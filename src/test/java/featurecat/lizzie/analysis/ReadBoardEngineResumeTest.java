package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadBoardEngineResumeTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  private int previousBoardWidth;
  private int previousBoardHeight;

  @BeforeEach
  void setUpFixtureBoardSize() {
    previousBoardWidth = Board.boardWidth;
    previousBoardHeight = Board.boardHeight;
    Board.boardWidth = BOARD_SIZE;
    Board.boardHeight = BOARD_SIZE;
    Zobrist.init();
  }

  @AfterEach
  void tearDownFixtureBoardSize() {
    Board.boardWidth = previousBoardWidth;
    Board.boardHeight = previousBoardHeight;
    Zobrist.init();
  }

  @Test
  void forceRebuildSchedulesResumeAnalysis() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              mainEnd.getData().stones,
              mainEnd.getData().lastMove,
              mainEnd.getData().lastMoveColor));

      assertEquals(1, harness.frame.scheduleResumeAnalysisCount);
      assertNotNull(harness.frame.lastScheduledResumeAction);
    }
  }

  @Test
  void firstNoChangeFrameAfterRestartSchedulesResumeAnalysis() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);

      harness.readBoard.parseLine("start 3 3");
      harness.sync(
          snapshot(
              mainEnd.getData().stones,
              mainEnd.getData().lastMove,
              mainEnd.getData().lastMoveColor));

      assertEquals(1, harness.frame.scheduleResumeAnalysisCount);
      assertNotNull(harness.frame.lastScheduledResumeAction);
    }
  }

  @Test
  void singleMoveRecoverySchedulesResumeAnalysis() throws Exception {
    Stone[] beforeCapture =
        stones(
            placement(0, 1, Stone.BLACK),
            placement(1, 0, Stone.BLACK),
            placement(1, 1, Stone.WHITE),
            placement(2, 1, Stone.BLACK));
    Stone[] afterCapture =
        stones(
            placement(0, 1, Stone.BLACK),
            placement(1, 0, Stone.BLACK),
            placement(2, 1, Stone.BLACK),
            placement(1, 2, Stone.BLACK));

    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(beforeCapture, true))) {
      harness.sync(snapshot(afterCapture, Optional.of(new int[] {1, 2}), Stone.BLACK));

      assertEquals(1, harness.frame.scheduleResumeAnalysisCount);
      assertNotNull(harness.frame.lastScheduledResumeAction);
    }
  }

  @Test
  void syncCommandDoesNotStartPonderWhenEngineIsNotStarted() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.leelaz.started = false;

      harness.readBoard.parseLine("sync");

      assertEquals(0, harness.leelaz.ponderCount);
      assertEquals(true, harness.frame.syncBoard);
    }
  }

  @Test
  void noponderDoesNotStartPonderWhenAlreadyStopped() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.leelaz.notPondering();

      harness.readBoard.parseLine("noponder");

      assertEquals(0, harness.leelaz.togglePonderCount);
      assertEquals(0, harness.leelaz.ponderCount);
      assertFalse(harness.leelaz.isPondering());
    }
  }

  @Test
  void noponderDoesNotRestartPonderAfterAutoPlayStopHandlesEngine() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.leelaz.Pondering();
      harness.frame.isAnaPlayingAgainstLeelaz = true;

      harness.readBoard.parseLine("noponder");

      assertEquals(1, harness.frame.stopAiPlayingAndPolicyCount);
      assertEquals(0, harness.leelaz.togglePonderCount);
      assertEquals(0, harness.leelaz.ponderCount);
      assertFalse(harness.leelaz.isPondering());
    }
  }

  @Test
  void noponderStopsActivePonderWhenNoGameStopHandledIt() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.leelaz.Pondering();

      harness.readBoard.parseLine("noponder");

      assertEquals(1, harness.leelaz.togglePonderCount);
      assertEquals(1, harness.leelaz.nameCmdCount);
      assertEquals(0, harness.leelaz.ponderCount);
      assertFalse(harness.leelaz.isPondering());
    }
  }

  @Test
  void boardOnlyForceRebuildDoesNotScheduleResumeAnalysis() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);

      harness.leelaz.started = false;
      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              mainEnd.getData().stones,
              mainEnd.getData().lastMove,
              mainEnd.getData().lastMoveColor));

      assertEquals(0, harness.frame.scheduleResumeAnalysisCount);
      assertNull(harness.frame.lastScheduledResumeAction);

      harness.leelaz.started = true;
      assertEquals(0, harness.leelaz.ponderCount);
    }
  }

  @Test
  void scheduledResumeAnalysisDoesNotRunAfterUserNavigatesAwayFromTarget() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      BoardHistoryNode mainEnd = path.nodes.get(path.nodes.size() - 1);
      BoardHistoryNode root = harness.board.getHistory().getStart();

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              mainEnd.getData().stones,
              mainEnd.getData().lastMove,
              mainEnd.getData().lastMoveColor));

      Runnable scheduledAction = harness.frame.lastScheduledResumeAction;
      assertNotNull(scheduledAction, "sync should bind a target-aware resume action.");

      harness.board.moveToAnyPosition(root);
      scheduledAction.run();

      assertEquals(0, harness.leelaz.ponderCount);
    }
  }

  @Test
  void newerSyncInvalidatesOlderScheduledResumeAction() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      HistoryPath firstPath = buildHistory(harness.board, placement(0, 0, Stone.BLACK));
      BoardHistoryNode moveOne = firstPath.nodes.get(firstPath.nodes.size() - 1);

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              moveOne.getData().stones,
              moveOne.getData().lastMove,
              moveOne.getData().lastMoveColor));

      Runnable firstScheduledAction = harness.frame.lastScheduledResumeAction;
      assertNotNull(firstScheduledAction, "first sync should bind a resume action.");

      HistoryPath secondPath = buildHistory(harness.board, placement(1, 0, Stone.WHITE));
      BoardHistoryNode moveTwo = secondPath.nodes.get(secondPath.nodes.size() - 1);

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(
          snapshot(
              moveTwo.getData().stones,
              moveTwo.getData().lastMove,
              moveTwo.getData().lastMoveColor));

      Runnable secondScheduledAction = harness.frame.lastScheduledResumeAction;
      assertNotNull(secondScheduledAction, "second sync should replace the resume action.");

      firstScheduledAction.run();
      assertEquals(0, harness.leelaz.ponderCount, "stale resume action should be ignored.");

      secondScheduledAction.run();
      assertEquals(1, harness.leelaz.ponderCount, "latest resume action should still run.");
    }
  }

  @Test
  void syncSpecificResumeSkipsAutoQuickAnalyzeLoadedGame() throws Exception {
    try (EngineResumeHarness harness =
        EngineResumeHarness.create(rootHistory(emptyStones(), true))) {
      Lizzie.config.autoQuickAnalyzeOnLoad = true;
      HistoryPath path =
          buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
      harness.board.moveToAnyPosition(path.nodes.get(path.nodes.size() - 1));

      boolean resumed = harness.frame.ensureAnalysisResumedAfterSyncLoad();

      assertEquals(true, resumed);
      assertEquals(1, harness.leelaz.ponderCount);
      assertEquals(0, harness.frame.flashAnalyzeGameCount);
    }
  }

  private static HistoryPath buildHistory(TrackingBoard board, Placement... moves) {
    BoardHistoryList history = board.getHistory();
    List<BoardHistoryNode> nodes = new ArrayList<>();
    for (Placement move : moves) {
      history.place(move.x, move.y, move.color, false);
      nodes.add(history.getCurrentHistoryNode());
    }
    return new HistoryPath(nodes);
  }

  private static BoardHistoryList rootHistory(Stone[] stones, boolean blackToPlay) {
    Board.boardWidth = BOARD_SIZE;
    Board.boardHeight = BOARD_SIZE;
    Zobrist.init();
    return new BoardHistoryList(
        BoardData.snapshot(
            stones.clone(),
            Optional.empty(),
            Stone.EMPTY,
            blackToPlay,
            zobrist(stones),
            0,
            new int[BOARD_AREA],
            0,
            0,
            50,
            0));
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private static Stone[] stones(Placement... placements) {
    Stone[] stones = emptyStones();
    for (Placement placement : placements) {
      stones[stoneIndex(placement.x, placement.y)] = placement.color;
    }
    return stones;
  }

  private static int[] snapshot(Stone[] stones, Optional<int[]> lastMove, Stone lastMoveColor) {
    int[] snapshot = new int[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      snapshot[index] = normalize(stones[stoneIndex(index % BOARD_SIZE, index / BOARD_SIZE)]);
    }
    if (lastMove.isPresent()) {
      int[] coords = lastMove.get();
      snapshot[coords[1] * BOARD_SIZE + coords[0]] = lastMoveColor == Stone.BLACK ? 3 : 4;
    }
    return snapshot;
  }

  private static int normalize(Stone stone) {
    if (stone == Stone.BLACK || stone == Stone.BLACK_RECURSED) {
      return 1;
    }
    if (stone == Stone.WHITE || stone == Stone.WHITE_RECURSED) {
      return 2;
    }
    return 0;
  }

  private static Zobrist zobrist(Stone[] stones) {
    Zobrist zobrist = new Zobrist();
    for (int x = 0; x < BOARD_SIZE; x++) {
      for (int y = 0; y < BOARD_SIZE; y++) {
        Stone stone = stones[stoneIndex(x, y)];
        if (!stone.isEmpty()) {
          zobrist.toggleStone(x, y, stone);
        }
      }
    }
    return zobrist;
  }

  private static int stoneIndex(int x, int y) {
    return x * BOARD_SIZE + y;
  }

  private static Placement placement(int x, int y, Stone color) {
    return new Placement(x, y, color);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
    Class<?> current = type;
    while (current != null) {
      try {
        return current.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        current = current.getSuperclass();
      }
    }
    throw new NoSuchFieldException(name);
  }

  private static void invokeSyncBoardStones(ReadBoard readBoard) throws Exception {
    Method method = ReadBoard.class.getDeclaredMethod("syncBoardStones", boolean.class);
    method.setAccessible(true);
    method.invoke(readBoard, false);
  }

  private static final class EngineResumeHarness implements AutoCloseable {
    private final Config previousConfig;
    private final Board previousBoard;
    private final Leelaz previousLeelaz;
    private final LizzieFrame previousFrame;
    private final TrackingBoard board;
    private final TrackingFrame frame;
    private final SnapshotTrackingLeelaz leelaz;
    private final ReadBoard readBoard;

    private EngineResumeHarness(
        Config previousConfig,
        Board previousBoard,
        Leelaz previousLeelaz,
        LizzieFrame previousFrame,
        TrackingBoard board,
        TrackingFrame frame,
        SnapshotTrackingLeelaz leelaz,
        ReadBoard readBoard) {
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousLeelaz = previousLeelaz;
      this.previousFrame = previousFrame;
      this.board = board;
      this.frame = frame;
      this.leelaz = leelaz;
      this.readBoard = readBoard;
    }

    private static EngineResumeHarness create(BoardHistoryList history) throws Exception {
      Config previousConfig = Lizzie.config;
      Board previousBoard = Lizzie.board;
      Leelaz previousLeelaz = Lizzie.leelaz;
      LizzieFrame previousFrame = Lizzie.frame;

      Config config = allocate(Config.class);
      config.alwaysSyncBoardStat = false;
      config.alwaysGotoLastOnLive = false;
      config.newMoveNumberInBranch = true;
      config.noCapture = false;
      config.readBoardPonder = true;
      config.winrateAlwaysBlack = false;
      Lizzie.config = config;

      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
      leelaz.canSuicidal = false;
      Lizzie.leelaz = leelaz;

      TrackingBoard board = allocate(TrackingBoard.class);
      board.initialize(history);
      Lizzie.board = board;

      TrackingFrame frame = allocate(TrackingFrame.class);
      frame.initialize(board);
      Lizzie.frame = frame;

      ReadBoard readBoard = allocate(ReadBoard.class);
      setField(readBoard, "conflictTracker", new SyncConflictTracker());
      setField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
      setField(readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
      setField(readBoard, "tempcount", new ArrayList<Integer>());
      readBoard.firstSync = false;
      frame.readBoard = readBoard;

      return new EngineResumeHarness(
          previousConfig,
          previousBoard,
          previousLeelaz,
          previousFrame,
          board,
          frame,
          leelaz,
          readBoard);
    }

    private void sync(int[] snapshotCodes) throws Exception {
      ArrayList<Integer> counts = new ArrayList<>(snapshotCodes.length);
      for (int code : snapshotCodes) {
        counts.add(code);
      }
      setField(readBoard, "tempcount", counts);
      invokeSyncBoardStones(readBoard);
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.frame = previousFrame;
    }
  }

  private static final class TrackingBoard extends Board {
    private void initialize(BoardHistoryList history) {
      setHistory(history);
      hasStartStone = false;
    }

    @Override
    public void clearAfterMove() {}

    @Override
    public void clear(boolean isEngineGame) {
      setHistory(rootHistory(emptyStones(), true));
      hasStartStone = false;
      if (Lizzie.frame != null && Lizzie.frame.readBoard != null) {
        Lizzie.frame.readBoard.firstSync = true;
      }
    }

    @Override
    public void placeForSync(int x, int y, Stone color, boolean newBranch) {
      getHistory().place(x, y, color, newBranch);
      if (Lizzie.frame != null && Lizzie.frame.readBoard != null) {
        Lizzie.frame.readBoard.lastMovePlayByLizzie = false;
      }
    }

    @Override
    public void moveToAnyPosition(BoardHistoryNode targetNode) {
      getHistory().setHead(targetNode);
    }

    @Override
    public boolean previousMove(boolean needRefresh) {
      Optional<BoardData> previous = getHistory().previous();
      return previous.isPresent();
    }

    @Override
    public void addStartListAll() {}

    @Override
    public void flatten() {}
  }

  private static final class TrackingFrame extends LizzieFrame {
    private int scheduleResumeAnalysisCount;
    private int stopAiPlayingAndPolicyCount;
    private int flashAnalyzeGameCount;
    private Runnable lastScheduledResumeAction;
    private TrackingBoard board;

    private void initialize(TrackingBoard board) {
      this.board = board;
      bothSync = false;
      syncBoard = false;
      isPlayingAgainstLeelaz = false;
      playerIsBlack = true;
    }

    @Override
    public void refresh() {}

    @Override
    public void flashAnalyzeGame(boolean isAllGame, boolean isAllBranches, boolean silentAnalyze) {
      flashAnalyzeGameCount++;
    }

    @Override
    public void renderVarTree(int vw, int vh, boolean changeSize, boolean needGetEnd) {}

    @Override
    public void lastMove() {
      board.getHistory().setHead(board.getHistory().getMainEnd());
    }

    @Override
    public void clearKataEstimate() {}

    @Override
    public void resetTitle() {}

    @Override
    public void clearTryPlay() {}

    @Override
    public boolean stopAiPlayingAndPolicy() {
      stopAiPlayingAndPolicyCount++;
      boolean wasGaming = isPlayingAgainstLeelaz || isAnaPlayingAgainstLeelaz;
      isPlayingAgainstLeelaz = false;
      isAnaPlayingAgainstLeelaz = false;
      if (Lizzie.leelaz != null) {
        Lizzie.leelaz.notPondering();
        Lizzie.leelaz.isThinking = false;
      }
      return wasGaming;
    }

    @Override
    public void scheduleResumeAnalysisAfterLoad(int delayMillis) {
      scheduleResumeAnalysisCount++;
      lastScheduledResumeAction = null;
    }

    public void scheduleResumeAnalysisAfterLoad(int delayMillis, Runnable action) {
      scheduleResumeAnalysisCount++;
      lastScheduledResumeAction = action;
    }
  }

  private static final class HistoryPath {
    private final List<BoardHistoryNode> nodes;

    private HistoryPath(List<BoardHistoryNode> nodes) {
      this.nodes = nodes;
    }
  }

  private static final class Placement {
    private final int x;
    private final int y;
    private final Stone color;

    private Placement(int x, int y, Stone color) {
      this.x = x;
      this.y = y;
      this.color = color;
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
