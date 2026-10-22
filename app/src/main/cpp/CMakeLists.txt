cmake_minimum_required(VERSION 3.22.1)
project(lulu_whisper LANGUAGES C CXX)

# Fixed upstream release. Sources are fetched at *build time*, not on the user's phone.
# No paid ASR endpoint or runtime model service is involved.
include(FetchContent)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.9.4
    GIT_SHALLOW TRUE
    GIT_PROGRESS TRUE
)
FetchContent_MakeAvailable(whisper_cpp)

add_library(lulu-whisper SHARED whisper_jni.cpp)
target_compile_features(lulu-whisper PRIVATE cxx_std_17)
target_link_libraries(lulu-whisper PRIVATE whisper log)


# Optional, explicitly licensed Cubism Native CORE probe. This is never built
# by default, so the existing video pipeline and CI need no proprietary files.
# The drawable probe proves the ARM64 native library can parse the user's Moc3.
# It is NOT a renderer; full Cubism Framework / GPU work remains separate.
if(DEFINED LULU_CUBISM_NATIVE_SDK_DIR AND NOT "${LULU_CUBISM_NATIVE_SDK_DIR}" STREQUAL "")
    set(CUBISM_HEADERS "${LULU_CUBISM_NATIVE_SDK_DIR}/Core/include")
    set(CUBISM_CORE_LIB "${LULU_CUBISM_NATIVE_SDK_DIR}/Core/lib/android/${ANDROID_ABI}/libLive2DCubismCore.a")
    if(NOT EXISTS "${CUBISM_HEADERS}/Live2DCubismCore.h")
        message(FATAL_ERROR "Missing official Cubism Native header")
    endif()
    if(NOT EXISTS "${CUBISM_CORE_LIB}")
        message(FATAL_ERROR "Missing licensed Android Native Core static library: ${CUBISM_CORE_LIB}")
    endif()
    add_library(lulu-cubism-core STATIC IMPORTED)
    set_target_properties(lulu-cubism-core PROPERTIES IMPORTED_LOCATION "${CUBISM_CORE_LIB}")
    add_library(lulu-cubism-probe SHARED cubism_native_probe.cpp)
    target_compile_features(lulu-cubism-probe PRIVATE cxx_std_17)
    target_include_directories(lulu-cubism-probe PRIVATE "${CUBISM_HEADERS}")
    target_link_libraries(lulu-cubism-probe PRIVATE lulu-cubism-core log)
endif()
