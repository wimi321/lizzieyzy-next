package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Branch;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class BranchInputCaptureTest {
  @Test
  void sameNodeTurnChangeRejectsCaptureWithoutRevisionChange() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Board board = Lizzie.board;
      BoardHistoryNode current = board.getHistory().getCurrentHistoryNode();
      long revision = board.getContextRevision();
      BranchInputCapture capture = begin(board);
      Branch.Input stale =
          capture.capture(
              () -> {
                // This is the production explicit-turn path: no history move or revision advance.
                board.changeNextTurn();
                return List.of("A1");
              },
              () -> null,
              1,
              false,
              false,
              null,
              null);
      assertNull(stale);
      assertSame(current, board.getHistory().getCurrentHistoryNode());
      assertEquals(revision, board.getContextRevision());
      assertFalse(current.getData().blackToPlay);
      Branch.Input fresh =
          begin(board).capture(() -> List.of("A1"), () -> null, 1, false, false, null, null);
      Branch result = new Branch(fresh);
      assertEquals(Stone.WHITE, result.data.stones[2]);
      assertTrue(result.data.blackToPlay);
      assertEquals(Stone.EMPTY, current.getData().stones[2]);
    }
  }

  @Test
  void displayOwnerChangeRejectsMixedPositionAndPayload() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Board board = Lizzie.board;
      BoardHistoryNode current = board.getHistory().getCurrentHistoryNode();
      BoardHistoryNode[] displayed = {current};
      BoardHistoryNode other = new BoardHistoryList(BoardData.empty(2, 3)).getCurrentHistoryNode();
      BranchInputCapture capture =
          BranchInputCapture.begin(board, () -> current, () -> displayed[0]);
      assertNull(
          capture.capture(
              () -> {
                displayed[0] = other;
                return List.of("A1");
              },
              () -> null,
              1,
              false,
              false,
              null,
              null));
      assertSame(current, board.getHistory().getCurrentHistoryNode());
    }
  }

  @Test
  void resizingDuringPayloadCaptureRejectsOldGeometry() throws Exception {
    try (Fixture fixture = new Fixture()) {
      BranchInputCapture capture = begin(Lizzie.board);
      assertNull(
          capture.capture(
              () -> {
                Board.boardHeight = 4;
                return List.of("A1");
              },
              () -> null,
              1,
              false,
              false,
              null,
              null));
    }
  }

  @Test
  void candidateEmptinessUsesSimulationPositionRatherThanAnalysisPosition() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Board board = Lizzie.board;
      BoardHistoryNode source = board.getHistory().getCurrentHistoryNode();
      source.getData().stones[Board.getIndex(0, 2)] = Stone.WHITE;
      BoardHistoryNode analysis = new BoardHistoryList(BoardData.empty(2, 3)).getCurrentHistoryNode();
      BranchInputCapture capture = BranchInputCapture.begin(board, () -> source, () -> analysis);
      assertFalse(capture.isEmptyPoint("A1"));
      assertTrue(capture.isEmptyPoint("B2"));
      assertTrue(capture.isEmptyPoint("pass"));
      board.changeNextTurn();
      assertFalse(capture.isEmptyPoint("B2"), "Retired source cannot admit a candidate");
    }
  }

  private static BranchInputCapture begin(Board board) {
    return BranchInputCapture.begin(
        board,
        () -> board.getHistory().getCurrentHistoryNode(),
        () -> board.getHistory().getCurrentHistoryNode());
  }

  private static final class Fixture implements AutoCloseable {
    final Board board = Lizzie.board;
    final Config config = Lizzie.config;
    final Leelaz engine = Lizzie.leelaz;
    final int width = Board.boardWidth;
    final int height = Board.boardHeight;

    Fixture() throws Exception {
      Board.boardWidth = 2;
      Board.boardHeight = 3;
      Lizzie.config = allocate(Config.class);
      Lizzie.leelaz = allocate(Leelaz.class);
      Lizzie.leelaz.canAddPlayer = true;
      Lizzie.board = allocate(Board.class);
      Lizzie.board.setHistory(new BoardHistoryList(BoardData.empty(2, 3)));
    }

    public void close() {
      Lizzie.board = board;
      Lizzie.config = config;
      Lizzie.leelaz = engine;
      Board.boardWidth = width;
      Board.boardHeight = height;
    }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }
}
