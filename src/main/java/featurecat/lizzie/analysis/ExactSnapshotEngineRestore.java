package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Restores an engine from one immutable snapshot plan. */
public final class ExactSnapshotEngineRestore {
  private static final long DELETE_RETRY_DELAY_MILLIS = 250L;
  private static final int DELETE_RETRY_LIMIT = 20;
  private static final int SGF_EXTENDED_COORD_THRESHOLD = 52;
  private static final String SGF_COORD_ALPHABET =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final ScheduledExecutorService DELETE_EXECUTOR =
      Executors.newSingleThreadScheduledExecutor(ExactSnapshotEngineRestore::newCleanupThread);

  private ExactSnapshotEngineRestore() {}

  enum FailureCategory {
    ADMISSION_STALE,
    SEND_FAILED,
    GTP_ERROR,
    TIMEOUT,
    TAIL_REJECTED
  }

  static final class Failure extends IllegalStateException {
    private final FailureCategory category;

    Failure(FailureCategory category, String detail) {
      super(detail);
      this.category = category;
    }

    Failure(FailureCategory category, String detail, Throwable cause) {
      super(detail, cause);
      this.category = category;
    }

    FailureCategory category() {
      return category;
    }
  }

  public static Optional<PreparedRestore> prepare(
      Leelaz.ExactSnapshotRestoreAdmission admission, BoardHistoryNode target) {
    try {
      Optional<PreparedRestore> prepared =
          RestorePlan.capture(admission, target).map(PreparedRestore::new);
      if (prepared.isEmpty()) {
        admission.completeBoardSync();
      }
      return prepared;
    } catch (RuntimeException failure) {
      admission.completeBoardSync();
      throw failure;
    }
  }

  public static PreparedRestore prepareCurrentPosition(
      Leelaz.ExactSnapshotRestoreAdmission admission, BoardData positionData) {
    try {
      return new PreparedRestore(RestorePlan.capture(admission, positionData));
    } catch (RuntimeException failure) {
      admission.completeBoardSync();
      throw failure;
    }
  }

  private static void validateCurrentPosition(BoardData sourceData) {
    if (sourceData == null) {
      throw new IllegalArgumentException("positionData");
    }
    if (sourceData.stones == null || sourceData.stones.length == 0) {
      throw new IllegalArgumentException("positionData.stones");
    }
    for (int index = 0; index < sourceData.stones.length; index++) {
      if (sourceData.stones[index] == null) {
        throw new IllegalArgumentException("positionData.stones[" + index + "]");
      }
    }
  }

  private static BoardData materializeCurrentPosition(BoardData sourceData) {
    validateCurrentPosition(sourceData);
    BoardData snapshot =
        BoardData.snapshot(
            sourceData.stones.clone(),
            Optional.empty(),
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
    snapshot.komi = sourceData.komi;
    snapshot.setProperties(sourceData.getProperties());
    int[] boardSize = resolveSnapshotBoardSize(sourceData);
    snapshot.addProperty("SZ", formatBoardSizeTag(boardSize[0], boardSize[1]));
    return snapshot;
  }

  private static Completion execute(RestorePlan plan) {
    if (plan.preclear) {
      clearCapturedTargets(plan);
    }
    List<Leelaz> remoteTargets = new ArrayList<>();
    List<Leelaz> localTargets = new ArrayList<>();
    for (Leelaz target : plan.targetEngines) {
      if (target.useRemoteCompute) {
        remoteTargets.add(target);
      } else {
        localTargets.add(target);
      }
    }
    RestoreLifecycle lifecycle = null;
    try {
      try {
        if (!remoteTargets.isEmpty()) {
          restoreRemoteSnapshotInBand(plan, remoteTargets);
        }
        if (!localTargets.isEmpty()) {
          Path sgfFile = writeSnapshotSgf(plan);
          lifecycle = new RestoreLifecycle(sgfFile);
          restoreLocalSnapshotSgf(plan, localTargets, lifecycle);
        }
      } catch (RuntimeException failure) {
        if (lifecycle != null) {
          lifecycle.failBeforeLoadDispatch();
        }
        throw failure;
      }
      for (TailAction action : plan.tail) {
        for (Leelaz targetEngine : plan.targetEngines) {
          sendCapturedRestoreCommand(plan, targetEngine, action.command, "tail");
        }
      }
      for (Leelaz targetEngine : plan.targetEngines) {
        targetEngine.width = plan.boardWidth;
        targetEngine.height = plan.boardHeight;
        if (plan.komi != null) {
          targetEngine.komi = plan.komi.floatValue();
        }
      }
      return new Completion();
    } finally {
      if (lifecycle != null) {
        lifecycle.finishTailReplay();
      }
      plan.admission.completeBoardSync();
    }
  }

  private static void restoreRemoteSnapshotInBand(RestorePlan plan, List<Leelaz> remoteTargets) {
    if (plan.komi != null) {
      String komiCommand = "komi " + formatKomi(plan.komi);
      for (Leelaz target : remoteTargets) {
        sendCapturedRestoreCommand(plan, target, komiCommand, "komi");
      }
    }
    String command = buildSetPositionCommand(plan);
    Leelaz loadEngine = remoteTargets.get(0);
    Leelaz loadMirror = remoteTargets.size() > 1 ? remoteTargets.get(1) : null;
    loadEngine.withExactSnapshotRestoreAdmission(
        plan.admission,
        () ->
            loadEngine.restoreInBandForExactSnapshotRestore(
                command, loadMirror, plan.admission, () -> {}, null));
    if (!plan.snapshotData.blackToPlay && plan.tail.isEmpty()) {
      for (Leelaz target : remoteTargets) {
        sendCapturedRestoreCommand(plan, target, "play B pass", "side-to-play");
      }
    }
  }

  private static void restoreLocalSnapshotSgf(
      RestorePlan plan, List<Leelaz> localTargets, RestoreLifecycle lifecycle) {
    Leelaz loadEngine = localTargets.get(0);
    Leelaz loadMirror = localTargets.size() > 1 ? localTargets.get(1) : null;
    loadEngine.withExactSnapshotRestoreAdmission(
        plan.admission,
        () ->
            loadEngine.loadSgfForExactSnapshotRestore(
                lifecycle.sgfFile,
                loadMirror,
                plan.admission,
                lifecycle::onLoadSgfConsumed,
                lifecycle::onLoadDispatchStarted));
  }

  private static String buildSetPositionCommand(RestorePlan plan) {
    StringBuilder command = new StringBuilder("set_position");
    appendSetPositionStones(command, plan, Stone.BLACK, "B");
    appendSetPositionStones(command, plan, Stone.WHITE, "W");
    return command.toString();
  }

  private static void appendSetPositionStones(
      StringBuilder command, RestorePlan plan, Stone targetColor, String gtpColor) {
    Stone[] stones = plan.snapshotData.stones;
    for (int index = 0; index < stones.length; index++) {
      if (stones[index] != targetColor) {
        continue;
      }
      int[] coord = toBoardCoord(index, plan.boardHeight);
      command
          .append(' ')
          .append(gtpColor)
          .append(' ')
          .append(formatGtpCoord(coord[0], coord[1], plan.boardWidth, plan.boardHeight));
    }
  }

  private static String formatGtpCoord(int x, int y, int boardWidth, int boardHeight) {
    if (boardWidth > 25 || boardHeight > 25) {
      return String.format(java.util.Locale.ENGLISH, "(%d,%d)", x, y);
    }
    return Board.coordsAsName(x) + (boardHeight - y);
  }

  private static void clearCapturedTargets(RestorePlan plan) {
    for (Leelaz targetEngine : plan.targetEngines) {
      sendCapturedRestoreCommand(plan, targetEngine, "clear_board", "preclear");
      targetEngine.onCapturedRestoreClearCommandSent();
    }
  }

  private static void sendCapturedRestoreCommand(
      RestorePlan plan, Leelaz target, String command, String phase) {
    final RuntimeException[] failure = new RuntimeException[1];
    target.withExactSnapshotRestoreAdmission(
        plan.admission,
        () -> {
          if (!target.sendCommandToCapturedRestoreTarget(command, plan.admission)) {
            failure[0] =
                new Failure(
                    FailureCategory.TAIL_REJECTED,
                    "Exact snapshot restore " + phase + " command was rejected: " + command);
          }
        });
    if (failure[0] != null) {
      throw failure[0];
    }
  }

  private static Path writeSnapshotSgf(RestorePlan plan) {
    Path sgfFile = null;
    try {
      sgfFile = Files.createTempFile("lizzie-snapshot-", ".sgf");
      String sgf = buildSnapshotSgf(plan);
      Files.writeString(sgfFile, sgf);
      if (TrialDiag.ENABLED) {
        System.out.println("[trial-sgf] " + sgf);
      }
      return sgfFile;
    } catch (IOException ex) {
      deleteFailedSnapshotFile(sgfFile, ex);
      throw new IllegalStateException("Failed to build snapshot SGF for engine restore", ex);
    } catch (RuntimeException ex) {
      deleteFailedSnapshotFile(sgfFile, ex);
      throw ex;
    }
  }

  private static void deleteFailedSnapshotFile(Path sgfFile, Throwable failure) {
    if (sgfFile == null) {
      return;
    }
    try {
      Files.deleteIfExists(sgfFile);
    } catch (IOException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
      deleteSnapshotFile(sgfFile, 0);
    }
  }

  private static String buildSnapshotSgf(RestorePlan plan) {
    StringBuilder builder = new StringBuilder();
    builder.append("(;FF[4]GM[1]CA[UTF-8]");
    builder.append("SZ[").append(formatBoardSizeTag(plan.boardWidth, plan.boardHeight)).append(']');
    if (plan.komi != null) {
      builder.append("KM[").append(formatKomi(plan.komi)).append(']');
    }
    builder.append("PL[").append(plan.snapshotData.blackToPlay ? "B" : "W").append(']');
    appendStones(
        builder, plan.snapshotData.stones, Stone.BLACK, "AB", plan.boardWidth, plan.boardHeight);
    appendStones(
        builder, plan.snapshotData.stones, Stone.WHITE, "AW", plan.boardWidth, plan.boardHeight);
    builder.append(')');
    return builder.toString();
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

  private static String formatKomi(double komi) {
    return String.format(java.util.Locale.US, "%.1f", komi);
  }

  private static Double captureKomi(Leelaz engine, BoardData snapshotData) {
    if (snapshotData != null && snapshotData.komi != -999) {
      return snapshotData.komi;
    }
    Double historyKomi = captureHistoryKomi();
    if (historyKomi != null) {
      return historyKomi;
    }
    if (engine != null && !Float.isNaN(engine.komi)) {
      return (double) engine.komi;
    }
    return null;
    }

  private static Double captureHistoryKomi() {
    Board board = Lizzie.board;
    if (board == null) {
      return null;
    }
    BoardHistoryList history = board.getHistory();
    if (history == null || history.getGameInfo() == null) {
      return null;
    }
    return history.getGameInfo().getKomi();
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

  public static final class Completion {
    private Completion() {}
  }

  public static final class PreparedRestore {
    private final RestorePlan plan;
    private final AtomicBoolean executed = new AtomicBoolean(false);

    private PreparedRestore(RestorePlan plan) {
      this.plan = plan;
    }

    public Completion execute() {
      if (!executed.compareAndSet(false, true)) {
        throw new IllegalStateException("Exact snapshot restore has already been executed");
      }
      return ExactSnapshotEngineRestore.execute(plan);
    }

    public void discard() {
      if (!executed.compareAndSet(false, true)) {
        throw new IllegalStateException("Exact snapshot restore has already been executed");
      }
      plan.admission.completeBoardSync();
    }
  }

  private static final class RestorePlan {
    private final Leelaz engine;
    private final Leelaz mirrorEngine;
    private final List<Leelaz> targetEngines;
    private final BoardData snapshotData;
    private final int boardWidth;
    private final int boardHeight;
    private final Double komi;
    private final List<TailAction> tail;
    private final boolean preclear;
    private final Leelaz.ExactSnapshotRestoreAdmission admission;

    private RestorePlan(
        Leelaz engine,
        Leelaz mirrorEngine,
        BoardData snapshotData,
        List<TailAction> tail,
        Double restoreKomi,
        Leelaz.ExactSnapshotRestoreAdmission admission) {
      this.engine = engine;
      this.mirrorEngine = mirrorEngine;
      this.targetEngines = mirrorEngine == null ? List.of(engine) : List.of(engine, mirrorEngine);
      this.snapshotData = snapshotData.clone();
      int[] boardSize = resolveSnapshotBoardSize(this.snapshotData);
      this.boardWidth = boardSize[0];
      this.boardHeight = boardSize[1];
      this.komi = restoreKomi == null ? captureKomi(engine, this.snapshotData) : restoreKomi;
      this.tail = List.copyOf(tail);
      this.admission = admission;
      this.preclear = admission.preclear();
    }

    private static Optional<RestorePlan> capture(
        Leelaz.ExactSnapshotRestoreAdmission admission,
        BoardHistoryNode target) {
      if (admission == null) {
        throw new IllegalArgumentException("admission");
    }
      Leelaz engine = admission.authority();
      Leelaz mirrorEngine = admission.mirror();
      requireEngine(engine);
      engine.requireExactSnapshotRestoreAdmission(admission);
      if (target == null) {
        throw new IllegalArgumentException("target");
      }
      SnapshotAnchor anchor = findSnapshotAnchor(target);
      if (anchor == null) {
        return Optional.empty();
      }
      BoardData snapshotData =
          anchor.data.isSnapshotNode() ? anchor.data : materializeCurrentPosition(anchor.data);
      int[] boardSize = resolveSnapshotBoardSize(snapshotData);
      List<TailAction> tail = captureTail(target, anchor.node, boardSize[0], boardSize[1]);
      Double restoreKomi = captureHistoryKomi();
      return Optional.of(
          new RestorePlan(engine, mirrorEngine, snapshotData, tail, restoreKomi, admission));
    }

    private static RestorePlan capture(
        Leelaz.ExactSnapshotRestoreAdmission admission,
        BoardData sourceData) {
      if (admission == null) {
        throw new IllegalArgumentException("admission");
    }
      Leelaz engine = admission.authority();
      Leelaz mirrorEngine = admission.mirror();
      requireEngine(engine);
      engine.requireExactSnapshotRestoreAdmission(admission);
      validateCurrentPosition(sourceData);
      BoardData snapshotData =
          sourceData.isSnapshotNode() ? sourceData : materializeCurrentPosition(sourceData);
      return new RestorePlan(
          engine,
          mirrorEngine,
          snapshotData,
          Collections.emptyList(),
          null,
          admission);
    }

    private static void requireEngine(Leelaz engine) {
      if (engine == null) {
        throw new IllegalArgumentException("engine");
      }
    }

    private static SnapshotAnchor findSnapshotAnchor(BoardHistoryNode target) {
      for (BoardHistoryNode node = target; node != null; node = node.previous().orElse(null)) {
        BoardData data = node.getData();
        if (data != null && (node.hasRemovedStone() || isUsableSnapshotAnchor(node, data))) {
          return new SnapshotAnchor(node, data);
        }
      }
      return null;
    }

    private static boolean isUsableSnapshotAnchor(BoardHistoryNode node, BoardData data) {
      if (!data.isSnapshotNode()) {
        return false;
      }
      if (node.previous().isPresent()
          || data.moveNumber > 0
          || data.lastMove.isPresent()
          || !data.blackToPlay) {
        return true;
      }
      for (Stone stone : data.stones) {
        if (stone.isBlack() || stone.isWhite()) {
          return true;
        }
      }
      return false;
    }

    private static List<TailAction> captureTail(
        BoardHistoryNode target, BoardHistoryNode snapshotAnchor, int boardWidth, int boardHeight) {
      List<TailAction> reversedTail = new ArrayList<>();
      for (BoardHistoryNode node = target;
          node != snapshotAnchor;
          node = node.previous().orElse(null)) {
        if (node == null) {
          throw new IllegalStateException("Snapshot anchor is not an ancestor of history target.");
        }
        TailAction.from(node.getData(), boardWidth, boardHeight).ifPresent(reversedTail::add);
      }
      Collections.reverse(reversedTail);
      return reversedTail;
    }
  }

  private static final class SnapshotAnchor {
    private final BoardHistoryNode node;
    private final BoardData data;

    private SnapshotAnchor(BoardHistoryNode node, BoardData data) {
      this.node = node;
      this.data = data;
    }
  }

  private static final class TailAction {
    private final String command;

    private TailAction(String command) {
      this.command = command;
    }

    private static Optional<TailAction> from(BoardData data, int boardWidth, int boardHeight) {
      if (data == null || data.dummy) {
        return Optional.empty();
      }
      String color = data.lastMoveColor.isBlack() ? "B" : "W";
      if (data.isPassNode()) {
        return Optional.of(new TailAction("play " + color + " pass"));
      }
      if (!data.isMoveNode() || !data.lastMove.isPresent()) {
        return Optional.empty();
      }
      int[] move = data.lastMove.get();
      String coordinate = formatGtpCoord(move[0], move[1], boardWidth, boardHeight);
      return Optional.of(new TailAction("play " + color + " " + coordinate));
    }
  }

  private static final class RestoreLifecycle {
    private final Path sgfFile;
    private final AtomicBoolean loadSgfConsumed = new AtomicBoolean(false);
    private final AtomicBoolean loadDispatchStarted = new AtomicBoolean(false);
    private final AtomicBoolean tailReplayFinished = new AtomicBoolean(false);
    private final AtomicBoolean deleteStarted = new AtomicBoolean(false);

    private RestoreLifecycle(Path sgfFile) {
      this.sgfFile = sgfFile;
    }

    private void onLoadSgfConsumed() {
      loadSgfConsumed.set(true);
      tryDelete();
    }

    private void onLoadDispatchStarted() {
      loadDispatchStarted.set(true);
    }

    private void failBeforeLoadDispatch() {
      if (!loadDispatchStarted.get()) {
        loadSgfConsumed.set(true);
        tryDelete();
      }
    }

    private void finishTailReplay() {
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
