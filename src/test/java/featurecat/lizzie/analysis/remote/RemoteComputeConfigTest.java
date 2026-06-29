package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.util.Utils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class RemoteComputeConfigTest {
  @Test
  void displayNameIncludesZhiziGpuType() {
    assertEquals(
        "智子云算力 VIP包月 · 智子28B · TensorRT",
        RemoteComputeConfig.displayNameForZhiziArgs(RemoteComputeConfig.DEFAULT_ZHIZI_ARGS));
    assertEquals(
        "智子云算力 按量1x · 智子28B · TensorRT",
        RemoteComputeConfig.displayNameForZhiziArgs(RemoteComputeConfig.ON_DEMAND_1X_ZHIZI_ARGS));
    assertEquals(
        "智子云算力 按量3x · 智子28B · TensorRT",
        RemoteComputeConfig.displayNameForZhiziArgs(RemoteComputeConfig.FASTER_ZHIZI_ARGS));
    assertEquals(
        "智子云算力 VIP包月 · 智子28B · TensorRT",
        RemoteComputeConfig.displayNameForZhiziArgs(RemoteComputeConfig.VIP_ZHIZI_ARGS));
  }

  @Test
  void gpuTypeParserReadsVipShareAndFallsBackForBlankArgs() {
    assertEquals("vip-share", RemoteComputeConfig.gpuTypeForArgs(RemoteComputeConfig.VIP_ZHIZI_ARGS));
    assertEquals("vip-share", RemoteComputeConfig.gpuTypeForArgs(""));
  }

  @Test
  void compactDisplayNameKeepsStatusAreaReadable() {
    assertEquals(
        "智子云算力",
        RemoteComputeConfig.compactDisplayNameForCommand(
            RemoteComputeConfig.COMMAND_ZHIZI, "智子云算力 VIP包月 · 智子28B · TensorRT"));
    assertEquals(
        "自建算力",
        RemoteComputeConfig.compactDisplayNameForCommand(
            RemoteComputeConfig.COMMAND_CUSTOM_WS, "自建算力 · compute.example.com"));
    assertEquals(
        "Local KataGo",
        RemoteComputeConfig.compactDisplayNameForCommand(
            "katago.exe gtp -config default.cfg", "Local KataGo"));
  }

  @Test
  void vipFailureMessageSuggestsSwitchingToOnDemand() {
    String message =
        RemoteComputeConfig.friendlyZhiziErrorMessage(
            "no worker available", RemoteComputeConfig.DEFAULT_ZHIZI_ARGS);

    assertEquals(
        "no worker available\n\n"
            + "当前账号可能未开通 VIP 包月，或 VIP 算力暂时没有可用 worker。"
            + "请在高级设置切换到“按量 1x”，或检查智子账号套餐。",
        message);
    assertEquals(
        "no worker available",
        RemoteComputeConfig.friendlyZhiziErrorMessage(
            "no worker available", RemoteComputeConfig.ON_DEMAND_1X_ZHIZI_ARGS));
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
          JSONObject json = Lizzie.config.leelazConfig.getJSONObject(RemoteComputeConfig.CONFIG_KEY);
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
              "code-token",
              true,
              RemoteComputeConfig.ON_DEMAND_1X_ZHIZI_ARGS,
              "user@example.com");

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
    assertTrue(RemoteComputeConfig.isCustomWebSocketUrl("ws://127.0.0.1:2718"));
    assertTrue(RemoteComputeConfig.isCustomWebSocketUrl("wss://remote.example.com/katago"));
    assertFalse(RemoteComputeConfig.isCustomWebSocketUrl("http://remote.example.com/katago"));
    assertFalse(RemoteComputeConfig.isCustomWebSocketUrl("remote.example.com/katago"));
  }

  @Test
  void customWebSocketEngineCanBecomeDefaultAndLocalSwitchSkipsIt() throws Exception {
    withConfig(
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
          assertEquals(RemoteComputeConfig.COMMAND_CUSTOM_WS, savedEngines.get(customIndex).commands);
          assertEquals("自建算力 · compute.example.com", savedEngines.get(customIndex).name);
          assertTrue(savedEngines.get(customIndex).isDefault);
          assertEquals(1, Lizzie.config.uiConfig.optInt("last-engine", -1));

          int localIndex = RemoteComputeConfig.saveLocalProviderAndDefaultEngine();

          savedEngines = Utils.getEngineData();
          assertEquals(0, localIndex);
          assertTrue(savedEngines.get(0).isDefault);
          assertFalse(savedEngines.get(customIndex).isDefault);
          assertEquals(0, Lizzie.config.uiConfig.optInt("last-engine", -1));
        });
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
}
