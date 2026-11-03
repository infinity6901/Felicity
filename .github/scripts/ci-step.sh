#!/usr/bin/env bash
# Source this file, then wrap every check in `ci_step`.
#
#   ci_step <id> "<description>" <command...>
#
# - runs the command with its output streamed to the Actions log AND to ci-logs/<id>.log
# - records PASS/FAIL in ci-status/<id>.tsv  (id <TAB> status <TAB> exit-code <TAB> description)
# - NEVER aborts the calling shell, so one failing check does not hide the results of the
#   following ones. A later "gate" step decides whether the job as a whole fails.
#
# Optional: set CI_FAIL_IF_LOG_MATCHES to an extended regex to mark a step FAIL when its own
# log matches even though the command exited 0 (e.g. "FAILED" lines in dependency reports).

CI_LOG_DIR="${CI_LOG_DIR:-ci-logs}"
CI_STATUS_DIR="${CI_STATUS_DIR:-ci-status}"
mkdir -p "$CI_LOG_DIR" "$CI_STATUS_DIR"

ci_step() {
  local id="$1" desc="$2"; shift 2
  local log="$CI_LOG_DIR/$id.log" rc status="PASS"
  echo "::group::[$id] $desc"
  echo "\$ $*"
  set -o pipefail
  "$@" 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
  set +o pipefail
  if [ "$rc" -ne 0 ]; then
    status="FAIL"
  elif [ -n "${CI_FAIL_IF_LOG_MATCHES:-}" ] && grep -Eq "$CI_FAIL_IF_LOG_MATCHES" "$log"; then
    status="FAIL"; rc=1
  fi
  printf '%s\t%s\t%s\t%s\n' "$id" "$status" "$rc" "$desc" > "$CI_STATUS_DIR/$id.tsv"
  echo "::endgroup::"
  if [ "$status" = "FAIL" ]; then
    echo "::error title=$id failed::$desc (exit $rc) - see ci-logs/$id.log in the build-logs artifact"
  else
    echo "[$id] PASS"
  fi
  return 0
}

# Record a result without running anything (e.g. a check that is skipped on this runner).
ci_record() {  # ci_record <id> <PASS|FAIL|SKIPPED|NOT_RUN> "<description>"
  printf '%s\t%s\t0\t%s\n' "$1" "$2" "$3" > "$CI_STATUS_DIR/$1.tsv"
}
