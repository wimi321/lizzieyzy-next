package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisCandidateValidator;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.TraceScope;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.BoardNodeKind;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.Utils;
import java.awt.Dialog;
import java.awt.Window;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Explicit Linux/Xvfb acceptance for a catalog-pinned production-owned KataGo CPU process. */
public final class RealCpuEngineAcceptanceIT {
  private static final Path CATALOG =
      Path.of("src/main/resources/katago-assets.json").toAbsolutePath().normalize();
  private static final Path FIXTURE =
      Path.of("src/test/resources/featurecat/lizzie/gui/d4-real-cpu-snapshot.sgf")
          .toAbsolutePath()
          .normalize();
  private static final Duration STARTUP_BUDGET = Duration.ofSeconds(60);
  private static final Duration POSITION_BUDGET = Duration.ofSeconds(30);
  // The pinned flagship Transformer took 83 seconds for the root alone on hosted Eigen CPU.
  // Allow a bounded cold search, but still require positive, legal visits on the exact node.
  private static final Duration ANALYSIS_BUDGET = Duration.ofSeconds(300);
  private static final Duration STOP_BUDGET = Duration.ofSeconds(10);
  private static final Duration QUIT_BUDGET = Duration.ofSeconds(15);
  private static final Duration CLEANUP_BUDGET = Duration.ofSeconds(10);
  private static final long PROBE_BUDGET_SECONDS = 450;

  @Test
  void analyzesPinnedCpuEngineThroughProductionOwnership() throws Exception {
    assumeTrue(explicitExecutionRequested(), "real CPU acceptance is opt-in");
    Inputs inputs = Inputs.load();
    System.setProperty("lizzie.desktop.required", "true");
    DesktopProbeProcess.requireDisplay();
    assertTrue(Files.isRegularFile(FIXTURE), FIXTURE.toString());

    SourceIdentity source = sourceIdentity();
    String engineId = UUID.randomUUID().toString();
    Path result =
        DesktopProbeProcess.run(
            RealCpuEngineAcceptanceIT.class,
            "real-cpu-engine",
            List.of(),
            List.of(
                "probe",
                FIXTURE.toString(),
                inputs.engine.toString(),
                inputs.model.toString(),
                inputs.config.toString(),
                inputs.manifestPath.toString(),
                engineId,
                source.commit,
                Boolean.toString(source.dirty)),
            PROBE_BUDGET_SECONDS);

    JSONObject record = new JSONObject(Files.readString(result, StandardCharsets.UTF_8));
    validateResult(record, inputs, source, engineId, result);
  }

  /** Child-process entry point. It exits only after the owned engine has settled. */
  public static void main(String[] args) throws Exception {
    if (args.length != 11 || !"probe".equals(args[0])) {
      throw new IllegalArgumentException(
          "expected probe, fixture, engine, model, config, manifest, engine ID, source commit, dirty, work, result");
    }
    Path fixture = Path.of(args[1]).toAbsolutePath().normalize();
    Inputs inputs =
        Inputs.load(
            Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5]));
    String engineId = args[6];
    SourceIdentity source = new SourceIdentity(args[7], Boolean.parseBoolean(args[8]));
    Path work = Path.of(args[9]).toAbsolutePath().normalize();
    Path result = Path.of(args[10]).toAbsolutePath().normalize();
    ProbeState state = new ProbeState();
    SnapshotObserver snapshots = null;
    int exitCode = 1;
    try {
      Files.createDirectories(work);
      Path snapshotDirectory = Files.createDirectory(work.resolve("snapshots"));
      System.setProperty("java.io.tmpdir", snapshotDirectory.toString());
      snapshots = new SnapshotObserver(snapshotDirectory);
      snapshots.start();
      runProbe(fixture, inputs, engineId, source, work, result, snapshots, state);
      exitCode = 0;
    } catch (Throwable failure) {
      try {
        String diagnostics = "phase=" + state.phase + "\n";
        if (state.engine != null) {
          diagnostics += "pondering=" + state.engine.isPondering()
              + "\nengineVisits=" + state.engine.getBestMovesPlayouts()
              + "\nnodeVisits=" + (state.target == null ? -1 : state.target.getData().getPlayouts()) + "\n";
        }
        for (java.lang.management.ThreadInfo thread :
            java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
          diagnostics += thread + "\n";
        }
        Files.writeString(result.resolveSibling("failure-diagnostic.txt"), diagnostics);
      } catch (Throwable diagnosticFailure) {
        failure.addSuppressed(diagnosticFailure);
      }
      CleanupOutcome cleanup = cleanupAfterFailure(state, snapshots);
      JSONObject failureResult =
          failureRecord(fixture, inputs, engineId, source, work, result, state, cleanup, failure);
      validateRecordShape(failureResult);
      writeResult(result, failureResult);
      failure.printStackTrace(System.err);
    } finally {
      if (snapshots != null) snapshots.close();
      System.exit(exitCode);
    }
  }

  private static void runProbe(
      Path fixture,
      Inputs inputs,
      String engineId,
      SourceIdentity source,
      Path work,
      Path result,
      SnapshotObserver snapshots,
      ProbeState state)
      throws Exception {
    long totalStart = System.nanoTime();
    state.totalStart = totalStart;
    writeIsolatedConfig(work);
    System.setProperty("lizzie.work.dir", work.toString());

    phase(state, result, "production-startup");
    long startupStart = System.nanoTime();
    Deadline startupDeadline = Deadline.after(STARTUP_BUDGET);
    callWithin(
        () -> {
          Lizzie.main(new String[0]);
          return null;
        },
        startupDeadline,
        "production application startup");
    await(
        () -> Lizzie.frame != null && Lizzie.frame.isShowing(),
        startupDeadline,
        "real main window");
    dismissDialogs();
    LoggingRuntime.current().orElseThrow().startFullTrace(Set.of(TraceScope.ENGINE_GTP));

    long positionStart = System.nanoTime();
    Deadline positionDeadline = Deadline.after(POSITION_BUDGET);
    AtomicBoolean loaded = new AtomicBoolean();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            loaded.set(SGFParser.load(fixture.toString(), false, false));
          } catch (IOException failure) {
            throw new RuntimeException(failure);
          }
        });
    if (!loaded.get()) {
      throw new AssertionError("production SGF load rejected the D4 fixture");
    }
    await(
        () -> !Lizzie.board.isLoadingFile,
        startupDeadline.min(positionDeadline),
        "fixture adoption");
    BoardHistoryList history = Lizzie.board.getHistory();
    BoardHistoryList.SessionRulesTarget rulesTarget = SGFParser.adoptCurrentHistoryExternalRules();
    BoardHistoryNode capturedTarget = history.getCurrentHistoryNode();
    assertApplicationPosition(history, capturedTarget, rulesTarget, false);
    state.target = capturedTarget;
    state.rulesTarget = rulesTarget;

    String command = command(inputs.engine, inputs.model, inputs.config);
    EngineManager manager = Lizzie.engineManager;
    int index = manager.engineList.size();
    EngineData entry = catalogEntry(index, engineId, command);
    Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
    CatalogNamedLeelaz real = new CatalogNamedLeelaz(entry);
    assertEquals(command, real.engineCommand(), "catalog command must remain exact");
    state.engine = real;
    manager.engineList.add(real);

    phase(state, result, "production-engine-switch");
    Deadline startupAndPositionDeadline = startupDeadline.min(positionDeadline);
    if (!callWithin(
        () -> manager.switchEngineIfAvailable(index, true),
        startupAndPositionDeadline,
        "production engine switch")) {
      throw new AssertionError("production engine switch rejected the D4 catalog entry");
    }
    ProcessHandle peer = awaitOwnedPeer(real, inputs, startupAndPositionDeadline);
    state.peer = peer;
    ReaderThreads readers = awaitReaderThreads(startupAndPositionDeadline);
    state.readers = readers;
    await(
        () ->
            real.isLoaded()
                && manager.isEngineSwitchActive(index, true)
                && confirmedChineseRules(real, rulesTarget)
                && Lizzie.board.getHistory() == history
                && history.getCurrentHistoryNode() == capturedTarget,
        startupAndPositionDeadline,
        "fresh rules and position confirmation");
    assertApplicationPosition(history, capturedTarget, rulesTarget, false);
    EngineRulesResult actualRules = real.engineRulesResult();
    state.actualRules = actualRules;
    state.positionConfirmed = true;
    long startupMillis = elapsedMillis(startupStart);
    long positionMillis = elapsedMillis(positionStart);
    state.startupMillis = startupMillis;
    state.positionMillis = positionMillis;

    phase(state, result, "positive-visits");
    long analysisStart = System.nanoTime();
    Deadline analysisDeadline = Deadline.after(ANALYSIS_BUDGET);
    AtomicReference<MoveData> firstCandidate = new AtomicReference<>();
    AtomicBoolean firstOutput = new AtomicBoolean();
    await(
        () -> {
          if (!real.getBestMoves().isEmpty() && firstOutput.compareAndSet(false, true)) {
            state.firstOutputMillis = elapsedMillis(analysisStart);
          }
          Optional<MoveData> candidate = positiveLegalCandidate(capturedTarget);
          candidate.ifPresent(value -> firstCandidate.compareAndSet(null, value));
          return real.isPondering()
              && history.getCurrentHistoryNode() == capturedTarget
              && candidate.isPresent();
        },
        analysisDeadline,
        "positive visits on the frozen current node");
    MoveData candidate = firstCandidate.get();
    state.candidate = candidate;
    assertApplicationPosition(history, capturedTarget, rulesTarget, true);
    long analysisMillis = elapsedMillis(analysisStart);
    state.analysisMillis = analysisMillis;
    Files.writeString(
        result.resolveSibling("cpu-timing.json"),
        new JSONObject()
            .put("availableProcessors", Runtime.getRuntime().availableProcessors())
            .put("coldAnalysisBudgetMs", ANALYSIS_BUDGET.toMillis())
            .put("firstOutputMs", state.firstOutputMillis)
            .put("firstPositiveVisitsMs", analysisMillis)
            .put("firstPositiveVisits", candidate.playouts)
            .put("purpose", "functional acceptance, not a throughput benchmark")
            .toString(2));

    phase(state, result, "analysis-stop");
    long stopStart = System.nanoTime();
    Deadline stopDeadline = Deadline.after(STOP_BUDGET);
    if (!real.isPondering()) {
      throw new AssertionError("real engine stopped before the bounded stop stimulus");
    }
    state.stopRequested = true;
    real.togglePonder();
    await(() -> !real.isPondering(), stopDeadline, "production analysis stop");
    QuietState quiet = awaitAnalysisQuiescence(real, capturedTarget, stopDeadline);
    state.quiet = quiet;
    long stopMillis = elapsedMillis(stopStart);
    state.stopMillis = stopMillis;

    List<Path> staged = snapshots.paths();
    if (staged.isEmpty()) {
      throw new AssertionError("no staged exact-snapshot SGF was observed");
    }

    phase(state, result, "normal-quit");
    long quitStart = System.nanoTime();
    Deadline quitDeadline = Deadline.after(QUIT_BUDGET);
    callWithin(
        () -> {
          real.normalQuit();
          state.normalQuit = true;
          return null;
        },
        quitDeadline,
        "production normal quit");
    await(
        () -> !peer.isAlive() && !real.isStarted(),
        quitDeadline,
        "owned production process exit");
    long quitMillis = elapsedMillis(quitStart);
    state.quitMillis = quitMillis;

    phase(state, result, "owned-cleanup");
    long cleanupStart = System.nanoTime();
    Deadline cleanupDeadline = Deadline.after(CLEANUP_BUDGET);
    await(readers::terminated, cleanupDeadline, "production reader termination");
    await(
        () -> staged.stream().noneMatch(Files::exists),
        cleanupDeadline,
        "staged snapshot deletion");
    long cleanupMillis = elapsedMillis(cleanupStart);
    snapshots.close();

    Path appLog = work.resolve("logs/app.log");
    await(() -> Files.isRegularFile(appLog), cleanupDeadline, "application lifecycle log");
    Path stdout = result.getParent().resolve("stdout.log");
    Path stderr = result.getParent().resolve("stderr.log");
    Path phases = result.resolveSibling("phases.log");

    JSONObject record =
        new JSONObject()
            .put("schemaVersion", 1)
            .put("scenario", "real-cpu-engine")
            .put("status", "PASS")
            .put("failure", JSONObject.NULL)
            .put(
                "peer",
                new JSONObject()
                    .put("kind", "real-katago-cpu")
                    .put("catalogEngineId", engineId)
                    .put("command", command)
                    .put("pid", peer.pid())
                    .put("stdoutReader", readers.identity(readers.stdout))
                    .put("stderrReader", readers.identity(readers.stderr)))
            .put(
                "source",
                new JSONObject().put("commit", source.commit).put("dirty", source.dirty))
            .put(
                "platform",
                new JSONObject()
                    .put("os", System.getProperty("os.name"))
                    .put("arch", System.getProperty("os.arch"))
                    .put("jvmVersion", System.getProperty("java.version"))
                    .put("jvmVendor", System.getProperty("java.vendor")))
            .put(
                "manifest",
                new JSONObject()
                    .put("path", inputs.manifestPath.toString())
                    .put("sha256", inputs.manifestSha256)
                    .put("preparedAt", inputs.manifest.getString("preparedAt"))
                    .put("networkUsed", inputs.manifest.getBoolean("networkUsed")))
            .put("assets", assetEvidence(inputs))
            .put(
                "fixture",
                new JSONObject()
                    .put("path", fixture.toString())
                    .put("sha256", sha256(fixture)))
            .put(
                "node",
                new JSONObject()
                    .put("semanticPath", "0/0/0/0/0")
                    .put("kind", capturedTarget.getData().getNodeKind().name())
                    .put("identity", Integer.toHexString(System.identityHashCode(capturedTarget))))
            .put(
                "rules",
                new JSONObject()
                    .put("targetRaw", rulesTarget.rawDeclaration())
                    .put("targetSummary", rulesTarget.parsedRules().orElseThrow().summary().name())
                    .put("targetRevision", rulesTarget.revision())
                    .put("actual", actualRules.observed().toJson())
                    .put("status", actualRules.status().name())
                    .put("fresh", true))
            .put("position", positionEvidence(capturedTarget))
            .put(
                "analysis",
                new JSONObject()
                    .put("schema", "katago-info-v1")
                    .put("move", candidate.coordinate)
                    .put("visits", candidate.playouts)
                    .put("winrate", candidate.winrate)
                    .put("scoreLead", candidate.scoreMean)
                    .put(
                        "pv",
                        new JSONArray(candidate.variation == null ? List.of() : candidate.variation)))
            .put(
                "phasesMs",
                new JSONObject()
                    .put("startup", startupMillis)
                    .put("rulesPosition", positionMillis)
                    .put("analysis", analysisMillis)
                    .put("stop", stopMillis)
                    .put("quit", quitMillis)
                    .put("cleanup", cleanupMillis)
                    .put("total", elapsedMillis(totalStart)))
            .put(
                "stop",
                new JSONObject()
                    .put("requested", true)
                    .put("quietWindowMs", 400)
                    .put("quiet", true)
                    .put("peerOutputCount", quiet.enginePlayouts)
                    .put("applicationVisits", quiet.applicationVisits))
            .put("quit", new JSONObject().put("normal", true))
            .put(
                "cleanup",
                new JSONObject()
                    .put("forced", false)
                    .put("durationMs", cleanupMillis)
                    .put("process", !peer.isAlive())
                    .put("readers", readers.terminated())
                    .put("stagedSgf", staged.stream().noneMatch(Files::exists)))
            .put(
                "evidence",
                new JSONObject()
                    .put("result", result.toString())
                    .put("stdout", stdout.toString())
                    .put("stderr", stderr.toString())
                    .put("appLog", appLog.toString())
                    .put("phases", phases.toString())
                    .put(
                        "stagedSgfs",
                        new JSONArray(staged.stream().map(Path::toString).toList())));
    writeResult(result, record);
    phase(state, result, "production-cleanup-complete");
  }

  private static CleanupOutcome cleanupAfterFailure(
      ProbeState state, SnapshotObserver snapshots) {
    long cleanupStart = System.nanoTime();
    Deadline deadline = Deadline.after(CLEANUP_BUDGET);
    ProcessHandle peer = state.peer;
    if (peer == null && state.engine != null) {
      try {
        Process process = engineProcess(state.engine);
        if (process != null) peer = process.toHandle();
      } catch (ReflectiveOperationException ignored) {
        // The exact production binding was unavailable; the failed record preserves that fact.
      }
    }
    ReaderThreads readers = state.readers;
    if (readers == null) readers = findReaderThreads().orElse(null);
    state.peer = peer;
    state.readers = readers;

    if (state.engine != null) {
      try {
        callWithin(
            () -> {
              state.engine.forceQuit();
              return null;
            },
            deadline,
            "forced production engine cleanup");
      } catch (Throwable ignored) {
        // The convergence checks below record the observable cleanup result.
      }
    }

    ProcessHandle ownedPeer = peer;
    ReaderThreads ownedReaders = readers;
    boolean processStopped =
        ownedPeer == null || settle(() -> !ownedPeer.isAlive(), deadline, "owned process cleanup");
    boolean readersStopped =
        ownedReaders == null
            || settle(ownedReaders::terminated, deadline, "owned reader cleanup");
    List<Path> staged = snapshots == null ? List.of() : snapshots.paths();
    boolean stagedRemoved =
        settle(
            () -> staged.stream().noneMatch(Files::exists),
            deadline,
            "staged snapshot cleanup");
    return new CleanupOutcome(
        ownedPeer == null ? -1L : ownedPeer.pid(),
        readersStopped,
        processStopped,
        stagedRemoved,
        elapsedMillis(cleanupStart),
        staged);
  }

  private static boolean settle(
      CheckedCondition condition, Deadline deadline, String description) {
    try {
      await(condition, deadline, description);
      return true;
    } catch (Throwable failure) {
      return false;
    }
  }

  private static JSONObject failureRecord(
      Path fixture,
      Inputs inputs,
      String engineId,
      SourceIdentity source,
      Path work,
      Path result,
      ProbeState state,
      CleanupOutcome cleanup,
      Throwable failure)
      throws IOException {
    BoardHistoryNode target = state.target;
    BoardHistoryList.SessionRulesTarget rulesTarget = state.rulesTarget;
    EngineRulesResult actualRules = state.actualRules;
    MoveData candidate = state.candidate;
    QuietState quiet = state.quiet;
    Path stdout = result.getParent().resolve("stdout.log");
    Path stderr = result.getParent().resolve("stderr.log");
    Path phases = result.resolveSibling("phases.log");
    Path appLog = work.resolve("logs/app.log");

    return new JSONObject()
        .put("schemaVersion", 1)
        .put("scenario", "real-cpu-engine")
        .put("status", "FAIL")
        .put(
            "failure",
            new JSONObject()
                .put("phase", state.phase)
                .put("class", failure.getClass().getName())
                .put("message", clean(failure.getMessage())))
        .put(
            "peer",
            new JSONObject()
                .put("kind", "real-katago-cpu")
                .put("catalogEngineId", engineId)
                .put("command", command(inputs.engine, inputs.model, inputs.config))
                .put("pid", cleanup.pid)
                .put("stdoutReader", readerIdentity(state.readers, true))
                .put("stderrReader", readerIdentity(state.readers, false)))
        .put("source", new JSONObject().put("commit", source.commit).put("dirty", source.dirty))
        .put(
            "platform",
            new JSONObject()
                .put("os", System.getProperty("os.name"))
                .put("arch", System.getProperty("os.arch"))
                .put("jvmVersion", System.getProperty("java.version"))
                .put("jvmVendor", System.getProperty("java.vendor")))
        .put(
            "manifest",
            new JSONObject()
                .put("path", inputs.manifestPath.toString())
                .put("sha256", inputs.manifestSha256)
                .put("preparedAt", inputs.manifest.getString("preparedAt"))
                .put("networkUsed", inputs.manifest.getBoolean("networkUsed")))
        .put("assets", assetEvidence(inputs))
        .put(
            "fixture",
            new JSONObject().put("path", fixture.toString()).put("sha256", sha256(fixture)))
        .put(
            "node",
            new JSONObject()
                .put("semanticPath", target == null ? "" : "0/0/0/0/0")
                .put("kind", target == null ? "NOT_OBSERVED" : target.getData().getNodeKind().name())
                .put(
                    "identity",
                    target == null ? "" : Integer.toHexString(System.identityHashCode(target))))
        .put(
            "rules",
            new JSONObject()
                .put("targetRaw", rulesTarget == null ? "" : rulesTarget.rawDeclaration())
                .put(
                    "targetSummary",
                    rulesTarget == null || rulesTarget.parsedRules().isEmpty()
                        ? "NOT_OBSERVED"
                        : rulesTarget.parsedRules().orElseThrow().summary().name())
                .put("targetRevision", rulesTarget == null ? -1L : rulesTarget.revision())
                .put(
                    "actual",
                    actualRules == null || actualRules.observed() == null
                        ? new JSONObject()
                        : actualRules.observed().toJson())
                .put("status", actualRules == null ? "NOT_OBSERVED" : actualRules.status().name())
                .put("fresh", actualRules != null && actualRules.isConfirmed()))
        .put("position", failurePositionEvidence(target, state.positionConfirmed))
        .put("analysis", failureAnalysisEvidence(candidate))
        .put(
            "phasesMs",
            new JSONObject()
                .put("startup", state.startupMillis)
                .put("rulesPosition", state.positionMillis)
                .put("analysis", state.analysisMillis)
                .put("stop", state.stopMillis)
                .put("quit", state.quitMillis)
                .put("cleanup", cleanup.durationMillis)
                .put(
                    "total",
                    state.totalStart == 0L ? -1L : elapsedMillis(state.totalStart)))
        .put(
            "stop",
            new JSONObject()
                .put("requested", state.stopRequested)
                .put("quietWindowMs", 400)
                .put("quiet", quiet != null)
                .put("peerOutputCount", quiet == null ? -1 : quiet.enginePlayouts)
                .put("applicationVisits", quiet == null ? -1 : quiet.applicationVisits))
        .put("quit", new JSONObject().put("normal", state.normalQuit))
        .put(
            "cleanup",
            new JSONObject()
                .put("forced", true)
                .put("durationMs", cleanup.durationMillis)
                .put("process", cleanup.processStopped)
                .put("readers", cleanup.readersStopped)
                .put("stagedSgf", cleanup.stagedRemoved))
        .put(
            "evidence",
            new JSONObject()
                .put("result", result.toString())
                .put("stdout", stdout.toString())
                .put("stderr", stderr.toString())
                .put("appLog", appLog.toString())
                .put("phases", phases.toString())
                .put(
                    "stagedSgfs",
                    new JSONArray(cleanup.staged.stream().map(Path::toString).toList())));
  }

  private static JSONObject failurePositionEvidence(
      BoardHistoryNode target, boolean confirmed) {
    if (target != null && confirmed) return positionEvidence(target);
    return new JSONObject()
        .put("confirmed", false)
        .put("board", "NOT_OBSERVED")
        .put("komi", JSONObject.NULL)
        .put("stones", "")
        .put("empty", "")
        .put("turn", "NOT_OBSERVED")
        .put("setupKind", "NOT_OBSERVED")
        .put("setupStones", "")
        .put("tailPrevious", "NOT_OBSERVED")
        .put("tailCurrent", "NOT_OBSERVED");
  }

  private static JSONObject failureAnalysisEvidence(MoveData candidate) {
    return new JSONObject()
        .put("schema", "katago-info-v1")
        .put("move", candidate == null ? "" : candidate.coordinate)
        .put("visits", candidate == null ? 0 : candidate.playouts)
        .put("winrate", candidate == null ? JSONObject.NULL : candidate.winrate)
        .put("scoreLead", candidate == null ? JSONObject.NULL : candidate.scoreMean)
        .put(
            "pv",
            new JSONArray(
                candidate == null || candidate.variation == null
                    ? List.of()
                    : candidate.variation));
  }

  private static String readerIdentity(ReaderThreads readers, boolean stdout) {
    if (readers == null) return "NOT_OBSERVED";
    return readers.identity(stdout ? readers.stdout : readers.stderr);
  }

  private static void phase(ProbeState state, Path result, String name) throws IOException {
    state.phase = name;
    DesktopProbeProcess.phase(result, name);
  }

  private static void writeIsolatedConfig(Path work) throws IOException {
    JSONObject ui =
        new JSONObject()
            .put("autoload-empty", true)
            .put("first-time-load", false)
            .put("use-language", 2)
            .put("load-sgf-last", true)
            .put("auto-quick-analyze-on-load", false);
    JSONObject root =
        new JSONObject()
            .put("leelaz", new JSONObject().put("engine-settings-list", new JSONArray()))
            .put("ui", ui);
    Files.writeString(work.resolve("config.txt"), root.toString(2), StandardCharsets.UTF_8);
  }

  private static EngineData catalogEntry(int index, String id, String command) {
    EngineData entry = new EngineData();
    entry.id = id;
    entry.index = index;
    entry.commands = command;
    entry.name = "D4 pinned KataGo CPU";
    entry.preload = false;
    entry.width = 19;
    entry.height = 19;
    entry.isDefault = false;
    entry.komi = 6.5f;
    return entry;
  }

  private static String command(Path engine, Path model, Path config) {
    return quote(engine) + " gtp -model " + quote(model) + " -config " + quote(config);
  }

  private static String quote(Path path) {
    String value = path.toAbsolutePath().normalize().toString();
    return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  private static ProcessHandle awaitOwnedPeer(Leelaz engine, Inputs inputs, Deadline deadline)
      throws Exception {
    AtomicReference<Process> found = new AtomicReference<>();
    await(
        () -> {
          Process process = engineProcess(engine);
          if (process == null || !process.isAlive()) return false;
          found.set(process);
          return true;
        },
        deadline,
        "production-owned KataGo process binding");

    ProcessHandle handle = found.get().toHandle();
    await(
        () -> matchesPeerArguments(handle, inputs),
        deadline,
        "production-owned KataGo command arguments");
    return handle;
  }

  private static Process engineProcess(Leelaz engine) throws ReflectiveOperationException {
    Field field = Leelaz.class.getDeclaredField("process");
    field.setAccessible(true);
    return (Process) field.get(engine);
  }

  private static boolean matchesPeerArguments(ProcessHandle handle, Inputs inputs) {
    Optional<String[]> arguments = handle.info().arguments();
    return arguments.isPresent()
        && Arrays.equals(
            arguments.get(),
            new String[] {
              "gtp", "-model", inputs.model.toString(), "-config", inputs.config.toString()
            });
  }

  private static boolean confirmedChineseRules(
      Leelaz engine, BoardHistoryList.SessionRulesTarget target) {
    EngineRulesResult result = engine.engineRulesResult();
    return result != null
        && result.isConfirmed()
        && result.observed() != null
        && target.parsedRules().isPresent()
        && target.parsedRules().orElseThrow().summary() == KataGoRules.Summary.CHINESE
        && result.observed().semanticallyEquals(target.parsedRules().orElseThrow());
  }

  private static Optional<MoveData> positiveLegalCandidate(BoardHistoryNode target) {
    List<MoveData> moves = target.getData().bestMoves;
    if (moves == null) {
      return Optional.empty();
    }
    return moves.stream()
        .filter(move -> move != null && move.playouts > 0 && legalCandidate(move, target.getData()))
        .findFirst();
  }

  private static boolean legalCandidate(MoveData candidate, BoardData position) {
    if (candidate.coordinate == null || candidate.coordinate.isBlank()) {
      return false;
    }
    if (candidate.coordinate.equalsIgnoreCase("pass")) {
      return true;
    }
    return Board.asCoordinates(candidate.coordinate)
        .filter(coordinates -> AnalysisCandidateValidator.isEmptyPoint(position, coordinates))
        .isPresent();
  }

  private static void assertApplicationPosition(
      BoardHistoryList history,
      BoardHistoryNode capturedTarget,
      BoardHistoryList.SessionRulesTarget rulesTarget,
      boolean requireAnalysis) {
    if (Board.boardWidth != 19 || Board.boardHeight != 19) {
      throw new AssertionError("application board size is not 19x19");
    }
    if (Double.compare(history.getGameInfo().getKomi(), 6.5) != 0) {
      throw new AssertionError("application komi is not 6.5");
    }
    if (history.getCurrentHistoryNode() != capturedTarget
        || Lizzie.board.getHistory().getCurrentHistoryNode() != capturedTarget) {
      throw new AssertionError("application current node changed from captured target");
    }
    if (history.captureSessionRules() != rulesTarget
        || rulesTarget.kind() != BoardHistoryList.SessionRulesKind.VALID
        || rulesTarget.parsedRules().isEmpty()
        || rulesTarget.parsedRules().orElseThrow().summary() != KataGoRules.Summary.CHINESE) {
      throw new AssertionError("fixture did not retain its immutable Chinese rules target");
    }
    BoardData current = capturedTarget.getData();
    String occupied = applicationStones(current);
    if (!occupied.equals("B:fd;W:ee,ff,hh")) {
      throw new AssertionError("unexpected application stones: " + occupied);
    }
    requireStone(current, "dd", Stone.EMPTY);
    if (current.blackToPlay) {
      throw new AssertionError("application side to play is not white");
    }
    BoardHistoryNode previous = capturedTarget.previous().orElseThrow();
    BoardHistoryNode snapshot = previous.previous().orElseThrow();
    if (current.getNodeKind() != BoardNodeKind.PASS
        || current.lastMove.isPresent()
        || current.lastMoveColor != Stone.BLACK
        || current.dummy
        || current.moveNumber != previous.getData().moveNumber + 1) {
      throw new AssertionError("current node is not the genuine consuming black PASS");
    }
    if (previous.getData().getNodeKind() != BoardNodeKind.MOVE
        || previous.getData().lastMoveColor != Stone.WHITE
        || previous.getData().lastMove.isEmpty()
        || !Arrays.equals(previous.getData().lastMove.get(), new int[] {7, 7})) {
      throw new AssertionError("previous node is not W[hh]");
    }
    if (snapshot.getData().getNodeKind() != BoardNodeKind.SNAPSHOT
        || !applicationStones(snapshot.getData()).equals("B:fd;W:ee,ff")
        || snapshot.getData().blackToPlay) {
      throw new AssertionError("removed-stone setup was not retained as the expected SNAPSHOT");
    }
    requireStone(snapshot.getData(), "dd", Stone.EMPTY);
    if (requireAnalysis && positiveLegalCandidate(capturedTarget).isEmpty()) {
      throw new AssertionError("captured current node has no legal positive-visit candidate");
    }
  }

  private static JSONObject positionEvidence(BoardHistoryNode target) {
    BoardHistoryNode previous = target.previous().orElseThrow();
    BoardHistoryNode snapshot = previous.previous().orElseThrow();
    return new JSONObject()
        .put("confirmed", true)
        .put("board", "19x19")
        .put("komi", 6.5)
        .put("stones", applicationStones(target.getData()))
        .put("empty", "dd")
        .put("turn", "W")
        .put("setupKind", snapshot.getData().getNodeKind().name())
        .put("setupStones", applicationStones(snapshot.getData()))
        .put("tailPrevious", "MOVE:W[hh]")
        .put("tailCurrent", "PASS:B[]");
  }

  private static void requireStone(BoardData data, String coordinate, Stone expected) {
    int x = coordinate.charAt(0) - 'a';
    int y = coordinate.charAt(1) - 'a';
    Stone actual = data.stones[Board.getIndex(x, y)];
    if (actual != expected) {
      throw new AssertionError(coordinate + " expected " + expected + " but was " + actual);
    }
  }

  private static String applicationStones(BoardData data) {
    List<String> black = new ArrayList<>();
    List<String> white = new ArrayList<>();
    for (int x = 0; x < Board.boardWidth; x++) {
      for (int y = 0; y < Board.boardHeight; y++) {
        Stone stone = data.stones[Board.getIndex(x, y)];
        if (stone == Stone.BLACK) {
          black.add("" + (char) ('a' + x) + (char) ('a' + y));
        } else if (stone == Stone.WHITE) {
          white.add("" + (char) ('a' + x) + (char) ('a' + y));
        } else if (stone != Stone.EMPTY) {
          throw new AssertionError("unexpected stone state: " + stone);
        }
      }
    }
    List<String> groups = new ArrayList<>();
    if (!black.isEmpty()) groups.add("B:" + String.join(",", black));
    if (!white.isEmpty()) groups.add("W:" + String.join(",", white));
    return String.join(";", groups);
  }

  private static QuietState awaitAnalysisQuiescence(
      Leelaz engine, BoardHistoryNode target, Deadline deadline) throws Exception {
    int enginePlayouts = -1;
    int applicationVisits = -1;
    long stableSince = -1L;
    while (deadline.hasTime()) {
      int currentEnginePlayouts = engine.getBestMovesPlayouts();
      int currentApplicationVisits = target.getData().getPlayouts();
      if (currentEnginePlayouts != enginePlayouts || currentApplicationVisits != applicationVisits) {
        enginePlayouts = currentEnginePlayouts;
        applicationVisits = currentApplicationVisits;
        stableSince = System.nanoTime();
      } else if (stableSince >= 0
          && System.nanoTime() - stableSince >= TimeUnit.MILLISECONDS.toNanos(400)) {
        return new QuietState(enginePlayouts, applicationVisits);
      }
      TimeUnit.NANOSECONDS.sleep(
          Math.min(TimeUnit.MILLISECONDS.toNanos(25), deadline.remainingNanos()));
    }
    throw new AssertionError("analysis stream did not become quiescent");
  }

  private static ReaderThreads awaitReaderThreads(Deadline deadline) throws Exception {
    AtomicReference<ReaderThreads> found = new AtomicReference<>();
    await(
        () -> {
          Optional<ReaderThreads> readers = findReaderThreads();
          readers.ifPresent(found::set);
          return readers.isPresent();
        },
        deadline,
        "two production reader threads");
    return found.get();
  }

  private static Optional<ReaderThreads> findReaderThreads() {
    Thread stdout = null;
    Thread stderr = null;
    for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
      Thread thread = entry.getKey();
      if (!thread.isAlive()) continue;
      boolean readsStdout = false;
      boolean readsStderr = false;
      for (StackTraceElement frame : entry.getValue()) {
        if (!frame.getClassName().equals(Leelaz.class.getName())) continue;
        readsStdout |= frame.getMethodName().equals("read");
        readsStderr |= frame.getMethodName().equals("readError");
      }
      if (readsStdout) {
        if (stdout != null && stdout != thread) return Optional.empty();
        stdout = thread;
      }
      if (readsStderr) {
        if (stderr != null && stderr != thread) return Optional.empty();
        stderr = thread;
      }
    }
    return stdout == null || stderr == null || stdout == stderr
        ? Optional.empty()
        : Optional.of(new ReaderThreads(stdout, stderr));
  }

  private static void dismissDialogs() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (Window window : Window.getWindows()) {
            if (window instanceof Dialog) window.dispose();
          }
        });
  }

  private static JSONObject assetEvidence(Inputs inputs) {
    JSONObject katago = inputs.manifest.getJSONObject("katago");
    JSONObject archive = katago.getJSONObject("archive");
    JSONObject executable = katago.getJSONObject("executable");
    JSONObject model = inputs.manifest.getJSONObject("model");
    JSONObject config = inputs.manifest.getJSONObject("config");
    JSONObject catalog = inputs.manifest.getJSONObject("catalog");
    return new JSONObject()
        .put("catalogPath", catalog.getString("path"))
        .put("catalogSha256", catalog.getString("sha256"))
        .put("archiveFileName", archive.getString("fileName"))
        .put("archivePath", archive.getString("path"))
        .put("archiveSizeBytes", archive.getLong("sizeBytes"))
        .put("archiveSha256", archive.getString("sha256"))
        .put("katagoVersion", katago.getString("version"))
        .put("katagoReleaseTag", katago.getString("releaseTag"))
        .put("katagoSourceCommit", katago.getString("sourceCommit"))
        .put("katagoBackend", katago.getString("backend"))
        .put("enginePath", executable.getString("path"))
        .put("engineSizeBytes", executable.getLong("sizeBytes"))
        .put("engineSha256", executable.getString("sha256"))
        .put("engineVersionOutput", executable.getString("versionOutput"))
        .put("modelId", model.getString("id"))
        .put("modelPath", model.getString("path"))
        .put("modelSizeBytes", model.getLong("sizeBytes"))
        .put("modelSha256", model.getString("sha256"))
        .put("configSource", config.getString("sourceArchiveEntry"))
        .put("configPath", config.getString("path"))
        .put("configSizeBytes", config.getLong("sizeBytes"))
        .put("configSha256", config.getString("sha256"));
  }

  private static void validateRecordShape(JSONObject result) {
    requireKeys(
        result,
        "result",
        Set.of(
            "schemaVersion",
            "scenario",
            "status",
            "failure",
            "peer",
            "source",
            "platform",
            "manifest",
            "assets",
            "fixture",
            "node",
            "rules",
            "position",
            "analysis",
            "phasesMs",
            "stop",
            "quit",
            "cleanup",
            "evidence"));
    requireKeys(
        result.getJSONObject("peer"),
        "peer",
        Set.of("kind", "catalogEngineId", "command", "pid", "stdoutReader", "stderrReader"));
    requireKeys(result.getJSONObject("source"), "source", Set.of("commit", "dirty"));
    requireKeys(
        result.getJSONObject("assets"),
        "assets",
        Set.of(
            "catalogPath",
            "catalogSha256",
            "archiveFileName",
            "archivePath",
            "archiveSizeBytes",
            "archiveSha256",
            "katagoVersion",
            "katagoReleaseTag",
            "katagoSourceCommit",
            "katagoBackend",
            "enginePath",
            "engineSizeBytes",
            "engineSha256",
            "engineVersionOutput",
            "modelId",
            "modelPath",
            "modelSizeBytes",
            "modelSha256",
            "configSource",
            "configPath",
            "configSizeBytes",
            "configSha256"));
    requireKeys(
        result.getJSONObject("platform"),
        "platform",
        Set.of("os", "arch", "jvmVersion", "jvmVendor"));
    requireKeys(
        result.getJSONObject("manifest"),
        "manifest",
        Set.of("path", "sha256", "preparedAt", "networkUsed"));
    requireKeys(result.getJSONObject("fixture"), "fixture", Set.of("path", "sha256"));
    requireKeys(
        result.getJSONObject("node"), "node", Set.of("semanticPath", "kind", "identity"));
    requireKeys(
        result.getJSONObject("rules"),
        "rules",
        Set.of("targetRaw", "targetSummary", "targetRevision", "actual", "status", "fresh"));
    requireKeys(
        result.getJSONObject("position"),
        "position",
        Set.of(
            "confirmed",
            "board",
            "komi",
            "stones",
            "empty",
            "turn",
            "setupKind",
            "setupStones",
            "tailPrevious",
            "tailCurrent"));
    requireKeys(
        result.getJSONObject("analysis"),
        "analysis",
        Set.of("schema", "move", "visits", "winrate", "scoreLead", "pv"));
    requireKeys(
        result.getJSONObject("phasesMs"),
        "phasesMs",
        Set.of("startup", "rulesPosition", "analysis", "stop", "quit", "cleanup", "total"));
    requireKeys(
        result.getJSONObject("stop"),
        "stop",
        Set.of("requested", "quietWindowMs", "quiet", "peerOutputCount", "applicationVisits"));
    requireKeys(result.getJSONObject("quit"), "quit", Set.of("normal"));
    requireKeys(
        result.getJSONObject("cleanup"),
        "cleanup",
        Set.of("forced", "durationMs", "process", "readers", "stagedSgf"));
    requireKeys(
        result.getJSONObject("evidence"),
        "evidence",
        Set.of("result", "stdout", "stderr", "appLog", "phases", "stagedSgfs"));
    if (!result.isNull("failure")) {
      requireKeys(
          result.getJSONObject("failure"), "failure", Set.of("phase", "class", "message"));
    }
  }

  private static void validateResult(
      JSONObject result,
      Inputs inputs,
      SourceIdentity source,
      String engineId,
      Path resultPath)
      throws Exception {
    validateRecordShape(result);
    assertEquals(1, result.getInt("schemaVersion"));
    assertEquals("real-cpu-engine", result.getString("scenario"));
    assertEquals("PASS", result.getString("status"));
    assertTrue(result.isNull("failure"));

    JSONObject peer = result.getJSONObject("peer");
    requireKeys(
        peer,
        "peer",
        Set.of("kind", "catalogEngineId", "command", "pid", "stdoutReader", "stderrReader"));
    assertEquals("real-katago-cpu", peer.getString("kind"));
    assertEquals(engineId, peer.getString("catalogEngineId"));
    assertEquals(command(inputs.engine, inputs.model, inputs.config), peer.getString("command"));
    long pid = peer.getLong("pid");
    assertTrue(pid > 0);
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));

    JSONObject sourceResult = result.getJSONObject("source");
    requireKeys(sourceResult, "source", Set.of("commit", "dirty"));
    assertEquals(source.commit, sourceResult.getString("commit"));
    assertEquals(source.dirty, sourceResult.getBoolean("dirty"));
    requireKeys(
        result.getJSONObject("platform"),
        "platform",
        Set.of("os", "arch", "jvmVersion", "jvmVendor"));
    requireKeys(
        result.getJSONObject("manifest"),
        "manifest",
        Set.of("path", "sha256", "preparedAt", "networkUsed"));
    assertEquals(inputs.manifestPath.toString(), result.getJSONObject("manifest").getString("path"));
    assertEquals(inputs.manifestSha256, result.getJSONObject("manifest").getString("sha256"));

    requireKeys(
        result.getJSONObject("assets"),
        "assets",
        Set.of(
            "catalogPath",
            "catalogSha256",
            "archiveFileName",
            "archivePath",
            "archiveSizeBytes",
            "archiveSha256",
            "katagoVersion",
            "katagoReleaseTag",
            "katagoSourceCommit",
            "katagoBackend",
            "enginePath",
            "engineSizeBytes",
            "engineSha256",
            "engineVersionOutput",
            "modelId",
            "modelPath",
            "modelSizeBytes",
            "modelSha256",
            "configSource",
            "configPath",
            "configSizeBytes",
            "configSha256"));
    assertEquals(assetEvidence(inputs).toString(), result.getJSONObject("assets").toString());
    requireKeys(result.getJSONObject("fixture"), "fixture", Set.of("path", "sha256"));
    assertEquals(FIXTURE.toString(), result.getJSONObject("fixture").getString("path"));
    assertEquals(sha256(FIXTURE), result.getJSONObject("fixture").getString("sha256"));

    JSONObject node = result.getJSONObject("node");
    requireKeys(node, "node", Set.of("semanticPath", "kind", "identity"));
    assertEquals("0/0/0/0/0", node.getString("semanticPath"));
    assertEquals("PASS", node.getString("kind"));
    JSONObject rules = result.getJSONObject("rules");
    requireKeys(
        rules,
        "rules",
        Set.of("targetRaw", "targetSummary", "targetRevision", "actual", "status", "fresh"));
    assertEquals("Chinese", rules.getString("targetRaw"));
    assertEquals("CHINESE", rules.getString("targetSummary"));
    assertEquals("CONFIRMED", rules.getString("status"));
    assertTrue(rules.getBoolean("fresh"));
    assertTrue(
        KataGoRules.fromJson(rules.getJSONObject("actual"))
            .semanticallyEquals(KataGoRules.parse("Chinese").orElseThrow()));

    JSONObject position = result.getJSONObject("position");
    requireKeys(
        position,
        "position",
        Set.of(
            "confirmed",
            "board",
            "komi",
            "stones",
            "empty",
            "turn",
            "setupKind",
            "setupStones",
            "tailPrevious",
            "tailCurrent"));
    assertTrue(position.getBoolean("confirmed"));
    assertEquals("19x19", position.getString("board"));
    assertEquals(6.5, position.getDouble("komi"));
    assertEquals("B:fd;W:ee,ff,hh", position.getString("stones"));
    assertEquals("dd", position.getString("empty"));
    assertEquals("W", position.getString("turn"));
    assertEquals("SNAPSHOT", position.getString("setupKind"));
    assertEquals("B:fd;W:ee,ff", position.getString("setupStones"));
    assertEquals("MOVE:W[hh]", position.getString("tailPrevious"));
    assertEquals("PASS:B[]", position.getString("tailCurrent"));

    JSONObject analysis = result.getJSONObject("analysis");
    requireKeys(
        analysis, "analysis", Set.of("schema", "move", "visits", "winrate", "scoreLead", "pv"));
    assertEquals("katago-info-v1", analysis.getString("schema"));
    assertFalse(analysis.getString("move").isBlank());
    assertTrue(analysis.getInt("visits") > 0);
    requireKeys(
        result.getJSONObject("phasesMs"),
        "phasesMs",
        Set.of("startup", "rulesPosition", "analysis", "stop", "quit", "cleanup", "total"));
    assertTrue(result.getJSONObject("phasesMs").getLong("startup") <= STARTUP_BUDGET.toMillis());
    assertTrue(
        result.getJSONObject("phasesMs").getLong("rulesPosition") <= POSITION_BUDGET.toMillis());
    assertTrue(result.getJSONObject("phasesMs").getLong("analysis") <= ANALYSIS_BUDGET.toMillis());
    assertTrue(result.getJSONObject("phasesMs").getLong("stop") <= STOP_BUDGET.toMillis());
    assertTrue(result.getJSONObject("phasesMs").getLong("quit") <= QUIT_BUDGET.toMillis());
    assertTrue(result.getJSONObject("phasesMs").getLong("cleanup") <= CLEANUP_BUDGET.toMillis());
    assertTrue(
        result.getJSONObject("phasesMs").getLong("total")
            <= Duration.ofSeconds(PROBE_BUDGET_SECONDS).toMillis());

    JSONObject stop = result.getJSONObject("stop");
    requireKeys(
        stop,
        "stop",
        Set.of("requested", "quietWindowMs", "quiet", "peerOutputCount", "applicationVisits"));
    assertTrue(stop.getBoolean("requested"));
    assertEquals(400, stop.getInt("quietWindowMs"));
    assertTrue(stop.getBoolean("quiet"));
    assertTrue(stop.getInt("peerOutputCount") > 0);
    assertTrue(stop.getInt("applicationVisits") > 0);
    requireKeys(result.getJSONObject("quit"), "quit", Set.of("normal"));
    assertTrue(result.getJSONObject("quit").getBoolean("normal"));
    JSONObject cleanup = result.getJSONObject("cleanup");
    requireKeys(
        cleanup,
        "cleanup",
        Set.of("forced", "durationMs", "process", "readers", "stagedSgf"));
    assertFalse(cleanup.getBoolean("forced"));
    assertTrue(cleanup.getLong("durationMs") <= CLEANUP_BUDGET.toMillis());
    assertTrue(cleanup.getBoolean("process"));
    assertTrue(cleanup.getBoolean("readers"));
    assertTrue(cleanup.getBoolean("stagedSgf"));

    JSONObject evidence = result.getJSONObject("evidence");
    requireKeys(
        evidence,
        "evidence",
        Set.of("result", "stdout", "stderr", "appLog", "phases", "stagedSgfs"));
    assertEquals(resultPath.toString(), evidence.getString("result"));
    for (String key : List.of("result", "stdout", "stderr", "appLog", "phases")) {
      assertTrue(Files.isRegularFile(Path.of(evidence.getString(key))), key + " missing");
    }
    JSONArray staged = evidence.getJSONArray("stagedSgfs");
    assertTrue(staged.length() > 0);
    for (int index = 0; index < staged.length(); index++) {
      assertFalse(Files.exists(Path.of(staged.getString(index))));
    }
  }

  private static void requireKeys(JSONObject value, String label, Set<String> expected) {
    if (!value.keySet().equals(expected)) {
      throw new AssertionError(
          label + " schema mismatch: expected " + expected + ", got " + value.keySet());
    }
  }

  private static boolean explicitExecutionRequested() {
    for (String property :
        List.of(
            "lizzie.acceptance.engine",
            "lizzie.acceptance.model",
            "lizzie.acceptance.engine.config",
            "lizzie.acceptance.engine.manifest")) {
      String value = System.getProperty(property);
      if (value != null) return true;
    }
    for (String selector : List.of("test", "it.test")) {
      String value = System.getProperty(selector);
      if (value != null && value.contains(RealCpuEngineAcceptanceIT.class.getSimpleName())) {
        return true;
      }
    }
    return false;
  }

  private static SourceIdentity sourceIdentity() throws Exception {
    String commit = runGit("rev-parse", "HEAD").trim();
    boolean dirty = !runGit("status", "--porcelain").isBlank();
    if (!commit.matches("[0-9a-f]{40}")) {
      throw new AssertionError("invalid source commit identity: " + commit);
    }
    return new SourceIdentity(commit, dirty);
  }

  private static String runGit(String... args) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(args));
    Process process =
        new ProcessBuilder(command)
            .directory(Path.of("").toAbsolutePath().toFile())
            .redirectErrorStream(true)
            .start();
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("git identity command timed out: " + command);
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.exitValue() != 0) {
      throw new AssertionError("git identity command failed: " + output);
    }
    return output;
  }

  private static String sha256(Path path) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
    try (java.io.InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[1024 * 1024];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        if (read > 0) digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void writeResult(Path path, JSONObject record) throws IOException {
    Files.writeString(path, record.toString(2) + "\n", StandardCharsets.UTF_8);
  }

  private static long elapsedMillis(long startNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
  }

  private static String clean(String message) {
    return message == null ? "unspecified failure" : message.replace('\n', ' ').replace('\r', ' ');
  }

  private static void await(CheckedCondition condition, Deadline deadline, String description)
      throws Exception {
    Throwable lastFailure = null;
    while (deadline.hasTime()) {
      try {
        if (condition.test()) return;
        lastFailure = null;
      } catch (IOException | IllegalArgumentException failure) {
        lastFailure = failure;
      }
      TimeUnit.NANOSECONDS.sleep(
          Math.min(TimeUnit.MILLISECONDS.toNanos(25), deadline.remainingNanos()));
    }
    AssertionError timeout = new AssertionError("Timed out awaiting " + description);
    if (lastFailure != null) timeout.initCause(lastFailure);
    throw timeout;
  }

  private static <T> T callWithin(CheckedSupplier<T> action, Deadline deadline, String description)
      throws Exception {
    FutureTask<T> task = new FutureTask<>(action::get);
    Thread worker = new Thread(task, "real-cpu-" + description.replace(' ', '-'));
    worker.setDaemon(true);
    worker.start();
    try {
      return task.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException failure) {
      task.cancel(true);
      throw new AssertionError("Timed out awaiting " + description, failure);
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof Exception exception) throw exception;
      if (cause instanceof Error error) throw error;
      throw new RuntimeException(cause);
    }
  }

  @FunctionalInterface
  private interface CheckedCondition {
    boolean test() throws Exception;
  }

  @FunctionalInterface
  private interface CheckedSupplier<T> {
    T get() throws Exception;
  }

  private record Deadline(long nanoTime) {
    static Deadline after(Duration duration) {
      return new Deadline(System.nanoTime() + duration.toNanos());
    }

    Deadline min(Deadline other) {
      return new Deadline(Math.min(nanoTime, other.nanoTime));
    }

    boolean hasTime() {
      return remainingNanos() > 0;
    }

    long remainingNanos() {
      return Math.max(0, nanoTime - System.nanoTime());
    }
  }

  private record QuietState(int enginePlayouts, int applicationVisits) {}

  private record SourceIdentity(String commit, boolean dirty) {}

  private record CleanupOutcome(
      long pid,
      boolean readersStopped,
      boolean processStopped,
      boolean stagedRemoved,
      long durationMillis,
      List<Path> staged) {}

  private static final class ProbeState {
    private String phase = "initialization";
    private long totalStart;
    private Leelaz engine;
    private ProcessHandle peer;
    private ReaderThreads readers;
    private BoardHistoryNode target;
    private BoardHistoryList.SessionRulesTarget rulesTarget;
    private EngineRulesResult actualRules;
    private MoveData candidate;
    private QuietState quiet;
    private boolean positionConfirmed;
    private boolean stopRequested;
    private boolean normalQuit;
    private long startupMillis = -1L;
    private long positionMillis = -1L;
    private long analysisMillis = -1L;
    private long firstOutputMillis = -1L;
    private long stopMillis = -1L;
    private long quitMillis = -1L;
  }

  private record ReaderThreads(Thread stdout, Thread stderr) {
    boolean terminated() {
      return !stdout.isAlive() && !stderr.isAlive();
    }

    String identity(Thread thread) {
      return thread.getName() + "#" + thread.getId();
    }
  }

  private static final class SnapshotObserver implements AutoCloseable {
    private final Path directory;
    private final WatchService watcher;
    private final Set<Path> paths = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;

    private SnapshotObserver(Path directory) throws IOException {
      this.directory = directory;
      watcher = FileSystems.getDefault().newWatchService();
      directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
    }

    void start() {
      if (!running.compareAndSet(false, true)) return;
      thread = new Thread(this::observe, "d4-snapshot-observer");
      thread.setDaemon(true);
      thread.start();
    }

    private void observe() {
      while (running.get()) {
        try {
          WatchKey key = watcher.poll(100, TimeUnit.MILLISECONDS);
          if (key == null) continue;
          for (WatchEvent<?> event : key.pollEvents()) {
            if (event.context() instanceof Path relative
                && relative.getFileName().toString().startsWith("lizzie-snapshot-")
                && relative.getFileName().toString().endsWith(".sgf")) {
              paths.add(directory.resolve(relative).toAbsolutePath().normalize());
            }
          }
          key.reset();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        } catch (java.nio.file.ClosedWatchServiceException closed) {
          return;
        }
      }
    }

    List<Path> paths() {
      synchronized (paths) {
        return List.copyOf(paths);
      }
    }

    @Override
    public void close() throws IOException {
      running.set(false);
      watcher.close();
      if (thread != null && thread != Thread.currentThread()) {
        try {
          thread.join(1000L);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private static final class Inputs {
    private final Path engine;
    private final Path model;
    private final Path config;
    private final Path manifestPath;
    private final JSONObject manifest;
    private final String manifestSha256;

    private Inputs(
        Path engine,
        Path model,
        Path config,
        Path manifestPath,
        JSONObject manifest,
        String manifestSha256) {
      this.engine = engine;
      this.model = model;
      this.config = config;
      this.manifestPath = manifestPath;
      this.manifest = manifest;
      this.manifestSha256 = manifestSha256;
    }

    static Inputs load() throws Exception {
      return load(
          propertyPath("lizzie.acceptance.engine"),
          propertyPath("lizzie.acceptance.model"),
          propertyPath("lizzie.acceptance.engine.config"),
          propertyPath("lizzie.acceptance.engine.manifest"));
    }

    static Inputs load(Path engine, Path model, Path config, Path manifestPath) throws Exception {
      engine = engine.toAbsolutePath().normalize();
      model = model.toAbsolutePath().normalize();
      config = config.toAbsolutePath().normalize();
      manifestPath = manifestPath.toAbsolutePath().normalize();
      requireRegular(engine, "lizzie.acceptance.engine");
      if (!Files.isExecutable(engine)) {
        throw new IllegalArgumentException("lizzie.acceptance.engine is not executable: " + engine);
      }
      requireRegular(model, "lizzie.acceptance.model");
      requireRegular(config, "lizzie.acceptance.engine.config");
      requireRegular(manifestPath, "lizzie.acceptance.engine.manifest");

      JSONObject manifest =
          new JSONObject(Files.readString(manifestPath, StandardCharsets.UTF_8));
      requireKeys(
          manifest,
          "manifest",
          Set.of(
              "schemaVersion",
              "catalog",
              "katago",
              "model",
              "config",
              "preparedAt",
              "networkUsed"));
      if (manifest.getInt("schemaVersion") != 1) {
        throw new IllegalArgumentException("manifest schemaVersion must be 1");
      }
      JSONObject manifestCatalog = manifest.getJSONObject("catalog");
      requireKeys(manifestCatalog, "manifest.catalog", Set.of("path", "sha256"));
      Path catalogPath =
          Path.of(manifestCatalog.getString("path")).toAbsolutePath().normalize();
      requireRegular(catalogPath, "catalog");
      if (Files.isRegularFile(CATALOG) && !catalogPath.equals(CATALOG)) {
        throw new IllegalArgumentException(
            "manifest catalog path does not identify the checked-in catalog");
      }
      if (!sha256(catalogPath).equals(manifestCatalog.getString("sha256"))) {
        throw new IllegalArgumentException("catalog SHA-256 does not match manifest");
      }

      JSONObject catalog =
          new JSONObject(Files.readString(catalogPath, StandardCharsets.UTF_8));
      if (catalog.getInt("schemaVersion") != 1) {
        throw new IllegalArgumentException("catalog schemaVersion must be 1");
      }
      JSONObject catalogAsset = catalog.getJSONObject("assets").getJSONObject("linux-cpu");
      String modelId = catalog.getString("defaultModelId");
      JSONObject catalogModel = catalog.getJSONObject("models").getJSONObject(modelId);

      JSONObject katago = manifest.getJSONObject("katago");
      requireKeys(
          katago,
          "manifest.katago",
          Set.of("version", "releaseTag", "sourceCommit", "backend", "archive", "executable"));
      if (!katago.getString("version").equals(catalog.getString("katagoVersion"))
          || !katago.getString("releaseTag").equals(catalog.getString("katagoReleaseTag"))
          || !katago.getString("sourceCommit").equals(catalog.getString("katagoSourceCommit"))
          || !katago.getString("backend").equals("eigen")) {
        throw new IllegalArgumentException("manifest KataGo identity does not match catalog pins");
      }
      JSONObject archive = katago.getJSONObject("archive");
      requireKeys(
          archive,
          "manifest.katago.archive",
          Set.of("fileName", "path", "sizeBytes", "sha256"));
      if (!archive.getString("fileName").equals(catalogAsset.getString("assetName"))
          || archive.getLong("sizeBytes") != catalogAsset.getLong("sizeBytes")
          || !archive.getString("sha256").equals(catalogAsset.getString("sha256"))) {
        throw new IllegalArgumentException("manifest archive identity does not match catalog pins");
      }
      requireIdentity(Path.of(archive.getString("path")), archive, "archive");

      JSONObject executable = katago.getJSONObject("executable");
      requireKeys(
          executable,
          "manifest.katago.executable",
          Set.of("path", "sizeBytes", "sha256", "versionOutput"));
      requireExactPath(engine, executable, "lizzie.acceptance.engine");
      requireIdentity(engine, executable, "engine");
      String actualVersion = runVersion(engine);
      if (!actualVersion.equals(executable.getString("versionOutput").trim())
          || !actualVersion.contains("KataGo v" + catalog.getString("katagoVersion"))
          || !actualVersion.contains("Using Eigen(CPU) backend")) {
        throw new IllegalArgumentException("engine version/backend does not match the manifest");
      }

      JSONObject manifestModel = manifest.getJSONObject("model");
      requireKeys(
          manifestModel,
          "manifest.model",
          Set.of("id", "fileName", "minimumKataGoVersion", "path", "sizeBytes", "sha256"));
      if (!manifestModel.getString("id").equals(modelId)
          || !manifestModel.getString("fileName").equals(catalogModel.getString("fileName"))
          || manifestModel.getLong("sizeBytes") != catalogModel.getLong("sizeBytes")
          || !manifestModel.getString("sha256").equals(catalogModel.getString("sha256"))) {
        throw new IllegalArgumentException("manifest model identity does not match catalog pins");
      }
      requireExactPath(model, manifestModel, "lizzie.acceptance.model");
      requireIdentity(model, manifestModel, "model");

      JSONObject manifestConfig = manifest.getJSONObject("config");
      requireKeys(
          manifestConfig,
          "manifest.config",
          Set.of("sourceArchiveEntry", "path", "sizeBytes", "sha256"));
      if (!manifestConfig.getString("sourceArchiveEntry").equals("default_gtp.cfg")) {
        throw new IllegalArgumentException("manifest config is not archive default_gtp.cfg");
      }
      requireExactPath(config, manifestConfig, "lizzie.acceptance.engine.config");
      requireIdentity(config, manifestConfig, "config");
      return new Inputs(engine, model, config, manifestPath, manifest, sha256(manifestPath));
    }

    private static Path propertyPath(String name) {
      String value = System.getProperty(name);
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("required system property is missing: " + name);
      }
      return Path.of(value);
    }

    private static void requireRegular(Path path, String label) {
      if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
        throw new IllegalArgumentException(label + " is not a readable regular file: " + path);
      }
    }

    private static void requireExactPath(Path actual, JSONObject identity, String label) {
      Path expected = Path.of(identity.getString("path")).toAbsolutePath().normalize();
      if (!actual.equals(expected)) {
        throw new IllegalArgumentException(
            label + " does not match manifest path: expected " + expected + ", got " + actual);
      }
    }

    private static void requireIdentity(Path path, JSONObject identity, String label)
        throws IOException {
      path = path.toAbsolutePath().normalize();
      requireRegular(path, label);
      if (Files.size(path) != identity.getLong("sizeBytes")
          || !sha256(path).equals(identity.getString("sha256"))) {
        throw new IllegalArgumentException(label + " size/SHA-256 does not match manifest: " + path);
      }
    }

    private static String runVersion(Path engine) throws Exception {
      Process process =
          new ProcessBuilder(engine.toString(), "version").redirectErrorStream(true).start();
      if (!process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalArgumentException("engine version command timed out");
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (process.exitValue() != 0) {
        throw new IllegalArgumentException("engine version command failed: " + output);
      }
      return output;
    }
  }

  /** The override only supplies the catalog display name; process ownership remains in Leelaz. */
  private static final class CatalogNamedLeelaz extends Leelaz {
    private final String catalogName;

    private CatalogNamedLeelaz(EngineData data) throws Exception {
      super(data.commands);
      catalogName = data.name;
      savedEntryId = data.id;
      preload = data.preload;
      width = data.width;
      height = data.height;
      oriWidth = data.width;
      oriHeight = data.height;
      komi = data.komi;
      orikomi = data.komi;
      useJavaSSH = data.useJavaSSH;
      ip = data.ip;
      port = data.port;
      useKeyGen = data.useKeyGen;
      keyGenPath = data.keyGenPath;
      userName = data.userName;
      password = data.password;
      initialCommand = data.initialCommand;
      gtpConfigurationProtocol = data.gtpConfigurationProtocol;
      gtpConfigurationProfile =
          data.gtpConfigurationProfile == null
              ? null
              : new JSONObject(data.gtpConfigurationProfile.toString());
    }

    @Override
    public String getEngineName(int index) {
      return catalogName;
    }
  }
}
