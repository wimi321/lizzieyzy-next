package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.util.katago.tuning.KataGoMeasuredFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KataGoMeasuredFingerprintTest {
  @TempDir Path directory;

  @Test
  void preservedSizeAndTimestampModelReplacementChangesContentIdentity() throws Exception {
    EngineData entry = entry();
    var snapshot = KataGoAutoSetupHelper.inspectSavedEngine(entry);
    var before = KataGoMeasuredFingerprint.capture(snapshot, gpu());
    var modified = Files.getLastModifiedTime(snapshot.activeWeightPath);
    Files.writeString(snapshot.activeWeightPath, "model B");
    Files.setLastModifiedTime(snapshot.activeWeightPath, modified);
    var after = KataGoMeasuredFingerprint.capture(snapshot, gpu());
    assertNotEquals(before.getString("modelSha256"), after.getString("modelSha256"));
  }

  @Test
  void recursiveIncludesAreHashedAndUnsupportedOrRepeatedDirectivesFailClosed() throws Exception {
    EngineData entry = entry();
    Files.writeString(directory.resolve("gtp.cfg"), "@include 'child.cfg'\nnumSearchThreads=8");
    Files.writeString(directory.resolve("child.cfg"), "@include = grand.cfg\n");
    Files.writeString(directory.resolve("grand.cfg"), "rules=chinese");
    var snapshot = KataGoAutoSetupHelper.inspectSavedEngine(entry);
    var before = KataGoMeasuredFingerprint.capture(snapshot, gpu());
    assertEquals(2, before.getJSONArray("configIncludes").length());
    Files.writeString(directory.resolve("grand.cfg"), "rules=japanese");
    var after = KataGoMeasuredFingerprint.capture(snapshot, gpu());
    assertNotEquals(
        before.getJSONArray("configIncludes").toString(),
        after.getJSONArray("configIncludes").toString());
    Files.writeString(directory.resolve("grand.cfg"), "@include child.cfg");
    assertThrows(
        java.io.IOException.class, () -> KataGoMeasuredFingerprint.capture(snapshot, gpu()));
    Files.writeString(directory.resolve("grand.cfg"), "@unsupported child.cfg");
    assertThrows(
        java.io.IOException.class, () -> KataGoMeasuredFingerprint.capture(snapshot, gpu()));
  }

  @Test
  void commandSemanticsBindNonTuningOverridesAndRejectUnknownInputs() throws Exception {
    EngineData entry = entry();
    entry.commands += " -override-config rules=chinese,numSearchThreads=8,nnMaxBatchSize=16";
    var first =
        KataGoMeasuredFingerprint.capture(KataGoAutoSetupHelper.inspectSavedEngine(entry), gpu());
    assertEquals("chinese", first.getJSONObject("commandSemantics").getString("rules"));
    assertFalse(first.getJSONObject("commandSemantics").has("numSearchThreads"));
    entry.commands += " -override-config numSearchThreads=16,nnMaxBatchSize=32";
    assertTrue(
        first.similar(
            KataGoMeasuredFingerprint.capture(
                KataGoAutoSetupHelper.inspectSavedEngine(entry), gpu())));
    entry.commands += " -unmeasured-input other.bin";
    assertThrows(
        java.io.IOException.class,
        () ->
            KataGoMeasuredFingerprint.capture(
                KataGoAutoSetupHelper.inspectSavedEngine(entry), gpu()));
  }

  @Test
  void eventThreadCannotPerformModelHashing() throws Exception {
    var snapshot = KataGoAutoSetupHelper.inspectSavedEngine(entry());
    SwingUtilities.invokeAndWait(
        () ->
            assertThrows(
                java.io.IOException.class,
                () -> KataGoMeasuredFingerprint.capture(snapshot, gpu())));
  }

  @Test
  void effectiveLaunchSettingsRemainPartOfIdentityIncludingBudgetAndPvLength() throws Exception {
    var snapshot = KataGoAutoSetupHelper.inspectSavedEngine(entry());
    var command = new java.util.ArrayList<>(snapshot.sourceArguments);
    command.add("-override-config");
    command.add("analysisPVLen=100,maxVisits=5000,numNNServerThreadsPerModel=2,homeDataDir=cache");
    var fingerprint = KataGoMeasuredFingerprint.capture(snapshot, gpu(), command);
    var semantics = fingerprint.getJSONObject("commandSemantics");
    assertEquals("100", semantics.getString("analysisPVLen"));
    assertEquals("5000", semantics.getString("maxVisits"));
    assertEquals("2", semantics.getString("numNNServerThreadsPerModel"));
    assertFalse(semantics.has("homeDataDir"));
    assertFalse(fingerprint.similar(KataGoMeasuredFingerprint.capture(snapshot, gpu())));
  }

  @Test
  void perRunOutputLocationsAreIgnoredButLoggingBehaviorIsStillBound() throws Exception {
    var snapshot = KataGoAutoSetupHelper.inspectSavedEngine(entry());
    var command = new java.util.ArrayList<>(snapshot.sourceArguments);
    command.add("-override-config");
    command.add(
        "homeDataDir=cache-a,logDir=round-a,logFile=round-a.log,logDirDated=dated-a,"
            + "logToStderr=false,logSearchInfo=false,maxVisits=5000,analysisPVLen=100");
    var first = KataGoMeasuredFingerprint.capture(snapshot, gpu(), command);
    var semantics = first.getJSONObject("commandSemantics");
    for (String key : java.util.List.of("homeDataDir", "logDir", "logFile", "logDirDated"))
      assertFalse(semantics.has(key));
    assertEquals("false", semantics.getString("logToStderr"));
    assertEquals("false", semantics.getString("logSearchInfo"));
    command.add("-override-config");
    command.add("homeDataDir=cache-b,logDir=round-b,logFile=round-b.log,logDirDated=dated-b");
    assertTrue(first.similar(KataGoMeasuredFingerprint.capture(snapshot, gpu(), command)));
    for (String change :
        java.util.List.of(
            "logToStderr=true", "logSearchInfo=true", "maxVisits=6000", "analysisPVLen=15")) {
      var changed = new java.util.ArrayList<>(command);
      changed.add("-override-config");
      changed.add(change);
      assertFalse(first.similar(KataGoMeasuredFingerprint.capture(snapshot, gpu(), changed)), change);
    }
  }

  private EngineData entry() throws Exception {
    Path engine = Files.writeString(directory.resolve("katago.exe"), "engine");
    Path model = Files.writeString(directory.resolve("model.bin.gz"), "model A");
    Path config = Files.writeString(directory.resolve("gtp.cfg"), "numSearchThreads=8");
    EngineData entry = new EngineData();
    entry.commands = "\"" + engine + "\" gtp -config \"" + config + "\" -model \"" + model + "\"";
    entry.name = "test";
    return entry;
  }

  private static NvidiaGpuDetector.GpuInfo gpu() {
    return new NvidiaGpuDetector.GpuInfo("RTX 5090", 12, 0, "591.86", 32607, "test");
  }
}
