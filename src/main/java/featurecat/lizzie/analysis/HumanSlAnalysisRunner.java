package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
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
import java.util.ArrayList;
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
  private static final double MOVE_TEMPERATURE_EARLY = 0.85;
  private static final double MOVE_TEMPERATURE_FINAL = 0.70;
  private static final double MOVE_TEMPERATURE_HALFLIFE = 80.0;

  private final List<String> commandParts;
  private final ProcessStarter processStarter;
  private final AtomicInteger nextRequestId = new AtomicInteger(1);
  private final ConcurrentMap<String, CompletableFuture<JSONObject>> pendingResponses =
      new ConcurrentHashMap<String, CompletableFuture<JSONObject>>();

  private Process process;
  private BufferedReader inputStream;
  private BufferedOutputStream outputStream;
  private ScheduledExecutorService readerExecutor;
  private volatile boolean started;
  private volatile boolean closed;
  private volatile String unavailableReason;

  public HumanSlAnalysisRunner(String analysisCommand, Path humanModelPath) {
    this(buildHumanSlCommand(analysisCommand, humanModelPath), ProcessBuilder::start);
  }

  HumanSlAnalysisRunner(List<String> commandParts, ProcessStarter processStarter) {
    this.commandParts = new ArrayList<String>(commandParts);
    this.processStarter = processStarter;
  }

  public synchronized boolean start() {
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
    if (Config.isBundledKataGoCommand(String.join(" ", preparedCommands))) {
      try {
        KataGoRuntimeHelper.ensureBundledRuntimeReady(engineExecutable, Lizzie.frame);
      } catch (IOException e) {
        unavailableReason = e.getLocalizedMessage();
        return false;
      }
    }

    List<String> launchCommands =
        KataGoRuntimeHelper.prepareBundledLaunchCommand(preparedCommands, engineExecutable);
    ProcessBuilder processBuilder = new ProcessBuilder(launchCommands);
    CommandLaunchHelper.configureProcessBuilder(processBuilder, launchSpec);
    KataGoRuntimeHelper.configureBundledProcessBuilder(processBuilder, engineExecutable);
    processBuilder.redirectErrorStream(true);
    try {
      process = processStarter.start(processBuilder);
      inputStream =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      outputStream = new BufferedOutputStream(process.getOutputStream());
      readerExecutor = Executors.newSingleThreadScheduledExecutor();
      readerExecutor.execute(this::readLoop);
      started = true;
      unavailableReason = null;
      return true;
    } catch (IOException e) {
      unavailableReason = e.getLocalizedMessage();
      close();
      return false;
    }
  }

  /** Samples a move from the HumanSL policy for the requested profile. */
  public Optional<String> bestHumanMove(
      BoardHistoryNode positionNode, String profile, Duration timeout) {
    if (positionNode == null || profile == null) {
      return Optional.empty();
    }
    if (!ensureStarted()) {
      return Optional.empty();
    }
    String requestId = "humansl-genmove-" + nextRequestId.getAndIncrement();
    boolean allowPass = shouldAllowPass(positionNode);
    int visits = allowPass ? HUMAN_LIKE_PLAY_VISITS : 1;
    JSONObject request = buildHumanSlRequest(requestId, positionNode, profile, visits);
    try {
      JSONObject response = request(request, timeout == null ? Duration.ofSeconds(30) : timeout);
      if (allowPass && isSearchTopMovePass(response)) {
        return Optional.of("pass");
      }
      Object policy = extractHumanPolicy(response);
      if (policy == null) {
        return Optional.empty();
      }
      return Optional.ofNullable(
          samplePolicyMove(
              policy,
              Board.boardWidth,
              Board.boardHeight,
              positionNode.getData().stones,
              ThreadLocalRandom.current().nextDouble(),
              allowPass,
              moveTemperature(positionNode)));
    } catch (TimeoutException | IOException e) {
      return Optional.empty();
    }
  }

  /**
   * Temperature for "majority vote" imitation of a rank: dampens the long tail of moves a typical
   * player at that level would rarely choose. Follows KataGo's chosenMoveTemperature schedule,
   * decaying from {@value #MOVE_TEMPERATURE_EARLY} toward {@value #MOVE_TEMPERATURE_FINAL}.
   */
  private static double moveTemperature(BoardHistoryNode positionNode) {
    int moveNumber =
        positionNode == null || positionNode.getData() == null
            ? 0
            : Math.max(0, positionNode.getData().moveNumber);
    double decay = Math.pow(0.5, moveNumber / MOVE_TEMPERATURE_HALFLIFE);
    return MOVE_TEMPERATURE_FINAL + (MOVE_TEMPERATURE_EARLY - MOVE_TEMPERATURE_FINAL) * decay;
  }

  static String samplePolicyMove(
      Object policy, int boardWidth, int boardHeight, double randomValue, boolean allowPass) {
    return samplePolicyMove(policy, boardWidth, boardHeight, null, randomValue, allowPass, 1.0);
  }

  static String samplePolicyMove(
      Object policy,
      int boardWidth,
      int boardHeight,
      Stone[] stones,
      double randomValue,
      boolean allowPass) {
    return samplePolicyMove(policy, boardWidth, boardHeight, stones, randomValue, allowPass, 1.0);
  }

  static String samplePolicyMove(
      Object policy,
      int boardWidth,
      int boardHeight,
      Stone[] stones,
      double randomValue,
      boolean allowPass,
      double temperature) {
    List<PolicyMove> moves = policyMoves(policy, boardWidth, boardHeight, stones, allowPass);
    if (moves.isEmpty()) {
      return argmaxPolicyMove(policy, boardWidth, boardHeight);
    }
    double total = 0.0;
    for (PolicyMove move : moves) {
      total += temperedProbability(move.probability, temperature);
    }
    if (total <= 0.0 || Double.isNaN(total)) {
      return moves.get(0).move;
    }
    double target = Math.max(0.0, Math.min(0.999999999999, randomValue)) * total;
    double cumulative = 0.0;
    for (PolicyMove move : moves) {
      cumulative += temperedProbability(move.probability, temperature);
      if (target < cumulative) {
        return move.move;
      }
    }
    return moves.get(moves.size() - 1).move;
  }

  private static double temperedProbability(double probability, double temperature) {
    if (temperature <= 0.0) {
      return probability;
    }
    if (temperature == 1.0) {
      return probability;
    }
    return Math.pow(probability, 1.0 / temperature);
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
    if (!started || outputStream == null) {
      throw new IOException("HumanSL analysis engine is not started.");
    }
    String id = request.optString("id", "");
    if (id.isEmpty()) {
      throw new IOException("HumanSL request id is empty.");
    }
    CompletableFuture<JSONObject> future = new CompletableFuture<JSONObject>();
    pendingResponses.put(id, future);
    try {
      outputStream.write((request.toString() + "\n").getBytes(StandardCharsets.UTF_8));
      outputStream.flush();
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

  public boolean isStarted() {
    return started;
  }

  @Override
  public synchronized void close() {
    closed = true;
    started = false;
    IOException closeError = new IOException("HumanSL analysis runner closed.");
    for (CompletableFuture<JSONObject> future : pendingResponses.values()) {
      future.completeExceptionally(closeError);
    }
    pendingResponses.clear();
    if (readerExecutor != null) {
      readerExecutor.shutdownNow();
    }
    try {
      if (outputStream != null) {
        outputStream.close();
      }
    } catch (IOException ignored) {
    }
    try {
      if (inputStream != null) {
        inputStream.close();
      }
    } catch (IOException ignored) {
    }
    if (process != null && process.isAlive()) {
      process.destroyForcibly();
    }
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
    request.put("overrideSettings", overrideSettings);
    return request;
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

  private static List<PolicyMove> policyMoves(
      Object policy, int boardWidth, int boardHeight, boolean allowPass) {
    return policyMoves(policy, boardWidth, boardHeight, null, allowPass);
  }

  private static List<PolicyMove> policyMoves(
      Object policy, int boardWidth, int boardHeight, Stone[] stones, boolean allowPass) {
    ArrayList<PolicyMove> moves = new ArrayList<PolicyMove>();
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

  private static void addPolicyMove(List<PolicyMove> moves, String move, Object rawProbability) {
    Double probability = coerceProbability(rawProbability);
    if (move == null || move.trim().isEmpty() || probability == null || probability <= 0.0) {
      return;
    }
    moves.add(new PolicyMove(move.trim(), probability.doubleValue()));
  }

  private boolean ensureStarted() {
    return started || start();
  }

  private void readLoop() {
    try {
      String line;
      while (!closed && (line = inputStream.readLine()) != null) {
        if (!line.trim().startsWith("{")) {
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
      IOException ioException = new IOException("HumanSL analysis reader stopped.", e);
      for (CompletableFuture<JSONObject> future : pendingResponses.values()) {
        future.completeExceptionally(ioException);
      }
    } finally {
      started = false;
    }
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
    return Math.max(probability, 1.0e-12);
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

  private static final class PolicyMove {
    private final String move;
    private final double probability;

    private PolicyMove(String move, double probability) {
      this.move = move;
      this.probability = probability;
    }
  }
}
