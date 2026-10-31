# ============================================================
# cppcheck.cmake
#
# Single source of truth for cppcheck integration.
# Include from the top-level CMakeLists.txt:
#
#   list(APPEND CMAKE_MODULE_PATH ${CMAKE_SOURCE_DIR}/cmake)
#   include(cppcheck)
#
# Provides:
#   * cppcheck        - default profile, fails on findings (high signal/noise)
#   * cppcheck-strict - exhaustive profile (all + inconclusive), opt-in
#   * cppcheck-xml    - machine-readable XML report for AI/tools, written to
#                       ${PROJECT_REPORTS_DIR}/cppcheck/cppcheck.xml
#
# Honors:
#   * ENABLE_CPPCHECK            - master switch (default ON)
#   * ENABLE_CPPCHECK_IN_BUILD   - run alongside the compiler (default OFF)
# ============================================================

if(NOT ENABLE_CPPCHECK)
    return()
endif()

find_program(CPPCHECK_EXE NAMES cppcheck)

if(NOT CPPCHECK_EXE)
    message(STATUS "cppcheck not found")
    return()
endif()

set(CPPCHECK_REPORT_DIR ${PROJECT_REPORTS_DIR}/cppcheck)
file(MAKE_DIRECTORY ${CPPCHECK_REPORT_DIR})

# Default profile: high signal-to-noise. Fails the target on real findings,
# but does not enable the speculative/style-heavy checks that block agents.
set(CPPCHECK_COMMON_ARGS
    --std=c++23
    --enable=warning,performance,portability
    --inline-suppr
    --error-exitcode=1
    --quiet
    --template=gcc
    --suppressions-list=${CMAKE_SOURCE_DIR}/cppcheck.supp

    # Reduce noise from third-party / generated / build content. FetchContent
    # places its dependencies (e.g. GoogleTest) under the *binary* dir, so the
    # binary dir must be excluded as well as the in-source build folders.
    -i${CMAKE_BINARY_DIR}
    -i${CMAKE_SOURCE_DIR}/build
    -i${CMAKE_SOURCE_DIR}/cmake-build-debug
    -i${CMAKE_SOURCE_DIR}/cmake-build-release
    -i${CMAKE_SOURCE_DIR}/_deps
    -i${CMAKE_SOURCE_DIR}/external
    -i${CMAKE_SOURCE_DIR}/third_party

    # GoogleTest's macros are not fully expandable by cppcheck's own parser
    # and yield spurious syntaxError noise; tests are covered by clang-tidy.
    -i${CMAKE_SOURCE_DIR}/tests
)

# Opt-in exhaustive profile (style + inconclusive). Kept off the default path
# so speculative findings never block an agent's build/verify loop. tests/ are
# excluded here too: GoogleTest macros are not fully expandable by cppcheck's
# parser and yield spurious syntaxError noise (tests stay covered by clang-tidy).
set(CPPCHECK_STRICT_ARGS
    --std=c++23
    --enable=all
    --inconclusive
    --force
    --inline-suppr
    --error-exitcode=1
    --quiet
    --template=gcc
    --suppressions-list=${CMAKE_SOURCE_DIR}/cppcheck.supp
    -i${CMAKE_BINARY_DIR}
    -i${CMAKE_SOURCE_DIR}/build
    -i${CMAKE_SOURCE_DIR}/cmake-build-debug
    -i${CMAKE_SOURCE_DIR}/cmake-build-release
    -i${CMAKE_SOURCE_DIR}/_deps
    -i${CMAKE_SOURCE_DIR}/external
    -i${CMAKE_SOURCE_DIR}/third_party
    -i${CMAKE_SOURCE_DIR}/tests
)

add_custom_target(cppcheck
    COMMAND ${CPPCHECK_EXE}
        ${CPPCHECK_COMMON_ARGS}
        --project=${CMAKE_BINARY_DIR}/compile_commands.json
    WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
    COMMENT "Running cppcheck static analysis (default profile)"
    VERBATIM
)

add_custom_target(cppcheck-strict
    COMMAND ${CPPCHECK_EXE}
        ${CPPCHECK_STRICT_ARGS}
        --project=${CMAKE_BINARY_DIR}/compile_commands.json
    WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
    COMMENT "Running cppcheck static analysis (exhaustive: all + inconclusive)"
    VERBATIM
)

# Machine-readable report for AI/tooling consumption.
add_custom_target(cppcheck-xml
    COMMAND ${CPPCHECK_EXE}
        ${CPPCHECK_COMMON_ARGS}
        --xml
        --xml-version=2
        --project=${CMAKE_BINARY_DIR}/compile_commands.json
        2> ${CPPCHECK_REPORT_DIR}/cppcheck.xml
    WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
    COMMENT "Writing cppcheck XML report to ${CPPCHECK_REPORT_DIR}/cppcheck.xml"
    VERBATIM
)

if(ENABLE_CPPCHECK_IN_BUILD)
    set(CMAKE_CXX_CPPCHECK ${CPPCHECK_EXE} ${CPPCHECK_COMMON_ARGS}
        CACHE INTERNAL "cppcheck command used during compilation")
    message(STATUS "cppcheck enabled during build: ${CPPCHECK_EXE}")
else()
    message(STATUS "cppcheck targets enabled: ${CPPCHECK_EXE}")
endif()
