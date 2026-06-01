package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OnlineDialogYikeSessionStateTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void pendingRoomDoesNotBecomeActiveUntilSyncAndGeometryAreBothReady() {
    OnlineDialog.YikeSessionState active =
        OnlineDialog.YikeSessionState.active("unite-board:66304678");
    OnlineDialog.YikeSessionState pending =
        OnlineDialog.YikeSessionState.pending("live-room:186538");

    pending = pending.withGeometryReady(true);
    assertFalse(OnlineDialog.shouldPromotePendingSession(active, pending));

    pending = pending.withSyncReady(true);
    assertTrue(OnlineDialog.shouldPromotePendingSession(active, pending));
  }

  @Test
  void waitingPageInvalidatesPlacementGeometryWithoutClearingDisplaySession() {
    assertTrue(
        OnlineDialog.shouldInvalidateYikePlacementGeometry("https://home.yikeweiqi.com/#/live"));
    assertTrue(
        OnlineDialog.shouldInvalidateYikePlacementGeometry("https://home.yikeweiqi.com/#/game"));
    assertFalse(
        OnlineDialog.shouldInvalidateYikePlacementGeometry(
            "https://home.yikeweiqi.com/#/unite/66304678"));
  }

  @Test
  void geometryFromDifferentSessionKeyIsRejected() {
    assertFalse(
        OnlineDialog.isGeometryForCurrentSession("live-room:186538", "unite-board:66304678"));
    assertTrue(OnlineDialog.isGeometryForCurrentSession("live-room:186538", "live-room:186538"));
  }

  @Test
  void waitingClearIsIgnoredWhenBrowserAlreadyOnBoardPage() {
    assertFalse(
        OnlineDialog.shouldApplyYikeClearEnvelope(
            "https://home.yikeweiqi.com/#/live",
            "https://home.yikeweiqi.com/#/live/new-room/186638/0/0",
            "https://home.yikeweiqi.com/#/live/new-room/186638/0/0",
            1000));
  }

  @Test
  void waitingClearIsIgnoredDuringRecentSyncBootstrapWindow() {
    assertFalse(
        OnlineDialog.shouldApplyYikeClearEnvelope(
            "https://home.yikeweiqi.com/#/live",
            "https://home.yikeweiqi.com/#/live",
            "https://home.yikeweiqi.com/#/live/new-room/186638/0/0",
            1200));
    assertTrue(
        OnlineDialog.shouldApplyYikeClearEnvelope(
            "https://home.yikeweiqi.com/#/live",
            "https://home.yikeweiqi.com/#/live",
            "https://home.yikeweiqi.com/#/live/new-room/186638/0/0",
            6000));
  }

  @Test
  void yikeRefreshUsesConfiguredSecondsWithoutFiveSecondFloor() {
    assertEquals(3, OnlineDialog.normalizeYikeRefreshSeconds(3));
    assertEquals(1, OnlineDialog.normalizeYikeRefreshSeconds(1));
    assertEquals(1, OnlineDialog.normalizeYikeRefreshSeconds(0));
  }

  @Test
  void staleLiveFetchResultIsRejectedAfterStopOrSessionChange() {
    assertFalse(OnlineDialog.shouldAcceptYikeLiveFetchResult(true, true, 6, 6, 2, 2));
    assertFalse(OnlineDialog.shouldAcceptYikeLiveFetchResult(false, false, 6, 6, 2, 2));
    assertFalse(OnlineDialog.shouldAcceptYikeLiveFetchResult(false, true, 7, 6, 2, 2));
    assertFalse(OnlineDialog.shouldAcceptYikeLiveFetchResult(false, true, 6, 6, 3, 2));
    assertTrue(OnlineDialog.shouldAcceptYikeLiveFetchResult(false, true, 6, 6, 2, 2));
  }

  @Test
  void yikeMainlineReplacementPreservesExistingAnalysisPayload() {
    try (BoardSizeScope ignored = BoardSizeScope.open()) {
      BoardHistoryList existing = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode existingMove =
          append(existing.getStart(), moveData(0, 0, Stone.BLACK, false, 1, 64.5, 240));
      existingMove.getData().scoreMean = 2.75;
      existingMove.getData().engineName = "cached-analysis";
      existingMove.getData().comment = "old analysis comment";
      existingMove.nodeInfo.analyzed = true;
      existingMove.nodeInfo.moveNum = 1;

      BoardHistoryList incoming = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode incomingMove =
          append(incoming.getStart(), moveData(0, 0, Stone.BLACK, false, 1, 50.0, 0));
      incomingMove.getData().comment = "fresh yike comment";

      int preserved = OnlineDialog.preserveYikeMainlineAnalysis(existing, incoming);

      assertEquals(1, preserved);
      assertEquals(240, incomingMove.getData().getPlayouts());
      assertEquals(64.5, incomingMove.getData().winrate, 0.001);
      assertEquals(2.75, incomingMove.getData().scoreMean, 0.001);
      assertEquals("cached-analysis", incomingMove.getData().engineName);
      assertEquals(
          "fresh yike comment",
          incomingMove.getData().comment,
          "Yike SGF comments should not be overwritten by preserved AI comments.");
      assertTrue(incomingMove.nodeInfo.analyzed);
      assertEquals(1, incomingMove.nodeInfo.moveNum);
    }
  }

  @Test
  void yikeMainlinePreserveLeavesNewMovesMissingForBackgroundCompletion() {
    try (BoardSizeScope ignored = BoardSizeScope.open()) {
      BoardHistoryList existing = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      append(existing.getStart(), moveData(0, 0, Stone.BLACK, false, 1, 61.0, 180));

      BoardHistoryList incoming = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode first =
          append(incoming.getStart(), moveData(0, 0, Stone.BLACK, false, 1, 50.0, 0));
      BoardHistoryNode second = append(first, moveData(1, 0, Stone.WHITE, true, 2, 50.0, 0));

      int preserved = OnlineDialog.preserveYikeMainlineAnalysis(existing, incoming);

      assertEquals(1, preserved);
      assertEquals(180, first.getData().getPlayouts());
      assertEquals(0, second.getData().getPlayouts());
      assertEquals(1, OnlineDialog.countMissingYikeCurveNodes(incoming));
    }
  }

  @Test
  void yikeCurveCompletionCountsOnlyMissingMainlineActionNodes() {
    try (BoardSizeScope ignored = BoardSizeScope.open()) {
      BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
      BoardHistoryNode root = history.getStart();
      BoardHistoryNode analyzed = append(root, moveData(0, 0, Stone.BLACK, false, 1, 58.0, 100));
      BoardData dummyPass =
          BoardData.pass(
              stonesWith(0, 0, Stone.BLACK),
              Stone.WHITE,
              true,
              new Zobrist(),
              2,
              moveList(0, 0, 1),
              0,
              0,
              50,
              0);
      dummyPass.dummy = true;
      BoardHistoryNode dummy = append(analyzed, dummyPass);
      append(dummy, moveData(1, 1, Stone.BLACK, false, 3, 50.0, 0));

      BoardHistoryNode branch = new BoardHistoryNode(moveData(2, 2, Stone.WHITE, true, 1, 50.0, 0));
      root.getVariations().add(branch);
      root.setPreviousForChild(branch);

      assertEquals(1, OnlineDialog.countMissingYikeCurveNodes(history));
    }
  }

  private static BoardData moveData(
      int x,
      int y,
      Stone color,
      boolean blackToPlay,
      int moveNumber,
      double winrate,
      int playouts) {
    Stone[] stones = stonesWith(x, y, color);
    return BoardData.move(
        stones,
        new int[] {x, y},
        color,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        moveList(x, y, moveNumber),
        0,
        0,
        winrate,
        playouts);
  }

  private static BoardHistoryNode append(BoardHistoryNode parent, BoardData data) {
    return parent.add(new BoardHistoryNode(data));
  }

  private static Stone[] stonesWith(int x, int y, Stone color) {
    Stone[] stones = new Stone[BOARD_AREA];
    Arrays.fill(stones, Stone.EMPTY);
    stones[Board.getIndex(x, y)] = color;
    return stones;
  }

  private static int[] moveList(int x, int y, int moveNumber) {
    int[] moves = new int[BOARD_AREA];
    moves[Board.getIndex(x, y)] = moveNumber;
    return moves;
  }

  private static final class BoardSizeScope implements AutoCloseable {
    private final int previousWidth;
    private final int previousHeight;

    private BoardSizeScope(int previousWidth, int previousHeight) {
      this.previousWidth = previousWidth;
      this.previousHeight = previousHeight;
    }

    private static BoardSizeScope open() {
      BoardSizeScope scope = new BoardSizeScope(Board.boardWidth, Board.boardHeight);
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      return scope;
    }

    @Override
    public void close() {
      Board.boardWidth = previousWidth;
      Board.boardHeight = previousHeight;
      Zobrist.init();
    }
  }
}
