package featurecat.lizzie.gui;

import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.BoardData;
import java.util.Locale;
import java.util.ResourceBundle;

/** Presentation of optional analysis fields; a reported zero remains a real value. */
final class AnalysisFieldDisplay {
  private AnalysisFieldDisplay() {}

  static String number(double value, boolean present) {
    return present && Double.isFinite(value) ? String.format(Locale.ENGLISH, "%.1f", value) : "--";
  }

  static int descending(double left, boolean leftPresent, double right, boolean rightPresent) {
    if (leftPresent != rightPresent) return leftPresent ? -1 : 1;
    return leftPresent ? Double.compare(right, left) : 0;
  }

  static boolean hasScore(BoardData data) {
    return data != null
        && data.isKataData
        && Double.isFinite(data.scoreMean)
        && (data.bestMoves.isEmpty() || data.bestMoves.get(0).hasScoreMean());
  }

  static String summary(
      ResourceBundle strings,
      BoardData data,
      boolean withKomi,
      boolean showSpread,
      boolean engineGame) {
    if (!hasScore(data) || data.bestMoves.isEmpty()) return "";
    MoveData best = data.bestMoves.get(0);
    double score = data.blackToPlay ? best.scoreMean : -best.scoreMean;
    String text =
        withKomi
            ? strings.getString("LizzieFrame.scoreLeadWithKomi")
                + number(score + data.getKomi(), true)
            : MoveStatusText.lead(strings, score);
    if (best.hasScoreStdev()) {
      if (showSpread) text += " (±" + number(data.scoreStdev, true) + ")";
      if (engineGame && !data.isSaiData)
        text +=
            " " + strings.getString("LizzieFrame.scoreStdev") + number(data.scoreStdev, true) + " ";
    }
    return text;
  }

  static String scoreCell(MoveData move, double displayedScore) {
    if (!move.hasScoreMean()) return "--";
    return move.isNextMove && move.order != 0 && move.hasBestScoreMean()
        ? CandidateTableText.valueWithDifference(
            displayedScore, move.scoreMean - move.bestScoreMean)
        : number(displayedScore, true);
  }
}
