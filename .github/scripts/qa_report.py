#!/usr/bin/env python3
"""Builds the QA report and the failure report from everything the workflow jobs produced.

Usage: qa_report.py <artifacts-root> <out-dir>
Env  : GITHUB_SHA, GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT, GITHUB_REPOSITORY, GITHUB_SERVER_URL,
       VERSION_NAME, STRICT_LINT, JOB_RESULTS (JSON of `needs` context)

Rule taken from Felicity_Android_Test_Plan.pdf: a test is PASS only if it actually ran and passed.
Anything that was not executed is reported as NOT RUN - never silently as passed.
"""
import glob, json, os, re, sys, datetime
import xml.etree.ElementTree as ET
from collections import OrderedDict, defaultdict
from pathlib import Path

root, out = Path(sys.argv[1]), Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
E = os.environ.get
strict_lint = E("STRICT_LINT", "false") == "true"

# ----------------------------------------------------------------------------- load results
steps = {}      # id -> dict(status, rc, desc, log)
for tsv in root.rglob("ci-status/*.tsv"):
    try:
        sid, status, rc, desc = tsv.read_text().rstrip("\n").split("\t", 3)
    except ValueError:
        continue
    log = tsv.parent.parent / "ci-logs" / f"{sid}.log"
    steps[sid] = dict(status=status, rc=rc, desc=desc, log=log if log.exists() else None)

tests = {}      # (classname, method) -> dict(status, kind, msg)
for xml in root.rglob("TEST-*.xml"):
    kind = "instrumented" if "androidTest" in str(xml) else "unit"
    try:
        for tc in ET.parse(xml).getroot().iter("testcase"):
            st, msg = "PASS", ""
            for tag, name in (("failure", "FAIL"), ("error", "FAIL"), ("skipped", "SKIPPED")):
                el = tc.find(tag)
                if el is not None:
                    st = name
                    msg = ((el.get("message") or "") + "\n" + (el.text or "")).strip()
                    break
            tests[(tc.get("classname", ""), tc.get("name", ""))] = dict(status=st, kind=kind, msg=msg)
    except ET.ParseError:
        pass

def test_lookup(cls_suffix, method):
    hits = [v for (c, m), v in tests.items() if c.endswith(cls_suffix) and (method == "*" or m == method)]
    return hits

lint = defaultdict(int)
for xml in root.rglob("lint-results*.xml"):
    try:
        for issue in ET.parse(xml).getroot().iter("issue"):
            lint[issue.get("severity", "?")] += 1
    except ET.ParseError:
        pass

def read(p, default=""):
    if p is None: return default
    try: return Path(p).read_text().strip()
    except OSError: return default
def find_one(name):
    return next(iter(root.rglob(name)), None)

warn_file = find_one("compiler-warnings.txt")
n_warn = len(warn_file.read_text().splitlines()) if warn_file else None
dep_file = find_one("deprecated-api-warnings.txt")
n_dep = len(dep_file.read_text().splitlines()) if dep_file else None
merged_manifest = next((p for p in root.rglob("AndroidManifest*.xml") if "merged" in str(p)), None)
auto_declared = bool(merged_manifest and re.search(r"automotive_app_desc|com\.google\.android\.gms\.car",
                                                    merged_manifest.read_text(errors="ignore")))
job_results = json.loads(E("JOB_RESULTS", "{}") or "{}")

# ----------------------------------------------------------------------------- row evaluation
def from_steps(ids, note=""):
    got = [steps.get(i) for i in ids]
    if any(g is None for g in got):
        missing = [i for i, g in zip(ids, got) if g is None]
        return "NOT RUN", f"step(s) did not run: {', '.join(missing)}"
    if any(g["status"] == "FAIL" for g in got):
        return "FAIL", "; ".join(f"{i}: FAIL" for i, g in zip(ids, got) if g["status"] == "FAIL")
    not_pass = [g for g in got if g["status"] != "PASS"]      # NOT_RUN / SKIPPED recorded by a step
    if not_pass:
        return "NOT RUN", "; ".join(g["desc"] for g in not_pass)
    return "PASS", note

def from_tests(refs, partial=False, note=""):
    hits, missing = [], []
    for cls, m in refs:
        h = test_lookup(cls, m)
        (hits.extend(h) if h else missing.append(f"{cls}.{m}"))
    if missing and not hits:
        return "NOT RUN", "test results not found: " + ", ".join(missing)
    if any(h["status"] == "FAIL" for h in hits):
        return "FAIL", "failing test(s) in " + ", ".join(f"{c}.{m}" for c, m in refs)
    if missing:
        return "NOT RUN", "test results not found: " + ", ".join(missing)
    if any(h["status"] == "SKIPPED" for h in hits):
        return "NOT RUN", "test(s) skipped"
    return ("PARTIAL" if partial else "PASS"), note

NO_TEST  = ("NOT RUN", "No automated test exists for this item in the repository yet.")
DEVICE   = ("NOT RUN", "Needs audible playback / physical device or audio hardware; not automatable in CI.")
HARDWARE = ("NOT RUN", "Needs the physical audio route (USB DAC / Bluetooth / wired); an emulator cannot exercise it.")
MANUAL   = ("NOT RUN", "Needs a human (or a UI-driving harness that does not exist yet).")

CF = "CrossfadeMathTest"; TG = "DualPlayerTransitionGateTest"; OP = "DualPlayerOutputPolicyTest"
PLAN = OrderedDict()
PLAN["1.1 Build-time verification"] = [
    ("Kotlin compilation",    from_steps(["compile"])),
    ("Java compilation",      from_steps(["compile"])),
    ("Resource processing",   from_steps(["resources"])),
    ("Manifest validation (merge + content check)", from_steps(["manifest", "manifest-content"])),
    ("Dependency resolution", from_steps(["dependencies"])),
    ("APK packaging (debug)", from_steps(["debug-apk", "apk-verify"])),
    ("Release packaging (release variant + signing)", from_steps(["release-apk", "release-apk-verify"])),
]
PLAN["1.2 Automated unit tests"] = [
    ("Crossfade calculations", from_tests([(CF, "endpointsAreSilentOnOppositeSides"),
                                           (CF, "delayScalesWithPlaybackSpeedAndRejectsInvalidSpeed")])),
    ("Equal-power fading",     from_tests([(CF, "equalPowerKeepsSquaredGainConstant")])),
    ("Transition state machine (IDLE/PREPARING/READY/FADING/HANDING_OFF)",
        from_tests([(TG, "validTransitionMustFollowOrderedStates"), (TG, "cancellationAndInvalidationReturnToIdle")])),
    ("Promotion validation",   from_tests([(TG, "newerTransitionInvalidatesOlderCoroutineToken")], partial=True,
                                          note="Covers stale-token rejection only; standby-readiness validation has no test.")),
    ("Promotion rollback",     NO_TEST),
    ("Queue synchronization",  NO_TEST),
    ("Repeat-one / repeat-all", NO_TEST),
    ("Shuffle",                NO_TEST),
    ("Playback parameters (speed etc.)", NO_TEST),
    ("Output policy (exclusive route / overlap)", from_tests([(OP, "*")])),
    ("[extra] Independent DSP graph ownership (DualAudioProcessorSet)", from_tests([("DualAudioProcessorSetTest", "*")])),
]
PLAN["1.3 Static analysis and regression"] = [
    ("Android Lint", ("FAIL" if steps.get("lint", {}).get("status") == "FAIL" else
                      "PASS" if steps.get("lint") else "NOT RUN",
                      f"lint findings: {dict(lint) or 'none parsed'}" +
                      ("" if strict_lint else " (non-blocking for the job: strict_lint=false)"))),
    ("Kotlin compiler warnings / deprecated Media3 API usage",
        ("REVIEW", f"{n_warn if n_warn is not None else '?'} compiler warnings, "
                   f"{n_dep if n_dep is not None else '?'} deprecation warnings - see compiler-warnings.txt")),
    ("Threading / lifecycle / player ownership / listener registration / leaks / stale callbacks",
        ("REVIEW", "Heuristic counts generated (static-review.md); a human must review them.")),
    ("Regression: all other unit tests (queue, notification, settings, lyrics...)", None),  # filled below
]
PLAN["2.1 Basic playback"] = [
    (n, DEVICE) for n in ("Start playback", "Pause / resume", "Next / previous", "Seek", "Volume", "Playback speed")
] + [("Screen off / app backgrounded (app survives; audio not asserted)",
      (lambda r: ("PARTIAL" if r[0] == "PASS" else r[0], "Emulator: process stays alive, no crash/ANR. Audio not asserted."
                  if r[0] == "PASS" else r[1]))(from_steps(["background-foreground", "lock-unlock"])))]
PLAN["2.2 Dual-player and crossfade"] = [(n, DEVICE) for n in (
    "Normal crossfade", "Equal-power curve (perceived)", "Fade durations 0/2/5/10/15 s", "Active-player promotion exactly once",
    "Outgoing-player reuse as standby", "Repeated transitions (no leaks / stuck states)", "Rapid consecutive tracks")]
PLAN["2.3 Transition interruption and queue mutation"] = [(n, DEVICE) for n in (
    "Pause during fade", "Resume during fade", "Next / previous during fade", "Seek during fade", "Edit/replace queue during fade",
    "Enable shuffle during fade", "Change repeat mode during fade", "Change fade setting during fade", "Stop playback during fade")]
PLAN["2.4 MediaSession and Android integration"] = [(n, DEVICE) for n in (
    "Notification metadata/artwork", "Notification controls", "Lock-screen controls/metadata", "Bluetooth headset controls",
    "Audio focus / calls / navigation ducking", "MediaSession never reports outgoing track after handoff")] + [
    ("Android Auto", ("N/A", "Not declared in the merged manifest.") if (merged_manifest and not auto_declared)
                     else ("NOT RUN", "Cannot determine / declared but not tested."))]
PLAN["2.5 DSP, equalizer and visualizer"] = [(n, DEVICE) for n in (
    "Equalizer on/off", "Bass boost / virtualizer across promotion", "Volume normalization during handoff",
    "Visualizer shows active track", "DSP settings after promotion", "No duplicate effects after repeated promotion")]
PLAN["2.6 Audio output and hardware compatibility"] = [
    ("Built-in speaker", DEVICE), ("Wired headphones / USB-C adapter", HARDWARE), ("Bluetooth headphones", HARDWARE),
    ("External USB DAC", HARDWARE), ("AAudio / Oboe route", HARDWARE), ("Android AudioTrack overlap/fallback", DEVICE)]

def partial_from(ids, note):
    s, n = from_steps(ids, note)
    return ("PARTIAL", note) if s == "PASS" else (s, n)
PLAN["2.7 Lifecycle and stress"] = [
    ("Rotate; foreground/background", partial_from(["rotation", "background-foreground"], "Emulator: survives, no crash/ANR. No playback active.")),
    ("Lock/unlock during playback and during a fade", partial_from(["lock-unlock"], "Emulator: survives screen off/on. No playback/fade active.")),
    ("Disconnect Bluetooth / unplug USB DAC", HARDWARE),
    ("Change audio output while playing / fading", HARDWARE),
    ("Terminate/recreate UI process while service stays active", MANUAL),
    ("Stop and restart playback service", partial_from(["process-restart"], "Emulator: force-stop + relaunch is clean (kills service and UI together).")),
    ("30-60 minutes continuous playback", DEVICE),
    ("At least 50 consecutive transitions", DEVICE),
    ("Monitor memory/CPU/battery/glitches/leaks", ("REVIEW", "PSS before/after: " + read(find_one("memory-trend.txt"), "n/a").replace("\n", ", ")) if find_one("memory-trend.txt") else DEVICE),
]
# regression row: every unit test that is not one of the mapped ones
mapped = {CF, TG, OP, "DualAudioProcessorSetTest"}
other = [v for (c, m), v in tests.items() if v["kind"] == "unit" and c.split(".")[-1] not in mapped]
reg = PLAN["1.3 Static analysis and regression"]
idx = [i for i, (n, _) in enumerate(reg) if n.startswith("Regression")][0]
if not tests:
    reg[idx] = (reg[idx][0], ("NOT RUN", "No unit-test results were found."))
elif any(v["status"] == "FAIL" for v in other):
    reg[idx] = (reg[idx][0], ("FAIL", f"{sum(v['status']=='FAIL' for v in other)} failing of {len(other)}"))
else:
    reg[idx] = (reg[idx][0], ("PASS", f"{len(other)} other unit tests passed"))

# ----------------------------------------------------------------------------- gates
def gate1():
    need = ["dependencies", "compile", "resources", "manifest", "manifest-content", "debug-apk", "apk-verify", "unit-tests"]
    s, n = from_steps(need)
    if s != "PASS": return s, n
    if steps.get("lint", {}).get("status") != "PASS":
        return "FAIL", "unresolved lint findings (or lint did not run)" + ("" if strict_lint else " - job not failed because strict_lint=false")
    return "PASS", "build, unit tests, lint and installable debug APK all OK"
def device_gate(needed_hw_note):
    dev = [steps.get(i) for i in ("cold-launch", "background-foreground", "rotation", "lock-unlock", "process-restart")]
    if any(d and d["status"] == "FAIL" for d in dev): return "FAIL", "automated device smoke checks failed"
    return "NOT VERIFIED", needed_hw_note
G = OrderedDict()
G["Gate 1 - Build and automated tests"] = gate1()
G["Gate 2 - Playback and dual-player handoff"] = device_gate("requires on-device playback/crossfade tests (section 2.1-2.3); CI cannot run them")
G["Gate 3 - Android integration and routing"] = device_gate("requires MediaSession/DSP/output-route tests on real hardware (2.4-2.6)")
G["Gate 4 - Stress and regression"] = device_gate("only emulator lifecycle smoke ran; 30-60 min playback and 50-transition stress run still required")

# ----------------------------------------------------------------------------- render qa-report.md
icon = {"PASS": "✅", "FAIL": "❌", "NOT RUN": "⬜", "PARTIAL": "🟡", "REVIEW": "🔎", "N/A": "➖", "NOT VERIFIED": "⬜"}
repo, run = E("GITHUB_REPOSITORY", ""), E("GITHUB_RUN_ID", "")
run_url = f"{E('GITHUB_SERVER_URL','https://github.com')}/{repo}/actions/runs/{run}" if run else ""
L = []
L += ["# Felicity QA report (generated by CI)", "",
      "> This report follows *Felicity_Android_Test_Plan.pdf*. A row is **PASS** only if CI actually executed it and it passed. "
      "Everything CI cannot execute is **NOT RUN**; PARTIAL means only a subset of the row was exercised.", ""]
L += ["## Release-readiness gates", "", "| Gate | Result | Detail |", "|---|---|---|"]
for g, (s, n) in G.items(): L.append(f"| {g} | {icon.get(s,'')} **{s}** | {n} |")
L += ["", "Release rule from the plan: do not label the migration stable/release-ready while a gate has unresolved critical failures. "
      "Gates 2-4 can only be cleared by on-device testing.", ""]
counts = defaultdict(int)
for sec, rows in PLAN.items():
    L += [f"## {sec}", "", "| Test | Result | Evidence / note |", "|---|---|---|"]
    for name, (s, n) in rows:
        counts[s] += 1; L.append(f"| {name} | {icon.get(s,'')} {s} | {n} |")
    L.append("")
L += ["## Totals", "", " · ".join(f"**{k}**: {v}" for k, v in sorted(counts.items())), ""]
ut = [v for v in tests.values() if v["kind"] == "unit"]; it = [v for v in tests.values() if v["kind"] == "instrumented"]
L += ["## Raw counts", "",
      f"- Unit tests: {len(ut)} run, {sum(v['status']=='FAIL' for v in ut)} failed, {sum(v['status']=='SKIPPED' for v in ut)} skipped",
      f"- Instrumented tests: {len(it)} run, {sum(v['status']=='FAIL' for v in it)} failed, {sum(v['status']=='SKIPPED' for v in it)} skipped",
      f"- Lint: {dict(lint) or 'no lint XML found'}", ""]
dm, av = read(find_one("device-model.txt"), "not run"), read(find_one("android-version.txt"), "not run")
L += ["## Test record (plan section 5)", "", "| Field | Value |", "|---|---|",
      f"| Test run ID / date | {run or 'local'} (attempt {E('GITHUB_RUN_ATTEMPT','1')}) / {datetime.datetime.now(datetime.timezone.utc):%Y-%m-%d %H:%M UTC} |",
      f"| Git commit / source revision | {E('GITHUB_SHA','unknown')} |",
      f"| APK version / build variant | {E('VERSION_NAME','unknown')} / foss debug + foss release |",
      f"| Device model | {dm} (emulator) |", f"| Android version / build | {av} {read(find_one('android-build.txt'))} |",
      "| Audio route / DAC / headset | Emulator virtual audio device only - no physical route tested |",
      "| Crossfade duration / playback speed | Not exercised (no audible playback in CI) |",
      f"| Result | Gate 1: {G['Gate 1 - Build and automated tests'][0]}; Gates 2-4: NOT VERIFIED unless FAIL shown above |",
      f"| Logcat / screenshot / artifact | {run_url or 'see workflow artifacts'} (device-logcat, instrumented-test-reports, unit-test-reports) |",
      "| Issue reference / notes | See failure-report.md |", ""]
(out / "qa-report.md").write_text("\n".join(L))

# ----------------------------------------------------------------------------- failure-report.md
def tail(path, n=60):
    try: return "\n".join(Path(path).read_text(errors="ignore").splitlines()[-n:])
    except OSError: return "(log not available)"
def gradle_errors(path, n=40):
    try: lines = Path(path).read_text(errors="ignore").splitlines()
    except OSError: return ""
    pick = [i for i, l in enumerate(lines) if re.search(r"^e: |What went wrong|FAILED|error:|Execution failed", l)]
    keep, last = [], -10
    for i in pick:
        if i < last + 6: continue          # already shown by the previous excerpt
        keep += lines[i:i + 6] + ["..."]; last = i
        if len(keep) >= n: break
    return "\n".join(keep[:n])

F = ["# Felicity failure report (generated by CI)", ""]
failed_steps = [(k, v) for k, v in steps.items() if v["status"] == "FAIL"]
failed_tests = [(k, v) for k, v in tests.items() if v["status"] == "FAIL"]
bad_jobs = {k: v["result"] for k, v in job_results.items() if v.get("result") not in ("success", "skipped")}
if not (failed_steps or failed_tests or bad_jobs):
    F += ["**No failures were recorded.** (NOT RUN items are listed in qa-report.md; they are not failures.)", ""]
if bad_jobs:
    F += ["## Jobs that did not succeed", ""] + [f"- `{k}`: **{v}**" for k, v in bad_jobs.items()] + [""]
if failed_steps:
    F += ["## Failed CI steps", ""]
    for k, v in failed_steps:
        F += [f"### `{k}` - {v['desc']} (exit {v['rc']})", ""]
        err = gradle_errors(v["log"]) if v["log"] else ""
        if err: F += ["Key errors:", "```", err, "```", ""]
        F += ["Last lines of the log:", "```", tail(v["log"]) if v["log"] else "(log not available)", "```", ""]
        F += ["Re-run just this step locally with the command in the log header (`$ ./gradlew ...`), then re-run the failed test (plan 3.1).", ""]
if failed_tests:
    F += ["## Failing tests", "", "| Kind | Test | Message |", "|---|---|---|"]
    for (c, m), v in failed_tests:
        F.append(f"| {v['kind']} | `{c}.{m}` | {v['msg'].splitlines()[0][:200] if v['msg'] else ''} |")
    F.append("")
    for (c, m), v in failed_tests[:15]:
        F += [f"<details><summary><code>{c}.{m}</code></summary>", "", "```", "\n".join(v["msg"].splitlines()[:25]), "```", "</details>", ""]
if lint.get("Error") or lint.get("Fatal"):
    F += ["## Lint", "", f"Findings by severity: {dict(lint)}. Open the lint HTML report in the `lint-reports` artifact.", ""]
F += ["## Debugging loop (plan 3.1)", "",
      "1. Capture Gradle output (`build-logs`), Logcat (`device-logcat`), steps and device details (qa-report.md).",
      "2. Identify the module, class and transition state responsible.",
      "3. Make the smallest safe change, rebuild the same variant, re-run the failed test.",
      "4. Run the regression tests (queue, session, output-route).", "5. Record the result, APK version and evidence.", ""]
(out / "failure-report.md").write_text("\n".join(F))

print((out / "qa-report.md").read_text()[:200])
print(f"failed steps={len(failed_steps)} failed tests={len(failed_tests)} bad jobs={len(bad_jobs)}")
