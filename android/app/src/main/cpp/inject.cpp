// beautiful_dump ptrace injector - arm64 Android, runs as root.
// Loads a .so into a target process by calling mmap + dlopen remotely
// via ptrace-driven register manipulation.
//
// Usage: bd_inject <pid> <so_path>
//
// Output: JSON lines on stdout, one per step, so the Kotlin harness can
// follow what happened. The .so itself is responsible for emitting any
// SDK output once it's loaded in-process (see payload.cpp).

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <linux/elf.h>
#include <signal.h>
#include <string>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <unistd.h>

namespace {

struct UserRegs {
    uint64_t x[31];
    uint64_t sp;
    uint64_t pc;
    uint64_t pstate;
};

bool get_regs(int pid, UserRegs& r) {
    iovec io = { &r, sizeof(r) };
    return ptrace(PTRACE_GETREGSET, pid, NT_PRSTATUS, &io) == 0;
}

bool set_regs(int pid, const UserRegs& r) {
    iovec io = { (void*)&r, sizeof(r) };
    return ptrace(PTRACE_SETREGSET, pid, NT_PRSTATUS, &io) == 0;
}

unsigned long find_lib_base(int pid, const char* libname) {
    char path[64];
    if (pid > 0) snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    else strcpy(path, "/proc/self/maps");
    FILE* f = fopen(path, "r");
    if (!f) return 0;
    char line[1024];
    unsigned long base = 0;
    while (fgets(line, sizeof(line), f)) {
        // Only consider the first 'r' segment - that's the start of the load.
        if (strstr(line, libname) && strstr(line, "r-")) {
            sscanf(line, "%lx-", &base);
            break;
        }
    }
    fclose(f);
    return base;
}

// Resolve a remote function's address by computing its offset within the
// library locally, then adding the remote library's base. This relies on
// the fact that on Android every process maps the same /system/lib64/libc.so
// content, so the function offset within the SO is identical.
unsigned long find_remote_func(int pid, const char* libname, const char* symname) {
    void* h = dlopen(libname, RTLD_NOW | RTLD_NOLOAD);
    if (!h) h = dlopen(libname, RTLD_NOW);
    if (!h) return 0;
    void* sym = dlsym(h, symname);
    if (!sym) return 0;
    unsigned long my_base = find_lib_base(0, libname);
    if (!my_base) return 0;
    unsigned long offset = (unsigned long)sym - my_base;
    unsigned long remote_base = find_lib_base(pid, libname);
    if (!remote_base) return 0;
    return remote_base + offset;
}

bool write_remote(int pid, unsigned long addr, const void* data, size_t len) {
    iovec local = { (void*)data, len };
    iovec remote = { (void*)addr, len };
    return process_vm_writev(pid, &local, 1, &remote, 1, 0) == (ssize_t)len;
}

bool read_remote(int pid, unsigned long addr, void* data, size_t len) {
    iovec local = { data, len };
    iovec remote = { (void*)addr, len };
    return process_vm_readv(pid, &local, 1, &remote, 1, 0) == (ssize_t)len;
}

// Set up the remote registers to call `func(args...)` and run until the
// callee returns to LR=0, which traps as SIGSEGV. We then read x0 as the
// return value and restore the saved register state.
bool remote_call(int pid, unsigned long func, const uint64_t* args, int nargs,
                 uint64_t& ret_val) {
    UserRegs saved, regs;
    if (!get_regs(pid, saved)) return false;
    regs = saved;
    for (int i = 0; i < nargs && i < 8; i++) regs.x[i] = args[i];
    regs.pc = func;
    regs.x[30] = 0;                              // LR=0 ⇒ SIGSEGV on return
    if (!set_regs(pid, regs)) return false;

    if (ptrace(PTRACE_CONT, pid, 0, 0) < 0) return false;

    int status = 0;
    if (waitpid(pid, &status, 0) < 0) return false;
    if (!WIFSTOPPED(status)) return false;
    int sig = WSTOPSIG(status);
    // Expect SIGSEGV (PC=0). Some kernels deliver SIGILL/SIGBUS instead.
    (void)sig;

    if (!get_regs(pid, regs)) return false;
    ret_val = regs.x[0];

    set_regs(pid, saved);
    return true;
}

#define EMIT(...) do { printf(__VA_ARGS__); fflush(stdout); } while (0)

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <pid> <so_path>\n", argv[0]);
        return 1;
    }
    int pid = atoi(argv[1]);
    const char* so_path = argv[2];

    EMIT("{\"event\":\"inject_start\",\"pid\":%d,\"so\":\"%s\"}\n", pid, so_path);

    // Verify payload exists and is readable by the target process. We chmod
    // 0644 + chcon system_lib_file in the Kotlin harness before invoking
    // this binary; this is just an early diagnostic.
    if (access(so_path, R_OK) != 0) {
        EMIT("{\"event\":\"inject_fail\",\"step\":\"access\",\"err\":\"%s\"}\n",
             strerror(errno));
        return 2;
    }

    if (ptrace(PTRACE_ATTACH, pid, 0, 0) < 0) {
        EMIT("{\"event\":\"inject_fail\",\"step\":\"attach\",\"err\":\"%s\"}\n",
             strerror(errno));
        return 3;
    }
    int status = 0;
    waitpid(pid, &status, 0);
    EMIT("{\"event\":\"inject_step\",\"step\":\"attached\"}\n");

    auto bail = [&](const char* step, const char* err = nullptr) {
        EMIT("{\"event\":\"inject_fail\",\"step\":\"%s\",\"err\":\"%s\"}\n",
             step, err ? err : strerror(errno));
        ptrace(PTRACE_DETACH, pid, 0, 0);
    };

    unsigned long mmap_addr = find_remote_func(pid, "libc.so", "mmap");
    unsigned long dlopen_addr = find_remote_func(pid, "libdl.so", "android_dlopen_ext");
    if (!dlopen_addr) dlopen_addr = find_remote_func(pid, "libdl.so", "dlopen");
    unsigned long munmap_addr = find_remote_func(pid, "libc.so", "munmap");

    EMIT("{\"event\":\"inject_syms\",\"mmap\":\"0x%lx\",\"dlopen\":\"0x%lx\","
         "\"munmap\":\"0x%lx\"}\n", mmap_addr, dlopen_addr, munmap_addr);

    if (!mmap_addr || !dlopen_addr || !munmap_addr) {
        bail("sym", "remote function lookup failed");
        return 4;
    }

    // Allocate a small buffer in the target for the .so path string.
    uint64_t mmap_args[6] = {
        0, 0x1000,
        PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS,
        (uint64_t)-1, 0,
    };
    uint64_t remote_buf = 0;
    if (!remote_call(pid, mmap_addr, mmap_args, 6, remote_buf) || remote_buf == 0 ||
        (long long)remote_buf == -1LL) {
        bail("mmap", "remote mmap failed");
        return 5;
    }
    EMIT("{\"event\":\"inject_step\",\"step\":\"mmap\",\"addr\":\"0x%lx\"}\n",
         remote_buf);

    // Push the .so path into the remote buffer.
    size_t plen = strlen(so_path) + 1;
    if (!write_remote(pid, remote_buf, so_path, plen)) {
        bail("write_path");
        return 6;
    }

    // Call dlopen(path, RTLD_NOW). For android_dlopen_ext signature compat
    // we add a NULL extinfo as third arg, which dlopen will ignore.
    uint64_t dl_args[3] = { remote_buf, RTLD_NOW, 0 };
    uint64_t handle = 0;
    if (!remote_call(pid, dlopen_addr, dl_args, 3, handle) || handle == 0) {
        bail("dlopen", "remote dlopen returned NULL");
        // Try to free the mmap and detach cleanly.
        uint64_t un_args[2] = { remote_buf, 0x1000 };
        uint64_t junk;
        remote_call(pid, munmap_addr, un_args, 2, junk);
        return 7;
    }
    EMIT("{\"event\":\"inject_step\",\"step\":\"dlopen\",\"handle\":\"0x%lx\"}\n",
         handle);

    // Free the path buffer - the payload .so is loaded; we don't need the
    // scratch page any more.
    uint64_t un_args[2] = { remote_buf, 0x1000 };
    uint64_t junk = 0;
    remote_call(pid, munmap_addr, un_args, 2, junk);

    if (ptrace(PTRACE_DETACH, pid, 0, 0) < 0) {
        EMIT("{\"event\":\"inject_warn\",\"step\":\"detach\",\"err\":\"%s\"}\n",
             strerror(errno));
    }
    EMIT("{\"event\":\"inject_done\",\"handle\":\"0x%lx\"}\n", handle);
    return 0;
}
