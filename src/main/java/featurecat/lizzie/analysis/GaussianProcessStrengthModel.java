package featurecat.lizzie.analysis;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Runtime scorer for the four-feature exact GP strength model. */
final class GaussianProcessStrengthModel {
  private static final String RESOURCE_PATH =
      "/models/strength/exact_gp_core4_strength_model.json.gz";

  private final String kernel;
  private final double lengthscale;
  private final double signal;
  private final double noise;
  private final double rqAlpha;
  private final double trainingMean;
  private final double biasCorrection;
  private final double varianceScale;
  private final double[] featureMean;
  private final double[] featureScale;
  private final double[][] xTrain;
  private final double[] alpha;
  private final double[][] choleskyLower;

  private GaussianProcessStrengthModel(
      String kernel,
      double lengthscale,
      double signal,
      double noise,
      double rqAlpha,
      double trainingMean,
      double biasCorrection,
      double varianceScale,
      double[] featureMean,
      double[] featureScale,
      double[][] xTrain,
      double[] alpha,
      double[][] choleskyLower) {
    this.kernel = kernel;
    this.lengthscale = lengthscale;
    this.signal = signal;
    this.noise = noise;
    this.rqAlpha = rqAlpha;
    this.trainingMean = trainingMean;
    this.biasCorrection = biasCorrection;
    this.varianceScale = varianceScale;
    this.featureMean = featureMean;
    this.featureScale = featureScale;
    this.xTrain = xTrain;
    this.alpha = alpha;
    this.choleskyLower = choleskyLower;
  }

  static boolean isAvailable() {
    return Holder.MODEL != null;
  }

  static Optional<Prediction> predictRankValue(Features features) {
    GaussianProcessStrengthModel model = Holder.MODEL;
    if (model == null || features == null) {
      return Optional.empty();
    }
    return Optional.of(model.predict(features.values));
  }

  private Prediction predict(double[] features) {
    double[] standardized = standardize(features);
    double[] crossKernel = new double[xTrain.length];
    double mean = trainingMean;
    for (int index = 0; index < xTrain.length; index++) {
      double value = kernel(standardized, xTrain[index]);
      crossKernel[index] = value;
      mean += value * alpha[index];
    }
    mean -= biasCorrection;

    double variance = signal * signal + noise * noise;
    if (choleskyLower.length == xTrain.length) {
      double[] solved = solveLower(choleskyLower, crossKernel);
      double explainedVariance = 0.0;
      for (double value : solved) {
        explainedVariance += value * value;
      }
      variance = Math.max(signal * signal - explainedVariance + noise * noise, 1e-9);
    }
    variance *= varianceScale * varianceScale;
    return new Prediction(clamp(mean), Math.sqrt(Math.max(variance, 1e-9)));
  }

  private double[] standardize(double[] features) {
    double[] standardized = new double[features.length];
    for (int index = 0; index < features.length; index++) {
      standardized[index] = (features[index] - featureMean[index]) / featureScale[index];
    }
    return standardized;
  }

  private double kernel(double[] left, double[] right) {
    double distanceSquared = 0.0;
    for (int index = 0; index < left.length; index++) {
      double difference = left[index] - right[index];
      distanceSquared += difference * difference;
    }
    double scaledDistanceSquared = distanceSquared / (lengthscale * lengthscale);
    if ("rbf".equals(kernel)) {
      return signal * signal * Math.exp(-0.5 * scaledDistanceSquared);
    }
    if ("matern32".equals(kernel)) {
      double scaledDistance = Math.sqrt(3.0 * Math.max(scaledDistanceSquared, 0.0));
      return signal * signal * (1.0 + scaledDistance) * Math.exp(-scaledDistance);
    }
    if ("matern52".equals(kernel)) {
      double scaledDistance = Math.sqrt(5.0 * Math.max(scaledDistanceSquared, 0.0));
      return signal
          * signal
          * (1.0 + scaledDistance + scaledDistance * scaledDistance / 3.0)
          * Math.exp(-scaledDistance);
    }
    double alphaValue = Math.max(rqAlpha, 1e-6);
    return signal
        * signal
        * Math.pow(1.0 + scaledDistanceSquared / (2.0 * alphaValue), -alphaValue);
  }

  private static double[] solveLower(double[][] lower, double[] rightHandSide) {
    double[] result = new double[rightHandSide.length];
    for (int row = 0; row < rightHandSide.length; row++) {
      double sum = rightHandSide[row];
      for (int col = 0; col < row; col++) {
        sum -= lower[row][col] * result[col];
      }
      result[row] = sum / lower[row][row];
    }
    return result;
  }

  private static GaussianProcessStrengthModel load() {
    try (InputStream rawStream =
        GaussianProcessStrengthModel.class.getResourceAsStream(RESOURCE_PATH)) {
      if (rawStream == null) {
        return null;
      }
      try (GZIPInputStream stream = new GZIPInputStream(rawStream);
          InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
        JSONObject root = new JSONObject(new JSONTokener(reader));
        JSONObject prediction = root.getJSONObject("prediction");
        JSONObject hyperparameters = root.getJSONObject("hyperparameters");
        JSONObject trainingState = root.getJSONObject("training_state");
        return new GaussianProcessStrengthModel(
            hyperparameters.getString("kernel"),
            hyperparameters.getDouble("lengthscale"),
            hyperparameters.getDouble("signal"),
            hyperparameters.getDouble("noise"),
            hyperparameters.getDouble("rq_alpha"),
            hyperparameters.getDouble("training_mean"),
            prediction.getDouble("median_cv_bias_correction"),
            prediction.getDouble("variance_scale"),
            doubleArray(prediction.getJSONArray("feature_mean")),
            doubleArray(prediction.getJSONArray("feature_scale")),
            doubleMatrix(trainingState.getJSONArray("x_train_z")),
            doubleArray(trainingState.getJSONArray("alpha")),
            doubleMatrix(trainingState.optJSONArray("cholesky_lower")));
      }
    } catch (RuntimeException | java.io.IOException e) {
      System.err.println("Failed to load GP strength model: " + e.getMessage());
      return null;
    }
  }

  private static double clamp(double rankValue) {
    return Math.max(-18.0, Math.min(12.0, rankValue));
  }

  private static double[] doubleArray(JSONArray array) {
    double[] values = new double[array.length()];
    for (int index = 0; index < array.length(); index++) {
      values[index] = array.getDouble(index);
    }
    return values;
  }

  private static double[][] doubleMatrix(JSONArray array) {
    if (array == null) {
      return new double[0][];
    }
    double[][] values = new double[array.length()][];
    for (int row = 0; row < array.length(); row++) {
      values[row] = doubleArray(array.getJSONArray(row));
    }
    return values;
  }

  static final class Features {
    private final double[] values;

    private Features(double[] values) {
      this.values = values;
    }

    static Features core4(
        double goodMoveRate,
        double firstChoiceRate,
        double averageScoreEquivalentLoss,
        double averageDifficulty) {
      return new Features(
          new double[] {
            goodMoveRate, firstChoiceRate, averageScoreEquivalentLoss, averageDifficulty
          });
    }
  }

  static final class Prediction {
    final double rankValue;
    final double sigma;

    private Prediction(double rankValue, double sigma) {
      this.rankValue = rankValue;
      this.sigma = sigma;
    }
  }

  private static final class Holder {
    private static final GaussianProcessStrengthModel MODEL = load();

    private Holder() {}
  }
}
