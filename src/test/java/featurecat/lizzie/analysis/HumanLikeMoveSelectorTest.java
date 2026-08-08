package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class HumanLikeMoveSelectorTest {

  @Test
  void openingTemperatureProducesMoreVarietyThanEndgame() {
    List<HumanLikeMoveSelector.Candidate> candidates =
        List.of(candidate("D4", 0.70), candidate("Q16", 0.20), candidate("Q4", 0.10));

    Map<String, Integer> opening = sampleCounts(candidates, null, 20, "rank_3k", 1000);
    Map<String, Integer> endgame = sampleCounts(candidates, null, 180, "rank_3k", 1000);

    assertTrue(opening.getOrDefault("D4", 0) < 680);
    assertTrue(endgame.getOrDefault("D4", 0) > opening.getOrDefault("D4", 0) + 70);
    assertTrue(opening.getOrDefault("Q16", 0) > 0);
    assertTrue(opening.getOrDefault("Q4", 0) > 0);
  }

  @Test
  void candidatePoolUsesPolicyMassAndCapsAtTwelveMoves() {
    List<HumanLikeMoveSelector.Candidate> concentrated =
        List.of(candidate("D4", 0.96), candidate("Q16", 0.03), candidate("Q4", 0.01));
    assertEquals(1, HumanLikeMoveSelector.candidatePool(concentrated).size());

    List<HumanLikeMoveSelector.Candidate> broad = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      broad.add(candidate("M" + i, 1.0));
    }
    List<HumanLikeMoveSelector.Candidate> pool = HumanLikeMoveSelector.candidatePool(broad);
    assertEquals(HumanLikeMoveSelector.MAX_CANDIDATES, pool.size());
    assertEquals("M11", pool.get(pool.size() - 1).move);
  }

  @Test
  void qualityGuardRejectsAnExtremeMoveForStrongProfile() {
    List<HumanLikeMoveSelector.Candidate> candidates =
        List.of(candidate("D4", 0.50), candidate("A1", 0.50));
    JSONArray moveInfos = new JSONArray().put(moveInfo("D4", 0.80)).put(moveInfo("A1", -1.00));

    for (int i = 0; i < 1000; i++) {
      String selected =
          HumanLikeMoveSelector.select(candidates, moveInfos, 10, "rank_5d", (i + 0.5) / 1000.0);
      assertEquals("D4", selected);
    }
  }

  @Test
  void weakerProfileRetainsMoreNaturalMistakesThanStrongProfile() {
    List<HumanLikeMoveSelector.Candidate> candidates =
        List.of(candidate("D4", 0.50), candidate("Q4", 0.50));
    JSONArray moveInfos = new JSONArray().put(moveInfo("D4", 0.50)).put(moveInfo("Q4", -1.00));

    Map<String, Integer> strong = sampleCounts(candidates, moveInfos, 80, "rank_5d", 1000);
    Map<String, Integer> developing = sampleCounts(candidates, moveInfos, 80, "rank_15k", 1000);

    assertTrue(
        developing.getOrDefault("Q4", 0) > strong.getOrDefault("Q4", 0) + 300,
        "A developing profile should preserve more plausible non-best moves.");
  }

  @Test
  void selectorHasNoInstanceState() {
    for (Field field : HumanLikeMoveSelector.class.getDeclaredFields()) {
      assertTrue(
          Modifier.isStatic(field.getModifiers()),
          () -> "Unexpected selector state field: " + field.getName());
    }
  }

  @Test
  void phaseTemperaturesAreOpeningMiddleAndEndgameSpecific() {
    assertEquals(1.25, HumanLikeMoveSelector.temperatureForMove(60), 0.0001);
    assertEquals(1.05, HumanLikeMoveSelector.temperatureForMove(61), 0.0001);
    assertEquals(1.05, HumanLikeMoveSelector.temperatureForMove(160), 0.0001);
    assertEquals(0.90, HumanLikeMoveSelector.temperatureForMove(161), 0.0001);
  }

  private static Map<String, Integer> sampleCounts(
      List<HumanLikeMoveSelector.Candidate> candidates,
      JSONArray moveInfos,
      int moveNumber,
      String profile,
      int samples) {
    HashMap<String, Integer> counts = new HashMap<>();
    for (int i = 0; i < samples; i++) {
      String selected =
          HumanLikeMoveSelector.select(
              candidates, moveInfos, moveNumber, profile, (i + 0.5) / samples);
      counts.merge(selected, 1, Integer::sum);
    }
    return counts;
  }

  private static HumanLikeMoveSelector.Candidate candidate(String move, double probability) {
    return new HumanLikeMoveSelector.Candidate(move, probability);
  }

  private static JSONObject moveInfo(String move, double utility) {
    return new JSONObject().put("move", move).put("utility", utility);
  }
}
