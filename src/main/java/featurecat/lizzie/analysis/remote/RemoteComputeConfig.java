package featurecat.lizzie.analysis.remote;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.json.JSONObject;

public final class RemoteComputeConfig {
  public static final String CONFIG_KEY = "remote-compute";
  public static final String COMMAND_ZHIZI = "remote-compute://zhizi";
  public static final String COMMAND_CUSTOM_WS = "remote-compute://custom-websocket";
  public static final String PROVIDER_LOCAL = "local";
  public static final String PROVIDER_ZHIZI = "zhizi";
  public static final String PROVIDER_CUSTOM = "custom";
  public static final String PROVIDER_LEGACY_SSH = "legacy-ssh";
  public static final String VIP_ZHIZI_ARGS =
      "--platform all --engine-type go --gpu-type vip-share --kata-name katago-TENSORRT --kata-weight 28bnbt";
  public static final String DEFAULT_ZHIZI_ARGS = VIP_ZHIZI_ARGS;
  public static final String ON_DEMAND_1X_ZHIZI_ARGS =
      "--platform all --engine-type go --gpu-type 1x --kata-name katago-TENSORRT --kata-weight 28bnbt";
  public static final String FASTER_ZHIZI_ARGS =
      "--platform all --engine-type go --gpu-type 3x --kata-name katago-TENSORRT --kata-weight 28bnbt";
  public static final String FASTEST_ZHIZI_ARGS =
      "--platform all --engine-type go --gpu-type 6x --kata-name katago-TENSORRT --kata-weight 28bnbt";
  public static final String TWELVE_X_ZHIZI_ARGS =
      "--platform all --engine-type go --gpu-type 12x --kata-name katago-TENSORRT --kata-weight 28bnbt";
  public static final String TWENTY_FOUR_X_ZHIZI_ARGS =
      "--platform all --engine-type go --gpu-type 24x --kata-name katago-TENSORRT --kata-weight 28bnbt";
  public static final String QUICK_START_ZHIZI_ARGS =
      "--platform all --engine-type go --gpu-type 1x --kata-name katago-CUDA --kata-weight 28bnbt";
  public static final long ZHIZI_CATALOG_REFRESH_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L;

  private static final String LEGACY_TOKEN_KEY = "zhizi-account-token";
  private static final String LEGACY_PASSWORD_KEY = "zhizi-password-v1";
  private static final String CREDENTIAL_BACKEND_KEY = "zhizi-credential-backend";
  private static final Object CREDENTIAL_LOCK = new Object();
  private static volatile String sessionZhiziToken = "";
  private static volatile String sessionZhiziPassword = "";
  private static volatile String sessionZhiziIdentifier = "";
  private static volatile boolean sessionTokenStoredSecurely;
  private static volatile boolean sessionPasswordStoredSecurely;
  private static volatile CredentialStore credentialStoreOverride;
  private static volatile CredentialStore systemCredentialStore;
  private static volatile Path systemCredentialDirectory;

  private RemoteComputeConfig() {}

  public static State load() {
    JSONObject json =
        Lizzie.config == null || Lizzie.config.leelazConfig == null
            ? new JSONObject()
            : Lizzie.config.leelazConfig.optJSONObject(CONFIG_KEY);
    if (json == null) {
      json = new JSONObject();
    }
    State state = new State();
    state.provider = json.optString("provider", PROVIDER_LOCAL);
    state.zhiziIdentifier = json.optString("zhizi-identifier", "");
    state.rememberZhiziToken = json.optBoolean("remember-zhizi-token", false);
    state.rememberZhiziPassword = json.optBoolean("remember-zhizi-password", false);
    state.zhiziArgs = json.optString("zhizi-args", DEFAULT_ZHIZI_ARGS);
    state.zhiziCatalog = loadZhiziCatalog(json.optJSONObject("zhizi-engine-catalog"));
    state.zhiziCatalogUpdatedAt = json.optLong("zhizi-catalog-updated-at", 0L);
    state.customRemoteCode = json.optString("custom-remote-code", "");
    CredentialStore store = credentialStore();
    state.credentialStoreBackend = store.backendName();
    state.credentialStoreAvailable = store.isAvailable();
    boolean changed = false;
    synchronized (CREDENTIAL_LOCK) {
      boolean sameSession = sameAccount(sessionZhiziIdentifier, state.zhiziIdentifier);
      SecretLoad token =
          loadSecret(
              store,
              CredentialStore.Kind.ACCOUNT_TOKEN,
              state.zhiziIdentifier,
              state.rememberZhiziToken,
              json.optString(LEGACY_TOKEN_KEY, ""),
              sessionZhiziToken,
              sameSession && sessionTokenStoredSecurely);
      SecretLoad password =
          loadSecret(
              store,
              CredentialStore.Kind.PASSWORD,
              state.zhiziIdentifier,
              state.rememberZhiziPassword,
              decodeSavedPassword(json.optString(LEGACY_PASSWORD_KEY, "")),
              sessionZhiziPassword,
              sameSession && sessionPasswordStoredSecurely);
      state.zhiziAccountToken = token.secret;
      state.zhiziPassword = password.secret;
      state.tokenStoredSecurely = token.storedSecurely;
      state.passwordStoredSecurely = password.storedSecurely;
      state.credentialMigrationFailed = token.migrationFailed || password.migrationFailed;
      state.credentialReadFailed = token.readFailed || password.readFailed;
      if (token.migrated) {
        json.remove(LEGACY_TOKEN_KEY);
        changed = true;
      }
      if (password.migrated) {
        json.remove(LEGACY_PASSWORD_KEY);
        changed = true;
      }
      if (!token.secret.isEmpty()) {
        sessionZhiziToken = token.secret;
        sessionTokenStoredSecurely = token.storedSecurely;
        sessionZhiziIdentifier = state.zhiziIdentifier;
      }
      if (!password.secret.isEmpty()) {
        sessionZhiziPassword = password.secret;
        sessionPasswordStoredSecurely = password.storedSecurely;
        sessionZhiziIdentifier = state.zhiziIdentifier;
      }
    }
    if (changed) {
      json.put(CREDENTIAL_BACKEND_KEY, store.backendName());
      saveConfigQuietly();
    }
    return state;
  }

  public static CredentialSaveResult save(State state) {
    if (state == null) {
      return CredentialSaveResult.none("session-only");
    }
    JSONObject previous = currentConfigJson();
    String previousIdentifier = previous.optString("zhizi-identifier", "");
    String identifier = state.zhiziIdentifier == null ? "" : state.zhiziIdentifier.trim();
    CredentialStore store = credentialStore();
    CredentialSaveResult result = new CredentialSaveResult(store.backendName());
    JSONObject json = new JSONObject();
    json.put("provider", emptyToDefault(state.provider, PROVIDER_LOCAL));
    json.put("zhizi-args", emptyToDefault(state.zhiziArgs, DEFAULT_ZHIZI_ARGS));
    json.put("zhizi-identifier", identifier);
    json.put("custom-remote-code", state.customRemoteCode == null ? "" : state.customRemoteCode);
    if (state.zhiziCatalog != null) {
      json.put("zhizi-engine-catalog", state.zhiziCatalog.withDocumentedWeights().toJson());
    }
    json.put("zhizi-catalog-updated-at", Math.max(0L, state.zhiziCatalogUpdatedAt));
    synchronized (CREDENTIAL_LOCK) {
      if (!sameAccount(previousIdentifier, identifier)) {
        result.deletionFailed |= !deleteSecrets(store, previousIdentifier);
        sessionZhiziToken = "";
        sessionZhiziPassword = "";
        sessionTokenStoredSecurely = false;
        sessionPasswordStoredSecurely = false;
      }
      sessionZhiziIdentifier = identifier;
      String token = state.zhiziAccountToken == null ? "" : state.zhiziAccountToken;
      String password = state.zhiziPassword == null ? "" : state.zhiziPassword;
      if (!token.isEmpty()) {
        sessionZhiziToken = token;
      } else if (!state.rememberZhiziToken) {
        sessionZhiziToken = "";
      }
      if (!password.isEmpty()) {
        sessionZhiziPassword = password;
      } else if (!state.rememberZhiziPassword) {
        sessionZhiziPassword = "";
      }

      Persistence tokenPersistence =
          persistSecret(
              store,
              CredentialStore.Kind.ACCOUNT_TOKEN,
              identifier,
              state.rememberZhiziToken,
              sessionZhiziToken,
              previous,
              LEGACY_TOKEN_KEY,
              state.credentialMigrationFailed,
              state.tokenStoredSecurely);
      Persistence passwordPersistence =
          persistSecret(
              store,
              CredentialStore.Kind.PASSWORD,
              identifier,
              state.rememberZhiziPassword,
              sessionZhiziPassword,
              previous,
              LEGACY_PASSWORD_KEY,
              state.credentialMigrationFailed,
              state.passwordStoredSecurely);
      state.rememberZhiziToken = tokenPersistence.remember;
      state.rememberZhiziPassword = passwordPersistence.remember;
      state.tokenStoredSecurely = tokenPersistence.storedSecurely;
      state.passwordStoredSecurely = passwordPersistence.storedSecurely;
      sessionTokenStoredSecurely = tokenPersistence.storedSecurely;
      sessionPasswordStoredSecurely = passwordPersistence.storedSecurely;
      result.tokenRequested = tokenPersistence.requested;
      result.passwordRequested = passwordPersistence.requested;
      result.tokenStored = tokenPersistence.storedSecurely;
      result.passwordStored = passwordPersistence.storedSecurely;
      result.deletionFailed |= tokenPersistence.deletionFailed;
      result.deletionFailed |= passwordPersistence.deletionFailed;
      json.put("remember-zhizi-token", tokenPersistence.remember);
      json.put("remember-zhizi-password", passwordPersistence.remember);
      if (tokenPersistence.preserveLegacy && previous.has(LEGACY_TOKEN_KEY)) {
        json.put(LEGACY_TOKEN_KEY, previous.optString(LEGACY_TOKEN_KEY, ""));
      }
      if (passwordPersistence.preserveLegacy && previous.has(LEGACY_PASSWORD_KEY)) {
        json.put(LEGACY_PASSWORD_KEY, previous.optString(LEGACY_PASSWORD_KEY, ""));
      }
      if (tokenPersistence.storedSecurely || passwordPersistence.storedSecurely) {
        json.put(CREDENTIAL_BACKEND_KEY, store.backendName());
      }
    }
    Lizzie.config.leelazConfig.put(CONFIG_KEY, json);
    saveConfigQuietly();
    return result;
  }

  public static CredentialSaveResult saveZhiziToken(String token, boolean remember, String args) {
    return saveZhiziToken(token, remember, args, "");
  }

  public static CredentialSaveResult saveZhiziToken(
      String token, boolean remember, String args, String identifier) {
    State state = load();
    state.zhiziAccountToken = token == null ? "" : token;
    state.tokenStoredSecurely = false;
    state.zhiziIdentifier = identifier == null ? "" : identifier.trim();
    state.rememberZhiziToken = remember;
    state.zhiziArgs = emptyToDefault(args, DEFAULT_ZHIZI_ARGS);
    return save(state);
  }

  public static CredentialSaveResult saveZhiziToken(
      String token,
      boolean remember,
      String args,
      String identifier,
      String password,
      boolean rememberPassword) {
    State state = load();
    state.zhiziAccountToken = token == null ? "" : token;
    state.tokenStoredSecurely = false;
    state.zhiziIdentifier = identifier == null ? "" : identifier.trim();
    state.rememberZhiziToken = remember;
    state.zhiziPassword = rememberPassword ? (password == null ? "" : password) : "";
    state.passwordStoredSecurely = false;
    state.rememberZhiziPassword = rememberPassword && !state.zhiziPassword.isEmpty();
    state.zhiziArgs = emptyToDefault(args, DEFAULT_ZHIZI_ARGS);
    return save(state);
  }

  public static CredentialSaveResult clearZhiziToken() {
    State state = load();
    state.zhiziAccountToken = "";
    state.zhiziIdentifier = "";
    state.zhiziPassword = "";
    state.rememberZhiziToken = false;
    state.rememberZhiziPassword = false;
    sessionZhiziToken = "";
    sessionZhiziPassword = "";
    sessionTokenStoredSecurely = false;
    sessionPasswordStoredSecurely = false;
    return save(state);
  }

  /** Invalidates an expired account token without discarding the selected plan or saved account. */
  public static CredentialSaveResult invalidateZhiziToken() {
    State state = load();
    state.zhiziAccountToken = "";
    state.rememberZhiziToken = false;
    synchronized (CREDENTIAL_LOCK) {
      sessionZhiziToken = "";
      sessionTokenStoredSecurely = false;
    }
    return save(state);
  }

  public static void saveZhiziCatalog(ZhiziEngineCatalog catalog) {
    if (catalog == null) {
      return;
    }
    State state = load();
    state.zhiziCatalog = catalog.withDocumentedWeights();
    state.zhiziCatalogUpdatedAt = System.currentTimeMillis();
    save(state);
  }

  public static boolean shouldRefreshZhiziCatalog(State state, long nowMillis) {
    if (state == null || state.zhiziCatalogUpdatedAt <= 0L) {
      return true;
    }
    return nowMillis - state.zhiziCatalogUpdatedAt >= ZHIZI_CATALOG_REFRESH_INTERVAL_MILLIS;
  }

  public static boolean isZhiziEngineCommand(String command) {
    return command != null && command.trim().startsWith(COMMAND_ZHIZI);
  }

  public static boolean isCustomWebSocketEngineCommand(String command) {
    return command != null && command.trim().startsWith(COMMAND_CUSTOM_WS);
  }

  public static boolean isRemoteComputeEngineCommand(String command) {
    return isZhiziEngineCommand(command) || isCustomWebSocketEngineCommand(command);
  }

  /**
   * Avoids a dead remote engine on startup when the user deliberately did not persist a Zhizi
   * login. The fallback is session-only: saved provider and default/last-engine choices stay
   * untouched so signing in can restore the remote engine immediately.
   */
  public static StartupSelection resolveStartupSelection(int requestedIndex, boolean loadDefault) {
    ArrayList<EngineData> engines = Utils.getEngineData();
    int selectedIndex = requestedIndex;
    if (selectedIndex < 0 && loadDefault) {
      for (int i = 0; i < engines.size(); i++) {
        EngineData engine = engines.get(i);
        if (engine != null && engine.isDefault) {
          selectedIndex = i;
          break;
        }
      }
    }
    if (selectedIndex < 0 || selectedIndex >= engines.size()) {
      return new StartupSelection(requestedIndex, loadDefault);
    }

    EngineData selected = engines.get(selectedIndex);
    if (selected == null
        || !isZhiziEngineCommand(selected.commands)
        || !load().zhiziAccountToken.isBlank()) {
      return new StartupSelection(requestedIndex, loadDefault);
    }

    int localIndex = firstLocalEngineIndex(engines);
    return localIndex >= 0
        ? new StartupSelection(localIndex, false)
        : new StartupSelection(requestedIndex, loadDefault);
  }

  public static String compactDisplayNameForCommand(String command, String fallback) {
    if (isZhiziEngineCommand(command)) {
      return localizedText("RemoteCompute.zhizi", "Zhizi Cloud");
    }
    if (isCustomWebSocketEngineCommand(command)) {
      return localizedText("RemoteCompute.custom", "Custom Compute");
    }
    return fallback == null ? "" : fallback.trim();
  }

  public static EngineTransport createTransportForCommand(String command) throws IOException {
    if (isZhiziEngineCommand(command)) {
      return ZhiziGtpTransport.fromSavedConfig();
    }
    if (isCustomWebSocketEngineCommand(command)) {
      return KataGoAnalysisWebSocketTransport.fromSavedConfig();
    }
    throw new IOException(
        localizedText("RemoteCompute.error.unknownEngine", "Unknown remote compute engine."));
  }

  public static int createOrUpdateZhiziEngine(boolean setDefault) {
    ArrayList<EngineData> engines = Utils.getEngineData();
    int index = -1;
    for (int i = 0; i < engines.size(); i++) {
      if (isZhiziEngineCommand(engines.get(i).commands)) {
        index = i;
        break;
      }
    }
    EngineData data;
    if (index >= 0) {
      data = engines.get(index);
    } else {
      data = new EngineData();
      index = engines.size();
      engines.add(data);
    }
    State state = load();
    data.index = index;
    data.commands = COMMAND_ZHIZI;
    data.name = displayNameForZhiziArgs(state.zhiziArgs);
    data.preload = false;
    data.width = Board.boardWidth > 0 ? Board.boardWidth : 19;
    data.height = Board.boardHeight > 0 ? Board.boardHeight : 19;
    data.komi = 7.5F;
    data.useJavaSSH = false;
    data.initialCommand = "";
    if (setDefault) {
      for (EngineData engine : engines) {
        engine.isDefault = false;
      }
      data.isDefault = true;
      rememberLastEngine(index);
    }
    Utils.saveEngineSettings(engines);
    refreshEngineCatalogQuietly();
    return index;
  }

  public static int createOrUpdateCustomWebSocketEngine(boolean setDefault) {
    State state = load();
    String remoteUrl = normalizeCustomWebSocketUrl(state.customRemoteCode);
    ArrayList<EngineData> engines = Utils.getEngineData();
    int index = -1;
    for (int i = 0; i < engines.size(); i++) {
      if (isCustomWebSocketEngineCommand(engines.get(i).commands)) {
        index = i;
        break;
      }
    }
    EngineData data;
    if (index >= 0) {
      data = engines.get(index);
    } else {
      data = new EngineData();
      index = engines.size();
      engines.add(data);
    }
    data.index = index;
    data.commands = COMMAND_CUSTOM_WS;
    data.name = displayNameForCustomWebSocketUrl(remoteUrl);
    data.preload = false;
    data.width = Board.boardWidth > 0 ? Board.boardWidth : 19;
    data.height = Board.boardHeight > 0 ? Board.boardHeight : 19;
    data.komi = 7.5F;
    data.useJavaSSH = false;
    data.initialCommand = "";
    if (setDefault) {
      for (EngineData engine : engines) {
        engine.isDefault = false;
      }
      data.isDefault = true;
      rememberLastEngine(index);
    }
    Utils.saveEngineSettings(engines);
    refreshEngineCatalogQuietly();
    return index;
  }

  public static int saveLocalProviderAndDefaultEngine() {
    State state = load();
    state.provider = PROVIDER_LOCAL;
    save(state);

    ArrayList<EngineData> engines = Utils.getEngineData();
    int localIndex = firstLocalEngineIndex(engines);
    for (int i = 0; i < engines.size(); i++) {
      EngineData engine = engines.get(i);
      if (engine != null) {
        engine.isDefault = i == localIndex;
      }
    }
    rememberLastEngine(localIndex);
    Utils.saveEngineSettings(engines);
    refreshEngineCatalogQuietly();
    return localIndex;
  }

  public static int firstLocalEngineIndex(ArrayList<EngineData> engines) {
    if (engines == null) {
      return -1;
    }
    for (int i = 0; i < engines.size(); i++) {
      EngineData engine = engines.get(i);
      if (engine == null || engine.commands == null || engine.commands.trim().isEmpty()) {
        continue;
      }
      if (!isRemoteComputeEngineCommand(engine.commands)) {
        return i;
      }
    }
    return -1;
  }

  public static String displayNameForZhiziArgs(String args) {
    String normalized = args == null ? "" : args;
    String model = displayNameForZhiziWeight(kataWeightForArgs(normalized));
    String backend = normalized.contains("katago-CUDA") ? "CUDA" : "TensorRT";
    String gpuType = gpuTypeForArgs(normalized);
    String billing = localizedPlanName(gpuType, backend);
    return localizedText("RemoteCompute.zhizi", "Zhizi Cloud")
        + " "
        + billing
        + " · "
        + model
        + " · "
        + backend;
  }

  public static String gpuTypeForArgs(String args) {
    return optionValueForArgs(args, "--gpu-type", "vip-share");
  }

  public static String kataWeightForArgs(String args) {
    return optionValueForArgs(args, "--kata-weight", "28bnbt");
  }

  public static String withKataWeight(String args, String weight) {
    String safeWeight = ZhiziEngineCatalog.isSelectableWeight(weight) ? weight.trim() : "28bnbt";
    return withOptionValue(
        args == null || args.trim().isEmpty() ? DEFAULT_ZHIZI_ARGS : args,
        "--kata-weight",
        safeWeight);
  }

  public static boolean sameZhiziPlan(String first, String second) {
    return withoutOption(first, "--kata-weight").equals(withoutOption(second, "--kata-weight"));
  }

  public static String displayNameForZhiziWeight(String weight) {
    String normalized = weight == null ? "" : weight.trim();
    if ("28bnbt".equalsIgnoreCase(normalized)) {
      return "28B NBT";
    }
    if ("18bnbt".equalsIgnoreCase(normalized)) {
      return "18B NBT";
    }
    if ("fdx".equalsIgnoreCase(normalized)) {
      return "FDX · 40B NBT";
    }
    if ("10b384t".equalsIgnoreCase(normalized)) {
      return localizedText("RemoteCompute.weightName.10b384t", "Transformer 10B - Lightweight");
    }
    if ("10b512t".equalsIgnoreCase(normalized)) {
      return localizedText("RemoteCompute.weightName.10b512t", "Transformer 10B - Balanced");
    }
    if ("11b768t".equalsIgnoreCase(normalized)) {
      return localizedText("RemoteCompute.weightName.11b768t", "Transformer 11B - Flagship");
    }
    if (normalized.matches("(?i)\\d+b")) {
      return normalized.toUpperCase();
    }
    return normalized.isEmpty() ? "28B NBT" : normalized;
  }

  public static String hintForZhiziWeight(String weight) {
    String normalized = weight == null ? "" : weight.trim();
    if ("fdx".equalsIgnoreCase(normalized)) {
      return localizedText("RemoteCompute.weightHint.fdx", "Extra-large network");
    }
    if ("20b".equalsIgnoreCase(normalized)) {
      return localizedText("RemoteCompute.weightHint.20b", "Commonly used for handicap games");
    }
    return "";
  }

  public static boolean isCustomWebSocketUrl(String value) {
    String normalized = normalizeCustomWebSocketUrl(value);
    if (normalized.isEmpty()) {
      return false;
    }
    try {
      URI uri = URI.create(normalized);
      String scheme = uri.getScheme();
      return ("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme))
          && uri.getHost() != null
          && !uri.getHost().isBlank();
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  public static String normalizeCustomWebSocketUrl(String value) {
    if (value == null) {
      return "";
    }
    String text = normalizeAsciiLikeInput(value.trim());
    if ((text.startsWith("\"") && text.endsWith("\""))
        || (text.startsWith("'") && text.endsWith("'"))) {
      text = text.substring(1, text.length() - 1).trim();
    }
    if (text.startsWith("完善://")) {
      text = "ws://" + text.substring("完善://".length());
    }
    int wsIndex = indexOfWebSocketScheme(text);
    if (wsIndex > 0) {
      text = text.substring(wsIndex);
    }
    if (wsIndex >= 0) {
      int end = text.length();
      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        if (Character.isWhitespace(c) || c == '"' || c == '\'' || c == '<' || c == '>') {
          end = i;
          break;
        }
      }
      text = text.substring(0, end);
    }
    return text.trim();
  }

  private static String normalizeAsciiLikeInput(String value) {
    StringBuilder normalized = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\u3000') {
        normalized.append(' ');
      } else if (c >= '\uFF01' && c <= '\uFF5E') {
        normalized.append((char) (c - 0xFEE0));
      } else {
        normalized.append(c);
      }
    }
    return normalized.toString();
  }

  public static String displayNameForCustomWebSocketUrl(String url) {
    String normalized = normalizeCustomWebSocketUrl(url);
    String customCompute = localizedText("RemoteCompute.custom", "Custom Compute");
    if (normalized.isEmpty()) {
      return customCompute;
    }
    try {
      URI uri = URI.create(normalized);
      String host = uri.getHost();
      if (host == null || host.isBlank()) {
        return customCompute;
      }
      StringBuilder label = new StringBuilder(customCompute).append(" · ").append(host);
      if (uri.getPort() > 0) {
        label.append(':').append(uri.getPort());
      }
      return label.toString();
    } catch (IllegalArgumentException e) {
      return customCompute;
    }
  }

  public static String friendlyZhiziErrorMessage(String message, String args) {
    String text =
        message == null || message.trim().isEmpty()
            ? localizedText(
                "RemoteCompute.error.genericConnection", "Remote compute connection failed.")
            : message.trim();
    if (text.startsWith("{") || text.contains("\"statusCode\"") || text.contains("\"key\"")) {
      text =
          localizedText(
              "RemoteCompute.error.genericConnection", "Remote compute connection failed.");
    }
    if (!isVipShareArgs(args) || !looksLikeVipAccessProblem(text)) {
      return text;
    }
    return text
        + "\n\n"
        + localizedText(
            "RemoteCompute.error.vipHint",
            "This account may not have VIP monthly access, or no VIP worker is available. "
                + "Switch to On-demand 1x in advanced settings, or check the Zhizi plan.");
  }

  public static String friendlyZhiziErrorMessage(Throwable error, String args) {
    ZhiziApiException apiError = findZhiziApiException(error);
    if (apiError == null) {
      String message = error == null ? "" : error.getLocalizedMessage();
      return friendlyZhiziErrorMessage(message, args);
    }

    String key = apiError.errorKey();
    String message;
    switch (key) {
      case "invalid_phone":
      case "invalid_email":
        message =
            localizedText(
                "RemoteCompute.error.invalidAccount",
                "Enter a valid phone number or email address.");
        break;
      case "invalid_password":
      case "invalid_credentials":
        message =
            localizedText(
                "RemoteCompute.error.invalidCredentials", "The account or password is incorrect.");
        break;
      case "invalid_verification_code":
        message =
            localizedText(
                "RemoteCompute.error.invalidVerificationCode",
                "The verification code is incorrect or has expired.");
        break;
      case "fast_login_too_frequent":
        message =
            localizedText(
                "RemoteCompute.error.codeTooFrequent",
                "Verification requests are too frequent. Please wait before trying again.");
        break;
      case "password_too_short":
        message =
            localizedText(
                "RemoteCompute.error.passwordTooShort",
                "The new password must contain at least 8 characters.");
        break;
      case "duplicate_phone":
      case "duplicate_email":
        message =
            localizedText(
                "RemoteCompute.error.accountAlreadyExists",
                "This account already exists. Sign in instead.");
        break;
      case "not_found":
        message =
            localizedText(
                "RemoteCompute.error.accountNotFound",
                "This account was not found. Check the phone number or email.");
        break;
      case "unauthorized":
        message =
            localizedText(
                "RemoteCompute.error.sessionExpired",
                "The Zhizi login has expired. Sign in again to continue.");
        break;
      case "send_code_error":
        message =
            localizedText(
                "RemoteCompute.error.codeSendTemporary",
                "The verification code could not be sent right now. Please try again later.");
        break;
      case "failed_to_insert_socket_io_token":
        message =
            localizedText(
                "RemoteCompute.error.workerUnavailable",
                "Zhizi could not allocate a compute session. Check the plan and try again later.");
        break;
      case "failed_aggerate":
      case "count_usage_error":
      case "list_usages_error":
      case "count_credits_error":
      case "list_credits_error":
        message =
            localizedText(
                "RemoteCompute.error.billingUnavailable",
                "Zhizi account usage is temporarily unavailable. Try again later.");
        break;
      case "invalid_pay_type":
      case "invalid_amount":
      case "invalid_top_up_amount":
      case "invalid_product":
      case "missing_product_name":
      case "invalid_product_name":
      case "failed_create_prepay_id":
      case "failed_insert_order":
      case "order_not_found":
        message =
            localizedText(
                "RemoteCompute.error.paymentUnavailable",
                "The Zhizi payment request could not be completed. Check it in the Zhizi app.");
        break;
      case "network_error":
        message =
            localizedText(
                "RemoteCompute.error.network",
                "The Zhizi connection was interrupted. Check the network or proxy and try again.");
        break;
      case "invalid_response":
        message =
            localizedText(
                "RemoteCompute.error.invalidResponse",
                "Zhizi returned an incomplete response. Please try again later.");
        break;
      default:
        message =
            apiError.operation() == ZhiziApiException.Operation.SEND_CODE
                ? localizedText(
                    "RemoteCompute.error.codeSendTemporary",
                    "The verification code could not be sent right now. Please try again later.")
                : localizedText(
                    "RemoteCompute.error.genericConnection", "Remote compute connection failed.");
        break;
    }
    if (!apiError.requestId().isEmpty()) {
      message +=
          "\n"
              + java.text.MessageFormat.format(
                  localizedText("RemoteCompute.error.requestId", "Request ID: {0}"),
                  apiError.requestId());
    }
    return friendlyZhiziErrorMessage(message, args);
  }

  private static ZhiziApiException findZhiziApiException(Throwable error) {
    Throwable current = error;
    for (int depth = 0; current != null && depth < 8; depth++) {
      if (current instanceof ZhiziApiException) {
        return (ZhiziApiException) current;
      }
      current = current.getCause();
    }
    return null;
  }

  static String localizedText(String key, String fallback) {
    try {
      if (Lizzie.resourceBundle != null && Lizzie.resourceBundle.containsKey(key)) {
        String value = Lizzie.resourceBundle.getString(key);
        if (value != null && !value.isBlank()) {
          return value;
        }
      }
    } catch (RuntimeException ignored) {
      // Resource loading must never prevent a remote engine from reporting its status.
    }
    return fallback;
  }

  private static String localizedPlanName(String gpuType, String backend) {
    if ("vip-share".equals(gpuType)) {
      return localizedText("RemoteCompute.plan.vipShort", "VIP monthly");
    }
    if ("1x".equals(gpuType)) {
      return "CUDA".equals(backend)
          ? localizedText("RemoteCompute.plan.1xCuda", "On-demand 1x - CUDA")
          : localizedText("RemoteCompute.plan.1x", "On-demand 1x");
    }
    if ("3x".equals(gpuType)) {
      return localizedText("RemoteCompute.plan.3x", "On-demand 3x");
    }
    if ("6x".equals(gpuType)) {
      return localizedText("RemoteCompute.plan.6x", "On-demand 6x");
    }
    if ("12x".equals(gpuType)) {
      return localizedText("RemoteCompute.plan.12x", "On-demand 12x");
    }
    if ("24x".equals(gpuType)) {
      return localizedText("RemoteCompute.plan.24x", "On-demand 24x");
    }
    return gpuType == null || gpuType.isBlank() ? "On-demand" : "On-demand " + gpuType;
  }

  public static boolean isVipShareArgs(String args) {
    return "vip-share".equals(gpuTypeForArgs(args));
  }

  private static boolean looksLikeVipAccessProblem(String message) {
    String lower = message.toLowerCase();
    return lower.contains("worker")
        || lower.contains("vip")
        || lower.contains("quota")
        || lower.contains("permission")
        || lower.contains("forbidden")
        || lower.contains("unauthorized")
        || lower.contains("403")
        || message.contains("无权限")
        || message.contains("未开通")
        || message.contains("额度")
        || message.contains("余额");
  }

  private static String emptyToDefault(String value, String fallback) {
    return value == null || value.trim().isEmpty() ? fallback : value.trim();
  }

  private static ZhiziEngineCatalog loadZhiziCatalog(JSONObject json) {
    if (json != null) {
      try {
        return ZhiziEngineCatalog.fromJson(json).withDocumentedWeights();
      } catch (IOException ignored) {
        // A corrupt cache must never prevent the remote-compute dialog from opening.
      }
    }
    return ZhiziEngineCatalog.fallback();
  }

  private static String optionValueForArgs(String args, String option, String fallback) {
    if (args == null || args.trim().isEmpty()) {
      return fallback;
    }
    String[] parts = args.trim().split("\\s+");
    for (int i = 0; i < parts.length - 1; i++) {
      if (option.equals(parts[i])) {
        return parts[i + 1].trim();
      }
    }
    return fallback;
  }

  private static String withOptionValue(String args, String option, String value) {
    String[] parts = args.trim().split("\\s+");
    StringBuilder result = new StringBuilder();
    boolean replaced = false;
    for (int i = 0; i < parts.length; i++) {
      appendArgument(result, parts[i]);
      if (option.equals(parts[i])) {
        if (i + 1 < parts.length) {
          i++;
        }
        appendArgument(result, value);
        replaced = true;
      }
    }
    if (!replaced) {
      appendArgument(result, option);
      appendArgument(result, value);
    }
    return result.toString();
  }

  private static String withoutOption(String args, String option) {
    if (args == null || args.trim().isEmpty()) {
      return "";
    }
    String[] parts = args.trim().split("\\s+");
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      if (option.equals(parts[i])) {
        if (i + 1 < parts.length) {
          i++;
        }
        continue;
      }
      appendArgument(result, parts[i]);
    }
    return result.toString();
  }

  private static void appendArgument(StringBuilder result, String argument) {
    if (result.length() > 0) {
      result.append(' ');
    }
    result.append(argument);
  }

  private static int indexOfWebSocketScheme(String text) {
    String lower = text.toLowerCase();
    int ws = lower.indexOf("ws://");
    int wss = lower.indexOf("wss://");
    if (ws < 0) {
      return wss;
    }
    if (wss < 0) {
      return ws;
    }
    return Math.min(ws, wss);
  }

  private static String decodeSavedPassword(String encoded) {
    if (encoded == null || encoded.trim().isEmpty()) {
      return "";
    }
    try {
      return new String(Base64.getDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  private static void rememberLastEngine(int index) {
    if (Lizzie.config != null && Lizzie.config.uiConfig != null) {
      Lizzie.config.uiConfig.put("last-engine", index);
    }
  }

  private static void refreshEngineCatalogQuietly() {
    if (Lizzie.engineManager == null) {
      return;
    }
    try {
      Lizzie.engineManager.refreshEngineCatalog();
    } catch (Exception ignored) {
    }
  }

  private static void saveConfigQuietly() {
    try {
      Lizzie.config.save();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static JSONObject currentConfigJson() {
    if (Lizzie.config == null || Lizzie.config.leelazConfig == null) {
      return new JSONObject();
    }
    JSONObject json = Lizzie.config.leelazConfig.optJSONObject(CONFIG_KEY);
    return json == null ? new JSONObject() : json;
  }

  private static CredentialStore credentialStore() {
    CredentialStore override = credentialStoreOverride;
    if (override != null) {
      return override;
    }
    Path directory = credentialDirectory();
    CredentialStore cached = systemCredentialStore;
    if (cached != null && directory.equals(systemCredentialDirectory)) {
      return cached;
    }
    synchronized (CREDENTIAL_LOCK) {
      if (systemCredentialStore == null || !directory.equals(systemCredentialDirectory)) {
        systemCredentialDirectory = directory;
        systemCredentialStore = PlatformCredentialStore.create(directory);
      }
      return systemCredentialStore;
    }
  }

  private static Path credentialDirectory() {
    if (Lizzie.config != null && Lizzie.config.getWorkDirectory() != null) {
      return Lizzie.config.getWorkDirectory().toPath().resolve("secure-credentials").normalize();
    }
    return Path.of(System.getProperty("user.dir", "."), "secure-credentials")
        .toAbsolutePath()
        .normalize();
  }

  private static SecretLoad loadSecret(
      CredentialStore store,
      CredentialStore.Kind kind,
      String identifier,
      boolean remember,
      String legacySecret,
      String sessionSecret,
      boolean sessionStoredSecurely) {
    if (sameAccount(sessionZhiziIdentifier, identifier)
        && sessionSecret != null
        && !sessionSecret.isEmpty()) {
      return SecretLoad.session(sessionSecret, sessionStoredSecurely);
    }
    if (!remember) {
      return SecretLoad.empty();
    }
    boolean readFailed = false;
    if (store.isAvailable() && identifier != null && !identifier.isBlank()) {
      try {
        Optional<String> stored = store.read(kind, identifier);
        if (stored.isPresent()) {
          return SecretLoad.stored(stored.get());
        }
      } catch (IOException e) {
        readFailed = true;
      }
    }
    if (legacySecret == null || legacySecret.isEmpty()) {
      return readFailed ? SecretLoad.readFailed() : SecretLoad.empty();
    }
    if (!store.isAvailable() || identifier == null || identifier.isBlank()) {
      return SecretLoad.migrationFailed(readFailed);
    }
    try {
      store.write(kind, identifier, legacySecret);
      return SecretLoad.migrated(legacySecret);
    } catch (IOException e) {
      return SecretLoad.migrationFailed(readFailed);
    }
  }

  private static Persistence persistSecret(
      CredentialStore store,
      CredentialStore.Kind kind,
      String identifier,
      boolean remember,
      String secret,
      JSONObject previous,
      String legacyKey,
      boolean preserveFailedMigration,
      boolean alreadyStoredSecurely) {
    String safeSecret = secret == null ? "" : secret;
    boolean requested = remember && !safeSecret.isEmpty();
    if (requested && alreadyStoredSecurely) {
      return Persistence.stored();
    }
    if (requested && store.isAvailable() && identifier != null && !identifier.isBlank()) {
      try {
        store.write(kind, identifier, safeSecret);
        return Persistence.stored();
      } catch (IOException ignored) {
        return Persistence.sessionOnly(previous.has(legacyKey) && preserveFailedMigration, true);
      }
    }
    if (requested) {
      return Persistence.sessionOnly(previous.has(legacyKey) && preserveFailedMigration, true);
    }
    if (remember && safeSecret.isEmpty() && previous.has(legacyKey) && preserveFailedMigration) {
      return Persistence.preserveLegacy();
    }
    boolean deletionFailed = false;
    if (identifier != null && !identifier.isBlank()) {
      try {
        store.delete(kind, identifier);
      } catch (IOException e) {
        deletionFailed = true;
      }
    }
    return Persistence.notRemembered(deletionFailed);
  }

  private static boolean deleteSecrets(CredentialStore store, String identifier) {
    if (identifier == null || identifier.isBlank()) {
      return true;
    }
    boolean deleted = true;
    for (CredentialStore.Kind kind :
        List.of(CredentialStore.Kind.ACCOUNT_TOKEN, CredentialStore.Kind.PASSWORD)) {
      try {
        store.delete(kind, identifier);
      } catch (IOException e) {
        deleted = false;
      }
    }
    return deleted;
  }

  private static boolean sameAccount(String first, String second) {
    String left = first == null ? "" : first.trim();
    String right = second == null ? "" : second.trim();
    return left.equalsIgnoreCase(right);
  }

  static void setCredentialStoreForTests(CredentialStore store) {
    synchronized (CREDENTIAL_LOCK) {
      credentialStoreOverride = store;
      resetSessionCredentials();
    }
  }

  static void resetCredentialStateForTests() {
    synchronized (CREDENTIAL_LOCK) {
      credentialStoreOverride = null;
      systemCredentialStore = null;
      systemCredentialDirectory = null;
      resetSessionCredentials();
    }
  }

  private static void resetSessionCredentials() {
    sessionZhiziToken = "";
    sessionZhiziPassword = "";
    sessionZhiziIdentifier = "";
    sessionTokenStoredSecurely = false;
    sessionPasswordStoredSecurely = false;
  }

  public static final class State {
    public String provider = PROVIDER_LOCAL;
    public String zhiziAccountToken = "";
    public String zhiziIdentifier = "";
    public boolean rememberZhiziToken;
    public String zhiziPassword = "";
    public boolean rememberZhiziPassword;
    public String zhiziArgs = DEFAULT_ZHIZI_ARGS;
    public ZhiziEngineCatalog zhiziCatalog = ZhiziEngineCatalog.fallback();
    public long zhiziCatalogUpdatedAt;
    public String customRemoteCode = "";
    public String credentialStoreBackend = "session-only";
    public boolean credentialStoreAvailable;
    public boolean tokenStoredSecurely;
    public boolean passwordStoredSecurely;
    public boolean credentialMigrationFailed;
    public boolean credentialReadFailed;
  }

  public static final class CredentialSaveResult {
    public final String backend;
    public boolean tokenRequested;
    public boolean passwordRequested;
    public boolean tokenStored;
    public boolean passwordStored;
    public boolean deletionFailed;

    private CredentialSaveResult(String backend) {
      this.backend = backend == null || backend.isBlank() ? "session-only" : backend;
    }

    private static CredentialSaveResult none(String backend) {
      return new CredentialSaveResult(backend);
    }

    public boolean isSessionOnly() {
      return (tokenRequested && !tokenStored) || (passwordRequested && !passwordStored);
    }
  }

  private static final class SecretLoad {
    final String secret;
    final boolean storedSecurely;
    final boolean migrated;
    final boolean migrationFailed;
    final boolean readFailed;

    private SecretLoad(
        String secret,
        boolean storedSecurely,
        boolean migrated,
        boolean migrationFailed,
        boolean readFailed) {
      this.secret = secret == null ? "" : secret;
      this.storedSecurely = storedSecurely;
      this.migrated = migrated;
      this.migrationFailed = migrationFailed;
      this.readFailed = readFailed;
    }

    static SecretLoad empty() {
      return new SecretLoad("", false, false, false, false);
    }

    static SecretLoad session(String secret, boolean storedSecurely) {
      return new SecretLoad(secret, storedSecurely, false, false, false);
    }

    static SecretLoad stored(String secret) {
      return new SecretLoad(secret, true, false, false, false);
    }

    static SecretLoad migrated(String secret) {
      return new SecretLoad(secret, true, true, false, false);
    }

    static SecretLoad migrationFailed(boolean readFailed) {
      return new SecretLoad("", false, false, true, readFailed);
    }

    static SecretLoad readFailed() {
      return new SecretLoad("", false, false, false, true);
    }
  }

  private static final class Persistence {
    final boolean remember;
    final boolean requested;
    final boolean storedSecurely;
    final boolean preserveLegacy;
    final boolean deletionFailed;

    private Persistence(
        boolean remember,
        boolean requested,
        boolean storedSecurely,
        boolean preserveLegacy,
        boolean deletionFailed) {
      this.remember = remember;
      this.requested = requested;
      this.storedSecurely = storedSecurely;
      this.preserveLegacy = preserveLegacy;
      this.deletionFailed = deletionFailed;
    }

    static Persistence stored() {
      return new Persistence(true, true, true, false, false);
    }

    static Persistence sessionOnly(boolean preserveLegacy, boolean requested) {
      return new Persistence(false, requested, false, preserveLegacy, false);
    }

    static Persistence preserveLegacy() {
      return new Persistence(true, false, false, true, false);
    }

    static Persistence notRemembered(boolean deletionFailed) {
      return new Persistence(false, false, false, false, deletionFailed);
    }
  }

  public static final class StartupSelection {
    public final int engineIndex;
    public final boolean loadDefault;

    private StartupSelection(int engineIndex, boolean loadDefault) {
      this.engineIndex = engineIndex;
      this.loadDefault = loadDefault;
    }
  }
}
