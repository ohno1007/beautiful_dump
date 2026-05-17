#!/usr/bin/env python3
"""beautiful_dump - U3D/IL2CPP anti-obfuscation dump pipeline.

End-to-end CLI that orchestrates:
  - Frida memory dump (or Zygisk-Il2CppDumper)
  - ELF reconstruction
  - Il2CppDumper
  - cross-version signature matching
  - readable SDK export
"""
from __future__ import annotations

import sys
from pathlib import Path
from typing import Optional

import click

from bd import dump as dump_mod
from bd import elf_fix
from bd import il2cpp_dumper
from bd import matcher
from bd import sdk_export
from bd import signature
from bd import zygisk_dump


@click.group()
def cli():
    """beautiful_dump - 优雅、广泛、抗混淆的 U3D dumper."""


@cli.command("dump")
@click.option("--package", "-p", required=True, help="Target package name or PID")
@click.option("--spawn/--attach", default=False, help="Spawn vs attach")
@click.option("--out", "-o", "out_dir", type=click.Path(path_type=Path), default=None)
@click.option("--magic", default=None, help="Custom metadata magic in hex (e.g. 'de ad be ef')")
@click.option("--engine", type=click.Choice(["frida", "zygisk"]), default="frida")
@click.option("--wait", type=float, default=10.0, help="Seconds to wait after spawn before dumping")
def cmd_dump(package, spawn, out_dir, magic, engine, wait):
    """Step 1 - dump libil2cpp.so + global-metadata.dat from a running app."""
    out_dir = out_dir or Path("./out") / package.replace(".", "_")
    if engine == "zygisk":
        zygisk_dump.run(package, out_dir)
    else:
        res = dump_mod.run_dump(
            target=package, spawn=spawn,
            save_dir_local=out_dir,
            magic=magic,
            wait_after_attach=wait,
        )
        click.echo(f"[+] dump complete: {res.save_dir_local}")
        click.echo(f"    libil2cpp base=0x{res.libil2cpp_base:x} size=0x{res.libil2cpp_size:x}")
        if res.metadata_version:
            click.echo(f"    metadata version={res.metadata_version} size={res.metadata_size}")


@cli.command("fix")
@click.argument("dumped", type=click.Path(exists=True, path_type=Path))
@click.option("--out", "-o", type=click.Path(path_type=Path), default=None)
def cmd_fix(dumped, out):
    """Step 2 - rebuild a usable ELF from the memory dump."""
    fixed = elf_fix.fix(dumped, out)
    click.echo(f"[+] fixed: {fixed}")


@cli.command("parse")
@click.argument("libil2cpp", type=click.Path(exists=True, path_type=Path))
@click.argument("metadata", type=click.Path(exists=True, path_type=Path))
@click.option("--out", "-o", type=click.Path(path_type=Path), default=None)
def cmd_parse(libil2cpp, metadata, out):
    """Step 3 - run Il2CppDumper to produce dump.cs / DummyDll / script.json."""
    out = out or libil2cpp.parent / "il2cpp_out"
    il2cpp_dumper.run(libil2cpp, metadata, out)
    found = il2cpp_dumper.find_outputs(out)
    for k, v in found.items():
        marker = "OK" if v.exists() else "missing"
        click.echo(f"  [{marker}] {k}: {v}")


@cli.command("sig")
@click.argument("libil2cpp_fixed", type=click.Path(exists=True, path_type=Path))
@click.argument("il2cpp_out", type=click.Path(exists=True, path_type=Path))
@click.option("--out", "-o", type=click.Path(path_type=Path), default=None)
def cmd_sig(libil2cpp_fixed, il2cpp_out, out):
    """Step 4 - extract name-independent signatures for matching."""
    sigs = signature.build_signatures(libil2cpp_fixed, il2cpp_out)
    out = out or il2cpp_out / "signatures.json"
    signature.save_signatures(sigs, out)
    click.echo(f"[+] {len(sigs)} signatures -> {out}")


@cli.command("match")
@click.argument("src_sigs", type=click.Path(exists=True, path_type=Path))
@click.argument("dst_sigs", type=click.Path(exists=True, path_type=Path))
@click.option("--out", "-o", type=click.Path(path_type=Path), default=None)
@click.option("--min-score", type=float, default=3.0)
def cmd_match(src_sigs, dst_sigs, out, min_score):
    """Step 5 - cross-version match: recover stable names between two dumps."""
    src = signature.load_signatures(src_sigs)
    dst = signature.load_signatures(dst_sigs)
    matches = matcher.match(src, dst, min_score=min_score)
    out = out or src_sigs.parent / "matches.json"
    matcher.save_mapping(matches, out)
    s = matcher.stats(matches)
    click.echo(f"[+] matched {s.get('count', 0)} methods -> {out}")
    click.echo(f"    high-confidence (score>=7): {s.get('high_conf', 0)}")
    click.echo(f"    avg score: {s.get('avg_score', 0):.2f}")


@cli.command("sdk")
@click.argument("sigs", type=click.Path(exists=True, path_type=Path))
@click.option("--out", "-o", type=click.Path(path_type=Path), default=Path("./sdk"))
@click.option("--module", default="libil2cpp.so")
def cmd_sdk(sigs, out, module):
    """Step 6 - export the readable SDK (header + IDA + Frida)."""
    sig_list = signature.load_signatures(sigs)
    files = sdk_export.export_all(sig_list, out, module)
    for k, v in files.items():
        click.echo(f"  {k}: {v}")


@cli.command("all")
@click.option("--package", "-p", required=True)
@click.option("--spawn/--attach", default=True)
@click.option("--out", "-o", "out_dir", type=click.Path(path_type=Path), default=None)
@click.option("--magic", default=None)
@click.option("--wait", type=float, default=10.0)
def cmd_all(package, spawn, out_dir, magic, wait):
    """All-in-one: dump -> fix -> parse -> signatures -> SDK."""
    out_dir = out_dir or Path("./out") / package.replace(".", "_")
    out_dir.mkdir(parents=True, exist_ok=True)

    click.echo("=== [1/5] memory dump ===")
    res = dump_mod.run_dump(
        target=package, spawn=spawn,
        save_dir_local=out_dir,
        magic=magic,
        wait_after_attach=wait,
    )

    dumped = out_dir / "libil2cpp.so"
    if not dumped.exists():
        sys.exit("libil2cpp.so missing in dump")

    click.echo("=== [2/5] ELF fix ===")
    fixed = elf_fix.fix(dumped)

    click.echo("=== [3/5] Il2CppDumper ===")
    parsed = out_dir / "il2cpp_out"
    il2cpp_dumper.run(fixed, out_dir / "global-metadata.dat", parsed)

    click.echo("=== [4/5] signatures ===")
    sigs = signature.build_signatures(fixed, parsed)
    sig_path = out_dir / "signatures.json"
    signature.save_signatures(sigs, sig_path)
    click.echo(f"    {len(sigs)} signatures -> {sig_path}")

    click.echo("=== [5/5] SDK export ===")
    sdk_dir = out_dir / "sdk"
    files = sdk_export.export_all(sigs, sdk_dir)
    for k, v in files.items():
        click.echo(f"    {k}: {v}")

    click.echo(f"\n[+] done. SDK ready at {sdk_dir}")
    click.echo(f"    Next: re-run with another version, then `beautiful_dump match`.")


if __name__ == "__main__":
    cli()
