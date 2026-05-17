// beautiful_dump payload - shared library loaded INTO the target game's
// address space via the ptrace injector. Once loaded, the constructor
// resolves IL2CPP runtime exports and walks the live domain/assembly/
// class/method tree, writing a readable SDK to /sdcard.
//
// This works against games whose metadata is encrypted in storage but
// decrypted on-demand at runtime - we ask the runtime for the truth
// instead of trying to parse global-metadata.dat ourselves.

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

namespace {

// IL2CPP opaque types - we treat them as void* and call exported helpers.
using Il2CppDomain = void;
using Il2CppAssembly = void;
using Il2CppImage = void;
using Il2CppClass = void;
using MethodInfo = void;
using FieldInfo = void;

#define IL_FN(ret, name, args) ret (*name) args = nullptr
IL_FN(Il2CppDomain*, il2cpp_domain_get, ());
IL_FN(void*, il2cpp_thread_attach, (Il2CppDomain*));
IL_FN(void, il2cpp_thread_detach, (void*));
IL_FN(const Il2CppAssembly**, il2cpp_domain_get_assemblies, (const Il2CppDomain*, size_t*));
IL_FN(const Il2CppImage*, il2cpp_assembly_get_image, (const Il2CppAssembly*));
IL_FN(const char*, il2cpp_image_get_name, (const Il2CppImage*));
IL_FN(size_t, il2cpp_image_get_class_count, (const Il2CppImage*));
IL_FN(const Il2CppClass*, il2cpp_image_get_class, (const Il2CppImage*, size_t));
IL_FN(const char*, il2cpp_class_get_name, (Il2CppClass*));
IL_FN(const char*, il2cpp_class_get_namespace, (Il2CppClass*));
IL_FN(const MethodInfo*, il2cpp_class_get_methods, (Il2CppClass*, void**));
IL_FN(const char*, il2cpp_method_get_name, (const MethodInfo*));
IL_FN(uint32_t, il2cpp_method_get_param_count, (const MethodInfo*));
IL_FN(const FieldInfo*, il2cpp_class_get_fields, (Il2CppClass*, void**));
IL_FN(const char*, il2cpp_field_get_name, (const FieldInfo*));
IL_FN(size_t, il2cpp_field_get_offset, (const FieldInfo*));
IL_FN(Il2CppClass*, il2cpp_class_get_parent, (Il2CppClass*));
#undef IL_FN

unsigned long find_self_lib_base(const char* libname) {
    FILE* f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[1024];
    unsigned long base = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, libname) && strstr(line, "r-")) {
            sscanf(line, "%lx-", &base);
            break;
        }
    }
    fclose(f);
    return base;
}

bool resolve() {
    void* h = dlopen("libil2cpp.so", RTLD_NOLOAD);
    if (!h) return false;
#define R(name) name = (decltype(name))dlsym(h, #name)
    R(il2cpp_domain_get);
    R(il2cpp_thread_attach);
    R(il2cpp_thread_detach);
    R(il2cpp_domain_get_assemblies);
    R(il2cpp_assembly_get_image);
    R(il2cpp_image_get_name);
    R(il2cpp_image_get_class_count);
    R(il2cpp_image_get_class);
    R(il2cpp_class_get_name);
    R(il2cpp_class_get_namespace);
    R(il2cpp_class_get_methods);
    R(il2cpp_method_get_name);
    R(il2cpp_method_get_param_count);
    R(il2cpp_class_get_fields);
    R(il2cpp_field_get_name);
    R(il2cpp_field_get_offset);
    R(il2cpp_class_get_parent);
#undef R
    return il2cpp_domain_get && il2cpp_class_get_name &&
           il2cpp_class_get_methods && il2cpp_image_get_class_count;
}

// Resolve where this game's dump output should land. We write to a
// process-private path under /data/data/<pkg>/cache so we don't need
// any extra SELinux relief, and let the Kotlin harness copy/move it.
std::string output_dir() {
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/cmdline");
    FILE* f = fopen(path, "r");
    std::string pkg = "unknown";
    if (f) {
        char buf[256];
        size_t n = fread(buf, 1, sizeof(buf) - 1, f);
        buf[n] = 0;
        pkg = buf;
        fclose(f);
    }
    std::string base = "/data/data/" + pkg + "/cache";
    mkdir(base.c_str(), 0755);
    return base;
}

void log_status(const std::string& dir, const char* msg) {
    FILE* lg = fopen((dir + "/bd_inject_status.txt").c_str(), "a");
    if (lg) { fprintf(lg, "%s\n", msg); fclose(lg); }
}

void* dump_thread(void*) {
    std::string dir = output_dir();
    log_status(dir, "payload constructor ran");

    if (!resolve()) {
        log_status(dir, "il2cpp symbol resolution failed");
        return nullptr;
    }
    log_status(dir, "il2cpp symbols resolved");

    Il2CppDomain* domain = il2cpp_domain_get();
    if (!domain) {
        log_status(dir, "il2cpp_domain_get returned NULL - runtime not initialized yet?");
        return nullptr;
    }

    void* attached = nullptr;
    if (il2cpp_thread_attach) attached = il2cpp_thread_attach(domain);

    unsigned long il_base = find_self_lib_base("libil2cpp.so");
    log_status(dir, ("libil2cpp.so base = 0x" + std::to_string(il_base)).c_str());

    std::string out_path = dir + "/bd_sdk.cs";
    FILE* out = fopen(out_path.c_str(), "w");
    if (!out) {
        log_status(dir, "open output failed");
        if (attached && il2cpp_thread_detach) il2cpp_thread_detach(attached);
        return nullptr;
    }
    fprintf(out, "// beautiful_dump inject SDK\n");
    fprintf(out, "// libil2cpp.so base: 0x%lx\n", il_base);

    size_t asm_count = 0;
    const Il2CppAssembly** assemblies = il2cpp_domain_get_assemblies(domain, &asm_count);
    fprintf(out, "// assemblies: %zu\n\n", asm_count);

    size_t total_classes = 0, total_methods = 0;
    for (size_t i = 0; i < asm_count; i++) {
        const Il2CppImage* img = il2cpp_assembly_get_image(assemblies[i]);
        if (!img) continue;
        const char* img_name = il2cpp_image_get_name(img);
        size_t cls_count = il2cpp_image_get_class_count(img);
        fprintf(out, "// === image: %s  (%zu classes) ===\n",
                img_name ? img_name : "?", cls_count);

        for (size_t j = 0; j < cls_count; j++) {
            Il2CppClass* klass = (Il2CppClass*)il2cpp_image_get_class(img, j);
            if (!klass) continue;
            const char* ns = il2cpp_class_get_namespace(klass);
            const char* name = il2cpp_class_get_name(klass);
            if (!name) continue;
            total_classes++;

            std::string full = (ns && *ns) ? std::string(ns) + "." + name : name;
            fprintf(out, "class %s {\n", full.c_str());

            void* fiter = nullptr;
            const FieldInfo* field;
            while ((field = il2cpp_class_get_fields(klass, &fiter))) {
                const char* fname = il2cpp_field_get_name(field);
                size_t foff = il2cpp_field_get_offset(field);
                if (fname) fprintf(out, "    field %s;  // offset 0x%zx\n", fname, foff);
            }

            void* miter = nullptr;
            const MethodInfo* method;
            while ((method = il2cpp_class_get_methods(klass, &miter))) {
                const char* mname = il2cpp_method_get_name(method);
                // MethodInfo's first pointer field is methodPointer in
                // every Unity 2018+ build we care about. Read it directly.
                void* mptr = *(void**)method;
                unsigned long rva = mptr ? ((unsigned long)mptr - il_base) : 0;
                uint32_t pcount = il2cpp_method_get_param_count
                                  ? il2cpp_method_get_param_count(method) : 0;
                fprintf(out, "    method %s(%u);  // RVA 0x%lx\n",
                        mname ? mname : "?", pcount, rva);
                total_methods++;
            }

            fprintf(out, "}\n\n");
        }
    }
    fprintf(out, "\n// totals: %zu classes, %zu methods\n",
            total_classes, total_methods);
    fclose(out);
    log_status(dir, ("dump complete: " + out_path).c_str());

    // Make world-readable so the Kotlin harness can pull/copy with root.
    chmod(out_path.c_str(), 0644);

    if (attached && il2cpp_thread_detach) il2cpp_thread_detach(attached);
    return nullptr;
}

}  // namespace

__attribute__((constructor))
static void payload_init() {
    // Run dumping on a background thread so the constructor returns
    // quickly to the dlopen caller and the game can continue.
    pthread_t t;
    if (pthread_create(&t, nullptr, dump_thread, nullptr) == 0) {
        pthread_detach(t);
    }
}
