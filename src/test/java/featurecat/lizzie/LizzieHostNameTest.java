package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
  void resolveNeverReturnsNullOrEmpty() {
    String resolved = Lizzie.resolveHostNameSafely("stored-name");
    assertFalse(resolved == null || resolved.isEmpty());
  }
}
