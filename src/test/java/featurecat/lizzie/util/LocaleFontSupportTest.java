package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class LocaleFontSupportTest {
  @Test
  void leavesConfiguredFontAloneOutsideThaiLocale() {
    assertEquals(
        "Dialog",
        LocaleFontSupport.resolveConfiguredFontName("Dialog", Locale.US, "Fallback"));
  }

  @Test
  void replacesUnsupportedThaiLogicalFontWithFirstSupportedPhysicalFont() {
    String resolved =
        LocaleFontSupport.resolveThaiFontName(
            "Dialog", "Dialog", name -> "Leelawadee UI".equals(name));

    assertEquals("Leelawadee UI", resolved);
  }

  @Test
  void preservesCustomThaiFontWhenItSupportsThai() {
    String resolved =
        LocaleFontSupport.resolveThaiFontName(
            "Custom Thai", "Dialog", name -> "Custom Thai".equals(name));

    assertEquals("Custom Thai", resolved);
  }

  @Test
  void treatsLizzieDefaultAsTheConfiguredFallback() {
    assertEquals(
        "Fallback Thai",
        LocaleFontSupport.resolveConfiguredFontName(
            "Lizzie Default", Locale.US, "Fallback Thai"));
  }
}
