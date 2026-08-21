package featurecat.lizzie.logging;

import java.util.Locale;

public enum NetworkEndpointCategory {
  PROTOCOL,
  AUTHENTICATION,
  ACCOUNT,
  PAYMENT,
  CREDENTIAL,
  OTHER;

  public boolean prohibitsBodies() {
    return this == AUTHENTICATION || this == ACCOUNT || this == PAYMENT || this == CREDENTIAL;
  }

  public String wireName() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
