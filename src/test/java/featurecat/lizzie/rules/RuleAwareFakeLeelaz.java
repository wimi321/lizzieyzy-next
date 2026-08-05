package featurecat.lizzie.rules;

import featurecat.lizzie.analysis.ExactSnapshotRestoreProtocolFixture;
import featurecat.lizzie.analysis.Leelaz;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class RuleAwareFakeLeelaz extends Leelaz {
  private static final Pattern PLAY_COMMAND = Pattern.compile("^play\\s+([BW])\\s+(.+)$");
  private static final Pattern LOAD_SGF_COMMAND = Pattern.compile("^loadsgf\\s+(.+)$");
  private static final Pattern PROPERTY_PATTERN = Pattern.compile("(AB|AW|PL)\\[([^\\]]*)\\]");

  private List<String> commands;
  private Stone[] stones;
  private boolean blackToPlay = true;
  private Path lastLoadedSgf;

  private RuleAwareFakeLeelaz() throws IOException {
    super("");
    ExactSnapshotRestoreProtocolFixture.install(
        this,
        command -> {
          if (command.startsWith("loadsgf ")) {
            loadSgf(Path.of(command.substring("loadsgf ".length())));
          } else {
            sendCommand(command);
          }
          return ExactSnapshotRestoreProtocolFixture.Response.success();
        });
  }

  @Override
  public void clear() {
    recordedCommands().add("clear");
    clearBoardState();
  }

  @Override
  public void sendCommand(String command) {
    recordedCommands().add(command);
    if ("clear_board".equals(command)) {
      clearBoardState();
      return;
    }
    Matcher playMatcher = PLAY_COMMAND.matcher(command);
    if (playMatcher.matches()) {
      applyPlay(
          playMatcher.group(1).charAt(0) == 'B' ? Stone.BLACK : Stone.WHITE, playMatcher.group(2));
      return;
    }
    Matcher loadSgfMatcher = LOAD_SGF_COMMAND.matcher(command);
    if (loadSgfMatcher.matches()) {
      restoreSnapshotSgf(Path.of(loadSgfMatcher.group(1).trim()));
    }
  }

  @Override
  public void playMove(Stone color, String move) {
    sendCommand("play " + (color.isBlack() ? "B" : "W") + " " + move);
  }

  @Override
  public void playMove(Stone color, String move, boolean addPlayer, boolean blackToPlay) {
    playMove(color, move);
  }

  @Override
  public void loadSgf(Path sgfFile) {
    recordedCommands().add("loadsgf " + sgfFile.toAbsolutePath());
    restoreSnapshotSgf(sgfFile);
  }

  List<String> recordedCommands() {
    if (commands == null) {
      commands = new ArrayList<>();
    }
    return commands;
  }

  Stone[] copyStones() {
    ensureBoardState();
    return stones.clone();
  }

  boolean isBlackToPlay() {
    return blackToPlay;
  }

  Path lastLoadedSgf() {
    return lastLoadedSgf;
  }

  private void clearBoardState() {
    stones = new Stone[Board.boardWidth * Board.boardHeight];
    for (int index = 0; index < stones.length; index++) {
      stones[index] = Stone.EMPTY;
    }
    blackToPlay = true;
  }

  private void ensureBoardState() {
    if (stones == null) {
      clearBoardState();
    }
  }

  private void restoreSnapshotSgf(Path path) {
    lastLoadedSgf = path;
    ensureBoardState();
    clearBoardState();
    try {
      String content = Files.readString(path);
      Matcher matcher = PROPERTY_PATTERN.matcher(content);
      while (matcher.find()) {
        String tag = matcher.group(1);
        String value = matcher.group(2);
        if ("PL".equals(tag)) {
          blackToPlay = !"W".equalsIgnoreCase(value);
          continue;
        }
        int[] coords = SGFParser.convertSgfPosToCoord(value);
        if (coords == null) {
          continue;
        }
        stones[Board.getIndex(coords[0], coords[1])] = "AB".equals(tag) ? Stone.BLACK : Stone.WHITE;
      }
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to read SGF fixture", ex);
    }
  }

  private void applyPlay(Stone color, String move) {
    ensureBoardState();
    blackToPlay = color == Stone.WHITE;
    if ("pass".equalsIgnoreCase(move)) {
      return;
    }
    int[] coords = Board.convertNameToCoordinates(move, Board.boardHeight);
    int index = Board.getIndex(coords[0], coords[1]);
    if (!stones[index].isEmpty()) {
      throw new IllegalStateException("Attempted to play on occupied point: " + move);
    }
    stones[index] = color;
    Stone opponent = color.opposite();
    for (int[] neighbor : neighbors(coords[0], coords[1])) {
      int neighborIndex = Board.getIndex(neighbor[0], neighbor[1]);
      if (stones[neighborIndex] == opponent && countLiberties(neighbor[0], neighbor[1]) == 0) {
        removeGroup(neighbor[0], neighbor[1]);
      }
    }
    if (countLiberties(coords[0], coords[1]) == 0) {
      throw new IllegalStateException("Suicide move is not supported in fake engine: " + move);
    }
  }

  private int countLiberties(int startX, int startY) {
    Stone color = stones[Board.getIndex(startX, startY)];
    boolean[] visited = new boolean[stones.length];
    boolean[] liberties = new boolean[stones.length];
    ArrayDeque<int[]> stack = new ArrayDeque<>();
    stack.push(new int[] {startX, startY});
    visited[Board.getIndex(startX, startY)] = true;
    while (!stack.isEmpty()) {
      int[] point = stack.pop();
      for (int[] neighbor : neighbors(point[0], point[1])) {
        int index = Board.getIndex(neighbor[0], neighbor[1]);
        Stone neighborStone = stones[index];
        if (neighborStone == Stone.EMPTY) {
          liberties[index] = true;
          continue;
        }
        if (neighborStone == color && !visited[index]) {
          visited[index] = true;
          stack.push(neighbor);
        }
      }
    }
    int libertyCount = 0;
    for (boolean liberty : liberties) {
      if (liberty) {
        libertyCount++;
      }
    }
    return libertyCount;
  }

  private void removeGroup(int startX, int startY) {
    Stone color = stones[Board.getIndex(startX, startY)];
    boolean[] visited = new boolean[stones.length];
    ArrayDeque<int[]> stack = new ArrayDeque<>();
    stack.push(new int[] {startX, startY});
    visited[Board.getIndex(startX, startY)] = true;
    while (!stack.isEmpty()) {
      int[] point = stack.pop();
      stones[Board.getIndex(point[0], point[1])] = Stone.EMPTY;
      for (int[] neighbor : neighbors(point[0], point[1])) {
        int index = Board.getIndex(neighbor[0], neighbor[1]);
        if (!visited[index] && stones[index] == color) {
          visited[index] = true;
          stack.push(neighbor);
        }
      }
    }
  }

  private List<int[]> neighbors(int x, int y) {
    List<int[]> neighbors = new ArrayList<>(4);
    addNeighbor(neighbors, x - 1, y);
    addNeighbor(neighbors, x + 1, y);
    addNeighbor(neighbors, x, y - 1);
    addNeighbor(neighbors, x, y + 1);
    return neighbors;
  }

  private void addNeighbor(List<int[]> neighbors, int x, int y) {
    if (Board.isValid(x, y)) {
      neighbors.add(new int[] {x, y});
    }
  }
}
