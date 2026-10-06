package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.AutomaticQuickAnalysisTask.Registration;
import featurecat.lizzie.analysis.AutomaticQuickAnalysisTask.RequestResult;
import featurecat.lizzie.analysis.AutomaticQuickAnalysisTask.Use;
import featurecat.lizzie.analysis.ForegroundRestoreResult;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutomaticQuickAnalysisEngineAdapterTest {
  private static final String STARTUP = "quick-analysis-engine-preloader";
  private static final String REQUEST = "loaded-game-quick-analysis-request";
  private static final String RETURN = "automatic-quick-analysis-return";
  private static final String CLOSE = "automatic-quick-analysis-close";
  private static final String UNCLAIMED_CLOSE = "quick-analysis-unclaimed-close";

  private TestEnvironment environment;
  private ControlledExecutor executor;
  private Deque<PhysicalWorker> workers;
  private AutomaticQuickAnalysisEngineAdapter adapter;

  @BeforeEach
  void openEnvironment() throws Exception {
    onEdt(
        () -> {
          environment = new TestEnvironment();
          environment.open();
          executor = new ControlledExecutor();
          workers = new ArrayDeque<>();
          adapter =
              new AutomaticQuickAnalysisEngineAdapter(
                  Lizzie.frame,
                  persistent -> {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    PhysicalWorker worker = workers.removeFirst();
                    worker.automatic = !persistent;
                    return worker;
                  },
                  executor::submit);
        });
  }

  @AfterEach
  void restoreEnvironment() throws Exception {
    if (environment != null) onEdt(environment::close);
  }

  @Test
  void constructingAdapterDoesNotStartOrClaimAWorker() throws Exception {
    onEdt(
        () -> {
          assertFalse(adapter.isStarting());
          assertNull(adapter.ownedEngine());
          assertNull(Lizzie.frame.analysisEngine);
          assertTrue(executor.pending.isEmpty());
          assertSame(environment.board, Lizzie.board);
          assertSame(environment.browsingNode, Lizzie.board.getHistory().getCurrentHistoryNode());
        });
  }

  @Test
  void uncheckedStartupFailureSettlesAcquisitionAndAllowsRetry() throws Exception {
    PhysicalWorker recovered = worker();
    int[] attempts = {0};
    Client first = new Client();
    Client retry = new Client();
    onEdt(
        () -> {
          adapter =
              new AutomaticQuickAnalysisEngineAdapter(
                  Lizzie.frame,
                  persistent -> {
                    if (attempts[0]++ == 0) {
                      throw new IllegalArgumentException("controlled invalid engine configuration");
                    }
                    return recovered;
                  },
                  executor::submit);
          first.acquire();
        });
    executor.run(STARTUP);
    onEdt(
        () -> {
          assertTrue(first.delivered);
          assertNull(first.use);
          assertFalse(first.registration.inProgress());
          assertFalse(adapter.isStarting());
          retry.acquire();
        });
    executor.run(STARTUP);
    onEdt(
        () -> {
          assertTrue(retry.delivered);
          assertNotNull(retry.use);
          assertSame(recovered, adapter.ownedEngine());
          assertFalse(adapter.isStarting());
          assertEquals(2, attempts[0]);
        });
  }

  @Test
  void cancelledAcquisitionLeavesPendingStartupAvailableToSuccessor() throws Exception {
    PhysicalWorker worker = worker();
    Client first = new Client();
    Client second = new Client();
    onEdt(
        () -> {
          first.acquire();
          assertTrue(first.registration.inProgress());
          first.registration.cancel();
          second.acquire();
          assertTrue(second.registration.inProgress());
          assertFalse(first.registration.inProgress());
        });

    executor.run(STARTUP);
    onEdt(
        () -> {
          assertFalse(first.delivered);
          second.request();
          first.registration.cancel();
          assertSame(worker, adapter.ownedEngine());
          assertSame(worker, Lizzie.frame.analysisEngine);
        });
    executor.run(REQUEST);
    worker.succeed(ForegroundRestoreResult.NOT_REQUIRED);
    flushEdt();

    onEdt(
        () -> {
          assertEquals(List.of(environment.browsingNode), worker.dispatchedNodes);
          assertEquals(
              List.of(new RequestResult(false, ForegroundRestoreResult.NOT_REQUIRED)),
              second.results);
          assertTrue(first.results.isEmpty());
          assertTrue(worker.live);
          assertFalse(executor.has(UNCLAIMED_CLOSE));
          assertFalse(executor.has(STARTUP));
        });
  }

  @Test
  void lateReleaseAndCallbacksFromPreviousUseCannotClearSuccessorRequest() throws Exception {
    PhysicalWorker worker = worker();
    Client first = new Client();
    Client second = new Client();
    onEdt(
        () -> {
          adapter.preload();
          first.acquire();
        });
    executor.run(STARTUP);
    onEdt(first::request);
    executor.run(REQUEST);
    Consumer<ForegroundRestoreResult> oldCompletion = worker.completion;
    Consumer<ForegroundRestoreResult> oldFailure = worker.failure;
    worker.succeed(ForegroundRestoreResult.NOT_REQUIRED);
    flushEdt();
    onEdt(() -> first.release(false));
    executor.run(RETURN);
    onEdt(
        () -> {
          second.acquire();
          second.request();
          first.registration.cancel();
          first.release(false);
          assertSame(worker, adapter.ownedEngine());
        });
    oldCompletion.accept(ForegroundRestoreResult.FAILED);
    oldFailure.accept(ForegroundRestoreResult.FAILED);
    flushEdt();
    executor.run(REQUEST);
    worker.succeed(ForegroundRestoreResult.SUCCEEDED);
    flushEdt();

    onEdt(
        () -> {
          assertEquals(
              List.of(new RequestResult(false, ForegroundRestoreResult.NOT_REQUIRED)),
              first.results);
          assertEquals(
              List.of(new RequestResult(false, ForegroundRestoreResult.SUCCEEDED)), second.results);
          assertEquals(
              List.of(environment.browsingNode, environment.browsingNode), worker.dispatchedNodes);
          assertSame(worker, Lizzie.frame.analysisEngine);
          assertTrue(worker.live);
          assertFalse(executor.has(CLOSE));
        });
  }

  @Test
  void invalidatedStartupClosesOnlyUnclaimedWorkerWhileSuccessorStarts() throws Exception {
    PhysicalWorker obsolete = worker();
    PhysicalWorker current = worker();
    Client first = new Client();
    Client second = new Client();
    onEdt(
        () -> {
          first.acquire();
          first.registration.cancel();
          adapter.invalidateStartup();
          second.acquire();
        });
    executor.run(STARTUP);
    onEdt(
        () -> {
          assertFalse(first.delivered);
          assertFalse(second.delivered);
          assertTrue(second.registration.inProgress());
        });
    // Let the replacement become usable before the old worker's physical close runs.
    executor.run(STARTUP);
    onEdt(second::request);
    executor.run(REQUEST);
    executor.run(UNCLAIMED_CLOSE);
    onEdt(first.registration::cancel);
    current.succeed(ForegroundRestoreResult.NOT_REQUIRED);
    flushEdt();

    onEdt(
        () -> {
          assertFalse(obsolete.live);
          assertTrue(obsolete.dispatchedNodes.isEmpty());
          assertTrue(current.live);
          assertSame(current, Lizzie.frame.analysisEngine);
          assertSame(current, adapter.ownedEngine());
          assertEquals(List.of(environment.browsingNode), current.dispatchedNodes);
          assertEquals(
              List.of(new RequestResult(false, ForegroundRestoreResult.NOT_REQUIRED)),
              second.results);
        });
  }

  @Test
  void preloadIsRetainedWithoutAClientAndDeduplicatesPendingStartup() throws Exception {
    PhysicalWorker worker = worker();
    Client cancelled = new Client();
    onEdt(
        () -> {
          adapter.preload();
          adapter.preload();
          cancelled.acquire();
          cancelled.registration.cancel();
        });
    executor.run(STARTUP);
    Client consumer = new Client();
    onEdt(
        () -> {
          assertFalse(cancelled.delivered);
          assertNull(adapter.ownedEngine());
          assertSame(worker, Lizzie.frame.analysisEngine);
          assertFalse(executor.has(STARTUP));
          consumer.acquire();
          consumer.request();
        });
    executor.run(REQUEST);
    worker.succeed(ForegroundRestoreResult.NOT_REQUIRED);
    flushEdt();
    onEdt(() -> consumer.release(false));
    executor.run(RETURN);
    onEdt(
        () -> {
          assertTrue(worker.live);
          assertSame(worker, Lizzie.frame.analysisEngine);
          assertNull(adapter.ownedEngine());
          assertEquals(
              List.of(new RequestResult(false, ForegroundRestoreResult.NOT_REQUIRED)),
              consumer.results);
          assertEquals(List.of(ForegroundRestoreResult.NOT_REQUIRED), consumer.releases);
          assertFalse(executor.has(UNCLAIMED_CLOSE));
        });
  }

  @Test
  void preloadRequestedDuringStartupSurvivesTheOriginalAcquisitionCancellation() throws Exception {
    PhysicalWorker worker = worker();
    Client original = new Client();
    onEdt(
        () -> {
          original.acquire();
          adapter.preload();
          original.registration.cancel();
        });
    executor.run(STARTUP);
    Client later = new Client();
    onEdt(
        () -> {
          assertFalse(original.delivered);
          assertSame(
              worker,
              Lizzie.frame.analysisEngine,
              "A preload request must survive cancellation of the separate automatic client");
          assertFalse(executor.has(UNCLAIMED_CLOSE));
          later.acquire();
          later.request();
        });
    executor.run(REQUEST);
    worker.succeed(ForegroundRestoreResult.NOT_REQUIRED);
    flushEdt();
    onEdt(
        () -> {
          assertTrue(worker.live);
          assertSame(worker, adapter.ownedEngine());
          assertEquals(List.of(environment.browsingNode), worker.dispatchedNodes);
          assertEquals(
              List.of(new RequestResult(false, ForegroundRestoreResult.NOT_REQUIRED)),
              later.results);
          assertFalse(executor.has(STARTUP));
        });
  }

  @Test
  void transferSuppressesQueuedAutomaticDispatchWithoutStoppingManualReuse() throws Exception {
    PhysicalWorker worker = worker();
    Client automatic = new Client();
    onEdt(automatic::acquire);
    executor.run(STARTUP);
    onEdt(
        () -> {
          automatic.request();
          assertTrue(automatic.use.inProgress());
          automatic.release(true);
        });
    Consumer<ForegroundRestoreResult> staleCompletion = worker.completion;
    // The manual owner is authorized only once the transfer callback has run.
    executor.run(RETURN);
    List<ForegroundRestoreResult> manualResults = new ArrayList<>();
    onEdt(
        () -> {
          assertEquals(List.of(ForegroundRestoreResult.NOT_REQUIRED), automatic.releases);
          assertNull(adapter.ownedEngine());
          assertSame(worker, Lizzie.frame.analysisEngine);
          worker.setCompletionCallback(manualResults::add);
        });
    worker.startRequest(0, 1, false);
    onEdt(() -> automatic.release(false));
    executor.run(REQUEST);
    staleCompletion.accept(ForegroundRestoreResult.FAILED);
    flushEdt();
    worker.succeed(ForegroundRestoreResult.SUCCEEDED);
    flushEdt();

    onEdt(
        () -> {
          assertTrue(worker.manualRequest);
          assertTrue(worker.dispatchedNodes.isEmpty());
          assertEquals(List.of(ForegroundRestoreResult.SUCCEEDED), manualResults);
          assertTrue(automatic.results.isEmpty());
          assertTrue(worker.live);
          assertSame(worker, Lizzie.frame.analysisEngine);
          assertFalse(executor.has(CLOSE));
          assertFalse(automatic.use.inProgress());
        });
  }

  @Test
  void failedForegroundRestoreCannotBeOverwrittenByLaterSuccessfulClose() throws Exception {
    assertFailureRelease(
        ForegroundRestoreResult.FAILED,
        ForegroundRestoreResult.SUCCEEDED,
        ForegroundRestoreResult.FAILED);
  }

  @Test
  void requestFailureWithSuccessfulRestoreRemainsSafeAfterNoOpClose() throws Exception {
    assertFailureRelease(
        ForegroundRestoreResult.SUCCEEDED,
        ForegroundRestoreResult.NOT_REQUIRED,
        ForegroundRestoreResult.SUCCEEDED);
  }

  private void assertFailureRelease(
      ForegroundRestoreResult requestRestore,
      ForegroundRestoreResult closeRestore,
      ForegroundRestoreResult expectedRestore)
      throws Exception {
    PhysicalWorker worker = worker();
    worker.shared = true;
    Client client = new Client();
    onEdt(client::acquire);
    executor.run(STARTUP);
    onEdt(client::request);
    executor.run(REQUEST);
    onEdt(() -> assertTrue(client.use.requiresForegroundRestore()));
    worker.fail(requestRestore);
    flushEdt();
    onEdt(
        () -> {
          assertEquals(List.of(new RequestResult(true, requestRestore)), client.results);
          client.release(false);
          assertTrue(client.releases.isEmpty());
        });
    executor.run(CLOSE);
    onEdt(() -> assertTrue(client.releases.isEmpty(), "Release must await physical handback"));
    worker.finishClose(closeRestore);
    flushEdt();
    onEdt(
        () -> {
          assertEquals(List.of(expectedRestore), client.releases);
          assertFalse(worker.live);
          assertNull(adapter.ownedEngine());
          assertNull(Lizzie.frame.analysisEngine);
          assertSame(environment.browsingNode, Lizzie.board.getHistory().getCurrentHistoryNode());
        });
  }

  private PhysicalWorker worker() throws Exception {
    PhysicalWorker worker = allocate(PhysicalWorker.class);
    worker.live = true;
    worker.dispatchedNodes = new ArrayList<>();
    workers.addLast(worker);
    return worker;
  }

  private final class Client {
    private Registration registration;
    private Use use;
    private boolean delivered;
    private final List<RequestResult> results = new ArrayList<>();
    private final List<ForegroundRestoreResult> releases = new ArrayList<>();

    private void acquire() {
      registration =
          adapter.acquire(
              acquired -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                delivered = true;
                use = acquired;
              });
    }

    private void request() {
      assertNotNull(use, "A usable worker must have been handed off");
      use.requestMissing(
          result -> {
            assertTrue(SwingUtilities.isEventDispatchThread());
            results.add(result);
          });
    }

    private void release(boolean transfer) {
      use.release(
          transfer,
          result -> {
            assertTrue(SwingUtilities.isEventDispatchThread());
            releases.add(result);
          });
    }
  }

  private static final class ControlledExecutor {
    private final List<BackgroundAction> pending = new ArrayList<>();

    private void submit(String name, Runnable action) {
      assertTrue(SwingUtilities.isEventDispatchThread());
      pending.add(new BackgroundAction(name, action));
    }

    private boolean has(String name) {
      return pending.stream().anyMatch(action -> action.name().equals(name));
    }

    private void run(String name) throws Exception {
      Runnable[] selected = new Runnable[1];
      onEdt(
          () -> {
            for (int index = 0; index < pending.size(); index++) {
              if (pending.get(index).name().equals(name)) {
                selected[0] = pending.remove(index).action();
                return;
              }
            }
            throw new AssertionError("No pending physical operation: " + name);
          });
      selected[0].run();
      flushEdt();
    }
  }

  private record BackgroundAction(String name, Runnable action) {}

  /** Only the process, request completion, and physical handback edges are controlled. */
  private static final class PhysicalWorker extends AnalysisEngine {
    private boolean live;
    private boolean automatic;
    private boolean shared;
    private boolean lifecycle;
    private boolean manualRequest;
    private boolean admissionClosed;
    private List<BoardHistoryNode> dispatchedNodes;
    private Consumer<ForegroundRestoreResult> completion;
    private Consumer<ForegroundRestoreResult> failure;
    private Consumer<ForegroundRestoreResult> closeFinished;

    private PhysicalWorker() throws IOException {
      super(false);
    }

    @Override
    public boolean isLoaded() {
      return live;
    }

    @Override
    public boolean isRunning() {
      return live;
    }

    @Override
    public boolean matchesCurrentAnalysisBackend() {
      return live;
    }

    @Override
    public boolean isAutomaticBackgroundTask() {
      return automatic;
    }

    @Override
    public boolean usesSharedForegroundEngine() {
      return shared;
    }

    @Override
    public synchronized boolean hasRequestLifecycleInProgress() {
      return lifecycle;
    }

    @Override
    public synchronized boolean isAnalysisInProgress() {
      return lifecycle;
    }

    @Override
    public void setCompletionCallback(Consumer<ForegroundRestoreResult> callback) {
      completion = callback;
    }

    @Override
    public void setFailureCallback(Consumer<ForegroundRestoreResult> callback) {
      failure = callback;
    }

    @Override
    public void clearRequestCallbacks() {
      completion = null;
      failure = null;
    }

    @Override
    public int startRequestMissingMainline(boolean showProgressDialog) {
      assertFalse(SwingUtilities.isEventDispatchThread());
      assertFalse(showProgressDialog);
      assertTrue(live, "A closed worker cannot dispatch a request");
      assertFalse(
          admissionClosed, "A stale owner must not close the successor's request admission");
      assertNotNull(completion);
      assertNotNull(failure);
      dispatchedNodes.add(Lizzie.board.getHistory().getCurrentHistoryNode());
      lifecycle = true;
      return 1;
    }

    @Override
    public void startRequest(int startMove, int endMove, boolean showProgressDialog) {
      assertTrue(live, "The transferred worker must remain usable for manual analysis");
      assertFalse(admissionClosed, "Transfer must preserve manual request admission");
      manualRequest = true;
      lifecycle = true;
    }

    private void succeed(ForegroundRestoreResult restore) {
      lifecycle = false;
      assertNotNull(
          completion, "The current request completion must not be cleared by a stale owner");
      completion.accept(restore);
    }

    private void fail(ForegroundRestoreResult restore) {
      lifecycle = false;
      assertNotNull(failure);
      failure.accept(restore);
    }

    @Override
    public void requestShutdown() {
      super.requestShutdown();
      admissionClosed = true;
    }

    @Override
    public void normalQuit() {
      assertFalse(SwingUtilities.isEventDispatchThread());
      admissionClosed = true;
      live = false;
      lifecycle = false;
    }

    @Override
    public void normalQuitWithRestoreResult(Consumer<ForegroundRestoreResult> finished) {
      normalQuit();
      closeFinished = finished;
    }

    private void finishClose(ForegroundRestoreResult restore) {
      assertNotNull(closeFinished);
      Consumer<ForegroundRestoreResult> finished = closeFinished;
      closeFinished = null;
      finished.accept(restore);
    }
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final Config previousConfig = Lizzie.config;
    private final Board previousBoard = Lizzie.board;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final Leelaz previousEngine = Lizzie.leelaz;
    private final BoardRenderer previousRenderer = LizzieFrame.boardRenderer;
    private final BoardRenderer previousSecondRenderer = LizzieFrame.boardRenderer2;
    private final String previousTitle = LizzieFrame.fileNameTitle;
    private final File previousFile = LizzieFrame.curFile;
    private final boolean previousForceRecreate = LizzieFrame.forceRecreate;
    private Board board;
    private BoardHistoryNode browsingNode;

    private void open() throws Exception {
      Lizzie.config = allocate(Config.class);
      Lizzie.config.quickAnalysisLightweightModelEnabled = false;
      Lizzie.frame = null;
      Lizzie.leelaz = null;
      LizzieFrame.boardRenderer = null;
      LizzieFrame.boardRenderer2 = null;
      board = new Board();
      Lizzie.board = board;
      BoardData root = board.getHistory().getData();
      Stone[] stones = root.stones.clone();
      stones[0] = Stone.BLACK;
      var zobrist = root.zobrist.clone();
      zobrist.toggleStone(0, 0, Stone.BLACK);
      board
          .getHistory()
          .add(
              BoardData.move(
                  stones,
                  new int[] {0, 0},
                  Stone.BLACK,
                  false,
                  zobrist,
                  1,
                  new int[stones.length],
                  0,
                  0,
                  50,
                  0));
      browsingNode = board.getHistory().getCurrentHistoryNode();
      Lizzie.frame = allocate(LizzieFrame.class);
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousEngine;
      LizzieFrame.boardRenderer = previousRenderer;
      LizzieFrame.boardRenderer2 = previousSecondRenderer;
      LizzieFrame.fileNameTitle = previousTitle;
      LizzieFrame.curFile = previousFile;
      LizzieFrame.forceRecreate = previousForceRecreate;
    }
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }

  private static void flushEdt() throws Exception {
    onEdt(() -> {});
  }

  private static void onEdt(CheckedAction action) throws Exception {
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              action.run();
            } catch (Exception failure) {
              throw new RuntimeException(failure);
            }
          });
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof AssertionError assertion) throw assertion;
      if (cause instanceof RuntimeException runtime
          && runtime.getCause() instanceof Exception checked) {
        throw checked;
      }
      throw failure;
    }
  }

  @FunctionalInterface
  private interface CheckedAction {
    void run() throws Exception;
  }
}
