package featurecat.lizzie.teacher;

import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Frozen board facts and reference lines. Never writes to the game or talks to an engine. */
final class TeacherBoardContext {
  record Group(String color, List<String> stones, List<String> liberties) {}

  record Frame(List<Stone> stones, String move, String color, int captures) {}

  record Line(String label, List<String> moves, List<Frame> frames, boolean truncated) {}

  final int width;
  final int height;
  final List<Stone> stones;
  final List<Group> groups;
  final List<Line> lines;
  final String knowledge;

  private TeacherBoardContext(
      int width,
      int height,
      List<Stone> stones,
      List<Group> groups,
      List<Line> lines,
      String knowledge) {
    this.width = width;
    this.height = height;
    this.stones = List.copyOf(stones);
    this.groups = List.copyOf(groups);
    this.lines = List.copyOf(lines);
    this.knowledge = knowledge;
  }

  // Capture on the EDT while the source board dimensions are still active. Replay frames are
  // precomputed so browsing a different-sized SGF later cannot change this explanation.
  static TeacherBoardContext capture(BoardHistoryNode node, TeacherEvidence.Position position) {
    int width = Board.boardWidth;
    int height = Board.boardHeight;
    if (node.getData().stones.length != width * height) return null;
    BoardData frozen = node.getData().clone();
    List<Stone> stones = List.copyOf(Arrays.asList(frozen.stones.clone()));
    List<Line> lines = new ArrayList<>();
    if (!position.playedContinuation.isEmpty()) {
      lines.add(replay(node, frozen, "recorded", position.playedContinuation));
    }
    for (TeacherEvidence.Candidate candidate : position.candidates) {
      List<String> moves = candidate.variation;
      if (moves.isEmpty() || !candidate.coordinate.equalsIgnoreCase(moves.get(0))) {
        moves = List.of(candidate.coordinate);
      }
      lines.add(replay(node, frozen, candidate.coordinate, moves));
    }
    return new TeacherBoardContext(
        width,
        height,
        stones,
        groups(stones, width, height),
        lines,
        TeacherEvidence.knowledgeMatchText(node));
  }

  private static Line replay(
      BoardHistoryNode source, BoardData frozen, String label, List<String> moves) {
    // Use the existing capture/suicide/ko implementation, replacing only history insertion:
    // normal addOrGoto has foreground-engine and UI side effects, add on this private tree does
    // not.
    BoardHistoryList history =
        new BoardHistoryList(frozen.clone()) {
          @Override
          public void addOrGoto(BoardData data, boolean branch, boolean change) {
            add(data);
          }

          @Override
          public void addOrGoto(BoardData data, boolean branch) {
            add(data);
          }
        };
    source
        .previous()
        .ifPresent(
            previous ->
                new BoardHistoryNode(previous.getData().clone())
                    .setPreviousForChild(history.getCurrentHistoryNode()));
    List<Frame> frames = new ArrayList<>();
    frames.add(new Frame(List.copyOf(Arrays.asList(frozen.stones.clone())), "", "", 0));
    List<String> accepted = new ArrayList<>();
    for (String move : moves) {
      BoardData before = history.getData();
      Stone color = before.blackToPlay ? Stone.BLACK : Stone.WHITE;
      if ("pass".equalsIgnoreCase(move)) {
        history.pass(color);
      } else {
        var coordinate = Board.asCoordinates(move);
        if (coordinate.isEmpty()) break;
        int[] xy = coordinate.get();
        history.place(xy[0], xy[1], color, false, false, false, false, false);
      }
      BoardData after = history.getData();
      if (after == before) break;
      accepted.add(move);
      int captures =
          color.isBlack()
              ? after.blackCaptures - before.blackCaptures
              : after.whiteCaptures - before.whiteCaptures;
      frames.add(
          new Frame(
              List.copyOf(Arrays.asList(after.stones.clone())),
              move,
              color.isBlack() ? "B" : "W",
              captures));
    }
    return new Line(
        label, List.copyOf(accepted), List.copyOf(frames), accepted.size() != moves.size());
  }

  static List<Group> groups(List<Stone> stones, int width, int height) {
    boolean[] visited = new boolean[stones.size()];
    List<Group> groups = new ArrayList<>();
    for (int origin = 0; origin < stones.size(); origin++) {
      Stone color = stones.get(origin);
      if (visited[origin] || color.isEmpty()) continue;
      ArrayDeque<Integer> queue = new ArrayDeque<>();
      List<String> members = new ArrayList<>();
      Set<String> liberties = new LinkedHashSet<>();
      queue.add(origin);
      visited[origin] = true;
      while (!queue.isEmpty()) {
        int index = queue.removeFirst();
        int x = index / height;
        int y = index % height;
        members.add(coordinate(x, y, height));
        for (int[] xy : new int[][] {{x - 1, y}, {x + 1, y}, {x, y - 1}, {x, y + 1}}) {
          if (xy[0] < 0 || xy[0] >= width || xy[1] < 0 || xy[1] >= height) continue;
          int neighbor = xy[0] * height + xy[1];
          if (stones.get(neighbor).isEmpty()) liberties.add(coordinate(xy[0], xy[1], height));
          else if (stones.get(neighbor) == color && !visited[neighbor]) {
            visited[neighbor] = true;
            queue.add(neighbor);
          }
        }
      }
      groups.add(
          new Group(color.isBlack() ? "B" : "W", List.copyOf(members), List.copyOf(liberties)));
    }
    return List.copyOf(groups);
  }

  static String coordinate(int x, int y, int height) {
    return String.valueOf((char) ('A' + x + (x >= 8 ? 1 : 0))) + (height - y);
  }

  String facts() {
    StringBuilder text = new StringBuilder("Board: ").append(width).append('x').append(height);
    text.append(
        "\nConnected groups (liberties are geometric facts, NOT proof of life/death or sente):\n");
    for (Group group : groups) {
      text.append(group.color())
          .append(" stones=")
          .append(group.stones())
          .append(" liberties=")
          .append(group.liberties())
          .append('\n');
    }
    if (groups.isEmpty()) text.append("Empty board.\n");
    text.append(
        "Locally replayed lines (simple ko and no suicide; NOT proof of best replies, superko or forced play):\n");
    for (Line line : lines) {
      text.append(
              line.label().equals("recorded")
                  ? "Recorded continuation"
                  : "Candidate " + line.label())
          .append(": ");
      for (int i = 1; i < line.frames().size(); i++) {
        Frame frame = line.frames().get(i);
        text.append(i).append('.').append(frame.color()).append(' ').append(frame.move());
        if (frame.captures() > 0) text.append(" captures=").append(frame.captures());
        text.append("; ");
      }
      if (line.truncated())
        text.append(
            "STOPPED: remaining moves could not be replayed. Do not explain the omitted suffix.");
      text.append('\n');
      if (line.frames().size() > 1) {
        for (Group group :
            groups(line.frames().get(line.frames().size() - 1).stones(), width, height)) {
          if (group.liberties().size() <= 2) {
            text.append("End of line: ")
                .append(group.color())
                .append(' ')
                .append(group.stones())
                .append(" has ")
                .append(group.liberties().size())
                .append(" liberties ")
                .append(group.liberties())
                .append(" (not a death verdict).\n");
          }
        }
      }
    }
    if (!knowledge.isBlank()) text.append("Position-specific knowledge:\n").append(knowledge);
    return text.toString();
  }
}
