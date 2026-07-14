package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveRankDefinition;
import featurecat.lizzie.analysis.PlayerStrengthEstimator;
import featurecat.lizzie.analysis.ReadBoard;
import featurecat.lizzie.analysis.TrackingEngine;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class LizzieFrameRegressionTest {
  private static final int BOARD_SIZE = 2;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void loadingStatusFontFitsLongLocalizedTextAndUsesCompositeFont() {
    BufferedImage image = new BufferedImage(800, 200, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      String thaiStatus =
          "\u0E01\u0E33\u0E25\u0E31\u0E07\u0E40\u0E23\u0E34\u0E48\u0E21\u0E15\u0E49\u0E19\u0E40\u0E04\u0E23\u0E37\u0E48\u0E2D\u0E07\u0E22\u0E19\u0E15\u0E4C\u0E27\u0E34\u0E40\u0E04\u0E23\u0E32\u0E30\u0E2B\u0E4C";
      Font font = LizzieFrame.fitStatusFont(graphics, thaiStatus, 72, 12, 240);

      assertTrue(font.getSize() >= 12);
      assertTrue(font.getSize() < 72);
      String displayed =
          LizzieFrame.truncateStatusText(thaiStatus, graphics.getFontMetrics(font), 240);
      assertTrue(displayed.endsWith("..."));
      assertTrue(graphics.getFontMetrics(font).stringWidth(displayed) <= 240);
    } finally {
      graphics.dispose();
    }
  }

  @Test
  void stackedEngineStatusLinesStaySeparatedAboveBottomToolbar() {
    int[] tops = LizzieFrame.stackedStatusTextTops(642, 667, 36, 22, 670);

    assertTrue(tops[1] >= tops[0] + 38);
    assertTrue(tops[1] + 22 <= 670);
  }

  @Test
  void pasteSgfDecisionIgnoresEmptyClipboard() {
    assertEquals(
        LizzieFrame.PasteSgfDecision.IGNORE_EMPTY, LizzieFrame.pasteSgfDecision("", true));
    assertEquals(
        LizzieFrame.PasteSgfDecision.IGNORE_EMPTY, LizzieFrame.pasteSgfDecision("   ", true));
    assertEquals(
        LizzieFrame.PasteSgfDecision.IGNORE_EMPTY, LizzieFrame.pasteSgfDecision(null, true));
  }

  @Test
  void pasteSgfDecisionIgnoresNonSgfClipboardText() {
    assertEquals(
        LizzieFrame.PasteSgfDecision.IGNORE_NOT_SGF,
        LizzieFrame.pasteSgfDecision("not a game record", true));
  }

  @Test
  void pasteSgfDecisionLoadsDirectlyWhenCurrentBoardIsEmpty() {
    assertEquals(
        LizzieFrame.PasteSgfDecision.LOAD,
        LizzieFrame.pasteSgfDecision("(;SZ[19];B[pd])", false));
  }

  @Test
  void pasteSgfDecisionRequiresConfirmationWhenCurrentBoardHasContent() {
    assertEquals(
        LizzieFrame.PasteSgfDecision.CONFIRM_REPLACE,
        LizzieFrame.pasteSgfDecision("(;SZ[19];B[pd])", true));
  }

  @Test
  void linuxUsesSwingKifuChooserToAvoidNativeDialogMisplacement() {
    assertTrue(LizzieFrame.shouldUseSwingKifuChooser("Linux"));
    assertTrue(LizzieFrame.shouldUseSwingKifuChooser("Ubuntu Linux"));
  }

  @Test
  void nonLinuxKeepsNativeKifuChooser() {
    assertFalse(LizzieFrame.shouldUseSwingKifuChooser("Windows 11"));
    assertFalse(LizzieFrame.shouldUseSwingKifuChooser("Mac OS X"));
    assertFalse(LizzieFrame.shouldUseSwingKifuChooser(null));
  }

  @Test
  void quickAnalysisWarmupWaitsForRemotePrimaryEngine() {
    assertEquals(
        LizzieFrame.QuickAnalysisWarmupAction.START,
        LizzieFrame.decideQuickAnalysisWarmup(true, false, false, false));
    assertEquals(
        LizzieFrame.QuickAnalysisWarmupAction.WAIT_FOR_PRIMARY,
        LizzieFrame.decideQuickAnalysisWarmup(true, true, false, false));
    assertEquals(
        LizzieFrame.QuickAnalysisWarmupAction.START,
        LizzieFrame.decideQuickAnalysisWarmup(true, true, true, false));
    assertEquals(
        LizzieFrame.QuickAnalysisWarmupAction.STOP,
        LizzieFrame.decideQuickAnalysisWarmup(true, true, false, true));
    assertEquals(
        LizzieFrame.QuickAnalysisWarmupAction.STOP,
        LizzieFrame.decideQuickAnalysisWarmup(false, false, true, false));
  }

  @Test
  void playerStrengthRankReferenceKeepsKyuResultsInKyuBand() throws Exception {
    Method rankLevel = LizzieFrame.class.getDeclaredMethod("playerStrengthRankLevel", String.class);
    rankLevel.setAccessible(true);

    assertEquals(1, rankLevel.invoke(null, "1-2k"));
    assertEquals(1, rankLevel.invoke(null, "11-15k"));
    assertEquals(5, rankLevel.invoke(null, "1d"));
    assertEquals(7, rankLevel.invoke(null, "2-3d"));
    assertEquals(8, rankLevel.invoke(null, "4d"));
    assertEquals(10, rankLevel.invoke(null, "10d\u804c\u4e1a"));
  }

  @Test
  void playerStrengthHitMapExposesMoveTooltip() throws Exception {
    Class<?> panelClass =
        Class.forName("featurecat.lizzie.gui.LizzieFrame$PlayerStrengthMoveHitMapPanel");
    java.lang.reflect.Constructor<?> constructor =
        panelClass.getDeclaredConstructor(PlayerStrengthEstimator.Report.class);
    constructor.setAccessible(true);
    javax.swing.JComponent panel =
        (javax.swing.JComponent) constructor.newInstance(playerStrengthReportWithSamples());
    panel.setSize(900, 300);

    java.awt.image.BufferedImage image =
        new java.awt.image.BufferedImage(900, 300, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    panel.paint(image.createGraphics());

    java.awt.event.MouseEvent event =
        new java.awt.event.MouseEvent(
            panel,
            java.awt.event.MouseEvent.MOUSE_MOVED,
            System.currentTimeMillis(),
            0,
            86,
            106,
            0,
            false);
    String tooltip = panel.getToolTipText(event);

    assertTrue(tooltip.contains("1"), "tooltip should include the move number.");
    assertTrue(tooltip.contains("AI"), "tooltip should include AI choice details.");

    String firstChoiceGoodMoveTooltip = tooltipContaining(panel, 125, 155, "1.2%", "0.4");
    String goodMoveTooltip = tooltipContaining(panel, "3.4%", "1.1");

    assertTrue(
        firstChoiceGoodMoveTooltip != null,
        "first-choice moves should also expose a tooltip on the good-move row.");
    assertTrue(
        firstChoiceGoodMoveTooltip.contains("AI"),
        "first-choice moves on the good-move row should keep first-choice details.");
    assertTrue(goodMoveTooltip != null, "good-move row should expose a tooltip.");
    assertTrue(goodMoveTooltip.contains("3.4%"), "good-move row should use the good move sample.");
    assertTrue(goodMoveTooltip.contains("1.1"), "good-move row should keep score loss details.");
    assertTrue(goodMoveTooltip.contains("35"), "sample complexity should use the 0-100 scale.");
  }

  @Test
  void playerStrengthRankWindowLabelUsesCompactRankText() throws Exception {
    Class<?> chartClass =
        Class.forName("featurecat.lizzie.gui.LizzieFrame$PlayerStrengthMatchChart");
    java.lang.reflect.Constructor<?> constructor =
        chartClass.getDeclaredConstructor(PlayerStrengthEstimator.Report.class);
    constructor.setAccessible(true);
    Object chart = constructor.newInstance(playerStrengthReportWithSamples());
    Method strengthLabel =
        chartClass.getDeclaredMethod("strengthLabel", PlayerStrengthEstimator.SideReport.class);
    strengthLabel.setAccessible(true);

    String label =
        String.valueOf(strengthLabel.invoke(chart, playerStrengthReportWithSamples().black));

    assertFalse(label.contains("Fox"), "rank window label should stay compact enough for the bar.");
    assertTrue(label.contains("1"), "rank window label should still include the rank.");
  }

  @Test
  void playerStrengthDetailPaletteKeepsTextReadable() throws Exception {
    Class<?> palette =
        Class.forName("featurecat.lizzie.gui.LizzieFrame$PlayerStrengthDetailPalette");
    Color card = colorConstant(palette, "CARD");
    Color cardSoft = colorConstant(palette, "CARD_SOFT");
    Color backgroundBottom = colorConstant(palette, "BACKGROUND_BOTTOM");

    assertContrastAtLeast("detail text on card", colorConstant(palette, "TEXT"), card, 9.0);
    assertContrastAtLeast(
        "detail muted text on card", colorConstant(palette, "MUTED_TEXT"), card, 7.0);
    assertContrastAtLeast(
        "detail warm text on soft card", colorConstant(palette, "WARM_TEXT"), cardSoft, 8.0);
    assertContrastAtLeast(
        "detail subtle text on dark background",
        colorConstant(palette, "SUBTLE_TEXT"),
        backgroundBottom,
        5.0);

    Class<?> chart = Class.forName("featurecat.lizzie.gui.LizzieFrame$PlayerStrengthMatchChart");
    assertContrastAtLeast(
        "match chart grid labels",
        colorConstant(chart, "GRID"),
        colorConstant(chart, "BACKGROUND"),
        3.0);
  }

  @Test
  void playerStrengthModelSelectorUsesReadableModelNames() throws Exception {
    Method displayName =
        LizzieFrame.class.getDeclaredMethod(
            "playerStrengthModelDisplayName", PlayerStrengthEstimator.StrengthModel.class);
    displayName.setAccessible(true);

    assertEquals(
        "XGBoost 20TUN",
        displayName.invoke(null, PlayerStrengthEstimator.StrengthModel.XGBOOST20TUN));
    assertEquals(
        "XGBoost 20TUN Previous",
        displayName.invoke(null, PlayerStrengthEstimator.StrengthModel.XGBOOST20TUN_PREVIOUS));
  }

  @Test
  void playerStrengthAssessmentCardExposesCompleteScoreRuleText() throws Exception {
    Class<?> cardClass =
        Class.forName("featurecat.lizzie.gui.LizzieFrame$PlayerStrengthAssessmentCard");
    java.lang.reflect.Constructor<?> constructor =
        cardClass.getDeclaredConstructor(
            String.class, boolean.class, PlayerStrengthEstimator.SideReport.class);
    constructor.setAccessible(true);
    javax.swing.JComponent card =
        (javax.swing.JComponent)
            constructor.newInstance("黑棋", true, playerStrengthReportWithSamples().black);

    String tooltip = card.getToolTipText();

    assertTrue(tooltip.contains("12+"), "score rule tooltip should show the AI band.");
    assertTrue(tooltip.contains("11+"), "score rule tooltip should show the top-pro band.");
    assertTrue(tooltip.contains("10+"), "score rule tooltip should show the pro band.");
    assertTrue(tooltip.contains("&lt;10"), "score rule tooltip should show the amateur band.");
    assertFalse(tooltip.contains("..."), "score rule text should not be ellipsized in tooltip.");
  }

  @Test
  void playerStrengthHeaderButtonExpandsForLocalizedText() throws Exception {
    Class<?> buttonClass =
        Class.forName("featurecat.lizzie.gui.LizzieFrame$PlayerStrengthDetailButton");
    java.lang.reflect.Constructor<?> constructor =
        buttonClass.getDeclaredConstructor(String.class, boolean.class);
    constructor.setAccessible(true);
    String thaiDetailText = "\u0E02\u0E49\u0E2D\u0E21\u0E39\u0E25\u0E42\u0E14\u0E22\u0E25\u0E30\u0E40\u0E2D\u0E35\u0E22\u0E14";
    javax.swing.JButton button = (javax.swing.JButton) constructor.newInstance(thaiDetailText, true);

    int requiredWidth = 66 + button.getFontMetrics(button.getFont()).stringWidth(thaiDetailText);

    assertTrue(
        button.getPreferredSize().width >= requiredWidth,
        "localized action text should not be squeezed into the legacy fixed width");
  }

  @Test
  void playerStrengthPerformanceDistributionUsesExclusiveTopChoiceRanks() throws Exception {
    PlayerStrengthEstimator.Report report = playerStrengthReportWithSamples();
    PlayerStrengthEstimator.SideReport sideReport =
        playerStrengthSideReport(
            java.util.List.of(
                playerStrengthSample(
                    Stone.BLACK,
                    1,
                    0.0,
                    Optional.of(0.0),
                    true,
                    0,
                    PlayerStrengthEstimator.MoveCategory.EXCELLENT),
                playerStrengthSample(
                    Stone.BLACK,
                    3,
                    0.2,
                    Optional.of(0.1),
                    false,
                    1,
                    PlayerStrengthEstimator.MoveCategory.EXCELLENT),
                playerStrengthSample(
                    Stone.BLACK,
                    5,
                    1.0,
                    Optional.of(0.4),
                    false,
                    1,
                    PlayerStrengthEstimator.MoveCategory.GREAT),
                playerStrengthSample(
                    Stone.BLACK,
                    7,
                    3.0,
                    Optional.of(1.0),
                    false,
                    2,
                    PlayerStrengthEstimator.MoveCategory.GOOD)),
            0.25,
            0.75);
    Class<?> panelClass =
        Class.forName("featurecat.lizzie.gui.LizzieFrame$PlayerStrengthPerformanceRankPanel");
    java.lang.reflect.Constructor<?> constructor =
        panelClass.getDeclaredConstructor(PlayerStrengthEstimator.Report.class);
    constructor.setAccessible(true);
    javax.swing.JComponent panel = (javax.swing.JComponent) constructor.newInstance(report);
    panel.setSize(900, 452);

    java.awt.image.BufferedImage image =
        new java.awt.image.BufferedImage(900, 452, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    panel.paint(image.createGraphics());

    Method rowsMethod =
        panelClass.getDeclaredMethod(
            "distributionRows", PlayerStrengthEstimator.SideReport.class);
    rowsMethod.setAccessible(true);
    Object[] rows = (Object[]) rowsMethod.invoke(panel, sideReport);
    Field countField = rows[0].getClass().getDeclaredField("count");
    countField.setAccessible(true);

    int total = 0;
    int[] counts = new int[rows.length];
    for (int i = 0; i < rows.length; i++) {
      counts[i] = countField.getInt(rows[i]);
      total += counts[i];
    }

    assertEquals(
        MoveRankDefinition.Rank.values().length,
        rows.length,
        "the overlapping top-choice row should be removed.");
    assertEquals(1, counts[MoveRankDefinition.Rank.BEST.ordinal()]);
    assertEquals(
        2,
        counts[MoveRankDefinition.Rank.GOOD.ordinal()],
        "non-top-choice Best moves should join the original Good moves.");
    assertEquals(1, counts[MoveRankDefinition.Rank.NORMAL.ordinal()]);
    assertEquals(
        sideReport.sampleCount,
        total,
        "each analyzed move should be counted exactly once.");
  }

  @Test
  void openBoardSyncCoalescesConsecutiveRestartsOnEdt() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingFrame frame = newTrackingFrame();
      frame.readBoard = fakeReadBoard();
      frame.nativeBoardSyncSupported = true;
      frame.nativeReadBoardAvailable = true;
      Lizzie.frame = frame;

      assertConsecutiveRestartIsCoalesced(frame, frame::openBoardSync);
      assertEquals(1, frame.nativeCreateCount.get());
    } finally {
      drainEdt();
      env.close();
    }
  }

  @Test
  void openBoardSyncDoesNotFallbackWhenNativeSyncUnsupported() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingFrame frame = newTrackingFrame();
      Lizzie.frame = frame;

      SwingUtilities.invokeAndWait(frame::openBoardSync);

      assertEquals(0, frame.nativeCreateCount.get());
      assertEquals(0, frame.createCount.get());
    } finally {
      drainEdt();
      env.close();
    }
  }

  @Test
  void openBoardSyncDoesNothingWhenNativeReadBoardMissing() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingFrame frame = newTrackingFrame();
      frame.nativeBoardSyncSupported = true;
      frame.nativeReadBoardAvailable = false;
      Lizzie.frame = frame;

      SwingUtilities.invokeAndWait(frame::openBoardSync);

      assertEquals(0, frame.nativeCreateCount.get());
      assertEquals(0, frame.createCount.get());
    } finally {
      drainEdt();
      env.close();
    }
  }

  @Test
  void openBoardSyncDoesNotStartReplacementWhileRestartStillReserved() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      TrackingFrame frame = newTrackingFrame();
      frame.nativeBoardSyncSupported = true;
      frame.nativeReadBoardAvailable = true;
      setField(frame, "readBoardRestartTarget", fakeReadBoard());
      Lizzie.frame = frame;

      SwingUtilities.invokeAndWait(frame::openBoardSync);

      assertEquals(0, frame.nativeCreateCount.get());
      assertEquals(0, frame.createCount.get());
      assertTrue(getField(frame, "pendingReadBoardFactory") != null);
    } finally {
      drainEdt();
      env.close();
    }
  }

  @Test
  void autoQuickAnalyzeIgnoresSnapshotMarkersInMoveCount() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithAnalyzedMoveThenSnapshotMarker());
      LizzieFrame frame = allocate(LizzieFrame.class);

      assertFalse(
          invokeShouldAutoQuickAnalyze(frame),
          "auto quick analyze should only count real moves and passes.");
    } finally {
      env.close();
    }
  }

  @Test
  void autoQuickAnalyzeIgnoresDummyPassPlaceholdersInMoveCount() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithAnalyzedMoveThenDummyPass());
      LizzieFrame frame = allocate(LizzieFrame.class);

      assertFalse(
          invokeShouldAutoQuickAnalyze(frame),
          "auto quick analyze should ignore dummy PASS placeholders in move counts.");
    } finally {
      env.close();
    }
  }

  @Test
  void autoQuickAnalyzeCanBeDisabledForLoadedGame() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze(false);
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      LizzieFrame frame = allocate(LizzieFrame.class);

      assertFalse(
          invokeShouldAutoQuickAnalyze(frame),
          "disabled auto quick analyze should not start the fast winrate graph refresh.");
    } finally {
      env.close();
    }
  }

  @Test
  void autoQuickAnalyzeSkipsWhenExistingAnalysisIsBelowTargetVisits() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithLowVisitAnalyzedMove());
      LizzieFrame frame = allocate(LizzieFrame.class);

      assertFalse(
          invokeShouldAutoQuickAnalyze(frame),
          "ordinary SGF load should not start auto quick analyze for already analyzed moves.");
    } finally {
      env.close();
    }
  }

  @Test
  void autoQuickAnalyzeSkipsWhenExistingAnalysisExists() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithTargetVisitAnalyzedMove());
      LizzieFrame frame = allocate(LizzieFrame.class);

      assertFalse(
          invokeShouldAutoQuickAnalyze(frame),
          "auto quick analyze should not restart when all mainline moves already have analysis.");
    } finally {
      env.close();
    }
  }

  @Test
  void remoteKifuLoadWaitsForPrimaryEngineBeforeStartingSilentQuickAnalyze() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      StartingRemoteLeelaz leelaz = new StartingRemoteLeelaz();
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      Lizzie.frame = frame;

      assertTrue(
          frame.ensureAnalysisResumedAfterLoad(),
          "loaded records should remain scheduled while the remote primary engine connects.");
      assertEquals(0, frame.flashAnalyzeGameCount);

      invokeRetryLoadedGameQuickAnalysisIfMissing(frame);
      assertEquals(
          0,
          frame.flashAnalyzeGameCount,
          "quick analysis must not open a competing remote worker before the primary is ready.");

      leelaz.loaded = true;
      invokeRetryLoadedGameQuickAnalysisIfMissing(frame);
      assertEquals(1, frame.flashAnalyzeGameCount);
      assertTrue(frame.lastIsAllGame);
      assertFalse(frame.lastIsAllBranches);
      assertTrue(frame.lastSilentAnalyze);
      invokeStopLoadedGameQuickAnalysisRetry(frame);
    } finally {
      env.close();
    }
  }

  @Test
  void downloadedKifuForcesSilentQuickAnalyzeEvenWhenSgfContainsAnalysisPayload()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithTargetVisitAnalyzedMove());
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);

      assertTrue(
          frame.ensureAnalysisResumedAfterDownloadedKifuLoad(),
          "downloaded Fox/Tencent records should build the fast graph even if SGF has stale payload.");
      assertEquals(1, frame.flashAnalyzeGameCount);
      assertTrue(frame.lastIsAllGame);
      assertFalse(frame.lastIsAllBranches);
      assertTrue(frame.lastSilentAnalyze);
      assertEquals(0, frame.refreshCount);
    } finally {
      env.close();
    }
  }

  @Test
  void downloadedKifuRespectsDisabledAutoQuickAnalyzeSetting() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze(false);
      Lizzie.board = boardWith(historyWithTargetVisitAnalyzedMove());
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);

      assertFalse(frame.ensureAnalysisResumedAfterDownloadedKifuLoad());
      assertEquals(0, frame.flashAnalyzeGameCount);
    } finally {
      env.close();
    }
  }

  @Test
  void loadedGameStartsSilentQuickAnalyzeAndForegroundAnalysisForUnanalyzedMoves()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      leelaz.pondering = true;
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      Lizzie.frame = frame;

      assertTrue(frame.ensureAnalysisResumedAfterLoad());
      assertEquals(1, frame.flashAnalyzeGameCount);
      assertTrue(frame.lastIsAllGame);
      assertFalse(frame.lastIsAllBranches);
      assertTrue(frame.lastSilentAnalyze);
      assertEquals(1, frame.refreshCount);
      assertEquals(
          1,
          leelaz.ponderCount,
          "fast curve completion must not replace foreground candidate analysis.");
    } finally {
      env.close();
    }
  }

  @Test
  void loadedGameResumeSyncsCurrentKifuBeforeForegroundAnalysis() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze(false);
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      Lizzie.frame = frame;

      assertTrue(frame.ensureAnalysisResumedAfterLoad());

      assertEquals(0, frame.flashAnalyzeGameCount);
      assertEquals(
          List.of("boardsize 2", "clear_board", "play B A2", "ponder"),
          leelaz.commands(),
          "foreground analysis should replay the loaded SGF position before starting ponder.");
    } finally {
      env.close();
    }
  }

  @Test
  void manualPonderStartSyncsCurrentKifuBeforeAnalysis() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze(false);
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      ManualPonderTrackingFrame frame = allocate(ManualPonderTrackingFrame.class);
      Lizzie.frame = frame;

      frame.togglePonderMannul();

      assertEquals(
          List.of("boardsize 2", "clear_board", "play B A2", "ponder"),
          leelaz.commands(),
          "manual resume should also align the engine to the current SGF before analysis.");
    } finally {
      env.close();
    }
  }

  @Test
  void trackingEngineStartupReplaysQueuedPointWithoutOpeningConsole() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze(false);
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      leelaz.engineCommand = "katago gtp -model test.bin";
      Lizzie.leelaz = leelaz;
      TrackingStartupFrame frame = allocate(TrackingStartupFrame.class);
      frame.engine = new StartupTrackingEngine();
      frame.trackedCoords = Collections.synchronizedSet(new LinkedHashSet<>());
      frame.trackedCoords.add("A1");
      setField(
          frame,
          "trackingEngineStarting",
          new java.util.concurrent.atomic.AtomicBoolean(false));
      Lizzie.frame = frame;

      frame.ensureTrackingEngine();

      assertTrue(
          frame.engine.started.await(2, TimeUnit.SECONDS),
          "tracking engine should start in the background.");
      drainEdt();
      assertTrue(
          frame.engine.requestSent.await(2, TimeUnit.SECONDS),
          "queued point should be analyzed after startup.");

      assertEquals(0, frame.engine.consoleAttachCount, "tracking console should stay hidden.");
      assertEquals(1, frame.engine.requestCount, "queued point should be analyzed after startup.");
      assertSame(
          Lizzie.board.getHistory().getCurrentHistoryNode(),
          frame.engine.lastNode,
          "tracking analysis should target the current board node.");
      assertEquals(Set.of("A1"), frame.engine.lastCoords);
    } finally {
      env.close();
    }
  }

  @Test
  void silentQuickAnalyzeCompletionRestartsForegroundAnalysisForCurrentPosition()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      QuickAnalysisResumeFrame frame = allocate(QuickAnalysisResumeFrame.class);
      QuickAnalysisCompletionEngine engine = allocate(QuickAnalysisCompletionEngine.class);
      engine.requestStarted = new CountDownLatch(1);
      frame.analysisEngine = engine;
      Lizzie.frame = frame;

      frame.flashAnalyzeGame(true, false, true);

      assertTrue(
          engine.requestStarted.await(2, TimeUnit.SECONDS),
          "silent quick analysis should dispatch its request in the background.");
      assertFalse(engine.lastShowProgressDialog);
      assertTrue(
          engine.completionCallback != null,
          "silent quick analysis should resume foreground board analysis after graph completion.");

      SwingUtilities.invokeAndWait(engine.completionCallback);

      assertEquals(
          1,
          leelaz.ponderCount,
          "foreground candidate analysis should restart immediately after fast curve completion.");
      assertEquals(1, frame.refreshCount);
    } finally {
      env.close();
    }
  }

  @Test
  void downloadedKifuStartsSilentQuickAnalyzeAndForegroundAnalysis() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithTargetVisitAnalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      Lizzie.frame = frame;

      assertTrue(frame.ensureAnalysisResumedAfterDownloadedKifuLoad());
      assertEquals(1, frame.flashAnalyzeGameCount);
      assertTrue(frame.lastIsAllGame);
      assertFalse(frame.lastIsAllBranches);
      assertTrue(frame.lastSilentAnalyze);
      assertEquals(1, frame.refreshCount);
      assertEquals(1, leelaz.ponderCount);
    } finally {
      env.close();
    }
  }

  @Test
  void winrateGraphNavigationContinuesMissingQuickAnalysisWhenEngineIsIdle() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      QuickAnalysisResumeFrame frame = allocate(QuickAnalysisResumeFrame.class);
      NavigationQuickAnalysisEngine engine = allocate(NavigationQuickAnalysisEngine.class);
      frame.analysisEngine = engine;
      Lizzie.frame = frame;

      SwingUtilities.invokeAndWait(frame::continueQuickAnalysisAfterHistoryNavigationWhenIdle);

      assertEquals(
          1,
          engine.keepAliveCount,
          "navigation continuation should keep the warmed quick-analysis engine reusable.");
      assertEquals(
          1,
          engine.missingMainlineRequestCount,
          "navigation continuation should fill any remaining fast-curve gaps.");
      assertTrue(
          engine.completionCallback != null,
          "navigation-triggered curve completion should also resume foreground board analysis.");

      SwingUtilities.invokeAndWait(engine.completionCallback);

      assertEquals(
          1,
          leelaz.ponderCount,
          "foreground analysis should restart after navigation-triggered curve completion.");
    } finally {
      env.close();
    }
  }

  @Test
  void winrateGraphNavigationWaitsWhenQuickAnalysisIsStillRunning() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      LizzieFrame frame = allocate(LizzieFrame.class);
      NavigationQuickAnalysisEngine engine = allocate(NavigationQuickAnalysisEngine.class);
      engine.analysisInProgress = true;
      frame.analysisEngine = engine;
      Lizzie.frame = frame;

      SwingUtilities.invokeAndWait(frame::continueQuickAnalysisAfterHistoryNavigationWhenIdle);

      assertEquals(
          0,
          engine.missingMainlineRequestCount,
          "navigation continuation must not clear or restart an active quick-analysis queue.");
    } finally {
      env.close();
    }
  }

  @Test
  void postLoadAnalysisResumeIgnoresStaleOlderLoadTask() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      LizzieFrame frame = allocate(LizzieFrame.class);
      AtomicInteger firstRunCount = new AtomicInteger();
      AtomicInteger secondRunCount = new AtomicInteger();
      CountDownLatch secondRan = new CountDownLatch(1);

      frame.scheduleResumeAnalysisAfterLoad(180, firstRunCount::incrementAndGet);
      frame.scheduleResumeAnalysisAfterLoad(
          0,
          () -> {
            secondRunCount.incrementAndGet();
            secondRan.countDown();
          });

      assertTrue(secondRan.await(2, TimeUnit.SECONDS));
      Thread.sleep(260);
      drainEdt();

      assertEquals(0, firstRunCount.get(), "an older delayed kifu-load resume must not run late.");
      assertEquals(1, secondRunCount.get());
    } finally {
      env.close();
    }
  }

  @Test
  void loadedGameQuickAnalysisRetryRestartsWhenInitialDispatchDisappears() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      EngineManager.isEngineGame = false;
      EngineManager.isPreEngineGame = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      Lizzie.frame = frame;

      assertTrue(frame.ensureAnalysisResumedAfterLoad());
      assertEquals(1, frame.flashAnalyzeGameCount);

      invokeRetryLoadedGameQuickAnalysisIfMissing(frame);

      assertEquals(
          2,
          frame.flashAnalyzeGameCount,
          "if the first silent quick-curve dispatch vanishes, the load guard should retry it.");
      assertEquals(2, leelaz.ponderCount);
      invokeStopLoadedGameQuickAnalysisRetry(frame);
    } finally {
      env.close();
    }
  }

  @Test
  void finishKifuLoadDoesNotRefreshAgainBeforeHidingOverlay() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      FinishTrackingFrame frame = allocate(FinishTrackingFrame.class);
      frame.refreshCount = new AtomicInteger();
      JPanel glassPane = new JPanel();
      glassPane.setVisible(true);
      setField(frame, "kifuLoadGlassPane", glassPane);
      setField(frame, "kifuLoadVisibleSince", System.currentTimeMillis() - 1000);
      CountDownLatch finished = new CountDownLatch(1);

      SwingUtilities.invokeAndWait(() -> frame.finishKifuLoad(finished::countDown));

      assertTrue(finished.await(2, TimeUnit.SECONDS), "kifu load overlay should always finish.");
      drainEdt();
      assertFalse(glassPane.isVisible(), "finish should hide the kifu load overlay.");
      assertEquals(0, frame.refreshCount.get(), "finish should not repeat heavy board refresh.");
    } finally {
      drainEdt();
      env.close();
    }
  }

  @Test
  void graphicsConfigurationScaleChangeRefreshesLayoutOnlyWhenScaleChanges() throws Exception {
    boolean previousScaled = Config.isScaled;
    float previousScaleFactor = Lizzie.javaScaleFactor;
    try {
      ScaleTrackingFrame frame = allocate(ScaleTrackingFrame.class);
      setField(frame, "refreshWinratePane", false);
      Config.isScaled = false;
      Lizzie.javaScaleFactor = 1.0f;

      invokeUpdateScaleFromGraphicsConfiguration(frame, graphicsConfigurationWithScale(2.0));

      assertTrue(Config.isScaled);
      assertEquals(2.0f, Lizzie.javaScaleFactor, 0.001f);
      assertTrue((boolean) getField(frame, "refreshWinratePane"));
      assertEquals(1, frame.resetLocationCount);
      assertEquals(1, frame.refreshContainerCount);
      assertEquals(1, frame.repaintCount);

      invokeUpdateScaleFromGraphicsConfiguration(frame, graphicsConfigurationWithScale(2.0));

      assertEquals(1, frame.resetLocationCount, "unchanged scale should not relayout.");
      assertEquals(1, frame.refreshContainerCount, "unchanged scale should not refresh containers.");
      assertEquals(1, frame.repaintCount, "unchanged scale should not repaint.");

      invokeUpdateScaleFromGraphicsConfiguration(frame, graphicsConfigurationWithScale(1.0));

      assertFalse(Config.isScaled);
      assertEquals(1.0f, Lizzie.javaScaleFactor, 0.001f);
      assertEquals(2, frame.resetLocationCount);
      assertEquals(2, frame.refreshContainerCount);
      assertEquals(2, frame.repaintCount);
    } finally {
      Config.isScaled = previousScaled;
      Lizzie.javaScaleFactor = previousScaleFactor;
    }
  }

  private static TrackingFrame newTrackingFrame() throws Exception {
    TrackingFrame frame = allocate(TrackingFrame.class);
    initReadBoardRestartLock(frame);
    frame.shutdownCalled = new CountDownLatch(1);
    frame.createCalled = new CountDownLatch(1);
    frame.secondShutdownCalled = new CountDownLatch(1);
    frame.secondCreateCalled = new CountDownLatch(1);
    frame.allowShutdown = new CountDownLatch(1);
    frame.shutdownCount = new AtomicInteger();
    frame.createCount = new AtomicInteger();
    frame.nativeCreateCount = new AtomicInteger();
    frame.replacementReadBoard = fakeReadBoard();
    return frame;
  }

  private static PlayerStrengthEstimator.Report playerStrengthReportWithSamples() throws Exception {
    PlayerStrengthEstimator.Sample blackSample =
        playerStrengthSample(
            Stone.BLACK,
            1,
            1.2,
            Optional.of(0.4),
            true,
            0,
            PlayerStrengthEstimator.MoveCategory.EXCELLENT);
    PlayerStrengthEstimator.Sample whiteSample =
        playerStrengthSample(
            Stone.WHITE,
            2,
            3.4,
            Optional.of(1.1),
            false,
            1,
            PlayerStrengthEstimator.MoveCategory.GREAT);
    PlayerStrengthEstimator.SideReport blackReport =
        playerStrengthSideReport(java.util.List.of(blackSample), 1.0, 1.0);
    PlayerStrengthEstimator.SideReport whiteReport =
        playerStrengthSideReport(java.util.List.of(whiteSample), 0.0, 1.0);
    PlayerStrengthEstimator.SideReport overallReport =
        playerStrengthSideReport(java.util.List.of(blackSample, whiteSample), 0.5, 1.0);

    java.lang.reflect.Constructor<PlayerStrengthEstimator.Report> constructor =
        PlayerStrengthEstimator.Report.class.getDeclaredConstructor(
            PlayerStrengthEstimator.SideReport.class,
            PlayerStrengthEstimator.SideReport.class,
            PlayerStrengthEstimator.SideReport.class,
            PlayerStrengthEstimator.StrengthModel.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        blackReport,
        whiteReport,
        overallReport,
        PlayerStrengthEstimator.StrengthModel.XGBOOST20TUN);
  }

  private static Color colorConstant(Class<?> owner, String fieldName) throws Exception {
    Field field = owner.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (Color) field.get(null);
  }

  private static String tooltipContaining(javax.swing.JComponent component, String... fragments) {
    return tooltipContaining(component, 0, component.getHeight(), fragments);
  }

  private static String tooltipContaining(
      javax.swing.JComponent component, int minY, int maxY, String... fragments) {
    for (int y = 0; y < component.getHeight(); y++) {
      if (y < minY || y >= maxY) {
        continue;
      }
      for (int x = 0; x < component.getWidth(); x++) {
        java.awt.event.MouseEvent event =
            new java.awt.event.MouseEvent(
                component,
                java.awt.event.MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(),
                0,
                x,
                y,
                0,
                false);
        String tooltip = component.getToolTipText(event);
        if (tooltip == null) {
          continue;
        }
        boolean matches = true;
        for (String fragment : fragments) {
          matches &= tooltip.contains(fragment);
        }
        if (matches) {
          return tooltip;
        }
      }
    }
    return null;
  }

  private static void assertContrastAtLeast(String label, Color foreground, Color background, double min) {
    double contrast = contrastRatio(foreground, background);
    assertTrue(
        contrast >= min,
        label
            + " contrast should be >= "
            + min
            + " but was "
            + String.format(java.util.Locale.US, "%.2f", contrast));
  }

  private static double contrastRatio(Color foreground, Color background) {
    double lighter =
        Math.max(relativeLuminance(foreground), relativeLuminance(background));
    double darker =
        Math.min(relativeLuminance(foreground), relativeLuminance(background));
    return (lighter + 0.05) / (darker + 0.05);
  }

  private static double relativeLuminance(Color color) {
    return 0.2126 * linearRgb(color.getRed())
        + 0.7152 * linearRgb(color.getGreen())
        + 0.0722 * linearRgb(color.getBlue());
  }

  private static double linearRgb(int channel) {
    double value = channel / 255.0;
    return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
  }

  private static PlayerStrengthEstimator.Sample playerStrengthSample(
      Stone color,
      int moveNumber,
      double winrateLoss,
      Optional<Double> scoreLoss,
      boolean firstChoice,
      int aiRank,
      PlayerStrengthEstimator.MoveCategory category)
      throws Exception {
    java.lang.reflect.Constructor<PlayerStrengthEstimator.Sample> constructor =
        PlayerStrengthEstimator.Sample.class.getDeclaredConstructor(
            Stone.class,
            int.class,
            String.class,
            double.class,
            Optional.class,
            boolean.class,
            int.class,
            PlayerStrengthEstimator.MoveCategory.class,
            MoveRankDefinition.Rank.class,
            double.class,
            double.class,
            double.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        color,
        moveNumber,
        moveNumber % 2 == 1 ? "A9" : "B9",
        winrateLoss,
        scoreLoss,
        firstChoice,
        aiRank,
        category,
        moveRankForCategory(category),
        1.0,
        0.35,
        1.0);
  }

  private static MoveRankDefinition.Rank moveRankForCategory(
      PlayerStrengthEstimator.MoveCategory category) {
    switch (category) {
      case EXCELLENT:
        return MoveRankDefinition.Rank.BEST;
      case GREAT:
        return MoveRankDefinition.Rank.GOOD;
      case INACCURACY:
        return MoveRankDefinition.Rank.INACCURACY;
      case MISTAKE:
        return MoveRankDefinition.Rank.MISTAKE;
      case BLUNDER:
        return MoveRankDefinition.Rank.BLUNDER;
      default:
        return MoveRankDefinition.Rank.NORMAL;
    }
  }

  private static PlayerStrengthEstimator.SideReport playerStrengthSideReport(
      java.util.List<PlayerStrengthEstimator.Sample> samples,
      double firstChoiceRate,
      double goodMoveRate)
      throws Exception {
    java.lang.reflect.Constructor<PlayerStrengthEstimator.SideReport> constructor =
        PlayerStrengthEstimator.SideReport.class.getDeclaredConstructor(
            int.class,
            int.class,
            PlayerStrengthEstimator.StrengthModel.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            double.class,
            int[].class,
            double.class,
            double.class,
            double.class,
            double.class,
            String.class,
            PlayerStrengthEstimator.Confidence.class,
            java.util.List.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        samples.size(),
        samples.size(),
        PlayerStrengthEstimator.StrengthModel.XGBOOST20TUN,
        1.0,
        0.5,
        82.0,
        1.5,
        0.8,
        0.8,
        1.2,
        1.2,
        goodMoveRate,
        firstChoiceRate,
        goodMoveRate,
        goodMoveRate,
        moveRankCounts(samples),
        0.0,
        0.0,
        0.0,
        35.0,
        "1d",
        PlayerStrengthEstimator.Confidence.HIGH,
        samples);
  }

  private static int[] moveRankCounts(java.util.List<PlayerStrengthEstimator.Sample> samples) {
    int[] counts = new int[MoveRankDefinition.Rank.values().length];
    for (PlayerStrengthEstimator.Sample sample : samples) {
      counts[sample.moveRankCategory.ordinal()]++;
    }
    return counts;
  }

  private static void assertConsecutiveRestartIsCoalesced(TrackingFrame frame, Runnable trigger)
      throws Exception {
    SwingUtilities.invokeAndWait(trigger);

    assertTrue(frame.shutdownCalled.await(2, TimeUnit.SECONDS));
    SwingUtilities.invokeAndWait(trigger);

    assertFalse(
        frame.secondShutdownCalled.await(200, TimeUnit.MILLISECONDS),
        "existing ReadBoard should only be shut down once during a coalesced restart.");
    frame.allowShutdown.countDown();

    assertTrue(
        frame.createCalled.await(2, TimeUnit.SECONDS),
        "coalesced restart should still create one replacement ReadBoard.");
    drainEdt();

    assertFalse(
        frame.secondCreateCalled.await(200, TimeUnit.MILLISECONDS),
        "coalesced restart should only create one replacement ReadBoard.");
    assertEquals(1, frame.shutdownCount.get());
    assertEquals(1, frame.createCount.get());
    assertSame(frame.replacementReadBoard, frame.readBoard);
  }

  private static boolean invokeShouldAutoQuickAnalyze(LizzieFrame frame) throws Exception {
    Method method = LizzieFrame.class.getDeclaredMethod("shouldAutoQuickAnalyzeLoadedGame");
    method.setAccessible(true);
    return (boolean) method.invoke(frame);
  }

  private static void invokeRetryLoadedGameQuickAnalysisIfMissing(LizzieFrame frame) throws Exception {
    Method method = LizzieFrame.class.getDeclaredMethod("retryLoadedGameQuickAnalysisIfMissing");
    method.setAccessible(true);
    SwingUtilities.invokeAndWait(() -> invokeReflective(method, frame));
  }

  private static void invokeStopLoadedGameQuickAnalysisRetry(LizzieFrame frame) throws Exception {
    Method method = LizzieFrame.class.getDeclaredMethod("stopLoadedGameQuickAnalysisRetry");
    method.setAccessible(true);
    SwingUtilities.invokeAndWait(() -> invokeReflective(method, frame));
  }

  private static void invokeReflective(Method method, Object target) {
    try {
      method.invoke(target);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static void invokeUpdateScaleFromGraphicsConfiguration(
      LizzieFrame frame, GraphicsConfiguration graphicsConfiguration) throws Exception {
    Method method =
        LizzieFrame.class.getDeclaredMethod(
            "updateScaleFromGraphicsConfiguration", GraphicsConfiguration.class);
    method.setAccessible(true);
    method.invoke(frame, graphicsConfiguration);
  }

  private static GraphicsConfiguration graphicsConfigurationWithScale(double scale) {
    return new TestGraphicsConfiguration(scale);
  }

  private static BoardHistoryList historyWithAnalyzedMoveThenSnapshotMarker() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveData(new int[] {0, 0}, Stone.BLACK, false, 1, targetAnalysisVisitsForTest()));
    history.add(snapshotData(new int[] {1, 1}, Stone.WHITE, true, 2));
    return history;
  }

  private static BoardHistoryList historyWithAnalyzedMoveThenDummyPass() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveData(new int[] {0, 0}, Stone.BLACK, false, 1, targetAnalysisVisitsForTest()));
    history.add(dummyPassData(Stone.WHITE, true, 2));
    history.add(moveData(new int[] {1, 1}, Stone.WHITE, false, 3, targetAnalysisVisitsForTest()));
    return history;
  }

  private static BoardHistoryList historyWithUnanalyzedMove() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(moveData(new int[] {0, 0}, Stone.BLACK, false, 1, 0));
    return history;
  }

  private static BoardHistoryList historyWithLowVisitAnalyzedMove() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(
        moveData(
            new int[] {0, 0}, Stone.BLACK, false, 1, targetAnalysisVisitsForTest() - 1));
    return history;
  }

  private static BoardHistoryList historyWithTargetVisitAnalyzedMove() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    history.add(
        moveData(new int[] {0, 0}, Stone.BLACK, false, 1, targetAnalysisVisitsForTest()));
    return history;
  }

  private static int targetAnalysisVisitsForTest() {
    return Lizzie.config.analysisMaxVisits + 1;
  }

  private static BoardData moveData(
      int[] lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber, int playouts) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(lastMove[0], lastMove[1])] = lastMoveColor;
    return BoardData.move(
        stones,
        lastMove,
        lastMoveColor,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        moveList(lastMove[0], lastMove[1], moveNumber),
        0,
        0,
        50,
        playouts);
  }

  private static BoardData snapshotData(
      int[] lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(lastMove[0], lastMove[1])] = lastMoveColor;
    return BoardData.snapshot(
        stones,
        Optional.of(lastMove),
        lastMoveColor,
        blackToPlay,
        new Zobrist(),
        moveNumber,
        moveList(lastMove[0], lastMove[1], moveNumber),
        0,
        0,
        50,
        0);
  }

  private static BoardData dummyPassData(Stone lastMoveColor, boolean blackToPlay, int moveNumber) {
    BoardData data =
        BoardData.pass(
            emptyStones(),
            lastMoveColor,
            blackToPlay,
            new Zobrist(),
            moveNumber,
            new int[BOARD_AREA],
            0,
            0,
            50,
            0);
    data.dummy = true;
    return data;
  }

  private static int[] moveList(int x, int y, int moveNumber) {
    int[] moveNumberList = new int[BOARD_AREA];
    moveNumberList[Board.getIndex(x, y)] = moveNumber;
    return moveNumberList;
  }

  private static Stone[] emptyStones() {
    Stone[] stones = new Stone[BOARD_AREA];
    for (int index = 0; index < BOARD_AREA; index++) {
      stones[index] = Stone.EMPTY;
    }
    return stones;
  }

  private static Board boardWith(BoardHistoryList history) throws Exception {
    Board board = allocate(Board.class);
    board.setHistory(history);
    return board;
  }

  private static Config configWithAutoQuickAnalyze() throws Exception {
    return configWithAutoQuickAnalyze(true);
  }

  private static Config configWithAutoQuickAnalyze(boolean enabled) throws Exception {
    Config config = allocate(Config.class);
    config.autoQuickAnalyzeOnLoad = enabled;
    config.analysisMaxVisits = 32;
    return config;
  }

  private static ReadBoard fakeReadBoard() throws Exception {
    return allocate(ReadBoard.class);
  }

  private static void drainEdt() throws Exception {
    SwingUtilities.invokeAndWait(() -> {});
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static void initReadBoardRestartLock(LizzieFrame frame) throws Exception {
    setField(frame, "readBoardRestartLock", new Object());
  }

  private static Object getField(Object target, String name) throws Exception {
    Field field = LizzieFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = LizzieFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final Leelaz previousLeelaz;
    private final boolean previousEngineEmpty;
    private final boolean previousEngineGame;
    private final boolean previousPreEngineGame;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame,
        Leelaz previousLeelaz,
        boolean previousEngineEmpty,
        boolean previousEngineGame,
        boolean previousPreEngineGame) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousLeelaz = previousLeelaz;
      this.previousEngineEmpty = previousEngineEmpty;
      this.previousEngineGame = previousEngineGame;
      this.previousPreEngineGame = previousPreEngineGame;
    }

    private static TestEnvironment open() {
      TestEnvironment env =
          new TestEnvironment(
              Board.boardWidth,
              Board.boardHeight,
              Lizzie.config,
              Lizzie.board,
              Lizzie.frame,
              Lizzie.leelaz,
              EngineManager.isEmpty,
              EngineManager.isEngineGame,
              EngineManager.isPreEngineGame);
      Board.boardWidth = BOARD_SIZE;
      Board.boardHeight = BOARD_SIZE;
      Zobrist.init();
      return env;
    }

    @Override
    public void close() {
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      Zobrist.init();
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousLeelaz;
      EngineManager.isEmpty = previousEngineEmpty;
      EngineManager.isEngineGame = previousEngineGame;
      EngineManager.isPreEngineGame = previousPreEngineGame;
    }
  }

  private static final class TrackingFrame extends LizzieFrame {
    private CountDownLatch shutdownCalled;
    private CountDownLatch createCalled;
    private CountDownLatch secondShutdownCalled;
    private CountDownLatch secondCreateCalled;
    private CountDownLatch allowShutdown;
    private AtomicInteger shutdownCount;
    private AtomicInteger createCount;
    private AtomicInteger nativeCreateCount;
    private ReadBoard replacementReadBoard;
    private boolean nativeBoardSyncSupported;
    private boolean nativeReadBoardAvailable;
    private volatile boolean shutdownCompleted;
    private volatile boolean startedBeforeShutdownCompleted;

    @Override
    protected void shutdownReadBoard(ReadBoard targetReadBoard) {
      if (shutdownCount.incrementAndGet() == 1) {
        shutdownCalled.countDown();
      } else {
        secondShutdownCalled.countDown();
      }
      await(allowShutdown);
      readBoard = null;
      shutdownCompleted = true;
    }

    @Override
    protected ReadBoard createNativeReadBoard() {
      nativeCreateCount.incrementAndGet();
      return recordCreate();
    }

    @Override
    protected boolean isNativeBoardSyncSupported() {
      return nativeBoardSyncSupported;
    }

    @Override
    protected boolean isNativeReadBoardAvailable() {
      return nativeReadBoardAvailable;
    }

    private ReadBoard recordCreate() {
      startedBeforeShutdownCompleted = !shutdownCompleted;
      if (createCount.incrementAndGet() == 1) {
        createCalled.countDown();
      } else {
        secondCreateCalled.countDown();
      }
      return replacementReadBoard;
    }

    private void await(CountDownLatch latch) {
      try {
        latch.await(2, TimeUnit.SECONDS);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for shutdown gate", ex);
      }
    }
  }

  private static final class FinishTrackingFrame extends LizzieFrame {
    private AtomicInteger refreshCount;

    @Override
    public void refresh() {
      refreshCount.incrementAndGet();
      throw new AssertionError("finishKifuLoad must not depend on a second refresh.");
    }
  }

  private static final class ScaleTrackingFrame extends LizzieFrame {
    private int resetLocationCount;
    private int refreshContainerCount;
    private int repaintCount;

    @Override
    public void reSetLoc() {
      resetLocationCount++;
    }

    @Override
    public void refreshContainer() {
      refreshContainerCount++;
    }

    @Override
    public void repaint() {
      repaintCount++;
    }
  }

  private static final class AnalysisResumeTrackingFrame extends LizzieFrame {
    private int flashAnalyzeGameCount;
    private int refreshCount;
    private boolean lastIsAllGame;
    private boolean lastIsAllBranches;
    private boolean lastSilentAnalyze;

    @Override
    public void flashAnalyzeGame(boolean isAllGame, boolean isAllBranches, boolean silentAnalyze) {
      flashAnalyzeGameCount++;
      lastIsAllGame = isAllGame;
      lastIsAllBranches = isAllBranches;
      lastSilentAnalyze = silentAnalyze;
    }

    @Override
    public void refresh() {
      refreshCount++;
    }
  }

  private static final class QuickAnalysisResumeFrame extends LizzieFrame {
    private int refreshCount;

    @Override
    public void refresh() {
      refreshCount++;
    }
  }

  private static final class ManualPonderTrackingFrame extends LizzieFrame {
    @Override
    public boolean stopAiPlayingAndPolicy() {
      return false;
    }
  }

  private static final class TrackingStartupFrame extends LizzieFrame {
    private StartupTrackingEngine engine;

    @Override
    protected TrackingEngine createTrackingEngine() {
      return engine;
    }

    @Override
    public void refresh() {}
  }

  private static final class StartupTrackingEngine extends TrackingEngine {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch requestSent = new CountDownLatch(1);
    private volatile boolean loaded;
    private int requestCount;
    private int consoleAttachCount;
    private BoardHistoryNode lastNode;
    private Set<String> lastCoords;

    @Override
    public void startEngine(String engineCommand) {
      loaded = true;
      started.countDown();
    }

    @Override
    public boolean isLoaded() {
      return loaded;
    }

    @Override
    public void sendTrackingRequest(BoardHistoryNode node, Set<String> trackedCoords) {
      requestCount++;
      lastNode = node;
      lastCoords = new LinkedHashSet<>(trackedCoords);
      requestSent.countDown();
    }

    @Override
    public void setConsolePane(TrackingConsolePane pane) {
      consoleAttachCount++;
    }
  }

  private static final class QuickAnalysisCompletionEngine extends AnalysisEngine {
    private CountDownLatch requestStarted = new CountDownLatch(1);
    private Runnable completionCallback;
    private boolean lastShowProgressDialog;

    @SuppressWarnings("unused")
    private QuickAnalysisCompletionEngine() throws java.io.IOException {
      super(true);
    }

    @Override
    public boolean isLoaded() {
      return true;
    }

    @Override
    public boolean isRunning() {
      return true;
    }

    @Override
    public synchronized boolean isAnalysisInProgress() {
      return lastShowProgressDialog || requestStarted.getCount() == 0;
    }

    @Override
    public boolean matchesCurrentAnalysisBackend() {
      return true;
    }

    @Override
    public void setCompletionCallback(Runnable completionCallback) {
      this.completionCallback = completionCallback;
    }

    @Override
    public void setKeepAliveAfterCurrentRequest(boolean keepAliveAfterCurrentRequest) {}

    @Override
    public void startRequest(int startMove, int endMove, boolean showProgressDialog) {
      lastShowProgressDialog = showProgressDialog;
      requestStarted.countDown();
    }
  }

  private static final class NavigationQuickAnalysisEngine extends AnalysisEngine {
    private boolean analysisInProgress;
    private int keepAliveCount;
    private int missingMainlineRequestCount;
    private Runnable completionCallback;

    @SuppressWarnings("unused")
    private NavigationQuickAnalysisEngine() throws java.io.IOException {
      super(true);
    }

    @Override
    public boolean isLoaded() {
      return true;
    }

    @Override
    public boolean isRunning() {
      return true;
    }

    @Override
    public synchronized boolean isAnalysisInProgress() {
      return analysisInProgress;
    }

    @Override
    public boolean matchesCurrentAnalysisBackend() {
      return true;
    }

    @Override
    public void setKeepAliveAfterCurrentRequest(boolean keepAliveAfterCurrentRequest) {
      if (keepAliveAfterCurrentRequest) {
        keepAliveCount++;
      }
    }

    @Override
    public void setCompletionCallback(Runnable completionCallback) {
      this.completionCallback = completionCallback;
    }

    @Override
    public int startRequestMissingMainline(boolean showProgressDialog) {
      missingMainlineRequestCount++;
      return 1;
    }
  }

  private static class TrackingLeelaz extends Leelaz {
    private boolean pondering;
    private int ponderCount;
    private List<String> commands;

    protected TrackingLeelaz() throws java.io.IOException {
      super("");
    }

    private List<String> commands() {
      if (commands == null) {
        commands = new ArrayList<>();
      }
      return commands;
    }

    @Override
    public boolean isStarted() {
      return true;
    }

    @Override
    public boolean isLoaded() {
      return true;
    }

    @Override
    public boolean isPondering() {
      return pondering;
    }

    @Override
    public void sendCommand(String command) {
      commands().add(command);
    }

    @Override
    public void ponder() {
      ponderCount++;
      commands().add("ponder");
      pondering = true;
    }

    @Override
    public void togglePonder() {
      if (pondering) {
        pondering = false;
        commands().add("stop");
      } else {
        ponder();
      }
    }
  }

  private static final class StartingRemoteLeelaz extends TrackingLeelaz {
    private boolean loaded;

    private StartingRemoteLeelaz() throws java.io.IOException {
      engineCommand = "remote-compute://zhizi";
    }

    @Override
    public boolean isLoaded() {
      return loaded;
    }
  }

  private static final class TestGraphicsConfiguration extends GraphicsConfiguration {
    private final double scale;

    private TestGraphicsConfiguration(double scale) {
      this.scale = scale;
    }

    @Override
    public GraphicsDevice getDevice() {
      return null;
    }

    @Override
    public ColorModel getColorModel() {
      return ColorModel.getRGBdefault();
    }

    @Override
    public ColorModel getColorModel(int transparency) {
      return ColorModel.getRGBdefault();
    }

    @Override
    public AffineTransform getDefaultTransform() {
      return AffineTransform.getScaleInstance(scale, scale);
    }

    @Override
    public AffineTransform getNormalizingTransform() {
      return new AffineTransform();
    }

    @Override
    public Rectangle getBounds() {
      return new Rectangle(0, 0, 1920, 1080);
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static sun.misc.Unsafe loadUnsafe() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Failed to access Unsafe", ex);
      }
    }
  }
}
