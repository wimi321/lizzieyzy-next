package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.training.HumanMoveDecision;
import featurecat.lizzie.util.CommandLaunchHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.Utils;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs KataGo HumanSL analysis queries without changing the normal analysis engine. */
public class HumanSlAnalysisRunner implements AutoCloseable {
  private static final String GTP_COLUMNS = "ABCDEFGHJKLMNOPQRSTUVWXYZ";
  private static final int HUMAN_LIKE_PLAY_VISITS = 64;
  private static final int MAX_STARTUP_DIAGNOSTICS = 10;

  public enum StartupStage {
    STARTING,
    LOADING_MODELS,
    OPTIMIZING_GPU,
    CACHE_READY,
    READY
  }

  @FunctionalInterface
  public interface StartupListener {
    void onStartupProgress(StartupStage stage, String detail);
  }

  private final List<String> commandParts;
  private final ProcessStarter processStarter;
  private final AtomicInteger nextRequestId = new AtomicInteger(1);
  private final AtomicInteger processGeneration = new AtomicInteger();
  private final ConcurrentMap<String, CompletableFuture<JSONObject>> pendingResponses =
      new ConcurrentHashMap<String, CompletableFuture<JSONObject>>();
  private final Deque<String> startupDiagnostics = new ArrayDeque<String>();

  private Process process;
  private BufferedReader inputStream;
  private BufferedOutputStream outputStream;
  private ScheduledExecutorService readerExecutor;
  private volatile boolean started;
  private volatile boolean closed;
  private volatile String unavailableReason;
  private volatile int activeProcessGeneration;
  private volatile StartupListener startupListener;

  public HumanSlAnalysisRunner(String analysisCommand, Path humanModelPath) {
    this(buildHumanSlCommand(analysisCommand, humanModelPath), ProcessBuilder::start);
  }

  HumanSlAnalysisRunner(List<String> commandParts, ProcessStarter processStarter) {
    this.commandParts = new ArrayList<String>(commandParts);
    this.processStarter = processStarter;
  }

  public synchronized boolean start() {
    if (closed) {
      unavailableReason = "HumanSL analysis runner is closed.";
      return false;
    }
    if (started && process != null && process.isAlive()) {
      return true;
    }
    if (commandParts.isEmpty()) {
      unavailableReason = "HumanSL analysis command is empty.";
      return false;
    }

    CommandLaunchHelper.LaunchSpec launchSpec = CommandLaunchHelper.prepare(commandParts);
    List<String> preparedCommands = launchSpec.getCommandParts();
    Path engineExecutable = KataGoRuntimeHelper.resolveCommandExecutable(preparedCommands);
    if (KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed()) {
      unavailableReason = "KataGo tuning is using the local compute device.";
      return false;
    }
    List<String> launchCommands =
        KataGoRuntimeHelper.prepareBundledLaunchCommand(
            preparedCommands, engineExecutable, KataGoRuntimeHelper.LaunchPurpose.HUMAN_SL);
    Path launchExecutable = KataGoRuntimeHelper.resolveCommandExecutable(launchCommands);
    if (Config.isBundledKataGoCommand(String.join(" ", launchCommands))) {
      try {
        KataGoRuntimeHelper.ensureBundledRuntimeReady(launchExecutable, Lizzie.frame);
      } catch (IOException e) {
        unavailableReason = e.getLocalizedMessage();
        return false;
      }
    }
    ProcessBuilder processBuilder = new ProcessBuilder(launchCommands);
    CommandLaunchHelper.configureProcessBuilder(processBuilder, launchSpec);
    KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, launchExecutable);
    processBuilder.redirectErrorStream(true);
    clearStartupDiagnostics();
    reportStartupStage(StartupStage.STARTING, "");
    try {
      Process launchedProcess = processStarter.start(processBuilder);
      BufferedReader launchedInput =
          new BufferedReader(
              new InputStreamReader(launchedProcess.getInputStream(), StandardCharsets.UTF_8));
      BufferedOutputStream launchedOutput =
          new BufferedOutputStream(launchedProcess.getOutputStream());
      ScheduledExecutorService launchedReader = Executors.newSingleThreadScheduledExecutor();
      int generation = processGeneration.incrementAndGet();
      process = launchedProcess;
      inputStream = launchedInput;
      outputStream = launchedOutput;
      readerExecutor = launchedReader;
      activeProcessGeneration = generation;
      started = true;
      unavailableReason = null;
      launchedReader.execute(() -> readLoop(generation, launchedInput));
      AnalysisResourceCoordinator.processStarted(
          this,
          AnalysisResourceCoordinator.Purpose.OTHER,
          String.join(" ", launchCommands),
          launchedProcess);
      return true;
    } catch (IOException e) {
      unavailableReason = withStartupDiagnostics(e.getLocalizedMessage());
      stopActiveProcess(false, unavailableReason);
      return false;
    }
  }

  /**
   * Starts the process and proves that the HumanSL model can answer a real request. Process
   * creation alone is insufficient because KataGo can fail later while loading models or GPU
   * libraries.
   */
  public boolean verifyReady(
      BoardHistoryNode positionNode, String profile, Duration timeout) {
    if (positionNode == null || profile == null || profile.trim().isEmpty()) {
      unavailableReason = "HumanSL readiness position or profile is missing.";
      return false;
    }
    if (!ensureStarted()) {
      return false;
    }
    int generation = activeProcessGeneration;
    String requestId = "humansl-ready-" + nextRequestId.getAndIncrement();
    JSONObject readinessRequest =
        buildHumanSlRequest(requestId, positionNode, profile, 1, 1);
    try {
      JSONObject response =
          request(readinessRequest, timeout == null ? Duration.ofSeconds(180) : timeout);
      if (response == null || !requestId.equals(response.optString("id", ""))) {
        stopActiveProcess(
            generation,
            false,
            withStartupDiagnostics("HumanSL engine returned an invalid readiness response."));
        return false;
      }
      unavailableReason = null;
      reportStartupStage(StartupStage.READY, "");
      return true;
    } catch (TimeoutException | IOException e) {
      stopActiveProcess(
          generation,
          false,
          withStartupDiagnostics(usefulMessage(e, "HumanSL engine did not become ready.")));
      return false;
    }
  }

  /** Selects a plausible move from the HumanSL policy for the requested profile. */
  public Optional<String> bestHumanMove(
      BoardHistoryNode positionNode, String profile, Duration timeout) {
    return bestHumanMove(positionNode, profile, HUMAN_LIKE_PLAY_VISITS, 1, timeout);
  }

  /** Selects a plausible move with a caller-defined search and root-symmetry budget. */
  public Optional<String> bestHumanMove(
      BoardHistoryNode positionNode,
      String profile,
      int maxVisits,
      int rootSymmetries,
      Duration timeout) {
    if (positionNode == null || profile == null) {
      return Optional.empty();
    }
    if (!ensureStarted()) {
      return Optional.empty();
    }
    int generation = activeProcessGeneration;
    String requestId = "humansl-genmove-" + nextRequestId.getAndIncrement();
    boolean allowPass = shouldAllowPass(positionNode);
    JSONObject request =
        buildHumanSlRequest(requestId, positionNode, profile, maxVisits, rootSymmetries);
    try {
      JSONObject response = request(request, timeout == null ? Duration.ofSeconds(30) : timeout);
      if (allowPass && isSearchTopMovePass(response)) {
        return Optional.of("pass");
      }
      Object policy = extractHumanPolicy(response);
      if (policy == null) {
        return Optional.empty();
      }
      List<HumanLikeMoveSelector.Candidate> legalMoves =
          policyMoves(
              policy, Board.boardWidth, Board.boardHeight, positionNode.getData().stones, false);
      return Optional.ofNullable(
          HumanLikeMoveSelector.select(
              legalMoves,
              response.optJSONArray("moveInfos"),
              positionNode.getData().moveNumber,
              profile,
              ThreadLocalRandom.current().nextDouble()));
    } catch (TimeoutException | IOException e) {
      stopActiveProcess(
          generation,
          false,
          withStartupDiagnostics(usefulMessage(e, "HumanSL move request failed.")));
      return Optional.empty();
    }
  }

  /** Evaluates the player's actual move against human preference and KataGo quality. */
  public Optional<HumanMoveDecision> evaluateHumanMove(
      BoardHistoryNode positionNode,
      String profile,
      String actualMove,
      int maxVisits,
      int rootSymmetries,
      Duration timeout) {
    if (positionNode == null || profile == null || actualMove == null || !ensureStarted()) {
      return Optional.empty();
    }
    int generation = activeProcessGeneration;
    String requestId = "humansl-review-" + nextRequestId.getAndIncrement();
    JSONObject request =
        buildHumanSlRequest(requestId, positionNode, profile, maxVisits, rootSymmetries);
    try {
      JSONObject response = request(request, timeout == null ? Duration.ofSeconds(30) : timeout);
      Object policy = extractHumanPolicy(response);
      JSONArray moveInfos = response.optJSONArray("moveInfos");
      String commonMove = argmaxPolicyMove(policy, Board.boardWidth, Board.boardHeight);
      JSONObject bestInfo = orderedMoveInfo(moveInfos, 0);
      JSONObject actualInfo = findMoveInfo(moveInfos, actualMove);
      String bestMove = bestInfo == null ? commonMove : bestInfo.optString("move", commonMove);
      double scoreLoss = qualityLoss(bestInfo, actualInfo, "scoreLead", "scoreMean");
      double winrateLoss = qualityLoss(bestInfo, actualInfo, "winrate", null);
      double humanProbability =
          policyProbability(policy, actualMove, Board.boardWidth, Board.boardHeight);
      return Optional.of(
          new HumanMoveDecision(
              positionNode.getData().moveNumber + 1,
              positionNode,
              actualMove,
              commonMove,
              bestMove,
              humanProbability,
              scoreLoss,
              winrateLoss));
    } catch (TimeoutException | IOException e) {
      stopActiveProcess(
          generation,
          false,
          withStartupDiagnostics(usefulMessage(e, "HumanSL review request failed.")));
      return Optional.empty();
    }
  }

  static String samplePolicyMove(
      Object policy, int boardWidth, int boardHeight, double randomValue, boolean allowPass) {
    return samplePolicyMove(policy, boardWidth, boardHeight, null, randomValue, allowPass);
  }

  static String samplePolicyMove(
      Object policy,
      int boardWidth,
      int boardHeight,
      Stone[] stones,
      double randomValue,
      boolean allowPass) {
    List<HumanLikeMoveSelector.Candidate> moves =
        policyMoves(policy, boardWidth, boardHeight, stones, allowPass);
    if (moves.isEmpty()) {
      String fallback = argmaxPolicyMove(policy, boardWidth, boardHeight);
      return !allowPass && "pass".equalsIgnoreCase(fallback) ? null : fallback;
    }
    double total = 0.0;
    for (HumanLikeMoveSelector.Candidate move : moves) {
      total += move.probability;
    }
    if (total <= 0.0 || Double.isNaN(total)) {
      return moves.get(0).move;
    }
    double target = Math.max(0.0, Math.min(0.999999999999, randomValue)) * total;
    double cumulative = 0.0;
    for (HumanLikeMoveSelector.Candidate move : moves) {
      cumulative += move.probability;
      if (target < cumulative) {
        return move.move;
      }
    }
    return moves.get(moves.size() - 1).move;
  }

  static String argmaxPolicyMove(Object policy, int boardWidth, int boardHeight) {
    if (policy == null) {
      return null;
    }
    if (policy instanceof JSONArray) {
      JSONArray array = (JSONArray) policy;
      if (isNumericPolicy(array)) {
        int bestIndex = -1;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < array.length(); i++) {
          Double value = coerceProbability(array.opt(i));
          if (value != null && value.doubleValue() > bestValue) {
            bestValue = value.doubleValue();
            bestIndex = i;
          }
        }
        if (bestIndex < 0) {
          return null;
        }
        if (bestIndex == boardWidth * boardHeight) {
          return "pass";
        }
        int[] coords = policyIndexToCoords(bestIndex, boardWidth, boardHeight);
        return Board.convertCoordinatesToName(coords[0], coords[1]);
      }
      String bestMove = null;
      double bestValue = Double.NEGATIVE_INFINITY;
      for (int i = 0; i < array.length(); i++) {
        Object item = array.opt(i);
        if (!(item instanceof JSONArray)) {
          continue;
        }
        JSONArray pair = (JSONArray) item;
        if (pair.length() < 2) {
          continue;
        }
        Double value = coerceProbability(pair.opt(1));
        if (value != null && value.doubleValue() > bestValue) {
          bestValue = value.doubleValue();
          bestMove = pair.optString(0);
        }
      }
      return bestMove;
    }
    if (policy instanceof JSONObject) {
      JSONObject object = (JSONObject) policy;
      String bestMove = null;
      double bestValue = Double.NEGATIVE_INFINITY;
      for (String key : object.keySet()) {
        Double value = coerceProbability(object.opt(key));
        if (value != null && value.doubleValue() > bestValue) {
          bestValue = value.doubleValue();
          bestMove = key;
        }
      }
      return bestMove;
    }
    return null;
  }

  public JSONObject request(JSONObject request, Duration timeout)
      throws IOException, TimeoutException {
    if (!ensureStarted()) {
      throw new IOException(
          unavailableReason == null
              ? "HumanSL analysis engine is not started."
              : unavailableReason);
    }
    String id = request.optString("id", "");
    if (id.isEmpty()) {
      throw new IOException("HumanSL request id is empty.");
    }
    CompletableFuture<JSONObject> future = new CompletableFuture<JSONObject>();
    pendingResponses.put(id, future);
    try {
      BufferedOutputStream activeOutput;
      int generation;
      synchronized (this) {
        if (!started || outputStream == null || process == null || !process.isAlive()) {
          throw new IOException("HumanSL analysis engine stopped before the request was sent.");
        }
        activeOutput = outputStream;
        generation = activeProcessGeneration;
      }
      synchronized (activeOutput) {
        if (!started || generation != activeProcessGeneration) {
          throw new IOException("HumanSL analysis engine restarted before the request was sent.");
        }
        activeOutput.write((request.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        activeOutput.flush();
      }
      long timeoutMillis = Math.max(1L, timeout.toMillis());
      return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      throw new TimeoutException("Timed out waiting for HumanSL response " + id + ".");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for HumanSL response.", e);
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      }
      throw new IOException(cause);
    } finally {
      pendingResponses.remove(id);
    }
  }

  public String getUnavailableReason() {
    return unavailableReason;
  }

  public void setStartupListener(StartupListener listener) {
    startupListener = listener;
  }

  public boolean isStarted() {
    Process active = process;
    return started && active != null && active.isAlive();
  }

  /**
   * Cancels the current request and process without permanently closing the runner. A later
   * request starts a clean process, so user actions such as "finish" never queue behind a stuck
   * engine request.
   */
  public void cancelActiveRequests() {
    stopActiveProcess(false, "HumanSL request cancelled.");
  }

  @Override
  public void close() {
    stopActiveProcess(true, "HumanSL analysis runner closed.");
  }

  private void stopActiveProcess(boolean permanently, String reason) {
    stopActiveProcess(-1, permanently, reason);
  }

  private void stopActiveProcess(int expectedGeneration, boolean permanently, String reason) {
    Process stoppedProcess;
    BufferedReader stoppedInput;
    BufferedOutputStream stoppedOutput;
    ScheduledExecutorService stoppedReader;
    synchronized (this) {
      if (expectedGeneration >= 0 && expectedGeneration != activeProcessGeneration) {
        return;
      }
      if (permanently) {
        closed = true;
      }
      started = false;
      activeProcessGeneration = processGeneration.incrementAndGet();
      stoppedProcess = process;
      stoppedInput = inputStream;
      stoppedOutput = outputStream;
      stoppedReader = readerExecutor;
      process = null;
      inputStream = null;
      outputStream = null;
      readerExecutor = null;
      if (reason != null && !reason.trim().isEmpty()) {
        unavailableReason = reason;
      }
    }
    IOException closeError =
        new IOException(reason == null ? "HumanSL analysis runner stopped." : reason);
    for (CompletableFuture<JSONObject> future : pendingResponses.values()) {
      future.completeExceptionally(closeError);
    }
    pendingResponses.clear();
    if (stoppedReader != null) {
      stoppedReader.shutdownNow();
    }
    if (stoppedProcess != null && stoppedProcess.isAlive()) {
      stoppedProcess.destroyForcibly();
    }
    try {
      if (stoppedOutput != null) {
        stoppedOutput.close();
      }
    } catch (IOException ignored) {
    }
    try {
      if (stoppedInput != null) {
        stoppedInput.close();
      }
    } catch (IOException ignored) {
    }
    AnalysisResourceCoordinator.processStopped(
        this, AnalysisResourceCoordinator.Purpose.OTHER, stoppedProcess);
  }

  static List<String> buildHumanSlCommand(String analysisCommand, Path humanModelPath) {
    List<String> parts = Utils.splitCommand(analysisCommand == null ? "" : analysisCommand.trim());
    for (int i = 0; i < parts.size(); i++) {
      if ("gtp".equalsIgnoreCase(parts.get(i))) {
        parts.set(i, "analysis");
        break;
      }
    }
    String modelPath =
        humanModelPath == null ? "" : humanModelPath.toAbsolutePath().normalize().toString();
    int humanModelIndex = findHumanModelValueIndex(parts);
    if (humanModelIndex >= 0) {
      parts.set(humanModelIndex, modelPath);
    } else if (!modelPath.isEmpty()) {
      parts.add("-human-model");
      parts.add(modelPath);
    }
    return parts;
  }

  static JSONObject buildHumanSlRequest(
      String id, BoardHistoryNode positionNode, String profile, int maxVisits) {
    return buildHumanSlRequest(id, positionNode, profile, maxVisits, 1);
  }

  static JSONObject buildHumanSlRequest(
      String id,
      BoardHistoryNode positionNode,
      String profile,
      int maxVisits,
      int rootSymmetries) {
    JSONObject request =
        AnalysisRequestBuilder.buildRequest(
            id, positionNode, Math.max(1, maxVisits), false, false, false);
    request.put("includePolicy", true);
    request.put("maxVisits", Math.max(1, maxVisits));
    JSONObject overrideSettings = request.optJSONObject("overrideSettings");
    if (overrideSettings == null) {
      overrideSettings = new JSONObject();
    }
    overrideSettings.put("humanSLProfile", profile);
    overrideSettings.put("ignorePreRootHistory", false);
    overrideSettings.put("humanSLRootExploreProbWeightless", 0.5);
    overrideSettings.put("rootNumSymmetriesToSample", Math.max(1, Math.min(8, rootSymmetries)));
    request.put("overrideSettings", overrideSettings);
    return request;
  }

  private static JSONObject orderedMoveInfo(JSONArray moveInfos, int order) {
    if (moveInfos == null) {
      return null;
    }
    for (int index = 0; index < moveInfos.length(); index++) {
      JSONObject info = moveInfos.optJSONObject(index);
      if (info != null && info.optInt("order", index) == order) {
        return info;
      }
    }
    return order >= 0 && order < moveInfos.length() ? moveInfos.optJSONObject(order) : null;
  }

  private static JSONObject findMoveInfo(JSONArray moveInfos, String move) {
    if (moveInfos == null || move == null) {
      return null;
    }
    String normalized = normalizeMove(move);
    for (int index = 0; index < moveInfos.length(); index++) {
      JSONObject info = moveInfos.optJSONObject(index);
      if (info != null && normalized.equals(normalizeMove(info.optString("move", "")))) {
        return info;
      }
    }
    return null;
  }

  private static double qualityLoss(
      JSONObject bestInfo, JSONObject actualInfo, String primaryKey, String fallbackKey) {
    Double best = numericValue(bestInfo, primaryKey, fallbackKey);
    Double actual = numericValue(actualInfo, primaryKey, fallbackKey);
    if (best == null || actual == null) {
      return Double.NaN;
    }
    double loss = best.doubleValue() - actual.doubleValue();
    if ("winrate".equals(primaryKey)
        && (Math.abs(best.doubleValue()) > 1.0 || Math.abs(actual.doubleValue()) > 1.0)) {
      loss /= 100.0;
    }
    return Math.max(0.0, loss);
  }

  private static Double numericValue(JSONObject object, String primaryKey, String fallbackKey) {
    if (object == null) {
      return null;
    }
    Object raw = object.opt(primaryKey);
    if (!(raw instanceof Number) && fallbackKey != null) {
      raw = object.opt(fallbackKey);
    }
    if (!(raw instanceof Number)) {
      return null;
    }
    double value = ((Number) raw).doubleValue();
    return Double.isFinite(value) ? Double.valueOf(value) : null;
  }

  static double policyProbability(Object policy, String move, int boardWidth, int boardHeight) {
    if (policy == null || move == null) {
      return Double.NaN;
    }
    String normalized = normalizeMove(move);
    if (policy instanceof JSONObject) {
      JSONObject object = (JSONObject) policy;
      for (String key : object.keySet()) {
        if (normalized.equals(normalizeMove(key))) {
          Double probability = coerceProbability(object.opt(key));
          return probability == null ? Double.NaN : probability.doubleValue();
        }
      }
      return Double.NaN;
    }
    if (!(policy instanceof JSONArray)) {
      return Double.NaN;
    }
    JSONArray array = (JSONArray) policy;
    if (!isNumericPolicy(array)) {
      for (int index = 0; index < array.length(); index++) {
        JSONArray pair = array.optJSONArray(index);
        if (pair != null && normalized.equals(normalizeMove(pair.optString(0, "")))) {
          Double probability = coerceProbability(pair.opt(1));
          return probability == null ? Double.NaN : probability.doubleValue();
        }
      }
      return Double.NaN;
    }
    int policyIndex;
    if ("pass".equals(normalized)) {
      policyIndex = boardWidth * boardHeight;
    } else {
      int[] coords = Board.convertNameToCoordinates(normalized);
      if (coords == null || coords == featurecat.lizzie.gui.LizzieFrame.outOfBoundCoordinate) {
        return Double.NaN;
      }
      policyIndex = coords[1] * boardWidth + coords[0];
    }
    Double probability = coerceProbability(array.opt(policyIndex));
    return probability == null ? Double.NaN : probability.doubleValue();
  }

  private static String normalizeMove(String move) {
    if (move == null) {
      return "";
    }
    String normalized = move.trim();
    return "pass".equalsIgnoreCase(normalized)
        ? "pass"
        : normalized.toUpperCase(java.util.Locale.ROOT);
  }

  private static boolean isSearchTopMovePass(JSONObject response) {
    JSONArray moveInfos = response == null ? null : response.optJSONArray("moveInfos");
    if (moveInfos == null || moveInfos.length() == 0) {
      return false;
    }
    JSONObject topMove = null;
    for (int i = 0; i < moveInfos.length(); i++) {
      JSONObject moveInfo = moveInfos.optJSONObject(i);
      if (moveInfo == null) {
        continue;
      }
      if (moveInfo.optInt("order", i) == 0) {
        topMove = moveInfo;
        break;
      }
    }
    if (topMove == null) {
      topMove = moveInfos.optJSONObject(0);
    }
    return topMove != null && "pass".equalsIgnoreCase(topMove.optString("move", ""));
  }

  static Object extractHumanPolicy(JSONObject response) {
    if (response.has("humanPolicy")) {
      return response.get("humanPolicy");
    }
    JSONObject rootInfo = response.optJSONObject("rootInfo");
    if (rootInfo != null && rootInfo.has("humanPolicy")) {
      return rootInfo.get("humanPolicy");
    }
    return null;
  }

  private static List<HumanLikeMoveSelector.Candidate> policyMoves(
      Object policy, int boardWidth, int boardHeight, boolean allowPass) {
    return policyMoves(policy, boardWidth, boardHeight, null, allowPass);
  }

  private static List<HumanLikeMoveSelector.Candidate> policyMoves(
      Object policy, int boardWidth, int boardHeight, Stone[] stones, boolean allowPass) {
    ArrayList<HumanLikeMoveSelector.Candidate> moves =
        new ArrayList<HumanLikeMoveSelector.Candidate>();
    if (policy instanceof JSONArray) {
      JSONArray array = (JSONArray) policy;
      if (isNumericPolicy(array)) {
        int boardArea = boardWidth * boardHeight;
        for (int i = 0; i < array.length(); i++) {
          if (i == boardArea) {
            if (allowPass) {
              addPolicyMove(moves, "pass", array.opt(i));
            }
            continue;
          }
          if (i >= boardArea) {
            continue;
          }
          int[] coords = policyIndexToCoords(i, boardWidth, boardHeight);
          if (coords != null
              && coords[0] >= 0
              && coords[0] < boardWidth
              && coords[1] >= 0
              && coords[1] < boardHeight
              && isEmpty(stones, boardHeight, coords[0], coords[1])) {
            addPolicyMove(
                moves, Board.convertCoordinatesToName(coords[0], coords[1]), array.opt(i));
          }
        }
        return moves;
      }
      for (int i = 0; i < array.length(); i++) {
        Object item = array.opt(i);
        if (!(item instanceof JSONArray)) {
          continue;
        }
        JSONArray pair = (JSONArray) item;
        if (pair.length() >= 2) {
          String move = pair.optString(0, "");
          if ((allowPass || !"pass".equalsIgnoreCase(move.trim()))
              && isLegalPolicyMove(move, stones, boardHeight)) {
            addPolicyMove(moves, move, pair.opt(1));
          }
        }
      }
      return moves;
    }
    if (policy instanceof JSONObject) {
      JSONObject object = (JSONObject) policy;
      for (String key : object.keySet()) {
        if ((allowPass || !"pass".equalsIgnoreCase(key.trim()))
            && isLegalPolicyMove(key, stones, boardHeight)) {
          addPolicyMove(moves, key, object.opt(key));
        }
      }
    }
    return moves;
  }

  private static void addPolicyMove(
      List<HumanLikeMoveSelector.Candidate> moves, String move, Object rawProbability) {
    Double probability = coerceProbability(rawProbability);
    if (move == null || move.trim().isEmpty() || probability == null || probability <= 0.0) {
      return;
    }
    moves.add(new HumanLikeMoveSelector.Candidate(move.trim(), probability.doubleValue()));
  }

  private boolean ensureStarted() {
    return isStarted() || start();
  }

  private void readLoop(int generation, BufferedReader reader) {
    IOException failure = null;
    try {
      String line;
      while (!closed
          && generation == activeProcessGeneration
          && (line = reader.readLine()) != null) {
        if (!line.trim().startsWith("{")) {
          if (!line.trim().isEmpty()) {
            String diagnostic = line.trim();
            recordStartupDiagnostic(diagnostic);
            StartupStage stage = startupStageForLine(diagnostic);
            if (stage != null) {
              reportStartupStage(stage, diagnostic);
            }
          }
          continue;
        }
        JSONObject response = new JSONObject(line);
        String id = response.optString("id", "");
        CompletableFuture<JSONObject> future = pendingResponses.get(id);
        if (future != null) {
          future.complete(response);
        }
      }
    } catch (Exception e) {
      failure = new IOException("HumanSL analysis reader stopped.", e);
    } finally {
      boolean activeGeneration = generation == activeProcessGeneration;
      if (activeGeneration && !closed) {
        IOException ioException =
            failure == null
                ? new IOException(
                    stoppedProcessMessage(generation))
                : failure;
        String reason =
            withStartupDiagnostics(
                usefulMessage(ioException, "HumanSL analysis engine stopped."));
        stopActiveProcess(generation, false, reason);
      }
    }
  }

  static StartupStage startupStageForLine(String line) {
    String normalized = line == null ? "" : line.trim().toLowerCase(java.util.Locale.ROOT);
    if (normalized.isEmpty()) {
      return null;
    }
    if (normalized.contains("started, ready")) {
      return StartupStage.READY;
    }
    if (normalized.contains("saved new timing cache")
        || normalized.contains("using existing timing cache")) {
      return StartupStage.CACHE_READY;
    }
    if (normalized.contains("initializing (may take a long time)")
        || normalized.contains("creating new timing cache")
        || normalized.contains("building network")) {
      return StartupStage.OPTIMIZING_GPU;
    }
    if (normalized.contains("analysis engine starting")
        || normalized.contains("after dedups")
        || normalized.contains("loaded model")) {
      return StartupStage.LOADING_MODELS;
    }
    return null;
  }

  private void clearStartupDiagnostics() {
    synchronized (startupDiagnostics) {
      startupDiagnostics.clear();
    }
  }

  private void recordStartupDiagnostic(String line) {
    String trimmed = line == null ? "" : line.trim();
    if (trimmed.isEmpty()) {
      return;
    }
    if (trimmed.length() > 500) {
      trimmed = trimmed.substring(0, 500);
    }
    synchronized (startupDiagnostics) {
      while (startupDiagnostics.size() >= MAX_STARTUP_DIAGNOSTICS) {
        startupDiagnostics.removeFirst();
      }
      startupDiagnostics.addLast(trimmed);
    }
  }

  private String withStartupDiagnostics(String reason) {
    List<String> diagnostics;
    synchronized (startupDiagnostics) {
      diagnostics = new ArrayList<String>(startupDiagnostics);
    }
    String base = reason == null || reason.trim().isEmpty() ? "HumanSL engine failed." : reason.trim();
    return diagnostics.isEmpty() ? base : base + " | " + String.join(" | ", diagnostics);
  }

  private String stoppedProcessMessage(int generation) {
    Process active = process;
    if (generation != activeProcessGeneration || active == null || active.isAlive()) {
      return "HumanSL analysis engine stopped unexpectedly.";
    }
    try {
      return "HumanSL analysis engine stopped unexpectedly (exit code "
          + active.exitValue()
          + ").";
    } catch (IllegalThreadStateException e) {
      return "HumanSL analysis engine stopped unexpectedly.";
    }
  }

  private void reportStartupStage(StartupStage stage, String detail) {
    StartupListener listener = startupListener;
    if (listener == null || stage == null) {
      return;
    }
    try {
      listener.onStartupProgress(stage, detail == null ? "" : detail);
    } catch (RuntimeException ignored) {
    }
  }

  private static String usefulMessage(Exception exception, String fallback) {
    if (exception == null || exception.getLocalizedMessage() == null) {
      return fallback;
    }
    String message = exception.getLocalizedMessage().trim();
    return message.isEmpty() ? fallback : message;
  }

  private static int findHumanModelValueIndex(List<String> parts) {
    for (int i = 0; i < parts.size() - 1; i++) {
      String part = parts.get(i);
      if ("-human-model".equals(part) || "--human-model".equals(part)) {
        return i + 1;
      }
    }
    return -1;
  }

  private static boolean isNumericPolicy(JSONArray array) {
    if (array.length() == 0) {
      return false;
    }
    for (int i = 0; i < array.length(); i++) {
      Object value = array.opt(i);
      if (!(value instanceof Number)) {
        return false;
      }
    }
    return true;
  }

  private static Double coerceProbability(Object value) {
    if (!(value instanceof Number)) {
      return null;
    }
    double probability = ((Number) value).doubleValue();
    if (Double.isNaN(probability) || probability < 0.0) {
      return null;
    }
    return probability;
  }

  private static int[] policyIndexToCoords(int index, int boardWidth, int boardHeight) {
    int x = index % boardWidth;
    int y = (index - x) / boardWidth;
    if (y < 0 || y >= boardHeight) {
      return null;
    }
    return new int[] {x, y};
  }

  private static boolean isLegalPolicyMove(String move, Stone[] stones, int boardHeight) {
    if (move == null || stones == null || "pass".equalsIgnoreCase(move.trim())) {
      return true;
    }
    int[] coords = Board.convertNameToCoordinates(move.trim());
    return coords != null
        && coords != featurecat.lizzie.gui.LizzieFrame.outOfBoundCoordinate
        && Board.isValid(coords[0], coords[1])
        && isEmpty(stones, boardHeight, coords[0], coords[1]);
  }

  private static boolean isEmpty(Stone[] stones, int boardHeight, int x, int y) {
    if (stones == null) {
      return true;
    }
    int index = x * boardHeight + y;
    return index >= 0 && index < stones.length && stones[index] == Stone.EMPTY;
  }

  private static boolean shouldAllowPass(BoardHistoryNode positionNode) {
    return positionNode != null
        && positionNode.getData() != null
        && positionNode.getData().moveNumber >= 200;
  }

  interface ProcessStarter {
    Process start(ProcessBuilder processBuilder) throws IOException;
  }
}
