package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Branch;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PassPreviewGateTest {
  private static final int BOARD_SIZE = 2;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;
  private static final int PREVIEW_CANVAS_SIZE = 100;
  private static final int PREVIEW_STONE_RADIUS = 10;
  private static final Color MAIN_BRANCH_PASS_FILL = new Color(255, 255, 255, 80);
  private static final Color SUB_BRANCH_PASS_FILL = new Color(255, 255, 255, 150);

  @Test
  void explicitSnapshotPreviewNeverPaintsPassOnMainAndSubBoards() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardData boardData = passData(Stone.WHITE, true, 60);
      BoardData previewData = snapshotData(Optional.empty(), Stone.EMPTY, false, 59);
      Board board = boardWithRoot(boardData);
      Branch branch = branchWith(previewData);
      Lizzie.board = board;

      BoardRenderer mainBoard = new BoardRenderer(false);
      mainBoard.branchOpt = Optional.of(branch);
      configurePassPreviewRenderer(mainBoard);
      SubBoardRenderer subBoard = new SubBoardRenderer(false);
      subBoard.branchOpt = Optional.of(branch);
      configurePassPreviewRenderer(subBoard);

      assertTrue(previewData.isSnapshotNode(), "fixture should use an explicit SNAPSHOT node.");
      assertFalse(
          hasVisiblePaint(renderMainBoardPassPreview(mainBoard, board)),
          "main board should keep snapshot previews free of PASS paint.");
      assertFalse(
          hasVisiblePaint(renderSubBoardPassPreview(subBoard)),
          "sub board should keep snapshot previews free of PASS paint.");
    } finally {
      env.close();
    }
  }

  @Test
  void explicitPassPreviewStillPaintsPassOnMainAndSubBoards() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      BoardData currentData = moveData(new int[] {0, 0}, Stone.BLACK, false, 59);
      BoardData previewData = passData(Stone.WHITE, true, 60);
      Board board = boardWithRoot(currentData);
      Branch branch = branchWith(previewData);
      Lizzie.board = board;

      BoardRenderer mainBoard = new BoardRenderer(false);
      mainBoard.branchOpt = Optional.of(branch);
      configurePassPreviewRenderer(mainBoard);

      SubBoardRenderer subBoard = new SubBoardRenderer(false);
      subBoard.branchOpt = Optional.of(branch);
      configurePassPreviewRenderer(subBoard);

      assertEquals(
          MAIN_BRANCH_PASS_FILL.getRGB(),
          renderMainBoardPassPreview(mainBoard, board).getRGB(),
          "main board should still paint explicit PASS previews.");
      assertEquals(
          SUB_BRANCH_PASS_FILL.getRGB(),
          renderSubBoardPassPreview(subBoard).getRGB(),
          "sub board should still paint explicit PASS previews.");
    } finally {
      env.close();
    }
  }

  @Test
  void removedStoneCurrentNodeDoesNotHideBranchPassPreviewOnMainAndSubBoards() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      assertBranchPassPreviewRendersThroughDrawPath(BoardHistoryNode::setRemovedStone);
    } finally {
      env.close();
    }
  }

  @Test
  void extraStonesCurrentNodeDoesNotHideBranchPassPreviewOnMainAndSubBoards() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      assertBranchPassPreviewRendersThroughDrawPath(node -> node.addExtraStones(1, 1, true));
    } finally {
      env.close();
    }
  }

  private static void assertBranchPassPreviewRendersThroughDrawPath(Consumer<BoardHistoryNode> edit)
      throws Exception {
    BoardData currentData = moveData(new int[] {0, 0}, Stone.BLACK, false, 59);
    BoardData previewData = passData(Stone.WHITE, true, 60);
    Board board = boardWithRoot(currentData);
    BoardHistoryNode currentNode = board.getHistory().getCurrentHistoryNode();
    Branch branch = branchWith(previewData);

    Lizzie.board = board;
    edit.accept(currentNode);

    BoardRenderer mainBoard = new BoardRenderer(false);
    mainBoard.branchOpt = Optional.of(branch);
    configurePassPreviewRenderer(mainBoard);

    SubBoardRenderer subBoard = new SubBoardRenderer(false);
    subBoard.branchOpt = Optional.of(branch);
    configurePassPreviewRenderer(subBoard);

    assertEquals(
        MAIN_BRANCH_PASS_FILL.getRGB(),
        renderMainBoardPassPreview(mainBoard, board).getRGB(),
        "Main board should paint the branch pass preview color even when current-node metadata exists.");
    assertEquals(
        SUB_BRANCH_PASS_FILL.getRGB(),
        renderSubBoardPassPreview(subBoard).getRGB(),
        "Sub board should paint the branch pass preview color even when current-node metadata exists.");
  }

  private static void configurePassPreviewRenderer(Object renderer) throws Exception {
    setIntField(renderer, "x", 0);
    setIntField(renderer, "y", 0);
    setIntField(renderer, "boardWidth", PREVIEW_CANVAS_SIZE);
    setIntField(renderer, "boardHeight", PREVIEW_CANVAS_SIZE);
    setIntField(renderer, "stoneRadius", PREVIEW_STONE_RADIUS);
  }

  private static Color renderMainBoardPassPreview(BoardRenderer renderer, Board board)
      throws Exception {
    BufferedImage image =
        new BufferedImage(PREVIEW_CANVAS_SIZE, PREVIEW_CANVAS_SIZE, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      Method method =
          BoardRenderer.class.getDeclaredMethod(
              "drawMoveRankMark", Graphics2D.class, BoardHistoryNode.class);
      method.setAccessible(true);
      method.invoke(renderer, graphics, board.getHistory().getCurrentHistoryNode());
      return samplePassPreviewFill(image);
    } finally {
      graphics.dispose();
    }
  }

  private static Color renderSubBoardPassPreview(SubBoardRenderer renderer) throws Exception {
    BufferedImage image =
        new BufferedImage(PREVIEW_CANVAS_SIZE, PREVIEW_CANVAS_SIZE, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      Method method = SubBoardRenderer.class.getDeclaredMethod("drawMoveNumbers", Graphics2D.class);
      method.setAccessible(true);
      method.invoke(renderer, graphics);
      return samplePassPreviewFill(image);
    } finally {
      graphics.dispose();
    }
  }

  private static Color samplePassPreviewFill(BufferedImage image) {
    int center = PREVIEW_CANVAS_SIZE / 2;
    int sampleY = center - PREVIEW_STONE_RADIUS * 2;
    return new Color(image.getRGB(center, sampleY), true);
  }

  private static boolean hasVisiblePaint(Color color) {
    return color.getAlpha() > 0;
  }

  private static void setIntField(Object target, String name, int value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.setInt(target, value);
  }

  private static Branch branchWith(BoardData data) throws Exception {
    Branch branch = allocate(Branch.class);
    branch.data = data;
    return branch;
  }

  private static Board boardWithRoot(BoardData root) throws Exception {
    Board board = allocate(Board.class);
    board.setHistory(new BoardHistoryList(root));
    return board;
  }

  private static BoardData moveData(
      int[] lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(lastMove[0], lastMove[1])] = lastMoveColor;
    int[] moveNumberList = new int[BOARD_AREA];
    moveNumberList[Board.getIndex(lastMove[0], lastMove[1])] = moveNumber;
    return BoardData.move(
        stones,
        lastMove,
        lastMoveColor,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        moveNumberList,
        0,
        0,
        50,
        0);
  }

  private static BoardData passData(Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    return BoardData.pass(
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
  }

  private static BoardData snapshotData(
      Optional<int[]> lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    Stone[] stones = emptyStones();
    lastMove.ifPresent(coords -> stones[Board.getIndex(coords[0], coords[1])] = lastMoveColor);
    return BoardData.snapshot(
        stones,
        lastMove,
        lastMoveColor,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        new int[BOARD_AREA],
        0,
        0,
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

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final ResourceBundle previousResourceBundle;
    private final Font previousUiFont;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        ResourceBundle previousResourceBundle,
        Font previousUiFont) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousResourceBundle = previousResourceBundle;
      this.previousUiFont = previousUiFont;
    }

    private static TestEnvironment open() throws Exception {
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      Config previousConfig = Lizzie.config;
      Board previousBoard = Lizzie.board;
      ResourceBundle previousResourceBundle = Lizzie.resourceBundle;
      Font previousUiFont = LizzieFrame.uiFont;

      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();

      Config config = allocate(Config.class);
      config.persisted = new JSONObject().put("ui-persist", new JSONObject().put("max-alpha", 240));
      Lizzie.config = config;
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      LizzieFrame.uiFont = new Font("Dialog", Font.PLAIN, 12);
      return new TestEnvironment(
          previousBoardWidth,
          previousBoardHeight,
          previousConfig,
          previousBoard,
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
