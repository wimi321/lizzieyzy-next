package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.AppLocale;
import java.awt.Color;
import java.util.List;
import java.util.ResourceBundle;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class WinrateGraphLegendCopyTest {
  private static final Pattern HAN_CHARACTER = Pattern.compile("[\\p{IsHan}]");
  private static final Color WINRATE_COLOR = new Color(100, 180, 255);
  private static final Color SCORE_COLOR = new Color(255, 220, 80);

  @Test
  void englishWinrateOnlyLegendIsBlackWinrateWithoutHan() {
    ResourceBundle english = AppLocale.ENGLISH.loadBundle();
    WinrateGraph.RenderableMetrics metrics =
        WinrateGraph.resolveRenderableMetrics(true, false, true);

    List<String> labels = WinrateGraph.lineLegendLabels(metrics, english);

    assertEquals(List.of("Black winrate"), labels);
    labels.forEach(WinrateGraphLegendCopyTest::assertNoHan);
    assertEquals(
        List.of(WINRATE_COLOR),
        WinrateGraph.lineLegendColors(metrics, WINRATE_COLOR, SCORE_COLOR));
  }

  @Test
  void englishScoreOnlyLegendIsScoreLeadWithoutHan() {
    ResourceBundle english = AppLocale.ENGLISH.loadBundle();
    WinrateGraph.RenderableMetrics metrics =
        WinrateGraph.resolveRenderableMetrics(false, true, true);

    List<String> labels = WinrateGraph.lineLegendLabels(metrics, english);

    assertEquals(List.of("Score lead"), labels);
    labels.forEach(WinrateGraphLegendCopyTest::assertNoHan);
    assertEquals(
        List.of(SCORE_COLOR), WinrateGraph.lineLegendColors(metrics, WINRATE_COLOR, SCORE_COLOR));
  }

  @Test
  void englishBothMetricsLegendIsBlackWinrateThenScoreLead() {
    ResourceBundle english = AppLocale.ENGLISH.loadBundle();
    WinrateGraph.RenderableMetrics metrics =
        WinrateGraph.resolveRenderableMetrics(true, true, true);

    List<String> labels = WinrateGraph.lineLegendLabels(metrics, english);

    assertEquals(List.of("Black winrate", "Score lead"), labels);
    labels.forEach(WinrateGraphLegendCopyTest::assertNoHan);
    assertEquals(
        List.of(WINRATE_COLOR, SCORE_COLOR),
        WinrateGraph.lineLegendColors(metrics, WINRATE_COLOR, SCORE_COLOR));
  }

  @Test
  void unavailableScoreLeadOmitsScoreLegendEvenIfScoreLineIsEnabled() {
    ResourceBundle english = AppLocale.ENGLISH.loadBundle();
    WinrateGraph.RenderableMetrics metrics =
        WinrateGraph.resolveRenderableMetrics(true, true, false);

    assertEquals(List.of("Black winrate"), WinrateGraph.lineLegendLabels(metrics, english));
    assertTrue(
        WinrateGraph.lineLegendLabels(
                WinrateGraph.resolveRenderableMetrics(false, true, false), english)
            .isEmpty());
  }

  @Test
  void simplifiedChineseKeepsBlackWinrateAndScoreLeadLiterals() {
    ResourceBundle chinese = AppLocale.SIMPLIFIED_CHINESE.loadBundle();

    assertEquals(
        List.of("黑方胜率", "目差"),
        WinrateGraph.lineLegendLabels(
            WinrateGraph.resolveRenderableMetrics(true, true, true), chinese));
    assertEquals(
        List.of("黑方胜率"),
        WinrateGraph.lineLegendLabels(
            WinrateGraph.resolveRenderableMetrics(true, false, true), chinese));
    assertEquals(
        List.of("目差"),
        WinrateGraph.lineLegendLabels(
            WinrateGraph.resolveRenderableMetrics(false, true, true), chinese));
  }

  private static void assertNoHan(String value) {
    assertFalse(HAN_CHARACTER.matcher(value).find(), "English legend contains Han: " + value);
  }
}
