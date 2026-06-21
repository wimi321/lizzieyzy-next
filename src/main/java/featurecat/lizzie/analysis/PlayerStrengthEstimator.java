package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Estimates a player's practical strength from already cached main-line analysis. */
public final class PlayerStrengthEstimator {
  private static final int MIN_REPORT_SAMPLES = 6;
  private static final double WINRATE_TO_SCORE_LOSS = 6.0;
  private static final int ADDITIONAL_MOVE_ORDER = 999;
  private static final int AI_RANK_CAP = 10;
  private static final int OPENING_MOVE_LIMIT = 60;
  private static final int MIDDLEGAME_MOVE_LIMIT = 160;
  private static final double MIN_DIFFICULTY_WEIGHT = 0.05;
  private static final int STRONG_KYU_LEVEL = 4;
  private static final int LOW_DAN_LEVEL = 6;
  private static final int MID_DAN_LEVEL = 7;
  private static final int HIGH_DAN_LEVEL = 9;
  private static final double LOW_DIFFICULTY_EVIDENCE = 25.0;
  private static final double LOW_DIFFICULTY_TOP_FIRST_CHOICE_RATE = 0.35;
  private static final double LOW_DIFFICULTY_TOP_GOOD_MOVE_RATE = 0.74;
  private static final double TOP_PRO_FIRST_CHOICE_RATE = 0.50;
  private static final double TOP_PRO_GOOD_MOVE_RATE = 0.86;
  private static final double TOP_PRO_MISTAKE_RATE = 0.05;
  private static final double TOP_PRO_WEIGHTED_LOSS = 3.20;
  private static final double PRO_FIRST_CHOICE_RATE = 0.45;
  private static final double PRO_GOOD_MOVE_RATE = 0.86;
  private static final double PRO_MISTAKE_RATE = 0.06;
  private static final double PRO_WEIGHTED_LOSS = 3.00;

  private static final double EXCELLENT_SCORE_LOSS = 0.2;
  private static final double GREAT_SCORE_LOSS = 0.6;
  private static final double GOOD_SCORE_LOSS = 1.2;
  private static final double INACCURACY_SCORE_LOSS = 4.0;
  private static final double MISTAKE_SCORE_LOSS = 10.0;
  private static final String[] STRENGTH_BANDS = {
    "Beginner",
    "11-15k",
    "6-10k",
    "3-5k",
    "1-2k",
    "1-2d",
    "3-4d",
    "5-6d",
    "7d",
    "8d",
    "9d",
    "10d\u804c\u4e1a",
    "11d\u4e00\u7ebf\u804c\u4e1a",
    "12d AI"
  };
  private static final LevelThreshold[] FIRST_CHOICE_CAPS = {
    new LevelThreshold(0.62, 13),
    new LevelThreshold(0.50, 12),
    new LevelThreshold(0.46, 11),
    new LevelThreshold(0.42, 10),
    new LevelThreshold(0.38, 9),
    new LevelThreshold(0.34, 8),
    new LevelThreshold(0.30, 7),
    new LevelThreshold(0.24, 7),
    new LevelThreshold(0.18, 6),
    new LevelThreshold(0.12, 5),
    new LevelThreshold(0.06, 4)
  };
  private static final LevelThreshold[] GOOD_MOVE_CAPS = {
    new LevelThreshold(0.96, 13),
    new LevelThreshold(0.88, 12),
    new LevelThreshold(0.82, 11),
    new LevelThreshold(0.78, 10),
    new LevelThreshold(0.74, 9),
    new LevelThreshold(0.68, 8),
    new LevelThreshold(0.62, 7),
    new LevelThreshold(0.56, 6),
    new LevelThreshold(0.50, 5),
    new LevelThreshold(0.42, 4),
    new LevelThreshold(0.32, 3),
    new LevelThreshold(0.22, 2)
  };
  private static final LevelThreshold[] MISTAKE_CAPS = {
    new LevelThreshold(0.005, 13),
    new LevelThreshold(0.050, 12),
    new LevelThreshold(0.060, 11),
    new LevelThreshold(0.070, 10),
    new LevelThreshold(0.100, 9),
    new LevelThreshold(0.140, 8),
    new LevelThreshold(0.180, 7),
    new LevelThreshold(0.240, 6),
    new LevelThreshold(0.310, 5),
    new LevelThreshold(0.400, 4),
    new LevelThreshold(0.520, 3),
    new LevelThreshold(0.660, 2)
  };
  private static final LevelThreshold[] MEDIAN_LOSS_CAPS = {
    new LevelThreshold(0.05, 13),
    new LevelThreshold(0.25, 12),
    new LevelThreshold(0.50, 11),
    new LevelThreshold(0.80, 10),
    new LevelThreshold(1.10, 9),
    new LevelThreshold(1.50, 8),
    new LevelThreshold(2.00, 7),
    new LevelThreshold(2.60, 6),
    new LevelThreshold(3.40, 5),
    new LevelThreshold(4.60, 4),
    new LevelThreshold(6.50, 3),
    new LevelThreshold(9.00, 2)
  };

  private PlayerStrengthEstimator() {}

  private static volatile StrengthModel activeModel = StrengthModel.XGBOOST20TUN;

  public static StrengthModel activeModel() {
    return activeModel;
  }

  public static void setActiveModel(StrengthModel model) {
    activeModel = effectiveModel(model);
  }

  public static StrengthModel[] selectableModels() {
    List<StrengthModel> models = new ArrayList<>();
    models.add(StrengthModel.XGBOOST20TUN);
    models.add(StrengthModel.XGBOOST20TUN_PREVIOUS);
    models.addAll(StrengthModel.importedModels());
    List<StrengthModel> available = new ArrayList<>();
    for (StrengthModel model : models) {
      if (isModelAvailable(model)) {
        available.add(model);
      }
    }
    if (available.isEmpty()) {
      available.add(StrengthModel.XGBOOST20TUN);
    }
    return available.toArray(new StrengthModel[0]);
  }

  public static StrengthModel importXGBoost20TunModel(Path boosterPath) throws IOException {
    return importStrengthModel(boosterPath);
  }

  public static StrengthModel importStrengthModel(Path modelPath) throws IOException {
    return StrengthModel.importModel(modelPath);
  }

  public static Report estimate(BoardHistoryNode start) {
    return estimate(start, activeModel());
  }

  public static Report estimate(BoardHistoryNode start, StrengthModel model) {
    StrengthModel selectedModel = effectiveModel(model);
    if (start == null) {
      return Report.empty(selectedModel);
    }

    Accumulator black = new Accumulator();
    Accumulator white = new Accumulator();
    BoardHistoryNode previous = start;
    Optional<BoardHistoryNode> next = previous.next(true);
    while (next.isPresent()) {
      BoardHistoryNode current = next.get();
      Sample sample = sample(previous.getData(), current.getData());
      if (sample != null) {
        if (sample.color.isBlack()) {
          black.add(sample);
        } else if (sample.color.isWhite()) {
          white.add(sample);
        }
      }
      previous = current;
      next = current.next(true);
    }

    SideReport blackReport = black.toReport(selectedModel);
    SideReport whiteReport = white.toReport(selectedModel);
    SideReport overallReport = Accumulator.merge(black, white).toReport(selectedModel);
    return new Report(blackReport, whiteReport, overallReport, selectedModel);
  }

  private static Sample sample(BoardData previous, BoardData current) {
    if (previous == null || current == null || !current.isHistoryActionNode()) {
      return null;
    }
    if (!current.lastMoveColor.isBlack() && !current.lastMoveColor.isWhite()) {
      return null;
    }
    if (!hasPrimaryAnalysisPayload(previous) || !hasPrimaryAnalysisPayload(current)) {
      return null;
    }

    CandidateMatch playedCandidate = findPlayedCandidate(previous, current);
    LossEstimate lossEstimate = lossEstimate(previous, current, playedCandidate);
    double scoreEquivalentLoss = positive(lossEstimate.scoreEquivalentLoss());
    double complexity = complexity(previous);
    double adjustedWeight = adjustedWeight(complexity, scoreEquivalentLoss);
    boolean firstChoice =
        playedCandidate.rank == 0 || playedCoordinate(current).equalsIgnoreCase(topMove(previous));
    return new Sample(
        current.lastMoveColor,
        current.moveNumber,
        positive(lossEstimate.winrateLoss),
        lossEstimate.scoreLoss.map(PlayerStrengthEstimator::positive),
        firstChoice,
        playedCandidate.rank >= 0 ? playedCandidate.rank : ADDITIONAL_MOVE_ORDER,
        categorize(scoreEquivalentLoss),
        categorizeMoveRank(lossEstimate),
        scoreEquivalentLoss,
        complexity,
        adjustedWeight);
  }

  private static boolean hasPrimaryAnalysisPayload(BoardData data) {
    return data != null
        && (data.getPlayouts() > 0
            || data.analysisHeaderSlots > 0
            || !isBlank(data.engineName)
            || (data.bestMoves != null && !data.bestMoves.isEmpty())
            || data.isKataData
            || (data.estimateArray != null && !data.estimateArray.isEmpty()));
  }

  private static LossEstimate lossEstimate(
      BoardData previous, BoardData current, CandidateMatch playedCandidate) {
    if (playedCandidate.move != null
        && previous.bestMoves != null
        && !previous.bestMoves.isEmpty()) {
      MoveData top = previous.bestMoves.get(0);
      Optional<Double> scoreLoss = candidateScoreLoss(top, playedCandidate.move);
      Optional<Double> winrateLoss = candidateWinrateLoss(top, playedCandidate.move);
      if (scoreLoss.isPresent() || winrateLoss.isPresent()) {
        return new LossEstimate(
            winrateLoss.orElseGet(() -> fallbackWinrateLoss(previous, current)), scoreLoss);
      }
    }
    return new LossEstimate(
        fallbackWinrateLoss(previous, current), fallbackScoreLoss(previous, current));
  }

  private static Optional<Double> candidateScoreLoss(MoveData top, MoveData actual) {
    if (top == null
        || actual == null
        || !hasMoveScorePayload(top)
        || !hasMoveScorePayload(actual)) {
      return Optional.empty();
    }
    return Optional.of(top.scoreMean - actual.scoreMean);
  }

  private static Optional<Double> candidateWinrateLoss(MoveData top, MoveData actual) {
    if (top == null
        || actual == null
        || !hasMoveWinratePayload(top)
        || !hasMoveWinratePayload(actual)) {
      return Optional.empty();
    }
    return Optional.of(top.winrate - actual.winrate);
  }

  private static Optional<Double> candidateScoreEquivalentLoss(MoveData top, MoveData actual) {
    Optional<Double> scoreLoss = candidateScoreLoss(top, actual);
    if (scoreLoss.isPresent()) {
      return scoreLoss;
    }
    return candidateWinrateLoss(top, actual).map(loss -> loss / WINRATE_TO_SCORE_LOSS);
  }

  private static boolean hasMoveScorePayload(MoveData move) {
    return move != null && move.isKataData && Double.isFinite(move.scoreMean);
  }

  private static boolean hasMoveWinratePayload(MoveData move) {
    return move != null
        && Double.isFinite(move.winrate)
        && (move.winrate > 0.0 || move.playouts > 0 || move.isKataData);
  }

  private static Optional<Double> fallbackScoreLoss(BoardData previous, BoardData current) {
    if (!hasScorePayload(previous, current)) {
      return Optional.empty();
    }
    return Optional.of(scoreLoss(previous, current));
  }

  private static boolean hasScorePayload(BoardData previous, BoardData current) {
    return (previous.isKataData || current.isKataData)
        && Double.isFinite(previous.scoreMean)
        && Double.isFinite(current.scoreMean);
  }

  private static double fallbackWinrateLoss(BoardData previous, BoardData current) {
    double expectedOpponentWinrate =
        previous.blackToPlay == current.blackToPlay ? previous.winrate : 100.0 - previous.winrate;
    return current.winrate - expectedOpponentWinrate;
  }

  private static double scoreLoss(BoardData previous, BoardData current) {
    double expectedOpponentScore =
        previous.blackToPlay == current.blackToPlay ? previous.scoreMean : -previous.scoreMean;
    return current.scoreMean - expectedOpponentScore;
  }

  private static CandidateMatch findPlayedCandidate(BoardData previous, BoardData current) {
    if (previous.bestMoves == null || previous.bestMoves.isEmpty()) {
      return CandidateMatch.missing();
    }
    String played = playedCoordinate(current);
    if (isBlank(played)) {
      return CandidateMatch.missing();
    }
    for (int index = 0; index < previous.bestMoves.size(); index++) {
      MoveData move = previous.bestMoves.get(index);
      if (move != null && played.equalsIgnoreCase(move.coordinate)) {
        return new CandidateMatch(index, move);
      }
    }
    return CandidateMatch.missing();
  }

  private static String playedCoordinate(BoardData current) {
    if (current == null) {
      return "";
    }
    if (current.isPassNode()) {
      return "pass";
    }
    return current
        .lastMove
        .map(move -> Board.convertCoordinatesToName(move[0], move[1]))
        .orElse("");
  }

  private static String topMove(BoardData previous) {
    if (previous == null || previous.bestMoves == null || previous.bestMoves.isEmpty()) {
      return "";
    }
    String topMove = previous.bestMoves.get(0).coordinate;
    return isBlank(topMove) ? "" : topMove;
  }

  private static double complexity(BoardData data) {
    if (data == null || data.bestMoves == null || data.bestMoves.isEmpty()) {
      return 0.0;
    }
    MoveData top = data.bestMoves.get(0);
    double weightedLossSum = 0.0;
    double priorSum = 0.0;
    for (int index = 0; index < data.bestMoves.size(); index++) {
      MoveData move = data.bestMoves.get(index);
      if (move == null || candidateOrder(move, index) >= ADDITIONAL_MOVE_ORDER) {
        continue;
      }
      double prior = policyWeight(move);
      if (prior <= 0.0) {
        continue;
      }
      Optional<Double> candidateLoss = candidateScoreEquivalentLoss(top, move);
      if (candidateLoss.isEmpty()) {
        continue;
      }
      weightedLossSum += positive(candidateLoss.get()) * prior;
      priorSum += prior;
    }
    if (priorSum > 0.0) {
      return clamp(weightedLossSum / priorSum, 0.0, 1.0);
    }
    return fallbackComplexity(data.bestMoves);
  }

  private static double fallbackComplexity(List<MoveData> moves) {
    if (moves == null || moves.size() < 2) {
      return 0.0;
    }
    MoveData top = moves.get(0);
    MoveData second = moves.get(1);
    return candidateScoreEquivalentLoss(top, second)
        .map(loss -> clamp(positive(loss) / GOOD_SCORE_LOSS, 0.0, 1.0))
        .orElse(0.0);
  }

  private static int candidateOrder(MoveData move, int fallbackOrder) {
    return move.order > 0 ? move.order : fallbackOrder;
  }

  private static double policyWeight(MoveData move) {
    if (move == null || !Double.isFinite(move.policy)) {
      return 0.0;
    }
    return Math.max(0.0, move.policy);
  }

  private static double adjustedWeight(double complexity, double scoreEquivalentLoss) {
    return clamp(
        Math.max(complexity, positive(scoreEquivalentLoss) / INACCURACY_SCORE_LOSS),
        MIN_DIFFICULTY_WEIGHT,
        1.0);
  }

  private static MoveCategory categorize(double scoreEquivalentLoss) {
    double loss = positive(scoreEquivalentLoss);
    if (loss < EXCELLENT_SCORE_LOSS) {
      return MoveCategory.EXCELLENT;
    }
    if (loss < GREAT_SCORE_LOSS) {
      return MoveCategory.GREAT;
    }
    if (loss < GOOD_SCORE_LOSS) {
      return MoveCategory.GOOD;
    }
    if (loss < INACCURACY_SCORE_LOSS) {
      return MoveCategory.INACCURACY;
    }
    if (loss < MISTAKE_SCORE_LOSS) {
      return MoveCategory.MISTAKE;
    }
    return MoveCategory.BLUNDER;
  }

  private static MoveRankDefinition.Rank categorizeMoveRank(LossEstimate lossEstimate) {
    return MoveRankDefinition.classifyLosses(
        lossEstimate.winrateLoss, lossEstimate.scoreLoss, Lizzie.config);
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static double positive(double loss) {
    return Math.max(0.0, loss);
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }

  public static SideReport summarizeSamples(List<Sample> samples) {
    return summarizeSamples(samples, activeModel());
  }

  public static SideReport summarizeSamples(List<Sample> samples, StrengthModel model) {
    Accumulator accumulator = new Accumulator();
    if (samples != null) {
      for (Sample sample : samples) {
        if (sample != null) {
          accumulator.add(sample);
        }
      }
    }
    return accumulator.toReport(effectiveModel(model));
  }

  private static StrengthModel effectiveModel(StrengthModel model) {
    if (isModelAvailable(model)) {
      return model;
    }
    if (isModelAvailable(StrengthModel.XGBOOST20TUN)) {
      return StrengthModel.XGBOOST20TUN;
    }
    return model == null ? StrengthModel.XGBOOST20TUN : model;
  }

  private static boolean isModelAvailable(StrengthModel model) {
    if (model == null) {
      return false;
    }
    if (!XGBoostStrengthModel.isXGBoostRankValueAvailable(
        model.boosterResourcePath(), model.boosterPath(), model.featureIndices())) {
      return false;
    }
    Path calibratorPath = model.calibratorPath();
    String calibratorResourcePath = model.calibratorResourcePath();
    return (calibratorPath == null && calibratorResourcePath == null)
        || XGBoost20TunResidualCalibrator.isAvailable(calibratorResourcePath, calibratorPath);
  }

  public static final class StrengthModel {
    private static final String USER_MODEL_DIR_NAME = "strength-models";
    private static final String XGBOOST20TUN_PREVIOUS_BOOSTER_RESOURCE_PATH =
        "/models/strength/xgboost20tun_previous_booster.json";
    private static final String XGBOOST20TUN_PREVIOUS_CALIBRATOR_RESOURCE_PATH =
        "/models/strength/xgboost20tun_previous_residual_calibrator.json";
    public static final StrengthModel XGBOOST20TUN =
        new StrengthModel(
            "bundled:xgboost20tun",
            "xgboost20tun",
            null,
            null,
            XGBoost20TunResidualCalibrator.CALIBRATOR_RESOURCE_PATH,
            null,
            XGBoostStrengthModel.Features.defaultXGBoost20TunFeatureIndices(),
            XGBoostStrengthModel.xgboost20TunSigma());
    public static final StrengthModel XGBOOST20TUN_PREVIOUS =
        new StrengthModel(
            "bundled:xgboost20tun-previous",
            "xgboost20tun-previous",
            XGBOOST20TUN_PREVIOUS_BOOSTER_RESOURCE_PATH,
            null,
            XGBOOST20TUN_PREVIOUS_CALIBRATOR_RESOURCE_PATH,
            null,
            XGBoostStrengthModel.Features.defaultXGBoost20TunFeatureIndices(),
            XGBoostStrengthModel.xgboost20TunSigma());

    private final String id;
    private final String displayName;
    private final String boosterResourcePath;
    private final Path boosterPath;
    private final String calibratorResourcePath;
    private final Path calibratorPath;
    private final int[] featureIndices;
    private final double predictionSigma;

    private StrengthModel(
        String id,
        String displayName,
        String boosterResourcePath,
        Path boosterPath,
        String calibratorResourcePath,
        Path calibratorPath,
        int[] featureIndices,
        double predictionSigma) {
      this.id = id;
      this.displayName = displayName;
      this.boosterResourcePath = isBlank(boosterResourcePath) ? null : boosterResourcePath.trim();
      this.boosterPath = boosterPath == null ? null : boosterPath.toAbsolutePath().normalize();
      this.calibratorResourcePath =
          isBlank(calibratorResourcePath) ? null : calibratorResourcePath.trim();
      this.calibratorPath =
          calibratorPath == null ? null : calibratorPath.toAbsolutePath().normalize();
      this.featureIndices =
          featureIndices == null
              ? XGBoostStrengthModel.Features.defaultXGBoost20TunFeatureIndices()
              : featureIndices.clone();
      this.predictionSigma =
          predictionSigma > 0.0 ? predictionSigma : XGBoostStrengthModel.xgboost20TunSigma();
    }

    public String displayName() {
      return displayName;
    }

    Path boosterPath() {
      return boosterPath;
    }

    String boosterResourcePath() {
      return boosterResourcePath;
    }

    Path calibratorPath() {
      return calibratorPath;
    }

    String calibratorResourcePath() {
      return calibratorResourcePath;
    }

    int[] featureIndices() {
      return featureIndices.clone();
    }

    double predictionSigma() {
      return predictionSigma;
    }

    private static List<StrengthModel> importedModels() {
      Path root = modelDirectory();
      if (!Files.isDirectory(root)) {
        return Collections.emptyList();
      }
      List<StrengthModel> models = new ArrayList<>();
      try (var stream = Files.list(root)) {
        stream
            .filter(Files::isDirectory)
            .map(StrengthModel::fromDirectory)
            .filter(Objects::nonNull)
            .sorted((left, right) -> left.displayName.compareToIgnoreCase(right.displayName))
            .forEach(models::add);
      } catch (IOException e) {
        System.err.println("Failed to list strength models: " + e.getMessage());
      }
      return models;
    }

    private static StrengthModel importModel(Path sourceModelPath) throws IOException {
      if (sourceModelPath == null) {
        throw new IOException("No model file selected.");
      }
      Path sourceModel = sourceModelPath.toAbsolutePath().normalize();
      if (!Files.isRegularFile(sourceModel)) {
        throw new IOException("Model file does not exist: " + sourceModel);
      }
      if (sourceModel.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
        return importZipModel(sourceModel);
      }
      return importBoosterModel(sourceModel);
    }

    private static StrengthModel importBoosterModel(Path sourceBooster) throws IOException {
      Path sourceMetadata = findSiblingMetadata(sourceBooster);
      ModelSpec spec =
          sourceMetadata == null ? ModelSpec.defaultXGBoost20Tun() : ModelSpec.load(sourceMetadata);
      validateBooster(sourceBooster, spec, sourceMetadata != null);
      Path root = modelDirectory();
      Files.createDirectories(root);
      String displayName =
          isBlank(spec.displayName)
              ? stem(sourceBooster.getFileName().toString())
              : spec.displayName;
      Path targetDir = uniqueModelDirectory(root, safeSlug(displayName));
      try {
        Files.createDirectories(targetDir);
        Path targetBooster = targetDir.resolve("booster.json");
        Files.copy(sourceBooster, targetBooster, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(targetDir.resolve("display-name.txt"), displayName);
        if (sourceMetadata == null) {
          Files.writeString(targetDir.resolve("metadata.json"), spec.toJson().toString(2));
        } else {
          Files.copy(
              sourceMetadata,
              targetDir.resolve("metadata.json"),
              StandardCopyOption.REPLACE_EXISTING);
        }

        Path sourceCalibrator = findSiblingCalibrator(sourceBooster);
        if (sourceCalibrator != null) {
          Path targetCalibrator = targetDir.resolve("calibrator.json");
          Files.copy(sourceCalibrator, targetCalibrator, StandardCopyOption.REPLACE_EXISTING);
        }
        StrengthModel imported = fromDirectory(targetDir);
        if (!isModelAvailable(imported)) {
          throw new IOException("Imported model did not pass runtime validation.");
        }
        return imported;
      } catch (IOException | RuntimeException e) {
        deleteRecursivelyQuietly(targetDir);
        if (e instanceof IOException) {
          throw (IOException) e;
        }
        throw new IOException("Imported model did not pass runtime validation.", e);
      }
    }

    private static StrengthModel importZipModel(Path sourceZip) throws IOException {
      Path root = modelDirectory();
      Files.createDirectories(root);
      String fallbackDisplayName = stem(sourceZip.getFileName().toString());
      Path targetDir = uniqueModelDirectory(root, safeSlug(fallbackDisplayName));
      try {
        Files.createDirectories(targetDir);
        try (ZipFile zip = new ZipFile(sourceZip.toFile())) {
          ZipEntry boosterEntry = findZipEntry(zip, "booster.json", "booster");
          if (boosterEntry == null) {
            throw new IOException("Model bundle is missing booster.json.");
          }
          ZipEntry metadataEntry = findZipEntry(zip, "metadata.json", "metadata");
          if (metadataEntry == null) {
            throw new IOException("Model bundle is missing metadata.json with feature_order.");
          }
          ZipEntry calibratorEntry = findZipEntry(zip, "calibrator.json", "calibrator");
          copyZipEntry(zip, boosterEntry, targetDir.resolve("booster.json"));
          copyZipEntry(zip, metadataEntry, targetDir.resolve("metadata.json"));
          if (calibratorEntry != null) {
            copyZipEntry(zip, calibratorEntry, targetDir.resolve("calibrator.json"));
          }
        }
        StrengthModel imported = fromDirectory(targetDir);
        if (imported == null || !isModelAvailable(imported)) {
          throw new IOException("Imported model bundle did not pass runtime validation.");
        }
        Files.writeString(targetDir.resolve("display-name.txt"), imported.displayName());
        return imported;
      } catch (IOException | RuntimeException e) {
        deleteRecursivelyQuietly(targetDir);
        if (e instanceof IOException) {
          throw (IOException) e;
        }
        throw new IOException("Imported model bundle did not pass runtime validation.", e);
      }
    }

    private static StrengthModel fromDirectory(Path directory) {
      Path booster = directory.resolve("booster.json");
      if (!Files.isRegularFile(booster)) {
        return null;
      }
      ModelSpec spec = ModelSpec.loadOrDefault(directory.resolve("metadata.json"));
      Path calibrator = directory.resolve("calibrator.json");
      return new StrengthModel(
          "file:" + directory.toAbsolutePath().normalize(),
          displayName(directory, spec),
          null,
          booster,
          null,
          Files.isRegularFile(calibrator) ? calibrator : null,
          spec.featureIndices,
          spec.predictionSigma);
    }

    private static String displayName(Path directory, ModelSpec spec) {
      Path displayName = directory.resolve("display-name.txt");
      if (Files.isRegularFile(displayName)) {
        try {
          String value = Files.readString(displayName).trim();
          if (!value.isEmpty()) {
            return value;
          }
        } catch (IOException ignored) {
        }
      }
      if (!isBlank(spec.displayName)) {
        return spec.displayName;
      }
      return directory.getFileName().toString();
    }

    private static Path modelDirectory() {
      try {
        if (Lizzie.config != null) {
          return Lizzie.config.getWorkDirectory().toPath().resolve(USER_MODEL_DIR_NAME);
        }
      } catch (RuntimeException ignored) {
      }
      try {
        return Config.resolveWritableFallbackDir().resolve(USER_MODEL_DIR_NAME);
      } catch (IOException e) {
        return Path.of(USER_MODEL_DIR_NAME).toAbsolutePath().normalize();
      }
    }

    private static Path findSiblingCalibrator(Path sourceBooster) {
      Path parent = sourceBooster.getParent();
      if (parent == null) {
        return null;
      }
      String fileName = sourceBooster.getFileName().toString();
      List<Path> candidates = new ArrayList<>();
      if (fileName.contains("booster")) {
        candidates.add(parent.resolve(fileName.replace("booster", "calibrator")));
      }
      candidates.add(parent.resolve("calibrator.json"));
      candidates.add(parent.resolve("xgboost20tun_residual_calibrator.json"));
      for (Path candidate : candidates) {
        if (Files.isRegularFile(candidate)
            && XGBoost20TunResidualCalibrator.load(candidate) != null) {
          return candidate;
        }
      }
      try (var stream = Files.list(parent)) {
        Optional<Path> wildcard =
            stream
                .filter(Files::isRegularFile)
                .filter(
                    path ->
                        path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                .filter(
                    path ->
                        path.getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .contains("calibrator"))
                .sorted()
                .filter(path -> XGBoost20TunResidualCalibrator.load(path) != null)
                .findFirst();
        if (wildcard.isPresent()) {
          return wildcard.get();
        }
      } catch (IOException ignored) {
      }
      return null;
    }

    private static Path findSiblingMetadata(Path sourceBooster) {
      Path parent = sourceBooster.getParent();
      if (parent == null) {
        return null;
      }
      String fileName = sourceBooster.getFileName().toString();
      List<Path> candidates = new ArrayList<>();
      if (fileName.contains("booster")) {
        candidates.add(parent.resolve(fileName.replace("booster", "metadata")));
      }
      candidates.add(parent.resolve("metadata.json"));
      candidates.add(parent.resolve("model_metadata.json"));
      for (Path candidate : candidates) {
        if (Files.isRegularFile(candidate)) {
          return candidate;
        }
      }
      try (var stream = Files.list(parent)) {
        Optional<Path> wildcard =
            stream
                .filter(Files::isRegularFile)
                .filter(
                    path ->
                        path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                .filter(
                    path ->
                        path.getFileName().toString().toLowerCase(Locale.ROOT).contains("metadata"))
                .sorted()
                .findFirst();
        if (wildcard.isPresent()) {
          return wildcard.get();
        }
      } catch (IOException ignored) {
      }
      return null;
    }

    private static void validateBooster(Path booster, ModelSpec spec, boolean metadataProvided)
        throws IOException {
      if (XGBoostStrengthModel.isXGBoostRankValueAvailable(booster, spec.featureIndices)) {
        return;
      }
      int boosterFeatureCount = XGBoostStrengthModel.featureCount(booster);
      if (boosterFeatureCount < 0) {
        throw new IOException("The selected file is not a readable XGBoost JSON booster.");
      }
      if (!metadataProvided && boosterFeatureCount != spec.featureIndices.length) {
        throw new IOException(
            "This booster has "
                + boosterFeatureCount
                + " features. Add metadata.json with feature_order, or package booster.json and metadata.json in a zip.");
      }
      throw new IOException(
          "Booster feature count "
              + boosterFeatureCount
              + " does not match metadata feature_order length "
              + spec.featureIndices.length
              + ".");
    }

    private static ZipEntry findZipEntry(
        ZipFile zip, String exactLeafName, String fallbackKeyword) {
      ZipEntry fallback = null;
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry.isDirectory()) {
          continue;
        }
        String leafName = zipLeafName(entry.getName());
        String lowerLeaf = leafName.toLowerCase(Locale.ROOT);
        if (lowerLeaf.equals(exactLeafName)) {
          return entry;
        }
        if (fallback == null
            && lowerLeaf.endsWith(".json")
            && lowerLeaf.contains(fallbackKeyword)) {
          fallback = entry;
        }
      }
      return fallback;
    }

    private static String zipLeafName(String name) {
      int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
      return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static void copyZipEntry(ZipFile zip, ZipEntry entry, Path target) throws IOException {
      try (InputStream stream = zip.getInputStream(entry)) {
        Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }

    private static void deleteRecursivelyQuietly(Path directory) {
      if (directory == null || !Files.exists(directory)) {
        return;
      }
      try (var stream = Files.walk(directory)) {
        stream
            .sorted(Comparator.reverseOrder())
            .forEach(
                path -> {
                  try {
                    Files.deleteIfExists(path);
                  } catch (IOException ignored) {
                  }
                });
      } catch (IOException ignored) {
      }
    }

    private static final class ModelSpec {
      private final String displayName;
      private final int[] featureIndices;
      private final String[] featureOrder;
      private final double predictionSigma;

      private ModelSpec(
          String displayName, int[] featureIndices, String[] featureOrder, double predictionSigma) {
        this.displayName = displayName;
        this.featureIndices = featureIndices.clone();
        this.featureOrder = featureOrder.clone();
        this.predictionSigma =
            predictionSigma > 0.0 ? predictionSigma : XGBoostStrengthModel.xgboost20TunSigma();
      }

      private static ModelSpec defaultXGBoost20Tun() {
        return new ModelSpec(
            "",
            XGBoostStrengthModel.Features.defaultXGBoost20TunFeatureIndices(),
            XGBoostStrengthModel.Features.defaultXGBoost20TunFeatureOrder(),
            XGBoostStrengthModel.xgboost20TunSigma());
      }

      private static ModelSpec invalid() {
        return new ModelSpec(
            "", new int[0], new String[0], XGBoostStrengthModel.xgboost20TunSigma());
      }

      private static ModelSpec loadOrDefault(Path metadataPath) {
        if (!Files.isRegularFile(metadataPath)) {
          return defaultXGBoost20Tun();
        }
        try {
          return load(metadataPath);
        } catch (IOException | RuntimeException e) {
          System.err.println(
              "Failed to load strength model metadata " + metadataPath + ": " + e.getMessage());
          return invalid();
        }
      }

      private static ModelSpec load(Path metadataPath) throws IOException {
        JSONObject root = new JSONObject(Files.readString(metadataPath));
        String modelType =
            firstNonBlank(root.optString("model_type", ""), root.optString("type", ""));
        if (!isBlank(modelType)
            && !modelType.equals("xgboost_rank_value")
            && !modelType.equals("xgboost20tun")) {
          throw new IOException("Unsupported strength model_type: " + modelType);
        }
        JSONArray order = root.optJSONArray("feature_order");
        if (order == null) {
          order = root.optJSONArray("final_features");
        }
        if (order == null || order.length() == 0) {
          throw new IOException("metadata.json must contain non-empty feature_order.");
        }
        int[] indices = new int[order.length()];
        String[] names = new String[order.length()];
        for (int index = 0; index < order.length(); index++) {
          String name = order.getString(index).trim();
          int fullIndex = XGBoostStrengthModel.Features.fullFeatureIndex(name);
          if (fullIndex < 0) {
            throw new IOException("Unknown feature in metadata feature_order: " + name);
          }
          names[index] = name;
          indices[index] = fullIndex;
        }
        String displayName =
            firstNonBlank(
                root.optString("display_name", ""),
                root.optString("model_name", ""),
                root.optString("name", ""));
        double sigma =
            root.has("prediction_sigma")
                ? root.optDouble("prediction_sigma", XGBoostStrengthModel.xgboost20TunSigma())
                : root.optDouble("sigma", XGBoostStrengthModel.xgboost20TunSigma());
        return new ModelSpec(displayName, indices, names, sigma);
      }

      private JSONObject toJson() {
        JSONObject root = new JSONObject();
        root.put("model_type", "xgboost_rank_value");
        if (!isBlank(displayName)) {
          root.put("display_name", displayName);
        }
        JSONArray order = new JSONArray();
        for (String feature : featureOrder) {
          order.put(feature);
        }
        root.put("feature_order", order);
        root.put("prediction_sigma", predictionSigma);
        return root;
      }

      private static String firstNonBlank(String... values) {
        for (String value : values) {
          if (!isBlank(value)) {
            return value.trim();
          }
        }
        return "";
      }
    }

    private static Path uniqueModelDirectory(Path root, String slug) {
      Path candidate = root.resolve(slug);
      int suffix = 2;
      while (Files.exists(candidate)) {
        candidate = root.resolve(slug + "-" + suffix);
        suffix++;
      }
      return candidate;
    }

    private static String safeSlug(String value) {
      String slug = value.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
      return slug.isEmpty() ? "xgboost20tun" : slug;
    }

    private static String stem(String fileName) {
      int dot = fileName.lastIndexOf('.');
      return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    @Override
    public String toString() {
      return displayName;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof StrengthModel)) {
        return false;
      }
      StrengthModel that = (StrengthModel) other;
      return id.equals(that.id);
    }

    @Override
    public int hashCode() {
      return id.hashCode();
    }
  }

  public static final class Report {
    public final SideReport black;
    public final SideReport white;
    public final SideReport overall;
    public final StrengthModel model;

    private Report(SideReport black, SideReport white, SideReport overall, StrengthModel model) {
      this.black = black;
      this.white = white;
      this.overall = overall;
      this.model = model;
    }

    private static Report empty(StrengthModel model) {
      SideReport empty = new Accumulator().toReport(model);
      return new Report(empty, empty, empty, model);
    }

    public boolean hasEnoughData() {
      return overall.sampleCount >= MIN_REPORT_SAMPLES;
    }
  }

  public static final class SideReport {
    public final int sampleCount;
    public final int scoreSampleCount;
    public final StrengthModel model;
    public final double rankValue;
    public final double predictionSigma;
    public final double qualityScore;
    public final double averageWinrateLoss;
    public final double averageScoreLoss;
    public final double medianScoreLoss;
    public final double weightedScoreLoss;
    public final double averageScoreEquivalentLoss;
    public final double matchRate;
    public final double firstChoiceRate;
    public final double goodMoveRate;
    public final double moveRankGoodMoveRate;
    public final int[] moveRankCounts;
    public final double badMoveRate;
    public final double mistakeRate;
    public final double blunderRate;
    public final double averageDifficulty;
    public final String strengthBand;
    public final Confidence confidence;
    public final List<Sample> samples;

    private SideReport(
        int sampleCount,
        int scoreSampleCount,
        StrengthModel model,
        double rankValue,
        double predictionSigma,
        double qualityScore,
        double averageWinrateLoss,
        double averageScoreLoss,
        double medianScoreLoss,
        double weightedScoreLoss,
        double averageScoreEquivalentLoss,
        double matchRate,
        double firstChoiceRate,
        double goodMoveRate,
        double moveRankGoodMoveRate,
        int[] moveRankCounts,
        double badMoveRate,
        double mistakeRate,
        double blunderRate,
        double averageDifficulty,
        String strengthBand,
        Confidence confidence,
        List<Sample> samples) {
      this.sampleCount = sampleCount;
      this.scoreSampleCount = scoreSampleCount;
      this.model = model;
      this.rankValue = rankValue;
      this.predictionSigma = predictionSigma;
      this.qualityScore = qualityScore;
      this.averageWinrateLoss = averageWinrateLoss;
      this.averageScoreLoss = averageScoreLoss;
      this.medianScoreLoss = medianScoreLoss;
      this.weightedScoreLoss = weightedScoreLoss;
      this.averageScoreEquivalentLoss = averageScoreEquivalentLoss;
      this.matchRate = matchRate;
      this.firstChoiceRate = firstChoiceRate;
      this.goodMoveRate = goodMoveRate;
      this.moveRankGoodMoveRate = moveRankGoodMoveRate;
      this.moveRankCounts = moveRankCounts == null ? new int[0] : moveRankCounts.clone();
      this.badMoveRate = badMoveRate;
      this.mistakeRate = mistakeRate;
      this.blunderRate = blunderRate;
      this.averageDifficulty = averageDifficulty;
      this.strengthBand = strengthBand;
      this.confidence = confidence;
      this.samples = Collections.unmodifiableList(samples);
    }

    public boolean hasSamples() {
      return sampleCount > 0;
    }

    public String qualityScoreText() {
      return String.format(Locale.US, "%.0f", qualityScore);
    }

    public String rankValueText() {
      return String.format(Locale.US, "%.1f", rankValue);
    }

    public String predictionSigmaText() {
      if (!Double.isFinite(predictionSigma) || predictionSigma <= 0.0) {
        return "-";
      }
      return String.format(Locale.US, "%.1f", predictionSigma);
    }

    public String averageWinrateLossText() {
      return String.format(Locale.US, "%.1f%%", averageWinrateLoss);
    }

    public String averageScoreLossText() {
      if (scoreSampleCount <= 0) {
        return "-";
      }
      return String.format(Locale.US, "%.1f", averageScoreLoss);
    }

    public String medianScoreLossText() {
      if (scoreSampleCount <= 0) {
        return "-";
      }
      return String.format(Locale.US, "%.1f", medianScoreLoss);
    }

    public String weightedScoreLossText() {
      return String.format(Locale.US, "%.1f", weightedScoreLoss);
    }

    public String averageScoreEquivalentLossText() {
      return String.format(Locale.US, "%.1f", averageScoreEquivalentLoss);
    }

    public String matchRateText() {
      return percentText(matchRate);
    }

    public String percentText(double value) {
      return String.format(Locale.US, "%.0f%%", value * 100.0);
    }

    public String difficultyText() {
      return String.format(Locale.US, "%.0f", averageDifficulty);
    }

    public int moveRankCount(MoveRankDefinition.Rank rank) {
      if (rank == null || rank.ordinal() >= moveRankCounts.length) {
        return 0;
      }
      return moveRankCounts[rank.ordinal()];
    }
  }

  public enum Confidence {
    LOW,
    MEDIUM,
    HIGH
  }

  public enum MoveCategory {
    EXCELLENT,
    GREAT,
    GOOD,
    INACCURACY,
    MISTAKE,
    BLUNDER;

    public boolean isGoodMove() {
      return this == EXCELLENT || this == GREAT || this == GOOD;
    }

    public boolean isMistake() {
      return this == MISTAKE || this == BLUNDER;
    }
  }

  public static final class Sample {
    public final Stone color;
    public final int moveNumber;
    public final double winrateLoss;
    public final Optional<Double> scoreLoss;
    public final boolean firstChoice;
    public final int aiRank;
    public final MoveCategory category;
    public final MoveRankDefinition.Rank moveRankCategory;
    public final double scoreEquivalentLoss;
    public final double complexity;
    public final double adjustedWeight;

    private Sample(
        Stone color,
        int moveNumber,
        double winrateLoss,
        Optional<Double> scoreLoss,
        boolean firstChoice,
        int aiRank,
        MoveCategory category,
        MoveRankDefinition.Rank moveRankCategory,
        double scoreEquivalentLoss,
        double complexity,
        double adjustedWeight) {
      this.color = color;
      this.moveNumber = moveNumber;
      this.winrateLoss = winrateLoss;
      this.scoreLoss = scoreLoss;
      this.firstChoice = firstChoice;
      this.aiRank = aiRank;
      this.category = category;
      this.moveRankCategory = moveRankCategory;
      this.scoreEquivalentLoss = scoreEquivalentLoss;
      this.complexity = complexity;
      this.adjustedWeight = adjustedWeight;
    }
  }

  private static final class Accumulator {
    private final List<Sample> samples = new ArrayList<>();

    private Accumulator() {}

    private void add(Sample sample) {
      samples.add(sample);
    }

    private static Accumulator merge(Accumulator first, Accumulator second) {
      Accumulator merged = new Accumulator();
      merged.samples.addAll(first.samples);
      merged.samples.addAll(second.samples);
      return merged;
    }

    private SideReport toReport(StrengthModel model) {
      StrengthModel selectedModel = effectiveModel(model);
      int sampleCount = samples.size();
      if (sampleCount == 0) {
        return new SideReport(
            0,
            0,
            selectedModel,
            0.0,
            Double.NaN,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            new int[MoveRankDefinition.Rank.values().length],
            0.0,
            0.0,
            0.0,
            0.0,
            "-",
            Confidence.LOW,
            new ArrayList<>());
      }

      double winrateLossSum = 0.0;
      double scoreLossSum = 0.0;
      double scoreEquivalentLossSum = 0.0;
      double weightedPointLossSum = 0.0;
      double weightSum = 0.0;
      double complexitySum = 0.0;
      List<Double> scoreLosses = new ArrayList<>();
      List<Double> scoreEquivalentLosses = new ArrayList<>();
      List<Sample> openingSamples = new ArrayList<>();
      List<Sample> middlegameSamples = new ArrayList<>();
      List<Sample> endgameSamples = new ArrayList<>();
      int firstChoices = 0;
      int top3Moves = 0;
      int top5Moves = 0;
      int excellentMoves = 0;
      int goodMoves = 0;
      int moveRankGoodMoves = 0;
      int[] moveRankCounts = new int[MoveRankDefinition.Rank.values().length];
      int badMoves = 0;
      int inaccuracies = 0;
      int mistakes = 0;
      int blunders = 0;
      double aiRankSum = 0.0;
      for (Sample sample : samples) {
        double winrateLoss = positive(sample.winrateLoss);
        winrateLossSum += winrateLoss;
        double scoreEquivalentLoss = positive(sample.scoreEquivalentLoss);
        scoreEquivalentLossSum += positive(scoreEquivalentLoss);
        scoreEquivalentLosses.add(scoreEquivalentLoss);
        weightedPointLossSum += scoreEquivalentLoss * sample.adjustedWeight;
        weightSum += sample.adjustedWeight;
        if (sample.scoreLoss.isPresent()) {
          double scoreLoss = positive(sample.scoreLoss.get());
          scoreLossSum += scoreLoss;
          scoreLosses.add(scoreLoss);
        }
        if (sample.firstChoice) {
          firstChoices++;
        }
        if (sample.aiRank < 3) {
          top3Moves++;
        }
        if (sample.aiRank < 5) {
          top5Moves++;
        }
        if (sample.category == MoveCategory.EXCELLENT) {
          excellentMoves++;
        }
        if (sample.category.isGoodMove()) {
          goodMoves++;
        } else {
          badMoves++;
        }
        if (sample.moveRankCategory != null) {
          moveRankCounts[sample.moveRankCategory.ordinal()]++;
        }
        if (sample.moveRankCategory != null && sample.moveRankCategory.isGoodMove()) {
          moveRankGoodMoves++;
        }
        if (sample.category == MoveCategory.INACCURACY) {
          inaccuracies++;
        }
        if (sample.category.isMistake()) {
          mistakes++;
        }
        if (sample.category == MoveCategory.BLUNDER) {
          blunders++;
        }
        if (sample.moveNumber <= OPENING_MOVE_LIMIT) {
          openingSamples.add(sample);
        } else if (sample.moveNumber <= MIDDLEGAME_MOVE_LIMIT) {
          middlegameSamples.add(sample);
        } else {
          endgameSamples.add(sample);
        }
        aiRankSum += cappedAiRank(sample);
        complexitySum += sample.complexity;
      }

      int scoreSampleCount = scoreLosses.size();
      double averageWinrateLoss = winrateLossSum / sampleCount;
      double averageScoreLoss = scoreSampleCount == 0 ? 0.0 : scoreLossSum / scoreSampleCount;
      double medianScoreLoss = scoreSampleCount == 0 ? 0.0 : median(scoreLosses);
      double averageScoreEquivalentLoss = scoreEquivalentLossSum / sampleCount;
      double p75ScoreEquivalentLoss = percentile(scoreEquivalentLosses, 0.75);
      double p90ScoreEquivalentLoss = percentile(scoreEquivalentLosses, 0.90);
      double p95ScoreEquivalentLoss = percentile(scoreEquivalentLosses, 0.95);
      double lossStddev = populationStddev(scoreEquivalentLosses);
      double weightedPointLoss =
          weightSum == 0.0 ? averageScoreEquivalentLoss : weightedPointLossSum / weightSum;
      double firstChoiceRate = (double) firstChoices / sampleCount;
      double top3Rate = (double) top3Moves / sampleCount;
      double top5Rate = (double) top5Moves / sampleCount;
      double excellentRate = (double) excellentMoves / sampleCount;
      double goodMoveRate = (double) goodMoves / sampleCount;
      double moveRankGoodMoveRate = (double) moveRankGoodMoves / sampleCount;
      double badMoveRate = (double) badMoves / sampleCount;
      double inaccuracyRate = (double) inaccuracies / sampleCount;
      double mistakeRate = (double) mistakes / sampleCount;
      double blunderRate = (double) blunders / sampleCount;
      double matchRate = matchRate(firstChoiceRate, goodMoveRate, mistakeRate);
      double averageDifficulty = complexitySum * 100.0 / sampleCount;
      double medianPointLossForModel =
          scoreSampleCount == 0 ? averageScoreEquivalentLoss : medianScoreLoss;
      double averageAiRank = aiRankSum / sampleCount;
      double openingWeightedLoss = weightedLoss(openingSamples, weightedPointLoss);
      double middlegameWeightedLoss = weightedLoss(middlegameSamples, weightedPointLoss);
      double endgameWeightedLoss = weightedLoss(endgameSamples, weightedPointLoss);
      double openingGoodMoveRate = phaseGoodMoveRate(openingSamples, goodMoveRate);
      double middlegameGoodMoveRate = phaseGoodMoveRate(middlegameSamples, goodMoveRate);
      double endgameGoodMoveRate = phaseGoodMoveRate(endgameSamples, goodMoveRate);
      RankPrediction prediction =
          rankPrediction(
              selectedModel,
              weightedPointLoss,
              averageScoreEquivalentLoss,
              medianPointLossForModel,
              p75ScoreEquivalentLoss,
              p90ScoreEquivalentLoss,
              p95ScoreEquivalentLoss,
              max(scoreEquivalentLosses),
              lossStddev,
              openingWeightedLoss,
              middlegameWeightedLoss,
              endgameWeightedLoss,
              firstChoiceRate,
              top3Rate,
              top5Rate,
              averageAiRank,
              excellentRate,
              goodMoveRate,
              inaccuracyRate,
              mistakeRate,
              blunderRate,
              matchRate,
              openingGoodMoveRate,
              middlegameGoodMoveRate,
              endgameGoodMoveRate,
              averageDifficulty);
      double qualityScore = rankValueToQualityScore(prediction.rankValue);

      return new SideReport(
          sampleCount,
          scoreSampleCount,
          selectedModel,
          prediction.rankValue,
          prediction.sigma,
          qualityScore,
          averageWinrateLoss,
          averageScoreLoss,
          medianScoreLoss,
          weightedPointLoss,
          averageScoreEquivalentLoss,
          matchRate,
          firstChoiceRate,
          goodMoveRate,
          moveRankGoodMoveRate,
          moveRankCounts,
          badMoveRate,
          mistakeRate,
          blunderRate,
          averageDifficulty,
          strengthBand(
              sampleCount,
              qualityScore,
              weightedPointLoss,
              averageScoreEquivalentLoss,
              medianPointLossForModel,
              p90ScoreEquivalentLoss,
              firstChoiceRate,
              goodMoveRate,
              mistakeRate,
              matchRate,
              averageDifficulty),
          confidence(sampleCount, scoreSampleCount),
          new ArrayList<>(samples));
    }

    private static double median(List<Double> values) {
      Collections.sort(values);
      int middle = values.size() / 2;
      if (values.size() % 2 == 1) {
        return values.get(middle);
      }
      return (values.get(middle - 1) + values.get(middle)) / 2.0;
    }

    private static double percentile(List<Double> values, double fraction) {
      if (values.isEmpty()) {
        return 0.0;
      }
      Collections.sort(values);
      if (values.size() == 1) {
        return values.get(0);
      }
      double position = fraction * (values.size() - 1);
      int lower = (int) Math.floor(position);
      int upper = (int) Math.ceil(position);
      if (lower == upper) {
        return values.get(lower);
      }
      double weight = position - lower;
      return values.get(lower) * (1.0 - weight) + values.get(upper) * weight;
    }

    private static double max(List<Double> values) {
      if (values.isEmpty()) {
        return 0.0;
      }
      double maximum = 0.0;
      for (double value : values) {
        maximum = Math.max(maximum, value);
      }
      return maximum;
    }

    private static double populationStddev(List<Double> values) {
      if (values == null || values.size() <= 1) {
        return 0.0;
      }
      double sum = 0.0;
      for (double value : values) {
        sum += value;
      }
      double mean = sum / values.size();
      double squaredDifferenceSum = 0.0;
      for (double value : values) {
        double difference = value - mean;
        squaredDifferenceSum += difference * difference;
      }
      return Math.sqrt(squaredDifferenceSum / values.size());
    }

    private static double weightedLoss(List<Sample> samples, double fallback) {
      if (samples == null || samples.isEmpty()) {
        return fallback;
      }
      double weightedLossSum = 0.0;
      double weightSum = 0.0;
      for (Sample sample : samples) {
        weightedLossSum += sample.scoreEquivalentLoss * sample.adjustedWeight;
        weightSum += sample.adjustedWeight;
      }
      return weightSum == 0.0 ? fallback : weightedLossSum / weightSum;
    }

    private static double phaseGoodMoveRate(List<Sample> samples, double fallback) {
      if (samples == null || samples.isEmpty()) {
        return fallback;
      }
      int goodMoves = 0;
      for (Sample sample : samples) {
        if (sample.category.isGoodMove()) {
          goodMoves++;
        }
      }
      return (double) goodMoves / samples.size();
    }

    private static double cappedAiRank(Sample sample) {
      if (sample.aiRank >= ADDITIONAL_MOVE_ORDER) {
        return AI_RANK_CAP;
      }
      return Math.min(sample.aiRank + 1.0, AI_RANK_CAP);
    }

    private static double matchRate(
        double firstChoiceRate, double goodMoveRate, double mistakeRate) {
      return clamp(
          0.45 * firstChoiceRate + 0.45 * goodMoveRate + 0.10 * (1.0 - mistakeRate), 0.0, 1.0);
    }

    private static RankPrediction rankPrediction(
        StrengthModel model,
        double weightedPointLoss,
        double averagePointLoss,
        double medianPointLoss,
        double p75PointLoss,
        double p90PointLoss,
        double p95PointLoss,
        double maxPointLoss,
        double lossStddev,
        double openingWeightedLoss,
        double middlegameWeightedLoss,
        double endgameWeightedLoss,
        double firstChoiceRate,
        double top3Rate,
        double top5Rate,
        double averageAiRank,
        double excellentRate,
        double goodMoveRate,
        double inaccuracyRate,
        double mistakeRate,
        double blunderRate,
        double matchRate,
        double openingGoodMoveRate,
        double middlegameGoodMoveRate,
        double endgameGoodMoveRate,
        double averageDifficulty) {
      OptionalDouble tunedPrediction =
          xgboost20TunPrediction(
              model,
              weightedPointLoss,
              averagePointLoss,
              medianPointLoss,
              p75PointLoss,
              p90PointLoss,
              p95PointLoss,
              maxPointLoss,
              lossStddev,
              openingWeightedLoss,
              middlegameWeightedLoss,
              endgameWeightedLoss,
              firstChoiceRate,
              top3Rate,
              top5Rate,
              averageAiRank,
              excellentRate,
              goodMoveRate,
              inaccuracyRate,
              mistakeRate,
              blunderRate,
              matchRate,
              openingGoodMoveRate,
              middlegameGoodMoveRate,
              endgameGoodMoveRate,
              averageDifficulty);
      return new RankPrediction(tunedPrediction.orElse(Double.NaN), model.predictionSigma());
    }

    private static OptionalDouble xgboost20TunPrediction(
        StrengthModel model,
        double weightedPointLoss,
        double averagePointLoss,
        double medianPointLoss,
        double p75PointLoss,
        double p90PointLoss,
        double p95PointLoss,
        double maxPointLoss,
        double lossStddev,
        double openingWeightedLoss,
        double middlegameWeightedLoss,
        double endgameWeightedLoss,
        double firstChoiceRate,
        double top3Rate,
        double top5Rate,
        double averageAiRank,
        double excellentRate,
        double goodMoveRate,
        double inaccuracyRate,
        double mistakeRate,
        double blunderRate,
        double matchRate,
        double openingGoodMoveRate,
        double middlegameGoodMoveRate,
        double endgameGoodMoveRate,
        double averageDifficulty) {
      return XGBoostStrengthModel.predictXGBoostRankValue(
          xgboostFeatures(
              weightedPointLoss,
              averagePointLoss,
              medianPointLoss,
              p75PointLoss,
              p90PointLoss,
              p95PointLoss,
              maxPointLoss,
              lossStddev,
              openingWeightedLoss,
              middlegameWeightedLoss,
              endgameWeightedLoss,
              firstChoiceRate,
              top3Rate,
              top5Rate,
              averageAiRank,
              excellentRate,
              goodMoveRate,
              inaccuracyRate,
              mistakeRate,
              blunderRate,
              matchRate,
              openingGoodMoveRate,
              middlegameGoodMoveRate,
              endgameGoodMoveRate,
              averageDifficulty),
          model.boosterResourcePath(),
          model.boosterPath(),
          model.calibratorResourcePath(),
          model.calibratorPath(),
          model.featureIndices());
    }

    private static XGBoostStrengthModel.Features xgboostFeatures(
        double weightedPointLoss,
        double averagePointLoss,
        double medianPointLoss,
        double p75PointLoss,
        double p90PointLoss,
        double p95PointLoss,
        double maxPointLoss,
        double lossStddev,
        double openingWeightedLoss,
        double middlegameWeightedLoss,
        double endgameWeightedLoss,
        double firstChoiceRate,
        double top3Rate,
        double top5Rate,
        double averageAiRank,
        double excellentRate,
        double goodMoveRate,
        double inaccuracyRate,
        double mistakeRate,
        double blunderRate,
        double matchRate,
        double openingGoodMoveRate,
        double middlegameGoodMoveRate,
        double endgameGoodMoveRate,
        double averageDifficulty) {
      double difficulty = clamp((averageDifficulty - 25.0) / 35.0, 0.0, 1.0);
      double firstChoice = clamp(firstChoiceRate, 0.0, 1.0);
      double top5 = clamp(top5Rate, 0.0, 1.0);
      double goodMove = clamp(goodMoveRate, 0.0, 1.0);
      double match = clamp(matchRate, 0.0, 1.0);
      return XGBoostStrengthModel.Features.full29(
          firstChoice,
          clamp(top3Rate, 0.0, 1.0),
          top5,
          1.0 / (1.0 + clamp(averageAiRank, 0.0, 10.0)),
          clamp(excellentRate, 0.0, 1.0),
          goodMove,
          1.0 - clamp(inaccuracyRate, 0.0, 1.0),
          match,
          1.0 - clamp(mistakeRate, 0.0, 1.0),
          1.0 - clamp(blunderRate, 0.0, 1.0),
          lossFit(weightedPointLoss, 50.0),
          lossFit(averagePointLoss, 50.0),
          lossFit(medianPointLoss, 50.0),
          lossFit(p75PointLoss, 50.0),
          lossFit(p90PointLoss, 80.0),
          lossFit(p95PointLoss, 100.0),
          lossFit(maxPointLoss, 120.0),
          lossFit(lossStddev, 50.0),
          difficulty,
          lossFit(openingWeightedLoss, 50.0),
          lossFit(middlegameWeightedLoss, 50.0),
          lossFit(endgameWeightedLoss, 50.0),
          clamp(openingGoodMoveRate, 0.0, 1.0),
          clamp(middlegameGoodMoveRate, 0.0, 1.0),
          clamp(endgameGoodMoveRate, 0.0, 1.0),
          firstChoice * difficulty,
          goodMove * difficulty,
          match * difficulty,
          top5 * difficulty);
    }

    private static double lossFit(double loss, double cap) {
      return 1.0 / (1.0 + clamp(positive(loss), 0.0, cap));
    }

    private Confidence confidence(int sampleCount, int scoreSampleCount) {
      if (sampleCount >= 40 && scoreSampleCount >= sampleCount * 0.7) {
        return Confidence.HIGH;
      }
      if (sampleCount >= 16) {
        return Confidence.MEDIUM;
      }
      return Confidence.LOW;
    }
  }

  private static String strengthBand(
      int sampleCount,
      double qualityScore,
      double weightedPointLoss,
      double averagePointLoss,
      double medianPointLoss,
      double p90PointLoss,
      double firstChoiceRate,
      double goodMoveRate,
      double mistakeRate,
      double matchRate,
      double averageDifficulty) {
    int baseLevel =
        Math.max(
            baseLevel(qualityScore),
            eliteEvidenceLevel(
                weightedPointLoss, firstChoiceRate, goodMoveRate, mistakeRate, matchRate));
    int level =
        Math.min(
            baseLevel,
            metricCapLevel(
                weightedPointLoss,
                medianPointLoss,
                firstChoiceRate,
                goodMoveRate,
                mistakeRate,
                matchRate));
    level =
        Math.min(
            level,
            tailLossCap(
                weightedPointLoss, averagePointLoss, medianPointLoss, p90PointLoss, matchRate));
    level = evidenceAdjustedLevel(level, firstChoiceRate, goodMoveRate, averageDifficulty);
    level = lowConfidenceAdjustedLevel(level, sampleCount, weightedPointLoss);
    return STRENGTH_BANDS[level];
  }

  private static int baseLevel(double qualityScore) {
    return levelFromRankValue(-18.0 + clamp(qualityScore, 0.0, 100.0) * 30.0 / 100.0);
  }

  private static double rankValueToQualityScore(double rankValue) {
    return clamp((rankValue + 18.0) * 100.0 / 30.0, 0.0, 100.0);
  }

  private static int levelFromRankValue(double rankValue) {
    if (rankValue >= 11.5) {
      return 13;
    }
    if (rankValue >= 10.5) {
      return 12;
    }
    if (rankValue >= 9.5) {
      return 11;
    }
    if (rankValue >= 8.5) {
      return 10;
    }
    if (rankValue >= 7.5) {
      return 9;
    }
    if (rankValue >= 6.5) {
      return 8;
    }
    if (rankValue >= 4.5) {
      return 7;
    }
    if (rankValue >= 2.5) {
      return 6;
    }
    if (rankValue >= 0.0) {
      return 5;
    }
    if (rankValue >= -2.75) {
      return 4;
    }
    if (rankValue >= -6.0) {
      return 3;
    }
    if (rankValue >= -10.5) {
      return 2;
    }
    if (rankValue >= -15.5) {
      return 1;
    }
    return 0;
  }

  private static int eliteEvidenceLevel(
      double weightedPointLoss,
      double firstChoiceRate,
      double goodMoveRate,
      double mistakeRate,
      double matchRate) {
    if (firstChoiceRate >= 0.95
        && goodMoveRate >= 0.96
        && mistakeRate <= 0.01
        && weightedPointLoss <= 1.00) {
      return 13;
    }
    if (firstChoiceRate >= TOP_PRO_FIRST_CHOICE_RATE
        && goodMoveRate >= TOP_PRO_GOOD_MOVE_RATE
        && mistakeRate <= TOP_PRO_MISTAKE_RATE
        && weightedPointLoss <= TOP_PRO_WEIGHTED_LOSS) {
      return 12;
    }
    if (firstChoiceRate >= PRO_FIRST_CHOICE_RATE
        && goodMoveRate >= PRO_GOOD_MOVE_RATE
        && mistakeRate <= PRO_MISTAKE_RATE
        && weightedPointLoss <= PRO_WEIGHTED_LOSS) {
      return 11;
    }
    if (firstChoiceRate >= 0.44
        && goodMoveRate >= 0.78
        && matchRate >= 0.62
        && mistakeRate <= 0.03
        && weightedPointLoss <= 2.00) {
      return 10;
    }
    return 0;
  }

  private static int tailLossCap(
      double weightedPointLoss,
      double averagePointLoss,
      double medianPointLoss,
      double p90PointLoss,
      double matchRate) {
    int cap = 13;
    if (p90PointLoss > 8.0 && averagePointLoss > 2.8 && matchRate < 0.55) {
      cap = Math.min(cap, MID_DAN_LEVEL);
    }
    if (p90PointLoss > 5.5 && medianPointLoss > 1.0 && matchRate < 0.50) {
      cap = Math.min(cap, LOW_DAN_LEVEL);
    }
    if (weightedPointLoss > 4.8 && averagePointLoss > 3.0 && matchRate < 0.45) {
      cap = Math.min(cap, STRONG_KYU_LEVEL);
    }
    return cap;
  }

  private static int metricCapLevel(
      double weightedPointLoss,
      double medianPointLoss,
      double firstChoiceRate,
      double goodMoveRate,
      double mistakeRate,
      double matchRate) {
    int cap = 13;
    cap = Math.min(cap, capByFirstChoice(firstChoiceRate));
    cap = Math.min(cap, capByGoodMoveRate(goodMoveRate));
    cap = Math.min(cap, capByMatchRate(matchRate));
    cap = Math.min(cap, capByMistakeRate(mistakeRate));
    cap = Math.min(cap, capByMedianLoss(medianPointLoss));
    cap = Math.min(cap, evidenceCapLevel(weightedPointLoss, firstChoiceRate, goodMoveRate));
    return cap;
  }

  private static int evidenceCapLevel(
      double weightedPointLoss, double firstChoiceRate, double goodMoveRate) {
    int cap = 13;
    if (goodMoveRate < 0.50 && firstChoiceRate < 0.22) {
      cap = Math.min(cap, STRONG_KYU_LEVEL);
    }
    if (goodMoveRate < 0.62 && firstChoiceRate < 0.30) {
      cap = Math.min(cap, STRONG_KYU_LEVEL);
    }
    if (weightedPointLoss > 7.50 && firstChoiceRate < 0.26) {
      cap = Math.min(cap, STRONG_KYU_LEVEL);
    }
    if (weightedPointLoss >= 2.90 && firstChoiceRate < 0.34 && goodMoveRate < 0.78) {
      cap = Math.min(cap, LOW_DAN_LEVEL);
    }
    return cap;
  }

  private static int evidenceAdjustedLevel(
      int level, double firstChoiceRate, double goodMoveRate, double averageDifficulty) {
    int adjusted = level;
    if (adjusted >= HIGH_DAN_LEVEL
        && averageDifficulty < LOW_DIFFICULTY_EVIDENCE
        && firstChoiceRate < LOW_DIFFICULTY_TOP_FIRST_CHOICE_RATE
        && goodMoveRate < LOW_DIFFICULTY_TOP_GOOD_MOVE_RATE) {
      adjusted = Math.min(adjusted, MID_DAN_LEVEL);
    }
    return adjusted;
  }

  private static int lowConfidenceAdjustedLevel(
      int level, int sampleCount, double weightedPointLoss) {
    if (sampleCount < MIN_REPORT_SAMPLES && level == 0 && weightedPointLoss <= MISTAKE_SCORE_LOSS) {
      return 1;
    }
    return level;
  }

  private static int capByFirstChoice(double firstChoiceRate) {
    return levelAtLeast(firstChoiceRate, FIRST_CHOICE_CAPS, LOW_DAN_LEVEL);
  }

  private static int capByGoodMoveRate(double goodMoveRate) {
    return levelAtLeast(goodMoveRate, GOOD_MOVE_CAPS, 1);
  }

  private static int capByMatchRate(double matchRate) {
    return levelAtLeast(
        matchRate,
        new LevelThreshold[] {
          new LevelThreshold(0.80, 13),
          new LevelThreshold(0.70, 12),
          new LevelThreshold(0.62, 11),
          new LevelThreshold(0.58, 10),
          new LevelThreshold(0.54, 9),
          new LevelThreshold(0.49, 8),
          new LevelThreshold(0.43, 7),
          new LevelThreshold(0.37, 6),
          new LevelThreshold(0.30, 5),
          new LevelThreshold(0.22, 4),
          new LevelThreshold(0.14, 3)
        },
        2);
  }

  private static int capByMistakeRate(double mistakeRate) {
    return levelAtMost(mistakeRate, MISTAKE_CAPS, 1);
  }

  private static int capByMedianLoss(double medianPointLoss) {
    return levelAtMost(medianPointLoss, MEDIAN_LOSS_CAPS, 1);
  }

  private static int levelAtLeast(double value, LevelThreshold[] thresholds, int fallbackLevel) {
    for (LevelThreshold threshold : thresholds) {
      if (value >= threshold.value) {
        return threshold.level;
      }
    }
    return fallbackLevel;
  }

  private static int levelAtMost(double value, LevelThreshold[] thresholds, int fallbackLevel) {
    for (LevelThreshold threshold : thresholds) {
      if (value <= threshold.value) {
        return threshold.level;
      }
    }
    return fallbackLevel;
  }

  private static final class LevelThreshold {
    private final double value;
    private final int level;

    private LevelThreshold(double value, int level) {
      this.value = value;
      this.level = level;
    }
  }

  private static final class RankPrediction {
    private final double rankValue;
    private final double sigma;

    private RankPrediction(double rankValue, double sigma) {
      this.rankValue = rankValue;
      this.sigma = sigma;
    }
  }

  private static final class CandidateMatch {
    private final int rank;
    private final MoveData move;

    private CandidateMatch(int rank, MoveData move) {
      this.rank = rank;
      this.move = move;
    }

    private static CandidateMatch missing() {
      return new CandidateMatch(-1, null);
    }
  }

  private static final class LossEstimate {
    private final double winrateLoss;
    private final Optional<Double> scoreLoss;

    private LossEstimate(double winrateLoss, Optional<Double> scoreLoss) {
      this.winrateLoss = winrateLoss;
      this.scoreLoss = scoreLoss;
    }

    private double scoreEquivalentLoss() {
      return scoreLoss
          .map(PlayerStrengthEstimator::positive)
          .orElse(positive(winrateLoss) / WINRATE_TO_SCORE_LOSS);
    }
  }
}
