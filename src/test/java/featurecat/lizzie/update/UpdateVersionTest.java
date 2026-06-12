package featurecat.lizzie.update;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UpdateVersionTest {
  @Test
  void comparesNextReleaseTagsByDateAndSerial() {
    assertTrue(UpdateVersion.isNewerThan("next-2026-06-12.1", "next-2026-06-11.2"));
    assertTrue(UpdateVersion.isNewerThan("next-2026-06-12.2", "next-2026-06-12.1"));
    assertFalse(UpdateVersion.isNewerThan("next-2026-06-11.2", "next-2026-06-12.1"));
  }

  @Test
  void skipsDevelopmentBuildsForAutomaticChecks() {
    assertTrue(UpdateVersion.shouldSkipAutomaticCheck("next-dev"));
    assertTrue(UpdateVersion.shouldSkipAutomaticCheck(""));
    assertFalse(UpdateVersion.shouldSkipAutomaticCheck("next-2026-06-12.1"));
  }
}
