package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class CountVerdictTest {
  private static final ResourceBundle STRINGS =
      ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);

  @Test
  void equalTotalsUnderAWholePointKomiAreADraw() {
    // 2027 Chinese rules: Black 184 against White 177 + 7.
    CountVerdict verdict = new CountVerdict(184, 177 + 7.0);

    assertEquals(CountVerdict.Outcome.DRAW, verdict.outcome());
    assertEquals("和棋", verdict.text(STRINGS));
  }

  @Test
  void aWinShowsItsMarginWithoutATrailingZero() {
    assertEquals(CountVerdict.Outcome.BLACK, new CountVerdict(186, 175 + 7.0).outcome());
    assertEquals("黑胜4目", new CountVerdict(186, 175 + 7.0).text(STRINGS));
    assertEquals("白胜0.5目", new CountVerdict(184, 177 + 7.5).text(STRINGS));
    assertEquals(CountVerdict.Outcome.WHITE, new CountVerdict(184, 177 + 7.5).outcome());
  }

  @Test
  void theDrawHasTextInEveryLanguage() {
    for (Locale locale :
        new Locale[] {
          Locale.US,
          Locale.TRADITIONAL_CHINESE,
          Locale.forLanguageTag("zh-HK"),
          Locale.JAPAN,
          Locale.KOREAN,
          Locale.forLanguageTag("th-TH")
        }) {
      String draw =
          new CountVerdict(7, 7).text(ResourceBundle.getBundle("l10n.DisplayStrings", locale));
      assertEquals(false, draw.isBlank(), locale.toString());
    }
  }
}
