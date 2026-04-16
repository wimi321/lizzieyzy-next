package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;

final class SyncSnapshotRebuildPolicy {
  private final int boardWidth;

  SyncSnapshotRebuildPolicy(int boardWidth) {
    this.boardWidth = boardWidth;
  }

  boolean shouldRebuildImmediately(BoardHistoryNode syncStartNode, int[] snapshotCodes) {
    if (syncStartNode == null || snapshotCodes.length == 0) {
      return false;
    }
    if (!syncStartNode.previous().isPresent()) {
      return true;
    }
    return removesCurrentLastMove(syncStartNode.getData(), snapshotCodes);
  }

  private boolean removesCurrentLastMove(BoardData syncStartData, int[] snapshotCodes) {
    if (!syncStartData.lastMove.isPresent()) {
      return false;
    }
    int[] coords = syncStartData.lastMove.get();
    int snapshotIndex = coords[1] * boardWidth + coords[0];
    if (snapshotIndex < 0 || snapshotIndex >= snapshotCodes.length) {
      return false;
    }
    return normalizeSnapshot(snapshotCodes[snapshotIndex])
        != normalizeStone(syncStartData.lastMoveColor);
  }

  private int normalizeSnapshot(int value) {
    if (value == 1 || value == 3) {
      return 1;
    }
    if (value == 2 || value == 4) {
      return 2;
    }
    return 0;
  }

  private int normalizeStone(Stone stone) {
    if (stone == Stone.BLACK || stone == Stone.BLACK_RECURSED) {
      return 1;
    }
    if (stone == Stone.WHITE || stone == Stone.WHITE_RECURSED) {
      return 2;
    }
    return 0;
  }
}
