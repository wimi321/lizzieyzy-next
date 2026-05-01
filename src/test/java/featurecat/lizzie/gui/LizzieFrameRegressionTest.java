package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.ReadBoard;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class LizzieFrameRegressionTest {
  private static final int BOARD_SIZE = 2;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void openReadBoardJavaRestartsExistingReadBoardOnEdt() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingFrame frame = newTrackingFrame();
      frame.readBoard = fakeReadBoard();
      frame.allowShutdown.countDown();
      Lizzie.frame = frame;

      SwingUtilities.invokeAndWait(frame::openReadBoardJava);

      assertTrue(frame.shutdownCalled.await(2, TimeUnit.SECONDS));
      assertTrue(
          frame.createCalled.await(2, TimeUnit.SECONDS),
          "EDT restart should start a replacement ReadBoard.");
      assertFalse(
          frame.startedBeforeShutdownCompleted,
          "replacement ReadBoard should only start after shutdown finishes detaching the old instance.");
      assertSame(frame.replacementReadBoard, frame.readBoard);
    } finally {
      drainEdt();
      env.close();
    }
  }

  @Test
  void openReadBoardJavaCoalescesConsecutiveRestartsOnEdt() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingFrame frame = newTrackingFrame();
      frame.readBoard = fakeReadBoard();
      Lizzie.frame = frame;

      assertConsecutiveRestartIsCoalesced(frame, frame::openReadBoardJava);
    } finally {
      drainEdt();
      env.close();
    }
  }

  @Test
  void openBoardSyncCoalescesConsecutiveRestartsOnEdt() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingFrame frame = newTrackingFrame();
      frame.readBoard = fakeReadBoard();
      frame.nativeBoardSyncSupported = true;
      frame.nativeReadBoardAvailable = true;
      Lizzie.frame = frame;

      assertConsecutiveRestartIsCoalesced(frame, frame::openBoardSync);
      assertEquals(1, frame.nativeCreateCount.get());
      assertEquals(0, frame.javaCreateCount.get());
    } finally {
      drainEdt();
      env.close();
    }
  }

  @Test
  void openBoardSyncFallsBackToJavaWhenNativeSyncUnsupported() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingFrame frame = newTrackingFrame();
      Lizzie.frame = frame;

      SwingUtilities.invokeAndWait(frame::openBoardSync);

      assertTrue(frame.createCalled.await(2, TimeUnit.SECONDS));
      assertEquals(0, frame.nativeCreateCount.get());
      assertEquals(1, frame.javaCreateCount.get());
    } finally {
      drainEdt();
      env.close();
    }
  }

  @Test
  void openBoardSyncDoesNothingWhenNativeReadBoardMissing() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingFrame frame = newTrackingFrame();
      frame.nativeBoardSyncSupported = true;
      frame.nativeReadBoardAvailable = false;
      Lizzie.frame = frame;

      SwingUtilities.invokeAndWait(frame::openBoardSync);

      assertEquals(0, frame.nativeCreateCount.get());
      assertEquals(0, frame.javaCreateCount.get());
      assertEquals(0, frame.createCount.get());
    } finally {
      drainEdt();
      env.close();
    }
  }

  @Test
  void openBoardSyncDoesNotStartReplacementWhileRestartStillReserved() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingFrame frame = newTrackingFrame();
      frame.nativeBoardSyncSupported = true;
      frame.nativeReadBoardAvailable = true;
      setField(frame, "readBoardRestartTarget", fakeReadBoard());
      Lizzie.frame = frame;

      SwingUtilities.invokeAndWait(frame::openBoardSync);

      assertEquals(0, frame.nativeCreateCount.get());
      assertEquals(0, frame.javaCreateCount.get());
      assertEquals(0, frame.createCount.get());
      assertTrue(getField(frame, "pendingReadBoardFactory") != null);
    } finally {
      drainEdt();
      env.close();
    }
  }

  @Test
  void autoQuickAnalyzeIgnoresSnapshotMarkersInMoveCount() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithAnalyzedMoveThenSnapshotMarker());
      LizzieFrame frame = allocate(LizzieFrame.class);

      assertFalse(
          invokeShouldAutoQuickAnalyze(frame),
          "auto quick analyze should only count real moves and passes.");
    } finally {
      env.close();
    }
  }

  @Test
  void autoQuickAnalyzeIgnoresDummyPassPlaceholdersInMoveCount() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithAnalyzedMoveThenDummyPass());
      LizzieFrame frame = allocate(LizzieFrame.class);

      assertFalse(
          invokeShouldAutoQuickAnalyze(frame),
          "auto quick analyze should ignore dummy PASS placeholders in move counts.");
    } finally {
      env.close();
    }
  }

  private static TrackingFrame newTrackingFrame() throws Exception {
    TrackingFrame frame = allocate(TrackingFrame.class);
    initReadBoardRestartLock(frame);
    frame.shutdownCalled = new CountDownLatch(1);
    frame.createCalled = new CountDownLatch(1);
    frame.secondShutdownCalled = new CountDownLatch(1);
    frame.secondCreateCalled = new CountDownLatch(1);
    frame.allowShutdown = new CountDownLatch(1);
    frame.shutdownCount = new AtomicInteger();
    frame.createCount = new AtomicInteger();
    frame.javaCreateCount = new AtomicInteger();
    frame.nativeCreateCount = new AtomicInteger();
    frame.replacementReadBoard = fakeReadBoard();
    return frame;
  }

  private static void assertConsecutiveRestartIsCoalesced(TrackingFrame frame, Runnable trigger)
      throws Exception {
    SwingUtilities.invokeAndWait(trigger);

    assertTrue(frame.shutdownCalled.await(2, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(trigger);

    assertFalse(
        frame.secondShutdownCalled.await(200, TimeUnit.MILLISECONDS),
        "existing ReadBoard should only be shut down once during a coalesced restart.");
    frame.allowShutdown.countDown();

    assertTrue(
        frame.createCalled.await(2, TimeUnit.SECONDS),
        "coalesced restart should still create one replacement ReadBoard.");
    drainEdt();

    assertFalse(
        frame.secondCreateCalled.await(200, TimeUnit.MILLISECONDS),
        "coalesced restart should only create one replacement ReadBoard.");
    assertEquals(1, frame.shutdownCount.get());
    assertEquals(1, frame.createCount.get());
    assertSame(frame.replacementReadBoard, frame.readBoard);
  }

  private static boolean invokeShouldAutoQuickAnalyze(LizzieFrame frame) throws Exception {
    Method method = LizzieFrame.class.getDeclaredMethod("shouldAutoQuickAnalyzeLoadedGame");
    method.setAccessible(true);
    return (boolean) method.invoke(frame);
  }

  private static BoardHistoryList historyWithAnalyzedMoveThenSnapshotMarker() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveData(new int[] {0, 0}, Stone.BLACK, false, 1, 1));
    history.add(snapshotData(new int[] {1, 1}, Stone.WHITE, true, 2));
    return history;
  }

  private static BoardHistoryList historyWithAnalyzedMoveThenDummyPass() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveData(new int[] {0, 0}, Stone.BLACK, false, 1, 1));
    history.add(dummyPassData(Stone.WHITE, true, 2));
    history.add(moveData(new int[] {1, 1}, Stone.WHITE, false, 3, 1));
    return history;
  }

  private static BoardData moveData(
      int[] lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber, int playouts) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(lastMove[0], lastMove[1])] = lastMoveColor;
    return BoardData.move(
        stones,
        lastMove,
        lastMoveColor,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        moveList(lastMove[0], lastMove[1], moveNumber),
        0,
        0,
        50,
        playouts);
  }

  private static BoardData snapshotData(
      int[] lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(lastMove[0], lastMove[1])] = lastMoveColor;
    return BoardData.snapshot(
        stones,
        Optional.of(lastMove),
        lastMoveColor,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        moveList(lastMove[0], lastMove[1], moveNumber),
        0,
        0,
        50,
        0);
  }

  private static BoardData dummyPassData(Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    BoardData data =
        BoardData.pass(
            emptyStones(),
            lastMoveColor,
            blackToPlay,
            new Zobrist(),
            moveNumber,
            new int[BOARD_AREA],
            0,
            0,
            50,
            0);
    data.dummy = true;
    return data;
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

  private static Board boardWith(BoardHistoryList history) throws Exception {
    Board board = allocate(Board.class);
    board.setHistory(history);
    return board;
  }

  private static Config configWithAutoQuickAnalyze() throws Exception {
    Config config = allocate(Config.class);
    config.autoQuickAnalyzeOnLoad = true;
    return config;
  }

  private static ReadBoard fakeReadBoard() throws Exception {
    return allocate(ReadBoard.class);
  }

  private static void drainEdt() throws Exception {
    SwingUtilities.invokeAndWait(() -> {});
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static void initReadBoardRestartLock(LizzieFrame frame) throws Exception {
    setField(frame, "readBoardRestartLock", new Object());
  }

  private static Object getField(Object target, String name) throws Exception {
    Field field = LizzieFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = LizzieFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
    }

    private static TestEnvironment open() {
      TestEnvironment env =
          new TestEnvironment(
              Board.boardWidth, Board.boardHeight, Lizzie.config, Lizzie.board, Lizzie.frame);
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      return env;
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
    }
  }

  private static final class TrackingFrame extends LizzieFrame {
    private CountDownLatch shutdownCalled;
    private CountDownLatch createCalled;
    private CountDownLatch secondShutdownCalled;
    private CountDownLatch secondCreateCalled;
    private CountDownLatch allowShutdown;
    private AtomicInteger shutdownCount;
    private AtomicInteger createCount;
    private AtomicInteger javaCreateCount;
    private AtomicInteger nativeCreateCount;
    private ReadBoard replacementReadBoard;
    private boolean nativeBoardSyncSupported;
    private boolean nativeReadBoardAvailable;
    private volatile boolean shutdownCompleted;
    private volatile boolean startedBeforeShutdownCompleted;

    @Override
    protected void shutdownReadBoard(ReadBoard targetReadBoard) {
      if (shutdownCount.incrementAndGet() == 1) {
        shutdownCalled.countDown();
      } else {
        secondShutdownCalled.countDown();
      }
      await(allowShutdown);
      readBoard = null;
      shutdownCompleted = true;
    }

    @Override
    protected ReadBoard createJavaReadBoard() {
      javaCreateCount.incrementAndGet();
      return recordCreate();
    }

    @Override
    protected ReadBoard createNativeReadBoard() {
      nativeCreateCount.incrementAndGet();
      return recordCreate();
    }

    @Override
    protected boolean isNativeBoardSyncSupported() {
      return nativeBoardSyncSupported;
    }

    @Override
    protected boolean isNativeReadBoardAvailable() {
      return nativeReadBoardAvailable;
    }

    private ReadBoard recordCreate() {
      startedBeforeShutdownCompleted = !shutdownCompleted;
      if (createCount.incrementAndGet() == 1) {
        createCalled.countDown();
      } else {
        secondCreateCalled.countDown();
      }
      return replacementReadBoard;
    }

    private void await(CountDownLatch latch) {
      try {
        latch.await(2, TimeUnit.SECONDS);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for shutdown gate", ex);
      }
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
