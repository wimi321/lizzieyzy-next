package featurecat.lizzie.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.rules.Stone;
import org.junit.jupiter.api.Test;

class GameResultTest {
  @Test
  void aHalfPointKomiGivesHalfPointResults() {
    assertEquals("B+3.5", GameResult.byScore(3.4, 7.5).sgf());
    assertEquals("B+3.5", GameResult.byScore(3.9, 7.5).sgf());
    assertEquals("W+0.5", GameResult.byScore(-0.2, 7.5).sgf());
    assertEquals("B+0.5", GameResult.byScore(0.2, 6.5).sgf());
    assertEquals("W+12.5", GameResult.byScore(-12.6, 0.5).sgf());
  }

  @Test
  void aWholePointKomiAllowsWholePointResultsAndDraws() {
    assertEquals("B+3", GameResult.byScore(3.2, 6.0).sgf());
    assertEquals("W+1", GameResult.byScore(-0.6, 0.0).sgf());
    GameResult draw = GameResult.byScore(0.3, 6.0);
    assertTrue(draw.isDraw());
    assertTrue(draw.isCounted());
    assertEquals("0", draw.sgf());
  }

  @Test
  void underTheFirstPassDrawRuleKataGosButtonHalfPointComesOffAndADrawGoesToTheFirstPasser() {
    // 2027 rules, komi 7: KataGo adds half a point for the first player to pass.
    GameResult drawToBlack = GameResult.byScore(0.4, 7.0, Stone.BLACK);
    assertFalse(drawToBlack.isDraw());
    assertTrue(drawToBlack.wonOnDraw());
    assertTrue(drawToBlack.isCounted());
    assertEquals(Boolean.TRUE, drawToBlack.blackWins());
    assertEquals("B+", drawToBlack.sgf());
    assertEquals("W+", GameResult.byScore(-0.6, 7.0, Stone.WHITE).sgf());

    assertEquals("B+3", GameResult.byScore(3.5, 7.0, Stone.BLACK).sgf());
    assertEquals("B+3", GameResult.byScore(2.5, 7.0, Stone.WHITE).sgf());
    assertEquals("W+2", GameResult.byScore(-2.5, 7.0, Stone.WHITE).sgf());
    assertFalse(GameResult.byScore(3.5, 7.0, Stone.BLACK).wonOnDraw());
    assertTrue(GameResult.byScore(0.3, 6.0, null).isDraw(), "other rules keep the draw");
  }

  @Test
  void resignationsRecordTheWinner() {
    assertEquals("B+R", GameResult.resignation(true).sgf());
    assertEquals("W+R", GameResult.resignation(false).sgf());
    assertTrue(GameResult.resignation(false).byResignation());
  }

  @Test
  void aFailedCountRecordsNothing() {
    GameResult result = GameResult.byScore(Double.NaN, 7.5);
    assertFalse(result.isCounted());
    assertFalse(result.isDraw());
    assertNull(result.blackWins());
    assertEquals("", result.sgf());
  }

  @Test
  void marginsReadWithoutATrailingZero() {
    assertEquals("4", GameResult.byScore(4.2, 6.0).marginText());
    assertEquals("3.5", GameResult.byScore(3.4, 7.5).marginText());
    assertEquals("", GameResult.resignation(true).marginText());
  }
}
