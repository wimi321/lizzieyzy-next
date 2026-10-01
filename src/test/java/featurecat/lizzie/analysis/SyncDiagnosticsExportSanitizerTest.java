package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.logging.PersistenceSanitizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyncDiagnosticsExportSanitizerTest {
  @Test
  void redactsWholeSecretLinesWithoutChangingNeighborsOrLineEndings() {
    String analysis = "info move D4 visits 100 winrate 0.5 pv " + "D4 Q16 ".repeat(2400);
    for (String ending : List.of("\n", "\r\n", "\r")) {
      for (String word : List.of("secret", "SECRET", "SeCrEt", "prefixsecretSuffix")) {
        String input =
            analysis + ending + "  " + word + " private detail  " + ending + analysis;
        String expected = analysis + ending + "<redacted-secret>" + ending + analysis;
        SyncDiagnosticsExportSanitizer sanitizer = new SyncDiagnosticsExportSanitizer();
        assertEquals(expected, sanitizer.text(input));
        assertEquals(expected, sanitizer.text(input));
      }
    }
    assertEquals(analysis, new SyncDiagnosticsExportSanitizer().text(analysis));
    assertEquals(
        "\r\n<redacted-secret>\r\n\n<redacted-secret>\r<redacted-secret>",
        new SyncDiagnosticsExportSanitizer()
            .text("\r\ndata secret tail\r\n\ndata SECRET tail\rdata SeCrEt tail"));
    // The existing regex uses ASCII case folding, not Unicode case folding.
    assertEquals("ſecret", new SyncDiagnosticsExportSanitizer().text("ſecret"));
  }

  @Test
  void keepsCredentialPathAndAliasProcessingBeforeWholeLineRedaction() {
    SyncDiagnosticsExportSanitizer sanitizer = new SyncDiagnosticsExportSanitizer();
    assertEquals(
        "password=<redacted>\npath=/home/<user>\n<redacted-secret>\nlive-room#1",
        sanitizer.text(
            "password=CANARY_VALUE\npath=/home/alice/log.txt\n"
                + "SeCrEt live-room:private-42\nlive-room:private-42"));
    assertEquals("live-room#1", sanitizer.sessionAlias("live-room:private-42"));
  }

  @Test
  void aliasesCompleteSessionKeysExactlyOnceAndKeepsRegistrationOrder() {
    SyncDiagnosticsExportSanitizer sanitizer = new SyncDiagnosticsExportSanitizer();

    assertEquals(
        "yike session=(live-room#1), next=unite-board#1.",
        sanitizer.text(
            "yike session=(live-room:private-room_42), next=unite-board:board-7."));
    assertEquals("live-room#1", sanitizer.sessionAlias(" \tlive-room:private-room_42\r\n"));
    assertEquals(2, sanitizer.aliases().size());
    assertEquals(
        List.of("live-room#1", "unite-board#1"),
        List.copyOf(sanitizer.aliases().values()));
  }

  @Test
  void preservesTextWhitespaceWhileRedactingCredentials() {
    SyncDiagnosticsExportSanitizer sanitizer = new SyncDiagnosticsExportSanitizer();

    assertEquals(" \t\r\n\n", sanitizer.text(" \t\r\n\n"));
    assertEquals(
        "\tpassword=<redacted> \r\n\n",
        sanitizer.text("\tpassword=CANARY_TEXT_WHITESPACE \r\n\n"));
  }

  @Test
  void preservesWholeUuidIdentifiersWithoutTreatingNumericPrefixesAsRoomIds() {
    SyncDiagnosticsExportSanitizer sanitizer = new SyncDiagnosticsExportSanitizer();
    String applicationSession = "20141286-de61-49e0-99f3-fcfa0dcb6c1e";

    String sanitized =
        sanitizer.text(
            "session="
                + applicationSession
                + " id="
                + applicationSession
                + " yike session=live-room:186538 id=186538");

    assertEquals(
        "session="
            + applicationSession
            + " id="
            + applicationSession
            + " yike session=live-room#1 id=live-room#1",
        sanitized);
    assertTrue(sanitized.contains("session=" + applicationSession), sanitized);
    assertTrue(sanitized.contains("id=" + applicationSession), sanitized);
    assertEquals(List.of("live-room#1"), List.copyOf(sanitizer.aliases().values()));
  }

  @Test
  void aliasesWholeAlphanumericIdentifierWithoutRewritingNumericPrefixes() {
    SyncDiagnosticsExportSanitizer sanitizer = new SyncDiagnosticsExportSanitizer();

    assertEquals(
        "id=186538-private session=live-room#1",
        sanitizer.text("id=186538-private session=live-room:186538-private"));
    assertEquals(List.of("live-room#1"), List.copyOf(sanitizer.aliases().values()));
  }

  @Test
  void redactsRemoteComputeCredentialsInCommonDiagnosticFormats() {
    String sanitized =
        new SyncDiagnosticsExportSanitizer()
            .text(
                "{\"password\":\"plain-password\",\"connectPassword\":\"socket-password\","
                    + "\"zhizi-account-token\":\"account-token-value\","
                    + "\"zz-socketio-token\":\"socket-token-value\"} "
                    + "Authorization: Bearer bearer-token-value password=query-password");

    for (String secret :
        new String[] {
          "plain-password",
          "socket-password",
          "account-token-value",
          "socket-token-value",
          "bearer-token-value",
          "query-password"
        }) {
      assertFalse(sanitized.contains(secret), secret);
    }
    assertTrue(sanitized.contains("<redacted-credential>"));
  }

  @Test
  void redactsEscapedCredentialSyntaxUsedByEmbeddedJson() {
    String sanitized =
        new SyncDiagnosticsExportSanitizer()
            .text(
                "payload={\\\"password\\\":\\\"CANARY_ESCAPED_SYNC_01\\\"} "
                    + "Authorization\\u003a Bearer CANARY_ESCAPED_SYNC_02 "
                    + "payload={\"password\":\"prefix\\\"CANARY_RAW_QUOTED_SYNC_03\"} "
                    + escapedEmbeddedJson("CANARY_EMBEDDED_QUOTED_SYNC_04")
                    + " passw\\u006frd=CANARY_UNICODE_NAME_SYNC_05");

    for (String canary :
        new String[] {
          "CANARY_ESCAPED_SYNC_01",
          "CANARY_ESCAPED_SYNC_02",
          "CANARY_RAW_QUOTED_SYNC_03",
          "CANARY_EMBEDDED_QUOTED_SYNC_04",
          "CANARY_UNICODE_NAME_SYNC_05"
        }) {
      assertFalse(sanitized.contains(canary), sanitized);
    }
    assertTrue(sanitized.contains("<redacted>"), sanitized);
    assertFalse(sanitized.contains(PersistenceSanitizer.FAILURE_MARKER), sanitized);
  }

  @Test
  void redactsWindowsAbsolutePathFamiliesButPreservesNonPathDoubleBackslashes() {
    assertWindowsPathRedacted(
        "unc=\\\\UNC_SERVER_CANARY\\UNC_SHARE_CANARY\\UNC_USER_CANARY\\UNC_PATH_CANARY\\file.txt",
        "UNC_SERVER_CANARY",
        "UNC_SHARE_CANARY",
        "UNC_USER_CANARY",
        "UNC_PATH_CANARY");
    assertWindowsPathRedacted(
        "escapedUnc=\\\\\\\\ESC_UNC_SERVER_CANARY\\\\ESC_UNC_SHARE_CANARY\\\\ESC_UNC_USER_CANARY\\\\ESC_UNC_PATH_CANARY\\\\file.txt",
        "ESC_UNC_SERVER_CANARY",
        "ESC_UNC_SHARE_CANARY",
        "ESC_UNC_USER_CANARY",
        "ESC_UNC_PATH_CANARY");
    assertWindowsPathRedacted(
        "wsl=\\\\wsl.localhost\\WSL_DISTRO_CANARY\\home\\WSL_USER_CANARY\\WSL_PATH_CANARY\\file.txt",
        "WSL_DISTRO_CANARY",
        "WSL_USER_CANARY",
        "WSL_PATH_CANARY");
    assertWindowsPathRedacted(
        "extended=\\\\?\\UNC\\EXT_SERVER_CANARY\\EXT_SHARE_CANARY\\EXT_USER_CANARY\\EXT_PATH_CANARY\\file.txt",
        "EXT_SERVER_CANARY",
        "EXT_SHARE_CANARY",
        "EXT_USER_CANARY",
        "EXT_PATH_CANARY");
    assertWindowsPathRedacted(
        "drive=\"Z:\\DRIVE_ROOT_CANARY\\DRIVE USER CANARY\\DRIVE_PATH_CANARY\\file.txt\"",
        "DRIVE_ROOT_CANARY",
        "DRIVE USER CANARY",
        "DRIVE_PATH_CANARY");
    assertWindowsPathRedacted(
        "escapedDrive=Z:\\\\ESC_DRIVE_ROOT_CANARY\\\\ESC_DRIVE_USER_CANARY\\\\ESC_DRIVE_PATH_CANARY\\\\file.txt",
        "ESC_DRIVE_ROOT_CANARY",
        "ESC_DRIVE_USER_CANARY",
        "ESC_DRIVE_PATH_CANARY");

    assertEquals(
        "<redacted-path>",
        SyncDiagnosticsExportSanitizer.redactEmbeddedWindowsAbsolutePaths(
            "\\UPSTREAM_SERVER_CANARY\\UPSTREAM_SHARE_CANARY\\file.txt"));

    String sanitized =
        new SyncDiagnosticsExportSanitizer()
            .text(
                "nonPath=alpha\\\\beta\\\\gamma incomplete=\\\\single-component\n"
                    + "url=https://example.test/status\n"
                    + "escapedUrl=https:\\/\\/example.test/status");
    assertTrue(sanitized.contains("nonPath=alpha\\\\beta\\\\gamma"), sanitized);
    assertTrue(sanitized.contains("incomplete=\\\\single-component"), sanitized);
    assertTrue(sanitized.contains("url=<redacted-url>"), sanitized);
    assertTrue(sanitized.contains("escapedUrl=<redacted-url>"), sanitized);

    String regex =
        new SyncDiagnosticsExportSanitizer().text("regex=^\\\\d+\\\\s+$");
    assertEquals("regex=^\\\\d+\\\\s+$", regex);
    assertFalse(regex.contains("<redacted-path>"), regex);

    String jsonRegex =
        new SyncDiagnosticsExportSanitizer()
            .text("jsonRegex={\\\"pattern\\\":\\\"^\\\\\\\\d+\\\\\\\\s+$\\\"}");
    assertEquals("jsonRegex={\"pattern\":\"^\\\\d+\\\\s+$\"}", jsonRegex);
    assertFalse(jsonRegex.contains("<redacted-path>"), jsonRegex);
  }

  private static void assertWindowsPathRedacted(String input, String... canaries) {
    String sanitized = new SyncDiagnosticsExportSanitizer().text(input);
    assertTrue(sanitized.contains("<redacted-path>"), sanitized);
    for (String canary : canaries) {
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
