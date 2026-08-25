package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
import featurecat.lizzie.EngineStartupStatus;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.gtpconfig.GtpConfigurationProbe;
import featurecat.lizzie.analysis.remote.EngineTransport;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.EngineFailedMessage;
import featurecat.lizzie.gui.JFontCheckBox;
import featurecat.lizzie.gui.JFontLabel;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Message;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Movelist;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.CommandLaunchHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.Utils;
import featurecat.lizzie.util.YikeSyncDebugLog;
import featurecat.lizzie.logging.EngineObservation;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.Box;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.jdesktop.swingx.util.OS;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * An interface with leelaz go engine. Can be adapted for GTP, but is specifically designed for
 * GCP's Leela Zero. leelaz is modified to output information as it ponders see
 * www.github.com/gcp/leela-zero
 */
public class Leelaz {
  private static final AtomicLong ENGINE_ARBITRATION_ORDER_SEQUENCE = new AtomicLong();
  private static final AtomicInteger COMMAND_DISPATCH_THREAD_SEQUENCE = new AtomicInteger();
  private static final Executor COMMAND_DISPATCH_EXECUTOR =
      Executors.newCachedThreadPool(Leelaz::newCommandDispatchThread);

  public enum ExclusiveGtpLeaseAvailability {
    AVAILABLE,
    NO_FOREGROUND_ENGINE,
    NOT_CURRENT_FOREGROUND_ENGINE,
    ENGINE_NOT_READY,
    NOT_KATAGO,
    MISSING_CAPABILITY,
    ENGINE_GAME,
    PLAY_MODE,
    HUMAN_SL_GAME,
    GENMOVE,
    READBOARD_GMA,
    EXISTING_LEASE,
    ENGINE_LIFECYCLE,
    ENGINE_STATE_UNRESTORED,
    APPLICATION_EXCLUSIVE_MODE
  }

  public enum ForegroundAnalysisLeaseFailure {
    INITIAL_STOP_SEND_FAILED,
    INITIAL_STOP_ERROR_RESPONSE,
    INITIAL_STOP_TIMEOUT,
    FINAL_STOP_SEND_FAILED,
    FINAL_STOP_ERROR_RESPONSE,
    FINAL_STOP_TIMEOUT,
    TRANSPORT_CLOSED,
    RESTORE_FAILED
  }

  enum ExactSnapshotRestoreOwner {
    ORDINARY(false),
    READ_BOARD_GMA(true),
    FOREGROUND(false),
    LIFECYCLE(false),
    BOARD_SYNC(true);

    private final boolean preclear;

    ExactSnapshotRestoreOwner(boolean preclear) {
      this.preclear = preclear;
    }

    boolean preclear() {
      return preclear;
    }
  }

  public enum TrackingStreamLeaseFailure {
    INITIAL_STOP_SEND_FAILED,
    INITIAL_STOP_ERROR_RESPONSE,
    INITIAL_STOP_TIMEOUT,
    ACTIVE_COMMAND_SEND_FAILED,
    FINAL_STOP_SEND_FAILED,
    FINAL_STOP_ERROR_RESPONSE,
    FINAL_STOP_TIMEOUT,
    TRANSPORT_CLOSED
  }

  private static final List<String> FLASH_ANALYSIS_GTP_COMMANDS =
      List.of(
          "stop",
          "boardsize",
          "komi",
          "kata-get-rules",
          "kata-set-rules",
          "clear_board",
          "play",
          "set_position",
          "kata-analyze");

  private enum StartupCommandKind {
    NONE(false),
    CLOSE_BUNDLED(true),
    KATA(true),
    LEELA_SAI(true);

    private final boolean closeBundledStartupDialog;

    StartupCommandKind(boolean closeBundledStartupDialog) {
      this.closeBundledStartupDialog = closeBundledStartupDialog;
    }
  }

  /** Deferred parser work carrying the exact startup generation and reader that produced it. */
  private static final class StartupCommandAction {
    private static final StartupCommandAction NONE =
        new StartupCommandAction(StartupCommandKind.NONE, -1L, null, null, false, false);

    private final StartupCommandKind kind;
    private final long expectedPrimaryGeneration;
    private final Object expectedEngineIncarnation;
    private final EngineManager.EngineGameTransaction engineGameTransaction;
    private final boolean publishReadyIcon;
    private final boolean suppressGlobalEnginePresentation;

    private StartupCommandAction(
        StartupCommandKind kind,
        long expectedPrimaryGeneration,
        Object expectedEngineIncarnation,
        EngineManager.EngineGameTransaction engineGameTransaction,
        boolean publishReadyIcon,
        boolean suppressGlobalEnginePresentation) {
      this.kind = kind;
      this.expectedPrimaryGeneration = expectedPrimaryGeneration;
      this.expectedEngineIncarnation = expectedEngineIncarnation;
      this.engineGameTransaction = engineGameTransaction;
      this.publishReadyIcon = publishReadyIcon;
      this.suppressGlobalEnginePresentation = suppressGlobalEnginePresentation;
    }

    private static StartupCommandAction of(
        StartupCommandKind kind,
        long expectedPrimaryGeneration,
        Object expectedEngineIncarnation,
        EngineManager.EngineGameTransaction engineGameTransaction,
        boolean publishReadyIcon,
        boolean suppressGlobalEnginePresentation) {
      return kind == StartupCommandKind.NONE
          ? NONE
          : new StartupCommandAction(
              kind,
              expectedPrimaryGeneration,
              expectedEngineIncarnation,
              engineGameTransaction,
              publishReadyIcon,
              suppressGlobalEnginePresentation);
    }
  }

  /** Marks startup parser post-work so the reader loop fails closed instead of swallowing it. */
  private static final class StartupPostActionFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private StartupPostActionFailure(Throwable cause) {
      super("engine startup post-action failed", cause);
    }
  }

  private enum ExclusiveGtpWritePhase {
    INITIAL_STOP,
    ACTIVE_COMMAND,
    RELEASE_STOP
  }

  private enum ExclusiveGtpWriteResult {
    NOT_CLAIMED,
    SENT,
    SEND_FAILED
  }

  private enum TrackingWriteState {
    UNSENT,
    WRITING,
    SENT,
    FAILED
  }

  private enum ExclusiveGtpReleasePolicy {
    FOREGROUND_RESTORE,
    STREAM_ONLY
  }

  public enum TrackingHandoffKind {
    FOREGROUND_ANALYSIS,
    RETAINED_ENGINE_MODE
  }

  public enum TrackingHandoffAvailability {
    ACCEPTED_PENDING,
    BUSY,
    NOT_TRACKING,
    INVALID_TARGET
  }

  public enum TrackingHandoffState {
    ACCEPTED_PENDING,
    ACTIVATING,
    ACTIVE,
    FAILED
  }

  public enum TrackingHandoffFailure {
    TRACKING_FAILED,
    CONTEXT_INVALIDATED,
    TARGET_CANCELLED,
    ACTIVATION_FAILED
  }

  public enum TrackingReleaseDisposition {
    ACTIVE,
    FROZEN_BY_SAFE,
    CLEARED
  }

  public enum TrackingReleaseReason {
    SAFE_READ_ONLY_QUERY,
    ORDINARY_OPERATION
  }

  @FunctionalInterface
  public interface TrackingReleaseDispositionObserver {
    void onDispositionChanged(TrackingReleaseDisposition disposition);

    default void onReleaseClaimed(TrackingReleaseReason reason) {}
  }

  public interface TrackingHandoffTarget {
    TrackingHandoffKind kind();

    boolean isCurrent();

    void activate(TrackingHandoffActivation activation);

    void fail(TrackingHandoffFailure failure);
  }

  public interface TrackingHandoffActivation {
    boolean activateForegroundAnalysis(Consumer<String> lineConsumer, Runnable onClosed);

    boolean completeRetainedEngineMode();

    default EngineModeReservation beginRetainedEngineModeReservation() {
      return null;
    }
  }

  // private static final long MINUTE = 60 * 1000; // number of milliseconds in a minute
  private static final Runnable NO_OP_RESPONSE_HANDLER = () -> {};
  private static final int NO_RESPONSE_COMMAND_ID = -1;
  private static final long FOREGROUND_INITIAL_STOP_TIMEOUT_MILLIS = 8000L;
  private static final long FOREGROUND_RELEASE_STOP_TIMEOUT_MILLIS = 8000L;
  private static final long STARTUP_COMMAND_DELIVERY_TIMEOUT_MILLIS = 8000L;

  // private long maxAnalyzeTimeMillis; // , maxThinkingTimeMillis;
  int cmdNumber;
  int modifyNumber;
  private int currentCmdNum;
  // public int modifyCmdNum;
  // private boolean isResponse=false;
  private ArrayDeque<QueuedCommand> cmdQueue;
  private ArrayDeque<QueuedCommand> foregroundRestoreQueue;
  private volatile boolean normalCommandSendInProgress;
  private QueuedCommand normalCommandBeingSent;
  private final AtomicLong eventDispatchCommandRequests = new AtomicLong();
  private final AtomicBoolean eventDispatchCommandScheduled = new AtomicBoolean();
  private final ThreadLocal<ExclusiveGtpSession> foregroundRestoreCommandSession =
      new ThreadLocal<>();
  private static final ThreadLocal<ExactSnapshotRestoreAdmission>
      exactSnapshotRestoreAdmissionContext = new ThreadLocal<>();
  private static final ThreadLocal<LifecycleCompletionClaim> lifecycleCompletionCommandContext =
      new ThreadLocal<>();
  private static final ThreadLocal<OrdinaryLiveBoardForwardingExecution>
      ordinaryLiveBoardForwardingContext = new ThreadLocal<>();
  private static final ThreadLocal<EngineManager.EngineGameTransaction>
      engineGameStartupCommandContext = new ThreadLocal<>();
  /** Isolates a retired match participant restart from ordinary foreground startup presentation. */
  private static final ThreadLocal<Boolean> deferredEngineGameRecoveryStartupContext =
      new ThreadLocal<>();

  private volatile boolean foregroundRestoreInProgress;
  private volatile boolean suppressNormalCommandsForForegroundAnalysis;
  private volatile ExclusiveGtpSession foregroundRestoreSession;
  private ArrayDeque<PendingResponseHandler> pendingResponseHandlers;
  private final AtomicReference<EngineGameResponseHandler> activeEngineGameResponseHandler =
      new AtomicReference<>();
  private volatile boolean loadSgfResponseQuarantined;
  private final AtomicInteger loadSgfResponseCommandIds = new AtomicInteger(1);
  private final AtomicInteger readBoardGmaResponseCommandIds = new AtomicInteger(700000000);
  private final AtomicInteger engineGameResponseCommandIds = new AtomicInteger(600000000);
  private final AtomicInteger exclusiveGtpResponseCommandIds = new AtomicInteger(800000000);
  private final AtomicInteger boardSynchronizationResponseCommandIds =
      new AtomicInteger(900000000);
  private final long engineArbitrationOrder = ENGINE_ARBITRATION_ORDER_SEQUENCE.incrementAndGet();
  private volatile boolean currentCommandResponseError;
  private volatile String currentCommandResponseLine = "";

  private Process process;
  private transient EngineTransport remoteTransport;
  private volatile Object engineArbitrationLock = new Object();
  /** Admission generations keep delayed local state publication from overwriting a newer command. */
  private volatile long komiAdmissionGeneration;
  private volatile long boardSizeAdmissionGeneration;
  /** Serializes an admission-generation check with its corresponding local state publication. */
  private volatile Object statefulOrdinaryPublicationLock = new Object();
  private final ThreadLocal<UpdateEngineStartAttempt> updateEngineStartAttemptContext =
      new ThreadLocal<>();
  private final ThreadLocal<Object> analysisOutputRecoveryTokenContext = new ThreadLocal<>();
  private final ThreadLocal<Object> startupPostActionCommandContext = new ThreadLocal<>();
  private UpdateEngineStartAttempt activeUpdateEngineStartAttempt;
  private volatile ExclusiveGtpSession exclusiveGtpSession;
  private boolean exclusiveGtpLifecycleTransition;
  private boolean exclusiveGtpLifecycleQueueGate;
  private Object exclusiveGtpLifecycleOwner;
  private int exclusiveGtpLifecycleDepth;
  private final AtomicLong restartBootstrapAttemptIds = new AtomicLong();
  private final ThreadLocal<RestartBootstrapReceipt> restartBootstrapReceiptContext =
      new ThreadLocal<>();
  private RestartBootstrapReceipt restartBootstrapReceipt;
  /** Serializes constructor-bypassing/test instances whose endpoint order token is identical. */
  private static final Object LIFECYCLE_COMPLETION_PAIR_TIE_LOCK = new Object();

  /** Active owner-identified lifecycle completion on this authority or frozen mirror endpoint. */
  private volatile LifecycleCompletionClaim lifecycleCompletionClaim;

  private BufferedReader inputStream;
  private volatile BufferedOutputStream outputStream;
  private BufferedReader errorStream;
  private final AtomicLong processIncarnationIds = new AtomicLong();
  private volatile ReaderStreamBinding readerStreamBinding;
  private boolean readerTerminalCleanupInProgress;
  private volatile boolean readerStreamRebindInProgress;
  private int engineIncarnationLeaseDepth;
  private final IdentityHashMap<Thread, Integer> engineIncarnationLeaseOwners =
      new IdentityHashMap<>();
  private volatile TrackingHandoffClaim trackingHandoffGate;
  private final ArrayDeque<String> recentStdoutLines = new ArrayDeque<String>();
  private final ArrayDeque<String> recentStderrLines = new ArrayDeque<String>();
  private volatile String loggingEngineId;

  // public Board board;
  private volatile List<MoveData> bestMoves;
  private final Object previousAnalysisSummaryLock = new Object();
  private AnalysisSummaryBatch previousAnalysisSummaryBatch;
  // private List<MoveData> bestMovesTemp;
  // public boolean canGetGenmoveInfo = false;
  private boolean underPonder = false;
  public boolean canGetSummaryInfo = false;
  // public boolean canGetChatInfo = false;
  // public boolean canGetGenmoveInfoGen = false;
  // public boolean getGenmoveInfoPrevious= false;
  // private List<LeelazListener> listeners;

  private boolean isPondering;
  private long startPonderTime;
  private boolean showStopTips = true;

  // fixed_handicap
  public boolean isSettingHandicap = false;

  // genmove
  public boolean isThinking = false;
  public boolean isInputCommand = false;

  public volatile boolean getRcentLine = false;
  private final Object parameterReadTimeoutLock = new Object();
  private long parameterReadTimeoutGeneration;
  private int recentLineNumber = 0;
  public volatile String recentRulesLine = "";
  public int usingSpecificRules = -1; // 1=中国规则2=中古规则3=日本规则4=TT规则5=其他规则
  public boolean preload = false;
  public volatile boolean started = false;
  public volatile boolean isDownWithError = false;
  public volatile boolean isLoaded = false;
  private volatile int initialBoardSynchronizationDepth = 0;
  private volatile EngineManager.InitialEngineSyncAdmission initialEngineSyncAdmission;
  private volatile Object initialBoardSynchronizationLock = new Object();
  private volatile long bundledStartupToken = 0L;
  private volatile long startupPrimaryEngineGeneration = -1L;
  private long pdaStartupQueryGeneration;
  private volatile boolean openClFp32CompatibilityActive = false;
  private volatile boolean launchCommandSetsKataGoThreads = false;
  private final AtomicBoolean openClCompatibilityRecoveryAttempted = new AtomicBoolean(false);
  public boolean isCheckingVersion;
  public volatile boolean isCheckingName;
  public String initialCommand;
  public String gtpConfigurationProtocol = "";
  public JSONObject gtpConfigurationProfile;
  private volatile boolean isCheckingPda = false;
  public boolean isKataGoPda = false;
  public boolean isDymPda = false;
  public boolean isStaticPda = false;
  public boolean canRestoreDymPda = false;
  public double pda = 0;
  public double wrn = 0;
  private double pdaBeforeGame = 0;
  public double pdaCap = 0;
  public boolean startAutoAna = false;
  // for Multiple Engine
  public String oriEngineCommand = "";
  public String engineCommand;
  private List<String> commands;
  //	private String currentWeightFile = "";
  //	private String currentWeight = "";
  // public boolean switching = false;
  private int currentEngineN = -1;
  private ScheduledExecutorService executor;
  private ScheduledExecutorService executorErr;
  ArrayList<Double> tempcount = new ArrayList<Double>();
  // dynamic komi and opponent komi as reported by dynamic-komi version of leelaz
  //	private float dynamicKomi = Float.NaN;
  //	private float dynamicOppKomi = Float.NaN;

  public int version = -1;
  //	public ArrayList<Integer> heatcount = new ArrayList<Integer>();
  public String currentEnginename = "";
  public String bestMovesEnginename = "";
  public String oriEnginename = "";
  public boolean autoAnalysed = false;
  private static final long BUNDLED_ENGINE_START_TIMEOUT_MS = 90000L;
  private static final long NVIDIA_ENGINE_START_TIMEOUT_MS = 180000L;
  private static final long FIRST_OPENCL_TUNING_START_TIMEOUT_MS = 600000L;
  private static final long LOAD_SGF_SEND_FAILURE_CLEANUP_TIMEOUT_MILLIS = 1000L;
  private static final long LOAD_SGF_PENDING_RESPONSE_GRACE_TIMEOUT_MILLIS = 3000L;
  private static final long LOAD_SGF_NO_RESPONSE_EXTRA_TIMEOUT_MILLIS = 2000L;
  private static final long LOAD_SGF_NO_RESPONSE_TIMEOUT_MILLIS =
      LOAD_SGF_PENDING_RESPONSE_GRACE_TIMEOUT_MILLIS + LOAD_SGF_NO_RESPONSE_EXTRA_TIMEOUT_MILLIS;
  private static final int ENGINE_DIAGNOSTIC_TAIL_LINES = 40;
  private static final ScheduledExecutorService LOAD_SGF_CLEANUP_EXECUTOR =
      Executors.newSingleThreadScheduledExecutor(Leelaz::newLoadSgfCleanupThread);
  //	private boolean isSaving = false;
  public boolean isResigning = false;
  //	public boolean isClosingAutoAna = false;
  public boolean isColorEngine = false;
  public int stage = -1;
  public float komi = 7.5f;
  public float orikomi = 7.5f;
  public int blackResignMoveCounts = 0;
  public int whiteResignMoveCounts = 0;
  public boolean resigned = false;
  //	public boolean isManualB=false;
  //	public boolean isManualW=false;
  public boolean doublePass = false;
  public boolean outOfMoveNum = false;
  public boolean played = false;
  private boolean canSetNotPlayed = false;

  public boolean isKatago = false;
  public boolean isKatagoCustom = false;
  public boolean noAnalyze = false;
  public boolean isSai = false;
  private boolean isLeela = false;
  private boolean isSayuri = false;
  public boolean isChanged = false;
  public volatile double scoreMean = 0;
  public volatile double scoreStdev = 0;
  private boolean isCommandLine = false;
  public int width = 19;
  public int height = 19;
  public int oriWidth = 19;
  public int oriHeight = 19;
  public boolean firstLoad = false;
  Message msg;
  public boolean playNow = false;
  public boolean isZen = false;
  public boolean canAddPlayer = true;
  public boolean requireResponseBeforeSend = false;
  public boolean noLcb = false;
  // private boolean isInfoLine = false;
  // private boolean isNotifying = false;
  public boolean isSSH = false;
  // public boolean isScreen = false;
  public boolean isheatmap = false;
  public boolean iskataHeatmapShowOwner = false;
  public ArrayList<Integer> heatcount = new ArrayList<Integer>();

  public long pkMoveStartTime;
  public long pkMoveTime;
  // private int prepareNoGetGenmoveInfo = -1;
  // public long pkMoveTimeAll=0;
  public long pkMoveTimeGame = 0;
  public boolean canSuicidal = false;
  // public int genmoveNode = 0;
  public int anaGameResignCount = 0;
  public double heatwinrate = -1;
  public int symmetry = 0;
  public double heatScore;
  private boolean heatCanGetPolicy;
  private boolean heatCanGetOwnership;
  private Object manualGenmoveRequestOwner;

  private boolean canheatRedraw = false;
  public ArrayList<Double> heatPolicy = new ArrayList<Double>();
  public ArrayList<Double> heatOwnership = new ArrayList<Double>();
  public boolean isGamePaused = false;
  // public boolean isReadyForGenmoveGame=false;
  // private boolean isModifying=false;
  // private int ignoreCmdNumber=0;
  public volatile boolean isTuning = false;
  public volatile boolean isNormalEnd = false;
  public boolean canCheckAlive = true;
  public boolean isLeela0110 = false;
  private List<MoveData> leela0110BestMoves;
  private long leela0110BestMovesEpoch = -1L;
  private Timer leela0110PonderingTimer;
  private BoardData leela0110PonderingBoardData;
  private EngineManager.EngineGameTransaction leela0110PonderingTransaction;
  private ReaderStreamBinding leela0110PonderingBinding;
  private Object leela0110PonderingStateToken;
  private final Object leela0110PonderStateLock = new Object();
  private final ReentrantLock leela0110PonderPhysicalWriteLock = new ReentrantLock();
  private volatile Object analysisInfoMutationLock = new Object();
  private long analysisInfoEpoch;
  private AnalysisInfoTarget analysisInfoPayloadTarget;
  private final AtomicLong analysisOutputGeneration = new AtomicLong();
  private static final int LEELA0110_PONDERING_INTERVAL_MILLIS = 1000;
  public boolean javaSSHClosed = false;
  public boolean useJavaSSH = false;
  public String ip;
  public String port;
  public String userName;
  public String password;
  public boolean useKeyGen;
  public String keyGenPath;
  public SSHController javaSSH;
  public boolean useRemoteCompute = false;
  private boolean stopByLimit = false;
  public boolean stopByPlayouts = false;
  public boolean outOfPlayoutsLimit = false;
  private EngineFailedMessage engineFailedMessage;
  public List<String> commandLists = new ArrayList<String>();
  private boolean startGetCommandList = false;
  private boolean endGetCommandList = false;
  private boolean readBoardGmaUnsupportedPromptShown = false;
  private final ReadBoardGmaRuntimeParam readBoardGmaMaxTime =
      new ReadBoardGmaRuntimeParam("maxTime");
  private final ReadBoardGmaRuntimeParam readBoardGmaMaxVisits =
      new ReadBoardGmaRuntimeParam("maxVisits");
  private final ReadBoardGmaRuntimeParam readBoardGmaPondering =
      new ReadBoardGmaRuntimeParam("ponderingEnabled");
  private volatile Object readBoardGmaLock;
  private volatile EngineModeReservation readBoardGmaReservation;
  private volatile ReadBoardGmaRestoreBarrier readBoardGmaRestoreBarrier;
  private volatile ReadBoardGmaPreparation readBoardGmaPreparation;
  private volatile ReadBoardGmaResponseBinding readBoardGmaResponseBinding;
  private volatile boolean engineStateUnrestored;
  private volatile int currentTotalPlayouts;
  public boolean supportMovesOwnership = false;

  // private int refreshNumber=0;
  // private boolean isEstimating=true;
  /**
   * Initializes the leelaz process and starts reading output
   *
   * @throws IOException
   */
  public Leelaz(String engineCommand) throws IOException, JSONException {
    // board = new Board();
    bestMoves = List.of();
    currentTotalPlayouts = 0;
    // bestMovesTemp = new ArrayList<>();
    //	listeners = new CopyOnWriteArrayList<>();

    isPondering = false;
    startPonderTime = System.currentTimeMillis();
    cmdNumber = 1;
    currentCmdNum = 0;
    cmdQueue = new ArrayDeque<QueuedCommand>();
    pendingResponseHandlers = new ArrayDeque<PendingResponseHandler>();
    setEngineCommand(engineCommand);
  }

  public String getEngineCommand() {
    if (oriEngineCommand.startsWith("encryption||"))
      return Lizzie.resourceBundle.getString("Leelaz.encryption");
    return engineCommand;
  }

  public void setEngineCommand(String commandString) {
    oriEngineCommand = commandString;
    if (commandString.startsWith("encryption||")) {
      commandString = commandString.substring(12);
      commandString = Utils.doDecrypt2(commandString);
    }
    this.engineCommand = commandString == null ? oriEngineCommand : commandString;
    if (this.engineCommand.toLowerCase().contains("katajigo")) {
      this.noAnalyze = true;
    }
    if (this.engineCommand.toLowerCase().contains("gogui")) {
      this.requireResponseBeforeSend = true;
    }
    this.useRemoteCompute = RemoteComputeConfig.isRemoteComputeEngineCommand(this.engineCommand);
    if (this.useRemoteCompute) {
      this.isSSH = false;
      this.isKatago = true;
    }
    if (this.engineCommand.toLowerCase().contains("ssh")
        || engineCommand.toLowerCase().contains("plink")) {
      this.isSSH = true;
    }
    //		if (this.engineCommand.startsWith("screen")) {
    //			this.engineCommand=this.engineCommand.substring(6);
    //			this.isScreen = true;
    //			}
  }

  public String getEngineName(int index) {
    if (index < 0) return Lizzie.resourceBundle.getString("Menu.noEngine");
    ArrayList<EngineData> engineData = Utils.getEngineData();
    EngineData data = engineData.get(index);
    String rawName = data.name;
    currentEnginename = deriveDisplayName(rawName, data.commands);
    oriEnginename = currentEnginename;
    String regEx = "[`~!@#$%^&*()+=|{}':;',\\[\\].<>/?~！@#￥%……&*（）——+|{}【】‘；：”“’。，、？]";
    String aa = "";
    Pattern p = Pattern.compile(regEx);
    Matcher m = p.matcher(currentEnginename);
    currentEnginename = m.replaceAll(aa).trim();
    bestMovesEnginename =
        RemoteComputeConfig.compactDisplayNameForCommand(data.commands, currentEnginename)
            .replaceAll(" ", "");
    return currentEnginename;
  }

  /**
   * If the stored engine name is a generic placeholder ("KataGo Bundled", "KataGo Auto Setup",
   * "KataGo TensorRT"), derive a friendlier name from the weight file referenced in the engine
   * command. Otherwise keep the user-assigned name.
   */
  public static String friendlyEngineName(String rawName, String command) {
    return deriveDisplayName(rawName, command);
  }

  private static String deriveDisplayName(String rawName, String command) {
    if (RemoteComputeConfig.isZhiziEngineCommand(command)) {
      return RemoteComputeConfig.displayNameForZhiziArgs(RemoteComputeConfig.load().zhiziArgs);
    }
    if (RemoteComputeConfig.isCustomWebSocketEngineCommand(command)) {
      return RemoteComputeConfig.displayNameForCustomWebSocketUrl(
          RemoteComputeConfig.load().customRemoteCode);
    }
    String name = rawName == null ? "" : rawName.trim();
    boolean placeholder =
        name.isEmpty()
            || name.equalsIgnoreCase("KataGo Bundled")
            || name.equalsIgnoreCase("KataGo Auto Setup")
            || name.equalsIgnoreCase("KataGo TensorRT");
    if (!placeholder) return name;
    String shortWeight = extractWeightShortName(command);
    if (shortWeight != null && !shortWeight.isEmpty()) return shortWeight;
    return name.isEmpty() ? "KataGo" : name;
  }

  static String extractWeightShortName(String command) {
    if (command == null || command.isEmpty()) return null;
    String[] flags = {"-model", "--model", "-weights", "--weights"};
    for (String flag : flags) {
      int idx = command.indexOf(flag);
      while (idx >= 0) {
        int after = idx + flag.length();
        if (after >= command.length()
            || (command.charAt(after) != ' '
                && command.charAt(after) != '='
                && command.charAt(after) != '\t')) {
          idx = command.indexOf(flag, after);
          continue;
        }
        int start = after + 1;
        while (start < command.length()
            && (command.charAt(start) == ' '
                || command.charAt(start) == '\t'
                || command.charAt(start) == '=')) {
          start++;
        }
        if (start >= command.length()) return null;
        boolean quoted = false;
        char q = 0;
        if (command.charAt(start) == '"' || command.charAt(start) == '\'') {
          quoted = true;
          q = command.charAt(start);
          start++;
        }
        int end = start;
        while (end < command.length()) {
          char c = command.charAt(end);
          if (quoted && c == q) break;
          if (!quoted && (c == ' ' || c == '\t')) break;
          end++;
        }
        String path = command.substring(start, end).trim();
        if (path.isEmpty()) return null;
        return shortenWeightPath(path);
      }
    }
    return null;
  }

  private static String shortenWeightPath(String path) {
    try {
      String displayName = KataGoAutoSetupHelper.resolveWeightDisplayName(Path.of(path));
      if (displayName != null && !displayName.trim().isEmpty()) {
        return displayName;
      }
    } catch (Exception ignored) {
    }
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    String base = slash >= 0 ? path.substring(slash + 1) : path;
    String lower = base.toLowerCase();
    String[] suffixes = {".bin.gz", ".txt.gz", ".bin", ".txt", ".gz"};
    for (String suf : suffixes) {
      if (lower.endsWith(suf)) {
        base = base.substring(0, base.length() - suf.length());
        break;
      }
    }
    return base;
  }

  public void startEngine(int index) throws IOException {
    EngineManager.EngineGameTransaction engineGameStartupTransaction =
        engineGameStartupCommandContext.get();
    boolean deferredEngineGameRecovery = isDeferredEngineGameRecoveryStartup();
    requireCurrentEngineGameStartupTransaction(engineGameStartupTransaction);
    launchCommandSetsKataGoThreads = false;
    if (engineCommand.trim().isEmpty()) {
      if (!deferredEngineGameRecovery) {
        Utils.showMsg(Lizzie.resourceBundle.getString("EngineFaied.empty"));
      }
      return;
    }
    canAddPlayer = false;
    currentEngineN = index;
    startupPrimaryEngineGeneration =
        deferredEngineGameRecovery ? -1L : Lizzie.capturePrimaryEngineGeneration(this);
    canRestoreDymPda = false;
    supportMovesOwnership = false;
    CommandLaunchHelper.LaunchSpec launchSpec =
        CommandLaunchHelper.prepare(Utils.splitCommand(engineCommand));
    commands = launchSpec.getCommandParts();
    rememberKataGoThreadLaunchOverride(commands);
    pda = 0;
    // Get weight name
    //	Pattern wPattern = Pattern.compile("(?s).*?(--weights |-w |-model )([^'\" ]+)(?s).*");
    // Matcher wMatcher = wPattern.matcher(engineCommand);
    currentEnginename = getEngineName(index);
    isDownWithError = false;
    openClFp32CompatibilityActive = false;
    openClCompatibilityRecoveryAttempted.set(false);
    this.useRemoteCompute = RemoteComputeConfig.isRemoteComputeEngineCommand(this.engineCommand);
    if (this.useRemoteCompute) {
      process = null;
      this.javaSSHClosed = false;
      this.isSSH = false;
      try {
        this.remoteTransport = RemoteComputeConfig.createTransportForCommand(this.engineCommand);
        recordUpdateEngineStartRemoteTransport(this.remoteTransport);
        this.remoteTransport.start();
        requireCurrentEngineGameStartupTransaction(engineGameStartupTransaction);
        initializeStreams(
            this.remoteTransport.stdout(),
            this.remoteTransport.stdin(),
            this.remoteTransport.stderr());
        bindCurrentEngineGameStartupIncarnation(engineGameStartupTransaction);
      } catch (IOException e) {
        isDownWithError = true;
        rememberRecentLine(recentStderrLines, e.getLocalizedMessage());
        noteEngineFailed(e.getLocalizedMessage());
        try {
          tryToDignostic(
              Lizzie.resourceBundle.getString("Leelaz.engineFailed")
                  + ": "
                  + (e.getLocalizedMessage() == null ? "远程算力连接失败" : e.getLocalizedMessage()),
              true);
        } catch (JSONException diagnosticError) {
          diagnosticError.printStackTrace();
        }
        throw e;
      }
    } else if (this.useJavaSSH) {
      process = null;
      this.javaSSH = new SSHController(this, this.ip, this.port);
      recordUpdateEngineStartJavaSsh(this.javaSSH);
      boolean loginStatus = false;
      if (this.useKeyGen) {
        loginStatus =
            this.javaSSH
                .loginByFileKey(this.engineCommand, this.userName, new File(this.keyGenPath))
                .booleanValue();
      } else {
        loginStatus =
            this.javaSSH.login(this.engineCommand, this.userName, this.password).booleanValue();
      }
      if (loginStatus) {
        this.javaSSHClosed = false;
        requireCurrentEngineGameStartupTransaction(engineGameStartupTransaction);
        initializeStreams(
            this.javaSSH.getStdout(), this.javaSSH.getStdin(), this.javaSSH.getSterr());
        bindCurrentEngineGameStartupIncarnation(engineGameStartupTransaction);
      } else {
        isDownWithError = true;
        noteEngineFailed("ssh login failed");
        return;
      }
    } else {
      if (KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed()) {
        isLoaded = false;
        started = false;
        return;
      }
      Path engineExecutable = KataGoRuntimeHelper.resolveCommandExecutable(commands);
      boolean bundledCommand = Config.isBundledKataGoCommand(engineCommand);
      boolean nvidiaBundled = KataGoRuntimeHelper.isNvidiaBundledPath(engineExecutable);
      long startupToken = 0L;
      if (bundledCommand) {
        startupToken = beginBundledStartup(engineExecutable);
      }
      if (Config.isBundledKataGoCommand(engineCommand)) {
        try {
          if (nvidiaBundled) {
            updateBundledStartupStage(
                engineExecutable,
                nvidiaBundled ? 2 : 1,
                "BundledEngineStartup.status.preparingRuntime",
                "Preparing NVIDIA acceleration...",
                "BundledEngineStartup.hint.nvidia",
                "First launch on the NVIDIA package may take a little longer.");
          }
          KataGoRuntimeHelper.ensureBundledRuntimeReady(
              engineExecutable,
              commands,
              deferredEngineGameRecovery ? null : Lizzie.frame);
        } catch (IOException e) {
          closeBundledStartupDialog();
          String err = e.getLocalizedMessage();
          try {
            tryToDignostic(
                Lizzie.resourceBundle.getString("Leelaz.engineFailed")
                    + ": "
                    + ((err == null)
                        ? Lizzie.resourceBundle.getString("Leelaz.engineStartNoExceptionMessage")
                        : err),
                true);
            if (shouldOpenInteractiveDiagnostic()) {
              LizzieFrame.openMoreEngineDialog();
            }
          } catch (JSONException e1) {
            e1.printStackTrace();
            isDownWithError = true;
          }
          isDownWithError = true;
          noteEngineFailed(e.getLocalizedMessage());
          return;
        }
      }
      if (bundledCommand) {
        updateBundledStartupStage(
            engineExecutable,
            nvidiaBundled ? 3 : 2,
            "BundledEngineStartup.status.startingProcess",
            "Starting KataGo...",
            nvidiaBundled ? "BundledEngineStartup.hint.nvidia" : "BundledEngineStartup.hint",
            nvidiaBundled
                ? "First launch on the NVIDIA package may take a little longer."
                : "First launch may take a little longer.");
      }
      List<String> launchCommands =
          KataGoRuntimeHelper.prepareBundledLaunchCommand(commands, engineExecutable);
      rememberKataGoThreadLaunchOverride(launchCommands);
      openClFp32CompatibilityActive =
          KataGoRuntimeHelper.isOpenClFp32CompatibilityActive(launchCommands, engineExecutable);
      if (openClFp32CompatibilityActive && bundledCommand && !preload) {
        publishBundledStartupStatus(
            "BundledEngineStartup.status.openclCompatibility",
            "Using stable NVIDIA OpenCL compatibility mode...");
      }
      ProcessBuilder processBuilder = new ProcessBuilder(launchCommands);
      CommandLaunchHelper.configureProcessBuilder(processBuilder, launchSpec);
      KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, engineExecutable);
      processBuilder.redirectErrorStream(false);
      try {
        process = processBuilder.start();
        recordUpdateEngineStartProcess(process);
        AnalysisResourceCoordinator.processStarted(
            this, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, engineCommand, process);
      } catch (IOException e) {
        closeBundledStartupDialog();
        String err = e.getLocalizedMessage();
        try {
          tryToDignostic(
              Lizzie.resourceBundle.getString("Leelaz.engineFailed")
                  + ": "
                  + ((err == null)
                      ? Lizzie.resourceBundle.getString("Leelaz.engineStartNoExceptionMessage")
                      : err),
              true);
          if (shouldOpenInteractiveDiagnostic()) {
            LizzieFrame.openMoreEngineDialog();
          }
        } catch (JSONException e1) {
          // TODO Auto-generated catch block
          e1.printStackTrace();
          isDownWithError = true;
        }
        noteEngineFailed(err);
        return;
      }
      requireCurrentEngineGameStartupTransaction(engineGameStartupTransaction);
      initializeStreams();
      bindCurrentEngineGameStartupIncarnation(engineGameStartupTransaction);
      if (bundledCommand) {
        updateBundledStartupStage(
            engineExecutable,
            nvidiaBundled ? 4 : 3,
            "BundledEngineStartup.status.waitingResponse",
            "Waiting for engine response...",
            "BundledEngineStartup.hint.waiting",
            "The first response can take a little longer while the engine finishes loading.");
        Object watchdogIncarnation = captureEngineIncarnationFence();
        startBundledStartupWatchdog(
            startupToken,
            engineExecutable,
            watchdogIncarnation,
            suppressesGlobalEnginePresentation(watchdogIncarnation));
      }
    }
    // Send a version request to check that we have a supported version
    // Response handled in parseLine
    prepareEngineBootstrapState(engineGameStartupTransaction);
    // sendCommand("turnon");
    RestartBootstrapReceipt startupReceipt = currentRestartBootstrapReceipt();
    requireCurrentEngineGameStartupTransaction(engineGameStartupTransaction);
    if (!isSSH) {
      dispatchStartupBootstrapCommands(
          engineGameStartupTransaction, startupReceipt, false);
    }
    if (engineGameStartupTransaction == null
        && !deferredEngineGameRecovery
        && this == Lizzie.leelaz
        && shouldApplyInitialEngineKomiToCurrentGame()) {
      Lizzie.board.getHistory().getGameInfo().setKomi(komi);
    }
    if (isSSH) {
      dispatchStartupBootstrapCommands(
          engineGameStartupTransaction, startupReceipt, true);
    }
    // if(width!=19||height!=19)

    // start a thread to continuously read Leelaz output
    // new Thread(this::read).start();
    // can stop engine for switching weights
    ReaderStreamBinding startedReaderStreamBinding = currentReaderStreamBinding();
    ScheduledExecutorService stdoutExecutor = Executors.newSingleThreadScheduledExecutor();
    ScheduledExecutorService stderrExecutor = Executors.newSingleThreadScheduledExecutor();
    if (!startReaderExecutors(startedReaderStreamBinding, stdoutExecutor, stderrExecutor)) {
      return;
    }

    publishEngineStartupPresentation(
        engineGameStartupTransaction, startedReaderStreamBinding);
  }

  private void dispatchStartupBootstrapCommands(
      EngineManager.EngineGameTransaction transaction,
      RestartBootstrapReceipt startupReceipt,
      boolean sshBootstrap) {
    Runnable bootstrap =
        () -> {
          try {
            runStartupBootstrapCommands(transaction, startupReceipt, sshBootstrap);
          } catch (RuntimeException | Error failure) {
            if (transaction != null
                && !EngineManager.isCurrentEngineGameTransaction(transaction)) {
              return;
            }
            throw failure;
          } finally {
            afterEngineGameBootstrapWorkerForTest(transaction);
          }
        };
    if (transaction == null) {
      Thread thread = new Thread(bootstrap, "lizzie-engine-startup-bootstrap");
      thread.start();
      return;
    }
    if (!EngineManager.dispatchEngineGameStartupWorker(
        transaction, "lizzie-engine-game-bootstrap-" + currentEngineN, bootstrap)) {
      throw new IllegalStateException(
          "engine-game startup transaction retired before bootstrap dispatch");
    }
  }

  private void runStartupBootstrapCommands(
      EngineManager.EngineGameTransaction transaction,
      RestartBootstrapReceipt startupReceipt,
      boolean sshBootstrap) {
    runWithEngineGameStartupCommandContext(
        transaction,
        () ->
            runWithRestartBootstrapReceipt(
                startupReceipt,
                () -> {
                  beforeEngineGameBootstrapCommandsForTest(transaction);
                  if (sshBootstrap && !sleepForEngineBootstrap(transaction, 500L)) {
                    return;
                  }
                  int times = 0;
                  while (!sshBootstrap && outputStream == null && times < 10) {
                    if (!sleepForEngineBootstrap(transaction, 100L)) {
                      return;
                    }
                    times++;
                  }
                  int nameRequests = sshBootstrap ? 3 : 1;
                  for (int request = 0; request < nameRequests; request++) {
                    sendEngineBootstrapCommand(transaction, "name");
                  }
                  sendEngineBootstrapCommand(transaction, "version");
                  sendEngineBootstrapCommand(transaction, "list_commands");
                  requireCurrentEngineGameStartupTransaction(transaction);
                  enqueueSavedGtpConfiguration();
                  if (!sshBootstrap
                      && !(Lizzie.frame.isPlayingAgainstLeelaz
                          || Lizzie.frame.isAnaPlayingAgainstLeelaz)) {
                    sendEngineBootstrapCommand(transaction, "komi " + komi);
                  }
                  if (transaction == null) {
                    boardSizeForEngine(width, height);
                  } else {
                    boardSizeForEngineGame(transaction, width, height);
                  }
                  if (sshBootstrap
                      && !(Lizzie.frame.isPlayingAgainstLeelaz
                          || Lizzie.frame.isAnaPlayingAgainstLeelaz)) {
                    sendEngineBootstrapCommand(transaction, "komi " + komi);
                  }
                  if (initialCommand != null && !initialCommand.equals("")) {
                    String[] initialCommands = initialCommand.trim().split(";");
                    for (String command : initialCommands) {
                      sendEngineBootstrapCommand(transaction, command);
                    }
                  }
                  if (sshBootstrap && transaction == null) {
                    requireCurrentEngineGameStartupTransaction(transaction);
                    setResponseUpToDate();
                  }
                  afterEngineGameBootstrapCommandsForTest(transaction);
                }));
  }

  private boolean sleepForEngineBootstrap(
      EngineManager.EngineGameTransaction transaction, long millis) {
    requireCurrentEngineGameStartupTransaction(transaction);
    try {
      Thread.sleep(millis);
      requireCurrentEngineGameStartupTransaction(transaction);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void sendEngineBootstrapCommand(
      EngineManager.EngineGameTransaction transaction, String command) {
    requireCurrentEngineGameStartupTransaction(transaction);
    sendCommand(command);
  }

  private static void requireCurrentEngineGameStartupTransaction(
      EngineManager.EngineGameTransaction transaction) {
    if (transaction != null && !EngineManager.isCurrentEngineGameTransaction(transaction)) {
      throw new IllegalStateException("engine-game startup transaction is no longer current");
    }
  }

  private void bindCurrentEngineGameStartupIncarnation(
      EngineManager.EngineGameTransaction transaction) {
    if (transaction == null) {
      return;
    }
    Object incarnation = currentEngineIncarnation();
    if (!EngineManager.bindEngineGameStartupIncarnation(transaction, this, incarnation)) {
      try {
        normalQuitIfCurrentIncarnation(incarnation);
      } catch (RuntimeException | Error cleanupFailure) {
        cleanupFailure.printStackTrace();
      }
      throw new IllegalStateException(
          "engine-game startup transaction retired during stream publication");
    }
  }

  private void prepareEngineBootstrapState(
      EngineManager.EngineGameTransaction transaction) {
    Runnable mutation =
        () -> {
          isCheckingVersion = true;
          isCheckingName = true;
          endGetCommandList = false;
          startGetCommandList = false;
          commandLists.clear();
          readBoardGmaUnsupportedPromptShown = false;
          if (!engineStateUnrestored) {
            clearReadBoardGmaSearchLimitSnapshots();
          }
        };
    if (transaction == null) {
      mutation.run();
      return;
    }
    if (!EngineManager.runIfCurrentEngineGameTransaction(transaction, mutation)) {
      throw new IllegalStateException(
          "engine-game startup transaction retired before bootstrap state publication");
    }
  }

  void beforeEngineGameBootstrapCommandsForTest(
      EngineManager.EngineGameTransaction transaction) {}

  void afterEngineGameBootstrapCommandsForTest(
      EngineManager.EngineGameTransaction transaction) {}

  void afterEngineGameBootstrapWorkerForTest(
      EngineManager.EngineGameTransaction transaction) {}

  void dispatchEngineGameBootstrapCommandsForTest(
      EngineManager.EngineGameTransaction transaction) {
    dispatchStartupBootstrapCommands(transaction, null, false);
  }

  private void publishEngineStartupPresentation(
      EngineManager.EngineGameTransaction transaction, Object startedReaderStreamBinding) {
    beforeEngineGameBootstrapPresentationForTest(transaction);
    if (transaction != null
        || suppressesGlobalEnginePresentation(startedReaderStreamBinding)) {
      return;
    }
    EngineManager.publishStartedEngineIconIfCurrent(this, startedReaderStreamBinding);
    if (Lizzie.frame.isShowingHeatmap) Lizzie.frame.isShowingHeatmap = false;
    if (Lizzie.frame.isShowingPolicy) Lizzie.frame.isShowingPolicy = false;
  }

  static boolean isDeferredEngineGameRecoveryStartup() {
    return Boolean.TRUE.equals(deferredEngineGameRecoveryStartupContext.get());
  }

  void startDeferredEngineGameRecovery(
      UpdateEngineStartAttempt attempt, Object recoveryToken, int index) throws IOException {
    if (attempt == null || attempt.owner() != this || recoveryToken == null) {
      throw new IllegalArgumentException("attempt/recoveryToken");
    }
    Boolean previousDeferredRecovery = deferredEngineGameRecoveryStartupContext.get();
    Object previousAnalysisRecovery = analysisOutputRecoveryTokenContext.get();
    if (Boolean.TRUE.equals(previousDeferredRecovery) || previousAnalysisRecovery != null) {
      throw new IllegalStateException("Nested deferred analysis-output recovery start");
    }
    deferredEngineGameRecoveryStartupContext.set(Boolean.TRUE);
    analysisOutputRecoveryTokenContext.set(recoveryToken);
    try {
      attempt.startEngine(index);
    } finally {
      analysisOutputRecoveryTokenContext.remove();
      if (previousDeferredRecovery == null) {
        deferredEngineGameRecoveryStartupContext.remove();
      } else {
        deferredEngineGameRecoveryStartupContext.set(previousDeferredRecovery);
      }
    }
  }

  void beforeEngineGameBootstrapPresentationForTest(
      EngineManager.EngineGameTransaction transaction) {}

  void publishEngineGameBootstrapPresentationForTest(
      EngineManager.EngineGameTransaction transaction) {
    publishEngineStartupPresentation(transaction, currentEngineIncarnation());
  }

  private boolean startReaderExecutors(
      ReaderStreamBinding startedReaderStreamBinding,
      ScheduledExecutorService stdoutExecutor,
      ScheduledExecutorService stderrExecutor) {
    CountDownLatch readerStartGate = new CountDownLatch(1);
    boolean readersSubmitted = false;
    synchronized (engineArbitrationLock()) {
      synchronized (startedReaderStreamBinding.readerExecutorLock) {
        if (readerStreamBinding != startedReaderStreamBinding
            || startedReaderStreamBinding.terminated
            || startedReaderStreamBinding.readerShutdownRequested) {
          startedReaderStreamBinding.readerShutdownRequested = true;
          stdoutExecutor.shutdown();
          stderrExecutor.shutdown();
          return false;
        }
        started = true;
        isNormalEnd = false;
        startedReaderStreamBinding.stdoutExecutor = stdoutExecutor;
        startedReaderStreamBinding.stderrExecutor = stderrExecutor;
        executor = stdoutExecutor;
        executorErr = stderrExecutor;
        try {
          stdoutExecutor.execute(
              () -> {
                if (awaitReaderStart(readerStartGate)) read(startedReaderStreamBinding);
              });
          stderrExecutor.execute(
              () -> {
                if (awaitReaderStart(readerStartGate)) readError(startedReaderStreamBinding);
              });
          readersSubmitted = true;
        } finally {
          if (!readersSubmitted) {
            startedReaderStreamBinding.readerShutdownRequested = true;
            stdoutExecutor.shutdownNow();
            stderrExecutor.shutdownNow();
            if (readerStreamBinding == startedReaderStreamBinding) {
              started = false;
              isNormalEnd = true;
            }
          }
          readerStartGate.countDown();
        }
      }
    }
    return true;
  }

  //	public void restartEngine(int index) throws IOException {
  //		if (engineCommand.trim().isEmpty()) {
  //			return;
  //		}
  //		//switching = true;
  //		this.engineCommand = engineCommand;
  //		// stop the ponder
  //		if (Lizzie.leelaz.isPondering()) {
  //			Lizzie.leelaz.togglePonder();
  //		}
  //		normalQuit();
  //		startEngine(index);
  //		// currentEngineN = index;
  //		togglePonder();
  //	}

  public void restartClosedEngine(int index) throws IOException {
    restartClosedEngine(index, null);
  }

  /** Convenience entry for manual/GMA callers that do not preflight an automatic attempt. */
  public void restartClosedEngine(int index, Runnable afterBoardRestore) throws IOException {
    if (engineCommand.trim().isEmpty()) {
      if (afterBoardRestore != null) {
        afterBoardRestore.run();
      }
      return;
    }
    AutomaticRestartAttempt attempt = beginAutomaticRestartAttempt(true);
    if (attempt == null) {
      throw new IllegalStateException("Automatic restart is not admitted");
    }
    attempt.restartClosedEngine(index, afterBoardRestore);
  }

  /**
   * Captures and owns one complete automatic restart attempt before any process side effect. The
   * returned opaque attempt atomically owns its frozen round, reservation, barriers and completion
   * claim; callers may either start it once or abandon it with {@link AutoCloseable#close()}.
   */
  public AutomaticRestartAttempt beginAutomaticEngineRestartAttempt() { return beginAutomaticRestartAttempt(false); }

  private AutomaticRestartAttempt beginAutomaticRestartAttempt(boolean allowUnrestoredState) {
    if (!allowUnrestoredState
        && (engineStateUnrestored
            || readBoardGmaReservation != null
            || readBoardGmaRestoreBarrier != null)) {
      return null;
    }
    AutomaticRestartRound frozenRound;
    try {
      frozenRound = captureAutomaticRestartRound(allowUnrestoredState);
    } catch (ExactSnapshotRestoreAdmissionException conflict) {
      return null;
    }
    ExclusiveGtpLifecycleReservation initialReservation =
        beginExclusiveGtpLifecycleReservation(frozenRound.owner());
    if (initialReservation == null) {
      return null;
    }
    LifecycleCompletionClaim completionClaim =
        tryBeginLifecycleCompletion(frozenRound.owner(), frozenRound.mirror());
    if (completionClaim == null) {
      initialReservation.close();
      return null;
    }
    AutomaticRestartOperation operation =
        new AutomaticRestartOperation(
            frozenRound, initialReservation, completionClaim, engineStateUnrestored);
    try {
      operation.beginBoardSynchronization();
      return new AutomaticRestartAttempt(operation);
    } catch (RuntimeException | Error failure) {
      try {
        operation.abandon();
      } catch (RuntimeException | Error cleanupFailure) {
        if (cleanupFailure != failure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      throw failure;
    }
  }

  private boolean waitForAutomaticRestartReadiness() {
    return waitForAutomaticRestartReadiness(this);
  }

  private static boolean waitForAutomaticRestartReadiness(Leelaz engine) {
    long now = System.nanoTime();
    long deadline =
        now
            + TimeUnit.MILLISECONDS.toNanos(
                Math.max(1L, engine.engineStartupSynchronizationTimeoutMillis()));
    boolean tuningTimeoutApplied = false;
    while (true) {
      if (!engine.isStarted() || engine.isDownWithError || engine.isNormalEnd) {
        return false;
      }
      if (automaticRestartReady(engine.isLoaded(), engine.isCheckingName, engine.endGetCommandList)) {
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
      long remainingMillis =
          Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - now));
      try {
        Thread.sleep(Math.min(100L, remainingMillis));
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  static boolean automaticRestartReady(
      boolean loaded, boolean checkingName, boolean commandListReady) {
    return loaded && !checkingName && commandListReady;
  }

  void restoreClosedEngineBoardState(boolean resumePonder) {
    isPondering = false;
    restoreRootAfterLifecyclePreparation();
    if (KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed()) {
      return;
    }
    if (engineStateUnrestored) {
      confirmBoardSynchronization(
          () -> {
            completeReadBoardGmaRecoveryAfterBoardSync();
            resumeClosedEngineAfterBoardSynchronization(resumePonder);
          },
          this::markBoardSynchronizationFailed);
      return;
    }
    resumeClosedEngineAfterBoardSynchronization(resumePonder);
  }

  void resumeClosedEngineAfterBoardSynchronization(boolean resumePonder) {
    Lizzie.initializeAfterVersionCheck(
        false, this, resumePonder, startupPrimaryEngineGeneration);
  }

  void bindCurrentPrimaryEngineGeneration() {
    long primaryGeneration = Lizzie.capturePrimaryEngineGeneration(this);
    synchronized (engineArbitrationLock()) {
      startupPrimaryEngineGeneration = primaryGeneration;
      if (readerStreamBinding != null) {
        readerStreamBinding.startupPrimaryEngineGeneration = primaryGeneration;
        readerStreamBinding.deferredEngineGameRecoveryPresentationSuppressed = false;
      }
    }
  }

  void initializeAfterExplicitRestartBoardSynchronization(boolean resumePonder) {
    Lizzie.initializeAfterVersionCheck(
        false, this, resumePonder, startupPrimaryEngineGeneration);
  }
  void completeSecondaryExplicitRestartBoardSynchronization() {
    notPondering();
    canRestoreDymPda = true;
  }

  void completeReadBoardGmaRecoveryAfterBoardSync() {
    synchronized (readBoardGmaLock()) {
      if (!engineStateUnrestored
          || this != Lizzie.leelaz
          || !started
          || !isLoaded
          || isCheckingName
          || !endGetCommandList) {
        return;
      }
      clearReadBoardGmaSearchLimitSnapshots();
      isThinking = false;
      isInputCommand = false;
      engineStateUnrestored = false;
    }
  }

  boolean hasUnrestoredReadBoardGmaState() {
    return engineStateUnrestored;
  }

  public boolean isEligibleLocalKataGoForReadBoardTracking() {
    return this == Lizzie.leelaz
        && started
        && isLoaded
        && trackingStaticAvailability() == ExclusiveGtpLeaseAvailability.AVAILABLE
        && !EngineManager.isEngineGame();
  }

  private AutomaticRestartRound captureAutomaticRestartRound(boolean allowUnrestoredState) {
    Board restoreBoard = Lizzie.board;
    AutomaticRestartOwner owner = new AutomaticRestartOwner(allowUnrestoredState);
    if (restoreBoard == null) {
      return AutomaticRestartRound.capture(
          this, null, owner, null, null, null, isPondering);
    }
    synchronized (restoreBoard) {
      BoardHistoryList history = restoreBoard.getHistory();
      BoardHistoryNode target = history == null ? null : history.getCurrentHistoryNode();
      Double komi =
          history == null || history.getGameInfo() == null
              ? null
              : history.getGameInfo().getKomi();
      return AutomaticRestartRound.capture(
          this,
          restoreBoard,
          owner,
          target,
          komi,
          Movelist.copyList(restoreBoard.getMoveList()),
          isPondering);
    }
  }

  /**
   * Atomically acquires completion ownership on this authority and its frozen mirror. The claim
   * remains installed through final fence settlement and the owner callback.
   */
  LifecycleCompletionClaim tryBeginLifecycleCompletion(Object owner, Leelaz frozenMirror) {
    return LifecycleCompletionClaim.tryAcquire(this, owner, frozenMirror);
  }

  private boolean hasLifecycleCompletionLocked() {
    return lifecycleCompletionClaim != null;
  }
  private boolean shouldRejectCommandDuringLifecycleCompletion() {
    return shouldRejectCommandDuringLifecycleCompletion(null);
  }

  private boolean shouldRejectCommandDuringLifecycleCompletion(String command) {
    String commandName = command == null ? "" : command.trim().split("\\s+", 2)[0];
    if ("name".equals(commandName)
        || "version".equals(commandName)
        || "protocol_version".equals(commandName)
        || "list_commands".equals(commandName)
        || "komi".equals(commandName)
        || "boardsize".equals(commandName)
        || "rectangular_boardsize".equals(commandName)) {
      return false;
    }
    synchronized (engineArbitrationLock()) {
      Object startupContext = startupPostActionCommandContext.get();
      Object startupBinding =
          startupContext instanceof StartupPostActionLease
              ? ((StartupPostActionLease) startupContext).binding
              : startupContext;
      if (startupBinding != null
          && readerStreamBinding == startupBinding
          && !readerStreamBinding.terminated
          && started) {
        return false;
      }
      LifecycleCompletionClaim claim = lifecycleCompletionClaim;
      return claim != null
          && claim != lifecycleCompletionCommandContext.get()
          && !isExactSnapshotRestoreAdmissionContextActive();
    }
  }

  private boolean hasLifecycleCompletionOwnedByOtherLocked(Object owner) {
    LifecycleCompletionClaim claim = lifecycleCompletionClaim;
    return claim != null && claim.owner() != owner;
  }

  private void restoreRootAfterLifecyclePreparation() {
    if (Lizzie.board != null) {
      Lizzie.board.resendMoveToEngine(this, false);
    }
  }

  void markBoardSynchronizationFailed(String detail) {
    RestartBootstrapReceipt receipt = restartBootstrapReceiptContext.get();
    if (receipt != null) {
      failRestartBootstrapReceipt(receipt, detail);
      return;
    }
    synchronized (readBoardGmaLock()) {
      engineStateUnrestored = true;
    }
    rememberRecentLine(
        recentStderrLines, "ReadBoard GMA recovery confirmation failed: " + detail);
    resetGtpCommandStateAfterRestoreFailure(detail);
  }

  void markLifecycleBoardSynchronizationFailed(String detail, boolean preserveUnrestoredState) {
    if (preserveUnrestoredState) {
      markBoardSynchronizationFailed(detail);
      return;
    }
    RestartBootstrapReceipt receipt = restartBootstrapReceiptContext.get();
    if (receipt != null) {
      failRestartBootstrapReceipt(receipt, detail, false);
      return;
    }
    rememberRecentLine(recentStderrLines, "Restart board synchronization failed: " + detail);
  }

  public void normalQuit() {
    ReaderStreamBinding binding = currentReaderStreamBinding();
    ReaderExecutorSnapshot executors = requestReaderShutdown(binding, true);
    EngineStopObservation stoppedObservation =
        markReaderBindingStoppedIfCurrent(binding, executors);
    finishClaimedNormalQuit(binding, executors, stoppedObservation);
  }

  /**
   * Retires exactly the reader/process incarnation represented by {@code expectedIncarnation}.
   * The shutdown claim is made while holding the same lock that publishes a replacement binding,
   * so a stale lifecycle callback can never resolve a newer binding between an identity check and
   * transport shutdown.
   */
  boolean normalQuitIfCurrentIncarnation(Object expectedIncarnation) {
    ExactNormalQuitClaim claim = claimNormalQuitIfCurrentIncarnation(expectedIncarnation);
    if (claim == null) {
      return false;
    }
    claim.finish();
    return true;
  }

  /** Claims an exact shutdown without performing any blocking transport or observation cleanup. */
  ExactNormalQuitClaim claimNormalQuitIfCurrentIncarnation(Object expectedIncarnation) {
    if (!(expectedIncarnation instanceof ReaderStreamBinding)) {
      return null;
    }
    ReaderStreamBinding binding = (ReaderStreamBinding) expectedIncarnation;
    ReaderExecutorSnapshot executors;
    EngineStopObservation stoppedObservation;
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding != binding) {
        return null;
      }
      // Do this overridable/failure-prone transition before consuming transport-close ownership.
      // If it fails, a later exact/ordinary shutdown can still claim and close the binding.
      notPondering();
      executors = requestReaderShutdown(binding, true);
      stoppedObservation = markReaderBindingStoppedLocked(binding, executors);
    }
    return new ExactNormalQuitClaim(this, binding, executors, stoppedObservation);
  }

  /**
   * Claims an exact failed-runtime shutdown even when a best-effort pondering transition fails.
   *
   * <p>This is intentionally separate from the ordinary exact normal-quit claim: an unavailable,
   * quarantined incarnation must not be abandoned merely because an overridable pre-shutdown hook
   * throws. The hook failure is retained on the stop observation and rethrown only after the exact
   * transport and reader executors have been cleaned up.
   */
  ExactNormalQuitClaim claimFailedRuntimeQuitIfCurrentIncarnation(Object expectedIncarnation) {
    if (!(expectedIncarnation instanceof ReaderStreamBinding)) {
      return null;
    }
    ReaderStreamBinding binding = (ReaderStreamBinding) expectedIncarnation;
    ReaderExecutorSnapshot executors;
    EngineStopObservation stoppedObservation;
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding != binding) {
        return null;
      }
      Throwable preparationFailure =
          runEngineCleanupStep(null, this::notPondering);
      executors = requestReaderShutdown(binding, true);
      stoppedObservation = markReaderBindingStoppedLocked(binding, executors);
      if (preparationFailure != null) {
        stoppedObservation =
            new EngineStopObservation(
                stoppedObservation.engineId,
                stoppedObservation.foregroundSample,
                appendEngineCleanupFailure(
                    preparationFailure, stoppedObservation.preparationFailure));
      }
    }
    return new ExactNormalQuitClaim(this, binding, executors, stoppedObservation);
  }

  /**
   * Retires exactly one engine incarnation without writing any application-level shutdown command.
   * The bounded claim freezes the reader binding and stop state; physical cleanup is deferred to
   * {@link ExactForceQuitClaim#finish()} so callers can run it outside lifecycle/selection locks.
   */
  boolean forceQuitIfCurrentIncarnation(Object expectedIncarnation) {
    ExactForceQuitClaim claim = claimForceQuitIfCurrentIncarnation(expectedIncarnation);
    if (claim == null) {
      return false;
    }
    claim.finish();
    return true;
  }

  /** Claims one exact non-protocol force close without performing transport or executor I/O. */
  ExactForceQuitClaim claimForceQuitIfCurrentIncarnation(Object expectedIncarnation) {
    if (!(expectedIncarnation instanceof ReaderStreamBinding)) {
      return null;
    }
    ReaderStreamBinding binding = (ReaderStreamBinding) expectedIncarnation;
    ForceReaderExecutorSnapshot executors;
    EngineStopObservation stoppedObservation;
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding != binding) {
        return null;
      }
      executors = claimReaderForceClose(binding);
      if (executors == null) {
        return null;
      }
      // Deliberately avoid notPondering and all output-producing hooks. This state-only transition
      // is the exact linearization point; a replacement may be published as soon as the claim
      // returns without becoming a target of the old binding's deferred cleanup.
      stoppedObservation = markEngineStoppedLocked(null, executors.ownsPhysicalClose);
    }
    return new ExactForceQuitClaim(this, binding, executors, stoppedObservation);
  }

  void finishExactNormalQuitClaim(ExactNormalQuitClaim claim) {
    if (claim == null
        || claim.owner != this
        || !claim.finished.compareAndSet(false, true)) {
      return;
    }
    finishClaimedNormalQuit(claim.binding, claim.executors, claim.stoppedObservation);
  }

  static final class ExactNormalQuitClaim {
    private final Leelaz owner;
    private final ReaderStreamBinding binding;
    private final ReaderExecutorSnapshot executors;
    private final EngineStopObservation stoppedObservation;
    private final AtomicBoolean finished = new AtomicBoolean();

    private ExactNormalQuitClaim(
        Leelaz owner,
        ReaderStreamBinding binding,
        ReaderExecutorSnapshot executors,
        EngineStopObservation stoppedObservation) {
      this.owner = owner;
      this.binding = binding;
      this.executors = executors;
      this.stoppedObservation = stoppedObservation;
    }

    void finish() {
      owner.finishExactNormalQuitClaim(this);
    }
  }

  void finishExactForceQuitClaim(ExactForceQuitClaim claim) {
    if (claim == null
        || claim.owner != this
        || !claim.finished.compareAndSet(false, true)) {
      return;
    }
    finishClaimedForceQuit(
        claim.binding, claim.executors, claim.stoppedObservation);
  }

  static final class ExactForceQuitClaim {
    private final Leelaz owner;
    private final ReaderStreamBinding binding;
    private final ForceReaderExecutorSnapshot executors;
    private final EngineStopObservation stoppedObservation;
    private final AtomicBoolean finished = new AtomicBoolean();

    private ExactForceQuitClaim(
        Leelaz owner,
        ReaderStreamBinding binding,
        ForceReaderExecutorSnapshot executors,
        EngineStopObservation stoppedObservation) {
      this.owner = owner;
      this.binding = binding;
      this.executors = executors;
      this.stoppedObservation = stoppedObservation;
    }

    void finish() {
      owner.finishExactForceQuitClaim(this);
    }
  }

  /**
   * Owns the exact resources published by one update-engine start call, including resources that
   * were installed before that call threw. Publication sites associate resources with the token;
   * blocking process/SSH/remote startup therefore never runs under the engine arbitration lock.
   */
  final class UpdateEngineStartAttempt {
    private final ReaderStreamBinding initialBinding;
    private final AtomicBoolean invocationStarted = new AtomicBoolean();
    private final AtomicBoolean settled = new AtomicBoolean();
    private final AtomicBoolean failureReportClaimed = new AtomicBoolean();
    private ReaderStreamBinding publishedBinding;
    private Process publishedProcess;
    private EngineTransport publishedRemoteTransport;
    private SSHController publishedJavaSsh;
    private boolean successfulStart;
    private UpdateEngineStartCompletion completionClaim;
    private volatile long primaryEngineGeneration = -1L;

    private UpdateEngineStartAttempt() {
      initialBinding = readerStreamBinding;
    }

    void startEngine(int index) throws IOException {
      if (!invocationStarted.compareAndSet(false, true)) {
        throw new IllegalStateException("Update-engine start attempt already used");
      }
      synchronized (engineArbitrationLock()) {
        if (activeUpdateEngineStartAttempt != this || settled.get()) {
          throw new IllegalStateException("Update-engine start attempt is no longer active");
        }
      }
      if (updateEngineStartAttemptContext.get() != null) {
        throw new IllegalStateException("Nested update-engine start attempt");
      }
      boolean returned = false;
      updateEngineStartAttemptContext.set(this);
      try {
        Leelaz.this.startEngine(index);
        returned = true;
      } finally {
        updateEngineStartAttemptContext.remove();
        synchronized (engineArbitrationLock()) {
          successfulStart =
              returned
                  && publishedBinding != null
                  && readerStreamBinding == publishedBinding
                  && Leelaz.this.started
                  && !publishedBinding.terminated;
        }
      }
      if (!successfulStart) {
        throw new IOException("Engine start returned without a live reader incarnation");
      }
    }

    private void recordBindingLocked(ReaderStreamBinding binding) {
      if (activeUpdateEngineStartAttempt == this && !settled.get()) {
        publishedBinding = binding;
        if (binding.process != null) {
          publishedProcess = binding.process;
        }
        if (binding.remoteTransport != null) {
          publishedRemoteTransport = binding.remoteTransport;
        }
        if (binding.javaSSH != null) {
          publishedJavaSsh = binding.javaSSH;
        }
      }
    }

    private void recordProcess(Process startedProcess) {
      synchronized (engineArbitrationLock()) {
        if (activeUpdateEngineStartAttempt == this && !settled.get()) {
          publishedProcess = startedProcess;
        }
      }
    }

    private void recordRemoteTransport(EngineTransport startedTransport) {
      synchronized (engineArbitrationLock()) {
        if (activeUpdateEngineStartAttempt == this && !settled.get()) {
          publishedRemoteTransport = startedTransport;
        }
      }
    }

    private void recordJavaSsh(SSHController startedJavaSsh) {
      synchronized (engineArbitrationLock()) {
        if (activeUpdateEngineStartAttempt == this && !settled.get()) {
          publishedJavaSsh = startedJavaSsh;
        }
      }
    }

    Object publishedIncarnation() {
      synchronized (engineArbitrationLock()) {
        return publishedBinding;
      }
    }

    void bindPrimaryEngineGeneration(long expectedGeneration) {
      if (expectedGeneration < 0L) {
        throw new IllegalStateException("Update-engine target is no longer the primary engine");
      }
      synchronized (engineArbitrationLock()) {
        if (activeUpdateEngineStartAttempt != this || settled.get()) {
          throw new IllegalStateException("Update-engine start attempt is no longer active");
        }
        if (primaryEngineGeneration >= 0L && primaryEngineGeneration != expectedGeneration) {
          throw new IllegalStateException("Update-engine primary generation was already bound");
        }
        primaryEngineGeneration = expectedGeneration;
      }
    }

    long primaryEngineGeneration() {
      return primaryEngineGeneration;
    }

    void complete() {
      synchronized (engineArbitrationLock()) {
        if (completionClaim != null || !canCompleteLocked()) {
          throw new IllegalStateException(
              "Cannot complete a stale or unsuccessful engine start");
        }
        settleCompleteLocked();
      }
    }

    void failClose(Throwable primaryFailure) {
      UpdateEngineStartFailureCleanup cleanup = claimFailClose(primaryFailure);
      if (cleanup != null) {
        cleanup.finish();
      }
    }

    UpdateEngineStartFailureCleanup claimFailClose(Throwable primaryFailure) {
      if (primaryFailure == null) {
        throw new IllegalArgumentException("primaryFailure");
      }
      return claimFailedUpdateEngineStartAttempt(this, primaryFailure);
    }

    private boolean canCompleteLocked() {
      return successfulStart
          && !settled.get()
          && activeUpdateEngineStartAttempt == this
          && publishedBinding != null
          && readerStreamBinding == publishedBinding
          && Leelaz.this.started
          && !publishedBinding.terminated;
    }

    private void settleCompleteLocked() {
      if (!settled.compareAndSet(false, true)) {
        throw new IllegalStateException("Engine start attempt was concurrently settled");
      }
      if (activeUpdateEngineStartAttempt == this) {
        activeUpdateEngineStartAttempt = null;
      }
      completionClaim = null;
      engineArbitrationLock().notifyAll();
    }

    private Leelaz owner() {
      return Leelaz.this;
    }

    boolean runIfCurrentRuntime(Runnable action) {
      if (action == null) {
        return false;
      }
      synchronized (engineArbitrationLock()) {
        ReaderStreamBinding expectedBinding =
            publishedBinding == null ? initialBinding : publishedBinding;
        if (readerStreamBinding != expectedBinding) {
          return false;
        }
        action.run();
        return true;
      }
    }

    private boolean isCurrentRuntimeLocked() {
      ReaderStreamBinding expectedBinding =
          publishedBinding == null ? initialBinding : publishedBinding;
      return readerStreamBinding == expectedBinding;
    }

    boolean claimFailureReport() {
      return failureReportClaimed.compareAndSet(false, true);
    }
  }

  UpdateEngineStartAttempt beginUpdateEngineStartAttempt() {
    synchronized (engineArbitrationLock()) {
      if (activeUpdateEngineStartAttempt != null) {
        throw new IllegalStateException("An update-engine start attempt is already active");
      }
      UpdateEngineStartAttempt attempt = new UpdateEngineStartAttempt();
      activeUpdateEngineStartAttempt = attempt;
      return attempt;
    }
  }

  static void completeUpdateEngineStartAttempts(
      UpdateEngineStartAttempt targetAttempt, UpdateEngineStartAttempt mirrorAttempt) {
    completeUpdateEngineStartAttempts(targetAttempt, mirrorAttempt, () -> {});
  }

  static void completeUpdateEngineStartAttempts(
      UpdateEngineStartAttempt targetAttempt,
      UpdateEngineStartAttempt mirrorAttempt,
      Runnable finalization) {
    if (finalization == null) {
      throw new IllegalArgumentException("finalization");
    }
    try (UpdateEngineStartCompletion completion =
        UpdateEngineStartCompletion.claim(targetAttempt, mirrorAttempt)) {
      finalization.run();
      completion.complete();
    }
  }

  static UpdateEngineStartCompletion claimUpdateEngineStartCompletion(
      UpdateEngineStartAttempt targetAttempt, UpdateEngineStartAttempt mirrorAttempt) {
    return UpdateEngineStartCompletion.claim(targetAttempt, mirrorAttempt);
  }

  static boolean runIfCurrentUpdateEngineStartRuntimes(
      UpdateEngineStartAttempt targetAttempt,
      UpdateEngineStartAttempt mirrorAttempt,
      Runnable action) {
    if (targetAttempt == null || action == null) {
      return false;
    }
    Leelaz target = targetAttempt.owner();
    Leelaz mirror = mirrorAttempt == null ? null : mirrorAttempt.owner();
    if (mirror == target) {
      return false;
    }
    Supplier<Boolean> exactAction =
        () -> {
          if (!targetAttempt.isCurrentRuntimeLocked()
              || (mirrorAttempt != null && !mirrorAttempt.isCurrentRuntimeLocked())) {
            return false;
          }
          action.run();
          return true;
        };
    if (mirror == null) {
      synchronized (target.engineArbitrationLock()) {
        return exactAction.get();
      }
    }
    return LifecycleCompletionClaim.withOrderedEndpointLocks(target, mirror, exactAction);
  }

  /**
   * Runs a required target action and an optional exact-mirror action under the endpoints' canonical
   * lock order. A rebound mirror cannot suppress terminal failure state for the still-current
   * target, but it also cannot receive bookkeeping from the stale paired attempt.
   */
  static boolean runIfCurrentUpdateEngineStartTargetRuntime(
      UpdateEngineStartAttempt targetAttempt,
      UpdateEngineStartAttempt mirrorAttempt,
      Runnable targetAction,
      Runnable mirrorAction) {
    if (targetAttempt == null || targetAction == null) {
      return false;
    }
    Leelaz target = targetAttempt.owner();
    Leelaz mirror = mirrorAttempt == null ? null : mirrorAttempt.owner();
    if (mirror == target) {
      return false;
    }
    Supplier<Boolean> exactAction =
        () -> {
          if (!targetAttempt.isCurrentRuntimeLocked()) {
            return false;
          }
          targetAction.run();
          if (mirrorAttempt != null
              && mirrorAction != null
              && mirrorAttempt.isCurrentRuntimeLocked()) {
            mirrorAction.run();
          }
          return true;
        };
    if (mirror == null) {
      synchronized (target.engineArbitrationLock()) {
        return exactAction.get();
      }
    }
    return LifecycleCompletionClaim.withOrderedEndpointLocks(target, mirror, exactAction);
  }

  static final class UpdateEngineStartCompletion implements AutoCloseable {
    private final UpdateEngineStartAttempt targetAttempt;
    private final UpdateEngineStartAttempt mirrorAttempt;
    private boolean finished;

    private UpdateEngineStartCompletion(
        UpdateEngineStartAttempt targetAttempt, UpdateEngineStartAttempt mirrorAttempt) {
      this.targetAttempt = targetAttempt;
      this.mirrorAttempt = mirrorAttempt;
    }

    private static UpdateEngineStartCompletion claim(
        UpdateEngineStartAttempt targetAttempt, UpdateEngineStartAttempt mirrorAttempt) {
      if (targetAttempt == null) {
        throw new IllegalArgumentException("targetAttempt");
      }
      Leelaz target = targetAttempt.owner();
      Leelaz mirror = mirrorAttempt == null ? null : mirrorAttempt.owner();
      if (mirror != null && target == mirror) {
        throw new IllegalArgumentException("Target and mirror attempts must own distinct engines");
      }
      UpdateEngineStartCompletion completion =
          new UpdateEngineStartCompletion(targetAttempt, mirrorAttempt);
      completion.withEndpointLocks(
          () -> {
            if (!targetAttempt.canCompleteLocked()
                || (mirrorAttempt != null && !mirrorAttempt.canCompleteLocked())) {
              throw new IllegalStateException(
                  "Cannot claim stale or unsuccessful target/mirror engine starts");
            }
            if (targetAttempt.completionClaim != null
                || (mirrorAttempt != null && mirrorAttempt.completionClaim != null)) {
              throw new IllegalStateException("Engine start completion is already claimed");
            }
            targetAttempt.completionClaim = completion;
            if (mirrorAttempt != null) {
              mirrorAttempt.completionClaim = completion;
            }
            return null;
          });
      return completion;
    }

    synchronized void complete() {
      complete(() -> null);
    }

    /**
     * Atomically commits trusted, non-callback terminal state and settles both exact attempts while
     * holding their ordered endpoint locks. The supplied action must not perform I/O, Swing work,
     * or listener callbacks.
     */
    synchronized <T> T complete(Supplier<T> terminalStateCommit) {
      if (terminalStateCommit == null) {
        throw new IllegalArgumentException("terminalStateCommit");
      }
      if (finished) {
        throw new IllegalStateException("Engine start completion is already settled");
      }
      T result =
          withEndpointLocks(
              () -> {
                if (targetAttempt.completionClaim != this
                    || !targetAttempt.canCompleteLocked()
                    || (mirrorAttempt != null
                        && (mirrorAttempt.completionClaim != this
                            || !mirrorAttempt.canCompleteLocked()))) {
                  throw new IllegalStateException(
                      "Cannot complete stale target/mirror engine starts");
                }
                T committed = terminalStateCommit.get();
                targetAttempt.settleCompleteLocked();
                if (mirrorAttempt != null) {
                  mirrorAttempt.settleCompleteLocked();
                }
                return committed;
              });
      finished = true;
      return result;
    }

    @Override
    public synchronized void close() {
      if (finished) {
        return;
      }
      withEndpointLocks(
          () -> {
            abandonLocked(targetAttempt);
            if (mirrorAttempt != null) {
              abandonLocked(mirrorAttempt);
            }
            return null;
          });
      finished = true;
    }

    private <T> T withEndpointLocks(Supplier<T> action) {
      Leelaz target = targetAttempt.owner();
      if (mirrorAttempt == null) {
        synchronized (target.engineArbitrationLock()) {
          return action.get();
        }
      }
      return LifecycleCompletionClaim.withOrderedEndpointLocks(
          target, mirrorAttempt.owner(), action);
    }

    private void abandonLocked(UpdateEngineStartAttempt attempt) {
      if (attempt.completionClaim == this) {
        attempt.completionClaim = null;
        attempt.owner().engineArbitrationLock().notifyAll();
      }
    }
  }

  final class UpdateEngineStartFailureCleanup {
    private final Throwable primaryFailure;
    private final ReaderStreamBinding binding;
    private final ReaderExecutorSnapshot executors;
    private final EngineStopObservation stoppedObservation;
    private final Process extraProcess;
    private final EngineTransport extraRemoteTransport;
    private final SSHController extraJavaSsh;
    private final EngineStopObservation unboundObservation;
    private final AtomicBoolean finished = new AtomicBoolean();

    private UpdateEngineStartFailureCleanup(
        Throwable primaryFailure,
        ReaderStreamBinding binding,
        ReaderExecutorSnapshot executors,
        EngineStopObservation stoppedObservation,
        Process extraProcess,
        EngineTransport extraRemoteTransport,
        SSHController extraJavaSsh,
        EngineStopObservation unboundObservation) {
      this.primaryFailure = primaryFailure;
      this.binding = binding;
      this.executors = executors;
      this.stoppedObservation = stoppedObservation;
      this.extraProcess = extraProcess;
      this.extraRemoteTransport = extraRemoteTransport;
      this.extraJavaSsh = extraJavaSsh;
      this.unboundObservation = unboundObservation;
    }

    void finish() {
      if (finished.compareAndSet(false, true)) {
        finishClaimedUpdateEngineStartFailure(this);
      }
    }
  }

  private void recordUpdateEngineStartBindingLocked(ReaderStreamBinding binding) {
    UpdateEngineStartAttempt attempt = updateEngineStartAttemptContext.get();
    if (attempt != null) {
      attempt.recordBindingLocked(binding);
    }
  }

  private void recordUpdateEngineStartProcess(Process startedProcess) {
    UpdateEngineStartAttempt attempt = updateEngineStartAttemptContext.get();
    if (attempt != null && startedProcess != null) {
      attempt.recordProcess(startedProcess);
    }
  }

  private void recordUpdateEngineStartRemoteTransport(EngineTransport startedTransport) {
    UpdateEngineStartAttempt attempt = updateEngineStartAttemptContext.get();
    if (attempt != null && startedTransport != null) {
      attempt.recordRemoteTransport(startedTransport);
    }
  }

  private void recordUpdateEngineStartJavaSsh(SSHController startedJavaSsh) {
    UpdateEngineStartAttempt attempt = updateEngineStartAttemptContext.get();
    if (attempt != null && startedJavaSsh != null) {
      attempt.recordJavaSsh(startedJavaSsh);
    }
  }

  private UpdateEngineStartFailureCleanup claimFailedUpdateEngineStartAttempt(
      UpdateEngineStartAttempt attempt, Throwable primaryFailure) {
    ReaderStreamBinding binding;
    ReaderExecutorSnapshot executors = null;
    EngineStopObservation stoppedObservation = null;
    Process extraProcess;
    EngineTransport extraRemoteTransport;
    SSHController extraJavaSsh;
    EngineStopObservation unboundObservation = null;
    synchronized (engineArbitrationLock()) {
      // Once final completion owns the exact incarnation it is the linearization winner. A
      // concurrent failure callback must defer to that finalization and let its terminal callback
      // retry after success or abandonment; otherwise it could retire a runtime while finalization
      // is already publishing READY state.
      if (attempt.completionClaim != null) {
        return null;
      }
      if (!attempt.settled.compareAndSet(false, true)) {
        return null;
      }
      if (activeUpdateEngineStartAttempt == attempt) {
        activeUpdateEngineStartAttempt = null;
      }
      attempt.completionClaim = null;
      engineArbitrationLock().notifyAll();
      binding = attempt.publishedBinding;
      if (binding != null) {
        boolean current = readerStreamBinding == binding;
        Throwable preparationFailure = null;
        if (current) {
          preparationFailure =
              runEngineCleanupStep(preparationFailure, Leelaz.this::notPondering);
        }
        executors = requestReaderShutdown(binding, true);
        if (current) {
          stoppedObservation = markReaderBindingStoppedLocked(binding, executors);
          if (preparationFailure != null) {
            stoppedObservation =
                new EngineStopObservation(
                    stoppedObservation.engineId,
                    stoppedObservation.foregroundSample,
                    appendEngineCleanupFailure(
                        preparationFailure, stoppedObservation.preparationFailure));
          }
        }
      } else if (isCurrentUnboundUpdateStartResource(attempt)) {
        Throwable preparationFailure = null;
        preparationFailure =
            runEngineCleanupStep(preparationFailure, Leelaz.this::notPondering);
        preparationFailure =
            runEngineCleanupStep(preparationFailure, Leelaz.this::leela0110StopPonder);
        unboundObservation = markEngineStoppedLocked(preparationFailure, true);
      }
      ReaderStreamBinding current = readerStreamBinding;
      extraProcess =
          isUnboundAttemptResource(attempt.publishedProcess, binding, current)
              ? attempt.publishedProcess
              : null;
      extraRemoteTransport =
          isUnboundAttemptResource(attempt.publishedRemoteTransport, binding, current)
              ? attempt.publishedRemoteTransport
              : null;
      extraJavaSsh =
          isUnboundAttemptResource(attempt.publishedJavaSsh, binding, current)
              ? attempt.publishedJavaSsh
              : null;
    }

    return new UpdateEngineStartFailureCleanup(
        primaryFailure,
        binding,
        executors,
        stoppedObservation,
        extraProcess,
        extraRemoteTransport,
        extraJavaSsh,
        unboundObservation);
  }

  private void finishClaimedUpdateEngineStartFailure(UpdateEngineStartFailureCleanup cleanup) {
    Throwable cleanupFailure = null;
    if (cleanup.unboundObservation != null || cleanup.extraProcess != null) {
      cleanupFailure =
          runEngineCleanupStep(
              cleanupFailure,
              () ->
                  notifyClaimedProcessStopped(
                      cleanup.extraProcess, true, cleanup.unboundObservation));
    }
    if (cleanup.binding != null && cleanup.executors != null) {
      cleanupFailure =
          runEngineCleanupStep(
              cleanupFailure,
              () ->
                  finishClaimedFailedStart(
                      cleanup.binding, cleanup.executors, cleanup.stoppedObservation));
    }
    if (cleanup.extraRemoteTransport != null) {
      cleanupFailure =
          runEngineCleanupStep(cleanupFailure, cleanup.extraRemoteTransport::close);
    }
    if (cleanup.extraJavaSsh != null) {
      cleanupFailure = runEngineCleanupStep(cleanupFailure, cleanup.extraJavaSsh::close);
    }
    if (cleanup.extraProcess != null) {
      cleanupFailure =
          runEngineCleanupStep(cleanupFailure, cleanup.extraProcess::destroyForcibly);
    }
    appendEngineCleanupFailure(cleanup.primaryFailure, cleanupFailure);
  }

  private boolean isCurrentUnboundUpdateStartResource(UpdateEngineStartAttempt attempt) {
    return (attempt.publishedProcess != null && process == attempt.publishedProcess)
        || (attempt.publishedRemoteTransport != null
            && remoteTransport == attempt.publishedRemoteTransport)
        || (attempt.publishedJavaSsh != null && javaSSH == attempt.publishedJavaSsh);
  }

  private static boolean isUnboundAttemptResource(
      Object resource, ReaderStreamBinding attemptBinding, ReaderStreamBinding currentBinding) {
    if (resource == null || bindingContainsResource(attemptBinding, resource)) {
      return false;
    }
    return !bindingContainsResource(currentBinding, resource);
  }

  private static boolean bindingContainsResource(ReaderStreamBinding binding, Object resource) {
    return binding != null
        && (binding.process == resource
            || binding.remoteTransport == resource
            || binding.javaSSH == resource);
  }

  private void finishClaimedNormalQuit(
      ReaderStreamBinding binding,
      ReaderExecutorSnapshot executors,
      EngineStopObservation stoppedObservation) {
    if (stoppedObservation != null) {
      try {
        EngineManager.publishStoppedEngineIconIfCurrent(this, binding);
      } catch (RuntimeException | Error presentationFailure) {
        presentationFailure.printStackTrace();
      }
    }

    notifyClaimedReaderBindingStopped(binding, executors, stoppedObservation);
    //		if(isScreen)
    //			sendCommand("name");
    Throwable cleanupFailure =
        stoppedObservation == null ? null : stoppedObservation.preparationFailure;
    if (executors.ownsTransportClose) {
      cleanupFailure = runEngineCleanupStep(cleanupFailure, () -> sendQuitToBinding(binding));
    }
    if (binding.javaSSH != null || binding.remoteTransport != null) {
      cleanupFailure =
          runEngineCleanupStep(
              cleanupFailure, () -> closeClaimedReaderBindingTransport(binding, executors));
      cleanupFailure =
          runEngineCleanupStep(
              cleanupFailure, () -> shutdownExecutor(executors.stdoutExecutor));
      cleanupFailure =
          runEngineCleanupStep(
              cleanupFailure, () -> shutdownExecutor(executors.stderrExecutor));
    } else {
      cleanupFailure =
          runEngineCleanupStep(
              cleanupFailure, () -> shutdownExecutor(executors.stdoutExecutor));
      cleanupFailure =
          runEngineCleanupStep(
              cleanupFailure, () -> shutdownExecutor(executors.stderrExecutor));
      cleanupFailure =
          runEngineCleanupStep(
              cleanupFailure, () -> closeClaimedReaderBindingTransport(binding, executors));
    }
    cleanupFailure =
        runEngineCleanupStep(cleanupFailure, () -> clearOutputStreamIfCurrent(binding));
    rethrowEngineCleanupFailure(cleanupFailure);
  }

  private void finishClaimedForceQuit(
      ReaderStreamBinding binding,
      ForceReaderExecutorSnapshot executors,
      EngineStopObservation stoppedObservation) {
    if (stoppedObservation != null && executors.ownsPhysicalClose) {
      try {
        EngineManager.publishStoppedEngineIconIfCurrent(this, binding);
      } catch (RuntimeException | Error presentationFailure) {
        presentationFailure.printStackTrace();
      }
    }

    notifyClaimedProcessStopped(
        binding.process, executors.ownsPhysicalClose, stoppedObservation);
    Throwable cleanupFailure =
        stoppedObservation == null ? null : stoppedObservation.preparationFailure;
    cleanupFailure =
        runEngineCleanupStep(cleanupFailure, () -> forceCloseClaimedReaderBinding(binding));
    cleanupFailure =
        runEngineCleanupStep(
            cleanupFailure, () -> shutdownExecutor(executors.stdoutExecutor));
    cleanupFailure =
        runEngineCleanupStep(
            cleanupFailure, () -> shutdownExecutor(executors.stderrExecutor));
    cleanupFailure =
        runEngineCleanupStep(cleanupFailure, () -> clearOutputStreamIfCurrent(binding));
    rethrowEngineCleanupFailure(cleanupFailure);
  }

  private static void forceCloseClaimedReaderBinding(ReaderStreamBinding binding) {
    if (binding.javaSSH != null) {
      binding.javaSSH.close();
    } else if (binding.remoteTransport != null) {
      binding.remoteTransport.abort();
    } else if (binding.process != null) {
      binding.process.destroyForcibly();
    }
  }

  private static void closeClaimedReaderBindingTransport(
      ReaderStreamBinding binding, ReaderExecutorSnapshot executors) {
    if (!executors.ownsTransportClose) {
      return;
    }
    if (binding.javaSSH != null) {
      binding.javaSSH.close();
    } else if (binding.remoteTransport != null) {
      binding.remoteTransport.close();
    } else if (binding.process != null) {
      binding.process.destroy();
    }
  }

  private void notifyClaimedReaderBindingStopped(
      ReaderStreamBinding binding,
      ReaderExecutorSnapshot executors,
      EngineStopObservation stoppedObservation) {
    notifyClaimedProcessStopped(
        binding.process, executors.ownsTransportClose, stoppedObservation);
  }

  private void notifyClaimedProcessStopped(
      Process stoppedProcess,
      boolean ownsTransportClose,
      EngineStopObservation stoppedObservation) {
    if (!ownsTransportClose) {
      return;
    }
    // A stale SSH/remote binding has no process identity with which the coordinator can fence its
    // owner-keyed bookkeeping. Its exact transport is still closed below, but it must not clear a
    // same-object replacement runtime's foreground sample or observation state.
    if (stoppedObservation == null && stoppedProcess == null) {
      return;
    }
    try {
      AnalysisResourceCoordinator.processStoppedAfterIdentityClaim(
          this,
          AnalysisResourceCoordinator.Purpose.MAIN_BOARD,
          stoppedProcess,
          stoppedObservation == null ? null : stoppedObservation.engineId,
          stoppedObservation == null ? null : stoppedObservation.foregroundSample);
    } catch (RuntimeException | Error observationFailure) {
      observationFailure.printStackTrace();
    }
    if (stoppedObservation != null && stoppedObservation.engineId != null) {
      try {
        if (EngineObservation.engineDiagnosticsEnabled()) {
          EngineObservation.recordRecentStderr(
              stoppedObservation.engineId, snapshotRecentLines(recentStderrLines));
        }
        EngineObservation.recordStopped(stoppedObservation.engineId, "stopped");
      } catch (RuntimeException | Error observationFailure) {
        observationFailure.printStackTrace();
      }
    }
  }

  private void sendQuitToBinding(ReaderStreamBinding binding) {
    BufferedOutputStream bindingOutput = binding.output;
    if (bindingOutput == null) {
      return;
    }
    try {
      synchronized (bindingOutput) {
        bindingOutput.write("quit\n".getBytes());
        bindingOutput.flush();
      }
    } catch (IOException failure) {
      String detail = failure.getLocalizedMessage();
      if (detail == null || detail.trim().isEmpty()) {
        detail = failure.getClass().getSimpleName();
      }
      rememberRecentLine(recentStderrLines, "Failed to send GTP command 'quit': " + detail);
      System.err.println("Failed to send GTP command 'quit': " + detail);
    }
  }

  private EngineStopObservation markReaderBindingStoppedIfCurrent(
      ReaderStreamBinding binding, ReaderExecutorSnapshot executors) {
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding != binding) {
        return null;
      }
      return markReaderBindingStoppedLocked(binding, executors);
    }
  }

  private EngineStopObservation markReaderBindingStoppedLocked(
      ReaderStreamBinding binding, ReaderExecutorSnapshot executors) {
    // These engine-level requests belong to the binding being retired. Keep their cancellation in
    // the same arbitration critical section as the incarnation check so a stale stop can never
    // cancel work installed by a replacement binding.
    Throwable preparationFailure = null;
    preparationFailure =
        runEngineCleanupStep(
            preparationFailure, () -> cancelLeela0110PonderForReaderBinding(binding));
    // Recovery settlement uses this same binding fence. Once a stop wins it, no final live check
    // may cross the started/isLoaded transition and promote a stopped recovery endpoint.
    binding.analysisOutputMutationLock.lock();
    try {
      return markEngineStoppedLocked(preparationFailure, executors.ownsTransportClose);
    } finally {
      binding.analysisOutputMutationLock.unlock();
    }
  }

  private EngineStopObservation markEngineStoppedLocked(
      Throwable preparationFailure, boolean ownsTransportClose) {
    isNormalEnd = true;
    started = false;
    isLoaded = false;
    String engineId = null;
    if (ownsTransportClose) {
      try {
        engineId = loggingEngineId;
        if (engineId == null) {
          engineId = EngineObservation.identityFor(this);
        }
        if (engineId != null && EngineObservation.discardIdentityIfCurrent(this, engineId)) {
          if (engineId.equals(loggingEngineId)) {
            loggingEngineId = null;
          }
        } else {
          engineId = null;
        }
      } catch (RuntimeException | Error observationFailure) {
        preparationFailure =
            appendEngineCleanupFailure(preparationFailure, observationFailure);
        engineId = null;
      }
    }
    Object foregroundSample = null;
    try {
      foregroundSample = AnalysisResourceCoordinator.captureForegroundSampleIdentity(this);
    } catch (RuntimeException | Error sampleFailure) {
      preparationFailure = appendEngineCleanupFailure(preparationFailure, sampleFailure);
    }
    return new EngineStopObservation(engineId, foregroundSample, preparationFailure);
  }

  private static final class EngineStopObservation {
    private final String engineId;
    private final Object foregroundSample;
    private final Throwable preparationFailure;

    private EngineStopObservation(
        String engineId, Object foregroundSample, Throwable preparationFailure) {
      this.engineId = engineId;
      this.foregroundSample = foregroundSample;
      this.preparationFailure = preparationFailure;
    }
  }

  private static Throwable runEngineCleanupStep(Throwable firstFailure, Runnable cleanup) {
    try {
      cleanup.run();
    } catch (RuntimeException | Error cleanupFailure) {
      return appendEngineCleanupFailure(firstFailure, cleanupFailure);
    }
    return firstFailure;
  }

  private static Throwable appendEngineCleanupFailure(Throwable firstFailure, Throwable next) {
    if (next == null) {
      return firstFailure;
    }
    if (firstFailure == null) {
      return next;
    }
    if (firstFailure != next) {
      try {
        firstFailure.addSuppressed(next);
      } catch (RuntimeException | Error ignored) {
        // Preserve the first cleanup failure even if suppression itself is unavailable.
      }
    }
    return firstFailure;
  }

  static String safeFailureDetail(Throwable failure, String fallback) {
    if (failure == null) {
      return fallback;
    }
    try {
      String detail = failure.getMessage();
      return detail == null || detail.trim().isEmpty() ? fallback : detail;
    } catch (RuntimeException | Error detailFailure) {
      appendEngineCleanupFailure(failure, detailFailure);
      return fallback;
    }
  }

  private static void rethrowEngineCleanupFailure(Throwable failure) {
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
  }

  /** Runs {@code action} only while the exact reader/process incarnation remains current. */
  boolean runIfCurrentEngineIncarnation(Object expectedIncarnation, Runnable action) {
    if (expectedIncarnation == null || action == null) {
      return false;
    }
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding != expectedIncarnation) {
        return false;
      }
      action.run();
      return true;
    }
  }

  /** Runs a state-only action while the exact live reader/process incarnation remains current. */
  boolean runIfCurrentLiveEngineIncarnation(Object expectedIncarnation, Runnable action) {
    if (expectedIncarnation == null || action == null) {
      return false;
    }
    synchronized (engineArbitrationLock()) {
      if (!isCurrentLiveEngineIncarnationLocked(expectedIncarnation)) {
        return false;
      }
      action.run();
      return true;
    }
  }

  /**
   * Runs a state-only action under two exact live endpoint locks in a stable global order.
   * Callers may hold selection and PRIMARY before entering; the action must acquire neither.
   */
  static boolean runIfCurrentLiveEngineIncarnations(
      Leelaz first,
      Object firstIncarnation,
      Leelaz second,
      Object secondIncarnation,
      Runnable action) {
    if (first == null
        || second == null
        || first == second
        || firstIncarnation == null
        || secondIncarnation == null
        || action == null) {
      return false;
    }
    return withOrderedEngineArbitrationLocks(
        first,
        second,
        () -> {
          if (!first.isCurrentLiveEngineIncarnationLocked(firstIncarnation)
              || !second.isCurrentLiveEngineIncarnationLocked(secondIncarnation)) {
            return false;
          }
          action.run();
          return true;
        });
  }

  private static <T> T withOrderedEngineArbitrationLocks(
      Leelaz first, Leelaz second, Supplier<T> action) {
    if (first.engineArbitrationOrder == second.engineArbitrationOrder) {
      synchronized (LIFECYCLE_COMPLETION_PAIR_TIE_LOCK) {
        return withEngineArbitrationLocks(first, second, action);
      }
    }
    return first.engineArbitrationOrder < second.engineArbitrationOrder
        ? withEngineArbitrationLocks(first, second, action)
        : withEngineArbitrationLocks(second, first, action);
  }

  private static <T> T withEngineArbitrationLocks(
      Leelaz lower, Leelaz upper, Supplier<T> action) {
    synchronized (lower.engineArbitrationLock()) {
      synchronized (upper.engineArbitrationLock()) {
        return action.get();
      }
    }
  }

  private static <T> T withOrderedEngineArbitrationAndQueueLocks(
      Leelaz first, Leelaz second, Supplier<T> action) {
    if (first.engineArbitrationOrder == second.engineArbitrationOrder) {
      synchronized (LIFECYCLE_COMPLETION_PAIR_TIE_LOCK) {
        return withEngineArbitrationAndQueueLocks(first, second, action);
      }
    }
    return first.engineArbitrationOrder < second.engineArbitrationOrder
        ? withEngineArbitrationAndQueueLocks(first, second, action)
        : withEngineArbitrationAndQueueLocks(second, first, action);
  }

  private static <T> T withEngineArbitrationAndQueueLocks(
      Leelaz lower, Leelaz upper, Supplier<T> action) {
    synchronized (lower.engineArbitrationLock()) {
      synchronized (upper.engineArbitrationLock()) {
        synchronized (lower.commandQueue()) {
          synchronized (upper.commandQueue()) {
            return action.get();
          }
        }
      }
    }
  }

  /**
   * Claims a short, binding-scoped presentation lease for an already selected runtime. The caller
   * must first establish manager/slot/PRIMARY identity; the lease then prevents same-object reader
   * rebind from crossing the callback-time incarnation check while non-modal UI is constructed.
   */
  EngineRuntimeUiLease claimEngineRuntimeUiLeaseIfCurrent(Object expectedIncarnation) {
    if (!(expectedIncarnation instanceof ReaderStreamBinding)) {
      return null;
    }
    synchronized (engineArbitrationLock()) {
      ReaderStreamBinding expectedBinding = (ReaderStreamBinding) expectedIncarnation;
      if (readerStreamBinding != expectedBinding || readerStreamRebindInProgress) {
        return null;
      }
      expectedBinding.runtimeUiPresentationsInProgress++;
      return new EngineRuntimeUiLease(this, expectedBinding);
    }
  }

  boolean runIfCurrentParserReadyPresentationIncarnation(
      Object expectedIncarnation, Runnable action) {
    if (expectedIncarnation == null || action == null) {
      return false;
    }
    synchronized (engineArbitrationLock()) {
      if (!isCurrentLiveEngineIncarnationLocked(expectedIncarnation)
          || isOrdinaryForwardingOccupied()
          || hasLifecycleCompletionLocked()
          || activeUpdateEngineStartAttempt != null) {
        return false;
      }
      action.run();
      return true;
    }
  }

  boolean suppressesGlobalEnginePresentation(Object expectedIncarnation) {
    synchronized (engineArbitrationLock()) {
      return expectedIncarnation instanceof ReaderStreamBinding
          && readerStreamBinding == expectedIncarnation
          && (((ReaderStreamBinding) expectedIncarnation).suppressGlobalEnginePresentation
              || ((ReaderStreamBinding) expectedIncarnation)
                  .deferredEngineGameRecoveryPresentationSuppressed);
    }
  }

  boolean allowsGlobalEnginePresentation(Object expectedIncarnation) {
    return !suppressesGlobalEnginePresentation(expectedIncarnation);
  }

  /**
   * Releases only a successfully committed recovery binding from bootstrap quarantine. This does
   * not assign PRIMARY ownership or change its startup PRIMARY generation; the manager calls it
   * while atomically retiring the recovery-batch output barrier.
   */
  boolean unquarantineDeferredEngineGameRecoveryIncarnation(Object expectedIncarnation) {
    synchronized (engineArbitrationLock()) {
      if (!(expectedIncarnation instanceof ReaderStreamBinding)
          || readerStreamBinding != expectedIncarnation
          || !isCurrentLiveEngineIncarnationLocked(expectedIncarnation)
          || !((ReaderStreamBinding) expectedIncarnation)
              .deferredEngineGameRecoveryPresentationSuppressed) {
        return false;
      }
      ((ReaderStreamBinding) expectedIncarnation)
          .deferredEngineGameRecoveryPresentationSuppressed = false;
      return true;
    }
  }

  /** Captures the nullable reader identity without creating a synthetic binding. */
  Object captureEngineIncarnationFence() {
    synchronized (engineArbitrationLock()) {
      return readerStreamBinding;
    }
  }

  Object analysisOutputRecoveryToken(Object expectedIncarnation) {
    if (!(expectedIncarnation instanceof ReaderStreamBinding)) {
      return null;
    }
    ReaderStreamBinding expectedBinding = (ReaderStreamBinding) expectedIncarnation;
    // Physical writers call this while owning the manager's analysis-write lease. They must not
    // block on engine arbitration because reader rebind owns that monitor while waiting for the
    // writer to finish. All fields in this identity snapshot are volatile/final.
    return readerStreamBinding == expectedBinding && !expectedBinding.terminated
        ? expectedBinding.analysisOutputRecoveryToken
        : null;
  }

  /**
   * Runs one recovery publication under the binding settlement fence without waiting for it.
   * Callers may already own manager analysis/selection/PRIMARY locks; a blocking acquisition here
   * would deadlock with reader rebind waiting for an in-flight physical writer.
   */
  boolean tryRunIfCurrentLiveRecoveryBinding(
      Object expectedIncarnation, Object recoveryToken, Runnable action) {
    if (!(expectedIncarnation instanceof ReaderStreamBinding)
        || recoveryToken == null
        || action == null) {
      return false;
    }
    ReaderStreamBinding binding = (ReaderStreamBinding) expectedIncarnation;
    if (!binding.analysisOutputMutationLock.tryLock()) {
      return false;
    }
    try {
      if (!isCurrentLiveRecoveryBindingLocked(binding, recoveryToken)) {
        return false;
      }
      action.run();
      return true;
    } finally {
      binding.analysisOutputMutationLock.unlock();
    }
  }

  /** Called only while {@code binding.analysisOutputMutationLock} is held. */
  private boolean isCurrentLiveRecoveryBindingLocked(
      ReaderStreamBinding binding, Object recoveryToken) {
    return binding != null
        && recoveryToken != null
        && readerStreamBinding == binding
        && !readerStreamRebindInProgress
        && !binding.terminated
        && !binding.readerShutdownRequested
        && started
        && isLoaded
        && binding.output != null
        && outputStream == binding.output
        && binding.analysisOutputRecoveryToken == recoveryToken;
  }

  /**
   * Attaches one manager-owned recovery capability to the exact current reader. A freshly started
   * reader already receives this token from the failed-switch or deferred engine-game recovery
   * startup context; this path also covers a still-live rollback engine whose stream did not need
   * replacement.
   */
  Object authorizeAnalysisOutputRecoveryForCurrentBinding(Object recoveryToken) {
    return authorizeAnalysisOutputRecoveryForExactBinding(
        captureEngineIncarnationFence(), recoveryToken);
  }

  /** Attaches a recovery capability only if the caller's exact reader is still current. */
  Object authorizeAnalysisOutputRecoveryForExactBinding(
      Object expectedIncarnation, Object recoveryToken) {
    if (!(expectedIncarnation instanceof ReaderStreamBinding) || recoveryToken == null) {
      return null;
    }
    synchronized (engineArbitrationLock()) {
      ReaderStreamBinding binding = (ReaderStreamBinding) expectedIncarnation;
      if (readerStreamBinding != binding
          || binding.terminated
          || binding.readerShutdownRequested
          || binding.output == null
          || outputStream != binding.output
          || (binding.analysisOutputRecoveryToken != null
              && binding.analysisOutputRecoveryToken != recoveryToken)) {
        return null;
      }
      binding.analysisOutputRecoveryToken = recoveryToken;
      return binding;
    }
  }

  /**
   * Commits a successful recovery at the manager's analysis-mutation/selection boundary. A fresh
   * analysis command written while the recovery gate was active is promoted from its exact
   * tombstone to an ordinary owner. If no command was emitted, suppression intentionally remains
   * until the next physical ordinary owner replaces the stale pre-recovery stream state.
   */
  boolean completeAnalysisOutputRecovery(
      Object expectedIncarnation,
      Object recoveryToken,
      boolean requireFreshOwner,
      Supplier<Boolean> finalSettlement) {
    if (!(expectedIncarnation instanceof ReaderStreamBinding)
        || recoveryToken == null
        || finalSettlement == null) {
      return false;
    }
    ReaderStreamBinding binding = (ReaderStreamBinding) expectedIncarnation;
    // Never block here: the caller owns the global analysis/selection boundary, while reader
    // rebind may own endpoint arbitration and wait for a physical writer that needs that boundary.
    if (!binding.analysisOutputMutationLock.tryLock()) {
      return false;
    }
    try {
      if (!isCurrentLiveRecoveryBindingLocked(binding, recoveryToken)) {
        return false;
      }
      AnalysisOutputOwnership ownership = binding.analysisOutputOwnership.get();
      boolean promotedFreshOwner = false;
      if (ownership != null && ownership.recoveryToken != null) {
        if (!ownership.isRecoveryTombstone(recoveryToken)
            || ownership.generation != analysisOutputGeneration.get()) {
          return false;
        }
        promotedFreshOwner = true;
      }
      if (requireFreshOwner && !promotedFreshOwner) {
        return false;
      }
      if (!Boolean.TRUE.equals(finalSettlement.get())) {
        return false;
      }
      if (promotedFreshOwner) {
        binding.analysisOutputOwnership.set(
            AnalysisOutputOwnership.ordinary(
                analysisOutputGeneration.get(), binding.analysisStateLineage));
        binding.suppressGlobalEnginePresentation = false;
        suppressGlobalEnginePresentationUntilOwned = false;
      }
      binding.analysisOutputRecoveryToken = null;
      return true;
    } finally {
      binding.analysisOutputMutationLock.unlock();
    }
  }

  /** Clears a failed/stale capability while retaining fail-closed parser quarantine. */
  void abandonAnalysisOutputRecovery(
      Object expectedIncarnation, Object recoveryToken) {
    if (!(expectedIncarnation instanceof ReaderStreamBinding) || recoveryToken == null) {
      return;
    }
    ReaderStreamBinding binding = (ReaderStreamBinding) expectedIncarnation;
    binding.analysisOutputMutationLock.lock();
    try {
      if (binding.analysisOutputRecoveryToken == null) {
        binding.analysisOutputRecoveryToken = recoveryToken;
      }
      binding.suppressGlobalEnginePresentation = true;
      suppressGlobalEnginePresentationUntilOwned = true;
    } finally {
      binding.analysisOutputMutationLock.unlock();
    }
  }

  void startEngineForAnalysisOutputRecovery(Object recoveryToken, int index) throws IOException {
    if (recoveryToken == null || analysisOutputRecoveryTokenContext.get() != null) {
      throw new IllegalStateException("Invalid nested analysis-output recovery start");
    }
    analysisOutputRecoveryTokenContext.set(recoveryToken);
    try {
      startEngine(index);
    } finally {
      analysisOutputRecoveryTokenContext.remove();
    }
  }

  /**
   * Quarantines parser-driven global presentation for the current and next reader incarnation.
   * Recovery owns this quarantine until a physically written ordinary or exact analysis command
   * wins the manager barrier and installs a fresh output owner.
   */
  void suppressGlobalEnginePresentationUntilPhysicalAnalysisOwnership() {
    // The manager has already installed its global recovery barrier. Do not wait for endpoint
    // arbitration while holding that barrier: rebind may own the endpoint monitor while waiting
    // for an older physical writer that needs the barrier. The persistent volatile flag covers a
    // newly constructed binding. Each binding publication also re-reads this flag after making
    // the new identity visible, closing the race where rebind sampled false immediately before
    // this write; the stable-read loop covers identity changes around the current binding write.
    suppressGlobalEnginePresentationUntilOwned = true;
    ReaderStreamBinding observed;
    do {
      observed = readerStreamBinding;
      if (observed != null) {
        observed.suppressGlobalEnginePresentation = true;
      }
    } while (observed != readerStreamBinding);
  }

  /** Captures the startup PRIMARY generation bound to the exact reader that produced a line. */
  private long captureStartupPrimaryGeneration(Object expectedIncarnation) {
    if (expectedIncarnation == null) {
      return -1L;
    }
    synchronized (engineArbitrationLock()) {
      return readerStreamBinding == expectedIncarnation
          ? readerStreamBinding.startupPrimaryEngineGeneration
          : -1L;
    }
  }

  /** Runs {@code action} while the nullable reader identity remains exactly unchanged. */
  boolean runIfEngineIncarnationFenceUnchanged(Object expectedIncarnation, Runnable action) {
    if (action == null) {
      return false;
    }
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding != expectedIncarnation) {
        return false;
      }
      action.run();
      return true;
    }
  }

  /** Runs {@code action} only after a different reader incarnation superseded the fence. */
  boolean runIfEngineIncarnationFenceChanged(Object expectedIncarnation, Runnable action) {
    if (action == null) {
      return false;
    }
    synchronized (engineArbitrationLock()) {
      ReaderStreamBinding current = readerStreamBinding;
      if (current == expectedIncarnation) {
        return false;
      }
      action.run();
      return true;
    }
  }

  /**
   * Pins one exact, already-published reader incarnation while a lifecycle decision is completed
   * outside the endpoint lock. Stream rebinding waits for the lease, so a callback can perform
   * failure settlement or presentation without an identity-check/rebind window. A lease owner is
   * rejected rather than allowed to wait on itself if it synchronously attempts a stream rebind.
   */
  EngineIncarnationLease claimEngineIncarnationLease(Object expectedIncarnation) {
    return claimEngineIncarnationLease(expectedIncarnation, () -> {});
  }

  /**
   * Claims an exact incarnation lease and performs one short state mutation in the same endpoint
   * critical section. If the mutation fails, the provisional lease is rolled back before the
   * original failure is rethrown.
   */
  EngineIncarnationLease claimEngineIncarnationLease(
      Object expectedIncarnation, Runnable exactMutation) {
    if (!(expectedIncarnation instanceof ReaderStreamBinding)) {
      return null;
    }
    if (exactMutation == null) {
      return null;
    }
    ReaderStreamBinding expectedBinding = (ReaderStreamBinding) expectedIncarnation;
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding != expectedBinding || readerStreamRebindInProgress) {
        return null;
      }
      Thread leaseOwner = Thread.currentThread();
      incrementEngineIncarnationLeaseLocked(leaseOwner);
      try {
        exactMutation.run();
        return new EngineIncarnationLease(this, leaseOwner);
      } catch (RuntimeException | Error failure) {
        try {
          decrementEngineIncarnationLeaseLocked(leaseOwner);
        } catch (RuntimeException | Error cleanupFailure) {
          if (cleanupFailure != failure) {
            try {
              failure.addSuppressed(cleanupFailure);
            } catch (RuntimeException | Error ignored) {
              // Preserve the exact mutation failure if suppression is itself unavailable.
            }
          }
        }
        throw failure;
      }
    }
  }

  /** Caller holds the endpoint lock. */
  private void incrementEngineIncarnationLeaseLocked(Thread leaseOwner) {
    engineIncarnationLeaseDepth++;
    engineIncarnationLeaseOwners.merge(leaseOwner, 1, Integer::sum);
  }

  /** Caller holds the endpoint lock. */
  private void decrementEngineIncarnationLeaseLocked(Thread leaseOwner) {
    Integer ownerDepth = engineIncarnationLeaseOwners.get(leaseOwner);
    if (engineIncarnationLeaseDepth <= 0 || ownerDepth == null || ownerDepth <= 0) {
      throw new IllegalStateException("Engine incarnation lease depth underflow");
    }
    engineIncarnationLeaseDepth--;
    if (ownerDepth == 1) {
      engineIncarnationLeaseOwners.remove(leaseOwner);
    } else {
      engineIncarnationLeaseOwners.put(leaseOwner, ownerDepth - 1);
    }
    engineArbitrationLock().notifyAll();
  }

  private void releaseEngineIncarnationLease(EngineIncarnationLease lease) {
    if (lease == null || lease.owner != this || !lease.released.compareAndSet(false, true)) {
      return;
    }
    synchronized (engineArbitrationLock()) {
      decrementEngineIncarnationLeaseLocked(lease.leaseOwner);
    }
  }

  static final class EngineIncarnationLease implements AutoCloseable {
    private final Leelaz owner;
    private final Thread leaseOwner;
    private final AtomicBoolean released = new AtomicBoolean();

    private EngineIncarnationLease(Leelaz owner, Thread leaseOwner) {
      this.owner = owner;
      this.leaseOwner = leaseOwner;
    }

    @Override
    public void close() {
      owner.releaseEngineIncarnationLease(this);
    }
  }

  /**
   * Runs a short terminal-state mutation while one or two nullable engine incarnations remain
   * exactly unchanged. Callers establish any higher-level selection/primary locks first.
   */
  static boolean runIfEngineIncarnationFencesUnchanged(
      Leelaz target,
      Object targetIncarnation,
      Leelaz mirror,
      Object mirrorIncarnation,
      Runnable action) {
    if (target == null || action == null || mirror == target) {
      return false;
    }
    java.util.function.Supplier<Boolean> exactAction =
        () -> {
          if (!hasExactLiveEngineIncarnationLocked(target, targetIncarnation)
              || (mirror != null
                  && !hasExactLiveEngineIncarnationLocked(mirror, mirrorIncarnation))) {
            return false;
          }
          action.run();
          return true;
        };
    if (mirror == null) {
      synchronized (target.engineArbitrationLock()) {
        return exactAction.get();
      }
    }
    return LifecycleCompletionClaim.withOrderedEndpointLocks(target, mirror, exactAction);
  }

  private static boolean hasExactLiveEngineIncarnationLocked(
      Leelaz engine, Object expectedIncarnation) {
    ReaderStreamBinding binding = engine.readerStreamBinding;
    return binding == expectedIncarnation
        && (binding == null || !binding.terminated)
        && engine.started
        && engine.isLoaded;
  }

  /**
   * Settles the command state owned by one failed reader binding before an engine-game transaction
   * waits for its physical-request leases. A later stream rebind observes the marker and does not
   * publish the same reset callbacks twice.
   */
  boolean retireEngineGameCommandStateForFailedIncarnation(
      Object expectedIncarnation, String detail) {
    if (!(expectedIncarnation instanceof ReaderStreamBinding)) {
      return false;
    }
    ReaderStreamBinding expectedBinding = (ReaderStreamBinding) expectedIncarnation;
    // Fast rejection also avoids queueing behind initializeStreams while it retains the endpoint
    // monitor and waits for the same physical writer to leave the command stream.
    if (normalCommandSendInProgress) {
      return false;
    }
    GtpCommandStateReset reset;
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding != expectedBinding
          || expectedBinding.engineGameCommandStateRetired) {
        return false;
      }
      synchronized (commandQueue()) {
        // A failed transport may be observed by the same physical write that currently owns the
        // command stream. Terminal transaction failure must never wait for that write: its
        // operation lease (and the reader/watchdog retirement path) keeps the old incarnation
        // alive until the writer leaves this section, at which point the ordinary exact reset can
        // settle it. In particular, do not wait here while holding engineArbitrationLock.
        if (normalCommandSendInProgress) {
          return false;
        }
        if (readerStreamBinding != expectedBinding
            || expectedBinding.engineGameCommandStateRetired) {
          return false;
        }
        expectedBinding.engineGameCommandStateRetired = true;
        reset =
            resetGtpCommandStateForReaderRebindLocked(
                detail == null ? "engine-game participant transport failed" : detail);
      }
    }
    notifyGtpCommandStateReset(reset);
    return true;
  }

  /** Atomically marks only the expected reader/process incarnation unavailable. */
  boolean markUnavailableIfCurrentIncarnation(Object expectedIncarnation) {
    if (!(expectedIncarnation instanceof ReaderStreamBinding)) {
      return false;
    }
    ReaderStreamBinding binding = (ReaderStreamBinding) expectedIncarnation;
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding != binding) {
        return false;
      }
      binding.analysisOutputMutationLock.lock();
      try {
        if (readerStreamBinding != binding || binding.terminated) {
          return false;
        }
        isLoaded = false;
        return true;
      } finally {
        binding.analysisOutputMutationLock.unlock();
      }
    }
  }

  private void shutdownExecutor(ScheduledExecutorService service) {
    if (service == null) {
      return;
    }
    service.shutdown();
    try {
      if (!service.awaitTermination(1, TimeUnit.SECONDS)) {
        service.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      service.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private static void shutdownReaderExecutors(
      ScheduledExecutorService stdoutExecutor, ScheduledExecutorService stderrExecutor) {
    if (stdoutExecutor != null) stdoutExecutor.shutdown();
    if (stderrExecutor != null) stderrExecutor.shutdown();
  }

  private static boolean awaitReaderStart(CountDownLatch readerStartGate) {
    try {
      readerStartGate.await();
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static ReaderExecutorSnapshot requestReaderShutdown(ReaderStreamBinding binding) {
    return requestReaderShutdown(binding, false);
  }

  private static ReaderExecutorSnapshot requestReaderShutdown(
      ReaderStreamBinding binding, boolean normalExitRequested) {
    synchronized (binding.readerExecutorLock) {
      binding.readerShutdownRequested = true;
      binding.normalExitRequested |= normalExitRequested;
      boolean ownsTransportClose = !binding.transportCloseClaimed;
      binding.transportCloseClaimed = true;
      return new ReaderExecutorSnapshot(
          binding.stdoutExecutor, binding.stderrExecutor, ownsTransportClose);
    }
  }

  private static ForceReaderExecutorSnapshot claimReaderForceClose(
      ReaderStreamBinding binding) {
    synchronized (binding.readerExecutorLock) {
      if (binding.forceCloseClaimed) {
        return null;
      }
      binding.forceCloseClaimed = true;
      binding.readerShutdownRequested = true;
      binding.normalExitRequested = true;
      // A force claimant always owns one exact escalation. It owns shared physical-close
      // bookkeeping only when no graceful/terminal claimant got there first.
      boolean ownsPhysicalClose = !binding.transportCloseClaimed;
      binding.transportCloseClaimed = true;
      return new ForceReaderExecutorSnapshot(
          binding.stdoutExecutor, binding.stderrExecutor, ownsPhysicalClose);
    }
  }

  private static void shutdownReaderExecutors(ReaderExecutorSnapshot executors) {
    shutdownReaderExecutors(executors.stdoutExecutor, executors.stderrExecutor);
  }

  private static final class ReaderExecutorSnapshot {
    private final ScheduledExecutorService stdoutExecutor;
    private final ScheduledExecutorService stderrExecutor;
    private final boolean ownsTransportClose;

    private ReaderExecutorSnapshot(
        ScheduledExecutorService stdoutExecutor,
        ScheduledExecutorService stderrExecutor,
        boolean ownsTransportClose) {
      this.stdoutExecutor = stdoutExecutor;
      this.stderrExecutor = stderrExecutor;
      this.ownsTransportClose = ownsTransportClose;
    }
  }

  private static final class ForceReaderExecutorSnapshot {
    private final ScheduledExecutorService stdoutExecutor;
    private final ScheduledExecutorService stderrExecutor;
    private final boolean ownsPhysicalClose;

    private ForceReaderExecutorSnapshot(
        ScheduledExecutorService stdoutExecutor,
        ScheduledExecutorService stderrExecutor,
        boolean ownsPhysicalClose) {
      this.stdoutExecutor = stdoutExecutor;
      this.stderrExecutor = stderrExecutor;
      this.ownsPhysicalClose = ownsPhysicalClose;
    }
  }

  public void forceQuit() {
    ReaderStreamBinding binding = currentReaderStreamBinding();
    ReaderExecutorSnapshot executors = requestReaderShutdown(binding, true);
    EngineStopObservation stoppedObservation =
        markReaderBindingStoppedIfCurrent(binding, executors);
    notifyClaimedReaderBindingStopped(binding, executors, stoppedObservation);
    //		if(isScreen)
    //			sendCommand("name");
    if (stoppedObservation != null) {
      try {
        EngineManager.publishStoppedEngineIconIfCurrent(this, binding);
      } catch (RuntimeException | Error presentationFailure) {
        presentationFailure.printStackTrace();
      }
    }
    Throwable cleanupFailure =
        stoppedObservation == null ? null : stoppedObservation.preparationFailure;
    if (binding.javaSSH != null) {
      if (executors.ownsTransportClose) {
        cleanupFailure =
            runEngineCleanupStep(cleanupFailure, () -> binding.javaSSH.close());
      }
    } else if (binding.remoteTransport != null) {
      if (executors.ownsTransportClose) {
        cleanupFailure =
            runEngineCleanupStep(cleanupFailure, () -> binding.remoteTransport.close());
      }
    } else if (binding.process != null) {
      // A prior graceful close claimant may still leave a stubborn exact process alive. Force is
      // an escalation for that captured process identity and remains safe across a reader rebind.
      cleanupFailure =
          runEngineCleanupStep(cleanupFailure, () -> binding.process.destroyForcibly());
    }
    cleanupFailure =
        runEngineCleanupStep(cleanupFailure, () -> shutdownExecutor(executors.stdoutExecutor));
    cleanupFailure =
        runEngineCleanupStep(cleanupFailure, () -> shutdownExecutor(executors.stderrExecutor));
    cleanupFailure =
        runEngineCleanupStep(cleanupFailure, () -> clearOutputStreamIfCurrent(binding));
    rethrowEngineCleanupFailure(cleanupFailure);
  }

  private void finishClaimedFailedStart(
      ReaderStreamBinding binding,
      ReaderExecutorSnapshot executors,
      EngineStopObservation stoppedObservation) {
    notifyClaimedReaderBindingStopped(binding, executors, stoppedObservation);
    if (stoppedObservation != null) {
      try {
        EngineManager.publishStoppedEngineIconIfCurrent(this, binding);
      } catch (RuntimeException | Error presentationFailure) {
        presentationFailure.printStackTrace();
      }
    }
    Throwable cleanupFailure =
        stoppedObservation == null ? null : stoppedObservation.preparationFailure;
    if (executors.ownsTransportClose) {
      if (binding.javaSSH != null) {
        cleanupFailure =
            runEngineCleanupStep(cleanupFailure, binding.javaSSH::close);
      } else if (binding.remoteTransport != null) {
        cleanupFailure =
            runEngineCleanupStep(cleanupFailure, binding.remoteTransport::close);
      } else if (binding.process != null) {
        cleanupFailure =
            runEngineCleanupStep(cleanupFailure, binding.process::destroyForcibly);
      }
    }
    cleanupFailure =
        runEngineCleanupStep(cleanupFailure, () -> shutdownExecutor(executors.stdoutExecutor));
    cleanupFailure =
        runEngineCleanupStep(cleanupFailure, () -> shutdownExecutor(executors.stderrExecutor));
    cleanupFailure =
        runEngineCleanupStep(cleanupFailure, () -> clearOutputStreamIfCurrent(binding));
    rethrowEngineCleanupFailure(cleanupFailure);
  }

  private void clearOutputStreamIfCurrent(ReaderStreamBinding binding) {
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding == binding) outputStream = null;
    }
  }

  /** Initializes the input and output streams */
  public void initializeStreams() {
    initializeStreams(
        process.getInputStream(), process.getOutputStream(), process.getErrorStream());
  }

  private void initializeStreams(InputStream stdout, OutputStream stdin, InputStream stderr) {
    BufferedReader nextInputStream = new BufferedReader(new InputStreamReader(stdout));
    BufferedOutputStream nextOutputStream = createCommandOutputStream(stdin);
    BufferedReader nextErrorStream = new BufferedReader(new InputStreamReader(stderr));
    ExclusiveGtpSession retiredTrackingSession = null;
    TrackingHandoffFailureNotification retiredHandoffFailure = null;
    TrackingDispositionNotification dispositionNotification = null;
    GtpCommandStateReset rebindCommandStateReset = null;
    boolean interrupted = false;
    boolean ownsRebindGateAfterTrackingCleanup = false;
    boolean rebindCommandStateCutover = false;
    synchronized (engineArbitrationLock()) {
      if (engineIncarnationLeaseOwners.containsKey(Thread.currentThread())) {
        throw new IllegalStateException(
            "Cannot replace engine streams from an incarnation-lease owner");
      }
      while ((!ownsRebindGateAfterTrackingCleanup && readerStreamRebindInProgress)
          || readerTerminalCleanupInProgress
          || isUpdateEngineStartCompletionClaimedLocked()
          || engineIncarnationLeaseDepth > 0
          || (readerStreamBinding != null && readerStreamBinding.linesInProgress > 0)
          || (readerStreamBinding != null
              && readerStreamBinding.startupPostActionsInProgress > 0)
          || (readerStreamBinding != null
              && readerStreamBinding.runtimeUiPresentationsInProgress > 0)
          || isFailedTrackingStreamCleanupInProgress()
          || isTrackingHandoffActivationCallbackInProgress()) {
        if (!ownsRebindGateAfterTrackingCleanup
            && (isFailedTrackingStreamCleanupInProgress()
                || isTrackingHandoffActivationCallbackInProgress())) {
          synchronized (commandQueue()) {
            readerStreamRebindInProgress = true;
            ownsRebindGateAfterTrackingCleanup = true;
            if (isTrackingHandoffActivationCallbackInProgress()) {
              claimTrackingHandoffFailureLocked(
                  trackingHandoffGate, TrackingHandoffFailure.TRACKING_FAILED);
            }
          }
        }
        try {
          engineArbitrationLock().wait();
        } catch (InterruptedException waitInterrupted) {
          interrupted = true;
        }
      }
      if (readerStreamBinding != null) {
        cancelLeela0110PonderForReaderBinding(readerStreamBinding);
        synchronized (commandQueue()) {
          readerStreamRebindInProgress = true;
          while (normalCommandSendInProgress) {
            try {
              commandQueue().wait();
            } catch (InterruptedException waitInterrupted) {
              interrupted = true;
            }
          }
          retireAnalysisOutputBindingLocked(readerStreamBinding);
          if (exclusiveGtpSession != null
              && exclusiveGtpSession.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY) {
            if (exclusiveGtpSession.closing) {
              retiredTrackingSession = exclusiveGtpSession;
              rebindCommandStateReset =
                  resetGtpCommandStateForReaderRebindLocked(
                      "tracking stream retired after successful close boundary");
            } else {
              TrackingStreamCleanup cleanup =
                  claimTrackingStreamCleanup(
                      exclusiveGtpSession,
                      TrackingStreamLeaseFailure.TRANSPORT_CLOSED,
                      "tracking stream retired before reader rebind",
                      true,
                      false);
              if (cleanup != null) {
                retiredTrackingSession = cleanup.session;
                rebindCommandStateReset = cleanup.commandStateReset;
                dispositionNotification = cleanup.dispositionNotification;
              } else {
                retiredTrackingSession = exclusiveGtpSession;
                recordTrackingStreamLeaseFailure(
                    retiredTrackingSession, TrackingStreamLeaseFailure.TRANSPORT_CLOSED);
                retiredTrackingSession.releaseStopFailed = true;
                retiredTrackingSession.closing = true;
                rebindCommandStateReset =
                    resetGtpCommandStateForReaderRebindLocked(
                        "stale tracking stream retired before reader rebind");
              }
            }
            if (dispositionNotification == null) {
              dispositionNotification =
                  advanceTrackingReleaseDispositionLocked(
                      retiredTrackingSession, TrackingReleaseDisposition.CLEARED);
            }
          } else if (!readerStreamBinding.engineGameCommandStateRetired) {
            rebindCommandStateReset =
                resetGtpCommandStateForReaderRebindLocked(
                    "command state retired before reader rebind");
          }
          if (trackingHandoffGate != null) {
            TrackingHandoffFailureSettlement handoffSettlement =
                claimTrackingHandoffFailureLocked(
                    trackingHandoffGate, TrackingHandoffFailure.TRACKING_FAILED);
            retiredHandoffFailure = handoffSettlement.notification;
          }
          rebindCommandStateCutover = rebindCommandStateReset != null;
        }
      }
      if (!rebindCommandStateCutover) {
        inputStream = nextInputStream;
        outputStream = nextOutputStream;
        errorStream = nextErrorStream;
        loadSgfResponseQuarantined = false;
        ReaderStreamBinding nextBinding =
            new ReaderStreamBinding(
                nextInputStream,
                nextErrorStream,
                nextOutputStream,
                process,
                useRemoteCompute ? remoteTransport : null,
                useJavaSSH ? javaSSH : null,
                processIncarnationIds.incrementAndGet(),
                startupPrimaryEngineGeneration,
                isDeferredEngineGameRecoveryStartup(),
                analysisOutputRecoveryTokenContext.get());
        nextBinding.rawOutput = stdin;
        nextBinding.suppressGlobalEnginePresentation =
            nextBinding.suppressGlobalEnginePresentation
                || suppressGlobalEnginePresentationUntilOwned;
        beforeReaderBindingPublicationForTest();
        synchronized (leela0110PonderStateLock) {
          if (leela0110PonderingBinding == readerStreamBinding) {
            clearLeela0110PonderStateLocked();
          }
          readerStreamBinding = nextBinding;
          if (suppressGlobalEnginePresentationUntilOwned) {
            nextBinding.suppressGlobalEnginePresentation = true;
          }
        }
        recordUpdateEngineStartBindingLocked(readerStreamBinding);
        synchronized (commandQueue()) {
          publishRestartBootstrapReceiptLocked(readerStreamBinding, nextOutputStream);
        }
        if (ownsRebindGateAfterTrackingCleanup || readerStreamRebindInProgress) {
          readerStreamRebindInProgress = false;
          engineArbitrationLock().notifyAll();
        }
      }
    }
    if (rebindCommandStateCutover) {
      if (retiredTrackingSession != null) {
        cancelExclusiveGtpInitialStopTimeout(retiredTrackingSession);
        cancelExclusiveGtpReleaseStopTimeout(retiredTrackingSession);
      }
      notifyTrackingDisposition(dispositionNotification);
      try {
        notifyGtpCommandStateReset(rebindCommandStateReset);
      } finally {
        Runnable onClosed = null;
        synchronized (engineArbitrationLock()) {
          if (retiredTrackingSession != null) {
            if (exclusiveGtpSession == retiredTrackingSession) {
              exclusiveGtpSession = null;
            }
            if (!retiredTrackingSession.closedCallbackRun) {
              retiredTrackingSession.closedCallbackRun = true;
              onClosed = retiredTrackingSession.onClosed;
            }
          }
          inputStream = nextInputStream;
          outputStream = nextOutputStream;
          errorStream = nextErrorStream;
          loadSgfResponseQuarantined = false;
          ReaderStreamBinding nextBinding =
              new ReaderStreamBinding(
                  nextInputStream,
                  nextErrorStream,
                  nextOutputStream,
                  process,
                  useRemoteCompute ? remoteTransport : null,
                  useJavaSSH ? javaSSH : null,
                  processIncarnationIds.incrementAndGet(),
                  startupPrimaryEngineGeneration,
                  isDeferredEngineGameRecoveryStartup(),
                  analysisOutputRecoveryTokenContext.get());
          nextBinding.rawOutput = stdin;
          nextBinding.suppressGlobalEnginePresentation =
              nextBinding.suppressGlobalEnginePresentation
                  || suppressGlobalEnginePresentationUntilOwned;
          beforeReaderBindingPublicationForTest();
          synchronized (leela0110PonderStateLock) {
            if (leela0110PonderingBinding == readerStreamBinding) {
              clearLeela0110PonderStateLocked();
            }
            readerStreamBinding = nextBinding;
            if (suppressGlobalEnginePresentationUntilOwned) {
              nextBinding.suppressGlobalEnginePresentation = true;
            }
          }
          recordUpdateEngineStartBindingLocked(readerStreamBinding);
          synchronized (commandQueue()) {
            publishRestartBootstrapReceiptLocked(readerStreamBinding, nextOutputStream);
          }
          readerStreamRebindInProgress = false;
          engineArbitrationLock().notifyAll();
        }
        notifyTrackingHandoffFailure(retiredHandoffFailure);
        try {
          trySendCommandFromQueue();
        } catch (RuntimeException ex) {
          ex.printStackTrace();
        }
        runTrackingCallback(onClosed);
      }
    } else if (ownsRebindGateAfterTrackingCleanup) {
      try {
        trySendCommandFromQueue();
      } catch (RuntimeException ex) {
        ex.printStackTrace();
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    noteEngineStarted();
  }

  private void noteEngineStarted() {
    String id = EngineObservation.identityFor(this);
    if (id == null) {
      id = EngineObservation.ensureStarted(this, "MAIN_BOARD");
    }
    loggingEngineId = id;
  }

  private void noteEngineStopped() {
    if (EngineObservation.engineDiagnosticsEnabled()) {
      EngineObservation.recordRecentStderr(
          loggingEngineId, snapshotRecentLines(recentStderrLines));
    }
    EngineObservation.ensureStopped(this, "stopped");
    loggingEngineId = null;
  }

  private void noteEngineFailed(String reason) {
    loggingEngineId = EngineObservation.mintIdentity(this);
    if (EngineObservation.engineDiagnosticsEnabled()) {
      EngineObservation.recordRecentStderr(
          loggingEngineId, snapshotRecentLines(recentStderrLines));
    }
    EngineObservation.recordFailed(loggingEngineId, reason);
    EngineObservation.discardIdentity(this);
    loggingEngineId = null;
  }

  private void markEngineLoaded() {
    boolean already = isLoaded;
    isLoaded = true;
    if (!already) {
      EngineObservation.recordReady(loggingEngineId);
    }
  }

  private boolean isFailedTrackingStreamCleanupInProgress() {
    return exclusiveGtpSession != null
        && exclusiveGtpSession.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY
        && exclusiveGtpSession.closing
        && exclusiveGtpSession.releaseStopFailed;
  }

  private boolean isTrackingHandoffActivationCallbackInProgress() {
    return trackingHandoffGate != null && trackingHandoffGate.activationCallbackInProgress;
  }

  private boolean isUpdateEngineStartCompletionClaimedLocked() {
    return activeUpdateEngineStartAttempt != null
        && activeUpdateEngineStartAttempt.completionClaim != null;
  }

  private ReaderStreamBinding currentReaderStreamBinding() {
    ReaderStreamBinding binding = readerStreamBinding;
    if (binding != null) {
      return binding;
    }
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding == null) {
        ReaderStreamBinding nextBinding =
            new ReaderStreamBinding(
                inputStream,
                errorStream,
                outputStream,
                process,
                useRemoteCompute ? remoteTransport : null,
                useJavaSSH ? javaSSH : null,
                processIncarnationIds.incrementAndGet(),
                startupPrimaryEngineGeneration,
                isDeferredEngineGameRecoveryStartup(),
                analysisOutputRecoveryTokenContext.get());
        nextBinding.suppressGlobalEnginePresentation =
            nextBinding.suppressGlobalEnginePresentation
                || suppressGlobalEnginePresentationUntilOwned;
        beforeReaderBindingPublicationForTest();
        synchronized (leela0110PonderStateLock) {
          readerStreamBinding = nextBinding;
          if (suppressGlobalEnginePresentationUntilOwned) {
            nextBinding.suppressGlobalEnginePresentation = true;
          }
        }
        recordUpdateEngineStartBindingLocked(readerStreamBinding);
      }
      return readerStreamBinding;
    }
  }

  private void publishRestartBootstrapReceiptLocked(
      ReaderStreamBinding binding, BufferedOutputStream bindingOutput) {
    if (!exclusiveGtpLifecycleTransition
        || !exclusiveGtpLifecycleQueueGate
        || exclusiveGtpLifecycleOwner == null) {
      restartBootstrapReceipt = null;
      return;
    }
    RestartBootstrapReceipt receipt =
        new RestartBootstrapReceipt(
            this,
            exclusiveGtpLifecycleOwner,
            restartBootstrapAttemptIds.incrementAndGet(),
            binding,
            binding.incarnation,
            bindingOutput);
    restartBootstrapReceipt = receipt;
    binding.restartBootstrapReceipt = receipt;
  }

  private RestartBootstrapReceipt currentRestartBootstrapReceipt() {
    synchronized (engineArbitrationLock()) {
      synchronized (commandQueue()) {
        RestartBootstrapReceipt receipt = restartBootstrapReceipt;
        return isCurrentRestartBootstrapReceiptLocked(receipt) ? receipt : null;
      }
    }
  }

  Runnable withCurrentRestartBootstrapReceipt(Runnable action) {
    RestartBootstrapReceipt receipt = currentRestartBootstrapReceipt();
    return () -> runWithRestartBootstrapReceipt(receipt, action);
  }

  Runnable currentRestartBootstrapFailureAction(String detail) {
    RestartBootstrapReceipt receipt = currentRestartBootstrapReceipt();
    return () -> failRestartBootstrapReceipt(receipt, detail);
  }

  Runnable currentRestartBoardSynchronizationFailureAction(String detail) {
    RestartBootstrapReceipt receipt = currentRestartBootstrapReceipt();
    boolean preserveUnrestoredState = engineStateUnrestored;
    return () -> failRestartBootstrapReceipt(receipt, detail, preserveUnrestoredState);
  }

  private void runWithRestartBootstrapReceipt(RestartBootstrapReceipt receipt, Runnable action) {
    RestartBootstrapReceipt previous = restartBootstrapReceiptContext.get();
    if (receipt == null) {
      restartBootstrapReceiptContext.remove();
    } else {
      restartBootstrapReceiptContext.set(receipt);
    }
    try {
      action.run();
    } finally {
      if (previous == null) {
        restartBootstrapReceiptContext.remove();
      } else {
        restartBootstrapReceiptContext.set(previous);
      }
    }
  }

  private boolean isCurrentRestartBootstrapReceiptLocked(RestartBootstrapReceipt receipt) {
    return receipt != null
        && receipt.engine == this
        && restartBootstrapReceipt == receipt
        && restartBootstrapAttemptIds.get() == receipt.restartAttempt
        && exclusiveGtpLifecycleTransition
        && exclusiveGtpLifecycleQueueGate
        && exclusiveGtpLifecycleOwner == receipt.lifecycleOwner
        && readerStreamBinding == receipt.binding
        && !receipt.binding.terminated
        && receipt.incarnation == receipt.binding.incarnation
        && outputStream == receipt.output;
  }

  private void failRestartBootstrapReceipt(RestartBootstrapReceipt receipt, String detail) {
    failRestartBootstrapReceipt(receipt, detail, true);
  }

  private void failRestartBootstrapReceipt(
      RestartBootstrapReceipt receipt, String detail, boolean quarantineEngineState) {
    GtpCommandStateReset reset = null;
    boolean releaseLifecycleTransition = false;
    synchronized (engineArbitrationLock()) {
      synchronized (commandQueue()) {
        if (isCurrentRestartBootstrapReceiptLocked(receipt)) {
          // Availability belongs to this exact receipt/binding. Publish the failed runtime as
          // unavailable here so callers without an EngineSwitchTransaction do not need an
          // unfenced object-level write, while a stale receipt cannot clobber a rebound runtime.
          isLoaded = false;
          if (quarantineEngineState) {
            engineStateUnrestored = true;
          }
          restartBootstrapReceipt = null;
          receipt.binding.restartBootstrapReceipt = null;
          reset = resetGtpCommandStateLocked(detail);
          releaseLifecycleTransition = true;
        }
      }
    }
    if (releaseLifecycleTransition) {
      endExclusiveGtpLifecycleTransition(receipt.lifecycleOwner);
    }
    if (reset != null) {
      rememberRecentLine(
          recentStderrLines,
          (quarantineEngineState
                  ? "Restart bootstrap failed: "
                  : "Restart board synchronization failed: ")
              + detail);
      notifyGtpCommandStateReset(reset);
    }
  }

  public long trackingStreamIncarnation() {
    return currentReaderStreamBinding().incarnation;
  }

  public boolean restorePonderAfterTracking(TrackingStreamLeaseReceipt receipt) {
    boolean claimed = false;
    boolean restored = false;
    try {
      synchronized (engineArbitrationLock()) {
        synchronized (commandQueue()) {
          if (receipt == null
              || receipt.engine() != this
              || !receipt.wasPondering()
              || Lizzie.leelaz != this
              || !isLoaded()
              || !isStarted()
              || currentReaderStreamBinding().incarnation != receipt.engineIncarnation()
              || exclusiveGtpSession != null
              || trackingHandoffGate != null
              || exclusiveGtpLifecycleTransition
              || foregroundRestoreInProgress
              || normalCommandSendInProgress
              || !commandQueue().isEmpty()
              || !foregroundRestoreCommandQueue().isEmpty()) {
            return false;
          }
          claimed = true;
          int commandNumberBeforePonder = cmdNumber;
          ponder();
          if (cmdNumber > commandNumberBeforePonder) {
            settleTrackingPonderResponseWatermark();
          }
          restored = true;
        }
      }
    } catch (Throwable ignored) {
      // A failed ponder handback cannot own recovery or strand the ordinary writer.
    } finally {
      if (claimed) {
        trySendCommandFromQueue();
      }
    }
    return restored;
  }

  private boolean isCurrentReaderStreamBinding(ReaderStreamBinding binding) {
    ReaderStreamBinding current = readerStreamBinding;
    return current == binding && !binding.terminated;
  }

  private boolean beginReaderLine(ReaderStreamBinding binding) {
    synchronized (engineArbitrationLock()) {
      if (!isCurrentReaderStreamBinding(binding)) {
        return false;
      }
      binding.linesInProgress++;
      return true;
    }
  }

  private void endReaderLine(ReaderStreamBinding binding) {
    boolean finishTerminalCleanup = false;
    synchronized (engineArbitrationLock()) {
      binding.linesInProgress--;
      if (binding.linesInProgress == 0
          && binding.startupPostActionsInProgress == 0
          && binding.terminated
          && !binding.terminalCleanupStarted) {
        binding.terminalCleanupStarted = true;
        readerTerminalCleanupInProgress = true;
        finishTerminalCleanup = true;
      }
      engineArbitrationLock().notifyAll();
    }
    if (finishTerminalCleanup) {
      finishReaderTerminalCleanup(binding);
    }
  }

  /**
   * Failure lineage for position-dependent output on one reader incarnation. Pending state
   * commands leave the lineage usable so streaming analysis can start immediately; a known failed
   * state command poisons every dependent owner until a full state rebuild starts a fresh lineage.
   */
  private static final class AnalysisStateLineage {
    private final AtomicBoolean failed = new AtomicBoolean();

    private boolean fail() {
      return failed.compareAndSet(false, true);
    }

    private boolean isFailed() {
      return failed.get();
    }
  }

  private static final class ReaderStreamBinding {
    private final BufferedReader stdout;
    private final BufferedReader stderr;
    private volatile OutputStream rawOutput;
    private volatile BufferedOutputStream output;
    private final Process process;
    private final EngineTransport remoteTransport;
    private final SSHController javaSSH;
    private final long incarnation;
    private volatile Object analysisOutputRecoveryToken;
    private long startupPrimaryEngineGeneration;
    /** Bootstrap-only quarantine; released solely by deferred engine-game recovery settlement. */
    private volatile boolean deferredEngineGameRecoveryPresentationSuppressed;
    /** Analysis-output ownership quarantine; released solely by a fresh physical owner. */
    private volatile boolean suppressGlobalEnginePresentation;
    private final Object readerExecutorLock = new Object();
    private ScheduledExecutorService stdoutExecutor;
    private ScheduledExecutorService stderrExecutor;
    private volatile boolean readerShutdownRequested;
    private boolean transportCloseClaimed;
    private boolean forceCloseClaimed;
    private volatile boolean normalExitRequested;
    private RestartBootstrapReceipt restartBootstrapReceipt;
    /** Serializes ownership replacement with one short parser-side state publication. */
    private final ReentrantLock analysisOutputMutationLock = new ReentrantLock();
    private final AtomicReference<AnalysisOutputOwnership> analysisOutputOwnership =
        new AtomicReference<>();
    private volatile AnalysisStateLineage analysisStateLineage = new AnalysisStateLineage();
    private int linesInProgress;
    private int startupPostActionsInProgress;
    private final List<StartupCommandDelivery> startupCommandDeliveries = new ArrayList<>();
    private int runtimeUiPresentationsInProgress;
    private Throwable terminalFailure;
    private boolean terminalCleanupStarted;
    /** The dead carrier's command leases were settled before deferred engine-game recovery. */
    private boolean engineGameCommandStateRetired;
    private volatile boolean terminated;

    private ReaderStreamBinding(
        BufferedReader stdout,
        BufferedReader stderr,
        BufferedOutputStream output,
        Process process,
        EngineTransport remoteTransport,
        SSHController javaSSH,
        long incarnation,
        long startupPrimaryEngineGeneration) {
      this(
          stdout,
          stderr,
          output,
          process,
          remoteTransport,
          javaSSH,
          incarnation,
          startupPrimaryEngineGeneration,
          false,
          null);
    }

    private ReaderStreamBinding(
        BufferedReader stdout,
        BufferedReader stderr,
        BufferedOutputStream output,
        Process process,
        EngineTransport remoteTransport,
        SSHController javaSSH,
        long incarnation,
        long startupPrimaryEngineGeneration,
        boolean deferredEngineGameRecoveryPresentationSuppressed,
        Object analysisOutputRecoveryToken) {
      this.stdout = stdout;
      this.stderr = stderr;
      this.rawOutput = output;
      this.output = output;
      this.process = process;
      this.remoteTransport = remoteTransport;
      this.javaSSH = javaSSH;
      this.incarnation = incarnation;
      this.startupPrimaryEngineGeneration = startupPrimaryEngineGeneration;
      this.deferredEngineGameRecoveryPresentationSuppressed =
          deferredEngineGameRecoveryPresentationSuppressed;
      this.analysisOutputRecoveryToken = analysisOutputRecoveryToken;
    }
  }

  /**
   * Retires an analysis-stream owner at the same linearization boundary used by parser commits
   * and physical ownership replacement. Callers already own the endpoint arbitration lock.
   */
  private void retireAnalysisOutputBindingLocked(ReaderStreamBinding binding) {
    if (binding == null) {
      return;
    }
    binding.analysisOutputMutationLock.lock();
    try {
      binding.terminated = true;
    } finally {
      binding.analysisOutputMutationLock.unlock();
    }
  }

  /**
   * Persists recovery quarantine across a same-object reader replacement. It is cleared only by a
   * physically written, exact analysis command after the manager's recovery/game barrier admits
   * that owner.
   */
  private volatile boolean suppressGlobalEnginePresentationUntilOwned;

  private enum AnalysisOutputRouteKind {
    EXACT_CURRENT,
    EXACT_RETIRED,
    ORDINARY_CURRENT,
    GENMOVE_CURRENT
  }

  private static final class AnalysisOutputOwnership {
    private final EngineManager.EngineGamePrimaryContext exactContext;
    private final boolean ordinary;
    private final Object recoveryToken;
    private final long generation;
    private final AnalysisStateLineage analysisStateLineage;

    private AnalysisOutputOwnership(
        EngineManager.EngineGamePrimaryContext exactContext,
        boolean ordinary,
        Object recoveryToken,
        long generation,
        AnalysisStateLineage analysisStateLineage) {
      this.exactContext = exactContext;
      this.ordinary = ordinary;
      this.recoveryToken = recoveryToken;
      this.generation = generation;
      this.analysisStateLineage = analysisStateLineage;
    }

    private static AnalysisOutputOwnership exact(
        EngineManager.EngineGamePrimaryContext context,
        long generation,
        AnalysisStateLineage analysisStateLineage) {
      return new AnalysisOutputOwnership(
          context, false, null, generation, analysisStateLineage);
    }

    private static AnalysisOutputOwnership ordinary(
        long generation, AnalysisStateLineage analysisStateLineage) {
      return new AnalysisOutputOwnership(
          null, true, null, generation, analysisStateLineage);
    }

    private static AnalysisOutputOwnership recoveryTombstone(
        Object recoveryToken, long generation, AnalysisStateLineage analysisStateLineage) {
      return new AnalysisOutputOwnership(
          null, false, recoveryToken, generation, analysisStateLineage);
    }

    private boolean isExact() {
      return exactContext != null;
    }

    private boolean isOrdinary() {
      return ordinary;
    }

    private boolean isRecoveryTombstone(Object expectedRecoveryToken) {
      return expectedRecoveryToken != null && recoveryToken == expectedRecoveryToken;
    }

    private boolean hasFailedAnalysisStateLineage() {
      return analysisStateLineage != null && analysisStateLineage.isFailed();
    }
  }

  private final class AnalysisOutputOwnershipPublication {
    private final ReaderStreamBinding binding;
    private final AnalysisOutputOwnership previousOwnership;
    private final AnalysisOutputOwnership publishedOwnership;
    private final boolean previousBindingSuppression;
    private final boolean previousPersistentSuppression;

    private AnalysisOutputOwnershipPublication(
        ReaderStreamBinding binding,
        AnalysisOutputOwnership previousOwnership,
        AnalysisOutputOwnership publishedOwnership,
        boolean previousBindingSuppression,
        boolean previousPersistentSuppression) {
      this.binding = binding;
      this.previousOwnership = previousOwnership;
      this.publishedOwnership = publishedOwnership;
      this.previousBindingSuppression = previousBindingSuppression;
      this.previousPersistentSuppression = previousPersistentSuppression;
    }

    private void outputWriteFailed(boolean partialWrite) {
      binding.analysisOutputMutationLock.lock();
      try {
        if (partialWrite) {
          // Bytes from the new command may be visible while its terminal response is unknowable.
          // Keep this incarnation quarantined until recovery/rebind establishes a new exact owner.
          binding.suppressGlobalEnginePresentation = true;
          suppressGlobalEnginePresentationUntilOwned = true;
          return;
        }
        if (binding.analysisOutputOwnership.compareAndSet(
            publishedOwnership, previousOwnership)) {
          binding.suppressGlobalEnginePresentation = previousBindingSuppression;
          suppressGlobalEnginePresentationUntilOwned = previousPersistentSuppression;
        }
      } finally {
        binding.analysisOutputMutationLock.unlock();
      }
    }
  }

  private static final class AnalysisOutputAdmissionFailure extends IllegalStateException {
    private AnalysisOutputAdmissionFailure(String message) {
      super(message);
    }
  }

  private static final class AnalysisOutputRoute {
    private final AnalysisOutputRouteKind kind;
    private final EngineManager.EngineGamePrimaryContext activeExactContext;
    private final ReaderStreamBinding binding;
    private final Object ownerToken;
    private final EngineManager.EngineGameMoveResponseContext genmoveContext;

    private AnalysisOutputRoute(
        AnalysisOutputRouteKind kind,
        EngineManager.EngineGamePrimaryContext activeExactContext) {
      this(kind, activeExactContext, null, null, null);
    }

    private AnalysisOutputRoute(
        AnalysisOutputRouteKind kind,
        EngineManager.EngineGamePrimaryContext activeExactContext,
        ReaderStreamBinding binding,
        Object ownerToken,
        EngineManager.EngineGameMoveResponseContext genmoveContext) {
      this.kind = kind;
      this.activeExactContext = activeExactContext;
      this.binding = binding;
      this.ownerToken = ownerToken;
      this.genmoveContext = genmoveContext;
    }

    private boolean acceptsExactEngineGameOutput() {
      return kind == AnalysisOutputRouteKind.EXACT_CURRENT && activeExactContext != null;
    }

    private boolean acceptsOrdinaryOutput() {
      return kind == AnalysisOutputRouteKind.ORDINARY_CURRENT;
    }

    private boolean dropsOutput() {
      return kind == AnalysisOutputRouteKind.EXACT_RETIRED;
    }

    private boolean acceptsInfoLine() {
      return kind == AnalysisOutputRouteKind.ORDINARY_CURRENT
          || kind == AnalysisOutputRouteKind.GENMOVE_CURRENT
          || (kind == AnalysisOutputRouteKind.EXACT_CURRENT && activeExactContext != null);
    }

    private boolean hasSameOwner(AnalysisOutputRoute other) {
      return other != null
          && kind == other.kind
          && binding == other.binding
          && ownerToken == other.ownerToken;
    }
  }

  private static final class AnalysisSummaryBatch {
    private final AnalysisOutputRoute route;
    private final long analysisInfoEpoch;
    private final Board board;
    private final BoardHistoryNode targetNode;
    private final List<MoveData> moves;

    private AnalysisSummaryBatch(
        AnalysisOutputRoute route,
        long analysisInfoEpoch,
        Board board,
        BoardHistoryNode targetNode,
        List<MoveData> moves) {
      this.route = route;
      this.analysisInfoEpoch = analysisInfoEpoch;
      this.board = board;
      this.targetNode = targetNode;
      this.moves = moves;
    }
  }

  static final class EngineRuntimeUiLease implements AutoCloseable {
    private final Leelaz engine;
    private final ReaderStreamBinding binding;
    private boolean closed;

    private EngineRuntimeUiLease(Leelaz engine, ReaderStreamBinding binding) {
      this.engine = engine;
      this.binding = binding;
    }

    @Override
    public void close() {
      synchronized (engine.engineArbitrationLock()) {
        if (closed) {
          return;
        }
        closed = true;
        if (binding.runtimeUiPresentationsInProgress > 0) {
          binding.runtimeUiPresentationsInProgress--;
        }
        engine.engineArbitrationLock().notifyAll();
      }
    }
  }

  private static final class RestartBootstrapReceipt {
    private final Leelaz engine;
    private final Object lifecycleOwner;
    private final long restartAttempt;
    private final ReaderStreamBinding binding;
    private final long incarnation;
    private final BufferedOutputStream output;

    private RestartBootstrapReceipt(
        Leelaz engine,
        Object lifecycleOwner,
        long restartAttempt,
        ReaderStreamBinding binding,
        long incarnation,
        BufferedOutputStream output) {
      this.engine = engine;
      this.lifecycleOwner = lifecycleOwner;
      this.restartAttempt = restartAttempt;
      this.binding = binding;
      this.incarnation = incarnation;
      this.output = output;
    }
  }

  public List<MoveData> parseInfoSai(String line) {
    List<MoveData> bestMoves = new ArrayList<>();
    String[] variations = line.split(" info ");
    for (String var : variations) {
      if (!var.trim().isEmpty()) {
        bestMoves.add(MoveData.fromInfoSai(var, isSayuri));
      }
    }
    return bestMoves;
  }

  public List<MoveData> parseInfo(String line) {
    List<MoveData> bestMoves = new ArrayList<>();
    String[] variations = line.split(" info ");
    //	int k = (Lizzie.config.limitMaxSuggestion > 0&&!Lizzie.config.showNoSuggCircle ?
    // Lizzie.config.limitMaxSuggestion : 361);
    for (String var : variations) {
      if (!var.trim().isEmpty()) {
        bestMoves.add(MoveData.fromInfo(var));
        //	k = k - 1;
        //	if (k < 1)
        //		break;
      }
    }
    return bestMoves;
  }

  public List<MoveData> parseInfoKatago(String line) {
    int ownershipIndex = line.indexOf("ownership");
    if (ownershipIndex >= 0) {
      line = line.substring(0, ownershipIndex);
    }
    List<MoveData> bestMoves = new ArrayList<>();
    String[] variations = line.split(" info ");
    // int k = (Lizzie.config.limitMaxSuggestion > 0&&!Lizzie.config.showNoSuggCircle ?
    // Lizzie.config.limitMaxSuggestion : 361);
    for (String var : variations) {
      if (!var.trim().isEmpty()) {
        bestMoves.add(MoveData.fromInfoKatago(var));
        //		k = k - 1;
        //		if (k < 1)
        //			break;
      }
    }
    return bestMoves;
  }

  private static final class ParsedAnalysisInfo {
    private final List<MoveData> moves;
    private final int totalPlayouts;
    private final List<Double> estimateArray;
    private final boolean kata;

    private ParsedAnalysisInfo(
        List<MoveData> moves,
        int totalPlayouts,
        List<Double> estimateArray,
        boolean kata) {
      this.moves = List.copyOf(moves);
      this.totalPlayouts = totalPlayouts;
      this.estimateArray = estimateArray == null ? null : List.copyOf(estimateArray);
      this.kata = kata;
    }
  }

  private static final class AnalysisInfoSnapshot {
    private final List<MoveData> moves;
    private final int totalPlayouts;
    private final long epoch;
    private final AnalysisInfoTarget target;

    private AnalysisInfoSnapshot(
        List<MoveData> moves,
        int totalPlayouts,
        long epoch,
        AnalysisInfoTarget target) {
      this.moves = moves;
      this.totalPlayouts = totalPlayouts;
      this.epoch = epoch;
      this.target = target;
    }
  }

  private static final class AnalysisInfoTarget {
    private final Board board;
    private final long boardRevision;
    private final BoardHistoryNode displayNode;

    private AnalysisInfoTarget(
        Board board, long boardRevision, BoardHistoryNode displayNode) {
      this.board = board;
      this.boardRevision = boardRevision;
      this.displayNode = displayNode;
    }
  }

  private AnalysisInfoTarget captureAnalysisInfoTarget() {
    Board board = Lizzie.board;
    BoardHistoryNode displayNode =
        Lizzie.frame == null || board == null ? null : Lizzie.frame.getDisplayNode();
    return new AnalysisInfoTarget(
        board, board == null ? -1L : board.getContextRevision(), displayNode);
  }

  private boolean isCurrentAnalysisInfoTarget(AnalysisInfoTarget expected) {
    return expected != null
        && expected.board != null
        && expected.board == Lizzie.board
        && expected.board.getContextRevision() == expected.boardRevision
        && expected.displayNode != null
        && Lizzie.frame != null
        && Lizzie.frame.getDisplayNode() == expected.displayNode;
  }

  private ParsedAnalysisInfo parseAnalysisInfoPayload(String payload) {
    boolean kata = isKatago;
    List<MoveData> parsedMoves;
    List<Double> estimateArray = null;
    if (kata) {
      parsedMoves = parseInfoKatago(payload);
      estimateArray = parseKataOwnershipEstimate(payload);
    } else if (isSai) {
      parsedMoves = parseInfoSai(payload);
    } else {
      parsedMoves = parseInfo(payload);
    }
    return new ParsedAnalysisInfo(
        parsedMoves, MoveData.getPlayouts(parsedMoves), estimateArray, kata);
  }

  private List<Double> parseKataOwnershipEstimate(String payload) {
    if (!Lizzie.config.showKataGoEstimate) {
      return null;
    }
    List<Double> estimateArray = new ArrayList<>();
    int ownershipIndex = payload.indexOf("ownership");
    if (ownershipIndex < 0) {
      return estimateArray;
    }
    String ownership = payload.substring(ownershipIndex + "ownership".length()).trim();
    if (ownership.isEmpty()) {
      return estimateArray;
    }
    for (String value : ownership.split(" ")) {
      if (!value.isEmpty()) {
        estimateArray.add(Double.parseDouble(value));
      }
    }
    return estimateArray;
  }

  /**
   * Publishes an immutable accepted payload through a mutable BoardData copy. BoardData sorts and
   * may limit its argument in place, so it must never receive the live parser snapshot.
   * Caller holds {@link #analysisInfoMutationLock} and the exact analysis-output admission.
   */
  private void publishAnalysisInfoToDisplay(
      ParsedAnalysisInfo parsed, AnalysisInfoTarget target) {
    publishAnalysisDisplayNonFatal(() -> publishAnalysisInfoToDisplayUnsafe(parsed, target));
  }

  private void publishAnalysisDisplayNonFatal(Runnable publication) {
    try {
      beforeAnalysisDisplayPublicationForTest();
      publication.run();
    } catch (RuntimeException displayFailure) {
      rememberRecentLine(
          recentStderrLines,
          "Ignored analysis display publication failure: "
              + String.valueOf(displayFailure.getMessage()));
    }
  }

  private void publishAnalysisInfoToDisplayUnsafe(
      ParsedAnalysisInfo parsed, AnalysisInfoTarget target) {
    if (parsed == null
        || parsed.moves.isEmpty()
        || target == null
        || target.displayNode == null) {
      return;
    }
    BoardData displayData = target.displayNode.getData();
    if (!AnalysisCandidateValidator.allCandidatesOnEmptyPoints(parsed.moves, displayData)) {
      return;
    }
    boolean secondaryDisplay =
        Lizzie.config.isDoubleEngineMode() && Lizzie.leelaz2 != null && this == Lizzie.leelaz2;
    boolean engineGameParticipantToMove = true;
    if (!secondaryDisplay && EngineManager.isEngineGame && Lizzie.config.enginePkPonder) {
      engineGameParticipantToMove =
          (Lizzie.board.getHistory().isBlacksTurn()
                  && this
                      == Lizzie.engineManager.engineList.get(
                          EngineManager.engineGameInfo.blackEngineIndex))
              || (!Lizzie.board.getHistory().isBlacksTurn()
                  && this
                      == Lizzie.engineManager.engineList.get(
                          EngineManager.engineGameInfo.whiteEngineIndex));
    }
    if (!secondaryDisplay && !engineGameParticipantToMove) {
      return;
    }
    List<MoveData> boardMoves = new ArrayList<>(parsed.moves);
    if (secondaryDisplay) {
      if (parsed.kata) {
        displayData.tryToSetBestMoves2FromEngine(
            boardMoves,
            bestMovesEnginename,
            this,
            parsed.totalPlayouts,
            parsed.estimateArray);
      } else {
        displayData.tryToSetBestMoves2FromEngine(
            boardMoves, bestMovesEnginename, this, parsed.totalPlayouts, null);
      }
    } else if (parsed.kata) {
      displayData.tryToSetBestMovesFromEngine(
          boardMoves,
          bestMovesEnginename,
          this,
          parsed.totalPlayouts,
          parsed.estimateArray,
          false);
    } else {
      displayData.tryToSetBestMovesFromEngine(
          boardMoves, bestMovesEnginename, this, parsed.totalPlayouts, null, false);
    }
  }

  /** Caller holds {@link #analysisInfoMutationLock}; {@link #bestMoves} is the release fence. */
  private void publishParsedAnalysisInfoLocked(
      ParsedAnalysisInfo parsed, AnalysisInfoTarget target) {
    if (parsed.kata && !parsed.moves.isEmpty()) {
      scoreMean = parsed.moves.get(0).scoreMean;
      scoreStdev = parsed.moves.get(0).scoreStdev;
    }
    publishAnalysisInfoToDisplay(parsed, target);
    analysisInfoPayloadTarget = target;
    currentTotalPlayouts = parsed.totalPlayouts;
    bestMoves = parsed.moves;
  }

  // 试下诊断：限频打印 katago info 落到哪个 displayNode、引擎首选 winrate。开关见 TrialDiag。
  private static long lastTrialKataLogMs = 0L;
  private static long lastMainlineKataLogMs = 0L;
  private static long lastRawInfoLogMs = 0L;

  private void logTrialKataInfo(List<MoveData> bestMoves, int totalPlayouts) {
    if (!TrialDiag.ENABLED) return;
    if (bestMoves == null || bestMoves.isEmpty()) return;
    boolean trial =
        Lizzie.engineFollowController != null && Lizzie.engineFollowController.isTrialActive();
    long now = System.currentTimeMillis();
    if (trial) {
      if (now - lastTrialKataLogMs < 500) return;
      lastTrialKataLogMs = now;
    } else {
      if (now - lastMainlineKataLogMs < 1500) return;
      lastMainlineKataLogMs = now;
    }
    featurecat.lizzie.rules.BoardHistoryNode dn = Lizzie.frame.getDisplayNode();
    featurecat.lizzie.rules.BoardData dd = dn.getData();
    MoveData top = bestMoves.get(0);
    System.out.printf(
        "[%s-kata-info] writeTo displayNode moveNum=%d blackToPlay=%s "
            + "topMove=%s topWR=%.2f topScore=%.2f totalVisits=%d%n",
        trial ? "trial" : "mainline",
        dd.moveNumber,
        dd.blackToPlay,
        top.coordinate,
        top.winrate,
        top.scoreMean,
        totalPlayouts);
  }

  /**
   * Parse a line of Leelaz output
   *
   * @param line output line
   * @throws IOException
   */
  private void parseLineForGenmovePk(
      String line,
      ReaderStreamBinding sourceEngineIncarnation,
      EngineGameResponseHandler moveResponseHandler)
      throws IOException {
    if (moveResponseHandler != null
        && parseResponseCommandId(line) == NO_RESPONSE_COMMAND_ID
        && isUnnumberedEngineGameTerminalCarrier(line)) {
      // Exact genmove requests are numbered. Never let a late legacy/unframed predecessor response
      // borrow the current carrier and mutate the board while leaving its real permit unsettled.
      return;
    }
    EngineManager.EngineGameMoveResponseContext moveResponseContext =
        moveResponseHandler == null ? null : moveResponseHandler.context;
    long startupPrimaryGenerationAtParse =
        captureStartupPrimaryGeneration(sourceEngineIncarnation);
    EngineManager.EngineGameMoveResponseLease responseLease =
        EngineManager.claimEngineGameMoveResponse(moveResponseContext);
    if (responseLease == null) {
      // A numbered response from a retired transaction still owns its exact pending handler.
      // Drain that handler so its WRITE_CLAIMED carrier cannot block a successor, but perform no
      // board, UI, or engine-game side effects.
      if (moveResponseHandler != null
          && parseResponseCommandId(line) != NO_RESPONSE_COMMAND_ID) {
        processCommandResponseLine(line, sourceEngineIncarnation);
        isCommandLine = false;
      } else if (moveResponseHandler != null
          && moveResponseHandler.awaitingPassingCoordinate
          && !line.startsWith("info")) {
        completeEngineGamePassingResponse(
            moveResponseHandler, sourceEngineIncarnation, line.trim());
        isCommandLine = false;
      }
      return;
    }
    try {
    if (line.startsWith("?") && parseResponseCommandId(line) != NO_RESPONSE_COMMAND_ID) {
      // A current exact error is terminal for this move request. The pending handler performs the
      // once-only transaction failure and releases the physical carrier/retirement lease.
      processCommandResponseLine(line, sourceEngineIncarnation);
      afterEngineGameResponseSettledForTest();
      isCommandLine = false;
      return;
    }
    // Lizzie.gtpConsole.addLineforce(line);

    if (line.startsWith("info")) {
      AnalysisOutputRoute ingressRoute =
          analysisOutputRoute(sourceEngineIncarnation, line);
      long expectedAnalysisInfoEpoch = analysisInfoEpochSnapshot();
      AnalysisInfoTarget analysisInfoTarget = captureAnalysisInfoTarget();
      boolean responseUpToDate = isAnalysisResponseUpToDateSnapshot(ingressRoute);
      afterAnalysisOutputRouteCapturedForTest(ingressRoute.kind.name());
      AnalysisOutputRoute currentRoute =
          analysisOutputRoute(sourceEngineIncarnation, line);
      if (!ingressRoute.acceptsInfoLine()
          || !ingressRoute.hasSameOwner(currentRoute)
          || !currentRoute.acceptsInfoLine()) {
        return;
      }
      afterAnalysisInfoAdmissionSnapshotCapturedForTest();
      final ParsedAnalysisInfo parsedInfo;
      try {
        parsedInfo = parseAnalysisInfoPayload(line.substring(5));
      } catch (RuntimeException malformedInfo) {
        // Diagnostic analysis output is not a GTP response frame. A malformed payload must not
        // settle or fail the exact genmove carrier that happens to own this reader.
        return;
      }
      AtomicBoolean infoCommitted = new AtomicBoolean();
      runIfCurrentAnalysisOutputRoute(
          ingressRoute,
          () -> {
            synchronized (analysisInfoMutationLock()) {
              if (analysisInfoEpoch != expectedAnalysisInfoEpoch
                  || this == Lizzie.leelaz
                  || !responseUpToDate
                  || !isCurrentAnalysisInfoTarget(analysisInfoTarget)) {
                return;
              }
              publishParsedAnalysisInfoLocked(parsedInfo, analysisInfoTarget);
              infoCommitted.set(true);
            }
          });
      if (infoCommitted.get()) {
        if (parsedInfo.kata && this == Lizzie.leelaz) {
          AnalysisResourceCoordinator.foregroundPlayoutSample(
              this, parsedInfo.totalPlayouts);
        }
        if (parsedInfo.kata) {
          logTrialKataInfo(parsedInfo.moves, parsedInfo.totalPlayouts);
        }
        Lizzie.frame.requestAnalysisRefresh();
      }
      return;
    } else if (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp || !this.isLoaded)
      Lizzie.gtpConsole.addLine(line + "\n");
    if (isCheckingPda) {
      if (line.startsWith("pda:")) {
        isDymPda = true;
        String[] params = line.trim().split(" ");
        if (params.length == 2) pda = Double.parseDouble(params[1]);
        LizzieFrame.menu.txtPDA.setText(String.format(Locale.ENGLISH, "%.3f", pda));
        if (LizzieFrame.menu.setPda != null)
          LizzieFrame.menu.setPda.curPDA.setText(String.format(Locale.ENGLISH, "%.3f", pda));
        if (Lizzie.config.chkAutoPDA) {
          sendCommand(Lizzie.config.AutoPDA);
          if (Lizzie.config.chkDymPDA) {
            this.pdaCap = Double.parseDouble(Lizzie.config.dymPDACap.trim());
            if (LizzieFrame.menu.setPda != null)
              LizzieFrame.menu.setPda.txtDymCap.setText(Lizzie.config.dymPDACap);
          }
          if (Lizzie.config.chkStaticPDA) {
            LizzieFrame.menu.txtPDA.setText(Lizzie.config.staticPDAcur);
            this.pda = Double.parseDouble(Lizzie.config.staticPDAcur.trim());
            isStaticPda = true;
          } else {
            isStaticPda = false;
          }
        }
      }
      if (line.startsWith("PDACap:")) {
        String[] params = line.trim().split(" ");
        if (params.length == 2) {
          //	if(pdaCap==0)
          pdaCap = Double.parseDouble(params[1]);
          if (pdaCap != 0 && !isStaticPda) {
            isStaticPda = false;
            Runnable syncDymPda =
                new Runnable() {
                  public void run() {
                    int i = 0;
                    while (!canRestoreDymPda) {
                      try {
                        i++;
                        if (i > 19) break;
                        Thread.sleep(50);
                      } catch (InterruptedException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                      }
                    }
                    canRestoreDymPda = false;
                    if (Lizzie.config.chkAutoPDA) sendCommand(Lizzie.config.AutoPDA);
                    else sendCommand("dympdacap " + pdaCap);
                    if (isPondering()) ponder();
                  }
                };
            Thread syncDymPdaTh = new Thread(syncDymPda);
            syncDymPdaTh.start();
          } else {
            isStaticPda = true;
          }
          if (LizzieFrame.menu.setPda != null)
            LizzieFrame.menu.setPda.txtDymCap.setText(String.valueOf(pdaCap));
        }
      }
    }

    boolean passingContinuation =
        moveResponseHandler != null && moveResponseHandler.awaitingPassingCoordinate;
    if (line.startsWith("=") || line.startsWith("play") || passingContinuation) {
      isCommandLine = true;
      String[] params =
          passingContinuation
              ? new String[] {"=", line.trim()}
              : line.trim().split(" ");
      // currentCmdNum = Integer.parseInt(params[0].substring(1).trim());
      if (params.length <= 1) {
        if (parseResponseCommandId(line) != NO_RESPONSE_COMMAND_ID) {
          processCommandResponseLine(line, sourceEngineIncarnation);
          afterEngineGameResponseSettledForTest();
        }
        EngineManager.failEngineGameTransaction(
            moveResponseContext.transaction,
            new IllegalStateException("Engine-game genmove returned an empty success response"));
        isCommandLine = false;
        return;
      }
      if (params.length >= 2) {
        EngineGameInfo responseGame = moveResponseContext.gameInfo;
        boolean moverIsBlack =
            moveResponseContext.participantIndex == responseGame.blackEngineIndex;
        if (parseResponseCommandId(line) != NO_RESPONSE_COMMAND_ID
            && !params[1].startsWith("Passing")) {
          processCommandResponseLine(line, sourceEngineIncarnation);
          afterEngineGameResponseSettledForTest();
          isCommandLine = false;
          if (!responseLease.isCurrent()) return;
        }
        if (this.isZen) {
          if (!publishExactZenEngineGameBestMoves(moveResponseContext)) {
            return;
          }
        }
        if (params[1].toLowerCase().contains("resign")) {
          if (!recordExactEngineGameMoveTime(moveResponseContext, false)) return;
          finishExactEngineGameAnalysis(moveResponseContext, AnalysisGameTerminal.RESIGN);
          return;
        }
        if (moveResponseContext.moveNumber > responseGame.getMaxGameMoves()) {
          if (!recordExactEngineGameMoveTime(moveResponseContext, false)) return;
          finishExactEngineGameAnalysis(moveResponseContext, AnalysisGameTerminal.MAX_MOVES);
          return;
        }
        if (EngineManager.isExactEngineGameBoardFull(moveResponseContext)) {
          if (!recordExactEngineGameMoveTime(moveResponseContext, false)) return;
          finishExactEngineGameAnalysis(moveResponseContext, AnalysisGameTerminal.MAX_MOVES);
          return;
        }
        boolean isPassingLose = false;
        if (params[1].startsWith("Passing")) {
          moveResponseHandler.awaitingPassingCoordinate = true;
          isCommandLine = false;
          return;
        }
        if (!isPassingLose && params[1].startsWith("pass")) {
          if (!recordExactEngineGameMoveTime(moveResponseContext, false)) return;
          boolean doublePassNow = moveResponseContext.boardNode.getData().isPassNode();
          Leelaz selectedEngine =
              moverIsBlack ? moveResponseContext.blackEngine : moveResponseContext.whiteEngine;
          int selectedIndex =
              moverIsBlack ? responseGame.blackEngineIndex : responseGame.whiteEngineIndex;
          EngineManager.EngineGamePostMoveToken postMove =
              EngineManager.commitEngineGameMove(
                  moveResponseContext, null, null, selectedEngine, selectedIndex);
          if (postMove == null) return;
          if (!resetExactEngineGameMoveTimes(postMove, moveResponseContext)) return;
          if (doublePassNow) {
            finishExactEngineGameAnalysis(
                postMove, moveResponseContext.participantIndex, AnalysisGameTerminal.DOUBLE_PASS);
            return;
          }
          continueEngineGameAfterCommittedMove(
              moveResponseContext, postMove, moverIsBlack, "pass");
          return;
        } else {
          //	try {
          Optional<int[]> coords;
          coords = Board.asCoordinates(params[1]);
          if (!coords.isPresent()) {
            if (passingContinuation) {
              completeEngineGamePassingResponse(
                  moveResponseHandler, sourceEngineIncarnation, params[1]);
              isCommandLine = false;
            }
            EngineManager.failEngineGameTransaction(
                moveResponseContext.transaction,
                new IllegalStateException(
                    "Engine-game genmove returned an invalid coordinate: " + params[1]));
            return;
          }
          if (!recordExactEngineGameMoveTime(moveResponseContext, true)) return;
          Leelaz selectedEngine =
              moverIsBlack ? moveResponseContext.blackEngine : moveResponseContext.whiteEngine;
          int selectedIndex =
              moverIsBlack ? responseGame.blackEngineIndex : responseGame.whiteEngineIndex;
          int moveX = coords.get()[0];
          int moveY = coords.get()[1];
          EngineManager.EngineGamePostMoveToken postMove =
              EngineManager.commitEngineGameMove(
                  moveResponseContext,
                  moveX,
                  moveY,
                  selectedEngine,
                  selectedIndex);
          if (postMove == null) return;
          if (!resetExactEngineGameMoveTimes(postMove, moveResponseContext)) return;
          if (EngineManager.isExactEngineGameBoardFull(postMove)) {
            finishExactEngineGameAnalysis(
                postMove, moveResponseContext.participantIndex, AnalysisGameTerminal.MAX_MOVES);
            return;
          }
          if (passingContinuation) {
            completeEngineGamePassingResponse(
                moveResponseHandler, sourceEngineIncarnation, params[1]);
            isCommandLine = false;
            if (!EngineManager.isCurrentEngineGamePostMoveToken(postMove)) return;
          }

          //					}
          //					catch (Exception e)
          //					{
          //						return;
          //					}
          String coordsString = Board.convertCoordinatesToName(coords.get()[0], coords.get()[1]);
          continueEngineGameAfterCommittedMove(
              moveResponseContext, postMove, moverIsBlack, coordsString);
          return;
        }
      }
      runStartupCommandAction(
          checkNameAndVersion(
              params, startupPrimaryGenerationAtParse, sourceEngineIncarnation, null));
    } else if (line.startsWith("?")) {
      isCommandLine = true;
      if (consumeReadBoardGmaEngineErrorLine(line)) {
        return;
      }
      if (line.startsWith("? unacceptable komi")) {
        illegalKomi();
      }
    } else if (line.startsWith("PDA:")) {
      try {
        parsePDALine(line);
      } catch (RuntimeException malformedDiagnostic) {
        // PDA output is an unnumbered diagnostic, not the terminal response for this exact turn.
        // Malformed content or presentation failure must neither settle nor fail the numbered
        // genmove carrier.
        return;
      }
    }
    } catch (RuntimeException | Error responseFailure) {
      EngineManager.failEngineGameTransaction(moveResponseContext.transaction, responseFailure);
      throw responseFailure;
    } finally {
      responseLease.close();
    }
  }

  /**
   * Compatibility entry retained for the reader-incarnation regression. Passing continuations are
   * deliberately never consumed from this supplied reader; only the outer reader loop may deliver
   * the next framed line to the exact active handler.
   */
  @SuppressWarnings("unused")
  private void parseLineForGenmovePk(String line, BufferedReader ignoredReader)
      throws IOException {
    ReaderStreamBinding binding = currentReaderStreamBinding();
    parseLineForGenmovePk(line, binding, engineGameResponseHandlerForLine(line, binding));
  }

  /**
   * Compatibility entry for reader-incarnation tests and integrations compiled against the
   * original three-argument parser seam. The supplied reader remains deliberately untouched: only
   * the outer reader loop may consume the next framed response line.
   */
  @SuppressWarnings("unused")
  private void parseLineForGenmovePk(
      String line, BufferedReader ignoredReader, Object sourceEngineIncarnation)
      throws IOException {
    if (!(sourceEngineIncarnation instanceof ReaderStreamBinding)) {
      return;
    }
    ReaderStreamBinding binding = (ReaderStreamBinding) sourceEngineIncarnation;
    parseLineForGenmovePk(line, binding, engineGameResponseHandlerForLine(line, binding));
  }

  private void continueEngineGameAfterCommittedMove(
      EngineManager.EngineGameMoveResponseContext response,
      EngineManager.EngineGamePostMoveToken postMove,
      boolean moverIsBlack,
      String move) {
    EngineManager.EngineGameClockSync clockSync =
        EngineManager.claimEngineGamePostMoveClockSync(postMove);
    if (clockSync == null) {
      return;
    }
    if (!clockSync.isValid()) {
      EngineManager.failEngineGameTransaction(
          postMove.transaction, new IllegalStateException(clockSync.invalidReason));
      return;
    }
    if (!clockSync.requiresCommand()) {
      continueEngineGameAfterClockSync(response, postMove, moverIsBlack, move);
      return;
    }
    if (!clockSync.endpoint.sendEngineGameTimeLeft(
            clockSync,
            () -> continueEngineGameAfterClockSync(response, postMove, moverIsBlack, move))
        && EngineManager.isCurrentEngineGamePostMoveToken(postMove)) {
      EngineManager.failEngineGameTransaction(
          postMove.transaction,
          new IllegalStateException("Engine-game time_left lost exact participant ownership"));
    }
  }

  private void continueEngineGameAfterClockSync(
      EngineManager.EngineGameMoveResponseContext response,
      EngineManager.EngineGamePostMoveToken postMove,
      boolean moverIsBlack,
      String move) {
    if (!EngineManager.isCurrentEngineGamePostMoveToken(postMove)) {
      return;
    }
    Leelaz opponent = moverIsBlack ? response.whiteEngine : response.blackEngine;
    Leelaz mover = moverIsBlack ? response.blackEngine : response.whiteEngine;
    String playedColor = moverIsBlack ? "B" : "W";
    String nextColor = moverIsBlack ? "W" : "B";
    if (!opponent.playMoveGenmove(playedColor, move, postMove)) {
      return;
    }
    if (!EngineManager.isCurrentEngineGamePostMoveToken(postMove)) {
      return;
    }
    if (!opponent.genmoveForPk(nextColor, postMove)) {
      return;
    }
    if (!EngineManager.isCurrentEngineGamePostMoveToken(postMove)) {
      return;
    }
    if (!Lizzie.config.enginePkPonder) {
      mover.nameCmdfornoponder(postMove);
    }
  }

  private boolean publishExactZenEngineGameBestMoves(
      EngineManager.EngineGameMoveResponseContext response) {
    return EngineManager.runIfCurrentEngineGameMoveResponse(
        response,
        () -> {
          synchronized (analysisInfoMutationLock()) {
            if (bestMoves == null || bestMoves.isEmpty()) {
              return;
            }
            List<MoveData> completedMoves = bestMoves;
            int completedPlayouts = MoveData.getPlayouts(completedMoves);
            publishAnalysisDisplayNonFatal(
                () ->
                    response.boardHistory
                        .getData()
                        .tryToSetBestMovesFromEngine(
                            new ArrayList<>(completedMoves),
                            bestMovesEnginename,
                            this,
                            completedPlayouts,
                            null,
                            false));
            analysisOutputGeneration.incrementAndGet();
            currentTotalPlayouts = 0;
            analysisInfoPayloadTarget = null;
            bestMoves = List.of();
            analysisInfoEpoch++;
          }
        });
  }

  private boolean recordExactEngineGameMoveTime(
      EngineManager.EngineGameMoveResponseContext response, boolean enableAliveCheck) {
    return EngineManager.runIfCurrentEngineGameMoveResponse(
        response,
        () -> {
          if (enableAliveCheck) {
            canCheckAlive = true;
          }
          pkMoveTime = System.currentTimeMillis() - pkMoveStartTime;
          pkMoveTimeGame += pkMoveTime;
        });
  }

  private boolean resetExactEngineGameMoveTimes(
      EngineManager.EngineGamePostMoveToken postMove,
      EngineManager.EngineGameMoveResponseContext response) {
    return EngineManager.runIfCurrentEngineGamePostMoveToken(
        postMove,
        () -> {
          response.whiteEngine.clearPkMoveStartTime();
          response.blackEngine.clearPkMoveStartTime();
        });
  }

  void afterEngineGameResponseSettledForTest() {}

  private StartupCommandAction checkNameAndVersion(
      String[] params,
      long expectedPrimaryGeneration,
      Object expectedEngineIncarnation,
      EngineManager.EngineGameTransaction engineGameTransaction) {
    if (!(expectedEngineIncarnation instanceof ReaderStreamBinding)) {
      return StartupCommandAction.NONE;
    }
    ReaderStreamBinding binding = (ReaderStreamBinding) expectedEngineIncarnation;
    // Keep rebind fenced while the legacy name/version classifier mutates engine-local fields,
    // but never keep the endpoint monitor held across config persistence or menu callbacks.
    if (!beginReaderLine(binding)) {
      return StartupCommandAction.NONE;
    }
    AtomicReference<StartupCommandAction> action =
        new AtomicReference<>(StartupCommandAction.NONE);
    Throwable failure = null;
    try {
      Runnable exactClassifier =
          () -> {
            StartupCommandAction next =
                checkNameAndVersionForCurrentReader(
                    params,
                    expectedPrimaryGeneration,
                    expectedEngineIncarnation,
                    engineGameTransaction);
            synchronized (engineArbitrationLock()) {
              if (readerStreamBinding != binding || binding.terminated || !started) {
                if (readerStreamBinding == binding) {
                  isLoaded = false;
                }
                next = StartupCommandAction.NONE;
              } else if (next.kind == StartupCommandKind.CLOSE_BUNDLED) {
                // Plain Leela/version startup has no engine-specific post commands. Commit its
                // loaded admission only after classifier success and exact reader revalidation.
                markEngineLoaded();
              }
              action.set(next);
            }
          };
      if (engineGameTransaction == null) {
        exactClassifier.run();
      } else if (!EngineManager.runIfCurrentEngineGameTransaction(
          engineGameTransaction, exactClassifier)) {
        action.set(StartupCommandAction.NONE);
      }
    } catch (RuntimeException | Error mutationFailure) {
      failure = mutationFailure;
      markStartupPostActionFailedIfCurrent(binding, mutationFailure);
    } finally {
      try {
        endReaderLine(binding);
      } catch (RuntimeException | Error releaseFailure) {
        failure = appendEngineCleanupFailure(failure, releaseFailure);
        if (failure == releaseFailure) {
          markStartupPostActionFailedIfCurrent(binding, releaseFailure);
        }
      }
    }
    if (failure != null) {
      throw new StartupPostActionFailure(failure);
    }
    return action.get();
  }

  private StartupCommandAction checkNameAndVersionForCurrentReader(
      String[] params,
      long expectedPrimaryGeneration,
      Object expectedEngineIncarnation,
      EngineManager.EngineGameTransaction engineGameTransaction) {
    // TODO Auto-generated method stub
    boolean suppressGlobalPresentation =
        suppressesGlobalEnginePresentation(expectedEngineIncarnation);
    StartupCommandAction startupCommandAction = StartupCommandAction.NONE;
    if (isCheckingName) {
      noAnalyze = false;
      isKataGoPda = false;
      pkMoveStartTime = System.currentTimeMillis();
      if (params[1].toLowerCase().startsWith("golaxy")) requireResponseBeforeSend = true;
      else requireResponseBeforeSend = false;
      if (params[1].toLowerCase().startsWith("zen")) this.isZen = true;
      if (params[1].toLowerCase().startsWith("llzero")) {
        this.noLcb = true;
        canAddPlayer = true;
      }
      if (params[1].toLowerCase().startsWith("sai")) this.isSai = true;
      if ((params[1].toLowerCase().startsWith("leela")
              && params.length > 2
              && params[2].toLowerCase().startsWith("zero"))
          || params[1].toLowerCase().startsWith("pachi")) {
        this.isLeela = true;
        canAddPlayer = true;
      }
      if (params[1].equals("Leela") && params.length == 2) {
        isLeela0110 = true;
        startupCommandAction =
            StartupCommandAction.of(
                StartupCommandKind.CLOSE_BUNDLED,
                expectedPrimaryGeneration,
                expectedEngineIncarnation,
                engineGameTransaction,
                false,
                suppressGlobalPresentation);
      }
      //						if (params[1].startsWith("KataGoYm"))
      //							sendCommandToLeelazWithOutLog("lizzie_use");
      if (params[1].toLowerCase().startsWith("kata")) {
        canAddPlayer = true;
        if (engineGameTransaction == null
            && !suppressGlobalPresentation
            && Utils.applyRecommendedKataGoThreads(false)) {
          Utils.persistConfigQuietly();
        }
        if (params[1].startsWith("KataGoPda")) {
          isKatagoCustom = true;
          isKataGoPda = true;
        }
        this.isKatago = true;
        if (params[1].startsWith("KataGoCustom")) isKatagoCustom = true;
        this.version = 17;
        startupCommandAction =
            StartupCommandAction.of(
                StartupCommandKind.KATA,
                expectedPrimaryGeneration,
                expectedEngineIncarnation,
                engineGameTransaction,
                true,
                suppressGlobalPresentation);

        if (engineGameTransaction == null
            && !suppressGlobalPresentation
            && this.currentEngineN == EngineManager.currentEngineNo) {
          Lizzie.config.leelaversion = version;
        }
        isTuning = false;
      } else {
        isTuning = false;
        isKatago = false;
        startupCommandAction =
            StartupCommandAction.of(
                StartupCommandKind.LEELA_SAI,
                expectedPrimaryGeneration,
                expectedEngineIncarnation,
                engineGameTransaction,
                false,
                suppressGlobalPresentation);
      }
      if (params[1].toLowerCase().startsWith("katajigo")) {
        this.isKatago = true;
        this.noAnalyze = true;
      }
      if (params[1].equals("Sayuri")) {
        isSayuri = true;
        isSai = true;
        canAddPlayer = true;
      }
      isCheckingName = false;
    } else if (isCheckingVersion && !isLeela0110) {
      if (isKatago) {
        String[] ver = params[1].split("\\.");
        if (ver.length >= 2) {
          try {
            if (Integer.parseInt(ver[0]) > 1 || Integer.parseInt(ver[1]) > 10) {
              supportMovesOwnership = true;
            }
          } catch (Exception ex) {
            ex.printStackTrace();
            supportMovesOwnership = false;
          }
        }
        isCheckingVersion = false;
      } else {
        String[] ver = params[1].split("\\.");
        try {
          int minor = Integer.parseInt(ver[1]);
          // Gtp support added in version 15
          version = minor;
          if (version == 15) canAddPlayer = false;
        } catch (Exception ex) {
          version = 17;
        }
        if (engineGameTransaction == null
            && !suppressGlobalPresentation
            && this.currentEngineN == EngineManager.currentEngineNo) {
          Lizzie.config.leelaversion = version;
        }
        if (version == 7) {
          version = 17;
        }
        isCheckingVersion = false;
        startupCommandAction =
            StartupCommandAction.of(
                StartupCommandKind.CLOSE_BUNDLED,
                expectedPrimaryGeneration,
                expectedEngineIncarnation,
                engineGameTransaction,
                true,
                suppressGlobalPresentation);
        isTuning = false;
        // Lizzie.initializeAfterVersionCheck();
      }
    }
    return startupCommandAction;
  }

  private StartupCommandAction mergeStartupCommandAction(
      StartupCommandAction current, StartupCommandAction next) {
    return current.kind == StartupCommandKind.NONE ? next : current;
  }

  private void runStartupCommandAction(StartupCommandAction action) {
    if (action.kind == StartupCommandKind.KATA
        || action.kind == StartupCommandKind.LEELA_SAI) {
      dispatchStartupEnginePostAction(action);
      return;
    }
    if (action.suppressGlobalEnginePresentation) {
      return;
    }
    if (action.publishReadyIcon) {
      EngineManager.publishReadyEngineIconIfCurrent(this, action.expectedEngineIncarnation);
    }
    if (action.kind.closeBundledStartupDialog) {
      // checkNameAndVersion may run under parseLine's object monitor. PRIMARY -> endpoint is the
      // canonical lifecycle order, so publish bundled readiness only after that parser monitor is
      // released and engine-specific startup post-work has committed.
      closeBundledStartupDialog(
          action.expectedPrimaryGeneration, action.expectedEngineIncarnation);
    }
  }

  private void dispatchStartupEnginePostAction(StartupCommandAction action) {
    if (!(action.expectedEngineIncarnation instanceof ReaderStreamBinding)) {
      return;
    }
    ReaderStreamBinding binding = (ReaderStreamBinding) action.expectedEngineIncarnation;
    StartupPostActionLease lease = beginStartupPostActionLease(binding, action);
    if (lease == null) {
      return;
    }
    Runnable worker = () -> runStartupEnginePostAction(lease);
    try {
      dispatchStartupPostActionWorker(worker);
    } catch (StartupPostActionFailure inlineWorkerFailure) {
      throw inlineWorkerFailure;
    } catch (RuntimeException | Error schedulingFailure) {
      if (lease.finishSchedulingFailure(schedulingFailure)) {
        throw new StartupPostActionFailure(schedulingFailure);
      }
    }
  }

  private StartupPostActionLease beginStartupPostActionLease(
      ReaderStreamBinding binding, StartupCommandAction action) {
    if (action.engineGameTransaction != null
        && !EngineManager.isCurrentEngineGameTransaction(action.engineGameTransaction)) {
      return null;
    }
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding != binding || binding.terminated || !started) {
        return null;
      }
      binding.startupPostActionsInProgress++;
      // parseLine runs with the exact restart-bootstrap receipt installed on its reader thread.
      // Freeze that capability before moving post-work to a different worker thread; a receipt
      // read later from mutable engine state could authorize a stale incarnation.
      RestartBootstrapReceipt bootstrapReceipt = restartBootstrapReceiptContext.get();
      if (bootstrapReceipt == null) {
        bootstrapReceipt = binding.restartBootstrapReceipt;
      }
      return new StartupPostActionLease(binding, action, bootstrapReceipt);
    }
  }

  private void runStartupEnginePostAction(StartupPostActionLease lease) {
    if (!lease.claimWorkerOwnership()) {
      return;
    }
    if (lease.action.engineGameTransaction != null
        && !EngineManager.isCurrentEngineGameTransaction(lease.action.engineGameTransaction)) {
      lease.finishRetired();
      return;
    }
    Object previousStartupCommandBinding = startupPostActionCommandContext.get();
    Throwable failure = null;
    try {
      startupPostActionCommandContext.set(lease);
      runWithRestartBootstrapReceipt(
          lease.bootstrapReceipt,
          () -> {
            runWithEngineGameStartupCommandContext(
                lease.action.engineGameTransaction,
                () -> {
                  if (lease.action.kind == StartupCommandKind.KATA) {
                    runKataStartupCommandAction(
                        lease.binding, lease.action.suppressGlobalEnginePresentation);
                  } else {
                    setLeelaSaiEnginePara();
                  }
                });
          });
    } catch (RuntimeException | Error startupFailure) {
      failure = startupFailure;
    } finally {
      if (previousStartupCommandBinding == null) {
        startupPostActionCommandContext.remove();
      } else {
        startupPostActionCommandContext.set(previousStartupCommandBinding);
      }
    }
    if (failure != null) {
      if (lease.finishFailure(failure)) {
        throw new StartupPostActionFailure(failure);
      }
      return;
    }
    afterStartupPostActionCommandsForTest();
    lease.finishSuccess();
  }

  void afterStartupPostActionCommandsForTest() {}

  void dispatchStartupPostActionWorker(Runnable worker) {
    Thread thread =
        new Thread(
            () -> {
              try {
                worker.run();
              } catch (StartupPostActionFailure failure) {
                // The exact binding has already failed closed; the lifecycle observer owns UI.
              }
            },
            "lizzie-engine-startup-post-action");
    thread.setDaemon(true);
    thread.start();
  }

  private final class StartupPostActionLease {
    private final ReaderStreamBinding binding;
    private final StartupCommandAction action;
    private final RestartBootstrapReceipt bootstrapReceipt;
    private final AtomicInteger dispatchOwner = new AtomicInteger();
    private final AtomicBoolean settled = new AtomicBoolean();

    private StartupPostActionLease(
        ReaderStreamBinding binding,
        StartupCommandAction action,
        RestartBootstrapReceipt bootstrapReceipt) {
      this.binding = binding;
      this.action = action;
      this.bootstrapReceipt = bootstrapReceipt;
    }

    private void finishSuccess() {
      if (!settled.compareAndSet(false, true)) {
        return;
      }
      AtomicReference<Throwable> commitFailure = new AtomicReference<>();
      AtomicBoolean publishReady = new AtomicBoolean();
      Runnable exactCommit =
          () -> {
            synchronized (engineArbitrationLock()) {
              try {
                if (readerStreamBinding == binding && !binding.terminated && started) {
                  markEngineLoaded();
                  publishReady.set(true);
                }
              } catch (RuntimeException | Error failure) {
                commitFailure.set(failure);
                terminateStartupPostActionBindingLocked(binding, failure);
              }
            }
          };
      boolean transactionCurrent = true;
      if (action.engineGameTransaction == null) {
        exactCommit.run();
      } else {
        transactionCurrent =
            EngineManager.runIfCurrentEngineGameTransaction(
                action.engineGameTransaction, exactCommit);
      }
      boolean finishTerminalCleanup;
      synchronized (engineArbitrationLock()) {
        finishTerminalCleanup = releaseStartupPostActionLeaseLocked(binding);
      }
      Throwable failure = commitFailure.get();
      if (failure != null && action.engineGameTransaction != null) {
        try {
          EngineManager.failEngineGameTransaction(action.engineGameTransaction, failure);
        } catch (RuntimeException | Error terminalFailure) {
          failure = appendEngineCleanupFailure(failure, terminalFailure);
        }
      }
      if (finishTerminalCleanup) {
        try {
          finishReaderTerminalCleanup(binding);
        } catch (RuntimeException | Error cleanupFailure) {
          failure = appendEngineCleanupFailure(failure, cleanupFailure);
        }
      }
      if (failure != null) {
        throw new StartupPostActionFailure(failure);
      }
      if (!transactionCurrent || !publishReady.get()) {
        return;
      }
      // Engine-game startup owns its own terminal presentation. Parser-specific READY icon/dialog
      // callbacks must never escape after the transaction has stopped or been superseded.
      if (action.engineGameTransaction != null
          || action.suppressGlobalEnginePresentation) {
        return;
      }
      if (action.publishReadyIcon) {
        try {
          EngineManager.publishReadyEngineIconIfCurrent(
              Leelaz.this, action.expectedEngineIncarnation);
        } catch (RuntimeException | Error presentationFailure) {
          presentationFailure.printStackTrace();
        }
      }
      if (action.kind.closeBundledStartupDialog) {
        try {
          closeBundledStartupDialog(
              action.expectedPrimaryGeneration, action.expectedEngineIncarnation);
        } catch (RuntimeException | Error presentationFailure) {
          presentationFailure.printStackTrace();
        }
      }
    }

    private boolean finishFailure(Throwable failure) {
      if (!settled.compareAndSet(false, true)) {
        return false;
      }
      boolean finishTerminalCleanup;
      synchronized (engineArbitrationLock()) {
        terminateStartupPostActionBindingLocked(binding, failure);
        finishTerminalCleanup = releaseStartupPostActionLeaseLocked(binding);
      }
      if (finishTerminalCleanup) {
        try {
          finishReaderTerminalCleanup(binding);
        } catch (RuntimeException | Error cleanupFailure) {
          appendEngineCleanupFailure(failure, cleanupFailure);
        }
      }
      return true;
    }

    private boolean claimWorkerOwnership() {
      return dispatchOwner.compareAndSet(0, 1);
    }

    private boolean finishSchedulingFailure(Throwable failure) {
      if (!dispatchOwner.compareAndSet(0, 2)) {
        return false;
      }
      return finishFailure(failure);
    }

    private void finishRetired() {
      if (!settled.compareAndSet(false, true)) {
        return;
      }
      boolean finishTerminalCleanup;
      synchronized (engineArbitrationLock()) {
        finishTerminalCleanup = releaseStartupPostActionLeaseLocked(binding);
      }
      if (finishTerminalCleanup) {
        finishReaderTerminalCleanup(binding);
      }
    }

    private void sendCommand(String command) {
      if (settled.get()) {
        throw new IllegalStateException("startup post-action is no longer current");
      }
      if (action.engineGameTransaction != null
          && !EngineManager.isCurrentEngineGameTransaction(action.engineGameTransaction)) {
        throw new IllegalStateException("engine-game startup transaction is no longer current");
      }
      sendStartupPostActionCommand(command, binding);
      if (settled.get()) {
        throw new IllegalStateException("startup post-action was retired during command delivery");
      }
    }
  }

  private boolean releaseStartupPostActionLeaseLocked(ReaderStreamBinding binding) {
    if (binding.startupPostActionsInProgress > 0) {
      binding.startupPostActionsInProgress--;
    }
    boolean finishTerminalCleanup =
        binding.startupPostActionsInProgress == 0
            && binding.linesInProgress == 0
            && binding.terminated
            && !binding.terminalCleanupStarted;
    if (finishTerminalCleanup) {
      binding.terminalCleanupStarted = true;
      readerTerminalCleanupInProgress = true;
    }
    engineArbitrationLock().notifyAll();
    return finishTerminalCleanup;
  }

  private void markStartupPostActionFailedLocked(
      ReaderStreamBinding binding, Throwable failure) {
    if (readerStreamBinding != binding || binding.terminated) {
      return;
    }
    isLoaded = false;
    isDownWithError = true;
    isCheckingPda = false;
    synchronized (parameterReadTimeoutLock) {
      parameterReadTimeoutGeneration++;
      getRcentLine = false;
    }
    try {
      rememberRecentLine(
          recentStderrLines,
          safeFailureDetail(failure, "engine startup post-action failed"));
    } catch (RuntimeException | Error diagnosticFailure) {
      appendEngineCleanupFailure(failure, diagnosticFailure);
    }
  }

  private void terminateStartupPostActionBindingLocked(
      ReaderStreamBinding binding, Throwable failure) {
    markStartupPostActionFailedLocked(binding, failure);
    if (readerStreamBinding == binding && !binding.terminated) {
      retireAnalysisOutputBindingLocked(binding);
      binding.terminalFailure = failure;
    }
  }

  private void markStartupPostActionFailedIfCurrent(
      ReaderStreamBinding binding, Throwable failure) {
    synchronized (engineArbitrationLock()) {
      markStartupPostActionFailedLocked(binding, failure);
    }
  }

  /** Runs under the exact startup-post lease without holding the endpoint monitor or PRIMARY. */
  private void runKataStartupCommandAction(
      Object expectedEngineIncarnation, boolean frozenGlobalPresentationSuppression) {
    boolean isolatedEngineGameStartup =
        engineGameStartupCommandContext.get() != null
            || frozenGlobalPresentationSuppression
            || suppressesGlobalEnginePresentation(expectedEngineIncarnation);
    Runnable pdaQueryCleanup = null;
    if (!isolatedEngineGameStartup && isKataGoPda) {
      isCheckingPda = true;
      long queryGeneration = ++pdaStartupQueryGeneration;
      pdaQueryCleanup =
          () ->
              runIfCurrentEngineIncarnation(
                  expectedEngineIncarnation,
                  () -> {
                    if (pdaStartupQueryGeneration == queryGeneration) {
                      isCheckingPda = false;
                    }
                  });
    }
    try {
      if (pdaQueryCleanup != null) {
        sendCommand("getpda");
        sendCommand("getdympdacap");
        startPdaStartupTimeoutThread(pdaQueryCleanup);
      }
      setKataEnginePara();
      if (Lizzie.config.autoLoadKataRules) {
        sendCommand("kata-set-rules " + Lizzie.config.kataRules);
      }
      if (!isolatedEngineGameStartup) {
        getParameterScadule(true);
      }
    } catch (RuntimeException | Error startupFailure) {
      if (pdaQueryCleanup != null) {
        pdaQueryCleanup.run();
      }
      throw startupFailure;
    }
  }

  void startPdaStartupTimeoutThread(Runnable timeout) {
    Thread thread =
        new Thread(
            () -> {
              try {
                Thread.sleep(5000);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                timeout.run();
                return;
              }
              timeout.run();
            },
            "lizzie-katago-pda-startup-timeout");
    thread.setDaemon(true);
    thread.start();
  }

  boolean isCheckingPdaForTest() {
    synchronized (engineArbitrationLock()) {
      return isCheckingPda;
    }
  }

  private void checkForGomokuFullBoard(boolean isGenmove) {
    // TODO Auto-generated method stub
    if (!Lizzie.config.noCapture) return;
    Stone[] stones = Lizzie.board.getData().stones;
    for (Stone stone : stones) {
      if (stone == Stone.EMPTY) return;
    }
    if (isGenmove) {
      pkMoveTime = System.currentTimeMillis() - pkMoveStartTime;
      pkMoveTimeGame = pkMoveTimeGame + pkMoveTime;
      outOfMoveNum = true;
      nameCmdfornoponder();
      genmoveResign(false);
    } else {
      outOfMoveNum = true;
      resigned = true;
    }
  }

  private boolean isAnalysisOutputOwnershipCommand(String command) {
    if (command == null) {
      return false;
    }
    String normalized = command.trim().toLowerCase(Locale.ROOT);
    return normalized.startsWith("kata-analyze")
        || normalized.startsWith("lz-analyze")
        || normalized.startsWith("analyze ")
        || normalized.startsWith("kata-genmove_analyze ")
        || normalized.startsWith("lz-genmove_analyze ")
        || normalized.startsWith("genmove_analyze ")
        || normalized.startsWith("genmove ")
        || (isLeela0110 && normalized.startsWith("time_left "));
  }

  /** Commands whose successful physical write starts a distinct analysis payload generation. */
  private boolean startsNewAnalysisInfoPayload(String command) {
    if (command == null) {
      return false;
    }
    String normalized = command.trim().toLowerCase(Locale.ROOT);
    return normalized.startsWith("kata-analyze")
        || normalized.startsWith("lz-analyze")
        || normalized.startsWith("analyze ")
        || normalized.startsWith("kata-genmove_analyze ")
        || normalized.startsWith("lz-genmove_analyze ")
        || normalized.startsWith("genmove_analyze ")
        || normalized.startsWith("genmove ");
  }

  private enum AnalysisStateMutation {
    NONE,
    RESET_PAYLOAD,
    RESET_PAYLOAD_AND_SCORE,
    RETAIN_PAYLOAD
  }

  /** Position mutations and the local payload policy linearized with their physical write. */
  private AnalysisStateMutation analysisStateMutation(String command) {
    if (command == null) {
      return AnalysisStateMutation.NONE;
    }
    String normalized = command.trim().toLowerCase(Locale.ROOT);
    if (normalized.equals("clear_board")) {
      return AnalysisStateMutation.RESET_PAYLOAD_AND_SCORE;
    }
    if (normalized.startsWith("komi ")) {
      return AnalysisStateMutation.RETAIN_PAYLOAD;
    }
    if (normalized.startsWith("play ")
        || normalized.equals("undo")
        || normalized.startsWith("loadsgf ")
        || normalized.equals("set_position")
        || normalized.startsWith("set_position ")
        || normalized.startsWith("boardsize ")
        || normalized.startsWith("rectangular_boardsize ")
        || normalized.startsWith("fixed_handicap ")
        || normalized.startsWith("place_free_handicap ")
        || normalized.startsWith("set_free_handicap ")) {
      return AnalysisStateMutation.RESET_PAYLOAD;
    }
    return AnalysisStateMutation.NONE;
  }

  /** Commands that fully replace the engine position and therefore start a clean dependency. */
  private boolean startsFreshAnalysisStateLineage(String command) {
    if (command == null) {
      return false;
    }
    String normalized = command.trim().toLowerCase(Locale.ROOT);
    return normalized.equals("clear_board")
        || normalized.startsWith("loadsgf ")
        || normalized.equals("set_position")
        || normalized.startsWith("set_position ")
        || normalized.startsWith("boardsize ")
        || normalized.startsWith("rectangular_boardsize ")
        || normalized.startsWith("fixed_handicap ")
        || normalized.startsWith("place_free_handicap ")
        || normalized.startsWith("set_free_handicap ");
  }

  /** Requires the binding's analysis-output mutation lock at the physical state-write boundary. */
  private void bindAnalysisStateLineageAtPhysicalWrite(
      QueuedCommand command, ReaderStreamBinding binding) {
    AnalysisStateLineage lineage = binding.analysisStateLineage;
    if (lineage == null || startsFreshAnalysisStateLineage(command.command)) {
      lineage = new AnalysisStateLineage();
      binding.analysisStateLineage = lineage;
    }
    command.bindAnalysisStateLineage(lineage, binding);
  }

  /**
   * Permanently poisons one physically written state lineage. If it still owns current analysis,
   * the same manager admission used by parser commits makes failure publication atomic with a
   * final payload clear. A raced successor on the same lineage is retried; a full rebuild owns a
   * different lineage and is deliberately left intact.
   */
  private void failAnalysisStateLineage(QueuedCommand command) {
    AnalysisStateLineage lineage = command == null ? null : command.analysisStateLineage();
    if (lineage == null || lineage.isFailed()) {
      return;
    }
    ReaderStreamBinding responseBinding = command.analysisStateResponseBinding();
    boolean resetScore =
        analysisStateMutation(command.command) == AnalysisStateMutation.RESET_PAYLOAD_AND_SCORE;
    while (!lineage.isFailed()) {
      AnalysisOutputRoute route = analysisOutputRoute(responseBinding);
      AnalysisOutputOwnership ownership =
          route.ownerToken instanceof AnalysisOutputOwnership
              ? (AnalysisOutputOwnership) route.ownerToken
              : null;
      if (route.acceptsInfoLine()
          && ownership != null
          && ownership.analysisStateLineage == lineage) {
        if (runIfCurrentAnalysisOutputRoute(
            route,
            () -> {
              if (lineage.fail()) {
                resetAnalysisInfoPayload(resetScore);
              }
            })) {
          return;
        }
        continue;
      }

      ReaderStreamBinding binding = responseBinding;
      boolean retryCurrentOwner = false;
      if (binding != null) {
        binding.analysisOutputMutationLock.lock();
        try {
          boolean currentLineage =
              readerStreamBinding == binding
                  && !binding.terminated
                  && binding.analysisStateLineage == lineage;
          AnalysisOutputOwnership currentOwnership = binding.analysisOutputOwnership.get();
          // A successor may have published between the route snapshot above and this binding
          // fence. Retry so its manager admission drains any parser that already passed the
          // owner check before the lineage is poisoned.
          retryCurrentOwner =
              currentLineage
                  && route.binding == binding
                  && currentOwnership != null
                  && currentOwnership != ownership
                  && currentOwnership.analysisStateLineage == lineage
                  && !currentOwnership.hasFailedAnalysisStateLineage();
          if (!retryCurrentOwner && lineage.fail() && currentLineage) {
            // Keep the reset inside the binding fence: a fresh full-state rebuild cannot replace
            // the lineage and publish a new payload between this decision and the clear.
            resetAnalysisInfoPayload(resetScore);
          }
        } finally {
          binding.analysisOutputMutationLock.unlock();
        }
      } else {
        lineage.fail();
      }
      if (retryCurrentOwner) {
        continue;
      }
      return;
    }
  }

  private boolean shouldPublishAnalysisOutputOwnership(
      QueuedCommand command, ReaderStreamBinding binding) {
    if (command == null || binding == null || !isAnalysisOutputOwnershipCommand(command.command)) {
      return false;
    }
    EngineManager.EngineGameTransaction transaction = command.engineGameTransaction();
    if (transaction != null && transaction.isGenmove()) {
      // Numbered engine-game genmove has its own exact response carrier. Installing an
      // unnumbered owner here would both misclassify its info and make context capture reject the
      // physical write because analysis-mode ownership is intentionally unavailable in genmove.
      return false;
    }
    if (!isLeela0110
        || !command.command.trim().toLowerCase(Locale.ROOT).startsWith("time_left ")) {
      return true;
    }
    EngineManager.EngineGamePrimaryContext currentGame =
        transaction == null
            ? EngineManager.captureEngineGamePrimaryContext(this, binding)
            : null;
    return !((transaction != null && transaction.isGenmove())
        || (currentGame != null && currentGame.gameInfo.isGenmove));
  }

  private AnalysisOutputOwnershipPublication publishAnalysisOutputOwnershipAtPhysicalWrite(
      QueuedCommand command,
      ReaderStreamBinding binding,
      EngineManager.TransactionlessAnalysisWriteLease transactionlessLease,
      EngineManager.EngineGamePrimaryContext exactContext) {
    EngineManager.EngineGameTransaction transaction = command.engineGameTransaction();
    if (readerStreamBinding != binding || binding.terminated) {
      throw new AnalysisOutputAdmissionFailure(
          "analysis output lost its reader before physical command output");
    }
    if (transaction == null) {
      if (transactionlessLease == null) {
        throw new AnalysisOutputAdmissionFailure(
            "transaction-less analysis output has no physical-write admission");
      }
      AnalysisOutputOwnership previous = binding.analysisOutputOwnership.get();
      boolean previousBindingSuppression = binding.suppressGlobalEnginePresentation;
      boolean previousPersistentSuppression = suppressGlobalEnginePresentationUntilOwned;
      AnalysisOutputOwnership replacement;
      if (transactionlessLease.kind
          == EngineManager.TransactionlessAnalysisWriteKind.RECOVERY_TOMBSTONE) {
        replacement =
            AnalysisOutputOwnership.recoveryTombstone(
                transactionlessLease.recoveryToken,
                analysisOutputGeneration.get(),
                binding.analysisStateLineage);
        binding.suppressGlobalEnginePresentation = true;
        suppressGlobalEnginePresentationUntilOwned = true;
      } else {
        replacement =
            AnalysisOutputOwnership.ordinary(
                analysisOutputGeneration.get(), binding.analysisStateLineage);
        binding.suppressGlobalEnginePresentation = false;
        suppressGlobalEnginePresentationUntilOwned = false;
      }
      binding.analysisOutputOwnership.set(replacement);
      return new AnalysisOutputOwnershipPublication(
          binding,
          previous,
          replacement,
          previousBindingSuppression,
          previousPersistentSuppression);
    }
    if (exactContext == null) {
      throw new AnalysisOutputAdmissionFailure(
          "engine-game analysis output lost ownership before physical command output");
    }
    AnalysisOutputOwnership replacement =
        AnalysisOutputOwnership.exact(
            exactContext, analysisOutputGeneration.get(), binding.analysisStateLineage);
    AnalysisOutputOwnership previous = binding.analysisOutputOwnership.get();
    boolean previousBindingSuppression = binding.suppressGlobalEnginePresentation;
    boolean previousPersistentSuppression = suppressGlobalEnginePresentationUntilOwned;
    binding.suppressGlobalEnginePresentation = false;
    suppressGlobalEnginePresentationUntilOwned = false;
    binding.analysisOutputOwnership.set(replacement);
    return new AnalysisOutputOwnershipPublication(
        binding,
        previous,
        replacement,
        previousBindingSuppression,
        previousPersistentSuppression);
  }

  private AnalysisOutputRoute analysisOutputRoute(Object sourceEngineIncarnation) {
    return analysisOutputRoute(sourceEngineIncarnation, null);
  }

  private boolean isAnalysisOutputSignalLine(String line) {
    if (line == null) {
      return false;
    }
    String lower = line.toLowerCase(Locale.ROOT);
    return line.startsWith("info")
        || line.contains("Nodes:")
        || line.contains("I pass")
        || lower.contains("resign")
        || line.contains("->")
        || line.startsWith("=====")
        || line.endsWith("nodes")
        || line.startsWith("NN eval")
        || line.startsWith("root eval")
        || line.startsWith("| ST")
        || line.startsWith("PDA:")
        || (line.startsWith("MALKOVICH:") && line.contains("PDA"));
  }

  private AnalysisOutputRoute analysisOutputRoute(
      Object sourceEngineIncarnation, String line) {
    if (!(sourceEngineIncarnation instanceof ReaderStreamBinding)) {
      return new AnalysisOutputRoute(AnalysisOutputRouteKind.EXACT_RETIRED, null);
    }
    ReaderStreamBinding binding = (ReaderStreamBinding) sourceEngineIncarnation;
    if (readerStreamBinding != binding
        || binding.terminated
        || suppressesGlobalEnginePresentation(binding)) {
      return new AnalysisOutputRoute(AnalysisOutputRouteKind.EXACT_RETIRED, null);
    }
    if (isAnalysisOutputSignalLine(line)) {
      EngineManager.EngineGameMoveResponseContext moveResponse =
          pendingEngineGameMoveResponseContext(line, binding);
      if (moveResponse != null && moveResponse.gameInfo.isGenmove) {
        return new AnalysisOutputRoute(
            EngineManager.isCurrentEngineGameMoveResponse(moveResponse)
                ? AnalysisOutputRouteKind.GENMOVE_CURRENT
                : AnalysisOutputRouteKind.EXACT_RETIRED,
            null,
            binding,
            moveResponse,
            moveResponse);
      }
    }
    AnalysisOutputOwnership ownership = binding.analysisOutputOwnership.get();
    if (ownership == null) {
      return new AnalysisOutputRoute(
          AnalysisOutputRouteKind.EXACT_RETIRED,
          null,
          binding,
          null,
          null);
    }
    if (ownership.hasFailedAnalysisStateLineage()) {
      return new AnalysisOutputRoute(
          AnalysisOutputRouteKind.EXACT_RETIRED, null, binding, ownership, null);
    }
    if (ownership.generation != analysisOutputGeneration.get()) {
      return new AnalysisOutputRoute(
          AnalysisOutputRouteKind.EXACT_RETIRED, null, binding, ownership, null);
    }
    if (ownership.isOrdinary()) {
      boolean currentOrdinary =
          readerStreamBinding == binding
              && !binding.terminated
              && !EngineManager.hasEngineGameAnalysisOutputBarrier();
      return new AnalysisOutputRoute(
          currentOrdinary
              ? AnalysisOutputRouteKind.ORDINARY_CURRENT
              : AnalysisOutputRouteKind.EXACT_RETIRED,
          null,
          binding,
          ownership,
          null);
    }
    if (!ownership.isExact()) {
      return new AnalysisOutputRoute(
          AnalysisOutputRouteKind.EXACT_RETIRED, null, binding, ownership, null);
    }
    if (!EngineManager.isCurrentEngineGameAnalysisOutputContext(ownership.exactContext)) {
      return new AnalysisOutputRoute(
          AnalysisOutputRouteKind.EXACT_RETIRED, null, binding, ownership, null);
    }
    return new AnalysisOutputRoute(
        AnalysisOutputRouteKind.EXACT_CURRENT,
        EngineManager.activeEngineGameAnalysisOutputContext(ownership.exactContext),
        binding,
        ownership,
        null);
  }

  private boolean hasSameAnalysisOutputOwnerLocked(AnalysisOutputRoute expected) {
    if (expected == null
        || expected.binding == null
        || readerStreamBinding != expected.binding
        || expected.binding.terminated
        || expected.binding.suppressGlobalEnginePresentation
        || expected.binding.deferredEngineGameRecoveryPresentationSuppressed) {
      return false;
    }
    if (expected.kind == AnalysisOutputRouteKind.GENMOVE_CURRENT) {
      return expected.ownerToken == expected.genmoveContext;
    }
    AnalysisOutputOwnership ownership = expected.binding.analysisOutputOwnership.get();
    return expected.ownerToken != null
        && ownership == expected.ownerToken
        && !ownership.hasFailedAnalysisStateLineage()
        && ownership.generation == analysisOutputGeneration.get();
  }

  /**
   * Acquires the short binding-owner fence used by parser publications. A numbered genmove writer
   * owns this lock across its physical write while its response carrier is already visible to the
   * parser. The parser also holds the transaction admission, so waiting here would let terminal
   * teardown wait on the parser while the parser waits on transport completion. Output observed in
   * that window is premature and may safely be dropped instead.
   */
  private boolean acquireAnalysisOutputMutationLock(AnalysisOutputRoute expected) {
    if (expected.kind == AnalysisOutputRouteKind.GENMOVE_CURRENT) {
      return expected.binding.analysisOutputMutationLock.tryLock();
    }
    expected.binding.analysisOutputMutationLock.lock();
    return true;
  }

  /**
   * Publishes one short parser mutation only while the physical stream owner captured at ingress
   * is still the identical owner. The manager fence prevents terminal/admission from crossing the
   * mutation. Ownership is checked under the binding lock, then that lock is released before the
   * mutation: the enclosing transaction/global admission prevents a physical successor from
   * replacing the owner, without ever nesting endpoint, selection, UI, or output work under the
   * binding lock.
   */
  private boolean runIfCurrentAnalysisOutputRoute(
      AnalysisOutputRoute expected, Runnable mutation) {
    if (expected == null || mutation == null || !expected.acceptsInfoLine()) {
      return false;
    }
    AtomicBoolean committed = new AtomicBoolean();
    Runnable guardedMutation =
        () -> {
          if (!acquireAnalysisOutputMutationLock(expected)) {
            return;
          }
          try {
            if (!hasSameAnalysisOutputOwnerLocked(expected)) {
              return;
            }
          } finally {
            expected.binding.analysisOutputMutationLock.unlock();
          }
          mutation.run();
          committed.set(true);
        };
    boolean admitted;
    if (expected.kind == AnalysisOutputRouteKind.EXACT_CURRENT) {
      admitted =
          expected.activeExactContext != null
              && EngineManager.runIfCurrentEngineGameAnalysisOutputContext(
                  expected.activeExactContext, guardedMutation);
    } else if (expected.kind == AnalysisOutputRouteKind.GENMOVE_CURRENT) {
      admitted =
          expected.genmoveContext != null
              && EngineManager.runIfCurrentEngineGameMoveResponse(
                  expected.genmoveContext, guardedMutation);
    } else {
      admitted = EngineManager.runIfNoActiveEngineGameAnalysisOutput(guardedMutation);
    }
    return admitted && committed.get();
  }

  private boolean claimCurrentAnalysisOutputRoute(AnalysisOutputRoute expected) {
    if (expected == null || !expected.acceptsInfoLine()) {
      return false;
    }
    AtomicBoolean claimed = new AtomicBoolean();
    Runnable claim =
        () -> {
          if (!acquireAnalysisOutputMutationLock(expected)) {
            return;
          }
          try {
            claimed.set(hasSameAnalysisOutputOwnerLocked(expected));
          } finally {
            expected.binding.analysisOutputMutationLock.unlock();
          }
        };
    if (expected.kind == AnalysisOutputRouteKind.EXACT_CURRENT) {
      return expected.activeExactContext != null
          && EngineManager.runIfCurrentEngineGameAnalysisOutputContext(
              expected.activeExactContext, claim)
          && claimed.get();
    }
    if (expected.kind == AnalysisOutputRouteKind.GENMOVE_CURRENT) {
      return expected.genmoveContext != null
          && EngineManager.runIfCurrentEngineGameMoveResponse(expected.genmoveContext, claim)
          && claimed.get();
    }
    return EngineManager.runIfNoActiveEngineGameAnalysisOutput(claim) && claimed.get();
  }

  /** Binding-only owner check for callers that already hold the manager analysis admission. */
  private boolean hasCurrentAnalysisOutputOwner(AnalysisOutputRoute expected) {
    if (expected == null || !expected.acceptsInfoLine() || expected.binding == null) {
      return false;
    }
    expected.binding.analysisOutputMutationLock.lock();
    try {
      return hasSameAnalysisOutputOwnerLocked(expected);
    } finally {
      expected.binding.analysisOutputMutationLock.unlock();
    }
  }

  /**
   * Returns the per-engine analysis payload lock. Normal construction initializes it eagerly;
   * tests that intentionally use {@code Unsafe.allocateInstance} bypass field initializers, so the
   * null-only path restores the same per-instance ownership without weakening production locking.
   */
  private Object analysisInfoMutationLock() {
    Object lock = analysisInfoMutationLock;
    if (lock != null) {
      return lock;
    }
    synchronized (this) {
      lock = analysisInfoMutationLock;
      if (lock == null) {
        lock = new Object();
        analysisInfoMutationLock = lock;
      }
      return lock;
    }
  }

  private long analysisInfoEpochSnapshot() {
    synchronized (analysisInfoMutationLock()) {
      return analysisInfoEpoch;
    }
  }

  private AnalysisInfoSnapshot currentAnalysisInfoSnapshot() {
    synchronized (analysisInfoMutationLock()) {
      return new AnalysisInfoSnapshot(
          bestMoves, currentTotalPlayouts, analysisInfoEpoch, analysisInfoPayloadTarget);
    }
  }

  private AnalysisInfoSnapshot currentAnalysisInfoSnapshot(long expectedEpoch) {
    synchronized (analysisInfoMutationLock()) {
      return analysisInfoEpoch == expectedEpoch
          ? new AnalysisInfoSnapshot(
              bestMoves, currentTotalPlayouts, analysisInfoEpoch, analysisInfoPayloadTarget)
          : null;
    }
  }

  private boolean hasCurrentAnalysisInfoSnapshotLocked(AnalysisInfoSnapshot expected) {
    return expected != null
        && analysisInfoEpoch == expected.epoch
        && bestMoves == expected.moves
        && currentTotalPlayouts == expected.totalPlayouts
        && analysisInfoPayloadTarget == expected.target
        && isCurrentAnalysisInfoTarget(expected.target);
  }

  private boolean claimCurrentAnalysisInfoSnapshot(
      AnalysisOutputRoute route, AnalysisInfoSnapshot expected) {
    AtomicBoolean claimed = new AtomicBoolean();
    return runIfCurrentAnalysisOutputRoute(
            route,
            () -> {
              synchronized (analysisInfoMutationLock()) {
                claimed.set(hasCurrentAnalysisInfoSnapshotLocked(expected));
              }
            })
        && claimed.get();
  }

  /**
   * Invalidates one complete local analysis payload. The epoch advances only after the reset, so
   * an ingress parser either commits first and is overwritten, or observes the new epoch and
   * cannot revive the old state.
   */
  private void resetAnalysisInfoPayload(boolean resetScore) {
    synchronized (analysisInfoMutationLock()) {
      if (resetScore && isKatago) {
        scoreMean = 0;
        scoreStdev = 0;
      }
      currentTotalPlayouts = 0;
      analysisInfoPayloadTarget = null;
      bestMoves = List.of();
      analysisInfoEpoch++;
    }
  }

  /**
   * Retires the currently installed stream owner before clearing a position-dependent payload.
   * A late line from the old analysis can no longer adopt the successor payload epoch; only a
   * physically written analysis command can publish an owner for the new generation.
   */
  private void invalidateAnalysisInfoPayloadForLocalStateChange(boolean resetScore) {
    analysisOutputGeneration.incrementAndGet();
    resetAnalysisInfoPayload(resetScore);
  }

  /** Quarantines the old streaming owner before an asynchronously dispatched local play. */
  private void retireAnalysisInfoBeforeQueuedPlay() {
    invalidateAnalysisInfoPayloadForLocalStateChange(false);
  }

  private void applyAnalysisStateMutation(AnalysisStateMutation mutation) {
    if (mutation == null || mutation == AnalysisStateMutation.NONE) {
      return;
    }
    analysisOutputGeneration.incrementAndGet();
    if (mutation == AnalysisStateMutation.RETAIN_PAYLOAD) {
      advanceAnalysisInfoEpoch();
    } else {
      resetAnalysisInfoPayload(mutation == AnalysisStateMutation.RESET_PAYLOAD_AND_SCORE);
    }
  }

  /** Invalidates a retained payload (for example a komi-only BoardData clear). */
  private void advanceAnalysisInfoEpoch() {
    synchronized (analysisInfoMutationLock()) {
      analysisInfoEpoch++;
    }
  }

  private static final class AnalysisInfoMutationOutcome {
    private boolean committed;
    private boolean notifyAutoPk;
    private boolean notifyAutoPlay;
    private int autoAnalyzeMode;
    private EngineTransport remoteProgressTransport;
    private int acceptedRemotePlayouts;
    private boolean requestAnalysisRefresh;
    private boolean requestAnalysisTitleUpdate;
    private boolean sendStopCommand;
    private boolean showStopTips;
    private ParsedAnalysisInfo parsedInfo;
    private AnalysisInfoSnapshot acceptedSnapshot;
  }

  private AnalysisInfoMutationOutcome commitAnalysisInfoLine(
      AnalysisOutputRoute route,
      String line,
      boolean treatCurrentInfoAsPrimary,
      long expectedAnalysisInfoEpoch,
      AnalysisInfoTarget analysisInfoTarget) {
    AnalysisInfoMutationOutcome outcome = new AnalysisInfoMutationOutcome();
    final ParsedAnalysisInfo parsedInfo;
    try {
      parsedInfo = parseAnalysisInfoPayload(line.substring(5));
    } catch (RuntimeException malformedInfo) {
      // Treat malformed analysis diagnostics as an uncommitted line. In particular, do not let a
      // parser exception escape into engine-game response settlement.
      return outcome;
    }
    runIfCurrentAnalysisOutputRoute(
            route,
            () -> {
              synchronized (analysisInfoMutationLock()) {
                if (analysisInfoEpoch != expectedAnalysisInfoEpoch
                    || !isCurrentAnalysisInfoTarget(analysisInfoTarget)) {
                  return;
                }
                publishParsedAnalysisInfoLocked(parsedInfo, analysisInfoTarget);
                outcome.parsedInfo = parsedInfo;
                outcome.acceptedSnapshot =
                    new AnalysisInfoSnapshot(
                        this.bestMoves,
                        this.currentTotalPlayouts,
                        analysisInfoEpoch,
                        analysisInfoTarget);
                if (useRemoteCompute && remoteTransport != null) {
                  outcome.remoteProgressTransport = remoteTransport;
                  outcome.acceptedRemotePlayouts = parsedInfo.totalPlayouts;
                }
                if (treatCurrentInfoAsPrimary) {
                  YikeSyncDebugLog.log("Leelaz parseLine bestMoves size=" + this.bestMoves.size());
                }
                if (!this.bestMoves.isEmpty()) {
                  outcome.notifyAutoPk = route.acceptsExactEngineGameOutput();
                  outcome.notifyAutoPlay = route.acceptsOrdinaryOutput();
                  if (outcome.notifyAutoPlay && Lizzie.config.isAutoAna) {
                    outcome.autoAnalyzeMode =
                        Lizzie.frame.isAutoAnalyzingDiffNode
                            ? 1
                            : Lizzie.config.analyzeAllBranch ? 2 : 3;
                  }
                }
                outcome.requestAnalysisRefresh =
                    !EngineManager.isEngineGame || (!played && treatCurrentInfoAsPrimary);
                outcome.requestAnalysisTitleUpdate = !outcome.requestAnalysisRefresh;
                // don't follow the maxAnalyzeTime rule if we are in game
                if (!Lizzie.frame.isPlayingAgainstLeelaz
                    && !Lizzie.frame.isAnaPlayingAgainstLeelaz
                    && !EngineManager.isEngineGame
                    && !Lizzie.config.isAutoAna) {
                  boolean emptyBoard = Lizzie.board.getHistory().noStoneBoard();
                  if (!outOfPlayoutsLimit
                      && ((Lizzie.config.limitPlayout
                              && getBestMovesPlayouts() > Lizzie.config.limitPlayouts)
                          || (Lizzie.config.stopAtEmptyBoard && emptyBoard))) {
                    stopByLimit = true;
                    stopByPlayouts = true;
                    isPondering = !isPondering;
                    outcome.sendStopCommand = true;
                    outcome.showStopTips = !Lizzie.config.stopAtEmptyBoard && !emptyBoard;
                  } else if (Lizzie.config.limitTime
                      && System.currentTimeMillis() - startPonderTime
                          > Lizzie.config.maxAnalyzeTimeMillis) {
                    stopByLimit = true;
                    isPondering = !isPondering;
                    outcome.sendStopCommand = true;
                    outcome.showStopTips = true;
                  }
                }
                this.canCheckAlive = true;
                outcome.committed = true;
              }
            });
    if (outcome.committed) {
      runAnalysisInfoMutationEffects(route, outcome);
    }
    return outcome;
  }

  private void runAnalysisInfoMutationEffects(
      AnalysisOutputRoute route, AnalysisInfoMutationOutcome outcome) {
    if (outcome.parsedInfo != null && outcome.parsedInfo.kata && this == Lizzie.leelaz) {
      AnalysisResourceCoordinator.foregroundPlayoutSample(
          this, outcome.parsedInfo.totalPlayouts);
    }
    if (outcome.parsedInfo != null && outcome.parsedInfo.kata) {
      logTrialKataInfo(outcome.parsedInfo.moves, outcome.parsedInfo.totalPlayouts);
    }
    if (outcome.remoteProgressTransport != null) {
      outcome.remoteProgressTransport.markAnalysisProgressAccepted(outcome.acceptedRemotePlayouts);
    }
    if (outcome.requestAnalysisRefresh) {
      Lizzie.frame.requestAnalysisRefresh();
    } else if (outcome.requestAnalysisTitleUpdate) {
      Lizzie.frame.requestAnalysisTitleUpdate();
    }
    if (outcome.sendStopCommand) {
      boolean sent = runIfCurrentAnalysisOutputRoute(route, this::nameCmd);
      if (sent && outcome.showStopTips) {
        showStopPonderTips();
      }
    }
  }

  private boolean clearOutOfDateAnalysisInfo(AnalysisOutputRoute route) {
    return runIfCurrentAnalysisOutputRoute(
        route,
        () -> {
          synchronized (analysisInfoMutationLock()) {
            if (Lizzie.config.isAutoAna) {
              currentTotalPlayouts = 0;
              analysisInfoPayloadTarget = null;
              bestMoves = List.of();
              analysisInfoEpoch++;
              Lizzie.board.getHistory().getCurrentHistoryNode().getData().tryToClearBestMoves();
            }
          }
        });
  }

  private boolean handleLeela0110AnalysisSignal(
      AnalysisOutputRoute route,
      String line,
      long expectedAnalysisInfoEpoch,
      AnalysisInfoTarget analysisInfoTarget) {
    if (!isLeela0110
        || route == null
        || route.kind == AnalysisOutputRouteKind.GENMOVE_CURRENT
        || (!line.contains(" -> ") && !line.startsWith("====="))) {
      return false;
    }
    EngineManager.EngineGameTransaction routeTransaction =
        route.activeExactContext == null ? null : route.activeExactContext.transaction;
    AtomicBoolean refreshLoadedEngine = new AtomicBoolean();
    AtomicBoolean terminalBatch = new AtomicBoolean();
    AtomicBoolean hasBestMoves = new AtomicBoolean();
    AtomicReference<AnalysisInfoSnapshot> acceptedSnapshot = new AtomicReference<>();
    boolean committed =
        runIfCurrentAnalysisOutputRoute(
            route,
            () -> {
              if (line.contains(" -> ")) {
                MoveData move;
                try {
                  move = MoveData.fromSummaryLeela0110(line);
                } catch (RuntimeException malformedSummary) {
                  return;
                }
                synchronized (leela0110PonderStateLock) {
                  if (leela0110PonderingBinding != route.binding
                      || leela0110PonderingTransaction != routeTransaction
                      || leela0110PonderingStateToken == null
                      || leela0110BestMoves == null) {
                    return;
                  }
                  if (leela0110BestMovesEpoch != expectedAnalysisInfoEpoch) {
                    leela0110BestMoves = new ArrayList<>();
                    leela0110BestMovesEpoch = expectedAnalysisInfoEpoch;
                  }
                  if (!isLoaded) {
                    refreshLoadedEngine.set(true);
                  }
                  isLoaded = true;
                  int limit =
                      Lizzie.config.limitMaxSuggestion > 0
                              && !Lizzie.config.showNoSuggCircle
                          ? Lizzie.config.limitMaxSuggestion
                          : 361;
                  if (!Lizzie.frame.isPlayingAgainstLeelaz
                      && leela0110BestMoves.size() < limit
                      && move != null) {
                    move.order = leela0110BestMoves.size();
                    leela0110BestMoves.add(move);
                  }
                }
                return;
              }
              List<MoveData> completedMoves;
              synchronized (leela0110PonderStateLock) {
                if (leela0110PonderingBinding != route.binding
                    || leela0110PonderingTransaction != routeTransaction
                    || leela0110PonderingStateToken == null
                    || leela0110PonderingBoardData != Lizzie.board.getData()
                    || leela0110BestMoves == null) {
                  return;
                }
                if (leela0110BestMovesEpoch != expectedAnalysisInfoEpoch) {
                  leela0110BestMoves = new ArrayList<>();
                  leela0110BestMovesEpoch = expectedAnalysisInfoEpoch;
                }
                completedMoves = List.copyOf(leela0110BestMoves);
              }
              synchronized (analysisInfoMutationLock()) {
                if (analysisInfoEpoch != expectedAnalysisInfoEpoch) {
                  return;
                }
                if (!isCurrentAnalysisInfoTarget(analysisInfoTarget)) {
                  return;
                }
                canCheckAlive = true;
                int completedPlayouts = MoveData.getPlayouts(completedMoves);
                if (!completedMoves.isEmpty()) {
                  publishAnalysisDisplayNonFatal(
                      () -> {
                        if (Lizzie.config.isDoubleEngineMode()
                            && Lizzie.leelaz2 != null
                            && this == Lizzie.leelaz2) {
                          analysisInfoTarget
                              .displayNode
                              .getData()
                              .tryToSetBestMoves2FromEngine(
                                  new ArrayList<>(completedMoves),
                                  bestMovesEnginename,
                                  this,
                                  completedPlayouts,
                                  null);
                        } else {
                          analysisInfoTarget
                              .displayNode
                              .getData()
                              .tryToSetBestMovesFromEngine(
                                  new ArrayList<>(completedMoves),
                                  bestMovesEnginename,
                                  this,
                                  completedPlayouts,
                                  null,
                                  false);
                        }
                      });
                }
                currentTotalPlayouts = completedPlayouts;
                analysisInfoPayloadTarget = analysisInfoTarget;
                bestMoves = completedMoves;
                acceptedSnapshot.set(
                    new AnalysisInfoSnapshot(
                        bestMoves,
                        currentTotalPlayouts,
                        analysisInfoEpoch,
                        analysisInfoTarget));
                hasBestMoves.set(!completedMoves.isEmpty());
                terminalBatch.set(true);
              }
            });
    if (!committed) {
      return true;
    }
    if (refreshLoadedEngine.get()) {
      Lizzie.frame.refresh();
    }
    if (terminalBatch.get()) {
      Lizzie.frame.requestAnalysisRefresh();
      leela0110UpdatePonder(route);
      if (hasBestMoves.get()) {
        if (route.acceptsExactEngineGameOutput()) {
          routeExactAnalysisOutput(false, route, acceptedSnapshot.get());
        } else {
          int autoAnalyzeMode =
              !Lizzie.config.isAutoAna
                  ? 0
                  : Lizzie.frame.isAutoAnalyzingDiffNode
                      ? 1
                      : Lizzie.config.analyzeAllBranch ? 2 : 3;
          routeOrdinaryAnalysisOutput(
              false, autoAnalyzeMode, route, acceptedSnapshot.get());
        }
      }
    }
    return true;
  }

  private void parseLine(String line) {
    parseLine(line, captureEngineIncarnationFence());
  }

  private void parseLine(String line, Object sourceEngineIncarnation) {
    EngineManager.EngineGameTransaction engineGameStartupTransactionAtParse =
        engineGameStartupTransactionForLine(line, sourceEngineIncarnation);
    if (engineGameStartupTransactionAtParse != null
        && !EngineManager.isCurrentEngineGameTransaction(
            engineGameStartupTransactionAtParse)) {
      // The exact pending handler still owns protocol settlement, but a retired match must not
      // enter the ordinary name/version classifier or mutate a reused endpoint/configuration.
      isCommandLine = line != null && (line.startsWith("=") || line.startsWith("?"));
      return;
    }
    if (engineGameStartupTransactionAtParse != null) {
      // Test seam sits deliberately after the optimistic ingress check. The classifier itself
      // still reclaims the transaction mutation lock and revalidates immediately before commit.
      afterEngineGameStartupResponseOwnershipCapturedForTest(
          engineGameStartupTransactionAtParse);
    }
    boolean analysisSignalLine =
        line != null && (line.startsWith("info") || line.startsWith("| ST"));
    AnalysisOutputRoute analysisOutputRouteAtParse =
        analysisSignalLine ? analysisOutputRoute(sourceEngineIncarnation, line) : null;
    long analysisInfoEpochAtParse =
        analysisOutputRouteAtParse == null ? -1L : analysisInfoEpochSnapshot();
    AnalysisInfoTarget analysisInfoTargetAtParse =
        analysisOutputRouteAtParse == null ? null : captureAnalysisInfoTarget();
    if (analysisOutputRouteAtParse != null) {
      afterAnalysisOutputRouteCapturedForTest(analysisOutputRouteAtParse.kind.name());
      AnalysisOutputRoute currentRoute =
          analysisOutputRoute(sourceEngineIncarnation, line);
      if (!analysisOutputRouteAtParse.acceptsInfoLine()
          || !analysisOutputRouteAtParse.hasSameOwner(currentRoute)
          || !currentRoute.acceptsInfoLine()) {
        return;
      }
    }
    if (line.startsWith("| ST")) {
      String[] params = line.trim().split(" ");
      if (params.length != 13) {
        return;
      }
      final int parsedStage;
      final float parsedKomi;
      try {
        parsedStage = Integer.parseInt(params[3].substring(0, params[3].length() - 1));
        parsedKomi = Float.parseFloat(params[6].substring(0, params[6].length() - 1));
      } catch (RuntimeException malformedStatus) {
        return;
      }
      boolean committed =
          runIfCurrentAnalysisOutputRoute(
              analysisOutputRouteAtParse,
              () -> {
                isColorEngine = true;
                stage = parsedStage;
                komi = parsedKomi;
              });
      if (committed && (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp)) {
        Lizzie.gtpConsole.addLine(oriEnginename + ": " + line);
      }
      return;
    }
    if (TrialDiag.ENABLED && line.startsWith("info")) {
      // 只在试下激活时打 KataGo 原始 info 行第一段，限频
      if (Lizzie.engineFollowController != null && Lizzie.engineFollowController.isTrialActive()) {
        long now = System.currentTimeMillis();
        if (now - lastRawInfoLogMs > 500) {
          lastRawInfoLogMs = now;
          int end = line.indexOf(" pv ");
          String head =
              end < 0
                  ? line.substring(0, Math.min(line.length(), 200))
                  : line.substring(0, Math.min(end, 200));
          System.out.println("[trial-raw-info] " + head);
        }
      }
    }
    boolean handledInfoLine = false;
    boolean notifyAutoPKAfterInfo = false;
    boolean notifyAutoPlayAfterInfo = false;
    AnalysisInfoSnapshot acceptedAnalysisInfoAfterInfo = null;
    EngineManager.DeferredEngineGamePrimaryPublication primaryPublicationAfterInfo = null;
    boolean treatCurrentInfoAsPrimary = false;
    int autoAnalyzeAfterInfo = 0;
    StartupCommandAction startupCommandAction = StartupCommandAction.NONE;
    // Capture PRIMARY before entering this object's parser monitor. Deferred engine-game
    // publication must never form this-monitor -> PRIMARY, and the generation prevents a stale
    // reader line from reclaiming ownership after an away/back switch.
    EngineManager.EngineGamePrimaryContext engineGameContextAtParse =
        analysisOutputRouteAtParse == null
            ? EngineManager.captureEngineGamePrimaryContext(this, sourceEngineIncarnation)
            : analysisOutputRouteAtParse.activeExactContext;
    EngineManager engineManagerAtParse =
        engineGameContextAtParse == null ? null : engineGameContextAtParse.manager;
    EngineGameInfo engineGameInfoAtParse =
        engineGameContextAtParse == null ? null : engineGameContextAtParse.gameInfo;
    Board engineGameBoardAtParse = Lizzie.board;
    long engineGameBoardRevisionAtParse =
        engineGameBoardAtParse == null ? -1L : engineGameBoardAtParse.getContextRevision();
    boolean engineGameBlackToPlayAtParse =
        engineGameBoardAtParse != null && engineGameBoardAtParse.getHistory().isBlacksTurn();
    boolean engineGamePonderRoutingAtParse =
        engineGameContextAtParse != null && engineGameContextAtParse.ponderRouting;
    Leelaz primaryAtParse = Lizzie.leelaz;
    long primaryGenerationAtParse = Lizzie.capturePrimaryEngineGeneration(primaryAtParse);
    long startupPrimaryGenerationAtParse =
        captureStartupPrimaryGeneration(sourceEngineIncarnation);
    if (line.startsWith("info")) {
        EngineObservation.traceRawStream(loggingEngineId, null, line);
        boolean upToDate = isAnalysisResponseUpToDateSnapshot(analysisOutputRouteAtParse);
        afterAnalysisInfoAdmissionSnapshotCapturedForTest();
        treatCurrentInfoAsPrimary = this == primaryAtParse && primaryGenerationAtParse >= 0L;
        if (this == Lizzie.leelaz) {
          int[] responseWatermark = commandWatermarkSnapshot();
          YikeSyncDebugLog.log(
              "Leelaz parseLine info upToDate="
                  + upToDate
                  + " currentCmd="
                  + responseWatermark[1]
                  + " cmd="
                  + responseWatermark[0]
                  + " isPondering="
                  + isPondering);
        }
        if ((upToDate)) {
          if (engineGameContextAtParse != null
              && engineManagerAtParse != null
              && engineGameInfoAtParse != null
              && Lizzie.engineManager == engineManagerAtParse
              && EngineManager.engineGameInfo == engineGameInfoAtParse
              && engineGameInfoAtParse.blackEngineIndex >= 0
              && engineGameInfoAtParse.blackEngineIndex < engineManagerAtParse.engineList.size()
              && engineGameInfoAtParse.whiteEngineIndex >= 0
              && engineGameInfoAtParse.whiteEngineIndex < engineManagerAtParse.engineList.size()) {
            // Lizzie.frame.subBoardRenderer.reverseBestmoves = false;
            // Lizzie.frame.boardRenderer.reverseBestmoves = false;
            if (engineGamePonderRoutingAtParse) {
              if ((engineGameBlackToPlayAtParse
                      && this
                          == engineManagerAtParse.engineList.get(
                              engineGameInfoAtParse.blackEngineIndex))
                  || !engineGameBlackToPlayAtParse
                      && this
                          == engineManagerAtParse.engineList.get(
                              engineGameInfoAtParse.whiteEngineIndex)) {
                int expectedIndex =
                    engineGameBlackToPlayAtParse
                        ? engineGameInfoAtParse.blackEngineIndex
                        : engineGameInfoAtParse.whiteEngineIndex;
                primaryPublicationAfterInfo =
                    EngineManager.prepareEngineGamePrimaryPublication(
                        engineGameContextAtParse,
                        expectedIndex,
                        this,
                        primaryAtParse,
                        primaryGenerationAtParse,
                        sourceEngineIncarnation,
                        engineGameBoardAtParse,
                        engineGameBoardRevisionAtParse,
                        engineGameBlackToPlayAtParse);
                treatCurrentInfoAsPrimary = primaryPublicationAfterInfo != null;
              }
            } else {
              primaryPublicationAfterInfo =
                  EngineManager.prepareEngineGamePrimaryPublication(
                      engineGameContextAtParse,
                      currentEngineN,
                      this,
                      primaryAtParse,
                      primaryGenerationAtParse,
                      sourceEngineIncarnation,
                      engineGameBoardAtParse,
                      engineGameBoardRevisionAtParse,
                      engineGameBlackToPlayAtParse);
              treatCurrentInfoAsPrimary = primaryPublicationAfterInfo != null;
            }
          }
          // Clear switching prompt
          // switching = false;

          AnalysisInfoMutationOutcome outcome =
              commitAnalysisInfoLine(
                  analysisOutputRouteAtParse,
                  line,
                  treatCurrentInfoAsPrimary,
                  analysisInfoEpochAtParse,
                  analysisInfoTargetAtParse);
          if (!outcome.committed) {
            return;
          }
          notifyAutoPKAfterInfo = outcome.notifyAutoPk;
          notifyAutoPlayAfterInfo = outcome.notifyAutoPlay;
          autoAnalyzeAfterInfo = outcome.autoAnalyzeMode;
          acceptedAnalysisInfoAfterInfo = outcome.acceptedSnapshot;
        } else {
          if (!clearOutOfDateAnalysisInfo(analysisOutputRouteAtParse)) {
            return;
          }
        }
        handledInfoLine = true;
      } else {
        synchronized (this) {
        if (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp || !this.isLoaded)
          Lizzie.gtpConsole.addLine(line + "\n");
      //			if (Lizzie.engineManager.isEngineGame && this.isPondering) {
      //				Lizzie.engineManager.startInfoTime = System.currentTimeMillis();
      //			}
      if (isCheckingPda) {
        if (line.startsWith("pda:")) {
          isDymPda = true;
          String[] params = line.trim().split(" ");
          if (params.length == 2) {
            pda = Double.parseDouble(params[1]);
            LizzieFrame.menu.txtPDA.setText(String.format(Locale.ENGLISH, "%.3f", pda));
            if (LizzieFrame.menu.setPda != null)
              LizzieFrame.menu.setPda.curPDA.setText(String.format(Locale.ENGLISH, "%.3f", pda));
            if (Lizzie.config.chkAutoPDA) {
              sendCommand(Lizzie.config.AutoPDA);
              if (Lizzie.config.chkDymPDA) {
                this.pdaCap = Double.parseDouble(Lizzie.config.dymPDACap.trim());
                if (LizzieFrame.menu.setPda != null)
                  LizzieFrame.menu.setPda.txtDymCap.setText(Lizzie.config.dymPDACap);
              }
              if (Lizzie.config.chkStaticPDA) {
                LizzieFrame.menu.txtPDA.setText(Lizzie.config.staticPDAcur);
                isStaticPda = true;
                this.pda = Double.parseDouble(Lizzie.config.staticPDAcur.trim());
              } else {
                isStaticPda = false;
              }
            }
          }
          if (!EngineManager.isEngineGame && this == Lizzie.leelaz) ponder();
        }
        if (line.startsWith("PDACap:")) {
          String[] params = line.trim().split(" ");
          if (params.length == 2) {
            // if(pdaCap==0)
            pdaCap = Double.parseDouble(params[1]);
            if (pdaCap != 0 && !isStaticPda) {
              isStaticPda = false;
              Runnable syncDymPda =
                  new Runnable() {
                    public void run() {
                      int i = 0;
                      while (!canRestoreDymPda) {
                        try {
                          i++;
                          if (i > 19) break;
                          Thread.sleep(50);
                        } catch (InterruptedException e) {
                          // TODO Auto-generated catch block
                          e.printStackTrace();
                        }
                      }
                      canRestoreDymPda = false;
                      if (Lizzie.config.chkAutoPDA) sendCommand(Lizzie.config.AutoPDA);
                      else sendCommand("dympdacap " + pdaCap);
                      if (isPondering() || Lizzie.config.isDoubleEngineMode()) ponder();
                    }
                  };
              Thread syncDymPdaTh = new Thread(syncDymPda);
              syncDymPdaTh.start();
            } else {
              isStaticPda = true;
            }
            if (LizzieFrame.menu.setPda != null)
              LizzieFrame.menu.setPda.txtDymCap.setText(String.valueOf(pdaCap));
          }
        }
      }
      if (this.isKatago) {
        if (line.startsWith("PDA:")) {
          parsePDALine(line);
        }
      }
      // if (!this.isScreen&&line.startsWith("play")) {
      if (line.startsWith("play")) {
        // In lz-genmove_analyze
        String[] params = line.trim().split(" ");
        boolean shouldStopPonder =
            !isInputCommand && params.length == 2 && shouldStopPonderAfterEnginePlayLine();
        YikeSyncDebugLog.log(
            "Leelaz parse play line="
                + line
                + " isInputCommand="
                + isInputCommand
                + " isPonderingBefore="
                + isPondering
                + " shouldStopPonder="
                + shouldStopPonder
                + " playingAgainst="
                + (Lizzie.frame != null && Lizzie.frame.isPlayingAgainstLeelaz)
                + " autoPlaying="
                + (Lizzie.frame != null && Lizzie.frame.isAnaPlayingAgainstLeelaz)
                + " engineGame="
                + EngineManager.isEngineGame());
        ReadBoardGmaResponseBinding readBoardGmaBinding = currentReadBoardGmaResponseBinding();
        ReadBoard readBoardGmaOwner =
            readBoardGmaBinding == null ? null : readBoardGmaBinding.owner;
        if (!isInputCommand
            && params.length == 2
            && readBoardGmaOwner != null
            && readBoardGmaOwner.handleReadBoardGmaEnginePlay(
                readBoardGmaBinding.identity, readBoardGmaBinding.generation, params[1])) {
          boolean retainReadBoardNativePonder =
              readBoardGmaOwner.consumeReadBoardGmaNativePonderRetention();
          processCommandResponseLine(line);
          readBoardGmaOwner.afterReadBoardGmaTerminalResponseConsumed("play-terminal");
          clearReadBoardGmaResponseOwner(
              readBoardGmaOwner, readBoardGmaBinding.identity, readBoardGmaBinding.generation);
          isCommandLine = false;
          if (shouldStopPonder && !retainReadBoardNativePonder) {
            isPondering = false;
            YikeSyncDebugLog.log("Leelaz marked isPondering=false after ReadBoard GMA play line");
          }
          isThinking = false;
          return;
        }
        if (isInputCommand) {
          //	getGenmoveInfoPrevious = true;
          Lizzie.board.place(params[1]);
          if (isPondering) ponder();
          else {
            nameCmdfornoponder();
          }
        }
        if (Lizzie.frame.isPlayingAgainstLeelaz && isResponseUpToDate()) {
          if (params.length > 1) {
            if (params[1].toLowerCase().startsWith("resign")) {
              if (Lizzie.frame.playerIsBlack) {

                if (msg == null || !msg.isVisible()) {
                  msg = new Message();
                  msg.setMessage(Lizzie.resourceBundle.getString("Leelaz.blackWinAiResign"));
                  //     msg.setVisible(true);
                }
                GameInfo gameInfo = Lizzie.board.getHistory().getGameInfo();
                gameInfo.setResult(Lizzie.resourceBundle.getString("Leelaz.blackWin"));
                Lizzie.frame.setResult(Lizzie.resourceBundle.getString("Leelaz.blackWin"));

              } else {
                if (msg == null || !msg.isVisible()) {
                  msg = new Message();
                  msg.setMessage(Lizzie.resourceBundle.getString("Leelaz.whiteWinAiResign"));
                  //     msg.setVisible(true);
                }
                GameInfo gameInfo = Lizzie.board.getHistory().getGameInfo();
                gameInfo.setResult(Lizzie.resourceBundle.getString("Leelaz.whiteWin"));
                Lizzie.frame.setResult(Lizzie.resourceBundle.getString("Leelaz.whiteWin"));
              }
              togglePonder();
              return;
            }

            if (params[1].startsWith("pass")) {
              // getGenmoveInfoPrevious = true;
              Lizzie.board.pass();
              LizzieFrame.menu.toggleEngineMenuStatus(false, false);
            } else {
              // getGenmoveInfoPrevious = true;
              Lizzie.board.place(params[1]);
              LizzieFrame.menu.toggleEngineMenuStatus(false, false);
            }
          }
          if (!Lizzie.config.playponder) Lizzie.leelaz.nameCmdfornoponder();
        }
        if (shouldStopPonder) {
          isPondering = false;
          YikeSyncDebugLog.log("Leelaz marked isPondering=false after engine play line");
        }
        isThinking = false;
        if (isInputCommand) {
          isInputCommand = false;
        }
      } else if (line.startsWith("=")) {
        isCommandLine = true;
        if (startGetCommandList) {
          startGetCommandList = false;
          endGetCommandList = true;
          if (Lizzie.frame != null && Lizzie.frame.readBoard != null) {
            Lizzie.frame.readBoard.onReadBoardGmaCapabilityReady();
          }
        }
        String[] params = line.trim().split(" ");
        if (params.length == 1) return;
        if (!endGetCommandList && params.length == 2 && params[1].equals("protocol_version")) {
          startGetCommandList = true;
        }
        if (isInputCommand) {
          //	getGenmoveInfoPrevious = true;
          Lizzie.board.place(params[1]);
          if (isPondering) ponder();
          else this.nameCmdfornoponder();
          isInputCommand = false;
          isThinking = false;
        }
        if (isSettingHandicap) {
          Lizzie.board.hasStartStone = true;
          for (int i = 1; i < params.length; i++) {
            Optional<int[]> coordsOpt = Board.asCoordinates(params[i]);
            if (coordsOpt.isPresent()) {
              int[] coords = coordsOpt.get();
              Lizzie.board.getHistory().setStone(coords, Stone.BLACK);
              Lizzie.board.getHistory().getData().blackToPlay = false;
              Lizzie.board.setStartListStone(coords, true);
            }
          }
          isSettingHandicap = false;
          Lizzie.frame.allowPlaceStone = true;
          if (Lizzie.frame.isAnaPlayingAgainstLeelaz) {
            if (Lizzie.config.UsePureNetInGame && !Lizzie.leelaz.isheatmap)
              Lizzie.leelaz.toggleHeatmap(false);
            Lizzie.leelaz.Pondering();
            if (Lizzie.config.playponder
                || (Lizzie.board.getHistory().isBlacksTurn() && !Lizzie.frame.playerIsBlack)
                || (!Lizzie.board.getHistory().isBlacksTurn() && Lizzie.frame.playerIsBlack)) {
              Lizzie.leelaz.ponder();
            }
          }
          Lizzie.frame.refresh();
        } else if (isThinking && !isPondering) {
          if (isInputCommand) {
            Lizzie.board.place(params[1]);
            togglePonder();
          }
          if (Lizzie.frame.isPlayingAgainstLeelaz && isResponseUpToPreDate()) {
            if (params[1].startsWith("resign")) {
              if (Lizzie.frame.playerIsBlack) {

                if (msg == null || !msg.isVisible()) {
                  msg = new Message();
                  msg.setMessage(Lizzie.resourceBundle.getString("Leelaz.blackWinAiResign"));
                }
                GameInfo gameInfo = Lizzie.board.getHistory().getGameInfo();
                gameInfo.setResult(Lizzie.resourceBundle.getString("Leelaz.blackWin"));
                Lizzie.frame.setResult(Lizzie.resourceBundle.getString("Leelaz.blackWin"));

              } else {
                if (msg == null || !msg.isVisible()) {
                  msg = new Message();
                  msg.setMessage(Lizzie.resourceBundle.getString("Leelaz.whiteWinAiResign"));
                }
                GameInfo gameInfo = Lizzie.board.getHistory().getGameInfo();
                gameInfo.setResult(Lizzie.resourceBundle.getString("Leelaz.whiteWin"));
                Lizzie.frame.setResult(Lizzie.resourceBundle.getString("Leelaz.whiteWin"));
              }
              togglePonder();
              return;
            }
            if (params[1].toLowerCase().startsWith("pass")) {
              Lizzie.board.pass();
              LizzieFrame.menu.toggleEngineMenuStatus(false, false);
            } else {
              Optional<int[]> coords = Board.asCoordinates(params[1]);
              if (coords.isPresent()) {
                Lizzie.board.place(coords.get()[0], coords.get()[1]);
                LizzieFrame.menu.toggleEngineMenuStatus(false, false);
              }
            }
            if (!Lizzie.config.playponder) Lizzie.leelaz.nameCmdfornoponder();
          }
          isThinking = false;
          if (isInputCommand) {
            isInputCommand = false;
          }
        }
        startupCommandAction =
            mergeStartupCommandAction(
                startupCommandAction,
                checkNameAndVersion(
                    params,
                    startupPrimaryGenerationAtParse,
                    sourceEngineIncarnation,
                    engineGameStartupTransactionAtParse));
      } else if (line.startsWith("?")) {
        isCommandLine = true;
        if (consumeReadBoardGmaEngineErrorLine(line)) {
          return;
        }
        if (line.startsWith("? unacceptable komi")) {
          illegalKomi();
        }
      }
      parseHeatMap(line);
    }
    }
    if (primaryPublicationAfterInfo != null) {
      // Local info mutation has already released both the manager admission and this object's
      // monitor. Reclaim the route before PRIMARY publication so a same-binding successor cannot
      // inherit this line's deferred action.
      // Reclaim the exact analysis owner as well as the manager/game/slot and prior-primary fences:
      // a same-binding successor must not inherit this line's deferred PRIMARY publication.
      EngineManager.DeferredEngineGamePrimaryPublication frozenPrimaryPublication =
          primaryPublicationAfterInfo;
      beforeAnalysisPrimaryPublicationForTest();
      runIfCurrentAnalysisOutputRoute(
          analysisOutputRouteAtParse, frozenPrimaryPublication::publish);
    }
    runStartupCommandAction(startupCommandAction);
    if (handledInfoLine) {
      AnalysisOutputRoute terminalRoute = analysisOutputRoute(sourceEngineIncarnation, line);
      if (!analysisOutputRouteAtParse.hasSameOwner(terminalRoute)
          || !terminalRoute.acceptsInfoLine()) {
        return;
      }
      notifyAutoPKAfterInfo &= terminalRoute.acceptsExactEngineGameOutput();
      notifyAutoPlayAfterInfo &= terminalRoute.acceptsOrdinaryOutput();
      if (!terminalRoute.acceptsOrdinaryOutput()) {
        autoAnalyzeAfterInfo = 0;
      }
      runPostInfoActions(
          notifyAutoPKAfterInfo,
          notifyAutoPlayAfterInfo,
          autoAnalyzeAfterInfo,
          analysisOutputRouteAtParse,
          acceptedAnalysisInfoAfterInfo);
    }
  }

  void afterEngineGameStartupResponseOwnershipCapturedForTest(
      EngineManager.EngineGameTransaction transaction) {}

  void afterAnalysisOutputRouteCapturedForTest(String route) {}

  void afterAnalysisInfoAdmissionSnapshotCapturedForTest() {}

  void beforeAnalysisPrimaryPublicationForTest() {}

  void beforeAnalysisDisplayPublicationForTest() {}

  void beforeReaderBindingPublicationForTest() {}

  private void runPostInfoActions(
      boolean notifyAutoPKAfterInfo,
      boolean notifyAutoPlayAfterInfo,
      int autoAnalyzeAfterInfo,
      AnalysisOutputRoute route,
      AnalysisInfoSnapshot acceptedInfo) {
    if (notifyAutoPKAfterInfo && route != null && route.acceptsExactEngineGameOutput()) {
      routeExactAnalysisOutput(false, route, acceptedInfo);
    }
    if (notifyAutoPlayAfterInfo || autoAnalyzeAfterInfo != 0) {
      routeOrdinaryAnalysisOutput(false, autoAnalyzeAfterInfo, route, acceptedInfo);
    }
  }

  private void routeExactAnalysisOutput(boolean playImmediately, AnalysisOutputRoute route) {
    routeExactAnalysisOutput(playImmediately, route, currentAnalysisInfoSnapshot());
  }

  private void routeExactAnalysisOutput(
      boolean playImmediately,
      AnalysisOutputRoute route,
      AnalysisInfoSnapshot acceptedInfo) {
    if (route == null || !route.acceptsExactEngineGameOutput()) {
      return;
    }
    if (!claimCurrentAnalysisInfoSnapshot(route, acceptedInfo)) {
      return;
    }
    beforeAnalysisOutputActionForTest(true);
    // notifyAutoPKExact reclaims a move/turn token before every board or endpoint mutation.
    // Never hold the binding owner lock across those operations.
    notifyAutoPK(playImmediately, route.activeExactContext, acceptedInfo);
  }

  private void routeExactAnalysisOutput(
      boolean playImmediately, EngineManager.EngineGamePrimaryContext context) {
    beforeAnalysisOutputActionForTest(true);
    notifyAutoPK(playImmediately, context, currentAnalysisInfoSnapshot());
  }

  private void routeExactAnalysisSideEffect(AnalysisOutputRoute route, Runnable action) {
    if (route == null
        || action == null
        || !route.acceptsExactEngineGameOutput()) {
      return;
    }
    runIfCurrentAnalysisOutputRoute(route, action);
  }

  private void routeOrdinaryAnalysisOutput(boolean playImmediately) {
    routeOrdinaryAnalysisOutput(playImmediately, 0);
  }

  private void routeOrdinaryAnalysisOutput(boolean playImmediately, int autoAnalyzeMode) {
    AnalysisInfoSnapshot acceptedInfo = currentAnalysisInfoSnapshot();
    routeOrdinaryAnalysisSideEffect(
        () -> {
          notifyAutoPlay(playImmediately, acceptedInfo);
          if (autoAnalyzeMode == 1) {
            nofityDiffAna(acceptedInfo);
          } else if (autoAnalyzeMode == 2) {
            notifyAutoAnaAllBranch(acceptedInfo);
          } else if (autoAnalyzeMode == 3) {
            notifyAutoAna(acceptedInfo);
          }
        });
  }

  private void routeOrdinaryAnalysisOutput(
      boolean playImmediately, int autoAnalyzeMode, AnalysisOutputRoute route) {
    routeOrdinaryAnalysisOutput(
        playImmediately, autoAnalyzeMode, route, currentAnalysisInfoSnapshot());
  }

  private void routeOrdinaryAnalysisOutput(
      boolean playImmediately,
      int autoAnalyzeMode,
      AnalysisOutputRoute route,
      AnalysisInfoSnapshot acceptedInfo) {
    if (route == null || !route.acceptsOrdinaryOutput()) {
      return;
    }
    beforeOrdinaryAnalysisOutputAdmissionForTest();
    EngineManager.runIfNoActiveEngineGameAnalysisOutput(
        () -> {
          if (!hasCurrentAnalysisOutputOwner(route)) {
            return;
          }
          synchronized (analysisInfoMutationLock()) {
            if (!hasCurrentAnalysisInfoSnapshotLocked(acceptedInfo)) {
              return;
            }
          }
          beforeAnalysisOutputActionForTest(false);
          notifyAutoPlay(playImmediately, acceptedInfo);
          if (autoAnalyzeMode == 1) {
            nofityDiffAna(acceptedInfo);
          } else if (autoAnalyzeMode == 2) {
            notifyAutoAnaAllBranch(acceptedInfo);
          } else if (autoAnalyzeMode == 3) {
            notifyAutoAna(acceptedInfo);
          }
        });
  }

  private void routeOrdinaryAnalysisSideEffect(Runnable action) {
    if (action == null) {
      return;
    }
    beforeOrdinaryAnalysisOutputAdmissionForTest();
    EngineManager.runIfNoActiveEngineGameAnalysisOutput(
        () -> {
          beforeAnalysisOutputActionForTest(false);
          action.run();
        });
  }

  private void routeOrdinaryAnalysisSideEffect(
      AnalysisOutputRoute route, Runnable action) {
    if (route == null || action == null || !route.acceptsOrdinaryOutput()) {
      return;
    }
    beforeOrdinaryAnalysisOutputAdmissionForTest();
    EngineManager.runIfNoActiveEngineGameAnalysisOutput(
        () -> {
          if (!hasCurrentAnalysisOutputOwner(route)) {
            return;
          }
          beforeAnalysisOutputActionForTest(false);
          action.run();
        });
  }

  void beforeOrdinaryAnalysisOutputAdmissionForTest() {}

  void beforeAnalysisOutputActionForTest(boolean exactEngineGame) {}

  private boolean consumeReadBoardGmaEngineErrorLine(String line) {
    if (parseResponseCommandId(line) != NO_RESPONSE_COMMAND_ID) {
      return false;
    }
    ReadBoardGmaResponseBinding readBoardGmaBinding = currentReadBoardGmaResponseBinding();
    ReadBoard readBoardGmaOwner = readBoardGmaBinding == null ? null : readBoardGmaBinding.owner;
    if (readBoardGmaOwner == null
        || !readBoardGmaOwner.handleReadBoardGmaEngineError(
            readBoardGmaBinding.identity, readBoardGmaBinding.generation, line)) {
      return false;
    }
    processCommandResponseLine(line);
    readBoardGmaOwner.afterReadBoardGmaTerminalResponseConsumed("error-terminal");
    clearReadBoardGmaResponseOwner(
        readBoardGmaOwner, readBoardGmaBinding.identity, readBoardGmaBinding.generation);
    isThinking = false;
    isCommandLine = false;
    return true;
  }

  private void illegalKomi() {
    Utils.showMsgNoModal(
        currentEnginename + ": " + Lizzie.resourceBundle.getString("Leelaz.unacceptableKomi"));
  }

  private void parsePDALine(String line) {
    String[] params = line.trim().split(" ");
    if (params.length == 2) {
      pda = Double.parseDouble(params[1]);
      LizzieFrame.menu.txtPDA.setText(String.format(Locale.ENGLISH, "%.3f", pda));
      if (LizzieFrame.menu.setPda != null)
        LizzieFrame.menu.setPda.curPDA.setText(String.format(Locale.ENGLISH, "%.3f", pda));
    }
  }

  private void showStopPonderTips() {
    // TODO Auto-generated method stub
    if (!Lizzie.config.showPonderLimitedTips) return;
    if (!showStopTips) return;
    showStopTips = false;
    SwingUtilities.invokeLater(this::showStopPonderTipsOnEdt);
  }

  private void showStopPonderTipsOnEdt() {
    Box box = Box.createVerticalBox();
    JFontLabel label = new JFontLabel(Lizzie.resourceBundle.getString("leelaz.stopByLimit"));
    label.setAlignmentX(Component.LEFT_ALIGNMENT);
    box.add(label);
    Utils.addFiller(box, 5, 5);
    Utils.addFiller(box, 5, 5);
    JFontLabel label2 = new JFontLabel(Lizzie.resourceBundle.getString("leelaz.stopByLimit2"));
    label2.setAlignmentX(Component.LEFT_ALIGNMENT);
    box.add(label2);
    Utils.addFiller(box, 5, 5);
    JFontCheckBox disableCheckBox =
        new JFontCheckBox(Lizzie.resourceBundle.getString("LizzieFrame.noNoticeAgain"));
    disableCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);
    box.add(disableCheckBox);
    JOptionPane optionPane = new JOptionPane(box, JOptionPane.INFORMATION_MESSAGE);
    JDialog dialog =
        optionPane.createDialog(
            Lizzie.frame, Lizzie.resourceBundle.getString("leelaz.stopByLimitTitle"));
    AtomicBoolean preferenceSaved = new AtomicBoolean(false);
    dialog.setModal(false);
    dialog.setAlwaysOnTop(true);
    dialog.setAutoRequestFocus(false);
    dialog.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
    optionPane.addPropertyChangeListener(
        event -> {
          if (!dialog.isVisible() || event.getSource() != optionPane) return;
          String propertyName = event.getPropertyName();
          if (JOptionPane.VALUE_PROPERTY.equals(propertyName)
              || JOptionPane.INPUT_VALUE_PROPERTY.equals(propertyName)) {
            saveStopPonderTipsPreference(disableCheckBox, preferenceSaved);
            dialog.dispose();
          }
        });
    dialog.addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosed(java.awt.event.WindowEvent e) {
            saveStopPonderTipsPreference(disableCheckBox, preferenceSaved);
          }
        });
    dialog.setVisible(true);
  }

  private void saveStopPonderTipsPreference(
      JFontCheckBox disableCheckBox, AtomicBoolean preferenceSaved) {
    if (!preferenceSaved.compareAndSet(false, true)) return;
    if (disableCheckBox.isSelected()) {
      Lizzie.config.showPonderLimitedTips = false;
      Lizzie.config.uiConfig.put("show-ponder-limited-tips", Lizzie.config.showPonderLimitedTips);
    }
  }

  private void notifyAutoPlay(
      boolean playImmediately, AnalysisInfoSnapshot acceptedInfo) {
    List<MoveData> acceptedMoves =
        acceptedInfo == null ? List.of() : acceptedInfo.moves;
    int acceptedPlayouts = acceptedInfo == null ? 0 : acceptedInfo.totalPlayouts;
    if (this != Lizzie.leelaz) return;
    if (Lizzie.frame != null
        && Lizzie.frame.readBoard != null
        && Lizzie.frame.readBoard.isReadBoardGmaAutoPlayActive()) return;
    if (LizzieFrame.toolbar.isAutoPlay) {
      if ((Lizzie.board.getHistory().isBlacksTurn()
              && LizzieFrame.toolbar.chkAutoPlayBlack.isSelected())
          || (!Lizzie.board.getHistory().isBlacksTurn()
              && LizzieFrame.toolbar.chkAutoPlayWhite.isSelected())) {
        int time = 0;
        int playouts = 0;
        int firstPlayouts = 0;
        if (LizzieFrame.toolbar.chkAutoPlayTime.isSelected()) {
          try {
            time =
                1000
                    * Integer.parseInt(
                        LizzieFrame.toolbar.txtAutoPlayTime.getText().replace(" ", ""));
          } catch (NumberFormatException err) {
          }
        }
        if (LizzieFrame.toolbar.chkAutoPlayPlayouts.isSelected()) {
          try {
            playouts =
                Integer.parseInt(
                    LizzieFrame.toolbar.txtAutoPlayPlayouts.getText().replace(" ", ""));
          } catch (NumberFormatException err) {
          }
        }
        if (LizzieFrame.toolbar.chkAutoPlayFirstPlayouts.isSelected()) {
          try {
            firstPlayouts =
                Integer.parseInt(
                    LizzieFrame.toolbar.txtAutoPlayFirstPlayouts.getText().replace(" ", ""));
          } catch (NumberFormatException err) {
          }
        }
        boolean playNow = false;
        if (playImmediately) playNow = true;
        if (firstPlayouts > 0) {
          if (!acceptedMoves.isEmpty()
              && acceptedMoves.get(0).playouts >= firstPlayouts) {
            playNow = true;
          }
        }
        if (playouts > 0) {
          if (acceptedPlayouts >= playouts) {
            playNow = true;
          }
        }

        if (time > 0) {
          if (System.currentTimeMillis() - startPonderTime >= time) {
            playNow = true;
          }
        }
        if (playNow) {
          if (acceptedMoves.isEmpty() || notifyAnaResign(false, acceptedMoves)) return;
          MoveData playMove = null;
          if (!Lizzie.frame.bothSync
              && Lizzie.config.enableAnaGameRamdonStart
              && Lizzie.board.getHistory().getMoveNumber() <= Lizzie.config.anaGameRandomMove)
            playMove =
                this.randomBestmove(
                    acceptedMoves, Lizzie.config.anaGameRandomWinrateDiff, true);
          else playMove = acceptedMoves.get(0);

          int coords[] = Board.convertNameToCoordinates(playMove.coordinate);
          Lizzie.board.place(coords[0], coords[1]);
          if ((Lizzie.board.getData().blackToPlay
                  && LizzieFrame.toolbar.chkAutoPlayBlack.isSelected())
              || (!Lizzie.board.getData().blackToPlay
                  && LizzieFrame.toolbar.chkAutoPlayWhite.isSelected())) {
            Lizzie.board.place(coords[0], coords[1]);
          }
          if (Lizzie.frame.bothSync) {
            if (!Lizzie.config.readBoardPonder) nameCmd();
            else ponder();
          } else if (!Lizzie.config.playponder) {
            nameCmd();
          } else ponder();
        }
      }
    }
  }

  private boolean notifyAnaResign(boolean isResgined, List<MoveData> acceptedMoves) {
    // TODO Auto-generated method stub
    if (isResgined) {
      Lizzie.frame.togglePonderMannul();
      Utils.showMsg(oriEnginename + " " + Lizzie.resourceBundle.getString("Leelaz.resign"));
    } else if (Lizzie.frame.isAnaPlayingAgainstLeelaz && !Lizzie.frame.bothSync) {
      if (Lizzie.board.getHistory().getMoveNumber() >= Lizzie.config.anaGameResignStartMove) {
        if (!acceptedMoves.isEmpty()
            && acceptedMoves.get(0).winrate < Lizzie.config.anaGameResignPercent) {
          this.anaGameResignCount++;
        } else this.anaGameResignCount = 0;
      }
      if (this.anaGameResignCount >= Lizzie.config.anaGameResignMove) {
        Lizzie.frame.togglePonderMannul();
        Utils.showMsg(oriEnginename + " " + Lizzie.resourceBundle.getString("Leelaz.resign"));
        return true;
      }
    }
    return isResgined;
  }

  public void analyzeNextMove(boolean isLastMove) {
    autoAnalysed = true;
    Lizzie.board.getHistory().getCurrentHistoryNode().analyzed = true;
    invalidateAnalysisInfoPayloadForLocalStateChange(false);
    if (isLastMove) {
      LizzieFrame.toolbar.stopAutoAna(true, false);
    } else {
      Lizzie.board.nextMove(true);
    }
  }

  private void nofityDiffAna(AnalysisInfoSnapshot acceptedInfo) {
    // TODO Auto-generated method stub
    List<MoveData> acceptedMoves =
        acceptedInfo == null ? List.of() : acceptedInfo.moves;
    int acceptedPlayouts = acceptedInfo == null ? 0 : acceptedInfo.totalPlayouts;
    if (this != Lizzie.leelaz) return;
    if (Lizzie.config.autoAnaDiffFirstPlayouts > 0) {
      if (!acceptedMoves.isEmpty()
          && acceptedMoves.get(0).playouts >= Lizzie.config.autoAnaDiffFirstPlayouts) {
        Lizzie.board.getHistory().getCurrentHistoryNode().diffAnalyzed = true;
        return;
      }
    }
    if ((isZen && Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber < 3)) {
      Lizzie.board.getHistory().getCurrentHistoryNode().diffAnalyzed = true;
      return;
    }
    if (Lizzie.config.autoAnaDiffPlayouts > 0) {
      if (acceptedPlayouts >= Lizzie.config.autoAnaDiffPlayouts) {
        Lizzie.board.getHistory().getCurrentHistoryNode().diffAnalyzed = true;
        return;
      }
    }

    if (Lizzie.config.autoAnaDiffTime > 0) {
      long curTime = System.currentTimeMillis();
      if (curTime - startPonderTime >= Lizzie.config.autoAnaDiffTime * 1000) {
        Lizzie.board.getHistory().getCurrentHistoryNode().diffAnalyzed = true;
        return;
      }
    }
  }

  public void notifyAutoAnaAllBranch() {
    notifyAutoAnaAllBranch(currentAnalysisInfoSnapshot());
  }

  private void notifyAutoAnaAllBranch(AnalysisInfoSnapshot acceptedInfo) {
    List<MoveData> acceptedMoves =
        acceptedInfo == null ? List.of() : acceptedInfo.moves;
    int acceptedPlayouts = acceptedInfo == null ? 0 : acceptedInfo.totalPlayouts;
    if (this != Lizzie.leelaz) return;
    if (Lizzie.board.getHistory().isBlacksTurn() && !Lizzie.config.anaBlack) {
      Lizzie.board.getHistory().getCurrentHistoryNode().analyzed = true;
      return;
    }
    if (!Lizzie.board.getHistory().isBlacksTurn() && !Lizzie.config.anaWhite) {
      Lizzie.board.getHistory().getCurrentHistoryNode().analyzed = true;
      return;
    }
    if (Lizzie.config.autoAnaFirstPlayouts > 0) {
      if (!acceptedMoves.isEmpty()
          && acceptedMoves.get(0).playouts >= Lizzie.config.autoAnaFirstPlayouts) {
        Lizzie.board.getHistory().getCurrentHistoryNode().analyzed = true;
        autoAnalysed = true;
        return;
      }
    }
    if ((isZen && Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber < 3)) {
      Lizzie.board.getHistory().getCurrentHistoryNode().analyzed = true;
      autoAnalysed = true;
      return;
    }
    if (Lizzie.config.autoAnaPlayouts > 0) {
      if (acceptedPlayouts >= Lizzie.config.autoAnaPlayouts) {
        Lizzie.board.getHistory().getCurrentHistoryNode().analyzed = true;
        autoAnalysed = true;
        return;
      }
    }

    if (Lizzie.config.autoAnaTime > 0) {
      long curTime = System.currentTimeMillis();
      if (curTime - startPonderTime >= Lizzie.config.autoAnaTime) {
        Lizzie.board.getHistory().getCurrentHistoryNode().analyzed = true;
        autoAnalysed = true;
        return;
      }
    }
  }

  public void notifyAutoAna() {
    notifyAutoAna(currentAnalysisInfoSnapshot());
  }

  private void notifyAutoAna(AnalysisInfoSnapshot acceptedInfo) {
    List<MoveData> acceptedMoves =
        acceptedInfo == null ? List.of() : acceptedInfo.moves;
    int acceptedPlayouts = acceptedInfo == null ? 0 : acceptedInfo.totalPlayouts;
    if (this != Lizzie.leelaz) return;
    if (Lizzie.config.autoAnaEndMove != -1) {
      if (Lizzie.config.autoAnaEndMove < Lizzie.board.getHistory().getData().moveNumber) {
        LizzieFrame.toolbar.stopAutoAna(true, false);
        return;
      }
    }
    boolean isLastMove = !Lizzie.board.getHistory().getNext().isPresent();
    if (Lizzie.board.getHistory().isBlacksTurn() && !Lizzie.config.anaBlack) {
      analyzeNextMove(isLastMove);
      return;
    }
    if (!Lizzie.board.getHistory().isBlacksTurn() && !Lizzie.config.anaWhite) {
      analyzeNextMove(isLastMove);
      return;
    }
    if (Lizzie.config.autoAnaFirstPlayouts > 0) {
      if (!acceptedMoves.isEmpty()
          && acceptedMoves.get(0).playouts >= Lizzie.config.autoAnaFirstPlayouts) {
        analyzeNextMove(isLastMove);
        return;
      }
    }
    if ((isZen && Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber < 3)) {
      analyzeNextMove(isLastMove);
      return;
    }
    if (Lizzie.config.autoAnaPlayouts > 0) {
      if (acceptedPlayouts >= Lizzie.config.autoAnaPlayouts) {
        analyzeNextMove(isLastMove);
        return;
      }
    }

    if (Lizzie.config.autoAnaTime > 0) {
      long curTime = System.currentTimeMillis();
      if (curTime - startPonderTime >= Lizzie.config.autoAnaTime) {
        analyzeNextMove(isLastMove);
        return;
      }
    }
  }

  public void genmoveResign(boolean needPass) {
    // if(resigned)
    //	return;
    synchronized (analysisInfoMutationLock()) {
      List<MoveData> acceptedMoves = bestMoves;
      if (!acceptedMoves.isEmpty()) {
        int acceptedPlayouts = MoveData.getPlayouts(acceptedMoves);
        currentTotalPlayouts = acceptedPlayouts;
        publishAnalysisDisplayNonFatal(
            () ->
                Lizzie.board
                    .getHistory()
                    .getData()
                    .tryToSetBestMovesFromEngine(
                        new ArrayList<>(acceptedMoves),
                        bestMovesEnginename,
                        this,
                        acceptedPlayouts,
                        null,
                        false));
      }
    }
    this.resigned = true;
    if (!this.doublePass
        && !this.outOfMoveNum
        && (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp))
      Lizzie.gtpConsole.addLine(
          oriEnginename + " " + Lizzie.resourceBundle.getString("Leelaz.resign") + "\n");
    Lizzie.board.updateComment();
    if (needPass) Lizzie.board.pass();
    Lizzie.engineManager.stopEngineGame(currentEngineN, false);
  }

  //	public void resignGame() {
  //		if (!resigned || isResigning)
  //			return;
  //		isResigning = true;
  //		if(Lizzie.gtpConsole.isVisible()||Lizzie.config.alwaysGtp)
  //		Lizzie.gtpConsole.addLine(oriEnginename+ resourceBundle.getString("Leelaz.resign")+"\n");
  //	Lizzie.engineManager.stopEngineGame(currentEngineN, false);
  //	}

  private void notifyAutoPK(boolean playImmediately) {
    notifyAutoPK(playImmediately, null, currentAnalysisInfoSnapshot());
  }

  private void notifyAutoPK(
      boolean playImmediately, EngineManager.EngineGamePrimaryContext exactGame) {
    notifyAutoPK(playImmediately, exactGame, currentAnalysisInfoSnapshot());
  }

  private void notifyAutoPK(
      boolean playImmediately,
      EngineManager.EngineGamePrimaryContext exactGame,
      AnalysisInfoSnapshot acceptedInfo) {
    if (exactGame != null) {
      notifyAutoPKExact(playImmediately, exactGame, acceptedInfo);
      return;
    }
    List<MoveData> acceptedMoves =
        acceptedInfo == null ? List.of() : acceptedInfo.moves;
    int acceptedPlayouts = acceptedInfo == null ? 0 : acceptedInfo.totalPlayouts;
    if (!EngineManager.isEngineGame || played || LizzieFrame.toolbar.isPkStop) {
      return;
    }
    if (resigned) {
      nameCmd();
      isResigning = true;
      if (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp)
        Lizzie.gtpConsole.addLine(
            oriEnginename + " " + Lizzie.resourceBundle.getString("Leelaz.resign") + "\n");
      Lizzie.engineManager.stopEngineGame(currentEngineN, false);
      return;
    }
    boolean shouldPlay = false;
    int time = 0;
    int playouts = 0;
    int firstPlayouts = 0;
    int minMove = 0;
    int resginMoveCounts = 2;
    double resignWinrate = 10.0;
    MoveData best;
    try {
      best = acceptedMoves.get(0);
    } catch (Exception e) {
      e.printStackTrace();
      return;
    }
    double curWR = best.winrate;
    int thisIdx = currentEngineN;
    int blackIdx = EngineManager.engineGameInfo.blackEngineIndex;
    // int whiteIdx=Lizzie.engineManager.engineGameInfo.whiteEngineIndex;
    boolean isBlackEngine = thisIdx == blackIdx;
    if (isBlackEngine) {
      time = EngineManager.engineGameInfo.timeBlack * 1000;
      playouts = EngineManager.engineGameInfo.playoutsBlack;
      firstPlayouts = EngineManager.engineGameInfo.firstPlayoutsBlack;
      minMove = EngineManager.engineGameInfo.blackMinMove;
      resginMoveCounts = EngineManager.engineGameInfo.blackResignMoveCounts;
      resignWinrate = EngineManager.engineGameInfo.blackResignWinrate;
    } else {
      time = EngineManager.engineGameInfo.timeWhite * 1000;
      playouts = EngineManager.engineGameInfo.playoutsWhite;
      firstPlayouts = EngineManager.engineGameInfo.firstPlayoutsWhite;
      minMove = EngineManager.engineGameInfo.whiteMinMove;
      resginMoveCounts = EngineManager.engineGameInfo.whiteResignMoveCounts;
      resignWinrate = EngineManager.engineGameInfo.whiteResignWinrate;
    }
    if (Lizzie.board.getHistory().isBlacksTurn() && !isBlackEngine
        || !Lizzie.board.getHistory().isBlacksTurn() & isBlackEngine) return;
    if (Lizzie.board.getHistory().getMoveNumber()
        > EngineManager.engineGameInfo.getMaxGameMoves()) {
      outOfMoveNum = true;
      resigned = true;
    }
    if (isZen) {
      if (Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber < 3)
        shouldPlay = true;
    }
    if (playImmediately || playNow) shouldPlay = true;
    if (firstPlayouts > 0 && best.playouts >= firstPlayouts) shouldPlay = true;
    if (firstPlayouts > 0 && best.playouts >= firstPlayouts) shouldPlay = true;
    if (playouts > 0) {
      if (acceptedPlayouts >= playouts) shouldPlay = true;
    }
    if (time > 0) {
      if (System.currentTimeMillis() - startPonderTime >= time) {
        shouldPlay = true;
      }
    }
    if (shouldPlay) {
      played = true;
      playNow = false;
      if ((curWR < resignWinrate) && Lizzie.board.getHistory().getMoveNumber() > minMove) {
        if (isBlackEngine) {
          blackResignMoveCounts = blackResignMoveCounts + 1;
          if (blackResignMoveCounts >= resginMoveCounts) resigned = true;
        } else {
          whiteResignMoveCounts = whiteResignMoveCounts + 1;
          if (whiteResignMoveCounts >= resginMoveCounts) resigned = true;
        }
      } else {
        if (isBlackEngine) blackResignMoveCounts = 0;
        else whiteResignMoveCounts = 0;
      }
      if (!resigned) {
        MoveData playMove = null;
        if (LizzieFrame.toolbar.isRandomMove
            && Lizzie.board.getHistory().getMoveNumber() <= LizzieFrame.toolbar.randomMove)
          playMove =
              this.randomBestmove(
                  acceptedMoves, LizzieFrame.toolbar.randomDiffWinrate, false);
        else playMove = best;
        int coords[] = Board.convertNameToCoordinates(playMove.coordinate);
        if (coords[0] >= 0 && coords[1] >= 0) {
          Lizzie.board.place(coords[0], coords[1]);
        } else {
          if (!Lizzie.board.getLastMove().isPresent()) {
            doublePass = true;
            resigned = true;
          }
          Lizzie.board.pass();
        }
        if (!resigned) {
          if (isBlackEngine) {
            Lizzie.engineManager
                .engineList
                .get(EngineManager.engineGameInfo.blackEngineIndex)
                .playMoveNoPonder("B", playMove.coordinate);
            Lizzie.engineManager
                .engineList
                .get(EngineManager.engineGameInfo.whiteEngineIndex)
                .playMovePonder("B", playMove.coordinate);
          } else {
            Lizzie.engineManager
                .engineList
                .get(EngineManager.engineGameInfo.whiteEngineIndex)
                .playMoveNoPonder("W", playMove.coordinate);
            Lizzie.engineManager
                .engineList
                .get(EngineManager.engineGameInfo.blackEngineIndex)
                .playMovePonder("W", playMove.coordinate);
          }
        }
      }
      checkForGomokuFullBoard(false);
    }
    if (resigned) {
      nameCmd();
      isResigning = true;
      if (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp)
        Lizzie.gtpConsole.addLine(
            oriEnginename + " " + Lizzie.resourceBundle.getString("Leelaz.resign") + "\n");
      Lizzie.engineManager.stopEngineGame(currentEngineN, false);
    }
  }

  private void notifyAutoPKExact(
      boolean playImmediately,
      EngineManager.EngineGamePrimaryContext game,
      AnalysisInfoSnapshot acceptedInfo) {
    if (game == null
        || game.gameInfo.isGenmove
        || game.participant != this
        || played
        || LizzieFrame.toolbar.isPkStop) {
      return;
    }
    EngineManager.EngineGameMoveResponseContext moveContext =
        EngineManager.captureEngineGameAnalysisMoveContext(game);
    if (moveContext == null) {
      return;
    }
    if (resigned) {
      finishExactEngineGameAnalysis(moveContext, AnalysisGameTerminal.RESIGN);
      return;
    }
    if (game.moveNumber > game.gameInfo.getMaxGameMoves()) {
      finishExactEngineGameAnalysis(moveContext, AnalysisGameTerminal.MAX_MOVES);
      return;
    }
    List<MoveData> acceptedMoves =
        acceptedInfo == null ? List.of() : acceptedInfo.moves;
    int acceptedPlayouts = acceptedInfo == null ? 0 : acceptedInfo.totalPlayouts;
    MoveData best;
    try {
      best = acceptedMoves.get(0);
    } catch (RuntimeException noMove) {
      return;
    }
    EngineGameInfo frozenGame = game.gameInfo;
    boolean blackParticipant = game.participantIndex == game.blackIndex;
    int timeMillis =
        (blackParticipant ? frozenGame.timeBlack : frozenGame.timeWhite) * 1000;
    int playoutLimit =
        blackParticipant ? frozenGame.playoutsBlack : frozenGame.playoutsWhite;
    int firstPlayoutLimit =
        blackParticipant ? frozenGame.firstPlayoutsBlack : frozenGame.firstPlayoutsWhite;
    boolean shouldPlay =
        playImmediately
            || playNow
            || (firstPlayoutLimit > 0 && best.playouts >= firstPlayoutLimit)
            || (playoutLimit > 0 && acceptedPlayouts >= playoutLimit)
            || (timeMillis > 0 && System.currentTimeMillis() - startPonderTime >= timeMillis)
            || (isZen && game.moveNumber < 3);
    if (!shouldPlay) {
      return;
    }
    int minMove =
        blackParticipant ? frozenGame.blackMinMove : frozenGame.whiteMinMove;
    int requiredResignMoves =
        blackParticipant
            ? frozenGame.blackResignMoveCounts
            : frozenGame.whiteResignMoveCounts;
    double resignWinrate =
        blackParticipant ? frozenGame.blackResignWinrate : frozenGame.whiteResignWinrate;
    AtomicBoolean resignNow = new AtomicBoolean();
    if (!EngineManager.runIfCurrentEngineGameMoveResponse(
        moveContext,
        () -> {
          if (best.winrate < resignWinrate && game.moveNumber > minMove) {
            if (blackParticipant) {
              blackResignMoveCounts++;
              resignNow.set(blackResignMoveCounts >= requiredResignMoves);
            } else {
              whiteResignMoveCounts++;
              resignNow.set(whiteResignMoveCounts >= requiredResignMoves);
            }
          } else if (blackParticipant) {
            blackResignMoveCounts = 0;
          } else {
            whiteResignMoveCounts = 0;
          }
        })) {
      return;
    }
    if (resignNow.get()) {
      finishExactEngineGameAnalysis(moveContext, AnalysisGameTerminal.RESIGN);
      return;
    }
    MoveData chosen =
        LizzieFrame.toolbar.isRandomMove && game.moveNumber <= LizzieFrame.toolbar.randomMove
            ? randomBestmove(acceptedMoves, LizzieFrame.toolbar.randomDiffWinrate, false)
            : best;
    if (chosen == null || chosen.coordinate == null) {
      EngineManager.failEngineGameTransaction(
          game.transaction, new IllegalStateException("Engine-game analysis produced no move"));
      return;
    }
    Optional<int[]> coordinate = Board.asCoordinates(chosen.coordinate);
    boolean pass = chosen.coordinate.equalsIgnoreCase("pass");
    if (!pass
        && (!coordinate.isPresent() || coordinate.get()[0] < 0 || coordinate.get()[1] < 0)) {
      EngineManager.failEngineGameTransaction(
          game.transaction,
          new IllegalStateException(
              "Engine-game analysis returned an invalid coordinate: " + chosen.coordinate));
      return;
    }
    EngineManager.EngineGamePostMoveToken postMove =
        EngineManager.commitEngineGameMove(
            moveContext,
            pass ? null : coordinate.get()[0],
            pass ? null : coordinate.get()[1],
            this,
            game.participantIndex);
    if (postMove == null) {
      return;
    }
    boolean doublePassNow =
        pass && game.boardNode != null && game.boardNode.getData().isPassNode();
    EngineManager.runIfCurrentEngineGameTransaction(
        game.transaction,
        () -> {
          played = true;
          playNow = false;
        });
    if (doublePassNow) {
      finishExactEngineGameAnalysis(
          postMove, game.participantIndex, AnalysisGameTerminal.DOUBLE_PASS);
      return;
    }
    if (EngineManager.isExactEngineGameBoardFull(postMove)) {
      finishExactEngineGameAnalysis(
          postMove, game.participantIndex, AnalysisGameTerminal.MAX_MOVES);
      return;
    }
    if (!EngineManager.isCurrentEngineGamePostMoveToken(postMove)) {
      return;
    }
    String color = blackParticipant ? "B" : "W";
    String move = pass ? "pass" : chosen.coordinate;
    Leelaz opponent = blackParticipant ? game.whiteEngine : game.blackEngine;
    if (!playEngineGameAnalysisMove(color, move, postMove, false)) {
      return;
    }
    if (!EngineManager.isCurrentEngineGamePostMoveToken(postMove)) {
      return;
    }
    opponent.playEngineGameAnalysisMove(color, move, postMove, true);
  }

  private enum AnalysisGameTerminal {
    RESIGN,
    DOUBLE_PASS,
    MAX_MOVES
  }

  private void finishExactEngineGameAnalysis(
      EngineManager.EngineGamePrimaryContext game, AnalysisGameTerminal terminal) {
    finishExactEngineGameAnalysis(
        EngineManager.captureEngineGameAnalysisMoveContext(game), terminal);
  }

  private void finishExactEngineGameAnalysis(
      EngineManager.EngineGameMoveResponseContext context, AnalysisGameTerminal terminal) {
    if (context == null) {
      return;
    }
    if (!EngineManager.runIfCurrentEngineGameMoveResponse(
        context, () -> markEngineGameAnalysisTerminal(terminal))) {
      return;
    }
    EngineManager.stopEngineGameIfCurrent(
        context.transaction, context.participantIndex, false);
  }

  private void finishExactEngineGameAnalysis(
      EngineManager.EngineGamePostMoveToken turn,
      int participantIndex,
      AnalysisGameTerminal terminal) {
    if (!EngineManager.runIfCurrentEngineGamePostMoveToken(
        turn, () -> markEngineGameAnalysisTerminal(terminal))) {
      return;
    }
    EngineManager.stopEngineGameIfCurrent(turn.transaction, participantIndex, false);
  }

  private void markEngineGameAnalysisTerminal(AnalysisGameTerminal terminal) {
    resigned = true;
    isResigning = true;
    if (terminal == AnalysisGameTerminal.DOUBLE_PASS) {
      doublePass = true;
    } else if (terminal == AnalysisGameTerminal.MAX_MOVES) {
      outOfMoveNum = true;
    }
  }

  public void nameCmd() {
    if (isKatago) sendCommand("stop");
    else sendCommand("name");
    LizzieFrame.menu.toggleEngineMenuStatus(false, false);
  }

  public void boardSize(int width, int height) {
    String command =
        width != height
            ? "rectangular_boardsize " + width + " " + height
            : "boardsize " + width;
    long admission =
        sendStatefulOrdinaryCommands(
            List.of(command), StatefulOrdinaryMutationKind.BOARD_SIZE);
    if (admission < 0L) return;
    publishCurrentStatefulOrdinaryAdmission(
        StatefulOrdinaryMutationKind.BOARD_SIZE,
        admission,
        () -> {
          applyBoardSize(width, height, false);
          Lizzie.board.reopen(width, height);
        });
  }

  public void boardSizeForEngine(int width, int height) {
    if (width != height) sendCommand("rectangular_boardsize " + width + " " + height);
    else sendCommand("boardsize " + width);
    applyBoardSize(width, height, false);
  }

  void boardSizeForEngineGame(
      EngineManager.EngineGameTransaction transaction, int width, int height) {
    if (transaction == null) {
      boardSizeForEngine(width, height);
      return;
    }
    requireCurrentEngineGameStartupTransaction(transaction);
    if (width != height) {
      sendCommand("rectangular_boardsize " + width + " " + height);
    } else {
      sendCommand("boardsize " + width);
    }
    if (!EngineManager.runIfCurrentEngineGameTransaction(
        transaction,
        () -> {
          this.width = width;
          this.height = height;
          // This process has completed its own one-time board setup. Engine-game bootstrap must
          // never publish its local komi as the application's global default.
          firstLoad = false;
        })) {
      throw new IllegalStateException(
          "engine-game startup transaction retired before board-size settlement");
    }
  }

  private void applyBoardSize(int width, int height, boolean reopenMainBoard) {
    this.width = width;
    this.height = height;
    if (reopenMainBoard) Lizzie.board.reopen(width, height);
    if (firstLoad) {
      if (shouldApplyInitialEngineKomiToCurrentGame()) {
        Lizzie.board.getHistory().getGameInfo().setKomi(komi);
      }
      GameInfo.DEFAULT_KOMI = (double) komi;
      firstLoad = false;
    }
  }

  private boolean shouldApplyInitialEngineKomiToCurrentGame() {
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      return false;
    }
    BoardHistoryList history = Lizzie.board.getHistory();
    BoardHistoryNode start = history.getStart();
    if (start == null || start.getData() == null) {
      return false;
    }
    if (start.next(true).isPresent()) {
      return false;
    }
    BoardData data = start.getData();
    if (!data.getProperties().isEmpty()) {
      return false;
    }
    if (data.stones != null) {
      for (Stone stone : data.stones) {
        if (stone != null && (stone.isBlack() || stone.isWhite())) {
          return false;
        }
      }
    }
    return true;
  }

  public void komi(double komi) {
    String command = "komi " + (komi == 0.0 ? "0" : komi);
    long admission =
        sendStatefulOrdinaryCommands(List.of(command), StatefulOrdinaryMutationKind.KOMI);
    if (admission < 0L) return;
    boolean published =
        publishCurrentStatefulOrdinaryAdmission(
            StatefulOrdinaryMutationKind.KOMI,
            admission,
            () -> {
              this.komi = (float) komi;
              Lizzie.board.getHistory().getGameInfo().setKomi(komi);
              //  Lizzie.board.getHistory().getGameInfo().changeKomi();
              Lizzie.board.clearBestMovesAfter(Lizzie.board.getHistory().getStart());
            });
    if (published && isPondering) {
      ponder();
    }
  }

  /**
   * Board-clear engine forwarding with a single lifecycle admission: atomically queues the whole
   * clear_board + komi pair, so a competing exclusive transition cannot interleave between them and
   * leave a cleared engine with a stale komi. Endpoint locks are released before queue drain or
   * transport work. Rejected as a group when exclusive lifecycle work (e.g. the initial startup
   * restore barrier) owns the engine. Preserves
   * the original forwarding semantics per path: komi mirrors to the secondary engine when supplied
   * (null komiCommand = clear-only, as in the SGF editor clear path), the regular clear path also
   * applies the gameInfo komi and best-move invalidation side effects of {@link #komi(double)}, and
   * a single re-ponder fires when already pondering (the legacy chain could analyze the
   * intermediate cleared board twice before the komi landed; the final state is identical). The
   * caller supplies the exact komi command serialization.
   */
  public boolean forwardBoardClearWithKomi(
      String komiCommand, double komi, boolean applyKomiSideEffects) {
    List<String> commands =
        komiCommand == null ? List.of("clear_board") : List.of("clear_board", komiCommand);
    StatefulOrdinaryMutationKind mutationKind =
        komiCommand == null
            ? StatefulOrdinaryMutationKind.NONE
            : StatefulOrdinaryMutationKind.KOMI;
    long admission = sendStatefulOrdinaryCommands(commands, mutationKind);
    if (admission < 0L) {
      return false;
    }
    if (komiCommand != null) {
      publishCurrentStatefulOrdinaryAdmission(
          StatefulOrdinaryMutationKind.KOMI,
          admission,
          () -> {
            this.komi = (float) komi;
            if (applyKomiSideEffects) {
              Lizzie.board.getHistory().getGameInfo().setKomi(komi);
              Lizzie.board.clearBestMovesAfter(Lizzie.board.getHistory().getStart());
            }
          });
    }
    if (isPondering) ponder();
    return true;
  }

  public void komiNoMenu(double komi) {
    String command = "komi " + (komi == 0.0 ? "0" : komi);
    long admission =
        sendStatefulOrdinaryCommands(List.of(command), StatefulOrdinaryMutationKind.KOMI);
    if (admission < 0L) return;
    boolean published =
        publishCurrentStatefulOrdinaryAdmission(
            StatefulOrdinaryMutationKind.KOMI,
            admission,
            () -> {
              this.komi = (float) komi;
              Lizzie.board.getHistory().getGameInfo().setKomiNoMenu(komi);
              //  Lizzie.board.getHistory().getGameInfo().changeKomi();
              Lizzie.board.clearBestMovesAfter(Lizzie.board.getHistory().getStart());
            });
    if (published && isPondering) {
      ponder();
    }
  }

  /**
   * Aligns the running engine with the displayed game's komi without changing the engine default or
   * discarding analysis embedded in a loaded SGF.
   */
  public boolean syncKomiForCurrentGame(double komi) {
    float normalizedKomi = (float) (komi == 0.0 ? 0.0 : komi);
    if (Float.compare(this.komi, normalizedKomi) == 0) {
      return false;
    }
    String command = "komi " + (komi == 0.0 ? "0" : komi);
    long admission =
        sendStatefulOrdinaryCommands(List.of(command), StatefulOrdinaryMutationKind.KOMI);
    if (admission < 0L) {
      return false;
    }
    publishCurrentStatefulOrdinaryAdmission(
        StatefulOrdinaryMutationKind.KOMI, admission, () -> this.komi = normalizedKomi);
    return true;
  }

  public void nameCmdfornoponder() {
    YikeSyncDebugLog.log(
        "Leelaz nameCmdfornoponder isKatago="
            + isKatago
            + " isPondering="
            + isPondering
            + " caller="
            + buildPonderCallerTrace());
    if (isKatago) sendCommand("stop");
    else sendCommand("name");
  }

  boolean nameCmdfornoponder(EngineManager.EngineGamePostMoveToken turn) {
    YikeSyncDebugLog.log(
        "Leelaz exact engine-game nameCmdfornoponder isKatago="
            + isKatago
            + " isPondering="
            + isPondering);
    return sendEngineGameCommand(isKatago ? "stop" : "name", turn);
  }

  private void readError() {
    readError(currentReaderStreamBinding());
  }

  private void readError(ReaderStreamBinding binding) {
    String line = "";
    try {
      while ((line = binding.stderr.readLine()) != null) {
        if (!beginReaderLine(binding)) {
          return;
        }
        try {
          if (TrialDiag.ENABLED && line != null && !line.isEmpty()) {
            System.out.println("[katago-stderr] " + line);
          }
          rememberRecentLine(recentStderrLines, line);
          try {
            parseLineForError(line, binding);
          } catch (Exception e) {
            e.printStackTrace();
          }
          if (binding.terminated) {
            return;
          }
        } finally {
          endReaderLine(binding);
        }
      }
    } catch (IOException | RuntimeException failure) {
      if (isCurrentReaderStreamBinding(binding)) {
        EngineObservation.recordTransportFailure(
            loggingEngineId,
            "stderr",
            failure instanceof IOException ? "io-error" : "reader-error",
            failure);
      }
    }
  }

  private void parseLineForError(String line, ReaderStreamBinding binding) {
    // TODO Auto-generated method stub
    EngineManager.EngineGamePrimaryContext presentationGameContext =
        EngineManager.captureEngineGamePrimaryContext(this, binding);
    if (suppressesGlobalEnginePresentation(binding) && presentationGameContext == null) {
      // Preserve the one internal readiness signal needed to extend the bundled startup deadline,
      // but quarantine all ordinary stderr analysis, board, console, dialog, and autoplay routes.
      if (!isLoaded
          && (line.startsWith("Started OpenCL SGEMM")
              || line.startsWith("Tuning xGemmDirect")
              || line.contains("long time"))) {
        isTuning = true;
      }
      return;
    }
    if (!this.isLoaded) {
      if (line.toLowerCase().contains("cl_platform_not_found"))
        Utils.showMsgNoModal(Lizzie.resourceBundle.getString("Leelaz.openclPlatfromNotFound"));
    }
    if (!this.isLeela0110 || Lizzie.frame.isPlayingAgainstLeelaz)
      if (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp || !this.isLoaded)
        if (!line.startsWith("info")) Lizzie.gtpConsole.addErrorLine(line + "\n");
    EngineManager.EngineGameMoveResponseContext engineGameResponse =
        isZen ? pendingEngineGameMoveResponseContext(line, binding) : null;
    AnalysisOutputRoute analysisOutputRouteAtParse = analysisOutputRoute(binding, line);
    long analysisInfoEpochAtParse = analysisInfoEpochSnapshot();
    boolean analysisResponseUpToDateAtParse =
        isAnalysisResponseUpToDateSnapshot(analysisOutputRouteAtParse);
    AnalysisInfoTarget analysisInfoTargetAtParse = captureAnalysisInfoTarget();
    EngineManager.EngineGamePrimaryContext analysisGameContext =
        analysisOutputRouteAtParse.activeExactContext;
    boolean engineGameSignal = isAnalysisOutputSignalLine(line);
    EngineManager.EngineGameMoveResponseLease engineGameResponseLease =
        engineGameSignal
            ? EngineManager.claimEngineGameMoveResponse(engineGameResponse)
            : null;
    if (engineGameSignal && engineGameResponseLease == null) {
      afterAnalysisOutputRouteCapturedForTest(analysisOutputRouteAtParse.kind.name());
      AnalysisOutputRoute currentRoute = analysisOutputRoute(binding, line);
      if (!analysisOutputRouteAtParse.acceptsInfoLine()
          || !analysisOutputRouteAtParse.hasSameOwner(currentRoute)
          || !currentRoute.acceptsInfoLine()) {
        return;
      }
    }
    try {
    if (isZen) {
      boolean exactAnalysisGame =
          analysisOutputRouteAtParse.acceptsExactEngineGameOutput();
      boolean ordinaryAutoPlay =
          analysisOutputRouteAtParse.acceptsOrdinaryOutput() && LizzieFrame.toolbar.isAutoPlay;
      if (exactAnalysisGame || ordinaryAutoPlay) {
        if (analysisResponseUpToDateAtParse) {
          if (line.contains("Nodes:")) {
            AnalysisInfoSnapshot terminalInfo =
                currentAnalysisInfoSnapshot(analysisInfoEpochAtParse);
            if (terminalInfo == null
                || !isCurrentAnalysisInfoTarget(analysisInfoTargetAtParse)) {
              return;
            }
            if (!terminalInfo.moves.isEmpty()) {
              if (exactAnalysisGame) {
                routeExactAnalysisOutput(
                    true, analysisOutputRouteAtParse, terminalInfo);
              } else {
                routeOrdinaryAnalysisOutput(
                    true, 0, analysisOutputRouteAtParse, terminalInfo);
              }
            } else {
              if (exactAnalysisGame) {
                EngineManager.EngineGamePrimaryContext frozenContext = analysisGameContext;
                routeExactAnalysisSideEffect(
                    analysisOutputRouteAtParse,
                    () -> playPassInEngineGame(frozenContext));
              } else {
                routeOrdinaryAnalysisSideEffect(
                    analysisOutputRouteAtParse, () -> Lizzie.board.pass());
              }
            }
          } else if (line.contains("I pass")) {
            if (exactAnalysisGame) {
              EngineManager.EngineGamePrimaryContext frozenContext = analysisGameContext;
              routeExactAnalysisSideEffect(
                  analysisOutputRouteAtParse,
                  () -> playPassInEngineGame(frozenContext));
            } else {
              routeOrdinaryAnalysisSideEffect(
                  analysisOutputRouteAtParse, () -> Lizzie.board.pass());
            }
          } else if (line.toLowerCase().contains("resign")) {
            if (exactAnalysisGame) {
              EngineManager.EngineGamePrimaryContext frozenContext = analysisGameContext;
              routeExactAnalysisSideEffect(
                  analysisOutputRouteAtParse,
                  () ->
                      finishExactEngineGameAnalysis(
                          frozenContext, AnalysisGameTerminal.RESIGN));
            } else {
              routeOrdinaryAnalysisSideEffect(
                  analysisOutputRouteAtParse,
                  () -> notifyAnaResign(true, List.of()));
            }
          }
        }
      }
      if (line.startsWith("info") && isLoaded) {
        if (analysisOutputRouteAtParse.acceptsOrdinaryOutput()) {
          runIfCurrentAnalysisOutputRoute(
              analysisOutputRouteAtParse,
              () -> {
                isLoaded = false;
                if (Lizzie.frame != null && Lizzie.frame.isDisplayable()) {
                  SwingUtilities.invokeLater(
                      () ->
                          Utils.showHtmlMessage(
                              Lizzie.resourceBundle.getString("Message.title"),
                              Lizzie.resourceBundle.getString("Leelaz.updateZenGtp"),
                              Lizzie.frame));
                }
                terminateReaderIncarnation(binding, null);
              });
        }
        return;
      }
      if (engineGameResponseLease != null && engineGameResponse.gameInfo.isGenmove) {
        if (line.contains("->")) {
          MoveData parsedMove = null;
          try {
            parsedMove = MoveData.fromSummaryZen(line);
          } catch (RuntimeException malformedSummary) {
            Lizzie.gtpConsole.addLine("genmovepk summary err");
          }
          if (parsedMove != null) {
            MoveData acceptedMove = parsedMove;
            runIfCurrentAnalysisOutputRoute(
                analysisOutputRouteAtParse,
                () -> {
                  synchronized (analysisInfoMutationLock()) {
                    if (analysisInfoEpoch == analysisInfoEpochAtParse
                        && isCurrentAnalysisInfoTarget(analysisInfoTargetAtParse)) {
                      MoveData mv = acceptedMove;
                      List<MoveData> updatedMoves = new ArrayList<>(bestMoves);
                      mv.order = updatedMoves.size();
                      updatedMoves.add(mv);
                      List<MoveData> publishedMoves = List.copyOf(updatedMoves);
                      currentTotalPlayouts = MoveData.getPlayouts(publishedMoves);
                      analysisInfoPayloadTarget = analysisInfoTargetAtParse;
                      bestMoves = publishedMoves;
                    }
                  }
                });
          }
        }
      }

      if ((Lizzie.frame.isPlayingAgainstLeelaz || isInputCommand)
          && analysisOutputRouteAtParse.acceptsOrdinaryOutput()) {
        if (line.contains("->")) {
          int k =
              (Lizzie.config.limitMaxSuggestion > 0 && !Lizzie.config.showNoSuggCircle
                  ? Lizzie.config.limitMaxSuggestion
                  : 361);
          MoveData parsedMove = null;
          try {
            parsedMove = MoveData.fromSummaryZen(line);
          } catch (RuntimeException malformedSummary) {
            // Malformed diagnostic output is ignored without failing the current owner.
          }
          if (parsedMove != null) {
            MoveData acceptedMove = parsedMove;
            runIfCurrentAnalysisOutputRoute(
                analysisOutputRouteAtParse,
                () -> {
                  synchronized (analysisInfoMutationLock()) {
                    if (analysisInfoEpoch == analysisInfoEpochAtParse
                        && analysisResponseUpToDateAtParse
                        && isCurrentAnalysisInfoTarget(analysisInfoTargetAtParse)
                        && bestMoves.size() < k) {
                      MoveData mv = acceptedMove;
                      List<MoveData> updatedMoves = new ArrayList<>(bestMoves);
                      mv.order = updatedMoves.size();
                      updatedMoves.add(mv);
                      int updatedPlayouts = MoveData.getPlayouts(updatedMoves);
                      publishAnalysisDisplayNonFatal(
                          () ->
                              analysisInfoTargetAtParse
                                  .displayNode
                                  .getData()
                                  .tryToSetBestMovesFromEngine(
                                      new ArrayList<>(updatedMoves),
                                      bestMovesEnginename,
                                      this,
                                      updatedPlayouts,
                                      null,
                                      false));
                      List<MoveData> publishedMoves = List.copyOf(updatedMoves);
                      currentTotalPlayouts = updatedPlayouts;
                      analysisInfoPayloadTarget = analysisInfoTargetAtParse;
                      bestMoves = publishedMoves;
                    }
                  }
                });
          }
        }
      }
    }
    if ((isLeela || isSai) && Lizzie.frame.isPlayingAgainstLeelaz && canGetSummaryInfo) {
      int k =
          (Lizzie.config.limitMaxSuggestion > 0 && !Lizzie.config.showNoSuggCircle
              ? Lizzie.config.limitMaxSuggestion
              : 361);
      MoveData parsedSummary = null;
      if (line.contains("->") && analysisResponseUpToDateAtParse) {
        try {
          parsedSummary = isSai ? MoveData.fromSummarySai(line) : MoveData.fromSummary(line);
        } catch (RuntimeException malformedSummary) {
          Lizzie.gtpConsole.addLine("genmovepk summary err");
        }
      }
      if (parsedSummary != null) {
        MoveData acceptedSummary = parsedSummary;
        Board summaryBoard = Lizzie.board;
        BoardHistoryNode summaryTarget =
            summaryBoard == null
                ? null
                : summaryBoard.getHistory().getCurrentHistoryNode();
        runIfCurrentAnalysisOutputRoute(
            analysisOutputRouteAtParse,
            () -> {
              synchronized (previousAnalysisSummaryLock) {
                AnalysisSummaryBatch batch = previousAnalysisSummaryBatch;
                if (batch == null
                    || !batch.route.hasSameOwner(analysisOutputRouteAtParse)
                    || batch.analysisInfoEpoch != analysisInfoEpochAtParse
                    || batch.board != summaryBoard
                    || batch.targetNode != summaryTarget) {
                  batch =
                      new AnalysisSummaryBatch(
                          analysisOutputRouteAtParse,
                          analysisInfoEpochAtParse,
                          summaryBoard,
                          summaryTarget,
                          List.of());
                }
                if (batch.moves.size() >= k) {
                  previousAnalysisSummaryBatch = batch;
                  return;
                }
                List<MoveData> updatedMoves = new ArrayList<>(batch.moves);
                acceptedSummary.order = updatedMoves.size();
                updatedMoves.add(acceptedSummary);
                previousAnalysisSummaryBatch =
                    new AnalysisSummaryBatch(
                        batch.route,
                        batch.analysisInfoEpoch,
                        batch.board,
                        batch.targetNode,
                        List.copyOf(updatedMoves));
              }
            });
      }
    }
    if (handleLeela0110AnalysisSignal(
        analysisOutputRouteAtParse,
        line,
        analysisInfoEpochAtParse,
        analysisInfoTargetAtParse)) {
      return;
    }
    if (isLeela0110) {
      if (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp || !this.isLoaded) {
        Lizzie.gtpConsole.addErrorLine(line + "\n");
      }
    }
    if (!this.isKatago) {
      if (line.startsWith("NN eval")) {
        runIfCurrentAnalysisOutputRoute(
            analysisOutputRouteAtParse,
            () -> {
              String[] params = line.trim().split("=");
              heatwinrate =
                  Double.valueOf(
                      params[1].length() > 5 ? params[1].substring(0, 5) : params[1]);
            });
      }
      if (line.startsWith("root eval")) {
        runIfCurrentAnalysisOutputRoute(
            analysisOutputRouteAtParse,
            () -> {
              String[] params = line.trim().split("=");
              heatwinrate =
                  Double.valueOf(
                      params[1].length() > 5 ? params[1].substring(0, 5) : params[1]);
            });
      }

      if (line.endsWith("nodes")) {
        AnalysisInfoSnapshot terminalInfo =
            currentAnalysisInfoSnapshot(analysisInfoEpochAtParse);
        if (terminalInfo != null
            && isCurrentAnalysisInfoTarget(analysisInfoTargetAtParse)
            && !terminalInfo.moves.isEmpty()) {
          if (analysisOutputRouteAtParse.acceptsExactEngineGameOutput()
              && analysisResponseUpToDateAtParse
              && !isGamePaused) {
            routeExactAnalysisOutput(
                true, analysisOutputRouteAtParse, terminalInfo);
          } else if (analysisOutputRouteAtParse.acceptsOrdinaryOutput()
              && Lizzie.frame.isAnaPlayingAgainstLeelaz
              && !isGamePaused) {
            routeOrdinaryAnalysisOutput(
                true, 0, analysisOutputRouteAtParse, terminalInfo);
          }
        }
      }
      if (line.startsWith("| ST")) {
        String[] params = line.trim().split(" ");
        if (params.length == 13) {
          Integer parsedStage = null;
          Float parsedKomi = null;
          try {
            parsedStage =
                Integer.parseInt(params[3].substring(0, params[3].length() - 1));
            parsedKomi = Float.parseFloat(params[6].substring(0, params[6].length() - 1));
          } catch (RuntimeException malformedStatus) {
            // Malformed stderr diagnostics are ignored without failing the current response.
          }
          if (parsedStage != null && parsedKomi != null) {
            int acceptedStage = parsedStage;
            float acceptedKomi = parsedKomi;
            boolean committed =
                runIfCurrentAnalysisOutputRoute(
                    analysisOutputRouteAtParse,
                    () -> {
                      isColorEngine = true;
                      stage = acceptedStage;
                      komi = acceptedKomi;
                    });
            if (committed && (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp)) {
              Lizzie.gtpConsole.addLine(oriEnginename + ": " + line);
            }
          }
        }
      }
    } else {
      if ((Lizzie.frame.isPlayingAgainstLeelaz || EngineManager.isEngineGame)
          && line.startsWith("MALKOVICH:")) {
        if (line.contains("PDA")) {
          Double parsedPda = null;
          try {
            String value = line.substring(line.indexOf("PDA") + 4);
            value = value.substring(0, value.indexOf(")"));
            parsedPda = Double.parseDouble(value);
          } catch (RuntimeException malformedPda) {
            // A malformed diagnostic line is not an engine-game protocol failure.
          }
          if (parsedPda != null) {
            double acceptedPda = parsedPda;
            runIfCurrentAnalysisOutputRoute(
                analysisOutputRouteAtParse, () -> this.pda = acceptedPda);
          }
        }
      }
    }
    if (!isLoaded) {
      if (line.startsWith("Started OpenCL SGEMM")
          || line.startsWith("Tuning xGemmDirect")
          || line.contains("long time")) {
        isTuning = true;
      }
    }
    parseHeatMap(line);
    } finally {
      if (engineGameResponseLease != null) {
        engineGameResponseLease.close();
      }
    }
  }

  private void playPassInEngineGame() {
    // TODO Auto-generated method stub
    played = true;
    Lizzie.board.pass();
    boolean isBlackEngine = currentEngineN == EngineManager.engineGameInfo.blackEngineIndex;
    if (isBlackEngine) {
      Lizzie.engineManager
          .engineList
          .get(EngineManager.engineGameInfo.blackEngineIndex)
          .playMoveNoPonder("B", "pass");
      Lizzie.engineManager
          .engineList
          .get(EngineManager.engineGameInfo.whiteEngineIndex)
          .playMovePonder("B", "pass");
    } else {
      Lizzie.engineManager
          .engineList
          .get(EngineManager.engineGameInfo.whiteEngineIndex)
          .playMoveNoPonder("W", "pass");
      Lizzie.engineManager
          .engineList
          .get(EngineManager.engineGameInfo.blackEngineIndex)
          .playMovePonder("W", "pass");
    }
  }

  private void playPassInEngineGame(EngineManager.EngineGamePrimaryContext game) {
    EngineManager.EngineGameMoveResponseContext moveContext =
        EngineManager.captureEngineGameAnalysisMoveContext(game);
    if (moveContext == null) {
      return;
    }
    boolean blackParticipant = game.participantIndex == game.blackIndex;
    EngineManager.EngineGamePostMoveToken postMove =
        EngineManager.commitEngineGameMove(
            moveContext, null, null, this, game.participantIndex);
    if (postMove == null) {
      return;
    }
    boolean doublePassNow =
        game.boardNode != null && game.boardNode.getData().isPassNode();
    EngineManager.runIfCurrentEngineGameTransaction(game.transaction, () -> played = true);
    if (doublePassNow) {
      finishExactEngineGameAnalysis(
          postMove, game.participantIndex, AnalysisGameTerminal.DOUBLE_PASS);
      return;
    }
    String color = blackParticipant ? "B" : "W";
    Leelaz opponent = blackParticipant ? game.whiteEngine : game.blackEngine;
    if (!playEngineGameAnalysisMove(color, "pass", postMove, false)) {
      return;
    }
    if (EngineManager.isCurrentEngineGamePostMoveToken(postMove)) {
      opponent.playEngineGameAnalysisMove(color, "pass", postMove, true);
    }
  }

  boolean hasTrackingStreamSession() {
    synchronized (engineArbitrationLock()) {
      return isTrackingStreamSession(exclusiveGtpSession);
    }
  }

  public boolean isPonderingOrWasPonderingBeforeTracking() {
    synchronized (engineArbitrationLock()) {
      return isPondering()
          || (exclusiveGtpSession != null
              && exclusiveGtpSession.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY
              && exclusiveGtpSession.owner instanceof TrackingStreamLease
              && !exclusiveGtpSession.closedCallbackRun
              && exclusiveGtpSession.wasPondering);
    }
  }

  private enum StatefulOrdinaryMutationKind {
    NONE,
    KOMI,
    BOARD_SIZE
  }

  /**
   * Atomically admits one state command or a state-command batch on both default engines, then
   * performs callbacks and physical output after releasing every endpoint/queue lock. A later
   * mirrored play/undo/state command therefore observes the batch on either both queues or neither
   * queue; it cannot slip between the authority and mirror legs. Local state publication is guarded
   * by the admission generation so a delayed caller cannot overwrite a newer komi or board size.
   */
  private long sendStatefulOrdinaryCommands(
      List<String> commands, StatefulOrdinaryMutationKind mutationKind) {
    List<String> capturedCommands = List.copyOf(commands);
    if (capturedCommands.isEmpty()) {
      throw new IllegalArgumentException("commands");
    }
    EngineManager.EngineGameTransaction startupTransaction =
        engineGameStartupCommandContext.get();
    ReaderStreamBinding startupBinding = null;
    if (startupTransaction != null) {
      startupBinding = currentReaderStreamBinding();
      if (startupBinding == null) {
        throw new IllegalStateException(
            "Engine-game startup state batch has no live reader binding");
      }
    }
    if (Lizzie.config.isDoubleEngineMode()
        && Lizzie.leelaz2 != null
        && this == Lizzie.leelaz2
        && this.isLeela0110) {
      this.leela0110Ponder(true);
      return -1L;
    }

    Leelaz mirroredEngine =
        startupTransaction == null ? resolveDefaultCommandMirrorEngine() : null;
    // The legacy secondary Leela 0.11.0 path rejects ordinary default mirroring and drives its
    // own ponder protocol. Preserve that contract while still making every supported mirror
    // participate in the atomic admission below.
    if (mirroredEngine != null && mirroredEngine.isLeela0110) {
      mirroredEngine = null;
    }
    RestartBootstrapReceipt bootstrapReceipt = restartBootstrapReceiptContext.get();
    OrdinaryEnqueueEffects effects = new OrdinaryEnqueueEffects();
    OrdinaryEnqueueEffects mirroredEffects =
        mirroredEngine == null ? null : mirroredEngine.new OrdinaryEnqueueEffects();
    RestartBootstrapReceipt mirroredBootstrapReceipt =
        mirroredEngine == null ? null : mirroredEngine.restartBootstrapReceiptContext.get();
    QueuedCommand[] admitted = new QueuedCommand[capturedCommands.size()];
    QueuedCommand[] mirroredAdmitted =
        mirroredEngine == null ? null : new QueuedCommand[capturedCommands.size()];
    ReaderStreamBinding commandBinding = startupBinding;
    long admissionGeneration;
    if (mirroredEngine == null) {
      synchronized (engineArbitrationLock()) {
        synchronized (commandQueue()) {
          admissionGeneration =
              admitStatefulOrdinaryCommandsLocked(
                  capturedCommands,
                  mutationKind,
                  startupTransaction,
                  commandBinding,
                  bootstrapReceipt,
                  effects,
                  admitted,
                  null,
                  null,
                  null,
                  null);
        }
      }
    } else {
      Leelaz capturedMirror = mirroredEngine;
      admissionGeneration =
          withOrderedEngineArbitrationAndQueueLocks(
              this,
              capturedMirror,
              () ->
                  admitStatefulOrdinaryCommandsLocked(
                      capturedCommands,
                      mutationKind,
                      startupTransaction,
                      commandBinding,
                      bootstrapReceipt,
                      effects,
                      admitted,
                      capturedMirror,
                      mirroredBootstrapReceipt,
                      mirroredEffects,
                      mirroredAdmitted));
    }
    if (admissionGeneration < 0L) {
      if (startupTransaction != null) {
        throw new IllegalStateException(
            "Engine-game startup state batch was rejected before enqueue: "
                + capturedCommands);
      }
      rejectNewExclusiveWorkDuringGtpLease();
      return -1L;
    }
    if (capturedCommands.size() > 1) {
      afterStatefulOrdinaryPairAdmissionForTest();
    }
    publishOrdinaryEnqueueEffects(effects);
    if (mirroredEngine != null) {
      mirroredEngine.publishOrdinaryEnqueueEffects(mirroredEffects);
    }
    trySendCommandFromQueue();
    if (mirroredEngine != null) {
      mirroredEngine.trySendCommandFromQueue();
      mirroredEngine.startPonderTime = this.startPonderTime;
    }
    return admissionGeneration;
  }

  /** Requires this endpoint/queue and, when present, the mirror endpoint/queue. */
  private long admitStatefulOrdinaryCommandsLocked(
      List<String> commands,
      StatefulOrdinaryMutationKind mutationKind,
      EngineManager.EngineGameTransaction startupTransaction,
      ReaderStreamBinding commandBinding,
      RestartBootstrapReceipt bootstrapReceipt,
      OrdinaryEnqueueEffects effects,
      QueuedCommand[] admitted,
      Leelaz mirroredEngine,
      RestartBootstrapReceipt mirroredBootstrapReceipt,
      OrdinaryEnqueueEffects mirroredEffects,
      QueuedCommand[] mirroredAdmitted) {
    for (String command : commands) {
      if (!canAdmitOrdinaryCommandLocked(
          command,
          TrackingReleaseReason.ORDINARY_OPERATION,
          true,
          commandBinding,
          null,
          bootstrapReceipt,
          startupTransaction)) {
        return -1L;
      }
      if (mirroredEngine != null
          && !mirroredEngine.canAdmitOrdinaryCommandLocked(
              command,
              TrackingReleaseReason.ORDINARY_OPERATION,
              true,
              null,
              null,
              mirroredBootstrapReceipt,
              null)) {
        return -1L;
      }
    }
    enqueueStatefulOrdinaryCommandsLocked(
        commands,
        startupTransaction,
        commandBinding,
        bootstrapReceipt,
        effects,
        admitted);
    if ("clear_board".equals(commands.get(0))) {
      // Bind the legacy clear watermark adjustment to this exact admission. Doing it in the
      // caller after transport/callback work could accidentally acknowledge a newer concurrent
      // state command.
      currentCmdNum = Math.max(cmdNumber - 2, currentCmdNum);
    }
    if (mirroredEngine != null) {
      mirroredEngine.enqueueStatefulOrdinaryCommandsLocked(
          commands,
          null,
          null,
          mirroredBootstrapReceipt,
          mirroredEffects,
          mirroredAdmitted);
    }
    installStatefulOrdinaryBatchFailureLinks(
        admitted, mirroredEngine, mirroredAdmitted);
    return advanceStatefulOrdinaryAdmissionLocked(mutationKind);
  }

  /** Requires this endpoint/queue and successful preflight for the complete batch. */
  private void enqueueStatefulOrdinaryCommandsLocked(
      List<String> commands,
      EngineManager.EngineGameTransaction startupTransaction,
      ReaderStreamBinding commandBinding,
      RestartBootstrapReceipt bootstrapReceipt,
      OrdinaryEnqueueEffects effects,
      QueuedCommand[] admitted) {
    for (int index = 0; index < commands.size(); index++) {
      QueuedCommandSettlement settlement =
          startupTransaction == null
              ? null
              : new EngineGameStartupCommandPermit(this, startupTransaction, commandBinding);
      admitted[index] =
          enqueueAdmittedOrdinaryCommandLocked(
              commands.get(index),
              null,
              null,
              false,
              settlement,
              TrackingReleaseReason.ORDINARY_OPERATION,
              true,
              false,
              commandBinding,
              null,
              bootstrapReceipt,
              effects);
    }
  }

  /**
   * Requires both endpoint queues when a mirror is present; installs callbacks without running
   * them.
   */
  private void installStatefulOrdinaryBatchFailureLinks(
      QueuedCommand[] admitted, Leelaz mirroredEngine, QueuedCommand[] mirroredAdmitted) {
    if (admitted.length > 1 || mirroredEngine != null) {
      for (QueuedCommand command : admitted) {
        command.installInternalSendFailureHandler(
            failure ->
                cancelStatefulOrdinaryBatchBeforeOutputWrite(
                    admitted, mirroredEngine, mirroredAdmitted, command, failure));
      }
    }
    if (mirroredEngine == null || mirroredAdmitted == null) {
      return;
    }
    for (QueuedCommand command : mirroredAdmitted) {
      command.installInternalSendFailureHandler(
          failure ->
              mirroredEngine.cancelStatefulOrdinaryBatchBeforeOutputWrite(
                  mirroredAdmitted, this, admitted, command, failure));
    }
  }

  private void cancelStatefulOrdinaryBatchBeforeOutputWrite(
      QueuedCommand[] localCommands,
      Leelaz mirroredEngine,
      QueuedCommand[] mirroredCommands,
      QueuedCommand failedCommand,
      RuntimeException failure) {
    Throwable notificationFailure = null;
    for (QueuedCommand command : localCommands) {
      if (command != failedCommand) {
        notificationFailure =
            cancelLinkedOrdinaryCommandBeforeOutputWrite(
                notificationFailure, this, command, failure);
      }
    }
    if (mirroredEngine != null && mirroredCommands != null) {
      for (QueuedCommand command : mirroredCommands) {
        notificationFailure =
            cancelLinkedOrdinaryCommandBeforeOutputWrite(
                notificationFailure, mirroredEngine, command, failure);
      }
    }
    rethrowEngineCleanupFailure(notificationFailure);
  }

  private static Throwable cancelLinkedOrdinaryCommandBeforeOutputWrite(
      Throwable firstFailure,
      Leelaz targetEngine,
      QueuedCommand command,
      RuntimeException failure) {
    try {
      targetEngine.cancelPairedOrdinaryCommandBeforeOutputWrite(command, failure);
    } catch (RuntimeException | Error notificationFailure) {
      return appendEngineCleanupFailure(firstFailure, notificationFailure);
    }
    return firstFailure;
  }

  /** Requires this command queue. */
  private long advanceStatefulOrdinaryAdmissionLocked(
      StatefulOrdinaryMutationKind mutationKind) {
    if (mutationKind == StatefulOrdinaryMutationKind.KOMI) {
      return ++komiAdmissionGeneration;
    }
    if (mutationKind == StatefulOrdinaryMutationKind.BOARD_SIZE) {
      return ++boardSizeAdmissionGeneration;
    }
    return 0L;
  }

  private boolean isCurrentStatefulOrdinaryAdmission(
      StatefulOrdinaryMutationKind mutationKind, long admissionGeneration) {
    if (mutationKind == StatefulOrdinaryMutationKind.KOMI) {
      return komiAdmissionGeneration == admissionGeneration;
    }
    if (mutationKind == StatefulOrdinaryMutationKind.BOARD_SIZE) {
      return boardSizeAdmissionGeneration == admissionGeneration;
    }
    return admissionGeneration == 0L;
  }

  private boolean publishCurrentStatefulOrdinaryAdmission(
      StatefulOrdinaryMutationKind mutationKind,
      long admissionGeneration,
      Runnable publication) {
    synchronized (statefulOrdinaryPublicationLock()) {
      if (!isCurrentStatefulOrdinaryAdmission(mutationKind, admissionGeneration)) {
        return false;
      }
      afterCurrentStatefulOrdinaryAdmissionCheckForTest(
          mutationKind.name(), admissionGeneration);
      publication.run();
      return true;
    }
  }

  void afterCurrentStatefulOrdinaryAdmissionCheckForTest(
      String mutationKind, long admissionGeneration) {}

  void afterStatefulOrdinaryPairAdmissionForTest() {}

  /** Cancels one linked command only while its physical output is still preventable. */
  private void cancelPairedOrdinaryCommandBeforeOutputWrite(
      QueuedCommand pairedCommand, RuntimeException failure) {
    if (pairedCommand == null || failure == null) {
      return;
    }
    boolean removed;
    synchronized (commandQueue()) {
      if (!pairedCommand.cancelBeforeOutputWrite(failure)) {
        return;
      }
      removed =
          commandQueue().remove(pairedCommand)
              || foregroundRestoreCommandQueue().remove(pairedCommand);
      if (removed && pairedCommand.claimCommandCountRetirement()) {
        cmdNumber = Math.max(1, cmdNumber - 1);
        if (currentCmdNum > cmdNumber - 1) {
          currentCmdNum = cmdNumber - 1;
        }
      }
    }
    if (removed) {
      pairedCommand.notifySendFailure(failure);
    }
  }


  private void parseHeatMap(String line) {
    if (isheatmap) {
      if (isKatago) {
        if (line.startsWith("=")) {
          heatPolicy = new ArrayList<Double>();
          heatOwnership = new ArrayList<Double>();
          canheatRedraw = true;
          isCommandLine = true;
          String[] params = line.trim().split(" ");
          if (params.length == 3) {
            if (params[1].startsWith("symmetry")) symmetry = Integer.parseInt(params[2]);
          }
        }
        if (line.startsWith("whiteWin")) {
          String[] params = line.trim().split(" ");
          heatwinrate = Double.valueOf(params[1]);
        }
        if (line.startsWith("whiteLead")) {
          String[] params = line.trim().split(" ");
          heatScore = Double.valueOf(params[1]);
        }
        if (line.startsWith("policy")) {
          heatCanGetPolicy = true;
          heatCanGetOwnership = false;
        }
        if (line.startsWith("whiteOwnership")) {
          heatCanGetPolicy = false;
          heatCanGetOwnership = true;
        }

        if (heatCanGetPolicy) {
          String[] params = line.trim().split("\\s+");
          if (params.length == Board.boardWidth) {
            for (int i = 0; i < params.length; i++) {
              try {
                heatPolicy.add((Double.parseDouble(params[i]) * 1000.0));
              } catch (NumberFormatException ex) {
                heatPolicy.add(0.0);
              }
            }
          }
        }

        if (heatCanGetOwnership) {
          String[] params = line.trim().split("\\s+");
          if (params.length == Board.boardWidth) {
            boolean blackToPlay = Lizzie.board.getHistory().isBlacksTurn();
            for (int i = 0; i < params.length; i++) {
              try {
                heatOwnership.add(
                    blackToPlay ? -Double.parseDouble(params[i]) : Double.parseDouble(params[i]));
              } catch (NumberFormatException ex) {
                heatOwnership.add(0.0);
              }
            }
          }
          if (heatOwnership.size() == Board.boardHeight * Board.boardWidth) {
            // 结束并显示
            if (canheatRedraw) {
              canheatRedraw = false;
              if (iskataHeatmapShowOwner) Lizzie.frame.drawKataEstimate(this, heatOwnership);
              heatcount = new ArrayList<Integer>();
              for (int i = 0; i < heatPolicy.size(); i++) {
                heatcount.add(heatPolicy.get(i).intValue());
              }
              if (!Lizzie.frame.isShowingHeatmap) Lizzie.frame.isShowingHeatmap = true;
              heatCanGetOwnership = false;
              Lizzie.frame.refresh();
            }
          }
        }
      } else {
        if (line.startsWith(" ") || line.length() > 0 && Character.isDigit(line.charAt(0))) {
          try {
            String[] params = line.trim().split("\\s+");
            if (params.length == Board.boardWidth) {
              for (int i = 0; i < params.length; i++) heatcount.add(Integer.parseInt(params[i]));
            }
          } catch (Exception ex) {
          }
          if (heatcount.size() == Board.boardHeight * Board.boardWidth) Lizzie.frame.refresh();
        }
        if (line.contains("winrate:")) {
          // isheatmap = false;
          if (!Lizzie.frame.isShowingHeatmap) Lizzie.frame.isShowingHeatmap = true;
          // Lizzie.frame.refresh();
          if (!isZen) {
            String[] params = line.trim().split(" ");
            heatwinrate = Double.valueOf(params[1]);
          }
        }
      }
    }
  }

  private void publishPreviousAnalysisSummary(ReaderStreamBinding responseBinding) {
    AnalysisSummaryBatch batch;
    synchronized (previousAnalysisSummaryLock) {
      batch = previousAnalysisSummaryBatch;
      previousAnalysisSummaryBatch = null;
    }
    canGetSummaryInfo = false;
    if (batch == null
        || batch.moves.isEmpty()
        || batch.route == null
        || batch.route.binding != responseBinding
        || !isAnalysisResponseUpToDateSnapshot(batch.route)) {
      return;
    }
    runIfCurrentAnalysisOutputRoute(
        batch.route,
        () -> {
          synchronized (analysisInfoMutationLock()) {
            if (analysisInfoEpoch != batch.analysisInfoEpoch) {
              return;
            }
            if (batch.board == null
                || batch.board != Lizzie.board
                || batch.targetNode == null
                || batch.board.getHistory().getCurrentHistoryNode() == null
                || batch.board
                    .getHistory()
                    .getCurrentHistoryNode()
                    .previous()
                    .filter(previous -> previous == batch.targetNode)
                    .isEmpty()) {
              return;
            }
            publishAnalysisDisplayNonFatal(
                () ->
                    batch.targetNode
                        .getData()
                        .tryToSetBestMovesFromEngine(
                            new ArrayList<>(batch.moves),
                            bestMovesEnginename,
                            this,
                            MoveData.getPlayouts(batch.moves),
                            null,
                            false));
          }
        });
  }

  /** Continually reads and processes output from leelaz */
  private void read() {
    read(currentReaderStreamBinding());
  }

  private void read(ReaderStreamBinding binding) {
    boolean lineInProgress = false;
    Throwable failure = null;
    try {
      String line = "";
      while ((line = binding.stdout.readLine()) != null) {
        if (!beginReaderLine(binding)) {
          return;
        }
        lineInProgress = true;
        rememberRecentLine(recentStdoutLines, line);
        if (dispatchExclusiveGtpLine(binding, line)) {
          lineInProgress = false;
          endReaderLine(binding);
          continue;
        }
        EngineManager.EngineGameTransaction startupResponseTransaction =
            engineGameStartupTransactionForLine(line, binding);
        if (startupResponseTransaction != null
            && !EngineManager.isEngineGameOutputAdmissionOpen(startupResponseTransaction)) {
          // Retired startup output is a tombstone, not ordinary parser input. Only a terminal GTP
          // frame may settle its exact physical permit; intermediate probe output is discarded.
          if (line.startsWith("=") || line.startsWith("?")) {
            processCommandResponseLine(line, binding);
          }
          lineInProgress = false;
          endReaderLine(binding);
          continue;
        }
        if (shouldQuarantineUnmatchedStrictResponseCarrier(line, binding)) {
          // An exact engine-game or parameter-read request owns this terminal frame until its
          // matching numbered response arrives. Do not let an unframed or wrong-id predecessor
          // response fall through and mutate shared board/UI/startup state.
          isCommandLine = false;
          lineInProgress = false;
          endReaderLine(binding);
          continue;
        }
        boolean recentParameterResponse = captureRecentParameterResponse(line, binding);
        EngineGameResponseHandler engineGameHandler =
            recentParameterResponse ? null : engineGameResponseHandlerForLine(line, binding);
        if (recentParameterResponse) {
          // The payload was decoded against its exact pending command. Keep it out of the legacy
          // parser, whose shared flags could otherwise mistake it for a move/name/version result.
          isCommandLine = true;
        } else if (engineGameHandler != null) {
          try {
            parseLineForGenmovePk(line, binding, engineGameHandler);
          } catch (IOException readFailure) {
            throw readFailure;
          } catch (StartupPostActionFailure startupFailure) {
            throw startupFailure;
          } catch (Exception e) {
            e.printStackTrace();
          }

        } else {
          if (startGetCommandList) {
            String cmd = line.trim();
            if (!cmd.equals("") && !cmd.equals("=")) commandLists.add(cmd);
          }
          try {
            String readerLine = line;
            runWithRestartBootstrapReceipt(
                binding.restartBootstrapReceipt, () -> parseLine(readerLine, binding));
          } catch (StartupPostActionFailure startupFailure) {
            throw startupFailure;
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
        if (isCommandLine) {
          String responseLine = line;
          runWithRestartBootstrapReceipt(
              binding.restartBootstrapReceipt,
              () -> processCommandResponseLine(responseLine, binding));
          if (!this.isKatago && !this.isLeela0110 && Lizzie.frame.isPlayingAgainstLeelaz) {
            publishPreviousAnalysisSummary(binding);
          }
        }
        isCommandLine = false;
        lineInProgress = false;
        endReaderLine(binding);
        // line = new StringBuilder();
        //					if(isInfoLine)
        //					{
        //						if (!this.bestMoves.isEmpty()) {
        //							  notifyAutoPK();
        //				        	  notifyAutoPlay();
        //						}
        //					}

        //	isInfoLine=false;
        // }
        //				else if (c == '='||c=='?') {
        //					isCommandLine = true;
        //				}
      }
    } catch (IOException | RuntimeException | Error readFailure) {
      failure = readFailure;
    } finally {
      if (lineInProgress) {
        endReaderLine(binding);
      }
    }
    terminateReaderIncarnation(binding, failure);
  }

  private void terminateReaderIncarnation(ReaderStreamBinding binding, Throwable failure) {
    boolean finishTerminalCleanup = false;
    List<StartupCommandDelivery> interruptedStartupDeliveries = List.of();
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding != binding || binding.terminated) {
        return;
      }
      retireAnalysisOutputBindingLocked(binding);
      binding.terminalFailure = failure;
      if (!binding.startupCommandDeliveries.isEmpty()) {
        interruptedStartupDeliveries = new ArrayList<>(binding.startupCommandDeliveries);
      }
      if (binding.linesInProgress == 0 && binding.startupPostActionsInProgress == 0) {
        binding.terminalCleanupStarted = true;
        readerTerminalCleanupInProgress = true;
        finishTerminalCleanup = true;
      }
    }
    RuntimeException deliveryFailure =
        failure instanceof RuntimeException
            ? (RuntimeException) failure
            : new IllegalStateException(
                failure == null
                    ? "Engine reader terminated before startup commands were delivered"
                    : "Engine reader failed before startup commands were delivered",
                failure);
    for (StartupCommandDelivery delivery : interruptedStartupDeliveries) {
      abortStartupCommandDelivery(delivery, deliveryFailure);
    }
    if (finishTerminalCleanup) {
      finishReaderTerminalCleanup(binding);
    }
  }

  private void finishReaderTerminalCleanup(ReaderStreamBinding binding) {
    EngineManager.EngineRuntimeUiFence terminalUiFence =
        EngineManager.captureEngineRuntimeUiFence(this, binding);
    String deferredNonModalDiagnostic = null;
    try {
      if (!binding.normalExitRequested) {
        Throwable terminalFailure = binding.terminalFailure;
        EngineObservation.recordTransportFailure(
            loggingEngineId,
            "stdout",
            terminalFailure == null
                ? "unexpected-eof"
                : terminalFailure instanceof IOException ? "io-error" : "reader-error",
            terminalFailure);
      }
      try {
        shutdownReaderTransport(binding);
      } catch (RuntimeException shutdownFailure) {
        EngineObservation.recordTransportFailure(
            loggingEngineId, "stdout", "shutdown-error", shutdownFailure);
      }
      markReaderBindingTerminatedIfCurrent(binding);
      deferredNonModalDiagnostic = finishTerminatedReaderIncarnation(binding);
    } finally {
      synchronized (engineArbitrationLock()) {
        readerTerminalCleanupInProgress = false;
        engineArbitrationLock().notifyAll();
      }
    }
    if (deferredNonModalDiagnostic != null) {
      String diagnostic = deferredNonModalDiagnostic;
      Runnable presentation =
          () -> {
            if (terminalUiFence != null) {
              try {
                terminalUiFence.publishTerminalDiagnosticIfCurrent(diagnostic);
              } catch (RuntimeException | Error presentationFailure) {
                presentationFailure.printStackTrace();
              }
            }
          };
      try {
        SwingUtilities.invokeLater(presentation);
      } catch (RuntimeException | Error dispatchFailure) {
        // Terminal resources are already closed. A failed UI dispatcher must not replay this
        // diagnostic synchronously without its exact manager/slot/incarnation fence.
        dispatchFailure.printStackTrace();
      }
    }
  }

  private String finishTerminatedReaderIncarnation(ReaderStreamBinding binding) {
    ExclusiveGtpSession interruptedForegroundWork;
    synchronized (engineArbitrationLock()) {
      interruptedForegroundWork =
          exclusiveGtpSession != null ? exclusiveGtpSession : foregroundRestoreSession;
    }
    if (interruptedForegroundWork != null
        && interruptedForegroundWork.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY) {
      TrackingStreamCleanup cleanup =
          claimTrackingStreamCleanup(
              interruptedForegroundWork,
              TrackingStreamLeaseFailure.TRANSPORT_CLOSED,
              "tracking stream transport closed",
              false,
              true);
      if (cleanup != null) {
        cancelExclusiveGtpInitialStopTimeout(interruptedForegroundWork);
        cancelExclusiveGtpReleaseStopTimeout(interruptedForegroundWork);
        try {
          notifyTrackingDisposition(cleanup.dispositionNotification);
          notifyGtpCommandStateReset(cleanup.commandStateReset);
        } finally {
          closeStreamOnlyExclusiveGtpSession(interruptedForegroundWork, false, true);
        }
      } else {
        closeStreamOnlyExclusiveGtpSession(interruptedForegroundWork, false, true);
      }
    } else {
      recordForegroundAnalysisLeaseFailure(
          interruptedForegroundWork, ForegroundAnalysisLeaseFailure.TRANSPORT_CLOSED);
      markForegroundRestoreFailed(interruptedForegroundWork, "engine transport closed");
      abortExclusiveGtpSession();
    }
    if (interruptedForegroundWork == null
        || interruptedForegroundWork.releasePolicy
            == ExclusiveGtpReleasePolicy.FOREGROUND_RESTORE) {
      completeForegroundRestore(interruptedForegroundWork);
    }
    ReadBoard readBoard = Lizzie.frame == null ? null : Lizzie.frame.readBoard;
    if (readBoard == null
        || !readBoard.failReadBoardGmaSessionForEngineTermination(
            this, "engine transport closed")) {
      failReadBoardGmaEngineRestore("engine transport closed");
    }
    if (!binding.normalExitRequested && Lizzie.engineManager != null) {
      EngineManager.EngineGameRecoveryDisposition disposition =
          EngineManager.requestEngineGameParticipantRecovery(
              Lizzie.engineManager,
              this,
              binding,
              classifyEngineGameRecoveryCause(binding));
      if (disposition == EngineManager.EngineGameRecoveryDisposition.HANDLED) {
        isDownWithError = true;
        if (binding.remoteTransport != null) {
          rememberRecentLine(
              recentStderrLines,
              "Remote engine-game participant retired; recovery deferred until transaction retirement");
        }
        return null;
      }
    }
    if (binding.remoteTransport != null && binding.remoteTransport.isRecoveryRequested()) {
      isDownWithError = true;
      rememberRecentLine(
          recentStderrLines,
          "Remote session retired; rebuilding with a fresh token and full board replay");
      if (Lizzie.engineManager != null) {
        Lizzie.engineManager.restartUnresponsiveRemoteEngine(this, currentEngineN);
      }
      return null;
    }
    if (!binding.normalExitRequested && !tryRecoverBundledOpenClNativeExit(binding.process)) {
      isDownWithError = true;
      // isLoaded=false;
      return buildEngineExitDiagnostic(
          Lizzie.resourceBundle.getString("Leelaz.engineEndUnormalHint"));
      // ("打开Gtp窗口(快捷键E)查看报错信息");
      // LizzieFrame.openMoreEngineDialog();
    }
    return null;
  }

  private void shutdownReaderTransport(ReaderStreamBinding binding) {
    ReaderExecutorSnapshot executors = requestReaderShutdown(binding);
    cancelLeela0110PonderForReaderBinding(binding);
    try {
      if (executors.ownsTransportClose) {
        if (binding.javaSSH != null) {
          binding.javaSSH.close();
        } else {
          if (binding.remoteTransport != null) {
            binding.remoteTransport.close();
          } else if (binding.process != null) {
            binding.process.destroy();
          }
        }
      }
    } finally {
      shutdownReaderExecutors(executors);
    }
  }

  private void markReaderBindingTerminatedIfCurrent(ReaderStreamBinding binding) {
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding == binding) {
        if (binding.javaSSH != null) {
          javaSSHClosed = true;
        }
        started = false;
      }
    }
  }

  private boolean tryRecoverBundledOpenClNativeExit() {
    return tryRecoverBundledOpenClNativeExit(process);
  }

  private boolean tryRecoverBundledOpenClNativeExit(Process expectedProcess) {
    Path engineExecutable = bundledOpenClNativeRecoveryExecutable(expectedProcess);
    if (engineExecutable == null) {
      return false;
    }
    Object failedIncarnation = captureEngineIncarnationFence();
    if (failedIncarnation instanceof ReaderStreamBinding
        && ((ReaderStreamBinding) failedIncarnation).process == expectedProcess
        && Lizzie.engineManager != null
        && EngineManager.requestEngineGameParticipantRecovery(
                Lizzie.engineManager,
                this,
                failedIncarnation,
                EngineManager.EngineGameRecoveryCause.OPENCL_NATIVE_EXIT)
            == EngineManager.EngineGameRecoveryDisposition.HANDLED) {
      return true;
    }
    AutomaticRestartAttempt attempt = beginAutomaticEngineRestartAttempt();
    if (attempt == null
        || !openClCompatibilityRecoveryAttempted.compareAndSet(false, true)
        || !KataGoRuntimeHelper.rememberOpenClFp32Compatibility(commands, engineExecutable)) {
      if (attempt != null) {
        attempt.close();
      }
      return false;
    }

    isDownWithError = false;
    isLoaded = false;
    canCheckAlive = false;
    long primaryGeneration = Lizzie.capturePrimaryEngineGeneration(this);
    if (primaryGeneration >= 0L) {
      Lizzie.runIfPrimaryEngine(
          this,
          primaryGeneration,
          () ->
              Lizzie.engineStartupStatus.checking(
                  "BundledEngineStartup.status.openclRecovering",
                  "NVIDIA OpenCL compatibility recovery is starting..."));
      if (Lizzie.frame != null) {
        SwingUtilities.invokeLater(
            () ->
                Lizzie.runIfPrimaryEngine(
                    this,
                    primaryGeneration,
                    Lizzie.frame::prepareQuickAnalysisForPrimaryOpenClRecovery));
      }
    }
    int engineIndex = currentEngineN;
    Thread recovery =
        new Thread(
            () -> {
              boolean restartStarted = false;
              try {
                attempt.restartClosedEngine(
                    engineIndex,
                    null,
                    detail -> {
                      isDownWithError = true;
                      SwingUtilities.invokeLater(
                          () ->
                              tryToDignostic(
                                  buildEngineExitDiagnostic(
                                      text(
                                          "BundledEngineStartup.openclRecoveryFailed",
                                          "NVIDIA OpenCL compatibility recovery failed.")),
                                  false));
                    });
                restartStarted = true;
              } catch (IOException | RuntimeException failure) {
                failure.printStackTrace();
              } finally {
                if (!restartStarted) {
                  attempt.close();
                }
              }
            },
            "katago-opencl-fp32-recovery");
    recovery.setDaemon(true);
    try {
      recovery.start();
    } catch (RuntimeException failure) {
      attempt.close();
      isDownWithError = true;
      failure.printStackTrace();
      return false;
    }
    return true;
  }

  EngineManager.EngineGameRecoveryCause classifyEngineGameRecoveryCause(
      Object expectedIncarnation) {
    if (!(expectedIncarnation instanceof ReaderStreamBinding)) {
      return EngineManager.EngineGameRecoveryCause.PROCESS_EXIT;
    }
    ReaderStreamBinding binding = (ReaderStreamBinding) expectedIncarnation;
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding != binding) {
        return EngineManager.EngineGameRecoveryCause.PROCESS_EXIT;
      }
      if (binding.remoteTransport != null || useRemoteCompute) {
        return EngineManager.EngineGameRecoveryCause.REMOTE_DISCONNECT;
      }
    }
    return bundledOpenClNativeRecoveryExecutable(binding.process) != null
        ? EngineManager.EngineGameRecoveryCause.OPENCL_NATIVE_EXIT
        : EngineManager.EngineGameRecoveryCause.PROCESS_EXIT;
  }

  /** Applies the OpenCL fallback only for the frozen failed carrier and only after game retirement. */
  boolean prepareBundledOpenClRecoveryForFailedIncarnation(Object expectedIncarnation) {
    if (!(expectedIncarnation instanceof ReaderStreamBinding)) {
      return false;
    }
    ReaderStreamBinding binding = (ReaderStreamBinding) expectedIncarnation;
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding != binding) {
        return false;
      }
      Path engineExecutable = bundledOpenClNativeRecoveryExecutable(binding.process);
      return engineExecutable != null
          && openClCompatibilityRecoveryAttempted.compareAndSet(false, true)
          && KataGoRuntimeHelper.rememberOpenClFp32Compatibility(commands, engineExecutable);
    }
  }

  private Path bundledOpenClNativeRecoveryExecutable(Process expectedProcess) {
    if (expectedProcess == null
        || useRemoteCompute
        || useJavaSSH
        || openClCompatibilityRecoveryAttempted.get()) {
      return null;
    }
    int exitCode;
    try {
      exitCode = expectedProcess.exitValue();
    } catch (IllegalThreadStateException e) {
      return null;
    }
    Path engineExecutable = KataGoRuntimeHelper.resolveCommandExecutable(commands);
    return KataGoRuntimeHelper.shouldRecoverOpenClNativeExit(
            commands, engineExecutable, exitCode, openClFp32CompatibilityActive)
        ? engineExecutable
        : null;
  }

  //	private void stopAutoAna() {
  //		//if (!isClosing) {
  //		      			//isClosing=true;
  //		      			Lizzie.frame.toolbar.stopAutoAna();
  //		      			//Lizzie.frame.addInput();
  //
  //		      //			}
  //	}

  public void setPda(String pda) {
    try {
      this.pda = Double.parseDouble(pda);
      pdaBeforeGame = Double.parseDouble(pda);
    } catch (NumberFormatException e) {
      e.printStackTrace();
      return;
    }
    sendCommand("kata-set-param playoutDoublingAdvantage " + pda);
  }

  public void setGameStatus(boolean isStart) {
    if (!Lizzie.leelaz.isKatagoCustom || Lizzie.leelaz.isKataGoPda) return;
    if (isStart) {
      sendCommand("startGame");
      pdaBeforeGame = pda;
    } else {
      sendCommand("stopGame");
      if (Lizzie.config.autoLoadKataEnginePDA) {
        this.pda = Double.parseDouble(Lizzie.config.txtKataEnginePDA);
      } else this.pda = pdaBeforeGame;
    }
  }

  /**
   * Sends a command to command queue for leelaz to execute
   *
   * @param command a GTP command containing no newline characters
   */
  public void loadSgf(Path sgfFile) {
    sendLoadSgfCommand(this, sgfFile, null, null);
  }

  public void sendCommand(String command) {
    if (this == Lizzie.leelaz) {
      AnalysisResourceCoordinator.commandSent(
          this, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, command);
    }
    if (command != null
        && (command.startsWith("clear_board") || command.startsWith("kata-analyze"))) {
      StringBuilder sb = new StringBuilder();
      StackTraceElement[] st = Thread.currentThread().getStackTrace();
      int taken = 0;
      for (StackTraceElement e : st) {
        if (!e.getClassName().startsWith("featurecat.lizzie")) continue;
        if (e.getClassName().equals(Leelaz.class.getName())
            && (e.getMethodName().equals("sendCommand")
                || e.getMethodName().equals("getStackTrace"))) continue;
        if (sb.length() > 0) sb.append(" <- ");
        sb.append(e.getClassName().substring("featurecat.lizzie.".length()))
            .append("#")
            .append(e.getMethodName())
            .append(":")
            .append(e.getLineNumber());
        if (++taken >= 6) break;
      }
      YikeSyncDebugLog.log("Leelaz sendCommand TRACE command=" + command + " caller=" + sb);
    }
    if (TrialDiag.ENABLED) {
      System.out.println("[katago-cmd] " + command);
    }
    Object startupContext = startupPostActionCommandContext.get();
    if (startupContext instanceof StartupPostActionLease) {
      ((StartupPostActionLease) startupContext).sendCommand(command);
      return;
    }
    if (startupContext instanceof ReaderStreamBinding) {
      sendStartupPostActionCommand(command, (ReaderStreamBinding) startupContext);
      return;
    }
    boolean failClosedStartupCommand = startupContext != null;
    boolean sent =
        sendCommand(command, null, null, failClosedStartupCommand, !failClosedStartupCommand);
    if (failClosedStartupCommand && !sent) {
      throw new IllegalStateException("startup command was rejected: " + command);
    }
  }

  private void sendStartupPostActionCommand(String command, ReaderStreamBinding binding) {
    EngineManager.EngineGameTransaction transaction = engineGameStartupCommandContext.get();
    if (transaction != null && !EngineManager.isCurrentEngineGameTransaction(transaction)) {
      throw new IllegalStateException("engine-game startup transaction is no longer current");
    }
    EngineGameStartupCommandPermit engineGamePermit =
        transaction == null
            ? null
            : new EngineGameStartupCommandPermit(this, transaction, binding);
    StartupCommandDelivery delivery =
        new StartupCommandDelivery(command, binding, engineGamePermit);
    boolean accepted;
    try {
      accepted =
          sendCommand(
              command,
              null,
              null,
              true,
              false,
              TrackingReleaseReason.ORDINARY_OPERATION,
              delivery,
              false,
              binding);
    } catch (RuntimeException | Error admissionFailure) {
      RuntimeException failure =
          admissionFailure instanceof RuntimeException
              ? (RuntimeException) admissionFailure
              : new IllegalStateException(
                  "Startup command admission failed: " + command, admissionFailure);
      cancelStartupCommandBeforeOutputWrite(delivery, failure, false);
      throw admissionFailure;
    }
    if (!accepted) {
      throw new IllegalStateException("startup command was rejected: " + command);
    }
    if (!registerStartupCommandDelivery(delivery)) {
      RuntimeException staleReceipt =
          new IllegalStateException("startup command capability is no longer current: " + command);
      cancelStartupCommandBeforeOutputWrite(delivery, staleReceipt, false);
      throw staleReceipt;
    }
    try {
      scheduleStartupCommandDeliveryTimeout(delivery);
      dispatchStartupCommandOutput(delivery);
    } catch (RuntimeException | Error schedulingFailure) {
      RuntimeException failure =
          schedulingFailure instanceof RuntimeException
              ? (RuntimeException) schedulingFailure
              : new IllegalStateException(
                  "Failed to schedule startup command output: " + command, schedulingFailure);
      abortStartupCommandDelivery(delivery, failure);
    }
    awaitStartupCommandDelivery(delivery);
  }

  private void awaitStartupCommandDelivery(StartupCommandDelivery delivery) {
    boolean interrupted = false;
    while (delivery.completion.getCount() != 0L) {
      try {
        delivery.completion.await();
      } catch (InterruptedException waitInterrupted) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    Throwable failure = delivery.failure.get();
    if (failure != null) {
      if (failure instanceof RuntimeException) {
        throw (RuntimeException) failure;
      }
      if (failure instanceof Error) {
        throw (Error) failure;
      }
      throw new IllegalStateException("Startup command delivery failed", failure);
    }
  }

  private boolean cancelStartupCommandBeforeOutputWrite(
      StartupCommandDelivery delivery, RuntimeException failure) {
    return cancelStartupCommandBeforeOutputWrite(delivery, failure, true);
  }

  private boolean cancelStartupCommandBeforeOutputWrite(
      StartupCommandDelivery delivery, RuntimeException failure, boolean dispatchNext) {
    QueuedCommand queuedCommand = delivery.queuedCommand;
    if (queuedCommand == null || !queuedCommand.cancelBeforeOutputWrite(failure)) {
      return false;
    }
    afterStartupCommandCancellationClaimBeforeQueueRemoval();
    synchronized (commandQueue()) {
      commandQueue().remove(queuedCommand);
      foregroundRestoreCommandQueue().remove(queuedCommand);
    }
    retireFailedCommandCount(queuedCommand);
    try {
      queuedCommand.notifySendFailure(failure);
    } catch (RuntimeException | Error notificationFailure) {
      appendEngineCleanupFailure(failure, notificationFailure);
    }
    if (dispatchNext) {
      try {
        trySendCommandFromQueue();
      } catch (RuntimeException | Error dispatchFailure) {
        appendEngineCleanupFailure(failure, dispatchFailure);
      }
    }
    return true;
  }

  private boolean registerStartupCommandDelivery(StartupCommandDelivery delivery) {
    synchronized (engineArbitrationLock()) {
      synchronized (commandQueue()) {
        ReaderStreamBinding binding = delivery.binding;
        if (readerStreamBinding != binding
            || binding.terminated
            || !started
            || (exclusiveGtpLifecycleQueueGate
                && !isCurrentRestartBootstrapReceiptLocked(
                    delivery.queuedCommand.restartBootstrapReceipt))) {
          return false;
        }
        binding.startupCommandDeliveries.add(delivery);
        return true;
      }
    }
  }

  private void unregisterStartupCommandDelivery(StartupCommandDelivery delivery) {
    synchronized (engineArbitrationLock()) {
      delivery.binding.startupCommandDeliveries.remove(delivery);
    }
  }

  private void scheduleStartupCommandDeliveryTimeout(StartupCommandDelivery delivery) {
    Runnable timeout =
        () -> {
          try {
            Thread.sleep(Math.max(1L, startupCommandDeliveryTimeoutMillis()));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
          }
          abortStartupCommandDelivery(
              delivery,
              new IllegalStateException(
                  "Timed out waiting for startup command output write: " + delivery.command));
        };
    dispatchStartupCommandTimeout(timeout);
  }

  void dispatchStartupCommandTimeout(Runnable timeout) {
    Thread thread = new Thread(timeout, "lizzie-engine-startup-command-timeout");
    thread.setDaemon(true);
    thread.start();
  }

  private void dispatchStartupCommandOutput(StartupCommandDelivery delivery) {
    Runnable output =
        () -> {
          if (!delivery.dispatchOwner.compareAndSet(0, 1)) {
            return;
          }
          // A timeout/EOF may win after a custom executor has accepted this worker but before it
          // actually runs.  Once settled, it must not get a second chance to drain the old queue.
          if (delivery.settled.get()) {
            return;
          }
          delivery.writerThread = Thread.currentThread();
          try {
            trySendCommandFromQueue();
          } catch (RuntimeException | Error sendFailure) {
            RuntimeException requestFailure =
                sendFailure instanceof RuntimeException
                    ? (RuntimeException) sendFailure
                    : new IllegalStateException(
                        "Startup command output failed: " + delivery.command, sendFailure);
            delivery.onPhysicalWriteFailure(sendFailure, requestFailure);
          }
        };
    try {
      dispatchStartupCommandOutputWorker(output);
    } catch (RuntimeException | Error schedulingFailure) {
      if (delivery.dispatchOwner.compareAndSet(0, 2)) {
        throw schedulingFailure;
      }
    }
  }

  void dispatchStartupCommandOutputWorker(Runnable output) {
    Thread thread = new Thread(output, "lizzie-engine-startup-command-output");
    thread.setDaemon(true);
    thread.start();
  }

  /** Test seam immediately after queue ownership and before an exact bootstrap write claim. */
  void beforeRestartBootstrapOutputWriteClaim() {}

  /** Test seam immediately before a watchdog/EOF competes with physical-write completion. */
  void beforeStartupCommandAbortClaim() {}

  /** Test seam after cancellation ownership and before the cancelled command leaves its queue. */
  void afterStartupCommandCancellationClaimBeforeQueueRemoval() {}

  private void abortStartupCommandDelivery(
      StartupCommandDelivery delivery, RuntimeException failure) {
    beforeStartupCommandAbortClaim();
    if (!delivery.claimFailure(failure)) {
      return;
    }
    try {
      if (cancelStartupCommandBeforeOutputWrite(delivery, failure, false)) {
        return;
      }
      QueuedCommand queuedCommand = delivery.queuedCommand;
      synchronized (engineArbitrationLock()) {
        ReaderStreamBinding binding = delivery.binding;
        if (readerStreamBinding == binding) {
          binding.terminated = true;
          binding.terminalFailure = failure;
          isLoaded = false;
          isDownWithError = true;
          if (outputStream == binding.output) {
            outputStream = null;
          }
        }
      }
      synchronized (commandQueue()) {
        if (normalCommandBeingSent == queuedCommand) {
          normalCommandBeingSent = null;
          normalCommandSendInProgress = false;
          commandQueue().notifyAll();
        }
      }
      Throwable abortFailure = failure;
      if (queuedCommand != null) {
        abortFailure =
            runEngineCleanupStep(
                abortFailure, () -> queuedCommand.markStateResetAfterOutputWrite(failure));
        abortFailure =
            runEngineCleanupStep(abortFailure, queuedCommand::publishStateResetAfterOutputWrite);
      }
      Thread writer = delivery.writerThread;
      if (writer != null) {
        abortFailure = runEngineCleanupStep(abortFailure, writer::interrupt);
      }
      Throwable dispatchFailure = abortFailure;
      try {
        dispatchStartupTransportAbort(delivery.binding);
      } catch (RuntimeException | Error transportAbortFailure) {
        appendEngineCleanupFailure(dispatchFailure, transportAbortFailure);
      }
    } finally {
      // Count down only after the abort owns the outcome and has made the old runtime unavailable.
      // A concurrent successful physical write uses the same CAS and therefore cannot be followed
      // by a stale watchdog that tears down its healthy binding.
      delivery.finishClaimedFailure();
    }
  }

  private void dispatchStartupTransportAbort(ReaderStreamBinding binding) {
    if (binding.rawOutput != null) {
      dispatchStartupTransportAbortStep(
          "output", () -> closeOutput(binding.rawOutput));
    }
  }

  private void dispatchStartupTransportAbortStep(String resource, Runnable abort) {
    try {
      Thread thread =
          new Thread(
              () -> {
                try {
                  abort.run();
                } catch (RuntimeException | Error failure) {
                  failure.printStackTrace();
                }
              },
              "lizzie-engine-startup-" + resource + "-abort");
      thread.setDaemon(true);
      thread.start();
    } catch (RuntimeException | Error schedulingFailure) {
      schedulingFailure.printStackTrace();
    }
  }

  private static void closeOutput(OutputStream output) {
    try {
      output.close();
    } catch (IOException closeFailure) {
      throw new IllegalStateException("Failed to close startup command output", closeFailure);
    }
  }

  long startupCommandDeliveryTimeoutMillis() {
    return STARTUP_COMMAND_DELIVERY_TIMEOUT_MILLIS;
  }

  static void runWithEngineGameStartupCommandContext(
      EngineManager.EngineGameTransaction transaction, Runnable action) {
    EngineManager.EngineGameTransaction previous = engineGameStartupCommandContext.get();
    if (transaction == null) {
      engineGameStartupCommandContext.remove();
    } else {
      engineGameStartupCommandContext.set(transaction);
    }
    try {
      action.run();
    } finally {
      if (previous == null) {
        engineGameStartupCommandContext.remove();
      } else {
        engineGameStartupCommandContext.set(previous);
      }
    }
  }

  void sendEngineGameStartupCommandForTest(
      String command, EngineManager.EngineGameTransaction transaction) {
    runWithEngineGameStartupCommandContext(transaction, () -> sendCommand(command));
  }

  void sendOrdinaryAnalysisCommandForTest(String command) {
    sendCommand(command);
  }

  boolean installLeela0110PonderStateForTest(
      EngineManager.EngineGameTransaction transaction) {
    return prepareLeela0110PonderState(transaction);
  }

  void sendLeela0110PonderCommandForTest(
      String command, EngineManager.EngineGameTransaction transaction) {
    Leela0110PonderCommandOwner commandOwner = leela0110PonderCommandOwner(transaction);
    if (commandOwner == null) {
      throw new IllegalStateException("Leela0110 ponder state is not current");
    }
    runWithEngineGameStartupCommandContext(
        transaction,
        () ->
            sendCommandNoLeelaz2(
                command, null, commandOwner.binding, commandOwner.stateToken));
  }

  boolean hasLeela0110PonderStateForTest(
      EngineManager.EngineGameTransaction transaction) {
    synchronized (leela0110PonderStateLock) {
      return leela0110PonderingTimer != null
          && leela0110PonderingBoardData != null
          && leela0110PonderingTransaction == transaction;
    }
  }

  void shutdownReaderBindingForTest(Object binding) {
    if (binding instanceof ReaderStreamBinding) {
      ReaderStreamBinding expected = (ReaderStreamBinding) binding;
      shutdown(expected, requestReaderShutdown(expected, true));
    }
  }

  void parseAnalysisLineForTest(String line) {
    parseLine(line, currentReaderStreamBinding());
  }

  void parseAnalysisErrorLineForTest(String line) {
    parseLineForError(line, currentReaderStreamBinding());
  }

  String analysisOutputRouteForTest() {
    return analysisOutputRoute(currentReaderStreamBinding()).kind.name();
  }

  String analysisOutputRouteForTest(String line) {
    return analysisOutputRoute(currentReaderStreamBinding(), line).kind.name();
  }

  String analysisOutputRouteForTest(Object sourceEngineIncarnation) {
    return analysisOutputRoute(sourceEngineIncarnation).kind.name();
  }

  boolean hasAnalysisOutputOwnershipForTest() {
    ReaderStreamBinding binding = currentReaderStreamBinding();
    return binding != null && binding.analysisOutputOwnership.get() != null;
  }

  Object analysisReaderBindingForTest() {
    return currentReaderStreamBinding();
  }

  Object analysisOutputRecoveryTokenForTest() {
    ReaderStreamBinding binding = currentReaderStreamBinding();
    return binding == null ? null : binding.analysisOutputRecoveryToken;
  }

  int nextEngineGameResponseCommandIdForTest() {
    return engineGameResponseCommandIds.get();
  }

  void requestCurrentReaderShutdownForTest() {
    ReaderStreamBinding binding = currentReaderStreamBinding();
    if (binding != null) {
      // Expose only the pre-settlement marker window. Calling requestReaderShutdown here would
      // also consume the one-shot transport-close capability without returning it to the test.
      binding.readerShutdownRequested = true;
    }
  }

  void suppressGlobalEnginePresentationForTest() {
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding != null) {
        readerStreamBinding.suppressGlobalEnginePresentation = true;
      }
    }
  }

  void parseAnalysisLineForTest(String line, Object sourceEngineIncarnation) {
    parseLine(line, sourceEngineIncarnation);
  }

  void sendEngineGameStartupCommandWithResponseForTest(
      String command,
      EngineManager.EngineGameTransaction transaction,
      Runnable onResponse) {
    runWithEngineGameStartupCommandContext(
        transaction, () -> sendCommand(command, onResponse));
  }

  void retireTimedOutNormalCommandForTest(Runnable onResponse) {
    retireTimedOutNormalCommand(onResponse);
  }

  void sendCommandWithResponseForTest(String command, Runnable onResponse) {
    sendCommand(command, onResponse, false, false);
  }

  void sendCommandWithFailingStateResetCallbackForTest(
      String command, RuntimeException callbackFailure) {
    CommandSendFailureHandler handler =
        new CommandSendFailureHandler() {
          @Override
          public void onSendFailure(RuntimeException failure) {}

          @Override
          public void onStateResetAfterOutputWrite(RuntimeException failure) {
            throw callbackFailure;
          }
        };
    sendCommand(command, null, handler, true, false);
  }

  void beginForegroundRestoreForTest() {
    foregroundRestoreInProgress = true;
    suppressNormalCommandsForForegroundAnalysis = true;
  }

  void installCommandOutputForTest(OutputStream stream) {
    BufferedOutputStream nextOutputStream =
        stream instanceof BufferedOutputStream
            ? (BufferedOutputStream) stream
            : createCommandOutputStream(stream);
    synchronized (engineArbitrationLock()) {
      synchronized (commandQueue()) {
        if (normalCommandSendInProgress) {
          throw new IllegalStateException(
              "test command output replacement requires an idle physical writer");
        }
        ReaderStreamBinding binding = readerStreamBinding;
        if (binding == null) {
          outputStream = nextOutputStream;
          binding = currentReaderStreamBinding();
        }
        binding.analysisOutputMutationLock.lock();
        try {
          // This test seam deliberately keeps the same reader incarnation. Production stream
          // replacement continues to go through initializeStreams(), which retires the old
          // binding. Holding the queue fence makes the in-place replacement quiescent for both
          // analysis/state and ordinary physical writers.
          binding.rawOutput = stream;
          binding.output = nextOutputStream;
          outputStream = nextOutputStream;
        } finally {
          binding.analysisOutputMutationLock.unlock();
        }
      }
    }
  }

  void installFreshCommandOutputForTest(OutputStream stream) {
    initializeStreams(
        new ByteArrayInputStream(new byte[0]),
        stream,
        new ByteArrayInputStream(new byte[0]));
  }

  void installFreshCommandStreamsForTest(
      InputStream stdout, OutputStream stdin, InputStream stderr) {
    initializeStreams(stdout, stdin, stderr);
  }

  void processCommandResponseLineForTest(String line) {
    processCommandResponseLine(line);
  }

  void dispatchReaderLineForTest(String line) throws IOException {
    ReaderStreamBinding binding = currentReaderStreamBinding();
    if (shouldQuarantineUnmatchedStrictResponseCarrier(line, binding)) {
      isCommandLine = false;
      return;
    }
    try {
      boolean recentParameterResponse = captureRecentParameterResponse(line, binding);
      EngineGameResponseHandler engineGameHandler =
          recentParameterResponse ? null : engineGameResponseHandlerForLine(line, binding);
      if (recentParameterResponse) {
        isCommandLine = true;
      } else if (engineGameHandler != null) {
        parseLineForGenmovePk(line, binding, engineGameHandler);
      } else {
        runWithRestartBootstrapReceipt(
            binding.restartBootstrapReceipt, () -> parseLine(line, binding));
      }
      if (isCommandLine) {
        runWithRestartBootstrapReceipt(
            binding.restartBootstrapReceipt,
            () -> processCommandResponseLine(line, binding));
      }
    } finally {
      isCommandLine = false;
    }
  }

  void parseRecoveryStderrForTest(String line) {
    parseLineForError(line, currentReaderStreamBinding());
  }

  void parseStartupCommandResponseForTest(String line) throws IOException {
    dispatchReaderLineForTest(line);
  }

  void parseEngineGameLineForTest(String line) throws IOException {
    dispatchReaderLineForTest(line);
  }

  void resetGtpCommandStateForTest(String detail) {
    GtpCommandStateReset reset;
    synchronized (commandQueue()) {
      reset = resetGtpCommandStateLocked(detail);
    }
    notifyGtpCommandStateReset(reset);
  }

  void setBestMovesForEngineGameTest(List<MoveData> moves) {
    synchronized (analysisInfoMutationLock()) {
      List<MoveData> publishedMoves = moves == null ? List.of() : List.copyOf(moves);
      currentTotalPlayouts = MoveData.getPlayouts(publishedMoves);
      analysisInfoPayloadTarget = captureAnalysisInfoTarget();
      bestMoves = publishedMoves;
      analysisInfoEpoch++;
    }
  }

  void notifyAutoPkForEngineGameTest(
      boolean playImmediately, EngineManager.EngineGamePrimaryContext context) {
    notifyAutoPK(playImmediately, context);
  }

  boolean runPendingResponseHandlerForTest(String line) {
    return runPendingResponseHandlerForLine(line);
  }

  public boolean sendRawConsoleCommand(String command) {
    synchronized (engineArbitrationLock()) {
      if (isTrackingStreamSession(exclusiveGtpSession) && !isSafeRawGtpQuery(command)) {
        return false;
      }
    }
    return sendCommand(
        command, null, null, false, true, TrackingReleaseReason.SAFE_READ_ONLY_QUERY, null, false);
  }

  private static boolean isSafeRawGtpQuery(String command) {
    if (command == null || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
      return false;
    }
    String trimmed = command.trim();
    if (trimmed.isEmpty()) {
      return false;
    }
    String[] tokens = trimmed.split("\\s+");
    String name = tokens[0].toLowerCase(Locale.ROOT);
    if (name.equals("known_command")) {
      return tokens.length == 2;
    }
    return tokens.length == 1
        && (name.equals("name")
            || name.equals("version")
            || name.equals("protocol_version")
            || name.equals("list_commands")
            || name.equals("showboard"));
  }

  private void sendCommand(String command, Runnable onResponse) {
    sendCommand(command, onResponse, null, false, true);
  }

  private void sendCommand(
      String command, Runnable onResponse, boolean failOnSendError, boolean mirrorToSecondEngine) {
    sendCommand(command, onResponse, null, failOnSendError, mirrorToSecondEngine);
  }

  private boolean sendCommand(
      String command,
      Runnable onResponse,
      CommandSendFailureHandler onSendFailure,
      boolean failOnSendError,
      boolean mirrorToSecondEngine) {
    return sendCommand(
        command,
        onResponse,
        onSendFailure,
        failOnSendError,
        mirrorToSecondEngine,
        TrackingReleaseReason.ORDINARY_OPERATION,
        null,
        false,
        null);
  }

  private boolean sendCommand(
      String command,
      Runnable onResponse,
      CommandSendFailureHandler onSendFailure,
      boolean failOnSendError,
      boolean mirrorToSecondEngine,
      TrackingReleaseReason releaseReason,
      QueuedCommandSettlement settlement,
      boolean rejectForExclusiveWinner) {
    return sendCommand(
        command,
        onResponse,
        onSendFailure,
        failOnSendError,
        mirrorToSecondEngine,
        releaseReason,
        settlement,
        rejectForExclusiveWinner,
        null);
  }

  private boolean sendCommand(
      String command,
      Runnable onResponse,
      CommandSendFailureHandler onSendFailure,
      boolean failOnSendError,
      boolean mirrorToSecondEngine,
      TrackingReleaseReason releaseReason,
      QueuedCommandSettlement settlement,
      boolean rejectForExclusiveWinner,
      ReaderStreamBinding readBoardGmaResponseBinding) {
    EngineManager.EngineGameTransaction startupTransaction =
        engineGameStartupCommandContext.get();
    if (settlement == null
        && startupTransaction != null
        && !(onResponse instanceof EngineGameResponseHandler)) {
      ReaderStreamBinding startupBinding = currentReaderStreamBinding();
      if (startupBinding == null) {
        throw new IllegalStateException(
            "Engine-game startup command has no live reader binding: " + command);
      }
      settlement =
          new EngineGameStartupCommandPermit(
              this, startupTransaction, startupBinding);
      readBoardGmaResponseBinding = startupBinding;
    }
    if (shouldDropStaleForegroundRestoreCommand()
        || shouldSuppressNormalCommandForForegroundAnalysis()
        || shouldDropCommandDuringInitialBoardSynchronization(command)
        || shouldRejectCommandDuringLifecycleCompletion(command)) {
      if (startupTransaction != null) {
        throw new IllegalStateException(
            "Engine-game startup command was rejected before enqueue: " + command);
      }
      return false;
    }
    if (Lizzie.config.isDoubleEngineMode()) {
      if ((command.startsWith("heat") || command.startsWith("kata-raw"))
          && !this.isKatago
          && Lizzie.leelaz2 != null
          && this == Lizzie.leelaz2) heatcount = new ArrayList<Integer>();
      if (Lizzie.leelaz2 != null && this == Lizzie.leelaz2)
        if (this.isLeela0110) {
          if (command.startsWith("lz-") || command.startsWith("kata-")) this.leela0110Ponder(true);
          return false;
        } else if (this.isKatago && !Lizzie.leelaz.isKatago) {
          if (command.startsWith("lz-")) {
            command = "kata-" + command.substring(3);
          }
          if (command.startsWith("heat")) {
            command = ("kata-raw-nn " + new Random().nextInt(8));
          }
        }
      if (Lizzie.leelaz2 != null
          && this == Lizzie.leelaz2
          && !this.isKatago
          && Lizzie.leelaz.isKatago) {
        if (command.startsWith("kata-raw")) {
          command = "heatmap";
        }
        if (command.startsWith("kata-")) {
          command = "lz-" + command.substring(5);
        }

        String[] params = command.trim().split(" ");
        if (params.length > 2) {
          if (params[params.length - 2].equals("ownership")) {
            command = command.substring(0, command.length() - 14);
          }
        }
      }
    }
    Leelaz mirroredEngine = mirrorToSecondEngine ? resolveDefaultCommandMirrorEngine() : null;
    String mirroredCommand =
        mirroredEngine == null ? null : mirroredEngine.prepareDefaultMirroredCommand(command);
    if (mirroredCommand == null) {
      mirroredEngine = null;
    }
    boolean atomicallyMirrored =
        mirroredEngine != null
            && startupTransaction == null
            && !Thread.holdsLock(engineArbitrationLock())
            && !Thread.holdsLock(mirroredEngine.engineArbitrationLock())
            && !Thread.holdsLock(commandQueue())
            && !Thread.holdsLock(mirroredEngine.commandQueue());
    boolean enqueued =
        atomicallyMirrored
            ? enqueueOrdinaryCommandWithMirror(
                command,
                onResponse,
                onSendFailure,
                failOnSendError || foregroundRestoreCommandSession.get() != null,
                settlement,
                releaseReason,
                rejectForExclusiveWinner,
                readBoardGmaResponseBinding,
                mirroredEngine,
                mirroredCommand)
            : enqueueOrdinaryCommand(
                command,
                onResponse,
                onSendFailure,
                failOnSendError || foregroundRestoreCommandSession.get() != null,
                settlement,
                releaseReason,
                rejectForExclusiveWinner,
                true,
                false,
                readBoardGmaResponseBinding,
                null);
    if (!enqueued) {
      if (startupTransaction != null) {
        throw new IllegalStateException(
            "Engine-game startup command was rejected during enqueue: " + command);
      }
      return false;
    }
    if (!(settlement instanceof StartupCommandDelivery)) {
      trySendCommandFromQueue();
    }
    if (mirroredEngine != null) {
      if (atomicallyMirrored) {
        mirroredEngine.trySendCommandFromQueue();
        mirroredEngine.startPonderTime = this.startPonderTime;
      } else {
        sendDefaultCommandMirror(mirroredEngine, command);
      }
    }
    return true;
  }

  /**
   * Applies the same secondary-engine protocol adaptation as a direct public send, without doing
   * any queue or transport work. Returning {@code null} preserves the dedicated Leela 0.11.0
   * secondary path, which does not accept ordinary default mirroring.
   */
  private String prepareDefaultMirroredCommand(String command) {
    if (Lizzie.config == null
        || !Lizzie.config.isDoubleEngineMode()
        || Lizzie.leelaz2 == null
        || this != Lizzie.leelaz2) {
      return command;
    }
    if ((command.startsWith("heat") || command.startsWith("kata-raw")) && !isKatago) {
      heatcount = new ArrayList<Integer>();
    }
    if (isLeela0110) {
      if (command.startsWith("lz-") || command.startsWith("kata-")) {
        leela0110Ponder(true);
      }
      return null;
    }
    String adapted = command;
    if (isKatago && !Lizzie.leelaz.isKatago) {
      if (adapted.startsWith("lz-")) {
        adapted = "kata-" + adapted.substring(3);
      }
      if (adapted.startsWith("heat")) {
        adapted = "kata-raw-nn " + new Random().nextInt(8);
      }
    }
    if (!isKatago && Lizzie.leelaz.isKatago) {
      if (adapted.startsWith("kata-raw")) {
        adapted = "heatmap";
      }
      if (adapted.startsWith("kata-")) {
        adapted = "lz-" + adapted.substring(5);
      }
      String[] params = adapted.trim().split(" ");
      if (params.length > 2 && params[params.length - 2].equals("ownership")) {
        adapted = adapted.substring(0, adapted.length() - 14);
      }
    }
    return adapted;
  }

  /** Atomically appends one ordinary command to both endpoint queues in stable lock order. */
  private boolean enqueueOrdinaryCommandWithMirror(
      String command,
      Runnable onResponse,
      CommandSendFailureHandler onSendFailure,
      boolean failOnSendError,
      QueuedCommandSettlement settlement,
      TrackingReleaseReason releaseReason,
      boolean rejectForExclusiveWinner,
      ReaderStreamBinding readBoardGmaResponseBinding,
      Leelaz mirroredEngine,
      String mirroredCommand) {
    RestartBootstrapReceipt bootstrapReceipt = restartBootstrapReceiptContext.get();
    RestartBootstrapReceipt mirroredBootstrapReceipt =
        mirroredEngine.restartBootstrapReceiptContext.get();
    EngineManager.EngineGameTransaction startupTransactionAtAdmission =
        engineGameStartupCommandContext.get();
    OrdinaryEnqueueEffects effects = new OrdinaryEnqueueEffects();
    OrdinaryEnqueueEffects mirroredEffects = mirroredEngine.new OrdinaryEnqueueEffects();
    boolean accepted =
        withOrderedEngineArbitrationAndQueueLocks(
            this,
            mirroredEngine,
            () -> {
              if (!canAdmitOrdinaryCommandLocked(
                      command,
                      releaseReason,
                      rejectForExclusiveWinner,
                      readBoardGmaResponseBinding,
                      null,
                      bootstrapReceipt,
                      startupTransactionAtAdmission)
                  || !mirroredEngine.canAdmitOrdinaryCommandLocked(
                      mirroredCommand,
                      TrackingReleaseReason.ORDINARY_OPERATION,
                      true,
                      null,
                      null,
                      mirroredBootstrapReceipt,
                      null)) {
                return false;
              }
              QueuedCommand primaryCommand =
                  enqueueAdmittedOrdinaryCommandLocked(
                      command,
                      onResponse,
                      onSendFailure,
                      failOnSendError,
                      settlement,
                      releaseReason,
                      true,
                      false,
                      readBoardGmaResponseBinding,
                      null,
                      bootstrapReceipt,
                      effects);
              QueuedCommand mirrorCommand =
                  mirroredEngine.enqueueAdmittedOrdinaryCommandLocked(
                      mirroredCommand,
                      null,
                      null,
                      mirroredEngine.foregroundRestoreCommandSession.get() != null,
                      null,
                      TrackingReleaseReason.ORDINARY_OPERATION,
                      true,
                      false,
                      null,
                      null,
                      mirroredBootstrapReceipt,
                      mirroredEffects);
              primaryCommand.installInternalSendFailureHandler(
                  failure ->
                      mirroredEngine.cancelPairedOrdinaryCommandBeforeOutputWrite(
                          mirrorCommand, failure));
              mirrorCommand.installInternalSendFailureHandler(
                  failure -> cancelPairedOrdinaryCommandBeforeOutputWrite(primaryCommand, failure));
              return true;
            });
    if (!accepted) {
      return false;
    }
    publishOrdinaryEnqueueEffects(effects);
    mirroredEngine.publishOrdinaryEnqueueEffects(mirroredEffects);
    return true;
  }

  /** Fallback for the rare legacy call site that already owns an endpoint command queue. */
  private void sendDefaultCommandMirror(Leelaz mirroredEngine, String command) {
    mirroredEngine.sendCommand(command);
    mirroredEngine.startPonderTime = this.startPonderTime;
  }

  private boolean enqueueOrdinaryCommand(
      String command,
      Runnable onResponse,
      CommandSendFailureHandler onSendFailure,
      boolean failOnSendError,
      QueuedCommandSettlement settlement,
      TrackingReleaseReason releaseReason,
      boolean rejectForExclusiveWinner,
      boolean countCommand,
      boolean noLeelaz2Coalescing,
      ReaderStreamBinding readBoardGmaResponseBinding,
      Object expectedLeela0110StateToken) {
    ArrayDeque<QueuedCommand> currentQueue = commandQueue();
    RestartBootstrapReceipt bootstrapReceipt = restartBootstrapReceiptContext.get();
    EngineManager.EngineGameTransaction startupTransactionAtAdmission =
        engineGameStartupCommandContext.get();
    if (Thread.holdsLock(currentQueue)
        && exclusiveGtpSession == null
        && trackingHandoffGate == null
        && settlement == null
        && readBoardGmaResponseBinding == null
        && expectedLeela0110StateToken == null) {
      if (shouldDropStaleForegroundRestoreCommand()
          || shouldSuppressNormalCommandForForegroundAnalysis()
          || shouldDropCommandDuringInitialBoardSynchronizationAtAdmission(command)
          || (exclusiveGtpLifecycleQueueGate
              && !isCurrentRestartBootstrapReceiptLocked(bootstrapReceipt))) {
        return false;
      }
      ArrayDeque<QueuedCommand> targetQueue = commandQueueForCurrentThread();
      if (countCommand) {
        cmdNumber++;
        calculateModifyNumber();
      }
      if (!targetQueue.isEmpty()
          && !targetQueue.peekLast().requiresStateReset()
          && shouldCoalesceQueuedCommand(targetQueue.peekLast().command, noLeelaz2Coalescing)) {
        targetQueue.removeLast();
        if (countCommand) {
          cmdNumber--;
        }
      }
      targetQueue.addLast(
          foregroundRestoreCommand(
              new QueuedCommand(
                  command,
                  onResponse,
                  onSendFailure,
                  failOnSendError,
                  null,
                  countCommand,
                  bootstrapReceipt,
                  readBoardGmaResponseBinding,
                  expectedLeela0110StateToken),
              targetQueue));
      return true;
    }
    OrdinaryEnqueueEffects effects = new OrdinaryEnqueueEffects();
    synchronized (engineArbitrationLock()) {
      synchronized (commandQueue()) {
        if (!canAdmitOrdinaryCommandLocked(
            command,
            releaseReason,
            rejectForExclusiveWinner,
            readBoardGmaResponseBinding,
            expectedLeela0110StateToken,
            bootstrapReceipt,
            startupTransactionAtAdmission)) {
          return false;
        }
        enqueueAdmittedOrdinaryCommandLocked(
            command,
            onResponse,
            onSendFailure,
            failOnSendError,
            settlement,
            releaseReason,
            countCommand,
            noLeelaz2Coalescing,
            readBoardGmaResponseBinding,
            expectedLeela0110StateToken,
            bootstrapReceipt,
            effects);
      }
    }
    publishOrdinaryEnqueueEffects(effects);
    return true;
  }

  private final class OrdinaryEnqueueEffects {
    private final List<QueuedCommand> coalescedCommands = new ArrayList<>();
    private final List<TrackingDispositionNotification> trackingNotifications = new ArrayList<>();
    private ExclusiveGtpSession trackingSession;
    private int releaseStopCommandId;
  }

  /** Requires endpoint arbitration followed by the command-queue monitor. */
  private boolean canAdmitOrdinaryCommandLocked(
      String command,
      TrackingReleaseReason releaseReason,
      boolean rejectForExclusiveWinner,
      ReaderStreamBinding readBoardGmaResponseBinding,
      Object expectedLeela0110StateToken,
      RestartBootstrapReceipt bootstrapReceipt,
      EngineManager.EngineGameTransaction startupTransactionAtAdmission) {
    if (shouldDropStaleForegroundRestoreCommand()
        || shouldSuppressNormalCommandForForegroundAnalysis()
        || shouldDropCommandDuringInitialBoardSynchronizationAtAdmission(command)
        || shouldRejectCommandDuringLifecycleCompletion(command)
        || (startupTransactionAtAdmission != null
            && !EngineManager.isEngineGameOutputAdmissionOpen(startupTransactionAtAdmission))
        || (exclusiveGtpLifecycleQueueGate
            && !isCurrentRestartBootstrapReceiptLocked(bootstrapReceipt))
        || (readBoardGmaResponseBinding != null
            && (readerStreamBinding != readBoardGmaResponseBinding
                || readBoardGmaResponseBinding.terminated))
        || (expectedLeela0110StateToken != null
            && !isCurrentLeela0110PonderState(
                readBoardGmaResponseBinding,
                startupTransactionAtAdmission,
                expectedLeela0110StateToken))
        || (rejectForExclusiveWinner
            && !isExactSnapshotRestoreAdmissionContextActive()
            && (engineStateUnrestored
                || readBoardGmaReservation != null
                || trackingHandoffGate != null
                || foregroundRestoreInProgress
                || (exclusiveGtpLifecycleTransition
                    && exclusiveGtpLifecycleOwner != Thread.currentThread())
                || (exclusiveGtpSession != null
                    && !isTrackingStreamSession(exclusiveGtpSession))))) {
      return false;
    }
    ExclusiveGtpSession trackingSession = exclusiveGtpSession;
    return !isTrackingStreamSession(trackingSession)
        || releaseReason != TrackingReleaseReason.SAFE_READ_ONLY_QUERY
        || isSafeRawGtpQuery(command);
  }

  /** Requires endpoint arbitration followed by the command-queue monitor and prior admission. */
  private QueuedCommand enqueueAdmittedOrdinaryCommandLocked(
      String command,
      Runnable onResponse,
      CommandSendFailureHandler onSendFailure,
      boolean failOnSendError,
      QueuedCommandSettlement settlement,
      TrackingReleaseReason releaseReason,
      boolean countCommand,
      boolean noLeelaz2Coalescing,
      ReaderStreamBinding readBoardGmaResponseBinding,
      Object expectedLeela0110StateToken,
      RestartBootstrapReceipt bootstrapReceipt,
      OrdinaryEnqueueEffects effects) {
    ArrayDeque<QueuedCommand> targetQueue = commandQueueForCurrentThread();
    if (countCommand) {
      cmdNumber++;
      calculateModifyNumber();
    }
    if (!targetQueue.isEmpty()
        && shouldCoalesceQueuedCommand(targetQueue.peekLast().command, noLeelaz2Coalescing)) {
      QueuedCommand coalesced = targetQueue.removeLast();
      effects.coalescedCommands.add(coalesced);
      if (countCommand) {
        cmdNumber--;
      }
    }
    QueuedCommand queuedCommand =
        foregroundRestoreCommand(
            new QueuedCommand(
                command,
                onResponse,
                onSendFailure,
                failOnSendError,
                settlement,
                countCommand,
                bootstrapReceipt,
                readBoardGmaResponseBinding,
                expectedLeela0110StateToken),
            targetQueue);
    targetQueue.addLast(queuedCommand);
    ExclusiveGtpSession trackingSession = exclusiveGtpSession;
    if (isTrackingStreamSession(trackingSession) && trackingHandoffGate == null) {
      TrackingReleaseDisposition disposition =
          releaseReason == TrackingReleaseReason.SAFE_READ_ONLY_QUERY
              ? TrackingReleaseDisposition.FROZEN_BY_SAFE
              : TrackingReleaseDisposition.CLEARED;
      TrackingDispositionNotification notification =
          advanceTrackingReleaseDispositionLocked(trackingSession, disposition, releaseReason);
      if (notification != null) {
        effects.trackingNotifications.add(notification);
      }
      if (!trackingSession.releaseRequested) {
        trackingSession.releaseRequested = true;
        if (trackingSession.active) {
          effects.trackingSession = trackingSession;
          effects.releaseStopCommandId = claimTrackingReleaseStopLocked(trackingSession);
        }
      }
    }
    return queuedCommand;
  }

  /** Publishes callbacks and tracking transport work only after both endpoint locks are released. */
  private void publishOrdinaryEnqueueEffects(OrdinaryEnqueueEffects effects) {
    for (QueuedCommand coalesced : effects.coalescedCommands) {
      RuntimeException failure =
          new IllegalStateException("Queued GTP command was coalesced before output write");
      if (coalesced.cancelBeforeOutputWrite(failure)) {
        try {
          coalesced.notifySendFailure(failure);
        } catch (Throwable ignored) {
          // A cancelled request callback cannot strand the replacement command.
        }
      }
    }
    for (TrackingDispositionNotification notification : effects.trackingNotifications) {
      notifyTrackingDisposition(notification);
    }
    if (effects.releaseStopCommandId != 0) {
      sendTrackingReleaseStop(effects.trackingSession, effects.releaseStopCommandId);
    }
  }

  private QueuedCommand foregroundRestoreCommand(
      QueuedCommand command, ArrayDeque<QueuedCommand> targetQueue) {
    command.foregroundRestoreCommand = targetQueue == foregroundRestoreCommandQueue();
    return command;
  }

  private boolean shouldCoalesceQueuedCommand(String command, boolean noLeelaz2Coalescing) {
    if (noLeelaz2Coalescing) {
      return command.startsWith("lz-analyze")
          || command.startsWith("kata-analyze")
          || command.startsWith("kata-raw")
          || command.startsWith("heatmap");
    }
    return (isKatago
            && (command.startsWith("kata-analyze")
                || command.startsWith("kata-raw")
                || command.startsWith("stop-ponder")))
        || (!isKatago
            && (command.startsWith("lz-analyze")
                || command.startsWith("analyze")
                || command.startsWith("heatmap")));
  }

  private static boolean isTrackingStreamSession(ExclusiveGtpSession session) {
    return session != null
        && session.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY
        && session.owner instanceof TrackingStreamLease
        && !session.closing
        && !session.closedCallbackRun;
  }

  private Leelaz resolveDefaultCommandMirrorEngine() {
    if (Lizzie.config == null || !Lizzie.config.isDoubleEngineMode()) {
      return null;
    }
    Leelaz primaryEngine = Lizzie.leelaz;
    Leelaz secondaryEngine = Lizzie.leelaz2;
    if (primaryEngine == null || secondaryEngine == null || primaryEngine == secondaryEngine) {
      return null;
    }
    if (this == primaryEngine) {
      return secondaryEngine;
    }
    return null;
  }

  public void loadSgf(Path sgfFile, Runnable afterConsumed) {
    Leelaz mirroredEngine = resolveLoadSgfMirrorEngine();
    loadSgf(sgfFile, mirroredEngine, afterConsumed);
  }

  void loadSgf(Path sgfFile, Leelaz mirroredEngine, Runnable afterConsumed) {
    if (afterConsumed == null) {
      loadSgf(sgfFile);
      return;
    }
    loadTrackedSgf(sgfFile, mirroredEngine, afterConsumed, null);
  }

  final void loadSgfForExactSnapshotRestore(
      Path sgfFile,
      Leelaz mirroredEngine,
      ExactSnapshotRestoreAdmission admission,
      Runnable afterConsumed,
      Runnable onDispatchStarted) {
    restoreExactSnapshotPosition(
        "loadsgf " + sgfFile.toAbsolutePath(),
        sgfFile,
        mirroredEngine,
        admission,
        afterConsumed,
        onDispatchStarted);
  }

  final void restoreInBandForExactSnapshotRestore(
      String command,
      Leelaz mirroredEngine,
      ExactSnapshotRestoreAdmission admission,
      Runnable afterConsumed,
      Runnable onDispatchStarted) {
    restoreInBandForExactSnapshotRestore(
        List.of(command), mirroredEngine, admission, afterConsumed, onDispatchStarted);
  }

  final void restoreInBandForExactSnapshotRestore(
      List<String> commands,
      Leelaz mirroredEngine,
      ExactSnapshotRestoreAdmission admission,
      Runnable afterConsumed,
      Runnable onDispatchStarted) {
    List<String> capturedCommands = List.copyOf(commands);
    if (capturedCommands.isEmpty()) {
      throw new IllegalArgumentException("commands");
    }
    for (String command : capturedCommands) {
      if (command.trim().isEmpty()) {
        throw new IllegalArgumentException("commands");
      }
    }
    restoreExactSnapshotCommands(
        capturedCommands, null, mirroredEngine, admission, afterConsumed, onDispatchStarted);
  }

  private void restoreExactSnapshotPosition(
      String command,
      Path sgfFile,
      Leelaz mirroredEngine,
      ExactSnapshotRestoreAdmission admission,
      Runnable afterConsumed,
      Runnable onDispatchStarted) {
    restoreExactSnapshotCommands(
        List.of(command),
        sgfFile,
        mirroredEngine,
        admission,
        afterConsumed,
        onDispatchStarted);
  }

  private void restoreExactSnapshotCommands(
      List<String> commands,
      Path sgfFile,
      Leelaz mirroredEngine,
      ExactSnapshotRestoreAdmission admission,
      Runnable afterConsumed,
      Runnable onDispatchStarted) {
    if (afterConsumed == null) {
      throw new IllegalArgumentException("afterConsumed");
    }
    if (!isExactSnapshotRestoreAdmissionValid(admission)
        || (mirroredEngine != null
            && !mirroredEngine.isExactSnapshotRestoreAdmissionValid(admission))) {
      throw new ExactSnapshotEngineRestore.Failure(
          ExactSnapshotEngineRestore.FailureCategory.ADMISSION_STALE,
          "Exact snapshot restore command was not admitted.");
    }
    withExactSnapshotRestoreAdmission(
        admission,
        () -> {
          if (onDispatchStarted != null) {
            onDispatchStarted.run();
          }
          loadTrackedSnapshotCommands(commands, sgfFile, mirroredEngine, afterConsumed, admission);
        });
  }

  private void loadTrackedSgf(
      Path sgfFile,
      Leelaz mirroredEngine,
      Runnable afterConsumed,
      ExactSnapshotRestoreAdmission admission) {
    loadTrackedSnapshotCommand(
        "loadsgf " + sgfFile.toAbsolutePath(),
        sgfFile,
        mirroredEngine,
        afterConsumed,
        admission);
  }

  private void loadTrackedSnapshotCommand(
      String command,
      Path sgfFile,
      Leelaz mirroredEngine,
      Runnable afterConsumed,
      ExactSnapshotRestoreAdmission admission) {
    loadTrackedSnapshotCommands(
        List.of(command), sgfFile, mirroredEngine, afterConsumed, admission);
  }

  private void loadTrackedSnapshotCommands(
      List<String> commands,
      Path sgfFile,
      Leelaz mirroredEngine,
      Runnable afterConsumed,
      ExactSnapshotRestoreAdmission admission) {
    LoadSgfDispatch dispatch =
        new LoadSgfDispatch(afterConsumed, snapshotRestoreResponseDescription(commands, sgfFile));
    RuntimeException sendFailure = null;
    for (String command : commands) {
      RuntimeException authoritySendFailure =
          sendTrackedSnapshotCommand(this, command, sgfFile, dispatch, admission);
      if (sendFailure == null) {
        sendFailure = authoritySendFailure;
      }
      if (mirroredEngine != null) {
        RuntimeException mirroredSendFailure =
            sendTrackedSnapshotCommand(mirroredEngine, command, sgfFile, dispatch, admission);
        if (sendFailure == null) {
          sendFailure = mirroredSendFailure;
        }
      }
    }
    if (sendFailure == null) {
      sendFailure = dispatch.failure();
    }
    dispatch.finishDispatch();
    if (sendFailure != null) {
      dispatch.recordFailure(sendFailure);
      dispatch.scheduleFallbackCleanupAfterSendFailure();
      throw sendFailure;
    }
    dispatch.awaitCompletion();
    RuntimeException responseFailure = dispatch.failure();
    if (responseFailure != null) {
      throw responseFailure;
    }
  }

  private static String snapshotRestoreResponseDescription(
      List<String> commands, Path sgfFile) {
    if (sgfFile != null) {
      return "loadsgf";
    }
    if (commands.size() == 1) {
      return "exact snapshot restore command '" + commands.get(0) + "'";
    }
    return "exact snapshot restore command batch " + commands;
  }

  Leelaz resolveLoadSgfMirrorEngine() {
    if (Lizzie.config == null || !Lizzie.config.isDoubleEngineMode()) {
      return null;
    }
    Leelaz primaryEngine = Lizzie.leelaz;
    Leelaz secondaryEngine = Lizzie.leelaz2;
    if (primaryEngine == null || secondaryEngine == null || primaryEngine == secondaryEngine) {
      return null;
    }
    if (this == primaryEngine) {
      return secondaryEngine;
    }
    if (this == secondaryEngine) {
      return primaryEngine;
    }
    return null;
  }

  private void sendLoadSgfCommand(
      Leelaz targetEngine,
      Path sgfFile,
      Runnable onResponse,
      CommandSendFailureHandler onSendFailure) {
    sendSnapshotRestoreCommand(
        targetEngine,
        "loadsgf " + sgfFile.toAbsolutePath(),
        onResponse,
        onSendFailure,
        null);
  }

  private void sendSnapshotRestoreCommand(
      Leelaz targetEngine,
      String command,
      Runnable onResponse,
      CommandSendFailureHandler onSendFailure,
      ExactSnapshotRestoreAdmission admission) {
    if (admission != null) {
      if (!targetEngine.sendExactSnapshotRestoreCommand(
          command, onResponse, onSendFailure, admission)) {
        throw new ExactSnapshotEngineRestore.Failure(
            ExactSnapshotEngineRestore.FailureCategory.SEND_FAILED,
            "Exact snapshot restore command was rejected: " + command);
      }
      return;
    }
    targetEngine.sendCommand(command, onResponse, onSendFailure, true, false);
  }

  private RuntimeException sendTrackedSnapshotCommand(
      Leelaz targetEngine,
      String command,
      Path sgfFile,
      LoadSgfDispatch dispatch,
      ExactSnapshotRestoreAdmission admission) {
    TrackedLoadSgfConsumer trackedConsumer =
        new TrackedLoadSgfConsumer(targetEngine, sgfFile, command, dispatch);
    try {
      Runnable send =
          () ->
              sendSnapshotRestoreCommand(
                  targetEngine,
                  command,
                  trackedConsumer.responseHandler(),
                  trackedConsumer.sendFailureHandler(),
                  admission);
      if (admission == null) {
        send.run();
      } else {
        targetEngine.withExactSnapshotRestoreAdmission(admission, send);
      }
      return null;
    } catch (RuntimeException ex) {
      trackedConsumer.failFromSend(ex);
      return ex;
    }
  }

  private RuntimeException buildSnapshotRestoreResponseFailure(
      String command, Path sgfFile, String responseLine) {
    String line = responseLine == null ? "" : responseLine.trim();
    String detail = line.isEmpty() ? "? snapshot restore failed" : line;
    if (sgfFile != null) {
      return new ExactSnapshotEngineRestore.Failure(
          ExactSnapshotEngineRestore.FailureCategory.GTP_ERROR,
          "GTP loadsgf failed for '" + sgfFile.toAbsolutePath() + "' with response: " + detail);
    }
    return new ExactSnapshotEngineRestore.Failure(
        ExactSnapshotEngineRestore.FailureCategory.GTP_ERROR,
        "GTP snapshot restore failed for '" + command + "' with response: " + detail);
  }

  private static Thread newLoadSgfCleanupThread(Runnable runnable) {
    Thread thread = new Thread(runnable, "lizzie-loadsgf-cleanup");
    thread.setDaemon(true);
    return thread;
  }

  public void sendCommandNoLeelaz2(String command) {
    sendCommandNoLeelaz2(command, null);
  }

  boolean sendCommandToCapturedRestoreTarget(
      String command, ExactSnapshotRestoreAdmission admission) {
    return sendExactSnapshotRestoreCommand(command, admission);
  }

  void onCapturedRestoreClearCommandSent() {
    synchronized (commandQueue()) {
      currentCmdNum = Math.max(cmdNumber - 2, currentCmdNum);
    }
  }

  public final void sendCapturedRestoreCommand(String command) {
    ExactSnapshotRestoreAdmission admission = exactSnapshotRestoreAdmissionContext.get();
    if (!sendExactSnapshotRestoreCommand(command, admission)) {
      throw new ExactSnapshotEngineRestore.Failure(
          ExactSnapshotEngineRestore.FailureCategory.TAIL_REJECTED,
          "Captured snapshot restore command was rejected: " + command);
    }
  }

  final boolean sendExactSnapshotRestoreCommand(
      String command, Runnable onResponse, CommandSendFailureHandler onSendFailure) {
    return sendExactSnapshotRestoreCommand(command, onResponse, onSendFailure, null);
  }

  private boolean sendExactSnapshotRestoreCommandAdmitted(
      String command,
      Runnable onResponse,
      CommandSendFailureHandler onSendFailure,
      ExactSnapshotRestoreAdmission admission) {
    return sendCommand(
        command,
        onResponse,
        onSendFailure,
        true,
        false,
        TrackingReleaseReason.ORDINARY_OPERATION,
        null,
        true,
        expectedReadBoardGmaResponseBinding(admission));
  }

  private ReaderStreamBinding expectedReadBoardGmaResponseBinding(
      ExactSnapshotRestoreAdmission admission) {
    if (admission == null || admission.owner != ExactSnapshotRestoreOwner.READ_BOARD_GMA) {
      return null;
    }
    Object incarnation =
        this == admission.authority
            ? admission.authorityIncarnation
            : this == admission.mirror ? admission.mirrorIncarnation : null;
    return incarnation instanceof ReaderStreamBinding binding ? binding : null;
  }
  private boolean sendExactSnapshotRestoreCommand(
      String command,
      Runnable onResponse,
      CommandSendFailureHandler onSendFailure,
      ExactSnapshotRestoreAdmission admission) {
    if (!isExactSnapshotRestoreAdmissionValid(admission)) {
      return false;
    }
    final boolean[] sent = new boolean[1];
    boolean ownerCurrent =
        admission.runIfCurrentBoardSyncPrimary(
            () ->
                sent[0] =
                    sendExactSnapshotRestoreCommandAdmitted(command, onResponse, onSendFailure, admission));
    return ownerCurrent && sent[0];
  }

  boolean sendExactSnapshotRestoreCommand(
      String command, ExactSnapshotRestoreAdmission admission) {
    return sendExactSnapshotRestoreCommand(command, null, null, admission);
  }


  private void enqueueSavedGtpConfiguration() {
    configurationProfileCommand(gtpConfigurationProtocol, gtpConfigurationProfile)
        .ifPresent(command -> sendCommand(command, null, false, false));
  }

  public static Optional<String> configurationProfileCommand(
      String protocol, JSONObject profile) {
    if (!GtpConfigurationProbe.ZENGTP_PROTOCOL.equals(protocol) || profile == null) {
      return Optional.empty();
    }
    return Optional.of(GtpConfigurationProbe.ZENGTP_SET_COMMAND + " " + profile.toString());
  }

  public boolean supportsGtpConfiguration() {
    return commandLists.contains(GtpConfigurationProbe.ZENGTP_SET_COMMAND);
  }

  public void applyGtpConfigurationProfile(
      JSONObject profile, Consumer<JSONObject> onSuccess, Consumer<String> onFailure) {
    Optional<String> command =
        configurationProfileCommand(GtpConfigurationProbe.ZENGTP_PROTOCOL, profile);
    if (command.isEmpty()) {
      if (onFailure != null) {
        onFailure.accept("Configuration profile is empty");
      }
      return;
    }
    if (!started || !supportsGtpConfiguration()) {
      if (onFailure != null) {
        onFailure.accept("The running engine does not expose visual configuration");
      }
      return;
    }
    sendCommand(
        command.get(),
        () -> {
          if (isCurrentCommandResponseError()) {
            if (onFailure != null) {
              onFailure.accept(gtpResponsePayload(currentCommandResponseLine()));
            }
            return;
          }
          JSONObject response = new JSONObject();
          String payload = gtpResponsePayload(currentCommandResponseLine());
          if (!payload.isEmpty()) {
            try {
              response = new JSONObject(payload);
            } catch (JSONException ignored) {
              response.put("raw", payload);
            }
          }
          if (onSuccess != null) {
            onSuccess.accept(response);
          }
        },
        failure -> {
          if (onFailure != null) {
            onFailure.accept(
                failure == null ? "Failed to send configuration" : failure.getMessage());
          }
        },
        true,
        false);
  }

  static String gtpResponsePayload(String line) {
    if (line == null) {
      return "";
    }
    String trimmed = line.trim();
    if (trimmed.isEmpty() || (trimmed.charAt(0) != '=' && trimmed.charAt(0) != '?')) {
      return trimmed;
    }
    int index = 1;
    while (index < trimmed.length() && Character.isDigit(trimmed.charAt(index))) {
      index++;
    }
    return trimmed.substring(index).trim();
  }

  private void sendCommandNoLeelaz2(String command, Runnable onResponse) {
    sendCommandNoLeelaz2(command, onResponse, null, null);
  }

  private void sendCommandNoLeelaz2(
      String command,
      Runnable onResponse,
      ReaderStreamBinding expectedBinding,
      Object expectedLeela0110StateToken) {
    if (shouldDropStaleForegroundRestoreCommand()
        || shouldSuppressNormalCommandForForegroundAnalysis()) {
      return;
    }
    if (Lizzie.config.isDoubleEngineMode()) {
      if ((command.startsWith("heat") || command.startsWith("kata-raw"))
          && !this.isKatago
          && Lizzie.leelaz2 != null
          && this == Lizzie.leelaz2) heatcount = new ArrayList<Integer>();
      if (Lizzie.leelaz2 != null
          && this == Lizzie.leelaz2
          && this.isKatago
          && !Lizzie.leelaz.isKatago) {
        if (command.startsWith("lz-")) {
          command = "kata-" + command.substring(3);
        }
        if (command.startsWith("heat")) {
          command = ("kata-raw-nn " + new Random().nextInt(8));
        }
      }
      if (Lizzie.leelaz2 != null
          && this == Lizzie.leelaz2
          && !this.isKatago
          && Lizzie.leelaz.isKatago) {
        if (command.startsWith("kata-raw")) {
          command = "heatmap";
        }
        if (command.startsWith("kata-")) {
          command = "lz-" + command.substring(5);
        }

        String[] params = command.trim().split(" ");
        if (params.length > 2) {
          if (params[params.length - 2].equals("ownership")) {
            command = command.substring(0, command.length() - 14);
          }
        }
      }
    }
    EngineManager.EngineGameTransaction startupTransaction =
        engineGameStartupCommandContext.get();
    ReaderStreamBinding startupBinding =
        startupTransaction == null ? null : currentReaderStreamBinding();
    if (expectedBinding != null
        && (readerStreamBinding != expectedBinding
            || expectedBinding.terminated
            || (startupTransaction != null && startupBinding != expectedBinding)
            || (expectedLeela0110StateToken != null
                && !isCurrentLeela0110PonderState(
                    expectedBinding,
                    startupTransaction,
                    expectedLeela0110StateToken)))) {
      if (startupTransaction != null) {
        throw new IllegalStateException(
            "engine-game startup command lost its Leela0110 reader binding: " + command);
      }
      return;
    }
    ReaderStreamBinding commandBinding =
        expectedBinding != null ? expectedBinding : startupBinding;
    QueuedCommandSettlement startupPermit =
        startupTransaction == null
            ? null
            : new EngineGameStartupCommandPermit(this, startupTransaction, commandBinding);
    if (!enqueueOrdinaryCommand(
        command,
        onResponse,
        null,
        foregroundRestoreCommandSession.get() != null,
        startupPermit,
        TrackingReleaseReason.ORDINARY_OPERATION,
        false,
        false,
        true,
        commandBinding,
        expectedLeela0110StateToken)) {
      if (startupTransaction != null) {
        throw new IllegalStateException(
            "engine-game startup command was rejected before enqueue: " + command);
      }
      return;
    }
    trySendCommandFromQueue();
    if (canSetNotPlayed) {
      canSetNotPlayed = false;
      played = false;
    }
  }

  /** Sends a command from command queue for leelaz to execute if it is ready */
  private void trySendCommandFromQueue() {
    if (SwingUtilities.isEventDispatchThread() && canDispatchOrdinaryCommandOffEventThread()) {
      requestCommandDispatchOffEventThread();
      return;
    }
    trySendCommandFromQueueNow();
  }

  private boolean canDispatchOrdinaryCommandOffEventThread() {
    if (foregroundRestoreCommandSession.get() != null
        || isExactSnapshotRestoreAdmissionContextActive()
        || lifecycleCompletionCommandContext.get() != null
        || ordinaryLiveBoardForwardingContext.get() != null
        || engineGameStartupCommandContext.get() != null
        || Boolean.TRUE.equals(deferredEngineGameRecoveryStartupContext.get())
        || updateEngineStartAttemptContext.get() != null
        || analysisOutputRecoveryTokenContext.get() != null
        || startupPostActionCommandContext.get() != null
        || restartBootstrapReceiptContext.get() != null) {
      return false;
    }
    synchronized (commandQueue()) {
      ArrayDeque<QueuedCommand> targetQueue =
          foregroundRestoreInProgress ? foregroundRestoreCommandQueue() : commandQueue();
      QueuedCommand queueHead = targetQueue.peekFirst();
      return queueHead != null
          && !queueHead.failOnSendError
          && queueHead.onSendFailure == null
          && queueHead.internalSendFailureHandler == null
          && queueHead.settlement == null
          && queueHead.restartBootstrapReceipt == null
          && queueHead.readBoardGmaResponseBinding == null
          && queueHead.expectedLeela0110StateToken == null
          && !queueHead.foregroundRestoreCommand;
    }
  }

  private void requestCommandDispatchOffEventThread() {
    eventDispatchCommandRequests.incrementAndGet();
    scheduleCommandDispatchOffEventThread();
  }

  private void scheduleCommandDispatchOffEventThread() {
    if (!eventDispatchCommandScheduled.compareAndSet(false, true)) {
      return;
    }
    COMMAND_DISPATCH_EXECUTOR.execute(
        () -> {
          long handledRequest = eventDispatchCommandRequests.get();
          try {
            trySendCommandFromQueueNow();
          } catch (RuntimeException | Error failure) {
            String detail =
                "Asynchronous GTP command dispatch failed: "
                    + safeFailureDetail(failure, failure.getClass().getSimpleName());
            rememberRecentLine(recentStderrLines, detail);
            System.err.println(detail);
            if (failure instanceof Error) {
              throw (Error) failure;
            }
          } finally {
            eventDispatchCommandScheduled.set(false);
            if (eventDispatchCommandRequests.get() != handledRequest) {
              scheduleCommandDispatchOffEventThread();
            }
          }
        });
  }

  private static Thread newCommandDispatchThread(Runnable runnable) {
    Thread thread =
        new Thread(
            runnable,
            "lizzie-gtp-command-dispatch-" + COMMAND_DISPATCH_THREAD_SEQUENCE.incrementAndGet());
    thread.setDaemon(true);
    return thread;
  }

  private void trySendCommandFromQueueNow() {
    // Defer sending "lz-analyze" if leelaz is not ready yet.
    // Though all commands should be deferred theoretically,
    // only "lz-analyze" is differed here for fear of
    // possible hang-up by missing response for some reason.
    // cmdQueue can be replaced with a mere String variable in this case,
    // but it is kept for future change of our mind.
    QueuedCommand queuedCommand;
    synchronized (commandQueue()) {
      if (exclusiveGtpSession != null
          || trackingHandoffGate != null
          || readerStreamRebindInProgress
          || normalCommandSendInProgress) {
        return;
      }
      ArrayDeque<QueuedCommand> targetQueue =
          foregroundRestoreInProgress ? foregroundRestoreCommandQueue() : commandQueue();
      if (!foregroundRestoreInProgress && requireResponseBeforeSend && !isResponseUpToPreDate()) {
        return;
      }
      if (targetQueue.isEmpty()) {
        return;
      }
      QueuedCommand queueHead = targetQueue.peekFirst();
      if (exclusiveGtpLifecycleQueueGate
          && !isCurrentRestartBootstrapReceiptLocked(queueHead.restartBootstrapReceipt)) {
        return;
      }
      if (!foregroundRestoreInProgress && !isResponseUpToPreCommand()) {
        String lastQueuedCommand = targetQueue.peekLast().command;
        if ((isKatago
                && (lastQueuedCommand.startsWith("kata-analyze")
                    || lastQueuedCommand.startsWith("kata-raw")
                    || lastQueuedCommand.startsWith("stop-ponder")))
            || (!isKatago
                && (lastQueuedCommand.startsWith("lz-analyze")
                    || lastQueuedCommand.startsWith("analyze")
                    || lastQueuedCommand.startsWith("heatmap")))) return;
      }
      queuedCommand = targetQueue.removeFirst();
      normalCommandSendInProgress = true;
      normalCommandBeingSent = queuedCommand;
    }
    String command = queuedCommand.command;
    if (command.equals("stop-ponder")) command = "stop";
    Runnable deferredResponse = null;
    Throwable sendFailure = null;
    try {
      deferredResponse =
          sendCommandToLeelaz(command, queuedCommand);
    } catch (RuntimeException | Error ex) {
      sendFailure = ex;
    } finally {
      synchronized (commandQueue()) {
        if (normalCommandBeingSent == queuedCommand) {
          normalCommandBeingSent = null;
        }
        normalCommandSendInProgress = false;
        commandQueue().notifyAll();
      }
    }
    if (sendFailure != null) {
      RuntimeException settlementFailure =
          sendFailure instanceof RuntimeException
              ? (RuntimeException) sendFailure
              : buildCommandSendFailure(
                  command,
                  safeFailureDetail(sendFailure, sendFailure.getClass().getSimpleName()),
                  sendFailure);
      if (!queuedCommand.isStateResetAfterOutputWritePublished()) {
        try {
          queuedCommand.publishInternalSendFailure(settlementFailure, false);
        } catch (RuntimeException | Error notificationFailure) {
          appendEngineCleanupFailure(sendFailure, notificationFailure);
        }
      }
      try {
        queuedCommand.publishSettlementFailure(settlementFailure);
      } catch (RuntimeException | Error settlementCallbackFailure) {
        appendEngineCleanupFailure(sendFailure, settlementCallbackFailure);
      }
      try {
        if (queuedCommand.onSendFailure != null) {
          queuedCommand.onSendFailure.onSendFailure(settlementFailure);
        }
      } catch (RuntimeException | Error notificationFailure) {
        appendEngineCleanupFailure(sendFailure, notificationFailure);
      }
      try {
        trySendCommandFromQueue();
      } catch (RuntimeException | Error dispatchFailure) {
        appendEngineCleanupFailure(sendFailure, dispatchFailure);
      }
      if (sendFailure instanceof Error) {
        throw (Error) sendFailure;
      }
      throw (RuntimeException) sendFailure;
    }
    try {
      if (deferredResponse != null) {
        deferredResponse.run();
      }
    } finally {
      trySendCommandFromQueue();
    }
  }

  /**
   * Sends a command for leelaz to execute
   *
   * @param command a GTP command containing no newline characters
   */
  private Runnable sendCommandToLeelaz(
      String command, QueuedCommand queuedCommand) {
    Runnable deferredResponse = null;
    logInterestingCommand(command, "sendCommandToLeelaz");
    if (command.startsWith("fixed_handicap")
        || (isKatago && command.startsWith("place_free_handicap"))) isSettingHandicap = true;
    if (command.startsWith("benchmark")) {
      synchronized (commandQueue()) {
        currentCmdNum++;
      }
    }
    Runnable responseHandler =
        queuedCommand.onResponse == null ? NO_OP_RESPONSE_HANDLER : queuedCommand.onResponse;
    PendingResponseHandler pendingHandler =
        buildPendingResponseHandler(command, responseHandler, queuedCommand);
    String commandLine = buildCommandLine(command, pendingHandler.responseCommandId);
    BufferedOutputStream currentOutputStream = outputStream;
    ReaderStreamBinding outputBinding = currentReaderStreamBinding();
    AnalysisStateMutation stateMutation = analysisStateMutation(command);
    AnalysisOutputOwnershipPublication analysisOutputPublication = null;
    EngineManager.TransactionlessAnalysisWriteLease transactionlessAnalysisWrite = null;
    EngineManager.EngineGameAnalysisWriteLease exactAnalysisWrite = null;
    EngineManager.EngineGameStateWriteLease exactStateWrite = null;
    boolean analysisOutputBindingLocked = false;
    boolean leela0110PonderPhysicalWriteLocked = false;
    boolean physicalWriteCompleted = false;
    if (currentOutputStream != null) {
      if (queuedCommand.restartBootstrapReceipt != null) {
        beforeRestartBootstrapOutputWriteClaim();
      }
      ReaderStreamBinding expectedOutputBinding = queuedCommand.readBoardGmaResponseBinding;
      if (expectedOutputBinding != null
          && (outputBinding != expectedOutputBinding
              || expectedOutputBinding.terminated
              || (queuedCommand.expectedLeela0110StateToken != null
                  && !isCurrentLeela0110PonderState(
                      expectedOutputBinding,
                      queuedCommand.engineGameTransaction(),
                      queuedCommand.expectedLeela0110StateToken)))) {
        queuedCommand.cancelBeforeOutputWrite(
            new IllegalStateException(
                "GTP command reader binding changed before physical output write"));
        finishRejectedCommandBeforeOutputWrite(queuedCommand, pendingHandler);
        return null;
      }
      if (!claimRestartBootstrapReceiptForOutputWrite(queuedCommand, currentOutputStream)) {
        finishRejectedCommandBeforeOutputWrite(queuedCommand, pendingHandler);
        return null;
      }
      if (!addPendingResponseHandler(pendingHandler)) {
        finishRejectedCommandBeforeOutputWrite(queuedCommand, pendingHandler);
        return null;
      }
      boolean publishesAnalysisOutputOwnership =
          shouldPublishAnalysisOutputOwnership(queuedCommand, outputBinding);
      boolean startsAnalysisInfoPayload = startsNewAnalysisInfoPayload(command);
      EngineManager.EngineGameTransaction analysisOutputTransaction =
          queuedCommand.engineGameTransaction();
      try {
        // Canonical physical-write order is transaction/global admission -> short selection
        // validation -> binding owner -> output stream. Never wait for either manager admission
        // while already owning the output monitor.
        if ((publishesAnalysisOutputOwnership || stateMutation != AnalysisStateMutation.NONE)
            && analysisOutputTransaction == null) {
          transactionlessAnalysisWrite =
              EngineManager.claimTransactionlessAnalysisWrite(
                  this,
                  outputBinding,
                  outputBinding == null ? null : outputBinding.analysisOutputRecoveryToken);
          if (transactionlessAnalysisWrite == null) {
            throw new AnalysisOutputAdmissionFailure(
                "analysis/state output is blocked by an unrelated game or recovery owner");
          }
        }
        if (!queuedCommand.beginOutputWrite()) {
          finishRejectedCommandBeforeOutputWrite(queuedCommand, pendingHandler);
          return null;
        }
        EngineManager.EngineGamePrimaryContext exactAnalysisOutputContext = null;
        if (stateMutation != AnalysisStateMutation.NONE && analysisOutputTransaction != null) {
          exactStateWrite =
              EngineManager.claimEngineGameStateWrite(
                  analysisOutputTransaction, this, outputBinding);
          if (exactStateWrite == null) {
            throw new AnalysisOutputAdmissionFailure(
                "engine-game state output lost ownership before physical command output");
          }
        } else if (publishesAnalysisOutputOwnership && analysisOutputTransaction != null) {
          exactAnalysisWrite =
              EngineManager.claimEngineGameAnalysisWrite(
                  analysisOutputTransaction, this, outputBinding);
          if (exactAnalysisWrite == null) {
            throw new AnalysisOutputAdmissionFailure(
                "engine-game analysis output lost ownership before physical command output");
          }
          exactAnalysisOutputContext = exactAnalysisWrite.context;
        }
        if (queuedCommand.expectedLeela0110StateToken != null) {
          // A Leela 0.11.0 timer command is valid only for one exact ponder-state incarnation.
          // Hold this lease from the final state check through flush. Stop/rebind/reprepare take
          // the same lease before changing that state, so an old same-binding timer can never
          // cross the successor's linearization point after a successful final check.
          leela0110PonderPhysicalWriteLock.lock();
          leela0110PonderPhysicalWriteLocked = true;
          if (!isCurrentLeela0110PonderState(
              outputBinding,
              analysisOutputTransaction,
              queuedCommand.expectedLeela0110StateToken)) {
            queuedCommand.cancelBeforeOutputWrite(
                new IllegalStateException(
                    "Leela0110 ponder state changed before physical output write"));
            finishRejectedCommandBeforeOutputWrite(queuedCommand, pendingHandler);
            return null;
          }
        }
        try {
          if (publishesAnalysisOutputOwnership
              || startsAnalysisInfoPayload
              || stateMutation != AnalysisStateMutation.NONE) {
            if (outputBinding == null) {
              throw new AnalysisOutputAdmissionFailure(
                  "analysis payload has no current reader binding");
            }
            outputBinding.analysisOutputMutationLock.lock();
            analysisOutputBindingLocked = true;
          }
          try {
            synchronized (currentOutputStream) {
              if (analysisOutputBindingLocked
                  && (readerStreamBinding != outputBinding
                      || outputBinding.terminated
                      || outputBinding.readerShutdownRequested
                      || outputBinding.output != currentOutputStream
                      || outputStream != currentOutputStream)) {
                throw new AnalysisOutputAdmissionFailure(
                    "analysis/state output lost its live reader before physical output");
              }
              if (stateMutation != AnalysisStateMutation.NONE) {
                // Retire and clear/advance the old payload before the first state-command byte is
                // observable. The manager lease prevents an already-admitted parser mutation from
                // crossing this boundary, and caller-side cleanup cannot retire a queued successor.
                bindAnalysisStateLineageAtPhysicalWrite(queuedCommand, outputBinding);
                applyAnalysisStateMutation(stateMutation);
                if (exactStateWrite != null) {
                  // Exact terminal cancellation must never wait for a blocked transport flush.
                  // Release binding before transaction: otherwise a parser can acquire the
                  // transaction fence, block behind this binding for the duration of flush, and
                  // indirectly make terminal settlement wait for the transport. Generation has
                  // already retired the old owner, so neither fence is needed after this point.
                  analysisOutputBindingLocked = false;
                  outputBinding.analysisOutputMutationLock.unlock();
                  exactStateWrite.close();
                  exactStateWrite = null;
                }
              }
              if (publishesAnalysisOutputOwnership) {
                analysisOutputPublication =
                    publishAnalysisOutputOwnershipAtPhysicalWrite(
                        queuedCommand,
                        outputBinding,
                        transactionlessAnalysisWrite,
                        exactAnalysisOutputContext);
              }
              currentOutputStream.write((commandLine + "\n").getBytes());
              currentOutputStream.flush();
              if (startsAnalysisInfoPayload) {
                // The binding fence keeps even an immediate reader line from committing before
                // the new physical command invalidates the prior payload generation.
                resetAnalysisInfoPayload(false);
              }
              physicalWriteCompleted = true;
            }
          } finally {
            if (analysisOutputBindingLocked) {
              analysisOutputBindingLocked = false;
              outputBinding.analysisOutputMutationLock.unlock();
            }
          }
        } finally {
          if (leela0110PonderPhysicalWriteLocked) {
            leela0110PonderPhysicalWriteLocked = false;
            leela0110PonderPhysicalWriteLock.unlock();
          }
        }
      } catch (Exception | Error e) {
        String detail = safeFailureDetail(e, e.getClass().getSimpleName());
        RuntimeException commandFailure = buildCommandSendFailure(commandLine, detail, e);
        Throwable cleanupFailure = e;
        if (!physicalWriteCompleted) {
          queuedCommand.failAnalysisStateLineageAfterSendFailure();
          // Publish delivery failure before any diagnostic or stream-recovery callback can throw.
          // Startup workers may be waiting on this exact write and must never strand their lease.
          cleanupFailure =
              runEngineCleanupStep(
                  cleanupFailure,
                  () -> queuedCommand.publishPhysicalWriteFailure(e, commandFailure));
          cleanupFailure =
              runEngineCleanupStep(
                  cleanupFailure,
                  () -> queuedCommand.markStateResetAfterOutputWrite(commandFailure));
          cleanupFailure =
              runEngineCleanupStep(
                  cleanupFailure, queuedCommand::publishStateResetAfterOutputWrite);
          final boolean[] pollutedStreamDetected = new boolean[1];
          cleanupFailure =
              runEngineCleanupStep(
                  cleanupFailure,
                  () ->
                      pollutedStreamDetected[0] =
                          clearBufferedCommandBytesAfterSendFailure(currentOutputStream));
          AnalysisOutputOwnershipPublication failedPublication = analysisOutputPublication;
          if (failedPublication != null) {
            cleanupFailure =
                runEngineCleanupStep(
                    cleanupFailure,
                    () -> failedPublication.outputWriteFailed(pollutedStreamDetected[0]));
          }
          if (pollutedStreamDetected[0]) {
            cleanupFailure =
                runEngineCleanupStep(
                    cleanupFailure,
                    () ->
                        invalidateCommandOutputStreamAfterPartialWrite(
                            currentOutputStream, commandLine));
          }
          cleanupFailure =
              runEngineCleanupStep(
                  cleanupFailure, () -> removePendingResponseHandler(pendingHandler));
          cleanupFailure =
              retireOutstandingResponseCountOnSendFailure(pendingHandler, cleanupFailure);
        }
        String diagnostic = "Failed to send GTP command '" + commandLine + "': " + detail;
        cleanupFailure =
            runEngineCleanupStep(
                cleanupFailure, () -> rememberRecentLine(recentStderrLines, diagnostic));
        cleanupFailure =
            runEngineCleanupStep(cleanupFailure, () -> System.err.println(diagnostic));
        if (e instanceof Error) {
          // Settlement must wake an exact startup waiter, but broadening this boundary must not
          // downgrade JVM Errors for ordinary command callers that historically observed them.
          throw (Error) e;
        }
        if (physicalWriteCompleted
            || e instanceof AnalysisOutputAdmissionFailure
            || queuedCommand.failOnSendError) {
          throw commandFailure;
        }
        deferredResponse = queuedCommand.onResponse;
        runEngineCleanupStep(cleanupFailure, () -> noteCommandFailed(pendingHandler));
      } finally {
        if (analysisOutputBindingLocked) {
          analysisOutputBindingLocked = false;
          outputBinding.analysisOutputMutationLock.unlock();
        }
        if (leela0110PonderPhysicalWriteLocked) {
          leela0110PonderPhysicalWriteLocked = false;
          leela0110PonderPhysicalWriteLock.unlock();
        }
        if (transactionlessAnalysisWrite != null) {
          transactionlessAnalysisWrite.close();
        }
        if (exactAnalysisWrite != null) {
          exactAnalysisWrite.close();
        }
        if (exactStateWrite != null) {
          exactStateWrite.close();
        }
      }
      if (physicalWriteCompleted) {
        try {
          noteCommandSent(pendingHandler, commandLine);
        } catch (RuntimeException observationFailure) {
          // Diagnostics are downstream of a completed protocol write and cannot redefine delivery.
          rememberRecentLine(
              recentStderrLines,
              "Command observation failed after GTP delivery: "
                  + safeFailureDetail(observationFailure, "observation failure"));
        }
        queuedCommand.publishWriteCompleted();
      }
      if (EngineManager.isEngineGame()) {
        int commandNumber = commandNumberSnapshot();
        Lizzie.gtpConsole.addCommandForEngineGame(
            command,
            commandNumber,
            oriEnginename,
            EngineManager.engineGameInfo.isBlackEngine(currentEngineN()));

      } else if (Lizzie.gtpConsole != null
          && ((Lizzie.config != null && Lizzie.config.alwaysGtp)
              || Lizzie.gtpConsole.isVisible())) {
        Lizzie.gtpConsole.addCommand(command, commandNumberSnapshot(), oriEnginename);
      }
    } else {
      // Preserve the historical fail-closed local cleanup even when no command transport exists.
      // No physical successor can publish ownership on this unavailable stream.
      applyAnalysisStateMutation(stateMutation);
      String detail = "outputStream unavailable";
      rememberRecentLine(
          recentStderrLines, "Failed to send GTP command '" + commandLine + "': " + detail);
      System.err.println("Failed to send GTP command '" + commandLine + "': " + detail);
      RuntimeException commandFailure = buildCommandSendFailure(commandLine, detail, null);
      Throwable cleanupFailure = commandFailure;
      if (queuedCommand.cancelBeforeOutputWrite(commandFailure)) {
        cleanupFailure =
            runEngineCleanupStep(
                cleanupFailure,
                () -> queuedCommand.publishInternalSendFailure(commandFailure, false));
        cleanupFailure =
            runEngineCleanupStep(
                cleanupFailure, () -> queuedCommand.publishSettlementFailure(commandFailure));
      }
      cleanupFailure =
          retireOutstandingResponseCountOnSendFailure(pendingHandler, cleanupFailure);
      if (queuedCommand.failOnSendError) {
        throw commandFailure;
      }
      deferredResponse = queuedCommand.onResponse;
      runEngineCleanupStep(cleanupFailure, () -> noteCommandFailed(pendingHandler));
    }
    if (canSetNotPlayed) {
      canSetNotPlayed = false;
      played = false;
    }
    return deferredResponse;
  }

  private boolean claimRestartBootstrapReceiptForOutputWrite(
      QueuedCommand queuedCommand, BufferedOutputStream currentOutputStream) {
    RestartBootstrapReceipt receipt = queuedCommand.restartBootstrapReceipt;
    if (receipt == null) {
      return true;
    }
    synchronized (engineArbitrationLock()) {
      synchronized (commandQueue()) {
        if (!isCurrentRestartBootstrapReceiptLocked(receipt)
            || currentOutputStream != receipt.output) {
          queuedCommand.cancelBeforeOutputWrite(
              new IllegalStateException("Restart bootstrap receipt is no longer current"));
          return false;
        }
        return true;
      }
    }
  }

  /** Completes a command which was dequeued but lost its exact pre-write capability. */
  private void finishRejectedCommandBeforeOutputWrite(
      QueuedCommand queuedCommand, PendingResponseHandler pendingHandler) {
    RuntimeException failure = queuedCommand.cancellationFailure();
    if (failure == null) {
      failure = new IllegalStateException("GTP command was cancelled before output write");
      queuedCommand.cancelBeforeOutputWrite(failure);
    }
    Throwable cleanupFailure = failure;
    cleanupFailure =
        runEngineCleanupStep(
            cleanupFailure, () -> removePendingResponseHandler(pendingHandler));
    cleanupFailure =
        retireOutstandingResponseCountOnSendFailure(pendingHandler, cleanupFailure);
    RuntimeException settledFailure = failure;
    cleanupFailure =
        runEngineCleanupStep(
            cleanupFailure, () -> queuedCommand.notifySendFailure(settledFailure));
    runEngineCleanupStep(cleanupFailure, () -> noteCommandFailed(pendingHandler));
    if (queuedCommand.failOnSendError) {
      throw failure;
    }
  }

  /**
   * Cancels exact engine-game commands that have not acquired the physical output stream.
   *
   * <p>A WRITE_CLAIMED request is deliberately left installed: bytes may already be visible to
   * the engine, so its unnumbered output must remain quarantined under the old carrier until the
   * exact terminal response drains or the reader binding is replaced.
   */
  void cancelEngineGameRequests(EngineManager.EngineGameTransaction transaction) {
    if (transaction == null) {
      return;
    }
    // Retire the exact Leela 0.11.0 follow-up source before releasing this endpoint's
    // cancellation barrier. Otherwise its BoardData sentinel can prevent the successor from
    // installing a timer, or its delayed "name" task can race the successor's output owner.
    cancelLeela0110PonderForEngineGameTransaction(transaction);
    RuntimeException failure =
        new IllegalStateException("Engine-game transaction ended before command output write");
    List<QueuedCommand> cancelled = new ArrayList<>();
    List<PendingResponseHandler> removedPending = new ArrayList<>();
    synchronized (commandQueue()) {
      cancelQueuedEngineGameRequests(commandQueue(), transaction, failure, cancelled);
      cancelQueuedEngineGameRequests(
          foregroundRestoreCommandQueue(), transaction, failure, cancelled);
      cancelEngineGameRequestBeforeWrite(
          normalCommandBeingSent, transaction, failure, cancelled);
      ArrayDeque<PendingResponseHandler> pending = pendingResponseHandlers();
      synchronized (pending) {
        Iterator<PendingResponseHandler> iterator = pending.iterator();
        while (iterator.hasNext()) {
          PendingResponseHandler response = iterator.next();
          QueuedCommand command = response.queuedCommand;
          if (!command.belongsToEngineGameTransaction(transaction)
              || command.isEngineGamePhysicalWriteClaimed()
              || !command.isCancelledBeforeOutputWrite()) {
            continue;
          }
          iterator.remove();
          removedPending.add(response);
        }
      }
      for (QueuedCommand command : cancelled) {
        if (command.claimCommandCountRetirement()) {
          cmdNumber = Math.max(1, cmdNumber - 1);
        }
      }
      if (currentCmdNum > cmdNumber - 1) {
        currentCmdNum = cmdNumber - 1;
      }
      commandQueue().notifyAll();
    }
    Throwable notificationFailure = null;
    for (QueuedCommand command : cancelled) {
      try {
        command.notifySendFailure(failure);
      } catch (RuntimeException | Error callbackFailure) {
        notificationFailure = appendEngineCleanupFailure(notificationFailure, callbackFailure);
      }
    }
    for (PendingResponseHandler response : removedPending) {
      noteCommandFailed(response);
    }
    try {
      trySendCommandFromQueue();
    } catch (RuntimeException | Error dispatchFailure) {
      notificationFailure = appendEngineCleanupFailure(notificationFailure, dispatchFailure);
    }
    rethrowEngineCleanupFailure(notificationFailure);
  }

  private void cancelQueuedEngineGameRequests(
      ArrayDeque<QueuedCommand> queue,
      EngineManager.EngineGameTransaction transaction,
      RuntimeException failure,
      List<QueuedCommand> cancelled) {
    Iterator<QueuedCommand> iterator = queue.iterator();
    while (iterator.hasNext()) {
      QueuedCommand command = iterator.next();
      if (cancelEngineGameRequestBeforeWrite(command, transaction, failure, cancelled)) {
        iterator.remove();
      }
    }
  }

  private boolean cancelEngineGameRequestBeforeWrite(
      QueuedCommand command,
      EngineManager.EngineGameTransaction transaction,
      RuntimeException failure,
      List<QueuedCommand> cancelled) {
    if (command == null || !command.belongsToEngineGameTransaction(transaction)) {
      return false;
    }
    if (command.isEngineGamePhysicalWriteClaimed()) {
      return false;
    }
    command.cancelEngineGameBeforePhysicalWrite(transaction);
    if (!command.cancelBeforeOutputWrite(failure)) {
      return false;
    }
    if (!cancelled.contains(command)) {
      cancelled.add(command);
    }
    return true;
  }

  private RuntimeException buildCommandSendFailure(String command, String detail, Throwable cause) {
    String message = "Failed to send GTP command '" + command + "': " + detail;
    return cause == null
        ? new IllegalStateException(message)
        : new IllegalStateException(message, cause);
  }

  private boolean clearBufferedCommandBytesAfterSendFailure(BufferedOutputStream stream) {
    if (stream instanceof RecoverableBufferedOutputStream) {
      RecoverableBufferedOutputStream recoverableStream = (RecoverableBufferedOutputStream) stream;
      boolean partialWriteDetected = recoverableStream.consumePartialWriteDetected();
      recoverableStream.discardBufferedBytes();
      return partialWriteDetected;
    }
    return false;
  }

  private void invalidateCommandOutputStreamAfterPartialWrite(
      BufferedOutputStream failedOutputStream, String commandLine) {
    synchronized (commandQueue()) {
      if (outputStream == failedOutputStream) {
        outputStream = null;
      }
    }
    String diagnostic =
        "Invalidated polluted GTP output stream after partial write failure on command '"
            + commandLine
            + "'";
    rememberRecentLine(recentStderrLines, diagnostic);
    System.err.println(diagnostic);
  }

  public static BufferedOutputStream createCommandOutputStream(OutputStream stream) {
    if (stream == null) {
      return null;
    }
    return new RecoverableBufferedOutputStream(stream);
  }

  private Throwable retireOutstandingResponseCountOnSendFailure(
      PendingResponseHandler pendingHandler, Throwable primaryFailure) {
    QueuedCommand queuedCommand = pendingHandler.queuedCommand;
    if (!queuedCommand.claimCommandCountRetirement()) {
      return primaryFailure;
    }
    synchronized (commandQueue()) {
      cmdNumber = Math.max(1, cmdNumber - 1);
      if (currentCmdNum > cmdNumber - 1) {
        currentCmdNum = cmdNumber - 1;
      }
    }
    try {
      trySendCommandFromQueue();
    } catch (RuntimeException | Error dispatchFailure) {
      return appendEngineCleanupFailure(primaryFailure, dispatchFailure);
    }
    return primaryFailure;
  }

  private void retireFailedCommandCount(QueuedCommand queuedCommand) {
    if (!queuedCommand.claimCommandCountRetirement()) {
      return;
    }
    synchronized (commandQueue()) {
      cmdNumber = Math.max(1, cmdNumber - 1);
      if (currentCmdNum > cmdNumber - 1) {
        currentCmdNum = cmdNumber - 1;
      }
    }
  }

  private ArrayDeque<QueuedCommand> commandQueue() {
    if (cmdQueue == null) {
      cmdQueue = new ArrayDeque<QueuedCommand>();
    }
    return cmdQueue;
  }

  private Object engineArbitrationLock() {
    Object lock = engineArbitrationLock;
    if (lock != null) {
      return lock;
    }
    synchronized (this) {
      if (engineArbitrationLock == null) {
        engineArbitrationLock = new Object();
      }
      return engineArbitrationLock;
    }
  }

  private Object statefulOrdinaryPublicationLock() {
    Object lock = statefulOrdinaryPublicationLock;
    if (lock != null) {
      return lock;
    }
    synchronized (this) {
      if (statefulOrdinaryPublicationLock == null) {
        statefulOrdinaryPublicationLock = new Object();
      }
      return statefulOrdinaryPublicationLock;
    }
  }

  private Object initialBoardSynchronizationLock() {
    Object lock = initialBoardSynchronizationLock;
    if (lock != null) {
      return lock;
    }
    synchronized (this) {
      if (initialBoardSynchronizationLock == null) {
        initialBoardSynchronizationLock = new Object();
      }
      return initialBoardSynchronizationLock;
    }
  }

  private ArrayDeque<QueuedCommand> foregroundRestoreCommandQueue() {
    if (foregroundRestoreQueue == null) {
      foregroundRestoreQueue = new ArrayDeque<QueuedCommand>();
    }
    return foregroundRestoreQueue;
  }

  private ArrayDeque<QueuedCommand> commandQueueForCurrentThread() {
    return foregroundRestoreCommandSession.get() != null
        ? foregroundRestoreCommandQueue()
        : commandQueue();
  }

  private boolean shouldDropStaleForegroundRestoreCommand() {
    ExclusiveGtpSession session = foregroundRestoreCommandSession.get();
    return session != null && (session.restoreCompleted || foregroundRestoreSession != session);
  }

  private boolean shouldSuppressNormalCommandForForegroundAnalysis() {
    boolean suppress =
        suppressNormalCommandsForForegroundAnalysis
            && foregroundRestoreCommandSession.get() == null
            && !isExactSnapshotRestoreAdmissionContextActive();
    if (suppress && foregroundRestoreInProgress && foregroundRestoreSession != null) {
      foregroundRestoreSession.restoreInvalidated = true;
    }
    return suppress;
  }

  private boolean shouldDropCommandDuringInitialBoardSynchronization(String command) {
    if (isExactSnapshotRestoreAdmissionContextActive()) {
      return false;
    }
    OrdinaryLiveBoardForwardingExecution execution = ordinaryLiveBoardForwardingContext.get();
    if (execution != null && isOrdinaryForwardingOccupied()) {
      execution.rejectedByStartupAdmission = true;
      return true;
    }
    return isInitialBoardSynchronizationActive()
        && isInitialBoardSynchronizationLiveUpdateCommand(command);
  }

  private boolean shouldDropCommandDuringInitialBoardSynchronizationAtAdmission(String command) {
    if (!isInitialBoardSynchronizationLiveUpdateCommand(command)
        && ordinaryLiveBoardForwardingContext.get() == null) {
      return false;
    }
    synchronized (initialBoardSynchronizationLock()) {
      return shouldDropCommandDuringInitialBoardSynchronization(command);
    }
  }

  private static boolean isInitialBoardSynchronizationLiveUpdateCommand(String command) {
    if (command == null) {
      return false;
    }
    return command.startsWith("play ")
        || command.startsWith("undo")
        || command.startsWith("clear_board")
        || command.startsWith("lz-analyze")
        || command.startsWith("kata-analyze")
        || command.startsWith("analyze ")
        || command.startsWith("kata-raw")
        || command.startsWith("heat");
  }
  private PendingResponseHandler buildPendingResponseHandler(
      String command, Runnable handler, QueuedCommand queuedCommand) {
    boolean exactLoadSgf = isExactSnapshotLoadSgf(command, handler);
    ReaderStreamBinding responseBinding = queuedCommand.readBoardGmaResponseBinding;
    int protocolId = nextResponseCommandId(command, handler, queuedCommand);
    String engineId =
        loggingEngineId != null ? loggingEngineId : EngineObservation.identityFor(this);
    return new PendingResponseHandler(
        command,
        handler,
        queuedCommand,
        protocolId,
        requiresMatchingResponseCommandId(command, handler, exactLoadSgf, queuedCommand),
        exactLoadSgf,
        responseBinding,
        engineId,
        EngineObservation.commandIdentity(protocolId),
        EngineObservation.commandName(command),
        System.nanoTime());
  }

  private void noteCommandSent(PendingResponseHandler pendingHandler, String commandLine) {
    int depth;
    int inFlight;
    synchronized (commandQueue()) {
      depth = commandQueue().size();
    }
    synchronized (pendingResponseHandlers()) {
      inFlight = pendingResponseHandlers().size();
    }
    EngineObservation.recordCommandSent(
        pendingHandler.loggingEngineId,
        pendingHandler.loggingCommandId,
        pendingHandler.commandName,
        depth,
        inFlight);
    EngineObservation.traceRawCommand(
        pendingHandler.loggingEngineId, pendingHandler.loggingCommandId, commandLine);
  }

  private void noteCommandFailed(PendingResponseHandler pendingHandler) {
    EngineObservation.recordCommandOutcome(
        pendingHandler.loggingEngineId,
        pendingHandler.loggingCommandId,
        pendingHandler.commandName,
        "failed",
        0L);
  }


  private static boolean isTrackedExactSnapshotRestoreCommand(String command) {
    return command != null
        && (command.startsWith("loadsgf ")
            || command.equals("set_position")
            || command.startsWith("set_position ")
            || command.startsWith("play ")
            || command.startsWith("komi ")
            || command.startsWith("boardsize ")
            || command.startsWith("rectangular_boardsize "));
  }

  private boolean isExactSnapshotLoadSgf(String command, Runnable handler) {
    return isTrackedExactSnapshotRestoreCommand(command)
        && handler != NO_OP_RESPONSE_HANDLER
        && isExactSnapshotRestoreAdmissionContextActive();
  }

  private boolean requiresMatchingResponseCommandId(
      String command,
      Runnable handler,
      boolean exactLoadSgf,
      QueuedCommand queuedCommand) {
    return handler instanceof BoardSynchronizationResponseHandler
        || handler instanceof EngineGameResponseHandler
        || handler instanceof EngineGameTimeLeftResponseHandler
        || (queuedCommand != null && queuedCommand.isEngineGameCommand())
        || exactLoadSgf
        || (getRcentLine && isRecentParameterReadCommand(command))
        || (command != null
            && handler != NO_OP_RESPONSE_HANDLER
            && (command.startsWith("kata-get-param ")
                || command.startsWith("kata-set-param ")));
  }

  private static boolean isRecentParameterReadCommand(String command) {
    return "kata-get-param playoutDoublingAdvantage".equals(command)
        || "kata-get-param analysisWideRootNoise".equals(command)
        || "kata-get-rules".equals(command);
  }

  private int nextResponseCommandId(
      String command, Runnable handler, QueuedCommand queuedCommand) {
    if (command != null && command.startsWith("loadsgf ")) {
      return loadSgfResponseCommandIds.getAndIncrement();
    }
    if (isExactSnapshotLoadSgf(command, handler)) {
      return loadSgfResponseCommandIds.getAndIncrement();
    }
    if (handler instanceof BoardSynchronizationResponseHandler) {
      return boardSynchronizationResponseCommandIds.getAndIncrement();
    }
    if (handler instanceof EngineGameResponseHandler) {
      return engineGameResponseCommandIds.getAndIncrement();
    }
    if (handler instanceof EngineGameTimeLeftResponseHandler) {
      return engineGameResponseCommandIds.getAndIncrement();
    }
    if (queuedCommand != null && queuedCommand.isEngineGameCommand()) {
      return engineGameResponseCommandIds.getAndIncrement();
    }
    if (getRcentLine && isRecentParameterReadCommand(command)) {
      return readBoardGmaResponseCommandIds.getAndIncrement();
    }
    if (command != null
        && handler != NO_OP_RESPONSE_HANDLER
        && (command.startsWith("kata-get-param ") || command.startsWith("kata-set-param "))) {
      return readBoardGmaResponseCommandIds.getAndIncrement();
    }
    return NO_RESPONSE_COMMAND_ID;
  }

  private String buildCommandLine(String command, int responseCommandId) {
    if (responseCommandId == NO_RESPONSE_COMMAND_ID) {
      return command;
    }
    return responseCommandId + " " + command;
  }

  private ArrayDeque<PendingResponseHandler> pendingResponseHandlers() {
    if (pendingResponseHandlers == null) {
      pendingResponseHandlers = new ArrayDeque<PendingResponseHandler>();
    }
    return pendingResponseHandlers;
  }

  private boolean addPendingResponseHandler(PendingResponseHandler handler) {
    if (handler.queuedCommand.isCancelledBeforeOutputWrite()) {
      return false;
    }
    ArrayDeque<PendingResponseHandler> handlers = pendingResponseHandlers();
    synchronized (handlers) {
      handlers.addLast(handler);
    }
    if (handler.queuedCommand.isCancelledBeforeOutputWrite()) {
      removePendingResponseHandler(handler);
      return false;
    }
    return true;
  }

  private void removePendingResponseHandler(PendingResponseHandler handler) {
    ArrayDeque<PendingResponseHandler> handlers = pendingResponseHandlers();
    synchronized (handlers) {
      handlers.remove(handler);
    }
  }

  private PendingResponseHandler removePendingResponseHandler(Runnable handler) {
    ArrayDeque<PendingResponseHandler> handlers = pendingResponseHandlers();
    synchronized (handlers) {
      Iterator<PendingResponseHandler> iterator = handlers.descendingIterator();
      while (iterator.hasNext()) {
        PendingResponseHandler pendingHandler = iterator.next();
        if (pendingHandler.handler == handler) {
          iterator.remove();
          return pendingHandler;
        }
      }
    }
    return null;
  }

  private void retireTimedOutNormalCommand(Runnable handler) {
    retireCommandWithoutResponse(
        handler, false, !engineStateUnrestored, "Timed out waiting for GTP command response");
  }

  private void retireTrackedLoadSgfWithoutResponse(Runnable handler) {
    if (handler == null) {
      return;
    }
    retireCommandWithoutResponse(
        handler, true, true, "Timed out waiting for tracked loadsgf response");
  }

  private void retireCommandWithoutResponse(
      Runnable handler, boolean trackedLoadSgfOnly, boolean dispatchNext, String detail) {
    if (handler == null) {
      return;
    }
    RuntimeException failure = new IllegalStateException(detail);
    QueuedCommand retiredQueued = null;
    PendingResponseHandler retiredPending = null;
    synchronized (commandQueue()) {
      QueuedCommand sending = normalCommandBeingSent;
      if (matchesRetiredCommand(sending, handler, trackedLoadSgfOnly)
          && sending.cancelBeforeOutputWrite(failure)) {
        retiredQueued = sending;
      }
      if (retiredQueued == null) {
        retiredQueued =
            removeQueuedCommandWithoutResponse(
                commandQueue(), handler, trackedLoadSgfOnly, failure);
      }
      if (retiredQueued == null) {
        retiredQueued =
            removeQueuedCommandWithoutResponse(
                foregroundRestoreCommandQueue(), handler, trackedLoadSgfOnly, failure);
      }
      if (retiredQueued == null) {
        retiredPending =
            removePendingResponseHandlerIfRetirementSafe(handler, trackedLoadSgfOnly);
        if (retiredPending != null) {
          retirePendingResponseCountWithoutResponse(retiredPending);
          if (retiredPending.isExactSnapshotLoadSgf()) {
            loadSgfResponseQuarantined = true;
          }
        }
      }
    }
    if (retiredPending != null) {
      failAnalysisStateLineage(retiredPending.queuedCommand);
    }
    if (retiredQueued != null) {
      try {
        retiredQueued.publishSettlementFailure(failure);
      } catch (RuntimeException | Error notificationFailure) {
        appendEngineCleanupFailure(failure, notificationFailure);
      }
    }
    if (retiredPending != null) {
      try {
        retiredPending.queuedCommand.publishSettlementFailure(failure);
      } catch (RuntimeException | Error notificationFailure) {
        appendEngineCleanupFailure(failure, notificationFailure);
      }
    }
    if ((retiredQueued != null || retiredPending != null) && dispatchNext) {
      try {
        trySendCommandFromQueue();
      } catch (RuntimeException | Error dispatchFailure) {
        appendEngineCleanupFailure(failure, dispatchFailure);
      }
    }
  }

  private QueuedCommand removeQueuedCommandWithoutResponse(
      ArrayDeque<QueuedCommand> queue,
      Runnable handler,
      boolean trackedLoadSgfOnly,
      RuntimeException failure) {
    Iterator<QueuedCommand> iterator = queue.iterator();
    while (iterator.hasNext()) {
      QueuedCommand command = iterator.next();
      if (!matchesRetiredCommand(command, handler, trackedLoadSgfOnly)
          || !command.cancelBeforeOutputWrite(failure)) {
        continue;
      }
      iterator.remove();
      if (command.claimCommandCountRetirement()) {
        cmdNumber = Math.max(1, cmdNumber - 1);
      }
      if (currentCmdNum > cmdNumber - 1) {
        currentCmdNum = cmdNumber - 1;
      }
      return command;
    }
    return null;
  }

  private boolean matchesRetiredCommand(
      QueuedCommand command, Runnable handler, boolean trackedLoadSgfOnly) {
    return command != null
        && command.onResponse == handler
        && (!trackedLoadSgfOnly || isTrackedExactSnapshotRestoreCommand(command.command));
  }

  private PendingResponseHandler removePendingResponseHandlerIfRetirementSafe(
      Runnable handler, boolean trackedLoadSgfOnly) {
    ArrayDeque<PendingResponseHandler> handlers = pendingResponseHandlers();
    synchronized (handlers) {
      Iterator<PendingResponseHandler> iterator = handlers.descendingIterator();
      while (iterator.hasNext()) {
        PendingResponseHandler pendingHandler = iterator.next();
        if (pendingHandler.handler != handler
            || (trackedLoadSgfOnly && !pendingHandler.isTrackedLoadSgf())) {
          continue;
        }
        QueuedCommand command = pendingHandler.queuedCommand;
        boolean exactRetirementSafe =
            !command.isEngineGameCommand()
                || pendingHandler.requiresMatchingResponseCommandId
                || pendingHandler.isStaleResponseBinding(
                    pendingHandler.responseBinding, currentReaderStreamBinding());
        if (!exactRetirementSafe) {
          // An unnumbered response may still arrive on this live binding. Preserve both its queue
          // position and physical lease until the response/reset or bounded force retirement.
          return null;
        }
        iterator.remove();
        return pendingHandler;
      }
    }
    return null;
  }

  private void retirePendingResponseCountWithoutResponse(PendingResponseHandler handler) {
    synchronized (commandQueue()) {
      if (!handler.isOutstandingResponseRetired() && currentCmdNum < cmdNumber - 1) {
        currentCmdNum++;
      }
      if (currentCmdNum > cmdNumber - 1) {
        currentCmdNum = cmdNumber - 1;
      }
    }
  }

  private boolean hasPendingResponseHandler(Runnable handler) {
    ArrayDeque<PendingResponseHandler> handlers = pendingResponseHandlers();
    synchronized (handlers) {
      for (PendingResponseHandler pendingHandler : handlers) {
        if (pendingHandler.handler == handler) {
          return true;
        }
      }
      return false;
    }
  }

  private void completeEngineGamePassingResponse(
      EngineGameResponseHandler handler,
      ReaderStreamBinding binding,
      String coordinate) {
    int responseCommandId = NO_RESPONSE_COMMAND_ID;
    ArrayDeque<PendingResponseHandler> handlers = pendingResponseHandlers();
    synchronized (handlers) {
      for (PendingResponseHandler pending : handlers) {
        if (pending.handler == handler) {
          responseCommandId = pending.responseCommandId;
          break;
        }
      }
    }
    handler.awaitingPassingCoordinate = false;
    if (responseCommandId == NO_RESPONSE_COMMAND_ID) {
      handler.settle();
      EngineManager.failEngineGameTransaction(
          handler.context.transaction,
          new IllegalStateException("Engine-game Passing response lost its command identity"));
      return;
    }
    processCommandResponseLine("=" + responseCommandId + " " + coordinate, binding);
  }

  private void runNextPendingResponseHandler() {
    PendingResponseHandler handler;
    ArrayDeque<PendingResponseHandler> handlers = pendingResponseHandlers();
    synchronized (handlers) {
      if (handlers.isEmpty()) {
        return;
      }
      handler = handlers.removeFirst();
    }
    handler.run();
  }

  private int parseResponseCommandId(String line) {
    if (line == null || line.length() < 2) {
      return NO_RESPONSE_COMMAND_ID;
    }
    char prefix = line.charAt(0);
    if (prefix != '=' && prefix != '?') {
      return NO_RESPONSE_COMMAND_ID;
    }
    if (!isAsciiDigit(line.charAt(1))) {
      return NO_RESPONSE_COMMAND_ID;
    }
    int end = 1;
    while (end < line.length() && isAsciiDigit(line.charAt(end))) {
      end++;
    }
    if (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
      return NO_RESPONSE_COMMAND_ID;
    }
    try {
      return Integer.parseInt(line.substring(1, end));
    } catch (NumberFormatException ex) {
      return NO_RESPONSE_COMMAND_ID;
    }
  }

  private boolean isAsciiDigit(char value) {
    return value >= '0' && value <= '9';
  }

  private PendingResponseHandler pollPendingResponseHandler(String line) {
    return findPendingResponseHandler(line, true);
  }

  private PendingResponseHandler peekPendingResponseHandler(String line) {
    return findPendingResponseHandler(line, false);
  }

  private EngineManager.EngineGameMoveResponseContext pendingEngineGameMoveResponseContext(
      String line, ReaderStreamBinding binding) {
    EngineGameResponseHandler handler = engineGameResponseHandlerForLine(line, binding);
    return handler == null ? null : handler.context;
  }

  /**
   * Rejects a terminal-looking frame before any ordinary parser can observe it when a live strict
   * parser-isolated command is waiting and the frame does not identify a live pending command.
   */
  private boolean shouldQuarantineUnmatchedStrictResponseCarrier(
      String line, ReaderStreamBinding binding) {
    if (!isUnnumberedEngineGameTerminalCarrier(line)) {
      return false;
    }
    ReaderStreamBinding currentBinding = currentReaderStreamBinding();
    PendingResponseHandler matched = peekPendingResponseHandler(line);
    if (matched != null && !matched.isStaleResponseBinding(binding, currentBinding)) {
      return false;
    }
    ArrayDeque<PendingResponseHandler> handlers = pendingResponseHandlers();
    synchronized (handlers) {
      for (PendingResponseHandler pending : handlers) {
        if (pending.requiresMatchingResponseCommandId
            && !pending.isStaleResponseBinding(binding, currentBinding)) {
          return true;
        }
      }
    }
    EngineGameResponseHandler active = activeEngineGameResponseHandler.get();
    return active != null && active.isActiveFor(binding);
  }

  /**
   * Consumes only a numbered terminal response belonging to its exact KataGo parameter command.
   * Returning {@code true} keeps that command-specific payload out of the legacy generic parser.
   */
  private boolean captureRecentParameterResponse(String line, ReaderStreamBinding binding) {
    if (line == null || parseResponseCommandId(line) == NO_RESPONSE_COMMAND_ID) {
      return false;
    }
    PendingResponseHandler pending = peekPendingResponseHandler(line);
    if (pending == null
        || pending.isStaleResponseBinding(binding, currentReaderStreamBinding())
        || !isRecentParameterReadCommand(pending.command)) {
      return false;
    }
    if (!line.startsWith("=") || !getRcentLine) {
      // Matching errors and late successes still belong exclusively to this pending command.
      return line.startsWith("?") || line.startsWith("=");
    }
    String payload = gtpResponsePayload(line);
    if (pending.command.equals("kata-get-rules")) {
      if (!payload.startsWith("{")) {
        return true;
      }
      String normalizedRulesLine = "= " + payload;
      recentRulesLine = normalizedRulesLine;
      Lizzie.config.currentKataGoRules = normalizedRulesLine;
      getSuicidalAndRules();
      getRcentLine = false;
      return true;
    }
    try {
      double value = Double.parseDouble(payload);
      if (pending.command.equals("kata-get-param playoutDoublingAdvantage")) {
        pda = value;
        recentLineNumber = Math.max(recentLineNumber, 1);
      } else if (pending.command.equals("kata-get-param analysisWideRootNoise")) {
        wrn = value;
        recentLineNumber = Math.max(recentLineNumber, 2);
        Lizzie.frame.setPdaAndWrn(pda, wrn);
      }
    } catch (NumberFormatException ignored) {
      // A malformed payload belongs to the matched command but must not corrupt the cached value.
    }
    return true;
  }

  /** Freezes the exact engine-game startup owner before the parser dispatches post-name work. */
  private EngineManager.EngineGameTransaction engineGameStartupTransactionForLine(
      String line, Object sourceEngineIncarnation) {
    if (!(sourceEngineIncarnation instanceof ReaderStreamBinding)) {
      return null;
    }
    ReaderStreamBinding binding = (ReaderStreamBinding) sourceEngineIncarnation;
    PendingResponseHandler pending = peekPendingResponseHandler(line);
    if (pending == null
        || pending.isStaleResponseBinding(binding, currentReaderStreamBinding())) {
      return null;
    }
    return pending.queuedCommand.engineGameStartupTransaction();
  }

  private EngineGameResponseHandler engineGameResponseHandlerForLine(
      String line, ReaderStreamBinding binding) {
    EngineGameResponseHandler handler = null;
    if (parseResponseCommandId(line) != NO_RESPONSE_COMMAND_ID) {
      PendingResponseHandler pending = peekPendingResponseHandler(line);
      if (pending != null
          && !pending.isStaleResponseBinding(binding, currentReaderStreamBinding())
          && pending.handler instanceof EngineGameResponseHandler) {
        handler = (EngineGameResponseHandler) pending.handler;
      }
    } else {
      EngineGameResponseHandler active = activeEngineGameResponseHandler.get();
      if (active != null
          && active.isActiveFor(binding)
          && !isUnnumberedEngineGameTerminalCarrier(line)) {
        handler = active;
      }
    }
    return handler != null && handler.binding == binding ? handler : null;
  }

  private boolean isUnnumberedEngineGameTerminalCarrier(String line) {
    if (line == null) {
      return false;
    }
    String trimmed = line.trim();
    return trimmed.startsWith("=")
        || trimmed.startsWith("?")
        || trimmed.equals("play")
        || trimmed.startsWith("play ");
  }

  private PendingResponseHandler findPendingResponseHandler(String line, boolean remove) {
    int responseCommandId = parseResponseCommandId(line);
    ArrayDeque<PendingResponseHandler> handlers = pendingResponseHandlers();
    synchronized (handlers) {
      if (handlers.isEmpty()) {
        return null;
      }
      if (responseCommandId == NO_RESPONSE_COMMAND_ID) {
        PendingResponseHandler first = handlers.peekFirst();
        if (first.requiresMatchingResponseCommandId) {
          return null;
        }
        if (remove) {
          handlers.removeFirst();
        }
        return first;
      }
      Iterator<PendingResponseHandler> iterator = handlers.iterator();
      while (iterator.hasNext()) {
        PendingResponseHandler handler = iterator.next();
        if (handler.responseCommandId == responseCommandId) {
          if (remove) {
            iterator.remove();
          }
          return handler;
        }
      }
      return null;
    }
  }


  // Response-binding tests invoke this directly to isolate handler routing from queue counters.
  private boolean runPendingResponseHandlerForLine(String line) {
    currentCommandResponseLine = line == null ? "" : line;
    currentCommandResponseError = line != null && line.startsWith("?");
    try {
      if (loadSgfResponseQuarantined && parseResponseCommandId(line) == NO_RESPONSE_COMMAND_ID) {
        loadSgfResponseQuarantined = false;
        return false;
      }
      PendingResponseHandler handler = pollPendingResponseHandler(line);
      if (handler == null) {
        return false;
      }
      handler.run();
      return true;
    } finally {
      currentCommandResponseLine = "";
      currentCommandResponseError = false;
    }
  }

  private boolean hasStrictPendingResponseHandlerAtFront() {
    ArrayDeque<PendingResponseHandler> handlers = pendingResponseHandlers();
    synchronized (handlers) {
      return !handlers.isEmpty() && handlers.peekFirst().requiresMatchingResponseCommandId;
    }
  }

  private void processCommandResponseLine(String line) {
    processCommandResponseLine(line, currentReaderStreamBinding());
  }

  private void processCommandResponseLine(String line, ReaderStreamBinding responseBinding) {
    PendingResponseHandler matchedPendingHandler;
    boolean ignoreResponse;
    boolean settleOnlyStaleBootstrapResponse = false;
    boolean foregroundRestoreResponseError = false;
    QueuedCommand failedAnalysisStateCommand = null;
    currentCommandResponseLine = line == null ? "" : line;
    currentCommandResponseError = line != null && line.startsWith("?");
    try {
      synchronized (engineArbitrationLock()) {
        synchronized (commandQueue()) {
          boolean quarantinedUnnumberedResponse =
              loadSgfResponseQuarantined && parseResponseCommandId(line) == NO_RESPONSE_COMMAND_ID;
          if (quarantinedUnnumberedResponse) {
            loadSgfResponseQuarantined = false;
          }
          PendingResponseHandler pendingResponseCandidate =
              quarantinedUnnumberedResponse ? null : peekPendingResponseHandler(line);
          boolean staleReadBoardGmaResponse =
              pendingResponseCandidate != null
                  && pendingResponseCandidate.isStaleResponseBinding(
                      responseBinding, currentReaderStreamBinding());
          matchedPendingHandler =
              quarantinedUnnumberedResponse || staleReadBoardGmaResponse
                  ? null
                  : pollPendingResponseHandler(line);
          RestartBootstrapReceipt receipt =
              matchedPendingHandler == null
                  ? null
                  : matchedPendingHandler.queuedCommand.restartBootstrapReceipt;
          boolean staleBootstrapResponse =
              receipt != null
                  && (responseBinding != receipt.binding
                      || !isCurrentRestartBootstrapReceiptLocked(receipt));
          EngineManager.EngineGameTransaction startupTransaction =
              matchedPendingHandler == null
                  ? null
                  : matchedPendingHandler.queuedCommand.engineGameStartupTransaction();
          boolean retiredEngineGameStartupResponse =
              startupTransaction != null
                  && !EngineManager.isEngineGameOutputAdmissionOpen(startupTransaction);
          settleOnlyStaleBootstrapResponse =
              (staleBootstrapResponse || retiredEngineGameStartupResponse)
                  && matchedPendingHandler != null;
          ignoreResponse =
              staleReadBoardGmaResponse
                  || staleBootstrapResponse
                  || retiredEngineGameStartupResponse
                  || quarantinedUnnumberedResponse
                  || (matchedPendingHandler == null
                      && (parseResponseCommandId(line) != NO_RESPONSE_COMMAND_ID
                          || hasStrictPendingResponseHandlerAtFront()));
          foregroundRestoreResponseError =
              !ignoreResponse
                  && matchedPendingHandler != null
                  && line != null
                  && line.trim().startsWith("?")
                  && matchedPendingHandler.queuedCommand.foregroundRestoreCommand;
          if (!ignoreResponse
              && matchedPendingHandler != null
              && !matchedPendingHandler.isOutstandingResponseRetired()
              && currentCommandResponseError
              && analysisStateMutation(matchedPendingHandler.queuedCommand.command)
                  != AnalysisStateMutation.NONE) {
            failedAnalysisStateCommand = matchedPendingHandler.queuedCommand;
          }
          if ((!ignoreResponse || settleOnlyStaleBootstrapResponse)
              && (matchedPendingHandler == null
                  || !matchedPendingHandler.isOutstandingResponseRetired())) {
            currentCmdNum++;
            if (currentCmdNum > cmdNumber - 1) {
              currentCmdNum = cmdNumber - 1;
            }
          }
        }
      }
      if (failedAnalysisStateCommand != null) {
        failAnalysisStateLineage(failedAnalysisStateCommand);
      }
      if (settleOnlyStaleBootstrapResponse) {
        matchedPendingHandler.settleWithoutBusinessCallback();
      } else if (!ignoreResponse) {
        boolean handlerSettled = false;
        try {
          if (foregroundRestoreResponseError) {
            failForegroundRestore(
                foregroundRestoreSession, "restore command failed: " + line.trim());
          }
          if (matchedPendingHandler != null) {
            String outcome = currentCommandResponseError ? "error" : "ok";
            long latencyMs =
                TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - matchedPendingHandler.sentAtNanos);
            EngineObservation.recordCommandOutcome(
                matchedPendingHandler.loggingEngineId,
                matchedPendingHandler.loggingCommandId,
                matchedPendingHandler.commandName,
                outcome,
                latencyMs);
            EngineObservation.traceRawResponse(
                matchedPendingHandler.loggingEngineId,
                matchedPendingHandler.loggingCommandId,
                line);
            matchedPendingHandler.run();
            handlerSettled = true;
          }
        } finally {
          if (matchedPendingHandler != null && !handlerSettled) {
            // A diagnostic or business callback must never strand a physical engine-game lease
            // after its exact pending handler has already been removed from the queue.
            matchedPendingHandler.settleWithoutBusinessCallback();
          }
        }
      }
      acknowledgeExclusiveGtpInitialStop(line);
    } finally {
      currentCommandResponseLine = "";
      currentCommandResponseError = false;
    }
    if (ignoreResponse && !settleOnlyStaleBootstrapResponse) {
      return;
    }
    try {
      trySendCommandFromQueue();
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  public ExclusiveGtpLeaseAvailability beginExclusiveGtpSession(
      Consumer<String> lineConsumer, Runnable onReady, Runnable onClosed) {
    return beginExclusiveGtpSession(new Object(), lineConsumer, onReady, onClosed);
  }

  private ExclusiveGtpLeaseAvailability beginExclusiveGtpSession(
      Object owner, Consumer<String> lineConsumer, Runnable onReady, Runnable onClosed) {
    ExclusiveGtpSession session;
    synchronized (engineArbitrationLock()) {
      ExclusiveGtpLeaseAvailability availability = intrinsicExclusiveGtpLeaseAvailability();
      if (availability != ExclusiveGtpLeaseAvailability.AVAILABLE || lineConsumer == null) {
        return availability == ExclusiveGtpLeaseAvailability.AVAILABLE
            ? ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY
            : availability;
      }
      session = reserveExclusiveGtpSession(owner, lineConsumer, onReady, onClosed);
    }
    return startReservedExclusiveGtpSession(session);
  }

  private ExclusiveGtpSession reserveExclusiveGtpSession(
      Object owner, Consumer<String> lineConsumer, Runnable onReady, Runnable onClosed) {
    return reserveExclusiveGtpSession(
        owner,
        lineConsumer,
        onReady,
        onClosed,
        ExclusiveGtpReleasePolicy.FOREGROUND_RESTORE,
        null);
  }

  private ExclusiveGtpSession reserveExclusiveGtpSession(
      Object owner,
      Consumer<String> lineConsumer,
      Runnable onReady,
      Runnable onClosed,
      ExclusiveGtpReleasePolicy releasePolicy,
      ReaderStreamBinding readerBinding) {
    ExclusiveGtpSession session =
        new ExclusiveGtpSession(
            owner,
            lineConsumer,
            onReady,
            onClosed,
            exclusiveGtpResponseCommandIds.getAndIncrement(),
            releasePolicy,
            readerBinding);
    session.wasPondering = isPondering();
    exclusiveGtpSession = session;
    return session;
  }

  private ExclusiveGtpLeaseAvailability startReservedExclusiveGtpSession(
      ExclusiveGtpSession session) {
    notPondering();
    if (session.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY) {
      synchronized (engineArbitrationLock()) {
        if (exclusiveGtpSession != session
            || session.trackingInitialWriteState != TrackingWriteState.UNSENT) {
          return ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY;
        }
        session.trackingInitialWriteState = TrackingWriteState.WRITING;
      }
    }
    scheduleExclusiveGtpInitialStopTimeout(session);
    if (session.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY) {
      ExclusiveGtpWriteResult writeResult =
          writeExclusiveGtpCommandResult(
              session,
              ExclusiveGtpWritePhase.INITIAL_STOP,
              session.stopCommandId,
              session.stopCommandId + " stop");
      return publishTrackingInitialWriteResult(session, writeResult);
    }
    if (!writeExclusiveGtpCommand(
        session,
        ExclusiveGtpWritePhase.INITIAL_STOP,
        session.stopCommandId,
        session.stopCommandId + " stop")) {
      synchronized (engineArbitrationLock()) {
        recordForegroundAnalysisLeaseFailure(
            session, ForegroundAnalysisLeaseFailure.INITIAL_STOP_SEND_FAILED);
        session.restoreFailed = true;
        session.closing = true;
      }
      restoreAfterClosedForegroundLease(session);
      return ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY;
    }
    return ExclusiveGtpLeaseAvailability.AVAILABLE;
  }

  private ExclusiveGtpLeaseAvailability publishTrackingInitialWriteResult(
      ExclusiveGtpSession session, ExclusiveGtpWriteResult writeResult) {
    boolean closeStaleSession = false;
    boolean failCurrentSession = false;
    boolean completeEarlyBoundary = false;
    String earlyErrorResponse = null;
    synchronized (engineArbitrationLock()) {
      if (exclusiveGtpSession == session
          && session.trackingInitialWriteState == TrackingWriteState.WRITING) {
        if (readerStreamBinding != session.readerBinding || session.readerBinding.terminated) {
          closeStaleSession = true;
        } else if (writeResult == ExclusiveGtpWriteResult.SENT) {
          session.trackingInitialWriteState = TrackingWriteState.SENT;
          earlyErrorResponse = session.initialStopErrorResponse;
          completeEarlyBoundary =
              session.initialStopAcknowledged && session.initialStopTerminated;
        } else {
          session.trackingInitialWriteState = TrackingWriteState.FAILED;
          failCurrentSession = true;
        }
      }
    }
    if (closeStaleSession) {
      closeStaleTrackingStreamLease(session, false);
      return ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY;
    }
    if (failCurrentSession) {
      failTrackingStreamLease(
          session,
          TrackingStreamLeaseFailure.INITIAL_STOP_SEND_FAILED,
          "failed to send initial stop command",
          true);
      return ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY;
    }
    if (earlyErrorResponse != null) {
      failTrackingStreamLease(
          session,
          TrackingStreamLeaseFailure.INITIAL_STOP_ERROR_RESPONSE,
          "initial stop command failed: " + earlyErrorResponse,
          true);
      return ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY;
    }
    if (completeEarlyBoundary) {
      completeExclusiveGtpInitialStopBoundary(session);
    }
    return writeResult == ExclusiveGtpWriteResult.SENT
            && session.trackingInitialWriteState == TrackingWriteState.SENT
        ? ExclusiveGtpLeaseAvailability.AVAILABLE
        : ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY;
  }

  public ExclusiveGtpLeaseAvailability beginForegroundAnalysisLease(
      Object owner, Consumer<String> lineConsumer, Runnable onReady, Runnable onClosed) {
    ExclusiveGtpSession session;
    synchronized (engineArbitrationLock()) {
      synchronized (commandQueue()) {
        ExclusiveGtpLeaseAvailability availability = previewForegroundAnalysisLeaseAvailability();
        if (availability != ExclusiveGtpLeaseAvailability.AVAILABLE) {
          return availability;
        }
        if (exclusiveGtpSession != null) {
          return ExclusiveGtpLeaseAvailability.EXISTING_LEASE;
        }
        if (normalCommandSendInProgress || !commandQueue().isEmpty() || lineConsumer == null) {
          return ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY;
        }
        suppressNormalCommandsForForegroundAnalysis = true;
        session = reserveExclusiveGtpSession(owner, lineConsumer, onReady, onClosed);
      }
    }
    ExclusiveGtpLeaseAvailability result = startReservedExclusiveGtpSession(session);
    if (result != ExclusiveGtpLeaseAvailability.AVAILABLE) {
      synchronized (engineArbitrationLock()) {
        if (!foregroundRestoreInProgress) {
          suppressNormalCommandsForForegroundAnalysis = false;
        }
      }
    }
    return result;
  }

  public ForegroundAnalysisLeaseAcquisition acquireForegroundAnalysisLease(
      Consumer<String> lineConsumer,
      Consumer<ForegroundAnalysisLease> onReady,
      Consumer<ForegroundAnalysisLease> onClosed) {
    ForegroundAnalysisLease lease = new ForegroundAnalysisLease(this);
    ExclusiveGtpLeaseAvailability availability =
        beginForegroundAnalysisLease(
            lease,
            lineConsumer,
            () -> {
              if (onReady != null) {
                onReady.accept(lease);
              }
            },
            () -> {
              if (onClosed != null) {
                onClosed.accept(lease);
              }
            });
    return new ForegroundAnalysisLeaseAcquisition(
        availability,
        availability == ExclusiveGtpLeaseAvailability.AVAILABLE ? lease : null,
        lease);
  }

  public TrackingStreamLeaseAcquisition acquireTrackingStreamLease(
      Consumer<String> lineConsumer,
      Consumer<TrackingStreamLease> onReady,
      Consumer<TrackingStreamLease> onClosed) {
    return acquireTrackingStreamLease(lineConsumer, onReady, onClosed, null);
  }

  public TrackingStreamLeaseAcquisition acquireTrackingStreamLease(
      Consumer<String> lineConsumer,
      Consumer<TrackingStreamLease> onReady,
      Consumer<TrackingStreamLease> onClosed,
      TrackingReleaseDispositionObserver dispositionObserver) {
    ExclusiveGtpSession session;
    TrackingStreamLease lease;
    synchronized (engineArbitrationLock()) {
      synchronized (commandQueue()) {
        ExclusiveGtpLeaseAvailability availability = trackingStreamLeaseAvailability();
        if (availability != ExclusiveGtpLeaseAvailability.AVAILABLE
            || normalCommandSendInProgress
            || !commandQueue().isEmpty()
            || !foregroundRestoreCommandQueue().isEmpty()
            || lineConsumer == null) {
          return new TrackingStreamLeaseAcquisition(
              availability == ExclusiveGtpLeaseAvailability.AVAILABLE
                  ? ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY
                  : availability,
              null,
              null,
              null);
        }
        ReaderStreamBinding binding = currentReaderStreamBinding();
        TrackingStreamLeaseReceipt receipt =
            new TrackingStreamLeaseReceipt(this, binding.incarnation, isPondering());
        TrackingStreamLease reservedLease =
            new TrackingStreamLease(this, receipt, dispositionObserver);
        lease = reservedLease;
        session =
            reserveExclusiveGtpSession(
                reservedLease,
                lineConsumer,
                () -> {
                  if (onReady != null) {
                    onReady.accept(reservedLease);
                  }
                },
                () -> {
                  if (onClosed != null) {
                    onClosed.accept(reservedLease);
                  }
                },
                ExclusiveGtpReleasePolicy.STREAM_ONLY,
                binding);
      }
    }
    ExclusiveGtpLeaseAvailability availability = startReservedExclusiveGtpSession(session);
    return new TrackingStreamLeaseAcquisition(
        availability,
        availability == ExclusiveGtpLeaseAvailability.AVAILABLE ? lease : null,
        availability == ExclusiveGtpLeaseAvailability.AVAILABLE ? lease.receipt() : null,
        lease);
  }

  private ExclusiveGtpLeaseAvailability trackingStreamLeaseAvailability() {
    if (Lizzie.leelaz == null) {
      return ExclusiveGtpLeaseAvailability.NO_FOREGROUND_ENGINE;
    }
    if (Lizzie.leelaz != this) {
      return ExclusiveGtpLeaseAvailability.NOT_CURRENT_FOREGROUND_ENGINE;
    }
    if (isWebTrialEngineBusy()) {
      return ExclusiveGtpLeaseAvailability.APPLICATION_EXCLUSIVE_MODE;
    }
    ExclusiveGtpLeaseAvailability staticAvailability = trackingStaticAvailability();
    if (staticAvailability != ExclusiveGtpLeaseAvailability.AVAILABLE) {
      return staticAvailability;
    }
    if (engineStateUnrestored) {
      return ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED;
    }
    if (readBoardGmaReservation != null) {
      return ExclusiveGtpLeaseAvailability.READBOARD_GMA;
    }
    if (exclusiveGtpLifecycleTransition || hasLifecycleCompletionLocked()) {
      return ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE;
    }
    if (trackingHandoffGate != null) {
      return ExclusiveGtpLeaseAvailability.EXISTING_LEASE;
    }
    if (!isLoaded() || !isStarted()) {
      return ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY;
    }
    if (exclusiveGtpSession != null) {
      return ExclusiveGtpLeaseAvailability.EXISTING_LEASE;
    }
    return foregroundEngineUseAvailability();
  }

  private boolean isWebTrialEngineBusy() {
    return Lizzie.webBoardManager != null
        && Lizzie.webBoardManager.isEngineOperationExcludedByTrial();
  }

  private ExclusiveGtpLeaseAvailability trackingStaticAvailability() {
    if (useRemoteCompute || useJavaSSH || isSSH) {
      return ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY;
    }
    if (Lizzie.config != null && Lizzie.config.isDoubleEngineMode()) {
      return ExclusiveGtpLeaseAvailability.APPLICATION_EXCLUSIVE_MODE;
    }
    if (!isKatago) {
      return ExclusiveGtpLeaseAvailability.NOT_KATAGO;
    }
    if (outputStream == null || !endGetCommandList) {
      return ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY;
    }
    if (!commandLists.contains("stop") || !commandLists.contains("kata-analyze")) {
      return ExclusiveGtpLeaseAvailability.MISSING_CAPABILITY;
    }
    return ExclusiveGtpLeaseAvailability.AVAILABLE;
  }

  private void recordForegroundAnalysisLeaseFailure(
      ExclusiveGtpSession session, ForegroundAnalysisLeaseFailure failure) {
    if (session != null && session.owner instanceof ForegroundAnalysisLease) {
      ((ForegroundAnalysisLease) session.owner).recordFailure(failure);
    }
  }

  private void recordTrackingStreamLeaseFailure(
      ExclusiveGtpSession session, TrackingStreamLeaseFailure failure) {
    if (session != null && session.owner instanceof TrackingStreamLease) {
      ((TrackingStreamLease) session.owner).recordFailure(failure);
    }
  }

  public ExclusiveGtpLeaseAvailability previewForegroundAnalysisLeaseAvailability() {
    synchronized (engineArbitrationLock()) {
      if (Lizzie.leelaz == null) {
        return ExclusiveGtpLeaseAvailability.NO_FOREGROUND_ENGINE;
      }
      if (Lizzie.leelaz != this) {
        return ExclusiveGtpLeaseAvailability.NOT_CURRENT_FOREGROUND_ENGINE;
      }
      if (isWebTrialEngineBusy()) {
        return ExclusiveGtpLeaseAvailability.APPLICATION_EXCLUSIVE_MODE;
      }
      synchronized (commandQueue()) {
        if (canClaimTrackingHandoffLocked()) {
          return ExclusiveGtpLeaseAvailability.AVAILABLE;
        }
      }
      ExclusiveGtpLeaseAvailability intrinsic = intrinsicExclusiveGtpLeaseAvailability();
      if (intrinsic != ExclusiveGtpLeaseAvailability.AVAILABLE) {
        return intrinsic;
      }
      return foregroundEngineUseAvailability();
    }
  }

  private boolean canClaimTrackingHandoffLocked() {
    ExclusiveGtpSession session = exclusiveGtpSession;
    return trackingHandoffGate == null
        && session != null
        && session.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY
        && session.owner instanceof TrackingStreamLease
        && !session.closing
        && !session.releaseRequested
        && !exclusiveGtpLifecycleTransition
        && !hasLifecycleCompletionLocked()
        && !normalCommandSendInProgress
        && commandQueue().isEmpty()
        && foregroundRestoreCommandQueue().isEmpty();
  }

  private ExclusiveGtpLeaseAvailability foregroundEngineUseAvailability() {
    if (Lizzie.frame != null
        && Lizzie.frame.readBoard != null
        && Lizzie.frame.readBoard.isReadBoardGmaEngineBusy()) {
      return ExclusiveGtpLeaseAvailability.READBOARD_GMA;
    }
    if (EngineManager.isEngineGame()) {
      return ExclusiveGtpLeaseAvailability.ENGINE_GAME;
    }
    if (isThinking || isInputCommand) {
      return ExclusiveGtpLeaseAvailability.GENMOVE;
    }
    if (isCheckingName || isCheckingVersion || isTuning) {
      return ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE;
    }
    if (KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed()) {
      return ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE;
    }
    if (Lizzie.frame != null) {
      if (Lizzie.frame.isPlayingAgainstLeelaz || Lizzie.frame.isAnaPlayingAgainstLeelaz) {
        return ExclusiveGtpLeaseAvailability.PLAY_MODE;
      }
      if (Lizzie.frame.humanSlGame != null && !Lizzie.frame.humanSlGame.isFinished()) {
        return ExclusiveGtpLeaseAvailability.HUMAN_SL_GAME;
      }
      if (Lizzie.frame.isContributing) {
        return ExclusiveGtpLeaseAvailability.APPLICATION_EXCLUSIVE_MODE;
      }
    }
    return ExclusiveGtpLeaseAvailability.AVAILABLE;
  }

  private ExclusiveGtpLeaseAvailability intrinsicExclusiveGtpLeaseAvailability() {
    if (engineStateUnrestored) {
      return ExclusiveGtpLeaseAvailability.ENGINE_STATE_UNRESTORED;
    }
    if (readBoardGmaReservation != null) {
      return ExclusiveGtpLeaseAvailability.READBOARD_GMA;
    }
    if (exclusiveGtpLifecycleTransition || hasLifecycleCompletionLocked()) {
      return ExclusiveGtpLeaseAvailability.ENGINE_LIFECYCLE;
    }
    if (trackingHandoffGate != null) {
      return ExclusiveGtpLeaseAvailability.EXISTING_LEASE;
    }
    if (!isLoaded() || !isStarted() || outputStream == null) {
      return ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY;
    }
    if (!isKatago) {
      return ExclusiveGtpLeaseAvailability.NOT_KATAGO;
    }
    if (!endGetCommandList) {
      return ExclusiveGtpLeaseAvailability.ENGINE_NOT_READY;
    }
    if (!commandLists.containsAll(FLASH_ANALYSIS_GTP_COMMANDS)) {
      return ExclusiveGtpLeaseAvailability.MISSING_CAPABILITY;
    }
    if (Board.boardWidth != Board.boardHeight && !commandLists.contains("rectangular_boardsize")) {
      return ExclusiveGtpLeaseAvailability.MISSING_CAPABILITY;
    }
    if (exclusiveGtpSession != null) {
      return ExclusiveGtpLeaseAvailability.EXISTING_LEASE;
    }
    return ExclusiveGtpLeaseAvailability.AVAILABLE;
  }

  public ExclusiveGtpLeaseAvailability previewExclusiveGtpLeaseAvailability() {
    synchronized (engineArbitrationLock()) {
      return intrinsicExclusiveGtpLeaseAvailability();
    }
  }

  public boolean sendExclusiveGtpCommand(String command) {
    ExclusiveGtpSession session;
    synchronized (engineArbitrationLock()) {
      session = exclusiveGtpSession;
      if (session == null || !session.active || command == null || command.trim().isEmpty()) {
        return false;
      }
    }
    return writeExclusiveGtpCommand(
        session, ExclusiveGtpWritePhase.ACTIVE_COMMAND, 0, command);
  }

  private boolean sendTrackingStreamCommand(TrackingStreamLease owner, String command) {
    ExclusiveGtpSession session;
    int commandId;
    synchronized (engineArbitrationLock()) {
      session = exclusiveGtpSession;
      if (session == null
          || session.owner != owner
          || session.releasePolicy != ExclusiveGtpReleasePolicy.STREAM_ONLY
          || !session.active
          || session.releaseRequested
          || session.trackingActiveWriteState != TrackingWriteState.UNSENT
          || command == null
          || command.trim().isEmpty()) {
        return false;
      }
      session.trackingActiveWriteState = TrackingWriteState.WRITING;
      commandId = exclusiveGtpResponseCommandIds.getAndIncrement();
    }
    ExclusiveGtpWriteResult writeResult =
        writeExclusiveGtpCommandResult(
            session, ExclusiveGtpWritePhase.ACTIVE_COMMAND, 0, commandId + " " + command.trim());
    int releaseStopCommandId = 0;
    boolean failCurrentSession = false;
    boolean closeStaleSession = false;
    boolean sentForCurrentSession = false;
    synchronized (engineArbitrationLock()) {
      if (exclusiveGtpSession == session
          && session.trackingActiveWriteState == TrackingWriteState.WRITING) {
        if (readerStreamBinding != session.readerBinding || session.readerBinding.terminated) {
          closeStaleSession = true;
        } else if (writeResult == ExclusiveGtpWriteResult.SENT) {
          session.trackingActiveWriteState = TrackingWriteState.SENT;
          sentForCurrentSession = true;
          if (session.releaseRequested) {
            releaseStopCommandId = claimTrackingReleaseStopLocked(session);
          }
        } else {
          session.trackingActiveWriteState = TrackingWriteState.FAILED;
          failCurrentSession = true;
        }
      }
    }
    if (closeStaleSession) {
      closeStaleTrackingStreamLease(session, true);
    } else if (failCurrentSession) {
      failTrackingStreamLease(
          session,
          TrackingStreamLeaseFailure.ACTIVE_COMMAND_SEND_FAILED,
          "failed to send active tracking command",
          true);
    } else if (writeResult != ExclusiveGtpWriteResult.SENT) {
      if (!isCurrentTrackingStreamIncarnation(session)) {
        closeStaleTrackingStreamLease(session, true);
      }
    } else if (releaseStopCommandId != 0) {
      sendTrackingReleaseStop(session, releaseStopCommandId);
    }
    return sentForCurrentSession;
  }

  private int claimTrackingReleaseStopLocked(ExclusiveGtpSession session) {
    if (!session.active
        || session.releaseStopCommandId != 0
        || session.trackingActiveWriteState == TrackingWriteState.WRITING
        || session.trackingActiveWriteState == TrackingWriteState.FAILED) {
      return 0;
    }
    int commandId = exclusiveGtpResponseCommandIds.getAndIncrement();
    session.releaseStopCommandId = commandId;
    session.trackingFinalWriteState = TrackingWriteState.WRITING;
    return commandId;
  }

  private void sendTrackingReleaseStop(
      ExclusiveGtpSession session, int releaseStopCommandId) {
    scheduleExclusiveGtpReleaseStopTimeout(session);
    ExclusiveGtpWriteResult writeResult =
        writeExclusiveGtpCommandResult(
            session,
            ExclusiveGtpWritePhase.RELEASE_STOP,
            releaseStopCommandId,
            releaseStopCommandId + " stop");
    boolean closeStaleSession = false;
    boolean failCurrentSession = false;
    boolean completeEarlyBoundary = false;
    String earlyErrorResponse = null;
    synchronized (engineArbitrationLock()) {
      if (exclusiveGtpSession == session
          && session.trackingFinalWriteState == TrackingWriteState.WRITING) {
        if (readerStreamBinding != session.readerBinding || session.readerBinding.terminated) {
          closeStaleSession = true;
        } else if (writeResult == ExclusiveGtpWriteResult.SENT) {
          session.trackingFinalWriteState = TrackingWriteState.SENT;
          earlyErrorResponse = session.releaseStopErrorResponse;
          completeEarlyBoundary =
              session.releaseStopAcknowledged && session.releaseStopTerminated;
        } else {
          session.trackingFinalWriteState = TrackingWriteState.FAILED;
          failCurrentSession = true;
        }
      }
    }
    if (closeStaleSession) {
      closeStaleTrackingStreamLease(session, true);
    } else if (failCurrentSession) {
      failTrackingStreamLease(
          session,
          TrackingStreamLeaseFailure.FINAL_STOP_SEND_FAILED,
          "failed to send final stop command",
          true);
    } else if (earlyErrorResponse != null) {
      failTrackingStreamLease(
          session,
          TrackingStreamLeaseFailure.FINAL_STOP_ERROR_RESPONSE,
          "final stop command failed: " + earlyErrorResponse,
          true);
    } else if (completeEarlyBoundary) {
      completeTrackingReleaseBoundary(session);
    }
  }

  public void endExclusiveGtpSession() {
    ExclusiveGtpSession session;
    synchronized (engineArbitrationLock()) {
      session = exclusiveGtpSession;
    }
    closeExclusiveGtpSession(session);
  }

  private boolean closeExclusiveGtpSession(ExclusiveGtpSession expected) {
    return closeExclusiveGtpSession(expected, true);
  }

  private boolean closeExclusiveGtpSession(
      ExclusiveGtpSession expected, boolean advanceOrdinaryQueue) {
    synchronized (engineArbitrationLock()) {
      if (expected == null || exclusiveGtpSession != expected) {
        return false;
      }
      exclusiveGtpSession = null;
      engineArbitrationLock().notifyAll();
    }
    if (advanceOrdinaryQueue) {
      try {
        trySendCommandFromQueue();
      } catch (RuntimeException ex) {
        ex.printStackTrace();
      }
    }
    return true;
  }

  public boolean hasExclusiveGtpLease() {
    synchronized (engineArbitrationLock()) {
      return exclusiveGtpSession != null;
    }
  }

  public boolean hasExclusiveGtpWorkInProgress() {
    synchronized (engineArbitrationLock()) {
      return exclusiveGtpSession != null
          || trackingHandoffGate != null
          || foregroundRestoreInProgress
          || exclusiveGtpLifecycleTransition
          || hasLifecycleCompletionLocked();
    }
  }

  boolean hasExclusiveGtpLifecycleTransitionForTest() {
    synchronized (engineArbitrationLock()) {
      return exclusiveGtpLifecycleTransition;
    }
  }

  /** Returns whether foreground quick analysis owns, or is restoring from, the exclusive lease. */
  public boolean hasForegroundAnalysisLeaseWorkInProgress() {
    synchronized (engineArbitrationLock()) {
      if (exclusiveGtpSession != null
          && exclusiveGtpSession.owner instanceof ForegroundAnalysisLease) {
        return true;
      }
      return foregroundRestoreSession != null
          && foregroundRestoreSession.owner instanceof ForegroundAnalysisLease;
    }
  }

  public boolean hasExclusiveGtpLeaseOwnedBy(Object owner) {
    synchronized (engineArbitrationLock()) {
      return exclusiveGtpSession != null && exclusiveGtpSession.owner == owner;
    }
  }

  boolean setForegroundAnalysisLeaseRestoreRules(Object owner, String rules) {
    synchronized (engineArbitrationLock()) {
      ExclusiveGtpSession session = exclusiveGtpSession;
      if (session == null
          || session.owner != owner
          || !session.active
          || session.closing
          || rules == null
          || rules.trim().isEmpty()) {
        return false;
      }
      session.originalRules = rules.trim();
      return true;
    }
  }

  public boolean beginExclusiveGtpLifecycleTransition() {
    synchronized (engineArbitrationLock()) {
      if (isWebTrialEngineBusy() || engineStateUnrestored || readBoardGmaReservation != null) {
        return false;
      }
      return beginExclusiveGtpLifecycleTransition(Thread.currentThread());
    }
  }

  boolean canArmReadBoardGma() {
    synchronized (engineArbitrationLock()) {
      return !isWebTrialEngineBusy()
          && !engineStateUnrestored
          && (Lizzie.config == null || !Lizzie.config.isDoubleEngineMode())
          && readBoardGmaReservation == null
          && !hasLifecycleCompletionLocked()
          && trackingHandoffGate == null
          && !foregroundRestoreInProgress
          && !exclusiveGtpLifecycleTransition
          && (exclusiveGtpSession == null
              || exclusiveGtpSession.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY);
    }
  }

  public ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation() {
    return beginExclusiveGtpLifecycleReservationInternal(new Object());
  }

  ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservation(Object owner) {
    if (owner == null) {
      throw new IllegalArgumentException("owner");
    }
    return beginExclusiveGtpLifecycleReservationInternal(owner);
  }

  private ExclusiveGtpLifecycleReservation beginExclusiveGtpLifecycleReservationInternal(
      Object owner) {
    ExclusiveGtpSession trackingSession = null;
    TrackingDispositionNotification dispositionNotification = null;
    int releaseStopCommandId = 0;
    boolean trackingFirstWinner = false;
    synchronized (engineArbitrationLock()) {
      synchronized (commandQueue()) {
        if (isWebTrialEngineBusy()) {
          return null;
        }
        if (exclusiveGtpSession == null) {
          if (!beginExclusiveGtpLifecycleTransition(owner)) {
            return null;
          }
        } else {
          trackingSession = exclusiveGtpSession;
          if (!(trackingSession.owner instanceof TrackingStreamLease)
              || trackingSession.releasePolicy != ExclusiveGtpReleasePolicy.STREAM_ONLY
              || trackingSession.closing
              || trackingSession.releaseRequested
              || trackingHandoffGate != null
              || exclusiveGtpLifecycleTransition) {
            return null;
          }
          exclusiveGtpLifecycleTransition = true;
          exclusiveGtpLifecycleQueueGate = true;
          exclusiveGtpLifecycleOwner = owner;
          exclusiveGtpLifecycleDepth = 1;
          trackingSession.releaseRequested = true;
          trackingFirstWinner = true;
          dispositionNotification =
              advanceTrackingReleaseDispositionLocked(
                  trackingSession, TrackingReleaseDisposition.CLEARED);
          if (trackingSession.active) {
            releaseStopCommandId = claimTrackingReleaseStopLocked(trackingSession);
          }
        }
      }
    }
    notifyTrackingDisposition(dispositionNotification);
    if (releaseStopCommandId != 0) {
      sendTrackingReleaseStop(trackingSession, releaseStopCommandId);
    }
    return new ExclusiveGtpLifecycleReservation(this, owner, trackingFirstWinner);
  }

  public EngineModeReservation beginEngineModeReservation() {
    synchronized (engineArbitrationLock()) {
      if (isWebTrialEngineBusy() || engineStateUnrestored || readBoardGmaReservation != null) {
        return null;
      }
      Object owner = Thread.currentThread();
      if (!beginExclusiveGtpLifecycleTransition(owner)) {
        return null;
      }
      return new EngineModeReservation(this, owner);
    }
  }

  private boolean beginExclusiveGtpLifecycleTransition(Object owner) {
    if (exclusiveGtpSession != null || trackingHandoffGate != null) {
      return false;
    }
    if (hasLifecycleCompletionOwnedByOtherLocked(owner)) {
      return false;
    }
    if (exclusiveGtpLifecycleTransition) {
      if (exclusiveGtpLifecycleOwner != owner) {
        return false;
      }
      exclusiveGtpLifecycleDepth++;
      return true;
    }
    exclusiveGtpLifecycleTransition = true;
    exclusiveGtpLifecycleOwner = owner;
    exclusiveGtpLifecycleDepth = 1;
    return true;
  }

  public void endExclusiveGtpLifecycleTransition() {
    synchronized (engineArbitrationLock()) {
      endExclusiveGtpLifecycleTransition(Thread.currentThread());
    }
  }

  private void endExclusiveGtpLifecycleTransition(Object owner) {
    boolean ended = false;
    synchronized (engineArbitrationLock()) {
      synchronized (commandQueue()) {
        if (!exclusiveGtpLifecycleTransition || exclusiveGtpLifecycleOwner != owner) {
          return;
        }
        exclusiveGtpLifecycleDepth--;
        if (exclusiveGtpLifecycleDepth <= 0) {
          exclusiveGtpLifecycleTransition = false;
          exclusiveGtpLifecycleQueueGate = false;
          exclusiveGtpLifecycleOwner = null;
          exclusiveGtpLifecycleDepth = 0;
          if (restartBootstrapReceipt != null) {
            restartBootstrapReceipt.binding.restartBootstrapReceipt = null;
          }
          restartBootstrapReceipt = null;
          ended = true;
        }
      }
    }
    if (ended) {
      trySendCommandFromQueue();
    }
  }

  private boolean rejectNewExclusiveWorkDuringGtpLease() {
    if (!engineStateUnrestored
        && readBoardGmaReservation == null
        && !hasConflictingExclusiveGtpWork()) {
      return false;
    }
    showExclusiveGtpConflictMessage();
    return true;
  }

  void showExclusiveGtpConflictMessage() {
    if (Lizzie.frame == null || !Lizzie.frame.isDisplayable() || Lizzie.resourceBundle == null) {
      return;
    }
    String key =
        engineStateUnrestored
            ? "AnalysisSettings.reuseStatus.engine_state_unrestored"
            : "AnalysisSettings.reuseStatus.existing_lease";
    SwingUtilities.invokeLater(() -> Utils.showMsg(Lizzie.resourceBundle.getString(key)));
  }

  private boolean hasConflictingExclusiveGtpWork() {
    synchronized (engineArbitrationLock()) {
      if (exclusiveGtpSession != null
          || trackingHandoffGate != null
          || foregroundRestoreInProgress
          || hasLifecycleCompletionLocked()) {
        return true;
      }
      return exclusiveGtpLifecycleTransition && exclusiveGtpLifecycleOwner != Thread.currentThread();
    }
  }

  /** Captures the board-sync owner for one immutable exact restore plan. */
  public ExactSnapshotRestoreAdmission captureBoardSyncExactSnapshotRestoreAdmission() {
    return captureExactSnapshotRestoreAdmission(
        ExactSnapshotRestoreOwner.BOARD_SYNC, null, resolveLoadSgfMirrorEngine(), false);
  }

  /** Captures a board-sync restore that must remain bound to the selected primary engine. */
  public ExactSnapshotRestoreAdmission captureHistoryNavigationExactSnapshotRestoreAdmission() {
    return captureExactSnapshotRestoreAdmission(
        ExactSnapshotRestoreOwner.BOARD_SYNC, null, resolveLoadSgfMirrorEngine(), true);
  }

  /** Captures the arbitration owner for one immutable exact restore plan. */
  ExactSnapshotRestoreAdmission captureExactSnapshotRestoreAdmission(
      ExactSnapshotRestoreOwner owner, Object ownerIdentity, Leelaz mirror) {
    return captureExactSnapshotRestoreAdmission(owner, ownerIdentity, mirror, false);
  }

  private ExactSnapshotRestoreAdmission captureExactSnapshotRestoreAdmission(
      ExactSnapshotRestoreOwner owner,
      Object ownerIdentity,
      Leelaz mirror,
      boolean bindToPrimaryEngine) {
    if (owner == null) {
      throw new IllegalArgumentException("owner");
    }
    Object capturedOwnerIdentity = ownerIdentity;
    long primaryEngineGeneration =
        owner == ExactSnapshotRestoreOwner.BOARD_SYNC && bindToPrimaryEngine
            ? Lizzie.capturePrimaryEngineGeneration(this)
            : -1L;
    if (owner == ExactSnapshotRestoreOwner.BOARD_SYNC
        && bindToPrimaryEngine
        && primaryEngineGeneration < 0L) {
      throw new ExactSnapshotRestoreAdmissionException(
          "Board-sync exact restore primary ownership changed during capture");
    }
    LifecycleCompletionClaim completionClaim = null;
    synchronized (engineArbitrationLock()) {
      if (owner == ExactSnapshotRestoreOwner.READ_BOARD_GMA && capturedOwnerIdentity == null) {
        capturedOwnerIdentity = readBoardGmaReservation;
      }
      if (!canCaptureExactSnapshotRestoreAdmission(owner, capturedOwnerIdentity)) {
        throw new ExactSnapshotRestoreAdmissionException(
            "Exact snapshot restore is not admitted for owner " + owner);
      }
      if (owner == ExactSnapshotRestoreOwner.BOARD_SYNC) {
        completionClaim = lifecycleCompletionClaim;
      }
    }
    Object authorityIncarnation = null;
    Object mirrorIncarnation = null;
    if (owner == ExactSnapshotRestoreOwner.READ_BOARD_GMA) {
      authorityIncarnation = currentEngineIncarnation();
      mirrorIncarnation = mirror == null ? null : mirror.currentEngineIncarnation();
    }
    if (mirror != null
        && !mirror.canAcceptExactSnapshotRestoreAdmission(this, owner, capturedOwnerIdentity)) {
      throw new ExactSnapshotRestoreAdmissionException(
          "Exact snapshot restore mirror is not admitted for owner " + owner);
    }
    LifecycleCompletionClaim.BoardSyncCompletionLease boardSyncLease =
        completionClaim == null ? null : completionClaim.acquireBoardSyncLease();
    if (completionClaim != null && boardSyncLease == null) {
      throw new ExactSnapshotRestoreAdmissionException(
          "Lifecycle completion is already settling board synchronization.");
    }
    return new ExactSnapshotRestoreAdmission(
        this,
        mirror,
        owner,
        capturedOwnerIdentity,
        authorityIncarnation,
        mirrorIncarnation,
        boardSyncLease,
        primaryEngineGeneration);
  }


  private boolean canCaptureExactSnapshotRestoreAdmission(
      ExactSnapshotRestoreOwner owner, Object ownerIdentity) {
    switch (owner) {
      case ORDINARY:
        return !hasConflictingExactSnapshotRestoreWorkLocked();
      case BOARD_SYNC:
        return !hasConflictingBoardSyncRestoreWorkLocked();
      case READ_BOARD_GMA:
        return !engineStateUnrestored
            && ownerIdentity != null
            && readBoardGmaReservation == ownerIdentity;
      case FOREGROUND:
        return ownerIdentity != null
            && ((exclusiveGtpSession != null
                    && exclusiveGtpSession == ownerIdentity
                    && !exclusiveGtpSession.closing)
                || (foregroundRestoreSession != null
                    && foregroundRestoreSession == ownerIdentity
                    && foregroundRestoreInProgress));
      case LIFECYCLE:
        return ownerIdentity != null
            && !hasConflictingExactSnapshotRestoreWorkLocked(ownerIdentity);
      default:
        return false;
    }
  }

  private boolean hasConflictingExactSnapshotRestoreWorkLocked() {
    return hasConflictingExactSnapshotRestoreWorkLocked(null);
  }

  private boolean hasConflictingExactSnapshotRestoreWorkLocked(Object allowedLifecycleOwner) {
    return (engineStateUnrestored && !allowsUnrestoredLifecycleOwner(allowedLifecycleOwner))
        || readBoardGmaReservation != null
        || trackingHandoffGate != null
        || foregroundRestoreInProgress
        || hasLifecycleCompletionOwnedByOtherLocked(allowedLifecycleOwner)
        || (exclusiveGtpLifecycleTransition
            && (allowedLifecycleOwner == null
                || exclusiveGtpLifecycleOwner != allowedLifecycleOwner))
        || (exclusiveGtpSession != null && !isTrackingStreamSession(exclusiveGtpSession));
  }
  private boolean hasConflictingBoardSyncRestoreWorkLocked() {
    return readBoardGmaReservation != null
        || readBoardGmaRestoreBarrier != null
        || trackingHandoffGate != null
        || foregroundRestoreInProgress
        || exclusiveGtpLifecycleTransition
        || (exclusiveGtpSession != null && !isTrackingStreamSession(exclusiveGtpSession));
  }

  private static boolean allowsUnrestoredLifecycleOwner(Object owner) {
    return owner instanceof AutomaticRestartOwner
        && ((AutomaticRestartOwner) owner).allowUnrestoredState;
  }

  private boolean canAcceptExactSnapshotRestoreAdmission(
      Leelaz authority, ExactSnapshotRestoreOwner owner, Object ownerIdentity) {
    if (this != authority && owner == ExactSnapshotRestoreOwner.READ_BOARD_GMA) {
      synchronized (authority.engineArbitrationLock()) {
        if (ownerIdentity == null
            || authority.engineStateUnrestored
            || authority.readBoardGmaReservation != ownerIdentity) {
          return false;
        }
      }
    }
    synchronized (engineArbitrationLock()) {
      if (this != authority) {
        return owner == ExactSnapshotRestoreOwner.BOARD_SYNC
            ? !hasConflictingBoardSyncRestoreWorkLocked()
            : !hasConflictingExactSnapshotRestoreWorkLocked(
                owner == ExactSnapshotRestoreOwner.LIFECYCLE ? ownerIdentity : null);
      }
      switch (owner) {
        case ORDINARY:
          return !hasConflictingExactSnapshotRestoreWorkLocked();
        case BOARD_SYNC:
          return !hasConflictingBoardSyncRestoreWorkLocked();
        case READ_BOARD_GMA:
          return !engineStateUnrestored
              && ownerIdentity != null
              && readBoardGmaReservation == ownerIdentity;
        case FOREGROUND:
          return canCaptureExactSnapshotRestoreAdmission(owner, ownerIdentity);
        case LIFECYCLE:
          return canCaptureExactSnapshotRestoreAdmission(owner, ownerIdentity);
        default:
          return false;
      }
    }
  }

  private boolean isExactSnapshotRestoreAdmissionValid(
      ExactSnapshotRestoreAdmission admission) {
    if (admission == null || !admission.includes(this)) {
      return false;
    }
    Leelaz authority = admission.authority;
    if (authority == null) {
      return false;
    }
    if (admission.owner == ExactSnapshotRestoreOwner.BOARD_SYNC
        && admission.primaryEngineGeneration >= 0
        && Lizzie.capturePrimaryEngineGeneration(authority)
            != admission.primaryEngineGeneration) {
      return false;
    }
    synchronized (authority.engineArbitrationLock()) {
      switch (admission.owner) {
        case ORDINARY:
          if (authority.hasConflictingExactSnapshotRestoreWorkLocked()) {
            return false;
          }
          break;
        case BOARD_SYNC:
          if (authority.hasConflictingBoardSyncRestoreWorkLocked()) {
            return false;
          }
          break;
        case READ_BOARD_GMA:
          if (admission.ownerIdentity == null
              || authority.engineStateUnrestored
              || authority.readBoardGmaReservation != admission.ownerIdentity
              || authority.currentEngineIncarnation() != admission.authorityIncarnation
              || (admission.mirror != null
                  && admission.mirror.currentEngineIncarnation() != admission.mirrorIncarnation)) {
            return false;
          }
          break;
        case FOREGROUND:
          if (!((authority.exclusiveGtpSession != null
                  && authority.exclusiveGtpSession == admission.ownerIdentity
                  && !authority.exclusiveGtpSession.closing)
              || (authority.foregroundRestoreSession != null
                  && authority.foregroundRestoreSession == admission.ownerIdentity))) {
            return false;
          }
          break;
        case LIFECYCLE:
          if (authority.hasConflictingExactSnapshotRestoreWorkLocked(admission.ownerIdentity)) {
            return false;
          }
          break;
        default:
          return false;
      }
    }
    if (this != authority) {
      synchronized (engineArbitrationLock()) {
        return admission.owner == ExactSnapshotRestoreOwner.BOARD_SYNC
            ? !hasConflictingBoardSyncRestoreWorkLocked()
            : !hasConflictingExactSnapshotRestoreWorkLocked(
                admission.owner == ExactSnapshotRestoreOwner.LIFECYCLE
                    ? admission.ownerIdentity
                    : null);
      }
    }
    return true;
  }

  private boolean isExactSnapshotRestoreAdmissionContextActive() {
    return isExactSnapshotRestoreAdmissionValid(exactSnapshotRestoreAdmissionContext.get());
  }

  void requireExactSnapshotRestoreAdmission(ExactSnapshotRestoreAdmission admission) {
    if (!isExactSnapshotRestoreAdmissionValid(admission)) {
      throw new ExactSnapshotEngineRestore.Failure(
          ExactSnapshotEngineRestore.FailureCategory.ADMISSION_STALE,
          "Exact snapshot restore admission is no longer valid");
    }
  }

  void withExactSnapshotRestoreAdmission(ExactSnapshotRestoreAdmission admission, Runnable action) {
    ExactSnapshotRestoreAdmission previous = exactSnapshotRestoreAdmissionContext.get();
    exactSnapshotRestoreAdmissionContext.set(admission);
    try {
      action.run();
    } finally {
      if (previous == null) {
        exactSnapshotRestoreAdmissionContext.remove();
      } else {
        exactSnapshotRestoreAdmissionContext.set(previous);
      }
    }
  }

  public void endForegroundAnalysisLease(Object owner) {
    endForegroundAnalysisLease(owner, null);
  }

  public boolean endForegroundAnalysisLease(Object owner, Runnable afterRestore) {
    return endForegroundAnalysisLease(owner, afterRestore, null);
  }

  public boolean endForegroundAnalysisLease(
      Object owner, Runnable afterRestore, Runnable afterRestoreFailure) {
    ExclusiveGtpSession session;
    ForegroundRestoreCapture restoreCapture;
    int releaseStopCommandId = 0;
    synchronized (engineArbitrationLock()) {
      session = exclusiveGtpSession;
      if (session == null
          || session.owner != owner
          || session.closing
          || session.releaseRequested) {
        return false;
      }
    }
    try {
      restoreCapture = prepareForegroundRestore(session);
    } catch (ExactSnapshotRestoreAdmissionException conflict) {
      return failForegroundRestoreAdmission(
          session, afterRestore, afterRestoreFailure, conflict.getMessage());
    }
    synchronized (engineArbitrationLock()) {
      if (exclusiveGtpSession != session
          || session.owner != owner
          || session.closing
          || session.releaseRequested) {
        return false;
      }
      session.restoreCapture = restoreCapture;
      session.releaseRequested = true;
      session.afterRestore = afterRestore;
      session.afterRestoreFailure = afterRestoreFailure;
      if (session.active) {
        releaseStopCommandId = exclusiveGtpResponseCommandIds.getAndIncrement();
        session.releaseStopCommandId = releaseStopCommandId;
      }
    }
    if (releaseStopCommandId == 0) {
      return true;
    }
    scheduleExclusiveGtpReleaseStopTimeout(session);
    if (!writeExclusiveGtpCommand(
        session,
        ExclusiveGtpWritePhase.RELEASE_STOP,
        releaseStopCommandId,
        releaseStopCommandId + " stop")) {
      failForegroundLeaseRelease(
          session,
          ForegroundAnalysisLeaseFailure.FINAL_STOP_SEND_FAILED,
          "failed to send final stop command");
    }
    return true;
  }

  private ForegroundRestoreCapture prepareForegroundRestore(ExclusiveGtpSession session) {
    Board board = Lizzie.board;
    if (board == null) {
      return ForegroundRestoreCapture.empty();
    }
    synchronized (board) {
      BoardHistoryList history = board.getHistory();
      Leelaz mirror = resolveLoadSgfMirrorEngine();
      if (mirror == this) {
        mirror = null;
      }
      ExactSnapshotRestoreAdmission admission =
          captureExactSnapshotRestoreAdmission(
              ExactSnapshotRestoreOwner.FOREGROUND, session, mirror);
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore =
          history == null
              ? null
              : ExactSnapshotEngineRestore.prepare(
                      admission, history.getCurrentHistoryNode())
                  .orElse(null);
      Double komi =
          history == null || history.getGameInfo() == null
              ? null
              : history.getGameInfo().getKomi();
      return new ForegroundRestoreCapture(
          preparedRestore,
          admission,
          board,
          mirror,
          Movelist.copyList(board.getMoveList()),
          komi,
          Board.boardWidth,
          Board.boardHeight,
          EngineManager.BoardFrame.capture(board));
    }
  }

  private boolean failForegroundRestoreAdmission(
      ExclusiveGtpSession session,
      Runnable afterRestore,
      Runnable afterRestoreFailure,
      String detail) {
    synchronized (engineArbitrationLock()) {
      if (session == null
          || exclusiveGtpSession != session
          || session.closing
          || session.releaseRequested) {
        return false;
      }
      session.releaseRequested = true;
      session.afterRestore = afterRestore;
      session.afterRestoreFailure = afterRestoreFailure;
      recordForegroundAnalysisLeaseFailure(
          session, ForegroundAnalysisLeaseFailure.RESTORE_FAILED);
      session.restoreFailed = true;
      session.closing = true;
    }
    String message = "Failed to prepare foreground engine restore: " + detail;
    rememberRecentLine(recentStderrLines, message);
    System.err.println(message);
    restoreAfterClosedForegroundLease(session);
    return true;
  }

  public TrackingHandoffClaim claimTrackingHandoff(TrackingHandoffTarget target) {
    if (target == null) {
      return TrackingHandoffClaim.rejected(
          this, target, TrackingHandoffAvailability.INVALID_TARGET);
    }
    TrackingHandoffKind kind;
    try {
      kind = target.kind();
    } catch (Throwable ignored) {
      kind = null;
    }
    if (kind == null) {
      return TrackingHandoffClaim.rejected(
          this, target, TrackingHandoffAvailability.INVALID_TARGET);
    }
    ExclusiveGtpSession session;
    TrackingHandoffClaim claim;
    TrackingDispositionNotification dispositionNotification;
    int releaseStopCommandId = 0;
    synchronized (engineArbitrationLock()) {
      synchronized (commandQueue()) {
        session = exclusiveGtpSession;
        if (trackingHandoffGate != null) {
          return TrackingHandoffClaim.rejected(this, target, TrackingHandoffAvailability.BUSY);
        }
        if (session == null || session.releasePolicy != ExclusiveGtpReleasePolicy.STREAM_ONLY) {
          return TrackingHandoffClaim.rejected(
              this, target, TrackingHandoffAvailability.NOT_TRACKING);
        }
        if (!(session.owner instanceof TrackingStreamLease)
            || session.closing
            || session.releaseRequested
            || exclusiveGtpLifecycleTransition
            || normalCommandSendInProgress
            || !commandQueue().isEmpty()
            || !foregroundRestoreCommandQueue().isEmpty()) {
          return TrackingHandoffClaim.rejected(this, target, TrackingHandoffAvailability.BUSY);
        }
        claim = new TrackingHandoffClaim(this, target, kind, session.wasPondering);
        trackingHandoffGate = claim;
        session.trackingHandoffClaim = claim;
        session.releaseRequested = true;
        dispositionNotification =
            advanceTrackingReleaseDispositionLocked(session, TrackingReleaseDisposition.CLEARED);
        if (session.active) {
          releaseStopCommandId = claimTrackingReleaseStopLocked(session);
        }
      }
    }
    notifyTrackingDisposition(dispositionNotification);
    if (releaseStopCommandId != 0) {
      sendTrackingReleaseStop(session, releaseStopCommandId);
    }
    return claim;
  }

  private TrackingDispositionNotification advanceTrackingReleaseDispositionLocked(
      ExclusiveGtpSession session, TrackingReleaseDisposition disposition) {
    return advanceTrackingReleaseDispositionLocked(session, disposition, null);
  }

  private TrackingDispositionNotification advanceTrackingReleaseDispositionLocked(
      ExclusiveGtpSession session,
      TrackingReleaseDisposition disposition,
      TrackingReleaseReason reason) {
    if (session == null
        || exclusiveGtpSession != session
        || session.closedCallbackRun
        || !(session.owner instanceof TrackingStreamLease)) {
      return null;
    }
    TrackingStreamLease lease = (TrackingStreamLease) session.owner;
    return lease.advanceDisposition(disposition)
        ? new TrackingDispositionNotification(lease.dispositionObserver, disposition, reason)
        : null;
  }

  private void notifyTrackingDisposition(TrackingDispositionNotification notification) {
    if (notification == null || notification.observer == null) {
      return;
    }
    if (notification.reason != null) {
      try {
        notification.observer.onReleaseClaimed(notification.reason);
      } catch (Throwable ignored) {
        // Observer failures do not own transport settlement.
      }
    }
    try {
      notification.observer.onDispositionChanged(notification.disposition);
    } catch (Throwable ignored) {
      // Observer failures do not own transport settlement.
    }
  }

  private boolean endTrackingStreamLease(TrackingStreamLease owner) {
    ExclusiveGtpSession session;
    int releaseStopCommandId = 0;
    synchronized (engineArbitrationLock()) {
      session = exclusiveGtpSession;
      if (session == null
          || session.owner != owner
          || session.releasePolicy != ExclusiveGtpReleasePolicy.STREAM_ONLY
          || session.closing
          || session.releaseRequested) {
        return false;
      }
      session.releaseRequested = true;
      if (session.active) {
        releaseStopCommandId = claimTrackingReleaseStopLocked(session);
      }
    }
    if (releaseStopCommandId == 0) {
      return true;
    }
    sendTrackingReleaseStop(session, releaseStopCommandId);
    return true;
  }

  protected long foregroundReleaseStopTimeoutMillis() {
    return FOREGROUND_RELEASE_STOP_TIMEOUT_MILLIS;
  }

  protected long foregroundInitialStopTimeoutMillis() {
    return FOREGROUND_INITIAL_STOP_TIMEOUT_MILLIS;
  }

  void executeForegroundReleaseStopTimeout(Runnable timeoutAction) {
    timeoutAction.run();
  }

  void executeForegroundInitialStopTimeout(Runnable timeoutAction) {
    timeoutAction.run();
  }

  void beforeForegroundReleaseRestoreAfterBoundary() {}

  private void scheduleExclusiveGtpInitialStopTimeout(ExclusiveGtpSession session) {
    Timer timeout = new Timer("lizzie-exclusive-gtp-initial-stop-timeout", true);
    TimerTask timeoutTask =
        new TimerTask() {
          @Override
          public void run() {
            executeForegroundInitialStopTimeout(
                () -> failExclusiveGtpInitialStop(session, "initial stop response timeout"));
          }
        };
    long timeoutMillis = foregroundInitialStopTimeoutMillis();
    synchronized (engineArbitrationLock()) {
      if (exclusiveGtpSession != session || session.active || session.closing) {
        timeout.cancel();
        return;
      }
      session.initialStopTimeout = timeout;
      timeout.schedule(timeoutTask, timeoutMillis);
    }
  }

  private void cancelExclusiveGtpInitialStopTimeout(ExclusiveGtpSession session) {
    Timer timeout;
    synchronized (engineArbitrationLock()) {
      timeout = session.initialStopTimeout;
      session.initialStopTimeout = null;
    }
    if (timeout != null) {
      timeout.cancel();
    }
  }

  private void failExclusiveGtpInitialStop(ExclusiveGtpSession session, String detail) {
    if (session != null && session.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY) {
      failTrackingStreamLease(
          session, TrackingStreamLeaseFailure.INITIAL_STOP_TIMEOUT, detail, true);
      return;
    }
    if (!abortExclusiveGtpSession(
        session, true, ForegroundAnalysisLeaseFailure.INITIAL_STOP_TIMEOUT)) {
      return;
    }
    String message = "Failed to stop foreground engine before flash analysis: " + detail;
    rememberRecentLine(recentStderrLines, message);
    System.err.println(message);
  }

  private void scheduleExclusiveGtpReleaseStopTimeout(ExclusiveGtpSession session) {
    Timer timeout = new Timer("lizzie-exclusive-gtp-release-stop-timeout", true);
    TimerTask timeoutTask =
        new TimerTask() {
          @Override
          public void run() {
            executeForegroundReleaseStopTimeout(
                () -> {
                  if (session.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY) {
                    failTrackingStreamLease(
                        session,
                        TrackingStreamLeaseFailure.FINAL_STOP_TIMEOUT,
                        "final stop response timeout",
                        true);
                  } else {
                    failForegroundLeaseRelease(
                        session,
                        ForegroundAnalysisLeaseFailure.FINAL_STOP_TIMEOUT,
                        "final stop response timeout");
                  }
                });
          }
        };
    long timeoutMillis = foregroundReleaseStopTimeoutMillis();
    synchronized (engineArbitrationLock()) {
      if (exclusiveGtpSession != session || session.closing || !session.releaseRequested) {
        timeout.cancel();
        return;
      }
      session.releaseStopTimeout = timeout;
      timeout.schedule(timeoutTask, timeoutMillis);
    }
  }

  private void cancelExclusiveGtpReleaseStopTimeout(ExclusiveGtpSession session) {
    Timer timeout;
    synchronized (engineArbitrationLock()) {
      timeout = session.releaseStopTimeout;
      session.releaseStopTimeout = null;
    }
    if (timeout != null) {
      timeout.cancel();
    }
  }

  private void failForegroundLeaseRelease(
      ExclusiveGtpSession session,
      ForegroundAnalysisLeaseFailure failureReason,
      String detail) {
    cancelExclusiveGtpReleaseStopTimeout(session);
    synchronized (engineArbitrationLock()) {
      if (session == null
          || exclusiveGtpSession != session
          || session.restoreCompleted
          || session.restoreStarted
          || session.closing
          || session.releaseStopFailed) {
        return;
      }
      recordForegroundAnalysisLeaseFailure(session, failureReason);
      session.releaseStopFailed = true;
      session.restoreFailed = true;
      session.closing = true;
    }
    String message = "Failed to stop foreground engine before restore: " + detail;
    rememberRecentLine(recentStderrLines, message);
    System.err.println(message);
    restoreAfterClosedForegroundLease(session);
  }

  private void failTrackingStreamLease(
      ExclusiveGtpSession session,
      TrackingStreamLeaseFailure failure,
      String detail,
      boolean notifyClosed) {
    TrackingStreamCleanup cleanup =
        claimTrackingStreamCleanup(session, failure, detail, false, true);
    if (cleanup == null) {
      return;
    }
    cancelExclusiveGtpInitialStopTimeout(session);
    cancelExclusiveGtpReleaseStopTimeout(session);
    String message = "Tracking stream lease failed: " + detail;
    rememberRecentLine(recentStderrLines, message);
    System.err.println(message);
    try {
      notifyTrackingDisposition(cleanup.dispositionNotification);
      notifyGtpCommandStateReset(cleanup.commandStateReset);
    } finally {
      if (notifyClosed && isCurrentTrackingStreamIncarnation(session)) {
        terminateReaderIncarnation(session.readerBinding, null);
      } else {
        closeStreamOnlyExclusiveGtpSession(session, false, notifyClosed);
      }
    }
  }

  private TrackingStreamCleanup claimTrackingStreamCleanup(
      ExclusiveGtpSession expectedSession,
      TrackingStreamLeaseFailure failure,
      String detail,
      boolean retiringForRebind,
      boolean invalidateTransport) {
    synchronized (engineArbitrationLock()) {
      synchronized (commandQueue()) {
        if (expectedSession == null
            || exclusiveGtpSession != expectedSession
            || expectedSession.releasePolicy != ExclusiveGtpReleasePolicy.STREAM_ONLY
            || readerStreamBinding != expectedSession.readerBinding
            || expectedSession.closing) {
          return null;
        }
        if (retiringForRebind) {
          readerStreamRebindInProgress = true;
          retireAnalysisOutputBindingLocked(expectedSession.readerBinding);
        }
        recordTrackingStreamLeaseFailure(expectedSession, failure);
        expectedSession.releaseStopFailed = true;
        expectedSession.closing = true;
        if (invalidateTransport) {
          isLoaded = false;
          outputStream = null;
        }
        GtpCommandStateReset commandStateReset =
            retiringForRebind
                ? resetGtpCommandStateForReaderRebindLocked(detail)
                : resetGtpCommandStateLocked(detail);
        TrackingDispositionNotification dispositionNotification =
            advanceTrackingReleaseDispositionLocked(
                expectedSession, TrackingReleaseDisposition.CLEARED);
        return new TrackingStreamCleanup(
            expectedSession, commandStateReset, dispositionNotification);
      }
    }
  }

  private boolean isCurrentTrackingStreamIncarnation(ExclusiveGtpSession session) {
    synchronized (engineArbitrationLock()) {
      return session != null
          && readerStreamBinding == session.readerBinding
          && !session.readerBinding.terminated;
    }
  }

  private void closeStaleTrackingStreamLease(
      ExclusiveGtpSession session, boolean notifyClosed) {
    synchronized (engineArbitrationLock()) {
      if (session == null || exclusiveGtpSession != session || session.closedCallbackRun) {
        return;
      }
      recordTrackingStreamLeaseFailure(session, TrackingStreamLeaseFailure.TRANSPORT_CLOSED);
      session.closing = true;
    }
    closeStreamOnlyExclusiveGtpSession(session, false, notifyClosed);
  }

  private void restoreAfterClosedForegroundLease(ExclusiveGtpSession session) {
    cancelExclusiveGtpInitialStopTimeout(session);
    cancelExclusiveGtpReleaseStopTimeout(session);
    boolean canRestore;
    synchronized (engineArbitrationLock()) {
      if (session == null || exclusiveGtpSession != session || session.restoreStarted) {
        return;
      }
      session.restoreStarted = true;
      canRestore =
          !session.restoreFailed
              && Lizzie.leelaz == this
              && Lizzie.board != null
              && isLoaded()
              && isStarted();
      exclusiveGtpLifecycleTransition = true;
      exclusiveGtpLifecycleQueueGate = false;
      exclusiveGtpLifecycleOwner = null;
      exclusiveGtpLifecycleDepth = 0;
      foregroundRestoreInProgress = true;
      foregroundRestoreSession = session;
    }
    if (!closeExclusiveGtpSession(session)) {
      failForegroundRestore(session, "lease changed before restore");
      return;
    }
    if (!canRestore) {
      completeForegroundRestore(session);
      return;
    }
    Timer timeout = new Timer("lizzie-foreground-engine-restore-timeout", true);
    session.restoreTimeout = timeout;
    timeout.schedule(
        new TimerTask() {
          @Override
          public void run() {
            failForegroundRestore(session, "restore response timeout");
          }
        },
        30000L);
    startForegroundRestoreAttempt(session);
  }

  private void startForegroundRestoreAttempt(ExclusiveGtpSession session) {
    Thread restoreThread =
        new Thread(() -> performForegroundRestore(session), "lizzie-foreground-engine-restore");
    restoreThread.setDaemon(true);
    synchronized (engineArbitrationLock()) {
      if (session == null || session.restoreCompleted || foregroundRestoreSession != session) {
        return;
      }
      session.restoreThread = restoreThread;
    }
    restoreThread.start();
  }

  private void performForegroundRestore(ExclusiveGtpSession session) {
    foregroundRestoreCommandSession.set(session);
    try {
      ForegroundRestoreCapture restoreCapture = session.restoreCapture;
      if (restoreCapture == null) {
        throw new IllegalStateException("Foreground restore capture is unavailable");
      }
      if (session.originalRules != null) {
        sendCommand("kata-set-rules " + session.originalRules);
      }
      restoreCapture.execute(this);
      if (isForegroundRestoreCompleted(session)) {
        return;
      }
      sendCommand(
          "name",
          () -> completeForegroundRestore(session),
          failure -> failForegroundRestore(session, failure.getMessage()),
          true,
          false);
    } catch (RuntimeException ex) {
      failForegroundRestore(session, ex.getMessage());
    } finally {
      foregroundRestoreCommandSession.remove();
    }
  }

  private boolean isForegroundRestoreCompleted(ExclusiveGtpSession session) {
    synchronized (engineArbitrationLock()) {
      return session == null || session.restoreCompleted;
    }
  }

  private void failForegroundRestore(ExclusiveGtpSession session, String detail) {
    if (!markForegroundRestoreFailed(session, detail)) {
      return;
    }
    completeForegroundRestore(session);
  }

  private boolean markForegroundRestoreFailed(ExclusiveGtpSession session, String detail) {
    synchronized (engineArbitrationLock()) {
      if (session == null || session.restoreCompleted) {
        return false;
      }
      recordForegroundAnalysisLeaseFailure(session, ForegroundAnalysisLeaseFailure.RESTORE_FAILED);
      session.restoreFailed = true;
    }
    String message = "Failed to restore foreground engine after flash analysis: " + detail;
    rememberRecentLine(recentStderrLines, message);
    System.err.println(message);
    if (Lizzie.frame != null && Lizzie.resourceBundle != null) {
      SwingUtilities.invokeLater(
          () ->
              Utils.showMsg(
                  Lizzie.resourceBundle.getString("AnalysisEngine.foregroundRestoreFailed")));
    }
    return true;
  }

  private void completeForegroundRestore(ExclusiveGtpSession session) {
    Timer restoreTimeout;
    Thread restoreThread;
    boolean restoreFailed;
    boolean releaseStopFailed;
    boolean retryRestore;
    Board board = Lizzie.board;
    Object boardLock = board == null ? engineArbitrationLock() : board;
    synchronized (boardLock) {
      synchronized (engineArbitrationLock()) {
        if (session == null || session.restoreCompleted) {
          return;
        }
        ForegroundRestoreCapture restoreCapture = session.restoreCapture;
        boolean restoreFrameChanged =
            restoreCapture == null
                || restoreCapture.board != board
                || !restoreCapture.frame.matches(EngineManager.BoardFrame.capture(board));
        retryRestore =
            !session.restoreFailed && (session.restoreInvalidated || restoreFrameChanged);
        if (retryRestore) {
          session.restoreInvalidated = false;
          restoreTimeout = null;
          restoreThread = null;
          restoreFailed = false;
          releaseStopFailed = false;
        } else {
          session.restoreCompleted = true;
          restoreFailed = session.restoreFailed;
          releaseStopFailed = session.releaseStopFailed;
          restoreTimeout = session.restoreTimeout;
          restoreThread = session.restoreThread;
          if (restoreFailed) {
            isLoaded = false;
          }
          foregroundRestoreInProgress = false;
          foregroundRestoreSession = null;
          suppressNormalCommandsForForegroundAnalysis = false;
          if (!restoreFailed) {
            beforeForegroundRestoreLifecycleRelease();
            finishForegroundRestoreLifecycleLocked();
          }
        }
      }
    }
    if (retryRestore) {
      ForegroundRestoreCapture restoreCapture;
      try {
        restoreCapture = prepareForegroundRestore(session);
      } catch (ExactSnapshotRestoreAdmissionException conflict) {
        failForegroundRestore(session, conflict.getMessage());
        return;
      }
      synchronized (engineArbitrationLock()) {
        if (session.restoreCompleted || foregroundRestoreSession != session) {
          return;
        }
        session.restoreCapture = restoreCapture;
      }
      startForegroundRestoreAttempt(session);
      return;
    }
    if (restoreTimeout != null) {
      restoreTimeout.cancel();
    }
    if (restoreFailed && restoreThread != null && restoreThread != Thread.currentThread()) {
      restoreThread.interrupt();
    }
    if (restoreFailed) {
      try {
        resetGtpCommandStateAfterRestoreFailure("foreground engine restore failed");
        notPondering();
      } finally {
        finishForegroundRestoreLifecycle();
      }
      runForegroundRestoreFailure(session);
      return;
    }
    boolean resumePonder = false;
    try {
      synchronized (commandQueue()) {
        foregroundRestoreCommandQueue().clear();
      }
      try {
        trySendCommandFromQueue();
      } catch (RuntimeException ex) {
        ex.printStackTrace();
      }
      resumePonder =
          session.wasPondering
              && Lizzie.leelaz == this
              && canResumePonderAfterForegroundLease();
    } finally {
      finishForegroundRestoreLifecycle();
    }
    if (resumePonder) {
      ponder();
    }
    if (releaseStopFailed) {
      runForegroundRestoreFailure(session);
    } else {
      runForegroundRestoreCompletion(session);
    }
  }

  private void runForegroundRestoreCompletion(ExclusiveGtpSession session) {
    Runnable completion;
    synchronized (engineArbitrationLock()) {
      completion = session.afterRestore;
      session.afterRestore = null;
      session.afterRestoreFailure = null;
    }
    if (completion != null) {
      completion.run();
    }
  }

  private void runForegroundRestoreFailure(ExclusiveGtpSession session) {
    Runnable failure;
    synchronized (engineArbitrationLock()) {
      failure = session.afterRestoreFailure;
      session.afterRestore = null;
      session.afterRestoreFailure = null;
    }
    if (failure != null) {
      failure.run();
    }
  }

  private void resetGtpCommandStateAfterRestoreFailure(String detail) {
    GtpCommandStateReset reset;
    synchronized (commandQueue()) {
      reset = resetGtpCommandStateLocked(detail);
    }
    notifyGtpCommandStateReset(reset);
  }

  private GtpCommandStateReset resetGtpCommandStateLocked(String detail) {
    return resetGtpCommandStateLocked(detail, true);
  }

  private GtpCommandStateReset resetGtpCommandStateForReaderRebindLocked(String detail) {
    return resetGtpCommandStateLocked(detail, false);
  }

  private GtpCommandStateReset resetGtpCommandStateLocked(
      String detail, boolean retainSentTrackedLoadSgfHandlers) {
    RuntimeException failure =
        new IllegalStateException(
            "Engine command state reset interrupted pending protocol work: " + detail);
    List<QueuedCommand> cancelledLoadSgfCommands = new ArrayList<>();
    List<QueuedCommand> sentLoadSgfCommands = new ArrayList<>();
    List<QueuedCommand> cancelledEngineGameCommands = new ArrayList<>();
    List<QueuedCommand> sentEngineGameCommands = new ArrayList<>();
    ArrayDeque<PendingResponseHandler> handlers = pendingResponseHandlers();
    cancelQueuedLoadSgfCommands(commandQueue(), failure, cancelledLoadSgfCommands);
    cancelQueuedLoadSgfCommands(foregroundRestoreCommandQueue(), failure, cancelledLoadSgfCommands);
    classifyEngineGameResetCommands(
        commandQueue(), failure, cancelledEngineGameCommands, sentEngineGameCommands);
    classifyEngineGameResetCommands(
        foregroundRestoreCommandQueue(),
        failure,
        cancelledEngineGameCommands,
        sentEngineGameCommands);
    classifyTrackedLoadSgfReset(
        normalCommandBeingSent, failure, cancelledLoadSgfCommands, sentLoadSgfCommands);
    classifyEngineGameReset(
        normalCommandBeingSent,
        failure,
        cancelledEngineGameCommands,
        sentEngineGameCommands);
    commandQueue().clear();
    foregroundRestoreCommandQueue().clear();
    synchronized (handlers) {
      Iterator<PendingResponseHandler> iterator = handlers.iterator();
      while (iterator.hasNext()) {
        PendingResponseHandler handler = iterator.next();
        if (handler.queuedCommand.requiresStateReset()) {
          classifyEngineGameReset(
              handler.queuedCommand,
              failure,
              cancelledEngineGameCommands,
              sentEngineGameCommands);
          boolean cancelled =
              classifyTrackedLoadSgfReset(
                  handler.queuedCommand, failure, cancelledLoadSgfCommands, sentLoadSgfCommands);
          if (!cancelled && handler.isTrackedLoadSgf() && retainSentTrackedLoadSgfHandlers) {
            handler.requireMatchingResponseCommandId();
            continue;
          }
        }
        iterator.remove();
      }
    }
    cmdNumber = 1;
    currentCmdNum = 0;
    modifyNumber = 0;
    EngineGameResponseHandler retiredEngineGameHandler =
        activeEngineGameResponseHandler.getAndSet(null);
    return new GtpCommandStateReset(
        failure,
        cancelledLoadSgfCommands,
        sentLoadSgfCommands,
        cancelledEngineGameCommands,
        sentEngineGameCommands,
        retiredEngineGameHandler);
  }

  private void classifyEngineGameResetCommands(
      ArrayDeque<QueuedCommand> queue,
      RuntimeException failure,
      List<QueuedCommand> cancelled,
      List<QueuedCommand> sent) {
    for (QueuedCommand command : queue) {
      classifyEngineGameReset(command, failure, cancelled, sent);
    }
  }

  private void classifyEngineGameReset(
      QueuedCommand command,
      RuntimeException failure,
      List<QueuedCommand> cancelled,
      List<QueuedCommand> sent) {
    if (command == null || !command.isEngineGameCommand()) {
      return;
    }
    if (command.isEngineGamePhysicalWriteClaimed()) {
      command.markStateResetAfterOutputWrite(failure);
      if (!sent.contains(command)) {
        sent.add(command);
      }
    } else if (command.cancelBeforeOutputWrite(failure)) {
      // The command lock prevents beginOutputWrite from winning. Leave the request-specific
      // RESERVED state intact so the lock-free notification phase below is the single owner that
      // settles the permit and fails an otherwise-current transaction.
      if (!cancelled.contains(command)) {
        cancelled.add(command);
      }
    }
  }

  private void notifyGtpCommandStateReset(GtpCommandStateReset reset) {
    Throwable firstFailure = null;
    for (QueuedCommand command : reset.cancelledLoadSgfCommands) {
      try {
        command.notifySendFailure(reset.failure);
      } catch (Throwable failure) {
        if (firstFailure == null) {
          firstFailure = failure;
        }
      }
    }
    for (QueuedCommand command : reset.sentLoadSgfCommands) {
      try {
        command.publishStateResetAfterOutputWrite();
      } catch (Throwable failure) {
        if (firstFailure == null) {
          firstFailure = failure;
        }
      }
    }
    for (QueuedCommand command : reset.cancelledEngineGameCommands) {
      try {
        command.notifySendFailure(reset.failure);
      } catch (Throwable failure) {
        firstFailure = appendEngineCommandResetFailure(firstFailure, failure);
      }
    }
    for (QueuedCommand command : reset.sentEngineGameCommands) {
      try {
        command.publishStateResetAfterOutputWrite();
      } catch (Throwable failure) {
        firstFailure = appendEngineCommandResetFailure(firstFailure, failure);
      }
    }
    if (reset.retiredEngineGameHandler != null) {
      try {
        reset.retiredEngineGameHandler.failRetiredBinding(reset.failure);
      } catch (Throwable failure) {
        firstFailure = appendEngineCommandResetFailure(firstFailure, failure);
      }
      try {
        // Physical engine-game leases may acquire the selection lock while closing. Settle only in
        // this notification phase, after every engine/command lock held by reset has released.
        reset.retiredEngineGameHandler.markSettledForBindingRetirement();
      } catch (Throwable failure) {
        firstFailure = appendEngineCommandResetFailure(firstFailure, failure);
      }
    }
    if (firstFailure instanceof RuntimeException) {
      throw (RuntimeException) firstFailure;
    }
    if (firstFailure instanceof Error) {
      throw (Error) firstFailure;
    }
  }

  private static Throwable appendEngineCommandResetFailure(
      Throwable primary, Throwable cleanup) {
    if (primary == null) {
      return cleanup;
    }
    if (cleanup != null && cleanup != primary) {
      try {
        primary.addSuppressed(cleanup);
      } catch (RuntimeException | Error ignored) {
      }
    }
    return primary;
  }

  private void cancelQueuedLoadSgfCommands(
      ArrayDeque<QueuedCommand> queue,
      RuntimeException failure,
      List<QueuedCommand> cancelledCommands) {
    for (QueuedCommand command : queue) {
      // Engine-game requests have their own exact response and physical-write ownership. Keep
      // them out of this legacy bucket, while preserving the reset generation retirement for
      // every non-engine-game command that this path classifies.
      if (!command.requiresStateReset() || command.isEngineGameCommand()) {
        continue;
      }
      // Claim retirement even if another cancellation path already owns send settlement but has
      // not removed this command from the queue yet.  The reset installs a fresh counter baseline
      // below, so any late owner must become a no-op against that new generation.
      command.claimCommandCountRetirement();
      if (command.cancelBeforeOutputWrite(failure)) {
        addUniqueCommand(cancelledCommands, command);
      }
    }
  }

  private boolean classifyTrackedLoadSgfReset(
      QueuedCommand command,
      RuntimeException failure,
      List<QueuedCommand> cancelledCommands,
      List<QueuedCommand> sentCommands) {
    if (command == null || !command.requiresStateReset() || command.isEngineGameCommand()) {
      return false;
    }
    // The reset owns retirement of every command it classifies.  In particular, a physical write
    // may already be in progress and fail after cmdNumber/currentCmdNum have been reset.  Mark its
    // count as retired now so that the late send-failure path cannot mutate the new generation.
    command.claimCommandCountRetirement();
    if (command.cancelBeforeOutputWrite(failure)) {
      addUniqueCommand(cancelledCommands, command);
      return true;
    }
    command.markStateResetAfterOutputWrite(failure);
    addUniqueCommand(sentCommands, command);
    return false;
  }

  private void addUniqueCommand(List<QueuedCommand> commands, QueuedCommand command) {
    if (!commands.contains(command)) {
      commands.add(command);
    }
  }

  protected void beforeForegroundRestoreLifecycleRelease() {}

  private void finishForegroundRestoreLifecycle() {
    synchronized (engineArbitrationLock()) {
      finishForegroundRestoreLifecycleLocked();
    }
  }

  private void finishForegroundRestoreLifecycleLocked() {
    exclusiveGtpLifecycleTransition = false;
    exclusiveGtpLifecycleQueueGate = false;
    exclusiveGtpLifecycleOwner = null;
    exclusiveGtpLifecycleDepth = 0;
  }

  private boolean canResumePonderAfterForegroundLease() {
    return isLoaded()
        && isStarted()
        && !isThinking
        && !EngineManager.isEngineGame()
        && (Lizzie.frame == null
            || (!Lizzie.frame.isPlayingAgainstLeelaz
                && !Lizzie.frame.isAnaPlayingAgainstLeelaz
                && !Lizzie.frame.isContributing
                && (Lizzie.frame.humanSlGame == null || Lizzie.frame.humanSlGame.isFinished())
                && (Lizzie.frame.readBoard == null
                    || !Lizzie.frame.readBoard.isReadBoardGmaEngineBusy())));
  }

  private boolean writeExclusiveGtpCommand(
      ExclusiveGtpSession expectedSession,
      ExclusiveGtpWritePhase phase,
      int expectedCommandId,
      String command) {
    return writeExclusiveGtpCommandResult(expectedSession, phase, expectedCommandId, command)
        == ExclusiveGtpWriteResult.SENT;
  }

  private ExclusiveGtpWriteResult writeExclusiveGtpCommandResult(
      ExclusiveGtpSession expectedSession,
      ExclusiveGtpWritePhase phase,
      int expectedCommandId,
      String command) {
    BufferedOutputStream currentOutputStream = outputStream;
    if (currentOutputStream == null) {
      return ExclusiveGtpWriteResult.NOT_CLAIMED;
    }
    try {
      synchronized (currentOutputStream) {
        synchronized (engineArbitrationLock()) {
          if (!canWriteExclusiveGtpCommand(expectedSession, phase, expectedCommandId)) {
            return ExclusiveGtpWriteResult.NOT_CLAIMED;
          }
        }
        currentOutputStream.write((command + "\n").getBytes());
        currentOutputStream.flush();
      }
      return ExclusiveGtpWriteResult.SENT;
    } catch (IOException ex) {
      boolean partialWrite = clearBufferedCommandBytesAfterSendFailure(currentOutputStream);
      if (partialWrite
          && expectedSession != null
          && expectedSession.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY) {
        invalidateCommandOutputStreamAfterPartialWrite(currentOutputStream, command);
      }
      rememberRecentLine(
          recentStderrLines,
          "Failed to send exclusive remote GTP command '" + command + "': " + ex.getMessage());
      return ExclusiveGtpWriteResult.SEND_FAILED;
    }
  }

  private boolean canWriteExclusiveGtpCommand(
      ExclusiveGtpSession expectedSession,
      ExclusiveGtpWritePhase phase,
      int expectedCommandId) {
    if (exclusiveGtpSession != expectedSession
        || expectedSession == null
        || expectedSession.closing
        || expectedSession.restoreCompleted) {
      return false;
    }
    if (expectedSession.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY
        && (readerStreamBinding != expectedSession.readerBinding
            || expectedSession.readerBinding.terminated)) {
      return false;
    }
    switch (phase) {
      case INITIAL_STOP:
        return !expectedSession.active
            && !expectedSession.releaseRequested
            && expectedSession.stopCommandId == expectedCommandId
            && (expectedSession.releasePolicy != ExclusiveGtpReleasePolicy.STREAM_ONLY
                || expectedSession.trackingInitialWriteState == TrackingWriteState.WRITING);
      case ACTIVE_COMMAND:
        return expectedSession.active
            && (expectedSession.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY
                ? expectedSession.trackingActiveWriteState == TrackingWriteState.WRITING
                : !expectedSession.releaseRequested);
      case RELEASE_STOP:
        return expectedSession.active
            && expectedSession.releaseRequested
            && !expectedSession.releaseStopFailed
            && expectedSession.releaseStopCommandId == expectedCommandId
            && (expectedSession.releasePolicy != ExclusiveGtpReleasePolicy.STREAM_ONLY
                || expectedSession.trackingFinalWriteState == TrackingWriteState.WRITING);
      default:
        return false;
    }
  }

  private boolean dispatchExclusiveGtpLine(String line) {
    return dispatchExclusiveGtpLine(currentReaderStreamBinding(), line);
  }

  private boolean dispatchExclusiveGtpLine(ReaderStreamBinding binding, String line) {
    ExclusiveGtpSession session = exclusiveGtpSession;
    if (session == null) {
      return false;
    }
    if (session.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY
        && session.readerBinding != binding) {
      closeStaleTrackingStreamLease(session, true);
      return false;
    }
    String trimmed = line == null ? "" : line.trim();
    if (!session.active) {
      if (session.releasePolicy == ExclusiveGtpReleasePolicy.FOREGROUND_RESTORE
          && trimmed.startsWith("info ")) {
        return true;
      }
      if (trimmed.startsWith("?") && parseResponseCommandId(trimmed) == session.stopCommandId) {
        if (session.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY) {
          boolean failNow = false;
          synchronized (engineArbitrationLock()) {
            if (exclusiveGtpSession == session && !session.closing) {
              session.initialStopErrorResponse = trimmed;
              failNow = session.trackingInitialWriteState == TrackingWriteState.SENT;
            }
          }
          if (failNow) {
            failTrackingStreamLease(
                session,
                TrackingStreamLeaseFailure.INITIAL_STOP_ERROR_RESPONSE,
                "initial stop command failed: " + trimmed,
                true);
          }
        } else {
          abortExclusiveGtpSession(
              session, true, ForegroundAnalysisLeaseFailure.INITIAL_STOP_ERROR_RESPONSE);
        }
        return true;
      }
      if (trimmed.isEmpty() && completeExclusiveGtpInitialStopBoundary(session)) {
        return true;
      }
      return false;
    }
    if (session.releaseRequested) {
      if (session.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY
          && session.releaseStopCommandId == 0) {
        recordTrackingAnalyzeTerminator(session, trimmed);
        session.lineConsumer.accept(line == null ? "" : line);
        return true;
      }
      if (session.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY
          && session.trackingActiveWriteState == TrackingWriteState.SENT
          && !session.trackingAnalyzeClosed) {
        recordTrackingAnalyzeTerminator(session, trimmed);
        return true;
      }
      int responseCommandId = parseResponseCommandId(trimmed);
      if (responseCommandId == session.releaseStopCommandId) {
        if (session.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY) {
          boolean failNow = false;
          synchronized (engineArbitrationLock()) {
            if (exclusiveGtpSession == session && !session.closing) {
              if (trimmed.startsWith("?")) {
                session.releaseStopErrorResponse = trimmed;
                failNow = session.trackingFinalWriteState == TrackingWriteState.SENT;
              } else if (trimmed.startsWith("=")) {
                session.releaseStopAcknowledged = true;
              }
            }
          }
          if (failNow) {
            failTrackingStreamLease(
                session,
                TrackingStreamLeaseFailure.FINAL_STOP_ERROR_RESPONSE,
                "final stop command failed: " + trimmed,
                true);
          }
        } else if (trimmed.startsWith("?")) {
          failForegroundLeaseRelease(
              session,
              ForegroundAnalysisLeaseFailure.FINAL_STOP_ERROR_RESPONSE,
              "final stop command failed: " + trimmed);
        } else if (trimmed.startsWith("=")) {
          synchronized (engineArbitrationLock()) {
            if (exclusiveGtpSession == session && !session.closing) {
              session.releaseStopAcknowledged = true;
            }
          }
        }
        return true;
      }
      if (session.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY
          && trimmed.isEmpty()
          && session.releaseStopAcknowledged) {
        synchronized (engineArbitrationLock()) {
          if (exclusiveGtpSession == session && !session.closing) {
            session.releaseStopTerminated = true;
          }
        }
        completeTrackingReleaseBoundary(session);
        return true;
      }
      boolean restore = false;
      synchronized (engineArbitrationLock()) {
        if (exclusiveGtpSession == session
            && !session.closing
            && session.releaseStopAcknowledged
            && trimmed.isEmpty()) {
          session.closing = true;
          restore = true;
        }
      }
      if (restore) {
        if (session.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY) {
          closeStreamOnlyExclusiveGtpSession(session);
        } else {
          beforeForegroundReleaseRestoreAfterBoundary();
          restoreAfterClosedForegroundLease(session);
        }
      }
      return true;
    }
    recordTrackingAnalyzeTerminator(session, trimmed);
    session.lineConsumer.accept(line == null ? "" : line);
    return true;
  }

  private boolean completeTrackingReleaseBoundary(ExclusiveGtpSession session) {
    synchronized (engineArbitrationLock()) {
      if (exclusiveGtpSession != session
          || session.closing
          || session.trackingFinalWriteState != TrackingWriteState.SENT
          || !session.releaseStopAcknowledged
          || !session.releaseStopTerminated
          || (session.trackingActiveWriteState == TrackingWriteState.SENT
              && !session.trackingAnalyzeClosed)) {
        return false;
      }
      session.closing = true;
    }
    closeStreamOnlyExclusiveGtpSession(session);
    return true;
  }

  private void recordTrackingAnalyzeTerminator(ExclusiveGtpSession session, String line) {
    if (session.releasePolicy != ExclusiveGtpReleasePolicy.STREAM_ONLY
        || !line.isEmpty()
        || (session.trackingActiveWriteState != TrackingWriteState.WRITING
            && session.trackingActiveWriteState != TrackingWriteState.SENT)) {
      return;
    }
    synchronized (engineArbitrationLock()) {
      if (exclusiveGtpSession == session && !session.closing) {
        session.trackingAnalyzeClosed = true;
      }
    }
  }

  private void acknowledgeExclusiveGtpInitialStop(String line) {
    synchronized (engineArbitrationLock()) {
      ExclusiveGtpSession session = exclusiveGtpSession;
      if (session == null
          || session.active
          || session.closing
          || line == null
          || !line.trim().startsWith("=")
          || parseResponseCommandId(line) != session.stopCommandId) {
        return;
      }
      session.initialStopAcknowledged = true;
    }
  }

  private boolean completeExclusiveGtpInitialStopBoundary(ExclusiveGtpSession session) {
    Runnable onReady = null;
    boolean restore = false;
    synchronized (engineArbitrationLock()) {
      if (session == null
          || exclusiveGtpSession != session
          || session.active
          || session.closing
          || !session.initialStopAcknowledged) {
        return false;
      }
      if (session.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY) {
        session.initialStopTerminated = true;
        if (session.trackingInitialWriteState != TrackingWriteState.SENT) {
          return true;
        }
      }
      session.active = true;
      if (session.releaseRequested) {
        session.closing = true;
        restore = true;
      } else {
        onReady = session.onReady;
      }
    }
    cancelExclusiveGtpInitialStopTimeout(session);
    if (restore) {
      if (session.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY) {
        closeStreamOnlyExclusiveGtpSession(session);
      } else {
        restoreAfterClosedForegroundLease(session);
      }
    }
    if (onReady != null) {
      onReady.run();
    }
    return true;
  }

  private void closeStreamOnlyExclusiveGtpSession(ExclusiveGtpSession session) {
    closeStreamOnlyExclusiveGtpSession(session, true, true);
  }

  private void closeStreamOnlyExclusiveGtpSession(
      ExclusiveGtpSession session, boolean advanceOrdinaryQueue, boolean notifyClosed) {
    cancelExclusiveGtpInitialStopTimeout(session);
    cancelExclusiveGtpReleaseStopTimeout(session);
    TrackingHandoffClaim handoff = session == null ? null : session.trackingHandoffClaim;
    boolean promoteHandoff =
        handoff != null
            && handoff.state.get() == TrackingHandoffState.ACCEPTED_PENDING
            && session.trackingLeaseFailureReason() == null;
    if (!closeExclusiveGtpSession(session, promoteHandoff ? false : advanceOrdinaryQueue)) {
      return;
    }
    runStreamOnlyClosedCallback(session, notifyClosed);
    if (promoteHandoff) {
      promoteTrackingHandoff(handoff);
    } else if (handoff != null) {
      failTrackingHandoff(handoff, TrackingHandoffFailure.TRACKING_FAILED);
    }
  }

  private void runStreamOnlyClosedCallback(ExclusiveGtpSession session, boolean notifyClosed) {
    if (!notifyClosed) {
      return;
    }
    Runnable onClosed;
    synchronized (engineArbitrationLock()) {
      if (session.closedCallbackRun) {
        return;
      }
      session.closedCallbackRun = true;
      onClosed = session.onClosed;
    }
    runTrackingCallback(onClosed);
  }

  private void runTrackingCallback(Runnable callback) {
    if (callback == null) {
      return;
    }
    try {
      callback.run();
    } catch (Throwable ignored) {
      // Tracking callbacks run after ownership has already settled.
    }
  }

  private void promoteTrackingHandoff(TrackingHandoffClaim claim) {
    synchronized (engineArbitrationLock()) {
      if (trackingHandoffGate != claim
          || !claim.state.compareAndSet(
              TrackingHandoffState.ACCEPTED_PENDING, TrackingHandoffState.ACTIVATING)) {
        return;
      }
    }
    try {
      if (!claim.target.isCurrent()) {
        failTrackingHandoff(claim, TrackingHandoffFailure.CONTEXT_INVALIDATED);
        return;
      }
      synchronized (engineArbitrationLock()) {
        if (trackingHandoffGate != claim
            || claim.state.get() != TrackingHandoffState.ACTIVATING) {
          return;
        }
        claim.activationCallbackInProgress = true;
      }
      claim.target.activate(new TrackingHandoffActivationImpl(claim));
      if (claim.state.get() == TrackingHandoffState.ACTIVATING) {
        failTrackingHandoff(claim, TrackingHandoffFailure.ACTIVATION_FAILED);
      }
    } catch (Throwable failure) {
      failTrackingHandoff(claim, TrackingHandoffFailure.ACTIVATION_FAILED);
    } finally {
      settleTrackingHandoffAfterActivationCallback(claim);
    }
  }

  private boolean completeRetainedTrackingHandoff(TrackingHandoffClaim claim) {
    synchronized (engineArbitrationLock()) {
      if (trackingHandoffGate != claim
          || claim.kind != TrackingHandoffKind.RETAINED_ENGINE_MODE
          || !claim.state.compareAndSet(
              TrackingHandoffState.ACTIVATING, TrackingHandoffState.ACTIVE)) {
        return false;
      }
      trackingHandoffGate = null;
    }
    trySendCommandFromQueue();
    return true;
  }

  private EngineModeReservation beginRetainedTrackingHandoffReservation(
      TrackingHandoffClaim claim) {
    synchronized (engineArbitrationLock()) {
      if (trackingHandoffGate != claim
          || claim.kind != TrackingHandoffKind.RETAINED_ENGINE_MODE
          || exclusiveGtpSession != null
          || exclusiveGtpLifecycleTransition
          || engineStateUnrestored
          || readBoardGmaReservation != null
          || !claim.state.compareAndSet(
              TrackingHandoffState.ACTIVATING, TrackingHandoffState.ACTIVE)) {
        return null;
      }
      Object owner = Thread.currentThread();
      trackingHandoffGate = null;
      exclusiveGtpLifecycleTransition = true;
      exclusiveGtpLifecycleOwner = owner;
      exclusiveGtpLifecycleDepth = 1;
      return new EngineModeReservation(this, owner);
    }
  }

  private boolean activateForegroundTrackingHandoff(
      TrackingHandoffClaim claim, Consumer<String> lineConsumer, Runnable onClosed) {
    synchronized (engineArbitrationLock()) {
      if (trackingHandoffGate != claim
          || claim.kind != TrackingHandoffKind.FOREGROUND_ANALYSIS
          || lineConsumer == null
          || !claim.state.compareAndSet(
              TrackingHandoffState.ACTIVATING, TrackingHandoffState.ACTIVE)) {
        return false;
      }
      ExclusiveGtpSession session =
          new ExclusiveGtpSession(
              claim.target,
              lineConsumer,
              null,
              onClosed,
              exclusiveGtpResponseCommandIds.getAndIncrement(),
              ExclusiveGtpReleasePolicy.FOREGROUND_RESTORE,
              null);
      session.active = true;
      session.wasPondering = claim.wasPondering;
      exclusiveGtpSession = session;
      suppressNormalCommandsForForegroundAnalysis = true;
      trackingHandoffGate = null;
    }
    return true;
  }

  private boolean failTrackingHandoff(TrackingHandoffClaim claim, TrackingHandoffFailure failure) {
    TrackingHandoffFailureSettlement settlement;
    synchronized (engineArbitrationLock()) {
      settlement = claimTrackingHandoffFailureLocked(claim, failure);
    }
    notifyTrackingHandoffFailure(settlement.notification);
    return settlement.won;
  }

  private TrackingHandoffFailureSettlement claimTrackingHandoffFailureLocked(
      TrackingHandoffClaim claim, TrackingHandoffFailure failure) {
    TrackingHandoffState current = claim.state.get();
    if (current == TrackingHandoffState.FAILED || current == TrackingHandoffState.ACTIVE) {
      return TrackingHandoffFailureSettlement.NOT_WON;
    }
    if (current == TrackingHandoffState.ACTIVATING
        && claim.activationCallbackInProgress
        && failure == TrackingHandoffFailure.TARGET_CANCELLED) {
      return TrackingHandoffFailureSettlement.NOT_WON;
    }
    if (!claim.state.compareAndSet(current, TrackingHandoffState.FAILED)) {
      return TrackingHandoffFailureSettlement.NOT_WON;
    }
    if (current == TrackingHandoffState.ACTIVATING && claim.activationCallbackInProgress) {
      claim.deferredFailure = failure;
      return TrackingHandoffFailureSettlement.WON_DEFERRED;
    }
    if (trackingHandoffGate == claim) {
      trackingHandoffGate = null;
    }
    return new TrackingHandoffFailureSettlement(
        true, new TrackingHandoffFailureNotification(claim.target, failure));
  }

  private void settleTrackingHandoffAfterActivationCallback(TrackingHandoffClaim claim) {
    TrackingHandoffFailureNotification notification = null;
    synchronized (engineArbitrationLock()) {
      if (trackingHandoffGate == claim
          && claim.state.get() == TrackingHandoffState.FAILED
          && claim.deferredFailure != null) {
        notification =
            new TrackingHandoffFailureNotification(claim.target, claim.deferredFailure);
        claim.deferredFailure = null;
      }
    }
    try {
      notifyTrackingHandoffFailure(notification);
    } finally {
      boolean gateCleared = false;
      synchronized (engineArbitrationLock()) {
        if (trackingHandoffGate == claim
            && claim.state.get() == TrackingHandoffState.FAILED) {
          trackingHandoffGate = null;
          gateCleared = true;
        }
        claim.activationCallbackInProgress = false;
        engineArbitrationLock().notifyAll();
      }
      if (gateCleared) {
        trySendCommandFromQueue();
      }
    }
  }

  private void notifyTrackingHandoffFailure(TrackingHandoffFailureNotification notification) {
    if (notification == null) {
      return;
    }
    try {
      notification.target.fail(notification.failure);
    } catch (Throwable ignored) {
      // Target failures cannot retain the queue gate.
    } finally {
      trySendCommandFromQueue();
    }
  }

  private final class TrackingHandoffActivationImpl implements TrackingHandoffActivation {
    private final TrackingHandoffClaim claim;

    private TrackingHandoffActivationImpl(TrackingHandoffClaim claim) {
      this.claim = claim;
    }

    @Override
    public boolean activateForegroundAnalysis(Consumer<String> lineConsumer, Runnable onClosed) {
      return activateForegroundTrackingHandoff(claim, lineConsumer, onClosed);
    }

    @Override
    public boolean completeRetainedEngineMode() {
      return completeRetainedTrackingHandoff(claim);
    }

    @Override
    public EngineModeReservation beginRetainedEngineModeReservation() {
      return beginRetainedTrackingHandoffReservation(claim);
    }
  }

  private void abortExclusiveGtpSession() {
    abortExclusiveGtpSession(exclusiveGtpSession);
  }

  private boolean abortExclusiveGtpSession(ExclusiveGtpSession expectedSession) {
    return abortExclusiveGtpSession(expectedSession, false, null);
  }

  private boolean abortExclusiveGtpSession(
      ExclusiveGtpSession expectedSession,
      boolean onlyBeforeReady,
      ForegroundAnalysisLeaseFailure failureReason) {
    Runnable onClosed = null;
    ExclusiveGtpSession closedSession = null;
    synchronized (engineArbitrationLock()) {
      ExclusiveGtpSession session = exclusiveGtpSession;
      if (session == null
          || session != expectedSession
          || session.closing
          || session.releasePolicy == ExclusiveGtpReleasePolicy.STREAM_ONLY
          || (onlyBeforeReady && session.active)) {
        return false;
      }
      if (failureReason != null) {
        recordForegroundAnalysisLeaseFailure(session, failureReason);
        session.restoreFailed = true;
      }
      session.closing = true;
      closedSession = session;
      onClosed = session.onClosed;
    }
    restoreAfterClosedForegroundLease(closedSession);
    if (onClosed != null) {
      onClosed.run();
    }
    return true;
  }

  private boolean isCurrentCommandResponseError() {
    return currentCommandResponseError;
  }

  private String currentCommandResponseLine() {
    return currentCommandResponseLine;
  }

  @FunctionalInterface
  interface CommandSendFailureHandler {
    void onSendFailure(RuntimeException ex);

    default void onStateResetAfterOutputWrite(RuntimeException ex) {
      onSendFailure(ex);
    }
  }

  private static final class RecoverableBufferedOutputStream extends BufferedOutputStream {
    private boolean partialWriteDetected;
    /** True after at least one byte reached the delegate and until its flush succeeds. */
    private boolean delegatedBytesAwaitingFlush;

    private RecoverableBufferedOutputStream(OutputStream out) {
      super(out);
    }

    @Override
    public synchronized void write(int value) throws IOException {
      if (count >= buf.length) {
        flushBufferedBytesToUnderlying();
      }
      buf[count++] = (byte) value;
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
      if (bytes == null) {
        throw new NullPointerException("bytes");
      }
      if (offset < 0 || length < 0 || length > bytes.length - offset) {
        throw new IndexOutOfBoundsException();
      }
      if (length == 0) {
        return;
      }
      if (length >= buf.length) {
        flushBufferedBytesToUnderlying();
        writeDirectToUnderlying(bytes, offset, length);
        return;
      }
      if (length > buf.length - count) {
        flushBufferedBytesToUnderlying();
      }
      System.arraycopy(bytes, offset, buf, count, length);
      count += length;
    }

    @Override
    public synchronized void flush() throws IOException {
      try {
        flushBufferedBytesToUnderlying();
        out.flush();
        delegatedBytesAwaitingFlush = false;
      } catch (IOException flushFailure) {
        if (delegatedBytesAwaitingFlush) {
          partialWriteDetected = true;
        }
        throw flushFailure;
      }
    }

    private void flushBufferedBytesToUnderlying() throws IOException {
      if (count <= 0) {
        return;
      }
      int bufferedByteCount = count;
      count = 0;
      writeDirectToUnderlying(buf, 0, bufferedByteCount);
    }

    private void writeDirectToUnderlying(byte[] bytes, int offset, int length) throws IOException {
      int writtenBytes = 0;
      try {
        while (writtenBytes < length) {
          out.write(bytes[offset + writtenBytes]);
          writtenBytes++;
          delegatedBytesAwaitingFlush = true;
        }
      } catch (IOException ex) {
        if (writtenBytes > 0) {
          partialWriteDetected = true;
        }
        throw ex;
      }
    }

    private void discardBufferedBytes() {
      count = 0;
    }

    private boolean consumePartialWriteDetected() {
      boolean detected = partialWriteDetected;
      partialWriteDetected = false;
      return detected;
    }
  }

  private static final class PendingResponseHandler {
    private final String command;
    private final Runnable handler;
    private final QueuedCommand queuedCommand;
    private final int responseCommandId;
    private final boolean exactSnapshotLoadSgf;
    private final ReaderStreamBinding responseBinding;
    private final String loggingEngineId;
    private final String loggingCommandId;
    private final String commandName;
    private final long sentAtNanos;
    private boolean requiresMatchingResponseCommandId;

    private PendingResponseHandler(
        String command,
        Runnable handler,
        QueuedCommand queuedCommand,
        int responseCommandId,
        boolean requiresMatchingResponseCommandId,
        boolean exactSnapshotLoadSgf,
        ReaderStreamBinding responseBinding,
        String loggingEngineId,
        String loggingCommandId,
        String commandName,
        long sentAtNanos) {
      this.command = command;
      this.handler = handler;
      this.queuedCommand = queuedCommand;
      this.responseCommandId = responseCommandId;
      this.requiresMatchingResponseCommandId = requiresMatchingResponseCommandId;
      this.exactSnapshotLoadSgf = exactSnapshotLoadSgf;
      this.responseBinding = responseBinding;
      this.loggingEngineId = loggingEngineId;
      this.loggingCommandId = loggingCommandId;
      this.commandName = commandName;
      this.sentAtNanos = sentAtNanos;
    }

    private boolean isStaleResponseBinding(
        ReaderStreamBinding binding, ReaderStreamBinding currentBinding) {
      return responseBinding != null
          && (responseBinding != binding
              || responseBinding != currentBinding
              || responseBinding.terminated
              || binding == null
              || binding.terminated
              || currentBinding == null
              || currentBinding.terminated);
    }

    private boolean isExactSnapshotLoadSgf() {
      return exactSnapshotLoadSgf;
    }

    private boolean isTrackedLoadSgf() {
      return isTrackedExactSnapshotRestoreCommand(command) && queuedCommand.isTrackedLoadSgf();
    }

    private boolean isOutstandingResponseRetired() {
      return queuedCommand.isOutstandingResponseRetired();
    }

    private void requireMatchingResponseCommandId() {
      requiresMatchingResponseCommandId = true;
    }

    private void run() {
      queuedCommand.publishStateResetAfterOutputWrite();
      try {
        handler.run();
      } finally {
        queuedCommand.publishResponseSettlement();
      }
    }

    private void settleWithoutBusinessCallback() {
      queuedCommand.publishStateResetAfterOutputWrite();
      queuedCommand.publishResponseSettlement();
    }
  }

  /** Marker tying a genmove response to the game transaction that physically issued it. */
  private static final class EngineGameResponseHandler implements Runnable {
    private static final int RESERVED = 0;
    private static final int WRITE_CLAIMED = 1;
    private static final int SETTLED = 2;

    private final Leelaz owner;
    private final EngineManager.EngineGameMoveResponseContext context;
    private final ReaderStreamBinding binding;
    private final AtomicInteger state = new AtomicInteger(RESERVED);
    private final AtomicReference<EngineManager.EngineGamePhysicalRequestLease> physicalLease =
        new AtomicReference<>();
    private volatile boolean awaitingPassingCoordinate;

    private EngineGameResponseHandler(
        Leelaz owner,
        EngineManager.EngineGameMoveResponseContext context,
        ReaderStreamBinding binding) {
      this.owner = owner;
      this.context = context;
      this.binding = binding;
    }

    private boolean claimPhysicalWrite() {
      if (!state.compareAndSet(RESERVED, WRITE_CLAIMED)) {
        return false;
      }
      AtomicBoolean installed = new AtomicBoolean();
      EngineManager.EngineGamePhysicalRequestLease lease =
          EngineManager.claimEngineGameMoveOutput(
              context,
              () -> {
                while (true) {
                  EngineGameResponseHandler active =
                      owner.activeEngineGameResponseHandler.get();
                  if (active != null
                      && active.state.get() != SETTLED
                      && active.binding == binding) {
                    return;
                  }
                  if (owner.activeEngineGameResponseHandler.compareAndSet(active, this)) {
                    installed.set(true);
                    return;
                  }
                }
              });
      if (lease != null && installed.get()) {
        physicalLease.set(lease);
        if (state.get() == SETTLED) {
          EngineManager.EngineGamePhysicalRequestLease retired = physicalLease.getAndSet(null);
          if (retired != null) {
            retired.close();
          }
          return false;
        }
        return true;
      }
      if (lease != null) {
        lease.close();
      }
      state.set(SETTLED);
      return false;
    }

    private boolean isActiveFor(ReaderStreamBinding sourceBinding) {
      return state.get() == WRITE_CLAIMED && binding == sourceBinding;
    }

    private boolean isPhysicalWriteClaimed() {
      return state.get() == WRITE_CLAIMED;
    }

    private boolean belongsTo(EngineManager.EngineGameTransaction transaction) {
      return context.transaction == transaction;
    }

    /** A RESERVED request has emitted no bytes and can be retired without poisoning the stream. */
    private boolean cancelBeforePhysicalWrite(
        EngineManager.EngineGameTransaction transaction) {
      return belongsTo(transaction) && state.compareAndSet(RESERVED, SETTLED);
    }

    private void failBeforePhysicalWrite(RuntimeException failure) {
      if (state.compareAndSet(RESERVED, SETTLED)) {
        EngineManager.failEngineGameTransaction(context.transaction, failure);
      }
    }

    private void settle() {
      state.set(SETTLED);
      owner.activeEngineGameResponseHandler.compareAndSet(this, null);
      EngineManager.EngineGamePhysicalRequestLease lease = physicalLease.getAndSet(null);
      if (lease != null) {
        lease.close();
      }
    }

    private void markSettledForBindingRetirement() {
      settle();
    }

    private void failRetiredBinding(RuntimeException failure) {
      EngineManager.failEngineGameTransaction(context.transaction, failure);
    }

    @Override
    public void run() {
      try {
        if (owner.currentCommandResponseError
            && EngineManager.isCurrentEngineGameMoveResponse(context)) {
          EngineManager.failEngineGameTransaction(
              context.transaction,
              new IllegalStateException(
                  "Engine-game genmove command failed: " + owner.currentCommandResponseLine));
        }
      } finally {
        settle();
      }
    }
  }

  private static final class ForegroundRestoreCapture {
    private final ExactSnapshotEngineRestore.PreparedRestore preparedRestore;
    private final ExactSnapshotRestoreAdmission admission;
    private final Board board;
    private final Leelaz mirror;
    private final ArrayList<Movelist> rootMoves;
    private final Double rootKomi;
    private final int boardWidth;
    private final int boardHeight;
    private final EngineManager.BoardFrame frame;

    private ForegroundRestoreCapture(
        ExactSnapshotEngineRestore.PreparedRestore preparedRestore,
        ExactSnapshotRestoreAdmission admission,
        Board board,
        Leelaz mirror,
        ArrayList<Movelist> rootMoves,
        Double rootKomi,
        int boardWidth,
        int boardHeight,
        EngineManager.BoardFrame frame) {
      this.preparedRestore = preparedRestore;
      this.admission = admission;
      this.board = board;
      this.mirror = mirror;
      this.rootMoves = Movelist.copyList(rootMoves);
      this.rootKomi = rootKomi;
      this.boardWidth = boardWidth;
      this.boardHeight = boardHeight;
      this.frame = frame;
    }

    private static ForegroundRestoreCapture empty() {
      return new ForegroundRestoreCapture(
          null,
          null,
          null,
          null,
          new ArrayList<>(),
          null,
          Board.boardWidth,
          Board.boardHeight,
          EngineManager.BoardFrame.capture(null));
    }

    private void execute(Leelaz engine) {
      if (board == null || admission == null) {
        return;
      }
      if (preparedRestore != null) {
        board.resendMoveToEngine(engine, false, preparedRestore);
        return;
      }
      Runnable replay =
          () -> {
            synchronizeBoardSize(engine);
            if (mirror != null) {
              synchronizeBoardSize(mirror);
            }
            board.resendMoveToEngineFromRoot(
                engine, mirror, false, false, rootMoves, rootKomi);
          };
      engine.withExactSnapshotRestoreAdmission(
          admission,
          () -> {
            if (mirror == null) {
              replay.run();
            } else {
              mirror.withExactSnapshotRestoreAdmission(admission, replay);
            }
          });
    }

    private void synchronizeBoardSize(Leelaz engine) {
      String command =
          boardWidth == boardHeight
              ? "boardsize " + boardWidth
              : "rectangular_boardsize " + boardWidth + " " + boardHeight;
      engine.sendCapturedRestoreCommand(command);
      engine.width = boardWidth;
      engine.height = boardHeight;
    }
  }

  private static final class ExclusiveGtpSession {
    private final Object owner;
    private final Consumer<String> lineConsumer;
    private final Runnable onReady;
    private final Runnable onClosed;
    private final int stopCommandId;
    private final ExclusiveGtpReleasePolicy releasePolicy;
    private final ReaderStreamBinding readerBinding;
    private volatile boolean active;
    private boolean initialStopAcknowledged;
    private Timer initialStopTimeout;
    private boolean wasPondering;
    private volatile boolean closing;
    private volatile boolean releaseRequested;
    private volatile TrackingWriteState trackingInitialWriteState = TrackingWriteState.UNSENT;
    private String initialStopErrorResponse;
    private boolean initialStopTerminated;
    private volatile TrackingWriteState trackingActiveWriteState = TrackingWriteState.UNSENT;
    private volatile boolean trackingAnalyzeClosed;
    private volatile int releaseStopCommandId;
    private volatile TrackingWriteState trackingFinalWriteState = TrackingWriteState.UNSENT;
    private volatile boolean releaseStopAcknowledged;
    private String releaseStopErrorResponse;
    private boolean releaseStopTerminated;
    private boolean releaseStopFailed;
    private volatile boolean restoreCompleted;
    private boolean restoreFailed;
    private volatile boolean restoreInvalidated;
    private boolean restoreStarted;
    private ForegroundRestoreCapture restoreCapture;
    private String originalRules;
    private Runnable afterRestore;
    private Runnable afterRestoreFailure;
    private Thread restoreThread;
    private Timer releaseStopTimeout;
    private Timer restoreTimeout;
    private boolean closedCallbackRun;
    private TrackingHandoffClaim trackingHandoffClaim;

    private TrackingStreamLeaseFailure trackingLeaseFailureReason() {
      if (!(owner instanceof TrackingStreamLease)) {
        return null;
      }
      return ((TrackingStreamLease) owner).failureReason.get();
    }

    private ExclusiveGtpSession(
        Object owner,
        Consumer<String> lineConsumer,
        Runnable onReady,
        Runnable onClosed,
        int stopCommandId,
        ExclusiveGtpReleasePolicy releasePolicy,
        ReaderStreamBinding readerBinding) {
      this.owner = owner;
      this.lineConsumer = lineConsumer;
      this.onReady = onReady;
      this.onClosed = onClosed;
      this.stopCommandId = stopCommandId;
      this.releasePolicy = releasePolicy;
      this.readerBinding = readerBinding;
    }
  }

  private static final class TrackingDispositionNotification {
    private final TrackingReleaseDispositionObserver observer;
    private final TrackingReleaseDisposition disposition;
    private final TrackingReleaseReason reason;

    private TrackingDispositionNotification(
        TrackingReleaseDispositionObserver observer,
        TrackingReleaseDisposition disposition,
        TrackingReleaseReason reason) {
      this.observer = observer;
      this.disposition = disposition;
      this.reason = reason;
    }
  }

  private static final class TrackingHandoffFailureNotification {
    private final TrackingHandoffTarget target;
    private final TrackingHandoffFailure failure;

    private TrackingHandoffFailureNotification(
        TrackingHandoffTarget target, TrackingHandoffFailure failure) {
      this.target = target;
      this.failure = failure;
    }
  }

  private static final class TrackingHandoffFailureSettlement {
    private static final TrackingHandoffFailureSettlement NOT_WON =
        new TrackingHandoffFailureSettlement(false, null);
    private static final TrackingHandoffFailureSettlement WON_DEFERRED =
        new TrackingHandoffFailureSettlement(true, null);

    private final boolean won;
    private final TrackingHandoffFailureNotification notification;

    private TrackingHandoffFailureSettlement(
        boolean won, TrackingHandoffFailureNotification notification) {
      this.won = won;
      this.notification = notification;
    }
  }

  public static final class TrackingStreamLeaseReceipt {
    private final Leelaz engine;
    private final long engineIncarnation;
    private final boolean wasPondering;

    private TrackingStreamLeaseReceipt(
        Leelaz engine, long engineIncarnation, boolean wasPondering) {
      this.engine = engine;
      this.engineIncarnation = engineIncarnation;
      this.wasPondering = wasPondering;
    }

    public Leelaz engine() {
      return engine;
    }

    public long engineIncarnation() {
      return engineIncarnation;
    }

    public boolean wasPondering() {
      return wasPondering;
    }
  }

  public static final class TrackingStreamLeaseAcquisition {
    private final ExclusiveGtpLeaseAvailability availability;
    private final TrackingStreamLease lease;
    private final TrackingStreamLeaseReceipt receipt;
    private final TrackingStreamLease failureSource;

    private TrackingStreamLeaseAcquisition(
        ExclusiveGtpLeaseAvailability availability,
        TrackingStreamLease lease,
        TrackingStreamLeaseReceipt receipt,
        TrackingStreamLease failureSource) {
      this.availability = availability;
      this.lease = lease;
      this.receipt = receipt;
      this.failureSource = failureSource;
    }

    public ExclusiveGtpLeaseAvailability availability() {
      return availability;
    }

    public TrackingStreamLease lease() {
      return lease;
    }

    public TrackingStreamLeaseReceipt receipt() {
      return receipt;
    }

    public Optional<TrackingStreamLeaseFailure> failureReason() {
      return failureSource == null ? Optional.empty() : failureSource.failureReason();
    }
  }

  public static final class TrackingStreamLease {
    private final Leelaz engine;
    private final TrackingStreamLeaseReceipt receipt;
    private final TrackingReleaseDispositionObserver dispositionObserver;
    private final AtomicReference<TrackingReleaseDisposition> disposition =
        new AtomicReference<>(TrackingReleaseDisposition.ACTIVE);
    private final AtomicReference<TrackingStreamLeaseFailure> failureReason =
        new AtomicReference<>();

    private TrackingStreamLease(
        Leelaz engine,
        TrackingStreamLeaseReceipt receipt,
        TrackingReleaseDispositionObserver dispositionObserver) {
      this.engine = engine;
      this.receipt = receipt;
      this.dispositionObserver = dispositionObserver;
    }

    public TrackingStreamLeaseReceipt receipt() {
      return receipt;
    }

    public boolean isOwned() {
      return engine.hasExclusiveGtpLeaseOwnedBy(this);
    }

    public boolean send(String command) {
      return engine.sendTrackingStreamCommand(this, command);
    }

    public boolean release() {
      return engine.endTrackingStreamLease(this);
    }

    public Optional<TrackingStreamLeaseFailure> failureReason() {
      return Optional.ofNullable(failureReason.get());
    }

    public TrackingReleaseDisposition disposition() {
      return disposition.get();
    }

    private boolean advanceDisposition(TrackingReleaseDisposition next) {
      TrackingReleaseDisposition current;
      do {
        current = disposition.get();
        if (current.ordinal() >= next.ordinal()) {
          return false;
        }
      } while (!disposition.compareAndSet(current, next));
      return true;
    }

    private void recordFailure(TrackingStreamLeaseFailure failure) {
      failureReason.compareAndSet(null, failure);
    }
  }

  public static final class TrackingHandoffClaim {
    private final Leelaz engine;
    private final TrackingHandoffTarget target;
    private final TrackingHandoffKind kind;
    private final boolean wasPondering;
    private final TrackingHandoffAvailability availability;
    private final AtomicReference<TrackingHandoffState> state;
    private boolean activationCallbackInProgress;
    private TrackingHandoffFailure deferredFailure;

    private TrackingHandoffClaim(
        Leelaz engine,
        TrackingHandoffTarget target,
        TrackingHandoffKind kind,
        boolean wasPondering) {
      this.engine = engine;
      this.target = target;
      this.kind = kind;
      this.wasPondering = wasPondering;
      this.availability = TrackingHandoffAvailability.ACCEPTED_PENDING;
      this.state = new AtomicReference<>(TrackingHandoffState.ACCEPTED_PENDING);
    }

    private TrackingHandoffClaim(
        Leelaz engine, TrackingHandoffTarget target, TrackingHandoffAvailability availability) {
      this.engine = engine;
      this.target = target;
      this.kind = null;
      this.wasPondering = false;
      this.availability = availability;
      this.state = new AtomicReference<>(TrackingHandoffState.FAILED);
    }

    private static TrackingHandoffClaim rejected(
        Leelaz engine, TrackingHandoffTarget target, TrackingHandoffAvailability availability) {
      return new TrackingHandoffClaim(engine, target, availability);
    }

    public TrackingHandoffAvailability availability() {
      return availability;
    }

    public TrackingHandoffState state() {
      return state.get();
    }

    public boolean cancel() {
      if (availability != TrackingHandoffAvailability.ACCEPTED_PENDING) {
        return false;
      }
      TrackingHandoffState current = state.get();
      if (current != TrackingHandoffState.ACCEPTED_PENDING
          && current != TrackingHandoffState.ACTIVATING) {
        return false;
      }
      return engine.failTrackingHandoff(this, TrackingHandoffFailure.TARGET_CANCELLED);
    }
  }

  public static final class ForegroundAnalysisLeaseAcquisition {
    private final ExclusiveGtpLeaseAvailability availability;
    private final ForegroundAnalysisLease lease;
    private final ForegroundAnalysisLease failureSource;

    private ForegroundAnalysisLeaseAcquisition(
        ExclusiveGtpLeaseAvailability availability,
        ForegroundAnalysisLease lease,
        ForegroundAnalysisLease failureSource) {
      this.availability = availability;
      this.lease = lease;
      this.failureSource = failureSource;
    }

    public ExclusiveGtpLeaseAvailability availability() {
      return availability;
    }

    public ForegroundAnalysisLease lease() {
      return lease;
    }

    public Optional<ForegroundAnalysisLeaseFailure> failureReason() {
      return failureSource.failureReason();
    }
  }

  public static final class ForegroundAnalysisLease {
    private final Leelaz engine;
    private final AtomicReference<ForegroundAnalysisLeaseFailure> failureReason =
        new AtomicReference<>();

    private ForegroundAnalysisLease(Leelaz engine) {
      this.engine = engine;
    }

    public boolean isOwned() {
      return engine.hasExclusiveGtpLeaseOwnedBy(this);
    }

    public boolean setRestoreRules(String rules) {
      return engine.setForegroundAnalysisLeaseRestoreRules(this, rules);
    }

    public boolean release(Runnable afterRestore, Runnable afterRestoreFailure) {
      return engine.endForegroundAnalysisLease(this, afterRestore, afterRestoreFailure);
    }

    public Optional<ForegroundAnalysisLeaseFailure> failureReason() {
      return Optional.ofNullable(failureReason.get());
    }

    private void recordFailure(ForegroundAnalysisLeaseFailure failure) {
      failureReason.compareAndSet(null, failure);
    }
  }

  static final class ExactSnapshotRestoreAdmissionException extends IllegalStateException {
    private ExactSnapshotRestoreAdmissionException(String message) {
      super(message);
    }
  }

  public static class EngineModeReservation implements AutoCloseable {
    private Leelaz engine;
    private final Object owner;

    private EngineModeReservation(Leelaz engine, Object owner) {
      this.engine = engine;
      this.owner = owner;
    }

    Object lifecycleOwnerFor(Leelaz expectedEngine) {
      synchronized (this) {
        return engine == expectedEngine ? owner : null;
      }
    }

    @Override
    public void close() {
      Leelaz reservedEngine;
      synchronized (this) {
        reservedEngine = engine;
        engine = null;
      }
      if (reservedEngine != null) {
        reservedEngine.endExclusiveGtpLifecycleTransition(owner);
      }
    }
  }

  public static final class ExactSnapshotRestoreAdmission {
    private final Leelaz authority;
    private final Leelaz mirror;
    private final ExactSnapshotRestoreOwner owner;
    private final Object ownerIdentity;
    private final Object authorityIncarnation;
    private final Object mirrorIncarnation;
    private final LifecycleCompletionClaim.BoardSyncCompletionLease boardSyncLease;
    private final long primaryEngineGeneration;

    private ExactSnapshotRestoreAdmission(
        Leelaz authority,
        Leelaz mirror,
        ExactSnapshotRestoreOwner owner,
        Object ownerIdentity,
        Object authorityIncarnation,
        Object mirrorIncarnation,
        LifecycleCompletionClaim.BoardSyncCompletionLease boardSyncLease,
        long primaryEngineGeneration) {
      this.authority = authority;
      this.mirror = mirror;
      this.owner = owner;
      this.ownerIdentity = ownerIdentity;
      this.authorityIncarnation = authorityIncarnation;
      this.mirrorIncarnation = mirrorIncarnation;
      this.boardSyncLease = boardSyncLease;
      this.primaryEngineGeneration = primaryEngineGeneration;
    }

    Leelaz authority() {
      return authority;
    }

    Leelaz mirror() {
      return mirror;
    }

    boolean preclear() {
      return owner.preclear();
    }

    private boolean includes(Leelaz engine) {
      return engine == authority || engine == mirror;
    }
    private boolean runIfCurrentBoardSyncPrimary(Runnable action) {
      if (owner != ExactSnapshotRestoreOwner.BOARD_SYNC || primaryEngineGeneration < 0) {
        action.run();
        return true;
      }
      return Lizzie.runIfPrimaryEngine(authority, primaryEngineGeneration, action);
    }

    void completeBoardSync() {
      if (boardSyncLease != null) {
        boardSyncLease.close();
      }
    }
  }

  public static final class AutomaticRestartAttempt implements AutoCloseable {
    private AutomaticRestartOperation operation;

    private AutomaticRestartAttempt(AutomaticRestartOperation operation) {
      this.operation = operation;
    }

    public void restartClosedEngine(int index) throws IOException {
      restartClosedEngine(index, null);
    }

    public void restartClosedEngine(int index, Runnable afterBoardRestore) throws IOException {
      restartClosedEngine(index, afterBoardRestore, null);
    }

    void restartClosedEngine(
        int index, Runnable afterBoardRestore, Consumer<String> onFailure) throws IOException {
      AutomaticRestartOperation ownedOperation;
      synchronized (this) {
        ownedOperation = operation;
        operation = null;
      }
      if (ownedOperation == null) {
        throw new IllegalStateException("Automatic restart attempt has already been consumed");
      }
      ownedOperation.start(index, afterBoardRestore, onFailure);
    }

    @Override
    public void close() {
      AutomaticRestartOperation ownedOperation;
      synchronized (this) {
        ownedOperation = operation;
        operation = null;
      }
      if (ownedOperation != null) {
        ownedOperation.abandon();
      }
    }
  }

  private enum AutomaticRestartState {
    RESERVED,
    STARTING,
    CONVERGING,
    FENCE_PENDING,
    COMPLETED,
    FAILED,
    ABANDONED
  }

  private static final class AutomaticRestartOwner {
    private final boolean allowUnrestoredState;

    private AutomaticRestartOwner(boolean allowUnrestoredState) {
      this.allowUnrestoredState = allowUnrestoredState;
    }
  }

  /** One immutable automatic-restart restore round: route, admission and Board frame move together. */
  private static final class AutomaticRestartRound {
    private final Leelaz target;
    private final Leelaz mirror;
    private final Board board;
    private final Object owner;
    private final ExactSnapshotRestoreAdmission admission;
    private final ExactSnapshotEngineRestore.PreparedRestore preparedRestore;
    private final ArrayList<Movelist> rootMoves;
    private final Double rootKomi;
    private final boolean resumePonder;
    private final EngineManager.BoardFrame capturedFrame;
    private final AtomicBoolean rootReplayExecuted = new AtomicBoolean(false);

    private AutomaticRestartRound(
        Leelaz target,
        Leelaz mirror,
        Board board,
        Object owner,
        ExactSnapshotRestoreAdmission admission,
        ExactSnapshotEngineRestore.PreparedRestore preparedRestore,
        ArrayList<Movelist> rootMoves,
        Double rootKomi,
        boolean resumePonder,
        EngineManager.BoardFrame capturedFrame) {
      this.target = target;
      this.mirror = mirror;
      this.board = board;
      this.owner = owner;
      this.admission = admission;
      this.preparedRestore = preparedRestore;
      this.rootMoves = Movelist.copyList(rootMoves);
      this.rootKomi = rootKomi;
      this.resumePonder = resumePonder;
      this.capturedFrame = capturedFrame;
    }

    private static AutomaticRestartRound capture(
        Leelaz target,
        Board board,
        Object owner,
        BoardHistoryNode historyTarget,
        Double komi,
        ArrayList<Movelist> rootMoves,
        boolean resumePonder) {
      return captureWithMirror(
          target,
          board,
          owner,
          target.resolveLoadSgfMirrorEngine(),
          historyTarget,
          komi,
          rootMoves,
          resumePonder);
    }

    private static AutomaticRestartRound captureWithMirror(
        Leelaz target,
        Board board,
        Object owner,
        Leelaz frozenMirror,
        BoardHistoryNode historyTarget,
        Double komi,
        ArrayList<Movelist> rootMoves,
        boolean resumePonder) {
      Leelaz effectiveMirror = frozenMirror == target ? null : frozenMirror;
      ExactSnapshotRestoreAdmission admission =
          target.captureExactSnapshotRestoreAdmission(
              ExactSnapshotRestoreOwner.LIFECYCLE, owner, effectiveMirror);
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore = null;
      if (historyTarget != null) {
        preparedRestore =
            ExactSnapshotEngineRestore.prepare(admission, historyTarget).orElse(null);
      }
      return new AutomaticRestartRound(
          target,
          effectiveMirror,
          board,
          owner,
          admission,
          preparedRestore,
          rootMoves,
          komi,
          resumePonder,
          EngineManager.BoardFrame.capture(board));
    }

    private Object owner() {
      return owner;
    }

    private Leelaz mirror() {
      return mirror;
    }

    private boolean resumePonder() {
      return resumePonder;
    }

    private void execute() {
      reconcileCapturedBoardSize();
      if (preparedRestore != null) {
        board.resendMoveToEngine(target, false, preparedRestore);
      } else if (board != null) {
        executeRootReplay();
      }
    }

    private void reconcileCapturedBoardSize() {
      int boardWidth = capturedFrame.boardWidth();
      int boardHeight = capturedFrame.boardHeight();
      Runnable reconcile =
          () -> {
            reconcileEngineBoardSize(target, boardWidth, boardHeight);
            if (mirror != null) {
              reconcileEngineBoardSize(mirror, boardWidth, boardHeight);
            }
          };
      target.withExactSnapshotRestoreAdmission(
          admission,
          () -> {
            if (mirror == null) {
              reconcile.run();
            } else {
              mirror.withExactSnapshotRestoreAdmission(admission, reconcile);
            }
          });
    }

    private static void reconcileEngineBoardSize(Leelaz engine, int boardWidth, int boardHeight) {
      if (engine.width == boardWidth && engine.height == boardHeight) {
        return;
      }
      String command =
          boardWidth == boardHeight
              ? "boardsize " + boardWidth
              : "rectangular_boardsize " + boardWidth + " " + boardHeight;
      engine.sendCapturedRestoreCommand(command);
      engine.width = boardWidth;
      engine.height = boardHeight;
    }

    private void executeRootReplay() {
      if (!rootReplayExecuted.compareAndSet(false, true)) {
        throw new IllegalStateException("Restart root replay has already been executed");
      }
      target.requireExactSnapshotRestoreAdmission(admission);
      if (mirror != null) {
        mirror.requireExactSnapshotRestoreAdmission(admission);
      }
      Runnable replay =
          () -> board.resendMoveToEngineFromRoot(target, mirror, false, false, rootMoves, rootKomi);
      target.withExactSnapshotRestoreAdmission(
          admission,
          () -> {
            if (mirror == null) {
              replay.run();
            } else {
              mirror.withExactSnapshotRestoreAdmission(admission, replay);
            }
          });
    }
  }

  /** Owner-local automatic restart operation from frozen capture through final fence settlement. */
  private final class AutomaticRestartOperation {
    private final Board board;
    private final Object owner;
    private final Leelaz frozenMirror;
    private final boolean resumePonder;
    private final boolean preserveUnrestoredState;
    private final LifecycleCompletionClaim completionClaim;
    private final AtomicReference<AutomaticRestartState> state =
        new AtomicReference<>(AutomaticRestartState.RESERVED);
    private final AtomicBoolean barriersEnded = new AtomicBoolean(false);
    private final AtomicBoolean completionNotified = new AtomicBoolean(false);
    private AutomaticRestartRound pendingRound;
    private ExclusiveGtpLifecycleReservation roundReservation;
    private Runnable afterBoardRestore;
    private Consumer<String> failureHandler;

    private AutomaticRestartOperation(
        AutomaticRestartRound frozenRound,
        ExclusiveGtpLifecycleReservation initialReservation,
        LifecycleCompletionClaim completionClaim,
        boolean preserveUnrestoredState) {
      this.board = frozenRound.board;
      this.owner = frozenRound.owner();
      this.frozenMirror = frozenRound.mirror();
      this.resumePonder = frozenRound.resumePonder();
      this.pendingRound = frozenRound;
      this.roundReservation = initialReservation;
      this.completionClaim = completionClaim;
      this.preserveUnrestoredState = preserveUnrestoredState;
    }

    private void beginBoardSynchronization() {
      beginInitialBoardSynchronization();
      if (frozenMirror != null) {
        frozenMirror.beginInitialBoardSynchronization();
      }
    }

    private void start(int index, Runnable completion, Consumer<String> onFailure)
        throws IOException {
      if (!state.compareAndSet(AutomaticRestartState.RESERVED, AutomaticRestartState.STARTING)) {
        throw new IllegalStateException("Automatic restart attempt has already been settled");
      }
      afterBoardRestore = completion;
      failureHandler = onFailure;
      try {
        if (useRemoteCompute && isStarted()) {
          normalQuit();
        }
        isPondering = false;
        isLoaded = false;
        canCheckAlive = false;
        startEngine(index);
        Thread synchronization =
            new Thread(
                withCurrentRestartBootstrapReceipt(this::awaitReadinessAndConverge),
                "lizzie-automatic-engine-restart");
        synchronization.start();
      } catch (IOException | RuntimeException | Error failure) {
        Throwable cleanupFailure =
            failBeforeFence(
                safeFailureDetail(failure, "automatic engine restart failed"));
        addSuppressedFailure(failure, cleanupFailure);
        throw failure;
      }
    }

    private void awaitReadinessAndConverge() {
      if (!waitForAutomaticRestartReadiness()) {
        reportAutomaticRestartSettlementFailure(
            "Automatic restart readiness failure cleanup failed",
            failBeforeFence("automatic engine restart did not become ready"));
        return;
      }
      if (!state.compareAndSet(AutomaticRestartState.STARTING, AutomaticRestartState.CONVERGING)) {
        return;
      }
      try {
        converge();
      } catch (RuntimeException | Error failure) {
        Throwable cleanupFailure =
            failBeforeFence(
                safeFailureDetail(
                    failure, "automatic engine board restore failed"));
        addSuppressedFailure(failure, cleanupFailure);
        reportAutomaticRestartSettlementFailure(
            "Automatic restart convergence failed", failure);
        return;
      }
      if (KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed()) {
        completeWithoutFence();
        return;
      }
      if (!state.compareAndSet(
          AutomaticRestartState.CONVERGING, AutomaticRestartState.FENCE_PENDING)) {
        return;
      }
      completionClaim.confirmFinalBoardSynchronization(
          this::completeAfterFence, this::failAfterFence);
    }

    private void converge() {
      try {
        if (board == null) {
          pendingRound.execute();
          releaseRoundReservation();
          endBoardSynchronization();
          return;
        }
        while (true) {
          if (Lizzie.board != board) {
            throw new IllegalStateException("Automatic restart Board changed during convergence");
          }
          pendingRound.execute();
          releaseRoundReservation();
          boolean stable;
          synchronized (board) {
            EngineManager.BoardFrame currentFrame = EngineManager.BoardFrame.capture(board);
            stable = pendingRound.capturedFrame.matches(currentFrame);
            if (stable) {
              endBoardSynchronization();
            } else {
              pendingRound = captureCatchUpRound();
            }
          }
          if (stable) {
            return;
          }
          acquireRoundReservation();
        }
      } finally {
        releaseRoundReservation();
      }
    }

    private AutomaticRestartRound captureCatchUpRound() {
      BoardHistoryList history = board.getHistory();
      BoardHistoryNode historyTarget = history == null ? null : history.getCurrentHistoryNode();
      Double komi =
          history == null || history.getGameInfo() == null
              ? null
              : history.getGameInfo().getKomi();
      return AutomaticRestartRound.captureWithMirror(
          Leelaz.this,
          board,
          owner,
          frozenMirror,
          historyTarget,
          komi,
          Movelist.copyList(board.getMoveList()),
          resumePonder);
    }

    private void acquireRoundReservation() {
      ExclusiveGtpLifecycleReservation reservation = beginExclusiveGtpLifecycleReservation(owner);
      if (reservation == null) {
        throw new IllegalStateException("Automatic restart round reservation was rejected");
      }
      roundReservation = reservation;
    }

    private void releaseRoundReservation() {
      ExclusiveGtpLifecycleReservation reservation = roundReservation;
      roundReservation = null;
      if (reservation != null) {
        reservation.close();
      }
    }

    private void completeAfterFence() {
      if (state.get() != AutomaticRestartState.FENCE_PENDING) {
        return;
      }
      if (engineStateUnrestored) {
        completeReadBoardGmaRecoveryAfterBoardSync();
      }
      resumeClosedEngineAfterBoardSynchronization(resumePonder);
      if (state.compareAndSet(
          AutomaticRestartState.FENCE_PENDING, AutomaticRestartState.COMPLETED)) {
        notifyCompletionAfterEndpointRelease();
      }
    }

    private void completeWithoutFence() {
      if (!state.compareAndSet(AutomaticRestartState.CONVERGING, AutomaticRestartState.COMPLETED)) {
        return;
      }
      Throwable settlementFailure = null;
      try {
        notifyCompletionAfterEndpointRelease();
      } catch (RuntimeException | Error failure) {
        settlementFailure = failure;
      } finally {
        try {
          completionClaim.abandonBeforeFence();
        } catch (RuntimeException | Error failure) {
          settlementFailure = combineFailures(settlementFailure, failure);
        }
      }
      reportAutomaticRestartSettlementFailure(
          "Automatic restart no-fence completion cleanup failed", settlementFailure);
    }

    private void failAfterFence(String detail) {
      if (!state.compareAndSet(AutomaticRestartState.FENCE_PENDING, AutomaticRestartState.FAILED)) {
        return;
      }
      Throwable settlementFailure = null;
      try {
        failCapturedEndpointsClosed();
        markLifecycleBoardSynchronizationFailed(
            detail, preserveUnrestoredState || engineStateUnrestored);
      } catch (RuntimeException | Error failure) {
        settlementFailure = failure;
      } finally {
        try {
          endBoardSynchronization();
        } catch (RuntimeException | Error failure) {
          settlementFailure = combineFailures(settlementFailure, failure);
        } finally {
          try {
            completionClaim.runAfterEndpointRelease(
                () -> {
                  notifyFailure(detail);
                  notifyCompletion();
                });
          } catch (RuntimeException | Error failure) {
            settlementFailure = combineFailures(settlementFailure, failure);
          }
        }
      }
      reportAutomaticRestartSettlementFailure(
          "Automatic restart final-fence failure cleanup failed", settlementFailure);
    }

    private Throwable failBeforeFence(String detail) {
      AutomaticRestartState current = state.getAndSet(AutomaticRestartState.FAILED);
      if (current == AutomaticRestartState.COMPLETED
          || current == AutomaticRestartState.FAILED
          || current == AutomaticRestartState.ABANDONED) {
        return null;
      }
      Throwable settlementFailure = null;
      try {
        failCapturedEndpointsClosed();
        markLifecycleBoardSynchronizationFailed(
            detail, preserveUnrestoredState || engineStateUnrestored);
      } catch (RuntimeException | Error failure) {
        settlementFailure = failure;
      }
      settlementFailure =
          combineFailures(settlementFailure, releaseBeforeFenceResources());
      notifyFailure(detail);
      notifyCompletion();
      return settlementFailure;
    }

    private void failCapturedEndpointsClosed() {
      isLoaded = false;
      if (frozenMirror != null) {
        frozenMirror.isLoaded = false;
      }
    }

    private void abandon() {
      if (!state.compareAndSet(AutomaticRestartState.RESERVED, AutomaticRestartState.ABANDONED)) {
        return;
      }
      rethrowAutomaticRestartCleanupFailure(releaseBeforeFenceResources());
    }

    /** Releases every pre-fence owner even when queue draining or barrier teardown fails. */
    private Throwable releaseBeforeFenceResources() {
      Throwable cleanupFailure = null;
      try {
        releaseRoundReservation();
      } catch (RuntimeException | Error failure) {
        cleanupFailure = failure;
      } finally {
        try {
          endBoardSynchronization();
        } catch (RuntimeException | Error failure) {
          cleanupFailure = combineFailures(cleanupFailure, failure);
        } finally {
          try {
            completionClaim.abandonBeforeFence();
          } catch (RuntimeException | Error failure) {
            cleanupFailure = combineFailures(cleanupFailure, failure);
          }
        }
      }
      return cleanupFailure;
    }

    private void endBoardSynchronization() {
      if (!barriersEnded.compareAndSet(false, true)) {
        return;
      }
      try {
        endInitialBoardSynchronization();
      } finally {
        if (frozenMirror != null) {
          frozenMirror.endInitialBoardSynchronization();
        }
      }
    }

    private void notifyFailure(String detail) {
      Consumer<String> handler = failureHandler;
      if (handler != null) {
        try {
          handler.accept(detail);
        } catch (RuntimeException | Error failure) {
          reportAutomaticRestartSettlementFailure(
              "Automatic restart failure observer failed", failure);
        }
      }
    }

    private void notifyCompletionAfterEndpointRelease() {
      // Public completion means both endpoints are fully reopened; owner-internal state changes
      // above still execute while the lifecycle claim excludes unrelated engine work.
      completionClaim.runAfterEndpointRelease(this::notifyCompletion);
    }

    private void notifyCompletion() {
      Runnable completion = afterBoardRestore;
      if (completion != null && completionNotified.compareAndSet(false, true)) {
        try {
          completion.run();
        } catch (RuntimeException | Error failure) {
          reportAutomaticRestartSettlementFailure(
              "Automatic restart completion callback failed", failure);
        }
      }
    }

    private Throwable combineFailures(Throwable primary, Throwable secondary) {
      if (secondary == null) {
        return primary;
      }
      if (primary == null) {
        return secondary;
      }
      addSuppressedFailure(primary, secondary);
      return primary;
    }

    private void addSuppressedFailure(Throwable primary, Throwable secondary) {
      if (primary != null && secondary != null && primary != secondary) {
        primary.addSuppressed(secondary);
      }
    }

    private void rethrowAutomaticRestartCleanupFailure(Throwable failure) {
      if (failure instanceof RuntimeException) {
        throw (RuntimeException) failure;
      }
      if (failure instanceof Error) {
        throw (Error) failure;
      }
    }

    private void reportAutomaticRestartSettlementFailure(String context, Throwable failure) {
      if (failure == null) {
        return;
      }
      String detail = safeFailureDetail(failure, failure.getClass().getSimpleName());
      String message = context + ": " + detail;
      try {
        rememberRecentLine(recentStderrLines, message);
      } catch (RuntimeException | Error ignored) {
        // Diagnostics must not reopen an already settled lifecycle owner.
      }
      System.err.println(message);
    }
  }

  /**
   * Owner-identified lifecycle completion claim installed atomically on an authority and its frozen
   * mirror. It owns final dual-leg fencing, releases both endpoints with identity-safe
   * compare-and-clear, then notifies the external completion observer.
   */
  static final class LifecycleCompletionClaim {
    private final Leelaz authority;
    private final Leelaz capturedMirror;
    private final Object owner;
    private final AtomicBoolean fenceStarted = new AtomicBoolean(false);
    private final AtomicBoolean settled = new AtomicBoolean(false);
    private int pendingBoardSyncCount;
    private boolean fenceSuccessPending;
    private Runnable deferredSuccess;
    private Consumer<String> deferredFailure;
    private final AtomicBoolean endpointsReleased = new AtomicBoolean(false);
    private final List<Runnable> afterEndpointRelease = new ArrayList<>();

    private LifecycleCompletionClaim(Leelaz authority, Object owner, Leelaz capturedMirror) {
      this.authority = authority;
      this.owner = owner;
      this.capturedMirror = capturedMirror == authority ? null : capturedMirror;
    }

    private static LifecycleCompletionClaim tryAcquire(
        Leelaz authority, Object owner, Leelaz capturedMirror) {
      if (authority == null) {
        throw new IllegalArgumentException("authority");
      }
      if (owner == null) {
        throw new IllegalArgumentException("owner");
      }
      LifecycleCompletionClaim claim =
          new LifecycleCompletionClaim(authority, owner, capturedMirror);
      Leelaz mirror = claim.capturedMirror;
      if (mirror == null) {
        synchronized (authority.engineArbitrationLock()) {
          return installLocked(claim) ? claim : null;
        }
      }
      return withOrderedEndpointLocks(
          authority, mirror, () -> installLocked(claim) ? claim : null);
    }

    private static <T> T withOrderedEndpointLocks(
        Leelaz authority, Leelaz mirror, Supplier<T> action) {
      return withOrderedEngineArbitrationLocks(authority, mirror, action);
    }

    private static boolean installLocked(LifecycleCompletionClaim claim) {
      if (claim.authority.lifecycleCompletionClaim != null
          || claim.authority.hasConflictingExactSnapshotRestoreWorkLocked(claim.owner)
          || (claim.capturedMirror != null
              && (claim.capturedMirror.lifecycleCompletionClaim != null
                  || claim.capturedMirror.hasConflictingExactSnapshotRestoreWorkLocked(
                      claim.owner)))) {
        return false;
      }
      claim.authority.lifecycleCompletionClaim = claim;
      if (claim.capturedMirror != null) {
        claim.capturedMirror.lifecycleCompletionClaim = claim;
      }
      return true;
    }

    Object owner() {
      return owner;
    }

    private void runAsCompletionFenceOwner(Runnable action) {
      LifecycleCompletionClaim previous = lifecycleCompletionCommandContext.get();
      lifecycleCompletionCommandContext.set(this);
      try {
        action.run();
      } finally {
        if (previous == null) {
          lifecycleCompletionCommandContext.remove();
        } else {
          lifecycleCompletionCommandContext.set(previous);
        }
      }
    }

    BoardSyncCompletionLease acquireBoardSyncLease() {
      synchronized (this) {
        if (settled.get()) {
          return null;
        }
        pendingBoardSyncCount++;
        return new BoardSyncCompletionLease(this);
      }
    }

    void confirmFinalBoardSynchronization(
        Runnable onSuccess, Consumer<String> onFailure) {
      startBoardSynchronizationAttempt(
          () -> completeSuccess(onSuccess, onFailure),
          detail -> completeFailure(detail, onFailure));
    }

    void startBoardSynchronizationAttempt(
        Runnable onSuccess, Consumer<String> onFailure) {
      if (!fenceStarted.compareAndSet(false, true)) {
        throw new IllegalStateException("Lifecycle completion fence has already started");
      }
      performBoardSynchronizationAttempt(onSuccess, onFailure);
    }

    void continueBoardSynchronizationAttempt(
        Runnable onSuccess, Consumer<String> onFailure) {
      if (!fenceStarted.get() || settled.get()) {
        throw new IllegalStateException("Lifecycle completion fence is not pending");
      }
      performBoardSynchronizationAttempt(onSuccess, onFailure);
    }

    private void performBoardSynchronizationAttempt(
        Runnable onSuccess, Consumer<String> onFailure) {
      try {
        Runnable ownedSuccess = () -> runAsCompletionFenceOwner(onSuccess);
        Consumer<String> ownedFailure =
            detail -> runAsCompletionFenceOwner(() -> onFailure.accept(detail));
        if (capturedMirror == null) {
          // Preserve the single-engine override seam; the production two-argument method delegates
          // to the paired implementation, while controlled/fake engines may provide their own
          // exact final-fence implementation here.
          authority.confirmBoardSynchronization(ownedSuccess, ownedFailure);
        } else {
          authority.confirmBoardSynchronization(capturedMirror, ownedSuccess, ownedFailure);
        }
      } catch (RuntimeException | Error failure) {
        runAsCompletionFenceOwner(
            () ->
                onFailure.accept(
                    safeFailureDetail(
                        failure, "lifecycle completion fence failed to start")));
      }
    }

    void completeSuccess(Runnable onSuccess, Consumer<String> onFailure) {
      settleSuccess(onSuccess, onFailure);
    }

    void completeFailure(String detail, Consumer<String> onFailure) {
      settleFailure(detail, onFailure);
    }

    void runAfterEndpointRelease(Runnable action) {
      if (action == null) {
        throw new IllegalArgumentException("action");
      }
      boolean runNow;
      synchronized (this) {
        runNow = endpointsReleased.get();
        if (!runNow) {
          afterEndpointRelease.add(action);
        }
      }
      if (runNow) {
        action.run();
      }
    }

    boolean abandonBeforeFence() {
      synchronized (this) {
        if (fenceStarted.get() || !settled.compareAndSet(false, true)) {
          return false;
        }
      }
      releaseEndpoints();
      return true;
    }

    private void settleSuccess(Runnable onSuccess, Consumer<String> onFailure) {
      synchronized (this) {
        if (settled.get() || fenceSuccessPending) {
          return;
        }
        if (pendingBoardSyncCount > 0) {
          fenceSuccessPending = true;
          deferredSuccess = onSuccess;
          deferredFailure = onFailure;
          return;
        }
        settled.set(true);
      }
      finishSuccess(onSuccess, onFailure);
    }

    private void settleFailure(String detail, Consumer<String> onFailure) {
      synchronized (this) {
        if (settled.get()) {
          return;
        }
        settled.set(true);
        fenceSuccessPending = false;
        deferredSuccess = null;
        deferredFailure = null;
      }
      try {
        if (onFailure != null) {
          onFailure.accept(detail);
        }
      } finally {
        releaseEndpoints();
      }
    }

    private void releaseBoardSyncLease() {
      Runnable success;
      Consumer<String> failure;
      synchronized (this) {
        if (pendingBoardSyncCount <= 0) {
          return;
        }
        pendingBoardSyncCount--;
        if (pendingBoardSyncCount != 0 || !fenceSuccessPending || settled.get()) {
          return;
        }
        settled.set(true);
        fenceSuccessPending = false;
        success = deferredSuccess;
        failure = deferredFailure;
        deferredSuccess = null;
        deferredFailure = null;
      }
      finishSuccess(success, failure);
    }

    private void finishSuccess(Runnable onSuccess, Consumer<String> onFailure) {
      try {
        if (onSuccess != null) {
          runAsCompletionFenceOwner(onSuccess);
        }
      } catch (RuntimeException | Error failure) {
        authority.isLoaded = false;
        if (onFailure != null) {
          runAsCompletionFenceOwner(
              () ->
                  onFailure.accept(
                      safeFailureDetail(
                          failure, "lifecycle completion callback failed")));
        } else {
          throw failure;
        }
      } finally {
        releaseEndpoints();
      }
    }

    private void releaseEndpoints() {
      Leelaz mirror = capturedMirror;
      if (mirror == null) {
        synchronized (authority.engineArbitrationLock()) {
          clearLocked();
        }
      } else {
        withOrderedEndpointLocks(
            authority,
            mirror,
            () -> {
              clearLocked();
              return null;
            });
      }
      notifyEndpointRelease();
    }

    private void notifyEndpointRelease() {
      if (!endpointsReleased.compareAndSet(false, true)) {
        return;
      }
      List<Runnable> callbacks;
      synchronized (this) {
        callbacks = new ArrayList<>(afterEndpointRelease);
        afterEndpointRelease.clear();
      }
      Throwable callbackFailure = null;
      for (Runnable callback : callbacks) {
        callbackFailure = runEngineCleanupStep(callbackFailure, callback);
      }
      rethrowEngineCleanupFailure(callbackFailure);
    }

    private void clearLocked() {
      if (authority.lifecycleCompletionClaim == this) {
        authority.lifecycleCompletionClaim = null;
      }
      if (capturedMirror != null && capturedMirror.lifecycleCompletionClaim == this) {
        capturedMirror.lifecycleCompletionClaim = null;
      }
    }

    static final class BoardSyncCompletionLease implements AutoCloseable {
      private LifecycleCompletionClaim claim;

      private BoardSyncCompletionLease(LifecycleCompletionClaim claim) {
        this.claim = claim;
      }

      @Override
      public void close() {
        LifecycleCompletionClaim ownedClaim;
        synchronized (this) {
          ownedClaim = claim;
          claim = null;
        }
        if (ownedClaim != null) {
          ownedClaim.releaseBoardSyncLease();
        }
      }
    }
  }

  public static final class ExclusiveGtpLifecycleReservation extends EngineModeReservation {
    private final boolean trackingFirstWinner;

    private ExclusiveGtpLifecycleReservation(
        Leelaz engine, Object owner, boolean trackingFirstWinner) {
      super(engine, owner);
      this.trackingFirstWinner = trackingFirstWinner;
    }

    boolean isTrackingFirstWinner() {
      return trackingFirstWinner;
    }
  }

  private static final class TrackedLoadSgfConsumer {
    private final Leelaz targetEngine;
    private final Path sgfFile;
    private final String command;
    private final LoadSgfDispatch dispatch;
    private final AtomicBoolean settled = new AtomicBoolean(false);
    private final Runnable responseHandler = this::onResponse;
    private final CommandSendFailureHandler sendFailureHandler =
        new CommandSendFailureHandler() {
          @Override
          public void onSendFailure(RuntimeException ex) {
            failFromSend(ex);
          }

          @Override
          public void onStateResetAfterOutputWrite(RuntimeException ex) {
            dispatch.recordFailure(ex);
          }
        };

    private TrackedLoadSgfConsumer(
        Leelaz targetEngine, Path sgfFile, String command, LoadSgfDispatch dispatch) {
      this.targetEngine = targetEngine;
      this.sgfFile = sgfFile;
      this.command = command;
      this.dispatch = dispatch;
      this.dispatch.registerPendingConsumer(this);
    }

    private Runnable responseHandler() {
      return responseHandler;
    }

    private CommandSendFailureHandler sendFailureHandler() {
      return sendFailureHandler;
    }

    private void onResponse() {
      if (targetEngine.isCurrentCommandResponseError()) {
        failFromResponse(
            targetEngine.buildSnapshotRestoreResponseFailure(
                command, sgfFile, targetEngine.currentCommandResponseLine()));
        return;
      }
      complete();
    }

    private void complete() {
      settle(false, null, false, false);
    }

    private void failFromSend(RuntimeException ex) {
      settle(true, ex, false, false);
    }

    private void failFromResponse(RuntimeException ex) {
      settle(true, ex, false, false);
    }

    private void cancelWithoutResponse() {
      settle(false, null, true, false);
    }

    private boolean shouldCancelForSendFailureFallback(boolean noResponseTimeoutReached) {
      if (!targetEngine.hasPendingResponseHandler(responseHandler)) {
        return true;
      }
      return noResponseTimeoutReached;
    }

    private void settle(
        boolean shouldRecordFailure,
        RuntimeException ex,
        boolean removeHandler,
        boolean cancelOtherConsumers) {
      if (!settled.compareAndSet(false, true)) {
        return;
      }
      if (removeHandler) {
        targetEngine.retireTrackedLoadSgfWithoutResponse(responseHandler);
      }
      if (shouldRecordFailure && ex != null) {
        if (cancelOtherConsumers) {
          dispatch.recordFailureAndCancelPendingConsumers(ex);
        } else {
          dispatch.recordFailure(ex);
        }
      }
      dispatch.completePendingConsumer(this);
    }
  }

  private static final class LoadSgfDispatch {
    private static final long PENDING_RESPONSE_TIMEOUT_NANOS =
        TimeUnit.MILLISECONDS.toNanos(LOAD_SGF_NO_RESPONSE_TIMEOUT_MILLIS);

    private final Runnable afterConsumed;
    private final String responseDescription;
    private final AtomicInteger pendingConsumers = new AtomicInteger(0);
    private final ArrayDeque<TrackedLoadSgfConsumer> pendingTrackedConsumers =
        new ArrayDeque<TrackedLoadSgfConsumer>();
    private final AtomicBoolean dispatchFinished = new AtomicBoolean(false);
    private final AtomicBoolean cleanupFinished = new AtomicBoolean(false);
    private final AtomicBoolean fallbackCleanupScheduled = new AtomicBoolean(false);
    private final AtomicLong dispatchFinishedAtNanos = new AtomicLong(-1L);
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final CountDownLatch completion = new CountDownLatch(1);

    private LoadSgfDispatch(Runnable afterConsumed, String responseDescription) {
      this.afterConsumed = afterConsumed;
      this.responseDescription = responseDescription;
    }

    private void registerPendingConsumer(TrackedLoadSgfConsumer consumer) {
      synchronized (pendingTrackedConsumers) {
        pendingTrackedConsumers.addLast(consumer);
      }
      pendingConsumers.incrementAndGet();
    }

    private void completePendingConsumer(TrackedLoadSgfConsumer consumer) {
      synchronized (pendingTrackedConsumers) {
        pendingTrackedConsumers.remove(consumer);
      }
      if (pendingConsumers.decrementAndGet() == 0 && dispatchFinished.get()) {
        finishCleanup();
      }
    }

    private void finishDispatch() {
      if (dispatchFinished.compareAndSet(false, true)) {
        dispatchFinishedAtNanos.compareAndSet(-1L, System.nanoTime());
      }
      if (pendingConsumers.get() == 0) {
        finishCleanup();
      }
    }

    private void recordFailure(RuntimeException ex) {
      failure.compareAndSet(null, ex);
    }

    private void recordFailureAndCancelPendingConsumers(RuntimeException ex) {
      recordFailure(ex);
      cancelAllPendingConsumersWithoutResponse();
    }

    private RuntimeException failure() {
      return failure.get();
    }

    private void scheduleFallbackCleanupAfterSendFailure() {
      if (pendingConsumers.get() == 0) {
        finishCleanup();
        return;
      }
      if (!fallbackCleanupScheduled.compareAndSet(false, true)) {
        return;
      }
      LOAD_SGF_CLEANUP_EXECUTOR.schedule(
          this::runSendFailureFallbackCleanup,
          LOAD_SGF_SEND_FAILURE_CLEANUP_TIMEOUT_MILLIS,
          TimeUnit.MILLISECONDS);
    }

    private List<TrackedLoadSgfConsumer> snapshotPendingConsumers() {
      synchronized (pendingTrackedConsumers) {
        return new ArrayList<>(pendingTrackedConsumers);
      }
    }

    private void cancelAllPendingConsumersWithoutResponse() {
      for (TrackedLoadSgfConsumer consumer : snapshotPendingConsumers()) {
        consumer.cancelWithoutResponse();
      }
    }

    private void runSendFailureFallbackCleanup() {
      fallbackCleanupScheduled.set(false);
      if (cleanupFinished.get()) {
        return;
      }
      cancelPendingConsumersAfterNoResponseTimeout();
      if (pendingConsumers.get() == 0) {
        finishCleanup();
        return;
      }
      if (dispatchFinished.get()) {
        scheduleFallbackCleanupAfterSendFailure();
      }
    }

    private void cancelPendingConsumersAfterNoResponseTimeout() {
      boolean noResponseTimeoutReached = isNoResponseTimeoutReached(System.nanoTime());
      for (TrackedLoadSgfConsumer consumer : snapshotPendingConsumers()) {
        if (consumer.shouldCancelForSendFailureFallback(noResponseTimeoutReached)) {
          consumer.cancelWithoutResponse();
        }
      }
    }

    private boolean isNoResponseTimeoutReached(long nowNanos) {
      long finishedAtNanos = dispatchFinishedAtNanos.get();
      if (finishedAtNanos < 0L) {
        return false;
      }
      return nowNanos - finishedAtNanos >= PENDING_RESPONSE_TIMEOUT_NANOS;
    }

    private void awaitCompletion() {
      try {
        if (completion.await(LOAD_SGF_NO_RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
          return;
        }
        recordFailureAndCancelPendingConsumers(
            new ExactSnapshotEngineRestore.Failure(
                ExactSnapshotEngineRestore.FailureCategory.TIMEOUT,
                "Timed out while waiting for "
                    + responseDescription
                    + " response after "
                    + LOAD_SGF_NO_RESPONSE_TIMEOUT_MILLIS
                    + " ms"));
        completion.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        recordFailureAndCancelPendingConsumers(
            new ExactSnapshotEngineRestore.Failure(
                ExactSnapshotEngineRestore.FailureCategory.TIMEOUT,
                "Interrupted while waiting for " + responseDescription + " response",
                ex));
      }
    }

    private void finishCleanup() {
      if (!cleanupFinished.compareAndSet(false, true)) {
        return;
      }
      try {
        afterConsumed.run();
      } finally {
        completion.countDown();
      }
    }
  }

  private final class StartupCommandDelivery implements QueuedCommandSettlement {
    private final String command;
    private final ReaderStreamBinding binding;
    private final EngineGameStartupCommandPermit engineGamePermit;
    private final CountDownLatch completion = new CountDownLatch(1);
    private final AtomicBoolean settled = new AtomicBoolean();
    private final AtomicInteger dispatchOwner = new AtomicInteger();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile QueuedCommand queuedCommand;
    private volatile Thread writerThread;

    private StartupCommandDelivery(
        String command,
        ReaderStreamBinding binding,
        EngineGameStartupCommandPermit engineGamePermit) {
      this.command = command;
      this.binding = binding;
      this.engineGamePermit = engineGamePermit;
    }

    @Override
    public void onQueued(QueuedCommand command) {
      queuedCommand = command;
    }

    @Override
    public void onWriteClaimed() {
      writerThread = Thread.currentThread();
      if (engineGamePermit != null) {
        engineGamePermit.onWriteClaimed();
      }
    }

    @Override
    public void onWriteCompleted() {
      if (settled.compareAndSet(false, true)) {
        unregisterStartupCommandDelivery(this);
        completion.countDown();
      }
    }

    @Override
    public void onRequestFailed(RuntimeException requestFailure) {
      try {
        if (engineGamePermit != null) {
          engineGamePermit.onRequestFailed(requestFailure);
        }
      } finally {
        if (settled.compareAndSet(false, true)) {
          Throwable physicalFailure = requestFailure.getCause();
          failure.set(
              physicalFailure instanceof RuntimeException || physicalFailure instanceof Error
                  ? physicalFailure
                  : requestFailure);
          finishClaimedFailure();
        }
      }
    }

    @Override
    public void onPhysicalWriteFailure(
        Throwable physicalFailure, RuntimeException requestFailure) {
      try {
        if (engineGamePermit != null) {
          engineGamePermit.onRequestFailed(requestFailure);
        }
      } finally {
        if (settled.compareAndSet(false, true)) {
          failure.set(physicalFailure);
          finishClaimedFailure();
        }
      }
    }

    @Override
    public void onResponseSettled() {
      if (engineGamePermit != null) {
        engineGamePermit.onResponseSettled();
      }
    }

    private EngineManager.EngineGameTransaction engineGameTransaction() {
      return engineGamePermit == null ? null : engineGamePermit.transaction;
    }

    private boolean belongsTo(EngineManager.EngineGameTransaction transaction) {
      return engineGamePermit != null && engineGamePermit.belongsTo(transaction);
    }

    private boolean isPhysicalWriteClaimed() {
      return engineGamePermit != null && engineGamePermit.isPhysicalWriteClaimed();
    }

    private void cancelBeforePhysicalWrite(EngineManager.EngineGameTransaction transaction) {
      if (engineGamePermit != null) {
        engineGamePermit.cancelBeforePhysicalWrite(transaction);
      }
    }

    private boolean claimFailure(RuntimeException requestFailure) {
      if (!settled.compareAndSet(false, true)) {
        return false;
      }
      failure.set(requestFailure);
      return true;
    }

    private void finishClaimedFailure() {
      unregisterStartupCommandDelivery(this);
      completion.countDown();
    }
  }

  private interface QueuedCommandSettlement {
    default void onQueued(QueuedCommand command) {}

    void onWriteClaimed();

    default void onWriteCompleted() {}

    void onRequestFailed(RuntimeException failure);

    default void onPhysicalWriteFailure(
        Throwable physicalFailure, RuntimeException requestFailure) {
      onRequestFailed(requestFailure);
    }

    default void onResponseSettled() {}
  }

  /**
   * A numbered response gate for the per-move clock command. Success alone may admit the
   * play/genmove continuation; an exact {@code ?id} is left to the command permit to fail the
   * owning transaction.
   */
  private static final class EngineGameTimeLeftResponseHandler implements Runnable {
    private final Leelaz owner;
    private final EngineManager.EngineGameClockSync sync;
    private final Runnable onSuccess;

    private EngineGameTimeLeftResponseHandler(
        Leelaz owner, EngineManager.EngineGameClockSync sync, Runnable onSuccess) {
      this.owner = owner;
      this.sync = sync;
      this.onSuccess = onSuccess;
    }

    @Override
    public void run() {
      if (owner.currentCommandResponseError
          || !EngineManager.isCurrentEngineGamePostMoveCommand(
              sync.turn, owner, sync.endpointIncarnation)) {
        return;
      }
      try {
        onSuccess.run();
      } catch (RuntimeException | Error continuationFailure) {
        EngineManager.failEngineGameTransaction(sync.turn.transaction, continuationFailure);
        throw continuationFailure;
      }
    }
  }

  private static final class EngineGameCommandPermit implements QueuedCommandSettlement {
    private static final int RESERVED = 0;
    private static final int WRITE_CLAIMED = 1;
    private static final int SETTLED = 2;

    private final Leelaz owner;
    private final EngineManager.EngineGamePostMoveToken turn;
    private final ReaderStreamBinding binding;
    private final AtomicInteger state = new AtomicInteger(RESERVED);
    private final AtomicReference<EngineManager.EngineGamePhysicalRequestLease> physicalLease =
        new AtomicReference<>();

    private EngineGameCommandPermit(
        Leelaz owner,
        EngineManager.EngineGamePostMoveToken turn,
        ReaderStreamBinding binding) {
      this.owner = owner;
      this.turn = turn;
      this.binding = binding;
    }

    private boolean belongsTo(EngineManager.EngineGameTransaction transaction) {
      return turn != null && turn.transaction == transaction;
    }

    private boolean isPhysicalWriteClaimed() {
      return state.get() == WRITE_CLAIMED;
    }

    private void cancelBeforePhysicalWrite(EngineManager.EngineGameTransaction transaction) {
      if (belongsTo(transaction)) {
        state.compareAndSet(RESERVED, SETTLED);
      }
    }

    @Override
    public void onWriteClaimed() {
      if (!state.compareAndSet(RESERVED, WRITE_CLAIMED)) {
        throw new IllegalStateException(
            "Engine-game turn changed before physical command output");
      }
      EngineManager.EngineGamePhysicalRequestLease lease =
          EngineManager.claimEngineGamePostMoveOutput(turn, owner, binding);
      if (lease == null) {
        state.set(SETTLED);
        throw new IllegalStateException(
            "Engine-game turn changed before physical command output");
      }
      physicalLease.set(lease);
      if (state.get() == SETTLED) {
        EngineManager.EngineGamePhysicalRequestLease retired = physicalLease.getAndSet(null);
        if (retired != null) {
          retired.close();
        }
        throw new IllegalStateException(
            "Engine-game turn retired while physical command output was being claimed");
      }
    }

    @Override
    public void onRequestFailed(RuntimeException failure) {
      if (settle()) {
        EngineManager.failEngineGameTransaction(turn == null ? null : turn.transaction, failure);
      }
    }

    @Override
    public void onResponseSettled() {
      boolean responseError = owner.currentCommandResponseError;
      String responseLine = owner.currentCommandResponseLine;
      if (!settle() || !responseError) {
        return;
      }
      EngineManager.failEngineGameTransaction(
          turn == null ? null : turn.transaction,
          new IllegalStateException(
              "Engine-game command failed: "
                  + (responseLine == null || responseLine.trim().isEmpty()
                      ? "? unknown engine error"
                      : responseLine.trim())));
    }

    private boolean settle() {
      int previous = state.getAndSet(SETTLED);
      EngineManager.EngineGamePhysicalRequestLease lease = physicalLease.getAndSet(null);
      if (lease != null) {
        lease.close();
      }
      return previous != SETTLED;
    }
  }

  /** Pins fallible PK startup commands to the exact game transaction and reader binding. */
  private static final class EngineGameStartupCommandPermit implements QueuedCommandSettlement {
    private static final int RESERVED = 0;
    private static final int WRITE_CLAIMED = 1;
    private static final int SETTLED = 2;

    private final Leelaz owner;
    private final EngineManager.EngineGameTransaction transaction;
    private final ReaderStreamBinding binding;
    private final AtomicInteger state = new AtomicInteger(RESERVED);
    private final AtomicReference<EngineManager.EngineGamePhysicalRequestLease> physicalLease =
        new AtomicReference<>();

    private EngineGameStartupCommandPermit(
        Leelaz owner,
        EngineManager.EngineGameTransaction transaction,
        ReaderStreamBinding binding) {
      this.owner = owner;
      this.transaction = transaction;
      this.binding = binding;
    }

    private boolean belongsTo(EngineManager.EngineGameTransaction expected) {
      return transaction == expected;
    }

    private boolean isPhysicalWriteClaimed() {
      return state.get() == WRITE_CLAIMED;
    }

    private void cancelBeforePhysicalWrite(EngineManager.EngineGameTransaction expected) {
      if (belongsTo(expected)) {
        state.compareAndSet(RESERVED, SETTLED);
      }
    }

    @Override
    public void onWriteClaimed() {
      if (!state.compareAndSet(RESERVED, WRITE_CLAIMED)) {
        throw new IllegalStateException(
            "Engine-game startup transaction changed before physical command output");
      }
      EngineManager.EngineGamePhysicalRequestLease lease =
          EngineManager.claimEngineGameStartupOutput(
              transaction, owner, binding);
      if (lease == null) {
        state.set(SETTLED);
        throw new IllegalStateException(
            "Engine-game startup transaction changed before physical command output");
      }
      physicalLease.set(lease);
      if (state.get() == SETTLED) {
        EngineManager.EngineGamePhysicalRequestLease retired = physicalLease.getAndSet(null);
        if (retired != null) {
          retired.close();
        }
        throw new IllegalStateException(
            "Engine-game startup transaction retired while output was being claimed");
      }
    }

    @Override
    public void onRequestFailed(RuntimeException failure) {
      if (settle()) {
        EngineManager.failEngineGameTransaction(transaction, failure);
      }
    }

    @Override
    public void onResponseSettled() {
      boolean responseError = owner.currentCommandResponseError;
      String responseLine = owner.currentCommandResponseLine;
      if (!settle() || !responseError) {
        return;
      }
      EngineManager.failEngineGameTransaction(
          transaction,
          new IllegalStateException(
              "Engine-game startup command failed: "
                  + (responseLine == null || responseLine.trim().isEmpty()
                      ? "? unknown engine error"
                      : responseLine.trim())));
    }

    private boolean settle() {
      int previous = state.getAndSet(SETTLED);
      EngineManager.EngineGamePhysicalRequestLease lease = physicalLease.getAndSet(null);
      if (lease != null) {
        lease.close();
      }
      return previous != SETTLED;
    }
  }

  private static final class QueuedCommand {
    private final String command;
    private final Runnable onResponse;
    private final CommandSendFailureHandler onSendFailure;
    /** Internal queue-consistency link; deliberately separate from tracked restore callbacks. */
    private volatile CommandSendFailureHandler internalSendFailureHandler;
    private final boolean failOnSendError;
    private final QueuedCommandSettlement settlement;
    private final boolean countedCommand;
    private final RestartBootstrapReceipt restartBootstrapReceipt;
    private final ReaderStreamBinding readBoardGmaResponseBinding;
    private final Object expectedLeela0110StateToken;
    private boolean foregroundRestoreCommand;
    private RuntimeException cancellationFailure;
    private boolean outputWriteStarted;
    private boolean settlementFailurePublished;
    private RuntimeException stateResetAfterOutputWriteFailure;
    private boolean stateResetAfterOutputWritePublished;
    private boolean outstandingResponseRetired;
    private boolean commandCountRetired;
    private volatile AnalysisStateLineage analysisStateLineage;
    private volatile ReaderStreamBinding analysisStateResponseBinding;

    private QueuedCommand(
        String command,
        Runnable onResponse,
        CommandSendFailureHandler onSendFailure,
        boolean failOnSendError) {
      this(command, onResponse, onSendFailure, failOnSendError, null, true, null, null, null);
    }

    private QueuedCommand(
        String command,
        Runnable onResponse,
        CommandSendFailureHandler onSendFailure,
        boolean failOnSendError,
        QueuedCommandSettlement settlement) {
      this(command, onResponse, onSendFailure, failOnSendError, settlement, true, null, null, null);
    }

    private QueuedCommand(
        String command,
        Runnable onResponse,
        CommandSendFailureHandler onSendFailure,
        boolean failOnSendError,
        QueuedCommandSettlement settlement,
        boolean countedCommand,
        RestartBootstrapReceipt restartBootstrapReceipt,
        ReaderStreamBinding readBoardGmaResponseBinding,
        Object expectedLeela0110StateToken) {
      this.command = command;
      this.onResponse = onResponse;
      this.onSendFailure = onSendFailure;
      this.failOnSendError = failOnSendError;
      this.settlement = settlement;
      this.countedCommand = countedCommand;
      this.restartBootstrapReceipt = restartBootstrapReceipt;
      this.readBoardGmaResponseBinding = readBoardGmaResponseBinding;
      this.expectedLeela0110StateToken = expectedLeela0110StateToken;
      if (settlement != null) {
        settlement.onQueued(this);
      }
    }

    private boolean isTrackedLoadSgf() {
      return isTrackedExactSnapshotRestoreCommand(command) && onSendFailure != null;
    }

    private void bindAnalysisStateLineage(
        AnalysisStateLineage lineage, ReaderStreamBinding responseBinding) {
      analysisStateLineage = lineage;
      analysisStateResponseBinding = responseBinding;
    }

    private AnalysisStateLineage analysisStateLineage() {
      return analysisStateLineage;
    }

    private ReaderStreamBinding analysisStateResponseBinding() {
      return analysisStateResponseBinding;
    }

    private void failAnalysisStateLineageAfterSendFailure() {
      AnalysisStateLineage lineage = analysisStateLineage;
      if (lineage != null) {
        lineage.fail();
      }
    }

    private boolean requiresStateReset() {
      return isTrackedLoadSgf()
          || internalSendFailureHandler != null
          || settlement != null
          || onResponse instanceof EngineGameResponseHandler;
    }

    private boolean isEngineGameCommand() {
      return onResponse instanceof EngineGameResponseHandler
          || settlement instanceof EngineGameCommandPermit
          || settlement instanceof EngineGameStartupCommandPermit
          || (settlement instanceof StartupCommandDelivery
              && ((StartupCommandDelivery) settlement).engineGameTransaction() != null);
    }

    private EngineManager.EngineGameTransaction engineGameTransaction() {
      if (onResponse instanceof EngineGameResponseHandler) {
        return ((EngineGameResponseHandler) onResponse).context.transaction;
      }
      if (settlement instanceof EngineGameCommandPermit) {
        EngineManager.EngineGamePostMoveToken turn =
            ((EngineGameCommandPermit) settlement).turn;
        return turn == null ? null : turn.transaction;
      }
      if (settlement instanceof EngineGameStartupCommandPermit) {
        return ((EngineGameStartupCommandPermit) settlement).transaction;
      }
      return settlement instanceof StartupCommandDelivery
          ? ((StartupCommandDelivery) settlement).engineGameTransaction()
          : null;
    }

    private EngineManager.EngineGameTransaction engineGameStartupTransaction() {
      if (settlement instanceof EngineGameStartupCommandPermit) {
        return ((EngineGameStartupCommandPermit) settlement).transaction;
      }
      return settlement instanceof StartupCommandDelivery
          ? ((StartupCommandDelivery) settlement).engineGameTransaction()
          : null;
    }

    private synchronized boolean cancelBeforeOutputWrite(RuntimeException failure) {
      if (outputWriteStarted || cancellationFailure != null) {
        return false;
      }
      outstandingResponseRetired = true;
      if (cancellationFailure == null) {
        cancellationFailure = failure;
      }
      return true;
    }

    private synchronized boolean isCancelledBeforeOutputWrite() {
      return cancellationFailure != null;
    }

    private synchronized RuntimeException cancellationFailure() {
      return cancellationFailure;
    }

    private synchronized boolean claimCommandCountRetirement() {
      if (!countedCommand || commandCountRetired) {
        return false;
      }
      commandCountRetired = true;
      return true;
    }

    private boolean beginOutputWrite() {
      synchronized (this) {
        if (cancellationFailure != null) {
          return false;
        }
        if (onResponse instanceof EngineGameResponseHandler
            && !((EngineGameResponseHandler) onResponse).claimPhysicalWrite()) {
          cancellationFailure =
              new IllegalStateException(
                  "Engine-game request lost exact ownership before output write");
          outstandingResponseRetired = true;
          return false;
        }
        outputWriteStarted = true;
      }
      if (settlement != null) {
        settlement.onWriteClaimed();
      }
      return true;
    }

    private synchronized void installInternalSendFailureHandler(
        CommandSendFailureHandler handler) {
      if (handler == null) {
        return;
      }
      if (internalSendFailureHandler != null) {
        throw new IllegalStateException("Internal send-failure handler already installed");
      }
      internalSendFailureHandler = handler;
    }

    private boolean belongsToEngineGameTransaction(
        EngineManager.EngineGameTransaction transaction) {
      if (onResponse instanceof EngineGameResponseHandler
          && ((EngineGameResponseHandler) onResponse).belongsTo(transaction)) {
        return true;
      }
      if (settlement instanceof EngineGameCommandPermit) {
        return ((EngineGameCommandPermit) settlement).belongsTo(transaction);
      }
      if (settlement instanceof EngineGameStartupCommandPermit) {
        return ((EngineGameStartupCommandPermit) settlement).belongsTo(transaction);
      }
      return settlement instanceof StartupCommandDelivery
          && ((StartupCommandDelivery) settlement).belongsTo(transaction);
    }

    private boolean isEngineGamePhysicalWriteClaimed() {
      if (onResponse instanceof EngineGameResponseHandler
          && ((EngineGameResponseHandler) onResponse).isPhysicalWriteClaimed()) {
        return true;
      }
      if (settlement instanceof EngineGameCommandPermit) {
        return ((EngineGameCommandPermit) settlement).isPhysicalWriteClaimed();
      }
      if (settlement instanceof EngineGameStartupCommandPermit) {
        return ((EngineGameStartupCommandPermit) settlement).isPhysicalWriteClaimed();
      }
      return settlement instanceof StartupCommandDelivery
          && ((StartupCommandDelivery) settlement).isPhysicalWriteClaimed();
    }

    private void cancelEngineGameBeforePhysicalWrite(
        EngineManager.EngineGameTransaction transaction) {
      if (onResponse instanceof EngineGameResponseHandler) {
        ((EngineGameResponseHandler) onResponse).cancelBeforePhysicalWrite(transaction);
      }
      if (settlement instanceof EngineGameCommandPermit) {
        ((EngineGameCommandPermit) settlement).cancelBeforePhysicalWrite(transaction);
      }
      if (settlement instanceof EngineGameStartupCommandPermit) {
        ((EngineGameStartupCommandPermit) settlement).cancelBeforePhysicalWrite(transaction);
      }
      if (settlement instanceof StartupCommandDelivery) {
        ((StartupCommandDelivery) settlement).cancelBeforePhysicalWrite(transaction);
      }
    }

    private void cancelEngineGameBeforePhysicalWrite() {
      if (onResponse instanceof EngineGameResponseHandler) {
        EngineGameResponseHandler handler = (EngineGameResponseHandler) onResponse;
        handler.cancelBeforePhysicalWrite(handler.context.transaction);
      }
      if (settlement instanceof EngineGameCommandPermit) {
        EngineGameCommandPermit permit = (EngineGameCommandPermit) settlement;
        permit.cancelBeforePhysicalWrite(permit.turn == null ? null : permit.turn.transaction);
      }
      if (settlement instanceof EngineGameStartupCommandPermit) {
        EngineGameStartupCommandPermit permit =
            (EngineGameStartupCommandPermit) settlement;
        permit.cancelBeforePhysicalWrite(permit.transaction);
      }
      if (settlement instanceof StartupCommandDelivery) {
        StartupCommandDelivery delivery = (StartupCommandDelivery) settlement;
        delivery.cancelBeforePhysicalWrite(delivery.engineGameTransaction());
      }
    }

    private synchronized void markStateResetAfterOutputWrite(RuntimeException failure) {
      outstandingResponseRetired = true;
      if (stateResetAfterOutputWriteFailure == null) {
        stateResetAfterOutputWriteFailure = failure;
      }
    }

    private synchronized boolean isStateResetAfterOutputWritePublished() {
      return stateResetAfterOutputWritePublished;
    }

    private synchronized boolean isOutstandingResponseRetired() {
      return outstandingResponseRetired;
    }

    private void notifySendFailure(RuntimeException failure) {
      try {
        try {
          publishInternalSendFailure(failure, false);
        } finally {
          if (onSendFailure != null) {
            onSendFailure.onSendFailure(failure);
          }
        }
      } finally {
        try {
          publishSettlementFailure(failure);
        } finally {
          if (onResponse instanceof EngineGameResponseHandler) {
            ((EngineGameResponseHandler) onResponse).failBeforePhysicalWrite(failure);
          }
        }
      }
    }

    private void publishInternalSendFailure(
        RuntimeException failure, boolean stateResetAfterOutputWrite) {
      CommandSendFailureHandler internalHandler;
      synchronized (this) {
        internalHandler = internalSendFailureHandler;
      }
      if (internalHandler == null) {
        return;
      }
      if (stateResetAfterOutputWrite) {
        internalHandler.onStateResetAfterOutputWrite(failure);
      } else {
        internalHandler.onSendFailure(failure);
      }
    }

    private void publishStateResetAfterOutputWrite() {
      RuntimeException failure;
      synchronized (this) {
        if (stateResetAfterOutputWriteFailure == null || stateResetAfterOutputWritePublished) {
          return;
        }
        failure = stateResetAfterOutputWriteFailure;
        stateResetAfterOutputWritePublished = true;
      }
      try {
        try {
          publishInternalSendFailure(failure, true);
        } finally {
          if (onSendFailure != null) {
            onSendFailure.onStateResetAfterOutputWrite(failure);
          }
        }
      } finally {
        publishSettlementFailure(failure);
      }
    }

    private void publishResponseSettlement() {
      if (settlement != null) {
        settlement.onResponseSettled();
      }
    }

    private void publishWriteCompleted() {
      if (settlement != null) {
        settlement.onWriteCompleted();
      }
    }

    private void publishSettlementFailure(RuntimeException failure) {
      synchronized (this) {
        if (settlement == null || settlementFailurePublished) {
          return;
        }
        settlementFailurePublished = true;
      }
      settlement.onRequestFailed(failure);
    }

    private void publishPhysicalWriteFailure(
        Throwable physicalFailure, RuntimeException requestFailure) {
      synchronized (this) {
        if (settlement == null || settlementFailurePublished) {
          return;
        }
        settlementFailurePublished = true;
      }
      settlement.onPhysicalWriteFailure(physicalFailure, requestFailure);
    }
  }

  private static final class GtpCommandStateReset {
    private final RuntimeException failure;
    private final List<QueuedCommand> cancelledLoadSgfCommands;
    private final List<QueuedCommand> sentLoadSgfCommands;
    private final List<QueuedCommand> cancelledEngineGameCommands;
    private final List<QueuedCommand> sentEngineGameCommands;
    private final EngineGameResponseHandler retiredEngineGameHandler;

    private GtpCommandStateReset(
        RuntimeException failure,
        List<QueuedCommand> cancelledLoadSgfCommands,
        List<QueuedCommand> sentLoadSgfCommands,
        List<QueuedCommand> cancelledEngineGameCommands,
        List<QueuedCommand> sentEngineGameCommands,
        EngineGameResponseHandler retiredEngineGameHandler) {
      this.failure = failure;
      this.cancelledLoadSgfCommands = cancelledLoadSgfCommands;
      this.sentLoadSgfCommands = sentLoadSgfCommands;
      this.cancelledEngineGameCommands = cancelledEngineGameCommands;
      this.sentEngineGameCommands = sentEngineGameCommands;
      this.retiredEngineGameHandler = retiredEngineGameHandler;
    }
  }

  private static final class TrackingStreamCleanup {
    private final ExclusiveGtpSession session;
    private final GtpCommandStateReset commandStateReset;
    private final TrackingDispositionNotification dispositionNotification;

    private TrackingStreamCleanup(
        ExclusiveGtpSession session,
        GtpCommandStateReset commandStateReset,
        TrackingDispositionNotification dispositionNotification) {
      this.session = session;
      this.commandStateReset = commandStateReset;
      this.dispositionNotification = dispositionNotification;
    }
  }

  private void rememberRecentLine(ArrayDeque<String> lines, String line) {
    if (lines == null || line == null) {
      return;
    }
    synchronized (lines) {
      while (lines.size() >= ENGINE_DIAGNOSTIC_TAIL_LINES) {
        lines.removeFirst();
      }
      lines.addLast(line);
    }
  }

  private String buildEngineExitDiagnostic(String baseMessage) {
    StringBuilder builder = new StringBuilder(baseMessage == null ? "" : baseMessage);
    appendExitCode(builder);
    appendRecentLines(builder, "Recent stderr", recentStderrLines);
    appendRecentLines(builder, "Recent stdout", recentStdoutLines);
    return builder.toString();
  }

  private void appendExitCode(StringBuilder builder) {
    if (builder == null || process == null) {
      return;
    }
    try {
      int exitCode = process.exitValue();
      builder.append("\nExit code: ").append(exitCode);
    } catch (IllegalThreadStateException e) {
    }
  }

  private void appendRecentLines(
      StringBuilder builder, String title, ArrayDeque<String> recentLines) {
    if (builder == null || recentLines == null) {
      return;
    }
    String tail = snapshotRecentLines(recentLines);
    if (tail.isEmpty()) {
      return;
    }
    builder.append("\n").append(title).append(":\n").append(tail);
  }

  private String snapshotRecentLines(ArrayDeque<String> lines) {
    StringBuilder builder = new StringBuilder();
    synchronized (lines) {
      for (String line : lines) {
        if (line == null || line.trim().isEmpty()) {
          continue;
        }
        if (builder.length() > 0) {
          builder.append('\n');
        }
        builder.append(line);
      }
    }
    return builder.toString();
  }

  /** Check whether leelaz is responding to the last command */
  private int[] commandWatermarkSnapshot() {
    synchronized (commandQueue()) {
      return new int[] {cmdNumber, currentCmdNum};
    }
  }

  int commandNumberSnapshot() {
    synchronized (commandQueue()) {
      return cmdNumber;
    }
  }

  public boolean isResponseUpToDate() {
    // Use >= instead of == for avoiding hang-up, though it cannot happen
    synchronized (commandQueue()) {
      return currentCmdNum >= cmdNumber - 1; // &&currentCmdNum >=ignoreCmdNumber;
    }
  }

  private boolean isAnalysisResponseUpToDateSnapshot(AnalysisOutputRoute route) {
    // A physically published analysis owner is the authoritative streaming carrier. Its route is
    // revalidated under the binding/transaction fence at every mutation, so unrelated queued
    // state responses must not make current pre-terminal info appear stale.
    if (route != null && route.acceptsInfoLine()) {
      return true;
    }
    synchronized (commandQueue()) {
      return currentCmdNum >= cmdNumber - 1;
    }
  }

  private boolean shouldStopPonderAfterEnginePlayLine() {
    return Lizzie.frame != null
        && (Lizzie.frame.isPlayingAgainstLeelaz
            || Lizzie.frame.isAnaPlayingAgainstLeelaz
            || EngineManager.isEngineGame());
  }

  private boolean isResponseUpToPreDate() {
    // Use >= instead of == for avoiding hang-up, though it cannot happen
    synchronized (commandQueue()) {
      return currentCmdNum >= cmdNumber - 2; // &&currentCmdNum >=ignoreCmdNumber;
    }
  }

  private boolean isResponseUpToPreCommand() {
    // Use >= instead of == for avoiding hang-up, though it cannot happen
    synchronized (commandQueue()) {
      return currentCmdNum >= cmdNumber - 3; // &&currentCmdNum >=ignoreCmdNumber;
    }
  }

  public void setResponseUpToDate() {
    // Use >= instead of == for avoiding hang-up, though it cannot happen
    synchronized (commandQueue()) {
      currentCmdNum = cmdNumber - 1;
    }
    //	ignoreCmdNumber=cmdNumber-1;
  }

  private void settleTrackingPonderAfterPlayResponse() {
    if (currentCommandResponseError) {
      return;
    }
    settleTrackingPonderResponseWatermark();
  }

  private void settleTrackingPonderResponseWatermark() {
    synchronized (commandQueue()) {
      if (currentCmdNum < cmdNumber - 1) {
        currentCmdNum++;
      }
    }
  }

  /**
   * @param color color of stone to play
   * @param move coordinate of the coordinate
   */
  public void playMove(Stone color, String move) {
    playMove(color, move, false, false);
  }

  public void playMove(Stone color, String move, boolean addPlayer, boolean blackToPlay) {
    if ((!isKatago || isSai)
        && "pass".equals(move)
        && Lizzie.board.getHistory().getCurrentHistoryNode() != Lizzie.board.getHistory().getStart()
        && Lizzie.board.getData().isPassNode()) {
      this.setModifyEnd();
      return;
    }
    //		canGetGenmoveInfoGen = true;
    //	getGenmoveInfoPrevious = true;
    String colorString;
    switch (color) {
      case BLACK:
        colorString = "B";
        break;
      case WHITE:
        colorString = "W";
        break;
      default:
        return;
        //          throw new IllegalArgumentException(
        //              "The stone color must be B or W, but was " + color.toString());
    }
    boolean continuePonderAfterMove = isPonderingOrWasPonderingBeforeTracking();
    boolean resumeAnalysisAfterMove = stopByLimit || continuePonderAfterMove;
    boolean ponderAfterMove =
        resumeAnalysisAfterMove
            && !Lizzie.frame.isPlayingAgainstLeelaz
            && (Lizzie.config.isAutoAna
                || ((Lizzie.config.analyzeBlack && color == Stone.WHITE)
                    || (Lizzie.config.analyzeWhite && color == Stone.BLACK)));
    boolean settleTrackingPonder = hasTrackingStreamSession() && ponderAfterMove;
    retireAnalysisInfoBeforeQueuedPlay();
    sendCommand(
        "play " + colorString + " " + move,
        settleTrackingPonder ? this::settleTrackingPonderAfterPlayResponse : null);
    if (Lizzie.frame.isPlayingAgainstLeelaz) this.canGetSummaryInfo = true;
    //				bestMovesPrevious = new ArrayList<>();
    if (Lizzie.frame.isAnaPlayingAgainstLeelaz
        && !Lizzie.frame.bothSync
        && Lizzie.frame.playerIsBlack == blackToPlay) return;
    if (ponderAfterMove) {
      ponder(addPlayer, blackToPlay);
    } else if (resumeAnalysisAfterMove && !Lizzie.frame.isPlayingAgainstLeelaz) {
      nameCmdfornoponder();
      underPonder = true;
    }
    if (!isPondering && !Lizzie.config.playponder && isKatago) sendCommand("stop-ponder");
  }

  public void playMoveNoPonder(Stone color, String move) {
    String colorString;
    switch (color) {
      case BLACK:
        colorString = "B";
        break;
      case WHITE:
        colorString = "W";
        break;
      default:
        return;
        //          throw new IllegalArgumentException(
        //              "The stone color must be B or W, but was " + color.toString());
    }
    sendCommand("play " + colorString + " " + move);
    // Lizzie.frame.subBoardRenderer.reverseBestmoves = true;
    // Lizzie.frame.boardRenderer.reverseBestmoves = true;
    // bestMoves = new ArrayList<>();
  }

  public void playMoveNoPonder(String colorString, String move) {
    if (Lizzie.config.enginePkPonder) {
      sendCommand("play " + colorString + " " + move);
      pkponder();
      pkMoveTime = System.currentTimeMillis() - pkMoveStartTime;
      pkMoveTimeGame = pkMoveTimeGame + pkMoveTime;
      return;
    }
    sendCommand("play " + colorString + " " + move);
    nameCmdfornoponder();
    // Lizzie.frame.subBoardRenderer.reverseBestmoves = true;
    // Lizzie.frame.boardRenderer.reverseBestmoves = true;
    // bestMoves = new ArrayList<>();
    pkMoveTime = System.currentTimeMillis() - pkMoveStartTime;
    pkMoveTimeGame = pkMoveTimeGame + pkMoveTime;
  }

  public void playMovePonder(String colorString, String move) {
    Lizzie.frame.mouseOverCoordinate = LizzieFrame.outOfBoundCoordinate;
    canSetNotPlayed = true;
    sendCommand("play " + colorString + " " + move);
    pkponder();
    pkMoveStartTime = System.currentTimeMillis();
  }

  public boolean playMoveGenmove(String colorString, String move) {
    // genmoveNode++;
    //	canGetGenmoveInfo = false;
    if (this.resigned) {
      return false;
    }
    sendCommand("play " + colorString + " " + move);
    Lizzie.frame.updateTitle();
    return true;
  }

  boolean playMoveGenmove(
      String colorString, String move, EngineManager.EngineGamePostMoveToken turn) {
    if (this.resigned) {
      return false;
    }
    boolean accepted = sendEngineGameCommand("play " + colorString + " " + move, turn);
    if (accepted && EngineManager.isCurrentEngineGamePostMoveToken(turn)) {
      Lizzie.frame.updateTitle();
    }
    return accepted;
  }

  private boolean playEngineGameAnalysisMove(
      String colorString,
      String move,
      EngineManager.EngineGamePostMoveToken turn,
      boolean ponderAfterPlay) {
    if (!sendEngineGameCommand("play " + colorString + " " + move, turn)) {
      return false;
    }
    if (!EngineManager.isCurrentEngineGamePostMoveToken(turn)) {
      return false;
    }
    if (!ponderAfterPlay) {
      return nameCmdfornoponder(turn);
    }
    String analyzeCommand =
        isKatago
            ? "kata-analyze " + getInterval() + addKataTag()
            : isSayuri ? "analyze 1 " + getInterval() : "lz-analyze " + getInterval();
    boolean accepted = sendEngineGameCommand(analyzeCommand, turn);
    if (accepted && EngineManager.isCurrentEngineGamePostMoveToken(turn)) {
      isPondering = true;
      startPonderTime = System.currentTimeMillis();
      pkMoveStartTime = startPonderTime;
    }
    return accepted;
  }

  private boolean sendEngineGameCommand(
      String command, EngineManager.EngineGamePostMoveToken turn) {
    ReaderStreamBinding binding = currentReaderStreamBinding();
    EngineGameCommandPermit permit = new EngineGameCommandPermit(this, turn, binding);
    boolean accepted =
        sendCommand(
            command,
            null,
            null,
            true,
            false,
            TrackingReleaseReason.ORDINARY_OPERATION,
            permit,
            false,
            binding);
    if (!accepted) {
      EngineManager.failEngineGameTransaction(
          turn == null ? null : turn.transaction,
          new IllegalStateException("Engine-game command was rejected: " + command));
    }
    return accepted;
  }

  private boolean sendEngineGameTimeLeft(
      EngineManager.EngineGameClockSync sync, Runnable onSuccess) {
    if (sync == null
        || sync.endpoint != this
        || sync.command == null
        || onSuccess == null) {
      return false;
    }
    ReaderStreamBinding binding = currentReaderStreamBinding();
    if (binding != sync.endpointIncarnation
        || !EngineManager.isCurrentEngineGamePostMoveCommand(sync.turn, this, binding)) {
      return false;
    }
    EngineGameCommandPermit permit = new EngineGameCommandPermit(this, sync.turn, binding);
    EngineGameTimeLeftResponseHandler responseHandler =
        new EngineGameTimeLeftResponseHandler(this, sync, onSuccess);
    boolean accepted =
        sendCommand(
            sync.command,
            responseHandler,
            null,
            true,
            false,
            TrackingReleaseReason.ORDINARY_OPERATION,
            permit,
            false,
            binding);
    if (!accepted) {
      EngineManager.failEngineGameTransaction(
          sync.turn.transaction,
          new IllegalStateException("Engine-game time_left command was rejected"));
    }
    return accepted;
  }

  public String addKataTag() {
    return (Lizzie.config.showKataGoEstimate ? " ownership true" : "")
        + (Lizzie.config.showPvVisits ? " pvVisits true" : "")
        + (Lizzie.config.showKataGoEstimate
                && supportMovesOwnership
                && Lizzie.config.useMovesOwnership
            ? " movesOwnership true"
            : "");
  }

  public synchronized void genmove(String color) {
    genmove(color, false);
  }

  public synchronized boolean genmove(String color, boolean inputCommand) {
    boolean manualRequest =
        inputCommand
            && (Lizzie.frame == null
                || (!Lizzie.frame.isPlayingAgainstLeelaz
                    && !Lizzie.frame.isAnaPlayingAgainstLeelaz));
    if (!(manualRequest && hasTrackingStreamSession())
        && rejectNewExclusiveWorkDuringGtpLease()) {
      return false;
    }
    sendPlayingAgainstHumanTimeLeftBeforeGenmove();
    String command =
        (this.isKatago
            ? ("kata-genmove_analyze " + color + " " + getInterval() + addKataTag())
            : (this.isSayuri
                ? ("genmove_analyze " + color + " " + getInterval())
                : (this.isSai || this.isLeela
                    ? ("lz-genmove_analyze " + color + " " + getInterval())
                    : ("genmove " + color))));
    if (manualRequest) {
      Object requestOwner = new Object();
      QueuedCommandSettlement settlement =
          new QueuedCommandSettlement() {
            @Override
            public void onWriteClaimed() {
              synchronized (Leelaz.this) {
                manualGenmoveRequestOwner = requestOwner;
                isInputCommand = true;
                isThinking = true;
              }
              LizzieFrame.menu.toggleEngineMenuStatus(false, true);
            }

            @Override
            public void onRequestFailed(RuntimeException failure) {
              boolean cleared;
              synchronized (Leelaz.this) {
                cleared = manualGenmoveRequestOwner == requestOwner;
                if (cleared) {
                  manualGenmoveRequestOwner = null;
                  isInputCommand = false;
                  isThinking = false;
                }
              }
              if (cleared) {
                LizzieFrame.menu.toggleEngineMenuStatus(false, false);
              }
            }

            @Override
            public void onResponseSettled() {
              synchronized (Leelaz.this) {
                if (manualGenmoveRequestOwner == requestOwner) {
                  manualGenmoveRequestOwner = null;
                }
              }
            }
          };
      return sendCommand(
          command,
          null,
          null,
          false,
          true,
          TrackingReleaseReason.ORDINARY_OPERATION,
          settlement,
          true);
    }
    if (inputCommand) {
      isInputCommand = true;
    }
    sendCommand(command);
    isThinking = true;
    LizzieFrame.menu.toggleEngineMenuStatus(false, true);
    return true;
  }

  private static final class ReadBoardGmaRuntimeParam {
    private final String name;
    private String originalValue = "";
    private boolean snapshotRequested = false;
    private boolean overridden = false;
    private boolean restorePending = false;
    private boolean restoreTracked = false;
    private long revision;
    private long standaloneRestoreRevision = -1L;
    private ReadBoardGmaRestoreBarrier barrierRestoreDispatched;

    private ReadBoardGmaRuntimeParam(String name) {
      this.name = name;
    }
  }

  private final class ReadBoardGmaPreparation {
    private final String color;
    private final int maxTimeSeconds;
    private final int maxVisits;
    private final boolean ponder;
    private final ReadBoardGmaSessionAdmission sessionAdmission;
    private boolean cancellationRequested;
    private Runnable cancellationSuccess;
    private Consumer<String> cancellationFailure;

    private ReadBoardGmaPreparation(
        String color,
        int maxTimeSeconds,
        int maxVisits,
        boolean ponder,
        ReadBoardGmaSessionAdmission sessionAdmission) {
      this.color = color;
      this.maxTimeSeconds = maxTimeSeconds;
      this.maxVisits = maxVisits;
      this.ponder = ponder;
      this.sessionAdmission = sessionAdmission;
    }

    private void start() {
      if (RemoteComputeConfig.isCustomWebSocketEngineCommand(engineCommand)) {
        prepareMaxTime();
      } else {
        preparePondering();
      }
    }

    private void preparePondering() {
      prepareValue(
          readBoardGmaPondering, ponder ? "true" : "false", this::prepareMaxTime);
    }

    private void prepareMaxTime() {
      prepareParam(readBoardGmaMaxTime, maxTimeSeconds, this::prepareMaxVisits);
    }

    private void prepareMaxVisits() {
      prepareParam(readBoardGmaMaxVisits, maxVisits, this::finish);
    }

    private void prepareParam(ReadBoardGmaRuntimeParam param, int value, Runnable completion) {
      if (finishCancellationIfRequested()) {
        return;
      }
      boolean requestSnapshot;
      synchronized (readBoardGmaLock()) {
        if (readBoardGmaPreparation != this || engineStateUnrestored) {
          return;
        }
        requestSnapshot = !param.snapshotRequested;
        if (requestSnapshot) {
          param.snapshotRequested = true;
        }
      }
      if (requestSnapshot) {
        sendPreparationCommand(
            "kata-get-param " + param.name,
            response -> {
              String originalValue = parseKataGetParamValue(response);
              if (originalValue.isEmpty()) {
                fail("invalid parameter snapshot response: " + param.name);
                return;
              }
              synchronized (readBoardGmaLock()) {
                if (readBoardGmaPreparation != this || engineStateUnrestored) {
                  return;
                }
                param.originalValue = originalValue;
              }
              if (finishCancellationIfRequested()) {
                return;
              }
              if (value <= 0) {
                completion.run();
                return;
              }
              setParam(param, String.valueOf(value), true, completion);
            });
        return;
      }
      if (value <= 0) {
        restoreParamForMoveIfNeeded(param, completion);
        return;
      }
      setParam(param, String.valueOf(value), true, completion);
    }

    private void prepareValue(
        ReadBoardGmaRuntimeParam param, String value, Runnable completion) {
      if (finishCancellationIfRequested()) {
        return;
      }
      boolean requestSnapshot;
      synchronized (readBoardGmaLock()) {
        if (readBoardGmaPreparation != this || engineStateUnrestored) {
          return;
        }
        requestSnapshot = !param.snapshotRequested;
        if (requestSnapshot) {
          param.snapshotRequested = true;
        }
      }
      if (!requestSnapshot) {
        setParam(param, value, true, completion);
        return;
      }
      sendPreparationCommand(
          "kata-get-param " + param.name,
          response -> {
            String originalValue = parseKataGetParamValue(response);
            if (originalValue.isEmpty()) {
              fail("invalid parameter snapshot response: " + param.name);
              return;
            }
            synchronized (readBoardGmaLock()) {
              if (readBoardGmaPreparation != this || engineStateUnrestored) {
                return;
              }
              param.originalValue = originalValue;
            }
            if (!finishCancellationIfRequested()) {
              setParam(param, value, true, completion);
            }
          });
    }

    private void restoreParamForMoveIfNeeded(ReadBoardGmaRuntimeParam param, Runnable completion) {
      String originalValue;
      synchronized (readBoardGmaLock()) {
        if (readBoardGmaPreparation != this || engineStateUnrestored) {
          return;
        }
        if (!param.overridden) {
          originalValue = null;
        } else {
          originalValue = param.originalValue;
        }
      }
      if (originalValue == null) {
        completion.run();
        return;
      }
      if (originalValue.isEmpty()) {
        fail("missing parameter snapshot: " + param.name);
        return;
      }
      setParam(param, originalValue, false, completion);
    }

    private void setParam(
        ReadBoardGmaRuntimeParam param,
        String value,
        boolean overridden,
        Runnable completion) {
      sendPreparationCommand(
          "kata-set-param " + param.name + " " + value,
          response -> {
            synchronized (readBoardGmaLock()) {
              if (readBoardGmaPreparation != this || engineStateUnrestored) {
                return;
              }
              param.overridden = overridden;
              param.restorePending = false;
              param.revision++;
            }
            if (finishCancellationIfRequested()) {
              return;
            }
            completion.run();
          });
    }

    private void finish() {
      boolean cancelled;
      Runnable cancellationSuccessCallback;
      Consumer<String> cancellationFailureCallback;
      synchronized (readBoardGmaLock()) {
        if (readBoardGmaPreparation != this || engineStateUnrestored) {
          return;
        }
        readBoardGmaPreparation = null;
        cancelled = cancellationRequested;
        if (cancelled) {
          cancellationSuccessCallback = cancellationSuccess;
          cancellationFailureCallback = cancellationFailure;
        } else {
          cancellationSuccessCallback = null;
          cancellationFailureCallback = null;
        }
      }
      if (cancelled) {
        completeReadBoardGmaEngineRestore(
            cancellationSuccessCallback, cancellationFailureCallback);
        return;
      }
      if (!sendReadBoardGmaCommand(color, sessionAdmission)) {
        fail("ReadBoard GMA session admission failed");
      }
    }

    private void sendPreparationCommand(String command, Consumer<String> success) {
      new ReadBoardGmaPreparationCommand(this, command, success).start();
    }

    private void fail(String detail) {
      Consumer<String> cancellationFailureCallback;
      synchronized (readBoardGmaLock()) {
        if (readBoardGmaPreparation != this || engineStateUnrestored) {
          return;
        }
        readBoardGmaPreparation = null;
        cancellationFailureCallback = cancellationFailure;
      }
      failReadBoardGmaEngineRestore(detail);
      if (cancellationFailureCallback != null) {
        cancellationFailureCallback.accept(detail);
      }
    }

    private void requestCancellation(Runnable onSuccess, Consumer<String> onFailure) {
      synchronized (readBoardGmaLock()) {
        if (readBoardGmaPreparation != this || cancellationRequested) {
          return;
        }
        cancellationRequested = true;
        cancellationSuccess = onSuccess;
        cancellationFailure = onFailure;
      }
    }

    private boolean finishCancellationIfRequested() {
      Runnable onSuccess;
      Consumer<String> onFailure;
      synchronized (readBoardGmaLock()) {
        if (readBoardGmaPreparation != this || !cancellationRequested) {
          return false;
        }
        readBoardGmaPreparation = null;
        onSuccess = cancellationSuccess;
        onFailure = cancellationFailure;
      }
      completeReadBoardGmaEngineRestore(onSuccess, onFailure);
      return true;
    }
  }

  private final class ReadBoardGmaPreparationCommand {
    private final ReadBoardGmaPreparation preparation;
    private final String command;
    private final Consumer<String> success;
    private final AtomicBoolean settled = new AtomicBoolean(false);
    private final Runnable responseHandler = this::onResponse;
    private Timer timeout;

    private ReadBoardGmaPreparationCommand(
        ReadBoardGmaPreparation preparation, String command, Consumer<String> success) {
      this.preparation = preparation;
      this.command = command;
      this.success = success;
    }

    private void start() {
      try {
        sendCommand(command, responseHandler, this::onSendFailure, true, false);
      } catch (RuntimeException failure) {
        settleFailure(failure.getMessage());
        return;
      }
      if (settled.get()) {
        return;
      }
      timeout = new Timer("lizzie-readboard-gma-prepare-timeout", true);
      timeout.schedule(
          new TimerTask() {
            @Override
            public void run() {
              if (!settled.compareAndSet(false, true)) {
                return;
              }
              try {
                preparation.fail("parameter response timeout: " + command);
              } finally {
                retireTimedOutNormalCommand(responseHandler);
              }
            }
          },
          Math.max(1L, readBoardGmaRestoreResponseTimeoutMillis()));
    }

    private void onResponse() {
      if (!settled.compareAndSet(false, true)) {
        return;
      }
      cancelTimeout();
      if (isCurrentCommandResponseError()) {
        preparation.fail("parameter command failed: " + currentCommandResponseLine());
        return;
      }
      success.accept(currentCommandResponseLine());
    }

    private void onSendFailure(RuntimeException failure) {
      settleFailure(failure == null ? "parameter send failed: " + command : failure.getMessage());
    }

    private void settleFailure(String detail) {
      if (!settled.compareAndSet(false, true)) {
        return;
      }
      cancelTimeout();
      preparation.fail(detail);
    }

    private void cancelTimeout() {
      Timer currentTimeout = timeout;
      timeout = null;
      if (currentTimeout != null) {
        currentTimeout.cancel();
      }
    }
  }

  private record ReadBoardGmaRuntimeFailure(
      ReadBoardGmaSession.FailureCategory category, String detail) {}

  private static final class ReadBoardGmaRestoreBarrier {
    private final Runnable onSuccess;
    private final Consumer<ReadBoardGmaRuntimeFailure> onFailure;
    private final boolean sessionOwned;
    private final ReaderStreamBinding readBoardGmaResponseBinding;
    private int remaining;
    private boolean completed;
    private Timer timeout;

    private ReadBoardGmaRestoreBarrier(
        Runnable onSuccess,
        Consumer<ReadBoardGmaRuntimeFailure> onFailure,
        boolean sessionOwned) {
      this(onSuccess, onFailure, sessionOwned, null);
    }

    private ReadBoardGmaRestoreBarrier(
        Runnable onSuccess,
        Consumer<ReadBoardGmaRuntimeFailure> onFailure,
        boolean sessionOwned,
        ReaderStreamBinding readBoardGmaResponseBinding) {
      this.onSuccess = onSuccess;
      this.onFailure = onFailure;
      this.sessionOwned = sessionOwned;
      this.readBoardGmaResponseBinding = readBoardGmaResponseBinding;
    }

    private void register() {
      remaining++;
    }

    private boolean completeOne() {
      if (completed || remaining <= 0) {
        return false;
      }
      remaining--;
      return remaining == 0;
    }

    private boolean isEmpty() {
      return !completed && remaining == 0;
    }
  }

  public boolean isReadBoardGmaCapabilityKnown() {
    return endGetCommandList;
  }

  public boolean supportsReadBoardGma() {
    return supportsReadBoardGmaFixedLimits();
  }

  public boolean supportsReadBoardGmaFixedLimits() {
    return isKatago
        && endGetCommandList
        && commandLists.contains("kata-genmove_analyze")
        && commandLists.contains("kata-get-param")
        && commandLists.contains("kata-set-param");
  }

  public boolean supportsReadBoardGmaPondering() {
    return supportsReadBoardGmaFixedLimits()
        && !RemoteComputeConfig.isCustomWebSocketEngineCommand(engineCommand);
  }

  public boolean shouldShowReadBoardGmaUnsupportedPrompt() {
    if (readBoardGmaUnsupportedPromptShown) return false;
    readBoardGmaUnsupportedPromptShown = true;
    return true;
  }

  /**
   * The session admission registered by the ReadBoard helper for the next GMA hand. Consumed (and
   * cleared) by {@link #genmoveAnalyzeForReadBoard(String, int, int, boolean)} so that the
   * admission is delivered between reservation establishment and the physical command write.
   * Volatile because the registering and consuming threads are not guaranteed to be the same.
   */
  private volatile ReadBoardGmaSessionAdmission pendingReadBoardGmaSessionAdmission;

  /** Registers the session admission for the next {@code genmoveAnalyzeForReadBoard} call. */
  void setReadBoardGmaSessionAdmission(ReadBoardGmaSessionAdmission sessionAdmission) {
    pendingReadBoardGmaSessionAdmission = sessionAdmission;
  }

  private ReadBoardGmaSessionAdmission takeReadBoardGmaSessionAdmission() {
    ReadBoardGmaSessionAdmission sessionAdmission = pendingReadBoardGmaSessionAdmission;
    pendingReadBoardGmaSessionAdmission = null;
    return sessionAdmission;
  }

  public synchronized boolean genmoveAnalyzeForReadBoard(
      String color, int maxTimeSeconds, int maxVisits, boolean ponder) {
    return genmoveAnalyzeForReadBoard(
        color, maxTimeSeconds, maxVisits, ponder, takeReadBoardGmaSessionAdmission());
  }

  /**
   * Starts one ReadBoard GMA hand with an explicit session admission callback. Package-private: the
   * admission seam is consumed by the ReadBoard helper in the same package; external callers use
   * the four-argument form, which consumes the registered admission.
   */
  synchronized boolean genmoveAnalyzeForReadBoard(
      String color,
      int maxTimeSeconds,
      int maxVisits,
      boolean ponder,
      ReadBoardGmaSessionAdmission sessionAdmission) {
    if (isThinking) return false;
    if (ponder && RemoteComputeConfig.isCustomWebSocketEngineCommand(engineCommand)) return false;
    if (!beginReadBoardGmaSession()) return false;
    synchronized (readBoardGmaLock()) {
      if (readBoardGmaPreparation != null) {
        return false;
      }
      readBoardGmaPreparation =
          new ReadBoardGmaPreparation(
              color, maxTimeSeconds, maxVisits, ponder, sessionAdmission);
    }
    readBoardGmaPreparation.start();
    return true;
  }

  private static final class ReadBoardGmaResponseBinding {
    private final ReadBoard owner;
    private final Object identity;
    private final long generation;

    private ReadBoardGmaResponseBinding(ReadBoard owner, Object identity, long generation) {
      this.owner = owner;
      this.identity = identity;
      this.generation = generation;
    }
  }

  /**
   * GMA session admission callback. Leelaz invokes it after the GMA reservation exists and before
   * the physical {@code kata-genmove_analyze} command is written, so the session is admitted and
   * its terminal capability exists before any terminal line can be consumed. A throwing callback
   * rejects the hand fail-closed (the reservation is released and the engine is quarantined).
   */
  interface ReadBoardGmaSessionAdmission {
    void admit();
  }

  void bindReadBoardGmaResponseOwner(ReadBoard owner, Object identity, long generation) {
    readBoardGmaResponseBinding = new ReadBoardGmaResponseBinding(owner, identity, generation);
  }

  void bindReadBoardGmaResponseOwner(ReadBoard owner) {
    bindReadBoardGmaResponseOwner(
        owner,
        owner == null ? null : owner.currentReadBoardGmaIdentity(),
        owner == null ? -1L : owner.currentReadBoardGmaGeneration());
  }

  void clearReadBoardGmaResponseOwner(ReadBoard owner, Object identity, long generation) {
    ReadBoardGmaResponseBinding binding = readBoardGmaResponseBinding;
    if (binding != null
        && binding.owner == owner
        && binding.identity == identity
        && binding.generation == generation) {
      readBoardGmaResponseBinding = null;
    }
  }

  void clearReadBoardGmaResponseOwner(ReadBoard owner) {
    ReadBoardGmaResponseBinding binding = readBoardGmaResponseBinding;
    if (binding != null && binding.owner == owner) {
      readBoardGmaResponseBinding = null;
    }
  }

  private ReadBoardGmaResponseBinding currentReadBoardGmaResponseBinding() {
    ReadBoardGmaResponseBinding binding = readBoardGmaResponseBinding;
    if (binding != null) {
      return binding;
    }
    ReadBoard owner = Lizzie.frame == null ? null : Lizzie.frame.readBoard;
    return owner == null
        ? null
        : new ReadBoardGmaResponseBinding(
            owner, owner.currentReadBoardGmaIdentity(), owner.currentReadBoardGmaGeneration());
  }

  void activateReadBoardGmaAfterTracking(
      TrackingHandoffTarget target,
      String color,
      int maxTimeSeconds,
      int maxVisits,
      boolean ponder,
      TrackingHandoffActivation activation) {
    activateReadBoardGmaAfterTracking(
        target, color, maxTimeSeconds, maxVisits, ponder, activation, null);
  }

  void activateReadBoardGmaAfterTracking(
      TrackingHandoffTarget target,
      String color,
      int maxTimeSeconds,
      int maxVisits,
      boolean ponder,
      TrackingHandoffActivation activation,
      ReadBoardGmaSessionAdmission sessionAdmission) {
    if (target == null
        || activation == null
        || isThinking
        || (ponder && RemoteComputeConfig.isCustomWebSocketEngineCommand(engineCommand))
        || !beginReadBoardGmaSession(target)) {
      return;
    }
    boolean activated = false;
    try {
      synchronized (readBoardGmaLock()) {
        if (readBoardGmaPreparation != null) {
          return;
        }
        readBoardGmaPreparation =
            new ReadBoardGmaPreparation(
                color, maxTimeSeconds, maxVisits, ponder, sessionAdmission);
      }
      readBoardGmaPreparation.start();
      activated = activation.completeRetainedEngineMode();
    } finally {
      if (!activated) {
        retireReadBoardGmaSession();
      }
    }
  }

  private boolean sendReadBoardGmaCommand(
      String color, ReadBoardGmaSessionAdmission sessionAdmission) {
    if (sessionAdmission != null && !admitReadBoardGmaSession(sessionAdmission)) {
      return false;
    }
    StringBuilder command =
        new StringBuilder("kata-genmove_analyze ")
            .append(color)
            .append(" ")
            .append(getInterval())
            .append(addKataTag());
    sendCommandNoLeelaz2(command.toString());
    isThinking = true;
    LizzieFrame.menu.toggleEngineMenuStatus(false, true);
    return true;
  }

  private boolean admitReadBoardGmaSession(ReadBoardGmaSessionAdmission sessionAdmission) {
    try {
      sessionAdmission.admit();
      return true;
    } catch (RuntimeException ex) {
      ex.printStackTrace();
      failReadBoardGmaEngineRestore("ReadBoard GMA session admission failed: " + ex.getMessage());
      return false;
    }
  }

  public void setReadBoardGmaPondering(boolean ponder) {
    if (RemoteComputeConfig.isCustomWebSocketEngineCommand(engineCommand)) {
      return;
    }
    prepareReadBoardGmaRuntimeParam(readBoardGmaPondering, ponder ? "true" : "false");
  }

  public void restoreReadBoardGmaSearchLimitsIfNeeded() {
    restoreReadBoardGmaRuntimeParamIfNeeded(readBoardGmaMaxTime);
    restoreReadBoardGmaRuntimeParamIfNeeded(readBoardGmaMaxVisits);
  }

  public void restoreReadBoardGmaRuntimeSettingsIfNeeded() {
    completeReadBoardGmaEngineRestore(null, null);
  }

  public boolean cancelReadBoardGmaPreparationIfPending(
      Runnable onSuccess, Consumer<String> onFailure) {
    synchronized (readBoardGmaLock()) {
      if (readBoardGmaPreparation == null) {
        return false;
      }
      readBoardGmaPreparation.requestCancellation(onSuccess, onFailure);
      return true;
    }
  }

  public void completeReadBoardGmaEngineRestore(
      Runnable onSuccess, Consumer<String> onFailure) {
    ReadBoardGmaRestoreBarrier barrier;
    List<ReadBoardGmaRuntimeParam> paramsToRestore = new ArrayList<>();
    synchronized (readBoardGmaLock()) {
      if (readBoardGmaPreparation != null) {
        readBoardGmaPreparation.requestCancellation(onSuccess, onFailure);
        return;
      }
      if (engineStateUnrestored
          || readBoardGmaReservation == null
          || readBoardGmaRestoreBarrier != null) {
        return;
      }
      barrier =
          new ReadBoardGmaRestoreBarrier(
              onSuccess,
              failure -> {
                if (onFailure != null) {
                  onFailure.accept(failure.detail());
                }
              },
              false);
      readBoardGmaRestoreBarrier = barrier;
      registerReadBoardGmaRuntimeParamRestore(
          barrier, readBoardGmaPondering, paramsToRestore);
      registerReadBoardGmaRuntimeParamRestore(barrier, readBoardGmaMaxTime, paramsToRestore);
      registerReadBoardGmaRuntimeParamRestore(barrier, readBoardGmaMaxVisits, paramsToRestore);
    }
    startReadBoardGmaRestoreBarrierDispatch(barrier, paramsToRestore);
  }

  /**
   * Runs a restore barrier to its convergence: completes immediately when no parameters were
   * registered, otherwise arms the barrier timeout and dispatches each registered parameter's
   * restore command, guarding against a barrier that already completed. Shared by the legacy
   * runtime restore and the session-owned runtime participant so the convergence shapes cannot
   * diverge.
   */
  private void startReadBoardGmaRestoreBarrierDispatch(
      ReadBoardGmaRestoreBarrier barrier, List<ReadBoardGmaRuntimeParam> paramsToRestore) {
    ReadBoardGmaSession.FailureCategory bindingFailureCategory =
        readBoardGmaResponseBindingFailureCategory(barrier);
    if (bindingFailureCategory != null) {
      failReadBoardGmaRuntimeRestore(
          barrier,
          bindingFailureCategory,
          readBoardGmaBindingFailureDetail(bindingFailureCategory));
      return;
    }
    if (barrier.isEmpty()) {
      completeReadBoardGmaRuntimeRestore(barrier);
      return;
    }
    startReadBoardGmaRestoreBarrierTimeout(barrier);
    for (ReadBoardGmaRuntimeParam param : paramsToRestore) {
      synchronized (readBoardGmaLock()) {
        if (readBoardGmaRestoreBarrier != barrier || barrier.completed) {
          return;
        }
      }
      bindingFailureCategory = readBoardGmaResponseBindingFailureCategory(barrier);
      if (bindingFailureCategory != null) {
        failReadBoardGmaRuntimeRestore(
            barrier,
            bindingFailureCategory,
            readBoardGmaBindingFailureDetail(bindingFailureCategory));
        return;
      }
      restoreReadBoardGmaRuntimeParamIfNeeded(param);
    }
  }

  private ReadBoardGmaSession.FailureCategory readBoardGmaResponseBindingFailureCategory(
      ReadBoardGmaRestoreBarrier barrier) {
    ReaderStreamBinding expected = barrier.readBoardGmaResponseBinding;
    if (expected == null) {
      return null;
    }
    synchronized (engineArbitrationLock()) {
      ReaderStreamBinding current = readerStreamBinding;
      if (expected.terminated) {
        return ReadBoardGmaSession.FailureCategory.PROCESS_TERMINATED;
      }
      if (current == expected) {
        return null;
      }
      return current == null || current.terminated
          ? ReadBoardGmaSession.FailureCategory.PROCESS_TERMINATED
          : ReadBoardGmaSession.FailureCategory.ADMISSION_STALE;
    }
  }

  private String readBoardGmaBindingFailureDetail(
      ReadBoardGmaSession.FailureCategory category) {
    return category == ReadBoardGmaSession.FailureCategory.PROCESS_TERMINATED
        ? "ReadBoard GMA runtime participant engine process terminated"
        : "ReadBoard GMA runtime participant belongs to a stale engine incarnation";
  }


  private Object readBoardGmaLock() {
    Object lock = readBoardGmaLock;
    if (lock != null) {
      return lock;
    }
    synchronized (this) {
      if (readBoardGmaLock == null) {
        readBoardGmaLock = new Object();
      }
      return readBoardGmaLock;
    }
  }

  /**
   * The current GMA reservation instance, or {@code null}. The session adapter captures this object
   * as the reservation owner at session admission; the release capability validates against it
   * instead of looking up the current global engine.
   */
  Object currentReadBoardGmaReservation() {
    synchronized (readBoardGmaLock()) {
      return readBoardGmaReservation;
    }
  }

  /**
   * The current engine process incarnation: the reader stream binding, replaced whenever the
   * process restarts or the stream rebinds. GMA session capabilities bind this identity so that
   * stale events from a replaced engine process cannot advance the session.
   */
  Object currentEngineIncarnation() {
    return currentReaderStreamBinding();
  }

  boolean isCurrentEngineIncarnation(Object expectedIncarnation) {
    return expectedIncarnation != null && readerStreamBinding == expectedIncarnation;
  }

  /**
   * Lock-free identity probe for callers that already own an external serialization boundary.
   * Engine-game physical writers hold {@code normalCommandSendInProgress}, which prevents a
   * reader rebind until their output attempt settles.
   */
  boolean isCurrentLiveEngineIncarnation(Object expectedIncarnation) {
    ReaderStreamBinding current = readerStreamBinding;
    return expectedIncarnation != null
        && current == expectedIncarnation
        && !current.terminated
        && !current.readerShutdownRequested
        && started
        && isLoaded;
  }

  /**
   * Exact bootstrap writer probe. Unlike a runtime-live probe, this deliberately admits the
   * interval after streams are installed but before the name/version handshake marks the engine
   * loaded.
   */
  boolean isCurrentStartupEngineIncarnation(Object expectedIncarnation) {
    ReaderStreamBinding current = readerStreamBinding;
    return expectedIncarnation != null
        && current == expectedIncarnation
        && !current.terminated
        && current.output != null
        && outputStream == current.output;
  }

  /**
   * Captures the runtime parameter restore snapshot for the next GMA session: the parameters this
   * session's preparation overrode, in the canonical restore order. The GMA session module treats
   * the contents as opaque; this adapter reads them back when the runtime participant starts. A
   * parameter whose original value is not yet captured stays pending in the participant instead of
   * being treated as restored.
   */
  ReadBoardGmaSession.RuntimeSnapshot captureReadBoardGmaRuntimeSnapshot() {
    synchronized (readBoardGmaLock()) {
      List<ReadBoardGmaRuntimeParam> overridden = new ArrayList<>();
      if (readBoardGmaPondering.overridden) {
        overridden.add(readBoardGmaPondering);
      }
      if (readBoardGmaMaxTime.overridden) {
        overridden.add(readBoardGmaMaxTime);
      }
      if (readBoardGmaMaxVisits.overridden) {
        overridden.add(readBoardGmaMaxVisits);
      }
      return ReadBoardGmaSession.RuntimeSnapshot.of(overridden);
    }
  }

  /**
   * Starts the runtime parameter restore participant for the captured snapshot: aggregates the
   * matching per-parameter restore ACKs for this session and engine incarnation and reports the
   * aggregate result through the session capability exactly once. The participant reuses the legacy
   * restore barrier/ACK machinery; a parameter whose original value was not yet captured stays
   * pending (its capture callback dispatches the restore command) instead of being treated as
   * restored. A synchronous start rejection throws, which the session module converts into a typed
   * {@link ReadBoardGmaSession.FailureCategory#START_REJECTED} failure and fail-closes.
   */
  void startReadBoardGmaRuntimeParticipant(
      ReadBoardGmaSession session,
      ReadBoardGmaSession.RuntimeParticipantCapability capability,
      ReadBoardGmaSession.RuntimeSnapshot runtimeSnapshot) {
    new ReadBoardGmaRuntimeParticipant(session, capability, runtimeSnapshot).start();
  }

  /**
   * Requests the exactly-once release of the captured GMA reservation. The capability's captured
   * owner is validated against the current reservation — a stale or foreign capability cannot
   * release a replacement reservation. When the legacy runtime restore barrier or a parameter
   * preparation is still converging, the release is deferred to that convergence path, which closes
   * the same captured reservation on completion or failure.
   */
  void requestReadBoardGmaReservationRelease(
      ReadBoardGmaSession.ReservationReleaseCapability capability) {
    if (capability == null || capability.reservationOwner() == null) {
      return;
    }
    EngineModeReservation reservation;
    synchronized (readBoardGmaLock()) {
      if (readBoardGmaReservation != capability.reservationOwner()) {
        return;
      }
      if (readBoardGmaPreparation != null || readBoardGmaRestoreBarrier != null) {
        return;
      }
      reservation = readBoardGmaReservation;
      readBoardGmaReservation = null;
    }
    if (reservation != null) {
      reservation.close();
    }
  }

  private boolean beginReadBoardGmaSession() {
    synchronized (engineArbitrationLock()) {
      synchronized (readBoardGmaLock()) {
        if (isWebTrialEngineBusy() || engineStateUnrestored || readBoardGmaRestoreBarrier != null) {
          return false;
        }
        if (readBoardGmaReservation != null) {
          return true;
        }
        Object owner = Thread.currentThread();
        if (!beginExclusiveGtpLifecycleTransition(owner)) {
          return false;
        }
        readBoardGmaReservation = new EngineModeReservation(this, owner);
        return true;
      }
    }
  }

  private boolean beginReadBoardGmaSession(TrackingHandoffTarget target) {
    synchronized (engineArbitrationLock()) {
      synchronized (readBoardGmaLock()) {
        if (isWebTrialEngineBusy()
            || engineStateUnrestored
            || hasLifecycleCompletionLocked()
            || readBoardGmaRestoreBarrier != null) {
          return false;
        }
        if (readBoardGmaReservation != null) {
          return false;
        }
        TrackingHandoffClaim claim = trackingHandoffGate;
        if (claim == null
            || claim.target != target
            || claim.kind != TrackingHandoffKind.RETAINED_ENGINE_MODE
            || claim.state.get() != TrackingHandoffState.ACTIVATING
            || exclusiveGtpLifecycleTransition) {
          return false;
        }
        Object owner = Thread.currentThread();
        exclusiveGtpLifecycleTransition = true;
        exclusiveGtpLifecycleOwner = owner;
        exclusiveGtpLifecycleDepth = 1;
        readBoardGmaReservation = new EngineModeReservation(this, owner);
        return true;
      }
    }
  }

  void retireReadBoardGmaSession() {
    ReadBoard readBoard = Lizzie.frame == null ? null : Lizzie.frame.readBoard;
    if (readBoard != null && readBoard.retireReadBoardGmaSession()) {
      return;
    }
    if (readBoard != null) {
      readBoard.abandonReadBoardGmaSession(this, "session-retired");
    }
    EngineModeReservation reservation;
    Timer barrierTimeout = null;
    boolean quarantined;
    synchronized (readBoardGmaLock()) {
      boolean dirtyRuntimeState =
          readBoardGmaPreparation != null
              || readBoardGmaRestoreBarrier != null
              || hasReadBoardGmaRuntimeState(readBoardGmaPondering)
              || hasReadBoardGmaRuntimeState(readBoardGmaMaxTime)
              || hasReadBoardGmaRuntimeState(readBoardGmaMaxVisits);
      if (readBoardGmaRestoreBarrier != null) {
        barrierTimeout = readBoardGmaRestoreBarrier.timeout;
        readBoardGmaRestoreBarrier.completed = true;
      }
      if (dirtyRuntimeState) {
        engineStateUnrestored = true;
      }
      quarantined = dirtyRuntimeState;
      readBoardGmaPreparation = null;
      readBoardGmaRestoreBarrier = null;
      clearReadBoardGmaSearchLimitSnapshots();
      reservation = readBoardGmaReservation;
      readBoardGmaReservation = null;
      isThinking = false;
      isInputCommand = false;
    }
    if (barrierTimeout != null) {
      barrierTimeout.cancel();
    }
    if (reservation != null) {
      reservation.close();
    }
    if (quarantined) {
      invalidateReadBoardTrackingEligibility(
          ReadBoardTrackingEligibilityAdapter.Reason.ENGINE_UNRESTORED);
    }
  }

  private boolean hasReadBoardGmaRuntimeState(ReadBoardGmaRuntimeParam param) {
    return param.snapshotRequested || param.overridden || param.restorePending;
  }

  private void invalidateReadBoardTrackingEligibility(
      ReadBoardTrackingEligibilityAdapter.Reason reason) {
    ReadBoard readBoard = Lizzie.frame == null ? null : Lizzie.frame.readBoard;
    if (readBoard != null) {
      readBoard.invalidateTrackingEligibilityForEngineState(reason);
    }
  }

  private void registerReadBoardGmaRuntimeParamRestore(
      ReadBoardGmaRestoreBarrier barrier,
      ReadBoardGmaRuntimeParam param,
      List<ReadBoardGmaRuntimeParam> paramsToRestore) {
    if (!param.overridden || param.restoreTracked) {
      return;
    }
    param.restoreTracked = true;
    barrier.register();
    paramsToRestore.add(param);
  }


  private void prepareReadBoardGmaRuntimeParam(ReadBoardGmaRuntimeParam param, String value) {
    boolean requestSnapshot;
    synchronized (readBoardGmaLock()) {
      param.restorePending = false;
      requestSnapshot = !param.snapshotRequested;
      param.snapshotRequested = true;
      param.overridden = true;
      param.revision++;
    }
    if (requestSnapshot) {
      captureReadBoardGmaOriginalParam(param);
    }
    sendCommandNoLeelaz2("kata-set-param " + param.name + " " + value);
  }

  private void captureReadBoardGmaOriginalParam(ReadBoardGmaRuntimeParam param) {
    sendCommandNoLeelaz2(
        "kata-get-param " + param.name,
        () -> {
          String value = parseKataGetParamValue(currentCommandResponseLine());
          boolean restorePending;
          synchronized (readBoardGmaLock()) {
            if (value.isEmpty() || engineStateUnrestored) {
              return;
            }
            param.originalValue = value;
            restorePending = param.restorePending;
          }
          if (restorePending) {
            restoreReadBoardGmaRuntimeParamIfNeeded(param);
          }
        });
  }

  private void startReadBoardGmaRestoreBarrierTimeout(ReadBoardGmaRestoreBarrier barrier) {
    Timer timeout = new Timer("lizzie-readboard-gma-restore-barrier-timeout", true);
    timeout.schedule(
        new TimerTask() {
          @Override
          public void run() {
            failReadBoardGmaRuntimeRestore(
                barrier,
                ReadBoardGmaSession.FailureCategory.TIMEOUT,
                "restore response timeout");
          }
        },
        Math.max(1L, readBoardGmaRestoreResponseTimeoutMillis()));
    synchronized (readBoardGmaLock()) {
      if (readBoardGmaRestoreBarrier != barrier || barrier.completed) {
        timeout.cancel();
        return;
      }
      barrier.timeout = timeout;
    }
  }

  private void restoreReadBoardGmaRuntimeParamIfNeeded(ReadBoardGmaRuntimeParam param) {
    ReadBoardGmaRestoreBarrier barrier;
    String originalValue;
    long revision;
    synchronized (readBoardGmaLock()) {
      if (engineStateUnrestored || readBoardGmaReservation == null || !param.overridden) {
        param.restorePending = false;
        return;
      }
      if (param.originalValue.isEmpty()) {
        param.restorePending = param.snapshotRequested;
        return;
      }
      barrier = readBoardGmaRestoreBarrier;
      if (barrier != null) {
        if (!param.restoreTracked || param.barrierRestoreDispatched == barrier) {
          return;
        }
        param.barrierRestoreDispatched = barrier;
      } else {
        if (param.standaloneRestoreRevision == param.revision) {
          return;
        }
        param.standaloneRestoreRevision = param.revision;
      }
      param.restorePending = false;
      originalValue = param.originalValue;
      revision = param.revision;
    }
    sendAcknowledgedReadBoardGmaRestoreCommand(barrier, param, revision, originalValue);
  }

  private void sendAcknowledgedReadBoardGmaRestoreCommand(
      ReadBoardGmaRestoreBarrier barrier,
      ReadBoardGmaRuntimeParam param,
      long revision,
      String originalValue) {
    new ReadBoardGmaTrackedCommand(barrier, param, revision, originalValue).start();
  }

  private void acknowledgeReadBoardGmaRuntimeRestore(ReadBoardGmaRestoreBarrier barrier) {
    boolean completed;
    synchronized (readBoardGmaLock()) {
      if (readBoardGmaRestoreBarrier != barrier || barrier.completed) {
        return;
      }
      completed = barrier.completeOne();
    }
    if (completed) {
      completeReadBoardGmaRuntimeRestore(barrier);
    }
  }

  private void failReadBoardGmaRuntimeRestore(
      ReadBoardGmaRestoreBarrier barrier,
      ReadBoardGmaSession.FailureCategory category,
      String detail) {
    if (category == ReadBoardGmaSession.FailureCategory.ADMISSION_STALE) {
      rejectStaleReadBoardGmaRuntimeRestore(barrier, detail);
      return;
    }
    EngineModeReservation reservation;
    Consumer<ReadBoardGmaRuntimeFailure> failure;
    Timer timeout;
    synchronized (readBoardGmaLock()) {
      if (readBoardGmaRestoreBarrier != barrier || barrier.completed) {
        return;
      }
      barrier.completed = true;
      readBoardGmaRestoreBarrier = null;
      engineStateUnrestored = true;
      reservation = barrier.sessionOwned ? null : readBoardGmaReservation;
      if (!barrier.sessionOwned) {
        readBoardGmaReservation = null;
      }
      failure = barrier.onFailure;
      timeout = barrier.timeout;
      barrier.timeout = null;
    }
    if (timeout != null) {
      timeout.cancel();
    }
    rememberRecentLine(recentStderrLines, "ReadBoard GMA engine restore failed: " + detail);
    resetGtpCommandStateAfterRestoreFailure(detail);
    invalidateReadBoardTrackingEligibility(
        ReadBoardTrackingEligibilityAdapter.Reason.ENGINE_UNRESTORED);
    if (reservation != null) {
      reservation.close();
    }
    if (failure != null) {
      failure.accept(new ReadBoardGmaRuntimeFailure(category, detail));
    }
  }

  private void rejectStaleReadBoardGmaRuntimeRestore(
      ReadBoardGmaRestoreBarrier barrier, String detail) {
    Consumer<ReadBoardGmaRuntimeFailure> failure;
    Timer timeout;
    synchronized (readBoardGmaLock()) {
      if (readBoardGmaRestoreBarrier != barrier || barrier.completed) {
        return;
      }
      barrier.completed = true;
      readBoardGmaRestoreBarrier = null;
      clearReadBoardGmaSearchLimitSnapshots();
      failure = barrier.onFailure;
      timeout = barrier.timeout;
      barrier.timeout = null;
    }
    if (timeout != null) {
      timeout.cancel();
    }
    if (failure != null) {
      failure.accept(
          new ReadBoardGmaRuntimeFailure(
              ReadBoardGmaSession.FailureCategory.ADMISSION_STALE, detail));
    }
  }

  /**
   * Quarantines a participant failure owned by {@link ReadBoardGmaSession}. This retires any
   * session runtime barrier but deliberately leaves the captured reservation for the session's
   * ordered release effect.
   */
  void quarantineSessionOwnedReadBoardGmaFailure(String detail) {
    Timer timeout = null;
    synchronized (readBoardGmaLock()) {
      engineStateUnrestored = true;
      ReadBoardGmaRestoreBarrier barrier = readBoardGmaRestoreBarrier;
      if (barrier != null && barrier.sessionOwned && !barrier.completed) {
        barrier.completed = true;
        readBoardGmaRestoreBarrier = null;
        timeout = barrier.timeout;
        barrier.timeout = null;
      }
    }
    if (timeout != null) {
      timeout.cancel();
    }
    rememberRecentLine(recentStderrLines, "ReadBoard GMA engine restore failed: " + detail);
    resetGtpCommandStateAfterRestoreFailure(detail);
    invalidateReadBoardTrackingEligibility(
        ReadBoardTrackingEligibilityAdapter.Reason.ENGINE_UNRESTORED);
  }

  public void failReadBoardGmaEngineRestore(String detail) {
    ReadBoard readBoard = Lizzie.frame == null ? null : Lizzie.frame.readBoard;
    ReadBoardGmaRestoreBarrier barrier;
    EngineModeReservation reservation;
    synchronized (readBoardGmaLock()) {
      barrier = readBoardGmaRestoreBarrier;
      if (barrier != null) {
        engineStateUnrestored = true;
        reservation = null;
      } else {
        reservation = readBoardGmaReservation;
        if (reservation == null) {
          if (readBoard != null) {
            readBoard.abandonReadBoardGmaSession(this, "engine-restore-failed");
          }
          return;
        }
        engineStateUnrestored = true;
        readBoardGmaReservation = null;
      }
    }
    if (readBoard != null) {
      // Quarantine is visible before the binding is cleared, so a concurrent restore request
      // cannot capture the still-owned reservation during this transition.
      readBoard.abandonReadBoardGmaSession(this, "engine-restore-failed");
    }
    if (barrier != null) {
      failReadBoardGmaRuntimeRestore(
          barrier, ReadBoardGmaSession.FailureCategory.SEND_FAILED, detail);
      return;
    }
    rememberRecentLine(recentStderrLines, "ReadBoard GMA engine restore failed: " + detail);
    resetGtpCommandStateAfterRestoreFailure(detail);
    invalidateReadBoardTrackingEligibility(
        ReadBoardTrackingEligibilityAdapter.Reason.ENGINE_UNRESTORED);
    reservation.close();
  }

  private void completeReadBoardGmaRuntimeRestore(ReadBoardGmaRestoreBarrier barrier) {
    EngineModeReservation reservation = null;
    Runnable completion = null;
    Timer timeout = null;
    Consumer<ReadBoardGmaRuntimeFailure> staleFailure = null;
    ReadBoardGmaSession.FailureCategory bindingFailureCategory;
    synchronized (engineArbitrationLock()) {
      synchronized (readBoardGmaLock()) {
        if (readBoardGmaRestoreBarrier != barrier
            || barrier.completed
            || barrier.remaining != 0) {
          return;
        }
        bindingFailureCategory = readBoardGmaResponseBindingFailureCategory(barrier);
        if (bindingFailureCategory == ReadBoardGmaSession.FailureCategory.PROCESS_TERMINATED) {
          // Leave the live barrier for the fail-closed termination path below.
        } else {
          barrier.completed = true;
          readBoardGmaRestoreBarrier = null;
          clearReadBoardGmaSearchLimitSnapshots();
          if (bindingFailureCategory == ReadBoardGmaSession.FailureCategory.ADMISSION_STALE) {
            staleFailure = barrier.onFailure;
          } else {
            reservation = barrier.sessionOwned ? null : readBoardGmaReservation;
            if (!barrier.sessionOwned) {
              readBoardGmaReservation = null;
            }
            completion = barrier.onSuccess;
          }
          timeout = barrier.timeout;
          barrier.timeout = null;
        }
      }
    }
    if (bindingFailureCategory == ReadBoardGmaSession.FailureCategory.PROCESS_TERMINATED) {
      failReadBoardGmaRuntimeRestore(
          barrier,
          bindingFailureCategory,
          readBoardGmaBindingFailureDetail(bindingFailureCategory));
      return;
    }
    if (timeout != null) {
      timeout.cancel();
    }
    if (reservation != null) {
      reservation.close();
    }
    if (staleFailure != null) {
      staleFailure.accept(
          new ReadBoardGmaRuntimeFailure(
              ReadBoardGmaSession.FailureCategory.ADMISSION_STALE,
              readBoardGmaBindingFailureDetail(
                  ReadBoardGmaSession.FailureCategory.ADMISSION_STALE)));
      return;
    }
    if (completion != null) {
      completion.run();
    }
  }
  void confirmBoardSynchronization(Runnable onSuccess, Consumer<String> onFailure) {
    confirmBoardSynchronization(null, onSuccess, onFailure);
  }

  /**
   * Fences the final restore point against the captured authority and, when a distinct captured
   * mirror exists, against that exact mirror as well. Ready, ponder and analyze only follow after
   * every leg acknowledges; any leg send failure, error response or timeout fails closed.
  */
  void confirmBoardSynchronization(Leelaz mirror, Runnable onSuccess, Consumer<String> onFailure) {
    new BoardSynchronizationConfirmation(mirror, onSuccess, onFailure).start();
  }

  private interface BoardSynchronizationResponseHandler extends Runnable {}

  private final class BoardSynchronizationConfirmation {
    private final Leelaz mirror;
    private final Runnable onSuccess;
    private final Consumer<String> onFailure;
    private final AtomicBoolean settled = new AtomicBoolean(false);
    private final AtomicInteger remainingLegs;
    private final AtomicBoolean authorityLegDispatched = new AtomicBoolean(false);
    private final AtomicBoolean mirrorLegDispatched = new AtomicBoolean(false);
    private final AtomicBoolean authorityLegConfirmed = new AtomicBoolean(false);
    private final AtomicBoolean mirrorLegConfirmed = new AtomicBoolean(false);
    private final RestartBootstrapReceipt restartReceipt =
        restartBootstrapReceiptContext.get();
    private final Runnable responseHandler =
        (BoardSynchronizationResponseHandler) this::onResponse;
    private final Runnable mirrorResponseHandler;
    private Timer timeout;

    private BoardSynchronizationConfirmation(
        Leelaz mirror, Runnable onSuccess, Consumer<String> onFailure) {
      this.mirror = mirror;
      this.onSuccess = onSuccess;
      this.onFailure = onFailure;
      this.mirrorResponseHandler =
          mirror == null ? null : (BoardSynchronizationResponseHandler) this::onMirrorResponse;
      this.remainingLegs = new AtomicInteger(mirror == null ? 1 : 2);
    }

    private void start() {
      Timer confirmationTimeout = new Timer("lizzie-board-sync-confirmation-timeout", true);
      timeout = confirmationTimeout;
      TimerTask timeoutTask =
          new TimerTask() {
            @Override
            public void run() {
              settleFailure("board synchronization response timeout");
            }
          };
      try {
        authorityLegDispatched.set(true);
        sendCommand("name", responseHandler, this::onSendFailure, true, false);
      } catch (RuntimeException ex) {
        settleFailure(ex.getMessage());
        return;
      }
      if (mirror != null && !settled.get()) {
        try {
          mirrorLegDispatched.set(true);
          mirror.sendCommand(
              "name", mirrorResponseHandler, this::onMirrorSendFailure, true, false);
        } catch (RuntimeException ex) {
          settleFailure(ex.getMessage());
          return;
        }
      }
      if (!settled.get()) {
        scheduleBoardSynchronizationTimeout(
            confirmationTimeout,
            timeoutTask,
            Math.max(1L, readBoardGmaRestoreResponseTimeoutMillis()),
            settled);
      }
    }

    private void onResponse() {
      if (settled.get()) {
        return;
      }
      if (isCurrentCommandResponseError()) {
        settleFailure("board synchronization failed: " + currentCommandResponseLine());
        return;
      }
      authorityLegConfirmed.set(true);
      completeLeg();
    }

    private void onMirrorResponse() {
      if (settled.get()) {
        return;
      }
      if (mirror.isCurrentCommandResponseError()) {
        settleFailure("board synchronization failed: " + mirror.currentCommandResponseLine());
        return;
      }
      mirrorLegConfirmed.set(true);
      completeLeg();
    }

    private void completeLeg() {
      if (remainingLegs.decrementAndGet() != 0) {
        return;
      }
      if (!settled.compareAndSet(false, true)) {
        return;
      }
      cancelTimeout();
      if (onSuccess != null) {
        runWithRestartBootstrapReceipt(restartReceipt, onSuccess);
      }
    }

    private void onSendFailure(RuntimeException failure) {
      settleFailure(failure == null ? "board synchronization send failed" : failure.getMessage());
    }

    private void onMirrorSendFailure(RuntimeException failure) {
      settleFailure(
          failure == null ? "board synchronization mirror send failed" : failure.getMessage());
    }

    /**
     * Linearized failure settlement: atomically claim the settlement, then retire every
     * dispatched leg handler, mark every captured endpoint that never confirmed unavailable, and
     * only then invoke the failure callback so the completion gates release after all old
     * handlers have retired.
     */
    private void settleFailure(String detail) {
      if (!settled.compareAndSet(false, true)) {
        return;
      }
      cancelTimeout();
      try {
        retireDispatchedLegs();
      } finally {
        markUnconfirmedCapturedEndpointsUnavailable();
        runFailure(detail);
      }
    }

    private void retireDispatchedLegs() {
      if (authorityLegDispatched.get()) {
        retireTimedOutNormalCommand(responseHandler);
      }
      if (mirror != null && mirrorLegDispatched.get()) {
        mirror.retireTimedOutNormalCommand(mirrorResponseHandler);
      }
    }

    /**
     * Marks every captured endpoint that never acknowledged its fence unavailable. The failed
     * endpoint is always unconfirmed; when no leg confirmed yet, the whole captured set fails
     * closed atomically.
     */
    private void markUnconfirmedCapturedEndpointsUnavailable() {
      if (!authorityLegConfirmed.get()) {
        isLoaded = false;
      }
      if (mirror != null && !mirrorLegConfirmed.get()) {
        mirror.isLoaded = false;
      }
    }

    private void runFailure(String detail) {
      if (onFailure != null) {
        runWithRestartBootstrapReceipt(restartReceipt, () -> onFailure.accept(detail));
      }
    }

    private void cancelTimeout() {
      Timer currentTimeout = timeout;
      timeout = null;
      if (currentTimeout != null) {
        currentTimeout.cancel();
      }
    }
  }

  static void scheduleBoardSynchronizationTimeout(
      Timer timer, TimerTask task, long delayMillis, AtomicBoolean settled) {
    try {
      timer.schedule(task, delayMillis);
    } catch (IllegalStateException ex) {
      if (!settled.get()) {
        throw ex;
      }
    }
  }

  protected long readBoardGmaRestoreResponseTimeoutMillis() {
    return FOREGROUND_RELEASE_STOP_TIMEOUT_MILLIS;
  }

  private final class ReadBoardGmaTrackedCommand {
    private final ReadBoardGmaRestoreBarrier barrier;
    private final ReadBoardGmaRuntimeParam param;
    private final long revision;
    private final String originalValue;
    private final AtomicBoolean settled = new AtomicBoolean(false);
    private final Runnable responseHandler = this::onResponse;
    private Timer timeout;

    private ReadBoardGmaTrackedCommand(
        ReadBoardGmaRestoreBarrier barrier,
        ReadBoardGmaRuntimeParam param,
        long revision,
        String originalValue) {
      this.barrier = barrier;
      this.param = param;
      this.revision = revision;
      this.originalValue = originalValue;
    }

    private void start() {
      boolean accepted;
      try {
        accepted =
            sendCommand(
                "kata-set-param " + param.name + " " + originalValue,
                responseHandler,
                this::onSendFailure,
                true,
                false,
                TrackingReleaseReason.ORDINARY_OPERATION,
                null,
                false,
                barrier == null ? null : barrier.readBoardGmaResponseBinding);
      } catch (RuntimeException ex) {
        settleFailure(ex.getMessage());
        return;
      }
      if (!accepted) {
        ReadBoardGmaSession.FailureCategory bindingFailureCategory =
            barrier == null ? null : readBoardGmaResponseBindingFailureCategory(barrier);
        if (bindingFailureCategory != null) {
          settleFailure(
              readBoardGmaBindingFailureDetail(bindingFailureCategory), bindingFailureCategory);
        } else {
          settleFailure("ReadBoard GMA runtime restore command was rejected");
        }
        return;
      }
      if (settled.get()) {
        return;
      }
      timeout = new Timer("lizzie-readboard-gma-restore-timeout", true);
      timeout.schedule(
          new TimerTask() {
            @Override
            public void run() {
              if (!settled.compareAndSet(false, true)) {
                return;
              }
              try {
                failRestore(
                    "restore response timeout: " + param.name,
                    ReadBoardGmaSession.FailureCategory.TIMEOUT);
              } finally {
                retireTimedOutNormalCommand(responseHandler);
              }
            }
          },
          Math.max(1L, readBoardGmaRestoreResponseTimeoutMillis()));
    }

    private void onResponse() {
      if (!settled.compareAndSet(false, true)) {
        return;
      }
      cancelTimeout();
      if (isCurrentCommandResponseError()) {
        failRestore(
            "restore command failed: " + currentCommandResponseLine(),
            ReadBoardGmaSession.FailureCategory.GTP_ERROR);
        return;
      }
      if (barrier != null) {
        acknowledgeReadBoardGmaRuntimeRestore(barrier);
        return;
      }
      synchronized (readBoardGmaLock()) {
        if (!engineStateUnrestored && param.revision == revision) {
          param.overridden = false;
          param.restorePending = false;
        }
      }
    }

    private void onSendFailure(RuntimeException failure) {
      String detail =
          failure == null ? "restore send failed: " + param.name : failure.getMessage();
      ReadBoardGmaSession.FailureCategory bindingFailureCategory =
          barrier == null ? null : readBoardGmaResponseBindingFailureCategory(barrier);
      ReadBoardGmaSession.FailureCategory category =
          bindingFailureCategory == null
              ? ReadBoardGmaSession.FailureCategory.SEND_FAILED
              : bindingFailureCategory;
      if (bindingFailureCategory != null) {
        detail = readBoardGmaBindingFailureDetail(bindingFailureCategory);
      }
      settleFailure(detail, category);
    }

    private void settleFailure(String detail) {
      settleFailure(detail, ReadBoardGmaSession.FailureCategory.SEND_FAILED);
    }

    private void settleFailure(String detail, ReadBoardGmaSession.FailureCategory category) {
      if (!settled.compareAndSet(false, true)) {
        return;
      }
      cancelTimeout();
      failRestore(detail, category);
    }

    private void failRestore(
        String detail, ReadBoardGmaSession.FailureCategory category) {
      if (barrier != null) {
        failReadBoardGmaRuntimeRestore(barrier, category, detail);
      } else {
        failReadBoardGmaEngineRestore(detail);
      }
    }

    private void cancelTimeout() {
      Timer currentTimeout = timeout;
      timeout = null;
      if (currentTimeout != null) {
        currentTimeout.cancel();
      }
    }
  }

  /**
   * Runtime parameter restore participant adapter — the session-typed seam between the narrow
   * {@link ReadBoardGmaSession} module and the Leelaz runtime restore barrier/ACK machinery.
   *
   * <p>The participant aggregates one {@link ReadBoardGmaRestoreBarrier} over the parameters
   * captured in the session's runtime snapshot: every parameter's {@code kata-set-param} restore
   * command must receive its matching successful ACK before the participant reports success. With
   * no parameters to restore the participant completes immediately. An uncaptured original value
   * leaves the parameter pending until its {@code kata-get-param} capture arrives; a sent command
   * is never treated as success. Any restore failure (GTP error, send failure, timeout, process
   * death) fails the participant closed with a stable {@link ReadBoardGmaSession.FailureCategory}.
   *
   * <p>The legacy restore barrier closes its reservation at its own completion boundary. A
   * session-owned barrier clears parameter snapshots but retains the captured reservation until the
   * session terminal effects finish the deferred exact restore and issue the capability-bound
   * release. On failure the engine is marked unrestored and the session failure path closes the
   * retained reservation. The session terminal effects publish, handle, continue, and request the
   * release exactly once; continuation is scheduled after release so a fresh hand cannot race the
   * old reservation. Duplicates and late events are absorbed by capability guards and barrier
   * identity checks.
   */
  private final class ReadBoardGmaRuntimeParticipant {
    private final ReadBoardGmaSession session;
    private final ReadBoardGmaSession.RuntimeParticipantCapability capability;
    private final ReadBoardGmaSession.RuntimeSnapshot runtimeSnapshot;

    private ReadBoardGmaRuntimeParticipant(
        ReadBoardGmaSession session,
        ReadBoardGmaSession.RuntimeParticipantCapability capability,
        ReadBoardGmaSession.RuntimeSnapshot runtimeSnapshot) {
      this.session = session;
      this.capability = capability;
      this.runtimeSnapshot = runtimeSnapshot;
    }

    /**
     * Builds the restore barrier for the captured parameters and dispatches their restore commands.
     * A synchronous rejection (stale engine incarnation, no captured reservation, unrestored engine
     * state, or an already converging restore) throws so the session fail-closes through the typed
     * start rejection contract with zero physical side effects; a stale session must never dispatch
     * restore commands on a replacement engine.
     */
    private void start() {
      ReaderStreamBinding readBoardGmaResponseBinding =
          session.engineIncarnation() instanceof ReaderStreamBinding binding ? binding : null;
      if (readBoardGmaResponseBinding == null) {
        throw new ReadBoardGmaSession.ParticipantStartFailure(
            ReadBoardGmaSession.FailureCategory.ADMISSION_STALE,
            "ReadBoard GMA runtime participant has no engine incarnation binding");
      }
      ReadBoardGmaRestoreBarrier barrier;
      List<ReadBoardGmaRuntimeParam> paramsToRestore = new ArrayList<>();
      synchronized (readBoardGmaLock()) {
        if (currentEngineIncarnation() != readBoardGmaResponseBinding) {
          throw new ReadBoardGmaSession.ParticipantStartFailure(
              ReadBoardGmaSession.FailureCategory.ADMISSION_STALE,
              "ReadBoard GMA runtime participant belongs to a stale engine incarnation");
        }
        if (readBoardGmaReservation == null) {
          throw new IllegalStateException(
              "ReadBoard GMA runtime participant has no captured reservation");
        }
        if (engineStateUnrestored || readBoardGmaRestoreBarrier != null) {
          throw new IllegalStateException(
              "ReadBoard GMA engine state does not admit the runtime participant");
        }
        barrier =
            new ReadBoardGmaRestoreBarrier(
                this::completeSucceeded, this::completeFailed, true, readBoardGmaResponseBinding);
        readBoardGmaRestoreBarrier = barrier;
        for (Object parameter : runtimeSnapshot.parameters()) {
          registerReadBoardGmaRuntimeParamRestore(
              barrier, (ReadBoardGmaRuntimeParam) parameter, paramsToRestore);
        }
      }
      startReadBoardGmaRestoreBarrierDispatch(barrier, paramsToRestore);
    }

    private void completeSucceeded() {
      session.completeRuntime(capability, new ReadBoardGmaSession.ParticipantResult.Succeeded());
    }

    private void completeFailed(ReadBoardGmaRuntimeFailure failure) {
      session.completeRuntime(
          capability,
          new ReadBoardGmaSession.ParticipantResult.Failed(
              new ReadBoardGmaSession.ParticipantFailure(
                  failure.category(), session.engineIncarnation(), failure.detail())));
    }
  }

  private String parseKataGetParamValue(String line) {
    if (line == null) {
      return "";
    }
    String trimmed = line.trim();
    if (!trimmed.startsWith("=")) {
      return "";
    }
    String value = trimmed.substring(1).trim();
    int separator = value.indexOf(' ');
    if (separator > 0
        && value.substring(0, separator).chars()
            .allMatch(character -> character >= '0' && character <= '9')) {
      return value.substring(separator + 1).trim();
    }
    return value;
  }

  private void clearReadBoardGmaSearchLimitSnapshots() {
    clearReadBoardGmaRuntimeParam(readBoardGmaMaxTime);
    clearReadBoardGmaRuntimeParam(readBoardGmaMaxVisits);
    clearReadBoardGmaRuntimeParam(readBoardGmaPondering);
  }

  private void clearReadBoardGmaRuntimeParam(ReadBoardGmaRuntimeParam param) {
    param.originalValue = "";
    param.snapshotRequested = false;
    param.overridden = false;
    param.restorePending = false;
    param.restoreTracked = false;
    param.revision = 0L;
    param.standaloneRestoreRevision = -1L;
    param.barrierRestoreDispatched = null;
  }

  private void sendPlayingAgainstHumanTimeLeftBeforeGenmove() {
    if (Lizzie.frame == null || !Lizzie.frame.isPlayingAgainstLeelaz) return;
    if (Lizzie.engineManager == null
        || Lizzie.engineManager.playingAgainstHumanEngineCountDown == null) return;
    if (this != Lizzie.leelaz) return;
    Lizzie.engineManager.playingAgainstHumanEngineCountDown.sendTimeLeft(false);
  }

  public void genmoveForPk(String color) {
    EngineManager.EngineGamePrimaryContext currentGame =
        EngineManager.captureEngineGamePrimaryContext();
    if (currentGame == null) {
      return;
    }
    genmoveForPk(color, currentGame.transaction);
  }

  boolean genmoveForPk(
      String color, EngineManager.EngineGameTransaction transaction) {
    return genmoveForPk(color, transaction, null);
  }

  boolean genmoveForPk(String color, EngineManager.EngineGamePostMoveToken turn) {
    return genmoveForPk(color, turn == null ? null : turn.transaction, turn);
  }

  private boolean genmoveForPk(
      String color,
      EngineManager.EngineGameTransaction transaction,
      EngineManager.EngineGamePostMoveToken turn) {
    if (rejectNewExclusiveWorkDuringGtpLease()) return false;
    if (LizzieFrame.toolbar.isPkStop) {
      LizzieFrame.toolbar.isPkGenmoveStop = true;
      if (color.equals("B")) {
        LizzieFrame.toolbar.isPkStopGenmoveB = true;
      } else {
        LizzieFrame.toolbar.isPkStopGenmoveB = false;
      }
      Lizzie.engineManager
          .engineList
          .get(EngineManager.engineGameInfo.whiteEngineIndex)
          .nameCmdfornoponder();
      Lizzie.engineManager
          .engineList
          .get(EngineManager.engineGameInfo.blackEngineIndex)
          .nameCmdfornoponder();
      return false;
    }
    String command =
        (this.isKatago
            ? ("kata-genmove_analyze " + color + " " + getIntervalForGenmovePk() + addKataTag())
            : (this.isSayuri
                ? ("genmove_analyze " + color + " " + getInterval())
                : (this.isSai || this.isLeela
                    ? ("lz-genmove_analyze " + color + " " + getInterval())
                    : ("genmove " + color))));
    /*
     * We don't support displaying this while playing, so no reason to request it
     * (for now) if (isPondering) { command = "lz-genmove_analyze " + color + " 10";
     * }
     */
    // bestMoves = new ArrayList<>();
    // canGetGenmoveInfo = true;
    ReaderStreamBinding binding = currentReaderStreamBinding();
    EngineManager.EngineGameMoveResponseContext responseContext =
        turn == null
            ? EngineManager.captureEngineGameMoveResponseContext(
                transaction, this, binding, color)
            : EngineManager.captureEngineGameMoveResponseContext(turn, this, binding, color);
    if (responseContext == null) {
      return false;
    }
    EngineGameResponseHandler responseHandler =
        new EngineGameResponseHandler(this, responseContext, binding);
    boolean accepted =
        sendCommand(
            command,
            responseHandler,
            failure -> {
              responseHandler.settle();
              EngineManager.failEngineGameTransaction(transaction, failure);
            },
            true,
            false,
            TrackingReleaseReason.ORDINARY_OPERATION,
            null,
            false,
            binding);
    if (!accepted) {
      throw new IllegalStateException("Engine-game genmove command was rejected");
    }
    // isThinking = true;

    // isPondering = false;
    // genmovenoponder =false;
    return true;
  }

  public void clearPkMoveStartTime() {
    pkMoveStartTime = System.currentTimeMillis();
  }

  //	public void genmove_analyze(String color) {
  //		String command = "lz-genmove_analyze " + color + " " +
  // Lizzie.config.analyzeUpdateIntervalCentisec;
  //		sendCommand(command);
  //		isThinking = true;
  //		isPondering = false;
  //	}

  //  public void time_settings() {
  //    Lizzie.leelaz.sendCommand("time_settings 0 " + Lizzie.config.maxGameThinkingTimeSeconds + "
  // 1");
  //  }

  public void clear() {
    synchronized (this) {
      YikeSyncDebugLog.log("Leelaz clear() entered isPondering=" + isPondering);
      sendStatefulOrdinaryCommands(
          List.of("clear_board"), StatefulOrdinaryMutationKind.NONE);
      afterClearStateCommandForTest();
      if (isPondering) ponder();
    }
  }

  void afterClearStateCommandForTest() {}

  public void clearWithoutPonder() {
    this.notPondering();
    nameCmdfornoponder();
    sendStatefulOrdinaryCommands(
        List.of("clear_board"), StatefulOrdinaryMutationKind.NONE);
  }

  public void undo() {
    undo(false, false);
  }

  public void undo(boolean addPlayer, boolean blackToPlay) {
    synchronized (this) {
      boolean continuePonderAfterUndo = isPonderingOrWasPonderingBeforeTracking();
      sendCommand("undo");
      if (continuePonderAfterUndo)
        if (Lizzie.config.isAutoAna
            || ((Lizzie.config.analyzeBlack && Lizzie.board.getHistory().isBlacksTurn())
                || (Lizzie.config.analyzeWhite && !Lizzie.board.getHistory().isBlacksTurn())))
          ponder(addPlayer, blackToPlay);
        else {
          nameCmdfornoponder();
          underPonder = true;
        }
    }
  }

  public void analyzeAvoid(String type, String color, String coordList, int untilMove) {
    analyzeAvoid(
        String.format(
            Locale.ENGLISH, "%s %s %s %d", type, color, coordList, untilMove <= 0 ? 1 : untilMove));
    Lizzie.board.clearbestmoves();
  }

  public void analyzeAvoid(String type, String coordList, int untilMove) {
    analyzeAvoid(type, coordList, untilMove, false, false);
  }

  public void analyzeAvoid(
      String type, String coordList, int untilMove, boolean addPlayer, boolean blackToPlay) {
    if (shouldRejectCommandDuringLifecycleCompletion()) return;
    if (!isPondering) {
      isPondering = true;
      startPonderTime = System.currentTimeMillis();
    }
    String parameters =
        String.format(
            Locale.ENGLISH, "%s %s %s %d", type, "b", coordList, untilMove <= 0 ? 1 : untilMove);
    parameters =
        parameters
            + " "
            + String.format(
                Locale.ENGLISH,
                "%s %s %s %d",
                type,
                "w",
                coordList,
                untilMove <= 0 ? 1 : untilMove);
    sendCommand(
        String.format(
            (isKatago
                ? "kata-analyze %s%d %s" + addKataTag()
                : (isSayuri ? "analyze %s%d %s" : "lz-analyze %s%d %s")),
            maybeAddPlayer(addPlayer, blackToPlay),
            getInterval(),
            parameters));
    Lizzie.board.clearbestmoves();
  }

  public void analyzeAvoid(String parameters) {
    if (shouldRejectCommandDuringLifecycleCompletion()) return;
    if (!isPondering) {
      isPondering = true;
      startPonderTime = System.currentTimeMillis();
    }
    sendCommand(
        String.format(
            (isKatago
                ? "kata-analyze %s%d %s" + addKataTag()
                : (isSayuri ? "analyze %s%d %s" : "lz-analyze %s%d %s")),
            maybeAddPlayer(),
            getInterval(),
            parameters));
    // Lizzie.board.getHistory().getData().tryToClearBestMoves();
    Lizzie.board.clearbestmoves();
  }

  /** This initializes leelaz's pondering mode at its current position */
  public void ponder() {
    ponder(false, false);
  }

  public void ponder(boolean addPlayer, boolean blackToPlay) {
    if (shouldRejectCommandDuringLifecycleCompletion()) {
      YikeSyncDebugLog.log("Leelaz ponder deferred: lifecycle completion pending");
      return;
    }
    if (noAnalyze) return;
    if (isInitialBoardSynchronizationActive()) {
      YikeSyncDebugLog.log("Leelaz ponder deferred: initial board synchronization active");
      return;
    }
    YikeSyncDebugLog.log(
        "Leelaz ponder request addPlayer="
            + addPlayer
            + " blackToPlay="
            + blackToPlay
            + " isPonderingBefore="
            + isPondering
            + " started="
            + started
            + " loaded="
            + isLoaded);
    isPondering = true;
    underPonder = false;
    if (stopByPlayouts) outOfPlayoutsLimit = true;
    stopByPlayouts = false;
    stopByLimit = false;
    startPonderTime = System.currentTimeMillis();
    if (EngineManager.isEngineGame) pkMoveStartTime = startPonderTime;
    if (!Lizzie.config.playponder && Lizzie.frame.isPlayingAgainstLeelaz) {
      return;
    }
    if (isheatmap) {
      heatcount = new ArrayList<Integer>();
      sendHeatCommand();
      return;
    }
    if (isLeela0110) {
      leela0110Ponder(true);
      return;
    }
    if (this == Lizzie.leelaz && Lizzie.frame != null) {
      AnalysisResourceCoordinator.processStarted(
          this, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, engineCommand, process);
      Lizzie.frame.onMainEnginePonder();
    }
    if (Lizzie.frame.isKeepingForce || LizzieFrame.isKeepForcing) {
      if (LizzieFrame.allowcoords != "") {
        Lizzie.leelaz.analyzeAvoid(
            "allow",
            LizzieFrame.allowcoords,
            Lizzie.config.selectAllowMoves,
            addPlayer,
            blackToPlay);
      } else {
        Lizzie.leelaz.analyzeAvoid(
            "avoid",
            LizzieFrame.avoidcoords,
            Lizzie.config.selectAvoidMoves,
            addPlayer,
            blackToPlay);
      }
    } else {
      LizzieFrame.isTempForcing = false;
      LizzieFrame.allowcoords = "";
      LizzieFrame.avoidcoords = "";
      Lizzie.frame.clearSelectImage();
      if (this.isKatago) {
        sendCommand(
            "kata-analyze "
                + maybeAddPlayer(addPlayer, blackToPlay)
                + getInterval()
                + addKataTag());
      } else {
        if (isSayuri)
          sendCommand("analyze " + maybeAddPlayer(addPlayer, blackToPlay) + getInterval());
        else sendCommand("lz-analyze " + maybeAddPlayer(addPlayer, blackToPlay) + getInterval());
      }
    }
    LizzieFrame.menu.toggleEngineMenuStatus(true, false);
  }

  private String maybeAddPlayer() {
    return maybeAddPlayer(false, false);
  }

  private String maybeAddPlayer(boolean addPlayer, boolean reverse) {
    if (!canAddPlayer) return "";
    else if (addPlayer) return (reverse ? "B " : "W ");
    // 试下激活时 mainline currentHistoryNode 停在 anchor，但要分析的是 displayNode（trial 子树里），
    // 用 mainline 视角会让 kata-analyze 颜色错位 → KataGo 用错视角给 winrate → 画面上"轮谁谁稳赢"。
    if (Lizzie.engineFollowController != null
        && Lizzie.engineFollowController.isTrialActive()
        && Lizzie.frame != null) {
      featurecat.lizzie.rules.BoardHistoryNode dn = Lizzie.frame.getDisplayNode();
      if (dn != null && dn.getData() != null) {
        return dn.getData().blackToPlay ? "B " : "W ";
      }
    }
    return (Lizzie.board.getHistory().isBlacksTurn() ? "B " : "W ");
  }

  public int getInterval() {
    if (isSSH || useJavaSSH) return Lizzie.config.analyzeUpdateIntervalCentisecSSH;
    else return Lizzie.config.analyzeUpdateIntervalCentisec;
  }

  public int getIntervalForGenmovePk() {
    if (isKatago && Lizzie.config.showPreviousBestmovesInEngineGame) return Integer.MAX_VALUE;
    if (isSSH || useJavaSSH) return Lizzie.config.analyzeUpdateIntervalCentisecSSH;
    else return Lizzie.config.analyzeUpdateIntervalCentisec;
  }

  public void pkponder() {
    isPondering = true;
    startPonderTime = System.currentTimeMillis();
    if (isLeela0110) {
      leela0110Ponder(true);
      return;
    }
    if (this.isKatago) {
      if (Lizzie.config.showKataGoEstimate)
        sendCommand("kata-analyze " + getInterval() + addKataTag() + " ownership true");
      else sendCommand("kata-analyze " + getInterval() + addKataTag());
    } else {
      if (isSayuri) sendCommand("analyze 1 " + getInterval());
      else sendCommand("lz-analyze " + getInterval());
    } // until it responds to this, incoming
    // ponder results are obsolete

  }

  public void togglePonder() {
    YikeSyncDebugLog.log(
        "Leelaz togglePonder before isPondering="
            + isPondering
            + " underPonder="
            + underPonder
            + " caller="
            + buildPonderCallerTrace());
    if (underPonder) {
      ponder();
      return;
    }
    isPondering = !isPondering;
    // if(isPondering)
    if (Lizzie.frame.isShowingHeatmap) {
      Lizzie.frame.isShowingHeatmap = false;
      ponder();
    }
    if (isPondering) {
      ponder();
    } else {
      nameCmd();
    }
    YikeSyncDebugLog.log("Leelaz togglePonder after isPondering=" + isPondering);
  }

  private String buildPonderCallerTrace() {
    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
    StringBuilder builder = new StringBuilder();
    int collected = 0;
    for (StackTraceElement element : stack) {
      String className = element.getClassName();
      String methodName = element.getMethodName();
      if (!className.startsWith("featurecat.lizzie")) {
        continue;
      }
      if (className.equals(Leelaz.class.getName())
          && (methodName.equals("togglePonder")
              || methodName.equals("nameCmdfornoponder")
              || methodName.equals("buildPonderCallerTrace"))) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append(" <- ");
      }
      builder
          .append(className)
          .append("#")
          .append(methodName)
          .append(":")
          .append(element.getLineNumber());
      collected++;
      if (collected >= 6) {
        break;
      }
    }
    return builder.length() == 0 ? "unknown" : builder.toString();
  }

  public void clearPonderLimit() {
    outOfPlayoutsLimit = false;
    stopByPlayouts = false;
  }

  /** End the process */
  public void shutdown() {
    ReaderStreamBinding binding = currentReaderStreamBinding();
    shutdown(binding, requestReaderShutdown(binding, true));
  }

  private void shutdown(ReaderStreamBinding binding, ReaderExecutorSnapshot executors) {
    cancelLeela0110PonderForReaderBinding(binding);
    try {
      if (executors.ownsTransportClose) {
        AnalysisResourceCoordinator.processStopped(
            this, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, binding.process);
        if (binding.javaSSH != null) {
          binding.javaSSH.close();
        } else if (binding.remoteTransport != null) {
          binding.remoteTransport.close();
        } else {
          if (binding.process != null) binding.process.destroy();
        }
      }
    } finally {
      shutdownReaderExecutors(executors);
    }
  }

  public List<MoveData> getBestMoves() {
    //	synchronized (this) {
    return bestMoves;
    //	}
  }

  public void clearBestMoves() {
    resetAnalysisInfoPayload(false);
  }

  // public Optional<String> getDynamicKomi() {
  // if (Float.isNaN(dynamicKomi) || Float.isNaN(dynamicOppKomi)) {
  // return Optional.empty();
  // } else {
  // return Optional.of(String.format(Locale.ENGLISH,"%.1f / %.1f", dynamicKomi,
  // dynamicOppKomi));
  // }
  // }

  //	public void setModifying() {
  //		//isModifying=true;
  //	//	ignoreCmdNumber=cmdNumber;
  //	}
  //
  //	public void setModifyEnd(boolean fromBoard) {
  //	//	isModifying=false;
  //	//	if(fromBoard)
  //		//	ignoreCmdNumber=cmdNumber-1;
  //	}

  public boolean isPondering() {
    return isPondering;
  }

  public void Pondering() {
    isPondering = true;
    YikeSyncDebugLog.log("Leelaz Pondering() set true");
  }

  public void notPondering() {
    isPondering = false;
    YikeSyncDebugLog.log("Leelaz notPondering() set false");
  }

  private void logInterestingCommand(String command, String source) {
    if (command == null) {
      return;
    }
    if (command.startsWith("play ")
        || command.equals("clear_board")
        || command.startsWith("loadsgf ")
        || command.startsWith("kata-analyze")
        || command.startsWith("lz-analyze")
        || command.startsWith("analyze ")
        || command.equals("name")
        || command.equals("stop")
        || command.equals("stop-ponder")) {
      YikeSyncDebugLog.log(
          "Leelaz " + source + " command=" + command + " isPondering=" + isPondering);
    }
  }

  public class WinrateStats {
    public double maxWinrate;
    public int totalPlayouts;
    public double scoreLead;

    public WinrateStats(double maxWinrate, int totalPlayouts, double score) {
      this.maxWinrate = maxWinrate;
      this.totalPlayouts = totalPlayouts;
      this.scoreLead = score;
    }
  }

  /*
   * Return the best win rate and total number of playouts. If no analysis
   * available, win rate is negative and playouts is 0.
   */
  public WinrateStats getWinrateStats() {
    WinrateStats stats = new WinrateStats(-100, 0, 0);
    AnalysisInfoSnapshot snapshot = currentAnalysisInfoSnapshot();
    if (!snapshot.moves.isEmpty()) {
      // we should match the Leelaz UCTNode get_eval, which is a weighted average
      // copy the list to avoid concurrent modification exception... TODO there must
      // be a better way
      // (note the concurrent modification exception is very very rare)
      // We should use Lizzie Board's best moves as they will generally be the most
      // accurate
      // final List<MoveData> moves = new ArrayList<MoveData>(Lizzie.board.getData().bestMoves);

      // get the total number of playouts in moves
      stats.totalPlayouts = snapshot.totalPlayouts;

      // stats.maxWinrate = bestMoves.get(0).winrate;
      stats.maxWinrate = BoardData.getWinrateFromBestMoves(snapshot.moves);
      stats.scoreLead = BoardData.getScoreLeadFromBestMoves(snapshot.moves);
      // BoardData.getWinrateFromBestMoves(moves);
    }

    return stats;
  }

  /*
   * initializes the normalizing factor for winrate_to_handicap_stones conversion.
   */
  //	public void estimatePassWinrate() {
  //		// we use A1 instead of pass, because valuenetwork is more accurate for A1 on
  //		// empty board than a
  //		// pass.
  //		// probably the reason for higher accuracy is that networks have randomness
  //		// which produces
  //		// occasionally A1 as first move, but never pass.
  //		// for all practical purposes, A1 should equal pass for the value it provides,
  //		// hence good
  //		// replacement.
  //		// this way we avoid having to run lots of playouts for accurate winrate for
  //		// pass.
  //		playMove(Stone.BLACK, "A1");
  //		togglePonder();
  //		WinrateStats stats = getWinrateStats();
  //
  //		// we could use a timelimit or higher minimum playouts to get a more accurate
  //		// measurement.
  //		while (stats.totalPlayouts < 1) {
  //			try {
  //				Thread.sleep(100);
  //			} catch (InterruptedException e) {
  //				throw new Error(e);
  //			}
  //			stats = getWinrateStats();
  //		}
  //		mHandicapWinrate = stats.maxWinrate;
  //		togglePonder();
  //		undo();
  //		Lizzie.board.clear(false);
  //	}

  // public static double mHandicapWinrate = 25;

  /**
   * Convert winrate to handicap stones, by normalizing winrate by first move pass winrate (one
   * stone handicap).
   */
  //	public static double winrateToHandicap(double pWinrate) {
  //		// we assume each additional handicap lowers winrate by fixed percentage.
  //		// this is pretty accurate for human handicap games at least.
  //		// also this kind of property is a requirement for handicaps to determined based
  //		// on rank
  //		// difference.
  //
  //		// lets convert the 0%-50% range and 100%-50% from both the move and and pass
  //		// into range of 0-1
  //		double moveWinrateSymmetric = 1 - Math.abs(1 - (pWinrate / 100) * 2);
  //		double passWinrateSymmetric = 1 - Math.abs(1 - (mHandicapWinrate / 100) * 2);
  //
  //		// convert the symmetric move winrate into correctly scaled log scale, so that
  //		// winrate of
  //		// passWinrate equals 1 handicap.
  //		double handicapSymmetric = Math.log(moveWinrateSymmetric) / Math.log(passWinrateSymmetric);
  //
  //		// make it negative if we had low winrate below 50.
  //		return Math.signum(pWinrate - 50) * handicapSymmetric;
  //	}

  // public synchronized void addListener(LeelazListener listener) {
  // listeners.add(listener);
  // }

  // Beware, due to race conditions, bestMoveNotification can be called once even
  // after item is
  // removed
  // with removeListener
  //	public synchronized void removeListener(LeelazListener listener) {
  //		listeners.remove(listener);
  //	}

  // private synchronized void notifyBestMoveListeners() {
  // for (LeelazListener listener : listeners) {
  // listener.bestMoveNotification(bestMoves);
  // }
  // }

  public boolean isStarted() {
    return started;
  }

  public void clearPDA() {
    pda = 0.0;
    LizzieFrame.menu.txtPDA.setText("0.0");
  }

  // 随机落子
  public MoveData randomBestmove(List<MoveData> bestMoves, double diffWinrate, boolean isAutoPlay) {
    int maxPlayouts = 0;
    if (Lizzie.config.checkRandomVisits) {
      for (MoveData move : bestMoves) {
        if (move.playouts > maxPlayouts) maxPlayouts = move.playouts;
      }
    }
    double minWinrate = bestMoves.get(0).winrate - diffWinrate;
    List<MoveData> bestMovesTemp = new ArrayList<>();
    bestMovesTemp.add(bestMoves.get(0));
    for (int i = 1; i < bestMoves.size(); i++) {
      if (bestMoves.get(i).winrate >= minWinrate) {
        if (isAutoPlay) {
          if (Lizzie.config.anaGameRandomPlayoutsDiff > 0) {
            if (bestMoves.get(i).playouts / (float) maxPlayouts
                >= Lizzie.config.anaGameRandomPlayoutsDiff / 100)
              bestMovesTemp.add(bestMoves.get(i));
          }
          bestMovesTemp.add(bestMoves.get(i));
        } else {
          if (Lizzie.config.checkRandomVisits && i > 0) {
            if (bestMoves.get(i).playouts / (float) maxPlayouts
                >= Lizzie.config.percentsRandomVisits / 100) bestMovesTemp.add(bestMoves.get(i));
          } else bestMovesTemp.add(bestMoves.get(i));
        }
      }
    }
    Random random = new Random();
    int n = random.nextInt(bestMovesTemp.size());
    return bestMovesTemp.get(n);
  }

  public boolean isLoaded() {
    return isLoaded;
  }

  /**
   * Marks this engine as a target or captured mirror of a lifecycle board restore. While active,
   * ordinary live-board updates (play/undo/clear/analyze) are dropped instead of interleaving with
   * a frozen or catch-up restore route. The depth keeps overlapping owner-local barriers isolated.
   */
  public void beginInitialBoardSynchronization() {
    synchronized (initialBoardSynchronizationLock()) {
      initialBoardSynchronizationDepth++;
    }
  }

  /** Ends one lifecycle board synchronization barrier. */
  public void endInitialBoardSynchronization() {
    synchronized (initialBoardSynchronizationLock()) {
      if (initialBoardSynchronizationDepth > 0) {
        initialBoardSynchronizationDepth--;
      }
    }
  }

  /**
   * Attaches the shared startup admission and begins one board-synchronization barrier on this
   * engine. Target and mirror receive the same admission instance. The owner activates and
   * deactivates that instance. Returns false without writing the binding or increasing depth when
   * another active admission already occupies this endpoint. The same admission may reattach.
   */
  boolean attachAndBeginInitialEngineSyncAdmission(
      EngineManager.InitialEngineSyncAdmission admission) {
    if (admission == null) {
      throw new IllegalArgumentException("admission");
    }
    synchronized (initialBoardSynchronizationLock()) {
      EngineManager.InitialEngineSyncAdmission current = initialEngineSyncAdmission;
      if (current != null && current != admission && current.isActive()) {
        return false;
      }
      initialEngineSyncAdmission = admission;
      initialBoardSynchronizationDepth++;
      return true;
    }
  }

  void endInitialEngineSyncAdmission(EngineManager.InitialEngineSyncAdmission admission) {
    synchronized (initialBoardSynchronizationLock()) {
      if (admission != null && initialEngineSyncAdmission != admission) {
        return;
      }
      if (initialBoardSynchronizationDepth > 0) {
        initialBoardSynchronizationDepth--;
      }
    }
  }

  void detachInitialEngineSyncAdmission(EngineManager.InitialEngineSyncAdmission admission) {
    synchronized (initialBoardSynchronizationLock()) {
      if (initialEngineSyncAdmission == admission) {
        initialEngineSyncAdmission = null;
      }
    }
  }

  /**
   * Captures ordinary forwarding occupancy at mutation time. Occupied when the startup admission
   * is active or a lifecycle board-synchronization barrier is still held.
   */
  public EngineManager.OrdinaryLiveBoardForwardingIntent captureOrdinaryLiveBoardForwarding(
      Supplier<Boolean> action) {
    synchronized (initialBoardSynchronizationLock()) {
      return EngineManager.OrdinaryLiveBoardForwardingIntent.capturedAtMutation(
          isOrdinaryForwardingOccupied(), action);
    }
  }

  public EngineManager.OrdinaryLiveBoardForwardingIntent captureOrdinaryLiveBoardForwarding(
      EngineManager.OrdinaryLiveBoardForwardingIntent occupancySource, Supplier<Boolean> action) {
    boolean occupied = occupancySource != null && occupancySource.occupiedAtMutation();
    return EngineManager.OrdinaryLiveBoardForwardingIntent.capturedAtMutation(occupied, action);
  }

  /**
   * Submits an ordinary live-board forwarding intent. Returns false without running the action
   * when the plan was occupied at mutation or the engine is occupied now. The current thread is
   * marked as this intent's execution for the synchronous action; startup occupancy then rejects
   * every command from that context instead of handshake-whitelisting komi/boardsize. Returns
   * false if startup rejects a command or still occupies the engine when the action ends;
   * otherwise returns the action result.
   */
  public boolean submitOrdinaryLiveBoardForwarding(
      EngineManager.OrdinaryLiveBoardForwardingIntent intent) {
    if (intent == null) {
      throw new IllegalArgumentException("intent");
    }
    synchronized (initialBoardSynchronizationLock()) {
      if (intent.occupiedAtMutation() || isOrdinaryForwardingOccupied()) {
        return false;
      }
    }
    OrdinaryLiveBoardForwardingExecution previous = ordinaryLiveBoardForwardingContext.get();
    OrdinaryLiveBoardForwardingExecution execution = new OrdinaryLiveBoardForwardingExecution();
    ordinaryLiveBoardForwardingContext.set(execution);
    try {
      boolean result = intent.execute();
      if (execution.rejectedByStartupAdmission) {
        return false;
      }
      synchronized (initialBoardSynchronizationLock()) {
        if (isOrdinaryForwardingOccupied()) {
          return false;
        }
      }
      return result;
    } finally {
      if (previous == null) {
        ordinaryLiveBoardForwardingContext.remove();
      } else {
        ordinaryLiveBoardForwardingContext.set(previous);
      }
    }
  }

  private static final class OrdinaryLiveBoardForwardingExecution {
    private boolean rejectedByStartupAdmission;
  }

  private boolean isOrdinaryForwardingOccupied() {
    EngineManager.InitialEngineSyncAdmission admission = initialEngineSyncAdmission;
    return (admission != null && admission.isActive()) || initialBoardSynchronizationDepth > 0;
  }

  private boolean isInitialBoardSynchronizationActive() {
    return initialBoardSynchronizationDepth > 0;
  }

  long engineStartupSynchronizationTimeoutMillis() {
    if (useRemoteCompute || useJavaSSH) {
      return 60000L;
    }
    if (Config.isBundledKataGoCommand(engineCommand)) {
      try {
        Path executable =
            KataGoRuntimeHelper.resolveCommandExecutable(Utils.splitCommand(engineCommand));
        if (KataGoRuntimeHelper.isNvidiaBundledPath(executable)) {
          return NVIDIA_ENGINE_START_TIMEOUT_MS;
        }
      } catch (RuntimeException ignored) {
      }
    }
    return BUNDLED_ENGINE_START_TIMEOUT_MS;
  }

  long engineTuningSynchronizationTimeoutMillis() {
    return FIRST_OPENCL_TUNING_START_TIMEOUT_MS;
  }

  public void tryToDignostic(String message, boolean isModal) {
    if (isDeferredEngineGameRecoveryStartup()) {
      return;
    }
    long primaryGeneration = startupPrimaryEngineGeneration;
    boolean primaryEngine =
        primaryGeneration >= 0L
            && Lizzie.capturePrimaryEngineGeneration(this) == primaryGeneration;
    Object expectedEngineIncarnation = captureEngineIncarnationFence();
    EngineFailedMessage.runOnEventDispatchThreadAndWait(
        () ->
            showDiagnosticOnEventDispatchThread(
                message,
                isModal,
                primaryGeneration,
                primaryEngine,
                expectedEngineIncarnation,
                true));
  }

  void tryToDignosticForTerminalReader(
      String message,
      boolean primaryEngine,
      long primaryGeneration,
      Object expectedEngineIncarnation) {
    EngineFailedMessage.runOnEventDispatchThreadAndWait(
        () ->
            showDiagnosticOnEventDispatchThread(
                message,
                false,
                primaryGeneration,
                primaryEngine,
                expectedEngineIncarnation,
                false));
  }

  private void showDiagnosticOnEventDispatchThread(
      String message,
      boolean isModal,
      long primaryGeneration,
      boolean primaryEngine,
      Object expectedEngineIncarnation,
      boolean mayClearEngineGame) {
    closeBundledStartupDialog(primaryGeneration, expectedEngineIncarnation);
    if (primaryEngine) {
      Lizzie.runIfPrimaryEngine(
          this,
          primaryGeneration,
          () -> {
            if (hasMissingLocalStartupAsset(commands, useRemoteCompute, useJavaSSH)) {
              Lizzie.engineStartupStatus.needsRepair(
                  "EngineStartup.needsRepair", "AI is not ready - click to repair", message);
            } else {
              Lizzie.engineStartupStatus.failed(
                  "EngineStartup.failed", "AI failed to start - click to repair", message);
            }
          });
    }
    if (!shouldOpenInteractiveDiagnostic(primaryEngine, Lizzie.isFirstLaunchSession())) {
      return;
    }
    if (mayClearEngineGame
        && Lizzie.config != null
        && !Lizzie.config.autoCheckEngineAlive
        && Lizzie.engineManager != null
        && EngineManager.isEngineGame())
      Lizzie.engineManager.clearEngineGame();
    if (GraphicsEnvironment.isHeadless()) return;
    if (engineFailedMessage != null && engineFailedMessage.isVisible()) return;
    EngineFailedMessage.showDialog(
        commands,
        engineCommand,
        message,
        !useJavaSSH && OS.isWindows(),
        true,
        false,
        isModal,
        dialog -> engineFailedMessage = dialog);
  }

  private boolean shouldOpenInteractiveDiagnostic() {
    if (isDeferredEngineGameRecoveryStartup()) {
      return false;
    }
    return shouldOpenInteractiveDiagnostic(this == Lizzie.leelaz, Lizzie.isFirstLaunchSession());
  }

  static boolean shouldOpenInteractiveDiagnostic(
      boolean primaryEngine, boolean firstLaunchSession) {
    return !primaryEngine && !firstLaunchSession;
  }

  static boolean hasMissingLocalStartupAsset(
      List<String> commandParts, boolean remoteCompute, boolean javaSsh) {
    if (remoteCompute || javaSsh || commandParts == null || commandParts.isEmpty()) {
      return false;
    }
    try {
      Path executable = KataGoRuntimeHelper.resolveCommandExecutable(commandParts);
      if (executable != null && executable.isAbsolute() && !Files.isRegularFile(executable)) {
        return true;
      }
      for (int i = 0; i + 1 < commandParts.size(); i++) {
        String option = commandParts.get(i);
        if (!"-model".equals(option)
            && !"--model".equals(option)
            && !"-config".equals(option)
            && !"--config".equals(option)) {
          continue;
        }
        Path asset = Path.of(commandParts.get(i + 1));
        if (asset.isAbsolute() && !Files.isRegularFile(asset)) {
          return true;
        }
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  //	public String currentWeight() {
  //		return currentWeight;
  //	}
  //
  //	public String currentShortWeight() {
  //		if (currentWeight != null && currentWeight.length() > 18) {
  //			return currentWeight.substring(0, 16) + "..";
  //		}
  //		return currentWeight;
  //	}

  //	public boolean switching() {
  //		return switching;
  //	}

  public int currentEngineN() {
    return currentEngineN;
  }

  private long beginBundledStartup(Path engineExecutable) {
    long token = System.nanoTime();
    bundledStartupToken = token;
    updateBundledStartupStage(
        engineExecutable,
        1,
        "BundledEngineStartup.status.checking",
        "Checking built-in engine files...",
        KataGoRuntimeHelper.isNvidiaBundledPath(engineExecutable)
            ? "BundledEngineStartup.hint.nvidia"
            : "BundledEngineStartup.hint",
        KataGoRuntimeHelper.isNvidiaBundledPath(engineExecutable)
            ? "First launch on the NVIDIA package may take a little longer."
            : "First launch may take a little longer.");
    return token;
  }

  private void updateBundledStartupStage(
      Path engineExecutable,
      int step,
      String statusKey,
      String statusFallback,
      String hintKey,
      String hintFallback) {
    if (preload || useJavaSSH || !Config.isBundledKataGoCommand(engineCommand)) {
      return;
    }
    final boolean nvidiaBundled = KataGoRuntimeHelper.isNvidiaBundledPath(engineExecutable);
    final int totalSteps = nvidiaBundled ? 4 : 3;
    String progressFallback = statusFallback + " (" + step + "/" + totalSteps + ")";
    publishBundledStartupStatus(statusKey, progressFallback);
  }

  private void publishBundledStartupStatus(String statusKey, String statusFallback) {
    long primaryGeneration = startupPrimaryEngineGeneration;
    if (primaryGeneration >= 0L) {
      Lizzie.runIfPrimaryEngine(
          this,
          primaryGeneration,
          () -> Lizzie.engineStartupStatus.checking(statusKey, statusFallback));
    }
  }

  private void closeBundledStartupDialog() {
    closeBundledStartupDialog(
        startupPrimaryEngineGeneration, captureEngineIncarnationFence());
  }

  private void closeBundledStartupDialog(
      long expectedPrimaryGeneration, Object expectedEngineIncarnation) {
    if (expectedPrimaryGeneration < 0L
        || expectedEngineIncarnation == null
        || !isBundledReadyPublicationAdmitted(
            expectedPrimaryGeneration, expectedEngineIncarnation)) {
      return;
    }
    try {
      if (!Lizzie.prepareEngineReadyPersistence()) {
        return;
      }
    } catch (RuntimeException | Error persistenceFailure) {
      persistenceFailure.printStackTrace();
      return;
    }
    AtomicReference<EngineStartupStatus.PreparedNotification> readyNotification =
        new AtomicReference<>();
    Lizzie.runIfPrimaryEngine(
        this,
        expectedPrimaryGeneration,
        () -> {
          synchronized (engineArbitrationLock()) {
            // Bundled startup callbacks are independent of the lifecycle final fence. They may
            // arrive after the board barrier depth reached zero but before the lifecycle/update
            // owner has settled. Commit only while PRIMARY -> endpoint locks prove that no such
            // owner can still publish a different terminal outcome. Admission/depth are volatile;
            // attempt and completion ownership are protected by this endpoint lock.
            if (isCurrentLiveEngineIncarnationLocked(expectedEngineIncarnation)
                && canPublishBundledReadyLocked()) {
              readyNotification.set(Lizzie.engineStartupStatus.prepareReady());
            }
          }
        });
    EngineStartupStatus.PreparedNotification notification = readyNotification.get();
    if (notification != null) {
      notification.run();
    }
  }

  private boolean isBundledReadyPublicationAdmitted(
      long primaryGeneration, Object expectedEngineIncarnation) {
    AtomicBoolean admitted = new AtomicBoolean();
    Lizzie.runIfPrimaryEngine(
        this,
        primaryGeneration,
        () -> {
          synchronized (engineArbitrationLock()) {
            admitted.set(
                isCurrentLiveEngineIncarnationLocked(expectedEngineIncarnation)
                    && canPublishBundledReadyLocked());
          }
        });
    return admitted.get();
  }

  /** Called only while this endpoint's arbitration lock is held. */
  private boolean isCurrentLiveEngineIncarnationLocked(Object expectedEngineIncarnation) {
    return expectedEngineIncarnation != null
        && readerStreamBinding == expectedEngineIncarnation
        && !readerStreamBinding.terminated
        && !readerStreamBinding.readerShutdownRequested
        && started
        && isLoaded;
  }

  /** Called only while PRIMARY and this endpoint's arbitration lock are both held. */
  private boolean canPublishBundledReadyLocked() {
    return isLoaded
        && !isOrdinaryForwardingOccupied()
        && !hasLifecycleCompletionLocked()
        && activeUpdateEngineStartAttempt == null
        && Lizzie.engineStartupStatus.snapshot().state != EngineStartupStatus.State.READY;
  }

  private void startBundledStartupWatchdog(
      long token,
      Path engineExecutable,
      Object expectedIncarnation,
      boolean suppressGlobalPresentation) {
    if (token <= 0L || preload || useJavaSSH || !Config.isBundledKataGoCommand(engineCommand)) {
      return;
    }
    if (!(expectedIncarnation instanceof ReaderStreamBinding)) {
      return;
    }
    ReaderStreamBinding expectedBinding = (ReaderStreamBinding) expectedIncarnation;
    Process expectedProcess = expectedBinding.process;
    final boolean nvidiaBundled = KataGoRuntimeHelper.isNvidiaBundledPath(engineExecutable);
    final boolean firstOpenCLTuning =
        !nvidiaBundled
            && KataGoRuntimeHelper.needsFirstOpenCLTuning(
                engineExecutable, openClFp32CompatibilityActive);
    final long timeoutMillis =
        firstOpenCLTuning
            ? FIRST_OPENCL_TUNING_START_TIMEOUT_MS
            : (nvidiaBundled ? NVIDIA_ENGINE_START_TIMEOUT_MS : BUNDLED_ENGINE_START_TIMEOUT_MS);
    Thread watchdog =
        new Thread(
            () -> {
              long deadline = System.currentTimeMillis() + timeoutMillis;
              while (System.currentTimeMillis() < deadline) {
                if (token != bundledStartupToken
                    || readerStreamBinding != expectedBinding
                    || isLoaded
                    || isDownWithError
                    || isNormalEnd) {
                  return;
                }
                if (expectedProcess != null && !expectedProcess.isAlive()) {
                  break;
                }
                // OpenCL autotuning can take several minutes; keep waiting while it runs.
                if (isTuning) {
                  deadline =
                      Math.max(
                          deadline,
                          System.currentTimeMillis() + FIRST_OPENCL_TUNING_START_TIMEOUT_MS);
                }
                try {
                  Thread.sleep(250L);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
              if (token != bundledStartupToken
                  || readerStreamBinding != expectedBinding
                  || isLoaded
                  || isDownWithError
                  || isNormalEnd) {
                return;
              }
              isDownWithError = true;
              if (expectedProcess != null) {
                try {
                  expectedProcess.destroyForcibly();
                } catch (Exception e) {
                }
              }
              String message =
                  text(
                          "BundledEngineStartup.timeout",
                          "The engine did not finish loading in time. Please check GPU drivers,"
                              + " runtime files, and folder permissions.")
                      + "\n"
                      + text("BundledEngineStartup.workDir", "Working folder")
                      + ": "
                      + Lizzie.config.getRuntimeWorkDirectory().getAbsolutePath();
              dispatchBundledStartupTimeoutPresentation(
                  expectedBinding, suppressGlobalPresentation, message);
            },
            "bundled-engine-startup-watchdog");
    watchdog.setDaemon(true);
    watchdog.start();
  }

  private void dispatchBundledStartupTimeoutPresentation(
      Object expectedIncarnation, boolean frozenSuppression, String message) {
    if (frozenSuppression) {
      return;
    }
    SwingUtilities.invokeLater(
        () -> {
          if (!isCurrentEngineIncarnation(expectedIncarnation)
              || suppressesGlobalEnginePresentation(expectedIncarnation)) {
            return;
          }
          closeBundledStartupDialog();
          try {
            tryToDignostic(message, true);
            if (shouldOpenInteractiveDiagnostic()) {
              LizzieFrame.openMoreEngineDialog();
            }
          } catch (JSONException e) {
            e.printStackTrace();
          }
        });
  }

  void dispatchBundledStartupTimeoutPresentationForTest(
      Object expectedIncarnation, String message) {
    dispatchBundledStartupTimeoutPresentation(
        expectedIncarnation,
        suppressesGlobalEnginePresentation(expectedIncarnation),
        message);
  }

  private String text(String key, String fallback) {
    try {
      if (Lizzie.resourceBundle != null && Lizzie.resourceBundle.containsKey(key)) {
        return Lizzie.resourceBundle.getString(key);
      }
    } catch (Exception e) {
    }
    return fallback;
  }

  public String engineCommand() {
    return this.engineCommand;
  }

  void rememberKataGoThreadLaunchOverride(List<String> effectiveLaunchCommand) {
    launchCommandSetsKataGoThreads =
        KataGoRuntimeHelper.hasEffectiveNumSearchThreadsOverride(effectiveLaunchCommand);
  }

  //	public void toggleGtpConsole() {
  //		gtpConsole = !gtpConsole;
  //	}
  //
  private void setLeelaSaiEnginePara() {
    if (Lizzie.config.chkLzsaiEngineMem && Lizzie.config.autoLoadLzsaiEngineMem)
      sendCommand(
          "lz-setoption name Maximum Memory Use (MiB) value " + Lizzie.config.txtLzsaiEngineMem);

    if (Lizzie.config.chkLzsaiEngineVisits && Lizzie.config.autoLoadLzsaiEngineVisits)
      sendCommand("lz-setoption name Visits value " + Lizzie.config.txtLzsaiEngineVisits);

    if (Lizzie.config.chkLzsaiEngineLagbuffer && Lizzie.config.autoLoadLzsaiEngineLagbuffer)
      sendCommand("lz-setoption name Lagbuffer value " + Lizzie.config.txtLzsaiEngineLagbuffer);

    if (Lizzie.config.chkLzsaiEngineResign && Lizzie.config.autoLoadLzsaiEngineResign)
      sendCommand(
          "lz-setoption name Resign Percentage value " + Lizzie.config.txtLzsaiEngineResign);
  }

  private void setKataEnginePara() {
    if (Lizzie.config.autoLoadKataEnginePDA && !isKataGoPda) {
      setPda(Lizzie.config.autoLoadTxtKataEnginePDA);
    }
    String kataGoThreads = Utils.resolveKataGoThreadsForEngineLoad();
    if (!launchCommandSetsKataGoThreads && !kataGoThreads.isEmpty()) {
      sendCommand("kata-set-param numSearchThreads " + kataGoThreads);
    }
    if (Lizzie.config.autoLoadKataEngineWRN) {
      try {
        this.wrn = Double.parseDouble(Lizzie.config.autoLoadTxtKataEngineWRN);
      } catch (NumberFormatException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
        return;
      }
      sendCommand("kata-set-param analysisWideRootNoise " + wrn);
    }
  }

  public void setHeatmap() {
    Lizzie.frame.isShowingHeatmap = true;
    isheatmap = true;
    heatcount = new ArrayList<Integer>();
    heatPolicy = new ArrayList<Double>();
    heatOwnership = new ArrayList<Double>();
  }

  public void toggleHeatmap(boolean bySpace) {
    // TODO Auto-generated method stub
    if (Lizzie.frame.isPlayingAgainstLeelaz) return;
    if (EngineManager.isEmpty) {
      Lizzie.frame.togglePolicy();
      return;
    }
    Lizzie.frame.isShowingPolicy = false;
    if (isKatago) Lizzie.frame.clearKataEstimate();
    if ((isKatago && !bySpace)
        || (Lizzie.config.isDoubleEngineMode()
            && Lizzie.leelaz2 != null
            && Lizzie.leelaz2.isKatago)) {
      if (isheatmap) {
        if (iskataHeatmapShowOwner) {
          Lizzie.frame.isShowingHeatmap = !Lizzie.frame.isShowingHeatmap;
          isheatmap = Lizzie.frame.isShowingHeatmap;
          iskataHeatmapShowOwner = false;
        } else {
          iskataHeatmapShowOwner = true;
        }
      } else {
        Lizzie.frame.isShowingHeatmap = !Lizzie.frame.isShowingHeatmap;
        isheatmap = Lizzie.frame.isShowingHeatmap;
      }
    } else {
      Lizzie.frame.isShowingHeatmap = !Lizzie.frame.isShowingHeatmap;
      isheatmap = Lizzie.frame.isShowingHeatmap;
      iskataHeatmapShowOwner = false;
    }
    heatcount = new ArrayList<Integer>();
    heatPolicy = new ArrayList<Double>();
    heatOwnership = new ArrayList<Double>();
    if (isheatmap) {
      sendHeatCommand();
      isPondering = true;
    } else {
      Lizzie.board.clearBestHeatMove();
      if (isPondering) {
        ponder();
      }
      // Lizzie.frame.handleAfterDrawGobanBottom();
    }
    if (Lizzie.config.isDoubleEngineMode() && Lizzie.leelaz2 != null)
      Lizzie.leelaz2.toggleHeatmapSub(bySpace);
  }

  public void toggleHeatmapSub(boolean bySpace) {
    // TODO Auto-generated method stub
    if (isKatago && !bySpace) {
      if (isheatmap) {
        if (iskataHeatmapShowOwner) {
          //  Lizzie.frame.isShowingHeatmap=!Lizzie.frame.isShowingHeatmap;
          isheatmap = Lizzie.frame.isShowingHeatmap;
          iskataHeatmapShowOwner = false;
        } else {
          iskataHeatmapShowOwner = true;
        }
      } else {
        //	Lizzie.frame.isShowingHeatmap=!Lizzie.frame.isShowingHeatmap;
        isheatmap = Lizzie.frame.isShowingHeatmap;
      }
    } else {
      //  Lizzie.frame.isShowingHeatmap=!Lizzie.frame.isShowingHeatmap;
      isheatmap = Lizzie.frame.isShowingHeatmap;
      iskataHeatmapShowOwner = false;
    }
    heatcount = new ArrayList<Integer>();
    heatPolicy = new ArrayList<Double>();
    heatOwnership = new ArrayList<Double>();
    if (isheatmap) {
      // sendHeatCommand();
    } else {
      Lizzie.board.clearBestHeatMove();
      if (isKatago) Lizzie.frame.clearKataEstimate();
      if (isPondering) {
        ponder();
      }
      // Lizzie.frame.handleAfterDrawGobanBottomSub();
    }
  }

  private void sendHeatCommand() {
    if (isKatago) {
      sendCommand("kata-raw-nn " + new Random().nextInt(8));
    } else sendCommand("heatmap");
  }

  public void getParameterScadule(boolean sendCommand) {
    getParameterScadule(sendCommand, TimeUnit.SECONDS.toMillis(30));
  }

  void getParameterScadule(boolean sendCommand, long timeoutMillis) {
    final long timeoutGeneration;
    synchronized (parameterReadTimeoutLock) {
      timeoutGeneration = ++parameterReadTimeoutGeneration;
      getRcentLine = true;
      if (sendCommand) {
        recentLineNumber = 0;
        sendCommand("kata-get-param playoutDoublingAdvantage");
        sendCommand("kata-get-param analysisWideRootNoise");
        sendCommand("kata-get-rules");
      }
    }
    Thread timeoutThread =
        new Thread(
            () -> {
              try {
                Thread.sleep(Math.max(0L, timeoutMillis));
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
              }
              synchronized (parameterReadTimeoutLock) {
                if (parameterReadTimeoutGeneration == timeoutGeneration) {
                  getRcentLine = false;
                }
              }
            },
            "lizzie-katago-parameter-timeout");
    timeoutThread.setDaemon(true);
    timeoutThread.start();
  }

  public void cancelParameterRead() {
    synchronized (parameterReadTimeoutLock) {
      parameterReadTimeoutGeneration++;
      getRcentLine = false;
    }
  }

  public void getSuicidalAndRules() {
    usingSpecificRules = -1;
    if (recentRulesLine.equals("")) {
      canSuicidal = false;
    } else {
      try {
        String line = recentRulesLine;
        JSONObject jo = new JSONObject(new String(line.substring(2)));
        if (jo.optBoolean("suicide", false)) canSuicidal = true;
        else canSuicidal = false;
        if (jo.optString("scoring", "").contentEquals("AREA")
            && jo.optString("ko", "").contentEquals("POSITIONAL")
            && jo.optBoolean("suicide", false)
            && jo.optString("tax", "").contentEquals("NONE")
            && jo.optString("whiteHandicapBonus", "").contentEquals("N")
            && !jo.optBoolean("hasButton", true)) {
          usingSpecificRules = 4; // tt规则
        } else if (jo.optString("scoring", "").contentEquals("AREA")
            && jo.optString("tax", "").contentEquals("NONE")
            && !jo.optBoolean("hasButton", true)) {
          usingSpecificRules = 1; // 中国规则
        } else if (jo.optString("scoring", "").contentEquals("AREA")
            && jo.optString("tax", "").contentEquals("ALL")
            && !jo.optBoolean("hasButton", true)) {
          usingSpecificRules = 2; // 中古规则
        } else if (jo.optString("scoring", "").contentEquals("TERRITORY")
            && jo.optString("tax", "").contentEquals("SEKI")) {
          usingSpecificRules = 3; // 日本规则
        } else if (jo.optString("scoring", "").contentEquals("AREA")
            || jo.optString("scoring", "").contentEquals("TERRITORY")) {
          usingSpecificRules = 5; // 其他规则
        }
      } catch (Exception e) {
      }
    }
  }

  private static final class Leela0110PonderCommandOwner {
    private final ReaderStreamBinding binding;
    private final Object stateToken;

    private Leela0110PonderCommandOwner(
        ReaderStreamBinding binding, Object stateToken) {
      this.binding = binding;
      this.stateToken = stateToken;
    }
  }

  private void leela0110Ponder(boolean first) {
    // Engine-game analysis enters ponder through runEngineGameIoStep, which carries the exact
    // transaction in this ThreadLocal. Preserve it for the timer, its derived command, ownership
    // publication, and exact retirement; ordinary UI ponder naturally observes null here.
    leela0110Ponder(first, engineGameStartupCommandContext.get());
  }

  private void leela0110Ponder(
      boolean first, EngineManager.EngineGameTransaction transaction) {
    if (first)
      if (Lizzie.config.isDoubleEngineMode()) {
        if (Lizzie.leelaz2 != null && this != Lizzie.leelaz2) {
          Lizzie.leelaz2.sendCommand("lz-analyze " + getInterval());
        }
      }
    if (prepareLeela0110PonderState(transaction)) {
      Leela0110PonderCommandOwner commandOwner = leela0110PonderCommandOwner(transaction);
      if (commandOwner == null) {
        return;
      }
      runWithEngineGameStartupCommandContext(
          transaction,
          () ->
              sendCommandNoLeelaz2(
                  "time_left b 0 0",
                  null,
                  commandOwner.binding,
                  commandOwner.stateToken));
    }
  }

  private boolean prepareLeela0110PonderState(
      EngineManager.EngineGameTransaction transaction) {
    long initialAnalysisInfoEpoch = analysisInfoEpochSnapshot();
    leela0110PonderPhysicalWriteLock.lock();
    try {
      synchronized (leela0110PonderStateLock) {
        ReaderStreamBinding expectedBinding = readerStreamBinding;
        if (expectedBinding == null || expectedBinding.terminated) {
          return false;
        }
        if (leela0110PonderingBoardData != null) {
          if (transaction == null || leela0110PonderingTransaction == transaction) {
            return false;
          }
          // An admitted exact game owner supersedes an ordinary timer (or the retired batch
          // predecessor) on this same endpoint. Do the replacement atomically under the one
          // Leela0110-state monitor so the stale BoardData sentinel cannot suppress startup.
          clearLeela0110PonderStateLocked();
        }
        leela0110PonderingBoardData = Lizzie.board.getData();
        leela0110PonderingTransaction = transaction;
        leela0110PonderingBinding = expectedBinding;
        Object stateToken = new Object();
        leela0110PonderingStateToken = stateToken;
        leela0110BestMoves = new ArrayList<>();
        leela0110BestMovesEpoch = initialAnalysisInfoEpoch;
        leela0110PonderingTimer = new Timer("lizzie-leela0110-ponder", true);
        leela0110PonderingTimer.schedule(
            new TimerTask() {
              public void run() {
                if (isCurrentLeela0110PonderState(
                        expectedBinding, transaction, stateToken)
                    && (transaction == null
                        || EngineManager.isCurrentEngineGameTransaction(transaction))) {
                  runWithEngineGameStartupCommandContext(
                      transaction,
                      () ->
                          sendCommandNoLeelaz2(
                              "name", null, expectedBinding, stateToken));
                }
              }
            },
            LEELA0110_PONDERING_INTERVAL_MILLIS);
        return true;
      }
    } finally {
      leela0110PonderPhysicalWriteLock.unlock();
    }
  }

  public void leela0110StopPonder() {
    leela0110PonderPhysicalWriteLock.lock();
    try {
      synchronized (leela0110PonderStateLock) {
        clearLeela0110PonderStateLocked();
      }
    } finally {
      leela0110PonderPhysicalWriteLock.unlock();
    }
  }

  void cancelLeela0110PonderForEngineGameTransaction(
      EngineManager.EngineGameTransaction transaction) {
    leela0110PonderPhysicalWriteLock.lock();
    try {
      synchronized (leela0110PonderStateLock) {
        if (transaction == null || leela0110PonderingTransaction != transaction) {
          return;
        }
        clearLeela0110PonderStateLocked();
      }
    } finally {
      leela0110PonderPhysicalWriteLock.unlock();
    }
  }

  private void cancelLeela0110PonderForReaderBinding(
      ReaderStreamBinding binding) {
    leela0110PonderPhysicalWriteLock.lock();
    try {
      synchronized (leela0110PonderStateLock) {
        if (binding == null || leela0110PonderingBinding != binding) {
          return;
        }
        clearLeela0110PonderStateLocked();
      }
    } finally {
      leela0110PonderPhysicalWriteLock.unlock();
    }
  }

  private Leela0110PonderCommandOwner leela0110PonderCommandOwner(
      EngineManager.EngineGameTransaction transaction) {
    synchronized (leela0110PonderStateLock) {
      return leela0110PonderingTransaction == transaction
              && leela0110PonderingBinding != null
              && leela0110PonderingStateToken != null
              && readerStreamBinding == leela0110PonderingBinding
              && !leela0110PonderingBinding.terminated
          ? new Leela0110PonderCommandOwner(
              leela0110PonderingBinding, leela0110PonderingStateToken)
          : null;
    }
  }

  private boolean isCurrentLeela0110PonderState(
      ReaderStreamBinding binding,
      EngineManager.EngineGameTransaction transaction,
      Object stateToken) {
    synchronized (leela0110PonderStateLock) {
      return binding != null
          && leela0110PonderingBinding == binding
          && leela0110PonderingTransaction == transaction
          && stateToken != null
          && leela0110PonderingStateToken == stateToken
          && readerStreamBinding == binding
          && !binding.terminated;
    }
  }

  private void clearLeela0110PonderStateLocked() {
    if (leela0110PonderingTimer != null) {
      leela0110PonderingTimer.cancel();
      leela0110PonderingTimer = null;
    }
    leela0110PonderingBoardData = null;
    leela0110PonderingTransaction = null;
    leela0110PonderingBinding = null;
    leela0110PonderingStateToken = null;
    leela0110BestMoves = null;
    leela0110BestMovesEpoch = -1L;
  }

  private void leela0110UpdatePonder(AnalysisOutputRoute route) {
    if (route == null) {
      return;
    }
    EngineManager.EngineGameTransaction transaction =
        route.activeExactContext == null ? null : route.activeExactContext.transaction;
    runIfCurrentAnalysisOutputRoute(
        route,
        () -> {
          leela0110StopPonder();
          if (isPondering && prepareLeela0110PonderState(transaction)) {
            Leela0110PonderCommandOwner commandOwner =
                leela0110PonderCommandOwner(transaction);
            if (commandOwner == null) {
              return;
            }
            // The route's transaction/global admission remains held, but its binding lock has
            // already been released. This preserves owner identity through the delimiter-triggered
            // follow-up write without creating binding -> output or binding -> selection nesting.
            runWithEngineGameStartupCommandContext(
                transaction,
                () ->
                    sendCommandNoLeelaz2(
                        "time_left b 0 0",
                        null,
                        commandOwner.binding,
                        commandOwner.stateToken));
          }
        });
  }

  private boolean isLeela0110PonderingValid() {
    synchronized (leela0110PonderStateLock) {
      return leela0110PonderingBoardData == Lizzie.board.getData();
    }
  }

  public int getBestMovesPlayouts() {
    return currentTotalPlayouts;
  }

  public boolean isStopPonderingByLimit() {
    return stopByLimit;
  }

  public long getStartPonderTime() {
    return startPonderTime;
  }

  public void modifyStart() {
    synchronized (commandQueue()) {
      this.cmdNumber++;
      this.modifyNumber++;
    }
  }

  public void setModifyEnd() {
    synchronized (commandQueue()) {
      cmdNumber -= modifyNumber;
      modifyNumber = 0;
    }
  }

  private void calculateModifyNumber() {
    synchronized (commandQueue()) {
      cmdNumber -= modifyNumber;
      modifyNumber = 0;
    }
  }

  public void timeLeft(String color, int seconds, int moves, boolean isDuringMove) {
    seconds = Math.max(0, seconds);
    sendCommand("time_left " + color + " " + seconds + " " + moves);
    if (isDuringMove) {
      synchronized (commandQueue()) {
        currentCmdNum++;
      }
    }
  }

  public void timeLeft(String color, float seconds, int moves, boolean isDuringMove) {
    seconds = Math.max(0, seconds);
    sendCommand(
        "time_left " + color + " " + String.format(Locale.ENGLISH, "%.2f", seconds) + " " + moves);
    if (isDuringMove) {
      synchronized (commandQueue()) {
        currentCmdNum++;
      }
    }
  }

  public boolean isProcessDead() {
    if (useRemoteCompute) {
      return remoteTransport == null || !remoteTransport.isOpen();
    }
    return process != null && !process.isAlive();
  }

  public void maybeAjustPDA(BoardHistoryNode node) {
    // TODO Auto-generated method stub
    if (!isDymPda) return;
    if (Lizzie.board.isFirstWhiteNodeWithHandicap(node)) {
      if (Lizzie.config.chkAutoPDA) sendCommand(Lizzie.config.AutoPDA);
      else sendCommand("dympdacap " + pdaCap);
      if (isPondering()) ponder(true, !Lizzie.board.getHistory().isBlacksTurn());
    }
  }
}
