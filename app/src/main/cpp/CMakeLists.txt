cmake_minimum_required(VERSION 3.22.1)
project(nodeai)

include(FetchContent)

# Build everything (ggml + llama) as shared libraries. The Android Gradle
# Plugin automatically packages every .so produced by this CMake build into
# the APK, and shared linking ensures ggml's CPU backend static initializers
# actually run at load time.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)

# Cross-compiling for arm64: never probe the CI host's CPU features.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# The Android NDK has no system OpenMP; ggml falls back to its own threadpool.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# We only need the core library, none of llama.cpp's extra targets.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp
    GIT_TAG b3900
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(llama)

add_library(llama-android SHARED llama-android.cpp)

find_library(log-lib log)
target_link_libraries(llama-android llama ${log-lib})
