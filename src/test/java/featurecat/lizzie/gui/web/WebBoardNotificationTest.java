package featurecat.lizzie.gui.web;

import static featurecat.lizzie.gui.web.WebBoardUpdateQueueTest.*;
import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class WebBoardNotificationTest {
  @Test
  void navigationDuringAnalysisSerializationDiscardsOldPayloadAndSendsLatestFullState()
      throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    BoardHistoryNode oldNode = node(1, 100);
    BoardHistoryNode newNode = node(2, 900);
    AtomicReference<BoardHistoryNode> display = new AtomicReference<>(oldNode);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean firstRead = new AtomicBoolean(true);
    RecordingServer server = new RecordingServer();
    WebBoardDataCollector collector =
        new WebBoardDataCollector(
            executor,
            () -> {
              BoardHistoryNode captured = display.get();
              if (firstRead.getAndSet(false)) block(entered, release);
              return captured;
            },
            display::get);
    collector.setServer(server);
    try {
      collector.onAnalysisUpdated();
      await(entered);
      display.set(newNode);
      for (int i = 0; i < 10_000; i++) {
        collector.onBoardStateChanged();
        collector.onAnalysisUpdated();
      }
      assertTrue(executor.getQueue().isEmpty());
      release.countDown();
      JSONObject full = server.next();
      assertEquals("full_state", full.getString("type"));
      assertEquals(2, full.getInt("moveNumber"));
      assertEquals(900, full.getInt("playouts"));
      assertEquals("winrate_history", server.next().getString("type"));
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertTrue(server.messages.isEmpty(), "no old analysis may overwrite the new full state");
      newNode.getData().setPlayouts(1200);
      collector.onAnalysisUpdated();
      JSONObject analysis = server.next();
      assertEquals("analysis_update", analysis.getString("type"));
      assertEquals(1200, analysis.getInt("playouts"));
    } finally {
      release.countDown();
      collector.shutdown();
    }
  }

  @Test
  void navigationDuringFullSerializationDiscardsBothOldBoardAndHistory() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    AtomicReference<BoardHistoryNode> display = new AtomicReference<>(node(1, 100));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean firstRead = new AtomicBoolean(true);
    RecordingServer server = new RecordingServer();
    WebBoardDataCollector collector =
        new WebBoardDataCollector(
            executor,
            display::get,
            () -> {
              BoardHistoryNode root = display.get();
              if (firstRead.getAndSet(false)) block(entered, release);
              return root;
            });
    collector.setServer(server);
    try {
      collector.onBoardStateChanged();
      await(entered);
      display.set(node(3, 300));
      collector.onBoardStateChanged();
      collector.onAnalysisUpdated();
      release.countDown();
      JSONObject full = server.next();
      assertEquals("full_state", full.getString("type"));
      assertEquals(3, full.getInt("moveNumber"));
      JSONObject history = server.next();
      assertEquals("winrate_history", history.getString("type"));
      assertEquals(3, history.getJSONArray("data").getJSONObject(0).getInt("moveNumber"));
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertTrue(server.messages.isEmpty());
    } finally {
      release.countDown();
      collector.shutdown();
    }
  }

  @Test
  void changedDisplayNodeWithoutNotificationStillGetsAFullState() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    BoardHistoryNode oldNode = node(1, 100);
    BoardHistoryNode newNode = node(4, 400);
    AtomicBoolean firstRead = new AtomicBoolean(true);
    RecordingServer server = new RecordingServer();
    WebBoardDataCollector collector =
        new WebBoardDataCollector(
            executor, () -> firstRead.getAndSet(false) ? oldNode : newNode, () -> newNode);
    collector.setServer(server);
    try {
      collector.onAnalysisUpdated();
      JSONObject full = server.next();
      assertEquals("full_state", full.getString("type"));
      assertEquals(4, full.getInt("moveNumber"));
      assertEquals("winrate_history", server.next().getString("type"));
    } finally {
      collector.shutdown();
    }
  }

  @Test
  void collectorControlAndDelayedTasksSurviveNotificationBursts() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    BoardHistoryNode node = node(0, 100);
    WebBoardDataCollector collector = new WebBoardDataCollector(executor, () -> node, () -> node);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    BlockingQueue<Integer> controls = new LinkedBlockingQueue<>();
    try {
      collector.runOnExecutor(() -> block(entered, release));
      await(entered);
      for (int i = 0; i < 100; i++) {
        int id = i;
        collector.onAnalysisUpdated();
        collector.onBoardStateChanged();
        collector.runOnExecutor(() -> controls.add(id));
      }
      collector.scheduleOnExecutor(() -> controls.add(100), 1, TimeUnit.MILLISECONDS);
      assertEquals(102, executor.getQueue().size(), "one notification and 101 control tasks");
      release.countDown();
      for (int i = 0; i <= 100; i++) assertEquals(i, controls.poll(5, TimeUnit.SECONDS));
      collector.shutdown();
      assertDoesNotThrow(collector::onAnalysisUpdated);
      assertDoesNotThrow(collector::onBoardStateChanged);
      assertDoesNotThrow(() -> collector.runOnExecutor(() -> fail("closed")));
      assertNull(collector.scheduleOnExecutor(() -> fail("closed"), 0, TimeUnit.MILLISECONDS));
    } finally {
      release.countDown();
      collector.shutdown();
    }
  }

  private static BoardHistoryNode node(int moveNumber, int playouts) {
    BoardData data = BoardData.empty(Board.boardWidth, Board.boardHeight);
    data.moveNumber = moveNumber;
    data.setPlayouts(playouts);
    MoveData move = new MoveData();
    move.coordinate = "D4";
    move.playouts = playouts;
    move.variation = List.of("D4");
    data.bestMoves = List.of(move);
    return new BoardHistoryNode(data);
  }

  private static final class RecordingServer extends WebBoardServer {
    final BlockingQueue<JSONObject> messages = new LinkedBlockingQueue<>();

    RecordingServer() {
      super(new InetSocketAddress("127.0.0.1", 0), 1);
    }

    @Override
    public void broadcastMessage(String json) {
      messages.add(new JSONObject(json));
    }

    @Override
    public void broadcastFullState(String json) {
      messages.add(new JSONObject(json));
    }

    JSONObject next() throws InterruptedException {
      JSONObject message = messages.poll(5, TimeUnit.SECONDS);
      assertNotNull(message, "timed out waiting for broadcast");
      return message;
    }
  }
}
