package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class GaussianProcessStrengthModelTest {
  @Test
  void loadsBundledCoreFourModelAndMatchesKnownPrediction() {
    assertTrue(GaussianProcessStrengthModel.isAvailable());

    Optional<GaussianProcessStrengthModel.Prediction> prediction =
        GaussianProcessStrengthModel.predictRankValue(
            GaussianProcessStrengthModel.Features.core4(0.7833, 0.5667, 1.184, 45.5));

    assertTrue(prediction.isPresent());
    assertEquals(5.351, prediction.get().rankValue, 0.02);
    assertEquals(1.391, prediction.get().sigma, 0.02);
  }
}
