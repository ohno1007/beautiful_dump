cmake_minimum_required(VERSION 3.22.1)
project(bd_native)

# === Passive memory dumper (PIE executable, exec'd from /data/local/tmp) ===
add_executable(bd_dumper dumper.cpp)
target_link_libraries(bd_dumper log)
target_compile_options(bd_dumper PRIVATE
    -Wall -Wextra -Os
    -fPIE -ffunction-sections -fdata-sections
    -fno-exceptions -fno-rtti)
target_link_options(bd_dumper PRIVATE -pie -Wl,--gc-sections -Wl,-z,noexecstack)
set_target_properties(bd_dumper PROPERTIES
    OUTPUT_NAME "bd_dumper" PREFIX "lib" SUFFIX ".so")

# === Ptrace injector (PIE executable, exec'd from /data/local/tmp) ===
add_executable(bd_inject inject.cpp)
target_link_libraries(bd_inject log)
target_compile_options(bd_inject PRIVATE
    -Wall -Wextra -Os
    -fPIE -ffunction-sections -fdata-sections
    -fno-exceptions -fno-rtti)
target_link_options(bd_inject PRIVATE -pie -Wl,--gc-sections -Wl,-z,noexecstack)
set_target_properties(bd_inject PROPERTIES
    OUTPUT_NAME "bd_inject" PREFIX "lib" SUFFIX ".so")

# === Payload (real shared library, dlopen'd inside the target game) ===
add_library(bd_payload SHARED payload.cpp)
target_link_libraries(bd_payload log dl)
target_compile_options(bd_payload PRIVATE
    -Wall -Wextra -Os
    -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(bd_payload PRIVATE -Wl,--gc-sections -Wl,-z,noexecstack)
