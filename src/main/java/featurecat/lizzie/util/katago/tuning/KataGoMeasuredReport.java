package featurecat.lizzie.util.katago.tuning;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable evidence only. Parsing or assessing a report never applies its parameters. */
public final class KataGoMeasuredReport {
  public enum Scene {
    LIVE("live", Set.of("numSearchThreads", "nnMaxBatchSize")),
    WHOLE_GAME(
        "whole-game",
        Set.of("numAnalysisThreads", "numSearchThreadsPerAnalysisThread", "nnMaxBatchSize"));

    private final String id;
    private final Set<String> keys;

    Scene(String id, Set<String> keys) {
      this.id = id;
      this.keys = keys;
    }

    public String id() {
      return id;
    }

    private static Scene parse(String value) {
      for (Scene scene : values()) if (scene.id.equals(value)) return scene;
      throw invalid("Unsupported scene: " + value);
    }
  }

  public record Assessment(boolean eligible, List<String> reasons, double speedup) {
    public Assessment {
      reasons = List.copyOf(reasons);
    }
  }

  private record Run(
      String profile,
      int round,
      double seconds,
      double first,
      double response,
      double edt,
      double memory,
      int processes,
      List<Integer> visits) {}

  private final String json;
  private final Scene scene;
  private final int budget;
  private final int positions;
  private final String fixtureSha256;
  private final double totalMemory;
  private final Map<String, Integer> baselineParameters;
  private final Map<String, Integer> candidateParameters;
  private final List<Run> runs;

  private KataGoMeasuredReport(JSONObject source) {
    if (integer(source, "schemaVersion", false) != 1) throw invalid("Unsupported schemaVersion");
    if (!"application".equals(string(source, "measurementMode"))) {
      throw invalid("Only application measurements can support a recommendation");
    }
    scene = Scene.parse(string(source, "scene"));
    budget = integer(source, "budget", false);
    positions = integer(source, "positions", false);
    if (scene == Scene.LIVE && positions != 1) throw invalid("Live reports require one position");
    fixtureSha256 = hash(source, "fixtureSha256");
    if (!"totalGpu".equals(string(source, "metricScope"))) {
      throw invalid("metricScope must be totalGpu");
    }
    JSONObject fingerprint = object(source, "fingerprint");
    hash(fingerprint, "engineSha256");
    hash(fingerprint, "modelSha256");
    hash(fingerprint, "configSha256");
    string(fingerprint, "gpuName");
    string(fingerprint, "driverVersion");
    totalMemory = number(fingerprint, "memoryMiB", false);
    JSONArray includes = array(fingerprint, "configIncludes");
    for (Object include : includes) {
      if (!(include instanceof String) || !((String) include).matches("[0-9a-f]{64}")) {
        throw invalid("configIncludes must contain SHA-256 hashes");
      }
    }
    object(fingerprint, "commandSemantics");
    baselineParameters = parameters(object(source, "baselineParameters"), scene);
    candidateParameters = parameters(object(source, "candidateParameters"), scene);
    List<Run> parsed = new ArrayList<>();
    for (Object item : array(source, "runs")) {
      if (!(item instanceof JSONObject)) throw invalid("Each run must be an object");
      JSONObject run = (JSONObject) item;
      if (!"warm".equals(string(run, "phase"))) {
        throw invalid("Only warm measurements can support a recommendation");
      }
      String profile = string(run, "profile");
      if (!profile.equals("baseline") && !profile.equals("candidate")) {
        throw invalid("Run profile must be baseline or candidate");
      }
      double elapsed = number(run, "seconds", false);
      double first = 0;
      List<Integer> visits = new ArrayList<>();
      if (scene == Scene.LIVE) {
        first = number(run, "firstResultSeconds", false);
        if (first > elapsed) throw invalid("First result cannot follow completion");
        if (run.has("rootVisitsByTurn")) throw invalid("Live run cannot contain per-turn visits");
        visits.add(integer(run, "observedRootVisits", true));
      } else {
        if (run.has("firstResultSeconds") || run.has("observedRootVisits")) {
          throw invalid("Whole-game run requires per-turn visits only");
        }
        JSONArray perTurn = array(run, "rootVisitsByTurn");
        if (perTurn.length() != positions) throw invalid("Every position must have a visit count");
        for (Object value : perTurn) visits.add(integer(value, "rootVisitsByTurn", true));
      }
      parsed.add(
          new Run(
              profile,
              integer(run, "round", false),
              elapsed,
              first,
              number(run, "responseSeconds", true),
              number(run, "edtP95Seconds", true),
              number(run, "maxMemoryMiB", false),
              integer(run, "maxEngineProcesses", true),
              List.copyOf(visits)));
    }
    runs = List.copyOf(parsed);
    json = source.toString();
  }

  public static KataGoMeasuredReport parse(JSONObject source) {
    if (source == null) throw invalid("Report is required");
    try {
      return new KataGoMeasuredReport(new JSONObject(source.toString()));
    } catch (IllegalArgumentException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("Malformed measured tuning report", failure);
    }
  }

  public Scene scene() {
    return scene;
  }

  public int budget() {
    return budget;
  }

  public int positions() {
    return positions;
  }

  public String fixtureSha256() {
    return fixtureSha256;
  }

  public Map<String, Integer> baselineParameters() {
    return baselineParameters;
  }

  public Map<String, Integer> candidateParameters() {
    return candidateParameters;
  }

  public JSONObject toJson() {
    return new JSONObject(json);
  }

  public JSONObject fingerprint() {
    return toJson().getJSONObject("fingerprint");
  }

  /** Only parsed numeric tuning parameters can become an overlay, never report command text. */
  public Map<String, String> candidateOverrides() {
    Map<String, String> result = new LinkedHashMap<>();
    candidateParameters.forEach((key, value) -> result.put(key, value.toString()));
    return Collections.unmodifiableMap(result);
  }

  public Assessment assess() {
    List<String> reasons = new ArrayList<>();
    if (baselineParameters.equals(candidateParameters)) {
      reasons.add("Candidate parameters are identical to baseline.");
    }
    if (runs.size() != 6 && runs.size() != 10) {
      reasons.add("Exactly three or five complete warm pairs are required.");
      return new Assessment(false, reasons, 0);
    }
    List<Run> baseline = new ArrayList<>();
    List<Run> candidate = new ArrayList<>();
    List<Double> speedups = new ArrayList<>();
    String previousFirst = null;
    for (int pair = 0; pair < runs.size() / 2; pair++) {
      Run a = runs.get(pair * 2);
      Run b = runs.get(pair * 2 + 1);
      if (a.round != pair + 1
          || b.round != pair + 1
          || a.profile.equals(b.profile)
          || a.profile.equals(previousFirst)) {
        reasons.add(
            "Rounds must be consecutive complete baseline/candidate pairs in alternating order.");
        return new Assessment(false, reasons, 0);
      }
      previousFirst = a.profile;
      Run base = a.profile.equals("baseline") ? a : b;
      Run next = a.profile.equals("candidate") ? a : b;
      baseline.add(base);
      candidate.add(next);
      double speedup = base.seconds / next.seconds;
      if (!Double.isFinite(speedup)) {
        reasons.add("Completion times cannot form a finite speed comparison.");
        return new Assessment(false, reasons, 0);
      }
      speedups.add(speedup);
    }
    if (runs.stream().anyMatch(run -> run.visits.stream().anyMatch(visit -> visit < budget))) {
      reasons.add("Every measured position must complete the unchanged visit budget.");
    }
    if (runs.stream().anyMatch(run -> run.processes != 1)) {
      reasons.add("Every run must record exactly one engine process.");
    }
    if (runs.stream().anyMatch(run -> run.memory > totalMemory)) {
      reasons.add("Recorded GPU memory exceeds physical GPU memory.");
    }
    double speedup = median(speedups);
    if (speedups.stream().anyMatch(value -> value <= 1.0) || speedup < 1.03) {
      reasons.add("Candidate must improve every pair and median speed by at least 3%.");
    }
    double noise = Math.max(coefficientOfVariation(baseline), coefficientOfVariation(candidate));
    if (baseline.size() == 3 && noise > 0.10) {
      reasons.add("Timing variation exceeds 10%; collect five alternating warm pairs.");
    } else if (baseline.size() == 5 && noise > 0.15) {
      reasons.add("Timing variation exceeds 15%; evidence is too noisy.");
    }
    if (scene == Scene.LIVE) latencyGate(baseline, candidate, Run::first, "First-result", reasons);
    latencyGate(baseline, candidate, Run::response, "Pause/cancel response", reasons);
    latencyGate(baseline, candidate, Run::edt, "Event-loop p95", reasons);
    double baseMemory = baseline.stream().mapToDouble(Run::memory).max().orElseThrow();
    double nextMemory = candidate.stream().mapToDouble(Run::memory).max().orElseThrow();
    if (nextMemory > baseMemory * 1.15 + 256 || nextMemory > totalMemory * 0.90) {
      reasons.add(
          "Candidate GPU memory exceeds the regression limit or leaves less than 10% headroom.");
    }
    return new Assessment(reasons.isEmpty(), reasons, speedup);
  }

  private static void latencyGate(
      List<Run> baseline,
      List<Run> candidate,
      ToDoubleFunction<Run> metric,
      String name,
      List<String> reasons) {
    double before = median(baseline.stream().mapToDouble(metric).boxed().toList());
    double after = median(candidate.stream().mapToDouble(metric).boxed().toList());
    if (after > before * 1.10 + 0.005)
      reasons.add(name + " latency regressed beyond 10% plus 5 ms.");
    double beforeTail = baseline.stream().mapToDouble(metric).max().orElseThrow();
    double afterTail = candidate.stream().mapToDouble(metric).max().orElseThrow();
    if (afterTail > beforeTail * 1.25 + 0.020) {
      reasons.add(name + " tail latency regressed beyond 25% plus 20 ms.");
    }
  }

  private static double coefficientOfVariation(List<Run> values) {
    // Scaling first avoids overflow for large but finite input durations.
    double scale = values.stream().mapToDouble(Run::seconds).max().orElseThrow();
    double mean = values.stream().mapToDouble(run -> run.seconds / scale).average().orElseThrow();
    double variance =
        values.stream()
                .mapToDouble(
                    run -> {
                      double delta = run.seconds / scale - mean;
                      return delta * delta;
                    })
                .sum()
            / (values.size() - 1);
    return Math.sqrt(variance) / mean;
  }

  private static double median(List<Double> values) {
    List<Double> ordered = new ArrayList<>(values);
    Collections.sort(ordered);
    return ordered.get(ordered.size() / 2); // accepted evidence always has an odd pair count
  }

  private static Map<String, Integer> parameters(JSONObject json, Scene scene) {
    if (!json.keySet().equals(scene.keys)) throw invalid("Unexpected or missing tuning parameter");
    Map<String, Integer> result = new LinkedHashMap<>();
    for (String key : scene.keys.stream().sorted().toList()) {
      int value = integer(json, key, false);
      int limit = key.equals("nnMaxBatchSize") ? 65536 : 4096;
      if (value > limit) throw invalid("Tuning parameter exceeds supported range: " + key);
      result.put(key, value);
    }
    return Collections.unmodifiableMap(result);
  }

  private static int integer(JSONObject json, String key, boolean zeroAllowed) {
    return integer(json.opt(key), key, zeroAllowed);
  }

  private static int integer(Object value, String key, boolean zeroAllowed) {
    if (!(value instanceof Number)) throw invalid(key + " must be an integer");
    try {
      int result = new BigDecimal(value.toString()).intValueExact();
      if (result < (zeroAllowed ? 0 : 1)) throw invalid(key + " is out of range");
      return result;
    } catch (ArithmeticException | NumberFormatException failure) {
      throw invalid(key + " must be a finite integer in range");
    }
  }

  private static double number(JSONObject json, String key, boolean zeroAllowed) {
    Object raw = json.opt(key);
    if (!(raw instanceof Number)) throw invalid(key + " must be numeric");
    double value = ((Number) raw).doubleValue();
    if (!Double.isFinite(value) || value < 0 || (!zeroAllowed && value == 0)) {
      throw invalid(key + " must be finite and " + (zeroAllowed ? "nonnegative" : "positive"));
    }
    return value;
  }

  private static JSONObject object(JSONObject json, String key) {
    Object value = json.opt(key);
    if (!(value instanceof JSONObject)) throw invalid(key + " must be an object");
    return (JSONObject) value;
  }

  private static JSONArray array(JSONObject json, String key) {
    Object value = json.opt(key);
    if (!(value instanceof JSONArray)) throw invalid(key + " must be an array");
    return (JSONArray) value;
  }

  private static String string(JSONObject json, String key) {
    Object value = json.opt(key);
    if (!(value instanceof String) || ((String) value).isBlank()) {
      throw invalid(key + " must be a nonempty string");
    }
    return (String) value;
  }

  private static String hash(JSONObject json, String key) {
    String value = string(json, key);
    if (!value.matches("[0-9a-f]{64}")) throw invalid(key + " must be a SHA-256 hash");
    return value;
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
