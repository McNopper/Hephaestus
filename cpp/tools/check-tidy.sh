#!/usr/bin/env bash
# check-tidy.sh - clang-tidy analysis lane.
#
# Contract (every tools/check-*.sh lane obeys it):
#   exit 0  = clean (findings in report-only categories are allowed)
#   exit 1  = findings in a FAIL category
#   exit 77 = toolchain missing (ctest SKIP via SKIP_RETURN_CODE 77)
#
# Fail-vs-report split (enforced by .clang-tidy's WarningsAsErrors, this
# script only classifies): correctness findings (clang-diagnostic-*,
# clang-analyzer-*, bugprone-*) fail; modernize/performance/portability are
# guidance and only summarized. Modernization must never block a build - it
# guides generated code instead.
set -u
BUILD_DIR="${1:-build}"

command -v clang-tidy >/dev/null 2>&1 || { echo "clang-tidy not found"; exit 77; }
DB="$BUILD_DIR/compile_commands.json"
[ -f "$DB" ] || { echo "no compile database at $DB - configure with CMAKE_EXPORT_COMPILE_COMMANDS=ON"; exit 77; }

LOG="$BUILD_DIR/reports/clang-tidy.log"
mkdir -p "$BUILD_DIR/reports"

fail=0
advisory=0
# one pass per translation unit keeps memory flat and output attributable
grep -o '"file": *"[^"]*\.cpp"' "$DB" | sed 's/.*: *"//; s/"$//' | sort -u | while read -r tu; do
    clang-tidy -p "$BUILD_DIR" "$tu" >>"$LOG" 2>&1
done

# classification: FAIL categories vs report-only categories
fail=$(grep -cE '\[(clang-diagnostic|clang-analyzer|bugprone)-' "$LOG" || true)
advisory=$(grep -cE '\[(modernize|performance|portability)-' "$LOG" || true)

echo "clang-tidy: $fail correctness finding(s), $advisory advisory finding(s) (see $LOG)"
if [ "$advisory" -gt 0 ]; then
    echo "advisory (report-only) top findings:"
    grep -E '\[(modernize|performance|portability)-' "$LOG" | head -10
fi
[ "$fail" -gt 0 ] && exit 1
exit 0
