package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LizzieVersionTest {
  @Test
  void prefersReleasePropertyForDisplayVersion() {
    assertEquals(
        "1.0.0-next-2026-04-24.2",
        Lizzie.chooseNextVersion(" 1.0.0-next-2026-04-24.2 ", "ignored"));
  }

  @Test
  void fallsBackToEnvironmentDisplayVersion() {
    assertEquals(
        "1.0.0-next-2026-04-25.1", Lizzie.chooseNextVersion("", "1.0.0-next-2026-04-25.1"));
  }

  @Test
  void marksUnpackagedLocalBuildsAsDevelopmentBuilds() {
    assertEquals("1.0.0-dev", Lizzie.chooseNextVersion(null, " "));
  }
}
