package featurecat.lizzie;

import java.util.Locale;
import java.util.ResourceBundle;

/** Stable application locales mapped to the legacy integer configuration values. */
public enum AppLocale {
  SYSTEM(0, null),
  SIMPLIFIED_CHINESE(1, Locale.CHINA),
  ENGLISH(2, Locale.US),
  KOREAN(3, Locale.KOREA),
  JAPANESE(4, Locale.JAPAN),
  TRADITIONAL_CHINESE(5, Locale.TAIWAN),
  THAI(6, Locale.forLanguageTag("th-TH"));

  private static final String BUNDLE_NAME = "l10n.DisplayStrings";
  private static final Locale SYSTEM_LOCALE = Locale.getDefault();

  private final int configValue;
  private final Locale locale;

  AppLocale(int configValue, Locale locale) {
    this.configValue = configValue;
    this.locale = locale;
  }

  public int configValue() {
    return configValue;
  }

  public Locale locale() {
    return locale == null ? SYSTEM_LOCALE : locale;
  }

  public ResourceBundle loadBundle() {
    return ResourceBundle.getBundle(BUNDLE_NAME, locale());
  }

  public static AppLocale fromConfigValue(int value) {
    for (AppLocale candidate : values()) {
      if (candidate.configValue == value) {
        return candidate;
      }
    }
    return SYSTEM;
  }

  public static ResourceBundle loadBundle(int value) {
    return fromConfigValue(value).loadBundle();
  }
}
