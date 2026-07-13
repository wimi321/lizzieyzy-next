package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.EngineTransport;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.gui.AnalysisSettings;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.EngineFailedMessage;
import featurecat.lizzie.gui.RemoteEngineData;
import featurecat.lizzie.gui.WaitForAnalysis;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Movelist;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.AnalysisEngineCommandHelper;
import featurecat.lizzie.util.CommandLaunchHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.Utils;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.jdesktop.swingx.util.OS;
import org.json.JSONArray;
import org.json.JSONObject;

public class AnalysisEngine {
  private static final int REMOTE_GTP_SILENT_INTERVAL_CENTISEC = 1;
  private static final int REMOTE_GTP_SILENT_MAX_VISITS = 16;
  private static final int REMOTE_GTP_OVERVIEW_STRIDE = 8;
  private static final int REMOTE_GTP_OVERVIEW_THRESHOLD = 24;
  private static final int REMOTE_GTP_STOP_COMMAND_ID_BASE = 810000000;
  private static final int REMOTE_GTP_ANALYZE_COMMAND_ID_BASE = 820000000;
  private static final long REMOTE_GTP_STOP_ACK_TIMEOUT_MILLIS = 1200L;
  private static final long REMOTE_GTP_ANALYZE_STALL_TIMEOUT_MILLIS = 8000L;
  public Process process;
  public boolean isNormalEnd = false;
  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;

  private BufferedReader inputStream;
  private BufferedOutputStream outputStream;
  private BufferedReader errorStream;
  private transient EngineTransport remoteTransport;

  private String engineCommand;
  private ScheduledExecutorService executor;
  private ScheduledExecutorService executorErr;
  private List<String> commands;
  private boolean isPreLoad;
  // private HashMap<Integer, List<MoveData>> resultMap = new HashMap<Integer, List<MoveData>>();
  private HashMap<Integer, BoardHistoryNode> analyzeMap = new HashMap<Integer, BoardHistoryNode>();
  private int globalID;
  private int resultCount;
  // private int analyzeNumberCount;
  // private BoardHistoryNode startAnalyzeNode;
  public WaitForAnalysis waitFrame;

  public boolean useJavaSSH = false;
  public String ip;
  public String port;
  public String userName;
  public String password;
  public boolean useKeyGen;
  public String keyGenPath;
  public AnalysisEngineSSHController javaSSH;
  public boolean javaSSHClosed;
  public boolean useRemoteCompute = false;
  private boolean shouldRePonder = false;
  private boolean isLoaded = false;
  private boolean silentProgress = false;
  private boolean requestDispatchFailed = false;
  private Runnable completionCallback;
  private boolean keepAliveAfterCurrentRequest = false;
  private ArrayDeque<RemoteGtpAnalyzeJob> remoteGtpQueue;
  private RemoteGtpAnalyzeJob remoteGtpActiveJob;
  private boolean remoteGtpWaitingForStopAck;
  private boolean remoteGtpStopSent;
  private int remoteGtpExpectedStopCommandId;
  private int remoteGtpNextStopCommandId = REMOTE_GTP_STOP_COMMAND_ID_BASE;
  private int remoteGtpExpectedAnalyzeCommandId;
  private int remoteGtpNextAnalyzeCommandId = REMOTE_GTP_ANALYZE_COMMAND_ID_BASE;
  private boolean remoteGtpAnalyzeResponseStarted;
  private boolean remoteGtpAnalyzeCompletionReceived;
  private boolean remoteGtpStopAckReceived;
  private RemoteGtpAnalyzeJob remoteGtpStoppingJob;
  private List<MoveData> remoteGtpStoppingMoves;
  private RemoteGtpRootInfo remoteGtpStoppingRootInfo;
  private long remoteGtpStopAckGeneration;
  private long remoteGtpLastCompletionActivityMillis;
  private Leelaz sharedRemoteEngine;
  private boolean sharedRemoteSessionStarting;
  private boolean sharedRemoteSessionActive;

  public AnalysisEngine(boolean isPreLoad) throws IOException {
    this.isPreLoad = isPreLoad;
    int maxVisits =
        Lizzie.frame.isBatchAnalysisMode
            ? Math.max(2, Lizzie.config.batchAnalysisPlayouts)
            : Lizzie.config.analysisMaxVisits + 1;
    engineCommand =
        KataGoRuntimeHelper.optimizeAnalysisEngineCommand(
            resolveConfiguredAnalysisEngineCommand(isPreLoad),
            maxVisits,
            Lizzie.frame.isBatchAnalysisMode);
    RemoteEngineData remoteData = Utils.getAnalysisEngineRemoteEngineData();
    this.useJavaSSH = remoteData.useJavaSSH;
    this.ip = remoteData.ip;
    this.port = remoteData.port;
    this.userName = remoteData.userName;
    this.password = remoteData.password;
    this.useKeyGen = remoteData.useKeyGen;
    this.keyGenPath = remoteData.keyGenPath;
    if (RemoteComputeConfig.isRemoteComputeEngineCommand(engineCommand)) {
      this.useJavaSSH = false;
      this.useRemoteCompute = true;
    }

    if (this.useRemoteCompute && hasCurrentRemoteEngine()) {
      sharedRemoteEngine = Lizzie.leelaz;
      isLoaded = sharedRemoteEngine.isLoaded() && sharedRemoteEngine.isStarted();
      isNormalEnd = false;
      return;
    }

    startEngine(engineCommand);
  }

  private boolean hasCurrentRemoteEngine() {
    Leelaz primary = Lizzie.leelaz;
    return primary != null
        && RemoteComputeConfig.isRemoteComputeEngineCommand(primary.engineCommand());
  }

  private static String resolveConfiguredAnalysisEngineCommand(boolean isPreLoad) {
    int currentIndex = currentMainEngineIndex();
    ArrayList<EngineData> engines = Utils.getEngineData();
    if (currentIndex >= 0 && currentIndex < engines.size()) {
      String command = engines.get(currentIndex).commands;
      if (RemoteComputeConfig.isRemoteComputeEngineCommand(command)) {
        return command;
      }
    }
    if (!Lizzie.config.analysisEngineCommandCustomized) {
      AnalysisEngineCommandHelper.Result result =
          AnalysisEngineCommandHelper.fromCurrentEngine(engines, currentIndex);
      if (result.isSuccess()) {
        Lizzie.config.analysisEngineCommand = result.getCommand();
        Lizzie.config.uiConfig.put("analysis-engine-command", Lizzie.config.analysisEngineCommand);
        if (shouldShowGeneratedConfigNotice(isPreLoad, result.generatedConfig())) {
          javax.swing.SwingUtilities.invokeLater(() -> Utils.showMsg(result.getMessage()));
        }
      }
    }
    return Lizzie.config.analysisEngineCommand;
  }

  static boolean shouldShowGeneratedConfigNotice(boolean isPreLoad, boolean generatedConfig) {
    return !isPreLoad && generatedConfig;
  }

  private static int currentMainEngineIndex() {
    if (Lizzie.leelaz != null && Lizzie.leelaz.currentEngineN() >= 0) {
      return Lizzie.leelaz.currentEngineN();
    }
    return EngineManager.currentEngineNo;
  }

  public void startEngine(String engineCommand) {
    CommandLaunchHelper.LaunchSpec launchSpec =
        CommandLaunchHelper.prepare(Utils.splitCommand(engineCommand));
    commands = launchSpec.getCommandParts();
    this.useRemoteCompute = RemoteComputeConfig.isRemoteComputeEngineCommand(engineCommand);
    if (this.useRemoteCompute) {
      this.useJavaSSH = false;
      process = null;
      try {
        remoteTransport = RemoteComputeConfig.createTransportForCommand(engineCommand);
        remoteTransport.start();
        initializeStreams(remoteTransport.stdout(), remoteTransport.stdin(), remoteTransport.stderr());
        isLoaded = true;
      } catch (IOException e) {
        showErrMsg(
            resourceBundle.getString("Leelaz.engineFailed")
                + ": "
                + (e.getLocalizedMessage() == null
                    ? "远程算力连接失败"
                    : e.getLocalizedMessage()));
        isLoaded = false;
        return;
      }
    } else if (this.useJavaSSH) {
      this.javaSSH = new AnalysisEngineSSHController(this, this.ip, this.port, this.isPreLoad);
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
        this.inputStream = new BufferedReader(new InputStreamReader(this.javaSSH.getStdout()));
        this.outputStream = new BufferedOutputStream(this.javaSSH.getStdin());
        this.errorStream = new BufferedReader(new InputStreamReader(this.javaSSH.getSterr()));
        javaSSHClosed = false;
        isLoaded = true;
      } else {
        javaSSHClosed = true;
        isLoaded = false;
        return;
      }
    } else {
      Path engineExecutable = KataGoRuntimeHelper.resolveCommandExecutable(commands);
      if (Config.isBundledKataGoCommand(engineCommand)) {
        try {
          KataGoRuntimeHelper.ensureBundledRuntimeReady(engineExecutable, Lizzie.frame);
        } catch (IOException e) {
          showErrMsg(
              resourceBundle.getString("Leelaz.engineFailed") + ": " + e.getLocalizedMessage());
          process = null;
          isLoaded = false;
          return;
        }
      }
      List<String> launchCommands =
          KataGoRuntimeHelper.prepareBundledLaunchCommand(commands, engineExecutable);
      ProcessBuilder processBuilder = new ProcessBuilder(launchCommands);
      CommandLaunchHelper.configureProcessBuilder(processBuilder, launchSpec);
      KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, engineExecutable);
      processBuilder.redirectErrorStream(true);
      try {
        process = processBuilder.start();
        isLoaded = true;
      } catch (IOException e) {
        // TODO Auto-generated catch block
        showErrMsg(
            resourceBundle.getString("Leelaz.engineFailed") + ": " + e.getLocalizedMessage());
        process = null;
        isLoaded = false;
        return;
      }
      initializeStreams();
    }
    executor = Executors.newSingleThreadScheduledExecutor();
    executor.execute(this::read);
    executorErr = Executors.newSingleThreadScheduledExecutor();
    executorErr.execute(this::readError);
    isNormalEnd = false;
  }

  private void showErrMsg(String errMsg) {
    if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
      javax.swing.SwingUtilities.invokeLater(() -> showErrMsg(errMsg));
      return;
    }
    if (isPreLoad) return;
    if (waitFrame != null) waitFrame.setVisible(false);
    tryToDignostic(errMsg);
    AnalysisSettings analysisSettings = new AnalysisSettings(true, true);
    analysisSettings.setVisible(true);
  }

  public void tryToDignostic(String message) {
    EngineFailedMessage engineFailedMessage =
        new EngineFailedMessage(
            commands,
            engineCommand,
            message,
            !useJavaSSH && !useRemoteCompute && OS.isWindows(),
            false,
            false);
    engineFailedMessage.setModal(true);
    engineFailedMessage.setVisible(true);
  }

  private void initializeStreams() {
    initializeStreams(process.getInputStream(), process.getOutputStream(), process.getErrorStream());
  }

  private void initializeStreams(InputStream stdout, OutputStream stdin, InputStream stderr) {
    inputStream = new BufferedReader(new InputStreamReader(stdout));
    outputStream = new BufferedOutputStream(stdin);
    errorStream = new BufferedReader(new InputStreamReader(stderr));
  }

  private void readError() {
    String line = "";

    try {
      while ((line = errorStream.readLine()) != null) {
        try {
          parseLineForError(line);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  private void parseLineForError(String line) {
    Lizzie.gtpConsole.addErrorLine(line + "\n");
  }

  private void read() {
    try {
      String line = "";
      // while ((c = inputStream.read()) != -1) {
      while ((line = inputStream.readLine()) != null) {
        try {
          parseLine(line.toString());
        } catch (Exception ex) {
          ex.printStackTrace();
        }
      }
      // this line will be reached when engine shuts down
      if (this.useJavaSSH) javaSSHClosed = true;
      if (this.useRemoteCompute && remoteTransport != null) remoteTransport.close();
      System.out.println("Flash analyze process ended.");
      // Do no exit for switching weights
      // System.exit(-1);
    } catch (IOException e) {
    }
    if (this.useJavaSSH) javaSSHClosed = true;
    isLoaded = false;
    if (!isNormalEnd) {
      showErrMsg(resourceBundle.getString("Leelaz.engineEndUnormalHint"));
    }
    process = null;
    shutdown();
    finishAbortedAnalysis();
    return;
  }

  private void parseLine(String line) {
    synchronized (this) {
      if (useRemoteCompute && handleRemoteGtpLine(line)) {
        return;
      }
      if (line.startsWith("{")) {
        try {
          parseResult(line);
        } catch (Exception e) {
          e.printStackTrace();
        }
      } else Lizzie.gtpConsole.addLine(line);
    }
  }

  public void parseResult(String line) {
    JSONObject result;
    result = new JSONObject(line);
    JSONArray moveInfos = result.getJSONArray("moveInfos");
    int id = Integer.parseInt(result.getString("id"));
    BoardHistoryNode node = analyzeMap.get(id);
    if (node == null) return;
    if (shouldKeepForegroundAnalysis(node)) {
      resultCount++;
      if (resultCount == analyzeMap.size()) setResult();
      return;
    }
    List<MoveData> moves = Utils.getBestMovesFromJsonArray(moveInfos, true, true);
    ArrayList<Double> ownershipArray = null;
    if (result.has("ownership")) {
      JSONArray ownership = result.getJSONArray("ownership");
      List<Object> list = ownership.toList();
      ownershipArray = (ArrayList<Double>) (List) list;
    }
    applyAnalysisPayload(node, moves, ownershipArray);
    resultCount++;
    Lizzie.frame.requestProblemListRefresh();
    if (waitFrame != null) {
      waitFrame.setProgress(resultCount, analyzeMap.size());
    } else if (silentProgress && shouldRefreshSilentProgress(resultCount, analyzeMap.size())) {
      Lizzie.board.setMovelistAll();
      Lizzie.frame.refresh();
    }
    if (resultCount == analyzeMap.size()) setResult();
  }

  private boolean handleRemoteGtpLine(String line) {
    String trimmed = line == null ? "" : line.trim();
    if (remoteGtpWaitingForStopAck) {
      if (trimmed.isEmpty()) {
        if (remoteGtpAnalyzeResponseStarted && !remoteGtpAnalyzeCompletionReceived) {
          remoteGtpLastCompletionActivityMillis = System.currentTimeMillis();
          remoteGtpAnalyzeCompletionReceived = true;
          finishRemoteGtpJobIfComplete();
          if (remoteGtpWaitingForStopAck) {
            scheduleRemoteGtpStopAckFallback();
          }
        }
        return true;
      }
      if (trimmed.startsWith("info ")) {
        remoteGtpLastCompletionActivityMillis = System.currentTimeMillis();
        if (!remoteGtpAnalyzeCompletionReceived) {
          rememberRemoteGtpStoppingResult(trimmed);
        }
        return true;
      }
      if (isExpectedRemoteGtpAnalyzeResponse(trimmed)) {
        remoteGtpLastCompletionActivityMillis = System.currentTimeMillis();
        remoteGtpAnalyzeResponseStarted = true;
      } else if (isExpectedRemoteGtpStopResponse(trimmed)) {
        remoteGtpLastCompletionActivityMillis = System.currentTimeMillis();
        remoteGtpStopAckReceived = true;
        finishRemoteGtpJobIfComplete();
      }
      return trimmed.startsWith("info ") || trimmed.startsWith("=") || trimmed.startsWith("?");
    }
    RemoteGtpAnalyzeJob job = remoteGtpActiveJob;
    if (trimmed.isEmpty()) {
      if (remoteGtpAnalyzeResponseStarted) {
        requestDispatchFailed = true;
        finishAbortedAnalysis();
        return true;
      }
      return false;
    }
    if (isExpectedRemoteGtpAnalyzeResponse(trimmed)) {
      remoteGtpAnalyzeResponseStarted = true;
      return true;
    }
    if (job == null || !trimmed.startsWith("info ")) {
      return false;
    }
    List<MoveData> moves = parseRemoteGtpInfo(trimmed);
    RemoteGtpRootInfo rootInfo = parseRemoteGtpRootInfo(trimmed);
    int playouts = rootInfo != null ? rootInfo.visits : MoveData.getPlayouts(moves);
    if (moves.isEmpty() || playouts <= 0) {
      return true;
    }
    if (playouts < job.targetVisits) {
      return true;
    }
    completeRemoteGtpJob(job, moves, rootInfo);
    return true;
  }

  private List<MoveData> parseRemoteGtpInfo(String line) {
    String payload = line.startsWith("info ") ? line.substring("info ".length()) : line;
    int rootInfoIndex = payload.indexOf(" rootInfo ");
    if (rootInfoIndex >= 0) {
      payload = payload.substring(0, rootInfoIndex);
    }
    List<MoveData> moves = new ArrayList<MoveData>();
    String[] variations = payload.split(" info ");
    for (String variation : variations) {
      if (variation == null || variation.trim().isEmpty()) {
        continue;
      }
      try {
        moves.add(MoveData.fromInfoKatago(variation));
      } catch (RuntimeException ignored) {
      }
    }
    return moves;
  }

  private RemoteGtpRootInfo parseRemoteGtpRootInfo(String line) {
    if (line == null) {
      return null;
    }
    int rootInfoIndex = line.indexOf(" rootInfo ");
    if (rootInfoIndex < 0) {
      return null;
    }
    String[] tokens = line.substring(rootInfoIndex + " rootInfo ".length()).trim().split("\\s+");
    int visits = -1;
    double winrate = Double.NaN;
    double scoreLead = Double.NaN;
    for (int i = 0; i < tokens.length - 1; i++) {
      String key = tokens[i];
      String value = tokens[i + 1];
      try {
        if ("visits".equals(key)) {
          visits = Integer.parseInt(value);
          i++;
        } else if ("winrate".equals(key)) {
          winrate = Double.parseDouble(value) * 100.0;
          i++;
        } else if ("scoreLead".equals(key) || "scoreMean".equals(key)) {
          scoreLead = Double.parseDouble(value);
          i++;
        }
      } catch (NumberFormatException ignored) {
      }
    }
    if (visits <= 0 || !Double.isFinite(winrate) || !Double.isFinite(scoreLead)) {
      return null;
    }
    return new RemoteGtpRootInfo(
        visits, Math.max(0.0, Math.min(100.0, winrate)), scoreLead);
  }

  private void completeRemoteGtpJob(
      RemoteGtpAnalyzeJob job, List<MoveData> moves, RemoteGtpRootInfo rootInfo) {
    if (remoteGtpStopSent) {
      return;
    }
    remoteGtpStopSent = true;
    remoteGtpActiveJob = null;
    remoteGtpStoppingJob = job;
    remoteGtpStoppingMoves = moves;
    remoteGtpStoppingRootInfo = rootInfo;
    int stopCommandId = nextRemoteGtpStopCommandId();
    remoteGtpExpectedStopCommandId = stopCommandId;
    remoteGtpAnalyzeCompletionReceived = false;
    remoteGtpStopAckReceived = false;
    remoteGtpLastCompletionActivityMillis = System.currentTimeMillis();
    remoteGtpWaitingForStopAck = true;
    if (sendCommand(stopCommandId + " stop")) {
      if (remoteGtpWaitingForStopAck) {
        scheduleRemoteGtpStopAckFallback();
      }
    } else {
      remoteGtpWaitingForStopAck = false;
      requestDispatchFailed = true;
      finishAbortedAnalysis();
    }
  }

  private void finishRemoteGtpJobIfComplete() {
    if (!remoteGtpWaitingForStopAck
        || !remoteGtpAnalyzeCompletionReceived
        || !remoteGtpStopAckReceived) {
      return;
    }
    remoteGtpWaitingForStopAck = false;
    finishRemoteGtpJobAfterStopAck();
  }

  private boolean isExpectedRemoteGtpStopResponse(String line) {
    return isExpectedRemoteGtpResponse(line, remoteGtpExpectedStopCommandId);
  }

  private boolean isExpectedRemoteGtpAnalyzeResponse(String line) {
    return isExpectedRemoteGtpResponse(line, remoteGtpExpectedAnalyzeCommandId);
  }

  private static boolean isExpectedRemoteGtpResponse(String line, int commandId) {
    if (commandId <= 0 || line == null) {
      return false;
    }
    String successPrefix = "=" + commandId;
    String errorPrefix = "?" + commandId;
    return line.equals(successPrefix)
        || line.startsWith(successPrefix + " ")
        || line.equals(errorPrefix)
        || line.startsWith(errorPrefix + " ");
  }

  private int nextRemoteGtpStopCommandId() {
    if (remoteGtpNextStopCommandId < REMOTE_GTP_STOP_COMMAND_ID_BASE) {
      remoteGtpNextStopCommandId = REMOTE_GTP_STOP_COMMAND_ID_BASE;
    }
    return remoteGtpNextStopCommandId++;
  }

  private int nextRemoteGtpAnalyzeCommandId() {
    if (remoteGtpNextAnalyzeCommandId < REMOTE_GTP_ANALYZE_COMMAND_ID_BASE) {
      remoteGtpNextAnalyzeCommandId = REMOTE_GTP_ANALYZE_COMMAND_ID_BASE;
    }
    return remoteGtpNextAnalyzeCommandId++;
  }

  private void rememberRemoteGtpStoppingResult(String line) {
    if (remoteGtpStoppingJob == null) {
      return;
    }
    List<MoveData> moves = parseRemoteGtpInfo(line);
    if (!moves.isEmpty() && MoveData.getPlayouts(moves) > 0) {
      remoteGtpStoppingMoves = moves;
    }
    RemoteGtpRootInfo rootInfo = parseRemoteGtpRootInfo(line);
    if (rootInfo != null) {
      remoteGtpStoppingRootInfo = rootInfo;
    }
  }

  private void finishRemoteGtpJobAfterStopAck() {
    remoteGtpStopSent = false;
    remoteGtpWaitingForStopAck = false;
    remoteGtpExpectedStopCommandId = 0;
    remoteGtpExpectedAnalyzeCommandId = 0;
    remoteGtpAnalyzeResponseStarted = false;
    remoteGtpAnalyzeCompletionReceived = false;
    remoteGtpStopAckReceived = false;
    if (!commitRemoteGtpStoppingResult()) {
      requestDispatchFailed = true;
      finishAbortedAnalysis();
      return;
    }
    if (resultCount == analyzeMap.size()) {
      setResult();
      return;
    }
    startNextRemoteGtpJob();
  }

  private boolean commitRemoteGtpStoppingResult() {
    RemoteGtpAnalyzeJob job = remoteGtpStoppingJob;
    List<MoveData> moves = remoteGtpStoppingMoves;
    RemoteGtpRootInfo rootInfo = remoteGtpStoppingRootInfo;
    remoteGtpStoppingJob = null;
    remoteGtpStoppingMoves = null;
    remoteGtpStoppingRootInfo = null;
    if (job == null || moves == null || moves.isEmpty()) {
      return false;
    }
    applyAnalysisPayload(job.node, moves, null);
    if (rootInfo != null) {
      BoardData data = job.node.getData();
      data.winrate = rootInfo.winrate;
      data.scoreMean = rootInfo.scoreLead;
      data.setPlayouts(rootInfo.visits);
      data.comment = SGFParser.formatComment(job.node);
      Lizzie.board.updateMovelist(job.node);
    }
    resultCount++;
    Lizzie.frame.requestProblemListRefresh();
    if (waitFrame != null) {
      waitFrame.setProgress(resultCount, analyzeMap.size());
    } else if (silentProgress && shouldRefreshSilentProgress(resultCount, analyzeMap.size())) {
      Lizzie.board.setMovelistAll();
      Lizzie.frame.refresh();
    }
    return true;
  }

  private void scheduleRemoteGtpStopAckFallback() {
    long timeoutMillis =
        remoteGtpAnalyzeCompletionReceived
            ? REMOTE_GTP_STOP_ACK_TIMEOUT_MILLIS
            : REMOTE_GTP_ANALYZE_STALL_TIMEOUT_MILLIS;
    scheduleRemoteGtpStopAckFallback(timeoutMillis);
  }

  private void scheduleRemoteGtpStopAckFallback(long delayMillis) {
    long generation = ++remoteGtpStopAckGeneration;
    Thread watchdog =
        new Thread(
            () -> {
              try {
                Thread.sleep(Math.max(1L, delayMillis));
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
              }
              synchronized (AnalysisEngine.this) {
                if (!remoteGtpWaitingForStopAck
                    || !remoteGtpStopSent
                    || generation != remoteGtpStopAckGeneration) {
                  return;
                }
                long idleMillis =
                    System.currentTimeMillis() - remoteGtpLastCompletionActivityMillis;
                long currentTimeoutMillis =
                    remoteGtpAnalyzeCompletionReceived
                        ? REMOTE_GTP_STOP_ACK_TIMEOUT_MILLIS
                        : REMOTE_GTP_ANALYZE_STALL_TIMEOUT_MILLIS;
                if (idleMillis < currentTimeoutMillis) {
                  scheduleRemoteGtpStopAckFallback(currentTimeoutMillis - idleMillis);
                  return;
                }
                if (remoteGtpAnalyzeCompletionReceived) {
                  remoteGtpStopAckReceived = true;
                  finishRemoteGtpJobIfComplete();
                } else {
                  requestDispatchFailed = true;
                  finishAbortedAnalysis();
                }
              }
            },
            "remote-gtp-stop-ack-watchdog");
    watchdog.setDaemon(true);
    watchdog.start();
  }

  private void applyAnalysisPayload(
      BoardHistoryNode node, List<MoveData> moves, ArrayList<Double> ownershipArray) {
    node.getData()
        .tryToSetBestMoves(
            moves,
            resourceBundle.getString("AnalysisEngine.flashAnalyze"),
            false,
            MoveData.getPlayouts(moves),
            ownershipArray,
            Lizzie.config.analysisAlwaysOverride);
    node.getData().comment = SGFParser.formatComment(node);
    Lizzie.board.updateMovelist(node);
  }

  private static boolean shouldRefreshSilentProgress(int resultCount, int totalCount) {
    if (resultCount <= 0) {
      return false;
    }
    if (resultCount <= 12) {
      return true;
    }
    if (resultCount <= 64) {
      return resultCount % 4 == 0;
    }
    return resultCount % 8 == 0 || resultCount == totalCount;
  }

  private boolean shouldKeepForegroundAnalysis(BoardHistoryNode node) {
    return silentProgress
        && Lizzie.leelaz != null
        && (Lizzie.leelaz.isPondering() || Lizzie.leelaz.isThinking)
        && Lizzie.board != null
        && Lizzie.board.getHistory() != null
        && node == Lizzie.board.getHistory().getCurrentHistoryNode();
  }

  private void setResult() {
    Lizzie.board.clearPkBoardStat();
    Lizzie.board.isKataBoard = true;
    boolean oriEnableLizzieCache = Lizzie.config.enableLizzieCache;
    if (Lizzie.config.analysisAlwaysOverride) {
      Lizzie.config.enableLizzieCache = false;
    }
    try {
      Lizzie.board.setMovelistAll();
      if (Lizzie.board.getHistory().getCurrentHistoryNode() == Lizzie.board.getHistory().getStart())
        Lizzie.board.nextMove(true);
      Lizzie.frame.refresh();
      Lizzie.frame.requestProblemListRefresh();
      boolean shouldKeepAlive = isPreLoad || keepAliveAfterCurrentRequest;
      keepAliveAfterCurrentRequest = false;
      if (Lizzie.config.analysisAutoQuit && !Lizzie.frame.isBatchAna && !shouldKeepAlive) {
        normalQuit();
      }
    } finally {
      if (Lizzie.config.analysisAlwaysOverride)
        Lizzie.config.enableLizzieCache = oriEnableLizzieCache;
    }
    releaseSharedRemoteSession();
    if (shouldRePonder && !Lizzie.leelaz.isPondering()) Lizzie.leelaz.togglePonder();
    Lizzie.frame.renderVarTree(0, 0, false, false);
    runCompletionCallback();
  }

  public void normalQuit() {
    // TODO Auto-generated method stub
    isNormalEnd = true;
    releaseSharedRemoteSession();
    if (this.useJavaSSH) {
      if (this.javaSSH != null) this.javaSSH.close();
    } else if (this.useRemoteCompute) {
      if (this.remoteTransport != null) this.remoteTransport.close();
      if (executor != null) executor.shutdownNow();
      if (executorErr != null) executorErr.shutdownNow();
    } else if (this.process != null) this.process.destroyForcibly();
    Lizzie.frame.requestProblemListRefresh();
  }

  private synchronized void finishAbortedAnalysis() {
    releaseSharedRemoteSession();
    if (analyzeMap.isEmpty() && completionCallback == null) {
      return;
    }
    analyzeMap.clear();
    remoteGtpQueue().clear();
    remoteGtpActiveJob = null;
    remoteGtpWaitingForStopAck = false;
    remoteGtpStopSent = false;
    remoteGtpExpectedStopCommandId = 0;
    remoteGtpExpectedAnalyzeCommandId = 0;
    remoteGtpAnalyzeResponseStarted = false;
    remoteGtpAnalyzeCompletionReceived = false;
    remoteGtpStopAckReceived = false;
    remoteGtpStoppingJob = null;
    remoteGtpStoppingMoves = null;
    remoteGtpStoppingRootInfo = null;
    resultCount = 0;
    runCompletionCallback();
  }

  public void startRequestAllBranches() {
    startRequestAllBranches(true);
  }

  public void startRequestAllBranches(boolean showProgressDialog) {
    if (!isLoaded) return;
    prepareRequestState(showProgressDialog);
    BoardHistoryNode node = Lizzie.board.getHistory().getStart();
    Stack<BoardHistoryNode> stack = new Stack<>();
    stack.push(node);
    while (!stack.isEmpty()) {
      BoardHistoryNode cur = stack.pop();
      if (shouldAnalyzeBranchNode(cur)) {
        if (!sendRequest(cur)) break;
      }
      if (cur.numberOfChildren() >= 1) {
        for (int i = cur.numberOfChildren() - 1; i >= 0; i--)
          stack.push(cur.getVariations().get(i));
      }
    }
    showRequestProgressOrContinueBatch(showProgressDialog);
  }

  public void startRequest(int startMove, int endMove) {
    startRequest(startMove, endMove, true);
  }

  public void startRequest(int startMove, int endMove, boolean showProgressDialog) {
    if (!isLoaded) return;
    prepareRequestState(showProgressDialog);
    int targetVisits = targetAnalysisVisitsForCurrentRequest(showProgressDialog);
    if (useRemoteCompute) {
      enqueueRemoteGtpMainlineRequests(
          targetVisits,
          (node, moveNum) ->
              shouldAnalyzeTurn(moveNum, startMove, endMove) && shouldAnalyzeNode(node, targetVisits));
      showRequestProgressOrContinueBatch(showProgressDialog);
      return;
    }
    BoardHistoryNode node = firstHistoryActionNode(Lizzie.board.getHistory().getStart());
    int moveNum = 1;
    while (node != null) {
      if (shouldAnalyzeTurn(moveNum, startMove, endMove)
          && shouldAnalyzeNode(node, targetVisits)) {
        if (!sendRequest(node)) break;
      }
      node = nextHistoryActionNode(node);
      moveNum++;
    }
    showRequestProgressOrContinueBatch(showProgressDialog);
  }

  public int startRequestMissingMainline(boolean showProgressDialog) {
    if (!isLoaded) return 0;
    prepareRequestState(showProgressDialog);
    if (useRemoteCompute) {
      enqueueRemoteGtpMainlineRequests(
          targetAnalysisVisitsForCurrentRequest(showProgressDialog),
          (node, moveNum) -> shouldAnalyzeMissingNode(node));
    } else {
      BoardHistoryNode node = firstHistoryActionNode(Lizzie.board.getHistory().getStart());
      while (node != null) {
        if (shouldAnalyzeMissingNode(node)) {
          if (!sendRequest(node)) break;
        }
        node = nextHistoryActionNode(node);
      }
    }
    int requestCount = analyzeMap.size();
    boolean dispatchFailed = requestDispatchFailed;
    showRequestProgressOrContinueBatch(showProgressDialog);
    if (dispatchFailed) {
      return -1;
    }
    if (requestCount == 0 && shouldRePonder && !Lizzie.leelaz.isPondering()) {
      Lizzie.leelaz.togglePonder();
    }
    return requestCount;
  }

  private void prepareRequestState(boolean showProgressDialog) {
    analyzeMap.clear();
    remoteGtpQueue().clear();
    remoteGtpActiveJob = null;
    remoteGtpWaitingForStopAck = false;
    remoteGtpStopSent = false;
    remoteGtpExpectedStopCommandId = 0;
    remoteGtpExpectedAnalyzeCommandId = 0;
    remoteGtpAnalyzeResponseStarted = false;
    remoteGtpAnalyzeCompletionReceived = false;
    remoteGtpStopAckReceived = false;
    remoteGtpStoppingJob = null;
    remoteGtpStoppingMoves = null;
    remoteGtpStoppingRootInfo = null;
    remoteGtpStopAckGeneration++;
    if (globalID <= 0) globalID = 1;
    resultCount = 0;
    requestDispatchFailed = false;
    silentProgress = !showProgressDialog;
    if (!showProgressDialog) waitFrame = null;
    if (showProgressDialog && Lizzie.leelaz.isPondering()) {
      Lizzie.leelaz.togglePonder();
      shouldRePonder = true;
    } else shouldRePonder = false;
  }

  private void showRequestProgressOrContinueBatch(boolean showProgressDialog) {
    if (requestDispatchFailed) {
      finishFailedRequestDispatch(showProgressDialog);
    } else if (analyzeMap.size() > 0) {
      Lizzie.frame.requestProblemListRefresh();
      if (showProgressDialog) {
        if (waitFrame == null) waitFrame = new WaitForAnalysis();
        waitFrame.setProgress(0, analyzeMap.size());
        waitFrame.setLocationRelativeTo(Lizzie.frame != null ? Lizzie.frame : null);
        if (!waitFrame.isVisible()) waitFrame.setVisible(true);
      }
    } else if (Lizzie.frame.isBatchAnalysisMode) {
      Lizzie.frame.flashAutoAnaSaveAndLoad();
    }
  }

  private void finishFailedRequestDispatch(boolean showProgressDialog) {
    analyzeMap.clear();
    resultCount = 0;
    completionCallback = null;
    requestDispatchFailed = false;
    if (shouldRePonder && !Lizzie.leelaz.isPondering()) Lizzie.leelaz.togglePonder();
    if (Lizzie.frame.isBatchAnalysisMode) Lizzie.frame.isBatchAnalysisMode = false;
    if (waitFrame != null || showProgressDialog) {
      javax.swing.SwingUtilities.invokeLater(
          () -> {
            if (waitFrame != null) waitFrame.setVisible(false);
            if (showProgressDialog) {
              Utils.showMsg(resourceBundle.getString("AnalysisEngine.requestDispatchFailed"));
            }
          });
    }
  }

  public boolean sendRequest(BoardHistoryNode analyzeNode) {
    if (useRemoteCompute) {
      return sendRemoteGtpRequest(analyzeNode);
    }
    JSONObject request = new JSONObject();
    int maxVisits = targetAnalysisVisits();
    request.put("id", String.valueOf(globalID));
    request.put("maxVisits", maxVisits);
    request.put("includePVVisits", Lizzie.config.showPvVisits);
    request.put("includeOwnership", Lizzie.config.showKataGoEstimate);
    request.put(
        "includeMovesOwnership",
        Lizzie.config.showKataGoEstimate && Lizzie.config.useMovesOwnership);
    BoardHistoryNode snapshotAnchor = findSnapshotAnchor(analyzeNode);
    BoardHistoryNode initialStateAnchor = resolveInitialStateAnchor(snapshotAnchor);
    ArrayList<String[]> moveList = collectHistoryActions(analyzeNode, snapshotAnchor);
    ArrayList<String[]> initialStoneList = collectInitialStones(initialStateAnchor);
    if (!initialStoneList.isEmpty()) {
      request.put("initialStones", initialStoneList);
    }
    String initialPlayer = collectInitialPlayer(initialStateAnchor);
    if (initialPlayer != null) {
      request.put("initialPlayer", initialPlayer);
    }
    JSONObject ruleSettings;
    if (!Lizzie.config.analysisUseCurrentRules) {
      if (!Lizzie.config.analysisSpecificRules.equals("")) {
        ruleSettings = new JSONObject(Lizzie.config.analysisSpecificRules);
        request.put("rules", ruleSettings);
      } else request.put("rules", "tromp-taylor");
    } else if (!Lizzie.config.currentKataGoRules.equals("")) {
      ruleSettings = new JSONObject(new String(Lizzie.config.currentKataGoRules.substring(2)));
      request.put("rules", ruleSettings);
    } else if (Lizzie.config.autoLoadKataRules && !Lizzie.config.kataRules.equals("")) {
      ruleSettings = new JSONObject(Lizzie.config.kataRules);
      request.put("rules", ruleSettings);
    } else request.put("rules", "tromp-taylor");
    request.put("komi", Lizzie.board.getHistory().getGameInfo().getKomi());
    request.put("boardXSize", Board.boardWidth);
    request.put("boardYSize", Board.boardHeight);
    ArrayList<Integer> moveTurns = new ArrayList<Integer>();
    moveTurns.add(moveList.size());
    request.put("moves", moveList);
    request.put("analyzeTurns", moveTurns);
    JSONObject overrideSettings = new JSONObject();
    overrideSettings.put("reportAnalysisWinratesAs", "SIDETOMOVE");
    request.put("overrideSettings", overrideSettings);
    if (sendCommand(request.toString())) {
      analyzeMap.put(globalID, analyzeNode);
      globalID++;
      return true;
    }
    requestDispatchFailed = true;
    return false;
  }

  private boolean sendRemoteGtpRequest(BoardHistoryNode analyzeNode) {
    int requestId =
        enqueueRemoteGtpRequest(
            analyzeNode,
            Math.max(1, targetAnalysisVisits()),
            buildRemoteGtpSetupCommands(analyzeNode));
    if (remoteGtpActiveJob == null && !remoteGtpWaitingForStopAck) {
      if (!startNextRemoteGtpJob()) {
        analyzeMap.remove(requestId);
        requestDispatchFailed = true;
        return false;
      }
    }
    return true;
  }

  private void enqueueRemoteGtpMainlineRequests(
      int targetVisits, RemoteGtpMainlineSelector selector) {
    List<BoardHistoryNode> selectedNodes = new ArrayList<BoardHistoryNode>();
    BoardHistoryNode node = firstHistoryActionNode(Lizzie.board.getHistory().getStart());
    int moveNum = 1;
    while (node != null) {
      if (selector.shouldAnalyze(node, moveNum)) {
        selectedNodes.add(node);
      }
      node = nextHistoryActionNode(node);
      moveNum++;
    }
    List<BoardHistoryNode> orderedNodes = orderRemoteGtpMainlineNodes(selectedNodes);
    BoardHistoryNode previousQueuedNode = null;
    for (BoardHistoryNode selectedNode : orderedNodes) {
      List<String> commands =
          previousQueuedNode == null
              ? buildRemoteGtpSetupCommands(selectedNode)
              : buildRemoteGtpIncrementalCommands(previousQueuedNode, selectedNode);
      if (commands == null) {
        commands = buildRemoteGtpSetupCommands(selectedNode);
      }
      enqueueRemoteGtpRequest(selectedNode, Math.max(1, targetVisits), commands);
      previousQueuedNode = selectedNode;
    }
    if (!remoteGtpQueue().isEmpty() && !startNextRemoteGtpJob()) {
      requestDispatchFailed = true;
    }
  }

  private List<BoardHistoryNode> orderRemoteGtpMainlineNodes(List<BoardHistoryNode> selectedNodes) {
    if (!silentProgress
        || !useRemoteCompute
        || selectedNodes.size() < REMOTE_GTP_OVERVIEW_THRESHOLD) {
      return selectedNodes;
    }
    Set<BoardHistoryNode> ordered = new LinkedHashSet<BoardHistoryNode>();
    for (int i = 0; i < selectedNodes.size(); i++) {
      int oneBasedIndex = i + 1;
      if (i == 0
          || i == selectedNodes.size() - 1
          || oneBasedIndex % REMOTE_GTP_OVERVIEW_STRIDE == 0) {
        ordered.add(selectedNodes.get(i));
      }
    }
    ordered.addAll(selectedNodes);
    return new ArrayList<BoardHistoryNode>(ordered);
  }

  private int enqueueRemoteGtpRequest(
      BoardHistoryNode analyzeNode, int targetVisits, List<String> commands) {
    int requestId = globalID++;
    analyzeMap.put(requestId, analyzeNode);
    remoteGtpQueue()
        .addLast(
            new RemoteGtpAnalyzeJob(
                requestId, analyzeNode, Math.max(1, targetVisits), commands));
    return requestId;
  }

  private boolean startNextRemoteGtpJob() {
    if (remoteGtpActiveJob != null || remoteGtpWaitingForStopAck) {
      return true;
    }
    if (sharedRemoteEngine != null && !sharedRemoteSessionActive) {
      return beginSharedRemoteSession();
    }
    RemoteGtpAnalyzeJob job = remoteGtpQueue().pollFirst();
    if (job == null) {
      return true;
    }
    remoteGtpActiveJob = job;
    remoteGtpStopSent = false;
    remoteGtpAnalyzeResponseStarted = false;
    remoteGtpAnalyzeCompletionReceived = false;
    remoteGtpStopAckReceived = false;
    int analyzeCommandId = nextRemoteGtpAnalyzeCommandId();
    remoteGtpExpectedAnalyzeCommandId = analyzeCommandId;
    boolean analyzeCommandSent = false;
    for (String command : job.commands) {
      String commandToSend = command;
      if (command.startsWith("kata-analyze ")) {
        if (analyzeCommandSent) {
          remoteGtpActiveJob = null;
          remoteGtpExpectedAnalyzeCommandId = 0;
          requestDispatchFailed = true;
          return false;
        }
        analyzeCommandSent = true;
        commandToSend = analyzeCommandId + " " + command;
      }
      if (!sendCommand(commandToSend)) {
        remoteGtpActiveJob = null;
        remoteGtpExpectedAnalyzeCommandId = 0;
        requestDispatchFailed = true;
        return false;
      }
    }
    if (!analyzeCommandSent) {
      remoteGtpActiveJob = null;
      remoteGtpExpectedAnalyzeCommandId = 0;
      requestDispatchFailed = true;
      return false;
    }
    return true;
  }

  private boolean beginSharedRemoteSession() {
    if (sharedRemoteSessionActive || sharedRemoteSessionStarting) {
      return true;
    }
    if (sharedRemoteEngine == null || !sharedRemoteEngine.isLoaded() || !sharedRemoteEngine.isStarted()) {
      return false;
    }
    sharedRemoteSessionStarting = true;
    boolean started =
        sharedRemoteEngine.beginExclusiveRemoteGtpSession(
            this::parseLine,
            () -> {
              synchronized (AnalysisEngine.this) {
                if (!sharedRemoteSessionStarting) {
                  return;
                }
                sharedRemoteSessionStarting = false;
                sharedRemoteSessionActive = true;
                if (!startNextRemoteGtpJob()) {
                  requestDispatchFailed = true;
                  finishAbortedAnalysis();
                }
              }
            },
            () -> {
              synchronized (AnalysisEngine.this) {
                sharedRemoteSessionStarting = false;
                sharedRemoteSessionActive = false;
                finishAbortedAnalysis();
              }
            });
    if (!started) {
      sharedRemoteSessionStarting = false;
    }
    return started;
  }

  private void releaseSharedRemoteSession() {
    Leelaz engine = sharedRemoteEngine;
    sharedRemoteSessionStarting = false;
    sharedRemoteSessionActive = false;
    if (engine != null) {
      engine.endExclusiveRemoteGtpSession();
    }
  }

  private List<String> buildRemoteGtpSetupCommands(BoardHistoryNode analyzeNode) {
    List<String> commands = new ArrayList<String>();
    if (Board.boardWidth == Board.boardHeight) {
      commands.add("boardsize " + Board.boardWidth);
    } else {
      commands.add("rectangular_boardsize " + Board.boardWidth + " " + Board.boardHeight);
    }
    commands.add("komi " + Lizzie.board.getHistory().getGameInfo().getKomi());
    commands.add("kata-set-rules " + remoteGtpRules());
    commands.add("clear_board");
    BoardHistoryNode snapshotAnchor = findSnapshotAnchor(analyzeNode);
    BoardHistoryNode initialStateAnchor = resolveInitialStateAnchor(snapshotAnchor);
    for (String[] stone : collectInitialStones(initialStateAnchor)) {
      commands.add("play " + stone[0] + " " + stone[1]);
    }
    for (String[] move : collectHistoryActions(analyzeNode, snapshotAnchor)) {
      commands.add("play " + move[0] + " " + move[1]);
    }
    commands.add(buildRemoteGtpAnalyzeCommand(analyzeNode));
    return commands;
  }

  private List<String> buildRemoteGtpIncrementalCommands(
      BoardHistoryNode previousAnalyzedNode, BoardHistoryNode analyzeNode) {
    List<String> commands = collectRemoteGtpAdvanceCommands(previousAnalyzedNode, analyzeNode);
    if (commands == null) {
      return null;
    }
    commands.add(buildRemoteGtpAnalyzeCommand(analyzeNode));
    return commands;
  }

  private String buildRemoteGtpAnalyzeCommand(BoardHistoryNode analyzeNode) {
    String player = analyzeNode.getData().blackToPlay ? "B" : "W";
    String command = "kata-analyze " + player + " " + remoteGtpAnalyzeInterval();
    return silentProgress && !isBatchAnalysisMode()
        ? command + " maxmoves 1 rootInfo true"
        : command;
  }

  private static boolean isBatchAnalysisMode() {
    return Lizzie.frame != null && Lizzie.frame.isBatchAnalysisMode;
  }

  private List<String> collectRemoteGtpAdvanceCommands(
      BoardHistoryNode previousAnalyzedNode, BoardHistoryNode analyzeNode) {
    if (previousAnalyzedNode == null || analyzeNode == null || previousAnalyzedNode == analyzeNode) {
      return null;
    }
    List<String> commands = new ArrayList<String>();
    BoardHistoryNode current = previousAnalyzedNode;
    while (current != analyzeNode) {
      if (!current.next().isPresent()) {
        return null;
      }
      current = current.next().get();
      if (isSnapshotAnchor(current)) {
        return null;
      }
      BoardData data = current.getData();
      if (data.isMoveNode()) {
        int[] move = data.lastMove.get();
        commands.add(
            "play "
                + (data.lastMoveColor.isBlack() ? "B" : "W")
                + " "
                + Board.convertCoordinatesToName(move[0], move[1]));
      } else if (data.isPassNode() && !data.dummy) {
        commands.add("play " + (data.lastMoveColor.isBlack() ? "B" : "W") + " pass");
      }
    }
    return commands;
  }

  private int remoteGtpAnalyzeInterval() {
    if (silentProgress) {
      return REMOTE_GTP_SILENT_INTERVAL_CENTISEC;
    }
    int interval = Lizzie.config == null ? 50 : Lizzie.config.analyzeUpdateIntervalCentisec;
    return interval > 0 ? interval : 50;
  }

  private String remoteGtpRules() {
    String rules = Lizzie.config == null ? "" : Lizzie.config.analysisSpecificRules;
    if (rules != null) {
      String normalized = rules.trim().toLowerCase();
      if ("chinese".equals(normalized)
          || "japanese".equals(normalized)
          || "tromp-taylor".equals(normalized)) {
        return normalized;
      }
    }
    return "chinese";
  }

  private ArrayDeque<RemoteGtpAnalyzeJob> remoteGtpQueue() {
    if (remoteGtpQueue == null) {
      remoteGtpQueue = new ArrayDeque<RemoteGtpAnalyzeJob>();
    }
    return remoteGtpQueue;
  }

  private static BoardHistoryNode firstHistoryActionNode(BoardHistoryNode node) {
    if (isRealHistoryAction(node.getData())) {
      return node;
    }
    return nextHistoryActionNode(node);
  }

  private static BoardHistoryNode nextHistoryActionNode(BoardHistoryNode node) {
    BoardHistoryNode current = node;
    while (current.next().isPresent()) {
      current = current.next().get();
      if (isRealHistoryAction(current.getData())) {
        return current;
      }
    }
    return null;
  }

  private static boolean isRealHistoryAction(BoardData data) {
    return data != null && (data.isMoveNode() || (data.isPassNode() && !data.dummy));
  }

  private static boolean shouldAnalyzeTurn(int moveNum, int startMove, int endMove) {
    boolean withinStart = startMove < 0 || moveNum >= startMove;
    boolean withinEnd = endMove < 0 || moveNum < endMove;
    return withinStart && withinEnd;
  }

  static BoardHistoryNode resolveInitialStateAnchor(BoardHistoryNode snapshotAnchor) {
    if (snapshotAnchor == null) {
      return null;
    }
    if (!Lizzie.board.hasStartStone || snapshotAnchor.previous().isPresent()) {
      return snapshotAnchor;
    }
    BoardData data = snapshotAnchor.getData();
    if (data.moveNumber > 0 || data.lastMove.isPresent()) {
      return snapshotAnchor;
    }
    for (Stone stone : data.stones) {
      if (stone.isBlack() || stone.isWhite()) {
        return snapshotAnchor;
      }
    }
    return null;
  }

  static ArrayList<String[]> collectInitialStones(BoardHistoryNode initialStateAnchor) {
    if (initialStateAnchor != null) {
      return collectSnapshotAnchorStones(initialStateAnchor.getData().stones);
    }
    if (Lizzie.board.hasStartStone) {
      return collectConfiguredStartStones();
    }
    return new ArrayList<String[]>();
  }

  private static ArrayList<String[]> collectConfiguredStartStones() {
    ArrayList<String[]> initialStoneList = new ArrayList<String[]>();
    for (Movelist mv : Lizzie.board.startStonelist) {
      if (!mv.ispass) {
        initialStoneList.add(
            new String[] {mv.isblack ? "B" : "W", Board.convertCoordinatesToName(mv.x, mv.y)});
      }
    }
    return initialStoneList;
  }

  private static ArrayList<String[]> collectSnapshotAnchorStones(Stone[] stones) {
    ArrayList<String[]> initialStoneList = new ArrayList<String[]>();
    for (int y = 0; y < Board.boardHeight; y++) {
      for (int x = 0; x < Board.boardWidth; x++) {
        Stone stone = stones[Board.getIndex(x, y)];
        if (stone.isBlack() || stone.isWhite()) {
          initialStoneList.add(
              new String[] {stone.isBlack() ? "B" : "W", Board.convertCoordinatesToName(x, y)});
        }
      }
    }
    return initialStoneList;
  }

  static String collectInitialPlayer(BoardHistoryNode initialStateAnchor) {
    if (initialStateAnchor != null) {
      return initialStateAnchor.getData().blackToPlay ? "B" : "W";
    }
    if (Lizzie.board.hasStartStone) {
      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      return root.getData().blackToPlay ? "B" : "W";
    }
    return null;
  }

  static BoardHistoryNode findSnapshotAnchor(BoardHistoryNode analyzeNode) {
    BoardHistoryNode current = analyzeNode;
    while (true) {
      if (isSnapshotAnchor(current)) {
        return current;
      }
      if (!current.previous().isPresent()) {
        return null;
      }
      current = current.previous().get();
    }
  }

  private static boolean isSnapshotAnchor(BoardHistoryNode node) {
    if (!node.getData().isSnapshotNode()) {
      return false;
    }
    if (node.previous().isPresent()) {
      return true;
    }
    BoardData data = node.getData();
    if (data.moveNumber > 0 || data.lastMove.isPresent()) {
      return true;
    }
    if (!data.blackToPlay) {
      return true;
    }
    for (Stone stone : data.stones) {
      if (stone.isBlack() || stone.isWhite()) {
        return true;
      }
    }
    return false;
  }

  private static ArrayList<String[]> collectHistoryActions(BoardHistoryNode analyzeNode) {
    return collectHistoryActions(analyzeNode, findSnapshotAnchor(analyzeNode));
  }

  static ArrayList<String[]> collectHistoryActions(
      BoardHistoryNode analyzeNode, BoardHistoryNode snapshotAnchor) {
    ArrayList<String[]> reversedMoves = new ArrayList<String[]>();
    BoardHistoryNode node = analyzeNode;
    while (node != snapshotAnchor && node.previous().isPresent()) {
      if (node.getData().isMoveNode()) {
        int[] move = node.getData().lastMove.get();
        reversedMoves.add(
            new String[] {
              node.getData().lastMoveColor.isBlack() ? "B" : "W",
              Board.convertCoordinatesToName(move[0], move[1])
            });
      } else if (node.getData().isPassNode() && !node.getData().dummy) {
        reversedMoves.add(
            new String[] {node.getData().lastMoveColor.isBlack() ? "B" : "W", "pass"});
      }
      node = node.previous().get();
    }
    ArrayList<String[]> moveList = new ArrayList<String[]>();
    for (int i = reversedMoves.size() - 1; i >= 0; i--) {
      moveList.add(reversedMoves.get(i));
    }
    return moveList;
  }

  private static boolean shouldAnalyzeBranchNode(BoardHistoryNode node) {
    BoardData data = node == null ? null : node.getData();
    return isRealHistoryAction(data) && shouldAnalyzeNode(node, targetAnalysisVisits());
  }

  private static boolean shouldAnalyzeNode(BoardHistoryNode node, int targetVisits) {
    BoardData data = node == null ? null : node.getData();
    return isRealHistoryAction(data)
        && (Lizzie.config.analysisAlwaysOverride || data.getPlayouts() < targetVisits);
  }

  private static boolean shouldAnalyzeMissingNode(BoardHistoryNode node) {
    BoardData data = node == null ? null : node.getData();
    return isRealHistoryAction(data) && !data.hasPrimaryAnalysisPayload();
  }

  public static int targetAnalysisVisits() {
    return isBatchAnalysisMode()
        ? Math.max(2, Lizzie.config.batchAnalysisPlayouts)
        : Lizzie.config.analysisMaxVisits + 1;
  }

  private int targetAnalysisVisitsForCurrentRequest(boolean showProgressDialog) {
    int targetVisits = targetAnalysisVisits();
    if (useRemoteCompute && !showProgressDialog && !isBatchAnalysisMode()) {
      return REMOTE_GTP_SILENT_MAX_VISITS;
    }
    return targetVisits;
  }

  public boolean matchesCurrentAnalysisBackend() {
    return useRemoteCompute
        == RemoteComputeConfig.isRemoteComputeEngineCommand(
            resolveConfiguredAnalysisEngineCommand(true));
  }

  public void shutdown() {
    // isShuttingdown = true;
    releaseSharedRemoteSession();
    if (useJavaSSH) {
      if (javaSSH != null) javaSSH.close();
    } else if (useRemoteCompute) {
      if (remoteTransport != null) remoteTransport.close();
    } else if (process != null) process.destroy();
  }

  public boolean isRunning() {
    if (!isLoaded()) {
      return false;
    }
    if (useJavaSSH) {
      return !javaSSHClosed;
    }
    if (useRemoteCompute) {
      if (sharedRemoteEngine != null) {
        return sharedRemoteEngine.isLoaded() && sharedRemoteEngine.isStarted();
      }
      return remoteTransport != null && remoteTransport.isOpen();
    }
    return process != null && process.isAlive();
  }

  public boolean sendCommand(String command) {
    if (sharedRemoteEngine != null) {
      return sharedRemoteEngine.sendExclusiveRemoteGtpCommand(command);
    }
    try {
      outputStream.write((command + "\n").getBytes());
      outputStream.flush();
      return true;
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
  }

  public synchronized boolean isAnalysisInProgress() {
    return analyzeMap.size() > 0 && resultCount < analyzeMap.size();
  }

  public void setCompletionCallback(Runnable completionCallback) {
    this.completionCallback = completionCallback;
  }

  public void setKeepAliveAfterCurrentRequest(boolean keepAliveAfterCurrentRequest) {
    this.keepAliveAfterCurrentRequest = keepAliveAfterCurrentRequest;
  }

  private void runCompletionCallback() {
    Runnable callback = completionCallback;
    completionCallback = null;
    if (callback != null) {
      javax.swing.SwingUtilities.invokeLater(callback);
    }
  }

  public boolean isLoaded() {
    return isLoaded;
  }

  private static final class RemoteGtpAnalyzeJob {
    private final int id;
    private final BoardHistoryNode node;
    private final int targetVisits;
    private final List<String> commands;

    private RemoteGtpAnalyzeJob(
        int id, BoardHistoryNode node, int targetVisits, List<String> commands) {
      this.id = id;
      this.node = node;
      this.targetVisits = targetVisits;
      this.commands = commands;
    }
  }

  private static final class RemoteGtpRootInfo {
    private final int visits;
    private final double winrate;
    private final double scoreLead;

    private RemoteGtpRootInfo(int visits, double winrate, double scoreLead) {
      this.visits = visits;
      this.winrate = winrate;
      this.scoreLead = scoreLead;
    }
  }

  private interface RemoteGtpMainlineSelector {
    boolean shouldAnalyze(BoardHistoryNode node, int moveNum);
  }
}
