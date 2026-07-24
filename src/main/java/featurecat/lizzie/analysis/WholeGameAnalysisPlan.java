package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Immutable main-line work plan for the two-stage whole-game analysis workflow. */
public final class WholeGameAnalysisPlan {
  public static final int DEFAULT_BASELINE_VISITS = 32;
  public static final int MINIMUM_DEEP_VISITS = 500;

  private final BoardHistoryNode root;
  private final List<BoardHistoryNode> positions;
  private final List<PositionFingerprint> positionFingerprints;
  private final int baselineVisits;
  private final int deepVisits;

  private WholeGameAnalysisPlan(
      BoardHistoryNode root, List<BoardHistoryNode> positions, int baselineVisits, int deepVisits) {
    this.root = root;
    this.positions = Collections.unmodifiableList(new ArrayList<BoardHistoryNode>(positions));
    List<PositionFingerprint> fingerprints = new ArrayList<PositionFingerprint>(positions.size());
    for (BoardHistoryNode position : positions) {
      fingerprints.add(PositionFingerprint.capture(position.getData()));
    }
    this.positionFingerprints = Collections.unmodifiableList(fingerprints);
    this.baselineVisits = baselineVisits;
    this.deepVisits = deepVisits;
  }

  public static WholeGameAnalysisPlan create(
      BoardHistoryNode root, int baselineVisits, int requestedDeepVisits) {
    if (root == null) {
      throw new IllegalArgumentException("A game root is required");
    }
    List<BoardHistoryNode> positions = new ArrayList<BoardHistoryNode>();
    positions.add(root);
    BoardHistoryNode node = root;
    while (node.next().isPresent()) {
      node = node.next().get();
      if (isRealHistoryAction(node.getData())) {
        positions.add(node);
      }
    }
    return new WholeGameAnalysisPlan(
        root,
        positions,
        Math.max(1, baselineVisits),
        Math.max(MINIMUM_DEEP_VISITS, requestedDeepVisits));
  }

  public BoardHistoryNode root() {
    return root;
  }

  public List<BoardHistoryNode> positions() {
    return positions;
  }

  public int positionCount() {
    return positions.size();
  }

  public int moveCount() {
    return Math.max(0, positions.size() - 1);
  }

  public int baselineVisits() {
    return baselineVisits;
  }

  public int deepVisits() {
    return deepVisits;
  }

  public List<BoardHistoryNode> positionsBelow(int targetVisits) {
    return positionsMissingAnalysis(targetVisits, false);
  }

  public List<BoardHistoryNode> positionsMissingAnalysis(
      int targetVisits, boolean ownershipRequested) {
    List<BoardHistoryNode> pending = new ArrayList<BoardHistoryNode>();
    for (BoardHistoryNode node : positions) {
      BoardData data = node.getData();
      if (data == null || !data.hasCompletePrimaryAnalysis(targetVisits, ownershipRequested)) {
        pending.add(node);
      }
    }
    return pending;
  }

  public int completedAtLeast(int targetVisits) {
    return completedAtLeast(targetVisits, false);
  }

  public int completedAtLeast(int targetVisits, boolean ownershipRequested) {
    return positionCount() - positionsMissingAnalysis(targetVisits, ownershipRequested).size();
  }

  public boolean stillMatches(BoardHistoryNode currentRoot) {
    if (root != currentRoot
        || !positionFingerprints.get(0).matches(currentRoot == null ? null : currentRoot.getData())) {
      return false;
    }
    int positionIndex = 1;
    BoardHistoryNode node = currentRoot;
    while (node.next().isPresent()) {
      node = node.next().get();
      if (!isRealHistoryAction(node.getData())) {
        continue;
      }
      if (positionIndex >= positions.size() || positions.get(positionIndex) != node) {
        return false;
      }
      if (!positionFingerprints.get(positionIndex).matches(node.getData())) {
        return false;
      }
      positionIndex++;
    }
    return positionIndex == positions.size();
  }

  private static boolean isRealHistoryAction(BoardData data) {
    return data != null && (data.isMoveNode() || (data.isPassNode() && !data.dummy));
  }

  static final class PositionFingerprint {
    private final Stone[] stones;
    private final Zobrist zobrist;
    private final boolean blackToPlay;
    private final boolean moveNode;
    private final boolean passNode;
    private final boolean snapshotNode;
    private final Stone lastMoveColor;
    private final int[] lastMove;

    private PositionFingerprint(BoardData data) {
      stones = data == null || data.stones == null ? null : data.stones.clone();
      zobrist = data == null || data.zobrist == null ? null : data.zobrist.clone();
      blackToPlay = data != null && data.blackToPlay;
      moveNode = data != null && data.isMoveNode();
      passNode = data != null && data.isPassNode();
      snapshotNode = data != null && data.isSnapshotNode();
      lastMoveColor = data == null ? null : data.lastMoveColor;
      lastMove =
          data == null || data.lastMove == null || data.lastMove.isEmpty()
              ? null
              : data.lastMove.get().clone();
    }

    static PositionFingerprint capture(BoardData data) {
      return new PositionFingerprint(data);
    }

    boolean matches(BoardData data) {
      if (data == null) {
        return false;
      }
      return Arrays.equals(stones, data.stones)
          && (zobrist == null ? data.zobrist == null : zobrist.equals(data.zobrist))
          && blackToPlay == data.blackToPlay
          && moveNode == data.isMoveNode()
          && passNode == data.isPassNode()
          && snapshotNode == data.isSnapshotNode()
          && lastMoveColor == data.lastMoveColor
          && Arrays.equals(
              lastMove,
              data.lastMove == null || data.lastMove.isEmpty()
                  ? null
                  : data.lastMove.get());
    }
  }
}
