package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Branch;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class SnapshotNodeRenderGateTest {
  private static final int BOARD_SIZE = 2;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;
  private static final int CANVAS_SIZE = 120;
  private static final int STONE_RADIUS = 12;
  private static final int SCALED_MARGIN = 20;
  private static final int SQUARE_SIZE = 40;

  @Test
  void mainBoardCurrentSnapshotWithMarkerDrawsNoOverlay() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardData snapshot = snapshotWithMarker(new int[] {0, 0}, Stone.BLACK, false, 59);
      Board board = boardWithRoot(snapshot);
      Lizzie.board = board;

      BoardRenderer renderer = new BoardRenderer(false);
      configureOverlayRenderer(renderer);

      assertTrue(snapshot.isSnapshotNode(), "fixture should use an explicit SNAPSHOT node.");
      assertFalse(
          hasVisiblePaint(renderOverlay(renderer, BoardRenderer.class, "drawMoveNumbers")),
          "main board should suppress guessed last-move overlays for SNAPSHOT nodes.");
    } finally {
      env.close();
    }
  }

  @Test
  void branchSnapshotWithMarkerDrawsNoOverlayOnMainAndSubBoards() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = boardWithRoot(snapshotWithMarker(new int[] {1, 1}, Stone.WHITE, true, 58));
      Lizzie.board = board;
      Branch branch = branchWith(snapshotWithMarker(new int[] {0, 0}, Stone.BLACK, false, 59));

      BoardRenderer mainBoard = new BoardRenderer(false);
      mainBoard.branchOpt = Optional.of(branch);
      configureOverlayRenderer(mainBoard);

      SubBoardRenderer subBoard = new SubBoardRenderer(false);
      subBoard.branchOpt = Optional.of(branch);
      configureOverlayRenderer(subBoard);

      assertFalse(
          hasVisiblePaint(renderOverlay(mainBoard, BoardRenderer.class, "drawMoveNumbers")),
          "main board branch previews should treat SNAPSHOT as board-only state.");
      assertFalse(
          hasVisiblePaint(renderOverlay(subBoard, SubBoardRenderer.class, "drawMoveNumbers")),
          "sub board branch previews should treat SNAPSHOT as board-only state.");
    } finally {
      env.close();
    }
  }

  @Test
  void floatBoardBranchSnapshotWithMarkerDrawsNoOverlay() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Board board = boardWithRoot(snapshotWithMarker(new int[] {1, 1}, Stone.WHITE, true, 58));
      Lizzie.board = board;
      Branch branch = branchWith(snapshotWithMarker(new int[] {0, 0}, Stone.BLACK, false, 59));

      FloatBoardRenderer floatBoard = new FloatBoardRenderer();
      floatBoard.branchOpt = Optional.of(branch);
      configureOverlayRenderer(floatBoard);

      assertFalse(
          hasVisiblePaint(renderOverlay(floatBoard, FloatBoardRenderer.class, "drawMoveNumbers")),
          "float board branch previews should treat SNAPSHOT as board-only state.");
    } finally {
      env.close();
    }
  }

  private static BufferedImage renderOverlay(Object renderer, Class<?> type, String methodName)
      throws Exception {
    BufferedImage image = new BufferedImage(CANVAS_SIZE, CANVAS_SIZE, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      Method method = type.getDeclaredMethod(methodName, Graphics2D.class);
      method.setAccessible(true);
      method.invoke(renderer, graphics);
      return image;
    } finally {
      graphics.dispose();
    }
  }

  private static boolean hasVisiblePaint(BufferedImage image) {
    for (int x = 0; x < image.getWidth(); x++) {
      for (int y = 0; y < image.getHeight(); y++) {
        if (((image.getRGB(x, y) >>> 24) & 0xFF) > 0) {
          return true;
        }
      }
    }
    return false;
  }

  private static void configureOverlayRenderer(Object renderer) throws Exception {
    setIntField(renderer, "x", 0);
    setIntField(renderer, "y", 0);
    setIntField(renderer, "boardWidth", CANVAS_SIZE);
    setIntField(renderer, "boardHeight", CANVAS_SIZE);
    setIntField(renderer, "stoneRadius", STONE_RADIUS);
    setIntField(renderer, "scaledMarginWidth", SCALED_MARGIN);
    setIntField(renderer, "scaledMarginHeight", SCALED_MARGIN);
    setIntField(renderer, "squareWidth", SQUARE_SIZE);
    setIntField(renderer, "squareHeight", SQUARE_SIZE);
  }

  private static void setIntField(Object target, String name, int value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.setInt(target, value);
  }

  private static BoardData snapshotWithMarker(
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

  private static int[] moveList(int x, int y, int moveNumber) {
    int[] moveNumberList = new int[BOARD_AREA];
    moveNumberList[Board.getIndex(x, y)] = moveNumber;
    return moveNumberList;
  }

  private static Board boardWithRoot(BoardData root) throws Exception {
    Board board = allocate(Board.class);
    board.setHistory(new BoardHistoryList(root));
    return board;
  }

  private static Branch branchWith(BoardData data) throws Exception {
    Branch branch = allocate(Branch.class);
    branch.data = data;
    branch.length = data.moveNumber;
    branch.isNewStone = new boolean[BOARD_AREA];
    branch.pvVisitsList = new int[BOARD_AREA];
    return branch;
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final ResourceBundle previousResourceBundle;
    private final Font previousUiFont;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame,
        ResourceBundle previousResourceBundle,
        Font previousUiFont) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousResourceBundle = previousResourceBundle;
      this.previousUiFont = previousUiFont;
    }

    private static TestEnvironment open() throws Exception {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Config previousConfig = Lizzie.config;
      Board previousBoard = Lizzie.board;
      LizzieFrame previousFrame = Lizzie.frame;
      ResourceBundle previousResourceBundle = Lizzie.resourceBundle;
      Font previousUiFont = LizzieFrame.uiFont;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      Config config = allocate(Config.class);
      config.allowMoveNumber = 0;
      config.showMoveAllInBranch = false;
      config.showPreviousBestmovesInEngineGame = false;
      config.removeDeadChainInVariation = false;
      config.noCapture = false;
      config.showPvVisitsAllMove = false;
      config.showPvVisitsLastMove = false;
      config.persisted = new JSONObject().put("ui-persist", new JSONObject().put("max-alpha", 240));
      Lizzie.config = config;
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      LizzieFrame.uiFont = new Font("Dialog", Font.PLAIN, 12);

      LizzieFrame frame = allocate(LizzieFrame.class);
      frame.isTrying = false;
      Lizzie.frame = frame;

      return new TestEnvironment(
          previousBoardWidth,
          previousBoardHeight,
          previousConfig,
          previousBoard,
          previousFrame,
          previousResourceBundle,
          previousUiFont);
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.resourceBundle = previousResourceBundle;
      LizzieFrame.uiFont = previousUiFont;
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
