package featurecat.lizzie.util;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** Selects a physical UI font when a platform logical font cannot render the active locale. */
public final class LocaleFontSupport {
  private static final String DEFAULT_FONT = "Dialog";
  private static final String THAI_SAMPLE =
      "\u0e20\u0e32\u0e29\u0e32\u0e44\u0e17\u0e22 \u0e01\u0e32\u0e23\u0e15\u0e31\u0e49\u0e07\u0e04\u0e48\u0e32 \u0e40\u0e04\u0e23\u0e37\u0e48\u0e2d\u0e07\u0e21\u0e37\u0e2d";
  private static final List<String> THAI_FONT_CANDIDATES =
      Arrays.asList(
          "Leelawadee UI",
          "Tahoma",
          "Noto Sans Thai",
          "Noto Sans Thai Looped",
          "Thonburi",
          "Arial Unicode MS",
          "Noto Sans",
          "DejaVu Sans");

  private LocaleFontSupport() {}

  public static String resolveDefaultFontName(String configuredName, Locale locale) {
    String configured = normalized(configuredName, DEFAULT_FONT);
    if (!isThai(locale)) {
      return configured;
    }
    return resolveThaiFontName(configured, configured, LocaleFontSupport::canRenderThai);
  }

  public static String resolveConfiguredFontName(
      String configuredName, Locale locale, String fallbackName) {
    String fallback = normalized(fallbackName, DEFAULT_FONT);
    String configured =
        isDefaultSelection(configuredName) ? fallback : normalized(configuredName, fallback);
    if (!isThai(locale)) {
      return configured;
    }
    return resolveThaiFontName(configured, fallback, LocaleFontSupport::canRenderThai);
  }

  static String resolveThaiFontName(
      String configuredName, String fallbackName, Predicate<String> supportsThai) {
    String fallback = normalized(fallbackName, DEFAULT_FONT);
    String configured = normalized(configuredName, fallback);
    if (supportsThai.test(configured)) {
      return configured;
    }
    if (!configured.equalsIgnoreCase(fallback) && supportsThai.test(fallback)) {
      return fallback;
    }
    for (String candidate : THAI_FONT_CANDIDATES) {
      if (supportsThai.test(candidate)) {
        return candidate;
      }
    }
    return fallback;
  }

  static boolean isDefaultSelection(String fontName) {
    if (fontName == null || fontName.trim().isEmpty()) {
      return true;
    }
    String value = fontName.trim();
    return "Lizzie Default".equalsIgnoreCase(value) || "Lizzie\u9ed8\u8ba4".equals(value);
  }

  private static boolean isThai(Locale locale) {
    return locale != null && "th".equalsIgnoreCase(locale.getLanguage());
  }

  private static String normalized(String fontName, String fallback) {
    return fontName == null || fontName.trim().isEmpty() ? fallback : fontName.trim();
  }

  private static boolean canRenderThai(String requestedName) {
    String family = availableFontFamilies().get(requestedName.toLowerCase(Locale.ROOT));
    if (family == null) {
      return false;
    }
    return new Font(family, Font.PLAIN, 12).canDisplayUpTo(THAI_SAMPLE) < 0;
  }

  private static Map<String, String> availableFontFamilies() {
    Map<String, String> families = new HashMap<>();
    for (String family :
        GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
      families.putIfAbsent(family.toLowerCase(Locale.ROOT), family);
    }
    return families;
  }
}
