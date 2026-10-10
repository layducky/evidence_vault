#!/usr/bin/env bash
# guard-edits.sh
#
# PreToolUse (Edit|Write): enforces two CLAUDE.md rules deterministically.
#   1. Never edit an existing Flyway migration. A migration is "existing" once git
#      tracks it (staged or committed). Creating a new V<N>__*.sql is allowed.
#   2. Never introduce an in-memory database. Edits to pom.xml or src/** that add
#      the H2 driver or an H2 JDBC URL are refused.
#
# Exit 2 = block the tool call; stderr is shown to Claude as the reason.
# No jq on this machine, so the payload is parsed with sed (paths are JSON-escaped).

set -uo pipefail

INPUT="$(cat)"

FILE="$(printf '%s' "$INPUT" \
  | sed -n 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
  | head -n 1 \
  | sed 's#\\\\#/#g')"

[ -z "$FILE" ] && exit 0

case "$FILE" in
  */src/main/resources/db/migration/V*.sql | src/main/resources/db/migration/V*.sql)
    ROOT="${CLAUDE_PROJECT_DIR:-$(pwd)}"
    if [ -f "$FILE" ] && git -C "$ROOT" ls-files --error-unmatch -- "$FILE" >/dev/null 2>&1; then
      echo "Blocked: $(basename "$FILE") is an existing Flyway migration tracked by git." >&2
      echo "Rule (rules/coding/db.md): never edit a committed or staged migration. Add a new V<N+1>__*.sql instead (use /new-migration)." >&2
      exit 2
    fi
    ;;
esac

case "$FILE" in
  */pom.xml | pom.xml | */src/* | src/*)
    if printf '%s' "$INPUT" | grep -Eiq 'com\.h2database|jdbc:h2:'; then
      echo "Blocked: H2 / in-memory database detected in $(basename "$FILE")." >&2
      echo "Rule (rules/testing/TEST_STRATEGY.md): DB tests run on real PostgreSQL via TestcontainersConfiguration." >&2
      exit 2
    fi
    ;;
esac

exit 0
