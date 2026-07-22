package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WholeGameAnalysisPlanTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void planIncludesRootAndRealMainlineActionsButSkipsSnapshotsAndDummyPasses() {
    BoardHistoryNode root = new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    BoardHistoryNode move = root.add(new BoardHistoryNode(moveData(Stone.BLACK, 1, 0)));
    BoardHistoryNode snapshot =
        move.add(new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
    BoardData dummyPassData = passData(Stone.WHITE, 2, 0);
    dummyPassData.dummy = true;
    BoardHistoryNode dummyPass = snapshot.add(new BoardHistoryNode(dummyPassData));
    BoardHistoryNode realPass = dummyPass.add(new BoardHistoryNode(passData(Stone.WHITE, 2, 0)));
    BoardHistoryNode lastMove = realPass.add(new BoardHistoryNode(moveData(Stone.BLACK, 3, 0)));

    WholeGameAnalysisPlan plan = WholeGameAnalysisPlan.create(root, 32, 300);

    assertEquals(4, plan.positionCount());
    assertEquals(3, plan.moveCount());
    assertEquals(Arrays.asList(root, move, realPass, lastMove), plan.positions());
    assertEquals(WholeGameAnalysisPlan.MINIMUM_DEEP_VISITS, plan.deepVisits());
  }

  @Test
  void cachedPositionsAreCountedAndSkippedPerStageWithoutChangingThePlan() {
    BoardHistoryNode root = new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    BoardHistoryNode first = root.add(new BoardHistoryNode(moveData(Stone.BLACK, 1, 40)));
    BoardHistoryNode second = first.add(new BoardHistoryNode(moveData(Stone.WHITE, 2, 700)));

    WholeGameAnalysisPlan plan = WholeGameAnalysisPlan.create(root, 32, 500);

    assertEquals(Arrays.asList(root), plan.positionsBelow(32));
    assertEquals(Arrays.asList(root, first), plan.positionsBelow(500));
    assertEquals(2, plan.completedAtLeast(32));
    assertEquals(1, plan.completedAtLeast(500));
    assertTrue(plan.stillMatches(root));
    assertFalse(plan.stillMatches(new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE))));
    assertSame(second, plan.positions().get(2));
  }

  @Test
  void overallProgressWeightsTheFastOverviewBeforeDeepAnalysis() {
    assertEquals(
        20,
        WholeGameAnalysisSession.overallPercent(100, 100, 0, WholeGameAnalysisSession.State.DEEP));
    assertEquals(
        60,
        WholeGameAnalysisSession.overallPercent(100, 100, 50, WholeGameAnalysisSession.State.DEEP));
    assertEquals(
        99,
        WholeGameAnalysisSession.overallPercent(
            100, 100, 100, WholeGameAnalysisSession.State.DEEP));
    assertEquals(
        100,
        WholeGameAnalysisSession.overallPercent(
            100, 100, 100, WholeGameAnalysisSession.State.COMPLETE));
  }

  private static BoardData moveData(Stone color, int moveNumber, int playouts) {
    Stone[] stones = emptyStones();
    stones[0] = color;
    return BoardData.move(
        stones,
        new int[] {0, 0},
        color,
        color == Stone.WHITE,
        new Zobrist(),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50.0,
        playouts);
  }

  private static BoardData passData(Stone color, int moveNumber, int playouts) {
    return BoardData.pass(
        emptyStones(),
        color,
        color == Stone.WHITE,
        new Zobrist(),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
        50.0,
        playouts);
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    Arrays.fill(stones, Stone.EMPTY);
    return stones;
  }
}
