package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

public class ConfigBundledKataGoDefaultsTest {
  private static final String HIDE_BLUNDER_BAR_DEFAULT_MIGRATION_KEY =
      "migrated-hide-blunder-bar-default-v1";

  @Test
  void windowsPortableMarkerKeepsMutableDataInsideExtractedFolder() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-portable-root");
    Path portableRoot = Files.createDirectories(tempRoot.resolve("LizzieYzy Next 围棋"));
    Files.writeString(portableRoot.resolve(".lizzie-portable"), "portable");
    Files.createDirectories(portableRoot.resolve("app"));
    Files.writeString(
        portableRoot.resolve("config.txt"), "{\"ui\":{},\"leelaz\":{\"legacy\":true}}");

    Path foundRoot =
        Config.findWindowsPortablePackageRootForTests(portableRoot.resolve("app")).orElseThrow();
    Path workDir = Config.prepareWindowsPortableWorkDirForTests(foundRoot);

    assertEquals(portableRoot.toAbsolutePath().normalize(), foundRoot);
    assertEquals(portableRoot.resolve("user-data").toAbsolutePath().normalize(), workDir);
    assertTrue(Files.exists(workDir.resolve("save")));
    assertTrue(Files.exists(workDir.resolve("config.txt")));
    assertFalse(workDir.equals(portableRoot));
  }

  @Test
  void windowsPortableUpgradeMigratesConfigAndSavesButLeavesCredentialsForSecureMigration()
      throws Exception {
    Path parent = Files.createTempDirectory("lizzie-portable-upgrade");
    Path previousRoot = Files.createDirectories(parent.resolve("2026-08-01-windows64.nvidia"));
    Path previousData = Files.createDirectories(previousRoot.resolve("user-data"));
    Files.writeString(previousRoot.resolve(".lizzie-portable"), "portable");
    Files.writeString(previousData.resolve("config.txt"), richPortableConfig().toString(2));
    Files.writeString(previousData.resolve("persist"), "{\"ui-persist\":{}}");
    Path previousCredentials = Files.createDirectories(previousData.resolve("secure-credentials"));
    Files.writeString(previousCredentials.resolve("account-token-user.dpapi"), "encrypted-token");
    Files.writeString(previousCredentials.resolve("password-user.dpapi"), "encrypted-password");

    Path decoyRoot = Files.createDirectories(parent.resolve("2026-08-07-windows64.nvidia"));
    Path decoyData = Files.createDirectories(decoyRoot.resolve("user-data"));
    Files.writeString(decoyRoot.resolve(".lizzie-portable"), "portable");
    Files.writeString(decoyData.resolve("config.txt"), freshPortableConfig().toString(2));

    Path currentRoot = Files.createDirectories(parent.resolve("2026-08-08-windows64.nvidia"));
    Files.writeString(currentRoot.resolve(".lizzie-portable"), "portable");
    Path currentSave = Files.createDirectories(currentRoot.resolve("user-data").resolve("save"));
    Files.writeString(currentSave.resolve("unfinished-game.sgf"), "(;GM[1])");

    Path workDir =
        Config.prepareWindowsPortableWorkDirWithSourcesForTests(
            currentRoot, decoyData, previousData);

    JSONObject migrated = new JSONObject(Files.readString(workDir.resolve("config.txt")));
    JSONArray engines = migrated.getJSONObject("leelaz").getJSONArray("engine-settings-list");
    assertEquals(2, engines.length());
    assertEquals(
        RemoteComputeConfig.PROVIDER_ZHIZI,
        migrated
            .getJSONObject("leelaz")
            .getJSONObject(RemoteComputeConfig.CONFIG_KEY)
            .getString("provider"));
    assertFalse(Files.exists(workDir.resolve("secure-credentials")));
    assertTrue(
        Config.windowsPortableCredentialDirectoriesForTests(currentRoot)
            .contains(previousCredentials.toAbsolutePath().normalize()));
    assertEquals("(;GM[1])", Files.readString(currentSave.resolve("unfinished-game.sgf")));
  }

  @Test
  void windowsPortableUpgradeDiscoversSiblingUserData() throws Exception {
    Path parent = Files.createTempDirectory("lizzie-portable-sibling-discovery");
    Path previousRoot = Files.createDirectories(parent.resolve("previous"));
    Path previousData = Files.createDirectories(previousRoot.resolve("user-data"));
    Files.writeString(previousRoot.resolve(".lizzie-portable"), "portable");
    Files.writeString(previousData.resolve("config.txt"), richPortableConfig().toString(2));
    Path currentRoot = Files.createDirectories(parent.resolve("current"));
    Files.writeString(currentRoot.resolve(".lizzie-portable"), "portable");

    assertTrue(
        Config.windowsPortableMigrationSourcesForTests(currentRoot)
            .contains(previousData.toAbsolutePath().normalize()));
  }

  @Test
  void windowsPortableUpgradeKeepsConfigFromNewestMeaningfulProfile() throws Exception {
    Path parent = Files.createTempDirectory("lizzie-portable-newest-profile");
    Path olderRoot = Files.createDirectories(parent.resolve("older"));
    Files.writeString(olderRoot.resolve(".lizzie-portable"), "portable");
    Path olderData = Files.createDirectories(olderRoot.resolve("user-data"));
    JSONObject olderConfig = richPortableConfig();
    olderConfig
        .getJSONObject("leelaz")
        .getJSONObject(RemoteComputeConfig.CONFIG_KEY)
        .put("zhizi-identifier", "older-user");
    olderConfig
        .getJSONObject("leelaz")
        .getJSONArray("engine-settings-list")
        .put(
            new JSONObject()
                .put("name", "Another Old Engine")
                .put("command", "D:/old/katago.exe gtp"));
    Path olderConfigFile = olderData.resolve("config.txt");
    Files.writeString(olderConfigFile, olderConfig.toString(2));
    Path olderCredentials = Files.createDirectories(olderData.resolve("secure-credentials"));
    Files.writeString(olderCredentials.resolve("account-token-user.dpapi"), "older-token");
    Files.setLastModifiedTime(olderConfigFile, FileTime.fromMillis(1_000));

    Path newerRoot = Files.createDirectories(parent.resolve("newer"));
    Files.writeString(newerRoot.resolve(".lizzie-portable"), "portable");
    Path newerData = Files.createDirectories(newerRoot.resolve("user-data"));
    JSONObject newerConfig = richPortableConfig();
    newerConfig
        .getJSONObject("leelaz")
        .getJSONObject(RemoteComputeConfig.CONFIG_KEY)
        .put("zhizi-identifier", "newer-user");
    Path newerConfigFile = newerData.resolve("config.txt");
    Files.writeString(newerConfigFile, newerConfig.toString(2));
    Path newerCredentials = Files.createDirectories(newerData.resolve("secure-credentials"));
    Files.writeString(newerCredentials.resolve("account-token-user.dpapi"), "newer-token");
    Files.setLastModifiedTime(newerConfigFile, FileTime.fromMillis(2_000));

    Path currentRoot = Files.createDirectories(parent.resolve("current"));
    Files.writeString(currentRoot.resolve(".lizzie-portable"), "portable");
    Path workDir =
        Config.prepareWindowsPortableWorkDirWithSourcesForTests(
            currentRoot, olderData, newerData);

    JSONObject migrated = new JSONObject(Files.readString(workDir.resolve("config.txt")));
    assertEquals(
        "newer-user",
        migrated
            .getJSONObject("leelaz")
            .getJSONObject(RemoteComputeConfig.CONFIG_KEY)
            .getString("zhizi-identifier"));
    assertFalse(Files.exists(workDir.resolve("secure-credentials")));
    List<Path> legacyCredentials =
        Config.windowsPortableCredentialDirectoriesForTests(currentRoot);
    assertTrue(legacyCredentials.contains(olderCredentials.toAbsolutePath().normalize()));
    assertTrue(legacyCredentials.contains(newerCredentials.toAbsolutePath().normalize()));
  }

  @Test
  void windowsPortableUpgradeBacksUpUnreadableTargetBeforeRecovery() throws Exception {
    Path parent = Files.createTempDirectory("lizzie-portable-broken-target");
    Path previousData = Files.createDirectories(parent.resolve("previous").resolve("user-data"));
    Files.writeString(previousData.resolve("config.txt"), richPortableConfig().toString(2));
    Path currentRoot = Files.createDirectories(parent.resolve("current"));
    Files.writeString(currentRoot.resolve(".lizzie-portable"), "portable");
    Path currentData = Files.createDirectories(currentRoot.resolve("user-data"));
    Files.writeString(currentData.resolve("config.txt"), "{broken-json");

    Config.prepareWindowsPortableWorkDirWithSourcesForTests(currentRoot, previousData);

    assertEquals(
        "{broken-json",
        Files.readString(currentData.resolve("config.txt.unreadable-backup")));
    JSONObject recovered = new JSONObject(Files.readString(currentData.resolve("config.txt")));
    assertEquals(
        2, recovered.getJSONObject("leelaz").getJSONArray("engine-settings-list").length());
  }

  @Test
  void windowsPortableUpgradeRecoversUserStateFromGeneratedDefaultOnce() throws Exception {
    Path parent = Files.createTempDirectory("lizzie-portable-recovery");
    Path previousData = Files.createDirectories(parent.resolve("previous").resolve("user-data"));
    Files.writeString(previousData.resolve("config.txt"), richPortableConfig().toString(2));
    Path currentRoot = Files.createDirectories(parent.resolve("current"));
    Files.writeString(currentRoot.resolve(".lizzie-portable"), "portable");
    Path currentData = Files.createDirectories(currentRoot.resolve("user-data"));
    Files.writeString(currentData.resolve("config.txt"), freshPortableConfig().toString(2));

    Config.prepareWindowsPortableWorkDirWithSourcesForTests(currentRoot, previousData);

    JSONObject recovered = new JSONObject(Files.readString(currentData.resolve("config.txt")));
    assertEquals(
        2, recovered.getJSONObject("leelaz").getJSONArray("engine-settings-list").length());
    assertEquals(1, recovered.getJSONObject("ui").getInt("default-engine"));
    assertTrue(recovered.getJSONObject("ui").getBoolean("migrated-windows-portable-user-state-v2"));
    assertTrue(Files.isRegularFile(currentData.resolve("config.before-portable-recovery.txt")));

    JSONObject userEdited = new JSONObject(Files.readString(currentData.resolve("config.txt")));
    userEdited.getJSONObject("ui").put("default-engine", 0);
    Files.writeString(currentData.resolve("config.txt"), userEdited.toString(2));
    Config.prepareWindowsPortableWorkDirWithSourcesForTests(currentRoot, previousData);
    JSONObject secondLaunch = new JSONObject(Files.readString(currentData.resolve("config.txt")));
    assertEquals(0, secondLaunch.getJSONObject("ui").getInt("default-engine"));
  }

  @Test
  void windowsPortableUpgradeNeverOverwritesAnExistingCustomConfig() throws Exception {
    Path parent = Files.createTempDirectory("lizzie-portable-custom-config");
    Path previousData = Files.createDirectories(parent.resolve("previous").resolve("user-data"));
    Files.writeString(previousData.resolve("config.txt"), richPortableConfig().toString(2));
    Path currentRoot = Files.createDirectories(parent.resolve("current"));
    Files.writeString(currentRoot.resolve(".lizzie-portable"), "portable");
    Path currentData = Files.createDirectories(currentRoot.resolve("user-data"));
    JSONObject custom = freshPortableConfig();
    custom
        .getJSONObject("leelaz")
        .put(
            "engine-settings-list",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("name", "My External KataGo")
                        .put("command", "D:/KataGo/katago.exe gtp -model D:/models/my.bin.gz")));
    Files.writeString(currentData.resolve("config.txt"), custom.toString(2));

    Config.prepareWindowsPortableWorkDirWithSourcesForTests(currentRoot, previousData);

    JSONObject preserved = new JSONObject(Files.readString(currentData.resolve("config.txt")));
    assertEquals(
        "My External KataGo",
        preserved
            .getJSONObject("leelaz")
            .getJSONArray("engine-settings-list")
            .getJSONObject(0)
            .getString("name"));
    assertFalse(Files.exists(currentData.resolve("config.before-portable-recovery.txt")));
  }

  @Test
  void defaultConfigHidesBlunderBarWhileKeepingAutoQuickAnalyzeOnLoad() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-config-default-ui");
    Config config = ConfigTestHelper.createForTests(tempRoot);
    JSONObject defaultConfig = createDefaultConfig(config);
    JSONObject ui = defaultConfig.getJSONObject("ui");

    assertFalse(config.showBlunderBar);
    assertFalse(ui.getBoolean("show-blunder-bar"));
    assertTrue(ui.getBoolean("auto-quick-analyze-on-load"));
    assertFalse(ui.getBoolean("quick-analysis-lightweight-model-enabled"));
  }

  @Test
  void configSchemaRepairKeepsExistingEngineSettings() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-config-schema-repair");
    Config config = ConfigTestHelper.createForTests(tempRoot);
    JSONObject customEngine =
        new JSONObject()
            .put("name", "External KataGo")
            .put("command", "D:/KataGo/katago.exe gtp -model D:/models/custom.bin.gz");
    JSONObject existingLeelaz =
        new JSONObject()
            .put("engine-settings-list", new JSONArray().put(customEngine))
            .put("analysis-engine-ssh-info", "legacy-invalid-value");
    JSONObject existing =
        new JSONObject().put("ui", new JSONObject()).put("leelaz", existingLeelaz);
    JSONObject defaults =
        new JSONObject()
            .put("ui", new JSONObject().put("show-status", true))
            .put(
                "leelaz",
                new JSONObject()
                    .put("analysis-engine-ssh-info", new JSONObject().put("useJavaSSH", false)));

    assertTrue(config.mergeDefaults(existing, defaults));

    assertEquals(
        "External KataGo",
        existingLeelaz.getJSONArray("engine-settings-list").getJSONObject(0).getString("name"));
    assertFalse(existingLeelaz.getJSONObject("analysis-engine-ssh-info").getBoolean("useJavaSSH"));
    assertTrue(existing.getJSONObject("ui").getBoolean("show-status"));
  }

  @Test
  void unreadableConfigIsBackedUpBesideOriginal() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-config-unreadable-backup");
    Path configFile = tempRoot.resolve("config.txt");
    Files.writeString(configFile, "{broken-json");
    Method method = Config.class.getDeclaredMethod("backupUnreadableConfig", java.io.File.class);
    method.setAccessible(true);

    method.invoke(null, configFile.toFile());

    Path backup = tempRoot.resolve("config.txt.unreadable-backup");
    assertTrue(Files.isRegularFile(backup));
    assertEquals("{broken-json", Files.readString(backup));
  }

  @Test
  void oldBlunderBarDefaultMigratesOffOnlyOnce() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-blunder-bar-migration");
    Config config = ConfigTestHelper.createForTests(tempRoot);
    JSONObject ui = new JSONObject();
    ui.put("show-blunder-bar", true);
    JSONObject root = new JSONObject();
    root.put("ui", ui);
    config.config = root;
    config.uiConfig = ui;

    hideBlunderBarDefaultOnce(config);

    assertFalse(ui.getBoolean("show-blunder-bar"));
    assertTrue(ui.getBoolean(HIDE_BLUNDER_BAR_DEFAULT_MIGRATION_KEY));
    assertTrue(
        Files.readString(tempRoot.resolve("config.txt")).contains("\"show-blunder-bar\": false"));

    ui.put("show-blunder-bar", true);
    hideBlunderBarDefaultOnce(config);

    assertTrue(
        ui.getBoolean("show-blunder-bar"),
        "after the one-time default migration, explicit user changes should be preserved.");
  }

  @Test
  void existingBundledEngineKeepsUserStartupModeAndKomiOnRestart() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-bundled-katago-existing");
    Files.writeString(tempRoot.resolve("config.txt"), "{}");
    createBundledKataGoAssets(tempRoot);

    Config config = ConfigTestHelper.createForTests(tempRoot);
    JSONObject ui = new JSONObject();
    ui.put("first-time-load", false);
    ui.put("autoload-default", false);
    ui.put("autoload-last", false);
    ui.put("autoload-empty", true);
    ui.put("default-engine", 0);

    JSONObject bundledEngine = new JSONObject();
    bundledEngine.put("name", "KataGo Auto Setup");
    bundledEngine.put("command", "\"old/engines/katago/macos-arm64/katago\" gtp");
    bundledEngine.put("isDefault", true);
    bundledEngine.put("preload", true);
    bundledEngine.put("komi", 6.5);
    bundledEngine.put("width", 13);
    bundledEngine.put("height", 13);

    JSONObject leelaz = new JSONObject();
    leelaz.put("engine-settings-list", new JSONArray().put(bundledEngine));
    config.config = new JSONObject().put("ui", ui).put("leelaz", leelaz);

    withUserDir(tempRoot, () -> applyBundledKataGoDefaults(config));

    JSONObject storedEngine = leelaz.getJSONArray("engine-settings-list").getJSONObject(0);
    assertFalse(ui.getBoolean("autoload-default"));
    assertFalse(ui.getBoolean("autoload-last"));
    assertTrue(ui.getBoolean("autoload-empty"));
    assertEquals(6.5, storedEngine.getDouble("komi"));
    assertEquals(13, storedEngine.getInt("width"));
    assertEquals(13, storedEngine.getInt("height"));
    assertTrue(storedEngine.getBoolean("preload"));
  }

  @Test
  void customCommandInDefaultSlotSurvivesRestart() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-bundled-katago-custom");
    Files.writeString(tempRoot.resolve("config.txt"), "{}");
    createBundledKataGoAssets(tempRoot);

    Config config = ConfigTestHelper.createForTests(tempRoot);
    JSONObject ui = new JSONObject();
    ui.put("first-time-load", false);
    ui.put("default-engine", 0);

    // The user kept the default name in engine 1 but pointed the command at their own engine.
    String customCommand =
        "\"/opt/my-katago/katago\" gtp -model \"/opt/my-katago/net.bin.gz\""
            + " -config \"/opt/package/engines/katago/configs/gtp.cfg\"";
    JSONObject customEngine = new JSONObject();
    customEngine.put("name", "KataGo Bundled");
    customEngine.put("command", customCommand);
    customEngine.put("isDefault", true);

    JSONObject leelaz = new JSONObject();
    leelaz.put("engine-settings-list", new JSONArray().put(customEngine));
    config.config = new JSONObject().put("ui", ui).put("leelaz", leelaz);

    withUserDir(tempRoot, () -> applyBundledKataGoDefaults(config));

    JSONObject storedEngine = leelaz.getJSONArray("engine-settings-list").getJSONObject(0);
    assertEquals(
        customCommand,
        storedEngine.getString("command"),
        "a custom command in the default slot must not be overwritten by the bundled default.");
  }

  @Test
  void bundledExecutableWithCustomWeightIsNeverTreatedAsManagedDefault() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-bundled-custom-weight");
    Files.writeString(tempRoot.resolve("config.txt"), "{}");
    createBundledKataGoAssets(tempRoot, "b10c512h8nbt3tflrs-fson-silu-rsnh.bin.gz");
    Path bundledExecutable = bundledExecutable(tempRoot);
    Path customWeight =
        Files.write(tempRoot.resolve("weights").resolve("my-study.bin.gz"), new byte[] {2});
    Path gtp = tempRoot.resolve("engines").resolve("katago").resolve("configs").resolve("gtp.cfg");
    String customCommand =
        quote(bundledExecutable) + " gtp -model " + quote(customWeight) + " -config " + quote(gtp);

    assertTrue(Config.isBundledKataGoCommand(customCommand));
    assertFalse(Config.isManagedBundledDefaultCommand(customCommand));

    Config config = ConfigTestHelper.createForTests(tempRoot);
    JSONObject ui =
        new JSONObject()
            .put("first-time-load", false)
            .put("autoload-default", false)
            .put("autoload-last", false)
            .put("autoload-empty", true)
            .put("default-engine", 0);
    JSONObject customEngine =
        new JSONObject()
            .put("name", "KataGo Bundled")
            .put("command", customCommand)
            .put("isDefault", true);
    JSONObject leelaz =
        new JSONObject().put("engine-settings-list", new JSONArray().put(customEngine));
    config.config = new JSONObject().put("ui", ui).put("leelaz", leelaz);

    withUserDir(tempRoot, () -> applyBundledKataGoDefaults(config));

    JSONArray engines = leelaz.getJSONArray("engine-settings-list");
    assertEquals(2, engines.length());
    assertEquals(customCommand, engines.getJSONObject(0).getString("command"));
    assertTrue(engines.getJSONObject(0).getBoolean("isDefault"));
    assertTrue(ui.getBoolean("autoload-empty"));
  }

  @Test
  void bundledExecutableWithExternalDefaultNamedWeightIsNeverMigrated() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-external-default-weight");
    Files.writeString(tempRoot.resolve("config.txt"), "{}");
    createBundledKataGoAssets(tempRoot, "b10c512h8nbt3tflrs-fson-silu-rsnh.bin.gz");
    Path executable = bundledExecutable(tempRoot);
    Path externalWeights = Files.createDirectories(tempRoot.resolve("custom weights"));
    Path externalDefault = Files.write(externalWeights.resolve("default.bin.gz"), new byte[] {5});
    Path externalLegacy =
        Files.write(
            externalWeights.resolve(KataGoAutoSetupHelper.LEGACY_DEFAULT_WEIGHT_MODEL + ".bin.gz"),
            new byte[] {6});
    Path gtp = tempRoot.resolve("engines").resolve("katago").resolve("configs").resolve("gtp.cfg");
    String defaultCommand =
        quote(executable) + " gtp -model " + quote(externalDefault) + " -config " + quote(gtp);
    String legacyCommand =
        quote(executable) + " gtp -model " + quote(externalLegacy) + " -config " + quote(gtp);

    assertFalse(Config.isManagedBundledDefaultCommand(defaultCommand));
    assertFalse(Config.isManagedBundledDefaultCommand(legacyCommand));

    Config config = ConfigTestHelper.createForTests(tempRoot);
    JSONObject ui =
        new JSONObject()
            .put("first-time-load", false)
            .put("autoload-default", false)
            .put("autoload-last", false)
            .put("autoload-empty", true)
            .put("default-engine", 0);
    JSONObject customEngine =
        new JSONObject()
            .put("name", "KataGo Auto Setup")
            .put("command", defaultCommand)
            .put("isDefault", true);
    JSONObject leelaz =
        new JSONObject().put("engine-settings-list", new JSONArray().put(customEngine));
    config.config = new JSONObject().put("ui", ui).put("leelaz", leelaz);

    withUserDir(tempRoot, () -> applyBundledKataGoDefaults(config));

    JSONArray engines = leelaz.getJSONArray("engine-settings-list");
    assertEquals(2, engines.length());
    assertEquals(defaultCommand, engines.getJSONObject(0).getString("command"));
    assertTrue(engines.getJSONObject(0).getBoolean("isDefault"));
    assertTrue(ui.getBoolean("autoload-empty"));
  }

  @Test
  void bundledDefaultNamesRemainManagedOnlyInsideTheirOwnBundle() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-managed-default-weight");
    createBundledKataGoAssets(tempRoot, "b10c512h8nbt3tflrs-fson-silu-rsnh.bin.gz");
    Path executable = bundledExecutable(tempRoot);
    Path weights = tempRoot.resolve("weights");
    Path legacy =
        Files.write(
            weights.resolve(KataGoAutoSetupHelper.LEGACY_DEFAULT_WEIGHT_MODEL + ".bin.gz"),
            new byte[] {7});
    Path gtp = tempRoot.resolve("engines").resolve("katago").resolve("configs").resolve("gtp.cfg");

    String defaultCommand =
        quote(executable)
            + " gtp -model "
            + quote(weights.resolve("default.bin.gz"))
            + " -config "
            + quote(gtp);
    String legacyCommand =
        quote(executable) + " gtp -model " + quote(legacy) + " -config " + quote(gtp);
    String commandWithoutModel = quote(executable) + " gtp -config " + quote(gtp);

    withUserDir(
        tempRoot,
        () -> {
          assertTrue(Config.isManagedBundledDefaultCommand(defaultCommand));
          assertTrue(Config.isManagedBundledDefaultCommand(legacyCommand));
          assertTrue(Config.isManagedBundledDefaultCommand(commandWithoutModel));
        });
  }

  @Test
  void completeExternalBundleIsNeverTreatedAsTheRunningBundle() throws Exception {
    Path currentRoot = Files.createTempDirectory("lizzie-current-bundle");
    Path externalRoot = Files.createTempDirectory("lizzie-external-bundle");
    createBundledKataGoAssets(currentRoot, "b10c512h8nbt3tflrs-fson-silu-rsnh.bin.gz");
    createBundledKataGoAssets(externalRoot, "b10c512h8nbt3tflrs-fson-silu-rsnh.bin.gz");
    String externalCommand =
        quote(bundledExecutable(externalRoot))
            + " gtp -model "
            + quote(externalRoot.resolve("weights").resolve("default.bin.gz"))
            + " -config "
            + quote(
                externalRoot
                    .resolve("engines")
                    .resolve("katago")
                    .resolve("configs")
                    .resolve("gtp.cfg"));
    String externalCommandWithoutModel =
        quote(bundledExecutable(externalRoot))
            + " gtp -config "
            + quote(
                externalRoot
                    .resolve("engines")
                    .resolve("katago")
                    .resolve("configs")
                    .resolve("gtp.cfg"));

    withUserDir(
        currentRoot,
        () -> {
          assertFalse(Config.isManagedBundledDefaultCommand(externalCommand));
          assertFalse(Config.isManagedBundledDefaultCommand(externalCommandWithoutModel));
        });
  }

  @Test
  void transformerBundleMigratesOnlyTheLegacyManagedDefaultCommands() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-transformer-default-migration");
    Files.writeString(tempRoot.resolve("config.txt"), "{}");
    createBundledKataGoAssets(tempRoot, "b10c512h8nbt3tflrs-fson-silu-rsnh.bin.gz");
    Path executable = bundledExecutable(tempRoot);
    Path configs = tempRoot.resolve("engines").resolve("katago").resolve("configs");
    Path oldWeight = tempRoot.resolve("weights").resolve("kata1-zhizi-b28c512nbt-muonfd2.bin.gz");
    Files.write(oldWeight, new byte[] {3});
    String oldGtp =
        quote(executable)
            + " gtp -model "
            + quote(oldWeight)
            + " -config "
            + quote(configs.resolve("gtp.cfg"));
    String oldAnalysis =
        quote(executable)
            + " analysis -model "
            + quote(oldWeight)
            + " -config "
            + quote(configs.resolve("analysis.cfg"))
            + " -quit-without-waiting";
    String oldEstimate =
        quote(executable)
            + " gtp -model "
            + quote(oldWeight)
            + " -config "
            + quote(configs.resolve("gtp.cfg"));

    Config config = ConfigTestHelper.createForTests(tempRoot);
    JSONObject ui =
        new JSONObject()
            .put("first-time-load", false)
            .put("autoload-default", false)
            .put("autoload-last", true)
            .put("autoload-empty", false)
            .put("default-engine", 0)
            .put("analysis-engine-command", oldAnalysis)
            .put("analysis-engine-command-customized", false)
            .put("estimate-command", oldEstimate);
    JSONObject managedEngine =
        new JSONObject()
            .put("name", "KataGo Auto Setup")
            .put("command", oldGtp)
            .put("isDefault", true);
    JSONObject leelaz =
        new JSONObject().put("engine-settings-list", new JSONArray().put(managedEngine));
    config.config = new JSONObject().put("ui", ui).put("leelaz", leelaz);

    withUserDir(tempRoot, () -> applyBundledKataGoDefaults(config));

    String migratedCommand =
        leelaz.getJSONArray("engine-settings-list").getJSONObject(0).getString("command");
    assertTrue(migratedCommand.contains("weights" + java.io.File.separator + "default.bin.gz"));
    assertFalse(migratedCommand.contains("zhizi"));
    assertTrue(ui.getString("analysis-engine-command").contains("default.bin.gz"));
    assertFalse(ui.getString("analysis-engine-command").contains("zhizi"));
    assertTrue(ui.getString("estimate-command").contains("default.bin.gz"));
    assertTrue(ui.getBoolean("migrated-default-transformer-v1"));
    assertFalse(ui.getBoolean("autoload-default"));
    assertTrue(ui.getBoolean("autoload-last"));
    assertFalse(ui.getBoolean("autoload-empty"));
  }

  @Test
  void customAnalysisCommandSurvivesTransformerDefaultMigration() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-transformer-custom-analysis");
    Files.writeString(tempRoot.resolve("config.txt"), "{}");
    createBundledKataGoAssets(tempRoot, "b10c512h8nbt3tflrs-fson-silu-rsnh.bin.gz");
    Path executable = bundledExecutable(tempRoot);
    Path gtp = tempRoot.resolve("engines").resolve("katago").resolve("configs").resolve("gtp.cfg");
    Path oldWeight =
        Files.write(
            tempRoot.resolve("weights").resolve("kata1-zhizi-b28c512nbt-muonfd2.bin.gz"),
            new byte[] {4});
    String oldGtp =
        quote(executable) + " gtp -model " + quote(oldWeight) + " -config " + quote(gtp);
    String customAnalysis = "\"/opt/custom/katago\" analysis --custom-option true";

    Config config = ConfigTestHelper.createForTests(tempRoot);
    JSONObject ui =
        new JSONObject()
            .put("first-time-load", false)
            .put("analysis-engine-command", customAnalysis)
            .put("analysis-engine-command-customized", true);
    JSONObject leelaz =
        new JSONObject()
            .put(
                "engine-settings-list",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("name", "KataGo Auto Setup")
                            .put("command", oldGtp)
                            .put("isDefault", true)));
    config.config = new JSONObject().put("ui", ui).put("leelaz", leelaz);

    withUserDir(tempRoot, () -> applyBundledKataGoDefaults(config));

    assertEquals(customAnalysis, ui.getString("analysis-engine-command"));
    assertTrue(ui.getBoolean("analysis-engine-command-customized"));
  }

  @Test
  void bundledDetectionOnlyUsesTheExecutableToken() {
    assertTrue(
        Config.isBundledKataGoCommand(
            "\"C:\\app\\engines\\katago\\windows-x64\\katago.exe\" gtp"
                + " -model \"C:\\app\\weights\\default.bin.gz\""));
    assertFalse(
        Config.isBundledKataGoCommand(
            "\"C:\\custom\\engine.exe\" gtp"
                + " -config \"C:\\app\\engines\\katago\\configs\\gtp.cfg\""));
  }

  @Test
  void bundledExecutableDetectionPreservesSpacesAndUnicode() {
    Path bundledExecutable =
        Path.of("LizzieYzy Next 测试", "app", "engines", "katago", "windows-x64", "katago.exe");
    Path externalExecutable = Path.of("LizzieYzy Next 测试", "app", "custom engines", "katago.exe");

    assertTrue(Config.isBundledKataGoExecutable(bundledExecutable));
    assertFalse(Config.isBundledKataGoExecutable(externalExecutable));
  }

  @Test
  void incompleteNearbyBundleDoesNotHideACompletePortableBundle() throws Exception {
    Path incompleteRoot = Files.createTempDirectory("lizzie-incomplete-bundle");
    Files.createDirectories(incompleteRoot.resolve("engines"));
    Files.createDirectories(incompleteRoot.resolve("weights"));

    Method method = Config.class.getDeclaredMethod("hasCompleteBundledKataGoAssets", Path.class);
    method.setAccessible(true);

    assertFalse((Boolean) method.invoke(null, incompleteRoot));

    createBundledKataGoAssets(incompleteRoot);
    assertTrue((Boolean) method.invoke(null, incompleteRoot));
  }

  @Test
  void freshInstallStillSelectsBundledEngineAsDefault() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-bundled-katago-fresh");
    createBundledKataGoAssets(tempRoot);

    Config config = ConfigTestHelper.createForTests(tempRoot);
    JSONObject ui = new JSONObject();
    ui.put("first-time-load", true);
    ui.put("autoload-default", false);
    ui.put("autoload-last", true);
    ui.put("autoload-empty", true);

    JSONObject leelaz = new JSONObject();
    leelaz.put("engine-settings-list", new JSONArray());
    config.config = new JSONObject().put("ui", ui).put("leelaz", leelaz);

    withUserDir(tempRoot, () -> applyBundledKataGoDefaults(config));

    JSONArray engines = leelaz.getJSONArray("engine-settings-list");
    assertEquals(1, engines.length());
    assertTrue(ui.getBoolean("autoload-default"));
    assertFalse(ui.getBoolean("autoload-last"));
    assertFalse(ui.getBoolean("autoload-empty"));
    assertEquals(0, ui.getInt("default-engine"));
    assertTrue(engines.getJSONObject(0).getBoolean("isDefault"));
    assertEquals(7.5, engines.getJSONObject(0).getDouble("komi"));
  }

  @Test
  void existingNoEngineModeIsNotRewrittenWhenBundledAssetsAppear() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-bundled-katago-no-engine");
    Files.writeString(tempRoot.resolve("config.txt"), "{}");
    createBundledKataGoAssets(tempRoot);

    Config config = ConfigTestHelper.createForTests(tempRoot);
    JSONObject ui = new JSONObject();
    ui.put("first-time-load", false);
    ui.put("autoload-default", false);
    ui.put("autoload-last", false);
    ui.put("autoload-empty", true);

    JSONObject leelaz = new JSONObject();
    leelaz.put("engine-settings-list", new JSONArray());
    config.config = new JSONObject().put("ui", ui).put("leelaz", leelaz);

    withUserDir(tempRoot, () -> applyBundledKataGoDefaults(config));

    assertFalse(ui.getBoolean("autoload-default"));
    assertFalse(ui.getBoolean("autoload-last"));
    assertTrue(ui.getBoolean("autoload-empty"));
  }

  @Test
  void testConfigsWriteOnlyInsideTestWorkDirectory() throws Exception {
    Path tempRoot = Files.createTempDirectory("lizzie-config-test-dir");
    Config config = ConfigTestHelper.createForTests(tempRoot);
    config.uiConfig = new JSONObject();
    config.config = new JSONObject().put("ui", config.uiConfig);
    config.saveBoard = new JSONObject().put("save", new JSONObject());
    Files.createDirectories(tempRoot.resolve("save"));

    assertTrue(Path.of(config.getConfigFilePath()).startsWith(tempRoot));
    assertTrue(Path.of(config.getPersistFilePath()).startsWith(tempRoot));

    config.uiConfig.put("config-test-marker", true);
    config.save();
    config.saveTempBoard();

    assertTrue(Files.exists(tempRoot.resolve("config.txt")));
    assertTrue(Files.exists(tempRoot.resolve("save").resolve("save")));
  }

  private static void applyBundledKataGoDefaults(Config config) throws Exception {
    Method method = Config.class.getDeclaredMethod("applyBundledKataGoDefaults");
    method.setAccessible(true);
    method.invoke(config);
  }

  private static JSONObject createDefaultConfig(Config config) throws Exception {
    Method method = Config.class.getDeclaredMethod("createDefaultConfig");
    method.setAccessible(true);
    return (JSONObject) method.invoke(config);
  }

  private static void hideBlunderBarDefaultOnce(Config config) throws Exception {
    Method method = Config.class.getDeclaredMethod("hideBlunderBarDefaultOnce");
    method.setAccessible(true);
    method.invoke(config);
  }

  private static JSONObject richPortableConfig() {
    JSONObject localEngine =
        new JSONObject()
            .put("name", "My KataGo")
            .put("command", "D:/KataGo/katago.exe gtp -model D:/models/custom.bin.gz")
            .put("isDefault", false);
    JSONObject zhiziEngine =
        new JSONObject()
            .put("name", "Zhizi Cloud")
            .put("command", RemoteComputeConfig.COMMAND_ZHIZI)
            .put("isDefault", true);
    JSONObject remote =
        new JSONObject()
            .put("provider", RemoteComputeConfig.PROVIDER_ZHIZI)
            .put("zhizi-identifier", "saved-user")
            .put("remember-zhizi-token", true)
            .put("remember-zhizi-password", true);
    JSONObject leelaz =
        new JSONObject()
            .put("engine-settings-list", new JSONArray().put(localEngine).put(zhiziEngine))
            .put(RemoteComputeConfig.CONFIG_KEY, remote);
    JSONObject ui =
        new JSONObject()
            .put("first-time-load", false)
            .put("autoload-default", true)
            .put("autoload-last", false)
            .put("autoload-empty", false)
            .put("default-engine", 1)
            .put("last-engine", 1);
    return new JSONObject().put("ui", ui).put("leelaz", leelaz);
  }

  private static JSONObject freshPortableConfig() {
    JSONObject bundled =
        new JSONObject()
            .put("name", "KataGo Bundled")
            .put(
                "command",
                "app/engines/katago/windows-x64/katago.exe gtp"
                    + " -model app/weights/default.bin.gz")
            .put("isDefault", true);
    JSONObject leelaz = new JSONObject().put("engine-settings-list", new JSONArray().put(bundled));
    JSONObject ui =
        new JSONObject()
            .put("first-time-load", false)
            .put("autoload-default", true)
            .put("default-engine", 0)
            .put("last-engine", 0);
    return new JSONObject().put("ui", ui).put("leelaz", leelaz);
  }

  private static void withUserDir(Path userDir, ThrowingRunnable action) throws Exception {
    String previousUserDir = System.getProperty("user.dir");
    try {
      System.setProperty("user.dir", userDir.toString());
      action.run();
    } finally {
      if (previousUserDir == null) {
        System.clearProperty("user.dir");
      } else {
        System.setProperty("user.dir", previousUserDir);
      }
    }
  }

  private static void createBundledKataGoAssets(Path root) throws Exception {
    createBundledKataGoAssets(root, "");
  }

  private static void createBundledKataGoAssets(Path root, String modelSource) throws Exception {
    Files.writeString(root.resolve(".lizzie-portable"), "");
    Path katagoRoot = root.resolve("engines").resolve("katago");
    String[] platformDirs = {
      "macos-arm64", "macos-amd64", "linux-x64", "linux-x86", "windows-x64", "windows-x86"
    };
    for (String platformDir : platformDirs) {
      Path dir = Files.createDirectories(katagoRoot.resolve(platformDir));
      Files.write(dir.resolve("katago"), new byte[] {1});
      Files.write(dir.resolve("katago.exe"), new byte[] {1});
    }
    Path configs = Files.createDirectories(katagoRoot.resolve("configs"));
    Files.write(configs.resolve("gtp.cfg"), new byte[] {1});
    Files.write(configs.resolve("analysis.cfg"), new byte[] {1});
    if (modelSource != null && !modelSource.isEmpty()) {
      Files.writeString(
          katagoRoot.resolve("VERSION.txt"),
          "KataGo release: v1.17.0\nModel source: " + modelSource + "\n");
    }
    Files.createDirectories(root.resolve("weights"));
    Files.write(root.resolve("weights").resolve("default.bin.gz"), new byte[] {1});
  }

  private static Path bundledExecutable(Path root) {
    String osName = System.getProperty("os.name", "").toLowerCase();
    String arch = System.getProperty("os.arch", "").toLowerCase();
    boolean isArm = arch.contains("aarch64") || arch.contains("arm64");
    boolean is64 = arch.contains("64");
    String platform;
    if (osName.contains("win")) {
      platform = is64 ? "windows-x64" : "windows-x86";
    } else if (osName.contains("mac") || osName.contains("darwin")) {
      platform = isArm ? "macos-arm64" : "macos-amd64";
    } else {
      platform = is64 ? "linux-x64" : "linux-x86";
    }
    String binary =
        System.getProperty("os.name", "").toLowerCase().contains("win") ? "katago.exe" : "katago";
    return root.resolve("engines").resolve("katago").resolve(platform).resolve(binary);
  }

  private static String quote(Path path) {
    return "\"" + path.toAbsolutePath().normalize() + "\"";
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
