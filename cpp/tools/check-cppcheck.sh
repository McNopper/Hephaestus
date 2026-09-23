#!/usr/bin/env bash
# check-cppcheck.sh - cppcheck analysis lane.
#
# Contract: exit 0 = clean, exit 1 = findings, exit 77 = toolchain missing.
# Suppressions live in cppcheck.supp and each entry carries a written
# rationale; prefer a one-off inline "// cppcheck-suppress X" with a reason
# over growing the global list. Measure the noise floor (count the hits)
# BEFORE disabling any check - a disable without a measurement is a guess.
set -u
BUILD_DIR="${1:-build}"

command -v cppcheck >/dev/null 2>&1 || { echo "cppcheck not found"; exit 77; }
DB="$BUILD_DIR/compile_commands.json"
[ -f "$DB" ] || { echo "no compile database at $DB"; exit 77; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

LOG="$BUILD_DIR/reports/cppcheck.log"
mkdir -p "$BUILD_DIR/reports"

# own code only: third-party sources are not ours to gate (see the CMake
# exclusion of fetched targets). --error-exitcode makes findings fail.
cppcheck --project="$DB" --enable=warning,performance,portability \
    --suppressions-list="$ROOT/cppcheck.supp" \
    --error-exitcode=1 --inline-suppr -q \
    --output-file="$LOG"
code=$?

echo "cppcheck: exit $code (report: $LOG)"
[ "$code" -ne 0 ] && exit 1
exit 0
