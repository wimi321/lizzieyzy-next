package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * drawName picks the Korean-capable font from this pattern. Five of its character ranges were
 * written with an en dash, which a character class treats as three literal characters instead of
 * a range, so names using compatibility jamo or halfwidth/fullwidth forms went unrecognized.
 */
class BoardRendererKoreanNameTest {
  private static boolean matches(String name) {
    return BoardRenderer.KOREAN_NAME_PATTERN.matcher(name).matches();
  }

  @Test
  void hangulSyllablesMatch() {
    assertTrue(matches("이세돌"));
    assertTrue(matches("김철수"));
  }

  @Test
  void compatibilityJamoMatches() {
    // U+3131.. lies inside the ㄰-㆏ range that the en dash disabled.
    assertTrue(matches("ㄱㄴㄷ"));
  }

  @Test
  void fullwidthFormsMatch() {
    // U+FF21 FULLWIDTH LATIN CAPITAL LETTER A, inside ＀-￯.
    assertTrue(matches("ＡＩ"));
  }

  @Test
  void chineseNamesDoNotMatch() {
    assertFalse(matches("聂卫平"));
    assertFalse(matches("柯洁"));
  }

  @Test
  void asciiNamesStillMatchViaWordClass() {
    // Longstanding behavior: \w admits plain ASCII names; pinned so a change is deliberate.
    assertTrue(matches("AlphaGo"));
  }
}
