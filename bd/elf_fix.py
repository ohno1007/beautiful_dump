"""Rebuild a usable ELF from a memory dump of libil2cpp.so.

Two strategies, in priority order:
  1. SoFixer (F8LEFT) if present in third_party/ - battle-tested for Android SO
  2. LIEF-based fallback that rewrites segment file offsets to match VAs

For Il2CppDumper to work, file offsets must equal virtual addresses since
in a memory dump the layout is page-aligned and includes BSS pages.
"""
from __future__ import annotations

import shutil
import struct
import subprocess
from pathlib import Path
from typing import Optional

THIRD_PARTY = Path(__file__).parent.parent / "third_party"


def _sofixer_binary() -> Optional[Path]:
    # SoFixer ships C++ source; user may have built it themselves.
    candidates = [
        THIRD_PARTY / "SoFixer" / "SoFixer-linux64",
        THIRD_PARTY / "SoFixer" / "SoFixer",
        Path(shutil.which("SoFixer") or "/nonexistent"),
    ]
    for c in candidates:
        if c.exists() and c.is_file():
            return c
    return None


def _read_load_base(path: Path) -> int:
    """First PT_LOAD's p_vaddr in the dumped image. Usually 0."""
    data = path.read_bytes()[:0x40 + 0x38 * 16]
    if data[:4] != b"\x7fELF":
        raise ValueError(f"{path} is not an ELF (memory dump corrupted?)")
    is64 = data[4] == 2
    if not is64:
        raise NotImplementedError("only ELF64 (arm64) supported")
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
    e_phnum = struct.unpack_from("<H", data, 0x38)[0]
    PT_LOAD = 1
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        if p_type == PT_LOAD:
            p_vaddr = struct.unpack_from("<Q", data, off + 0x10)[0]
            return p_vaddr
    return 0


def fix_with_sofixer(dumped: Path, fixed: Path, base: int) -> bool:
    binary = _sofixer_binary()
    if not binary:
        return False
    base_str = f"0x{base:x}"
    subprocess.run(
        [str(binary), "-m", base_str, "-s", str(dumped), "-o", str(fixed)],
        check=True,
    )
    return True


def fix_with_lief(dumped: Path, fixed: Path) -> None:
    """Pure-Python fallback. Rewrites segment file_offset := virtual_address."""
    import lief

    binary = lief.parse(str(dumped))
    if binary is None:
        raise RuntimeError(f"LIEF failed to parse {dumped}")

    for seg in binary.segments:
        if seg.type == lief.ELF.SEGMENT_TYPES.LOAD:
            seg.file_offset = seg.virtual_address
            seg.physical_size = seg.virtual_size

    for sec in binary.sections:
        if sec.virtual_address:
            sec.offset = sec.virtual_address

    if binary.has(lief.ELF.DYNAMIC_TAGS.INIT_ARRAY):
        # Initialisers can carry stale absolute pointers; clearing prevents
        # downstream tools from chasing into invalid memory.
        try:
            binary.remove(lief.ELF.DYNAMIC_TAGS.INIT_ARRAY)
            binary.remove(lief.ELF.DYNAMIC_TAGS.INIT_ARRAYSZ)
        except Exception:
            pass

    builder = lief.ELF.Builder(binary)
    builder.build()
    builder.write(str(fixed))


def fix(dumped: Path, fixed: Optional[Path] = None) -> Path:
    fixed = fixed or dumped.with_name(dumped.stem + ".fixed.so")
    base = _read_load_base(dumped)
    if fix_with_sofixer(dumped, fixed, base):
        return fixed
    fix_with_lief(dumped, fixed)
    return fixed
