package featurecat.lizzie.teacher.analysis;
import java.util.*;

/**
 * GoAgent classifier.ts / pvConfidence.ts 的 Java 移植。 从 KataGo 分析（本仓库 BoardData + topMoves）派生每手的： -
 * 失误分类（severity/confidence/phase/shouldTeach/shouldDeepen） - 变化图可信度（PV confidence report） 对齐
 * GoAgent 的 classifyMoveAnalysis / buildPvConfidenceReport。
 */
public final class AnalysisBrain {

  private AnalysisBrain() {}

  // ---- 类型 ----
  public enum Severity {
    GOOD,
    INACCURACY,
    MISTAKE,
    BLUNDER,
    UNCLEAR
  }

  public enum Confidence {
    LOW,
    MEDIUM,
    HIGH
  }

  public enum Phase {
    OPENING,
    MIDDLE,
    ENDGAME
  }

  public enum PvLevel {
    UNSTABLE,
    WEAK,
    MEDIUM,
    STRONG
  }

  public static class MoveClassification {
    public int moveNumber;   // 真实手数（analyze 时设置，供关键手列表对齐）
    public Severity severity;
    public Confidence confidence;
    public Phase phase;
    public double winrateLoss;
    public double scoreLoss;
    public boolean shouldTeach;
    public boolean shouldDeepen;
    public String reason;
    public java.util.List<String> evidenceWarnings = new java.util.ArrayList<>();
  }

  public static class PvCandidate {
    public String move;
    public int rank;
    public PvLevel level;
    public int visits;
    public int pvLength;
    public Integer pvVisitsTotal;
    public String reason;
    public Double winrate;        // 落子方胜率(%)
    public Double scoreLead;      // 落子方目差(black-positive)
    public Double prior;          // KataGo prior 概率(%)，无则 null
    public Double humanPrior;     // 人类策略 prior
    public Double humanPolicy;    // 人类策略 policy
    public int edgeVisits;
    public double[] ownership;
    public Double scoreStdev;
    public Double utility;
    public Double lcb;
    public List<Double> pvVisits = new ArrayList<>();
    public List<String> pv = new ArrayList<>();
  }

  public static class PvReport {
    public PvLevel overall;
    public boolean stableMainLine;
    public boolean shouldDeepen;
    public String summary;
    public String recommendedWording;
    public java.util.List<PvCandidate> candidates = new java.util.ArrayList<>();
  }

  // ---- classifier.ts ----

  private static double round(Double v, int digits) {
    if (v == null || !Double.isFinite(v)) return 0;
    double f = Math.pow(10, digits);
    return Math.round(v * f) / f;
  }

  private static Phase defaultPhase(int moveNumber, Phase qualityPhase) {
    if (qualityPhase != null) return qualityPhase;
    return moveNumber <= 50 ? Phase.OPENING : moveNumber <= 160 ? Phase.MIDDLE : Phase.ENDGAME;
  }

  private static Severity phaseSeverity(Phase phase, double winrateLoss, double scoreLoss) {
    if (phase == Phase.ENDGAME) {
      if (scoreLoss >= 6 || winrateLoss >= 18) return Severity.BLUNDER;
      if (scoreLoss >= 3 || winrateLoss >= 9) return Severity.MISTAKE;
      if (scoreLoss >= 1.2 || winrateLoss >= 3) return Severity.INACCURACY;
      return Severity.GOOD;
    }
    if (phase == Phase.OPENING) {
      if (winrateLoss >= 15 || scoreLoss >= 6) return Severity.BLUNDER;
      if (winrateLoss >= 7 || scoreLoss >= 3.5) return Severity.MISTAKE;
      if (winrateLoss >= 2.5 || scoreLoss >= 1.8) return Severity.INACCURACY;
      return Severity.GOOD;
    }
    // middle
    if (winrateLoss >= 15 || scoreLoss >= 6) return Severity.BLUNDER;
    if (winrateLoss >= 7 || scoreLoss >= 3) return Severity.MISTAKE;
    if (winrateLoss >= 2.5 || scoreLoss >= 1.5) return Severity.INACCURACY;
    return Severity.GOOD;
  }

  private static int rank(Confidence c) {
    return c == Confidence.LOW ? 1 : c == Confidence.MEDIUM ? 2 : 3;
  }

  private static Confidence capConfidence(Confidence v, Confidence cap) {
    return rank(v) > rank(cap) ? cap : v;
  }

  private static Confidence confidenceFromEvidence(
      Confidence qualityConfidence,
      Severity severity,
      int actualVisits,
      int bestVisits,
      boolean deepenRecommended) {
    Confidence confidence = qualityConfidence == null ? Confidence.MEDIUM : qualityConfidence;
    if (severity == Severity.GOOD && bestVisits < 160)
      confidence = capConfidence(confidence, Confidence.MEDIUM);
    if (actualVisits > 0 && actualVisits < 80)
      confidence = capConfidence(confidence, Confidence.MEDIUM);
    if (bestVisits < 80) confidence = capConfidence(confidence, Confidence.LOW);
    if (deepenRecommended)
      confidence =
          capConfidence(
              confidence,
              qualityConfidence == Confidence.HIGH ? Confidence.MEDIUM : qualityConfidence);
    return confidence;
  }

  private static boolean shouldTeach(
      Severity severity, Confidence confidence, double winrateLoss, double scoreLoss) {
    if (severity == Severity.BLUNDER || severity == Severity.MISTAKE) return true;
    if (severity == Severity.INACCURACY && confidence != Confidence.LOW) return true;
    return winrateLoss >= 4 || scoreLoss >= 2;
  }

  /** 输入：当前手分析。bestWinrate/scoreLead/visits 来自 AI 首选；actualWinrate/scoreLead/visits 来自实战手。 */
  public static MoveClassification classify(
      int moveNumber,
      Double actualWinrate,
      Double actualScoreLead,
      Integer actualVisits,
      Double bestWinrate,
      Double bestScoreLead,
      Integer bestVisits,
      Phase qualityPhase,
      Confidence qualityConfidence,
      boolean deepenRecommended) {

    Phase phase = defaultPhase(moveNumber, qualityPhase);
    double winrateLoss =
        round(
            bestWinrate == null ? 0 : bestWinrate - (actualWinrate == null ? 0 : actualWinrate), 2);
    double scoreLoss =
        round(
            bestScoreLead == null
                ? 0
                : bestScoreLead - (actualScoreLead == null ? 0 : actualScoreLead),
            2);
    int bestV = Math.max(0, bestVisits == null ? 0 : bestVisits);
    int actualV = Math.max(0, actualVisits == null ? 0 : actualVisits);
    boolean unclear = (actualWinrate == null || bestWinrate == null);

    MoveClassification mc = new MoveClassification();
    if (unclear) {
      mc.severity = Severity.UNCLEAR;
      mc.confidence = Confidence.LOW;
      mc.phase = phase;
      mc.winrateLoss = 0;
      mc.scoreLoss = 0;
      mc.shouldTeach = false;
      mc.shouldDeepen = true;
      mc.reason = "No played move evidence is available for this position.";
      mc.evidenceWarnings.add("missing-played-move-evidence");
      return mc;
    }

    Severity severity = phaseSeverity(phase, winrateLoss, scoreLoss);
    Confidence confidence =
        confidenceFromEvidence(qualityConfidence, severity, actualV, bestV, deepenRecommended);
    boolean shouldDeepen =
        deepenRecommended
            || (severity != Severity.GOOD && confidence == Confidence.LOW)
            || (winrateLoss >= 4 && actualV < 120)
            || bestV < 120;

    if (actualV > 0 && actualV < 80) mc.evidenceWarnings.add("actual-move-low-visits");
    if (bestV < 120) mc.evidenceWarnings.add("best-move-low-visits");
    if (deepenRecommended) mc.evidenceWarnings.add("analysis-quality-recommends-deeper-search");

    mc.severity = severity;
    mc.confidence = confidence;
    mc.phase = phase;
    mc.winrateLoss = winrateLoss;
    mc.scoreLoss = scoreLoss;
    mc.shouldTeach = shouldTeach(severity, confidence, winrateLoss, scoreLoss);
    mc.shouldDeepen = shouldDeepen;
    mc.reason =
        String.format(
            "phase=%s, severity=%s, confidence=%s, winrateLoss=%.2f%%, scoreLoss=%.2f, bestVisits=%d, actualVisits=%d.",
            phase, severity, confidence, winrateLoss, scoreLoss, bestV, actualV);
    return mc;
  }

  // ---- pvConfidence.ts ----

  private static int rank(PvLevel l) {
    return l == PvLevel.UNSTABLE ? 0 : l == PvLevel.WEAK ? 1 : l == PvLevel.MEDIUM ? 2 : 3;
  }

  private static PvLevel weakest(PvLevel a, PvLevel b) {
    return rank(a) <= rank(b) ? a : b;
  }

  private static Integer pvVisitsTotal(int[] pvVisits) {
    if (pvVisits == null || pvVisits.length == 0) return null;
    int s = 0;
    for (int v : pvVisits) s += Math.max(0, v);
    return s;
  }

  private static PvCandidate candidateLevel(
      String move,
      int rank,
      int bestVisits,
      int visits,
      int pvLength,
      int[] pvVisits,
      boolean unstableRoot) {
    PvCandidate c = new PvCandidate();
    c.move = move;
    c.rank = rank;
    c.visits = visits;
    c.pvLength = pvLength;
    c.pvVisitsTotal = pvVisitsTotal(pvVisits);
    PvLevel level = PvLevel.WEAK;
    java.util.List<String> reasons = new java.util.ArrayList<>();
    if (unstableRoot) {
      level = PvLevel.UNSTABLE;
      reasons.add("root-analysis-unstable");
    } else if (visits >= 700 && pvLength >= 6) {
      level = PvLevel.STRONG;
      reasons.add("high-visits-and-long-pv");
    } else if (visits >= 220 && pvLength >= 4) {
      level = PvLevel.MEDIUM;
      reasons.add("moderate-visits-and-usable-pv");
    } else if (visits < 80 || pvLength < 3) {
      level = PvLevel.WEAK;
      reasons.add("low-visits-or-short-pv");
    }
    if (rank > 1 && bestVisits > 0 && visits / (double) bestVisits < 0.18) {
      level = weakest(level, PvLevel.WEAK);
      reasons.add("candidate-far-below-best-visits");
    }
    if (c.pvVisitsTotal != null && c.pvVisitsTotal < Math.max(40, visits * 0.15)) {
      level = weakest(level, PvLevel.MEDIUM);
      reasons.add("pv-visits-thin");
    }
    c.level = level;
    c.reason =
        String.join("; ", reasons)
            + (reasons.isEmpty() ? "pv evidence is usable but not deeply qualified" : "");
    return c;
  }

  private static String wording(PvLevel level) {
    if (level == PvLevel.STRONG) return "这条变化比较稳定，可以作为主线理解。";
    if (level == PvLevel.MEDIUM) return "AI 倾向这条变化，教学上可以参考，但不要说成唯一结果。";
    if (level == PvLevel.WEAK) return "这只是参考变化，适合说明方向，不宜讲成必然。";
    return "当前搜索不够稳定，建议加深分析后再下定论。";
  }

  /** 输入：AI 首选候选列表（move/visits/pv/pvVisits）。 */
  public static PvReport buildPvReport(
      java.util.List<KataGoCandidate> topMoves,
      boolean deepenRecommended,
      Confidence qualityConfidence) {
    PvReport report = new PvReport();
    int bestVisits = topMoves.isEmpty() ? 0 : Math.max(0, topMoves.get(0).visits);
    boolean unstableRoot = deepenRecommended && qualityConfidence == Confidence.LOW;
    for (int i = 0; i < Math.min(5, topMoves.size()); i++) {
      KataGoCandidate c = topMoves.get(i);
      PvCandidate pc = candidateLevel(
              c.move,
              i + 1,
              bestVisits,
              c.visits,
              c.pv == null ? 0 : c.pv.length,
              c.pvVisits,
              unstableRoot);
      pc.winrate = c.winrate;
      pc.scoreLead = c.scoreLead;
      pc.prior = c.prior;
      pc.humanPrior = c.humanPrior;
      pc.humanPolicy = c.humanPolicy;
      pc.edgeVisits = c.edgeVisits;
      pc.ownership = c.ownership;
      if (c.pv != null) pc.pv = new ArrayList<>(java.util.Arrays.asList(c.pv));
      if (c.pvVisits != null) { for (int v : c.pvVisits) pc.pvVisits.add((double) v); pc.pvVisitsTotal = pc.pvVisits.stream().mapToInt(Double::intValue).sum(); }
      report.candidates.add(pc);
    }
    if (report.candidates.isEmpty()) {
      report.overall = PvLevel.UNSTABLE;
      report.stableMainLine = false;
      report.shouldDeepen = true;
      report.summary = "PV confidence=unstable; no candidate PV is available.";
      report.recommendedWording = wording(PvLevel.UNSTABLE);
      return report;
    }
    PvLevel overall = PvLevel.UNSTABLE;
    for (PvCandidate c : report.candidates) {
      if (overall == PvLevel.STRONG) overall = c.level;
      else if (c.level == PvLevel.UNSTABLE) overall = PvLevel.UNSTABLE;
      else overall = rank(c.level) > rank(overall) ? c.level : overall;
    }
    report.overall = overall;
    report.stableMainLine = overall == PvLevel.STRONG || overall == PvLevel.MEDIUM;
    report.shouldDeepen =
        overall == PvLevel.WEAK || overall == PvLevel.UNSTABLE || deepenRecommended;
    report.summary =
        "PV confidence="
            + overall
            + "; best="
            + report.candidates.get(0).move
            + "; candidates="
            + report.candidates.stream()
                .map(c -> c.move + ":" + c.level)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    report.recommendedWording = wording(overall);
    return report;
  }

  /** KataGo 候选（对应 GoAgent KataGoCandidate） */
  public static class KataGoCandidate {
    public String move;
    public int visits;
    public String[] pv;
    public int[] pvVisits;
    public double winrate;
    public double scoreLead;
    public Double prior;        // KataGo prior（来自 MoveData.policy）
    public Double humanPrior;     // 人类策略 prior（来自 MoveData.humanPrior）
    public Double humanPolicy;    // 人类策略 policy（来自 MoveData.humanPolicy）
    public double[] ownership;  // KataGo ownership 数组（lizzieyzy 已解析）
    public int edgeVisits;

    public double winrateOrZero() {
      return winrate;
    }

    public double scoreLeadOrZero() {
      return scoreLead;
    }
  }
}
