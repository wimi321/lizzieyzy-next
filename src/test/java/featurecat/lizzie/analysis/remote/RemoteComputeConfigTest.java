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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
    String[] weights = {"18bnbt", "28bnbt", "fdx", "20b", "10b384t", "10b512t", "11b768t"};
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
          assertEquals(
              "Transformer 10B · 轻量版", RemoteComputeConfig.displayNameForZhiziWeight("10b384t"));
          assertEquals(
              "Transformer 10B · 均衡版", RemoteComputeConfig.displayNameForZhiziWeight("10b512t"));
          assertEquals(
              "Transformer 11B · 旗舰版", RemoteComputeConfig.displayNameForZhiziWeight("11b768t"));
        });
  }

  @Test
  void invalidWeightCannotBeInjectedIntoEngineArguments() {
    String selected =
        RemoteComputeConfig.withKataWeight(
            RemoteComputeConfig.ON_DEMAND_1X_ZHIZI_ARGS, "60b --gpu-type 24x");

    assertEquals("28bnbt", RemoteComputeConfig.kataWeightForArgs(selected));
    assertEquals("1x", RemoteComputeConfig.gpuTypeForArgs(selected));

    String futureServerModel =
        RemoteComputeConfig.withKataWeight(
            RemoteComputeConfig.ON_DEMAND_1X_ZHIZI_ARGS, "future-net");
    assertEquals("future-net", RemoteComputeConfig.kataWeightForArgs(futureServerModel));
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
          assertEquals("28bnbt", restored.zhiziCatalog.defaultWeight());
          assertEquals(8, restored.zhiziCatalog.weights().size());
          assertTrue(restored.zhiziCatalog.containsWeight("18bnbt"));
          assertTrue(restored.zhiziCatalog.containsWeight("fdx"));
          assertTrue(restored.zhiziCatalog.containsWeight("20b"));
          assertTrue(restored.zhiziCatalog.containsWeight("10b512t"));
          assertFalse(restored.zhiziCatalog.containsWeight("60b"));
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
                  RemoteComputeConfig.COMMAND_ZHIZI, "智子云算力 VIP 包月 · 28B NBT · TensorRT"));
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
          RemoteComputeConfig.CredentialSaveResult saved =
              RemoteComputeConfig.saveZhiziToken(
                  "super-secret-account-token-value",
                  true,
                  RemoteComputeConfig.DEFAULT_ZHIZI_ARGS,
                  "user@example.com",
                  "secret-password",
                  true);

          RemoteComputeConfig.State state = RemoteComputeConfig.load();
          assertTrue(state.rememberZhiziPassword);
          assertTrue(state.passwordStoredSecurely);
          assertTrue(state.tokenStoredSecurely);
          assertTrue(saved.passwordStored);
          assertTrue(saved.tokenStored);
          assertEquals("secret-password", state.zhiziPassword);
          JSONObject json =
              Lizzie.config.leelazConfig.getJSONObject(RemoteComputeConfig.CONFIG_KEY);
          assertFalse(json.has("zhizi-password-v1"));
          assertFalse(json.has("zhizi-account-token"));
          assertFalse(json.toString().contains("secret-password"));
          assertFalse(json.toString().contains("super-secret-account-token-value"));

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
          assertFalse(json.has("zhizi-account-token"));
        });
  }

  @Test
  void legacyCredentialsMigrateOnlyAfterSecureStorageAcceptsThem() throws Exception {
    withConfigAndStore(
        store -> {
          JSONObject json = new JSONObject();
          json.put("zhizi-identifier", "legacy@example.com");
          json.put("remember-zhizi-token", true);
          json.put("remember-zhizi-password", true);
          json.put("zhizi-account-token", "legacy-token");
          json.put(
              "zhizi-password-v1",
              Base64.getEncoder()
                  .encodeToString("legacy-password".getBytes(StandardCharsets.UTF_8)));
          Lizzie.config.leelazConfig.put(RemoteComputeConfig.CONFIG_KEY, json);

          RemoteComputeConfig.State state = RemoteComputeConfig.load();

          assertEquals("legacy-token", state.zhiziAccountToken);
          assertEquals("legacy-password", state.zhiziPassword);
          assertTrue(state.tokenStoredSecurely);
          assertTrue(state.passwordStoredSecurely);
          assertFalse(state.credentialMigrationFailed);
          assertFalse(json.has("zhizi-account-token"));
          assertFalse(json.has("zhizi-password-v1"));
          assertEquals(
              "legacy-token",
              store.read(CredentialStore.Kind.ACCOUNT_TOKEN, "legacy@example.com").orElseThrow());
          assertEquals(
              "legacy-password",
              store.read(CredentialStore.Kind.PASSWORD, "legacy@example.com").orElseThrow());
        });
  }

  @Test
  void failedLegacyMigrationLeavesLegacyDataButDoesNotAutoLogin() throws Exception {
    withConfigAndStore(
        store -> {
          store.failWrites = true;
          JSONObject json = new JSONObject();
          json.put("zhizi-identifier", "legacy@example.com");
          json.put("remember-zhizi-token", true);
          json.put("remember-zhizi-password", true);
          json.put("zhizi-account-token", "legacy-token");
          json.put(
              "zhizi-password-v1",
              Base64.getEncoder()
                  .encodeToString("legacy-password".getBytes(StandardCharsets.UTF_8)));
          Lizzie.config.leelazConfig.put(RemoteComputeConfig.CONFIG_KEY, json);

          RemoteComputeConfig.State state = RemoteComputeConfig.load();

          assertEquals("", state.zhiziAccountToken);
          assertEquals("", state.zhiziPassword);
          assertTrue(state.credentialMigrationFailed);
          assertTrue(json.has("zhizi-account-token"));
          assertTrue(json.has("zhizi-password-v1"));

          RemoteComputeConfig.save(state);
          JSONObject preserved =
              Lizzie.config.leelazConfig.getJSONObject(RemoteComputeConfig.CONFIG_KEY);
          assertEquals("legacy-token", preserved.getString("zhizi-account-token"));
          assertTrue(preserved.has("zhizi-password-v1"));
        });
  }

  @Test
  void unavailableSecureStorageKeepsNewLoginInMemoryForThisSessionOnly() throws Exception {
    withConfigAndStore(
        store -> {
          store.available = false;

          RemoteComputeConfig.CredentialSaveResult result =
              RemoteComputeConfig.saveZhiziToken(
                  "session-token",
                  true,
                  RemoteComputeConfig.DEFAULT_ZHIZI_ARGS,
                  "session@example.com",
                  "session-password",
                  true);

          assertTrue(result.isSessionOnly());
          RemoteComputeConfig.State state = RemoteComputeConfig.load();
          assertEquals("session-token", state.zhiziAccountToken);
          assertEquals("session-password", state.zhiziPassword);
          assertFalse(state.rememberZhiziToken);
          assertFalse(state.rememberZhiziPassword);
          JSONObject json =
              Lizzie.config.leelazConfig.getJSONObject(RemoteComputeConfig.CONFIG_KEY);
          assertFalse(json.has("zhizi-account-token"));
          assertFalse(json.has("zhizi-password-v1"));

          RemoteComputeConfig.setCredentialStoreForTests(store);
          state = RemoteComputeConfig.load();
          assertEquals("", state.zhiziAccountToken);
          assertEquals("", state.zhiziPassword);
        });
  }

  @Test
  void switchingAccountsAndLogoutRemoveOldSecureCredentials() throws Exception {
    withConfigAndStore(
        store -> {
          RemoteComputeConfig.saveZhiziToken(
              "first-token",
              true,
              RemoteComputeConfig.DEFAULT_ZHIZI_ARGS,
              "first@example.com",
              "first-password",
              true);
          store.write(CredentialStore.Kind.API_KEY, "first@example.com", "unrelated-api-key");

          RemoteComputeConfig.saveZhiziToken(
              "second-token",
              true,
              RemoteComputeConfig.DEFAULT_ZHIZI_ARGS,
              "second@example.com",
              "second-password",
              true);

          assertTrue(store.read(CredentialStore.Kind.ACCOUNT_TOKEN, "first@example.com").isEmpty());
          assertTrue(store.read(CredentialStore.Kind.PASSWORD, "first@example.com").isEmpty());
          assertEquals(
              "unrelated-api-key",
              store.read(CredentialStore.Kind.API_KEY, "first@example.com").orElseThrow());
          assertEquals(
              "second-token",
              store.read(CredentialStore.Kind.ACCOUNT_TOKEN, "second@example.com").orElseThrow());

          RemoteComputeConfig.clearZhiziToken();
          assertTrue(
              store.read(CredentialStore.Kind.ACCOUNT_TOKEN, "second@example.com").isEmpty());
          assertTrue(store.read(CredentialStore.Kind.PASSWORD, "second@example.com").isEmpty());
          assertEquals(
              "unrelated-api-key",
              store.read(CredentialStore.Kind.API_KEY, "first@example.com").orElseThrow());
        });
  }

  @Test
  void ordinarySettingsSaveDoesNotRewriteUnchangedSecureCredentials() throws Exception {
    withConfigAndStore(
        store -> {
          RemoteComputeConfig.saveZhiziToken(
              "stable-token",
              true,
              RemoteComputeConfig.DEFAULT_ZHIZI_ARGS,
              "stable@example.com",
              "stable-password",
              true);
          int writesAfterLogin = store.writeCount;

          RemoteComputeConfig.State state = RemoteComputeConfig.load();
          state.provider = RemoteComputeConfig.PROVIDER_ZHIZI;
          RemoteComputeConfig.save(state);

          assertEquals(writesAfterLogin, store.writeCount);
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
  void expiredTokenIsRemovedWithoutDiscardingAccountPlanOrSavedPassword() throws Exception {
    withConfigAndStore(
        store -> {
          RemoteComputeConfig.saveZhiziToken(
              "expired-token",
              true,
              RemoteComputeConfig.FASTER_ZHIZI_ARGS,
              "user@example.com",
              "remember-me",
              true);
          RemoteComputeConfig.State state = RemoteComputeConfig.load();
          state.provider = RemoteComputeConfig.PROVIDER_ZHIZI;
          RemoteComputeConfig.save(state);

          RemoteComputeConfig.invalidateZhiziToken();

          state = RemoteComputeConfig.load();
          assertEquals("", state.zhiziAccountToken);
          assertFalse(state.rememberZhiziToken);
          assertEquals("user@example.com", state.zhiziIdentifier);
          assertEquals(RemoteComputeConfig.PROVIDER_ZHIZI, state.provider);
          assertEquals(RemoteComputeConfig.FASTER_ZHIZI_ARGS, state.zhiziArgs);
          assertTrue(state.rememberZhiziPassword);
          assertEquals("remember-me", state.zhiziPassword);
          assertTrue(store.read(CredentialStore.Kind.ACCOUNT_TOKEN, "user@example.com").isEmpty());
          assertEquals(
              "remember-me",
              store.read(CredentialStore.Kind.PASSWORD, "user@example.com").orElseThrow());
        });
  }

  @Test
  void structuredErrorsAreLocalizedWithoutShowingServerJson() {
    withResourceBundle(
        AppLocale.SIMPLIFIED_CHINESE.loadBundle(),
        () -> {
          ZhiziApiException sendFailure =
              new ZhiziApiException(
                  500,
                  "send_code_error",
                  "request-42",
                  0,
                  false,
                  ZhiziApiException.Operation.SEND_CODE);
          String message =
              RemoteComputeConfig.friendlyZhiziErrorMessage(
                  sendFailure, RemoteComputeConfig.DEFAULT_ZHIZI_ARGS);
          assertTrue(message.contains("验证码暂时发送失败"));
          assertTrue(message.contains("request-42"));
          assertFalse(message.contains("send_code_error"));

          ZhiziApiException futureFailure =
              new ZhiziApiException(
                  409, "future_private_error", "", 0, false, ZhiziApiException.Operation.OTHER);
          String fallback =
              RemoteComputeConfig.friendlyZhiziErrorMessage(
                  futureFailure, RemoteComputeConfig.ON_DEMAND_1X_ZHIZI_ARGS);
          assertEquals("远程算力连接失败。", fallback);

          assertEquals(
              "远程算力连接失败。",
              RemoteComputeConfig.friendlyZhiziErrorMessage(
                  "{\"statusCode\":500,\"key\":\"private\"}",
                  RemoteComputeConfig.ON_DEMAND_1X_ZHIZI_ARGS));
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
    withConfigAndStore(store -> action.run());
  }

  private static void withConfigAndStore(ThrowingStoreRunnable action) throws Exception {
    Config previousConfig = Lizzie.config;
    Path runtimeRoot = Files.createTempDirectory("remote-compute-config");
    TestCredentialStore store = new TestCredentialStore();
    try {
      Config config = ConfigTestHelper.createForTests(runtimeRoot);
      config.config = new JSONObject();
      config.leelazConfig = new JSONObject();
      config.uiConfig = new JSONObject();
      config.config.put("leelaz", config.leelazConfig);
      config.config.put("ui", config.uiConfig);
      Lizzie.config = config;
      RemoteComputeConfig.setCredentialStoreForTests(store);
      action.run(store);
    } finally {
      RemoteComputeConfig.resetCredentialStateForTests();
      Lizzie.config = previousConfig;
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private interface ThrowingStoreRunnable {
    void run(TestCredentialStore store) throws Exception;
  }

  private static final class TestCredentialStore implements CredentialStore {
    private final Map<String, String> secrets = new HashMap<>();
    boolean available = true;
    boolean failReads;
    boolean failWrites;
    boolean failDeletes;
    int writeCount;

    @Override
    public String backendName() {
      return "test-memory";
    }

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public Optional<String> read(Kind kind, String account) throws IOException {
      if (failReads) {
        throw new IOException("read failed");
      }
      return Optional.ofNullable(secrets.get(key(kind, account)));
    }

    @Override
    public void write(Kind kind, String account, String secret) throws IOException {
      if (!available || failWrites) {
        throw new IOException("write failed");
      }
      writeCount++;
      secrets.put(key(kind, account), secret);
    }

    @Override
    public void delete(Kind kind, String account) throws IOException {
      if (failDeletes) {
        throw new IOException("delete failed");
      }
      secrets.remove(key(kind, account));
    }

    private static String key(Kind kind, String account) {
      return kind.id() + ":" + (account == null ? "" : account.trim().toLowerCase(Locale.ROOT));
    }
  }

  private interface ResultSupplier<T> {
    T get();
  }
}
