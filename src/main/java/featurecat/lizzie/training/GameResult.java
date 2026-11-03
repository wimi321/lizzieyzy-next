package featurecat.lizzie.training;

import featurecat.lizzie.rules.Stone;
import java.util.Locale;

/**
 * How a coaching game ended: a counted margin, a resignation, a draw, or a count that failed.
 *
 * @param blackWins true or false for a winner, null for a draw or an uncounted game
 * @param margin points the winner won by, 0 for a draw, NaN for a resignation or no count
 */
public record GameResult(Boolean blackWins, double margin, boolean byResignation) {
  public static GameResult resignation(boolean blackWins) {
    return new GameResult(blackWins, Double.NaN, true);
  }

  public static GameResult uncounted() {
    return new GameResult(null, Double.NaN, false);
  }

  /**
   * From KataGo's final score lead for Black, rounded to what the komi allows: with a half-point
   * komi every result is a half point, with a whole-point komi a draw is possible.
   */
  public static GameResult byScore(double blackLead, double komi) {
    return byScore(blackLead, komi, null);
  }

  /**
   * As {@link #byScore(double, double)}, under rules that give a drawn count to the player who
   * passed first, {@code firstPasser} (null under other rules). KataGo counts that rule as half a
   * point for the first passer, its button, which comes off again here: a draw then goes to the
   * first passer by no margin, and every other result keeps its whole-point margin.
   */
  public static GameResult byScore(double blackLead, double komi, Stone firstPasser) {
    if (!Double.isFinite(blackLead) || !Double.isFinite(komi)) {
      return uncounted();
    }
    double lead =
        firstPasser == null ? blackLead : blackLead - (firstPasser.isBlack() ? 0.5 : -0.5);
    double fraction = komi - Math.floor(komi);
    double rounded = Math.round(lead - fraction) + fraction;
    if (Math.abs(rounded) < 1.0e-9) {
      return new GameResult(firstPasser == null ? null : firstPasser.isBlack(), 0.0, false);
    }
    return new GameResult(rounded > 0, Math.abs(rounded), false);
  }

  public boolean isDraw() {
    return blackWins == null && margin == 0.0;
  }

  /** Whether the count was a draw that the rules gave to the player who passed first. */
  public boolean wonOnDraw() {
    return blackWins != null && margin == 0.0 && !byResignation;
  }

  public boolean isCounted() {
    return blackWins != null || isDraw();
  }

  /** The SGF RE value: B+3.5, W+R, 0 for a draw, empty when the game was not counted. */
  public String sgf() {
    if (isDraw()) {
      return "0";
    }
    if (blackWins == null) {
      return "";
    }
    // A draw given to the first passer is a win by no margin: "B+".
    return (blackWins ? "B+" : "W+") + (byResignation ? "R" : wonOnDraw() ? "" : marginText());
  }

  /** The margin without a trailing ".0": 3.5 or 4. */
  public String marginText() {
    if (!Double.isFinite(margin)) {
      return "";
    }
    return margin == Math.rint(margin)
        ? String.valueOf((long) margin)
        : String.format(Locale.ROOT, "%.1f", margin);
  }
}
