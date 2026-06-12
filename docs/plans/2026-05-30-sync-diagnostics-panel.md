# Sync Diagnostics Panel Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a read-only sync diagnostics panel that shows current ReadBoard/Yike readiness and the latest sync decision, with a copyable text summary.

**Architecture:** Keep diagnostics separate from sync behavior. `ReadBoard` and `OnlineDialog` only publish immutable value snapshots into a small recorder; `SyncDiagnosticsDialog` reads the recorder and formats it for display. The recorder never decides `HOLD` / `FORCE_REBUILD`, and the UI never calls sync, rebuild, or session-clear methods.

**Tech Stack:** Java 17, Swing, existing ReadBoard/Yike sync classes, Maven Surefire/JUnit 5.

**Spec:** `docs/specs/2026-05-30-sync-diagnostics-panel-design.md`

---

## Pre-Implementation Checks

Before writing implementation code, re-run these checks from the WSL worktree:

```bash
cd /home/dev/dev/weiqi/lizzieyzy-next
command -v rg
git status --short --branch
sed -n '120,210p' docs/SNAPSHOT_NODE_KIND.md
sed -n '1,140p' docs/specs/2026-05-08-yike-sync-session-state-design.md
sed -n '1,120p' docs/2026-05-09-yike-sync-hotfix-and-debug-log-map.md
rg -n "CompleteSnapshotRecoveryOutcome|resolveCompleteSnapshotRecovery|syncBoardStones|pendingRemoteContext" src/main/java/featurecat/lizzie/analysis/ReadBoard.java
rg -n "YikeSessionState|activeYikeSession|pendingYikeSession|shouldPromotePendingSession" src/main/java/featurecat/lizzie/gui/OnlineDialog.java
```

Expected:

- `rg` resolves to a Linux path such as `/usr/bin/rg`.
- Working tree contains only intended docs changes before implementation starts.
- `SNAPSHOT_NODE_KIND.md` still defines only `NO_CHANGE`, `SINGLE_MOVE_RECOVERY`, `HOLD`, and `FORCE_REBUILD` for the sync decision result set.

## File Structure

### Existing files to modify

| File | Responsibility in this change |
|---|---|
| `src/main/java/featurecat/lizzie/analysis/ReadBoard.java` | Publish readboard state, latest protocol summary, and latest complete snapshot decision trace without changing sync behavior. |
| `src/main/java/featurecat/lizzie/gui/OnlineDialog.java` | Publish current Yike active/pending session readiness and geometry placement state. |
| `src/main/java/featurecat/lizzie/gui/BottomToolbar.java` | Add a read-only diagnostics menu item near the existing Yike/readboard sync actions. |
| `src/main/resources/l10n/DisplayStrings.properties` | Add fallback UI strings for the diagnostics entry and buttons. |
| `src/main/resources/l10n/DisplayStrings_zh_CN.properties` | Add Chinese UI strings for the diagnostics entry and buttons. |

### New production files

| File | Responsibility |
|---|---|
| `src/main/java/featurecat/lizzie/analysis/SyncDecisionTrace.java` | Immutable value object for the latest complete snapshot recovery decision plus formatter support. |
| `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsSnapshot.java` | Immutable readboard/sync-state snapshot consumed by the UI. |
| `src/main/java/featurecat/lizzie/analysis/YikeSessionDiagnosticsSnapshot.java` | Immutable Yike session/geometry readiness snapshot consumed by the UI. |
| `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsReport.java` | Immutable combined view of readboard, Yike, and latest decision snapshots for UI/clipboard formatting. |
| `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsRecorder.java` | Thread-safe in-memory holder for the latest readboard, Yike, protocol, and decision snapshots. |
| `src/main/java/featurecat/lizzie/gui/SyncDiagnosticsDialog.java` | Swing dialog that renders the current diagnostics summary and supports refresh/copy/close. |

### New tests

| File | Responsibility |
|---|---|
| `src/test/java/featurecat/lizzie/analysis/SyncDecisionTraceTest.java` | Lock result/reason formatting and safe snapshot hashing. |
| `src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsRecorderTest.java` | Lock recorder immutability, empty-state behavior, and update isolation. |
| `src/test/java/featurecat/lizzie/analysis/YikeSessionDiagnosticsSnapshotTest.java` | Lock Yike active/pending readiness summary output, including `geometryReady=true` with `syncReady=false`. |

## Context to Preserve

- `docs/SNAPSHOT_NODE_KIND.md` is the sync decision contract. Diagnostics must not add new decision outcomes or new sync gates.
- `docs/specs/2026-05-08-yike-sync-session-state-design.md` allows `geometryReady=true` before `syncReady=true`; the panel must show this state clearly, not treat it as an error by itself.
- `YikeSyncDebugLog` remains default-off. The MVP uses in-memory snapshots, not always-on file logging.
- The WSL clone is the development copy. Windows packaging/build validation uses `D:\dev\weiqi\lizzieyzy-next` after syncing the branch.
- Do not touch second-stage zip export in this implementation. Keep it as future work.

---

## Task 1: Add Immutable Diagnostics Value Objects

**Files:**
- Create: `src/main/java/featurecat/lizzie/analysis/SyncDecisionTrace.java`
- Create: `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsSnapshot.java`
- Create: `src/main/java/featurecat/lizzie/analysis/YikeSessionDiagnosticsSnapshot.java`
- Create: `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsReport.java`
- Create: `src/test/java/featurecat/lizzie/analysis/SyncDecisionTraceTest.java`

- [ ] **Step 1: Write failing tests for decision trace formatting**

Create `SyncDecisionTraceTest` with tests for the four existing result names:

```java
@Test
void formatsHoldTraceWithReasonAndInputSummary() {
  SyncDecisionTrace trace =
      SyncDecisionTrace.builder("HOLD", "conflict_hold")
          .platform("YIKE")
          .windowKind("LIVE_ROOM")
          .firstSyncFrame(false)
          .changedStoneCount(3)
          .removedStoneCount(0)
          .snapshotHash("8f3a2c11")
          .shouldResumeAnalysis(false)
          .timestampMillis(1_780_147_922_000L)
          .build();

  String text = trace.toSummaryText();

  assertTrue(text.contains("result: HOLD"));
  assertTrue(text.contains("reason: conflict_hold"));
  assertTrue(text.contains("changedStones: 3"));
  assertTrue(text.contains("removedStones: 0"));
}
```

Add equivalent small tests for `NO_CHANGE`, `SINGLE_MOVE_RECOVERY`, and `FORCE_REBUILD`.

- [ ] **Step 2: Run the new test and verify it fails**

Run from WSL if Maven is available:

```bash
mvn -Dtest=SyncDecisionTraceTest test
```

Expected: FAIL because `SyncDecisionTrace` does not exist yet.

- [ ] **Step 3: Implement `SyncDecisionTrace` minimally**

Implement:

- private final fields.
- public getters.
- a small builder.
- `toSummaryText()`.
- `hashSnapshotCodes(int[] snapshotCodes)`.

Rules:

- `hashSnapshotCodes` must not output the full board.
- Use only stable primitives and strings.
- Do not reference `ReadBoard` from this value object.

- [ ] **Step 4: Add snapshot and report value objects**

Implement `SyncDiagnosticsSnapshot`, `YikeSessionDiagnosticsSnapshot`, and `SyncDiagnosticsReport` as immutable values with:

- `empty()` factory.
- builder or static factory methods.
- `toSummaryText()`.

Keep `SyncDiagnosticsSnapshot` scoped to ReadBoard/sync state. `SyncDiagnosticsReport` is the only class that combines ReadBoard, Yike, and latest-decision sections.

Keep fields close to the spec. Do not include mutable references such as `BoardHistoryNode`, `YikeSessionState`, arrays, or JSON objects.

- [ ] **Step 5: Re-run the value object tests**

```bash
mvn -Dtest=SyncDecisionTraceTest test
```

Expected: PASS.

- [ ] **Step 6: Commit the value-object checkpoint**

```bash
git add src/main/java/featurecat/lizzie/analysis/SyncDecisionTrace.java \
  src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsSnapshot.java \
  src/main/java/featurecat/lizzie/analysis/YikeSessionDiagnosticsSnapshot.java \
  src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsReport.java \
  src/test/java/featurecat/lizzie/analysis/SyncDecisionTraceTest.java
git commit -m "feat(sync): add diagnostics snapshot values"
```

---

## Task 2: Add the Diagnostics Recorder

**Files:**
- Create: `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsRecorder.java`
- Create: `src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsRecorderTest.java`

- [ ] **Step 1: Write failing recorder tests**

Create tests for these cases:

```java
@Test
void emptyRecorderFormatsUsableSummary() {
  SyncDiagnosticsRecorder recorder = new SyncDiagnosticsRecorder();

  String summary = recorder.snapshot().toSummaryText();

  assertTrue(summary.contains("Sync Diagnostics"));
  assertTrue(summary.contains("readboard:"));
  assertTrue(summary.contains("Latest decision"));
}

@Test
void updatingDecisionDoesNotClearYikeSnapshot() {
  SyncDiagnosticsRecorder recorder = new SyncDiagnosticsRecorder();
  recorder.updateYikeSession(
      YikeSessionDiagnosticsSnapshot.builder()
          .currentRouteKind("live-room")
          .activeSessionKey("live-room:1")
          .activeSyncReady(true)
          .activeGeometryReady(true)
          .build());

  recorder.updateLatestDecision(
      SyncDecisionTrace.builder("HOLD", "conflict_hold").build());

  String summary = recorder.snapshot().toSummaryText();
  assertTrue(summary.contains("active: live-room:1"));
  assertTrue(summary.contains("result: HOLD"));
}
```

- [ ] **Step 2: Run recorder tests and verify they fail**

```bash
mvn -Dtest=SyncDiagnosticsRecorderTest test
```

Expected: FAIL because the recorder does not exist.

- [ ] **Step 3: Implement the recorder**

Implement a small thread-safe recorder:

```java
public final class SyncDiagnosticsRecorder {
  private final Object lock = new Object();
  private SyncDiagnosticsSnapshot sync = SyncDiagnosticsSnapshot.empty();
  private YikeSessionDiagnosticsSnapshot yike = YikeSessionDiagnosticsSnapshot.empty();
  private SyncDecisionTrace latestDecision = SyncDecisionTrace.empty();

  public void updateSync(SyncDiagnosticsSnapshot value) { ... }
  public void updateYikeSession(YikeSessionDiagnosticsSnapshot value) { ... }
  public void updateLatestDecision(SyncDecisionTrace value) { ... }
  public SyncDiagnosticsReport snapshot() { ... }
}
```

If a single global instance is needed for `ReadBoard`, `OnlineDialog`, and UI, add:

```java
public static SyncDiagnosticsRecorder getDefault() { ... }
```

Rules:

- Synchronize state copies.
- Accept `null` as `empty()` to keep callers simple.
- Do not call back into `ReadBoard`, `OnlineDialog`, or `LizzieFrame`.

- [ ] **Step 4: Re-run recorder tests**

```bash
mvn -Dtest=SyncDiagnosticsRecorderTest test
```

Expected: PASS.

- [ ] **Step 5: Commit the recorder checkpoint**

```bash
git add src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsRecorder.java \
  src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsRecorderTest.java
git commit -m "feat(sync): record latest diagnostics snapshots"
```

---

## Task 3: Publish ReadBoard Diagnostics Without Changing Decisions

**Files:**
- Modify: `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
- Modify: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`

- [ ] **Step 1: Add failing tests for trace publication**

Add focused tests in `ReadBoardSyncDecisionTest` using the existing `SyncHarness` style:

```java
@Test
void completeSnapshotHoldPublishesDiagnosticsTraceWithoutChangingBehavior() throws Exception {
  try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
    // Arrange a conflict that existing tests already expect to HOLD.
    // Invoke syncBoardStones via the existing helper.
    invokeSyncBoardStones(harness.readBoard);

    SyncDecisionTrace trace =
        SyncDiagnosticsRecorder.getDefault().snapshot().latestDecisionTrace();
    assertEquals("HOLD", trace.result());
    assertEquals("conflict_hold", trace.reasonCode());
  }
}
```

Use an existing HOLD scenario from the same test file rather than inventing new board setup. Add a second test for a `FORCE_REBUILD` path that already exists in the file.

- [ ] **Step 2: Run the targeted test and verify it fails**

```bash
mvn -Dtest=ReadBoardSyncDecisionTest test
```

Expected: FAIL only in the new diagnostics assertions.

- [ ] **Step 3: Add reason code to `CompleteSnapshotRecoveryDecision`**

Update the private nested decision class in `ReadBoard.java`:

- Add `reasonCode`.
- Add static factories:
  - `noChange(..., "snapshot_matches_existing_node")`
  - `singleMoveRecovery("single_move_recovery")`
  - `hold("conflict_hold")`
  - `forceRebuild("force_rebuild_requested")`

At each return in `resolveCompleteSnapshotRecovery(...)`, pass the most specific stable reason available:

- `remote_force_rebuild_requested`
- `resume_context_conflict`
- `snapshot_matches_existing_node`
- `snapshot_matches_adjacent_resolved_node`
- `single_move_recovery`
- `first_sync_force_rebuild`
- `conflict_hold`
- `fallback_force_rebuild`

Do not change any branch condition.

- [ ] **Step 4: Publish trace after the decision is already resolved**

In `syncBoardStones(boolean isSecondTime)`, immediately after `resolveCompleteSnapshotRecovery(...)` returns, create a `SyncDecisionTrace` and send it to the recorder.

Include:

- `recovery.outcome.name()`
- `recovery.reasonCode`
- `currentRemoteContext.platform.name()`
- `currentRemoteContext.windowKind.name()`
- `currentRemoteContext.recoveryMoveNumber()`
- `currentRemoteContext.forceRebuild`
- `awaitingFirstSyncFrame`
- `SyncDecisionTrace.hashSnapshotCodes(currentSnapshotCodes)`
- `snapshotDelta.changedStoneCount()`, or add tiny accessors if existing fields are private.
- `snapshotDelta.removedStoneCount()`, or use the existing equivalent.
- resolved node move number when present.
- `recovery.shouldResumeAnalysis`.

If `SnapshotDelta` does not expose counts, add read-only accessors there and test them in the existing classifier tests if needed.

- [ ] **Step 5: Record readboard state and last protocol summary**

In the readboard input loop around the existing `inputStream.read()` / line handling:

- update last protocol timestamp.
- store a sanitized protocol summary, not the full raw line.

Sanitization rule:

- Keep command names such as `syncPlatform`, `yikeSyncStart`, `yikeGeometry`, `foxMoveNumber`.
- Hash or omit room tokens and long payloads.
- Never store full SGF or full window title.

- [ ] **Step 6: Re-run targeted sync tests**

```bash
mvn -Dtest=ReadBoardSyncDecisionTest,SyncSnapshotRebuildPolicyTest,SyncSnapshotClassifierTest test
```

Expected: PASS.

- [ ] **Step 7: Commit the ReadBoard diagnostics checkpoint**

```bash
git add src/main/java/featurecat/lizzie/analysis/ReadBoard.java \
  src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java
git commit -m "feat(sync): publish readboard diagnostics traces"
```

---

## Task 4: Publish Yike Session Diagnostics

**Files:**
- Modify: `src/main/java/featurecat/lizzie/gui/OnlineDialog.java`
- Create: `src/test/java/featurecat/lizzie/analysis/YikeSessionDiagnosticsSnapshotTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/YikeSessionDiagnosticsSnapshotTest.java`
- Test: `src/test/java/featurecat/lizzie/gui/OnlineDialogYikeSessionStateTest.java`

- [ ] **Step 1: Write failing Yike diagnostics tests**

Create `YikeSessionDiagnosticsSnapshotTest`:

```java
@Test
void showsGeometryReadyBeforeSyncReady() {
  YikeSessionDiagnosticsSnapshot snapshot =
      YikeSessionDiagnosticsSnapshot.builder()
          .currentRouteKind("live-room")
          .pendingSessionKey("live-room:186538")
          .pendingSyncReady(false)
          .pendingGeometryReady(true)
          .pendingBoardSize(19)
          .placementGeometryAllowed(false)
          .build();

  String text = snapshot.toSummaryText();

  assertTrue(text.contains("pending: live-room:186538"));
  assertTrue(text.contains("syncReady=false"));
  assertTrue(text.contains("geometryReady=true"));
  assertTrue(text.contains("placementGeometryAllowed=false"));
}
```

- [ ] **Step 2: Run the new test and verify it fails**

```bash
mvn -Dtest=YikeSessionDiagnosticsSnapshotTest test
```

Expected: FAIL if builder/formatter fields are missing.

- [ ] **Step 3: Add `OnlineDialog` snapshot publication**

Add a private method:

```java
private void publishYikeDiagnostics(String reason) {
  SyncDiagnosticsRecorder.getDefault().updateYikeSession(buildYikeDiagnosticsSnapshot(reason));
}
```

Call it after state changes in:

- `startOrRefreshYikeSession(...)`
- `markYikeSessionSyncReady(...)`
- `markYikeSessionGeometryReady(...)`
- `promotePendingYikeSessionIfReady()`
- `clearActiveYikeGeometry()`
- `clearYikeSessions()`

The method should read fields and build values only. It must not call `sendYikeGeometryToReadBoard()`, `clearYikeGeometryToReadBoard()`, `syncOnline(...)`, or network/probe methods.

- [ ] **Step 4: Keep readiness semantics unchanged**

Do not change:

- `shouldPromotePendingSession(...)`
- `shouldInvalidateYikePlacementGeometry(...)`
- `YikeSessionState.withSyncReady(...)`
- `YikeSessionState.withGeometry(...)`

The diagnostics snapshot only mirrors these values.

- [ ] **Step 5: Run Yike targeted tests**

```bash
mvn -Dtest=YikeSessionDiagnosticsSnapshotTest,OnlineDialogYikeSessionStateTest,BrowserFrameYikeSyncControlTest,YikeGeometryNormalizationTest,YikeUrlParserTest test
```

Expected: PASS.

- [ ] **Step 6: Commit the Yike diagnostics checkpoint**

```bash
git add src/main/java/featurecat/lizzie/gui/OnlineDialog.java \
  src/test/java/featurecat/lizzie/analysis/YikeSessionDiagnosticsSnapshotTest.java
git commit -m "feat(yike): publish session readiness diagnostics"
```

---

## Task 5: Add the Read-Only Diagnostics Dialog

**Files:**
- Create: `src/main/java/featurecat/lizzie/gui/SyncDiagnosticsDialog.java`
- Modify: `src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsRecorderTest.java`

- [ ] **Step 1: Add formatter coverage for full summary text**

Extend `SyncDiagnosticsRecorderTest` so a combined readboard + Yike + decision snapshot renders these sections:

```text
Sync Diagnostics
ReadBoard
Yike
Latest decision
```

Expected: FAIL if combined formatting is incomplete.

- [ ] **Step 2: Implement a minimal Swing dialog**

Create `SyncDiagnosticsDialog`:

- Extends `JDialog`.
- Contains a non-editable `JTextArea` inside `JScrollPane`.
- Uses monospace font for the summary.
- Has three buttons:
  - refresh
  - copy
  - close
- Reads only `SyncDiagnosticsRecorder.getDefault().snapshot().toSummaryText()`.

Copy action:

```java
Toolkit.getDefaultToolkit()
    .getSystemClipboard()
    .setContents(new StringSelection(summaryText), null);
```

Rules:

- No sync action buttons.
- No background polling in MVP.
- Refresh is manual only.
- Dialog construction must not require readboard to be running.

- [ ] **Step 3: Re-run formatter tests**

```bash
mvn -Dtest=SyncDiagnosticsRecorderTest test
```

Expected: PASS.

- [ ] **Step 4: Commit the dialog checkpoint**

```bash
git add src/main/java/featurecat/lizzie/gui/SyncDiagnosticsDialog.java \
  src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsRecorderTest.java
git commit -m "feat(sync): add diagnostics summary dialog"
```

---

## Task 6: Wire the UI Entry Point

**Files:**
- Modify: `src/main/java/featurecat/lizzie/gui/BottomToolbar.java`
- Modify: `src/main/resources/l10n/DisplayStrings.properties`
- Modify: `src/main/resources/l10n/DisplayStrings_zh_CN.properties`

- [ ] **Step 1: Add resource keys**

Add fallback keys:

```properties
Menu.syncDiagnostics=Sync diagnostics
SyncDiagnostics.title=Sync diagnostics
SyncDiagnostics.refresh=Refresh
SyncDiagnostics.copy=Copy summary
SyncDiagnostics.close=Close
```

Add Chinese keys:

```properties
Menu.syncDiagnostics=同步诊断
SyncDiagnostics.title=同步诊断
SyncDiagnostics.refresh=刷新
SyncDiagnostics.copy=复制诊断摘要
SyncDiagnostics.close=关闭
```

- [ ] **Step 2: Add the menu item near readboard sync actions**

In `BottomToolbar.java`, add a `JFontMenuItem` after the existing `syncBoard` / `syncBoardJava` items in the Yike popup:

```java
JFontMenuItem syncDiagnostics =
    new JFontMenuItem(Lizzie.resourceBundle.getString("Menu.syncDiagnostics"));
syncDiagnostics.addActionListener(
    e -> new SyncDiagnosticsDialog(Lizzie.frame).setVisible(true));
yike.add(syncDiagnostics);
```

If import cleanup is needed, keep it local to `BottomToolbar.java`.

- [ ] **Step 3: Run a compile-focused test**

```bash
mvn -Dtest=SyncDecisionTraceTest,SyncDiagnosticsRecorderTest,YikeSessionDiagnosticsSnapshotTest test
```

Expected: PASS and no missing resource key or import compile errors.

- [ ] **Step 4: Commit the UI entry checkpoint**

```bash
git add src/main/java/featurecat/lizzie/gui/BottomToolbar.java \
  src/main/resources/l10n/DisplayStrings.properties \
  src/main/resources/l10n/DisplayStrings_zh_CN.properties
git commit -m "feat(sync): expose diagnostics panel from sync menu"
```

---

## Task 7: Final Verification and Scope Review

**Files:**
- Verify all files changed in Tasks 1-6.
- No new implementation files outside the listed files unless a name is adjusted during implementation with a documented reason.

- [ ] **Step 1: Run targeted diagnostics tests**

```bash
mvn -Dtest=SyncDecisionTraceTest,SyncDiagnosticsRecorderTest,YikeSessionDiagnosticsSnapshotTest test
```

Expected: PASS.

- [ ] **Step 2: Run targeted sync/Yike regression tests**

```bash
mvn -Dtest=ReadBoardSyncDecisionTest,SyncSnapshotRebuildPolicyTest,OnlineDialogYikeSessionStateTest,BrowserFrameYikeSyncControlTest,YikeGeometryNormalizationTest,YikeUrlParserTest test
```

Expected: PASS.

- [ ] **Step 3: Review diff for forbidden behavior changes**

```bash
git diff -- src/main/java/featurecat/lizzie/analysis/ReadBoard.java \
  src/main/java/featurecat/lizzie/gui/OnlineDialog.java \
  src/main/java/featurecat/lizzie/gui/BottomToolbar.java \
  src/main/java/featurecat/lizzie/analysis/SyncDecisionTrace.java \
  src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsSnapshot.java \
  src/main/java/featurecat/lizzie/analysis/YikeSessionDiagnosticsSnapshot.java \
  src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsReport.java \
  src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsRecorder.java \
  src/main/java/featurecat/lizzie/gui/SyncDiagnosticsDialog.java
```

Expected:

- No new calls from UI to `syncBoardStones(...)`.
- No new calls from UI to `rebuildFromSnapshot(...)`.
- No new calls from UI to geometry clear or session clear methods.
- No changed branch conditions in `resolveCompleteSnapshotRecovery(...)`.
- No new readboard protocol token.

- [ ] **Step 4: Review docs alignment**

```bash
git diff -- docs/specs/2026-05-30-sync-diagnostics-panel-design.md \
  docs/plans/2026-05-30-sync-diagnostics-panel.md
```

Expected: plan still matches the approved MVP: current panel + copy summary only, no zip export.

- [ ] **Step 5: Optional Windows verification after syncing the branch**

After the WSL branch is pushed or pulled into `D:\dev\weiqi\lizzieyzy-next`, run:

```powershell
& 'D:\dev\weiqi\lizzieyzy-next\.tools\apache-maven-3.9.10\bin\mvn.cmd' "-Dtest=SyncDecisionTraceTest,SyncDiagnosticsRecorderTest,YikeSessionDiagnosticsSnapshotTest,ReadBoardSyncDecisionTest,SyncSnapshotRebuildPolicyTest,OnlineDialogYikeSessionStateTest,BrowserFrameYikeSyncControlTest,YikeGeometryNormalizationTest,YikeUrlParserTest" test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit final verification fixes if needed**

If verification required fixes:

```bash
git add <fixed-files>
git commit -m "fix(sync): tighten diagnostics integration"
```

If no fixes were needed, do not create an empty commit.

## Implementation Notes

- Prefer package-private helper methods over new abstractions when only tests need access.
- Keep recorder calls at the edge of existing logic. They should look like logging, not control flow.
- Use reason codes as stable diagnostic labels, not user-facing explanations.
- For the MVP, a readable text area is enough. Do not build a complex live dashboard or zip exporter.
- If implementation reveals that a field in the spec cannot be collected without behavior risk, leave it as `unknown` and update the spec/plan before broadening the code change.
