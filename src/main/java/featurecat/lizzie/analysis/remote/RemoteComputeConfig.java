package featurecat.lizzie.analysis.remote;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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

  private static volatile String sessionZhiziToken = "";

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
    state.zhiziAccountToken = json.optString("zhizi-account-token", "");
    state.zhiziIdentifier = json.optString("zhizi-identifier", "");
    state.rememberZhiziToken = json.optBoolean("remember-zhizi-token", false);
    state.rememberZhiziPassword = json.optBoolean("remember-zhizi-password", false);
    state.zhiziPassword =
        state.rememberZhiziPassword
            ? decodeSavedPassword(json.optString("zhizi-password-v1", ""))
            : "";
    state.zhiziArgs = json.optString("zhizi-args", DEFAULT_ZHIZI_ARGS);
    state.customRemoteCode = json.optString("custom-remote-code", "");
    if (state.zhiziAccountToken.isEmpty() && !sessionZhiziToken.isEmpty()) {
      state.zhiziAccountToken = sessionZhiziToken;
    }
    return state;
  }

  public static void save(State state) {
    JSONObject json = new JSONObject();
    json.put("provider", emptyToDefault(state.provider, PROVIDER_LOCAL));
    json.put("zhizi-args", emptyToDefault(state.zhiziArgs, DEFAULT_ZHIZI_ARGS));
    json.put("zhizi-identifier", state.zhiziIdentifier == null ? "" : state.zhiziIdentifier.trim());
    json.put("remember-zhizi-token", state.rememberZhiziToken);
    json.put("remember-zhizi-password", state.rememberZhiziPassword);
    json.put("custom-remote-code", state.customRemoteCode == null ? "" : state.customRemoteCode);
    String savedPassword = state.zhiziPassword == null ? "" : state.zhiziPassword;
    if (state.rememberZhiziPassword && !savedPassword.isEmpty()) {
      json.put("zhizi-password-v1", encodeSavedPassword(savedPassword));
    }
    sessionZhiziToken = state.zhiziAccountToken == null ? "" : state.zhiziAccountToken;
    if (state.rememberZhiziToken && !sessionZhiziToken.isEmpty()) {
      json.put("zhizi-account-token", sessionZhiziToken);
    }
    Lizzie.config.leelazConfig.put(CONFIG_KEY, json);
    saveConfigQuietly();
  }

  public static void saveZhiziToken(String token, boolean remember, String args) {
    saveZhiziToken(token, remember, args, "");
  }

  public static void saveZhiziToken(
      String token, boolean remember, String args, String identifier) {
    State state = load();
    state.zhiziAccountToken = token == null ? "" : token;
    state.zhiziIdentifier = identifier == null ? "" : identifier.trim();
    state.rememberZhiziToken = remember;
    state.zhiziArgs = emptyToDefault(args, DEFAULT_ZHIZI_ARGS);
    save(state);
  }

  public static void saveZhiziToken(
      String token,
      boolean remember,
      String args,
      String identifier,
      String password,
      boolean rememberPassword) {
    State state = load();
    state.zhiziAccountToken = token == null ? "" : token;
    state.zhiziIdentifier = identifier == null ? "" : identifier.trim();
    state.rememberZhiziToken = remember;
    state.zhiziPassword = rememberPassword ? (password == null ? "" : password) : "";
    state.rememberZhiziPassword = rememberPassword && !state.zhiziPassword.isEmpty();
    state.zhiziArgs = emptyToDefault(args, DEFAULT_ZHIZI_ARGS);
    save(state);
  }

  public static void clearZhiziToken() {
    State state = load();
    state.zhiziAccountToken = "";
    state.zhiziIdentifier = "";
    state.zhiziPassword = "";
    state.rememberZhiziToken = false;
    state.rememberZhiziPassword = false;
    sessionZhiziToken = "";
    save(state);
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
    String model =
        normalized.contains("18bnbt")
            ? "Zhizi 18B"
            : normalized.contains("fdx") ? "FDX" : "Zhizi 28B";
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
    if (args == null || args.trim().isEmpty()) {
      return "vip-share";
    }
    String[] parts = args.trim().split("\\s+");
    for (int i = 0; i < parts.length - 1; i++) {
      if ("--gpu-type".equals(parts[i])) {
        return parts[i + 1].trim();
      }
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

  private static String encodeSavedPassword(String password) {
    return Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8));
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

  public static final class State {
    public String provider = PROVIDER_LOCAL;
    public String zhiziAccountToken = "";
    public String zhiziIdentifier = "";
    public boolean rememberZhiziToken;
    public String zhiziPassword = "";
    public boolean rememberZhiziPassword;
    public String zhiziArgs = DEFAULT_ZHIZI_ARGS;
    public String customRemoteCode = "";
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
