package featurecat.lizzie.analysis.gtp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GtpResponseHeaderTest {
  @Test
  void parameterValuePreservesNumberedAndUnnumberedLegacyPayloads() {
    assertEquals("true", GtpResponseHeader.parameterValue("=123 true"));
    assertEquals("999", GtpResponseHeader.parameterValue("=123   999  "));
    assertEquals("999", GtpResponseHeader.parameterValue("= 999"));
    assertEquals("0.5", GtpResponseHeader.parameterValue("  = 0.5  "));
    assertEquals("false", GtpResponseHeader.parameterValue("= false"));
    assertEquals("", GtpResponseHeader.parameterValue("?123 rejected"));
    assertEquals("", GtpResponseHeader.parameterValue(null));
    assertEquals("", GtpResponseHeader.parameterValue("="));
    assertEquals("12x value", GtpResponseHeader.parameterValue("=12x value"));
    assertEquals("12\tvalue", GtpResponseHeader.parameterValue("=12\tvalue"));
  }

  @Test
  void acceptsNumberedSuccessAndErrorWithWhitespaceOrEndBoundary() {
    assertEquals(0, GtpResponseHeader.commandId("=0", -1));
    assertEquals(123, GtpResponseHeader.commandId("=00123 KataGo", -1));
    assertEquals(456, GtpResponseHeader.commandId("?456\tillegal move", -1));
    assertEquals(Integer.MAX_VALUE, GtpResponseHeader.commandId("=2147483647\n", -1));
  }

  @Test
  void rejectsUnnumberedMalformedUnicodeAndOverflowHeaders() {
    for (String line :
        new String[] {
          null, "", "=", "?", "= 42", " =42", "info 42", "=-1", "=42x", "=٤٢", "=2147483648"
        }) {
      assertEquals(-1, GtpResponseHeader.commandId(line, -1));
    }
    assertEquals(99, GtpResponseHeader.commandId("?", 99));
  }
}
