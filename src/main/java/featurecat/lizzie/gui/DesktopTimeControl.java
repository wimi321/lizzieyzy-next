package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import java.util.List;

public final class DesktopTimeControl {
  public enum Mode {
    FIXED,
    RAW_ADVANCED,
    KATAGO_ADVANCED
  }

  private DesktopTimeControl() {}

  static Mode selectedMode(boolean rawAdvanced, boolean kataGoAdvanced) {
    if (rawAdvanced) return Mode.RAW_ADVANCED;
    if (kataGoAdvanced) return Mode.KATAGO_ADVANCED;
    return Mode.FIXED;
  }

  static boolean rejectsHumanGame(Leelaz engine, Mode mode, boolean noClock) {
    return !noClock && mode != Mode.FIXED && isWebSocket(engine);
  }

  public static boolean rejectsEngineGame(
      List<Leelaz> engines, int blackEngineIndex, int whiteEngineIndex, boolean advancedClock) {
    return advancedClock
        && (isWebSocket(engines.get(blackEngineIndex))
            || isWebSocket(engines.get(whiteEngineIndex)));
  }

  static boolean submitHumanSelection(
      Config config,
      Leelaz engine,
      Mode mode,
      int kataTimeType,
      boolean noClock,
      Runnable unsupportedWarning) {
    if (rejectsHumanGame(engine, mode, noClock)) {
      unsupportedWarning.run();
      return false;
    }
    commitHumanSelection(config, mode, kataTimeType);
    return true;
  }

  static void commitHumanSelection(Config config, Mode mode, int kataTimeType) {
    config.advanceTimeSettings = mode == Mode.RAW_ADVANCED;
    config.kataTimeSettings = mode == Mode.KATAGO_ADVANCED;
    config.kataTimeType = kataTimeType;
    config.uiConfig.put("advance-time-settings", config.advanceTimeSettings);
    config.uiConfig.put("kata-time-settings", config.kataTimeSettings);
    config.uiConfig.put("kata-time-type", config.kataTimeType);
  }

  static void commitEngineGameSelection(Config config, boolean advancedClock) {
    config.pkAdvanceTimeSettings = advancedClock;
    config.uiConfig.put("pk-advance-time-settings", advancedClock);
  }

  public static void sendEngineGameFixedTime(Leelaz engine, int seconds) {
    if (seconds <= 0) return;
    if (isWebSocket(engine)) {
      engine.sendCommand("kata-time_settings none");
      engine.sendCommand(LizzieFrame.buildKataGoFixedMoveTimeCommand(seconds));
    } else {
      engine.sendCommand(LizzieFrame.buildDefaultAiMoveTimeSettings(seconds));
    }
  }

  static boolean isWebSocket(Leelaz engine) {
    return engine != null
        && RemoteComputeConfig.isCustomWebSocketEngineCommand(engine.getEngineCommand());
  }
}
