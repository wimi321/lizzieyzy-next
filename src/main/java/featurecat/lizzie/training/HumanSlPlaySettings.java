package featurecat.lizzie.training;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How KataGo picks the AI's moves at one AI 陪练 level, after the gtp_human*_example.cfg configs the
 * levels were tuned with.
 *
 * <p>A policy level ({@code searches} false) plays the raw HumanSL policy of its profile, like
 * gtp_human5k_example.cfg: its lambda of 100000000 leaves the human odds untouched, so its small
 * search only judges passing and resigning. A search level searches with KataGo and shifts the
 * human policy towards the moves the search likes (PiKL, {@code humanSLChosenMovePiklLambda}): the
 * lower lambda, the more KataGo and the less the human model decides.
 *
 * @param profile the HumanSL profile, such as {@code preaz_11k} or {@code rank_3k}
 * @param searches whether the level searches; a policy level uses only its profile and the
 *     temperature, and keeps the other values of its config for reference
 * @param maxVisits KataGo's visits per move
 * @param piklLambda utility a move may lose before its human odds are cut, {@code
 *     humanSLChosenMovePiklLambda}
 * @param rootExploreWeightless share of root visits spent on human moves, {@code
 *     humanSLRootExploreProbWeightless}
 * @param cpuctPermanent how far human moves are explored whatever they lose, {@code
 *     humanSLCpuctPermanent}
 * @param temperatureOnlyBelowProb the temperature only damps moves rarer than this, {@code
 *     chosenMoveTemperatureOnlyBelowProb}
 * @param staticScoreUtility {@code staticScoreUtilityFactor}
 * @param dynamicScoreUtility {@code dynamicScoreUtilityFactor}
 * @param moveTimeFactor how many times the coach's time per move the level may search: the levels
 *     of 200 and 400 visits get twice as long, so that slower machines still reach their visits
 */
public record HumanSlPlaySettings(
    String profile,
    boolean searches,
    int maxVisits,
    double piklLambda,
    double rootExploreWeightless,
    double cpuctPermanent,
    double temperatureOnlyBelowProb,
    double staticScoreUtility,
    double dynamicScoreUtility,
    int moveTimeFactor) {
  /** Both families sample the root from two symmetries ({@code rootNumSymmetriesToSample}). */
  public static final int ROOT_SYMMETRIES = 2;

  static final int SEARCH_THREADS = 8;

  /** A level that plays the raw policy of {@code profile}, as gtp_human5k_example.cfg does. */
  public static HumanSlPlaySettings policy(String profile) {
    return new HumanSlPlaySettings(profile, false, 40, 100000000.0, 0.0, 1000.0, 0.01, 0.3, 0.0, 1);
  }

  /** A search level with the values of gtp_human9d_search_example.cfg's family. */
  public static HumanSlPlaySettings search(String profile) {
    return new HumanSlPlaySettings(profile, true, 60, 0.75, 0.8, 5.0, 1.0, 0.5, 0.5, 1);
  }

  HumanSlPlaySettings visits(int value) {
    return new HumanSlPlaySettings(
        profile,
        searches,
        value,
        piklLambda,
        rootExploreWeightless,
        cpuctPermanent,
        temperatureOnlyBelowProb,
        staticScoreUtility,
        dynamicScoreUtility,
        moveTimeFactor);
  }

  HumanSlPlaySettings pikl(double value) {
    return new HumanSlPlaySettings(
        profile,
        searches,
        maxVisits,
        value,
        rootExploreWeightless,
        cpuctPermanent,
        temperatureOnlyBelowProb,
        staticScoreUtility,
        dynamicScoreUtility,
        moveTimeFactor);
  }

  HumanSlPlaySettings rootWeightless(double value) {
    return new HumanSlPlaySettings(
        profile,
        searches,
        maxVisits,
        piklLambda,
        value,
        cpuctPermanent,
        temperatureOnlyBelowProb,
        staticScoreUtility,
        dynamicScoreUtility,
        moveTimeFactor);
  }

  HumanSlPlaySettings permanentExploration(double value) {
    return new HumanSlPlaySettings(
        profile,
        searches,
        maxVisits,
        piklLambda,
        rootExploreWeightless,
        value,
        temperatureOnlyBelowProb,
        staticScoreUtility,
        dynamicScoreUtility,
        moveTimeFactor);
  }

  HumanSlPlaySettings onlyBelowProb(double value) {
    return new HumanSlPlaySettings(
        profile,
        searches,
        maxVisits,
        piklLambda,
        rootExploreWeightless,
        cpuctPermanent,
        value,
        staticScoreUtility,
        dynamicScoreUtility,
        moveTimeFactor);
  }

  HumanSlPlaySettings scoreUtility(double value) {
    return new HumanSlPlaySettings(
        profile,
        searches,
        maxVisits,
        piklLambda,
        rootExploreWeightless,
        cpuctPermanent,
        temperatureOnlyBelowProb,
        value,
        value,
        moveTimeFactor);
  }

  HumanSlPlaySettings moveTime(int factor) {
    return new HumanSlPlaySettings(
        profile,
        searches,
        maxVisits,
        piklLambda,
        rootExploreWeightless,
        cpuctPermanent,
        temperatureOnlyBelowProb,
        staticScoreUtility,
        dynamicScoreUtility,
        factor);
  }

  /** The move temperature at the start of the game, {@code chosenMoveTemperatureEarly}. */
  public double temperatureEarly() {
    return searches ? 0.70 : 0.85;
  }

  /** The move temperature later on, {@code chosenMoveTemperature}. */
  public double temperature() {
    return searches ? 0.40 : 0.70;
  }

  /** Moves until the temperature is halfway there, {@code chosenMoveTemperatureHalflife}. */
  public double temperatureHalflife() {
    return searches ? 50.0 : 80.0;
  }

  /**
   * Whether passing keeps the share KataGo's search gives it instead of its human odds, {@code
   * humanSLChosenMoveIgnorePass}.
   */
  public boolean ignorePass() {
    return searches;
  }

  /**
   * The search settings of a search level as KataGo config keys, for an analysis query's {@code
   * overrideSettings}. KataGo's analysis engine never chooses a move itself, so the chosen-move
   * keys only order its moveInfos; the AI's move is chosen from them the way genmove would. The
   * configs leave {@code wideRootNoise} at genmove's default of 0, which the analysis engine would
   * raise.
   */
  public Map<String, Object> searchOverrides() {
    Map<String, Object> settings = new LinkedHashMap<String, Object>();
    settings.put("maxVisits", maxVisits);
    settings.put("numSearchThreads", SEARCH_THREADS);
    settings.put("humanSLProfile", profile);
    settings.put("humanSLChosenMoveProp", 1.0);
    settings.put("humanSLChosenMoveIgnorePass", ignorePass());
    settings.put("humanSLChosenMovePiklLambda", piklLambda);
    settings.put("humanSLRootExploreProbWeightless", rootExploreWeightless);
    settings.put("humanSLRootExploreProbWeightful", 0.1);
    settings.put("humanSLPlaExploreProbWeightless", 0.0);
    settings.put("humanSLPlaExploreProbWeightful", 0.1);
    settings.put("humanSLOppExploreProbWeightless", 0.0);
    settings.put("humanSLOppExploreProbWeightful", 0.1);
    settings.put("humanSLCpuctExploration", 0.5);
    settings.put("humanSLCpuctPermanent", cpuctPermanent);
    settings.put("chosenMoveTemperatureEarly", temperatureEarly());
    settings.put("chosenMoveTemperature", temperature());
    settings.put("chosenMoveTemperatureHalflife", temperatureHalflife());
    settings.put("chosenMoveTemperatureOnlyBelowProb", temperatureOnlyBelowProb);
    settings.put("chosenMoveSubtract", 0.0);
    settings.put("chosenMovePrune", 0.0);
    settings.put("ignorePreRootHistory", false);
    settings.put("rootNumSymmetriesToSample", ROOT_SYMMETRIES);
    settings.put("useLcbForSelection", false);
    settings.put("winLossUtilityFactor", 1.0);
    settings.put("staticScoreUtilityFactor", staticScoreUtility);
    settings.put("dynamicScoreUtilityFactor", dynamicScoreUtility);
    settings.put("useUncertainty", false);
    settings.put("subtreeValueBiasFactor", 0.0);
    settings.put("useNoisePruning", false);
    settings.put("wideRootNoise", 0.0);
    return settings;
  }
}
