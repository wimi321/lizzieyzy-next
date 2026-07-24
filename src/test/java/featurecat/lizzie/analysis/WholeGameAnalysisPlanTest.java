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
import java.util.List;
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
    installCompleteAnalysis(first.getData(), 40, false);
    installCompleteAnalysis(second.getData(), 700, false);

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
  void planDetectsSameRootMainlineReplacementAppendAndRemoval() {
    BoardHistoryNode replacementRoot =
        new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    replacementRoot.add(new BoardHistoryNode(moveData(Stone.BLACK, 1, 0)));
    WholeGameAnalysisPlan replacementPlan = WholeGameAnalysisPlan.create(replacementRoot, 32, 500);
    assertTrue(replacementPlan.stillMatches(replacementRoot));
    replacementRoot.add(new BoardHistoryNode(moveData(Stone.BLACK, 1, 0)));
    assertFalse(replacementPlan.stillMatches(replacementRoot));

    BoardHistoryNode appendRoot = new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    BoardHistoryNode appendLast = appendRoot.add(new BoardHistoryNode(moveData(Stone.BLACK, 1, 0)));
    WholeGameAnalysisPlan appendPlan = WholeGameAnalysisPlan.create(appendRoot, 32, 500);
    new BoardHistoryNode(moveData(Stone.BLACK, 1, 0)).reparentAsLastVariationOf(appendRoot);
    assertTrue(appendPlan.stillMatches(appendRoot));
    appendLast.add(new BoardHistoryNode(moveData(Stone.WHITE, 2, 0)));
    assertFalse(appendPlan.stillMatches(appendRoot));

    BoardHistoryNode removalRoot = new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    removalRoot.add(new BoardHistoryNode(moveData(Stone.BLACK, 1, 0)));
    WholeGameAnalysisPlan removalPlan = WholeGameAnalysisPlan.create(removalRoot, 32, 500);
    removalRoot.deleteChild(0);
    assertFalse(removalPlan.stillMatches(removalRoot));
  }

  @Test
  void planDetectsInPlaceTurnAndBoardStateMutations() {
    BoardHistoryNode turnRoot =
        new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    WholeGameAnalysisPlan turnPlan = WholeGameAnalysisPlan.create(turnRoot, 32, 500);
    turnRoot.getData().blackToPlay = !turnRoot.getData().blackToPlay;
    assertFalse(turnPlan.stillMatches(turnRoot));

    BoardHistoryNode stonesRoot =
        new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    WholeGameAnalysisPlan stonesPlan = WholeGameAnalysisPlan.create(stonesRoot, 32, 500);
    stonesRoot.getData().stones[0] = Stone.BLACK;
    stonesRoot.getData().zobrist.toggleStone(0, 0, Stone.BLACK);
    assertFalse(stonesPlan.stillMatches(stonesRoot));
  }

  @Test
  void visitHeaderWithoutDisplayableCandidatesRemainsPending() {
    BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
    rootData.setPlayouts(700);
    BoardHistoryNode root = new BoardHistoryNode(rootData);
    WholeGameAnalysisPlan plan = WholeGameAnalysisPlan.create(root, 32, 500);

    assertEquals(List.of(root), plan.positionsMissingAnalysis(500, false));
    assertEquals(0, plan.completedAtLeast(500));
  }

  @Test
  void requestedOwnershipIsPartOfTheCompletionContract() {
    BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
    installCompleteAnalysis(rootData, 500, false);
    BoardHistoryNode root = new BoardHistoryNode(rootData);
    WholeGameAnalysisPlan plan = WholeGameAnalysisPlan.create(root, 32, 500);

    assertTrue(plan.positionsMissingAnalysis(500, false).isEmpty());
    assertEquals(List.of(root), plan.positionsMissingAnalysis(500, true));

    rootData.estimateArray = List.of(0.25);
    assertTrue(plan.positionsMissingAnalysis(500, true).isEmpty());
  }

  @Test
  void malformedFirstCandidateDoesNotCountAsComplete() {
    BoardData rootData = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
    installCompleteAnalysis(rootData, 500, false);
    MoveData firstMove = rootData.bestMoves.get(0);

    firstMove.coordinate = "";
    assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
    firstMove.coordinate = "B2";
    firstMove.playouts = 0;
    assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
    firstMove.playouts = 500;
    firstMove.winrate = Double.NaN;
    assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
    firstMove.winrate = 50.0;
    firstMove.variation = null;
    assertFalse(rootData.hasCompletePrimaryAnalysis(500, false));
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

  private static void installCompleteAnalysis(
      BoardData data, int visits, boolean includeOwnership) {
    MoveData move = new MoveData();
    move.coordinate = "B2";
    move.playouts = visits;
    move.winrate = 50.0;
    move.order = 0;
    move.variation = List.of("B2");
    data.setPlayouts(visits);
    data.bestMoves = List.of(move);
    data.estimateArray = includeOwnership ? List.of(0.25) : null;
  }
}
