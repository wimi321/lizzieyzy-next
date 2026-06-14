package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.PlayerStrengthEstimator;
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
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class LizzieFrameRegressionTest {
  private static final int BOARD_SIZE = 2;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void playerStrengthRankReferenceKeepsKyuResultsInKyuBand() throws Exception {
    Method rankLevel = LizzieFrame.class.getDeclaredMethod("playerStrengthRankLevel", String.class);
    rankLevel.setAccessible(true);

    assertEquals(1, rankLevel.invoke(null, "1-2k"));
    assertEquals(1, rankLevel.invoke(null, "11-15k"));
    assertEquals(5, rankLevel.invoke(null, "1d"));
    assertEquals(7, rankLevel.invoke(null, "2-3d"));
    assertEquals(8, rankLevel.invoke(null, "4d"));
    assertEquals(10, rankLevel.invoke(null, "10d\u804c\u4e1a"));
  }

  @Test
  void playerStrengthHitMapExposesMoveTooltip() throws Exception {
    Class<?> panelClass =
        Class.forName("featurecat.lizzie.gui.LizzieFrame$PlayerStrengthMoveHitMapPanel");
    java.lang.reflect.Constructor<?> constructor =
        panelClass.getDeclaredConstructor(PlayerStrengthEstimator.Report.class);
    constructor.setAccessible(true);
    javax.swing.JComponent panel =
        (javax.swing.JComponent) constructor.newInstance(playerStrengthReportWithSamples());
    panel.setSize(900, 300);

    java.awt.image.BufferedImage image =
        new java.awt.image.BufferedImage(900, 300, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    panel.paint(image.createGraphics());

    java.awt.event.MouseEvent event =
        new java.awt.event.MouseEvent(
            panel,
            java.awt.event.MouseEvent.MOUSE_MOVED,
            System.currentTimeMillis(),
            0,
            86,
            106,
            0,
            false);
    String tooltip = panel.getToolTipText(event);

    assertTrue(tooltip.contains("1"), "tooltip should include the move number.");
    assertTrue(tooltip.contains("AI"), "tooltip should include AI choice details.");
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
    } finally {
      drainEdt();
      env.close();
    }
  }

  @Test
  void openBoardSyncDoesNotFallbackWhenNativeSyncUnsupported() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingFrame frame = newTrackingFrame();
      Lizzie.frame = frame;

      SwingUtilities.invokeAndWait(frame::openBoardSync);

      assertEquals(0, frame.nativeCreateCount.get());
      assertEquals(0, frame.createCount.get());
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

  @Test
  void autoQuickAnalyzeCanBeDisabledForLoadedGame() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze(false);
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      LizzieFrame frame = allocate(LizzieFrame.class);

      assertFalse(
          invokeShouldAutoQuickAnalyze(frame),
          "disabled auto quick analyze should not start the fast winrate graph refresh.");
    } finally {
      env.close();
    }
  }

  @Test
  void foxKifuLoadStartsSilentQuickAnalyzeBeforePrimaryEngineIsReady() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);

      assertTrue(
          frame.ensureAnalysisResumedAfterLoad(),
          "loaded Fox records should start the silent winrate refresh even before Space starts ponder.");
      assertEquals(1, frame.flashAnalyzeGameCount);
      assertTrue(frame.lastIsAllGame);
      assertFalse(frame.lastIsAllBranches);
      assertTrue(frame.lastSilentAnalyze);
      assertEquals(0, frame.refreshCount, "auto quick analyze should not fall back to ponder refresh.");
    } finally {
      env.close();
    }
  }

  @Test
  void finishKifuLoadDoesNotRefreshAgainBeforeHidingOverlay() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      FinishTrackingFrame frame = allocate(FinishTrackingFrame.class);
      frame.refreshCount = new AtomicInteger();
      JPanel glassPane = new JPanel();
      glassPane.setVisible(true);
      setField(frame, "kifuLoadGlassPane", glassPane);
      setField(frame, "kifuLoadVisibleSince", System.currentTimeMillis() - 1000);
      CountDownLatch finished = new CountDownLatch(1);

      SwingUtilities.invokeAndWait(() -> frame.finishKifuLoad(finished::countDown));

      assertTrue(finished.await(2, TimeUnit.SECONDS), "kifu load overlay should always finish.");
      drainEdt();
      assertFalse(glassPane.isVisible(), "finish should hide the kifu load overlay.");
      assertEquals(0, frame.refreshCount.get(), "finish should not repeat heavy board refresh.");
    } finally {
      drainEdt();
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
    frame.nativeCreateCount = new AtomicInteger();
    frame.replacementReadBoard = fakeReadBoard();
    return frame;
  }

  private static PlayerStrengthEstimator.Report playerStrengthReportWithSamples() throws Exception {
    PlayerStrengthEstimator.Sample blackSample =
        playerStrengthSample(
            Stone.BLACK,
            1,
            1.2,
            Optional.of(0.4),
            true,
            0,
            PlayerStrengthEstimator.MoveCategory.EXCELLENT);
    PlayerStrengthEstimator.Sample whiteSample =
        playerStrengthSample(
            Stone.WHITE,
            2,
            3.4,
            Optional.of(1.1),
            false,
            1,
            PlayerStrengthEstimator.MoveCategory.GOOD);
    PlayerStrengthEstimator.SideReport blackReport =
        playerStrengthSideReport(java.util.List.of(blackSample), 1.0, 1.0);
    PlayerStrengthEstimator.SideReport whiteReport =
        playerStrengthSideReport(java.util.List.of(whiteSample), 0.0, 1.0);
    PlayerStrengthEstimator.SideReport overallReport =
        playerStrengthSideReport(java.util.List.of(blackSample, whiteSample), 0.5, 1.0);

    java.lang.reflect.Constructor<PlayerStrengthEstimator.Report> constructor =
        PlayerStrengthEstimator.Report.class.getDeclaredConstructor(
            PlayerStrengthEstimator.SideReport.class,
            PlayerStrengthEstimator.SideReport.class,
            PlayerStrengthEstimator.SideReport.class,
            PlayerStrengthEstimator.StrengthModel.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        blackReport, whiteReport, overallReport, PlayerStrengthEstimator.StrengthModel.GP_CORE4);
  }

  private static PlayerStrengthEstimator.Sample playerStrengthSample(
      Stone color,
      int moveNumber,
      double winrateLoss,
      Optional<Double> scoreLoss,
      boolean firstChoice,
      int aiRank,
      PlayerStrengthEstimator.MoveCategory category)
      throws Exception {
    java.lang.reflect.Constructor<PlayerStrengthEstimator.Sample> constructor =
        PlayerStrengthEstimator.Sample.class.getDeclaredConstructor(
            Stone.class,
            int.class,
            double.class,
            Optional.class,
            boolean.class,
            int.class,
            PlayerStrengthEstimator.MoveCategory.class,
            double.class,
            double.class,
            double.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        color, moveNumber, winrateLoss, scoreLoss, firstChoice, aiRank, category, 1.0, 35.0, 1.0);
  }

  private static PlayerStrengthEstimator.SideReport playerStrengthSideReport(
      java.util.List<PlayerStrengthEstimator.Sample> samples,
      double firstChoiceRate,
      double goodMoveRate)
      throws Exception {
    java.lang.reflect.Constructor<PlayerStrengthEstimator.SideReport> constructor =
        PlayerStrengthEstimator.SideReport.class.getDeclaredConstructor(
            int.class,
            int.class,
            PlayerStrengthEstimator.StrengthModel.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            String.class,
            PlayerStrengthEstimator.Confidence.class,
            java.util.List.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        samples.size(),
        samples.size(),
        PlayerStrengthEstimator.StrengthModel.GP_CORE4,
        1.0,
        0.5,
        82.0,
        1.5,
        0.8,
        0.8,
        1.2,
        1.2,
        goodMoveRate,
        firstChoiceRate,
        goodMoveRate,
        0.0,
        0.0,
        0.0,
        35.0,
        "1d",
        PlayerStrengthEstimator.Confidence.HIGH,
        samples);
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

  private static BoardHistoryList historyWithUnanalyzedMove() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveData(new int[] {0, 0}, Stone.BLACK, false, 1, 0));
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
    return configWithAutoQuickAnalyze(true);
  }

  private static Config configWithAutoQuickAnalyze(boolean enabled) throws Exception {
    Config config = allocate(Config.class);
    config.autoQuickAnalyzeOnLoad = enabled;
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
    private final Leelaz previousLeelaz;
    private final boolean previousEngineEmpty;
    private final boolean previousEngineGame;
    private final boolean previousPreEngineGame;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame,
        Leelaz previousLeelaz,
        boolean previousEngineEmpty,
        boolean previousEngineGame,
        boolean previousPreEngineGame) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousLeelaz = previousLeelaz;
      this.previousEngineEmpty = previousEngineEmpty;
      this.previousEngineGame = previousEngineGame;
      this.previousPreEngineGame = previousPreEngineGame;
    }

    private static TestEnvironment open() {
      TestEnvironment env =
          new TestEnvironment(
              Board.boardWidth,
              Board.boardHeight,
              Lizzie.config,
              Lizzie.board,
              Lizzie.frame,
              Lizzie.leelaz,
              EngineManager.isEmpty,
              EngineManager.isEngineGame,
              EngineManager.isPreEngineGame);
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
      Lizzie.leelaz = previousLeelaz;
      EngineManager.isEmpty = previousEngineEmpty;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.isPreEngineGame = previousPreEngineGame;
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

  private static final class FinishTrackingFrame extends LizzieFrame {
    private AtomicInteger refreshCount;

    @Override
    public void refresh() {
      refreshCount.incrementAndGet();
      throw new AssertionError("finishKifuLoad must not depend on a second refresh.");
    }
  }

  private static final class AnalysisResumeTrackingFrame extends LizzieFrame {
    private int flashAnalyzeGameCount;
    private int refreshCount;
    private boolean lastIsAllGame;
    private boolean lastIsAllBranches;
    private boolean lastSilentAnalyze;

    @Override
    public void flashAnalyzeGame(boolean isAllGame, boolean isAllBranches, boolean silentAnalyze) {
      flashAnalyzeGameCount++;
      lastIsAllGame = isAllGame;
      lastIsAllBranches = isAllBranches;
      lastSilentAnalyze = silentAnalyze;
    }

    @Override
    public void refresh() {
      refreshCount++;
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
