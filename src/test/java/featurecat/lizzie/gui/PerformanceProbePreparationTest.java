package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class PerformanceProbePreparationTest {
  @Test
  void waitsForProductionRestoreAndStopAcknowledgementBeforeClearingCache() throws Exception {
    CountDownLatch waitingForRestore = new CountDownLatch(1);
    CompletableFuture<Void> restored =
        new CompletableFuture<>() {
          @Override
          public Void get(long timeout, TimeUnit unit)
              throws InterruptedException,
                  ExecutionException,
                  java.util.concurrent.TimeoutException {
            waitingForRestore.countDown();
            return super.get(timeout, unit);
          }
        };
    List<String> actions = new CopyOnWriteArrayList<>();
    CountDownLatch stopSent = new CountDownLatch(1);
    CountDownLatch stopAcknowledged = new CountDownLatch(1);
    FutureTask<Void> preparation =
        new FutureTask<>(
            () -> {
              PerformanceProbePreparation.realtime(
                  restored,
                  () -> actions.add("arm"),
                  command -> {
                    actions.add(command);
                    if (command.equals("stop")) {
                      stopSent.countDown();
                      if (!stopAcknowledged.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("Test stop ACK not released");
                    }
                  });
              return null;
            });
    Thread worker = new Thread(preparation, "test-measurement-preparation");
    worker.start();
    try {
      assertTrue(waitingForRestore.await(5, TimeUnit.SECONDS));
      assertTrue(actions.isEmpty(), "Even stop must not overtake deferred restore work");
      restored.complete(null);
      assertTrue(stopSent.await(5, TimeUnit.SECONDS));
      assertEquals(List.of("arm", "stop"), actions);
      assertFalse(preparation.isDone());
      stopAcknowledged.countDown();
      preparation.get(5, TimeUnit.SECONDS);
      assertEquals(List.of("arm", "stop", "clear_cache"), actions);
    } finally {
      restored.completeExceptionally(new IllegalStateException("Test cleanup"));
      stopAcknowledged.countDown();
      worker.join(5000);
    }
  }

  @Test
  void failedOrSupersededRestoreDoesNotArmSearchOrClearCache() {
    List<String> actions = new CopyOnWriteArrayList<>();
    assertThrows(
        ExecutionException.class,
        () ->
            PerformanceProbePreparation.realtime(
                CompletableFuture.failedFuture(new IllegalStateException("Sync target superseded")),
                () -> actions.add("arm"),
                actions::add));
    assertTrue(actions.isEmpty());
  }

  @Test
  void failedStopDoesNotClearCache() {
    List<String> actions = new CopyOnWriteArrayList<>();
    assertThrows(
        IllegalStateException.class,
        () ->
            PerformanceProbePreparation.realtime(
                CompletableFuture.completedFuture(null),
                () -> actions.add("arm"),
                command -> {
                  actions.add(command);
                  throw new IllegalStateException("No stop ACK");
                }));
    assertEquals(List.of("arm", "stop"), actions);
  }

  @Test
  void refusesToBlockEdtOnRestoration() throws Exception {
    SwingUtilities.invokeAndWait(
        () ->
            assertThrows(
                IllegalStateException.class,
                () ->
                    PerformanceProbePreparation.realtime(
                        new CompletableFuture<>(), () -> {}, ignored -> {})));
  }
}
