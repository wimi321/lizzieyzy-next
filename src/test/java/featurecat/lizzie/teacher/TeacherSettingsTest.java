package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.remote.CredentialStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TeacherSettingsTest {
  @TempDir Path temporaryDirectory;

  @Test
  void removesLegacyPlaintextKeyAndKeepsItOnlyForCurrentSession() throws Exception {
    Path settingsFile = temporaryDirectory.resolve("teacher.properties");
    Properties legacy = new Properties();
    legacy.setProperty("baseUrl", "https://example.test/v1");
    legacy.setProperty("model", "test-model");
    legacy.setProperty("apiKey", "plain-secret");
    try (java.io.OutputStream output = Files.newOutputStream(settingsFile)) {
      legacy.store(output, "legacy");
    }

    TeacherSettings settings = new TeacherSettings(settingsFile, new MemoryCredentialStore(true));
    TeacherSettings.Snapshot snapshot = settings.load();

    assertTrue(snapshot.hasApiKey);
    assertFalse(snapshot.rememberApiKey);
    assertEquals("plain-secret", settings.apiKey().orElseThrow());
    String persisted = Files.readString(settingsFile);
    assertFalse(persisted.contains("plain-secret"));
    assertFalse(persisted.contains("apiKey="));
  }

  @Test
  void remembersApiKeyOnlyThroughCredentialStore() throws Exception {
    Path settingsFile = temporaryDirectory.resolve("teacher.properties");
    MemoryCredentialStore store = new MemoryCredentialStore(true);
    TeacherSettings first = new TeacherSettings(settingsFile, store);
    first.load();
    first.save("https://provider.example/v1", "model-a", "secret-a".toCharArray(), true);

    String persisted = Files.readString(settingsFile);
    assertFalse(persisted.contains("secret-a"));
    assertTrue(persisted.contains("rememberApiKey=true"));

    TeacherSettings second = new TeacherSettings(settingsFile, store);
    TeacherSettings.Snapshot snapshot = second.load();
    assertTrue(snapshot.rememberApiKey);
    assertTrue(snapshot.hasApiKey);
    assertEquals("secret-a", second.apiKey().orElseThrow());
  }

  @Test
  void unavailableSecureStoreRejectsRememberButAllowsSessionOnlyKey() throws Exception {
    Path settingsFile = temporaryDirectory.resolve("teacher.properties");
    TeacherSettings settings = new TeacherSettings(settingsFile, new MemoryCredentialStore(false));
    settings.load();

    assertThrows(
        IOException.class,
        () ->
            settings.save("https://provider.example/v1", "model-a", "secret".toCharArray(), true));

    TeacherSettings.Snapshot snapshot =
        settings.save("https://provider.example/v1", "model-a", "secret".toCharArray(), false);
    assertTrue(snapshot.hasApiKey);
    assertFalse(snapshot.rememberApiKey);
    Properties persisted = new Properties();
    try (java.io.InputStream input = Files.newInputStream(settingsFile)) {
      persisted.load(input);
    }
    assertFalse(persisted.containsKey("apiKey"));
    assertTrue(persisted.values().stream().noneMatch("secret"::equals));
  }

  @Test
  void clearingThePasswordFieldClearsSessionAndSecureCopies() throws Exception {
    Path settingsFile = temporaryDirectory.resolve("teacher.properties");
    MemoryCredentialStore store = new MemoryCredentialStore(true);
    TeacherSettings settings = new TeacherSettings(settingsFile, store);
    settings.load();
    settings.save("https://provider.example/v1", "model-a", "secret".toCharArray(), true);

    TeacherSettings.Snapshot cleared =
        settings.save("https://provider.example/v1", "model-a", new char[0], false);

    assertFalse(cleared.hasApiKey);
    assertFalse(cleared.rememberApiKey);
    assertTrue(settings.apiKey().isEmpty());
    assertTrue(
        store
            .read(
                CredentialStore.Kind.API_KEY,
                TeacherSettings.credentialAccount("https://provider.example/v1"))
            .isEmpty());
  }

  @Test
  void anEmptyKeyCannotSilentlyReuseThePreviousKeyWhenRemembering() throws Exception {
    Path settingsFile = temporaryDirectory.resolve("teacher.properties");
    TeacherSettings settings = new TeacherSettings(settingsFile, new MemoryCredentialStore(true));
    settings.load();
    settings.save("https://provider.example/v1", "model-a", "secret".toCharArray(), true);

    assertThrows(
        IOException.class,
        () -> settings.save("https://provider.example/v1", "model-a", new char[0], true));
    assertEquals("secret", settings.apiKey().orElseThrow());
  }

  @Test
  void freshSettingsStayBlankAcrossPreferenceSaveAndReload() throws Exception {
    Path file = temporaryDirectory.resolve("new.properties");
    var store = new MemoryCredentialStore(true);
    var settings = new TeacherSettings(file, store);
    assertEquals("", settings.load().baseUrl);
    assertEquals("", settings.snapshot().model);
    settings.saveTeachingPreferences("k", 5, 0, 1, 1, 1);
    assertEquals("", new TeacherSettings(file, store).load().baseUrl);
    assertEquals("", new TeacherSettings(file, store).load().model);
    assertThrows(
        IllegalArgumentException.class,
        () -> settings.save("https://provider.example/v1", "", new char[0], false));
    assertThrows(
        IllegalArgumentException.class, () -> settings.save("  ", "model", new char[0], false));
    assertFalse(Files.readString(file).contains("openai.com"));
    assertFalse(Files.readString(file).contains("gpt-"));
    settings.forgetApiKey();
    assertEquals("", settings.snapshot().baseUrl);
  }

  @Test
  void explicitlySavedOpenAiAddressIsNotCleared() throws Exception {
    Path file = temporaryDirectory.resolve("existing.properties");
    var store = new MemoryCredentialStore(true);
    var settings = new TeacherSettings(file, store);
    settings.load();
    settings.save("https://api.openai.com/v1", "model", new char[0], false);
    assertEquals("https://api.openai.com/v1", new TeacherSettings(file, store).load().baseUrl);
  }

  @Test
  void legacySettingsWithoutAnAddressRetainTheirOriginalEndpoint() throws Exception {
    Path file = temporaryDirectory.resolve("legacy.properties");
    Files.writeString(file, "model=legacy-model\n");
    assertEquals(
        "https://api.openai.com/v1",
        new TeacherSettings(file, new MemoryCredentialStore(true)).load().baseUrl);
  }

  @Test
  void explicitlySavedModelRemainsUnchanged() throws Exception {
    Path file = temporaryDirectory.resolve("existing-model.properties");
    Files.writeString(file, "model=gpt-4o-mini\n");
    assertEquals(
        "gpt-4o-mini", new TeacherSettings(file, new MemoryCredentialStore(true)).load().model);
  }

  @Test
  void validatesProviderUrlAndModel() {
    assertThrows(IllegalArgumentException.class, () -> TeacherSettings.validateBaseUrl(""));
    assertThrows(IllegalArgumentException.class, () -> TeacherSettings.validateBaseUrl(null));
    assertThrows(IllegalArgumentException.class, () -> TeacherLlmClient.normalizeApiBase(""));
    assertThrows(
        IllegalArgumentException.class,
        () -> TeacherSettings.validateBaseUrl("file:///tmp/provider"));
    assertThrows(
        IllegalArgumentException.class,
        () -> TeacherSettings.validateBaseUrl("https://user:pass@example.test/v1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> TeacherSettings.validateBaseUrl("http://provider.example/v1"));
    assertEquals(
        "http://localhost:8080/v1", TeacherSettings.validateBaseUrl("http://localhost:8080/v1/"));
    assertThrows(IllegalArgumentException.class, () -> TeacherSettings.validateModel("  "));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "localhost",
        "127.0.0.1",
        "10.0.0.1",
        "10.255.255.254",
        "172.16.0.1",
        "172.31.255.254",
        "192.168.1.3",
        "[::1]",
        "[0:0:0:0:0:0:0:1]",
        "[fc00::1]",
        "[fd12:3456:789a::1234]"
      })
  void acceptsLocalAndLiteralPrivateNetworkHttpServices(String host) throws Exception {
    String base = "http://" + host + ":1234/v1";
    assertEquals(base, TeacherSettings.validateBaseUrl(base + "/"));
    assertEquals(base, TeacherLlmClient.normalizeApiBase(base).toString());
    TeacherSettings settings =
        new TeacherSettings(
            temporaryDirectory.resolve("lan.properties"), new MemoryCredentialStore(true));
    settings.save(base, "local-model", "local-key".toCharArray(), false);
    assertEquals(base, settings.load().baseUrl);
    assertFalse(
        Files.readString(temporaryDirectory.resolve("lan.properties")).contains("local-key"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "8.8.8.8",
        "172.15.255.255",
        "172.32.0.1",
        "192.167.255.255",
        "192.169.0.1",
        "169.254.169.254",
        "100.64.0.1",
        "0.0.0.0",
        "224.0.0.1",
        "10.0.0.256",
        "010.0.0.1",
        "10.1",
        "167772161",
        "0x0a000001",
        "nas.local",
        "192.168.1.3.example.com",
        "localhost.example.com",
        "[::]",
        "[fe80::1]",
        "[ff02::1]",
        "[2001:db8::1]",
        "[::ffff:192.168.1.3]",
        "[::ffff:c0a8:103]",
        "[fd00::1%25eth0]"
      })
  void refusesHttpPublicSpecialAmbiguousOrDnsAddresses(String host) {
    String base = "http://" + host + ":1234/v1";
    assertThrows(IllegalArgumentException.class, () -> TeacherSettings.validateBaseUrl(base));
    assertThrows(IllegalArgumentException.class, () -> TeacherLlmClient.normalizeApiBase(base));
  }

  @Test
  void clampsDanRanksToNineForStoredAndNewPreferences() throws Exception {
    Path settingsFile = temporaryDirectory.resolve("teacher.properties");
    Properties stored = new Properties();
    stored.setProperty("teacher.rankMode", "d");
    stored.setProperty("teacher.rankNum", "18");
    try (java.io.OutputStream output = Files.newOutputStream(settingsFile)) {
      stored.store(output, "invalid legacy rank");
    }

    TeacherSettings settings = new TeacherSettings(settingsFile, new MemoryCredentialStore(true));
    assertEquals(9, settings.load().rankNum);

    settings.saveTeachingPreferences("d", 15, 0, 1, 1, 1);
    assertEquals(9, settings.snapshot().rankNum);
  }

  private static final class MemoryCredentialStore implements CredentialStore {
    private final boolean available;
    private final Map<String, String> values = new HashMap<>();

    private MemoryCredentialStore(boolean available) {
      this.available = available;
    }

    @Override
    public String backendName() {
      return available ? "test-secure" : "session-only";
    }

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public Optional<String> read(Kind kind, String account) {
      return Optional.ofNullable(values.get(kind + ":" + account));
    }

    @Override
    public void write(Kind kind, String account, String secret) throws IOException {
      if (!available) {
        throw new IOException("unavailable");
      }
      values.put(kind + ":" + account, secret);
    }

    @Override
    public void delete(Kind kind, String account) {
      values.remove(kind + ":" + account);
    }
  }
}
