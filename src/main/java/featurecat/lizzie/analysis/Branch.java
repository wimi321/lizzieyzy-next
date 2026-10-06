package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardNodeKind;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class Branch {
  public BoardData data;
  public boolean[] isNewStone;
  public int[] pvVisitsList;
  public int length;

  public static final class Position {
    private final int width;
    private final int height;
    private final Stone[] stones;
    private final boolean blackToPlay;
    private final BoardNodeKind nodeKind;
    private final Optional<int[]> lastMove;
    private final Stone lastMoveColor;
    private final int moveNumber;
    private final int blackCaptures;
    private final int whiteCaptures;
    private final Zobrist zobrist;
    private final boolean dummy;
    private final int moveMNNumber;
    private final boolean verify;

    public Position(int width, int height, BoardData source, Stone[] stones, boolean blackToPlay) {
      if (width <= 0 || height <= 0) {
        throw new IllegalArgumentException("Dimensions must be positive: " + width + "x" + height);
      }
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(stones, "stones");
      if (stones.length != width * height) {
        throw new IllegalArgumentException(
            "Stones length ("
                + stones.length
                + ") does not match board area ("
                + (width * height)
                + ")");
      }
      this.width = width;
      this.height = height;
      this.stones = stones.clone();
      this.blackToPlay = blackToPlay;
      this.nodeKind = Objects.requireNonNull(source.getNodeKind(), "nodeKind");
      this.lastMove = source.lastMove.map(int[]::clone);
      this.lastMoveColor = source.lastMoveColor;
      this.moveNumber = source.moveNumber;
      this.blackCaptures = source.blackCaptures;
      this.whiteCaptures = source.whiteCaptures;
      this.zobrist = source.zobrist == null ? null : source.zobrist.clone();
      this.dummy = source.dummy;
      this.moveMNNumber = source.moveMNNumber;
      this.verify = source.verify;
    }
  }

  public static final class Input {
    public final Position position;
    public final List<String> variation;
    public final List<String> pvVisits;
    public final int maxLength;
    public final boolean removeDeadChains;
    public final boolean recordPvVisits;

    public Input(
        Position position,
        List<String> variation,
        List<String> pvVisits,
        int maxLength,
        boolean removeDeadChains,
        boolean recordPvVisits) {
      this.position = Objects.requireNonNull(position, "position");
      this.variation =
          variation == null
              ? Collections.emptyList()
              : Collections.unmodifiableList(new ArrayList<>(variation));
      this.pvVisits =
          pvVisits == null ? null : Collections.unmodifiableList(new ArrayList<>(pvVisits));
      this.maxLength = maxLength;
      this.removeDeadChains = removeDeadChains;
      this.recordPvVisits = recordPvVisits;
    }
  }

  public Branch(Branch.Input input) {
    Objects.requireNonNull(input, "input");
    Position position = Objects.requireNonNull(input.position, "position");
    int width = position.width;
    int height = position.height;
    int area = width * height;

    this.isNewStone = new boolean[area];
    this.pvVisitsList = new int[area];
    Stone[] stones = position.stones.clone();
    int[] moveNumberList = new int[area];

    int currentMoveNumber = position.moveNumber;
    boolean currentBlackToPlay = position.blackToPlay;
    BoardNodeKind currentNodeKind = position.nodeKind;
    Optional<int[]> currentLastMove = position.lastMove;
    Stone currentLastMoveColor = position.lastMoveColor;

    List<String> variation = input.variation;
    int limit = Math.min(variation.size(), input.maxLength);
    int processedMoves = 0;

    for (int i = 0; i < limit; i++) {
      String rawMove = variation.get(i);
      if (rawMove == null) {
        break;
      }
      String move = rawMove.trim();
      if (move.equalsIgnoreCase("resign")) {
        break;
      }
      int branchMoveNumber = processedMoves + 1;
      if (move.equalsIgnoreCase("pass")) {
        Stone moveColor = currentBlackToPlay ? Stone.BLACK : Stone.WHITE;
        currentNodeKind = BoardNodeKind.PASS;
        currentLastMove = Optional.empty();
        currentLastMoveColor = moveColor;
        currentBlackToPlay = !currentBlackToPlay;
        currentMoveNumber++;
        processedMoves++;
        continue;
      }
      Optional<int[]> coordOpt = Board.asCoordinates(move, height);
      if (!coordOpt.isPresent() || !isValid(coordOpt.get()[0], coordOpt.get()[1], width, height)) {
        break;
      }
      int[] coord = coordOpt.get();
      int x = coord[0];
      int y = coord[1];
      int boardIndex = getIndex(x, y, height);
      Stone moveColor = currentBlackToPlay ? Stone.BLACK : Stone.WHITE;
      currentNodeKind = BoardNodeKind.MOVE;
      currentLastMove = coordOpt;
      currentLastMoveColor = moveColor;
      stones[boardIndex] = moveColor;
      isNewStone[boardIndex] = true;
      if (input.removeDeadChains) {
        removeDeadChain(x + 1, y, moveColor.opposite(), stones, width, height);
        removeDeadChain(x, y + 1, moveColor.opposite(), stones, width, height);
        removeDeadChain(x - 1, y, moveColor.opposite(), stones, width, height);
        removeDeadChain(x, y - 1, moveColor.opposite(), stones, width, height);
      }
      moveNumberList[boardIndex] = branchMoveNumber;
      currentBlackToPlay = !currentBlackToPlay;
      currentMoveNumber++;
      if (input.recordPvVisits) {
        recordPvVisits(boardIndex, i, variation.size(), input.pvVisits);
      }
      processedMoves++;
    }
    this.length = processedMoves;

    this.data =
        createBoardData(
            currentNodeKind,
            stones,
            currentLastMove,
            currentLastMoveColor,
            currentBlackToPlay,
            position.zobrist,
            currentMoveNumber,
            moveNumberList,
            position.blackCaptures,
            position.whiteCaptures,
            0.0,
            0);
    this.data.dummy = position.dummy;
    this.data.moveMNNumber = position.moveMNNumber;
    this.data.verify = position.verify;
  }

  private void recordPvVisits(
      int boardIndex, int variationIndex, int variationSize, List<String> pvVisits) {
    if (pvVisits == null || pvVisits.size() != variationSize) {
      return;
    }
    try {
      pvVisitsList[boardIndex] = Integer.parseInt(pvVisits.get(variationIndex));
    } catch (NumberFormatException ignored) {
    }
  }

  private static void removeDeadChain(
      int x, int y, Stone color, Stone[] stones, int width, int height) {
    if (!isValid(x, y, width, height) || stones[getIndex(x, y, height)] != color) {
      return;
    }
    boolean hasLiberties = hasLibertiesHelper(x, y, color, stones, width, height);
    cleanupHasLibertiesHelper(x, y, color.recursed(), stones, !hasLiberties, width, height);
  }

  private static boolean hasLibertiesHelper(
      int x, int y, Stone color, Stone[] stones, int width, int height) {
    if (!isValid(x, y, width, height)) {
      return false;
    }
    int index = getIndex(x, y, height);
    if (stones[index].isEmpty()) {
      return true;
    } else if (stones[index] != color) {
      return false;
    }
    stones[index] = color.recursed();
    return hasLibertiesHelper(x + 1, y, color, stones, width, height)
        || hasLibertiesHelper(x, y + 1, color, stones, width, height)
        || hasLibertiesHelper(x - 1, y, color, stones, width, height)
        || hasLibertiesHelper(x, y - 1, color, stones, width, height);
  }

  private static void cleanupHasLibertiesHelper(
      int x,
      int y,
      Stone recursedColor,
      Stone[] stones,
      boolean removeStones,
      int width,
      int height) {
    if (!isValid(x, y, width, height)) {
      return;
    }
    int index = getIndex(x, y, height);
    if (stones[index] != recursedColor) {
      return;
    }
    stones[index] =
        removeStones
            ? (recursedColor == Stone.BLACK_RECURSED ? Stone.BLACK_CAPTURED : Stone.WHITE_CAPTURED)
            : recursedColor.unrecursed();
    cleanupHasLibertiesHelper(x + 1, y, recursedColor, stones, removeStones, width, height);
    cleanupHasLibertiesHelper(x, y + 1, recursedColor, stones, removeStones, width, height);
    cleanupHasLibertiesHelper(x - 1, y, recursedColor, stones, removeStones, width, height);
    cleanupHasLibertiesHelper(x, y - 1, recursedColor, stones, removeStones, width, height);
  }

  private static boolean isValid(int x, int y, int width, int height) {
    return x >= 0 && x < width && y >= 0 && y < height;
  }

  private static int getIndex(int x, int y, int height) {
    return x * height + y;
  }

  private static BoardData createBoardData(
      BoardNodeKind nodeKind,
      Stone[] stones,
      Optional<int[]> lastMove,
      Stone lastMoveColor,
      boolean blackToPlay,
      Zobrist zobrist,
      int moveNumber,
      int[] moveNumberList,
      int blackCaptures,
      int whiteCaptures,
      double winrate,
      int playouts) {
    switch (nodeKind) {
      case MOVE:
        return BoardData.move(
            stones,
            lastMove.orElseThrow(
                () -> new IllegalStateException("MOVE nodes require coordinates.")),
            lastMoveColor,
            blackToPlay,
            zobrist,
            moveNumber,
            moveNumberList,
            blackCaptures,
            whiteCaptures,
            winrate,
            playouts);
      case PASS:
        return BoardData.pass(
            stones,
            lastMoveColor,
            blackToPlay,
            zobrist,
            moveNumber,
            moveNumberList,
            blackCaptures,
            whiteCaptures,
            winrate,
            playouts);
      case SNAPSHOT:
        return BoardData.snapshot(
            stones,
            lastMove,
            lastMoveColor,
            blackToPlay,
            zobrist,
            moveNumber,
            moveNumberList,
            blackCaptures,
            whiteCaptures,
            winrate,
            playouts);
      default:
        throw new IllegalStateException("Unsupported board node kind: " + nodeKind);
    }
  }
}
