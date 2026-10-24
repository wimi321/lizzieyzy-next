package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.logging.LogCategories;
import featurecat.lizzie.util.AnalysisEngineCommandHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * When the automatic analysis of a loaded game runs on the main engine instead of an analysis
 * engine of its own: for remote and bundled NVIDIA main engines, by the user's choice, and when the
 * analysis engine in the settings is a program that is not there any more, which would otherwise
 * make the analysis fail quietly on every game.
 */
final class QuickAnalysisPrimaryReuse {
  private static final Logger LOG = LoggerFactory.getLogger(LogCategories.ENGINE);
  private static volatile boolean missingEngineLogged;

  private QuickAnalysisPrimaryReuse() {}

  static boolean shouldReuse(
      Config config, Leelaz foreground, boolean lightweightModel, BooleanSupplier javaSsh) {
    String command = foreground == null ? null : foreground.engineCommand();
    return shouldReuse(
        AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS,
        RemoteComputeConfig.isRemoteComputeEngineCommand(command),
        KataGoRuntimeHelper.isBundledNvidiaCommand(command),
        KataGoRuntimeHelper.isBundledTensorRtCommand(command),
        lightweightModel,
        analysisEngineMissing(config, javaSsh));
  }

  /** Default flash/deep analysis follows a selected cloud engine; explicit choices still win. */
  static boolean shouldReuseForManualAnalysis(Config config, Leelaz foreground) {
    if (config == null) return false;
    if (config.analysisReuseCurrentEngine) return true;
    org.json.JSONObject ssh =
        config.leelazConfig == null
            ? null
            : config.leelazConfig.optJSONObject("analysis-engine-ssh-info");
    return !config.analysisEngineCommandCustomized
        && (ssh == null || !ssh.optBoolean("useJavaSSH", false))
        && foreground != null
        && RemoteComputeConfig.isRemoteComputeEngineCommand(foreground.engineCommand());
  }

  static boolean shouldReuse(
      AnalysisResourceCoordinator.Purpose purpose,
      boolean remotePrimary,
      boolean bundledNvidiaPrimary,
      boolean bundledTensorRtPrimary,
      boolean lightweightQuickModelRequested,
      boolean analysisEngineMissing) {
    return purpose == AnalysisResourceCoordinator.Purpose.AUTO_QUICK_ANALYSIS
        && ((remotePrimary && !lightweightQuickModelRequested)
            || (bundledNvidiaPrimary && (bundledTensorRtPrimary || !lightweightQuickModelRequested))
            || (analysisEngineMissing && !lightweightQuickModelRequested));
  }

  /**
   * Whether the analysis engine of {@code config} is a local program that is not there any more.
   * Engines reached over SSH, as {@code javaSsh} tells once a program is missing, are not checked.
   */
  static boolean analysisEngineMissing(Config config, BooleanSupplier javaSsh) {
    if (config == null
        || config.analysisReuseCurrentEngine
        || !AnalysisEngineCommandHelper.isMissingLocalExecutable(config.analysisEngineCommand)
        || javaSsh.getAsBoolean()) {
      return false;
    }
    if (!missingEngineLogged) {
      missingEngineLogged = true;
      LOG.warn(
          "The analysis engine in the settings does not exist, so automatic analysis uses the"
              + " main engine: {}",
          config.analysisEngineCommand);
    }
    return true;
  }

  /**
   * Whether automatic analysis runs on the main engine, by choice or as the only one that works.
   */
  static boolean reusesPrimary(Config config, BooleanSupplier javaSsh) {
    return config != null
        && (config.analysisReuseCurrentEngine || analysisEngineMissing(config, javaSsh));
  }
}
