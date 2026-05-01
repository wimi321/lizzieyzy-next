package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
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
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import featurecat.lizzie.util.Utils;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ReadBoard {
  private static final long PROCESS_EXIT_WAIT_TIMEOUT_MS = 1000L;
  private static final long PROCESS_DESTROY_WAIT_TIMEOUT_MS = 200L;
  private static final String LEGACY_NATIVE_READBOARD_EXE = "readboard.exe";
  private static final String LEGACY_NATIVE_READBOARD_BAT = "readboard.bat";
  private static final String JAVA_READBOARD_DIRECTORY = "readboard_java";
  private static final String JAVA_READBOARD_JAR = "readboard-1.6.2-shaded.jar";
  private static final String[] JAVA_READBOARD_SUPPORT_FILES = {
    "help.docx", "help_en.docx", "help_jp.docx"
  };
  private static final int SYNC_ANALYSIS_RESUME_DELAY_MS = 200;
  private static final int STARTUP_OUTPUT_LOG_LIMIT = 8;
  private static final String READBOARD_UPDATE_SUPPORTED_COMMAND = "readboardUpdateSupported";

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

  public Process process;
  private InputStreamReader inputStream;
  private BufferedOutputStream outputStream;
  private ScheduledExecutorService executor;
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
  // private long startTime;
  private boolean javaReadBoard = false;
  private String javaReadBoardName = JAVA_READBOARD_JAR;
  private boolean waitSocket = true;
  public boolean lastMovePlayByLizzie = false;
  private SyncRemoteContext pendingRemoteContext = SyncRemoteContext.generic(false);
  private boolean waitingForReadBoardLocalMoveAck = false;
  private boolean hideFloadBoardBeforePlace = false;
  private boolean hideFromPlace = false;
  public boolean editMode = false;
  private int startupOutputLineCount = 0;
  private final SyncConflictTracker conflictTracker = new SyncConflictTracker();
  private final SyncHistoryJumpTracker historyJumpTracker = new SyncHistoryJumpTracker();
  private final SyncLocalNavigationTracker localNavigationTracker =
      new SyncLocalNavigationTracker();
  private SyncResumeState resumeState;
  private BoardHistoryNode lastResolvedSnapshotNode;
  private boolean awaitingFirstSyncFrame = true;
  private int historyOverwriteSuppressionDepth = 0;
  private volatile long syncAnalysisEpoch = 0L;

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

    private CompleteSnapshotRecoveryDecision(
        CompleteSnapshotRecoveryOutcome outcome,
        BoardHistoryNode resolvedNode,
        boolean shouldResumeAnalysis) {
      this.outcome = outcome;
      this.resolvedNode = resolvedNode;
      this.shouldResumeAnalysis = shouldResumeAnalysis;
    }

    private static CompleteSnapshotRecoveryDecision noChange(
        BoardHistoryNode resolvedNode, boolean shouldResumeAnalysis) {
      return new CompleteSnapshotRecoveryDecision(
          CompleteSnapshotRecoveryOutcome.NO_CHANGE, resolvedNode, shouldResumeAnalysis);
    }

    private static CompleteSnapshotRecoveryDecision singleMoveRecovery() {
      return new CompleteSnapshotRecoveryDecision(
          CompleteSnapshotRecoveryOutcome.SINGLE_MOVE_RECOVERY, null, false);
    }

    private static CompleteSnapshotRecoveryDecision hold() {
      return new CompleteSnapshotRecoveryDecision(
          CompleteSnapshotRecoveryOutcome.HOLD, null, false);
    }

    private static CompleteSnapshotRecoveryDecision forceRebuild() {
      return new CompleteSnapshotRecoveryDecision(
          CompleteSnapshotRecoveryOutcome.FORCE_REBUILD, null, false);
    }
  }

  private SyncSnapshotRebuildPolicy rebuildPolicy() {
    return new SyncSnapshotRebuildPolicy(Board.boardWidth);
  }

  private SyncSnapshotDiffChecker snapshotDiffChecker() {
    return new SyncSnapshotDiffChecker(Board.boardWidth);
  }

  public ReadBoard(boolean usePipe, boolean isJavaReadBoard) throws Exception {
    this.usePipe = usePipe;
    this.javaReadBoard = isJavaReadBoard;
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

  static File legacyJavaReadBoardJar(String javaReadBoardName) {
    return resolveJavaReadBoardJar(defaultJavaReadBoardDirectoryCandidates(), javaReadBoardName);
  }

  static File resolveJavaReadBoardJar(List<File> candidates, String javaReadBoardName) {
    for (File candidate : candidates) {
      File jar = new File(candidate, javaReadBoardName);
      if (jar.canRead()) {
        return jar.getAbsoluteFile();
      }
    }
    return new File(JAVA_READBOARD_DIRECTORY + File.separator + javaReadBoardName)
        .getAbsoluteFile();
  }

  static List<File> defaultJavaReadBoardDirectoryCandidates() {
    Set<File> candidates = new LinkedHashSet<File>();
    candidates.addAll(
        javaReadBoardDirectoryCandidatesForBase(new File(System.getProperty("user.dir", "."))));
    codeSourceDirectory()
        .ifPresent(
            directory -> candidates.addAll(javaReadBoardDirectoryCandidatesForBase(directory)));
    bundledRuntimeRoot()
        .ifPresent(
            directory -> candidates.addAll(javaReadBoardDirectoryCandidatesForBase(directory)));
    return new ArrayList<File>(candidates);
  }

  static List<File> javaReadBoardDirectoryCandidatesForBase(File baseDir) {
    Set<File> candidates = new LinkedHashSet<File>();
    File absoluteBase = baseDir.getAbsoluteFile();
    candidates.add(new File(absoluteBase, JAVA_READBOARD_DIRECTORY));
    candidates.add(new File(new File(absoluteBase, "app"), JAVA_READBOARD_DIRECTORY));
    if ("app".equalsIgnoreCase(absoluteBase.getName())) {
      candidates.add(new File(absoluteBase, JAVA_READBOARD_DIRECTORY));
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

  static ProcessBuilder buildJavaReadBoardProcessBuilder(
      File javaReadBoardJar, List<String> appArgs, List<String> jvmArgs) {
    return buildJavaReadBoardProcessBuilder(
        javaReadBoardJar, javaReadBoardJar.getAbsoluteFile().getParentFile(), appArgs, jvmArgs);
  }

  static ProcessBuilder buildJavaReadBoardProcessBuilder(
      File javaReadBoardJar, File workingDirectory, List<String> appArgs, List<String> jvmArgs) {
    File absoluteJar = javaReadBoardJar.getAbsoluteFile();
    ProcessBuilder processBuilder = Utils.buildJavaJarProcess(absoluteJar, appArgs, jvmArgs);
    processBuilder.directory(workingDirectory.getAbsoluteFile());
    processBuilder.redirectErrorStream(true);
    return processBuilder;
  }

  static boolean canUseJavaReadBoardWorkingDirectory(File directory) {
    if (directory == null || !directory.isDirectory()) {
      return false;
    }
    File probe = null;
    try {
      probe = File.createTempFile(".lizzie-readboard-write-test", ".tmp", directory);
      return probe.isFile();
    } catch (IOException | SecurityException e) {
      return false;
    } finally {
      if (probe != null && probe.exists() && !probe.delete()) {
        probe.deleteOnExit();
      }
    }
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
    if (javaReadBoard) {
      File javaReadBoardJar = legacyJavaReadBoardJar(javaReadBoardName);
      if (!javaReadBoardJar.exists()) {
        Utils.deleteDir(new File(JAVA_READBOARD_DIRECTORY));
        Utils.copyReadBoardJava(javaReadBoardName);
        javaReadBoardJar =
            new File(JAVA_READBOARD_DIRECTORY + File.separator + javaReadBoardName)
                .getAbsoluteFile();
      }
      List<String> jvmArgs = new ArrayList<String>();
      jvmArgs.add("-Dsun.java2d.uiScale=1.0");
      if (Lizzie.javaVersion >= 17) {
        jvmArgs.add("--add-opens");
        jvmArgs.add("java.desktop/sun.awt=ALL-UNNAMED");
        jvmArgs.add("--add-opens");
        jvmArgs.add("java.desktop/java.awt=ALL-UNNAMED");
        jvmArgs.add("--add-opens");
        jvmArgs.add("java.base/java.lang=ALL-UNNAMED");
      }
      List<String> appArgs = new ArrayList<String>();
      appArgs.add(Lizzie.resourceBundle.getString("ReadBoard.language"));
      appArgs.add(Lizzie.config.useJavaLooks ? "true" : "false");
      appArgs.add(String.valueOf((int) Math.round(Config.frameFontSize * Lizzie.javaScaleFactor)));
      appArgs.add(String.valueOf(Board.boardWidth));
      appArgs.add(String.valueOf(Board.boardHeight));
      File workingDirectory = resolveJavaReadBoardWorkingDirectory(javaReadBoardJar);
      ProcessBuilder processBuilder =
          buildJavaReadBoardProcessBuilder(javaReadBoardJar, workingDirectory, appArgs, jvmArgs);
      logJavaReadBoardLaunch(processBuilder, javaReadBoardJar);
      try {
        process = processBuilder.start();
      } catch (Exception e) {
        logJavaReadBoardStartFailure(processBuilder, e);
        Utils.showMsg(e.getLocalizedMessage());
        throw new Exception("Start Java board synchronization failed", e);
      }
    } else {
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
      ProcessBuilder processBuilder = buildLegacyNativeReadBoardProcessBuilder(usePipe, commands);
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

  private File resolveJavaReadBoardWorkingDirectory(File javaReadBoardJar) {
    File jarDirectory = javaReadBoardJar.getAbsoluteFile().getParentFile();
    if (canUseJavaReadBoardWorkingDirectory(jarDirectory)) {
      return jarDirectory;
    }

    File runtimeDirectory =
        Lizzie.config == null
            ? new File(new File(System.getProperty("user.home", "."), ".lizzieyzy-next"), "runtime")
            : Lizzie.config.getRuntimeWorkDirectory();
    File workingDirectory = new File(runtimeDirectory, JAVA_READBOARD_DIRECTORY).getAbsoluteFile();
    if (!workingDirectory.exists() && !workingDirectory.mkdirs()) {
      System.err.println(
          "Unable to create Java board synchronization working directory: "
              + workingDirectory.getAbsolutePath());
    }
    copyJavaReadBoardSupportFiles(jarDirectory, workingDirectory);
    return workingDirectory;
  }

  private void copyJavaReadBoardSupportFiles(File sourceDirectory, File targetDirectory) {
    if (sourceDirectory == null || targetDirectory == null || !targetDirectory.isDirectory()) {
      return;
    }
    for (String fileName : JAVA_READBOARD_SUPPORT_FILES) {
      File source = new File(sourceDirectory, fileName);
      File target = new File(targetDirectory, fileName);
      if (!source.canRead()) {
        continue;
      }
      try {
        if (!target.exists() || source.length() != target.length()) {
          Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (IOException e) {
        System.err.println(
            "Unable to copy Java board synchronization support file "
                + source.getAbsolutePath()
                + " to "
                + target.getAbsolutePath()
                + ": "
                + e.getMessage());
      }
    }
  }

  private void logNativeReadBoardLaunch(ProcessBuilder processBuilder) {
    if (javaReadBoard) {
      return;
    }
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
    if (javaReadBoard) {
      return;
    }
    System.err.println("Failed to start native board synchronization tool.");
    logNativeReadBoardLaunch(processBuilder);
    System.err.println("  start exception: " + exception.getClass().getName());
    System.err.println("  message: " + exception.getLocalizedMessage());
  }

  private void logJavaReadBoardLaunch(ProcessBuilder processBuilder, File javaReadBoardJar) {
    if (!javaReadBoard) {
      return;
    }
    File workingDirectory = processBuilder.directory();
    System.out.println("Starting Java board synchronization tool.");
    System.out.println("  jar: " + javaReadBoardJar.getAbsolutePath());
    System.out.println(
        "  working directory: "
            + (workingDirectory == null ? "" : workingDirectory.getAbsolutePath()));
    System.out.println("  command: " + processBuilder.command());
  }

  private void logJavaReadBoardStartFailure(ProcessBuilder processBuilder, Exception exception) {
    if (!javaReadBoard) {
      return;
    }
    System.err.println("Failed to start Java board synchronization tool.");
    logJavaReadBoardLaunch(processBuilder, javaReadBoardJarFromCommand(processBuilder.command()));
    System.err.println("  start exception: " + exception.getClass().getName());
    System.err.println("  message: " + exception.getLocalizedMessage());
  }

  private File javaReadBoardJarFromCommand(List<String> command) {
    int jarOptionIndex = command.indexOf("-jar");
    if (jarOptionIndex < 0 || jarOptionIndex + 1 >= command.size()) {
      return new File("");
    }
    return new File(command.get(jarOptionIndex + 1));
  }

  private void logReadBoardOutputLine(String rawLine) {
    if (startupOutputLineCount >= STARTUP_OUTPUT_LOG_LIMIT) {
      return;
    }
    startupOutputLineCount++;
    String line = rawLine.replace("\r", "\\r").replace("\n", "\\n");
    System.out.println(
        (javaReadBoard ? "Java" : "Native") + " board synchronization output: " + line);
  }

  private void logReadBoardExit() {
    Process currentProcess = process;
    String label = javaReadBoard ? "Java" : "Native";
    if (currentProcess == null) {
      System.out.println(label + " board synchronization process handle is already cleared.");
      return;
    }
    try {
      if (currentProcess.waitFor(PROCESS_DESTROY_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        System.out.println(
            label + " board synchronization process exit code: " + currentProcess.exitValue());
      } else {
        System.out.println(label + " board synchronization stdout closed before process exit.");
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
            if (!isLoaded) {
              isLoaded = true;
              if (!javaReadBoard) checkVersion();
              announceHostedUpdateSupport();
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
      if (!javaReadBoard && !isLoaded) {
        shutdown();
      } else shutdown();
      // Do no exit for switching weights
      // System.exit(-1);
    } catch (IOException e) {
      e.printStackTrace();
      Lizzie.frame.bothSync = false;
      Lizzie.frame.syncBoard = false;
      // System.exit(-1);
    }
  }

  public void parseLine(String line) {
    ensureTransientSyncStateInitialized();
    ReadBoardUpdateRequest updateRequest = ReadBoardUpdateRequest.tryParse(line);
    if (updateRequest != null) {
      handleHostedUpdateRequest(updateRequest);
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
      }
    }
    if (line.startsWith("syncPlatform ")) {
      pendingRemoteContext =
          currentPendingRemoteContext()
              .withPlatform(parseSyncPlatform(line.substring("syncPlatform ".length())));
      return;
    }
    if (line.startsWith("roomToken ")) {
      pendingRemoteContext =
          currentPendingRemoteContext().withRoomToken(line.substring("roomToken ".length()).trim());
      return;
    }
    if (line.startsWith("liveTitleMove ")) {
      pendingRemoteContext =
          currentPendingRemoteContext().withLiveTitleMove(parseOptionalInt(line, "liveTitleMove "));
      return;
    }
    if (line.startsWith("recordCurrentMove ")) {
      pendingRemoteContext =
          currentPendingRemoteContext()
              .withRecordCurrentMove(parseOptionalInt(line, "recordCurrentMove "));
      return;
    }
    if (line.startsWith("recordTotalMove ")) {
      pendingRemoteContext =
          currentPendingRemoteContext()
              .withRecordTotalMove(parseOptionalInt(line, "recordTotalMove "));
      return;
    }
    if (line.startsWith("recordAtEnd ")) {
      pendingRemoteContext =
          currentPendingRemoteContext().withRecordAtEnd(line.trim().endsWith("1"));
      return;
    }
    if (line.startsWith("recordTitleFingerprint ")) {
      pendingRemoteContext =
          currentPendingRemoteContext()
              .withTitleFingerprint(line.substring("recordTitleFingerprint ".length()).trim());
      return;
    }
    if (line.trim().equals("forceRebuild")) {
      pendingRemoteContext = currentPendingRemoteContext().withForceRebuild(true);
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
    }
    if (line.startsWith("clear")) {
      resetActiveSyncState();
      clearPendingRemoteContext();
      tempcount = new ArrayList<Integer>();
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
          resetActiveSyncState();
        }
      } else {
        resetActiveSyncState();
      }
      tempcount = new ArrayList<Integer>();
    }
    if (line.trim().equals("sync")) {
      Lizzie.frame.syncBoard = true;
      if (isReadBoardAnalysisEngineAvailable() && !Lizzie.leelaz.isPondering()) {
        Lizzie.leelaz.togglePonder();
      }
    }
    if (line.startsWith("both")) {
      Lizzie.frame.bothSync = true;
      if (Lizzie.frame.floatBoard != null && Lizzie.frame.floatBoard.isVisible())
        Lizzie.frame.floatBoard.setEditButton();
    }
    if (line.startsWith("noboth")) {
      Lizzie.frame.bothSync = false;
      if (Lizzie.frame.floatBoard != null && Lizzie.frame.floatBoard.isVisible())
        Lizzie.frame.floatBoard.setEditButton();
    }
    if (line.startsWith("stopAutoPlay")) {
      LizzieFrame.toolbar.chkAutoPlay.setSelected(false);
      LizzieFrame.toolbar.isAutoPlay = false;
    }
    if (line.startsWith("endsync")) {
      noMsg = true;
      resetActiveSyncState();
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
    }
    if (line.startsWith("stopsync")) {
      resetActiveSyncState();
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
    }
    if (line.startsWith("play")) {
      String[] params = line.trim().split(">");
      if (params.length == 3) {
        String[] playParams = params[2].trim().split(" ");
        int playouts = Integer.parseInt(playParams[1]);
        int firstPlayouts = Integer.parseInt(playParams[2]);
        int time = Integer.parseInt(playParams[0]);
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
        Lizzie.leelaz.ponder();
      }
    }
    if (line.startsWith("pass")) {
      Lizzie.board.changeNextTurn();
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

    if (line.startsWith("noponder")) {
      if (Lizzie.frame.isPlayingAgainstLeelaz) {
        Lizzie.frame.isPlayingAgainstLeelaz = false;
        Lizzie.leelaz.isThinking = false;
      }
      if (Lizzie.frame.isAnaPlayingAgainstLeelaz) {
        Lizzie.frame.stopAiPlayingAndPolicy();
      }
      Lizzie.leelaz.togglePonder();
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
      markLocalMoveCommandCompleted();
      if (hideFloadBoardBeforePlace && hideFromPlace) {
        hideFromPlace = false;
        if (Lizzie.frame.floatBoard != null) Lizzie.frame.floatBoard.setVisible(true);
      }
    }
  }

  private void syncBoardStones(boolean isSecondTime) {
    //    if (!this.javaReadBoard && !isSecondTime) {
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
        return;
      }
      acknowledgeLocalMoveIfSnapshotCaughtUp(stones, currentSnapshotCodes);

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
        if (recovery.outcome == CompleteSnapshotRecoveryOutcome.HOLD) {
          return;
        }
        if (recovery.outcome == CompleteSnapshotRecoveryOutcome.FORCE_REBUILD) {
          rebuildFromSnapshot(node2, currentSnapshotCodes, snapshotDelta, currentFoxMoveNumber);
          return;
        }
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
            currentSyncEndNode, currentRemoteContext, currentSnapshotCodes)) {
          rebuildFromSnapshot(
              currentSyncEndNode, currentSnapshotCodes, snapshotDelta, currentFoxMoveNumber);
          return;
        }
        historyJumpTracker.clear();
        applySyncViewState(played, currentNode, currentSyncEndNode);
      }
      if (!needReSync) {
        conflictTracker.clear();
        awaitingFirstSyncFrame = false;
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
      if (Lizzie.frame.isPlayingAgainstLeelaz && needGenmove) {
        if (!Lizzie.board.getHistory().isBlacksTurn() && Lizzie.frame.playerIsBlack) {
          Lizzie.leelaz.genmove("W");
          needGenmove = false;
        } else if (!Lizzie.frame.playerIsBlack) {
          Lizzie.leelaz.genmove("B");
          needGenmove = false;
        }
      }
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
      BoardHistoryNode syncEndNode, SyncRemoteContext remoteContext, int[] snapshotCodes) {
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
    boolean expectedBlackToPlay = explicitPlayerOverride(currentData).orElse(moveNumber % 2 == 0);
    return currentData.moveNumber != moveNumber || currentData.blackToPlay != expectedBlackToPlay;
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
      return CompleteSnapshotRecoveryDecision.forceRebuild();
    }
    if (shouldForceRebuildOnResumeConflict(remoteContext)) {
      return CompleteSnapshotRecoveryDecision.forceRebuild();
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
          matchedNode, matchedNode != currentNode || awaitingFirstSyncFrame);
    }

    Optional<BoardHistoryNode> adjacentMatch =
        rebuildPolicy()
            .findAdjacentMatchFromLastResolvedNode(resumeState, snapshotCodes, remoteContext);
    if (adjacentMatch.isPresent()) {
      BoardHistoryNode matchedNode = adjacentMatch.get();
      return CompleteSnapshotRecoveryDecision.noChange(
          matchedNode, matchedNode != currentNode || awaitingFirstSyncFrame);
    }

    if (snapshotDelta.hasMarker()
        && tryApplySingleMoveRecovery(syncStartNode, syncStartStones, snapshotCodes)) {
      return CompleteSnapshotRecoveryDecision.singleMoveRecovery();
    }
    if (shouldForceRebuildWithoutWaiting(syncStartNode, remoteContext)) {
      return CompleteSnapshotRecoveryDecision.forceRebuild();
    }
    if (shouldHoldConflictingSnapshot(syncStartNode, snapshotCodes, remoteContext)) {
      return CompleteSnapshotRecoveryDecision.hold();
    }
    return CompleteSnapshotRecoveryDecision.forceRebuild();
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
    boolean analysisEngineAvailable = isReadBoardAnalysisEngineAvailable();
    resetActiveSyncState();
    BoardHistoryList previousHistory = Lizzie.board.getHistory();
    RootStartSetupState preservedRootStartSetup = captureRootStartSetupState();
    BoardHistoryList rebuiltHistory =
        buildSnapshotHistory(
            previousHistory, syncStartNode, snapshotCodes, snapshotDelta, foxMoveNumber);
    if (rebuildPolicy().shouldRebuildImmediatelyWithoutHistory(syncStartNode)) {
      clearBoardWithoutInvalidatingResumeState(false);
    }
    Lizzie.board.hasStartStone = false;
    Lizzie.board.startStonelist = new ArrayList<>();
    setHistoryWithoutInvalidatingResumeState(rebuiltHistory);
    restoreRootStartSetupIfNoOrRootSnapshotAnchor(syncStartNode, preservedRootStartSetup);
    if (analysisEngineAvailable) {
      syncEngineToRebuiltSnapshot(rebuiltHistory.getCurrentHistoryNode());
    }
    rememberResolvedSnapshotNode(rebuiltHistory.getCurrentHistoryNode(), resolvedRemoteContext);
    if (analysisEngineAvailable) {
      scheduleResumeAnalysisAfterSync(rebuiltHistory.getCurrentHistoryNode());
    }
    Lizzie.frame.renderVarTree(0, 0, false, false);
    Lizzie.frame.refresh();
    firstSync = false;
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
    BoardData snapshotData =
        buildSnapshotBoardData(syncStartNode, snapshotCodes, snapshotDelta, foxMoveNumber);
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
        syncStartNode, snapshotCodes, snapshotDelta, currentPendingFoxMoveNumber());
  }

  private BoardData buildSnapshotBoardData(
      BoardHistoryNode syncStartNode,
      int[] snapshotCodes,
      SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
      OptionalInt foxMoveNumber) {
    Stone[] snapshotStones = buildSnapshotStones(snapshotCodes);
    SnapshotHistoryState historyState =
        inferSnapshotHistoryState(syncStartNode, snapshotStones, snapshotDelta, foxMoveNumber);
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
      OptionalInt foxMoveNumber) {
    int moveNumber =
        inferSnapshotMoveNumber(syncStartNode, snapshotStones, snapshotDelta, foxMoveNumber);
    boolean blackToPlay =
        inferSnapshotBlackToPlay(syncStartNode, snapshotStones, snapshotDelta, foxMoveNumber);
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
      OptionalInt foxMoveNumber) {
    if (foxMoveNumber.isPresent()) {
      return foxMoveNumber.getAsInt() % 2 == 0;
    }
    if (isEmptySnapshot(snapshotStones)) {
      return true;
    }
    if (snapshotDelta.hasMarker()) {
      return snapshotDelta.markerColor() == Stone.WHITE;
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
    if (!isReadBoardAnalysisEngineAvailable()) {
      return;
    }
    BoardData data = rebuiltNode.getData();
    Lizzie.leelaz.clear();
    if (!ExactSnapshotEngineRestore.restoreIfNeeded(Lizzie.leelaz, data)) {
      throw new IllegalStateException("Snapshot rebuild must sync through snapshot data.");
    }
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
    conflictTracker.clear();
    historyJumpTracker.clear();
    waitingForReadBoardLocalMoveAck = false;
    pendingRemoteContext = SyncRemoteContext.generic(false);
    awaitingFirstSyncFrame = true;
    invalidatePendingSyncAnalysisResume();
  }

  private void clearResumeState() {
    invalidatePendingSyncAnalysisResume();
    resumeState = null;
    lastResolvedSnapshotNode = null;
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
    runWithoutTrackingLocalHistoryNavigation(() -> Lizzie.frame.lastMove());
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

  private void acknowledgeLocalMoveIfSnapshotCaughtUp(Stone[] stones, int[] snapshotCodes) {
    if (!Lizzie.frame.bothSync || !lastMovePlayByLizzie) {
      return;
    }
    if (!currentPendingLocalMoveCoordinates().isPresent()) {
      return;
    }
    if (!snapshotDiffChecker().hasDiff(snapshotCodes, stones, false, Optional.empty())) {
      lastMovePlayByLizzie = false;
      waitingForReadBoardLocalMoveAck = false;
    }
  }

  private void startTrackingLocalMoveFromLizzie() {
    lastMovePlayByLizzie = true;
    waitingForReadBoardLocalMoveAck = true;
  }

  private void markLocalMoveCommandCompleted() {
    waitingForReadBoardLocalMoveAck = false;
  }

  private boolean shouldIgnoreCurrentLastLocalMove() {
    return Lizzie.frame.bothSync && lastMovePlayByLizzie && waitingForReadBoardLocalMoveAck;
  }

  private boolean shouldIgnoreCurrentLastLocalMove(Optional<int[]> currentPendingLocalMove) {
    return shouldIgnoreCurrentLastLocalMove() && currentPendingLocalMove.isPresent();
  }

  private Optional<int[]> currentPendingLocalMoveCoordinates() {
    BoardData mainEndData = Lizzie.board.getHistory().getMainEnd().getData();
    if (!mainEndData.isMoveNode()) {
      return Optional.empty();
    }
    return mainEndData.lastMove;
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
    Lizzie.board.placeForSync(move.x, move.y, move.color, false);
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
      return;
    }
    long scheduledEpoch = ++syncAnalysisEpoch;
    Lizzie.frame.scheduleResumeAnalysisAfterLoad(
        SYNC_ANALYSIS_RESUME_DELAY_MS,
        () -> resumeAnalysisAfterSyncIfStillCurrent(scheduledEpoch, targetNode));
  }

  private void resumeAnalysisAfterSyncIfStillCurrent(
      long scheduledEpoch, BoardHistoryNode targetNode) {
    if (scheduledEpoch != syncAnalysisEpoch
        || Lizzie.frame == null
        || Lizzie.board == null
        || Lizzie.config == null
        || !Lizzie.config.readBoardPonder
        || !isReadBoardAnalysisEngineAvailable()) {
      return;
    }
    if (Lizzie.board.getHistory().getCurrentHistoryNode() != targetNode) {
      return;
    }
    Lizzie.frame.ensureAnalysisResumedAfterLoad();
  }

  private boolean isReadBoardAnalysisEngineAvailable() {
    return Lizzie.leelaz != null && Lizzie.leelaz.isStarted();
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
        BoardHistoryNode curNode = Lizzie.board.getHistory().getMainEnd();
        if (curNode.getData().lastMove.isPresent()) {
          int[] lastCoords = curNode.getData().lastMove.get();
          if (lastCoords[0] == x && lastCoords[1] == y) {
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
    noMsg = true;
    resetActiveSyncState();
    clearResumeState();
    clearPendingRemoteContext();
    tempcount = new ArrayList<Integer>();
    if (Lizzie.frame != null) {
      Lizzie.frame.syncBoard = false;
      Lizzie.frame.bothSync = false;
    }
    this.sendCommand("quit");
    releaseHostedResources();
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
    if (command.startsWith("place")) {
      if (hideFloadBoardBeforePlace && Lizzie.frame.floatBoard != null) {
        Lizzie.frame.floatBoard.setVisible(false);
        hideFromPlace = true;
      }
      startTrackingLocalMoveFromLizzie();
      if (Lizzie.frame.isPlayingAgainstLeelaz) needGenmove = true;
    }
    if (usePipe) {
      sendCommandTo(command);
    } else if (readBoardStream != null) readBoardStream.sendCommand(command);
  }

  public void sendLossFocus() {
    // TODO Auto-generated method stub
    if (!Lizzie.config.readBoardGetFocus) return;
    sendCommand("loss");
  }

  public void checkVersion() {
    sendCommand("version");
  }

  public boolean shouldAnnounceHostedUpdateSupport() {
    return usePipe && !javaReadBoard;
  }

  public void announceHostedUpdateSupport() {
    if (shouldAnnounceHostedUpdateSupport()) {
      sendCommand(READBOARD_UPDATE_SUPPORTED_COMMAND);
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
    ReadBoardStream currentReadBoardStream = readBoardStream;
    Socket currentSocket = socket;
    ServerSocket currentServerSocket = s;
    Process currentProcess = process;

    inputStream = null;
    outputStream = null;
    executor = null;
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
