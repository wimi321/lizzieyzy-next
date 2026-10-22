package featurecat.lizzie.enginegame;

import featurecat.lizzie.Config;
import java.util.Objects;

/**
 * Opening analysis parameters of the two engine-game participant positions ("Engine 1" and "Engine
 * 2"): breadth ({@code analysisWideRootNoise}) and aggressiveness ({@code
 * playoutDoublingAdvantage}). A batch freezes them and applies them at the start of every game.
 * They belong to positions, not to engine profiles or colors; zero breadth turns the parameter off
 * and zero aggressiveness is neutral.
 */
public record EngineGameOpeningParameters(
    double firstWrn, double secondWrn, double firstPda, double secondPda) {
  public static final double MAX_WRN = 2.0;
  public static final double MAX_PDA = 3.0;
  public static final EngineGameOpeningParameters NEUTRAL =
      new EngineGameOpeningParameters(0, 0, 0, 0);
  static final String FIRST_WRN_KEY = "engine-game-first-wrn";
  static final String SECOND_WRN_KEY = "engine-game-second-wrn";
  static final String FIRST_PDA_KEY = "engine-game-first-pda";
  static final String SECOND_PDA_KEY = "engine-game-second-pda";

  public EngineGameOpeningParameters {
    if (!validWrn(firstWrn) || !validWrn(secondWrn)) {
      throw new IllegalArgumentException("Engine-game breadth must be between 0 and " + MAX_WRN);
    }
    if (!validPda(firstPda) || !validPda(secondPda)) {
      throw new IllegalArgumentException(
          "Engine-game aggressiveness must be between -" + MAX_PDA + " and " + MAX_PDA);
    }
  }

  public double wrn(boolean firstPosition) {
    return firstPosition ? firstWrn : secondWrn;
  }

  public double pda(boolean firstPosition) {
    return firstPosition ? firstPda : secondPda;
  }

  public static boolean validWrn(double value) {
    return Double.isFinite(value) && value >= 0 && value <= MAX_WRN;
  }

  public static boolean validPda(double value) {
    return Double.isFinite(value) && value >= -MAX_PDA && value <= MAX_PDA;
  }

  /** Parses user input; null for blank, partial, non-finite or out-of-range text. */
  public static Double parseWrn(String text) {
    Double value = parse(text);
    return value != null && validWrn(value) ? value : null;
  }

  /** Parses user input; null for blank, partial, non-finite or out-of-range text. */
  public static Double parsePda(String text) {
    Double value = parse(text);
    return value != null && validPda(value) ? value : null;
  }

  /** The last saved opening settings. Missing or invalid values, as in older configs, are zero. */
  public static EngineGameOpeningParameters stored(Config config) {
    if (config == null || config.uiConfig == null) {
      return NEUTRAL;
    }
    return new EngineGameOpeningParameters(
        stored(config, FIRST_WRN_KEY, MAX_WRN, 0),
        stored(config, SECOND_WRN_KEY, MAX_WRN, 0),
        stored(config, FIRST_PDA_KEY, MAX_PDA, -MAX_PDA),
        stored(config, SECOND_PDA_KEY, MAX_PDA, -MAX_PDA));
  }

  public static void persist(Config config, EngineGameOpeningParameters parameters) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(parameters, "parameters");
    if (config.uiConfig != null) {
      config.uiConfig.put(FIRST_WRN_KEY, parameters.firstWrn());
      config.uiConfig.put(SECOND_WRN_KEY, parameters.secondWrn());
      config.uiConfig.put(FIRST_PDA_KEY, parameters.firstPda());
      config.uiConfig.put(SECOND_PDA_KEY, parameters.secondPda());
    }
  }

  private static Double parse(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      double value = Double.parseDouble(text.trim());
      return Double.isFinite(value) ? value : null;
    } catch (NumberFormatException invalid) {
      return null;
    }
  }

  private static double stored(Config config, String key, double max, double min) {
    double value = config.uiConfig.optDouble(key, 0);
    return Double.isFinite(value) && value >= min && value <= max ? value : 0;
  }
}
