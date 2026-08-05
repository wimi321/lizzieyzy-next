package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.EngineTransport;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.analysis.gtpconfig.GtpConfigurationProbe;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.EngineFailedMessage;
import featurecat.lizzie.gui.JFontCheckBox;
import featurecat.lizzie.gui.JFontLabel;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Message;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Movelist;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.CommandLaunchHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.Utils;
import featurecat.lizzie.util.YikeSyncDebugLog;
import java.awt.Component;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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

  private enum StartupCommandAction {
    NONE,
    KATA,
    LEELA_SAI
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

  // private long maxAnalyzeTimeMillis; // , maxThinkingTimeMillis;
  int cmdNumber;
  int modifyNumber;
  private int currentCmdNum;
  // public int modifyCmdNum;
  // private boolean isResponse=false;
  private ArrayDeque<QueuedCommand> cmdQueue;
  private ArrayDeque<QueuedCommand> foregroundRestoreQueue;
  private boolean normalCommandSendInProgress;
  private QueuedCommand normalCommandBeingSent;
  private final ThreadLocal<ExclusiveGtpSession> foregroundRestoreCommandSession =
      new ThreadLocal<>();
  private static final ThreadLocal<ExactSnapshotRestoreAdmission>
      exactSnapshotRestoreAdmissionContext = new ThreadLocal<>();
  private final ThreadLocal<AtomicReference<RuntimeException>> deferredDefaultMirrorFailure =
      new ThreadLocal<>();
  private volatile boolean foregroundRestoreInProgress;
  private volatile boolean suppressNormalCommandsForForegroundAnalysis;
  private volatile ExclusiveGtpSession foregroundRestoreSession;
  private ArrayDeque<PendingResponseHandler> pendingResponseHandlers;
  private volatile boolean loadSgfResponseQuarantined;
  private final AtomicInteger loadSgfResponseCommandIds = new AtomicInteger(1);
  private final AtomicInteger readBoardGmaResponseCommandIds = new AtomicInteger(700000000);
  private final AtomicInteger exclusiveGtpResponseCommandIds = new AtomicInteger(800000000);
  private final AtomicInteger boardSynchronizationResponseCommandIds =
      new AtomicInteger(900000000);
  private volatile boolean currentCommandResponseError;
  private volatile String currentCommandResponseLine = "";

  private Process process;
  private transient EngineTransport remoteTransport;
  private volatile Object engineArbitrationLock = new Object();
  private volatile ExclusiveGtpSession exclusiveGtpSession;
  private boolean exclusiveGtpLifecycleTransition;
  private boolean exclusiveGtpLifecycleQueueGate;
  private Object exclusiveGtpLifecycleOwner;
  private int exclusiveGtpLifecycleDepth;
  private final AtomicLong restartBootstrapAttemptIds = new AtomicLong();
  private final ThreadLocal<RestartBootstrapReceipt> restartBootstrapReceiptContext =
      new ThreadLocal<>();
  private RestartBootstrapReceipt restartBootstrapReceipt;
  private volatile RestartRestorePreparation automaticRestartRestorePreparation;
  private volatile ExclusiveGtpLifecycleReservation automaticRestartReservation;

  private BufferedReader inputStream;
  private BufferedOutputStream outputStream;
  private BufferedReader errorStream;
  private final AtomicLong processIncarnationIds = new AtomicLong();
  private volatile ReaderStreamBinding readerStreamBinding;
  private boolean readerTerminalCleanupInProgress;
  private volatile boolean readerStreamRebindInProgress;
  private volatile TrackingHandoffClaim trackingHandoffGate;
  private final ArrayDeque<String> recentStdoutLines = new ArrayDeque<String>();
  private final ArrayDeque<String> recentStderrLines = new ArrayDeque<String>();

  // public Board board;
  private List<MoveData> bestMoves;
  private List<MoveData> bestMovesPrevious;
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

  public boolean getRcentLine = false;
  private int recentLineNumber = 0;
  public String recentRulesLine = "";
  public int usingSpecificRules = -1; // 1=中国规则2=中古规则3=日本规则4=TT规则5=其他规则
  public boolean preload = false;
  public volatile boolean started = false;
  public volatile boolean isDownWithError = false;
  public volatile boolean isLoaded = false;
  private volatile long bundledStartupToken = 0L;
  private volatile boolean openClFp32CompatibilityActive = false;
  private volatile boolean launchCommandSetsKataGoThreads = false;
  private final AtomicBoolean openClCompatibilityRecoveryAttempted = new AtomicBoolean(false);
  public boolean isCheckingVersion;
  public volatile boolean isCheckingName;
  public String initialCommand;
  public String gtpConfigurationProtocol = "";
  public JSONObject gtpConfigurationProfile;
  private boolean isCheckingPda = false;
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
  public double scoreMean = 0;
  public double scoreStdev = 0;
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
  private final Object positionEstimateLock = new Object();
  private final KataRawOwnershipParser positionEstimateParser = new KataRawOwnershipParser();
  private Consumer<List<Double>> positionEstimateConsumer;
  private Object positionEstimateRequestOwner;
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
  private Timer leela0110PonderingTimer;
  private BoardData leela0110PonderingBoardData;
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
  private int currentTotalPlayouts;
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
    bestMoves = new ArrayList<>();
    currentTotalPlayouts = 0;
    bestMovesPrevious = new ArrayList<>();
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
    launchCommandSetsKataGoThreads = false;
    if (engineCommand.trim().isEmpty()) {
      Utils.showMsg(Lizzie.resourceBundle.getString("EngineFaied.empty"));
      return;
    }
    canAddPlayer = false;
    currentEngineN = index;
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
        this.remoteTransport.start();
        initializeStreams(
            this.remoteTransport.stdout(),
            this.remoteTransport.stdin(),
            this.remoteTransport.stderr());
      } catch (IOException e) {
        isDownWithError = true;
        rememberRecentLine(recentStderrLines, e.getLocalizedMessage());
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
        initializeStreams(
            this.javaSSH.getStdout(), this.javaSSH.getStdin(), this.javaSSH.getSterr());
      } else {
        isDownWithError = true;
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
          KataGoRuntimeHelper.ensureBundledRuntimeReady(engineExecutable, Lizzie.frame);
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
        Lizzie.engineStartupStatus.checking(
            "BundledEngineStartup.status.openclCompatibility",
            "Using stable NVIDIA OpenCL compatibility mode...");
      }
      ProcessBuilder processBuilder = new ProcessBuilder(launchCommands);
      CommandLaunchHelper.configureProcessBuilder(processBuilder, launchSpec);
      KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, engineExecutable);
      processBuilder.redirectErrorStream(false);
      try {
        process = processBuilder.start();
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
        return;
      }
      initializeStreams();
      if (bundledCommand) {
        updateBundledStartupStage(
            engineExecutable,
            nvidiaBundled ? 4 : 3,
            "BundledEngineStartup.status.waitingResponse",
            "Waiting for engine response...",
            "BundledEngineStartup.hint.waiting",
            "The first response can take a little longer while the engine finishes loading.");
        startBundledStartupWatchdog(startupToken, engineExecutable);
      }
    }
    // Send a version request to check that we have a supported version
    // Response handled in parseLine
    isCheckingVersion = true;
    isCheckingName = true;
    endGetCommandList = false;
    startGetCommandList = false;
    commandLists.clear();
    readBoardGmaUnsupportedPromptShown = false;
    if (!engineStateUnrestored) {
      clearReadBoardGmaSearchLimitSnapshots();
    }
    // sendCommand("turnon");
    RestartBootstrapReceipt startupReceipt = currentRestartBootstrapReceipt();
    if (!isSSH) {
      Runnable runnable =
          new Runnable() {
            public void run() {
              runWithRestartBootstrapReceipt(
                  startupReceipt,
                  () -> {
                    int times = 0;
                    while (outputStream == null && times < 10) {
                      try {
                        times++;
                        Thread.sleep(100);
                      } catch (InterruptedException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                      }
                    }
                    sendCommand("name");
                    sendCommand("version");
                    sendCommand("list_commands");
                    enqueueSavedGtpConfiguration();
                    if (!(Lizzie.frame.isPlayingAgainstLeelaz
                        || Lizzie.frame.isAnaPlayingAgainstLeelaz))
                      sendCommand("komi " + komi);
                    boardSizeForEngine(width, height);
                    if (initialCommand != null && !initialCommand.equals("")) {
                      String[] initialCommands = initialCommand.trim().split(";");
                      for (String command : initialCommands) {
                        sendCommand(command);
                      }
                    }
                  });
            }
          };
      Thread thread = new Thread(runnable);
      thread.start();
    }
    if (this == Lizzie.leelaz && shouldApplyInitialEngineKomiToCurrentGame()) {
      Lizzie.board.getHistory().getGameInfo().setKomi(komi);
    }
    if (isSSH) {
      Runnable runnable =
          new Runnable() {
            public void run() {
              try {
                Thread.sleep(500);
              } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
              }
              sendCommand("name");
              sendCommand("name");
              sendCommand("name");
              sendCommand("version");
              sendCommand("list_commands");
              enqueueSavedGtpConfiguration();
              boardSizeForEngine(width, height);
              if (!(Lizzie.frame.isPlayingAgainstLeelaz || Lizzie.frame.isAnaPlayingAgainstLeelaz))
                sendCommand("komi " + komi);
              if (initialCommand != null && !initialCommand.equals("")) {
                String[] initialCommands = initialCommand.trim().split(";");
                for (String command : initialCommands) {
                  sendCommand(command);
                }
              }
              setResponseUpToDate();
            }
          };
      Thread thread = new Thread(runnable);
      thread.start();
    }
    // if(width!=19||height!=19)

    // start a thread to continuously read Leelaz output
    // new Thread(this::read).start();
    // can stop engine for switching weights
    ReaderStreamBinding startedReaderStreamBinding = currentReaderStreamBinding();
    started = true;
    executor = Executors.newSingleThreadScheduledExecutor();
    isNormalEnd = false;
    executor.execute(() -> read(startedReaderStreamBinding));
    executorErr = Executors.newSingleThreadScheduledExecutor();
    executorErr.execute(() -> readError(startedReaderStreamBinding));

    if (Lizzie.leelaz2 != null && this == Lizzie.leelaz2) {
      if (index > 19) LizzieFrame.menu.changeEngineIcon2(20, 1);
      else LizzieFrame.menu.changeEngineIcon2(index, 1);
    } else {
      if (index > 19) LizzieFrame.menu.changeEngineIcon(20, 1);
      else LizzieFrame.menu.changeEngineIcon(index, 1);
    }
    if (Lizzie.frame.isShowingHeatmap) Lizzie.frame.isShowingHeatmap = false;
    if (Lizzie.frame.isShowingPolicy) Lizzie.frame.isShowingPolicy = false;
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

  public void restartClosedEngine(int index, Runnable afterBoardRestore) throws IOException {
    boolean restoreScheduled = false;
    Runnable restoreCompletion = afterBoardRestore;
    try {
      if (engineCommand.trim().isEmpty()) {
        return;
      }
      RestartRestorePreparation restorePreparation = consumeAutomaticRestartPreparation();
      if (restorePreparation == null) {
        restorePreparation = captureRestartRestore();
        ExclusiveGtpLifecycleReservation directReservation =
            beginExclusiveGtpLifecycleReservation(restorePreparation.owner());
        if (directReservation == null) {
          return;
        }
        Runnable callerCompletion = restoreCompletion;
        restoreCompletion =
            () -> {
              try {
                directReservation.close();
              } finally {
                if (callerCompletion != null) {
                  callerCompletion.run();
                }
              }
            };
      }
      final RestartRestorePreparation frozenRestorePreparation = restorePreparation;
      final ExactSnapshotEngineRestore.PreparedRestore restorePlan =
          frozenRestorePreparation == null ? null : frozenRestorePreparation.preparedRestore();
      final boolean resumePonder =
          frozenRestorePreparation != null && frozenRestorePreparation.resumePonder();
      final Runnable boardRestoreCompletion = restoreCompletion;
      if (useRemoteCompute && isStarted()) {
        normalQuit();
      }
      isLoaded = false;
      canCheckAlive = false;
      startEngine(index);
      Leelaz thisLeelz = this;
      Runnable syncBoard =
          new Runnable() {
            public void run() {
              boolean completionDelegated = false;
              try {
                if (!waitForAutomaticRestartReadiness()) {
                  isLoaded = false;
                  markBoardSynchronizationFailed(
                      "automatic engine restart did not become ready");
                  return;
                }
                if (boardRestoreCompletion == null) {
                  thisLeelz.restoreClosedEngineBoardState(
                      resumePonder, restorePlan, frozenRestorePreparation);
                } else {
                  completionDelegated = true;
                  thisLeelz.restoreClosedEngineBoardState(
                      resumePonder,
                      restorePlan,
                      frozenRestorePreparation,
                      boardRestoreCompletion);
                }
              } finally {
                if (boardRestoreCompletion != null && !completionDelegated) {
                  boardRestoreCompletion.run();
                }
              }
            }
          };
      Thread syncBoardTh = new Thread(withCurrentRestartBootstrapReceipt(syncBoard));
      syncBoardTh.start();
      restoreScheduled = true;
    } finally {
      if (!restoreScheduled && restoreCompletion != null) {
        restoreCompletion.run();
      }
    }
  }

  private boolean waitForAutomaticRestartReadiness() {
    long now = System.nanoTime();
    long deadline =
        now
            + TimeUnit.MILLISECONDS.toNanos(
                Math.max(1L, engineStartupSynchronizationTimeoutMillis()));
    boolean tuningTimeoutApplied = false;
    while (true) {
      if (!isStarted() || isDownWithError || isNormalEnd) {
        return false;
      }
      if (automaticRestartReady(isLoaded(), isCheckingName, endGetCommandList)) {
        return true;
      }
      now = System.nanoTime();
      if (!tuningTimeoutApplied && isTuning) {
        deadline =
            now
                + TimeUnit.MILLISECONDS.toNanos(
                    Math.max(1L, engineTuningSynchronizationTimeoutMillis()));
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
    restoreClosedEngineBoardState(resumePonder, null, null);
  }

  private void restoreClosedEngineBoardState(
      boolean resumePonder,
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore,
      RestartRestorePreparation restorePreparation) {
    isPondering = false;
    if (preparedRestore == null) {
      restoreRootAfterLifecyclePreparation(restorePreparation);
    } else {
      Lizzie.board.resendMoveToEngine(this, false, preparedRestore);
    }
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

  private void restoreClosedEngineBoardState(
      boolean resumePonder,
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore,
      RestartRestorePreparation restorePreparation,
      Runnable afterBoardRestore) {
    boolean preserveUnrestoredState = engineStateUnrestored;
    try {
      isPondering = false;
      if (preparedRestore == null) {
        restoreRootAfterLifecyclePreparation(restorePreparation);
      } else {
        Lizzie.board.resendMoveToEngine(this, false, preparedRestore);
      }
    } catch (RuntimeException failure) {
      isLoaded = false;
      markLifecycleBoardSynchronizationFailed(
          failure.getMessage() == null
              ? "automatic engine board restore failed"
              : failure.getMessage(),
          preserveUnrestoredState);
      afterBoardRestore.run();
      return;
    }
    if (KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed()) {
      afterBoardRestore.run();
      return;
    }
    confirmBoardSynchronization(
        () -> {
          try {
            if (engineStateUnrestored) {
              completeReadBoardGmaRecoveryAfterBoardSync();
            }
            resumeClosedEngineAfterBoardSynchronization(resumePonder);
          } finally {
            afterBoardRestore.run();
          }
        },
        detail -> {
          isLoaded = false;
          markLifecycleBoardSynchronizationFailed(detail, preserveUnrestoredState);
          afterBoardRestore.run();
        });
  }

  void resumeClosedEngineAfterBoardSynchronization(boolean resumePonder) {
    if (resumePonder) {
      Lizzie.initializeAfterVersionCheck(false, this);
    }
  }
  void initializeAfterExplicitRestartBoardSynchronization(boolean resumePonder) {
    Lizzie.initializeAfterVersionCheck(false, this, resumePonder);
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

  public ExclusiveGtpLifecycleReservation beginAutomaticEngineRestartReservation() {
    synchronized (engineArbitrationLock()) {
      if (engineStateUnrestored
          || readBoardGmaReservation != null
          || readBoardGmaRestoreBarrier != null) {
        return null;
      }
      RestartRestorePreparation restorePreparation;
      try {
        restorePreparation = captureRestartRestore();
      } catch (ExactSnapshotRestoreAdmissionException conflict) {
        return null;
      }
      if (!beginExclusiveGtpLifecycleTransition(restorePreparation.owner())) {
        return null;
      }
      try {
        ExclusiveGtpLifecycleReservation reservation =
            new ExclusiveGtpLifecycleReservation(this, restorePreparation.owner(), false);
        automaticRestartRestorePreparation = restorePreparation;
        automaticRestartReservation = reservation;
        return reservation;
      } catch (RuntimeException failure) {
        endExclusiveGtpLifecycleTransition(restorePreparation.owner());
        throw failure;
      }
    }
  }

  private RestartRestorePreparation captureRestartRestore() {
    Board restoreBoard = Lizzie.board;
    ArrayList<Movelist> rootMoves =
        Movelist.copyList(restoreBoard == null ? null : restoreBoard.getMoveList());
    BoardHistoryList history = restoreBoard == null ? null : restoreBoard.getHistory();
    BoardHistoryNode target = history == null ? null : history.getCurrentHistoryNode();
    Double komi =
        history == null || history.getGameInfo() == null ? null : history.getGameInfo().getKomi();
    return RestartRestorePreparation.capture(this, target, komi, rootMoves, isPondering);
  }

  private RestartRestorePreparation consumeAutomaticRestartPreparation() {
    synchronized (engineArbitrationLock()) {
      RestartRestorePreparation restorePreparation = automaticRestartRestorePreparation;
      if (restorePreparation == null && automaticRestartReservation != null) {
        throw new IllegalStateException("Automatic restart restore has already been claimed.");
    }
      automaticRestartRestorePreparation = null;
      return restorePreparation;
  }
  }

  private void clearAutomaticRestartPreparation(EngineModeReservation reservation) {
    synchronized (engineArbitrationLock()) {
    if (automaticRestartReservation == reservation) {
        automaticRestartRestorePreparation = null;
      automaticRestartReservation = null;
    }
  }
  }

  private void restoreRootAfterLifecyclePreparation(RestartRestorePreparation restorePreparation) {
    if (restorePreparation == null) {
      Lizzie.board.resendMoveToEngine(this, false);
      return;
    }
    restorePreparation.executeRootReplay(Lizzie.board);
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
    closeBundledStartupDialog();
    isNormalEnd = true;
    leela0110StopPonder();
    if (Lizzie.leelaz2 != null && this == Lizzie.leelaz2) {
      if (currentEngineN > 20) LizzieFrame.menu.changeEngineIcon2(20, 0);
      else LizzieFrame.menu.changeEngineIcon2(currentEngineN, 0);
    } else {
      if (currentEngineN > 20) LizzieFrame.menu.changeEngineIcon(20, 0);
      else LizzieFrame.menu.changeEngineIcon(currentEngineN, 0);
    }

    //		if(isScreen)
    //			sendCommand("name");
    sendCommand("quit");
    if (this.useJavaSSH) {
      javaSSH.close();
    } else {
      if (this.useRemoteCompute && remoteTransport != null) remoteTransport.close();
      shutdownExecutor(executor);
      shutdownExecutor(executorErr);
      shutdown();
    }
    started = false;
    isLoaded = false;
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

  public void forceQuit() {
    AnalysisResourceCoordinator.processStopped(
        this, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, process);
    isNormalEnd = true;
    started = false;
    isLoaded = false;
    try {
      leela0110StopPonder();
      sendCommand("quit");
    } catch (Exception e) {
      e.printStackTrace();
    }
    //		if(isScreen)
    //			sendCommand("name");
    if (Lizzie.leelaz2 != null && this == Lizzie.leelaz2) {
      if (currentEngineN > 20) LizzieFrame.menu.changeEngineIcon2(20, 0);
      else LizzieFrame.menu.changeEngineIcon2(currentEngineN, 0);
    } else {
      if (currentEngineN > 20) LizzieFrame.menu.changeEngineIcon(20, 0);
      else LizzieFrame.menu.changeEngineIcon(currentEngineN, 0);
    }
    if (this.useJavaSSH) {
      javaSSH.close();
    } else if (this.useRemoteCompute) {
      if (remoteTransport != null) remoteTransport.close();
      if (executor != null) executor.shutdownNow();
      if (executorErr != null) executorErr.shutdownNow();
    } else {
      try {
        process.destroyForcibly();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    outputStream = null;
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
      while ((!ownsRebindGateAfterTrackingCleanup && readerStreamRebindInProgress)
          || readerTerminalCleanupInProgress
          || (readerStreamBinding != null && readerStreamBinding.linesInProgress > 0)
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
        synchronized (commandQueue()) {
          readerStreamRebindInProgress = true;
          while (normalCommandSendInProgress) {
            try {
              commandQueue().wait();
            } catch (InterruptedException waitInterrupted) {
              interrupted = true;
            }
          }
          readerStreamBinding.terminated = true;
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
          } else {
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
          readerStreamBinding =
            new ReaderStreamBinding(
                nextInputStream,
                nextErrorStream,
                process,
                useRemoteCompute ? remoteTransport : null,
                useJavaSSH ? javaSSH : null,
                processIncarnationIds.incrementAndGet());
          synchronized (commandQueue()) {
            publishRestartBootstrapReceiptLocked(readerStreamBinding, nextOutputStream);
          }
        if (ownsRebindGateAfterTrackingCleanup) {
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
          readerStreamBinding =
              new ReaderStreamBinding(
                  nextInputStream,
                  nextErrorStream,
                  process,
                  useRemoteCompute ? remoteTransport : null,
                  useJavaSSH ? javaSSH : null,
                  processIncarnationIds.incrementAndGet());
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

  private ReaderStreamBinding currentReaderStreamBinding() {
    ReaderStreamBinding binding = readerStreamBinding;
    if (binding != null) {
      return binding;
    }
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding == null) {
        readerStreamBinding =
            new ReaderStreamBinding(
                inputStream,
                errorStream,
                process,
                useRemoteCompute ? remoteTransport : null,
                useJavaSSH ? javaSSH : null,
                processIncarnationIds.incrementAndGet());
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
    synchronized (engineArbitrationLock()) {
      synchronized (commandQueue()) {
        if (isCurrentRestartBootstrapReceiptLocked(receipt)) {
          if (quarantineEngineState) {
          engineStateUnrestored = true;
          }
          restartBootstrapReceipt = null;
          receipt.binding.restartBootstrapReceipt = null;
          reset = resetGtpCommandStateLocked(detail);
        }
      }
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

  private static final class ReaderStreamBinding {
    private final BufferedReader stdout;
    private final BufferedReader stderr;
    private final Process process;
    private final EngineTransport remoteTransport;
    private final SSHController javaSSH;
    private final long incarnation;
    private RestartBootstrapReceipt restartBootstrapReceipt;
    private int linesInProgress;
    private Throwable terminalFailure;
    private boolean terminalCleanupStarted;
    private volatile boolean terminated;

    private ReaderStreamBinding(
        BufferedReader stdout,
        BufferedReader stderr,
        Process process,
        EngineTransport remoteTransport,
        SSHController javaSSH,
        long incarnation) {
      this.stdout = stdout;
      this.stderr = stderr;
      this.process = process;
      this.remoteTransport = remoteTransport;
      this.javaSSH = javaSSH;
      this.incarnation = incarnation;
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
    currentTotalPlayouts = MoveData.getPlayouts(bestMoves);
    if (Lizzie.config.isDoubleEngineMode() && Lizzie.leelaz2 != null && this == Lizzie.leelaz2)
      Lizzie.frame
          .getDisplayNode()
          .getData()
          .tryToSetBestMoves2(bestMoves, bestMovesEnginename, true, currentTotalPlayouts);
    else {
      if (EngineManager.isEngineGame && Lizzie.config.enginePkPonder) {
        if ((Lizzie.board.getHistory().isBlacksTurn()
                && this
                    == Lizzie.engineManager.engineList.get(
                        EngineManager.engineGameInfo.blackEngineIndex))
            || !Lizzie.board.getHistory().isBlacksTurn()
                && this
                    == Lizzie.engineManager.engineList.get(
                        EngineManager.engineGameInfo.whiteEngineIndex)) {
          Lizzie.frame
              .getDisplayNode()
              .getData()
              .tryToSetBestMoves(bestMoves, bestMovesEnginename, true, currentTotalPlayouts);
        }
      } else
        Lizzie.frame
            .getDisplayNode()
            .getData()
            .tryToSetBestMoves(bestMoves, bestMovesEnginename, true, currentTotalPlayouts);
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
    currentTotalPlayouts = MoveData.getPlayouts(bestMoves);
    if (Lizzie.config.isDoubleEngineMode() && Lizzie.leelaz2 != null && this == Lizzie.leelaz2)
      Lizzie.frame
          .getDisplayNode()
          .getData()
          .tryToSetBestMoves2(bestMoves, bestMovesEnginename, true, currentTotalPlayouts);
    else {
      if (EngineManager.isEngineGame && Lizzie.config.enginePkPonder) {
        if ((Lizzie.board.getHistory().isBlacksTurn()
                && this
                    == Lizzie.engineManager.engineList.get(
                        EngineManager.engineGameInfo.blackEngineIndex))
            || !Lizzie.board.getHistory().isBlacksTurn()
                && this
                    == Lizzie.engineManager.engineList.get(
                        EngineManager.engineGameInfo.whiteEngineIndex)) {
          // if(!isModifying)
          Lizzie.frame
              .getDisplayNode()
              .getData()
              .tryToSetBestMoves(bestMoves, bestMovesEnginename, true, currentTotalPlayouts);
        }
      } else
        Lizzie.frame
            .getDisplayNode()
            .getData()
            .tryToSetBestMoves(bestMoves, bestMovesEnginename, true, currentTotalPlayouts);
    }
    return bestMoves;
  }

  public List<MoveData> parseInfoKatago(String line) {
    boolean hasOwnership = false;
    String[] lineInfo = null;
    if (line.contains("ownership")) {
      hasOwnership = true;
      lineInfo = line.split("ownership");
      line = lineInfo[0];
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
    currentTotalPlayouts = MoveData.getPlayouts(bestMoves);
    if (this == Lizzie.leelaz) {
      AnalysisResourceCoordinator.foregroundPlayoutSample(this, currentTotalPlayouts);
    }
    ArrayList<Double> estimateArray = new ArrayList<Double>();
    if (Lizzie.config.showKataGoEstimate) {
      if (hasOwnership && lineInfo != null && lineInfo.length > 1) {
        String[] params2 = lineInfo[1].trim().split(" ");
        for (int i = 0; i < params2.length; i++) estimateArray.add(Double.parseDouble(params2[i]));
      }
    } else estimateArray = null;
    if (Lizzie.config.isDoubleEngineMode() && Lizzie.leelaz2 != null && this == Lizzie.leelaz2)
      Lizzie.frame
          .getDisplayNode()
          .getData()
          .tryToSetBestMoves2(
              bestMoves, bestMovesEnginename, true, currentTotalPlayouts, estimateArray);
    else {
      if (EngineManager.isEngineGame && Lizzie.config.enginePkPonder) {
        if ((Lizzie.board.getHistory().isBlacksTurn()
                && this
                    == Lizzie.engineManager.engineList.get(
                        EngineManager.engineGameInfo.blackEngineIndex))
            || !Lizzie.board.getHistory().isBlacksTurn()
                && this
                    == Lizzie.engineManager.engineList.get(
                        EngineManager.engineGameInfo.whiteEngineIndex)) {
          //	if(!isModifying)
          Lizzie.frame
              .getDisplayNode()
              .getData()
              .tryToSetBestMoves(
                  bestMoves, bestMovesEnginename, true, currentTotalPlayouts, estimateArray);
        }
      } else
        Lizzie.frame
            .getDisplayNode()
            .getData()
            .tryToSetBestMoves(
                bestMoves, bestMovesEnginename, true, currentTotalPlayouts, estimateArray);
    }
    logTrialKataInfo(bestMoves);
    return bestMoves;
  }

  // 试下诊断：限频打印 katago info 落到哪个 displayNode、引擎首选 winrate。开关见 TrialDiag。
  private static long lastTrialKataLogMs = 0L;
  private static long lastMainlineKataLogMs = 0L;
  private static long lastRawInfoLogMs = 0L;

  private void logTrialKataInfo(List<MoveData> bestMoves) {
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
        currentTotalPlayouts);
  }

  /**
   * Parse a line of Leelaz output
   *
   * @param line output line
   * @throws IOException
   */
  private void parseLineForGenmovePk(String line, BufferedReader reader) throws IOException {
    // Lizzie.gtpConsole.addLineforce(line);

    if (line.startsWith("info")) {
      if (this != Lizzie.leelaz && isResponseUpToDate()) {
        if (isKatago) {
          this.bestMoves = parseInfoKatago(line.substring(5));
        } else if (isSai) {
          this.bestMoves = parseInfoSai(line.substring(5));
        } else {
          this.bestMoves = parseInfo(line.substring(5));
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

    if (line.startsWith("=") || line.startsWith("play")) {
      isCommandLine = true;
      String[] params = line.trim().split(" ");
      // currentCmdNum = Integer.parseInt(params[0].substring(1).trim());
      if (params.length <= 1) return;
      if (EngineManager.isEngineGame && params.length >= 2) {
        if (Lizzie.board.getHistory().isBlacksTurn()
            && (this.currentEngineN == EngineManager.engineGameInfo.whiteEngineIndex)) {
          return;
        }
        if (!Lizzie.board.getHistory().isBlacksTurn()
            && (this.currentEngineN == EngineManager.engineGameInfo.blackEngineIndex)) {
          return;
        }
        if (this.isZen) {
          synchronized (bestMoves) {
            try {
              if (bestMoves != null && !bestMoves.isEmpty()) {
                currentTotalPlayouts = MoveData.getPlayouts(bestMoves);
                Lizzie.board
                    .getData()
                    .tryToSetBestMoves(bestMoves, bestMovesEnginename, true, currentTotalPlayouts);
                this.bestMoves = new ArrayList<>();
              }
            } catch (Exception e) {
              this.bestMoves = new ArrayList<>();
              e.printStackTrace();
            }
          }
        }
        if (params[1].toLowerCase().contains("resign")) {
          pkMoveTime = System.currentTimeMillis() - pkMoveStartTime;
          pkMoveTimeGame = pkMoveTimeGame + pkMoveTime;

          nameCmdfornoponder();
          genmoveResign(false);
          return;
        }
        if (Lizzie.board.getHistory().getMoveNumber()
            > EngineManager.engineGameInfo.getMaxGameMoves()) {
          pkMoveTime = System.currentTimeMillis() - pkMoveStartTime;
          pkMoveTimeGame = pkMoveTimeGame + pkMoveTime;
          outOfMoveNum = true;
          nameCmdfornoponder();
          genmoveResign(false);
          return;
        }
        checkForGomokuFullBoard(true);
        boolean isPassingLose = false;
        if (params[1].startsWith("Passing")) {
          isPassingLose = true;
        }
        if (!isPassingLose && params[1].startsWith("pass")) {
          pkMoveTime = System.currentTimeMillis() - pkMoveStartTime;
          pkMoveTimeGame = pkMoveTimeGame + pkMoveTime;
          if (Lizzie.board.getData().isPassNode()) {
            doublePass = true;
            nameCmdfornoponder();
            genmoveResign(true);
            return;
          }
          Lizzie.engineManager
              .engineList
              .get(EngineManager.engineGameInfo.whiteEngineIndex)
              .clearPkMoveStartTime();
          Lizzie.engineManager
              .engineList
              .get(EngineManager.engineGameInfo.blackEngineIndex)
              .clearPkMoveStartTime();
          Lizzie.board.pass();
          if (this.currentEngineN == EngineManager.engineGameInfo.blackEngineIndex) {
            if (!Lizzie.engineManager
                .engineList
                .get(EngineManager.engineGameInfo.whiteEngineIndex)
                .playMoveGenmove("B", "pass")) {
              return;
            }
            Lizzie.engineManager
                .engineList
                .get(EngineManager.engineGameInfo.whiteEngineIndex)
                .genmoveForPk("W");
            if (!Lizzie.config.enginePkPonder)
              Lizzie.engineManager
                  .engineList
                  .get(EngineManager.engineGameInfo.blackEngineIndex)
                  .nameCmdfornoponder();
            Lizzie.leelaz =
                Lizzie.engineManager.engineList.get(EngineManager.engineGameInfo.blackEngineIndex);
          } else {
            if (!Lizzie.engineManager
                .engineList
                .get(EngineManager.engineGameInfo.blackEngineIndex)
                .playMoveGenmove("W", "pass")) {
              return;
            }
            Lizzie.engineManager
                .engineList
                .get(EngineManager.engineGameInfo.blackEngineIndex)
                .genmoveForPk("B");
            if (!Lizzie.config.enginePkPonder)
              Lizzie.engineManager
                  .engineList
                  .get(EngineManager.engineGameInfo.whiteEngineIndex)
                  .nameCmdfornoponder();
            Lizzie.leelaz =
                Lizzie.engineManager.engineList.get(EngineManager.engineGameInfo.whiteEngineIndex);
          }
          return;
        } else {
          //	try {
          Optional<int[]> coords;
          if (isPassingLose) {
            coords = Board.asCoordinates(reader.readLine());
          } else coords = Board.asCoordinates(params[1]);
          if (!coords.isPresent()) {
            return;
          }
          canCheckAlive = true;
          pkMoveTime = System.currentTimeMillis() - pkMoveStartTime;
          pkMoveTimeGame = pkMoveTimeGame + pkMoveTime;
          Lizzie.engineManager
              .engineList
              .get(EngineManager.engineGameInfo.whiteEngineIndex)
              .clearPkMoveStartTime();
          Lizzie.engineManager
              .engineList
              .get(EngineManager.engineGameInfo.blackEngineIndex)
              .clearPkMoveStartTime();
          Lizzie.board.place(coords.get()[0], coords.get()[1]);

          //					}
          //					catch (Exception e)
          //					{
          //						return;
          //					}
          String coordsString = Board.convertCoordinatesToName(coords.get()[0], coords.get()[1]);
          if (this.currentEngineN == EngineManager.engineGameInfo.blackEngineIndex) {

            if (!Lizzie.engineManager
                .engineList
                .get(EngineManager.engineGameInfo.whiteEngineIndex)
                .playMoveGenmove("B", coordsString)) {
              return;
            }
            Lizzie.engineManager
                .engineList
                .get(EngineManager.engineGameInfo.whiteEngineIndex)
                .genmoveForPk("W");
            if (!Lizzie.config.enginePkPonder)
              Lizzie.engineManager
                  .engineList
                  .get(EngineManager.engineGameInfo.blackEngineIndex)
                  .nameCmdfornoponder();
            Lizzie.leelaz =
                Lizzie.engineManager.engineList.get(EngineManager.engineGameInfo.blackEngineIndex);

          } else {
            if (!Lizzie.engineManager
                .engineList
                .get(EngineManager.engineGameInfo.blackEngineIndex)
                .playMoveGenmove("W", coordsString)) {
              return;
            }
            Lizzie.engineManager
                .engineList
                .get(EngineManager.engineGameInfo.blackEngineIndex)
                .genmoveForPk("B");
            if (!Lizzie.config.enginePkPonder)
              Lizzie.engineManager
                  .engineList
                  .get(EngineManager.engineGameInfo.whiteEngineIndex)
                  .nameCmdfornoponder();
            Lizzie.leelaz =
                Lizzie.engineManager.engineList.get(EngineManager.engineGameInfo.whiteEngineIndex);
          }
          return;
        }
      }
      runStartupCommandAction(checkNameAndVersion(params));
    } else if (line.startsWith("?")) {
      isCommandLine = true;
      if (consumeReadBoardGmaEngineErrorLine(line)) {
        return;
      }
      if (line.startsWith("? unacceptable komi")) {
        illegalKomi();
      }
    } else if (line.startsWith("PDA:")) {
      parsePDALine(line);
    }
  }

  private StartupCommandAction checkNameAndVersion(String[] params) {
    // TODO Auto-generated method stub
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
        isLoaded = true;
        closeBundledStartupDialog();
      }
      //						if (params[1].startsWith("KataGoYm"))
      //							sendCommandToLeelazWithOutLog("lizzie_use");
      if (params[1].toLowerCase().startsWith("kata")) {
        canAddPlayer = true;
        if (Utils.applyRecommendedKataGoThreads(false)) {
          Utils.persistConfigQuietly();
        }
        if (params[1].startsWith("KataGoPda")) {
          isKatagoCustom = true;
          isCheckingPda = true;
          isKataGoPda = true;
        }
        this.isKatago = true;
        if (params[1].startsWith("KataGoCustom")) isKatagoCustom = true;
        this.version = 17;
        startupCommandAction = StartupCommandAction.KATA;

        if (this.currentEngineN == EngineManager.currentEngineNo) {
          Lizzie.config.leelaversion = version;
        }
        isLoaded = true;
        closeBundledStartupDialog();
        isTuning = false;
        if (Lizzie.leelaz2 != null && this == Lizzie.leelaz2) {
          if (currentEngineN > 20) LizzieFrame.menu.changeEngineIcon2(20, 2);
          else LizzieFrame.menu.changeEngineIcon2(currentEngineN, 2);
        } else {
          if (currentEngineN > 20) LizzieFrame.menu.changeEngineIcon(20, 2);
          else LizzieFrame.menu.changeEngineIcon(currentEngineN, 2);
        }
      } else {
        isLoaded = true;
        closeBundledStartupDialog();
        isTuning = false;
        isKatago = false;
        startupCommandAction = StartupCommandAction.LEELA_SAI;
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
        if (this.currentEngineN == EngineManager.currentEngineNo) {
          Lizzie.config.leelaversion = version;
        }
        if (version == 7) {
          version = 17;
        }
        isCheckingVersion = false;
        isLoaded = true;
        closeBundledStartupDialog();
        isTuning = false;
        // Lizzie.initializeAfterVersionCheck();
        if (Lizzie.leelaz2 != null && this == Lizzie.leelaz2) {
          if (currentEngineN > 20) LizzieFrame.menu.changeEngineIcon2(20, 2);
          else LizzieFrame.menu.changeEngineIcon2(currentEngineN, 2);

        } else {
          if (currentEngineN > 20) LizzieFrame.menu.changeEngineIcon(20, 2);
          else LizzieFrame.menu.changeEngineIcon(currentEngineN, 2);
        }
      }
    }
    return startupCommandAction;
  }

  private StartupCommandAction mergeStartupCommandAction(
      StartupCommandAction current, StartupCommandAction next) {
    return current == StartupCommandAction.NONE ? next : current;
  }

  private void runStartupCommandAction(StartupCommandAction action) {
    if (action == StartupCommandAction.KATA) {
      if (isKataGoPda) {
        sendCommand("getpda");
        sendCommand("getdympdacap");
        Thread thread =
            new Thread(
                () -> {
                  try {
                    Thread.sleep(5000);
                  } catch (InterruptedException e) {
                    e.printStackTrace();
                  }
                  isCheckingPda = false;
                });
        thread.start();
      }
      setKataEnginePara();
      if (Lizzie.config.autoLoadKataRules) {
        sendCommand("kata-set-rules " + Lizzie.config.kataRules);
      }
      getParameterScadule(true);
    } else if (action == StartupCommandAction.LEELA_SAI) {
      setLeelaSaiEnginePara();
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

  private void parseLine(String line) {
    parsePositionEstimateLine(line);
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
    int autoAnalyzeAfterInfo = 0;
    StartupCommandAction startupCommandAction = StartupCommandAction.NONE;
    synchronized (this) {
      if (line.startsWith("info")) {
        boolean upToDate = isResponseUpToDate();
        if (this == Lizzie.leelaz) {
          YikeSyncDebugLog.log(
              "Leelaz parseLine info upToDate="
                  + upToDate
                  + " currentCmd="
                  + currentCmdNum
                  + " cmd="
                  + cmdNumber
                  + " isPondering="
                  + isPondering);
        }
        if ((upToDate)) {
          if (EngineManager.isEngineGame) {
            // Lizzie.frame.subBoardRenderer.reverseBestmoves = false;
            // Lizzie.frame.boardRenderer.reverseBestmoves = false;
            if (Lizzie.config.enginePkPonder) {
              if ((Lizzie.board.getHistory().isBlacksTurn()
                      && this
                          == Lizzie.engineManager.engineList.get(
                              EngineManager.engineGameInfo.blackEngineIndex))
                  || !Lizzie.board.getHistory().isBlacksTurn()
                      && this
                          == Lizzie.engineManager.engineList.get(
                              EngineManager.engineGameInfo.whiteEngineIndex)) {
                Lizzie.leelaz = this;
              }
            } else Lizzie.leelaz = this;
          }
          // Clear switching prompt
          // switching = false;

          // This should not be stale data when the command number match
          if (isKatago) {
            this.bestMoves = parseInfoKatago(line.substring(5));
          } else if (isSai) {
            this.bestMoves = parseInfoSai(line.substring(5));
          } else {
            this.bestMoves = parseInfo(line.substring(5));
          }
          if (useRemoteCompute && remoteTransport != null) {
            remoteTransport.markAnalysisProgressAccepted(MoveData.getPlayouts(this.bestMoves));
          }
          if (this == Lizzie.leelaz) {
            YikeSyncDebugLog.log("Leelaz parseLine bestMoves size=" + this.bestMoves.size());
          }
          if (!this.bestMoves.isEmpty()) {
            notifyAutoPKAfterInfo = true;
            notifyAutoPlayAfterInfo = true;
            if (Lizzie.config.isAutoAna) {
              if (Lizzie.frame.isAutoAnalyzingDiffNode) {
                autoAnalyzeAfterInfo = 1;
              } else if (Lizzie.config.analyzeAllBranch) {
                autoAnalyzeAfterInfo = 2;
              } else {
                autoAnalyzeAfterInfo = 3;
              }
            }
          }
          if (!EngineManager.isEngineGame || (!played && this == Lizzie.leelaz)) {
            Lizzie.frame.requestAnalysisRefresh();
          } else {
            Lizzie.frame.requestAnalysisTitleUpdate();
          }
          // don't follow the maxAnalyzeTime rule if we are in game
          if (!Lizzie.frame.isPlayingAgainstLeelaz
              && !Lizzie.frame.isAnaPlayingAgainstLeelaz
              && !EngineManager.isEngineGame
              && !Lizzie.config.isAutoAna) {
            if (!outOfPlayoutsLimit
                && ((Lizzie.config.limitPlayout
                        && getBestMovesPlayouts() > Lizzie.config.limitPlayouts)
                    || (Lizzie.config.stopAtEmptyBoard
                        && Lizzie.board.getHistory().noStoneBoard()))) {
              stopByLimit = true;
              stopByPlayouts = true;
              isPondering = !isPondering;
              nameCmd();
              if (!Lizzie.config.stopAtEmptyBoard && !Lizzie.board.getHistory().noStoneBoard()) {
                showStopPonderTips();
              }
            } else if ((Lizzie.config.limitTime
                && (System.currentTimeMillis() - startPonderTime)
                    > Lizzie.config.maxAnalyzeTimeMillis)) {
              stopByLimit = true;
              isPondering = !isPondering;
              nameCmd();
              showStopPonderTips();
            }
          }
          this.canCheckAlive = true;
        } else {
          if (Lizzie.config.isAutoAna) {
            bestMoves = new ArrayList<>();
            currentTotalPlayouts = 0;
          }
          if (Lizzie.config.isAutoAna)
            Lizzie.board.getHistory().getCurrentHistoryNode().getData().tryToClearBestMoves();
        }
        handledInfoLine = true;
      } else {
        if (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp || !this.isLoaded)
          Lizzie.gtpConsole.addLine(line + "\n");
      }
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
      } else {
        if (line.startsWith("| ST")) {
          String[] params = line.trim().split(" ");
          if (params.length == 13) {
            isColorEngine = true;
            if (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp)
              Lizzie.gtpConsole.addLine(oriEnginename + ": " + line);
            stage = Integer.parseInt(params[3].substring(0, params[3].length() - 1));
            komi = Float.parseFloat(params[6].substring(0, params[6].length() - 1));
          }
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
          processCommandResponseLine(line);
          readBoardGmaOwner.afterReadBoardGmaTerminalResponseConsumed("play-terminal");
          clearReadBoardGmaResponseOwner(
              readBoardGmaOwner, readBoardGmaBinding.identity, readBoardGmaBinding.generation);
          isCommandLine = false;
          if (shouldStopPonder) {
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
          if (Lizzie.frame.isAutocounting) {
            String command =
                "play " + (Lizzie.board.getHistory().isBlacksTurn() ? "w " : "b ") + params[1];
            Lizzie.frame.forwardAutoPositionEstimateCommand(command, false);
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
          if (Lizzie.frame.isAutocounting) {
            String command =
                "play " + (Lizzie.board.getHistory().isBlacksTurn() ? "w " : "b ") + params[1];
            Lizzie.frame.forwardAutoPositionEstimateCommand(command, false);
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
          if (Lizzie.frame.isAutocounting) {
            String command =
                "play " + (Lizzie.board.getHistory().isBlacksTurn() ? "w " : "b ") + params[1];
            Lizzie.frame.forwardAutoPositionEstimateCommand(command, false);
          }
          isInputCommand = false;
          isThinking = false;
        }
        if (isSettingHandicap) {
          bestMoves = new ArrayList<>();
          currentTotalPlayouts = 0;
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
            if (Lizzie.frame.isAutocounting) {
              String command =
                  "play " + (Lizzie.board.getHistory().isBlacksTurn() ? "w " : "b ") + params[1];
              Lizzie.frame.forwardAutoPositionEstimateCommand(command, false);
            }
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
            if (Lizzie.frame.isAutocounting) {
              String command =
                  "play " + (Lizzie.board.getHistory().isBlacksTurn() ? "w " : "b ") + params[1];
              Lizzie.frame.forwardAutoPositionEstimateCommand(command, false);
            }
            if (!Lizzie.config.playponder) Lizzie.leelaz.nameCmdfornoponder();
          }
          isThinking = false;
          if (isInputCommand) {
            isInputCommand = false;
          }
        }
        startupCommandAction =
            mergeStartupCommandAction(startupCommandAction, checkNameAndVersion(params));
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
    runStartupCommandAction(startupCommandAction);
    if (handledInfoLine) {
      runPostInfoActions(notifyAutoPKAfterInfo, notifyAutoPlayAfterInfo, autoAnalyzeAfterInfo);
    }
  }

  private void runPostInfoActions(
      boolean notifyAutoPKAfterInfo, boolean notifyAutoPlayAfterInfo, int autoAnalyzeAfterInfo) {
    if (notifyAutoPKAfterInfo) {
      notifyAutoPK(false);
    }
    if (notifyAutoPlayAfterInfo) {
      notifyAutoPlay(false);
    }
    if (autoAnalyzeAfterInfo == 1) {
      nofityDiffAna();
    } else if (autoAnalyzeAfterInfo == 2) {
      notifyAutoAnaAllBranch();
    } else if (autoAnalyzeAfterInfo == 3) {
      notifyAutoAna();
    }
  }

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

  private void notifyAutoPlay(boolean playImmediately) {
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
          if (bestMoves.get(0).playouts >= firstPlayouts) {
            playNow = true;
          }
        }
        if (playouts > 0) {
          if (currentTotalPlayouts >= playouts) {
            playNow = true;
          }
        }

        if (time > 0) {
          if (System.currentTimeMillis() - startPonderTime >= time) {
            playNow = true;
          }
        }
        if (playNow) {
          if (notifyAnaResign(false)) return;
          MoveData playMove = null;
          if (!Lizzie.frame.bothSync
              && Lizzie.config.enableAnaGameRamdonStart
              && Lizzie.board.getHistory().getMoveNumber() <= Lizzie.config.anaGameRandomMove)
            playMove = this.randomBestmove(bestMoves, Lizzie.config.anaGameRandomWinrateDiff, true);
          else playMove = bestMoves.get(0);

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

  private boolean notifyAnaResign(boolean isResgined) {
    // TODO Auto-generated method stub
    if (isResgined) {
      Lizzie.frame.togglePonderMannul();
      Utils.showMsg(oriEnginename + " " + Lizzie.resourceBundle.getString("Leelaz.resign"));
    } else if (Lizzie.frame.isAnaPlayingAgainstLeelaz && !Lizzie.frame.bothSync) {
      if (Lizzie.board.getHistory().getMoveNumber() >= Lizzie.config.anaGameResignStartMove) {
        if (bestMoves.get(0).winrate < Lizzie.config.anaGameResignPercent) {
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
    bestMoves = new ArrayList<>();
    currentTotalPlayouts = 0;
    if (isLastMove) {
      LizzieFrame.toolbar.stopAutoAna(true, false);
    } else {
      Lizzie.board.nextMove(true);
    }
  }

  private void nofityDiffAna() {
    // TODO Auto-generated method stub
    if (this != Lizzie.leelaz) return;
    if (Lizzie.config.autoAnaDiffFirstPlayouts > 0) {
      if (!bestMoves.isEmpty()
          && bestMoves.get(0).playouts >= Lizzie.config.autoAnaDiffFirstPlayouts) {
        Lizzie.board.getHistory().getCurrentHistoryNode().diffAnalyzed = true;
        return;
      }
    }
    if ((isZen && Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber < 3)) {
      Lizzie.board.getHistory().getCurrentHistoryNode().diffAnalyzed = true;
      return;
    }
    if (Lizzie.config.autoAnaDiffPlayouts > 0) {
      if (currentTotalPlayouts >= Lizzie.config.autoAnaDiffPlayouts) {
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
      if (!bestMoves.isEmpty() && bestMoves.get(0).playouts >= Lizzie.config.autoAnaFirstPlayouts) {
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
      if (currentTotalPlayouts >= Lizzie.config.autoAnaPlayouts) {
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
      if (!bestMoves.isEmpty() && bestMoves.get(0).playouts >= Lizzie.config.autoAnaFirstPlayouts) {
        analyzeNextMove(isLastMove);
        return;
      }
    }
    if ((isZen && Lizzie.board.getHistory().getCurrentHistoryNode().getData().moveNumber < 3)) {
      analyzeNextMove(isLastMove);
      return;
    }
    if (Lizzie.config.autoAnaPlayouts > 0) {
      if (currentTotalPlayouts >= Lizzie.config.autoAnaPlayouts) {
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
    if (!bestMoves.isEmpty()) {
      currentTotalPlayouts = MoveData.getPlayouts(bestMoves);
      Lizzie.board
          .getHistory()
          .getData()
          .tryToSetBestMoves(bestMoves, bestMovesEnginename, true, currentTotalPlayouts);
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
      best = this.bestMoves.get(0);
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
      if (currentTotalPlayouts >= playouts) shouldPlay = true;
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
          playMove = this.randomBestmove(bestMoves, LizzieFrame.toolbar.randomDiffWinrate, false);
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
    if (!sendStatefulOrdinaryCommand(command)) return;
    applyBoardSize(width, height, false);
    AtomicReference<RuntimeException> mirrorFailure = new AtomicReference<>();
    deferredDefaultMirrorFailure.set(mirrorFailure);
    try {
      mirrorStatefulOrdinaryCommand(command);
      Lizzie.board.reopen(width, height);
    } finally {
      deferredDefaultMirrorFailure.remove();
    }
    if (mirrorFailure.get() != null) {
      throw mirrorFailure.get();
    }
  }

  public void boardSizeForEngine(int width, int height) {
    if (width != height) sendCommand("rectangular_boardsize " + width + " " + height);
    else sendCommand("boardsize " + width);
    applyBoardSize(width, height, false);
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
    synchronized (this) {
      if (!sendStatefulOrdinaryCommand("komi " + (komi == 0.0 ? "0" : komi))) return;
      this.komi = (float) komi;
      Lizzie.board.getHistory().getGameInfo().setKomi(komi);
      //  Lizzie.board.getHistory().getGameInfo().changeKomi();
      Lizzie.board.clearBestMovesAfter(Lizzie.board.getHistory().getStart());
      mirrorStatefulOrdinaryCommand("komi " + (komi == 0.0 ? "0" : komi));
      if (isPondering) ponder();
    }
  }

  public void komiNoMenu(double komi) {
    synchronized (this) {
      if (!sendStatefulOrdinaryCommand("komi " + (komi == 0.0 ? "0" : komi))) return;
      this.komi = (float) komi;
      Lizzie.board.getHistory().getGameInfo().setKomiNoMenu(komi);
      //  Lizzie.board.getHistory().getGameInfo().changeKomi();
      Lizzie.board.clearBestMovesAfter(Lizzie.board.getHistory().getStart());
      mirrorStatefulOrdinaryCommand("komi " + (komi == 0.0 ? "0" : komi));
      if (isPondering) ponder();
    }
  }

  /**
   * Aligns the running engine with the displayed game's komi without changing the engine default or
   * discarding analysis embedded in a loaded SGF.
   */
  public boolean syncKomiForCurrentGame(double komi) {
    float normalizedKomi = (float) (komi == 0.0 ? 0.0 : komi);
    synchronized (this) {
      if (Float.compare(this.komi, normalizedKomi) == 0) {
        return false;
      }
      this.komi = normalizedKomi;
      sendCommand("komi " + (komi == 0.0 ? "0" : komi));
      return true;
    }
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
        failure.printStackTrace();
      }
    }
  }

  private void parseLineForError(String line, ReaderStreamBinding binding) {
    // TODO Auto-generated method stub
    if (!this.isLoaded) {
      if (line.toLowerCase().contains("cl_platform_not_found"))
        Utils.showMsgNoModal(Lizzie.resourceBundle.getString("Leelaz.openclPlatfromNotFound"));
    }
    if (!this.isLeela0110 || Lizzie.frame.isPlayingAgainstLeelaz)
      if (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp || !this.isLoaded)
        if (!line.startsWith("info")) Lizzie.gtpConsole.addErrorLine(line + "\n");
    if (isZen) {
      if ((EngineManager.isEngineGame && !EngineManager.engineGameInfo.isGenmove)
          || LizzieFrame.toolbar.isAutoPlay) {
        if ((isResponseUpToDate())) {
          if (line.contains("Nodes:")) {
            if (!this.bestMoves.isEmpty()) {
              notifyAutoPK(true);
              notifyAutoPlay(true);
            } else {
              if (EngineManager.isEngineGame) playPassInEngineGame();
              else Lizzie.board.pass();
            }
          } else if (line.contains("I pass")) {
            if (EngineManager.isEngineGame) playPassInEngineGame();
            else Lizzie.board.pass();
          } else if (line.toLowerCase().contains("resign")) {
            if (EngineManager.isEngineGame) {
              resigned = true;
              nameCmd();
              isResigning = true;
              if (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp)
                Lizzie.gtpConsole.addLine(
                    oriEnginename + " " + Lizzie.resourceBundle.getString("Leelaz.resign") + "\n");
              Lizzie.engineManager.stopEngineGame(currentEngineN, false);
            } else notifyAnaResign(true);
          }
        }
      }
      if (line.startsWith("info") && isLoaded) {
        isLoaded = false;
        if (Lizzie.frame != null && Lizzie.frame.isDisplayable()) {
          SwingUtilities.invokeLater(
              new Runnable() {
                public void run() {
                  Utils.showHtmlMessage(
                      Lizzie.resourceBundle.getString("Message.title"),
                      Lizzie.resourceBundle.getString("Leelaz.updateZenGtp"),
                      Lizzie.frame);
                }
              });
        }
        terminateReaderIncarnation(binding, null);
        return;
      }
      if (EngineManager.isEngineGame && EngineManager.engineGameInfo.isGenmove) {
        if (line.contains("->")) {
          synchronized (bestMoves) {
            try {
              MoveData mv = MoveData.fromSummaryZen(line);
              if (mv != null) {
                mv.order = bestMoves.size();
                bestMoves.add(mv);
              }
            } catch (Exception ex) {
              Lizzie.gtpConsole.addLine("genmovepk summary err");
            }
          }
        }
      }

      if ((Lizzie.frame.isPlayingAgainstLeelaz || isInputCommand)) {
        if (line.contains("->")) {
          int k =
              (Lizzie.config.limitMaxSuggestion > 0 && !Lizzie.config.showNoSuggCircle
                  ? Lizzie.config.limitMaxSuggestion
                  : 361);
          if (bestMoves.size() < k) {
            MoveData mv = MoveData.fromSummaryZen(line);
            if (mv != null) {

              mv.order = bestMoves.size();
              bestMoves.add(mv);
              currentTotalPlayouts = MoveData.getPlayouts(bestMoves);
              Lizzie.board
                  .getData()
                  .tryToSetBestMoves(bestMoves, bestMovesEnginename, true, currentTotalPlayouts);
            }
          }
        }
      }
    }
    if ((isLeela || isSai) && Lizzie.frame.isPlayingAgainstLeelaz && canGetSummaryInfo) {
      int k =
          (Lizzie.config.limitMaxSuggestion > 0 && !Lizzie.config.showNoSuggCircle
              ? Lizzie.config.limitMaxSuggestion
              : 361);
      if (bestMovesPrevious.size() < k) {
        if (line.contains("->")) {
          try {
            MoveData mv = isSai ? MoveData.fromSummarySai(line) : MoveData.fromSummary(line);
            if (mv != null) {
              mv.order = bestMovesPrevious.size();
              bestMovesPrevious.add(mv);
            }
          } catch (Exception ex) {
            Lizzie.gtpConsole.addLine("genmovepk summary err");
          }
        }
      }
    }
    if (isLeela0110 && !(EngineManager.isEngineGame && EngineManager.engineGameInfo.isGenmove)) {
      if (line.contains(" -> ")) {
        if (!isLoaded) {
          Lizzie.frame.refresh();
        }
        isLoaded = true;
        List<MoveData> bm = leela0110BestMoves;
        int k =
            (Lizzie.config.limitMaxSuggestion > 0 && !Lizzie.config.showNoSuggCircle
                ? Lizzie.config.limitMaxSuggestion
                : 361);
        if (!Lizzie.frame.isPlayingAgainstLeelaz && (bm.size() < k)) {
          MoveData mv = MoveData.fromSummaryLeela0110(line);
          mv.order = bm.size();
          bm.add(mv);
        }
      } else if (isLeela0110 && line.startsWith("=====")) {
        this.canCheckAlive = true;
        if (isLeela0110PonderingValid() && !leela0110BestMoves.isEmpty()) {
          bestMoves = leela0110BestMoves;
          currentTotalPlayouts = MoveData.getPlayouts(bestMoves);
          if (Lizzie.config.isDoubleEngineMode()
              && Lizzie.leelaz2 != null
              && this == Lizzie.leelaz2)
            Lizzie.board
                .getData()
                .tryToSetBestMoves2(bestMoves, bestMovesEnginename, true, currentTotalPlayouts);
          else
            Lizzie.board
                .getData()
                .tryToSetBestMoves(bestMoves, bestMovesEnginename, true, currentTotalPlayouts);
        }
        leela0110UpdatePonder();
        Lizzie.frame.requestAnalysisRefresh();
        if (!this.bestMoves.isEmpty()) {
          notifyAutoPK(false);
          notifyAutoPlay(false);
          if (Lizzie.config.isAutoAna) {
            if (Lizzie.frame.isAutoAnalyzingDiffNode) {
              nofityDiffAna();
            } else if (Lizzie.config.analyzeAllBranch) {
              notifyAutoAnaAllBranch();
            } else {
              notifyAutoAna();
            }
          }
        }
        return;
      } else {
        if (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp || !this.isLoaded)
          Lizzie.gtpConsole.addErrorLine(line + "\n");
      }
    }
    if (!this.isKatago) {
      if (line.startsWith("NN eval")) {
        String[] params = line.trim().split("=");
        heatwinrate =
            Double.valueOf(params[1].length() > 5 ? params[1].substring(0, 5) : params[1]);
      }
      if (line.startsWith("root eval")) {
        String[] params = line.trim().split("=");
        heatwinrate =
            Double.valueOf(params[1].length() > 5 ? params[1].substring(0, 5) : params[1]);
      }

      if (line.endsWith("nodes")) {
        if (!this.bestMoves.isEmpty()) {
          if (EngineManager.isEngineGame && !EngineManager.engineGameInfo.isGenmove) {
            if ((Lizzie.board.getHistory().isBlacksTurn()
                    && this
                        == Lizzie.engineManager.engineList.get(
                            EngineManager.engineGameInfo.blackEngineIndex))
                || !Lizzie.board.getHistory().isBlacksTurn()
                    && this
                        == Lizzie.engineManager.engineList.get(
                            EngineManager.engineGameInfo.whiteEngineIndex)) {
              if (isResponseUpToDate()) {
                if (!isGamePaused) {
                  notifyAutoPK(true);
                }
              }
            }
          }
          if (Lizzie.frame.isAnaPlayingAgainstLeelaz && !isGamePaused) notifyAutoPlay(true);
        }
      }
      if (line.startsWith("| ST")) {
        String[] params = line.trim().split(" ");
        if (params.length == 13) {
          isColorEngine = true;
          if (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp)
            Lizzie.gtpConsole.addLine(oriEnginename + ": " + line);
          stage = Integer.parseInt(params[3].substring(0, params[3].length() - 1));
          komi = Float.parseFloat(params[6].substring(0, params[6].length() - 1));
        }
      }
    } else {
      if ((Lizzie.frame.isPlayingAgainstLeelaz || EngineManager.isEngineGame)
          && line.startsWith("MALKOVICH:")) {
        if (line.contains("PDA")) {
          String value = line.substring(line.indexOf("PDA") + 4);
          value = value.substring(0, value.indexOf(")"));
          this.pda = Double.parseDouble(value);
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

  public boolean requestPositionEstimate(Consumer<List<Double>> consumer) {
    if (consumer == null
        || !isKatago
        || !started
        || !isLoaded
        || isNormalEnd
        || isProcessDead()
        || (!hasTrackingStreamSession() && rejectNewExclusiveWorkDuringGtpLease())) {
      return false;
    }
    Object requestOwner = new Object();
    int boardWidth = Board.boardWidth;
    int boardHeight = Board.boardHeight;
    QueuedCommandSettlement settlement =
        new QueuedCommandSettlement() {
          @Override
          public void onWriteClaimed() {
            synchronized (positionEstimateLock) {
              positionEstimateParser.begin(boardWidth, boardHeight);
              positionEstimateConsumer = consumer;
              positionEstimateRequestOwner = requestOwner;
            }
          }

          @Override
          public void onRequestFailed(RuntimeException failure) {
            synchronized (positionEstimateLock) {
              if (positionEstimateRequestOwner != requestOwner) {
                return;
              }
              positionEstimateParser.reset();
              positionEstimateConsumer = null;
              positionEstimateRequestOwner = null;
            }
          }
        };
    return sendCommand(
        "kata-raw-nn 0",
        null,
        null,
        false,
        false,
        TrackingReleaseReason.ORDINARY_OPERATION,
        settlement,
        true);
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

  private boolean sendStatefulOrdinaryCommand(String command) {
    boolean accepted =
        sendCommand(
            command,
            null,
            null,
            false,
            false,
            TrackingReleaseReason.ORDINARY_OPERATION,
            null,
            true);
    if (!accepted) {
      rejectNewExclusiveWorkDuringGtpLease();
    }
    return accepted;
  }

  private void mirrorStatefulOrdinaryCommand(String command) {
    Leelaz mirroredEngine = resolveDefaultCommandMirrorEngine();
    if (mirroredEngine != null) {
      sendDefaultCommandMirror(mirroredEngine, command);
    }
  }

  private void sendDefaultCommandMirror(Leelaz mirroredEngine, String command) {
    try {
      mirroredEngine.sendCommand(command);
      mirroredEngine.startPonderTime = this.startPonderTime;
    } catch (RuntimeException failure) {
      AtomicReference<RuntimeException> deferredFailure = deferredDefaultMirrorFailure.get();
      if (deferredFailure == null) {
        throw failure;
      }
      deferredFailure.compareAndSet(null, failure);
    }
  }

  public void cancelPositionEstimateRequest() {
    synchronized (positionEstimateLock) {
      positionEstimateParser.reset();
      positionEstimateConsumer = null;
      positionEstimateRequestOwner = null;
    }
  }

  private void parsePositionEstimateLine(String line) {
    Consumer<List<Double>> consumer = null;
    List<Double> ownership = null;
    synchronized (positionEstimateLock) {
      Optional<List<Double>> parsed = positionEstimateParser.accept(line);
      if (parsed.isPresent()) {
        ownership = parsed.get();
        consumer = positionEstimateConsumer;
        positionEstimateConsumer = null;
        positionEstimateRequestOwner = null;
      }
    }
    if (consumer != null) {
      try {
        consumer.accept(ownership);
      } catch (RuntimeException e) {
        e.printStackTrace();
      }
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
        if (getRcentLine) {
          if (line.startsWith("= {")) {
            recentRulesLine = line;
            Lizzie.config.currentKataGoRules = line;
            getSuicidalAndRules();
            getRcentLine = false;
          } else if (line.startsWith("=")) {
            String[] params = line.trim().split(" ");
            if (params.length == 2) {
              try {
                if (recentLineNumber == 0) {
                  this.pda = Double.parseDouble(params[1]);
                } else if (recentLineNumber == 1) {
                  wrn = Double.parseDouble(params[1]);
                  Lizzie.frame.setPdaAndWrn(pda, wrn);
                  recentLineNumber++;
                }
                recentLineNumber++;
              } catch (NumberFormatException e) {
              }
            }
          }
        }
        if (EngineManager.isEngineGame && EngineManager.engineGameInfo.isGenmove && isLoaded) {
          try {
            parseLineForGenmovePk(line, binding.stdout);
          } catch (IOException readFailure) {
            throw readFailure;
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
                binding.restartBootstrapReceipt, () -> parseLine(readerLine));
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
        if (isCommandLine) {
          if (!this.isKatago && !this.isLeela0110 && Lizzie.frame.isPlayingAgainstLeelaz) {
            Runnable runnable =
                new Runnable() {
                  public void run() {
                    try {
                      while (!isResponseUpToDate()) Thread.sleep(10);
                    } catch (InterruptedException e) {
                      // TODO Auto-generated catch block
                      e.printStackTrace();
                    }
                    if (Lizzie.board.getHistory().getCurrentHistoryNode().previous().isPresent()
                        && !bestMovesPrevious.isEmpty()) {
                      Lizzie.board
                          .getHistory()
                          .getCurrentHistoryNode()
                          .previous()
                          .get()
                          .getData()
                          .tryToSetBestMoves(
                              bestMovesPrevious,
                              bestMovesEnginename,
                              true,
                              MoveData.getPlayouts(bestMovesPrevious));
                      bestMovesPrevious = new ArrayList<>();
                    }
                    canGetSummaryInfo = false;
                  }
                };
            Thread thread = new Thread(runnable);
            thread.start();
          }
          String responseLine = line;
          runWithRestartBootstrapReceipt(
              binding.restartBootstrapReceipt,
              () -> processCommandResponseLine(responseLine, binding));
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
    } catch (IOException | RuntimeException readFailure) {
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
    synchronized (engineArbitrationLock()) {
      if (readerStreamBinding != binding || binding.terminated) {
        return;
      }
      binding.terminated = true;
      binding.terminalFailure = failure;
      if (binding.linesInProgress == 0) {
        binding.terminalCleanupStarted = true;
        readerTerminalCleanupInProgress = true;
        finishTerminalCleanup = true;
      }
    }
    if (finishTerminalCleanup) {
      finishReaderTerminalCleanup(binding);
    }
  }

  private void finishReaderTerminalCleanup(ReaderStreamBinding binding) {
    try {
      if (binding.terminalFailure != null) {
        binding.terminalFailure.printStackTrace();
      }
      System.out.println("engine process ended.");
      try {
        shutdownReaderTransport(binding);
      } catch (RuntimeException shutdownFailure) {
        shutdownFailure.printStackTrace();
      }
      if (binding.javaSSH != null) {
        javaSSHClosed = true;
      }
      started = false;
      finishTerminatedReaderIncarnation(binding);
    } finally {
      synchronized (engineArbitrationLock()) {
        readerTerminalCleanupInProgress = false;
        engineArbitrationLock().notifyAll();
      }
    }
  }

  private void finishTerminatedReaderIncarnation(ReaderStreamBinding binding) {
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
    failReadBoardGmaEngineRestore("engine transport closed");
    if (binding.remoteTransport != null && binding.remoteTransport.isRecoveryRequested()) {
      isDownWithError = true;
      rememberRecentLine(
          recentStderrLines,
          "Remote session retired; rebuilding with a fresh token and full board replay");
      if (Lizzie.engineManager != null) {
        Lizzie.engineManager.restartUnresponsiveRemoteEngine(this, currentEngineN);
      }
      return;
    }
    if (!isNormalEnd && !tryRecoverBundledOpenClNativeExit(binding.process)) {
      isDownWithError = true;
      // isLoaded=false;
      tryToDignostic(
          buildEngineExitDiagnostic(Lizzie.resourceBundle.getString("Leelaz.engineEndUnormalHint")),
          false);
      // ("打开Gtp窗口(快捷键E)查看报错信息");
      // LizzieFrame.openMoreEngineDialog();
    }
  }

  private void shutdownReaderTransport(ReaderStreamBinding binding) {
    cancelPositionEstimateRequest();
    leela0110StopPonder();
    if (binding.javaSSH != null) {
      binding.javaSSH.close();
    } else if (binding.remoteTransport != null) {
      binding.remoteTransport.close();
    } else if (binding.process != null) {
      binding.process.destroy();
    }
  }

  private boolean tryRecoverBundledOpenClNativeExit() {
    return tryRecoverBundledOpenClNativeExit(process);
  }

  private boolean tryRecoverBundledOpenClNativeExit(Process expectedProcess) {
    if (expectedProcess == null
        || useRemoteCompute
        || useJavaSSH
        || openClCompatibilityRecoveryAttempted.get()) {
      return false;
    }
    int exitCode;
    try {
      exitCode = expectedProcess.exitValue();
    } catch (IllegalThreadStateException e) {
      return false;
    }
    Path engineExecutable = KataGoRuntimeHelper.resolveCommandExecutable(commands);
    if (!KataGoRuntimeHelper.shouldRecoverOpenClNativeExit(
        commands, engineExecutable, exitCode, openClFp32CompatibilityActive)) {
      return false;
    }
    ExclusiveGtpLifecycleReservation reservation = beginAutomaticEngineRestartReservation();
    if (reservation == null
        || !openClCompatibilityRecoveryAttempted.compareAndSet(false, true)
        || !KataGoRuntimeHelper.rememberOpenClFp32Compatibility(commands, engineExecutable)) {
      if (reservation != null) {
        reservation.close();
      }
      return false;
    }

    isDownWithError = false;
    isLoaded = false;
    canCheckAlive = false;
    if (this == Lizzie.leelaz) {
      Lizzie.engineStartupStatus.checking(
          "BundledEngineStartup.status.openclRecovering",
          "NVIDIA OpenCL compatibility recovery is starting...");
      if (Lizzie.frame != null) {
        SwingUtilities.invokeLater(Lizzie.frame::prepareQuickAnalysisForPrimaryOpenClRecovery);
      }
    }
    int engineIndex = currentEngineN;
    Thread recovery =
        new Thread(
            () -> {
              boolean restoreScheduled = false;
              try {
                restartClosedEngine(engineIndex, reservation::close);
                restoreScheduled = true;
              } catch (IOException | RuntimeException failure) {
                failure.printStackTrace();
                isDownWithError = true;
                SwingUtilities.invokeLater(
                    () ->
                        tryToDignostic(
                            buildEngineExitDiagnostic(
                                text(
                                    "BundledEngineStartup.openclRecoveryFailed",
                                    "NVIDIA OpenCL compatibility recovery failed.")),
                            false));
              } finally {
                if (!restoreScheduled) {
                  reservation.close();
                }
              }
            },
            "katago-opencl-fp32-recovery");
    recovery.setDaemon(true);
    recovery.start();
    return true;
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
    sendCommand(command, null);
  }
  void sendCommandWithResponseForTest(String command, Runnable onResponse) {
    sendCommand(command, onResponse, false, false);
  }

  void beginForegroundRestoreForTest() {
    foregroundRestoreInProgress = true;
    suppressNormalCommandsForForegroundAnalysis = true;
  }

  void installCommandOutputForTest(OutputStream stream) {
    outputStream = createCommandOutputStream(stream);
  }

  void processCommandResponseLineForTest(String line) {
    processCommandResponseLine(line);
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

  private void sendCommand(
      String command,
      Runnable onResponse,
      CommandSendFailureHandler onSendFailure,
      boolean failOnSendError,
      boolean mirrorToSecondEngine) {
    sendCommand(
        command,
        onResponse,
        onSendFailure,
        failOnSendError,
        mirrorToSecondEngine,
        TrackingReleaseReason.ORDINARY_OPERATION,
        null,
        false);
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
    if (shouldDropStaleForegroundRestoreCommand()
        || shouldSuppressNormalCommandForForegroundAnalysis()) {
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
    if (!enqueueOrdinaryCommand(
        command,
        onResponse,
        onSendFailure,
        failOnSendError || foregroundRestoreCommandSession.get() != null,
        settlement,
        releaseReason,
        rejectForExclusiveWinner,
        true,
        false)) {
      return false;
    }
    trySendCommandFromQueue();
    if (Lizzie.frame.isAutocounting) {
      Lizzie.frame.forwardAutoPositionEstimateCommand(command, true);
    }
    Leelaz mirroredEngine = mirrorToSecondEngine ? resolveDefaultCommandMirrorEngine() : null;
    if (mirroredEngine != null) {
      sendDefaultCommandMirror(mirroredEngine, command);
    }
    return true;
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
      boolean noLeelaz2Coalescing) {
    ArrayDeque<QueuedCommand> currentQueue = commandQueue();
    RestartBootstrapReceipt bootstrapReceipt = restartBootstrapReceiptContext.get();
    if (Thread.holdsLock(currentQueue)
        && exclusiveGtpSession == null
        && trackingHandoffGate == null
        && settlement == null) {
      if (shouldDropStaleForegroundRestoreCommand()
          || shouldSuppressNormalCommandForForegroundAnalysis()
          || (restartBootstrapReceipt != null
              && exclusiveGtpLifecycleQueueGate
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
                  command, onResponse, onSendFailure, failOnSendError, null, bootstrapReceipt),
              targetQueue));
      return true;
    }
    QueuedCommand coalesced = null;
    TrackingDispositionNotification dispositionNotification = null;
    ExclusiveGtpSession trackingSession = null;
    int releaseStopCommandId = 0;
    synchronized (engineArbitrationLock()) {
      synchronized (commandQueue()) {
        if (shouldDropStaleForegroundRestoreCommand()
            || shouldSuppressNormalCommandForForegroundAnalysis()
            || (restartBootstrapReceipt != null
                && exclusiveGtpLifecycleQueueGate
                && !isCurrentRestartBootstrapReceiptLocked(bootstrapReceipt))
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
        trackingSession = exclusiveGtpSession;
        if (isTrackingStreamSession(trackingSession)
            && releaseReason == TrackingReleaseReason.SAFE_READ_ONLY_QUERY
            && !isSafeRawGtpQuery(command)) {
          return false;
        }
        ArrayDeque<QueuedCommand> targetQueue = commandQueueForCurrentThread();
        if (countCommand) {
          cmdNumber++;
          calculateModifyNumber();
        }
        if (!targetQueue.isEmpty()
            && shouldCoalesceQueuedCommand(targetQueue.peekLast().command, noLeelaz2Coalescing)) {
          coalesced = targetQueue.removeLast();
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
                settlement,
                    bootstrapReceipt),
                targetQueue));
        if (isTrackingStreamSession(trackingSession) && trackingHandoffGate == null) {
          TrackingReleaseDisposition disposition =
              releaseReason == TrackingReleaseReason.SAFE_READ_ONLY_QUERY
                  ? TrackingReleaseDisposition.FROZEN_BY_SAFE
                  : TrackingReleaseDisposition.CLEARED;
          dispositionNotification =
              advanceTrackingReleaseDispositionLocked(trackingSession, disposition, releaseReason);
          if (!trackingSession.releaseRequested) {
            trackingSession.releaseRequested = true;
            if (trackingSession.active) {
              releaseStopCommandId = claimTrackingReleaseStopLocked(trackingSession);
            }
          }
        }
      }
    }
    if (coalesced != null) {
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
    notifyTrackingDisposition(dispositionNotification);
    if (releaseStopCommandId != 0) {
      sendTrackingReleaseStop(trackingSession, releaseStopCommandId);
    }
    return true;
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
    if (afterConsumed == null) {
      throw new IllegalArgumentException("afterConsumed");
    }
    if (!isExactSnapshotRestoreAdmissionValid(admission)
        || (mirroredEngine != null
            && !mirroredEngine.isExactSnapshotRestoreAdmissionValid(admission))) {
      throw new IllegalStateException("Exact snapshot restore loadsgf was not admitted.");
    }
    withExactSnapshotRestoreAdmission(
        admission,
        () -> {
          if (onDispatchStarted != null) {
            onDispatchStarted.run();
          }
          loadTrackedSgf(sgfFile, mirroredEngine, afterConsumed, admission);
        });
  }

  private void loadTrackedSgf(
      Path sgfFile,
      Leelaz mirroredEngine,
      Runnable afterConsumed,
      ExactSnapshotRestoreAdmission admission) {
    LoadSgfDispatch dispatch = new LoadSgfDispatch(afterConsumed);
    RuntimeException sendFailure =
        sendTrackedLoadSgfCommand(this, sgfFile, dispatch, admission);
    RuntimeException mirroredSendFailure = null;
    if (mirroredEngine != null) {
      mirroredSendFailure =
          sendTrackedLoadSgfCommand(mirroredEngine, sgfFile, dispatch, admission);
    }
    if (sendFailure == null) {
      sendFailure = mirroredSendFailure;
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
    sendLoadSgfCommand(targetEngine, sgfFile, onResponse, onSendFailure, null);
  }

  private void sendLoadSgfCommand(
      Leelaz targetEngine,
      Path sgfFile,
      Runnable onResponse,
      CommandSendFailureHandler onSendFailure,
      ExactSnapshotRestoreAdmission admission) {
    if (admission != null) {
      String command = "loadsgf " + sgfFile.toAbsolutePath();
      if (!targetEngine.sendExactSnapshotRestoreCommand(command, onResponse, onSendFailure)) {
        throw new IllegalStateException(
            "Exact snapshot restore loadsgf command was rejected: " + command);
      }
      return;
    }
    targetEngine.sendCommand(
        "loadsgf " + sgfFile.toAbsolutePath(), onResponse, onSendFailure, true, false);
  }

  private RuntimeException sendTrackedLoadSgfCommand(
      Leelaz targetEngine, Path sgfFile, LoadSgfDispatch dispatch) {
    return sendTrackedLoadSgfCommand(targetEngine, sgfFile, dispatch, null);
  }

  private RuntimeException sendTrackedLoadSgfCommand(
      Leelaz targetEngine,
      Path sgfFile,
      LoadSgfDispatch dispatch,
      ExactSnapshotRestoreAdmission admission) {
    TrackedLoadSgfConsumer trackedConsumer =
        new TrackedLoadSgfConsumer(targetEngine, sgfFile, dispatch);
    try {
      Runnable send =
          () ->
              sendLoadSgfCommand(
                  targetEngine,
                  sgfFile,
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

  private RuntimeException buildLoadSgfResponseFailure(Path sgfFile, String responseLine) {
    String line = responseLine == null ? "" : responseLine.trim();
    String detail = line.isEmpty() ? "? loadsgf failed" : line;
    String message =
        "GTP loadsgf failed for '" + sgfFile.toAbsolutePath() + "' with response: " + detail;
    return new IllegalStateException(message);
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
    if (isKatago) {
      scoreMean = 0;
      scoreStdev = 0;
    }
    bestMoves = new ArrayList<>();
    currentTotalPlayouts = 0;
    currentCmdNum = Math.max(cmdNumber - 2, currentCmdNum);
  }

  public final void sendCapturedRestoreCommand(String command) {
    ExactSnapshotRestoreAdmission admission = exactSnapshotRestoreAdmissionContext.get();
    if (!sendExactSnapshotRestoreCommand(command, admission)) {
      throw new IllegalStateException("Captured snapshot restore command was rejected: " + command);
    }
  }

  final boolean sendExactSnapshotRestoreCommand(
      String command, Runnable onResponse, CommandSendFailureHandler onSendFailure) {
    return sendCommand(
        command,
        onResponse,
        onSendFailure,
        true,
        false,
        TrackingReleaseReason.ORDINARY_OPERATION,
        null,
        true);
  }

  boolean sendExactSnapshotRestoreCommand(
      String command, ExactSnapshotRestoreAdmission admission) {
    if (!isExactSnapshotRestoreAdmissionValid(admission)) {
      return false;
    }
    return sendExactSnapshotRestoreCommand(command, null, null);
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
            onFailure.accept(failure == null ? "Failed to send configuration" : failure.getMessage());
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
    if (!enqueueOrdinaryCommand(
        command,
        onResponse,
        null,
        foregroundRestoreCommandSession.get() != null,
        null,
        TrackingReleaseReason.ORDINARY_OPERATION,
        false,
        false,
        true)) {
      return;
    }
    trySendCommandFromQueue();
    if (Lizzie.frame.isAutocounting) {
      Lizzie.frame.forwardAutoPositionEstimateCommand(command, true);
    }
    if (canSetNotPlayed) {
      canSetNotPlayed = false;
      played = false;
    }
  }

  /** Sends a command from command queue for leelaz to execute if it is ready */
  private void trySendCommandFromQueue() {
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
    RuntimeException sendFailure = null;
    try {
      deferredResponse =
          sendCommandToLeelaz(command, queuedCommand);
    } catch (RuntimeException ex) {
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
      try {
        if (queuedCommand.onSendFailure != null) {
          queuedCommand.onSendFailure.onSendFailure(sendFailure);
        }
      } finally {
        trySendCommandFromQueue();
      }
      throw sendFailure;
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
      currentCmdNum++;
    }
    Runnable responseHandler =
        queuedCommand.onResponse == null ? NO_OP_RESPONSE_HANDLER : queuedCommand.onResponse;
    PendingResponseHandler pendingHandler =
        buildPendingResponseHandler(command, responseHandler, queuedCommand);
    String commandLine = buildCommandLine(command, pendingHandler.responseCommandId);
    BufferedOutputStream currentOutputStream = outputStream;
    if (currentOutputStream != null) {
      if (!claimRestartBootstrapOutputWrite(queuedCommand, currentOutputStream)) {
        return null;
      }
      if (!addPendingResponseHandler(pendingHandler)) {
        return null;
      }
      try {
        synchronized (currentOutputStream) {
          if (queuedCommand.restartBootstrapReceipt == null
              && !queuedCommand.beginOutputWrite()) {
            removePendingResponseHandler(pendingHandler);
            return null;
          }
          currentOutputStream.write((commandLine + "\n").getBytes());
          currentOutputStream.flush();
        }
      } catch (Exception e) {
        boolean pollutedStreamDetected =
            clearBufferedCommandBytesAfterSendFailure(currentOutputStream);
        if (pollutedStreamDetected) {
          invalidateCommandOutputStreamAfterPartialWrite(currentOutputStream, commandLine);
        }
        removePendingResponseHandler(pendingHandler);
        retireOutstandingResponseCountOnSendFailure(pendingHandler);
        String detail = e.getLocalizedMessage();
        if (detail == null || detail.trim().isEmpty()) {
          detail = e.getClass().getSimpleName();
        }
        rememberRecentLine(
            recentStderrLines, "Failed to send GTP command '" + commandLine + "': " + detail);
        System.err.println("Failed to send GTP command '" + commandLine + "': " + detail);
        RuntimeException commandFailure = buildCommandSendFailure(commandLine, detail, e);
        queuedCommand.markStateResetAfterOutputWrite(commandFailure);
        queuedCommand.publishStateResetAfterOutputWrite();
        if (queuedCommand.failOnSendError) {
          throw commandFailure;
        }
        deferredResponse = queuedCommand.onResponse;
      }
      if (EngineManager.isEngineGame()) {
        Lizzie.gtpConsole.addCommandForEngineGame(
            command,
            cmdNumber,
            oriEnginename,
            EngineManager.engineGameInfo.isBlackEngine(currentEngineN()));

      } else if (Lizzie.gtpConsole != null
          && ((Lizzie.config != null && Lizzie.config.alwaysGtp)
              || Lizzie.gtpConsole.isVisible())) {
        Lizzie.gtpConsole.addCommand(command, cmdNumber, oriEnginename);
      }
    } else {
      String detail = "outputStream unavailable";
      rememberRecentLine(
          recentStderrLines, "Failed to send GTP command '" + commandLine + "': " + detail);
      System.err.println("Failed to send GTP command '" + commandLine + "': " + detail);
      RuntimeException commandFailure = buildCommandSendFailure(commandLine, detail, null);
      if (queuedCommand.cancelBeforeOutputWrite(commandFailure)) {
        queuedCommand.publishSettlementFailure(commandFailure);
      }
      if (queuedCommand.failOnSendError) {
        retireOutstandingResponseCountOnSendFailure(pendingHandler);
        throw commandFailure;
      }
      deferredResponse = queuedCommand.onResponse;
    }
    if (canSetNotPlayed) {
      canSetNotPlayed = false;
      played = false;
    }
    return deferredResponse;
  }

  private boolean claimRestartBootstrapOutputWrite(
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
        return queuedCommand.beginOutputWrite();
      }
    }
  }

  private RuntimeException buildCommandSendFailure(String command, String detail, Exception cause) {
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

  private void retireOutstandingResponseCountOnSendFailure(PendingResponseHandler pendingHandler) {
    if (pendingHandler.responseCommandId == NO_RESPONSE_COMMAND_ID) {
      return;
    }
    synchronized (commandQueue()) {
      if (pendingHandler.isOutstandingResponseRetired()) {
        return;
      }
      cmdNumber = Math.max(1, cmdNumber - 1);
      if (currentCmdNum > cmdNumber - 1) {
        currentCmdNum = cmdNumber - 1;
      }
    }
    try {
      trySendCommandFromQueue();
    } catch (Exception ex) {
      ex.printStackTrace();
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

  private PendingResponseHandler buildPendingResponseHandler(
      String command, Runnable handler, QueuedCommand queuedCommand) {
    boolean exactLoadSgf = isExactSnapshotLoadSgf(command, handler);
    return new PendingResponseHandler(
        command,
        handler,
        queuedCommand,
        nextResponseCommandId(command, handler),
        requiresMatchingResponseCommandId(command, handler, exactLoadSgf),
        exactLoadSgf);
  }

  private boolean isExactSnapshotLoadSgf(String command, Runnable handler) {
    return command != null
        && command.startsWith("loadsgf ")
        && handler != NO_OP_RESPONSE_HANDLER
        && isExactSnapshotRestoreAdmissionContextActive();
  }

  private boolean requiresMatchingResponseCommandId(
      String command, Runnable handler, boolean exactLoadSgf) {
    return handler instanceof BoardSynchronizationResponseHandler
        || exactLoadSgf
        || (command != null
            && handler != NO_OP_RESPONSE_HANDLER
            && (command.startsWith("kata-get-param ")
                || command.startsWith("kata-set-param ")));
  }

  private int nextResponseCommandId(String command, Runnable handler) {
    if (command != null && command.startsWith("loadsgf ")) {
      return loadSgfResponseCommandIds.getAndIncrement();
    }
    if (handler instanceof BoardSynchronizationResponseHandler) {
      return boardSynchronizationResponseCommandIds.getAndIncrement();
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
    boolean retired = false;
    synchronized (commandQueue()) {
      Iterator<QueuedCommand> iterator = commandQueue().iterator();
      while (iterator.hasNext()) {
        QueuedCommand queuedCommand = iterator.next();
        if (queuedCommand.onResponse != handler) {
          continue;
        }
        iterator.remove();
        cmdNumber = Math.max(1, cmdNumber - 1);
        if (currentCmdNum > cmdNumber - 1) {
          currentCmdNum = cmdNumber - 1;
        }
        retired = true;
        break;
      }
      if (!retired) {
        PendingResponseHandler removedHandler = removePendingResponseHandler(handler);
        if (removedHandler != null) {
          retirePendingResponseCountWithoutResponse(removedHandler);
          if (removedHandler.isExactSnapshotLoadSgf()) {
            loadSgfResponseQuarantined = true;
          }
          retired = true;
        }
      }
    }
    if (retired && !engineStateUnrestored) {
      try {
        trySendCommandFromQueue();
      } catch (RuntimeException ex) {
        ex.printStackTrace();
      }
    }
  }

  private void retireTrackedLoadSgfWithoutResponse(Runnable handler) {
    if (handler == null) {
      return;
    }
    boolean retired = false;
    synchronized (commandQueue()) {
      Iterator<QueuedCommand> iterator = commandQueue().iterator();
      while (iterator.hasNext()) {
        QueuedCommand queuedCommand = iterator.next();
        if (queuedCommand.onResponse != handler) {
          continue;
        }
        if (queuedCommand.command == null || !queuedCommand.command.startsWith("loadsgf ")) {
          continue;
        }
        iterator.remove();
        cmdNumber = Math.max(1, cmdNumber - 1);
        if (currentCmdNum > cmdNumber - 1) {
          currentCmdNum = cmdNumber - 1;
        }
        retired = true;
        break;
      }
      if (!retired) {
        PendingResponseHandler removedHandler = removePendingResponseHandler(handler);
        if (removedHandler != null) {
          retirePendingResponseCountWithoutResponse(removedHandler);
          if (removedHandler.isExactSnapshotLoadSgf()) {
            loadSgfResponseQuarantined = true;
          }
          retired = true;
        }
      }
    }
    if (retired) {
      try {
        trySendCommandFromQueue();
      } catch (RuntimeException ex) {
        ex.printStackTrace();
      }
    }
  }

  private void retirePendingResponseCountWithoutResponse(PendingResponseHandler handler) {
    if (!handler.isOutstandingResponseRetired() && currentCmdNum < cmdNumber - 1) {
      currentCmdNum++;
    }
    if (currentCmdNum > cmdNumber - 1) {
      currentCmdNum = cmdNumber - 1;
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
    int responseCommandId = parseResponseCommandId(line);
    ArrayDeque<PendingResponseHandler> handlers = pendingResponseHandlers();
    synchronized (handlers) {
      if (handlers.isEmpty()) {
        return null;
      }
      if (responseCommandId == NO_RESPONSE_COMMAND_ID) {
        return handlers.peekFirst().requiresMatchingResponseCommandId
            ? null
            : handlers.removeFirst();
      }
      Iterator<PendingResponseHandler> iterator = handlers.iterator();
      while (iterator.hasNext()) {
        PendingResponseHandler handler = iterator.next();
        if (handler.responseCommandId == responseCommandId) {
          iterator.remove();
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
    boolean foregroundRestoreResponseError = false;
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
          matchedPendingHandler =
              quarantinedUnnumberedResponse ? null : pollPendingResponseHandler(line);
          RestartBootstrapReceipt receipt =
              matchedPendingHandler == null
                  ? null
                  : matchedPendingHandler.queuedCommand.restartBootstrapReceipt;
          boolean staleBootstrapResponse =
              receipt != null
                  && (responseBinding != receipt.binding
                      || !isCurrentRestartBootstrapReceiptLocked(receipt));
          ignoreResponse =
              staleBootstrapResponse
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
              && (matchedPendingHandler == null
                  || !matchedPendingHandler.isOutstandingResponseRetired())) {
            currentCmdNum++;
            if (currentCmdNum > cmdNumber - 1) {
              currentCmdNum = cmdNumber - 1;
            }
          }
        }
      }
      if (!ignoreResponse) {
        if (foregroundRestoreResponseError) {
          failForegroundRestore(foregroundRestoreSession, "restore command failed: " + line.trim());
        }
        if (matchedPendingHandler != null) {
          matchedPendingHandler.run();
        }
      }
      acknowledgeExclusiveGtpInitialStop(line);
    } finally {
      currentCommandResponseLine = "";
      currentCommandResponseError = false;
    }
    if (ignoreResponse) {
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
    if (exclusiveGtpLifecycleTransition) {
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
    if (exclusiveGtpLifecycleTransition) {
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
          || exclusiveGtpLifecycleTransition;
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
          && readBoardGmaReservation == null
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
          || foregroundRestoreInProgress) {
        return true;
      }
      return exclusiveGtpLifecycleTransition && exclusiveGtpLifecycleOwner != Thread.currentThread();
    }
  }

  /** Captures the board-sync owner for one immutable exact restore plan. */
  public ExactSnapshotRestoreAdmission captureBoardSyncExactSnapshotRestoreAdmission() {
    return captureExactSnapshotRestoreAdmission(
        ExactSnapshotRestoreOwner.BOARD_SYNC, null, resolveLoadSgfMirrorEngine());
  }

  /** Captures the arbitration owner for one immutable exact restore plan. */
  ExactSnapshotRestoreAdmission captureExactSnapshotRestoreAdmission(
      ExactSnapshotRestoreOwner owner, Object ownerIdentity, Leelaz mirror) {
    if (owner == null) {
      throw new IllegalArgumentException("owner");
    }
    Object capturedOwnerIdentity = ownerIdentity;
    synchronized (engineArbitrationLock()) {
      if (owner == ExactSnapshotRestoreOwner.READ_BOARD_GMA && capturedOwnerIdentity == null) {
        capturedOwnerIdentity = readBoardGmaReservation;
      }
      if (!canCaptureExactSnapshotRestoreAdmission(owner, capturedOwnerIdentity)) {
        throw new ExactSnapshotRestoreAdmissionException(
            "Exact snapshot restore is not admitted for owner " + owner);
      }
    }
    if (mirror != null
        && !mirror.canAcceptExactSnapshotRestoreAdmission(this, owner, capturedOwnerIdentity)) {
      throw new ExactSnapshotRestoreAdmissionException(
          "Exact snapshot restore mirror is not admitted for owner " + owner);
    }
    return new ExactSnapshotRestoreAdmission(this, mirror, owner, capturedOwnerIdentity);
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
    return engineStateUnrestored
        || readBoardGmaReservation != null
        || trackingHandoffGate != null
        || foregroundRestoreInProgress
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
            : !hasConflictingExactSnapshotRestoreWorkLocked();
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
              || authority.readBoardGmaReservation != admission.ownerIdentity) {
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
            : !hasConflictingExactSnapshotRestoreWorkLocked();
      }
    }
    return true;
  }

  private boolean isExactSnapshotRestoreAdmissionContextActive() {
    return isExactSnapshotRestoreAdmissionValid(exactSnapshotRestoreAdmissionContext.get());
  }

  void requireExactSnapshotRestoreAdmission(ExactSnapshotRestoreAdmission admission) {
    if (!isExactSnapshotRestoreAdmissionValid(admission)) {
      throw new IllegalStateException("Exact snapshot restore admission is no longer valid");
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
    ExactSnapshotEngineRestore.PreparedRestore preparedRestore;
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
      preparedRestore = prepareForegroundRestore(session);
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
      session.preparedRestore = preparedRestore;
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

  private ExactSnapshotEngineRestore.PreparedRestore prepareForegroundRestore(
      ExclusiveGtpSession session) {
    Board board = Lizzie.board;
    BoardHistoryList history = board == null ? null : board.getHistory();
    if (history == null) {
      return null;
    }
    Leelaz mirror = resolveLoadSgfMirrorEngine();
    ExactSnapshotRestoreAdmission admission =
        captureExactSnapshotRestoreAdmission(ExactSnapshotRestoreOwner.FOREGROUND, session, mirror);
    return ExactSnapshotEngineRestore.prepare(admission, history.getCurrentHistoryNode())
        .orElse(null);
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
          expectedSession.readerBinding.terminated = true;
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
      if (session.preparedRestore != null) {
        if (session.originalRules != null) {
          sendCommand("kata-set-rules " + session.originalRules);
        }
        Lizzie.board.resendMoveToEngine(this, false, session.preparedRestore);
      } else {
        int currentBoardWidth = Board.boardWidth;
        int currentBoardHeight = Board.boardHeight;
        sendCommand(
            currentBoardWidth == currentBoardHeight
                ? "boardsize " + currentBoardWidth
                : "rectangular_boardsize " + currentBoardWidth + " " + currentBoardHeight);
        width = currentBoardWidth;
        height = currentBoardHeight;
        double currentKomi = komi;
        BoardHistoryList currentHistory = Lizzie.board == null ? null : Lizzie.board.getHistory();
        if (currentHistory != null && currentHistory.getGameInfo() != null) {
          currentKomi = currentHistory.getGameInfo().getKomi();
        }
        sendCommand("komi " + (currentKomi == 0.0 ? "0" : currentKomi));
        komi = (float) currentKomi;
        if (session.originalRules != null) {
          sendCommand("kata-set-rules " + session.originalRules);
        }
        Lizzie.board.resendMoveToEngineFromCurrentRoot(this);
      }
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
    synchronized (engineArbitrationLock()) {
      if (session == null || session.restoreCompleted) {
        return;
      }
      retryRestore = !session.restoreFailed && session.restoreInvalidated;
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
      }
    }
    if (retryRestore) {
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore;
      try {
        preparedRestore = prepareForegroundRestore(session);
      } catch (ExactSnapshotRestoreAdmissionException conflict) {
        failForegroundRestore(session, conflict.getMessage());
        return;
      }
      synchronized (engineArbitrationLock()) {
        if (session.restoreCompleted || foregroundRestoreSession != session) {
          return;
        }
        session.preparedRestore = preparedRestore;
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
    try {
      synchronized (commandQueue()) {
        foregroundRestoreCommandQueue().clear();
      }
      try {
        trySendCommandFromQueue();
      } catch (RuntimeException ex) {
        ex.printStackTrace();
      }
      if (session.wasPondering
          && Lizzie.leelaz == this
          && canResumePonderAfterForegroundLease()) {
        ponder();
      }
    } finally {
      finishForegroundRestoreLifecycle();
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
            "Engine command state reset interrupted loadsgf after restore failure: " + detail);
    List<QueuedCommand> cancelledLoadSgfCommands = new ArrayList<>();
    List<QueuedCommand> sentLoadSgfCommands = new ArrayList<>();
    ArrayDeque<PendingResponseHandler> handlers = pendingResponseHandlers();
    cancelQueuedLoadSgfCommands(commandQueue(), failure, cancelledLoadSgfCommands);
    cancelQueuedLoadSgfCommands(foregroundRestoreCommandQueue(), failure, cancelledLoadSgfCommands);
    classifyTrackedLoadSgfReset(
        normalCommandBeingSent, failure, cancelledLoadSgfCommands, sentLoadSgfCommands);
    commandQueue().clear();
    foregroundRestoreCommandQueue().clear();
    synchronized (handlers) {
      Iterator<PendingResponseHandler> iterator = handlers.iterator();
      while (iterator.hasNext()) {
        PendingResponseHandler handler = iterator.next();
        if (handler.queuedCommand.requiresStateReset()) {
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
    return new GtpCommandStateReset(failure, cancelledLoadSgfCommands, sentLoadSgfCommands);
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
    if (firstFailure instanceof RuntimeException) {
      throw (RuntimeException) firstFailure;
    }
    if (firstFailure instanceof Error) {
      throw (Error) firstFailure;
    }
  }

  private void cancelQueuedLoadSgfCommands(
      ArrayDeque<QueuedCommand> queue,
      RuntimeException failure,
      List<QueuedCommand> cancelledCommands) {
    for (QueuedCommand command : queue) {
      if (command.requiresStateReset() && command.cancelBeforeOutputWrite(failure)) {
        addUniqueCommand(cancelledCommands, command);
      }
    }
  }

  private boolean classifyTrackedLoadSgfReset(
      QueuedCommand command,
      RuntimeException failure,
      List<QueuedCommand> cancelledCommands,
      List<QueuedCommand> sentCommands) {
    if (command == null || !command.requiresStateReset()) {
      return false;
    }
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

  private void finishForegroundRestoreLifecycle() {
    synchronized (engineArbitrationLock()) {
      exclusiveGtpLifecycleTransition = false;
      exclusiveGtpLifecycleQueueGate = false;
      exclusiveGtpLifecycleOwner = null;
      exclusiveGtpLifecycleDepth = 0;
    }
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
      flushBufferedBytesToUnderlying();
      out.flush();
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
    private boolean requiresMatchingResponseCommandId;

    private PendingResponseHandler(
        String command,
        Runnable handler,
        QueuedCommand queuedCommand,
        int responseCommandId,
        boolean requiresMatchingResponseCommandId,
        boolean exactSnapshotLoadSgf) {
      this.command = command;
      this.handler = handler;
      this.queuedCommand = queuedCommand;
      this.responseCommandId = responseCommandId;
      this.requiresMatchingResponseCommandId = requiresMatchingResponseCommandId;
      this.exactSnapshotLoadSgf = exactSnapshotLoadSgf;
    }

    private boolean isExactSnapshotLoadSgf() {
      return exactSnapshotLoadSgf;
    }

    private boolean isTrackedLoadSgf() {
      return command != null && command.startsWith("loadsgf ") && queuedCommand.isTrackedLoadSgf();
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
    private ExactSnapshotEngineRestore.PreparedRestore preparedRestore;
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

    @Override
    public void close() {
      Leelaz reservedEngine;
      synchronized (this) {
        reservedEngine = engine;
        engine = null;
      }
      if (reservedEngine != null) {
        reservedEngine.clearAutomaticRestartPreparation(this);
        reservedEngine.endExclusiveGtpLifecycleTransition(owner);
      }
    }
  }

  public static final class ExactSnapshotRestoreAdmission {
    private final Leelaz authority;
    private final Leelaz mirror;
    private final ExactSnapshotRestoreOwner owner;
    private final Object ownerIdentity;

    private ExactSnapshotRestoreAdmission(
        Leelaz authority, Leelaz mirror, ExactSnapshotRestoreOwner owner, Object ownerIdentity) {
      this.authority = authority;
      this.mirror = mirror;
      this.owner = owner;
      this.ownerIdentity = ownerIdentity;
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
  }

  private static final class RestartRestorePreparation {
    private final Leelaz target;
    private final Leelaz mirror;
    private final Object owner;
    private final ExactSnapshotRestoreAdmission admission;
    private final ExactSnapshotEngineRestore.PreparedRestore preparedRestore;
    private final ArrayList<Movelist> rootMoves;
    private final Double rootKomi;
    private final boolean resumePonder;
    private final AtomicBoolean rootReplayExecuted = new AtomicBoolean(false);

    private RestartRestorePreparation(
        Leelaz target,
        Leelaz mirror,
        Object owner,
        ExactSnapshotRestoreAdmission admission,
        ExactSnapshotEngineRestore.PreparedRestore preparedRestore,
        ArrayList<Movelist> rootMoves,
        Double rootKomi,
        boolean resumePonder) {
      this.target = target;
      this.mirror = mirror;
      this.owner = owner;
      this.admission = admission;
      this.preparedRestore = preparedRestore;
      this.rootMoves = Movelist.copyList(rootMoves);
      this.rootKomi = rootKomi;
      this.resumePonder = resumePonder;
    }

    private static RestartRestorePreparation capture(
        Leelaz target,
        BoardHistoryNode historyTarget,
        Double komi,
        ArrayList<Movelist> rootMoves,
        boolean resumePonder) {
      Leelaz mirror = target.resolveLoadSgfMirrorEngine();
      Object owner = new Object();
      ExactSnapshotRestoreAdmission admission =
          target.captureExactSnapshotRestoreAdmission(
              ExactSnapshotRestoreOwner.LIFECYCLE, owner, mirror);
      ExactSnapshotEngineRestore.PreparedRestore preparedRestore = null;
      if (historyTarget != null) {
        preparedRestore =
            ExactSnapshotEngineRestore.prepare(admission, historyTarget)
                .orElse(null);
      }
      return new RestartRestorePreparation(
          target, mirror, owner, admission, preparedRestore, rootMoves, komi, resumePonder);
    }

    private Object owner() {
      return owner;
    }
    private ExactSnapshotEngineRestore.PreparedRestore preparedRestore() {
      return preparedRestore;
    }

    private boolean resumePonder() {
      return resumePonder;
    }

    private void executeRootReplay(Board board) {
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

    private TrackedLoadSgfConsumer(Leelaz targetEngine, Path sgfFile, LoadSgfDispatch dispatch) {
      this.targetEngine = targetEngine;
      this.sgfFile = sgfFile;
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
            targetEngine.buildLoadSgfResponseFailure(
                sgfFile, targetEngine.currentCommandResponseLine()));
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
    private final AtomicInteger pendingConsumers = new AtomicInteger(0);
    private final ArrayDeque<TrackedLoadSgfConsumer> pendingTrackedConsumers =
        new ArrayDeque<TrackedLoadSgfConsumer>();
    private final AtomicBoolean dispatchFinished = new AtomicBoolean(false);
    private final AtomicBoolean cleanupFinished = new AtomicBoolean(false);
    private final AtomicBoolean fallbackCleanupScheduled = new AtomicBoolean(false);
    private final AtomicLong dispatchFinishedAtNanos = new AtomicLong(-1L);
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final CountDownLatch completion = new CountDownLatch(1);

    private LoadSgfDispatch(Runnable afterConsumed) {
      this.afterConsumed = afterConsumed;
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
            new IllegalStateException(
                "Timed out while waiting for loadsgf response after "
                    + LOAD_SGF_NO_RESPONSE_TIMEOUT_MILLIS
                    + " ms"));
        completion.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        recordFailureAndCancelPendingConsumers(
            new IllegalStateException("Interrupted while waiting for loadsgf response", ex));
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

  private interface QueuedCommandSettlement {
    void onWriteClaimed();

    void onRequestFailed(RuntimeException failure);

    default void onResponseSettled() {}
  }

  private static final class QueuedCommand {
    private final String command;
    private final Runnable onResponse;
    private final CommandSendFailureHandler onSendFailure;
    private final boolean failOnSendError;
    private final QueuedCommandSettlement settlement;
    private final RestartBootstrapReceipt restartBootstrapReceipt;
    private boolean foregroundRestoreCommand;
    private RuntimeException cancellationFailure;
    private boolean outputWriteStarted;
    private boolean settlementFailurePublished;
    private RuntimeException stateResetAfterOutputWriteFailure;
    private boolean stateResetAfterOutputWritePublished;
    private boolean outstandingResponseRetired;

    private QueuedCommand(
        String command,
        Runnable onResponse,
        CommandSendFailureHandler onSendFailure,
        boolean failOnSendError) {
      this(command, onResponse, onSendFailure, failOnSendError, null, null);
    }

    private QueuedCommand(
        String command,
        Runnable onResponse,
        CommandSendFailureHandler onSendFailure,
        boolean failOnSendError,
        QueuedCommandSettlement settlement) {
      this(command, onResponse, onSendFailure, failOnSendError, settlement, null);
    }

    private QueuedCommand(
        String command,
        Runnable onResponse,
        CommandSendFailureHandler onSendFailure,
        boolean failOnSendError,
        QueuedCommandSettlement settlement,
        RestartBootstrapReceipt restartBootstrapReceipt) {
      this.command = command;
      this.onResponse = onResponse;
      this.onSendFailure = onSendFailure;
      this.failOnSendError = failOnSendError;
      this.settlement = settlement;
      this.restartBootstrapReceipt = restartBootstrapReceipt;
    }

    private boolean isTrackedLoadSgf() {
      return command != null && command.startsWith("loadsgf ") && onSendFailure != null;
    }

    private boolean requiresStateReset() {
      return isTrackedLoadSgf() || settlement != null;
    }

    private synchronized boolean cancelBeforeOutputWrite(RuntimeException failure) {
      if (outputWriteStarted) {
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

    private boolean beginOutputWrite() {
      synchronized (this) {
        if (cancellationFailure != null) {
          return false;
        }
        outputWriteStarted = true;
      }
      if (settlement != null) {
        settlement.onWriteClaimed();
      }
      return true;
    }

    private synchronized void markStateResetAfterOutputWrite(RuntimeException failure) {
      outstandingResponseRetired = true;
      if (stateResetAfterOutputWriteFailure == null) {
        stateResetAfterOutputWriteFailure = failure;
      }
    }

    private synchronized boolean isOutstandingResponseRetired() {
      return outstandingResponseRetired;
    }

    private void notifySendFailure(RuntimeException failure) {
      try {
        if (onSendFailure != null) {
          onSendFailure.onSendFailure(failure);
        }
      } finally {
        publishSettlementFailure(failure);
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
        if (onSendFailure != null) {
          onSendFailure.onStateResetAfterOutputWrite(failure);
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

    private void publishSettlementFailure(RuntimeException failure) {
      synchronized (this) {
        if (settlement == null || settlementFailurePublished) {
          return;
        }
        settlementFailurePublished = true;
      }
      settlement.onRequestFailed(failure);
    }
  }

  private static final class GtpCommandStateReset {
    private final RuntimeException failure;
    private final List<QueuedCommand> cancelledLoadSgfCommands;
    private final List<QueuedCommand> sentLoadSgfCommands;

    private GtpCommandStateReset(
        RuntimeException failure,
        List<QueuedCommand> cancelledLoadSgfCommands,
        List<QueuedCommand> sentLoadSgfCommands) {
      this.failure = failure;
      this.cancelledLoadSgfCommands = cancelledLoadSgfCommands;
      this.sentLoadSgfCommands = sentLoadSgfCommands;
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
  public boolean isResponseUpToDate() {
    // Use >= instead of == for avoiding hang-up, though it cannot happen
    return currentCmdNum >= cmdNumber - 1; // &&currentCmdNum >=ignoreCmdNumber;
  }

  private boolean shouldStopPonderAfterEnginePlayLine() {
    return Lizzie.frame != null
        && (Lizzie.frame.isPlayingAgainstLeelaz
            || Lizzie.frame.isAnaPlayingAgainstLeelaz
            || EngineManager.isEngineGame());
  }

  private boolean isResponseUpToPreDate() {
    // Use >= instead of == for avoiding hang-up, though it cannot happen
    return currentCmdNum >= cmdNumber - 2; // &&currentCmdNum >=ignoreCmdNumber;
  }

  private boolean isResponseUpToPreCommand() {
    // Use >= instead of == for avoiding hang-up, though it cannot happen
    return currentCmdNum >= cmdNumber - 3; // &&currentCmdNum >=ignoreCmdNumber;
  }

  public void setResponseUpToDate() {
    // Use >= instead of == for avoiding hang-up, though it cannot happen
    currentCmdNum = cmdNumber - 1;
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
    sendCommand(
        "play " + colorString + " " + move,
        settleTrackingPonder ? this::settleTrackingPonderAfterPlayResponse : null);
    bestMoves = new ArrayList<>();
    currentTotalPlayouts = 0;
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
      bestMoves = new ArrayList<>();
      currentTotalPlayouts = 0;
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
    if (Lizzie.config.enginePkPonder) {
      bestMoves = new ArrayList<>();
      currentTotalPlayouts = 0;
    }
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
    private boolean cancellationRequested;
    private Runnable cancellationSuccess;
    private Consumer<String> cancellationFailure;

    private ReadBoardGmaPreparation(String color, int maxTimeSeconds, int maxVisits) {
      this.color = color;
      this.maxTimeSeconds = maxTimeSeconds;
      this.maxVisits = maxVisits;
    }

    private void start() {
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

    private void restoreParamForMoveIfNeeded(
        ReadBoardGmaRuntimeParam param, Runnable completion) {
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
      sendReadBoardGmaCommand(color);
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

  private static final class ReadBoardGmaRestoreBarrier {
    private final Runnable onSuccess;
    private final Consumer<String> onFailure;
    private int remaining;
    private boolean completed;
    private Timer timeout;

    private ReadBoardGmaRestoreBarrier(Runnable onSuccess, Consumer<String> onFailure) {
      this.onSuccess = onSuccess;
      this.onFailure = onFailure;
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

  public synchronized boolean genmoveAnalyzeForReadBoard(
      String color, int maxTimeSeconds, int maxVisits, boolean ponder) {
    if (isThinking) return false;
    if (ponder && RemoteComputeConfig.isCustomWebSocketEngineCommand(engineCommand)) return false;
    if (!beginReadBoardGmaSession()) return false;
    if (RemoteComputeConfig.isCustomWebSocketEngineCommand(engineCommand)) {
      synchronized (readBoardGmaLock()) {
        if (readBoardGmaPreparation != null) {
          return false;
        }
        readBoardGmaPreparation =
            new ReadBoardGmaPreparation(color, maxTimeSeconds, maxVisits);
      }
      readBoardGmaPreparation.start();
      return true;
    }
    setReadBoardGmaPondering(ponder);
    prepareReadBoardGmaMaxTime(maxTimeSeconds);
    prepareReadBoardGmaMaxVisits(maxVisits);
    sendReadBoardGmaCommand(color);
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
    if (target == null
        || activation == null
        || isThinking
        || (ponder && RemoteComputeConfig.isCustomWebSocketEngineCommand(engineCommand))
        || !beginReadBoardGmaSession(target)) {
      return;
    }
    boolean activated = false;
    try {
      if (RemoteComputeConfig.isCustomWebSocketEngineCommand(engineCommand)) {
        synchronized (readBoardGmaLock()) {
          if (readBoardGmaPreparation != null) {
            return;
          }
          readBoardGmaPreparation = new ReadBoardGmaPreparation(color, maxTimeSeconds, maxVisits);
        }
        readBoardGmaPreparation.start();
      } else {
        setReadBoardGmaPondering(ponder);
        prepareReadBoardGmaMaxTime(maxTimeSeconds);
        prepareReadBoardGmaMaxVisits(maxVisits);
        sendReadBoardGmaCommand(color);
      }
      activated = activation.completeRetainedEngineMode();
    } finally {
      if (!activated) {
        retireReadBoardGmaSession();
      }
    }
  }

  private void sendReadBoardGmaCommand(String color) {
    StringBuilder command =
        new StringBuilder("kata-genmove_analyze ")
            .append(color)
            .append(" ")
            .append(getInterval())
            .append(addKataTag());
    sendCommandNoLeelaz2(command.toString());
    isThinking = true;
    LizzieFrame.menu.toggleEngineMenuStatus(false, true);
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
    boolean noParamsToRestore;
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
      barrier = new ReadBoardGmaRestoreBarrier(onSuccess, onFailure);
      readBoardGmaRestoreBarrier = barrier;
      registerReadBoardGmaRuntimeParamRestore(
          barrier, readBoardGmaPondering, paramsToRestore);
      registerReadBoardGmaRuntimeParamRestore(barrier, readBoardGmaMaxTime, paramsToRestore);
      registerReadBoardGmaRuntimeParamRestore(barrier, readBoardGmaMaxVisits, paramsToRestore);
      noParamsToRestore = barrier.isEmpty();
    }
    if (noParamsToRestore) {
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
      restoreReadBoardGmaRuntimeParamIfNeeded(param);
    }
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
        if (isWebTrialEngineBusy() || engineStateUnrestored || readBoardGmaRestoreBarrier != null) {
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

  private void prepareReadBoardGmaMaxTime(int maxTimeSeconds) {
    if (maxTimeSeconds > 0) {
      prepareReadBoardGmaRuntimeParam(readBoardGmaMaxTime, String.valueOf(maxTimeSeconds));
      return;
    }
    restoreReadBoardGmaRuntimeParamIfNeeded(readBoardGmaMaxTime);
  }

  private void prepareReadBoardGmaMaxVisits(int maxVisits) {
    if (maxVisits > 0) {
      prepareReadBoardGmaRuntimeParam(readBoardGmaMaxVisits, String.valueOf(maxVisits));
      return;
    }
    restoreReadBoardGmaRuntimeParamIfNeeded(readBoardGmaMaxVisits);
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
            failReadBoardGmaRuntimeRestore(barrier, "restore response timeout");
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
      ReadBoardGmaRestoreBarrier barrier, String detail) {
    EngineModeReservation reservation;
    Consumer<String> failure;
    Timer timeout;
    synchronized (readBoardGmaLock()) {
      if (readBoardGmaRestoreBarrier != barrier || barrier.completed) {
        return;
      }
      barrier.completed = true;
      readBoardGmaRestoreBarrier = null;
      engineStateUnrestored = true;
      reservation = readBoardGmaReservation;
      readBoardGmaReservation = null;
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
      failure.accept(detail);
    }
  }

  public void failReadBoardGmaEngineRestore(String detail) {
    ReadBoardGmaRestoreBarrier barrier;
    EngineModeReservation reservation;
    synchronized (readBoardGmaLock()) {
      barrier = readBoardGmaRestoreBarrier;
      if (barrier != null) {
        reservation = null;
      } else {
        reservation = readBoardGmaReservation;
        if (reservation == null) {
          return;
        }
        engineStateUnrestored = true;
        readBoardGmaReservation = null;
      }
    }
    if (barrier != null) {
      failReadBoardGmaRuntimeRestore(barrier, detail);
      return;
    }
    rememberRecentLine(recentStderrLines, "ReadBoard GMA engine restore failed: " + detail);
    resetGtpCommandStateAfterRestoreFailure(detail);
    invalidateReadBoardTrackingEligibility(
        ReadBoardTrackingEligibilityAdapter.Reason.ENGINE_UNRESTORED);
    reservation.close();
  }

  private void completeReadBoardGmaRuntimeRestore(ReadBoardGmaRestoreBarrier barrier) {
    EngineModeReservation reservation;
    Runnable completion;
    Timer timeout;
    synchronized (readBoardGmaLock()) {
      if (readBoardGmaRestoreBarrier != barrier
          || barrier.completed
          || barrier.remaining != 0) {
        return;
      }
      barrier.completed = true;
      readBoardGmaRestoreBarrier = null;
      clearReadBoardGmaSearchLimitSnapshots();
      reservation = readBoardGmaReservation;
      readBoardGmaReservation = null;
      completion = barrier.onSuccess;
      timeout = barrier.timeout;
      barrier.timeout = null;
    }
    if (timeout != null) {
      timeout.cancel();
    }
    if (reservation != null) {
      reservation.close();
    }
    if (completion != null) {
      completion.run();
    }
  }

  void confirmBoardSynchronization(Runnable onSuccess, Consumer<String> onFailure) {
    new BoardSynchronizationConfirmation(onSuccess, onFailure).start();
  }

  private interface BoardSynchronizationResponseHandler extends Runnable {}

  private final class BoardSynchronizationConfirmation {
    private final Runnable onSuccess;
    private final Consumer<String> onFailure;
    private final AtomicBoolean settled = new AtomicBoolean(false);
    private final RestartBootstrapReceipt restartReceipt =
        restartBootstrapReceiptContext.get();
    private final Runnable responseHandler =
        (BoardSynchronizationResponseHandler) this::onResponse;
    private Timer timeout;

    private BoardSynchronizationConfirmation(
        Runnable onSuccess, Consumer<String> onFailure) {
      this.onSuccess = onSuccess;
      this.onFailure = onFailure;
    }

    private void start() {
      try {
        sendCommand("name", responseHandler, this::onSendFailure, true, false);
      } catch (RuntimeException ex) {
        settleFailure(ex.getMessage());
        return;
      }
      if (settled.get()) {
        return;
      }
      timeout = new Timer("lizzie-board-sync-confirmation-timeout", true);
      timeout.schedule(
          new TimerTask() {
            @Override
            public void run() {
              if (!settled.compareAndSet(false, true)) {
                return;
              }
              try {
                runFailure("board synchronization response timeout");
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
        runFailure("board synchronization failed: " + currentCommandResponseLine());
      } else if (onSuccess != null) {
        runWithRestartBootstrapReceipt(restartReceipt, onSuccess);
      }
    }

    private void onSendFailure(RuntimeException failure) {
      settleFailure(
          failure == null ? "board synchronization send failed" : failure.getMessage());
    }

    private void settleFailure(String detail) {
      if (!settled.compareAndSet(false, true)) {
        return;
      }
      cancelTimeout();
      runFailure(detail);
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
      try {
        sendCommand(
            "kata-set-param " + param.name + " " + originalValue,
            responseHandler,
            this::onSendFailure,
            true,
            false);
      } catch (RuntimeException ex) {
        settleFailure(ex.getMessage());
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
                failRestore("restore response timeout: " + param.name);
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
        failRestore("restore command failed: " + currentCommandResponseLine());
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
      settleFailure(failure == null ? "restore send failed: " + param.name : failure.getMessage());
    }

    private void settleFailure(String detail) {
      if (!settled.compareAndSet(false, true)) {
        return;
      }
      cancelTimeout();
      failRestore(detail);
    }

    private void failRestore(String detail) {
      if (barrier != null) {
        failReadBoardGmaRuntimeRestore(barrier, detail);
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

  public synchronized void genmoveForPk(String color) {
    if (rejectNewExclusiveWorkDuringGtpLease()) return;
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
      return;
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
    sendCommand(command);
    // isThinking = true;

    // isPondering = false;
    // genmovenoponder =false;
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
      sendCommand("clear_board");
      if (isKatago) {
        scoreMean = 0;
        scoreStdev = 0;
      }
      bestMoves = new ArrayList<>();
      currentTotalPlayouts = 0;
      if (isPondering) ponder();
      currentCmdNum = Math.max(cmdNumber - 2, currentCmdNum);
    }
  }

  public void clearWithoutPonder() {
    this.notPondering();
    nameCmdfornoponder();
    sendCommand("clear_board");
    bestMoves = new ArrayList<>();
    currentTotalPlayouts = 0;
    currentCmdNum = Math.max(cmdNumber - 2, currentCmdNum);
  }

  public void undo() {
    undo(false, false);
  }

  public void undo(boolean addPlayer, boolean blackToPlay) {
    synchronized (this) {
      boolean continuePonderAfterUndo = isPonderingOrWasPonderingBeforeTracking();
      sendCommand("undo");
      bestMoves = new ArrayList<>();
      currentTotalPlayouts = 0;
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
    bestMoves = new ArrayList<>();
    currentTotalPlayouts = 0;
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
    bestMoves = new ArrayList<>();
    currentTotalPlayouts = 0;
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
    if (noAnalyze) return;
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
    AnalysisResourceCoordinator.processStopped(
        this, AnalysisResourceCoordinator.Purpose.MAIN_BOARD, process);
    cancelPositionEstimateRequest();
    leela0110StopPonder();
    if (this.useJavaSSH) {
      javaSSH.close();
    } else if (this.useRemoteCompute) {
      if (remoteTransport != null) remoteTransport.close();
    } else {
      if (process != null) process.destroy();
    }
  }

  public List<MoveData> getBestMoves() {
    //	synchronized (this) {
    return bestMoves;
    //	}
  }

  public void clearBestMoves() {
    bestMoves = new ArrayList<>();
    currentTotalPlayouts = 0;
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
    if (!bestMoves.isEmpty()) {
      // we should match the Leelaz UCTNode get_eval, which is a weighted average
      // copy the list to avoid concurrent modification exception... TODO there must
      // be a better way
      // (note the concurrent modification exception is very very rare)
      // We should use Lizzie Board's best moves as they will generally be the most
      // accurate
      // final List<MoveData> moves = new ArrayList<MoveData>(Lizzie.board.getData().bestMoves);

      // get the total number of playouts in moves
      stats.totalPlayouts = currentTotalPlayouts;

      // stats.maxWinrate = bestMoves.get(0).winrate;
      stats.maxWinrate = BoardData.getWinrateFromBestMoves(bestMoves);
      stats.scoreLead = BoardData.getScoreLeadFromBestMoves(bestMoves);
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
    closeBundledStartupDialog();
    boolean primaryEngine = this == Lizzie.leelaz;
    if (primaryEngine) {
      if (hasMissingLocalStartupAsset(commands, useRemoteCompute, useJavaSSH)) {
        Lizzie.engineStartupStatus.needsRepair(
            "EngineStartup.needsRepair", "AI is not ready - click to repair", message);
      } else {
        Lizzie.engineStartupStatus.failed(
            "EngineStartup.failed", "AI failed to start - click to repair", message);
      }
    }
    if (!shouldOpenInteractiveDiagnostic(primaryEngine, Lizzie.isFirstLaunchSession())) {
      return;
    }
    if (!Lizzie.config.autoCheckEngineAlive && EngineManager.isEngineGame())
      Lizzie.engineManager.clearEngineGame();
    if (engineFailedMessage != null && engineFailedMessage.isVisible()) return;
    engineFailedMessage =
        new EngineFailedMessage(
            commands, engineCommand, message, !useJavaSSH && OS.isWindows(), true, false);
    engineFailedMessage.setModal(isModal);
    engineFailedMessage.setVisible(true);
  }

  private boolean shouldOpenInteractiveDiagnostic() {
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
    Lizzie.engineStartupStatus.checking(statusKey, progressFallback);
  }

  private void closeBundledStartupDialog() {
    if (isLoaded && this == Lizzie.leelaz) {
      Lizzie.markEngineReady();
    }
  }

  private void startBundledStartupWatchdog(long token, Path engineExecutable) {
    if (token <= 0L || preload || useJavaSSH || !Config.isBundledKataGoCommand(engineCommand)) {
      return;
    }
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
                if (token != bundledStartupToken || isLoaded || isDownWithError || isNormalEnd) {
                  return;
                }
                if (process != null && !process.isAlive()) {
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
              if (token != bundledStartupToken || isLoaded || isDownWithError || isNormalEnd) {
                return;
              }
              isDownWithError = true;
              if (process != null) {
                try {
                  process.destroyForcibly();
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
              SwingUtilities.invokeLater(
                  () -> {
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
            },
            "bundled-engine-startup-watchdog");
    watchdog.setDaemon(true);
    watchdog.start();
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
    getRcentLine = true;
    if (sendCommand) {
      recentLineNumber = 0;
      sendCommand("kata-get-param playoutDoublingAdvantage");
      sendCommand("kata-get-param analysisWideRootNoise");
      sendCommand("kata-get-rules");
    }
    Runnable runnable =
        new Runnable() {
          public void run() {
            try {
              Thread.sleep(30000);
            } catch (InterruptedException e) {
              // TODO Auto-generated catch block
              e.printStackTrace();
            }
            Lizzie.leelaz.getRcentLine = false;
          }
        };
    Thread thread = new Thread(runnable);
    thread.start();
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

  private void leela0110Ponder(boolean first) {
    if (first)
      if (Lizzie.config.isDoubleEngineMode()) {
        if (Lizzie.leelaz2 != null && this != Lizzie.leelaz2) {
          Lizzie.leelaz2.sendCommand("lz-analyze " + getInterval());
        }
      }
    synchronized (this) {
      if (leela0110PonderingBoardData != null) return;
      leela0110PonderingBoardData = Lizzie.board.getData();
      leela0110BestMoves = new ArrayList<>();
      sendCommandNoLeelaz2("time_left b 0 0");
      leela0110PonderingTimer = new Timer();
      leela0110PonderingTimer.schedule(
          new TimerTask() {
            public void run() {
              sendCommandNoLeelaz2("name");
            }
          },
          LEELA0110_PONDERING_INTERVAL_MILLIS);
    }
  }

  public void leela0110StopPonder() {
    if (leela0110PonderingTimer != null) {
      leela0110PonderingTimer.cancel();
      leela0110PonderingTimer = null;
    }
    leela0110PonderingBoardData = null;
  }

  private void leela0110UpdatePonder() {
    leela0110StopPonder();
    if (isPondering) leela0110Ponder(false);
  }

  private boolean isLeela0110PonderingValid() {
    return leela0110PonderingBoardData == Lizzie.board.getData();
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
    cmdNumber -= modifyNumber;
    modifyNumber = 0;
  }

  public void timeLeft(String color, int seconds, int moves, boolean isDuringMove) {
    seconds = Math.max(0, seconds);
    sendCommand("time_left " + color + " " + seconds + " " + moves);
    if (isDuringMove) currentCmdNum++;
  }

  public void timeLeft(String color, float seconds, int moves, boolean isDuringMove) {
    seconds = Math.max(0, seconds);
    sendCommand(
        "time_left " + color + " " + String.format(Locale.ENGLISH, "%.2f", seconds) + " " + moves);
    if (isDuringMove) currentCmdNum++;
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
