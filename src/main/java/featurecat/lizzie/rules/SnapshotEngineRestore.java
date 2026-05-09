package featurecat.lizzie.rules;

import featurecat.lizzie.analysis.Leelaz;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SnapshotEngineRestore {
  private static final long DELETE_RETRY_DELAY_MILLIS = 250L;
  private static final int DELETE_RETRY_LIMIT = 20;
  private static final int SGF_EXTENDED_COORD_THRESHOLD = 52;
  private static final String SGF_COORD_ALPHABET =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final ScheduledExecutorService DELETE_EXECUTOR =
      Executors.newSingleThreadScheduledExecutor(SnapshotEngineRestore::newCleanupThread);

  private SnapshotEngineRestore() {}

  public static boolean restoreExactSnapshotIfNeeded(Leelaz engine, BoardData snapshotData) {
    RestoreLifecycle lifecycle = beginExactSnapshotRestoreIfNeeded(engine, snapshotData);
    if (lifecycle == null) {
      return false;
    }
    lifecycle.finishTailReplay();
    return true;
  }

  public static RestoreLifecycle beginExactSnapshotRestoreIfNeeded(
      Leelaz engine, BoardData snapshotData) {
    if (snapshotData == null || !snapshotData.isSnapshotNode()) {
      return null;
    }
    Path sgfFile = writeSnapshotSgf(snapshotData);
    RestoreLifecycle lifecycle = new RestoreLifecycle(sgfFile);
    try {
      engine.loadSgf(sgfFile, lifecycle::onLoadSgfConsumed);
      return lifecycle;
    } catch (RuntimeException ex) {
      lifecycle.finishTailReplay();
      throw ex;
    }
  }

  public static BoardData snapshotFromCurrentBoard(BoardData sourceData) {
    if (sourceData == null) {
      throw new IllegalArgumentException("sourceData");
    }
    BoardData snapshot =
        BoardData.snapshot(
            sourceData.stones.clone(),
            java.util.Optional.empty(),
            Stone.EMPTY,
            sourceData.blackToPlay,
            sourceData.zobrist == null ? null : sourceData.zobrist.clone(),
            sourceData.moveNumber,
            sourceData.moveNumberList == null ? null : sourceData.moveNumberList.clone(),
            sourceData.blackCaptures,
            sourceData.whiteCaptures,
            sourceData.winrate,
            sourceData.getPlayouts());
    snapshot.moveMNNumber = sourceData.moveMNNumber;
    snapshot.comment = sourceData.comment;
    snapshot.setProperties(sourceData.getProperties());
    int[] boardSize = resolveSnapshotBoardSize(sourceData);
    snapshot.addProperty("SZ", formatBoardSizeTag(boardSize[0], boardSize[1]));
    return snapshot;
  }

  private static Path writeSnapshotSgf(BoardData snapshotData) {
    try {
      Path sgfFile = Files.createTempFile("lizzie-snapshot-", ".sgf");
      String sgf = buildSnapshotSgf(snapshotData);
      Files.writeString(sgfFile, sgf);
      if (featurecat.lizzie.analysis.TrialDiag.ENABLED) {
        System.out.println("[trial-sgf] " + sgf);
      }
      return sgfFile;
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to build snapshot SGF for engine restore", ex);
    }
  }

  private static void scheduleDelete(Path sgfFile, int attempt) {
    DELETE_EXECUTOR.schedule(
        () -> deleteSnapshotFile(sgfFile, attempt),
        DELETE_RETRY_DELAY_MILLIS,
        TimeUnit.MILLISECONDS);
  }

  private static void deleteSnapshotFile(Path sgfFile, int attempt) {
    try {
      Files.deleteIfExists(sgfFile);
      return;
    } catch (IOException ex) {
      if (attempt + 1 >= DELETE_RETRY_LIMIT) {
        sgfFile.toFile().deleteOnExit();
        return;
      }
    }
    scheduleDelete(sgfFile, attempt + 1);
  }

  private static Thread newCleanupThread(Runnable runnable) {
    Thread thread = new Thread(runnable, "lizzie-snapshot-sgf-cleanup");
    thread.setDaemon(true);
    return thread;
  }

  private static String buildSnapshotSgf(BoardData snapshotData) {
    int[] boardSize = resolveSnapshotBoardSize(snapshotData);
    int boardWidth = boardSize[0];
    int boardHeight = boardSize[1];
    StringBuilder builder = new StringBuilder();
    builder.append("(;FF[4]GM[1]CA[UTF-8]");
    builder.append("SZ[").append(formatBoardSizeTag(boardWidth, boardHeight)).append(']');
    // 写入 KM 字段：KataGo loadsgf 会按 SGF 的 KM 重置 komi。漏写让 KataGo 进程内的 komi 退回 SGF 默认值
    // 而不是 lizzie 启动时已经 GTP `komi X` 设的值。优先用 snapshotData.komi（引擎分析过的节点会填）；
    // 否则读 Lizzie.board.history.gameInfo.komi；测试场景下两者都拿不到时跳过 KM 字段。
    Double komi = resolveKomi(snapshotData);
    if (komi != null) {
      builder.append("KM[").append(formatKomi(komi)).append(']');
    }
    builder.append("PL[").append(snapshotData.blackToPlay ? "B" : "W").append(']');
    appendStones(builder, snapshotData.stones, Stone.BLACK, "AB", boardWidth, boardHeight);
    appendStones(builder, snapshotData.stones, Stone.WHITE, "AW", boardWidth, boardHeight);
    builder.append(')');
    return builder.toString();
  }

  private static String formatKomi(double komi) {
    return String.format(java.util.Locale.US, "%.1f", komi);
  }

  /** 取 komi 写入 SGF：snapshotData 自身 → Lizzie 全局历史 → 都拿不到时返回 null（跳过 KM 字段）。 */
  private static Double resolveKomi(BoardData snapshotData) {
    if (snapshotData.komi != -999) return snapshotData.komi;
    Board board = featurecat.lizzie.Lizzie.board;
    if (board == null) return null;
    BoardHistoryList history = board.getHistory();
    if (history == null) return null;
    featurecat.lizzie.analysis.GameInfo gameInfo = history.getGameInfo();
    if (gameInfo == null) return null;
    return gameInfo.getKomi();
  }

  private static void appendStones(
      StringBuilder builder,
      Stone[] stones,
      Stone targetColor,
      String propertyName,
      int boardWidth,
      int boardHeight) {
    for (int index = 0; index < stones.length; index++) {
      if (stones[index] != targetColor) {
        continue;
      }
      int[] coord = toBoardCoord(index, boardHeight);
      builder
          .append(propertyName)
          .append('[')
          .append(formatSgfCoord(coord[0], coord[1], boardWidth, boardHeight))
          .append(']');
    }
  }

  private static int[] resolveSnapshotBoardSize(BoardData snapshotData) {
    int boardArea = snapshotData.stones == null ? 0 : snapshotData.stones.length;
    int[] boardSizeFromProperty = parseBoardSizeTagValue(snapshotData.getProperty("SZ"));
    if (boardSizeFromProperty != null) {
      int parsedBoardArea = boardSizeFromProperty[0] * boardSizeFromProperty[1];
      if (boardArea == 0 || parsedBoardArea == boardArea) {
        return boardSizeFromProperty;
      }
    }
    if (boardArea <= 0) {
      throw new IllegalStateException("Snapshot data does not contain board stones.");
    }
    int[] boardSizeFromCurrentBoard = resolveBoardSizeFromCurrentBoard(boardArea);
    if (boardSizeFromCurrentBoard != null) {
      return boardSizeFromCurrentBoard;
    }
    return inferBoardSizeFromArea(boardArea);
  }

  private static int[] resolveBoardSizeFromCurrentBoard(int boardArea) {
    int currentBoardWidth = Board.boardWidth;
    int currentBoardHeight = Board.boardHeight;
    if (currentBoardWidth <= 0 || currentBoardHeight <= 0) {
      return null;
    }
    if (currentBoardWidth * currentBoardHeight != boardArea) {
      return null;
    }
    return new int[] {currentBoardWidth, currentBoardHeight};
  }

  private static int[] parseBoardSizeTagValue(String rawBoardSizeValue) {
    if (rawBoardSizeValue == null) {
      return null;
    }
    String boardSizeValue = rawBoardSizeValue.split(",")[0].trim();
    if (boardSizeValue.isEmpty()) {
      return null;
    }
    int split = boardSizeValue.indexOf(':');
    try {
      if (split >= 0) {
        int boardWidth = Integer.parseInt(boardSizeValue.substring(0, split));
        int boardHeight = Integer.parseInt(boardSizeValue.substring(split + 1));
        if (boardWidth > 0 && boardHeight > 0) {
          return new int[] {boardWidth, boardHeight};
        }
        return null;
      }
      int boardSize = Integer.parseInt(boardSizeValue);
      if (boardSize <= 0) {
        return null;
      }
      return new int[] {boardSize, boardSize};
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static int[] inferBoardSizeFromArea(int boardArea) {
    int boardHeight = (int) Math.ceil(Math.sqrt(boardArea));
    while (boardHeight <= boardArea && boardArea % boardHeight != 0) {
      boardHeight++;
    }
    if (boardHeight > boardArea) {
      return new int[] {boardArea, 1};
    }
    int boardWidth = boardArea / boardHeight;
    return new int[] {boardWidth, boardHeight};
  }

  private static int[] toBoardCoord(int index, int boardHeight) {
    int y = index % boardHeight;
    int x = (index - y) / boardHeight;
    return new int[] {x, y};
  }

  private static String formatSgfCoord(int x, int y, int boardWidth, int boardHeight) {
    if (boardWidth >= SGF_EXTENDED_COORD_THRESHOLD || boardHeight >= SGF_EXTENDED_COORD_THRESHOLD) {
      return x + "_" + y;
    }
    return "" + SGF_COORD_ALPHABET.charAt(x) + SGF_COORD_ALPHABET.charAt(y);
  }

  private static String formatBoardSizeTag(int boardWidth, int boardHeight) {
    return boardWidth == boardHeight ? String.valueOf(boardWidth) : boardWidth + ":" + boardHeight;
  }

  public static final class RestoreLifecycle {
    private final Path sgfFile;
    private final AtomicBoolean loadSgfConsumed = new AtomicBoolean(false);
    private final AtomicBoolean tailReplayFinished = new AtomicBoolean(false);
    private final AtomicBoolean deleteStarted = new AtomicBoolean(false);

    private RestoreLifecycle(Path sgfFile) {
      this.sgfFile = sgfFile;
    }

    private void onLoadSgfConsumed() {
      loadSgfConsumed.set(true);
      tryDelete();
    }

    public void finishTailReplay() {
      tailReplayFinished.set(true);
      tryDelete();
    }

    private void tryDelete() {
      if (!loadSgfConsumed.get() || !tailReplayFinished.get()) {
        return;
      }
      if (!deleteStarted.compareAndSet(false, true)) {
        return;
      }
      deleteSnapshotFile(sgfFile, 0);
    }
  }
}
