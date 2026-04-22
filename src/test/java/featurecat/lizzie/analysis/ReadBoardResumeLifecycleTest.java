package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadBoardResumeLifecycleTest {
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
  void externalSetHistoryClearsResumeState() throws Exception {
    try (ResumeHarness harness = ResumeHarness.create(rootHistory(emptyStones(), true))) {
      BoardHistoryNode mainEnd = buildHistory(harness.board, placement(0, 0, Stone.BLACK)).get(0);
      armResumeState(harness.readBoard, mainEnd, 1);

      harness.board.setHistory(rootHistory(stones(placement(1, 1, Stone.WHITE)), false));

      assertNull(getField(harness.readBoard, "resumeState"));
      assertNull(getField(harness.readBoard, "lastResolvedSnapshotNode"));
    }
  }

  @Test
  void externalClearClearsResumeState() throws Exception {
    try (ResumeHarness harness = ResumeHarness.create(rootHistory(emptyStones(), true))) {
      BoardHistoryNode mainEnd = buildHistory(harness.board, placement(0, 0, Stone.BLACK)).get(0);
      armResumeState(harness.readBoard, mainEnd, 1);

      harness.board.clear(false);

      assertNull(getField(harness.readBoard, "resumeState"));
      assertNull(getField(harness.readBoard, "lastResolvedSnapshotNode"));
    }
  }

  @Test
  void loadFromStringClearsResumeState() throws Exception {
    try (ResumeHarness harness = ResumeHarness.create(rootHistory(emptyStones(), true))) {
      BoardHistoryNode mainEnd = buildHistory(harness.board, placement(0, 0, Stone.BLACK)).get(0);
      armResumeState(harness.readBoard, mainEnd, 1);

      assertTrue(SGFParser.loadFromString("(;FF[4]SZ[3];B[aa])"));

      assertNull(getField(harness.readBoard, "resumeState"));
      assertNull(getField(harness.readBoard, "lastResolvedSnapshotNode"));
    }
  }

  @Test
  void readBoardForceRebuildKeepsFreshResumeState() throws Exception {
    try (ResumeHarness harness = ResumeHarness.create(rootHistory(emptyStones(), true))) {
      Stone[] target = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));

      harness.readBoard.parseLine("forceRebuild");
      harness.sync(snapshot(target, Optional.of(new int[] {1, 0}), Stone.WHITE));

      SyncResumeState resumeState = (SyncResumeState) getField(harness.readBoard, "resumeState");
      BoardHistoryNode mainEnd = harness.board.getHistory().getMainEnd();

      assertNotNull(resumeState);
      assertSame(mainEnd, resumeState.node);
      assertSame(mainEnd, getField(harness.readBoard, "lastResolvedSnapshotNode"));
    }
  }

  @Test
  void ordinaryIncrementalSyncAdvancesResumeStateToNewMainEnd() throws Exception {
    try (ResumeHarness harness = ResumeHarness.create(rootHistory(emptyStones(), true))) {
      harness.readBoard.parseLine("syncPlatform fox");
      harness.readBoard.parseLine("foxMoveNumber 1");
      harness.sync(
          snapshot(
              stones(placement(0, 0, Stone.BLACK)), Optional.of(new int[] {0, 0}), Stone.BLACK));

      SyncResumeState resumeState = (SyncResumeState) getField(harness.readBoard, "resumeState");
      BoardHistoryNode mainEnd = harness.board.getHistory().getMainEnd();

      assertNotNull(resumeState, "successful incremental sync should arm resumeState.");
      assertSame(mainEnd, resumeState.node, "resumeState should track the newest proven node.");
      assertSame(mainEnd, getField(harness.readBoard, "lastResolvedSnapshotNode"));
    }
  }

  private static void armResumeState(ReadBoard readBoard, BoardHistoryNode node, int moveNumber)
      throws Exception {
    SyncResumeState resumeState =
        new SyncResumeState(
            node,
            SyncRemoteContext.forFoxLive(
                OptionalInt.of(moveNumber), "43581号", OptionalInt.of(moveNumber), false));
    setField(readBoard, "resumeState", resumeState);
    setField(readBoard, "lastResolvedSnapshotNode", node);
  }

  private static List<BoardHistoryNode> buildHistory(TrackingBoard board, Placement... moves) {
    List<BoardHistoryNode> nodes = new ArrayList<>();
    for (Placement move : moves) {
      board.getHistory().place(move.x, move.y, move.color, false);
      nodes.add(board.getHistory().getCurrentHistoryNode());
    }
    return nodes;
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

  private static Object getField(Object target, String name) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    return field.get(target);
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

  private static final class ResumeHarness implements AutoCloseable {
    private final Config previousConfig;
    private final Board previousBoard;
    private final Leelaz previousLeelaz;
    private final LizzieFrame previousFrame;
    private final TrackingBoard board;
    private final TrackingFrame frame;
    private final SnapshotTrackingLeelaz leelaz;
    private final ReadBoard readBoard;

    private ResumeHarness(
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

    private static ResumeHarness create(BoardHistoryList history) throws Exception {
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

      return new ResumeHarness(
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
      notifyHistoryOverwriteIfAvailable();
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

    private void notifyHistoryOverwriteIfAvailable() {
      try {
        Method method = Board.class.getDeclaredMethod("notifyReadBoardHistoryOverwritten");
        method.setAccessible(true);
        method.invoke(this);
      } catch (NoSuchMethodException ignored) {
        // Current production code has not added the hook yet.
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Failed to invoke Board history overwrite hook", ex);
      }
    }
  }

  private static final class TrackingFrame extends LizzieFrame {
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
