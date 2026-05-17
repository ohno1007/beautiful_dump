"""Export a readable SDK from matched signatures.

Produces three artefacts that together form the "sustainable SDK":

  - sdk.h            : C-style header with stable typedefs + extern decls
  - sdk_ida.py       : IDAPython script that renames addresses in current IDB
  - sdk_frida.js     : Frida helper that resolves stable names at runtime
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Optional

from .matcher import Match
from .signature import MethodSig


_SANITIZE = re.compile(r"[^A-Za-z0-9_]")


def _sanitize(name: str) -> str:
    s = _SANITIZE.sub("_", name)
    if not s or s[0].isdigit():
        s = "_" + s
    return s


def _stable_name(sig: MethodSig, fallback_idx: int) -> str:
    """Derive a stable, readable identifier from anchors."""
    # Prefer string literals as the basis for stable naming
    if sig.string_xrefs:
        anchor = max(sig.string_xrefs, key=len)
        anchor = anchor[:32]
        return _sanitize(f"{sig.class_name}__{sig.name}__{anchor}")
    if sig.stable_callees:
        anchor = sig.stable_callees[0].split(".")[-1]
        return _sanitize(f"{sig.class_name}__{sig.name}__via_{anchor}")
    return _sanitize(f"{sig.class_name}__{sig.name}__{fallback_idx:06x}")


def export_header(sigs: list[MethodSig], out: Path, module: str = "libil2cpp.so") -> None:
    lines = [
        "// beautiful_dump - generated SDK header",
        f"// module: {module}",
        "// Stable names derived from string/callee anchors so they survive name obfuscation.",
        "#pragma once",
        "#include <stdint.h>",
        "",
        f"#define BD_MODULE_NAME \"{module}\"",
        "",
    ]
    seen: set[str] = set()
    for i, s in enumerate(sigs):
        name = _stable_name(s, i)
        while name in seen:
            name += "_"
        seen.add(name)
        lines.append(
            f"// {s.class_name}::{s.name} ret={s.return_type} params={s.param_types}"
        )
        lines.append(f"#define BD_{name}_RVA 0x{s.addr:x}")
    out.write_text("\n".join(lines))


def export_ida_script(sigs: list[MethodSig], out: Path) -> None:
    body = [
        "# beautiful_dump - IDA renamer (Python 3, IDA 7.5+)",
        "import idc, ida_name, ida_funcs",
        "",
        "RENAMES = [",
    ]
    seen: set[str] = set()
    for i, s in enumerate(sigs):
        name = _stable_name(s, i)
        while name in seen:
            name += "_"
        seen.add(name)
        body.append(f"    (0x{s.addr:x}, {name!r}),")
    body += [
        "]",
        "",
        "def apply(base=None):",
        "    if base is None:",
        "        base = idaapi.get_imagebase()",
        "    for rva, name in RENAMES:",
        "        ea = base + rva",
        "        if ida_funcs.get_func(ea) is None:",
        "            ida_funcs.add_func(ea)",
        "        ida_name.set_name(ea, name, ida_name.SN_FORCE)",
        "    print(f'beautiful_dump: renamed {len(RENAMES)} functions')",
        "",
        "if __name__ == '__main__':",
        "    apply()",
    ]
    out.write_text("\n".join(body))


def export_frida_helper(sigs: list[MethodSig], out: Path, module: str = "libil2cpp.so") -> None:
    body = [
        "// beautiful_dump - Frida SDK resolver",
        f"const BD_MODULE = '{module}';",
        "const BD_BASE = Module.findBaseAddress(BD_MODULE);",
        "if (!BD_BASE) throw new Error(BD_MODULE + ' not loaded');",
        "",
        "const BD_RVAS = {",
    ]
    seen: set[str] = set()
    for i, s in enumerate(sigs):
        name = _stable_name(s, i)
        while name in seen:
            name += "_"
        seen.add(name)
        body.append(f"  '{name}': 0x{s.addr:x},")
    body += [
        "};",
        "",
        "function bd_addr(name) {",
        "  const rva = BD_RVAS[name];",
        "  if (rva === undefined) throw new Error('unknown SDK method: ' + name);",
        "  return BD_BASE.add(rva);",
        "}",
        "",
        "rpc.exports = { resolve: bd_addr, list: () => Object.keys(BD_RVAS) };",
    ]
    out.write_text("\n".join(body))


def export_mapping(matches: list[Match], out: Path) -> None:
    """For two-version flow: write a CSV / JSON of stable mappings."""
    rows = [
        {
            "stable_id": f"{_sanitize(m.src_name)}__{m.score:.1f}",
            "src_addr": f"0x{m.src_addr:x}",
            "src_name": m.src_name,
            "dst_addr": f"0x{m.dst_addr:x}",
            "dst_name": m.dst_name,
            "score": m.score,
            "reasons": m.reasons,
        }
        for m in matches
    ]
    out.write_text(json.dumps(rows, indent=2, ensure_ascii=False))


def export_all(sigs: list[MethodSig], out_dir: Path, module: str = "libil2cpp.so") -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    files = {
        "header": out_dir / "sdk.h",
        "ida": out_dir / "sdk_ida.py",
        "frida": out_dir / "sdk_frida.js",
    }
    export_header(sigs, files["header"], module)
    export_ida_script(sigs, files["ida"])
    export_frida_helper(sigs, files["frida"], module)
    return {k: str(v) for k, v in files.items()}
