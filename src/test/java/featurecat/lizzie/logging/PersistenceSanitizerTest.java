package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PersistenceSanitizerTest {
  @Test
  void zhiziStartupAccountIdentityIsRemovedBeforePersistenceAndExport() {
    PersistenceSanitizer sanitizer = new PersistenceSanitizer();
    for (String account : List.of("13800000000", "synthetic@example.invalid", "CANARY_ACCOUNT")) {
      String input = "DEBUG Platform: [all] User: [zz-" + account + "] Backend: [TENSORRT]";
      String expected = "DEBUG Platform: [all] User: [<redacted>] Backend: [TENSORRT]";
      assertEquals(expected, sanitizer.sanitize(input));
      assertEquals(expected, new ExportSanitizer().sanitizeText(input));
      String json = new org.json.JSONObject().put("diagnostic", input).toString();
      assertFalse(sanitizer.sanitize(json).contains(account));
      assertEquals(
          "User: [<redacted>\nnext", sanitizer.sanitize("User: [zz-" + account + "\nnext"));
    }
    String unrelated = "User: [guest] Platform: [all] userVisits=500";
    assertEquals(unrelated, sanitizer.sanitize(unrelated));
  }

  @Test
  void persistedEncoderRemovesZhiziAccountFromProviderStderr() {
    SanitizingEncoder encoder = new SanitizingEncoder();
    ch.qos.logback.classic.LoggerContext context = new ch.qos.logback.classic.LoggerContext();
    encoder.setContext(context);
    encoder.setPattern("%msg%n");
    encoder.start();
    try {
      var event = new ch.qos.logback.classic.spi.LoggingEvent();
      event.setLoggerName(LogCategories.APP);
      event.setLevel(ch.qos.logback.classic.Level.DEBUG);
      event.setMessage("DEBUG Platform: [all] User: [zz-CANARY_ACCOUNT]");
      String persisted = new String(encoder.encode(event), java.nio.charset.StandardCharsets.UTF_8);
      assertFalse(persisted.contains("CANARY_ACCOUNT"));
      assertTrue(persisted.contains("Platform: [all] User: [<redacted>]"));
    } finally {
      encoder.stop();
      context.stop();
    }
  }

  private static final List<String> CANARIES =
      List.of(
          "CANARY_BARE_BEARER_01",
          "CANARY_ESCAPED_PASSWORD_02",
          "CANARY_UNICODE_COLON_03",
          "CANARY_UNICODE_EQUAL_04",
          "CANARY_RAW_QUOTED_SUFFIX_05",
          "CANARY_EMBEDDED_QUOTED_SUFFIX_06",
          "CANARY_UNICODE_NAME_07",
          "CANARY_DOUBLE_UNICODE_NAME_08",
          "CANARY_PERCENT_PASSWORD_09",
          "CANARY_PERCENT_KEY_10",
          "CANARY_PERCENT_DOUBLE_11",
          "CANARY_PERCENT_MIXED_HEX_12",
          "CANARY_PERCENT_OVERDEPTH_ASSIGNMENT_13",
          "CANARY_PERCENT_OVERDEPTH_KEY_14",
          "CANARY_PERCENT_OVERDEPTH_MIXED_HEX_15",
          "CANARY_PERCENT_OVERDEPTH_HEADER_16",
          "CANARY_CLI_API_KEY",
          "CANARY_CLI_QUOTED_KEY",
          "CANARY_PERCENT_OVERDEPTH_BOUNDARY_17");

  private static final List<String> PAYLOADS =
      List.of(
          "Bearer CANARY_BARE_BEARER_01",
          "payload={\\\"password\\\":\\\"CANARY_ESCAPED_PASSWORD_02\\\"}",
          "Authorization\\u003a Bearer CANARY_UNICODE_COLON_03",
          "password\\u003dCANARY_UNICODE_EQUAL_04",
          "payload={\"password\":\"prefix\\\"CANARY_RAW_QUOTED_SUFFIX_05\"}",
          escapedEmbeddedJson("CANARY_EMBEDDED_QUOTED_SUFFIX_06"),
          "passw\\u006frd=CANARY_UNICODE_NAME_07",
          "passw\\\\u006frd=CANARY_DOUBLE_UNICODE_NAME_08",
          "body=password%3DCANARY_PERCENT_PASSWORD_09",
          "body=%70assword=CANARY_PERCENT_KEY_10",
          "body=token%253DCANARY_PERCENT_DOUBLE_11",
          "body=passw%4Frd%3dCANARY_PERCENT_MIXED_HEX_12",
          "body=password%252525253DCANARY_PERCENT_OVERDEPTH_ASSIGNMENT_13",
          "body=%2525252570assword=CANARY_PERCENT_OVERDEPTH_KEY_14",
          "body=passw%254Frd%252525253dCANARY_PERCENT_OVERDEPTH_MIXED_HEX_15",
          "Authorization%252525253A Bearer CANARY_PERCENT_OVERDEPTH_HEADER_16",
          "engine.exe --api-key CANARY_CLI_API_KEY gtp",
          "engine.exe --api-key 'CANARY_CLI_QUOTED_KEY with spaces' gtp",
          "body=opaque%2525252520token=CANARY_PERCENT_OVERDEPTH_BOUNDARY_17");

  @Test
  void quotedFlagsAndControlCharactersUseLaunchArgumentBoundaries() {
    PersistenceSanitizer sanitizer = new PersistenceSanitizer();
    for (String flag : List.of("-pw", "\"-pw\"", "'-pw'")) {
      for (String value : List.of("CANARY&SUFFIX;END", "CANARY\tSUFFIX", "CANARY\nSUFFIX")) {
        String command = "plink " + flag + " " + value + " -P 22";
        assertEquals(value, featurecat.lizzie.util.Utils.splitCommand(command).get(2));
        assertEquals("plink " + flag + " <redacted> -P 22", sanitizer.sanitize(command));
      }
    }
  }

  @Test
  void plinkPasswordsAreWholeArgumentsRatherThanQueryValues() {
    PersistenceSanitizer sanitizer = new PersistenceSanitizer();
    for (String value :
        List.of(
            "CANARY_PW_SIMPLE",
            "\"CANARY_PW_SPACE tail\"",
            "'CANARY_PW_SINGLE tail'",
            "CANARY_PW_SPECIAL&suffix;end,rest}final",
            "\"CANARY_PW_ESCAPED\\\" suffix\"",
            "CANARY_PW_PREFIX\" spaced suffix\"",
            "CANARY_PW_ESCAPED\\ suffix")) {
      assertEquals(
          "command=plink user@host -P 22 -pw <redacted> ./start-engine.sh",
          sanitizer.sanitize("command=plink user@host -P 22 -pw " + value + " ./start-engine.sh"));
    }
    assertEquals(
        "plink -pwfile file.txt -P 22", sanitizer.sanitize("plink -pwfile file.txt -P 22"));
  }

  @Test
  void structuredPlinkArgumentsRedactTheWholeValueWithoutMutatingInput() {
    org.json.JSONObject input =
        new org.json.JSONObject()
            .put(
                "arguments",
                new org.json.JSONArray(List.of("plink", "-pw", "CANARY_PW & suffix", "-P", "22")));
    org.json.JSONArray safe =
        new ExportSanitizer().sanitizeJsonObject(input).getJSONArray("arguments");
    assertEquals("<redacted>", safe.getString(2));
    assertEquals("-P", safe.getString(3));
    assertEquals("22", safe.getString(4));
    assertEquals("CANARY_PW & suffix", input.getJSONArray("arguments").getString(2));
  }

  @Test
  void structuredOverridesPreserveLiteralReplacementMetacharacters() {
    String argument = "runLabel=run$archive\\segment,password=CANARY secret,maxSimultaneousGames=1";
    org.json.JSONObject input =
        new org.json.JSONObject()
            .put(
                "arguments",
                new org.json.JSONArray(
                    List.of("katago", "contribute", "-override-config", argument)));
    org.json.JSONArray safe =
        new ExportSanitizer().sanitizeJsonObject(input).getJSONArray("arguments");
    assertEquals(
        "runLabel=run$archive\\segment,password=<redacted>,maxSimultaneousGames=1",
        safe.getString(3));
    assertEquals(argument, input.getJSONArray("arguments").getString(3));
  }

  @Test
  void ordinaryCredentialFieldsPreserveFollowingDiagnosticContext() {
    String safe =
        new PersistenceSanitizer()
            .sanitize("token=CANARY_TOKEN password=CANARY_PASSWORD stage=install status=failed");
    assertFalse(safe.contains("CANARY"));
    assertTrue(safe.contains("stage=install status=failed"));
  }

  @Test
  void contributionPasswordAssignmentsAreCompletelyRedactedAcrossRawSplitAndRenderedForms() {
    PersistenceSanitizer sanitizer = new PersistenceSanitizer();
    String raw =
        "katago contribute -override-config"
            + " \"password=CANARY_CONTRIB space&suffix;end\",\"maxSimultaneousGames=1\"";
    assertEquals(
        "katago contribute -override-config \"password=<redacted>\",\"maxSimultaneousGames=1\"",
        sanitizer.sanitize(raw));

    List<String> splitTokens = featurecat.lizzie.util.Utils.splitCommand(raw);
    assertEquals(
        "password=CANARY_CONTRIB space&suffix;end,maxSimultaneousGames=1", splitTokens.get(3));
    assertEquals(
        "password=<redacted>,maxSimultaneousGames=1", sanitizer.sanitize(splitTokens.get(3)));
    assertEquals(
        "maxSimultaneousGames=1,password=<redacted>",
        sanitizer.sanitize("maxSimultaneousGames=1,password=CANARY_CONTRIB space&suffix;end"));
    assertEquals(
        "threads=4,password=<redacted>,maxSimultaneousGames=1",
        sanitizer.sanitize(
            "threads=4,password=CANARY_CONTRIB space&suffix;end,maxSimultaneousGames=1"));

    String rendered =
        "\"katago\" \"contribute\" \"-override-config\" \"password=CANARY_CONTRIB space&suffix;end,maxSimultaneousGames=1\"";
    assertEquals(
        "\"katago\" \"contribute\" \"-override-config\" \"password=<redacted>,maxSimultaneousGames=1\"",
        sanitizer.sanitize(rendered));

    assertEquals(
        "-override-config \"maxSimultaneousGames=1,rules=chinese\"",
        sanitizer.sanitize("-override-config \"maxSimultaneousGames=1,rules=chinese\""));
    assertEquals(
        "-override-config \"maxSimultaneousGames=1\",\"rules=chinese\"",
        sanitizer.sanitize("-override-config \"maxSimultaneousGames=1\",\"rules=chinese\""));
  }

  @Test
  void contributionPasswordSupportsOrdinarySpacesPunctuationAndQuoteEscapes() {
    PersistenceSanitizer sanitizer = new PersistenceSanitizer();

    assertEquals(
        "\"password=<redacted>\"", sanitizer.sanitize("\"password=CANARY\\\"quote suffix\""));
    assertEquals(
        "\"password=<redacted>\"", sanitizer.sanitize("\"password=CANARY'single quote suffix\""));
    assertEquals(
        "'password=<redacted>'", sanitizer.sanitize("'password=CANARY single quote suffix'"));
    assertEquals(
        "'password=<redacted>'", sanitizer.sanitize("'password=CANARY\\'single quote suffix'"));
    assertEquals(
        "'password=<redacted>'", sanitizer.sanitize("'password=CANARY\\\"double quote suffix'"));
    assertEquals(
        "\"password=<redacted>\"", sanitizer.sanitize("\"password=CANARY\\\\path suffix\""));

    assertEquals(
        "\"password=<redacted>\"",
        sanitizer.sanitize("\"password=CANARY space&suffix;end#foo!bar?baz=123\""));
    assertEquals(
        "password=<redacted>,maxSimultaneousGames=1",
        sanitizer.sanitize(
            "password=CANARY space&suffix;end#foo!bar?baz=123,maxSimultaneousGames=1"));
    assertEquals(
        "maxSimultaneousGames=1,password=<redacted>",
        sanitizer.sanitize(
            "maxSimultaneousGames=1,password=CANARY space&suffix;end#foo!bar?baz=123"));
  }

  @Test
  void pwCredentialAssignmentsAndArgumentsAreRedactedWithoutBreakingPwFlags() {
    PersistenceSanitizer sanitizer = new PersistenceSanitizer();

    String raw =
        "katago contribute -override-config"
            + " \"pw=CANARY_PW space&suffix;end\",\"maxSimultaneousGames=1\"";
    assertEquals(
        "katago contribute -override-config \"pw=<redacted>\",\"maxSimultaneousGames=1\"",
        sanitizer.sanitize(raw));
    assertEquals(
        "\"katago\" \"contribute\" \"-override-config\" \"pw=<redacted>,maxSimultaneousGames=1\"",
        sanitizer.sanitize(
            "\"katago\" \"contribute\" \"-override-config\" \"pw=CANARY_PW space&suffix;end,maxSimultaneousGames=1\""));
    assertEquals(
        "pw=<redacted>,maxSimultaneousGames=1",
        sanitizer.sanitize("pw=CANARY_PW space&suffix;end,maxSimultaneousGames=1"));
    assertEquals(
        "maxSimultaneousGames=1,pw=<redacted>",
        sanitizer.sanitize("maxSimultaneousGames=1,pw=CANARY_PW space&suffix;end"));

    assertEquals(
        "plink -pw <redacted> -P 22",
        sanitizer.sanitize("plink -pw \"CANARY_PW_SPACE tail\" -P 22"));
    assertEquals(
        "plink -pwfile file.txt -P 22", sanitizer.sanitize("plink -pwfile file.txt -P 22"));
  }

  @Test
  void encodedCredentialFormsAreRedactedWithoutCopyingCanaries() {
    assertSanitized(new PersistenceSanitizer());
  }

  @Test
  void percentCredentialMatchingPreservesEncodedPrefixAndNeverRewritesValue() {
    PersistenceSanitizer sanitizer = new PersistenceSanitizer();

    assertEquals(
        "body=password%3D<redacted>",
        sanitizer.sanitize("body=password%3DCANARY_PERCENT_PASSWORD_09"));
    assertEquals(
        "body=%70assword=<redacted>", sanitizer.sanitize("body=%70assword=CANARY_PERCENT_KEY_10"));
    assertEquals(
        "body=token%253D<redacted>", sanitizer.sanitize("body=token%253DCANARY_PERCENT_DOUBLE_11"));
    assertEquals(
        "body=passw%4Frd%3d<redacted>",
        sanitizer.sanitize("body=passw%4Frd%3dCANARY_PERCENT_MIXED_HEX_12"));
    assertEquals(
        "body=secret%2525253D<redacted>",
        sanitizer.sanitize("body=secret%2525253DCANARY_PERCENT_MAX_DEPTH"));
    assertEquals(
        "body=password%252525253D<redacted>",
        sanitizer.sanitize("body=password%252525253DCANARY_PERCENT_OVERDEPTH_ASSIGNMENT_13"));
    assertEquals(
        "body=%2525252570assword=<redacted>",
        sanitizer.sanitize("body=%2525252570assword=CANARY_PERCENT_OVERDEPTH_KEY_14"));
    assertEquals(
        "body=passw%254Frd%252525253d<redacted>",
        sanitizer.sanitize("body=passw%254Frd%252525253dCANARY_PERCENT_OVERDEPTH_MIXED_HEX_15"));
    assertEquals(
        "Authorization%252525253A <redacted>",
        sanitizer.sanitize("Authorization%252525253A Bearer CANARY_PERCENT_OVERDEPTH_HEADER_16"));
    assertEquals(
        "body=opaque%2525252520token=<redacted>",
        sanitizer.sanitize("body=opaque%2525252520token=CANARY_PERCENT_OVERDEPTH_BOUNDARY_17"));
  }

  @Test
  void percentCredentialScanLimitFailsClosedWithoutExposingTheValue() {
    String beyondScanLimit = "%" + "25".repeat(64) + "3D";
    String canary = "CANARY_PERCENT_BEYOND_SCAN_LIMIT_18";
    String sanitized = new PersistenceSanitizer().sanitize("body=token" + beyondScanLimit + canary);

    assertEquals(PersistenceSanitizer.FAILURE_MARKER, sanitized);
    assertFalse(sanitized.contains(canary));
  }

  @Test
  void exportSanitizerAlsoRedactsEncodedCredentialForms() {
    ExportSanitizer sanitizer = new ExportSanitizer();
    for (String payload : PAYLOADS) {
      String sanitized = sanitizer.sanitize(payload);
      assertNoCanary(sanitized);
      assertTrue(
          sanitized.contains("<redacted") || sanitized.contains("<redacted-credential>"),
          sanitized);
    }
  }

  @Test
  void nonCredentialPercentEncodingIsPreservedAndUrlPolicyIsUnchanged() {
    String ordinary = "progress=90%25 note=hello%20world code=%7Bplain%7D";
    String deepOrdinary = "opaque=%2525252541";
    String url = "https://example.invalid/a%2Fb?q=keep%20me";

    assertEquals(ordinary, new PersistenceSanitizer().sanitize(ordinary));
    assertEquals(deepOrdinary, new PersistenceSanitizer().sanitize(deepOrdinary));
    assertEquals(url, new PersistenceSanitizer().sanitize(url));

    ExportSanitizer export = new ExportSanitizer();
    assertEquals(ordinary, export.sanitize(ordinary));
    assertEquals(deepOrdinary, export.sanitize(deepOrdinary));
    String sanitizedUrl = export.sanitize(url);
    assertTrue(sanitizedUrl.contains("<redacted-url>"), sanitizedUrl);
    assertFalse(sanitizedUrl.contains("example.invalid"), sanitizedUrl);
  }

  private static void assertSanitized(PersistenceSanitizer sanitizer) {
    for (String payload : PAYLOADS) {
      String sanitized = sanitizer.sanitize(payload);
      assertNoCanary(sanitized);
      assertTrue(sanitized.contains("<redacted>"), sanitized);
    }
  }

  private static void assertNoCanary(String sanitized) {
    for (String canary : CANARIES) {
      assertFalse(sanitized.contains(canary), sanitized);
    }
  }

  private static String escapedEmbeddedJson(String canary) {
    String slash = "\\";
    String quote = "\"";
    return "payload={"
        + slash
        + quote
        + "password"
        + slash
        + quote
        + ":"
        + slash
        + quote
        + "prefix"
        + slash
        + slash
        + slash
        + quote
        + canary
        + slash
        + quote
        + "}";
  }
}
