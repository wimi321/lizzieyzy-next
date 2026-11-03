package featurecat.lizzie.teacher;

import featurecat.lizzie.rules.Stone;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Names the shape a move makes on the frozen board (a hane, a cut, a 3-3 invasion, ...), so the
 * commentary can say what a move does instead of reading out coordinates. Only shapes that the
 * stones alone settle are named; any other move gets no name rather than a guess.
 */
final class MoveShapes {
  private static final int CORNER_BOX = 5;
  private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
  private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

  private MoveShapes() {}

  /** The shape of {@code move} played by Black or White on {@code stones}, if it has a name. */
  static Optional<String> describe(
      List<Stone> stones, int width, int height, String move, boolean black) {
    int[] point = point(move, width, height);
    if (point == null || stones.size() != width * height) return Optional.empty();
    Grid grid = new Grid(stones.toArray(new Stone[0]), width, height);
    if (!grid.at(point[0], point[1]).isEmpty()) return Optional.empty();
    Stone own = black ? Stone.BLACK : Stone.WHITE;
    Stone opponent = black ? Stone.WHITE : Stone.BLACK;
    // null: not tactical, look further; "": a tactical move without a name.
    String named = tactical(grid, point, own, opponent);
    if (named == null) {
      boolean touches =
          !grid.neighbors(point, own).isEmpty() || !grid.neighbors(point, opponent).isEmpty();
      named = touches ? contact(grid, point, own, opponent) : distant(grid, point, own, opponent);
    }
    return Optional.ofNullable(named).filter(text -> !text.isEmpty());
  }

  private static String tactical(Grid grid, int[] point, Stone own, Stone opponent) {
    List<Set<Integer>> opponentGroups = grid.adjacentGroups(point, opponent);
    Grid after = grid.with(point, own);
    List<Integer> captured = new ArrayList<>();
    for (Set<Integer> group : opponentGroups) {
      if (after.liberties(group) == 0) captured.addAll(group);
    }
    if (!captured.isEmpty()) {
      return name("capture", "提子", "of " + grid.stones(captured, opponent));
    }
    for (Set<Integer> group : opponentGroups) {
      if (after.liberties(group) == 1) {
        return name("atari", "打吃", "on " + grid.stones(group, opponent));
      }
    }
    List<Set<Integer>> ownGroups = grid.adjacentGroups(point, own);
    if (ownGroups.size() >= 2) {
      return name("connection", "粘", "joining " + grid.firstStones(ownGroups, own));
    }
    if (opponentGroups.size() >= 2) {
      // Between two separate stones: a wedge across a gap, a cut across a diagonal held by our
      // own stone. Other moves between groups get no name.
      List<int[]> touched = grid.neighbors(point, opponent);
      for (int[] first : touched) {
        for (int[] second : touched) {
          if (grid.sameGroup(first, second)) continue;
          if (first[0] + second[0] == 2 * point[0] && first[1] + second[1] == 2 * point[1]) {
            return name("wedge", "挖", "between " + grid.pair(first, second, opponent));
          }
          int acrossX = first[0] + second[0] - point[0];
          int acrossY = first[1] + second[1] - point[1];
          if (first[0] != second[0] && first[1] != second[1] && grid.at(acrossX, acrossY) == own) {
            return name("cut", "断", "between " + grid.pair(first, second, opponent));
          }
        }
      }
      return "";
    }
    return null;
  }

  private static String contact(Grid grid, int[] point, Stone own, Stone opponent) {
    List<int[]> opponents = grid.neighbors(point, opponent);
    List<int[]> owns = grid.neighbors(point, own);
    if (opponents.isEmpty()) {
      for (int[] stone : owns) {
        for (int[] touched : grid.neighbors(stone, opponent)) {
          // Alongside the stone it touches; straight back from it is a retreat.
          if ((point[0] - stone[0]) * (touched[0] - stone[0])
                  + (point[1] - stone[1]) * (touched[1] - stone[1])
              == 0) {
            return name("extension", "长", "from " + grid.stone(stone, own));
          }
        }
      }
      return null;
    }
    // A move touching stones of both colours blocks or pushes; those are not told apart here.
    if (!owns.isEmpty()) return null;
    for (int[] touched : opponents) {
      for (int[] diagonal : DIAGONALS) {
        int[] stone = {point[0] + diagonal[0], point[1] + diagonal[1]};
        if (grid.at(stone[0], stone[1]) == own
            && Math.abs(stone[0] - touched[0]) + Math.abs(stone[1] - touched[1]) == 1) {
          return name("hane", "扳", "against " + grid.stone(touched, opponent));
        }
      }
    }
    return name("attachment", "靠", "to " + grid.stone(opponents.get(0), opponent));
  }

  private static String distant(Grid grid, int[] point, Stone own, Stone opponent) {
    if (Math.min(grid.width, grid.height) >= 13) {
      String named = corner(grid, point, own, opponent);
      if (named == null) named = sideExtension(grid, point, own);
      if (named != null) return named;
    }
    String relation = relation(grid, point, own);
    if (relation != null) return relation;
    return invasion(grid, point, own, opponent);
  }

  private static String corner(Grid grid, int[] point, Stone own, Stone opponent) {
    int cornerX = point[0] <= CORNER_BOX ? 0 : point[0] >= grid.width - 1 - CORNER_BOX ? 1 : -1;
    int cornerY = point[1] <= CORNER_BOX ? 0 : point[1] >= grid.height - 1 - CORNER_BOX ? 1 : -1;
    if (cornerX < 0 || cornerY < 0) return null;
    List<int[]> owns = new ArrayList<>();
    List<int[]> opponents = new ArrayList<>();
    for (int x = 0; x < grid.width; x++) {
      for (int y = 0; y < grid.height; y++) {
        if (grid.edgeX(x, cornerX) > CORNER_BOX || grid.edgeY(y, cornerY) > CORNER_BOX) continue;
        if (grid.at(x, y) == own) owns.add(new int[] {x, y});
        else if (grid.at(x, y) == opponent) opponents.add(new int[] {x, y});
      }
    }
    int x = grid.edgeX(point[0], cornerX);
    int y = grid.edgeY(point[1], cornerY);
    if (owns.isEmpty() && opponents.isEmpty()) {
      return between(x, 2, 4) && between(y, 2, 4)
          ? name("move in an empty corner", "占角", "")
          : null;
    }
    if (owns.isEmpty() && opponents.size() == 1) {
      int[] stone = opponents.get(0);
      int stoneX = grid.edgeX(stone[0], cornerX);
      int stoneY = grid.edgeY(stone[1], cornerY);
      if (x == 2 && y == 2 && stoneX == 3 && stoneY == 3) {
        return name("3-3 invasion", "点三三", "under " + grid.stone(stone, opponent));
      }
      if (between(stoneX, 2, 4)
          && between(stoneY, 2, 4)
          && x >= 2
          && y >= 2
          && distance(point, stone) >= 2) {
        return name("corner approach", "挂角", "to " + grid.stone(stone, opponent));
      }
    }
    if (opponents.isEmpty() && owns.size() == 1) {
      int[] stone = owns.get(0);
      if (between(grid.edgeX(stone[0], cornerX), 2, 4)
          && between(grid.edgeY(stone[1], cornerY), 2, 4)
          && x >= 2
          && y >= 2
          && distance(point, stone) <= 3) {
        String relation = relation(grid, point, own);
        return name(
            "corner enclosure",
            "守角",
            relation == null ? "with " + grid.stone(stone, own) : "as a " + relation);
      }
    }
    return null;
  }

  private static String sideExtension(Grid grid, int[] point, Stone own) {
    for (int[] along : new int[][] {{0, 1}, {1, 0}}) {
      // The side runs along `along`; the edge is across it.
      int edge =
          along[0] == 0
              ? Math.min(point[0], grid.width - 1 - point[0])
              : Math.min(point[1], grid.height - 1 - point[1]);
      if (edge < 2 || edge > 3) continue;
      for (int direction : new int[] {1, -1}) {
        for (int gap = 1; gap <= 5; gap++) {
          int x = point[0] + along[0] * direction * gap;
          int y = point[1] + along[1] * direction * gap;
          Stone stone = grid.at(x, y);
          if (stone == null || (!stone.isEmpty() && stone != own)) break;
          if (stone == own) {
            if (gap < 2) break;
            String chinese = "拆" + "一二三四".charAt(gap - 2);
            return name("extension along the side", chinese, "from " + grid.stone(x, y, own));
          }
        }
      }
    }
    return null;
  }

  private static String relation(Grid grid, int[] point, Stone own) {
    String[][] shapes = {
      {"1", "1", "diagonal move", "尖"},
      {"2", "0", "one-space jump", "跳"},
      {"1", "2", "knight's move", "小飞"},
      {"3", "0", "two-space jump", "大跳"},
      {"1", "3", "large knight's move", "大飞"}
    };
    for (String[] shape : shapes) {
      int a = Integer.parseInt(shape[0]);
      int b = Integer.parseInt(shape[1]);
      for (int[] offset :
          new int[][] {{a, b}, {-a, b}, {a, -b}, {-a, -b}, {b, a}, {-b, a}, {b, -a}, {-b, -a}}) {
        int x = point[0] + offset[0];
        int y = point[1] + offset[1];
        if (grid.at(x, y) == own && grid.openBetween(point, x, y)) {
          return name(shape[2], shape[3], "from " + grid.stone(x, y, own));
        }
      }
    }
    return null;
  }

  private static String invasion(Grid grid, int[] point, Stone own, Stone opponent) {
    if (Math.min(
            Math.min(point[0], grid.width - 1 - point[0]),
            Math.min(point[1], grid.height - 1 - point[1]))
        < 2) return null;
    int owns = 0;
    int opponents = 0;
    for (int x = point[0] - 4; x <= point[0] + 4; x++) {
      for (int y = point[1] - 4; y <= point[1] + 4; y++) {
        Stone stone = grid.at(x, y);
        if (stone == own) owns++;
        else if (stone == opponent) opponents++;
      }
    }
    return owns == 0 && opponents >= 3 ? name("invasion", "打入", "") : null;
  }

  private static String name(String english, String chinese, String detail) {
    return english + " (" + chinese + ")" + (detail.isEmpty() ? "" : " " + detail);
  }

  private static boolean between(int value, int low, int high) {
    return value >= low && value <= high;
  }

  private static int distance(int[] a, int[] b) {
    return Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]));
  }

  private static int[] point(String move, int width, int height) {
    if (move == null || move.length() < 2) return null;
    char column = Character.toUpperCase(move.charAt(0));
    if (column < 'A' || column > 'Z' || column == 'I') return null;
    int x = column - 'A' - (column > 'I' ? 1 : 0);
    int row;
    try {
      row = Integer.parseInt(move.substring(1));
    } catch (NumberFormatException notAPoint) {
      return null;
    }
    int y = height - row;
    return x < width && row >= 1 && row <= height ? new int[] {x, y} : null;
  }

  /**
   * The frozen stones, indexed like {@link TeacherBoardContext}: x * height + y, y from the top.
   */
  private static final class Grid {
    final Stone[] cells;
    final int width;
    final int height;

    Grid(Stone[] cells, int width, int height) {
      this.cells = cells;
      this.width = width;
      this.height = height;
    }

    Stone at(int x, int y) {
      return x < 0 || y < 0 || x >= width || y >= height ? null : cells[x * height + y];
    }

    Grid with(int[] point, Stone stone) {
      Stone[] copy = cells.clone();
      copy[point[0] * height + point[1]] = stone;
      return new Grid(copy, width, height);
    }

    int edgeX(int x, int corner) {
      return corner == 0 ? x : width - 1 - x;
    }

    int edgeY(int y, int corner) {
      return corner == 0 ? y : height - 1 - y;
    }

    List<int[]> neighbors(int[] point, Stone color) {
      List<int[]> found = new ArrayList<>();
      for (int[] side : SIDES) {
        int x = point[0] + side[0];
        int y = point[1] + side[1];
        if (at(x, y) == color) found.add(new int[] {x, y});
      }
      return found;
    }

    List<Set<Integer>> adjacentGroups(int[] point, Stone color) {
      List<Set<Integer>> groups = new ArrayList<>();
      for (int[] neighbor : neighbors(point, color)) {
        int index = neighbor[0] * height + neighbor[1];
        if (groups.stream().noneMatch(group -> group.contains(index))) {
          groups.add(group(index));
        }
      }
      return groups;
    }

    Set<Integer> group(int origin) {
      Set<Integer> group = new LinkedHashSet<>();
      ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(origin));
      while (!queue.isEmpty()) {
        int index = queue.removeFirst();
        if (!group.add(index)) continue;
        for (int[] neighbor :
            neighbors(new int[] {index / height, index % height}, cells[origin])) {
          queue.add(neighbor[0] * height + neighbor[1]);
        }
      }
      return group;
    }

    int liberties(Set<Integer> group) {
      Set<Integer> liberties = new LinkedHashSet<>();
      for (int index : group) {
        for (int[] side : SIDES) {
          int x = index / height + side[0];
          int y = index % height + side[1];
          if (at(x, y) != null && at(x, y).isEmpty()) liberties.add(x * height + y);
        }
      }
      return liberties.size();
    }

    /** Whether the points strictly between a straight jump's ends are empty. */
    boolean openBetween(int[] point, int x, int y) {
      int dx = x - point[0];
      int dy = y - point[1];
      if (dx != 0 && dy != 0) return true;
      int steps = Math.max(Math.abs(dx), Math.abs(dy));
      for (int step = 1; step < steps; step++) {
        Stone between =
            at(point[0] + Integer.signum(dx) * step, point[1] + Integer.signum(dy) * step);
        if (between == null || !between.isEmpty()) return false;
      }
      return true;
    }

    String stone(int[] point, Stone color) {
      return stone(point[0], point[1], color);
    }

    String stone(int x, int y, Stone color) {
      return (color.isBlack() ? "B " : "W ") + TeacherBoardContext.coordinate(x, y, height);
    }

    String coordinate(int index) {
      return TeacherBoardContext.coordinate(index / height, index % height, height);
    }

    /** Up to three of the stones, left to right and then top to bottom. */
    String stones(Collection<Integer> indices, Stone color) {
      List<String> names = new ArrayList<>();
      for (int index : indices.stream().sorted().limit(3).toList()) names.add(coordinate(index));
      return (color.isBlack() ? "B " : "W ") + String.join(" ", names);
    }

    boolean sameGroup(int[] first, int[] second) {
      return group(first[0] * height + first[1]).contains(second[0] * height + second[1]);
    }

    String pair(int[] first, int[] second, Stone color) {
      int a = first[0] * height + first[1];
      int b = second[0] * height + second[1];
      return (color.isBlack() ? "B " : "W ")
          + coordinate(Math.min(a, b))
          + " and "
          + coordinate(Math.max(a, b));
    }

    String firstStones(List<Set<Integer>> groups, Stone color) {
      List<Integer> first = new ArrayList<>();
      for (Set<Integer> group : groups) first.add(group.iterator().next());
      return stones(first, color);
    }
  }
}
