# Native code: JPEG XL decoding with libjxl (Samsung Expert RAW stores its raw data as JPEG XL).
cmake_minimum_required(VERSION 3.22.1)
project(headsupjxl C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# Only the decoder: no tools, tests, encoders' extras or colour management library.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
foreach(option
    JPEGXL_ENABLE_TOOLS JPEGXL_ENABLE_JPEGLI JPEGXL_ENABLE_JPEGLI_LIBJPEG JPEGXL_ENABLE_DOXYGEN
    JPEGXL_ENABLE_MANPAGES JPEGXL_ENABLE_BENCHMARK JPEGXL_ENABLE_EXAMPLES JPEGXL_ENABLE_JNI
    JPEGXL_ENABLE_SJPEG JPEGXL_ENABLE_OPENEXR JPEGXL_ENABLE_SKCMS JPEGXL_ENABLE_TCMALLOC
    JPEGXL_ENABLE_FUZZERS JPEGXL_ENABLE_DEVTOOLS JPEGXL_ENABLE_PLUGINS JPEGXL_ENABLE_VIEWERS
    JPEGXL_ENABLE_TRANSCODE_JPEG JPEGXL_BUNDLE_LIBPNG JPEGXL_ENABLE_COVERAGE
    HWY_ENABLE_TESTS HWY_ENABLE_EXAMPLES HWY_ENABLE_INSTALL)
  set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
set(BROTLI_DISABLE_TESTS ON CACHE BOOL "" FORCE)

FetchContent_Declare(
  libjxl
  GIT_REPOSITORY https://github.com/libjxl/libjxl.git
  GIT_TAG v0.11.1
  GIT_SHALLOW TRUE
  GIT_SUBMODULES "third_party/highway;third_party/brotli;third_party/lcms"
)
FetchContent_MakeAvailable(libjxl)

add_library(headsupjxl SHARED jxl_decode.cpp jxl_jni.cpp)
target_link_libraries(headsupjxl PRIVATE jxl_dec jxl_threads)
