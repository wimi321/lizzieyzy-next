package featurecat.lizzie.rules;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.gui.BoardRenderer;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.WinrateGraph;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

/**
 * Minimal headless fixture for Board / SGFParser rules tests. Stubs GUI and engine callbacks so
 * play, capture, history jumps, parse, and save can run without a window, network, or KataGo.
 */
final class RulesLayerTestHarness implements AutoCloseable {
  private final int previousBoardWidth;
  private final int previousBoardHeight;
  private final Board previousBoard;
  private final LizzieFrame previousFrame;
  private final Menu previousMenu;
  private final WinrateGraph previousWinrateGraph;
  private final BoardRenderer previousBoardRenderer;
  private final Leelaz previousLeelaz;
  private final Config previousConfig;
  private final boolean previousEngineEmpty;
  private final boolean previousEngineGame;
  private final boolean previousPreEngineGame;
  private final boolean previousSavingRaw;
  private final boolean previousUrlSgf;
  private final featurecat.lizzie.analysis.EngineFollowController previousEngineFollowController;

  private RulesLayerTestHarness(
      int previousBoardWidth,
      int previousBoardHeight,
      Board previousBoard,
      LizzieFrame previousFrame,
      Menu previousMenu,
      WinrateGraph previousWinrateGraph,
      BoardRenderer previousBoardRenderer,
      Leelaz previousLeelaz,
      Config previousConfig,
      boolean previousEngineEmpty,
      boolean previousEngineGame,
      boolean previousPreEngineGame,
      boolean previousSavingRaw,
      boolean previousUrlSgf,
      featurecat.lizzie.analysis.EngineFollowController previousEngineFollowController) {
    this.previousBoardWidth = previousBoardWidth;
    this.previousBoardHeight = previousBoardHeight;
    this.previousBoard = previousBoard;
    this.previousFrame = previousFrame;
    this.previousMenu = previousMenu;
    this.previousWinrateGraph = previousWinrateGraph;
    this.previousBoardRenderer = previousBoardRenderer;
    this.previousLeelaz = previousLeelaz;
    this.previousConfig = previousConfig;
    this.previousEngineEmpty = previousEngineEmpty;
    this.previousEngineGame = previousEngineGame;
    this.previousPreEngineGame = previousPreEngineGame;
    this.previousSavingRaw = previousSavingRaw;
    this.previousUrlSgf = previousUrlSgf;
    this.previousEngineFollowController = previousEngineFollowController;
  }

  static RulesLayerTestHarness open() throws Exception {
    return open(5);
  }

  static RulesLayerTestHarness open(int boardSize) throws Exception {
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Board previousBoard = Lizzie.board;
    LizzieFrame previousFrame = Lizzie.frame;
    Menu previousMenu = LizzieFrame.menu;
    WinrateGraph previousWinrateGraph = LizzieFrame.winrateGraph;
    BoardRenderer previousBoardRenderer = LizzieFrame.boardRenderer;
    Leelaz previousLeelaz = Lizzie.leelaz;
    Config previousConfig = Lizzie.config;
    boolean previousEngineEmpty = EngineManager.isEmpty;
    boolean previousEngineGame = EngineManager.isEngineGame;
    boolean previousPreEngineGame = EngineManager.isPreEngineGame;
    boolean previousSavingRaw = LizzieFrame.isSavingRaw;
    boolean previousUrlSgf = LizzieFrame.urlSgf;
    featurecat.lizzie.analysis.EngineFollowController previousEngineFollowController =
        Lizzie.engineFollowController;

    Board.boardWidth = boardSize;
    Board.boardHeight = boardSize;
    Zobrist.init();

    TrackingBoard board = allocate(TrackingBoard.class);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    board.movelistwr = new ArrayList<>();
    board.setHistory(new BoardHistoryList(BoardData.empty(boardSize, boardSize)));
    Lizzie.board = board;

    TrackingFrame frame = allocate(TrackingFrame.class);
    Lizzie.frame = frame;
    LizzieFrame.menu = allocate(Menu.class);
    LizzieFrame.menu.txtKomi = new javax.swing.JTextField();
    LizzieFrame.winrateGraph = allocate(WinrateGraph.class);
    LizzieFrame.boardRenderer = allocate(NoOpBoardRenderer.class);
    LizzieFrame.isSavingRaw = false;
    LizzieFrame.urlSgf = false;

    TrackingLeelaz leelaz = new TrackingLeelaz();
    Lizzie.setPrimaryEngine(leelaz);
    EngineManager.isEmpty = true;
    EngineManager.isEngineGame = false;
    EngineManager.isPreEngineGame = false;
    Lizzie.engineFollowController = null;

    Config config = allocate(Config.class);
    config.playSound = false;
    config.noCapture = false;
    config.readKomi = true;
    config.appendWinrateToComment = false;
    config.showComment = true;
    config.newMoveNumberInBranch = false;
    config.noRefreshOnMouseMove = false;
    config.initialMaxScoreLead = 10;
    Lizzie.config = config;

    return new RulesLayerTestHarness(
        previousBoardWidth,
        previousBoardHeight,
        previousBoard,
        previousFrame,
        previousMenu,
        previousWinrateGraph,
        previousBoardRenderer,
        previousLeelaz,
        previousConfig,
        previousEngineEmpty,
        previousEngineGame,
        previousPreEngineGame,
        previousSavingRaw,
        previousUrlSgf,
        previousEngineFollowController);
  }

  Board board() {
    return Lizzie.board;
  }

  Stone stoneAt(int x, int y) {
    return Lizzie.board.getHistory().getStones()[Board.getIndex(x, y)];
  }

  BoardHistoryNode current() {
    return Lizzie.board.getHistory().getCurrentHistoryNode();
  }

  @Override
  public void close() {
    Board.boardWidth = previousBoardWidth;
    Board.boardHeight = previousBoardHeight;
    Zobrist.init();
    Lizzie.board = previousBoard;
    Lizzie.frame = previousFrame;
    LizzieFrame.menu = previousMenu;
    LizzieFrame.winrateGraph = previousWinrateGraph;
    LizzieFrame.boardRenderer = previousBoardRenderer;
    Lizzie.setPrimaryEngine(previousLeelaz);
    Lizzie.config = previousConfig;
    EngineManager.isEmpty = previousEngineEmpty;
    EngineManager.isEngineGame = previousEngineGame;
    EngineManager.isPreEngineGame = previousPreEngineGame;
    LizzieFrame.isSavingRaw = previousSavingRaw;
    LizzieFrame.urlSgf = previousUrlSgf;
    Lizzie.engineFollowController = previousEngineFollowController;
  }

  static boolean sameLastMove(BoardData data, int x, int y) {
    Optional<int[]> lastMove = data.lastMove;
    return lastMove.isPresent() && Arrays.equals(lastMove.get(), new int[] {x, y});
  }

  @SuppressWarnings("unchecked")
  static <T> T allocate(Class<T> type) {
    try {
      return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
    } catch (InstantiationException ex) {
      throw new IllegalStateException("Failed to allocate " + type.getSimpleName(), ex);
    }
  }

  static final class TrackingBoard extends Board {
    @Override
    public void clearAfterMove() {}
  }

  static final class TrackingFrame extends LizzieFrame {
    @Override
    public void refresh() {}

    @Override
    public void refresh(int mode) {}

    @Override
    public void onMainEnginePonder() {}

    @Override
    public void setPlayers(String whitePlayer, String blackPlayer) {}

    @Override
    public void resetTitle() {}

    @Override
    public void tryToResetByoTime() {}

    @Override
    public void setResult(String result) {}

    @Override
    public void requestProblemListRefresh() {}

    @Override
    public void refreshProblemListSnapshot() {}

    @Override
    public void clearKataEstimate() {}
  }

  static final class NoOpBoardRenderer extends BoardRenderer {
    private NoOpBoardRenderer() {
      super(false);
    }

    @Override
    public void removedrawmovestone() {}
  }

  static final class TrackingLeelaz extends Leelaz {
    TrackingLeelaz() throws IOException {
      super("");
    }

    @Override
    public void playMove(Stone color, String move) {}

    @Override
    public void playMove(Stone color, String move, boolean addPlayer, boolean blackToPlay) {}

    @Override
    public void undo() {}

    @Override
    public void undo(boolean addPlayer, boolean blackToPlay) {}

    @Override
    public void clearBestMoves() {}

    @Override
    public void maybeAjustPDA(BoardHistoryNode node) {}

    @Override
    public void modifyStart() {}

    @Override
    public void setModifyEnd() {}

    @Override
    public boolean isLoaded() {
      return false;
    }

    @Override
    public boolean isStarted() {
      return false;
    }

    @Override
    public boolean isPondering() {
      return false;
    }

    @Override
    public void clearPonderLimit() {}
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
