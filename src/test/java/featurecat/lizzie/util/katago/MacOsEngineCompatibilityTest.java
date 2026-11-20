package featurecat.lizzie.util.katago;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MacOsEngineCompatibilityTest {
  @TempDir Path directory;

  @Test
  void montereyGetsAnActionableExplanationBeforeLaunchingMetal() throws Exception {
    receipt("15.0");
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> check("Mac OS X", "12.7.6"));
    assertTrue(error.getMessage().contains("macOS 15.0"));
    assertTrue(error.getMessage().contains("12.7.6"));
    assertTrue(error.getMessage().contains("remote"));
  }

  @Test
  void supportedSystemsAndNonMacPlatformsAreNotBlocked() throws Exception {
    receipt("15.0");
    for (String version : List.of("15", "15.0.1", "15.7", "26.1"))
      assertDoesNotThrow(() -> check("Mac OS X", version));
    assertDoesNotThrow(() -> check("Windows 11", "10.0"));
    assertDoesNotThrow(() -> check("Linux", "6.8"));
  }

  @Test
  void compatibleOlderEngineRetainsItsOwnMinimum() throws Exception {
    receipt("11.0");
    assertDoesNotThrow(() -> check("Mac OS X", "12.7.6"));
  }

  @Test
  void unknownAndMalformedReceiptsDeferToExistingProcessValidation() throws Exception {
    assertDoesNotThrow(() -> check("Mac OS X", "12.7.6"));
    for (String json :
        List.of(
            "{",
            "{}",
            "{\"origin\":\"official-release\",\"macOSMinimumVersion\":\"26.0\"}",
            "{\"origin\":\"project-source-build\",\"macOSMinimumVersion\":\"new\"}")) {
      Files.writeString(directory.resolve("source-build.json"), json);
      assertDoesNotThrow(() -> check("Mac OS X", "12.7.6"));
    }
  }

  @Test
  void versionComparisonIsNumericAndRejectsUnparseableValues() {
    assertTrue(MacOsEngineCompatibility.isOlder("9.10.1", "12.0"));
    assertFalse(MacOsEngineCompatibility.isOlder("15.10", "15.2"));
    assertFalse(MacOsEngineCompatibility.isOlder("unknown", "15"));
  }

  private void receipt(String minimum) throws Exception {
    Files.writeString(
        directory.resolve("source-build.json"),
        "{\"origin\":\"project-source-build\",\"macOSMinimumVersion\":\"" + minimum + "\"}");
  }

  private void check(String os, String version) {
    MacOsEngineCompatibility.requireSupported(
        directory.resolve("katago"),
        os,
        version,
        key ->
            "This engine requires macOS {0}; your Mac uses {1}. Use remote compute or a compatible local engine.");
  }
}
