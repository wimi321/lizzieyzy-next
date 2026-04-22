# ReadBoard Early-Game History Implementation Plan

> **For agentic workers:** Choose the execution workflow explicitly. Use `superpowers:executing-plans` when higher-priority instructions prefer inline execution or tasks are tightly coupled. Use `superpowers:subagent-driven-development` only when tasks are truly independent and delegation is explicitly desired. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Fox opening sync keep provable early moves as real `MOVE` history from move 1, or from the first move after a handicap/setup root, instead of collapsing those frames into `SNAPSHOT` rebuilds.

**Architecture:** Keep the generic classifier conservative and implement the new tolerance only inside `ReadBoard`. `ReadBoard` will allow one extra Fox-only incremental case: markerless single-stone addition with `recoveryMoveNumber == syncStartNode.moveNumber + 1`. The same change will also remove legacy `firstSync` flattening from the incremental path and advance `lastResolvedSnapshotNode` after every successful incremental landing so later Fox recovery decisions keep using the newest proven node.

**Tech Stack:** Java 17/21 + Maven + JUnit 5, existing `ReadBoard` sync harnesses, branch contract docs under `docs/`.

---

## File Map

- Modify: `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
  - Add a Fox-only incremental eligibility helper.
  - Remove the legacy `firstSync` flatten branch from normal incremental sync.
  - Remember the resolved node after ordinary incremental sync succeeds.
- Modify: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`
  - Add opening-history regression tests for empty-board openings, handicap openings, steady-state after the first markerless move, and first-sync flatten removal.
  - Replace the stale expectation that a markerless Fox opening frame should rebuild into a root `SNAPSHOT`.
- Modify: `src/test/java/featurecat/lizzie/analysis/ReadBoardResumeLifecycleTest.java`
  - Add a lifecycle assertion that successful ordinary incremental sync advances `resumeState` / `lastResolvedSnapshotNode`.
- No code change expected: `src/main/java/featurecat/lizzie/analysis/SyncSnapshotClassifier.java`
  - This plan intentionally keeps generic classifier semantics unchanged.

## Task 0: Re-establish Contracts And Fast Feedback

**Files:**
- Modify: none
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardResumeLifecycleTest.java`

- [ ] **Step 1: Re-read the active contracts before touching code**

```text
/mnt/d/dev/weiqi/lizzieyzy-next/docs/SNAPSHOT_NODE_KIND.md
/mnt/d/dev/weiqi/lizzieyzy-next/docs/superpowers/specs/2026-04-21-readboard-sync-boundaries-design.md
/mnt/d/dev/weiqi/lizzieyzy-next/docs/superpowers/specs/2026-04-22-readboard-mainline-window-sync-design.md
/mnt/d/dev/weiqi/lizzieyzy-next/docs/superpowers/specs/2026-04-22-readboard-early-game-history-design.md
```

Expected: the worker is operating from the branch contract plus the approved early-game spec, not memory.

- [ ] **Step 2: Refresh compiled classes without invoking the default Maven lifecycle**

Run:

```bash
mvn -q compiler:compile compiler:testCompile
```

Expected: exit code `0`.

- [ ] **Step 3: Run the focused pre-change baseline**

Run:

```bash
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardSyncDecisionTest -DfailIfNoTests=false surefire:test
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardResumeLifecycleTest -DfailIfNoTests=false surefire:test
```

Expected: the current branch baseline is green before new failing tests are added.

- [ ] **Step 4: Keep the implementation boundary visible**

```text
Do not modify `SyncSnapshotClassifier` for this task.
Do not touch `/mnt/d/dev/weiqi/readboard`.
Do not use `mvn test` or the default `package` lifecycle in feedback loops on this branch.
```

- [ ] **Step 5: Do not commit**

```text
No production code has changed yet. Move straight into TDD.
```

## Task 1: Add Failing Sync-Decision Tests For Early-Game Fox History

**Files:**
- Modify: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`

- [ ] **Step 1: Add the failing empty-board opening test**

```java
@Test
void foxMarkerlessOpeningMoveBecomesRealHistoryInsteadOfSnapshotRebuild() throws Exception {
  Stone[] target = stones(placement(0, 0, Stone.BLACK));

  try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
    armFoxMoveNumber(harness.readBoard, 1);
    harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

    BoardHistoryNode mainEnd = harness.board.getHistory().getMainEnd();
    BoardData data = mainEnd.getData();

    assertEquals(1, harness.board.placeForSyncCount, "opening move should be appended incrementally.");
    assertSame(mainEnd, harness.board.getHistory().getCurrentHistoryNode());
    assertTrue(data.isMoveNode(), "first proven Fox move should stay a MOVE node.");
    assertTrue(mainEnd.previous().isPresent(), "opening move should keep explicit history.");
    assertEquals(1, data.moveNumber, "opening move should keep its real move number.");
    assertFalse(harness.board.hasStartStone, "incremental opening sync must not rewrite root setup.");
  }
}
```

- [ ] **Step 2: Add the failing handicap-opening test**

```java
@Test
void foxMarkerlessFirstMoveAfterHandicapSetupBecomesRealHistory() throws Exception {
  Stone[] handicapRoot = stones(placement(0, 0, Stone.BLACK));
  Stone[] target = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));

  try (SyncHarness harness =
      SyncHarness.create(
          false,
          rootHistory(
              handicapRoot, Optional.empty(), Stone.EMPTY, false, 0, BoardNodeKind.SNAPSHOT))) {
    armFoxMoveNumber(harness.readBoard, 1);
    harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

    BoardHistoryNode root = harness.board.getHistory().root();
    BoardHistoryNode mainEnd = harness.board.getHistory().getMainEnd();

    assertEquals(1, harness.board.placeForSyncCount, "first move after setup should append incrementally.");
    assertTrue(mainEnd.getData().isMoveNode(), "the move after handicap setup should become a MOVE node.");
    assertTrue(root.getData().isSnapshotNode(), "the original setup root should remain a SNAPSHOT.");
    assertEquals(1, mainEnd.getData().moveNumber, "the first post-setup move should stay move 1.");
  }
}
```

- [ ] **Step 3: Replace the stale steady-state rebuild expectation with a real-history expectation**

```java
@Test
void steadyStateFoxOpeningFrameKeepsRealMoveNodeAfterMarkerlessIncrementalSync() throws Exception {
  Stone[] target = stones(placement(0, 0, Stone.BLACK));

  try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
    armFoxMoveNumber(harness.readBoard, 1);
    harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

    BoardHistoryNode trackedMainEnd = harness.board.getHistory().getMainEnd();
    assertTrue(trackedMainEnd.getData().isMoveNode(), "first markerless Fox move should no longer rebuild.");

    harness.board.resetCounters();
    harness.frame.refreshCount = 0;
    harness.leelaz.clearCount = 0;
    harness.leelaz.playedMoves = new ArrayList<>();

    armFoxMoveNumber(harness.readBoard, 1);
    harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

    assertSame(trackedMainEnd, harness.board.getHistory().getMainEnd());
    assertEquals(0, harness.board.placeForSyncCount, "steady-state frame should stay on NO_CHANGE.");
    assertEquals(0, harness.leelaz.clearCount, "steady-state frame should not rebuild the engine.");
    assertEquals(0, harness.frame.refreshCount, "steady-state frame should keep the UI steady.");
  }
}
```

- [ ] **Step 4: Add the failing first-sync flatten regression**

```java
@Test
void firstSyncMarkedOpeningDoesNotFlattenRealMoveIntoRootSetup() throws Exception {
  Stone[] target = stones(placement(0, 0, Stone.BLACK));

  try (SyncHarness harness = SyncHarness.create(true, emptyHistory())) {
    harness.sync(snapshot(target, Optional.of(new int[] {0, 0}), Stone.BLACK));

    BoardHistoryNode mainEnd = harness.board.getHistory().getMainEnd();
    assertTrue(mainEnd.getData().isMoveNode(), "first sync incremental move should remain a MOVE node.");
    assertTrue(mainEnd.previous().isPresent(), "first sync should keep explicit history.");
    assertFalse(harness.board.hasStartStone, "first sync should not convert the prefix into start stones.");
  }
}
```

- [ ] **Step 5: Run only the new/changed decision tests and verify they fail for the current reasons**

Run:

```bash
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardSyncDecisionTest#foxMarkerlessOpeningMoveBecomesRealHistoryInsteadOfSnapshotRebuild -DfailIfNoTests=false surefire:test
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardSyncDecisionTest#foxMarkerlessFirstMoveAfterHandicapSetupBecomesRealHistory -DfailIfNoTests=false surefire:test
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardSyncDecisionTest#steadyStateFoxOpeningFrameKeepsRealMoveNodeAfterMarkerlessIncrementalSync -DfailIfNoTests=false surefire:test
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardSyncDecisionTest#firstSyncMarkedOpeningDoesNotFlattenRealMoveIntoRootSetup -DfailIfNoTests=false surefire:test
```

Expected:
- the first two tests fail because markerless single-addition Fox openings still rebuild;
- the steady-state test fails because the first frame currently lands on a rebuilt `SNAPSHOT`;
- the first-sync test fails because `hasStartStone/addStartListAll/flatten` still collapses incremental history.

## Task 2: Add The Failing `lastResolved` Lifecycle Test

**Files:**
- Modify: `src/test/java/featurecat/lizzie/analysis/ReadBoardResumeLifecycleTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardResumeLifecycleTest.java`

- [ ] **Step 1: Add the failing ordinary-incremental lifecycle assertion**

```java
@Test
void ordinaryIncrementalSyncAdvancesResumeStateToNewMainEnd() throws Exception {
  try (ResumeHarness harness = ResumeHarness.create(rootHistory(emptyStones(), true))) {
    harness.readBoard.parseLine("syncPlatform fox");
    harness.readBoard.parseLine("foxMoveNumber 1");
    harness.sync(snapshot(stones(placement(0, 0, Stone.BLACK)), Optional.of(new int[] {0, 0}), Stone.BLACK));

    SyncResumeState resumeState = (SyncResumeState) getField(harness.readBoard, "resumeState");
    BoardHistoryNode mainEnd = harness.board.getHistory().getMainEnd();

    assertNotNull(resumeState, "successful incremental sync should arm resumeState.");
    assertSame(mainEnd, resumeState.node, "resumeState should track the newest proven node.");
    assertSame(mainEnd, getField(harness.readBoard, "lastResolvedSnapshotNode"));
  }
}
```

- [ ] **Step 2: Run the lifecycle test in isolation and verify it fails**

Run:

```bash
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardResumeLifecycleTest#ordinaryIncrementalSyncAdvancesResumeStateToNewMainEnd -DfailIfNoTests=false surefire:test
```

Expected: FAIL because the ordinary incremental path currently does not call `rememberResolvedSnapshotNode(...)`.

- [ ] **Step 3: Do not commit yet**

```text
Keep the tests red and move directly into the production change.
```

## Task 3: Implement The Fox Early-Game Incremental Path In `ReadBoard`

**Files:**
- Modify: `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardResumeLifecycleTest.java`

- [ ] **Step 1: Add a Fox-only incremental eligibility helper instead of widening `SyncSnapshotClassifier`**

```java
private boolean allowsIncrementalSync(
    SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
    BoardHistoryNode syncStartNode,
    SyncRemoteContext remoteContext) {
  return snapshotDelta.allowsIncrementalSync()
      || allowsFoxMarkerlessSingleStep(snapshotDelta, syncStartNode, remoteContext);
}

private boolean allowsFoxMarkerlessSingleStep(
    SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
    BoardHistoryNode syncStartNode,
    SyncRemoteContext remoteContext) {
  if (snapshotDelta == null
      || syncStartNode == null
      || remoteContext == null
      || !remoteContext.supportsFoxRecovery()
      || snapshotDelta.hasMarker()
      || !snapshotDelta.hasOnlyAdditions()
      || snapshotDelta.additions() != 1) {
    return false;
  }
  return remoteContext.recoveryMoveNumber().getAsInt()
      == syncStartNode.getData().moveNumber + 1;
}
```

- [ ] **Step 2: Route the existing incremental branch through the new helper and remove first-sync flattening**

```java
if (allowsIncrementalSync(snapshotDelta, node2, currentRemoteContext)) {
  for (int i = 0; i < tempcount.size(); i++) {
    int m = tempcount.get(i);
    int y = i / Board.boardWidth;
    int x = i % Board.boardWidth;
    // keep the current per-stone replay conditions exactly as they are today
  }
  if (holdLastMove && !needReSync) {
    if (!played) {
      moveToAnyPositionWithoutTracking(node2);
    }
    Lizzie.board.placeForSync(lastX, lastY, isLastBlack ? Stone.BLACK : Stone.WHITE, true);
    if (node2.variations.size() > 0 && node2.variations.get(0).isEndDummay()) {
      node2.variations.add(0, node2.variations.get(node2.variations.size() - 1));
      node2.variations.remove(1);
      node2.variations.remove(node2.variations.size() - 1);
    }
    played = true;
    if (Lizzie.config.alwaysSyncBoardStat || showInBoard) {
      lastMoveWithoutTracking();
    }
  }
} else {
  needReSync = true;
}
```

- [ ] **Step 3: Advance `lastResolvedSnapshotNode` after ordinary incremental success**

```java
if (!needReSync) {
  BoardHistoryNode currentSyncEndNode = Lizzie.board.getHistory().getMainEnd();
  if (played && !singleMoveRecovered) {
    rememberResolvedSnapshotNode(currentSyncEndNode);
  }
  if (singleMoveRecovered) {
    keepViewOnRecoveredMainEnd(currentSyncEndNode);
  }
  BoardHistoryNode currentNode =
      singleMoveRecovered
          ? currentSyncEndNode
          : resolveLocalNavigationTarget(Lizzie.board.getHistory().getCurrentHistoryNode());
  if (shouldRebuildForFoxMetadataChange(
      currentSyncEndNode, currentRemoteContext, currentSnapshotCodes)) {
    rebuildFromSnapshot(
        currentSyncEndNode, currentSnapshotCodes, snapshotDelta, currentFoxMoveNumber);
    return;
  }
}
```

- [ ] **Step 4: Keep the conservative boundaries explicit in code review**

```text
Do not allow:
- markerless captures
- markerless multi-stone additions
- rollback
- move-number jumps larger than one
- non-Fox recovery contexts
```

- [ ] **Step 5: Run the new targeted tests and make them pass**

Run:

```bash
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardSyncDecisionTest#foxMarkerlessOpeningMoveBecomesRealHistoryInsteadOfSnapshotRebuild -DfailIfNoTests=false surefire:test
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardSyncDecisionTest#foxMarkerlessFirstMoveAfterHandicapSetupBecomesRealHistory -DfailIfNoTests=false surefire:test
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardSyncDecisionTest#steadyStateFoxOpeningFrameKeepsRealMoveNodeAfterMarkerlessIncrementalSync -DfailIfNoTests=false surefire:test
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardSyncDecisionTest#firstSyncMarkedOpeningDoesNotFlattenRealMoveIntoRootSetup -DfailIfNoTests=false surefire:test
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardResumeLifecycleTest#ordinaryIncrementalSyncAdvancesResumeStateToNewMainEnd -DfailIfNoTests=false surefire:test
```

Expected: PASS.

- [ ] **Step 6: Commit the production change once the targeted red tests are green**

```bash
git add src/main/java/featurecat/lizzie/analysis/ReadBoard.java src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java src/test/java/featurecat/lizzie/analysis/ReadBoardResumeLifecycleTest.java
git commit -m "fix(sync): 修复野狐开局前几手历史丢失"
```

Expected: one focused commit for the early-game history fix.

## Task 4: Re-run The Focused Regression Net

**Files:**
- Modify: none
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardResumeLifecycleTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardEngineResumeTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java`

- [ ] **Step 1: Re-run the full sync-decision class**

Run:

```bash
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardSyncDecisionTest -DfailIfNoTests=false surefire:test
```

Expected: PASS, including the tests that used to lock markerless Fox openings into `SNAPSHOT` rebuilds.

- [ ] **Step 2: Re-run resume lifecycle coverage**

Run:

```bash
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardResumeLifecycleTest -DfailIfNoTests=false surefire:test
```

Expected: PASS.

- [ ] **Step 3: Re-run the neighboring regressions that must stay green**

Run:

```bash
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardEngineResumeTest -DfailIfNoTests=false surefire:test
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.SyncSnapshotRebuildPolicyTest -DfailIfNoTests=false surefire:test
```

Expected: PASS, proving the early-game fix did not disturb analysis-resume binding or rebuild-policy identity logic.

- [ ] **Step 4: Rebuild compiled artifacts without packaging**

Run:

```bash
mvn -q compiler:compile compiler:testCompile
```

Expected: exit code `0`.

- [ ] **Step 5: Commit only if every focused regression is green**

```text
If any regression fails, fix it before leaving this task. Do not add fallback behavior just to make tests pass.
```

## Task 5: Final Self-Review Before Execution Hand-off

**Files:**
- Modify: none
- Test: none

- [ ] **Step 1: Verify the final code still matches the approved boundary**

```text
Confirm that the implementation only widened:
- Fox markerless single-stone additions
- when recovery move number proves exactly current + 1

Confirm that the implementation did not widen:
- generic / non-Fox platforms
- captures
- multi-stone additions
- rollback
- jumps larger than one
```

- [ ] **Step 2: Verify root/setup semantics stayed intact**

```text
Confirm that root setup and handicap roots still enter via SNAPSHOT rebuilds only.
Confirm that ordinary incremental sync no longer calls `hasStartStone`, `addStartListAll()`, or `flatten()`.
```

- [ ] **Step 3: Verify the stale test expectation is gone**

```text
Search `ReadBoardSyncDecisionTest` for assertions that a markerless Fox opening frame should rebuild into a root SNAPSHOT.
There should be none left after this task.
```

- [ ] **Step 4: Leave packaging out of this task**

```text
Do not run `mvn package`, release scripts, or installer workflows here.
This task ends at code + focused tests.
```
