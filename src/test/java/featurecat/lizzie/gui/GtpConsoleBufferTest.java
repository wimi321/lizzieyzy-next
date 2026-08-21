package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.util.DocType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GtpConsoleBufferTest {
  @Test
  void dropsOldestWhenFullAndNeverExceedsCapacity() {
    GtpConsoleBuffer buffer = new GtpConsoleBuffer();
    for (int i = 0; i < GtpConsoleBuffer.CAPACITY + 25; i++) {
      buffer.offer(doc("line-" + i));
    }
    assertEquals(GtpConsoleBuffer.CAPACITY, buffer.size());
    assertEquals("line-25", buffer.poll().content);
  }

  @Test
  void offerReturnsWhileConsumerIsStalled() throws Exception {
    GtpConsoleBuffer buffer = new GtpConsoleBuffer();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch hold = new CountDownLatch(1);
    AtomicBoolean producerFinished = new AtomicBoolean();
    Thread consumer =
        new Thread(
            () -> {
              started.countDown();
              try {
                hold.await(2, TimeUnit.SECONDS);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              buffer.poll();
            });
    consumer.start();
    assertTrue(started.await(1, TimeUnit.SECONDS));
    long began = System.nanoTime();
    buffer.offer(doc("producer"));
    producerFinished.set(true);
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 250);
    hold.countDown();
    consumer.join(1000L);
    assertTrue(producerFinished.get());
  }

  private static DocType doc(String content) {
    DocType type = new DocType();
    type.content = content;
    return type;
  }
}
