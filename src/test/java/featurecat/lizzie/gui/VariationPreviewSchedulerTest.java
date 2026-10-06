package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.analysis.Branch;
import featurecat.lizzie.rules.BoardData;
import java.awt.Color;
import java.awt.Font;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class VariationPreviewSchedulerTest {
  @Test
  void runningRevisionMakesProgressAndLatestPendingYieldsToOtherHost() {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    ArrayDeque<Runnable> edt = new ArrayDeque<>();
    VariationPreviewScheduler scheduler = new VariationPreviewScheduler(worker::add, edt::add);
    Object main = new Object();
    Object floating = new Object();
    List<Integer> published = new ArrayList<>();
    submit(scheduler, main, 1, published);
    submit(scheduler, floating, 2, published);
    for (int n = 3; n <= 100; n++) submit(scheduler, main, n, published);
    worker.remove().run();
    assertEquals(List.of(), published);
    edt.remove().run();
    assertEquals(List.of(1), published);
    worker.remove().run();
    edt.remove().run();
    assertEquals(List.of(1, 2), published);
    worker.remove().run();
    edt.remove().run();
    assertEquals(List.of(1, 2, 100), published);
    assertTrue(worker.isEmpty());
  }

  @Test
  void boardCancellationCanFinishWhilePublicationValidatesAndNextHostStillRuns() throws Exception {
    ArrayDeque<Runnable> worker = new ArrayDeque<>();
    ArrayDeque<Runnable> edt = new ArrayDeque<>();
    VariationPreviewScheduler scheduler = new VariationPreviewScheduler(worker::add, edt::add);
    Object board = new Object();
    Object host = new Object();
    CountDownLatch validating = new CountDownLatch(1);
    CountDownLatch boardHeld = new CountDownLatch(1);
    AtomicBoolean invalidated = new AtomicBoolean();
    AtomicBoolean published = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    submit(scheduler, host, 1, result -> {
      validating.countDown();
      try {
        assertTrue(boardHeld.await(5, TimeUnit.SECONDS));
        synchronized (board) {
          if (!invalidated.get()) published.set(true);
        }
      } catch (Throwable error) {
        failure.set(error);
      }
    });
    List<Integer> nextPublished = new ArrayList<>();
    submit(scheduler, new Object(), 2, nextPublished);
    worker.remove().run();
    Thread publication = new Thread(edt.remove());
    Thread cancellation = new Thread(() -> {
      try {
        assertTrue(validating.await(5, TimeUnit.SECONDS));
        synchronized (board) {
          boardHeld.countDown();
          invalidated.set(true);
          scheduler.cancel(host);
        }
      } catch (Throwable error) {
        failure.set(error);
      }
    });
    publication.setDaemon(true);
    cancellation.setDaemon(true);
    publication.start();
    cancellation.start();
    cancellation.join(5000);
    publication.join(5000);
    assertFalse(cancellation.isAlive(), "Board owner must not deadlock with publication");
    assertFalse(publication.isAlive(), "Publication must release the active turn");
    assertNull(failure.get());
    assertFalse(published.get(), "Invalidated result must stay retired");
    worker.remove().run();
    edt.remove().run();
    assertEquals(List.of(2), nextPublished);
  }

  private static void submit(VariationPreviewScheduler scheduler, Object host, int visits,
      List<Integer> published) {
    submit(scheduler, host, visits, result -> {
      assertEquals(Color.WHITE.getRGB(), result.stones().getRGB(80, 80));
      published.add(result.branch().pvVisitsList[4]);
    });
  }

  private static void submit(VariationPreviewScheduler scheduler, Object host, int visits,
      Consumer<VariationPreviewGenerator.Result> publish) {
    BoardData data = BoardData.empty(3, 3);
    Branch.Input input = new Branch.Input(new Branch.Position(3, 3, data, data.stones, true),
        List.of("B2", "C1"), List.of(Integer.toString(visits), "200"), 2, false, true);
    VariationPreviewGenerator.Geometry geometry =
        new VariationPreviewGenerator.Geometry(3, 3, 100, 100, 20, 20, 30, 30, 10, 1, 1);
    VariationPreviewGenerator.Style style = new VariationPreviewGenerator.Style(
        false, false, false, false, true, false, false, false, false, true, true, 0,
        199, 199, 0, new Font("Dialog", Font.PLAIN, 12), null, null, null, 0,
        Color.ORANGE, Color.ORANGE,
        VariationPreviewGenerator.captureSourceStones(data.stones, null), "pass");
    scheduler.submit(host, input, geometry, style, publish);
  }
}
