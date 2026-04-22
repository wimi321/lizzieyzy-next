package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

public class SyncSnapshotRebuildPolicyTest {
  private static final int BOARD_WIDTH = 3;
  private static final int BOARD_AREA = BOARD_WIDTH * BOARD_WIDTH;

  @Test
  void matchesCurrentNodeWhenMarkedSnapshotMatchesSyncEnd() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
    BoardHistoryNode previousNode =
        root.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK)), new int[] {1, 1}, Stone.BLACK, false, 1));
    BoardHistoryNode currentNode =
        previousNode.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK), placement(0, 0, Stone.WHITE)),
                new int[] {0, 0},
                Stone.WHITE,
                true,
                2));

    Optional<BoardHistoryNode> matchedNode =
        policy.findMatchingHistoryNode(
            currentNode,
            snapshot(currentNode.getData().stones, Optional.of(new int[] {0, 0}), 4),
            OptionalInt.empty());

    assertTrue(matchedNode.isPresent());
    assertSame(currentNode, matchedNode.get());
  }

  @Test
  void matchesCurrentSnapshotNodeWhenMarkedSnapshotMatchesRebuiltRoot() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    Stone[] currentStones = stones(placement(1, 1, Stone.BLACK), placement(0, 0, Stone.WHITE));
    BoardHistoryNode currentNode =
        createNode(currentStones, Optional.of(new int[] {0, 0}), Stone.WHITE);

    Optional<BoardHistoryNode> matchedNode =
        policy.findMatchingHistoryNode(
            currentNode,
            snapshot(currentStones, Optional.of(new int[] {0, 0}), 4),
            OptionalInt.empty());

    assertTrue(matchedNode.isPresent());
    assertSame(currentNode, matchedNode.get());
  }

  @Test
  void doesNotMatchAncestorWhenMarkedSnapshotOnlyMatchesEarlierHistory() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
    BoardHistoryNode previousNode =
        root.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK)), new int[] {1, 1}, Stone.BLACK, false, 1));
    BoardHistoryNode currentNode =
        previousNode.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK), placement(0, 0, Stone.WHITE)),
                new int[] {0, 0},
                Stone.WHITE,
                true,
                2));

    Optional<BoardHistoryNode> matchedNode =
        policy.findMatchingHistoryNode(
            currentNode,
            snapshot(previousNode.getData().stones, Optional.of(new int[] {1, 1}), 3),
            OptionalInt.empty());

    assertFalse(matchedNode.isPresent());
  }

  @Test
  void doesNotMatchTransientIntermediateSnapshot() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
    BoardHistoryNode previousNode =
        root.add(
            createMoveHistoryNode(
                stones(
                    placement(1, 1, Stone.BLACK),
                    placement(0, 1, Stone.WHITE),
                    placement(1, 0, Stone.WHITE),
                    placement(2, 1, Stone.WHITE)),
                new int[] {2, 1},
                Stone.WHITE,
                true,
                4));
    BoardHistoryNode currentNode =
        previousNode.add(
            createMoveHistoryNode(
                stones(
                    placement(0, 1, Stone.WHITE),
                    placement(1, 0, Stone.WHITE),
                    placement(2, 1, Stone.WHITE),
                    placement(1, 2, Stone.WHITE)),
                new int[] {1, 2},
                Stone.WHITE,
                false,
                5));

    Optional<BoardHistoryNode> matchedNode =
        policy.findMatchingHistoryNode(
            currentNode,
            snapshot(
                stones(
                    placement(0, 1, Stone.WHITE),
                    placement(1, 0, Stone.WHITE),
                    placement(2, 1, Stone.WHITE)),
                Optional.empty(),
                0),
            OptionalInt.empty());

    assertFalse(matchedNode.isPresent());
  }

  @Test
  void returnsEmptyWhenRoomSnapshotHasNoCurrentNodeMatch() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    BoardHistoryNode currentNode = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);

    Optional<BoardHistoryNode> matchedNode =
        policy.findMatchingHistoryNode(
            currentNode,
            snapshot(
                stones(placement(0, 0, Stone.BLACK), placement(2, 2, Stone.WHITE)),
                Optional.empty(),
                0),
            OptionalInt.empty());

    assertFalse(matchedNode.isPresent());
  }

  @Test
  void returnsEmptyWhenSnapshotContainsMultipleMarkers() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
    BoardHistoryNode currentNode =
        root.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK)), new int[] {1, 1}, Stone.BLACK, false, 1));
    int[] snapshot = snapshot(currentNode.getData().stones, Optional.of(new int[] {1, 1}), 3);
    snapshot[0] = 4;

    Optional<BoardHistoryNode> matchedNode =
        policy.findMatchingHistoryNode(currentNode, snapshot, OptionalInt.empty());

    assertFalse(matchedNode.isPresent());
  }

  @Test
  void returnsEmptyWhenSnapshotMarkerColorDoesNotMatchCurrentNode() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
    BoardHistoryNode currentNode =
        root.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK)), new int[] {1, 1}, Stone.BLACK, false, 1));

    Optional<BoardHistoryNode> matchedNode =
        policy.findMatchingHistoryNode(
            currentNode,
            snapshot(currentNode.getData().stones, Optional.of(new int[] {1, 1}), 4),
            OptionalInt.empty());

    assertFalse(matchedNode.isPresent());
  }

  @Test
  void foxLiveAncestorMatchDoesNotRequireMarkerAgreement() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
    BoardHistoryNode ancestorNode =
        root.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK)), new int[] {1, 1}, Stone.BLACK, false, 1));
    BoardHistoryNode currentNode =
        ancestorNode.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK), placement(0, 0, Stone.WHITE)),
                new int[] {0, 0},
                Stone.WHITE,
                true,
                2));

    Optional<BoardHistoryNode> matchedNode =
        policy.findMatchingHistoryNode(
            currentNode,
            snapshot(ancestorNode.getData().stones, Optional.of(new int[] {1, 1}), 4),
            SyncRemoteContext.forFoxLive(OptionalInt.of(1), "43581号", OptionalInt.of(1), false));

    assertTrue(matchedNode.isPresent());
    assertSame(ancestorNode, matchedNode.get());
  }

  @Test
  void foxLiveConflictKeyIgnoresMarkerColorJitter() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    Stone[] stones = stones(placement(1, 1, Stone.BLACK), placement(0, 0, Stone.WHITE));
    SyncRemoteContext remoteContext =
        SyncRemoteContext.forFoxLive(OptionalInt.of(58), "43581号", OptionalInt.of(58), false);

    String blackMarkerConflictKey =
        policy.buildConflictKey(snapshot(stones, Optional.of(new int[] {1, 1}), 3), remoteContext);
    String whiteMarkerConflictKey =
        policy.buildConflictKey(snapshot(stones, Optional.of(new int[] {1, 1}), 4), remoteContext);

    assertEquals(blackMarkerConflictKey, whiteMarkerConflictKey);
  }

  @Test
  void foxLiveConflictKeyIgnoresMarkerPresenceJitter() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    Stone[] stones = stones(placement(1, 1, Stone.WHITE), placement(0, 0, Stone.BLACK));
    SyncRemoteContext remoteContext =
        SyncRemoteContext.forFoxLive(OptionalInt.of(58), "43581号", OptionalInt.of(58), false);

    String markedConflictKey =
        policy.buildConflictKey(snapshot(stones, Optional.of(new int[] {1, 1}), 3), remoteContext);
    String markerlessConflictKey =
        policy.buildConflictKey(snapshot(stones, Optional.empty(), 0), remoteContext);

    assertEquals(markedConflictKey, markerlessConflictKey);
  }

  @Test
  void foxRecordAtEndUsesTotalMoveAsRecoveryMoveNumber() {
    SyncRemoteContext remoteContext =
        SyncRemoteContext.forFoxRecord(
            OptionalInt.of(333),
            OptionalInt.empty(),
            OptionalInt.of(333),
            true,
            "record-fingerprint",
            false);

    assertTrue(remoteContext.supportsFoxRecovery());
    assertTrue(remoteContext.recoveryMoveNumber().isPresent());
    assertEquals(333, remoteContext.recoveryMoveNumber().getAsInt());
  }

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
            snapshot(moveTwo.getData().stones, moveTwo.getData().lastMove, 4),
            foxLiveContext(2, "43581号"));

    assertTrue(matchedNode.isPresent());
    assertSame(moveTwo, matchedNode.get());
  }

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
        createMoveHistoryNode(
            stones(placement(0, 0, Stone.BLACK), placement(2, 2, Stone.WHITE)),
            new int[] {2, 2},
            Stone.WHITE,
            true,
            2);
    moveOne.variations.add(variationMoveTwo);

    Optional<BoardHistoryNode> matchedNode =
        policy.findMatchingNodeInMainlineWindow(
            moveOne,
            mainlineMoveTwo,
            snapshot(variationMoveTwo.getData().stones, variationMoveTwo.getData().lastMove, 4),
            foxLiveContext(2, "43581号"));

    assertFalse(matchedNode.isPresent());
  }

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
            snapshot(moveTwo.getData().stones, moveTwo.getData().lastMove, 4),
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

  @Test
  void rebuildsImmediatelyWhenCurrentPositionHasNoHistory() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    BoardHistoryNode currentNode = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);

    assertTrue(policy.shouldRebuildImmediatelyWithoutHistory(currentNode));
  }

  @Test
  void doesNotRebuildImmediatelyWhenCurrentPositionHasHistory() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
    BoardHistoryNode currentNode =
        root.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK)), new int[] {1, 1}, Stone.BLACK, false, 1));

    assertFalse(policy.shouldRebuildImmediatelyWithoutHistory(currentNode));
  }

  @Test
  void matchesCurrentNodeWhenSnapshotOmitsLastMoveMarker() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    Stone[] currentStones = stones(placement(1, 1, Stone.WHITE));
    BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
    BoardHistoryNode currentNode =
        root.add(createMoveHistoryNode(currentStones, new int[] {1, 1}, Stone.WHITE, true, 1));

    Optional<BoardHistoryNode> matchedNode =
        policy.findMatchingHistoryNode(
            currentNode, snapshot(currentStones, Optional.empty(), 0), OptionalInt.empty());

    assertTrue(matchedNode.isPresent());
    assertSame(currentNode, matchedNode.get());
  }

  @Test
  void matchesCurrentPassNodeWhenMarkerlessSnapshotEqualsSamePosition() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    Stone[] repeatedStones = stones(placement(1, 1, Stone.BLACK));
    BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
    BoardHistoryNode moveOne =
        root.add(createMoveHistoryNode(repeatedStones, new int[] {1, 1}, Stone.BLACK, false, 1));
    BoardHistoryNode passNode =
        moveOne.add(createPassHistoryNode(repeatedStones, Stone.WHITE, true, 2));

    Optional<BoardHistoryNode> matchedNode =
        policy.findMatchingHistoryNode(
            passNode,
            snapshot(repeatedStones, Optional.empty(), 0),
            SyncRemoteContext.generic(false));

    assertTrue(matchedNode.isPresent());
    assertSame(passNode, matchedNode.get());
  }

  @Test
  void doesNotMatchEarlierNodeWhenMarkerlessSnapshotOnlyMatchesHistory() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    Stone[] repeatedStones = stones(placement(1, 1, Stone.BLACK));
    BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
    BoardHistoryNode moveOne =
        root.add(createMoveHistoryNode(repeatedStones, new int[] {1, 1}, Stone.BLACK, false, 1));
    BoardHistoryNode currentNode =
        moveOne.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK), placement(0, 0, Stone.WHITE)),
                new int[] {0, 0},
                Stone.WHITE,
                true,
                2));

    Optional<BoardHistoryNode> matchedNode =
        policy.findMatchingHistoryNode(
            currentNode, snapshot(repeatedStones, Optional.empty(), 0), OptionalInt.empty());

    assertFalse(matchedNode.isPresent());
  }

  @Test
  void foxLiveContextCanRecoverEarlierMainTrunkAncestor() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    Stone[] repeatedStones = stones(placement(1, 1, Stone.BLACK));
    BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
    BoardHistoryNode moveOne =
        root.add(createMoveHistoryNode(repeatedStones, new int[] {1, 1}, Stone.BLACK, false, 1));
    BoardHistoryNode currentNode =
        moveOne.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK), placement(0, 0, Stone.BLACK)),
                new int[] {0, 0},
                Stone.BLACK,
                false,
                3));

    Optional<BoardHistoryNode> matchedNode =
        policy.findMatchingHistoryNode(
            currentNode,
            snapshot(repeatedStones, Optional.empty(), 0),
            foxLiveContext(1, "43581号"));

    assertTrue(matchedNode.isPresent());
    assertSame(moveOne, matchedNode.get());
  }

  @Test
  void foxContextWithoutMoveNumberDoesNotScanAncestors() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    Stone[] repeatedStones = stones(placement(1, 1, Stone.BLACK));
    BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
    BoardHistoryNode moveOne =
        root.add(createMoveHistoryNode(repeatedStones, new int[] {1, 1}, Stone.BLACK, false, 1));
    BoardHistoryNode currentNode =
        moveOne.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK), placement(0, 0, Stone.BLACK)),
                new int[] {0, 0},
                Stone.BLACK,
                false,
                3));

    Optional<BoardHistoryNode> matchedNode =
        policy.findMatchingHistoryNode(
            currentNode,
            snapshot(repeatedStones, Optional.empty(), 0),
            SyncRemoteContext.foxUnknown(false).withRoomToken("43581号"));

    assertFalse(matchedNode.isPresent());
  }

  @Test
  void resolvesAdjacentMatchFromLastResolvedNodeWhenSameRoomAndExactNextMoveMatches() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
    BoardHistoryNode moveOne =
        root.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK)), new int[] {1, 1}, Stone.BLACK, false, 1));
    BoardHistoryNode moveTwo =
        moveOne.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK), placement(0, 0, Stone.WHITE)),
                new int[] {0, 0},
                Stone.WHITE,
                true,
                2));

    Optional<BoardHistoryNode> matchedNode =
        policy.findAdjacentMatchFromLastResolvedNode(
            new SyncResumeState(moveOne, foxLiveContext(1, "43581号")),
            snapshot(moveTwo.getData().stones, Optional.empty(), 0),
            foxLiveContext(2, "43581号"));

    assertTrue(matchedNode.isPresent());
    assertSame(moveTwo, matchedNode.get());
  }

  @Test
  void doesNotResolveAdjacentMatchWhenRoomTokenChanges() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
    BoardHistoryNode moveOne =
        root.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK)), new int[] {1, 1}, Stone.BLACK, false, 1));
    BoardHistoryNode moveTwo =
        moveOne.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK), placement(0, 0, Stone.WHITE)),
                new int[] {0, 0},
                Stone.WHITE,
                true,
                2));

    Optional<BoardHistoryNode> matchedNode =
        policy.findAdjacentMatchFromLastResolvedNode(
            new SyncResumeState(moveOne, foxLiveContext(1, "43581号")),
            snapshot(moveTwo.getData().stones, Optional.empty(), 0),
            foxLiveContext(2, "55667号"));

    assertFalse(matchedNode.isPresent());
  }

  @Test
  void liveRoomContextDoesNotRecoverAncestorWhenTitleMoveConflictsWithFoxMoveNumber() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    Stone[] repeatedStones = stones(placement(1, 1, Stone.BLACK));
    BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
    BoardHistoryNode moveOne =
        root.add(createMoveHistoryNode(repeatedStones, new int[] {1, 1}, Stone.BLACK, false, 1));
    BoardHistoryNode currentNode =
        moveOne.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK), placement(0, 0, Stone.BLACK)),
                new int[] {0, 0},
                Stone.BLACK,
                false,
                3));

    Optional<BoardHistoryNode> matchedNode =
        policy.findMatchingHistoryNode(
            currentNode,
            snapshot(repeatedStones, Optional.empty(), 0),
            SyncRemoteContext.forFoxLive(OptionalInt.of(1), "43581号", OptionalInt.of(2), false));

    assertFalse(matchedNode.isPresent());
  }

  @Test
  void recordViewContextDoesNotRecoverAncestorWhenRecordCurrentMoveConflictsWithFoxMoveNumber() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    Stone[] repeatedStones = stones(placement(1, 1, Stone.BLACK));
    BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
    BoardHistoryNode moveOne =
        root.add(createMoveHistoryNode(repeatedStones, new int[] {1, 1}, Stone.BLACK, false, 1));
    BoardHistoryNode currentNode =
        moveOne.add(
            createMoveHistoryNode(
                stones(placement(1, 1, Stone.BLACK), placement(0, 0, Stone.BLACK)),
                new int[] {0, 0},
                Stone.BLACK,
                false,
                3));

    Optional<BoardHistoryNode> matchedNode =
        policy.findMatchingHistoryNode(
            currentNode,
            snapshot(repeatedStones, Optional.empty(), 0),
            SyncRemoteContext.forFoxRecord(
                OptionalInt.of(1),
                OptionalInt.of(2),
                OptionalInt.of(333),
                false,
                "record-fingerprint",
                false));

    assertFalse(matchedNode.isPresent());
  }

  private BoardHistoryNode createNode(
      Stone[] stones, Optional<int[]> lastMove, Stone lastMoveColor) {
    return new BoardHistoryNode(
        BoardData.snapshot(
            stones,
            lastMove,
            lastMoveColor,
            true,
            new Zobrist(),
            0,
            new int[BOARD_AREA],
            0,
            0,
            50,
            0));
  }

  private BoardData createMoveNode(
      Stone[] stones, int[] lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    return BoardData.move(
        stones,
        lastMove,
        lastMoveColor,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private BoardHistoryNode createMoveHistoryNode(
      Stone[] stones, int[] lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    return new BoardHistoryNode(
        createMoveNode(stones, lastMove, lastMoveColor, blackToPlay, moveNumber));
  }

  private BoardData createPassNode(
      Stone[] stones, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    return BoardData.pass(
        stones,
        lastMoveColor,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50,
        0);
  }

  private BoardHistoryNode createPassHistoryNode(
      Stone[] stones, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    return new BoardHistoryNode(createPassNode(stones, lastMoveColor, blackToPlay, moveNumber));
  }

  private Stone[] stones(Placement... placements) {
    Stone[] stones = emptyStones();
    for (Placement placement : placements) {
      stones[stoneIndex(placement.x, placement.y)] = placement.stone;
    }
    return stones;
  }

  private Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private int[] snapshot(Stone[] stones, Optional<int[]> marker, int markerCode) {
    int[] snapshot = new int[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      int x = index % BOARD_WIDTH;
      int y = index / BOARD_WIDTH;
      snapshot[index] = normalize(stones[stoneIndex(x, y)]);
    }
    marker.ifPresent(coords -> snapshot[coords[1] * BOARD_WIDTH + coords[0]] = markerCode);
    return snapshot;
  }

  private int normalize(Stone stone) {
    if (stone == Stone.BLACK || stone == Stone.BLACK_RECURSED) {
      return 1;
    }
    if (stone == Stone.WHITE || stone == Stone.WHITE_RECURSED) {
      return 2;
    }
    return 0;
  }

  private int stoneIndex(int x, int y) {
    return x * BOARD_WIDTH + y;
  }

  private Placement placement(int x, int y, Stone stone) {
    return new Placement(x, y, stone);
  }

  private SyncRemoteContext foxLiveContext(int moveNumber, String roomToken) {
    return SyncRemoteContext.forFoxLive(
        OptionalInt.of(moveNumber), roomToken, OptionalInt.of(moveNumber), false);
  }

  private static final class Placement {
    private final int x;
    private final int y;
    private final Stone stone;

    private Placement(int x, int y, Stone stone) {
      this.x = x;
      this.y = y;
      this.stone = stone;
    }
  }
}
