package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
import featurecat.lizzie.EngineStartupStatus;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.DesktopTimeControl;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.SgfWinLossList;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.EngineCountDown;
import featurecat.lizzie.rules.Movelist;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import featurecat.lizzie.util.Utils;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.json.JSONException;
import org.json.JSONObject;

public class EngineManager {
  private static final long ENGINE_GAME_PHYSICAL_REQUEST_FORCE_GRACE_MILLIS = 5_000L;
  private static final ScheduledThreadPoolExecutor ENGINE_GAME_PHYSICAL_REQUEST_WATCHDOG =
      createEngineGamePhysicalRequestWatchdogExecutor();
  private static final Set<Leelaz> REMOTE_ENGINES_RESTARTING = ConcurrentHashMap.newKeySet();

  /** Serializes the provisional owner pointer and its committed index/empty-state publication. */
  private static final Object ENGINE_SELECTION_STATE_LOCK = new Object();

  /** Serializes token-checked engine-game UI terminal/preparing presentations. */
  private static final ReentrantLock ENGINE_GAME_UI_MUTATION_LOCK = new ReentrantLock();

  /**
   * Orders legacy ordinary-analysis side effects before or after engine-game admission without
   * holding the selection monitor across board, UI, or engine work.
   */
  private static final ReentrantLock ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK =
      new ReentrantLock();
  private static boolean ordinaryAnalysisOutputMutationInProgress;

  /** Exact failed incarnations remain unavailable for selection until their blocking stop ends. */
  private static final Map<Leelaz, FailedEngineQuarantine> FAILED_ENGINE_QUARANTINES =
      new IdentityHashMap<>();
  private static volatile FailedEngineStopThreadFactory failedEngineStopThreadFactory =
      Thread::new;
  private static final InitialEnginePreloadScheduler DEFAULT_INITIAL_ENGINE_PRELOAD_SCHEDULER =
      new InitialEnginePreloadScheduler() {
        @Override
        public Thread create(Runnable work, String name) {
          return new Thread(work, name);
        }

        @Override
        public void configure(Thread worker) {
          worker.setDaemon(true);
        }

        @Override
        public void start(Thread worker) {
          worker.start();
        }
      };
  private static volatile InitialEnginePreloadScheduler initialEnginePreloadScheduler =
      DEFAULT_INITIAL_ENGINE_PRELOAD_SCHEDULER;
  private static final InitialEngineStartupScheduler DEFAULT_INITIAL_ENGINE_STARTUP_SCHEDULER =
      new InitialEngineStartupScheduler() {
        @Override
        public Thread create(Runnable work, String name) {
          return new Thread(work, name);
        }

        @Override
        public void configure(Thread worker) {
          worker.setDaemon(true);
        }

        @Override
        public void start(Thread worker) {
          worker.start();
        }

        @Override
        public void dispatch(Runnable work) {
          SwingUtilities.invokeLater(work);
        }
      };
  private static volatile InitialEngineStartupScheduler initialEngineStartupScheduler =
      DEFAULT_INITIAL_ENGINE_STARTUP_SCHEDULER;
  private static volatile InitialEngineStartFailureSettlementHook
      initialEngineStartFailureSettlementHook;
  private static final Object INITIAL_MANAGER_STARTUP_LOCK = new Object();
  private static final AtomicLong INITIAL_MANAGER_STARTUP_SEQUENCE = new AtomicLong();
  private static volatile InitialManagerStartupAuthority activeInitialManagerStartup;
  private static long engineGameTransactionSequence;
  private static EngineGameTransaction activeEngineGameTransaction;
  private static EngineGameTransaction retiringEngineGameTransaction;
  /**
   * A failed participant is recovered only after its game transaction has fully retired. While
   * this gate is installed no successor game may be admitted: the recovery worker owns the exact
   * failed slot/incarnation until it either publishes a replacement incarnation or gives up.
   */
  private static EngineGameRecoveryBatch activeEngineGameRecoveryBatch;
  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;
  public static boolean isUpdating = false;
  public volatile List<Leelaz> engineList;
  public static volatile int currentEngineNo;
  private int engineNo = 1;
  public static volatile int currentEngineNo2 = -1;
  public static volatile boolean isEmpty = false;
  String name = "";
  public static volatile EngineGameInfo engineGameInfo = new EngineGameInfo();
  public static volatile boolean isEngineGame = false;
  public static volatile boolean isPreEngineGame = false;
  public static boolean isSaveingEngineSGF = false;
  public EngineCountDown playingAgainstHumanEngineCountDown;
  public EngineCountDown firstEngineCountDown;
  public EngineCountDown secondEngineCountDown;
  private ScheduledThreadPoolExecutor timeScheduled;
  private int timeScheduledTimes;
  Timer timer;
  private final EngineSwitchUiTracker engineSwitchUiTracker = new EngineSwitchUiTracker();
  private final AtomicReference<EngineSwitchTransaction> engineSwitchTransaction =
      new AtomicReference<>();
  private final AtomicReference<FailedRollbackRecovery> failedRollbackRecovery =
      new AtomicReference<>();
  private InitialManagerStartupAuthority initialManagerStartup;

  enum EngineGamePhase {
    PREPARING,
    DISPATCHED,
    ACTIVE,
    FAILED,
    CANCELLED
  }

  enum EngineGameRecoveryCause {
    PROCESS_EXIT,
    REMOTE_DISCONNECT,
    OPENCL_NATIVE_EXIT
  }

  enum EngineGameRecoveryDisposition {
    NOT_ENGINE_GAME_PARTICIPANT,
    HANDLED
  }

  static final class EngineGameTransaction {
    private final EngineManager manager;
    private final EngineGameInfo gameInfo;
    private final long epoch;
    private final int blackIndex;
    private final int whiteIndex;
    private final Leelaz blackEngine;
    private final Leelaz whiteEngine;
    private final boolean noCapture;
    private final boolean canSuicidal;
    private final boolean newMoveNumberInBranch;
    private final Leelaz previousPrimary;
    private final long previousPrimaryGeneration;
    private final AtomicLong deadlineNanos;
    private final AtomicInteger tuningBudgetParticipants = new AtomicInteger();
    private final AtomicInteger operationsInFlight = new AtomicInteger();
    private final Set<EngineGamePhysicalRequestLease> physicalRequests =
        ConcurrentHashMap.newKeySet();
    private final AtomicBoolean physicalRequestWatchdogScheduled = new AtomicBoolean();
    private final AtomicBoolean physicalRequestWatchdogRun = new AtomicBoolean();
    private final ReentrantLock mutationLock = new ReentrantLock();
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean rollbackStarted = new AtomicBoolean();
    private final AtomicBoolean rollbackFinished = new AtomicBoolean();
    private final AtomicBoolean retirementCompletionClaimed = new AtomicBoolean();
    private final AtomicBoolean retirementFinished = new AtomicBoolean();
    private final AtomicReference<EngineGameRetirementContinuation> retirementContinuation =
        new AtomicReference<>();
    /** Guarded by {@link #ENGINE_SELECTION_STATE_LOCK}. */
    private final List<EngineGameDeferredRecovery> deferredRecoveries = new ArrayList<>();
    private volatile EngineGamePhase phase = EngineGamePhase.PREPARING;
    /** Exact bindings published while each participant is still PREPARING/DISPATCHED. */
    private volatile Object blackStartupIncarnation;
    private volatile Object whiteStartupIncarnation;
    private volatile Object blackIncarnation;
    private volatile Object whiteIncarnation;
    private volatile EngineCountDown blackCountDown;
    private volatile EngineCountDown whiteCountDown;
    private volatile boolean gameWasActiveBeforeTerminal;
    private volatile boolean gameWasStartingBeforeTerminal;
    private volatile Throwable terminalFailure;
    private volatile boolean externalTerminalOwner;
    private volatile long inactiveEpoch = -1L;

    private EngineGameTransaction(
        EngineManager manager,
        EngineGameInfo gameInfo,
        long epoch,
        int blackIndex,
        Leelaz blackEngine,
        int whiteIndex,
        Leelaz whiteEngine,
        Leelaz previousPrimary,
        long previousPrimaryGeneration,
        long deadlineNanos) {
      this.manager = manager;
      this.gameInfo = gameInfo;
      this.epoch = epoch;
      this.blackIndex = blackIndex;
      this.blackEngine = blackEngine;
      this.whiteIndex = whiteIndex;
      this.whiteEngine = whiteEngine;
      this.noCapture = Lizzie.config != null && Lizzie.config.noCapture;
      // A match has one ruleset. If either participant cannot represent suicide, reject suicide
      // for both sides instead of changing legality when PRIMARY changes after each move.
      this.canSuicidal = blackEngine.canSuicidal && whiteEngine.canSuicidal;
      this.newMoveNumberInBranch =
          Lizzie.config != null && Lizzie.config.newMoveNumberInBranch;
      this.previousPrimary = previousPrimary;
      this.previousPrimaryGeneration = previousPrimaryGeneration;
      this.deadlineNanos = new AtomicLong(deadlineNanos);
    }

    EngineGamePhase phase() {
      return phase;
    }

    long epoch() {
      return epoch;
    }

    Throwable terminalFailure() {
      return terminalFailure;
    }

    boolean isGenmove() {
      return gameInfo.isGenmove;
    }

    int operationsInFlightForTest() {
      return operationsInFlight.get();
    }

    boolean retirementFinishedForTest() {
      return retirementFinished.get();
    }

    int openPhysicalRequestsForTest() {
      int open = 0;
      for (EngineGamePhysicalRequestLease request : physicalRequests) {
        if (request.isOpen()) {
          open++;
        }
      }
      return open;
    }
  }

  /** One exact failed participant generation captured before terminal cleanup mutates the runtime. */
  static final class EngineGameDeferredRecovery {
    private final EngineGameTransaction transaction;
    private final long transactionEpoch;
    private final int engineIndex;
    private final Leelaz engine;
    private final Object failedIncarnation;
    private final AtomicBoolean completed = new AtomicBoolean();
    private volatile boolean remoteDisconnect;
    private volatile boolean openClNativeExit;
    private volatile Object replacementIncarnation;
    private volatile Thread worker;
    private volatile InitialEngineStartupSynchronization synchronization;
    private volatile Leelaz.UpdateEngineStartAttempt startAttempt;
    private volatile boolean startAttemptCommitted;
    private volatile boolean recoveredSuccessfully;

    private EngineGameDeferredRecovery(
        EngineGameTransaction transaction,
        int engineIndex,
        Leelaz engine,
        Object failedIncarnation,
        EngineGameRecoveryCause cause) {
      this.transaction = transaction;
      this.transactionEpoch = transaction.epoch;
      this.engineIndex = engineIndex;
      this.engine = engine;
      this.failedIncarnation = failedIncarnation;
      mergeCause(cause);
    }

    private void mergeCause(EngineGameRecoveryCause cause) {
      if (cause == EngineGameRecoveryCause.REMOTE_DISCONNECT) {
        remoteDisconnect = true;
      } else if (cause == EngineGameRecoveryCause.OPENCL_NATIVE_EXIT) {
        openClNativeExit = true;
      }
    }

    int engineIndexForTest() {
      return engineIndex;
    }

    Object failedIncarnationForTest() {
      return failedIncarnation;
    }
  }

  private static final class EngineGameRecoveryBatch {
    private final EngineGameTransaction transaction;
    private final List<EngineGameDeferredRecovery> recoveries;
    private int nextIndex;
    private EngineGameDeferredRecovery current;

    private EngineGameRecoveryBatch(
        EngineGameTransaction transaction, List<EngineGameDeferredRecovery> recoveries) {
      this.transaction = transaction;
      this.recoveries = recoveries;
    }
  }

  private static final class EngineGameParticipantProbe {
    private final Leelaz engine;
    private final Object incarnation;

    private EngineGameParticipantProbe(Leelaz engine, Object incarnation) {
      this.engine = engine;
      this.incarnation = incarnation;
    }
  }

  /**
   * A short admission token for work that may block after it leaves the selection lock.
   *
   * <p>Cancellation never waits for a lease. The retiring transaction remains installed until all
   * already-admitted work returns, so a replacement game cannot observe side effects from an old
   * worker. Callers must recheck {@link #isCurrent()} after every blocking or fallible step before
   * issuing another command or publishing global state.
   */
  static final class EngineGameOperationLease implements AutoCloseable {
    private final EngineGameTransaction transaction;
    private final AtomicBoolean closed = new AtomicBoolean();

    private EngineGameOperationLease(EngineGameTransaction transaction) {
      this.transaction = transaction;
    }

    boolean isCurrent() {
      return isCurrentEngineGameTransaction(transaction);
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      transaction.operationsInFlight.decrementAndGet();
      finishAutomaticEngineGameRetirementIfQuiescent(transaction);
    }
  }

  private static final class EngineGameWorkerDispatch {
    private final AtomicInteger owner = new AtomicInteger();
  }

  private static final class EngineGameRetirementContinuation {
    private final Runnable action;
    private final Runnable schedulingFailure;

    private EngineGameRetirementContinuation(Runnable action, Runnable schedulingFailure) {
      this.action = action;
      this.schedulingFailure = schedulingFailure;
    }
  }

  private static final class EngineGameInactiveUiToken {
    private final EngineManager manager;
    private final EngineGameInfo gameInfo;
    private final long inactiveEpoch;
    private final EngineGameTransaction expectedRetiring;

    private EngineGameInactiveUiToken(
        EngineManager manager,
        EngineGameInfo gameInfo,
        long inactiveEpoch,
        EngineGameTransaction expectedRetiring) {
      this.manager = manager;
      this.gameInfo = gameInfo;
      this.inactiveEpoch = inactiveEpoch;
      this.expectedRetiring = expectedRetiring;
    }
  }

  private static final class EngineGameStopClaim {
    private final EngineGameTransaction transaction;
    private final EngineGameInfo gameInfo;
    private final boolean wasActive;
    private final boolean wasStarting;
    private final long invalidationEpoch;

    private EngineGameStopClaim(
        EngineGameTransaction transaction,
        EngineGameInfo gameInfo,
        boolean wasActive,
        boolean wasStarting,
        long invalidationEpoch) {
      this.transaction = transaction;
      this.gameInfo = gameInfo;
      this.wasActive = wasActive;
      this.wasStarting = wasStarting;
      this.invalidationEpoch = invalidationEpoch;
    }
  }

  public EngineManager(Config config, int index, boolean loadDefault)
      throws JSONException, IOException {
    this(config, index, loadDefault, Utils.getEngineData(), Leelaz::new);
  }

  @FunctionalInterface
  interface InitialEngineFactory {
    Leelaz create(String command) throws JSONException, IOException;
  }

  interface InitialEnginePreloadScheduler {
    Thread create(Runnable work, String name);

    void configure(Thread worker);

    void start(Thread worker);
  }

  interface InitialEngineStartupScheduler extends InitialEnginePreloadScheduler {
    void dispatch(Runnable work);
  }

  @FunctionalInterface
  interface InitialEngineStartFailureSettlementHook {
    void afterTargetCleanupClaimed(Leelaz engine);
  }

  static void setInitialEnginePreloadSchedulerForTest(InitialEnginePreloadScheduler scheduler) {
    initialEnginePreloadScheduler =
        scheduler == null ? DEFAULT_INITIAL_ENGINE_PRELOAD_SCHEDULER : scheduler;
  }

  static void setInitialEngineStartupSchedulerForTest(InitialEngineStartupScheduler scheduler) {
    initialEngineStartupScheduler =
        scheduler == null ? DEFAULT_INITIAL_ENGINE_STARTUP_SCHEDULER : scheduler;
  }

  static void setInitialEngineStartFailureSettlementHookForTest(
      InitialEngineStartFailureSettlementHook hook) {
    initialEngineStartFailureSettlementHook = hook;
  }

  EngineManager(
      Config config,
      int index,
      boolean loadDefault,
      ArrayList<EngineData> engineData,
      InitialEngineFactory engineFactory)
      throws JSONException, IOException {
    initialManagerStartup = claimInitialManagerStartup(Lizzie.board);
    if (engineData == null) {
      engineData = new ArrayList<>();
    }
    if (engineFactory == null) {
      throw new IllegalArgumentException("engineFactory");
    }
    if (index > engineData.size() - 1) {
      index = 0;
    }
    int selectedPosition = index;
    int selectedEngineIndex = index;
    if (loadDefault) {
      for (int candidatePosition = 0; candidatePosition < engineData.size(); candidatePosition++) {
        EngineData candidate = engineData.get(candidatePosition);
        if (candidate != null && candidate.isDefault) {
          selectedPosition = candidatePosition;
          selectedEngineIndex = candidatePosition;
          break;
        }
      }
    }
    engineList = new ArrayList<Leelaz>();
    InitialEngineStartupCandidate initialStartupCandidate = null;
    // engineList.add(lz);
    for (int i = 0; i < engineData.size(); i++) {
      EngineData engineDt = engineData.get(i);
      Leelaz e;
      e = engineFactory.create(engineDt.commands);
      e.preload = engineDt.preload;
      e.width = engineDt.width;
      e.height = engineDt.height;
      e.oriWidth = engineDt.width;
      e.oriHeight = engineDt.height;
      e.komi = engineDt.komi;
      e.orikomi = engineDt.komi;
      e.useJavaSSH = engineDt.useJavaSSH;
      e.ip = engineDt.ip;
      e.port = engineDt.port;
      e.useKeyGen = engineDt.useKeyGen;
      e.keyGenPath = engineDt.keyGenPath;
      e.userName = engineDt.userName;
      e.password = engineDt.password;
      e.initialCommand = engineDt.initialCommand;
      e.gtpConfigurationProtocol = engineDt.gtpConfigurationProtocol;
      e.gtpConfigurationProfile = copyProfile(engineDt.gtpConfigurationProfile);
      if (i == selectedPosition) {
        int startupIndex = i;
        Board restoreBoard = Lizzie.board;
        boolean boardShapeChanges = e.oriWidth != 19 || e.oriHeight != 19;
        e.preload = true;
        e.firstLoad = true;
        initialStartupCandidate =
            new InitialEngineStartupCandidate(startupIndex, e, restoreBoard, boardShapeChanges);
      } else {
        if (e.preload) {
          dispatchInitialEnginePreload(e, i);
        }
      }
      engineList.add(e);
    }
    engineNo = selectedEngineIndex;
    if (!activateInitialManagerStartup(initialManagerStartup)) {
      return;
    }
    Leelaz emptyPlaceholder =
        Lizzie.leelaz != null && !Lizzie.leelaz.isStarted() ? Lizzie.leelaz : null;
    publishPrimarySelectionState(emptyPlaceholder, -1, true);
    if (initialStartupCandidate != null) {
      submitInitialEngineStartup(initialStartupCandidate);
    }
    if (selectedPosition == -1) {
      if (Lizzie.leelaz != null) {
        Lizzie.leelaz.isKatago = true;
        Lizzie.leelaz.isLoaded = true;
      }
      if (Menu.engineMenu != null) {
        Menu.engineMenu.setText(resourceBundle.getString("Menu.noEngine"));
      }
      if (Lizzie.config.isDoubleEngineMode())
        if (Menu.engineMenu2 != null) {
          Menu.engineMenu2.setText(resourceBundle.getString("Menu.noEngine"));
        }
      isEmpty = true;
      if (LizzieFrame.menu != null) LizzieFrame.menu.updateMenuStatusForEngine();
      if (Lizzie.frame != null) {
        Lizzie.frame.reSetLoc();
        Lizzie.frame.addInput(false);
      }

      SwingUtilities.invokeLater(
          new Runnable() {
            public void run() {
              if (Lizzie.config.uiConfig.optBoolean("show-badmoves-frame", false)) {
                Lizzie.frame.toggleBadMoves();
                Lizzie.frame.setVisible(true);
              }
              if (Lizzie.config.uiConfig.optBoolean("show-suggestions-frame", false)) {
                Lizzie.frame.toggleBestMoves();
                Lizzie.frame.setVisible(true);
              }
            }
          });
    }
    if (Lizzie.gtpConsole != null && Lizzie.gtpConsole.console != null) {
      Lizzie.gtpConsole.console.setText("");
    }
    autoCheckEngineAlive(Lizzie.config != null && Lizzie.config.autoCheckEngineAlive);
    if (Lizzie.config != null
        && Lizzie.config.uiConfig != null
        && Lizzie.config.uiConfig.optBoolean("autoload-empty", false)
        && Lizzie.config.showStatus) Lizzie.frame.refresh();
  }

  private static void dispatchInitialEnginePreload(Leelaz engine, int engineIndex) {
    InitialEnginePreloadDispatch dispatch =
        new InitialEnginePreloadDispatch(engine, engineIndex);
    InitialEnginePreloadScheduler scheduler = initialEnginePreloadScheduler;
    try {
      Thread worker =
          scheduler.create(
              dispatch,
              "lizzie-initial-engine-preload-" + engineIndex);
      if (worker == null) {
        throw new IllegalStateException("Initial engine preload scheduler returned no worker");
      }
      scheduler.configure(worker);
      scheduler.start(worker);
      dispatch.schedulingSucceeded();
    } catch (RuntimeException | Error schedulingFailure) {
      dispatch.schedulingFailed(schedulingFailure);
      throw schedulingFailure;
    }
  }

  private static final class InitialEnginePreloadDispatch implements Runnable {
    private final Leelaz engine;
    private final int engineIndex;
    private final CountDownLatch schedulingSettled = new CountDownLatch(1);
    private final AtomicReference<Throwable> schedulingFailure = new AtomicReference<>();

    private InitialEnginePreloadDispatch(Leelaz engine, int engineIndex) {
      this.engine = engine;
      this.engineIndex = engineIndex;
    }

    @Override
    public void run() {
      Leelaz.UpdateEngineStartAttempt startAttempt = null;
      Throwable startupFailure = null;
      try {
        if (schedulingFailure.get() != null) {
          return;
        }
        startAttempt = engine.beginUpdateEngineStartAttempt();
        startAttempt.startEngine(engineIndex);
        startupFailure = awaitSchedulingOutcome();
        if (startupFailure == null) {
          startAttempt.complete();
          startAttempt = null;
        }
      } catch (IOException | RuntimeException | Error failure) {
        startupFailure = failure;
      } finally {
        if (startAttempt != null && startupFailure != null) {
          try {
            startAttempt.failClose(startupFailure);
          } catch (RuntimeException | Error cleanupFailure) {
            suppressEngineStartCleanupFailure(startupFailure, cleanupFailure);
          }
        }
        if (startupFailure != null && startupFailure != schedulingFailure.get()) {
          startupFailure.printStackTrace();
        }
      }
    }

    private Throwable awaitSchedulingOutcome() {
      boolean interrupted = false;
      while (true) {
        try {
          schedulingSettled.await();
          break;
        } catch (InterruptedException interruption) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      return schedulingFailure.get();
    }

    private void schedulingSucceeded() {
      schedulingSettled.countDown();
    }

    private void schedulingFailed(Throwable failure) {
      schedulingFailure.compareAndSet(null, failure);
      schedulingSettled.countDown();
    }
  }

  private static InitialManagerStartupAuthority claimInitialManagerStartup(Board board) {
    InitialManagerStartupAuthority authority =
        new InitialManagerStartupAuthority(
            INITIAL_MANAGER_STARTUP_SEQUENCE.incrementAndGet(), board);
    synchronized (INITIAL_MANAGER_STARTUP_LOCK) {
      activeInitialManagerStartup = authority;
    }
    return authority;
  }

  private boolean activateInitialManagerStartup(InitialManagerStartupAuthority authority) {
    synchronized (INITIAL_MANAGER_STARTUP_LOCK) {
      if (authority == null
          || activeInitialManagerStartup != authority
          || authority.board != Lizzie.board) {
        return false;
      }
      authority.manager = this;
      Lizzie.setEngineManager(this);
      return true;
    }
  }

  private boolean isCurrentInitialManagerStartup(
      InitialManagerStartupAuthority authority, Board board) {
    synchronized (INITIAL_MANAGER_STARTUP_LOCK) {
      return authority != null
          && activeInitialManagerStartup == authority
          && authority.manager == this
          && Lizzie.engineManager == this
          && authority.board == board
          && Lizzie.board == board;
    }
  }

  private void submitInitialEngineStartup(InitialEngineStartupCandidate candidate) {
    EngineSwitchTransaction transaction =
        tryBeginEngineSwitchTransaction(true, candidate.index, null, -1, candidate.engine);
    if (transaction == null) {
      throw new IllegalStateException("Initial engine startup transaction was unavailable");
    }
    EngineSwitchUiSnapshot submitted;
    try {
      submitted = beginEngineSwitchUiSnapshot(candidate.index, true, -1, null, candidate.engine);
    } catch (RuntimeException | Error presentationFailure) {
      finishEngineSwitchTransaction(transaction);
      throw presentationFailure;
    }
    transaction.uiToken = submitted.token;
    launchInitialEngineStartup(
        candidate.index,
        candidate.engine,
        candidate.board,
        candidate.boardShapeChanges,
        transaction,
        submitted.token,
        initialManagerStartup);
  }

  private void launchInitialEngineStartup(
      int index,
      Leelaz engine,
      Board restoreBoard,
      boolean boardShapeChanges,
      EngineSwitchTransaction transaction,
      long uiToken,
      InitialManagerStartupAuthority startupAuthority) {
    Runnable startup =
        () -> {
          boolean finalFenceOwnsCleanup = false;
          InitialEngineStartupSynchronization synchronization = null;
          Leelaz.UpdateEngineStartAttempt startAttempt = null;
          try {
            if (!isCurrentInitialManagerStartup(startupAuthority, restoreBoard)) {
              throw new IllegalStateException("Initial engine manager was superseded");
            }
            if (restoreBoard != null) {
              synchronization =
                  InitialEngineStartupSynchronization.capture(
                      engine, restoreBoard, boardShapeChanges);
              synchronization.beginLifecycleCompletionClaim();
            }
            if (!isCurrentEngineSwitchTransaction(transaction)
                || !engineSwitchUiTracker.isSwitching(uiToken, true)
                || !isCurrentInitialManagerStartup(startupAuthority, restoreBoard)) {
              throw new IllegalStateException("Initial engine startup was superseded");
            }
            startAttempt = engine.beginUpdateEngineStartAttempt();
            transaction.targetEngineIncarnation = engine.captureEngineIncarnationFence();
            transaction.targetEngineIncarnationCaptured = true;
            synchronized (INITIAL_MANAGER_STARTUP_LOCK) {
              if (!isCurrentInitialManagerStartup(startupAuthority, restoreBoard)) {
                throw new IllegalStateException("Initial engine manager was superseded");
              }
              // Board.initialize() still consults the primary engine for engine-owned defaults.
              // Publish only this authority's quarantined startup target before the local clear;
              // the committed index/ACTIVE state remain fenced below.
              if (!installInitialPrimaryEngine(engine, transaction)) {
                throw new IllegalStateException("Initial engine startup lost PRIMARY authority");
              }
              transaction.targetInstalled = true;
              if (boardShapeChanges) {
                if (restoreBoard == null) {
                  Board.boardWidth = engine.oriWidth;
                  Board.boardHeight = engine.oriHeight;
                  Zobrist.init();
                } else {
                  restoreBoard.resizeAndClearForInitialEngineStartup(
                      engine.oriWidth, engine.oriHeight);
                }
              }
            }
            try {
              startAttempt.startEngine(index);
            } finally {
              transaction.targetEngineIncarnation = startAttempt.publishedIncarnation();
              transaction.targetEngineIncarnationCaptured = true;
            }
            startAttempt.complete();
            startAttempt = null;
            if (!waitForEngineSynchronizationReadiness(engine)) {
              failInitialEngineStartup(
                  engine,
                  synchronization,
                  transaction,
                  uiToken,
                  engineFailedText(),
                  startupAuthority,
                  restoreBoard);
              return;
            }
            if (!isCurrentInitialManagerStartup(startupAuthority, restoreBoard)) {
              throw new IllegalStateException("Initial engine manager was superseded");
            }
            if (restoreBoard == null || synchronization == null) {
              if (!isCurrentEngineSwitchTransaction(transaction)
                  || !engineSwitchUiTracker.isSwitching(uiToken, true)
                  || !isCurrentInitialManagerStartup(startupAuthority, restoreBoard)) {
                failInitialEngineStartup(
                    engine,
                    synchronization,
                    transaction,
                    uiToken,
                    "Initial engine startup was superseded",
                    startupAuthority,
                    restoreBoard);
                return;
              }
              synchronized (INITIAL_MANAGER_STARTUP_LOCK) {
                if (!isCurrentInitialManagerStartup(startupAuthority, restoreBoard)
                    || !commitPrimaryEngineSelection(engine, index)) {
                  throw new IllegalStateException("Initial engine changed before startup commit");
                }
                completeEngineSwitchUi(uiToken, index, true, engine);
              }
              finishEngineSwitchTransaction(transaction);
              return;
            }
            synchronization.runUntilStable();
            final InitialEngineStartupSynchronization completedSynchronization = synchronization;
            completedSynchronization.confirmFinalBoardSynchronization(
                () -> {
                  Runnable staleRuntimeStop = null;
                  try {
                    if (!isCurrentEngineSwitchTransaction(transaction)
                        || !engineSwitchUiTracker.isSwitching(uiToken, true)
                        || Lizzie.leelaz != engine
                        || !isCurrentInitialManagerStartup(startupAuthority, restoreBoard)) {
                      throw new IllegalStateException(
                          "Initial engine changed before final startup commit");
                    }
                    synchronized (INITIAL_MANAGER_STARTUP_LOCK) {
                      if (!isCurrentInitialManagerStartup(startupAuthority, restoreBoard)
                          || !commitPrimaryEngineSelection(engine, index)) {
                        throw new IllegalStateException(
                            "Initial engine changed before final startup commit");
                      }
                      completedSynchronization.initializeAfterRestore();
                      completeEngineSwitchUi(uiToken, index, true, engine);
                    }
                  } catch (RuntimeException | Error failure) {
                    if (isCurrentInitialManagerStartup(startupAuthority, restoreBoard)) {
                      failEngineSwitchUi(uiToken, true, engineSwitchFailureDetail(failure));
                      showEngineSynchronizationFailure(engine);
                    } else {
                      staleRuntimeStop =
                          quarantineStaleInitialEngineIncarnation(
                              engine, transaction.targetEngineIncarnation, uiToken);
                    }
                  } finally {
                    try {
                      completedSynchronization.close();
                    } finally {
                      try {
                        finishEngineSwitchTransaction(transaction);
                      } finally {
                        dispatchFailedEngineStop(staleRuntimeStop, uiToken);
                      }
                    }
                  }
                },
                detail ->
                    failInitialEngineStartup(
                        engine,
                        completedSynchronization,
                        transaction,
                        uiToken,
                        detail,
                        startupAuthority,
                        restoreBoard));
            finalFenceOwnsCleanup = true;
          } catch (IOException | RuntimeException | Error failure) {
            failure.printStackTrace();
            if (startAttempt == null) {
              failInitialEngineStartup(
                  engine,
                  synchronization,
                  transaction,
                  uiToken,
                  engineSwitchFailureDetail(failure),
                  startupAuthority,
                  restoreBoard);
            } else {
              settleInitialEngineStartFailure(
                  engine,
                  startAttempt,
                  synchronization,
                  transaction,
                  uiToken,
                  startupAuthority,
                  restoreBoard,
                  failure);
            }
          } finally {
            if (!finalFenceOwnsCleanup && isCurrentEngineSwitchTransaction(transaction)) {
              if (synchronization != null) {
                synchronization.close();
              }
              finishEngineSwitchTransaction(transaction);
            }
          }
        };
    InitialEngineStartupScheduler scheduler = initialEngineStartupScheduler;
    InitialEngineStartupDispatch dispatch =
        new InitialEngineStartupDispatch(
            startup,
            schedulingFailure ->
                settleInitialEngineStartupSchedulingFailure(
                    engine,
                    transaction,
                    uiToken,
                    startupAuthority,
                    restoreBoard,
                    schedulingFailure));
    Thread worker;
    try {
      worker =
          scheduler.create(
              dispatch,
              "lizzie-initial-engine-startup-" + uiToken);
      if (worker == null) {
        throw new IllegalStateException("Initial engine startup scheduler returned no worker");
      }
      scheduler.configure(worker);
    } catch (RuntimeException | Error schedulingFailure) {
      dispatch.schedulingFailed(schedulingFailure);
      return;
    }
    Runnable startWorker =
        () -> {
          try {
            scheduler.start(worker);
            dispatch.schedulingSucceeded();
          } catch (RuntimeException | Error schedulingFailure) {
            dispatch.schedulingFailed(schedulingFailure);
          }
        };
    if (!SwingUtilities.isEventDispatchThread()) {
      startWorker.run();
      return;
    }
    try {
      scheduler.dispatch(
          () -> {
            try {
              scheduler.dispatch(startWorker);
            } catch (RuntimeException | Error schedulingFailure) {
              dispatch.schedulingFailed(schedulingFailure);
            }
          });
    } catch (RuntimeException | Error schedulingFailure) {
      dispatch.schedulingFailed(schedulingFailure);
    }
  }

  private static final class InitialEngineStartupDispatch implements Runnable {
    private static final Object SCHEDULING_SUCCEEDED = new Object();
    private final Runnable startup;
    private final java.util.function.Consumer<Throwable> failureSettlement;
    private final AtomicReference<Object> schedulingOutcome = new AtomicReference<>();
    private final CountDownLatch schedulingSettled = new CountDownLatch(1);

    private InitialEngineStartupDispatch(
        Runnable startup, java.util.function.Consumer<Throwable> failureSettlement) {
      this.startup = startup;
      this.failureSettlement = failureSettlement;
    }

    @Override
    public void run() {
      Object outcome = awaitSchedulingOutcome();
      if (outcome == SCHEDULING_SUCCEEDED) {
        startup.run();
      }
    }

    private Object awaitSchedulingOutcome() {
      boolean interrupted = false;
      while (true) {
        try {
          schedulingSettled.await();
          break;
        } catch (InterruptedException waitInterrupted) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      return schedulingOutcome.get();
    }

    private void schedulingSucceeded() {
      schedulingOutcome.compareAndSet(null, SCHEDULING_SUCCEEDED);
      schedulingSettled.countDown();
    }

    private void schedulingFailed(Throwable failure) {
      if (!schedulingOutcome.compareAndSet(null, failure)) {
        failure.printStackTrace();
        return;
      }
      schedulingSettled.countDown();
      failureSettlement.accept(failure);
    }
  }

  private void settleInitialEngineStartupSchedulingFailure(
      Leelaz engine,
      EngineSwitchTransaction transaction,
      long uiToken,
      InitialManagerStartupAuthority startupAuthority,
      Board restoreBoard,
      Throwable schedulingFailure) {
    try {
      failInitialEngineStartup(
          engine,
          null,
          transaction,
          uiToken,
          engineSwitchFailureDetail(schedulingFailure),
          startupAuthority,
          restoreBoard);
    } catch (RuntimeException | Error cleanupFailure) {
      suppressEngineStartCleanupFailure(schedulingFailure, cleanupFailure);
    }
    schedulingFailure.printStackTrace();
  }

  private void settleInitialEngineStartFailure(
      Leelaz engine,
      Leelaz.UpdateEngineStartAttempt startAttempt,
      InitialEngineStartupSynchronization synchronization,
      EngineSwitchTransaction transaction,
      long uiToken,
      InitialManagerStartupAuthority startupAuthority,
      Board restoreBoard,
      Throwable primaryFailure) {
    UpdateEngineStartFailureCleanups failureCleanups =
        claimUpdateEngineStartFailureCleanups(startAttempt, null, primaryFailure);
    Runnable failurePresentation = null;
    if (failureCleanups.claimedTarget()) {
      transaction.targetStartFailureCleanupClaimed = true;
      InitialEngineStartFailureSettlementHook settlementHook =
          initialEngineStartFailureSettlementHook;
      if (settlementHook != null) {
        settlementHook.afterTargetCleanupClaimed(engine);
      }
      failurePresentation =
          reportEngineSynchronizationFailureIfCurrent(
              engine, startAttempt, null, null, primaryFailure);
    }
    try {
      failInitialEngineStartup(
          engine,
          synchronization,
          transaction,
          uiToken,
          engineSwitchFailureDetail(primaryFailure),
          startupAuthority,
          restoreBoard);
    } catch (RuntimeException | Error cleanupFailure) {
      suppressEngineStartCleanupFailure(primaryFailure, cleanupFailure);
    } finally {
      if (failurePresentation != null) {
        try {
          failurePresentation.run();
        } catch (RuntimeException | Error presentationFailure) {
          suppressEngineStartCleanupFailure(primaryFailure, presentationFailure);
        }
      }
      dispatchUpdateEngineStartFailureCleanupAfterRelease(failureCleanups, null);
    }
  }

  private void failInitialEngineStartup(
      Leelaz engine,
      InitialEngineStartupSynchronization synchronization,
      EngineSwitchTransaction transaction,
      long uiToken,
      String detail,
      InitialManagerStartupAuthority startupAuthority,
      Board restoreBoard) {
    boolean currentStartup = isCurrentInitialManagerStartup(startupAuthority, restoreBoard);
    boolean failureSuperseded =
        transaction != null && transaction.synchronizationFailureSuperseded;
    AtomicReference<Runnable> staleRuntimeStop = new AtomicReference<>();
    Throwable cleanupFailure = null;
    if (currentStartup && !failureSuperseded) {
      cleanupFailure =
          runLifecycleCleanupStep(
              cleanupFailure, () -> failEngineSwitchUi(uiToken, true, detail));
    } else if (!currentStartup
        && (transaction == null || !transaction.targetStartFailureCleanupClaimed)) {
      cleanupFailure =
          runLifecycleCleanupStep(
              cleanupFailure,
              () ->
                  staleRuntimeStop.set(
                      quarantineStaleInitialEngineIncarnation(
                          engine,
                          transaction == null ? null : transaction.targetEngineIncarnation,
                          uiToken)));
    }
    if (synchronization != null) {
      cleanupFailure =
          runLifecycleCleanupStep(cleanupFailure, synchronization::close);
    }
    cleanupFailure =
        runLifecycleCleanupStep(
            cleanupFailure, () -> finishEngineSwitchTransaction(transaction));
    cleanupFailure =
        runLifecycleCleanupStep(
            cleanupFailure,
            () -> dispatchFailedEngineStop(staleRuntimeStop.get(), uiToken));
    if (currentStartup
        && !failureSuperseded
        && (transaction == null || !transaction.targetStartFailureCleanupClaimed)) {
      cleanupFailure =
          runLifecycleCleanupStep(
              cleanupFailure, () -> showEngineSynchronizationFailure(engine));
    }
    rethrowLifecycleCleanupFailure(cleanupFailure);
  }

  EngineManager(List<Leelaz> engines) {
    engineList = engines;
  }

  public void autoCheckEngineAlive(boolean enable) {
    if (enable) {
      if (timer == null) {
        timer =
            new Timer(
                5000,
                new ActionListener() {
                  public void actionPerformed(ActionEvent evt) {
                    checkEngineAlive();
                    try {
                    } catch (Exception e) {
                    }
                  }
                });
        timer.start();
      } else timer.start();
    } else {
      if (timer != null) timer.stop();
    }
  }

  public boolean startEngineGame(
      int engineBlack,
      int engineWhite,
      int timeBlack,
      int timeWhite,
      int playoutsBlack,
      int playoutsWhite,
      int firstPlayoutsBlack,
      int firstPlayoutsWhite,
      boolean isBatchGame,
      int batchGameNumber,
      String batchGameName,
      boolean isContinueGame,
      boolean isGenmove,
      boolean isExchange,
      boolean checkGameMaxMove,
      int maxGameMoves) {
    DesktopTimeControl.SideMode blackTimeMode =
        DesktopTimeControl.loadEngineGameSideMode(Lizzie.config, true);
    DesktopTimeControl.SideMode whiteTimeMode =
        DesktopTimeControl.loadEngineGameSideMode(Lizzie.config, false);
    if (isGenmove
        && DesktopTimeControl.rejectsEngineGame(
            engineList, engineBlack, engineWhite, blackTimeMode, whiteTimeMode)) {
      Lizzie.frame.showUnsupportedWebSocketAdvancedClock();
      return false;
    }
    if (engineBlack == engineWhite) {
      Utils.showMsg(resourceBundle.getString("EngineManager.engineGameSameEngine"));
      return false;
    }
    if (!isGenmove) {
      if (timeBlack <= 0 && playoutsBlack <= 0 && firstPlayoutsBlack <= 0) {
        Utils.showMsg(resourceBundle.getString("EngineManager.engineGameBlackSettingWrong"));
        return false;
      }
      if (timeWhite <= 0 && playoutsWhite <= 0 && firstPlayoutsWhite <= 0) {
        Utils.showMsg(resourceBundle.getString("EngineManager.engineGameWhiteSettingWrong"));
        return false;
      }
    }
    // Construct the next game completely off to the side. Publishing a partially initialized
    // object here used to invalidate an already-running game's identity even when validation or
    // later setup failed.
    EngineGameInfo engineGameInfo = new EngineGameInfo();
    engineGameInfo.isGenmove = isGenmove;
    engineGameInfo.blackEngineIndex = engineBlack;
    engineGameInfo.whiteEngineIndex = engineWhite;
    engineGameInfo.firstEngineIndex = engineBlack;
    engineGameInfo.secondEngineIndex = engineWhite;
    if (isGenmove) {
      timeBlack =
          DesktopTimeControl.fixedSecondsForToolbar(blackTimeMode, timeBlack > 0, timeBlack);
      timeWhite =
          DesktopTimeControl.fixedSecondsForToolbar(whiteTimeMode, timeWhite > 0, timeWhite);
    }
    engineGameInfo.timeBlack = timeBlack;
    engineGameInfo.timeWhite = timeWhite;
    engineGameInfo.timeFirstEngine = timeBlack;
    engineGameInfo.timeSecondEngine = timeWhite;
    engineGameInfo.blackTimeMode = blackTimeMode;
    engineGameInfo.whiteTimeMode = whiteTimeMode;
    engineGameInfo.advanceBlackTimeCmd = Lizzie.config.advanceBlackTimeTxt;
    engineGameInfo.advanceWhiteTimeCmd = Lizzie.config.advanceWhiteTimeTxt;
    engineGameInfo.playoutsBlack = playoutsBlack;
    engineGameInfo.playoutsWhite = playoutsWhite;
    engineGameInfo.playoutsFirstEngine = playoutsBlack;
    engineGameInfo.firstPlayoutsFirstEngine = firstPlayoutsBlack;
    engineGameInfo.playoutsSecondEngine = playoutsWhite;
    engineGameInfo.firstPlayoutsSecondEngine = firstPlayoutsWhite;
    engineGameInfo.firstPlayoutsBlack = firstPlayoutsBlack;
    engineGameInfo.firstPlayoutsWhite = firstPlayoutsWhite;
    engineGameInfo.isBatchGame = isBatchGame;
    engineGameInfo.batchNumber = batchGameNumber;
    engineGameInfo.isExchange = isExchange;
    engineGameInfo.batchNumberCurrent = 1;
    engineGameInfo.isContinueGame = isContinueGame;
    engineGameInfo.handicap = Lizzie.config.newEngineGameHandicap;
    engineGameInfo.komi = Lizzie.config.newEngineGameKomi;
    engineGameInfo.blackMinMove = Lizzie.config.firstEngineMinMove;
    engineGameInfo.blackResignMoveCounts = Lizzie.config.firstEngineResignMoveCounts;
    engineGameInfo.blackResignWinrate = Lizzie.config.firstEngineResignWinrate;

    engineGameInfo.whiteMinMove = Lizzie.config.secondEngineMinMove;
    engineGameInfo.whiteResignMoveCounts = Lizzie.config.secondEngineResignMoveCounts;
    engineGameInfo.whiteResignWinrate = Lizzie.config.secondEngineResignWinrate;
    if (checkGameMaxMove) engineGameInfo.setMaxGameMoves(maxGameMoves);
    else engineGameInfo.setMaxGameMoves(-1);
    engineGameInfo.SF = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
    if (Lizzie.frame.enginePkSgfWinLoss != null)
      engineGameInfo.engineGameSgfWinLoss = Lizzie.frame.enginePkSgfWinLoss;
    if (isGenmove) {
      engineGameInfo.settingFirst =
          resourceBundle.getString("EngineGameInfo.settingFirst"); // "第一引擎设置:";
      engineGameInfo.settingSecond =
          resourceBundle.getString("EngineGameInfo.settingSecond"); // "第二引擎设置:";
      if (engineGameInfo.blackTimeMode == DesktopTimeControl.SideMode.RAW_ADVANCED) {
        engineGameInfo.settingFirst +=
            resourceBundle.getString("EngineGameInfo.time") + engineGameInfo.advanceBlackTimeCmd;
      } else if (engineGameInfo.timeFirstEngine > 0) {
        engineGameInfo.settingFirst +=
            resourceBundle.getString("EngineGameInfo.time")
                + engineGameInfo.timeFirstEngine
                + resourceBundle.getString("SGFParse.seconds");
      }
      engineGameInfo.settingFirst +=
          "\r\n"
              + resourceBundle.getString("EngineGameInfo.command")
              + engineList.get(engineGameInfo.firstEngineIndex).getEngineCommand();
      if (engineGameInfo.whiteTimeMode == DesktopTimeControl.SideMode.RAW_ADVANCED) {
        engineGameInfo.settingSecond +=
            resourceBundle.getString("EngineGameInfo.time") + engineGameInfo.advanceWhiteTimeCmd;
      } else if (engineGameInfo.timeSecondEngine > 0) {
        engineGameInfo.settingSecond +=
            resourceBundle.getString("EngineGameInfo.time")
                + engineGameInfo.timeSecondEngine
                + resourceBundle.getString("SGFParse.seconds");
      }
      engineGameInfo.settingSecond +=
          "\r\n"
              + resourceBundle.getString("EngineGameInfo.command")
              + engineList.get(engineGameInfo.secondEngineIndex).getEngineCommand();

    } else {
      engineGameInfo.settingFirst =
          resourceBundle.getString("EngineGameInfo.settingFirst"); // "第一引擎设置:";
      if (engineGameInfo.timeFirstEngine > 0)
        engineGameInfo.settingFirst +=
            resourceBundle.getString("EngineGameInfo.time")
                + engineGameInfo.timeFirstEngine
                + resourceBundle.getString("SGFParse.seconds");
      if (engineGameInfo.playoutsFirstEngine > 0)
        engineGameInfo.settingFirst +=
            resourceBundle.getString("EngineGameInfo.totalVisits")
                + engineGameInfo.playoutsFirstEngine;
      if (engineGameInfo.firstPlayoutsFirstEngine > 0)
        engineGameInfo.settingFirst +=
            resourceBundle.getString("EngineGameInfo.firstVisits")
                + engineGameInfo.firstPlayoutsFirstEngine;

      engineGameInfo.settingFirst +=
          "\r\n"
              + resourceBundle.getString("EngineGameInfo.resignThreshold")
              + Lizzie.config.firstEngineMinMove
              + resourceBundle.getString("EngineGameInfo.resignThreshold2")
              + Lizzie.config.firstEngineResignMoveCounts
              + resourceBundle.getString("EngineGameInfo.resignThreshold3")
              + Lizzie.config.firstEngineResignWinrate;

      engineGameInfo.settingFirst +=
          "\r\n"
              + resourceBundle.getString("EngineGameInfo.command")
              + engineList.get(engineGameInfo.firstEngineIndex).getEngineCommand();

      engineGameInfo.settingSecond =
          resourceBundle.getString("EngineGameInfo.settingSecond"); // "第二引擎设置:";
      if (engineGameInfo.timeSecondEngine > 0)
        engineGameInfo.settingSecond +=
            resourceBundle.getString("EngineGameInfo.time")
                + engineGameInfo.timeSecondEngine
                + resourceBundle.getString("SGFParse.seconds");
      if (engineGameInfo.playoutsSecondEngine > 0)
        engineGameInfo.settingSecond +=
            resourceBundle.getString("EngineGameInfo.totalVisits")
                + engineGameInfo.playoutsSecondEngine;
      if (engineGameInfo.firstPlayoutsSecondEngine > 0)
        engineGameInfo.settingSecond +=
            resourceBundle.getString("EngineGameInfo.firstVisits")
                + engineGameInfo.firstPlayoutsSecondEngine;
      engineGameInfo.settingSecond +=
          "\r\n"
              + resourceBundle.getString("EngineGameInfo.resignThreshold")
              + Lizzie.config.secondEngineMinMove
              + resourceBundle.getString("EngineGameInfo.resignThreshold2")
              + Lizzie.config.secondEngineResignMoveCounts
              + resourceBundle.getString("EngineGameInfo.resignThreshold3")
              + Lizzie.config.secondEngineResignWinrate;

      engineGameInfo.settingSecond +=
          "\r\n"
              + resourceBundle.getString("EngineGameInfo.command")
              + engineList.get(engineGameInfo.secondEngineIndex).getEngineCommand();
    }

    if (engineGameInfo.isContinueGame) {
      engineGameInfo.continueGameList = Lizzie.board.getMoveList();
    }
    if (engineGameInfo.isBatchGame) {
      if (batchGameName.equals("")) {
        // batchPkName = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        String SF = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        SF =
            getEngineName(engineGameInfo.firstEngineIndex)
                + "_VS_"
                + getEngineName(engineGameInfo.secondEngineIndex)
                + "_"
                + SF;
        SF = SF.replaceAll("[/\\\\:*?|]", ".");
        SF = SF.replaceAll("[\"<>]", "'");
        engineGameInfo.batchGameName = SF;
      } else {
        engineGameInfo.batchGameName = batchGameName;
      }
    }

    EngineGameTransaction gameTransaction = startNewEngineGame(true, engineGameInfo, null, true);
    if (gameTransaction == null || !isCurrentEngineGameTransaction(gameTransaction)) {
      return false;
    }
    publishEngineGamePreparingUi(gameTransaction);
    return true;
  }

  public ArrayList<Movelist> getStartListForEnginePk() {
    return getStartListForEnginePk(engineGameInfo);
  }

  private ArrayList<Movelist> getStartListForEnginePk(EngineGameInfo gameInfo) {
    if (gameInfo.isContinueGame) {
      return gameInfo.continueGameList;
    }
    if (Lizzie.config.chkEngineSgfStart) {
      int length = Lizzie.frame.enginePKSgfString.size();
      if (Lizzie.config.engineSgfStartRandom) {
        Random random = new Random();
        LizzieFrame.toolbar.currentEnginePkSgfNum = random.nextInt(length);
      } else {
        LizzieFrame.toolbar.currentEnginePkSgfNum = Lizzie.frame.enginePKSgfNum % length;
        Lizzie.frame.enginePKSgfNum++;
      }
      return Lizzie.frame.enginePKSgfString.get(LizzieFrame.toolbar.currentEnginePkSgfNum);
    }
    return null;
  }

  private ArrayList<Movelist> prepareEngineGameBoard(
      boolean firstTime, boolean analysisMode, EngineGameInfo engineGame) {
    Lizzie.board.clear(true);
    ArrayList<Movelist> startList = getStartListForEnginePk(engineGame);
    if (startList != null) {
      if (analysisMode) {
        Lizzie.board.setMoveList(startList, false, true);
      } else {
        Lizzie.board.setlist(startList);
      }
    } else if (firstTime) {
      int width = engineList.get(engineGame.blackEngineIndex).width;
      int height = engineList.get(engineGame.blackEngineIndex).height;
      if (width != Board.boardWidth || height != Board.boardHeight) {
        Lizzie.board.reopen(width, height);
      }
    }

    GameInfo boardGameInfo = Lizzie.board.getHistory().getGameInfo();
    boardGameInfo.setKomiNoMenu(engineGame.komi);
    boardGameInfo.setHandicap(0);
    if (startList == null && engineGame.handicap >= 2) {
      Lizzie.board.setupFixedHandicap(engineGame.handicap);
    }
    return startList;
  }

  private String formateSaveString(String filename) {
    filename = filename.replaceAll("[/\\\\:*?|]", ".");
    filename = filename.replaceAll("[\"<>]", "'");
    return filename;
  }

  private void saveEngineGameFile(int resignIndex) {
    File file = new File("");
    String courseFile = "";
    try {
      courseFile = file.getCanonicalPath();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    String sf = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());

    String df = "";
    if (engineGameInfo.isBatchGame) {
      df =
          engineGameInfo.batchNumberCurrent
              + (Lizzie.config.chkPkStartNum ? (Lizzie.config.pkStartNum - 1) : 0)
              + "_"
              + (Lizzie.config.chkEngineSgfStart
                  ? resourceBundle.getString("EngineGameInfo.openingSGFindex")
                      + LizzieFrame.toolbar.currentEnginePkSgfNum
                      + "_"
                  : "");
    }
    df =
        df
            + resourceBundle.getString("Leelaz.black")
            + "("
            + Lizzie.engineManager.engineList.get(engineGameInfo.blackEngineIndex).currentEnginename
            + ")"
            + "_vs_"
            + resourceBundle.getString("Leelaz.white")
            + "("
            + engineList.get(engineGameInfo.whiteEngineIndex).currentEnginename
            + ")";
    // 添加结果
    if (engineList.get(resignIndex).doublePass) {
      df += resourceBundle.getString("EngineManager.doublePassFileName"); // "_双pass对局";
    } else if (Lizzie.board.getHistory().getMoveNumber() > engineGameInfo.getMaxGameMoves()) {
      df += resourceBundle.getString("EngineManager.outOfMoveFileName"); // "_超手数对局";
    } else {
      if (resignIndex == engineGameInfo.whiteEngineIndex) {
        GameInfo gameInfo = Lizzie.board.getHistory().getGameInfo();
        gameInfo.setResult(resourceBundle.getString("Leelaz.blackWin"));
        df =
            df
                + "_"
                + resourceBundle.getString("Leelaz.black")
                + "("
                + engineList.get(engineGameInfo.blackEngineIndex).currentEnginename
                + ")"
                + resourceBundle.getString("Leelaz.win");
      } else {
        GameInfo gameInfo = Lizzie.board.getHistory().getGameInfo();
        gameInfo.setResult(resourceBundle.getString("Leelaz.whiteWin"));
        df =
            df
                + "_"
                + resourceBundle.getString("Leelaz.white")
                + "("
                + engineList.get(engineGameInfo.whiteEngineIndex).currentEnginename
                + ")"
                + resourceBundle.getString("Leelaz.win");
      }
    }
    df = df + "_" + sf;
    // 增加如果已命名,则保存在命名的文件夹下
    df = formateSaveString(df);

    File autoSaveFile;
    File autoSaveFile2 = null;
    if (engineGameInfo.isBatchGame) {
      autoSaveFile =
          new File(
              courseFile
                  + File.separator
                  + "EngineGames"
                  + File.separator
                  + engineGameInfo.batchGameName
                  + File.separator
                  + df
                  + ".sgf");
      autoSaveFile2 =
          new File(
              courseFile
                  + File.separator
                  + "EngineGames"
                  + File.separator
                  + engineGameInfo.SF
                  + File.separator
                  + df
                  + ".sgf");
    } else {
      autoSaveFile =
          new File(courseFile + File.separator + "EngineGames" + File.separator + df + ".sgf");
      autoSaveFile2 =
          new File(courseFile + File.separator + "EngineGames" + File.separator + df + ".sgf");
    }

    File fileParent = autoSaveFile.getParentFile();
    if (!fileParent.exists()) {
      fileParent.mkdirs();
    }
    try {
      SGFParser.save(Lizzie.board, autoSaveFile.getPath());
      if (LizzieFrame.toolbar.enginePkSaveWinrate) {
        String autoSavePng;
        if (engineGameInfo.isBatchGame) {
          autoSavePng =
              courseFile
                  + File.separator
                  + "EngineGames"
                  + File.separator
                  + engineGameInfo.batchGameName
                  + File.separator
                  + df
                  + ".png";

        } else {
          autoSavePng = courseFile + File.separator + "EngineGames" + File.separator + df + ".png";
        }
        Lizzie.frame.saveImage(
            Lizzie.frame.statx,
            Lizzie.frame.staty,
            (int) (Lizzie.frame.grw * 1.03),
            Lizzie.frame.grh + Lizzie.frame.stath,
            autoSavePng);
      }
    } catch (IOException e) {
      // TODO Auto-generated catch block
      if (engineGameInfo.isBatchGame) {
        try {
          File fileParent2 = autoSaveFile2.getParentFile();
          if (!fileParent2.exists()) {
            fileParent2.mkdirs();
          }
          SGFParser.save(Lizzie.board, autoSaveFile2.getPath());

          if (LizzieFrame.toolbar.enginePkSaveWinrate) {

            String autoSavePng2 = null;
            if (engineGameInfo.isBatchGame) {
              autoSavePng2 =
                  courseFile
                      + File.separator
                      + "EngineGames"
                      + File.separator
                      + engineGameInfo.SF
                      + File.separator
                      + df
                      + ".png";
            } else {
              autoSavePng2 =
                  courseFile + File.separator + "EngineGames" + File.separator + df + ".png";
            }
            Lizzie.frame.saveImage(
                Lizzie.frame.statx,
                Lizzie.frame.staty,
                (int) (Lizzie.frame.grw * 1.03),
                Lizzie.frame.grh + Lizzie.frame.stath,
                autoSavePng2);
          }
        } catch (IOException e1) {
          // TODO Auto-generated catch block
          e1.printStackTrace();
        }
      }
      e.printStackTrace();
    }
  }

  private void writeToFile(
      File file,
      String settingAll,
      String settingB,
      String settingW,
      String resultB,
      String resultW,
      String resultOther)
      throws IOException {

    try (FileOutputStream writerStream = new FileOutputStream(file);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(writerStream, "UTF-8"))) {
      double games = (double) engineGameInfo.batchNumberCurrent;
      double wr =
          (double) engineGameInfo.getFirstEngineWins()
              / (double)
                  (engineGameInfo.getFirstEngineWins() + engineGameInfo.getSecondEngineWins());

      double elo = Math.log10(1.0 / wr - 1.0) * 400;
      double zxwr = (wr + 1.0 / (2.0 * games)) / (1.0 + 1.0 / games);
      double zxwrc =
          1.0
              * Math.sqrt(wr * (1.0 - wr) / games + 1.0 / ((2.0 * games) * (2.0 * games)))
              / (1.0 + 1.0 / games);
      double zxwr2 = (wr + 4.0 / (2.0 * games)) / (1.0 + 4.0 / games);
      double zxwrc2 =
          2.0
              * Math.sqrt(wr * (1.0 - wr) / games + 4.0 / ((2.0 * games) * (2.0 * games)))
              / (1.0 + 4.0 / games);
      double zxwr3 = (wr + 9.0 / (2.0 * games)) / (1.0 + 9.0 / games);
      double zxwrc3 =
          3.0
              * Math.sqrt(wr * (1.0 - wr) / games + 9.0 / ((2.0 * games) * (2.0 * games)))
              / (1.0 + 9.0 / games);
      double elo2 = Math.log10(1.0 / ((zxwr2 > 0.5 ? zxwr2 + zxwrc2 : zxwr2 - zxwrc2)) - 1.0) * 400;

      writer.write(
          settingAll
              + resourceBundle.getString("EngineGameInfo.backgroundPonder")
              + (Lizzie.config.enginePkPonder
                  ? resourceBundle.getString("EngineGameInfo.yes")
                  : resourceBundle.getString("EngineGameInfo.no")));
      writer.write("\r\n");
      writer.write("\r\n");
      writer.write(settingB);
      writer.write("\r\n");
      writer.write("\r\n");
      writer.write(settingW);
      writer.write("\r\n");
      writer.write("\r\n");
      writer.write(
          resourceBundle.getString("EngineGameInfo.totalGameResults")
              + engineGameInfo.batchNumberCurrent
              + resourceBundle.getString("EngineGameInfo.gameScore")
              + engineGameInfo.getFirstEngineWins()
              + ":"
              + engineGameInfo.getSecondEngineWins()
              + resourceBundle.getString("EngineGameInfo.gameWinrate")
              + String.format(Locale.ENGLISH, "%.2f", wr * 100)
              + "%");
      writer.write("\r\n");
      writer.write("\r\n");
      writer.write(resultB);
      writer.write("\r\n");
      writer.write(resultW);
      writer.write("\r\n");
      writer.write("\r\n");
      writer.write(resourceBundle.getString("EngineGameInfo.timeVisitsTips"));
      writer.write("\r\n");
      writer.write("\r\n");
      writer.write(resultOther);
      writer.write("\r\n");
      writer.write("\r\n");
      if (engineGameInfo.getFirstEngineWins() == 0 || engineGameInfo.getFirstEngineWins() == 0)
        writer.write(
            resourceBundle.getString("EngineGameInfo.secondEngineElo")
                + resourceBundle.getString("EngineGameInfo.elo100Wr"));
      else {
        writer.write(
            resourceBundle.getString("EngineGameInfo.secondEngineElo")
                + (elo > 0 ? "+" : "")
                + String.format(Locale.ENGLISH, "%.2f", elo)
                + " ± "
                + (zxwr2 + zxwrc2 < 1 && zxwr2 + zxwrc2 > 0
                    ? String.format(Locale.ENGLISH, "%.2f", Math.abs(elo2 - elo))
                    : ""));
        if (EngineManager.engineGameInfo.batchNumberCurrent < 50)
          writer.write("?(" + resourceBundle.getString("EngineGameInfo.notEnoughGames") + ")");
      }
      writer.write("\r\n");
      writer.write(
          resourceBundle.getString("EngineGameInfo.oneStdev")
              + String.format(Locale.ENGLISH, "%.2f", zxwr * 100)
              + "% ± "
              + String.format(Locale.ENGLISH, "%.2f", zxwrc * 100)
              + "%");
      writer.write("\r\n");
      writer.write(
          resourceBundle.getString("EngineGameInfo.twoStdev")
              + String.format(Locale.ENGLISH, "%.2f", zxwr2 * 100)
              + "% ± "
              + String.format(Locale.ENGLISH, "%.2f", zxwrc2 * 100)
              + "%");
      writer.write("\r\n");
      writer.write(
          resourceBundle.getString("EngineGameInfo.threeStdev")
              + String.format(Locale.ENGLISH, "%.2f", zxwr3 * 100)
              + "% ± "
              + String.format(Locale.ENGLISH, "%.2f", zxwrc3 * 100)
              + "%");
      writer.write("\r\n");

      Lizzie.frame.hasEnginePkTitile = true;
      Lizzie.frame.enginePkTitile =
          engineGameInfo.getFirstEngineWins()
              + ":"
              + engineGameInfo.getSecondEngineWins()
              + " "
              + engineList.get(engineGameInfo.firstEngineIndex).oriEnginename
              + " VS "
              + engineList.get(engineGameInfo.secondEngineIndex).oriEnginename
              + resourceBundle.getString("EngineGameInfo.titleWinRate")
              + String.format(Locale.ENGLISH, "%.1f", wr * 100)
              + "%"
              + " 2σ "
              + String.format(Locale.ENGLISH, "%.2f", zxwr2 * 100)
              + "%±"
              + String.format(Locale.ENGLISH, "%.2f", zxwrc2 * 100)
              + "%";

      if (Lizzie.config.chkEngineSgfStart) {
        writer.write("\r\n");
        writer.write(
            resourceBundle.getString("EngineGameInfo.sgfStartNumber")
                + Lizzie.frame.enginePKSgfString.size());
        writer.write("\r\n");
        for (SgfWinLossList wl : Lizzie.frame.enginePkSgfWinLoss) {
          writer.write(
              resourceBundle.getString("EngineGameInfo.sgfStartOpen")
                  + (Lizzie.config.isChinese ? "" : " ")
                  + wl.SgfNumber
                  + ":\n"
                  + resourceBundle.getString("EngineGameInfo.engine1")
                  + ": "
                  + resourceBundle.getString("EngineGameInfo.allWins")
                  + wl.engineOneWins
                  + resourceBundle.getString("EngineGameInfo.sgfStartBlackWin")
                  + wl.engineOneWinsAsBlack
                  + resourceBundle.getString("EngineGameInfo.sgfStartWhiteWin")
                  + wl.engineOneWinsAsWhite
                  + "   "
                  + resourceBundle.getString("EngineGameInfo.engine2")
                  + ": "
                  + resourceBundle.getString("EngineGameInfo.allWins")
                  + wl.engineTwoWins
                  + resourceBundle.getString("EngineGameInfo.sgfStartBlackWin")
                  + wl.engineTwoWinsAsBlack
                  + resourceBundle.getString("EngineGameInfo.sgfStartWhiteWin")
                  + wl.engineTwoWinsAsWhite);
          writer.write("\r\n");
        }
      }
      writer.flush();
    }
  }

  private void savePkTxt(
      String settingB,
      String settingW,
      String settingAll,
      String resultB,
      String resultW,
      String resultOther) {
    File file = new File("");
    String courseFile = "";
    try {
      courseFile = file.getCanonicalPath();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    // 增加如果已命名,则保存在命名的文件夹下
    File autoSaveFile;
    File autoSaveFile2 = null;
    autoSaveFile =
        new File(
            courseFile
                + File.separator
                + "EngineGames"
                + File.separator
                + engineGameInfo.batchGameName
                + File.separator
                + resourceBundle.getString("Leelaz.result")
                + engineGameInfo.SF
                + ".txt");
    autoSaveFile2 =
        new File(
            courseFile
                + File.separator
                + "EngineGames"
                + File.separator
                + engineGameInfo.SF
                + File.separator
                + resourceBundle.getString("Leelaz.result")
                + engineGameInfo.SF
                + ".txt");

    File fileParent = autoSaveFile.getParentFile();
    if (!fileParent.exists()) {
      fileParent.mkdirs();
    }
    try {
      writeToFile(autoSaveFile, settingAll, settingB, settingW, resultB, resultW, resultOther);
    } catch (IOException e) {
      // TODO Auto-generated catch block

      try {
        File fileParent2 = autoSaveFile2.getParentFile();
        if (!fileParent2.exists()) {
          fileParent2.mkdirs();
        }
        writeToFile(autoSaveFile2, settingAll, settingB, settingW, resultB, resultW, resultOther);

      } catch (IOException e1) {
        // TODO Auto-generated catch block
        e1.printStackTrace();
      }
      e.printStackTrace();
    }
  }

  public void stopEngineGame(int resgnEngineIndex, boolean mannul) {
    finishClaimedEngineGameStop(invalidateEngineGameTransaction(), resgnEngineIndex, mannul);
  }

  /**
   * Stops exactly the supplied game transaction. A delayed parser callback must never fall back to
   * the public "stop whichever game is current" operation after a successor has been admitted.
   */
  static boolean stopEngineGameIfCurrent(
      EngineGameTransaction transaction, int resignEngineIndex, boolean manual) {
    if (transaction == null || transaction.manager == null) {
      return false;
    }
    if (!claimTerminalEngineGameTransaction(
        transaction, EngineGamePhase.CANCELLED, null, true)) {
      return false;
    }
    EngineGameStopClaim stopClaim;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      stopClaim =
          new EngineGameStopClaim(
              transaction,
              transaction.gameInfo,
              transaction.gameWasActiveBeforeTerminal,
              transaction.gameWasStartingBeforeTerminal,
              transaction.inactiveEpoch);
    }
    transaction.manager.finishClaimedEngineGameStop(
        stopClaim, resignEngineIndex, manual);
    return true;
  }

  private void finishClaimedEngineGameStop(
      EngineGameStopClaim stopClaim, int resgnEngineIndex, boolean mannul) {
    EngineGameInfo engineGameInfo = stopClaim.gameInfo;
    EngineGameTransaction stoppedTransaction = stopClaim.transaction;
    boolean restoreOnFailure = false;
    boolean ownsSavingFlag = false;
    try {
      if (stoppedTransaction == null && !stopClaim.wasActive && !stopClaim.wasStarting) {
        return;
      }
      // Retirement has already cleared the legacy game booleans. Pass the terminal owner's exact
      // captured state to the parser instead of temporarily republishing a global saving flag.
      appendEngineGameStopComment(stopClaim.wasActive);
      if (!stopClaim.wasActive) {
        if (stoppedTransaction != null) {
          finishEngineGameTransactionRetirement(stoppedTransaction, true);
        } else {
          restoreUiAfterEngineGameStartAbort(inactiveUiToken(stopClaim), null);
      }
      return;
    }

    isSaveingEngineSGF = true;
    ownsSavingFlag = true;
    stopCountDown();
    LizzieFrame.menu.toggleDoubleMenuGameStatus();
    LizzieFrame.toolbar.isPkStop = false;
    Lizzie.frame.hasEnginePkTitile = true;
    Lizzie.frame.enginePkTitile = "";
    // 保存SGF文件
    if (mannul) {
      markEngineGameParticipantsStopped(stoppedTransaction, engineGameInfo);
      changeEngIcoForEndPk();
      LizzieFrame.toolbar.enableDisabelForEngineGame(true);
      Lizzie.frame.addInput(true);
      if (engineGameInfo.isBatchGame && engineGameInfo.batchNumberCurrent > 1) {
        File file = new File("");
        String courseFile = "";
        try {
          courseFile = file.getCanonicalPath();
        } catch (IOException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
        String passandMove = "";
        if (engineGameInfo.doublePassGame > 0)
          passandMove =
              resourceBundle.getString("EngineGameInfo.doublePassGame")
                  + engineGameInfo.doublePassGame;
        if (engineGameInfo.maxMoveGame > 0)
          passandMove +=
              (passandMove.equals("") ? "" : " ")
                  + resourceBundle.getString("EngineGameInfo.outOfMoveGame")
                  + engineGameInfo.maxMoveGame;
        Utils.showMsgNoModal(
            (resourceBundle.getString("EngineGameInfo.batchGameEndAndScore")
                + engineList.get(engineGameInfo.firstEngineIndex).oriEnginename
                + "   "
                + engineGameInfo.getFirstEngineWins()
                + ":"
                + engineGameInfo.getSecondEngineWins()
                + "   "
                + engineList.get(engineGameInfo.secondEngineIndex).oriEnginename
                + (passandMove.equals("") ? "" : " ")
                + passandMove
                + ","
                + resourceBundle.getString("EngineGameInfo.engineGameEndHintKifuPos")
                + courseFile
                + File.separator
                + "EngineGames"));
      }
      isSaveingEngineSGF = false;
      return;
    }
    SGFParser.appendGameTimeAndPlayouts();
    if (engineGameInfo.isBatchGame || LizzieFrame.toolbar.AutosavePk) {
      saveEngineGameFile(resgnEngineIndex);
    }
    if (engineGameInfo.isBatchGame) {
      if (engineList.get(resgnEngineIndex).doublePass) {
        engineGameInfo.doublePassGame++;
      } else if (engineList.get(resgnEngineIndex).outOfMoveNum) {
        engineGameInfo.maxMoveGame++;
      } else {
        if (resgnEngineIndex == engineGameInfo.firstEngineIndex) {
          if (resgnEngineIndex == engineGameInfo.blackEngineIndex) {
            engineGameInfo.secondEngineWinAsWhite++;
            for (SgfWinLossList wl : engineGameInfo.engineGameSgfWinLoss) {
              if (wl.SgfNumber == LizzieFrame.toolbar.currentEnginePkSgfNum) {
                wl.engineTwoWins++;
                wl.engineTwoWinsAsWhite++;
                break;
              }
            }
          } else {
            engineGameInfo.secondEngineWinAsBlack++;
            for (SgfWinLossList wl : engineGameInfo.engineGameSgfWinLoss) {
              if (wl.SgfNumber == LizzieFrame.toolbar.currentEnginePkSgfNum) {
                wl.engineTwoWins++;
                wl.engineTwoWinsAsBlack++;
                break;
              }
            }
          }
        } else {
          if (resgnEngineIndex == engineGameInfo.blackEngineIndex) {
            engineGameInfo.firstEngineWinAsWhite++;
            for (SgfWinLossList wl : engineGameInfo.engineGameSgfWinLoss) {
              if (wl.SgfNumber == LizzieFrame.toolbar.currentEnginePkSgfNum) {
                wl.engineOneWins++;
                wl.engineOneWinsAsWhite++;
                break;
              }
            }
          } else {
            engineGameInfo.firstEngineWinAsBlack++;
            for (SgfWinLossList wl : engineGameInfo.engineGameSgfWinLoss) {
              if (wl.SgfNumber == LizzieFrame.toolbar.currentEnginePkSgfNum) {
                wl.engineOneWins++;
                wl.engineOneWinsAsBlack++;
                break;
              }
            }
          }
        }
      }
      // 保存对局结果txt

      // resultOther, resultFirst, resultSecond;
      engineGameInfo.resultFirst =
          resourceBundle.getString("EngineGameInfo.engine1")
              + "("
              + engineList.get(engineGameInfo.firstEngineIndex).oriEnginename
              + "):\n"
              + resourceBundle.getString("EngineGameInfo.allWins")
              + ": "
              + engineGameInfo.getFirstEngineWins();
      engineGameInfo.resultFirst +=
          " "
              + resourceBundle.getString("EngineGameInfo.sgfStartBlackWin")
              + ": "
              + engineGameInfo.firstEngineWinAsBlack
              + " "
              + resourceBundle.getString("EngineGameInfo.sgfStartWhiteWin")
              + ": "
              + engineGameInfo.firstEngineWinAsWhite;
      engineGameInfo.resultFirst +=
          resourceBundle.getString("EngineGameInfo.totalTime")
              + engineGameInfo.firstEngineTotleTime / (float) 1000
              + resourceBundle.getString("SGFParse.seconds");
      engineGameInfo.resultFirst +=
          resourceBundle.getString("EngineGameInfo.result.totalVisits")
              + engineGameInfo.firstEngineTotlePlayouts;

      engineGameInfo.resultSecond =
          resourceBundle.getString("EngineGameInfo.engine2")
              + "("
              + engineList.get(engineGameInfo.secondEngineIndex).oriEnginename
              + "):\n"
              + resourceBundle.getString("EngineGameInfo.allWins")
              + ": "
              + engineGameInfo.getSecondEngineWins();
      engineGameInfo.resultSecond +=
          " "
              + resourceBundle.getString("EngineGameInfo.sgfStartBlackWin")
              + ": "
              + engineGameInfo.secondEngineWinAsBlack
              + " "
              + resourceBundle.getString("EngineGameInfo.sgfStartWhiteWin")
              + ": "
              + engineGameInfo.secondEngineWinAsWhite;
      engineGameInfo.resultSecond +=
          resourceBundle.getString("EngineGameInfo.totalTime")
              + engineGameInfo.secondEngineTotleTime / (float) 1000
              + resourceBundle.getString("SGFParse.seconds");
      engineGameInfo.resultSecond +=
          resourceBundle.getString("EngineGameInfo.result.totalVisits")
              + engineGameInfo.secondEngineTotlePlayouts;

      engineGameInfo.resultOther =
            resourceBundle.getString("EngineGameInfo.doublePassGame")
                + engineGameInfo.doublePassGame;
      engineGameInfo.resultOther +=
          " "
              + resourceBundle.getString("EngineGameInfo.outOfMoveGame")
              + engineGameInfo.maxMoveGame;
      if (engineGameInfo.isGenmove) {
        engineGameInfo.settingAll =
            resourceBundle.getString("EngineGameInfo.otherSettings")
                + resourceBundle.getString("EngineGameInfo.genmoveMode");
        engineGameInfo.settingAll +=
            resourceBundle.getString("EngineGameInfo.komi")
                + +Lizzie.board.getHistory().getGameInfo().getKomi();

        if (engineGameInfo.isBatchGame) {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.totalGames") + engineGameInfo.batchNumber;
        }
        if (engineGameInfo.isContinueGame) {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.continueGame")
                  + resourceBundle.getString("EngineGameInfo.yes");
        } else {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.continueGame")
                  + resourceBundle.getString("EngineGameInfo.no");
        }
        if (engineGameInfo.isExchange) {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.exchange")
                  + resourceBundle.getString("EngineGameInfo.yes"); // " 交换黑白: 是";
        } else {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.exchange")
                  + resourceBundle.getString("EngineGameInfo.no"); // " 交换黑白: 否";
        }

        engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.maxMoves")
                  + engineGameInfo.getMaxGameMoves();
      } else {
        engineGameInfo.settingAll =
            resourceBundle.getString("EngineGameInfo.otherSettings")
                + resourceBundle.getString("EngineGameInfo.analyzeMode");
        engineGameInfo.settingAll +=
            resourceBundle.getString("EngineGameInfo.komi")
                + Lizzie.board.getHistory().getGameInfo().getKomi();
        //      engineGameInfo.settingAll +=
        //          " 认输阈值:连续"
        //              + Lizzie.frame.toolbar.pkResignMoveCounts
        //              + "手,胜率低于"
        //              + Lizzie.frame.toolbar.pkResginWinrate
        //              + "%";
        if (engineGameInfo.isBatchGame) {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.totalGames") + engineGameInfo.batchNumber;
        }
        if (engineGameInfo.isContinueGame) {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.continueGame")
                  + resourceBundle.getString("EngineGameInfo.yes"); // " 续弈: 是";
        } else {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.continueGame")
                  + resourceBundle.getString("EngineGameInfo.no"); // " 续弈: 否";
        }
        if (engineGameInfo.isExchange) {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.exchange")
                  + resourceBundle.getString("EngineGameInfo.yes"); // " 交换黑白: 是";
        } else {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.exchange")
                  + resourceBundle.getString("EngineGameInfo.no"); // " 交换黑白: 否";
        }

        engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.maxMoves")
                  + engineGameInfo.getMaxGameMoves();

        if (LizzieFrame.toolbar.isRandomMove) {
          engineGameInfo.settingAll +=
              resourceBundle.getString("EngineGameInfo.randomPlay1") //    " 随机落子: 前"
                  + LizzieFrame.toolbar.randomMove
                  + resourceBundle.getString("EngineGameInfo.randomPlay2") // "手,胜率不低于首位"
                  + LizzieFrame.toolbar.randomDiffWinrate
                  + "%";
          if (Lizzie.config.checkRandomVisits)
            engineGameInfo.settingAll +=
                resourceBundle.getString("EngineGameInfo.randomPlay3") // ",计算量不低于最高值"
                    + String.format(Locale.ENGLISH, "%.1f", Lizzie.config.percentsRandomVisits)
                    + "%";
        }
      }
      savePkTxt(
          engineGameInfo.settingFirst,
          engineGameInfo.settingSecond,
          engineGameInfo.settingAll,
          engineGameInfo.resultFirst,
          engineGameInfo.resultSecond,
          engineGameInfo.resultOther);

      if (engineGameInfo.batchNumberCurrent < engineGameInfo.batchNumber) {
        engineGameInfo.batchNumberCurrent++;
        if (engineGameInfo.isExchange) engineGameInfo.exChangeBlackWhite();
        isSaveingEngineSGF = false;
        EngineGameInfo nextGame = engineGameInfo;
        EngineGameInactiveUiToken failedNextGameUi =
            new EngineGameInactiveUiToken(
                this, nextGame, stopClaim.invalidationEpoch, null);
        runAfterEngineGameTransactionRetirement(
            stoppedTransaction,
            () -> {
              if (!isEngineGameBatchContinuationCurrent(
                  this, nextGame, stopClaim.invalidationEpoch)) {
                return;
              }
              try {
                if (startNewEngineGame(
                        false, nextGame, stopClaim.invalidationEpoch, false)
                        == null
                    && isEngineGameBatchContinuationCurrent(
                        this, nextGame, stopClaim.invalidationEpoch)) {
                  restoreUiAfterEngineGameStartAbort(failedNextGameUi, null);
                }
              } catch (RuntimeException | Error nextGameFailure) {
                if (isEngineGameBatchContinuationCurrent(
                    this, nextGame, stopClaim.invalidationEpoch)) {
                  restoreUiAfterEngineGameStartAbort(failedNextGameUi, null);
                }
                throw nextGameFailure;
              }
            },
            () -> restoreUiAfterEngineGameStartAbort(failedNextGameUi, null));
        finishEngineGameTransactionRetirement(stoppedTransaction, false);
        return;
      }
    }
    LizzieFrame.toolbar.enableDisabelForEngineGame(true);
    Lizzie.board.clearBestMovesAfter(Lizzie.board.getHistory().getStart());
    File file = new File("");
    String courseFile = "";
    try {
      courseFile = file.getCanonicalPath();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    // showmsg 多局
    if (engineGameInfo.isBatchGame) {
      String passandMove = "";
      if (engineGameInfo.doublePassGame > 0)
        passandMove =
            passandMove
                + resourceBundle.getString("EngineGameInfo.doublePassGame")
                + engineGameInfo.doublePassGame;
      if (engineGameInfo.maxMoveGame > 0)
        passandMove =
            passandMove
                + (passandMove.equals("") ? "" : " ")
                + resourceBundle.getString("EngineGameInfo.outOfMoveGame")
                + engineGameInfo.maxMoveGame;
      Utils.showMsgNoModal(
          (resourceBundle.getString("EngineGameInfo.batchGameEndAndScore")
              + engineList.get(engineGameInfo.firstEngineIndex).oriEnginename
              + "   "
              + engineGameInfo.getFirstEngineWins()
              + ":"
              + engineGameInfo.getSecondEngineWins()
              + "   "
              + engineList.get(engineGameInfo.secondEngineIndex).oriEnginename
              + (passandMove.equals("") ? "" : " ")
              + passandMove
              + ","
              + resourceBundle.getString("EngineGameInfo.engineGameEndHintKifuPos")
              + courseFile
              + File.separator
              + "EngineGames"));
    } else {
      // 单局
      String jg = resourceBundle.getString("EngineGameInfo.gameFinished"); // "对战已结束，";
      if (engineList.get(resgnEngineIndex).outOfMoveNum)
        jg = jg + resourceBundle.getString("EngineGameInfo.finishedByMoves"); // "超过手数限制";
      else {
        if (engineList.get(resgnEngineIndex).doublePass) {
          jg = jg + resourceBundle.getString("EngineGameInfo.finishedByDoublePass"); // "双Pass对局";
        } else if (resgnEngineIndex == engineGameInfo.blackEngineIndex) {
          // df=df+"_白胜";
          jg =
              jg
                  + resourceBundle.getString("GameInfoDialog.white")
                  + "("
                  + engineList.get(engineGameInfo.whiteEngineIndex).oriEnginename
                  + ")"
                  + resourceBundle.getString("EngineGameInfo.finishedWin");
        } else {
          jg =
              jg
                  + resourceBundle.getString("GameInfoDialog.black")
                  + "("
                  + engineList.get(engineGameInfo.blackEngineIndex).oriEnginename
                  + ")"
                  + resourceBundle.getString("EngineGameInfo.finishedWin");
        }
      }
      if (LizzieFrame.toolbar.AutosavePk) {
        jg =
            jg
                + ","
                + resourceBundle.getString("EngineGameInfo.engineGameEndHintKifuPos")
                + courseFile
                + File.separator
                + "EngineGames";
      }
      Utils.showMsgNoModal(jg);
    }
    isSaveingEngineSGF = false;
    markEngineGameParticipantsStopped(stoppedTransaction, engineGameInfo);
    Lizzie.frame.addInput(true);
    changeEngIcoForEndPk();
    } catch (RuntimeException | Error stopFailure) {
      restoreOnFailure = true;
      if (stoppedTransaction != null) {
        stoppedTransaction.terminalFailure = stopFailure;
      }
      throw stopFailure;
    } finally {
      if (ownsSavingFlag) {
        isSaveingEngineSGF = false;
      }
      if (stoppedTransaction != null) {
        finishEngineGameTransactionRetirement(stoppedTransaction, restoreOnFailure);
      }
    }
  }

  /** Test seam for verifying that PK comment routing is frozen before transaction retirement. */
  protected void appendEngineGameStopComment(boolean forceEngineGame) {
    SGFParser.appendComment(forceEngineGame);
  }

  private void markEngineGameParticipantsStopped(
      EngineGameTransaction transaction, EngineGameInfo gameInfo) {
    Leelaz blackEngine =
        transaction != null
            ? transaction.blackEngine
            : exactCatalogEngine(gameInfo == null ? -1 : gameInfo.blackEngineIndex);
    Leelaz whiteEngine =
        transaction != null
            ? transaction.whiteEngine
            : exactCatalogEngine(gameInfo == null ? -1 : gameInfo.whiteEngineIndex);
    if (blackEngine != null) {
      blackEngine.played = false;
    }
    if (whiteEngine != null) {
      whiteEngine.played = false;
    }
  }

  private Leelaz exactCatalogEngine(int index) {
    List<Leelaz> catalog = engineList;
    return catalog != null && index >= 0 && index < catalog.size() ? catalog.get(index) : null;
  }

  public void startNewEngineGame(boolean firstTime) {
    EngineGameInfo gameAtStart;
    long expectedInactiveEpoch;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      gameAtStart = engineGameInfo;
      expectedInactiveEpoch = engineGameTransactionSequence;
    }
    startNewEngineGame(firstTime, gameAtStart, expectedInactiveEpoch, false);
  }

  private EngineGameTransaction startNewEngineGame(
      boolean firstTime,
      EngineGameInfo gameAtStart,
      Long expectedInactiveEpoch,
      boolean publishGameInfo) {
    if (rejectForegroundEngineStartDuringSetup(true)) return null;
    EngineGameTransaction gameTransaction;
    Leelaz currentForegroundEngine = Lizzie.leelaz;
    long currentForegroundGeneration =
        Lizzie.capturePrimaryEngineGeneration(currentForegroundEngine);
    if (currentForegroundGeneration < 0L) {
      return null;
    }
    if (currentForegroundEngine != null) {
      // Preserve the legacy, user-visible stop-ponder intent even when the lifecycle admission is
      // rejected. This happens before transaction publication, so a throwing override cannot
      // strand PREPARING state or a lifecycle lease.
      currentForegroundEngine.notPondering();
      if (!currentForegroundEngine.beginExclusiveGtpLifecycleTransition()) {
        showForegroundEngineLeaseInUse();
        return null;
      }
      gameTransaction =
          beginEngineGameTransactionUnderForegroundLease(
              currentForegroundEngine,
              currentForegroundGeneration,
              gameAtStart,
              expectedInactiveEpoch,
              publishGameInfo);
    } else {
      gameTransaction =
          beginEngineGameTransaction(
              this,
              gameAtStart,
              expectedInactiveEpoch,
              publishGameInfo,
              currentForegroundEngine,
              currentForegroundGeneration);
    }
    if (gameTransaction == null) {
      return null;
    }
    EngineGameOperationLease startupLease = claimEngineGameOperation(gameTransaction);
    if (startupLease == null) {
      return null;
    }
    Thread startupThread = Thread.currentThread();
    gameTransaction.workers.add(startupThread);
    try {
      startEngineGameDeadlineWatcher(gameTransaction);
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (!isCurrentEngineGameTransactionLocked(gameTransaction)
            || gameTransaction.phase != EngineGamePhase.PREPARING) {
          return null;
      }
      }
      if (currentForegroundEngine != null) {
        currentForegroundEngine.notPondering();
        if (!startupLease.isCurrent()) {
          return null;
        }
      }

      if (firstTime) {
        prepareAcceptedEngineGameStart(gameTransaction);
        if (!startupLease.isCurrent()) {
          return null;
        }
      } else if (gameAtStart.isGenmove) {
        // Batch games receive fresh private clock state. A late memory-only tick from the retiring
        // game can therefore never debit its successor's clock object.
        prepareEngineGameCountDowns(gameTransaction);
        if (!startupLease.isCurrent()) {
          return null;
        }
      }

    // engineGameInfo
    Lizzie.frame.setResult("");
    if (!startupLease.isCurrent()) {
      return null;
    }
    if (firstTime) {
        if (!killOtherEnginesForTransaction(gameTransaction)) {
          return null;
        }
        if (currentForegroundEngine != null) {
          currentForegroundEngine.notPondering();
          if (!startupLease.isCurrent()) {
            return null;
          }
        }
        if (currentEngineNo == gameAtStart.blackEngineIndex
            || currentEngineNo == gameAtStart.whiteEngineIndex) {
          if (currentForegroundEngine != null) {
            currentForegroundEngine.nameCmd();
            if (!startupLease.isCurrent()) {
              return null;
            }
            currentForegroundEngine.clearBestMoves();
          }
      } else {
          if (!isEmpty && currentForegroundEngine != null) {
          try {
              currentForegroundEngine.normalQuit();
              if (!startupLease.isCurrent()) {
                return null;
              }
          } catch (Exception ex) {
          }
        }
      }
    }
      if (!gameAtStart.isGenmove) {
      // 分析模式对战
        ArrayList<Movelist> startList = prepareEngineGameBoard(firstTime, true, gameAtStart);
      if (!startupLease.isCurrent()) {
        return null;
      }
      if (!firstTime) {
          if (!runEngineGameIoStep(gameTransaction, gameTransaction.blackEngine::notPondering)) {
            return null;
          }
          if (!runEngineGameIoStep(gameTransaction, gameTransaction.blackEngine::clear)) {
            return null;
          }
          if (!runEngineGameIoStep(gameTransaction, gameTransaction.whiteEngine::notPondering)) {
            return null;
          }
          if (!runEngineGameIoStep(gameTransaction, gameTransaction.whiteEngine::clear)) {
            return null;
          }
      }
      PkEngineSynchronization blackSynchronization =
            startEngineForPkSynchronization(
                gameTransaction, gameTransaction.blackIndex, gameTransaction.blackEngine);
      if (!startupLease.isCurrent()) return null;
      PkEngineSynchronization whiteSynchronization =
            startEngineForPkSynchronization(
                gameTransaction, gameTransaction.whiteIndex, gameTransaction.whiteEngine);
      if (!startupLease.isCurrent()) return null;
      Runnable runnable =
          new Runnable() {
            public void run() {
              if (!finishPkEngineSynchronizations(
                    gameTransaction, blackSynchronization, whiteSynchronization)) {
                return;
              }
              if (startList != null) {
                try {
                  Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failEngineGameTransaction(
                        gameTransaction,
                        new IllegalStateException(
                            "Engine-game analysis startup was interrupted", e));
                    return;
                }
              }
                Leelaz blackEngine = gameTransaction.blackEngine;
                Leelaz whiteEngine = gameTransaction.whiteEngine;
                if (Lizzie.config.autoLoadLzsaiEngineVisits) {
                  if (!runEngineGameIoStep(
                      gameTransaction,
                      () -> blackEngine.sendCommand("lz-setoption name Visits value 1000000000"))) {
                    return;
                  }
                  if (!runEngineGameIoStep(
                      gameTransaction,
                      () -> whiteEngine.sendCommand("lz-setoption name Visits value 1000000000"))) {
                    return;
                  }
                }
                if (!runEngineGameIoStep(
                    gameTransaction, () -> blackEngine.sendCommand("clear_cache"))) {
                  return;
                }
                if (!runEngineGameIoStep(
                    gameTransaction, () -> whiteEngine.sendCommand("clear_cache"))) {
                  return;
                }
                if (firstTime
                    && !runEngineGameIoStep(
                        gameTransaction, () -> appendEngineGameRules(gameTransaction))) {
                  return;
                }
                if (!isCurrentEngineGameTransaction(gameTransaction)) {
                  return;
                }
                Leelaz selectedEngine =
                    Lizzie.board.getHistory().isBlacksTurn() ? blackEngine : whiteEngine;
                int selectedIndex =
                    selectedEngine == blackEngine
                        ? gameTransaction.blackIndex
                        : gameTransaction.whiteIndex;
                int cmdNumberTemp = selectedEngine.commandNumberSnapshot();
                startEngineGameAnalysisCompletionWorkers(
                    gameTransaction,
                    gameAtStart,
                    selectedEngine,
                    selectedIndex,
                    cmdNumberTemp,
                    BoardFrame.capture(Lizzie.board),
                    firstTime);
                  }
            };
        if (System.nanoTime() >= engineGameDeadlineNanos(gameTransaction)) {
          throw new IllegalStateException("Engine-game analysis setup timed out before dispatch");
                }
        if (!transitionEngineGameToDispatched(gameTransaction)) {
          return null;
              }
        dispatchEngineGameWorker(gameTransaction, "engine-game-analysis-start", runnable);
              } else {
        // genmove对战
        if (gameTransaction.blackEngine != null) {
          if (!runEngineGameIoStep(gameTransaction, gameTransaction.blackEngine::clearBestMoves)) {
            return null;
          }
        }
        if (gameTransaction.whiteEngine != null) {
          if (!runEngineGameIoStep(gameTransaction, gameTransaction.whiteEngine::clearBestMoves)) {
            return null;
          }
              }
        ArrayList<Movelist> startList = prepareEngineGameBoard(firstTime, false, gameAtStart);
        if (!startupLease.isCurrent()) return null;
        PkEngineSynchronization blackSynchronization =
            startEngineForPkSynchronization(
                gameTransaction, gameTransaction.blackIndex, gameTransaction.blackEngine);
        if (!startupLease.isCurrent()) return null;
        PkEngineSynchronization whiteSynchronization =
            startEngineForPkSynchronization(
                gameTransaction, gameTransaction.whiteIndex, gameTransaction.whiteEngine);
        if (!startupLease.isCurrent()) return null;
        Runnable runnable =
                  new Runnable() {
                    public void run() {
                if (!finishPkEngineSynchronizations(
                    gameTransaction, blackSynchronization, whiteSynchronization)) {
                  return;
                }
                if (startList != null) {
                        try {
                    Thread.sleep(1000);
                        } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failEngineGameTransaction(
                        gameTransaction,
                        new IllegalStateException(
                            "Engine-game genmove startup was interrupted", e));
                    return;
                        }
                }
                AtomicBoolean activated = new AtomicBoolean();
                Leelaz blackEngine = gameTransaction.blackEngine;
                Leelaz whiteEngine = gameTransaction.whiteEngine;
                if (!runEngineGameIoStep(gameTransaction, blackEngine::nameCmd)) return;
                if (!runEngineGameIoStep(gameTransaction, blackEngine::notPondering)) return;
                if (!runEngineGameIoStep(gameTransaction, whiteEngine::nameCmd)) return;
                if (!runEngineGameIoStep(gameTransaction, whiteEngine::notPondering)) return;
                if (!runEngineGameIoStep(
                    gameTransaction,
                    () -> gameAtStart.applyCapturedTime(blackEngine, gameTransaction.blackIndex))) {
                  return;
                }
                if (!runEngineGameIoStep(
                    gameTransaction,
                    () -> gameAtStart.applyCapturedTime(whiteEngine, gameTransaction.whiteIndex))) {
                  return;
                }
                if (firstEngineCountDown != null || secondEngineCountDown != null) {
                  if (!runEngineGameIoStep(
                      gameTransaction,
                      () -> {
                        initializeEngineGameCountDowns(gameTransaction, gameAtStart);
                        StartCountDown(gameTransaction);
                      })) {
                    return;
                  }
                }
                if (!runEngineGameIoStep(
                    gameTransaction, () -> blackEngine.sendCommand("clear_cache"))) return;
                if (!runEngineGameIoStep(
                    gameTransaction, () -> whiteEngine.sendCommand("clear_cache"))) return;
                if (!isCurrentEngineGameTransaction(gameTransaction)) return;
                Leelaz moverEngine;
                String moverColor;
                Leelaz selectedEngine;
                int selectedIndex;
                if (Lizzie.board.getHistory().isBlacksTurn()) {
                  moverEngine = blackEngine;
                  moverColor = "b";
                  selectedEngine = whiteEngine;
                  selectedIndex = gameTransaction.whiteIndex;
                } else {
                  moverEngine = whiteEngine;
                  moverColor = "w";
                  selectedEngine = blackEngine;
                  selectedIndex = gameTransaction.blackIndex;
                }
                if (firstTime
                    && !runEngineGameIoStep(
                        gameTransaction, () -> appendEngineGameRules(gameTransaction))) return;
                if (!activateEngineGameTransaction(
                    gameTransaction,
                    selectedEngine,
                    selectedIndex,
                    gameTransaction.blackIncarnation,
                    gameTransaction.whiteIncarnation)) {
                  throw new IllegalStateException(
                      "Engine-game ownership changed before genmove activation");
                }
                AtomicBoolean genmoveAccepted = new AtomicBoolean();
                if (!runEngineGameIoStep(
                    gameTransaction,
                    () ->
                        genmoveAccepted.set(
                            moverEngine.genmoveForPk(moverColor, gameTransaction)))) {
                  return;
                }
                if (!genmoveAccepted.get()) {
                  throw new IllegalStateException(
                      "Engine-game genmove request lost exact transaction ownership");
                }
                activated.set(true);
                if (activated.get()) {
                  publishEngineGameStartedUi(gameTransaction, gameAtStart, true, firstTime);
                    }
            }
          };
        if (System.nanoTime() >= engineGameDeadlineNanos(gameTransaction)) {
          throw new IllegalStateException("Engine-game genmove setup timed out before dispatch");
        }
        if (!transitionEngineGameToDispatched(gameTransaction)) {
          return null;
      }
        dispatchEngineGameWorker(gameTransaction, "engine-game-genmove-start", runnable);
      }
      return gameTransaction;
    } catch (RuntimeException | Error startupFailure) {
      failEngineGameTransaction(gameTransaction, startupFailure);
      throw startupFailure;
    } finally {
      gameTransaction.workers.remove(startupThread);
      startupLease.close();
    }
  }

  private EngineGameTransaction beginEngineGameTransactionUnderForegroundLease(
      Leelaz foregroundEngine,
      long foregroundGeneration,
      EngineGameInfo gameInfo,
      Long expectedInactiveEpoch,
      boolean publishGameInfo) {
    EngineGameTransaction transaction = null;
    Throwable primaryFailure = null;
    try {
      transaction =
          beginEngineGameTransaction(
              this,
              gameInfo,
              expectedInactiveEpoch,
              publishGameInfo,
              foregroundEngine,
              foregroundGeneration);
      return transaction;
    } catch (RuntimeException | Error admissionFailure) {
      primaryFailure = admissionFailure;
      if (transaction != null) {
        failEngineGameTransaction(transaction, admissionFailure);
              }
      throw admissionFailure;
    } finally {
                try {
        foregroundEngine.endExclusiveGtpLifecycleTransition();
      } catch (RuntimeException | Error releaseFailure) {
        if (transaction != null) {
          failEngineGameTransaction(transaction, releaseFailure);
        }
        if (primaryFailure != null) {
          if (releaseFailure != primaryFailure) {
            try {
              primaryFailure.addSuppressed(releaseFailure);
            } catch (Throwable ignored) {
                }
              }
              } else {
          throw releaseFailure;
              }
      }
    }
  }

  private void prepareAcceptedEngineGameStart(EngineGameTransaction transaction) {
    EngineGameInfo gameInfo = transaction.gameInfo;
    if (Lizzie.frame.isTrying) {
      Lizzie.frame.tryPlay(false);
    }
    if (Lizzie.frame.isShowingHeatmap && Lizzie.leelaz != null) {
      Lizzie.leelaz.toggleHeatmap(true);
    }
    if (!isEmpty && Lizzie.leelaz != null) {
      Lizzie.leelaz.clearBestMoves();
    }
    Lizzie.frame.hasEnginePkTitile = false;
    Lizzie.frame.enginePkTitile = "";
    Lizzie.frame.removeInput(true);
    LizzieFrame.winrateGraph.resetMaxScoreLead();
    Lizzie.frame.isPlayingAgainstLeelaz = false;
    Lizzie.frame.isAnaPlayingAgainstLeelaz = false;
    Lizzie.config.isAutoAna = false;
    Lizzie.board.isPkBoard = true;
    if (gameInfo.isGenmove) {
      prepareEngineGameCountDowns(transaction);
    }
  }

  private void prepareEngineGameCountDowns(EngineGameTransaction transaction) {
    EngineGameInfo gameInfo = transaction.gameInfo;
    clearFirstSecondEngineCountDown();
    boolean firstPlaysBlack = gameInfo.isFirstEnginePlayBlack();
    DesktopTimeControl.SideMode firstMode =
        firstPlaysBlack ? gameInfo.blackTimeMode : gameInfo.whiteTimeMode;
    String firstTimeCommand =
        firstPlaysBlack ? gameInfo.advanceBlackTimeCmd : gameInfo.advanceWhiteTimeCmd;
    if (firstMode == DesktopTimeControl.SideMode.RAW_ADVANCED) {
      firstEngineCountDown = new EngineCountDown();
      Leelaz firstEngine = exactEngineGameParticipant(transaction, gameInfo.firstEngineIndex);
      boolean parsed =
          firstEngine != null
              && firstEngineCountDown.setEngineCountDown(firstTimeCommand, firstEngine);
      if (!parsed) {
        firstEngineCountDown = null;
        Utils.showMsgNoModal(
            resourceBundle.getString("EngineManager.parseAdvcanceTimeSettingsFailed"));
      }
    }
    DesktopTimeControl.SideMode secondMode =
        firstPlaysBlack ? gameInfo.whiteTimeMode : gameInfo.blackTimeMode;
    String secondTimeCommand =
        firstPlaysBlack ? gameInfo.advanceWhiteTimeCmd : gameInfo.advanceBlackTimeCmd;
    if (secondMode == DesktopTimeControl.SideMode.RAW_ADVANCED) {
      secondEngineCountDown = new EngineCountDown();
      Leelaz secondEngine = exactEngineGameParticipant(transaction, gameInfo.secondEngineIndex);
      boolean parsed =
          secondEngine != null
              && secondEngineCountDown.setEngineCountDown(secondTimeCommand, secondEngine);
      if (!parsed) {
        secondEngineCountDown = null;
        Utils.showMsgNoModal(
            resourceBundle.getString("EngineManager.parseAdvcanceTimeSettingsFailed"));
      }
    }
  }

  private void initializeEngineGameCountDowns(
      EngineGameTransaction transaction, EngineGameInfo gameInfo) {
    boolean firstPlaysBlack = gameInfo.isFirstEnginePlayBlack();
    EngineCountDown first = firstEngineCountDown;
    EngineCountDown second = secondEngineCountDown;
    if (first != null) {
      first.initialize(firstPlaysBlack);
    }
    if (second != null) {
      second.initialize(!firstPlaysBlack);
    }
    EngineCountDown blackClock = firstPlaysBlack ? first : second;
    EngineCountDown whiteClock = firstPlaysBlack ? second : first;
    if (!installEngineGameCountDowns(transaction, blackClock, whiteClock)) {
      throw new IllegalStateException(
          "Engine-game clocks changed before exact participant initialization");
    }
  }

  private static boolean installEngineGameCountDowns(
      EngineGameTransaction transaction,
      EngineCountDown blackClock,
      EngineCountDown whiteClock) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGameTransactionLocked(transaction)
          || (blackClock != null && !blackClock.belongsTo(transaction.blackEngine, true))
          || (whiteClock != null && !whiteClock.belongsTo(transaction.whiteEngine, false))) {
        return false;
      }
      transaction.blackCountDown = blackClock;
      transaction.whiteCountDown = whiteClock;
      return true;
    }
  }

  static boolean installEngineGameCountDownsForTest(
      EngineGameTransaction transaction,
      EngineCountDown blackClock,
      EngineCountDown whiteClock) {
    return installEngineGameCountDowns(transaction, blackClock, whiteClock);
  }

  private void appendEngineGameRules(EngineGameTransaction transaction) {
    EngineGameInfo gameInfo = transaction.gameInfo;
    Leelaz firstEngine = exactEngineGameParticipant(transaction, gameInfo.firstEngineIndex);
    if (firstEngine == null) {
      throw new IllegalStateException("Engine-game first participant left its frozen catalog slot");
    }
    if (firstEngine.isKatago
        && !firstEngine.recentRulesLine.isEmpty()
        && firstEngine.recentRulesLine.length() > 2) {
      gameInfo.settingFirst +=
                        "\r\n"
                            + resourceBundle.getString("EngineGameInfo.rules")
                            + ": "
              + firstEngine.recentRulesLine.substring(2);
                }
    Leelaz secondEngine = exactEngineGameParticipant(transaction, gameInfo.secondEngineIndex);
    if (secondEngine == null) {
      throw new IllegalStateException("Engine-game second participant left its frozen catalog slot");
    }
    if (secondEngine.isKatago
        && !secondEngine.recentRulesLine.isEmpty()
        && secondEngine.recentRulesLine.length() > 2) {
      gameInfo.settingSecond +=
                        "\r\n"
                            + resourceBundle.getString("EngineGameInfo.rules")
                            + ": "
              + secondEngine.recentRulesLine.substring(2);
    }
  }

  private void startEngineGameAnalysisCompletionWorkers(
      EngineGameTransaction transaction,
      EngineGameInfo gameInfo,
      Leelaz selectedEngine,
      int selectedIndex,
      int commandNumber,
      BoardFrame activationFrame,
      boolean firstTime) {
    Runnable activationTask =
        () -> {
          if (!waitForEngineGameCondition(
              transaction,
              selectedEngine::isResponseUpToDate,
              "Engine-game analysis startup timed out waiting for command responses")) {
            return;
          }
          if (!runEngineGameIoStep(transaction, selectedEngine::ponder)) return;
          if (!runEngineGameIoStep(transaction, selectedEngine::clearBestMoves)) return;
          if (!waitForEngineGameCondition(
              transaction,
              () -> selectedEngine.commandNumberSnapshot() != commandNumber,
              "Engine-game analysis startup timed out waiting for analysis dispatch")) {
            return;
                }
          AtomicBoolean activated = new AtomicBoolean();
          if (!isCurrentEngineGameTransaction(transaction)) return;
          BoardFrame currentFrame = BoardFrame.capture(Lizzie.board);
          if (activationFrame == null || !activationFrame.matches(currentFrame)) {
            throw new IllegalStateException("Engine-game board changed before analysis activation");
          }
          if (!isCurrentEngineGameTransaction(transaction)) return;
          selectedEngine.played = false;
          if (!activateEngineGameTransaction(
              transaction,
              selectedEngine,
              selectedIndex,
              transaction.blackIncarnation,
              transaction.whiteIncarnation)) {
            throw new IllegalStateException(
                "Engine-game ownership changed before analysis activation");
          }
          activated.set(true);
          if (activated.get()) {
            publishEngineGameStartedUi(transaction, gameInfo, false, firstTime);
            }
          };
    dispatchEngineGameWorker(transaction, "engine-game-analysis-activation", activationTask);
    }

  private boolean waitForEngineGameCondition(
      EngineGameTransaction transaction, BooleanSupplier condition, String timeoutDetail) {
    while (isCurrentEngineGameTransaction(transaction)) {
      if (condition.getAsBoolean()) {
        return true;
      }
      if (System.nanoTime() >= engineGameDeadlineNanos(transaction)) {
        failEngineGameTransaction(transaction, new IllegalStateException(timeoutDetail));
        return false;
      }
      try {
        Thread.sleep(25L);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        failEngineGameTransaction(
            transaction,
            new IllegalStateException("Engine-game startup was interrupted", interrupted));
        return false;
      }
    }
    return false;
  }

  private void startEngineGameDeadlineWatcher(EngineGameTransaction transaction) {
    dispatchEngineGameWorker(
        transaction,
        "engine-game-deadline-" + transaction.epoch,
        () -> {
          while (isCurrentEngineGameTransaction(transaction)
              && transaction.phase != EngineGamePhase.ACTIVE) {
            long remaining = engineGameDeadlineNanos(transaction) - System.nanoTime();
            if (remaining <= 0L) {
              failEngineGameTransaction(
                  transaction,
                  new IllegalStateException("Engine-game startup deadline expired"));
              return;
            }
            try {
              TimeUnit.NANOSECONDS.sleep(
                  Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(250L)));
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              if (isCurrentEngineGameTransaction(transaction)) {
                failEngineGameTransaction(
                    transaction,
                    new IllegalStateException(
                        "Engine-game startup deadline watcher was interrupted", interrupted));
              }
              return;
            }
          }
        });
  }

  protected Thread createEngineGameWorker(Runnable task, String name) {
    return new Thread(task, name);
  }

  boolean dispatchEngineGameWorker(EngineGameTransaction transaction, String name, Runnable task) {
    return dispatchEngineGameWorker(transaction, name, task, false);
  }

  private boolean dispatchEngineGameWorker(
      EngineGameTransaction transaction, String name, Runnable task, boolean runWhenRetired) {
    EngineGameOperationLease workerLease = claimEngineGameOperation(transaction);
    if (workerLease == null) {
      return false;
    }
    EngineGameWorkerDispatch dispatch = new EngineGameWorkerDispatch();
    Runnable guardedTask =
        () -> {
          if (!dispatch.owner.compareAndSet(0, 1)) {
            return;
          }
          Thread worker = Thread.currentThread();
          transaction.workers.add(worker);
          try {
            if (!runWhenRetired && !workerLease.isCurrent()) {
              return;
            }
            task.run();
          } catch (RuntimeException | Error workerFailure) {
            failEngineGameTransaction(transaction, workerFailure);
            throw workerFailure;
          } finally {
            transaction.workers.remove(worker);
            workerLease.close();
          }
        };
    try {
      Thread worker = createEngineGameWorker(guardedTask, name);
      worker.setDaemon(true);
      worker.start();
      return true;
    } catch (RuntimeException | Error schedulingFailure) {
      if (dispatch.owner.compareAndSet(0, 2)) {
        workerLease.close();
        failEngineGameTransaction(transaction, schedulingFailure);
        throw schedulingFailure;
      }
      // A custom scheduler may start the worker and then throw. The worker already owns the
      // transaction; failing it here would create a second terminal owner.
      schedulingFailure.printStackTrace();
      return true;
    }
  }

  static boolean dispatchEngineGameStartupWorker(
      EngineGameTransaction transaction, String name, Runnable task) {
    if (transaction == null || transaction.manager == null || task == null) {
      return false;
    }
    return transaction.manager.dispatchEngineGameWorker(transaction, name, task);
  }

  private void publishEngineGameStartedUi(
      EngineGameTransaction transaction,
      EngineGameInfo gameInfo,
      boolean genmove,
      boolean firstTime) {
    Runnable presentation =
        () ->
            runIfCurrentEngineGameTransaction(
                transaction,
                () -> {
                  Throwable failure = null;
                  failure = runEngineGameCleanupStep(failure, Lizzie.frame::reSetLoc);
                  failure =
                      runEngineGameCleanupStep(
                          failure, () -> Lizzie.frame.clearWRNforGame(genmove));
                  failure =
                      runEngineGameCleanupStep(
                          failure,
                          () ->
                              Lizzie.frame.setPlayers(
                                  transaction.whiteEngine.oriEnginename,
                                  transaction.blackEngine.oriEnginename));
                  GameInfo boardGameInfo = Lizzie.board.getHistory().getGameInfo();
                  failure =
                      runEngineGameCleanupStep(
                          failure,
                          () ->
                              boardGameInfo.setPlayerWhite(
                                  transaction.whiteEngine.oriEnginename));
                  failure =
                      runEngineGameCleanupStep(
                          failure,
                          () ->
                              boardGameInfo.setPlayerBlack(
                                  transaction.blackEngine.oriEnginename));
                  failure = runEngineGameCleanupStep(failure, Lizzie.frame::updateTitle);
                  failure =
                      runEngineGameCleanupStep(
                          failure, () -> LizzieFrame.menu.toggleDoubleMenuGameStatus());
                  if (firstTime) {
                    failure =
                        runEngineGameCleanupStep(
                            failure, Lizzie.frame::resetMovelistFrameandAnalysisFrame);
                    failure =
                        runEngineGameCleanupStep(
                            failure, () -> LizzieFrame.menu.updateMenuStatusForEngine());
                  }
                  if (failure != null) {
                    failure.printStackTrace();
                  }
                });
    if (SwingUtilities.isEventDispatchThread()) {
      presentation.run();
      return;
    }
    try {
      dispatchEngineGameUi(presentation);
    } catch (RuntimeException | Error dispatchFailure) {
      presentation.run();
      dispatchFailure.printStackTrace();
    }
  }

  private void publishEngineGamePreparingUi(EngineGameTransaction transaction) {
    Runnable presentation =
        () ->
            runIfCurrentEngineGameTransaction(
                transaction,
                () -> {
                  Throwable failure = null;
                  failure =
                      runEngineGameCleanupStep(
                          failure,
                          () -> {
                            if (LizzieFrame.toolbar != null
                                && LizzieFrame.toolbar.lblenginePkResult != null) {
                              LizzieFrame.toolbar.lblenginePkResult.setText("0:0");
                            }
                          });
                  failure =
                      runEngineGameCleanupStep(
                          failure,
                          () -> {
                            if (Menu.engineMenu != null) {
                              Menu.engineMenu.setText(
                                  resourceBundle.getString(
                                      "EngineManager.engineGamePlaying"));
                            }
                          });
                  failure =
                      runEngineGameCleanupStep(
                          failure,
                          () -> {
                            if (LizzieFrame.menu != null) {
                              LizzieFrame.menu.toggleEngineMenuStatus(true, false);
                            }
                          });
                  failure =
                      runEngineGameCleanupStep(
                          failure,
                          () -> {
                            if (LizzieFrame.toolbar != null) {
                              LizzieFrame.toolbar.enableDisabelForEngineGame(false);
                            }
                          });
                  if (failure != null) {
                    failure.printStackTrace();
                  }
                });
    if (SwingUtilities.isEventDispatchThread()) {
      presentation.run();
      return;
    }
    try {
      dispatchEngineGameUi(presentation);
    } catch (RuntimeException | Error dispatchFailure) {
      presentation.run();
      dispatchFailure.printStackTrace();
    }
  }

  private void setInfoAfterEngineGame(EngineGameInfo gameInfo) {
    Lizzie.frame.setPlayers(
        engineList.get(gameInfo.whiteEngineIndex).oriEnginename,
        engineList.get(gameInfo.blackEngineIndex).oriEnginename);
    GameInfo boardGameInfo = Lizzie.board.getHistory().getGameInfo();
    boardGameInfo.setPlayerWhite(engineList.get(gameInfo.whiteEngineIndex).oriEnginename);
    boardGameInfo.setPlayerBlack(engineList.get(gameInfo.blackEngineIndex).oriEnginename);
    Lizzie.frame.updateTitle();
    LizzieFrame.menu.toggleDoubleMenuGameStatus();
  }

  //  private void checkEngineNotHang() {
  //    if (isEngineGame
  //        && !Lizzie.frame.toolbar.isGenmoveToolbar
  //        && !Lizzie.frame.toolbar.isPkStop
  //        && System.currentTimeMillis() - startInfoTime > 1000 * 240) {
  //      Lizzie.leelaz.process.destroy();
  //      Lizzie.gtpConsole.addLine("EnginePkHangs");
  //      startInfoTime = System.currentTimeMillis();
  //    }
  //    //    try {
  //    //      timer3.stop();
  //    //      // timer3 = null;
  //    //    } catch (Exception ex) {
  //    //
  //    //    }
  //  }

  private void checkEngineAlive() {
    if (isEmpty || isSetupModeActive()) return;
    if (!isEngineGame && Lizzie.leelaz != null) {
      if (Lizzie.leelaz.isStarted()
          && Lizzie.leelaz.canCheckAlive
          && Lizzie.leelaz.isProcessDead()) {
        if (Lizzie.leelaz.useRemoteCompute) {
          restartRemoteEngineInBackground(Lizzie.leelaz, currentEngineNo);
        } else {
          try {
            restartEngineAutomatically(Lizzie.leelaz, currentEngineNo);
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      }
      if (Lizzie.leelaz.useJavaSSH && Lizzie.leelaz.isLoaded() && Lizzie.leelaz.canCheckAlive) {
        if (Lizzie.leelaz.javaSSHClosed)
          try {
            restartEngineAutomatically(Lizzie.leelaz, currentEngineNo);
          } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
          }
      }
    }
    //   if (isEngineGame) {
    //    {
    // checkEngineNotHang();
    checkEnginePK();
    // if (Lizzie.leelaz.resigned) Lizzie.leelaz.pkResign();
    //        if (Lizzie.leelaz.isPondering() && (timer3 == null || !timer3.isRunning())) {
    //          timer3 =
    //              new Timer(
    //                  5000,
    //                  new ActionListener() {
    //                    public void actionPerformed(ActionEvent evt) {
    //
    //
    //                      try {
    //                      } catch (Exception e) {
    //                      }
    //                    }
    //                  });
    //          timer3.start();
    //        }
    //      }
    //      if ((timer2 == null || !timer2.isRunning())) {
    //        timer2 =
    //            new Timer(
    //                20000,
    //                new ActionListener() {
    //                  public void actionPerformed(ActionEvent evt) {
    //                    checkEnginePK();
    //                    try {
    //                    } catch (Exception e) {
    //                    }
    //                  }
    //                });
    //        timer2.start();
    //    }
    //   }
  }

  private void checkEnginePK() {
    EngineGameParticipantProbe[] probes;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      EngineGameTransaction transaction = activeEngineGameTransaction;
      if (!isEngineGame
          || transaction == null
          || transaction.manager != this
          || transaction.phase != EngineGamePhase.ACTIVE) {
        return;
      }
      probes =
          new EngineGameParticipantProbe[] {
            new EngineGameParticipantProbe(
                transaction.blackEngine, transaction.blackIncarnation),
            new EngineGameParticipantProbe(
                transaction.whiteEngine, transaction.whiteIncarnation)
          };
    }
    for (EngineGameParticipantProbe probe : probes) {
      Leelaz engine = probe.engine;
      if (engine == null
          || probe.incarnation == null
          || !engine.canCheckAlive
          || (!engine.isProcessDead() && !(engine.useJavaSSH && engine.javaSSHClosed))) {
        continue;
      }
      try {
        requestEngineGameParticipantRecovery(
            this,
            engine,
            probe.incarnation,
            engine.classifyEngineGameRecoveryCause(probe.incarnation));
      } catch (RuntimeException | Error failure) {
        failure.printStackTrace();
      }
    }
    //    try {
    //      timer2.stop();
    //      // timer2 = null;
    //    } catch (Exception ex) {
    //
    //    }
  }

  public void updateEngines() throws JSONException, IOException {
    if (rejectForegroundEngineStartDuringSetup(true)) return;
    isUpdating = true;
    try {
      updateEnginesWhileMarkedUpdating();
    } finally {
      isUpdating = false;
    }
  }

  private void updateEnginesWhileMarkedUpdating() throws JSONException, IOException {
    int preIndex = currentEngineNo;
    Leelaz previousForegroundEngine = Lizzie.leelaz;
    Leelaz previousSecondaryEngine = Lizzie.leelaz2;
    String currentEngineName =
        previousForegroundEngine == null ? "" : previousForegroundEngine.oriEnginename;
    String currentSecondaryEngineName =
        previousSecondaryEngine == null ? "" : previousSecondaryEngine.oriEnginename;
    ArrayList<EngineData> engineData = Utils.getEngineData();
    EngineData selectedEngineData = null;
    EngineData selectedSecondaryData = null;
    for (EngineData engineDt : engineData) {
      if (selectedEngineData == null && engineDt.name.equals(currentEngineName)) {
        selectedEngineData = engineDt;
      }
      if (selectedSecondaryData == null
          && !currentSecondaryEngineName.isEmpty()
          && engineDt.name.equals(currentSecondaryEngineName)
          && engineDt != selectedEngineData) {
        selectedSecondaryData = engineDt;
      }
    }
    Board restoreBoard = Lizzie.board;
    boolean resumePonder =
        previousForegroundEngine != null
            && previousForegroundEngine.isPonderingOrWasPonderingBeforeTracking();
    Leelaz preparedTarget = null;
    Leelaz preparedMirror = null;
    InitialEngineStartupSynchronization lifecycleSynchronization = null;
    if (selectedEngineData != null) {
      preparedTarget = createUnstartedEngine(selectedEngineData);
      if (selectedSecondaryData != null && previousSecondaryEngine != null) {
        preparedMirror = createUnstartedEngine(selectedSecondaryData);
      }
      boolean exactEligible =
          restoreBoard != null
              && preparedTarget.oriWidth == Board.boardWidth
              && preparedTarget.oriHeight == Board.boardHeight
              && (preparedMirror == null
                  || (preparedMirror.oriWidth == Board.boardWidth
                      && preparedMirror.oriHeight == Board.boardHeight));
      try {
        lifecycleSynchronization =
            InitialEngineStartupSynchronization.capture(
                previousForegroundEngine,
                preparedTarget,
                preparedMirror,
                restoreBoard,
                !exactEligible,
                resumePonder);
        lifecycleSynchronization.beginLifecycleCompletionClaim();
      } catch (InitialStartupReservationException leaseInUse) {
        boolean cleanupFailed =
            closeUpdateLifecycleSynchronization(lifecycleSynchronization, leaseInUse);
        if (cleanupFailed) {
          throw leaseInUse;
        }
        showForegroundEngineLeaseInUse();
        return;
      } catch (RuntimeException | Error startupFailure) {
        boolean cleanupFailed =
            closeUpdateLifecycleSynchronization(lifecycleSynchronization, startupFailure);
        if (startupFailure instanceof Error || cleanupFailed) {
          rethrowLifecycleCleanupFailure(startupFailure);
        }
        startupFailure.printStackTrace();
        preparedTarget.isLoaded = false;
        if (preparedMirror != null) {
          preparedMirror.isLoaded = false;
        }
        showEngineSynchronizationFailure(preparedTarget);
        return;
      }
    }
    boolean updateSyncDelegated = false;
    final InitialEngineStartupSynchronization frozenLifecycleSynchronization =
        lifecycleSynchronization;
    Throwable updateFailure = null;
    try {
      if (lifecycleSynchronization == null) {
        if (!killAllEngines()) {
          return;
        }
      } else {
        killAllEnginesUnderReservation();
      }
      final Leelaz frozenPreparedTarget = preparedTarget;
      final Leelaz frozenPreparedMirror = preparedMirror;
      Lizzie.runWithEngineAuthorityMutation(
          () -> {
            engineList = new ArrayList<Leelaz>();
            return null;
          });
      Thread replacementStart = null;
      // engineList.add(lz);
      boolean loadLeelaz = false;
      for (int i = 0; i < engineData.size(); i++) {
        EngineData engineDt = engineData.get(i);
        Leelaz e;
        if (engineDt == selectedEngineData) {
          e = frozenPreparedTarget;
        } else if (engineDt == selectedSecondaryData) {
          e = frozenPreparedMirror;
        } else {
          e = createUnstartedEngine(engineDt);
        }
        if (!loadLeelaz && engineDt.name.equals(currentEngineName)) {
          loadLeelaz = true;
          if (e.oriWidth != Board.boardWidth || e.oriHeight != Board.boardHeight) {
            Board.boardWidth = e.oriWidth;
            Board.boardHeight = e.oriHeight;
            Zobrist.init();
            Lizzie.board.clear(false);
          }
          e.preload = true;
          e.firstLoad = true;
          engineNo = i;
          // killAllEnginesUnderReservation() publishes the no-engine state before the
          // replacement catalog is rebuilt.  Install all primary-selection fields as one
          // canonical selection -> primary transition so the replacement worker's exact
          // terminal fence cannot observe the new owner with the stale empty marker.
          publishPrimarySelectionState(e, i, false);
          final int frozenReplacementIndex = i;
          final EngineData frozenSelectedSecondaryData = selectedSecondaryData;
          replacementStart =
              new Thread() {
                public void run() {
                  boolean synchronizationScheduled = false;
                  Throwable startupFailure = null;
                  Leelaz.UpdateEngineStartAttempt targetAttempt = null;
                  Leelaz.UpdateEngineStartAttempt mirrorAttempt = null;
                  Object acquisitionIncarnation = e.captureEngineIncarnationFence();
                  long acquisitionPrimaryGeneration = Lizzie.capturePrimaryEngineGeneration(e);
                  try {
                    try {
                      targetAttempt = e.beginUpdateEngineStartAttempt();
                      targetAttempt.bindPrimaryEngineGeneration(acquisitionPrimaryGeneration);
                    } catch (RuntimeException | Error acquisitionFailure) {
                      startupFailure = acquisitionFailure;
                      acquisitionFailure.printStackTrace();
                      return;
                    }
                    try {
                      mirrorAttempt =
                          frozenPreparedMirror == null
                              ? null
                              : frozenPreparedMirror.beginUpdateEngineStartAttempt();
                    } catch (RuntimeException | Error acquisitionFailure) {
                      startupFailure = acquisitionFailure;
                      acquisitionFailure.printStackTrace();
                      return;
                    }
                    try {
                      targetAttempt.startEngine(engineDt.index);
                      if (frozenPreparedMirror != null) {
                        mirrorAttempt.startEngine(
                            frozenSelectedSecondaryData == null
                                ? -1
                                : frozenSelectedSecondaryData.index);
                      }
                      publishReplacementEngineMenuStateIfCurrent(
                          frozenReplacementIndex,
                          e,
                          targetAttempt.publishedIncarnation(),
                          "[" + (e.currentEngineN() + 1) + "]: " + e.oriEnginename,
                          3);
                    } catch (IOException | RuntimeException | Error e2) {
                      startupFailure = e2;
                      e2.printStackTrace();
                      return;
                    }
                    final Leelaz.UpdateEngineStartAttempt frozenTargetAttempt = targetAttempt;
                    final Leelaz.UpdateEngineStartAttempt frozenMirrorAttempt = mirrorAttempt;
                    Runnable syncBoard =
                        () -> {
                          frozenLifecycleSynchronization.runUntilStable();
                          frozenLifecycleSynchronization.confirmFinalBoardSynchronization(
                              () -> {
                                completeUpdateEngineReplacementStart(
                                    frozenReplacementIndex,
                                    e,
                                    frozenPreparedMirror,
                                    frozenTargetAttempt,
                                    frozenMirrorAttempt,
                                    frozenLifecycleSynchronization);
                              },
                              detail -> {
                                IllegalStateException synchronizationFailure =
                                    new IllegalStateException(detail);
                                failUpdateEngineStartAfterLifecycleRelease(
                                    frozenReplacementIndex,
                                    e,
                                    frozenPreparedMirror,
                                    frozenTargetAttempt,
                                    frozenMirrorAttempt,
                                    synchronizationFailure,
                                    frozenLifecycleSynchronization);
                              });
                        };
                    // The synchronization helper owns failure settlement even when allocating or
                    // starting its worker throws. Mark delegation before entering it so this
                    // outer finally cannot re-close the once-outcome lifecycle and replace the
                    // original scheduling failure.
                    synchronizationScheduled = true;
                    synchronizeUpdateEnginesWhenReady(
                        frozenReplacementIndex,
                        e,
                        frozenPreparedMirror,
                        frozenTargetAttempt,
                        frozenMirrorAttempt,
                        syncBoard,
                        () ->
                            closeUpdateEngineLifecycleSynchronization(
                                frozenLifecycleSynchronization),
                        frozenLifecycleSynchronization::runAfterCompletionRelease);
                  } finally {
                    if (!synchronizationScheduled) {
                      if (startupFailure != null && targetAttempt != null) {
                        failUpdateEngineStartAfterLifecycleRelease(
                            frozenReplacementIndex,
                            e,
                            frozenPreparedMirror,
                            targetAttempt,
                            mirrorAttempt,
                            startupFailure,
                            frozenLifecycleSynchronization);
                      } else {
                        if (startupFailure != null) {
                          reportUpdateEngineStartAcquisitionFailure(
                              frozenReplacementIndex,
                              e,
                              acquisitionIncarnation,
                              acquisitionPrimaryGeneration,
                              startupFailure);
                        }
                        closeUpdateEngineLifecycleSynchronization(frozenLifecycleSynchronization);
                      }
                    }
                  }
                }
              };
        } else if (engineDt == selectedSecondaryData) {
          Lizzie.leelaz2 = e;
          e.preload = true;
          e.firstLoad = true;
          currentEngineNo2 = i;
        } else if (e.preload) {
          new Thread() {
            public void run() {
              try {
                e.startEngine(engineDt.index);
              } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
              }
            }
          }.start();
        }
        engineList.add(e);
      }
      if (replacementStart != null) {
        updateSyncDelegated = true;
        try {
          startUpdateEngineReplacement(replacementStart);
        } catch (RuntimeException | Error failure) {
          updateSyncDelegated = false;
          throw failure;
        }
      }
      if (!loadLeelaz && preIndex >= 0) {
        switchEngine(preIndex, true);
      }

      int j = LizzieFrame.toolbar.enginePkBlack.getItemCount();
      LizzieFrame.toolbar.removeEngineLis();
      for (int i = 0; i < j; i++) {
        LizzieFrame.toolbar.enginePkBlack.removeItemAt(0);
        LizzieFrame.toolbar.enginePkWhite.removeItemAt(0);
      }
      for (int i = 0; i < engineData.size(); i++) {
        EngineData engineDt = engineData.get(i);
        LizzieFrame.toolbar.enginePkBlack.addItem("[" + (i + 1) + "]" + engineDt.name);
        LizzieFrame.toolbar.enginePkWhite.addItem("[" + (i + 1) + "]" + engineDt.name);
      }
      LizzieFrame.toolbar.engineBlackToolbar = 0;
      LizzieFrame.toolbar.engineWhiteToolbar = 0;
      LizzieFrame.toolbar.addEngineLis();
      LizzieFrame.menu.updateEngineMenu();
      if (!isEmpty) {
        Menu.engineMenu.setText(
            "["
                + (EngineManager.currentEngineNo > 0
                    ? EngineManager.currentEngineNo + 1
                    : engineNo + 1)
                + "]: "
                + Lizzie.leelaz.oriEnginename);
      }
    } catch (IOException | RuntimeException | Error failure) {
      updateFailure = failure;
      throw failure;
    } finally {
      if (!updateSyncDelegated && frozenLifecycleSynchronization != null) {
        closeUpdateLifecycleSynchronization(frozenLifecycleSynchronization, updateFailure);
      }
    }
  }

  private static boolean closeUpdateLifecycleSynchronization(
      InitialEngineStartupSynchronization synchronization, Throwable primaryFailure) {
    if (synchronization == null) {
      return false;
    }
    try {
      synchronization.close();
      return false;
    } catch (RuntimeException | Error cleanupFailure) {
      if (primaryFailure != null) {
        if (primaryFailure != cleanupFailure) {
          primaryFailure.addSuppressed(cleanupFailure);
        }
        return true;
      }
      throw cleanupFailure;
    }
  }

  /** Test seam for deterministic replacement-worker scheduling failures. */
  protected void startUpdateEngineReplacement(Thread replacementStart) {
    replacementStart.start();
  }

  private static Throwable runLifecycleCleanupStep(Throwable primaryFailure, Runnable cleanup) {
    try {
      cleanup.run();
    } catch (RuntimeException | Error cleanupFailure) {
      if (primaryFailure == null) {
        return cleanupFailure;
      }
      if (primaryFailure != cleanupFailure) {
        primaryFailure.addSuppressed(cleanupFailure);
      }
    }
    return primaryFailure;
  }

  private static Runnable onceEngineSwitchCleanup(Runnable cleanup) {
    AtomicBoolean claimed = new AtomicBoolean(false);
    AtomicReference<Thread> owner = new AtomicReference<>();
    AtomicReference<Throwable> outcome = new AtomicReference<>();
    CountDownLatch settled = new CountDownLatch(1);
    return () -> {
      if (claimed.compareAndSet(false, true)) {
        owner.set(Thread.currentThread());
        try {
          cleanup.run();
        } catch (RuntimeException | Error failure) {
          outcome.set(failure);
          throw failure;
        } finally {
          owner.set(null);
          settled.countDown();
        }
        return;
      }
      if (owner.get() == Thread.currentThread()) {
        return;
      }
      boolean interrupted = false;
      while (true) {
        try {
          settled.await();
          break;
        } catch (InterruptedException interruption) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      rethrowLifecycleCleanupFailure(outcome.get());
    };
  }

  private static void rethrowLifecycleCleanupFailure(Throwable failure) {
    if (failure == null) {
      return;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    throw (RuntimeException) failure;
  }

  private void restartRemoteEngineInBackground(Leelaz engine, int index) {
    if (engine == null || isSetupModeActive()) {
      return;
    }
    Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
    if (attempt == null) {
      return;
    }
    if (!REMOTE_ENGINES_RESTARTING.add(engine)) {
      attempt.close();
      return;
    }
    engine.canCheckAlive = false;
    Thread restartThread =
        new Thread(
            () -> {
              boolean restartStarted = false;
              try {
                if (Lizzie.leelaz != engine
                    || currentEngineNo != index
                    || isEmpty
                    || isSetupModeActive()
                    || !engine.isProcessDead()) {
                  engine.canCheckAlive = true;
                  return;
                }
                attempt.restartClosedEngine(index);
                restartStarted = true;
              } catch (IOException | RuntimeException failure) {
                failure.printStackTrace();
              } finally {
                if (!restartStarted) {
                  attempt.close();
                }
                REMOTE_ENGINES_RESTARTING.remove(engine);
              }
            },
            "lizzie-remote-engine-restart");
    restartThread.setDaemon(true);
    try {
      restartThread.start();
    } catch (RuntimeException failure) {
      attempt.close();
      REMOTE_ENGINES_RESTARTING.remove(engine);
      engine.canCheckAlive = true;
      throw failure;
    }
  }

  void restartUnresponsiveRemoteEngine(Leelaz engine, int index) {
    Object failedIncarnation = engine == null ? null : engine.captureEngineIncarnationFence();
    if (failedIncarnation != null
        && requestEngineGameParticipantRecovery(
                this,
                engine,
                failedIncarnation,
                EngineGameRecoveryCause.REMOTE_DISCONNECT)
            == EngineGameRecoveryDisposition.HANDLED) {
      return;
    }
    if (engine == null
        || isSetupModeActive()
        || engine != Lizzie.leelaz
        || index != currentEngineNo
        || isEmpty
        || !engine.isProcessDead()) {
      return;
    }
    restartRemoteEngineInBackground(engine, index);
  }

  private void restartEngineAutomatically(Leelaz engine, int index) throws IOException {
    if (isSetupModeActive()) return;
    Leelaz.AutomaticRestartAttempt attempt = engine.beginAutomaticEngineRestartAttempt();
    if (attempt == null) {
      return;
    }
    try {
      attempt.restartClosedEngine(index);
    } catch (IOException | RuntimeException failure) {
      attempt.close();
      throw failure;
    }
  }

  public void refreshEngineCatalog() throws JSONException, IOException {
    EngineGameTransaction catalogGame;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      catalogGame =
          activeEngineGameTransaction != null && activeEngineGameTransaction.manager == this
              ? activeEngineGameTransaction
              : null;
    }
    if (catalogGame != null) {
      // Catalog slots are transaction identity. Retire the game before exposing a replacement
      // list; stale workers retain frozen participants and fail their next exact recheck.
      clearEngineGame();
      runAfterEngineGameTransactionRetirement(
          catalogGame,
          () -> {
            try {
              refreshEngineCatalog();
            } catch (JSONException | IOException refreshFailure) {
              refreshFailure.printStackTrace();
            }
          },
          () ->
              new IllegalStateException(
                      "Engine catalog refresh could not be scheduled after game retirement")
                  .printStackTrace());
      return;
    }
    if (engineList == null) {
      updateEngines();
      return;
    }
    ArrayList<EngineData> engineData = loadEngineCatalogSnapshot();
    List<Leelaz> previousEngines = new ArrayList<Leelaz>(engineList);
    boolean[] matched = new boolean[previousEngines.size()];
    List<Leelaz> refreshedEngines = new ArrayList<Leelaz>();
    Leelaz currentMainEngine = Lizzie.leelaz;
    Leelaz currentSecondaryEngine = Lizzie.leelaz2;
    int refreshedCurrentEngineNo = -1;
    int refreshedCurrentEngineNo2 = -1;

    for (int i = 0; i < engineData.size(); i++) {
      EngineData engineDt = engineData.get(i);
      int matchIndex = findMatchingEngine(previousEngines, matched, engineDt, i);
      Leelaz engine =
          matchIndex >= 0 ? previousEngines.get(matchIndex) : createUnstartedEngine(engineDt);
      applySavedEngineMetadata(engine, engineDt, i);
      refreshedEngines.add(engine);
      if (engine == currentMainEngine) refreshedCurrentEngineNo = i;
      if (engine == currentSecondaryEngine) refreshedCurrentEngineNo2 = i;
    }

    if (!isEmpty && currentMainEngine != null && refreshedCurrentEngineNo < 0) {
      updateEngines();
      return;
    }

    int frozenCurrentEngineNo = refreshedCurrentEngineNo;
    int frozenCurrentEngineNo2 = refreshedCurrentEngineNo2;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      Lizzie.runWithEngineAuthorityMutation(
          () -> {
            engineList = refreshedEngines;
            if (frozenCurrentEngineNo >= 0) {
              currentEngineNo = frozenCurrentEngineNo;
              engineNo = frozenCurrentEngineNo;
            }
            currentEngineNo2 = frozenCurrentEngineNo2;
            return null;
          });
    }
    runEngineSwitchUiUpdate(
        () -> {
          refreshEngineSelectionControls(engineData);
          if (LizzieFrame.menu != null) {
            LizzieFrame.menu.updateEngineMenu();
          }
          List<Leelaz> displayedEngines = engineList;
          if (Menu.engineMenu != null
              && !isEmpty
              && currentEngineNo >= 0
              && currentEngineNo < displayedEngines.size()) {
            Menu.engineMenu.setText(
                "["
                    + (currentEngineNo + 1)
                    + "]: "
                    + displayedEngines.get(currentEngineNo).oriEnginename);
          }
        });
  }

  /** Catalog I/O seam; callers may run this off the Swing event thread. */
  protected ArrayList<EngineData> loadEngineCatalogSnapshot() {
    return Utils.getEngineData();
  }

  private int findMatchingEngine(
      List<Leelaz> previousEngines, boolean[] matched, EngineData engineDt, int preferredIndex) {
    if (preferredIndex >= 0
        && preferredIndex < previousEngines.size()
        && !matched[preferredIndex]
        && isSameEngineProcess(previousEngines.get(preferredIndex), engineDt)) {
      matched[preferredIndex] = true;
      return preferredIndex;
    }
    for (int i = 0; i < previousEngines.size(); i++) {
      if (!matched[i] && isSameEngineProcess(previousEngines.get(i), engineDt)) {
        matched[i] = true;
        return i;
      }
    }
    return -1;
  }

  private boolean isSameEngineProcess(Leelaz engine, EngineData engineDt) {
    return engine != null
        && engineDt != null
        && safeEquals(engine.oriEngineCommand, engineDt.commands)
        && engine.oriWidth == engineDt.width
        && engine.oriHeight == engineDt.height
        && engine.useJavaSSH == engineDt.useJavaSSH
        && safeEquals(engine.ip, engineDt.ip)
        && safeEquals(engine.port, engineDt.port)
        && safeEquals(engine.userName, engineDt.userName);
  }

  private boolean safeEquals(String first, String second) {
    if (first == null) return second == null;
    return first.equals(second);
  }

  protected Leelaz createUnstartedEngine(EngineData engineDt) throws JSONException, IOException {
    Leelaz engine = new Leelaz(engineDt.commands);
    applySavedEngineMetadata(engine, engineDt, engineDt.index);
    return engine;
  }

  private void applySavedEngineMetadata(Leelaz engine, EngineData engineDt, int index) {
    engine.preload = engineDt.preload;
    engine.width = engineDt.width;
    engine.height = engineDt.height;
    engine.oriWidth = engineDt.width;
    engine.oriHeight = engineDt.height;
    engine.komi = engineDt.komi;
    engine.orikomi = engineDt.komi;
    engine.useJavaSSH = engineDt.useJavaSSH;
    engine.ip = engineDt.ip;
    engine.port = engineDt.port;
    engine.useKeyGen = engineDt.useKeyGen;
    engine.keyGenPath = engineDt.keyGenPath;
    engine.userName = engineDt.userName;
    engine.password = engineDt.password;
    engine.initialCommand = engineDt.initialCommand;
    engine.gtpConfigurationProtocol = engineDt.gtpConfigurationProtocol;
    engine.gtpConfigurationProfile = copyProfile(engineDt.gtpConfigurationProfile);
    engine.getEngineName(index);
  }

  private static JSONObject copyProfile(JSONObject profile) {
    return profile == null ? null : new JSONObject(profile.toString());
  }

  private void refreshEngineSelectionControls(ArrayList<EngineData> engineData) {
    int j = LizzieFrame.toolbar.enginePkBlack.getItemCount();
    LizzieFrame.toolbar.removeEngineLis();
    for (int i = 0; i < j; i++) {
      LizzieFrame.toolbar.enginePkBlack.removeItemAt(0);
      LizzieFrame.toolbar.enginePkWhite.removeItemAt(0);
    }
    for (int i = 0; i < engineData.size(); i++) {
      EngineData engineDt = engineData.get(i);
      LizzieFrame.toolbar.enginePkBlack.addItem("[" + (i + 1) + "]" + engineDt.name);
      LizzieFrame.toolbar.enginePkWhite.addItem("[" + (i + 1) + "]" + engineDt.name);
    }
    LizzieFrame.toolbar.engineBlackToolbar = 0;
    LizzieFrame.toolbar.engineWhiteToolbar = 0;
    LizzieFrame.toolbar.addEngineLis();
  }

  public boolean killAllEngines() {
    Leelaz currentForegroundEngine = Lizzie.leelaz;
    Leelaz.ExclusiveGtpLifecycleReservation reservation =
        currentForegroundEngine == null
            ? null
            : currentForegroundEngine.beginExclusiveGtpLifecycleReservation();
    if (currentForegroundEngine != null && reservation == null) {
      showForegroundEngineLeaseInUse();
      return false;
    }
    try {
      killAllEnginesUnderReservation();
    } finally {
      if (reservation != null) {
        reservation.close();
      }
    }
    return true;
  }

  private void killAllEnginesUnderReservation() {
    // currentEngineNo = -1;
    for (int i = 0; i < engineList.size(); i++) {
      if (engineList.get(i).isStarted()) {
        try {
          engineList.get(i).forceQuit();
        } catch (Exception e) {
        }
      }
    }
    currentEngineNo2 = -1;
    currentEngineNo = -1;
    isEmpty = true;
    Lizzie.leelaz.notPondering();
    Lizzie.leelaz.isLoaded = true;
    Menu.engineMenu.setText(resourceBundle.getString("Menu.noEngine"));
    Lizzie.frame.invalidateTrackingAnalysis();
    Lizzie.frame.refresh();
  }

  public void forceKillAllEngines() {
    // currentEngineNo = -1;
    for (int i = 0; i < engineList.size(); i++) {
      if (engineList.get(i).isStarted()) {
        try {
          engineList.get(i).forceQuit();
        } catch (Exception e) {
        }
      }
    }
    Lizzie.leelaz.notPondering();
  }

  public void reStartEngine() {
    // currentEngineNo = -1;
    if (rejectForegroundEngineStartDuringSetup(true)) return;
    if (isEmpty || Lizzie.leelaz == null) return;
    Leelaz currentForegroundEngine = Lizzie.leelaz;
    boolean restartPonderIntent = currentForegroundEngine.isPonderingOrWasPonderingBeforeTracking();
    int restartEngineIndex = currentEngineNo;
    if (rejectSameEngineSelection(restartEngineIndex, true)) return;
    Leelaz restartTarget = engineList.get(restartEngineIndex);
    PreparedEngineSwitch preparedSwitch;
    try {
      preparedSwitch = prepareEngineSwitch(restartEngineIndex, true, true);
    } catch (Leelaz.ExactSnapshotRestoreAdmissionException
        | InitialStartupReservationException conflict) {
      showForegroundEngineLeaseInUse();
      return;
    } catch (RuntimeException failure) {
      restartTarget.isLoaded = false;
      showEngineSynchronizationFailure(restartTarget);
      return;
    }
    if (preparedSwitch != null) {
      restartTarget = preparedSwitch.targetEngine;
    }
    if (preparedSwitch == null) {
    EngineLifecycleReservations reservations =
          reservePreparedEngineSwitch(currentForegroundEngine, restartTarget, null);
    if (reservations == null) {
      showForegroundEngineLeaseInUse();
      return;
    }
    if (!attachRestartInteractionGate(reservations)) {
      return;
    }
      shutdownEngineForRestart(restartTarget);
      switchEngineInternal(
          restartEngineIndex,
          true,
          null,
          releaseEngineLifecycleAfterBoardSync(
              currentForegroundEngine,
              restartTarget,
              true,
              true,
              restartPonderIntent,
              reservations,
              null));
      return;
    }
    InitialEngineStartupSynchronization lifecycleSynchronization =
        preparedSwitch.initialStartupSynchronization;
    EngineLifecycleReservations reservations =
        reservePreparedEngineSwitch(currentForegroundEngine, restartTarget, preparedSwitch);
    if (reservations == null) {
      lifecycleSynchronization.close();
      showForegroundEngineLeaseInUse();
      return;
    }
    lifecycleSynchronization.installReservations(reservations);
    if (!lifecycleSynchronization.attachRestartInteractionGate()) {
      showEngineSynchronizationFailure(restartTarget);
      return;
    }
    shutdownEngineForRestart(restartTarget);
    switchEngineInternal(
        restartEngineIndex,
        true,
        preparedSwitch,
        releaseEngineLifecycleAfterBoardSync(
            currentForegroundEngine,
            restartTarget,
            true,
            true,
            restartPonderIntent,
            lifecycleSynchronization::close,
            lifecycleSynchronization.isTrackingFirstWinner(),
            preparedSwitch.lifecycleRestore));
  }

  public void reStartEngine(int index) {
    // currentEngineNo = -1;
    if (rejectForegroundEngineStartDuringSetup(true)) return;
    if (isEmpty || Lizzie.leelaz == null) return;
    if (rejectSameEngineSelection(index, true)) return;
    Leelaz currentForegroundEngine = Lizzie.leelaz;
    boolean restartPonderIntent = currentForegroundEngine.isPonderingOrWasPonderingBeforeTracking();
    Leelaz targetEngine = engineList.get(index);
    PreparedEngineSwitch preparedSwitch;
    try {
      preparedSwitch = prepareEngineSwitch(index, true, true);
    } catch (Leelaz.ExactSnapshotRestoreAdmissionException
        | InitialStartupReservationException conflict) {
      showForegroundEngineLeaseInUse();
      return;
    } catch (RuntimeException failure) {
      targetEngine.isLoaded = false;
      showEngineSynchronizationFailure(targetEngine);
      return;
    }
    if (preparedSwitch != null) {
      targetEngine = preparedSwitch.targetEngine;
    }
    if (preparedSwitch == null) {
    EngineLifecycleReservations reservations =
          reservePreparedEngineSwitch(currentForegroundEngine, targetEngine, null);
    if (reservations == null) {
        showForegroundEngineLeaseInUse();
        return;
      }
    if (!attachRestartInteractionGate(reservations)) {
      return;
    }
      shutdownEngineForRestart(targetEngine);
      switchEngineInternal(
          index,
          true,
          null,
          releaseEngineLifecycleAfterBoardSync(
              currentForegroundEngine,
              targetEngine,
              true,
              true,
              restartPonderIntent,
              reservations,
              null));
      return;
    }
    InitialEngineStartupSynchronization lifecycleSynchronization =
        preparedSwitch.initialStartupSynchronization;
    EngineLifecycleReservations reservations =
        reservePreparedEngineSwitch(currentForegroundEngine, targetEngine, preparedSwitch);
    if (reservations == null) {
      lifecycleSynchronization.close();
      showForegroundEngineLeaseInUse();
      return;
    }
    lifecycleSynchronization.installReservations(reservations);
    if (!lifecycleSynchronization.attachRestartInteractionGate()) {
      showEngineSynchronizationFailure(targetEngine);
      return;
    }
    shutdownEngineForRestart(targetEngine);
    switchEngineInternal(
        index,
        true,
        preparedSwitch,
        releaseEngineLifecycleAfterBoardSync(
            currentForegroundEngine,
            targetEngine,
            true,
            true,
            restartPonderIntent,
            lifecycleSynchronization::close,
            lifecycleSynchronization.isTrackingFirstWinner(),
            preparedSwitch.lifecycleRestore));
  }

  public void reStartEngine2() {
    // currentEngineNo = -1;
    if (Lizzie.leelaz2 == null || currentEngineNo2 < 0 || currentEngineNo2 >= engineList.size())
      return;
    int restartEngineIndex = currentEngineNo2;
    if (rejectSameEngineSelection(restartEngineIndex, false)) return;
    boolean restartPonderIntent =
        Lizzie.leelaz != null && Lizzie.leelaz.isPonderingOrWasPonderingBeforeTracking();
    Leelaz secondaryTarget = engineList.get(restartEngineIndex);
    PreparedEngineSwitch preparedSwitch;
    try {
      preparedSwitch = prepareEngineSwitch(restartEngineIndex, false, true);
    } catch (Leelaz.ExactSnapshotRestoreAdmissionException
        | InitialStartupReservationException conflict) {
      showForegroundEngineLeaseInUse();
      return;
    } catch (RuntimeException failure) {
      secondaryTarget.isLoaded = false;
      showEngineSynchronizationFailure(secondaryTarget);
      return;
    }
    if (preparedSwitch != null) {
      secondaryTarget = preparedSwitch.targetEngine;
    }
    if (preparedSwitch == null) {
      EngineLifecycleReservations reservations =
          reservePreparedEngineSwitch(Lizzie.leelaz, secondaryTarget, null);
      if (reservations == null) {
        showForegroundEngineLeaseInUse();
        return;
      }
      shutdownEngineForRestart(secondaryTarget);
      switchEngineInternal(
          restartEngineIndex,
          false,
          null,
          releaseEngineLifecycleAfterBoardSync(
              Lizzie.leelaz,
              secondaryTarget,
              false,
              true,
              restartPonderIntent,
              reservations,
              null));
      return;
    }
    InitialEngineStartupSynchronization lifecycleSynchronization =
        preparedSwitch.initialStartupSynchronization;
    EngineLifecycleReservations reservations =
        reservePreparedEngineSwitch(Lizzie.leelaz, secondaryTarget, preparedSwitch);
    if (reservations == null) {
      lifecycleSynchronization.close();
      showForegroundEngineLeaseInUse();
      return;
    }
    lifecycleSynchronization.installReservations(reservations);
    shutdownEngineForRestart(secondaryTarget);
    switchEngineInternal(
        restartEngineIndex,
        false,
        preparedSwitch,
        releaseEngineLifecycleAfterBoardSync(
            Lizzie.leelaz,
            secondaryTarget,
            false,
            true,
            restartPonderIntent,
            lifecycleSynchronization::close,
            lifecycleSynchronization.isTrackingFirstWinner(),
            preparedSwitch.lifecycleRestore));
  }

  private void shutdownEngineForRestart(Leelaz engine) {
    try {
      engine.isNormalEnd = true;
      engine.shutdown();
      Thread.sleep(200);
      engine.started = false;
      engine.isLoaded = false;
      if (engine.isLeela0110) {
        engine.leela0110StopPonder();
      }
    } catch (Exception ignored) {
    }
  }

  public void killOtherEngines() {
    for (int i = 0; i < engineList.size(); i++) {
      if (engineList.get(i).isStarted()) {
        if (engineList.get(i) != Lizzie.leelaz)
          try {
            // engineList.get(i).normalQuit();
            engineList.get(i).forceQuit();
          } catch (Exception e) {
          }
      }
    }
    currentEngineNo2 = -1;
  }

  public void killOtherEngines(int engineBlack, int engineWhite) {
    for (int i = 0; i < engineList.size(); i++) {
      if (engineList.get(i).isStarted()) {
        if (i != engineBlack && i != engineWhite) engineList.get(i).normalQuit();
      }
    }
  }

  private boolean killOtherEnginesForTransaction(EngineGameTransaction transaction) {
    List<Leelaz> catalog = new ArrayList<>(transaction.manager.engineList);
    for (Leelaz engine : catalog) {
      if (engine == null
          || engine == transaction.blackEngine
          || engine == transaction.whiteEngine
          || !engine.isStarted()) {
        continue;
      }
      engine.normalQuit();
      if (!isCurrentEngineGameTransaction(transaction)) {
        return false;
      }
    }
    return true;
  }

  public void killThisEngines() {
    Leelaz currentForegroundEngine = Lizzie.leelaz;
    Leelaz.ExclusiveGtpLifecycleReservation reservation =
        currentForegroundEngine == null
            ? null
            : currentForegroundEngine.beginExclusiveGtpLifecycleReservation();
    if (currentForegroundEngine != null && reservation == null) {
      showForegroundEngineLeaseInUse();
      return;
    }
    try {
      if (engineList.get(currentEngineNo).isStarted()) {
        engineList.get(currentEngineNo).forceQuit();
      }
      currentEngineNo = -1;
      isEmpty = true;
      Lizzie.leelaz.isLoaded = true;
      Lizzie.leelaz.notPondering();
      Lizzie.leelaz.clearBestMoves();
      Lizzie.frame.invalidateTrackingAnalysis();
    } finally {
      if (reservation != null) {
        reservation.close();
      }
    }
  }

  public void killThisEngines2() {
    engineList.get(currentEngineNo2).normalQuit();
    currentEngineNo2 = -1;
    Lizzie.leelaz2.notPondering();
    Lizzie.leelaz2.clearBestMoves();
  }

  protected void showForegroundEngineLeaseInUse() {
    Utils.showMsg(Lizzie.resourceBundle.getString("AnalysisSettings.reuseStatus.existing_lease"));
  }

  /**
   * Switch the Engine by index number
   *
   * @param index engine index
   */
  public void startEngineForPk(int index) {
    startEngineForPkSynchronization(index);
  }

  PkEngineSynchronization startEngineForPkSynchronization(int index) {
    PkEngineSynchronization completion = new PkEngineSynchronization();
    if (index < 0 || index >= this.engineList.size()) {
      completion.fail();
      return completion;
    }
    Leelaz newEng = engineList.get(index);
    return startEngineForPkSynchronization(null, index, newEng, completion);
  }

  private PkEngineSynchronization startEngineForPkSynchronization(
      EngineGameTransaction transaction, int index, Leelaz expectedEngine) {
    PkEngineSynchronization completion = new PkEngineSynchronization();
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGameTransactionLocked(transaction)
          || transaction.manager != this
          || !isExactCatalogSlot(this, index, expectedEngine)) {
        completion.fail();
        return completion;
      }
    }
    return startEngineForPkSynchronization(transaction, index, expectedEngine, completion);
  }

  private PkEngineSynchronization startEngineForPkSynchronization(
      EngineGameTransaction transaction,
      int index,
      Leelaz newEng,
      PkEngineSynchronization completion) {
    if (newEng == null) {
      completion.fail();
      return completion;
    }
    newEng.outOfMoveNum = false;
    newEng.blackResignMoveCounts = 0;
    newEng.whiteResignMoveCounts = 0;
    newEng.doublePass = false;
    newEng.resigned = false;
    newEng.isResigning = false;
    newEng.width = Board.boardWidth;
    newEng.height = Board.boardHeight;
    newEng.pkMoveTimeGame = 0;
    Board restoreBoard = Lizzie.board;
    Leelaz proposedRestoreMirror = newEng.resolveLoadSgfMirrorEngine();
    InitialEngineStartupSynchronization lifecycleSynchronization = null;
    try {
      lifecycleSynchronization =
          InitialEngineStartupSynchronization.capturePrepared(
              null, newEng, proposedRestoreMirror, restoreBoard, false, false);
      lifecycleSynchronization.bindEngineGameTransaction(transaction);
      lifecycleSynchronization.acquireReservation();
      lifecycleSynchronization.beginLifecycleCompletionClaim();
      lifecycleSynchronization.completePkSynchronizationAfterClaimRelease(completion);
    } catch (InitialStartupReservationException
        | Leelaz.ExactSnapshotRestoreAdmissionException conflict) {
      if (lifecycleSynchronization != null) {
        lifecycleSynchronization.close();
      }
      showForegroundEngineLeaseInUse();
      completion.fail();
      return completion;
    }
    final InitialEngineStartupSynchronization frozenLifecycleSynchronization =
        lifecycleSynchronization;
    try {
      if (!runEngineGameStartupCommandStep(transaction, newEng::notPondering)
          || !runEngineGameStartupCommandStep(transaction, newEng::clearBestMoves)) {
        lifecycleSynchronization.close();
        completion.fail();
        return completion;
      }
      newEng.komi = lifecycleSynchronization.pendingRoute.rootKomi.floatValue();
      if (!newEng.isStarted()) {
        try {
          startEngineForPkWithTransactionContext(transaction, newEng, index);
        } catch (IOException failure) {
          failPkEngineSynchronization(
              newEng, transaction, newEng.captureEngineIncarnationFence());
          failure.printStackTrace();
          lifecycleSynchronization.close();
          completion.fail();
          return completion;
        }
      } else {
        newEng.canRestoreDymPda = false;
        if (!runEngineGameStartupCommandStep(
                transaction,
                () ->
                    newEng.boardSizeForEngineGame(
                        transaction, newEng.width, newEng.height))
            || !runEngineGameStartupCommandStep(
                transaction, () -> newEng.sendCommand("komi " + newEng.komi))) {
          lifecycleSynchronization.close();
          completion.fail();
          return completion;
        }
        newEng.pkMoveStartTime = System.currentTimeMillis();
      }
      newEng.isResigning = false;
      if (!runEngineGameStartupCommandStep(transaction, newEng::clearWithoutPonder)) {
        lifecycleSynchronization.close();
        completion.fail();
        return completion;
      }
      Object synchronizationIncarnation = newEng.currentEngineIncarnation();
      if (transaction != null
          && !bindEngineGameStartupIncarnation(
              transaction, newEng, synchronizationIncarnation)) {
        lifecycleSynchronization.close();
        completion.fail();
        return completion;
      }
      Runnable syncBoard =
          () -> {
            if (!frozenLifecycleSynchronization.runUntilStableForBoundEngineGame()) {
              frozenLifecycleSynchronization.close();
              completion.fail();
              return;
            }
            frozenLifecycleSynchronization.confirmFinalBoardSynchronization(
                () -> {
                  try {
                    if (newEng.isKataGoPda) {
                      if (!runEngineGameStartupCommandStep(
                          transaction,
                          () -> newEng.sendCommand("dympdacap " + newEng.pdaCap))) {
                        completion.fail();
                        return;
                      }
                    }
                    if (transaction != null
                        && (!isCurrentEngineGameTransaction(transaction)
                            || !newEng.isCurrentLiveEngineIncarnation(
                                synchronizationIncarnation))) {
                      completion.fail();
                      return;
                    }
                    completion.markSuccessful(synchronizationIncarnation);
                  } finally {
                    frozenLifecycleSynchronization.close();
                  }
                },
                detail -> {
                  try {
                    failPkEngineSynchronization(
                        newEng, transaction, synchronizationIncarnation);
                  } finally {
                    frozenLifecycleSynchronization.close();
                  }
                });
          };
      if (transaction == null) {
        Lizzie.frame.clearKataEstimate();
      }
      synchronizePkEngineWhenReady(
          transaction,
          newEng,
          synchronizationIncarnation,
          syncBoard,
          frozenLifecycleSynchronization,
          completion);
    } catch (RuntimeException | Error failure) {
      failPkEngineSynchronization(
          newEng, transaction, newEng.captureEngineIncarnationFence());
      lifecycleSynchronization.close();
      completion.fail();
      return completion;
    }
    return completion;
  }

  private static boolean runEngineGameStartupCommandStep(
      EngineGameTransaction transaction, Runnable command) {
    if (transaction == null) {
      command.run();
      return true;
    }
    return runEngineGameIoStep(transaction, command);
  }

  private static void startEngineForPkWithTransactionContext(
      EngineGameTransaction transaction, Leelaz engine, int index) throws IOException {
    if (transaction == null) {
      engine.startEngine(index);
      return;
    }
    AtomicReference<IOException> startFailure = new AtomicReference<>();
    Leelaz.runWithEngineGameStartupCommandContext(
        transaction,
        () -> {
          try {
            engine.startEngine(index);
          } catch (IOException failure) {
            startFailure.set(failure);
          }
        });
    IOException failure = startFailure.get();
    if (failure != null) {
      throw failure;
    }
    recordEngineGameStartupIncarnation(
        transaction, engine, engine.captureEngineIncarnationFence());
  }

  /**
   * Binds the reader generation published by a participant start to its exact pending match.
   * Callers capture the endpoint identity before entering this selection-locked helper, preserving
   * the canonical selection -> endpoint lock order used by activation.
   */
  static boolean recordEngineGameStartupIncarnation(
      EngineGameTransaction transaction, Leelaz engine, Object startupIncarnation) {
    if (transaction == null || engine == null || startupIncarnation == null) {
      return false;
    }
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGameTransactionLocked(transaction)
          || (transaction.phase != EngineGamePhase.PREPARING
              && transaction.phase != EngineGamePhase.DISPATCHED)) {
        return false;
      }
      if (engine == transaction.blackEngine
          && isExactCatalogSlot(transaction.manager, transaction.blackIndex, engine)) {
        if (transaction.blackStartupIncarnation != null
            && transaction.blackStartupIncarnation != startupIncarnation) {
          return false;
        }
        transaction.blackStartupIncarnation = startupIncarnation;
        return true;
      }
      if (engine == transaction.whiteEngine
          && isExactCatalogSlot(transaction.manager, transaction.whiteIndex, engine)) {
        if (transaction.whiteStartupIncarnation != null
            && transaction.whiteStartupIncarnation != startupIncarnation) {
          return false;
        }
        transaction.whiteStartupIncarnation = startupIncarnation;
        return true;
      }
      return false;
    }
  }

  boolean finishPkEngineSynchronizations(
      PkEngineSynchronization blackSynchronization, PkEngineSynchronization whiteSynchronization) {
    boolean blackReady = blackSynchronization.await();
    boolean whiteReady = whiteSynchronization.await();
    if (blackReady && whiteReady) {
      return true;
    }
    clearEngineGame();
    return false;
  }

  boolean finishPkEngineSynchronizations(
      EngineGameTransaction transaction,
      PkEngineSynchronization blackSynchronization,
      PkEngineSynchronization whiteSynchronization) {
    boolean blackReady =
        blackSynchronization.awaitUntil(
            () -> engineGameDeadlineNanos(transaction),
            () -> isCurrentEngineGameTransaction(transaction));
    if (!blackReady) {
      if (isCurrentEngineGameTransaction(transaction)) {
        failEngineGameTransaction(
            transaction,
            new IllegalStateException("Black engine synchronization failed or timed out"));
      }
      return false;
    }
    boolean whiteReady =
        whiteSynchronization.awaitUntil(
            () -> engineGameDeadlineNanos(transaction),
            () -> isCurrentEngineGameTransaction(transaction));
    if (blackReady && whiteReady) {
      Leelaz blackEngine = transaction.blackEngine;
      Leelaz whiteEngine = transaction.whiteEngine;
      Object blackIncarnation = blackSynchronization.successfulIncarnation();
      Object whiteIncarnation = whiteSynchronization.successfulIncarnation();
      boolean exactParticipantsReady = false;
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (!isCurrentEngineGameTransactionLocked(transaction)) {
          return false;
        }
        if (transaction.phase == EngineGamePhase.DISPATCHED
            && isExactCatalogSlot(
                transaction.manager, transaction.blackIndex, blackEngine)
            && isExactCatalogSlot(
                transaction.manager, transaction.whiteIndex, whiteEngine)
            && (transaction.blackStartupIncarnation == null
                || transaction.blackStartupIncarnation == blackIncarnation)
            && (transaction.whiteStartupIncarnation == null
                || transaction.whiteStartupIncarnation == whiteIncarnation)) {
          AtomicBoolean bothLive = new AtomicBoolean();
          Leelaz.runIfCurrentLiveEngineIncarnations(
              blackEngine,
              blackIncarnation,
              whiteEngine,
              whiteIncarnation,
              () -> bothLive.set(true));
          if (bothLive.get()) {
            transaction.blackIncarnation = blackIncarnation;
            transaction.whiteIncarnation = whiteIncarnation;
            exactParticipantsReady = true;
          }
        }
      }
      if (exactParticipantsReady) {
        return true;
      }
      failEngineGameTransaction(
          transaction,
          new IllegalStateException(
              "Engine-game participants changed before synchronization settled"));
      return false;
    }
    if (isCurrentEngineGameTransaction(transaction)) {
      failEngineGameTransaction(
          transaction,
          new IllegalStateException("White engine synchronization failed or timed out"));
    }
    return false;
  }

  public void clearEngineGame() {
    EngineGameStopClaim stopClaim = invalidateEngineGameTransaction();
    if (stopClaim.transaction != null) {
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (retiringEngineGameTransaction == stopClaim.transaction) {
          stopClaim.transaction.externalTerminalOwner = false;
        }
      }
      finishAutomaticEngineGameRetirementIfQuiescent(stopClaim.transaction);
      }
    if (!stopClaim.wasActive && !stopClaim.wasStarting) {
      return;
    }
    if (stopClaim.transaction == null) {
      restoreUiAfterEngineGameStartAbort(inactiveUiToken(stopClaim), null);
    }
  }

  private EngineGameInactiveUiToken inactiveUiToken(EngineGameStopClaim stopClaim) {
    return stopClaim == null
        ? null
        : new EngineGameInactiveUiToken(
            this, stopClaim.gameInfo, stopClaim.invalidationEpoch, null);
  }

  private void restoreUiAfterEngineGameStartAbort() {
    restoreUiAfterEngineGameStartAbort(null, null);
  }

  private void restoreUiAfterEngineGameStartAbort(Runnable afterRestore) {
    restoreUiAfterEngineGameStartAbort(null, afterRestore);
  }

  private void restoreUiAfterEngineGameStartAbort(
      EngineGameInactiveUiToken token, Runnable afterRestore) {
    AtomicBoolean restoreClaimed = new AtomicBoolean();
    Runnable restore =
        () -> {
          if (!restoreClaimed.compareAndSet(false, true)) {
            return;
          }
          ENGINE_GAME_UI_MUTATION_LOCK.lock();
          try {
            if (token != null) {
              synchronized (ENGINE_SELECTION_STATE_LOCK) {
                if (!isCurrentEngineGameInactiveUiTokenLocked(token)) {
                  return;
                }
              }
            }
            Throwable failure = null;
            failure =
                runEngineGameCleanupStep(
                    failure,
                    () -> {
                      if (Lizzie.board != null) {
                        Lizzie.board.isPkBoard = false;
                      }
                    });
            failure =
                runEngineGameCleanupStep(
                    failure,
                    () -> {
          if (Lizzie.frame != null && Lizzie.frame.isInputRoutingInitialized()) {
            Lizzie.frame.addInput(true);
          }
                    });
            failure =
                runEngineGameCleanupStep(
                    failure,
                    () -> {
          if (LizzieFrame.toolbar != null) {
            LizzieFrame.toolbar.enableDisabelForEngineGame(true);
                        LizzieFrame.toolbar.isPkStop = false;
          }
                    });
            failure =
                runEngineGameCleanupStep(
                    failure,
                    () -> {
          if (Menu.engineMenu != null) {
            Menu.engineMenu.setEnabled(true);
          }
                    });
            if (failure != null) {
              failure.printStackTrace();
            }
          } finally {
            try {
              if (afterRestore != null) {
                afterRestore.run();
              }
            } finally {
              ENGINE_GAME_UI_MUTATION_LOCK.unlock();
            }
          }
        };
    if (SwingUtilities.isEventDispatchThread()) {
      restore.run();
    } else {
      try {
        dispatchEngineGameUi(restore);
      } catch (RuntimeException | Error dispatchFailure) {
        // The logical transaction is already terminal. A broken dispatcher must not prevent the
        // remaining UI/input restoration from being attempted.
        restore.run();
        dispatchFailure.printStackTrace();
      }
    }
  }

  private static boolean isCurrentEngineGameInactiveUiTokenLocked(
      EngineGameInactiveUiToken token) {
    return token != null
        && token.manager != null
        && Lizzie.engineManager == token.manager
        && engineGameInfo == token.gameInfo
        && engineGameTransactionSequence == token.inactiveEpoch
        && activeEngineGameTransaction == null
        && !isEngineGame
        && !isPreEngineGame
        && retiringEngineGameTransaction == token.expectedRetiring;
  }

  protected void dispatchEngineGameUi(Runnable update) {
    SwingUtilities.invokeLater(update);
  }

  private static Throwable runEngineGameCleanupStep(Throwable firstFailure, Runnable cleanup) {
    try {
      cleanup.run();
    } catch (RuntimeException | Error cleanupFailure) {
      if (firstFailure == null) {
        return cleanupFailure;
      }
      if (firstFailure != cleanupFailure) {
        try {
          firstFailure.addSuppressed(cleanupFailure);
        } catch (RuntimeException | Error ignored) {
        }
      }
    }
    return firstFailure;
  }

  /**
   * Captures one exact failed engine-game endpoint before any reader/OpenCL/remote worker can
   * restart it. Registration is selection-atomic; command-state retirement and transaction
   * failure deliberately happen after releasing the selection lock.
   */
  static EngineGameRecoveryDisposition requestEngineGameParticipantRecovery(
      EngineManager manager,
      Leelaz engine,
      Object failedIncarnation,
      EngineGameRecoveryCause cause) {
    if (manager == null || engine == null || failedIncarnation == null) {
      return EngineGameRecoveryDisposition.NOT_ENGINE_GAME_PARTICIPANT;
    }
    EngineGameTransaction transaction;
    EngineGameDeferredRecovery recovery;
    EngineGameOperationLease preparationLease;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (Lizzie.engineManager != manager) {
        return EngineGameRecoveryDisposition.NOT_ENGINE_GAME_PARTICIPANT;
      }
      EngineGameRecoveryBatch recoveryBatch = activeEngineGameRecoveryBatch;
      transaction = participantTransaction(activeEngineGameTransaction, manager, engine);
      if (transaction == null) {
        transaction = participantTransaction(retiringEngineGameTransaction, manager, engine);
      }
      if (transaction == null && recoveryBatch != null) {
        transaction = participantTransaction(recoveryBatch.transaction, manager, engine);
      }
      if (transaction == null) {
        return EngineGameRecoveryDisposition.NOT_ENGINE_GAME_PARTICIPANT;
      }
      int engineIndex =
          transaction.blackEngine == engine ? transaction.blackIndex : transaction.whiteIndex;
      Object expectedIncarnation =
          transaction.blackEngine == engine
              ? (transaction.blackIncarnation != null
                  ? transaction.blackIncarnation
                  : transaction.blackStartupIncarnation)
              : (transaction.whiteIncarnation != null
                  ? transaction.whiteIncarnation
                  : transaction.whiteStartupIncarnation);
      // A reader from a predecessor generation is handled (suppressed) but may neither fail nor
      // recover the transaction that owns a newer incarnation of the same Leelaz object.
      if (expectedIncarnation == null) {
        // PREPARING owns this participant even before its start publishes a reader binding. A
        // callback from a pre-start/predecessor carrier must not fall through to the ordinary
        // auto-restart path and mutate that pending transaction; its startup/readiness owner will
        // publish or fail the exact generation.
        return EngineGameRecoveryDisposition.HANDLED;
      }
      if (expectedIncarnation != failedIncarnation
          || !isExactCatalogSlot(manager, engineIndex, engine)) {
        return EngineGameRecoveryDisposition.HANDLED;
      }
      recovery = null;
      for (EngineGameDeferredRecovery candidate : transaction.deferredRecoveries) {
        if (candidate.engine == engine && candidate.failedIncarnation == failedIncarnation) {
          recovery = candidate;
          break;
        }
      }
      if (recovery == null) {
        recovery =
            new EngineGameDeferredRecovery(
                transaction,
                engineIndex,
                engine,
                failedIncarnation,
                cause == null ? EngineGameRecoveryCause.PROCESS_EXIT : cause);
        transaction.deferredRecoveries.add(recovery);
        if (recoveryBatch != null
            && recoveryBatch.transaction == transaction
            && !recoveryBatch.recoveries.contains(recovery)) {
          recoveryBatch.recoveries.add(recovery);
        }
      } else {
        recovery.mergeCause(cause);
      }
      // Keep a retiring transaction installed until terminal claim plus the exact, non-blocking
      // endpoint reset attempt have both completed. This closes the late-reader race where the
      // physical operation lease reaches zero immediately after recovery registration.
      transaction.operationsInFlight.incrementAndGet();
      preparationLease = new EngineGameOperationLease(transaction);
    }

    String detail =
        recovery.openClNativeExit
            ? "engine-game OpenCL participant exited"
            : recovery.remoteDisconnect
                ? "engine-game remote participant disconnected"
                : "engine-game participant terminated";
    // Claim terminal ownership before touching the endpoint. This makes failure immediately
    // visible even when a physical writer still owns the command stream. The exact endpoint reset
    // is deliberately non-blocking; a writer/reader/watchdog that still owns the failed carrier
    // performs the later physical settlement through its normal retirement path.
    IllegalStateException terminalFailure = new IllegalStateException(detail);
    try {
      claimTerminalEngineGameTransaction(
          transaction, EngineGamePhase.FAILED, terminalFailure, false);
      try {
        engine.retireEngineGameCommandStateForFailedIncarnation(failedIncarnation, detail);
      } catch (RuntimeException | Error resetFailure) {
        terminalFailure.addSuppressed(resetFailure);
        resetFailure.printStackTrace();
      }
    } finally {
      preparationLease.close();
    }
    return EngineGameRecoveryDisposition.HANDLED;
  }

  private static EngineGameTransaction participantTransaction(
      EngineGameTransaction transaction, EngineManager manager, Leelaz engine) {
    return transaction != null
            && transaction.manager == manager
            && (transaction.blackEngine == engine || transaction.whiteEngine == engine)
        ? transaction
        : null;
  }

  public void restartEngineForPk(int index) {
    if (index < 0 || index >= this.engineList.size()) return;
    Leelaz targetEngine = engineList.get(index);
    Object failedIncarnation = targetEngine.captureEngineIncarnationFence();
    if (failedIncarnation != null
        && requestEngineGameParticipantRecovery(
                this,
                targetEngine,
                failedIncarnation,
                targetEngine.classifyEngineGameRecoveryCause(failedIncarnation))
            == EngineGameRecoveryDisposition.HANDLED) {
      return;
    }
    // Participant failure belongs to the exact engine-game transaction even when the board is in
    // setup mode. Only the legacy, foreground restart fallback is subject to the setup-mode gate.
    if (rejectForegroundEngineStartDuringSetup(true)) return;
    Board restoreBoard = Lizzie.board;
    Leelaz proposedRestoreMirror = targetEngine.resolveLoadSgfMirrorEngine();
    InitialEngineStartupSynchronization lifecycleSynchronization = null;
    try {
      lifecycleSynchronization =
          InitialEngineStartupSynchronization.capturePrepared(
              null, targetEngine, proposedRestoreMirror, restoreBoard, false, false);
      lifecycleSynchronization.acquireReservation();
      lifecycleSynchronization.beginLifecycleCompletionClaim();
    } catch (InitialStartupReservationException
        | Leelaz.ExactSnapshotRestoreAdmissionException conflict) {
      if (lifecycleSynchronization != null) {
        lifecycleSynchronization.close();
      }
      showForegroundEngineLeaseInUse();
      return;
    }
    try {
      restartEngineForPkInternal(index, targetEngine, lifecycleSynchronization);
    } catch (RuntimeException failure) {
      targetEngine.isLoaded = false;
      lifecycleSynchronization.close();
      throw failure;
    }
  }

  private void restartEngineForPkInternal(
      int index, Leelaz newEng, InitialEngineStartupSynchronization lifecycleSynchronization) {
    newEng.isLoaded = false;
    newEng.played = false;
    newEng.width = Board.boardWidth;
    newEng.height = Board.boardHeight;
    newEng.komi = lifecycleSynchronization.pendingRoute.rootKomi.floatValue();
    try {
      newEng.startEngine(index);
    } catch (IOException failure) {
      newEng.isLoaded = false;
      failure.printStackTrace();
      lifecycleSynchronization.close();
      return;
    }
    EngineManager.currentEngineNo = index;
    Runnable syncBoard =
        () -> {
          lifecycleSynchronization.runUntilStable(false);
          lifecycleSynchronization.confirmFinalBoardSynchronization(
              () -> {
                try {
                  newEng.nameCmd();
                  newEng.setResponseUpToDate();
                  if (engineGameInfo.isGenmove) {
                    engineGameInfo.applyCapturedTime(newEng, index);
                    if (Lizzie.board.getHistory().isBlacksTurn()) {
                      Lizzie.setPrimaryEngine(engineList.get(engineGameInfo.blackEngineIndex));
                      Lizzie.leelaz.genmoveForPk("b");
                    } else {
                      Lizzie.setPrimaryEngine(engineList.get(engineGameInfo.whiteEngineIndex));
                      Lizzie.leelaz.genmoveForPk("w");
                    }
                  } else if (Lizzie.board.getHistory().isBlacksTurn()) {
                    engineList.get(engineGameInfo.blackEngineIndex).ponder();
                  } else {
                    engineList.get(engineGameInfo.whiteEngineIndex).ponder();
                  }
                } finally {
                  lifecycleSynchronization.close();
                }
              },
              detail -> {
                try {
                  failPkEngineSynchronization(newEng);
                } finally {
                  lifecycleSynchronization.close();
                }
              });
        };
    synchronizePkEngineWhenReady(newEng, syncBoard, lifecycleSynchronization);
  }

  public enum EngineSwitchUiPhase {
    IDLE,
    SWITCHING,
    ACTIVE,
    FAILED
  }

  /**
   * Immutable presentation state for one primary or secondary engine switch.
   *
   * <p>The committed engine index remains separate from the requested target. A slow startup may
   * therefore show the target immediately without claiming that it is already active.
   */
  public static final class EngineSwitchUiSnapshot {
    private final long token;
    private final boolean main;
    private final EngineSwitchUiPhase phase;
    private final int activeIndex;
    private final String activeName;
    private final int targetIndex;
    private final String targetName;
    private final String failureDetail;
    private final int rollbackIndex;
    private final String rollbackName;
    private final Leelaz activeEngineIdentity;
    private final Leelaz targetEngineIdentity;
    private final Leelaz rollbackEngineIdentity;

    private EngineSwitchUiSnapshot(
        long token,
        boolean main,
        EngineSwitchUiPhase phase,
        int activeIndex,
        String activeName,
        int targetIndex,
        String targetName,
        String failureDetail,
        int rollbackIndex,
        String rollbackName,
        Leelaz activeEngineIdentity,
        Leelaz targetEngineIdentity,
        Leelaz rollbackEngineIdentity) {
      this.token = token;
      this.main = main;
      this.phase = phase;
      this.activeIndex = activeIndex;
      this.activeName = activeName == null ? "" : activeName;
      this.targetIndex = targetIndex;
      this.targetName = targetName == null ? "" : targetName;
      this.failureDetail = failureDetail == null ? "" : failureDetail;
      this.rollbackIndex = rollbackIndex;
      this.rollbackName = rollbackName == null ? "" : rollbackName;
      this.activeEngineIdentity = activeEngineIdentity;
      this.targetEngineIdentity = targetEngineIdentity;
      this.rollbackEngineIdentity = rollbackEngineIdentity;
    }

    private static EngineSwitchUiSnapshot idle(boolean main) {
      return new EngineSwitchUiSnapshot(
          0L, main, EngineSwitchUiPhase.IDLE, -1, "", -1, "", "", -1, "", null, null, null);
    }

    public long token() {
      return token;
    }

    public boolean isMain() {
      return main;
    }

    public EngineSwitchUiPhase phase() {
      return phase;
    }

    public int activeIndex() {
      return activeIndex;
    }

    public String activeName() {
      return activeName;
    }

    public int targetIndex() {
      return targetIndex;
    }

    public String targetName() {
      return targetName;
    }

    public String failureDetail() {
      return failureDetail;
    }

    public int previousActiveIndex() {
      return rollbackIndex;
    }

    public String previousActiveName() {
      return rollbackName;
    }
  }

  static final class EngineSwitchUiTracker {
    private long sequence;
    private EngineSwitchUiSnapshot primary = EngineSwitchUiSnapshot.idle(true);
    private EngineSwitchUiSnapshot secondary = EngineSwitchUiSnapshot.idle(false);

    synchronized EngineSwitchUiSnapshot begin(
        boolean main, int activeIndex, String activeName, int targetIndex, String targetName) {
      return begin(main, activeIndex, activeName, null, targetIndex, targetName, null);
    }

    synchronized EngineSwitchUiSnapshot begin(
        boolean main,
        int activeIndex,
        String activeName,
        Leelaz activeEngine,
        int targetIndex,
        String targetName,
        Leelaz targetEngine) {
      EngineSwitchUiSnapshot next =
          new EngineSwitchUiSnapshot(
              ++sequence,
              main,
              EngineSwitchUiPhase.SWITCHING,
              activeIndex,
              activeName,
              targetIndex,
              targetName,
              "",
              activeIndex,
              activeName,
              activeEngine,
              targetEngine,
              activeEngine);
      set(main, next);
      return next;
    }

    synchronized Optional<EngineSwitchUiSnapshot> succeed(
        long token, boolean main, int targetIndex, String targetName) {
      return succeed(token, main, targetIndex, targetName, null);
    }

    synchronized Optional<EngineSwitchUiSnapshot> succeed(
        long token,
        boolean main,
        int targetIndex,
        String targetName,
        Leelaz targetEngine) {
      return succeed(token, main, targetIndex, targetName, targetEngine, () -> {});
    }

    synchronized Optional<EngineSwitchUiSnapshot> succeed(
        long token,
        boolean main,
        int targetIndex,
        String targetName,
        Leelaz targetEngine,
        Runnable terminalStateCommit) {
      EngineSwitchUiSnapshot current = current(main);
      if (current.token != token || current.phase != EngineSwitchUiPhase.SWITCHING) {
        return Optional.empty();
      }
      terminalStateCommit.run();
      EngineSwitchUiSnapshot next =
          new EngineSwitchUiSnapshot(
              token,
              main,
              EngineSwitchUiPhase.ACTIVE,
              targetIndex,
              targetName,
              targetIndex,
              targetName,
              "",
              current.rollbackIndex,
              current.rollbackName,
              targetEngine,
              targetEngine == null ? current.targetEngineIdentity : targetEngine,
              current.rollbackEngineIdentity);
      set(main, next);
      return Optional.of(next);
    }

    synchronized Optional<EngineSwitchUiSnapshot> fail(
        long token, boolean main, String failureDetail) {
      EngineSwitchUiSnapshot current = current(main);
      if (current.token != token
          || (current.phase != EngineSwitchUiPhase.SWITCHING
              && current.phase != EngineSwitchUiPhase.ACTIVE)) {
        return Optional.empty();
      }
      EngineSwitchUiSnapshot next =
          new EngineSwitchUiSnapshot(
              token,
              main,
              EngineSwitchUiPhase.FAILED,
              current.rollbackIndex,
              current.rollbackName,
              current.targetIndex,
              current.targetName,
              failureDetail,
              current.rollbackIndex,
              current.rollbackName,
              current.rollbackEngineIdentity,
              current.targetEngineIdentity,
              current.rollbackEngineIdentity);
      set(main, next);
      return Optional.of(next);
    }

    synchronized Optional<EngineSwitchUiSnapshot> failPending(
        long token, boolean main, String failureDetail) {
      EngineSwitchUiSnapshot current = current(main);
      if (current.token != token || current.phase != EngineSwitchUiPhase.SWITCHING) {
        return Optional.empty();
      }
      return fail(token, main, failureDetail);
    }

    synchronized Optional<EngineSwitchUiSnapshot> abandonPending(long token, boolean main) {
      EngineSwitchUiSnapshot current = current(main);
      if (current.token != token || current.phase != EngineSwitchUiPhase.SWITCHING) {
        return Optional.empty();
      }
      EngineSwitchUiSnapshot abandoned = EngineSwitchUiSnapshot.idle(main);
      set(main, abandoned);
      return Optional.of(abandoned);
    }

    synchronized EngineSwitchUiSnapshot current(boolean main) {
      return main ? primary : secondary;
    }

    synchronized boolean isCurrent(EngineSwitchUiSnapshot snapshot) {
      return snapshot != null && current(snapshot.main) == snapshot;
    }

    synchronized boolean isSwitching(long token, boolean main) {
      EngineSwitchUiSnapshot current = current(main);
      return current.token == token && current.phase == EngineSwitchUiPhase.SWITCHING;
    }

    private void set(boolean main, EngineSwitchUiSnapshot snapshot) {
      if (main) {
        primary = snapshot;
      } else {
        secondary = snapshot;
      }
    }
  }

  private static final class EngineSwitchTransaction {
    private final boolean main;
    private final int targetIndex;
    private final Leelaz previousEngine;
    private final int previousIndex;
    private final Leelaz targetEngine;
    private final Board decisionBoard;
    private final List<Leelaz> decisionEngineCatalog;
    private volatile Object targetEngineIncarnation;
    private volatile boolean targetEngineIncarnationCaptured;
    private volatile Leelaz decisionPrimaryEngine;
    private volatile long decisionPrimaryGeneration = -1L;
    private volatile long uiToken;
    private volatile Board rollbackBoard;
    private volatile Object rollbackLifecycleOwner;
    private volatile boolean rollbackResumePonder;
    private volatile Leelaz rollbackMirrorEngine;
    private volatile Object rollbackMirrorEngineIncarnation;
    private volatile boolean rollbackMirrorEngineIncarnationCaptured;
    private volatile long rollbackMirrorUiToken = -1L;
    private volatile boolean targetInstalled;
    private volatile boolean targetStartFailureCleanupClaimed;
    private volatile boolean synchronizationFailureSuperseded;
    /**
     * Primary authority published by the exact failed-target rollback.  Failure presentation must
     * be fenced to this post-settlement owner rather than the provisional target generation that
     * was required to claim the failure: a successful rollback deliberately advances PRIMARY.
     */
    private volatile Leelaz failurePresentationPrimaryEngine;
    private volatile long failurePresentationPrimaryGeneration = -1L;
    private volatile long failurePresentationUiToken = -1L;
    private final Object completionLock = new Object();
    private boolean completionPublished;
    private Runnable afterCompletion;

    private EngineSwitchTransaction(
        boolean main,
        int targetIndex,
        Leelaz previousEngine,
        int previousIndex,
        Leelaz targetEngine,
        Board decisionBoard,
        List<Leelaz> decisionEngineCatalog) {
      this.main = main;
      this.targetIndex = targetIndex;
      this.previousEngine = previousEngine;
      this.previousIndex = previousIndex;
      this.targetEngine = targetEngine;
      this.decisionBoard = decisionBoard;
      this.decisionEngineCatalog = decisionEngineCatalog;
    }

    private void prepareRollback(
        InitialEngineStartupSynchronization synchronization,
        EngineSwitchUiSnapshot mirrorSnapshot) {
      if (synchronization == null) {
        return;
      }
      rollbackBoard = synchronization.board;
      rollbackLifecycleOwner = synchronization.lifecycleOwner;
      rollbackResumePonder = synchronization.resumePonder;
      rollbackMirrorEngine = synchronization.mirrorEngine;
      if (rollbackMirrorEngine != null) {
        rollbackMirrorEngineIncarnation = rollbackMirrorEngine.captureEngineIncarnationFence();
        rollbackMirrorEngineIncarnationCaptured = true;
      }
      rollbackMirrorUiToken =
          mirrorSnapshot != null
                  && mirrorSnapshot.phase == EngineSwitchUiPhase.ACTIVE
                  && mirrorSnapshot.activeEngineIdentity == rollbackMirrorEngine
              ? mirrorSnapshot.token
              : -1L;
    }

    private void runAfterCompletion(Runnable action) {
      if (action == null) {
        return;
      }
      boolean runNow;
      synchronized (completionLock) {
        runNow = completionPublished;
        if (!runNow) {
          if (afterCompletion != null) {
            throw new IllegalStateException("Engine switch completion action is already installed");
          }
          afterCompletion = action;
        }
      }
      if (runNow) {
        action.run();
      }
    }

    private void publishCompletion() {
      Runnable action;
      synchronized (completionLock) {
        if (completionPublished) {
          return;
        }
        completionPublished = true;
        action = afterCompletion;
        afterCompletion = null;
      }
      if (action != null) {
        action.run();
      }
    }
  }

  private static final class InitialManagerStartupAuthority {
    private final long generation;
    private final Board board;
    private volatile EngineManager manager;

    private InitialManagerStartupAuthority(long generation, Board board) {
      this.generation = generation;
      this.board = board;
    }
  }

  /** Immutable authority captured before a synchronization failure mutates engine/UI state. */
  private static final class EngineSynchronizationFailureFence {
    private final Board board;
    private final List<Leelaz> engineCatalog;
    private final EngineSwitchTransaction transaction;
    private final EngineSwitchUiSnapshot switchingSnapshot;
    private final Leelaz engine;
    private final Object engineIncarnation;
    private final Leelaz primaryEngine;
    private final long primaryGeneration;

    private EngineSynchronizationFailureFence(
        Board board,
        List<Leelaz> engineCatalog,
        EngineSwitchTransaction transaction,
        EngineSwitchUiSnapshot switchingSnapshot,
        Leelaz engine,
        Object engineIncarnation,
        Leelaz primaryEngine,
        long primaryGeneration) {
      this.board = board;
      this.engineCatalog = engineCatalog;
      this.transaction = transaction;
      this.switchingSnapshot = switchingSnapshot;
      this.engine = engine;
      this.engineIncarnation = engineIncarnation;
      this.primaryEngine = primaryEngine;
      this.primaryGeneration = primaryGeneration;
    }
  }

  /**
   * Exact authority for owner-scoped readiness work that is not backed by an engine-switch
   * transaction (for example, startup parser post-actions on the already selected primary).
   */
  private static final class TransactionlessEngineSynchronizationFailureFence {
    private final Board board;
    private final List<Leelaz> engineCatalog;
    private final Leelaz engine;
    private final Object engineIncarnation;
    private final boolean main;
    private final int engineIndex;
    private final Leelaz primaryEngine;
    private final long primaryGeneration;
    private final EngineStartupStatus.Snapshot startupStatus;

    private TransactionlessEngineSynchronizationFailureFence(
        Board board,
        List<Leelaz> engineCatalog,
        Leelaz engine,
        Object engineIncarnation,
        boolean main,
        int engineIndex,
        Leelaz primaryEngine,
        long primaryGeneration,
        EngineStartupStatus.Snapshot startupStatus) {
      this.board = board;
      this.engineCatalog = engineCatalog;
      this.engine = engine;
      this.engineIncarnation = engineIncarnation;
      this.main = main;
      this.engineIndex = engineIndex;
      this.primaryEngine = primaryEngine;
      this.primaryGeneration = primaryGeneration;
      this.startupStatus = startupStatus;
    }
  }

  private static final class EngineSynchronizationFailurePresentationLease
      implements AutoCloseable {
    private final Lizzie.EngineAuthorityPresentationLease authorityLease;
    private final Leelaz.EngineIncarnationLease incarnationLease;

    private EngineSynchronizationFailurePresentationLease(
        Lizzie.EngineAuthorityPresentationLease authorityLease,
        Leelaz.EngineIncarnationLease incarnationLease) {
      this.authorityLease = authorityLease;
      this.incarnationLease = incarnationLease;
    }

    @Override
    public void close() {
      Throwable failure = null;
      failure = runLifecycleCleanupStep(failure, incarnationLease::close);
      failure = runLifecycleCleanupStep(failure, authorityLease::close);
      rethrowLifecycleCleanupFailure(failure);
    }
  }

  /** Post-settlement PRIMARY authority for one exact failed switch UI token. */
  private static final class EngineSynchronizationFailurePresentationAuthority {
    private final EngineSwitchTransaction transaction;

    private EngineSynchronizationFailurePresentationAuthority(EngineSwitchTransaction transaction) {
      this.transaction = transaction;
    }
  }

  private static final class InitialEngineStartupCandidate {
    private final int index;
    private final Leelaz engine;
    private final Board board;
    private final boolean boardShapeChanges;

    private InitialEngineStartupCandidate(
        int index, Leelaz engine, Board board, boolean boardShapeChanges) {
      this.index = index;
      this.engine = engine;
      this.board = board;
      this.boardShapeChanges = boardShapeChanges;
    }
  }

  private static final class FailedRollbackRecovery {
    private final EngineSwitchUiSnapshot failedSnapshot;
    private final Leelaz engine;
    private final int engineIndex;
    private final Board board;
    private final Object lifecycleOwner;
    private final boolean resumePonder;
    private final EngineSwitchTransaction failedTransaction;
    private final AtomicReference<Object> analysisOutputBinding = new AtomicReference<>();

    private FailedRollbackRecovery(
        EngineSwitchUiSnapshot failedSnapshot,
        Leelaz engine,
        int engineIndex,
        Board board,
        Object lifecycleOwner,
        boolean resumePonder,
        EngineSwitchTransaction failedTransaction) {
      this.failedSnapshot = failedSnapshot;
      this.engine = engine;
      this.engineIndex = engineIndex;
      this.board = board;
      this.lifecycleOwner = lifecycleOwner;
      this.resumePonder = resumePonder;
      this.failedTransaction = failedTransaction;
    }

    private boolean claimAnalysisOutputBinding(Object binding) {
      if (binding == null) {
        return false;
      }
      Object current = analysisOutputBinding.get();
      return current == binding
          || (current == null
              && analysisOutputBinding.compareAndSet(null, binding));
    }
  }

  private static final class FailedEngineQuarantine {
    private final Leelaz engine;
    private final Object incarnation;
    private final long switchToken;

    private FailedEngineQuarantine(Leelaz engine, Object incarnation, long switchToken) {
      this.engine = engine;
      this.incarnation = incarnation;
      this.switchToken = switchToken;
    }
  }

  @FunctionalInterface
  interface FailedEngineStopThreadFactory {
    Thread create(Runnable stop, String name);
  }

  static void setFailedEngineStopThreadFactoryForTest(FailedEngineStopThreadFactory factory) {
    failedEngineStopThreadFactory = factory == null ? Thread::new : factory;
  }

  /** One lock-consistent view used by every failed-switch status/UI correction. */
  private static final class EngineSelectionAvailability {
    private final Leelaz primary;
    private final Leelaz secondary;
    private final int primaryIndex;
    private final boolean primaryAvailable;
    private final boolean secondaryAvailable;
    private final boolean empty;
    private final boolean engineGame;

    private EngineSelectionAvailability(
        Leelaz primary,
        Leelaz secondary,
        int primaryIndex,
        boolean primaryAvailable,
        boolean secondaryAvailable,
        boolean empty,
        boolean engineGame) {
      this.primary = primary;
      this.secondary = secondary;
      this.primaryIndex = primaryIndex;
      this.primaryAvailable = primaryAvailable;
      this.secondaryAvailable = secondaryAvailable;
      this.empty = empty;
      this.engineGame = engineGame;
    }
  }

  private EngineSwitchTransaction tryBeginEngineSwitchTransaction(
      boolean main,
      int targetIndex,
      Leelaz previousEngine,
      int previousIndex,
      Leelaz targetEngine) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      return Lizzie.runWithEngineAuthorityMutation(
          () -> {
            if (failedRollbackRecovery.get() != null
                || (targetEngine != null && FAILED_ENGINE_QUARANTINES.containsKey(targetEngine))) {
              return null;
            }
            EngineSwitchTransaction transaction =
                new EngineSwitchTransaction(
                    main,
                    targetIndex,
                    previousEngine,
                    previousIndex,
                    targetEngine,
                    Lizzie.board,
                    engineList);
            return engineSwitchTransaction.compareAndSet(null, transaction) ? transaction : null;
          });
    }
  }

  private EngineSwitchTransaction tryBeginEngineSwitchTransaction(
      boolean main, int targetIndex, Leelaz targetEngine) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      return tryBeginEngineSwitchTransaction(
          main,
          targetIndex,
          main ? Lizzie.leelaz : Lizzie.leelaz2,
          main ? currentEngineNo : currentEngineNo2,
          targetEngine);
    }
  }

  private boolean isCurrentEngineSwitchTransaction(EngineSwitchTransaction transaction) {
    return transaction != null && engineSwitchTransaction.get() == transaction;
  }

  private void finishEngineSwitchTransaction(EngineSwitchTransaction transaction) {
    if (transaction != null) {
      engineSwitchTransaction.compareAndSet(transaction, null);
      transaction.publishCompletion();
    }
  }

  public EngineSwitchUiSnapshot engineSwitchUiSnapshot(boolean main) {
    return engineSwitchUiTracker.current(main);
  }

  public boolean isSnapshotActiveEngineAvailable(EngineSwitchUiSnapshot snapshot) {
    if (snapshot == null || snapshot.activeIndex < 0) {
      return false;
    }
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      Leelaz engine = snapshot.activeEngineIdentity;
      Leelaz selected = snapshot.main ? Lizzie.leelaz : Lizzie.leelaz2;
      return engine != null
          && engine == selected
          && engine.started
          && engine.isLoaded
          && snapshot.activeIndex == (snapshot.main ? currentEngineNo : currentEngineNo2);
    }
  }

  public boolean isEngineSwitchActive(int index, boolean main) {
    EngineSwitchUiSnapshot snapshot = engineSwitchUiTracker.current(main);
    return snapshot.phase == EngineSwitchUiPhase.ACTIVE
        && snapshot.activeIndex == index
        && isSnapshotActiveEngineAvailable(snapshot);
  }

  private long beginEngineSwitchUi(int index, boolean isMain, Leelaz targetEngine) {
    return beginEngineSwitchUiSnapshot(index, isMain, targetEngine).token;
  }

  private EngineSwitchUiSnapshot beginEngineSwitchUiSnapshot(
      int index, boolean isMain, Leelaz targetEngine) {
    return beginEngineSwitchUiSnapshot(
        index, isMain, isMain ? Lizzie.leelaz : Lizzie.leelaz2, targetEngine);
  }

  private EngineSwitchUiSnapshot beginEngineSwitchUiSnapshot(
      int index, boolean isMain, Leelaz activeEngine, Leelaz targetEngine) {
    int activeIndex;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      activeIndex = isMain ? currentEngineNo : currentEngineNo2;
    }
    return beginEngineSwitchUiSnapshot(index, isMain, activeIndex, activeEngine, targetEngine);
  }

  private EngineSwitchUiSnapshot beginEngineSwitchUiSnapshot(
      int index, boolean isMain, int activeIndex, Leelaz activeEngine, Leelaz targetEngine) {
    EngineSwitchUiSnapshot snapshot =
        engineSwitchUiTracker.begin(
            isMain,
            activeIndex,
            engineDisplayNameWithoutCatalogLookup(activeEngine, activeIndex),
            activeEngine,
            index,
            engineDisplayNameWithoutCatalogLookup(targetEngine, index),
            targetEngine);
    publishEngineSwitchUiState(snapshot);
    return snapshot;
  }

  private void completeEngineSwitchUi(long token, int index, boolean isMain, Leelaz targetEngine) {
    if (!isCommittedEngineSelection(isMain, targetEngine, index)) {
      failEngineSwitchUi(
          token, isMain, "Engine selection was not committed at the final synchronization fence");
      return;
    }
    engineSwitchUiTracker
        .succeed(token, isMain, index, engineDisplayName(targetEngine, index), targetEngine)
        .ifPresent(this::publishEngineSwitchUiState);
  }

  private static boolean isCommittedEngineSelection(
      boolean main, Leelaz expectedEngine, int expectedIndex) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (main) {
        return expectedEngine != null
            && Lizzie.leelaz == expectedEngine
            && currentEngineNo == expectedIndex
            && !isEmpty;
      }
      return expectedEngine != null
          && Lizzie.leelaz2 == expectedEngine
          && currentEngineNo2 == expectedIndex;
    }
  }

  private void completeEngineSwitchUi(PreparedLifecycleRestore lifecycleRestore) {
    if (lifecycleRestore == null || lifecycleRestore.engineSwitchUiToken <= 0L) {
      return;
    }
    completeEngineSwitchUi(
        lifecycleRestore.engineSwitchUiToken,
        lifecycleRestore.engineSwitchUiIndex,
        lifecycleRestore.engineSwitchUiMain,
        lifecycleRestore.targetEngine);
  }

  private void failEngineSwitchUi(long token, boolean isMain) {
    failEngineSwitchUi(token, isMain, engineFailedText());
  }

  private void failEngineSwitchUi(long token, boolean isMain, String detail) {
    engineSwitchUiTracker.fail(token, isMain, detail).ifPresent(this::settleFailedEngineSwitchUi);
  }

  private void failPendingEngineSwitchUi(long token, boolean isMain) {
    engineSwitchUiTracker
        .failPending(token, isMain, engineFailedText())
        .ifPresent(this::settleFailedEngineSwitchUi);
  }

  private void settleFailedEngineSwitchUi(EngineSwitchUiSnapshot failed) {
    EngineSwitchTransaction failedTransaction = installedTransactionFor(failed);
    AtomicReference<FailedTargetSettlement> targetSettlement = new AtomicReference<>();
    runFailedSwitchCleanup(
        () ->
            targetSettlement.set(
                claimAndRollbackFailedTarget(failed, failedTransaction)));
    FailedTargetSettlement settledTarget = targetSettlement.get();
    boolean failedTargetWasSelected = settledTarget != null;
    FailedRollbackRecovery rollbackRecovery =
        failed.main && failedTargetWasSelected
            ? beginFailedRollbackRecovery(failed, failedTransaction)
            : null;
    EngineSelectionAvailability availability = captureEngineSelectionAvailability();
    runFailedSwitchCleanup(
        () -> correctEngineStartupStatusAfterFailedSwitch(failed.failureDetail, availability));
    runFailedSwitchCleanup(() -> correctPdaAfterFailedSwitch(availability));
    publishEngineSwitchUiState(failed);
    dispatchFailedRollbackRecovery(rollbackRecovery);
    dispatchFailedEngineStop(
        settledTarget == null ? null : settledTarget.runtimeStop, failed.token);
  }

  private EngineSwitchTransaction installedTransactionFor(EngineSwitchUiSnapshot failed) {
    EngineSwitchTransaction transaction = engineSwitchTransaction.get();
    if (failed == null || transaction == null || !transaction.targetInstalled) {
      return null;
    }
    boolean directTarget =
        transaction.uiToken == failed.token
            && transaction.main == failed.main
            && transaction.targetEngine == failed.targetEngineIdentity;
    boolean primaryMirror =
        !transaction.main
            && failed.main
            && transaction.rollbackMirrorEngine == failed.targetEngineIdentity
            && transaction.rollbackMirrorUiToken == failed.token;
    return directTarget || primaryMirror ? transaction : null;
  }

  private FailedTargetSettlement claimAndRollbackFailedTarget(
      EngineSwitchUiSnapshot failed, EngineSwitchTransaction transaction) {
    if (failed == null || failed.targetEngineIdentity == null || transaction == null) {
      return null;
    }
    boolean directTarget =
        transaction.targetEngine == failed.targetEngineIdentity
            && transaction.main == failed.main;
    Object expectedIncarnation =
        directTarget
            ? transaction.targetEngineIncarnation
            : transaction.rollbackMirrorEngineIncarnation;
    boolean incarnationCaptured =
        directTarget
            ? transaction.targetEngineIncarnationCaptured
            : transaction.rollbackMirrorEngineIncarnationCaptured;
    int expectedIndex = directTarget ? transaction.targetIndex : failed.targetIndex;
    if (!incarnationCaptured) {
      return null;
    }
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (Lizzie.engineManager != this
          || failed.targetEngineIdentity != (failed.main ? Lizzie.leelaz : Lizzie.leelaz2)
          || !isExactCatalogSlot(this, expectedIndex, failed.targetEngineIdentity)) {
        return null;
      }
      Leelaz target = failed.targetEngineIdentity;
      AtomicReference<FailedTargetSettlement> settlement = new AtomicReference<>();
      Runnable exactRollback =
          () -> {
            Runnable runtimeStop;
            if (directTarget && transaction.targetStartFailureCleanupClaimed) {
              // A start-attempt failure capability already owns every resource published by this
              // exact invocation.  Roll back the provisional selection here, but do not create a
              // second process/reader shutdown owner for the same incarnation.
              runtimeStop = null;
            } else if (expectedIncarnation == null) {
              target.isLoaded = false;
              runtimeStop = null;
            } else {
              runtimeStop =
                  quarantineUnavailableEngineLocked(
                      target, expectedIncarnation, failed.token);
            }
            rollbackEngineSelectionAfterFailedSwitch(failed);
            captureFailurePresentationPrimaryAuthorityLocked(
                transaction, failed.token);
            settlement.set(new FailedTargetSettlement(runtimeStop));
          };
      if (failed.main) {
        long primaryGeneration = Lizzie.capturePrimaryEngineGeneration(target);
        if (primaryGeneration < 0L
            || !Lizzie.runIfPrimaryEngine(
                target,
                primaryGeneration,
                () ->
                    target.runIfEngineIncarnationFenceUnchanged(
                        expectedIncarnation, exactRollback))) {
          return null;
        }
      } else {
        target.runIfEngineIncarnationFenceUnchanged(
            expectedIncarnation, exactRollback);
      }
      return settlement.get();
    }
  }

  /** Caller holds selection state after publishing the PRIMARY owner represented by this failure. */
  private static void captureFailurePresentationPrimaryAuthorityLocked(
      EngineSwitchTransaction transaction, long uiToken) {
    if (transaction == null || transaction.uiToken != uiToken) {
      return;
    }
    Leelaz presentationPrimary = Lizzie.leelaz;
    long presentationPrimaryGeneration =
        Lizzie.capturePrimaryEngineGeneration(presentationPrimary);
    if (presentationPrimaryGeneration < 0L) {
      return;
    }
    transaction.failurePresentationPrimaryEngine = presentationPrimary;
    transaction.failurePresentationPrimaryGeneration = presentationPrimaryGeneration;
    transaction.failurePresentationUiToken = uiToken;
  }

  private static final class FailedTargetSettlement {
    private final Runnable runtimeStop;

    private FailedTargetSettlement(Runnable runtimeStop) {
      this.runtimeStop = runtimeStop;
    }
  }

  private static void runFailedSwitchCleanup(Runnable cleanup) {
    if (cleanup == null) {
      return;
    }
    try {
      cleanup.run();
    } catch (RuntimeException | Error cleanupFailure) {
      cleanupFailure.printStackTrace();
    }
  }

  /** Caller holds {@link #ENGINE_SELECTION_STATE_LOCK}; returned shutdown remains incarnation-safe. */
  private static Runnable quarantineUnavailableEngineLocked(Leelaz target, long token) {
    if (target == null) {
      return null;
    }
    return quarantineUnavailableEngineLocked(target, target.currentEngineIncarnation(), token);
  }

  /** Caller holds {@link #ENGINE_SELECTION_STATE_LOCK}; rejects a stale runtime capability. */
  private static Runnable quarantineUnavailableEngineLocked(
      Leelaz target, Object expectedIncarnation, long token) {
    if (target == null
        || expectedIncarnation == null
        || FAILED_ENGINE_QUARANTINES.containsKey(target)) {
      return null;
    }
    // Caller holds selection state; acquire the engine arbitration lock second so a rebind cannot
    // slip between the incarnation check and the unavailable publication.
    if (!target.markUnavailableIfCurrentIncarnation(expectedIncarnation)) {
      return null;
    }
    FailedEngineQuarantine quarantine =
        new FailedEngineQuarantine(target, expectedIncarnation, token);
    FAILED_ENGINE_QUARANTINES.put(target, quarantine);
    return new FailedEngineStopCapability(quarantine);
  }

  private static final class FailedEngineStopCapability implements Runnable {
    private final FailedEngineQuarantine quarantine;
    private final AtomicBoolean settled = new AtomicBoolean();

    private FailedEngineStopCapability(FailedEngineQuarantine quarantine) {
      this.quarantine = quarantine;
    }

    @Override
    public void run() {
      if (!settled.compareAndSet(false, true)) {
        return;
      }
      Leelaz target = quarantine.engine;
      try {
        Leelaz.ExactNormalQuitClaim shutdownClaim;
        synchronized (ENGINE_SELECTION_STATE_LOCK) {
          if (FAILED_ENGINE_QUARANTINES.get(target) != quarantine
              || target == Lizzie.leelaz
              || target == Lizzie.leelaz2
              || target.isLoaded) {
            return;
          }
          // Selection publication and exact-incarnation shutdown claim share one short critical
          // section. Cleanup happens below after releasing the global selection lock.
          shutdownClaim = target.claimFailedRuntimeQuitIfCurrentIncarnation(quarantine.incarnation);
        }
        if (shutdownClaim != null) {
          try {
            shutdownClaim.finish();
          } catch (RuntimeException | Error cleanupFailure) {
            cleanupFailure.printStackTrace();
          }
        }
      } finally {
        releaseQuarantine();
      }
    }

    private void schedulingFailed(Throwable failure) {
      if (!settled.compareAndSet(false, true)) {
        return;
      }
      Leelaz.ExactNormalQuitClaim shutdownClaim = null;
      try {
        synchronized (ENGINE_SELECTION_STATE_LOCK) {
          Leelaz target = quarantine.engine;
          if (FAILED_ENGINE_QUARANTINES.get(target) == quarantine
              && target != Lizzie.leelaz
              && target != Lizzie.leelaz2
              && !target.isLoaded) {
            shutdownClaim =
                target.claimFailedRuntimeQuitIfCurrentIncarnation(quarantine.incarnation);
          }
        }
        if (shutdownClaim != null) {
          shutdownClaim.finish();
        }
      } catch (RuntimeException | Error cleanupFailure) {
        if (cleanupFailure != failure) {
          try {
            failure.addSuppressed(cleanupFailure);
          } catch (RuntimeException | Error ignored) {
            // Retain the scheduling failure if suppression itself is unavailable.
          }
        }
      } finally {
        releaseQuarantine();
      }
    }

    private void releaseQuarantine() {
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (FAILED_ENGINE_QUARANTINES.get(quarantine.engine) == quarantine) {
          FAILED_ENGINE_QUARANTINES.remove(quarantine.engine);
        }
      }
    }
  }

  static void publishStoppedEngineIconIfCurrent(Leelaz engine, Object expectedIncarnation) {
    if (engine == null || expectedIncarnation == null) {
      return;
    }
    EngineManager expectedManager;
    boolean main;
    int expectedIndex;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      expectedManager = Lizzie.engineManager;
      if (expectedManager == null) {
        return;
      }
      if (engine == Lizzie.leelaz) {
        main = true;
        expectedIndex = currentEngineNo;
      } else if (engine == Lizzie.leelaz2) {
        main = false;
        expectedIndex = currentEngineNo2;
      } else {
        return;
      }
      if (!isExactCatalogSlot(expectedManager, expectedIndex, engine)) {
        return;
      }
    }

    Runnable update =
        () -> {
          synchronized (ENGINE_SELECTION_STATE_LOCK) {
            if (Lizzie.engineManager != expectedManager
                || engine != (main ? Lizzie.leelaz : Lizzie.leelaz2)
                || expectedIndex != (main ? currentEngineNo : currentEngineNo2)
                || !isExactCatalogSlot(expectedManager, expectedIndex, engine)) {
              return;
            }
            // Lock order is selection -> engine arbitration. Catalog/owner publication never
            // acquires these locks in the reverse order. Holding both across the icon mutation
            // prevents a same-object rebind from publishing its running icon between the final
            // incarnation check and this stale stopped-icon write.
            engine.runIfCurrentEngineIncarnation(
                expectedIncarnation,
                () -> {
                  Menu currentMenu = LizzieFrame.menu;
                  if (currentMenu != null) {
                    if (main) {
                      currentMenu.changeEngineIcon(expectedIndex, 0);
                    } else {
                      currentMenu.changeEngineIcon2(expectedIndex, 0);
                    }
                  }
                });
          }
        };
    if (SwingUtilities.isEventDispatchThread()) {
      update.run();
    } else {
      SwingUtilities.invokeLater(update);
    }
  }

  static void publishStartedEngineIconIfCurrent(Leelaz engine, Object expectedIncarnation) {
    publishRunningEngineIconIfCurrent(engine, expectedIncarnation, 1);
  }

  static void publishReadyEngineIconIfCurrent(Leelaz engine, Object expectedIncarnation) {
    publishRunningEngineIconIfCurrent(engine, expectedIncarnation, 2);
  }

  private static void publishRunningEngineIconIfCurrent(
      Leelaz engine, Object expectedIncarnation, int iconMode) {
    if (engine == null
        || expectedIncarnation == null
        || !engine.allowsGlobalEnginePresentation(expectedIncarnation)) {
      return;
    }
    EngineManager expectedManager;
    boolean main;
    int expectedIndex;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      expectedManager = Lizzie.engineManager;
      if (expectedManager == null) {
        return;
      }
      if (engine == Lizzie.leelaz) {
        main = true;
        expectedIndex = currentEngineNo;
      } else if (engine == Lizzie.leelaz2) {
        main = false;
        expectedIndex = currentEngineNo2;
      } else {
        return;
      }
      if (!isExactCatalogSlot(expectedManager, expectedIndex, engine)) {
        return;
      }
      if (iconMode == 2
          && !engine.runIfCurrentParserReadyPresentationIncarnation(
              expectedIncarnation, () -> {})) {
        return;
      }
    }
    Runnable update =
        () -> {
          synchronized (ENGINE_SELECTION_STATE_LOCK) {
            if (Lizzie.engineManager != expectedManager
                || engine != (main ? Lizzie.leelaz : Lizzie.leelaz2)
                || expectedIndex != (main ? currentEngineNo : currentEngineNo2)
                || !isExactCatalogSlot(expectedManager, expectedIndex, engine)) {
              return;
            }
            Runnable iconMutation =
                () -> {
                  Menu currentMenu = LizzieFrame.menu;
                  if (currentMenu != null) {
                    if (main) {
                      currentMenu.changeEngineIcon(expectedIndex, iconMode);
                    } else {
                      currentMenu.changeEngineIcon2(expectedIndex, iconMode);
                    }
                  }
                };
            if (iconMode == 2) {
              engine.runIfCurrentParserReadyPresentationIncarnation(
                  expectedIncarnation, iconMutation);
            } else {
              engine.runIfCurrentEngineIncarnation(expectedIncarnation, iconMutation);
            }
          }
        };
    if (SwingUtilities.isEventDispatchThread()) {
      update.run();
    } else {
      SwingUtilities.invokeLater(update);
    }
  }

  private static boolean isExactCatalogSlot(
      EngineManager manager, int index, Leelaz expectedEngine) {
    return manager != null
        && manager.engineList != null
        && index >= 0
        && index < manager.engineList.size()
        && manager.engineList.get(index) == expectedEngine;
  }

  static EngineRuntimeUiFence captureEngineRuntimeUiFence(
      Leelaz engine, Object expectedIncarnation) {
    if (engine == null || expectedIncarnation == null) {
      return null;
    }
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      EngineManager manager = Lizzie.engineManager;
      if (manager == null) {
        return null;
      }
      boolean main;
      int index;
      long primaryGeneration = -1L;
      if (engine == Lizzie.leelaz) {
        main = true;
        index = currentEngineNo;
        primaryGeneration = Lizzie.capturePrimaryEngineGeneration(engine);
        if (primaryGeneration < 0L) {
          return null;
        }
      } else if (engine == Lizzie.leelaz2) {
        main = false;
        index = currentEngineNo2;
      } else {
        return null;
      }
      if (!isExactCatalogSlot(manager, index, engine)) {
        return null;
      }
      return new EngineRuntimeUiFence(
          manager, engine, expectedIncarnation, main, index, primaryGeneration);
    }
  }

  static final class EngineRuntimeUiFence {
    private final EngineManager manager;
    private final Leelaz engine;
    private final Object expectedIncarnation;
    private final boolean main;
    private final int index;
    private final long primaryGeneration;

    private EngineRuntimeUiFence(
        EngineManager manager,
        Leelaz engine,
        Object expectedIncarnation,
        boolean main,
        int index,
        long primaryGeneration) {
      this.manager = manager;
      this.engine = engine;
      this.expectedIncarnation = expectedIncarnation;
      this.main = main;
      this.index = index;
      this.primaryGeneration = primaryGeneration;
    }

    boolean publishTerminalDiagnosticIfCurrent(String diagnostic) {
      if (diagnostic == null) {
        return false;
      }
      AtomicReference<Leelaz.EngineRuntimeUiLease> presentationLease = new AtomicReference<>();
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (Lizzie.engineManager != manager
            || engine != (main ? Lizzie.leelaz : Lizzie.leelaz2)
            || index != (main ? currentEngineNo : currentEngineNo2)
            || !isExactCatalogSlot(manager, index, engine)) {
          return false;
        }
        if (main) {
          Lizzie.runIfPrimaryEngine(
              engine,
              primaryGeneration,
              () ->
                  presentationLease.set(
                      engine.claimEngineRuntimeUiLeaseIfCurrent(expectedIncarnation)));
        } else {
          presentationLease.set(engine.claimEngineRuntimeUiLeaseIfCurrent(expectedIncarnation));
        }
      }
      Leelaz.EngineRuntimeUiLease lease = presentationLease.get();
      if (lease == null) {
        return false;
      }
      try {
        engine.tryToDignosticForTerminalReader(
            diagnostic, main, primaryGeneration, expectedIncarnation);
        return true;
      } finally {
        lease.close();
      }
    }
  }

  private static Runnable quarantineStaleInitialEngineIncarnation(
      Leelaz engine, Object expectedIncarnation, long token) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (engine == null || engine == Lizzie.leelaz || engine == Lizzie.leelaz2) {
        return null;
      }
      return quarantineUnavailableEngineLocked(engine, expectedIncarnation, token);
    }
  }

  private static void dispatchFailedEngineStop(Runnable stop, long token) {
    if (stop == null) {
      return;
    }
    // A reader/fence callback is just as latency-sensitive as Swing's EDT: transaction completion
    // and rollback recovery may be waiting for this callback to return. Always isolate process
    // shutdown because normalQuit can block on reader/process teardown for an unbounded interval.
    try {
      Thread cleanup =
          failedEngineStopThreadFactory.create(stop, "lizzie-failed-engine-stop-" + token);
      cleanup.setDaemon(true);
      cleanup.start();
    } catch (RuntimeException | Error schedulingFailure) {
      if (stop instanceof FailedEngineStopCapability) {
        ((FailedEngineStopCapability) stop).schedulingFailed(schedulingFailure);
      }
      schedulingFailure.printStackTrace();
    }
  }

  private static EngineSelectionAvailability captureEngineSelectionAvailability() {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      Leelaz primary = Lizzie.leelaz;
      Leelaz secondary = Lizzie.leelaz2;
      return new EngineSelectionAvailability(
          primary,
          secondary,
          currentEngineNo,
          primary != null && primary.started && primary.isLoaded,
          secondary != null && secondary.started && secondary.isLoaded,
          isEmpty,
          isEngineGame);
    }
  }

  private void correctEngineStartupStatusAfterFailedSwitch(String detail) {
    correctEngineStartupStatusAfterFailedSwitch(detail, captureEngineSelectionAvailability());
  }

  private void correctEngineStartupStatusAfterFailedSwitch(
      String detail, EngineSelectionAvailability availability) {
    if (availability != null
        && !availability.empty
        && availability.primaryIndex >= 0
        && availability.primaryAvailable) {
      Lizzie.engineStartupStatus.ready();
      return;
    }
    Lizzie.engineStartupStatus.failed(
        "EngineStartup.failed",
        "AI failed to start - click to repair",
        detail == null ? engineFailedText() : detail);
  }

  private void correctPdaAfterFailedSwitch() {
    correctPdaAfterFailedSwitch(captureEngineSelectionAvailability());
  }

  private void correctPdaAfterFailedSwitch(EngineSelectionAvailability availability) {
    if (availability == null) {
      return;
    }
    Leelaz active;
    boolean show;
    if (availability.engineGame) {
      active =
          availability.primaryAvailable
              ? availability.primary
              : availability.secondaryAvailable ? availability.secondary : null;
      show =
          (availability.primaryAvailable && availability.primary.isKataGoPda)
              || (availability.secondaryAvailable && availability.secondary.isKataGoPda);
    } else {
      active = availability.primaryAvailable ? availability.primary : null;
      show = availability.primaryAvailable && availability.primary.isKataGoPda;
    }
    if (LizzieFrame.menu != null) {
      LizzieFrame.menu.showPdaForEngine(active, show);
    }
  }

  void rollbackEngineSelectionAfterFailedSwitch(EngineSwitchUiSnapshot failed) {
    if (failed == null || failed.phase != EngineSwitchUiPhase.FAILED) {
      return;
    }
    Leelaz rollbackEngine = failed.rollbackEngineIdentity;
    if (failed.main) {
      // A stopped/stale previous owner is not routable until it has been restored to the current
      // Board and acknowledged the final synchronization fence.
      rollbackPrimaryEngineSelection(failed.targetEngineIdentity, null, -1);
    } else {
      boolean rollbackAvailable =
          rollbackEngine != null && rollbackEngine.started && rollbackEngine.isLoaded;
      rollbackSecondaryEngineSelection(
          failed.targetEngineIdentity,
          rollbackAvailable ? rollbackEngine : null,
          rollbackAvailable ? failed.rollbackIndex : -1);
    }
  }

  private FailedRollbackRecovery beginFailedRollbackRecovery(
      EngineSwitchUiSnapshot failed, EngineSwitchTransaction transaction) {
    Leelaz rollbackEngine = failed == null ? null : failed.rollbackEngineIdentity;
    if (rollbackEngine == null || failed.rollbackIndex < 0) {
      return null;
    }
    Board rollbackBoard = transaction != null ? transaction.rollbackBoard : Lizzie.board;
    Object lifecycleOwner = transaction != null ? transaction.rollbackLifecycleOwner : null;
    boolean resumePonder = transaction != null && transaction.rollbackResumePonder;
    if (rollbackBoard == null || rollbackBoard != Lizzie.board || Lizzie.engineManager != this) {
      return null;
    }
    FailedRollbackRecovery recovery =
        new FailedRollbackRecovery(
            failed,
            rollbackEngine,
            failed.rollbackIndex,
            rollbackBoard,
            lifecycleOwner,
            resumePonder,
            transaction);
    ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.lock();
    try {
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (!failedRollbackRecovery.compareAndSet(null, recovery)) {
          return null;
        }
        rollbackEngine.suppressGlobalEnginePresentationUntilPhysicalAnalysisOwnership();
      }
    } finally {
      ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.unlock();
    }
    return recovery;
  }

  private void clearFailedRollbackRecovery(FailedRollbackRecovery recovery) {
    ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.lock();
    try {
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (failedRollbackRecovery.get() != recovery) {
          return;
        }
        recovery.engine.abandonAnalysisOutputRecovery(
            recovery.analysisOutputBinding.get(), recovery);
        failedRollbackRecovery.compareAndSet(recovery, null);
      }
    } finally {
      ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.unlock();
    }
  }

  /**
   * Promotes the exact recovery stream and removes its global gate as one canonical
   * analysis-mutation -> selection -> binding commit. No ordinary writer or game admission can
   * observe a cleared recovery barrier while its fresh physical owner is still a tombstone.
   */
  private boolean completeFailedRollbackRecovery(
      FailedRollbackRecovery recovery, boolean requireFreshOwner) {
    ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.lock();
    try {
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        Object recoveryBinding =
            recovery == null ? null : recovery.analysisOutputBinding.get();
        if (!isCurrentFailedRollbackRecoveryLocked(recovery)
            || Lizzie.leelaz != recovery.engine
            || currentEngineNo != recovery.engineIndex) {
          return false;
        }
        long primaryGeneration = Lizzie.capturePrimaryEngineGeneration(recovery.engine);
        if (primaryGeneration < 0L) {
          return false;
        }
        AtomicBoolean completed = new AtomicBoolean();
        boolean primaryCurrent =
            Lizzie.runIfPrimaryEngine(
                recovery.engine,
                primaryGeneration,
                () ->
                    completed.set(
                        recovery.engine.completeAnalysisOutputRecovery(
                            recoveryBinding,
                            recovery,
                            requireFreshOwner,
                            () ->
                                isCurrentFailedRollbackRecoveryLocked(recovery)
                                    && Lizzie.leelaz == recovery.engine
                                    && currentEngineNo == recovery.engineIndex
                                    && !isEmpty
                                    && recovery.analysisOutputBinding.get() == recoveryBinding
                                    && failedRollbackRecovery.compareAndSet(recovery, null))));
        return primaryCurrent && completed.get();
      }
    } finally {
      ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.unlock();
    }
  }

  private void dispatchFailedRollbackRecovery(FailedRollbackRecovery recovery) {
    if (recovery == null) {
      return;
    }
    AtomicBoolean dispatchClaimed = new AtomicBoolean(false);
    java.util.function.Consumer<Throwable> settleSchedulingFailure =
        schedulingFailure -> {
          if (!dispatchClaimed.compareAndSet(false, true)) {
            return;
          }
          schedulingFailure.printStackTrace();
          finishFailedRollbackRecovery(
              recovery,
              null,
              Leelaz.safeFailureDetail(
                  schedulingFailure, "rollback recovery scheduling failed"));
        };
    Runnable work =
        () -> {
          if (!dispatchClaimed.compareAndSet(false, true)) {
            return;
          }
          if (!isCurrentFailedRollbackRecovery(recovery)) {
            clearFailedRollbackRecovery(recovery);
            return;
          }
          runFailedSwitchCleanup(() -> runFailedRollbackRecovery(recovery));
        };
    Runnable dispatch =
        () -> {
          if (!SwingUtilities.isEventDispatchThread()) {
            work.run();
            return;
          }
          try {
            Thread worker =
                createFailedRollbackRecoveryWorker(
                    work,
                    "lizzie-failed-engine-rollback-" + recovery.failedSnapshot.token);
            configureFailedRollbackRecoveryWorker(worker);
            startFailedRollbackRecoveryWorker(worker);
          } catch (RuntimeException | Error schedulingFailure) {
            settleSchedulingFailure.accept(schedulingFailure);
          }
        };
    try {
      if (recovery.failedTransaction == null) {
        dispatch.run();
      } else {
        recovery.failedTransaction.runAfterCompletion(dispatch);
      }
    } catch (RuntimeException | Error schedulingFailure) {
      settleSchedulingFailure.accept(schedulingFailure);
    }
  }

  protected Thread createFailedRollbackRecoveryWorker(Runnable work, String name) {
    return new Thread(work, name);
  }

  protected void configureFailedRollbackRecoveryWorker(Thread worker) {
    worker.setDaemon(true);
  }

  protected void startFailedRollbackRecoveryWorker(Thread worker) {
    worker.start();
  }

  private void runFailedRollbackRecovery(FailedRollbackRecovery recovery) {
    InitialEngineStartupSynchronization synchronization = null;
    try {
      if (!isCurrentFailedRollbackRecovery(recovery)) {
        clearFailedRollbackRecovery(recovery);
        return;
      }
      synchronization =
          InitialEngineStartupSynchronization.captureRollback(
              recovery.engine, recovery.board, recovery.resumePonder, recovery.lifecycleOwner);
      synchronization.beginLifecycleCompletionClaim();
      if (!recovery.engine.isStarted()) {
        recovery.engine.isLoaded = false;
        recovery.engine.startEngineForAnalysisOutputRecovery(recovery, recovery.engineIndex);
      }
      Object recoveryBinding =
          recovery.engine.authorizeAnalysisOutputRecoveryForCurrentBinding(recovery);
      if (!recovery.claimAnalysisOutputBinding(recoveryBinding)) {
        throw new IllegalStateException(
            "Previous engine changed reader binding during rollback restore");
      }
      if (!waitForEngineSynchronizationReadiness(recovery.engine)) {
        throw new IllegalStateException("Previous engine was unavailable during rollback restore");
      }
      synchronization.runUntilStable();
      InitialEngineStartupSynchronization completed = synchronization;
      completed.confirmFinalBoardSynchronization(
          () -> finishFailedRollbackRecovery(recovery, completed, null),
          detail -> finishFailedRollbackRecovery(recovery, completed, detail));
    } catch (IOException | RuntimeException | Error failure) {
      finishFailedRollbackRecovery(recovery, synchronization, engineSwitchFailureDetail(failure));
    }
  }

  private boolean isCurrentFailedRollbackRecovery(FailedRollbackRecovery recovery) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      return isCurrentFailedRollbackRecoveryLocked(recovery);
    }
  }

  private boolean isCurrentFailedRollbackRecoveryLocked(FailedRollbackRecovery recovery) {
    return recovery != null
        && failedRollbackRecovery.get() == recovery
        && recovery.board == Lizzie.board
        && Lizzie.engineManager == this
        && isExactCatalogSlot(this, recovery.engineIndex, recovery.engine)
        && (Lizzie.leelaz == null || Lizzie.leelaz == recovery.engine);
  }

  private boolean publishFailedRollbackPrimary(
      FailedRollbackRecovery recovery, Object recoveryBinding) {
    ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.lock();
    try {
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (!isCurrentFailedRollbackRecoveryLocked(recovery)
            || Lizzie.leelaz != null
            || currentEngineNo != -1
            || !isEmpty) {
          return false;
        }
        long primaryGeneration = Lizzie.capturePrimaryEngineGeneration(null);
        if (primaryGeneration < 0L) {
          return false;
        }
        AtomicBoolean published = new AtomicBoolean();
        boolean primaryCurrent =
            Lizzie.runIfPrimaryEngineWithMutation(
                null,
                primaryGeneration,
                primaryMutation ->
                    recovery.engine.tryRunIfCurrentLiveRecoveryBinding(
                        recoveryBinding,
                        recovery,
                        () -> {
                          if (!isCurrentFailedRollbackRecoveryLocked(recovery)
                              || Lizzie.leelaz != null
                              || currentEngineNo != -1
                              || !isEmpty
                              || recovery.analysisOutputBinding.get() != recoveryBinding) {
                            return;
                          }
                          primaryMutation.replaceWith(recovery.engine);
                          currentEngineNo = recovery.engineIndex;
                          isEmpty = false;
                          captureFailurePresentationPrimaryAuthorityLocked(
                              recovery.failedTransaction, recovery.failedSnapshot.token);
                          published.set(true);
                        }));
        return primaryCurrent && published.get();
      }
    } finally {
      ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.unlock();
    }
  }

  private void finishFailedRollbackRecovery(
      FailedRollbackRecovery recovery,
      InitialEngineStartupSynchronization synchronization,
      String failureDetail) {
    Runnable rollbackRuntimeStop = null;
    boolean recoveryCompleted = false;
    try {
      if (!isCurrentFailedRollbackRecovery(recovery)) {
        return;
      }
      Object recoveryBinding = recovery.analysisOutputBinding.get();
      if (failureDetail == null
          && (recovery.engine.isCheckingName
              || !recovery.engine.isCurrentLiveEngineIncarnation(recoveryBinding))) {
        failureDetail = "Previous engine died before rollback recovery was committed";
      }
      if (failureDetail == null
          && !publishFailedRollbackPrimary(recovery, recoveryBinding)) {
        failureDetail = "Primary owner changed during rollback recovery";
      }
      if (failureDetail == null) {
        try {
          boolean requireFreshOwner =
              recovery.resumePonder
                  && Lizzie.frame != null
                  && !Lizzie.frame.isPlayingAgainstLeelaz
                  && !Lizzie.config.notStartPondering;
          Lizzie.initializeAfterVersionCheck(false, recovery.engine, recovery.resumePonder);
          if (!completeFailedRollbackRecovery(recovery, requireFreshOwner)) {
            throw new IllegalStateException(
                "Rollback engine changed before analysis ownership commit");
          }
          recoveryCompleted = true;
        } catch (RuntimeException | Error finalInitializationFailure) {
          failRecoveredPrimaryFinalInitialization(recovery, finalInitializationFailure);
          return;
        }
        publishEngineSwitchUiState(recovery.failedSnapshot);
      } else {
        synchronized (ENGINE_SELECTION_STATE_LOCK) {
          recovery.engine.isLoaded = false;
          rollbackRuntimeStop =
              quarantineUnavailableEngineLocked(recovery.engine, recovery.failedSnapshot.token);
        }
        correctEngineStartupStatusAfterFailedSwitch(failureDetail);
        correctPdaAfterFailedSwitch();
        publishEngineSwitchUiState(recovery.failedSnapshot);
      }
    } finally {
      try {
        if (synchronization != null) {
          synchronization.close();
        }
      } finally {
        try {
          if (!recoveryCompleted) {
            clearFailedRollbackRecovery(recovery);
          }
        } finally {
          if (rollbackRuntimeStop != null) {
            dispatchFailedEngineStop(rollbackRuntimeStop, recovery.failedSnapshot.token);
          }
        }
      }
    }
  }

  private void failRecoveredPrimaryFinalInitialization(
      FailedRollbackRecovery recovery, Throwable failure) {
    if (recovery == null || recovery.engine == null) {
      return;
    }
    Leelaz engine = recovery.engine;
    Runnable runtimeStop;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      engine.isLoaded = false;
      if (Lizzie.engineManager == this && Lizzie.leelaz == engine) {
        Lizzie.setPrimaryEngine(null);
        currentEngineNo = -1;
        isEmpty = true;
        captureFailurePresentationPrimaryAuthorityLocked(
            recovery.failedTransaction, recovery.failedSnapshot.token);
      }
      runtimeStop = quarantineUnavailableEngineLocked(engine, recovery.failedSnapshot.token);
    }
    String detail = engineSwitchFailureDetail(failure);
    runFailedSwitchCleanup(
        () ->
            engine.markLifecycleBoardSynchronizationFailed(
                detail, engine.hasUnrestoredReadBoardGmaState()));
    runFailedSwitchCleanup(() -> correctEngineStartupStatusAfterFailedSwitch(detail));
    runFailedSwitchCleanup(this::correctPdaAfterFailedSwitch);
    publishEngineSwitchUiState(recovery.failedSnapshot);
    runFailedSwitchCleanup(() -> showEngineSynchronizationFailure(engine));
    dispatchFailedEngineStop(runtimeStop, recovery.failedSnapshot.token);
  }

  private static void publishPrimarySelectionState(Leelaz engine, int index, boolean empty) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      Lizzie.setPrimaryEngine(engine);
      currentEngineNo = index;
      isEmpty = empty;
    }
  }

  static void runEngineGameStateMutation(Runnable mutation) {
    if (mutation == null) {
      return;
    }
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      mutation.run();
    }
  }

  static void resetEngineGameTransactionStateForTest() {
    EngineGameRecoveryBatch recoveryBatch;
    Set<EngineManager> managersWithInstanceRecovery =
        java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (Lizzie.engineManager != null) {
        managersWithInstanceRecovery.add(Lizzie.engineManager);
      }
      if (activeEngineGameTransaction != null && activeEngineGameTransaction.manager != null) {
        managersWithInstanceRecovery.add(activeEngineGameTransaction.manager);
      }
      if (retiringEngineGameTransaction != null && retiringEngineGameTransaction.manager != null) {
        managersWithInstanceRecovery.add(retiringEngineGameTransaction.manager);
      }
      recoveryBatch = activeEngineGameRecoveryBatch;
      if (recoveryBatch != null
          && recoveryBatch.transaction != null
          && recoveryBatch.transaction.manager != null) {
        managersWithInstanceRecovery.add(recoveryBatch.transaction.manager);
      }
      activeEngineGameTransaction = null;
      retiringEngineGameTransaction = null;
      activeEngineGameRecoveryBatch = null;
      engineGameTransactionSequence++;
      isEngineGame = false;
      isPreEngineGame = false;
    }
    for (EngineManager manager : managersWithInstanceRecovery) {
      manager.failedRollbackRecovery.set(null);
    }
    if (recoveryBatch != null && recoveryBatch.current != null) {
      Thread worker = recoveryBatch.current.worker;
      if (worker != null) {
        worker.interrupt();
      }
      closeDeferredEngineGameRecoverySynchronization(recoveryBatch.current);
    }
  }

  static EngineGameTransaction beginEngineGameTransaction(
      EngineManager manager,
      EngineGameInfo gameInfo,
      Long expectedInactiveEpoch,
      boolean publishGameInfo) {
    Leelaz expectedPrimary = Lizzie.leelaz;
    long expectedPrimaryGeneration = Lizzie.capturePrimaryEngineGeneration(expectedPrimary);
    return beginEngineGameTransaction(
        manager,
        gameInfo,
        expectedInactiveEpoch,
        publishGameInfo,
        expectedPrimary,
        expectedPrimaryGeneration);
  }

  private static EngineGameTransaction beginEngineGameTransaction(
      EngineManager manager,
      EngineGameInfo gameInfo,
      Long expectedInactiveEpoch,
      boolean publishGameInfo,
      Leelaz expectedPrimary,
      long expectedPrimaryGeneration) {
    if (manager == null || gameInfo == null) {
      return null;
    }
    long timeoutMillis = Math.max(1L, manager.engineGameStartupTimeoutMillis(gameInfo));
    long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    long now = System.nanoTime();
    long deadlineNanos = now > Long.MAX_VALUE - timeoutNanos ? Long.MAX_VALUE : now + timeoutNanos;
    ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.lock();
    try {
      if (ordinaryAnalysisOutputMutationInProgress) {
        return null;
      }
      ENGINE_GAME_UI_MUTATION_LOCK.lock();
      try {
        synchronized (ENGINE_SELECTION_STATE_LOCK) {
          if (Lizzie.engineManager != manager
              || activeEngineGameTransaction != null
              || retiringEngineGameTransaction != null
              || activeEngineGameRecoveryBatch != null
              || manager.failedRollbackRecovery.get() != null
              || isEngineGame
              || isPreEngineGame
              || (!publishGameInfo && engineGameInfo != gameInfo)) {
            return null;
          }
          if (expectedInactiveEpoch != null
              && engineGameTransactionSequence != expectedInactiveEpoch.longValue()) {
            return null;
          }
          long currentPrimaryGeneration = Lizzie.capturePrimaryEngineGeneration(expectedPrimary);
          if (expectedPrimaryGeneration < 0L
              || currentPrimaryGeneration != expectedPrimaryGeneration) {
            return null;
          }
          int blackIndex = gameInfo.blackEngineIndex;
          int whiteIndex = gameInfo.whiteEngineIndex;
          List<Leelaz> catalog = manager.engineList;
          if (catalog == null
              || blackIndex < 0
              || whiteIndex < 0
              || blackIndex >= catalog.size()
              || whiteIndex >= catalog.size()) {
            return null;
          }
          Leelaz blackEngine = catalog.get(blackIndex);
          Leelaz whiteEngine = catalog.get(whiteIndex);
          if (blackEngine == null || whiteEngine == null || blackEngine == whiteEngine) {
            return null;
          }
          EngineGameTransaction transaction =
              new EngineGameTransaction(
                  manager,
                  gameInfo,
                  ++engineGameTransactionSequence,
                  blackIndex,
                  blackEngine,
                  whiteIndex,
                  whiteEngine,
                  expectedPrimary,
                  expectedPrimaryGeneration,
                  deadlineNanos);
          engineGameInfo = gameInfo;
          activeEngineGameTransaction = transaction;
          isEngineGame = false;
          isPreEngineGame = true;
          isEmpty = false;
          return transaction;
        }
      } finally {
        ENGINE_GAME_UI_MUTATION_LOCK.unlock();
      }
    } finally {
      ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.unlock();
    }
  }

  static boolean runIfNoActiveEngineGameAnalysisOutput(Runnable action) {
    if (action == null) {
      return false;
    }
    ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.lock();
    try {
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (hasEngineGameAnalysisOutputBarrierLocked()) {
          return false;
        }
        ordinaryAnalysisOutputMutationInProgress = true;
      }
      try {
        action.run();
      } finally {
        ordinaryAnalysisOutputMutationInProgress = false;
      }
      return true;
    } finally {
      ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.unlock();
    }
  }

  enum TransactionlessAnalysisWriteKind {
    ORDINARY,
    RECOVERY_TOMBSTONE
  }

  /**
   * Holds the analysis-admission lock from the physical-write classification through flush. The
   * selection monitor is used only for the short classification and is never held across endpoint
   * or transport work.
   */
  static final class TransactionlessAnalysisWriteLease implements AutoCloseable {
    final TransactionlessAnalysisWriteKind kind;
    final Object recoveryToken;
    private boolean closed;

    private TransactionlessAnalysisWriteLease(
        TransactionlessAnalysisWriteKind kind, Object recoveryToken) {
      this.kind = kind;
      this.recoveryToken = recoveryToken;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.unlock();
    }
  }

  /**
   * Holds one exact game transaction's mutation lock from physical-write classification through
   * flush. This is the exact-owner counterpart of {@link TransactionlessAnalysisWriteLease}; it
   * prevents an old parser mutation or successor owner installation from crossing the write.
   */
  static final class EngineGameAnalysisWriteLease implements AutoCloseable {
    final EngineGamePrimaryContext context;
    private final EngineGameTransaction transaction;
    private boolean closed;

    private EngineGameAnalysisWriteLease(
        EngineGameTransaction transaction, EngineGamePrimaryContext context) {
      this.transaction = transaction;
      this.context = context;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      transaction.mutationLock.unlock();
    }
  }

  /** Holds an exact transaction mutation fence across one position-changing physical write. */
  static final class EngineGameStateWriteLease implements AutoCloseable {
    private final EngineGameTransaction transaction;
    private boolean closed;

    private EngineGameStateWriteLease(EngineGameTransaction transaction) {
      this.transaction = transaction;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      transaction.mutationLock.unlock();
    }
  }

  static EngineGameAnalysisWriteLease claimEngineGameAnalysisWrite(
      EngineGameTransaction transaction, Leelaz participant, Object participantIncarnation) {
    if (transaction == null || participant == null || participantIncarnation == null) {
      return null;
    }
    transaction.mutationLock.lock();
    boolean claimed = false;
    try {
      EngineGamePrimaryContext context =
          captureEngineGameAnalysisOutputContext(
              transaction, participant, participantIncarnation);
      if (context == null) {
        return null;
      }
      claimed = true;
      return new EngineGameAnalysisWriteLease(transaction, context);
    } finally {
      if (!claimed) {
        transaction.mutationLock.unlock();
      }
    }
  }

  static EngineGameStateWriteLease claimEngineGameStateWrite(
      EngineGameTransaction transaction, Leelaz participant, Object participantIncarnation) {
    if (transaction == null || participant == null || participantIncarnation == null) {
      return null;
    }
    transaction.mutationLock.lock();
    boolean claimed = false;
    try {
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (!isCurrentEngineGameTransactionLocked(transaction)) {
          return null;
        }
        boolean currentBlack =
            participant == transaction.blackEngine
                && (transaction.blackIncarnation == null
                    || transaction.blackIncarnation == participantIncarnation);
        boolean currentWhite =
            participant == transaction.whiteEngine
                && (transaction.whiteIncarnation == null
                    || transaction.whiteIncarnation == participantIncarnation);
        if (!currentBlack && !currentWhite) {
          return null;
        }
      }
      claimed = true;
      return new EngineGameStateWriteLease(transaction);
    } finally {
      if (!claimed) {
        transaction.mutationLock.unlock();
      }
    }
  }

  /**
   * Classifies a transaction-less physical analysis write at one canonical
   * analysis-mutation -> selection boundary. A game/recovery barrier may never be inferred merely
   * from an endpoint suppression flag: only the exact recovery token captured by this binding can
   * authorize a quarantine write.
   */
  static TransactionlessAnalysisWriteLease claimTransactionlessAnalysisWrite(
      Leelaz engine, Object expectedIncarnation, Object recoveryToken) {
    if (engine == null || expectedIncarnation == null) {
      return null;
    }
    ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.lock();
    boolean claimed = false;
    try {
      TransactionlessAnalysisWriteKind kind;
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        EngineManager manager = Lizzie.engineManager;
        boolean hasBarrier = hasEngineGameAnalysisOutputBarrierLocked();
        Object bindingRecoveryToken =
            engine.analysisOutputRecoveryToken(expectedIncarnation);
        if (bindingRecoveryToken != null) {
          if (manager == null
              || recoveryToken != bindingRecoveryToken
              || (!manager.isExactFailedRollbackAnalysisRecoveryLocked(
                      engine, expectedIncarnation, bindingRecoveryToken)
                  && !manager.isExactDeferredEngineGameAnalysisRecoveryLocked(
                      engine, expectedIncarnation, bindingRecoveryToken))) {
            return null;
          }
          kind = TransactionlessAnalysisWriteKind.RECOVERY_TOMBSTONE;
        } else if (hasBarrier) {
          return null;
        } else {
          kind = TransactionlessAnalysisWriteKind.ORDINARY;
        }
      }
      claimed = true;
      return new TransactionlessAnalysisWriteLease(
          kind,
          kind == TransactionlessAnalysisWriteKind.RECOVERY_TOMBSTONE
              ? recoveryToken
              : null);
    } finally {
      if (!claimed) {
        ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.unlock();
      }
    }
  }

  private boolean isExactFailedRollbackAnalysisRecoveryLocked(
      Leelaz engine, Object expectedIncarnation, Object recoveryToken) {
    FailedRollbackRecovery recovery = failedRollbackRecovery.get();
    return recovery != null
        && recoveryToken == recovery
        && recovery.engine == engine
        && isExactCatalogSlot(this, recovery.engineIndex, engine)
        && recovery.board == Lizzie.board
        && Lizzie.engineManager == this
        && engine.analysisOutputRecoveryToken(expectedIncarnation) == recoveryToken
        && recovery.claimAnalysisOutputBinding(expectedIncarnation);
  }

  /** Requires {@link #ENGINE_SELECTION_STATE_LOCK}. */
  private boolean isExactDeferredEngineGameAnalysisRecoveryLocked(
      Leelaz engine, Object expectedIncarnation, Object recoveryToken) {
    EngineGameRecoveryBatch recoveryBatch = activeEngineGameRecoveryBatch;
    EngineGameDeferredRecovery recovery =
        recoveryBatch == null ? null : recoveryBatch.current;
    InitialEngineStartupSynchronization synchronization =
        recovery == null ? null : recovery.synchronization;
    Object replacementIncarnation =
        recovery == null ? null : recovery.replacementIncarnation;
    return recovery != null
        && recoveryToken == recovery
        && recoveryBatch.transaction == recovery.transaction
        && !recovery.completed.get()
        && recovery.engine == engine
        && recovery.startAttempt != null
        && (replacementIncarnation == null || replacementIncarnation == expectedIncarnation)
        && synchronization != null
        && synchronization.lifecycleOwner != null
        && synchronization.board == Lizzie.board
        && recovery.transaction.manager == this
        && Lizzie.engineManager == this
        && engineGameInfo == recovery.transaction.gameInfo
        && engineGameTransactionSequence == recovery.transaction.inactiveEpoch
        && activeEngineGameTransaction == null
        && retiringEngineGameTransaction == null
        && !isEngineGame
        && !isPreEngineGame
        && recovery.transactionEpoch == recovery.transaction.epoch
        && isExactCatalogSlot(this, recovery.engineIndex, engine)
        && engine.analysisOutputRecoveryToken(expectedIncarnation) == recoveryToken;
  }

  private static boolean isCurrentEngineGameTransactionLocked(EngineGameTransaction transaction) {
    return transaction != null
        && activeEngineGameTransaction == transaction
        && engineGameTransactionSequence == transaction.epoch
        && transaction.manager != null
        && Lizzie.engineManager == transaction.manager
        && engineGameInfo == transaction.gameInfo
        && transaction.gameInfo.blackEngineIndex == transaction.blackIndex
        && transaction.gameInfo.whiteEngineIndex == transaction.whiteIndex
        && isExactCatalogSlot(transaction.manager, transaction.blackIndex, transaction.blackEngine)
        && isExactCatalogSlot(transaction.manager, transaction.whiteIndex, transaction.whiteEngine)
        && transaction.phase != EngineGamePhase.FAILED
        && transaction.phase != EngineGamePhase.CANCELLED
        && (isEngineGame || isPreEngineGame);
  }

  static boolean isCurrentEngineGameTransaction(EngineGameTransaction transaction) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      return isCurrentEngineGameTransactionLocked(transaction);
    }
  }

  static boolean runIfNoEngineGameAnalysisOutputBarrier(Runnable action) {
    if (action == null) {
      return false;
    }
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (hasEngineGameAnalysisOutputBarrierLocked()) {
        return false;
      }
      action.run();
      return true;
    }
  }

  static boolean runIfEngineGameAnalysisOutputBarrier(Runnable action) {
    if (action == null) {
      return false;
    }
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!hasEngineGameAnalysisOutputBarrierLocked()) {
        return false;
      }
      action.run();
      return true;
    }
  }

  static boolean hasEngineGameAnalysisOutputBarrier() {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      return hasEngineGameAnalysisOutputBarrierLocked();
    }
  }

  private static boolean hasEngineGameAnalysisOutputBarrierLocked() {
    EngineManager manager = Lizzie.engineManager;
    return activeEngineGameTransaction != null
        || retiringEngineGameTransaction != null
        || activeEngineGameRecoveryBatch != null
        || isEngineGame
        || isPreEngineGame
        || (manager != null && manager.failedRollbackRecovery.get() != null);
  }

  /** Lock-free second admission check used only while an endpoint command queue is held. */
  static boolean isEngineGameOutputAdmissionOpen(EngineGameTransaction transaction) {
    if (transaction == null) {
      return false;
    }
    EngineGamePhase phase = transaction.phase;
    return phase == EngineGamePhase.PREPARING
        || phase == EngineGamePhase.DISPATCHED
        || phase == EngineGamePhase.ACTIVE;
  }

  private static EngineGameOperationLease claimEngineGameOperation(
      EngineGameTransaction transaction) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGameTransactionLocked(transaction)) {
        return null;
      }
      transaction.operationsInFlight.incrementAndGet();
      return new EngineGameOperationLease(transaction);
    }
  }

  private static boolean runEngineGameIoStep(
      EngineGameTransaction transaction, Runnable operation) {
    EngineGameOperationLease lease = claimEngineGameOperation(transaction);
    if (lease == null) {
      return false;
    }
    try {
      if (!lease.isCurrent()) {
        return false;
      }
      Leelaz.runWithEngineGameStartupCommandContext(transaction, operation);
      return lease.isCurrent();
    } catch (RuntimeException | Error failure) {
      failEngineGameTransaction(transaction, failure);
      throw failure;
    } finally {
      lease.close();
    }
  }

  static boolean runEngineGameIoStepForTest(
      EngineGameTransaction transaction, Runnable operation) {
    return runEngineGameIoStep(transaction, operation);
  }

  static boolean transitionEngineGameToDispatched(EngineGameTransaction transaction) {
    if (transaction == null) {
      return false;
    }
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGameTransactionLocked(transaction)
          || transaction.phase != EngineGamePhase.PREPARING) {
        return false;
      }
      transaction.phase = EngineGamePhase.DISPATCHED;
      return true;
    }
  }

  static boolean runIfCurrentEngineGameTransaction(
      EngineGameTransaction transaction, Runnable action) {
    if (action == null) {
      return false;
    }
    Throwable failure = null;
    boolean executed = false;
    transaction.mutationLock.lock();
    try {
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (!isCurrentEngineGameTransactionLocked(transaction)) {
          return false;
        }
      }
      executed = true;
      try {
        action.run();
      } catch (RuntimeException | Error operationFailure) {
        failure = operationFailure;
        failEngineGameTransaction(transaction, operationFailure);
      }
    } finally {
      transaction.mutationLock.unlock();
    }
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    return executed;
  }

  /** Runs one short, non-blocking mutation only for the exact participant/turn parser frame. */
  static boolean runIfCurrentEngineGameMoveResponse(
      EngineGameMoveResponseContext context, Runnable action) {
    if (context == null || context.transaction == null || action == null) {
      return false;
    }
    EngineGameTransaction transaction = context.transaction;
    Throwable failure = null;
    boolean executed = false;
    transaction.mutationLock.lock();
    try {
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (!isCurrentEngineGameMoveResponseLocked(context)) {
          return false;
        }
      }
      executed = true;
      try {
        action.run();
      } catch (RuntimeException | Error mutationFailure) {
        failure = mutationFailure;
      }
    } finally {
      transaction.mutationLock.unlock();
    }
    if (failure != null) {
      failEngineGameTransaction(transaction, failure);
    }
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    return executed;
  }

  /** Runs one short, non-blocking mutation after an exact engine-game board commit. */
  static boolean runIfCurrentEngineGamePostMoveToken(
      EngineGamePostMoveToken token, Runnable action) {
    if (token == null || token.transaction == null || action == null) {
      return false;
    }
    EngineGameTransaction transaction = token.transaction;
    Throwable failure = null;
    boolean executed = false;
    transaction.mutationLock.lock();
    try {
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (!isCurrentEngineGamePostMoveTokenLocked(token)) {
          return false;
        }
      }
      executed = true;
      try {
        action.run();
      } catch (RuntimeException | Error mutationFailure) {
        failure = mutationFailure;
      }
    } finally {
      transaction.mutationLock.unlock();
    }
    if (failure != null) {
      failEngineGameTransaction(transaction, failure);
    }
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    return executed;
  }

  /** Runs potentially blocking work without making cancellation wait for it. */
  static boolean runIfCurrentEngineGameOperation(
      EngineGameTransaction transaction, Runnable action) {
    if (transaction == null || action == null) {
      return false;
    }
    Throwable failure = null;
    boolean executed = false;
    EngineGameOperationLease lease = claimEngineGameOperation(transaction);
    if (lease == null) {
      return false;
    }
    try {
      if (!lease.isCurrent()) {
        return false;
      }
      executed = true;
      try {
        action.run();
      } catch (RuntimeException | Error operationFailure) {
        failure = operationFailure;
        failEngineGameTransaction(transaction, operationFailure);
      }
    } finally {
      lease.close();
    }
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    return executed;
  }

  static void failEngineGameTransaction(EngineGameTransaction transaction, Throwable failure) {
    claimTerminalEngineGameTransaction(transaction, EngineGamePhase.FAILED, failure, false);
    finishAutomaticEngineGameRetirementIfQuiescent(transaction);
  }

  private static boolean claimTerminalEngineGameTransaction(
      EngineGameTransaction transaction,
      EngineGamePhase terminalPhase,
      Throwable failure,
      boolean externalTerminalOwner) {
    if (transaction == null) {
      return false;
    }
    boolean claimed = false;
    transaction.mutationLock.lock();
    try {
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (isCurrentEngineGameTransactionLocked(transaction)) {
          transaction.gameWasActiveBeforeTerminal = isEngineGame;
          transaction.gameWasStartingBeforeTerminal = isPreEngineGame;
          transaction.phase = terminalPhase;
          transaction.terminalFailure = failure;
          transaction.externalTerminalOwner = externalTerminalOwner;
          activeEngineGameTransaction = null;
          retiringEngineGameTransaction = transaction;
          transaction.inactiveEpoch = ++engineGameTransactionSequence;
          isEngineGame = false;
          isPreEngineGame = false;
          claimed = true;
        }
      }
    } finally {
      transaction.mutationLock.unlock();
    }
    if (claimed) {
      interruptEngineGameWorkers(transaction);
      Throwable cancellationFailure = null;
      try {
        transaction.blackEngine.cancelEngineGameRequests(transaction);
      } catch (RuntimeException | Error endpointFailure) {
        cancellationFailure = appendEngineGameFailure(cancellationFailure, endpointFailure);
      }
      try {
        transaction.whiteEngine.cancelEngineGameRequests(transaction);
      } catch (RuntimeException | Error endpointFailure) {
        cancellationFailure = appendEngineGameFailure(cancellationFailure, endpointFailure);
      }
      if (cancellationFailure != null) {
        appendEngineGameTerminalFailure(transaction, cancellationFailure);
      }
      scheduleEngineGamePhysicalRequestWatchdog(transaction);
    }
    return claimed;
  }

  /**
   * Schedules one bounded escalation only for commands which already owned their physical stream.
   * Generic transaction work and RESERVED queued commands never enter {@code physicalRequests}, so
   * neither can cause an engine process to be force-closed.
   */
  private static void scheduleEngineGamePhysicalRequestWatchdog(
      EngineGameTransaction transaction) {
    if (transaction == null
        || !hasOpenEngineGamePhysicalRequest(transaction)
        || !transaction.physicalRequestWatchdogScheduled.compareAndSet(false, true)) {
      return;
    }
    long graceMillis = ENGINE_GAME_PHYSICAL_REQUEST_FORCE_GRACE_MILLIS;
    try {
      graceMillis =
          Math.max(0L, transaction.manager.engineGamePhysicalRequestForceGraceMillis());
    } catch (RuntimeException | Error graceFailure) {
      appendEngineGameTerminalFailure(transaction, graceFailure);
    }
    Runnable guarded =
        () -> {
          if (transaction.physicalRequestWatchdogRun.compareAndSet(false, true)) {
            forceOpenEngineGamePhysicalRequests(transaction);
          }
        };
    try {
      transaction.manager.scheduleEngineGamePhysicalRequestWatchdog(
          guarded,
          graceMillis,
          "engine-game-physical-watchdog-" + transaction.epoch);
    } catch (RuntimeException | Error schedulingFailure) {
      appendEngineGameTerminalFailure(transaction, schedulingFailure);
      scheduleEngineGamePhysicalRequestWatchdogFallback(
          transaction, guarded, graceMillis, schedulingFailure);
    }
  }

  private static boolean hasOpenEngineGamePhysicalRequest(
      EngineGameTransaction transaction) {
    for (EngineGamePhysicalRequestLease request : transaction.physicalRequests) {
      if (request.isOpen()) {
        return true;
      }
    }
    return false;
  }

  /** Claims every lease for one exact stream before issuing its single physical abort. */
  private static void forceOpenEngineGamePhysicalRequests(
      EngineGameTransaction transaction) {
    List<EngineGamePhysicalRequestLease> snapshot =
        new ArrayList<>(transaction.physicalRequests);
    boolean[] grouped = new boolean[snapshot.size()];
    for (int index = 0; index < snapshot.size(); index++) {
      if (grouped[index]) {
        continue;
      }
      EngineGamePhysicalRequestLease seed = snapshot.get(index);
      List<EngineGamePhysicalRequestLease> claimed = new ArrayList<>();
      for (int candidateIndex = index; candidateIndex < snapshot.size(); candidateIndex++) {
        EngineGamePhysicalRequestLease candidate = snapshot.get(candidateIndex);
        if (!seed.samePhysicalStream(candidate)) {
          continue;
        }
        grouped[candidateIndex] = true;
        if (candidate.claimForce()) {
          claimed.add(candidate);
        }
      }
      if (claimed.isEmpty()) {
        continue;
      }
      Throwable forceFailure = null;
      try {
        // No selection, mutation, or endpoint lock is held here. The exact binding makes a rebind
        // that already won harmless: the endpoint simply returns false and the old leases close.
        seed.endpoint.forceQuitIfCurrentIncarnation(seed.endpointIncarnation);
      } catch (RuntimeException | Error endpointFailure) {
        forceFailure = endpointFailure;
      } finally {
        Throwable cleanupFailure = forceFailure;
        for (EngineGamePhysicalRequestLease request : claimed) {
          try {
            request.finishForce();
          } catch (RuntimeException | Error requestFailure) {
            cleanupFailure = appendEngineGameFailure(cleanupFailure, requestFailure);
          }
        }
        if (cleanupFailure != null) {
          appendEngineGameTerminalFailure(transaction, cleanupFailure);
        }
      }
    }
  }

  private static void scheduleEngineGamePhysicalRequestWatchdogFallback(
      EngineGameTransaction transaction,
      Runnable task,
      long graceMillis,
      Throwable schedulingFailure) {
    try {
      Thread fallback =
          new Thread(
              () -> runDelayedEngineGamePhysicalRequestWatchdog(task, graceMillis),
              "engine-game-physical-watchdog-fallback-" + transaction.epoch);
      fallback.setDaemon(true);
      fallback.start();
    } catch (RuntimeException | Error fallbackFailure) {
      appendEngineGameTerminalFailure(transaction, fallbackFailure);
      appendEngineGameFailure(schedulingFailure, fallbackFailure);
      try {
        ForkJoinPool.commonPool()
            .execute(() -> runDelayedEngineGamePhysicalRequestWatchdog(task, graceMillis));
      } catch (RuntimeException | Error commonPoolFailure) {
        appendEngineGameTerminalFailure(transaction, commonPoolFailure);
        appendEngineGameFailure(schedulingFailure, commonPoolFailure);
        // Calling an endpoint synchronously here could violate an outer caller's lock order. If
        // every asynchronous mechanism is unavailable, release the quarantined leases without an
        // endpoint call so successor admission is still bounded and deadlock-free.
        if (transaction.physicalRequestWatchdogRun.compareAndSet(false, true)) {
          releaseOpenEngineGamePhysicalRequestsWithoutForce(transaction);
        }
      }
    }
  }

  private static void releaseOpenEngineGamePhysicalRequestsWithoutForce(
      EngineGameTransaction transaction) {
    Throwable releaseFailure = null;
    for (EngineGamePhysicalRequestLease request :
        new ArrayList<>(transaction.physicalRequests)) {
      if (!request.claimForce()) {
        continue;
      }
      try {
        request.finishForce();
      } catch (RuntimeException | Error requestFailure) {
        releaseFailure = appendEngineGameFailure(releaseFailure, requestFailure);
      }
    }
    if (releaseFailure != null) {
      appendEngineGameTerminalFailure(transaction, releaseFailure);
    }
  }

  private static void runDelayedEngineGamePhysicalRequestWatchdog(
      Runnable task, long graceMillis) {
    boolean interrupted = false;
    try {
      if (graceMillis > 0L) {
        TimeUnit.MILLISECONDS.sleep(graceMillis);
      }
    } catch (InterruptedException interruption) {
      interrupted = true;
    } finally {
      task.run();
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  protected long engineGamePhysicalRequestForceGraceMillis() {
    return ENGINE_GAME_PHYSICAL_REQUEST_FORCE_GRACE_MILLIS;
  }

  /**
   * Test seam and manager-owned scheduler. Implementations must dispatch asynchronously because a
   * terminal caller may still own an outer transaction lock; the task is deliberately not a
   * transaction worker and therefore is not interrupted by terminal cancellation.
   */
  protected void scheduleEngineGamePhysicalRequestWatchdog(
      Runnable task, long graceMillis, String name) {
    ENGINE_GAME_PHYSICAL_REQUEST_WATCHDOG.schedule(
        task, graceMillis, TimeUnit.MILLISECONDS);
  }

  private static ScheduledThreadPoolExecutor createEngineGamePhysicalRequestWatchdogExecutor() {
    ScheduledThreadPoolExecutor executor =
        new ScheduledThreadPoolExecutor(
            1,
            task -> {
              Thread worker = new Thread(task, "engine-game-physical-watchdog");
              worker.setDaemon(true);
              return worker;
            });
    executor.setRemoveOnCancelPolicy(true);
    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    return executor;
  }

  private static void appendEngineGameTerminalFailure(
      EngineGameTransaction transaction, Throwable failure) {
    if (transaction == null || failure == null) {
      return;
    }
    synchronized (transaction) {
      transaction.terminalFailure =
          appendEngineGameFailure(transaction.terminalFailure, failure);
    }
  }

  private static void finishAutomaticEngineGameRetirementIfQuiescent(
      EngineGameTransaction transaction) {
    if (transaction == null || transaction.retirementFinished.get()) {
      return;
    }
    boolean shouldRestore;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (retiringEngineGameTransaction != transaction
          || (transaction.phase != EngineGamePhase.FAILED
              && transaction.phase != EngineGamePhase.CANCELLED)) {
        return;
      }
      if (transaction.rollbackFinished.get()) {
        completeEngineGameTransactionRetirementIfQuiescent(transaction);
        return;
      }
      shouldRestore = !transaction.externalTerminalOwner;
    }
    if (!shouldRestore) {
      return;
    }
    beginEngineGameRollback(transaction);
  }

  private static void finishEngineGameTransactionRetirement(
      EngineGameTransaction transaction, boolean restoreUi) {
    if (transaction == null) {
      return;
    }
    if (restoreUi) {
      beginEngineGameRollback(transaction);
    } else {
      transaction.rollbackFinished.set(true);
      completeEngineGameTransactionRetirementIfQuiescent(transaction);
    }
  }

  private static void beginEngineGameRollback(EngineGameTransaction transaction) {
    if (transaction == null || !transaction.rollbackStarted.compareAndSet(false, true)) {
      completeEngineGameTransactionRetirementIfQuiescent(transaction);
      return;
    }
    restoreFailedEngineGameTransaction(
        transaction,
        transaction.terminalFailure,
        () -> {
          transaction.rollbackFinished.set(true);
          completeEngineGameTransactionRetirementIfQuiescent(transaction);
        });
  }

  private static void completeEngineGameTransactionRetirementIfQuiescent(
      EngineGameTransaction transaction) {
    if (transaction == null
        || !transaction.rollbackFinished.get()
        || transaction.operationsInFlight.get() != 0
        || !transaction.retirementCompletionClaimed.compareAndSet(false, true)) {
      return;
    }
    // One last exact cleanup closes the window where an already-admitted nonblocking operation
    // installs Leela0110 state after the terminal owner's first cancellation pass. No new lease
    // can be admitted now, and the retiring barrier remains published until both endpoints are
    // clean, so a batch successor cannot inherit the predecessor's timer or BoardData sentinel.
    Throwable cleanupFailure = null;
    try {
      transaction.blackEngine.cancelLeela0110PonderForEngineGameTransaction(transaction);
    } catch (RuntimeException | Error endpointFailure) {
      cleanupFailure = appendEngineGameFailure(cleanupFailure, endpointFailure);
    }
    try {
      transaction.whiteEngine.cancelLeela0110PonderForEngineGameTransaction(transaction);
    } catch (RuntimeException | Error endpointFailure) {
      cleanupFailure = appendEngineGameFailure(cleanupFailure, endpointFailure);
    }
    if (cleanupFailure != null) {
      transaction.terminalFailure =
          appendEngineGameFailure(transaction.terminalFailure, cleanupFailure);
    }
    completeEngineGameTransactionRetirement(transaction);
  }

  private static void completeEngineGameTransactionRetirement(EngineGameTransaction transaction) {
    EngineGameRecoveryBatch recoveryBatch = null;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (retiringEngineGameTransaction == transaction) {
        retiringEngineGameTransaction = null;
        if (!transaction.deferredRecoveries.isEmpty()) {
          recoveryBatch =
              new EngineGameRecoveryBatch(
                  transaction, new ArrayList<>(transaction.deferredRecoveries));
          activeEngineGameRecoveryBatch = recoveryBatch;
        }
      }
    }
    if (recoveryBatch != null) {
      dispatchNextDeferredEngineGameRecovery(recoveryBatch);
    } else {
      // Publish completion only after endpoint cleanup and the retiring barrier are gone. A
      // continuation may use this bit as its release/acquire handoff and must not cross either
      // operation.
      transaction.retirementFinished.set(true);
      dispatchEngineGameRetirementContinuation(transaction);
    }
  }

  enum DeferredEngineGameRecoveryStage {
    START,
    READINESS,
    CONFIRMATION
  }

  private static void dispatchNextDeferredEngineGameRecovery(
      EngineGameRecoveryBatch recoveryBatch) {
    EngineGameDeferredRecovery recovery = null;
    boolean finished = false;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (activeEngineGameRecoveryBatch != recoveryBatch) {
        return;
      }
      if (recoveryBatch.nextIndex >= recoveryBatch.recoveries.size()) {
        recoveryBatch.current = null;
        activeEngineGameRecoveryBatch = null;
        // Keep the selection lock across batch-gate release and exact unquarantine. Analysis
        // output admission also takes this lock, so no ordinary output can observe the gate open
        // while a successfully recovered binding is still presentation-suppressed. Failed or
        // superseded replacements remain quarantined and are closed by their start attempt.
        for (EngineGameDeferredRecovery completedRecovery : recoveryBatch.recoveries) {
          if (completedRecovery.recoveredSuccessfully
              && completedRecovery.replacementIncarnation != null) {
            completedRecovery.engine
                .unquarantineDeferredEngineGameRecoveryIncarnation(
                    completedRecovery.replacementIncarnation);
          }
        }
        // Deferred endpoint recovery is part of retirement: do not publish the handoff until its
        // barrier is removed and every successful replacement has been unquarantined.
        recoveryBatch.transaction.retirementFinished.set(true);
        finished = true;
      } else {
        recovery = recoveryBatch.recoveries.get(recoveryBatch.nextIndex++);
        recoveryBatch.current = recovery;
      }
    }
    if (finished) {
      dispatchEngineGameRetirementContinuation(recoveryBatch.transaction);
      return;
    }

    EngineGameDeferredRecovery frozenRecovery = recovery;
    AtomicInteger owner = new AtomicInteger();
    Runnable task =
        () -> {
          if (owner.compareAndSet(0, 1)) {
            runDeferredEngineGameRecovery(recoveryBatch, frozenRecovery);
          }
        };
    try {
      Thread worker =
          recoveryBatch.transaction.manager.createDeferredEngineGameRecoveryWorker(
              task,
              "engine-game-recovery-"
                  + recoveryBatch.transaction.epoch
                  + "-"
                  + recovery.engineIndex);
      recovery.worker = worker;
      worker.setDaemon(true);
      worker.start();
    } catch (RuntimeException | Error schedulingFailure) {
      if (owner.compareAndSet(0, 2)) {
        completeDeferredEngineGameRecovery(
            recoveryBatch,
            recovery,
            "deferred engine-game recovery could not be scheduled",
            schedulingFailure);
      } else {
        schedulingFailure.printStackTrace();
      }
    }
  }

  private static void runDeferredEngineGameRecovery(
      EngineGameRecoveryBatch recoveryBatch, EngineGameDeferredRecovery recovery) {
    EngineManager manager = recovery.transaction.manager;
    Leelaz engine = recovery.engine;
    InitialEngineStartupSynchronization synchronization = null;
    try {
      if (!isDeferredEngineGameRecoveryCurrent(recoveryBatch, recovery, recovery.failedIncarnation)) {
        completeDeferredEngineGameRecovery(recoveryBatch, recovery, null, null);
        return;
      }
      Board restoreBoard = Lizzie.board;
      if (restoreBoard == null) {
        completeDeferredEngineGameRecovery(
            recoveryBatch, recovery, "engine-game recovery board is unavailable", null);
        return;
      }
      synchronization =
          InitialEngineStartupSynchronization.capturePrepared(
              null,
              engine,
              null,
              restoreBoard,
              false,
              false);
      recovery.synchronization = synchronization;
      synchronization.acquireReservation();
      synchronization.beginLifecycleCompletionClaim();
      final InitialEngineStartupSynchronization recoverySynchronization = synchronization;
      Leelaz.UpdateEngineStartAttempt startAttempt = engine.beginUpdateEngineStartAttempt();
      recovery.startAttempt = startAttempt;
      if (!isDeferredEngineGameRecoveryCurrent(recoveryBatch, recovery, recovery.failedIncarnation)) {
        completeDeferredEngineGameRecovery(recoveryBatch, recovery, null, null);
        return;
      }

      manager.beforeDeferredEngineGameRecoveryStage(
          recovery, DeferredEngineGameRecoveryStage.START);
      if (!isDeferredEngineGameRecoveryCurrent(recoveryBatch, recovery, recovery.failedIncarnation)) {
        completeDeferredEngineGameRecovery(recoveryBatch, recovery, null, null);
        return;
      }
      if (!engine.forceQuitIfCurrentIncarnation(recovery.failedIncarnation)) {
        completeDeferredEngineGameRecovery(recoveryBatch, recovery, null, null);
        return;
      }
      if (recovery.openClNativeExit
          && !engine.prepareBundledOpenClRecoveryForFailedIncarnation(
              recovery.failedIncarnation)) {
        completeDeferredEngineGameRecovery(
            recoveryBatch,
            recovery,
            "OpenCL compatibility recovery no longer owns the force-retired incarnation",
            null);
        return;
      }
      if (!runIfCurrentDeferredEngineGameRecoveryIncarnation(
          recoveryBatch,
          recovery,
          recovery.failedIncarnation,
          () -> {
            engine.isLoaded = false;
            engine.played = false;
            engine.width = Board.boardWidth;
            engine.height = Board.boardHeight;
            engine.komi = recoverySynchronization.pendingRoute.rootKomi.floatValue();
          })) {
        completeDeferredEngineGameRecovery(recoveryBatch, recovery, null, null);
        return;
      }
      engine.startDeferredEngineGameRecovery(startAttempt, recovery, recovery.engineIndex);
      Object replacementIncarnation = startAttempt.publishedIncarnation();
      if (replacementIncarnation == null || replacementIncarnation == recovery.failedIncarnation) {
        completeDeferredEngineGameRecovery(
            recoveryBatch,
            recovery,
            "engine-game recovery did not publish a replacement incarnation",
            null);
        return;
      }
      Object authorizedIncarnation =
          engine.authorizeAnalysisOutputRecoveryForExactBinding(
              replacementIncarnation, recovery);
      if (authorizedIncarnation != replacementIncarnation) {
        completeDeferredEngineGameRecovery(
            recoveryBatch,
            recovery,
            "engine-game recovery lost its exact analysis-output binding",
            null);
        return;
      }
      recovery.replacementIncarnation = authorizedIncarnation;
      manager.beforeDeferredEngineGameRecoveryStage(
          recovery, DeferredEngineGameRecoveryStage.READINESS);
      if (!isDeferredEngineGameRecoveryCurrent(
          recoveryBatch, recovery, replacementIncarnation)) {
        completeDeferredEngineGameRecovery(recoveryBatch, recovery, null, null);
        return;
      }
      if (!manager.waitForEngineSynchronizationReadiness(engine)) {
        completeDeferredEngineGameRecovery(
            recoveryBatch, recovery, "replacement engine did not become ready", null);
        return;
      }
      if (!isDeferredEngineGameRecoveryCurrent(
          recoveryBatch, recovery, replacementIncarnation)) {
        completeDeferredEngineGameRecovery(recoveryBatch, recovery, null, null);
        return;
      }

      recoverySynchronization.runUntilStable(false);
      if (!isDeferredEngineGameRecoveryCurrent(
          recoveryBatch, recovery, replacementIncarnation)) {
        completeDeferredEngineGameRecovery(recoveryBatch, recovery, null, null);
        return;
      }
      manager.beforeDeferredEngineGameRecoveryStage(
          recovery, DeferredEngineGameRecoveryStage.CONFIRMATION);
      if (!isDeferredEngineGameRecoveryCurrent(
          recoveryBatch, recovery, replacementIncarnation)) {
        completeDeferredEngineGameRecovery(recoveryBatch, recovery, null, null);
        return;
      }
      recoverySynchronization.confirmFinalBoardSynchronization(
          () -> {
            try {
              if (!runIfCurrentDeferredEngineGameRecoveryIncarnation(
                  recoveryBatch,
                  recovery,
                  replacementIncarnation,
                  () -> {
                    engine.nameCmd();
                    engine.setResponseUpToDate();
                    engine.isDownWithError = false;
                  })) {
                completeDeferredEngineGameRecovery(recoveryBatch, recovery, null, null);
                return;
              }
              if (!completeDeferredEngineGameAnalysisOutputRecovery(
                  recoveryBatch, recovery, replacementIncarnation)) {
                completeDeferredEngineGameRecovery(
                    recoveryBatch,
                    recovery,
                    "replacement engine lost analysis-output recovery ownership",
                    null);
                return;
              }
              startAttempt.complete();
              recovery.startAttemptCommitted = true;
              recovery.recoveredSuccessfully = true;
              completeDeferredEngineGameRecovery(recoveryBatch, recovery, null, null);
            } catch (RuntimeException | Error completionFailure) {
              completeDeferredEngineGameRecovery(
                  recoveryBatch,
                  recovery,
                  "replacement engine finalization failed",
                  completionFailure);
            }
          },
          detail ->
              completeDeferredEngineGameRecovery(recoveryBatch, recovery, detail, null));
    } catch (IOException | RuntimeException | Error recoveryFailure) {
      completeDeferredEngineGameRecovery(
          recoveryBatch,
          recovery,
          Leelaz.safeFailureDetail(recoveryFailure, "deferred engine-game recovery failed"),
          recoveryFailure);
    }
  }

  private static boolean isDeferredEngineGameRecoveryCurrent(
      EngineGameRecoveryBatch recoveryBatch,
      EngineGameDeferredRecovery recovery,
      Object expectedIncarnation) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      return recoveryBatch != null
          && recovery != null
          && expectedIncarnation != null
          && activeEngineGameRecoveryBatch == recoveryBatch
          && recoveryBatch.transaction == recovery.transaction
          && recoveryBatch.current == recovery
          && !recovery.completed.get()
          && recovery.transaction.manager != null
          && Lizzie.engineManager == recovery.transaction.manager
          && engineGameInfo == recovery.transaction.gameInfo
          && engineGameTransactionSequence == recovery.transaction.inactiveEpoch
          && activeEngineGameTransaction == null
          && retiringEngineGameTransaction == null
          && !isEngineGame
          && !isPreEngineGame
          && recovery.transactionEpoch == recovery.transaction.epoch
          && isExactCatalogSlot(
              recovery.transaction.manager, recovery.engineIndex, recovery.engine)
          && recovery.engine.isCurrentEngineIncarnation(expectedIncarnation);
    }
  }

  private static boolean runIfCurrentDeferredEngineGameRecoveryIncarnation(
      EngineGameRecoveryBatch recoveryBatch,
      EngineGameDeferredRecovery recovery,
      Object expectedIncarnation,
      Runnable action) {
    if (!isDeferredEngineGameRecoveryCurrent(recoveryBatch, recovery, expectedIncarnation)) {
      return false;
    }
    if (!recovery.engine.runIfCurrentEngineIncarnation(expectedIncarnation, action)) {
      return false;
    }
    return isDeferredEngineGameRecoveryCurrent(recoveryBatch, recovery, expectedIncarnation);
  }

  /**
   * Clears the exact recovery capability and promotes any physically published tombstone while
   * the batch barrier is still active. The later batch commit only removes presentation
   * quarantine and the global barrier; ordinary output can never observe an unowned gap.
   */
  private static boolean completeDeferredEngineGameAnalysisOutputRecovery(
      EngineGameRecoveryBatch recoveryBatch,
      EngineGameDeferredRecovery recovery,
      Object expectedIncarnation) {
    if (recoveryBatch == null || recovery == null || expectedIncarnation == null) {
      return false;
    }
    ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.lock();
    try {
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        EngineManager manager = recovery.transaction.manager;
        if (manager == null
            || activeEngineGameRecoveryBatch != recoveryBatch
            || recoveryBatch.current != recovery
            || recovery.replacementIncarnation != expectedIncarnation
            || !manager.isExactDeferredEngineGameAnalysisRecoveryLocked(
                recovery.engine, expectedIncarnation, recovery)) {
          return false;
        }
        return recovery.engine.completeAnalysisOutputRecovery(
            expectedIncarnation,
            recovery,
            false,
            () ->
                activeEngineGameRecoveryBatch == recoveryBatch
                    && recoveryBatch.current == recovery
                    && recovery.replacementIncarnation == expectedIncarnation
                    && manager.isExactDeferredEngineGameAnalysisRecoveryLocked(
                        recovery.engine, expectedIncarnation, recovery));
      }
    } finally {
      ENGINE_GAME_ANALYSIS_OUTPUT_MUTATION_LOCK.unlock();
    }
  }

  private static void completeDeferredEngineGameRecovery(
      EngineGameRecoveryBatch recoveryBatch,
      EngineGameDeferredRecovery recovery,
      String failureDetail,
      Throwable failure) {
    if (recovery == null || !recovery.completed.compareAndSet(false, true)) {
      return;
    }
    InitialEngineStartupSynchronization synchronization =
        takeDeferredEngineGameRecoverySynchronization(recovery);
    AtomicBoolean settled = new AtomicBoolean();
    Runnable settlement =
        () -> {
          if (settled.compareAndSet(false, true)) {
            finishDeferredEngineGameRecovery(
                recoveryBatch, recovery, failureDetail, failure);
          }
        };
    if (synchronization == null) {
      settlement.run();
      return;
    }
    boolean callbackRegistered = false;
    try {
      // Start-attempt cleanup must run only after the lifecycle claim releases its endpoint gates.
      // Both success and failure fence callbacks execute before that release.
      synchronization.runAfterCompletionRelease(settlement);
      callbackRegistered = true;
    } catch (RuntimeException | Error registrationFailure) {
      registrationFailure.printStackTrace();
    }
    try {
      synchronization.close();
    } catch (RuntimeException | Error closeFailure) {
      closeFailure.printStackTrace();
    }
    if (!callbackRegistered) {
      settlement.run();
    }
  }

  private static void finishDeferredEngineGameRecovery(
      EngineGameRecoveryBatch recoveryBatch,
      EngineGameDeferredRecovery recovery,
      String failureDetail,
      Throwable failure) {
    Leelaz.UpdateEngineStartAttempt startAttempt = recovery.startAttempt;
    if (startAttempt != null && !recovery.startAttemptCommitted) {
      Throwable cleanupCause =
          failure != null
              ? failure
              : new IllegalStateException(
                  failureDetail == null
                      ? "deferred engine-game recovery was superseded"
                      : failureDetail);
      try {
        startAttempt.failClose(cleanupCause);
      } catch (RuntimeException | Error cleanupFailure) {
        if (cleanupCause != cleanupFailure) {
          try {
            cleanupCause.addSuppressed(cleanupFailure);
          } catch (RuntimeException | Error ignored) {
          }
        }
        cleanupFailure.printStackTrace();
      }
    }
    if (failureDetail != null) {
      Object replacementIncarnation = recovery.replacementIncarnation;
      if (replacementIncarnation != null) {
        recovery.engine.runIfCurrentEngineIncarnation(
            replacementIncarnation,
            () -> {
              recovery.engine.isLoaded = false;
              recovery.engine.isDownWithError = true;
            });
      }
      if (failure != null) {
        failure.printStackTrace();
      }
    }
    boolean dispatchNext = false;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (activeEngineGameRecoveryBatch == recoveryBatch
          && recoveryBatch.current == recovery) {
        recoveryBatch.current = null;
        dispatchNext = true;
      }
    }
    if (dispatchNext) {
      dispatchNextDeferredEngineGameRecovery(recoveryBatch);
    }
    try {
      recovery.transaction.manager.afterDeferredEngineGameRecovery(recovery, failureDetail);
    } catch (RuntimeException | Error observerFailure) {
      observerFailure.printStackTrace();
    }
  }

  private static void closeDeferredEngineGameRecoverySynchronization(
      EngineGameDeferredRecovery recovery) {
    InitialEngineStartupSynchronization synchronization =
        takeDeferredEngineGameRecoverySynchronization(recovery);
    if (synchronization == null) {
      return;
    }
    try {
      synchronization.close();
    } catch (RuntimeException | Error closeFailure) {
      closeFailure.printStackTrace();
    }
  }

  private static InitialEngineStartupSynchronization
      takeDeferredEngineGameRecoverySynchronization(EngineGameDeferredRecovery recovery) {
    synchronized (recovery) {
      InitialEngineStartupSynchronization synchronization = recovery.synchronization;
      recovery.synchronization = null;
      return synchronization;
    }
  }

  protected Thread createDeferredEngineGameRecoveryWorker(Runnable task, String name) {
    return new Thread(task, name);
  }

  /** Deterministic test seam; production never blocks or mutates a recovery at a stage gate. */
  protected void beforeDeferredEngineGameRecoveryStage(
      EngineGameDeferredRecovery recovery, DeferredEngineGameRecoveryStage stage) {}

  /** Deterministic completion observer for recovery state-machine tests. */
  protected void afterDeferredEngineGameRecovery(
      EngineGameDeferredRecovery recovery, String failureDetail) {}

  static boolean hasDeferredEngineGameRecoveryGateForTest() {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      return activeEngineGameRecoveryBatch != null;
    }
  }

  private static void runAfterEngineGameTransactionRetirement(
      EngineGameTransaction transaction,
      Runnable continuation,
      Runnable schedulingFailure) {
    if (transaction == null || continuation == null) {
      return;
    }
    if (!transaction.retirementContinuation.compareAndSet(
        null, new EngineGameRetirementContinuation(continuation, schedulingFailure))) {
      throw new IllegalStateException("Engine-game retirement continuation already installed");
    }
    if (transaction.retirementFinished.get()) {
      dispatchEngineGameRetirementContinuation(transaction);
    }
  }

  private static boolean isEngineGameBatchContinuationCurrent(
      EngineManager manager, EngineGameInfo gameInfo, long expectedInactiveEpoch) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      return manager != null
          && Lizzie.engineManager == manager
          && engineGameInfo == gameInfo
          && engineGameTransactionSequence == expectedInactiveEpoch
          && activeEngineGameTransaction == null
          && retiringEngineGameTransaction == null
          && activeEngineGameRecoveryBatch == null
          && !isEngineGame
          && !isPreEngineGame;
    }
  }

  private static void dispatchEngineGameRetirementContinuation(
      EngineGameTransaction transaction) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (activeEngineGameRecoveryBatch != null
          && activeEngineGameRecoveryBatch.transaction == transaction) {
        return;
      }
    }
    EngineGameRetirementContinuation continuation =
        transaction.retirementContinuation.getAndSet(null);
    if (continuation == null) {
      return;
    }
    EngineManager manager = transaction.manager;
    AtomicInteger owner = new AtomicInteger();
    Runnable guarded =
        () -> {
          if (!owner.compareAndSet(0, 1)) {
            return;
          }
          continuation.action.run();
        };
    try {
      Thread worker =
          manager.createEngineGameRetirementContinuationWorker(
              guarded, "engine-game-retirement-" + transaction.epoch);
      worker.setDaemon(true);
      worker.start();
    } catch (RuntimeException | Error schedulingFailure) {
      if (!owner.compareAndSet(0, 2)) {
        return;
      }
      try {
        ForkJoinPool.commonPool().execute(continuation.action);
      } catch (RuntimeException | Error fallbackFailure) {
        appendEngineGameFailure(schedulingFailure, fallbackFailure);
        if (continuation.schedulingFailure != null) {
          try {
            continuation.schedulingFailure.run();
          } catch (RuntimeException | Error restoreFailure) {
            appendEngineGameFailure(schedulingFailure, restoreFailure);
          }
        }
        schedulingFailure.printStackTrace();
      }
    }
  }

  protected Thread createEngineGameRetirementContinuationWorker(Runnable task, String name) {
    return new Thread(task, name);
  }

  private static Throwable appendEngineGameFailure(Throwable primary, Throwable cleanup) {
    if (cleanup == null) {
      return primary;
    }
    if (primary == null) {
      return cleanup;
    }
    if (primary != cleanup) {
      try {
        primary.addSuppressed(cleanup);
      } catch (RuntimeException | Error ignored) {
      }
    }
    return primary;
  }

  private static void interruptEngineGameWorkers(EngineGameTransaction transaction) {
    Thread current = Thread.currentThread();
    for (Thread worker : transaction.workers) {
      if (worker != null && worker != current) {
        worker.interrupt();
      }
    }
  }

  private static void restoreFailedEngineGameTransaction(
      EngineGameTransaction transaction, Throwable failure, Runnable afterRestore) {
    if (transaction.manager != null) {
      try {
        transaction.manager.restoreUiAfterEngineGameStartAbort(
            new EngineGameInactiveUiToken(
                transaction.manager,
                transaction.gameInfo,
                transaction.inactiveEpoch,
                transaction),
            afterRestore);
        return;
      } catch (Throwable cleanupFailure) {
        if (failure != null && cleanupFailure != failure) {
          try {
            failure.addSuppressed(cleanupFailure);
          } catch (Throwable ignored) {
          }
        }
      }
    }
    if (afterRestore != null) {
      afterRestore.run();
    }
  }

  static boolean activateEngineGameTransaction(
      EngineGameTransaction transaction,
      Leelaz selectedEngine,
      int selectedIndex,
      Object blackIncarnation,
      Object whiteIncarnation) {
    if (transaction == null
        || selectedEngine == null
        || blackIncarnation == null
        || whiteIncarnation == null) {
      return false;
    }
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (!isCurrentEngineGameTransactionLocked(transaction)
            || transaction.phase != EngineGamePhase.DISPATCHED
            || System.nanoTime() >= engineGameDeadlineNanos(transaction)
            || (selectedIndex == transaction.blackIndex
                ? selectedEngine != transaction.blackEngine
                : selectedIndex == transaction.whiteIndex
                    ? selectedEngine != transaction.whiteEngine
                    : true)
            || !isExactCatalogSlot(transaction.manager, selectedIndex, selectedEngine)
            || (transaction.blackStartupIncarnation != null
                && transaction.blackStartupIncarnation != blackIncarnation)
            || (transaction.whiteStartupIncarnation != null
                && transaction.whiteStartupIncarnation != whiteIncarnation)) {
          return false;
        }
        AtomicBoolean activated = new AtomicBoolean();
        boolean primaryCurrent =
            Lizzie.runIfPrimaryEngineWithMutation(
                transaction.previousPrimary,
                transaction.previousPrimaryGeneration,
                primaryMutation ->
                    Leelaz.runIfCurrentLiveEngineIncarnations(
                        transaction.blackEngine,
                        blackIncarnation,
                        transaction.whiteEngine,
                        whiteIncarnation,
                        () -> {
                          if (isCurrentEngineGameTransactionLocked(transaction)
                              && transaction.phase == EngineGamePhase.DISPATCHED) {
                            primaryMutation.replaceWith(selectedEngine);
                            currentEngineNo = selectedIndex;
                            isEmpty = false;
                            transaction.blackIncarnation = blackIncarnation;
                            transaction.whiteIncarnation = whiteIncarnation;
                            transaction.phase = EngineGamePhase.ACTIVE;
                            isEngineGame = true;
                            isPreEngineGame = false;
                            activated.set(true);
                          }
                        }));
        return primaryCurrent && activated.get();
    }
  }

  private static EngineGameStopClaim invalidateEngineGameTransaction() {
    while (true) {
      EngineGameTransaction transaction;
      EngineGameInfo stoppedGame;
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        transaction = activeEngineGameTransaction;
        stoppedGame = transaction == null ? engineGameInfo : transaction.gameInfo;
        if (transaction == null) {
          boolean wasActive = isEngineGame;
          boolean wasStarting = isPreEngineGame;
          if (retiringEngineGameTransaction != null || (!wasActive && !wasStarting)) {
            return new EngineGameStopClaim(
                null, stoppedGame, false, false, engineGameTransactionSequence);
          }
          engineGameTransactionSequence++;
          isEngineGame = false;
          isPreEngineGame = false;
          return new EngineGameStopClaim(
              null, stoppedGame, wasActive, wasStarting, engineGameTransactionSequence);
        }
      }
      if (claimTerminalEngineGameTransaction(transaction, EngineGamePhase.CANCELLED, null, true)) {
        synchronized (ENGINE_SELECTION_STATE_LOCK) {
          return new EngineGameStopClaim(
              transaction,
              transaction.gameInfo,
              transaction.gameWasActiveBeforeTerminal,
              transaction.gameWasStartingBeforeTerminal,
              engineGameTransactionSequence);
        }
      }
      // Another terminal owner won between capture and claim. Retry to observe the settled state;
      // never mutate or restore UI as a second owner.
    }
  }

  private static void invalidateEngineGameTransactionIfCurrent(EngineGameTransaction transaction) {
    claimTerminalEngineGameTransaction(transaction, EngineGamePhase.FAILED, null, false);
    finishAutomaticEngineGameRetirementIfQuiescent(transaction);
  }

  protected long engineGameStartupTimeoutMillis() {
    return TimeUnit.SECONDS.toMillis(30L);
  }

  /**
   * Returns the first-stage budget for this exact pair of participants.
   *
   * <p>PK startup delegates each endpoint to the normal startup synchronizer, whose supported
   * budgets are 60 seconds for remote engines, 90/180 seconds for bundled runtimes, and longer
   * while OpenCL tuning is observed. A shorter transaction-wide constant must not cancel that
   * legitimate endpoint work first.
   */
  protected long engineGameStartupTimeoutMillis(EngineGameInfo gameInfo) {
    long timeoutMillis = Math.max(1L, engineGameStartupTimeoutMillis());
    if (gameInfo == null || engineList == null) {
      return timeoutMillis;
    }
    int[] participantIndexes = {gameInfo.blackEngineIndex, gameInfo.whiteEngineIndex};
    for (int participantIndex : participantIndexes) {
      if (participantIndex < 0 || participantIndex >= engineList.size()) {
        continue;
      }
      Leelaz participant = engineList.get(participantIndex);
      if (participant != null) {
        timeoutMillis =
            Math.max(timeoutMillis, Math.max(1L, engineSynchronizationTimeoutMillis(participant)));
      }
    }
    return timeoutMillis;
  }

  static long engineGameDeadlineNanos(EngineGameTransaction transaction) {
    if (transaction == null) {
      return Long.MIN_VALUE;
    }
    int[] participantIndexes = {
      transaction.blackIndex, transaction.whiteIndex
    };
    for (int participant = 0; participant < participantIndexes.length; participant++) {
      Leelaz engine = exactEngineGameParticipant(transaction, participantIndexes[participant]);
      int participantBit = 1 << participant;
      if (engine != null
          && engine.isTuning
          && claimEngineGameTuningBudget(transaction, participantBit)) {
        long tuningMillis = Math.max(1L, engine.engineTuningSynchronizationTimeoutMillis());
        long tuningNanos = TimeUnit.MILLISECONDS.toNanos(tuningMillis);
        long now = System.nanoTime();
        long extended = now > Long.MAX_VALUE - tuningNanos ? Long.MAX_VALUE : now + tuningNanos;
        extendEngineGameDeadline(transaction.deadlineNanos, extended);
      }
    }
    return transaction.deadlineNanos.get();
  }

  private static boolean claimEngineGameTuningBudget(
      EngineGameTransaction transaction, int participantBit) {
    while (true) {
      int claimed = transaction.tuningBudgetParticipants.get();
      if ((claimed & participantBit) != 0) {
        return false;
      }
      if (transaction.tuningBudgetParticipants.compareAndSet(
          claimed, claimed | participantBit)) {
        return true;
      }
    }
  }

  private static void extendEngineGameDeadline(AtomicLong deadline, long candidate) {
    while (true) {
      long current = deadline.get();
      if (candidate <= current || deadline.compareAndSet(current, candidate)) {
        return;
      }
    }
  }

  private static Leelaz exactEngineGameParticipant(
      EngineGameTransaction transaction, int participantIndex) {
    if (transaction == null) {
      return null;
    }
    Leelaz participant =
        participantIndex == transaction.blackIndex
            ? transaction.blackEngine
            : participantIndex == transaction.whiteIndex ? transaction.whiteEngine : null;
    return participant != null
            && isExactCatalogSlot(transaction.manager, participantIndex, participant)
        ? participant
        : null;
  }

  public static boolean setEngineGamePonderEnabled(boolean enabled) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (Lizzie.config == null) {
        return false;
      }
      // Changing routing mode in the middle of a game can leave PRIMARY pointing at the
      // non-moving participant until the next engine response. Apply it to future games only.
      if (activeEngineGameTransaction != null || isEngineGame || isPreEngineGame) {
        return Lizzie.config.enginePkPonder;
      }
      Lizzie.config.enginePkPonder = enabled;
      return enabled;
    }
  }

  static final class DeferredEngineGamePrimaryPublication {
    private final EngineManager expectedManager;
    private final EngineGameInfo expectedGameInfo;
    private final EngineGameTransaction expectedTransaction;
    private final long expectedEpoch;
    private final int expectedIndex;
    private final Leelaz engine;
    private final Leelaz expectedPreviousPrimary;
    private final long expectedPrimaryGeneration;
    private final Object expectedEngineIncarnation;
    private final int expectedBlackIndex;
    private final int expectedWhiteIndex;
    private final boolean expectedPonderRouting;
    private final Board expectedBoard;
    private final BoardHistoryList expectedBoardHistory;
    private final long expectedBoardRevision;
    private final boolean expectedBlackToPlay;

    private DeferredEngineGamePrimaryPublication(
        EngineManager expectedManager,
        EngineGameInfo expectedGameInfo,
        EngineGameTransaction expectedTransaction,
        int expectedIndex,
        Leelaz engine,
        Leelaz expectedPreviousPrimary,
        long expectedPrimaryGeneration,
        Object expectedEngineIncarnation,
        boolean expectedPonderRouting,
        Board expectedBoard,
        long expectedBoardRevision,
        boolean expectedBlackToPlay) {
      this.expectedManager = expectedManager;
      this.expectedGameInfo = expectedGameInfo;
      this.expectedTransaction = expectedTransaction;
      this.expectedEpoch = expectedTransaction == null ? -1L : expectedTransaction.epoch;
      this.expectedIndex = expectedIndex;
      this.engine = engine;
      this.expectedPreviousPrimary = expectedPreviousPrimary;
      this.expectedPrimaryGeneration = expectedPrimaryGeneration;
      this.expectedEngineIncarnation = expectedEngineIncarnation;
      this.expectedBlackIndex = expectedGameInfo == null ? -1 : expectedGameInfo.blackEngineIndex;
      this.expectedWhiteIndex = expectedGameInfo == null ? -1 : expectedGameInfo.whiteEngineIndex;
      this.expectedPonderRouting = expectedPonderRouting;
      this.expectedBoard = expectedBoard;
      this.expectedBoardHistory = expectedBoard == null ? null : expectedBoard.getHistory();
      this.expectedBoardRevision = expectedBoardRevision;
      this.expectedBlackToPlay = expectedBlackToPlay;
    }

    boolean publish() {
      return publishEngineGamePrimaryIfCurrent(this);
    }
  }

  /**
   * Selection-locked engine-game identity captured when a reader line enters parsing.
   *
   * <p>Batch games deliberately reuse {@link EngineGameInfo}. Keeping only that object identity
   * would let a delayed line from the previous game attach itself to the next game's transaction.
   * This context freezes the monotonic epoch and routing mode before the parser can block.
   */
  static final class EngineGamePrimaryContext {
    final EngineManager manager;
    final EngineGameInfo gameInfo;
    final EngineGameTransaction transaction;
    final long epoch;
    final int blackIndex;
    final int whiteIndex;
    final boolean ponderRouting;
    final Leelaz participant;
    final int participantIndex;
    final Object participantIncarnation;
    final Leelaz blackEngine;
    final Leelaz whiteEngine;
    final Board board;
    final BoardHistoryList boardHistory;
    final BoardHistoryNode boardNode;
    final int moveNumber;
    final long boardRevision;
    final boolean blackToPlay;

    private EngineGamePrimaryContext(
        EngineManager manager,
        EngineGameInfo gameInfo,
        EngineGameTransaction transaction,
        boolean ponderRouting,
        Leelaz participant,
        int participantIndex,
        Object participantIncarnation,
        Board board) {
      this.manager = manager;
      this.gameInfo = gameInfo;
      this.transaction = transaction;
      this.epoch = transaction.epoch;
      this.blackIndex = gameInfo.blackEngineIndex;
      this.whiteIndex = gameInfo.whiteEngineIndex;
      this.ponderRouting = ponderRouting;
      this.participant = participant;
      this.participantIndex = participantIndex;
      this.participantIncarnation = participantIncarnation;
      this.blackEngine = transaction.blackEngine;
      this.whiteEngine = transaction.whiteEngine;
      this.board = board;
      this.boardHistory = board == null ? null : board.getHistory();
      this.boardNode = boardHistory == null ? null : boardHistory.getCurrentHistoryNode();
      this.moveNumber = boardHistory == null ? -1 : boardHistory.getMoveNumber();
      this.boardRevision = board == null ? -1L : board.getContextRevision();
      this.blackToPlay = boardHistory != null && boardHistory.isBlacksTurn();
    }
  }

  /**
   * Exact ownership frozen when a genmove request is admitted.
   *
   * <p>The same engine objects and reader bindings may be reused by consecutive batch games. A
   * response therefore belongs to the transaction/board frame that issued the command, not to the
   * global game that happens to be active when the line eventually arrives.
   */
  static final class EngineGameMoveResponseContext {
    final EngineManager manager;
    final EngineGameInfo gameInfo;
    final EngineGameTransaction transaction;
    final long epoch;
    final Leelaz participant;
    final int participantIndex;
    final Object participantIncarnation;
    final Leelaz blackEngine;
    final Leelaz whiteEngine;
    final Board board;
    final BoardHistoryList boardHistory;
    final BoardHistoryNode boardNode;
    final int moveNumber;
    final long boardRevision;
    final boolean blackToPlay;
    final boolean genmoveMode;

    private EngineGameMoveResponseContext(
        EngineManager manager,
        EngineGameInfo gameInfo,
        EngineGameTransaction transaction,
        Leelaz participant,
        int participantIndex,
        Object participantIncarnation,
        Board board,
        long boardRevision,
        boolean blackToPlay) {
      this.manager = manager;
      this.gameInfo = gameInfo;
      this.transaction = transaction;
      this.epoch = transaction.epoch;
      this.participant = participant;
      this.participantIndex = participantIndex;
      this.participantIncarnation = participantIncarnation;
      this.blackEngine = transaction.blackEngine;
      this.whiteEngine = transaction.whiteEngine;
      this.board = board;
      this.boardHistory = board.getHistory();
      this.boardNode = this.boardHistory.getCurrentHistoryNode();
      this.moveNumber = this.boardHistory.getMoveNumber();
      this.boardRevision = boardRevision;
      this.blackToPlay = blackToPlay;
      this.genmoveMode = transaction.gameInfo.isGenmove;
    }
  }

  /** Exact board/turn ownership produced by committing one engine-game move. */
  static final class EngineGamePostMoveToken {
    final EngineGameTransaction transaction;
    final long epoch;
    final Board board;
    final BoardHistoryList boardHistory;
    final BoardHistoryNode boardNode;
    final int moveNumber;
    final long boardRevision;
    final boolean blackToPlay;
    private final AtomicBoolean clockSyncClaimed = new AtomicBoolean();

    private EngineGamePostMoveToken(
        EngineGameTransaction transaction,
        Board board,
        BoardHistoryNode boardNode,
        long boardRevision,
        boolean blackToPlay) {
      this.transaction = transaction;
      this.epoch = transaction.epoch;
      this.board = board;
      this.boardHistory = board.getHistory();
      this.boardNode = boardNode;
      this.moveNumber = boardNode == null ? -1 : boardNode.getData().moveNumber;
      this.boardRevision = boardRevision;
      this.blackToPlay = blackToPlay;
    }
  }

  /** One exact, once-only clock synchronization admitted for a committed engine-game turn. */
  static final class EngineGameClockSync {
    final EngineGamePostMoveToken turn;
    final Leelaz endpoint;
    final Object endpointIncarnation;
    final String command;
    final String invalidReason;

    private EngineGameClockSync(
        EngineGamePostMoveToken turn,
        Leelaz endpoint,
        Object endpointIncarnation,
        String command,
        String invalidReason) {
      this.turn = turn;
      this.endpoint = endpoint;
      this.endpointIncarnation = endpointIncarnation;
      this.command = command;
      this.invalidReason = invalidReason;
    }

    boolean requiresCommand() {
      return endpoint != null && endpointIncarnation != null && command != null;
    }

    boolean isValid() {
      return invalidReason == null;
    }
  }

  /** Pins an admitted response to its retiring transaction without holding a lock across I/O. */
  static final class EngineGameMoveResponseLease implements AutoCloseable {
    private final EngineGameOperationLease operation;

    private EngineGameMoveResponseLease(EngineGameOperationLease operation) {
      this.operation = operation;
    }

    boolean isCurrent() {
      return operation.isCurrent();
    }

    @Override
    public void close() {
      operation.close();
    }
  }

  /**
   * Keeps a physically emitted engine-game command attached to its retiring transaction.
   *
   * <p>Terminal cancellation never waits for this lease. Retirement (and therefore admission of a
   * same-stream successor) waits until the exact response is drained or the binding is retired,
   * preventing unnumbered output from an old request being attributed to a new game.
   */
  static final class EngineGamePhysicalRequestLease implements AutoCloseable {
    private static final int OPEN = 0;
    private static final int FORCE_CLAIMED = 1;
    private static final int CLOSED = 2;

    private final EngineGameTransaction transaction;
    private final Leelaz endpoint;
    private final Object endpointIncarnation;
    private final EngineGameOperationLease operation;
    private final AtomicInteger state = new AtomicInteger(OPEN);

    private EngineGamePhysicalRequestLease(
        EngineGameTransaction transaction,
        Leelaz endpoint,
        Object endpointIncarnation,
        EngineGameOperationLease operation) {
      this.transaction = transaction;
      this.endpoint = endpoint;
      this.endpointIncarnation = endpointIncarnation;
      this.operation = operation;
      transaction.physicalRequests.add(this);
    }

    private boolean isOpen() {
      return state.get() == OPEN;
    }

    private boolean samePhysicalStream(EngineGamePhysicalRequestLease other) {
      return other != null
          && endpoint == other.endpoint
          && endpointIncarnation == other.endpointIncarnation;
    }

    private boolean claimForce() {
      return state.compareAndSet(OPEN, FORCE_CLAIMED);
    }

    private void finishForce() {
      if (state.compareAndSet(FORCE_CLAIMED, CLOSED)) {
        finishClose();
      }
    }

    private void finishClose() {
      transaction.physicalRequests.remove(this);
      operation.close();
    }

    @Override
    public void close() {
      if (state.compareAndSet(OPEN, CLOSED)) {
        finishClose();
      }
    }
  }

  static EngineGameMoveResponseContext captureEngineGameMoveResponseContext(
      EngineGameTransaction transaction,
      Leelaz participant,
      Object participantIncarnation,
      String color) {
    if (transaction == null
        || participant == null
        || participantIncarnation == null
        || color == null) {
      return null;
    }
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGameTransactionLocked(transaction)
          || transaction.phase != EngineGamePhase.ACTIVE
          || !transaction.gameInfo.isGenmove
          || Lizzie.board == null) {
        return null;
      }
      boolean blackToPlay = Lizzie.board.getHistory().isBlacksTurn();
      boolean requestsBlack = color.equalsIgnoreCase("b");
      int participantIndex =
          requestsBlack
              ? transaction.gameInfo.blackEngineIndex
              : transaction.gameInfo.whiteEngineIndex;
      Object expectedIncarnation =
          requestsBlack ? transaction.blackIncarnation : transaction.whiteIncarnation;
      if (requestsBlack != blackToPlay
          || expectedIncarnation != participantIncarnation
          || !isExactCatalogSlot(transaction.manager, participantIndex, participant)) {
        return null;
      }
      AtomicBoolean live = new AtomicBoolean();
      participant.runIfCurrentLiveEngineIncarnation(
          participantIncarnation, () -> live.set(true));
      if (!live.get()) {
        return null;
      }
      return new EngineGameMoveResponseContext(
          transaction.manager,
          transaction.gameInfo,
          transaction,
          participant,
          participantIndex,
          participantIncarnation,
          Lizzie.board,
          Lizzie.board.getContextRevision(),
          blackToPlay);
    }
  }

  static EngineGameMoveResponseContext captureEngineGameMoveResponseContext(
      EngineGamePostMoveToken turn,
      Leelaz participant,
      Object participantIncarnation,
      String color) {
    if (turn == null
        || participant == null
        || participantIncarnation == null
        || color == null) {
      return null;
    }
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGamePostMoveTokenLocked(turn)) {
        return null;
      }
      EngineGameTransaction transaction = turn.transaction;
      boolean requestsBlack = color.equalsIgnoreCase("b");
      int participantIndex = requestsBlack ? transaction.blackIndex : transaction.whiteIndex;
      Leelaz expectedParticipant =
          requestsBlack ? transaction.blackEngine : transaction.whiteEngine;
      Object expectedIncarnation =
          requestsBlack ? transaction.blackIncarnation : transaction.whiteIncarnation;
      if (requestsBlack != turn.blackToPlay
          || participant != expectedParticipant
          || participantIncarnation != expectedIncarnation
          || !isExactCatalogSlot(transaction.manager, participantIndex, participant)
          || !participant.isCurrentLiveEngineIncarnation(participantIncarnation)) {
        return null;
      }
      return new EngineGameMoveResponseContext(
          transaction.manager,
          transaction.gameInfo,
          transaction,
          participant,
          participantIndex,
          participantIncarnation,
          turn.board,
          turn.boardRevision,
          turn.blackToPlay);
    }
  }

  static EngineGamePhysicalRequestLease claimEngineGameMoveOutput(
      EngineGameMoveResponseContext context, Runnable carrierInstallation) {
    if (context == null || carrierInstallation == null) {
      return null;
    }
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGameMoveResponseLocked(context)
          || !context.participant.isCurrentLiveEngineIncarnation(
              context.participantIncarnation)) {
        return null;
      }
      context.transaction.operationsInFlight.incrementAndGet();
      EngineGameOperationLease operation = new EngineGameOperationLease(context.transaction);
      try {
        carrierInstallation.run();
        return new EngineGamePhysicalRequestLease(
            context.transaction,
            context.participant,
            context.participantIncarnation,
            operation);
      } catch (RuntimeException | Error installationFailure) {
        operation.close();
        throw installationFailure;
      }
    }
  }

  static EngineGamePhysicalRequestLease claimEngineGamePostMoveOutput(
      EngineGamePostMoveToken token, Leelaz endpoint, Object endpointIncarnation) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGamePostMoveTokenLocked(token)
          || endpoint == null
          || endpointIncarnation == null) {
        return null;
      }
      EngineGameTransaction transaction = token.transaction;
      Object expectedIncarnation =
          endpoint == transaction.blackEngine
              ? transaction.blackIncarnation
              : endpoint == transaction.whiteEngine ? transaction.whiteIncarnation : null;
      if (expectedIncarnation != endpointIncarnation
          || !endpoint.isCurrentLiveEngineIncarnation(endpointIncarnation)) {
        return null;
      }
      transaction.operationsInFlight.incrementAndGet();
      return new EngineGamePhysicalRequestLease(
          transaction,
          endpoint,
          endpointIncarnation,
          new EngineGameOperationLease(transaction));
    }
  }

  static EngineGamePhysicalRequestLease claimEngineGameStartupOutput(
      EngineGameTransaction transaction, Leelaz endpoint, Object endpointIncarnation) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGameTransactionLocked(transaction)
          || endpoint == null
          || endpointIncarnation == null) {
        return null;
      }
      Object frozenIncarnation;
      int endpointIndex;
      if (endpoint == transaction.blackEngine) {
        frozenIncarnation =
            transaction.blackIncarnation != null
                ? transaction.blackIncarnation
                : transaction.blackStartupIncarnation;
        endpointIndex = transaction.blackIndex;
      } else if (endpoint == transaction.whiteEngine) {
        frozenIncarnation =
            transaction.whiteIncarnation != null
                ? transaction.whiteIncarnation
                : transaction.whiteStartupIncarnation;
        endpointIndex = transaction.whiteIndex;
      } else {
        return null;
      }
      if (!isExactCatalogSlot(transaction.manager, endpointIndex, endpoint)
          || (frozenIncarnation != null && frozenIncarnation != endpointIncarnation)
          || !endpoint.isCurrentStartupEngineIncarnation(endpointIncarnation)) {
        return null;
      }
      if (endpoint == transaction.blackEngine && transaction.blackStartupIncarnation == null) {
        transaction.blackStartupIncarnation = endpointIncarnation;
      } else if (endpoint == transaction.whiteEngine
          && transaction.whiteStartupIncarnation == null) {
        transaction.whiteStartupIncarnation = endpointIncarnation;
      }
      transaction.operationsInFlight.incrementAndGet();
      return new EngineGamePhysicalRequestLease(
          transaction,
          endpoint,
          endpointIncarnation,
          new EngineGameOperationLease(transaction));
    }
  }

  static boolean bindEngineGameStartupIncarnation(
      EngineGameTransaction transaction, Leelaz endpoint, Object endpointIncarnation) {
    if (transaction == null || endpoint == null || endpointIncarnation == null) {
      return false;
    }
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGameTransactionLocked(transaction)
          || !endpoint.isCurrentStartupEngineIncarnation(endpointIncarnation)) {
        return false;
      }
      if (endpoint == transaction.blackEngine) {
        if (!isExactCatalogSlot(transaction.manager, transaction.blackIndex, endpoint)
            || (transaction.blackStartupIncarnation != null
                && transaction.blackStartupIncarnation != endpointIncarnation)) {
          return false;
        }
        transaction.blackStartupIncarnation = endpointIncarnation;
        return true;
      }
      if (endpoint == transaction.whiteEngine) {
        if (!isExactCatalogSlot(transaction.manager, transaction.whiteIndex, endpoint)
            || (transaction.whiteStartupIncarnation != null
                && transaction.whiteStartupIncarnation != endpointIncarnation)) {
          return false;
        }
        transaction.whiteStartupIncarnation = endpointIncarnation;
        return true;
      }
      return false;
    }
  }

  static EngineGameMoveResponseLease claimEngineGameMoveResponse(
      EngineGameMoveResponseContext context) {
    if (context == null || context.transaction == null) {
      return null;
    }
    EngineGameTransaction transaction = context.transaction;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGameMoveResponseLocked(context)) {
        return null;
      }
      AtomicBoolean bothLive = new AtomicBoolean();
      Leelaz.runIfCurrentLiveEngineIncarnations(
          transaction.blackEngine,
          transaction.blackIncarnation,
          transaction.whiteEngine,
          transaction.whiteIncarnation,
          () -> bothLive.set(true));
      if (!bothLive.get()) {
        return null;
      }
      transaction.operationsInFlight.incrementAndGet();
      return new EngineGameMoveResponseLease(new EngineGameOperationLease(transaction));
    }
  }

  private static boolean isCurrentEngineGameMoveResponseLocked(
      EngineGameMoveResponseContext context) {
    EngineGameTransaction transaction = context.transaction;
    Object expectedParticipantIncarnation =
        context.participantIndex == context.gameInfo.blackEngineIndex
            ? transaction.blackIncarnation
            : transaction.whiteIncarnation;
    return isCurrentEngineGameTransactionLocked(transaction)
        && transaction.phase == EngineGamePhase.ACTIVE
        && transaction.manager == context.manager
        && transaction.gameInfo == context.gameInfo
        && transaction.epoch == context.epoch
        && engineGameTransactionSequence == context.epoch
        && context.gameInfo.isGenmove == context.genmoveMode
        && expectedParticipantIncarnation == context.participantIncarnation
        && isExactCatalogSlot(context.manager, context.participantIndex, context.participant)
        && Lizzie.board == context.board
        && context.board.getHistory() == context.boardHistory
        && context.boardHistory.getCurrentHistoryNode() == context.boardNode
        && context.boardHistory.getMoveNumber() == context.moveNumber
        && context.board.getContextRevision() == context.boardRevision
        && context.boardHistory.isBlacksTurn() == context.blackToPlay;
  }

  static boolean isCurrentEngineGameMoveResponse(EngineGameMoveResponseContext context) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      return isCurrentEngineGameMoveResponseLocked(context);
    }
  }

  /** Returns whether the exact captured no-capture game has exhausted every board point. */
  static boolean isExactEngineGameBoardFull(EngineGameMoveResponseContext context) {
    AtomicBoolean full = new AtomicBoolean();
    if (!runIfCurrentEngineGameMoveResponse(
        context,
        () -> {
          if (!context.transaction.noCapture) {
            return;
          }
          full.set(true);
          for (Stone stone : context.boardHistory.getData().stones) {
            if (stone == Stone.EMPTY) {
              full.set(false);
              return;
            }
          }
        })) {
      return false;
    }
    return full.get();
  }

  /** Returns whether the exact post-move no-capture game has filled the board. */
  static boolean isExactEngineGameBoardFull(EngineGamePostMoveToken token) {
    AtomicBoolean full = new AtomicBoolean();
    if (!runIfCurrentEngineGamePostMoveToken(
        token,
        () -> {
          if (!token.transaction.noCapture) {
            return;
          }
          full.set(true);
          for (Stone stone : token.boardHistory.getData().stones) {
            if (stone == Stone.EMPTY) {
              full.set(false);
              return;
            }
          }
        })) {
      return false;
    }
    return full.get();
  }

  /**
   * Commits the board mutation and the selected-model publication as one short transaction
   * mutation. The board core is pure history mutation; all engine I/O and UI work follows the
   * returned exact post-move token.
   */
  static EngineGamePostMoveToken commitEngineGameMove(
      EngineGameMoveResponseContext response,
      Integer moveX,
      Integer moveY,
      Leelaz selectedEngine,
      int selectedIndex) {
    if (response == null || selectedEngine == null || (moveX == null) != (moveY == null)) {
      return null;
    }
    EngineGameTransaction transaction = response.transaction;
    EngineGamePostMoveToken[] committed = new EngineGamePostMoveToken[1];
    Throwable[] failure = new Throwable[1];
    Board.EngineGameMoveCommit[] boardCommit = new Board.EngineGameMoveCommit[1];
    transaction.mutationLock.lock();
    try {
      Object selectedIncarnation;
      Leelaz expectedPrimary;
      long expectedPrimaryGeneration;
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (!isCurrentEngineGameMoveResponseLocked(response)) {
          return null;
        }
        selectedIncarnation =
            selectedIndex == transaction.blackIndex
                ? transaction.blackIncarnation
                : selectedIndex == transaction.whiteIndex
                    ? transaction.whiteIncarnation
                    : null;
        Leelaz frozenSelected =
            selectedIndex == transaction.blackIndex
                ? transaction.blackEngine
                : selectedIndex == transaction.whiteIndex ? transaction.whiteEngine : null;
        if (selectedIncarnation == null
            || selectedEngine != frozenSelected
            || !isExactCatalogSlot(transaction.manager, selectedIndex, selectedEngine)) {
          return null;
        }
        expectedPrimary = Lizzie.leelaz;
        expectedPrimaryGeneration = Lizzie.capturePrimaryEngineGeneration(expectedPrimary);
        if (expectedPrimaryGeneration < 0L) {
          return null;
        }
      }
      try {
        boardCommit[0] =
            moveX == null
                ? response.board.commitEngineGamePass(
                    response.boardHistory,
                    response.boardNode,
                    response.blackToPlay,
                    response.blackToPlay ? Stone.BLACK : Stone.WHITE,
                    transaction.newMoveNumberInBranch)
                : response.board.commitEngineGamePlace(
                    response.boardHistory,
                    response.boardNode,
                    response.blackToPlay,
                    moveX,
                    moveY,
                    response.blackToPlay ? Stone.BLACK : Stone.WHITE,
                    transaction.noCapture,
                    transaction.canSuicidal,
                    transaction.newMoveNumberInBranch);
        if (boardCommit[0] == null) {
          throw new IllegalStateException("Engine-game board rejected the exact move commit");
        }
        BoardHistoryNode nextNode = boardCommit[0].node();
        synchronized (ENGINE_SELECTION_STATE_LOCK) {
          if (!isCurrentEngineGameTransactionLocked(transaction)
              || Lizzie.board != response.board
              || response.board.getHistory() != response.boardHistory
              || response.boardHistory.getCurrentHistoryNode() != nextNode) {
            throw new IllegalStateException(
                "Engine-game ownership changed during board move commit");
          }
          long nextRevision = response.board.getContextRevision();
          boolean nextBlackToPlay = response.boardHistory.isBlacksTurn();
          if (response.boardHistory.getMoveNumber() != response.moveNumber + 1
              || nextBlackToPlay == response.blackToPlay) {
            throw new IllegalStateException(
                "Engine-game board mutation did not advance the captured turn");
          }
          AtomicBoolean published = new AtomicBoolean();
          boolean primaryCurrent =
              Lizzie.runIfPrimaryEngineWithMutation(
                  expectedPrimary,
                  expectedPrimaryGeneration,
                  primaryMutation ->
                      selectedEngine.runIfCurrentLiveEngineIncarnation(
                          selectedIncarnation,
                          () -> {
                            if (isCurrentEngineGameTransactionLocked(transaction)
                                && transaction.phase == EngineGamePhase.ACTIVE
                                && response.boardHistory.getCurrentHistoryNode() == nextNode
                                && response.board.getContextRevision() == nextRevision
                                && response.boardHistory.isBlacksTurn() == nextBlackToPlay) {
                              primaryMutation.replaceWith(selectedEngine);
                              currentEngineNo = selectedIndex;
                              isEmpty = false;
                              committed[0] =
                                  new EngineGamePostMoveToken(
                                      transaction,
                                      response.board,
                                      nextNode,
                                      nextRevision,
                                      nextBlackToPlay);
                              published.set(true);
                            }
                          }));
          if (!primaryCurrent || !published.get()) {
            throw new IllegalStateException(
                "Engine-game primary changed during board move commit");
          }
        }
      } catch (RuntimeException | Error commitFailure) {
        failure[0] = commitFailure;
        if (boardCommit[0] != null
            && !response.board.rollbackEngineGameMove(
                response.boardHistory, response.boardNode, boardCommit[0])) {
          failure[0] =
              appendEngineGameFailure(
                  failure[0],
                  new IllegalStateException(
                      "Engine-game board move could not be rolled back after publication failure"));
        }
      }
    } finally {
      transaction.mutationLock.unlock();
    }
    if (failure[0] != null) {
      failEngineGameTransaction(transaction, failure[0]);
      if (failure[0] instanceof RuntimeException) {
        throw (RuntimeException) failure[0];
      }
      throw (Error) failure[0];
    }
    return committed[0];
  }

  static boolean isCurrentEngineGamePostMoveToken(EngineGamePostMoveToken token) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      return isCurrentEngineGamePostMoveTokenLocked(token);
    }
  }

  static EngineGameClockSync claimEngineGamePostMoveClockSync(
      EngineGamePostMoveToken token) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGamePostMoveTokenLocked(token)
          || !token.transaction.gameInfo.isGenmove
          || !token.clockSyncClaimed.compareAndSet(false, true)) {
        return null;
      }
      EngineGameTransaction transaction = token.transaction;
      EngineCountDown clock =
          token.blackToPlay ? transaction.blackCountDown : transaction.whiteCountDown;
      if (clock == null) {
        return new EngineGameClockSync(token, null, null, null, null);
      }
      Leelaz endpoint = token.blackToPlay ? transaction.blackEngine : transaction.whiteEngine;
      Object endpointIncarnation =
          token.blackToPlay ? transaction.blackIncarnation : transaction.whiteIncarnation;
      if (!clock.belongsTo(endpoint, token.blackToPlay)
          || endpointIncarnation == null
          || !endpoint.isCurrentLiveEngineIncarnation(endpointIncarnation)) {
        // The once-only claim has already been consumed. Return an explicit invalid result so the
        // caller fails this transaction instead of silently abandoning the committed move with no
        // play/genmove successor. A plain null remains reserved for stale or duplicate callers.
        return new EngineGameClockSync(
            token,
            null,
            null,
            null,
            "Engine-game clock lost exact participant ownership");
      }
      String command = clock.claimTimeLeftCommand();
      return command == null
          ? new EngineGameClockSync(token, null, null, null, null)
          : new EngineGameClockSync(token, endpoint, endpointIncarnation, command, null);
    }
  }

  static boolean isCurrentEngineGamePostMoveCommand(
      EngineGamePostMoveToken token, Leelaz endpoint, Object endpointIncarnation) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGamePostMoveTokenLocked(token)
          || endpoint == null
          || endpointIncarnation == null) {
        return false;
      }
      EngineGameTransaction transaction = token.transaction;
      Object expectedIncarnation =
          endpoint == transaction.blackEngine
              ? transaction.blackIncarnation
              : endpoint == transaction.whiteEngine ? transaction.whiteIncarnation : null;
      return expectedIncarnation == endpointIncarnation
          && endpoint.isCurrentLiveEngineIncarnation(endpointIncarnation);
    }
  }

  private static boolean isCurrentEngineGamePostMoveTokenLocked(
      EngineGamePostMoveToken token) {
    return token != null
        && token.transaction != null
        && token.epoch == token.transaction.epoch
        && isCurrentEngineGameTransactionLocked(token.transaction)
        && token.transaction.phase == EngineGamePhase.ACTIVE
        && Lizzie.board == token.board
        && token.board.getHistory() == token.boardHistory
        && token.boardHistory.getCurrentHistoryNode() == token.boardNode
        && token.boardHistory.getMoveNumber() == token.moveNumber
        && token.board.getContextRevision() == token.boardRevision
        && token.boardHistory.isBlacksTurn() == token.blackToPlay;
  }

  static boolean publishEngineGameMovePrimaryIfCurrent(
      EngineGameMoveResponseContext response,
      Leelaz selectedEngine,
      int selectedIndex) {
    if (response == null || selectedEngine == null) {
      return false;
    }
    Leelaz expectedPrimary = Lizzie.leelaz;
    long expectedPrimaryGeneration = Lizzie.capturePrimaryEngineGeneration(expectedPrimary);
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      EngineGameTransaction transaction = response.transaction;
      if (!isCurrentEngineGameTransactionLocked(transaction)
          || transaction.phase != EngineGamePhase.ACTIVE
          || transaction.manager != response.manager
          || transaction.gameInfo != response.gameInfo
          || transaction.epoch != response.epoch
          || engineGameTransactionSequence != response.epoch
          || !isExactCatalogSlot(response.manager, selectedIndex, selectedEngine)) {
        return false;
      }
      Object selectedIncarnation =
          selectedIndex == response.gameInfo.blackEngineIndex
              ? transaction.blackIncarnation
              : selectedIndex == response.gameInfo.whiteEngineIndex
                  ? transaction.whiteIncarnation
                  : null;
      if (selectedIncarnation == null) {
        return false;
      }
      AtomicBoolean published = new AtomicBoolean();
      boolean primaryCurrent =
          Lizzie.runIfPrimaryEngineWithMutation(
              expectedPrimary,
              expectedPrimaryGeneration,
              primaryMutation ->
                  selectedEngine.runIfCurrentLiveEngineIncarnation(
                      selectedIncarnation,
                      () -> {
                        if (isCurrentEngineGameTransactionLocked(transaction)
                            && transaction.phase == EngineGamePhase.ACTIVE) {
                          primaryMutation.replaceWith(selectedEngine);
                          currentEngineNo = selectedIndex;
                          isEmpty = false;
                          published.set(true);
                        }
                      }));
      return primaryCurrent && published.get();
    }
  }

  static EngineGamePrimaryContext captureEngineGamePrimaryContext() {
    return captureEngineGamePrimaryContext(null, null);
  }

  /**
   * Freezes ownership of an unnumbered analysis stream at the physical command write.
   *
   * <p>The first analysis command can be written while the game is still {@link
   * EngineGamePhase#DISPATCHED}. Capturing the transaction and board frame here prevents an early
   * info line from falling through to ordinary autoplay before activation, and lets the same
   * owner become actionable once activation reaches the identical frame.
   */
  static EngineGamePrimaryContext captureEngineGameAnalysisOutputContext(
      EngineGameTransaction transaction, Leelaz participant, Object participantIncarnation) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGameTransactionLocked(transaction)
          || transaction.gameInfo.isGenmove
          || participant == null
          || participantIncarnation == null
          || Lizzie.board == null) {
        return null;
      }
      int participantIndex =
          participant == transaction.blackEngine
              ? transaction.blackIndex
              : participant == transaction.whiteEngine ? transaction.whiteIndex : -1;
      Object expectedIncarnation =
          participantIndex == transaction.blackIndex
              ? (transaction.blackIncarnation != null
                  ? transaction.blackIncarnation
                  : transaction.blackStartupIncarnation)
              : participantIndex == transaction.whiteIndex
                  ? (transaction.whiteIncarnation != null
                      ? transaction.whiteIncarnation
                      : transaction.whiteStartupIncarnation)
                  : null;
      if (participantIndex < 0
          || expectedIncarnation != participantIncarnation
          || !isExactCatalogSlot(transaction.manager, participantIndex, participant)
          || !participant.isCurrentLiveEngineIncarnation(participantIncarnation)) {
        return null;
      }
      return new EngineGamePrimaryContext(
          transaction.manager,
          transaction.gameInfo,
          transaction,
          Lizzie.config != null && Lizzie.config.enginePkPonder,
          participant,
          participantIndex,
          participantIncarnation,
          Lizzie.board);
    }
  }

  static boolean isCurrentEngineGameAnalysisOutputContext(EngineGamePrimaryContext context) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      return isCurrentEngineGameAnalysisOutputContextLocked(context);
    }
  }

  static boolean runIfCurrentEngineGameAnalysisOutputContext(
      EngineGamePrimaryContext context, Runnable action) {
    if (context == null || context.transaction == null || action == null) {
      return false;
    }
    EngineGameTransaction transaction = context.transaction;
    Throwable failure = null;
    boolean executed = false;
    transaction.mutationLock.lock();
    try {
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (!isCurrentEngineGameAnalysisOutputContextLocked(context)) {
          return false;
        }
      }
      executed = true;
      try {
        action.run();
      } catch (RuntimeException | Error mutationFailure) {
        failure = mutationFailure;
      }
    } finally {
      transaction.mutationLock.unlock();
    }
    if (failure != null) {
      failEngineGameTransaction(transaction, failure);
    }
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    return executed;
  }

  static EngineGamePrimaryContext activeEngineGameAnalysisOutputContext(
      EngineGamePrimaryContext context) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      return isCurrentEngineGameAnalysisOutputContextLocked(context)
              && context.transaction.phase == EngineGamePhase.ACTIVE
          ? context
          : null;
    }
  }

  private static boolean isCurrentEngineGameAnalysisOutputContextLocked(
      EngineGamePrimaryContext context) {
    if (context == null
        || context.gameInfo.isGenmove
        || !isCurrentEngineGameTransactionLocked(context.transaction)
        || context.manager != context.transaction.manager
        || context.gameInfo != context.transaction.gameInfo
        || context.epoch != context.transaction.epoch
        || context.participant == null
        || context.participantIncarnation == null
        || context.board == null
        || Lizzie.board != context.board
        || context.board.getHistory() != context.boardHistory
        || context.boardHistory.getCurrentHistoryNode() != context.boardNode
        || context.boardHistory.getMoveNumber() != context.moveNumber
        || context.board.getContextRevision() != context.boardRevision
        || context.boardHistory.isBlacksTurn() != context.blackToPlay
        || (Lizzie.config != null && Lizzie.config.enginePkPonder) != context.ponderRouting) {
      return false;
    }
    int participantIndex =
        context.participant == context.transaction.blackEngine
            ? context.transaction.blackIndex
            : context.participant == context.transaction.whiteEngine
                ? context.transaction.whiteIndex
                : -1;
    Object expectedIncarnation =
        participantIndex == context.transaction.blackIndex
            ? (context.transaction.blackIncarnation != null
                ? context.transaction.blackIncarnation
                : context.transaction.blackStartupIncarnation)
            : participantIndex == context.transaction.whiteIndex
                ? (context.transaction.whiteIncarnation != null
                    ? context.transaction.whiteIncarnation
                    : context.transaction.whiteStartupIncarnation)
                : null;
    return participantIndex == context.participantIndex
        && participantIndex >= 0
        && expectedIncarnation == context.participantIncarnation
        && isExactCatalogSlot(context.manager, participantIndex, context.participant)
        && context.participant.isCurrentLiveEngineIncarnation(context.participantIncarnation);
  }

  static EngineGamePrimaryContext captureEngineGamePrimaryContext(
      Leelaz participant, Object participantIncarnation) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      EngineGameTransaction transaction = activeEngineGameTransaction;
      EngineManager manager = Lizzie.engineManager;
      EngineGameInfo gameInfo = engineGameInfo;
      if (!isEngineGame
          || transaction == null
          || transaction.phase != EngineGamePhase.ACTIVE
          || transaction.manager != manager
          || transaction.gameInfo != gameInfo
          || engineGameTransactionSequence != transaction.epoch
          || Lizzie.board == null) {
        return null;
      }
      int participantIndex = -1;
      if (participant != null) {
        participantIndex =
            participant == transaction.blackEngine
                ? transaction.blackIndex
                : participant == transaction.whiteEngine ? transaction.whiteIndex : -1;
        Object expectedIncarnation =
            participantIndex == transaction.blackIndex
                ? transaction.blackIncarnation
                : participantIndex == transaction.whiteIndex
                    ? transaction.whiteIncarnation
                    : null;
        if (participantIndex < 0
            || participantIncarnation == null
            || participantIncarnation != expectedIncarnation
            || !isExactCatalogSlot(manager, participantIndex, participant)
            || !participant.isCurrentLiveEngineIncarnation(participantIncarnation)) {
          return null;
        }
      }
      return new EngineGamePrimaryContext(
          manager,
          gameInfo,
          transaction,
          Lizzie.config != null && Lizzie.config.enginePkPonder,
          participant,
          participantIndex,
          participantIncarnation,
          Lizzie.board);
    }
  }

  static EngineGameMoveResponseContext captureEngineGameAnalysisMoveContext(
      EngineGamePrimaryContext context) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGamePrimaryContextLocked(context)
          || context.gameInfo.isGenmove
          || context.participant == null
          || context.participantIncarnation == null
          || context.board == null
          || context.board.getHistory() != context.boardHistory
          || context.boardHistory.getCurrentHistoryNode() != context.boardNode
          || context.boardHistory.getMoveNumber() != context.moveNumber
          || context.board.getContextRevision() != context.boardRevision
          || context.boardHistory.isBlacksTurn() != context.blackToPlay) {
        return null;
      }
      boolean participantIsBlack = context.participantIndex == context.blackIndex;
      if (participantIsBlack != context.blackToPlay) {
        return null;
      }
      return new EngineGameMoveResponseContext(
          context.manager,
          context.gameInfo,
          context.transaction,
          context.participant,
          context.participantIndex,
          context.participantIncarnation,
          context.board,
          context.boardRevision,
          context.blackToPlay);
    }
  }

  static DeferredEngineGamePrimaryPublication prepareEngineGamePrimaryPublication(
      EngineGamePrimaryContext context,
      int expectedIndex,
      Leelaz engine,
      Leelaz expectedPreviousPrimary,
      long expectedPrimaryGeneration,
      Object expectedEngineIncarnation,
      Board expectedBoard,
      long expectedBoardRevision,
      boolean expectedBlackToPlay) {
    if (context == null) {
      return null;
    }
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGamePrimaryContextLocked(context)
          || expectedEngineIncarnation == null
          || expectedBoard == null) {
        return null;
      }
      return new DeferredEngineGamePrimaryPublication(
          context.manager,
          context.gameInfo,
          context.transaction,
          expectedIndex,
          engine,
          expectedPreviousPrimary,
          expectedPrimaryGeneration,
          expectedEngineIncarnation,
          context.ponderRouting,
          expectedBoard,
          expectedBoardRevision,
          expectedBlackToPlay);
    }
  }

  private static boolean isCurrentEngineGamePrimaryContextLocked(EngineGamePrimaryContext context) {
    return context != null
        && isEngineGame
        && Lizzie.engineManager == context.manager
        && engineGameInfo == context.gameInfo
        && activeEngineGameTransaction == context.transaction
        && context.transaction != null
        && context.transaction.phase == EngineGamePhase.ACTIVE
        && context.transaction.epoch == context.epoch
        && engineGameTransactionSequence == context.epoch
        && context.gameInfo.blackEngineIndex == context.blackIndex
        && context.gameInfo.whiteEngineIndex == context.whiteIndex
        && context.board != null
        && Lizzie.board == context.board
        && context.board.getHistory() == context.boardHistory
        && context.boardHistory.getCurrentHistoryNode() == context.boardNode
        && context.boardHistory.getMoveNumber() == context.moveNumber
        && context.board.getContextRevision() == context.boardRevision
        && context.boardHistory.isBlacksTurn() == context.blackToPlay
        && (Lizzie.config != null && Lizzie.config.enginePkPonder) == context.ponderRouting;
  }

  static DeferredEngineGamePrimaryPublication prepareEngineGamePrimaryPublication(
      EngineManager expectedManager,
      EngineGameInfo expectedGameInfo,
      int expectedIndex,
      Leelaz engine,
      Leelaz expectedPreviousPrimary,
      long expectedPrimaryGeneration,
      Object expectedEngineIncarnation,
      boolean expectedPonderRouting,
      Board expectedBoard,
      long expectedBoardRevision,
      boolean expectedBlackToPlay) {
    EngineGamePrimaryContext context = captureEngineGamePrimaryContext();
    if (context == null
        || context.manager != expectedManager
        || context.gameInfo != expectedGameInfo
        || context.ponderRouting != expectedPonderRouting) {
      return null;
    }
    return prepareEngineGamePrimaryPublication(
        context,
        expectedIndex,
        engine,
        expectedPreviousPrimary,
        expectedPrimaryGeneration,
        expectedEngineIncarnation,
        expectedBoard,
        expectedBoardRevision,
        expectedBlackToPlay);
  }

  /**
   * Publishes a deferred engine-game primary only while its manager, game and catalog slot match.
   */
  private static boolean publishEngineGamePrimaryIfCurrent(
      DeferredEngineGamePrimaryPublication publication) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGamePrimaryPublication(publication)) {
        return false;
      }
      AtomicBoolean published = new AtomicBoolean();
      boolean primaryCurrent =
          Lizzie.runIfPrimaryEngineWithMutation(
              publication.expectedPreviousPrimary,
              publication.expectedPrimaryGeneration,
              primaryMutation ->
                  publication.engine.runIfCurrentLiveEngineIncarnation(
                      publication.expectedEngineIncarnation,
                      () -> {
                        if (isCurrentEngineGamePrimaryPublication(publication)) {
                          primaryMutation.replaceWith(publication.engine);
                          currentEngineNo = publication.expectedIndex;
                          isEmpty = false;
                          published.set(true);
                        }
                      }));
      return primaryCurrent && published.get();
    }
  }

  /** Called only while the selection lock is held; final callers also hold PRIMARY + endpoint. */
  private static boolean isCurrentEngineGamePrimaryPublication(
      DeferredEngineGamePrimaryPublication publication) {
      if (!isEngineGame
          || Lizzie.engineManager != publication.expectedManager
          || engineGameInfo != publication.expectedGameInfo
        || activeEngineGameTransaction != publication.expectedTransaction
        || publication.expectedTransaction == null
        || publication.expectedTransaction.epoch != publication.expectedEpoch
        || engineGameTransactionSequence != publication.expectedEpoch
        || publication.expectedTransaction.phase != EngineGamePhase.ACTIVE
        || publication.expectedGameInfo == null
        || publication.expectedGameInfo.blackEngineIndex != publication.expectedBlackIndex
        || publication.expectedGameInfo.whiteEngineIndex != publication.expectedWhiteIndex
        || (publication.expectedIndex != publication.expectedBlackIndex
            && publication.expectedIndex != publication.expectedWhiteIndex)
        || (Lizzie.config != null && Lizzie.config.enginePkPonder)
            != publication.expectedPonderRouting
          || !isExactCatalogSlot(
              publication.expectedManager, publication.expectedIndex, publication.engine)) {
        return false;
      }
    Object participantIncarnation =
        publication.expectedIndex == publication.expectedBlackIndex
            ? publication.expectedTransaction.blackIncarnation
            : publication.expectedTransaction.whiteIncarnation;
    if (participantIncarnation != publication.expectedEngineIncarnation
        || Lizzie.board != publication.expectedBoard
        || publication.expectedBoard.getHistory() != publication.expectedBoardHistory
        || publication.expectedBoard.getContextRevision() != publication.expectedBoardRevision
        || publication.expectedBoard.getHistory().isBlacksTurn()
            != publication.expectedBlackToPlay) {
      return false;
    }
    return !publication.expectedPonderRouting
        || (publication.expectedBlackToPlay
            ? publication.expectedIndex == publication.expectedBlackIndex
            : publication.expectedIndex == publication.expectedWhiteIndex);
  }

  private static boolean installInitialPrimaryEngine(
      Leelaz engine, EngineSwitchTransaction transaction) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      Lizzie.setPrimaryEngine(engine);
      currentEngineNo = -1;
      isEmpty = true;
      if (transaction != null) {
        long primaryGeneration = Lizzie.capturePrimaryEngineGeneration(engine);
        if (primaryGeneration < 0L) {
          Lizzie.setPrimaryEngine(null);
          return false;
        }
        transaction.decisionPrimaryEngine = engine;
        transaction.decisionPrimaryGeneration = primaryGeneration;
      }
      return true;
    }
  }

  private static boolean installProvisionalEngineSelection(
      boolean main,
      Leelaz expectedPrevious,
      Leelaz target,
      EngineSwitchTransaction transaction) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (target != null && FAILED_ENGINE_QUARANTINES.containsKey(target)) {
        return false;
      }
      Leelaz selected = main ? Lizzie.leelaz : Lizzie.leelaz2;
      if (selected != expectedPrevious) {
        return false;
      }
      if (main) {
        Lizzie.setPrimaryEngine(target);
      } else {
        Lizzie.leelaz2 = target;
      }
      if (transaction != null) {
        Leelaz decisionPrimary = Lizzie.leelaz;
        long decisionGeneration = Lizzie.capturePrimaryEngineGeneration(decisionPrimary);
        if (decisionGeneration < 0L) {
          if (main) {
            Lizzie.setPrimaryEngine(expectedPrevious);
          } else {
            Lizzie.leelaz2 = expectedPrevious;
          }
          return false;
        }
        transaction.decisionPrimaryEngine = decisionPrimary;
        transaction.decisionPrimaryGeneration = decisionGeneration;
      }
      return true;
    }
  }

  private static boolean commitPrimaryEngineSelection(Leelaz expectedTarget, int index) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (Lizzie.leelaz != expectedTarget) {
        return false;
      }
      currentEngineNo = index;
      isEmpty = false;
      return true;
    }
  }

  private static boolean commitSecondaryEngineSelection(Leelaz expectedTarget, int index) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (Lizzie.leelaz2 != expectedTarget) {
        return false;
      }
      currentEngineNo2 = index;
      return true;
    }
  }

  private static void clearPrimaryEngineSelectionIfCurrent(Leelaz expectedTarget) {
    rollbackPrimaryEngineSelection(expectedTarget, null, -1);
  }

  private static void rollbackPrimaryEngineSelection(
      Leelaz expectedTarget, Leelaz rollbackEngine, int rollbackIndex) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (Lizzie.leelaz != expectedTarget) {
        return;
      }
      Lizzie.setPrimaryEngine(rollbackEngine);
      currentEngineNo = rollbackEngine == null ? -1 : rollbackIndex;
      isEmpty = rollbackEngine == null;
    }
  }

  private static void rollbackSecondaryEngineSelection(
      Leelaz expectedTarget, Leelaz rollbackEngine, int rollbackIndex) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (Lizzie.leelaz2 != expectedTarget) {
        return;
      }
      Lizzie.leelaz2 = rollbackEngine;
      currentEngineNo2 = rollbackEngine == null ? -1 : rollbackIndex;
    }
  }

  private void failEngineSwitchUi(long token, String detail) {
    if (token <= 0L) {
      return;
    }
    for (boolean main : new boolean[] {true, false}) {
      EngineSwitchUiSnapshot current = engineSwitchUiTracker.current(main);
      if (current.token == token) {
        failEngineSwitchUi(current.token, main, detail);
      }
    }
  }

  private String engineDisplayName(Leelaz engine, int index) {
    if (engine != null) {
      if (engine.oriEnginename != null && !engine.oriEnginename.trim().isEmpty()) {
        return engine.oriEnginename;
      }
      if (engine.currentEnginename != null && !engine.currentEnginename.trim().isEmpty()) {
        return engine.currentEnginename;
      }
      if (index >= 0) {
        try {
          String configuredName = engine.getEngineName(index);
          if (configuredName != null && !configuredName.trim().isEmpty()) {
            return configuredName;
          }
        } catch (RuntimeException | Error ignored) {
          // Presentation lookup must never prevent an otherwise valid engine switch.
        }
      }
    }
    return resourceText("EngineManager.engine", "Engine ") + Math.max(1, index + 1);
  }

  /**
   * Resolves the label used by the admission-time SWITCHING snapshot without consulting or
   * migrating the on-disk engine catalog. Admission can run on Swing's EDT and must stay bounded.
   */
  private String engineDisplayNameWithoutCatalogLookup(Leelaz engine, int index) {
    if (engine != null) {
      if (engine.oriEnginename != null && !engine.oriEnginename.trim().isEmpty()) {
        return engine.oriEnginename;
      }
      if (engine.currentEnginename != null && !engine.currentEnginename.trim().isEmpty()) {
        return engine.currentEnginename;
      }
    }
    return resourceText("EngineManager.engine", "Engine ") + Math.max(1, index + 1);
  }

  private Runnable trackEngineSwitchUiCompletion(
      int index,
      boolean isMain,
      Leelaz targetEngine,
      PreparedEngineSwitch preparedSwitch,
      Runnable afterSync) {
    return trackEngineSwitchUiCompletion(
        index, isMain, targetEngine, preparedSwitch, afterSync, 0L);
  }

  private Runnable trackEngineSwitchUiCompletion(
      int index,
      boolean isMain,
      Leelaz targetEngine,
      PreparedEngineSwitch preparedSwitch,
      Runnable afterSync,
      long submittedToken) {
    long token =
        submittedToken > 0L
            ? submittedToken
            : preparedSwitch != null && preparedSwitch.engineSwitchUiToken > 0L
            ? preparedSwitch.engineSwitchUiToken
            : beginEngineSwitchUi(index, isMain, targetEngine);
    if (preparedSwitch != null) {
      preparedSwitch.engineSwitchUiToken = token;
      preparedSwitch.lifecycleRestore.engineSwitchUiToken = token;
      preparedSwitch.lifecycleRestore.engineSwitchUiIndex = index;
      preparedSwitch.lifecycleRestore.engineSwitchUiMain = isMain;
    }
    return () -> {
      boolean callbackSucceeded = false;
      try {
        boolean failureSuperseded =
            preparedSwitch != null
                && preparedSwitch.engineSwitchTransaction != null
                && preparedSwitch.engineSwitchTransaction.synchronizationFailureSuperseded;
        if ((preparedSwitch == null || !preparedSwitch.engineSynchronizationReady)
            && !failureSuperseded) {
          failPendingEngineSwitchUi(token, isMain);
        }
        if (afterSync != null) {
          afterSync.run();
        }
        callbackSucceeded = true;
      } catch (RuntimeException | Error failure) {
        failEngineSwitchUi(token, isMain, engineSwitchFailureDetail(failure));
        throw failure;
      } finally {
        if (preparedSwitch != null
            && !preparedSwitch.lifecycleRestore.engineSwitchUiCompletionAtLifecycleFence) {
          try {
            if (callbackSucceeded && preparedSwitch.engineSynchronizationReady) {
              completeEngineSwitchUi(preparedSwitch.lifecycleRestore);
            }
          } finally {
            finishEngineSwitchTransaction(preparedSwitch.engineSwitchTransaction);
          }
        }
      }
    };
  }

  protected void publishEngineSwitchUiState(EngineSwitchUiSnapshot snapshot) {
    if (snapshot == null) {
      return;
    }
    Runnable update =
        () -> {
          if (engineSwitchUiTracker.isCurrent(snapshot)) {
            try {
              renderEngineSwitchUiState(snapshot);
            } catch (RuntimeException | Error presentationFailure) {
              // Presentation must never strand an engine lifecycle reservation or transaction.
              presentationFailure.printStackTrace();
            }
          }
        };
    try {
      runEngineSwitchUiUpdate(update);
    } catch (RuntimeException | Error dispatchFailure) {
      dispatchFailure.printStackTrace();
    }
  }

  static void runEngineSwitchUiUpdate(Runnable update) {
    if (update == null) {
      return;
    }
    if (SwingUtilities.isEventDispatchThread()) {
      update.run();
    } else {
      SwingUtilities.invokeLater(update);
    }
  }

  protected void renderEngineSwitchUiState(EngineSwitchUiSnapshot snapshot) {
    if (LizzieFrame.menu != null) {
      LizzieFrame.menu.applyEngineSwitchUiState(snapshot);
    }
  }

  public void switchEngine(int index, boolean isMain) {
    switchEngineIfAvailable(index, isMain, true);
  }

  /**
   * Attempts an engine switch without showing the generic exclusive-task popup.
   *
   * <p>Configuration workflows use this after coordinating any interruptible quick analysis so they
   * can report failure in their own status area instead of claiming that a switch succeeded.
   */
  public boolean switchEngineIfAvailable(int index, boolean isMain) {
    return submitEngineSwitchIfAvailable(index, isMain, false, null).isPresent();
  }

  /**
   * Submits a switch and returns the immutable SWITCHING snapshot that identifies this request.
   * Callers that present completion state must wait for this exact token to become ACTIVE.
   */
  public Optional<EngineSwitchUiSnapshot> switchEngineTrackedIfAvailable(
      int index, boolean isMain) {
    return submitEngineSwitchIfAvailable(index, isMain, false, null);
  }

  /**
   * Switches an engine as part of a UI flow that already owns the foreground engine mode.
   *
   * <p>The retained reservation is accepted only for the current foreground engine. It allows the
   * same new-game flow to deepen its lifecycle reservation without weakening exclusion against any
   * unrelated task.
   */
  public boolean switchEngineIfAvailable(
      int index, boolean isMain, Leelaz.EngineModeReservation retainedForegroundReservation) {
    return submitEngineSwitchIfAvailable(index, isMain, true, retainedForegroundReservation)
        .isPresent();
  }

  private boolean switchEngineIfAvailable(int index, boolean isMain, boolean showConflict) {
    return submitEngineSwitchIfAvailable(index, isMain, showConflict, null).isPresent();
  }

  private Optional<EngineSwitchUiSnapshot> submitEngineSwitchIfAvailable(
      int index,
      boolean isMain,
      boolean showConflict,
      Leelaz.EngineModeReservation retainedForegroundReservation) {
    if (rejectForegroundEngineStartDuringSetup(showConflict)) return Optional.empty();
    if (engineList == null || index < 0 || index >= engineList.size()) {
      return Optional.empty();
    }
    if (rejectSameEngineSelection(index, isMain)) {
      return Optional.empty();
    }
    Leelaz currentForegroundEngine = Lizzie.leelaz;
    Object retainedLifecycleOwner = null;
    if (retainedForegroundReservation != null) {
      retainedLifecycleOwner =
          retainedForegroundReservation.lifecycleOwnerFor(currentForegroundEngine);
      if (!isMain || currentForegroundEngine == null || retainedLifecycleOwner == null) {
        if (showConflict) {
          showForegroundEngineLeaseInUse();
        }
        return Optional.empty();
      }
    }
    Leelaz submittedTarget = engineList.get(index);
    EngineSwitchTransaction transaction =
        tryBeginEngineSwitchTransaction(isMain, index, submittedTarget);
    if (transaction == null) {
      if (showConflict) {
        showForegroundEngineLeaseInUse();
      }
      return Optional.empty();
    }
    EngineSwitchUiSnapshot submitted;
    try {
      submitted =
          beginEngineSwitchUiSnapshot(
              index,
              isMain,
              transaction.previousIndex,
              transaction.previousEngine,
              transaction.targetEngine);
    } catch (RuntimeException | Error presentationFailure) {
      finishEngineSwitchTransaction(transaction);
      presentationFailure.printStackTrace();
      return Optional.empty();
    }
    transaction.uiToken = submitted.token;
    boolean foregroundActivation = isMain && isEmpty && currentEngineNo < 0;
    boolean asynchronousSubmission = SwingUtilities.isEventDispatchThread();
    Object submittedLifecycleOwner = retainedLifecycleOwner;
    AtomicBoolean preparationAccepted = new AtomicBoolean(true);
    dispatchEngineSwitchWork(
        transaction,
        () ->
            prepareAndExecuteSubmittedEngineSwitch(
                index,
                isMain,
                showConflict,
                foregroundActivation,
                submittedLifecycleOwner,
                preparationAccepted,
                transaction,
                submitted),
        () -> {
          failPendingEngineSwitchUi(submitted.token, isMain);
          finishEngineSwitchTransaction(transaction);
        });
    return !asynchronousSubmission && !preparationAccepted.get()
        ? Optional.empty()
        : Optional.of(submitted);
  }

  /** Performs every potentially blocking or history-sized preparation step after UI admission. */
  private void prepareAndExecuteSubmittedEngineSwitch(
      int index,
      boolean isMain,
      boolean showConflict,
      boolean foregroundActivation,
      Object retainedLifecycleOwner,
      AtomicBoolean preparationAccepted,
      EngineSwitchTransaction transaction,
      EngineSwitchUiSnapshot submitted) {
    java.util.concurrent.atomic.AtomicReference<Runnable> failureCleanup =
        new java.util.concurrent.atomic.AtomicReference<>(
            () -> finishEngineSwitchTransaction(transaction));
    try {
      PreparedEngineSwitch preparedSwitch =
          prepareEngineSwitch(
              index,
              isMain,
              false,
              foregroundActivation,
              retainedLifecycleOwner,
              transaction.targetEngine,
              transaction.previousEngine);
      Leelaz targetEngine =
          preparedSwitch == null ? transaction.targetEngine : preparedSwitch.targetEngine;
      if (targetEngine != transaction.targetEngine) {
        if (preparedSwitch != null && preparedSwitch.initialStartupSynchronization != null) {
          preparedSwitch.initialStartupSynchronization.close();
        }
        throw new IllegalStateException("Engine catalog changed before switch preparation");
      }
      Leelaz currentEngine =
          preparedSwitch == null
              ? transaction.previousEngine
              : preparedSwitch.lifecycleRestore.previousEngine;
      if (preparedSwitch == null) {
        EngineLifecycleReservations reservations =
            reservePreparedEngineSwitch(currentEngine, targetEngine, null);
        if (reservations == null) {
          throw new InitialStartupReservationException("Engine lifecycle reservation was rejected");
        }
        Runnable releaseTransaction =
            () -> {
              try {
                reservations.close();
              } finally {
                finishEngineSwitchTransaction(transaction);
              }
            };
        failureCleanup.set(releaseTransaction);
        Runnable afterSync =
            targetEngine == currentEngine && !foregroundActivation
                ? releaseTransaction
                : releaseEngineLifecycleAfterBoardSync(
                    currentEngine,
                    targetEngine,
                    isMain,
                    false,
                    false,
                    releaseTransaction,
                    reservations.isTrackingFirstWinner(),
                    null);
        Runnable uiAwareAfterSync =
            trackEngineSwitchUiCompletion(
                index, isMain, targetEngine, null, afterSync, submitted.token);
        switchEngineInternal(index, isMain, null, uiAwareAfterSync);
        return;
      }

      InitialEngineStartupSynchronization lifecycleSynchronization =
          preparedSwitch.initialStartupSynchronization;
      preparedSwitch.engineSwitchTransaction = transaction;
      preparedSwitch.lifecycleRestore.engineSwitchTransaction = transaction;
      transaction.prepareRollback(
          lifecycleSynchronization,
          transaction.main ? null : engineSwitchUiTracker.current(true));
      Runnable releaseLifecycle =
          onceEngineSwitchCleanup(lifecycleSynchronization::close);
      Runnable releaseTransaction =
          onceEngineSwitchCleanup(
              () -> {
                Throwable failure = null;
                failure = runLifecycleCleanupStep(failure, releaseLifecycle);
                failure =
                    runLifecycleCleanupStep(
                        failure, () -> finishEngineSwitchTransaction(transaction));
                rethrowLifecycleCleanupFailure(failure);
              });
      failureCleanup.set(releaseTransaction);
      if (!foregroundActivation) {
        EngineLifecycleReservations reservations =
            reservePreparedEngineSwitch(currentEngine, targetEngine, preparedSwitch);
        if (reservations == null) {
          throw new InitialStartupReservationException("Engine lifecycle reservation was rejected");
        }
        lifecycleSynchronization.installReservations(reservations);
      }
      preparedSwitch.engineSwitchUiToken = submitted.token;
      preparedSwitch.lifecycleRestore.engineSwitchUiToken = submitted.token;
      preparedSwitch.lifecycleRestore.engineSwitchUiIndex = index;
      preparedSwitch.lifecycleRestore.engineSwitchUiMain = isMain;
      Runnable afterSync =
          releaseEngineLifecycleAfterBoardSync(
              currentEngine,
              targetEngine,
              isMain,
              false,
              false,
              releaseLifecycle,
              lifecycleSynchronization.isTrackingFirstWinner(),
              preparedSwitch.lifecycleRestore);
      Runnable uiAwareAfterSync =
          trackEngineSwitchUiCompletion(
              index, isMain, targetEngine, preparedSwitch, afterSync, submitted.token);
      switchEngineInternal(index, isMain, preparedSwitch, uiAwareAfterSync);
    } catch (Leelaz.ExactSnapshotRestoreAdmissionException
        | InitialStartupReservationException conflict) {
      preparationAccepted.set(false);
      failPendingEngineSwitchUi(submitted.token, isMain);
      failureCleanup.get().run();
      if (showConflict) {
        runEngineSwitchUiUpdate(this::showForegroundEngineLeaseInUse);
      }
    } catch (RuntimeException | Error failure) {
      preparationAccepted.set(false);
      failure.printStackTrace();
      failEngineSwitchUi(submitted.token, isMain, engineSwitchFailureDetail(failure));
      failureCleanup.get().run();
      showEngineSynchronizationFailure(transaction.targetEngine);
    }
  }

  private void dispatchEngineSwitchWork(
      EngineSwitchTransaction transaction, Runnable work, Runnable rejectedOrFailed) {
    Runnable guardedWork =
        () -> {
          if (!isCurrentEngineSwitchTransaction(transaction)
              || !engineSwitchUiTracker.isSwitching(transaction.uiToken, transaction.main)) {
            rejectedOrFailed.run();
            return;
          }
          try {
            work.run();
          } catch (RuntimeException | Error failure) {
            failEngineSwitchUi(
                transaction.uiToken, transaction.main, engineSwitchFailureDetail(failure));
            rejectedOrFailed.run();
          }
        };
    if (!SwingUtilities.isEventDispatchThread()) {
      guardedWork.run();
      return;
    }
    Thread worker =
        new Thread(
            guardedWork,
            "lizzie-engine-switch-"
                + (transaction.main ? "primary-" : "secondary-")
                + transaction.uiToken);
    worker.setDaemon(true);
    Runnable startWorker =
        () -> {
          try {
            worker.start();
          } catch (RuntimeException | Error schedulingFailure) {
            failEngineSwitchUi(
                transaction.uiToken,
                transaction.main,
                engineSwitchFailureDetail(schedulingFailure));
            rejectedOrFailed.run();
          }
        };
    // Reserve one complete EDT turn for the SWITCHING presentation before a very fast engine can
    // publish completion. This makes the first post-action pump deterministic and paintable.
    SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(startWorker));
  }

  private boolean rejectSameEngineSelection(int index, boolean isMain) {
    if (Lizzie.config == null
        || !Lizzie.config.isDoubleEngineMode()
        || index != (isMain ? currentEngineNo2 : currentEngineNo)) {
      return false;
    }
    showSameEngineSelection();
    return true;
  }

  protected void showSameEngineSelection() {
    if (Lizzie.frame == null || !Lizzie.frame.isShowing()) {
      return;
    }
    Utils.showMsg(resourceBundle.getString("EngineManager.sameEngineHint"));
  }

  private boolean isSetupModeActive() {
    return Lizzie.board != null && Lizzie.board.isSetupMode();
  }

  private boolean rejectForegroundEngineStartDuringSetup(boolean showMessage) {
    if (!isSetupModeActive()) return false;
    if (showMessage) showSetupModeEngineUnavailable();
    return true;
  }

  protected void showSetupModeEngineUnavailable() {
    Utils.showMsg(resourceBundle.getString("EngineManager.setupModeActive"));
  }

  private Runnable releaseEngineLifecycleAfterBoardSync(
      Leelaz current,
      Leelaz target,
      boolean isMain,
      boolean explicitRestart,
      boolean restartPonderIntent,
      EngineLifecycleReservations reservations,
      PreparedLifecycleRestore lifecycleRestore) {
    return releaseEngineLifecycleAfterBoardSync(
        current,
        target,
        isMain,
        explicitRestart,
        restartPonderIntent,
        reservations::close,
        reservations.isTrackingFirstWinner(),
        lifecycleRestore);
  }

  private Runnable releaseEngineLifecycleAfterBoardSync(
      Leelaz current,
      Leelaz target,
      boolean isMain,
      boolean explicitRestart,
      boolean restartPonderIntent,
      Runnable releaseLifecycle,
      boolean trackingFirstWinner,
      PreparedLifecycleRestore lifecycleRestore) {
    // Re-selecting the exact foreground runtime is not an explicit restart or recovery.  It may
    // converge a frozen Board route, but must not manufacture a ReadBoard recovery ACK for the
    // same quarantined incarnation.  Leave terminal UI publication to the ordinary after-sync
    // wrapper once this lifecycle owner has been released.
    if (!explicitRestart
        && current != null
        && current == target
        && lifecycleRestore != null
        && lifecycleRestore.engineSwitchTransaction != null
        && lifecycleRestore.engineSwitchTransaction.previousIndex >= 0) {
      return releaseLifecycle;
    }
    if (lifecycleRestore != null) {
      lifecycleRestore.engineSwitchUiCompletionAtLifecycleFence = true;
    }
    boolean targetWasUnrestored = target != null && target.hasUnrestoredReadBoardGmaState();
    boolean readBoardRecovery =
        (explicitRestart
                && (targetWasUnrestored
                    || (current != null && current.hasUnrestoredReadBoardGmaState())))
            || (!explicitRestart && current != null && current.hasUnrestoredReadBoardGmaState());
    if (!explicitRestart && lifecycleRestore != null) {
      Runnable finishTransaction =
          onceEngineSwitchCleanup(
              () -> finishEngineSwitchTransaction(lifecycleRestore.engineSwitchTransaction));
      Runnable releaseFailedLifecycle =
          onceEngineSwitchCleanup(
              () -> {
                Throwable failure = null;
                failure = runLifecycleCleanupStep(failure, releaseLifecycle);
                failure = runLifecycleCleanupStep(failure, finishTransaction);
                rethrowLifecycleCleanupFailure(failure);
              });
      return () -> {
        EngineSwitchTransaction switchTransaction = lifecycleRestore.engineSwitchTransaction;
        if (switchTransaction != null
            && switchTransaction.synchronizationFailureSuperseded) {
          releaseFailedLifecycle.run();
          return;
        }
        if (!engineSwitchUiTracker.isSwitching(
            lifecycleRestore.engineSwitchUiToken, lifecycleRestore.engineSwitchUiMain)) {
          releaseFailedLifecycle.run();
          return;
        }
        // Claim settlement before running callbacks so cleanup failures cannot be mistaken for
        // confirmation setup failures and settle the lifecycle a second time.
        AtomicBoolean confirmationSettled = new AtomicBoolean(false);
        Object targetIncarnation = lifecycleRestore.targetEngine.captureEngineIncarnationFence();
        Object mirrorIncarnation =
            lifecycleRestore.mirrorEngine == null
                ? null
                : lifecycleRestore.mirrorEngine.captureEngineIncarnationFence();
        try {
          lifecycleRestore.confirmBoardSynchronization(
                () -> {
                  if (!confirmationSettled.compareAndSet(false, true)) {
                    return;
                  }
                  boolean lifecycleReleased = false;
                  Throwable settlementFailure = null;
                  try {
                    if (!engineSwitchUiTracker.isSwitching(
                      lifecycleRestore.engineSwitchUiToken, lifecycleRestore.engineSwitchUiMain)) {
                      throw new IllegalStateException(
                          "Engine switch was superseded before final initialization");
                    }
                    Lizzie.PreparedEngineReadyPublication readyPublication =
                        isMain
                            ? lifecycleRestore.prepareAfterRestore(false, true)
                            : null;
                    if (isMain && readyPublication == null) {
                      throw new IllegalStateException(
                          "Primary engine changed before READY preparation");
                    }
                    lifecycleRestore.resumePonderAfterSuccessfulSynchronization();
                    // Final selection/ACTIVE publication is terminal. Release every fallible
                    // lifecycle owner first so a detach/reservation failure cannot leave a failed
                    // target published as active.
                    releaseLifecycle.run();
                    lifecycleReleased = true;
                    Runnable terminalPublication =
                        completeOrdinaryEngineSwitchAtFinalFence(
                            lifecycleRestore,
                            targetIncarnation,
                            mirrorIncarnation,
                            readyPublication);
                    terminalPublication.run();
                  } catch (RuntimeException | Error failure) {
                    settlementFailure = failure;
                    settlementFailure =
                        runLifecycleCleanupStep(
                            settlementFailure,
                            () ->
                                failLifecycleFinalInitialization(
                                    target,
                                    isMain,
                                    lifecycleRestore,
                                    targetWasUnrestored,
                                    failure));
                  } finally {
                    if (!lifecycleReleased) {
                      settlementFailure =
                          runLifecycleCleanupStep(settlementFailure, releaseLifecycle);
                    }
                    settlementFailure =
                        runLifecycleCleanupStep(settlementFailure, finishTransaction);
                  }
                  rethrowLifecycleCleanupFailure(settlementFailure);
                },
                (failedEngine, detail) -> {
                  if (!confirmationSettled.compareAndSet(false, true)) {
                    return;
                  }
                  failLifecycleBoardSynchronization(
                      target,
                      failedEngine,
                      detail,
                      targetWasUnrestored,
                      releaseFailedLifecycle,
                      engineSwitchUiToken(lifecycleRestore));
                });
        } catch (RuntimeException | Error confirmationFailure) {
          if (!confirmationSettled.compareAndSet(false, true)) {
            throw confirmationFailure;
          }
          failLifecycleBoardSynchronization(
              target,
              target,
              engineSwitchFailureDetail(confirmationFailure),
              targetWasUnrestored,
              releaseFailedLifecycle,
              engineSwitchUiToken(lifecycleRestore));
        }
      };
    }
    if (explicitRestart && !isMain && target != null) {
      return () -> {
        if (Lizzie.leelaz2 != target || !target.isStarted() || !target.isLoaded()) {
          failLifecycleBoardSynchronization(
              target,
              target,
              "restart engine was unavailable before board synchronization",
              targetWasUnrestored,
              releaseLifecycle,
              engineSwitchUiToken(lifecycleRestore));
          return;
        }
        confirmLifecycleBoardSynchronization(
            lifecycleRestore,
            target,
            () -> {
              try {
                target.completeSecondaryExplicitRestartBoardSynchronization();
                if (restartPonderIntent
                    && current != null
                    && current == Lizzie.leelaz
                    && current.isStarted()
                    && current.isLoaded()
                    && !current.isCheckingName) {
                  current.ponder();
                }
                target.setResponseUpToDate();
                commitEngineSelectionAtFinalFence(lifecycleRestore);
                completeEngineSwitchUi(lifecycleRestore);
              } catch (RuntimeException | Error failure) {
                failLifecycleFinalInitialization(
                    target, isMain, lifecycleRestore, targetWasUnrestored, failure);
              } finally {
                releaseLifecycle.run();
              }
            },
            (failedEngine, detail) ->
                failLifecycleBoardSynchronization(
                    target,
                    failedEngine,
                    detail,
                    targetWasUnrestored,
                    releaseLifecycle,
                    engineSwitchUiToken(lifecycleRestore)));
      };
    }
    if (!isMain
        || current == null
        || target == null
        || (!explicitRestart && !trackingFirstWinner && !readBoardRecovery)) {
      return () -> {
        try {
          if (lifecycleRestore != null) {
            lifecycleRestore.resumePonderAfterSuccessfulSynchronization();
            commitEngineSelectionAtFinalFence(lifecycleRestore);
          }
          completeEngineSwitchUi(lifecycleRestore);
        } catch (RuntimeException | Error failure) {
          failLifecycleFinalInitialization(
              target, isMain, lifecycleRestore, targetWasUnrestored, failure);
        } finally {
          releaseLifecycle.run();
        }
      };
    }
    return () -> {
      if (Lizzie.leelaz != target || !target.isStarted() || !target.isLoaded()) {
        if (explicitRestart) {
          failLifecycleBoardSynchronization(
              target,
              target,
              "restart engine was unavailable before board synchronization",
              targetWasUnrestored,
              releaseLifecycle,
              engineSwitchUiToken(lifecycleRestore));
          return;
        }
        failEngineSwitchUi(
            engineSwitchUiToken(lifecycleRestore),
            "engine was unavailable before board synchronization");
        releaseLifecycle.run();
        return;
      }
      confirmLifecycleBoardSynchronization(
          lifecycleRestore,
          target,
          () -> {
            try {
              if (explicitRestart && target.hasUnrestoredReadBoardGmaState()) {
                target.completeReadBoardGmaRecoveryAfterBoardSync();
              }
              if (explicitRestart) {
                if (lifecycleRestore != null) {
                  lifecycleRestore.initializeAfterExplicitRestart(restartPonderIntent);
                } else {
                  target.initializeAfterExplicitRestartBoardSynchronization(restartPonderIntent);
                }
              } else if (lifecycleRestore != null) {
                lifecycleRestore.resumePonderAfterSuccessfulSynchronization();
              }
              commitEngineSelectionAtFinalFence(lifecycleRestore);
              completeEngineSwitchUi(lifecycleRestore);
            } catch (RuntimeException | Error failure) {
              failLifecycleFinalInitialization(
                  target, isMain, lifecycleRestore, targetWasUnrestored, failure);
            } finally {
              releaseLifecycle.run();
            }
          },
          (failedEngine, detail) ->
              failLifecycleBoardSynchronization(
                  target,
                  failedEngine,
                  detail,
                  targetWasUnrestored,
                  releaseLifecycle,
                  engineSwitchUiToken(lifecycleRestore)));
    };
  }

  private void confirmLifecycleBoardSynchronization(
      PreparedLifecycleRestore lifecycleRestore,
      Leelaz target,
      Runnable onSuccess,
      java.util.function.BiConsumer<Leelaz, String> onFailure) {
    if (lifecycleRestore != null) {
      lifecycleRestore.confirmBoardSynchronization(onSuccess, onFailure);
    } else {
      target.confirmBoardSynchronization(onSuccess, detail -> onFailure.accept(target, detail));
    }
  }

  private void failLifecycleFinalInitialization(
      Leelaz target,
      boolean isMain,
      PreparedLifecycleRestore lifecycleRestore,
      boolean targetWasUnrestored,
      Throwable failure) {
    String detail = engineSwitchFailureDetail(failure);
    if (target != null) {
      target.isLoaded = false;
    }
    failEngineSwitchUi(engineSwitchUiToken(lifecycleRestore, target, isMain), isMain, detail);
    if (target != null) {
      try {
        target.markLifecycleBoardSynchronizationFailed(detail, targetWasUnrestored);
      } catch (RuntimeException | Error cleanupFailure) {
        cleanupFailure.printStackTrace();
      }
      showEngineSynchronizationFailure(target);
    }
  }

  private void failLifecycleBoardSynchronization(
      Leelaz target,
      Leelaz failedEngine,
      String detail,
      boolean targetWasUnrestored,
      Runnable releaseLifecycle,
      long engineSwitchUiToken) {
    try {
      target.isLoaded = false;
      if (failedEngine != null && failedEngine != target) {
        failedEngine.isLoaded = false;
      }
      failEngineSwitchUi(engineSwitchUiToken, detail);
      if (failedEngine != null && failedEngine != target) {
        invalidateUnavailableEngineUiState(failedEngine, detail);
      }
      try {
        target.markLifecycleBoardSynchronizationFailed(detail, targetWasUnrestored);
      } catch (RuntimeException | Error cleanupFailure) {
        cleanupFailure.printStackTrace();
      }
      if (failedEngine != null && failedEngine != target) {
        try {
          failedEngine.markLifecycleBoardSynchronizationFailed(
              detail, failedEngine.hasUnrestoredReadBoardGmaState());
        } catch (RuntimeException | Error cleanupFailure) {
          cleanupFailure.printStackTrace();
        }
      }
      showEngineSynchronizationFailure(target);
    } finally {
      releaseLifecycle.run();
    }
  }

  private void invalidateUnavailableEngineUiState(Leelaz unavailableEngine, String detail) {
    if (unavailableEngine == null) {
      return;
    }
    for (boolean main : new boolean[] {true, false}) {
      EngineSwitchUiSnapshot snapshot = engineSwitchUiTracker.current(main);
      if (snapshot.phase == EngineSwitchUiPhase.ACTIVE
          && snapshot.activeEngineIdentity == unavailableEngine
          && !isSnapshotActiveEngineAvailable(snapshot)) {
        failEngineSwitchUi(snapshot.token, main, detail);
      }
    }
  }

  private long engineSwitchUiToken(PreparedLifecycleRestore lifecycleRestore) {
    return lifecycleRestore == null ? 0L : lifecycleRestore.engineSwitchUiToken;
  }

  private long engineSwitchUiToken(
      PreparedLifecycleRestore lifecycleRestore, Leelaz target, boolean isMain) {
    long preparedToken = engineSwitchUiToken(lifecycleRestore);
    if (preparedToken > 0L) {
      return preparedToken;
    }
    EngineSwitchUiSnapshot snapshot = engineSwitchUiTracker.current(isMain);
    return snapshot.phase == EngineSwitchUiPhase.SWITCHING
            && snapshot.targetEngineIdentity == target
        ? snapshot.token
        : 0L;
  }

  private void commitEngineSelectionAtFinalFence(PreparedLifecycleRestore lifecycleRestore) {
    if (lifecycleRestore == null || lifecycleRestore.engineSwitchUiToken <= 0L) {
      return;
    }
    if (!engineSwitchUiTracker.isSwitching(
        lifecycleRestore.engineSwitchUiToken, lifecycleRestore.engineSwitchUiMain)) {
      throw new IllegalStateException("Engine switch was superseded before final commit");
    }
    if (lifecycleRestore.engineSwitchUiMain) {
      if (!commitPrimaryEngineSelection(
          lifecycleRestore.targetEngine, lifecycleRestore.engineSwitchUiIndex)) {
        throw new IllegalStateException("Primary engine changed before final switch commit");
      }
    } else {
      if (!commitSecondaryEngineSelection(
          lifecycleRestore.targetEngine, lifecycleRestore.engineSwitchUiIndex)) {
        throw new IllegalStateException("Secondary engine changed before final switch commit");
      }
    }
  }

  private Runnable completeOrdinaryEngineSwitchAtFinalFence(
      PreparedLifecycleRestore lifecycleRestore,
      Object targetIncarnation,
      Object mirrorIncarnation,
      Lizzie.PreparedEngineReadyPublication readyPublication) {
    if (lifecycleRestore == null || lifecycleRestore.engineSwitchUiToken <= 0L) {
      throw new IllegalStateException("Engine switch final authority is unavailable");
    }
    String targetName =
        engineDisplayName(lifecycleRestore.targetEngine, lifecycleRestore.engineSwitchUiIndex);
    AtomicReference<EngineSwitchUiSnapshot> activeSnapshot = new AtomicReference<>();
    AtomicReference<EngineStartupStatus.PreparedNotification> readyNotification =
        new AtomicReference<>();
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      boolean main = lifecycleRestore.engineSwitchUiMain;
      Leelaz targetEngine = lifecycleRestore.targetEngine;
      EngineSwitchTransaction transaction = lifecycleRestore.engineSwitchTransaction;
      if (Lizzie.engineManager != this
          || transaction == null
          || engineSwitchTransaction.get() != transaction
          || transaction.decisionEngineCatalog != engineList
          || transaction.targetIndex != lifecycleRestore.engineSwitchUiIndex
          || transaction.targetEngine != targetEngine
          || transaction.main != main
          || targetEngine != (main ? Lizzie.leelaz : Lizzie.leelaz2)
          || !engineSwitchUiTracker.isSwitching(
              lifecycleRestore.engineSwitchUiToken, main)) {
        throw new IllegalStateException("Engine switch was superseded before final commit");
      }
      Runnable exactTerminalCommit =
          () -> {
            if (!targetEngine.isStarted() || !targetEngine.isLoaded()) {
              throw new IllegalStateException(
                  "Engine became unavailable before final switch commit");
            }
            EngineSwitchUiSnapshot committed =
                engineSwitchUiTracker
                    .succeed(
                        lifecycleRestore.engineSwitchUiToken,
                        main,
                        lifecycleRestore.engineSwitchUiIndex,
                        targetName,
                        targetEngine,
                        () -> {
                          if (main) {
                            currentEngineNo = lifecycleRestore.engineSwitchUiIndex;
                            isEmpty = false;
                            readyNotification.set(readyPublication.prepareReadyStatus());
                          } else {
                            currentEngineNo2 = lifecycleRestore.engineSwitchUiIndex;
                          }
                        })
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "Engine switch UI authority changed before final commit"));
            activeSnapshot.set(committed);
          };
      AtomicBoolean exactIncarnations = new AtomicBoolean(false);
      Runnable commitUnderEndpointFences =
          () ->
              exactIncarnations.set(
                  Leelaz.runIfEngineIncarnationFencesUnchanged(
                      targetEngine,
                      targetIncarnation,
                      lifecycleRestore.mirrorEngine,
                      mirrorIncarnation,
                      exactTerminalCommit));
      boolean primaryCurrent =
          !main
              || Lizzie.runIfPrimaryEngine(
                  targetEngine,
                  readyPublication.primaryGeneration(),
                  commitUnderEndpointFences);
      if (!main) {
        commitUnderEndpointFences.run();
      }
      if (!primaryCurrent || !exactIncarnations.get() || activeSnapshot.get() == null) {
        throw new IllegalStateException(
            "Engine incarnation changed before final switch commit");
      }
    }
    return () -> {
      publishEngineSwitchUiState(activeSnapshot.get());
      if (readyPublication != null) {
        readyPublication.publishForPrimary(readyNotification.get());
      }
    };
  }

  private String engineSwitchFailureDetail(Throwable failure) {
    return Leelaz.safeFailureDetail(failure, engineFailedText());
  }

  private String engineFailedText() {
    return resourceText("Leelaz.engineFailed", "Engine startup failed");
  }

  private String resourceText(String key, String fallback) {
    if (resourceBundle == null || key == null) {
      return fallback;
    }
    try {
      String value = resourceBundle.getString(key);
      return value == null || value.trim().isEmpty() ? fallback : value;
    } catch (RuntimeException missingOrUnavailable) {
      return fallback;
    }
  }

  protected void switchEngineInternal(int index, boolean isMain, Runnable afterSync) {
    if (rejectSameEngineSelection(index, isMain)) {
      if (afterSync != null) afterSync.run();
      return;
    }
    PreparedEngineSwitch preparedSwitch = prepareEngineSwitch(index, isMain);
    Leelaz targetEngine =
        preparedSwitch == null ? engineList.get(index) : preparedSwitch.targetEngine;
    Runnable uiAwareAfterSync =
        trackEngineSwitchUiCompletion(index, isMain, targetEngine, preparedSwitch, afterSync);
    switchEngineInternal(index, isMain, preparedSwitch, uiAwareAfterSync);
  }

  protected void showContributingEngineSwitchUnavailable() {
    runEngineSwitchUiUpdate(
        () ->
            Utils.showMsg(
                resourceBundle.getString("Contribute.tips.contributingAndStartAnotherLizzieYzy")));
  }

  protected void switchEngineInternal(
      int index, boolean isMain, PreparedEngineSwitch preparedSwitch, Runnable afterSync) {
    boolean syncScheduled = false;
    Runnable uiAwareAfterSync = afterSync;
    Runnable delegatedAfterSync = null;
    Leelaz.UpdateEngineStartAttempt targetStartAttempt = null;
    try {
    if (Lizzie.frame.isContributing) {
      showContributingEngineSwitchUnavailable();
      return;
    }

    engineNo = index;
      if (rejectSameEngineSelection(index, isMain)) {
      return;
    }
      Leelaz newEng = preparedSwitch.targetEngine;
    if (newEng == null) return;
      if (preparedSwitch.engineSwitchUiToken <= 0L) {
        uiAwareAfterSync =
            trackEngineSwitchUiCompletion(index, isMain, newEng, preparedSwitch, uiAwareAfterSync);
      }
      InitialEngineStartupSynchronization lifecycleSynchronization =
          preparedSwitch.initialStartupSynchronization;
    // newEng.isReadyForGenmoveGame = false;
      boolean changeBoard = preparedSwitch.targetBoardSizeChanges;
      boolean changeOriBoard = preparedSwitch.targetOriginalBoardSizeChanges;
      boolean isEmptyBoard = preparedSwitch.boardEmpty;

    // Lizzie.frame.menu.showPda(false);
      if (isEmptyBoard && changeOriBoard && isMain)
        Lizzie.board.reopenOnlyBoard(newEng.oriWidth, newEng.oriHeight);
      if (preparedSwitch.previousEngine != null) {
        Leelaz curEng = preparedSwitch.previousEngine;
        // curEng.switching = true;
        try {
          if (!Lizzie.config.fastChange) {
            curEng.normalQuit();
          } else {
            if (curEng.isLeela0110) curEng.leela0110StopPonder();
            curEng.nameCmdfornoponder();
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
        curEng.notPondering();
      }
      boolean targetAlreadyStarted = newEng.isStarted();
      if (!targetAlreadyStarted && !preparedSwitch.explicitRestart) {
        // Acquire exact start ownership before publishing the provisional selection.  If
        // acquisition itself fails, no global pointer has moved and there is no unowned target
        // runtime for the failure path to guess at.
        targetStartAttempt = newEng.beginUpdateEngineStartAttempt();
      }
      EngineSwitchTransaction installedTransaction = preparedSwitch.engineSwitchTransaction;
      if (!installProvisionalEngineSelection(
          isMain, preparedSwitch.previousEngine, newEng, installedTransaction)) {
        throw new IllegalStateException("Engine selection changed before switch execution");
      }
      if (preparedSwitch.explicitRestart && isMain) {
        // Re-installing the same object deliberately advances the primary generation; bind the
        // restarted incarnation after that publication so its final READY/PDA callback is valid.
        newEng.bindCurrentPrimaryEngineGeneration();
      }
      if (installedTransaction != null
          && isCurrentEngineSwitchTransaction(installedTransaction)
          && installedTransaction.targetEngine == newEng
          && installedTransaction.main == isMain) {
        installedTransaction.targetInstalled = true;
      }
      // Re-render the same token after provisional ownership changes so a stopped previous
      // process is never left with a stale playing icon while the target is still switching.
      publishEngineSwitchUiState(engineSwitchUiTracker.current(isMain));
      if (isMain && Lizzie.frame != null) {
        LizzieFrame frame = Lizzie.frame;
        runEngineSwitchUiUpdate(frame::invalidateTrackingAnalysis);
      }
      newEng.komi = preparedSwitch.targetKomi;
      if (targetAlreadyStarted
          && installedTransaction != null
          && isCurrentEngineSwitchTransaction(installedTransaction)
          && installedTransaction.targetEngine == newEng) {
        installedTransaction.targetEngineIncarnation = newEng.captureEngineIncarnationFence();
        installedTransaction.targetEngineIncarnationCaptured = true;
      }
      if (!targetAlreadyStarted) {
        newEng.isLoaded = false;
        if (isEmptyBoard && isMain) {
          newEng.width = newEng.oriWidth;
          newEng.height = newEng.oriHeight;
        } else {
          newEng.width = preparedSwitch.boardWidth;
          newEng.height = preparedSwitch.boardHeight;
        }
        if (!preparedSwitch.explicitRestart) {
          try {
            targetStartAttempt.startEngine(index);
          } finally {
            if (installedTransaction != null
                && installedTransaction.targetEngine == newEng) {
              installedTransaction.targetEngineIncarnation =
                  targetStartAttempt.publishedIncarnation();
              installedTransaction.targetEngineIncarnationCaptured = true;
            }
          }
          targetStartAttempt.complete();
          targetStartAttempt = null;
        } else {
          newEng.startEngine(index);
        }
      } else {
        // newEng.getEngineName(index);
        newEng.canRestoreDymPda = false;
        if (!(isEmptyBoard && changeBoard) || !isMain) {
          newEng.width = preparedSwitch.boardWidth;
          newEng.height = preparedSwitch.boardHeight;
          newEng.boardSizeForEngine(newEng.width, newEng.height);
        }
        if (isEmptyBoard && changeOriBoard && isMain) {
          newEng.width = newEng.oriWidth;
          newEng.height = newEng.oriHeight;
          newEng.boardSizeForEngine(newEng.width, newEng.height);
        }
        newEng.sendCommand("komi " + newEng.komi);
        newEng.isCheckingName = true;
        newEng.sendCommand("name");

        synchronized (Lizzie.board) {
          Lizzie.board.getHistory().getGameInfo().setKomi(newEng.komi);
        }
        Lizzie.config.leelaversion = newEng.version;
        var toolbar = LizzieFrame.toolbar;
        var frame = Lizzie.frame;
        SwingUtilities.invokeLater(
            () -> {
              if (toolbar != null) {
                toolbar.reSetButtonLocation();
              }
              if (frame != null && frame.resetMovelistFrameandAnalysisFrame()) {
                frame.setVisible(true);
              }
            });
      }
      newEng.anaGameResignCount = 0;
      if (installedTransaction != null
          && isCurrentEngineSwitchTransaction(installedTransaction)
          && installedTransaction.targetEngine == newEng
          && !installedTransaction.targetEngineIncarnationCaptured) {
        installedTransaction.targetEngineIncarnation = newEng.captureEngineIncarnationFence();
        installedTransaction.targetEngineIncarnationCaptured = true;
      }
      if (isMain) {
        Runnable syncBoard =
            new Runnable() {
              public void run() {
                newEng.notPondering();
                lifecycleSynchronization.runUntilStable();
                if (newEng == Lizzie.leelaz) {
                  synchronized (Lizzie.board) {
                    Lizzie.board.clearBestMovesAfterForFirstEngine(
                        Lizzie.board.getHistory().getStart());
                  }
                  runEngineSwitchUiUpdate(
                      () -> {
                        if (LizzieFrame.toolbar != null) {
                          LizzieFrame.toolbar.reSetButtonLocation();
                        }
                        if (LizzieFrame.boardRenderer != null) {
                          LizzieFrame.boardRenderer.removeKataEstimateImage();
                        }
                        if (Lizzie.frame != null && Lizzie.frame.floatBoard != null) {
                          Lizzie.frame.floatBoard.boardRenderer.removeKataEstimateImage();
                        }
                        if (Lizzie.config.showSubBoard && LizzieFrame.subBoardRenderer != null) {
                          LizzieFrame.subBoardRenderer.removeKataEstimateImage();
                        }
                      });
                  if (!preparedSwitch.explicitRestart && !preparedSwitch.foregroundActivation) {
                    newEng.setResponseUpToDate();
                  }
                  preparedSwitch.engineSynchronizationReady = true;
                }
              }
            };
        syncScheduled = true;
        delegatedAfterSync = onceEngineSwitchCleanup(uiAwareAfterSync);
        synchronizeEngineWhenReady(newEng, syncBoard, delegatedAfterSync);
      } else if (Lizzie.leelaz2 != null) {
        Runnable syncBoard =
            new Runnable() {
              public void run() {
                synchronized (Lizzie.board) {
                  Lizzie.board.clearBestMovesAfterForSecondEngine(
                      Lizzie.board.getHistory().getStart());
                }
                lifecycleSynchronization.runUntilStable();
                runEngineSwitchUiUpdate(
                    () -> {
                      if (LizzieFrame.boardRenderer2 != null) {
                        LizzieFrame.boardRenderer2.removeKataEstimateImage();
                      }
                    });
                if (!preparedSwitch.explicitRestart) {
                  newEng.setResponseUpToDate();
                }
                preparedSwitch.engineSynchronizationReady = true;
              }
            };
        syncScheduled = true;
        delegatedAfterSync = onceEngineSwitchCleanup(uiAwareAfterSync);
        synchronizeEngineWhenReady(newEng, syncBoard, delegatedAfterSync);
      }
    } catch (IOException | RuntimeException | Error failure) {
      if (syncScheduled) {
        Runnable completion = delegatedAfterSync;
        if (completion != null) {
          try {
            completion.run();
          } catch (RuntimeException | Error cleanupFailure) {
            suppressEngineStartCleanupFailure(failure, cleanupFailure);
          }
        }
        failure.printStackTrace();
        return;
      }
      if (targetStartAttempt != null) {
        syncScheduled = true;
        settleEngineStartFailureBeforeSynchronization(
            newEngForFailure(preparedSwitch),
            targetStartAttempt,
            onceEngineSwitchCleanup(uiAwareAfterSync),
            failure);
        failure.printStackTrace();
        return;
      }
      if (failure instanceof IOException) {
        failure.printStackTrace();
        return;
      }
      if (failure instanceof RuntimeException) {
        throw (RuntimeException) failure;
      }
      throw (Error) failure;
    } finally {
      if (!syncScheduled && uiAwareAfterSync != null) {
        if (preparedSwitch != null && preparedSwitch.initialStartupSynchronization != null) {
          failEngineSwitchUi(preparedSwitch.engineSwitchUiToken, isMain);
          preparedSwitch.initialStartupSynchronization.close();
          finishEngineSwitchTransaction(preparedSwitch.engineSwitchTransaction);
        } else {
          uiAwareAfterSync.run();
        }
      }
    }
  }

  private static Leelaz newEngForFailure(PreparedEngineSwitch preparedSwitch) {
    return preparedSwitch == null ? null : preparedSwitch.targetEngine;
  }

  private void settleEngineStartFailureBeforeSynchronization(
      Leelaz engine,
      Leelaz.UpdateEngineStartAttempt startAttempt,
      Runnable afterSync,
      Throwable primaryFailure) {
    UpdateEngineStartFailureCleanups failureCleanups =
        claimUpdateEngineStartFailureCleanups(startAttempt, null, primaryFailure);
    Runnable failurePresentation = null;
    if (failureCleanups.claimedTarget()) {
      failurePresentation =
          reportEngineSynchronizationFailureIfCurrent(
              engine, startAttempt, null, null, primaryFailure);
    }
    try {
      if (afterSync != null) {
        afterSync.run();
      }
    } catch (RuntimeException | Error cleanupFailure) {
      suppressEngineStartCleanupFailure(primaryFailure, cleanupFailure);
    } finally {
      if (failurePresentation != null) {
        try {
          failurePresentation.run();
        } catch (RuntimeException | Error presentationFailure) {
          suppressEngineStartCleanupFailure(primaryFailure, presentationFailure);
        }
      }
      dispatchUpdateEngineStartFailureCleanupAfterRelease(
          failureCleanups, Runnable::run);
    }
  }

  private PreparedEngineSwitch prepareEngineSwitch(int index, boolean isMain) {
    return prepareEngineSwitch(index, isMain, false, false);
  }

  private PreparedEngineSwitch prepareEngineSwitch(
      int index, boolean isMain, boolean explicitRestart) {
    return prepareEngineSwitch(index, isMain, explicitRestart, false);
  }

  private PreparedEngineSwitch prepareEngineSwitch(
      int index, boolean isMain, boolean explicitRestart, boolean foregroundActivation) {
    return prepareEngineSwitch(index, isMain, explicitRestart, foregroundActivation, null);
  }

  private PreparedEngineSwitch prepareEngineSwitch(
      int index,
      boolean isMain,
      boolean explicitRestart,
      boolean foregroundActivation,
      Object retainedLifecycleOwner) {
    Board restoreBoard = Lizzie.board;
    if (restoreBoard == null) {
      return null;
    }
    Leelaz targetEngine = engineList.get(index);
    Leelaz previousEngine = isMain ? Lizzie.leelaz : Lizzie.leelaz2;
    return prepareEngineSwitch(
        isMain,
        explicitRestart,
        foregroundActivation,
        retainedLifecycleOwner,
        restoreBoard,
        targetEngine,
        previousEngine);
  }

  private PreparedEngineSwitch prepareEngineSwitch(
      int index,
      boolean isMain,
      boolean explicitRestart,
      boolean foregroundActivation,
      Object retainedLifecycleOwner,
      Leelaz targetEngine,
      Leelaz previousEngine) {
    Board restoreBoard = Lizzie.board;
    if (restoreBoard == null) {
      return null;
    }
    return prepareEngineSwitch(
        isMain,
        explicitRestart,
        foregroundActivation,
        retainedLifecycleOwner,
        restoreBoard,
        targetEngine,
        previousEngine);
  }

  private PreparedEngineSwitch prepareEngineSwitch(
      boolean isMain,
      boolean explicitRestart,
      boolean foregroundActivation,
      Object retainedLifecycleOwner,
      Board restoreBoard,
      Leelaz targetEngine,
      Leelaz previousEngine) {
    if (explicitRestart && isMain && targetEngine != null) {
      targetEngine.bindCurrentPrimaryEngineGeneration();
    }
    boolean resumePonder =
        foregroundActivation
            || (previousEngine != null && previousEngine.isPonderingOrWasPonderingBeforeTracking());
    Leelaz proposedRestoreMirror =
        Lizzie.config.isDoubleEngineMode() ? (isMain ? Lizzie.leelaz2 : Lizzie.leelaz) : null;
    InitialEngineStartupSynchronization lifecycleSynchronization =
        foregroundActivation
            ? InitialEngineStartupSynchronization.capture(
            previousEngine,
            targetEngine,
            proposedRestoreMirror,
                restoreBoard,
                false,
                resumePonder)
            : InitialEngineStartupSynchronization.capturePrepared(
                previousEngine,
                targetEngine,
                proposedRestoreMirror,
                restoreBoard,
                false,
                resumePonder,
                retainedLifecycleOwner);
    PreparedLifecycleRestore lifecycleRestore = lifecycleSynchronization.pendingRoute;
    BoardFrame frame = lifecycleSynchronization.capturedFrame;
    int boardWidth = frame.boardWidth;
    int boardHeight = frame.boardHeight;
    boolean targetBoardSizeChanges =
        targetEngine.width != boardWidth || targetEngine.height != boardHeight;
    boolean targetOriginalBoardSizeChanges =
        targetEngine.oriWidth != boardWidth || targetEngine.oriHeight != boardHeight;
    float targetKomi =
        !isMain || frame.changedKomi || lifecycleRestore.exactRestore.isPresent()
            ? (float) frame.komi
            : targetEngine.orikomi;
    return new PreparedEngineSwitch(
        targetEngine,
        previousEngine,
        lifecycleRestore,
        lifecycleSynchronization,
        boardWidth,
        boardHeight,
        frame.boardEmpty,
        targetBoardSizeChanges,
        targetOriginalBoardSizeChanges,
        targetKomi,
        explicitRestart,
        foregroundActivation);
  }

  void synchronizeUpdateEnginesWhenReady(
      int expectedTargetIndex,
      Leelaz target,
      Leelaz mirror,
      Leelaz.UpdateEngineStartAttempt targetAttempt,
      Leelaz.UpdateEngineStartAttempt mirrorAttempt,
      Runnable synchronization,
      Runnable afterSync) {
    synchronizeUpdateEnginesWhenReady(
        expectedTargetIndex,
        target,
        mirror,
        targetAttempt,
        mirrorAttempt,
        synchronization,
        afterSync,
        Runnable::run);
  }

  private void synchronizeUpdateEnginesWhenReady(
      int expectedTargetIndex,
      Leelaz target,
      Leelaz mirror,
      Leelaz.UpdateEngineStartAttempt targetAttempt,
      Leelaz.UpdateEngineStartAttempt mirrorAttempt,
      Runnable synchronization,
      Runnable afterSync,
      java.util.function.Consumer<Runnable> afterLifecycleRelease) {
    Runnable synchronizationWork =
        () -> {
          Throwable synchronizationFailure = null;
          UpdateEngineStartFailureCleanups failureCleanups = null;
          try {
            if (!waitForEngineSynchronizationReadiness(target)
                || (mirror != null && !waitForEngineSynchronizationReadiness(mirror))) {
              synchronizationFailure =
                  new IllegalStateException("update engine did not become ready");
              failureCleanups =
                  claimUpdateEngineStartFailureCleanups(
                      targetAttempt, mirrorAttempt, synchronizationFailure);
              if (failureCleanups.claimedTarget()) {
                reportUpdateEngineStartFailure(
                    expectedTargetIndex,
                    target,
                    targetAttempt,
                    mirror,
                    mirrorAttempt,
                    synchronizationFailure);
              }
              return;
            }
            synchronization.run();
          } catch (RuntimeException | Error failure) {
            synchronizationFailure = failure;
            failureCleanups =
                claimUpdateEngineStartFailureCleanups(targetAttempt, mirrorAttempt, failure);
            if (failureCleanups.claimedTarget()) {
              reportUpdateEngineStartFailure(
                  expectedTargetIndex, target, targetAttempt, mirror, mirrorAttempt, failure);
            }
            failure.printStackTrace();
          } finally {
            if (afterSync != null) {
              try {
                afterSync.run();
              } catch (RuntimeException | Error cleanupFailure) {
                if (synchronizationFailure == null) {
                  synchronizationFailure = cleanupFailure;
                  failureCleanups =
                      claimUpdateEngineStartFailureCleanups(
                          targetAttempt, mirrorAttempt, cleanupFailure);
                  if (failureCleanups.claimedTarget()) {
                    reportUpdateEngineStartFailure(
                        expectedTargetIndex,
                        target,
                        targetAttempt,
                        mirror,
                        mirrorAttempt,
                        cleanupFailure);
                  }
                } else {
                  suppressEngineStartCleanupFailure(synchronizationFailure, cleanupFailure);
                }
                cleanupFailure.printStackTrace();
              }
            }
            if (failureCleanups != null) {
              dispatchUpdateEngineStartFailureCleanupAfterRelease(
                  failureCleanups, afterLifecycleRelease);
            }
          }
        };
    try {
      Thread synchronizationThread = createUpdateEngineSynchronizationThread(synchronizationWork);
      synchronizationThread.start();
    } catch (RuntimeException | Error schedulingFailure) {
      UpdateEngineStartFailureCleanups failureCleanups =
          claimUpdateEngineStartFailureCleanups(targetAttempt, mirrorAttempt, schedulingFailure);
      if (failureCleanups.claimedTarget()) {
        reportUpdateEngineStartFailure(
            expectedTargetIndex, target, targetAttempt, mirror, mirrorAttempt, schedulingFailure);
      }
      try {
        if (afterSync != null) {
          afterSync.run();
        }
      } catch (RuntimeException | Error cleanupFailure) {
        suppressEngineStartCleanupFailure(schedulingFailure, cleanupFailure);
      } finally {
        dispatchUpdateEngineStartFailureCleanupAfterRelease(failureCleanups, afterLifecycleRelease);
      }
      throw schedulingFailure;
    }
  }

  void completeUpdateEngineReplacementStart(
      int expectedTargetIndex,
      Leelaz target,
      Leelaz mirror,
      Leelaz.UpdateEngineStartAttempt targetAttempt,
      Leelaz.UpdateEngineStartAttempt mirrorAttempt,
      InitialEngineStartupSynchronization lifecycleSynchronization) {
    Leelaz.UpdateEngineStartCompletion completion =
        Leelaz.claimUpdateEngineStartCompletion(targetAttempt, mirrorAttempt);
    boolean settlementDeferred = false;
    try {
      Lizzie.PreparedEngineReadyPublication readyPublication =
          lifecycleSynchronization.prepareUpdateRestore();
      lifecycleSynchronization.close();
      Runnable settleAfterLifecycleRelease =
          () -> {
            try {
              EngineStartupStatus.PreparedNotification readyNotification;
              AtomicReference<EngineStartupStatus.PreparedNotification> preparedReady =
                  new AtomicReference<>();
              synchronized (ENGINE_SELECTION_STATE_LOCK) {
                if (!isCurrentUpdateEngineReplacementSelection(expectedTargetIndex, target, mirror)
                    || readyPublication.engine() != target) {
                  throw new IllegalStateException(
                      "Update engine selection changed before terminal READY publication");
                }
                if (!Lizzie.runIfPrimaryEngine(
                    target,
                    readyPublication.primaryGeneration(),
                    () ->
                        preparedReady.set(
                            completion.complete(readyPublication::prepareReadyStatus)))) {
                  throw new IllegalStateException(
                      "Update engine primary generation changed before terminal READY publication");
                }
                readyNotification = preparedReady.get();
              }
              if (readyNotification != null) {
                publishUpdateEngineReadyIfCurrent(
                    expectedTargetIndex,
                    target,
                    mirror,
                    targetAttempt,
                    mirrorAttempt,
                    readyPublication,
                    readyNotification);
              }
            } catch (RuntimeException | Error settlementFailure) {
              try {
                completion.close();
              } catch (RuntimeException | Error abandonFailure) {
                suppressEngineStartCleanupFailure(settlementFailure, abandonFailure);
              }
              try {
                failUpdateEngineStartAfterLifecycleRelease(
                    expectedTargetIndex,
                    target,
                    mirror,
                    targetAttempt,
                    mirrorAttempt,
                    settlementFailure,
                    lifecycleSynchronization);
              } catch (RuntimeException | Error reportingFailure) {
                suppressEngineStartCleanupFailure(settlementFailure, reportingFailure);
                settlementFailure.printStackTrace();
              }
            }
          };
      lifecycleSynchronization.runAfterCompletionRelease(settleAfterLifecycleRelease);
      settlementDeferred = true;
    } finally {
      if (!settlementDeferred) {
        completion.close();
      }
    }
  }

  private boolean isCurrentUpdateEngineReplacementSelection(
      int expectedTargetIndex, Leelaz target, Leelaz mirror) {
    if (Lizzie.engineManager != this
        || Lizzie.leelaz != target
        || currentEngineNo != expectedTargetIndex
        || isEmpty
        || !isExactCatalogSlot(this, expectedTargetIndex, target)) {
      return false;
    }
    return mirror == null
        || (Lizzie.leelaz2 == mirror
            && currentEngineNo2 >= 0
            && isExactCatalogSlot(this, currentEngineNo2, mirror));
  }

  private void publishUpdateEngineReadyIfCurrent(
      int expectedTargetIndex,
      Leelaz target,
      Leelaz mirror,
      Leelaz.UpdateEngineStartAttempt targetAttempt,
      Leelaz.UpdateEngineStartAttempt mirrorAttempt,
      Lizzie.PreparedEngineReadyPublication readyPublication,
      EngineStartupStatus.PreparedNotification readyNotification) {
    try {
      dispatchEngineStartupStatusNotification(readyNotification);
    } catch (RuntimeException | Error notificationFailure) {
      notificationFailure.printStackTrace();
    }
    Runnable presentation =
        () -> {
          if (!readyNotification.isCurrent()) {
            return;
          }
          AtomicBoolean exactRuntime = new AtomicBoolean();
          synchronized (ENGINE_SELECTION_STATE_LOCK) {
            if (!isCurrentUpdateEngineReplacementSelection(expectedTargetIndex, target, mirror)) {
              return;
            }
            Lizzie.runIfPrimaryEngine(
                target,
                readyPublication.primaryGeneration(),
                () ->
                    Leelaz.runIfCurrentUpdateEngineStartRuntimes(
                        targetAttempt, mirrorAttempt, () -> exactRuntime.set(true)));
          }
          if (exactRuntime.get() && readyNotification.isCurrent()) {
            readyPublication.runPresentation();
          }
        };
    try {
      if (SwingUtilities.isEventDispatchThread()) {
        presentation.run();
      } else {
        SwingUtilities.invokeLater(presentation);
      }
    } catch (RuntimeException | Error presentationFailure) {
      presentationFailure.printStackTrace();
    }
  }

  protected Thread createUpdateEngineSynchronizationThread(Runnable synchronization) {
    return new Thread(synchronization, "lizzie-update-engine-synchronization");
  }

  /** Test seam around the once-outcome update lifecycle release. */
  protected void closeUpdateEngineLifecycleSynchronization(
      InitialEngineStartupSynchronization synchronization) {
    synchronization.close();
  }

  private static void failCloseUpdateEngineStartAttempts(
      Leelaz.UpdateEngineStartAttempt targetAttempt,
      Leelaz.UpdateEngineStartAttempt mirrorAttempt,
      Throwable primaryFailure) {
    claimUpdateEngineStartFailureCleanups(targetAttempt, mirrorAttempt, primaryFailure).finish();
  }

  private void failUpdateEngineStartAfterLifecycleRelease(
      int expectedTargetIndex,
      Leelaz target,
      Leelaz mirror,
      Leelaz.UpdateEngineStartAttempt targetAttempt,
      Leelaz.UpdateEngineStartAttempt mirrorAttempt,
      Throwable primaryFailure,
      InitialEngineStartupSynchronization lifecycleSynchronization) {
    UpdateEngineStartFailureCleanups failureCleanups =
        claimUpdateEngineStartFailureCleanups(targetAttempt, mirrorAttempt, primaryFailure);
    if (failureCleanups.claimedTarget()) {
      reportUpdateEngineStartFailure(
          expectedTargetIndex, target, targetAttempt, mirror, mirrorAttempt, primaryFailure);
    }
    try {
      closeUpdateEngineLifecycleSynchronization(lifecycleSynchronization);
    } catch (RuntimeException | Error lifecycleFailure) {
      suppressEngineStartCleanupFailure(primaryFailure, lifecycleFailure);
    } finally {
      dispatchUpdateEngineStartFailureCleanupAfterRelease(
          failureCleanups, lifecycleSynchronization::runAfterCompletionRelease);
    }
  }

  private void dispatchUpdateEngineStartFailureCleanupAfterRelease(
      UpdateEngineStartFailureCleanups cleanups,
      java.util.function.Consumer<Runnable> afterLifecycleRelease) {
    if (cleanups == null || cleanups.isEmpty()) {
      return;
    }
    Runnable dispatch = () -> dispatchUpdateEngineStartFailureCleanup(cleanups);
    if (afterLifecycleRelease == null) {
      dispatch.run();
    } else {
      afterLifecycleRelease.accept(dispatch);
    }
  }

  private void dispatchUpdateEngineStartFailureCleanup(UpdateEngineStartFailureCleanups cleanups) {
    if (cleanups.isEmpty()) {
      return;
    }
    Runnable cleanup = cleanups::finish;
    try {
      Thread cleanupThread = createUpdateEngineStartCleanupThread(cleanup);
      cleanupThread.setDaemon(true);
      cleanupThread.start();
      return;
    } catch (RuntimeException | Error schedulingFailure) {
      suppressEngineStartCleanupFailure(cleanups.primaryFailure, schedulingFailure);
    }
    try {
      executeUpdateEngineStartCleanupFallback(cleanup);
    } catch (RuntimeException | Error fallbackFailure) {
      suppressEngineStartCleanupFailure(cleanups.primaryFailure, fallbackFailure);
      cleanup.run();
    }
  }

  protected Thread createUpdateEngineStartCleanupThread(Runnable cleanup) {
    return new Thread(cleanup, "lizzie-update-engine-failed-start-cleanup");
  }

  protected void executeUpdateEngineStartCleanupFallback(Runnable cleanup) {
    java.util.concurrent.ForkJoinPool.commonPool().execute(cleanup);
  }

  private static UpdateEngineStartFailureCleanups claimUpdateEngineStartFailureCleanups(
      Leelaz.UpdateEngineStartAttempt targetAttempt,
      Leelaz.UpdateEngineStartAttempt mirrorAttempt,
      Throwable primaryFailure) {
    Leelaz.UpdateEngineStartFailureCleanup mirrorCleanup = null;
    if (mirrorAttempt != null) {
      try {
        mirrorCleanup = mirrorAttempt.claimFailClose(primaryFailure);
      } catch (RuntimeException | Error cleanupFailure) {
        suppressEngineStartCleanupFailure(primaryFailure, cleanupFailure);
      }
    }
    Leelaz.UpdateEngineStartFailureCleanup targetCleanup = null;
    if (targetAttempt != null) {
      try {
        targetCleanup = targetAttempt.claimFailClose(primaryFailure);
      } catch (RuntimeException | Error cleanupFailure) {
        suppressEngineStartCleanupFailure(primaryFailure, cleanupFailure);
      }
    }
    return new UpdateEngineStartFailureCleanups(targetCleanup, mirrorCleanup, primaryFailure);
  }

  private static final class UpdateEngineStartFailureCleanups {
    private final Leelaz.UpdateEngineStartFailureCleanup targetCleanup;
    private final Leelaz.UpdateEngineStartFailureCleanup mirrorCleanup;
    private final Throwable primaryFailure;

    private UpdateEngineStartFailureCleanups(
        Leelaz.UpdateEngineStartFailureCleanup targetCleanup,
        Leelaz.UpdateEngineStartFailureCleanup mirrorCleanup,
        Throwable primaryFailure) {
      this.targetCleanup = targetCleanup;
      this.mirrorCleanup = mirrorCleanup;
      this.primaryFailure = primaryFailure;
    }

    private boolean isEmpty() {
      return targetCleanup == null && mirrorCleanup == null;
    }

    private boolean claimedTarget() {
      return targetCleanup != null;
    }

    private void finish() {
      finish(mirrorCleanup);
      finish(targetCleanup);
    }

    private void finish(Leelaz.UpdateEngineStartFailureCleanup cleanup) {
      if (cleanup == null) {
        return;
      }
      try {
        cleanup.finish();
      } catch (RuntimeException | Error cleanupFailure) {
        suppressEngineStartCleanupFailure(primaryFailure, cleanupFailure);
      }
    }
  }

  static Leelaz.UpdateEngineStartAttempt beginMirrorUpdateEngineStartAttempt(
      Leelaz.UpdateEngineStartAttempt targetAttempt, Leelaz mirror) {
    if (mirror == null) {
      return null;
    }
    try {
      return mirror.beginUpdateEngineStartAttempt();
    } catch (RuntimeException | Error acquisitionFailure) {
      failCloseUpdateEngineStartAttempts(targetAttempt, null, acquisitionFailure);
      throw acquisitionFailure;
    }
  }

  private static void suppressEngineStartCleanupFailure(
      Throwable primaryFailure, Throwable cleanupFailure) {
    if (primaryFailure == null || cleanupFailure == null || primaryFailure == cleanupFailure) {
      return;
    }
    try {
      primaryFailure.addSuppressed(cleanupFailure);
    } catch (RuntimeException | Error ignored) {
      // Preserve the original startup failure if suppression itself is unavailable.
    }
  }

  void reportUpdateEngineStartFailure(
      int expectedTargetIndex,
      Leelaz target,
      Leelaz.UpdateEngineStartAttempt targetAttempt,
      Leelaz mirror,
      Leelaz.UpdateEngineStartAttempt mirrorAttempt,
      Throwable failure) {
    try {
      if (targetAttempt == null || !targetAttempt.claimFailureReport()) {
        return;
      }
      String detail =
          Leelaz.safeFailureDetail(failure, "update engine board synchronization failed");
      runFailedSwitchCleanup(
          () ->
              publishUpdateEngineStartFailureIfCurrent(
                  expectedTargetIndex, target, targetAttempt, mirror, mirrorAttempt, detail));
    } catch (RuntimeException | Error reportingFailure) {
      suppressEngineStartCleanupFailure(failure, reportingFailure);
    }
  }

  void reportUpdateEngineStartAcquisitionFailure(
      int expectedTargetIndex,
      Leelaz target,
      Object expectedIncarnation,
      long expectedPrimaryGeneration,
      Throwable failure) {
    try {
      String detail =
          Leelaz.safeFailureDetail(failure, "update engine start ownership could not be acquired");
      AtomicReference<EngineStartupStatus.PreparedNotification> statusNotification =
          new AtomicReference<>();
      featurecat.lizzie.EngineStartupStatus.Snapshot expectedStatus =
          Lizzie.engineStartupStatus.snapshot();
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (Lizzie.engineManager != this
            || currentEngineNo != expectedTargetIndex
            || isEmpty
            || !isExactCatalogSlot(this, expectedTargetIndex, target)) {
          return;
        }
        Lizzie.runIfPrimaryEngine(
            target,
            expectedPrimaryGeneration,
            () ->
                target.runIfEngineIncarnationFenceUnchanged(
                    expectedIncarnation,
                    () -> {
                      EngineStartupStatus.PreparedNotification notification =
                          Lizzie.engineStartupStatus.prepareFailedIfCurrent(
                              expectedStatus,
                              "EngineStartup.failed",
                              "AI failed to start - click to repair",
                              detail);
                      statusNotification.set(notification);
                    }));
      }
      if (statusNotification.get() != null) {
        runFailedSwitchCleanup(
            () -> dispatchEngineStartupStatusNotification(statusNotification.get()));
      }
      if (statusNotification.get() != null) {
        dispatchUpdateEngineAcquisitionFailurePresentationIfCurrent(
            expectedTargetIndex,
            target,
            expectedIncarnation,
            expectedPrimaryGeneration,
            statusNotification.get());
      }
    } catch (RuntimeException | Error reportingFailure) {
      suppressEngineStartCleanupFailure(failure, reportingFailure);
    }
  }

  private void publishUpdateEngineStartFailureIfCurrent(
      int expectedTargetIndex,
      Leelaz target,
      Leelaz.UpdateEngineStartAttempt targetAttempt,
      Leelaz mirror,
      Leelaz.UpdateEngineStartAttempt mirrorAttempt,
      String detail) {
    AtomicReference<EngineStartupStatus.PreparedNotification> statusNotification =
        new AtomicReference<>();
    AtomicReference<Throwable> lifecycleMarkFailure = new AtomicReference<>();
    long expectedPrimaryGeneration = targetAttempt.primaryEngineGeneration();
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (Lizzie.engineManager != this
          || currentEngineNo != expectedTargetIndex
          || isEmpty
          || !isExactCatalogSlot(this, expectedTargetIndex, target)) {
        return;
      }
      Lizzie.runIfPrimaryEngine(
          target,
          expectedPrimaryGeneration,
          () ->
              Leelaz.runIfCurrentUpdateEngineStartTargetRuntime(
                  targetAttempt,
                  mirrorAttempt,
                  () -> {
                    try {
                      target.markLifecycleBoardSynchronizationFailed(detail, false);
                    } catch (RuntimeException | Error failure) {
                      lifecycleMarkFailure.set(failure);
                    }
                    EngineStartupStatus.PreparedNotification notification =
                        Lizzie.engineStartupStatus.prepareFailed(
                            "EngineStartup.failed", "AI failed to start - click to repair", detail);
                    statusNotification.set(notification);
                  },
                  mirror == null
                      ? null
                      : () -> {
                        try {
                          mirror.markLifecycleBoardSynchronizationFailed(detail, false);
                        } catch (RuntimeException | Error failure) {
                          Throwable first = lifecycleMarkFailure.get();
                          if (first == null) {
                            lifecycleMarkFailure.set(failure);
                          } else {
                            suppressEngineStartCleanupFailure(first, failure);
                          }
                        }
                      }));
    }
    if (statusNotification.get() != null) {
      runFailedSwitchCleanup(
          () -> dispatchEngineStartupStatusNotification(statusNotification.get()));
    }
    if (statusNotification.get() != null) {
      dispatchUpdateEngineFailurePresentationIfCurrent(
          expectedTargetIndex,
          target,
          targetAttempt,
          expectedPrimaryGeneration,
          statusNotification.get());
    }
    if (lifecycleMarkFailure.get() != null) {
      lifecycleMarkFailure.get().printStackTrace();
    }
  }

  private void dispatchUpdateEngineFailurePresentationIfCurrent(
      int expectedTargetIndex,
      Leelaz target,
      Leelaz.UpdateEngineStartAttempt targetAttempt,
      long expectedPrimaryGeneration,
      EngineStartupStatus.PreparedNotification failureNotification) {
    dispatchUpdateEngineFailurePresentation(
        () -> {
          if (!isCurrentStartFailure(failureNotification)) {
            return;
          }
          AtomicBoolean exactRuntime = new AtomicBoolean();
          synchronized (ENGINE_SELECTION_STATE_LOCK) {
            if (!isCurrentUpdateEngineReplacementSelection(expectedTargetIndex, target, null)) {
              return;
            }
            Lizzie.runIfPrimaryEngine(
                target,
                expectedPrimaryGeneration,
                () -> targetAttempt.runIfCurrentRuntime(() -> exactRuntime.set(true)));
          }
          if (exactRuntime.get() && isCurrentStartFailure(failureNotification)) {
            showEngineSynchronizationFailure(target);
          }
        });
  }

  private void dispatchUpdateEngineAcquisitionFailurePresentationIfCurrent(
      int expectedTargetIndex,
      Leelaz target,
      Object expectedIncarnation,
      long expectedPrimaryGeneration,
      EngineStartupStatus.PreparedNotification failureNotification) {
    dispatchUpdateEngineFailurePresentation(
        () -> {
          if (!isCurrentStartFailure(failureNotification)) {
            return;
          }
          AtomicBoolean exactRuntime = new AtomicBoolean();
          synchronized (ENGINE_SELECTION_STATE_LOCK) {
            if (!isCurrentUpdateEngineReplacementSelection(expectedTargetIndex, target, null)) {
              return;
            }
            Lizzie.runIfPrimaryEngine(
                target,
                expectedPrimaryGeneration,
                () ->
                    target.runIfEngineIncarnationFenceUnchanged(
                        expectedIncarnation, () -> exactRuntime.set(true)));
          }
          if (exactRuntime.get() && isCurrentStartFailure(failureNotification)) {
            showEngineSynchronizationFailure(target);
          }
        });
  }

  private static boolean isCurrentStartFailure(
      EngineStartupStatus.PreparedNotification failureNotification) {
    return failureNotification != null
        && failureNotification.isCurrent()
        && failureNotification.snapshot().state == EngineStartupStatus.State.START_FAILED;
  }

  protected void dispatchUpdateEngineFailurePresentation(Runnable presentation) {
    if (presentation == null) {
      return;
    }
    try {
      if (SwingUtilities.isEventDispatchThread()) {
        presentation.run();
      } else {
        SwingUtilities.invokeLater(presentation);
      }
    } catch (RuntimeException | Error dispatchFailure) {
      dispatchFailure.printStackTrace();
    }
  }

  protected void dispatchEngineStartupStatusNotification(Runnable notification) {
    if (notification == null) {
      return;
    }
    if (SwingUtilities.isEventDispatchThread()) {
      notification.run();
    } else {
      SwingUtilities.invokeLater(notification);
    }
  }

  void publishReplacementEngineMenuStateIfCurrent(
      int expectedIndex, Leelaz engine, Object expectedIncarnation, String title, int iconMode) {
    if (engine == null || expectedIncarnation == null) {
      return;
    }
    Runnable update =
        () -> {
          synchronized (ENGINE_SELECTION_STATE_LOCK) {
            if (Lizzie.engineManager != this
                || Lizzie.leelaz != engine
                || currentEngineNo != expectedIndex
                || isEmpty
                || !isExactCatalogSlot(this, expectedIndex, engine)) {
              return;
            }
            engine.runIfCurrentEngineIncarnation(
                expectedIncarnation,
                () -> {
                  if (Menu.engineMenu != null) {
                    Menu.engineMenu.setText(title);
                  }
                  Menu currentMenu = LizzieFrame.menu;
                  if (currentMenu != null) {
                    currentMenu.changeEngineIcon(expectedIndex, iconMode);
                  }
                });
          }
        };
    if (SwingUtilities.isEventDispatchThread()) {
      update.run();
    } else {
      SwingUtilities.invokeLater(update);
    }
  }

  protected void synchronizeEngineWhenReady(
      Leelaz engine, Runnable synchronization, Runnable afterSync) {
    synchronizeEngineWhenReady(engine, null, synchronization, afterSync);
  }

  void synchronizeEngineWhenReady(
      Leelaz engine,
      Leelaz.UpdateEngineStartAttempt startAttempt,
      Runnable synchronization,
      Runnable afterSync) {
    synchronizeEngineWhenReady(engine, startAttempt, synchronization, afterSync, Runnable::run);
  }

  void synchronizeEngineWhenReady(
      Leelaz engine,
      Leelaz.UpdateEngineStartAttempt startAttempt,
      Runnable synchronization,
      Runnable afterSync,
      java.util.function.Consumer<Runnable> afterLifecycleRelease) {
    AtomicBoolean dispatchClaimed = new AtomicBoolean(false);
    TransactionlessEngineSynchronizationFailureFence transactionlessFailureFence =
        captureTransactionlessEngineSynchronizationFailureFence(engine, startAttempt);
    Runnable restartScopedAfterSync = afterSync;
    Runnable restartBoardSynchronizationFailure = null;
    try {
      Runnable restartScopedSynchronization =
          engine.withCurrentRestartBootstrapReceipt(synchronization);
      restartScopedAfterSync =
          afterSync == null ? null : engine.withCurrentRestartBootstrapReceipt(afterSync);
      Runnable restartBootstrapFailure =
          engine.currentRestartBootstrapFailureAction(
              "restart engine did not complete startup and board synchronization");
      restartBoardSynchronizationFailure =
          engine.currentRestartBoardSynchronizationFailureAction(
              "restart engine board synchronization failed");
      Runnable frozenRestartScopedAfterSync = restartScopedAfterSync;
      Runnable frozenRestartBoardSynchronizationFailure =
          restartBoardSynchronizationFailure;
      Runnable synchronizationWork =
          () -> {
            Throwable synchronizationFailure = null;
            UpdateEngineStartFailureCleanups failureCleanups = null;
            Runnable failurePresentation = null;
            try {
              if (!waitForEngineSynchronizationReadiness(engine)) {
                synchronizationFailure =
                    new IllegalStateException("restart engine did not become ready");
                failureCleanups =
                    claimUpdateEngineStartFailureCleanups(
                        startAttempt, null, synchronizationFailure);
                if (startAttempt == null || failureCleanups.claimedTarget()) {
                  failurePresentation =
                      reportEngineSynchronizationFailureIfCurrent(
                          engine,
                          startAttempt,
                          transactionlessFailureFence,
                          restartBootstrapFailure,
                          synchronizationFailure);
                }
                return;
              }
              restartScopedSynchronization.run();
            } catch (RuntimeException | Error failure) {
              synchronizationFailure = failure;
              failureCleanups =
                  claimUpdateEngineStartFailureCleanups(startAttempt, null, failure);
              if (startAttempt == null || failureCleanups.claimedTarget()) {
                failurePresentation =
                    reportEngineSynchronizationFailureIfCurrent(
                        engine,
                        startAttempt,
                        transactionlessFailureFence,
                        frozenRestartBoardSynchronizationFailure,
                        failure);
              }
              failure.printStackTrace();
            } finally {
              if (frozenRestartScopedAfterSync != null) {
                try {
                  frozenRestartScopedAfterSync.run();
                } catch (RuntimeException | Error cleanupFailure) {
                  if (synchronizationFailure == null) {
                    synchronizationFailure = cleanupFailure;
                    failureCleanups =
                        claimUpdateEngineStartFailureCleanups(
                            startAttempt, null, cleanupFailure);
                    if (startAttempt == null || failureCleanups.claimedTarget()) {
                      failurePresentation =
                          reportEngineSynchronizationFailureIfCurrent(
                              engine,
                              startAttempt,
                              transactionlessFailureFence,
                              frozenRestartBoardSynchronizationFailure,
                              cleanupFailure);
                    }
                  } else {
                    suppressEngineStartCleanupFailure(
                        synchronizationFailure, cleanupFailure);
                  }
                  cleanupFailure.printStackTrace();
                }
              }
              if (failurePresentation != null) {
                try {
                  failurePresentation.run();
                } catch (RuntimeException | Error presentationFailure) {
                  suppressEngineStartCleanupFailure(
                      synchronizationFailure, presentationFailure);
                }
              }
              if (failureCleanups != null) {
                dispatchUpdateEngineStartFailureCleanupAfterRelease(
                    failureCleanups, afterLifecycleRelease);
              }
            }
          };
      Thread synchronizationThread =
          createEngineSynchronizationThread(
              () -> {
                if (dispatchClaimed.compareAndSet(false, true)) {
                  synchronizationWork.run();
                }
              });
      configureEngineSynchronizationThread(synchronizationThread);
      startEngineSynchronizationThread(synchronizationThread);
    } catch (RuntimeException | Error schedulingFailure) {
      if (!dispatchClaimed.compareAndSet(false, true)) {
        // A custom scheduler may start the worker and then throw. The worker owns the lifecycle;
        // treating the same throw as a second failure would race success with rollback.
        schedulingFailure.printStackTrace();
        return;
      }
      UpdateEngineStartFailureCleanups failureCleanups =
          claimUpdateEngineStartFailureCleanups(
              startAttempt, null, schedulingFailure);
      Runnable failurePresentation = null;
      if (startAttempt == null || failureCleanups.claimedTarget()) {
        failurePresentation =
            reportEngineSynchronizationFailureIfCurrent(
                engine,
                startAttempt,
                transactionlessFailureFence,
                restartBoardSynchronizationFailure,
                schedulingFailure);
      }
      try {
        if (restartScopedAfterSync != null) {
          restartScopedAfterSync.run();
        }
      } catch (RuntimeException | Error cleanupFailure) {
        suppressEngineStartCleanupFailure(schedulingFailure, cleanupFailure);
      } finally {
        if (failurePresentation != null) {
          try {
            failurePresentation.run();
          } catch (RuntimeException | Error presentationFailure) {
            suppressEngineStartCleanupFailure(schedulingFailure, presentationFailure);
          }
        }
        dispatchUpdateEngineStartFailureCleanupAfterRelease(
            failureCleanups, afterLifecycleRelease);
      }
      throw schedulingFailure;
    }
  }

  protected Thread createEngineSynchronizationThread(Runnable synchronization) {
    return new Thread(synchronization, "lizzie-engine-switch-synchronization");
  }

  protected void configureEngineSynchronizationThread(Thread worker) {
    worker.setDaemon(true);
  }

  protected void startEngineSynchronizationThread(Thread worker) {
    worker.start();
  }

  private TransactionlessEngineSynchronizationFailureFence
      captureTransactionlessEngineSynchronizationFailureFence(
          Leelaz engine, Leelaz.UpdateEngineStartAttempt startAttempt) {
    if (engine == null || startAttempt != null) {
      return null;
    }
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (Lizzie.engineManager != this || engineSwitchTransaction.get() != null) {
        return null;
      }
      boolean main;
      int engineIndex;
      if (engine == Lizzie.leelaz) {
        main = true;
        engineIndex = currentEngineNo;
      } else if (engine == Lizzie.leelaz2) {
        main = false;
        engineIndex = currentEngineNo2;
      } else {
        return null;
      }
      if (!isExactCatalogSlot(this, engineIndex, engine)) {
        return null;
      }
      Leelaz primaryEngine = Lizzie.leelaz;
      long primaryGeneration = Lizzie.capturePrimaryEngineGeneration(primaryEngine);
      Object engineIncarnation = engine.captureEngineIncarnationFence();
      if (primaryGeneration < 0L || engineIncarnation == null) {
        return null;
      }
      return new TransactionlessEngineSynchronizationFailureFence(
          Lizzie.board,
          engineList,
          engine,
          engineIncarnation,
          main,
          engineIndex,
          primaryEngine,
          primaryGeneration,
          Lizzie.engineStartupStatus.snapshot());
    }
  }

  private Leelaz.EngineIncarnationLease claimTransactionlessEngineSynchronizationFailure(
      TransactionlessEngineSynchronizationFailureFence failureFence) {
    AtomicReference<Leelaz.EngineIncarnationLease> settlementLease = new AtomicReference<>();
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentTransactionlessEngineSynchronizationFailureAuthority(failureFence)) {
        return null;
      }
      Lizzie.runIfPrimaryEngine(
          failureFence.primaryEngine,
          failureFence.primaryGeneration,
          () ->
              settlementLease.set(
                  failureFence.engine.claimEngineIncarnationLease(
                      failureFence.engineIncarnation,
                      () -> failureFence.engine.isLoaded = false)));
    }
    return settlementLease.get();
  }

  private void enqueueTransactionlessEngineSynchronizationFailureIfCurrent(
      TransactionlessEngineSynchronizationFailureFence failureFence) {
    Runnable presentation =
        () -> {
          EngineSynchronizationFailurePresentationLease presentationLease = null;
          try {
            presentationLease =
                claimTransactionlessEngineSynchronizationFailurePresentation(failureFence);
            if (presentationLease != null) {
              showEngineSynchronizationFailure(failureFence.engine);
            }
          } finally {
            if (presentationLease != null) {
              presentationLease.close();
            }
          }
        };
    enqueueEngineSynchronizationFailurePresentation(presentation);
  }

  private EngineSynchronizationFailurePresentationLease
      claimTransactionlessEngineSynchronizationFailurePresentation(
          TransactionlessEngineSynchronizationFailureFence failureFence) {
    Lizzie.EngineAuthorityPresentationLease authorityLease = null;
    Leelaz.EngineIncarnationLease incarnationLease = null;
    boolean claimed = false;
    try {
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (!isCurrentTransactionlessEngineSynchronizationFailureAuthority(failureFence)) {
          return null;
        }
        authorityLease =
            Lizzie.claimEngineAuthorityPresentation(
                failureFence.board,
                this,
                failureFence.primaryEngine,
                failureFence.primaryGeneration);
        if (authorityLease == null
            || !isCurrentTransactionlessEngineSynchronizationFailureAuthority(failureFence)) {
          return null;
        }
        incarnationLease =
            failureFence.engine.claimEngineIncarnationLease(
                failureFence.engineIncarnation);
        if (incarnationLease == null) {
          return null;
        }
        claimed = true;
        return new EngineSynchronizationFailurePresentationLease(
            authorityLease, incarnationLease);
      }
    } finally {
      if (!claimed) {
        Throwable failure = null;
        Leelaz.EngineIncarnationLease incarnationToClose = incarnationLease;
        Lizzie.EngineAuthorityPresentationLease authorityToClose = authorityLease;
        if (incarnationToClose != null) {
          failure = runLifecycleCleanupStep(failure, incarnationToClose::close);
        }
        if (authorityToClose != null) {
          failure = runLifecycleCleanupStep(failure, authorityToClose::close);
        }
        rethrowLifecycleCleanupFailure(failure);
      }
    }
  }

  private boolean isCurrentTransactionlessEngineSynchronizationFailureAuthority(
      TransactionlessEngineSynchronizationFailureFence failureFence) {
    return failureFence != null
        && Lizzie.engineManager == this
        && Lizzie.board == failureFence.board
        && engineList == failureFence.engineCatalog
        && engineSwitchTransaction.get() == null
        && failureFence.engine
            == (failureFence.main ? Lizzie.leelaz : Lizzie.leelaz2)
        && failureFence.engineIndex
            == (failureFence.main ? currentEngineNo : currentEngineNo2)
        && isExactCatalogSlot(this, failureFence.engineIndex, failureFence.engine)
        && failureFence.startupStatus.isCurrent();
  }

  private Runnable reportEngineSynchronizationFailureIfCurrent(
      Leelaz engine,
      Leelaz.UpdateEngineStartAttempt startAttempt,
      TransactionlessEngineSynchronizationFailureFence transactionlessFailureFence,
      Runnable failureAction,
      Throwable primaryFailure) {
    Leelaz.EngineIncarnationLease settlementLease = null;
    boolean transactionlessSettlement = false;
    try {
      EngineSynchronizationFailureFence failureFence =
          captureEngineSynchronizationFailureFence(engine, startAttempt);
      if (startAttempt != null && !startAttempt.claimFailureReport()) {
        return null;
      }
      if (failureFence != null) {
        // Claim the failed incarnation before the receipt action mutates endpoint state. Besides
        // keeping the generic failure commit exact, the lease pins this decision across receipt
        // cleanup so a same-object rebind cannot cross between rollback and UI settlement.
        settlementLease = commitEngineSynchronizationFailureIfCurrent(failureFence);
      } else if (transactionlessFailureFence != null) {
        settlementLease =
            claimTransactionlessEngineSynchronizationFailure(transactionlessFailureFence);
        transactionlessSettlement = settlementLease != null;
      }
      // The frozen restart-receipt action is already exact to its lifecycle owner and reader
      // binding. Retire that failed bootstrap even when generic switch/UI authority was absent or
      // superseded; a stale receipt makes the action a no-op. Run it outside selection/endpoint
      // locks, while retaining any exact incarnation lease claimed above.
      if (failureAction != null) {
        failureAction.run();
      }
      if (settlementLease == null) {
        return null;
      }
      if (transactionlessSettlement) {
        Leelaz.EngineIncarnationLease transferredLease = settlementLease;
        settlementLease = null;
        return () -> {
          Throwable presentationFailure = null;
          try {
            enqueueTransactionlessEngineSynchronizationFailureIfCurrent(
                transactionlessFailureFence);
          } catch (RuntimeException | Error failure) {
            presentationFailure = failure;
          }
          presentationFailure =
              runLifecycleCleanupStep(presentationFailure, transferredLease::close);
          rethrowLifecycleCleanupFailure(presentationFailure);
        };
      }
      if (!retainEngineSynchronizationFailureAuthority(failureFence)) {
        return null;
      }
      Leelaz.EngineIncarnationLease transferredLease = settlementLease;
      settlementLease = null;
      return () -> {
        Throwable presentationFailure = null;
        try {
          enqueueEngineSynchronizationFailureIfCurrent(failureFence);
        } catch (RuntimeException | Error failure) {
          presentationFailure = failure;
        }
        presentationFailure =
            runLifecycleCleanupStep(presentationFailure, transferredLease::close);
        rethrowLifecycleCleanupFailure(presentationFailure);
      };
    } catch (RuntimeException | Error reportingFailure) {
      suppressEngineStartCleanupFailure(primaryFailure, reportingFailure);
      return null;
    } finally {
      if (settlementLease != null) {
        try {
          settlementLease.close();
        } catch (RuntimeException | Error leaseFailure) {
          suppressEngineStartCleanupFailure(primaryFailure, leaseFailure);
        }
      }
    }
  }

  private Leelaz.EngineIncarnationLease commitEngineSynchronizationFailureIfCurrent(
      EngineSynchronizationFailureFence failureFence) {
    AtomicReference<Leelaz.EngineIncarnationLease> settlementLease = new AtomicReference<>();
    AtomicReference<EngineSwitchUiSnapshot> abandonedSnapshot = new AtomicReference<>();
    boolean rejected = false;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentSynchronizationFailureSettlementAuthority(failureFence)
          || engineSwitchTransaction.get() != failureFence.transaction
          || engineSwitchUiTracker.current(failureFence.switchingSnapshot.main)
              != failureFence.switchingSnapshot) {
        boolean primaryCurrent = isCurrentSynchronizationFailurePrimary(failureFence);
        markSynchronizationFailureSupersededLocked(
            failureFence,
            shouldRollbackSupersededSynchronizationFailureLocked(
                failureFence, primaryCurrent),
            abandonedSnapshot);
        rejected = true;
      } else {
        boolean primaryCurrent =
            Lizzie.runIfPrimaryEngine(
                failureFence.primaryEngine,
                failureFence.primaryGeneration,
                () -> {
                  Leelaz.EngineIncarnationLease exactLease =
                      failureFence.engine.claimEngineIncarnationLease(
                          failureFence.engineIncarnation,
                          () -> failureFence.engine.isLoaded = false);
                  if (exactLease != null) {
                    settlementLease.set(exactLease);
                  }
                });
        if (!primaryCurrent || settlementLease.get() == null) {
          markSynchronizationFailureSupersededLocked(
              failureFence,
              shouldRollbackSupersededSynchronizationFailureLocked(
                  failureFence, primaryCurrent),
              abandonedSnapshot);
          rejected = true;
        }
      }
    }
    if (abandonedSnapshot.get() != null) {
      publishEngineSwitchUiState(abandonedSnapshot.get());
    }
    return rejected ? null : settlementLease.get();
  }

  private boolean retainEngineSynchronizationFailureAuthority(
      EngineSynchronizationFailureFence failureFence) {
    AtomicReference<EngineSwitchUiSnapshot> abandonedSnapshot = new AtomicReference<>();
    boolean retained;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      boolean primaryCurrent = isCurrentSynchronizationFailurePrimary(failureFence);
      retained =
          isCurrentSynchronizationFailureSettlementAuthority(failureFence)
              && engineSwitchTransaction.get() == failureFence.transaction
              && engineSwitchUiTracker.current(failureFence.switchingSnapshot.main)
                  == failureFence.switchingSnapshot
              && primaryCurrent;
      if (!retained) {
        markSynchronizationFailureSupersededLocked(
            failureFence,
            shouldRollbackSupersededSynchronizationFailureLocked(
                failureFence, primaryCurrent),
            abandonedSnapshot);
      }
    }
    if (abandonedSnapshot.get() != null) {
      publishEngineSwitchUiState(abandonedSnapshot.get());
    }
    return retained;
  }

  private boolean isCurrentSynchronizationFailurePrimary(
      EngineSynchronizationFailureFence failureFence) {
    AtomicBoolean current = new AtomicBoolean(false);
    Lizzie.runIfPrimaryEngine(
        failureFence.primaryEngine,
        failureFence.primaryGeneration,
        () -> current.set(true));
    return current.get();
  }

  /** Caller holds selection state. */
  private boolean shouldRollbackSupersededSynchronizationFailureLocked(
      EngineSynchronizationFailureFence failureFence, boolean primaryCurrent) {
    return Lizzie.engineManager == this
        && engineSwitchUiTracker.current(failureFence.switchingSnapshot.main)
            == failureFence.switchingSnapshot
        && failureFence.engine
            == (failureFence.switchingSnapshot.main ? Lizzie.leelaz : Lizzie.leelaz2)
        && (!failureFence.switchingSnapshot.main || primaryCurrent);
  }

  /** Caller holds selection state; PRIMARY is also held when {@code rollbackSelection} is true. */
  private void markSynchronizationFailureSupersededLocked(
      EngineSynchronizationFailureFence failureFence,
      boolean rollbackSelection,
      AtomicReference<EngineSwitchUiSnapshot> abandonedSnapshot) {
    failureFence.transaction.synchronizationFailureSuperseded = true;
    if (rollbackSelection) {
      if (failureFence.switchingSnapshot.main) {
        rollbackPrimaryEngineSelection(failureFence.engine, null, -1);
      } else {
        rollbackSecondaryEngineSelection(failureFence.engine, null, -1);
      }
    }
    Optional<EngineSwitchUiSnapshot> abandoned =
        engineSwitchUiTracker.abandonPending(
            failureFence.switchingSnapshot.token,
            failureFence.switchingSnapshot.main);
    if (Lizzie.engineManager == this) {
      abandoned.ifPresent(abandonedSnapshot::set);
    }
  }

  private EngineSynchronizationFailureFence captureEngineSynchronizationFailureFence(
      Leelaz engine, Leelaz.UpdateEngineStartAttempt startAttempt) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      EngineSwitchTransaction transaction = engineSwitchTransaction.get();
      if (transaction == null
          || transaction.targetEngine != engine
          || !transaction.targetInstalled
          || transaction.uiToken <= 0L) {
        return null;
      }
      EngineSwitchUiSnapshot switchingSnapshot =
          engineSwitchUiTracker.current(transaction.main);
      if (switchingSnapshot.token != transaction.uiToken
          || switchingSnapshot.phase != EngineSwitchUiPhase.SWITCHING
          || switchingSnapshot.targetEngineIdentity != engine) {
        return null;
      }
      Object expectedIncarnation;
      if (startAttempt != null) {
        expectedIncarnation = startAttempt.publishedIncarnation();
      } else {
        if (!transaction.targetEngineIncarnationCaptured) {
          return null;
        }
        expectedIncarnation = transaction.targetEngineIncarnation;
      }
      Leelaz primaryEngine = transaction.decisionPrimaryEngine;
      long primaryGeneration = transaction.decisionPrimaryGeneration;
      if (primaryEngine == null || primaryGeneration < 0L) {
        return null;
      }
      return new EngineSynchronizationFailureFence(
          transaction.decisionBoard,
          transaction.decisionEngineCatalog,
          transaction,
          switchingSnapshot,
          engine,
          expectedIncarnation,
          primaryEngine,
          primaryGeneration);
    }
  }

  private void enqueueEngineSynchronizationFailureIfCurrent(
      EngineSynchronizationFailureFence failureFence) {
    EngineSwitchUiSnapshot failedSnapshot;
    EngineSwitchUiSnapshot peerSnapshot;
    EngineStartupStatus.Snapshot startupStatus;
    EngineSynchronizationFailurePresentationAuthority presentationAuthority;
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentSynchronizationFailureAuthority(failureFence)) {
        return;
      }
      failedSnapshot =
          engineSwitchUiTracker.current(failureFence.switchingSnapshot.main);
      if (failedSnapshot.token != failureFence.switchingSnapshot.token
          || failedSnapshot.phase != EngineSwitchUiPhase.FAILED
          || failedSnapshot.targetEngineIdentity != failureFence.engine) {
        return;
      }
      peerSnapshot = engineSwitchUiTracker.current(!failureFence.switchingSnapshot.main);
      startupStatus = Lizzie.engineStartupStatus.snapshot();
      EngineSwitchTransaction transaction = failureFence.transaction;
      if (transaction.failurePresentationUiToken != failedSnapshot.token
          || transaction.failurePresentationPrimaryGeneration < 0L) {
        return;
      }
      presentationAuthority =
          new EngineSynchronizationFailurePresentationAuthority(transaction);
    }
    Runnable presentation =
        () -> {
          if (!startupStatus.isCurrent()) {
            return;
          }
          EngineSynchronizationFailurePresentationLease claimedPresentation =
              claimEngineSynchronizationFailurePresentation(
                  failureFence,
                  presentationAuthority,
                  failedSnapshot,
                  peerSnapshot,
                  startupStatus);
          if (claimedPresentation == null) {
            return;
          }
          try {
            if (isCurrentEngineSynchronizationFailurePresentation(
                failureFence,
                presentationAuthority,
                failedSnapshot,
                peerSnapshot,
                startupStatus)) {
              showEngineSynchronizationFailure(failureFence.engine);
            }
          } finally {
            claimedPresentation.close();
          }
        };
    enqueueEngineSynchronizationFailurePresentation(presentation);
  }

  private EngineSynchronizationFailurePresentationLease
      claimEngineSynchronizationFailurePresentation(
          EngineSynchronizationFailureFence failureFence,
          EngineSynchronizationFailurePresentationAuthority presentationAuthority,
          EngineSwitchUiSnapshot failedSnapshot,
          EngineSwitchUiSnapshot peerSnapshot,
          EngineStartupStatus.Snapshot startupStatus) {
    Lizzie.EngineAuthorityPresentationLease authorityLease = null;
    Leelaz.EngineIncarnationLease incarnationLease = null;
    boolean claimed = false;
    try {
      synchronized (ENGINE_SELECTION_STATE_LOCK) {
        if (!isCurrentSynchronizationFailureAuthority(failureFence)
            || engineSwitchUiTracker.current(failedSnapshot.main) != failedSnapshot
            || engineSwitchUiTracker.current(!failedSnapshot.main) != peerSnapshot
            || !startupStatus.isCurrent()) {
          return null;
        }
        EngineSwitchTransaction presentationTransaction = presentationAuthority.transaction;
        if (presentationTransaction != failureFence.transaction
            || presentationTransaction.failurePresentationUiToken != failedSnapshot.token
            || presentationTransaction.failurePresentationPrimaryGeneration < 0L) {
          return null;
        }
        authorityLease =
            Lizzie.claimEngineAuthorityPresentation(
                failureFence.board,
                this,
                presentationTransaction.failurePresentationPrimaryEngine,
                presentationTransaction.failurePresentationPrimaryGeneration);
        if (authorityLease == null
            || !isCurrentSynchronizationFailureAuthority(failureFence)
            || engineSwitchUiTracker.current(failedSnapshot.main) != failedSnapshot
            || engineSwitchUiTracker.current(!failedSnapshot.main) != peerSnapshot
            || !startupStatus.isCurrent()) {
          return null;
        }
        incarnationLease =
            failureFence.engine.claimEngineIncarnationLease(
                failureFence.engineIncarnation);
        if (incarnationLease == null) {
          return null;
        }
        claimed = true;
        return new EngineSynchronizationFailurePresentationLease(
            authorityLease, incarnationLease);
      }
    } finally {
      if (!claimed) {
        Throwable failure = null;
        Leelaz.EngineIncarnationLease incarnationToClose = incarnationLease;
        Lizzie.EngineAuthorityPresentationLease authorityToClose = authorityLease;
        if (incarnationToClose != null) {
          failure =
              runLifecycleCleanupStep(failure, incarnationToClose::close);
        }
        if (authorityToClose != null) {
          failure = runLifecycleCleanupStep(failure, authorityToClose::close);
        }
        rethrowLifecycleCleanupFailure(failure);
      }
    }
  }

  private boolean isCurrentEngineSynchronizationFailurePresentation(
      EngineSynchronizationFailureFence failureFence,
      EngineSynchronizationFailurePresentationAuthority presentationAuthority,
      EngineSwitchUiSnapshot failedSnapshot,
      EngineSwitchUiSnapshot peerSnapshot,
      EngineStartupStatus.Snapshot startupStatus) {
    AtomicBoolean exactAuthority = new AtomicBoolean(false);
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentSynchronizationFailureAuthority(failureFence)
          || engineSwitchUiTracker.current(failedSnapshot.main) != failedSnapshot
          || engineSwitchUiTracker.current(!failedSnapshot.main) != peerSnapshot
          || !startupStatus.isCurrent()) {
        return false;
      }
      EngineSwitchTransaction presentationTransaction = presentationAuthority.transaction;
      if (presentationTransaction != failureFence.transaction
          || presentationTransaction.failurePresentationUiToken != failedSnapshot.token
          || presentationTransaction.failurePresentationPrimaryGeneration < 0L) {
        return false;
      }
      boolean primaryCurrent =
          Lizzie.runIfPrimaryEngine(
              presentationTransaction.failurePresentationPrimaryEngine,
              presentationTransaction.failurePresentationPrimaryGeneration,
              () ->
                  failureFence.engine.runIfEngineIncarnationFenceUnchanged(
                      failureFence.engineIncarnation,
                      () -> {
                        if (startupStatus.isCurrent()) {
                          exactAuthority.set(true);
                        }
                      }));
      return primaryCurrent && exactAuthority.get();
    }
  }

  private boolean isCurrentSynchronizationFailureAuthority(
      EngineSynchronizationFailureFence failureFence) {
    EngineSwitchTransaction currentTransaction = engineSwitchTransaction.get();
    return failureFence != null
        && Lizzie.engineManager == this
        && Lizzie.board == failureFence.board
        && engineList == failureFence.engineCatalog
        && (currentTransaction == null || currentTransaction == failureFence.transaction)
        && isExactCatalogSlot(
            this, failureFence.transaction.targetIndex, failureFence.engine);
  }

  private boolean isCurrentSynchronizationFailureSettlementAuthority(
      EngineSynchronizationFailureFence failureFence) {
    return isCurrentSynchronizationFailureAuthority(failureFence)
        && failureFence.engine
            == (failureFence.switchingSnapshot.main ? Lizzie.leelaz : Lizzie.leelaz2);
  }

  protected void enqueueEngineSynchronizationFailurePresentation(Runnable presentation) {
    if (SwingUtilities.isEventDispatchThread()) {
      presentation.run();
    } else {
      SwingUtilities.invokeLater(presentation);
    }
  }

  private void synchronizePkEngineWhenReady(
      Leelaz engine,
      Runnable synchronization,
      InitialEngineStartupSynchronization lifecycleSynchronization) {
    synchronizePkEngineWhenReady(
        null,
        engine,
        engine == null ? null : engine.currentEngineIncarnation(),
        synchronization,
        lifecycleSynchronization,
        new PkEngineSynchronization());
  }

  private void synchronizePkEngineWhenReady(
      EngineGameTransaction transaction,
      Leelaz engine,
      Object expectedIncarnation,
      Runnable synchronization,
      InitialEngineStartupSynchronization lifecycleSynchronization,
      PkEngineSynchronization completion) {
    synchronizePkEngineWhenReady(
        transaction,
        engine,
        expectedIncarnation,
        synchronization,
        lifecycleSynchronization::close,
        completion);
  }

  private void synchronizePkEngineWhenReady(
      EngineGameTransaction transaction,
      Leelaz engine,
      Object expectedIncarnation,
      Runnable synchronization,
      Runnable lifecycleClose,
      PkEngineSynchronization completion) {
    Runnable synchronizationWork =
        () -> {
          try {
            if (!waitForEngineSynchronizationReadiness(
                    engine, transaction, expectedIncarnation)
                || (transaction != null
                    && (!isCurrentEngineGameTransaction(transaction)
                        || !engine.isCurrentStartupEngineIncarnation(expectedIncarnation)))) {
              settleFailedPkEngineSynchronization(
                  engine,
                  transaction,
                  expectedIncarnation,
                  lifecycleClose,
                  completion,
                  null);
              return;
            }
            synchronization.run();
          } catch (RuntimeException | Error failure) {
            settleFailedPkEngineSynchronization(
                engine,
                transaction,
                expectedIncarnation,
                lifecycleClose,
                completion,
                failure);
          }
        };
    if (transaction == null) {
      Thread synchronizationThread =
          new Thread(synchronizationWork, "lizzie-pk-engine-synchronization");
      synchronizationThread.start();
      return;
    }
    try {
      if (!dispatchEngineGameWorker(
          transaction,
          "lizzie-pk-engine-synchronization-" + transaction.epoch,
          synchronizationWork,
          true)) {
        settleFailedPkEngineSynchronization(
            engine,
            transaction,
            expectedIncarnation,
            lifecycleClose,
            completion,
            null);
      }
    } catch (RuntimeException | Error schedulingFailure) {
      settleFailedPkEngineSynchronization(
          engine,
          transaction,
          expectedIncarnation,
          lifecycleClose,
          completion,
          schedulingFailure);
    }
  }

  private void failPkEngineSynchronization(Leelaz engine) {
    engine.isLoaded = false;
  }

  private void failPkEngineSynchronization(
      Leelaz engine,
      EngineGameTransaction transaction,
      Object expectedIncarnation) {
    if (engine == null) {
      return;
    }
    if (transaction == null) {
      if (expectedIncarnation == null) {
        failPkEngineSynchronization(engine);
      } else {
        engine.runIfCurrentEngineIncarnation(
            expectedIncarnation, () -> engine.isLoaded = false);
      }
      return;
    }
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGameTransactionLocked(transaction)) {
        return;
      }
      engine.runIfCurrentEngineIncarnation(
          expectedIncarnation, () -> engine.isLoaded = false);
    }
  }

  private void settleFailedPkEngineSynchronization(
      Leelaz engine,
      EngineGameTransaction transaction,
      Object expectedIncarnation,
      Runnable lifecycleClose,
      PkEngineSynchronization completion,
      Throwable failure) {
    Throwable settlementFailure = failure;
    try {
      failPkEngineSynchronization(engine, transaction, expectedIncarnation);
    } catch (RuntimeException | Error stateFailure) {
      settlementFailure = appendEngineGameFailure(settlementFailure, stateFailure);
    }
    try {
      lifecycleClose.run();
    } catch (RuntimeException | Error closeFailure) {
      settlementFailure = appendEngineGameFailure(settlementFailure, closeFailure);
    } finally {
      completion.fail();
    }
    if (settlementFailure != null) {
      settlementFailure.printStackTrace();
    }
  }

  private boolean waitForEngineSynchronizationReadiness(Leelaz engine) {
    return waitForEngineSynchronizationReadiness(
        engine, null, engine == null ? null : engine.currentEngineIncarnation());
  }

  private boolean waitForEngineSynchronizationReadiness(
      Leelaz engine,
      EngineGameTransaction transaction,
      Object expectedIncarnation) {
    if (engine == null) {
      return false;
    }
    long now = System.nanoTime();
    long deadline =
        now
            + TimeUnit.MILLISECONDS.toNanos(
                Math.max(1L, engineSynchronizationTimeoutMillis(engine)));
    boolean tuningTimeoutApplied = false;
    while (true) {
      if (transaction != null
          && (!isCurrentEngineGameTransaction(transaction)
              || !engine.isCurrentStartupEngineIncarnation(expectedIncarnation))) {
        return false;
      }
      afterPkEngineReadinessProbeForTest(transaction, engine, expectedIncarnation);
      if (transaction != null
          && (!isCurrentEngineGameTransaction(transaction)
              || !engine.isCurrentStartupEngineIncarnation(expectedIncarnation))) {
        return false;
      }
      if (!engine.isStarted() || engine.isDownWithError || engine.isNormalEnd) {
        return false;
      }
      if (engine.isLoaded() && !engine.isCheckingName) {
        return true;
      }
      now = System.nanoTime();
      if (!tuningTimeoutApplied && engine.isTuning) {
        deadline =
            now
                + TimeUnit.MILLISECONDS.toNanos(
                    Math.max(1L, engine.engineTuningSynchronizationTimeoutMillis()));
        tuningTimeoutApplied = true;
      }
      if (now >= deadline) {
        return false;
      }
      long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - now));
      try {
        Thread.sleep(Math.min(100L, remainingMillis));
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  void afterPkEngineReadinessProbeForTest(
      EngineGameTransaction transaction, Leelaz engine, Object expectedIncarnation) {}

  PkEngineSynchronization synchronizePkEngineWhenReadyForTest(
      EngineGameTransaction transaction,
      Leelaz engine,
      Object expectedIncarnation,
      Runnable synchronization,
      Runnable lifecycleClose) {
    PkEngineSynchronization completion = new PkEngineSynchronization();
    synchronizePkEngineWhenReady(
        transaction,
        engine,
        expectedIncarnation,
        synchronization,
        lifecycleClose,
        completion);
    return completion;
  }

  protected long engineSynchronizationTimeoutMillis(Leelaz engine) {
    return engine.engineStartupSynchronizationTimeoutMillis();
  }

  protected void showEngineSynchronizationFailure(Leelaz engine) {
    String message = engineFailedText();
    try {
      if (SwingUtilities.isEventDispatchThread()) {
        Utils.showMsg(message);
      } else {
        SwingUtilities.invokeLater(() -> Utils.showMsg(message));
      }
    } catch (RuntimeException | Error presentationFailure) {
      presentationFailure.printStackTrace();
    }
  }

  private EngineLifecycleReservations reservePreparedEngineSwitch(
      Leelaz current, Leelaz target, PreparedEngineSwitch preparedSwitch) {
    if (preparedSwitch == null) {
      return reserveEngineLifecycle(current, target, null);
    }
    return reserveEngineLifecycle(preparedSwitch.lifecycleRestore);
  }

  private EngineLifecycleReservations reserveEngineLifecycle(PreparedLifecycleRestore restore) {
    return reserveEngineLifecycle(restore.previousEngine, restore.targetEngine, restore.owner());
  }

  private EngineLifecycleReservations reserveEngineLifecycle(
      Leelaz current, Leelaz target, Object owner) {
    Leelaz.ExclusiveGtpLifecycleReservation targetReservation = null;
    if (target != null && target != current) {
      targetReservation =
          owner == null
              ? target.beginExclusiveGtpLifecycleReservation()
              : target.beginExclusiveGtpLifecycleReservation(owner);
      if (targetReservation == null) {
        return null;
      }
    }
    Leelaz.ExclusiveGtpLifecycleReservation currentReservation;
    try {
      currentReservation =
          current == null
              ? null
              : owner == null
                  ? current.beginExclusiveGtpLifecycleReservation()
                  : current.beginExclusiveGtpLifecycleReservation(owner);
    } catch (RuntimeException | Error failure) {
      if (targetReservation != null) {
        try {
          targetReservation.close();
        } catch (RuntimeException | Error cleanupFailure) {
          if (cleanupFailure != failure) {
            failure.addSuppressed(cleanupFailure);
          }
        }
      }
      throw failure;
    }
    if (current != null && currentReservation == null) {
      if (targetReservation != null) targetReservation.close();
      return null;
    }
    return new EngineLifecycleReservations(currentReservation, targetReservation);
  }

  private boolean attachRestartInteractionGate(EngineLifecycleReservations reservations) {
    try {
      if (reservations != null
          && reservations.isTrackingFirstWinner()
          && Lizzie.frame != null
          && Lizzie.frame.isDisplayable()) {
        reservations.interactionGate = Lizzie.frame.beginRestartInteractionGate();
      }
      return true;
    } catch (RuntimeException | Error failure) {
      try {
        reservations.close();
      } catch (RuntimeException | Error cleanupFailure) {
        if (cleanupFailure != failure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      if (failure instanceof Error) {
        throw (Error) failure;
      }
      showEngineSynchronizationFailure(Lizzie.leelaz);
      return false;
    }
  }

  private static final class PreparedLifecycleRestore {
    private final Leelaz previousEngine;
    private final Leelaz targetEngine;
    private final Leelaz mirrorEngine;
    private final Object owner;
    private final Leelaz.ExactSnapshotRestoreAdmission admission;
    private final Optional<ExactSnapshotEngineRestore.PreparedRestore> exactRestore;
    private final ArrayList<Movelist> rootMoves;
    private final Double rootKomi;
    private final boolean resumePonder;
    private final AtomicBoolean rootReplayExecuted = new AtomicBoolean(false);
    private volatile long engineSwitchUiToken;
    private volatile int engineSwitchUiIndex = -1;
    private volatile boolean engineSwitchUiMain;
    private volatile boolean engineSwitchUiCompletionAtLifecycleFence;
    private volatile EngineSwitchTransaction engineSwitchTransaction;

    private PreparedLifecycleRestore(
        Leelaz previousEngine,
        Leelaz targetEngine,
        Leelaz mirrorEngine,
        Object owner,
        Leelaz.ExactSnapshotRestoreAdmission admission,
        Optional<ExactSnapshotEngineRestore.PreparedRestore> exactRestore,
        ArrayList<Movelist> rootMoves,
        Double rootKomi,
        boolean resumePonder) {
      this.previousEngine = previousEngine;
      this.targetEngine = targetEngine;
      this.mirrorEngine = mirrorEngine;
      this.owner = owner;
      this.admission = admission;
      this.exactRestore = exactRestore;
      this.rootMoves = Movelist.copyList(rootMoves);
      this.rootKomi = rootKomi;
      this.resumePonder = resumePonder;
    }

    private static PreparedLifecycleRestore capture(
        Leelaz previousEngine,
        Leelaz targetEngine,
        Leelaz mirrorEngine,
        BoardHistoryNode historyTarget,
        Double komi,
        ArrayList<Movelist> rootMoves,
        boolean resumePonder) {
      return capture(
          previousEngine,
          targetEngine,
          mirrorEngine,
          historyTarget,
          komi,
          rootMoves,
          resumePonder,
          null);
    }

    private static PreparedLifecycleRestore capture(
        Leelaz previousEngine,
        Leelaz targetEngine,
        Leelaz mirrorEngine,
        Object owner,
        BoardHistoryNode historyTarget,
        Double komi,
        ArrayList<Movelist> rootMoves,
        boolean resumePonder) {
      if (owner == null) {
        throw new IllegalArgumentException("owner");
      }
      return capture(
          previousEngine,
          targetEngine,
          mirrorEngine,
          historyTarget,
          komi,
          rootMoves,
          resumePonder,
          owner);
    }

    private static PreparedLifecycleRestore capture(
        Leelaz previousEngine,
        Leelaz targetEngine,
        Leelaz mirrorEngine,
        BoardHistoryNode historyTarget,
        Double komi,
        ArrayList<Movelist> rootMoves,
        boolean resumePonder,
        Object retainedLifecycleOwner) {
      if (targetEngine == null) {
        throw new IllegalArgumentException("targetEngine");
      }
      Object owner = retainedLifecycleOwner == null ? new Object() : retainedLifecycleOwner;
      Leelaz.ExactSnapshotRestoreAdmission admission =
          targetEngine.captureExactSnapshotRestoreAdmission(
              Leelaz.ExactSnapshotRestoreOwner.LIFECYCLE, owner, mirrorEngine);
      Optional<ExactSnapshotEngineRestore.PreparedRestore> exactRestore = Optional.empty();
      if (historyTarget != null) {
        exactRestore = ExactSnapshotEngineRestore.prepare(admission, historyTarget);
      }
      return new PreparedLifecycleRestore(
          previousEngine,
          targetEngine,
          mirrorEngine,
          owner,
          admission,
          exactRestore,
          rootMoves,
          komi,
          resumePonder);
    }

    private Object owner() {
      return owner;
    }

    private void executeRootReplay(Board board, boolean loadEngine, boolean isEngineGame) {
      if (!rootReplayExecuted.compareAndSet(false, true)) {
        throw new IllegalStateException("Lifecycle root replay has already been executed");
      }
      targetEngine.requireExactSnapshotRestoreAdmission(admission);
      if (mirrorEngine != null) {
        mirrorEngine.requireExactSnapshotRestoreAdmission(admission);
      }
      Runnable replay =
          () ->
              board.resendMoveToEngineFromRoot(
                  targetEngine, mirrorEngine, loadEngine, isEngineGame, rootMoves, rootKomi);
      targetEngine.withExactSnapshotRestoreAdmission(
          admission,
          () -> {
            if (mirrorEngine == null) {
              replay.run();
            } else {
              mirrorEngine.withExactSnapshotRestoreAdmission(admission, replay);
            }
          });
    }

    private void confirmBoardSynchronization(
        Runnable onSuccess, java.util.function.BiConsumer<Leelaz, String> onFailure) {
      confirmBoardSynchronizationOnce(
          targetEngine,
          () -> {
            if (mirrorEngine == null) {
              onSuccess.run();
              return;
            }
            confirmBoardSynchronizationOnce(
                mirrorEngine,
                onSuccess,
                onFailure,
                "board synchronization mirror fence failed to start");
          },
          onFailure,
          "board synchronization target fence failed to start");
    }

    private static void confirmBoardSynchronizationOnce(
        Leelaz engine,
        Runnable onSuccess,
        java.util.function.BiConsumer<Leelaz, String> onFailure,
        String setupFailureFallback) {
      AtomicBoolean completionClaimed = new AtomicBoolean(false);
      try {
        confirmBoardSynchronization(
            engine,
            () -> {
              if (completionClaimed.compareAndSet(false, true)) {
                onSuccess.run();
              }
            },
            (failedEngine, detail) -> {
              if (completionClaimed.compareAndSet(false, true)) {
                onFailure.accept(failedEngine, detail);
              }
            });
      } catch (RuntimeException | Error failure) {
        if (!completionClaimed.compareAndSet(false, true)) {
          throw failure;
        }
        onFailure.accept(engine, Leelaz.safeFailureDetail(failure, setupFailureFallback));
      }
    }

    private static void confirmBoardSynchronization(
        Leelaz engine,
        Runnable onSuccess,
        java.util.function.BiConsumer<Leelaz, String> onFailure) {
      if (!engine.isStarted() || !engine.isLoaded()) {
        onFailure.accept(engine, "engine was unavailable before board synchronization");
        return;
      }
      engine.confirmBoardSynchronization(onSuccess, detail -> onFailure.accept(engine, detail));
    }

    private Lizzie.PreparedEngineReadyPublication prepareAfterRestore(
        boolean isEngineGame, boolean deferPdaPresentation) {
      long primaryGeneration = Lizzie.capturePrimaryEngineGeneration(targetEngine);
      return Lizzie.prepareInitializeAfterVersionCheck(
          isEngineGame,
          targetEngine,
          false,
          primaryGeneration,
          deferPdaPresentation);
    }

    private void initializeAfterExplicitRestart(boolean resumePonder) {
      targetEngine.initializeAfterExplicitRestartBoardSynchronization(resumePonder);
    }

    private void resumePonderAfterSuccessfulSynchronization() {
      if (resumePonder
          && targetEngine != null
          && targetEngine.isStarted()
          && targetEngine.isLoaded()
          && !targetEngine.isCheckingName) {
        targetEngine.ponder();
        targetEngine.setResponseUpToDate();
      }
    }
  }

  protected static final class PreparedEngineSwitch {
    private final Leelaz targetEngine;
    private final Leelaz previousEngine;
    private final Optional<ExactSnapshotEngineRestore.PreparedRestore> exactRestore;
    private final PreparedLifecycleRestore lifecycleRestore;
    private final int boardWidth;
    private final int boardHeight;
    private final boolean boardEmpty;
    private final boolean targetBoardSizeChanges;
    private final boolean targetOriginalBoardSizeChanges;
    private final float targetKomi;
    private final boolean explicitRestart;
    private final boolean foregroundActivation;
    private final InitialEngineStartupSynchronization initialStartupSynchronization;
    private long engineSwitchUiToken;
    private boolean engineSynchronizationReady;
    private EngineSwitchTransaction engineSwitchTransaction;

    private PreparedEngineSwitch(
        Leelaz targetEngine,
        Leelaz previousEngine,
        PreparedLifecycleRestore lifecycleRestore,
        InitialEngineStartupSynchronization initialStartupSynchronization,
        int boardWidth,
        int boardHeight,
        boolean boardEmpty,
        boolean targetBoardSizeChanges,
        boolean targetOriginalBoardSizeChanges,
        float targetKomi,
        boolean explicitRestart,
        boolean foregroundActivation) {
      this.targetEngine = targetEngine;
      this.previousEngine = previousEngine;
      this.exactRestore = lifecycleRestore.exactRestore;
      this.lifecycleRestore = lifecycleRestore;
      this.initialStartupSynchronization = initialStartupSynchronization;
      this.boardWidth = boardWidth;
      this.boardHeight = boardHeight;
      this.boardEmpty = boardEmpty;
      this.targetBoardSizeChanges = targetBoardSizeChanges;
      this.targetOriginalBoardSizeChanges = targetOriginalBoardSizeChanges;
      this.targetKomi = targetKomi;
      this.explicitRestart = explicitRestart;
      this.foregroundActivation = foregroundActivation;
    }
  }

  private static final class EngineLifecycleReservations implements AutoCloseable {
    private Leelaz.ExclusiveGtpLifecycleReservation current;
    private Leelaz.ExclusiveGtpLifecycleReservation target;
    private LizzieFrame.RestartInteractionGate interactionGate;

    private EngineLifecycleReservations(
        Leelaz.ExclusiveGtpLifecycleReservation current,
        Leelaz.ExclusiveGtpLifecycleReservation target) {
      this.current = current;
      this.target = target;
    }

    private boolean isTrackingFirstWinner() {
      return current != null && current.isTrackingFirstWinner();
    }

    @Override
    public void close() {
      Leelaz.ExclusiveGtpLifecycleReservation targetToClose;
      Leelaz.ExclusiveGtpLifecycleReservation currentToClose;
      LizzieFrame.RestartInteractionGate gateToClose;
      synchronized (this) {
        targetToClose = target;
        currentToClose = current;
        gateToClose = interactionGate;
        target = null;
        current = null;
        interactionGate = null;
      }
      Throwable failure = null;
      if (targetToClose != null) {
        failure = runLifecycleCleanupStep(failure, targetToClose::close);
      }
      if (currentToClose != null) {
        failure = runLifecycleCleanupStep(failure, currentToClose::close);
      }
      if (gateToClose != null) {
        failure = runLifecycleCleanupStep(failure, gateToClose::close);
      }
      rethrowLifecycleCleanupFailure(failure);
    }
  }

  static final class PkEngineSynchronization {
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile boolean successful;
    private volatile Object successfulIncarnation;

    private void markSuccessful(Object incarnation) {
      successfulIncarnation = incarnation;
      successful = true;
    }

    private Object successfulIncarnation() {
      return successfulIncarnation;
    }

    private void complete() {
      completed.countDown();
    }

    private void fail() {
      complete();
    }

    boolean isComplete() {
      return completed.getCount() == 0;
    }

    boolean await() {
      boolean interrupted = false;
      while (true) {
        try {
          completed.await();
          break;
        } catch (InterruptedException ignored) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      return successful;
    }

    boolean awaitUntil(long deadlineNanos) {
      return awaitUntil(deadlineNanos, () -> true);
    }

    boolean awaitUntil(long deadlineNanos, BooleanSupplier keepWaiting) {
      return awaitUntil(() -> deadlineNanos, keepWaiting);
    }

    boolean awaitUntil(LongSupplier deadlineNanos, BooleanSupplier keepWaiting) {
      boolean interrupted = false;
      try {
        while (completed.getCount() != 0L) {
          if (keepWaiting == null || !keepWaiting.getAsBoolean()) {
            return false;
          }
          long remaining = deadlineNanos.getAsLong() - System.nanoTime();
          if (remaining <= 0L) {
            return false;
          }
          try {
            completed.await(
                Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25L)), TimeUnit.NANOSECONDS);
          } catch (InterruptedException waitInterrupted) {
            interrupted = true;
            return false;
          }
        }
        return successful;
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private static final class InitialStartupReservationException extends IllegalStateException {
    private InitialStartupReservationException(String message) {
      super(message);
    }
  }

  /**
   * Owner-scoped 初始引擎同步准入. One instance is created at capture, shared by the captured target and
   * mirror, and stays active across the frozen route, catch-up routes and final handoff. It is not
   * {@link Leelaz.ExactSnapshotRestoreAdmission}.
   */
  static final class InitialEngineSyncAdmission {
    private final AtomicBoolean active = new AtomicBoolean(false);

    boolean isActive() {
      return active.get();
    }

    void activate() {
      active.set(true);
    }

    void deactivate() {
      active.set(false);
    }
  }

  /**
   * Ordinary live-board forwarding submitted by Board. The startup owner decides whether the action
   * runs; Leelaz still enforces enqueue races on the command queue.
   *
   * <p>History-overwrite plans capture occupancy at mutation time. If the startup owner already
   * occupied the engine then, the action stays suppressed even after handoff.
   */
  public static final class OrdinaryLiveBoardForwardingIntent {
    private final Supplier<Boolean> action;
    private final boolean occupiedAtMutation;

    private OrdinaryLiveBoardForwardingIntent(
        Supplier<Boolean> action, boolean occupiedAtMutation) {
      this.action = action;
      this.occupiedAtMutation = occupiedAtMutation;
    }

    public static OrdinaryLiveBoardForwardingIntent of(Supplier<Boolean> action) {
      return capturedAtMutation(false, action);
    }

    public static OrdinaryLiveBoardForwardingIntent capturedAtMutation(
        boolean occupiedAtMutation, Supplier<Boolean> action) {
      if (action == null) {
        throw new IllegalArgumentException("action");
      }
      return new OrdinaryLiveBoardForwardingIntent(action, occupiedAtMutation);
    }

    boolean occupiedAtMutation() {
      return occupiedAtMutation;
    }

    boolean execute() {
      return Boolean.TRUE.equals(action.get());
    }
  }

  /**
   * Engine lifecycle board restore barrier (Issue #223) and the sole owner of 初始引擎同步准入.
   *
   * <p>Owns one lifecycle owner identity, the shared startup admission, owner-local previous/target
   * reservations, captured target/mirror gates and immutable restore routes. Navigation remains
   * available while ordinary live-board updates are suppressed on the captured targets. Each
   * completed route releases its reservations before comparing a new Board frame and, when stale,
   * captures and reserves a new catch-up route under the same owner and admission.
   */
  static final class InitialEngineStartupSynchronization implements AutoCloseable {
    private final Leelaz previousEngine;
    private final Leelaz targetEngine;
    private final Leelaz mirrorEngine;
    private final Board board;
    private final boolean resumePonder;
    private final boolean ensureRootReplayKomiTransport;
    private final Object lifecycleOwner;
    private Leelaz.LifecycleCompletionClaim completionClaim;
    private EngineLifecycleReservations reservations;
    private LizzieFrame.RestartInteractionGate interactionGate;
    private PreparedLifecycleRestore pendingRoute;
    private BoardFrame capturedFrame;
    private boolean stable;
    private boolean engineGameInitialization;
    private EngineGameTransaction engineGameTransaction;
    private boolean trackingFirstWinner;
    private final AtomicBoolean barriersEnded = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CountDownLatch closeSettled = new CountDownLatch(1);
    private volatile Thread closeOwner;
    private volatile Throwable closeFailure;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final InitialEngineSyncAdmission admission = new InitialEngineSyncAdmission();

    /** Test seam: runs outside the board lock before each restore route execution. */
    Runnable beforeRestore;

    /** Test seam: runs outside the board lock immediately before each reservation release. */
    Runnable beforeReservationRelease;

    /** Test seam: runs outside the board lock immediately after each reservation release. */
    Runnable afterReservationRelease;

    private InitialEngineStartupSynchronization(
        Leelaz previousEngine,
        Leelaz targetEngine,
        Leelaz mirrorEngine,
        Board board,
        boolean resumePonder,
        boolean ensureRootReplayKomiTransport,
        Object retainedLifecycleOwner) {
      this.previousEngine = previousEngine;
      this.targetEngine = targetEngine;
      this.mirrorEngine = mirrorEngine == targetEngine ? null : mirrorEngine;
      this.board = board;
      this.resumePonder = resumePonder;
      this.ensureRootReplayKomiTransport = ensureRootReplayKomiTransport;
      this.lifecycleOwner = retainedLifecycleOwner == null ? new Object() : retainedLifecycleOwner;
    }

    static InitialEngineStartupSynchronization capture(
        Leelaz targetEngine, Board board, boolean forceRootReplay) {
      return capture(null, targetEngine, null, board, forceRootReplay, false);
    }

    /**
     * Captures target, mirror, immutable route, Board frame, lifecycle reservations and ordinary
     * update gates before the first external lifecycle side effect.
     */
    static InitialEngineStartupSynchronization capture(
        Leelaz previousEngine,
        Leelaz targetEngine,
        Leelaz mirrorEngine,
        Board board,
        boolean forceRootReplay,
        boolean resumePonder) {
      if (targetEngine == null) {
        throw new IllegalArgumentException("targetEngine");
      }
      if (board == null) {
        throw new IllegalArgumentException("board");
      }
      InitialEngineStartupSynchronization coordination =
          new InitialEngineStartupSynchronization(
              previousEngine, targetEngine, mirrorEngine, board, resumePonder, false, null);
      try {
        coordination.beginSynchronizationBarriers();
        coordination.acquireReservation();
        synchronized (board) {
          coordination.pendingRoute = coordination.captureRoute(forceRootReplay);
          coordination.capturedFrame = BoardFrame.capture(board);
        }
        return coordination;
      } catch (RuntimeException | Error failure) {
        try {
          coordination.close();
        } catch (RuntimeException | Error cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
        throw failure;
      }
    }

    static InitialEngineStartupSynchronization captureRollback(
        Leelaz targetEngine, Board board, boolean resumePonder, Object retainedLifecycleOwner) {
      if (targetEngine == null) {
        throw new IllegalArgumentException("targetEngine");
      }
      if (board == null) {
        throw new IllegalArgumentException("board");
      }
      InitialEngineStartupSynchronization coordination =
          new InitialEngineStartupSynchronization(
              null, targetEngine, null, board, resumePonder, true, retainedLifecycleOwner);
      try {
        coordination.beginSynchronizationBarriers();
        coordination.acquireReservation();
        synchronized (board) {
          coordination.pendingRoute = coordination.captureRoute(false);
          coordination.capturedFrame = BoardFrame.capture(board);
        }
        return coordination;
      } catch (RuntimeException | Error failure) {
        try {
          coordination.close();
        } catch (RuntimeException | Error cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
        throw failure;
      }
    }

    static InitialEngineStartupSynchronization capturePrepared(
        Leelaz previousEngine,
        Leelaz targetEngine,
        Leelaz mirrorEngine,
        Board board,
        boolean forceRootReplay,
        boolean resumePonder) {
      return capturePrepared(
          previousEngine, targetEngine, mirrorEngine, board, forceRootReplay, resumePonder, null);
    }

    static InitialEngineStartupSynchronization capturePrepared(
        Leelaz previousEngine,
        Leelaz targetEngine,
        Leelaz mirrorEngine,
        Board board,
        boolean forceRootReplay,
        boolean resumePonder,
        Object retainedLifecycleOwner) {
      if (targetEngine == null) {
        throw new IllegalArgumentException("targetEngine");
      }
      if (board == null) {
        throw new IllegalArgumentException("board");
      }
      InitialEngineStartupSynchronization coordination =
          new InitialEngineStartupSynchronization(
              previousEngine,
              targetEngine,
              mirrorEngine,
              board,
              resumePonder,
              true,
              retainedLifecycleOwner);
      try {
        coordination.beginSynchronizationBarriers();
        synchronized (board) {
          coordination.pendingRoute = coordination.captureRoute(forceRootReplay);
          coordination.capturedFrame = BoardFrame.capture(board);
        }
        return coordination;
      } catch (RuntimeException | Error failure) {
        try {
          coordination.close();
        } catch (RuntimeException | Error cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
        throw failure;
      }
    }

    /** Executes the restore rounds and, on a stable restore point, marks the engine ready once. */
    void run() {
      runUntilStable();
      initializeAfterRestore();
    }

    /** Executes immutable restore and catch-up rounds without publishing engine readiness. */
    private void runUntilStable() {
      runUntilStable(false);
    }

    private void runUntilStable(boolean engineGameInitialization) {
      this.engineGameInitialization = engineGameInitialization;
      while (!stable) {
        if (beforeRestore != null) {
          beforeRestore.run();
        }
        executePendingRoute(engineGameInitialization);
        if (beforeReservationRelease != null) {
          beforeReservationRelease.run();
        }
        releaseReservation();
        if (afterReservationRelease != null) {
          afterReservationRelease.run();
        }
        synchronized (board) {
          BoardFrame currentFrame = BoardFrame.capture(board);
          if (capturedFrame.matches(currentFrame)) {
            endSynchronizationBarriers();
            stable = true;
          } else {
            capturedFrame = currentFrame;
            pendingRoute = captureRoute(false);
          }
        }
        if (!stable) {
          acquireReservation();
        }
      }
    }

    private synchronized void bindEngineGameTransaction(EngineGameTransaction transaction) {
      if (transaction == null) {
        return;
      }
      if (engineGameTransaction != null && engineGameTransaction != transaction) {
        throw new IllegalStateException(
            "Engine lifecycle synchronization already belongs to another game transaction");
      }
      engineGameTransaction = transaction;
    }

    private boolean runUntilStableForBoundEngineGame() {
      EngineGameTransaction transaction = engineGameTransaction;
      if (transaction == null) {
        runUntilStable(true);
        return true;
      }
      return runEngineGameIoStep(transaction, () -> runUntilStable(true));
    }

    private void executePendingRoute(boolean engineGameInitialization) {
      reconcileCapturedBoardSize();
      PreparedLifecycleRestore route = pendingRoute;
      if (route.exactRestore.isPresent()) {
        if (engineGameInitialization) {
          board.resendMoveToEngine(targetEngine, false, route.exactRestore.orElseThrow(), true);
        } else {
          board.resendMoveToEngine(targetEngine, false, route.exactRestore.orElseThrow());
        }
      } else {
        if (ensureRootReplayKomiTransport) {
          ensureRootReplayKomiCommand(targetEngine, route);
          if (mirrorEngine != null) {
            ensureRootReplayKomiCommand(mirrorEngine, route);
          }
        }
        route.executeRootReplay(board, false, engineGameInitialization);
      }
    }

    private void reconcileCapturedBoardSize() {
      reconcileCapturedBoardSize(targetEngine);
      if (mirrorEngine != null) {
        reconcileCapturedBoardSize(mirrorEngine);
      }
    }

    private void reconcileCapturedBoardSize(Leelaz engine) {
      int frameWidth = capturedFrame.boardWidth;
      int frameHeight = capturedFrame.boardHeight;
      if (engine.width == frameWidth && engine.height == frameHeight) {
        return;
      }
      String command =
          frameWidth != frameHeight
              ? "rectangular_boardsize " + frameWidth + " " + frameHeight
              : "boardsize " + frameWidth;
      PreparedLifecycleRestore route = pendingRoute;
      engine.withExactSnapshotRestoreAdmission(
          route.admission,
          () -> {
            engine.sendCapturedRestoreCommand(command);
            engine.width = frameWidth;
            engine.height = frameHeight;
          });
    }

    private void ensureRootReplayKomiCommand(Leelaz engine, PreparedLifecycleRestore route) {
      if (route.rootKomi == null) {
        return;
      }
      float capturedKomi = (float) (route.rootKomi == 0.0 ? 0.0 : route.rootKomi);
      if (Float.compare(engine.komi, capturedKomi) != 0) {
        return;
      }
      String command = "komi " + (route.rootKomi == 0.0 ? "0" : route.rootKomi);
      engine.withExactSnapshotRestoreAdmission(
          route.admission, () -> engine.sendCapturedRestoreCommand(command));
    }

    private PreparedLifecycleRestore captureRoute(boolean forceRootReplay) {
      BoardHistoryList history = board.getHistory();
      BoardHistoryNode historyTarget =
          forceRootReplay || history == null ? null : history.getCurrentHistoryNode();
      Double currentGameKomi =
          history == null || history.getGameInfo() == null ? null : history.getGameInfo().getKomi();
      return PreparedLifecycleRestore.capture(
          previousEngine,
          targetEngine,
          mirrorEngine,
          lifecycleOwner,
          historyTarget,
          currentGameKomi,
          Movelist.copyList(board.getMoveList()),
          resumePonder);
    }

    private void beginSynchronizationBarriers() {
      admission.activate();
      if (!targetEngine.attachAndBeginInitialEngineSyncAdmission(admission)) {
        throw new InitialStartupReservationException(
            "Engine lifecycle target admission was rejected");
      }
      if (mirrorEngine != null
          && !mirrorEngine.attachAndBeginInitialEngineSyncAdmission(admission)) {
        throw new InitialStartupReservationException(
            "Engine lifecycle mirror admission was rejected");
      }
    }

    private void beginLifecycleCompletionClaim() {
      Leelaz.LifecycleCompletionClaim claim =
          targetEngine.tryBeginLifecycleCompletion(lifecycleOwner, mirrorEngine);
      if (claim == null) {
        throw new InitialStartupReservationException(
            "Engine lifecycle completion claim was rejected");
      }
      completionClaim = claim;
    }

    private void completePkSynchronizationAfterClaimRelease(PkEngineSynchronization completion) {
      Leelaz.LifecycleCompletionClaim claim = completionClaim;
      if (claim == null) {
        throw new IllegalStateException("Engine lifecycle completion claim is unavailable");
      }
      claim.runAfterEndpointRelease(completion::complete);
    }

    private void runAfterCompletionRelease(Runnable action) {
      Leelaz.LifecycleCompletionClaim claim = completionClaim;
      if (claim == null) {
        throw new IllegalStateException("Engine lifecycle completion claim is unavailable");
      }
      claim.runAfterEndpointRelease(action);
    }

    private void endSynchronizationBarriers() {
      if (barriersEnded.compareAndSet(false, true)) {
        Throwable failure = null;
        failure =
            runLifecycleCleanupStep(
                failure, () -> targetEngine.endInitialEngineSyncAdmission(admission));
        if (mirrorEngine != null) {
          failure =
              runLifecycleCleanupStep(
                  failure, () -> mirrorEngine.endInitialEngineSyncAdmission(admission));
        }
        failure = runLifecycleCleanupStep(failure, admission::deactivate);
        rethrowLifecycleCleanupFailure(failure);
      }
    }

    private void detachSynchronizationAdmission() {
      Throwable failure = null;
      failure =
          runLifecycleCleanupStep(
              failure, () -> targetEngine.detachInitialEngineSyncAdmission(admission));
      if (mirrorEngine != null) {
        failure =
            runLifecycleCleanupStep(
                failure, () -> mirrorEngine.detachInitialEngineSyncAdmission(admission));
      }
      rethrowLifecycleCleanupFailure(failure);
    }

    private void acquireReservation() {
      Leelaz.ExclusiveGtpLifecycleReservation targetReservation = null;
      if (targetEngine != previousEngine) {
        targetReservation = targetEngine.beginExclusiveGtpLifecycleReservation(lifecycleOwner);
        if (targetReservation == null) {
          throw new InitialStartupReservationException(
              "Engine lifecycle target reservation was rejected");
        }
      }
      Leelaz.ExclusiveGtpLifecycleReservation previousReservation;
      try {
        previousReservation =
            previousEngine == null
                ? null
                : previousEngine.beginExclusiveGtpLifecycleReservation(lifecycleOwner);
      } catch (RuntimeException | Error failure) {
        if (targetReservation != null) {
          try {
            targetReservation.close();
          } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != failure) {
              failure.addSuppressed(cleanupFailure);
            }
          }
        }
        throw failure;
      }
      if (previousEngine != null && previousReservation == null) {
        if (targetReservation != null) {
          targetReservation.close();
        }
        throw new InitialStartupReservationException(
            "Engine lifecycle previous reservation was rejected");
      }
      installReservations(new EngineLifecycleReservations(previousReservation, targetReservation));
    }

    private void installReservations(EngineLifecycleReservations preparedReservations) {
      if (preparedReservations == null) {
        throw new InitialStartupReservationException("Engine lifecycle reservation was rejected");
      }
      boolean accepted = false;
      synchronized (this) {
        if (!closed.get() && reservations == null) {
          reservations = preparedReservations;
          trackingFirstWinner |= preparedReservations.isTrackingFirstWinner();
          accepted = true;
        }
      }
      if (!accepted) {
        preparedReservations.close();
        throw new InitialStartupReservationException(
            "Engine lifecycle reservation could not be installed");
      }
    }

    private void releaseReservation() {
      EngineLifecycleReservations activeReservations;
      synchronized (this) {
        activeReservations = reservations;
        reservations = null;
      }
      if (activeReservations != null) {
        activeReservations.close();
      }
    }

    private boolean isTrackingFirstWinner() {
      return trackingFirstWinner;
    }

    private boolean attachRestartInteractionGate() {
      try {
        if (trackingFirstWinner && Lizzie.frame != null && Lizzie.frame.isDisplayable()) {
          interactionGate = Lizzie.frame.beginRestartInteractionGate();
        }
        return true;
      } catch (RuntimeException | Error failure) {
        try {
          close();
        } catch (RuntimeException | Error cleanupFailure) {
          if (cleanupFailure != failure) {
            failure.addSuppressed(cleanupFailure);
          }
        }
        if (failure instanceof Error) {
          throw (Error) failure;
        }
        return false;
      }
    }

    private void initializeAfterRestore() {
      if (initialized.compareAndSet(false, true)) {
        Lizzie.initializeAfterVersionCheck(false, targetEngine);
      }
    }

    /**
     * Prepares every failure-prone update restore step without publishing READY, then resumes
     * ponder only when the captured update intent requested it. The caller commits READY only after
     * lifecycle endpoint release and exact group settlement. Must be called after {@link
     * #runUntilStable()} has converged.
     */
    private Lizzie.PreparedEngineReadyPublication prepareUpdateRestore() {
      if (!initialized.compareAndSet(false, true)) {
        throw new IllegalStateException("Update engine restore was already initialized");
      }
      long primaryGeneration = Lizzie.capturePrimaryEngineGeneration(targetEngine);
      Lizzie.PreparedEngineReadyPublication readyPublication =
          Lizzie.prepareInitializeAfterVersionCheck(false, targetEngine, false, primaryGeneration);
      if (readyPublication == null) {
        throw new IllegalStateException(
            "Update engine was no longer the current primary during initialization");
      }
      PreparedLifecycleRestore route = pendingRoute;
      if (route != null) {
        route.resumePonderAfterSuccessfulSynchronization();
      }
      return readyPublication;
    }

    private void confirmFinalBoardSynchronization(
        Runnable onSuccess, java.util.function.Consumer<String> onFailure) {
      Leelaz.LifecycleCompletionClaim claim = completionClaim;
      if (claim == null) {
        throw new IllegalStateException("Engine lifecycle completion claim is unavailable");
      }
      claim.startBoardSynchronizationAttempt(
          () -> completeFinalBoardSynchronizationAttempt(onSuccess, onFailure),
          detail -> claim.completeFailure(detail, onFailure));
    }

    private void completeFinalBoardSynchronizationAttempt(
        Runnable onSuccess, java.util.function.Consumer<String> onFailure) {
      Leelaz.LifecycleCompletionClaim claim = completionClaim;
      try {
        boolean catchUpRequired;
        synchronized (board) {
          BoardFrame currentFrame = BoardFrame.capture(board);
          catchUpRequired = !capturedFrame.matches(currentFrame);
          if (catchUpRequired) {
            stable = false;
            barriersEnded.set(false);
            beginSynchronizationBarriers();
            capturedFrame = currentFrame;
            pendingRoute = captureRoute(false);
          }
        }
        if (!catchUpRequired) {
          claim.completeSuccess(onSuccess, onFailure);
          return;
        }
        acquireReservation();
        EngineGameTransaction transaction = engineGameTransaction;
        if (transaction == null) {
          runUntilStable(engineGameInitialization);
        } else if (!runEngineGameIoStep(
            transaction, () -> runUntilStable(engineGameInitialization))) {
          claim.completeFailure(
              "engine-game lifecycle completion catch-up lost transaction ownership", onFailure);
          return;
        }
        claim.continueBoardSynchronizationAttempt(
            () -> completeFinalBoardSynchronizationAttempt(onSuccess, onFailure),
            detail -> claim.completeFailure(detail, onFailure));
      } catch (RuntimeException | Error failure) {
        claim.completeFailure(
            Leelaz.safeFailureDetail(failure, "lifecycle completion catch-up failed"), onFailure);
      }
    }

    /** Releases owner resources and ends all captured engine gates. */
    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        closeOwner = Thread.currentThread();
        try {
          Throwable failure = null;
          failure = runLifecycleCloseStep(failure, this::releaseReservation);
          if (interactionGate != null) {
            failure = runLifecycleCloseStep(failure, interactionGate::close);
          }
          failure = runLifecycleCloseStep(failure, this::endSynchronizationBarriers);
          failure = runLifecycleCloseStep(failure, this::detachSynchronizationAdmission);
          Leelaz.LifecycleCompletionClaim claim = completionClaim;
          if (claim != null) {
            failure = runLifecycleCloseStep(failure, claim::abandonBeforeFence);
          }
          closeFailure = failure;
          if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
          }
          if (failure instanceof Error) {
            throw (Error) failure;
          }
        } finally {
          closeOwner = null;
          closeSettled.countDown();
        }
        return;
      }
      if (closeOwner == Thread.currentThread()) {
        // A cleanup callback may defensively close its owner again. Waiting here would
        // self-deadlock;
        // the outer owned close remains responsible for publishing its eventual outcome.
        return;
      }
      boolean interrupted = false;
      while (true) {
        try {
          closeSettled.await();
          break;
        } catch (InterruptedException interruption) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      Throwable failure = closeFailure;
      if (failure instanceof RuntimeException) {
        throw (RuntimeException) failure;
      }
      if (failure instanceof Error) {
        throw (Error) failure;
      }
    }

    private static Throwable runLifecycleCloseStep(Throwable firstFailure, Runnable action) {
      try {
        action.run();
      } catch (RuntimeException | Error failure) {
        if (firstFailure == null) {
          return failure;
        }
        suppressEngineStartCleanupFailure(firstFailure, failure);
      }
      return firstFailure;
    }
  }

  /**
   * Immutable identity of the board position the startup restore route was captured for. Captured
   * and compared inside {@code synchronized (Board)} so it is consistent with history navigation.
   */
  static final class BoardFrame {
    private final BoardHistoryNode root;
    private final BoardHistoryNode current;
    private final long contextRevision;
    private final boolean blackToPlay;
    private final double komi;
    private final Zobrist zobrist;
    private final int boardWidth;
    private final int boardHeight;
    private final boolean boardEmpty;
    private final boolean changedKomi;

    private BoardFrame(
        BoardHistoryNode root,
        BoardHistoryNode current,
        long contextRevision,
        boolean blackToPlay,
        double komi,
        Zobrist zobrist,
        int boardWidth,
        int boardHeight,
        boolean boardEmpty,
        boolean changedKomi) {
      this.root = root;
      this.current = current;
      this.contextRevision = contextRevision;
      this.blackToPlay = blackToPlay;
      this.komi = komi;
      this.zobrist = zobrist;
      this.boardWidth = boardWidth;
      this.boardHeight = boardHeight;
      this.boardEmpty = boardEmpty;
      this.changedKomi = changedKomi;
    }

    static BoardFrame capture(Board board) {
      BoardHistoryList history = board == null ? null : board.getHistory();
      BoardData data = history == null ? null : history.getData();
      Zobrist zobrist = data == null ? null : data.zobrist;
      return new BoardFrame(
          history == null ? null : history.getStart(),
          history == null ? null : history.getCurrentHistoryNode(),
          board == null ? 0L : board.getContextRevision(),
          history != null && history.isBlacksTurn(),
          history == null || history.getGameInfo() == null
              ? Double.NaN
              : history.getGameInfo().getKomi(),
          zobrist == null ? null : zobrist.clone(),
          Board.boardWidth,
          Board.boardHeight,
          history != null && history.getStart() == history.getEnd(),
          history != null && history.getGameInfo() != null && history.getGameInfo().changedKomi);
    }

    int boardWidth() {
      return boardWidth;
    }

    int boardHeight() {
      return boardHeight;
    }

    boolean matches(BoardFrame other) {
      return other != null
          && root == other.root
          && current == other.current
          && contextRevision == other.contextRevision
          && blackToPlay == other.blackToPlay
          && Double.compare(komi, other.komi) == 0
          && java.util.Objects.equals(zobrist, other.zobrist)
          && boardWidth == other.boardWidth
          && boardHeight == other.boardHeight
          && boardEmpty == other.boardEmpty
          && changedKomi == other.changedKomi;
    }
  }

  public void changeEngIcoForEndPk() {
    // Lizzie.frame.subBoardRenderer.reverseBestmoves = false;
    //  Lizzie.frame.boardRenderer.reverseBestmoves = false;
    clearFirstSecondEngineCountDown();
    Menu.engineMenu.setEnabled(true);
    if (Lizzie.board.getData().blackToPlay) {
      // switchEngine(Lizzie.frame.toolbar.engineWhite);
      Lizzie.setPrimaryEngine(engineList.get(engineGameInfo.firstEngineIndex));
      engineList.get(engineGameInfo.firstEngineIndex).nameCmd();

      // switchEngine(Lizzie.frame.toolbar.engineBlack);
    } else {
      // switchEngine(Lizzie.frame.toolbar.engineBlack);
      Lizzie.setPrimaryEngine(engineList.get(engineGameInfo.secondEngineIndex));
      engineList.get(engineGameInfo.secondEngineIndex).nameCmd();
      // engineList.get(Lizzie.frame.toolbar.engineWhite).clear();
      // switchEngine(Lizzie.frame.toolbar.engineWhite);
    }
    // this.currentEngineNo = Lizzie.leelaz.currentEngineN();
    // double komi = Lizzie.board.getHistory().getGameInfo().getKomi();
    Lizzie.config.notStartPondering = true;
    // switchEngine(Lizzie.leelaz.currentEngineN(), true);
    // Lizzie.board.setKomi(komi);
    //  Lizzie.board.clearAfterMove();
    EngineManager.currentEngineNo = Lizzie.leelaz.currentEngineN();
    Menu.engineMenu.setText(
        resourceBundle.getString("EngineManager.engine")
            + (Lizzie.leelaz.currentEngineN() + 1)
            + ": "
            + Lizzie.leelaz.oriEnginename);
    changeEngIco(1);
    LizzieFrame.menu.setBtnRankMark();
    if (engineList.get(engineGameInfo.whiteEngineIndex).isKatago
        || engineList.get(engineGameInfo.whiteEngineIndex).isSai)
      Lizzie.board.isPkBoardKataW = true;
    else if (engineList.get(engineGameInfo.blackEngineIndex).isKatago
        || engineList.get(engineGameInfo.blackEngineIndex).isSai)
      Lizzie.board.isPkBoardKataB = true;
    Lizzie.config.chkPkStartNum = false;
    Lizzie.frame.restoreWRN(engineGameInfo.isGenmove);
    Lizzie.frame.refresh();
  }

  public String getEngineName(int index) {
    return engineList.get(index).getEngineName(index);
  }

  private void changeEngIco(int index) {
    LizzieFrame.menu.changeicon(index);
  }

  public static boolean isEngineGame() {
    return isPreEngineGame || isEngineGame;
  }

  //  public void setEngineCountDown(
  //      EngineCountDown engineCountDown,
  //      int leftMinutes,
  //      int countDownSeconds,
  //      int countDownMoves,
  //      Leelaz engine) {
  //    engineCountDown.leftSeconds = leftMinutes * 60;
  //    engineCountDown.countDownSeconds = countDownSeconds;
  //    engineCountDown.countDownMoves = countDownMoves;
  //    engineCountDown.engine = engine;
  //  }

  private void clearFirstSecondEngineCountDown() {
    firstEngineCountDown = null;
    secondEngineCountDown = null;
  }

  public void clearPlayingAgainstHumanEngineCountDown() {
    playingAgainstHumanEngineCountDown = null;
  }

  public void stopCountDown() {
    if (timeScheduled != null) {
      timeScheduled.shutdownNow();
      timeScheduled = null;
    }
  }

  public void StartCountDown() {
    startCountDown(null);
  }

  private void StartCountDown(EngineGameTransaction transaction) {
    startCountDown(transaction);
  }

  private void startCountDown(EngineGameTransaction transaction) {
    stopCountDown();
    timeScheduledTimes = 0;
    timeScheduled = new ScheduledThreadPoolExecutor(1);
    timeScheduled.scheduleAtFixedRate(
        new Runnable() {
          @Override
          public void run() {
            timeScheduledTimes++;
            if (timeScheduledTimes >= 10) {
              timeScheduledTimes = 0;
              EngineCountDown countDown = null;
              if (transaction != null) {
                countDown = exactEngineGameCountDownForTick(transaction);
              } else if (Lizzie.frame.isPlayingAgainstLeelaz
                  && playingAgainstHumanEngineCountDown != null
                  && Lizzie.board.getHistory().isBlacksTurn()
                      == playingAgainstHumanEngineCountDown.isPlayBlack)
                countDown = playingAgainstHumanEngineCountDown;
              if (countDown != null) {
                countDown.countDownCentiseconds();
              }
            }
          }
        },
        0,
        1,
        TimeUnit.MILLISECONDS);
  }

  private static EngineCountDown exactEngineGameCountDownForTick(
      EngineGameTransaction transaction) {
    synchronized (ENGINE_SELECTION_STATE_LOCK) {
      if (!isCurrentEngineGameTransactionLocked(transaction)
          || transaction.phase != EngineGamePhase.ACTIVE
          || Lizzie.board == null) {
        return null;
      }
      boolean blackToPlay = Lizzie.board.getHistory().isBlacksTurn();
      EngineCountDown clock =
          blackToPlay ? transaction.blackCountDown : transaction.whiteCountDown;
      Leelaz participant = blackToPlay ? transaction.blackEngine : transaction.whiteEngine;
      Object incarnation =
          blackToPlay ? transaction.blackIncarnation : transaction.whiteIncarnation;
      return clock != null
              && clock.belongsTo(participant, blackToPlay)
              && participant.isCurrentLiveEngineIncarnation(incarnation)
          ? clock
          : null;
    }
  }

  static void tickEngineGameCountDownForTest(EngineGameTransaction transaction) {
    EngineCountDown clock = exactEngineGameCountDownForTick(transaction);
    if (clock != null) {
      clock.countDownCentiseconds();
    }
  }
}
