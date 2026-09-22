package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class SgfSaveQueueTest {
  @Test
  void busyWriterDoesNotBlockEventThreadAndOnlyReceivesFrozenSnapshot() throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    CountDownLatch writing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean completedOnEdt = new AtomicBoolean();
    try {
      SgfSaveQueue queue =
          new SgfSaveQueue(
              worker,
              SwingUtilities::invokeLater,
              (path, snapshot) -> {
                assertFalse(SwingUtilities.isEventDispatchThread());
                assertEquals("captured", snapshot);
                writing.countDown();
                try {
                  assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                  throw new IOException(failure);
                }
              });
      CompletableFuture<Void>[] result = new CompletableFuture[1];
      SwingUtilities.invokeAndWait(
          () ->
              result[0] =
                  queue.submit(
                      Path.of("a.sgf"),
                      "captured",
                      () -> true,
                      () -> completedOnEdt.set(SwingUtilities.isEventDispatchThread()),
                      error -> fail(error)));
      assertTrue(writing.await(5, TimeUnit.SECONDS));
      assertFalse(result[0].isDone());
      SwingUtilities.invokeAndWait(() -> assertFalse(result[0].isDone()));
      release.countDown();
      result[0].get(5, TimeUnit.SECONDS);
      assertTrue(completedOnEdt.get());
    } finally {
      release.countDown();
      worker.shutdownNow();
    }
  }

  @Test
  void replacedBoardStillSavesItsSnapshotButCannotChangeNewBoardsCurrentFile() {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    AtomicBoolean current = new AtomicBoolean(true);
    AtomicInteger writes = new AtomicInteger();
    AtomicInteger effects = new AtomicInteger();
    SgfSaveQueue queue =
        new SgfSaveQueue(worker::add, Runnable::run, (path, snapshot) -> writes.incrementAndGet());
    var result =
        queue.submit(
            Path.of("old.sgf"),
            "old board",
            current::get,
            effects::incrementAndGet,
            error -> fail(error));
    current.set(false);
    worker.remove().run();
    assertTrue(result.isDone());
    assertEquals(1, writes.get());
    assertEquals(0, effects.get());
  }

  @Test
  void repeatedSavesCommitInOrderLeavingTheLatestCurrentFile() {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    ArrayDeque<Runnable> owner = new ArrayDeque<>();
    List<String> writes = new ArrayList<>();
    List<String> effects = new ArrayList<>();
    SgfSaveQueue queue =
        new SgfSaveQueue(worker::add, owner::add, (path, snapshot) -> writes.add(snapshot));
    queue.submit(
        Path.of("same.sgf"), "first", () -> true, () -> effects.add("first"), error -> fail(error));
    queue.submit(
        Path.of("same.sgf"),
        "edited",
        () -> true,
        () -> effects.add("edited"),
        error -> fail(error));
    var pending = queue.pending();
    assertFalse(pending.isDone());
    worker.remove().run();
    worker.remove().run();
    assertEquals(List.of("first", "edited"), writes);
    assertFalse(pending.isDone(), "Shutdown must also await owner-thread completion");
    owner.remove().run();
    owner.remove().run();
    assertEquals(List.of("first", "edited"), effects);
    assertTrue(pending.isDone());
  }

  @Test
  void failureAndRejectedTaskNeverRunSuccessOrLeaveShutdownPending() {
    AtomicInteger errors = new AtomicInteger();
    SgfSaveQueue failed =
        new SgfSaveQueue(
            Runnable::run,
            Runnable::run,
            (path, snapshot) -> {
              throw new IOException("disk full");
            });
    assertTrue(
        failed
            .submit(
                Path.of("a.sgf"),
                "a",
                () -> true,
                () -> fail("success"),
                error -> errors.incrementAndGet())
            .isCompletedExceptionally());
    assertTrue(failed.pending().isDone());
    SgfSaveQueue rejected =
        new SgfSaveQueue(
            task -> {
              throw new RejectedExecutionException();
            },
            Runnable::run,
            (path, snapshot) -> fail("must not write"));
    assertTrue(
        rejected
            .submit(
                Path.of("a.sgf"),
                "a",
                () -> true,
                () -> fail("success"),
                error -> errors.incrementAndGet())
            .isCompletedExceptionally());
    assertTrue(rejected.pending().isDone());
    assertEquals(2, errors.get());
  }
}
