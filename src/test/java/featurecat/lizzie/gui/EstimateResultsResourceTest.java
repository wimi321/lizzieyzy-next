package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class EstimateResultsResourceTest {

  @Test
  void supportedLocalesContainPositionEstimateStatusText() {
    List<Locale> locales =
        List.of(
            Locale.SIMPLIFIED_CHINESE,
            Locale.TRADITIONAL_CHINESE,
            Locale.US,
            Locale.JAPAN,
            Locale.KOREA,
            Locale.forLanguageTag("th-TH"));

    for (Locale locale : locales) {
      ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
      assertFalse(bundle.getString("EstimateResults.calculating").isBlank(), locale.toString());
      assertFalse(bundle.getString("EstimateResults.unavailable").isBlank(), locale.toString());
    }
  }

  @Test
  void simplifiedChineseUsesUserFacingPositionEstimateText() {
    ResourceBundle bundle =
        ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);

    assertEquals("正在判断...", bundle.getString("EstimateResults.calculating"));
    assertEquals("形势判断暂不可用", bundle.getString("EstimateResults.unavailable"));
  }
}
