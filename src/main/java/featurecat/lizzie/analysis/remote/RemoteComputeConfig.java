package featurecat.lizzie.analysis.remote;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.util.Utils;
import java.io.IOException;
import java.util.ArrayList;
import org.json.JSONObject;

public final class RemoteComputeConfig {
  public static final String CONFIG_KEY = "remote-compute";
  public static final String COMMAND_ZHIZI = "remote-compute://zhizi";
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
    json.put("custom-remote-code", state.customRemoteCode == null ? "" : state.customRemoteCode);
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
    state.provider = PROVIDER_ZHIZI;
    state.zhiziAccountToken = token == null ? "" : token;
    state.zhiziIdentifier = identifier == null ? "" : identifier.trim();
    state.rememberZhiziToken = remember;
    state.zhiziArgs = emptyToDefault(args, DEFAULT_ZHIZI_ARGS);
    save(state);
  }

  public static void clearZhiziToken() {
    State state = load();
    state.zhiziAccountToken = "";
    state.zhiziIdentifier = "";
    state.rememberZhiziToken = false;
    sessionZhiziToken = "";
    save(state);
  }

  public static boolean isZhiziEngineCommand(String command) {
    return command != null && command.trim().startsWith(COMMAND_ZHIZI);
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
    }
    Utils.saveEngineSettings(engines);
    refreshEngineCatalogQuietly();
    return index;
  }

  public static String displayNameForZhiziArgs(String args) {
    String normalized = args == null ? "" : args;
    String model =
        normalized.contains("18bnbt") ? "智子18B" : normalized.contains("fdx") ? "FDX" : "智子28B";
    String backend = normalized.contains("katago-CUDA") ? "CUDA" : "TensorRT";
    String gpuType = gpuTypeForArgs(normalized);
    String billing =
        gpuType.isEmpty() ? "按量" : "vip-share".equals(gpuType) ? "VIP包月" : "按量" + gpuType;
    return "智子云算力 " + billing + " · " + model + " · " + backend;
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

  public static String friendlyZhiziErrorMessage(String message, String args) {
    String text = message == null || message.trim().isEmpty() ? "远程算力连接失败。" : message.trim();
    if (!isVipShareArgs(args) || !looksLikeVipAccessProblem(text)) {
      return text;
    }
    return text
        + "\n\n当前账号可能未开通 VIP 包月，或 VIP 算力暂时没有可用 worker。"
        + "请在高级设置切换到“按量 1x”，或检查智子账号套餐。";
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
    public String zhiziArgs = DEFAULT_ZHIZI_ARGS;
    public String customRemoteCode = "";
  }
}
