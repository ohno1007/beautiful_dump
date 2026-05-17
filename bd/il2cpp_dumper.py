"""Thin wrapper around Perfare/Il2CppDumper."""
from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path
from typing import Optional

THIRD_PARTY = Path(__file__).parent.parent / "third_party"
IL2CPP_DLL = THIRD_PARTY / "Il2CppDumper" / "Il2CppDumper.dll"


def is_available() -> bool:
    return IL2CPP_DLL.exists() and shutil.which("dotnet") is not None


def write_config(out_dir: Path, **overrides) -> Path:
    """Il2CppDumper reads config.json from cwd. We write one beside the output."""
    cfg = {
        "DumpMethod": True,
        "DumpField": True,
        "DumpProperty": True,
        "DumpAttribute": False,
        "DumpFieldOffset": True,
        "DumpMethodOffset": True,
        "DumpTypeDefIndex": True,
        "GenerateDummyDll": True,
        "GenerateScript": True,
        "RequireAnyKey": False,
        "ForceIl2CppVersion": False,
        "ForceVersion": 24.5,
        "ForceDump": False,
        "NoRedirectedPointer": False,
    }
    cfg.update(overrides)
    p = out_dir / "config.json"
    p.write_text(json.dumps(cfg, indent=2))
    return p


def run(libil2cpp: Path, metadata: Path, out_dir: Path) -> Path:
    """Invoke Il2CppDumper.  Returns the output directory."""
    if not is_available():
        raise RuntimeError(
            "Il2CppDumper not available. Run ./setup.sh and install .NET 6 runtime."
        )
    out_dir.mkdir(parents=True, exist_ok=True)
    write_config(out_dir)

    # Il2CppDumper writes to cwd, so run it in out_dir
    cmd = ["dotnet", str(IL2CPP_DLL), str(libil2cpp.resolve()), str(metadata.resolve())]
    print(f"[*] running: {' '.join(cmd)} (cwd={out_dir})")
    subprocess.run(cmd, check=True, cwd=out_dir)
    return out_dir


def find_outputs(out_dir: Path) -> dict:
    """Locate the standard Il2CppDumper outputs."""
    return {
        "dump_cs": out_dir / "dump.cs",
        "script_json": out_dir / "script.json",
        "stringliteral_json": out_dir / "stringliteral.json",
        "dummy_dll": out_dir / "DummyDll",
        "il2cpp_h": out_dir / "il2cpp.h",
    }
