package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GtpResponseTimeoutSchedulerTest {
  @Test
  void blockedCommandDispatcherCannotHoldUpOtherEngineDeadlines() throws Exception {
    var scheduler = GtpResponseTimeoutScheduler.create();
    var commands = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
    var expired = new java.util.concurrent.atomic.AtomicBoolean();
    try {
      GtpResponseTimeoutScheduler.schedule(scheduler, commands::add, () -> expired.set(true), 0)
          .get(2, TimeUnit.SECONDS);
      assertFalse(expired.get(), "the clock must not execute transport callbacks");
      assertTrue(scheduler.schedule(() -> true, 0, TimeUnit.MILLISECONDS).get(2, TimeUnit.SECONDS));
      commands.remove().run();
      assertTrue(expired.get());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void burstUsesOneWorkerAndAcknowledgementsRemoveCancelledDeadlines() {
    var scheduler = GtpResponseTimeoutScheduler.create();
    try {
      var deadlines = new ArrayList<ScheduledFuture<?>>();
      for (int i = 0; i < 512; i++) {
        deadlines.add(scheduler.schedule(() -> fail("cancelled deadline ran"), 1, TimeUnit.DAYS));
      }
      assertEquals(1, scheduler.getLargestPoolSize());
      assertEquals(512, scheduler.getQueue().size());
      deadlines.forEach(deadline -> deadline.cancel(false));
      assertTrue(scheduler.getQueue().isEmpty(), "cancelled commands must not retain the engine");
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void timeoutFailureDoesNotKillFollowingDeadlineAndWorkerIsDaemon() throws Exception {
    var scheduler = GtpResponseTimeoutScheduler.create();
    try {
      var failed =
          scheduler.schedule(
              () -> {
                throw new IllegalStateException("fixture");
              },
              0,
              TimeUnit.MILLISECONDS);
      assertThrows(ExecutionException.class, () -> failed.get(2, TimeUnit.SECONDS));
      var next =
          scheduler.schedule(() -> Thread.currentThread().isDaemon(), 0, TimeUnit.MILLISECONDS);
      assertTrue(next.get(2, TimeUnit.SECONDS));
      assertEquals(1, scheduler.getLargestPoolSize());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void cancellingACompletedResponseDoesNotInterruptTheDeadlineWorker() throws Exception {
    var scheduler = GtpResponseTimeoutScheduler.create();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(1);
    try {
      var future =
          scheduler.schedule(
              () -> {
                entered.countDown();
                try {
                  if (release.await(2, TimeUnit.SECONDS) && !Thread.currentThread().isInterrupted())
                    completed.countDown();
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                }
              },
              0,
              TimeUnit.MILLISECONDS);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      future.cancel(false);
      release.countDown();
      assertTrue(completed.await(2, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      scheduler.shutdownNow();
    }
  }
}
