package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Startup must survive a failing local host name lookup, and the fallback must reuse the stored
 * name so a resolver failure is never mistaken for a machine change (which wipes persist data).
 */
class LizzieHostNameTest {
  @Test
  void fallbackKeepsStoredHostName() {
    assertEquals("my-mac", Lizzie.hostNameFallback("my-mac"));
  }

  @Test
  void fallbackUsesPlaceholderWhenNothingStored() {
    assertEquals("unknown-host", Lizzie.hostNameFallback(""));
    assertEquals("unknown-host", Lizzie.hostNameFallback(null));
  }

  @Test
  void successfulLookupReturnsResolvedName() {
    assertEquals(
        "resolved-host", Lizzie.resolveHostNameSafely("stored-name", () -> "resolved-host", 100));
  }

  @Test
  void failedLookupFallsBackWithoutChangingMachineIdentity() {
    String resolved =
        Lizzie.resolveHostNameSafely(
            "stored-name",
            () -> {
              throw new IOException("resolver unavailable");
            },
            100);

    assertEquals("stored-name", resolved);
    assertFalse(Lizzie.shouldTreatAsHostChange("stored-name", resolved));
  }

  @Test
  void stalledLookupHonorsStartupDeadline() {
    long started = System.nanoTime();
    String resolved =
        Lizzie.resolveHostNameSafely(
            "stored-name",
            () -> {
              Thread.sleep(5_000L);
              return "late-host";
            },
            25L);

    long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
    assertEquals("stored-name", resolved);
    assertTrue(elapsedMillis < 500L, "hostname timeout must not stall startup");
  }

  @Test
  void recoveredFirstRunPlaceholderDoesNotLookLikeMachineChange() {
    assertFalse(Lizzie.shouldTreatAsHostChange("unknown-host", "resolved-host"));
    assertFalse(Lizzie.shouldTreatAsHostChange("", "resolved-host"));
    assertTrue(Lizzie.shouldTreatAsHostChange("old-host", "new-host"));
  }
}
