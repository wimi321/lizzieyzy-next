package featurecat.lizzie.util;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.util.katago.tuning.KataGoCommandSpec;
import featurecat.lizzie.util.katago.tuning.KataGoMeasuredFingerprint;
import featurecat.lizzie.util.katago.tuning.KataGoMeasuredReport;
import featurecat.lizzie.util.katago.tuning.KataGoMeasuredReport.Scene;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.json.JSONObject;

/** Explicitly accepted, scene-scoped overlays, independent of legacy Apple/official profiles. */
public final class MeasuredKataGoTuning {
  public static final String KEY = "katago-measured-scenes-v1";

  private MeasuredKataGoTuning() {}

  public record Review(String entryId, String command, KataGoMeasuredReport report) {}

  @FunctionalInterface
  interface Verification {
    void verify(EngineData entry, String command, KataGoMeasuredReport report) throws IOException;
  }

  public static Review review(String entryId, Path reportFile) throws IOException {
    return review(entryId, reportFile, MeasuredKataGoTuning::verifyFingerprint);
  }

  static Review review(String entryId, Path reportFile, Verification verification)
      throws IOException {
    requireWorker();
    if (Files.size(reportFile) > 1024 * 1024) throw new IOException("Report exceeds 1 MiB");
    KataGoMeasuredReport report =
        KataGoMeasuredReport.parse(new JSONObject(Files.readString(reportFile)));
    var assessment = report.assess();
    if (!assessment.eligible()) throw new IOException(String.join("\n", assessment.reasons()));
    EngineData entry = requireEntry(entryId);
    String command = commandFor(entry, report.scene());
    requireSupportedScene(entry, report.scene());
    verification.verify(entry, command, report);
    requireSupportedScene(requireEntry(entryId), report.scene());
    return new Review(entryId, command, report);
  }

  /** Called only after the UI has explicitly confirmed this exact reviewed scene/report. */
  public static void apply(Review reviewed) throws IOException {
    apply(reviewed, MeasuredKataGoTuning::verifyFingerprint);
  }

  static void apply(Review reviewed, Verification verification) throws IOException {
    requireWorker();
    EngineData current = requireEntry(reviewed.entryId());
    if (!reviewed.command().equals(commandFor(current, reviewed.report().scene()))) {
      throw new IOException("Engine command changed after review");
    }
    if (!reviewed.report().assess().eligible())
      throw new IOException("Report no longer passes acceptance");
    requireSupportedScene(current, reviewed.report().scene());
    verification.verify(current, reviewed.command(), reviewed.report());
    synchronized (Utils.class) {
      ArrayList<EngineData> entries = Utils.getEngineData();
      EngineData target = find(entries, reviewed.entryId());
      requireSupportedScene(target, reviewed.report().scene());
      if (!reviewed.command().equals(commandFor(target, reviewed.report().scene()))) {
        throw new IOException("Engine command changed before commit");
      }
      if (target.threadPolicy == null) target.threadPolicy = new JSONObject();
      JSONObject scenes = target.threadPolicy.optJSONObject(KEY);
      scenes = scenes == null ? new JSONObject() : new JSONObject(scenes.toString());
      scenes.put(
          reviewed.report().scene().id(),
          new JSONObject()
              .put("schemaVersion", 1)
              .put("command", reviewed.command())
              .put("report", reviewed.report().toJson())
              .put("acceptedAt", System.currentTimeMillis()));
      target.threadPolicy.put(KEY, scenes);
      save(entries);
    }
  }

  public static boolean hasProfile(String entryId) {
    EngineData entry = EngineThreadPolicy.findSavedEntry(entryId);
    JSONObject scenes =
        entry == null || entry.threadPolicy == null ? null : entry.threadPolicy.optJSONObject(KEY);
    return scenes != null && !scenes.isEmpty();
  }

  public static void restore(String entryId) throws IOException {
    requireWorker();
    synchronized (Utils.class) {
      ArrayList<EngineData> entries = Utils.getEngineData();
      EngineData target = find(entries, entryId);
      if (target.threadPolicy != null) target.threadPolicy.remove(KEY);
      // Commands are never rewritten: removing overlays restores exactly the configured policies,
      // including any edits the user made after accepting a recommendation.
      save(entries);
    }
  }

  public static List<String> applyLive(List<String> command, EngineData entry) {
    if (entry == null || entry.threadPolicy == null || SwingUtilities.isEventDispatchThread())
      return command;
    return applyOverlay(command, entry, entry.commands, Scene.LIVE);
  }

  public static List<String> applyWholeGame(List<String> command, String configuredCommand) {
    if (SwingUtilities.isEventDispatchThread() || Lizzie.config == null) return command;
    EngineData match = null;
    for (EngineData entry : Utils.getEngineData()) {
      JSONObject stored = stored(entry, Scene.WHOLE_GAME);
      if (stored != null && configuredCommand.equals(stored.optString("command"))) {
        if (match != null) return command;
        match = entry;
      }
    }
    return match == null
        ? command
        : applyOverlay(command, match, configuredCommand, Scene.WHOLE_GAME);
  }

  private static List<String> applyOverlay(
      List<String> command, EngineData entry, String configured, Scene scene) {
    return applyOverlay(command, entry, configured, scene, MeasuredKataGoTuning::verifyFingerprint);
  }

  static List<String> applyOverlay(
      List<String> command,
      EngineData entry,
      String configured,
      Scene scene,
      Verification verification) {
    JSONObject stored = stored(entry, scene);
    if (stored == null
        || stored.optInt("schemaVersion") != 1
        || !configured.equals(stored.optString("command"))) return command;
    try {
      KataGoMeasuredReport report = KataGoMeasuredReport.parse(stored.getJSONObject("report"));
      if (report.scene() != scene || !report.assess().eligible()) return command;
      requireSupportedScene(entry, scene);
      verification.verify(entry, configured, report);
      requireSupportedScene(entry, scene);
      Map<String, String> overrides = new LinkedHashMap<>(report.candidateOverrides());
      if (scene == Scene.WHOLE_GAME) overrides.put("numSearchThreads", "");
      return KataGoCommandSpec.parse(command).withForcedOverrides(overrides);
    } catch (IOException | RuntimeException invalidated) {
      return command;
    }
  }

  private static JSONObject stored(EngineData entry, Scene scene) {
    JSONObject scenes =
        entry == null || entry.threadPolicy == null ? null : entry.threadPolicy.optJSONObject(KEY);
    return scenes == null ? null : scenes.optJSONObject(scene.id());
  }

  private static void requireSupportedScene(EngineData entry, Scene scene) throws IOException {
    if (entry.useJavaSSH) throw new IOException("Measured profiles require a local saved engine");
    if (scene != Scene.WHOLE_GAME) return;
    if (Lizzie.config == null || Lizzie.config.leelazConfig == null)
      throw new IOException("Whole-game analysis settings are unavailable");
    if (Lizzie.config.analysisReuseCurrentEngine)
      throw new IOException(
          "This whole-game report measures an independent analysis process; "
              + "reusing the current engine is not supported");
    if (Utils.getAnalysisEngineRemoteEngineData().useJavaSSH)
      throw new IOException(
          "This whole-game report measures local hardware; remote analysis through SSH "
              + "is not supported");
  }

  private static void verifyFingerprint(
      EngineData entry, String command, KataGoMeasuredReport report) throws IOException {
    requireWorker();
    if (entry.useJavaSSH || !EngineThreadPolicy.isLocalKataGoCommand(command, false)) {
      throw new IOException("Measured profiles require a local KataGo engine");
    }
    EngineData inspected = new EngineData();
    inspected.id = entry.id;
    inspected.name = entry.name;
    inspected.commands = command;
    var snapshot = KataGoAutoSetupHelper.inspectSavedEngine(inspected);
    var hardware = NvidiaGpuDetector.detectBestGpu();
    if (!hardware.detected || hardware.gpus.size() != 1)
      throw new IOException("A single measured NVIDIA GPU is required");
    List<String> effectiveCommand =
        KataGoRuntimeHelper.prepareBundledLaunchCommand(
            snapshot.sourceArguments, snapshot.enginePath);
    if (report.scene() == Scene.LIVE) {
      EngineData withoutMeasured =
          Utils.engineDataFromJson(Utils.engineDataToJson(entry), entry.index);
      if (withoutMeasured.threadPolicy != null) withoutMeasured.threadPolicy.remove(KEY);
      effectiveCommand =
          KataGoRuntimeHelper.applyEntryLaunchPolicy(
              effectiveCommand, snapshot.enginePath, withoutMeasured);
    } else {
      effectiveCommand =
          Utils.splitCommand(
              KataGoRuntimeHelper.optimizeAnalysisEngineCommand(
                  command, report.budget(), false, false));
      effectiveCommand =
          KataGoRuntimeHelper.prepareBundledLaunchCommand(effectiveCommand, snapshot.enginePath);
    }
    JSONObject current =
        KataGoMeasuredFingerprint.capture(snapshot, hardware.bestGpu, effectiveCommand);
    if (!current.similar(report.fingerprint()))
      throw new IOException(
          "Engine, weights, config, GPU, driver or command no longer matches this report");
    if (report.scene() == Scene.WHOLE_GAME) {
      var live = KataGoAutoSetupHelper.inspectSavedEngine(entry);
      if (live == null
          || !sameArtifact(live.enginePath, snapshot.enginePath, current.getString("engineSha256"))
          || !sameArtifact(
              live.activeWeightPath, snapshot.activeWeightPath, current.getString("modelSha256"))) {
        throw new IOException("Whole-game analysis must use the selected engine and weights");
      }
    }
  }

  private static boolean sameArtifact(Path first, Path second, String digest) throws IOException {
    return first != null
        && second != null
        && (first.toRealPath().equals(second.toRealPath())
            || KataGoMeasuredFingerprint.sha256(first).equals(digest));
  }

  private static String commandFor(EngineData entry, Scene scene) throws IOException {
    String command = scene == Scene.LIVE ? entry.commands : Lizzie.config.analysisEngineCommand;
    if (command == null || command.isBlank()) throw new IOException("No command for this scene");
    return command;
  }

  private static EngineData requireEntry(String entryId) throws IOException {
    EngineData entry = EngineThreadPolicy.findSavedEntry(entryId);
    if (entry == null) throw new IOException("Saved engine no longer exists");
    return entry;
  }

  private static EngineData find(List<EngineData> entries, String id) throws IOException {
    return entries.stream()
        .filter(entry -> id.equals(entry.id))
        .findFirst()
        .orElseThrow(() -> new IOException("Saved engine no longer exists"));
  }

  private static void save(ArrayList<EngineData> entries) throws IOException {
    try {
      Utils.saveEngineSettings(entries);
    } catch (java.io.UncheckedIOException failure) {
      throw failure.getCause();
    }
  }

  private static void requireWorker() throws IOException {
    if (SwingUtilities.isEventDispatchThread())
      throw new IOException("Measured tuning requires a worker thread");
  }
}
