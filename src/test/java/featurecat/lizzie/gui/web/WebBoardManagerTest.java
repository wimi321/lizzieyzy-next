package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
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
    assertEquals(1, anchor.variations.size());
    assertSame(anchor.variations.get(0), display);
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
    assertEquals(1, anchor.variations.size());
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

  // --- Helpers ---

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
