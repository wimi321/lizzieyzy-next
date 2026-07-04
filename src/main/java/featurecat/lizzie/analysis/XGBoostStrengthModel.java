package featurecat.lizzie.analysis;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Lightweight scorer for the bundled xgboost20tun strength model. */
final class XGBoostStrengthModel {
  private static final String XGBOOST20TUN_RESOURCE_PATH =
      "/models/strength/xgboost20tun_booster.json";
  private static final String XGBOOST20TUN_MODEL_PROPERTY = "lizzie.strength.xgboost20tun.booster";
  private static final String LEGACY_ZHANGQI_DISTILLED_MODEL_PROPERTY =
      "lizzie.strength.zhangqi.distilled.booster";
  private static final String DEFAULT_XGBOOST20TUN_MODEL_PATH =
      "target/zhangqi-xgb-distill-v500-m210/nested-tuning/xgboost_distilled_candidate_booster.json";
  private static final double XGBOOST20TUN_SIGMA = 0.92;
  private static final double MIN_RANK_VALUE = -1.0;
  private static final double MAX_RANK_VALUE = 12.0;

  private final double baseScore;
  private final int featureCount;
  private final List<Tree> trees;

  private XGBoostStrengthModel(double baseScore, int featureCount, List<Tree> trees) {
    this.baseScore = baseScore;
    this.featureCount = featureCount;
    this.trees = trees;
  }

  static boolean isXGBoost20TunAvailable() {
    return Holder.XGBOOST20TUN_MODEL != null;
  }

  static boolean isXGBoost20TunAvailable(Path boosterPath) {
    return isXGBoostRankValueAvailable(boosterPath, Features.defaultXGBoost20TunFeatureIndices());
  }

  static boolean isXGBoostRankValueAvailable(Path boosterPath, int[] featureIndices) {
    return isXGBoostRankValueAvailable(null, boosterPath, featureIndices);
  }

  static boolean isXGBoostRankValueAvailable(
      String boosterResourcePath, Path boosterPath, int[] featureIndices) {
    XGBoostStrengthModel model = modelFor(boosterResourcePath, boosterPath);
    return model != null && featureIndices != null && model.featureCount == featureIndices.length;
  }

  static int featureCount(Path boosterPath) {
    XGBoostStrengthModel model = modelFor(null, boosterPath);
    return model == null ? -1 : model.featureCount;
  }

  static OptionalDouble predictXGBoost20TunRankValue(Features full29Features) {
    return predictXGBoost20TunRankValue(full29Features, null, null);
  }

  static OptionalDouble predictXGBoost20TunRankValue(
      Features full29Features, Path boosterPath, Path calibratorPath) {
    return predictXGBoostRankValue(
        full29Features, boosterPath, calibratorPath, Features.defaultXGBoost20TunFeatureIndices());
  }

  static OptionalDouble predictXGBoostRankValue(
      Features full29Features, Path boosterPath, Path calibratorPath, int[] featureIndices) {
    return predictXGBoostRankValue(
        full29Features, null, boosterPath, null, calibratorPath, featureIndices);
  }

  static OptionalDouble predictXGBoostRankValue(
      Features full29Features,
      String boosterResourcePath,
      Path boosterPath,
      String calibratorResourcePath,
      Path calibratorPath,
      int[] featureIndices) {
    XGBoostStrengthModel model = modelFor(boosterResourcePath, boosterPath);
    if (model == null
        || full29Features == null
        || featureIndices == null
        || !full29Features.hasFeatureCount(Features.fullFeatureCount())) {
      return OptionalDouble.empty();
    }
    Features selectedFeatures = full29Features.select(featureIndices);
    if (!model.accepts(selectedFeatures.values)) {
      return OptionalDouble.empty();
    }
    double rawPrediction = clamp(model.predict(selectedFeatures.values));
    return OptionalDouble.of(
        XGBoost20TunResidualCalibrator.calibrate(
            rawPrediction, full29Features, calibratorResourcePath, calibratorPath));
  }

  static double xgboost20TunSigma() {
    return XGBOOST20TUN_SIGMA;
  }

  private double predict(double[] features) {
    double prediction = baseScore;
    for (Tree tree : trees) {
      prediction += tree.predict(features);
    }
    return prediction;
  }

  private boolean accepts(double[] features) {
    return features != null && features.length >= featureCount;
  }

  private static XGBoostStrengthModel loadXGBoost20Tun() {
    XGBoostStrengthModel configured = loadExternal(configuredXGBoost20TunPath());
    if (configured != null) {
      return configured;
    }
    XGBoostStrengthModel bundled = loadResource(XGBOOST20TUN_RESOURCE_PATH, "xgboost20tun model");
    if (bundled != null) {
      return bundled;
    }
    return loadExternal(Paths.get(DEFAULT_XGBOOST20TUN_MODEL_PATH));
  }

  private static XGBoostStrengthModel loadResource(String resourcePath, String description) {
    try (InputStream stream = XGBoostStrengthModel.class.getResourceAsStream(resourcePath)) {
      if (stream == null) {
        return null;
      }
      return parse(stream);
    } catch (RuntimeException | java.io.IOException e) {
      System.err.println("Failed to load " + description + ": " + e.getMessage());
      return null;
    }
  }

  private static XGBoostStrengthModel loadExternal(Path path) {
    if (path == null || !Files.isRegularFile(path)) {
      return null;
    }
    try (InputStream stream = Files.newInputStream(path)) {
      return parse(stream);
    } catch (RuntimeException | java.io.IOException e) {
      System.err.println(
          "Failed to load external XGBoost strength model " + path + ": " + e.getMessage());
      return null;
    }
  }

  private static XGBoostStrengthModel modelFor(Path boosterPath) {
    return modelFor(null, boosterPath);
  }

  private static XGBoostStrengthModel modelFor(String resourcePath, Path boosterPath) {
    if (resourcePath != null && !resourcePath.trim().isEmpty()) {
      String normalized = resourcePath.trim();
      return Holder.RESOURCE_MODELS.computeIfAbsent(
          normalized, path -> loadResource(path, "XGBoost strength model " + path));
    }
    if (boosterPath == null) {
      return Holder.XGBOOST20TUN_MODEL;
    }
    Path normalized = boosterPath.toAbsolutePath().normalize();
    return Holder.EXTERNAL_MODELS.computeIfAbsent(normalized, XGBoostStrengthModel::loadExternal);
  }

  private static XGBoostStrengthModel parse(InputStream stream) throws java.io.IOException {
    JSONObject root =
        new JSONObject(new JSONTokener(new InputStreamReader(stream, StandardCharsets.UTF_8)));
    JSONObject learner = root.getJSONObject("learner");
    JSONObject learnerModelParam = learner.getJSONObject("learner_model_param");
    double baseScore = parseBracketedDouble(learnerModelParam.getString("base_score"));
    int featureCount = Integer.parseInt(learnerModelParam.getString("num_feature"));
    JSONArray treesJson =
        learner.getJSONObject("gradient_booster").getJSONObject("model").getJSONArray("trees");
    List<Tree> trees = new ArrayList<>(treesJson.length());
    for (int index = 0; index < treesJson.length(); index++) {
      trees.add(Tree.fromJson(treesJson.getJSONObject(index)));
    }
    return new XGBoostStrengthModel(baseScore, featureCount, trees);
  }

  private static Path configuredXGBoost20TunPath() {
    String configured = System.getProperty(XGBOOST20TUN_MODEL_PROPERTY, "").trim();
    if (configured.isEmpty()) {
      configured = System.getProperty(LEGACY_ZHANGQI_DISTILLED_MODEL_PROPERTY, "").trim();
    }
    if (!configured.isEmpty()) {
      return Paths.get(configured);
    }
    return null;
  }

  private static double parseBracketedDouble(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.startsWith("[") && normalized.endsWith("]")) {
      normalized = normalized.substring(1, normalized.length() - 1).trim();
    }
    return Double.parseDouble(normalized);
  }

  private static double clamp(double rankValue) {
    return Math.max(MIN_RANK_VALUE, Math.min(MAX_RANK_VALUE, rankValue));
  }

  static final class Features {
    private final double[] values;

    private static final String[] FULL29_FEATURE_NAMES = {
      "first_choice_rate",
      "top3_rate",
      "top5_rate",
      "average_ai_rank_fit",
      "excellent_rate",
      "good_move_rate",
      "non_inaccuracy_rate",
      "match_rate",
      "non_mistake_rate",
      "non_blunder_rate",
      "weighted_loss_fit",
      "average_loss_fit",
      "median_loss_fit",
      "p75_loss_fit",
      "p90_loss_fit",
      "p95_loss_fit",
      "max_loss_fit",
      "loss_stability_fit",
      "difficulty_fit",
      "opening_loss_fit",
      "middlegame_loss_fit",
      "endgame_loss_fit",
      "opening_good_move_rate",
      "middlegame_good_move_rate",
      "endgame_good_move_rate",
      "first_choice_x_difficulty",
      "good_move_x_difficulty",
      "match_x_difficulty",
      "top5_x_difficulty",
    };

    private static final int[] XGBOOST20TUN_20_INDICES = {
      13, 5, 2, 19, 10, 20, 26, 3, 14, 11, 28, 8, 18, 7, 12, 23, 27, 22, 25, 0
    };

    private Features(double[] values) {
      this.values = values;
    }

    private Features select(int[] full29Indices) {
      double[] selected = new double[full29Indices.length];
      for (int index = 0; index < full29Indices.length; index++) {
        selected[index] = values[full29Indices[index]];
      }
      return new Features(selected);
    }

    static int[] defaultXGBoost20TunFeatureIndices() {
      return XGBOOST20TUN_20_INDICES.clone();
    }

    static String[] defaultXGBoost20TunFeatureOrder() {
      String[] names = new String[XGBOOST20TUN_20_INDICES.length];
      for (int index = 0; index < XGBOOST20TUN_20_INDICES.length; index++) {
        names[index] = FULL29_FEATURE_NAMES[XGBOOST20TUN_20_INDICES[index]];
      }
      return names;
    }

    static int fullFeatureCount() {
      return FULL29_FEATURE_NAMES.length;
    }

    static int fullFeatureIndex(String name) {
      for (int index = 0; index < FULL29_FEATURE_NAMES.length; index++) {
        if (FULL29_FEATURE_NAMES[index].equals(name)) {
          return index;
        }
      }
      return -1;
    }

    private boolean hasFeatureCount(int featureCount) {
      return values != null && values.length >= featureCount;
    }

    double valueAtFull29Index(int index) {
      return values[index];
    }

    static Features full29(
        double firstChoiceRate,
        double top3Rate,
        double top5Rate,
        double averageAiRankFit,
        double excellentRate,
        double goodMoveRate,
        double nonInaccuracyRate,
        double matchRate,
        double nonMistakeRate,
        double nonBlunderRate,
        double weightedLossFit,
        double averageLossFit,
        double medianLossFit,
        double p75LossFit,
        double p90LossFit,
        double p95LossFit,
        double maxLossFit,
        double lossStabilityFit,
        double difficultyFit,
        double openingLossFit,
        double middlegameLossFit,
        double endgameLossFit,
        double openingGoodMoveRate,
        double middlegameGoodMoveRate,
        double endgameGoodMoveRate,
        double firstChoiceDifficulty,
        double goodMoveDifficulty,
        double matchDifficulty,
        double top5Difficulty) {
      return new Features(
          new double[] {
            firstChoiceRate,
            top3Rate,
            top5Rate,
            averageAiRankFit,
            excellentRate,
            goodMoveRate,
            nonInaccuracyRate,
            matchRate,
            nonMistakeRate,
            nonBlunderRate,
            weightedLossFit,
            averageLossFit,
            medianLossFit,
            p75LossFit,
            p90LossFit,
            p95LossFit,
            maxLossFit,
            lossStabilityFit,
            difficultyFit,
            openingLossFit,
            middlegameLossFit,
            endgameLossFit,
            openingGoodMoveRate,
            middlegameGoodMoveRate,
            endgameGoodMoveRate,
            firstChoiceDifficulty,
            goodMoveDifficulty,
            matchDifficulty,
            top5Difficulty
          });
    }
  }

  private static final class Tree {
    private final int[] leftChildren;
    private final int[] rightChildren;
    private final int[] splitIndices;
    private final double[] splitConditions;
    private final boolean[] defaultLeft;

    private Tree(
        int[] leftChildren,
        int[] rightChildren,
        int[] splitIndices,
        double[] splitConditions,
        boolean[] defaultLeft) {
      this.leftChildren = leftChildren;
      this.rightChildren = rightChildren;
      this.splitIndices = splitIndices;
      this.splitConditions = splitConditions;
      this.defaultLeft = defaultLeft;
    }

    private static Tree fromJson(JSONObject tree) {
      return new Tree(
          intArray(tree.getJSONArray("left_children")),
          intArray(tree.getJSONArray("right_children")),
          intArray(tree.getJSONArray("split_indices")),
          doubleArray(tree.getJSONArray("split_conditions")),
          booleanArray(tree.getJSONArray("default_left")));
    }

    private double predict(double[] features) {
      int node = 0;
      while (leftChildren[node] != -1) {
        double value = features[splitIndices[node]];
        if (Double.isNaN(value)) {
          node = defaultLeft[node] ? leftChildren[node] : rightChildren[node];
        } else if (value < splitConditions[node]) {
          node = leftChildren[node];
        } else {
          node = rightChildren[node];
        }
      }
      // In XGBoost JSON dumps, leaf values are stored in split_conditions for leaf nodes.
      return splitConditions[node];
    }
  }

  private static int[] intArray(JSONArray array) {
    int[] values = new int[array.length()];
    for (int index = 0; index < array.length(); index++) {
      values[index] = array.getInt(index);
    }
    return values;
  }

  private static double[] doubleArray(JSONArray array) {
    double[] values = new double[array.length()];
    for (int index = 0; index < array.length(); index++) {
      values[index] = array.getDouble(index);
    }
    return values;
  }

  private static boolean[] booleanArray(JSONArray array) {
    boolean[] values = new boolean[array.length()];
    for (int index = 0; index < array.length(); index++) {
      values[index] = array.getInt(index) != 0;
    }
    return values;
  }

  private static final class Holder {
    private static final XGBoostStrengthModel XGBOOST20TUN_MODEL = loadXGBoost20Tun();
    private static final ConcurrentMap<String, XGBoostStrengthModel> RESOURCE_MODELS =
        new ConcurrentHashMap<>();
    private static final ConcurrentMap<Path, XGBoostStrengthModel> EXTERNAL_MODELS =
        new ConcurrentHashMap<>();

    private Holder() {}
  }
}
