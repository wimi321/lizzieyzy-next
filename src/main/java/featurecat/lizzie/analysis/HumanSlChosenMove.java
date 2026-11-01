package featurecat.lizzie.analysis;

import featurecat.lizzie.training.HumanSlPlaySettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The move KataGo's genmove would choose under a human-imitation search config, worked out from an
 * analysis response searched with that config. The analysis engine never chooses a move, so this
 * follows Search::getPlaySelectionValues and getChosenMoveLoc of KataGo 1.18 with
 * humanSLChosenMoveProp 1:
 *
 * <ol>
 *   <li>every legal move gets its HumanSL odds, multiplied by exp((utility - best utility) /
 *       lambda), the PiKL shift; moves the search did not reach count at the average utility of the
 *       ones it did;
 *   <li>with humanSLChosenMoveIgnorePass, passing keeps the share KataGo's search gives it and the
 *       other moves share the rest;
 *   <li>the temperature, which falls from its early value with the move number, sharpens moves
 *       rarer than chosenMoveTemperatureOnlyBelowProb, and one move is drawn.
 * </ol>
 *
 * <p>Symmetric copies of searched moves, which the analysis engine reports as {@code isSymmetryOf},
 * are left out as genmove leaves them out. A move without visits counts as not searched.
 */
final class HumanSlChosenMove {
  private HumanSlChosenMove() {}

  /** The moves genmove chooses among, and their weights before the temperature. */
  record Weights(List<String> moves, double[] values) {
    double of(String move) {
      int index = moves.indexOf(normalize(move));
      return index < 0 ? 0.0 : values[index];
    }
  }

  /**
   * @param moveInfos the analysis response's moveInfos, utilities from the side to move
   * @param humanMoves the legal moves with their HumanSL odds; a pass only when it is allowed
   * @param randomValue uniform in [0, 1)
   * @return the chosen move, or null when no move has any weight
   */
  static String choose(
      JSONArray moveInfos,
      List<HumanLikeMoveSelector.Candidate> humanMoves,
      HumanSlPlaySettings play,
      int moveNumber,
      int boardWidth,
      int boardHeight,
      boolean allowPass,
      double randomValue) {
    Weights weights = weights(moveInfos, humanMoves, play, allowPass);
    double temperature =
        interpolateEarly(
            moveNumber,
            boardWidth,
            boardHeight,
            play.temperatureHalflife(),
            play.temperatureEarly(),
            play.temperature());
    int index =
        sampleIndex(
            temperatureWeights(weights.values(), temperature, play.temperatureOnlyBelowProb()),
            randomValue);
    return index < 0 ? null : weights.moves().get(index);
  }

  /** Steps 1 and 2: the PiKL-shifted human odds, with the pass at its search share. */
  static Weights weights(
      JSONArray moveInfos,
      List<HumanLikeMoveSelector.Candidate> humanMoves,
      HumanSlPlaySettings play,
      boolean allowPass) {
    Map<String, Double> humanOdds = new HashMap<String, Double>();
    if (humanMoves != null) {
      for (HumanLikeMoveSelector.Candidate move : humanMoves) {
        humanOdds.merge(normalize(move.move), Math.max(0.0, move.probability), Double::sum);
      }
    }

    List<String> moves = new ArrayList<String>();
    List<Double> utilities = new ArrayList<Double>();
    List<Double> selectionValues = new ArrayList<Double>();
    Set<String> symmetryCopies = new HashSet<String>();
    double utilitySum = 0.0;
    double utilityMax = Double.NEGATIVE_INFINITY;
    if (moveInfos != null) {
      for (int i = 0; i < moveInfos.length(); i++) {
        JSONObject info = moveInfos.optJSONObject(i);
        if (info == null) {
          continue;
        }
        String move = normalize(info.optString("move", ""));
        if (move.isEmpty()) {
          continue;
        }
        if (info.has("isSymmetryOf")) {
          symmetryCopies.add(move);
          continue;
        }
        double utility = info.optDouble("utility", Double.NaN);
        if (info.optDouble("visits", 0.0) <= 0.0
            || !Double.isFinite(utility)
            || moves.contains(move)) {
          continue;
        }
        moves.add(move);
        utilities.add(utility);
        selectionValues.add(Math.max(0.0, info.optDouble("playSelectionValue", 0.0)));
        humanOdds.putIfAbsent(move, Math.max(0.0, info.optDouble("humanPrior", 0.0)));
        utilitySum += utility;
        utilityMax = Math.max(utilityMax, utility);
      }
    }
    int searched = moves.size();
    double utilityAverage = searched == 0 ? 0.0 : utilitySum / searched;
    utilityMax = Math.max(utilityMax, utilityAverage);
    if (humanMoves != null) {
      for (HumanLikeMoveSelector.Candidate candidate : humanMoves) {
        String move = normalize(candidate.move);
        if (!symmetryCopies.contains(move) && !moves.contains(move)) {
          moves.add(move);
        }
      }
    }

    double lambda = Math.max(1.0e-9, play.piklLambda());
    double[] values = new double[moves.size()];
    double shiftedSum = 0.0;
    for (int i = 0; i < values.length; i++) {
      String move = moves.get(i);
      if (!allowPass && "pass".equals(move)) {
        continue;
      }
      double utility = i < searched ? utilities.get(i) : utilityAverage;
      values[i] = humanOdds.getOrDefault(move, 0.0) * Math.exp((utility - utilityMax) / lambda);
      shiftedSum += values[i];
    }
    if (!(shiftedSum > 0.0) || !Double.isFinite(shiftedSum)) {
      // No human odds left: KataGo keeps the search's own selection values.
      values = new double[moves.size()];
      for (int i = 0; i < searched; i++) {
        values[i] = allowPass || !"pass".equals(moves.get(i)) ? selectionValues.get(i) : 0.0;
      }
    } else if (play.ignorePass()) {
      keepSearchShareOfPass(moves, values, selectionValues, searched, allowPass);
    }
    return new Weights(moves, values);
  }

  /**
   * humanSLChosenMoveIgnorePass: the pass keeps its share of the search's selection values,
   * whatever its human odds, and the shifted human odds of the other moves fill the rest.
   */
  private static void keepSearchShareOfPass(
      List<String> moves,
      double[] values,
      List<Double> selectionValues,
      int searched,
      boolean allowPass) {
    double selectionSum = 0.0;
    double nonPassSelection = 0.0;
    for (int i = 0; i < searched; i++) {
      selectionSum += selectionValues.get(i);
      if (!"pass".equals(moves.get(i))) {
        nonPassSelection += selectionValues.get(i);
      }
    }
    double nonPassShifted = 0.0;
    for (int i = 0; i < values.length; i++) {
      if (!"pass".equals(moves.get(i))) {
        nonPassShifted += values[i];
      }
    }
    if (!(selectionSum > 0.0) || !(nonPassShifted > 0.0)) {
      return;
    }
    for (int i = 0; i < values.length; i++) {
      if ("pass".equals(moves.get(i))) {
        values[i] = allowPass ? (selectionSum - nonPassSelection) / selectionSum : 0.0;
      } else {
        values[i] = values[i] / nonPassShifted * nonPassSelection / selectionSum;
      }
    }
  }

  /**
   * KataGo's interpolateEarly: the value halves its distance to {@code late} every halflife of
   * moves, counted as on a 19x19 board.
   */
  static double interpolateEarly(
      int moveNumber, int boardWidth, int boardHeight, double halflife, double early, double late) {
    double area = Math.max(1, boardWidth) * (double) Math.max(1, boardHeight);
    double halflives =
        Math.max(0, moveNumber) / Math.max(1.0e-9, halflife) * 19.0 / Math.sqrt(area);
    return late + (early - late) * Math.pow(0.5, halflives);
  }

  /**
   * KataGo's chooseIndexWithTemperature before the draw: weights relative to the largest value,
   * where values below {@code onlyBelowProb} of the total are sharpened by the temperature and
   * larger ones keep their odds. Near zero temperature with no threshold it is the largest value.
   */
  static double[] temperatureWeights(double[] values, double temperature, double onlyBelowProb) {
    double[] weights = new double[values == null ? 0 : values.length];
    double max = 0.0;
    double sum = 0.0;
    int best = -1;
    for (int i = 0; i < weights.length; i++) {
      double value = values[i];
      if (value > 0.0 && Double.isFinite(value)) {
        sum += value;
        if (value > max) {
          max = value;
          best = i;
        }
      }
    }
    if (best < 0 || !Double.isFinite(sum)) {
      return weights;
    }
    if (temperature <= 1.0e-4 && onlyBelowProb >= 1.0) {
      weights[best] = 1.0;
      return weights;
    }
    double threshold =
        Math.min(0.0, Math.log(Math.max(1.0e-50, onlyBelowProb)) + Math.log(sum / max));
    for (int i = 0; i < weights.length; i++) {
      double value = values[i];
      if (!(value > 0.0) || !Double.isFinite(value)) {
        continue;
      }
      double logRelative = Math.log(value / max);
      double damped =
          logRelative > threshold
              ? logRelative
              : (logRelative - threshold) / temperature + threshold;
      weights[i] = Math.exp(damped);
    }
    return weights;
  }

  /** Draws an index by weight; {@code randomValue} is uniform in [0, 1). -1 when nothing weighs. */
  static int sampleIndex(double[] weights, double randomValue) {
    double total = 0.0;
    for (double weight : weights) {
      if (weight > 0.0 && Double.isFinite(weight)) {
        total += weight;
      }
    }
    if (!(total > 0.0) || !Double.isFinite(total)) {
      return -1;
    }
    double target = Math.max(0.0, Math.min(0.999999999999, randomValue)) * total;
    double cumulative = 0.0;
    int last = -1;
    for (int i = 0; i < weights.length; i++) {
      if (!(weights[i] > 0.0) || !Double.isFinite(weights[i])) {
        continue;
      }
      last = i;
      cumulative += weights[i];
      if (target < cumulative) {
        return i;
      }
    }
    return last;
  }

  private static String normalize(String move) {
    if (move == null) {
      return "";
    }
    String trimmed = move.trim();
    return "pass".equalsIgnoreCase(trimmed) ? "pass" : trimmed.toUpperCase(Locale.ROOT);
  }
}
