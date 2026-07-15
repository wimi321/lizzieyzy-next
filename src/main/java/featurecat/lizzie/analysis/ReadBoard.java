package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.FloatBoard;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.SMessage;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.ExtraStones;
import featurecat.lizzie.rules.Movelist;
import featurecat.lizzie.rules.SnapshotEngineRestore;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import featurecat.lizzie.util.Utils;
import featurecat.lizzie.util.YikeSyncDebugLog;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

public class ReadBoard {
  private static final long PROCESS_EXIT_WAIT_TIMEOUT_MS = 1000L;
  private static final long PROCESS_DESTROY_WAIT_TIMEOUT_MS = 200L;
  private static final String LEGACY_NATIVE_READBOARD_EXE = "readboard.exe";
  private static final String LEGACY_NATIVE_READBOARD_BAT = "readboard.bat";
  private static final int SYNC_ANALYSIS_RESUME_DELAY_MS = 200;
  private static final int STARTUP_OUTPUT_LOG_LIMIT = 8;
  private static final String READBOARD_PLACEMENT_FAILED = "error place failed";
  private static final int LOCAL_MOVE_PLACE_RETRY_LIMIT = 0;
  private static final long LOCAL_MOVE_PLACE_RETRY_INTERVAL_MS = 350L;
  private static final long PENDING_LOCAL_MOVE_ACK_TIMEOUT_MS = 3000L;
  private static final long FAILED_LOCAL_MOVE_OBSERVATION_GRACE_MS = 1000L;
  private static final boolean LOCAL_MOVE_SYNC_LOG_ENABLED =
      Boolean.parseBoolean(
          System.getProperty(
              "lizzie.localMoveSyncLog.enabled",
              System.getProperty("surefire.test.class.path") == null ? "true" : "false"));
  private static final String LOCAL_MOVE_SYNC_LOG_PATH =
      System.getProperty("lizzie.localMoveSyncLog", "runtime/readboard-local-move-debug.log");
  private static final long LOCAL_MOVE_SYNC_LOG_MAX_BYTES = 2_000_000L;
  private static final String READBOARD_UPDATE_SUPPORTED_COMMAND = "readboardUpdateSupported";
  private static final String READBOARD_UPDATE_PACKAGE_V2_SUPPORTED_COMMAND =
      "readboardUpdatePackageV2Supported";
  private static final String YIKE_SYNC_START_COMMAND = "yikeSyncStart";
  private static final String YIKE_SYNC_STOP_COMMAND = "yikeSyncStop";
  private static final String READBOARD_GMA_TOKEN = "gma";
  private static final String READBOARD_GMA_UNSUPPORTED_MESSAGE = "该模式仅支持 KataGo";

  public static boolean isLegacyNativeReadBoardAvailable() {
    return isLegacyNativeReadBoardAvailable(legacyNativeReadBoardDirectory());
  }

  public static boolean isNativeReadBoardExeAvailable() {
    return new File(legacyNativeReadBoardDirectory(), LEGACY_NATIVE_READBOARD_EXE).canRead();
  }

  public static boolean isNativeReadBoardBatAvailable() {
    return new File(legacyNativeReadBoardDirectory(), LEGACY_NATIVE_READBOARD_BAT).canRead();
  }

  public static File nativeReadBoardDirectoryForDiagnostics() {
    return legacyNativeReadBoardDirectory();
  }

  static boolean isYikeSyncStartCommand(String line) {
    return isExactReadBoardCommand(line, YIKE_SYNC_START_COMMAND);
  }

  public static void localMoveSyncDebug(String message) {
    if (!LOCAL_MOVE_SYNC_LOG_ENABLED) {
      return;
    }
    synchronized (ReadBoard.class) {
      try {
        File file = new File(LOCAL_MOVE_SYNC_LOG_PATH);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
          parent.mkdirs();
        }
        rotateLocalMoveSyncLogIfNeeded(file);
        try (FileWriter writer = new FileWriter(file, true)) {
          writer.write(
              "["
                  + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date())
                  + "] "
                  + Thread.currentThread().getName()
                  + " "
                  + message
                  + System.lineSeparator());
        }
      } catch (IOException ignored) {
      }
    }
  }

  private static void rotateLocalMoveSyncLogIfNeeded(File file) {
    if (!file.exists() || file.length() <= LOCAL_MOVE_SYNC_LOG_MAX_BYTES) {
      return;
    }
    File rotated = new File(file.getParentFile(), file.getName() + ".1");
    if (rotated.exists()) {
      rotated.delete();
    }
    file.renameTo(rotated);
  }

  static boolean isYikeSyncStopCommand(String line) {
    return isExactReadBoardCommand(line, YIKE_SYNC_STOP_COMMAND);
  }

  static boolean isYikeSyncPlatformLine(String line) {
    return line != null && "syncPlatform yike".equalsIgnoreCase(line.trim());
  }

  public Process process;
  private InputStreamReader inputStream;
  private BufferedOutputStream outputStream;
  private ScheduledExecutorService executor;
  private ScheduledExecutorService pendingLocalMoveTimeoutExecutor;
  ArrayList<Integer> tempcount = new ArrayList<Integer>();
  // private long startSyncTime = 0;

  public boolean isLoaded = false;
  private int version = 220430;
  public String currentEnginename = "";
  private int port = -1;

  public boolean firstSync = true;
  // public boolean syncBoth = Lizzie.config.syncBoth;
  private ReadBoardStream readBoardStream;
  private Socket socket;
  private ServerSocket s;
  private boolean noMsg = false;
  private boolean usePipe = true;
  private boolean needGenmove = false;
  private boolean showInBoard = false;
  private volatile boolean isSyncing = false;
  private volatile boolean readBoardGmaAutoPlayActive = false;
  private Stone readBoardGmaAutoPlayColor = Stone.EMPTY;
  private int readBoardGmaTimeSeconds = 0;
  private int readBoardGmaMaxVisits = 0;
  private volatile boolean readBoardGmaPending = false;
  private boolean readBoardGmaPendingLogicallyInvalid = false;
  private boolean readBoardGmaAwaitingSyncedBoard = false;
  private volatile boolean readBoardGmaEngineRestorePending = false;
  private volatile boolean readBoardGmaEngineRestoreInProgress = false;
  private volatile BoardHistoryNode readBoardGmaDeferredRestoreNode = null;
  private boolean readBoardTurnTrusted = false;
  // private long startTime;
  private boolean waitSocket = true;
  public boolean lastMovePlayByLizzie = false;
  private SyncRemoteContext pendingRemoteContext = SyncRemoteContext.generic(false);
  private boolean waitingForReadBoardLocalMoveAck = false;
  private boolean pendingLocalMoveClickCompleted = false;
  private int pendingLocalMoveRetryCount = 0;
  private long lastPendingLocalMoveRetryTimeMs = 0L;
  private BoardHistoryNode pendingLocalMoveNode = null;
  private int pendingLocalMoveX = -1;
  private int pendingLocalMoveY = -1;
  private Stone pendingLocalMoveColor = Stone.EMPTY;
  private String pendingLocalMoveBaselineKey = "";
  private long pendingLocalMoveAckGeneration = 0L;
  // Readboard placeComplete has no request id, so a late success from the previous command must
  // never be allowed to confirm the next pending move. Explicit failures still release the current
  // pending move; otherwise a missed click can wait forever with no follow-up snapshot.
  private boolean ignoreReadBoardPlaceResultsForCurrentPending = false;
  private boolean ignoreReadBoardPlaceResultsForNextPending = false;
  private boolean failedLocalMoveSuppressionActive = false;
  private int failedLocalMoveSuppressionX = -1;
  private int failedLocalMoveSuppressionY = -1;
  private Stone failedLocalMoveSuppressionColor = Stone.EMPTY;
  private String failedLocalMoveSuppressionSnapshotKey = "";
  private boolean failedLocalMoveRecoveryActive = false;
  private int failedLocalMoveRecoveryX = -1;
  private int failedLocalMoveRecoveryY = -1;
  private Stone failedLocalMoveRecoveryColor = Stone.EMPTY;
  private boolean failedLocalMoveAwaitingRemoteObservation = false;
  private boolean failedLocalMoveWaitingForOurTurnAfterRemoteChange = false;
  private long failedLocalMoveObservationDeadlineMs = 0L;
  private boolean confirmedLocalMoveActive = false;
  private BoardHistoryNode confirmedLocalMoveNode = null;
  private int confirmedLocalMoveX = -1;
  private int confirmedLocalMoveY = -1;
  private Stone confirmedLocalMoveColor = Stone.EMPTY;
  private String confirmedLocalMoveBaselineKey = "";
  private boolean hideFloadBoardBeforePlace = false;
  private boolean hideFromPlace = false;
  public boolean editMode = false;
  private int startupOutputLineCount = 0;
  private volatile boolean shutdownStarted = false;
  private final SyncConflictTracker conflictTracker = new SyncConflictTracker();
  private final SyncHistoryJumpTracker historyJumpTracker = new SyncHistoryJumpTracker();
  private final SyncLocalNavigationTracker localNavigationTracker =
      new SyncLocalNavigationTracker();
  private SyncResumeState resumeState;
  private BoardHistoryNode lastResolvedSnapshotNode;
  private boolean awaitingFirstSyncFrame = true;
  private int historyOverwriteSuppressionDepth = 0;
  private volatile long syncAnalysisEpoch = 0L;
  private String lastProtocolLineSummary;
  private long lastProtocolTimestampMillis;

  private enum CompleteSnapshotRecoveryOutcome {
    NO_CHANGE,
    SINGLE_MOVE_RECOVERY,
    HOLD,
    FORCE_REBUILD
  }

  private static final class CompleteSnapshotRecoveryDecision {
    private final CompleteSnapshotRecoveryOutcome outcome;
    private final BoardHistoryNode resolvedNode;
    private final boolean shouldResumeAnalysis;
    private final String reasonCode;

    private CompleteSnapshotRecoveryDecision(
        CompleteSnapshotRecoveryOutcome outcome,
        BoardHistoryNode resolvedNode,
        boolean shouldResumeAnalysis,
        String reasonCode) {
      this.outcome = outcome;
      this.resolvedNode = resolvedNode;
      this.shouldResumeAnalysis = shouldResumeAnalysis;
      this.reasonCode = reasonCode;
    }

    private static CompleteSnapshotRecoveryDecision noChange(
        BoardHistoryNode resolvedNode, boolean shouldResumeAnalysis, String reasonCode) {
      return new CompleteSnapshotRecoveryDecision(
          CompleteSnapshotRecoveryOutcome.NO_CHANGE,
          resolvedNode,
          shouldResumeAnalysis,
          reasonCode);
    }

    private static CompleteSnapshotRecoveryDecision singleMoveRecovery() {
      return new CompleteSnapshotRecoveryDecision(
          CompleteSnapshotRecoveryOutcome.SINGLE_MOVE_RECOVERY,
          null,
          false,
          "single_move_recovery");
    }

    private static CompleteSnapshotRecoveryDecision hold() {
      return new CompleteSnapshotRecoveryDecision(
          CompleteSnapshotRecoveryOutcome.HOLD, null, false, "conflict_hold");
    }

    private static CompleteSnapshotRecoveryDecision forceRebuild(String reasonCode) {
      return new CompleteSnapshotRecoveryDecision(
          CompleteSnapshotRecoveryOutcome.FORCE_REBUILD, null, false, reasonCode);
    }
  }

  private SyncSnapshotRebuildPolicy rebuildPolicy() {
    return new SyncSnapshotRebuildPolicy(Board.boardWidth);
  }

  private SyncSnapshotDiffChecker snapshotDiffChecker() {
    return new SyncSnapshotDiffChecker(Board.boardWidth);
  }

  public ReadBoard(boolean usePipe, boolean retiredSyncMode) throws Exception {
    if (retiredSyncMode) {
      throw new UnsupportedOperationException(
          "Retired board synchronization mode is no longer supported.");
    }
    this.usePipe = usePipe;
    if (s != null && !s.isClosed()) {
      s.close();
    }
    startEngine();
  }

  static File legacyNativeReadBoardDirectory() {
    return resolveNativeReadBoardDirectory(defaultNativeReadBoardDirectoryCandidates());
  }

  static File resolveNativeReadBoardDirectory(List<File> candidates) {
    for (File candidate : candidates) {
      if (new File(candidate, LEGACY_NATIVE_READBOARD_EXE).canRead()) {
        return candidate.getAbsoluteFile();
      }
    }
    return new File("readboard").getAbsoluteFile();
  }

  static List<File> defaultNativeReadBoardDirectoryCandidates() {
    Set<File> candidates = new LinkedHashSet<File>();
    candidates.addAll(
        nativeReadBoardDirectoryCandidatesForBase(new File(System.getProperty("user.dir", "."))));
    codeSourceDirectory()
        .ifPresent(
            directory -> candidates.addAll(nativeReadBoardDirectoryCandidatesForBase(directory)));
    bundledRuntimeRoot()
        .ifPresent(
            directory -> candidates.addAll(nativeReadBoardDirectoryCandidatesForBase(directory)));
    return new ArrayList<File>(candidates);
  }

  static List<File> nativeReadBoardDirectoryCandidatesForBase(File baseDir) {
    Set<File> candidates = new LinkedHashSet<File>();
    File absoluteBase = baseDir.getAbsoluteFile();
    candidates.add(new File(absoluteBase, "readboard"));
    candidates.add(new File(new File(absoluteBase, "app"), "readboard"));
    if ("app".equalsIgnoreCase(absoluteBase.getName())) {
      candidates.add(new File(absoluteBase, "readboard"));
    }
    return new ArrayList<File>(candidates);
  }

  private static Optional<File> codeSourceDirectory() {
    try {
      if (ReadBoard.class.getProtectionDomain().getCodeSource() == null) {
        return Optional.empty();
      }
      File codeSource =
          new File(ReadBoard.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      File directory = codeSource.isFile() ? codeSource.getParentFile() : codeSource;
      return Optional.ofNullable(directory);
    } catch (URISyntaxException | SecurityException e) {
      return Optional.empty();
    }
  }

  private static Optional<File> bundledRuntimeRoot() {
    String javaHome = System.getProperty("java.home");
    if (javaHome == null || javaHome.isEmpty()) {
      return Optional.empty();
    }
    File runtimeDir = new File(javaHome).getAbsoluteFile();
    File root = runtimeDir.getParentFile();
    return Optional.ofNullable(root);
  }

  static File resolveLegacyNativeReadBoardCommand(File readBoardDir, boolean usePipe) {
    File absoluteDir = readBoardDir.getAbsoluteFile();
    if (usePipe) {
      return new File(absoluteDir, LEGACY_NATIVE_READBOARD_EXE);
    }

    return new File(absoluteDir, LEGACY_NATIVE_READBOARD_BAT);
  }

  static boolean isLegacyNativeReadBoardAvailable(File readBoardDir) {
    File absoluteDir = readBoardDir.getAbsoluteFile();
    return new File(absoluteDir, LEGACY_NATIVE_READBOARD_EXE).canRead()
        || new File(absoluteDir, LEGACY_NATIVE_READBOARD_BAT).canRead();
  }

  static ProcessBuilder buildLegacyNativeReadBoardProcessBuilder(
      boolean usePipe, List<String> arguments) {
    return buildLegacyNativeReadBoardProcessBuilder(
        legacyNativeReadBoardDirectory(), usePipe, arguments);
  }

  static ProcessBuilder buildLegacyNativeReadBoardProcessBuilder(
      File readBoardDir, boolean usePipe, List<String> arguments) {
    File absoluteDir = readBoardDir.getAbsoluteFile();
    List<String> commands = new ArrayList<String>();
    commands.add(resolveLegacyNativeReadBoardCommand(absoluteDir, usePipe).getAbsolutePath());
    commands.addAll(arguments);
    ProcessBuilder processBuilder = new ProcessBuilder(commands);
    processBuilder.directory(absoluteDir);
    processBuilder.redirectErrorStream(true);
    return processBuilder;
  }

  private void createSocketServer() {
    try {
      s = new ServerSocket(0);
      port = s.getLocalPort();
      waitSocket = false;
      while (true) {
        socket = s.accept();
        readBoardStream = new ReadBoardStream(this, socket);
        break;
      }
    } catch (Exception e) {
      if (!noMsg)
        Utils.showMsg(
            Lizzie.resourceBundle.getString("ReadBoard.port")
                + " "
                + port
                + " "
                + Lizzie.resourceBundle.getString("ReadBoard.portUsed")
                + e.getMessage());
      try {
        s.close();
      } catch (Exception e1) {
        e1.printStackTrace();
      }
      e.printStackTrace();
    }
  }

  public void startEngine() throws Exception {
    if (!usePipe) {
      waitSocket = true;
      noMsg = false;
      Runnable runnable2 =
          new Runnable() {
            public void run() {
              if (s == null || s.isClosed()) createSocketServer();
            }
          };
      Thread thread2 = new Thread(runnable2);
      thread2.start();
      int times = 300;
      while (waitSocket && times > 0) {
        Thread.sleep(10);
        times--;
      }
    }
    List<String> commands = new ArrayList<String>();
    commands.add("yzy");
    commands.add(
        !LizzieFrame.toolbar.chkAutoPlayTime.isSelected()
                || LizzieFrame.toolbar.txtAutoPlayTime.getText().equals("")
            ? " "
            : LizzieFrame.toolbar.txtAutoPlayTime.getText());
    commands.add(
        !LizzieFrame.toolbar.chkAutoPlayPlayouts.isSelected()
                || LizzieFrame.toolbar.txtAutoPlayPlayouts.getText().equals("")
            ? " "
            : LizzieFrame.toolbar.txtAutoPlayPlayouts.getText());
    commands.add(
        !LizzieFrame.toolbar.chkAutoPlayFirstPlayouts.isSelected()
                || LizzieFrame.toolbar.txtAutoPlayFirstPlayouts.getText().equals("")
            ? " "
            : LizzieFrame.toolbar.txtAutoPlayFirstPlayouts.getText());

    if (usePipe) commands.add("0");
    else commands.add("1");
    commands.add(Lizzie.resourceBundle.getString("ReadBoard.language"));
    if (usePipe) commands.add("-1");
    else commands.add(String.valueOf(port));
    File nativeReadBoardDirectory = legacyNativeReadBoardDirectory();
    ProcessBuilder processBuilder =
        buildLegacyNativeReadBoardProcessBuilder(nativeReadBoardDirectory, usePipe, commands);
    logNativeReadBoardLaunch(processBuilder);
    try {
      process = processBuilder.start();
    } catch (IOException e) {
      logNativeReadBoardStartFailure(processBuilder, e);
      if (!usePipe) {
        Utils.showMsg(e.getLocalizedMessage());
        SMessage msg = new SMessage();
        msg.setMessage(Lizzie.resourceBundle.getString("ReadBoard.loadFailed"), 2);
        s.close();
        return;
      } else {
        throw new Exception("Start native board synchronization failed", e);
      }
    }
    if (usePipe) {
      initializeStreams();
      executor = Executors.newSingleThreadScheduledExecutor();
      executor.execute(this::read);
    }
  }

  private void initializeStreams() throws UnsupportedEncodingException {
    inputStream = new InputStreamReader(process.getInputStream(), "UTF-8");
    outputStream = new BufferedOutputStream(process.getOutputStream());
  }

  private void logNativeReadBoardLaunch(ProcessBuilder processBuilder) {
    List<String> command = processBuilder.command();
    File workingDirectory = processBuilder.directory();
    System.out.println("Starting native board synchronization tool.");
    System.out.println(
        "  executable: " + (command.isEmpty() ? "" : new File(command.get(0)).getAbsolutePath()));
    System.out.println(
        "  working directory: "
            + (workingDirectory == null ? "" : workingDirectory.getAbsolutePath()));
    System.out.println("  command: " + command);
  }

  private void logNativeReadBoardStartFailure(
      ProcessBuilder processBuilder, IOException exception) {
    System.err.println("Failed to start native board synchronization tool.");
    logNativeReadBoardLaunch(processBuilder);
    System.err.println("  start exception: " + exception.getClass().getName());
    System.err.println("  message: " + exception.getLocalizedMessage());
  }

  private void logReadBoardOutputLine(String rawLine) {
    if (startupOutputLineCount >= STARTUP_OUTPUT_LOG_LIMIT) {
      return;
    }
    startupOutputLineCount++;
    String line = rawLine.replace("\r", "\\r").replace("\n", "\\n");
    System.out.println("Native board synchronization output: " + line);
  }

  private void logReadBoardExit() {
    Process currentProcess = process;
    if (currentProcess == null) {
      System.out.println("Native board synchronization process handle is already cleared.");
      return;
    }
    try {
      if (currentProcess.waitFor(PROCESS_DESTROY_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        System.out.println(
            "Native board synchronization process exit code: " + currentProcess.exitValue());
      } else {
        System.out.println("Native board synchronization stdout closed before process exit.");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      System.out.println("Interrupted while checking board synchronization exit code.");
    }
  }

  private void read() {
    try {
      int c;
      StringBuilder line = new StringBuilder();
      // while ((c = inputStream.read()) != -1) {
      while ((c = inputStream.read()) != -1) {
        line.append((char) c);

        if ((c == '\n')) {
          try {
            String rawLine = line.toString();
            logReadBoardOutputLine(rawLine);
            parseLine(rawLine);
            if ("ready".equals(rawLine.trim())) {
              handleReady();
            }
          } catch (Exception ex) {
            ex.printStackTrace();
          }
          line = new StringBuilder();
        }
      }
      // this line will be reached when BoardSync shuts down
      if (Lizzie.leelaz.isPondering()) Lizzie.leelaz.ponder();
      showInBoard = false;
      if (Lizzie.frame.floatBoard != null) {
        Lizzie.frame.floatBoard.setVisible(false);
      }
      System.out.println("Board synchronization tool process ended.");
      logReadBoardExit();
      shutdownAfterProcessEnd();
      // Do no exit for switching weights
      // System.exit(-1);
    } catch (IOException e) {
      e.printStackTrace();
      if (Lizzie.frame != null) {
        Lizzie.frame.bothSync = false;
        Lizzie.frame.syncBoard = false;
      }
      shutdownAfterProcessEnd();
      // System.exit(-1);
    }
  }

  public void parseLine(String line) {
    ensureTransientSyncStateInitialized();
    recordProtocolLine(line);
    ReadBoardUpdateRequest updateRequest = ReadBoardUpdateRequest.tryParse(line);
    if (updateRequest != null) {
      handleHostedUpdateRequest(updateRequest);
      return;
    }
    if (isYikeSyncStartCommand(line)) {
      YikeSyncDebugLog.log("ReadBoard received yikeSyncStart");
      handleYikeSyncStartCommand(true);
      return;
    }
    if (isYikeSyncStopCommand(line)) {
      YikeSyncDebugLog.log("ReadBoard received yikeSyncStop");
      handleYikeSyncStopCommand();
      return;
    }
    // if (Lizzie.gtpConsole.isVisible())
    // Lizzie.gtpConsole.addLine(line);
    //  System.out.println(line);
    //    if (Lizzie.frame.isPlayingAgainstLeelaz) {
    //      if (Lizzie.frame.playerIsBlack && !Lizzie.board.getHistory().isBlacksTurn()) return;
    //      if (!Lizzie.frame.playerIsBlack && Lizzie.board.getHistory().isBlacksTurn()) return;
    //    }
    if (line.startsWith("playpon")) {
      String[] params = line.split(" ");
      if (params.length == 2) {
        if (params[1].startsWith("on")) {
          Lizzie.config.readBoardPonder = true;
        } else if (params[1].startsWith("off")) {
          Lizzie.config.readBoardPonder = false;
          disableReadBoardGmaPonderIfIdle();
        }
      }
    }
    if (line.startsWith("re=")) {
      String[] params = line.substring(3).split(",");
      if (params.length == Board.boardWidth) {
        for (int i = 0; i < params.length; i++)
          tempcount.add(Integer.parseInt(params[i].substring(0, 1)));
      }
    }
    if (line.startsWith("foxMoveNumber")) {
      OptionalInt foxMoveNumber = parseFoxMoveNumber(line);
      if (foxMoveNumber.isPresent()) {
        pendingRemoteContext = currentPendingRemoteContext().withFoxMoveNumber(foxMoveNumber);
        publishCurrentReadBoardDiagnosticsSnapshot();
      }
    }
    if (line.startsWith("lastMoveSource ")) {
      pendingRemoteContext =
          currentPendingRemoteContext()
              .withLastMoveSource(
                  ReadBoardLastMoveSource.parse(line.substring("lastMoveSource ".length()).trim()));
      publishCurrentReadBoardDiagnosticsSnapshot();
      return;
    }
    if (line.startsWith("syncPlatform ")) {
      pendingRemoteContext =
          currentPendingRemoteContext()
              .withPlatform(parseSyncPlatform(line.substring("syncPlatform ".length())));
      if (isYikeSyncPlatformLine(line)) {
        YikeSyncDebugLog.log("ReadBoard received syncPlatform yike");
        handleYikeSyncStartCommand(false);
      }
      publishCurrentReadBoardDiagnosticsSnapshot();
      return;
    }
    if (line.startsWith("roomToken ")) {
      pendingRemoteContext =
          currentPendingRemoteContext().withRoomToken(line.substring("roomToken ".length()).trim());
      publishCurrentReadBoardDiagnosticsSnapshot();
      return;
    }
    if (line.startsWith("liveTitleMove ")) {
      pendingRemoteContext =
          currentPendingRemoteContext().withLiveTitleMove(parseOptionalInt(line, "liveTitleMove "));
      publishCurrentReadBoardDiagnosticsSnapshot();
      return;
    }
    if (line.startsWith("recordCurrentMove ")) {
      pendingRemoteContext =
          currentPendingRemoteContext()
              .withRecordCurrentMove(parseOptionalInt(line, "recordCurrentMove "));
      publishCurrentReadBoardDiagnosticsSnapshot();
      return;
    }
    if (line.startsWith("recordTotalMove ")) {
      pendingRemoteContext =
          currentPendingRemoteContext()
              .withRecordTotalMove(parseOptionalInt(line, "recordTotalMove "));
      publishCurrentReadBoardDiagnosticsSnapshot();
      return;
    }
    if (line.startsWith("recordAtEnd ")) {
      pendingRemoteContext =
          currentPendingRemoteContext().withRecordAtEnd(line.trim().endsWith("1"));
      publishCurrentReadBoardDiagnosticsSnapshot();
      return;
    }
    if (line.startsWith("recordTitleFingerprint ")) {
      pendingRemoteContext =
          currentPendingRemoteContext()
              .withTitleFingerprint(line.substring("recordTitleFingerprint ".length()).trim());
      publishCurrentReadBoardDiagnosticsSnapshot();
      return;
    }
    if (line.trim().equals("forceRebuild")) {
      pendingRemoteContext = currentPendingRemoteContext().withForceRebuild(true);
      publishCurrentReadBoardDiagnosticsSnapshot();
      return;
    }
    if (line.startsWith("version")) {
      Lizzie.gtpConsole.addLineReadBoard("Board synchronization tool " + line + "\n");
      String[] params = line.trim().split(" ");
      if (Integer.parseInt(params[1]) < version) {
        SMessage msg = new SMessage();
        msg.setMessage(Lizzie.resourceBundle.getString("ReadBoard.versionCheckFaied"), 2);
      }
    }
    if (isPlacementFailedLine(line)) {
      handleLocalMovePlacementFailed(line);
    }
    if (line.startsWith("error")) {
      Lizzie.gtpConsole.addLineReadBoard(line + (usePipe ? "" : "\n"));
    }
    if (line.startsWith("end")) {
      boolean isYikePlatform =
          pendingRemoteContext != null
              && pendingRemoteContext.platform == SyncRemoteContext.SyncPlatform.YIKE;
      if (!isSyncing && !isYikePlatform) syncBoardStones(false);
      clearPendingRemoteContext();
      tempcount = new ArrayList<Integer>();
      publishCurrentReadBoardDiagnosticsSnapshot();
    }
    if (line.trim().equals("clear")) {
      resetActiveSyncStateForReadBoardControlLine();
      clearPendingRemoteContext();
      tempcount = new ArrayList<Integer>();
      publishCurrentReadBoardDiagnosticsSnapshot();
    }
    if (line.trim().equals("clearBoard")) {
      runOnEdtAndWait(() -> Lizzie.board.clear(false));
    }
    if (line.startsWith("start")) {
      clearPendingRemoteContext();
      String[] params = line.trim().split(" ");
      if (params.length >= 3) {
        int boardWidth = Integer.parseInt(params[1]);
        int boardHeight = Integer.parseInt(params[2]);
        if (boardWidth != Board.boardWidth || boardHeight != Board.boardHeight) {
          resetActiveSyncState();
          clearResumeState();
          Lizzie.board.reopen(boardWidth, boardHeight);
        } else {
          resetActiveSyncStateForReadBoardControlLine();
        }
      } else {
        resetActiveSyncStateForReadBoardControlLine();
      }
      tempcount = new ArrayList<Integer>();
      publishCurrentReadBoardDiagnosticsSnapshot();
    }
    if (line.trim().equals("sync")) {
      Lizzie.frame.syncBoard = true;
      if (isFailedLocalMoveAwaitingRemoteObservation()) {
        localMoveSyncDebug(
            "sync line resumes analysis while failed-place guard only blocks placement "
                + pendingLocalMoveState());
        releaseFailedLocalMoveObservationIfTimedOut("sync-line");
      }
      if (!readBoardGmaAutoPlayActive
          && isReadBoardAnalysisEngineAvailable()
          && !Lizzie.leelaz.isPondering()) {
        Lizzie.leelaz.togglePonder();
      }
    }
    if (line.startsWith("both")) {
      Lizzie.frame.bothSync = true;
      if (Lizzie.frame.floatBoard != null && Lizzie.frame.floatBoard.isVisible())
        Lizzie.frame.floatBoard.setEditButton();
    }
    if (line.startsWith("noboth")) {
      clearReadBoardGmaAutoPlay("noboth");
      readBoardTurnTrusted = false;
      Lizzie.frame.bothSync = false;
      if (Lizzie.frame.floatBoard != null && Lizzie.frame.floatBoard.isVisible())
        Lizzie.frame.floatBoard.setEditButton();
    }
    if (line.startsWith("stopAutoPlay")) {
      clearReadBoardGmaAutoPlay("stopAutoPlay");
      LizzieFrame.toolbar.chkAutoPlay.setSelected(false);
      LizzieFrame.toolbar.isAutoPlay = false;
    }
    if (line.startsWith("endsync")) {
      clearReadBoardGmaAutoPlay("endsync");
      noMsg = true;
      resetActiveSyncStateForReadBoardControlLine();
      clearPendingRemoteContext();
      tempcount = new ArrayList<Integer>();
      Lizzie.frame.syncBoard = false;
      if (Lizzie.frame.isAnaPlayingAgainstLeelaz) {
        Lizzie.frame.stopAiPlayingAndPolicy();
      }
      showInBoard = false;
      if (Lizzie.frame.floatBoard != null) {
        Lizzie.frame.floatBoard.setVisible(false);
      }
      publishCurrentReadBoardDiagnosticsSnapshot();
    }
    if (line.startsWith("stopsync")) {
      clearReadBoardGmaAutoPlay("stopsync");
      resetActiveSyncStateForReadBoardControlLine();
      clearPendingRemoteContext();
      tempcount = new ArrayList<Integer>();
      Lizzie.frame.syncBoard = false;
      if (Lizzie.frame.isAnaPlayingAgainstLeelaz) {
        Lizzie.frame.stopAiPlayingAndPolicy();
      }
      Lizzie.leelaz.nameCmd();
      showInBoard = false;
      if (Lizzie.frame.floatBoard != null) {
        Lizzie.frame.floatBoard.setVisible(false);
      }
      publishCurrentReadBoardDiagnosticsSnapshot();
    }
    if (line.startsWith("play")) {
      String[] params = line.trim().split(">");
      Stone autoPlayColor = autoPlayColorFromPlayParams(params);
      if (params.length == 3) {
        String[] playParams = params[2].trim().split(" ");
        if (playParams.length < 3) {
          return;
        }
        int playouts = Integer.parseInt(playParams[1]);
        int firstPlayouts = Integer.parseInt(playParams[2]);
        int time = Integer.parseInt(playParams[0]);
        boolean useGma = isReadBoardGmaPlayMode(playParams);
        Leelaz currentForegroundEngine = useGma ? Lizzie.leelaz : null;
        if (currentForegroundEngine != null
            && !currentForegroundEngine.beginExclusiveGtpLifecycleTransition()) {
          showForegroundEngineLeaseConflict();
          return;
        }
        try {
          clearFailedLocalMoveStateIfAutoPlaySideChanged(autoPlayColor);
          if (hasFailedLocalMoveStateToPreserve()) {
            localMoveSyncDebug(
                "play line preserves failed local move state autoPlayColor="
                    + autoPlayColor
                    + " "
                    + pendingLocalMoveState());
          } else {
            clearFailedLocalMoveSuppression();
            clearFailedLocalMoveRecovery();
          }
        readBoardGmaAutoPlayActive = useGma;
        readBoardGmaAutoPlayColor = autoPlayColor;
        readBoardGmaTimeSeconds = Math.max(0, time);
        readBoardGmaMaxVisits = Math.max(0, playouts);
        readBoardGmaAwaitingSyncedBoard = useGma;
        } finally {
          if (currentForegroundEngine != null) {
            currentForegroundEngine.endExclusiveGtpLifecycleTransition();
          }
        }
        if (!useGma) {
          readBoardGmaAwaitingSyncedBoard = false;
          invalidateReadBoardGmaPhysicalRequestIfPending("play-mode-switch");
        }
        if (time > 0) {
          LizzieFrame.toolbar.txtAutoPlayTime.setText(String.valueOf(time));
          LizzieFrame.toolbar.chkAutoPlayTime.setSelected(true);
        } else {
          LizzieFrame.toolbar.txtAutoPlayTime.setText(
              String.valueOf(Lizzie.config.leelazConfig.getInt("max-game-thinking-time-seconds")));
          LizzieFrame.toolbar.chkAutoPlayTime.setSelected(true);
        }
        if (playouts > 0) {
          LizzieFrame.toolbar.txtAutoPlayPlayouts.setText(String.valueOf(playouts));
          LizzieFrame.toolbar.chkAutoPlayPlayouts.setSelected(true);
        } else LizzieFrame.toolbar.chkAutoPlayPlayouts.setSelected(false);
        if (firstPlayouts > 0) {
          LizzieFrame.toolbar.txtAutoPlayFirstPlayouts.setText(String.valueOf(firstPlayouts));
          LizzieFrame.toolbar.chkAutoPlayFirstPlayouts.setSelected(true);
        } else LizzieFrame.toolbar.chkAutoPlayFirstPlayouts.setSelected(false);
        if (params[1].equals("black")) {
          LizzieFrame.toolbar.chkAutoPlayBlack.setSelected(true);
          LizzieFrame.toolbar.chkAutoPlayWhite.setSelected(false);
          LizzieFrame.toolbar.chkAutoPlay.setSelected(true);
          LizzieFrame.toolbar.setChkShowBlack(true);
          LizzieFrame.toolbar.setChkShowWhite(true);
          Lizzie.config.UsePureNetInGame = false;
          Lizzie.frame.isAnaPlayingAgainstLeelaz = true;
          LizzieFrame.toolbar.isAutoPlay = true;
          Lizzie.frame.clearWRNforGame(false);
        } else if (params[1].equals("white")) {
          LizzieFrame.toolbar.chkAutoPlayBlack.setSelected(false);
          LizzieFrame.toolbar.chkAutoPlayWhite.setSelected(true);
          LizzieFrame.toolbar.chkAutoPlay.setSelected(true);
          LizzieFrame.toolbar.setChkShowBlack(true);
          LizzieFrame.toolbar.setChkShowWhite(true);
          Lizzie.config.UsePureNetInGame = false;
          Lizzie.frame.isAnaPlayingAgainstLeelaz = true;
          LizzieFrame.toolbar.isAutoPlay = true;
          Lizzie.frame.clearWRNforGame(false);
        }
        if (!readBoardGmaAutoPlayActive) {
          Lizzie.leelaz.ponder();
        }
      }
    }
    if (line.startsWith("pass")) {
      Lizzie.board.changeNextTurn();
      readBoardTurnTrusted = true;
      scheduleReadBoardGmaIfNeeded("readboard-exchange-order");
    }
    if (line.startsWith("firstchanged")) {
      String[] params = line.trim().split(" ");
      if (params.length == 2) {
        int firstPlayouts = Integer.parseInt(params[1]);
        if (firstPlayouts > 0) {
          LizzieFrame.toolbar.txtAutoPlayFirstPlayouts.setText(String.valueOf(firstPlayouts));
          LizzieFrame.toolbar.chkAutoPlayFirstPlayouts.setSelected(true);
        } else LizzieFrame.toolbar.chkAutoPlayFirstPlayouts.setSelected(false);
      }
    }
    if (line.startsWith("playoutschanged")) {
      String[] params = line.trim().split(" ");
      if (params.length == 2) {
        int playouts = Integer.parseInt(params[1]);
        if (readBoardGmaAutoPlayActive) {
          readBoardGmaMaxVisits = Math.max(0, playouts);
        }
        if (playouts > 0) {
          LizzieFrame.toolbar.txtAutoPlayPlayouts.setText(String.valueOf(playouts));
          LizzieFrame.toolbar.chkAutoPlayPlayouts.setSelected(true);
        } else LizzieFrame.toolbar.chkAutoPlayPlayouts.setSelected(false);
      }
    }
    if (line.startsWith("timechanged")) {
      String[] params = line.trim().split(" ");
      if (params.length == 2) {
        int time = Integer.parseInt(params[1]);
        if (readBoardGmaAutoPlayActive) {
          readBoardGmaTimeSeconds = Math.max(0, time);
        }
        if (time > 0) {
          LizzieFrame.toolbar.txtAutoPlayTime.setText(String.valueOf(time));
          LizzieFrame.toolbar.chkAutoPlayTime.setSelected(true);
        } else {
          LizzieFrame.toolbar.txtAutoPlayTime.setText(
              String.valueOf(Lizzie.config.leelazConfig.getInt("max-game-thinking-time-seconds")));
          LizzieFrame.toolbar.chkAutoPlayTime.setSelected(true);
        }
      }
    }

    if (line.trim().equals("noponder")) {
      clearReadBoardGmaAutoPlay("noponder");
      if (Lizzie.frame.isPlayingAgainstLeelaz) {
        Lizzie.frame.isPlayingAgainstLeelaz = false;
        Lizzie.leelaz.isThinking = false;
      }
      if (Lizzie.frame.isAnaPlayingAgainstLeelaz) {
        Lizzie.frame.stopAiPlayingAndPolicy();
      }
      stopPonderingIfActive();
      sendAnalysisState();
    }
    if (line.trim().equals("resumeponder")) {
      runOnEdtAndWait(
          () -> {
            if (isReadBoardAnalysisEngineAvailable() && !Lizzie.leelaz.isPondering()) {
              Lizzie.frame.togglePonderMannul();
            }
          });
      sendAnalysisState();
    }
    if (line.startsWith("noinboard")) {
      if (Lizzie.frame.floatBoard != null && Lizzie.frame.floatBoard.isVisible()) {
        Lizzie.frame.floatBoard.setVisible(false);
      }
    }
    if (line.startsWith("inboard")) {
      //	Lizzie.gtpConsole.addLine(line);
      if (hideFromPlace) return;
      showInBoard = true;
      String[] params = line.trim().split(" ");
      if (params.length == 6) {
        if (params[5].startsWith("99")) {
          String[] param = params[5].split("_");
          float factor = Float.parseFloat(param[1]);
          if (Lizzie.frame.floatBoard == null) {
            Lizzie.frame.floatBoard =
                new FloatBoard(
                    (int) Math.ceil(Integer.parseInt(params[1]) * factor),
                    (int) Math.ceil(Integer.parseInt(params[2]) * factor),
                    (int) Math.ceil(Integer.parseInt(params[3]) * factor),
                    (int) Math.ceil(Integer.parseInt(params[4]) * factor),
                    Integer.parseInt(param[2]),
                    true);
            // Lizzie.frame.floatBoard.setFactor(factor);
          } else {
            Lizzie.frame.floatBoard.setPos(
                (int) Math.ceil(Integer.parseInt(params[1]) * factor),
                (int) Math.ceil(Integer.parseInt(params[2]) * factor),
                (int) Math.ceil(Integer.parseInt(params[3]) * factor),
                (int) Math.ceil(Integer.parseInt(params[4]) * factor),
                Integer.parseInt(param[2]));
            //   Lizzie.frame.floatBoard.setFactor(factor);
          }
        } else {
          if (Lizzie.frame.floatBoard == null) {
            Lizzie.frame.floatBoard =
                new FloatBoard(
                    Integer.parseInt(params[1]),
                    Integer.parseInt(params[2]),
                    Integer.parseInt(params[3]),
                    Integer.parseInt(params[4]),
                    Integer.parseInt(params[5]),
                    false);
            Lizzie.frame.floatBoard.setBoardType();
          } else {
            Lizzie.frame.floatBoard.setPos(
                Integer.parseInt(params[1]),
                Integer.parseInt(params[2]),
                Integer.parseInt(params[3]),
                Integer.parseInt(params[4]),
                Integer.parseInt(params[5]));
          }
        }
      }
    }
    if (line.startsWith("notinboard")) {
      showInBoard = false;
      if (Lizzie.frame.floatBoard != null) {
        Lizzie.frame.floatBoard.setVisible(false);
      }
    }
    if (line.startsWith("foreFoxWithInBoard")) {
      hideFloadBoardBeforePlace = true;
    }
    if (line.startsWith("notForeFoxWithInBoard")) {
      hideFloadBoardBeforePlace = false;
    }
    if (line.startsWith("placeComplete")) {
      localMoveSyncDebug("readboard line placeComplete before " + pendingLocalMoveState());
      markLocalMoveCommandCompleted();
      restoreFloatBoardAfterPlaceResult();
      localMoveSyncDebug("readboard line placeComplete after " + pendingLocalMoveState());
    }
  }

  void showForegroundEngineLeaseConflict() {
    SwingUtilities.invokeLater(
        () ->
            Utils.showMsg(
                Lizzie.resourceBundle.getString(
                    "AnalysisSettings.reuseStatus.existing_lease")));
  }

  private static boolean isPlacementFailedLine(String line) {
    return line != null && line.trim().startsWith(READBOARD_PLACEMENT_FAILED);
  }

  private void handleLocalMovePlacementFailed(String line) {
    String reason = "readboard line placement failed line=" + line;
    if (ignoreReadBoardPlaceResultsForCurrentPending) {
      localMoveSyncDebug(
          "pending local move placement failure bypasses stale placeComplete quarantine reason="
              + reason
              + " "
              + pendingLocalMoveState());
    }
    handlePendingLocalMovePlacementFailure(reason);
  }

  private boolean handlePendingLocalMovePlacementFailure(String reason) {
    localMoveSyncDebug(
        "pending local move placement failure reason="
            + reason
            + " before "
            + pendingLocalMoveState());
    restoreFloatBoardAfterPlaceResult();
    if (isPendingLocalMoveAwaitingReadBoard()) {
      clearConfirmedLocalMove();
      rememberFailedLocalMoveSuppression();
      Optional<BoardHistoryNode> rollbackNode = discardFailedLocalMoveFromMainEnd();
      clearPendingLocalMoveTracking();
      if (failedLocalMoveRecoveryActive) {
        beginFailedLocalMoveObservationGuard();
      }
      if (rollbackNode.isPresent()) {
        bindFailedLocalMoveSuppressionBaseline(rollbackNode.get());
        syncEngineToRebuiltSnapshot(rollbackNode.get());
        localMoveSyncDebug(
            "placement failed rolled back; resume analysis with placement guard baseline="
                + historyNodeSummary(rollbackNode.get())
                + " "
                + pendingLocalMoveState());
        continueGameAfterSyncIfNeeded("placeFailed", rollbackNode.get());
      } else if (failedLocalMoveRecoveryActive) {
        localMoveSyncDebug(
            "placement failed keeps placement guard without rollback " + pendingLocalMoveState());
      }
    } else {
      localMoveSyncDebug(
          "pending local move placement failure ignored no pending reason="
              + reason
              + " "
              + pendingLocalMoveState());
    }
    localMoveSyncDebug(
        "pending local move placement failure after reason="
            + reason
            + " "
            + pendingLocalMoveState());
    return true;
  }

  private Optional<BoardHistoryNode> discardFailedLocalMoveFromMainEnd() {
    if (!failedLocalMoveRecoveryActive
        || Lizzie.board == null
        || Lizzie.board.getHistory() == null) {
      localMoveSyncDebug(
          "discard failed local move skip missing recovery " + pendingLocalMoveState());
      return Optional.empty();
    }
    BoardHistoryList history = Lizzie.board.getHistory();
    BoardHistoryNode failedNode = history.getMainEnd();
    if (pendingLocalMoveNode != null && failedNode != pendingLocalMoveNode) {
      localMoveSyncDebug(
          "discard failed local move skip main end moved away from pending node mainEnd="
              + historyNodeSummary(failedNode)
              + " pendingNode="
              + historyNodeSummary(pendingLocalMoveNode)
              + " "
              + pendingLocalMoveState());
      return Optional.empty();
    }
    if (failedNode == null || !matchesFailedLocalMove(failedNode)) {
      localMoveSyncDebug(
          "discard failed local move skip no matching main end node="
              + historyNodeSummary(failedNode)
              + " "
              + pendingLocalMoveState());
      return Optional.empty();
    }
    Optional<BoardHistoryNode> previous = failedNode.previous();
    if (!previous.isPresent()) {
      localMoveSyncDebug(
          "discard failed local move skip no previous node="
              + historyNodeSummary(failedNode)
              + " "
              + pendingLocalMoveState());
      return Optional.empty();
    }
    BoardHistoryNode rollbackNode = previous.get();
    int childIndex = rollbackNode.indexOfNode(failedNode);
    if (childIndex < 0) {
      localMoveSyncDebug(
          "discard failed local move skip child not linked failed="
              + historyNodeSummary(failedNode)
              + " rollback="
              + historyNodeSummary(rollbackNode)
              + " "
              + pendingLocalMoveState());
      return Optional.empty();
    }
    moveToAnyPositionWithoutTracking(rollbackNode);
    rollbackNode.deleteChild(childIndex);
    historyJumpTracker.clear();
    localMoveSyncDebug(
        "discard failed local move rollback="
            + historyNodeSummary(rollbackNode)
            + " removed="
            + historyNodeSummary(failedNode)
            + " childIndex="
            + childIndex
            + " "
            + pendingLocalMoveState());
    if (Lizzie.frame != null) {
      Lizzie.frame.redrawTree = true;
      Lizzie.frame.renderVarTree(0, 0, false, false);
      Lizzie.frame.refresh();
    }
    return Optional.of(rollbackNode);
  }

  private boolean matchesFailedLocalMove(BoardHistoryNode node) {
    if (node == null || node.getData() == null) {
      return false;
    }
    BoardData data = node.getData();
    if (!data.isMoveNode() || !data.lastMove.isPresent()) {
      return false;
    }
    int[] coords = data.lastMove.get();
    return coords[0] == failedLocalMoveRecoveryX
        && coords[1] == failedLocalMoveRecoveryY
        && data.lastMoveColor == failedLocalMoveRecoveryColor;
  }

  private void restoreFloatBoardAfterPlaceResult() {
    if (hideFloadBoardBeforePlace && hideFromPlace) {
      hideFromPlace = false;
      if (Lizzie.frame != null && Lizzie.frame.floatBoard != null) {
        Lizzie.frame.floatBoard.setVisible(true);
      }
    }
  }

  private void recordProtocolLine(String line) {
    lastProtocolLineSummary = summarizeProtocolLine(line);
    lastProtocolTimestampMillis = System.currentTimeMillis();
    SyncDiagnosticsRecorder recorder = SyncDiagnosticsRecorder.getDefault();
    recorder.recordProtocolEvent(
        SyncProtocolDiagnosticEvent.of(
            lastProtocolTimestampMillis, lastProtocolLineSummary, "ReadBoard"));
    publishReadBoardDiagnosticsSnapshot(recorder.snapshot().getLatestDecisionTrace());
  }

  private String summarizeProtocolLine(String line) {
    if (line == null) {
      return "null";
    }
    String trimmed = line.trim();
    if (trimmed.isEmpty()) {
      return "empty";
    }
    String command = protocolCommand(trimmed);
    if (isSensitiveProtocolCommand(command) || trimmed.length() > 80) {
      return command + " <redacted>";
    }
    return trimmed;
  }

  private String protocolCommand(String line) {
    int firstWhitespace = firstWhitespaceIndex(line);
    if (firstWhitespace <= 0) {
      return line.length() > 40 ? "<payload>" : line;
    }
    return line.substring(0, firstWhitespace);
  }

  private int firstWhitespaceIndex(String line) {
    for (int index = 0; index < line.length(); index++) {
      if (Character.isWhitespace(line.charAt(index))) {
        return index;
      }
    }
    return -1;
  }

  private boolean isSensitiveProtocolCommand(String command) {
    String normalized = command.toLowerCase(Locale.ROOT);
    return "roomtoken".equals(normalized)
        || "livetitle".equals(normalized)
        || "sgf".equals(normalized)
        || "loadsgf".equals(normalized)
        || "recordtitlefingerprint".equals(normalized)
        || "readboardupdateready".equals(normalized);
  }

  private SyncDecisionTrace publishCompleteSnapshotRecoveryDiagnostics(
      CompleteSnapshotRecoveryDecision recovery,
      SyncRemoteContext remoteContext,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta) {
    SyncDecisionTrace trace =
        SyncDecisionTrace.builder(recovery.outcome.name(), recovery.reasonCode)
            .source("ReadBoard.syncBoardStones")
            .summary(recovery.outcome.name() + " " + recovery.reasonCode)
            .platform(remoteContext.platform.name())
            .windowKind(remoteContext.windowKind.name())
            .remoteContextFingerprint(buildRemoteContextFingerprint(remoteContext))
            .snapshotHash(SyncDecisionTrace.hashSnapshotCodes(snapshotCodes))
            .changedStoneCount(snapshotDelta.changedStones())
            .removedStoneCount(snapshotDelta.removals())
            .recoveryMoveNumber(
                remoteContext.recoveryMoveNumber().isPresent()
                    ? remoteContext.recoveryMoveNumber().getAsInt()
                    : -1)
            .resolvedSnapshotMoveNumber(resolvedSnapshotMoveNumber(recovery.resolvedNode))
            .resolvedSnapshotKind(resolvedSnapshotKind(recovery.resolvedNode))
            .forceRebuildRequested(remoteContext.forceRebuild)
            .firstSyncFrame(awaitingFirstSyncFrame)
            .shouldResumeAnalysis(recovery.shouldResumeAnalysis)
            .timestampMillis(System.currentTimeMillis())
            .epoch(syncAnalysisEpoch)
            .build();
    SyncDiagnosticsRecorder recorder = SyncDiagnosticsRecorder.getDefault();
    recorder.updateLatestDecision(trace);
    publishReadBoardDiagnosticsSnapshot(trace);
    return trace;
  }

  private void publishCurrentReadBoardDiagnosticsSnapshot() {
    publishReadBoardDiagnosticsSnapshot(
        SyncDiagnosticsRecorder.getDefault().snapshot().getLatestDecisionTrace());
  }

  private void publishReadBoardDiagnosticsSnapshot(SyncDecisionTrace latestDecisionTrace) {
    SyncDiagnosticsRecorder.getDefault()
        .updateSync(
            SyncDiagnosticsSnapshot.builder()
                .readBoardAttached(Lizzie.frame != null && Lizzie.frame.readBoard == this)
                .readBoardConnected(isReadBoardConnectedForDiagnostics())
                .usePipe(usePipe)
                .syncing(isSyncing)
                .awaitingFirstSyncFrame(awaitingFirstSyncFrame)
                .hasResumeState(resumeState != null)
                .hasLastResolvedSnapshotNode(lastResolvedSnapshotNode != null)
                .syncAnalysisEpoch(syncAnalysisEpoch)
                .timestampMillis(System.currentTimeMillis())
                .source("ReadBoard")
                .summary("readboard diagnostics snapshot")
                .pendingRemoteContextSummary(
                    buildRemoteContextFingerprint(currentPendingRemoteContext()))
                .lastResolvedSnapshotSummary(
                    summarizeResolvedSnapshotNode(lastResolvedSnapshotNode))
                .lastProtocolLineSummary(lastProtocolLineSummary)
                .lastProtocolTimestampMillis(lastProtocolTimestampMillis)
                .latestDecisionTrace(latestDecisionTrace)
                .build());
  }

  private boolean isReadBoardConnectedForDiagnostics() {
    return outputStream != null;
  }

  private String buildRemoteContextFingerprint(SyncRemoteContext remoteContext) {
    if (remoteContext == null) {
      return "none";
    }
    StringBuilder summary = new StringBuilder();
    summary.append("platform=").append(remoteContext.platform.name());
    summary.append(",windowKind=").append(remoteContext.windowKind.name());
    summary.append(",hasRoomToken=").append(remoteContext.roomToken.isPresent());
    summary.append(",hasTitleFingerprint=").append(remoteContext.titleFingerprint.isPresent());
    summary.append(",recordAtEnd=").append(remoteContext.recordAtEnd);
    summary.append(",forceRebuild=").append(remoteContext.forceRebuild);
    summary.append(",recoveryMoveNumber=");
    summary.append(
        remoteContext.recoveryMoveNumber().isPresent()
            ? remoteContext.recoveryMoveNumber().getAsInt()
            : -1);
    return summary.toString();
  }

  private String summarizeResolvedSnapshotNode(BoardHistoryNode node) {
    if (node == null) {
      return "none";
    }
    BoardData data = node.getData();
    return "moveNumber=" + data.moveNumber + ",kind=" + data.getNodeKind().name();
  }

  private int resolvedSnapshotMoveNumber(BoardHistoryNode node) {
    return node == null ? -1 : node.getData().moveNumber;
  }

  private String resolvedSnapshotKind(BoardHistoryNode node) {
    return node == null ? "none" : node.getData().getNodeKind().name();
  }

  private static boolean isExactReadBoardCommand(String line, String command) {
    return line != null && command.equals(line.trim());
  }

  private void handleYikeSyncStartCommand(final boolean forceReloadCurrentRoom) {
    SwingUtilities.invokeLater(
        new Runnable() {
          public void run() {
            YikeSyncDebugLog.log(
                "ReadBoard handling yike start on EDT frame="
                    + (Lizzie.frame != null)
                    + " browserFrame="
                    + (Lizzie.frame != null && Lizzie.frame.browserFrame != null)
                    + " forceReload="
                    + forceReloadCurrentRoom);
            if (Lizzie.frame != null && Lizzie.frame.browserFrame != null) {
              if (forceReloadCurrentRoom) {
                Lizzie.frame.browserFrame.startYikeSyncFromReadBoard();
              } else {
                Lizzie.frame.browserFrame.ensureYikeSyncFromCurrentAddress();
              }
            }
          }
        });
  }

  private void handleYikeSyncStopCommand() {
    SwingUtilities.invokeLater(
        new Runnable() {
          public void run() {
            YikeSyncDebugLog.log(
                "ReadBoard handling yike stop on EDT frame="
                    + (Lizzie.frame != null)
                    + " browserFrame="
                    + (Lizzie.frame != null && Lizzie.frame.browserFrame != null));
            if (Lizzie.frame != null && Lizzie.frame.browserFrame != null) {
              Lizzie.frame.browserFrame.stopYikeSyncFromReadBoard();
            }
          }
        });
  }

  private void syncBoardStones(boolean isSecondTime) {
    //    if (!isSecondTime) {
    //      long thisTime = System.currentTimeMillis();
    //      if (thisTime - startSyncTime < Lizzie.config.readBoardArg2 / 2) return;
    //      startSyncTime = thisTime;
    //    }
    localNavigationTracker.startSyncPass(isSecondTime);
    if (tempcount.size() > Board.boardWidth * Board.boardHeight) {
      tempcount = new ArrayList<Integer>();
      resetActiveSyncState();
      return;
    }
    isSyncing = true;
    try {
      boolean needReSync = false;
      boolean played = false;
      boolean singleMoveRecovered = false;
      SyncDecisionTrace completeSnapshotTrace = null;
      boolean holdLastMove = false;
      int lastX = 0;
      int lastY = 0;
      int playedMove = 0;
      boolean isLastBlack = false;
      BoardHistoryNode node = Lizzie.board.getHistory().getCurrentHistoryNode();
      BoardHistoryNode node2 = Lizzie.board.getHistory().getMainEnd();
      Stone[] syncStartStones = node2.getData().stones.clone();
      Stone[] stones = Lizzie.board.getHistory().getMainEnd().getData().stones;
      int[] currentSnapshotCodes = getSnapshotCodes();
      SyncRemoteContext currentRemoteContext = currentPendingRemoteContext();
      OptionalInt currentFoxMoveNumber = currentRemoteContext.recoveryMoveNumber();
      SyncSnapshotClassifier classifier =
          new SyncSnapshotClassifier(Board.boardWidth, Board.boardHeight);
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta =
          classifier.summarizeDelta(syncStartStones, currentSnapshotCodes);
      if (!snapshotDiffChecker().isComparable(currentSnapshotCodes, stones)) {
        if (lastMovePlayByLizzie || failedLocalMoveSuppressionActive) {
          localMoveSyncDebug(
              "syncBoardStones not-comparable isSecondTime="
                  + isSecondTime
                  + " snapshot="
                  + snapshotSummary(currentSnapshotCodes)
                  + " "
                  + pendingLocalMoveState());
        }
        return;
      }
      boolean diffWithoutIgnore =
          snapshotDiffChecker().hasDiff(currentSnapshotCodes, stones, false, Optional.empty());
      if (lastMovePlayByLizzie || failedLocalMoveSuppressionActive || diffWithoutIgnore) {
        localMoveSyncDebug(
            "syncBoardStones start isSecondTime="
                + isSecondTime
                + " diffWithoutIgnore="
                + diffWithoutIgnore
                + " allowsIncremental="
                + allowsIncrementalSync(snapshotDelta, node2, currentRemoteContext)
                + " snapshot="
                + snapshotSummary(currentSnapshotCodes)
                + " "
                + pendingLocalMoveState());
      }
      updateFailedLocalMoveSuppressionForSnapshot(currentSnapshotCodes);
      if (acknowledgeLocalMoveIfSnapshotCaughtUp(stones, currentSnapshotCodes)) {
        updateReadBoardTurnTrustFromAcceptedFrame(
            node2,
            currentSnapshotCodes,
            snapshotDelta,
            currentRemoteContext,
            currentRemoteContext.lastMoveSource,
            currentFoxMoveNumber,
            false);
        finishSyncAfterAcknowledgedLocalMoveSnapshot();
        return;
      }
      updateConfirmedLocalMoveForSnapshot(currentSnapshotCodes);
      if (shouldHoldSyncForConfirmedLocalMove(currentSnapshotCodes)) {
        localMoveSyncDebug(
            "syncBoardStones hold stale snapshot before confirmed local move snapshot="
                + snapshotSummary(currentSnapshotCodes)
                + " "
                + pendingLocalMoveState());
        return;
      }
      if (retryPendingLocalMoveIfSnapshotStillMissing(currentSnapshotCodes)) {
        return;
      }

      boolean needRefresh = false;
      if (allowsIncrementalSync(snapshotDelta, node2, currentRemoteContext)) {
        for (int i = 0; i < tempcount.size(); i++) {
          int m = tempcount.get(i);
          int y = i / Board.boardWidth;
          int x = i % Board.boardWidth;
          if (((holdLastMove && m == 3) || m == 1) && !stones[Board.getIndex(x, y)].isBlack()) {
            if (stones[Board.getIndex(x, y)].isWhite()) {
              clearBoardWithoutInvalidatingResumeState(false);
              needReSync = true;
              needRefresh = true;
              break;
            }
            if (!played) {
              moveToAnyPositionWithoutTracking(node2);
            }
            Lizzie.board.placeForSync(x, y, Stone.BLACK, true);
            {
              EngineFollowController c = Lizzie.engineFollowController;
              if (c != null) c.onMainlineAdvance(Lizzie.board.getHistory().getCurrentHistoryNode());
            }
            if (node2.variations.size() > 0 && node2.variations.get(0).isEndDummay()) {
              node2.variations.add(0, node2.variations.get(node2.variations.size() - 1));
              node2.variations.remove(1);
              node2.variations.remove(node2.variations.size() - 1);
            }
            played = true;
            playedMove = playedMove + 1;
          }
          if (((holdLastMove && m == 4) || m == 2) && !stones[Board.getIndex(x, y)].isWhite()) {
            if (stones[Board.getIndex(x, y)].isBlack()) {
              clearBoardWithoutInvalidatingResumeState(false);
              needReSync = true;
              needRefresh = true;
              break;
            }

            if (!played) {
              moveToAnyPositionWithoutTracking(node2);
            }
            Lizzie.board.placeForSync(x, y, Stone.WHITE, true);
            {
              EngineFollowController c = Lizzie.engineFollowController;
              if (c != null) c.onMainlineAdvance(Lizzie.board.getHistory().getCurrentHistoryNode());
            }
            if (node2.variations.size() > 0 && node2.variations.get(0).isEndDummay()) {
              node2.variations.add(0, node2.variations.get(node2.variations.size() - 1));
              node2.variations.remove(1);
              node2.variations.remove(node2.variations.size() - 1);
            }
            played = true;
            playedMove = playedMove + 1;
          }

          if (!holdLastMove && m == 3 && !stones[Board.getIndex(x, y)].isBlack()) {
            if (stones[Board.getIndex(x, y)].isWhite()) {
              clearBoardWithoutInvalidatingResumeState(false);
              needReSync = true;
              needRefresh = true;
              break;
            }
            holdLastMove = true;
            lastX = x;
            lastY = y;
            isLastBlack = true;
          }
          if (!holdLastMove && m == 4 && !stones[Board.getIndex(x, y)].isWhite()) {
            if (stones[Board.getIndex(x, y)].isBlack()) {
              clearBoardWithoutInvalidatingResumeState(false);
              needReSync = true;
              needRefresh = true;
              break;
            }
            holdLastMove = true;
            lastX = x;
            lastY = y;
            isLastBlack = false;
          }
        }
        // 落最后一步
        if (holdLastMove && !needReSync) {
          if (!played) {
            moveToAnyPositionWithoutTracking(node2);
          }
          Lizzie.board.placeForSync(lastX, lastY, isLastBlack ? Stone.BLACK : Stone.WHITE, true);
          {
            EngineFollowController c = Lizzie.engineFollowController;
            if (c != null) c.onMainlineAdvance(Lizzie.board.getHistory().getCurrentHistoryNode());
          }
          if (node2.variations.size() > 0 && node2.variations.get(0).isEndDummay()) {
            node2.variations.add(0, node2.variations.get(node2.variations.size() - 1));
            node2.variations.remove(1);
            node2.variations.remove(node2.variations.size() - 1);
          }
          played = true;
          if (Lizzie.config.alwaysSyncBoardStat || showInBoard) lastMoveWithoutTracking();
        }
        stones = Lizzie.board.getHistory().getMainEnd().getData().stones;
        if (shouldResyncAfterIncrementalSync(stones, currentSnapshotCodes)) {
          needReSync = true;
        }
      } else {
        needReSync = true;
      }
      if (needReSync && !isSecondTime) {
        CompleteSnapshotRecoveryDecision recovery =
            resolveCompleteSnapshotRecovery(
                node2, node, syncStartStones, currentSnapshotCodes, snapshotDelta);
        SyncDecisionTrace trace =
            publishCompleteSnapshotRecoveryDiagnostics(
                recovery, currentRemoteContext, currentSnapshotCodes, snapshotDelta);
        completeSnapshotTrace = trace;
        if (recovery.outcome == CompleteSnapshotRecoveryOutcome.HOLD) {
          if (lastMovePlayByLizzie || failedLocalMoveSuppressionActive || diffWithoutIgnore) {
            localMoveSyncDebug(
                "syncBoardStones recovery HOLD snapshot="
                    + snapshotSummary(currentSnapshotCodes)
                    + " "
                    + pendingLocalMoveState());
          }
          resumeFailedLocalMoveAfterRecoveryHoldIfNeeded(played || needRefresh);
          return;
        }
        if (recovery.outcome == CompleteSnapshotRecoveryOutcome.FORCE_REBUILD) {
          localMoveSyncDebug(
              "syncBoardStones recovery FORCE_REBUILD snapshot="
                  + snapshotSummary(currentSnapshotCodes)
                  + " "
                  + pendingLocalMoveState());
          rebuildFromSnapshot(node2, currentSnapshotCodes, snapshotDelta, currentFoxMoveNumber);
          publishReadBoardDiagnosticsSnapshot(trace);
          return;
        }
        updateReadBoardTurnTrustFromAcceptedFrame(
            recovery.resolvedNode != null ? recovery.resolvedNode : node2,
            currentSnapshotCodes,
            snapshotDelta,
            currentRemoteContext,
            currentRemoteContext.lastMoveSource,
            currentFoxMoveNumber,
            recovery.outcome == CompleteSnapshotRecoveryOutcome.SINGLE_MOVE_RECOVERY);
        if (recovery.outcome == CompleteSnapshotRecoveryOutcome.NO_CHANGE
            && recovery.resolvedNode != null) {
          if (recovery.resolvedNode != Lizzie.board.getHistory().getCurrentHistoryNode()) {
            moveToAnyPositionWithoutTracking(recovery.resolvedNode);
            needRefresh = true;
          }
          if (recovery.shouldResumeAnalysis) {
            rememberResolvedSnapshotNode(recovery.resolvedNode);
            scheduleResumeAnalysisAfterSync(recovery.resolvedNode);
          }
        }
        needReSync = false;
        singleMoveRecovered =
            recovery.outcome == CompleteSnapshotRecoveryOutcome.SINGLE_MOVE_RECOVERY;
        played = singleMoveRecovered;
        needRefresh = needRefresh || played;
      }
      if (!needReSync) {
        BoardHistoryNode currentSyncEndNode = Lizzie.board.getHistory().getMainEnd();
        if (played && !singleMoveRecovered) {
          rememberResolvedSnapshotNode(currentSyncEndNode);
        }
        if (singleMoveRecovered) {
          keepViewOnRecoveredMainEnd(currentSyncEndNode);
        }
        BoardHistoryNode currentNode =
            singleMoveRecovered
                ? currentSyncEndNode
                : resolveLocalNavigationTarget(Lizzie.board.getHistory().getCurrentHistoryNode());
        if (shouldRebuildForFoxMetadataChange(
            currentSyncEndNode, currentRemoteContext, currentSnapshotCodes, snapshotDelta)) {
          rebuildFromSnapshot(
              currentSyncEndNode, currentSnapshotCodes, snapshotDelta, currentFoxMoveNumber);
          return;
        }
        updateReadBoardTurnTrustFromAcceptedFrame(
            currentSyncEndNode,
            currentSnapshotCodes,
            snapshotDelta,
            currentRemoteContext,
            currentRemoteContext.lastMoveSource,
            currentFoxMoveNumber,
            played);
        historyJumpTracker.clear();
        applySyncViewState(played, currentNode, currentSyncEndNode);
      }
      if (!needReSync) {
        conflictTracker.clear();
        awaitingFirstSyncFrame = false;
      }
      if (completeSnapshotTrace != null && !needReSync) {
        publishReadBoardDiagnosticsSnapshot(completeSnapshotTrace);
      }
      if (played || needRefresh) {
        Lizzie.frame.refresh();
      }
      if (firstSync) {
        firstSync = false;
        previousMoveWithoutTracking(true);
        new Thread() {
          public void run() {
            try {
              Thread.sleep(500);
            } catch (InterruptedException e1) {
              // TODO Auto-generated catch block
              e1.printStackTrace();
            }
            lastMoveWithoutTracking();
          }
        }.start();
      }
      continueGameAfterSyncIfNeeded("sync", Lizzie.board.getHistory().getCurrentHistoryNode());
    } finally {
      localNavigationTracker.clear();
      isSyncing = false;
    }
    //	    if (played && Lizzie.config.alwaysGotoLastOnLive) {
    //	      int moveNumber = Lizzie.board.getHistory().getMainEnd().getData().moveNumber;
    //	      Lizzie.board.goToMoveNumberBeyondBranch(moveNumber);
    //	      Lizzie.frame.refresh();
    //	    }
  }

  private void resumeFailedLocalMoveAfterRecoveryHoldIfNeeded(boolean needRefresh) {
    if (!failedLocalMoveRecoveryActive
        || Lizzie.board == null
        || Lizzie.board.getHistory() == null) {
      return;
    }
    BoardHistoryNode heldMainEnd = Lizzie.board.getHistory().getMainEnd();
    localMoveSyncDebug(
        "syncBoardStones recovery HOLD checks failed local move target="
            + historyNodeSummary(heldMainEnd)
            + " "
            + pendingLocalMoveState());
    if (failedLocalMoveAwaitingRemoteObservation) {
      localMoveSyncDebug(
          "syncBoardStones recovery HOLD keeps failed-place guard but lets analysis continue "
              + pendingLocalMoveState());
      if (continueGameAfterSyncIfNeeded("sync-hold-observation", heldMainEnd)
          && needRefresh
          && Lizzie.frame != null) {
        Lizzie.frame.refresh();
      }
      return;
    }
    if (resumeFailedLocalMoveAfterSyncIfNeeded("sync-hold", heldMainEnd)
        && needRefresh
        && Lizzie.frame != null) {
      Lizzie.frame.refresh();
    }
  }

  private int[] getSnapshotCodes() {
    int[] codes = new int[tempcount.size()];
    for (int index = 0; index < tempcount.size(); index++) {
      codes[index] = tempcount.get(index);
    }
    return codes;
  }

  private OptionalInt parseFoxMoveNumber(String line) {
    String[] params = line.trim().split(" ");
    if (params.length != 2) {
      return OptionalInt.empty();
    }
    try {
      return OptionalInt.of(Integer.parseInt(params[1]));
    } catch (NumberFormatException ex) {
      return OptionalInt.empty();
    }
  }

  private SyncRemoteContext.SyncPlatform parseSyncPlatform(String platformToken) {
    String t = platformToken.trim();
    if ("fox".equalsIgnoreCase(t)) return SyncRemoteContext.SyncPlatform.FOX;
    if ("yike".equalsIgnoreCase(t)) return SyncRemoteContext.SyncPlatform.YIKE;
    return SyncRemoteContext.SyncPlatform.GENERIC;
  }

  private OptionalInt parseOptionalInt(String line, String prefix) {
    if (!line.startsWith(prefix)) {
      return OptionalInt.empty();
    }
    try {
      return OptionalInt.of(Integer.parseInt(line.substring(prefix.length()).trim()));
    } catch (NumberFormatException ex) {
      return OptionalInt.empty();
    }
  }

  private SyncRemoteContext currentPendingRemoteContext() {
    ensureTransientSyncStateInitialized();
    return pendingRemoteContext;
  }

  private void ensureTransientSyncStateInitialized() {
    if (pendingRemoteContext == null) {
      pendingRemoteContext = SyncRemoteContext.generic(false);
      awaitingFirstSyncFrame = true;
    }
  }

  private OptionalInt currentPendingFoxMoveNumber() {
    return currentPendingRemoteContext().recoveryMoveNumber();
  }

  private boolean allowsIncrementalSync(
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
      BoardHistoryNode syncStartNode,
      SyncRemoteContext remoteContext) {
    return snapshotDelta.allowsIncrementalSync()
        || allowsFoxMarkerlessSingleStep(snapshotDelta, syncStartNode, remoteContext);
  }

  private boolean allowsFoxMarkerlessSingleStep(
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
      BoardHistoryNode syncStartNode,
      SyncRemoteContext remoteContext) {
    if (snapshotDelta == null
        || syncStartNode == null
        || remoteContext == null
        || !remoteContext.supportsFoxRecovery()
        || !snapshotDelta.hasOnlyAdditions()
        || snapshotDelta.additions() != 1) {
      return false;
    }
    return remoteContext.recoveryMoveNumber().getAsInt() == syncStartNode.getData().moveNumber + 1;
  }

  private void clearPendingRemoteContext() {
    pendingRemoteContext = SyncRemoteContext.generic(false);
  }

  private boolean shouldRebuildForFoxMetadataChange(
      BoardHistoryNode syncEndNode,
      SyncRemoteContext remoteContext,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta) {
    if (remoteContext == null || !remoteContext.supportsFoxRecovery()) {
      return false;
    }
    if (!syncEndMatchesSnapshot(syncEndNode, snapshotCodes)) {
      return false;
    }
    BoardData currentData = syncEndNode.getData();
    if (currentData.isHistoryActionNode()) {
      return false;
    }
    int moveNumber = remoteContext.recoveryMoveNumber().getAsInt();
    Optional<Boolean> expectedBlackToPlay =
        expectedBlackToPlayForFoxMetadataChange(
            syncEndNode, currentData, remoteContext, snapshotDelta);
    if (!expectedBlackToPlay.isPresent()) {
      return currentData.moveNumber != moveNumber;
    }
    return currentData.moveNumber != moveNumber
        || currentData.blackToPlay != expectedBlackToPlay.get();
  }

  private Optional<Boolean> expectedBlackToPlayForFoxMetadataChange(
      BoardHistoryNode syncEndNode,
      BoardData currentData,
      SyncRemoteContext remoteContext,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta) {
    Optional<Boolean> explicitOverride = explicitPlayerOverride(currentData);
    if (explicitOverride.isPresent()) {
      return explicitOverride;
    }
    if (isTrustedFoxZeroMoveHandicapSetupTurn(
        currentData.stones,
        snapshotDelta,
        remoteContext,
        remoteContext.lastMoveSource,
        remoteContext.recoveryMoveNumber())) {
      return Optional.of(false);
    }
    if (snapshotDelta.hasMarker() && remoteContext.lastMoveSource.isTrustedVisualMarker()) {
      return Optional.of(snapshotDelta.markerColor() == Stone.WHITE);
    }
    if (isMarkerlessOrdinaryFoxTurnFallback(
        syncEndNode,
        snapshotDelta,
        remoteContext.lastMoveSource,
        remoteContext.recoveryMoveNumber())) {
      return Optional.of(remoteContext.recoveryMoveNumber().getAsInt() % 2 == 0);
    }
    return Optional.empty();
  }

  private Optional<Boolean> explicitPlayerOverride(BoardData data) {
    String player = data.getProperty("PL");
    if (player == null || player.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(!"W".equalsIgnoreCase(player));
  }

  private boolean syncEndMatchesSnapshot(BoardHistoryNode syncEndNode, int[] snapshotCodes) {
    return !snapshotDiffChecker()
        .hasDiff(snapshotCodes, syncEndNode.getData().stones, false, Optional.empty());
  }

  private CompleteSnapshotRecoveryDecision resolveCompleteSnapshotRecovery(
      BoardHistoryNode syncStartNode,
      BoardHistoryNode currentNode,
      Stone[] syncStartStones,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta) {
    SyncRemoteContext remoteContext = currentPendingRemoteContext();
    if (remoteContext.forceRebuild) {
      return CompleteSnapshotRecoveryDecision.forceRebuild("remote_force_rebuild_requested");
    }
    if (shouldForceRebuildOnResumeConflict(remoteContext)) {
      return CompleteSnapshotRecoveryDecision.forceRebuild("resume_context_conflict");
    }
    if (shouldHoldCompleteSnapshotRecoveryForPendingLocalMove(snapshotCodes)) {
      localMoveSyncDebug(
          "resolveCompleteSnapshotRecovery hold pending local move snapshot="
              + snapshotSummary(snapshotCodes)
              + " "
              + pendingLocalMoveState());
      return CompleteSnapshotRecoveryDecision.hold();
    }

    Optional<BoardHistoryNode> matchingNode =
        remoteContext != null && remoteContext.supportsFoxRecovery()
            ? rebuildPolicy()
                .findMatchingNodeInMainlineWindow(
                    currentNode, syncStartNode, snapshotCodes, remoteContext)
            : rebuildPolicy().findMatchingHistoryNode(syncStartNode, snapshotCodes, remoteContext);
    if (matchingNode.isPresent()) {
      BoardHistoryNode matchedNode = matchingNode.get();
      return CompleteSnapshotRecoveryDecision.noChange(
          matchedNode,
          matchedNode != currentNode || awaitingFirstSyncFrame,
          "snapshot_matches_existing_node");
    }

    Optional<BoardHistoryNode> adjacentMatch =
        rebuildPolicy()
            .findAdjacentMatchFromLastResolvedNode(resumeState, snapshotCodes, remoteContext);
    if (adjacentMatch.isPresent()) {
      BoardHistoryNode matchedNode = adjacentMatch.get();
      return CompleteSnapshotRecoveryDecision.noChange(
          matchedNode,
          matchedNode != currentNode || awaitingFirstSyncFrame,
          "snapshot_matches_adjacent_resolved_node");
    }

    if (snapshotDelta.hasMarker()
        && tryApplySingleMoveRecovery(syncStartNode, syncStartStones, snapshotCodes)) {
      return CompleteSnapshotRecoveryDecision.singleMoveRecovery();
    }
    if (shouldForceRebuildWithoutWaiting(syncStartNode, remoteContext)) {
      return CompleteSnapshotRecoveryDecision.forceRebuild(
          awaitingFirstSyncFrame ? "first_sync_force_rebuild" : "fallback_force_rebuild");
    }
    if (shouldHoldConflictingSnapshot(syncStartNode, snapshotCodes, remoteContext)) {
      return CompleteSnapshotRecoveryDecision.hold();
    }
    return CompleteSnapshotRecoveryDecision.forceRebuild("fallback_force_rebuild");
  }

  private boolean shouldHoldCompleteSnapshotRecoveryForPendingLocalMove(int[] snapshotCodes) {
    // Callers must run snapshot ack and pending-place timeout handling before this stale-snapshot
    // guard.
    return isPendingLocalMoveAwaitingReadBoard()
        && currentPendingLocalMoveCoordinates().isPresent();
  }

  private boolean shouldForceRebuildWithoutWaiting(
      BoardHistoryNode syncStartNode, SyncRemoteContext remoteContext) {
    if (awaitingFirstSyncFrame) {
      return remoteContext != null && remoteContext.supportsFoxRecovery();
    }
    if (syncStartNode == null || remoteContext == null || !remoteContext.supportsFoxRecovery()) {
      return false;
    }
    return remoteContext.recoveryMoveNumber().getAsInt() != syncStartNode.getData().moveNumber;
  }

  private boolean shouldHoldConflictingSnapshot(
      BoardHistoryNode syncStartNode, int[] snapshotCodes, SyncRemoteContext remoteContext) {
    if (rebuildPolicy().shouldRebuildImmediatelyWithoutHistory(syncStartNode)) {
      return false;
    }
    String conflictKey = rebuildPolicy().buildConflictKey(snapshotCodes, remoteContext);
    return conflictTracker.evaluate(conflictKey) == SyncConflictTracker.Decision.HOLD;
  }

  private boolean shouldForceRebuildOnResumeConflict(SyncRemoteContext remoteContext) {
    return remoteContext != null
        && remoteContext.supportsFoxRecovery()
        && resumeState != null
        && resumeState.remoteContext != null
        && resumeState.remoteContext.conflictsWith(remoteContext);
  }

  private void rebuildFromSnapshot(
      BoardHistoryNode syncStartNode,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta) {
    rebuildFromSnapshot(syncStartNode, snapshotCodes, snapshotDelta, currentPendingFoxMoveNumber());
  }

  private void rebuildFromSnapshot(
      BoardHistoryNode syncStartNode,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
      OptionalInt foxMoveNumber) {
    SyncRemoteContext resolvedRemoteContext = currentPendingRemoteContext().withoutForceRebuild();
    ReadBoardLastMoveSource lastMoveSource = resolvedRemoteContext.lastMoveSource;
    boolean analysisEngineAvailable = isReadBoardAnalysisEngineAvailable();
    localMoveSyncDebug(
        "rebuildFromSnapshot start engineAvailable="
            + analysisEngineAvailable
            + " syncStart="
            + historyNodeSummary(syncStartNode)
            + " snapshot="
            + snapshotSummary(snapshotCodes)
            + " foxMoveNumber="
            + (foxMoveNumber.isPresent() ? foxMoveNumber.getAsInt() : "empty")
            + " "
            + pendingLocalMoveState());
    if (failedLocalMoveRecoveryActive) {
      resetActiveSyncState(true, failedLocalMoveSuppressionActive);
    } else {
      resetActiveSyncState(true);
    }
    BoardHistoryList previousHistory = Lizzie.board.getHistory();
    RootStartSetupState preservedRootStartSetup = captureRootStartSetupState();
    BoardHistoryList rebuiltHistory =
        buildSnapshotHistory(
            previousHistory,
            syncStartNode,
            snapshotCodes,
            snapshotDelta,
            foxMoveNumber,
            resolvedRemoteContext,
            lastMoveSource);
    if (rebuildPolicy().shouldRebuildImmediatelyWithoutHistory(syncStartNode)) {
      clearBoardWithoutInvalidatingResumeState(false);
    }
    Lizzie.board.hasStartStone = false;
    Lizzie.board.startStonelist = new ArrayList<>();
    setHistoryWithoutInvalidatingResumeState(rebuiltHistory);
    restoreRootStartSetupIfNoOrRootSnapshotAnchor(syncStartNode, preservedRootStartSetup);
    BoardHistoryNode rebuiltNode = rebuiltHistory.getCurrentHistoryNode();
    updateReadBoardTurnTrustFromAcceptedFrame(
        rebuiltNode,
        snapshotCodes,
        snapshotDelta,
        resolvedRemoteContext,
        lastMoveSource,
        foxMoveNumber,
        false);
    if (analysisEngineAvailable) {
      try {
        syncEngineToRebuiltSnapshot(rebuiltNode);
      } catch (RuntimeException ex) {
        localMoveSyncDebug(
            "rebuildFromSnapshot syncEngine failed node="
                + historyNodeSummary(rebuiltNode)
                + " error="
                + ex.getClass().getSimpleName()
                + ": "
                + ex.getMessage());
        throw ex;
      }
    }
    rememberResolvedSnapshotNode(rebuiltNode, resolvedRemoteContext);
    if (analysisEngineAvailable && !continueGameAfterSyncIfNeeded("rebuild", rebuiltNode)) {
      scheduleResumeAnalysisAfterSync(rebuiltNode);
    }
    Lizzie.frame.renderVarTree(0, 0, false, false);
    Lizzie.frame.refresh();
    firstSync = false;
    localMoveSyncDebug("rebuildFromSnapshot done rebuilt=" + historyNodeSummary(rebuiltNode));
  }

  private BoardHistoryList buildSnapshotHistory(
      BoardHistoryList previousHistory,
      BoardHistoryNode syncStartNode,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta) {
    return buildSnapshotHistory(
        previousHistory,
        syncStartNode,
        snapshotCodes,
        snapshotDelta,
        currentPendingFoxMoveNumber());
  }

  private BoardHistoryList buildSnapshotHistory(
      BoardHistoryList previousHistory,
      BoardHistoryNode syncStartNode,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
      OptionalInt foxMoveNumber) {
    return buildSnapshotHistory(
        previousHistory,
        syncStartNode,
        snapshotCodes,
        snapshotDelta,
        foxMoveNumber,
        currentPendingRemoteContext(),
        currentPendingRemoteContext().lastMoveSource);
  }

  private BoardHistoryList buildSnapshotHistory(
      BoardHistoryList previousHistory,
      BoardHistoryNode syncStartNode,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
      OptionalInt foxMoveNumber,
      ReadBoardLastMoveSource lastMoveSource) {
    return buildSnapshotHistory(
        previousHistory,
        syncStartNode,
        snapshotCodes,
        snapshotDelta,
        foxMoveNumber,
        currentPendingRemoteContext(),
        lastMoveSource);
  }

  private BoardHistoryList buildSnapshotHistory(
      BoardHistoryList previousHistory,
      BoardHistoryNode syncStartNode,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
      OptionalInt foxMoveNumber,
      SyncRemoteContext remoteContext,
      ReadBoardLastMoveSource lastMoveSource) {
    BoardData snapshotData =
        buildSnapshotBoardData(
            syncStartNode,
            snapshotCodes,
            snapshotDelta,
            foxMoveNumber,
            remoteContext,
            lastMoveSource);
    BoardHistoryList rebuiltHistory = new BoardHistoryList(snapshotData);
    copySnapshotSetupMetadata(syncStartNode, rebuiltHistory.getCurrentHistoryNode());
    rebuiltHistory.setGameInfo(previousHistory.getGameInfo());
    return rebuiltHistory;
  }

  private void copySnapshotSetupMetadata(
      BoardHistoryNode syncStartNode, BoardHistoryNode rebuiltSnapshotNode) {
    BoardHistoryNode sourceSnapshotNode = findNearestSnapshotAnchor(syncStartNode);
    if (sourceSnapshotNode == null) {
      return;
    }
    BoardData rebuiltData = rebuiltSnapshotNode.getData();
    BoardData sourceData = sourceSnapshotNode.getData();
    rebuiltData.comment = sourceData.comment;
    rebuiltData.setProperties(sourceData.getProperties());
    applyExplicitSetupSnapshotSemantics(rebuiltData);
    rebuiltSnapshotNode.extraStones = cloneExtraStones(sourceSnapshotNode.extraStones);
    if (sourceSnapshotNode.hasRemovedStone()) {
      rebuiltSnapshotNode.setRemovedStone();
    }
  }

  private void applyExplicitSetupSnapshotSemantics(BoardData snapshotData) {
    String player = snapshotData.getProperty("PL");
    if (player != null && !player.isEmpty()) {
      snapshotData.blackToPlay = !"W".equalsIgnoreCase(player);
    }
    String moveNumber = snapshotData.getProperty("MN");
    if (moveNumber == null || moveNumber.isEmpty()) {
      return;
    }
    try {
      snapshotData.moveMNNumber = Integer.parseInt(moveNumber);
    } catch (NumberFormatException ex) {
      throw new IllegalStateException("Invalid MN property on rebuilt snapshot: " + moveNumber, ex);
    }
  }

  private BoardHistoryNode findNearestSnapshotAnchor(BoardHistoryNode syncStartNode) {
    BoardHistoryNode node = syncStartNode;
    while (node != null) {
      if (node.getData().isSnapshotNode()) {
        return node;
      }
      node = node.previous().orElse(null);
    }
    return null;
  }

  private ArrayList<ExtraStones> cloneExtraStones(ArrayList<ExtraStones> sourceStones) {
    if (sourceStones == null) {
      return null;
    }
    ArrayList<ExtraStones> clone = new ArrayList<>(sourceStones.size());
    for (ExtraStones sourceStone : sourceStones) {
      ExtraStones copy = new ExtraStones();
      copy.x = sourceStone.x;
      copy.y = sourceStone.y;
      copy.isBlack = sourceStone.isBlack;
      clone.add(copy);
    }
    return clone;
  }

  private RootStartSetupState captureRootStartSetupState() {
    if (!Lizzie.board.hasStartStone) {
      return RootStartSetupState.empty();
    }
    ArrayList<Movelist> startStones =
        Lizzie.board.startStonelist == null
            ? new ArrayList<>()
            : cloneStartStoneList(Lizzie.board.startStonelist);
    return new RootStartSetupState(true, startStones);
  }

  private void restoreRootStartSetupIfNoOrRootSnapshotAnchor(
      BoardHistoryNode syncStartNode, RootStartSetupState preservedRootStartSetup) {
    if (!preservedRootStartSetup.hasStartStone()) {
      return;
    }
    BoardHistoryNode snapshotAnchor = findNearestSnapshotAnchor(syncStartNode);
    if (snapshotAnchor != null && snapshotAnchor.previous().isPresent()) {
      return;
    }
    Lizzie.board.hasStartStone = true;
    Lizzie.board.startStonelist = cloneStartStoneList(preservedRootStartSetup.startStones());
  }

  private ArrayList<Movelist> cloneStartStoneList(ArrayList<Movelist> source) {
    ArrayList<Movelist> clone = new ArrayList<>(source.size());
    for (Movelist sourceMove : source) {
      Movelist copy = new Movelist();
      copy.x = sourceMove.x;
      copy.y = sourceMove.y;
      copy.isblack = sourceMove.isblack;
      copy.ispass = sourceMove.ispass;
      copy.movenum = sourceMove.movenum;
      clone.add(copy);
    }
    return clone;
  }

  private BoardData buildSnapshotBoardData(
      BoardHistoryNode syncStartNode,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta) {
    return buildSnapshotBoardData(
        syncStartNode,
        snapshotCodes,
        snapshotDelta,
        currentPendingFoxMoveNumber(),
        currentPendingRemoteContext(),
        currentPendingRemoteContext().lastMoveSource);
  }

  private BoardData buildSnapshotBoardData(
      BoardHistoryNode syncStartNode,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
      OptionalInt foxMoveNumber) {
    return buildSnapshotBoardData(
        syncStartNode,
        snapshotCodes,
        snapshotDelta,
        foxMoveNumber,
        currentPendingRemoteContext(),
        currentPendingRemoteContext().lastMoveSource);
  }

  private BoardData buildSnapshotBoardData(
      BoardHistoryNode syncStartNode,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
      OptionalInt foxMoveNumber,
      ReadBoardLastMoveSource lastMoveSource) {
    return buildSnapshotBoardData(
        syncStartNode,
        snapshotCodes,
        snapshotDelta,
        foxMoveNumber,
        currentPendingRemoteContext(),
        lastMoveSource);
  }

  private BoardData buildSnapshotBoardData(
      BoardHistoryNode syncStartNode,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
      OptionalInt foxMoveNumber,
      SyncRemoteContext remoteContext,
      ReadBoardLastMoveSource lastMoveSource) {
    Stone[] snapshotStones = buildSnapshotStones(snapshotCodes);
    SnapshotHistoryState historyState =
        inferSnapshotHistoryState(
            syncStartNode,
            snapshotStones,
            snapshotDelta,
            foxMoveNumber,
            remoteContext,
            lastMoveSource);
    int[] moveNumberList = buildSnapshotMoveNumberList(syncStartNode, snapshotStones, historyState);
    Zobrist zobrist = buildSnapshotZobrist(snapshotStones);
    SnapshotCaptures captures = inferSnapshotCaptures(syncStartNode, snapshotStones);
    return BoardData.snapshot(
        snapshotStones,
        historyState.lastMove,
        historyState.lastMoveColor,
        historyState.blackToPlay,
        zobrist,
        historyState.moveNumber,
        moveNumberList,
        captures.blackCaptures,
        captures.whiteCaptures,
        50,
        0);
  }

  private Stone[] buildSnapshotStones(int[] snapshotCodes) {
    Stone[] stones = new Stone[snapshotCodes.length];
    for (int x = 0; x < Board.boardWidth; x++) {
      for (int y = 0; y < Board.boardHeight; y++) {
        int snapshotIndex = y * Board.boardWidth + x;
        stones[Board.getIndex(x, y)] = snapshotStone(snapshotCodes[snapshotIndex]);
      }
    }
    return stones;
  }

  private Stone snapshotStone(int snapshotCode) {
    if (snapshotCode == 1 || snapshotCode == 3) {
      return Stone.BLACK;
    }
    if (snapshotCode == 2 || snapshotCode == 4) {
      return Stone.WHITE;
    }
    return Stone.EMPTY;
  }

  private Zobrist buildSnapshotZobrist(Stone[] stones) {
    Zobrist zobrist = new Zobrist();
    for (int x = 0; x < Board.boardWidth; x++) {
      for (int y = 0; y < Board.boardHeight; y++) {
        Stone stone = stones[Board.getIndex(x, y)];
        if (!stone.isEmpty()) {
          zobrist.toggleStone(x, y, stone);
        }
      }
    }
    return zobrist;
  }

  private int[] buildSnapshotMoveNumberList(
      BoardHistoryNode syncStartNode, Stone[] snapshotStones, SnapshotHistoryState historyState) {
    int[] moveNumberList = new int[Board.boardWidth * Board.boardHeight];
    copySnapshotMoveNumbersFromSyncStart(
        syncStartNode, snapshotStones, moveNumberList, historyState.moveNumber);
    historyState.lastMove.ifPresent(
        move -> moveNumberList[Board.getIndex(move[0], move[1])] = historyState.moveNumber);
    return moveNumberList;
  }

  private void copySnapshotMoveNumbersFromSyncStart(
      BoardHistoryNode syncStartNode,
      Stone[] snapshotStones,
      int[] moveNumberList,
      int maxMoveNumber) {
    if (syncStartNode == null) {
      return;
    }
    BoardData syncStartData = syncStartNode.getData();
    if (syncStartData.moveNumberList == null) {
      return;
    }
    for (int index = 0; index < snapshotStones.length; index++) {
      if (!sameStoneState(snapshotStones[index], syncStartData.stones[index])) {
        continue;
      }
      int moveNumber = syncStartData.moveNumberList[index];
      if (moveNumber > 0 && moveNumber <= maxMoveNumber) {
        moveNumberList[index] = moveNumber;
      }
    }
  }

  private boolean sameStoneState(Stone left, Stone right) {
    if (left.isEmpty() || right.isEmpty()) {
      return left.isEmpty() && right.isEmpty();
    }
    return (left.isBlack() && right.isBlack()) || (left.isWhite() && right.isWhite());
  }

  private SnapshotCaptures inferSnapshotCaptures(
      BoardHistoryNode syncStartNode, Stone[] snapshotStones) {
    if (syncStartNode == null) {
      return SnapshotCaptures.empty();
    }
    BoardData syncStartData = syncStartNode.getData();
    if (!sameStoneLayout(snapshotStones, syncStartData.stones)) {
      return SnapshotCaptures.empty();
    }
    return SnapshotCaptures.from(syncStartData);
  }

  private void updateReadBoardTurnTrustFromAcceptedFrame(
      BoardHistoryNode syncStartNode,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
      SyncRemoteContext remoteContext,
      ReadBoardLastMoveSource lastMoveSource,
      OptionalInt foxMoveNumber,
      boolean acceptedRealMove) {
    boolean hasExplicitPlayerOverride =
        syncStartNode != null && explicitPlayerOverride(syncStartNode.getData()).isPresent();
    boolean acceptedGenericRealMove =
        acceptedRealMove && (remoteContext == null || !remoteContext.supportsFoxRecovery());
    readBoardTurnTrusted =
        acceptedGenericRealMove
            || hasExplicitPlayerOverride
            || (snapshotDelta != null
                && snapshotDelta.hasMarker()
                && lastMoveSource.isTrustedVisualMarker())
            || isTrustedUnchangedSnapshotTurn(syncStartNode, snapshotDelta)
            || isTrustedEmptyBoardDefaultTurn(syncStartNode, snapshotCodes, snapshotDelta)
            || (snapshotCodes != null
                && isTrustedFoxZeroMoveHandicapSetupTurn(
                    buildSnapshotStones(snapshotCodes),
                    snapshotDelta,
                    remoteContext,
                    lastMoveSource,
                    foxMoveNumber))
            || isMarkerlessOrdinaryFoxTurnFallback(
                syncStartNode, snapshotDelta, lastMoveSource, foxMoveNumber);
  }

  private boolean isTrustedUnchangedSnapshotTurn(
      BoardHistoryNode syncStartNode, SyncSnapshotClassifier.SnapshotDelta snapshotDelta) {
    return syncStartNode != null
        && syncStartNode.getData().isSnapshotNode()
        && snapshotDelta != null
        && !snapshotDelta.hasMarker()
        && snapshotDelta.changedStones() == 0
        && !hasSetupOrHandicapTurnRisk(syncStartNode, snapshotDelta);
  }

  private boolean isTrustedEmptyBoardDefaultTurn(
      BoardHistoryNode syncStartNode,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta) {
    return snapshotCodes != null
        && snapshotDelta != null
        && !snapshotDelta.hasMarker()
        && snapshotDelta.changedStones() == 0
        && isEmptySnapshot(buildSnapshotStones(snapshotCodes))
        && !hasSetupOrHandicapTurnRisk(syncStartNode, snapshotDelta);
  }

  private boolean isTrustedFoxZeroMoveHandicapSetupTurn(
      Stone[] snapshotStones,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
      SyncRemoteContext remoteContext,
      ReadBoardLastMoveSource lastMoveSource,
      OptionalInt foxMoveNumber) {
    return remoteContext != null
        && remoteContext.platform == SyncRemoteContext.SyncPlatform.FOX
        && foxMoveNumber.isPresent()
        && foxMoveNumber.getAsInt() == 0
        && snapshotDelta != null
        && !snapshotDelta.hasMarker()
        && !lastMoveSource.isTrustedVisualMarker()
        && hasOnlyHandicapBlackSetupStones(snapshotStones);
  }

  private boolean hasOnlyHandicapBlackSetupStones(Stone[] snapshotStones) {
    int blackStones = 0;
    for (Stone stone : snapshotStones) {
      if (stone.isWhite()) {
        return false;
      }
      if (stone.isBlack()) {
        blackStones++;
      }
    }
    return blackStones >= 2;
  }

  private boolean isMarkerlessOrdinaryFoxTurnFallback(
      BoardHistoryNode syncStartNode,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
      ReadBoardLastMoveSource lastMoveSource,
      OptionalInt foxMoveNumber) {
    return snapshotDelta != null
        && !snapshotDelta.hasMarker()
        && (lastMoveSource == ReadBoardLastMoveSource.NONE
            || lastMoveSource == ReadBoardLastMoveSource.LEGACY_UNKNOWN)
        && foxMoveNumber.isPresent()
        && snapshotDelta.removals() == 0
        && snapshotDelta.additions() == 1
        && singleAdditionMatchesFoxMoveNumber(snapshotDelta, foxMoveNumber.getAsInt())
        && !hasSetupOrHandicapTurnRisk(syncStartNode, snapshotDelta);
  }

  private boolean singleAdditionMatchesFoxMoveNumber(
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta, int foxMoveNumber) {
    Stone expectedColor = foxMoveNumber % 2 == 1 ? Stone.BLACK : Stone.WHITE;
    return snapshotDelta.singleAdditionColor() == expectedColor;
  }

  private boolean hasSetupOrHandicapTurnRisk(
      BoardHistoryNode syncStartNode, SyncSnapshotClassifier.SnapshotDelta snapshotDelta) {
    if (Lizzie.board.hasStartStone
        || (Lizzie.board.startStonelist != null && !Lizzie.board.startStonelist.isEmpty())
        || (snapshotDelta != null
            && (snapshotDelta.removals() > 0 || snapshotDelta.additions() > 1))) {
      return true;
    }
    BoardHistoryNode node = syncStartNode;
    while (node != null) {
      if (node.hasRemovedStone()
          || (node.extraStones != null && !node.extraStones.isEmpty())
          || hasSetupOrHandicapProperty(node.getData())) {
        return true;
      }
      node = node.previous().orElse(null);
    }
    return false;
  }

  private boolean hasSetupOrHandicapProperty(BoardData data) {
    return data.getProperties().containsKey("AB")
        || data.getProperties().containsKey("AW")
        || data.getProperties().containsKey("AE")
        || data.getProperties().containsKey("PL")
        || data.getProperties().containsKey("HA");
  }

  private boolean sameStoneLayout(Stone[] left, Stone[] right) {
    if (left.length != right.length) {
      return false;
    }
    for (int index = 0; index < left.length; index++) {
      if (!sameStoneState(left[index], right[index])) {
        return false;
      }
    }
    return true;
  }

  private SnapshotHistoryState inferSnapshotHistoryState(
      BoardHistoryNode syncStartNode,
      Stone[] snapshotStones,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
      OptionalInt foxMoveNumber,
      SyncRemoteContext remoteContext,
      ReadBoardLastMoveSource lastMoveSource) {
    int moveNumber =
        inferSnapshotMoveNumber(syncStartNode, snapshotStones, snapshotDelta, foxMoveNumber);
    boolean blackToPlay =
        inferSnapshotBlackToPlay(
            syncStartNode,
            snapshotStones,
            snapshotDelta,
            foxMoveNumber,
            remoteContext,
            lastMoveSource);
    if (snapshotDelta.hasMarker()) {
      return SnapshotHistoryState.fromMarker(
          snapshotDelta.markerX(),
          snapshotDelta.markerY(),
          snapshotDelta.markerColor(),
          blackToPlay,
          moveNumber);
    }
    return SnapshotHistoryState.markerlessSnapshot(blackToPlay, moveNumber);
  }

  private int inferSnapshotMoveNumber(
      BoardHistoryNode syncStartNode,
      Stone[] snapshotStones,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
      OptionalInt foxMoveNumber) {
    if (foxMoveNumber.isPresent()) {
      return foxMoveNumber.getAsInt();
    }
    if (isEmptySnapshot(snapshotStones)) {
      return 0;
    }
    if (snapshotDelta.hasMarker()) {
      return inferMarkedSnapshotMoveNumber(syncStartNode, snapshotStones, snapshotDelta);
    }
    return inferMarkerlessSnapshotMoveNumber(syncStartNode, snapshotDelta);
  }

  private int inferMarkedSnapshotMoveNumber(
      BoardHistoryNode syncStartNode,
      Stone[] snapshotStones,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta) {
    if (syncStartNode != null) {
      int syncMoveNumber = syncStartNode.getData().moveNumber;
      if (snapshotDelta.changedStones() == 0) {
        return syncMoveNumber;
      }
      if (snapshotDelta.hasOnlyAdditions()) {
        return syncMoveNumber + snapshotDelta.additions();
      }
    }
    Stone markerColor = snapshotDelta.markerColor();
    int occupiedStones = countOccupiedStones(snapshotStones);
    boolean blackToPlay = markerColor == Stone.WHITE;
    if ((occupiedStones % 2 == 0) == blackToPlay) {
      return occupiedStones;
    }
    return occupiedStones + 1;
  }

  private int inferMarkerlessSnapshotMoveNumber(
      BoardHistoryNode syncStartNode, SyncSnapshotClassifier.SnapshotDelta snapshotDelta) {
    return syncStartNode == null ? 0 : syncStartNode.getData().moveNumber;
  }

  private int countOccupiedStones(Stone[] snapshotStones) {
    int occupiedStones = 0;
    for (Stone stone : snapshotStones) {
      if (!stone.isEmpty()) {
        occupiedStones++;
      }
    }
    return occupiedStones;
  }

  private boolean inferSnapshotBlackToPlay(
      BoardHistoryNode syncStartNode,
      Stone[] snapshotStones,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
      OptionalInt foxMoveNumber,
      SyncRemoteContext remoteContext,
      ReadBoardLastMoveSource lastMoveSource) {
    if (isEmptySnapshot(snapshotStones)) {
      return true;
    }
    if (isTrustedFoxZeroMoveHandicapSetupTurn(
        snapshotStones,
        snapshotDelta,
        remoteContext,
        lastMoveSource,
        foxMoveNumber)) {
      return false;
    }
    if (snapshotDelta.hasMarker() && lastMoveSource.isTrustedVisualMarker()) {
      return snapshotDelta.markerColor() == Stone.WHITE;
    }
    if (isMarkerlessOrdinaryFoxTurnFallback(
        syncStartNode, snapshotDelta, lastMoveSource, foxMoveNumber)) {
      return foxMoveNumber.getAsInt() % 2 == 0;
    }
    return inferBlackToPlayWithoutMarker(syncStartNode, snapshotStones, snapshotDelta);
  }

  private boolean inferBlackToPlayWithoutMarker(
      BoardHistoryNode syncStartNode,
      Stone[] snapshotStones,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta) {
    if (isEmptySnapshot(snapshotStones)) {
      return true;
    }
    return syncStartNode == null || syncStartNode.getData().blackToPlay;
  }

  private boolean isEmptySnapshot(Stone[] snapshotStones) {
    for (Stone stone : snapshotStones) {
      if (!stone.isEmpty()) {
        return false;
      }
    }
    return true;
  }

  private void syncEngineToRebuiltSnapshot(BoardHistoryNode rebuiltNode) {
    if (deferReadBoardGmaEngineRestoreIfPending("syncEngineToRebuiltSnapshot", rebuiltNode)) {
      return;
    }
    syncEngineToRebuiltSnapshotNow(rebuiltNode);
  }

  private void syncEngineToRebuiltSnapshotNow(BoardHistoryNode rebuiltNode) {
    if (!isReadBoardAnalysisEngineAvailable()) {
      localMoveSyncDebug(
          "syncEngineToRebuiltSnapshot skip engine unavailable node="
              + historyNodeSummary(rebuiltNode));
      return;
    }
    BoardData data = rebuiltNode.getData();
    BoardData restoreData =
        data.isSnapshotNode() ? data : SnapshotEngineRestore.snapshotFromCurrentBoard(data);
    boolean wasPondering = Lizzie.leelaz.isPondering();
    localMoveSyncDebug(
        "syncEngineToRebuiltSnapshot begin node="
            + historyNodeSummary(rebuiltNode)
            + " restoreNodeType="
            + (data.isSnapshotNode() ? "snapshot" : "move")
            + " enginePondering="
            + wasPondering);
    Lizzie.leelaz.clearWithoutPonder();
    localMoveSyncDebug("syncEngineToRebuiltSnapshot clearWithoutPonder sent");
    if (!ExactSnapshotEngineRestore.restoreIfNeeded(Lizzie.leelaz, restoreData)) {
      throw new IllegalStateException("Engine restore must sync through snapshot data.");
    }
    localMoveSyncDebug(
        "syncEngineToRebuiltSnapshot loadsgf completed node=" + historyNodeSummary(rebuiltNode));
  }

  private static final class SnapshotCaptures {
    private final int blackCaptures;
    private final int whiteCaptures;

    private SnapshotCaptures(int blackCaptures, int whiteCaptures) {
      this.blackCaptures = blackCaptures;
      this.whiteCaptures = whiteCaptures;
    }

    private static SnapshotCaptures empty() {
      return new SnapshotCaptures(0, 0);
    }

    private static SnapshotCaptures from(BoardData data) {
      return new SnapshotCaptures(data.blackCaptures, data.whiteCaptures);
    }
  }

  private static final class RootStartSetupState {
    private final boolean hasStartStone;
    private final ArrayList<Movelist> startStones;

    private RootStartSetupState(boolean hasStartStone, ArrayList<Movelist> startStones) {
      this.hasStartStone = hasStartStone;
      this.startStones = startStones;
    }

    private static RootStartSetupState empty() {
      return new RootStartSetupState(false, new ArrayList<>());
    }

    private boolean hasStartStone() {
      return hasStartStone;
    }

    private ArrayList<Movelist> startStones() {
      return startStones;
    }
  }

  private void resetActiveSyncState() {
    resetActiveSyncState(false, false, false);
  }

  private void resetActiveSyncState(boolean preserveFailedLocalMoveRecovery) {
    resetActiveSyncState(preserveFailedLocalMoveRecovery, false, false);
  }

  private void resetActiveSyncStateForReadBoardControlLine() {
    boolean preserveFailedLocalMoveState = hasFailedLocalMoveStateToPreserve();
    boolean preservePendingLocalMove = isPendingLocalMoveAwaitingReadBoard();
    boolean preserveConfirmedLocalMove = confirmedLocalMoveActive;
    resetActiveSyncState(
        preserveFailedLocalMoveState,
        preserveFailedLocalMoveState,
        preservePendingLocalMove,
        preserveConfirmedLocalMove);
  }

  private void resetActiveSyncState(
      boolean preserveFailedLocalMoveRecovery, boolean preserveFailedLocalMoveSuppression) {
    resetActiveSyncState(
        preserveFailedLocalMoveRecovery, preserveFailedLocalMoveSuppression, false);
  }

  private void resetActiveSyncState(
      boolean preserveFailedLocalMoveRecovery,
      boolean preserveFailedLocalMoveSuppression,
      boolean preservePendingLocalMoveTracking) {
    resetActiveSyncState(
        preserveFailedLocalMoveRecovery,
        preserveFailedLocalMoveSuppression,
        preservePendingLocalMoveTracking,
        false);
  }

  private void resetActiveSyncState(
      boolean preserveFailedLocalMoveRecovery,
      boolean preserveFailedLocalMoveSuppression,
      boolean preservePendingLocalMoveTracking,
      boolean preserveConfirmedLocalMove) {
    conflictTracker.clear();
    historyJumpTracker.clear();
    if (preservePendingLocalMoveTracking && isPendingLocalMoveAwaitingReadBoard()) {
      localMoveSyncDebug(
          "preserve pending local move across active sync reset " + pendingLocalMoveState());
    } else {
      clearPendingLocalMoveTracking();
    }
    if (preserveFailedLocalMoveSuppression && failedLocalMoveSuppressionActive) {
      localMoveSyncDebug(
          "preserve suppression across active sync reset " + pendingLocalMoveState());
    } else {
      clearFailedLocalMoveSuppression();
    }
    if (!preserveFailedLocalMoveRecovery) {
      clearFailedLocalMoveRecovery();
    } else if (failedLocalMoveRecoveryActive) {
      localMoveSyncDebug(
          "preserve failed local move recovery across active sync reset "
              + pendingLocalMoveState());
    }
    if (preserveConfirmedLocalMove && confirmedLocalMoveActive) {
      localMoveSyncDebug(
          "preserve confirmed local move across active sync reset " + pendingLocalMoveState());
    } else {
      clearConfirmedLocalMove();
    }
    pendingRemoteContext = SyncRemoteContext.generic(false);
    readBoardTurnTrusted = false;
    awaitingFirstSyncFrame = true;
    invalidatePendingSyncAnalysisResume();
  }

  private void clearResumeState() {
    invalidatePendingSyncAnalysisResume();
    resumeState = null;
    lastResolvedSnapshotNode = null;
    clearConfirmedLocalMove();
  }

  private synchronized void runWithSuppressedHistoryOverwriteInvalidation(Runnable action) {
    historyOverwriteSuppressionDepth++;
    try {
      action.run();
    } finally {
      historyOverwriteSuppressionDepth--;
    }
  }

  private synchronized boolean shouldSuppressHistoryOverwriteInvalidation() {
    return historyOverwriteSuppressionDepth > 0;
  }

  private void clearBoardWithoutInvalidatingResumeState(boolean isEngineGame) {
    runWithSuppressedHistoryOverwriteInvalidation(() -> Lizzie.board.clear(isEngineGame));
  }

  private void runOnEdtAndWait(Runnable action) {
    if (SwingUtilities.isEventDispatchThread()) {
      action.run();
      return;
    }
    try {
      SwingUtilities.invokeAndWait(action);
    } catch (Exception e) {
      throw new IllegalStateException("ReadBoard EDT command failed", e);
    }
  }

  private void setHistoryWithoutInvalidatingResumeState(BoardHistoryList history) {
    runWithSuppressedHistoryOverwriteInvalidation(() -> Lizzie.board.setHistory(history));
  }

  private void invalidatePendingSyncAnalysisResume() {
    syncAnalysisEpoch++;
  }

  private void moveToAnyPositionWithoutTracking(BoardHistoryNode node) {
    runWithoutTrackingLocalHistoryNavigation(() -> Lizzie.board.moveToAnyPosition(node));
  }

  private void previousMoveWithoutTracking(boolean needRefresh) {
    runWithoutTrackingLocalHistoryNavigation(() -> Lizzie.board.previousMove(needRefresh));
  }

  private void lastMoveWithoutTracking() {
    runWithoutTrackingLocalHistoryNavigation(
        () -> {
          if (Lizzie.frame != null) {
            Lizzie.frame.lastMove();
          }
        });
  }

  private void runWithoutTrackingLocalHistoryNavigation(Runnable navigation) {
    localNavigationTracker.beginReadBoardNavigation();
    try {
      navigation.run();
    } finally {
      localNavigationTracker.endReadBoardNavigation();
    }
  }

  private BoardHistoryNode resolveLocalNavigationTarget(BoardHistoryNode currentNode) {
    return localNavigationTracker.resolve(currentNode);
  }

  private boolean shouldResyncAfterIncrementalSync(Stone[] stones, int[] snapshotCodes) {
    Optional<int[]> currentPendingLocalMove = currentPendingLocalMoveCoordinates();
    return snapshotDiffChecker()
        .shouldResyncAfterIncrementalSync(
            Lizzie.config.alwaysSyncBoardStat,
            showInBoard,
            snapshotCodes,
            stones,
            shouldIgnoreCurrentLastLocalMove(currentPendingLocalMove),
            currentPendingLocalMove);
  }

  private boolean acknowledgeLocalMoveIfSnapshotCaughtUp(Stone[] stones, int[] snapshotCodes) {
    if (!Lizzie.frame.bothSync || !lastMovePlayByLizzie) {
      return false;
    }
    if (!currentPendingLocalMoveCoordinates().isPresent()) {
      localMoveSyncDebug("ack skip no pending coordinates " + pendingLocalMoveState());
      return false;
    }
    boolean pendingPresent = isCurrentPendingLocalMovePresentInSnapshot(snapshotCodes);
    boolean noDiff = !snapshotDiffChecker().hasDiff(snapshotCodes, stones, false, Optional.empty());
    localMoveSyncDebug(
        "ack check pendingPresent="
            + pendingPresent
            + " noDiffWithoutIgnore="
            + noDiff
            + " snapshot="
            + snapshotSummary(snapshotCodes)
            + " "
            + pendingLocalMoveState());
    if (pendingPresent) {
      localMoveSyncDebug("ack clear pending " + pendingLocalMoveState());
      clearPendingLocalMoveTracking();
      return true;
    } else if (noDiff) {
      localMoveSyncDebug(
          "ack keeps pending despite noDiff because pending move is not visible "
              + pendingLocalMoveState());
    }
    return false;
  }

  private void finishSyncAfterAcknowledgedLocalMoveSnapshot() {
    BoardHistoryNode acknowledgedNode = Lizzie.board.getHistory().getMainEnd();
    clearConfirmedLocalMove();
    rememberResolvedSnapshotNode(acknowledgedNode);
    conflictTracker.clear();
    historyJumpTracker.clear();
    awaitingFirstSyncFrame = false;
    invalidatePendingSyncAnalysisResume();
    localMoveSyncDebug(
        "syncBoardStones ack caught up; skip stale recovery target="
            + historyNodeSummary(acknowledgedNode)
            + " "
            + pendingLocalMoveState());
    if (!continueGameAfterSyncIfNeeded("ack", acknowledgedNode)) {
      scheduleResumeAnalysisAfterSync(acknowledgedNode);
    }
  }

  private void finishSyncAfterConfirmedLocalMoveCommand(BoardHistoryNode confirmedNode) {
    if (confirmedNode == null) {
      return;
    }
    rememberResolvedSnapshotNode(confirmedNode);
    conflictTracker.clear();
    historyJumpTracker.clear();
    awaitingFirstSyncFrame = false;
    invalidatePendingSyncAnalysisResume();
    localMoveSyncDebug(
        "placeComplete confirmed local move; protect against stale recovery target="
            + historyNodeSummary(confirmedNode)
            + " "
            + pendingLocalMoveState());
    if (!continueGameAfterSyncIfNeeded("placeComplete", confirmedNode)) {
      scheduleResumeAnalysisAfterSync(confirmedNode);
    }
  }

  private void startTrackingLocalMoveFromLizzie() {
    clearConfirmedLocalMove();
    capturePendingLocalMoveRecord(currentMainEndNode());
    lastMovePlayByLizzie = true;
    waitingForReadBoardLocalMoveAck = true;
    pendingLocalMoveClickCompleted = false;
    pendingLocalMoveRetryCount = 0;
    ignoreReadBoardPlaceResultsForCurrentPending = ignoreReadBoardPlaceResultsForNextPending;
    ignoreReadBoardPlaceResultsForNextPending = false;
    lastPendingLocalMoveRetryTimeMs = System.currentTimeMillis();
    pendingLocalMoveAckGeneration++;
    localMoveSyncDebug("startTrackingLocalMoveFromLizzie " + pendingLocalMoveState());
    schedulePendingLocalMoveAckTimeout();
  }

  private void schedulePendingLocalMoveAckTimeout() {
    ScheduledExecutorService timeoutExecutor = ensurePendingLocalMoveTimeoutExecutor();
    if (timeoutExecutor == null || timeoutExecutor.isShutdown()) {
      return;
    }
    final long generation = pendingLocalMoveAckGeneration;
    final BoardHistoryNode node = pendingLocalMoveNode;
    final int x = pendingLocalMoveX;
    final int y = pendingLocalMoveY;
    final Stone color = pendingLocalMoveColor;
    timeoutExecutor.schedule(
        () -> failPendingLocalMoveIfAckTimedOutWithoutSnapshot(generation, node, x, y, color),
        PENDING_LOCAL_MOVE_ACK_TIMEOUT_MS,
        TimeUnit.MILLISECONDS);
  }

  private ScheduledExecutorService ensurePendingLocalMoveTimeoutExecutor() {
    if (shutdownStarted || System.getProperty("surefire.test.class.path") != null) {
      return null;
    }
    if (pendingLocalMoveTimeoutExecutor == null || pendingLocalMoveTimeoutExecutor.isShutdown()) {
      pendingLocalMoveTimeoutExecutor =
          Executors.newSingleThreadScheduledExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, "readboard-local-move-timeout");
                thread.setDaemon(true);
                return thread;
              });
    }
    return pendingLocalMoveTimeoutExecutor;
  }

  private void markLocalMoveCommandCompleted() {
    if (!isPendingLocalMoveAwaitingReadBoard()) {
      pendingLocalMoveClickCompleted = false;
      localMoveSyncDebug(
          "markLocalMoveCommandCompleted ignored no pending " + pendingLocalMoveState());
      return;
    }
    if (ignoreReadBoardPlaceResultsForCurrentPending) {
      localMoveSyncDebug(
          "markLocalMoveCommandCompleted ignored by stale-result quarantine "
              + pendingLocalMoveState());
      return;
    }
    pendingLocalMoveClickCompleted = true;
    localMoveSyncDebug("markLocalMoveCommandCompleted " + pendingLocalMoveState());
    localMoveSyncDebug(
        "placeComplete noted pending local move; waiting for remote snapshot "
            + pendingLocalMoveState());
  }

  private void clearFailedLocalMoveStateIfCurrentMoveConfirmed() {
    if (!hasFailedLocalMoveStateToPreserve()
        || Lizzie.board == null
        || Lizzie.board.getHistory() == null) {
      return;
    }
    BoardHistoryNode mainEnd = Lizzie.board.getHistory().getMainEnd();
    if (matchesFailedLocalMove(mainEnd)) {
      localMoveSyncDebug(
          "placeComplete clears failed local move state node="
              + historyNodeSummary(mainEnd)
              + " "
              + pendingLocalMoveState());
      clearFailedLocalMoveSuppression();
      clearFailedLocalMoveRecovery();
    }
  }

  private void clearPendingLocalMoveTracking() {
    localMoveSyncDebug("clearPendingLocalMoveTracking before " + pendingLocalMoveState());
    boolean wasPending = isPendingLocalMoveAwaitingReadBoard();
    lastMovePlayByLizzie = false;
    waitingForReadBoardLocalMoveAck = false;
    pendingLocalMoveClickCompleted = false;
    pendingLocalMoveRetryCount = 0;
    lastPendingLocalMoveRetryTimeMs = 0L;
    pendingLocalMoveNode = null;
    pendingLocalMoveX = -1;
    pendingLocalMoveY = -1;
    pendingLocalMoveColor = Stone.EMPTY;
    pendingLocalMoveBaselineKey = "";
    ignoreReadBoardPlaceResultsForCurrentPending = false;
    if (wasPending) {
      pendingLocalMoveAckGeneration++;
      ignoreReadBoardPlaceResultsForNextPending = true;
    }
    localMoveSyncDebug("clearPendingLocalMoveTracking after " + pendingLocalMoveState());
  }

  public boolean isPendingLocalMoveAwaitingReadBoard() {
    return lastMovePlayByLizzie && waitingForReadBoardLocalMoveAck;
  }

  private boolean shouldIgnoreCurrentLastLocalMove() {
    return Lizzie.frame.bothSync && lastMovePlayByLizzie && waitingForReadBoardLocalMoveAck;
  }

  private boolean shouldIgnoreCurrentLastLocalMove(Optional<int[]> currentPendingLocalMove) {
    return shouldIgnoreCurrentLastLocalMove() && currentPendingLocalMove.isPresent();
  }

  private void capturePendingLocalMoveRecord(BoardHistoryNode node) {
    pendingLocalMoveNode = null;
    pendingLocalMoveX = -1;
    pendingLocalMoveY = -1;
    pendingLocalMoveColor = Stone.EMPTY;
    pendingLocalMoveBaselineKey = "";
    if (node == null || node.getData() == null) {
      localMoveSyncDebug("capture pending local move skipped missing node");
      return;
    }
    BoardData data = node.getData();
    if (!data.isMoveNode()
        || !data.lastMove.isPresent()
        || data.lastMoveColor == null
        || data.lastMoveColor.isEmpty()) {
      localMoveSyncDebug(
          "capture pending local move skipped non-move node=" + historyNodeSummary(node));
      return;
    }
    int[] coords = data.lastMove.get();
    pendingLocalMoveNode = node;
    pendingLocalMoveX = coords[0];
    pendingLocalMoveY = coords[1];
    pendingLocalMoveColor = data.lastMoveColor;
    Optional<BoardHistoryNode> previousNode = node.previous();
    pendingLocalMoveBaselineKey =
        previousNode.isPresent()
            ? snapshotPositionKey(snapshotCodesForNode(previousNode.get()))
            : "";
    localMoveSyncDebug(
        "capture pending local move coords="
            + coordsText(coords)
            + " color="
            + pendingLocalMoveColor
            + " baselineKey="
            + pendingLocalMoveBaselineKey
            + " node="
            + historyNodeSummary(node));
  }

  private boolean hasPendingLocalMoveRecord() {
    return pendingLocalMoveNode != null
        && pendingLocalMoveX >= 0
        && pendingLocalMoveY >= 0
        && pendingLocalMoveColor != null
        && !pendingLocalMoveColor.isEmpty();
  }

  private Optional<int[]> currentPendingLocalMoveCoordinates() {
    if (!hasPendingLocalMoveRecord()) {
      return Optional.empty();
    }
    return Optional.of(new int[] {pendingLocalMoveX, pendingLocalMoveY});
  }

  private BoardHistoryNode currentMainEndNode() {
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      return null;
    }
    return Lizzie.board.getHistory().getMainEnd();
  }

  private boolean isCurrentPendingLocalMovePresentInSnapshot(int[] snapshotCodes) {
    Optional<int[]> currentPendingLocalMove = currentPendingLocalMoveCoordinates();
    if (!currentPendingLocalMove.isPresent()) {
      return false;
    }
    return snapshotContainsMove(
        snapshotCodes, currentPendingLocalMove.get(), pendingLocalMoveColor);
  }

  private boolean snapshotContainsMove(int[] snapshotCodes, int[] coords, Stone color) {
    if (coords == null || coords.length < 2 || color == null || color.isEmpty()) {
      return false;
    }
    int index = coords[1] * Board.boardWidth + coords[0];
    if (index < 0 || index >= snapshotCodes.length) {
      return false;
    }
    int code = snapshotCodes[index];
    return color.isBlack() ? code == 1 || code == 3 : code == 2 || code == 4;
  }

  private void rememberConfirmedLocalMove(BoardHistoryNode node) {
    if (node == null || node.getData() == null) {
      clearConfirmedLocalMove();
      return;
    }
    BoardData data = node.getData();
    if (!data.isMoveNode()
        || !data.lastMove.isPresent()
        || data.lastMoveColor == null
        || data.lastMoveColor.isEmpty()) {
      clearConfirmedLocalMove();
      return;
    }
    int[] coords = data.lastMove.get();
    confirmedLocalMoveActive = true;
    confirmedLocalMoveNode = node;
    confirmedLocalMoveX = coords[0];
    confirmedLocalMoveY = coords[1];
    confirmedLocalMoveColor = data.lastMoveColor;
    Optional<BoardHistoryNode> previousNode = node.previous();
    confirmedLocalMoveBaselineKey =
        previousNode.isPresent()
            ? snapshotPositionKey(snapshotCodesForNode(previousNode.get()))
            : "";
    localMoveSyncDebug(
        "remember confirmed local move coords="
            + coordsText(coords)
            + " color="
            + confirmedLocalMoveColor
            + " baselineKey="
            + confirmedLocalMoveBaselineKey
            + " node="
            + historyNodeSummary(node));
  }

  private void clearConfirmedLocalMove() {
    if (confirmedLocalMoveActive) {
      localMoveSyncDebug("clear confirmed local move before " + pendingLocalMoveState());
    }
    confirmedLocalMoveActive = false;
    confirmedLocalMoveNode = null;
    confirmedLocalMoveX = -1;
    confirmedLocalMoveY = -1;
    confirmedLocalMoveColor = Stone.EMPTY;
    confirmedLocalMoveBaselineKey = "";
  }

  private void updateConfirmedLocalMoveForSnapshot(int[] snapshotCodes) {
    if (!confirmedLocalMoveActive) {
      return;
    }
    if (snapshotContainsConfirmedLocalMove(snapshotCodes)) {
      localMoveSyncDebug(
          "confirmed local move reached remote snapshot snapshot="
              + snapshotSummary(snapshotCodes)
              + " "
              + pendingLocalMoveState());
      clearConfirmedLocalMove();
      return;
    }
    if (!isConfirmedLocalMoveBaselineSnapshot(snapshotCodes)) {
      localMoveSyncDebug(
          "confirmed local move snapshot advanced without target; releasing stale guard snapshot="
              + snapshotSummary(snapshotCodes)
              + " "
              + pendingLocalMoveState());
      clearConfirmedLocalMove();
    }
  }

  private boolean shouldHoldSyncForConfirmedLocalMove(int[] snapshotCodes) {
    return confirmedLocalMoveActive
        && !snapshotContainsConfirmedLocalMove(snapshotCodes)
        && isConfirmedLocalMoveBaselineSnapshot(snapshotCodes);
  }

  private boolean snapshotContainsConfirmedLocalMove(int[] snapshotCodes) {
    return snapshotContainsMove(
        snapshotCodes,
        new int[] {confirmedLocalMoveX, confirmedLocalMoveY},
        confirmedLocalMoveColor);
  }

  private boolean isConfirmedLocalMoveBaselineSnapshot(int[] snapshotCodes) {
    return confirmedLocalMoveBaselineKey != null
        && !confirmedLocalMoveBaselineKey.isEmpty()
        && confirmedLocalMoveBaselineKey.equals(snapshotPositionKey(snapshotCodes));
  }

  private boolean retryPendingLocalMoveIfSnapshotStillMissing(int[] snapshotCodes) {
    if (!Lizzie.frame.bothSync || !lastMovePlayByLizzie) {
      return false;
    }
    if (!waitingForReadBoardLocalMoveAck) {
      localMoveSyncDebug("retry skip not waiting " + pendingLocalMoveState());
      return false;
    }
    boolean pendingPresent = isCurrentPendingLocalMovePresentInSnapshot(snapshotCodes);
    if (failPendingLocalMoveIfRemoteChangedWithoutTarget(snapshotCodes, pendingPresent)) {
      return true;
    }
    if (failPendingLocalMoveIfAckTimedOut(snapshotCodes, pendingPresent)) {
      return true;
    }
    if (pendingLocalMoveRetryCount >= LOCAL_MOVE_PLACE_RETRY_LIMIT || pendingPresent) {
      localMoveSyncDebug(
          "retry skip limit-or-present pendingPresent="
              + pendingPresent
              + " snapshot="
              + snapshotSummary(snapshotCodes)
              + " "
              + pendingLocalMoveState());
      return false;
    }
    Optional<int[]> currentPendingLocalMove = currentPendingLocalMoveCoordinates();
    if (!currentPendingLocalMove.isPresent()) {
      localMoveSyncDebug("retry skip no pending coordinates " + pendingLocalMoveState());
      return false;
    }
    long now = System.currentTimeMillis();
    if (!pendingLocalMoveClickCompleted
        && now - lastPendingLocalMoveRetryTimeMs < LOCAL_MOVE_PLACE_RETRY_INTERVAL_MS) {
      localMoveSyncDebug(
          "retry wait no placeComplete elapsedMs="
              + (now - lastPendingLocalMoveRetryTimeMs)
              + " "
              + pendingLocalMoveState());
      return false;
    }
    if (now - lastPendingLocalMoveRetryTimeMs < LOCAL_MOVE_PLACE_RETRY_INTERVAL_MS) {
      localMoveSyncDebug(
          "retry wait interval elapsedMs="
              + (now - lastPendingLocalMoveRetryTimeMs)
              + " "
              + pendingLocalMoveState());
      return false;
    }
    int[] coords = currentPendingLocalMove.get();
    pendingLocalMoveRetryCount++;
    pendingLocalMoveClickCompleted = false;
    lastPendingLocalMoveRetryTimeMs = now;
    localMoveSyncDebug(
        "retry sending place coords="
            + coordsText(coords)
            + " snapshot="
            + snapshotSummary(snapshotCodes)
            + " "
            + pendingLocalMoveState());
    sendPlaceCommandWithoutRestartingTracking(coords[0], coords[1]);
    return false;
  }

  private boolean failPendingLocalMoveIfRemoteChangedWithoutTarget(
      int[] snapshotCodes, boolean pendingPresent) {
    if (pendingPresent || !isPendingLocalMoveAwaitingReadBoard()) {
      return false;
    }
    if (pendingLocalMoveBaselineKey == null || pendingLocalMoveBaselineKey.isEmpty()) {
      return false;
    }
    String snapshotKey = snapshotPositionKey(snapshotCodes);
    if (snapshotKey.isEmpty() || pendingLocalMoveBaselineKey.equals(snapshotKey)) {
      return false;
    }
    localMoveSyncDebug(
        "pending local move remote changed without target; treating as misplaced/failed move snapshot="
            + snapshotSummary(snapshotCodes)
            + " "
            + pendingLocalMoveState());
    return handlePendingLocalMovePlacementFailure(
        "pending local move remote changed without target");
  }

  private boolean failPendingLocalMoveIfAckTimedOut(int[] snapshotCodes, boolean pendingPresent) {
    if (pendingPresent || !isPendingLocalMoveAwaitingReadBoard()) {
      return false;
    }
    Optional<int[]> currentPendingLocalMove = currentPendingLocalMoveCoordinates();
    if (!currentPendingLocalMove.isPresent()) {
      localMoveSyncDebug(
          "pending local move ack timeout skip no coordinates " + pendingLocalMoveState());
      return false;
    }
    long now = System.currentTimeMillis();
    long elapsedMs =
        lastPendingLocalMoveRetryTimeMs == 0L ? 0L : now - lastPendingLocalMoveRetryTimeMs;
    if (lastPendingLocalMoveRetryTimeMs == 0L || elapsedMs < PENDING_LOCAL_MOVE_ACK_TIMEOUT_MS) {
      localMoveSyncDebug(
          "pending local move waits for place result elapsedMs="
              + elapsedMs
              + " timeoutMs="
              + PENDING_LOCAL_MOVE_ACK_TIMEOUT_MS
              + " snapshot="
              + snapshotSummary(snapshotCodes)
              + " "
              + pendingLocalMoveState());
      return false;
    }
    localMoveSyncDebug(
        "pending local move timed out without place result elapsedMs="
            + elapsedMs
            + " timeoutMs="
            + PENDING_LOCAL_MOVE_ACK_TIMEOUT_MS
            + " snapshot="
            + snapshotSummary(snapshotCodes)
            + " "
            + pendingLocalMoveState());
    return handlePendingLocalMovePlacementFailure(
        "pending local move timed out without place result elapsedMs=" + elapsedMs);
  }

  private boolean failPendingLocalMoveIfAckTimedOutWithoutSnapshot() {
    return failPendingLocalMoveIfAckTimedOutWithoutSnapshot(
        pendingLocalMoveAckGeneration,
        pendingLocalMoveNode,
        pendingLocalMoveX,
        pendingLocalMoveY,
        pendingLocalMoveColor);
  }

  private boolean failPendingLocalMoveIfAckTimedOutWithoutSnapshot(
      long generation, BoardHistoryNode node, int x, int y, Stone color) {
    if (!isPendingLocalMoveAwaitingReadBoard()
        || generation != pendingLocalMoveAckGeneration
        || !samePendingLocalMove(node, x, y, color)) {
      localMoveSyncDebug(
          "pending local move timer skip stale generation="
              + generation
              + " currentGeneration="
              + pendingLocalMoveAckGeneration
              + " "
              + pendingLocalMoveState());
      return false;
    }
    long now = System.currentTimeMillis();
    long elapsedMs =
        lastPendingLocalMoveRetryTimeMs == 0L ? 0L : now - lastPendingLocalMoveRetryTimeMs;
    if (lastPendingLocalMoveRetryTimeMs == 0L || elapsedMs < PENDING_LOCAL_MOVE_ACK_TIMEOUT_MS) {
      localMoveSyncDebug(
          "pending local move timer skip before timeout elapsedMs="
              + elapsedMs
              + " timeoutMs="
              + PENDING_LOCAL_MOVE_ACK_TIMEOUT_MS
              + " "
              + pendingLocalMoveState());
      return false;
    }
    localMoveSyncDebug(
        "pending local move timer timed out without sync frame elapsedMs="
            + elapsedMs
            + " timeoutMs="
            + PENDING_LOCAL_MOVE_ACK_TIMEOUT_MS
            + " "
            + pendingLocalMoveState());
    return handlePendingLocalMovePlacementFailure(
        "pending local move timed out without sync frame elapsedMs=" + elapsedMs);
  }

  private boolean samePendingLocalMove(BoardHistoryNode node, int x, int y, Stone color) {
    return pendingLocalMoveNode == node
        && pendingLocalMoveX == x
        && pendingLocalMoveY == y
        && pendingLocalMoveColor == color;
  }

  public boolean shouldSuppressLocalPlaceAfterFailedSync(int x, int y, Stone color) {
    if (!failedLocalMoveSuppressionActive || color == null) {
      return false;
    }
    if (failedLocalMoveAwaitingRemoteObservation) {
      if (releaseFailedLocalMoveObservationIfTimedOut("local-place")) {
        return false;
      }
      YikeSyncDebugLog.log(
          "ReadBoard suppress local place while awaiting failed-place observation x="
              + x
              + " y="
              + y
              + " color="
              + color);
      localMoveSyncDebug(
          "suppress local place while awaiting failed-place observation x="
              + x
              + " y="
              + y
              + " color="
              + color
              + " "
              + pendingLocalMoveState());
      return true;
    }
    if (failedLocalMoveWaitingForOurTurnAfterRemoteChange) {
      YikeSyncDebugLog.log(
          "ReadBoard suppress local place while waiting for failed-place side turn x="
              + x
              + " y="
              + y
              + " color="
              + color);
      localMoveSyncDebug(
          "suppress local place while waiting for failed-place side turn x="
              + x
              + " y="
              + y
              + " color="
              + color
              + " "
              + pendingLocalMoveState());
      return true;
    }
    boolean sameFailedMove =
        x == failedLocalMoveSuppressionX
            && y == failedLocalMoveSuppressionY
            && color == failedLocalMoveSuppressionColor;
    if (!sameFailedMove) {
      localMoveSyncDebug(
          "clear failed local move state because engine selected a different move x="
              + x
              + " y="
              + y
              + " color="
              + color
              + " "
              + pendingLocalMoveState());
      clearFailedLocalMoveSuppression();
      clearFailedLocalMoveRecovery();
      return false;
    }
    YikeSyncDebugLog.log(
        "ReadBoard suppress repeated failed local place x=" + x + " y=" + y + " color=" + color);
    localMoveSyncDebug(
        "suppress repeated failed local place x="
            + x
            + " y="
            + y
            + " color="
            + color
            + " "
            + pendingLocalMoveState());
    return true;
  }

  private void rememberFailedLocalMoveSuppression() {
    rememberFailedLocalMoveSuppression(null);
  }

  private void rememberFailedLocalMoveSuppression(int[] snapshotCodes) {
    Optional<int[]> currentPendingLocalMove = currentPendingLocalMoveCoordinates();
    if (!currentPendingLocalMove.isPresent()) {
      clearFailedLocalMoveSuppression();
      clearFailedLocalMoveRecovery();
      return;
    }
    Stone color = pendingLocalMoveColor;
    if (color == null || color.isEmpty()) {
      clearFailedLocalMoveSuppression();
      clearFailedLocalMoveRecovery();
      return;
    }
    int[] coords = currentPendingLocalMove.get();
    failedLocalMoveSuppressionActive = true;
    failedLocalMoveSuppressionX = coords[0];
    failedLocalMoveSuppressionY = coords[1];
    failedLocalMoveSuppressionColor = color;
    failedLocalMoveSuppressionSnapshotKey = snapshotPositionKey(snapshotCodes);
    rememberFailedLocalMoveRecovery(coords, color);
    localMoveSyncDebug(
        "remember suppression coords="
            + coordsText(coords)
            + " color="
            + color
            + " snapshot="
            + snapshotSummary(snapshotCodes));
  }

  private void bindFailedLocalMoveSuppressionBaseline(BoardHistoryNode baselineNode) {
    if (!failedLocalMoveSuppressionActive
        || baselineNode == null
        || baselineNode.getData() == null) {
      return;
    }
    int[] baselineSnapshotCodes = snapshotCodesForNode(baselineNode);
    failedLocalMoveSuppressionSnapshotKey = snapshotPositionKey(baselineSnapshotCodes);
    localMoveSyncDebug(
        "bind suppression rollback baseline="
            + snapshotSummary(baselineSnapshotCodes)
            + " "
            + pendingLocalMoveState());
  }

  private void updateFailedLocalMoveSuppressionForSnapshot(int[] snapshotCodes) {
    if (!failedLocalMoveSuppressionActive || snapshotCodes == null || snapshotCodes.length == 0) {
      return;
    }
    int[] coords = new int[] {failedLocalMoveSuppressionX, failedLocalMoveSuppressionY};
    String snapshotKey = snapshotPositionKey(snapshotCodes);
    if (failedLocalMoveAwaitingRemoteObservation) {
      observeFailedLocalMoveSnapshot(snapshotCodes, coords, snapshotKey);
      return;
    }
    if (failedLocalMoveWaitingForOurTurnAfterRemoteChange) {
      localMoveSyncDebug(
          "failed-place waiting for our turn keeps suppression snapshot="
              + snapshotSummary(snapshotCodes)
              + " "
              + pendingLocalMoveState());
      return;
    }
    if (snapshotContainsMove(snapshotCodes, coords, failedLocalMoveSuppressionColor)) {
      localMoveSyncDebug(
          "clear suppression because failed move is now visible snapshot="
              + snapshotSummary(snapshotCodes)
              + " "
              + pendingLocalMoveState());
      clearFailedLocalMoveSuppression();
      clearFailedLocalMoveRecovery();
      return;
    }
    if (failedLocalMoveSuppressionSnapshotKey.isEmpty()) {
      failedLocalMoveSuppressionSnapshotKey = snapshotKey;
      localMoveSyncDebug(
          "bind suppression snapshot="
              + snapshotSummary(snapshotCodes)
              + " "
              + pendingLocalMoveState());
      return;
    }
    if (!failedLocalMoveSuppressionSnapshotKey.equals(snapshotKey)) {
      localMoveSyncDebug(
          "clear suppression due snapshot change snapshot="
              + snapshotSummary(snapshotCodes)
              + " "
              + pendingLocalMoveState());
      clearFailedLocalMoveSuppression();
      clearFailedLocalMoveRecovery();
    }
  }

  private void observeFailedLocalMoveSnapshot(
      int[] snapshotCodes, int[] failedCoords, String snapshotKey) {
    if (snapshotContainsMove(snapshotCodes, failedCoords, failedLocalMoveSuppressionColor)) {
      localMoveSyncDebug(
          "failed-place observation sees intended move visible; treating failure as stale snapshot="
              + snapshotSummary(snapshotCodes)
              + " "
              + pendingLocalMoveState());
      clearFailedLocalMoveSuppression();
      clearFailedLocalMoveRecovery();
      return;
    }
    if (failedLocalMoveSuppressionSnapshotKey.isEmpty()) {
      failedLocalMoveSuppressionSnapshotKey = snapshotKey;
      localMoveSyncDebug(
          "failed-place observation binds first snapshot="
              + snapshotSummary(snapshotCodes)
              + " "
              + pendingLocalMoveState());
      return;
    }
    if (failedLocalMoveSuppressionSnapshotKey.equals(snapshotKey)) {
      localMoveSyncDebug(
          "failed-place observation sees no remote board change; releasing failed move for fresh analysis snapshot="
              + snapshotSummary(snapshotCodes)
              + " "
              + pendingLocalMoveState());
      clearFailedLocalMoveSuppression();
      clearFailedLocalMoveRecovery();
      return;
    }
    failedLocalMoveAwaitingRemoteObservation = false;
    failedLocalMoveWaitingForOurTurnAfterRemoteChange = true;
    failedLocalMoveObservationDeadlineMs = 0L;
    localMoveSyncDebug(
        "failed-place observation sees remote board changed; resolving turn from sync snapshot="
            + snapshotSummary(snapshotCodes)
            + " "
            + pendingLocalMoveState());
  }

  private void clearFailedLocalMoveSuppression() {
    if (failedLocalMoveSuppressionActive) {
      localMoveSyncDebug("clear suppression before " + pendingLocalMoveState());
    }
    failedLocalMoveSuppressionActive = false;
    failedLocalMoveSuppressionX = -1;
    failedLocalMoveSuppressionY = -1;
    failedLocalMoveSuppressionColor = Stone.EMPTY;
    failedLocalMoveSuppressionSnapshotKey = "";
  }

  private void rememberFailedLocalMoveRecovery(int[] coords, Stone color) {
    failedLocalMoveRecoveryActive = true;
    failedLocalMoveRecoveryX = coords[0];
    failedLocalMoveRecoveryY = coords[1];
    failedLocalMoveRecoveryColor = color;
    failedLocalMoveAwaitingRemoteObservation = false;
    failedLocalMoveWaitingForOurTurnAfterRemoteChange = false;
    failedLocalMoveObservationDeadlineMs = 0L;
    localMoveSyncDebug(
        "remember failed local move recovery coords="
            + coordsText(coords)
            + " color="
            + color
            + " "
            + pendingLocalMoveState());
  }

  private void beginFailedLocalMoveObservationGuard() {
    failedLocalMoveAwaitingRemoteObservation = true;
    failedLocalMoveWaitingForOurTurnAfterRemoteChange = false;
    failedLocalMoveObservationDeadlineMs =
        System.currentTimeMillis() + FAILED_LOCAL_MOVE_OBSERVATION_GRACE_MS;
    localMoveSyncDebug(
        "begin failed-place observation guard graceMs="
            + FAILED_LOCAL_MOVE_OBSERVATION_GRACE_MS
            + " "
            + pendingLocalMoveState());
  }

  private boolean releaseFailedLocalMoveObservationIfTimedOut(String reason) {
    if (!failedLocalMoveAwaitingRemoteObservation) {
      return false;
    }
    long deadlineMs = failedLocalMoveObservationDeadlineMs;
    if (deadlineMs > 0L && System.currentTimeMillis() < deadlineMs) {
      return false;
    }
    localMoveSyncDebug(
        "failed-place observation guard elapsed; release for fresh analysis reason="
            + reason
            + " "
            + pendingLocalMoveState());
    clearFailedLocalMoveSuppression();
    clearFailedLocalMoveRecovery();
    return true;
  }

  private void clearFailedLocalMoveRecovery() {
    if (failedLocalMoveRecoveryActive) {
      localMoveSyncDebug("clear failed local move recovery before " + pendingLocalMoveState());
    }
    failedLocalMoveRecoveryActive = false;
    failedLocalMoveRecoveryX = -1;
    failedLocalMoveRecoveryY = -1;
    failedLocalMoveRecoveryColor = Stone.EMPTY;
    failedLocalMoveAwaitingRemoteObservation = false;
    failedLocalMoveWaitingForOurTurnAfterRemoteChange = false;
    failedLocalMoveObservationDeadlineMs = 0L;
  }

  private boolean hasFailedLocalMoveStateToPreserve() {
    return failedLocalMoveRecoveryActive;
  }

  private boolean isReadBoardGmaPlayMode(String[] playParams) {
    return playParams != null
        && playParams.length >= 4
        && READBOARD_GMA_TOKEN.equals(playParams[3].trim());
  }

  public boolean isReadBoardGmaAutoPlayActive() {
    return readBoardGmaAutoPlayActive;
  }

  public boolean isReadBoardGmaEngineBusy() {
    return readBoardGmaAutoPlayActive
        || readBoardGmaPending
        || readBoardGmaEngineRestorePending
        || readBoardGmaEngineRestoreInProgress;
  }

  public void onReadBoardGmaCapabilityReady() {
    scheduleReadBoardGmaIfNeeded("capability-ready");
  }

  private void clearReadBoardGmaAutoPlay(String reason) {
    if (readBoardGmaAutoPlayActive || readBoardGmaPending) {
      localMoveSyncDebug(
          "clear ReadBoard GMA autoplay reason="
              + reason
              + " active="
              + readBoardGmaAutoPlayActive
              + " pending="
              + readBoardGmaPending);
    }
    boolean pendingPhysicalRequest = readBoardGmaPending;
    readBoardGmaAutoPlayActive = false;
    readBoardGmaAwaitingSyncedBoard = false;
    readBoardGmaAutoPlayColor = Stone.EMPTY;
    readBoardGmaTimeSeconds = 0;
    readBoardGmaMaxVisits = 0;
    if (pendingPhysicalRequest) {
      invalidateReadBoardGmaPhysicalRequestIfPending(reason);
      return;
    }
    if (Lizzie.leelaz != null) {
      Lizzie.leelaz.restoreReadBoardGmaRuntimeSettingsIfNeeded();
    }
  }

  private void disableReadBoardGmaPonderIfIdle() {
    if (!readBoardGmaAutoPlayActive
        || readBoardGmaPending
        || Lizzie.leelaz == null
        || !Lizzie.leelaz.supportsReadBoardGma()) {
      return;
    }
    Lizzie.leelaz.setReadBoardGmaPondering(false);
    stopPonderingIfActive();
  }

  private void invalidateReadBoardGmaPhysicalRequestIfPending(String reason) {
    if (!readBoardGmaPending) {
      if (Lizzie.leelaz != null && !readBoardGmaAutoPlayActive) {
        Lizzie.leelaz.restoreReadBoardGmaRuntimeSettingsIfNeeded();
      }
      return;
    }
    readBoardGmaPendingLogicallyInvalid = true;
    requestReadBoardGmaEngineRestore(reason);
  }

  private void requestReadBoardGmaEngineRestore(String reason) {
    BoardHistoryNode currentNode = null;
    if (Lizzie.board != null && Lizzie.board.getHistory() != null) {
      currentNode = Lizzie.board.getHistory().getCurrentHistoryNode();
    }
    requestReadBoardGmaEngineRestore(reason, currentNode);
  }

  private void requestReadBoardGmaEngineRestore(String reason, BoardHistoryNode restoreNode) {
    requestReadBoardGmaEngineRestore(reason, restoreNode, true);
  }

  private void requestReadBoardGmaEngineRestore(
      String reason, BoardHistoryNode restoreNode, boolean flushIfReady) {
    if (restoreNode == null) {
      if (!readBoardGmaPending && Lizzie.leelaz != null && !readBoardGmaAutoPlayActive) {
        Lizzie.leelaz.restoreReadBoardGmaRuntimeSettingsIfNeeded();
      }
      return;
    }
    if (readBoardGmaPending) {
      readBoardGmaPendingLogicallyInvalid = true;
    }
    readBoardGmaEngineRestorePending = true;
    readBoardGmaDeferredRestoreNode = restoreNode;
    localMoveSyncDebug(
        "ReadBoard GMA request engine restore reason="
            + reason
            + " pending="
            + readBoardGmaPending
            + " node="
            + historyNodeSummary(restoreNode));
    if (flushIfReady) {
      flushReadBoardGmaEngineRestoreIfReady(reason);
    }
  }

  private boolean deferReadBoardGmaEngineRestoreIfPending(
      String reason, BoardHistoryNode restoreNode) {
    if (!readBoardGmaPending
        && !readBoardGmaEngineRestorePending
        && !readBoardGmaEngineRestoreInProgress) {
      return false;
    }
    if (readBoardGmaPending) {
      readBoardGmaPendingLogicallyInvalid = true;
    }
    readBoardGmaEngineRestorePending = true;
    readBoardGmaDeferredRestoreNode = restoreNode;
    localMoveSyncDebug(
        "ReadBoard GMA defer engine restore reason="
            + reason
            + " node="
            + historyNodeSummary(restoreNode));
    return true;
  }

  private boolean flushReadBoardGmaEngineRestoreIfReady(String reason) {
    BoardHistoryNode restoreNode;
    synchronized (this) {
      if (!readBoardGmaEngineRestorePending) {
        return false;
      }
      if (readBoardGmaPending || readBoardGmaEngineRestoreInProgress) {
        return true;
      }
      restoreNode = readBoardGmaDeferredRestoreNode;
      if (restoreNode == null && Lizzie.board != null && Lizzie.board.getHistory() != null) {
        restoreNode = Lizzie.board.getHistory().getCurrentHistoryNode();
      }
      readBoardGmaEngineRestoreInProgress = true;
    }
    RuntimeException restoreFailure = null;
    if (restoreNode != null) {
      localMoveSyncDebug(
          "ReadBoard GMA flush engine restore reason="
              + reason
              + " node="
              + historyNodeSummary(restoreNode));
      try {
        syncEngineToRebuiltSnapshotNow(restoreNode);
      } catch (RuntimeException ex) {
        restoreFailure = ex;
      }
    }
    boolean hasNewerRestoreNode;
    synchronized (this) {
      readBoardGmaEngineRestoreInProgress = false;
      hasNewerRestoreNode =
          readBoardGmaEngineRestorePending
              && readBoardGmaDeferredRestoreNode != null
              && readBoardGmaDeferredRestoreNode != restoreNode;
      if (!hasNewerRestoreNode) {
        readBoardGmaEngineRestorePending = false;
        readBoardGmaDeferredRestoreNode = null;
      }
    }
    if (hasNewerRestoreNode) {
      return flushReadBoardGmaEngineRestoreIfReady(reason + "-latest");
    }
    if (!readBoardGmaAutoPlayActive && Lizzie.leelaz != null) {
      Lizzie.leelaz.restoreReadBoardGmaRuntimeSettingsIfNeeded();
    }
    if (restoreFailure != null) {
      throw restoreFailure;
    }
    return true;
  }

  private boolean scheduleReadBoardGmaIfNeeded(String reason) {
    if (!readBoardGmaAutoPlayActive) {
      return false;
    }
    if (readBoardGmaEngineRestorePending) {
      localMoveSyncDebug("ReadBoard GMA skip pending engine restore reason=" + reason);
      return true;
    }
    if (readBoardGmaPending) {
      localMoveSyncDebug("ReadBoard GMA skip pending reason=" + reason);
      return true;
    }
    if (failedLocalMoveAwaitingRemoteObservation
        && !releaseFailedLocalMoveObservationIfTimedOut(reason)) {
      localMoveSyncDebug(
          "ReadBoard GMA wait failed-place observation reason="
              + reason
              + " "
              + pendingLocalMoveState());
      return true;
    }
    if (Lizzie.frame == null || Lizzie.board == null || Lizzie.leelaz == null) {
      localMoveSyncDebug(
          "ReadBoard GMA skip missing app state reason="
              + reason
              + " frame="
              + (Lizzie.frame != null)
              + " board="
              + (Lizzie.board != null)
              + " engine="
              + (Lizzie.leelaz != null));
      return false;
    }
    if (readBoardGmaAwaitingSyncedBoard) {
      localMoveSyncDebug("ReadBoard GMA wait synced board reason=" + reason);
      return true;
    }
    if (!Lizzie.frame.bothSync || EngineManager.isEngineGame()) {
      showReadBoardGmaUnsupportedOnce();
      return true;
    }
    if (readBoardGmaAutoPlayColor == null || readBoardGmaAutoPlayColor.isEmpty()) {
      return true;
    }
    boolean blacksTurn = Lizzie.board.getHistory().isBlacksTurn();
    if ((blacksTurn && !readBoardGmaAutoPlayColor.isBlack())
        || (!blacksTurn && !readBoardGmaAutoPlayColor.isWhite())) {
      localMoveSyncDebug(
          "ReadBoard GMA skip opponent turn reason="
              + reason
              + " blackToPlay="
              + blacksTurn
              + " color="
              + readBoardGmaAutoPlayColor);
      return true;
    }
    if (!readBoardTurnTrusted) {
      localMoveSyncDebug("ReadBoard GMA skip untrusted turn reason=" + reason);
      return false;
    }
    if (!isReadBoardAnalysisEngineAvailable()) {
      localMoveSyncDebug("ReadBoard GMA skip engine unavailable reason=" + reason);
      return false;
    }
    if (!Lizzie.leelaz.isReadBoardGmaCapabilityKnown()) {
      localMoveSyncDebug("ReadBoard GMA wait capability handshake reason=" + reason);
      return true;
    }
    if (!Lizzie.leelaz.supportsReadBoardGma()) {
      showReadBoardGmaUnsupportedOnce();
      return true;
    }

    String color = readBoardGmaAutoPlayColor.isBlack() ? "B" : "W";
    boolean ponder = Lizzie.config != null && Lizzie.config.readBoardPonder;
    readBoardGmaPending = true;
    readBoardGmaPendingLogicallyInvalid = false;
    localMoveSyncDebug(
        "ReadBoard GMA start reason="
            + reason
            + " color="
            + color
            + " maxTime="
            + readBoardGmaTimeSeconds
            + " maxVisits="
            + readBoardGmaMaxVisits
            + " ponder="
            + ponder);
    if (!Lizzie.leelaz.genmoveAnalyzeForReadBoard(
        color, readBoardGmaTimeSeconds, readBoardGmaMaxVisits, ponder)) {
      readBoardGmaPending = false;
      readBoardGmaPendingLogicallyInvalid = false;
      localMoveSyncDebug("ReadBoard GMA rejected by foreground lease reason=" + reason);
    }
    return true;
  }

  private void showReadBoardGmaUnsupportedOnce() {
    if (Lizzie.leelaz != null && Lizzie.leelaz.shouldShowReadBoardGmaUnsupportedPrompt()) {
      Utils.showMsg(READBOARD_GMA_UNSUPPORTED_MESSAGE);
    }
  }

  public boolean handleReadBoardGmaEnginePlay(String move) {
    if (!readBoardGmaPending) {
      return false;
    }
    readBoardGmaPending = false;
    boolean staleResult = readBoardGmaPendingLogicallyInvalid || !readBoardGmaAutoPlayActive;
    readBoardGmaPendingLogicallyInvalid = false;
    if (staleResult) {
      localMoveSyncDebug("ReadBoard GMA consume stale play move=" + move);
      return true;
    }
    if (move == null) {
      requestReadBoardGmaEngineRestoreAfterTerminalResult("null-final-play");
      return true;
    }
    String normalizedMove = move.trim();
    if (normalizedMove.equalsIgnoreCase("pass")
        || normalizedMove.toLowerCase(Locale.ROOT).startsWith("resign")
        || normalizedMove.equalsIgnoreCase("cancelled")) {
      localMoveSyncDebug("ReadBoard GMA final non-board move=" + normalizedMove);
      requestReadBoardGmaEngineRestoreAfterTerminalResult("non-board-final-play");
      return true;
    }
    if (Lizzie.board == null || Lizzie.leelaz == null) {
      requestReadBoardGmaEngineRestoreAfterTerminalResult("missing-app-state-final-play");
      return true;
    }
    boolean blacksTurn = Lizzie.board.getHistory().isBlacksTurn();
    Stone color = readBoardGmaAutoPlayColor;
    if ((blacksTurn && !color.isBlack()) || (!blacksTurn && !color.isWhite())) {
      localMoveSyncDebug(
          "ReadBoard GMA consume stale play due turn mismatch move="
              + normalizedMove
              + " blackToPlay="
              + blacksTurn
              + " color="
              + color);
      requestReadBoardGmaEngineRestoreAfterTerminalResult("turn-mismatch-final-play");
      return true;
    }
    int[] coords = Board.convertNameToCoordinates(normalizedMove);
    if (coords == null
        || coords.length < 2
        || coords[0] < 0
        || coords[1] < 0
        || coords[0] >= Board.boardWidth
        || coords[1] >= Board.boardHeight) {
      localMoveSyncDebug("ReadBoard GMA final invalid move=" + normalizedMove);
      requestReadBoardGmaEngineRestoreAfterTerminalResult("invalid-final-play");
      return true;
    }
    boolean oldInputCommand = Lizzie.leelaz.isInputCommand;
    try {
      Lizzie.leelaz.isInputCommand = true;
      Lizzie.board.placeFromReadBoardGma(coords[0], coords[1], color);
    } finally {
      Lizzie.leelaz.isInputCommand = oldInputCommand;
    }
    return true;
  }

  public void afterReadBoardGmaTerminalResponseConsumed(String reason) {
    if (readBoardGmaEngineRestorePending) {
      Thread restoreThread =
          new Thread(
              () -> flushReadBoardGmaTerminalResponseConsumed(reason),
              "ReadBoard-GMA-terminal-restore");
      restoreThread.setDaemon(true);
      restoreThread.start();
      return;
    }
    flushReadBoardGmaTerminalResponseConsumed(reason);
  }

  private void flushReadBoardGmaTerminalResponseConsumed(String reason) {
    if (!flushReadBoardGmaEngineRestoreIfReady(reason)
        && !readBoardGmaAutoPlayActive
        && Lizzie.leelaz != null) {
      Lizzie.leelaz.restoreReadBoardGmaRuntimeSettingsIfNeeded();
    }
  }

  public boolean handleReadBoardGmaEngineError(String line) {
    if (!readBoardGmaPending) {
      return false;
    }
    readBoardGmaPending = false;
    readBoardGmaPendingLogicallyInvalid = false;
    localMoveSyncDebug("ReadBoard GMA terminal error line=" + line);
    if (readBoardGmaAutoPlayActive) {
      requestReadBoardGmaEngineRestoreAfterTerminalResult("terminal-error");
    }
    return true;
  }

  private void requestReadBoardGmaEngineRestoreAfterTerminalResult(String reason) {
    readBoardGmaAwaitingSyncedBoard = true;
    BoardHistoryNode currentNode = null;
    if (Lizzie.board != null && Lizzie.board.getHistory() != null) {
      currentNode = Lizzie.board.getHistory().getCurrentHistoryNode();
    }
    requestReadBoardGmaEngineRestore(reason, currentNode, false);
  }

  private Stone autoPlayColorFromPlayParams(String[] params) {
    if (params == null || params.length < 2) {
      return Stone.EMPTY;
    }
    String color = params[1].trim();
    if ("black".equals(color)) {
      return Stone.BLACK;
    }
    if ("white".equals(color)) {
      return Stone.WHITE;
    }
    return Stone.EMPTY;
  }

  private void clearFailedLocalMoveStateIfAutoPlaySideChanged(Stone autoPlayColor) {
    if (autoPlayColor == null || autoPlayColor.isEmpty() || !hasFailedLocalMoveStateToPreserve()) {
      return;
    }
    Stone failedColor = failedLocalMoveRecoveryColor;
    if (failedColor == null || failedColor.isEmpty()) {
      failedColor = failedLocalMoveSuppressionColor;
    }
    if (failedColor == null || failedColor.isEmpty() || failedColor == autoPlayColor) {
      return;
    }
    localMoveSyncDebug(
        "clear failed local move state because auto-play side changed failedColor="
            + failedColor
            + " autoPlayColor="
            + autoPlayColor
            + " "
            + pendingLocalMoveState());
    clearFailedLocalMoveSuppression();
    clearFailedLocalMoveRecovery();
  }

  private boolean isFailedLocalMoveAwaitingRemoteObservation() {
    return hasFailedLocalMoveStateToPreserve() && failedLocalMoveAwaitingRemoteObservation;
  }

  private String pendingLocalMoveState() {
    long retryAgeMs =
        lastPendingLocalMoveRetryTimeMs == 0L
            ? -1L
            : System.currentTimeMillis() - lastPendingLocalMoveRetryTimeMs;
    long observationMsLeft =
        failedLocalMoveObservationDeadlineMs == 0L
            ? -1L
            : failedLocalMoveObservationDeadlineMs - System.currentTimeMillis();
    return "pendingState{lastMovePlayByLizzie="
        + lastMovePlayByLizzie
        + ",waiting="
        + waitingForReadBoardLocalMoveAck
        + ",clickCompleted="
        + pendingLocalMoveClickCompleted
        + ",retryCount="
        + pendingLocalMoveRetryCount
        + ",retryAgeMs="
        + retryAgeMs
        + ",pendingMove="
        + safePendingMoveText()
        + ",ackGeneration="
        + pendingLocalMoveAckGeneration
        + ",ignoreResult="
        + ignoreReadBoardPlaceResultsForCurrentPending
        + ",suppression="
        + failedLocalMoveSuppressionActive
        + "/"
        + failedLocalMoveSuppressionX
        + ","
        + failedLocalMoveSuppressionY
        + ","
        + failedLocalMoveSuppressionColor
        + ",recovery="
        + failedLocalMoveRecoveryActive
        + "/"
        + failedLocalMoveRecoveryX
        + ","
        + failedLocalMoveRecoveryY
        + ","
        + failedLocalMoveRecoveryColor
        + ",awaitRemoteObservation="
        + failedLocalMoveAwaitingRemoteObservation
        + ",observationMsLeft="
        + observationMsLeft
        + ",waitingOurTurnAfterRemoteChange="
        + failedLocalMoveWaitingForOurTurnAfterRemoteChange
        + ",confirmed="
        + confirmedLocalMoveActive
        + "/"
        + confirmedLocalMoveX
        + ","
        + confirmedLocalMoveY
        + ","
        + confirmedLocalMoveColor
        + ",baseline="
        + (confirmedLocalMoveBaselineKey != null && !confirmedLocalMoveBaselineKey.isEmpty())
        + "}";
  }

  private String safePendingMoveText() {
    try {
      if (hasPendingLocalMoveRecord()) {
        BoardData pendingData = pendingLocalMoveNode.getData();
        return pendingData.moveNumber
            + ":"
            + pendingLocalMoveColor
            + "@"
            + coordsText(new int[] {pendingLocalMoveX, pendingLocalMoveY})
            + ":fixed=true";
      }
      BoardData mainEndData = Lizzie.board.getHistory().getMainEnd().getData();
      return mainEndData.moveNumber
          + ":"
          + mainEndData.lastMoveColor
          + "@"
          + (mainEndData.lastMove.isPresent() ? coordsText(mainEndData.lastMove.get()) : "none")
          + ":isMoveNode="
          + mainEndData.isMoveNode();
    } catch (Exception ex) {
      return "unavailable:" + ex.getClass().getSimpleName();
    }
  }

  private String coordsText(int[] coords) {
    if (coords == null || coords.length < 2) {
      return "none";
    }
    return coords[0] + "," + coords[1];
  }

  private String snapshotSummary(int[] snapshotCodes) {
    if (snapshotCodes == null) {
      return "null";
    }
    return "len=" + snapshotCodes.length + ",key=" + snapshotKey(snapshotCodes);
  }

  private int[] snapshotCodesForNode(BoardHistoryNode node) {
    if (node == null || node.getData() == null) {
      return new int[0];
    }
    BoardData data = node.getData();
    int[] snapshotCodes = new int[Board.boardWidth * Board.boardHeight];
    for (int y = 0; y < Board.boardHeight; y++) {
      for (int x = 0; x < Board.boardWidth; x++) {
        snapshotCodes[y * Board.boardWidth + x] =
            snapshotCodeForStone(data.stones[Board.getIndex(x, y)]);
      }
    }
    if (data.lastMove.isPresent() && data.lastMoveColor != null && !data.lastMoveColor.isEmpty()) {
      int[] coords = data.lastMove.get();
      int snapshotIndex = coords[1] * Board.boardWidth + coords[0];
      if (snapshotIndex >= 0 && snapshotIndex < snapshotCodes.length) {
        snapshotCodes[snapshotIndex] = data.lastMoveColor.isBlack() ? 3 : 4;
      }
    }
    return snapshotCodes;
  }

  private int snapshotCodeForStone(Stone stone) {
    if (stone == null || stone.isEmpty()) {
      return 0;
    }
    return stone.isBlack() ? 1 : 2;
  }

  private String snapshotPositionKey(int[] snapshotCodes) {
    if (snapshotCodes == null || snapshotCodes.length == 0) {
      return "";
    }
    StringBuilder builder = new StringBuilder(snapshotCodes.length);
    for (int code : snapshotCodes) {
      builder.append((char) ('0' + normalizeSnapshotPositionCode(code)));
    }
    return builder.toString();
  }

  private int normalizeSnapshotPositionCode(int code) {
    if (code == 3) {
      return 1;
    }
    if (code == 4) {
      return 2;
    }
    return code;
  }

  private String snapshotKey(int[] snapshotCodes) {
    if (snapshotCodes == null || snapshotCodes.length == 0) {
      return "";
    }
    StringBuilder builder = new StringBuilder(snapshotCodes.length);
    for (int code : snapshotCodes) {
      builder.append((char) ('0' + code));
    }
    return builder.toString();
  }

  private void sendPlaceCommandWithoutRestartingTracking(int x, int y) {
    String command = "place " + x + " " + y;
    localMoveSyncDebug(
        "sendPlaceCommandWithoutRestartingTracking command="
            + command
            + " usePipe="
            + usePipe
            + " stream="
            + (readBoardStream != null)
            + " "
            + pendingLocalMoveState());
    YikeSyncDebugLog.log(
        "ReadBoard retry pending local move command="
            + command
            + " retry="
            + pendingLocalMoveRetryCount);
    if (hideFloadBoardBeforePlace && Lizzie.frame.floatBoard != null) {
      Lizzie.frame.floatBoard.setVisible(false);
      hideFromPlace = true;
    }
    if (usePipe) {
      sendCommandTo(command);
    } else if (readBoardStream != null) {
      readBoardStream.sendCommand(command);
    }
  }

  private void restoreViewedNodeAfterSync(
      boolean played, BoardHistoryNode currentNode, BoardHistoryNode syncEndNode) {
    if (Lizzie.frame.bothSync
        || !played
        || Lizzie.config.alwaysGotoLastOnLive
        || (showInBoard
            && Lizzie.frame.floatBoard != null
            && !Lizzie.frame.floatBoard.hideSuggestion)
        || !Lizzie.board.getHistory().getCurrentHistoryNode().previous().isPresent()
        || currentNode == syncEndNode) {
      return;
    }
    moveToAnyPositionWithoutTracking(currentNode);
  }

  private void keepViewOnRecoveredMainEnd(BoardHistoryNode syncEndNode) {
    if (Lizzie.board.getHistory().getCurrentHistoryNode() == syncEndNode) {
      return;
    }
    moveToAnyPositionWithoutTracking(syncEndNode);
  }

  private boolean tryApplySingleMoveRecovery(
      BoardHistoryNode syncStartNode, Stone[] syncStartStones, int[] snapshotCodes) {
    SyncSnapshotClassifier classifier =
        new SyncSnapshotClassifier(Board.boardWidth, Board.boardHeight);
    Optional<SyncSnapshotClassifier.SingleMove> recoveredMove =
        classifier.findSingleMoveCapture(syncStartStones, snapshotCodes);
    if (!recoveredMove.isPresent()) {
      return false;
    }
    SyncSnapshotClassifier.SingleMove move = recoveredMove.get();
    if (!canApplySingleMoveRecovery(syncStartNode, move, snapshotCodes)) {
      return false;
    }
    historyJumpTracker.clear();
    moveToAnyPositionWithoutTracking(syncStartNode);
    if (readBoardGmaPending
        || readBoardGmaEngineRestorePending
        || readBoardGmaEngineRestoreInProgress) {
      Lizzie.board.getHistory().place(move.x, move.y, move.color, false);
      BoardHistoryNode resolvedNode = Lizzie.board.getHistory().getMainEnd();
      rememberResolvedSnapshotNode(resolvedNode);
      requestReadBoardGmaEngineRestore("single-move-recovery", resolvedNode);
      return true;
    }
    Lizzie.board.placeForSync(move.x, move.y, move.color, false);
    {
      EngineFollowController c = Lizzie.engineFollowController;
      if (c != null) c.onMainlineAdvance(Lizzie.board.getHistory().getCurrentHistoryNode());
    }
    if (Lizzie.config.alwaysSyncBoardStat || showInBoard) {
      lastMoveWithoutTracking();
    }
    BoardHistoryNode resolvedNode = Lizzie.board.getHistory().getMainEnd();
    rememberResolvedSnapshotNode(resolvedNode);
    scheduleResumeAnalysisAfterSync(resolvedNode);
    return true;
  }

  private void applySyncViewState(
      boolean played, BoardHistoryNode currentNode, BoardHistoryNode syncEndNode) {
    restoreViewedNodeAfterSync(played, currentNode, syncEndNode);
    if (editMode) moveToAnyPositionWithoutTracking(currentNode);
    if (played) Lizzie.frame.renderVarTree(0, 0, false, false);
  }

  private void rememberResolvedSnapshotNode(BoardHistoryNode resolvedNode) {
    rememberResolvedSnapshotNode(resolvedNode, currentPendingRemoteContext().withoutForceRebuild());
  }

  private void rememberResolvedSnapshotNode(
      BoardHistoryNode resolvedNode, SyncRemoteContext resolvedRemoteContext) {
    resumeState =
        resolvedNode == null ? null : new SyncResumeState(resolvedNode, resolvedRemoteContext);
    lastResolvedSnapshotNode = resolvedNode;
    awaitingFirstSyncFrame = false;
  }

  private void scheduleResumeAnalysisAfterSync(BoardHistoryNode targetNode) {
    if (Lizzie.frame == null || targetNode == null || !isReadBoardAnalysisEngineAvailable()) {
      localMoveSyncDebug(
          "scheduleResumeAnalysisAfterSync skip frame="
              + (Lizzie.frame != null)
              + " target="
              + historyNodeSummary(targetNode)
              + " engineAvailable="
              + isReadBoardAnalysisEngineAvailable());
      return;
    }
    if (shouldSkipResumeTargetBeforeConfirmedLocalMove(targetNode)) {
      localMoveSyncDebug(
          "scheduleResumeAnalysisAfterSync skip stale target before confirmed local move target="
              + historyNodeSummary(targetNode)
              + " "
              + pendingLocalMoveState());
      return;
    }
    long scheduledEpoch = ++syncAnalysisEpoch;
    localMoveSyncDebug(
        "scheduleResumeAnalysisAfterSync epoch="
            + scheduledEpoch
            + " target="
            + historyNodeSummary(targetNode)
            + " readBoardPonder="
            + (Lizzie.config != null && Lizzie.config.readBoardPonder)
            + " enginePondering="
            + (Lizzie.leelaz != null && Lizzie.leelaz.isPondering()));
    Lizzie.frame.scheduleResumeAnalysisAfterLoad(
        SYNC_ANALYSIS_RESUME_DELAY_MS,
        () -> resumeAnalysisAfterSyncIfStillCurrent(scheduledEpoch, targetNode));
  }

  private boolean shouldSkipResumeTargetBeforeConfirmedLocalMove(BoardHistoryNode targetNode) {
    return confirmedLocalMoveActive
        && targetNode != confirmedLocalMoveNode
        && isAncestorOf(targetNode, confirmedLocalMoveNode);
  }

  private boolean isAncestorOf(BoardHistoryNode possibleAncestor, BoardHistoryNode node) {
    BoardHistoryNode current = node;
    while (current != null) {
      if (current == possibleAncestor) {
        return true;
      }
      Optional<BoardHistoryNode> previous = current.previous();
      if (!previous.isPresent()) {
        return false;
      }
      current = previous.get();
    }
    return false;
  }

  private boolean continueGameAfterSyncIfNeeded(String reason, BoardHistoryNode targetNode) {
    markReadBoardGmaSyncedBoard(reason);
    if (readBoardGmaEngineRestorePending) {
      if (targetNode != null) {
        readBoardGmaDeferredRestoreNode = targetNode;
      }
      flushReadBoardGmaEngineRestoreIfReady(reason);
      if (readBoardGmaEngineRestorePending) {
        return true;
      }
    }
    if (resumeFailedLocalMoveAfterSyncIfNeeded(reason, targetNode)) {
      return true;
    }
    if (continuePlayingAgainstLeelazAfterSyncIfNeeded(reason)) {
      return true;
    }
    return resumeAutoPlayAnalysisAfterSyncIfNeeded(reason, targetNode);
  }

  private void markReadBoardGmaSyncedBoard(String reason) {
    if (readBoardGmaAwaitingSyncedBoard) {
      readBoardGmaAwaitingSyncedBoard = false;
      localMoveSyncDebug("ReadBoard GMA synced board ready reason=" + reason);
    }
  }

  private boolean resumeFailedLocalMoveAfterSyncIfNeeded(
      String reason, BoardHistoryNode targetNode) {
    if (!failedLocalMoveRecoveryActive) {
      return false;
    }
    if (failedLocalMoveAwaitingRemoteObservation
        && !releaseFailedLocalMoveObservationIfTimedOut(reason)) {
      localMoveSyncDebug(
          "failed local move recovery leaves placement guard active while analysis may continue reason="
              + reason
              + " target="
              + historyNodeSummary(targetNode)
              + " "
              + pendingLocalMoveState());
      return false;
    }
    if (!failedLocalMoveRecoveryActive) {
      return false;
    }
    if (targetNode == null || targetNode.getData() == null) {
      localMoveSyncDebug(
          "failed local move recovery skip missing target reason="
              + reason
              + " target="
              + historyNodeSummary(targetNode)
              + " "
              + pendingLocalMoveState());
      return true;
    }
    Stone failedColor = failedLocalMoveRecoveryColor;
    if (failedColor == null || failedColor.isEmpty()) {
      localMoveSyncDebug(
          "failed local move recovery clear invalid color reason="
              + reason
              + " "
              + pendingLocalMoveState());
      clearFailedLocalMoveSuppression();
      clearFailedLocalMoveRecovery();
      return false;
    }
    boolean blackToPlay = targetNode.getData().blackToPlay;
    if (failedColor.isBlack() != blackToPlay) {
      failedLocalMoveAwaitingRemoteObservation = false;
      failedLocalMoveWaitingForOurTurnAfterRemoteChange = true;
      localMoveSyncDebug(
          "failed local move observed opponent turn; waiting for failed side turn reason="
              + reason
              + " target="
              + historyNodeSummary(targetNode)
              + " "
              + pendingLocalMoveState());
      return true;
    }
    failedLocalMoveAwaitingRemoteObservation = false;
    failedLocalMoveWaitingForOurTurnAfterRemoteChange = false;
    if (Lizzie.frame == null || Lizzie.leelaz == null || !isReadBoardAnalysisEngineAvailable()) {
      localMoveSyncDebug(
          "failed local move recovery skip missing game state reason="
              + reason
              + " frame="
              + (Lizzie.frame != null)
              + " engine="
              + (Lizzie.leelaz != null)
              + " engineAvailable="
              + isReadBoardAnalysisEngineAvailable()
              + " "
              + pendingLocalMoveState());
      return true;
    }
    String genmoveColor = failedColor.isBlack() ? "B" : "W";
    if (Lizzie.frame.isPlayingAgainstLeelaz) {
      boolean failedColorIsEngineSide = failedColor.isBlack() != Lizzie.frame.playerIsBlack;
      if (!failedColorIsEngineSide) {
        localMoveSyncDebug(
            "failed local move recovery skip player side reason="
                + reason
                + " target="
                + historyNodeSummary(targetNode)
                + " playerIsBlack="
                + Lizzie.frame.playerIsBlack
                + " "
                + pendingLocalMoveState());
        clearFailedLocalMoveSuppression();
        clearFailedLocalMoveRecovery();
        return false;
      }
      localMoveSyncDebug(
          "failed local move recovery genmove reason="
              + reason
              + " color="
              + genmoveColor
              + " target="
              + historyNodeSummary(targetNode)
              + " playerIsBlack="
              + Lizzie.frame.playerIsBlack
              + " "
              + pendingLocalMoveState());
      clearFailedLocalMoveSuppression();
      clearFailedLocalMoveRecovery();
      Lizzie.leelaz.genmove(genmoveColor);
      needGenmove = false;
      return true;
    }
    if (Lizzie.frame.isAnaPlayingAgainstLeelaz) {
      if (readBoardGmaAutoPlayActive) {
        clearFailedLocalMoveSuppression();
        clearFailedLocalMoveRecovery();
        scheduleReadBoardGmaIfNeeded(reason + "-failed-local-recovery");
        return true;
      }
      if (Lizzie.config != null && !Lizzie.config.readBoardPonder) {
        localMoveSyncDebug(
            "failed local move observed our turn but auto-play analysis skip readBoardPonder=false reason="
                + reason
                + " target="
                + historyNodeSummary(targetNode)
                + " "
                + pendingLocalMoveState());
        clearFailedLocalMoveSuppression();
        clearFailedLocalMoveRecovery();
        return true;
      }
      localMoveSyncDebug(
          "failed local move observed our turn; resume auto-play analysis reason="
              + reason
              + " target="
              + historyNodeSummary(targetNode)
              + " enginePonderingBefore="
              + Lizzie.leelaz.isPondering()
              + " "
              + pendingLocalMoveState());
      clearFailedLocalMoveSuppression();
      clearFailedLocalMoveRecovery();
      if (!Lizzie.leelaz.isPondering()) {
        Lizzie.leelaz.ponder();
      }
      return true;
    }
    localMoveSyncDebug(
        "failed local move recovery skip no active game reason="
            + reason
            + " target="
            + historyNodeSummary(targetNode)
            + " playingAgainst=false autoPlaying=false "
            + pendingLocalMoveState());
    clearFailedLocalMoveSuppression();
    clearFailedLocalMoveRecovery();
    return false;
  }

  private boolean continuePlayingAgainstLeelazAfterSyncIfNeeded(String reason) {
    if (Lizzie.frame == null || !Lizzie.frame.isPlayingAgainstLeelaz || !needGenmove) {
      if ("rebuild".equals(reason) || failedLocalMoveRecoveryActive) {
        localMoveSyncDebug(
            "continuePlayingAgainstLeelaz skip inactive reason="
                + reason
                + " frame="
                + (Lizzie.frame != null)
                + " playingAgainst="
                + (Lizzie.frame != null && Lizzie.frame.isPlayingAgainstLeelaz)
                + " autoPlaying="
                + (Lizzie.frame != null && Lizzie.frame.isAnaPlayingAgainstLeelaz)
                + " needGenmove="
                + needGenmove
                + " "
                + pendingLocalMoveState());
      }
      return false;
    }
    if (Lizzie.board == null || Lizzie.leelaz == null || !isReadBoardAnalysisEngineAvailable()) {
      localMoveSyncDebug(
          "continuePlayingAgainstLeelaz skip reason="
              + reason
              + " board="
              + (Lizzie.board != null)
              + " engine="
              + (Lizzie.leelaz != null)
              + " engineAvailable="
              + isReadBoardAnalysisEngineAvailable());
      return false;
    }
    boolean blacksTurn = Lizzie.board.getHistory().isBlacksTurn();
    String genmoveColor = null;
    if (blacksTurn != Lizzie.frame.playerIsBlack) {
      genmoveColor = blacksTurn ? "B" : "W";
    }
    if (genmoveColor == null) {
      localMoveSyncDebug(
          "continuePlayingAgainstLeelaz skip not engine turn reason="
              + reason
              + " blackToPlay="
              + blacksTurn
              + " playerIsBlack="
              + Lizzie.frame.playerIsBlack);
      return false;
    }
    localMoveSyncDebug(
        "continuePlayingAgainstLeelaz genmove reason="
            + reason
            + " color="
            + genmoveColor
            + " blackToPlay="
            + blacksTurn
            + " playerIsBlack="
            + Lizzie.frame.playerIsBlack);
    Lizzie.leelaz.genmove(genmoveColor);
    needGenmove = false;
    return true;
  }

  private boolean resumeAutoPlayAnalysisAfterSyncIfNeeded(
      String reason, BoardHistoryNode targetNode) {
    if (Lizzie.frame == null || !Lizzie.frame.isAnaPlayingAgainstLeelaz) {
      return false;
    }
    if (readBoardGmaAutoPlayActive) {
      return scheduleReadBoardGmaIfNeeded(reason + "-resume-auto-play");
    }
    if (hasFailedLocalMoveStateToPreserve()) {
      if (failedLocalMoveAwaitingRemoteObservation) {
        localMoveSyncDebug(
            "resume auto-play analysis while failed-place guard blocks only placement reason="
                + reason
                + " target="
                + historyNodeSummary(targetNode)
                + " "
                + pendingLocalMoveState());
      } else {
        localMoveSyncDebug(
            "resume auto-play analysis held by unresolved failed local move reason="
                + reason
                + " target="
                + historyNodeSummary(targetNode)
                + " "
                + pendingLocalMoveState());
        return true;
      }
    }
    if (hasFailedLocalMoveStateToPreserve()
        && failedLocalMoveAwaitingRemoteObservation
        && releaseFailedLocalMoveObservationIfTimedOut(reason)) {
      localMoveSyncDebug(
          "resume auto-play analysis released elapsed failed-place guard reason="
              + reason
              + " target="
              + historyNodeSummary(targetNode)
              + " "
              + pendingLocalMoveState());
    }
    if (hasFailedLocalMoveStateToPreserve() && !failedLocalMoveAwaitingRemoteObservation) {
      localMoveSyncDebug(
          "resume auto-play analysis held by unresolved failed local move reason="
              + reason
              + " target="
              + historyNodeSummary(targetNode)
              + " "
              + pendingLocalMoveState());
      return true;
    }
    if (Lizzie.config != null && !Lizzie.config.readBoardPonder) {
      if ("rebuild".equals(reason)) {
        localMoveSyncDebug(
            "resume auto-play analysis skip readBoardPonder=false reason="
                + reason
                + " target="
                + historyNodeSummary(targetNode));
      }
      return true;
    }
    if (Lizzie.leelaz == null || !isReadBoardAnalysisEngineAvailable()) {
      localMoveSyncDebug(
          "resume auto-play analysis skip engine unavailable reason="
              + reason
              + " target="
              + historyNodeSummary(targetNode)
              + " engine="
              + (Lizzie.leelaz != null)
              + " engineAvailable="
              + isReadBoardAnalysisEngineAvailable());
      return false;
    }
    if (Lizzie.leelaz.isPondering()) {
      if ("rebuild".equals(reason)) {
        localMoveSyncDebug(
            "resume auto-play analysis already pondering reason="
                + reason
                + " target="
                + historyNodeSummary(targetNode));
      }
      return true;
    }
    localMoveSyncDebug(
        "resume auto-play analysis reason="
            + reason
            + " target="
            + historyNodeSummary(targetNode)
            + " readBoardPonder="
            + (Lizzie.config != null && Lizzie.config.readBoardPonder));
    Lizzie.leelaz.ponder();
    return true;
  }

  private void resumeAnalysisAfterSyncIfStillCurrent(
      long scheduledEpoch, BoardHistoryNode targetNode) {
    if (scheduledEpoch != syncAnalysisEpoch) {
      localMoveSyncDebug(
          "resumeAnalysisAfterSync skip stale epoch scheduled="
              + scheduledEpoch
              + " current="
              + syncAnalysisEpoch
              + " target="
              + historyNodeSummary(targetNode));
      return;
    }
    if (Lizzie.frame == null || Lizzie.board == null || Lizzie.config == null) {
      localMoveSyncDebug(
          "resumeAnalysisAfterSync skip missing app state frame="
              + (Lizzie.frame != null)
              + " board="
              + (Lizzie.board != null)
              + " config="
              + (Lizzie.config != null));
      return;
    }
    if (readBoardGmaAutoPlayActive) {
      scheduleReadBoardGmaIfNeeded("resume-analysis-after-sync");
      return;
    }
    if (!Lizzie.config.readBoardPonder) {
      localMoveSyncDebug(
          "resumeAnalysisAfterSync skip readBoardPonder=false target="
              + historyNodeSummary(targetNode));
      return;
    }
    if (!isReadBoardAnalysisEngineAvailable()) {
      localMoveSyncDebug(
          "resumeAnalysisAfterSync skip engine unavailable target="
              + historyNodeSummary(targetNode));
      return;
    }
    BoardHistoryNode currentNode = Lizzie.board.getHistory().getCurrentHistoryNode();
    if (currentNode != targetNode) {
      localMoveSyncDebug(
          "resumeAnalysisAfterSync skip current moved current="
              + historyNodeSummary(currentNode)
              + " target="
              + historyNodeSummary(targetNode));
      return;
    }
    localMoveSyncDebug(
        "resumeAnalysisAfterSync run target="
            + historyNodeSummary(targetNode)
            + " enginePonderingBefore="
            + (Lizzie.leelaz != null && Lizzie.leelaz.isPondering()));
    boolean resumed = Lizzie.frame.ensureAnalysisResumedAfterLoad();
    localMoveSyncDebug(
        "resumeAnalysisAfterSync result resumed="
            + resumed
            + " enginePonderingAfter="
            + (Lizzie.leelaz != null && Lizzie.leelaz.isPondering()));
  }

  private boolean isReadBoardAnalysisEngineAvailable() {
    return Lizzie.leelaz != null && Lizzie.leelaz.isStarted();
  }

  private String historyNodeSummary(BoardHistoryNode node) {
    if (node == null) {
      return "null";
    }
    BoardData data = node.getData();
    if (data == null) {
      return "node[data=null]";
    }
    return "move="
        + data.moveNumber
        + ",last="
        + coordsText(data.lastMove.orElse(null))
        + ",color="
        + data.lastMoveColor
        + ",blackToPlay="
        + data.blackToPlay
        + ",snapshot="
        + data.isSnapshotNode();
  }

  private void stopPonderingIfActive() {
    if (Lizzie.leelaz != null && Lizzie.leelaz.isPondering()) {
      Lizzie.leelaz.togglePonder();
    }
  }

  private static final class SnapshotHistoryState {
    private final Optional<int[]> lastMove;
    private final Stone lastMoveColor;
    private final boolean blackToPlay;
    private final int moveNumber;

    private SnapshotHistoryState(
        Optional<int[]> lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
      this.lastMove = lastMove;
      this.lastMoveColor = lastMoveColor;
      this.blackToPlay = blackToPlay;
      this.moveNumber = moveNumber;
    }

    private static SnapshotHistoryState fromMarker(
        int x, int y, Stone color, boolean blackToPlay, int moveNumber) {
      return new SnapshotHistoryState(
          Optional.of(new int[] {x, y}), color, blackToPlay, moveNumber);
    }

    private static SnapshotHistoryState markerlessSnapshot(boolean blackToPlay, int moveNumber) {
      return new SnapshotHistoryState(Optional.empty(), Stone.EMPTY, blackToPlay, moveNumber);
    }
  }

  private boolean canApplySingleMoveRecovery(
      BoardHistoryNode syncStartNode, SyncSnapshotClassifier.SingleMove move, int[] snapshotCodes) {
    BoardData syncStartData = syncStartNode.getData();
    Stone[] stones = syncStartData.stones.clone();
    int moveIndex = Board.getIndex(move.x, move.y);
    if (!stones[moveIndex].isEmpty()) {
      return false;
    }
    Zobrist zobrist = syncStartData.zobrist.clone();
    stones[moveIndex] = move.color;
    zobrist.toggleStone(move.x, move.y, move.color);
    int isSuicidal = 0;
    if (!Lizzie.config.noCapture) {
      Board.removeDeadChain(move.x + 1, move.y, move.color.opposite(), stones, zobrist);
      Board.removeDeadChain(move.x, move.y + 1, move.color.opposite(), stones, zobrist);
      Board.removeDeadChain(move.x - 1, move.y, move.color.opposite(), stones, zobrist);
      Board.removeDeadChain(move.x, move.y - 1, move.color.opposite(), stones, zobrist);
      isSuicidal = Board.removeDeadChain(move.x, move.y, move.color, stones, zobrist);
    }
    if (violatesRecoveryKo(syncStartNode, zobrist) || violatesRecoverySuicide(isSuicidal)) {
      return false;
    }
    return !hasSnapshotDiff(stones, snapshotCodes);
  }

  private boolean violatesRecoveryKo(BoardHistoryNode syncStartNode, Zobrist zobrist) {
    return syncStartNode
        .previous()
        .map(previousNode -> previousNode != null && zobrist.equals(previousNode.getData().zobrist))
        .orElse(false);
  }

  private boolean violatesRecoverySuicide(int isSuicidal) {
    if (Lizzie.leelaz.canSuicidal) {
      return isSuicidal == 1;
    }
    return isSuicidal > 0;
  }

  private boolean hasSnapshotDiff(Stone[] stones, int[] snapshotCodes) {
    Optional<int[]> currentPendingLocalMove = currentPendingLocalMoveCoordinates();
    return snapshotDiffChecker()
        .hasDiff(
            snapshotCodes,
            stones,
            shouldIgnoreCurrentLastLocalMove(currentPendingLocalMove),
            currentPendingLocalMove);
  }

  private boolean isStoneDiff(int m, Stone[] stones, int x, int y) {
    // TODO Auto-generated method stub
    Stone stone = stones[Board.getIndex(x, y)];
    if (m == 0 && stone != Stone.EMPTY) {
      if (Lizzie.frame.bothSync && lastMovePlayByLizzie) {
        Optional<int[]> pendingMove = currentPendingLocalMoveCoordinates();
        if (pendingMove.isPresent()) {
          int[] coords = pendingMove.get();
          if (coords[0] == x && coords[1] == y) {
            return false;
          }
        }
      }
      return true;
    }
    if ((m == 1 || m == 3) && !stone.isBlack()) {
      return true;
    }
    if ((m == 2 || m == 4) && !stone.isWhite()) {
      return true;
    }
    return false;
  }

  public void shutdown() {
    shutdown(true);
  }

  void shutdownAfterProcessEnd() {
    shutdown(false);
  }

  private void shutdown(boolean requestGracefulExit) {
    if (!beginShutdown()) {
      return;
    }
    noMsg = true;
    resetActiveSyncState();
    clearResumeState();
    clearPendingRemoteContext();
    tempcount = new ArrayList<Integer>();
    if (Lizzie.frame != null) {
      Lizzie.frame.syncBoard = false;
      Lizzie.frame.bothSync = false;
    }
    if (requestGracefulExit && shouldSendQuitToHostedProcess()) {
      this.sendCommand("quit");
    }
    releaseHostedResources();
    publishCurrentReadBoardDiagnosticsSnapshot();
  }

  private synchronized boolean beginShutdown() {
    if (shutdownStarted) {
      return false;
    }
    shutdownStarted = true;
    return true;
  }

  private boolean shouldSendQuitToHostedProcess() {
    if (outputStream == null) {
      return false;
    }
    Process currentProcess = process;
    return currentProcess == null || currentProcess.isAlive();
  }

  public void onLocalHistoryNavigation() {
    if (!localNavigationTracker.shouldProcessLocalNavigation()) {
      return;
    }
    invalidatePendingSyncAnalysisResume();
    historyJumpTracker.onLocalNavigation();
    if (isSyncing) {
      localNavigationTracker.remember(Lizzie.board.getHistory().getCurrentHistoryNode());
    }
  }

  public void onHistoryOverwritten() {
    if (shouldSuppressHistoryOverwriteInvalidation()) {
      return;
    }
    clearResumeState();
    publishCurrentReadBoardDiagnosticsSnapshot();
  }

  public void sendCommandTo(String command) {
    // if (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp)
    // Lizzie.gtpConsole.addReadBoardCommand(command);
    BufferedOutputStream currentOutputStream = outputStream;
    if (currentOutputStream == null) {
      return;
    }
    try {
      currentOutputStream.write((command + "\n").getBytes());
      currentOutputStream.flush();
    } catch (IOException e) {
      // e.printStackTrace();
    }
  }

  public void sendCommand(String command) {
    if (command == null) {
      return;
    }
    if (command != null
        && (command.startsWith("yike")
            || command.startsWith("place")
            || command.startsWith("syncPlatform"))) {
      YikeSyncDebugLog.log(
          "ReadBoard sendCommand command="
              + command
              + " usePipe="
              + usePipe
              + " syncing="
              + isSyncing);
    }
    if (command.startsWith("place")) {
      localMoveSyncDebug(
          "sendCommand external place command="
              + command
              + " usePipe="
              + usePipe
              + " stream="
              + (readBoardStream != null)
              + " before "
              + pendingLocalMoveState());
      if (isPendingLocalMoveAwaitingReadBoard()) {
        localMoveSyncDebug("sendCommand external place skipped; pending local move still active");
        return;
      }
      clearFailedLocalMoveStateIfOutgoingPlaceDiffers(command);
      if (hideFloadBoardBeforePlace && Lizzie.frame.floatBoard != null) {
        Lizzie.frame.floatBoard.setVisible(false);
        hideFromPlace = true;
      }
      startTrackingLocalMoveFromLizzie();
      if (Lizzie.frame.isPlayingAgainstLeelaz) needGenmove = true;
      localMoveSyncDebug("sendCommand external place after tracking " + pendingLocalMoveState());
    }
    if (usePipe) {
      sendCommandTo(command);
    } else if (readBoardStream != null) readBoardStream.sendCommand(command);
  }

  private void clearFailedLocalMoveStateIfOutgoingPlaceDiffers(String command) {
    if (!hasFailedLocalMoveStateToPreserve()) {
      return;
    }
    Optional<int[]> coords = parsePlaceCommandCoordinates(command);
    if (!coords.isPresent()) {
      return;
    }
    int[] move = coords.get();
    if (move[0] == failedLocalMoveRecoveryX && move[1] == failedLocalMoveRecoveryY) {
      return;
    }
    localMoveSyncDebug(
        "outgoing different place clears failed local move state command="
            + command
            + " "
            + pendingLocalMoveState());
    clearFailedLocalMoveSuppression();
    clearFailedLocalMoveRecovery();
  }

  private Optional<int[]> parsePlaceCommandCoordinates(String command) {
    String[] params = command.trim().split("\\s+");
    if (params.length < 3 || !"place".equals(params[0])) {
      return Optional.empty();
    }
    try {
      return Optional.of(new int[] {Integer.parseInt(params[1]), Integer.parseInt(params[2])});
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  public void sendLossFocus() {
    // TODO Auto-generated method stub
    if (!Lizzie.config.readBoardGetFocus) return;
    sendCommand("loss");
  }

  public void checkVersion() {
    sendCommand("version");
  }

  void handleReady() {
    if (isLoaded) {
      return;
    }
    isLoaded = true;
    checkVersion();
    announceHostedUpdateSupport();
    sendAnalysisState();
  }

  private void sendAnalysisState() {
    boolean running = Lizzie.leelaz != null && Lizzie.leelaz.isPondering();
    sendCommand("analysisState " + (running ? "running" : "paused"));
  }

  public boolean shouldAnnounceHostedUpdateSupport() {
    return usePipe;
  }

  public void announceHostedUpdateSupport() {
    if (shouldAnnounceHostedUpdateSupport()) {
      sendCommand(READBOARD_UPDATE_SUPPORTED_COMMAND);
      sendCommand(READBOARD_UPDATE_PACKAGE_V2_SUPPORTED_COMMAND);
    }
  }

  private void handleHostedUpdateRequest(ReadBoardUpdateRequest request) {
    LizzieFrame frame = Lizzie.frame;
    if (frame == null) {
      return;
    }
    frame.handleReadBoardHostedUpdateRequest(this, request);
  }

  private void releaseHostedResources() {
    InputStreamReader currentInputStream = inputStream;
    BufferedOutputStream currentOutputStream = outputStream;
    ScheduledExecutorService currentExecutor = executor;
    ScheduledExecutorService currentPendingLocalMoveTimeoutExecutor =
        pendingLocalMoveTimeoutExecutor;
    ReadBoardStream currentReadBoardStream = readBoardStream;
    Socket currentSocket = socket;
    ServerSocket currentServerSocket = s;
    Process currentProcess = process;

    inputStream = null;
    outputStream = null;
    executor = null;
    pendingLocalMoveTimeoutExecutor = null;
    readBoardStream = null;
    socket = null;
    s = null;
    process = null;

    closeQuietly(currentReadBoardStream);
    detachFromFrame();
    closeQuietly(currentInputStream);
    closeQuietly(currentOutputStream);
    closeQuietly(currentSocket);
    closeQuietly(currentServerSocket);
    shutdownExecutor(currentExecutor);
    shutdownExecutor(currentPendingLocalMoveTimeoutExecutor);
    waitForHostedProcessExit(currentProcess);
  }

  private void detachFromFrame() {
    if (Lizzie.frame != null && Lizzie.frame.readBoard == this) {
      Lizzie.frame.readBoard = null;
    }
  }

  private static void shutdownExecutor(ScheduledExecutorService executor) {
    if (executor == null) {
      return;
    }
    executor.shutdownNow();
  }

  private static void waitForHostedProcessExit(Process process) {
    if (process == null) {
      return;
    }
    try {
      if (process.waitFor(PROCESS_EXIT_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        return;
      }
      process.destroy();
      if (process.waitFor(PROCESS_DESTROY_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        return;
      }
      process.destroyForcibly();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  private static void closeQuietly(Closeable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (IOException ex) {
      // Ignore close failures during shutdown.
    }
  }

  // public void sendStopInBoard() {
  //	// TODO Auto-generated method stub
  //	 sendCommand("notinboard");
  // }
}
