package featurecat.lizzie.gui;

import featurecat.lizzie.rules.Stone;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * The count window's verdict: who wins and by how much, or a draw. With a whole-point komi, such as
 * the 7 points of the 2027 Chinese rules, equal totals are a draw, not a win for Black, unless the
 * rules give a drawn count to the player who passed first ({@code drawWinner}).
 */
record CountVerdict(double blackTotal, double whiteTotal, Stone drawWinner) {
  enum Outcome {
    BLACK,
    WHITE,
    DRAW
  }

  CountVerdict(double blackTotal, double whiteTotal) {
    this(blackTotal, whiteTotal, null);
  }

  Outcome outcome() {
    double margin = blackTotal - whiteTotal;
    if (Math.abs(margin) < 1e-9) {
      if (drawWinner == null) return Outcome.DRAW;
      return drawWinner.isBlack() ? Outcome.BLACK : Outcome.WHITE;
    }
    return margin > 0 ? Outcome.BLACK : Outcome.WHITE;
  }

  /** "黑胜3.5目", "白胜2目", "和棋", or for a draw the first passer won, "黑胜（和棋，黑方最先放弃着手）". */
  String text(ResourceBundle strings) {
    Outcome outcome = outcome();
    if (outcome == Outcome.DRAW) return strings.getString("ScoreResult.draw");
    double margin = Math.abs(blackTotal - whiteTotal);
    if (margin < 1e-9) {
      return strings.getString(
          outcome == Outcome.BLACK ? "ScoreResult.blackWinsDraw" : "ScoreResult.whiteWinsDraw");
    }
    String amount =
        margin == Math.rint(margin)
            ? String.valueOf((long) margin)
            : String.format(Locale.ENGLISH, "%.1f", margin);
    return strings.getString(
            outcome == Outcome.BLACK ? "ScoreResult.blackWin" : "ScoreResult.whiteWin")
        + amount
        + strings.getString("ScoreResult.points");
  }
}
