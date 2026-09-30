# Native part of :whisper — builds whisper.cpp (vendored in ./whisper.cpp) plus the JNI glue.
#
# Output per ABI (all packaged into the AAR/APK):
#   libnoteai_whisper.so            JNI glue + whisper.cpp itself (links ggml, ggml-base)
#   libggml.so, libggml-base.so     ggml core / backend registry (arch-neutral code)
#   libggml-cpu-<variant>.so        the compute kernels, one build per CPU feature level
#
# CPU features: ggml's GGML_CPU_ALL_VARIANTS builds the CPU backend several times, each with a
# different -march (e.g. arm64: armv8.0 baseline, +dotprod, +fp16, +i8mm, +sve2, +sme; x86_64:
# sse4.2 .. avx512). Each variant exports ggml_backend_score(), compiled WITHOUT the arch flags,
# which checks the running CPU (getauxval HWCAPs / cpuid) and returns 0 when unsupported. At
# runtime whisper_jni.cpp dlopen()s every variant by soname, asks for its score and registers
# the best one — so no variant's instructions ever run on a CPU that lacks them.
cmake_minimum_required(VERSION 3.22.1)
project(noteai_whisper C CXX)

set(WHISPER_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# ---- Always optimised -------------------------------------------------------------------------
# AGP configures debug variants with CMAKE_BUILD_TYPE=Debug (-O0), which makes inference 10-50x
# slower. Force a release build for every variant; -O3 is appended after the per-config flags
# so it wins over whatever -O level the NDK toolchain picks.
# No DWARF (-g0, and --strip-debug for the prebuilt static libc++ that every .so links): the NDK
# toolchain adds -g, and an app module without a matching NDK installed can't strip it ("Unable to
# strip the following libraries"), which ships ~190 MB of native libs instead of ~30 MB. The
# symbol table stays, so native crashes still symbolicate to function names.
set(CMAKE_BUILD_TYPE Release CACHE STRING "Always build optimised native code" FORCE)
add_compile_options(-O3 -g0 -ffunction-sections -fdata-sections)
add_link_options(-Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--strip-debug)

# ---- ggml -------------------------------------------------------------------------------------
set(BUILD_SHARED_LIBS     ON)   # required by GGML_BACKEND_DL
set(GGML_BACKEND_DL       ON)   # CPU backend(s) become dlopen()-able modules
set(GGML_CPU_ALL_VARIANTS ON)   # one module per CPU feature level, picked at runtime
set(GGML_NATIVE           OFF)  # never tune for the build machine
set(GGML_OPENMP           OFF)  # ggml's own pthread pool; no libomp to ship or conflict with
set(GGML_LLAMAFILE        OFF)  # upstream whisper.cpp default
set(GGML_CPU_KLEIDIAI     OFF)  # would download KleidiAI at configure time
set(GGML_CCACHE           OFF)
set(GGML_BUILD_TESTS      OFF)
set(GGML_BUILD_EXAMPLES   OFF)
add_subdirectory(${WHISPER_CPP_DIR}/ggml ggml)

# Every CPU backend module ggml defined for this ABI, in definition order (baseline first).
get_property(_ggml_src_targets DIRECTORY ${WHISPER_CPP_DIR}/ggml/src PROPERTY BUILDSYSTEM_TARGETS)
set(_cpu_backends "")
foreach(_t IN LISTS _ggml_src_targets)
    if (_t MATCHES "^ggml-cpu(-.+)?$" AND NOT _t MATCHES "-feats$")
        list(APPEND _cpu_backends ${_t})
    endif()
endforeach()
if (NOT _cpu_backends)
    message(FATAL_ERROR "No ggml CPU backend targets found")
endif()
message(STATUS "ggml CPU backends for ${ANDROID_ABI}: ${_cpu_backends}")
set(_cpu_backend_files "")
foreach(_t IN LISTS _cpu_backends)
    list(APPEND _cpu_backend_files "$<TARGET_FILE_NAME:${_t}>")
endforeach()
string(JOIN "," _cpu_backend_files_csv ${_cpu_backend_files})

# ---- whisper.cpp + JNI ------------------------------------------------------------------------
file(STRINGS ${WHISPER_CPP_DIR}/VERSION _whisper_version_lines)
list(GET _whisper_version_lines 0 WHISPER_CPP_VERSION)

add_library(noteai_whisper SHARED
    whisper_jni.cpp
    ${WHISPER_CPP_DIR}/src/whisper.cpp
)
target_include_directories(noteai_whisper PRIVATE
    ${WHISPER_CPP_DIR}/include
    ${WHISPER_CPP_DIR}/src
)
target_compile_features(noteai_whisper PRIVATE cxx_std_17)
target_compile_definitions(noteai_whisper PRIVATE
    WHISPER_VERSION="${WHISPER_CPP_VERSION}"
    NOTEAI_GGML_CPU_BACKENDS="${_cpu_backend_files_csv}"
)
# Only the JNI entry points need to be exported.
set_target_properties(noteai_whisper PROPERTIES
    C_VISIBILITY_PRESET hidden
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
target_link_libraries(noteai_whisper PRIVATE ggml ggml-base log dl)
add_dependencies(noteai_whisper ${_cpu_backends})
