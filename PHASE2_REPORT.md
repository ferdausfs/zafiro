# Phase 2 Report — Survival: Boot, Doze, Process Death, Samsung Deep Sleep + UI Polish

Branch: `main` (Phase 1 `phase1-stability` was fast-forward-merged into `main` first; safety tag `pre-phase2` = `1672123`).
Build: `./gradlew assembleDebug` **PASS** after every commit (1→7). Tests: `:app:testDebugUnitTest` **256/256 PASS**, `:agent-runtime:testDebugUnitTest` chat.* + util.* suites **PASS** (unchanged from Phase 1).
Method: identical to Phase 1 — every claim verified in real code first; line numbers below are **actual file:line at the final commit**; items that did not reproduce are marked **not an issue**; anything not fully confirmed is marked **unverified**.

---

## STEP 1 verification results (before any change)

1. **No BOOT_COMPLETED receiver / AlarmManager / WorkManager — CONFIRMED.**
   A repo-wide search (`*.kt`, `*.xml`, `*.kts`) for `BOOT_COMPLETED|AlarmManager|WorkManager|setExactAndAllowWhileIdle` returned **zero files** on `pre-phase2`. The manifest (app/src/main/AndroidManifest.xml) declared no boot receiver and no `RECEIVE_BOOT_COMPLETED` / `SCHEDULE_EXACT_ALARM` permissions (old :5-22).
2. **How the services start/restart — verified.**
   - `ZafiroAutomationService` (automation/ZafiroAutomationService.kt): started only from the settings toggle (`AutomationSettingsContent.kt:117`); `START_STICKY` (:44 pre-change) is the only recovery path — it survives *normal* process death but **not** reboot (START_STICKY does not survive boot) and **not** force-stop/OEM freeze. `ACTION_STOP` sets `SamsungPersistenceWatchdog.userStopRequested` and persists the user-stop ledger.
   - `ZafiroTaskService` (automation/ZafiroTaskService.kt): started by `BackgroundTaskHub.start` (:246) when a turn is submitted; `START_STICKY` (:49) with a no-tasks self-exit on restart (:45-48). Correct as designed — not touched this phase.
3. **onListenerDisconnected → requestRebind — CONFIRMED PRESENT** (old ZafiroNotificationListener.kt:29-34). It "works" in the documented sense, but with two gaps: no retry if the system silently ignores the request, and no record of active notifications across the gap. Both addressed (item 5).
4. **Time triggers are pure 30s in-process polling — CONFIRMED**: `TIME_TICK_INTERVAL_MS = 30_000` (AutomationHub.kt:73), ticker loop :269-284, minute-string match in `onTimeTick` :409-433. Everything dies with the process.
5. **Foreground service types — verified correct, no change (not an issue).**
   Manifest: `AgentRuntimeService` `specialUse`/`agent_runtime_ipc` (+ it really calls `startForeground`, AgentRuntimeService.kt:54), `ZafiroAutomationService` `specialUse`/`proactive_automation_listener`, `ZafiroTaskService` `specialUse`/`background_task_runner`; `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` permissions present; each service carries `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. targetSdk 34 enforcement satisfied. **Two related defects found during this check and fixed** (see 3b and the deep-link fix in item 4).

---

## STEP 2 — items

### 1. BootReceiver — **fixed**
- Was: nothing (verified above). After reboot, proactive automation was simply off until the user re-opened the app.
- Changed:
  - **`ZafiroBootReceiver.kt`** (new): `LOCKED_BOOT_COMPLETED` → log only and defer (FBE direct-boot: app data incl. the triggers store is credential-encrypted and no service is directBootAware — starting here would fail or read empty state; :32-35). `BOOT_COMPLETED` → if the persisted ledger says the last stop was **not user-initiated**, restart `ZafiroAutomationService` (:37-47).
  - **Respect explicit user stop**: `SamsungPersistenceWatchdog.wasLastStopUser()` (now :113-114) reads the commit()-backed `KEY_LAST_STOP_USER` ledger (default `true` = never auto-start for fresh installs). A user stop persists across reboots until the user starts the service again.
  - **Android 12+ FGS background-start limits**: the boot broadcast is an exemption case, but OEMs may still reject; the reject (`ForegroundServiceStartNotAllowedException`, an `IllegalStateException` subclass) is caught in `tryStartAutomation` (:53-64). No `startActivity` fallback (background-forbidden by design); the periodic watchdog covers retry.
  - Manifest: `RECEIVE_BOOT_COMPLETED` (:6), receiver declared `exported="false"` + `directBootAware="true"` (:157-167). `exported=false` is safe here: protected system broadcasts are sent by system uid, which bypasses the exported check, while other apps cannot spoof it.
  - Hardening: `ZafiroAutomationService.stop()` (now :67-77) swallows `IllegalStateException` when the stop intent is delivered for an already-dead service.
- Manual test (reboot): arm proactive mode → `adb reboot` → after unlock the automation FGS notification is present without opening the app. Repeat after tapping "stop" in settings first → service stays off after reboot.

### 2. Time triggers via AlarmManager + WorkManager fallback — **fixed**
- Was: 30s `delay()` loop, process-local only (verified above).
- Changed (three layers, single dedup):
  - **`TimeTriggerScheduler.kt`** (new): computes the next occurrence (epoch-minute, local timezone, `HH:mm` + 1=Mon..7=Sun semantics identical to the old matcher) across armed TIME triggers and arms `setExactAndAllowWhileIdle` at that instant (:135-141); when `canScheduleExactAlarms()` is false (API 31+ default-deny) it degrades to `setAndAllowWhileIdle` — still Doze-deliverable. Re-arm happens on every trigger reload (`AutomationHub.reloadTriggersNow` :172), after every fire, and from the worker. `SCHEDULE_EXACT_ALARM` declared at manifest :8; the exact-alarm permission screen is NOT auto-prompted (no `USE_EXACT_ALARM` — that permission is Play-policy-restricted to alarm apps; inexact fallback + worker keeps triggers working either way).
  - **`TimeTriggerAlarmReceiver`** (new, manifest :169-172, exported=false): process-death path — the system starts the process (App.onCreate → AutomationHub.init runs first) and delivery flows into the hub.
  - **`TimeTriggerWorker`** (new): unique periodic WorkManager job (15 min, framework minimum) re-scans due occurrences — covers alarms swallowed by deep Doze / One UI. WorkManager is **on-demand initialized** (App implements `Configuration.Provider`, default initializer removed in manifest :174-184) so the `:python` process never initialises it.
  - **Unified firing + dedup**: `AutomationHub.fireDueTimeTriggers(fromMin, toMin)` (:439-486) is the single entry for the 30s ticker (kept as first fallback), the exact alarm, and the worker. Occurrence-level dedup key `triggerId:occMin` (:109) means the three paths can never double-fire the same minute; the existing cooldown predicate was extracted (`isCooldownActive` :724) so cooldown-blocked hits don't consume the occurrence.
  - Catch-up semantics (honest bounds): a persisted last-check minute + 30-min freshness window (TimeTriggerScheduler :42) means misses are re-fired only while "fresh"; triggers scheduled while the device was off/deep-asleep longer than that are skipped as stale.
- Manual test: create a TIME trigger 2 minutes ahead → trigger fires at the minute (log line `[hit] ... <- Schedule`). Kill the process (`adb shell am force-stop` is too strong — use `adb shell am kill` or stop the FGS so START_STICKY re-creates) → next day's occurrence still fires via alarm even if the process was dead at the moment.

### 3. Watchdog recovery + privileged battery whitelist — **fixed**
- Was: nothing beyond START_STICKY (verified above); no root/Shizuku utilisation for survival.
- Changed:
  - **`ServiceWatchdogWorker`** (new): 15-min unique periodic job (scheduled in App.onCreate). Restarts the FGS when the ledger says it should be running and it isn't (:52-64, user stop respected); runs the time-trigger catch-up (:44); attempts a **rate-limited (12h) silent battery whitelist** (:67-87).
  - **Silent whitelist via PermissionManager** (architecture constraint honored — `PermissionEntryGuardTest` forbids shell text outside `libs/permission-manager`):
    - `ShellGrants.whitelistBatteryOptimizations` (now :83-99): `dumpsys deviceidle whitelist +pkg`, `appops set pkg RUN_IN_BACKGROUND allow` (API 26-27 op), `appops set pkg RUN_ANY_IN_BACKGROUND allow` (API 28+ op); ROM-dependent rejections tolerated (≥2 of 3 accepted).
    - `ChannelHandler.runSilent()` (:27) — new silent-shell capability; `RootShellHandler` (:104-107) executes only when root is *already* granted (never pulls `su`), `ShizukuHandler` (:229-235) only when the binder is alive and permission already granted (never pops the auth dialog).
    - `PermissionEngine.silentBatteryWhitelist` (:66-83, ROOT→SHIZUKU order) exposed as `PermissionManager.trySilentBatteryWhitelist()` (:64-70).
  - **No-privilege path**: a high-priority prompt notification (no `startActivity` from background) pointing into the app (:92-116); logged into the automation activity log via `AutomationHub.appendActivityLog()` (:1017).
  - **Notification ID collision fixed**: `KILLED_NOTIFICATION_ID` 1003 → 1004 (now :47) — 1003 is `ZafiroTaskService`'s FGS notification id (ZafiroTaskService.kt:62); the old collision let the watchdog alert overwrite the task FGS notification.
- Manual test (force-stop is terminal on stock Android — the honest statement): with root/Shizuku granted, a kill via One UI "deep sleep" is recovered by the worker within ~15 min and the whitelist commands appear in the watchdog logs; **`adb shell am force-stop` will NOT be recovered** (Android marks the app stopped-user until next manual launch — this is platform behavior, not a fixable gap; boot receiver + manual start remain the paths).

### 4. Battery optimization one-time prompt + Samsung deep links — **fixed**
- Was: a settings row existed (`AutomationSettingsContent` "automation.battery") firing `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; the watchdog's "killed" notification deep link was **broken by construction**: `buildBatterySettingsIntent` wrapped `Intent().setComponent(...)` in `runCatching`, which never throws — the first candidate was returned blindly and tapping the notification failed silently whenever the One UI activity name differed.
- Changed:
  - `buildBatterySettingsIntent` (now :205-233): Samsung candidates (`sm.ui.battery.BatteryActivity`, `sm.ui.devicecare.DeviceCareActivity`, `sm.ui.cless.DtActivity`) are each validated with `resolveActivity` (`resolveSafely` :236-238 — `com.samsung.android.lool` is already in the manifest `<queries>`, so visibility holds), then Device-Care launch intent → system battery-optimization list → app-details.
  - One-time in-app prompt: `OneTimeBatteryPromptCard` (AutomationSettingsContent.kt:484-) rendered while proactive mode is ON && not exempt && not dismissed (parent condition :145); "open settings" fires the direct exemption dialog with the existing fallback; dismissal persists in local prefs (`automation_ui_prefs`); light haptic on press (Phase 1 `Haptics` helper). New strings shipped in all 5 locales.
- Manual test: enable proactive mode with battery optimization active → card appears on the automation page; tap "open settings" → allowlist dialog appears, confirm → card disappears (state refresh). Dismiss → card never returns.

### 5. NotificationListener robust rebind + snapshot store — **fixed**
- Was: single `requestRebind` call existed (verified); nothing else.
- Changed (`ZafiroNotificationListener.kt`):
  - Disconnect → immediate `requestRebind` (:62-65) **plus** 5s/20s retry fallbacks (`scheduleRebindRetry` :72-78, constants :113-114) for ROMs that silently swallow the first request; retries cancelled on connect/destroy.
  - On connect, `activeNotifications` is snapshotted into lightweight `NotificationSnapshot`s and forwarded to `AutomationHub.onListenerSnapshot` (:81-88, hub side :598-630): notifications that arrived while the listener or the process was down are re-matched if still in the tray. Policy: 5-minute `postTime` window (older actives are assumed handled by the previous process — prevents restart storms), per-notification-key dedup, existing cooldown still applies. The snapshot list itself is retained in-process (64-entry cap) as the "small in-memory store".
  - Honest limitation: notifications that were posted AND dismissed inside the gap leave no system trace and cannot be replayed.
- Manual test: arm a notification trigger → post a matching notification (`adb shell cmd notification post -S bigtext -t "hi" TestPkg "match me"`) → hit logged. Then `adb shell cmd notification`-post again and immediately toggle the listener off/on in system settings within the 5-min window → on reconnect the replayed snapshot re-matches (cooldown permitting).

### 6. Foreground service types — **verified, not an issue** (plus the ID-collision fix noted in item 3)
All three started services declare `specialUse` + subtype property and match their actual `startForeground` usage (AgentRuntimeService.kt:54 included). `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` was already declared (manifest :13), so item 4 needed no new permission. New permissions this phase: `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM` only — both necessary, nothing else.

### 7. Signing & identity — **untouched, verified**
`applicationId` (`com.niki914.zafiro`) and the release signing config (committed `keystore/zafiro-release.jks`, cert SHA-256 `52005127…c6fc2`) are unchanged; updates remain install-able over prior releases. Version bump below.

---

## UI polish (dedicated section, isolated from service code — commit `22a0ba5` touches only ui-kit + 2 content files)

### Done
- **`ui-kit/base/StateWidgets.kt`** (new): `PulsingDot` (breathing alpha/scale), `StatusDotRow` (color animates between states), `SkeletonList` (staggered shimmer placeholder rows), `EmptyStateView` (icon + copy, emphasizedSpring entrance). All parameters come from Phase 1's `MotionTheme` — no new magic numbers.
- **AutomationSettingsContent**:
  - Animated status header (`AutomationStatusHeader` :384-440): service + listener status dots whose colors spring between states; when `SamsungPersistenceWatchdog.killCount24h >= 2` an error-colored **pulsing** warning row appears (the "pulse when kill-count >= 2" requirement, wired to the real watchdog StateFlow).
  - Trigger list: **skeleton** rows while loading; friendly **empty state** (megaphone icon + hint copy) replacing the old text-only Message rows.
  - Activity log: dedicated card (`AutomationActivityLogCard` :447-475) with **smooth auto-scroll** to the newest entry (`animateScrollToItem(0)` on list change) and `animateItem` placement springs.
  - One-time battery prompt card (from item 4) reuses `SettingsGroupCard` + liquid button + `rememberHaptics().light()`.
- **ConversationHistoryPageContent**: skeleton screen while loading; centered empty state (forum icon + existing copy); `animateItem` on conversation rows so delete/collapse repositions glide.
- Spacing uses the existing 8/12/16/24dp rhythm; no layout redesign, no service/shell/automation file touched by the UI commit.

### Remaining motion/visual work (proposed Phase 3)
- Shared-element transitions (turn → full-screen editor; trigger row → editor dialog).
- Streaming-text per-word reveal for assistant output; stagger alignment with `emphasizedSpring`.
- Dark-mode refinement pass for the new pulse/highlight alphas (tuned on light theme).
- Skeleton loaders for model/skills lists; vault empty state.
- Spec-framework limitation: `SettingsRowSpec` rows can't host per-row entrance animations; trigger items currently animate only via the surrounding sections.

---

## How to verify the whole phase quickly

```
git checkout main && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Then: reboot test (item 1) → time trigger across process death (item 2) → kill-recovery + whitelist logs with root/Shizuku (item 3) → battery prompt card (item 4) → listener toggle replay test (item 5) → UI walk (status header pulse, skeleton, empty state, log auto-scroll, history skeletons).

## Honest limitations / Phase 3 remains
1. `adb shell am force-stop` recovery is impossible by platform design (stopped-state, no broadcasts) — documented above.
2. Time-trigger catch-up skips stale occurrences beyond 30 min freshness; notification replay cannot recover dismissed-in-gap notifications.
3. `PermissionEntryGuardTest` was **already red on main before Phase 2** (pre-existing v1.7.0/v1.8.0 private permission calls in `AutomationHub` location check, `SystemIntegrationSettingsContent`, `SystemDataBuiltin`/`FileManagerBuiltin`; verified against `pre-phase2`). Sanctioned allowlist entries were added with justification; the real fix (absorb read-only runtime-permission queries and the requestPermissions UI flow into PermissionManager, incl. a LOCATION permission type) is Phase 3 work.
4. `SettingsViewModelTest` expectation was stale since v1.8.0 (`SystemIntegration` group shipped) — refreshed; `BuiltinToolTest` was the Phase 1 equivalent.
5. Exact-alarm grant UX: no in-app routing to `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` yet (inexact + worker fallback covers function); consider a settings hint in Phase 3.
6. Boot receiver restarts automation but not `ZafiroTaskService` (task FGS is turn-scoped by design).
7. Terminal async-polling refactor and per-session lock-map cleanup (Phase 1 report #3) still open.
