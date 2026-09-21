cmake_minimum_required(VERSION 3.26)

project(
    MyProject
    VERSION 0.1.0
    DESCRIPTION "AI-first modern C++23 project template"
    LANGUAGES CXX
)

# ============================================================
# Global project settings
# ============================================================

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Machine-readable compilation database: the backbone of every AI/tooling
# integration here (clang-tidy, cppcheck, Doxygen clang parsing, editors).
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

set_property(GLOBAL PROPERTY USE_FOLDERS ON)

list(APPEND CMAKE_MODULE_PATH ${CMAKE_SOURCE_DIR}/cmake)

# Stable, predictable location for all generated, machine-readable reports
# (clang-tidy fixes, cppcheck XML, Doxygen). Agents can rely on these paths.
set(PROJECT_REPORTS_DIR ${CMAKE_BINARY_DIR}/reports)
file(MAKE_DIRECTORY ${PROJECT_REPORTS_DIR})

# The AI-first static analysis (clang-tidy, cppcheck) builds on a Clang-based
# frontend and a compilation database. State this positively as an allowlist
# rather than blocking specific compilers: it runs when
#   * the generator emits a compile database (Ninja or Makefiles), and
#   * the compiler is Clang- or GNU-compatible (the frontends clang-tidy reads
#     cleanly; MSVC and other frontends are simply not on the list).
# Otherwise the project still configures and builds; analysis is just skipped.
set(PROJECT_ANALYSIS_AVAILABLE FALSE)
set(PROJECT_ANALYSIS_STATUS "skipped")
set(PROJECT_ANALYSIS_REASON "toolchain does not match analysis allowlist")

if(CMAKE_EXPORT_COMPILE_COMMANDS
   AND (CMAKE_GENERATOR MATCHES "Ninja" OR CMAKE_GENERATOR MATCHES "Makefiles")
   AND (CMAKE_CXX_COMPILER_ID MATCHES "Clang" OR CMAKE_CXX_COMPILER_ID MATCHES "GNU"))
    set(PROJECT_ANALYSIS_AVAILABLE TRUE)
    set(PROJECT_ANALYSIS_STATUS "enabled")
    set(PROJECT_ANALYSIS_REASON "generator+compiler matched analysis allowlist")
    message(STATUS "AI-first static analysis enabled (${CMAKE_GENERATOR}, ${CMAKE_CXX_COMPILER_ID})")
else()
    set(PROJECT_ANALYSIS_STATUS "skipped")
    set(PROJECT_ANALYSIS_REASON "requires Ninja/Makefiles plus Clang/GNU-compatible compiler")
    message(STATUS
        "AI-first static analysis skipped for this toolchain "
        "(${CMAKE_GENERATOR}, ${CMAKE_CXX_COMPILER_ID}); "
        "use Ninja/Makefiles with a Clang- or GNU-compatible compiler to enable it")
endif()

file(GENERATE
    OUTPUT ${PROJECT_REPORTS_DIR}/analysis-status.txt
    CONTENT "analysis=${PROJECT_ANALYSIS_STATUS}\ngenerator=${CMAKE_GENERATOR}\ncompiler=${CMAKE_CXX_COMPILER_ID}\nreason=${PROJECT_ANALYSIS_REASON}\n"
)

# ============================================================
# Options
# ============================================================

option(ENABLE_CLANG_TIDY "Enable clang-tidy static analysis" ON)
option(ENABLE_CLANG_TIDY_IN_BUILD "Run clang-tidy during compilation" ON)
option(ENABLE_CLANG_FORMAT "Enable clang-format targets" ON)

option(ENABLE_CPPCHECK "Enable cppcheck static analysis target" ON)
option(ENABLE_CPPCHECK_IN_BUILD "Run cppcheck alongside the compiler" OFF)

option(ENABLE_CLANG_SCAN_DEPS "Enable the clang-scan-deps dependency graph target" ON)

option(ENABLE_TESTING "Enable GoogleTest-based tests" ON)
option(ENABLE_DOXYGEN "Enable Doxygen documentation target" ON)

# ============================================================
# Shared source list (used by clang-format and clang-tidy targets)
# ============================================================

file(GLOB_RECURSE PROJECT_CXX_SOURCES CONFIGURE_DEPENDS
    "${CMAKE_SOURCE_DIR}/include/*.h"
    "${CMAKE_SOURCE_DIR}/include/*.hh"
    "${CMAKE_SOURCE_DIR}/include/*.hpp"
    "${CMAKE_SOURCE_DIR}/include/*.hxx"

    "${CMAKE_SOURCE_DIR}/src/*.c"
    "${CMAKE_SOURCE_DIR}/src/*.cc"
    "${CMAKE_SOURCE_DIR}/src/*.cpp"
    "${CMAKE_SOURCE_DIR}/src/*.cxx"
    "${CMAKE_SOURCE_DIR}/src/*.h"
    "${CMAKE_SOURCE_DIR}/src/*.hh"
    "${CMAKE_SOURCE_DIR}/src/*.hpp"
    "${CMAKE_SOURCE_DIR}/src/*.hxx"

    "${CMAKE_SOURCE_DIR}/tests/*.c"
    "${CMAKE_SOURCE_DIR}/tests/*.cc"
    "${CMAKE_SOURCE_DIR}/tests/*.cpp"
    "${CMAKE_SOURCE_DIR}/tests/*.cxx"
    "${CMAKE_SOURCE_DIR}/tests/*.h"
    "${CMAKE_SOURCE_DIR}/tests/*.hh"
    "${CMAKE_SOURCE_DIR}/tests/*.hpp"
    "${CMAKE_SOURCE_DIR}/tests/*.hxx"
)

# ============================================================
# clang-tidy
#
# Each source file's nearest .clang-tidy is used (no forced --config-file),
# so tests/ can relax noisy checks via tests/.clang-tidy.
# ============================================================

if(ENABLE_CLANG_TIDY AND PROJECT_ANALYSIS_AVAILABLE)
    find_program(CLANG_TIDY_EXE NAMES clang-tidy)

    if(CLANG_TIDY_EXE)
        if(ENABLE_CLANG_TIDY_IN_BUILD)
            set(CMAKE_CXX_CLANG_TIDY "${CLANG_TIDY_EXE}")
            message(STATUS "clang-tidy enabled during build: ${CLANG_TIDY_EXE}")
        else()
            message(STATUS "clang-tidy target enabled: ${CLANG_TIDY_EXE}")
        endif()

        # Machine-readable report: clang-tidy exports findings AND suggested
        # fixes as YAML, which agents can parse and apply.
        set(CLANG_TIDY_REPORT_DIR ${PROJECT_REPORTS_DIR}/clang-tidy)
        file(MAKE_DIRECTORY ${CLANG_TIDY_REPORT_DIR})

        add_custom_target(tidy
            COMMAND ${CLANG_TIDY_EXE}
                -p ${CMAKE_BINARY_DIR}
                --export-fixes=${CLANG_TIDY_REPORT_DIR}/fixes.yaml
                ${PROJECT_CXX_SOURCES}
            WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
            COMMENT "Running clang-tidy; fixes -> ${CLANG_TIDY_REPORT_DIR}/fixes.yaml"
            VERBATIM
        )
    else()
        message(STATUS "clang-tidy not found")
    endif()
endif()

# ============================================================
# cppcheck (single source of truth in cmake/cppcheck.cmake)
# ============================================================

if(PROJECT_ANALYSIS_AVAILABLE)
    include(cppcheck)
endif()

# ============================================================
# clang-scan-deps (single source of truth in cmake/clang-scan-deps.cmake)
# ============================================================

if(PROJECT_ANALYSIS_AVAILABLE)
    include(clang-scan-deps)
endif()

# ============================================================
# clang-format
# ============================================================

if(ENABLE_CLANG_FORMAT)
    find_program(CLANG_FORMAT_EXE NAMES clang-format)

    if(CLANG_FORMAT_EXE)
        add_custom_target(format
            COMMAND ${CLANG_FORMAT_EXE}
                -i
                --style=file
                ${PROJECT_CXX_SOURCES}
            WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
            COMMENT "Formatting source files with clang-format"
            VERBATIM
        )

        add_custom_target(format-check
            COMMAND ${CLANG_FORMAT_EXE}
                --dry-run
                --Werror
                --style=file
                ${PROJECT_CXX_SOURCES}
            WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
            COMMENT "Checking source formatting with clang-format"
            VERBATIM
        )

        message(STATUS "clang-format enabled: ${CLANG_FORMAT_EXE}")
    else()
        message(STATUS "clang-format not found")
    endif()
endif()

# ============================================================
# Project options target
# ============================================================

add_library(project_options INTERFACE)

target_compile_features(project_options INTERFACE cxx_std_23)

if(MSVC)
    target_compile_options(project_options INTERFACE
        /W4
        /permissive-
    )
else()
    target_compile_options(project_options INTERFACE
        -Wall
        -Wextra
        -Wpedantic
        -Wshadow
        -Wnon-virtual-dtor
        -Wcast-align
        -Wunused
        -Woverloaded-virtual
    )
endif()

# ============================================================
# Main library
# ============================================================

add_library(my_project_lib
    src/example.cpp
)

target_include_directories(my_project_lib
    PUBLIC
        ${CMAKE_SOURCE_DIR}/include
)

target_link_libraries(my_project_lib
    PUBLIC
        project_options
)

# ============================================================
# Main executable
# ============================================================

add_executable(my_project_app
    src/main.cpp
)

target_link_libraries(my_project_app
    PRIVATE
        my_project_lib
        project_options
)

# ============================================================
# GoogleTest
# ============================================================

if(ENABLE_TESTING)
    include(CTest)
    enable_testing()

    include(FetchContent)

    FetchContent_Declare(
        googletest
        GIT_REPOSITORY https://github.com/google/googletest.git
        GIT_TAG v1.17.0
    )

    set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)

    FetchContent_MakeAvailable(googletest)

    # Never run our strict static analysis over third-party (GoogleTest)
    # sources: it is noise and would break the build under WarningsAsErrors.
    foreach(_third_party_target gtest gtest_main gmock gmock_main)
        if(TARGET ${_third_party_target})
            set_target_properties(${_third_party_target} PROPERTIES
                CXX_CLANG_TIDY ""
                CXX_CPPCHECK ""
            )
        endif()
    endforeach()

    add_executable(my_project_tests
        tests/example_test.cpp
    )

    target_link_libraries(my_project_tests
        PRIVATE
            my_project_lib
            project_options
            GTest::gtest_main
    )

    include(GoogleTest)
    gtest_discover_tests(my_project_tests)
endif()

# ============================================================
# Doxygen
# ============================================================

if(ENABLE_DOXYGEN)
    find_package(Doxygen)

    if(DOXYGEN_FOUND)
        set(DOXYGEN_INPUT_FILE ${CMAKE_SOURCE_DIR}/Doxyfile.in)
        set(DOXYGEN_OUTPUT_FILE ${CMAKE_BINARY_DIR}/Doxyfile)

        if(EXISTS ${DOXYGEN_INPUT_FILE})
            configure_file(
                ${DOXYGEN_INPUT_FILE}
                ${DOXYGEN_OUTPUT_FILE}
                @ONLY
            )

            add_custom_target(docs
                COMMAND ${DOXYGEN_EXECUTABLE} ${DOXYGEN_OUTPUT_FILE}
                WORKING_DIRECTORY ${CMAKE_BINARY_DIR}
                COMMENT "Generating API documentation with Doxygen"
                VERBATIM
            )

            message(STATUS "Doxygen enabled: ${DOXYGEN_EXECUTABLE}")
        else()
            message(STATUS "Doxyfile.in not found; docs target unavailable")
        endif()
    else()
        message(STATUS "Doxygen not found")
    endif()
endif()

# ============================================================
# Aggregate verification target
#
# verify: fast default verification (build + test). Reports whether analysis is
# enabled or skipped for this toolchain.
# verify-full: full strict verification (verify + format + static analysis + docs).
# ============================================================

add_custom_target(verify-analysis-status
    COMMAND ${CMAKE_COMMAND} -E echo "AI analysis status: ${PROJECT_ANALYSIS_STATUS} (${PROJECT_ANALYSIS_REASON})"
    COMMAND ${CMAKE_COMMAND} -E echo "Status artifact: ${PROJECT_REPORTS_DIR}/analysis-status.txt"
    COMMENT "Reporting AI analysis status"
    VERBATIM
)

add_custom_target(verify
    COMMAND ${CMAKE_CTEST_COMMAND} --output-on-failure --build-config $<CONFIG>
    WORKING_DIRECTORY ${CMAKE_BINARY_DIR}
    COMMENT "Fast verification: build + test"
    VERBATIM
)

add_dependencies(verify my_project_app)
add_dependencies(verify verify-analysis-status)

if(ENABLE_TESTING)
    add_dependencies(verify my_project_tests)
endif()

add_custom_target(verify-full
    COMMENT "Full verification: verify + format + static analysis + docs"
)

add_dependencies(verify-full verify)

if(TARGET format-check)
    add_dependencies(verify-full format-check)
endif()

if(TARGET cppcheck)
    add_dependencies(verify-full cppcheck)
endif()

if(TARGET tidy)
    add_dependencies(verify-full tidy)
endif()

if(TARGET scan-deps)
    add_dependencies(verify-full scan-deps)
endif()

if(TARGET docs)
    add_dependencies(verify-full docs)
endif()
