package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SuggestionHoverIntentTest {
  private static final int TEST_DELAY_MS = 60_000;

  @Test
  void blocksOnlyTrackedCandidateUntilRevealed() {
    AtomicInteger refreshes = new AtomicInteger();
    SuggestionHoverIntent intent =
        new SuggestionHoverIntent(TEST_DELAY_MS, refreshes::incrementAndGet);

    intent.arm(3, 4);

    assertTrue(intent.isPending());
    assertFalse(intent.permits(3, 4));
    assertTrue(intent.permits(4, 4));

    intent.reveal();

    assertFalse(intent.isPending());
    assertTrue(intent.permits(3, 4));
    assertEquals(1, refreshes.get());
  }

  @Test
  void movingToAnotherCandidateReplacesPendingPreview() {
    AtomicInteger refreshes = new AtomicInteger();
    SuggestionHoverIntent intent =
        new SuggestionHoverIntent(TEST_DELAY_MS, refreshes::incrementAndGet);

    intent.arm(3, 4);
    intent.arm(7, 8);

    assertTrue(intent.permits(3, 4));
    assertFalse(intent.permits(7, 8));
    intent.reveal();
    assertTrue(intent.permits(7, 8));
    assertEquals(1, refreshes.get());
  }

  @Test
  void quickClickCancellationPreventsDelayedRefresh() {
    AtomicInteger refreshes = new AtomicInteger();
    SuggestionHoverIntent intent =
        new SuggestionHoverIntent(TEST_DELAY_MS, refreshes::incrementAndGet);

    intent.arm(3, 4);
    intent.cancel();
    intent.reveal();

    assertFalse(intent.isPending());
    assertTrue(intent.permits(3, 4));
    assertEquals(0, refreshes.get());
  }

  @Test
  void repeatedMotionInsideReadyCandidateDoesNotRestartDelay() {
    AtomicInteger refreshes = new AtomicInteger();
    SuggestionHoverIntent intent =
        new SuggestionHoverIntent(TEST_DELAY_MS, refreshes::incrementAndGet);

    intent.arm(3, 4);
    intent.reveal();
    intent.arm(3, 4);

    assertFalse(intent.isPending());
    assertTrue(intent.permits(3, 4));
    assertEquals(1, refreshes.get());
  }
}
