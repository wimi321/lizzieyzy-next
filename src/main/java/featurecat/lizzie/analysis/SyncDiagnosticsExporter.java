package featurecat.lizzie.analysis;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SyncDiagnosticsExporter {
  private static final DateTimeFormatter FILE_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

  private final Path outputDirectory;

  public SyncDiagnosticsExporter(Path outputDirectory) {
    this.outputDirectory = outputDirectory;
  }

  public static Path defaultOutputDirectory() {
    return defaultApplicationDirectory().resolve("sync-diagnostics");
  }

  public Path export(SyncDiagnosticsExportSnapshot snapshot) throws IOException {
    SyncDiagnosticsExportSnapshot value =
        snapshot == null
            ? new SyncDiagnosticsExportSnapshot(
                System.currentTimeMillis(), null, null, null, null, null)
            : snapshot;
    Files.createDirectories(outputDirectory);
    Path zip =
        outputDirectory.resolve(
            "sync-diagnostics-"
                + FILE_TIMESTAMP.format(Instant.ofEpochMilli(value.getCapturedAtMillis()))
                + ".zip");
    SyncDiagnosticsExportSanitizer sanitizer = new SyncDiagnosticsExportSanitizer();
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      writeEntry(out, "summary.txt", renderSummary(value, sanitizer));
      writeEntry(out, "sync-context.json", renderSyncContext(value.getReport(), sanitizer));
      writeEntry(
          out, "yike-session.json", renderYike(value.getReport().getYikeSnapshot(), sanitizer));
      writeEntry(out, "recent-decisions.jsonl", renderDecisions(value, sanitizer));
      writeEntry(out, "readboard-protocol.log", renderProtocolLog(value, sanitizer));
      writeEntry(out, "yike-events.jsonl", renderYikeEvents(value, sanitizer));
      writeEntry(out, "environment.txt", renderEnvironment(value.getEnvironment(), sanitizer));
    }
    return zip;
  }

  private static void writeEntry(ZipOutputStream out, String name, String text) throws IOException {
    out.putNextEntry(new ZipEntry(name));
    out.write(text.getBytes(StandardCharsets.UTF_8));
    out.closeEntry();
  }

  private static Path defaultApplicationDirectory() {
    Path workingDirectory =
        Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    Path root = findLizzieDirectory(workingDirectory);
    if (root != null) {
      return root;
    }
    Path codeSource = codeSourceDirectory();
    root = findLizzieDirectory(codeSource);
    if (root != null) {
      return root;
    }
    return workingDirectory;
  }

  private static Path findLizzieDirectory(Path seed) {
    Path current = seed;
    for (int depth = 0; current != null && depth < 8; depth++) {
      if (isLizzieDirectory(current)) {
        return current;
      }
      current = current.getParent();
    }
    return null;
  }

  private static boolean isLizzieDirectory(Path path) {
    Path fileName = path.getFileName();
    if (fileName != null && "lizzieyzy-next".equalsIgnoreCase(fileName.toString())) {
      return true;
    }
    return Files.isRegularFile(path.resolve("pom.xml"))
        && Files.isDirectory(path.resolve("src/main/java/featurecat/lizzie"));
  }

  private static Path codeSourceDirectory() {
    try {
      CodeSource source = SyncDiagnosticsExporter.class.getProtectionDomain().getCodeSource();
      if (source == null || source.getLocation() == null) {
        return null;
      }
      Path location = Paths.get(source.getLocation().toURI()).toAbsolutePath().normalize();
      return Files.isRegularFile(location) ? location.getParent() : location;
    } catch (URISyntaxException | SecurityException e) {
      return null;
    }
  }

  private static String renderSummary(
      SyncDiagnosticsExportSnapshot snapshot, SyncDiagnosticsExportSanitizer sanitizer) {
    SyncDiagnosticsReport report = snapshot.getReport();
    SyncDiagnosticsSnapshot sync = report.getSyncSnapshot();
    YikeSessionDiagnosticsSnapshot yike = report.getYikeSnapshot();
    SyncDecisionTrace decision = report.getLatestDecisionTrace();
    StringBuilder text = new StringBuilder();
    text.append("Sync Diagnostics Export\n");
    text.append("capturedAtMillis: ").append(snapshot.getCapturedAtMillis()).append('\n');
    text.append("source: ").append(sanitizer.text(report.getSource())).append('\n');
    text.append('\n').append("ReadBoard").append('\n');
    text.append("  attached: ").append(sync.isReadBoardAttached()).append('\n');
    text.append("  connected: ").append(sync.isReadBoardConnected()).append('\n');
    text.append("  javaReadBoard: ").append(sync.isJavaReadBoard()).append('\n');
    text.append("  usePipe: ").append(sync.isUsePipe()).append('\n');
    text.append("  syncing: ").append(sync.isSyncing()).append('\n');
    text.append("  awaitingFirstSyncFrame: ").append(sync.isAwaitingFirstSyncFrame()).append('\n');
    text.append("  hasResumeState: ").append(sync.hasResumeState()).append('\n');
    text.append("  hasLastResolvedSnapshotNode: ")
        .append(sync.hasLastResolvedSnapshotNode())
        .append('\n');
    text.append("  syncAnalysisEpoch: ").append(sync.getSyncAnalysisEpoch()).append('\n');
    text.append("  pendingRemoteContext: ")
        .append(sanitizer.text(sync.getPendingRemoteContextSummary()))
        .append('\n');
    text.append("  lastResolvedSnapshot: ")
        .append(sanitizer.text(sync.getLastResolvedSnapshotSummary()))
        .append('\n');
    text.append("  lastProtocolLine: ")
        .append(sanitizer.text(sync.getLastProtocolLineSummary()))
        .append('\n');
    text.append("  lastProtocolTimestampMillis: ")
        .append(sync.getLastProtocolTimestampMillis())
        .append('\n');
    text.append("  source: ").append(sanitizer.text(sync.getSource())).append('\n');
    text.append("  timestampMillis: ").append(sync.getTimestampMillis()).append('\n');
    text.append("  summary: ").append(sanitizer.text(sync.getSummary())).append('\n');
    text.append('\n').append("Yike").append('\n');
    text.append("  currentRouteKind: ")
        .append(sanitizer.text(yike.getCurrentRouteKind()))
        .append('\n');
    text.append("  currentSession: ")
        .append(sanitizer.sessionAlias(yike.getCurrentSessionKey()))
        .append('\n');
    text.append("  active: ")
        .append(sanitizer.sessionAlias(yike.getActiveSessionKey()))
        .append(" syncReady=")
        .append(readinessText(yike.getActiveSyncReady()))
        .append(" geometryReady=")
        .append(readinessText(yike.getActiveGeometryReady()))
        .append(" boardSize=")
        .append(yike.getActiveBoardSize())
        .append('\n');
    text.append("  pending: ")
        .append(sanitizer.sessionAlias(yike.getPendingSessionKey()))
        .append(" syncReady=")
        .append(readinessText(yike.getPendingSyncReady()))
        .append(" geometryReady=")
        .append(readinessText(yike.getPendingGeometryReady()))
        .append(" boardSize=")
        .append(yike.getPendingBoardSize())
        .append('\n');
    text.append("  effectiveGeometry: ")
        .append(sanitizer.sessionAlias(yike.getEffectiveGeometrySessionKey()))
        .append(" ready=")
        .append(readinessText(yike.getEffectiveGeometryReady()))
        .append('\n');
    text.append("  lastGeometryClearReason: ")
        .append(sanitizer.text(yike.getLastGeometryClearReason()))
        .append('\n');
    text.append("  lastSessionSwitchReason: ")
        .append(sanitizer.text(yike.getLastSessionSwitchReason()))
        .append('\n');
    text.append("  lastYikeDebugEventSummary: ")
        .append(sanitizer.text(yike.getLastYikeDebugEventSummary()))
        .append('\n');
    text.append('\n').append("Latest decision").append('\n');
    text.append("  result: ").append(sanitizer.text(decision.getResult())).append('\n');
    text.append("  reason: ").append(sanitizer.text(decision.getReasonCode())).append('\n');
    text.append("  remoteContextFingerprint: ")
        .append(sanitizer.text(decision.getRemoteContextFingerprint()))
        .append('\n');
    text.append("  resolvedSnapshotKind: ")
        .append(sanitizer.text(decision.getResolvedSnapshotKind()))
        .append('\n');
    text.append('\n').append("Counts").append('\n');
    text.append("  recentProtocolEvents: ")
        .append(snapshot.getRecentProtocolEvents().size())
        .append('\n');
    text.append("  recentDecisionTraces: ")
        .append(snapshot.getRecentDecisionTraces().size())
        .append('\n');
    text.append("  recentYikeEvents: ").append(snapshot.getRecentYikeEvents().size()).append('\n');
    text.append("  exportTimestampMillis: ").append(snapshot.getCapturedAtMillis()).append('\n');
    return text.toString();
  }

  private static String renderSyncContext(
      SyncDiagnosticsReport report, SyncDiagnosticsExportSanitizer sanitizer) {
    SyncDiagnosticsSnapshot sync = report.getSyncSnapshot();
    return object(
        SyncDiagnosticsJson.field("readBoardAttached", sync.isReadBoardAttached()),
        SyncDiagnosticsJson.field("readBoardConnected", sync.isReadBoardConnected()),
        SyncDiagnosticsJson.field("javaReadBoard", sync.isJavaReadBoard()),
        SyncDiagnosticsJson.field("usePipe", sync.isUsePipe()),
        SyncDiagnosticsJson.field("syncing", sync.isSyncing()),
        SyncDiagnosticsJson.field("awaitingFirstSyncFrame", sync.isAwaitingFirstSyncFrame()),
        SyncDiagnosticsJson.field("hasResumeState", sync.hasResumeState()),
        SyncDiagnosticsJson.field(
            "hasLastResolvedSnapshotNode", sync.hasLastResolvedSnapshotNode()),
        SyncDiagnosticsJson.field("syncAnalysisEpoch", sync.getSyncAnalysisEpoch()),
        SyncDiagnosticsJson.field(
            "pendingRemoteContextSummary", sanitizer.text(sync.getPendingRemoteContextSummary())),
        SyncDiagnosticsJson.field(
            "lastResolvedSnapshotSummary", sanitizer.text(sync.getLastResolvedSnapshotSummary())),
        SyncDiagnosticsJson.field(
            "lastProtocolLineSummary", sanitizer.text(sync.getLastProtocolLineSummary())),
        SyncDiagnosticsJson.field(
            "lastProtocolTimestampMillis", sync.getLastProtocolTimestampMillis()),
        SyncDiagnosticsJson.field("timestampMillis", sync.getTimestampMillis()),
        SyncDiagnosticsJson.field("source", sanitizer.text(sync.getSource())),
        SyncDiagnosticsJson.field("summary", sanitizer.text(sync.getSummary())));
  }

  private static String renderYike(
      YikeSessionDiagnosticsSnapshot yike, SyncDiagnosticsExportSanitizer sanitizer) {
    return object(
        nullableBooleanField("listenerEnabled", yike.getListenerEnabled()),
        SyncDiagnosticsJson.field("currentRouteKind", sanitizer.text(yike.getCurrentRouteKind())),
        SyncDiagnosticsJson.field(
            "currentSessionAlias", sanitizer.sessionAlias(yike.getCurrentSessionKey())),
        SyncDiagnosticsJson.field(
            "activeSessionAlias", sanitizer.sessionAlias(yike.getActiveSessionKey())),
        nullableBooleanField("activeSyncReady", yike.getActiveSyncReady()),
        nullableBooleanField("activeGeometryReady", yike.getActiveGeometryReady()),
        SyncDiagnosticsJson.field("activeBoardSize", yike.getActiveBoardSize()),
        SyncDiagnosticsJson.field(
            "pendingSessionAlias", sanitizer.sessionAlias(yike.getPendingSessionKey())),
        nullableBooleanField("pendingSyncReady", yike.getPendingSyncReady()),
        nullableBooleanField("pendingGeometryReady", yike.getPendingGeometryReady()),
        SyncDiagnosticsJson.field("pendingBoardSize", yike.getPendingBoardSize()),
        SyncDiagnosticsJson.field(
            "effectiveGeometrySessionAlias",
            sanitizer.sessionAlias(yike.getEffectiveGeometrySessionKey())),
        nullableBooleanField("effectiveGeometryReady", yike.getEffectiveGeometryReady()),
        nullableBooleanField("placementGeometryAllowed", yike.getPlacementGeometryAllowed()),
        SyncDiagnosticsJson.field(
            "lastGeometryClearReason", sanitizer.text(yike.getLastGeometryClearReason())),
        SyncDiagnosticsJson.field(
            "lastSessionSwitchReason", sanitizer.text(yike.getLastSessionSwitchReason())),
        SyncDiagnosticsJson.field(
            "lastYikeDebugEventSummary", sanitizer.text(yike.getLastYikeDebugEventSummary())),
        SyncDiagnosticsJson.field("timestampMillis", yike.getTimestampMillis()),
        SyncDiagnosticsJson.field("source", sanitizer.text(yike.getSource())),
        SyncDiagnosticsJson.field("summary", sanitizer.text(yike.getSummary())));
  }

  private static String renderDecisions(
      SyncDiagnosticsExportSnapshot snapshot, SyncDiagnosticsExportSanitizer sanitizer) {
    StringBuilder text = new StringBuilder();
    for (SyncDecisionTrace decision : snapshot.getRecentDecisionTraces()) {
      text.append(renderDecision(decision, sanitizer)).append('\n');
    }
    return text.toString();
  }

  private static String renderDecision(
      SyncDecisionTrace decision, SyncDiagnosticsExportSanitizer sanitizer) {
    return object(
        SyncDiagnosticsJson.field("timestampMillis", decision.getTimestampMillis()),
        SyncDiagnosticsJson.field("result", sanitizer.text(decision.getResult())),
        SyncDiagnosticsJson.field("reasonCode", sanitizer.text(decision.getReasonCode())),
        SyncDiagnosticsJson.field("platform", sanitizer.text(decision.getPlatform())),
        SyncDiagnosticsJson.field("windowKind", sanitizer.text(decision.getWindowKind())),
        SyncDiagnosticsJson.field(
            "remoteContextFingerprint", sanitizer.text(decision.getRemoteContextFingerprint())),
        SyncDiagnosticsJson.field("snapshotHash", sanitizer.text(decision.getSnapshotHash())),
        SyncDiagnosticsJson.field("changedStoneCount", decision.getChangedStoneCount()),
        SyncDiagnosticsJson.field("removedStoneCount", decision.getRemovedStoneCount()),
        SyncDiagnosticsJson.field("recoveryMoveNumber", decision.getRecoveryMoveNumber()),
        SyncDiagnosticsJson.field(
            "resolvedSnapshotMoveNumber", decision.getResolvedSnapshotMoveNumber()),
        SyncDiagnosticsJson.field(
            "resolvedSnapshotKind", sanitizer.text(decision.getResolvedSnapshotKind())),
        SyncDiagnosticsJson.field("forceRebuildRequested", decision.isForceRebuildRequested()),
        SyncDiagnosticsJson.field("firstSyncFrame", decision.isFirstSyncFrame()),
        SyncDiagnosticsJson.field("shouldResumeAnalysis", decision.shouldResumeAnalysis()),
        SyncDiagnosticsJson.field("epoch", decision.getEpoch()));
  }

  private static String renderProtocolLog(
      SyncDiagnosticsExportSnapshot snapshot, SyncDiagnosticsExportSanitizer sanitizer) {
    StringBuilder text = new StringBuilder();
    for (SyncProtocolDiagnosticEvent event : snapshot.getRecentProtocolEvents()) {
      text.append(event.getTimestampMillis())
          .append('\t')
          .append(sanitizer.text(event.getSummary()))
          .append('\n');
    }
    return text.toString();
  }

  private static String renderYikeEvents(
      SyncDiagnosticsExportSnapshot snapshot, SyncDiagnosticsExportSanitizer sanitizer) {
    StringBuilder text = new StringBuilder();
    for (YikeSessionDiagnosticsSnapshot yike : snapshot.getRecentYikeEvents()) {
      text.append(renderYike(yike, sanitizer)).append('\n');
    }
    return text.toString();
  }

  private static String renderEnvironment(
      SyncDiagnosticsEnvironment environment, SyncDiagnosticsExportSanitizer sanitizer) {
    StringBuilder text = new StringBuilder();
    text.append("appVersion: ").append(sanitizer.text(environment.getAppVersion())).append('\n');
    text.append("javaVersion: ").append(sanitizer.text(environment.getJavaVersion())).append('\n');
    text.append("osName: ").append(sanitizer.text(environment.getOsName())).append('\n');
    text.append("osVersion: ").append(sanitizer.text(environment.getOsVersion())).append('\n');
    text.append("osArch: ").append(sanitizer.text(environment.getOsArch())).append('\n');
    text.append("userDirSanitized: ")
        .append(sanitizer.path(environment.getUserDirSanitized()))
        .append('\n');
    text.append("timestampMillis: ").append(environment.getTimestampMillis()).append('\n');
    return text.toString();
  }

  private static String nullableBooleanField(String name, Boolean value) {
    return SyncDiagnosticsJson.quote(name) + ":" + (value == null ? "null" : value.toString());
  }

  private static String object(String... fields) {
    List<String> values = new ArrayList<>();
    for (String field : fields) {
      if (field != null && !field.isEmpty()) {
        values.add(field);
      }
    }
    return "{" + String.join(",", values) + "}";
  }

  private static String readinessText(Boolean value) {
    return value == null ? "unknown" : value.toString();
  }
}
