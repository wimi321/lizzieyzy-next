package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class SyncSnapshotRebuildPolicyTest {
  private static final int BOARD_WIDTH = 3;

  @Test
  void rebuildsImmediatelyWhenCurrentPositionHasNoHistory() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    BoardHistoryNode currentNode = createNode(Optional.empty(), Stone.EMPTY);

    assertTrue(policy.shouldRebuildImmediately(currentNode, new int[] {1, 0, 0, 0, 0, 0, 0, 0, 0}));
  }

  @Test
  void rebuildsImmediatelyWhenSnapshotRemovesCurrentLastMove() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    BoardHistoryNode root = createNode(Optional.empty(), Stone.EMPTY);
    BoardHistoryNode currentNode = createNode(Optional.of(new int[] {1, 1}), Stone.BLACK);
    root.add(currentNode);

    assertTrue(policy.shouldRebuildImmediately(currentNode, new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0}));
  }

  @Test
  void doesNotRebuildWhenSnapshotStillContainsCurrentLastMoveStone() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    BoardHistoryNode root = createNode(Optional.empty(), Stone.EMPTY);
    BoardHistoryNode currentNode = createNode(Optional.of(new int[] {1, 1}), Stone.BLACK);
    root.add(currentNode);

    assertFalse(
        policy.shouldRebuildImmediately(currentNode, new int[] {0, 0, 0, 0, 1, 0, 0, 0, 0}));
  }

  @Test
  void doesNotTreatMissingLastMoveMarkerAsRemoval() {
    SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
    BoardHistoryNode root = createNode(Optional.empty(), Stone.EMPTY);
    BoardHistoryNode currentNode = createNode(Optional.of(new int[] {1, 1}), Stone.WHITE);
    root.add(currentNode);

    assertFalse(
        policy.shouldRebuildImmediately(currentNode, new int[] {0, 0, 0, 0, 2, 0, 0, 0, 0}));
  }

  private BoardHistoryNode createNode(Optional<int[]> lastMove, Stone lastMoveColor) {
    return new BoardHistoryNode(
        new BoardData(
            new Stone[] {
              Stone.EMPTY, Stone.EMPTY, Stone.EMPTY,
              Stone.EMPTY, Stone.EMPTY, Stone.EMPTY,
              Stone.EMPTY, Stone.EMPTY, Stone.EMPTY
            },
            lastMove,
            lastMoveColor,
            true,
            new Zobrist(),
            0,
            new int[BOARD_WIDTH * BOARD_WIDTH],
            0,
            0,
            50,
            0));
  }
}
