package featurecat.lizzie.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class UpdateAdmissionTest {
  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    Lizzie.config = null;
  }

  private static final String INSTALLED = "next-2026-08-01.1";
  private static final String NEWER = "next-2026-08-24.1";
  private static final String OLDER = "next-2026-07-01.1";

  @Test
  void officialChannelRejectsTestManifest() {
    UpdateAdmission.Result result =
        UpdateAdmission.evaluate(
            UpdateChannel.STABLE, INSTALLED, signed(NEWER, true), null);

    assertEquals(UpdateAdmission.Kind.NO_UPDATE, result.kind);
    assertNull(result.manifest);
    assertTrue(result.message.contains("official channel"));
  }

  @Test
  void testChannelAdmitsNewerTestManifest() {
    UpdateAdmission.Result result =
        UpdateAdmission.evaluate(
            UpdateChannel.BETA, INSTALLED, signed(NEWER, true), null);

    assertEquals(UpdateAdmission.Kind.OFFER, result.kind);
    assertEquals(NEWER, result.manifest.releaseTag);
  }

  @Test
  void testChannelRejectsOfficialManifest() {
    UpdateAdmission.Result result =
        UpdateAdmission.evaluate(
            UpdateChannel.BETA, INSTALLED, signed(NEWER, false), null);

    assertEquals(UpdateAdmission.Kind.ERROR, result.kind);
    assertNull(result.manifest);
    assertFalse(result.message.toLowerCase().contains("latest"));
  }

  @Test
  void testChannelRejectsUnsignedLegacyV1() {
    UpdateManifest unsigned = UpdateManifest.parse(UpdateManifestTest.validManifest());
    UpdateAdmission.Result result =
        UpdateAdmission.evaluate(
            UpdateChannel.BETA,
            INSTALLED,
            new UpdateAdmission.FetchedManifest(unsigned, false),
            null);

    assertEquals(UpdateAdmission.Kind.ERROR, result.kind);
    assertNull(result.manifest);
  }

  @Test
  void bothChannelsRejectNonNewerTags() {
    UpdateAdmission.Result officialEqual =
        UpdateAdmission.evaluate(
            UpdateChannel.STABLE, INSTALLED, signed(INSTALLED, false), null);
    UpdateAdmission.Result officialOlder =
        UpdateAdmission.evaluate(
            UpdateChannel.STABLE, INSTALLED, signed(OLDER, false), null);
    UpdateAdmission.Result testEqual =
        UpdateAdmission.evaluate(
            UpdateChannel.BETA, INSTALLED, signed(INSTALLED, true), null);
    UpdateAdmission.Result testOlder =
        UpdateAdmission.evaluate(
            UpdateChannel.BETA, NEWER, signed(INSTALLED, true), null);

    assertEquals(UpdateAdmission.Kind.NO_UPDATE, officialEqual.kind);
    assertEquals(UpdateAdmission.Kind.NO_UPDATE, officialOlder.kind);
    assertEquals(UpdateAdmission.Kind.NO_UPDATE, testEqual.kind);
    assertEquals(UpdateAdmission.Kind.NO_UPDATE, testOlder.kind);
  }

  @Test
  void officialChannelDoesNotDowngradeFromNewerTestInstall() {
    UpdateAdmission.Result result =
        UpdateAdmission.evaluate(
            UpdateChannel.STABLE, NEWER, signed(INSTALLED, false), null);

    assertEquals(UpdateAdmission.Kind.NO_UPDATE, result.kind);
    assertNull(result.manifest);
    assertTrue(result.message.contains("official channel"));
  }

  @Test
  void developmentBuildIsSkippedBeforeFetch() {
    assertFalse(UpdateAdmission.shouldFetch("next-dev"));
    assertFalse(UpdateAdmission.shouldFetch("local"));
    assertTrue(UpdateAdmission.shouldFetch(INSTALLED));

    UpdateAdmission.Result result =
        UpdateAdmission.evaluate(
            UpdateChannel.STABLE, "next-dev", signed(NEWER, false), null);

    assertEquals(UpdateAdmission.Kind.ERROR, result.kind);
    assertTrue(result.message.contains("development or unpackaged"));
  }

  @Test
  void testChannelFetchFailureIsErrorNotAlreadyLatest() {
    UpdateAdmission.Result result =
        UpdateAdmission.evaluate(
            UpdateChannel.BETA, INSTALLED, null, new IOException("HTTP 404"));

    assertEquals(UpdateAdmission.Kind.ERROR, result.kind);
    assertNull(result.manifest);
    assertTrue(result.message.contains("test channel"));
    assertFalse(result.message.toLowerCase().contains("latest"));
  }

  @Test
  void officialChannelFetchFailureNamesOfficialChannel() {
    UpdateAdmission.Result result =
        UpdateAdmission.evaluate(
            UpdateChannel.STABLE,
            INSTALLED,
            null,
            new IOException("all update download sources failed"));

    assertEquals(UpdateAdmission.Kind.ERROR, result.kind);
    assertNull(result.manifest);
    assertTrue(result.message.contains("official channel"));
    assertFalse(result.message.contains("R2"));
    assertFalse(result.message.contains("Cloudflare"));
    assertFalse(result.message.toLowerCase().contains("latest"));
  }

  @Test
  void officialChannelAdmitsNewerOfficialManifest() {
    UpdateAdmission.Result result =
        UpdateAdmission.evaluate(
            UpdateChannel.STABLE, INSTALLED, signed(NEWER, false), null);

    assertEquals(UpdateAdmission.Kind.OFFER, result.kind);
    assertEquals(NEWER, result.manifest.releaseTag);
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

  private static UpdateAdmission.FetchedManifest signed(String tag, boolean prerelease) {
    JSONObject payload = SignedUpdateEnvelopeTest.validPayload();
    payload.put("releaseTag", tag);
    payload.put("prerelease", prerelease);
    return new UpdateAdmission.FetchedManifest(UpdateManifest.parse(payload), true);
  }
}
