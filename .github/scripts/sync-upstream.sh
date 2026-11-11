#!/usr/bin/env bash
# Syncs this fork with the official Felicity repo's newest RELEASE, keeping the removed
# trial/purchase code removed. Run by .github/workflows/sync-upstream.yml (daily), or by hand.
#
#  - finds the newest official release (GitHub Releases of $UPSTREAM_REPO)
#  - if it is already merged into this branch: does nothing
#  - otherwise merges that release's commit into a new branch `upstream-sync/<tag>`, re-applies
#    this fork's rules (see .github/sync/), and:
#        clean + passes the paywall guard  -> pushes the branch, opens a PR, starts the APK build
#        anything else                      -> opens an issue explaining what needs a human
#
# Env: UPSTREAM_REPO (Hamza417/Felicity)  UPSTREAM_URL  SYNC_TAG (force a tag)
#      INCLUDE_PRERELEASES (true)  DRY_RUN (0)  BASE_BRANCH (current branch)  GH_TOKEN
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

UPSTREAM_REPO="${UPSTREAM_REPO:-Hamza417/Felicity}"
UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/${UPSTREAM_REPO}.git}"
INCLUDE_PRERELEASES="${INCLUDE_PRERELEASES:-true}"
DRY_RUN="${DRY_RUN:-0}"
BASE_BRANCH="${BASE_BRANCH:-$(git rev-parse --abbrev-ref HEAD)}"
SYNC_DIR=".github/sync"

log() { echo "[sync] $*"; }
summary() { if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then echo "$*" >> "$GITHUB_STEP_SUMMARY"; fi; }

open_issue() { # title, body
  local title="$1" body="$2"
  if [ "$DRY_RUN" = "1" ]; then log "DRY_RUN issue: $title"; echo "$body"; return; fi
  if gh issue list --state open --search "\"$title\" in:title" --json title --jq '.[].title' | grep -qxF "$title"; then
    log "Issue already open: $title"; return
  fi
  gh issue create --title "$title" --body "$body" || log "Could not create issue (check 'issues: write' permission)."
}

# ---------------------------------------------------------------- 1. which release?
if [ -n "${SYNC_TAG:-}" ]; then
  TAG="$SYNC_TAG"
else
  RELEASES="$(mktemp)"
  gh api "repos/${UPSTREAM_REPO}/releases?per_page=30" > "$RELEASES"
  TAG="$(jq -r --arg pre "$INCLUDE_PRERELEASES" \
        '[.[] | select(.draft == false) | select($pre == "true" or .prerelease == false)]
         | sort_by(.published_at) | last | .tag_name // empty' "$RELEASES")"
fi
if [ -z "$TAG" ]; then log "No official release found."; summary "No official release found."; exit 0; fi
log "Newest official release: $TAG"

# ---------------------------------------------------------------- 2. fetch it
git remote remove upstream 2>/dev/null || true
git remote add upstream "$UPSTREAM_URL"
git fetch --no-tags -q upstream '+refs/heads/*:refs/remotes/upstream/*'
git fetch --no-tags -q upstream "+refs/tags/${TAG}:refs/tags/upstream/${TAG}"
TAGSHA="$(git rev-parse "refs/tags/upstream/${TAG}^{commit}")"
BRANCH="upstream-sync/${TAG}"

if git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
  log "Branch $BRANCH already exists on origin — this release was already handled. (Delete the branch to retry.)"
  summary "Release \`$TAG\` was already handled (branch \`$BRANCH\` exists)."
  exit 0
fi

# ---------------------------------------------------------------- 3. branch + shared history
git checkout -q -B "$BRANCH" "$BASE_BRANCH"
if ! git merge-base HEAD "$TAGSHA" >/dev/null 2>&1; then
  BASE_SHA="$(tr -d '[:space:]' < "$SYNC_DIR/upstream-base.txt")"
  log "No shared history with upstream yet — recording $BASE_SHA as the merge base (no content change)."
  git cat-file -e "${BASE_SHA}^{commit}" 2>/dev/null || { log "Base commit $BASE_SHA not found upstream."; exit 1; }
  git merge -q -s ours --allow-unrelated-histories --no-edit \
      -m "Record upstream ${BASE_SHA:0:7} as merge base (no content change)" "$BASE_SHA"
fi
if git merge-base --is-ancestor "$TAGSHA" HEAD; then
  log "Release $TAG is already merged. Nothing to do."
  summary "Up to date with \`$TAG\`."
  git checkout -q "$BASE_BRANCH"; git branch -q -D "$BRANCH"; exit 0
fi

# ---------------------------------------------------------------- 4. merge
log "Merging $TAG ($TAGSHA) ..."
set +e
git merge --no-commit --no-ff "$TAGSHA" > /tmp/merge.log 2>&1
set -e
tail -n 5 /tmp/merge.log || true

# ---------------------------------------------------------------- 5. re-apply fork rules
# (a) our .github (workflows, scripts, sync rules) always wins
git rm -rfq --ignore-unmatch .github >/dev/null 2>&1 || true
rm -rf .github
git checkout -q HEAD -- .github

# (b) files this fork deliberately deleted: if upstream only modified them, keep them deleted
git status --porcelain | awk '$1=="DU"{print $2}' | while read -r f; do git rm -q -- "$f" || true; done

# (c) translations: no local edits, so upstream's version wins on conflict (cleaned below)
git status --porcelain | awk '$1=="UU"{print $2}' | grep -E '/values-[^/]+/strings\.xml$' | while read -r f; do
  git checkout -q --theirs -- "$f" && git add -- "$f"
done || true

# (d) paths that must not exist
grep -vE '^\s*(#|$)' "$SYNC_DIR/strip-paths.txt" | while read -r pat; do
  git rm -rfq --ignore-unmatch -- ":(glob)$pat" >/dev/null 2>&1 || true
done

# (e) string resources of the removed features; and translations of strings that no longer exist
python3 - "$SYNC_DIR/removed-strings.txt" <<'PY'
import re, glob, sys
removed = set(l.strip() for l in open(sys.argv[1]) if l.strip())
def strip(path, drop):
    s = open(path, encoding='utf8').read()
    if '<<<<<<<' in s: return False
    out = s
    for n in drop:
        out = re.sub(r'[ \t]*<string name="%s"[^>]*>.*?</string>[ \t]*\n?' % re.escape(n), '', out, flags=re.S)
    if out != s:
        open(path, 'w', encoding='utf8').write(out)
        return True
    return False
for res in glob.glob('*/src/main/res'):
    default = res + '/values/strings.xml'
    try: base = open(default, encoding='utf8').read()
    except OSError: continue
    if '<<<<<<<' not in base:
        strip(default, removed)
        base = open(default, encoding='utf8').read()
    # A translation is only dropped if NO file in the default values/ folder defines the string.
    known = set()
    for d in glob.glob(res + '/values/*.xml'):
        known |= set(re.findall(r'<string name="([^"]+)"', open(d, encoding='utf8').read()))
    for f in glob.glob(res + '/values-*/strings.xml'):
        s = open(f, encoding='utf8').read()
        names = set(re.findall(r'<string name="([^"]+)"', s))
        strip(f, names - known)
PY
git add -A

# ---------------------------------------------------------------- 6. problems?
UNRESOLVED="$(git diff --name-only --diff-filter=U || true)"
MARKERS="$(git grep -lE '^(<<<<<<<|>>>>>>>) ' -- . ':(exclude).github' ':(exclude)*.md' 2>/dev/null || true)"
GUARD=""
while read -r f; do
  [ -z "$f" ] && continue
  case "$f" in *.md|.github/*) continue;; esac
  hits="$(git diff --cached -U0 HEAD -- "$f" | grep -E '^\+[^+]' | grep -E -f <(grep -vE '^\s*(#|$)' "$SYNC_DIR/forbidden-patterns.txt") || true)"
  if [ -n "$hits" ]; then GUARD+=$'\n'"$f:"$'\n'"$(echo "$hits" | head -5)"; fi
done < <(git diff --cached --name-only HEAD)

HOWTO="Manual merge:
\`\`\`
git remote add upstream ${UPSTREAM_URL}
git fetch upstream --tags
git checkout -b upstream-sync/${TAG} ${BASE_BRANCH}
git merge -s ours --allow-unrelated-histories \$(cat ${SYNC_DIR}/upstream-base.txt)   # only if the repo has never been synced
git merge ${TAGSHA}
# fix conflicts, re-delete anything listed in ${SYNC_DIR}/strip-paths.txt, then build and open a PR
\`\`\`
Merge the PR with a **merge commit** (not squash/rebase) so future syncs know what is already merged."

if [ -n "$UNRESOLVED$MARKERS" ]; then
  log "Conflicts need a human."
  open_issue "Upstream sync: ${TAG} needs a manual merge" "Release \`${TAG}\` of ${UPSTREAM_REPO} conflicts with this fork in:
\`\`\`
$(printf '%s\n%s' "$UNRESOLVED" "$MARKERS" | sort -u)
\`\`\`
${HOWTO}"
  summary "Conflicts merging \`$TAG\` — issue opened."
  git merge --abort 2>/dev/null || git reset -q --hard HEAD
  git checkout -q "$BASE_BRANCH"; git branch -q -D "$BRANCH"; exit 0
fi

if [ -n "$GUARD" ]; then
  log "Paywall guard tripped."
  open_issue "Upstream sync: ${TAG} re-adds removed trial/purchase code" "The merge of \`${TAG}\` is conflict-free but ADDS lines matching \`${SYNC_DIR}/forbidden-patterns.txt\`:
\`\`\`${GUARD}
\`\`\`
Nothing was pushed. Either add paths to \`${SYNC_DIR}/strip-paths.txt\` / patterns to the guard and re-run, or merge by hand.
${HOWTO}"
  summary "Paywall guard tripped for \`$TAG\` — issue opened."
  git merge --abort 2>/dev/null || git reset -q --hard HEAD
  git checkout -q "$BASE_BRANCH"; git branch -q -D "$BRANCH"; exit 0
fi

# ---------------------------------------------------------------- 7. commit, push, PR, build
git commit -q -m "Merge upstream release ${TAG}

Upstream commit: ${TAGSHA}
Fork rules re-applied (${SYNC_DIR}): paywall paths removed, .github kept."
log "Merged cleanly: $(git rev-parse --short HEAD)"
if [ "$DRY_RUN" = "1" ]; then
  log "DRY_RUN: would push $BRANCH, open a PR and start the build."
  git diff --stat "$BASE_BRANCH"..HEAD | tail -3
  exit 0
fi

if ! git push -q origin "$BRANCH" 2> /tmp/push.log; then
  cat /tmp/push.log
  open_issue "Upstream sync: ${TAG} could not be pushed" "Pushing \`${BRANCH}\` failed:
\`\`\`
$(tail -5 /tmp/push.log)
\`\`\`
If it mentions \`workflow\` permissions, create a personal access token with \`repo\` + \`workflow\` scopes and save it as the repository secret \`SYNC_TOKEN\`."
  exit 0
fi

PR_BODY="Automatic merge of official release **${TAG}** (\`${TAGSHA:0:10}\`) from ${UPSTREAM_REPO}.

* Trial / purchase / Play-billing code is kept removed (rules: \`${SYNC_DIR}/\`); a guard checked that none came back.
* A debug + release build with an emulator smoke test was started on this branch — check the **Actions** tab and merge only when it is green.
* **Merge with a merge commit** (not squash or rebase) so the next sync knows what is already merged."
if gh pr create --base "$BASE_BRANCH" --head "$BRANCH" --title "Sync with upstream release ${TAG}" --body "$PR_BODY"; then
  log "PR opened."
else
  open_issue "Upstream sync: ${TAG} is ready (open the PR manually)" "Branch \`${BRANCH}\` was pushed but a PR could not be created automatically. Enable *Settings → Actions → General → Allow GitHub Actions to create and approve pull requests*, or open it yourself."
fi
gh workflow run build-apk.yml --ref "$BRANCH" -f build_debug=true -f build_release=true -f publish_release=false -f smoke_test=true \
  && log "Build started on $BRANCH." || log "Could not start the build workflow."
summary "Opened sync for \`$TAG\` on branch \`$BRANCH\`."
