package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WholeGameAnalysisResultViewTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void cachedSuggestionsOverrideDisplayFlagsOnlyForTheAnalyzedGame() {
    BoardHistoryNode root = analyzedNode(500);
    WholeGameAnalysisResultView view = new WholeGameAnalysisResultView();
    view.activate(root);

    assertTrue(view.shouldShowSuggestions(root, root, false));
    assertTrue(view.shouldShowSuggestionWinrate(root, root, false));
    assertTrue(view.shouldShowSuggestionPlayouts(root, root, false));

    BoardHistoryNode anotherRoot = analyzedNode(500);
    assertFalse(view.shouldShowSuggestions(anotherRoot, anotherRoot, false));
    assertFalse(view.shouldShowSuggestionWinrate(anotherRoot, anotherRoot, false));
    assertFalse(view.shouldShowSuggestionPlayouts(anotherRoot, anotherRoot, false));
  }

  @Test
  void emptyCachedPayloadDoesNotForceSuggestions() {
    BoardHistoryNode root = new BoardHistoryNode(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    WholeGameAnalysisResultView view = new WholeGameAnalysisResultView();
    view.activate(root);

    assertFalse(view.shouldShowSuggestions(root, root, false));
  }

  @Test
  void analyzedMoveGetsOneEffectiveEvaluationMarkWithoutChangingConfiguredPreference() {
    BoardHistoryNode root = analyzedNode(500);
    BoardHistoryNode move = root.add(new BoardHistoryNode(moveData(Stone.BLACK, 1, 500)));
    WholeGameAnalysisResultView view = new WholeGameAnalysisResultView();
    view.activate(root);

    assertTrue(view.hasVisibleMoveEvaluation(root, move));
    assertEquals(1, view.effectiveMoveRankLimit(root, move, -1));
    assertEquals(0, view.effectiveMoveRankLimit(root, move, 0));
    assertEquals(-1, view.effectiveMoveRankLimit(analyzedNode(500), move, -1));
  }

  @Test
  void visitsWithoutCandidatePayloadDoNotForceAnEvaluationMark() {
    BoardHistoryNode root = analyzedNode(500);
    BoardData headerOnlyMove = moveData(Stone.BLACK, 1, 500);
    headerOnlyMove.bestMoves.clear();
    BoardHistoryNode move = root.add(new BoardHistoryNode(headerOnlyMove));
    WholeGameAnalysisResultView view = new WholeGameAnalysisResultView();
    view.activate(root);

    assertFalse(view.hasVisibleMoveEvaluation(root, move));
    assertEquals(-1, view.effectiveMoveRankLimit(root, move, -1));
  }

  @Test
  void wholeGameEvaluationIsPaintedAfterTheDefaultLastMoveMarker() {
    assertEquals(
        BoardRenderer.MoveOverlayOrder.MOVE_NUMBERS_THEN_RANK,
        BoardRenderer.moveOverlayOrder(0, false, false, 1, false, true));
  }

  private static BoardHistoryNode analyzedNode(int playouts) {
    BoardData data = BoardData.empty(BOARD_SIZE, BOARD_SIZE);
    data.setPlayouts(playouts);
    MoveData move = new MoveData();
    move.coordinate = "A1";
    move.playouts = playouts;
    move.variation = java.util.List.of("A1");
    data.bestMoves.add(move);
    return new BoardHistoryNode(data);
  }

  private static BoardData moveData(Stone color, int moveNumber, int playouts) {
    Stone[] stones = new Stone[BOARD_AREA];
    Arrays.fill(stones, Stone.EMPTY);
    stones[0] = color;
    BoardData data =
        BoardData.move(
            stones,
            new int[] {0, 0},
            color,
            color == Stone.WHITE,
            new Zobrist(),
            moveNumber,
            new int[BOARD_AREA],
            0,
            0,
            50,
            playouts);
    MoveData suggestion = new MoveData();
    suggestion.coordinate = "B1";
    suggestion.playouts = playouts;
    suggestion.variation = java.util.List.of("B1");
    data.bestMoves.add(suggestion);
    return data;
  }
}
