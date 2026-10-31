#!/usr/bin/env bash
# check-layers.sh - include-graph layer rules (architecture as a test).
#
# Contract: exit 0 = clean, exit 1 = violations, exit 77 = toolchain missing.
#
# Template layer cake (adapt the rules to your project's blocks):
#   tests/** may include anything;
#   src/**   may include include/** and src/**;
#   include/** (the public surface) may include include/** ONLY - never the
#   src/ internals. A forbidden include is a downward dependency and fails.
#
# The include sets come from clang-scan-deps (make-dependencies mode): its
# rules wrap across lines, so continuations are unwrapped FIRST and the rule
# BODIES are matched (2026-09-23 review finding: matching colon-terminated
# header lines could never fire - real violations live in wrapped rule bodies).
set -u
BUILD_DIR="${1:-build}"

command -v clang-scan-deps >/dev/null 2>&1 || { echo "clang-scan-deps not found"; exit 77; }
DB="$BUILD_DIR/compile_commands.json"
[ -f "$DB" ] || { echo "no compile database at $DB"; exit 77; }

RAW="$BUILD_DIR/reports/scan-deps/deps.mk"
RULES="$BUILD_DIR/reports/scan-deps/deps.rules"
mkdir -p "$BUILD_DIR/reports/scan-deps"
clang-scan-deps --compilation-database "$DB" --mode=make-dependencies >"$RAW" 2>/dev/null \
    || { echo "clang-scan-deps failed (see $RAW)"; exit 77; }

# unwrap backslash continuations: one rule per line
awk '{ while (sub(/\\$/, "")) { getline more; $0 = $0 " " more } print }' "$RAW" >"$RULES"

# rule: a public header (include/**) whose dependency body reaches into src/**
violations=$(grep -E '^include[\\/][^:]*:' "$RULES" | grep -E '[^a-zA-Z]src[\\/]' || true)

if [ -n "$violations" ]; then
    echo "LAYER VIOLATION - public headers reaching into src/:"
    echo "$violations"
    exit 1
fi
echo "layer rules: clean (include/ never includes src/)"
exit 0
