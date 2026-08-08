package featurecat.lizzie.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Selects a plausible HumanSL move without retaining state between calls. */
final class HumanLikeMoveSelector {
  static final int MAX_CANDIDATES = 12;
  static final double CANDIDATE_POLICY_MASS = 0.95;

  private static final double MIN_RELATIVE_POLICY = 0.01;
  private static final double MAX_QUALITY_SCALE_LOSS = 4.0;
  private static final int OPENING_END_MOVE = 60;
  private static final int MIDDLEGAME_END_MOVE = 160;
  private static final double OPENING_TEMPERATURE = 1.25;
  private static final double MIDDLEGAME_TEMPERATURE = 1.05;
  private static final double ENDGAME_TEMPERATURE = 0.90;

  private HumanLikeMoveSelector() {}

  static String select(
      List<Candidate> legalMoves,
      JSONArray moveInfos,
      int moveNumber,
      String profile,
      double randomValue) {
    List<Candidate> pool = candidatePool(legalMoves);
    if (pool.isEmpty()) {
      return null;
    }
    if (pool.size() == 1) {
      return pool.get(0).move;
    }

    QualityTable quality = QualityTable.from(moveInfos);
    double qualityScale = qualityScale(profile, quality.kind);
    double temperature = temperatureForMove(moveNumber);
    ArrayList<WeightedMove> weighted = new ArrayList<WeightedMove>(pool.size());
    for (Candidate candidate : pool) {
      double qualityFactor = quality.factor(candidate.move, qualityScale);
      if (qualityFactor <= 0.0) {
        continue;
      }
      double policyWeight = Math.pow(candidate.probability, 1.0 / temperature);
      double weight = policyWeight * qualityFactor;
      if (Double.isFinite(weight) && weight > 0.0) {
        weighted.add(new WeightedMove(candidate.move, weight));
      }
    }

    if (weighted.isEmpty()) {
      return pool.get(0).move;
    }
    return sampleWeighted(weighted, randomValue);
  }

  static List<Candidate> candidatePool(List<Candidate> legalMoves) {
    ArrayList<Candidate> sorted = new ArrayList<Candidate>();
    if (legalMoves != null) {
      for (Candidate candidate : legalMoves) {
        if (candidate != null
            && candidate.move != null
            && !candidate.move.trim().isEmpty()
            && Double.isFinite(candidate.probability)
            && candidate.probability > 0.0) {
          sorted.add(candidate);
        }
      }
    }
    sorted.sort(Comparator.comparingDouble((Candidate move) -> move.probability).reversed());
    if (sorted.isEmpty()) {
      return sorted;
    }

    double total = 0.0;
    for (Candidate candidate : sorted) {
      total += candidate.probability;
    }
    if (!(total > 0.0) || !Double.isFinite(total)) {
      return List.of(sorted.get(0));
    }

    double topProbability = sorted.get(0).probability;
    double cumulative = 0.0;
    ArrayList<Candidate> pool = new ArrayList<Candidate>(MAX_CANDIDATES);
    for (Candidate candidate : sorted) {
      if (pool.size() >= MAX_CANDIDATES) {
        break;
      }
      if (!pool.isEmpty() && candidate.probability < topProbability * MIN_RELATIVE_POLICY) {
        break;
      }
      pool.add(candidate);
      cumulative += candidate.probability;
      if (cumulative / total >= CANDIDATE_POLICY_MASS) {
        break;
      }
    }
    return pool;
  }

  static double temperatureForMove(int moveNumber) {
    if (moveNumber <= OPENING_END_MOVE) {
      return OPENING_TEMPERATURE;
    }
    if (moveNumber <= MIDDLEGAME_END_MOVE) {
      return MIDDLEGAME_TEMPERATURE;
    }
    return ENDGAME_TEMPERATURE;
  }

  private static String sampleWeighted(List<WeightedMove> moves, double randomValue) {
    double total = 0.0;
    for (WeightedMove move : moves) {
      total += move.weight;
    }
    if (!(total > 0.0) || !Double.isFinite(total)) {
      return moves.get(0).move;
    }

    double boundedRandom = Math.max(0.0, Math.min(0.999999999999, randomValue));
    double target = boundedRandom * total;
    double cumulative = 0.0;
    for (WeightedMove move : moves) {
      cumulative += move.weight;
      if (target < cumulative) {
        return move.move;
      }
    }
    return moves.get(moves.size() - 1).move;
  }

  private static double qualityScale(String profile, QualityKind kind) {
    double rankMultiplier = rankQualityMultiplier(profile);
    switch (kind) {
      case UTILITY:
        return 0.50 * rankMultiplier;
      case SCORE:
        return 4.0 * rankMultiplier;
      case WINRATE:
        return 0.12 * rankMultiplier;
      case NONE:
      default:
        return 1.0;
    }
  }

  private static double rankQualityMultiplier(String profile) {
    if (profile == null) {
      return 1.0;
    }
    String normalized = profile.trim().toLowerCase(Locale.ROOT);
    int rank = parseRankNumber(normalized);
    if (normalized.endsWith("d") || normalized.startsWith("proyear_")) {
      return 0.70;
    }
    if (rank <= 5) {
      return 0.90;
    }
    if (rank <= 10) {
      return 1.15;
    }
    return 1.45;
  }

  private static int parseRankNumber(String profile) {
    int underscore = profile.lastIndexOf('_');
    int suffix = profile.length() - 1;
    if (underscore < 0 || suffix <= underscore) {
      return 10;
    }
    try {
      return Integer.parseInt(profile.substring(underscore + 1, suffix));
    } catch (NumberFormatException ignored) {
      return 10;
    }
  }

  static final class Candidate {
    final String move;
    final double probability;

    Candidate(String move, double probability) {
      this.move = move;
      this.probability = probability;
    }
  }

  private static final class WeightedMove {
    private final String move;
    private final double weight;

    private WeightedMove(String move, double weight) {
      this.move = move;
      this.weight = weight;
    }
  }

  private enum QualityKind {
    UTILITY,
    SCORE,
    WINRATE,
    NONE
  }

  private static final class QualityTable {
    private final QualityKind kind;
    private final Map<String, Double> values;
    private final double best;

    private QualityTable(QualityKind kind, Map<String, Double> values, double best) {
      this.kind = kind;
      this.values = values;
      this.best = best;
    }

    private double factor(String move, double scale) {
      if (kind == QualityKind.NONE || values.isEmpty()) {
        return 1.0;
      }
      Double value = values.get(normalizeMove(move));
      if (value == null) {
        return 1.0;
      }
      double loss = Math.max(0.0, best - value.doubleValue());
      if (loss > scale * MAX_QUALITY_SCALE_LOSS) {
        return 0.0;
      }
      return Math.exp(-loss / Math.max(1.0e-9, scale * MAX_QUALITY_SCALE_LOSS));
    }

    private static QualityTable from(JSONArray moveInfos) {
      QualityTable utility = collect(moveInfos, QualityKind.UTILITY);
      if (!utility.values.isEmpty()) {
        return utility;
      }
      QualityTable score = collect(moveInfos, QualityKind.SCORE);
      if (!score.values.isEmpty()) {
        return score;
      }
      QualityTable winrate = collect(moveInfos, QualityKind.WINRATE);
      if (!winrate.values.isEmpty()) {
        return winrate;
      }
      return new QualityTable(QualityKind.NONE, Map.of(), 0.0);
    }

    private static QualityTable collect(JSONArray moveInfos, QualityKind kind) {
      HashMap<String, Double> values = new HashMap<String, Double>();
      double best = Double.NEGATIVE_INFINITY;
      if (moveInfos != null) {
        for (int i = 0; i < moveInfos.length(); i++) {
          JSONObject moveInfo = moveInfos.optJSONObject(i);
          if (moveInfo == null) {
            continue;
          }
          String move = normalizeMove(moveInfo.optString("move", ""));
          Double value = qualityValue(moveInfo, kind);
          if (move.isEmpty() || value == null || !Double.isFinite(value.doubleValue())) {
            continue;
          }
          values.put(move, value);
          best = Math.max(best, value.doubleValue());
        }
      }
      return new QualityTable(kind, values, best);
    }

    private static Double qualityValue(JSONObject moveInfo, QualityKind kind) {
      switch (kind) {
        case UTILITY:
          return number(moveInfo, "utility");
        case SCORE:
          Double scoreLead = number(moveInfo, "scoreLead");
          return scoreLead != null ? scoreLead : number(moveInfo, "scoreMean");
        case WINRATE:
          Double winrate = number(moveInfo, "winrate");
          if (winrate != null && winrate.doubleValue() > 1.0) {
            return winrate.doubleValue() / 100.0;
          }
          return winrate;
        case NONE:
        default:
          return null;
      }
    }

    private static Double number(JSONObject object, String key) {
      if (!object.has(key) || !(object.opt(key) instanceof Number)) {
        return null;
      }
      return ((Number) object.opt(key)).doubleValue();
    }
  }

  private static String normalizeMove(String move) {
    if (move == null) {
      return "";
    }
    String normalized = move.trim();
    return "pass".equalsIgnoreCase(normalized) ? "pass" : normalized.toUpperCase(Locale.ROOT);
  }
}
