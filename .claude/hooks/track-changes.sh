#!/usr/bin/env bash
# track-changes.sh
#
# PostToolUse (Edit|Write): records which build-relevant files Claude touched, so the
# Stop hook (compile-check.sh) only runs when there is something to verify.
# Never blocks; always exits 0.

set -uo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$(pwd)}"
LOG_DIR="$ROOT/.claude/hooks/logs"
mkdir -p "$LOG_DIR"
CHANGED_LIST="$LOG_DIR/changed-files.list"

FILE="$(cat \
  | sed -n 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
  | head -n 1 \
  | sed 's#\\\\#/#g')"

case "$FILE" in
  *.java | */pom.xml | pom.xml | */src/main/resources/* | */src/test/resources/*)
    echo "$FILE" >>"$CHANGED_LIST"
    ;;
esac

exit 0
