package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class KifuLoadFinisherTest {

  @Test
  void completionRunsEvenWhenRefreshThrows() throws Exception {
    CountDownLatch completeLatch = new CountDownLatch(1);
    AtomicInteger refreshCount = new AtomicInteger();
    AtomicInteger failureCount = new AtomicInteger();
    AtomicBoolean completedOnEdt = new AtomicBoolean(false);

    KifuLoadFinisher.finishAfterRefresh(
        new Runnable() {
          public void run() {
            refreshCount.incrementAndGet();
            throw new RuntimeException("simulated paint failure");
          }
        },
        new Runnable() {
          public void run() {
            completedOnEdt.set(SwingUtilities.isEventDispatchThread());
            completeLatch.countDown();
          }
        },
        1,
        t -> failureCount.incrementAndGet());

    assertTrue(
        completeLatch.await(2, TimeUnit.SECONDS), "finish callback should not get stranded.");
    assertEquals(1, refreshCount.get(), "refresh should be attempted exactly once.");
    assertEquals(1, failureCount.get(), "refresh failure should be reported once.");
    assertTrue(completedOnEdt.get(), "Swing completion should run on the EDT.");
  }
}
