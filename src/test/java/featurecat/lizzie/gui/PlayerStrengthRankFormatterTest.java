package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class PlayerStrengthRankFormatterTest {
  @Test
  void preservesDistinctSubDanPredictionsAsKyuRanks() {
    ResourceBundle chinese = bundle(Locale.SIMPLIFIED_CHINESE);

    String black = PlayerStrengthRankFormatter.format(0.6339170252703988, chinese);
    String white = PlayerStrengthRankFormatter.format(0.462926218772769957, chinese);

    assertEquals("\u4e1a\u4f591.4\u7ea7", black);
    assertEquals("\u4e1a\u4f591.5\u7ea7", white);
    assertNotEquals(black, white);
  }

  @Test
  void handlesTheMissingZeroRankAtTheKyuDanBoundary() {
    ResourceBundle english = bundle(Locale.US);

    assertEquals("1.0 kyu", PlayerStrengthRankFormatter.format(0.999, english));
    assertEquals("1.0 dan", PlayerStrengthRankFormatter.format(1.0, english));
    assertEquals("9.9 dan", PlayerStrengthRankFormatter.format(9.94, english));
  }

  @Test
  void localizesKyuRanksForEverySupportedLanguage() {
    assertEquals(
        "\u4e1a\u4f591.4\u7ea7",
        PlayerStrengthRankFormatter.format(0.6, bundle(Locale.SIMPLIFIED_CHINESE)));
    assertEquals(
        "\u696d\u99181.4\u7d1a",
        PlayerStrengthRankFormatter.format(0.6, bundle(Locale.TRADITIONAL_CHINESE)));
    assertEquals(
        "1.4\u7d1a", PlayerStrengthRankFormatter.format(0.6, bundle(Locale.JAPAN)));
    assertEquals(
        "1.4 \ud050", PlayerStrengthRankFormatter.format(0.6, bundle(Locale.KOREA)));
    assertEquals(
        "1.4 \u0e04\u0e22\u0e39",
        PlayerStrengthRankFormatter.format(0.6, bundle(Locale.forLanguageTag("th-TH"))));
    assertEquals("1.4 kyu", PlayerStrengthRankFormatter.format(0.6, bundle(Locale.US)));
  }

  @Test
  void formatsProfessionalBandsAndRejectsInvalidPredictions() {
    ResourceBundle chinese = bundle(Locale.SIMPLIFIED_CHINESE);

    assertEquals(
        "10.0 \u804c\u4e1a", PlayerStrengthRankFormatter.format(10.0, chinese));
    assertEquals(
        "11.0 \u4e00\u7ebf\u804c\u4e1a", PlayerStrengthRankFormatter.format(11.0, chinese));
    assertEquals("12.0 \u534a\u795e/AI", PlayerStrengthRankFormatter.format(12.0, chinese));
    assertEquals("-", PlayerStrengthRankFormatter.format(Double.NaN, chinese));
    assertEquals("-", PlayerStrengthRankFormatter.format(Double.POSITIVE_INFINITY, chinese));
  }

  private static ResourceBundle bundle(Locale locale) {
    return ResourceBundle.getBundle("l10n.DisplayStrings", locale);
  }
}
