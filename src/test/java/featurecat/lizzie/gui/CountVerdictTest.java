package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.rules.Stone;
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
  void aDrawGoesToTheFirstPasserWhenTheRulesSaySo() {
    CountVerdict black = new CountVerdict(184, 177 + 7.0, Stone.BLACK);
    CountVerdict white = new CountVerdict(184, 177 + 7.0, Stone.WHITE);

    assertEquals(CountVerdict.Outcome.BLACK, black.outcome());
    assertEquals("黑胜（和棋，黑方最先放弃着手）", black.text(STRINGS));
    assertEquals(CountVerdict.Outcome.WHITE, white.outcome());
    assertEquals("白胜（和棋，白方最先放弃着手）", white.text(STRINGS));
    assertEquals("黑胜4目", new CountVerdict(186, 175 + 7.0, Stone.WHITE).text(STRINGS));
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
      ResourceBundle strings = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
      for (Stone drawWinner : new Stone[] {null, Stone.BLACK, Stone.WHITE}) {
        String draw = new CountVerdict(7, 7, drawWinner).text(strings);
        assertEquals(false, draw.isBlank(), locale + " " + drawWinner);
      }
    }
  }
}
