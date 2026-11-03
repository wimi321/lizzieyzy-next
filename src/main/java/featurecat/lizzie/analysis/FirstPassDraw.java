package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.util.Optional;

/**
 * The second way the 2027 Chinese rules break a draw, 平局时，最先放弃着手一方胜: a drawn count goes to the
 * player who passed first. KataGo plays it as its button rule, half a point to the first player to
 * pass, which with a whole-point komi decides only a draw.
 */
public final class FirstPassDraw {
  private FirstPassDraw() {}

  /** Whether these rules give a drawn count to the first player to pass. */
  public static boolean applies(KataGoRules rules) {
    return rules != null && rules.bool(KataGoRules.HAS_BUTTON);
  }

  /** Whether the rules KataGo reported ({@code = {...}}) give a drawn count to the first passer. */
  public static boolean applies(String rulesLine) {
    return applies(KataGoRules.parse(rulesLine).orElse(null));
  }

  /** Black or White, whoever passed first on the way to {@code node}; empty when nobody passed. */
  public static Optional<Stone> firstPasser(BoardHistoryNode node) {
    Stone first = null;
    for (BoardHistoryNode cursor = node; cursor != null; cursor = cursor.previous().orElse(null)) {
      BoardData data = cursor.getData();
      if (data != null && data.isPassNode() && data.lastMoveColor != null) {
        first = data.lastMoveColor;
      }
    }
    return Optional.ofNullable(first).filter(stone -> stone.isBlack() || stone.isWhite());
  }

  /** Who wins a drawn count at {@code node} under {@code rulesLine}: the first passer, if any. */
  public static Optional<Stone> drawWinner(String rulesLine, BoardHistoryNode node) {
    return applies(rulesLine) ? firstPasser(node) : Optional.empty();
  }
}
