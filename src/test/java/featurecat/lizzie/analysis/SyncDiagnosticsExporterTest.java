package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyncDiagnosticsExporterTest {
  @TempDir Path tempDir;

  @Test
  void exportWritesFixedZipLayout() throws IOException {
    Path zip = new SyncDiagnosticsExporter(tempDir).export(sensitiveSnapshot());

    assertEquals(
        Set.of(
            "environment.txt",
            "readboard-protocol.log",
            "recent-decisions.jsonl",
            "summary.txt",
            "sync-context.json",
            "yike-events.jsonl",
            "yike-session.json"),
        zipEntries(zip));
  }

  @Test
  void exportRedactsSensitiveValuesAcrossWholeZip() throws IOException {
    Path zip = new SyncDiagnosticsExporter(tempDir).export(sensitiveSnapshot());
    Map<String, String> entries = unzipTextEntries(zip);
    String allText = String.join("\n", entries.values());

    for (String raw :
        List.of(
            "(;GM[1]SZ[19])",
            "secret-room-token",
            "live-room:186538",
            "https://www.yikeweiqi.com/live/186538",
            "User Secret Room Title",
            "C:\\Users\\alice\\Lizzie",
            "/mnt/c/Users/alice/Lizzie",
            "\\\\wsl.localhost\\Ubuntu\\home\\alice\\dev",
            "/Users/alice/Lizzie",
            "C:\\secret\\file.zip",
            "/var/tmp/alice/lizzie",
            "relative/parent/file.sgf")) {
      assertFalse(allText.contains(raw), raw);
    }

    for (String leaked :
        List.of(
            "alice",
            "Users\\alice",
            "Users\\\\alice",
            "home\\alice",
            "home\\\\alice",
            "/Users/alice",
            "/home/alice",
            "/mnt/c/Users/alice")) {
      assertFalse(allText.contains(leaked), leaked);
    }

    assertTrue(allText.contains("live-room#1"));
    assertTrue(allText.contains("C:\\Users\\<user>"));
    assertTrue(allText.contains("/mnt/c/Users/<user>"));
    assertTrue(allText.contains("\\\\wsl.localhost\\Ubuntu\\home\\<user>"));
    assertTrue(allText.contains("/Users/<user>"));
    assertTrue(allText.contains("<redacted-path>"));
    assertTrue(allText.contains("file.sgf"));
    assertFalse(allText.contains("relative/parent"));
  }

  @Test
  void defaultOutputDirectoryUsesUserHomeApplicationDiagnosticsDirectory() {
    String originalUserHome = System.getProperty("user.home");
    try {
      System.setProperty("user.home", tempDir.toString());

      assertEquals(
          tempDir.resolve(".lizzie-yzy").resolve("sync-diagnostics"),
          SyncDiagnosticsExporter.defaultOutputDirectory());
    } finally {
      if (originalUserHome == null) {
        System.clearProperty("user.home");
      } else {
        System.setProperty("user.home", originalUserHome);
      }
    }
  }

  private static SyncDiagnosticsExportSnapshot sensitiveSnapshot() {
    SyncDiagnosticsSnapshot sync =
        SyncDiagnosticsSnapshot.builder()
            .readBoardAttached(true)
            .readBoardConnected(true)
            .javaReadBoard(true)
            .usePipe(false)
            .syncing(true)
            .awaitingFirstSyncFrame(false)
            .hasResumeState(true)
            .hasLastResolvedSnapshotNode(true)
            .syncAnalysisEpoch(7L)
            .pendingRemoteContextSummary(
                "sgf=(;GM[1]SZ[19]) token=secret-room-token path=C:\\Users\\alice\\Lizzie")
            .lastResolvedSnapshotSummary("url=https://www.yikeweiqi.com/live/186538")
            .lastProtocolLineSummary("window=User Secret Room Title")
            .lastProtocolTimestampMillis(110L)
            .timestampMillis(100L)
            .source("test")
            .summary("/mnt/c/Users/alice/Lizzie")
            .build();
    YikeSessionDiagnosticsSnapshot yike =
        YikeSessionDiagnosticsSnapshot.builder()
            .listenerEnabled(true)
            .currentRouteKind("live-room")
            .currentSessionKey("live-room:186538")
            .activeSessionKey("live-room:186538")
            .activeSyncReady(true)
            .activeGeometryReady(true)
            .activeBoardSize(19)
            .pendingSessionKey("live-room:186538")
            .pendingSyncReady(false)
            .pendingGeometryReady(false)
            .pendingBoardSize(19)
            .effectiveGeometrySessionKey("live-room:186538")
            .effectiveGeometryReady(true)
            .placementGeometryAllowed(true)
            .lastGeometryClearReason("User Secret Room Title")
            .lastSessionSwitchReason("https://www.yikeweiqi.com/live/186538")
            .lastYikeDebugEventSummary("token=secret-room-token")
            .timestampMillis(120L)
            .source("test")
            .summary("session live-room:186538")
            .build();
    SyncDecisionTrace decision =
        SyncDecisionTrace.builder("HOLD", "conflict_hold")
            .platform("yike")
            .windowKind("live-room")
            .remoteContextFingerprint("route=live-room syncReady=true move=42")
            .snapshotHash("safe-hash")
            .changedStoneCount(1)
            .removedStoneCount(0)
            .recoveryMoveNumber(42)
            .resolvedSnapshotMoveNumber(41)
            .resolvedSnapshotKind("SNAPSHOT")
            .forceRebuildRequested(false)
            .firstSyncFrame(false)
            .shouldResumeAnalysis(true)
            .timestampMillis(130L)
            .epoch(7L)
            .summary("C:\\secret\\file.zip")
            .source("test")
            .build();

    return new SyncDiagnosticsExportSnapshot(
        1767225599000L,
        SyncDiagnosticsReport.builder()
            .syncSnapshot(sync)
            .yikeSnapshot(yike)
            .latestDecisionTrace(decision)
            .capturedAtMillis(1767225599000L)
            .source("test")
            .build(),
        List.of(
            SyncProtocolDiagnosticEvent.of(140L, "raw /Users/alice/Lizzie", "test"),
            SyncProtocolDiagnosticEvent.of(141L, "C:\\secret\\file.zip", "test"),
            SyncProtocolDiagnosticEvent.of(142L, "/var/tmp/alice/lizzie", "test"),
            SyncProtocolDiagnosticEvent.of(143L, "relative/parent/file.sgf", "test")),
        List.of(decision),
        List.of(yike),
        SyncDiagnosticsEnvironment.of(
            "test-version",
            "17",
            "Linux",
            "test-os",
            "x86_64",
            "\\\\wsl.localhost\\Ubuntu\\home\\alice\\dev",
            150L));
  }

  private static Set<String> zipEntries(Path zip) throws IOException {
    return new TreeSet<>(unzipTextEntries(zip).keySet());
  }

  private static Map<String, String> unzipTextEntries(Path zip) throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        if (!entry.isDirectory()) {
          entries.put(entry.getName(), new String(readEntry(input), StandardCharsets.UTF_8));
        }
      }
    }
    return entries;
  }

  private static byte[] readEntry(ZipInputStream input) throws IOException {
    byte[] buffer = new byte[4096];
    List<byte[]> chunks = new ArrayList<>();
    int total = 0;
    int count;
    while ((count = input.read(buffer)) != -1) {
      byte[] chunk = new byte[count];
      System.arraycopy(buffer, 0, chunk, 0, count);
      chunks.add(chunk);
      total += count;
    }
    byte[] all = new byte[total];
    int offset = 0;
    for (byte[] chunk : chunks) {
      System.arraycopy(chunk, 0, all, offset, chunk.length);
      offset += chunk.length;
    }
    return all;
  }
}
