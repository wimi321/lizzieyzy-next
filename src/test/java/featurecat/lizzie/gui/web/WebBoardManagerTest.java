package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.analysis.EngineCommandSink;
import featurecat.lizzie.analysis.EngineFollowController;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class WebBoardManagerTest {

  @Test
  void getLanIp_returnsNonNullAddress() {
    String ip = WebBoardManager.getLanIp();
    assertNotNull(ip);
    assertFalse(ip.isEmpty());
    assertTrue(ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"), "Expected IPv4 format but got: " + ip);
  }

  @Test
  void enterTrialFromIdleSucceedsAndSetsOverride() {
    WebBoardManager m = new WebBoardManager();
    AtomicReference<BoardHistoryNode> sink = new AtomicReference<>();
    m.setOverrideSinkForTest(sink::set);
    m.setCollectorForTest(stubCollector());
    BoardHistoryNode anchor = anyNode();
    boolean ok = m.enterTrial("client-1", anchor);
    assertTrue(ok);
    assertSame(anchor, sink.get());
    assertEquals("client-1", m.getTrialOwnerForTest());
  }

  @Test
  void enterTrialFromOtherClientReturnsFalse() {
    WebBoardManager m = new WebBoardManager();
    m.setOverrideSinkForTest(n -> {});
    m.setCollectorForTest(stubCollector());
    m.enterTrial("client-1", anyNode());
    assertFalse(m.enterTrial("client-2", anyNode()));
  }

  @Test
  void enterTrialIdempotentForSameClient() {
    WebBoardManager m = new WebBoardManager();
    m.setOverrideSinkForTest(n -> {});
    m.setCollectorForTest(stubCollector());
    m.enterTrial("c1", anyNode());
    assertTrue(m.enterTrial("c1", anyNode()));
  }

  @Test
  void exitTrialClearsOverride() {
    WebBoardManager m = new WebBoardManager();
    AtomicReference<BoardHistoryNode> sink = new AtomicReference<>();
    m.setOverrideSinkForTest(sink::set);
    m.setCollectorForTest(stubCollector());
    BoardHistoryNode anchor = anyNode();
    m.enterTrial("c1", anchor);
    m.exitTrial("c1");
    assertNull(sink.get());
    assertNull(m.getTrialOwnerForTest());
  }

  @Test
  void exitTrialFromNonOwnerIgnored() {
    WebBoardManager m = new WebBoardManager();
    AtomicReference<BoardHistoryNode> sink = new AtomicReference<>();
    m.setOverrideSinkForTest(sink::set);
    m.setCollectorForTest(stubCollector());
    BoardHistoryNode anchor = anyNode();
    m.enterTrial("c1", anchor);
    m.exitTrial("c2");
    assertEquals("c1", m.getTrialOwnerForTest());
    assertSame(anchor, sink.get());
  }

  @Test
  void forceExitTrialAlwaysClears() {
    WebBoardManager m = new WebBoardManager();
    AtomicReference<BoardHistoryNode> sink = new AtomicReference<>();
    m.setOverrideSinkForTest(sink::set);
    m.setCollectorForTest(stubCollector());
    m.enterTrial("c1", anyNode());
    m.forceExitTrial();
    assertNull(m.getTrialOwnerForTest());
  }

  @Test
  void trialNavigateBackAtAnchorIsNoop() {
    BoardHistoryNode anchor = anyNode();
    WebBoardManager m = setupTrial("c", anchor);
    m.trialNavigate("c", "back");
    assertSame(anchor, m.getDisplayNodeForTest());
  }

  @Test
  void trialResetGoesBackToAnchor() {
    BoardHistoryNode anchor = anyNode();
    BoardHistoryNode child = nodeWithLastMove(3, 3);
    anchor.variations.add(child);
    anchor.setPreviousForChild(child);
    WebBoardManager m = setupTrial("c", anchor);
    m.setDisplayNodeForTest(child);
    m.trialReset("c");
    assertSame(anchor, m.getDisplayNodeForTest());
  }

  @Test
  void applyTrialMoveCreatesNewChildOnEmptyPoint() {
    BoardHistoryNode anchor = anyNode();
    WebBoardManager m = setupTrial("c", anchor);
    m.applyTrialMove("c", 3, 3);
    BoardHistoryNode display = m.getDisplayNodeForTest();
    assertNotSame(anchor, display);
    // anchor.variations: [dummy 占位, 试下子]
    assertEquals(2, anchor.variations.size());
    assertTrue(anchor.variations.get(0).getData().dummy);
    assertSame(anchor.variations.get(1), display);
    BoardData childData = display.getData();
    assertTrue(childData.lastMove.isPresent());
    assertEquals(3, childData.lastMove.get()[0]);
    assertEquals(3, childData.lastMove.get()[1]);
  }

  @Test
  void applyTrialMoveReusesExistingChild() {
    BoardHistoryNode anchor = anyNode();
    WebBoardManager m = setupTrial("c", anchor);
    m.applyTrialMove("c", 3, 3);
    BoardHistoryNode firstChild = m.getDisplayNodeForTest();
    m.trialReset("c");
    m.applyTrialMove("c", 3, 3);
    assertSame(firstChild, m.getDisplayNodeForTest());
    // 仍然是 [dummy 占位, 试下子] 两个，没创建新分支
    assertEquals(2, anchor.variations.size());
  }

  @Test
  void applyTrialMoveRejectsOccupiedPoint() {
    BoardHistoryNode anchor = anyNode();
    WebBoardManager m = setupTrial("c", anchor);
    m.applyTrialMove("c", 3, 3);
    BoardHistoryNode firstChild = m.getDisplayNodeForTest();
    m.applyTrialMove("c", 3, 3);
    assertSame(firstChild, m.getDisplayNodeForTest());
    assertEquals(0, firstChild.variations.size());
  }

  @Test
  void enterTrialRefusesWhenDesktopPlaying() {
    WebBoardManager m = new WebBoardManager();
    m.setOverrideSinkForTest(n -> {});
    m.setDesktopRefresherForTest(() -> {});
    m.setCollectorForTest(stubCollector());
    m.setDesktopPlayingProbe(() -> true);
    BoardHistoryNode anchor = anyNode();
    assertFalse(m.enterTrial("client-1", anchor));
    assertNull(m.getTrialOwnerForTest());
  }

  @Test
  void applyTrialMoveInvokesControllerHook() throws Exception {
    BoardHistoryNode anchor = anyNode();
    RecordingEngineCommandSink sink = new RecordingEngineCommandSink();
    EngineFollowController c = new EngineFollowController(sink);
    c.setCurrentEngineNode(anchor);

    WebBoardManager m = new WebBoardManager();
    m.setOverrideSinkForTest(n -> {});
    m.setDesktopRefresherForTest(() -> {});
    m.setCollectorForTest(stubCollector());
    m.setEngineFollowController(c);

    m.enterTrial("c1", anchor);
    m.applyTrialMove("c1", 3, 3);
    c.awaitIdle();

    // controller 三个入口都走 forceResync 路径（详见 EngineFollowController 注释），
    // 因此应观察到 resync + clearBestMoves，而不是单独的 undo / play 增量命令。
    assertTrue(sink.calls.contains("resync"), "expected 'resync' in " + sink.calls);
    assertTrue(sink.calls.contains("clearBestMoves"), "expected clearBestMoves in " + sink.calls);
  }

  @Test
  void exitTrialInvokesControllerExit() throws Exception {
    BoardHistoryNode anchor = anyNode();
    RecordingEngineCommandSink sink = new RecordingEngineCommandSink();
    EngineFollowController c = new EngineFollowController(sink);
    c.setCurrentEngineNode(anchor);

    WebBoardManager m = new WebBoardManager();
    m.setOverrideSinkForTest(n -> {});
    m.setDesktopRefresherForTest(() -> {});
    m.setCollectorForTest(stubCollector());
    m.setEngineFollowController(c);
    m.setMainlineTailSupplier(() -> anchor);

    m.enterTrial("c1", anchor);
    m.applyTrialMove("c1", 3, 3);
    c.awaitIdle();
    sink.calls.clear();

    m.exitTrial("c1");
    c.awaitIdle();

    // controller 退出试下也走 forceResync 路径（同样不再用增量 undo）
    assertTrue(sink.calls.contains("resync"), "expected 'resync' in " + sink.calls);
    assertTrue(sink.calls.contains("clearBestMoves"), "expected clearBestMoves in " + sink.calls);
  }

  // --- Helpers ---

  /** 测试用 sink：与 EngineFollowControllerTest 中同名类等价，包内复制。 */
  private static final class RecordingEngineCommandSink implements EngineCommandSink {
    final List<String> calls = new ArrayList<>();

    @Override
    public void playMove(Stone color, String coord) {
      calls.add("play " + (color == Stone.BLACK ? "B" : "W") + " " + coord);
    }

    @Override
    public void undo() {
      calls.add("undo");
    }

    @Override
    public void clear() {
      calls.add("clear");
    }

    @Override
    public void clearBestMoves() {
      calls.add("clearBestMoves");
    }

    @Override
    public void resyncFromCurrentHistory(BoardHistoryNode target) {
      calls.add("resync");
    }
  }

  private WebBoardManager setupTrial(String clientId, BoardHistoryNode anchor) {
    WebBoardManager m = new WebBoardManager();
    m.setOverrideSinkForTest(n -> {});
    m.setCollectorForTest(stubCollector());
    m.enterTrial(clientId, anchor);
    return m;
  }

  private BoardHistoryNode anyNode() {
    return new BoardHistoryNode(BoardData.empty(Board.boardWidth, Board.boardHeight));
  }

  private BoardHistoryNode nodeWithLastMove(int x, int y) {
    BoardData d = BoardData.empty(Board.boardWidth, Board.boardHeight);
    d.lastMove = Optional.of(new int[] {x, y});
    return new BoardHistoryNode(d);
  }

  private WebBoardDataCollector stubCollector() {
    return new WebBoardDataCollector() {
      @Override
      public void runOnExecutor(Runnable r) {
        r.run();
      }

      @Override
      public ScheduledFuture<?> scheduleOnExecutor(Runnable r, long delay, TimeUnit u) {
        return new ScheduledFuture<Object>() {
          public boolean cancel(boolean mayInterruptIfRunning) {
            return true;
          }

          public boolean isCancelled() {
            return false;
          }

          public boolean isDone() {
            return false;
          }

          public Object get() {
            return null;
          }

          public Object get(long t, TimeUnit u) {
            return null;
          }

          public long getDelay(TimeUnit u) {
            return 0;
          }

          public int compareTo(java.util.concurrent.Delayed o) {
            return 0;
          }
        };
      }

      @Override
      public void broadcastTrialState(WebBoardManager.TrialSession s) {}

      @Override
      public void onBoardStateChanged() {}
    };
  }
}
