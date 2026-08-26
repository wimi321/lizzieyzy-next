package featurecat.lizzie.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateChannelSourceTest {
  @TempDir Path tempDir;

  private featurecat.lizzie.Config previousConfig;

  @BeforeEach
  void setUp() {
    previousConfig = Lizzie.config;
    Lizzie.config = null;
  }

  @AfterEach
  void tearDown() {
    Lizzie.config = previousConfig;
  }

  @Test
  void missingUpdateChannelConfigIsOfficial() {
    assertEquals(UpdateChannel.STABLE, UpdateChannel.fromConfigValue(null));
    assertEquals(UpdateChannel.STABLE, UpdateChannel.fromConfigValue(""));
    assertEquals(UpdateChannel.STABLE, UpdateChannel.fromConfigValue("stable"));
    assertEquals(UpdateChannel.BETA, UpdateChannel.fromConfigValue("beta"));
    assertEquals("update-channel", UpdateChannel.CONFIG_KEY);
  }

  @Test
  void persistingChannelWritesUiConfigOnly() {
    Lizzie.config = ConfigTestHelper.createForTests(tempDir.resolve("channel-config"));
    Lizzie.config.uiConfig = new JSONObject();

    assertEquals(UpdateChannel.STABLE, UpdateChannel.current());
    UpdateChannel.persist(UpdateChannel.BETA);

    assertEquals("beta", Lizzie.config.uiConfig.getString(UpdateChannel.CONFIG_KEY));
    assertEquals(UpdateChannel.BETA, UpdateChannel.current());
    assertFalse(Lizzie.config.uiConfig.has("update-source"));
  }

  @Test
  void missingUpdateSourceConfigIsOfficial() {
    assertEquals(UpdateSource.OFFICIAL_SITE, UpdateSource.fromConfigValue(null));
    assertEquals(UpdateSource.OFFICIAL_SITE, UpdateSource.fromConfigValue(""));
    assertEquals(UpdateSource.OFFICIAL_SITE, UpdateSource.fromConfigValue("official"));
    assertEquals(UpdateSource.OFFICIAL_SITE, UpdateSource.fromConfigValue("unknown"));
    assertEquals(UpdateSource.GITHUB, UpdateSource.fromConfigValue("github"));
    assertEquals("update-source", UpdateSource.CONFIG_KEY);
  }

  @Test
  void persistingSourceWritesUiConfigOnly() {
    Lizzie.config = ConfigTestHelper.createForTests(tempDir.resolve("source-config"));
    Lizzie.config.uiConfig = new JSONObject();

    assertEquals(UpdateSource.OFFICIAL_SITE, UpdateSource.current());
    UpdateSource.persist(UpdateSource.GITHUB);

    assertEquals("github", Lizzie.config.uiConfig.getString(UpdateSource.CONFIG_KEY));
    assertEquals(UpdateSource.GITHUB, UpdateSource.current());
    assertFalse(Lizzie.config.uiConfig.has(UpdateChannel.CONFIG_KEY));
  }

  @Test
  void persistingChannelDoesNotChangeExistingSource() {
    Lizzie.config = ConfigTestHelper.createForTests(tempDir.resolve("source-channel-isolation"));
    Lizzie.config.uiConfig = new JSONObject();

    UpdateSource.persist(UpdateSource.GITHUB);
    UpdateChannel.persist(UpdateChannel.BETA);

    assertEquals(UpdateSource.GITHUB, UpdateSource.current());
    assertEquals(UpdateChannel.BETA, UpdateChannel.current());
  }
}
