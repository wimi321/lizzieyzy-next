package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KifuEngineSyncCoordinatorTest {

  @Test
  void newerKifuSupersedesBlockedOlderRestore() throws Exception {
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    KifuEngineSyncCoordinator coordinator = new KifuEngineSyncCoordinator(executor);
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondCompleted = new CountDownLatch(1);
    List<String> completions = new CopyOnWriteArrayList<>();
    try {
      coordinator.submit(
          request(
              () -> {
                firstStarted.countDown();
                await(releaseFirst);
                return KifuEngineSyncCoordinator.AttemptResult.COMPLETE;
              },
              () -> completions.add("first")));
      assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

      coordinator.submit(
          request(
              () -> KifuEngineSyncCoordinator.AttemptResult.COMPLETE,
              () -> {
                completions.add("second");
                secondCompleted.countDown();
              }));
      releaseFirst.countDown();

      assertTrue(secondCompleted.await(2, TimeUnit.SECONDS));
      assertEquals(List.of("second"), completions);
    } finally {
      coordinator.close();
    }
  }

  @Test
  void transientFailureRetriesUntilTheCurrentKifuSynchronizes() throws Exception {
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    KifuEngineSyncCoordinator coordinator = new KifuEngineSyncCoordinator(executor);
    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch completed = new CountDownLatch(1);
    try {
      coordinator.submit(
          request(
              () -> {
                if (attempts.incrementAndGet() < 3) {
                  throw new IllegalStateException("temporary engine occupancy");
                }
                return KifuEngineSyncCoordinator.AttemptResult.COMPLETE;
              },
              completed::countDown));

      assertTrue(completed.await(3, TimeUnit.SECONDS));
      assertEquals(3, attempts.get());
    } finally {
      coordinator.close();
    }
  }

  @Test
  void requestThatStopsMatchingTheDisplayedKifuNeverCompletes() throws Exception {
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    KifuEngineSyncCoordinator coordinator = new KifuEngineSyncCoordinator(executor);
    AtomicBoolean current = new AtomicBoolean(true);
    AtomicBoolean completed = new AtomicBoolean();
    CountDownLatch attemptStarted = new CountDownLatch(1);
    CountDownLatch releaseAttempt = new CountDownLatch(1);
    try {
      coordinator.submit(
          new KifuEngineSyncCoordinator.Request() {
            @Override
            public boolean isCurrent() {
              return current.get();
            }

            @Override
            public KifuEngineSyncCoordinator.AttemptResult synchronize() {
              attemptStarted.countDown();
              await(releaseAttempt);
              return KifuEngineSyncCoordinator.AttemptResult.COMPLETE;
            }

            @Override
            public void onSynchronized() {
              completed.set(true);
            }
          });
      assertTrue(attemptStarted.await(1, TimeUnit.SECONDS));
      current.set(false);
      releaseAttempt.countDown();
      Thread.sleep(150L);

      assertFalse(completed.get());
    } finally {
      coordinator.close();
    }
  }

  private static KifuEngineSyncCoordinator.Request request(
      java.util.function.Supplier<KifuEngineSyncCoordinator.AttemptResult> attempt,
      Runnable completion) {
    return new KifuEngineSyncCoordinator.Request() {
      @Override
      public boolean isCurrent() {
        return true;
      }

      @Override
      public KifuEngineSyncCoordinator.AttemptResult synchronize() {
        return attempt.get();
      }

      @Override
      public void onSynchronized() {
        completion.run();
      }
    };
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }
}
