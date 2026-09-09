package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveRankDefinition;
import featurecat.lizzie.analysis.PlayerStrengthEstimator;
import featurecat.lizzie.analysis.ReadBoard;
import featurecat.lizzie.analysis.WholeGameAnalysisSession;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import featurecat.lizzie.teacher.CommentDisplayRenderer;
import featurecat.lizzie.teacher.TeacherCommentCodec;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.GraphicsDevice;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.StyleSheet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.json.JSONObject;

class LizzieFrameRegressionTest {
  private static final int BOARD_SIZE = 2;
  private static final int BOARD_AREA = BOARD_SIZE * BOARD_SIZE;

  @Test
  void autoSaveFilesUseConfiguredWorkDirectoryInsteadOfProcessCwd(@TempDir Path workDir) {
    File autoSave = LizzieFrame.autoSaveFile(workDir.toFile(), 2, "sgf");

    assertEquals(workDir.resolve("save").resolve("autoGame2.sgf").toFile(), autoSave);
    assertTrue(autoSave.getParentFile().isDirectory());
  }

  @Test
  void aiPositionToggleIsNoOpWithoutAnEngine() throws Exception {
    Leelaz previousEngine = Lizzie.leelaz;
    try {
      Lizzie.leelaz = null;

      allocate(LizzieFrame.class).toggleShowKataEstimate();
    } finally {
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void estimateRenderersUseSafeDefaultsWithoutAForegroundEngine() throws Exception {
    Config previousConfig = Lizzie.config;
    Leelaz previousEngine = Lizzie.leelaz;
    try {
      Config config = allocate(Config.class);
      config.showKataGoEstimateBigBelow = true;
      config.showPureEstimateBigBelow = false;
      Lizzie.config = config;
      Lizzie.leelaz = null;

      BoardRenderer boardRenderer = allocate(BoardRenderer.class);
      FloatBoardRenderer floatBoardRenderer = allocate(FloatBoardRenderer.class);
      SubBoardRenderer subBoardRenderer = allocate(SubBoardRenderer.class);

      assertTrue(boardRenderer.shouldShowCountBlockBelow());
      assertTrue(boardRenderer.shouldShowCountBlockBig());
      assertTrue(floatBoardRenderer.shouldShowCountBlockBelow());
      assertTrue(floatBoardRenderer.shouldShowCountBlockBig());
      assertTrue(subBoardRenderer.shouldShowCountBlockBelow());
      assertTrue(subBoardRenderer.shouldShowCountBlockBig());
    } finally {
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void captureRulesPaintDoesNotThrowWithoutAForegroundEngine() throws Exception {
    Font previousFont = LizzieFrame.uiFont;
    try (TestEnvironment env = TestEnvironment.open()) {
      EmptyEngineUiFrame frame = prepareEmptyEngineUiFrame();
      LizzieFrame.uiFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
      BufferedImage image = new BufferedImage(200, 120, BufferedImage.TYPE_INT_ARGB);
      Graphics2D graphics = image.createGraphics();
      try {
        assertDoesNotThrow(() -> invokeDrawCaptured(frame, graphics, 0, 0, 200, 120, false));
        assertDoesNotThrow(() -> invokeDrawMoveStatistics(frame, graphics, 0, 0, 200, 120));
      } finally {
        graphics.dispose();
      }
    } finally {
      LizzieFrame.uiFont = previousFont;
    }
  }

  @Test
  void commentSetAndRefreshDoNotTreatAMissingEngineAsLoading() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      EmptyEngineUiFrame frame = prepareEmptyEngineUiFrame();

      assertDoesNotThrow(() -> invokeSetComment(frame, false));
      assertDoesNotThrow(() -> frame.appendComment());
      assertDoesNotThrow(() -> frame.refresh());

      String comment = String.valueOf(getField(frame, "cachedComment"));
      assertEquals("", comment);
      assertFalse(
          comment.contains(Lizzie.resourceBundle.getString("LizzieFrame.display.loading")),
          "an absent foreground engine is idle, not still loading");
    }
  }

  @Test
  void loadingStatusFontFitsAndTruncatesLongLocalizedText() {
    BufferedImage image = new BufferedImage(800, 200, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      String thaiStatus =
          "\u0E01\u0E33\u0E25\u0E31\u0E07\u0E40\u0E23\u0E34\u0E48\u0E21\u0E15\u0E49\u0E19\u0E40\u0E04\u0E23\u0E37\u0E48\u0E2D\u0E07\u0E22\u0E19\u0E15\u0E4C\u0E27\u0E34\u0E40\u0E04\u0E23\u0E32\u0E30\u0E2B\u0E4C";
      int availableWidth = 80;
      Font font =
          LizzieFrame.fitStatusFont(graphics, thaiStatus, 72, 12, availableWidth);

      assertTrue(font.getSize() >= 12);
      assertTrue(font.getSize() < 72);
      String displayed =
          LizzieFrame.truncateStatusText(
              thaiStatus, graphics.getFontMetrics(font), availableWidth);
      assertTrue(displayed.endsWith("..."));
      assertTrue(
          graphics.getFontMetrics(font).stringWidth(displayed) <= availableWidth);
    } finally {
      graphics.dispose();
    }
  }

  @Test
  void engineStatusAreaExcludesBottomToolbarAndUsesAllRemainingHeight() {
    int bottom = LizzieFrame.statusAreaBottom(700, 4);
    int[][] lines = LizzieFrame.statusLineBounds(620, bottom, 3);

    assertEquals(696, bottom);
    assertEquals(620, lines[0][0]);
    assertTrue(lines[1][0] >= lines[0][0] + lines[0][1]);
    assertTrue(lines[2][0] >= lines[1][0] + lines[1][1]);
    assertEquals(bottom, lines[2][0] + lines[2][1]);
  }

  @Test
  void engineStatusTypographyCanGrowToFillItsLine() {
    BufferedImage image = new BufferedImage(800, 200, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      Font font =
          LizzieFrame.fitStatusFontInBox(graphics, "智子云算力", 48, 12, 400, 60);

      assertTrue(font.getSize() > 28);
      assertTrue(graphics.getFontMetrics(font).getHeight() <= 58);
    } finally {
      graphics.dispose();
    }
  }

  @Test
  void aiCommentDisplayPaintsTextWithoutAnOpaqueWhiteBlock(@TempDir Path tempDir)
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = ConfigTestHelper.createForTests(tempDir);
      Lizzie.config.uiFontName = "SansSerif";
      Lizzie.config.commentFontSize = 16;
      String storedComment =
          TeacherCommentCodec.upsert(
              "User <comment>\n\nSecond line",
              "# AI review\n\n| Move | Review |\n| --- | --- |\n| D4 | **Good** |",
              "test-model");
      AtomicReference<BufferedImage> painted = new AtomicReference<>();

      SwingUtilities.invokeAndWait(
          () -> {
            LizzieFrame.HtmlKit kit = new LizzieFrame.HtmlKit();
            StyleSheet style = kit.getStyleSheet();
            LizzieFrame.configureCommentHtmlStyle(
                style, new Color(31, 91, 61), "SansSerif", 16);
            JPaintTextPane pane = new JPaintTextPane();
            pane.setBorder(BorderFactory.createEmptyBorder());
            pane.setOpaque(false);
            pane.setEditorKit(kit);
            pane.setDocument((HTMLDocument) kit.createDefaultDocument());
            pane.setEditable(false);
            pane.setForeground(new Color(31, 91, 61));
            pane.setText(CommentDisplayRenderer.render(storedComment));
            pane.setSize(420, 260);
            pane.doLayout();

            BufferedImage image =
                new BufferedImage(pane.getWidth(), pane.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
              pane.paint(graphics);
            } finally {
              graphics.dispose();
            }
            painted.set(image);
          });

      BufferedImage image = painted.get();
      assertEquals(0, image.getRGB(0, 0) >>> 24);
      assertEquals(0, image.getRGB(image.getWidth() - 1, 0) >>> 24);
      assertEquals(0, image.getRGB(0, image.getHeight() - 1) >>> 24);
      assertEquals(
          0, image.getRGB(image.getWidth() - 1, image.getHeight() - 1) >>> 24);
      int paintedPixels = 0;
      int nearWhitePixels = 0;
      for (int y = 0; y < image.getHeight(); y++) {
        for (int x = 0; x < image.getWidth(); x++) {
          int argb = image.getRGB(x, y);
          if ((argb >>> 24) != 0) {
            paintedPixels++;
            Color pixel = new Color(argb, true);
            if (pixel.getAlpha() > 220
                && pixel.getRed() > 245
                && pixel.getGreen() > 245
                && pixel.getBlue() > 245) {
              nearWhitePixels++;
            }
          }
        }
      }
      assertTrue(paintedPixels > 100, "comment text and table borders should still be visible");
      assertEquals(0, nearWhitePixels, "HTML content must not paint an opaque white background");
    } finally {
      env.close();
    }
  }

  @Test
  void commentDisplayStaysTransparentAfterThemeRefresh(@TempDir Path tempDir) throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = ConfigTestHelper.createForTests(tempDir);
      AtomicReference<JPaintTextPane> paneReference = new AtomicReference<>();
      AtomicReference<JScrollPane> scrollReference = new AtomicReference<>();

      SwingUtilities.invokeAndWait(
          () -> {
            JPaintTextPane pane = new JPaintTextPane();
            JScrollPane scrollPane = new JScrollPane(pane);
            pane.setOpaque(true);
            pane.setBackground(Color.WHITE);
            scrollPane.setOpaque(true);
            scrollPane.setBackground(Color.WHITE);
            scrollPane.getViewport().setOpaque(true);
            scrollPane.getViewport().setBackground(Color.WHITE);

            LizzieFrame.configureCommentDisplaySurface(pane, scrollPane);
            paneReference.set(pane);
            scrollReference.set(scrollPane);
          });

      JPaintTextPane pane = paneReference.get();
      JScrollPane scrollPane = scrollReference.get();
      assertFalse(pane.isOpaque());
      assertEquals(0, pane.getBackground().getAlpha());
      assertFalse(scrollPane.isOpaque());
      assertEquals(0, scrollPane.getBackground().getAlpha());
      assertFalse(scrollPane.getViewport().isOpaque());
      assertEquals(0, scrollPane.getViewport().getBackground().getAlpha());
    } finally {
      env.close();
    }
  }

  @Test
  void firstPaintStatusHintUsesCurrentLayoutBoardBoundary() {
    int availableWidth = LizzieFrame.statusTextMaxWidth(1200, 8, 500, true);

    assertEquals(484, availableWidth);
    BufferedImage image = new BufferedImage(1200, 120, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      String hint = "按住X键不放查看快捷键提示";
      Font font = LizzieFrame.fitStatusFontInBox(graphics, hint, 28, 12, availableWidth, 36);
      String displayed =
          LizzieFrame.truncateStatusText(hint, graphics.getFontMetrics(font), availableWidth);

      assertTrue(displayed.contains("X"));
    } finally {
      graphics.dispose();
    }
  }

  @Test
  void loadingStatusTypographyStillShrinksForSmallWindows() {
    assertEquals(9, LizzieFrame.boundedStatusFontSize(9, 12, true));
    assertEquals(8, LizzieFrame.boundedStatusFontSize(8, 12, false));
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
    assertFalse(LizzieFrame.shouldDiscardQuickAnalysisWarmup(7L, 7L));
    assertTrue(LizzieFrame.shouldDiscardQuickAnalysisWarmup(7L, 8L));
    assertTrue(LizzieFrame.quickAnalysisDependsOnPrimary(true, false, false, false));
    assertTrue(LizzieFrame.quickAnalysisDependsOnPrimary(false, true, false, false));
    assertTrue(LizzieFrame.quickAnalysisDependsOnPrimary(false, false, true, false));
    assertTrue(LizzieFrame.quickAnalysisDependsOnPrimary(false, false, false, true));
    assertFalse(LizzieFrame.quickAnalysisDependsOnPrimary(false, false, false, false));
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
    assertEquals(
        LizzieFrame.QuickAnalysisWarmupAction.WAIT_FOR_PRIMARY,
        LizzieFrame.decideQuickAnalysisWarmup(true, false, true, false, true),
        "a loaded primary must not start quick analysis before the new record is synchronized");
  }

  @Test
  void rulesDialogRequiresAFullyStartedEngine() throws Exception {
    assertFalse(LizzieFrame.isRulesEngineReady(null));
    Leelaz engine = new Leelaz("");
    assertFalse(LizzieFrame.isRulesEngineReady(engine));
    engine.isLoaded = true;
    assertFalse(LizzieFrame.isRulesEngineReady(engine));
    engine.started = true;
    assertTrue(LizzieFrame.isRulesEngineReady(engine));
  }

  @Test
  void automaticQuickAnalysisDoesNotReuseTheWrongModelBackend() {
    assertTrue(
        LizzieFrame.shouldReplaceAutomaticQuickAnalysisEngine(
            true, false, false, false, true, false));
    assertFalse(
        LizzieFrame.shouldReplaceAutomaticQuickAnalysisEngine(
            true, true, false, false, true, false));
    assertFalse(
        LizzieFrame.shouldReplaceAutomaticQuickAnalysisEngine(
            false, false, false, false, true, false));
    assertTrue(
        LizzieFrame.shouldReplaceAutomaticQuickAnalysisEngine(
            false, true, false, false, true, false));
    assertTrue(
        LizzieFrame.shouldReplaceAutomaticQuickAnalysisEngine(
            false, true, false, false, true, true));
    assertTrue(
        LizzieFrame.shouldReplaceAutomaticQuickAnalysisEngine(
            false, false, true, false, true, false));
    assertTrue(
        LizzieFrame.shouldReplaceAutomaticQuickAnalysisEngine(
            false, false, false, true, true, false));
    assertTrue(
        LizzieFrame.shouldReplaceAutomaticQuickAnalysisEngine(
            false, false, true, true, false, false));
  }

  @Test
  void replacingBusyQuickAnalysisWaitsForForegroundRestoreBeforeContinuing() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      LizzieFrame frame = allocate(LizzieFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.analysisInProgress = true;
      frame.analysisEngine = engine;
      AtomicInteger continuations = new AtomicInteger();

      assertTrue(
          invokeStopBusyQuickAnalysisEngineBeforeLoadedKifuAnalysis(
              frame, continuations::incrementAndGet));

      assertNull(frame.analysisEngine);
      assertEquals(1, engine.normalQuitCount);
      assertEquals(0, continuations.get());

      engine.completeExit();
      assertEquals(1, continuations.get());
    } finally {
      env.close();
    }
  }

  @Test
  void openingKifuWaitsForSharedAutomaticQuickAnalysisRestore() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      LizzieFrame frame = allocate(LizzieFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.shared = true;
      engine.automatic = true;
      engine.requestLifecycleInProgress = true;
      frame.analysisEngine = engine;
      setField(frame, "loadedGameQuickAnalysisActive", true);
      AtomicInteger continuations = new AtomicInteger();

      assertTrue(invokeDeferKifuOpen(frame, continuations::incrementAndGet));

      assertNull(frame.analysisEngine);
      assertFalse((boolean) getField(frame, "loadedGameQuickAnalysisActive"));
      assertEquals(1, engine.normalQuitCount);
      assertEquals(0, continuations.get());
      assertTrue(invokeDeferKifuOpen(frame, continuations::incrementAndGet));

      engine.completeExit();
      drainEdt();
      assertEquals(1, continuations.get());
      assertFalse((boolean) getField(frame, "kifuOpenWaitingForQuickAnalysisRestore"));
    } finally {
      env.close();
    }
  }

  @Test
  void engineSwitchWaitsForAutomaticQuickAnalysisLeaseRestore() throws Exception {
    LizzieFrame frame = allocate(LizzieFrame.class);
    ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
    engine.shared = true;
    engine.automatic = true;
    engine.requestLifecycleInProgress = true;
    frame.analysisEngine = engine;
    setField(frame, "quickAnalysisEngineGeneration", new AtomicLong());
    setField(frame, "loadedGameQuickAnalysisActive", true);
    AtomicInteger continuations = new AtomicInteger();

    SwingUtilities.invokeAndWait(
        () -> frame.runAfterAutomaticQuickAnalysisReleased(continuations::incrementAndGet));

    assertNull(frame.analysisEngine);
    assertFalse((boolean) getField(frame, "loadedGameQuickAnalysisActive"));
    assertEquals(1, engine.normalQuitCount);
    assertEquals(0, continuations.get());

    engine.completeExit();
    drainEdt();

    assertEquals(1, continuations.get());
  }

  @Test
  void engineSwitchDoesNotCancelUserStartedAnalysis() throws Exception {
    LizzieFrame frame = allocate(LizzieFrame.class);
    ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
    engine.analysisInProgress = true;
    frame.analysisEngine = engine;
    AtomicInteger continuations = new AtomicInteger();

    SwingUtilities.invokeAndWait(
        () -> frame.runAfterAutomaticQuickAnalysisReleased(continuations::incrementAndGet));

    assertSame(engine, frame.analysisEngine);
    assertEquals(0, engine.normalQuitCount);
    assertEquals(1, continuations.get());
  }

  @Test
  void manualAutoAnalysisWaitsForAutomaticSharedEngineRestore() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      LizzieFrame frame = allocate(LizzieFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.shared = true;
      engine.automatic = true;
      engine.requestLifecycleInProgress = true;
      frame.analysisEngine = engine;
      Lizzie.frame = frame;
      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      armLoadedGameQuickAnalysis(frame, root, true);
      AtomicInteger starts = new AtomicInteger();
      AtomicReference<LizzieFrame.ManualAutoAnalysisStartFailure> failure =
          new AtomicReference<>();

      SwingUtilities.invokeAndWait(
          () ->
              frame.requestManualAutoAnalysisStart(
                  () -> {
                    Lizzie.config.isAutoAna = true;
                    starts.incrementAndGet();
                  },
                  failure::set));

      assertTrue(frame.isManualAutoAnalysisStarting());
      assertNull(frame.analysisEngine);
      assertFalse((boolean) getField(frame, "loadedGameQuickAnalysisActive"));
      assertSame(root, getField(frame, "userCancelledQuickAnalysisRoot"));
      assertEquals(1, engine.normalQuitCount);
      assertEquals(0, starts.get());

      engine.completeExit();
      drainEdt();

      assertEquals(1, starts.get());
      assertNull(failure.get());
      assertFalse(frame.isManualAutoAnalysisStarting());
      assertFalse(invokeShouldAutoQuickAnalyze(frame));

      Lizzie.config.isAutoAna = false;
      assertFalse(invokeShouldAutoQuickAnalyze(frame));

      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      SwingUtilities.invokeAndWait(frame::startNewKifuAnalysisContextAfterSuccessfulLoad);
      assertTrue(invokeShouldAutoQuickAnalyze(frame));
    }
  }

  @Test
  void manualAutoAnalysisDoesNotInterruptUserStartedAnalysis() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      LizzieFrame frame = allocate(LizzieFrame.class);
      ResourceTrackingAnalysisEngine userTask = allocate(ResourceTrackingAnalysisEngine.class);
      userTask.analysisInProgress = true;
      frame.analysisEngine = userTask;
      Lizzie.frame = frame;
      AtomicInteger starts = new AtomicInteger();
      AtomicReference<LizzieFrame.ManualAutoAnalysisStartFailure> failure =
          new AtomicReference<>();

      SwingUtilities.invokeAndWait(
          () ->
              frame.requestManualAutoAnalysisStart(
                  starts::incrementAndGet, failure::set));

      assertSame(userTask, frame.analysisEngine);
      assertEquals(0, userTask.normalQuitCount);
      assertEquals(0, starts.get());
      assertEquals(
          LizzieFrame.ManualAutoAnalysisStartFailure.ANALYSIS_CONFLICT, failure.get());
      assertFalse(frame.isManualAutoAnalysisStarting());
    }
  }

  @Test
  void manualAutoAnalysisDoesNotStartAfterUserTaskClaimsEngineDuringRestore() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      LizzieFrame frame = allocate(LizzieFrame.class);
      ResourceTrackingAnalysisEngine automaticEngine =
          allocate(ResourceTrackingAnalysisEngine.class);
      automaticEngine.shared = true;
      automaticEngine.automatic = true;
      automaticEngine.requestLifecycleInProgress = true;
      frame.analysisEngine = automaticEngine;
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), true);
      AtomicInteger starts = new AtomicInteger();
      AtomicReference<LizzieFrame.ManualAutoAnalysisStartFailure> failure =
          new AtomicReference<>();

      SwingUtilities.invokeAndWait(
          () -> frame.requestManualAutoAnalysisStart(starts::incrementAndGet, failure::set));
      assertTrue(frame.isManualAutoAnalysisStarting());
      assertNull(frame.analysisEngine);

      ResourceTrackingAnalysisEngine userTask = allocate(ResourceTrackingAnalysisEngine.class);
      userTask.analysisInProgress = true;
      userTask.requestLifecycleInProgress = true;
      frame.analysisEngine = userTask;
      automaticEngine.completeExit();
      drainEdt();

      assertEquals(0, starts.get());
      assertEquals(
          LizzieFrame.ManualAutoAnalysisStartFailure.ANALYSIS_CONFLICT, failure.get());
      assertFalse(frame.isManualAutoAnalysisStarting());
      assertSame(userTask, frame.analysisEngine);
      assertEquals(0, userTask.normalQuitCount);
    }
  }

  @Test
  void userFlashAnalysisCancelsPendingManualAutoAnalysisStart() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.config.analysisReuseCurrentEngine = true;
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      LizzieFrame frame = allocate(LizzieFrame.class);
      ResourceTrackingAnalysisEngine automaticEngine =
          allocate(ResourceTrackingAnalysisEngine.class);
      automaticEngine.shared = true;
      automaticEngine.automatic = true;
      automaticEngine.requestLifecycleInProgress = true;
      frame.analysisEngine = automaticEngine;
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), true);
      AtomicInteger starts = new AtomicInteger();
      AtomicReference<LizzieFrame.ManualAutoAnalysisStartFailure> failure =
          new AtomicReference<>();

      SwingUtilities.invokeAndWait(
          () -> frame.requestManualAutoAnalysisStart(starts::incrementAndGet, failure::set));
      assertTrue(frame.isManualAutoAnalysisStarting());

      ResourceTrackingAnalysisEngine userTask = allocate(ResourceTrackingAnalysisEngine.class);
      userTask.reusable = true;
      userTask.waitFrame = allocate(WaitForAnalysis.class);
      frame.analysisEngine = userTask;
      SwingUtilities.invokeAndWait(() -> frame.flashAnalyzeGame(false, false, false));

      assertTrue(userTask.awaitManualRequestStarted());
      automaticEngine.completeExit();
      drainEdt();

      assertEquals(0, starts.get());
      assertEquals(LizzieFrame.ManualAutoAnalysisStartFailure.CANCELLED, failure.get());
      assertFalse(frame.isManualAutoAnalysisStarting());
      assertSame(userTask, frame.analysisEngine);
      assertEquals(0, userTask.normalQuitCount);
    }
  }

  @Test
  void failedAutomaticEngineRestoreDoesNotStartManualAutoAnalysis() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      LizzieFrame frame = allocate(LizzieFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.shared = true;
      engine.automatic = true;
      engine.requestLifecycleInProgress = true;
      frame.analysisEngine = engine;
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), true);
      AtomicInteger starts = new AtomicInteger();
      AtomicReference<LizzieFrame.ManualAutoAnalysisStartFailure> failure =
          new AtomicReference<>();

      SwingUtilities.invokeAndWait(
          () ->
              frame.requestManualAutoAnalysisStart(
                  starts::incrementAndGet, failure::set));
      engine.failExit();
      drainEdt();

      assertEquals(0, starts.get());
      assertEquals(LizzieFrame.ManualAutoAnalysisStartFailure.RELEASE_FAILED, failure.get());
      assertFalse(frame.isManualAutoAnalysisStarting());
      assertTrue(invokeShouldAutoQuickAnalyze(frame));
    }
  }

  @Test
  void changingGamesCancelsPendingManualAutoAnalysisAndDiscardsLateRestore() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      LizzieFrame frame = allocate(LizzieFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.shared = true;
      engine.automatic = true;
      engine.requestLifecycleInProgress = true;
      frame.analysisEngine = engine;
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), true);
      AtomicInteger starts = new AtomicInteger();
      AtomicReference<LizzieFrame.ManualAutoAnalysisStartFailure> failure =
          new AtomicReference<>();

      SwingUtilities.invokeAndWait(
          () ->
              frame.requestManualAutoAnalysisStart(
                  starts::incrementAndGet, failure::set));
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      SwingUtilities.invokeAndWait(frame::startNewKifuAnalysisContextAfterSuccessfulLoad);
      engine.completeExit();
      drainEdt();

      assertEquals(0, starts.get());
      assertEquals(LizzieFrame.ManualAutoAnalysisStartFailure.GAME_CHANGED, failure.get());
      assertFalse(frame.isManualAutoAnalysisStarting());
      assertNull(getField(frame, "userCancelledQuickAnalysisRoot"));
    }
  }

  @Test
  void manualAutoAnalysisWaitsForSlowPrimaryEngineWithoutRetryLimit() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      LoadingLeelaz leelaz = allocate(LoadingLeelaz.class);
      Lizzie.leelaz = leelaz;
      LizzieFrame frame = allocate(LizzieFrame.class);
      Lizzie.frame = frame;
      setField(frame, "quickAnalysisEngineGeneration", new AtomicLong());
      AtomicInteger starts = new AtomicInteger();
      AtomicReference<LizzieFrame.ManualAutoAnalysisStartFailure> failure =
          new AtomicReference<>();

      SwingUtilities.invokeAndWait(
          () ->
              frame.requestManualAutoAnalysisStart(
                  starts::incrementAndGet, failure::set));
      drainEdt();

      assertTrue(frame.isManualAutoAnalysisStarting());
      assertEquals(0, starts.get());
      javax.swing.Timer readinessTimer =
          (javax.swing.Timer) getField(frame, "manualAutoAnalysisEngineReadyTimer");
      assertTrue(readinessTimer.isRunning());

      leelaz.loaded = true;
      SwingUtilities.invokeAndWait(
          () -> readinessTimer.getActionListeners()[0].actionPerformed(null));

      assertEquals(1, starts.get());
      assertNull(failure.get());
      assertFalse(frame.isManualAutoAnalysisStarting());
      assertNull(getField(frame, "manualAutoAnalysisEngineReadyTimer"));
    }
  }

  @Test
  void manualAutoAnalysisWaitsForInvalidatedQuickEngineStartupToFinish() throws Exception {
    try (TestEnvironment env = TestEnvironment.open()) {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      Lizzie.leelaz = allocate(TrackingLeelaz.class);
      LizzieFrame frame = allocate(LizzieFrame.class);
      AtomicBoolean quickEngineStarting = new AtomicBoolean(true);
      setField(frame, "quickAnalysisEngineStarting", quickEngineStarting);
      setField(frame, "quickAnalysisEngineGeneration", new AtomicLong());
      Lizzie.frame = frame;
      AtomicInteger starts = new AtomicInteger();

      SwingUtilities.invokeAndWait(
          () -> frame.requestManualAutoAnalysisStart(starts::incrementAndGet, failure -> {}));
      drainEdt();

      assertTrue(frame.isManualAutoAnalysisStarting());
      assertEquals(0, starts.get());
      javax.swing.Timer readinessTimer =
          (javax.swing.Timer) getField(frame, "manualAutoAnalysisEngineReadyTimer");
      assertTrue(readinessTimer.isRunning());

      quickEngineStarting.set(false);
      SwingUtilities.invokeAndWait(
          () -> readinessTimer.getActionListeners()[0].actionPerformed(null));

      assertEquals(1, starts.get());
      assertFalse(frame.isManualAutoAnalysisStarting());
    }
  }

  @Test
  void rapidDownloadedKifuSwitchKeepsOnlyLatestDeferredLoad() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      LizzieFrame frame = allocate(LizzieFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.shared = true;
      engine.automatic = true;
      engine.requestLifecycleInProgress = true;
      frame.analysisEngine = engine;
      AtomicInteger firstRuns = new AtomicInteger();
      AtomicInteger firstSuperseded = new AtomicInteger();
      AtomicInteger secondRuns = new AtomicInteger();

      assertTrue(
          invokeDeferKifuOpen(
              frame, firstRuns::incrementAndGet, firstSuperseded::incrementAndGet));
      assertTrue(invokeDeferKifuOpen(frame, secondRuns::incrementAndGet, null));

      assertEquals(1, firstSuperseded.get());
      assertEquals(0, firstRuns.get());
      assertEquals(0, secondRuns.get());

      engine.completeExit();
      drainEdt();

      assertEquals(0, firstRuns.get());
      assertEquals(1, secondRuns.get());
      assertFalse((boolean) getField(frame, "kifuOpenWaitingForQuickAnalysisRestore"));
    } finally {
      env.close();
    }
  }

  @Test
  void foregroundAnalysisReleasesIdleDedicatedQuickEngine() throws Exception {
    LizzieFrame frame = allocate(LizzieFrame.class);
    ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
    engine.localDedicated = true;
    frame.analysisEngine = engine;

    assertEquals(
        featurecat.lizzie.analysis.AnalysisResourceCoordinator.ForegroundDecision
            .RELEASE_IDLE_SECONDARY,
        frame.releaseSecondaryAnalysisResourcesForForeground());
    assertNull(frame.analysisEngine);
    assertEquals(1, engine.normalQuitCount);
  }

  @Test
  void foregroundAnalysisPreemptsOnlyAutomaticRunningQuickEngine() throws Exception {
    LizzieFrame frame = allocate(LizzieFrame.class);
    ResourceTrackingAnalysisEngine automatic = allocate(ResourceTrackingAnalysisEngine.class);
    automatic.localDedicated = true;
    automatic.analysisInProgress = true;
    automatic.automatic = true;
    frame.analysisEngine = automatic;

    assertEquals(
        featurecat.lizzie.analysis.AnalysisResourceCoordinator.ForegroundDecision
            .PREEMPT_AUTOMATIC_SECONDARY,
        frame.releaseSecondaryAnalysisResourcesForForeground());
    assertNull(frame.analysisEngine);
    assertEquals(1, automatic.normalQuitCount);

    ResourceTrackingAnalysisEngine userTask = allocate(ResourceTrackingAnalysisEngine.class);
    userTask.localDedicated = true;
    userTask.analysisInProgress = true;
    frame.analysisEngine = userTask;

    assertEquals(
        featurecat.lizzie.analysis.AnalysisResourceCoordinator.ForegroundDecision.KEEP_USER_TASK,
        frame.releaseSecondaryAnalysisResourcesForForeground());
    assertSame(userTask, frame.analysisEngine);
    assertEquals(0, userTask.normalQuitCount);
  }

  @Test
  void mainPonderCannotPreemptActiveLoadedGameQuickAnalysis() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      LizzieFrame frame = allocate(LizzieFrame.class);
      ResourceTrackingAnalysisEngine automatic = allocate(ResourceTrackingAnalysisEngine.class);
      automatic.localDedicated = true;
      automatic.analysisInProgress = true;
      automatic.automatic = true;
      frame.analysisEngine = automatic;
      setField(frame, "loadedGameQuickAnalysisActive", true);
      setField(frame, "loadedGameQuickAnalysisRunning", true);
      setField(frame, "loadedGameQuickAnalysisRoot", Lizzie.board.getHistory().getStart());

      frame.onMainEnginePonder();

      assertSame(automatic, frame.analysisEngine);
      assertEquals(0, automatic.normalQuitCount);
    } finally {
      env.close();
    }
  }

  @Test
  void foregroundAnalysisDoesNotDuplicateAnActiveSharedQuickRequest() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      LizzieFrame frame = allocate(LizzieFrame.class);
      ResourceTrackingAnalysisEngine shared = allocate(ResourceTrackingAnalysisEngine.class);
      shared.shared = true;
      shared.analysisInProgress = true;
      frame.analysisEngine = shared;
      setField(frame, "loadedGameQuickAnalysisActive", true);
      setField(frame, "loadedGameQuickAnalysisRunning", true);
      setField(frame, "loadedGameQuickAnalysisRoot", Lizzie.board.getHistory().getStart());

      assertEquals(
          featurecat.lizzie.analysis.AnalysisResourceCoordinator.ForegroundDecision.SHARED_ENGINE,
          frame.releaseSecondaryAnalysisResourcesForForeground());
      assertTrue(
          (boolean) getField(frame, "loadedGameQuickAnalysisRunning"),
          "foreground activity must not mark a still-running shared request as idle");
      assertSame(shared, frame.analysisEngine);
      invokeStopLoadedGameQuickAnalysisRetry(frame);
    } finally {
      env.close();
    }
  }

  @Test
  void automaticWarmupWithoutAPendingRequestReleasesItsEngine() throws Exception {
    LizzieFrame frame = allocate(LizzieFrame.class);
    ResourceTrackingAnalysisEngine warmed = allocate(ResourceTrackingAnalysisEngine.class);
    warmed.automatic = true;
    warmed.reusable = true;
    setField(frame, "quickAnalysisEngineStarting", new AtomicBoolean(true));
    setField(frame, "quickAnalysisEngineGeneration", new AtomicLong(7L));

    invokeFinishQuickAnalysisEngineWarmup(frame, warmed, 7L);

    assertNull(frame.analysisEngine);
    assertEquals(1, warmed.normalQuitCount);
    assertFalse(((AtomicBoolean) getField(frame, "quickAnalysisEngineStarting")).get());
  }

  @Test
  void startNewGameStopsBeforeMutatingStateWhenForegroundEngineIsReserved() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz engine = new Leelaz("");
    Leelaz.ExclusiveGtpLifecycleReservation reservation =
        engine.beginExclusiveGtpLifecycleReservation();
    NewGameGateFrame frame = allocate(NewGameGateFrame.class);
    try {
      Lizzie.frame = frame;
      Lizzie.leelaz = engine;

      frame.startNewGame();

      assertEquals(1, frame.conflictCount);
      assertFalse(frame.stopAiPlayingCalled);
    } finally {
      if (reservation != null) {
        reservation.close();
      }
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousEngine;
    }
  }

  @Test
  void websocketAdvancedClockStopsContinueGameBeforeCommandsOrGameStateChanges(
      @TempDir Path tempDir) throws Exception {
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    Leelaz previousEngine = Lizzie.leelaz;
    boolean previousEmpty = EngineManager.isEmpty;
    WebSocketClockGateFrame frame = allocate(WebSocketClockGateFrame.class);
    ClockGateLeelaz engine = new ClockGateLeelaz();
    try {
      Lizzie.config = ConfigTestHelper.createForTests(tempDir);
      Lizzie.config.uiConfig = new JSONObject();
      Lizzie.config.advanceTimeSettings = true;
      Lizzie.config.kataTimeSettings = false;
      Lizzie.config.genmoveGameNoTime = false;
      Lizzie.frame = frame;
      Lizzie.leelaz = engine;
      EngineManager.isEmpty = false;

      frame.continueAiPlaying(true, true, true, false);

      assertEquals(1, frame.warningCount);
      assertEquals(0, engine.commandCount);
      assertFalse(frame.isPlayingAgainstLeelaz);
    } finally {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousEngine;
      EngineManager.isEmpty = previousEmpty;
    }
  }

  @Test
  void noAnalyzeFallbackUsesHeldModeReservationAndRestoresPonderingWhenCancelled()
      throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    Leelaz previousEngine = Lizzie.leelaz;
    NoAnalyzeGameFrame frame = allocate(NoAnalyzeGameFrame.class);
    CancelledNewGameDialog dialog = allocate(CancelledNewGameDialog.class);
    NoAnalyzeLeelaz engine = new NoAnalyzeLeelaz();
    frame.dialog = dialog;
    try {
      Lizzie.frame = frame;
      Lizzie.leelaz = engine;

      frame.startAnalyzeGameDialog();

      assertEquals(1, dialog.visibleCount);
      assertEquals(1, frame.createDialogCount);
      assertTrue(frame.modeReservedWhenDialogCreated);
      assertEquals(1, frame.stopAiPlayingCount);
      assertEquals(0, frame.conflictCount);
      assertEquals(2, engine.toggleCount, "cancel must restore the pondering stopped by new game");
      assertTrue(engine.isPondering());
      assertFalse(engine.hasExclusiveGtpWorkInProgress());
    } finally {
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousEngine;
    }
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
  void autoQuickAnalyzeTreatsMetadataOnlyPayloadAsMissing() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithPlaceholderAnalysisMove());
      LizzieFrame frame = allocate(LizzieFrame.class);

      assertTrue(
          invokeShouldAutoQuickAnalyze(frame),
          "engine/header placeholders without visits must not suppress the fast curve.");
    } finally {
      env.close();
    }
  }

  @Test
  void autoQuickAnalyzeTreatsVisitOnlyPlaceholderAsMissing() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithVisitOnlyPlaceholderAnalysisMove());
      LizzieFrame frame = allocate(LizzieFrame.class);

      assertTrue(
          invokeShouldAutoQuickAnalyze(frame),
          "a visit counter without a serialized header or candidate is not a graph result");
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
  void downloadedKifuAnalyzesMetadataOnlySgfPayload()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithPlaceholderAnalysisMove());
      Lizzie.leelaz = null;
      EngineManager.isEmpty = true;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);

      assertTrue(
          frame.ensureAnalysisResumedAfterDownloadedKifuLoad(),
          "downloaded Fox/Tencent records should build the fast graph when SGF has placeholders.");
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
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);

      assertFalse(frame.ensureAnalysisResumedAfterDownloadedKifuLoad());
      assertEquals(0, frame.flashAnalyzeGameCount);
    } finally {
      env.close();
    }
  }

  @Test
  void loadedGameDefersForegroundAnalysisUntilSilentQuickAnalysisCompletes()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      leelaz.pondering = true;
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      Lizzie.frame = frame;

      assertTrue(frame.ensureAnalysisResumedAfterLoad());
      assertEquals(1, frame.flashAnalyzeGameCount);
      assertTrue(frame.lastIsAllGame);
      assertFalse(frame.lastIsAllBranches);
      assertTrue(frame.lastSilentAnalyze);
      assertEquals(0, frame.refreshCount);
      assertEquals(
          0,
          leelaz.ponderCount,
          "foreground analysis must not contend with the automatic quick-curve process.");
    } finally {
      env.close();
    }
  }

  @Test
  void loadedGameResumeSyncsCurrentKifuBeforeForegroundAnalysis() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze(false);
      AnalysisSyncBoard board = analysisSyncBoardWith(historyWithUnanalyzedMove());
      Lizzie.board = board;
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      board.events = leelaz.commands();
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      Lizzie.frame = frame;

      assertTrue(frame.ensureAnalysisResumedAfterLoad());

      assertEquals(0, frame.flashAnalyzeGameCount);
      assertEquals(
          List.of("sync", "ponder"),
          leelaz.commands(),
          "foreground analysis must synchronize the loaded position before pondering.");
    } finally {
      env.close();
    }
  }

  @Test
  void manualPonderStartSyncsCurrentKifuBeforeAnalysis() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze(false);
      AnalysisSyncBoard board = analysisSyncBoardWith(historyWithUnanalyzedMove());
      Lizzie.board = board;
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      board.events = leelaz.commands();
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      ManualPonderTrackingFrame frame = allocate(ManualPonderTrackingFrame.class);
      Lizzie.frame = frame;

      frame.togglePonderMannul();

      assertEquals(
          List.of("sync", "ponder"),
          leelaz.commands(),
          "manual resume must synchronize the current position before pondering.");
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
      AnalysisSyncBoard board = analysisSyncBoardWith(historyWithUnanalyzedMove());
      Lizzie.board = board;
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
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

      Lizzie.board
          .getHistory()
          .getStart()
          .next()
          .orElseThrow()
          .getData()
          .setPlayouts(10);
      Lizzie.board
          .getHistory()
          .getStart()
          .next()
          .orElseThrow()
          .getData()
          .analysisHeaderSlots = 3;

      SwingUtilities.invokeAndWait(engine.completionCallback);
      waitForMovelistRefreshThreads();
      drainEdt();

      assertEquals(
          1,
          leelaz.ponderCount,
          "foreground candidate analysis should restart immediately after fast curve completion.");
      assertEquals(1, board.syncCount);
      assertEquals(1, frame.refreshCount);
      assertEquals(1, frame.problemSnapshotRefreshCount);
      assertEquals(1, frame.silentProgressRefreshCount);
    } finally {
      env.close();
    }
  }

  @Test
  void completedQuickAnalysisRefreshesFinalProgressWithoutOverridingUserPause()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithTargetVisitAnalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      QuickAnalysisResumeFrame frame = allocate(QuickAnalysisResumeFrame.class);
      Lizzie.frame = frame;
      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      armLoadedGameQuickAnalysis(frame, root, true);
      setField(frame, "userAnalysisPaused", true);

      invokeFinishLoadedGameQuickAnalysisAttempt(frame, 17L, root, false);
      waitForMovelistRefreshThreads();
      drainEdt();

      assertEquals(1, frame.problemSnapshotRefreshCount);
      assertEquals(1, frame.silentProgressRefreshCount);
      assertEquals(0, leelaz.ponderCount);
      assertFalse(leelaz.isPondering());
    } finally {
      env.close();
    }
  }

  @Test
  void runningWholeGameAnalysisBlocksSilentQuickAnalysisDispatch() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      QuickAnalysisResumeFrame frame = allocate(QuickAnalysisResumeFrame.class);
      QuickAnalysisCompletionEngine engine = allocate(QuickAnalysisCompletionEngine.class);
      engine.requestStarted = new CountDownLatch(1);
      frame.analysisEngine = engine;
      WholeGameAnalysisSession session = allocate(WholeGameAnalysisSession.class);
      setDeclaredField(
          WholeGameAnalysisSession.class,
          session,
          "state",
          WholeGameAnalysisSession.State.BASELINE);
      setField(frame, "wholeGameAnalysisSession", session);
      Lizzie.frame = frame;

      frame.flashAnalyzeGame(true, false, true);

      assertEquals(1L, engine.requestStarted.getCount());
      assertSame(engine, frame.analysisEngine);
    } finally {
      env.close();
    }
  }

  @Test
  void navigatingDuringWholeGameAnalysisDoesNotScheduleQuickAnalysisContinuation()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      LizzieFrame frame = allocate(LizzieFrame.class);
      WholeGameAnalysisSession session = allocate(WholeGameAnalysisSession.class);
      setDeclaredField(
          WholeGameAnalysisSession.class,
          session,
          "state",
          WholeGameAnalysisSession.State.BASELINE);
      setField(frame, "wholeGameAnalysisSession", session);
      Lizzie.frame = frame;

      SwingUtilities.invokeAndWait(
          frame::scheduleQuickAnalysisContinuationAfterHistoryNavigation);

      assertNull(getField(frame, "quickAnalysisNavigationResumeTimer"));
    } finally {
      env.close();
    }
  }

  @Test
  void wholeGameEngineDoesNotReplaceTheQuickAnalysisEngineSlot() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      LizzieFrame frame = allocate(LizzieFrame.class);
      WholeGameAnalysisSession session = allocate(WholeGameAnalysisSession.class);
      AnalysisEngine quickEngine = allocate(AnalysisEngine.class);
      AnalysisEngine wholeGameEngine = allocate(AnalysisEngine.class);
      frame.analysisEngine = quickEngine;
      setField(frame, "wholeGameAnalysisSession", session);

      frame.attachWholeGameAnalysisEngine(session, wholeGameEngine);

      assertSame(quickEngine, frame.analysisEngine);
    } finally {
      env.close();
    }
  }

  @Test
  void staleWholeGameCompletionCannotDisposeTheCurrentSessionDialog() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      LizzieFrame frame = allocate(LizzieFrame.class);
      WholeGameAnalysisSession staleSession = allocate(WholeGameAnalysisSession.class);
      WholeGameAnalysisSession currentSession = allocate(WholeGameAnalysisSession.class);
      WholeGameAnalysisDialog currentDialog = allocate(WholeGameAnalysisDialog.class);
      setDeclaredField(
          WholeGameAnalysisSession.class,
          staleSession,
          "state",
          WholeGameAnalysisSession.State.COMPLETE);
      setField(frame, "wholeGameAnalysisSession", currentSession);
      setField(frame, "wholeGameAnalysisDialog", currentDialog);

      frame.onWholeGameAnalysisFinished(
          staleSession, allocate(AnalysisEngine.class), false);

      assertSame(currentSession, getField(frame, "wholeGameAnalysisSession"));
      assertSame(currentDialog, getField(frame, "wholeGameAnalysisDialog"));
    } finally {
      env.close();
    }
  }

  @Test
  void downloadedKifuDefersForegroundAnalysisUntilSilentQuickAnalysisCompletes() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithPlaceholderAnalysisMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      Lizzie.frame = frame;

      assertTrue(frame.ensureAnalysisResumedAfterDownloadedKifuLoad());
      assertEquals(1, frame.flashAnalyzeGameCount);
      assertTrue(frame.lastIsAllGame);
      assertFalse(frame.lastIsAllBranches);
      assertTrue(frame.lastSilentAnalyze);
      assertEquals(0, frame.refreshCount);
      assertEquals(0, leelaz.ponderCount);
    } finally {
      env.close();
    }
  }

  @Test
  void winrateGraphNavigationContinuesMissingQuickAnalysisWhenEngineIsIdle() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      AnalysisSyncBoard board = analysisSyncBoardWith(historyWithUnanalyzedMove());
      Lizzie.board = board;
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      QuickAnalysisResumeFrame frame = allocate(QuickAnalysisResumeFrame.class);
      NavigationQuickAnalysisEngine engine = allocate(NavigationQuickAnalysisEngine.class);
      frame.analysisEngine = engine;
      Lizzie.frame = frame;

      SwingUtilities.invokeAndWait(frame::continueQuickAnalysisAfterHistoryNavigationWhenIdle);

      assertTrue(engine.awaitRequestStarted());
      assertEquals(
          0,
          engine.keepAliveCount,
          "automatic curve completion must release its dedicated engine instead of keeping it resident.");
      assertEquals(
          1,
          engine.missingMainlineRequestCount,
          "navigation continuation should fill any remaining fast-curve gaps.");
      assertTrue(
          engine.completionCallback != null,
          "navigation-triggered curve completion should also resume foreground board analysis.");

      Lizzie.board
          .getHistory()
          .getStart()
          .next()
          .orElseThrow()
          .getData()
          .setPlayouts(10);
      Lizzie.board
          .getHistory()
          .getStart()
          .next()
          .orElseThrow()
          .getData()
          .analysisHeaderSlots = 3;

      SwingUtilities.invokeAndWait(engine.completionCallback);

      assertEquals(
          1,
          leelaz.ponderCount,
          "foreground analysis should restart after navigation-triggered curve completion.");
      assertEquals(1, board.syncCount);
      waitForMovelistRefreshThreads();
    } finally {
      env.close();
    }
  }

  @Test
  void winrateGraphNavigationResumesForegroundAnalysisAfterAsyncHandoffFailure() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      AnalysisSyncBoard board = analysisSyncBoardWith(historyWithUnanalyzedMove());
      Lizzie.board = board;
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      QuickAnalysisResumeFrame frame = allocate(QuickAnalysisResumeFrame.class);
      try {
        NavigationQuickAnalysisEngine engine = allocate(NavigationQuickAnalysisEngine.class);
        frame.analysisEngine = engine;
        Lizzie.frame = frame;

        SwingUtilities.invokeAndWait(frame::continueQuickAnalysisAfterHistoryNavigationWhenIdle);
        assertTrue(engine.failureCallback != null);
        SwingUtilities.invokeAndWait(engine.failureCallback);

        assertEquals(1, leelaz.ponderCount);
        assertEquals(1, board.syncCount);
      } finally {
        invokeStopLoadedGameQuickAnalysisRetry(frame);
      }
    } finally {
      env.close();
    }
  }

  @Test
  void yikeCurveCompletionRegistersAsyncHandoffFailure() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      LizzieFrame frame = allocate(LizzieFrame.class);
      NavigationQuickAnalysisEngine engine = allocate(NavigationQuickAnalysisEngine.class);
      frame.analysisEngine = engine;
      Lizzie.frame = frame;

      Method method =
          LizzieFrame.class.getDeclaredMethod(
              "startYikeCurveCompletionRequests", AnalysisEngine.class, String.class);
      method.setAccessible(true);
      AtomicReference<Throwable> invocationFailure = new AtomicReference<>();
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              method.invoke(frame, engine, "test-status");
            } catch (Throwable failure) {
              invocationFailure.set(failure);
            }
          });

      assertNull(invocationFailure.get());
      assertTrue(engine.awaitRequestStarted());
      assertTrue(engine.failureCallback != null);
      assertFalse(
          engine.requestStartedOnEdt,
          "Yike curve request serialization and pipe writes must stay off the Swing EDT.");
    } finally {
      env.close();
    }
  }

  @Test
  void completedYikeCurveRefreshesFinalProgressWithoutOverridingUserPause()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithTargetVisitAnalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      QuickAnalysisResumeFrame frame = allocate(QuickAnalysisResumeFrame.class);
      Lizzie.frame = frame;
      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      setField(frame, "yikeCurveCompletionGeneration", 23L);
      setField(frame, "userAnalysisPaused", true);

      invokeFinishYikeCurveCompletion(frame, 23L, root, false);
      waitForMovelistRefreshThreads();
      drainEdt();

      assertEquals(1, frame.problemSnapshotRefreshCount);
      assertEquals(1, frame.silentProgressRefreshCount);
      assertEquals(0, leelaz.ponderCount);
      assertFalse(leelaz.isPondering());
    } finally {
      env.close();
    }
  }

  @Test
  void quickAnalysisResumeWaitsForNewForegroundEngineWhenReuseIsEnabled() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.config.analysisReuseCurrentEngine = true;
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      LoadingLeelaz leelaz = allocate(LoadingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      QuickAnalysisResumeFrame frame = allocate(QuickAnalysisResumeFrame.class);
      NavigationQuickAnalysisEngine engine = allocate(NavigationQuickAnalysisEngine.class);
      frame.analysisEngine = engine;
      Lizzie.frame = frame;

      SwingUtilities.invokeAndWait(frame::continueQuickAnalysisAfterHistoryNavigationWhenIdle);
      assertEquals(0, engine.missingMainlineRequestCount);

      leelaz.loaded = true;
      SwingUtilities.invokeAndWait(frame::continueQuickAnalysisAfterHistoryNavigationWhenIdle);
      assertTrue(engine.awaitRequestStarted());
      assertEquals(1, engine.missingMainlineRequestCount);
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
  void loadedQuickAnalysisRetryDoesNotDuplicatePendingEngineHandoff() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      Lizzie.frame = frame;

      assertTrue(frame.ensureAnalysisResumedAfterLoad());
      invokeRetryLoadedGameQuickAnalysisIfMissing(frame);
      invokeRetryLoadedGameQuickAnalysisIfMissing(frame);

      assertEquals(
          1,
          frame.flashAnalyzeGameCount,
          "the retry timer must not duplicate a request that is still waiting for engine handoff");
      invokeStopLoadedGameQuickAnalysisRetry(frame);
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
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      Lizzie.frame = frame;

      assertTrue(frame.ensureAnalysisResumedAfterLoad());
      assertEquals(1, frame.flashAnalyzeGameCount);

      for (int retry = 0; retry < 5; retry++) {
        setField(
            frame,
            "loadedGameQuickAnalysisDispatchStartedAt",
            System.currentTimeMillis() - 60_000L);
        invokeRetryLoadedGameQuickAnalysisIfMissing(frame);
      }

      assertEquals(
          6,
          frame.flashAnalyzeGameCount,
          "slow or vanished dispatches must continue beyond the former three-retry limit.");
      assertEquals(
          0,
          leelaz.ponderCount,
          "a retry must not resume the foreground engine while quick analysis is still pending.");
      invokeStopLoadedGameQuickAnalysisRetry(frame);
    } finally {
      env.close();
    }
  }

  @Test
  void failedLoadedGameQuickAnalysisResumesForegroundWithoutCancellingRetry() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      AnalysisSyncBoard board = analysisSyncBoardWith(historyWithUnanalyzedMove());
      Lizzie.board = board;
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      Lizzie.frame = frame;
      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      setField(frame, "loadedGameQuickAnalysisGeneration", 17L);
      setField(frame, "loadedGameQuickAnalysisRoot", root);
      setField(frame, "loadedGameQuickAnalysisActive", true);
      setField(frame, "loadedGameQuickAnalysisRunning", true);

      invokeFinishLoadedGameQuickAnalysisAttempt(frame, 17L, root, true);

      assertTrue((boolean) getField(frame, "loadedGameQuickAnalysisActive"));
      assertEquals(
          1,
          leelaz.ponderCount,
          "users should retain current-position analysis while the curve waits to retry.");
      assertEquals(1, board.syncCount);
      invokeStopLoadedGameQuickAnalysisRetry(frame);
    } finally {
      env.close();
    }
  }

  @Test
  void analysisControlPauseCancelsRunningAutomaticQuickAnalysisAndDoesNotPonder()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      AnalysisSyncBoard board = analysisSyncBoardWith(historyWithUnanalyzedMove());
      Lizzie.board = board;
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.automatic = true;
      engine.localDedicated = true;
      engine.analysisInProgress = true;
      engine.reusable = true;
      frame.analysisEngine = engine;
      Lizzie.frame = frame;
      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      setField(frame, "loadedGameQuickAnalysisGeneration", 17L);
      setField(frame, "loadedGameQuickAnalysisRoot", root);
      setField(frame, "loadedGameQuickAnalysisActive", true);
      setField(frame, "loadedGameQuickAnalysisRunning", true);
      setField(frame, "quickAnalysisEngineGeneration", new AtomicLong(3L));
      setField(frame, "quickAnalysisEngineStarting", new AtomicBoolean(false));
      BoardHistoryNode viewed = Lizzie.board.getHistory().getCurrentHistoryNode();

      frame.togglePonderMannul();
      engine.completeExit();
      drainEdt();

      assertFalse(
          (boolean) getField(frame, "loadedGameQuickAnalysisActive"),
          "analysis control pause must cancel the current automatic kifu quick analysis.");
      assertEquals(1, engine.normalQuitCount);
      assertEquals(
          0,
          leelaz.ponderCount,
          "pausing while automatic quick analysis occupies the control must not start ponder.");
      assertFalse(leelaz.isPondering());
      assertSame(viewed, Lizzie.board.getHistory().getCurrentHistoryNode());
      assertEquals(0, frame.flashAnalyzeGameCount);
    } finally {
      env.close();
    }
  }

  @Test
  void analysisControlPauseCancelsAutomaticRequestOnReusablePreloadedWorker()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.automatic = false;
      engine.analysisInProgress = true;
      engine.reusable = true;
      frame.analysisEngine = engine;
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), true);

      frame.togglePonderMannul();

      assertEquals(
          1,
          engine.normalQuitCount,
          "the automatic request must stop even when it reused a preloaded worker.");
      assertFalse((boolean) getField(frame, "loadedGameQuickAnalysisActive"));
      assertEquals(0, leelaz.ponderCount);
    } finally {
      env.close();
    }
  }

  @Test
  void analysisControlPauseWithoutPrimaryCancelsWaitingAutomaticQuickAnalysis()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      Lizzie.leelaz = null;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), false);

      frame.togglePonderMannul();
      invokeRetryLoadedGameQuickAnalysisIfMissing(frame);

      assertTrue(frame.isUserAnalysisPaused());
      assertFalse((boolean) getField(frame, "loadedGameQuickAnalysisActive"));
      assertEquals(0, frame.flashAnalyzeGameCount);
    } finally {
      env.close();
    }
  }

  @Test
  void analysisControlPauseWithoutPrimaryStopsRunningDedicatedAutomaticWorker()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      Lizzie.leelaz = null;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.automatic = true;
      engine.localDedicated = true;
      engine.analysisInProgress = true;
      engine.reusable = true;
      frame.analysisEngine = engine;
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), true);

      frame.togglePonderMannul();

      assertTrue(frame.isUserAnalysisPaused());
      assertEquals(1, engine.normalQuitCount);
      assertFalse((boolean) getField(frame, "loadedGameQuickAnalysisActive"));
    } finally {
      env.close();
    }
  }

  @Test
  void analysisControlPauseWhileWaitingForResourcesBlocksLaterDispatch() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), false);

      frame.togglePonderMannul();
      invokeRetryLoadedGameQuickAnalysisIfMissing(frame);

      assertFalse((boolean) getField(frame, "loadedGameQuickAnalysisActive"));
      assertEquals(0, frame.flashAnalyzeGameCount);
      assertEquals(0, leelaz.ponderCount);
      assertTrue(Lizzie.config.autoQuickAnalyzeOnLoad);
    } finally {
      env.close();
    }
  }

  @Test
  void analysisControlPauseDiscardsLateAutomaticEngineWarmup() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), true);
      setField(frame, "quickAnalysisEngineStarting", new AtomicBoolean(true));
      setField(frame, "quickAnalysisEngineGeneration", new AtomicLong(7L));
      ResourceTrackingAnalysisEngine warmed = allocate(ResourceTrackingAnalysisEngine.class);
      warmed.automatic = true;
      warmed.reusable = true;

      frame.togglePonderMannul();
      invokeFinishQuickAnalysisEngineWarmup(frame, warmed, 7L);

      assertEquals(1, warmed.normalQuitCount);
      assertNull(frame.analysisEngine);
      assertEquals(0, leelaz.ponderCount);
      assertFalse(leelaz.isPondering());
    } finally {
      env.close();
    }
  }

  @Test
  void analysisControlPauseReturnsSharedForegroundLeaseWithoutPonderOrProcessExit()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      AnalysisSyncBoard board = analysisSyncBoardWith(historyWithUnanalyzedMove());
      Lizzie.board = board;
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.automatic = true;
      engine.shared = true;
      engine.analysisInProgress = true;
      engine.reusable = true;
      frame.analysisEngine = engine;
      Lizzie.frame = frame;
      BoardHistoryNode move =
          Lizzie.board.getHistory().getStart().next().orElseThrow();
      move.getData().setPlayouts(10);
      move.getData().analysisHeaderSlots = 3;
      BoardHistoryNode viewed = Lizzie.board.getHistory().getCurrentHistoryNode();
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), true);

      frame.togglePonderMannul();
      assertEquals(1, engine.normalQuitCount);
      assertEquals(0, leelaz.ponderCount);
      engine.completeExit();
      drainEdt();

      assertTrue(leelaz.isStarted());
      assertFalse(leelaz.isPondering());
      assertEquals(0, leelaz.ponderCount);
      assertSame(viewed, Lizzie.board.getHistory().getCurrentHistoryNode());
      assertEquals(10, move.getData().getPlayouts());
      assertEquals(3, move.getData().analysisHeaderSlots);
      assertFalse((boolean) getField(frame, "loadedGameQuickAnalysisActive"));
    } finally {
      env.close();
    }
  }

  @Test
  void staleAutomaticQuickAnalysisEventsAfterPauseDoNotPonderOrRestart() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      AnalysisSyncBoard board = analysisSyncBoardWith(historyWithUnanalyzedMove());
      Lizzie.board = board;
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.automatic = true;
      engine.localDedicated = true;
      engine.reusable = true;
      frame.analysisEngine = engine;
      Lizzie.frame = frame;
      BoardHistoryNode root = Lizzie.board.getHistory().getStart();
      armLoadedGameQuickAnalysis(frame, root, true);

      frame.togglePonderMannul();
      engine.completeExit();
      drainEdt();

      invokeFinishLoadedGameQuickAnalysisAttempt(frame, 17L, root, false);
      invokeFinishLoadedGameQuickAnalysisAttempt(frame, 17L, root, true);
      setField(
          frame,
          "loadedGameQuickAnalysisDispatchStartedAt",
          System.currentTimeMillis() - 60_000L);
      invokeRetryLoadedGameQuickAnalysisIfMissing(frame);
      SwingUtilities.invokeAndWait(frame::scheduleQuickAnalysisContinuationAfterHistoryNavigation);
      SwingUtilities.invokeAndWait(frame::resumeForegroundAnalysisAfterQuickAnalysisComplete);

      assertEquals(0, frame.flashAnalyzeGameCount);
      assertEquals(0, leelaz.ponderCount);
      assertFalse(leelaz.isPondering());
      assertNull(getField(frame, "quickAnalysisNavigationResumeTimer"));
    } finally {
      env.close();
    }
  }

  @Test
  void analysisControlResumeAfterPauseStartsOnlyCurrentPositionForegroundAnalysis()
      throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      AnalysisSyncBoard board = analysisSyncBoardWith(historyWithUnanalyzedMove());
      Lizzie.board = board;
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      board.events = leelaz.commands();
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.automatic = true;
      engine.localDedicated = true;
      engine.reusable = true;
      frame.analysisEngine = engine;
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), true);

      frame.togglePonderMannul();
      assertFalse(frame.ensureAnalysisResumedAfterLoad());
      assertTrue((boolean) getField(frame, "analysisControlCleanupInProgress"));
      frame.togglePonderMannul();
      assertEquals(0, leelaz.ponderCount);
      engine.completeExit();
      drainEdt();

      assertEquals(List.of("sync", "ponder"), leelaz.commands());
      assertEquals(1, leelaz.ponderCount);
      assertEquals(0, frame.flashAnalyzeGameCount);
      assertFalse((boolean) getField(frame, "loadedGameQuickAnalysisActive"));
    } finally {
      env.close();
    }
  }

  @Test
  void analysisControlResumeAfterFailedSharedCleanupStaysPaused() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      AnalysisSyncBoard board = analysisSyncBoardWith(historyWithUnanalyzedMove());
      Lizzie.board = board;
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      board.events = leelaz.commands();
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.automatic = true;
      engine.shared = true;
      engine.reusable = true;
      frame.analysisEngine = engine;
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), true);

      frame.togglePonderMannul();
      frame.togglePonderMannul();
      engine.failExit();
      drainEdt();

      assertTrue(frame.isUserAnalysisPaused());
      assertFalse((boolean) getField(frame, "analysisControlCleanupInProgress"));
      assertEquals(0, leelaz.ponderCount);
      assertTrue(leelaz.commands().isEmpty());
    } finally {
      env.close();
    }
  }

  @Test
  void newKifuLoadWaitsForSharedCleanupBeforeStartingFreshContext() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.automatic = true;
      engine.shared = true;
      engine.reusable = true;
      frame.analysisEngine = engine;
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), true);
      AtomicInteger newContexts = new AtomicInteger();

      frame.togglePonderMannul();
      Method defer =
          LizzieFrame.class.getDeclaredMethod(
              "deferKifuOpenUntilAutomaticQuickAnalysisRestored", Runnable.class);
      defer.setAccessible(true);
      assertTrue(
          (boolean)
              defer.invoke(
                  frame,
                  (Runnable)
                      () -> {
                        frame.startNewKifuAnalysisContextAfterSuccessfulLoad();
                        newContexts.incrementAndGet();
                      }));

      assertEquals(0, newContexts.get());
      assertTrue(frame.isUserAnalysisPaused());
      assertTrue((boolean) getField(frame, "analysisControlCleanupInProgress"));

      engine.completeExit();
      drainEdt();

      assertEquals(1, newContexts.get());
      assertFalse(frame.isUserAnalysisPaused());
      assertFalse((boolean) getField(frame, "analysisControlCleanupInProgress"));
      assertEquals(0, leelaz.ponderCount);
    } finally {
      env.close();
    }
  }

  @Test
  void dragDropCapturesFilesBeforeWaitingForSharedCleanup(@TempDir Path tempDir) throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      frame.interceptFileLoads = true;
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.automatic = true;
      engine.shared = true;
      engine.reusable = true;
      frame.analysisEngine = engine;
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), true);
      File droppedFile = tempDir.resolve("captured-before-cleanup.sgf").toFile();
      AtomicInteger transferReads = new AtomicInteger();
      Transferable transferable =
          new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
              return new DataFlavor[] {DataFlavor.javaFileListFlavor};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
              return DataFlavor.javaFileListFlavor.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) {
              if (!isDataFlavorSupported(flavor) || transferReads.incrementAndGet() != 1) {
                throw new IllegalStateException("drop transferable is no longer live");
              }
              return Collections.singletonList(droppedFile);
            }
          };

      frame.togglePonderMannul();
      assertTrue(frame.importDroppedKifuFiles(transferable));

      assertEquals(1, transferReads.get());
      assertEquals(0, frame.loadFileCount);
      assertNull(frame.curFile);

      engine.completeExit();
      drainEdt();

      assertEquals(1, transferReads.get());
      assertEquals(1, frame.loadFileCount);
      assertSame(droppedFile, frame.loadedFile);
      assertSame(droppedFile, frame.curFile);
    } finally {
      env.close();
    }
  }

  @Test
  void manualFlashAnalysisTakeoverIsNotCancelledByAnalysisControl() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      leelaz.pondering = true;
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      ManualPonderTrackingFrame frame = allocate(ManualPonderTrackingFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.automatic = true;
      engine.analysisInProgress = true;
      engine.reusable = true;
      engine.waitFrame = allocate(WaitForAnalysis.class);
      frame.analysisEngine = engine;
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), true);

      frame.flashAnalyzeGame(true, false);
      assertTrue(engine.awaitManualRequestStarted());
      frame.togglePonderMannul();

      assertFalse((boolean) getField(frame, "loadedGameQuickAnalysisActive"));
      assertEquals(0, engine.normalQuitCount);
      assertFalse(leelaz.isPondering());
    } finally {
      env.close();
    }
  }

  @Test
  void analysisControlPauseDoesNotCancelManualOrWholeGameAnalysis() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      leelaz.pondering = true;
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.analysisInProgress = true;
      frame.analysisEngine = engine;
      WholeGameAnalysisSession session = allocate(WholeGameAnalysisSession.class);
      setDeclaredField(
          WholeGameAnalysisSession.class,
          session,
          "state",
          WholeGameAnalysisSession.State.BASELINE);
      setField(frame, "wholeGameAnalysisSession", session);
      Lizzie.frame = frame;

      frame.togglePonderMannul();

      assertFalse(leelaz.isPondering());
      assertEquals(0, engine.normalQuitCount);
      assertSame(engine, frame.analysisEngine);
      assertSame(session, getField(frame, "wholeGameAnalysisSession"));
    } finally {
      env.close();
    }
  }

  @Test
  void historyReplacementWithinCurrentLoadContextKeepsUserAnalysisPaused() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.automatic = true;
      engine.reusable = true;
      frame.analysisEngine = engine;
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), true);

      frame.togglePonderMannul();
      engine.completeExit();
      drainEdt();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());

      assertFalse(frame.ensureAnalysisResumedAfterLoad());
      assertTrue(frame.isUserAnalysisPaused());
      assertEquals(0, frame.flashAnalyzeGameCount);
      assertEquals(0, leelaz.ponderCount);
    } finally {
      env.close();
    }
  }

  @Test
  void loadingANewKifuAfterPauseCanStartAFreshAutomaticQuickAnalysis() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      AnalysisSyncBoard board = analysisSyncBoardWith(historyWithUnanalyzedMove());
      Lizzie.board = board;
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.automatic = true;
      engine.reusable = true;
      frame.analysisEngine = engine;
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), true);

      frame.togglePonderMannul();
      engine.completeExit();
      drainEdt();
      Lizzie.board = boardWith(historyWithUnanalyzedMove());
      frame.startNewKifuAnalysisContextAfterSuccessfulLoad();

      assertTrue(frame.ensureAnalysisResumedAfterLoad());
      assertEquals(1, frame.flashAnalyzeGameCount);
      assertTrue(frame.lastSilentAnalyze);
      assertTrue(Lizzie.config.autoQuickAnalyzeOnLoad);
      invokeStopLoadedGameQuickAnalysisRetry(frame);
    } finally {
      env.close();
    }
  }

  @Test
  void placingMovesAfterPauseDoesNotReviveCancelledAutomaticQuickAnalysis() throws Exception {
    TestEnvironment env = TestEnvironment.open();
    try {
      Lizzie.config = configWithAutoQuickAnalyze();
      AnalysisSyncBoard board = analysisSyncBoardWith(historyWithUnanalyzedMove());
      Lizzie.board = board;
      TrackingLeelaz leelaz = allocate(TrackingLeelaz.class);
      Lizzie.leelaz = leelaz;
      EngineManager.isEmpty = false;
      AnalysisResumeTrackingFrame frame = allocate(AnalysisResumeTrackingFrame.class);
      ResourceTrackingAnalysisEngine engine = allocate(ResourceTrackingAnalysisEngine.class);
      engine.automatic = true;
      engine.reusable = true;
      frame.analysisEngine = engine;
      Lizzie.frame = frame;
      armLoadedGameQuickAnalysis(frame, Lizzie.board.getHistory().getStart(), true);

      frame.togglePonderMannul();
      engine.completeExit();
      drainEdt();
      SwingUtilities.invokeAndWait(frame::scheduleQuickAnalysisContinuationAfterHistoryNavigation);

      assertEquals(0, frame.flashAnalyzeGameCount);
      assertEquals(0, leelaz.ponderCount);
      assertFalse((boolean) getField(frame, "loadedGameQuickAnalysisActive"));
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

  private static EmptyEngineUiFrame prepareEmptyEngineUiFrame() throws Exception {
    Config config = allocate(Config.class);
    config.showComment = true;
    config.commentFontSize = 16;
    config.uiFontName = Font.SANS_SERIF;
    config.appendWinrateToComment = false;
    config.UsePlayMode = false;
    Lizzie.config = config;
    installEmptyBoard();
    EmptyEngineUiFrame frame = allocate(EmptyEngineUiFrame.class);
    setField(frame, "cachedComment", "");
    Lizzie.frame = frame;
    Lizzie.leelaz = null;
    EngineManager.isEmpty = true;
    return frame;
  }

  private static void invokeDrawCaptured(
      LizzieFrame frame, Graphics2D graphics, int x, int y, int width, int height, boolean small)
      throws Exception {
    Method method =
        LizzieFrame.class.getDeclaredMethod(
            "drawCaptured",
            Graphics2D.class,
            int.class,
            int.class,
            int.class,
            int.class,
            boolean.class);
    method.setAccessible(true);
    invokeUnchecked(method, frame, graphics, x, y, width, height, small);
  }

  private static void invokeDrawMoveStatistics(
      LizzieFrame frame, Graphics2D graphics, int x, int y, int width, int height)
      throws Exception {
    Method method =
        LizzieFrame.class.getDeclaredMethod(
            "drawMoveStatistics",
            Graphics2D.class,
            int.class,
            int.class,
            int.class,
            int.class);
    method.setAccessible(true);
    invokeUnchecked(method, frame, graphics, x, y, width, height);
  }

  private static void invokeSetComment(LizzieFrame frame, boolean needReaddText) throws Exception {
    Method method = LizzieFrame.class.getDeclaredMethod("setComment", boolean.class);
    method.setAccessible(true);
    invokeUnchecked(method, frame, needReaddText);
  }

  private static void invokeUnchecked(Method method, Object target, Object... arguments) {
    try {
      method.invoke(target, arguments);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new RuntimeException(cause);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }



  private static void armLoadedGameQuickAnalysis(
      LizzieFrame frame, BoardHistoryNode root, boolean running) throws Exception {
    setField(frame, "loadedGameQuickAnalysisGeneration", 17L);
    setField(frame, "loadedGameQuickAnalysisRoot", root);
    setField(frame, "loadedGameQuickAnalysisActive", true);
    setField(frame, "loadedGameQuickAnalysisRunning", running);
    if (running && frame.analysisEngine != null) {
      setField(frame, "loadedGameQuickAnalysisEngine", frame.analysisEngine);
      setField(frame, "loadedGameQuickAnalysisEngineGeneration", 17L);
    }
    setField(frame, "quickAnalysisEngineGeneration", new AtomicLong(3L));
    setField(frame, "quickAnalysisEngineStarting", new AtomicBoolean(false));
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

  private static void invokeFinishLoadedGameQuickAnalysisAttempt(
      LizzieFrame frame, long generation, BoardHistoryNode root, boolean failed) throws Exception {
    Method method =
        LizzieFrame.class.getDeclaredMethod(
            "finishLoadedGameQuickAnalysisAttempt",
            long.class,
            BoardHistoryNode.class,
            boolean.class);
    method.setAccessible(true);
    SwingUtilities.invokeAndWait(
        () -> invokeReflectiveResult(method, frame, generation, root, failed));
  }

  private static void invokeFinishYikeCurveCompletion(
      LizzieFrame frame, long generation, BoardHistoryNode root, boolean failed) throws Exception {
    Method method =
        LizzieFrame.class.getDeclaredMethod(
            "finishYikeCurveCompletion",
            AnalysisEngine.class,
            String.class,
            long.class,
            BoardHistoryNode.class,
            boolean.class,
            AtomicBoolean.class);
    method.setAccessible(true);
    SwingUtilities.invokeAndWait(
        () ->
            invokeReflectiveResult(
                method,
                frame,
                null,
                "test-status",
                generation,
                root,
                failed,
                new AtomicBoolean(false)));
  }

  private static boolean invokeStopBusyQuickAnalysisEngineBeforeLoadedKifuAnalysis(
      LizzieFrame frame, Runnable continuation) throws Exception {
    Method method =
        LizzieFrame.class.getDeclaredMethod(
            "stopBusyQuickAnalysisEngineBeforeLoadedKifuAnalysis", Runnable.class);
    method.setAccessible(true);
    AtomicBoolean stopped = new AtomicBoolean(false);
    SwingUtilities.invokeAndWait(
        () -> stopped.set((boolean) invokeReflectiveResult(method, frame, continuation)));
    return stopped.get();
  }

  private static boolean invokeDeferKifuOpen(LizzieFrame frame, Runnable continuation)
      throws Exception {
    Method method =
        LizzieFrame.class.getDeclaredMethod(
            "deferKifuOpenUntilAutomaticQuickAnalysisRestored", Runnable.class);
    method.setAccessible(true);
    AtomicBoolean deferred = new AtomicBoolean(false);
    SwingUtilities.invokeAndWait(
        () -> deferred.set((boolean) invokeReflectiveResult(method, frame, continuation)));
    return deferred.get();
  }

  private static boolean invokeDeferKifuOpen(
      LizzieFrame frame, Runnable continuation, Runnable superseded) throws Exception {
    Method method =
        LizzieFrame.class.getDeclaredMethod(
            "deferKifuOpenUntilAutomaticQuickAnalysisRestored",
            Runnable.class,
            Runnable.class);
    method.setAccessible(true);
    AtomicBoolean deferred = new AtomicBoolean(false);
    SwingUtilities.invokeAndWait(
        () ->
            deferred.set(
                (boolean) invokeReflectiveResult(method, frame, continuation, superseded)));
    return deferred.get();
  }

  private static Object invokeReflectiveResult(Method method, Object target, Object... arguments) {
    try {
      return method.invoke(target, arguments);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
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

  private static BoardHistoryList historyWithPlaceholderAnalysisMove() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    BoardData placeholder = moveData(new int[] {0, 0}, Stone.BLACK, false, 1, 0);
    placeholder.engineName = "downloaded-placeholder";
    placeholder.analysisHeaderSlots = 3;
    history.add(placeholder);
    return history;
  }

  private static BoardHistoryList historyWithVisitOnlyPlaceholderAnalysisMove() {
    BoardHistoryList history = new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE));
    BoardData placeholder = moveData(new int[] {0, 0}, Stone.BLACK, false, 1, 0);
    placeholder.setPlayouts(targetAnalysisVisitsForTest());
    history.add(placeholder);
    return history;
  }

  private static int targetAnalysisVisitsForTest() {
    return Lizzie.config.analysisMaxVisits + 1;
  }

  private static BoardData moveData(
      int[] lastMove, Stone lastMoveColor, boolean blackToPlay, int moveNumber, int playouts) {
    Stone[] stones = emptyStones();
    stones[Board.getIndex(lastMove[0], lastMove[1])] = lastMoveColor;
    BoardData data =
        BoardData.move(
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
    if (playouts > 0) {
      data.engineName = "saved-analysis";
      data.analysisHeaderSlots = 3;
    }
    return data;
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

  private static AnalysisSyncBoard analysisSyncBoardWith(BoardHistoryList history)
      throws Exception {
    AnalysisSyncBoard board = allocate(AnalysisSyncBoard.class);
    board.setHistory(history);
    board.events = new ArrayList<>();
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

  private static void waitForMovelistRefreshThreads() throws InterruptedException {
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if ("lizzie-movelist-refresh".equals(thread.getName()) && thread.isAlive()) {
        thread.join(1000);
      }
    }
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

  private static void invokeFinishQuickAnalysisEngineWarmup(
      LizzieFrame frame, AnalysisEngine engine, long generation) throws Exception {
    Method method =
        LizzieFrame.class.getDeclaredMethod(
            "finishQuickAnalysisEngineWarmup", AnalysisEngine.class, long.class);
    method.setAccessible(true);
    method.invoke(frame, engine, generation);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = LizzieFrame.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void setDeclaredField(
      Class<?> owner, Object target, String name, Object value) throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }


  private static void installEmptyBoard() throws Exception {
    Board board = allocate(Board.class);
    board.setHistory(new BoardHistoryList(BoardData.empty(BOARD_SIZE, BOARD_SIZE)));
    Lizzie.board = board;
  }



  private static final class EmptyEngineUiFrame extends LizzieFrame {
    @Override
    public void requestProblemListRefresh() {}

    @Override
    public void repaint() {}
  }

  private static final class TestEnvironment implements AutoCloseable {
    private final int previousBoardWidth;
    private final int previousBoardHeight;
    private final Config previousConfig;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final Leelaz previousLeelaz;
    private final boolean previousEngineEmpty;

    private TestEnvironment(
        int previousBoardWidth,
        int previousBoardHeight,
        Config previousConfig,
        Board previousBoard,
        LizzieFrame previousFrame,
        Leelaz previousLeelaz,
        boolean previousEngineEmpty) {
      this.previousBoardWidth = previousBoardWidth;
      this.previousBoardHeight = previousBoardHeight;
      this.previousConfig = previousConfig;
      this.previousBoard = previousBoard;
      this.previousFrame = previousFrame;
      this.previousLeelaz = previousLeelaz;
      this.previousEngineEmpty = previousEngineEmpty;
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
              EngineManager.isEmpty);
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
    private boolean interceptFileLoads;
    private int loadFileCount;
    private File loadedFile;
    private boolean lastIsAllGame;
    private boolean lastIsAllBranches;
    private boolean lastSilentAnalyze;

    @Override
    public boolean loadFile(File file, boolean fromTemp, boolean showHint) {
      if (!interceptFileLoads) {
        return super.loadFile(file, fromTemp, showHint);
      }
      loadFileCount++;
      loadedFile = file;
      return true;
    }

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

    @Override
    public boolean stopAiPlayingAndPolicy() {
      return false;
    }
  }


  private static final class QuickAnalysisResumeFrame extends LizzieFrame {
    private int refreshCount;
    private int problemSnapshotRefreshCount;
    private int silentProgressRefreshCount;

    @Override
    public void refresh() {
      refreshCount++;
    }

    @Override
    public void refreshProblemListSnapshot() {
      problemSnapshotRefreshCount++;
    }

    @Override
    public void refreshSilentAnalysisProgress() {
      silentProgressRefreshCount++;
    }
  }

  private static final class ResourceTrackingAnalysisEngine extends AnalysisEngine {
    private boolean shared;
    private boolean localDedicated;
    private boolean analysisInProgress;
    private boolean automatic;
    private boolean reusable;
    private boolean requestLifecycleInProgress;
    private CountDownLatch manualRequestStarted;
    private int normalQuitCount;
    private Runnable exitContinuation;
    private Runnable exitFailureContinuation;

    @SuppressWarnings("unused")
    private ResourceTrackingAnalysisEngine() throws java.io.IOException {
      super(false);
    }

    @Override
    public boolean usesSharedForegroundEngine() {
      return shared;
    }

    @Override
    public boolean isLoaded() {
      return reusable;
    }

    @Override
    public boolean isRunning() {
      return reusable;
    }

    @Override
    public boolean matchesCurrentAnalysisBackend() {
      return reusable;
    }

    @Override
    public boolean isLocalDedicatedProcess() {
      return localDedicated;
    }

    @Override
    public synchronized boolean isAnalysisInProgress() {
      return analysisInProgress;
    }

    @Override
    public synchronized boolean hasRequestLifecycleInProgress() {
      return requestLifecycleInProgress || analysisInProgress;
    }

    @Override
    public boolean isAutomaticBackgroundTask() {
      return automatic;
    }

    @Override
    public void startRequest(int startMove, int endMove, boolean showProgressDialog) {
      analysisInProgress = true;
      manualRequestStartedLatch().countDown();
    }

    private boolean awaitManualRequestStarted() throws InterruptedException {
      return manualRequestStartedLatch().await(2, TimeUnit.SECONDS);
    }

    private synchronized CountDownLatch manualRequestStartedLatch() {
      if (manualRequestStarted == null) {
        manualRequestStarted = new CountDownLatch(1);
      }
      return manualRequestStarted;
    }

    @Override
    public void clearRequestCallbacks() {}

    @Override
    public void normalQuit() {
      normalQuitCount++;
    }

    @Override
    public void normalQuit(Runnable afterRestore) {
      normalQuitCount++;
      exitContinuation = afterRestore;
      exitFailureContinuation = afterRestore;
    }

    @Override
    public void normalQuit(Runnable afterRestore, Runnable afterRestoreFailure) {
      normalQuitCount++;
      exitContinuation = afterRestore;
      exitFailureContinuation = afterRestoreFailure;
    }

    private void completeExit() {
      Runnable continuation = exitContinuation;
      exitContinuation = null;
      exitFailureContinuation = null;
      if (continuation != null) {
        continuation.run();
      }
    }

    private void failExit() {
      Runnable continuation = exitFailureContinuation;
      exitContinuation = null;
      exitFailureContinuation = null;
      if (continuation != null) {
        continuation.run();
      }
    }
  }

  private static final class ManualPonderTrackingFrame extends LizzieFrame {
    @Override
    public boolean stopAiPlayingAndPolicy() {
      return false;
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
    public int startRequestMissingMainline(boolean showProgressDialog) {
      lastShowProgressDialog = showProgressDialog;
      requestStarted.countDown();
      return 1;
    }
  }

  private static final class NavigationQuickAnalysisEngine extends AnalysisEngine {
    private boolean analysisInProgress;
    private int keepAliveCount;
    private int missingMainlineRequestCount;
    private CountDownLatch requestStarted = new CountDownLatch(1);
    private Runnable completionCallback;
    private Runnable failureCallback;
    private volatile boolean requestStartedOnEdt;

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
    public void setFailureCallback(Runnable failureCallback) {
      this.failureCallback = failureCallback;
    }

    @Override
    public int startRequestMissingMainline(boolean showProgressDialog) {
      missingMainlineRequestCount++;
      requestStartedOnEdt = SwingUtilities.isEventDispatchThread();
      requestStartedLatch().countDown();
      return 1;
    }

    private boolean awaitRequestStarted() throws InterruptedException {
      return requestStartedLatch().await(2, TimeUnit.SECONDS);
    }

    private synchronized CountDownLatch requestStartedLatch() {
      if (requestStarted == null) {
        requestStarted = new CountDownLatch(1);
      }
      return requestStarted;
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

    @Override
    public void notPondering() {
      pondering = false;
    }

    @Override
    public void nameCmd() {
      commands().add("name");
    }
  }

  private static final class AnalysisSyncBoard extends Board {
    private int syncCount;
    private List<String> events;

    private AnalysisSyncBoard() {
      super();
    }

    @Override
    public boolean resendCurrentPositionToPrimaryEngine() {
      syncCount++;
      events.add("sync");
      return true;
    }
  }

  private static final class LoadingLeelaz extends TrackingLeelaz {
    private boolean loaded;

    private LoadingLeelaz() throws java.io.IOException {
      super();
    }

    @Override
    public boolean isLoaded() {
      return loaded;
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

  private static final class NewGameGateFrame extends LizzieFrame {
    private int conflictCount;
    private boolean stopAiPlayingCalled;

    private NewGameGateFrame() {
      super();
    }

    @Override
    protected void showForegroundEngineLeaseConflict() {
      conflictCount++;
    }

    @Override
    public boolean stopAiPlayingAndPolicy() {
      stopAiPlayingCalled = true;
      return false;
    }
  }

  private static final class WebSocketClockGateFrame extends LizzieFrame {
    private int warningCount;

    private WebSocketClockGateFrame() {
      super();
    }

    @Override
    public void showUnsupportedWebSocketAdvancedClock() {
      warningCount++;
    }
  }

  private static final class ClockGateLeelaz extends Leelaz {
    private int commandCount;

    private ClockGateLeelaz() throws Exception {
      super(RemoteComputeConfig.COMMAND_CUSTOM_WS);
    }

    @Override
    public void sendCommand(String command) {
      commandCount++;
    }
  }

  private static final class NoAnalyzeGameFrame extends LizzieFrame {
    private CancelledNewGameDialog dialog;
    private int conflictCount;
    private int stopAiPlayingCount;
    private int createDialogCount;
    private boolean modeReservedWhenDialogCreated;

    private NoAnalyzeGameFrame() {
      super();
    }

    @Override
    protected NewGameDialog createNewGameDialog() {
      createDialogCount++;
      modeReservedWhenDialogCreated = Lizzie.leelaz.hasExclusiveGtpWorkInProgress();
      return dialog;
    }


    @Override
    protected void showForegroundEngineLeaseConflict() {
      conflictCount++;
    }

    @Override
    protected void showForegroundEngineModeReservationConflict() {
      conflictCount++;
    }

    @Override
    public boolean stopAiPlayingAndPolicy() {
      stopAiPlayingCount++;
      return false;
    }
  }


  private static final class CancelledNewGameDialog extends NewGameDialog {
    private int visibleCount;

    private CancelledNewGameDialog() {
      super((Window) null);
    }

    @Override
    public void setVisible(boolean visible) {
      if (visible) {
        visibleCount++;
      }
    }

    @Override
    public boolean playerIsBlack() {
      return true;
    }

    @Override
    public boolean isCancelled() {
      return true;
    }

    @Override
    public void dispose() {}
  }

  private static final class NoAnalyzeLeelaz extends Leelaz {
    private boolean pondering = true;
    private int toggleCount;

    private NoAnalyzeLeelaz() throws Exception {
      super("");
      noAnalyze = true;
    }

    @Override
    public boolean isPondering() {
      return pondering;
    }

    @Override
    public void togglePonder() {
      toggleCount++;
      pondering = !pondering;
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
