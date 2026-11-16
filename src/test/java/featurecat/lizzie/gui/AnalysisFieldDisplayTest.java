package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.BoardData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class AnalysisFieldDisplayTest {
  private static final ResourceBundle EN =
      ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);

  @Test
  void missingScoreDoesNotShowAnEvenPositionOrKomiAsALead() {
    BoardData data = board("move D4 visits 20 winrate 0.6 pv D4");
    assertEquals("", AnalysisFieldDisplay.summary(EN, data, false, true, false));
    assertEquals("", AnalysisFieldDisplay.summary(EN, data, true, true, true));
    assertEquals("--", AnalysisFieldDisplay.scoreCell(data.bestMoves.get(0), 0));
  }

  @Test
  void missingSpreadIsOmittedButAReportedZeroSpreadRemainsVisible() {
    BoardData without = board("move D4 visits 20 scoreLead 2 pv D4");
    assertEquals(
        "Black leads by 2.0", AnalysisFieldDisplay.summary(EN, without, false, true, false));
    assertFalse(AnalysisFieldDisplay.summary(EN, without, false, true, true).contains("±"));
    BoardData zero = board("move D4 visits 20 scoreLead 0 scoreStdev 0 pv D4");
    assertTrue(AnalysisFieldDisplay.summary(EN, zero, false, true, false).endsWith(" (±0.0)"));
    assertEquals("0.0", AnalysisFieldDisplay.scoreCell(zero.bestMoves.get(0), 0));
  }

  @Test
  void reportedNegativeValuesSortAheadOfMissingValuesAndMissingValuesStayStable() {
    MoveData missing = MoveData.fromInfoKatago("move D4 visits 20 pv D4");
    MoveData negative = MoveData.fromInfoKatago("move Q16 visits 30 scoreLead -2 lcb -0.1 pv Q16");
    MoveData zero = MoveData.fromInfoKatago("move C3 visits 40 scoreLead 0 lcb 0 pv C3");
    List<MoveData> moves = new ArrayList<>(List.of(missing, negative, zero));
    moves.sort((a, b) -> AnalysisFieldDisplay.descending(a.lcb, a.hasLcb(), b.lcb, b.hasLcb()));
    assertEquals(List.of(zero, negative, missing), moves);
    assertEquals("--", AnalysisFieldDisplay.number(missing.lcb, missing.hasLcb()));
    assertEquals("0.0", AnalysisFieldDisplay.number(zero.lcb, zero.hasLcb()));
    assertEquals(0, AnalysisFieldDisplay.descending(0, false, 42, false));
  }

  @Test
  void playedMoveCannotInventADifferenceFromAnAbsentBestScore() {
    MoveData played = MoveData.fromInfoKatago("move D4 visits 20 scoreLead 2 pv D4");
    played.isNextMove = true;
    played.order = 1;
    played.setBestScoreMean(MoveData.fromInfoKatago("move Q16 visits 30 pv Q16"));
    assertEquals("2.0", AnalysisFieldDisplay.scoreCell(played, 2));
    played.setBestScoreMean(MoveData.fromInfoKatago("move Q16 visits 30 scoreLead 3 pv Q16"));
    assertEquals("2.0 ↓1.0", AnalysisFieldDisplay.scoreCell(played, 2));
  }

  private static BoardData board(String line) {
    MoveData move = MoveData.fromInfoKatago(line);
    BoardData data = BoardData.empty(19, 19);
    data.bestMoves = List.of(move);
    data.isKataData = move.isKataData;
    data.scoreMean = move.scoreMean;
    data.scoreStdev = move.scoreStdev;
    data.komi = 7.5;
    return data;
  }
}
