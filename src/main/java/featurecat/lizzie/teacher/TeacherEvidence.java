package featurecat.lizzie.teacher;

import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.teacher.analysis.AnalysisBrain;
import featurecat.lizzie.teacher.analysis.ScorePerspective;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

/** Immutable KataGo evidence copied from the SGF tree before an AI request starts. */
public final class TeacherEvidence {
  static final int MAX_CANDIDATES = 3;
  static final int MAX_PV_MOVES = 12;
  static final int MAX_RANGE_POSITIONS = 5;

  private TeacherEvidence() {}

  public static Optional<Position> current(BoardHistoryNode node) {
    return position(node).map(p -> p.withBoard(TeacherBoardContext.capture(node, p)));
  }

  public static Range mainLine(BoardHistoryNode root, int firstMove, int lastMove) {
    if (root == null) {
      return new Range(Collections.emptyList(), 0, 0);
    }
    int normalizedFirst = Math.max(1, firstMove);
    int normalizedLast = Math.max(normalizedFirst, lastMove);
    ArrayList<Position> available = new ArrayList<>();
    java.util.Map<Position, BoardHistoryNode> sources = new java.util.IdentityHashMap<>();
    BoardHistoryNode parent = root;
    while (parent != null && parent.next().isPresent()) {
      BoardHistoryNode child = parent.next().get();
      int moveNumber = child.getData().moveNumber;
      if (moveNumber >= normalizedFirst && moveNumber <= normalizedLast) {
        Optional<Position> evidence = position(parent);
        if (evidence.isPresent()) {
          available.add(evidence.get());
          sources.put(evidence.get(), parent);
        }
      }
      if (moveNumber > normalizedLast) {
        break;
      }
      parent = child;
    }
    List<Position> selected = selectKeyPositions(available, MAX_RANGE_POSITIONS);
    // Only freeze/replay the selected teaching moments, not every move in a long SGF.
    ArrayList<Position> grounded = new ArrayList<>();
    for (Position next : selected)
      grounded.add(next.withBoard(TeacherBoardContext.capture(sources.get(next), next)));
    return new Range(grounded, available.size(), Math.max(0, available.size() - grounded.size()));
  }

  public static Range wholeGame(BoardHistoryNode root) {
    return mainLine(root, 1, Integer.MAX_VALUE);
  }

  static Optional<Position> position(BoardHistoryNode parent) {
    if (parent == null || parent.getData() == null) {
      return Optional.empty();
    }
    BoardData data = parent.getData();
    List<MoveData> moves = stableCopy(data.bestMoves);
    if (moves.isEmpty()) {
      return Optional.empty();
    }

    ArrayList<Candidate> candidates = new ArrayList<>();
    for (MoveData move : moves) {
      Candidate candidate = candidate(candidates.size() + 1, move);
      if (candidate != null) {
        candidates.add(candidate);
      }
      if (candidates.size() >= MAX_CANDIDATES) {
        break;
      }
    }
    if (candidates.isEmpty()) {
      return Optional.empty();
    }

    String actualMove = actualMove(parent.next().orElse(null));
    if (!actualMove.isEmpty()
        && candidates.stream().noneMatch(c -> c.coordinate.equals(actualMove))) {
      for (int i = 0; i < moves.size(); i++) {
        MoveData move = moves.get(i);
        if (move != null && actualMove.equals(normalizeCoordinate(move.coordinate))) {
          Candidate actual = candidate(i + 1, move);
          if (actual != null) candidates.add(actual);
          break;
        }
      }
    }
    OptionalDouble actualLoss = OptionalDouble.empty();
    if (!actualMove.isEmpty()) {
      Candidate best = candidates.get(0);
      for (MoveData move : moves) {
        if (move != null
            && actualMove.equalsIgnoreCase(normalizeCoordinate(move.coordinate))
            && Double.isFinite(move.winrate)
            && Double.isFinite(best.winrate)) {
          actualLoss = OptionalDouble.of(Math.max(0.0, best.winrate - move.winrate));
          break;
        }
      }
    }

    ArrayList<String> continuation = new ArrayList<>();
    BoardHistoryNode walk = parent.next().orElse(null);
    boolean black = data.blackToPlay;
    while (walk != null && continuation.size() < 5) {
      String move = actualMove(walk);
      if (move.isEmpty() || walk.getData().lastMoveColor != (black ? Stone.BLACK : Stone.WHITE))
        break;
      continuation.add(move);
      black = !black;
      walk = walk.next().orElse(null);
    }

    return Optional.of(
        new Position(
            data.moveNumber,
            data.blackToPlay ? "B" : "W",
            data.getPlayouts(),
            actualMove,
            actualLoss,
            candidates,
            continuation));
  }

  private static Candidate candidate(int rank, MoveData move) {
    if (move == null || normalizeCoordinate(move.coordinate).isEmpty()) {
      return null;
    }
    ArrayList<String> variation = new ArrayList<>();
    if (move.variation != null) {
      for (String coordinate : move.variation) {
        String normalized = normalizeCoordinate(coordinate);
        if (!normalized.isEmpty()) {
          variation.add(normalized);
        }
        if (variation.size() >= MAX_PV_MOVES) {
          break;
        }
      }
    }
    return new Candidate(
        rank,
        normalizeCoordinate(move.coordinate),
        finiteOrNaN(move.winrate),
        finiteOrNaN(move.scoreMean),
        Math.max(0, move.playouts),
        variation);
  }

  private static String actualMove(BoardHistoryNode child) {
    if (child == null || child.getData() == null) {
      return "";
    }
    BoardData data = child.getData();
    if (data.isPassNode()) {
      return "pass";
    }
    if (!data.isMoveNode() || data.lastMove.isEmpty()) {
      return "";
    }
    int[] move = data.lastMove.get();
    return normalizeCoordinate(Board.convertCoordinatesToName(move[0], move[1]));
  }

  private static List<MoveData> stableCopy(List<MoveData> source) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyList();
    }
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return new ArrayList<>(source);
      } catch (RuntimeException concurrentUpdate) {
        Thread.yield();
      }
    }
    return Collections.emptyList();
  }

  private static List<Position> selectKeyPositions(List<Position> positions, int limit) {
    if (positions.size() <= limit) {
      return Collections.unmodifiableList(new ArrayList<>(positions));
    }

    ArrayList<Position> ranked = new ArrayList<>(positions);
    ranked.sort(
        Comparator.comparingDouble(TeacherEvidence::importance)
            .reversed()
            .thenComparingInt(position -> position.moveNumber));
    LinkedHashSet<Position> selected = new LinkedHashSet<>();
    for (Position position : ranked) {
      if (selected.stream().anyMatch(p -> Math.abs(p.moveNumber - position.moveNumber) < 3))
        continue;
      selected.add(position);
      if (selected.size() >= limit) {
        break;
      }
    }
    ArrayList<Position> chronological = new ArrayList<>(selected);
    chronological.sort(Comparator.comparingInt(position -> position.moveNumber));
    return Collections.unmodifiableList(chronological);
  }

  private static double importance(Position position) {
    OptionalDouble scoreLoss = position.actualScoreLoss();
    if (scoreLoss.isPresent()) return scoreLoss.getAsDouble();
    if (position.actualWinrateLoss.isPresent()) {
      return position.actualWinrateLoss.getAsDouble() / 10.0;
    }
    return position.candidates.isEmpty() ? 0.0 : position.candidates.get(0).visits / 1_000_000.0;
  }

  /** 知识库匹配（定式/棋形）：对当前局面查询 joseki-sgf 定式库与棋形知识库， 返回可直接拼入 prompt 的匹配说明；无匹配或失败返回空串（不阻断讲解）。 */

  /**
   * 构建单手分析载体 MoveAnalysis（供重型防编造校验链 QualityGate 使用）： 分析目标 = 实战下一手（parent.next()），视角基准 = parent
   * 节点（KataGo 已分析）。
   */
  static MoveAnalysis moveAnalysis(BoardHistoryNode parent) {
    MoveAnalysis ma = new MoveAnalysis();
    if (parent == null || parent.getData() == null) {
      return ma;
    }
    BoardData data = parent.getData();
    BoardHistoryNode child = parent.next().orElse(null);
    BoardData childData = child == null ? null : child.getData();
    ma.moveNumber = childData != null ? childData.moveNumber : data.moveNumber + 1;
    ma.actualMove = actualMove(child);
    boolean playedByBlack =
        childData != null && childData.lastMoveColor == featurecat.lizzie.rules.Stone.BLACK;
    ma.actualWinrate =
        childData != null ? ScorePerspective.winrateFromAfterMove(childData.winrate) : Double.NaN;
    ma.actualScoreLead =
        childData != null
            ? ScorePerspective.scoreLeadFromAfterMove(childData.scoreMean, playedByBlack)
            : Double.NaN;
    ma.beforeWinrate = ScorePerspective.winrateFromAfterMove(data.winrate);
    ma.beforeScoreLead = ScorePerspective.scoreLeadFromAfterMove(data.scoreMean, playedByBlack);
    ma.afterWinrate = ma.actualWinrate;
    ma.afterScoreLead = ma.actualScoreLead;

    java.util.ArrayList<AnalysisBrain.KataGoCandidate> topMoves = new java.util.ArrayList<>();
    for (MoveData move : stableCopy(data.bestMoves)) {
      if (move == null || normalizeCoordinate(move.coordinate).isEmpty()) {
        continue;
      }
      AnalysisBrain.KataGoCandidate kc = new AnalysisBrain.KataGoCandidate();
      kc.move = normalizeCoordinate(move.coordinate);
      kc.visits = Math.max(0, move.playouts);
      kc.winrate = finiteOrNaN(move.winrate);
      kc.scoreLead = finiteOrNaN(move.scoreMean);
      kc.prior = finiteOrNaN(move.policy);
      kc.pv = new String[0];
      topMoves.add(kc);
      if (topMoves.size() >= 5) {
        break;
      }
    }
    if (!topMoves.isEmpty()) {
      ma.best = topMoves.get(0);
    }
    ma.classification =
        AnalysisBrain.classify(
            ma.moveNumber,
            Double.isNaN(ma.actualWinrate) ? null : ma.actualWinrate,
            Double.isNaN(ma.actualScoreLead) ? null : ma.actualScoreLead,
            data.getPlayouts(),
            ma.best == null ? null : finiteOrNaN(ma.best.winrate),
            ma.best == null ? null : finiteOrNaN(ma.best.scoreLead),
            ma.best == null ? null : ma.best.visits,
            null,
            null,
            false);
    ma.pv = AnalysisBrain.buildPvReport(topMoves, false, null);
    return ma;
  }

  static String knowledgeMatchText(BoardHistoryNode node) {
    try {
      if (node == null || node.getData() == null) {
        return "";
      }
      featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatchQuery query =
          new featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatchQuery();
      query.boardSize = featurecat.lizzie.rules.Board.boardWidth;
      query.moveNumber = node.getData().moveNumber;
      query.playedMove = actualMove(node.next().orElse(null));
      query.text = "";
      populateKnowledgeContext(query, node);
      Optional<Position> position = position(node);
      query.lossScore =
          position.isPresent() && position.get().actualScoreLoss().isPresent()
              ? position.get().actualScoreLoss().getAsDouble()
              : null;
      List<featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatch> matches =
          featurecat.lizzie.teacher.knowledge.MatchEngine.searchKnowledgeMatchEngine(query);
      if (matches == null || matches.isEmpty()) {
        return "";
      }
      StringBuilder builder = new StringBuilder();
      int shown = 0;
      for (featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatch match : matches) {
        if (match.title == null || match.title.isEmpty() || !hasGroundedKnowledgeEvidence(match)) {
          continue;
        }
        if (shown > 0) {
          builder.append("\n\n");
        }
        builder.append(
            featurecat.lizzie.teacher.knowledge.MatchEngine.formatKnowledgeMatchForPrompt(match));
        shown++;
        if (shown >= 2) {
          break;
        }
      }
      return shown == 0 ? "" : builder.toString();
    } catch (Exception ignored) {
      return "";
    }
  }

  private static void populateKnowledgeContext(
      featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatchQuery query,
      BoardHistoryNode node) {
    List<BoardHistoryNode> path = new ArrayList<>();
    BoardHistoryNode cursor = node;
    while (cursor != null && path.size() < 40) {
      path.add(cursor);
      cursor = cursor.previous().orElse(null);
    }
    Collections.reverse(path);
    for (BoardHistoryNode historyNode : path) {
      BoardData data = historyNode.getData();
      if (data == null || data.lastMove.isEmpty()) {
        continue;
      }
      int[] coordinates = data.lastMove.get();
      featurecat.lizzie.teacher.knowledge.MatchEngine.GameMove move =
          new featurecat.lizzie.teacher.knowledge.MatchEngine.GameMove();
      move.col = coordinates[0];
      move.row = coordinates[1];
      move.gtp =
          normalizeCoordinate(Board.convertCoordinatesToName(coordinates[0], coordinates[1]));
      query.recentMoves.add(move);
    }

    List<MoveData> bestMoves = stableCopy(node.getData().bestMoves);
    for (MoveData move : bestMoves) {
      String coordinate = normalizeCoordinate(move == null ? null : move.coordinate);
      if (!coordinate.isEmpty()) {
        query.candidateMoves.add(coordinate);
      }
      if (query.candidateMoves.size() >= 5) {
        break;
      }
    }
    if (!bestMoves.isEmpty() && bestMoves.get(0) != null && bestMoves.get(0).variation != null) {
      for (String move : bestMoves.get(0).variation) {
        String coordinate = normalizeCoordinate(move);
        if (!coordinate.isEmpty()) {
          query.principalVariation.add(coordinate);
        }
        if (query.principalVariation.size() >= MAX_PV_MOVES) {
          break;
        }
      }
    }

    Stone[] stones = node.getData().stones;
    if (stones != null) {
      for (int index = 0; index < stones.length; index++) {
        Stone stone = stones[index];
        if (stone == null || stone.isEmpty()) {
          continue;
        }
        featurecat.lizzie.teacher.knowledge.LocalPatternMatcher.BoardSnapshotStone snapshot =
            new featurecat.lizzie.teacher.knowledge.LocalPatternMatcher.BoardSnapshotStone();
        snapshot.point =
            normalizeCoordinate(
                Board.convertCoordinatesToName(Board.getCoord(index)[0], Board.getCoord(index)[1]));
        snapshot.color = stone.isBlack() ? "B" : "W";
        query.boardSnapshot.add(snapshot);
      }
    }
  }

  static boolean hasGroundedKnowledgeEvidence(
      featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatch match) {
    if (match == null || match.reason == null) {
      return false;
    }
    if ("exact".equals(match.confidence)) {
      return true;
    }
    int independentSignals = 0;
    for (String reason : match.reason) {
      if (reason == null) {
        continue;
      }
      if ("geometry-strong-local-shape".equals(reason)) {
        return true;
      }
      if (reason.startsWith("sequence-overlap:")) {
        try {
          if (Double.parseDouble(reason.substring("sequence-overlap:".length())) >= 3.0) {
            return true;
          }
        } catch (NumberFormatException ignored) {
          // Ignore malformed internal evidence rather than treating it as grounded.
        }
      }
      if (reason.startsWith("specific-text:")
          || reason.startsWith("answer-")
          || reason.startsWith("candidate:")
          || reason.startsWith("pv:")) {
        independentSignals++;
      }
    }
    return "strong".equals(match.confidence) && independentSignals >= 2;
  }

  private static String normalizeCoordinate(String coordinate) {
    return coordinate == null ? "" : coordinate.trim().toUpperCase(Locale.ROOT);
  }

  private static double finiteOrNaN(double value) {
    return Double.isFinite(value) ? value : Double.NaN;
  }

  public static final class Position {
    public final int moveNumber;
    public final String toPlay;
    public final int playouts;
    public final String actualMove;
    public final OptionalDouble actualWinrateLoss;
    public final List<Candidate> candidates;
    final TeacherBoardContext board;

    /** 实战手之后的棋谱实际续走序列（最多 5 手），无则空列表。 */
    public final List<String> playedContinuation;

    Position(
        int moveNumber,
        String toPlay,
        int playouts,
        String actualMove,
        OptionalDouble actualWinrateLoss,
        Collection<Candidate> candidates) {
      this(moveNumber, toPlay, playouts, actualMove, actualWinrateLoss, candidates, List.of());
    }

    Position(
        int moveNumber,
        String toPlay,
        int playouts,
        String actualMove,
        OptionalDouble actualWinrateLoss,
        Collection<Candidate> candidates,
        Collection<String> playedContinuation) {
      this(
          moveNumber,
          toPlay,
          playouts,
          actualMove,
          actualWinrateLoss,
          candidates,
          playedContinuation,
          null);
    }

    private Position(
        int moveNumber,
        String toPlay,
        int playouts,
        String actualMove,
        OptionalDouble actualWinrateLoss,
        Collection<Candidate> candidates,
        Collection<String> playedContinuation,
        TeacherBoardContext board) {
      this.moveNumber = Math.max(0, moveNumber);
      this.toPlay = "W".equals(toPlay) ? "W" : "B";
      this.playouts = Math.max(0, playouts);
      this.actualMove = actualMove == null ? "" : actualMove;
      this.actualWinrateLoss =
          actualWinrateLoss == null ? OptionalDouble.empty() : actualWinrateLoss;
      this.candidates =
          Collections.unmodifiableList(
              new ArrayList<>(candidates == null ? List.of() : candidates));
      this.playedContinuation =
          Collections.unmodifiableList(
              new ArrayList<>(playedContinuation == null ? List.of() : playedContinuation));
      this.board = board;
    }

    Position withBoard(TeacherBoardContext board) {
      return new Position(
          moveNumber,
          toPlay,
          playouts,
          actualMove,
          actualWinrateLoss,
          candidates,
          playedContinuation,
          board);
    }

    OptionalDouble actualScoreLoss() {
      if (candidates.isEmpty() || !Double.isFinite(candidates.get(0).scoreLead))
        return OptionalDouble.empty();
      for (Candidate candidate : candidates) {
        if (candidate.coordinate.equals(actualMove) && Double.isFinite(candidate.scoreLead))
          return OptionalDouble.of(Math.max(0, candidates.get(0).scoreLead - candidate.scoreLead));
      }
      return OptionalDouble.empty();
    }
  }

  public static final class Candidate {
    public final int rank;
    public final String coordinate;
    public final double winrate;
    public final double scoreLead;
    public final int visits;
    public final List<String> variation;

    Candidate(
        int rank,
        String coordinate,
        double winrate,
        double scoreLead,
        int visits,
        Collection<String> variation) {
      this.rank = rank;
      this.coordinate = coordinate;
      this.winrate = winrate;
      this.scoreLead = scoreLead;
      this.visits = visits;
      this.variation =
          Collections.unmodifiableList(new ArrayList<>(variation == null ? List.of() : variation));
    }
  }

  public static final class Range {
    public final List<Position> positions;
    public final int analyzedPositions;
    public final int omittedPositions;

    Range(List<Position> positions, int analyzedPositions, int omittedPositions) {
      this.positions = Collections.unmodifiableList(new ArrayList<>(positions));
      this.analyzedPositions = Math.max(0, analyzedPositions);
      this.omittedPositions = Math.max(0, omittedPositions);
    }

    public boolean isEmpty() {
      return positions.isEmpty();
    }
  }
}
