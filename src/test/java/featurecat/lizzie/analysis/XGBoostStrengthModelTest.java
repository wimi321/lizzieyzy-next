package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalDouble;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XGBoostStrengthModelTest {
  @TempDir Path tempDir;

  @Test
  void loadsBundledXGBoost20TunModelAndCalibrator() {
    assertTrue(XGBoostStrengthModel.isXGBoost20TunAvailable());
    assertTrue(XGBoost20TunResidualCalibrator.isAvailable());

    OptionalDouble prediction =
        XGBoostStrengthModel.predictXGBoost20TunRankValue(knownFull29Features());

    assertTrue(prediction.isPresent());
    assertEquals(10.210, prediction.getAsDouble(), 0.03);
  }

  @Test
  void residualCalibratorOnlyAppliesAboveGateStart() {
    assertTrue(XGBoost20TunResidualCalibrator.isAvailable());

    XGBoostStrengthModel.Features features =
        XGBoostStrengthModel.Features.full29(
            0.2952, 0.0, 0.6000, 0.0, 0.0, 0.6152, 0.0, 0.5014, 1.0, 1.0, 0.3260, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.40, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

    double belowGate = XGBoost20TunResidualCalibrator.calibrate(4.8, features);
    double aboveGate = XGBoost20TunResidualCalibrator.calibrate(7.4, features);

    assertEquals(4.8, belowGate, 0.0001);
    assertTrue(aboveGate > 7.4);
  }

  @Test
  void malformedHingeThresholdInImportedCalibratorDoesNotCrashPrediction() throws Exception {
    Path calibrator = tempDir.resolve("bad-hinge-calibrator.json");
    JSONObject root = new JSONObject();
    JSONObject finalCalibrator = new JSONObject();
    finalCalibrator.put(
        "spec",
        new JSONObject()
            .put("gate_start", 0.0)
            .put("gate_full", 1.0)
            .put("correction_min", -1.0)
            .put("correction_max", 1.0));
    finalCalibrator.put("feature_order", new JSONArray().put("hinge_above_not-a-number"));
    finalCalibrator.put("scaler_mean", new JSONArray().put(0.0));
    finalCalibrator.put("scaler_scale", new JSONArray().put(1.0));
    finalCalibrator.put("intercept", 1.0);
    finalCalibrator.put("coefficients", new JSONArray().put(1.0));
    root.put("final_calibrator", finalCalibrator);
    Files.writeString(calibrator, root.toString(2));

    assertTrue(XGBoost20TunResidualCalibrator.isAvailable(calibrator));
    assertEquals(
        8.0,
        XGBoost20TunResidualCalibrator.calibrate(8.0, knownFull29Features(), calibrator),
        0.0001);
  }

  private static XGBoostStrengthModel.Features knownFull29Features() {
    return XGBoostStrengthModel.Features.full29(
        0.4444,
        0.7444,
        0.8111,
        0.2284148013,
        0.5444,
        0.8222,
        0.8222,
        0.67,
        1.0,
        1.0,
        0.5154639175,
        0.6406149904,
        0.9132420091,
        0.5379236148,
        0.3597122302,
        0.2777777778,
        0.2317497103,
        0.546149645,
        0.32,
        0.5800464037,
        0.4803073967,
        0.7942811755,
        0.9333,
        0.74,
        0.9,
        0.142208,
        0.263104,
        0.2144,
        0.259552);
  }
}
