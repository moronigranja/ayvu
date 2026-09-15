cmake_minimum_required(VERSION 3.22)
project(ayvu_llm C CXX)

# llama.cpp vendored at the revision the translate model was MEASURED against
# on the S22 (decisions #161/#162). The tree is gitignored (repo convention,
# same as build/espeak-ng-src); tools/fetch-llama-cpp.sh materializes it.
set(LLAMA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../build/llama.cpp-src")
if (NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp source not found at ${LLAMA_SRC}\n"
        "Run tools/fetch-llama-cpp.sh from the repo root (fetches the pinned commit b75ecd1971).")
endif()

# Always Release: every translate number decisions #161 locked (22.3 tok/s,
# 2.7 s/passage) was measured in a Release build — a Debug core would silently
# make the leg several times slower than the locked claim.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# The measured device configuration: the CPU backend is built once per arm64
# kernel variant and the best one is picked at RUNTIME by a feature probe
# (GGML_CPU_ALL_VARIANTS + GGML_BACKEND_DL), so one APK serves i8mm devices
# (the measured armv8.6/9.x kernels) and baseline-only arm64 alike. Linking a
# single i8mm binary instead would SIGILL on every pre-2021 arm64 device.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

# llama-common stays OFF: llama_chat_apply_template lives in libllama, and its
# rendering was verified byte-identical to the jinja engine (common/chat.cpp,
# what the measured llama-server gate used) for this model's template and the
# single-user-message shape we apply — see decisions #162.
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

add_library(ayvu_llm SHARED llama_translator_jni.cpp)
target_link_libraries(ayvu_llm PRIVATE llama android log)