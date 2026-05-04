package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LizzieVersionTest {
  @Test
  void prefersReleasePropertyForDisplayVersion() {
    assertEquals("next-2026-05-03.1", Lizzie.chooseNextVersion(" next-2026-05-03.1 ", "ignored"));
  }

  @Test
  void fallsBackToEnvironmentDisplayVersion() {
    assertEquals("next-2026-05-03.1", Lizzie.chooseNextVersion("", "next-2026-05-03.1"));
  }

  @Test
  void marksUnpackagedLocalBuildsAsDevelopmentBuilds() {
    assertEquals("next-dev", Lizzie.chooseNextVersion(null, " "));
  }
}
