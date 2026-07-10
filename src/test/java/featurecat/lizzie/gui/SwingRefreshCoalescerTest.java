package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class SwingRefreshCoalescerTest {
  @Test
  void burstRequestsRunOnceOnEventThread() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    AtomicBoolean ranOnEventThread = new AtomicBoolean();
    CountDownLatch firstRun = new CountDownLatch(1);
    SwingRefreshCoalescer coalescer =
        new SwingRefreshCoalescer(
            25,
            () -> {
              runs.incrementAndGet();
              ranOnEventThread.set(SwingUtilities.isEventDispatchThread());
              firstRun.countDown();
            });

    for (int i = 0; i < 100; i++) {
      coalescer.request();
    }

    assertTrue(firstRun.await(2, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(() -> {});
    assertEquals(1, runs.get());
    assertTrue(ranOnEventThread.get());
  }

  @Test
  void laterRequestRunsAfterMinimumInterval() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    CountDownLatch runsCompleted = new CountDownLatch(2);
    SwingRefreshCoalescer coalescer =
        new SwingRefreshCoalescer(
            30,
            () -> {
              runs.incrementAndGet();
              runsCompleted.countDown();
            });

    coalescer.request();
    while (runs.get() == 0) {
      Thread.sleep(2L);
    }
    coalescer.request();

    assertTrue(runsCompleted.await(2, TimeUnit.SECONDS));
    assertEquals(2, runs.get());
  }

  @Test
  void requestArrivingDuringTaskIsNotLost() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    CountDownLatch taskStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstTask = new CountDownLatch(1);
    CountDownLatch secondRun = new CountDownLatch(1);
    SwingRefreshCoalescer coalescer =
        new SwingRefreshCoalescer(
            5,
            () -> {
              int run = runs.incrementAndGet();
              if (run == 1) {
                taskStarted.countDown();
                try {
                  releaseFirstTask.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              } else {
                secondRun.countDown();
              }
            });

    coalescer.request();
    assertTrue(taskStarted.await(2, TimeUnit.SECONDS));
    coalescer.request();
    releaseFirstTask.countDown();

    assertTrue(secondRun.await(2, TimeUnit.SECONDS));
    assertEquals(2, runs.get());
  }
}
