package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HighEndStrengthCalibratorTest {
  @Test
  void liftsProfessionalAnchorsTowardProvidedReferenceValues() {
    assertEquals(11.1, HighEndStrengthCalibrator.calibrate(5.8, 0.709, 0.500, 0.99, 47.6), 0.05);
    assertEquals(10.8, HighEndStrengthCalibrator.calibrate(3.0, 0.716, 0.495, 1.74, 42.9), 0.05);
    assertEquals(8.6, HighEndStrengthCalibrator.calibrate(2.4, 0.500, 0.315, 1.31, 32.3), 0.05);
    assertEquals(9.5, HighEndStrengthCalibrator.calibrate(2.9, 0.333, 0.259, 1.30, 47.2), 0.05);
  }

  @Test
  void doesNotLiftClearlyNonAnchorLowEvidenceProfiles() {
    assertEquals(3.0, HighEndStrengthCalibrator.calibrate(3.0, 0.42, 0.18, 4.2, 34.0), 0.0001);
    assertEquals(4.5, HighEndStrengthCalibrator.calibrate(4.5, 0.50, 0.22, 1.4, 18.0), 0.0001);
  }
}
