package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.SyncDiagnosticsRecorder;
import featurecat.lizzie.analysis.YikeSessionDiagnosticsSnapshot;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import javax.swing.JTextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class OnlineDialogYikeSessionStateTest {
  private static final int BOARD_SIZE = 3;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @BeforeEach
  void resetDiagnosticsBeforeTest() {
    SyncDiagnosticsRecorder.clearDefaultForTests();
  }

  @AfterEach
  void resetDiagnosticsAfterTest() {
    SyncDiagnosticsRecorder.clearDefaultForTests();
  }

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

  @Test
  void publishesPendingGeometryReadyBeforeSyncReady() throws Exception {
    OnlineDialog dialog = dialogForYikeUrl("https://home.yikeweiqi.com/#/live/new-room/186538/0/0");

    invoke(dialog, "beginPendingYikeSession", String.class, currentUrl(dialog));
    invoke(
        dialog,
        "markYikeGeometryReady",
        new Class<?>[] {String.class, geometryClass()},
        new Object[] {"live-room:186538", newGeometry()});

    YikeSessionDiagnosticsSnapshot snapshot = currentYikeSnapshot();
    assertEquals("live-room:186538", snapshot.getPendingSessionKey());
    assertEquals(Boolean.FALSE, snapshot.getPendingSyncReady());
    assertEquals(Boolean.TRUE, snapshot.getPendingGeometryReady());
    assertEquals("none", snapshot.getActiveSessionKey());
    assertEquals(Boolean.FALSE, snapshot.getPlacementGeometryAllowed());
    assertEquals("none", snapshot.getLastGeometryClearReason());
    assertEquals("none", snapshot.getLastYikeDebugEventSummary());
  }

  @Test
  void publishesPromotedActiveSessionAfterSyncAndGeometryReady() throws Exception {
    OnlineDialog dialog = dialogForYikeUrl("https://home.yikeweiqi.com/#/live/new-room/186538/0/0");

    invoke(dialog, "beginPendingYikeSession", String.class, currentUrl(dialog));
    invoke(
        dialog,
        "markYikeGeometryReady",
        new Class<?>[] {String.class, geometryClass()},
        new Object[] {"live-room:186538", newGeometry()});
    invoke(
        dialog,
        "markYikeSyncReady",
        new Class<?>[] {String.class, int.class},
        new Object[] {"live-room:186538", 19});

    YikeSessionDiagnosticsSnapshot snapshot = currentYikeSnapshot();
    assertEquals("live-room:186538", snapshot.getActiveSessionKey());
    assertEquals(Boolean.TRUE, snapshot.getActiveSyncReady());
    assertEquals(Boolean.TRUE, snapshot.getActiveGeometryReady());
    assertEquals(19, snapshot.getActiveBoardSize());
    assertEquals("none", snapshot.getPendingSessionKey());
    assertEquals(Boolean.TRUE, snapshot.getPlacementGeometryAllowed());
    assertEquals("pending-promoted", snapshot.getLastSessionSwitchReason());
    assertEquals("none", snapshot.getLastYikeDebugEventSummary());
  }

  @Test
  void publishesClearedSessionAfterReset() throws Exception {
    OnlineDialog dialog = dialogForYikeUrl("https://home.yikeweiqi.com/#/live/new-room/186538/0/0");

    invoke(dialog, "beginPendingYikeSession", String.class, currentUrl(dialog));
    invoke(
        dialog,
        "markYikeGeometryReady",
        new Class<?>[] {String.class, geometryClass()},
        new Object[] {"live-room:186538", newGeometry()});
    invoke(
        dialog,
        "markYikeSyncReady",
        new Class<?>[] {String.class, int.class},
        new Object[] {"live-room:186538", 19});
    invoke(dialog, "resetYikeSessions");

    YikeSessionDiagnosticsSnapshot snapshot = currentYikeSnapshot();
    assertEquals("none", snapshot.getActiveSessionKey());
    assertEquals(Boolean.FALSE, snapshot.getActiveGeometryReady());
    assertEquals("none", snapshot.getPendingSessionKey());
    assertEquals(Boolean.FALSE, snapshot.getEffectiveGeometryReady());
    assertEquals(Boolean.FALSE, snapshot.getPlacementGeometryAllowed());
    assertEquals("sessions-reset", snapshot.getLastGeometryClearReason());
    assertEquals("sessions-reset", snapshot.getLastSessionSwitchReason());
    assertEquals("none", snapshot.getLastYikeDebugEventSummary());
  }

  @Test
  void publishesClearedGeometryAfterInvalidateWithoutClearingActiveSession() throws Exception {
    OnlineDialog dialog = activeReadyDialog();

    invoke(dialog, "invalidateYikePlacementGeometry");

    YikeSessionDiagnosticsSnapshot snapshot = currentYikeSnapshot();
    assertEquals("live-room:186538", snapshot.getActiveSessionKey());
    assertEquals(Boolean.TRUE, snapshot.getActiveSyncReady());
    assertEquals(Boolean.FALSE, snapshot.getActiveGeometryReady());
    assertEquals(Boolean.FALSE, snapshot.getEffectiveGeometryReady());
    assertEquals(Boolean.FALSE, snapshot.getPlacementGeometryAllowed());
    assertEquals("placement-geometry-invalidated", snapshot.getLastGeometryClearReason());
    assertEquals("pending-promoted", snapshot.getLastSessionSwitchReason());
    assertEquals("none", snapshot.getLastYikeDebugEventSummary());
  }

  private static OnlineDialog dialogForYikeUrl(String url) throws Exception {
    OnlineDialog dialog = (OnlineDialog) unsafe().allocateInstance(OnlineDialog.class);
    JTextField txtUrl = new JTextField();
    txtUrl.setText(url);
    setField(dialog, "txtUrl", txtUrl);
    setField(dialog, "activeYikeSession", OnlineDialog.YikeSessionState.empty());
    setField(dialog, "pendingYikeSession", OnlineDialog.YikeSessionState.empty());
    setField(dialog, "boardSize", 19);
    setField(dialog, "hasResolvedYikeBoardSize", false);
    setField(dialog, "lastYikeGeometry", null);
    return dialog;
  }

  private static OnlineDialog activeReadyDialog() throws Exception {
    OnlineDialog dialog = dialogForYikeUrl("https://home.yikeweiqi.com/#/live/new-room/186538/0/0");
    invoke(dialog, "beginPendingYikeSession", String.class, currentUrl(dialog));
    invoke(
        dialog,
        "markYikeGeometryReady",
        new Class<?>[] {String.class, geometryClass()},
        new Object[] {"live-room:186538", newGeometry()});
    invoke(
        dialog,
        "markYikeSyncReady",
        new Class<?>[] {String.class, int.class},
        new Object[] {"live-room:186538", 19});
    return dialog;
  }

  private static String currentUrl(OnlineDialog dialog) throws Exception {
    JTextField txtUrl = (JTextField) getField(dialog, "txtUrl");
    return txtUrl.getText();
  }

  private static YikeSessionDiagnosticsSnapshot currentYikeSnapshot() {
    return SyncDiagnosticsRecorder.getDefault().snapshot().getYikeSnapshot();
  }

  private static Object newGeometry() throws Exception {
    Constructor<?> constructor =
        geometryClass()
            .getDeclaredConstructor(
                int.class,
                int.class,
                int.class,
                int.class,
                Double.class,
                Double.class,
                Double.class,
                Double.class,
                int.class,
                String.class,
                String.class);
    constructor.setAccessible(true);
    return constructor.newInstance(10, 20, 380, 380, 10.0, 20.0, 20.0, 20.0, 100, "#board", "test");
  }

  private static Class<?> geometryClass() throws Exception {
    return Class.forName("featurecat.lizzie.gui.OnlineDialog$YikeGeometrySnapshot");
  }

  private static void invoke(Object target, String methodName) throws Exception {
    Method method = target.getClass().getDeclaredMethod(methodName);
    method.setAccessible(true);
    method.invoke(target);
  }

  private static void invoke(Object target, String methodName, Class<?> argType, Object arg)
      throws Exception {
    invoke(target, methodName, new Class<?>[] {argType}, new Object[] {arg});
  }

  private static void invoke(Object target, String methodName, Class<?>[] argTypes, Object[] args)
      throws Exception {
    Method method = target.getClass().getDeclaredMethod(methodName, argTypes);
    method.setAccessible(true);
    method.invoke(target, args);
  }

  private static Object getField(Object target, String fieldName) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void setField(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Unsafe unsafe() throws Exception {
    Field field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (Unsafe) field.get(null);
  }
}
