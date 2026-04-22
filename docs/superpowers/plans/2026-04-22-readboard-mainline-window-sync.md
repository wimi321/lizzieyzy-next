# ReadBoard Mainline Window Sync Implementation Plan

> **For agentic workers:** Choose the execution workflow explicitly. Use `superpowers:executing-plans` when higher-priority instructions prefer inline execution or tasks are tightly coupled. Use `superpowers:subagent-driven-development` only when tasks are truly independent and delegation is explicitly desired. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Fox sync reuse already-existing nodes inside the current mainline window so rollback-to-ancestor plus forward navigation no longer stalls or misfires into `FORCE_REBUILD`.

**Architecture:** Keep the external sync outcomes unchanged (`NO_CHANGE`, `SINGLE_MOVE_RECOVERY`, `HOLD`, `FORCE_REBUILD`), but insert a new “current mainline window hit” layer before `lastResolved` adjacency and rebuild. `SyncSnapshotRebuildPolicy` will become responsible for finding an existing node between the viewed node, its ancestors, and the current `mainEnd`, while `ReadBoard` will distinguish “stay on current” from “navigate to an existing mainline node” through an internal decision object instead of a bare enum.

**Tech Stack:** Java 17/21 + Maven + JUnit 5, existing `ReadBoard` sync harnesses, branch contract docs under `docs/`.

---

## File Map

- Modify: `src/main/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicy.java`
  - Add a focused helper that searches only the current mainline window.
  - Keep ancestor-only and `lastResolved + 1` helpers intact for fallback layers.
- Modify: `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
  - Replace the bare `CompleteSnapshotRecovery` enum return path with an internal decision object.
  - Insert “window hit” before `lastResolved` adjacency, `SINGLE_MOVE_RECOVERY`, and `HOLD / FORCE_REBUILD`.
  - Navigate to an existing node when the target is already present in the current mainline window.
- Modify: `src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java`
  - Lock window-forward hits and “no variation hit” behavior.
- Modify: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`
  - Lock live-room and record-view rollback/forward navigation against already-existing mainline nodes.
- Modify: `docs/SNAPSHOT_NODE_KIND.md`
  - Add the branch-level contract for “existing node in current mainline window => direct navigation”.
- Modify: `docs/superpowers/specs/2026-04-21-readboard-sync-boundaries-design.md`
  - Extend the approved decision matrix with the window-hit rule.

## Task 0: Re-establish The Guardrails And Baseline

**Files:**
- Modify: none
- Test: `src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`

- [ ] **Step 1: Re-read the active contracts before changing code**

```text
/mnt/d/dev/weiqi/lizzieyzy-next/docs/SNAPSHOT_NODE_KIND.md
/mnt/d/dev/weiqi/lizzieyzy-next/docs/superpowers/specs/2026-04-21-readboard-sync-boundaries-design.md
/mnt/d/dev/weiqi/lizzieyzy-next/docs/superpowers/specs/2026-04-22-readboard-mainline-window-sync-design.md
```

Expected: the worker is operating from the branch contract plus the newly approved spec, not memory.

- [ ] **Step 2: Refresh compiled classes without invoking the default Maven lifecycle**

Run:

```bash
mvn -q compiler:compile compiler:testCompile
```

Expected: exit code `0`.

- [ ] **Step 3: Run the two existing focused test classes as the pre-change baseline**

Run:

```bash
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.SyncSnapshotRebuildPolicyTest -DfailIfNoTests=false surefire:test
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardSyncDecisionTest -DfailIfNoTests=false surefire:test
```

Expected: current baseline is green before adding new failing tests.

- [ ] **Step 4: Keep the verification constraint visible during the whole task**

```text
Do not use `mvn test` or plain `mvn package` during implementation feedback loops on this branch.
Use `compiler:compile compiler:testCompile` plus targeted `surefire:test` commands.
```

- [ ] **Step 5: Do not commit**

```text
No code changed yet. Move straight into TDD.
```

## Task 1: Add Failing Policy Tests For Mainline Window Matching

**Files:**
- Modify: `src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java`

- [ ] **Step 1: Add the failing Fox live forward-window test**

```java
@Test
void foxLiveWindowMatchFindsExistingForwardMainlineNode() {
  SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
  BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
  BoardHistoryNode moveOne =
      root.add(
          createMoveHistoryNode(
              stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
  BoardHistoryNode moveTwo =
      moveOne.add(
          createMoveHistoryNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
  BoardHistoryNode moveThree =
      moveTwo.add(
          createMoveHistoryNode(
              stones(
                  placement(0, 0, Stone.BLACK),
                  placement(1, 0, Stone.WHITE),
                  placement(0, 1, Stone.BLACK)),
              new int[] {0, 1},
              Stone.BLACK,
              false,
              3));

  Optional<BoardHistoryNode> matchedNode =
      policy.findMatchingNodeInMainlineWindow(
          moveOne,
          moveThree,
          snapshot(moveTwo.getData().stones, moveTwo.getData().lastMove, moveTwo.getData().lastMoveColor),
          SyncRemoteContext.forFoxLive(OptionalInt.of(2), "43581号", OptionalInt.of(2), false));

  assertTrue(matchedNode.isPresent());
  assertSame(moveTwo, matchedNode.get());
}
```

- [ ] **Step 2: Add the failing variation-exclusion test**

```java
@Test
void mainlineWindowMatchDoesNotUseVariationNode() {
  SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
  BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
  BoardHistoryNode moveOne =
      root.add(
          createMoveHistoryNode(
              stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
  BoardHistoryNode mainlineMoveTwo =
      moveOne.add(
          createMoveHistoryNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));
  BoardHistoryNode variationMoveTwo =
      moveOne.add(
          createMoveHistoryNode(
              stones(placement(0, 0, Stone.BLACK), placement(2, 2, Stone.WHITE)),
              new int[] {2, 2},
              Stone.WHITE,
              true,
              2));

  Optional<BoardHistoryNode> matchedNode =
      policy.findMatchingNodeInMainlineWindow(
          moveOne,
          mainlineMoveTwo,
          snapshot(
              variationMoveTwo.getData().stones,
              variationMoveTwo.getData().lastMove,
              variationMoveTwo.getData().lastMoveColor),
          SyncRemoteContext.forFoxLive(OptionalInt.of(2), "43581号", OptionalInt.of(2), false));

  assertFalse(matchedNode.isPresent());
}
```

- [ ] **Step 3: Add the failing Fox record-view forward-window test**

```java
@Test
void foxRecordWindowMatchFindsExistingForwardMainlineNode() {
  SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
  BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
  BoardHistoryNode moveOne =
      root.add(
          createMoveHistoryNode(
              stones(placement(0, 0, Stone.BLACK)), new int[] {0, 0}, Stone.BLACK, false, 1));
  BoardHistoryNode moveTwo =
      moveOne.add(
          createMoveHistoryNode(
              stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE)),
              new int[] {1, 0},
              Stone.WHITE,
              true,
              2));

  Optional<BoardHistoryNode> matchedNode =
      policy.findMatchingNodeInMainlineWindow(
          moveOne,
          moveTwo,
          snapshot(moveTwo.getData().stones, moveTwo.getData().lastMove, moveTwo.getData().lastMoveColor),
          SyncRemoteContext.forFoxRecord(
              OptionalInt.of(2),
              OptionalInt.of(2),
              OptionalInt.of(333),
              false,
              "record-fingerprint",
              false));

  assertTrue(matchedNode.isPresent());
  assertSame(moveTwo, matchedNode.get());
}
```

- [ ] **Step 4: Run the policy test class and verify the new tests fail**

Run:

```bash
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.SyncSnapshotRebuildPolicyTest -DfailIfNoTests=false surefire:test
```

Expected: FAIL because `findMatchingNodeInMainlineWindow(...)` does not exist yet.

- [ ] **Step 5: Commit only after the class is green**

```bash
git add src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java src/main/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicy.java
git commit -m "test(sync): 补充主线窗口命中策略用例"
```

Expected: skip this step until Task 2 is complete and the class is green.

## Task 2: Implement Mainline Window Matching In `SyncSnapshotRebuildPolicy`

**Files:**
- Modify: `src/main/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicy.java`
- Test: `src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java`

- [ ] **Step 1: Add the new window-matching helper with the narrowest allowed scan**

```java
Optional<BoardHistoryNode> findMatchingNodeInMainlineWindow(
    BoardHistoryNode currentNode,
    BoardHistoryNode mainEnd,
    int[] snapshotCodes,
    SyncRemoteContext remoteContext) {
  if (currentNode == null
      || mainEnd == null
      || snapshotCodes.length == 0
      || remoteContext == null
      || !remoteContext.supportsFoxRecovery()) {
    return Optional.empty();
  }

  Optional<BoardHistoryNode> currentOrAncestor =
      findMatchingHistoryNode(currentNode, snapshotCodes, remoteContext);
  if (currentOrAncestor.isPresent()) {
    return currentOrAncestor;
  }

  for (BoardHistoryNode cursor = nextMainlineNode(currentNode);
      cursor != null;
      cursor = cursor == mainEnd ? null : nextMainlineNode(cursor)) {
    if (matchesRemoteIdentity(cursor.getData(), snapshotCodes, remoteContext)) {
      return Optional.of(cursor);
    }
  }
  return Optional.empty();
}
```

- [ ] **Step 2: Add the tiny forward-only helper so the scan cannot escape the main trunk**

```java
private BoardHistoryNode nextMainlineNode(BoardHistoryNode node) {
  return node == null ? null : node.next().filter(BoardHistoryNode::isMainTrunk).orElse(null);
}
```

- [ ] **Step 3: Keep existing helpers intact**

```text
Do not remove or repurpose:
- findMatchingHistoryNode(...)
- findAdjacentMatchFromLastResolvedNode(...)

They stay as fallback layers for existing behavior outside the new window-hit rule.
```

- [ ] **Step 4: Run the policy test class and verify it passes**

Run:

```bash
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.SyncSnapshotRebuildPolicyTest -DfailIfNoTests=false surefire:test
```

Expected: PASS.

- [ ] **Step 5: Commit the policy layer**

```bash
git add src/main/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicy.java src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java
git commit -m "fix(sync): 增加主线窗口已有节点命中策略"
```

Expected: one commit containing only the policy helper and its tests.

## Task 3: Add Failing `ReadBoard` Decision Tests For Existing-Node Navigation

**Files:**
- Modify: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`

- [ ] **Step 1: Add the failing live-room forward-one-node test**

```java
@Test
void foxLiveForwardToExistingNextNodeNavigatesInsteadOfStalling() throws Exception {
  try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
    HistoryPath path =
        buildHistory(
            harness.board,
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(0, 1, Stone.BLACK));
    BoardHistoryNode moveOne = path.nodes.get(0);
    BoardHistoryNode moveTwo = path.nodes.get(1);
    BoardHistoryNode mainEnd = path.nodes.get(2);
    harness.board.getHistory().setHead(moveOne);

    harness.readBoard.parseLine("syncPlatform fox");
    harness.readBoard.parseLine("roomToken 43581号");
    harness.readBoard.parseLine("liveTitleMove 2");
    harness.readBoard.parseLine("foxMoveNumber 2");
    harness.sync(snapshot(moveTwo.getData().stones, moveTwo.getData().lastMove, moveTwo.getData().lastMoveColor));

    assertSame(mainEnd, harness.board.getHistory().getMainEnd());
    assertSame(moveTwo, harness.board.getHistory().getCurrentHistoryNode());
    assertEquals(0, harness.leelaz.clearCount);
  }
}
```

- [ ] **Step 2: Add the failing live-room forward-later-node test**

```java
@Test
void foxLiveForwardToExistingLaterMainlineNodeNavigatesInsteadOfRebuilding() throws Exception {
  try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
    HistoryPath path =
        buildHistory(
            harness.board,
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(0, 1, Stone.BLACK),
            placement(1, 1, Stone.WHITE));
    BoardHistoryNode moveOne = path.nodes.get(0);
    BoardHistoryNode moveThree = path.nodes.get(2);
    BoardHistoryNode mainEnd = path.nodes.get(3);
    harness.board.getHistory().setHead(moveOne);

    harness.readBoard.parseLine("syncPlatform fox");
    harness.readBoard.parseLine("roomToken 43581号");
    harness.readBoard.parseLine("liveTitleMove 3");
    harness.readBoard.parseLine("foxMoveNumber 3");
    harness.sync(
        snapshot(
            moveThree.getData().stones,
            moveThree.getData().lastMove,
            moveThree.getData().lastMoveColor));

    assertSame(mainEnd, harness.board.getHistory().getMainEnd());
    assertSame(moveThree, harness.board.getHistory().getCurrentHistoryNode());
    assertEquals(0, harness.leelaz.clearCount);
  }
}
```

- [ ] **Step 3: Add the failing record-view equivalent**

```java
@Test
void recordViewForwardToExistingNodeNavigatesInsideMainlineWindow() throws Exception {
  try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
    HistoryPath path =
        buildHistory(
            harness.board,
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(0, 1, Stone.BLACK));
    BoardHistoryNode moveOne = path.nodes.get(0);
    BoardHistoryNode moveTwo = path.nodes.get(1);
    BoardHistoryNode mainEnd = path.nodes.get(2);
    harness.board.getHistory().setHead(moveOne);

    harness.readBoard.parseLine("syncPlatform fox");
    harness.readBoard.parseLine("recordCurrentMove 2");
    harness.readBoard.parseLine("recordTotalMove 333");
    harness.readBoard.parseLine("recordAtEnd 0");
    harness.readBoard.parseLine("recordTitleFingerprint record-fingerprint");
    harness.readBoard.parseLine("foxMoveNumber 2");
    harness.sync(snapshot(moveTwo.getData().stones, moveTwo.getData().lastMove, moveTwo.getData().lastMoveColor));

    assertSame(mainEnd, harness.board.getHistory().getMainEnd());
    assertSame(moveTwo, harness.board.getHistory().getCurrentHistoryNode());
    assertEquals(0, harness.leelaz.clearCount);
  }
}
```

- [ ] **Step 4: Add the failing rollback-within-window test**

```java
@Test
void foxLiveRollbackWithinWindowNavigatesToAncestorWithoutRebuild() throws Exception {
  try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
    HistoryPath path =
        buildHistory(
            harness.board,
            placement(0, 0, Stone.BLACK),
            placement(1, 0, Stone.WHITE),
            placement(0, 1, Stone.BLACK),
            placement(1, 1, Stone.WHITE));
    BoardHistoryNode moveTwo = path.nodes.get(1);
    BoardHistoryNode mainEnd = path.nodes.get(3);
    harness.board.getHistory().setHead(mainEnd);

    harness.readBoard.parseLine("syncPlatform fox");
    harness.readBoard.parseLine("roomToken 43581号");
    harness.readBoard.parseLine("liveTitleMove 2");
    harness.readBoard.parseLine("foxMoveNumber 2");
    harness.sync(snapshot(moveTwo.getData().stones, moveTwo.getData().lastMove, moveTwo.getData().lastMoveColor));

    assertSame(mainEnd, harness.board.getHistory().getMainEnd());
    assertSame(moveTwo, harness.board.getHistory().getCurrentHistoryNode());
    assertEquals(0, harness.leelaz.clearCount);
  }
}
```

- [ ] **Step 5: Run the decision test class and verify the new tests fail**

Run:

```bash
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardSyncDecisionTest -DfailIfNoTests=false surefire:test
```

Expected: FAIL because `ReadBoard` still cannot distinguish “stay here” from “navigate to an already-existing node”.

- [ ] **Step 6: Commit only after the class is green**

```bash
git add src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java src/main/java/featurecat/lizzie/analysis/ReadBoard.java
git commit -m "test(readboard): 补充主线窗口已有节点导航回归用例"
```

Expected: skip this step until Task 4 is complete and the class is green.

## Task 4: Refactor `ReadBoard` To Use A Decision Object And Window Hits

**Files:**
- Modify: `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`

- [ ] **Step 1: Replace the bare enum with an internal decision object**

```java
private enum CompleteSnapshotRecoveryOutcome {
  NO_CHANGE,
  SINGLE_MOVE_RECOVERY,
  HOLD,
  FORCE_REBUILD
}

private static final class CompleteSnapshotRecoveryDecision {
  private final CompleteSnapshotRecoveryOutcome outcome;
  private final BoardHistoryNode resolvedNode;

  private CompleteSnapshotRecoveryDecision(
      CompleteSnapshotRecoveryOutcome outcome, BoardHistoryNode resolvedNode) {
    this.outcome = outcome;
    this.resolvedNode = resolvedNode;
  }

  private static CompleteSnapshotRecoveryDecision stay(BoardHistoryNode node) {
    return new CompleteSnapshotRecoveryDecision(CompleteSnapshotRecoveryOutcome.NO_CHANGE, node);
  }

  private static CompleteSnapshotRecoveryDecision recoverOneStep() {
    return new CompleteSnapshotRecoveryDecision(
        CompleteSnapshotRecoveryOutcome.SINGLE_MOVE_RECOVERY, null);
  }

  private static CompleteSnapshotRecoveryDecision hold() {
    return new CompleteSnapshotRecoveryDecision(CompleteSnapshotRecoveryOutcome.HOLD, null);
  }

  private static CompleteSnapshotRecoveryDecision rebuild() {
    return new CompleteSnapshotRecoveryDecision(CompleteSnapshotRecoveryOutcome.FORCE_REBUILD, null);
  }
}
```

- [ ] **Step 2: Add a tiny helper that tries the mainline window before fallback layers**

```java
private Optional<BoardHistoryNode> findExistingNodeInMainlineWindow(
    BoardHistoryNode currentNode,
    BoardHistoryNode syncStartNode,
    int[] snapshotCodes,
    SyncRemoteContext remoteContext) {
  return rebuildPolicy().findMatchingNodeInMainlineWindow(
      currentNode, syncStartNode, snapshotCodes, remoteContext);
}
```

- [ ] **Step 3: Rewrite `resolveCompleteSnapshotRecovery(...)` around the new decision flow**

```java
private CompleteSnapshotRecoveryDecision resolveCompleteSnapshotRecovery(
    BoardHistoryNode syncStartNode,
    BoardHistoryNode currentNode,
    Stone[] syncStartStones,
    int[] snapshotCodes,
    SyncSnapshotClassifier.SnapshotDelta snapshotDelta) {
  SyncRemoteContext remoteContext = currentPendingRemoteContext();
  if (remoteContext.forceRebuild || shouldForceRebuildOnResumeConflict(remoteContext)) {
    return CompleteSnapshotRecoveryDecision.rebuild();
  }

  Optional<BoardHistoryNode> windowMatch =
      findExistingNodeInMainlineWindow(currentNode, syncStartNode, snapshotCodes, remoteContext);
  if (windowMatch.isPresent()) {
    return CompleteSnapshotRecoveryDecision.stay(windowMatch.get());
  }

  Optional<BoardHistoryNode> adjacentMatch =
      rebuildPolicy().findAdjacentMatchFromLastResolvedNode(resumeState, snapshotCodes, remoteContext);
  if (adjacentMatch.isPresent()) {
    return CompleteSnapshotRecoveryDecision.stay(adjacentMatch.get());
  }

  if (snapshotDelta.hasMarker() && tryApplySingleMoveRecovery(syncStartNode, syncStartStones, snapshotCodes)) {
    return CompleteSnapshotRecoveryDecision.recoverOneStep();
  }
  if (shouldForceRebuildWithoutWaiting(syncStartNode, remoteContext)) {
    return CompleteSnapshotRecoveryDecision.rebuild();
  }
  if (shouldHoldConflictingSnapshot(syncStartNode, snapshotCodes, remoteContext)) {
    return CompleteSnapshotRecoveryDecision.hold();
  }
  return CompleteSnapshotRecoveryDecision.rebuild();
}
```

- [ ] **Step 4: Apply navigation at the call site instead of treating every hit as “do nothing”**

```java
CompleteSnapshotRecoveryDecision recovery =
    resolveCompleteSnapshotRecovery(node2, node, syncStartStones, currentSnapshotCodes, snapshotDelta);
if (recovery.outcome == CompleteSnapshotRecoveryOutcome.HOLD) {
  return;
}
if (recovery.outcome == CompleteSnapshotRecoveryOutcome.FORCE_REBUILD) {
  rebuildFromSnapshot(node2, currentSnapshotCodes, snapshotDelta, currentFoxMoveNumber);
  return;
}
if (recovery.outcome == CompleteSnapshotRecoveryOutcome.NO_CHANGE && recovery.resolvedNode != null) {
  if (recovery.resolvedNode != Lizzie.board.getHistory().getCurrentHistoryNode()) {
    moveToAnyPositionWithoutTracking(recovery.resolvedNode);
  }
  rememberResolvedSnapshotNode(recovery.resolvedNode);
  scheduleResumeAnalysisAfterSync(recovery.resolvedNode);
}
```

- [ ] **Step 5: Keep the one critical guardrail explicit**

```text
Do not search:
- variation nodes
- non-mainline siblings
- arbitrary historical duplicates

Only:
- current node
- current-node ancestors
- current node -> mainEnd main-trunk forward path
```

- [ ] **Step 6: Run the decision test class and verify it passes**

Run:

```bash
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardSyncDecisionTest -DfailIfNoTests=false surefire:test
```

Expected: PASS.

- [ ] **Step 7: Commit the `ReadBoard` layer**

```bash
git add src/main/java/featurecat/lizzie/analysis/ReadBoard.java src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java
git commit -m "fix(readboard): 复用主线窗口已有节点同步"
```

Expected: one commit containing the decision-object refactor and the green tests.

## Task 5: Update Branch Contract Docs To Match The New Rule

**Files:**
- Modify: `docs/SNAPSHOT_NODE_KIND.md`
- Modify: `docs/superpowers/specs/2026-04-21-readboard-sync-boundaries-design.md`

- [ ] **Step 1: Update the branch contract with the new navigation rule**

```md
- 命中当前保留主线窗口内的已有节点时，直接导航到该节点。
- 这类命中仍属于已有历史导航，不生成新 `MOVE/PASS`，也不触发 `FORCE_REBUILD`。
- 当前保留主线窗口只包含当前节点、其主线祖先链，以及当前节点到 `mainEnd` 的现有主线节点；variation 与窗口外旧历史不参与命中。
```

- [ ] **Step 2: Update the detailed decision matrix doc**

```md
3.5 当前保留主线窗口：
- 当前显示节点
- 当前显示节点的主线祖先链
- 当前显示节点到当前 `mainEnd` 的现有主线节点链

LIVE_ROOM / RECORD_VIEW 决策补充：
- 若远端目标已是当前保留主线窗口内的现有节点，则直接导航到该节点。
- 这类导航不生成新历史，不放宽真实历史恢复边界。
- variation 不参与窗口命中。
```

- [ ] **Step 3: Review both docs for contradictions against the approved 2026-04-22 spec**

```text
Check:
- no wording implies whole-tree history salvage
- no wording weakens non-Fox conservative mode
- no wording changes external outcomes beyond internal NO_CHANGE refinement
```

- [ ] **Step 4: Commit the docs update**

```bash
git add docs/SNAPSHOT_NODE_KIND.md docs/superpowers/specs/2026-04-21-readboard-sync-boundaries-design.md
git commit -m "docs(sync): 补充主线窗口已有节点直达规则"
```

Expected: one docs-only commit.

## Task 6: Run Focused Regression Verification Before Claiming Completion

**Files:**
- Modify: none
- Test: `src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardEngineResumeTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardResumeLifecycleTest.java`

- [ ] **Step 1: Recompile after all code changes**

Run:

```bash
mvn -q compiler:compile compiler:testCompile
```

Expected: exit code `0`.

- [ ] **Step 2: Run the policy regression class**

Run:

```bash
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.SyncSnapshotRebuildPolicyTest -DfailIfNoTests=false surefire:test
```

Expected: PASS.

- [ ] **Step 3: Run the main decision regression class**

Run:

```bash
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardSyncDecisionTest -DfailIfNoTests=false surefire:test
```

Expected: PASS.

- [ ] **Step 4: Run the two neighboring safety-net classes**

Run:

```bash
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardEngineResumeTest -DfailIfNoTests=false surefire:test
timeout 60s mvn -q -Dtest=featurecat.lizzie.analysis.ReadBoardResumeLifecycleTest -DfailIfNoTests=false surefire:test
```

Expected: PASS.

- [ ] **Step 5: Record the manual verification focus for the next real-world pass**

```text
Manual checks after build:
1. LIVE_ROOM rollback to ancestor, then forward one existing step
2. LIVE_ROOM rollback several existing steps inside the same mainline window
3. RECORD_VIEW rollback / forward inside the same kifu window
4. Jump outside the retained window still rebuilds
```

- [ ] **Step 6: Do not claim success without the command outputs**

```text
Only report completion after the four targeted test commands above have fresh exit code 0.
```

## Plan Self-Review

- Spec coverage:
  - Existing-node direct navigation inside the current mainline window: covered by Tasks 1-4.
  - No variation / no whole-tree salvage: covered by Tasks 1, 2, and 5.
  - Docs alignment: covered by Task 5.
  - Focused regression verification: covered by Task 6.
- Placeholder scan:
  - No `TODO`, `TBD`, or “similar to above” shortcuts remain.
- Type consistency:
  - The plan uses `findMatchingNodeInMainlineWindow(...)`, `CompleteSnapshotRecoveryDecision`, and `CompleteSnapshotRecoveryOutcome` consistently across implementation and tests.
