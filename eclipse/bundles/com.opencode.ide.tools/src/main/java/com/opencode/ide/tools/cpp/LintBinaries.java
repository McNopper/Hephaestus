package com.opencode.ide.tools.cpp;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The lint/format binaries behind {@code lint_run}/{@code format_run}:
 * clang-tidy and clang-format resolved from the first detected toolchain
 * that carries them (MSYS2 env bins in default order, then standalone LLVM,
 * then PATH - see {@link ToolchainRegistry}) and cppcheck via its own
 * standalone resolver. An explicit instance can be injected into
 * {@link CppToolProvider} to test the missing-binary paths deterministically.
 */
public record LintBinaries(Optional<Path> clangTidy, Optional<Path> clangFormat, Optional<Path> cppcheck) {

    /** Resolves the lint/format binaries from the cached registry detection. */
    public static LintBinaries detect() {
        Optional<Path> clangTidy = ToolchainRegistry.detected().stream()
                .map(ToolchainRegistry.Toolchain::clangTidy)
                .flatMap(Optional::stream)
                .findFirst().or(() -> ToolchainRegistry.which("clang-tidy"));
        Optional<Path> clangFormat = ToolchainRegistry.detected().stream()
                .map(ToolchainRegistry.Toolchain::clangFormat)
                .flatMap(Optional::stream)
                .findFirst().or(() -> ToolchainRegistry.which("clang-format"));
        return new LintBinaries(clangTidy, clangFormat, ToolchainRegistry.cppcheck());
    }
}
