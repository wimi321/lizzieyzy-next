package featurecat.lizzie.gui;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * The count window's verdict: who wins and by how much, or a draw. With a whole-point komi, such as
 * the 7 points of the 2027 Chinese rules, equal totals are a draw, not a win for Black.
 */
record CountVerdict(double blackTotal, double whiteTotal) {
  enum Outcome {
    BLACK,
    WHITE,
    DRAW
  }

  Outcome outcome() {
    double margin = blackTotal - whiteTotal;
    if (Math.abs(margin) < 1e-9) return Outcome.DRAW;
    return margin > 0 ? Outcome.BLACK : Outcome.WHITE;
  }

  /** "黑胜3.5目", "白胜2目" or "和棋": the margin without a trailing ".0". */
  String text(ResourceBundle strings) {
    Outcome outcome = outcome();
    if (outcome == Outcome.DRAW) return strings.getString("ScoreResult.draw");
    double margin = Math.abs(blackTotal - whiteTotal);
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
