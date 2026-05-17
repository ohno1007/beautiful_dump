"""Install/use Zygisk-Il2CppDumper for automatic memory dump.

Workflow:
1. Push the Zygisk module zip to the device.
2. Place a config that targets the package.
3. Launch the app - module auto-dumps to /data/data/<pkg>/files/
4. Pull the dump directory.

Reference: https://github.com/Perfare/Zygisk-Il2CppDumper
Requires Magisk + Zygisk enabled (or KernelSU + ZygiskNext).
"""
from __future__ import annotations

import shlex
import subprocess
import time
from pathlib import Path
from typing import Optional

THIRD_PARTY = Path(__file__).parent.parent / "third_party" / "Zygisk-Il2CppDumper"


def adb(*args, check=True, capture=True):
    return subprocess.run(["adb", *args], check=check, capture_output=capture, text=True)


def is_installed() -> bool:
    """Crude check: zygisk-il2cppdumper directory in /data/adb/modules/."""
    try:
        out = adb("shell", "su", "-c", "ls /data/adb/modules/ 2>/dev/null").stdout
        return "zygisk-il2cppdumper" in out or "zygisk_il2cppdumper" in out
    except subprocess.CalledProcessError:
        return False


def install_module(zip_path: Optional[Path] = None) -> None:
    zip_path = zip_path or (THIRD_PARTY / "zygisk-il2cppdumper.zip")
    if not zip_path.exists():
        raise FileNotFoundError(f"{zip_path} missing - run ./setup.sh first")
    adb("push", str(zip_path), "/data/local/tmp/zyg-il2cpp.zip")
    adb("shell", "su", "-c", "magisk --install-module /data/local/tmp/zyg-il2cpp.zip")
    print("[*] module flashed - REBOOT the device, then re-run the dump command")


def configure_target(package: str) -> None:
    """Write the per-package config so the module dumps only that app."""
    cfg = (
        '{\n'
        f'  "package_name": "{package}"\n'
        '}'
    )
    target_dir = "/data/local/tmp/il2cppdumper"
    adb("shell", "su", "-c", f"mkdir -p {target_dir}")
    # Use printf via shell - quoting via shlex
    adb("shell", "su", "-c", f"echo {shlex.quote(cfg)} > {target_dir}/config.json")


def wait_for_dump(package: str, timeout: float = 120.0) -> str:
    """Poll for the dump directory inside the app's files dir."""
    out_dir = f"/data/data/{package}/files/il2cpp_dump"
    deadline = time.time() + timeout
    while time.time() < deadline:
        r = adb("shell", "su", "-c", f"ls {out_dir} 2>/dev/null", check=False)
        if r.returncode == 0 and "global-metadata.dat" in r.stdout:
            return out_dir
        time.sleep(2)
    raise TimeoutError(f"no dump appeared at {out_dir} within {timeout}s")


def pull_dump(remote_dir: str, local_dir: Path) -> list[str]:
    local_dir.mkdir(parents=True, exist_ok=True)
    listing = adb("shell", "su", "-c", f"ls -1 {remote_dir}").stdout.splitlines()
    files: list[str] = []
    for fname in listing:
        fname = fname.strip()
        if not fname:
            continue
        tmp = f"/data/local/tmp/_pull_{fname}"
        adb("shell", "su", "-c", f"cp {remote_dir}/{fname} {tmp} && chmod 644 {tmp}")
        adb("pull", tmp, str(local_dir / fname))
        adb("shell", "rm", "-f", tmp)
        files.append(fname)
    return files


def run(package: str, local_out: Path, wait_timeout: float = 120.0) -> Path:
    """End-to-end: configure, force-stop, relaunch, wait, pull."""
    if not is_installed():
        raise RuntimeError(
            "Zygisk-Il2CppDumper not installed.\n"
            "  bd.zygisk_dump.install_module() first, then reboot."
        )
    configure_target(package)
    adb("shell", "am", "force-stop", package)
    adb("shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1")
    print(f"[*] launched {package}, waiting for module to dump...")
    remote = wait_for_dump(package, wait_timeout)
    print(f"[*] dump ready at {remote}, pulling")
    pull_dump(remote, local_out)
    return local_out
