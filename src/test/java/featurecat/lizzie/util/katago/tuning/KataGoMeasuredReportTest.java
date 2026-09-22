package featurecat.lizzie.util.katago.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class KataGoMeasuredReportTest {
  @Test
  void acceptsRepeatableLiveImprovementWithoutApplyingOrExposingMutableEvidence() {
    JSONObject input = report(false, 3);
    KataGoMeasuredReport parsed = KataGoMeasuredReport.parse(input);
    assertTrue(parsed.assess().eligible());
    assertEquals(1.25, parsed.assess().speedup(), 1e-12);
    assertEquals(KataGoMeasuredReport.Scene.LIVE, parsed.scene());
    assertEquals("live", parsed.scene().id());
    assertEquals(5000, parsed.budget());
    assertEquals(1, parsed.positions());
    assertEquals("d".repeat(64), parsed.fixtureSha256());
    assertEquals("16", parsed.candidateOverrides().get("numSearchThreads"));
    input.getJSONObject("candidateParameters").put("numSearchThreads", 128);
    parsed.toJson().getJSONObject("candidateParameters").put("numSearchThreads", 256);
    parsed.fingerprint().put("engineSha256", "e".repeat(64));
    assertEquals(16, parsed.candidateParameters().get("numSearchThreads"));
    assertEquals("a".repeat(64), parsed.fingerprint().getString("engineSha256"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> parsed.candidateParameters().put("numSearchThreads", 99));
    assertThrows(
        UnsupportedOperationException.class,
        () -> parsed.candidateOverrides().put("maxVisits", "1"));
    assertThrows(
        UnsupportedOperationException.class, () -> parsed.assess().reasons().add("changed"));
  }

  @Test
  void acceptsFiveWholeGamePairsWithEveryPositionAtBudget() {
    KataGoMeasuredReport parsed = KataGoMeasuredReport.parse(report(true, 5));
    assertTrue(parsed.assess().eligible());
    assertEquals(KataGoMeasuredReport.Scene.WHOLE_GAME, parsed.scene());
    assertEquals("whole-game", parsed.scene().id());
    assertEquals(3, parsed.positions());
    assertEquals(3, parsed.candidateOverrides().size());
    assertFalse(parsed.candidateOverrides().containsKey("numSearchThreads"));
  }

  @Test
  void rejectsUnsupportedSchemaScopeAndMalformedFingerprint() {
    for (String key : List.of("schemaVersion", "scene", "metricScope", "measurementMode")) {
      JSONObject input = report(false, 3);
      input.put(key, key.equals("schemaVersion") ? 2 : "unsupported");
      assertThrows(IllegalArgumentException.class, () -> KataGoMeasuredReport.parse(input));
    }
    JSONObject missingIncludes = report(false, 3);
    missingIncludes.getJSONObject("fingerprint").remove("configIncludes");
    assertThrows(IllegalArgumentException.class, () -> KataGoMeasuredReport.parse(missingIncludes));
    JSONObject malformedHash = report(false, 3);
    malformedHash.getJSONObject("fingerprint").put("configSha256", "/untrusted/file.cfg");
    assertThrows(IllegalArgumentException.class, () -> KataGoMeasuredReport.parse(malformedHash));
    JSONObject invalidInclude = report(false, 3);
    invalidInclude.getJSONObject("fingerprint").getJSONArray("configIncludes").put("missing");
    assertThrows(IllegalArgumentException.class, () -> KataGoMeasuredReport.parse(invalidInclude));
  }

  @Test
  void rejectsUnmeasuredQualityChangesAndNonintegerOrOutOfRangeParameters() {
    for (Object value : List.of("16", 1.5, 0, 4097, "16, maxVisits=1")) {
      JSONObject input = report(false, 3);
      input.getJSONObject("candidateParameters").put("numSearchThreads", value);
      assertThrows(IllegalArgumentException.class, () -> KataGoMeasuredReport.parse(input));
    }
    for (String key : List.of("maxVisits", "model", "nnUseFP16", "numAnalysisThreads")) {
      JSONObject input = report(false, 3);
      input.getJSONObject("candidateParameters").put(key, 1);
      assertThrows(IllegalArgumentException.class, () -> KataGoMeasuredReport.parse(input));
    }
    JSONObject missing = report(true, 3);
    missing.getJSONObject("baselineParameters").remove("numSearchThreadsPerAnalysisThread");
    assertThrows(IllegalArgumentException.class, () -> KataGoMeasuredReport.parse(missing));
  }

  @Test
  void rejectsInvalidMetricsWithoutCoercingStringsOrMissingMeasurements() {
    for (Object value :
        List.of("10", "NaN", "Infinity", "-Infinity", -1, 0, new java.math.BigDecimal("1E1000"))) {
      JSONObject input = report(false, 3);
      input.getJSONArray("runs").getJSONObject(0).put("seconds", value);
      assertThrows(IllegalArgumentException.class, () -> KataGoMeasuredReport.parse(input));
    }
    JSONObject missing = report(false, 3);
    missing.getJSONArray("runs").getJSONObject(0).remove("edtP95Seconds");
    assertThrows(IllegalArgumentException.class, () -> KataGoMeasuredReport.parse(missing));
    JSONObject lateFirst = report(false, 3);
    lateFirst.getJSONArray("runs").getJSONObject(0).put("firstResultSeconds", 100);
    assertThrows(IllegalArgumentException.class, () -> KataGoMeasuredReport.parse(lateFirst));
  }

  @Test
  void rejectsColdOrEngineOnlyEvidenceRatherThanTreatingItAsApplicationWarmup() {
    JSONObject engine = report(false, 3).put("measurementMode", "engine");
    assertThrows(IllegalArgumentException.class, () -> KataGoMeasuredReport.parse(engine));
    JSONObject cold = report(false, 3);
    cold.getJSONArray("runs").getJSONObject(0).put("phase", "cold");
    assertThrows(IllegalArgumentException.class, () -> KataGoMeasuredReport.parse(cold));
    JSONObject missing = report(false, 3);
    missing.getJSONArray("runs").getJSONObject(0).remove("phase");
    assertThrows(IllegalArgumentException.class, () -> KataGoMeasuredReport.parse(missing));
  }

  @Test
  void zeroEventOrResponseLatencyIsValidButCannotHideALargeRegression() {
    JSONObject input = report(false, 3);
    for (Object value : input.getJSONArray("runs")) {
      ((JSONObject) value).put("edtP95Seconds", 0).put("responseSeconds", 0);
    }
    assertTrue(KataGoMeasuredReport.parse(input).assess().eligible());
    setCandidateMetric(input, "edtP95Seconds", 0.02);
    rejected(input, "Event-loop");
  }

  @Test
  void rejectsIncompleteDuplicateMissingOrNonalternatingPairs() {
    JSONObject incomplete = report(false, 3);
    incomplete.getJSONArray("runs").remove(5);
    rejected(incomplete, "complete warm pairs");
    JSONObject four = report(false, 4);
    rejected(four, "complete warm pairs");
    JSONObject duplicate = report(false, 3);
    duplicate.getJSONArray("runs").getJSONObject(1).put("profile", "baseline");
    rejected(duplicate, "alternating order");
    JSONObject missingRound = report(false, 3);
    missingRound.getJSONArray("runs").getJSONObject(4).put("round", 4);
    rejected(missingRound, "consecutive");
    JSONObject order = report(false, 3);
    JSONArray rows = order.getJSONArray("runs");
    JSONObject a = rows.getJSONObject(2);
    JSONObject b = rows.getJSONObject(3);
    rows.put(2, b).put(3, a);
    rejected(order, "alternating order");
  }

  @Test
  void eitherFirstProfileIsAllowedWhenAllSubsequentPairsAlternate() {
    JSONObject input = report(false, 3);
    JSONArray rows = input.getJSONArray("runs");
    for (int i = 0; i < rows.length(); i += 2) {
      JSONObject a = rows.getJSONObject(i);
      JSONObject b = rows.getJSONObject(i + 1);
      rows.put(i, b).put(i + 1, a);
    }
    assertTrue(KataGoMeasuredReport.parse(input).assess().eligible());
  }

  @Test
  void rejectsUnderBudgetLiveAndAnyUnderBudgetWholeGamePosition() {
    JSONObject live = report(false, 3);
    live.getJSONArray("runs").getJSONObject(1).put("observedRootVisits", 4999);
    rejected(live, "unchanged visit budget");
    JSONObject game = report(true, 3);
    game.getJSONArray("runs").getJSONObject(1).getJSONArray("rootVisitsByTurn").put(2, 4999);
    rejected(game, "unchanged visit budget");
    JSONObject missing = report(true, 3);
    missing.getJSONArray("runs").getJSONObject(0).getJSONArray("rootVisitsByTurn").remove(2);
    assertThrows(IllegalArgumentException.class, () -> KataGoMeasuredReport.parse(missing));
  }

  @Test
  void rejectsOtherEngineProcessesEvenWhenCandidateIsFaster() {
    JSONObject input = report(false, 3);
    input.getJSONArray("runs").getJSONObject(0).put("maxEngineProcesses", 2);
    rejected(input, "exactly one engine");
    input.getJSONArray("runs").getJSONObject(0).put("maxEngineProcesses", 0);
    rejected(input, "exactly one engine");
  }

  @Test
  void rejectsSmallOrInconsistentGainsAndIdenticalParameters() {
    JSONObject small = report(false, 3);
    setCandidateMetric(small, "seconds", 9.9);
    rejected(small, "at least 3%");
    JSONObject inconsistent = report(false, 3);
    inconsistent.getJSONArray("runs").getJSONObject(1).put("seconds", 10.1);
    rejected(inconsistent, "every pair");
    JSONObject identical = report(false, 3);
    identical.put("candidateParameters", identical.getJSONObject("baselineParameters"));
    rejected(identical, "identical");
  }

  @Test
  void threeNoisyRoundsRequireFiveAndFiveVeryNoisyRoundsAreRejected() {
    JSONObject three = report(false, 3);
    three.getJSONArray("runs").getJSONObject(4).put("seconds", 14);
    three.getJSONArray("runs").getJSONObject(5).put("seconds", 11.2);
    rejected(three, "collect five");
    JSONObject five = report(false, 5);
    five.getJSONArray("runs").getJSONObject(8).put("seconds", 20);
    five.getJSONArray("runs").getJSONObject(9).put("seconds", 16);
    rejected(five, "too noisy");
    JSONObject moderate = report(false, 5);
    moderate.getJSONArray("runs").getJSONObject(8).put("seconds", 13);
    moderate.getJSONArray("runs").getJSONObject(9).put("seconds", 10.4);
    assertTrue(KataGoMeasuredReport.parse(moderate).assess().eligible());
  }

  @Test
  void everyLatencyCategoryCanRejectAnOtherwiseFasterCandidate() {
    for (String metric : List.of("firstResultSeconds", "responseSeconds", "edtP95Seconds")) {
      JSONObject input = report(false, 3);
      setCandidateMetric(input, metric, 1.0);
      rejected(input, "latency regressed");
    }
    JSONObject whole = report(true, 3);
    setCandidateMetric(whole, "responseSeconds", 1.0);
    rejected(whole, "Pause/cancel");
  }

  @Test
  void singleRoundLatencySpikeCannotHideBehindAnUnchangedMedian() {
    for (String metric : List.of("firstResultSeconds", "responseSeconds", "edtP95Seconds")) {
      JSONObject input = report(false, 3);
      input.getJSONArray("runs").getJSONObject(1).put(metric, 1.0);
      rejected(input, "tail latency");
    }
    JSONObject whole = report(true, 5);
    whole.getJSONArray("runs").getJSONObject(1).put("responseSeconds", 1.0);
    rejected(whole, "tail latency");
  }

  @Test
  void peakMemoryNotMedianMemoryControlsRegressionAndHeadroom() {
    JSONObject peak = report(false, 3);
    peak.getJSONArray("runs").getJSONObject(1).put("maxMemoryMiB", 6000);
    rejected(peak, "GPU memory");
    JSONObject capacity = report(false, 3);
    for (Object value : capacity.getJSONArray("runs")) {
      ((JSONObject) value).put("maxMemoryMiB", 30000);
    }
    rejected(capacity, "headroom");
    capacity.getJSONArray("runs").getJSONObject(0).put("maxMemoryMiB", 40000);
    rejected(capacity, "physical GPU memory");
  }

  @Test
  void hugeFiniteDurationsDoNotOverflowNoiseMathAndInfiniteRatiosAreRejected() {
    JSONObject input = report(false, 3);
    for (Object value : input.getJSONArray("runs")) {
      JSONObject row = (JSONObject) value;
      row.put("seconds", row.getString("profile").equals("baseline") ? 1e300 : 8e299);
    }
    assertTrue(KataGoMeasuredReport.parse(input).assess().eligible());
    input
        .getJSONArray("runs")
        .getJSONObject(1)
        .put("seconds", 1e-300)
        .put("firstResultSeconds", 1e-301);
    rejected(input, "finite speed comparison");
  }

  private static void rejected(JSONObject input, String reason) {
    var assessment = KataGoMeasuredReport.parse(input).assess();
    assertFalse(assessment.eligible());
    assertTrue(
        assessment.reasons().stream().anyMatch(value -> value.contains(reason)),
        assessment.reasons().toString());
  }

  private static void setCandidateMetric(JSONObject input, String key, double value) {
    for (Object item : input.getJSONArray("runs")) {
      JSONObject row = (JSONObject) item;
      if (row.getString("profile").equals("candidate")) row.put(key, value);
    }
  }

  private static JSONObject report(boolean whole, int rounds) {
    JSONObject fingerprint =
        new JSONObject()
            .put("engineSha256", "a".repeat(64))
            .put("modelSha256", "b".repeat(64))
            .put("configSha256", "c".repeat(64))
            .put("configIncludes", new JSONArray())
            .put("gpuName", "NVIDIA GeForce RTX 5090")
            .put("driverVersion", "591.86")
            .put("memoryMiB", 32607)
            .put("commandSemantics", new JSONObject());
    JSONObject baseline =
        whole
            ? new JSONObject()
                .put("numAnalysisThreads", 8)
                .put("numSearchThreadsPerAnalysisThread", 2)
            : new JSONObject().put("numSearchThreads", 8);
    JSONObject candidate = new JSONObject(baseline.toString());
    candidate.put(whole ? "numAnalysisThreads" : "numSearchThreads", 16);
    baseline.put("nnMaxBatchSize", 16);
    candidate.put("nnMaxBatchSize", 32);
    JSONArray runs = new JSONArray();
    for (int round = 1; round <= rounds; round++) {
      for (String profile :
          round % 2 == 1 ? List.of("baseline", "candidate") : List.of("candidate", "baseline")) {
        boolean base = profile.equals("baseline");
        JSONObject row =
            new JSONObject()
                .put("round", round)
                .put("profile", profile)
                .put("phase", "warm")
                .put("seconds", base ? 10.0 : 8.0)
                .put("responseSeconds", 0.08)
                .put("edtP95Seconds", 0.01)
                .put("maxMemoryMiB", base ? 4000 : 4200)
                .put("maxEngineProcesses", 1);
        if (whole) row.put("rootVisitsByTurn", new JSONArray(List.of(5000, 5001, 5002)));
        else row.put("observedRootVisits", 5010).put("firstResultSeconds", 0.2);
        runs.put(row);
      }
    }
    return new JSONObject()
        .put("schemaVersion", 1)
        .put("measurementMode", "application")
        .put("scene", whole ? "whole-game" : "live")
        .put("fingerprint", fingerprint)
        .put("fixtureSha256", "d".repeat(64))
        .put("budget", 5000)
        .put("positions", whole ? 3 : 1)
        .put("metricScope", "totalGpu")
        .put("baselineParameters", baseline)
        .put("candidateParameters", candidate)
        .put("runs", runs);
  }
}
