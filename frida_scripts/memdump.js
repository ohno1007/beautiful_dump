'use strict';

// Beautiful dump - Frida memory dumper for IL2CPP Unity games
// 通过内存dump绕过 global-metadata.dat 加密 / so 加壳

const SAVE_DIR_DEFAULT = '/data/local/tmp/bd_out';
let SAVE_DIR = SAVE_DIR_DEFAULT;

function log(msg) { send({ type: 'log', msg: String(msg) }); }
function err(msg) { send({ type: 'err', msg: String(msg) }); }
function info(payload) { send({ type: 'info', payload: payload }); }

function ensureDir(path) {
  const mkdirPtr = Module.findExportByName(null, 'mkdir');
  if (!mkdirPtr) return;
  const mkdir = new NativeFunction(mkdirPtr, 'int', ['pointer', 'int']);
  // recursive
  const parts = path.split('/').filter(p => p.length);
  let cur = '';
  for (const p of parts) {
    cur += '/' + p;
    mkdir(Memory.allocUtf8String(cur), 0o755);
  }
}

function writeBytes(filePath, base, size) {
  const f = new File(filePath, 'wb');
  const CHUNK = 0x100000;
  let off = 0;
  let badPages = 0;
  while (off < size) {
    const n = Math.min(CHUNK, size - off);
    try {
      const buf = base.add(off).readByteArray(n);
      f.write(buf);
    } catch (_) {
      badPages++;
      // Walk per-page so a single bad page doesn't lose 1MB
      for (let i = 0; i < n; i += 0x1000) {
        const pageSize = Math.min(0x1000, n - i);
        try {
          f.write(base.add(off + i).readByteArray(pageSize));
        } catch (_) {
          f.write(new Uint8Array(pageSize).buffer);
        }
      }
    }
    off += n;
  }
  f.close();
  return { size, badPages };
}

function dumpModule(modName, outName) {
  const m = Process.findModuleByName(modName);
  if (!m) {
    err(`module ${modName} not loaded`);
    return null;
  }
  const out = SAVE_DIR + '/' + outName;
  log(`dumping ${modName} base=${m.base} size=0x${m.size.toString(16)} -> ${out}`);
  const r = writeBytes(out, m.base, m.size);
  log(`  done (${r.size} bytes, ${r.badPages} unreadable chunks)`);
  return { name: modName, base: m.base.toString(), size: m.size, path: out };
}

const META_MAGIC_LE = 'af 1b b1 fa';

function scanMetadata(customMagicLE) {
  const magic = customMagicLE || META_MAGIC_LE;
  const matches = [];
  const ranges = [].concat(
    Process.enumerateRanges({ protection: 'r--', coalesce: true }),
    Process.enumerateRanges({ protection: 'rw-', coalesce: true })
  );
  for (const r of ranges) {
    if (r.size > 0x20000000) continue; // skip giant ranges
    try {
      Memory.scanSync(r.base, r.size, magic).forEach(hit => {
        try {
          const ver = hit.address.add(4).readU32();
          if (ver >= 16 && ver <= 35) {
            matches.push({ addr: hit.address, version: ver, rangeBase: r.base, rangeSize: r.size });
          }
        } catch (_) { /* invalid header */ }
      });
    } catch (_) { /* range gone */ }
  }
  return matches;
}

function computeMetaSize(addr) {
  let maxEnd = 256;
  for (let i = 8; i < 0x400; i += 8) {
    try {
      const off = addr.add(i).readU32();
      const sz = addr.add(i + 4).readU32();
      if (off === 0 && sz === 0) continue;
      if (off > 0x40000000 || sz > 0x40000000) break;
      if (off + sz > maxEnd) maxEnd = off + sz;
    } catch (_) { break; }
  }
  // Round to page
  return (maxEnd + 0xFFF) & ~0xFFF;
}

function dumpMetadata(customMagic) {
  const cands = scanMetadata(customMagic);
  if (!cands.length) {
    err('global-metadata magic not found, try --magic <hex> if game uses custom magic');
    return null;
  }
  log(`found ${cands.length} metadata candidate(s)`);
  let best = null;
  let bestSize = 0;
  for (const c of cands) {
    const sz = computeMetaSize(c.addr);
    log(`  candidate @ ${c.addr} version=${c.version} computed_size=0x${sz.toString(16)}`);
    if (sz > bestSize && sz < 0x10000000) { best = c; bestSize = sz; }
  }
  if (!best) { err('no valid metadata candidate'); return null; }
  const out = SAVE_DIR + '/global-metadata.dat';
  log(`dumping metadata version=${best.version} size=0x${bestSize.toString(16)} -> ${out}`);
  const r = writeBytes(out, best.addr, bestSize);
  log(`  done (${r.size} bytes)`);
  return { path: out, size: bestSize, version: best.version, addr: best.addr.toString() };
}

function dumpMaps() {
  const out = SAVE_DIR + '/maps.txt';
  const f = new File(out, 'w');
  Process.enumerateModules().forEach(m => {
    f.write(`${m.base} - ${m.base.add(m.size)} ${m.name} size=0x${m.size.toString(16)}\n`);
  });
  f.close();
  return out;
}

rpc.exports = {
  setSaveDir: function (dir) {
    SAVE_DIR = dir || SAVE_DIR_DEFAULT;
    ensureDir(SAVE_DIR);
    return SAVE_DIR;
  },
  dumpAll: function (opts) {
    opts = opts || {};
    ensureDir(SAVE_DIR);
    const result = { saveDir: SAVE_DIR };
    result.maps = dumpMaps();
    result.libil2cpp = dumpModule('libil2cpp.so', 'libil2cpp.so');
    result.libunity = dumpModule('libunity.so', 'libunity.so');
    if (opts.extraModules) {
      result.extra = [];
      for (const name of opts.extraModules) {
        result.extra.push(dumpModule(name, name));
      }
    }
    result.metadata = dumpMetadata(opts.magic);
    info(result);
    return result;
  },
};
