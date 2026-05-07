package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import java.awt.Window;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoardPrimaryEngineSyncTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void resendCurrentPositionToPrimaryEngineReplaysCurrentHistoryAfterReplacement()
      throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(moveNode(0, 0, Stone.BLACK, false, 1));
      history.add(moveNode(1, 1, Stone.WHITE, true, 2));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz engine = new Leelaz("");
      engine.isLoaded = true;
      setStarted(engine, true);
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      Lizzie.leelaz = engine;

      assertTrue(board.resendCurrentPositionToPrimaryEngine());

      assertEquals(
          List.of("boardsize 3", "clear_board", "play B A3", "play W B2"), output.commands());
    }
  }

  @Test
  void trySyncCurrentPositionToPrimaryEngineIncrementallyPlaysSingleAppendedMoveWithoutReset()
      throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData previousPosition = history.getData().clone();
      history.add(moveNode(0, 0, Stone.BLACK, false, 1));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz engine = new Leelaz("");
      engine.isLoaded = true;
      setStarted(engine, true);
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      Lizzie.leelaz = engine;

      assertTrue(
          board.trySyncCurrentPositionToPrimaryEngineIncrementally(
              previousPosition, BOARD_SIZE, BOARD_SIZE));

      assertEquals(List.of("boardsize 3", "play B A3"), output.commands());
    }
  }

  @Test
  void trySyncCurrentPositionToPrimaryEngineIncrementallySkipsWhenDisplayedPositionDidNotChange()
      throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      history.add(moveNode(0, 0, Stone.BLACK, false, 1));
      history.previous();
      BoardData previousPosition = history.getData().clone();
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz engine = new Leelaz("");
      engine.isLoaded = true;
      setStarted(engine, true);
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      Lizzie.leelaz = engine;

      assertTrue(
          board.trySyncCurrentPositionToPrimaryEngineIncrementally(
              previousPosition, BOARD_SIZE, BOARD_SIZE));

      assertTrue(output.commands().isEmpty());
    }
  }

  @Test
  void trySyncCurrentPositionToPrimaryEngineIncrementallyRejectsMultipleAppendedMoves()
      throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      Board board = allocate(Board.class);
      board.startStonelist = new ArrayList<>();
      board.hasStartStone = false;
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardData previousPosition = history.getData().clone();
      history.add(moveNode(0, 0, Stone.BLACK, false, 1));
      history.add(moveNode(1, 1, Stone.WHITE, true, 2));
      board.setHistory(history);
      Lizzie.board = board;

      Leelaz engine = new Leelaz("");
      engine.isLoaded = true;
      setStarted(engine, true);
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);
      Lizzie.leelaz = engine;

      assertFalse(
          board.trySyncCurrentPositionToPrimaryEngineIncrementally(
              previousPosition, BOARD_SIZE, BOARD_SIZE));

      assertTrue(output.commands().isEmpty());
    }
  }

  @Test
  void resendCurrentPositionToPrimaryEngineSkipsUnavailableEngine() throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      Lizzie.leelaz = null;

      assertFalse(Lizzie.board.resendCurrentPositionToPrimaryEngine());
    }
  }

  private static BoardData moveNode(
      int x, int y, Stone color, boolean blackToPlay, int moveNumber) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(x, y)] = color;
    int[] lastMove = new int[] {x, y};
    return BoardData.move(
        stones,
        lastMove,
        color,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        moveList(x, y, moveNumber),
        0,
        0,
        50,
        1);
  }

  private static int[] moveList(int x, int y, int moveNumber) {
    int[] moveNumberList = new int[BOARD_AREA];
    moveNumberList[Board.getIndex(x, y)] = moveNumber;
    return moveNumberList;
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private static void setOutputStream(Leelaz engine, OutputStream stream) throws Exception {
    Field outputField = Leelaz.class.getDeclaredField("outputStream");
    outputField.setAccessible(true);
    outputField.set(engine, Leelaz.createCommandOutputStream(stream));
  }

  private static void setStarted(Leelaz engine, boolean started) throws Exception {
    Field startedField = Leelaz.class.getDeclaredField("started");
    startedField.setAccessible(true);
    startedField.setBoolean(engine, started);
  }

  private static Config minimalConfig() throws Exception {
    Config config = allocate(Config.class);
    config.extraMode = ExtraMode.Normal;
    config.alwaysGtp = false;
    return config;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class RecordingOutputStream extends OutputStream {
    private final StringBuilder currentCommand = new StringBuilder();
    private final List<String> commands = new ArrayList<>();

    @Override
    public void write(int b) {
      currentCommand.append((char) b);
    }

    @Override
    public void flush() throws IOException {
      String command = currentCommand.toString().trim();
      currentCommand.setLength(0);
      if (!command.isEmpty()) {
        commands.add(command);
      }
    }

    private List<String> commands() {
      return commands;
    }
  }

  private static final class SilentFrame extends LizzieFrame {
    private SilentFrame() {
      super();
    }

    @Override
    public void refresh() {}
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super((Window) null);
    }

    @Override
    public boolean isVisible() {
      return false;
    }

    @Override
    public void addCommand(String command, int commandNumber, String engineName) {}

    @Override
    public void addCommandForEngineGame(
        String command, int commandNumber, String engineName, boolean isBlack) {}

    @Override
    public void addLine(String line) {}
  }

  private static final class TestHarness implements AutoCloseable {
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final GtpConsolePane previousGtpConsole;
    private final Leelaz previousLeelaz;
    private final boolean previousEngineEmpty;
    private final int previousBoardWidth;
    private final int previousBoardHeight;

    private TestHarness() {
      previousConfig = Lizzie.config;
      previousBoard = Lizzie.board;
      previousFrame = Lizzie.frame;
      previousGtpConsole = Lizzie.gtpConsole;
      previousLeelaz = Lizzie.leelaz;
      previousEngineEmpty = EngineManager.isEmpty;
      previousBoardWidth = Board.boardWidth;
      previousBoardHeight = Board.boardHeight;
    }

    private static TestHarness open() throws Exception {
      TestHarness harness = new TestHarness();
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      Lizzie.config = minimalConfig();
      Lizzie.board = allocate(Board.class);
      Lizzie.board.startStonelist = new ArrayList<>();
      Lizzie.board.setHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
      Lizzie.frame = allocate(SilentFrame.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      Lizzie.leelaz = null;
      EngineManager.isEmpty = false;
      return harness;
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousGtpConsole;
      Lizzie.leelaz = previousLeelaz;
      EngineManager.isEmpty = previousEngineEmpty;
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static sun.misc.Unsafe loadUnsafe() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Failed to access Unsafe", ex);
      }
    }
  }
}
