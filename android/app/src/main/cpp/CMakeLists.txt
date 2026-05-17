cmake_minimum_required(VERSION 3.22.1)
project(bd_dumper)

# We ship the dumper as libbd_dumper.so so Android extracts it to
# nativeLibraryDir and (on most versions) leaves it executable, so we can
# run it as a binary via libsu instead of dlopen'ing it.
add_library(bd_dumper SHARED dumper.cpp)

target_link_libraries(bd_dumper log)
target_compile_options(bd_dumper PRIVATE -Wall -Wextra -ffunction-sections -fdata-sections)
target_link_options(bd_dumper PRIVATE -Wl,--gc-sections -Wl,-emain)
