// beautiful_dump native dumper - arm64 Android, runs as root.
// Built as libbd_dumper.so; main() is the entrypoint - we invoke it as an
// executable via libsu rather than dlopen'ing it.

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

constexpr size_t CHUNK = 1u << 20;        // 1 MiB read chunks
constexpr size_t PAGE = 0x1000;
constexpr uint32_t IL2CPP_META_MAGIC_LE = 0xFAB11BAFu;

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

void ptrace_detach(int pid) {
    ptrace(PTRACE_DETACH, pid, 0, 0);
}

ssize_t read_remote(int mem_fd, uintptr_t addr, void* buf, size_t n) {
    return pread64(mem_fd, buf, n, (off_t)addr);
}

// Dump [start, end) of remote process to out_path. Bad pages are zero-filled
// page-by-page so we don't lose 1 MiB on a single unreadable guard page.
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

uintptr_t scan_metadata(int mem_fd, const std::vector<MemRange>& maps, uint32_t magic) {
    std::vector<uint8_t> buf(CHUNK + 8);
    for (const auto& r : maps) {
        if (r.perms[0] != 'r') continue;
        if (r.end - r.start > 0x10000000ULL) continue;
        for (uintptr_t off = 0; off < r.end - r.start; off += CHUNK) {
            size_t n = std::min((size_t)(r.end - r.start - off), CHUNK);
            ssize_t got = read_remote(mem_fd, r.start + off, buf.data(), n);
            if (got <= 8) continue;
            for (ssize_t i = 0; i + 8 <= got; i += 4) {
                uint32_t m, ver;
                memcpy(&m, buf.data() + i, 4);
                memcpy(&ver, buf.data() + i + 4, 4);
                if (m == magic && ver >= 16 && ver <= 35) {
                    return r.start + off + i;
                }
            }
        }
    }
    return 0;
}

uintptr_t metadata_size(int mem_fd, uintptr_t base) {
    uint8_t hdr[0x400] = {0};
    read_remote(mem_fd, base, hdr, sizeof(hdr));
    uintptr_t max_end = 256;
    for (size_t i = 8; i + 8 <= sizeof(hdr); i += 8) {
        uint32_t off, sz;
        memcpy(&off, hdr + i, 4);
        memcpy(&sz, hdr + i + 4, 4);
        if (off == 0 && sz == 0) continue;
        if (off > 0x40000000 || sz > 0x40000000) break;
        if ((uintptr_t)off + sz > max_end) max_end = off + sz;
    }
    return (max_end + 0xFFF) & ~0xFFFULL;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <pid> <out_dir> [magic_hex_le]\n", argv[0]);
        return 1;
    }
    int pid = atoi(argv[1]);
    const char* out_dir = argv[2];
    uint32_t magic = IL2CPP_META_MAGIC_LE;
    if (argc >= 4) magic = (uint32_t)strtoul(argv[3], nullptr, 16);

    // Print immediately so the harness can tell that we at least started.
    printf("{\"event\":\"start\",\"pid\":%d,\"out\":\"%s\",\"magic\":\"0x%x\"}\n",
           pid, out_dir, magic);
    fflush(stdout);

    if (mkdir(out_dir, 0755) != 0 && errno != EEXIST) {
        printf("{\"event\":\"fatal\",\"error\":\"mkdir %s failed: %s\"}\n",
               out_dir, strerror(errno));
        return 4;
    }

    bool attached = false;
    int mem_fd = open_mem(pid);
    if (mem_fd < 0) {
        // YAMA may require ptrace_attach before allowing /proc/pid/mem.
        printf("{\"event\":\"info\",\"msg\":\"open mem failed (%s), trying ptrace_attach\"}\n",
               strerror(errno));
        fflush(stdout);
        if (ptrace_attach(pid)) {
            attached = true;
            mem_fd = open_mem(pid);
        }
        if (mem_fd < 0) {
            printf("{\"event\":\"fatal\",\"error\":\"open /proc/%d/mem failed even after ptrace: %s\"}\n",
                   pid, strerror(errno));
            if (attached) ptrace_detach(pid);
            return 2;
        }
        printf("{\"event\":\"info\",\"msg\":\"ptrace_attach ok\"}\n");
        fflush(stdout);
    }

    auto maps = read_maps(pid);
    printf("{\"event\":\"maps\",\"count\":%zu}\n", maps.size());
    fflush(stdout);

    uintptr_t il_base = 0, il_end = 0;
    for (auto& r : maps) {
        if (r.path.find("libil2cpp.so") != std::string::npos) {
            if (il_base == 0) il_base = r.start;
            il_end = r.end;
        }
    }
    if (il_base == 0) {
        printf("{\"event\":\"fatal\",\"error\":\"libil2cpp.so not mapped in pid %d\"}\n", pid);
        close(mem_fd);
        if (attached) ptrace_detach(pid);
        return 3;
    }

    // We emit one JSON object per line for easy parsing on the Kotlin side.
    char outp[1024];

    snprintf(outp, sizeof(outp), "%s/libil2cpp.so", out_dir);
    size_t isz = dump_range(mem_fd, il_base, il_end, outp);
    printf("{\"event\":\"libil2cpp\",\"base\":\"0x%lx\",\"size\":%zu,\"path\":\"%s\"}\n",
           il_base, isz, outp);
    fflush(stdout);

    uintptr_t meta = scan_metadata(mem_fd, maps, magic);
    if (meta) {
        uintptr_t msize = metadata_size(mem_fd, meta);
        snprintf(outp, sizeof(outp), "%s/global-metadata.dat", out_dir);
        size_t gz = dump_range(mem_fd, meta, meta + msize, outp);
        printf("{\"event\":\"metadata\",\"addr\":\"0x%lx\",\"size\":%zu,\"path\":\"%s\"}\n",
               meta, gz, outp);
    } else {
        printf("{\"event\":\"metadata\",\"error\":\"magic 0x%x not found - try custom magic\"}\n",
               magic);
    }
    fflush(stdout);

    // libunity.so is small and often handy for IDA cross-reference.
    for (auto& r : maps) {
        if (r.path.find("libunity.so") != std::string::npos) {
            uintptr_t ub = r.start, ue = r.end;
            for (auto& r2 : maps) {
                if (r2.path == r.path && r2.start > ue && r2.start - ue < 0x1000) ue = r2.end;
            }
            snprintf(outp, sizeof(outp), "%s/libunity.so", out_dir);
            size_t usz = dump_range(mem_fd, ub, ue, outp);
            printf("{\"event\":\"libunity\",\"base\":\"0x%lx\",\"size\":%zu,\"path\":\"%s\"}\n",
                   ub, usz, outp);
            break;
        }
    }

    snprintf(outp, sizeof(outp), "%s/maps.txt", out_dir);
    FILE* mf = fopen(outp, "w");
    if (mf) {
        for (auto& r : maps) fprintf(mf, "%lx-%lx %s %s\n", r.start, r.end, r.perms, r.path.c_str());
        fclose(mf);
    }
    printf("{\"event\":\"done\",\"out_dir\":\"%s\"}\n", out_dir);
    close(mem_fd);
    if (attached) ptrace_detach(pid);
    return 0;
}
