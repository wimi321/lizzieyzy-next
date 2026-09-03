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
  private static final String SIMPLIFIED_CHINESE_SAMPLE = "简体中文";
  private static final String TRADITIONAL_CHINESE_SAMPLE = "繁體中文";
  private static final String JAPANESE_SAMPLE = "日本語";
  private static final String KOREAN_SAMPLE = "한국어";
  private static final String THAI_SAMPLE =
      "\u0e20\u0e32\u0e29\u0e32\u0e44\u0e17\u0e22 \u0e01\u0e32\u0e23\u0e15\u0e31\u0e49\u0e07\u0e04\u0e48\u0e32 \u0e40\u0e04\u0e23\u0e37\u0e48\u0e2d\u0e07\u0e21\u0e37\u0e2d";
  private static final List<String> SIMPLIFIED_CHINESE_FONT_CANDIDATES =
      Arrays.asList(
          "Microsoft YaHei UI",
          "Microsoft YaHei",
          "PingFang SC",
          "Noto Sans CJK SC",
          "Source Han Sans SC",
          "SimHei",
          "Arial Unicode MS");
  private static final List<String> TRADITIONAL_CHINESE_FONT_CANDIDATES =
      Arrays.asList(
          "Microsoft JhengHei UI",
          "Microsoft JhengHei",
          "PingFang TC",
          "Noto Sans CJK TC",
          "Source Han Sans TC",
          "MingLiU",
          "Arial Unicode MS");
  private static final List<String> JAPANESE_FONT_CANDIDATES =
      Arrays.asList(
          "Yu Gothic UI",
          "Yu Gothic",
          "Meiryo UI",
          "Meiryo",
          "Hiragino Sans",
          "Noto Sans CJK JP",
          "Arial Unicode MS");
  private static final List<String> KOREAN_FONT_CANDIDATES =
      Arrays.asList(
          "Malgun Gothic",
          "Apple SD Gothic Neo",
          "Noto Sans CJK KR",
          "NanumGothic",
          "Arial Unicode MS");
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

  /** Selects a font that can render the native label for a language picker option. */
  public static String resolveLanguageFontName(String preferredName, Locale locale) {
    String preferred = normalized(preferredName, DEFAULT_FONT);
    String sample = languageSample(locale);
    List<String> candidates = languageFontCandidates(locale);
    if (sample == null || candidates.isEmpty()) {
      return preferred;
    }
    return resolveFontName(
        preferred,
        preferred,
        candidates,
        requestedName -> canRender(requestedName, sample));
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
    return resolveFontName(
        configuredName, fallbackName, THAI_FONT_CANDIDATES, supportsThai);
  }

  private static String resolveFontName(
      String configuredName,
      String fallbackName,
      List<String> candidates,
      Predicate<String> supportsText) {
    String fallback = normalized(fallbackName, DEFAULT_FONT);
    String configured = normalized(configuredName, fallback);
    if (supportsText.test(configured)) {
      return configured;
    }
    if (!configured.equalsIgnoreCase(fallback) && supportsText.test(fallback)) {
      return fallback;
    }
    for (String candidate : candidates) {
      if (supportsText.test(candidate)) {
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

  private static String languageSample(Locale locale) {
    if (locale == null) {
      return null;
    }
    switch (locale.getLanguage().toLowerCase(Locale.ROOT)) {
      case "zh":
        return isTraditionalChinese(locale)
            ? TRADITIONAL_CHINESE_SAMPLE
            : SIMPLIFIED_CHINESE_SAMPLE;
      case "ja":
        return JAPANESE_SAMPLE;
      case "ko":
        return KOREAN_SAMPLE;
      case "th":
        return THAI_SAMPLE;
      default:
        return null;
    }
  }

  private static List<String> languageFontCandidates(Locale locale) {
    if (locale == null) {
      return List.of();
    }
    switch (locale.getLanguage().toLowerCase(Locale.ROOT)) {
      case "zh":
        return isTraditionalChinese(locale)
            ? TRADITIONAL_CHINESE_FONT_CANDIDATES
            : SIMPLIFIED_CHINESE_FONT_CANDIDATES;
      case "ja":
        return JAPANESE_FONT_CANDIDATES;
      case "ko":
        return KOREAN_FONT_CANDIDATES;
      case "th":
        return THAI_FONT_CANDIDATES;
      default:
        return List.of();
    }
  }

  private static boolean isTraditionalChinese(Locale locale) {
    String script = locale.getScript();
    if ("Hant".equalsIgnoreCase(script)) {
      return true;
    }
    String country = locale.getCountry();
    return "TW".equalsIgnoreCase(country)
        || "HK".equalsIgnoreCase(country)
        || "MO".equalsIgnoreCase(country);
  }

  private static String normalized(String fontName, String fallback) {
    return fontName == null || fontName.trim().isEmpty() ? fallback : fontName.trim();
  }

  private static boolean canRenderThai(String requestedName) {
    return canRender(requestedName, THAI_SAMPLE);
  }

  private static boolean canRender(String requestedName, String sample) {
    String family = availableFontFamilies().get(requestedName.toLowerCase(Locale.ROOT));
    if (family == null) {
      return false;
    }
    return new Font(family, Font.PLAIN, 12).canDisplayUpTo(sample) < 0;
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
