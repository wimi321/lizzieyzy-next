package featurecat.lizzie.util.katago.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KataGoCommandSpecTest {
  @Test
  void laterOverrideSegmentsWinAndNormalizeToOneOption() {
    KataGoCommandSpec spec =
        KataGoCommandSpec.parse(
            List.of(
                "katago",
                "analysis",
                "--keep",
                "untouched",
                "-override-config",
                "threads=1,shared=first,,",
                "--override-config",
                "shared=second,newKey=newValue",
                "-model",
                "model.bin"));

    assertEquals("1", spec.overrideValue("threads").orElseThrow());
    assertEquals("second", spec.overrideValue("shared").orElseThrow());
    assertEquals("newValue", spec.overrideValue("newKey").orElseThrow());
    assertTrue(spec.hasOverrideMatching(key -> key.startsWith("new")));
    assertFalse(spec.hasOverrideMatching(key -> key.equals("missing")));
    assertEquals(
        Map.of("threads", "1", "shared", "second", "newKey", "newValue"),
        spec.effectiveOverrides());
    assertEquals(
        List.of(
            "katago",
            "analysis",
            "--keep",
            "untouched",
            "-override-config",
            "threads=1,shared=second,newKey=newValue",
            "-model",
            "model.bin"),
        spec.withManagedOverrides(Map.of()));
  }

  @Test
  void managedOverridesKeepExplicitValuesAndAddOnlyMissingKeys() {
    KataGoCommandSpec spec =
        KataGoCommandSpec.parse(
            List.of(
                "katago",
                "gtp",
                "-override-config",
                "numSearchThreads=2,userSetting=keep",
                "-config",
                "gtp.cfg"));
    Map<String, String> managed = new LinkedHashMap<>();
    managed.put("numSearchThreads", "8");
    managed.put("numNNServerThreadsPerModel", "4");

    assertEquals(
        List.of(
            "katago",
            "gtp",
            "-override-config",
            "numSearchThreads=2,userSetting=keep,numNNServerThreadsPerModel=4",
            "-config",
            "gtp.cfg"),
        spec.withManagedOverrides(managed));
  }

  @Test
  void forcedOverridesReplaceEffectiveValuesAndRetainUnknownData() {
    KataGoCommandSpec spec =
        KataGoCommandSpec.parse(
            List.of(
                "katago",
                "benchmark",
                "-override-config",
                "unknown=value,numSearchThreads=1",
                "--diagnostic",
                "yes",
                "--override-config",
                "numSearchThreads=2"));
    Map<String, String> forced = new LinkedHashMap<>();
    forced.put("numSearchThreads", "16");
    forced.put("metalDeviceToUseThread0", "0");

    assertEquals(
        List.of(
            "katago",
            "benchmark",
            "-override-config",
            "unknown=value,numSearchThreads=16,metalDeviceToUseThread0=0",
            "--diagnostic",
            "yes"),
        spec.withForcedOverrides(forced));
  }

  @Test
  void missingManagedKeysHaveDeterministicOrder() {
    KataGoCommandSpec spec = KataGoCommandSpec.parse(List.of("katago", "analysis"));
    Map<String, String> managed = new LinkedHashMap<>();
    managed.put("zeta", "last");
    managed.put("alpha", "first");

    assertEquals(
        List.of("katago", "analysis", "-override-config", "alpha=first,zeta=last"),
        spec.withManagedOverrides(managed));
  }
}
