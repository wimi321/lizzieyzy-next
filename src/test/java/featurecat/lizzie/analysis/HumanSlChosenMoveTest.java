package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.training.CoachLevel;
import featurecat.lizzie.training.HumanSlPlaySettings;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class HumanSlChosenMoveTest {
  /** 3 dan: lambda 0.75, passes keep their search share, the temperature damps every move. */
  private static final HumanSlPlaySettings THREE_DAN = CoachLevel.DAN_3.play();

  @Test
  void humanOddsAreCutByTheUtilityAMoveLosesOverLambda() {
    JSONArray infos = new JSONArray().put(searched("D4", 0.10, 30)).put(searched("Q16", -0.65, 10));
    List<HumanLikeMoveSelector.Candidate> human =
        List.of(candidate("D4", 0.4), candidate("Q16", 0.6));

    HumanSlChosenMove.Weights weights = HumanSlChosenMove.weights(infos, human, THREE_DAN, false);

    // Q16 loses 0.75 utility, exactly lambda: its human odds shrink by e.
    assertEquals(0.6 * Math.exp(-1.0) / 0.4, weights.of("Q16") / weights.of("D4"), 1e-12);
  }

  @Test
  void movesTheSearchDidNotReachCountAtTheAverageUtility() {
    JSONArray infos = new JSONArray().put(searched("D4", 0.0, 30)).put(searched("Q16", -0.3, 10));
    List<HumanLikeMoveSelector.Candidate> human =
        List.of(candidate("D4", 0.5), candidate("Q16", 0.25), candidate("C3", 0.25));

    HumanSlChosenMove.Weights weights = HumanSlChosenMove.weights(infos, human, THREE_DAN, false);

    assertEquals(List.of("D4", "Q16", "C3"), weights.moves());
    assertEquals(0.25 * Math.exp(-0.15 / 0.75) / 0.5, weights.of("C3") / weights.of("D4"), 1e-12);
  }

  @Test
  void symmetricCopiesOfASearchedMoveAreNeitherSearchedNorAddedAgain() {
    JSONArray infos =
        new JSONArray()
            .put(searched("D4", 0.0, 30))
            .put(searched("Q16", 0.0, 30).put("isSymmetryOf", "D4"))
            .put(searched("C3", -0.75, 10));
    List<HumanLikeMoveSelector.Candidate> human =
        List.of(candidate("D4", 0.3), candidate("Q16", 0.3), candidate("C3", 0.4));

    HumanSlChosenMove.Weights weights = HumanSlChosenMove.weights(infos, human, THREE_DAN, false);

    assertEquals(List.of("D4", "C3"), weights.moves());
  }

  @Test
  void passKeepsTheShareTheSearchGivesItWhateverItsHumanOdds() {
    JSONArray infos =
        new JSONArray()
            .put(searched("D4", 0.0, 30).put("playSelectionValue", 60.0))
            .put(searched("pass", 0.0, 20).put("playSelectionValue", 40.0));
    List<HumanLikeMoveSelector.Candidate> human = List.of(candidate("D4", 1.0));

    HumanSlChosenMove.Weights weights = HumanSlChosenMove.weights(infos, human, THREE_DAN, true);

    assertEquals(0.6, weights.of("D4"), 1e-12);
    assertEquals(0.4, weights.of("pass"), 1e-12);
  }

  @Test
  void passGetsNothingWhenItIsNotAllowed() {
    JSONArray infos =
        new JSONArray()
            .put(searched("D4", 0.0, 30).put("playSelectionValue", 60.0))
            .put(searched("pass", 0.0, 20).put("playSelectionValue", 40.0));
    List<HumanLikeMoveSelector.Candidate> human = List.of(candidate("D4", 1.0));

    HumanSlChosenMove.Weights weights = HumanSlChosenMove.weights(infos, human, THREE_DAN, false);

    assertEquals(0.0, weights.of("pass"));
    assertEquals("D4", choose(infos, human, THREE_DAN, false, 0.999));
  }

  @Test
  void aLowLambdaLetsKataGoOverruleThePopularMistake() {
    // 11 dan: lambda 0.01. Most players answer at D4, but it loses 0.2 utility to C3.
    JSONArray infos = new JSONArray().put(searched("D4", -0.2, 300)).put(searched("C3", 0.0, 100));
    List<HumanLikeMoveSelector.Candidate> human =
        List.of(candidate("D4", 0.9), candidate("C3", 0.1));

    assertEquals("C3", choose(infos, human, CoachLevel.DAN_11.play(), false, 0.001));
    assertEquals("C3", choose(infos, human, CoachLevel.DAN_11.play(), false, 0.999));
    // At 1 dan (lambda 0.75) the popular move keeps most of its odds.
    assertEquals("D4", choose(infos, human, CoachLevel.DAN_1.play(), false, 0.0));
  }

  @Test
  void withoutSearchResultsTheHumanPolicyDecides() {
    List<HumanLikeMoveSelector.Candidate> human =
        List.of(candidate("D4", 0.7), candidate("Q4", 0.3));

    HumanSlChosenMove.Weights weights =
        HumanSlChosenMove.weights(new JSONArray(), human, THREE_DAN, false);

    assertEquals(0.7, weights.of("D4"), 1e-12);
    assertEquals(0.3, weights.of("Q4"), 1e-12);
    assertNull(choose(new JSONArray(), List.of(), THREE_DAN, false, 0.5));
  }

  @Test
  void movesWithoutVisitsCountAsNotSearched() {
    JSONArray infos =
        new JSONArray()
            .put(searched("D4", 0.3, 30))
            .put(searched("Q16", 0.9, 0))
            .put(searched("C3", -0.3, 10));
    List<HumanLikeMoveSelector.Candidate> human =
        List.of(candidate("D4", 0.4), candidate("Q16", 0.2), candidate("C3", 0.4));

    HumanSlChosenMove.Weights weights = HumanSlChosenMove.weights(infos, human, THREE_DAN, false);

    // Q16 takes the average utility of D4 and C3, not its unvisited 0.9.
    assertEquals(
        0.2 * Math.exp((0.0 - 0.3) / 0.75) / 0.4, weights.of("Q16") / weights.of("D4"), 1e-12);
  }

  @Test
  void theTemperatureFallsFromItsEarlyValueByHalflives() {
    assertEquals(0.70, HumanSlChosenMove.interpolateEarly(0, 19, 19, 50, 0.70, 0.40), 1e-12);
    assertEquals(0.55, HumanSlChosenMove.interpolateEarly(50, 19, 19, 50, 0.70, 0.40), 1e-12);
    // Moves count as on 19x19: on 9x9, 50 moves are 19/9 halflives.
    assertEquals(
        0.40 + 0.30 * Math.pow(0.5, 19.0 / 9.0),
        HumanSlChosenMove.interpolateEarly(50, 9, 9, 50, 0.70, 0.40),
        1e-12);
  }

  @Test
  void theTemperatureDampsEveryMoveOrOnlyTheRareOnes() {
    double[] values = {0.5, 0.3, 0.2};

    // onlyBelowProb 1: every move is raised to 1 / temperature, relative to the top one.
    double[] all = HumanSlChosenMove.temperatureWeights(values, 0.5, 1.0);
    assertArrayEquals(new double[] {1.0, 0.36, 0.16}, all, 1e-12);

    // onlyBelowProb 0.01: all three are common, so they keep their odds.
    double[] rare = HumanSlChosenMove.temperatureWeights(values, 0.5, 0.01);
    assertArrayEquals(new double[] {1.0, 0.6, 0.4}, rare, 1e-12);

    double[] greedy = HumanSlChosenMove.temperatureWeights(values, 0.0, 1.0);
    assertArrayEquals(new double[] {1.0, 0.0, 0.0}, greedy, 1e-12);
  }

  @Test
  void theDrawFollowsTheWeights() {
    double[] weights = {1.0, 0.0, 3.0};

    assertEquals(0, HumanSlChosenMove.sampleIndex(weights, 0.0));
    assertEquals(0, HumanSlChosenMove.sampleIndex(weights, 0.249));
    assertEquals(2, HumanSlChosenMove.sampleIndex(weights, 0.25));
    assertEquals(2, HumanSlChosenMove.sampleIndex(weights, 0.999));
    assertEquals(-1, HumanSlChosenMove.sampleIndex(new double[] {0.0}, 0.5));
  }

  @Test
  void everySearchLevelLeavesTheChoiceToTheClientAndKeepsGenmovesNoise() {
    for (CoachLevel level : CoachLevel.values()) {
      HumanSlPlaySettings play = level.play();
      if (!play.searches()) {
        continue;
      }
      assertEquals(1.0, play.searchOverrides().get("humanSLChosenMoveProp"), level.name());
      assertEquals(0.0, play.searchOverrides().get("wideRootNoise"), level.name());
      assertTrue(play.ignorePass(), level.name());
    }
    assertFalse(CoachLevel.KYU_1.play().searches());
  }

  private static String choose(
      JSONArray infos,
      List<HumanLikeMoveSelector.Candidate> human,
      HumanSlPlaySettings play,
      boolean allowPass,
      double random) {
    return HumanSlChosenMove.choose(infos, human, play, 200, 19, 19, allowPass, random);
  }

  private static JSONObject searched(String move, double utility, int visits) {
    return new JSONObject()
        .put("move", move)
        .put("utility", utility)
        .put("visits", visits)
        .put("playSelectionValue", (double) visits);
  }

  private static HumanLikeMoveSelector.Candidate candidate(String move, double probability) {
    return new HumanLikeMoveSelector.Candidate(move, probability);
  }
}
