package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class TrackingEngineRequestTest {

  @Test
  void buildAllowMoves_singlePoint_correctFormat() {
    Set<String> coords = new LinkedHashSet<>(Arrays.asList("E3"));
    JSONArray allowMoves = TrackingEngine.buildAllowMoves(coords, true);

    assertEquals(1, allowMoves.length());
    JSONObject entry = allowMoves.getJSONObject(0);
    assertEquals("B", entry.getString("player"));
    assertEquals(1, entry.getInt("untilDepth"));
    JSONArray moves = entry.getJSONArray("moves");
    assertEquals(1, moves.length());
    assertEquals("E3", moves.getString(0));
  }

  @Test
  void buildAllowMoves_multiplePoints_allIncluded() {
    Set<String> coords = new LinkedHashSet<>(Arrays.asList("E3", "F5", "Q16"));
    JSONArray allowMoves = TrackingEngine.buildAllowMoves(coords, false);

    assertEquals(1, allowMoves.length());
    JSONObject entry = allowMoves.getJSONObject(0);
    assertEquals("W", entry.getString("player"));
    JSONArray moves = entry.getJSONArray("moves");
    assertEquals(3, moves.length());
  }

  @Test
  void buildAllowMoves_emptyCoords_emptyArray() {
    Set<String> coords = new LinkedHashSet<>();
    JSONArray allowMoves = TrackingEngine.buildAllowMoves(coords, true);
    assertEquals(0, allowMoves.length());
  }

  @Test
  void buildAllowMoves_blackToPlay_playerIsB() {
    Set<String> coords = new LinkedHashSet<>(Arrays.asList("D4"));
    JSONArray allowMoves = TrackingEngine.buildAllowMoves(coords, true);
    assertEquals("B", allowMoves.getJSONObject(0).getString("player"));
  }

  @Test
  void buildAllowMoves_whiteToPlay_playerIsW() {
    Set<String> coords = new LinkedHashSet<>(Arrays.asList("D4"));
    JSONArray allowMoves = TrackingEngine.buildAllowMoves(coords, false);
    assertEquals("W", allowMoves.getJSONObject(0).getString("player"));
  }

  @Test
  void toAnalysisCommand_replacesGtpWithAnalysis() {
    String result = TrackingEngine.toAnalysisCommand("katago gtp -model m.bin -config c.cfg");
    assertTrue(result.contains("analysis"));
    assertFalse(result.contains("gtp"));
    assertTrue(result.contains("numAnalysisThreads=1"));
  }

  @Test
  void toAnalysisCommand_caseInsensitive() {
    String result = TrackingEngine.toAnalysisCommand("katago GTP -model m.bin");
    assertTrue(result.contains("analysis"));
    assertFalse(result.contains("GTP"));
  }

  @Test
  void toAnalysisCommand_noGtp_unchanged() {
    String result = TrackingEngine.toAnalysisCommand("katago analysis -model m.bin");
    assertTrue(result.contains("analysis"));
    assertTrue(result.contains("numAnalysisThreads=1"));
  }

  @Test
  void toAnalysisCommand_existingOverrideConfig() {
    String result =
        TrackingEngine.toAnalysisCommand(
            "katago gtp -model m.bin -override-config \"maxVisits=500\"");
    assertTrue(result.contains("numAnalysisThreads=1"));
    assertTrue(result.contains("maxVisits=500"));
  }
}
