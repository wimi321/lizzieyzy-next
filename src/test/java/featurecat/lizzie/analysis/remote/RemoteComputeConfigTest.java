package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.AppLocale;
import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.util.Utils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class RemoteComputeConfigTest {
  @Test
  void displayNameIncludesZhiziGpuType() {
    withResourceBundle(
        AppLocale.SIMPLIFIED_CHINESE.loadBundle(),
        () -> {
          assertEquals(
              "智子云算力 VIP 包月 · 28B NBT · TensorRT",
              RemoteComputeConfig.displayNameForZhiziArgs(RemoteComputeConfig.DEFAULT_ZHIZI_ARGS));
          assertEquals(
              "智子云算力 按量 1x · 28B NBT · TensorRT",
              RemoteComputeConfig.displayNameForZhiziArgs(
                  RemoteComputeConfig.ON_DEMAND_1X_ZHIZI_ARGS));
          assertEquals(
              "智子云算力 按量 3x · 28B NBT · TensorRT",
              RemoteComputeConfig.displayNameForZhiziArgs(RemoteComputeConfig.FASTER_ZHIZI_ARGS));
          assertEquals(
              "智子云算力 VIP 包月 · 28B NBT · TensorRT",
              RemoteComputeConfig.displayNameForZhiziArgs(RemoteComputeConfig.VIP_ZHIZI_ARGS));
        });
  }

  @Test
  void gpuTypeParserReadsVipShareAndFallsBackForBlankArgs() {
    assertEquals(
        "vip-share", RemoteComputeConfig.gpuTypeForArgs(RemoteComputeConfig.VIP_ZHIZI_ARGS));
    assertEquals("vip-share", RemoteComputeConfig.gpuTypeForArgs(""));
  }

  @Test
  void weightSelectionChangesOnlyKataWeightAndSupportsNewCatalogModels() {
    String selected =
        RemoteComputeConfig.withKataWeight(RemoteComputeConfig.FASTER_ZHIZI_ARGS, "60b");

    assertEquals("60b", RemoteComputeConfig.kataWeightForArgs(selected));
    assertEquals("3x", RemoteComputeConfig.gpuTypeForArgs(selected));
    assertTrue(selected.contains("--kata-name katago-TENSORRT"));
    assertTrue(RemoteComputeConfig.sameZhiziPlan(selected, RemoteComputeConfig.FASTER_ZHIZI_ARGS));
    assertFalse(
        RemoteComputeConfig.sameZhiziPlan(selected, RemoteComputeConfig.DEFAULT_ZHIZI_ARGS));
    assertEquals(
        "智子云算力 按量 3x · 60B · TensorRT",
        withBundleResult(
            AppLocale.SIMPLIFIED_CHINESE.loadBundle(),
            () -> RemoteComputeConfig.displayNameForZhiziArgs(selected)));
  }

  @Test
  void everyConfirmedZhiziWeightUsesTheExactServerArgumentAndReadableLabel() {
    String[] weights = {"18bnbt", "28bnbt", "fdx", "60b", "40b", "20b"};
    for (String weight : weights) {
      String args =
          RemoteComputeConfig.withKataWeight(RemoteComputeConfig.DEFAULT_ZHIZI_ARGS, weight);
      assertEquals(weight, RemoteComputeConfig.kataWeightForArgs(args));
      assertTrue(args.contains("--kata-weight " + weight));
    }

    assertEquals("18B NBT", RemoteComputeConfig.displayNameForZhiziWeight("18bnbt"));
    assertEquals("28B NBT", RemoteComputeConfig.displayNameForZhiziWeight("28bnbt"));
    assertEquals("FDX · 40B NBT", RemoteComputeConfig.displayNameForZhiziWeight("fdx"));
    assertEquals("60B", RemoteComputeConfig.displayNameForZhiziWeight("60b"));
    assertEquals("40B", RemoteComputeConfig.displayNameForZhiziWeight("40b"));
    assertEquals("20B", RemoteComputeConfig.displayNameForZhiziWeight("20b"));

    withResourceBundle(
        AppLocale.SIMPLIFIED_CHINESE.loadBundle(),
        () -> {
          assertEquals("超大权重", RemoteComputeConfig.hintForZhiziWeight("fdx"));
          assertEquals("让子棋常用", RemoteComputeConfig.hintForZhiziWeight("20b"));
        });
  }

  @Test
  void invalidWeightCannotBeInjectedIntoEngineArguments() {
    String selected =
        RemoteComputeConfig.withKataWeight(
            RemoteComputeConfig.ON_DEMAND_1X_ZHIZI_ARGS, "60b --gpu-type 24x");

    assertEquals("28bnbt", RemoteComputeConfig.kataWeightForArgs(selected));
    assertEquals("1x", RemoteComputeConfig.gpuTypeForArgs(selected));
  }

  @Test
  void zhiziCatalogCachePersistsAndExpiresAfterRefreshInterval() throws Exception {
    withConfig(
        () -> {
          ZhiziEngineCatalog catalog =
              new ZhiziEngineCatalog(
                  "8.0.1",
                  "40b",
                  List.of(
                      new ZhiziEngineCatalog.Option("28bnbt", "standard"),
                      new ZhiziEngineCatalog.Option("40b", "large")));
          RemoteComputeConfig.State state = RemoteComputeConfig.load();
          state.zhiziCatalog = catalog;
          state.zhiziCatalogUpdatedAt = 1_000L;
          RemoteComputeConfig.save(state);

          RemoteComputeConfig.State restored = RemoteComputeConfig.load();
          assertEquals("40b", restored.zhiziCatalog.defaultWeight());
          assertEquals(6, restored.zhiziCatalog.weights().size());
          assertTrue(restored.zhiziCatalog.containsWeight("20b"));
          assertTrue(restored.zhiziCatalog.containsWeight("60b"));
          assertFalse(
              RemoteComputeConfig.shouldRefreshZhiziCatalog(
                  restored,
                  1_000L + RemoteComputeConfig.ZHIZI_CATALOG_REFRESH_INTERVAL_MILLIS - 1L));
          assertTrue(
              RemoteComputeConfig.shouldRefreshZhiziCatalog(
                  restored, 1_000L + RemoteComputeConfig.ZHIZI_CATALOG_REFRESH_INTERVAL_MILLIS));
        });
  }

  @Test
  void compactDisplayNameKeepsStatusAreaReadable() {
    withResourceBundle(
        AppLocale.SIMPLIFIED_CHINESE.loadBundle(),
        () -> {
          assertEquals(
              "智子云算力",
              RemoteComputeConfig.compactDisplayNameForCommand(
                  RemoteComputeConfig.COMMAND_ZHIZI,
                  "智子云算力 VIP 包月 · 28B NBT · TensorRT"));
          assertEquals(
              "自建算力",
              RemoteComputeConfig.compactDisplayNameForCommand(
                  RemoteComputeConfig.COMMAND_CUSTOM_WS, "自建算力 · compute.example.com"));
          assertEquals(
              "Local KataGo",
              RemoteComputeConfig.compactDisplayNameForCommand(
                  "katago.exe gtp -config default.cfg", "Local KataGo"));
        });
  }

  @Test
  void remoteDisplayNamesFollowThaiLocaleWithoutHanFallbacks() {
    withResourceBundle(
        AppLocale.THAI.loadBundle(),
        () -> {
          String custom =
              RemoteComputeConfig.displayNameForCustomWebSocketUrl(
                  "wss://compute.example.com/katago");
          String zhizi =
              RemoteComputeConfig.displayNameForZhiziArgs(
                  RemoteComputeConfig.ON_DEMAND_1X_ZHIZI_ARGS);

          assertEquals("การคำนวณแบบกำหนดเอง · compute.example.com", custom);
          assertTrue(zhizi.startsWith("คลาวด์ Zhizi ตามการใช้งาน 1x · 28B NBT · "));
          assertFalse(custom.matches(".*\\p{IsHan}.*"));
          assertFalse(zhizi.matches(".*\\p{IsHan}.*"));
        });
  }

  @Test
  void vipFailureMessageSuggestsSwitchingToOnDemand() {
    withResourceBundle(
        AppLocale.ENGLISH.loadBundle(),
        () -> {
          String message =
              RemoteComputeConfig.friendlyZhiziErrorMessage(
                  "no worker available", RemoteComputeConfig.DEFAULT_ZHIZI_ARGS);

          assertEquals(
              "no worker available\n\n"
                  + "This account may not have VIP monthly access, or no VIP worker is available. "
                  + "Switch to On-demand 1x in advanced settings, or check the Zhizi plan.",
              message);
          assertEquals(
              "no worker available",
              RemoteComputeConfig.friendlyZhiziErrorMessage(
                  "no worker available", RemoteComputeConfig.ON_DEMAND_1X_ZHIZI_ARGS));
        });
  }

  @Test
  void zhiziPasswordIsOnlySavedWhenExplicitlyRemembered() throws Exception {
    withConfig(
        () -> {
          RemoteComputeConfig.saveZhiziToken(
              "token",
              true,
              RemoteComputeConfig.DEFAULT_ZHIZI_ARGS,
              "user@example.com",
              "secret-password",
              true);

          RemoteComputeConfig.State state = RemoteComputeConfig.load();
          assertTrue(state.rememberZhiziPassword);
          assertEquals("secret-password", state.zhiziPassword);
          JSONObject json =
              Lizzie.config.leelazConfig.getJSONObject(RemoteComputeConfig.CONFIG_KEY);
          assertFalse(json.optString("zhizi-password-v1").contains("secret-password"));

          RemoteComputeConfig.saveZhiziToken(
              "token2",
              true,
              RemoteComputeConfig.DEFAULT_ZHIZI_ARGS,
              "user@example.com",
              "new-password",
              false);

          state = RemoteComputeConfig.load();
          assertFalse(state.rememberZhiziPassword);
          assertEquals("", state.zhiziPassword);
          json = Lizzie.config.leelazConfig.getJSONObject(RemoteComputeConfig.CONFIG_KEY);
          assertFalse(json.has("zhizi-password-v1"));
        });
  }

  @Test
  void codeLoginPreservesRememberedPasswordUntilLogout() throws Exception {
    withConfig(
        () -> {
          RemoteComputeConfig.saveZhiziToken(
              "password-token",
              true,
              RemoteComputeConfig.DEFAULT_ZHIZI_ARGS,
              "user@example.com",
              "remember-me",
              true);

          RemoteComputeConfig.saveZhiziToken(
              "code-token", true, RemoteComputeConfig.ON_DEMAND_1X_ZHIZI_ARGS, "user@example.com");

          RemoteComputeConfig.State state = RemoteComputeConfig.load();
          assertEquals("code-token", state.zhiziAccountToken);
          assertTrue(state.rememberZhiziPassword);
          assertEquals("remember-me", state.zhiziPassword);

          RemoteComputeConfig.clearZhiziToken();
          state = RemoteComputeConfig.load();
          assertEquals("", state.zhiziAccountToken);
          assertFalse(state.rememberZhiziPassword);
          assertEquals("", state.zhiziPassword);
        });
  }

  @Test
  void zhiziLoginDoesNotSwitchActiveProviderUntilUserEnablesIt() throws Exception {
    withConfig(
        () -> {
          assertEquals(RemoteComputeConfig.PROVIDER_LOCAL, RemoteComputeConfig.load().provider);

          RemoteComputeConfig.saveZhiziToken(
              "token", true, RemoteComputeConfig.DEFAULT_ZHIZI_ARGS, "user@example.com");

          RemoteComputeConfig.State state = RemoteComputeConfig.load();
          assertEquals("token", state.zhiziAccountToken);
          assertEquals(RemoteComputeConfig.PROVIDER_LOCAL, state.provider);

          state.provider = RemoteComputeConfig.PROVIDER_CUSTOM;
          state.customRemoteCode = "wss://compute.example.com/katago";
          RemoteComputeConfig.save(state);
          RemoteComputeConfig.saveZhiziToken(
              "new-token", true, RemoteComputeConfig.ON_DEMAND_1X_ZHIZI_ARGS, "user@example.com");

          state = RemoteComputeConfig.load();
          assertEquals("new-token", state.zhiziAccountToken);
          assertEquals(RemoteComputeConfig.PROVIDER_CUSTOM, state.provider);
        });
  }

  @Test
  void switchingBackToLocalPersistsLocalEngineForNextStartup() throws Exception {
    withConfig(
        () -> {
          ArrayList<EngineData> engines = new ArrayList<>();
          EngineData local = new EngineData();
          local.commands = "katago.exe gtp -config default.cfg";
          local.name = "Local KataGo";
          local.isDefault = false;
          engines.add(local);

          EngineData zhizi = new EngineData();
          zhizi.commands = RemoteComputeConfig.COMMAND_ZHIZI;
          zhizi.name = "智子云算力";
          zhizi.isDefault = true;
          engines.add(zhizi);
          Lizzie.config.uiConfig.put("last-engine", 1);
          Utils.saveEngineSettings(engines);

          RemoteComputeConfig.State state = RemoteComputeConfig.load();
          state.provider = RemoteComputeConfig.PROVIDER_ZHIZI;
          RemoteComputeConfig.save(state);

          int localIndex = RemoteComputeConfig.saveLocalProviderAndDefaultEngine();

          assertEquals(0, localIndex);
          assertEquals(RemoteComputeConfig.PROVIDER_LOCAL, RemoteComputeConfig.load().provider);
          ArrayList<EngineData> savedEngines = Utils.getEngineData();
          assertTrue(savedEngines.get(0).isDefault);
          assertFalse(savedEngines.get(1).isDefault);
          assertEquals(0, Lizzie.config.uiConfig.optInt("default-engine", -1));
          assertEquals(0, Lizzie.config.uiConfig.optInt("last-engine", -1));
        });
  }

  @Test
  void customWebSocketUrlIsNormalizedAndRecognized() {
    assertEquals(
        "wss://example.com/katago?token=abc",
        RemoteComputeConfig.normalizeCustomWebSocketUrl(
            "  二维码内容: wss://example.com/katago?token=abc  "));
    assertEquals(
        "ws://127.0.0.1:2718",
        RemoteComputeConfig.normalizeCustomWebSocketUrl("完善：//127.0.0.1：2718"));
    assertEquals(
        "ws://127.0.0.1:2718",
        RemoteComputeConfig.normalizeCustomWebSocketUrl("ｗｓ：／／127.0.0.1：2718"));
    assertTrue(RemoteComputeConfig.isCustomWebSocketUrl("ws://127.0.0.1:2718"));
    assertTrue(RemoteComputeConfig.isCustomWebSocketUrl("完善：//127.0.0.1：2718"));
    assertTrue(RemoteComputeConfig.isCustomWebSocketUrl("wss://remote.example.com/katago"));
    assertFalse(RemoteComputeConfig.isCustomWebSocketUrl("http://remote.example.com/katago"));
    assertFalse(RemoteComputeConfig.isCustomWebSocketUrl("remote.example.com/katago"));
  }

  @Test
  void customWebSocketEngineCanBecomeDefaultAndLocalSwitchSkipsIt() throws Exception {
    withConfig(
        () ->
            withResourceBundle(
                AppLocale.ENGLISH.loadBundle(),
                () -> {
                  ArrayList<EngineData> engines = new ArrayList<>();
                  EngineData local = new EngineData();
                  local.commands = "katago.exe gtp -config default.cfg";
                  local.name = "Local KataGo";
                  local.isDefault = false;
                  engines.add(local);
                  Utils.saveEngineSettings(engines);

                  RemoteComputeConfig.State state = RemoteComputeConfig.load();
                  state.provider = RemoteComputeConfig.PROVIDER_CUSTOM;
                  state.customRemoteCode = "wss://compute.example.com/katago";
                  RemoteComputeConfig.save(state);

                  int customIndex = RemoteComputeConfig.createOrUpdateCustomWebSocketEngine(true);

                  ArrayList<EngineData> savedEngines = Utils.getEngineData();
                  assertEquals(1, customIndex);
                  assertEquals(
                      RemoteComputeConfig.COMMAND_CUSTOM_WS,
                      savedEngines.get(customIndex).commands);
                  assertEquals(
                      "Custom Compute · compute.example.com", savedEngines.get(customIndex).name);
                  assertTrue(savedEngines.get(customIndex).isDefault);
                  assertEquals(1, Lizzie.config.uiConfig.optInt("last-engine", -1));

                  int localIndex = RemoteComputeConfig.saveLocalProviderAndDefaultEngine();

                  savedEngines = Utils.getEngineData();
                  assertEquals(0, localIndex);
                  assertTrue(savedEngines.get(0).isDefault);
                  assertFalse(savedEngines.get(customIndex).isDefault);
                  assertEquals(0, Lizzie.config.uiConfig.optInt("last-engine", -1));
                }));
  }

  @Test
  void startupWithoutRememberedZhiziLoginUsesLocalEngineForThisSessionOnly() throws Exception {
    withConfig(
        () -> {
          RemoteComputeConfig.clearZhiziToken();
          ArrayList<EngineData> engines = new ArrayList<>();
          EngineData local = new EngineData();
          local.index = 0;
          local.commands = "katago.exe gtp -config default.cfg";
          local.name = "Local KataGo";
          local.isDefault = false;
          engines.add(local);

          EngineData zhizi = new EngineData();
          zhizi.index = 1;
          zhizi.commands = RemoteComputeConfig.COMMAND_ZHIZI;
          zhizi.name = "智子云算力";
          zhizi.isDefault = true;
          engines.add(zhizi);
          Utils.saveEngineSettings(engines);
          Lizzie.config.uiConfig.put("default-engine", 1);
          Lizzie.config.uiConfig.put("last-engine", 1);

          RemoteComputeConfig.State state = RemoteComputeConfig.load();
          state.provider = RemoteComputeConfig.PROVIDER_ZHIZI;
          RemoteComputeConfig.save(state);

          RemoteComputeConfig.StartupSelection defaultSelection =
              RemoteComputeConfig.resolveStartupSelection(-1, true);
          RemoteComputeConfig.StartupSelection lastSelection =
              RemoteComputeConfig.resolveStartupSelection(1, false);

          assertEquals(0, defaultSelection.engineIndex);
          assertFalse(defaultSelection.loadDefault);
          assertEquals(0, lastSelection.engineIndex);
          assertFalse(lastSelection.loadDefault);
          assertEquals(RemoteComputeConfig.PROVIDER_ZHIZI, RemoteComputeConfig.load().provider);
          assertEquals(1, Lizzie.config.uiConfig.optInt("default-engine", -1));
          assertEquals(1, Lizzie.config.uiConfig.optInt("last-engine", -1));
          assertTrue(Utils.getEngineData().get(1).isDefault);
        });
  }

  @Test
  void startupKeepsZhiziSelectedWhenRememberedLoginIsAvailable() throws Exception {
    withConfig(
        () -> {
          RemoteComputeConfig.clearZhiziToken();
          ArrayList<EngineData> engines = new ArrayList<>();
          EngineData local = new EngineData();
          local.index = 0;
          local.commands = "katago.exe gtp -config default.cfg";
          engines.add(local);

          EngineData zhizi = new EngineData();
          zhizi.index = 1;
          zhizi.commands = RemoteComputeConfig.COMMAND_ZHIZI;
          zhizi.isDefault = true;
          engines.add(zhizi);
          Utils.saveEngineSettings(engines);

          RemoteComputeConfig.saveZhiziToken(
              "remembered-token", true, RemoteComputeConfig.DEFAULT_ZHIZI_ARGS, "user");
          RemoteComputeConfig.StartupSelection selection =
              RemoteComputeConfig.resolveStartupSelection(-1, true);

          assertEquals(-1, selection.engineIndex);
          assertTrue(selection.loadDefault);
        });
  }

  private static void withResourceBundle(ResourceBundle bundle, Runnable action) {
    ResourceBundle previous = Lizzie.resourceBundle;
    try {
      Lizzie.resourceBundle = bundle;
      action.run();
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }

  private static <T> T withBundleResult(ResourceBundle bundle, ResultSupplier<T> supplier) {
    ResourceBundle previous = Lizzie.resourceBundle;
    try {
      Lizzie.resourceBundle = bundle;
      return supplier.get();
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }

  private static void withConfig(ThrowingRunnable action) throws Exception {
    Config previousConfig = Lizzie.config;
    Path runtimeRoot = Files.createTempDirectory("remote-compute-config");
    try {
      Config config = ConfigTestHelper.createForTests(runtimeRoot);
      config.config = new JSONObject();
      config.leelazConfig = new JSONObject();
      config.uiConfig = new JSONObject();
      config.config.put("leelaz", config.leelazConfig);
      config.config.put("ui", config.uiConfig);
      Lizzie.config = config;
      action.run();
    } finally {
      Lizzie.config = previousConfig;
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private interface ResultSupplier<T> {
    T get();
  }
}
