package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.analysis.Branch;
import featurecat.lizzie.rules.BoardData;
import java.awt.Color;
import java.awt.Font;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VariationPreviewPublicationTest {
  private final ArrayDeque<Runnable> worker = new ArrayDeque<>();
  private final ArrayDeque<Runnable> edt = new ArrayDeque<>();
  private final VariationPreviewScheduler scheduler =
      new VariationPreviewScheduler(worker::add, edt::add);
  private final VariationPreviewState state = new VariationPreviewState(scheduler);
  private final BoardData data = BoardData.empty(3, 3);
  private VariationPreviewGenerator.Geometry geometry =
      new VariationPreviewGenerator.Geometry(3, 3, 100, 100, 20, 20, 30, 30, 10, 1, 1);
  private final VariationPreviewGenerator.Style style = new VariationPreviewGenerator.Style(
      false, false, false, false, true, false, false, false, false, true, true, 0,
      199, 199, 0, new Font("Dialog", Font.PLAIN, 12), null, null, null, 0,
      Color.ORANGE, Color.ORANGE,
      VariationPreviewGenerator.captureSourceStones(data.stones, null), "pass");
  private VariationPreviewState.Mode mode =
      new VariationPreviewState.Mode(ExtraMode.Normal, false, false);
  private VariationPreviewState.Selection live;

  private VariationPreviewState.Selection selection(String coordinate, int visits) {
    Branch.Input input = new Branch.Input(new Branch.Position(3, 3, data, data.stones, true),
        List.of(coordinate, "C1"), List.of(Integer.toString(visits), "200"), 2, false, true);
    return new VariationPreviewState.Selection(null, coordinate, input, 2);
  }

  private void request() {
    state.request(state.generation(), live, geometry, style, mode, this::request, result -> {});
  }

  private void select(String coordinate, int visits) {
    live = selection(coordinate, visits);
    state.select(live);
    request();
  }

  private void finish() {
    worker.remove().run();
    edt.remove().run();
  }

  @Test
  void replacementKeepsCompletePictureUntilTheLatestTargetPublishes() {
    select("B2", 1);
    finish();
    var visible = state.published();
    state.setDisplayedLength(1);
    live = state.selected();
    request();
    assertSame(visible, state.published());
    select("A3", 3);
    assertSame(visible, state.published());
    assertEquals("B2", state.applicationSelection().coordinate());
    assertEquals(2, state.applicationSelection().displayedLength());
    assertEquals("A3", state.selected().coordinate());
    finish();
    assertSame(visible, state.published(), "retired step cannot replace the displayed revision");
    finish();
    assertEquals(3, state.published().branch().pvVisitsList[0]);
    assertEquals("A3", state.applicationSelection().coordinate());
    geometry = new VariationPreviewGenerator.Geometry(3, 3, 150, 150, 30, 30, 40, 40, 15, 1, 1);
    request();
    assertNull(state.published(), "a resized surface must not retain an incorrectly scaled image");
    finish();
    assertEquals(150, state.published().stones().getWidth());
    state.clear();
    assertNull(state.published());
  }


  @Test
  void lateA1CannotPublishOverReselectedA2() {
    select("B2", 1);
    worker.remove().run();
    select("A3", 2);
    select("B2", 3);
    edt.remove().run();
    assertNull(state.published());
    assertEquals(List.of("3", "200"), state.selected().input().pvVisits);
    finish();
    assertEquals(3, state.published().branch().pvVisitsList[4]);
    assertEquals(Color.WHITE.getRGB(), state.published().stones().getRGB(80, 80));
  }

  @Test
  void liveGeometryAndModeChangesAreCheckedAtPublicationWithoutAnotherPaint() {
    select("B2", 1);
    worker.remove().run();
    geometry = new VariationPreviewGenerator.Geometry(3, 3, 150, 150, 30, 30, 40, 40, 15, 1, 1);
    edt.remove().run();
    assertNull(state.published());
    worker.remove().run();
    mode = new VariationPreviewState.Mode(ExtraMode.Normal, true, false);
    edt.remove().run();
    assertNull(state.published());
    finish();
    assertEquals(150, state.published().stones().getWidth());
    assertEquals(1, state.published().branch().pvVisitsList[4]);
  }

  @Test
  void updatesPreserveVisibleSelectionUntilWholeNewResultAndEventuallyCatchUp() {
    select("B2", 1);
    for (int i = 2; i <= 100; i++) {
      live = selection("B2", i);
      request();
    }
    assertEquals(List.of("1", "200"), state.selected().input().pvVisits);
    finish();
    assertEquals(1, state.published().branch().pvVisitsList[4]);
    assertEquals(List.of("1", "200"), state.selected().input().pvVisits);
    finish();
    assertEquals(100, state.published().branch().pvVisitsList[4]);
    assertEquals(List.of("100", "200"), state.selected().input().pvVisits);
    var cached = state.published();
    request();
    assertSame(cached, state.published());
    assertTrue(worker.isEmpty());
  }

  @Test
  void cancelKeepsInputButClearAndClosePreventQueuedResultsFromReturning() {
    select("B2", 1);
    worker.remove().run();
    state.cancelPreview();
    edt.remove().run();
    assertNull(state.published());
    assertEquals(List.of("B2", "C1"), state.selected().input().variation);
    select("B2", 2);
    worker.remove().run();
    state.clear();
    edt.remove().run();
    assertNull(state.selected());
    assertNull(state.published());
    select("B2", 3);
    worker.remove().run();
    scheduler.close();
    edt.remove().run();
    assertNull(state.published());
  }

  @Test
  void explicitLengthRetiresRefreshAndPublishesTheSelectedPrefixFirst() {
    select("B2", 1);
    finish();
    live = selection("B2", 2);
    request();
    worker.remove().run();
    state.setDisplayedLength(1);
    live = state.selected();
    request();
    edt.remove().run();
    assertEquals(2, state.published().branch().length);
    assertEquals(2, state.applicationSelection().displayedLength());
    finish();
    assertEquals(1, state.published().branch().length);
    assertEquals(List.of("1", "200"), state.selected().input().pvVisits);
  }

  @Test
  void clearDuringValidationRejectsPublicationWithoutHoldingTheStateMonitor() {
    live = selection("B2", 1);
    state.select(live);
    state.request(state.generation(), live, geometry, style, mode,
        () -> runWriter(state::clear), result -> fail("retired result consumed"));
    finish();
    assertNull(state.selected());
    assertNull(state.published());
  }

  @Test
  void newerStepDuringValidationIsNotOverwrittenByTheCompletedRequest() {
    live = selection("B2", 1);
    state.select(live);
    state.request(state.generation(), live, geometry, style, mode,
        () -> runWriter(() -> state.setDisplayedLength(1)),
        result -> fail("old prefix consumed"));
    finish();
    assertEquals(1, state.selected().displayedLength());
    assertNull(state.published());
    live = state.selected();
    request();
    finish();
    assertEquals(1, state.published().branch().length);
  }

  @Test
  void clearCannotSplitPublicationFromRendererConsumption() throws Exception {
    live = selection("B2", 1);
    state.select(live);
    CountDownLatch clearing = new CountDownLatch(1);
    CountDownLatch cleared = new CountDownLatch(1);
    AtomicReference<VariationPreviewGenerator.Result> visible = new AtomicReference<>();
    Thread writer = new Thread(() -> {
      clearing.countDown();
      synchronized (state) {
        state.clear();
        visible.set(null);
      }
      cleared.countDown();
    });
    state.request(state.generation(), live, geometry, style, mode, () -> {}, result -> {
      writer.start();
      await(clearing);
      assertSame(result, state.published());
      visible.set(result);
      assertEquals(2, result.branch().length);
      assertEquals(1L, cleared.getCount(), "clear must follow whole-result consumption");
    });
    finish();
    await(cleared);
    writer.join(2000);
    assertFalse(writer.isAlive());
    assertNull(visible.get());
    assertNull(state.published());
    assertNull(state.selected());
  }

  @Test
  void captureAndReplayTokensRejectClearReselectEvenAtTheSameCoordinate() {
    select("B2", 1);
    long capture = state.generation();
    long target = state.replayTarget();
    assertTrue(state.setDisplayedLength(1));
    assertEquals(target, state.replayTarget());
    state.clear();
    assertEquals(-1, state.selectIfCurrent(capture, live));
    select("B2", 1);
    assertNotEquals(target, state.replayTarget());
    assertEquals(2, state.selected().displayedLength());
  }

  private static void runWriter(Runnable action) {
    CountDownLatch finished = new CountDownLatch(1);
    Thread writer = new Thread(() -> {
      try {
        action.run();
      } finally {
        finished.countDown();
      }
    });
    writer.setDaemon(true);
    writer.start();
    await(finished);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(2, TimeUnit.SECONDS), "preview operation blocked");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
