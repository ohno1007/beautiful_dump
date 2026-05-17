cmake_minimum_required(VERSION 3.22.1)
project(bd_dumper)

# Build as a PIE executable, but rename to libbd_dumper.so so AGP picks
# it up into jniLibs/<abi>/ and Android extracts it to disk on install.
# We then `cp` it to /data/local/tmp at runtime and exec it via libsu.
#
# Building as add_library(SHARED) would skip PT_INTERP, leaving the
# binary unable to resolve libc symbols when exec'd directly (SIGSEGV
# on the first printf).
add_executable(bd_dumper dumper.cpp)

target_link_libraries(bd_dumper log)
target_compile_options(bd_dumper PRIVATE
    -Wall -Wextra -Os
    -fPIE
    -ffunction-sections -fdata-sections
    -fno-exceptions -fno-rtti
)
target_link_options(bd_dumper PRIVATE
    -pie
    -Wl,--gc-sections
    -Wl,-z,noexecstack
)

# AGP only packages files matching lib*.so from the cxx build output.
set_target_properties(bd_dumper PROPERTIES
    OUTPUT_NAME "bd_dumper"
    PREFIX "lib"
    SUFFIX ".so"
)
