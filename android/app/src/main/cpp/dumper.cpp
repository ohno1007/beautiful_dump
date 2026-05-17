// beautiful_dump native dumper - arm64 Android, runs as root.

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

namespace {

constexpr size_t CHUNK = 1u << 20;            // 1 MiB I/O
constexpr size_t PAGE = 0x1000;
constexpr uint32_t IL2CPP_META_MAGIC_LE = 0xFAB11BAFu;
// Cap individual module dumps so a maps-layout glitch can't fill /sdcard.
constexpr size_t MODULE_MAX = 256u << 20;     // 256 MiB
constexpr size_t META_MAX   = 256u << 20;

struct MemRange {
    uintptr_t start = 0;
    uintptr_t end = 0;
    char perms[5] = {0};
    std::string path;
};

std::vector<MemRange> read_maps(int pid) {
    std::vector<MemRange> out;
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE* f = fopen(path, "r");
    if (!f) return out;
    char line[1024];
    while (fgets(line, sizeof(line), f)) {
        MemRange r;
        char perms[5] = {0};
        char file[512] = {0};
        if (sscanf(line, "%lx-%lx %4s %*x %*x:%*x %*d %511[^\n]",
                   &r.start, &r.end, perms, file) >= 3) {
            memcpy(r.perms, perms, sizeof(perms));
            r.path = file;
            out.push_back(std::move(r));
        }
    }
    fclose(f);
    return out;
}

int open_mem(int pid) {
    char p[64];
    snprintf(p, sizeof(p), "/proc/%d/mem", pid);
    return open(p, O_RDONLY | O_LARGEFILE);
}

bool ptrace_attach(int pid) {
    if (ptrace(PTRACE_ATTACH, pid, 0, 0) < 0) return false;
    int status;
    if (waitpid(pid, &status, 0) < 0) {
        ptrace(PTRACE_DETACH, pid, 0, 0);
        return false;
    }
    return true;
}

void ptrace_detach(int pid) { ptrace(PTRACE_DETACH, pid, 0, 0); }

ssize_t read_remote(int mem_fd, uintptr_t addr, void* buf, size_t n) {
    return pread64(mem_fd, buf, n, (off_t)addr);
}

size_t dump_range(int mem_fd, uintptr_t start, uintptr_t end, const char* out_path) {
    int fd = open(out_path, O_CREAT | O_WRONLY | O_TRUNC, 0644);
    if (fd < 0) return 0;
    std::vector<uint8_t> buf(CHUNK);
    size_t total = 0;
    for (uintptr_t off = 0; off < end - start; off += CHUNK) {
        size_t n = std::min((size_t)(end - start - off), CHUNK);
        ssize_t got = read_remote(mem_fd, start + off, buf.data(), n);
        if (got == (ssize_t)n) {
            write(fd, buf.data(), n);
            total += n;
            continue;
        }
        for (size_t i = 0; i < n; i += PAGE) {
            size_t ps = std::min(PAGE, n - i);
            uint8_t page[PAGE] = {0};
            ssize_t g = read_remote(mem_fd, start + off + i, page, ps);
            if (g <= 0) memset(page, 0, ps);
            write(fd, page, ps);
            total += ps;
        }
    }
    close(fd);
    return total;
}

// Find the FIRST contiguous cluster of segments belonging to a module by
// exact filename. ASLR-spread "shadow" mappings of the same name later
// in /proc/<pid>/maps are intentionally ignored - mixing them is what
// produced the 5.9 GiB nonsense dump.
struct Cluster { uintptr_t start = 0, end = 0; int segments = 0; };

Cluster find_first_cluster(const std::vector<MemRange>& maps, const char* name) {
    Cluster c;
    for (const auto& r : maps) {
        auto slash = r.path.rfind('/');
        std::string base = (slash != std::string::npos) ? r.path.substr(slash + 1) : r.path;
        if (base != name) {
            if (c.start) return c;       // hit non-match while inside cluster
            continue;
        }
        if (c.start == 0) {
            c.start = r.start; c.end = r.end; c.segments = 1;
        } else if (r.start == c.end) {       // strict adjacency only
            c.end = r.end;
            c.segments++;
        } else {
            return c;                         // any gap = different mapping, stop
        }
    }
    return c;
}

// Verify a memory address looks like an Il2CppGlobalMetadataHeader regardless
// of what magic value it carries. We require a plausible version number AND
// 15+ monotonically-increasing (offset, size) pairs in the header.
struct HeaderProbe {
    bool valid = false;
    uint32_t magic = 0;
    uint32_t version = 0;
    size_t total_size = 0;
};

HeaderProbe probe_header(const uint8_t* hdr, size_t hdr_size) {
    HeaderProbe p;
    if (hdr_size < 0x400) return p;
    uint32_t version;
    memcpy(&version, hdr + 4, 4);
    // Modern Unity range only.  Older versions almost never appear in
    // shipping games anymore and widening this is the main source of
    // structural false positives.
    if (version < 24 || version > 31) return p;

    uint32_t max_end = 0;
    uint32_t prev_end = 0;
    uint32_t first_off = 0;
    int valid_pairs = 0;
    for (size_t i = 8; i + 8 <= 0x400; i += 8) {
        uint32_t off, sz;
        memcpy(&off, hdr + i, 4);
        memcpy(&sz, hdr + i + 4, 4);
        if (off == 0 && sz == 0) continue;
        // Real IL2CPP tables: offsets are 4-byte aligned, monotonic,
        // first table starts past the header (≥0x100, ≤0x400),
        // sizes are reasonable.
        if (off & 0x3) return p;
        if (off < 0x100 || off > META_MAX) return p;
        if (sz == 0 || sz > META_MAX) return p;
        if (off < prev_end) return p;
        if (!first_off) first_off = off;
        prev_end = off + sz;
        if (prev_end > max_end) max_end = prev_end;
        valid_pairs++;
    }
    // Real headers have 30+ tables for v24+, first table within typical
    // header size, total payload in a sane range (1 MiB - 128 MiB).
    if (valid_pairs < 30) return p;
    if (first_off > 0x400) return p;
    if (max_end < (1u << 20) || max_end > (128u << 20)) return p;

    memcpy(&p.magic, hdr, 4);
    p.version = version;
    p.total_size = (max_end + 0xFFF) & ~0xFFFULL;
    p.valid = true;
    return p;
}

// Sample a candidate buffer and look for canonical IL2CPP/.NET strings.
// If we can't find any of these, the buffer is almost certainly not
// real metadata (encrypted stringblob notwithstanding) - reject it
// rather than dump 100 MiB of unrelated memory.
bool verify_metadata_strings(int mem_fd, uintptr_t base, size_t total_size) {
    static constexpr const char* markers[] = {
        "System.Object", "mscorlib", "UnityEngine", "Il2Cpp",
        "System.String", "<Module>",
    };
    constexpr size_t SAMPLE = 0x80000;   // 512 KiB per chunk
    std::vector<uint8_t> buf(SAMPLE);
    int hits = 0;
    int chunks = 6;
    for (int i = 0; i < chunks; i++) {
        size_t off = (total_size / chunks) * i;
        size_t to_read = std::min(SAMPLE, total_size - off);
        ssize_t got = read_remote(mem_fd, base + off, buf.data(), to_read);
        if (got <= 0) continue;
        for (auto m : markers) {
            size_t mlen = strlen(m);
            for (ssize_t j = 0; j + (ssize_t)mlen <= got; j++) {
                if (memcmp(buf.data() + j, m, mlen) == 0) { hits++; break; }
            }
            if (hits >= 2) return true;
        }
    }
    return hits >= 2;
}

struct MetaCandidate {
    uintptr_t addr;
    uint32_t magic;
    uint32_t version;
    size_t size;
    std::string source_range;
};

// Scan all readable mappings for ANY structurally-valid metadata header.
// Doesn't depend on a magic number, so it works on games that re-roll the
// magic field as part of their protection.
std::vector<MetaCandidate> scan_metadata(int mem_fd, const std::vector<MemRange>& maps,
                                          uint32_t require_magic = 0) {
    std::vector<MetaCandidate> out;
    std::vector<uint8_t> buf(CHUNK + 256);
    for (const auto& r : maps) {
        if (r.perms[0] != 'r') continue;
        size_t span = r.end - r.start;
        if (span > 0x10000000ULL) continue;
        for (size_t off = 0; off < span; off += CHUNK) {
            size_t n = std::min(CHUNK + 256, span - off);
            ssize_t got = read_remote(mem_fd, r.start + off, buf.data(), n);
            if (got < 256) continue;
            for (ssize_t i = 0; i + 256 <= got; i += 4) {
                if (require_magic) {
                    uint32_t m;
                    memcpy(&m, buf.data() + i, 4);
                    if (m != require_magic) continue;
                }
                auto p = probe_header(buf.data() + i, got - i);
                if (!p.valid) continue;
                out.push_back({
                    r.start + off + i, p.magic, p.version, p.total_size,
                    r.path.empty() ? "[anon]" : r.path,
                });
                // Skip past this candidate so we don't re-detect the same
                // buffer at offset+4, +8, … inside its own body.
                ssize_t skip = (ssize_t)std::min(p.total_size, (size_t)(got - i)) - 4;
                if (skip > 0) i += skip;
                if (out.size() >= 8) return out;
            }
        }
    }
    return out;
}

void dump_metadata_candidate(int mem_fd, const MetaCandidate& c,
                              const std::string& out_dir, int idx) {
    char outp[1024];
    if (idx == 0)
        snprintf(outp, sizeof(outp), "%s/global-metadata.dat", out_dir.c_str());
    else
        snprintf(outp, sizeof(outp), "%s/global-metadata.candidate%d.dat", out_dir.c_str(), idx);
    size_t got = dump_range(mem_fd, c.addr, c.addr + c.size, outp);

    // If the protection stripped the magic (or rolled a custom one), patch
    // 0xFAB11BAF back so Il2CppDumper on the host accepts the file.
    bool patched = false;
    if (c.magic != IL2CPP_META_MAGIC_LE && got > 4) {
        int fd = open(outp, O_RDWR);
        if (fd >= 0) {
            uint32_t fixed = IL2CPP_META_MAGIC_LE;
            patched = pwrite64(fd, &fixed, 4, 0) == 4;
            close(fd);
        }
    }

    printf("{\"event\":\"metadata\",\"idx\":%d,\"addr\":\"0x%lx\","
           "\"magic\":\"0x%08x\",\"version\":%u,\"size\":%zu,\"path\":\"%s\","
           "\"region\":\"%s\",\"magic_patched\":%s}\n",
           idx, c.addr, c.magic, c.version, got, outp,
           c.source_range.c_str(), patched ? "true" : "false");
    fflush(stdout);
}

// Pull identifier-like strings out of a dumped file. Useful when metadata
// extraction fails (custom protection) - class/method/field names baked
// into .rodata still survive name-randomisation across builds, so the
// result is a usable "what does this binary reference" SDK skeleton.
size_t extract_identifiers(const char* in_path, const char* out_path,
                            size_t min_len = 6) {
    FILE* in = fopen(in_path, "rb");
    if (!in) return 0;
    FILE* out = fopen(out_path, "w");
    if (!out) { fclose(in); return 0; }

    constexpr size_t BUF = 64 * 1024;
    std::vector<uint8_t> buf(BUF);
    std::string cur;
    cur.reserve(256);
    size_t emitted = 0;

    auto flush = [&](void) {
        if (cur.size() < min_len) return;
        // Must contain at least one letter and look like a path/identifier.
        bool has_alpha = false;
        bool plausible = true;
        for (char c : cur) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) has_alpha = true;
            // Allowed: letters, digits, _.<>+`/$:
            bool ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                      (c >= '0' && c <= '9') ||
                      c == '_' || c == '.' || c == '<' || c == '>' ||
                      c == '+' || c == '`' || c == '/' || c == '$' || c == ':';
            if (!ok) { plausible = false; break; }
        }
        if (has_alpha && plausible) {
            fprintf(out, "%s\n", cur.c_str());
            emitted++;
        }
    };

    while (size_t n = fread(buf.data(), 1, BUF, in)) {
        for (size_t i = 0; i < n; i++) {
            uint8_t c = buf[i];
            if (c >= 0x20 && c < 0x7f) cur += (char)c;
            else { flush(); cur.clear(); }
        }
    }
    flush();
    fclose(in);
    fclose(out);
    return emitted;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <pid> <out_dir> [magic_hex_le]\n", argv[0]);
        return 1;
    }
    int pid = atoi(argv[1]);
    const char* out_dir = argv[2];
    uint32_t custom_magic = 0;
    if (argc >= 4) custom_magic = (uint32_t)strtoul(argv[3], nullptr, 16);

    printf("{\"event\":\"start\",\"pid\":%d,\"out\":\"%s\",\"magic\":\"0x%x\"}\n",
           pid, out_dir, custom_magic ? custom_magic : IL2CPP_META_MAGIC_LE);
    fflush(stdout);

    if (mkdir(out_dir, 0755) != 0 && errno != EEXIST) {
        printf("{\"event\":\"fatal\",\"error\":\"mkdir %s: %s\"}\n", out_dir, strerror(errno));
        return 4;
    }

    bool attached = false;
    int mem_fd = open_mem(pid);
    if (mem_fd < 0) {
        printf("{\"event\":\"info\",\"msg\":\"open mem failed (%s), ptrace_attach\"}\n",
               strerror(errno));
        fflush(stdout);
        if (ptrace_attach(pid)) {
            attached = true;
            mem_fd = open_mem(pid);
        }
        if (mem_fd < 0) {
            printf("{\"event\":\"fatal\",\"error\":\"open /proc/%d/mem failed: %s\"}\n",
                   pid, strerror(errno));
            if (attached) ptrace_detach(pid);
            return 2;
        }
    }

    auto maps = read_maps(pid);
    printf("{\"event\":\"maps\",\"count\":%zu}\n", maps.size());
    fflush(stdout);

    // libil2cpp.so - first contiguous cluster only, capped.
    auto il = find_first_cluster(maps, "libil2cpp.so");
    if (il.start == 0) {
        printf("{\"event\":\"fatal\",\"error\":\"libil2cpp.so not mapped\"}\n");
        close(mem_fd); if (attached) ptrace_detach(pid);
        return 3;
    }
    size_t il_size = il.end - il.start;
    if (il_size > MODULE_MAX) {
        printf("{\"event\":\"warn\",\"msg\":\"libil2cpp.so cluster %zu MiB > cap, truncating to 256 MiB\"}\n",
               il_size >> 20);
        il_size = MODULE_MAX;
    }
    char outp[1024];
    snprintf(outp, sizeof(outp), "%s/libil2cpp.so", out_dir);
    size_t isz = dump_range(mem_fd, il.start, il.start + il_size, outp);
    printf("{\"event\":\"libil2cpp\",\"base\":\"0x%lx\",\"size\":%zu,"
           "\"segments\":%d,\"path\":\"%s\"}\n",
           il.start, isz, il.segments, outp);
    fflush(stdout);

    // libunity.so - same logic
    auto un = find_first_cluster(maps, "libunity.so");
    if (un.start) {
        size_t usize = std::min((size_t)(un.end - un.start), MODULE_MAX);
        snprintf(outp, sizeof(outp), "%s/libunity.so", out_dir);
        size_t usz = dump_range(mem_fd, un.start, un.start + usize, outp);
        printf("{\"event\":\"libunity\",\"base\":\"0x%lx\",\"size\":%zu,\"path\":\"%s\"}\n",
               un.start, usz, outp);
        fflush(stdout);
    }

    // Metadata - try in order: custom magic, default magic, then any plausible.
    std::vector<MetaCandidate> cands;
    if (custom_magic) cands = scan_metadata(mem_fd, maps, custom_magic);
    if (cands.empty()) cands = scan_metadata(mem_fd, maps, IL2CPP_META_MAGIC_LE);
    if (cands.empty()) {
        // Structural scan - reports whatever magic the protection uses.
        printf("{\"event\":\"info\",\"msg\":\"default magic absent, structural scan\"}\n");
        fflush(stdout);
        cands = scan_metadata(mem_fd, maps, 0);
    }

    // Verify each candidate by looking for IL2CPP marker strings.
    // Reject structurally-plausible-but-clearly-not-metadata buffers
    // (Android resource pools, JIT scratch, etc.) before they hit disk.
    std::vector<MetaCandidate> verified;
    for (auto& c : cands) {
        if (verify_metadata_strings(mem_fd, c.addr, c.size)) verified.push_back(c);
    }
    printf("{\"event\":\"info\",\"msg\":\"%zu of %zu candidates passed string verification\"}\n",
           verified.size(), cands.size());
    fflush(stdout);

    if (verified.empty()) {
        printf("{\"event\":\"metadata\",\"error\":\"no IL2CPP metadata found in memory - "
               "either the protection encrypts the stringblob, or metadata never gets "
               "fully decrypted into a single buffer\"}\n");
    } else {
        // Dedupe by (version, size, first 256 bytes after header) - same
        // buffer mapped twice via shared mmap.
        std::sort(verified.begin(), verified.end(),
                  [](const auto& a, const auto& b) { return a.size > b.size; });
        std::vector<MetaCandidate> unique;
        std::vector<std::vector<uint8_t>> seen_blobs;
        for (auto& c : verified) {
            std::vector<uint8_t> probe(256);
            if (read_remote(mem_fd, c.addr + 256, probe.data(), 256) != 256) continue;
            bool dup = false;
            for (size_t k = 0; k < seen_blobs.size(); k++) {
                if (unique[k].version == c.version && unique[k].size == c.size &&
                    memcmp(seen_blobs[k].data(), probe.data(), 256) == 0) {
                    dup = true; break;
                }
            }
            if (!dup) { unique.push_back(c); seen_blobs.push_back(std::move(probe)); }
            if (unique.size() >= 2) break;
        }
        printf("{\"event\":\"info\",\"msg\":\"%zu unique verified metadata\"}\n", unique.size());
        fflush(stdout);
        for (size_t i = 0; i < unique.size(); ++i)
            dump_metadata_candidate(mem_fd, unique[i], out_dir, (int)i);
        cands = std::move(unique);
    }

    snprintf(outp, sizeof(outp), "%s/maps.txt", out_dir);
    FILE* mf = fopen(outp, "w");
    if (mf) {
        for (auto& r : maps) fprintf(mf, "%lx-%lx %s %s\n",
                                      r.start, r.end, r.perms, r.path.c_str());
        fclose(mf);
    }

    // Best-effort SDK output: scrape identifier-like strings out of the
    // dumped binaries. Beats nothing when full metadata parsing fails.
    char in[1024];
    snprintf(in, sizeof(in), "%s/libil2cpp.so", out_dir);
    snprintf(outp, sizeof(outp), "%s/sdk_strings_libil2cpp.txt", out_dir);
    size_t n1 = extract_identifiers(in, outp);
    printf("{\"event\":\"sdk\",\"source\":\"libil2cpp.so\",\"count\":%zu,\"path\":\"%s\"}\n",
           n1, outp);
    for (size_t i = 0; i < cands.size() && i < 3; ++i) {
        if (i == 0) snprintf(in, sizeof(in), "%s/global-metadata.dat", out_dir);
        else snprintf(in, sizeof(in), "%s/global-metadata.candidate%zu.dat", out_dir, i);
        snprintf(outp, sizeof(outp), "%s/sdk_strings_metadata%zu.txt", out_dir, i);
        size_t n = extract_identifiers(in, outp);
        printf("{\"event\":\"sdk\",\"source\":\"metadata%zu\",\"count\":%zu,\"path\":\"%s\"}\n",
               i, n, outp);
    }
    fflush(stdout);

    printf("{\"event\":\"done\",\"out_dir\":\"%s\"}\n", out_dir);
    close(mem_fd);
    if (attached) ptrace_detach(pid);
    return 0;
}
