#!/usr/bin/env bash
# Heuristic static review for the dual-player migration (test plan section 1.3).
# This is a *review aid*, not a pass/fail test: it flags places a human should look at.
# Output: static-review/static-review.md
set -u
OUT="${1:-static-review}"; mkdir -p "$OUT"
R="$OUT/static-review.md"
SVC=$(find . -name FelicityPlayerService.kt -not -path '*/build/*' | head -n1)
PLAYBACK_DIR=$(find . -type d -path '*engine/playback' -not -path '*/build/*' | head -n1)

count() { # count <regex> <file...>
  local n; n=$(cat "${@:2}" 2>/dev/null | grep -Ec "$1"); echo "${n:-0}"
}

{
echo "# Static review hints (heuristic - NOT a pass/fail test)"
echo
echo "Service file: \`${SVC:-not found}\`"
echo
if [ -n "$SVC" ]; then
  echo "## Player ownership / leaks"
  echo "| Pattern | Count |"; echo "|---|---|"
  echo "| \`ExoPlayer.Builder(\` constructions | $(count 'ExoPlayer\.Builder\(' "$SVC") |"
  echo "| \`.release()\` calls | $(count '\.release\(\)' "$SVC") |"
  echo "| \`addListener(\` registrations | $(count 'addListener\(' "$SVC") |"
  echo "| \`removeListener(\` calls | $(count 'removeListener\(' "$SVC") |"
  echo "| \`addAnalyticsListener(\` | $(count 'addAnalyticsListener\(' "$SVC") |"
  echo "| \`removeAnalyticsListener(\` | $(count 'removeAnalyticsListener\(' "$SVC") |"
  echo "| \`setAudioAttributes(...true)\` (focus requests) | $(count 'setAudioAttributes\(.*true' "$SVC") |"
  echo "| \`MediaSession.Builder(\` | $(count 'MediaSession\.Builder\(' "$SVC") |"
  echo
  echo "Review: every \`addListener\` should have a matching \`removeListener\` on the player it was added to, and the number of"
  echo "\`ExoPlayer.Builder(\` sites should be explained by (primary + standby) x (initial build + decoder rebuild)."
  echo
  echo "## Threading"
  echo "- \`runBlocking\` uses: $(count 'runBlocking' "$SVC")"
  echo "- \`Dispatchers.IO\`/\`Default\` uses near player calls (review manually): $(count 'Dispatchers\.(IO|Default)' "$SVC")"
  echo "- \`Thread.sleep\` uses: $(count 'Thread\.sleep' "$SVC")"
fi
if [ -n "$PLAYBACK_DIR" ]; then
  echo
  echo "## Dual-player package (\`$PLAYBACK_DIR\`)"
  echo "- Kotlin files: $(ls "$PLAYBACK_DIR"/*.kt 2>/dev/null | wc -l)"
  echo "- \`TODO\`/\`FIXME\` markers:"
  grep -rnE 'TODO|FIXME' "$PLAYBACK_DIR" 2>/dev/null | sed 's/^/  - /' || true
fi
echo
echo "## Deprecated API usage in dual-player sources (from the compile log)"
if [ -f ci-logs/compile.log ]; then
  grep -E '^w: .*(playback|FelicityPlayerService|AudioProcessorManager|FelicityAudioSink)' ci-logs/compile.log \
    | grep -i 'deprecat' | sed 's/^/- /' || true
else
  echo "_compile log not available_"
fi
} > "$R"
echo "wrote $R"
