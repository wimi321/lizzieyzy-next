package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisRequestRulesTest {
  @TempDir Path workDirectory;

  @Test
  void requestsFollowTheMainEngineAndTellWhetherTheFirstPasserWinsADraw() {
    Config previous = Lizzie.config;
    try {
      Lizzie.config = ConfigTestHelper.createForTests(workDirectory);
      Lizzie.config.analysisUseCurrentRules = true;
      Lizzie.config.currentKataGoRules =
          RulePreset.CHINESE_2027_DRAW_TO_FIRST_PASS.rules().toResponseLine();
      JSONObject request = new JSONObject();
      AnalysisRequestBuilder.addRules(request);
      assertTrue(request.getJSONObject("rules").getBoolean("hasButton"));
      assertTrue(AnalysisRequestBuilder.sendsFirstPassDrawRule());

      Lizzie.config.currentKataGoRules =
          KataGoRules.parse("chinese").orElseThrow().toResponseLine();
      assertFalse(AnalysisRequestBuilder.sendsFirstPassDrawRule());

      Lizzie.config.analysisUseCurrentRules = false;
      Lizzie.config.analysisSpecificRules = "";
      request = new JSONObject();
      AnalysisRequestBuilder.addRules(request);
      assertEquals("tromp-taylor", request.getString("rules"));
      assertFalse(AnalysisRequestBuilder.sendsFirstPassDrawRule());
    } finally {
      Lizzie.config = previous;
    }
  }
}
