package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.logging.LoggingSettings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigBootstrapTest {
  private static final String USER_UI_MARKER = "keep-ui";
  private static final String USER_LEELAZ_MARKER = "keep-leelaz";
  private static final String USER_ROOT_MARKER = "keep-root";
  private static final String USER_PERSIST_MARKER = "keep-persist";
  private static final String USER_SAVE_MARKER = "keep-save";
  private static final String CHINESE_NOTE_KEY = "用户备注";
  private static final String CHINESE_NOTE_VALUE = "围棋分析";

  @TempDir Path tempDir;

  @Test
  void firstStartCreatesRequiredFilesAndAppliesLaunchDefaults() throws Exception {
    Path workDir = isolatedWorkDir("first-start");

    Config config = ConfigTestHelper.createBootstrapped(workDir);

    assertTrue(config.isNewProfile());
    assertTrue(Files.isRegularFile(configFile(workDir)));
    assertTrue(Files.isRegularFile(persistFile(workDir)));
    assertTrue(Files.isRegularFile(saveFile(workDir)));
    assertWorkDirIsIsolated(workDir, config);

    JSONObject writtenConfig = Config.readJsonObjectForTests(configFile(workDir));
    JSONObject writtenPersist = Config.readJsonObjectForTests(persistFile(workDir));
    JSONObject writtenSave = Config.readJsonObjectForTests(saveFile(workDir));
    assertTrue(writtenConfig.has("ui"));
    assertTrue(writtenConfig.has("leelaz"));
    assertTrue(writtenPersist.has("ui-persist"));
    assertTrue(writtenSave.has("save"));

    assertSame(config.config.getJSONObject("ui"), config.uiConfig);
    assertSame(config.config.getJSONObject("leelaz"), config.leelazConfig);
    assertSame(config.persisted.getJSONObject("ui-persist"), config.persistedUi);
    assertSame(config.saveBoard.getJSONObject("save"), config.saveBoardConfig);

    assertEquals(
        AppLocale.fromSystemLocale(Locale.getDefault()).configValue(),
        config.uiConfig.getInt("use-language"));
    assertFalse(config.uiConfig.getBoolean("enable-startup-benchmark"));
    assertTrue(config.config.has(LoggingSettings.CONFIG_KEY));
    assertTrue(config.loggingSettings.diagnosticsEnabled());
  }

  @Test
  void mergeKeepsUserValuesFillsDefaultsAndPreservesUnknownKeys() throws Exception {
    Path workDir = isolatedWorkDir("merge");
    writeJson(configFile(workDir), userConfig());
    writeJson(persistFile(workDir), userPersist());
    writeJson(saveFile(workDir), userSave());

    Config config = ConfigTestHelper.createBootstrapped(workDir);

    assertFalse(config.isNewProfile());
    assertWorkDirIsIsolated(workDir, config);

    assertTrue(config.uiConfig.getBoolean("show-move-number"));
    assertEquals(AppLocale.THAI.configValue(), config.uiConfig.getInt("use-language"));
    assertTrue(config.uiConfig.getBoolean("enable-startup-benchmark"));
    assertEquals("10.0.0.1", config.uiConfig.getString("network-proxy-host"));
    assertEquals(USER_UI_MARKER, config.uiConfig.getString("future-ui-key"));
    assertEquals(
        "nested-value", config.uiConfig.getJSONObject("nested-user-object").getString("kept"));
    assertTrue(config.uiConfig.getJSONObject("nested-user-object").getBoolean("only-user"));
    assertEquals(42, config.leelazConfig.getInt("limit-max-suggestion"));
    assertEquals(USER_LEELAZ_MARKER, config.leelazConfig.getString("future-leelaz-key"));
    assertEquals(USER_ROOT_MARKER, config.config.getString("future-root-key"));
    assertEquals(USER_PERSIST_MARKER, config.persistedUi.getString("future-persist-key"));
    assertEquals("keep-persist-root", config.persisted.getString("future-persist-root"));
    assertFalse(config.saveBoardConfig.getBoolean("save-config"));
    assertEquals(USER_SAVE_MARKER, config.saveBoardConfig.getString("future-save-key"));

    assertTrue(config.uiConfig.has("show-status"));
    assertTrue(config.uiConfig.has("show-subboard"));
    assertTrue(config.leelazConfig.has("limit-branch-length"));
    assertTrue(config.persistedUi.has("main-window-position"));
    assertTrue(config.config.has(LoggingSettings.CONFIG_KEY));

    assertSame(config.config.getJSONObject("ui"), config.uiConfig);
    assertSame(config.config.getJSONObject("leelaz"), config.leelazConfig);
    assertSame(config.persisted.getJSONObject("ui-persist"), config.persistedUi);
    assertSame(config.saveBoard.getJSONObject("save"), config.saveBoardConfig);

    JSONObject writtenConfig = Config.readJsonObjectForTests(configFile(workDir));
    JSONObject writtenPersist = Config.readJsonObjectForTests(persistFile(workDir));
    JSONObject writtenSave = Config.readJsonObjectForTests(saveFile(workDir));
    assertEquals(USER_UI_MARKER, writtenConfig.getJSONObject("ui").getString("future-ui-key"));
    assertEquals(
        USER_LEELAZ_MARKER, writtenConfig.getJSONObject("leelaz").getString("future-leelaz-key"));
    assertEquals(USER_ROOT_MARKER, writtenConfig.getString("future-root-key"));
    assertTrue(writtenConfig.getJSONObject("ui").has("show-status"));
    assertTrue(writtenConfig.has(LoggingSettings.CONFIG_KEY));
    assertEquals(
        USER_PERSIST_MARKER,
        writtenPersist.getJSONObject("ui-persist").getString("future-persist-key"));
    assertEquals(USER_SAVE_MARKER, writtenSave.getJSONObject("save").getString("future-save-key"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "{\"ui\":{"})
  void corruptedMainConfigIsBackedUpAndRebuiltWithoutResettingOthers(String payload)
      throws Exception {
    Path workDir = isolatedWorkDir("corrupt-config-" + (payload.isEmpty() ? "empty" : "truncated"));
    writeCorrupt(configFile(workDir), payload);
    writeJson(persistFile(workDir), userPersist());
    writeJson(saveFile(workDir), userSave());

    Config config = ConfigTestHelper.createBootstrapped(workDir);

    List<Path> backups = unreadableBackups(workDir);
    assertFalse(backups.isEmpty());
    assertEquals(payload, Files.readString(backups.get(0), StandardCharsets.UTF_8));
    JSONObject rebuilt = Config.readJsonObjectForTests(configFile(workDir));
    assertTrue(rebuilt.has("ui"));
    assertTrue(rebuilt.has("leelaz"));
    assertFalse(rebuilt.getJSONObject("ui").has("future-ui-key"));
    assertTrue(config.uiConfig.has("show-status"));
    JSONObject writtenPersist = Config.readJsonObjectForTests(persistFile(workDir));
    JSONObject writtenSave = Config.readJsonObjectForTests(saveFile(workDir));
    assertEquals(
        USER_PERSIST_MARKER,
        writtenPersist.getJSONObject("ui-persist").getString("future-persist-key"));
    assertEquals(USER_SAVE_MARKER, writtenSave.getJSONObject("save").getString("future-save-key"));
    assertEquals(USER_PERSIST_MARKER, config.persistedUi.getString("future-persist-key"));
    assertEquals(USER_SAVE_MARKER, config.saveBoardConfig.getString("future-save-key"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "{\"ui-persist\":{"})
  void corruptedPersistIsRecoveredWithoutResettingOtherFiles(String payload) throws Exception {
    Path workDir = isolatedWorkDir("corrupt-persist-" + (payload.isEmpty() ? "empty" : "truncated"));
    writeJson(configFile(workDir), userConfig());
    writeCorrupt(persistFile(workDir), payload);
    writeJson(saveFile(workDir), userSave());

    Config config = ConfigTestHelper.createBootstrapped(workDir);

    assertTrue(config.persisted.has("ui-persist"));
    assertSame(config.persisted.getJSONObject("ui-persist"), config.persistedUi);
    assertEquals(payload, Files.readString(persistFile(workDir), StandardCharsets.UTF_8));
    JSONObject writtenConfig = Config.readJsonObjectForTests(configFile(workDir));
    JSONObject writtenSave = Config.readJsonObjectForTests(saveFile(workDir));
    assertEquals(USER_UI_MARKER, writtenConfig.getJSONObject("ui").getString("future-ui-key"));
    assertEquals(USER_SAVE_MARKER, writtenSave.getJSONObject("save").getString("future-save-key"));
    assertTrue(config.uiConfig.getBoolean("show-move-number"));
    assertEquals(USER_SAVE_MARKER, config.saveBoardConfig.getString("future-save-key"));
    assertTrue(unreadableBackups(workDir).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "{\"save\":{"})
  void corruptedSaveBoardIsRebuiltWithoutResettingOtherFiles(String payload) throws Exception {
    Path workDir = isolatedWorkDir("corrupt-save-" + (payload.isEmpty() ? "empty" : "truncated"));
    writeJson(configFile(workDir), userConfig());
    writeJson(persistFile(workDir), userPersist());
    writeCorrupt(saveFile(workDir), payload);

    Config config = ConfigTestHelper.createBootstrapped(workDir);

    JSONObject rebuiltSave = Config.readJsonObjectForTests(saveFile(workDir));
    assertTrue(rebuiltSave.has("save"));
    assertTrue(config.saveBoardConfig.getBoolean("save-config"));
    assertFalse(rebuiltSave.getJSONObject("save").has("future-save-key"));
    JSONObject writtenConfig = Config.readJsonObjectForTests(configFile(workDir));
    JSONObject writtenPersist = Config.readJsonObjectForTests(persistFile(workDir));
    assertEquals(USER_UI_MARKER, writtenConfig.getJSONObject("ui").getString("future-ui-key"));
    assertEquals(
        USER_PERSIST_MARKER,
        writtenPersist.getJSONObject("ui-persist").getString("future-persist-key"));
    assertTrue(config.uiConfig.getBoolean("show-move-number"));
    assertEquals(USER_PERSIST_MARKER, config.persistedUi.getString("future-persist-key"));
    assertTrue(unreadableBackups(workDir).isEmpty());
  }

  @Test
  void utf8BomAndChineseWorkDirRemainReadable() throws Exception {
    Path workDir = isolatedWorkDir("配置-围棋");
    JSONObject bomConfig =
        new JSONObject()
            .put(
                "ui",
                new JSONObject()
                    .put("show-move-number", true)
                    .put(CHINESE_NOTE_KEY, CHINESE_NOTE_VALUE)
                    .put("future-ui-key", USER_UI_MARKER))
            .put("leelaz", new JSONObject().put("limit-max-suggestion", 42));
    writeBomJson(configFile(workDir), bomConfig);
    writeJson(persistFile(workDir), userPersist());
    writeJson(saveFile(workDir), userSave());

    Config config = ConfigTestHelper.createBootstrapped(workDir);

    assertWorkDirIsIsolated(workDir, config);
    assertTrue(config.uiConfig.getBoolean("show-move-number"));
    assertEquals(CHINESE_NOTE_VALUE, config.uiConfig.getString(CHINESE_NOTE_KEY));
    assertEquals(USER_UI_MARKER, config.uiConfig.getString("future-ui-key"));
    assertEquals(42, config.leelazConfig.getInt("limit-max-suggestion"));

    JSONObject written = Config.readJsonObjectForTests(configFile(workDir));
    assertEquals(CHINESE_NOTE_VALUE, written.getJSONObject("ui").getString(CHINESE_NOTE_KEY));
    assertEquals(USER_UI_MARKER, written.getJSONObject("ui").getString("future-ui-key"));
    Config.readJsonObjectForTests(persistFile(workDir));
    Config.readJsonObjectForTests(saveFile(workDir));
  }

  private Path isolatedWorkDir(String name) throws IOException {
    Path workDir = Files.createDirectories(tempDir.resolve(name));
    // Production WorkDirectoryResolver creates workDir/save for explicit work dirs.
    // loadAndMergeSaveBoardConfig mkdirs CWD "save", not the override work dir.
    Files.createDirectories(workDir.resolve("save"));
    return workDir;
  }

  private void assertWorkDirIsIsolated(Path workDir, Config config) {
    Path absoluteWorkDir = workDir.toAbsolutePath().normalize();
    assertTrue(
        Path.of(config.getConfigFilePath())
            .toAbsolutePath()
            .normalize()
            .startsWith(absoluteWorkDir));
    assertTrue(
        Path.of(config.getPersistFilePath())
            .toAbsolutePath()
            .normalize()
            .startsWith(absoluteWorkDir));
    assertTrue(saveFile(workDir).toAbsolutePath().normalize().startsWith(absoluteWorkDir));
    assertTrue(absoluteWorkDir.startsWith(tempDir.toAbsolutePath().normalize()));
  }

  private static Path configFile(Path workDir) {
    return workDir.resolve("config.txt");
  }

  private static Path persistFile(Path workDir) {
    return workDir.resolve("persist");
  }

  private static Path saveFile(Path workDir) {
    return workDir.resolve("save").resolve("save");
  }

  private static JSONObject userConfig() {
    JSONObject ui =
        new JSONObject()
            .put("show-move-number", true)
            .put("use-language", AppLocale.THAI.configValue())
            .put("enable-startup-benchmark", true)
            .put("network-proxy-host", "10.0.0.1")
            .put("future-ui-key", USER_UI_MARKER)
            .put(
                "nested-user-object",
                new JSONObject().put("kept", "nested-value").put("only-user", true));
    JSONObject leelaz =
        new JSONObject()
            .put("limit-max-suggestion", 42)
            .put("future-leelaz-key", USER_LEELAZ_MARKER);
    return new JSONObject()
        .put("ui", ui)
        .put("leelaz", leelaz)
        .put("future-root-key", USER_ROOT_MARKER);
  }

  private static JSONObject userPersist() {
    return new JSONObject()
        .put(
            "ui-persist",
            new JSONObject()
                .put("window-maximized", true)
                .put("future-persist-key", USER_PERSIST_MARKER))
        .put("future-persist-root", "keep-persist-root");
  }

  private static JSONObject userSave() {
    return new JSONObject()
        .put(
            "save",
            new JSONObject().put("save-config", false).put("future-save-key", USER_SAVE_MARKER));
  }

  private static void writeJson(Path file, JSONObject json) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, json.toString(2), StandardCharsets.UTF_8);
  }

  private static void writeCorrupt(Path file, String payload) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, payload, StandardCharsets.UTF_8);
  }

  private static void writeBomJson(Path file, JSONObject json) throws IOException {
    Files.createDirectories(file.getParent());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
    out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
    Files.write(file, out.toByteArray());
  }

  private static List<Path> unreadableBackups(Path workDir) throws IOException {
    try (Stream<Path> stream = Files.list(workDir)) {
      return stream
          .filter(path -> path.getFileName().toString().startsWith("config.txt.unreadable-backup"))
          .sorted()
          .toList();
    }
  }
}
