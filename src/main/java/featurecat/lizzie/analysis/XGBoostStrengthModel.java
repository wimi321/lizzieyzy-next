package featurecat.lizzie.analysis;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Lightweight scorer for the selected XGBoost strength model. */
final class XGBoostStrengthModel {
  private static final String RESOURCE_PATH =
      "/models/strength/xgboost_selected_strength_booster.json";
  private static final double MEDIAN_CV_BIAS_CORRECTION = -0.001;

  private final double baseScore;
  private final List<Tree> trees;

  private XGBoostStrengthModel(double baseScore, List<Tree> trees) {
    this.baseScore = baseScore;
    this.trees = trees;
  }

  static boolean isAvailable() {
    return Holder.MODEL != null;
  }

  static OptionalDouble predictRankValue(Features features) {
    XGBoostStrengthModel model = Holder.MODEL;
    if (model == null || features == null) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.of(clamp(model.predict(features.values) - MEDIAN_CV_BIAS_CORRECTION));
  }

  private double predict(double[] features) {
    double prediction = baseScore;
    for (Tree tree : trees) {
      prediction += tree.predict(features);
    }
    return prediction;
  }

  private static XGBoostStrengthModel load() {
    try (InputStream stream = XGBoostStrengthModel.class.getResourceAsStream(RESOURCE_PATH)) {
      if (stream == null) {
        return null;
      }
      JSONObject root =
          new JSONObject(new JSONTokener(new InputStreamReader(stream, StandardCharsets.UTF_8)));
      JSONObject learner = root.getJSONObject("learner");
      double baseScore =
          parseBracketedDouble(
              learner.getJSONObject("learner_model_param").getString("base_score"));
      JSONArray treesJson =
          learner.getJSONObject("gradient_booster").getJSONObject("model").getJSONArray("trees");
      List<Tree> trees = new ArrayList<>(treesJson.length());
      for (int index = 0; index < treesJson.length(); index++) {
        trees.add(Tree.fromJson(treesJson.getJSONObject(index)));
      }
      return new XGBoostStrengthModel(baseScore, trees);
    } catch (RuntimeException | java.io.IOException e) {
      System.err.println("Failed to load XGBoost strength model: " + e.getMessage());
      return null;
    }
  }

  private static double parseBracketedDouble(String value) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.startsWith("[") && normalized.endsWith("]")) {
      normalized = normalized.substring(1, normalized.length() - 1).trim();
    }
    return Double.parseDouble(normalized);
  }

  private static double clamp(double rankValue) {
    return Math.max(-18.0, Math.min(12.0, rankValue));
  }

  static final class Features {
    private final double[] values;

    private Features(double[] values) {
      this.values = values;
    }

    static Features selectedTop16(
        double weightedLossFit,
        double goodMoveRate,
        double averageLossFit,
        double medianLossFit,
        double top5Rate,
        double openingLossFit,
        double middlegameLossFit,
        double averageAiRankFit,
        double p75LossFit,
        double maxLossFit,
        double matchRate,
        double p90LossFit,
        double firstChoiceRate,
        double nonMistakeRate,
        double top5Difficulty,
        double goodMoveDifficulty) {
      return new Features(
          new double[] {
            weightedLossFit,
            goodMoveRate,
            averageLossFit,
            medianLossFit,
            top5Rate,
            openingLossFit,
            middlegameLossFit,
            averageAiRankFit,
            p75LossFit,
            maxLossFit,
            matchRate,
            p90LossFit,
            firstChoiceRate,
            nonMistakeRate,
            top5Difficulty,
            goodMoveDifficulty
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
    private static final XGBoostStrengthModel MODEL = load();

    private Holder() {}
  }
}
