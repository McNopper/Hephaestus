# ============================================================
# clang-scan-deps.cmake
#
# Single source of truth for clang-scan-deps integration.
# Include from the top-level CMakeLists.txt:
#
#   list(APPEND CMAKE_MODULE_PATH ${CMAKE_SOURCE_DIR}/cmake)
#   include(clang-scan-deps)   # inside the PROJECT_ANALYSIS_AVAILABLE gate
#
# Provides:
#   * scan-deps - the dependency graph of every translation unit, written to
#                 ${PROJECT_REPORTS_DIR}/scan-deps/deps.json
#
# clang-scan-deps re-runs each TU's preprocessor with the exact flags from the
# compilation database, so it answers "what does this TU really read" faster
# and more accurately than a hand-rolled #include walk - and it is the same
# scanner CMake/Ninja use for C++20 module dyndep, so the report is
# modules-ready the day the project adopts them.
#
# Report format (LLVM 17+, verified against LLVM 22):
#   --format=experimental-full emits one JSON document:
#     {"modules": [...],                      # empty until modules are adopted
#      "translation-units": [
#        {"input-file": "<tu>",
#         "commands": [{"file-deps": [...],  # everything the TU depends on
#                        "clang-module-deps": [...],
#                        "command-line": [...], ...}]},
#        ...]}
#   Per-TU Makefile fragments (--format=make) and the P1689 module rules
#   (--format=p1689) stay ad-hoc invocations - see the cpp-tools skill.
#
# Honors:
#   * ENABLE_CLANG_SCAN_DEPS - master switch (default ON)
# ============================================================

if(NOT ENABLE_CLANG_SCAN_DEPS)
    return()
endif()

find_program(CLANG_SCAN_DEPS_EXE NAMES clang-scan-deps)

if(NOT CLANG_SCAN_DEPS_EXE)
    message(STATUS "clang-scan-deps not found")
    return()
endif()

set(CLANG_SCAN_DEPS_REPORT_DIR ${PROJECT_REPORTS_DIR}/scan-deps)
file(MAKE_DIRECTORY ${CLANG_SCAN_DEPS_REPORT_DIR})

# Scanning is read-only and never fails the build on project findings - it is
# a reporting tool, not a gate (mirrors the Doxygen target's posture).
add_custom_target(scan-deps
    COMMAND ${CLANG_SCAN_DEPS_EXE}
        -compilation-database=${CMAKE_BINARY_DIR}/compile_commands.json
        --format=experimental-full
        > ${CLANG_SCAN_DEPS_REPORT_DIR}/deps.json
    WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
    COMMENT "Writing clang-scan-deps dependency graph to ${CLANG_SCAN_DEPS_REPORT_DIR}/deps.json"
    VERBATIM
)

message(STATUS "clang-scan-deps target enabled: ${CLANG_SCAN_DEPS_EXE}")
