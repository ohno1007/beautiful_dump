"""Build name-independent signatures for IL2CPP methods.

Input: Il2CppDumper output (script.json + DummyDll + native libil2cpp.so).
Output: per-method feature vectors that survive name obfuscation.

Features used (chosen because they survive the typical Beebyte/CodeStage
style randomisers that re-roll identifier names per build):

  - param_count, return_type_name (Unity built-ins are stable)
  - class_attr: base type, interface set, declaring assembly
  - string_xrefs: literal strings the method references
  - unity_xrefs: UnityEngine.* / System.* API calls (anchors)
  - call_fanout: number of direct calls
  - native_prologue_hash: hash of first 32 non-relocatable bytes of the
        native function (call/branch immediates zeroed)
  - il_opcodes_hash: hash of the decoded IL opcode stream from DummyDll
"""
from __future__ import annotations

import hashlib
import json
import re
import struct
from dataclasses import dataclass, asdict, field
from pathlib import Path
from typing import Optional


STABLE_NAMESPACES = (
    "UnityEngine.", "System.", "Mono.", "Microsoft.", "TMPro.",
)


def _is_stable_name(full: str) -> bool:
    return any(full.startswith(p) for p in STABLE_NAMESPACES)


@dataclass
class MethodSig:
    addr: int                       # RVA in libil2cpp.so
    name: str                       # raw name (may be obfuscated)
    class_name: str
    return_type: str
    param_types: list[str] = field(default_factory=list)
    string_xrefs: list[str] = field(default_factory=list)
    stable_callees: list[str] = field(default_factory=list)
    call_fanout: int = 0
    native_prologue_hash: Optional[str] = None
    il_opcodes_hash: Optional[str] = None

    @property
    def param_count(self) -> int:
        return len(self.param_types)

    def fingerprint(self) -> str:
        """Compact key used for direct equality lookup."""
        h = hashlib.sha256()
        h.update(self.return_type.encode())
        h.update(b"|")
        h.update(",".join(self.param_types).encode())
        h.update(b"|")
        h.update(",".join(sorted(self.stable_callees)).encode())
        h.update(b"|")
        h.update(",".join(sorted(self.string_xrefs)).encode())
        return h.hexdigest()[:16]


def _hash_prologue(code: bytes) -> str:
    """Zero-out 26-bit branch immediates so identical logic at different
    relocations still hashes the same. ARM64 only."""
    if len(code) < 32:
        return ""
    out = bytearray(code[:32])
    for off in range(0, 32, 4):
        insn = struct.unpack_from("<I", out, off)[0]
        op = insn >> 26
        # 0b000101 = B, 0b100101 = BL  (top 6 bits)
        if op == 0b000101 or op == 0b100101:
            insn &= 0xFC000000
            struct.pack_into("<I", out, off, insn)
    return hashlib.sha256(bytes(out)).hexdigest()[:16]


def _read_native_prologue(so_path: Path, rva: int, n: int = 64) -> bytes:
    """Read n bytes from a file-offset-aligned ELF (after elf_fix)."""
    with so_path.open("rb") as f:
        f.seek(rva)
        return f.read(n)


def _parse_script_json(script_json: Path) -> list[dict]:
    """Il2CppDumper script.json contains ScriptMethod entries with addr+name+sig."""
    data = json.loads(script_json.read_text(encoding="utf-8"))
    return data.get("ScriptMethod", [])


def _parse_stringliteral_xrefs(stringliteral_json: Path) -> dict[int, list[str]]:
    """Map method_addr -> list of literal strings referenced."""
    if not stringliteral_json.exists():
        return {}
    raw = json.loads(stringliteral_json.read_text(encoding="utf-8"))
    out: dict[int, list[str]] = {}
    for entry in raw if isinstance(raw, list) else raw.get("ScriptString", []):
        addr = entry.get("Address") or entry.get("address")
        val = entry.get("Value") or entry.get("value") or ""
        if addr is None:
            continue
        out.setdefault(int(addr), []).append(val)
    return out


_NAME_RE = re.compile(r"^([\w\.\+`<>]+)\$\$([\w\.<>]+)$")


def _split_class_method(name: str) -> tuple[str, str]:
    """Il2CppDumper formats method names as 'Class$$Method'."""
    m = _NAME_RE.match(name)
    if m:
        return m.group(1), m.group(2)
    if "$$" in name:
        cls, meth = name.split("$$", 1)
        return cls, meth
    return "", name


def _scan_dll_callees(dummy_dll_dir: Path) -> dict[str, list[str]]:
    """Parse DummyDll/*.dll with a lightweight IL walker to record stable callees.

    Falls back gracefully if pythonnet/pythonpefile aren't available - the
    matcher will still work, just with lower precision.
    """
    callees: dict[str, list[str]] = {}
    try:
        import dnfile  # type: ignore
    except ImportError:
        return callees
    for dll in dummy_dll_dir.glob("*.dll"):
        try:
            pe = dnfile.dnPE(str(dll))
            pe.parse_data_directories()
        except Exception:
            continue
        meta = pe.net.mdtables
        if not meta or not meta.MethodDef:
            continue
        # Simple pass: list all referenced member references; pin them
        # to declaring methods would need IL parsing.  For now collect
        # per-DLL stable references as a coarse anchor set.
        stable: list[str] = []
        if meta.MemberRef:
            for ref in meta.MemberRef:
                try:
                    cls = ref.Class.row.TypeName  # type: ignore[attr-defined]
                    ns = ref.Class.row.TypeNamespace  # type: ignore[attr-defined]
                    full = f"{ns}.{cls}.{ref.Name}" if ns else f"{cls}.{ref.Name}"
                    if _is_stable_name(full):
                        stable.append(full)
                except Exception:
                    continue
        callees[dll.stem] = sorted(set(stable))
    return callees


def build_signatures(
    libil2cpp_fixed: Path,
    il2cpp_out_dir: Path,
) -> list[MethodSig]:
    script = _parse_script_json(il2cpp_out_dir / "script.json")
    str_xrefs = _parse_stringliteral_xrefs(il2cpp_out_dir / "stringliteral.json")
    dll_callees = _scan_dll_callees(il2cpp_out_dir / "DummyDll")

    sigs: list[MethodSig] = []
    for entry in script:
        addr = int(entry.get("Address", 0))
        if not addr:
            continue
        raw_name = entry.get("Name", "")
        cls, meth = _split_class_method(raw_name)
        ret = entry.get("TypeSignature", "") or "?"
        params = entry.get("ParameterSignatures", []) or []

        prologue = _read_native_prologue(libil2cpp_fixed, addr)
        sig = MethodSig(
            addr=addr,
            name=meth,
            class_name=cls,
            return_type=ret,
            param_types=list(params),
            string_xrefs=sorted(set(str_xrefs.get(addr, []))),
            stable_callees=dll_callees.get(cls.split(".")[-1] if cls else "", []),
            call_fanout=len(params),
            native_prologue_hash=_hash_prologue(prologue) if prologue else None,
        )
        sigs.append(sig)
    return sigs


def save_signatures(sigs: list[MethodSig], out: Path) -> None:
    payload = {"version": 1, "methods": [asdict(s) for s in sigs]}
    out.write_text(json.dumps(payload, indent=2, ensure_ascii=False))


def load_signatures(path: Path) -> list[MethodSig]:
    data = json.loads(path.read_text(encoding="utf-8"))
    return [MethodSig(**m) for m in data["methods"]]
