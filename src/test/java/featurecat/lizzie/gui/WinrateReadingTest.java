package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import org.junit.jupiter.api.Test;

class WinrateReadingTest {
  @Test
  void aPositionsWinRateIsTheSideToMovesAndIsTurnedRoundForWhite() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(19, 19));
    analyse(history.getStart().getData(), 63);

    WinrateReading blackToMove = WinrateReading.of(history.getStart());
    assertEquals(63, blackToMove.blackPercent());
    assertEquals(37, blackToMove.whitePercent());
    assertTrue(blackToMove.current());

    history.add(move(3, 3, Stone.BLACK, 1));
    analyse(history.getCurrentHistoryNode().getData(), 63);
    assertEquals(37, WinrateReading.of(history.getCurrentHistoryNode()).blackPercent());
  }

  @Test
  void withoutAnalysisItShowsTheLastOfTheTwoPositionsBefore() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(19, 19));
    analyse(history.getStart().getData(), 70);
    history.add(move(3, 3, Stone.BLACK, 1));
    history.add(move(15, 15, Stone.WHITE, 2));

    WinrateReading twoBack = WinrateReading.of(history.getCurrentHistoryNode());
    assertEquals(70, twoBack.blackPercent());
    assertFalse(twoBack.current(), "an earlier position's figures are dimmed");

    history.add(move(15, 3, Stone.BLACK, 3));
    assertFalse(
        WinrateReading.of(history.getCurrentHistoryNode()).known(), "three back is too far");
  }

  @Test
  void theTwoFiguresAlwaysMakeAHundred() {
    WinrateReading half = new WinrateReading(62.5, true);
    assertEquals(63, half.blackPercent());
    assertEquals(37, half.whitePercent());
    assertFalse(WinrateReading.UNKNOWN.known());
  }

  private static void analyse(BoardData data, double sideToMoveWinrate) {
    data.winrate = sideToMoveWinrate;
    data.setPlayoutsForce(200);
  }

  private static BoardData move(int x, int y, Stone color, int moveNumber) {
    Stone[] stones = BoardData.empty(19, 19).stones;
    stones[x * 19 + y] = color;
    return BoardData.move(
        stones,
        new int[] {x, y},
        color,
        color == Stone.WHITE,
        new Zobrist(),
        moveNumber,
        new int[361],
        0,
        0,
        50,
        0);
  }
}
