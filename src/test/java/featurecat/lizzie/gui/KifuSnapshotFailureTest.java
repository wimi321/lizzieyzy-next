package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.ExactSnapshotRestoreProtocolFixture;
import featurecat.lizzie.analysis.ForegroundRestoreResult;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.SessionRulesSynchronizer;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

/** Exercises the imported-position owner without opening windows or using the OS clipboard. */
class KifuSnapshotFailureTest {
  @TempDir Path directory;

  @Test
  void mixedSetupFromClipboardStopsRetryingAndExplainsResumeFailure() throws Exception {
    try (Fixture f = new Fixture()) {
      f.submit(false);
      f.runPending();

      assertNull(field(f.frame, "pendingKifuEngineSyncRoot"));
      assertTrue(LizzieFrame.canGoAfterload);
      assertEquals(true, field(f.frame, "userAnalysisPaused"));
      assertEquals(0, f.executor.retries);
      assertEquals(1, f.frame.failures);
      assertEquals(0, f.resumes);
      assertTrue(f.output.toString().isEmpty(), "unsupported wrapper must not mutate its board");
      assertSame(f.root, Lizzie.board.getHistory().getStart());
      assertFalse(f.root.getData().blackToPlay);
      assertEquals(Stone.BLACK, f.root.getData().stones[Board.getIndex(0, 0)]);
      assertEquals(Stone.WHITE, f.root.getData().stones[Board.getIndex(1, 0)]);
      assertEquals(7.5, Lizzie.board.getHistory().getGameInfo().getKomi());

      invoke(f.frame, "resumeFromAnalysisControl", new Class<?>[0]);
      assertSame(f.root, field(f.frame, "pendingKifuEngineSyncRoot"));
      f.runPending();
      assertEquals(2, f.frame.failures, "Continue must report the same unsupported restore");
      assertEquals(0, f.executor.retries, "explicit retry must not restart background retries");
      assertEquals(0, f.frame.scheduledResumes);
      assertTrue(f.output.toString().isEmpty());
    }
  }

  @Test
  void positionFailureAfterRulesConsentAlsoTerminatesWithoutResuming() throws Exception {
    try (Fixture f = new Fixture()) {
      f.submit(true);
      f.runPending();
      assertNull(field(f.frame, "pendingKifuEngineSyncRoot"));
      assertNull(field(f.frame, "pendingKifuRulesConsent"));
      assertTrue(LizzieFrame.canGoAfterload);
      assertEquals(0, f.executor.retries);
      assertEquals(1, f.frame.failures);
      assertEquals(0, f.resumes);
      assertTrue(f.output.toString().isEmpty());
    }
  }

  @Test
  void rulesOverrideTransfersResumeBeforeItsDelayedPostLoadAction() throws Exception {
    try (Fixture f = new Fixture(true)) {
      f.install(f.engine, f.output, List.of("katago.exe", "gtp"));
      Lizzie.config.analysisReuseCurrentEngine = true;
      FlashAnalysisAfterSync request = new FlashAnalysisAfterSync(f.frame, f.engine, true, false);
      CallbackWorker worker = allocate(CallbackWorker.class);
      f.frame.analysisEngine = worker;
      set(f.frame, "manualAutoAnalysisStarting", true);
      set(f.frame, "pendingManualAutoAnalysisReady", request);
      request.bind(worker);
      try (var transport =
          ExactSnapshotRestoreProtocolFixture.installAsync(
              f.engine, command -> ExactSnapshotRestoreProtocolFixture.Response.success())) {
        f.submit(true);
        f.runPending();
        transport.awaitResponses();
        assertNull(field(f.frame, "pendingKifuEngineSyncRoot"));
        assertEquals(1, f.frame.scheduledResumes);
        assertEquals(0, f.resumes, "post-load action deliberately remains queued");
        assertEquals(0, ((ResumeTrackingLeelaz) f.engine).ponderCount);
        set(f.frame, "manualAutoAnalysisStarting", false);
        Field callbackField = AnalysisEngine.class.getDeclaredField("completionCallback");
        callbackField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Consumer<ForegroundRestoreResult> callback =
            (Consumer<ForegroundRestoreResult>) callbackField.get(worker);
        SwingUtilities.invokeAndWait(() -> callback.accept(ForegroundRestoreResult.SUCCEEDED));
        assertEquals(1, ((ResumeTrackingLeelaz) f.engine).ponderCount);
      }
    }
  }

  @Test
  void queuedFailureCannotClearANewerImportOrShowItsPrompt() throws Exception {
    try (Fixture f = new Fixture()) {
      f.submit(false);
      // Run on the EDT so the terminal callback remains queued until the board has changed.
      SwingUtilities.invokeAndWait(
          () -> {
            f.executor.pending.run();
            Lizzie.board = new Board();
            try {
              set(f.frame, "pendingKifuEngineSyncRoot", Lizzie.board.getHistory().getStart());
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
          });
      SwingUtilities.invokeAndWait(() -> {});
      assertSame(Lizzie.board.getHistory().getStart(), field(f.frame, "pendingKifuEngineSyncRoot"));
      assertFalse(LizzieFrame.canGoAfterload);
      assertEquals(0, f.frame.failures);
      assertEquals(0, f.resumes);
    }
  }

  @Test
  void directLocalReplacementRestoresThePreservedSetupBeforeResume() throws Exception {
    try (Fixture f = new Fixture()) {
      f.submit(false);
      f.runPending();
      Leelaz direct = new Leelaz("");
      direct.started = true;
      direct.isLoaded = true;
      direct.isKatago = true;
      f.install(direct, new ByteArrayOutputStream(), List.of("katago.exe", "gtp"));
      AtomicReference<String> snapshot = new AtomicReference<>();
      try (var transport =
          ExactSnapshotRestoreProtocolFixture.installAsync(
              direct,
              command -> {
                if (command.startsWith("loadsgf ")) {
                  snapshot.set(Files.readString(Path.of(command.substring("loadsgf ".length()))));
                }
                return ExactSnapshotRestoreProtocolFixture.Response.success();
              })) {
        Lizzie.leelaz = direct;
        invoke(f.frame, "resumeFromAnalysisControl", new Class<?>[0]);
        assertSame(f.root, field(f.frame, "pendingKifuEngineSyncRoot"));
        assertFalse(LizzieFrame.canGoAfterload);
        assertEquals(0, f.frame.scheduledResumes);
        f.runPending();
        transport.awaitResponses();
        assertNull(field(f.frame, "pendingKifuEngineSyncRoot"));
        assertNull(field(f.frame, "failedKifuPositionRetry"));
        assertTrue(LizzieFrame.canGoAfterload);
        assertEquals(1, f.frame.scheduledResumes);
        assertEquals(1, f.frame.failures, "replacement must not inherit the old failure prompt");
        assertTrue(snapshot.get().contains("AB[aa]"), snapshot.get());
        assertTrue(snapshot.get().contains("AW[ba]"), snapshot.get());
        assertTrue(snapshot.get().contains("PL[W]"), snapshot.get());
        assertTrue(snapshot.get().contains("KM[7.5]"), snapshot.get());
        assertTrue(transport.commands().stream().noneMatch(command -> command.startsWith("play ")));
      }
    }
  }

  @Test
  void queuedFailureCannotAffectAReplacementReaderOrPrimary() throws Exception {
    for (boolean replacementPrimary : List.of(false, true)) {
      try (Fixture f = new Fixture()) {
        f.submit(false);
        SwingUtilities.invokeAndWait(
            () -> {
              f.executor.pending.run();
              try {
                if (replacementPrimary) {
                  Lizzie.leelaz = new Leelaz("");
                } else {
                  f.install(f.engine, new ByteArrayOutputStream(), List.of("katago.exe", "gtp"));
                }
              } catch (Exception failure) {
                throw new AssertionError(failure);
              }
            });
        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(0, f.frame.failures);
        assertNull(field(f.frame, "failedKifuPositionRetry"));
        assertEquals(0, f.resumes);
      }
    }
  }

  @Test
  void unsupportedComparisonEngineFailsBeforeEitherTargetIsChanged() throws Exception {
    try (Fixture f = new Fixture()) {
      f.install(f.engine, f.output, List.of("katago.exe", "gtp"));
      Leelaz mirror = new Leelaz("");
      mirror.started = true;
      mirror.isLoaded = true;
      mirror.isKatago = true;
      ByteArrayOutputStream mirrorOutput = new ByteArrayOutputStream();
      f.install(mirror, mirrorOutput, List.of("helper.bat", "cuda", "gtp", "Elo"));
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      Lizzie.leelaz2 = mirror;
      f.submit(false);
      f.runPending();
      assertNull(field(f.frame, "pendingKifuEngineSyncRoot"));
      assertEquals(1, f.frame.failures);
      assertEquals(0, f.frame.scheduledResumes);
      assertEquals(0, f.executor.retries);
      assertTrue(f.output.toString().isEmpty());
      assertTrue(mirrorOutput.toString().isEmpty());
    }
  }

  @Test
  void cancelAndManualRulesSelectionRetireTheFailedImportRetry() throws Exception {
    try (Fixture f = new Fixture()) {
      f.submit(false);
      f.runPending();
      assertNotNull(field(f.frame, "failedKifuPositionRetry"));
      f.frame.retireImportedRulesForManualSelection(
          Lizzie.board.getHistory(), Lizzie.board.getHistory().captureSessionRules());
      assertNull(field(f.frame, "failedKifuPositionRetry"));
      set(f.frame, "pendingKifuEngineSyncRoot", f.root);
      f.submit(false);
      f.runPending();
      assertNotNull(field(f.frame, "failedKifuPositionRetry"));
      f.frame.shutdownKifuEngineSyncCoordinator();
      assertNull(field(f.frame, "failedKifuPositionRetry"));
    }
  }

  private final class Fixture implements AutoCloseable {
    final Config oldConfig = Lizzie.config;
    final Board oldBoard = Lizzie.board;
    final Leelaz oldEngine = Lizzie.leelaz;
    final Leelaz oldSecondary = Lizzie.leelaz2;
    final LizzieFrame oldFrame = Lizzie.frame;
    final EngineManager oldManager = Lizzie.engineManager;
    final GtpConsolePane oldConsole = Lizzie.gtpConsole;
    final int oldWidth = Board.boardWidth;
    final int oldHeight = Board.boardHeight;
    final boolean oldEmpty = EngineManager.isEmpty;
    final boolean oldCanGo = LizzieFrame.canGoAfterload;
    final CapturingExecutor executor = new CapturingExecutor();
    final RecordingFrame frame = allocate(RecordingFrame.class);
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final Leelaz engine;
    final BoardHistoryNode root;
    int resumes;

    Fixture() throws Exception {
      this(false);
    }

    Fixture(boolean trackResume) throws Exception {
      Lizzie.config = ConfigTestHelper.createForTests(directory);
      Lizzie.frame = null;
      Lizzie.engineManager = null;
      Lizzie.gtpConsole = null;
      Lizzie.leelaz = null;
      Lizzie.leelaz2 = null;
      Board.boardWidth = 19;
      Board.boardHeight = 19;
      Zobrist.init();
      Board board = new Board();
      Lizzie.board = board;
      board.setHistory(SGFParser.parseSgf("(;SZ[19]KM[7.5]AB[aa]AW[ba]PL[W])", true));
      root = board.getHistory().getStart();
      board.requireEngineAlignment();
      engine = trackResume ? new ResumeTrackingLeelaz() : new Leelaz("");
      install(engine, output, List.of("helper.bat", "cuda", "gtp", "Elo"));
      engine.started = true;
      engine.isLoaded = true;
      engine.isKatago = true;
      EngineManager.isEmpty = false;
      Lizzie.leelaz = engine;
      Lizzie.frame = frame;
      set(frame, "kifuEngineSyncCoordinator", new KifuEngineSyncCoordinator(executor));
      set(frame, "pendingKifuEngineSyncRoot", root);
      LizzieFrame.canGoAfterload = false;
    }

    void install(Leelaz target, OutputStream stream, List<String> commands) throws Exception {
      Method install =
          Leelaz.class.getDeclaredMethod(
              "installFreshCommandOutputForTest", OutputStream.class, Path.class, List.class);
      install.setAccessible(true);
      install.invoke(target, stream, directory, commands);
    }

    void submit(boolean rulesOverride) throws Exception {
      Class<?>[] types = {
        BoardHistoryNode.class,
        BoardHistoryList.SessionRulesTarget.class,
        Leelaz.class,
        long.class,
        Leelaz.class,
        int.class,
        Runnable.class
      };
      Object[] args = {
        root,
        Lizzie.board.getHistory().captureSessionRules(),
        engine,
        Lizzie.capturePrimaryEngineGeneration(engine),
        engine.activeComparisonEngine(),
        0,
        (Runnable) () -> resumes++
      };
      if (rulesOverride) {
        types =
            new Class<?>[] {
              BoardHistoryNode.class,
              BoardHistoryList.SessionRulesTarget.class,
              Leelaz.class,
              long.class,
              Leelaz.class,
              SessionRulesSynchronizer.Result.class,
              int.class,
              Runnable.class
            };
        args = new Object[] {args[0], args[1], args[2], args[3], args[4], null, args[5], args[6]};
      }
      invoke(
          frame,
          rulesOverride ? "submitKifuPositionSyncAfterRulesOverride" : "submitKifuEngineSync",
          types,
          args);
    }

    void runPending() throws Exception {
      executor.pending.run();
      SwingUtilities.invokeAndWait(() -> {});
    }

    @Override
    public void close() throws Exception {
      frame.shutdownKifuEngineSyncCoordinator();
      SwingUtilities.invokeAndWait(() -> {});
      Lizzie.config = oldConfig;
      Lizzie.board = oldBoard;
      Lizzie.leelaz = oldEngine;
      Lizzie.leelaz2 = oldSecondary;
      Lizzie.frame = oldFrame;
      Lizzie.engineManager = oldManager;
      Lizzie.gtpConsole = oldConsole;
      Board.boardWidth = oldWidth;
      Board.boardHeight = oldHeight;
      Zobrist.init();
      EngineManager.isEmpty = oldEmpty;
      LizzieFrame.canGoAfterload = oldCanGo;
    }
  }

  private static final class ResumeTrackingLeelaz extends Leelaz {
    int ponderCount;

    ResumeTrackingLeelaz() throws java.io.IOException {
      super("");
    }

    @Override
    public void ponder() {
      ponderCount++;
    }
  }

  private static final class CallbackWorker extends AnalysisEngine {
    CallbackWorker() throws java.io.IOException {
      super(false);
    }

    @Override
    public boolean isRunning() {
      return true;
    }

    @Override
    public boolean hasRequestLifecycleInProgress() {
      return false;
    }

    @Override
    public boolean usesSharedForegroundEngine() {
      return true;
    }
  }

  private static final class RecordingFrame extends LizzieFrame {
    int failures;
    int scheduledResumes;

    @Override
    protected void showPositionSyncFailure(RuntimeException failure) {
      failures++;
    }

    @Override
    public void scheduleResumeAnalysisAfterLoad(int delayMillis, Runnable action) {
      scheduledResumes++;
    }
  }

  private static final class CapturingExecutor extends ScheduledThreadPoolExecutor {
    Runnable pending;
    int retries;

    CapturingExecutor() {
      super(1);
    }

    @Override
    public void execute(Runnable command) {
      pending = command;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      retries++;
      return super.schedule(command, 1, TimeUnit.DAYS);
    }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = LizzieFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    Field field = LizzieFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object invoke(Object target, String name, Class<?>[] types, Object... args)
      throws Exception {
    Method method = LizzieFrame.class.getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(target, args);
  }
}
