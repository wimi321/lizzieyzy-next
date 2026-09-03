package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoardMovelistRefreshLifecycleTest {
  @Test
  void refreshUsesItsBoardInstanceWhenTheGlobalBoardIsUnavailable() throws Exception {
    Board previousBoard = Lizzie.board;
    Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
    AtomicReference<Throwable> uncaught = new AtomicReference<>();
    TrackingBoard board = trackingBoard(singleNodeHistory());
    try {
      Thread.setDefaultUncaughtExceptionHandler((thread, error) -> uncaught.compareAndSet(null, error));
      Lizzie.board = null;

      board.setMovelistAll();

      assertTrue(board.firstUpdate.await(2, TimeUnit.SECONDS), "instance refresh should run.");
      assertNull(uncaught.get(), "refresh must not dereference the global board.");
    } finally {
      board.releaseFirstUpdate.countDown();
      Thread.setDefaultUncaughtExceptionHandler(previousHandler);
      Lizzie.board = previousBoard;
    }
  }

  @Test
  void replacingHistoryCancelsAnInFlightRefreshBeforeItVisitsMoreNodes() throws Exception {
    Board previousBoard = Lizzie.board;
    TrackingBoard board = trackingBoard(twoNodeHistory());
    try {
      Lizzie.board = board;
      board.blockFirstUpdate = true;
      board.setMovelistAll();
      assertTrue(board.firstUpdate.await(2, TimeUnit.SECONDS), "refresh should reach its first node.");

      board.setHistory(singleNodeHistory());
      board.releaseFirstUpdate.countDown();

      assertFalse(
          board.secondUpdate.await(500, TimeUnit.MILLISECONDS),
          "the stale refresh must stop after its history is replaced.");
      assertEquals(1, board.updates.get(), "only the already-running update may finish.");
    } finally {
      board.releaseFirstUpdate.countDown();
      Lizzie.board = previousBoard;
    }
  }

  @Test
  void completionRunsOnlyAfterEveryMoveListNodeIsUpdated() throws Exception {
    Board previousBoard = Lizzie.board;
    TrackingBoard board = trackingBoard(twoNodeHistory());
    CountDownLatch completed = new CountDownLatch(1);
    try {
      Lizzie.board = board;

      board.setMovelistAll(completed::countDown);

      assertTrue(completed.await(2, TimeUnit.SECONDS), "refresh completion should be delivered.");
      assertEquals(2, board.updates.get(), "completion must observe a fully rebuilt move list.");
    } finally {
      board.releaseFirstUpdate.countDown();
      Lizzie.board = previousBoard;
    }
  }

  @Test
  void staleRefreshDoesNotDeliverItsCompletionAfterHistoryReplacement() throws Exception {
    Board previousBoard = Lizzie.board;
    TrackingBoard board = trackingBoard(twoNodeHistory());
    CountDownLatch completed = new CountDownLatch(1);
    try {
      Lizzie.board = board;
      board.blockFirstUpdate = true;
      board.setMovelistAll(completed::countDown);
      assertTrue(board.firstUpdate.await(2, TimeUnit.SECONDS), "refresh should reach its first node.");

      board.setHistory(singleNodeHistory());
      board.releaseFirstUpdate.countDown();

      assertFalse(
          completed.await(500, TimeUnit.MILLISECONDS),
          "a refresh for replaced history must not publish a stale completion.");
    } finally {
      board.releaseFirstUpdate.countDown();
      Lizzie.board = previousBoard;
    }
  }

  private static TrackingBoard trackingBoard(BoardHistoryList history) throws Exception {
    TrackingBoard board = allocate(TrackingBoard.class);
    board.updates = new AtomicInteger();
    board.firstUpdate = new CountDownLatch(1);
    board.secondUpdate = new CountDownLatch(1);
    board.releaseFirstUpdate = new CountDownLatch(1);
    board.setHistory(history);
    return board;
  }

  private static BoardHistoryList singleNodeHistory() {
    return new BoardHistoryList(BoardData.empty(Board.boardWidth, Board.boardHeight));
  }

  private static BoardHistoryList twoNodeHistory() {
    BoardHistoryList history = singleNodeHistory();
    history.add(BoardData.empty(Board.boardWidth, Board.boardHeight));
    return history;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class TrackingBoard extends Board {
    private AtomicInteger updates;
    private CountDownLatch firstUpdate;
    private CountDownLatch secondUpdate;
    private CountDownLatch releaseFirstUpdate;
    private volatile boolean blockFirstUpdate;

    private TrackingBoard() {
      super();
    }

    @Override
    public void updateMovelist(BoardHistoryNode node) {
      int update = updates.incrementAndGet();
      if (update == 1) {
        firstUpdate.countDown();
        if (blockFirstUpdate) {
          try {
            releaseFirstUpdate.await(2, TimeUnit.SECONDS);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
        }
      } else {
        secondUpdate.countDown();
      }
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
