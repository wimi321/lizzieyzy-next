package featurecat.lizzie.gui.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WebBoardUpdateQueueTest {
  @Test
  void immediateBurstQueuesOneNotificationAndReadsTheLatestValue() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger current = new AtomicInteger();
    BlockingQueue<Integer> sent = new LinkedBlockingQueue<>();
    WebBoardUpdateQueue queue =
        new WebBoardUpdateQueue(executor, 100, u -> sent.add(current.get()));
    try {
      executor.execute(() -> block(entered, release));
      await(entered);
      for (int i = 1; i <= 50_000; i++) {
        current.set(i);
        queue.requestAnalysis();
      }
      assertEquals(1, executor.getQueue().size());
      release.countDown();
      assertEquals(50_000, sent.poll(5, TimeUnit.SECONDS));
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertTrue(sent.isEmpty());
      assertTrue(executor.getQueue().isEmpty());
    } finally {
      release.countDown();
      queue.close();
      executor.shutdownNow();
    }
  }

  @Test
  void slowBroadcastKeepsOneDirtyStateAndDoesNotCoalesceControlTasks() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger current = new AtomicInteger();
    AtomicInteger calls = new AtomicInteger();
    BlockingQueue<Integer> sent = new LinkedBlockingQueue<>();
    WebBoardUpdateQueue queue =
        new WebBoardUpdateQueue(
            executor,
            100,
            u -> {
              int value = current.get();
              if (calls.incrementAndGet() == 1) block(entered, release);
              sent.add(value);
            });
    try {
      queue.requestAnalysis();
      await(entered);
      for (int i = 1; i <= 50_000; i++) {
        current.set(i);
        queue.requestAnalysis();
      }
      assertTrue(executor.getQueue().isEmpty(), "running broadcast owns the only pending slot");
      List<Integer> controls = new ArrayList<>();
      CountDownLatch controlsDone = new CountDownLatch(100);
      for (int i = 0; i < 100; i++) {
        int id = i;
        executor.execute(
            () -> {
              controls.add(id);
              controlsDone.countDown();
            });
      }
      release.countDown();
      await(controlsDone);
      assertEquals(100, controls.size());
      for (int i = 0; i < 100; i++) assertEquals(i, controls.get(i));
      assertEquals(0, sent.poll(5, TimeUnit.SECONDS));
      assertEquals(50_000, sent.poll(5, TimeUnit.SECONDS));
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertTrue(sent.isEmpty());
    } finally {
      release.countDown();
      queue.close();
      executor.shutdownNow();
    }
  }

  @Test
  void fullStatePromotesDelayedAnalysisAndConsumesBothPendingNotifications() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    BlockingQueue<Boolean> sent = new LinkedBlockingQueue<>();
    WebBoardUpdateQueue queue =
        new WebBoardUpdateQueue(executor, 60_000, u -> sent.add(u.fullState));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      queue.requestAnalysis();
      assertEquals(false, sent.poll(5, TimeUnit.SECONDS));
      executor.execute(() -> block(entered, release));
      await(entered);
      queue.requestAnalysis();
      assertEquals(1, executor.getQueue().size());
      for (int i = 0; i < 50_000; i++) {
        queue.requestFullState();
        queue.requestAnalysis();
      }
      assertEquals(1, executor.getQueue().size(), "cancelled analysis timers must be removed");
      release.countDown();
      assertEquals(true, sent.poll(5, TimeUnit.SECONDS), "full state must not wait for rate limit");
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertTrue(sent.isEmpty());
      assertTrue(executor.getQueue().isEmpty());
    } finally {
      release.countDown();
      queue.close();
      executor.shutdownNow();
    }
  }

  @Test
  void updatesDuringSerializationAreDeliveredAtMostTenTimesPerSecond() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    BlockingQueue<Long> times = new LinkedBlockingQueue<>();
    WebBoardUpdateQueue[] holder = new WebBoardUpdateQueue[1];
    holder[0] =
        new WebBoardUpdateQueue(
            executor,
            100,
            u -> {
              times.add(System.nanoTime());
              for (int i = 0; i < 1000; i++) holder[0].requestAnalysis();
            });
    try {
      holder[0].requestAnalysis();
      Long previous = times.poll(5, TimeUnit.SECONDS);
      assertNotNull(previous);
      for (int i = 0; i < 4; i++) {
        Long current = times.poll(5, TimeUnit.SECONDS);
        assertNotNull(current);
        assertTrue(current - previous >= TimeUnit.MILLISECONDS.toNanos(100));
        previous = current;
      }
      holder[0].close();
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertTrue(executor.getQueue().isEmpty());
    } finally {
      holder[0].close();
      executor.shutdownNow();
    }
  }

  @Test
  void closeInvalidatesInFlightStateAndRemovesScheduledWork() throws Exception {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger sent = new AtomicInteger();
    WebBoardUpdateQueue queue =
        new WebBoardUpdateQueue(
            executor,
            100,
            u -> {
              block(entered, release);
              if (u.isCurrent()) sent.incrementAndGet();
            });
    try {
      queue.requestAnalysis();
      await(entered);
      queue.requestFullState();
      queue.close();
      for (int i = 0; i < 1000; i++) {
        queue.requestAnalysis();
        queue.requestFullState();
      }
      release.countDown();
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertEquals(0, sent.get());
      assertTrue(executor.getQueue().isEmpty());
    } finally {
      release.countDown();
      queue.close();
      executor.shutdownNow();
    }
  }

  @Test
  void shutdownExecutorRejectsNotificationsWithoutLeakingOrThrowing() {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    WebBoardUpdateQueue queue =
        new WebBoardUpdateQueue(executor, 100, u -> fail("closed executor cannot broadcast"));
    executor.shutdownNow();
    assertDoesNotThrow(
        () -> {
          queue.requestAnalysis();
          queue.requestFullState();
          queue.close();
        });
    assertTrue(executor.getQueue().isEmpty());
  }

  static void block(CountDownLatch entered, CountDownLatch release) {
    entered.countDown();
    await(release);
  }

  static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out waiting for test worker");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
