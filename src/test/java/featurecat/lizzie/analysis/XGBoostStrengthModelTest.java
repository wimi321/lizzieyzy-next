package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class XGBoostStrengthModelTest {
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
