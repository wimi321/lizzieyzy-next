package featurecat.lizzie.gui;

import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** EDT-owned record of this coaching session's withdrawn continuations. */
final class CoachTakenBackLine {
  private final Map<BoardHistoryNode, Continuations> retained = new IdentityHashMap<>();

  static CoachTakenBackLine capture(BoardHistoryNode live, int moves, CoachTakenBackLine previous) {
    CoachTakenBackLine line = previous == null ? new CoachTakenBackLine() : previous;
    BoardHistoryNode current = live;
    for (int i = 0; i < moves; i++) {
      BoardHistoryNode parent = current.previous().orElseThrow();
      line.remember(parent, current);
      current = parent;
    }
    return line;
  }

  boolean replayMatching(Board board, Stone color, int x, int y, boolean liveAnalysis) {
    BoardHistoryNode parent = board.getHistory().getCurrentHistoryNode();
    Continuations choices = retained.get(parent);
    if (choices == null) return false;
    for (BoardHistoryNode node : choices.moves) {
      int index = parent.indexOfNode(node);
      if (index >= 0 && matches(node.getData(), color, x, y)) {
        boolean[] moved = new boolean[1];
        Runnable navigate = () -> moved[0] = board.nextVariation(index);
        if (liveAnalysis) navigate.run();
        else board.applyReadBoardSyncLocally(navigate);
        return moved[0];
      }
    }
    return false;
  }

  private static boolean matches(BoardData move, Stone color, int x, int y) {
    return move.lastMoveColor == color
        && (x < 0
            ? move.isPassNode()
            : move.isMoveNode()
                && move.lastMove.map(point -> point[0] == x && point[1] == y).orElse(false));
  }

  void resumeWith(Board board, BoardHistoryNode before, BoardHistoryNode after) {
    if (after.previous().orElse(null) != before) return;
    Continuations choices = retained.get(before);
    int current = before.indexOfNode(after);
    if (choices != null && current >= 0 && current != choices.activeIndex) {
      // Keep the actual game in its former slot. Imported continuations before the training
      // branch stay ahead of it; only moves withdrawn in this session can be replayed here.
      while (current > choices.activeIndex) {
        after.moveUp();
        current--;
      }
      while (current < choices.activeIndex) {
        after.moveDown();
        current++;
      }
      board.clearNodeInfo(board.getHistory().getStart());
      board.setMovelistAll();
    }
    // A replayed older attempt may already have a whole continuation. Remember its next move
    // lazily so a different reply replaces that main slot without losing the older subtree.
    after.getVariation(0).ifPresent(next -> remember(after, next));
  }

  private void remember(BoardHistoryNode parent, BoardHistoryNode move) {
    Continuations choices = retained.computeIfAbsent(parent, ignored -> new Continuations());
    choices.activeIndex = parent.indexOfNode(move);
    if (!choices.moves.contains(move)) choices.moves.add(move);
  }

  private static final class Continuations {
    private final List<BoardHistoryNode> moves = new ArrayList<>();
    private int activeIndex;
  }
}
