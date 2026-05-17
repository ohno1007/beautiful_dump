cmake_minimum_required(VERSION 3.22.1)
project(bd_native)

# Passive memory dumper (PIE executable, exec'd from /data/local/tmp).
# Kept as a baseline for unprotected games. For protected ones the
# Zygisk module (shipped as an asset, installed at runtime) is the real
# path.
add_executable(bd_dumper dumper.cpp)
target_link_libraries(bd_dumper log)
target_compile_options(bd_dumper PRIVATE
    -Wall -Wextra -Os
    -fPIE -ffunction-sections -fdata-sections
    -fno-exceptions -fno-rtti)
target_link_options(bd_dumper PRIVATE -pie -Wl,--gc-sections -Wl,-z,noexecstack)
set_target_properties(bd_dumper PROPERTIES
    OUTPUT_NAME "bd_dumper" PREFIX "lib" SUFFIX ".so")
