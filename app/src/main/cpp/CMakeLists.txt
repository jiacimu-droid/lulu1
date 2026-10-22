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
