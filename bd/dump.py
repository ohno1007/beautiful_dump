"""Orchestrate Frida memory dump on a rooted Android device."""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import frida

SCRIPT_PATH = Path(__file__).parent.parent / "frida_scripts" / "memdump.js"


@dataclass
class DumpResult:
    save_dir_remote: str
    save_dir_local: Path
    libil2cpp_base: Optional[int] = None
    libil2cpp_size: Optional[int] = None
    metadata_version: Optional[int] = None
    metadata_size: Optional[int] = None
    files: list[str] = field(default_factory=list)


def adb(*args, check=True, capture=True) -> subprocess.CompletedProcess:
    cmd = ["adb", *args]
    return subprocess.run(cmd, check=check, capture_output=capture, text=True)


def ensure_frida_server(version: Optional[str] = None) -> None:
    """Check frida-server is running on device. Does not auto-install."""
    out = adb("shell", "su", "-c", "pgrep -f frida-server || true").stdout.strip()
    if not out:
        sys.exit(
            "frida-server not running on device.\n"
            "Download matching arm64 server from https://github.com/frida/frida/releases\n"
            "Then: adb push frida-server /data/local/tmp/ && "
            "adb shell 'su -c \"chmod 755 /data/local/tmp/frida-server && /data/local/tmp/frida-server &\"'"
        )


def pull_dir(remote: str, local: Path) -> list[str]:
    local.mkdir(parents=True, exist_ok=True)
    files: list[str] = []
    listing = adb("shell", "su", "-c", f"ls -1 {remote}").stdout.splitlines()
    for fname in listing:
        fname = fname.strip()
        if not fname:
            continue
        remote_path = f"{remote}/{fname}"
        # Use cat via su to bypass permission, then write locally.
        # adb pull cannot use su, so we copy to a world-readable temp first.
        tmp = f"/data/local/tmp/_pull_{fname}"
        adb("shell", "su", "-c", f"cp {remote_path} {tmp} && chmod 644 {tmp}")
        adb("pull", tmp, str(local / fname))
        adb("shell", "rm", "-f", tmp)
        files.append(fname)
    return files


def run_dump(
    target: str,
    spawn: bool = False,
    save_dir_remote: str = "/data/local/tmp/bd_out",
    save_dir_local: Optional[Path] = None,
    magic: Optional[str] = None,
    extra_modules: Optional[list[str]] = None,
    wait_after_attach: float = 0.0,
) -> DumpResult:
    """Run the Frida memdump against `target` (package name or PID).

    If `spawn=True`, spawns the package and resumes after attach.
    """
    ensure_frida_server()
    save_dir_local = save_dir_local or Path("./out") / target.replace(".", "_")
    save_dir_local.mkdir(parents=True, exist_ok=True)

    # Wipe remote dir first
    adb("shell", "su", "-c", f"rm -rf {save_dir_remote} && mkdir -p {save_dir_remote} && chmod 777 {save_dir_remote}")

    device = frida.get_usb_device(timeout=10)

    if spawn:
        pid = device.spawn([target])
        session = device.attach(pid)
    else:
        try:
            pid = int(target)
            session = device.attach(pid)
        except ValueError:
            session = device.attach(target)
            pid = None

    script_src = SCRIPT_PATH.read_text()
    script = session.create_script(script_src)

    collected: dict = {}

    def on_message(msg, data):
        if msg["type"] == "send":
            payload = msg["payload"]
            t = payload.get("type")
            if t == "log":
                print(f"[dev] {payload['msg']}")
            elif t == "err":
                print(f"[dev:ERR] {payload['msg']}", file=sys.stderr)
            elif t == "info":
                collected["info"] = payload["payload"]
        elif msg["type"] == "error":
            print(f"[script error] {msg.get('description')}", file=sys.stderr)

    script.on("message", on_message)
    script.load()

    if spawn:
        device.resume(pid)
        # Wait for Unity to fully load libil2cpp and decrypt metadata
        time.sleep(max(8.0, wait_after_attach))
    elif wait_after_attach:
        time.sleep(wait_after_attach)

    script.exports_sync.set_save_dir(save_dir_remote)
    result = script.exports_sync.dump_all({
        "magic": magic,
        "extraModules": extra_modules or [],
    })

    session.detach()

    info = collected.get("info") or result
    libil2cpp = info.get("libil2cpp") or {}
    metadata = info.get("metadata") or {}

    print(f"[*] pulling files from {save_dir_remote} -> {save_dir_local}")
    files = pull_dir(save_dir_remote, save_dir_local)

    res = DumpResult(
        save_dir_remote=save_dir_remote,
        save_dir_local=save_dir_local,
        libil2cpp_base=int(libil2cpp.get("base"), 16) if libil2cpp.get("base") else None,
        libil2cpp_size=libil2cpp.get("size"),
        metadata_version=metadata.get("version"),
        metadata_size=metadata.get("size"),
        files=files,
    )

    # Persist a manifest for the rebuild step
    (save_dir_local / "dump_manifest.json").write_text(json.dumps({
        "libil2cpp_base": res.libil2cpp_base,
        "libil2cpp_size": res.libil2cpp_size,
        "metadata_version": res.metadata_version,
        "metadata_size": res.metadata_size,
        "files": files,
        "raw_info": info,
    }, indent=2, default=str))

    return res
