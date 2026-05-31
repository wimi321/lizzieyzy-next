package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SyncDiagnosticsJsonTest {
  @Test
  void quoteEscapesBackslashQuoteAndNewline() {
    assertEquals("\"a\\\\b\\\"c\\n\"", SyncDiagnosticsJson.quote("a\\b\"c\n"));
  }

  @Test
  void quoteEscapesLowAsciiControlCharacters() {
    assertEquals("\"x\\u0001y\"", SyncDiagnosticsJson.quote("x\u0001y"));
  }
}
