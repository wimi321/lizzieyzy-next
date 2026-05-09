# Yike Sync Session State Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rework Yike sync triggering into a clear listener/session state machine so first-entry live rooms can bootstrap independently, room switches use soft handoff, waiting pages invalidate geometry without stopping listening, and stale unite/live state no longer leaks across routes.

**Architecture:** Keep the change scoped to `BrowserFrame` and `OnlineDialog`. `BrowserFrame` owns listener state, route classification, and idempotent “start listening” behavior; `OnlineDialog` owns active/pending room session readiness, geometry attribution, and soft session switching. Do not add a new cross-module framework; if a tiny helper type is needed, keep it package-private under `featurecat.lizzie.gui`.

**Tech Stack:** Java 17, Swing/JCEF integration, Maven Surefire/JUnit 5, existing Yike URL parser / probe / poller code.

**Spec:** `docs/specs/2026-05-08-yike-sync-session-state-design.md`

---

## File Structure

### Existing files to modify

| File | Responsibility in this change |
|---|---|
| `src/main/java/featurecat/lizzie/gui/BrowserFrame.java` | Split “listener enabled” from “room is actively syncing”, classify pages, make Yike start signals idempotent, and stop treating waiting pages as immediate sync targets. |
| `src/main/java/featurecat/lizzie/gui/OnlineDialog.java` | Track active/pending Yike room sessions, bind geometry to session identity, gate active handoff on `syncReady + geometryReady`, and invalidate geometry when leaving board pages. |
| `src/test/java/featurecat/lizzie/gui/BrowserFrameYikeSyncControlTest.java` | Lock listener semantics, waiting-page behavior, first-entry room bootstrap, and same-room idempotence. |
| `src/test/java/featurecat/lizzie/gui/YikeGeometryNormalizationTest.java` | Lock route classification and candidate compatibility, especially `live-room` vs `unite-board` vs waiting pages. |
| `src/test/java/featurecat/lizzie/gui/YikeUrlParserTest.java` | Keep URL parser expectations aligned with room/waiting-page semantics. |
| `src/test/java/featurecat/lizzie/analysis/ReadBoardYikeSyncControlTest.java` | Keep readboard-triggered `yikeSyncStart` / `syncPlatform yike` behavior aligned with new idempotent listener semantics. |

### New test file

| File | Responsibility |
|---|---|
| `src/test/java/featurecat/lizzie/gui/OnlineDialogYikeSessionStateTest.java` | Pure session-state coverage for active/pending room transitions, first-entry live-room bootstrap, waiting-state geometry invalidation, and stale geometry rejection. |

### Context to preserve

- There are already uncommitted Yike-related code changes in `BrowserFrame.java` and `OnlineDialog.java`. Build on them; do not revert unrelated edits.
- The DOM capture notes in `docs/2026-05-08-yike-page-structure-capture.md` are part of the contract for route classification and candidate filtering.
- Maven commands in this repo must use `D:\dev\weiqi\lizzieyzy-next\.tools\apache-maven-3.9.10\bin\mvn.cmd`.

---

## Task 1: Lock Listener Semantics With Failing Tests

**Files:**
- Modify: `src/test/java/featurecat/lizzie/gui/BrowserFrameYikeSyncControlTest.java`
- Modify: `src/test/java/featurecat/lizzie/analysis/ReadBoardYikeSyncControlTest.java`
- Test: `src/test/java/featurecat/lizzie/gui/BrowserFrameYikeSyncControlTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardYikeSyncControlTest.java`

- [ ] **Step 1: Add a failing test for waiting-page start behavior**

Add a test that treats `#/live` and `#/game` as waiting pages:

```java
@Test
void yikePlatformSignalOnWaitingPageKeepsListenerButDoesNotStartRoomSync() {
  assertTrue(BrowserFrame.shouldKeepYikeListenerEnabledOnPage(
      true, "https://home.yikeweiqi.com/#/live"));
  assertTrue(BrowserFrame.shouldKeepYikeListenerEnabledOnPage(
      true, "https://home.yikeweiqi.com/#/game"));
  assertFalse(BrowserFrame.shouldCreateOrRefreshYikeRoomSession(
      true, true, "https://home.yikeweiqi.com/#/live", ""));
}
```

- [ ] **Step 2: Run the targeted test and confirm it fails**

Run:

```powershell
& 'D:\dev\weiqi\lizzieyzy-next\.tools\apache-maven-3.9.10\bin\mvn.cmd' "-Dtest=BrowserFrameYikeSyncControlTest" test
```

Expected: FAIL because the new helper or behavior does not exist yet.

- [ ] **Step 3: Add a failing test for first-entry live-room bootstrap**

Add a test that proves a fresh `live-room` URL must bootstrap even when there is no prior `unite-board` success:

```java
@Test
void firstLiveRoomEntryCanBootstrapWithoutPriorUniteSession() {
  assertTrue(BrowserFrame.shouldCreateOrRefreshYikeRoomSession(
      true, true, "https://home.yikeweiqi.com/#/live/new-room/186538/0/0", ""));
}
```

- [ ] **Step 4: Add a failing test for same-room idempotence**

```java
@Test
void sameRoomSignalDoesNotRebuildSession() {
  String url = "https://home.yikeweiqi.com/#/unite/66304678";
  assertFalse(BrowserFrame.shouldCreateOrRefreshYikeRoomSession(true, true, url, url));
}
```

- [ ] **Step 5: Extend `ReadBoardYikeSyncControlTest` for repeated platform signals**

Add coverage that repeated `yikeSyncStart` / `syncPlatform yike` calls against the same room do not imply repeated full sync restarts, only listener retention and same-session no-op.

- [ ] **Step 6: Run the focused tests again and keep them red**

Run:

```powershell
& 'D:\dev\weiqi\lizzieyzy-next\.tools\apache-maven-3.9.10\bin\mvn.cmd' "-Dtest=BrowserFrameYikeSyncControlTest,ReadBoardYikeSyncControlTest" test
```

Expected: FAIL in the new assertions, PASS in unrelated existing tests.

- [ ] **Step 7: Commit the test-only checkpoint**

```bash
git add src/test/java/featurecat/lizzie/gui/BrowserFrameYikeSyncControlTest.java src/test/java/featurecat/lizzie/analysis/ReadBoardYikeSyncControlTest.java
git commit -m "test(yike): 锁定监听态与首进房间启动语义"
```

---

## Task 2: Lock Session-State and Geometry Attribution With Failing Tests

**Files:**
- Create: `src/test/java/featurecat/lizzie/gui/OnlineDialogYikeSessionStateTest.java`
- Modify: `src/test/java/featurecat/lizzie/gui/YikeGeometryNormalizationTest.java`
- Modify: `src/test/java/featurecat/lizzie/gui/YikeUrlParserTest.java`

- [ ] **Step 1: Create a new pure session-state test file**

Start with a focused test class that does not require Swing/JCEF boot:

```java
class OnlineDialogYikeSessionStateTest {
}
```

- [ ] **Step 2: Add a failing test for soft handoff gating**

Write a test that proves a pending room cannot replace the active room until both readiness flags are true:

```java
@Test
void pendingRoomDoesNotBecomeActiveUntilSyncAndGeometryAreBothReady() {
  YikeSessionState active = YikeSessionState.active("unite-board:66304678");
  YikeSessionState pending = YikeSessionState.pending("live-room:186538");

  pending = pending.withGeometryReady(true);
  assertFalse(OnlineDialog.shouldPromotePendingSession(active, pending));

  pending = pending.withSyncReady(true);
  assertTrue(OnlineDialog.shouldPromotePendingSession(active, pending));
}
```

- [ ] **Step 3: Add a failing test for waiting-page geometry invalidation**

```java
@Test
void waitingPageInvalidatesPlacementGeometryWithoutClearingDisplaySession() {
  assertTrue(OnlineDialog.shouldInvalidateYikePlacementGeometry("https://home.yikeweiqi.com/#/live"));
  assertTrue(OnlineDialog.shouldInvalidateYikePlacementGeometry("https://home.yikeweiqi.com/#/game"));
  assertFalse(OnlineDialog.shouldInvalidateYikePlacementGeometry("https://home.yikeweiqi.com/#/unite/66304678"));
}
```

- [ ] **Step 4: Add a failing test for stale geometry rejection**

```java
@Test
void geometryFromDifferentSessionKeyIsRejected() {
  assertFalse(OnlineDialog.isGeometryForCurrentSession("live-room:186538", "unite-board:66304678"));
  assertTrue(OnlineDialog.isGeometryForCurrentSession("live-room:186538", "live-room:186538"));
}
```

- [ ] **Step 5: Extend `YikeGeometryNormalizationTest` with session-key-aware expectations**

Add a focused assertion that a `live-room` candidate cannot be reused for `unite-board`, and vice versa, when the route/session identity changes.

- [ ] **Step 6: Extend `YikeUrlParserTest` only if needed**

If helper logic requires a stable room/session token from parsed URLs, add the smallest test necessary to lock that token derivation. Do not broaden parser scope.

- [ ] **Step 7: Run the geometry/session tests and confirm failure**

Run:

```powershell
& 'D:\dev\weiqi\lizzieyzy-next\.tools\apache-maven-3.9.10\bin\mvn.cmd' "-Dtest=OnlineDialogYikeSessionStateTest,YikeGeometryNormalizationTest,YikeUrlParserTest" test
```

Expected: FAIL on the new state-machine assertions.

- [ ] **Step 8: Commit the test-only checkpoint**

```bash
git add src/test/java/featurecat/lizzie/gui/OnlineDialogYikeSessionStateTest.java src/test/java/featurecat/lizzie/gui/YikeGeometryNormalizationTest.java src/test/java/featurecat/lizzie/gui/YikeUrlParserTest.java
git commit -m "test(yike): 锁定会话切换与几何归属规则"
```

---

## Task 3: Implement BrowserFrame Listener and Route Behavior

**Files:**
- Modify: `src/main/java/featurecat/lizzie/gui/BrowserFrame.java`
- Test: `src/test/java/featurecat/lizzie/gui/BrowserFrameYikeSyncControlTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardYikeSyncControlTest.java`

- [ ] **Step 1: Introduce explicit helper methods for listener/page semantics**

Add or reshape helpers so these concerns are separate:

- listener stays enabled
- page is waiting-page vs room-page
- current room session should be created/refreshed
- same-room repeated signal is a no-op

Keep them package-private/static where possible so the tests stay cheap.

- [ ] **Step 2: Make `ensureYikeSyncFromCurrentAddress()` listener-safe**

Change it so:

- waiting pages do not call full room sync startup
- room pages can bootstrap from zero state
- same-room repeated signals do not rebuild the room session

- [ ] **Step 3: Keep auto-start and manual-start semantics aligned**

Apply the same room/waiting-page logic to:

- `onAddressChange`
- popup navigation path
- readboard-triggered `yikeSyncStart`
- manual “同步” button path

The code should not have one path using “listener state” and another path bypassing it.

- [ ] **Step 4: Run focused tests**

Run:

```powershell
& 'D:\dev\weiqi\lizzieyzy-next\.tools\apache-maven-3.9.10\bin\mvn.cmd' "-Dtest=BrowserFrameYikeSyncControlTest,ReadBoardYikeSyncControlTest" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/featurecat/lizzie/gui/BrowserFrame.java src/test/java/featurecat/lizzie/gui/BrowserFrameYikeSyncControlTest.java src/test/java/featurecat/lizzie/analysis/ReadBoardYikeSyncControlTest.java
git commit -m "fix(yike): 拆分监听态与房间启动语义"
```

---

## Task 4: Implement OnlineDialog Session Handoff, Readiness, and Geometry Attribution

**Files:**
- Modify: `src/main/java/featurecat/lizzie/gui/OnlineDialog.java`
- Test: `src/test/java/featurecat/lizzie/gui/OnlineDialogYikeSessionStateTest.java`
- Test: `src/test/java/featurecat/lizzie/gui/YikeGeometryNormalizationTest.java`

- [ ] **Step 1: Add explicit active/pending session state**

Keep the state local to `OnlineDialog`. Store at least:

- active session key
- pending session key
- active geometry
- pending geometry
- syncReady / geometryReady per session
- active boardSize / pending boardSize if they can differ during handoff

Do not clear the old display state just because a new pending room appeared.

- [ ] **Step 2: Bind probe geometry to session identity**

When probe payload arrives:

- derive or pass through the current `sessionKey`
- reject geometry that does not belong to the current active/pending session
- never allow stale geometry from a previous room to become current placement geometry

- [ ] **Step 3: Separate display preservation from placement geometry**

Implement the waiting-page rule:

- leaving a board page invalidates placement geometry immediately
- old board display may remain until a new active room is promoted or user stops sync

- [ ] **Step 4: Gate room promotion on dual readiness**

Only promote `pendingSession -> activeSession` when:

- the new room has resolved a valid sync source and board size
- the new room has valid geometry for the same `sessionKey`

If only one is ready, keep logging/reporting that partial state instead of half-switching.

- [ ] **Step 5: Make first-entry live-room bootstrap independent**

Ensure the `live-room` path can mark `syncReady` and `boardSize` without requiring a prior `unite-board` success, old poller residue, or old geometry.

- [ ] **Step 6: Run the focused tests**

Run:

```powershell
& 'D:\dev\weiqi\lizzieyzy-next\.tools\apache-maven-3.9.10\bin\mvn.cmd' "-Dtest=OnlineDialogYikeSessionStateTest,YikeGeometryNormalizationTest,YikeUrlParserTest" test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/featurecat/lizzie/gui/OnlineDialog.java src/test/java/featurecat/lizzie/gui/OnlineDialogYikeSessionStateTest.java src/test/java/featurecat/lizzie/gui/YikeGeometryNormalizationTest.java
git commit -m "fix(yike): 实现会话软切换与几何归属隔离"
```

---

## Task 5: End-to-End Verification and Packaging Check

**Files:**
- Modify only if verification exposes a real bug in touched files
- Verify: `src/main/java/featurecat/lizzie/gui/BrowserFrame.java`
- Verify: `src/main/java/featurecat/lizzie/gui/OnlineDialog.java`
- Verify: `src/test/java/featurecat/lizzie/gui/*Yike*.java`
- Verify artifact: `target/lizzie-yzy2.5.3-shaded.jar`

- [ ] **Step 1: Run the full targeted Yike regression set**

Run:

```powershell
& 'D:\dev\weiqi\lizzieyzy-next\.tools\apache-maven-3.9.10\bin\mvn.cmd' "-Dtest=BrowserFrameYikeSyncControlTest,OnlineDialogYikeSessionStateTest,YikeGeometryNormalizationTest,YikeUrlParserTest,ReadBoardYikeSyncControlTest,YikeApiClientTest,YikeSgfMainlineTest" test
```

Expected: `BUILD SUCCESS` and all Yike-targeted tests passing.

- [ ] **Step 2: Build the jar**

Run:

```powershell
& 'D:\dev\weiqi\lizzieyzy-next\.tools\apache-maven-3.9.10\bin\mvn.cmd' -DskipTests package
```

Expected: `BUILD SUCCESS` and a fresh `target/lizzie-yzy2.5.3-shaded.jar`.

- [ ] **Step 3: Verify artifact timestamp**

Run:

```powershell
Get-Item 'D:\dev\weiqi\lizzieyzy-next\target\lizzie-yzy2.5.3-shaded.jar' | Select-Object FullName,Length,LastWriteTime
```

Expected: `LastWriteTime` matches the current build session.

- [ ] **Step 4: Review diff before final commit**

Run:

```bash
git diff --stat
git diff -- src/main/java/featurecat/lizzie/gui/BrowserFrame.java src/main/java/featurecat/lizzie/gui/OnlineDialog.java src/test/java/featurecat/lizzie/gui/BrowserFrameYikeSyncControlTest.java src/test/java/featurecat/lizzie/gui/OnlineDialogYikeSessionStateTest.java src/test/java/featurecat/lizzie/gui/YikeGeometryNormalizationTest.java src/test/java/featurecat/lizzie/gui/YikeUrlParserTest.java src/test/java/featurecat/lizzie/analysis/ReadBoardYikeSyncControlTest.java
```

Expected: only Yike sync/session-state behavior and related tests are changed.

- [ ] **Step 5: Final commit**

```bash
git add src/main/java/featurecat/lizzie/gui/BrowserFrame.java src/main/java/featurecat/lizzie/gui/OnlineDialog.java src/test/java/featurecat/lizzie/gui/BrowserFrameYikeSyncControlTest.java src/test/java/featurecat/lizzie/gui/OnlineDialogYikeSessionStateTest.java src/test/java/featurecat/lizzie/gui/YikeGeometryNormalizationTest.java src/test/java/featurecat/lizzie/gui/YikeUrlParserTest.java src/test/java/featurecat/lizzie/analysis/ReadBoardYikeSyncControlTest.java
git commit -m "fix(yike): 重构同步会话状态与房间切换链路"
```

---

## Notes for the Implementer

- Prefer adding small package-private helpers over piling more conditionals into long event handlers.
- Keep waiting-page semantics explicit. `#/live` and `#/game` are not “failed room sync”; they are “listener stays on, room session absent”.
- Treat white-box probe readiness and sync-source readiness as separate signals in code and logs.
- Do not silently clear old display state during soft handoff.
- Do not broaden route recognition beyond the DOM capture doc unless fresh evidence requires it.

## Plan Review Notes

This plan was reviewed locally against:

- `docs/specs/2026-05-08-yike-sync-session-state-design.md`
- `docs/2026-05-08-yike-page-structure-capture.md`
- existing Yike tests in `src/test/java/featurecat/lizzie/gui/`

No subagent plan review was dispatched here because this session is operating under a no-delegation constraint for subagents unless explicitly requested by the user.

## Execution Handoff

Plan complete and saved to `docs/plans/2026-05-08-yike-sync-session-state.md`. Ready to execute?
