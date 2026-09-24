# Phase 1 Report — Stability & Crash Fixes + UI Polish

Branch: `phase1-stability` (based on `main` @ `3f5d880`, v1.8.0)
Build: `./gradlew assembleDebug` **PASS** (app-debug.apk generated after every step).
Tests: `:agent-runtime:testDebugUnitTest` chat.* + util.* suites **PASS** (441 tests; 1 pre-existing failure fixed, see Test hygiene).

Method: every audit claim was verified against the real code before touching it. Line numbers below are **actual file:line at this branch tip** (post-fix where marked "now", pre-fix where marked "was"). Nothing was invented; items that did not reproduce are marked **not an issue** and were not touched (except where explicitly noted as a related fix in the same file).

---

## The true cause of "Terminal Closed" / lost output

There were **two separate mechanisms**, both in the foreground-command path:

1. **Session destroyed after every foreground command** — `TerminalBuiltin.executeForegroundLocal` opens a one-shot session via `TerminalSessionPool.openAndExecute`, then a `finally` block called `closeForegroundSession(outcome)` on **every** outcome, including Timeout (was TerminalBuiltin.kt:191-195 + :396). Meanwhile the libterm exec timeout (`TerminalCommandExecutor.exec`, libterm-runtime `TerminalCommandExecutor.kt:87-120`) does **not** kill the timed-out process — `withTimeoutOrNull` just returns a partial snapshot and the process keeps running in the PTY. So on timeout the code closed a still-running session: the PTY died, the process was killed with it, and all output after the timeout was destroyed. The tool description (was :44-47) contradicted this by claiming the working directory "persists between calls within a session" — for foreground commands there *is* no session between calls.

2. **Unbounded collector buffers as an amplifier** — for background/interactive sessions, `collectorJob` appended every chunk to `StringBuilder`s with no cap (was TerminalSessionPool.kt:525-534 async, :867-877 interactive). A long-running command (`ping`, `logcat`, `yes`) grew the buffers until OOM/low-memory kill — which also surfaces to the user as the app dying mid-output.

Fixes: A1 (description), A2 (timeout → readable background session), A4 (buffer cap). Details below.

---

## A. Terminal — highest priority

### A1. Description vs reality — **fixed**
- Verified: description claimed persistence at TerminalBuiltin.kt:44-47 (old); reality: `executeForegroundLocal` (TerminalBuiltin.kt:139-196) opens + closes a fresh session per call (`closeForegroundSession` now :401-424).
- Changed: description rewritten (now TerminalBuiltin.kt:44-70). It now states foreground commands are ONE-SHOT sessions, cwd/env do **not** persist between foreground calls, and points to `workdir` / background mode for state carry-over.
- Manual test: ask the agent to run `cd /data/local/tmp` (foreground), then `pwd` (foreground) → second call shows the previous cwd, proving non-persistence; the description no longer promises otherwise.

### A2. Timed-out foreground command loses output — **fixed**
- Verified: on Timeout, `closeForegroundSession` closed the session (old TerminalBuiltin.kt:396), discarding the still-running process (see cause analysis above); the response had no session_id, so the output could never be retrieved.
- Changed:
  - `TerminalSessionPool.promoteToInteractive()` (now TerminalSessionPool.kt:900-917) attaches an interactive collector to the surviving session so post-timeout output accumulates.
  - Timeout branch no longer closes (now TerminalBuiltin.kt:414 `Timeout -> Unit`); it promotes and returns `session_id` + partial output + instructions (now :169-180).
  - `TerminalToolResponse.commandTimeoutFlat` gained optional `sessionId` (now TerminalToolResponse.kt:33-53) → `{"stdout": "...", "stderr": "...", "session_id": "a0a1", "error": {"code": "TIMEOUT", "message": "... poll session_id=\"a0a1\" with action=\"read\" ..."}}`.
- Manual test: run `{"command":"sleep 30 && echo done","timeout":2}` → response contains `session_id`; `action="read"` on that id after ~30s shows `done`; `action="close"` releases it.

### A3. executionLocks mutex stuck on cancellation — **not an issue** (verified, untouched)
- `executeBlocking`: `tryLock()` (TerminalSessionPool.kt:416) + `finally { executeLock.unlock() }` (now :486-488) — unlock is guaranteed even on cancellation while suspended in `exec`.
- `startAsync`: after `tryLock` (:507) there is **no suspension point** before `invokeOnCompletion` registration (:570-578), so a cancelled caller cannot skip it; completion (normal, failed, or cancelled) always routes through `completeAsync` (:964-973) → `unlockIfLocked` (:975-979). A cancelled `execJob` on a dead scope fires the handler immediately at registration with a cancellation cause — still unlocked.
- `closeAll`: removes locks and calls `unlockIfLocked` per async state (:738-742); `close()` does the same (:698-702). Locks held by an in-flight `executeBlocking` are released by its own `finally` regardless of map removal.
- Conclusion: the audit's claim described an older revision; current code already guarantees release on all three paths.

### A4. Unbounded collector output buffers — **fixed**
- Verified: async collector appended without bound (old TerminalSessionPool.kt:529-531); interactive collector likewise (old :871-873).
- Changed: new `CappedBuffer` (now TerminalSessionPool.kt:1153-1188, tests in `agent-runtime/src/test/.../CappedBufferTest.kt`) caps each stream at `MAX_COLLECTED_CHARS = 512K chars` (now :51). Overflow trims from the **head** (~half the cap per trim, amortized), DELTA offsets are rewound by the dropped amount so "already consumed" semantics stay correct, and SNAPSHOT returns only the retained tail — matching the truncateTail output philosophy. Wired into both the async collector (:528-529) and interactive collector (:872-873).
- Manual test: `background=true` + `yes | head -c 50M` (or a long `logcat`), then poll `action="read"` repeatedly → memory stays flat (~1-2MB/stream max), reads keep returning fresh tail output.

### A5. `ContextProvider.await()` with no timeout — **fixed** (4 audited sites + 2 same-pattern sites)
- Verified: `XProvider.await()` was an unbounded `CompletableDeferred.await()` (XProvider.kt:11-13 old). Confirmed call sites with no timeout: TerminalSessionPool.kt:813, PyRuntime.kt:283, AppInfoProvider.kt:12, LiveVisionController.kt:190 — all exactly as the audit stated. Additionally the same pattern existed at AccessibilityController.kt:430 and :494.
- Changed:
  - `XProvider.await(timeoutMillis): T?` added (now XProvider.kt:23-27); existing `await()` kept for call sites whose semantics intentionally block (Xposed host init).
  - TerminalSessionPool:818 → 10s wait, then clear `IllegalStateException` (terminal init fails with a reason instead of hanging forever).
  - PyRuntime:285 → 10s wait, clear error; importantly this path runs **inside `connectionMutex`**, so the old code would have poisoned every later python exec with a permanent hang.
  - AppInfoProvider:15 → 10s wait, clear error.
  - LiveVisionController:191 → 5s wait, `null` → pixel screenshot skipped (best-effort preserved; the structural frame still renders). Note: the old `runCatching { await() }` only caught exceptions — it could not time out.
  - AccessibilityController:436 (refreshNodeCache, throws → tool failure) and :505 (searchNodes, returns `Result.failure`) → 10s wait.
- Manual test: hard to trigger without forcing `provide()` to never run; regression-covered by compile-time contract (`await(timeout)`) — grep confirms no unbounded `ContextProvider.await()` remains in agent-runtime foreground paths.

### A6. ToolOutputTruncator loses full output when export dir is null — **fixed**
- Verified: `defaultExportDir()` returned `null` whenever the context wasn't provided yet (old ToolOutputTruncator.kt:25-28) → `filterForAgent` truncated **without** exporting, and the `[Full output: ...]` hint was simply absent — output beyond 50KB/2000 lines was unrecoverable.
- Changed (ToolOutputTruncator.kt):
  - `resolveExportDir(timeoutMs=3000)` (now :37-42): brief bounded wait for the context instead of instant give-up.
  - `exportDirForContext()` (now :49-55): filesDir/tool_output, falling back to **cacheDir**/tool_output when filesDir is unusable (some Xposed host environments) — the export location degrades, the full output is not lost.
  - Callers: `TerminalSessionPool.exportDir()` is now suspend and uses `resolveExportDir()` (now :986-987).
- Honest limitation: with **no** Context at all there is no `cacheDir` either (it comes from Context); after the 3s wait the function still returns null and only truncation happens. That case is now bounded and logged rather than silent.
- Manual test: from a cold app start, immediately run a command producing >50KB output → response now ends with `[Full output: <path>]`; the file is readable via `terminal cat`.

---

## B. Null-safety crashes (`!!` → structured handling) — **fixed**

| File | Audit claim | Reality at this branch | Fix |
|---|---|---|---|
| AccessibilityController | ~427, 732, 773, 888 | `!!` at 427 and 732 only; 773 was already safe (`serviceInstance?.dispatchGesture(...) ?: false`, now :789-790); **no `!!` exists at 888** | 427 → `serviceInstance?.windowRoot ?: throw RuntimeException("Accessibility service disconnected")` (now :430-433); 732 → local `serviceInstance` + structured `SERVICE_UNAVAILABLE` failure with shell fallback intact (now :749-756) |
| AutomationHub | ~113, 194, 229, 298 | all four confirmed exactly | 113 → local `appCtx` (now :111-115); 194/229/298 → `?.let { runCatching { unregister/remove } }` (now :221, :259, :331) — concurrent `reloadTriggers()` could null the field between check and `!!` |
| BackgroundTaskHub | ~84 | confirmed | `ensureChannels(appCtx)` local val (now :82-86) |
| PointerOverlay | ~273 | confirmed at 273-274 (`view!!`, `wm!!`) | local snapshot vals `wmRef/viewRef/lpRef` so the null check and use read the same fields (now :270-283) |

- Manual test (accessibility): enable accessibility, start a screen operation, toggle the accessibility service off from quick settings mid-operation → tool returns a `SERVICE_UNAVAILABLE`-style failure instead of crashing; app remains alive.

---

## C. Automation reliability — **fixed**

### C1. Unbounded queue + trigger/service race
- Verified: `Channel<TriggerHit>(Channel.UNLIMITED)` (old AutomationHub.kt:89). The "queue full, dropped" branch (old :559-562) was **dead code** — `trySend` on an unlimited channel always succeeds, so a full queue was never observable; growth was unbounded while the single consumer can spend 1-6 minutes per AGENT hit (`waitUntilAgentFree` up to 60s + `withTimeout(AGENT_TURN_TIMEOUT_MS)` = 5min).
- Race verified: `onServiceStarted` set `_serviceRunning = true` **immediately**, while trigger loading was async; events arriving in that window matched an **empty** trigger list (`matchNotificationTrigger` → null, old :466) and were silently lost.
- Changed (AutomationHub.kt):
  - Queue → `Channel(32, onBufferOverflow = BufferOverflow.DROP_OLDEST)` (now :99) with the drop policy documented in-code: newest event wins; stale queued events (whose battery/notification context aged out) are the ones dropped. Cooldown bookkeeping and enqueue are now always consistent.
  - Arming moved after first successful trigger load: `onServiceStarted` (now :531-554) launches `reloadTriggersNow()` (now :152-166) and only then sets `_serviceRunning = true`; load failure still arms (availability preferred) but logs and marks the activity log.
- Manual test: arm 3+ notification triggers, generate a burst of 50 matching notifications (e.g. via `adb shell cmd notification post`) → process RSS stays flat, newest events processed, no OOM; start proactive mode and immediately fire a matching notification → it is matched once triggers finish loading (log line `reloadTriggers total=… armed=…` precedes `proactive mode ON`).

### C2. DownloadObserver hard-coded path + stop() check
- Verified: `File("/storage/emulated/0/Download")` hard-coded (old DownloadObserver.kt:18 and :56). `stop()` itself was correct (old :47-50, called from `syncDownloadObserver` old :161) — the real defect was `start()`: if the directory was missing it returned silently while the Hub kept the observer marked as running (old :155-159), so FILE_DOWNLOAD triggers never fired and never retried.
- Changed (DownloadObserver.kt):
  - `resolveDownloadDir()` (now :70-79): `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` — resolves the **current user's** external storage (multi-user correct; work-profile downloads belong to another user/process and were never visible anyway) — with the legacy `/storage/emulated/0/Download` path as fallback for odd ROMs.
  - `start(): Boolean` (now :30) reports failure; `syncDownloadObserver` (AutomationHub.kt:172-190) only keeps the observer when `start()` succeeded, otherwise leaves it null so the next `reloadTriggers()` retries.
- Manual test: on a device, download a `.pdf` into Downloads with a FILE_DOWNLOAD trigger armed → trigger fires with the file path; on a multi-user build (`adb shell am create-user` + switch), the observer watches that user's Downloads, not user 0's.

---

## D. Safety — **fixed** (D1 was already handled)

### D1. FileManagerBuiltin unzip zip-slip — **not an issue** (verified, untouched)
- The protection already exists at FileManagerBuiltin.kt:515-522: each entry is resolved `File(dest, entry.name)` and its **canonical path** must stay inside `dest.canonicalPath` (prefix + separator) or equal it; otherwise the entry is skipped. Absolute-path entries are neutralized by `File(parent, child)` on Unix (leading `/` is dropped into a relative parse), and `..` escapes fail the canonical containment check. Symlinks are not creatable via `ZipInputStream` (entries are written as plain files). No change made, per instructions.

### D2. Xposed Entrance.kt host crash — **fixed**
- Verified: `context.createPackageContext(XValues.myPackageName, 0)` (Entrance.kt:87 old) ran unprotected inside `scope.launch(Dispatchers.IO)` — a `NameNotFoundException` (module hidden from the host, dual-space clone, different user) would propagate as an uncaught coroutine exception and **kill the host app** (Breeno `com.heytap.speechassist` / XiaoAi `com.miui.voiceassist`).
- Changed (Entrance.kt:84-131): `createPackageContext` wrapped in a targeted `catch (NameNotFoundException)` (now :92-101) with a log line, and the whole `loadConfigFromRaw` body wrapped in a catch-all returning `null` (now :126-130) — a config-load failure can never crash the host again; it degrades to "no config".
- Manual test: enable the module for a host, hide/uninstall-keep-data Zafiro (or use a hidden-app launcher setting), cold-start the host → host runs normally; logcat shows `module package not visible to host …` instead of a crash.

---

## E. SamsungPersistenceWatchdog commit() — **fixed** (quick, as sanctioned)
- Verified: `apply()` at old :80, :90, :115, :155.
- Changed to `commit()` at now :83, :94, :120, :161. Rationale: these records exist **specifically to survive abrupt process death** (the kill-detection mechanism compares them on next start); `apply()`'s async flush is exactly what a process kill loses. The prefs file is 4 keys, written only on service lifecycle transitions — main-thread `commit()` cost is negligible.
- Manual test: force-stop the app (`adb shell am force-stop`) immediately after toggling proactive mode on → next start still detects the abrupt stop correctly (kill counter increments when boot count unchanged).

---

## Explicitly NOT done in Phase 1 (per instructions)
BootReceiver, AlarmManager/WorkManager scheduling, new builtin tools, SMS/calls, settings write — all deferred to later phases. No functional surface was added.

## Test hygiene (found during verification)
- `BuiltinToolTest.defaultRegistry_containsExpectedTools` failed **on main before any Phase 1 change**: the expected tool list was last updated at v1.3.0 while v1.7.0/v1.8.0 added `file_manager`, `live_screen`, `pdf_tools`, `samsung`, `system_data`. Updated the expectation to the shipped registry (commit `test: refresh stale BuiltinToolRegistry expectation`).
- New `CappedBufferTest` covers cap/trim/delta semantics for A4; `TerminalBuiltinTest` timeout test extended to assert the A2 contract (`session_id` present, session still alive, read instructions in message).

---

## UI polish (isolated from stability files)

### Done
- **`MotionTheme.kt`** (new, `ui-kit/src/main/java/com/niki914/uikit/base/`): single source for motion — `PRESS_SCALE = 0.97f`, spring factories `pressSpring()` (damped, snappy) / `standardSpring()` (near-critical, for placement) / `emphasizedSpring()`, M3 easings (`EasingStandard`, `EasingEmphasizedDecelerate`, `EasingEmphasizedAccelerate`), duration tokens (120/220/320ms). No component layouts changed.
- **`Haptics.kt`** (new, same package): semantic `rememberHaptics()` → `light()` (ContextClick), `success()` (Confirm), `failure()` (Reject). Haptic-type mapping lives in one place.
- **Chat screen**:
  - Message turns animate in and reposition smoothly: `Modifier.animateItem(placementSpec = MotionTheme.standardSpring())` on keyed turn items (HomePageContent.kt:629-633; keys already stable = `turn.id`).
  - Press-scale 0.97: `LiquidButton` (LiquidButton.kt:57-72) and `ActionBarButton` — the send button's base (ActionBarButton.kt:69-83) — via interaction-driven `animateFloatAsState(MotionTheme.pressSpring())`, layered over the existing liquid-glass press transforms.
  - Tool success/failure haptics: `ToolChain` (ToolChain.kt:118-139) fires `success()` on Running→Succeeded and `failure()` on Running→Failed; failure wins when several tools settle in one batch.
  - Running-tool pulse: `CollapsibleBlock` trailing dot breathes alpha 0.12→0.30 behind the M3E LoadingIndicator while running (CollapsibleBlock.kt:180-195, applied :250).
  - Send haptic: already provided by `ActionBarButton`'s built-in ContextClick (ActionBarButton.kt:149 area) — intentionally not duplicated.
- Spacing: only existing tokens used; no spacing or layout changes on touched components.
- No service/shell/automation code was modified for polish (verified per-commit: UI commit touches only ui-kit + 3 chat UI files).

### Remaining motion/visual work (proposed Phase 2+)
- Shared-element transitions for conversation→detail navigation (turn → full-screen editor).
- Skeleton loaders for model list / skills list first load.
- Designed empty states (new chat, no triggers armed, empty vault) with motion accents.
- Dark-mode refinement pass on the new pulse/highlight alphas (current values tuned on light).
- Streaming-text reveal (per-word fade-in) for assistant output; stagger alignment with `emphasizedSpring`.

---

## Phase 2 remains (from the audit's later-phase list + observations)
1. BootReceiver + WorkManager/AlarmManager-based rescheduling (also strengthens the Deep-Sleeping-Apps persistence directive).
2. New builtin tools; SMS/call surfaces; settings write path.
3. Terminal (deferred by design): the async-polling refactor flagged by existing `FIXME(async-refactor)` comments — unify `readSession` truncation/export semantics with DELTA re-design; per-session lock-map cleanup (executionLocks entries currently live until close/closeAll — bounded by session count, harmless but untidy).
4. Automation: DROP_OLDEST drop counter for observability; work-profile Download coverage if product wants it (needs cross-user listening strategy).
5. ContextProvider: audit the remaining intentional-blocking `await()` call sites (mostly Xposed host init) for per-site timeout policies.

## How to verify the whole phase quickly
```
git checkout phase1-stability && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Then walk: A1/A2 terminal script above → B toggle accessibility mid-operation → C burst notifications + Download trigger → D hide-module host cold start → E force-stop + restart → UI: send a message, watch a tool run to completion (pulse + haptics), press-and-hold the send button (scale).
