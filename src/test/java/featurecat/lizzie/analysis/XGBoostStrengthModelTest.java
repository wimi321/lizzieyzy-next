package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class XGBoostStrengthModelTest {
  @Test
  void loadsBundledSelectedModelAndMatchesKnownPrediction() {
    assertTrue(XGBoostStrengthModel.isAvailable());

    OptionalDouble prediction =
        XGBoostStrengthModel.predictRankValue(
            XGBoostStrengthModel.Features.selectedTop16(
                1.0 / (1.0 + 2.091),
                0.7833,
                1.0 / (1.0 + 1.184),
                1.0,
                0.8333,
                1.0 / (1.0 + 2.896),
                1.0 / (1.0 + 1.703),
                1.0 / (1.0 + 2.867),
                1.0 / (1.0 + 0.713),
                1.0 / (1.0 + 11.907),
                0.6958,
                1.0 / (1.0 + 4.241),
                0.5667,
                1.0 - 0.1167,
                0.8333 * ((45.5 - 25.0) / 35.0),
                0.7833 * ((45.5 - 25.0) / 35.0)));

    assertTrue(prediction.isPresent());
    assertEquals(4.412, prediction.getAsDouble(), 0.02);
  }
}
