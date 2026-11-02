# Upstream sync rules

`.github/workflows/sync-upstream.yml` runs daily. When the official repo (Hamza417/Felicity) publishes
a new **release**, it merges that release into a branch `upstream-sync/<tag>`, re-applies the rules in
this folder, opens a PR and starts the APK build on the branch. It never touches `master` itself.

| File | Purpose |
|------|---------|
| `upstream-base.txt` | Upstream commit this fork was last merged against (used once, to link the histories). |
| `strip-paths.txt` | Globs deleted after every merge — keeps the trial / purchase / Play-billing code removed. |
| `removed-strings.txt` | String names removed with that code; stripped from the default strings file. Translations of strings missing from the default file are dropped automatically. |
| `forbidden-patterns.txt` | Guard: if a merge ADDS a line matching one of these, no PR is opened — an issue is filed instead. |

Also on every sync: `.github/` is always kept as ours (upstream's workflows are ignored), and files this
fork deleted stay deleted when upstream merely edits them.

**Merge the sync PR with a merge commit** (not squash / rebase), otherwise the next sync can't tell what
is already merged.

Run it by hand: Actions → "Sync with upstream release" → Run workflow (`dry_run` to just test,
`force_tag` to try a specific release).
