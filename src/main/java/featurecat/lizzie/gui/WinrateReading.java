package featurecat.lizzie.gui;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;

/**
 * Black's win rate at a position, as the move statistics show it: the position's own analysis, or
 * while that is missing the last of the two positions before it that was analysed. A node's win
 * rate is the side to move's, so it is turned round for White.
 *
 * @param black Black's win rate from 0 to 100, NaN when no nearby position was analysed
 * @param current whether it comes from the position itself rather than an earlier one
 */
record WinrateReading(double black, boolean current) {
  static final WinrateReading UNKNOWN = new WinrateReading(Double.NaN, false);

  static WinrateReading of(BoardHistoryNode position) {
    BoardHistoryNode node = position;
    for (int back = 0; node != null && back <= 2; back++) {
      BoardData data = node.getData();
      if (data != null && data.getPlayouts() > 0 && Double.isFinite(data.winrate)) {
        double black = data.blackToPlay ? data.winrate : 100 - data.winrate;
        return new WinrateReading(Math.max(0, Math.min(100, black)), back == 0);
      }
      node = node.previous().orElse(null);
    }
    return UNKNOWN;
  }

  boolean known() {
    return Double.isFinite(black);
  }

  /** Black's win rate rounded to a whole percent; with {@link #whitePercent} it makes 100. */
  int blackPercent() {
    return (int) Math.round(black);
  }

  int whitePercent() {
    return 100 - blackPercent();
  }
}
