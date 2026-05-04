package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class SnapshotTrackingLeelaz extends Leelaz {
  private static final Pattern PLAY_COMMAND = Pattern.compile("^play\\s+([BW])\\s+(.+)$");
  private static final Pattern LOAD_SGF_COMMAND = Pattern.compile("^loadsgf\\s+(.+)$");
  private static final Pattern PROPERTY_PATTERN = Pattern.compile("(AB|AW|PL)\\[([^\\]]*)\\]");

  int clearCount;
  int ponderCount;
  int togglePonderCount;
  int nameCmdCount;
  List<String> playedMoves;
  List<String> sentCommands;
  private Stone[] stones;
  private boolean blackToPlay = true;
  private Path lastLoadedSgf;

  private SnapshotTrackingLeelaz() throws IOException {
    super("");
  }

  static SnapshotTrackingLeelaz create() throws Exception {
    SnapshotTrackingLeelaz leelaz =
        (SnapshotTrackingLeelaz) UnsafeHolder.UNSAFE.allocateInstance(SnapshotTrackingLeelaz.class);
    leelaz.clearCount = 0;
    leelaz.ponderCount = 0;
    leelaz.togglePonderCount = 0;
    leelaz.nameCmdCount = 0;
    leelaz.playedMoves = new ArrayList<>();
    leelaz.sentCommands = new ArrayList<>();
    leelaz.started = true;
    leelaz.resetBoardState();
    return leelaz;
  }

  @Override
  public void clear() {
    clearCount++;
    ponderCount = 0;
    togglePonderCount = 0;
    nameCmdCount = 0;
    playedMoves = new ArrayList<>();
    sentCommands = new ArrayList<>();
    resetBoardState();
  }

  @Override
  public void togglePonder() {
    togglePonderCount++;
    if (isPondering()) {
      notPondering();
      nameCmd();
    } else {
      Pondering();
      ponder();
    }
  }

  @Override
  public void ponder() {
    ponderCount++;
  }

  @Override
  public void nameCmd() {
    nameCmdCount++;
    sendCommand("name");
  }

  @Override
  public void sendCommand(String command) {
    recordedCommands().add(command);
    if ("clear_board".equals(command)) {
      resetBoardState();
      return;
    }
    Matcher playMatcher = PLAY_COMMAND.matcher(command);
    if (playMatcher.matches()) {
      Stone color = playMatcher.group(1).charAt(0) == 'B' ? Stone.BLACK : Stone.WHITE;
      String move = playMatcher.group(2);
      recordPlayedMove(color, move);
      applyPlay(color, move);
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

  Stone[] copyStones() {
    return stones.clone();
  }

  boolean isBlackToPlay() {
    return blackToPlay;
  }

  Path lastLoadedSgf() {
    return lastLoadedSgf;
  }

  private List<String> recordedCommands() {
    if (sentCommands == null) {
      sentCommands = new ArrayList<>();
    }
    return sentCommands;
  }

  private void recordPlayedMove(Stone color, String move) {
    if (playedMoves == null) {
      playedMoves = new ArrayList<>();
    }
    playedMoves.add(color.name() + ":" + move);
  }

  private void resetBoardState() {
    stones = new Stone[Board.boardWidth * Board.boardHeight];
    for (int index = 0; index < stones.length; index++) {
      stones[index] = Stone.EMPTY;
    }
    blackToPlay = true;
  }

  private void restoreSnapshotSgf(Path path) {
    lastLoadedSgf = path;
    resetBoardState();
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

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static sun.misc.Unsafe loadUnsafe() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }
}
