#!/usr/bin/env bash
# compile-check.sh
#
# Stop hook: when Claude finishes a turn in which it edited Java/pom/resources,
# compile main + test code. A failure blocks the stop (exit 2) so Claude fixes it
# instead of reporting "done" on code that does not compile.
#
#   - Default: ./mvnw test-compile (no Docker, ~10-20 s).
#   - EV_HOOK_VERIFY=1: ./mvnw verify instead (runs the tests; needs Docker).
#
# Guards:
#   - stop_hook_active=true -> exit 0, otherwise an unfixable build loops forever.
#   - nothing recorded by track-changes.sh -> exit 0, so Q&A turns cost nothing.
#   - no JDK 25 found -> warn and exit 0 (do not trap Claude on a machine problem).

set -uo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$ROOT" || exit 0

LOG_DIR="$ROOT/.claude/hooks/logs"
mkdir -p "$LOG_DIR"
find "$LOG_DIR" -name "*.log" -mtime +7 -delete 2>/dev/null || true
LOG_FILE="$LOG_DIR/compile-check-$(date -u +%F).log"
CHANGED_LIST="$LOG_DIR/changed-files.list"

INPUT="$(cat)"
if printf '%s' "$INPUT" | grep -q '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then
  echo "[compile-check] $(date -u +%FT%TZ) stop_hook_active - skipping" >>"$LOG_FILE"
  exit 0
fi

[ -s "$CHANGED_LIST" ] || exit 0

has_jdk() {
  [ -n "${JAVA_HOME:-}" ] && { [ -x "$JAVA_HOME/bin/java" ] || [ -x "$JAVA_HOME/bin/java.exe" ]; }
}
if ! has_jdk; then
  echo "compile-check: JAVA_HOME is not set to a JDK; skipped. Set it in .claude/settings.local.json (env.JAVA_HOME)." >&2
  exit 0
fi

GOAL="test-compile"
[ "${EV_HOOK_VERIFY:-0}" = "1" ] && GOAL="verify"

echo "[compile-check] $(date -u +%FT%TZ) ./mvnw $GOAL ($(sort -u "$CHANGED_LIST" | wc -l) files changed)" >>"$LOG_FILE"
OUTPUT="$(./mvnw -B -q "$GOAL" 2>&1)"
STATUS=$?
echo "$OUTPUT" >>"$LOG_FILE"

if [ $STATUS -ne 0 ]; then
  # Keep the change list so the fix is re-verified on the next stop.
  echo "./mvnw $GOAL failed. Fix it before finishing:" >&2
  echo "$OUTPUT" | grep -E '^\[ERROR\]|FAIL|Tests run:' | head -n 40 >&2
  exit 2
fi

rm -f "$CHANGED_LIST"
exit 0
