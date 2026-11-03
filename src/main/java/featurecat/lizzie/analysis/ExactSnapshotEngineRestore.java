package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.InitialHandicapSetup;
import featurecat.lizzie.rules.Stone;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  static final String NO_WRITABLE_SNAPSHOT_LOCATION_DETAIL =
      "No writable snapshot location is available. Check permissions for the temporary, engine "
          + "working, and application runtime directories.";
  static final String UNSUPPORTED_SNAPSHOT_TRANSPORT_DETAIL =
      "This engine command cannot prove access to local snapshot files. Run a direct local engine "
          + "executable with the same GTP options.";
  static final String NO_HANDICAP_RESTORE_DETAIL =
      "Engine does not advertise set_free_handicap for exact handicap restore.";
  static final String WHITE_TO_PLAY_WITHOUT_TAIL_DETAIL =
      "Remote exact snapshot restore cannot represent a white-to-play position with an empty real "
          + "tail.";
  private static final ScheduledExecutorService DELETE_EXECUTOR =
      Executors.newSingleThreadScheduledExecutor(ExactSnapshotEngineRestore::newCleanupThread);

  private ExactSnapshotEngineRestore() {}

  enum FailureCategory {
    ADMISSION_STALE,
    SNAPSHOT_PREPARATION,
    UNSUPPORTED_REMOTE_POSITION,
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

  /**
   * Preparation needs a different launcher or writable location, or the engine cannot represent the
   * position: a background retry cannot help.
   */
  public static boolean isPermanentFailure(Throwable failure) {
    return failure instanceof Failure exactFailure
        && (exactFailure.category() == FailureCategory.SNAPSHOT_PREPARATION
            || exactFailure.category() == FailureCategory.UNSUPPORTED_REMOTE_POSITION);
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
    } catch (RuntimeException | Error failure) {
      admission.completeBoardSync();
      throw failure;
    }
  }

  public static PreparedRestore prepareCurrentPosition(
      Leelaz.ExactSnapshotRestoreAdmission admission, BoardData positionData) {
    try {
      return new PreparedRestore(RestorePlan.capture(admission, positionData));
    } catch (RuntimeException | Error failure) {
      admission.completeBoardSync();
      throw failure;
    }
  }

  /**
   * Captures an exact restore plan for a displayed history position.
   *
   * <p>Unlike {@link #prepare(Leelaz.ExactSnapshotRestoreAdmission, BoardHistoryNode)}, this
   * variant treats an ordinary empty history root as a replay origin. That gives remote engines a
   * real move tail that preserves side-to-play without inventing a pass, while retaining strict GTP
   * acknowledgements for resource handoffs.
   */
  public static PreparedRestore prepareCurrentHistoryPosition(
      Leelaz.ExactSnapshotRestoreAdmission admission, BoardHistoryNode target) {
    try {
      return new PreparedRestore(RestorePlan.captureCurrentHistory(admission, target));
    } catch (RuntimeException | Error failure) {
      admission.completeBoardSync();
      throw failure;
    }
  }

  /** Board evidence frozen before admission; contains no engine or mutable history references. */
  public static final class FrozenHistoryPosition {
    private final BoardData snapshot;
    private final List<TailAction> tail;
    private final Double komi;
    private final boolean initialHandicap;
    private final boolean emptyOrigin;

    private FrozenHistoryPosition(BoardHistoryNode target, int handicap, Double komi) {
      SnapshotAnchor anchor = RestorePlan.findSnapshotAnchor(target);
      if (anchor == null) {
        BoardHistoryNode root = RestorePlan.findHistoryOrigin(target);
        anchor = new SnapshotAnchor(root, root.getData());
      }
      snapshot = anchor.data.clone();
      int[] size = resolveSnapshotBoardSize(snapshot);
      snapshot.addProperty("SZ", formatBoardSizeTag(size[0], size[1]));
      tail = List.copyOf(RestorePlan.captureTail(target, anchor.node, size[0], size[1]));
      this.komi = komi;
      initialHandicap =
          InitialHandicapSetup.isInitialHandicap(anchor.node, handicap, size[0], size[1])
              && !snapshot.blackToPlay;
      boolean empty = !anchor.node.previous().isPresent() && snapshot.blackToPlay;
      if (snapshot.stones == null) {
        empty = false;
      } else {
        for (Stone stone : snapshot.stones) {
          empty &= stone == Stone.EMPTY;
        }
      }
      emptyOrigin = empty;
    }

    public PreparedRestore prepare(Leelaz.ExactSnapshotRestoreAdmission admission) {
      try {
        admission.authority().requireExactSnapshotRestoreAdmission(admission);
        return new PreparedRestore(
            new RestorePlan(
                admission.authority(),
                admission.mirror(),
                snapshot,
                tail,
                komi,
                admission,
                initialHandicap,
                emptyOrigin));
      } catch (RuntimeException | Error failure) {
        admission.completeBoardSync();
        throw failure;
      }
    }
  }

  public static FrozenHistoryPosition freezeHistoryPosition(
      BoardHistoryNode target, int handicap, Double komi) {
    if (target == null) throw new IllegalArgumentException("target");
    return new FrozenHistoryPosition(target, handicap, komi);
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
    RestoreLifecycle lifecycle = null;
    try {
      List<Leelaz> remoteTargets = new ArrayList<>();
      List<Leelaz> localTargets = new ArrayList<>();
      for (Leelaz target : plan.targetEngines) {
        if (target.requiresInBandSnapshotRestore(plan.admission)) {
          if (plan.initialHandicap && !target.supportsHandicapSnapshotRestore(plan.admission)) {
            throw new Failure(
                FailureCategory.UNSUPPORTED_REMOTE_POSITION, NO_HANDICAP_RESTORE_DETAIL);
          }
          // Engines that cannot read host files (SSH, scripts, WSL, containers) restore an
          // arbitrary position in-band with set_position when they advertise it (KataGo does).
          if (!target.useRemoteCompute
              && !plan.initialHandicap
              && !plan.emptyOrigin
              && !target.supportsSetPositionSnapshotRestore(plan.admission)) {
            throw new Failure(
                FailureCategory.SNAPSHOT_PREPARATION, UNSUPPORTED_SNAPSHOT_TRANSPORT_DETAIL);
          }
          remoteTargets.add(target);
        } else {
          localTargets.add(target);
        }
      }
      requireSupportedRemotePosition(plan, remoteTargets);
      try {
        if (!localTargets.isEmpty()) {
          StagedSnapshot stagedSnapshot = stageSnapshotSgf(plan, localTargets);
          lifecycle = new RestoreLifecycle(stagedSnapshot);
          stagedSnapshot.requireCurrent(plan.admission);
          localTargets.get(0).beforeExactSnapshotPreclearForTest();
        }
        if (plan.preclear) {
          clearCapturedTargets(
              plan, lifecycle == null ? null : lifecycle.stagedSnapshot, remoteTargets);
        }
        if (!remoteTargets.isEmpty()) {
          restoreRemoteSnapshotInBand(plan, remoteTargets);
        }
        if (!localTargets.isEmpty()) {
          restoreLocalSnapshotSgf(plan, localTargets, lifecycle);
        }
      } catch (RuntimeException failure) {
        if (lifecycle != null) {
          lifecycle.failBeforeLoadDispatch();
        }
        throw failure;
      }
      for (TailAction action : plan.tail) {
        if (!remoteTargets.isEmpty()) {
          restoreCommandWithResponse(plan, remoteTargets, action.command);
        }
        if (!localTargets.isEmpty()) {
          restoreCommandWithResponse(plan, localTargets, action.command);
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

  private static void requireSupportedRemotePosition(RestorePlan plan, List<Leelaz> remoteTargets) {
    if (remoteTargets.isEmpty()
        || plan.initialHandicap
        || plan.snapshotData.blackToPlay
        || !plan.tail.isEmpty()) {
      return;
    }
    // set_position leaves Black to play. A white-to-play position without a real tail is only
    // representable when later analysis and move commands name the side to play explicitly.
    for (Leelaz target : remoteTargets) {
      if (!target.namesSideToPlayInCommands()) {
        throw new Failure(
            FailureCategory.UNSUPPORTED_REMOTE_POSITION, WHITE_TO_PLAY_WITHOUT_TAIL_DETAIL);
      }
    }
  }

  private static void restoreRemoteSnapshotInBand(RestorePlan plan, List<Leelaz> remoteTargets) {
    restoreCommandWithResponse(plan, remoteTargets, buildBoardSizeCommand(plan));
    if ((plan.initialHandicap || plan.emptyOrigin) && !plan.preclear) {
      restoreCommandWithResponse(plan, remoteTargets, "clear_board");
    }
    List<String> commands = new ArrayList<>(2);
    if (plan.komi != null) {
      commands.add("komi " + formatKomi(plan.komi));
    }
    if (plan.initialHandicap) {
      StringBuilder handicap = new StringBuilder("set_free_handicap");
      for (int index = 0; index < plan.snapshotData.stones.length; index++) {
        if (plan.snapshotData.stones[index] == Stone.BLACK) {
          int[] coord = toBoardCoord(index, plan.boardHeight);
          handicap
              .append(' ')
              .append(formatGtpCoord(coord[0], coord[1], plan.boardWidth, plan.boardHeight));
        }
      }
      commands.add(handicap.toString());
    } else if (!plan.emptyOrigin) {
      commands.add(buildSetPositionCommand(plan));
    }
    if (!commands.isEmpty()) {
      restoreCommandsWithResponse(plan, remoteTargets, commands);
    }
  }

  private static void restoreCommandWithResponse(
      RestorePlan plan, List<Leelaz> targets, String command) {
    restoreCommandsWithResponse(plan, targets, List.of(command));
  }

  private static void restoreCommandsWithResponse(
      RestorePlan plan, List<Leelaz> targets, List<String> commands) {
    Leelaz loadEngine = targets.get(0);
    Leelaz loadMirror = targets.size() > 1 ? targets.get(1) : null;
    loadEngine.withExactSnapshotRestoreAdmission(
        plan.admission,
        () ->
            loadEngine.restoreInBandForExactSnapshotRestore(
                commands, loadMirror, plan.admission, () -> {}, null));
  }

  private static String buildBoardSizeCommand(RestorePlan plan) {
    return plan.boardWidth == plan.boardHeight
        ? "boardsize " + plan.boardWidth
        : "rectangular_boardsize " + plan.boardWidth + " " + plan.boardHeight;
  }

  private static void restoreLocalSnapshotSgf(
      RestorePlan plan, List<Leelaz> localTargets, RestoreLifecycle lifecycle) {
    Leelaz loadEngine = localTargets.get(0);
    Leelaz loadMirror = localTargets.size() > 1 ? localTargets.get(1) : null;
    StagedTargetFile authorityFile = lifecycle.stagedSnapshot.forEngine(loadEngine);
    StagedTargetFile mirrorFile =
        loadMirror == null ? null : lifecycle.stagedSnapshot.forEngine(loadMirror);
    loadEngine.withExactSnapshotRestoreAdmission(
        plan.admission,
        () ->
            loadEngine.loadSgfForExactSnapshotRestore(
                authorityFile.physicalPath,
                authorityFile.gtpFileName,
                authorityFile.fileAccess,
                loadMirror,
                mirrorFile == null ? null : mirrorFile.physicalPath,
                mirrorFile == null ? null : mirrorFile.gtpFileName,
                mirrorFile == null ? null : mirrorFile.fileAccess,
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

  private static void clearCapturedTargets(
      RestorePlan plan, StagedSnapshot stagedSnapshot, List<Leelaz> inBandTargets) {
    for (Leelaz targetEngine : plan.targetEngines) {
      // In-band targets never read the staged file, so they have no file access to verify.
      Leelaz.SnapshotFileAccess fileAccess =
          targetEngine.useRemoteCompute
                  || stagedSnapshot == null
                  || inBandTargets.contains(targetEngine)
              ? null
              : stagedSnapshot.forEngine(targetEngine).fileAccess;
      sendCapturedRestoreCommand(plan, targetEngine, fileAccess, "clear_board", "preclear");
      targetEngine.onCapturedRestoreClearCommandSent();
    }
  }

  private static void sendCapturedRestoreCommand(
      RestorePlan plan,
      Leelaz target,
      Leelaz.SnapshotFileAccess fileAccess,
      String command,
      String phase) {
    final RuntimeException[] failure = new RuntimeException[1];
    target.withExactSnapshotRestoreAdmission(
        plan.admission,
        () -> {
          if (!target.sendCommandToCapturedRestoreTarget(command, plan.admission, fileAccess)) {
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

  private static StagedSnapshot stageSnapshotSgf(RestorePlan plan, List<Leelaz> localTargets) {
    List<SnapshotTargetAccess> targetAccesses = new ArrayList<>(localTargets.size());
    for (Leelaz target : localTargets) {
      targetAccesses.add(
          new SnapshotTargetAccess(target, target.captureSnapshotFileAccess(plan.admission)));
    }

    String sgf = buildSnapshotSgf(plan);
    if (TrialDiag.ENABLED) {
      System.out.println("[trial-sgf] " + sgf);
    }
    List<Throwable> failures = new ArrayList<>();
    Path defaultTempDirectory = configuredTempDirectory();
    StagedSnapshot staged =
        tryStageSharedAbsolute(targetAccesses, defaultTempDirectory, sgf, false, failures);
    if (staged == null) {
      staged = tryStageWorkingDirectoryRelative(targetAccesses, sgf, failures);
    }
    if (staged == null) {
      for (Path fallbackDirectory : sharedFallbackDirectories()) {
        staged = tryStageSharedAbsolute(targetAccesses, fallbackDirectory, sgf, true, failures);
        if (staged != null) {
          break;
        }
      }
    }
    if (staged == null) {
      IOException cause =
          new IOException("No safe writable snapshot directory was available to every engine.");
      failures.forEach(cause::addSuppressed);
      throw new Failure(
          FailureCategory.SNAPSHOT_PREPARATION, NO_WRITABLE_SNAPSHOT_LOCATION_DETAIL, cause);
    }
    try {
      staged.requireCurrent(plan.admission);
      return staged;
    } catch (RuntimeException stale) {
      staged.deleteImmediately(stale);
      throw stale;
    }
  }

  private static Path configuredTempDirectory() {
    String configured = System.getProperty("java.io.tmpdir");
    if (configured == null || configured.isBlank()) {
      return null;
    }
    try {
      return Path.of(configured).toAbsolutePath().normalize();
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  private static StagedSnapshot tryStageSharedAbsolute(
      List<SnapshotTargetAccess> targets,
      Path directory,
      String sgf,
      boolean createDirectory,
      List<Throwable> failures) {
    if (directory == null
        || !isSafeGtpFileName(directory.toAbsolutePath().normalize().toString())) {
      return null;
    }
    Path snapshotFile = null;
    try {
      if (createDirectory) {
        Files.createDirectories(directory);
      }
      snapshotFile = writeSnapshotFile(directory, sgf);
      String gtpFileName = snapshotFile.toAbsolutePath().normalize().toString();
      if (!isSafeGtpFileName(gtpFileName)) {
        throw new IOException("Snapshot absolute path is not safe for GTP: " + gtpFileName);
      }
      List<StagedTargetFile> targetFiles = new ArrayList<>(targets.size());
      for (SnapshotTargetAccess target : targets) {
        targetFiles.add(
            new StagedTargetFile(target.engine, target.fileAccess, snapshotFile, gtpFileName));
      }
      return new StagedSnapshot(targetFiles, Set.of(snapshotFile));
    } catch (IOException | RuntimeException failure) {
      deleteFailedSnapshotFile(snapshotFile, failure);
      failures.add(failure);
      return null;
    }
  }

  private static StagedSnapshot tryStageWorkingDirectoryRelative(
      List<SnapshotTargetAccess> targets, String sgf, List<Throwable> failures) {
    for (SnapshotTargetAccess target : targets) {
      if (target.fileAccess.provenWorkingDirectory() == null) {
        return null;
      }
    }
    Map<Path, Path> filesByDirectory = new LinkedHashMap<>();
    List<StagedTargetFile> targetFiles = new ArrayList<>(targets.size());
    try {
      for (SnapshotTargetAccess target : targets) {
        Path workingDirectory = target.fileAccess.provenWorkingDirectory();
        Path snapshotFile = filesByDirectory.get(workingDirectory);
        if (snapshotFile == null) {
          snapshotFile = writeSnapshotFile(workingDirectory, sgf);
          filesByDirectory.put(workingDirectory, snapshotFile);
        }
        String gtpFileName = snapshotFile.getFileName().toString();
        if (!isSafeGtpFileName(gtpFileName)) {
          throw new IOException("Snapshot relative path is not safe for GTP: " + gtpFileName);
        }
        targetFiles.add(
            new StagedTargetFile(target.engine, target.fileAccess, snapshotFile, gtpFileName));
      }
      return new StagedSnapshot(targetFiles, new LinkedHashSet<>(filesByDirectory.values()));
    } catch (IOException | RuntimeException failure) {
      for (Path snapshotFile : filesByDirectory.values()) {
        deleteFailedSnapshotFile(snapshotFile, failure);
      }
      failures.add(failure);
      return null;
    }
  }

  private static Path writeSnapshotFile(Path directory, String sgf) throws IOException {
    Path snapshotFile = null;
    try {
      snapshotFile = Files.createTempFile(directory, "lizzie-snapshot-", ".sgf");
      Files.writeString(snapshotFile, sgf, StandardCharsets.UTF_8);
      if (!Files.isRegularFile(snapshotFile)
          || !Files.isReadable(snapshotFile)
          || Files.size(snapshotFile) == 0L) {
        throw new IOException("Snapshot SGF was not written completely: " + snapshotFile);
      }
      return snapshotFile;
    } catch (IOException | RuntimeException failure) {
      deleteFailedSnapshotFile(snapshotFile, failure);
      throw failure;
    }
  }

  private static List<Path> sharedFallbackDirectories() {
    LinkedHashSet<Path> directories = new LinkedHashSet<>();
    if (Lizzie.config != null) {
      try {
        directories.add(
            Lizzie.config.getRuntimeWorkDirectory().toPath().toAbsolutePath().normalize());
      } catch (RuntimeException ignored) {
      }
    }
    addWindowsFallback(
        directories, System.getenv("PUBLIC"), "Documents", "LizzieYzyNext", "runtime");
    addWindowsFallback(directories, System.getenv("PUBLIC"), "LizzieYzyNext", "runtime");
    addWindowsFallback(directories, System.getenv("ProgramData"), "LizzieYzyNext", "runtime");
    return List.copyOf(directories);
  }

  private static void addWindowsFallback(Set<Path> directories, String root, String... children) {
    if (root == null || root.isBlank()) {
      return;
    }
    try {
      Path directory = Path.of(root);
      for (String child : children) {
        directory = directory.resolve(child);
      }
      directories.add(directory.toAbsolutePath().normalize());
    } catch (RuntimeException ignored) {
    }
  }

  private static boolean isSafeGtpFileName(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 33 || character > 126 || character == '#') {
        return false;
      }
    }
    return true;
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
    private final boolean initialHandicap;
    private final boolean emptyOrigin;

    private RestorePlan(
        Leelaz engine,
        Leelaz mirrorEngine,
        BoardData snapshotData,
        List<TailAction> tail,
        Double restoreKomi,
        Leelaz.ExactSnapshotRestoreAdmission admission,
        boolean initialHandicap,
        boolean emptyOrigin) {
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
      this.initialHandicap = initialHandicap;
      this.emptyOrigin = emptyOrigin;
    }

    private static Optional<RestorePlan> capture(
        Leelaz.ExactSnapshotRestoreAdmission admission, BoardHistoryNode target) {
      return capture(admission, target, false);
    }

    private static RestorePlan captureCurrentHistory(
        Leelaz.ExactSnapshotRestoreAdmission admission, BoardHistoryNode target) {
      return capture(admission, target, true)
          .orElseThrow(() -> new IllegalStateException("History position has no replay origin."));
    }

    private static Optional<RestorePlan> capture(
        Leelaz.ExactSnapshotRestoreAdmission admission,
        BoardHistoryNode target,
        boolean allowHistoryOrigin) {
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
      if (anchor == null && allowHistoryOrigin) {
        BoardHistoryNode origin = findHistoryOrigin(target);
        BoardData originData = origin.getData();
        validateCurrentPosition(originData);
        anchor = new SnapshotAnchor(origin, originData);
      }
      if (anchor == null) {
        return Optional.empty();
      }
      BoardHistoryList history = Lizzie.board == null ? null : Lizzie.board.getHistory();
      int handicap =
          history == null
                  || history.getGameInfo() == null
                  || history.getStart() != findHistoryOrigin(target)
              ? 0
              : history.getGameInfo().getHandicap();
      FrozenHistoryPosition frozen = freezeHistoryPosition(target, handicap, captureHistoryKomi());
      return Optional.of(
          new RestorePlan(
              engine,
              mirrorEngine,
              frozen.snapshot,
              frozen.tail,
              frozen.komi,
              admission,
              frozen.initialHandicap,
              frozen.emptyOrigin));
    }

    private static BoardHistoryNode findHistoryOrigin(BoardHistoryNode target) {
      BoardHistoryNode origin = target;
      while (origin.previous().isPresent()) {
        origin = origin.previous().get();
      }
      return origin;
    }

    private static RestorePlan capture(
        Leelaz.ExactSnapshotRestoreAdmission admission, BoardData sourceData) {
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
          admission,
          false,
          false);
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

  private static final class SnapshotTargetAccess {
    private final Leelaz engine;
    private final Leelaz.SnapshotFileAccess fileAccess;

    private SnapshotTargetAccess(Leelaz engine, Leelaz.SnapshotFileAccess fileAccess) {
      this.engine = engine;
      this.fileAccess = fileAccess;
    }
  }

  private static final class StagedTargetFile {
    private final Leelaz engine;
    private final Leelaz.SnapshotFileAccess fileAccess;
    private final Path physicalPath;
    private final String gtpFileName;

    private StagedTargetFile(
        Leelaz engine,
        Leelaz.SnapshotFileAccess fileAccess,
        Path physicalPath,
        String gtpFileName) {
      this.engine = engine;
      this.fileAccess = fileAccess;
      this.physicalPath = physicalPath;
      this.gtpFileName = gtpFileName;
    }
  }

  private static final class StagedSnapshot {
    private final List<StagedTargetFile> targetFiles;
    private final Set<Path> ownedFiles;

    private StagedSnapshot(List<StagedTargetFile> targetFiles, Set<Path> ownedFiles) {
      this.targetFiles = List.copyOf(targetFiles);
      this.ownedFiles = Set.copyOf(ownedFiles);
    }

    private StagedTargetFile forEngine(Leelaz engine) {
      for (StagedTargetFile targetFile : targetFiles) {
        if (targetFile.engine == engine) {
          return targetFile;
        }
      }
      throw new IllegalArgumentException("Engine is not part of the staged snapshot");
    }

    private void requireCurrent(Leelaz.ExactSnapshotRestoreAdmission admission) {
      for (StagedTargetFile targetFile : targetFiles) {
        targetFile.engine.requireSnapshotFileAccessCurrent(targetFile.fileAccess, admission);
      }
    }

    private void deleteImmediately(Throwable failure) {
      for (Path ownedFile : ownedFiles) {
        deleteFailedSnapshotFile(ownedFile, failure);
      }
    }

    private void delete() {
      for (Path ownedFile : ownedFiles) {
        deleteSnapshotFile(ownedFile, 0);
      }
    }
  }

  private static final class RestoreLifecycle {
    private final StagedSnapshot stagedSnapshot;
    private final AtomicBoolean loadSgfConsumed = new AtomicBoolean(false);
    private final AtomicBoolean loadDispatchStarted = new AtomicBoolean(false);
    private final AtomicBoolean tailReplayFinished = new AtomicBoolean(false);
    private final AtomicBoolean deleteStarted = new AtomicBoolean(false);

    private RestoreLifecycle(StagedSnapshot stagedSnapshot) {
      this.stagedSnapshot = stagedSnapshot;
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
      stagedSnapshot.delete();
    }
  }
}
