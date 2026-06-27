# ReadBoard Last Move Source GMA Turn Trust Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Lizzie consume ReadBoard `lastMoveSource` metadata so visual markers determine side-to-play and GMA only starts from trusted synchronized board states.

**Architecture:** Extend the existing `SyncRemoteContext` metadata path that already carries `foxMoveNumber`. Keep `foxMoveNumber` as move-number metadata, but use `lastMoveSource` to decide whether marker color is authoritative and whether GMA may trust the current `isBlacksTurn()`.

**Tech Stack:** Java, Maven, JUnit 5, existing `ReadBoard` sync/GMA harnesses.

---

## Specs And Contracts

- Primary spec: `docs/specs/2026-06-26-readboard-last-move-source-gma-turn-trust-design.md`
- ReadBoard companion spec: `/home/dev/.config/superpowers/worktrees/readboard/kata-genmove-analyze-sync/docs/specs/2026-06-26-last-move-source-gma-turn-trust-design.md`
- Existing contracts to preserve:
  - `docs/SNAPSHOT_NODE_KIND.md`
  - `docs/specs/2026-06-24-readboard-gma-engine-decision-design.md`

## File Structure

- Create `src/main/java/featurecat/lizzie/analysis/ReadBoardLastMoveSource.java`  
  Parses wire tokens and exposes `isTrustedVisualMarker()`.
- Modify `src/main/java/featurecat/lizzie/analysis/SyncRemoteContext.java`  
  Add per-frame source metadata and `withLastMoveSource(...)`.
- Modify `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`  
  Parse `lastMoveSource`, clear it with pending frame resets, pass it into snapshot inference, and guard GMA.
- Modify `docs/SNAPSHOT_NODE_KIND.md`  
  Clarify that `foxMoveNumber` is not a handicap side-to-play authority and that visual marker source is separate from heuristic source.
- Modify `docs/specs/2026-06-24-readboard-gma-engine-decision-design.md`  
  Add follow-up turn-trust requirement.
- Tests:
  - `src/test/java/featurecat/lizzie/analysis/ReadBoardFoxMoveNumberParsingTest.java`
  - `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`
  - `src/test/java/featurecat/lizzie/analysis/ReadBoardEngineResumeTest.java`

## Task 1: Parse `lastMoveSource` As Per-Frame Metadata

**Files:**
- Create: `src/main/java/featurecat/lizzie/analysis/ReadBoardLastMoveSource.java`
- Modify: `src/main/java/featurecat/lizzie/analysis/SyncRemoteContext.java`
- Modify: `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardFoxMoveNumberParsingTest.java`

- [ ] **Step 1: Write failing parse tests**

Add tests:

```java
@Test
void validLastMoveSourceUpdatesPendingMetadata() throws Exception {
  ReadBoard readBoard = allocate(ReadBoard.class);

  readBoard.parseLine("lastMoveSource foxCornerFlip");

  SyncRemoteContext pendingRemoteContext =
      (SyncRemoteContext) getField(readBoard, "pendingRemoteContext");
  assertEquals(ReadBoardLastMoveSource.FOX_CORNER_FLIP, pendingRemoteContext.lastMoveSource);
}
```

Add similar tests for:

- `lastMoveSource redBlueMarker`
- `lastMoveSource deviation`
- `lastMoveSource stoneCount`
- unknown token becomes `UNKNOWN`, not previous trusted value
- invalid/blank line does not throw

- [ ] **Step 2: Write failing frame lifecycle test**

Add a test proving source does not leak:

```java
@Test
void lastMoveSourceDoesNotLeakAcrossControlReset() throws Exception {
  ReadBoard readBoard = allocate(ReadBoard.class);

  readBoard.parseLine("lastMoveSource foxCornerFlip");
  readBoard.parseLine("stopsync");

  SyncRemoteContext pendingRemoteContext =
      (SyncRemoteContext) getField(readBoard, "pendingRemoteContext");
  assertEquals(ReadBoardLastMoveSource.LEGACY_UNKNOWN, pendingRemoteContext.lastMoveSource);
}
```

Also cover `lastMoveSource nonsense` after `foxCornerFlip`; expected value is `UNKNOWN`.

- [ ] **Step 3: Run tests and verify they fail**

Run:

```bash
mvn -q -Dfmt.skip=true -Dtest=ReadBoardFoxMoveNumberParsingTest test
```

Expected: compile/test failure because source enum/context field does not exist.

- [ ] **Step 4: Implement parser and context field**

Create enum:

```java
enum ReadBoardLastMoveSource {
  LEGACY_UNKNOWN,
  UNKNOWN,
  NONE,
  RED_BLUE_MARKER,
  FOX_CORNER_FLIP,
  DEVIATION,
  STONE_COUNT;

  boolean isTrustedVisualMarker() {
    return this == RED_BLUE_MARKER || this == FOX_CORNER_FLIP;
  }
}
```

Add `parse(String token)` mapping exact wire tokens. Add `lastMoveSource` to `SyncRemoteContext`; all factory methods default it to `LEGACY_UNKNOWN`, and `withLastMoveSource(...)` preserves all other metadata. Add a `ReadBoard.parseLine(...)` branch for `lastMoveSource`.

- [ ] **Step 5: Ensure reset paths clear source**

Any control line that resets `pendingRemoteContext` must reset source to `LEGACY_UNKNOWN`. Do not preserve old trusted values through `end`, `clear`, `start`, `stopsync`, `endsync`, or `resetActiveSyncStateForReadBoardControlLine()`.

- [ ] **Step 6: Run tests and verify they pass**

Run the command from Step 3.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/featurecat/lizzie/analysis/ReadBoardLastMoveSource.java src/main/java/featurecat/lizzie/analysis/SyncRemoteContext.java src/main/java/featurecat/lizzie/analysis/ReadBoard.java src/test/java/featurecat/lizzie/analysis/ReadBoardFoxMoveNumberParsingTest.java
git commit -m "feat(readboard): parse last move source metadata"
```

## Task 2: Use Trusted Visual Markers For Snapshot Side-To-Play

**Files:**
- Modify: `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`

- [ ] **Step 1: Write failing visual-marker precedence tests**

Add tests near the existing `foxMoveNumber` rebuild tests:

```java
@Test
void visualLastMoveSourceBeatsConflictingFoxMoveNumberParity() throws Exception {
  Stone[] target = stones(placement(0, 0, Stone.BLACK));

  try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
    armFoxMoveNumber(harness.readBoard, 1); // odd parity would imply white to play.
    harness.readBoard.parseLine("lastMoveSource foxCornerFlip");
    harness.sync(snapshot(target, Optional.of(new int[] {0, 0}), Stone.BLACK));

    assertStaticSnapshotRoot(harness.board, target, Optional.of(new int[] {0, 0}), Stone.BLACK, 1);
    assertFalse(
        harness.board.getHistory().getMainEnd().getData().blackToPlay,
        "black last move from visual marker means white to play next");
  }
}
```

Use the existing marker convention from `assertStaticSnapshotRoot(...)`: marker color is the last move color; next side is black when marker color is white, and white when marker color is black. Add one test each for `foxCornerFlip` and `redBlueMarker`.

- [ ] **Step 2: Write failing heuristic-marker tests**

Add tests for `lastMoveSource stoneCount` and `lastMoveSource deviation` where `foxMoveNumber` conflicts with marker color. Expected: heuristic marker does not override the conservative fallback for GMA trust. Snapshot sync may still preserve marker metadata, but the new GMA trust state must be untrusted.

Add frame-lifecycle tests for source-only and held frames:

- accepted same-payload `NO_CHANGE` frame with `lastMoveSource stoneCount` keeps the turn trust untrusted.
- accepted same-payload `NO_CHANGE` frame that changes only `lastMoveSource` from `stoneCount` to `foxCornerFlip` refreshes turn trust to trusted without requiring a rebuild.
- a recovery `HOLD` / stale frame does not refresh turn trust from its pending `lastMoveSource`.
- `end` clears pending frame metadata/source but preserves the latest accepted-board turn trust.
- `stopsync`, `noboth`, and other invalidating controls reset turn trust to untrusted.

- [ ] **Step 3: Run tests and verify they fail**

Run:

```bash
mvn -q -Dfmt.skip=true -Dtest=ReadBoardSyncDecisionTest test
```

Expected: FAIL because `inferSnapshotBlackToPlay(...)` still prioritizes `foxMoveNumber`.

- [ ] **Step 4: Thread source into snapshot inference**

Change method signatures from:

```java
inferSnapshotHistoryState(..., OptionalInt foxMoveNumber)
inferSnapshotBlackToPlay(..., OptionalInt foxMoveNumber)
```

to also accept `ReadBoardLastMoveSource lastMoveSource`.

Priority:

1. empty snapshot existing behavior
2. `snapshotDelta.hasMarker()` and `lastMoveSource.isTrustedVisualMarker()` -> `snapshotDelta.markerColor() == Stone.WHITE`
3. markerless ordinary + `foxMoveNumber` -> parity fallback
4. existing `inferBlackToPlayWithoutMarker(...)`

Do not change `inferSnapshotMoveNumber(...)`.

- [ ] **Step 5: Add turn-trust state but do not guard GMA yet**

Add a small internal enum or boolean pair in `ReadBoard`, for example:

```java
private enum ReadBoardTurnTrust {
  TRUSTED,
  UNTRUSTED
}
```

After each successful sync/rebuild path, set this state based on the same source/risk analysis. This task may expose the state through package-private/test reflection only.

Use one helper, for example `updateReadBoardTurnTrustFromAcceptedFrame(reason, snapshotDelta, foxMoveNumber, lastMoveSource)`, and call it for every accepted frame outcome before `continueGameAfterSyncIfNeeded(...)` can schedule GMA:

- single-move sync
- rebuild
- acknowledged local move
- confirmed local placement
- complete-snapshot recovery `NO_CHANGE`
- other accepted same-payload frames where only metadata such as `lastMoveSource` changed

Do not call the helper for `HOLD` / stale observations. Clear the state together with per-frame ReadBoard metadata resets.

- [ ] **Step 6: Run tests and verify they pass**

Run the command from Step 3.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/featurecat/lizzie/analysis/ReadBoard.java src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java
git commit -m "fix(readboard): trust visual marker source for snapshot turns"
```

## Task 3: Define Markerless Ordinary And Setup Risk Helpers

**Files:**
- Modify: `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`

- [ ] **Step 1: Write failing tests for setup risk**

Add tests covering:

- `Lizzie.board.hasStartStone = true` makes `foxMoveNumber` fallback untrusted for GMA.
- root/current `BoardData.getProperties()` containing `AB` or `PL` makes fallback untrusted.
- `snapshotDelta.additions() > 1` makes fallback untrusted.
- markerless ordinary single-addition with `foxMoveNumber` remains trusted.

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
mvn -q -Dfmt.skip=true -Dtest=ReadBoardSyncDecisionTest test
```

Expected: FAIL until helper logic exists.

- [ ] **Step 3: Implement helpers**

Add private helpers in `ReadBoard.java`:

```java
private boolean isMarkerlessOrdinaryFoxTurnFallback(
    BoardHistoryNode syncStartNode,
    SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
    ReadBoardLastMoveSource lastMoveSource,
    OptionalInt foxMoveNumber) { ... }

private boolean hasSetupOrHandicapTurnRisk(
    BoardHistoryNode syncStartNode,
    SyncSnapshotClassifier.SnapshotDelta snapshotDelta) { ... }
```

Risk checks:

- `Lizzie.board.hasStartStone`
- `Lizzie.board.startStonelist != null && !Lizzie.board.startStonelist.isEmpty()`
- nearest relevant `BoardHistoryNode.hasRemovedStone()`
- `extraStones != null && !extraStones.isEmpty()`
- `BoardData.getProperties()` contains `AB`, `AW`, `AE`, `PL`, or `HA`
- `snapshotDelta.removals() > 0 || snapshotDelta.additions() > 1`

- [ ] **Step 4: Run tests and verify they pass**

Run the command from Step 2.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/featurecat/lizzie/analysis/ReadBoard.java src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java
git commit -m "fix(readboard): classify fox turn fallback risk"
```

## Task 4: Guard GMA Scheduling With Turn Trust

**Files:**
- Modify: `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardEngineResumeTest.java`

- [ ] **Step 1: Write failing GMA trusted visual tests**

Add tests to `ReadBoardEngineResumeTest`:

```java
@Test
void readBoardGmaStartsAfterTrustedFoxCornerMarkerShowsConfiguredSideToMove() throws Exception {
  try (EngineResumeHarness harness =
      EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
    harness.frame.bothSync = true;
    harness.leelaz.enableReadBoardGmaSupport();

    harness.readBoard.parseLine("play>white>0 0 0 gma");
    harness.readBoard.parseLine("foxMoveNumber 1");
    harness.readBoard.parseLine("lastMoveSource foxCornerFlip");
    Stone[] stones = emptyStones();
    stones[stoneIndex(0, 0)] = Stone.BLACK;

    harness.sync(snapshot(stones, Optional.of(new int[] {0, 0}), Stone.BLACK));

    assertEquals(1, harness.leelaz.readBoardGmaCount);
    assertEquals("W", harness.leelaz.lastReadBoardGmaColor);
  }
}
```

Add a red/blue marker equivalent if it is not redundant after helper coverage.

- [ ] **Step 2: Write failing untrusted heuristic GMA tests**

Add tests:

- `lastMoveSource stoneCount` + setup/handicap risk -> no GMA start
- missing `lastMoveSource` + setup/handicap risk -> no GMA start
- markerless ordinary `foxMoveNumber` without risk -> existing GMA behavior still starts
- source-only update: first an accepted frame leaves GMA blocked as untrusted, then an otherwise identical accepted `NO_CHANGE` frame with `lastMoveSource foxCornerFlip` refreshes turn trust and starts GMA

- [ ] **Step 3: Run tests and verify they fail**

Run:

```bash
mvn -q -Dfmt.skip=true -Dtest=ReadBoardEngineResumeTest test
```

Expected: FAIL until scheduler checks turn trust.

- [ ] **Step 4: Implement scheduler guard**

In `scheduleReadBoardGmaIfNeeded(...)`, after turn/color match and before engine start, add:

```java
if (!isCurrentReadBoardGmaTurnTrusted()) {
  localMoveSyncDebug("ReadBoard GMA skip untrusted turn reason=" + reason);
  return false;
}
```

Do not clear auto-play state. The next frame may become trusted.

- [ ] **Step 5: Recheck final engine play path**

In `handleReadBoardGmaEnginePlay(...)`, keep the existing stale/turn mismatch checks. Do not add a second click path or fall back to normal `genmove`.

- [ ] **Step 6: Run tests and verify they pass**

Run the command from Step 3.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/featurecat/lizzie/analysis/ReadBoard.java src/test/java/featurecat/lizzie/analysis/ReadBoardEngineResumeTest.java
git commit -m "fix(readboard): require trusted turns for GMA scheduling"
```

## Task 5: Update SNAPSHOT And GMA Docs

**Files:**
- Modify: `docs/SNAPSHOT_NODE_KIND.md`
- Modify: `docs/specs/2026-06-24-readboard-gma-engine-decision-design.md`
- Test: no code test required unless existing doc contract tests fail.

- [ ] **Step 1: Update `docs/SNAPSHOT_NODE_KIND.md`**

Add a short section near the `foxMoveNumber`/marker bullets:

```markdown
- `foxMoveNumber` 修正 `SNAPSHOT` moveNumber metadata，但不是让子棋、setup 或白方首手场景的 side-to-play 权威。
- ReadBoard `lastMoveSource` 区分真实视觉 marker 与启发式末手；只有真实视觉 marker 可在冲突时优先决定 `side-to-play`。
- 启发式 `deviation` / `stoneCount` 不能被当成真实 `MOVE/PASS` 或 GMA 权威轮次。
```

- [ ] **Step 2: Update GMA design spec**

Add a follow-up paragraph to `docs/specs/2026-06-24-readboard-gma-engine-decision-design.md` under the unified GMA scheduling section explaining that `scheduleReadBoardGmaIfNeeded(...)` requires trusted ReadBoard side-to-play.

- [ ] **Step 3: Run focused docs-adjacent tests**

Run:

```bash
mvn -q -Dfmt.skip=true -Dtest=ReadBoardSyncDecisionTest,ReadBoardEngineResumeTest test
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add docs/SNAPSHOT_NODE_KIND.md docs/specs/2026-06-24-readboard-gma-engine-decision-design.md
git commit -m "docs(readboard): clarify last move source turn trust"
```

## Task 6: Final Lizzie Verification

**Files:**
- No new files unless failures expose missing coverage.

- [ ] **Step 1: Run focused test suite**

Run:

```bash
mvn -q -Dfmt.skip=true -Dtest=ReadBoardFoxMoveNumberParsingTest,ReadBoardSyncDecisionTest,ReadBoardEngineResumeTest test
```

Expected: PASS.

- [ ] **Step 2: Run the existing GMA engine command tests**

Run:

```bash
mvn -q -Dfmt.skip=true -Dtest=LeelazReadBoardGmaTest test
```

Expected: PASS.

- [ ] **Step 3: Review scope**

Run:

```bash
git status --short
git diff --stat HEAD
```

Expected: only files listed in this plan changed.

- [ ] **Step 4: Commit final fixes if any**

If verification required a small follow-up:

```bash
git add src/main/java src/test/java docs
git commit -m "fix(readboard): harden last move source turn trust"
```
