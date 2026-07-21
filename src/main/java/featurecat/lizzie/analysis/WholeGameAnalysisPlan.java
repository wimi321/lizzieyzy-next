package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable main-line work plan for the two-stage whole-game analysis workflow. */
public final class WholeGameAnalysisPlan {
  public static final int DEFAULT_BASELINE_VISITS = 32;
  public static final int MINIMUM_DEEP_VISITS = 500;

  private final BoardHistoryNode root;
  private final List<BoardHistoryNode> positions;
  private final int baselineVisits;
  private final int deepVisits;

  private WholeGameAnalysisPlan(
      BoardHistoryNode root, List<BoardHistoryNode> positions, int baselineVisits, int deepVisits) {
    this.root = root;
    this.positions = Collections.unmodifiableList(new ArrayList<BoardHistoryNode>(positions));
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
    List<BoardHistoryNode> pending = new ArrayList<BoardHistoryNode>();
    for (BoardHistoryNode node : positions) {
      BoardData data = node.getData();
      if (data != null && data.getPlayouts() < targetVisits) {
        pending.add(node);
      }
    }
    return pending;
  }

  public int completedAtLeast(int targetVisits) {
    return positionCount() - positionsBelow(targetVisits).size();
  }

  public boolean stillMatches(BoardHistoryNode currentRoot) {
    return root == currentRoot;
  }

  private static boolean isRealHistoryAction(BoardData data) {
    return data != null && (data.isMoveNode() || (data.isPassNode() && !data.dummy));
  }
}
