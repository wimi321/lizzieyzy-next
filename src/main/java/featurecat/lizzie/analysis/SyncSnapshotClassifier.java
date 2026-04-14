package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.Stone;
import java.util.Optional;

final class SyncSnapshotClassifier {
  private static final int[][] NEIGHBORS = new int[][] {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

  private final int boardWidth;
  private final int boardHeight;
  private final int boardArea;

  SyncSnapshotClassifier(int boardWidth, int boardHeight) {
    this.boardWidth = boardWidth;
    this.boardHeight = boardHeight;
    this.boardArea = boardWidth * boardHeight;
  }

  Optional<SingleMove> findSingleMoveCapture(Stone[] currentStones, int[] snapshot) {
    if (currentStones.length != boardArea || snapshot.length != boardArea) {
      return Optional.empty();
    }
    Delta delta = analyzeDelta(currentStones, snapshot);
    if (!delta.isSingleMove()) {
      return Optional.empty();
    }
    SingleMove move = new SingleMove(delta.moveX, delta.moveY, delta.moveColor);
    Stone[] simulated = simulateMove(currentStones, move);
    if (simulated == null || !matchesSnapshot(simulated, snapshot)) {
      return Optional.empty();
    }
    return Optional.of(move);
  }

  private Delta analyzeDelta(Stone[] currentStones, int[] snapshot) {
    Delta delta = new Delta();
    for (int snapshotIndex = 0; snapshotIndex < boardArea; snapshotIndex++) {
      int x = snapshotIndex % boardWidth;
      int y = snapshotIndex / boardWidth;
      int currentValue = normalize(currentStones[stoneIndex(x, y)]);
      int snapshotValue = normalize(snapshot[snapshotIndex]);
      if (currentValue == snapshotValue) {
        continue;
      }
      if (currentValue == 0 && snapshotValue > 0) {
        delta.recordAddition(x, y, snapshotValue == 1 ? Stone.BLACK : Stone.WHITE);
        continue;
      }
      if (snapshotValue == 0 && currentValue > 0) {
        delta.recordRemoval(currentValue == 1 ? Stone.BLACK : Stone.WHITE);
        continue;
      }
      delta.invalidate();
      return delta;
    }
    return delta;
  }

  private Stone[] simulateMove(Stone[] currentStones, SingleMove move) {
    int moveIndex = stoneIndex(move.x, move.y);
    if (!currentStones[moveIndex].isEmpty()) {
      return null;
    }
    Stone[] simulated = currentStones.clone();
    simulated[moveIndex] = move.color;
    for (int[] neighbor : NEIGHBORS) {
      captureDeadNeighbor(
          simulated, move.x + neighbor[0], move.y + neighbor[1], move.color.opposite());
    }
    if (!hasLiberty(simulated, move.x, move.y, move.color, new boolean[boardArea])) {
      return null;
    }
    return simulated;
  }

  private void captureDeadNeighbor(Stone[] stones, int x, int y, Stone color) {
    if (!isOnBoard(x, y) || !sameColor(stones[stoneIndex(x, y)], color)) {
      return;
    }
    if (hasLiberty(stones, x, y, color, new boolean[boardArea])) {
      return;
    }
    removeGroup(stones, x, y, color, new boolean[boardArea]);
  }

  private boolean hasLiberty(Stone[] stones, int x, int y, Stone color, boolean[] visited) {
    int index = stoneIndex(x, y);
    if (visited[index]) {
      return false;
    }
    visited[index] = true;
    for (int[] neighbor : NEIGHBORS) {
      int nextX = x + neighbor[0];
      int nextY = y + neighbor[1];
      if (!isOnBoard(nextX, nextY)) {
        continue;
      }
      Stone nextStone = stones[stoneIndex(nextX, nextY)];
      if (nextStone.isEmpty()) {
        return true;
      }
      if (sameColor(nextStone, color) && hasLiberty(stones, nextX, nextY, color, visited)) {
        return true;
      }
    }
    return false;
  }

  private void removeGroup(Stone[] stones, int x, int y, Stone color, boolean[] visited) {
    int index = stoneIndex(x, y);
    if (visited[index] || !sameColor(stones[index], color)) {
      return;
    }
    visited[index] = true;
    stones[index] = Stone.EMPTY;
    for (int[] neighbor : NEIGHBORS) {
      int nextX = x + neighbor[0];
      int nextY = y + neighbor[1];
      if (isOnBoard(nextX, nextY)) {
        removeGroup(stones, nextX, nextY, color, visited);
      }
    }
  }

  private boolean matchesSnapshot(Stone[] stones, int[] snapshot) {
    for (int snapshotIndex = 0; snapshotIndex < boardArea; snapshotIndex++) {
      int x = snapshotIndex % boardWidth;
      int y = snapshotIndex / boardWidth;
      if (normalize(stones[stoneIndex(x, y)]) != normalize(snapshot[snapshotIndex])) {
        return false;
      }
    }
    return true;
  }

  private boolean sameColor(Stone left, Stone right) {
    return normalize(left) == normalize(right);
  }

  private int stoneIndex(int x, int y) {
    return x * boardHeight + y;
  }

  private boolean isOnBoard(int x, int y) {
    return x >= 0 && x < boardWidth && y >= 0 && y < boardHeight;
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

  private int normalize(int value) {
    if (value == 1 || value == 3) {
      return 1;
    }
    if (value == 2 || value == 4) {
      return 2;
    }
    return 0;
  }

  static final class SingleMove {
    final int x;
    final int y;
    final Stone color;

    SingleMove(int x, int y, Stone color) {
      this.x = x;
      this.y = y;
      this.color = color;
    }
  }

  private static final class Delta {
    private int moveX = -1;
    private int moveY = -1;
    private Stone moveColor = Stone.EMPTY;
    private int removedBlack = 0;
    private int removedWhite = 0;
    private boolean valid = true;

    private void recordAddition(int x, int y, Stone color) {
      if (moveColor != Stone.EMPTY) {
        valid = false;
        return;
      }
      moveX = x;
      moveY = y;
      moveColor = color;
    }

    private void recordRemoval(Stone color) {
      if (color == Stone.BLACK) {
        removedBlack++;
      } else if (color == Stone.WHITE) {
        removedWhite++;
      }
    }

    private void invalidate() {
      valid = false;
    }

    private boolean isSingleMove() {
      if (!valid || moveColor == Stone.EMPTY) {
        return false;
      }
      if (moveColor == Stone.BLACK) {
        return removedBlack == 0;
      }
      return removedWhite == 0;
    }
  }
}
