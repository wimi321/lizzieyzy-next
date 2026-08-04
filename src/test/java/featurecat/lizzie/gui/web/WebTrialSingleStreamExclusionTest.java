package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineCommandSink;
import featurecat.lizzie.analysis.EngineFollowController;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.WebSocket;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebTrialSingleStreamExclusionTest {
  private Leelaz previousEngine;
  private WebBoardManager previousManager;
  private Config previousConfig;
  private Board previousBoard;
  private Leelaz engine;
  private WebBoardManager manager;
  private ByteArrayOutputStream output;
  private AtomicInteger overrideMutations;

  @BeforeEach
  void setUp() throws Exception {
    previousEngine = Lizzie.leelaz;
    previousManager = Lizzie.webBoardManager;
    previousConfig = Lizzie.config;
    previousBoard = Lizzie.board;
    engine = trackingCapableEngine();
    output = new ByteArrayOutputStream();
    setLeelazField(engine, "outputStream", new BufferedOutputStream(output));
    overrideMutations = new AtomicInteger();
    manager = new WebBoardManager();
    manager.setOverrideSinkForTest(node -> overrideMutations.incrementAndGet());
    manager.setDesktopRefresherForTest(() -> {});
    manager.setCollectorForTest(stubCollector());
    Lizzie.leelaz = engine;
    Lizzie.webBoardManager = manager;
    Lizzie.config = null;
  }

  @AfterEach
  void tearDown() {
    Lizzie.leelaz = previousEngine;
    Lizzie.webBoardManager = previousManager;
    Lizzie.config = previousConfig;
    Lizzie.board = previousBoard;
  }

  @Test
  void activeTrackingWinsBeforeTrialWithoutAnyTrialOrTrackingMutation() throws Exception {
    Leelaz.TrackingStreamLeaseAcquisition tracking = activateTracking(engine);
    BoardHistoryNode anchor = anyNode();
    EngineFollowController controller = new EngineFollowController(new NoOpEngineCommandSink());
    manager.setEngineFollowController(controller);
    String bytesBeforeEnter = output.toString(StandardCharsets.UTF_8);

    assertEquals(
        WebBoardManager.TrialEnterResult.ENGINE_BUSY,
        manager.enterTrialWithResult("tracking-loser", anchor));

    assertTrue(tracking.lease().isOwned());
    assertEquals(Leelaz.TrackingReleaseDisposition.ACTIVE, tracking.lease().disposition());
    assertEquals(bytesBeforeEnter, output.toString(StandardCharsets.UTF_8));
    assertNull(manager.getTrialOwnerForTest());
    assertTrue(anchor.variations.isEmpty());
    assertFalse(controller.isTrialActive());
    assertEquals(0, overrideMutations.get());
  }

  @Test
  void acquiringTrackingWinsBeforeTrialWithoutAnyTrialMutation() throws Exception {
    Leelaz.TrackingStreamLeaseAcquisition tracking =
        engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
    BoardHistoryNode anchor = anyNode();
    String bytesBeforeEnter = output.toString(StandardCharsets.UTF_8);

    assertEquals(Leelaz.ExclusiveGtpLeaseAvailability.AVAILABLE, tracking.availability());
    assertEquals(
        WebBoardManager.TrialEnterResult.ENGINE_BUSY,
        manager.enterTrialWithResult("tracking-acquiring", anchor));
    assertTrue(tracking.lease().isOwned());
    assertEquals(bytesBeforeEnter, output.toString(StandardCharsets.UTF_8));
    assertNull(manager.getTrialOwnerForTest());
    assertTrue(anchor.variations.isEmpty());
    assertEquals(0, overrideMutations.get());

    processCommandResponse(engine, "=800000000");
    assertTrue(dispatchExclusiveLine(engine, ""));
  }

  @Test
  void releaseClosingTrackingWinsBeforeTrialWithoutAnyTrialMutation() throws Exception {
    Leelaz.TrackingStreamLeaseAcquisition tracking = activateTracking(engine);
    BoardHistoryNode anchor = anyNode();
    assertTrue(tracking.lease().release());
    String bytesBeforeEnter = output.toString(StandardCharsets.UTF_8);

    assertEquals(
        WebBoardManager.TrialEnterResult.ENGINE_BUSY,
        manager.enterTrialWithResult("tracking-closing", anchor));
    assertTrue(tracking.lease().isOwned());
    assertEquals(bytesBeforeEnter, output.toString(StandardCharsets.UTF_8));
    assertNull(manager.getTrialOwnerForTest());
    assertTrue(anchor.variations.isEmpty());
    assertEquals(0, overrideMutations.get());

    assertTrue(dispatchExclusiveLine(engine, ""));
    assertTrue(dispatchExclusiveLine(engine, "=800000002"));
    assertTrue(dispatchExclusiveLine(engine, ""));
    assertFalse(tracking.lease().isOwned());
  }

  @Test
  void trialWinnerExcludesLaterSingleStreamOwners() throws Exception {
    BoardHistoryNode anchor = anyNode();
    EngineFollowController controller = new EngineFollowController(new NoOpEngineCommandSink());
    controller.setCurrentEngineNode(anchor);
    manager.setEngineFollowController(controller);

    assertEquals(
        WebBoardManager.TrialEnterResult.ENTERED,
        manager.enterTrialWithResult("trial-owner", anchor));
    controller.awaitIdle();
    String bytesBeforeRejectedOwners = output.toString(StandardCharsets.UTF_8);

    assertTrialExcludesEngineOwners();
    assertEquals(bytesBeforeRejectedOwners, output.toString(StandardCharsets.UTF_8));
    assertEquals(
        WebBoardManager.TrialEnterResult.IDEMPOTENT,
        manager.enterTrialWithResult("trial-owner", anchor));
    WebBoardManager.TrialEnterResult inUse = manager.enterTrialWithResult("other-client", anchor);
    assertEquals(WebBoardManager.TrialEnterResult.Kind.IN_USE, inUse.kind());
    assertEquals("trial-owner", inUse.capturedOwnerClientId());
  }

  @Test
  void exitKeepsOwnersExcludedUntilControllerResyncSettles() throws Exception {
    BoardHistoryNode anchor = anyNode();
    BlockingEngineCommandSink sink = new BlockingEngineCommandSink();
    EngineFollowController controller = new EngineFollowController(sink);
    controller.setCurrentEngineNode(anchor);
    manager.setEngineFollowController(controller);
    manager.setMainlineTailSupplier(() -> anchor);
    assertTrue(manager.enterTrial("trial-owner", anchor));
    controller.awaitIdle();

    manager.exitTrial("trial-owner");
    assertTrue(sink.resyncStarted.await(1, TimeUnit.SECONDS));

    assertNull(manager.getTrialOwnerForTest());
    assertTrue(controller.isTrialActive());
    assertTrialExcludesEngineOwners();

    sink.allowResync.countDown();
    controller.awaitIdle();
    assertFalse(manager.isEngineOperationExcludedByTrial());
    Leelaz.EngineModeReservation recovered = engine.beginEngineModeReservation();
    assertNotNull(recovered);
    recovered.close();
  }

  @Test
  void productionMessageEntryReturnsEngineBusyWithoutTrialMutation() throws Exception {
    Board board = new Board();
    Lizzie.board = board;
    Leelaz.TrackingStreamLeaseAcquisition tracking = activateTracking(engine);
    RecordingWebBoardServer server = new RecordingWebBoardServer();
    manager.attachWebSocketServer(server);
    String bytesBeforeEnter = output.toString(StandardCharsets.UTF_8);

    server.onMessage(null, "{\"type\":\"enter_trial\",\"clientId\":\"browser-client\"}");

    JSONObject denied = new JSONObject(server.lastMessage.get());
    assertEquals("trial_denied", denied.getString("type"));
    assertEquals("engine_busy", denied.getString("reason"));
    assertEquals("", denied.getString("ownerClientId"));
    assertTrue(tracking.lease().isOwned());
    assertEquals(bytesBeforeEnter, output.toString(StandardCharsets.UTF_8));
    assertNull(manager.getTrialOwnerForTest());
    assertEquals(0, overrideMutations.get());
  }

  @Test
  void productionMessageEntrySerializesCapturedTrialOwnerAcrossExitAndReenter() throws Exception {
    Lizzie.board = new Board();
    BoardHistoryNode anchor = anyNode();
    assertTrue(manager.enterTrial("original-owner", anchor));
    RecordingWebBoardServer server = new RecordingWebBoardServer();
    manager.attachWebSocketServer(server);
    server.beforeSend =
        () -> {
          manager.exitTrial("original-owner");
          assertTrue(manager.enterTrial("replacement-owner", anchor));
        };

    server.onMessage(null, "{\"type\":\"enter_trial\",\"clientId\":\"challenger\"}");

    JSONObject denied = new JSONObject(server.lastMessage.get());
    assertEquals("trial_denied", denied.getString("type"));
    assertEquals("in_use", denied.getString("reason"));
    assertEquals("original-owner", denied.getString("ownerClientId"));
    assertEquals("replacement-owner", manager.getTrialOwnerForTest());
  }

  @Test
  void productionMessageEntryRevalidatesCapturedBoardContext() throws Exception {
    Board capturedBoard = new Board();
    Board replacementBoard = new Board();
    BoardChangingLeelaz changingEngine = new BoardChangingLeelaz(replacementBoard);
    Lizzie.board = capturedBoard;
    Lizzie.leelaz = changingEngine;
    engine = changingEngine;
    RecordingWebBoardServer server = new RecordingWebBoardServer();
    manager.attachWebSocketServer(server);

    server.onMessage(null, "{\"type\":\"enter_trial\",\"clientId\":\"browser-client\"}");

    JSONObject denied = new JSONObject(server.lastMessage.get());
    assertEquals("engine_busy", denied.getString("reason"));
    assertNull(manager.getTrialOwnerForTest());
    assertEquals(0, overrideMutations.get());
  }

  @Test
  void inactiveTrialLeavesAutomaticRecoveryAdmissionUnchanged() throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("beginAutomaticEngineRestartReservation");
    method.setAccessible(true);

    Leelaz.ExclusiveGtpLifecycleReservation reservation =
        (Leelaz.ExclusiveGtpLifecycleReservation) method.invoke(engine);

    assertNotNull(reservation);
    reservation.close();
  }

  private void assertTrialExcludesEngineOwners() throws Exception {
    Leelaz.TrackingStreamLeaseAcquisition tracking =
        engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.APPLICATION_EXCLUSIVE_MODE, tracking.availability());
    assertNull(tracking.lease());
    assertEquals(
        Leelaz.ExclusiveGtpLeaseAvailability.APPLICATION_EXCLUSIVE_MODE,
        engine.previewForegroundAnalysisLeaseAvailability());
    assertNull(engine.beginExclusiveGtpLifecycleReservation());
    assertNull(engine.beginEngineModeReservation());
    assertFalse(invokeCanArmReadBoardGma(engine));
    assertFalse(invokeBeginReadBoardGmaSession(engine));
  }

  private static Leelaz trackingCapableEngine() throws Exception {
    Leelaz engine = new Leelaz("");
    engine.started = true;
    engine.isLoaded = true;
    engine.isKatago = true;
    engine.commandLists.addAll(List.of("stop", "boardsize", "komi", "kata-analyze"));
    setLeelazField(engine, "endGetCommandList", true);
    return engine;
  }

  private static Leelaz.TrackingStreamLeaseAcquisition activateTracking(Leelaz engine)
      throws Exception {
    Leelaz.TrackingStreamLeaseAcquisition tracking =
        engine.acquireTrackingStreamLease(line -> {}, lease -> {}, lease -> {});
    processCommandResponse(engine, "=800000000");
    assertTrue(dispatchExclusiveLine(engine, ""));
    assertTrue(tracking.lease().send("kata-analyze B 10"));
    return tracking;
  }

  private static void setLeelazField(Leelaz engine, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(engine, value);
  }

  private static boolean dispatchExclusiveLine(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("dispatchExclusiveGtpLine", String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(engine, line);
  }

  private static void processCommandResponse(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("processCommandResponseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static boolean invokeCanArmReadBoardGma(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("canArmReadBoardGma");
    method.setAccessible(true);
    return (boolean) method.invoke(engine);
  }

  private static boolean invokeBeginReadBoardGmaSession(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("beginReadBoardGmaSession");
    method.setAccessible(true);
    return (boolean) method.invoke(engine);
  }

  private static BoardHistoryNode anyNode() {
    return new BoardHistoryNode(BoardData.empty(Board.boardWidth, Board.boardHeight));
  }

  private static WebBoardDataCollector stubCollector() {
    return new WebBoardDataCollector() {
      @Override
      public void runOnExecutor(Runnable action) {
        action.run();
      }

      @Override
      public ScheduledFuture<?> scheduleOnExecutor(Runnable action, long delay, TimeUnit timeUnit) {
        return new ScheduledFuture<Object>() {
          @Override
          public boolean cancel(boolean mayInterruptIfRunning) {
            return true;
          }

          @Override
          public boolean isCancelled() {
            return false;
          }

          @Override
          public boolean isDone() {
            return false;
          }

          @Override
          public Object get() {
            return null;
          }

          @Override
          public Object get(long timeout, TimeUnit unit) {
            return null;
          }

          @Override
          public long getDelay(TimeUnit unit) {
            return 0;
          }

          @Override
          public int compareTo(java.util.concurrent.Delayed other) {
            return 0;
          }
        };
      }
    };
  }

  private static class NoOpEngineCommandSink implements EngineCommandSink {
    @Override
    public void playMove(Stone color, String coord) {}

    @Override
    public void undo() {}

    @Override
    public void clear() {}

    @Override
    public void clearBestMoves() {}

    @Override
    public void resyncFromCurrentHistory(BoardHistoryNode target) {}
  }

  private static final class BlockingEngineCommandSink extends NoOpEngineCommandSink {
    private final CountDownLatch resyncStarted = new CountDownLatch(1);
    private final CountDownLatch allowResync = new CountDownLatch(1);

    @Override
    public void resyncFromCurrentHistory(BoardHistoryNode target) {
      resyncStarted.countDown();
      try {
        assertTrue(allowResync.await(1, TimeUnit.SECONDS));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
    }
  }

  private static final class RecordingWebBoardServer extends WebBoardServer {
    private final AtomicReference<String> lastMessage = new AtomicReference<>();
    private Runnable beforeSend = () -> {};

    private RecordingWebBoardServer() {
      super(new InetSocketAddress("127.0.0.1", 0), 1);
    }

    @Override
    public void sendToConnection(WebSocket conn, String json) {
      beforeSend.run();
      lastMessage.set(json);
    }
  }

  private static final class BoardChangingLeelaz extends Leelaz {
    private final Board replacementBoard;

    private BoardChangingLeelaz(Board replacementBoard) throws IOException, JSONException {
      super("");
      this.replacementBoard = replacementBoard;
    }

    @Override
    public EngineModeReservation beginEngineModeReservation() {
      EngineModeReservation reservation = super.beginEngineModeReservation();
      if (reservation != null) {
        Lizzie.board = replacementBoard;
      }
      return reservation;
    }
  }
}
