#!/usr/bin/env bash
# Beautiful dump - one-shot setup
# Downloads and prepares third-party OSS tools used by the pipeline.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TP="$ROOT/third_party"
mkdir -p "$TP"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
log() { echo -e "${GREEN}[+]${NC} $*"; }
warn() { echo -e "${YELLOW}[!]${NC} $*"; }
fail() { echo -e "${RED}[x]${NC} $*"; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }

# --- Python deps ---
log "installing Python deps"
if have pip; then pip install -r "$ROOT/requirements.txt"
elif have pip3; then pip3 install -r "$ROOT/requirements.txt"
else fail "pip not found"; fi

# --- Il2CppDumper (Perfare) ---
# https://github.com/Perfare/Il2CppDumper - cross-platform .NET tool.
IL2CPP_VER="${IL2CPP_DUMPER_VER:-v6.7.46}"
IL2CPP_DIR="$TP/Il2CppDumper"
if [ ! -f "$IL2CPP_DIR/Il2CppDumper.dll" ]; then
  log "downloading Il2CppDumper $IL2CPP_VER"
  rm -rf "$IL2CPP_DIR" && mkdir -p "$IL2CPP_DIR"
  URL="https://github.com/Perfare/Il2CppDumper/releases/download/$IL2CPP_VER/Il2CppDumper-net6-$IL2CPP_VER.zip"
  curl -fL "$URL" -o "$TP/_il2cpp.zip" || fail "download failed"
  unzip -q "$TP/_il2cpp.zip" -d "$IL2CPP_DIR"
  rm "$TP/_il2cpp.zip"
fi
if ! have dotnet; then
  warn "dotnet not installed - Il2CppDumper needs .NET 6 runtime"
  warn "  ubuntu: sudo apt install -y dotnet-runtime-6.0"
  warn "  macos:  brew install --cask dotnet"
fi

# --- SoFixer (F8LEFT) for ELF reconstruction ---
# https://github.com/F8LEFT/SoFixer  Optional; we have a LIEF-based fallback.
SOFIXER_DIR="$TP/SoFixer"
if [ ! -d "$SOFIXER_DIR/.git" ]; then
  log "cloning SoFixer (optional)"
  git clone --depth 1 https://github.com/F8LEFT/SoFixer.git "$SOFIXER_DIR" || warn "SoFixer clone failed (optional)"
fi

# --- Zygisk-Il2CppDumper (Perfare) ---
# https://github.com/Perfare/Zygisk-Il2CppDumper - auto memory dump on rooted device
ZYG_VER="${ZYGISK_DUMPER_VER:-v3.4.5}"
ZYG_DIR="$TP/Zygisk-Il2CppDumper"
if [ ! -f "$ZYG_DIR/zygisk-il2cppdumper.zip" ]; then
  log "downloading Zygisk-Il2CppDumper $ZYG_VER"
  mkdir -p "$ZYG_DIR"
  URL="https://github.com/Perfare/Zygisk-Il2CppDumper/releases/download/$ZYG_VER/zygisk-il2cppdumper-$ZYG_VER-release.zip"
  curl -fL "$URL" -o "$ZYG_DIR/zygisk-il2cppdumper.zip" || warn "Zygisk dumper download failed (optional)"
fi

# --- Frida server check ---
if have adb && adb get-state >/dev/null 2>&1; then
  ARCH=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
  log "device arch: $ARCH"
  if adb shell "su -c 'ls /data/local/tmp/frida-server'" >/dev/null 2>&1; then
    log "frida-server present on device"
  else
    warn "frida-server not on device. Download arm64 binary from:"
    warn "  https://github.com/frida/frida/releases (frida-server-*-android-arm64.xz)"
    warn "Push with: adb push frida-server /data/local/tmp/"
  fi
else
  warn "no adb device detected (will be required for dump)"
fi

log "setup done. See README.md for usage."
