package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerStrengthEstimatorTest {
  private static final int BOARD_SIZE = 9;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;
  private static final String XGBOOST20TUN_BOOSTER_RESOURCE =
      "/models/strength/xgboost20tun_booster.json";
  private static final String XGBOOST20TUN_CALIBRATOR_RESOURCE =
      "/models/strength/xgboost20tun_residual_calibrator.json";

  private int previousBoardWidth;
  private int previousBoardHeight;
  private PlayerStrengthEstimator.StrengthModel previousStrengthModel;
  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    previousBoardWidth = Board.boardWidth;
    previousBoardHeight = Board.boardHeight;
    previousStrengthModel = PlayerStrengthEstimator.activeModel();
    Board.boardWidth = BOARD_SIZE;
    Board.boardHeight = BOARD_SIZE;
    PlayerStrengthEstimator.setActiveModel(PlayerStrengthEstimator.StrengthModel.XGBOOST20TUN);
  }

  @AfterEach
  void tearDown() {
    Board.boardWidth = previousBoardWidth;
    Board.boardHeight = previousBoardHeight;
    PlayerStrengthEstimator.setActiveModel(previousStrengthModel);
  }

  @Test
  void exposesCurrentAndPreviousBundledTunModels() {
    boolean foundCurrent = false;
    boolean foundPrevious = false;
    for (PlayerStrengthEstimator.StrengthModel model : PlayerStrengthEstimator.selectableModels()) {
      foundCurrent |= model.equals(PlayerStrengthEstimator.StrengthModel.XGBOOST20TUN);
      foundPrevious |= model.equals(PlayerStrengthEstimator.StrengthModel.XGBOOST20TUN_PREVIOUS);
    }

    assertTrue(foundCurrent);
    assertTrue(foundPrevious);
  }

  @Test
  void importsStandaloneBoosterWithSiblingMetadata() throws Exception {
    try (StrengthModelTestEnvironment env =
        StrengthModelTestEnvironment.open(tempDir.resolve("standalone-work"))) {
      Path sourceDir = Files.createDirectories(tempDir.resolve("standalone-source"));
      Path booster = sourceDir.resolve("custom_booster.json");
      copyBundledResource(XGBOOST20TUN_BOOSTER_RESOURCE, booster);
      Files.writeString(
          sourceDir.resolve("metadata.json"),
          metadataJson(
              "Custom Tun Model", XGBoostStrengthModel.Features.defaultXGBoost20TunFeatureOrder()));

      PlayerStrengthEstimator.StrengthModel imported =
          PlayerStrengthEstimator.importStrengthModel(booster);

      assertEquals("Custom Tun Model", imported.displayName());
      assertEquals(1, modelDirectoryCount(env.modelRoot()));
      assertTrue(selectableModelsContain(imported));
    }
  }

  @Test
  void importsZipModelBundleWithOptionalCalibrator() throws Exception {
    try (StrengthModelTestEnvironment env =
        StrengthModelTestEnvironment.open(tempDir.resolve("zip-work"))) {
      Path bundle = tempDir.resolve("strength-model.zip");
      try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundle))) {
        writeZipEntry(
            zip, "model/booster.json", bundledResourceBytes(XGBOOST20TUN_BOOSTER_RESOURCE));
        writeZipEntry(
            zip,
            "model/metadata.json",
            metadataJson(
                    "Zip Tun Model",
                    XGBoostStrengthModel.Features.defaultXGBoost20TunFeatureOrder())
                .getBytes());
        writeZipEntry(
            zip, "model/calibrator.json", bundledResourceBytes(XGBOOST20TUN_CALIBRATOR_RESOURCE));
      }

      PlayerStrengthEstimator.StrengthModel imported =
          PlayerStrengthEstimator.importStrengthModel(bundle);

      assertEquals("Zip Tun Model", imported.displayName());
      assertEquals(1, modelDirectoryCount(env.modelRoot()));
      assertTrue(selectableModelsContain(imported));
    }
  }

  @Test
  void failedZipImportRemovesPartialModelDirectory() throws Exception {
    try (StrengthModelTestEnvironment env =
        StrengthModelTestEnvironment.open(tempDir.resolve("failed-work"))) {
      Path bundle = tempDir.resolve("bad-strength-model.zip");
      try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundle))) {
        writeZipEntry(zip, "booster.json", bundledResourceBytes(XGBOOST20TUN_BOOSTER_RESOURCE));
        writeZipEntry(
            zip,
            "metadata.json",
            metadataJson("Bad Feature Model", new String[] {"not_a_known_strength_feature"})
                .getBytes());
      }

      assertThrows(IOException.class, () -> PlayerStrengthEstimator.importStrengthModel(bundle));
      assertEquals(0, modelDirectoryCount(env.modelRoot()));
    }
  }

  @Test
  void estimatesCandidateBasedReviewMetricsBySide() {
    BoardHistoryList history =
        new BoardHistoryList(
            analyzedRoot(true, 60.0, 3.0, candidate("A9", 60.0, 3.0), candidate("B9", 58.0, 2.2)));
    history.add(
        analyzedMove(
            0,
            0,
            Stone.BLACK,
            false,
            1,
            40.0,
            -3.0,
            candidate("D9", 65.0, 5.0),
            candidate("C9", 50.0, 0.0)));
    history.add(analyzedMove(2, 0, Stone.WHITE, true, 2, 75.0, 8.0, candidate("C8", 55.0, 1.0)));

    PlayerStrengthEstimator.Report report = PlayerStrengthEstimator.estimate(history.getStart());

    assertEquals(1, report.black.sampleCount);
    assertEquals(0.0, report.black.averageWinrateLoss, 0.0001);
    assertEquals(0.0, report.black.averageScoreLoss, 0.0001);
    assertEquals(0.0, report.black.weightedScoreLoss, 0.0001);
    assertEquals(1.0, report.black.firstChoiceRate, 0.0001);
    assertEquals(1.0, report.black.goodMoveRate, 0.0001);
    assertEquals("12d AI", report.black.strengthBand);

    assertEquals(1, report.white.sampleCount);
    assertEquals(15.0, report.white.averageWinrateLoss, 0.0001);
    assertEquals(5.0, report.white.averageScoreLoss, 0.0001);
    assertEquals(5.0, report.white.weightedScoreLoss, 0.0001);
    assertEquals(0.0, report.white.firstChoiceRate, 0.0001);
    assertEquals(0.0, report.white.goodMoveRate, 0.0001);
    assertEquals(1.0, report.white.mistakeRate, 0.0001);
    assertEquals("11-15k", report.white.strengthBand);
  }

  @Test
  void keepsWorldClassLikeLowLossGamesInTopBand() {
    BoardHistoryList history =
        new BoardHistoryList(
            analyzedRoot(true, 58.0, 2.0, candidate("A9", 58.0, 2.0), candidate("B9", 57.5, 1.8)));
    history.add(
        analyzedMove(
            0,
            0,
            Stone.BLACK,
            false,
            1,
            42.0,
            -2.0,
            candidate("B9", 57.0, 2.3),
            candidate("C9", 56.7, 2.0)));
    history.add(
        analyzedMove(
            1,
            0,
            Stone.WHITE,
            true,
            2,
            57.0,
            2.2,
            candidate("C9", 58.0, 2.4),
            candidate("D9", 57.6, 2.1)));
    history.add(
        analyzedMove(
            2,
            0,
            Stone.BLACK,
            false,
            3,
            43.0,
            -2.1,
            candidate("D9", 57.5, 2.2),
            candidate("E9", 57.0, 1.9)));
    history.add(
        analyzedMove(
            3,
            0,
            Stone.WHITE,
            true,
            4,
            56.0,
            1.9,
            candidate("E9", 58.0, 2.1),
            candidate("F9", 57.9, 2.0)));
    history.add(
        analyzedMove(
            4,
            0,
            Stone.BLACK,
            false,
            5,
            44.0,
            -2.0,
            candidate("F9", 57.0, 1.9),
            candidate("G9", 56.8, 1.7)));
    history.add(
        analyzedMove(
            5,
            0,
            Stone.WHITE,
            true,
            6,
            56.5,
            1.8,
            candidate("G9", 58.0, 2.0),
            candidate("H9", 57.8, 1.9)));

    PlayerStrengthEstimator.Report report = PlayerStrengthEstimator.estimate(history.getStart());

    assertEquals(6, report.overall.sampleCount);
    assertEquals(1.0, report.overall.goodMoveRate, 0.0001);
    assertEquals(0.0, report.overall.mistakeRate, 0.0001);
    assertEquals("12d AI", report.overall.strengthBand);
  }

  @Test
  void keepsChampionLikeReviewProfileInTopBand() {
    double[] losses = championLikeLosses();
    BoardHistoryList history =
        new BoardHistoryList(
            analyzedRoot(true, 50.0, 0.0, candidatesFor(coordinate(0), losses[0])));
    for (int index = 0; index < losses.length; index++) {
      Stone color = index % 2 == 0 ? Stone.BLACK : Stone.WHITE;
      int nextIndex = Math.min(index + 1, losses.length - 1);
      history.add(
          analyzedMove(
              index % BOARD_SIZE,
              index / BOARD_SIZE,
              color,
              color.isWhite(),
              index + 1,
              50.0,
              0.0,
              candidatesFor(coordinate(nextIndex), losses[nextIndex])));
    }

    PlayerStrengthEstimator.Report report = PlayerStrengthEstimator.estimate(history.getStart());

    assertEquals(51, report.overall.sampleCount);
    assertEquals(0.51, report.overall.firstChoiceRate, 0.01);
    assertEquals(0.88, report.overall.goodMoveRate, 0.01);
    assertEquals(0.04, report.overall.mistakeRate, 0.01);
    assertEquals(0.0, report.overall.medianScoreLoss, 0.0001);
    assertEquals("11d\u4e00\u7ebf\u804c\u4e1a", report.overall.strengthBand);
  }

  @Test
  void keepsHighDanProfileFromBeingCollapsedByLowDifficultyGuard() {
    double[] losses = lowDifficultyHighDanLosses();
    BoardHistoryList history =
        new BoardHistoryList(
            analyzedRoot(true, 50.0, 0.0, candidatesFor(coordinate(0), losses[0])));
    for (int index = 0; index < losses.length; index++) {
      Stone color = index % 2 == 0 ? Stone.BLACK : Stone.WHITE;
      int nextIndex = Math.min(index + 1, losses.length - 1);
      history.add(
          analyzedMove(
              index % BOARD_SIZE,
              index / BOARD_SIZE,
              color,
              color.isWhite(),
              index + 1,
              50.0,
              0.0,
              candidatesFor(coordinate(nextIndex), losses[nextIndex])));
    }

    PlayerStrengthEstimator.Report report = PlayerStrengthEstimator.estimate(history.getStart());

    assertEquals(56, report.overall.sampleCount);
    assertEquals(0.46, report.overall.firstChoiceRate, 0.01);
    assertEquals(0.79, report.overall.goodMoveRate, 0.01);
    assertEquals(0.98, report.overall.moveRankGoodMoveRate, 0.01);
    assertEquals(0.02, report.overall.mistakeRate, 0.01);
    assertEquals("9d", report.overall.strengthBand);
  }

  @Test
  void keepsSolidAmateurProfilesInLowDanRange() {
    double[] losses = solidAmateurLosses();
    BoardHistoryList history =
        new BoardHistoryList(
            analyzedRoot(true, 50.0, 0.0, candidatesFor(coordinate(0), losses[0])));
    for (int index = 0; index < losses.length; index++) {
      Stone color = index % 2 == 0 ? Stone.BLACK : Stone.WHITE;
      int nextIndex = Math.min(index + 1, losses.length - 1);
      history.add(
          analyzedMove(
              index % BOARD_SIZE,
              index / BOARD_SIZE,
              color,
              color.isWhite(),
              index + 1,
              50.0,
              0.0,
              candidatesFor(coordinate(nextIndex), losses[nextIndex])));
    }

    PlayerStrengthEstimator.Report report = PlayerStrengthEstimator.estimate(history.getStart());

    assertEquals(51, report.overall.sampleCount);
    assertEquals(0.31, report.overall.firstChoiceRate, 0.01);
    assertEquals(0.75, report.overall.goodMoveRate, 0.01);
    assertEquals(0.12, report.overall.mistakeRate, 0.01);
    assertEquals("3-4d", report.overall.strengthBand);
  }

  @Test
  void keepsModerateDanEvidenceProfilesBelowHighDan() {
    double[] losses = lowLossHighRankLosses();
    BoardHistoryList history =
        new BoardHistoryList(
            analyzedRoot(true, 50.0, 0.0, candidatesFor(coordinate(0), losses[0])));
    for (int index = 0; index < losses.length; index++) {
      Stone color = index % 2 == 0 ? Stone.BLACK : Stone.WHITE;
      int nextIndex = Math.min(index + 1, losses.length - 1);
      history.add(
          analyzedMove(
              index % BOARD_SIZE,
              index / BOARD_SIZE,
              color,
              color.isWhite(),
              index + 1,
              50.0,
              0.0,
              candidatesFor(coordinate(nextIndex), losses[nextIndex])));
    }

    PlayerStrengthEstimator.Report report = PlayerStrengthEstimator.estimate(history.getStart());

    assertEquals(65, report.overall.sampleCount);
    assertEquals(0.43, report.overall.firstChoiceRate, 0.01);
    assertEquals(0.72, report.overall.goodMoveRate, 0.01);
    assertEquals(0.11, report.overall.mistakeRate, 0.01);
    assertEquals("7d", report.overall.strengthBand);
  }

  @Test
  void capsWeakDanEvidenceProfilesBelowDan() {
    double[] losses = weakDanEvidenceLosses();
    BoardHistoryList history =
        new BoardHistoryList(
            analyzedRoot(true, 50.0, 0.0, candidatesFor(coordinate(0), losses[0])));
    for (int index = 0; index < losses.length; index++) {
      Stone color = index % 2 == 0 ? Stone.BLACK : Stone.WHITE;
      int nextIndex = Math.min(index + 1, losses.length - 1);
      history.add(
          analyzedMove(
              index % BOARD_SIZE,
              index / BOARD_SIZE,
              color,
              color.isWhite(),
              index + 1,
              50.0,
              0.0,
              candidatesFor(coordinate(nextIndex), losses[nextIndex])));
    }

    PlayerStrengthEstimator.Report report = PlayerStrengthEstimator.estimate(history.getStart());

    assertEquals(50, report.overall.sampleCount);
    assertEquals(0.28, report.overall.firstChoiceRate, 0.01);
    assertEquals(0.60, report.overall.goodMoveRate, 0.01);
    assertEquals(0.10, report.overall.mistakeRate, 0.01);
    assertEquals("1-2k", report.overall.strengthBand);
  }

  @Test
  void fallsBackToHeaderWinrateWhenCandidateScoreIsMissing() {
    BoardHistoryList history =
        new BoardHistoryList(analyzedRoot(false, 60.0, 0.0, candidate("B9", 0.0, 0.0, false)));
    history.add(
        analyzedMove(0, 0, Stone.BLACK, true, 1, 50.0, 0.0, candidate("C9", 0.0, 0.0, false)));

    PlayerStrengthEstimator.Report report = PlayerStrengthEstimator.estimate(history.getStart());

    assertEquals(1, report.black.sampleCount);
    assertEquals(0, report.black.scoreSampleCount);
    assertEquals(10.0, report.black.averageWinrateLoss, 0.0001);
    assertEquals("-", report.black.averageScoreLossText());
  }

  @Test
  void keepsModelGoodMoveRateStableButExcludesHighWinrateLossFromDisplayedGoodMoves() {
    BoardHistoryList history =
        new BoardHistoryList(
            analyzedRoot(
                true, 70.0, 10.0, candidate("pass", 70.0, 10.0), candidate("A9", 50.0, 9.9)));
    history.add(analyzedMove(0, 0, Stone.BLACK, false, 1, 30.0, -9.9));

    PlayerStrengthEstimator.Report report = PlayerStrengthEstimator.estimate(history.getStart());

    assertEquals(1, report.black.sampleCount);
    assertEquals(20.0, report.black.averageWinrateLoss, 0.0001);
    assertEquals(0.1, report.black.averageScoreLoss, 0.0001);
    assertEquals(1.0, report.black.goodMoveRate, 0.0001);
    assertEquals(0.0, report.black.moveRankGoodMoveRate, 0.0001);
    assertEquals(1, report.black.moveRankCount(MoveRankDefinition.Rank.MISTAKE));
    assertEquals(0.0, report.black.mistakeRate, 0.0001);
  }

  @Test
  void keepsFirstChoiceRateStrictWhenSecondCandidateIsDisplayedAsBest() {
    MoveData topMove = candidate("pass", 70.0, 10.0);
    MoveData playedMove = candidate("A9", 69.95, 9.95);
    playedMove.order = 1;
    BoardHistoryList history =
        new BoardHistoryList(analyzedRoot(true, 70.0, 10.0, topMove, playedMove));
    history.add(analyzedMove(0, 0, Stone.BLACK, false, 1, 30.05, -9.95));

    PlayerStrengthEstimator.Report report = PlayerStrengthEstimator.estimate(history.getStart());

    assertEquals(1, report.black.sampleCount);
    assertEquals(0.0, report.black.firstChoiceRate, 0.0001);
    assertEquals(1.0, report.black.goodMoveRate, 0.0001);
    assertEquals(1.0, report.black.moveRankGoodMoveRate, 0.0001);
    assertEquals(1, report.black.moveRankCount(MoveRankDefinition.Rank.BEST));
    assertEquals("A9", report.black.samples.get(0).coordinate);
    assertEquals(1, report.black.samples.get(0).aiRank);
  }

  @Test
  void reportsInsufficientDataWhenNoAnalyzedMovePairsExist() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(rawMove(0, 0, Stone.BLACK, false, 1));

    PlayerStrengthEstimator.Report report = PlayerStrengthEstimator.estimate(history.getStart());

    assertEquals(0, report.overall.sampleCount);
    assertFalse(report.hasEnoughData());
  }

  private static BoardData analyzedRoot(
      boolean blackToPlay, double winrate, double scoreMean, MoveData... bestMoves) {
    BoardData data =
        BoardData.snapshot(
            emptyStones(),
            Optional.empty(),
            Stone.EMPTY,
            blackToPlay,
            new Zobrist(),
            0,
            new int[BOARD_AREA],
            0,
            0,
            winrate,
            100);
    seedAnalysis(data, winrate, scoreMean, bestMoves);
    return data;
  }

  private static BoardData analyzedMove(
      int x,
      int y,
      Stone color,
      boolean blackToPlay,
      int moveNumber,
      double winrate,
      double scoreMean,
      MoveData... bestMoves) {
    BoardData data = rawMove(x, y, color, blackToPlay, moveNumber);
    seedAnalysis(data, winrate, scoreMean, bestMoves);
    return data;
  }

  private static BoardData rawMove(int x, int y, Stone color, boolean blackToPlay, int moveNumber) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(x, y)] = color;
    return BoardData.move(
        stones,
        new int[] {x, y},
        color,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        moveNumberList(x, y, moveNumber),
        0,
        0,
        50.0,
        0);
  }

  private static void seedAnalysis(
      BoardData data, double winrate, double scoreMean, MoveData... bestMoves) {
    data.winrate = winrate;
    data.scoreMean = scoreMean;
    data.engineName = "TestEngine";
    data.analysisHeaderSlots = 1;
    data.setPlayouts(100);
    data.isKataData = false;
    for (MoveData move : bestMoves) {
      data.isKataData = data.isKataData || move.isKataData;
      data.bestMoves.add(move);
    }
  }

  private static MoveData candidate(String coordinate, double winrate, double scoreMean) {
    return candidate(coordinate, winrate, scoreMean, true);
  }

  private static MoveData candidate(
      String coordinate, double winrate, double scoreMean, boolean kataScore) {
    MoveData move = new MoveData();
    move.coordinate = coordinate;
    move.winrate = winrate;
    move.scoreMean = scoreMean;
    move.scoreStdev = 8.0;
    move.policy = 10.0;
    move.playouts = kataScore ? 100 : 0;
    move.isKataData = kataScore;
    return move;
  }

  private static MoveData[] candidatesFor(String actualCoordinate, double scoreLoss) {
    if (scoreLoss <= 0.0) {
      return new MoveData[] {candidate(actualCoordinate, 50.0, 10.0), candidate("pass", 49.0, 9.8)};
    }
    return new MoveData[] {
      candidate("pass", 50.0, 10.0), candidate(actualCoordinate, 50.0 - scoreLoss, 10.0 - scoreLoss)
    };
  }

  private static double[] championLikeLosses() {
    double[] losses = new double[51];
    for (int index = 26; index < 45; index++) {
      losses[index] = 0.8;
    }
    for (int index = 45; index < 49; index++) {
      losses[index] = 3.0;
    }
    losses[49] = 7.0;
    losses[50] = 7.0;
    return losses;
  }

  private static double[] lowDifficultyHighDanLosses() {
    double[] losses = new double[56];
    for (int index = 26; index < 44; index++) {
      losses[index] = 0.6;
    }
    for (int index = 44; index < 55; index++) {
      losses[index] = 1.21;
    }
    losses[55] = 7.0;
    return losses;
  }

  private static double[] solidAmateurLosses() {
    double[] losses = new double[51];
    for (int index = 16; index < 38; index++) {
      losses[index] = 0.8;
    }
    for (int index = 38; index < 45; index++) {
      losses[index] = 3.0;
    }
    for (int index = 45; index < 51; index++) {
      losses[index] = 7.0;
    }
    return losses;
  }

  private static double[] lowLossHighRankLosses() {
    double[] losses = new double[65];
    for (int index = 28; index < 47; index++) {
      losses[index] = 0.8;
    }
    for (int index = 47; index < 58; index++) {
      losses[index] = 2.0;
    }
    for (int index = 58; index < 65; index++) {
      losses[index] = 7.0;
    }
    return losses;
  }

  private static double[] weakDanEvidenceLosses() {
    double[] losses = new double[50];
    for (int index = 14; index < 30; index++) {
      losses[index] = 0.8;
    }
    for (int index = 30; index < 45; index++) {
      losses[index] = 3.0;
    }
    for (int index = 45; index < 50; index++) {
      losses[index] = 7.0;
    }
    return losses;
  }

  private static String coordinate(int index) {
    return Board.convertCoordinatesToName(index % BOARD_SIZE, index / BOARD_SIZE);
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < stones.length; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private static int[] moveNumberList(int x, int y, int moveNumber) {
    int[] moveNumberList = new int[BOARD_AREA];
    moveNumberList[Board.getIndex(x, y)] = moveNumber;
    return moveNumberList;
  }

  private static boolean selectableModelsContain(PlayerStrengthEstimator.StrengthModel target) {
    for (PlayerStrengthEstimator.StrengthModel model : PlayerStrengthEstimator.selectableModels()) {
      if (model.equals(target)) {
        return true;
      }
    }
    return false;
  }

  private static String metadataJson(String displayName, String[] featureOrder) {
    JSONObject metadata = new JSONObject();
    metadata.put("model_type", "xgboost_rank_value");
    metadata.put("display_name", displayName);
    metadata.put("feature_order", new JSONArray(featureOrder));
    metadata.put("prediction_sigma", XGBoostStrengthModel.xgboost20TunSigma());
    return metadata.toString(2);
  }

  private static void copyBundledResource(String resourcePath, Path target) throws IOException {
    Files.write(target, bundledResourceBytes(resourcePath));
  }

  private static byte[] bundledResourceBytes(String resourcePath) throws IOException {
    try (InputStream stream = PlayerStrengthEstimatorTest.class.getResourceAsStream(resourcePath)) {
      assertNotNull(stream, "Missing test resource " + resourcePath);
      return stream.readAllBytes();
    }
  }

  private static void writeZipEntry(ZipOutputStream zip, String name, byte[] content)
      throws IOException {
    ZipEntry entry = new ZipEntry(name);
    zip.putNextEntry(entry);
    zip.write(content);
    zip.closeEntry();
  }

  private static long modelDirectoryCount(Path modelRoot) throws IOException {
    if (!Files.isDirectory(modelRoot)) {
      return 0;
    }
    try (var stream = Files.list(modelRoot)) {
      return stream.filter(Files::isDirectory).count();
    }
  }

  private static final class StrengthModelTestEnvironment implements AutoCloseable {
    private final Config previousConfig;
    private final Path modelRoot;

    private StrengthModelTestEnvironment(Config previousConfig, Path modelRoot) {
      this.previousConfig = previousConfig;
      this.modelRoot = modelRoot;
    }

    private static StrengthModelTestEnvironment open(Path workDirectory) throws IOException {
      Config previousConfig = Lizzie.config;
      Files.createDirectories(workDirectory);
      Lizzie.config = ConfigTestHelper.createForTests(workDirectory);
      return new StrengthModelTestEnvironment(
          previousConfig, Lizzie.config.getWorkDirectory().toPath().resolve("strength-models"));
    }

    private Path modelRoot() {
      return modelRoot;
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
    }
  }
}
