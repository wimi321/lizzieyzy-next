package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.AnalysisSettings;
import featurecat.lizzie.gui.EngineFailedMessage;
import featurecat.lizzie.gui.RemoteEngineData;
import featurecat.lizzie.gui.WaitForAnalysis;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Movelist;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.CommandLaunchHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.Utils;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Stack;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.jdesktop.swingx.util.OS;
import org.json.JSONArray;
import org.json.JSONObject;

public class AnalysisEngine {
  public Process process;
  public boolean isNormalEnd = false;
  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;

  private BufferedReader inputStream;
  private BufferedOutputStream outputStream;
  private BufferedReader errorStream;

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
  private boolean shouldRePonder = false;
  private boolean isLoaded = false;
  private boolean silentProgress = false;

  public AnalysisEngine(boolean isPreLoad) throws IOException {
    int maxVisits =
        Lizzie.frame.isBatchAnalysisMode
            ? Math.max(2, Lizzie.config.batchAnalysisPlayouts)
            : Lizzie.config.analysisMaxVisits + 1;
    engineCommand =
        KataGoRuntimeHelper.optimizeAnalysisEngineCommand(
            Lizzie.config.analysisEngineCommand, maxVisits, Lizzie.frame.isBatchAnalysisMode);
    this.isPreLoad = isPreLoad;
    RemoteEngineData remoteData = Utils.getAnalysisEngineRemoteEngineData();
    this.useJavaSSH = remoteData.useJavaSSH;
    this.ip = remoteData.ip;
    this.port = remoteData.port;
    this.userName = remoteData.userName;
    this.password = remoteData.password;
    this.useKeyGen = remoteData.useKeyGen;
    this.keyGenPath = remoteData.keyGenPath;

    startEngine(engineCommand);
  }

  public void startEngine(String engineCommand) {
    CommandLaunchHelper.LaunchSpec launchSpec =
        CommandLaunchHelper.prepare(Utils.splitCommand(engineCommand));
    commands = launchSpec.getCommandParts();
    if (this.useJavaSSH) {
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
    if (isPreLoad) return;
    if (waitFrame != null) waitFrame.setVisible(false);
    tryToDignostic(errMsg);
    AnalysisSettings analysisSettings = new AnalysisSettings(true, true);
    analysisSettings.setVisible(true);
  }

  public void tryToDignostic(String message) {
    EngineFailedMessage engineFailedMessage =
        new EngineFailedMessage(
            commands, engineCommand, message, !useJavaSSH && OS.isWindows(), false, false);
    engineFailedMessage.setModal(true);
    engineFailedMessage.setVisible(true);
  }

  private void initializeStreams() {
    inputStream = new BufferedReader(new InputStreamReader(process.getInputStream()));
    outputStream = new BufferedOutputStream(process.getOutputStream());
    errorStream = new BufferedReader(new InputStreamReader(process.getErrorStream()));
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
    return;
  }

  private void parseLine(String line) {
    synchronized (this) {
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
    List<MoveData> moves = Utils.getBestMovesFromJsonArray(moveInfos, true, true);
    if (result.has("ownership")) {
      JSONArray ownership = result.getJSONArray("ownership");
      List<Object> list = ownership.toList();
      node.getData()
          .tryToSetBestMoves(
              moves,
              resourceBundle.getString("AnalysisEngine.flashAnalyze"),
              false,
              MoveData.getPlayouts(moves),
              (ArrayList<Double>) (List) list);
    } else
      node.getData()
          .tryToSetBestMoves(
              moves,
              resourceBundle.getString("AnalysisEngine.flashAnalyze"),
              false,
              MoveData.getPlayouts(moves));

    node.getData().comment = SGFParser.formatComment(node);
    Lizzie.board.updateMovelist(node);
    resultCount++;
    Lizzie.frame.requestProblemListRefresh();
    if (waitFrame != null) {
      waitFrame.setProgress(resultCount, analyzeMap.size());
    } else if (silentProgress && (resultCount == 1 || resultCount % 8 == 0)) {
      Lizzie.board.setMovelistAll();
      Lizzie.frame.refresh();
    }
    if (resultCount == analyzeMap.size()) setResult();
  }

  private void setResult() {
    Lizzie.board.clearPkBoardStat();
    Lizzie.board.isKataBoard = true;
    boolean oriEnableLizzieCache = Lizzie.config.enableLizzieCache;
    if (Lizzie.config.analysisAlwaysOverride) {
      Lizzie.config.enableLizzieCache = false;
    }
    Lizzie.board.setMovelistAll();
    if (Lizzie.board.getHistory().getCurrentHistoryNode() == Lizzie.board.getHistory().getStart())
      Lizzie.board.nextMove(true);
    Lizzie.frame.refresh();
    Lizzie.frame.requestProblemListRefresh();
    if (Lizzie.config.analysisAutoQuit && !Lizzie.frame.isBatchAna) {
      normalQuit();
    }
    if (Lizzie.config.analysisAlwaysOverride)
      Lizzie.config.enableLizzieCache = oriEnableLizzieCache;
    if (shouldRePonder && !Lizzie.leelaz.isPondering()) Lizzie.leelaz.togglePonder();
    Lizzie.frame.renderVarTree(0, 0, false, false);
  }

  public void normalQuit() {
    // TODO Auto-generated method stub
    isNormalEnd = true;
    if (this.useJavaSSH) this.javaSSH.close();
    else this.process.destroyForcibly();
    Lizzie.frame.requestProblemListRefresh();
  }

  public void startRequestAllBranches() {
    startRequestAllBranches(true);
  }

  public void startRequestAllBranches(boolean showProgressDialog) {
    if (!isLoaded) return;
    analyzeMap.clear();
    if (globalID <= 0) globalID = 1;
    resultCount = 0;
    silentProgress = !showProgressDialog;
    waitFrame = null;
    if (Lizzie.leelaz.isPondering()) {
      Lizzie.leelaz.togglePonder();
      shouldRePonder = true;
    } else shouldRePonder = false;
    BoardHistoryNode node = Lizzie.board.getHistory().getStart();
    Stack<BoardHistoryNode> stack = new Stack<>();
    stack.push(node);
    while (!stack.isEmpty()) {
      BoardHistoryNode cur = stack.pop();
      if (shouldAnalyzeBranchNode(cur)) {
        sendRequest(cur);
      }
      if (cur.numberOfChildren() >= 1) {
        for (int i = cur.numberOfChildren() - 1; i >= 0; i--)
          stack.push(cur.getVariations().get(i));
      }
    }
    if (analyzeMap.size() > 0) {
      Lizzie.frame.requestProblemListRefresh();
      if (showProgressDialog) {
        waitFrame = new WaitForAnalysis();
        if (Lizzie.config.analysisEnginePreLoad) waitFrame.setProgress(0, analyzeMap.size());
        waitFrame.setLocationRelativeTo(Lizzie.frame != null ? Lizzie.frame : null);
        waitFrame.setVisible(true);
      }
    } else if (Lizzie.frame.isBatchAnalysisMode) {
      Lizzie.frame.flashAutoAnaSaveAndLoad();
    }
  }

  public void startRequest(int startMove, int endMove) {
    startRequest(startMove, endMove, true);
  }

  public void startRequest(int startMove, int endMove, boolean showProgressDialog) {
    if (!isLoaded) return;
    analyzeMap.clear();
    if (globalID <= 0) globalID = 1;
    resultCount = 0;
    silentProgress = !showProgressDialog;
    waitFrame = null;
    if (Lizzie.leelaz.isPondering()) {
      Lizzie.leelaz.togglePonder();
      shouldRePonder = true;
    } else shouldRePonder = false;
    BoardHistoryNode node = firstHistoryActionNode(Lizzie.board.getHistory().getStart());
    int moveNum = 1;
    while (node != null) {
      if (shouldAnalyzeTurn(moveNum, startMove, endMove)) {
        sendRequest(node);
      }
      node = nextHistoryActionNode(node);
      moveNum++;
    }
    if (analyzeMap.size() > 0) {
      Lizzie.frame.requestProblemListRefresh();
      if (showProgressDialog) {
        waitFrame = new WaitForAnalysis();
        if (Lizzie.config.analysisEnginePreLoad) waitFrame.setProgress(0, analyzeMap.size());
        waitFrame.setLocationRelativeTo(Lizzie.frame != null ? Lizzie.frame : null);
        waitFrame.setVisible(true);
      }
    } else if (Lizzie.frame.isBatchAnalysisMode) {
      Lizzie.frame.flashAutoAnaSaveAndLoad();
    }
  }

  public void sendRequest(BoardHistoryNode analyzeNode) {
    JSONObject request = new JSONObject();
    int maxVisits =
        Lizzie.frame.isBatchAnalysisMode
            ? Math.max(2, Lizzie.config.batchAnalysisPlayouts)
            : Lizzie.config.analysisMaxVisits + 1;
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
    sendCommand(request.toString());
    analyzeMap.put(globalID, analyzeNode);
    globalID++;
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
    return data.isMoveNode() || (data.isPassNode() && !data.dummy);
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
    return isRealHistoryAction(node.getData());
  }

  public void shutdown() {
    // isShuttingdown = true;
    if (useJavaSSH) javaSSH.close();
    process.destroy();
  }

  public void sendCommand(String command) {
    try {
      outputStream.write((command + "\n").getBytes());
      outputStream.flush();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public synchronized boolean isAnalysisInProgress() {
    return analyzeMap.size() > 0 && resultCount < analyzeMap.size();
  }
}
