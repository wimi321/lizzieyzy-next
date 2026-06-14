package featurecat.lizzie.analysis;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Residual calibrator for the xgboost20tun strength model. */
final class XGBoost20TunResidualCalibrator {
  private static final String CALIBRATOR_RESOURCE_PATH =
      "/models/strength/xgboost20tun_residual_calibrator.json";
  private static final String CALIBRATOR_PROPERTY = "lizzie.strength.xgboost20tun.calibrator";
  private static final String LEGACY_ZHANGQI_CALIBRATOR_PROPERTY =
      "lizzie.strength.zhangqi.calibrator";
  private static final String DEFAULT_CALIBRATOR_PATH =
      "target/zhangqi-xgb-distill-v500-m210/nested-tuning/residual-calibration/calibrator.json";
  private static final double MIN_RANK_VALUE = -1.0;
  private static final double MAX_RANK_VALUE = 12.0;
  private static final double[] DEFAULT_MATCH_GATE = {0.52, 0.60};
  private static final double[] DEFAULT_TOP5_GATE = {0.68, 0.80};
  private static final double[] DEFAULT_WEIGHTED_LOSS_GATE = {0.32, 0.50};

  private final double gateStart;
  private final double gateFull;
  private final double correctionMin;
  private final double correctionMax;
  private final String[] featureOrder;
  private final double[] scalerMean;
  private final double[] scalerScale;
  private final double intercept;
  private final double[] coefficients;
  private final double[] matchGate;
  private final double[] top5Gate;
  private final double[] weightedLossGate;

  private XGBoost20TunResidualCalibrator(
      double gateStart,
      double gateFull,
      double correctionMin,
      double correctionMax,
      String[] featureOrder,
      double[] scalerMean,
      double[] scalerScale,
      double intercept,
      double[] coefficients,
      double[] matchGate,
      double[] top5Gate,
      double[] weightedLossGate) {
    this.gateStart = gateStart;
    this.gateFull = gateFull;
    this.correctionMin = correctionMin;
    this.correctionMax = correctionMax;
    this.featureOrder = featureOrder;
    this.scalerMean = scalerMean;
    this.scalerScale = scalerScale;
    this.intercept = intercept;
    this.coefficients = coefficients;
    this.matchGate = matchGate;
    this.top5Gate = top5Gate;
    this.weightedLossGate = weightedLossGate;
  }

  static boolean isAvailable() {
    return Holder.CALIBRATOR != null;
  }

  static boolean isAvailable(Path path) {
    return calibratorFor(path) != null;
  }

  static boolean isAvailable(String resourcePath, Path path) {
    return calibratorFor(resourcePath, path) != null;
  }

  static double calibrate(double basePrediction, XGBoostStrengthModel.Features full29Features) {
    return calibrate(basePrediction, full29Features, null);
  }

  static double calibrate(
      double basePrediction, XGBoostStrengthModel.Features full29Features, Path calibratorPath) {
    return calibrate(basePrediction, full29Features, null, calibratorPath);
  }

  static double calibrate(
      double basePrediction,
      XGBoostStrengthModel.Features full29Features,
      String calibratorResourcePath,
      Path calibratorPath) {
    XGBoost20TunResidualCalibrator calibrator =
        calibratorFor(calibratorResourcePath, calibratorPath);
    if (calibrator == null || full29Features == null) {
      return clamp(basePrediction);
    }
    return calibrator.apply(basePrediction, full29Features);
  }

  static XGBoost20TunResidualCalibrator load(Path path) {
    if (path == null || !Files.isRegularFile(path)) {
      return null;
    }
    try (InputStream stream = Files.newInputStream(path)) {
      return parse(stream);
    } catch (RuntimeException | java.io.IOException e) {
      System.err.println(
          "Failed to load xgboost20tun residual calibrator " + path + ": " + e.getMessage());
      return null;
    }
  }

  private static XGBoost20TunResidualCalibrator loadDefault() {
    XGBoost20TunResidualCalibrator configured = load(configuredPath());
    if (configured != null) {
      return configured;
    }
    XGBoost20TunResidualCalibrator bundled = loadResource();
    if (bundled != null) {
      return bundled;
    }
    return load(Paths.get(DEFAULT_CALIBRATOR_PATH));
  }

  private static XGBoost20TunResidualCalibrator loadResource() {
    return loadResource(CALIBRATOR_RESOURCE_PATH, "bundled xgboost20tun residual calibrator");
  }

  private static XGBoost20TunResidualCalibrator loadResource(
      String resourcePath, String description) {
    try (InputStream stream =
        XGBoost20TunResidualCalibrator.class.getResourceAsStream(resourcePath)) {
      if (stream == null) {
        return null;
      }
      return parse(stream);
    } catch (RuntimeException | java.io.IOException e) {
      System.err.println("Failed to load " + description + ": " + e.getMessage());
      return null;
    }
  }

  private static XGBoost20TunResidualCalibrator calibratorFor(Path path) {
    return calibratorFor(null, path);
  }

  private static XGBoost20TunResidualCalibrator calibratorFor(String resourcePath, Path path) {
    if (resourcePath != null && !resourcePath.trim().isEmpty()) {
      String normalized = resourcePath.trim();
      return Holder.RESOURCE_CALIBRATORS.computeIfAbsent(
          normalized,
          candidate -> loadResource(candidate, "XGBoost residual calibrator " + candidate));
    }
    if (path == null) {
      return Holder.CALIBRATOR;
    }
    Path normalized = path.toAbsolutePath().normalize();
    return Holder.EXTERNAL_CALIBRATORS.computeIfAbsent(
        normalized, XGBoost20TunResidualCalibrator::load);
  }

  private static XGBoost20TunResidualCalibrator parse(InputStream stream)
      throws java.io.IOException {
    JSONObject root =
        new JSONObject(new JSONTokener(new InputStreamReader(stream, StandardCharsets.UTF_8)));
    JSONObject finalCalibrator = root.getJSONObject("final_calibrator");
    JSONObject spec = finalCalibrator.getJSONObject("spec");
    JSONObject qualityGates = root.optJSONObject("quality_gate_thresholds");
    return new XGBoost20TunResidualCalibrator(
        spec.getDouble("gate_start"),
        spec.getDouble("gate_full"),
        spec.optDouble("correction_min", -0.5),
        spec.optDouble("correction_max", 1.0),
        stringArray(finalCalibrator.getJSONArray("feature_order")),
        doubleArray(finalCalibrator.getJSONArray("scaler_mean")),
        doubleArray(finalCalibrator.getJSONArray("scaler_scale")),
        finalCalibrator.getDouble("intercept"),
        doubleArray(finalCalibrator.getJSONArray("coefficients")),
        gate(qualityGates, "match_rate", DEFAULT_MATCH_GATE),
        gate(qualityGates, "top5_rate", DEFAULT_TOP5_GATE),
        gate(qualityGates, "weighted_loss_fit", DEFAULT_WEIGHTED_LOSS_GATE));
  }

  private double apply(double basePrediction, XGBoostStrengthModel.Features full29Features) {
    if (!isValid()) {
      return clamp(basePrediction);
    }
    double rawCorrection = intercept;
    for (int index = 0; index < coefficients.length; index++) {
      double scale = scalerScale[index] == 0.0 ? 1.0 : scalerScale[index];
      double featureValue = featureValue(featureOrder[index], basePrediction, full29Features);
      if (!Double.isFinite(featureValue)) {
        return clamp(basePrediction);
      }
      double standardized = (featureValue - scalerMean[index]) / scale;
      rawCorrection += coefficients[index] * standardized;
    }
    double rankGate = clamp01((basePrediction - gateStart) / Math.max(gateFull - gateStart, 1e-9));
    double qualityGate = highRankQualityGate(full29Features);
    double correction = rankGate * qualityGate * clamp(rawCorrection, correctionMin, correctionMax);
    return clamp(basePrediction + correction);
  }

  private boolean isValid() {
    return featureOrder.length == coefficients.length
        && scalerMean.length == coefficients.length
        && scalerScale.length == coefficients.length;
  }

  private double highRankQualityGate(XGBoostStrengthModel.Features features) {
    return Math.max(
        gateValue(features.valueAtFull29Index(7), matchGate),
        Math.max(
            gateValue(features.valueAtFull29Index(2), top5Gate),
            gateValue(features.valueAtFull29Index(10), weightedLossGate)));
  }

  private static double featureValue(
      String name, double basePrediction, XGBoostStrengthModel.Features features) {
    if ("base_prediction".equals(name)) {
      return basePrediction;
    }
    if (name.startsWith("hinge_above_")) {
      return Math.max(
          0.0, basePrediction - Double.parseDouble(name.substring("hinge_above_".length())));
    }
    if ("match_rate".equals(name)) {
      return features.valueAtFull29Index(7);
    }
    if ("first_choice_rate".equals(name)) {
      return features.valueAtFull29Index(0);
    }
    if ("top5_rate".equals(name)) {
      return features.valueAtFull29Index(2);
    }
    if ("weighted_loss_fit".equals(name)) {
      return features.valueAtFull29Index(10);
    }
    if ("difficulty_fit".equals(name)) {
      return features.valueAtFull29Index(18);
    }
    return Double.NaN;
  }

  private static double gateValue(double value, double[] threshold) {
    if (threshold == null || threshold.length < 2) {
      return 0.0;
    }
    return clamp01((value - threshold[0]) / Math.max(threshold[1] - threshold[0], 1e-9));
  }

  private static double[] gate(JSONObject gates, String name, double[] fallback) {
    if (gates == null || !gates.has(name)) {
      return fallback;
    }
    return doubleArray(gates.getJSONArray(name));
  }

  private static Path configuredPath() {
    String configured = System.getProperty(CALIBRATOR_PROPERTY, "").trim();
    if (configured.isEmpty()) {
      configured = System.getProperty(LEGACY_ZHANGQI_CALIBRATOR_PROPERTY, "").trim();
    }
    if (!configured.isEmpty()) {
      return Paths.get(configured);
    }
    return null;
  }

  private static String[] stringArray(JSONArray array) {
    String[] values = new String[array.length()];
    for (int index = 0; index < array.length(); index++) {
      values[index] = array.getString(index);
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

  private static double clamp01(double value) {
    return clamp(value, 0.0, 1.0);
  }

  private static double clamp(double value) {
    return clamp(value, MIN_RANK_VALUE, MAX_RANK_VALUE);
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }

  private static final class Holder {
    private static final XGBoost20TunResidualCalibrator CALIBRATOR = loadDefault();
    private static final ConcurrentMap<String, XGBoost20TunResidualCalibrator>
        RESOURCE_CALIBRATORS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Path, XGBoost20TunResidualCalibrator> EXTERNAL_CALIBRATORS =
        new ConcurrentHashMap<>();

    private Holder() {}
  }
}
