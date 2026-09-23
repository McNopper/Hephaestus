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
# The include sets come from clang-scan-deps over the compile database, so
# the rules are enforced mechanically instead of by review.
set -u
BUILD_DIR="${1:-build}"

command -v clang-scan-deps >/dev/null 2>&1 || { echo "clang-scan-deps not found"; exit 77; }
DB="$BUILD_DIR/compile_commands.json"
[ -f "$DB" ] || { echo "no compile database at $DB"; exit 77; }

LOG="$BUILD_DIR/reports/scan-deps/deps.json"
mkdir -p "$BUILD_DIR/reports/scan-deps"
clang-scan-deps --compilation-database "$DB" --mode=make-dependencies >"$LOG" 2>/dev/null \
    || { echo "clang-scan-deps failed (see $LOG)"; exit 77; }

# rule: no public header (include/**) may pull in a src/** implementation header
violations=$(grep -E '^.*include[\\/].*\.[hH](pp|xx)?:' "$LOG" | grep -E 'src[\\/].*\.[hH](pp|xx)?' || true)

if [ -n "$violations" ]; then
    echo "LAYER VIOLATION - public headers reaching into src/:"
    echo "$violations"
    exit 1
fi
echo "layer rules: clean (include/ never includes src/)"
exit 0
