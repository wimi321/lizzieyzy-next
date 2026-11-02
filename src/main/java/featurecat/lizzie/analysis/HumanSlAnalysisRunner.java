package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.training.HumanSlPlaySettings;
import featurecat.lizzie.training.PositionReview;
import featurecat.lizzie.util.CommandLaunchHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.Utils;
import featurecat.lizzie.util.katago.tuning.KataGoCommandSpec;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
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
  private static final int POLICY_PROBE_VISITS = 1;
  // KataGo's gtp_human5k_example.cfg keeps 40 visits only to judge passing and resigning.
  static final int PASS_CHECK_VISITS = 40;
  static final int FINAL_SCORE_VISITS = 200;
  // KataGo's human configs only resign once 40% of the board was played; passing earlier than
  // that is not considered either, unless the opponent just passed.
  static final double PASS_BOARD_SHARE = 0.4;
  // When the human policy is mostly pass, the game is over for players of that rank.
  static final double HUMAN_PASS_SHARE = 0.5;
  // After the opponent passed, passing too ends the game if it gives up at most this many points.
  static final double PASS_TOLERANCE_POINTS = 1.0;
  private static final int HUMAN_LIKE_PLAY_VISITS = 64;
  private static final int MAX_ADAPTIVE_VERIFICATION_VISITS = 4_096;
  private static final int MAX_ADAPTIVE_VERIFICATION_ROUNDS = 4;
  private static final int MIN_ADAPTIVE_VISIT_GAIN = 16;
  private static final long MIN_ADAPTIVE_REQUEST_MILLIS = 750L;
  private static final long ADAPTIVE_RETURN_RESERVE_MILLIS = 450L;
  private static final double ADAPTIVE_TIME_USE_RATIO = 0.85;
  private static final int MAX_STARTUP_DIAGNOSTICS = 10;
  private static final long PROCESS_TERMINATION_TIMEOUT_MILLIS = 10_000L;

  public enum StartupStage {
    STARTING,
    LOADING_MODELS,
    OPTIMIZING_GPU,
    CACHE_READY,
    READY
  }

  public enum LaunchProfile {
    DEFAULT,
    POLICY_SUPPLEMENT
  }

  @FunctionalInterface
  public interface StartupListener {
    void onStartupProgress(StartupStage stage, String detail);
  }

  private final List<String> commandParts;
  private final ProcessStarter processStarter;
  private final LaunchProfile launchProfile;
  private final AtomicInteger nextRequestId = new AtomicInteger(1);
  private final AtomicInteger processGeneration = new AtomicInteger();
  private final ConcurrentMap<String, CompletableFuture<JSONObject>> pendingResponses =
      new ConcurrentHashMap<String, CompletableFuture<JSONObject>>();
  private final Deque<String> startupDiagnostics = new ArrayDeque<String>();

  private Process process;
  private final Set<Process> terminatingProcesses =
      Collections.newSetFromMap(new IdentityHashMap<Process, Boolean>());
  private BufferedReader inputStream;
  private BufferedOutputStream outputStream;
  private ScheduledExecutorService readerExecutor;
  private volatile boolean started;
  private volatile boolean closed;
  private volatile String unavailableReason;
  private volatile KataGoRuntimeHelper.TensorRtRepairContext tensorRtRepairContext;
  private volatile int activeProcessGeneration;
  private volatile StartupListener startupListener;

  public HumanSlAnalysisRunner(String analysisCommand, Path humanModelPath) {
    this(analysisCommand, humanModelPath, LaunchProfile.DEFAULT);
  }

  public HumanSlAnalysisRunner(
      String analysisCommand, Path humanModelPath, LaunchProfile launchProfile) {
    this(
        buildHumanSlCommand(analysisCommand, humanModelPath), ProcessBuilder::start, launchProfile);
  }

  HumanSlAnalysisRunner(List<String> commandParts, ProcessStarter processStarter) {
    this(commandParts, processStarter, LaunchProfile.DEFAULT);
  }

  HumanSlAnalysisRunner(
      List<String> commandParts, ProcessStarter processStarter, LaunchProfile launchProfile) {
    this.commandParts = new ArrayList<String>(commandParts);
    this.processStarter = processStarter;
    this.launchProfile = java.util.Objects.requireNonNull(launchProfile);
  }

  public synchronized boolean start() {
    if (closed) {
      unavailableReason = "HumanSL analysis runner is closed.";
      tensorRtRepairContext = null;
      return false;
    }
    Throwable terminationFailure = awaitAllTerminatingProcesses();
    if (terminationFailure != null) {
      throwUnchecked(terminationFailure);
    }
    if (started && process != null && process.isAlive()) {
      return true;
    }
    if (commandParts.isEmpty()) {
      unavailableReason = "HumanSL analysis command is empty.";
      tensorRtRepairContext = null;
      return false;
    }

    CommandLaunchHelper.LaunchSpec launchSpec = CommandLaunchHelper.prepare(commandParts);
    List<String> preparedCommands = launchSpec.getCommandParts();
    Path engineExecutable = KataGoRuntimeHelper.resolveCommandExecutable(preparedCommands);
    tensorRtRepairContext =
        KataGoRuntimeHelper.inspectHumanSlTensorRtStartupFailure(
            engineExecutable, preparedCommands, String.join(" ", preparedCommands));
    if (tensorRtRepairContext != null && tensorRtRepairContext.repairable) {
      unavailableReason = tensorRtRepairContext.displayMessage;
      return false;
    }
    if (KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed()) {
      unavailableReason = "KataGo tuning is using the local compute device.";
      return false;
    }
    List<String> launchCommands;
    try {
      launchCommands =
          KataGoRuntimeHelper.prepareBundledLaunchCommand(
              preparedCommands, engineExecutable, KataGoRuntimeHelper.LaunchPurpose.HUMAN_SL);
      if (launchProfile == LaunchProfile.POLICY_SUPPLEMENT
          && Config.isBundledKataGoExecutable(engineExecutable)) {
        // Retain bundled runtime/companion setup, but restore the policy-only batching budget.
        launchCommands =
            KataGoCommandSpec.parse(launchCommands)
                .withForcedOverrides(
                    Map.of(
                        "numAnalysisThreads", "4",
                        "numSearchThreadsPerAnalysisThread", "1",
                        "nnMaxBatchSize", "32"));
      }
    } catch (IllegalStateException e) {
      unavailableReason = usefulMessage(e, "HumanSL engine launch configuration is invalid.");
      return false;
    }
    Path launchExecutable = KataGoRuntimeHelper.resolveCommandExecutable(launchCommands);
    if (Config.isBundledKataGoExecutable(launchExecutable)) {
      try {
        KataGoRuntimeHelper.ensureBundledRuntimeReady(
            launchExecutable, launchCommands, Lizzie.frame);
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
      // Publish ownership immediately so every later unchecked failure can terminate this process.
      process = launchedProcess;
      BufferedReader launchedInput =
          new BufferedReader(
              new InputStreamReader(launchedProcess.getInputStream(), StandardCharsets.UTF_8));
      BufferedOutputStream launchedOutput =
          new BufferedOutputStream(launchedProcess.getOutputStream());
      ScheduledExecutorService launchedReader = Executors.newSingleThreadScheduledExecutor();
      int generation = processGeneration.incrementAndGet();
      inputStream = launchedInput;
      outputStream = launchedOutput;
      readerExecutor = launchedReader;
      activeProcessGeneration = generation;
      started = true;
      unavailableReason = null;
      tensorRtRepairContext = null;
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
    } catch (RuntimeException | Error failure) {
      Throwable cleanupFailure = null;
      try {
        stopActiveProcess(false, usefulMessage(failure, "HumanSL engine startup failed."));
      } catch (RuntimeException | Error added) {
        cleanupFailure = added;
      }
      appendFailure(failure, cleanupFailure);
      throw failure;
    }
  }

  /**
   * Starts the process and proves that the HumanSL model can answer a real request. Process
   * creation alone is insufficient because KataGo can fail later while loading models or GPU
   * libraries.
   */
  public boolean verifyReady(BoardHistoryNode positionNode, String profile, Duration timeout) {
    if (positionNode == null || profile == null || profile.trim().isEmpty()) {
      unavailableReason = "HumanSL readiness position or profile is missing.";
      return false;
    }
    if (!ensureStarted()) {
      return false;
    }
    int generation = activeProcessGeneration;
    String requestId = "humansl-ready-" + nextRequestId.getAndIncrement();
    JSONObject readinessRequest = buildHumanSlRequest(requestId, positionNode, profile, 1, 1);
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
    return chooseHumanMove(positionNode, profile, maxVisits, rootSymmetries, timeout)
        .map(HumanMoveChoice::move);
  }

  /**
   * Chooses the AI's move. Up to 5 dan it is sampled straight from the HumanSL policy, as KataGo
   * recommends for imitating a rank; stronger profiles verify human candidates with search, and
   * only they use the search budget.
   */
  public Optional<HumanMoveChoice> chooseHumanMove(
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
    boolean allowPass = mayPass(positionNode);
    Duration effectiveTimeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    long deadlineNanos = System.nanoTime() + Math.max(1L, effectiveTimeout.toNanos());
    try {
      if (HumanPolicySampler.playsRawPolicy(profile)) {
        return choosePolicyMove(positionNode, profile, rootSymmetries, allowPass, deadlineNanos);
      }
      PolicyProbe probe =
          probePolicy(positionNode, profile, rootSymmetries, allowPass, deadlineNanos);
      if (probe == null) {
        return Optional.empty();
      }
      JSONObject probeResponse = probe.response();
      List<HumanLikeMoveSelector.Candidate> selectableMoves = probe.selectableMoves();
      List<HumanLikeMoveSelector.Candidate> policyMovesIncludingPass = probe.movesIncludingPass();
      double topShare = probe.topShare();
      LinkedHashSet<String> verificationMoves = new LinkedHashSet<String>();
      addVerificationMoves(
          verificationMoves,
          HumanLikeMoveSelector.candidatePool(policyMovesIncludingPass),
          allowPass);
      addVerificationMoves(
          verificationMoves, HumanLikeMoveSelector.candidatePool(selectableMoves), allowPass);
      addVerificationMove(verificationMoves, searchTopMove(probeResponse), allowPass);
      addVerificationMove(
          verificationMoves,
          argmaxPolicyMove(probeResponse.opt("policy"), Board.boardWidth, Board.boardHeight),
          allowPass);
      if (verificationMoves.isEmpty()) {
        return Optional.empty();
      }

      int verificationVisits = Math.max(1, maxVisits);
      String verificationId = "humansl-verify-" + nextRequestId.getAndIncrement();
      JSONObject verificationRequest =
          buildHumanSlVerificationRequest(
              verificationId,
              positionNode,
              profile,
              verificationVisits,
              rootSymmetries,
              new ArrayList<String>(verificationMoves));
      long verificationStartedNanos = System.nanoTime();
      JSONObject verifiedResponse =
          requestHumanMove(verificationRequest, remainingRequestTime(deadlineNanos));
      long verificationElapsedNanos = Math.max(1L, System.nanoTime() - verificationStartedNanos);
      if (allowPass && isSearchTopMovePass(verifiedResponse)) {
        return Optional.of(SearchView.of(verifiedResponse).choice("pass", topShare));
      }

      JSONObject finalResponse = verifiedResponse;
      boolean volatileCandidates =
          HumanLikeMoveSelector.needsDeepVerification(
              selectableMoves, verifiedResponse.optJSONArray("moveInfos"), profile);
      int previousVisitLimit = verificationVisits;
      long completedElapsedNanos = verificationElapsedNanos;
      for (int round = 0;
          round < MAX_ADAPTIVE_VERIFICATION_ROUNDS
              && shouldAdaptivelyDeepen(profile, volatileCandidates);
          round++) {
        int deepVisits =
            adaptiveVerificationVisits(
                previousVisitLimit,
                completedElapsedNanos,
                Math.max(0L, deadlineNanos - System.nanoTime()));
        if (deepVisits <= previousVisitLimit) {
          break;
        }
        String deepId = "humansl-tactical-" + nextRequestId.getAndIncrement();
        JSONObject deepRequest =
            buildHumanSlVerificationRequest(
                deepId,
                positionNode,
                profile,
                deepVisits,
                rootSymmetries,
                new ArrayList<String>(verificationMoves));
        long deepStartedNanos = System.nanoTime();
        try {
          JSONObject deepResponse =
              requestHumanMove(deepRequest, remainingAdaptiveRequestTime(deadlineNanos));
          if (!hasAtLeastVerificationEvidence(finalResponse, deepResponse)) {
            break;
          }
          finalResponse = deepResponse;
          completedElapsedNanos = Math.max(1L, System.nanoTime() - deepStartedNanos);
          previousVisitLimit = deepVisits;
          volatileCandidates =
              HumanLikeMoveSelector.needsDeepVerification(
                  selectableMoves, finalResponse.optJSONArray("moveInfos"), profile);
        } catch (TimeoutException timeoutFailure) {
          terminateRequestBestEffort(generation, deepId);
          break;
        }
      }
      SearchView view = SearchView.of(finalResponse);
      if (allowPass && view.passEnds(positionNode)) {
        return Optional.of(view.choice("pass", topShare));
      }
      String selected =
          HumanLikeMoveSelector.select(
              selectableMoves,
              finalResponse.optJSONArray("moveInfos"),
              positionNode.getData().moveNumber,
              profile,
              ThreadLocalRandom.current().nextDouble());
      if (!allowPass && "pass".equalsIgnoreCase(selected)) {
        return Optional.empty();
      }
      return Optional.ofNullable(selected).map(move -> view.choice(move, topShare));
    } catch (TimeoutException | IOException e) {
      stopActiveProcess(
          generation,
          false,
          withStartupDiagnostics(usefulMessage(e, "HumanSL move request failed.")));
      return Optional.empty();
    }
  }

  /**
   * Chooses the AI's move at an AI 陪练 level. A policy level samples the raw HumanSL policy of its
   * profile, as gtp_human5k_example.cfg plays; a search level searches with the level's settings
   * and plays the move KataGo's genmove would choose with them, see {@link HumanSlChosenMove}.
   */
  public Optional<HumanMoveChoice> chooseMove(
      BoardHistoryNode positionNode, HumanSlPlaySettings play, Duration timeout) {
    if (positionNode == null || play == null) {
      return Optional.empty();
    }
    if (!ensureStarted()) {
      return Optional.empty();
    }
    int generation = activeProcessGeneration;
    boolean allowPass = mayPass(positionNode);
    Duration effectiveTimeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    long deadlineNanos = System.nanoTime() + Math.max(1L, effectiveTimeout.toNanos());
    try {
      return play.searches()
          ? chooseSearchedMove(positionNode, play, allowPass, deadlineNanos)
          : choosePolicyMove(
              positionNode,
              play.profile(),
              HumanSlPlaySettings.ROOT_SYMMETRIES,
              allowPass,
              deadlineNanos);
    } catch (TimeoutException | IOException e) {
      stopActiveProcess(
          generation,
          false,
          withStartupDiagnostics(usefulMessage(e, "HumanSL move request failed.")));
      return Optional.empty();
    }
  }

  /** The human policy of one visit, as the moves the AI may play and the share of the top one. */
  private record PolicyProbe(
      JSONObject response,
      List<HumanLikeMoveSelector.Candidate> selectableMoves,
      List<HumanLikeMoveSelector.Candidate> movesIncludingPass,
      double topShare) {}

  private PolicyProbe probePolicy(
      BoardHistoryNode positionNode,
      String profile,
      int rootSymmetries,
      boolean allowPass,
      long deadlineNanos)
      throws IOException, TimeoutException {
    String probeId = "humansl-policy-" + nextRequestId.getAndIncrement();
    JSONObject probe =
        buildHumanSlRequest(probeId, positionNode, profile, POLICY_PROBE_VISITS, rootSymmetries);
    JSONObject response = requestHumanMove(probe, remainingRequestTime(deadlineNanos));
    Object policy = extractHumanPolicy(response);
    if (policy == null) {
      return null;
    }
    Stone[] stones = positionNode.getData().stones;
    List<HumanLikeMoveSelector.Candidate> selectable =
        policyMoves(policy, Board.boardWidth, Board.boardHeight, stones, false);
    return new PolicyProbe(
        response,
        selectable,
        policyMoves(policy, Board.boardWidth, Board.boardHeight, stones, allowPass),
        HumanPolicySampler.topShare(selectable));
  }

  /**
   * Samples the raw HumanSL policy, as KataGo recommends for imitating a rank up to mid-high dan;
   * KataGo's own search only decides when to pass.
   */
  private Optional<HumanMoveChoice> choosePolicyMove(
      BoardHistoryNode positionNode,
      String profile,
      int rootSymmetries,
      boolean allowPass,
      long deadlineNanos)
      throws IOException, TimeoutException {
    PolicyProbe probe =
        probePolicy(positionNode, profile, rootSymmetries, allowPass, deadlineNanos);
    if (probe == null) {
      return Optional.empty();
    }
    double topShare = probe.topShare();
    SearchView view = allowPass ? passCheck(positionNode, deadlineNanos) : SearchView.NONE;
    if (view.passEnds(positionNode)) {
      return Optional.of(view.choice("pass", topShare));
    }
    if (allowPass
        && view.topMove() != null
        && HumanPolicySampler.passShare(probe.movesIncludingPass()) >= HUMAN_PASS_SHARE) {
      // Players of this rank would pass, but KataGo still sees a point that matters, such as a
      // dame or a needed connection. What is left of the human policy here is noise.
      return Optional.of(view.choice(view.topMove(), topShare));
    }
    String sampled =
        HumanPolicySampler.sample(
            probe.selectableMoves(),
            positionNode.getData().moveNumber,
            Board.boardWidth,
            Board.boardHeight,
            ThreadLocalRandom.current().nextDouble());
    return Optional.ofNullable(sampled).map(move -> view.choice(move, topShare));
  }

  /** One search with the level's settings, then the move genmove would choose from it. */
  private Optional<HumanMoveChoice> chooseSearchedMove(
      BoardHistoryNode positionNode,
      HumanSlPlaySettings play,
      boolean allowPass,
      long deadlineNanos)
      throws IOException, TimeoutException {
    String id = "humansl-play-" + nextRequestId.getAndIncrement();
    JSONObject response =
        requestHumanMove(
            buildHumanSlPlayRequest(id, positionNode, play), remainingRequestTime(deadlineNanos));
    Object policy = extractHumanPolicy(response);
    if (policy == null) {
      return Optional.empty();
    }
    Stone[] stones = positionNode.getData().stones;
    double topShare =
        HumanPolicySampler.topShare(
            policyMoves(policy, Board.boardWidth, Board.boardHeight, stones, false));
    SearchView view = SearchView.of(response);
    if (allowPass && view.passEnds(positionNode)) {
      return Optional.of(view.choice("pass", topShare));
    }
    String move =
        HumanSlChosenMove.choose(
            response.optJSONArray("moveInfos"),
            policyMoves(policy, Board.boardWidth, Board.boardHeight, stones, allowPass),
            play,
            positionNode.getData().moveNumber,
            Board.boardWidth,
            Board.boardHeight,
            allowPass,
            ThreadLocalRandom.current().nextDouble());
    return Optional.ofNullable(move).map(chosen -> view.choice(chosen, topShare));
  }

  /** An analysis query that searches with every setting of a search level. */
  static JSONObject buildHumanSlPlayRequest(
      String id, BoardHistoryNode positionNode, HumanSlPlaySettings play) {
    JSONObject request =
        AnalysisRequestBuilder.buildRequest(
            id, positionNode, play.maxVisits(), false, false, false);
    request.put("includePolicy", true);
    JSONObject overrideSettings = request.optJSONObject("overrideSettings");
    if (overrideSettings == null) {
      overrideSettings = new JSONObject();
    }
    for (Map.Entry<String, Object> setting : play.searchOverrides().entrySet()) {
      overrideSettings.put(setting.getKey(), setting.getValue());
    }
    request.put("overrideSettings", overrideSettings);
    return request;
  }

  /**
   * Reviews a position for 边下边提醒: KataGo's values for the moves it searched, and how often players
   * of the profile's rank play each move. The search stops at {@code maxTime}, so a slow engine
   * judges with fewer visits instead of holding up the AI's moves. Unlike a move request, a review
   * that times out is dropped without restarting the engine.
   */
  public Optional<PositionReview> reviewPosition(
      BoardHistoryNode positionNode,
      String profile,
      int maxVisits,
      Duration maxTime,
      Duration timeout) {
    if (positionNode == null || profile == null || !ensureStarted()) {
      return Optional.empty();
    }
    int generation = activeProcessGeneration;
    String requestId = "humansl-review-" + nextRequestId.getAndIncrement();
    JSONObject request = buildHumanSlRequest(requestId, positionNode, profile, maxVisits, 1);
    request.getJSONObject("overrideSettings").put("maxTime", maxTime.toMillis() / 1000.0);
    try {
      JSONObject response = request(request, timeout);
      return Optional.of(
          parseReview(
              response, positionNode.getData().stones, Board.boardWidth, Board.boardHeight));
    } catch (TimeoutException e) {
      terminateRequestBestEffort(generation, requestId);
      return Optional.empty();
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  static PositionReview parseReview(
      JSONObject response, Stone[] stones, int boardWidth, int boardHeight) {
    JSONObject rootInfo = response.optJSONObject("rootInfo");
    double rootLead = rootInfo == null ? Double.NaN : rootInfo.optDouble("scoreLead", Double.NaN);
    double stdev =
        rootInfo == null ? Double.NaN : rootInfo.optDouble("humanScoreStdev", Double.NaN);
    List<JSONObject> infos = new ArrayList<JSONObject>();
    JSONArray moveInfos = response.optJSONArray("moveInfos");
    for (int i = 0; moveInfos != null && i < moveInfos.length(); i++) {
      JSONObject info = moveInfos.optJSONObject(i);
      if (info != null && !info.optString("move", "").trim().isEmpty()) {
        infos.add(info.put("order", info.optInt("order", i)));
      }
    }
    infos.sort(java.util.Comparator.comparingInt(info -> info.getInt("order")));
    List<PositionReview.Candidate> candidates = new ArrayList<PositionReview.Candidate>();
    for (JSONObject info : infos) {
      double lead = info.optDouble("scoreLead", info.optDouble("scoreMean", Double.NaN));
      if (Double.isFinite(lead)) {
        candidates.add(
            new PositionReview.Candidate(
                info.getString("move").trim(), info.optInt("visits", 0), lead));
      }
    }
    Map<String, Double> shares = new java.util.HashMap<String, Double>();
    Object policy = extractHumanPolicy(response);
    for (HumanLikeMoveSelector.Candidate move :
        policyMoves(policy, boardWidth, boardHeight, stones, true)) {
      shares.merge(normalizeMove(move.move), move.probability, Double::sum);
    }
    return new PositionReview(rootLead, stdev, candidates, shares);
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

  public KataGoRuntimeHelper.TensorRtRepairContext getTensorRtRepairContext() {
    return tensorRtRepairContext;
  }

  public void setStartupListener(StartupListener listener) {
    startupListener = listener;
  }

  public boolean isStarted() {
    Process active = process;
    return started && active != null && active.isAlive();
  }

  /**
   * Cancels the current request and process without permanently closing the runner. A later request
   * starts a clean process, so user actions such as "finish" never queue behind a stuck engine
   * request.
   */
  public void cancelActiveRequests() {
    stopActiveProcess(false, "HumanSL request cancelled.");
  }

  @Override
  public void close() {
    Throwable failure = null;
    try {
      stopActiveProcess(true, "HumanSL analysis runner closed.");
    } catch (RuntimeException | Error added) {
      failure = appendFailure(failure, added);
    }
    failure = appendFailure(failure, awaitAllTerminatingProcesses());
    if (failure != null) {
      throwUnchecked(failure);
    }
  }

  private Process stopActiveProcess(boolean permanently, String reason) {
    return stopActiveProcess(-1, permanently, reason);
  }

  private Process stopActiveProcess(int expectedGeneration, boolean permanently, String reason) {
    Process stoppedProcess;
    BufferedReader stoppedInput;
    BufferedOutputStream stoppedOutput;
    ScheduledExecutorService stoppedReader;
    synchronized (this) {
      if (expectedGeneration >= 0 && expectedGeneration != activeProcessGeneration) {
        return null;
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
      if (stoppedProcess != null) {
        terminatingProcesses.add(stoppedProcess);
      }
      inputStream = null;
      outputStream = null;
      readerExecutor = null;
      if (reason != null && !reason.trim().isEmpty()) {
        unavailableReason = reason;
      }
    }
    Throwable cleanupFailure = null;
    IOException closeError =
        new IOException(reason == null ? "HumanSL analysis runner stopped." : reason);
    for (CompletableFuture<JSONObject> future : pendingResponses.values()) {
      try {
        future.completeExceptionally(closeError);
      } catch (RuntimeException | Error failure) {
        cleanupFailure = appendFailure(cleanupFailure, failure);
      }
    }
    try {
      pendingResponses.clear();
    } catch (RuntimeException | Error failure) {
      cleanupFailure = appendFailure(cleanupFailure, failure);
    }
    if (stoppedReader != null) {
      try {
        stoppedReader.shutdownNow();
      } catch (RuntimeException | Error failure) {
        cleanupFailure = appendFailure(cleanupFailure, failure);
      }
    }
    if (stoppedProcess != null) {
      try {
        if (stoppedProcess.isAlive()) {
          stoppedProcess.destroyForcibly();
        }
      } catch (RuntimeException | Error failure) {
        cleanupFailure = appendFailure(cleanupFailure, failure);
      }
    }
    try {
      if (stoppedOutput != null) {
        stoppedOutput.close();
      }
    } catch (IOException | RuntimeException | Error failure) {
      cleanupFailure = appendFailure(cleanupFailure, failure);
    }
    try {
      if (stoppedInput != null) {
        stoppedInput.close();
      }
    } catch (IOException | RuntimeException | Error failure) {
      cleanupFailure = appendFailure(cleanupFailure, failure);
    }
    if (cleanupFailure != null) {
      throwUnchecked(cleanupFailure);
    }
    return stoppedProcess;
  }

  private Throwable awaitAllTerminatingProcesses() {
    List<Process> snapshot;
    synchronized (this) {
      snapshot = new ArrayList<Process>(terminatingProcesses);
    }
    Throwable failure = null;
    for (Process stoppedProcess : snapshot) {
      try {
        awaitProcessTermination(stoppedProcess);
      } catch (RuntimeException | Error added) {
        failure = appendFailure(failure, added);
      }
    }
    return failure;
  }

  private void awaitProcessTermination(Process stoppedProcess) {
    try {
      if (stoppedProcess.isAlive()
          && !stoppedProcess.waitFor(PROCESS_TERMINATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("HumanSL process did not terminate after close request.");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting for the HumanSL process to terminate.", interrupted);
    }
    // Occupancy is released only after the OS process is really gone. If publication fails, keep
    // the identity in the set so a later close/start can retry it without losing ownership.
    AnalysisResourceCoordinator.processStopped(
        this, AnalysisResourceCoordinator.Purpose.OTHER, stoppedProcess);
    synchronized (this) {
      terminatingProcesses.remove(stoppedProcess);
    }
  }

  private static Throwable appendFailure(Throwable primary, Throwable added) {
    if (added == null) {
      return primary;
    }
    if (primary == null) {
      return added;
    }
    if (primary != added) {
      try {
        primary.addSuppressed(added);
      } catch (RuntimeException | Error ignored) {
      }
    }
    return primary;
  }

  private static void throwUnchecked(Throwable failure) {
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    throw new IllegalStateException(failure);
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
      String id, BoardHistoryNode positionNode, String profile, int maxVisits, int rootSymmetries) {
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
    overrideSettings.put(
        "humanSLRootExploreProbWeightless", isEliteHumanProfile(profile) ? 0.8 : 0.5);
    overrideSettings.put("rootNumSymmetriesToSample", Math.max(1, Math.min(8, rootSymmetries)));
    request.put("overrideSettings", overrideSettings);
    return request;
  }

  static JSONObject buildHumanSlVerificationRequest(
      String id,
      BoardHistoryNode positionNode,
      String profile,
      int maxVisits,
      int rootSymmetries,
      List<String> allowedMoves) {
    JSONObject request = buildHumanSlRequest(id, positionNode, profile, maxVisits, rootSymmetries);
    JSONObject overrideSettings = request.getJSONObject("overrideSettings");
    overrideSettings.put("humanSLCpuctPermanent", 2.0);
    JSONArray moves = new JSONArray();
    if (allowedMoves != null) {
      for (String move : allowedMoves) {
        String normalized = normalizeMove(move);
        if (!normalized.isEmpty()) {
          moves.put(normalized);
        }
      }
    }
    JSONObject allowance =
        new JSONObject()
            .put("player", positionNode.getData().blackToPlay ? "B" : "W")
            .put("moves", moves)
            .put("untilDepth", 1);
    request.put("allowMoves", new JSONArray().put(allowance));
    return request;
  }

  static int adaptiveVerificationVisits(
      int previousVisitLimit, long completedElapsedNanos, long remainingNanos) {
    // Root visits omit HumanSL weightless exploration. Estimate work from the prior
    // request limit and elapsed time, not root visits or an inferred timeout.
    int safeCompletedVisits = Math.max(1, previousVisitLimit);
    long reserveNanos = TimeUnit.MILLISECONDS.toNanos(ADAPTIVE_RETURN_RESERVE_MILLIS);
    long usableNanos = Math.max(0L, remainingNanos - reserveNanos);
    if (completedElapsedNanos <= 0L
        || usableNanos < TimeUnit.MILLISECONDS.toNanos(MIN_ADAPTIVE_REQUEST_MILLIS)) {
      return safeCompletedVisits;
    }

    double estimatedVisits =
        safeCompletedVisits
            * ((double) usableNanos / (double) completedElapsedNanos)
            * ADAPTIVE_TIME_USE_RATIO;
    int minimumGain = Math.max(MIN_ADAPTIVE_VISIT_GAIN, safeCompletedVisits / 4);
    int minimumTarget =
        Math.min(MAX_ADAPTIVE_VERIFICATION_VISITS, safeCompletedVisits + minimumGain);
    if (estimatedVisits < minimumTarget) {
      return safeCompletedVisits;
    }
    int targetVisits =
        (int)
            Math.min(
                MAX_ADAPTIVE_VERIFICATION_VISITS,
                Math.max(minimumTarget, Math.floor(estimatedVisits)));
    return targetVisits > safeCompletedVisits ? targetVisits : safeCompletedVisits;
  }

  /**
   * Asks KataGo's own search, without the human model, whether passing is best here, and how the AI
   * stands, which decides when it resigns.
   */
  private SearchView passCheck(BoardHistoryNode positionNode, long deadlineNanos)
      throws IOException, TimeoutException {
    String id = "humansl-pass-" + nextRequestId.getAndIncrement();
    JSONObject request =
        AnalysisRequestBuilder.buildRequest(
            id, positionNode, PASS_CHECK_VISITS, false, false, false);
    request.put("maxVisits", PASS_CHECK_VISITS);
    return SearchView.of(requestHumanMove(request, remainingRequestTime(deadlineNanos)));
  }

  /**
   * KataGo's final score lead for Black once both players passed, from its own search, or empty
   * when the engine does not answer in time.
   */
  public OptionalDouble finalBlackScoreLead(BoardHistoryNode positionNode, Duration timeout) {
    if (positionNode == null || positionNode.getData() == null || !ensureStarted()) {
      return OptionalDouble.empty();
    }
    int generation = activeProcessGeneration;
    String id = "humansl-count-" + nextRequestId.getAndIncrement();
    JSONObject request =
        AnalysisRequestBuilder.buildRequest(
            id, positionNode, FINAL_SCORE_VISITS, false, false, false);
    request.put("maxVisits", FINAL_SCORE_VISITS);
    try {
      JSONObject rootInfo =
          requestHumanMove(request, timeout == null ? Duration.ofSeconds(20) : timeout)
              .optJSONObject("rootInfo");
      double lead = rootInfo == null ? Double.NaN : rootInfo.optDouble("scoreLead", Double.NaN);
      if (!Double.isFinite(lead)) {
        return OptionalDouble.empty();
      }
      return OptionalDouble.of(positionNode.getData().blackToPlay ? lead : -lead);
    } catch (TimeoutException | IOException e) {
      stopActiveProcess(
          generation,
          false,
          withStartupDiagnostics(usefulMessage(e, "HumanSL final count failed.")));
      return OptionalDouble.empty();
    }
  }

  /**
   * What one KataGo search says about the side to move: its top move, whether that is a pass, its
   * winrate and score lead, and what passing would cost in points.
   */
  private record SearchView(
      String topMove, boolean prefersPass, double winrate, double scoreLead, double passCost) {
    static final SearchView NONE = new SearchView(null, false, Double.NaN, Double.NaN, Double.NaN);

    static SearchView of(JSONObject response) {
      JSONObject rootInfo = response == null ? null : response.optJSONObject("rootInfo");
      String top = response == null ? null : searchTopMove(response);
      boolean pass = isSearchTopMovePass(response);
      return new SearchView(
          pass || top == null || top.isEmpty() ? null : top,
          pass,
          rootInfo == null ? Double.NaN : rootInfo.optDouble("winrate", Double.NaN),
          rootInfo == null ? Double.NaN : rootInfo.optDouble("scoreLead", Double.NaN),
          passCost(response == null ? null : response.optJSONArray("moveInfos")));
    }

    /** Points the top move gains over passing, NaN when the search did not look at passing. */
    private static double passCost(JSONArray moveInfos) {
      double best = Double.NaN;
      double pass = Double.NaN;
      if (moveInfos != null) {
        for (int i = 0; i < moveInfos.length(); i++) {
          JSONObject info = moveInfos.optJSONObject(i);
          if (info == null) {
            continue;
          }
          double lead = info.optDouble("scoreLead", Double.NaN);
          if (info.optInt("order", i) == 0) {
            best = lead;
          }
          if ("pass".equalsIgnoreCase(info.optString("move", "").trim())) {
            pass = lead;
          }
        }
      }
      return best - pass;
    }

    /**
     * Whether the AI should pass now: KataGo's top move is a pass, or the opponent just passed and
     * passing too gives up at most a point, which ends the game. Between equal moves, such as
     * filling one's own territory under area scoring, KataGo would otherwise play on forever.
     */
    boolean passEnds(BoardHistoryNode positionNode) {
      boolean opponentPassed =
          positionNode != null
              && positionNode.getData() != null
              && positionNode.getData().isPassNode();
      return prefersPass || (opponentPassed && passCost <= PASS_TOLERANCE_POINTS);
    }

    HumanMoveChoice choice(String move, double topShare) {
      return new HumanMoveChoice(move, topShare, winrate, scoreLead);
    }
  }

  private JSONObject requestHumanMove(JSONObject request, Duration timeout)
      throws IOException, TimeoutException {
    // maxVisits alone is not a clock, especially with HumanSL's weightless exploration.
    // Leave time for the engine's final batch, JSON output and delivery to the game controller.
    request.getJSONObject("overrideSettings").put("maxTime", engineSearchSeconds(timeout));
    return request(request, timeout);
  }

  static double engineSearchSeconds(Duration timeout) {
    long remainingNanos = Math.max(0L, timeout.toNanos());
    long reserveNanos =
        Math.min(
            TimeUnit.MILLISECONDS.toNanos(ADAPTIVE_RETURN_RESERVE_MILLIS), remainingNanos / 2L);
    return (remainingNanos - reserveNanos) / 1_000_000_000.0 * ADAPTIVE_TIME_USE_RATIO;
  }

  static boolean hasAtLeastVerificationEvidence(JSONObject previous, JSONObject next) {
    JSONArray moves = next.optJSONArray("moveInfos");
    if (moves == null || moves.length() == 0) {
      return false;
    }
    double previousVisits = candidateSearchVisits(previous.optJSONArray("moveInfos"));
    double nextVisits = candidateSearchVisits(moves);
    return previousVisits < 0.0 || nextVisits < 0.0 || nextVisits >= previousVisits;
  }

  private static double candidateSearchVisits(JSONArray moves) {
    if (moves == null || moves.length() == 0) {
      return -1.0;
    }
    double total = 0.0;
    for (int i = 0; i < moves.length(); i++) {
      JSONObject move = moves.optJSONObject(i);
      double visits = move == null ? Double.NaN : move.optDouble("visits", Double.NaN);
      if (!Double.isFinite(visits) || visits < 0.0) {
        return -1.0;
      }
      // Child visits include weightless HumanSL exploration, unlike root visits.
      total += visits;
    }
    return total;
  }

  static boolean shouldAdaptivelyDeepen(String profile, boolean volatileCandidates) {
    return volatileCandidates || isEliteHumanProfile(profile);
  }

  private static boolean isEliteHumanProfile(String profile) {
    if (profile == null) {
      return false;
    }
    String normalized = profile.trim().toLowerCase(java.util.Locale.ROOT);
    if (normalized.startsWith("proyear_")) {
      return true;
    }
    return normalized.matches("rank_[789]d");
  }

  private static Duration remainingRequestTime(long deadlineNanos) throws TimeoutException {
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0L) {
      throw new TimeoutException("HumanSL move verification exceeded its time budget.");
    }
    return Duration.ofNanos(remaining);
  }

  private static Duration remainingAdaptiveRequestTime(long deadlineNanos) throws TimeoutException {
    long reserveNanos = TimeUnit.MILLISECONDS.toNanos(ADAPTIVE_RETURN_RESERVE_MILLIS);
    long remaining = deadlineNanos - System.nanoTime() - reserveNanos;
    if (remaining <= 0L) {
      throw new TimeoutException("No safe time remains for deeper HumanSL verification.");
    }
    return Duration.ofNanos(remaining);
  }

  private void terminateRequestBestEffort(int expectedGeneration, String terminateId) {
    BufferedOutputStream activeOutput;
    synchronized (this) {
      if (!started
          || expectedGeneration != activeProcessGeneration
          || outputStream == null
          || terminateId == null
          || terminateId.isEmpty()) {
        return;
      }
      activeOutput = outputStream;
    }
    JSONObject terminate =
        new JSONObject()
            .put("id", "humansl-terminate-" + nextRequestId.getAndIncrement())
            .put("action", "terminate")
            .put("terminateId", terminateId);
    try {
      synchronized (activeOutput) {
        if (!started || expectedGeneration != activeProcessGeneration) {
          return;
        }
        activeOutput.write((terminate.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        activeOutput.flush();
      }
    } catch (IOException ignored) {
      // The next normal request will perform the authoritative process-health check.
    }
  }

  private static void addVerificationMoves(
      Set<String> moves, List<HumanLikeMoveSelector.Candidate> candidates, boolean allowPass) {
    for (HumanLikeMoveSelector.Candidate candidate : candidates) {
      addVerificationMove(moves, candidate.move, allowPass);
    }
  }

  private static void addVerificationMove(Set<String> moves, String move, boolean allowPass) {
    String normalized = normalizeMove(move);
    if (!normalized.isEmpty() && (allowPass || !"pass".equals(normalized))) {
      moves.add(normalized);
    }
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
    return "pass".equalsIgnoreCase(searchTopMove(response));
  }

  private static String searchTopMove(JSONObject response) {
    JSONArray moveInfos = response == null ? null : response.optJSONArray("moveInfos");
    if (moveInfos == null || moveInfos.length() == 0) {
      return null;
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
    return topMove == null ? null : topMove.optString("move", null);
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
            failure == null ? new IOException(stoppedProcessMessage(generation)) : failure;
        String reason =
            withStartupDiagnostics(usefulMessage(ioException, "HumanSL analysis engine stopped."));
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
      // KataGo is accepting requests, but the HumanSL model has not yet answered the explicit
      // readiness probe. READY is emitted only after verifyReady receives that response.
      return StartupStage.CACHE_READY;
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
    String base =
        reason == null || reason.trim().isEmpty() ? "HumanSL engine failed." : reason.trim();
    return diagnostics.isEmpty() ? base : base + " | " + String.join(" | ", diagnostics);
  }

  private String stoppedProcessMessage(int generation) {
    Process active = process;
    if (generation != activeProcessGeneration || active == null || active.isAlive()) {
      return "HumanSL analysis engine stopped unexpectedly.";
    }
    try {
      return "HumanSL analysis engine stopped unexpectedly (exit code " + active.exitValue() + ").";
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
    } catch (RuntimeException | Error ignored) {
    }
  }

  private static String usefulMessage(Throwable exception, String fallback) {
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

  /** KataGo may pass once 40% of the board was played, or right after the opponent passed. */
  static boolean mayPass(BoardHistoryNode positionNode) {
    if (positionNode == null || positionNode.getData() == null) {
      return false;
    }
    BoardData data = positionNode.getData();
    return data.isPassNode()
        || data.moveNumber >= PASS_BOARD_SHARE * Board.boardWidth * Board.boardHeight;
  }

  interface ProcessStarter {
    Process start(ProcessBuilder processBuilder) throws IOException;
  }
}
