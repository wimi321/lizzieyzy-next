package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoardHistoryMoveIdentityTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void nextByMoveIdentityReusesExistingVariationWithDifferentComment() {
    try (BoardSizeScope ignored = BoardSizeScope.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode root = history.getCurrentHistoryNode();
      root.add(new BoardHistoryNode(moveData(1, 0, Stone.BLACK, false, 0, 0)));

      BoardData existingData = moveData(0, 0, Stone.BLACK, false, 0, 0);
      existingData.comment = "existing SGF variation comment";
      BoardHistoryNode existingVariation = new BoardHistoryNode(existingData);
      existingVariation.reparentAsLastVariationOf(root);

      BoardData clickedMove = moveData(0, 0, Stone.BLACK, false, 0, 0);

      assertTrue(history.nextByMoveIdentity(clickedMove).isPresent());
      assertSame(existingVariation, history.getCurrentHistoryNode());
      assertEquals(2, root.numberOfChildren(), "clicking an existing variation must not duplicate it.");
      assertEquals(
          "existing SGF variation comment",
          history.getData().comment,
          "entering the existing branch should preserve its original node data.");
    }
  }

  @Test
  void nextByMoveIdentityDoesNotMatchDifferentMoveIdentity() {
    try (BoardSizeScope ignored = BoardSizeScope.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode root = history.getCurrentHistoryNode();
      new BoardHistoryNode(moveData(0, 0, Stone.BLACK, false, 1, 0))
          .reparentAsLastVariationOf(root);

      assertFalse(
          history.nextByMoveIdentity(moveData(0, 0, Stone.BLACK, false, 0, 0)).isPresent(),
          "same coordinate should not match when capture totals differ.");
      assertFalse(
          history.nextByMoveIdentity(moveData(0, 0, Stone.WHITE, true, 1, 0)).isPresent(),
          "same coordinate should not match when the played color differs.");
      assertSame(root, history.getCurrentHistoryNode());
      assertEquals(1, root.numberOfChildren());
    }
  }

  @Test
  void directChildMoveIdentitySupportsPassNodes() {
    try (BoardSizeScope ignored = BoardSizeScope.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode root = history.getCurrentHistoryNode();
      BoardData existingPass = passData(Stone.BLACK, false, 0, 0);
      existingPass.comment = "existing pass variation";
      BoardHistoryNode passVariation = new BoardHistoryNode(existingPass);
      passVariation.reparentAsLastVariationOf(root);

      assertTrue(history.nextByMoveIdentity(passData(Stone.BLACK, false, 0, 0)).isPresent());
      assertSame(passVariation, history.getCurrentHistoryNode());
      assertEquals("existing pass variation", history.getData().comment);
    }
  }

  private static BoardData moveData(
      int x, int y, Stone color, boolean blackToPlay, int blackCaptures, int whiteCaptures) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(x, y)] = color;
    return BoardData.move(
        stones,
        new int[] {x, y},
        color,
        blackToPlay,
        zobrist(stones),
        1,
        new int[BOARD_AREA],
        blackCaptures,
        whiteCaptures,
        50,
        0);
  }

  private static BoardData passData(
      Stone color, boolean blackToPlay, int blackCaptures, int whiteCaptures) {
    Stone[] stones = emptyStones();
    return BoardData.pass(
        stones,
        color,
        blackToPlay,
        zobrist(stones),
        1,
        new int[BOARD_AREA],
        blackCaptures,
        whiteCaptures,
        50,
        0);
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private static Zobrist zobrist(Stone[] stones) {
    Zobrist zobrist = new Zobrist();
    for (int x = 0; x < BOARD_SIZE; x++) {
      for (int y = 0; y < BOARD_SIZE; y++) {
        Stone stone = stones[Board.getIndex(x, y)];
        if (!stone.isEmpty()) {
          zobrist.toggleStone(x, y, stone);
        }
      }
    }
    return zobrist;
  }

  private static final class BoardSizeScope implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;

    private BoardSizeScope(int previousBoardWidth, int previousBoardHeight) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
    }

    private static BoardSizeScope open() {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      return new BoardSizeScope(previousBoardWidth, previousBoardHeight);
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
    }
  }
}
