package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KataGoAutoSetupDialogLayoutTest {
  @Test
  void missingSetupPathsAreHandledAsAbsentFiles() {
    assertFalse(KataGoAutoSetupDialog.isRegularFile(null));
  }

  @Test
  void windowsGpuDetectionDoesNotDependOnTensorRtInstallEligibility() {
    assertTrue(KataGoAutoSetupDialog.shouldStartNvidiaGpuDetection("Windows 11", false, false));
    assertFalse(KataGoAutoSetupDialog.shouldStartNvidiaGpuDetection("Windows 11", true, false));
    assertFalse(KataGoAutoSetupDialog.shouldStartNvidiaGpuDetection("Windows 11", false, true));
    assertFalse(KataGoAutoSetupDialog.shouldStartNvidiaGpuDetection("Mac OS X", false, false));
  }

  @Test
  void sidebarRendererFontDoesNotGrowAcrossRepaints() {
    Font listFont = new Font(Font.DIALOG, Font.PLAIN, 13);

    Font first = KataGoAutoSetupDialog.deriveSidebarNavFont(listFont, null, false);
    Font second = KataGoAutoSetupDialog.deriveSidebarNavFont(listFont, first, true);

    assertEquals(14.5f, first.getSize2D());
    assertEquals(first.getSize2D(), second.getSize2D());
    assertEquals(Font.BOLD, second.getStyle());
  }

  @Test
  void sidebarExpandsForThaiNavigationWithoutConsumingUnboundedSpace() {
    JLabel measurementLabel = new JLabel();
    Font selectedFont =
        KataGoAutoSetupDialog.deriveSidebarNavFont(
            new Font(Font.DIALOG, Font.PLAIN, 13), null, true);
    FontMetrics metrics = measurementLabel.getFontMetrics(selectedFont);
    String thaiAcceleration = "เร่งความเร็ว NVIDIA GPU";

    int thaiWidth = KataGoAutoSetupDialog.localizedSidebarWidth(metrics, thaiAcceleration);

    assertTrue(thaiWidth > 218, "the Thai acceleration label must expand the fixed-width sidebar");
    assertTrue(
        thaiWidth >= metrics.stringWidth(thaiAcceleration) + 100,
        "the expanded width must include icon, gap, renderer, and sidebar insets");
    assertEquals(218, KataGoAutoSetupDialog.localizedSidebarWidth(metrics, "概览"));
    assertEquals(
        330,
        KataGoAutoSetupDialog.localizedSidebarWidth(
            metrics, "A deliberately extreme navigation label that must remain bounded"));
  }

  @Test
  void officialWeightsUseACompactSixRowScrollingViewport() {
    assertEquals(206, KataGoAutoSetupDialog.weightCatalogVisibleHeight(6));
    assertEquals(6, KataGoAutoSetupDialog.weightCatalogViewportRows(16));
    assertEquals(2, KataGoAutoSetupDialog.weightCatalogViewportRows(2));
    assertEquals(2, KataGoAutoSetupDialog.weightCatalogViewportRows(0));
    assertTrue(
        KataGoAutoSetupDialog.weightCatalogVisibleHeight(16)
            > KataGoAutoSetupDialog.weightCatalogVisibleHeight(6));
  }

  @Test
  void eloColumnShowsOnlyTheReadableRatingValue() {
    assertEquals(
        "14,552", KataGoAutoSetupDialog.compactEloRating("14551.5 ± 26.0 - (3,325 games)"));
    assertEquals("-", KataGoAutoSetupDialog.compactEloRating("not rated"));
  }

  @Test
  void weightColumnsReserveReadableSpaceBetweenDateAndStatus() {
    JPanel row = new JPanel();
    JLabel model = new JLabel("zhizi 40B s11272M d5935M");
    JLabel elo = new JLabel("14,552");
    JLabel date = new JLabel("2026-06-06");
    JLabel status = new JLabel("下载");

    KataGoAutoSetupDialog.configureWeightCatalogColumns(row, model, elo, date, status);
    row.setSize(892, 34);
    row.doLayout();

    assertEquals(330, model.getWidth(), "model column must stop growing after its useful width");
    assertEquals(338, elo.getX(), "Elo should move left into a stable numeric column");
    assertEquals(20, date.getX() - (elo.getX() + elo.getWidth()));
    assertEquals(26, status.getX() - (date.getX() + date.getWidth()));
    assertEquals(892, status.getX() + status.getWidth(), "the columns should use the full row");
    assertEquals(JLabel.LEFT, model.getHorizontalAlignment());
    assertEquals(JLabel.CENTER, elo.getHorizontalAlignment());
    assertEquals(JLabel.CENTER, date.getHorizontalAlignment());
    assertEquals(JLabel.CENTER, status.getHorizontalAlignment());
  }

  @Test
  void onlyTheVisibleStatusButtonAreaTriggersDirectDownload() {
    int listWidth = 892;

    assertFalse(KataGoAutoSetupDialog.isWeightCatalogDownloadHit(724, listWidth));
    assertTrue(KataGoAutoSetupDialog.isWeightCatalogDownloadHit(725, listWidth));
    assertTrue(KataGoAutoSetupDialog.isWeightCatalogDownloadHit(840, listWidth));
    assertFalse(KataGoAutoSetupDialog.isWeightCatalogDownloadHit(841, listWidth));
  }

  @Test
  void downloadedWeightMatchingPrefersThePathUsedByTheCurrentEngine() {
    Path bundledAlias = Path.of("weights", "default.bin.gz").toAbsolutePath().normalize();
    Path activeZhizi =
        Path.of("weights", "kata1-zhizi-b28c512nbt-muonfd2.bin.gz").toAbsolutePath().normalize();
    Path other = Path.of("weights", "other.bin.gz").toAbsolutePath().normalize();

    List<Path> ordered =
        KataGoAutoSetupDialog.prioritizeActiveWeightCandidate(
            activeZhizi, List.of(bundledAlias, activeZhizi, other));

    assertEquals(List.of(activeZhizi, bundledAlias, other), ordered);
  }

  @Test
  void recommendationIconRemainsCenteredWhenTheBadgeHasItsOwnWidth() {
    Rectangle iconBounds =
        KataGoAutoSetupDialog.centeredRecommendationIconBounds(300, 40, new Dimension(40, 40));

    assertEquals(130, iconBounds.x);
    assertEquals(0, iconBounds.y);
    assertEquals(150, iconBounds.getCenterX());
  }

  @Test
  void recommendationCardsExposeTheNextUsefulAction() {
    assertEquals(
        KataGoAutoSetupDialog.RecommendationAction.DOWNLOAD,
        KataGoAutoSetupDialog.recommendationAction(false, false));
    assertEquals(
        KataGoAutoSetupDialog.RecommendationAction.USE,
        KataGoAutoSetupDialog.recommendationAction(true, false));
    assertEquals(
        KataGoAutoSetupDialog.RecommendationAction.CURRENT,
        KataGoAutoSetupDialog.recommendationAction(true, true));
  }

  @Test
  void downloadedRecommendationWaitsForCompatibleValidatedEngine() {
    assertFalse(
        KataGoAutoSetupDialog.recommendationActionEnabled(
            KataGoAutoSetupDialog.RecommendationAction.USE, true, false, true));
    assertFalse(
        KataGoAutoSetupDialog.recommendationActionEnabled(
            KataGoAutoSetupDialog.RecommendationAction.USE, true, true, false));
    assertTrue(
        KataGoAutoSetupDialog.recommendationActionEnabled(
            KataGoAutoSetupDialog.RecommendationAction.USE, true, true, true));
    assertTrue(
        KataGoAutoSetupDialog.recommendationActionEnabled(
            KataGoAutoSetupDialog.RecommendationAction.DOWNLOAD, true, false, false));
  }

  @Test
  void longAccelerationActionsWrapIntoTwoColumns() {
    JButton[] actions = {
      new JButton("ตรวจสอบแพ็คเกจ NVIDIA"),
      new JButton("ติดตั้งการเร่งความเร็ว TensorRT"),
      new JButton("เปลี่ยนกลับเป็น CUDA"),
      new JButton("ทำความสะอาดแคช TensorRT")
    };

    int singleRowWidth = 0;
    int tallestAction = 0;
    for (JButton action : actions) {
      Dimension preferred = action.getPreferredSize();
      singleRowWidth += preferred.width;
      tallestAction = Math.max(tallestAction, preferred.height);
    }

    JPanel actionBar = KataGoAutoSetupDialog.createActionBar(FlowLayout.RIGHT, actions);
    Dimension wrapped = actionBar.getPreferredSize();

    assertTrue(wrapped.width < singleRowWidth, "four actions must not force a single wide row");
    assertTrue(wrapped.height > tallestAction, "four actions must occupy at least two rows");
  }

  @Test
  void weightActionsStayInlineAtTheDefaultDialogWidth() {
    JComboBox<String> selector = new JComboBox<String>();
    selector.setPreferredSize(new Dimension(390, 36));
    JButton use = new JButton("使用此权重");
    JButton importWeight = new JButton("导入自定义权重");

    JPanel row = KataGoAutoSetupDialog.createResponsiveActionRow(selector, use, importWeight);
    row.setSize(640, 80);
    row.doLayout();

    assertTrue(selector.getWidth() >= 220);
    assertTrue(use.getX() > selector.getX());
    assertTrue(use.getY() < selector.getHeight());
    assertTrue(importWeight.getX() > use.getX());
  }

  @Test
  void longLocalizedWeightActionsWrapWithoutClipping() {
    JComboBox<String> selector = new JComboBox<String>();
    selector.setPreferredSize(new Dimension(390, 36));
    JButton use = new JButton("ใช้โมเดลที่เลือกกับ KataGo");
    JButton importWeight = new JButton("นำเข้าโมเดลที่กำหนดเองจากคอมพิวเตอร์");

    JPanel row = KataGoAutoSetupDialog.createResponsiveActionRow(selector, use, importWeight);
    row.setSize(430, 180);
    row.doLayout();

    assertTrue(use.getY() >= selector.getHeight());
    assertTrue(importWeight.getY() >= selector.getHeight());
    assertTrue(use.getX() >= 0 && use.getX() + use.getWidth() <= row.getWidth());
    assertTrue(
        importWeight.getX() >= 0
            && importWeight.getX() + importWeight.getWidth() <= row.getWidth());
  }

  @Test
  void localizedExperimentalBackendActionWrapsWithoutClipping() {
    JComboBox<String> selector =
        new JComboBox<String>(
            new String[] {"OpenVINO (หน่วยประมวลผลประสาท Intel NPU)"});
    selector.setPreferredSize(new Dimension(390, 36));
    JButton install = new JButton("ติดตั้งและใช้แบ็กเอนด์ทดลองที่เลือก");

    JPanel row = KataGoAutoSetupDialog.createResponsiveActionRow(selector, install);
    row.setSize(420, 120);
    row.doLayout();

    assertEquals(0, selector.getX());
    assertEquals(row.getWidth(), selector.getWidth());
    assertTrue(install.getY() >= selector.getHeight());
    assertTrue(install.getX() >= 0 && install.getX() + install.getWidth() <= row.getWidth());
    assertTrue(row.getPreferredSize().height >= install.getY() + install.getHeight());
  }

  @Test
  void detailCardsAlwaysTrackTheViewportWidth() {
    KataGoAutoSetupDialog.ViewportWidthPanel cards =
        new KataGoAutoSetupDialog.ViewportWidthPanel(new CardLayout());

    assertTrue(cards.getScrollableTracksViewportWidth());
    assertFalse(cards.getScrollableTracksViewportHeight());
  }

  @Test
  void cardLayoutMeasuresOnlyTheVisiblePage() {
    KataGoAutoSetupDialog.ActiveCardLayout layout = new KataGoAutoSetupDialog.ActiveCardLayout();
    JPanel cards = new JPanel(layout);
    JPanel compact = new JPanel();
    compact.setPreferredSize(new Dimension(500, 220));
    JPanel tall = new JPanel();
    tall.setPreferredSize(new Dimension(500, 760));
    cards.add(compact, "compact");
    cards.add(tall, "tall");

    layout.show(cards, "compact");
    int compactHeight = layout.preferredLayoutSize(cards).height;
    layout.show(cards, "tall");
    int tallHeight = layout.preferredLayoutSize(cards).height;

    assertTrue(compactHeight < 300);
    assertTrue(tallHeight > compactHeight);
  }

  @Test
  void localizedButtonWidthIncludesTheEntireThaiLabel() {
    JButton button = new JButton("นำเข้าโมเดลที่กำหนดเองจากคอมพิวเตอร์");

    Dimension size = KataGoAutoSetupDialog.localizedButtonSize(button, 90, 32);
    int textWidth = button.getFontMetrics(button.getFont()).stringWidth(button.getText());

    assertTrue(size.width >= textWidth + button.getInsets().left + button.getInsets().right);
  }

  @Test
  void rowLabelExpandsForLocalizedText() {
    JLabel label = new JLabel("TensorRT download and configuration status");

    int width = KataGoAutoSetupDialog.localizedRowLabelWidth(label);

    assertTrue(width > 132);
    assertTrue(width <= 240);
  }

  @Test
  void longStatusWrapsAndKeepsPlainAccessibleName() {
    JLabel label = new JLabel();
    label.setPreferredSize(new Dimension(390, 30));
    String status = "Ready | C:\\ailearn3\\" + "very-long-runtime-path\\".repeat(12);

    KataGoAutoSetupDialog.setWrappedInfoText(label, status);

    assertTrue(label.getText().startsWith("<html>"));
    assertTrue(
        label.getPreferredSize().height >= label.getFontMetrics(label.getFont()).getHeight() * 3,
        "a later long backend path must replace the label's old one-line height");
    assertTrue(label.getAccessibleContext().getAccessibleName().equals(status));
  }

  @Test
  void wrappedStatusCanShrinkAgainAfterBackendSwitch() {
    JLabel label = new JLabel();

    KataGoAutoSetupDialog.setWrappedInfoText(label, "Ready");
    int shortHeight = label.getPreferredSize().height;
    KataGoAutoSetupDialog.setWrappedInfoText(label, "runtime-path-without-spaces-".repeat(18));
    int longHeight = label.getPreferredSize().height;
    KataGoAutoSetupDialog.setWrappedInfoText(label, "Ready");

    assertTrue(longHeight > shortHeight);
    assertEquals(shortHeight, label.getPreferredSize().height);
  }

  @Test
  void tensorRtRepairSummaryWrapsInsideABoundedKeyboardDialog() {
    String summary =
        "Repaired C:\\ailearn3\\"
            + "long-managed-tensorrt-path\\".repeat(14)
            + ". Runtime ready.";

    JTextArea textArea = KataGoAutoSetupDialog.createTensorRtRepairSummaryText(summary);

    assertTrue(textArea.getLineWrap());
    assertTrue(textArea.getWrapStyleWord());
    assertFalse(textArea.isEditable());
    assertTrue(textArea.getPreferredSize().width < 900);
    assertEquals(summary, textArea.getAccessibleContext().getAccessibleName());
  }

  @Test
  void weightSwitchOnlyInterruptsRecoverableQuickAnalysis() {
    assertEquals(
        KataGoAutoSetupDialog.WeightSwitchPreparation.READY,
        KataGoAutoSetupDialog.decideWeightSwitchPreparation(false, false, false, false, false));
    assertEquals(
        KataGoAutoSetupDialog.WeightSwitchPreparation.RESET_QUICK_ANALYSIS,
        KataGoAutoSetupDialog.decideWeightSwitchPreparation(true, true, true, false, false));
    assertEquals(
        KataGoAutoSetupDialog.WeightSwitchPreparation.WAIT_FOR_QUICK_ANALYSIS,
        KataGoAutoSetupDialog.decideWeightSwitchPreparation(true, true, true, true, true));
    assertEquals(
        KataGoAutoSetupDialog.WeightSwitchPreparation.BLOCKED_BY_ANALYSIS,
        KataGoAutoSetupDialog.decideWeightSwitchPreparation(true, true, false, true, true));
    assertEquals(
        KataGoAutoSetupDialog.WeightSwitchPreparation.BLOCKED_BY_ENGINE_TASK,
        KataGoAutoSetupDialog.decideWeightSwitchPreparation(true, false, false, true, false));
  }

  @Test
  void appliedWeightWaitsForTheExactEngineSwitchTokenAndFinalActivePhase() {
    long expectedToken = 41L;

    assertEquals(
        KataGoAutoSetupDialog.AppliedEngineWaitState.WAITING,
        KataGoAutoSetupDialog.evaluateAppliedEngineWait(
            expectedToken,
            EngineManager.EngineSwitchUiPhase.SWITCHING,
            0,
            expectedToken,
            1));
    assertEquals(
        KataGoAutoSetupDialog.AppliedEngineWaitState.ACTIVE,
        KataGoAutoSetupDialog.evaluateAppliedEngineWait(
            expectedToken,
            EngineManager.EngineSwitchUiPhase.ACTIVE,
            1,
            expectedToken,
            1));
    assertEquals(
        KataGoAutoSetupDialog.AppliedEngineWaitState.FAILED,
        KataGoAutoSetupDialog.evaluateAppliedEngineWait(
            expectedToken,
            EngineManager.EngineSwitchUiPhase.FAILED,
            0,
            expectedToken,
            1));
    assertEquals(
        KataGoAutoSetupDialog.AppliedEngineWaitState.SUPERSEDED,
        KataGoAutoSetupDialog.evaluateAppliedEngineWait(
            expectedToken + 1,
            EngineManager.EngineSwitchUiPhase.ACTIVE,
            1,
            expectedToken,
            1));
  }

  @Test
  void acceptedWeightGetsOneEdtPumpBeforeBlockingCatalogWorkStarts() throws Exception {
    CountDownLatch catalogLoadEntered = new CountDownLatch(1);
    CountDownLatch releaseCatalogLoad = new CountDownLatch(1);
    CountDownLatch firstPaintPumpEntered = new CountDownLatch(1);
    CountDownLatch releaseFirstPaintPump = new CountDownLatch(1);

    SwingUtilities.invokeAndWait(
        () -> {
          KataGoAutoSetupDialog.dispatchWeightSwitchReloadWork(
              () -> {
                catalogLoadEntered.countDown();
                try {
                  assertTrue(releaseCatalogLoad.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(interrupted);
                }
              },
              73L,
              null);
          SwingUtilities.invokeLater(
              () -> {
                firstPaintPumpEntered.countDown();
                try {
                  assertTrue(releaseFirstPaintPump.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(interrupted);
                }
              });
        });
    assertTrue(firstPaintPumpEntered.await(2, TimeUnit.SECONDS));

    assertEquals(
        1L,
        catalogLoadEntered.getCount(),
        "the first post-acceptance EDT pump must remain available for painting");
    releaseFirstPaintPump.countDown();
    assertTrue(catalogLoadEntered.await(2, TimeUnit.SECONDS));
    releaseCatalogLoad.countDown();
  }

  @Test
  void engineReloadOutcomeCannotCrossManagerIdentity() throws Exception {
    EngineManager previous = Lizzie.engineManager;
    EngineManager first = newEngineManager();
    EngineManager replacement = newEngineManager();
    try {
      Lizzie.engineManager = first;
      assertTrue(KataGoAutoSetupDialog.isCurrentEngineManager(first));
      Lizzie.engineManager = replacement;
      assertFalse(
          KataGoAutoSetupDialog.isCurrentEngineManager(first),
          "an old switch snapshot must not be paired with a replacement manager");
      assertTrue(KataGoAutoSetupDialog.isCurrentEngineManager(replacement));
    } finally {
      Lizzie.engineManager = previous;
    }
  }

  private static EngineManager newEngineManager() throws Exception {
    java.lang.reflect.Constructor<EngineManager> constructor =
        EngineManager.class.getDeclaredConstructor(List.class);
    constructor.setAccessible(true);
    return constructor.newInstance(List.of());
  }

  @Test
  void acceptedWeightSwitchPublishesTargetImmediatelyOnTheEventDispatchThread(@TempDir Path tempDir)
      throws IOException {
    SetupSnapshot current = createSetupSnapshot(tempDir, "current.bin.gz");
    SetupSnapshot requested = createSetupSnapshot(tempDir, "requested.bin.gz");
    AtomicReference<KataGoAutoSetupDialog.WeightSwitchDisplayState> displayed =
        new AtomicReference<>(KataGoAutoSetupDialog.WeightSwitchDisplayState.active(current));
    AtomicBoolean updatedOnEdt = new AtomicBoolean(false);

    KataGoAutoSetupDialog.runWeightSwitchUiUpdate(
        () -> {
          displayed.set(displayed.get().begin(requested));
          updatedOnEdt.set(SwingUtilities.isEventDispatchThread());
        });

    assertSame(
        requested,
        displayed.get().bannerSnapshot(),
        "the target must be visible before readiness polling");
    assertTrue(displayed.get().isPendingWeight(requested.activeWeightPath));
    assertFalse(
        displayed.get().isActiveWeight(requested.activeWeightPath),
        "a requested weight must not be reported active before engine readiness");
    assertTrue(displayed.get().isActiveWeight(current.activeWeightPath));
    assertTrue(updatedOnEdt.get(), "Swing model and labels must only be updated on the EDT");
  }

  @Test
  void terminalFailedWeightSwitchReconcilesWithTheAuthoritativeSetupSnapshot(
      @TempDir Path tempDir)
      throws IOException {
    SetupSnapshot current = createSetupSnapshot(tempDir, "current.bin.gz");
    SetupSnapshot requested = createSetupSnapshot(tempDir, "requested.bin.gz");
    SetupSnapshot authoritative = createSetupSnapshot(tempDir, "authoritative.bin.gz");
    KataGoAutoSetupDialog.WeightSwitchDisplayState pending =
        KataGoAutoSetupDialog.WeightSwitchDisplayState.active(current).begin(requested);
    KataGoAutoSetupDialog.WeightSwitchDisplayState displayed =
        pending.fail(pending.token(), authoritative);

    assertSame(
        authoritative,
        displayed.displayedSnapshot(),
        "a terminal failure must replace the optimistic object with the latest setup");
    assertEquals(
        "authoritative.bin.gz",
        displayed.displayedSnapshot().activeWeightPath.getFileName().toString());
    assertTrue(displayed.isFailed());
    assertFalse(displayed.isActiveWeight(requested.activeWeightPath));
    assertFalse(displayed.isActiveWeight(authoritative.activeWeightPath));
  }

  @Test
  void timedOutWeightSwitchNeverClaimsTheUnreadyTargetAsActive(@TempDir Path tempDir)
      throws IOException {
    SetupSnapshot current = createSetupSnapshot(tempDir, "current.bin.gz");
    SetupSnapshot requested = createSetupSnapshot(tempDir, "requested.bin.gz");
    SetupSnapshot staleAuthoritative = createSetupSnapshot(tempDir, "current.bin.gz");
    KataGoAutoSetupDialog.WeightSwitchDisplayState pending =
        KataGoAutoSetupDialog.WeightSwitchDisplayState.active(current).begin(requested);
    KataGoAutoSetupDialog.WeightSwitchDisplayState displayed =
        pending.fail(pending.token(), staleAuthoritative);

    assertSame(
        staleAuthoritative,
        displayed.displayedSnapshot(),
        "the catalog may reconcile with discovery, but no weight is active after readiness timeout");
    assertFalse(displayed.isActiveWeight(requested.activeWeightPath));
    assertTrue(displayed.isFailed());
  }

  @Test
  void diskDiscoveryAloneNeverClaimsThatAWeightIsActive(@TempDir Path tempDir)
      throws IOException {
    SetupSnapshot discovered = createSetupSnapshot(tempDir, "discovered.bin.gz");

    KataGoAutoSetupDialog.WeightSwitchDisplayState displayed =
        KataGoAutoSetupDialog.WeightSwitchDisplayState.discovered(discovered);

    assertSame(discovered, displayed.displayedSnapshot());
    assertFalse(displayed.isActive());
    assertFalse(displayed.isActiveWeight(discovered.activeWeightPath));
  }

  @Test
  void delayedSuccessfulWeightSwitchKeepsTheTargetModelCurrent(@TempDir Path tempDir)
      throws IOException {
    SetupSnapshot current = createSetupSnapshot(tempDir, "current.bin.gz");
    SetupSnapshot requested = createSetupSnapshot(tempDir, "requested.bin.gz");
    KataGoAutoSetupDialog.WeightSwitchDisplayState pending =
        KataGoAutoSetupDialog.WeightSwitchDisplayState.active(current).begin(requested);
    KataGoAutoSetupDialog.WeightSwitchDisplayState displayed =
        pending.succeed(pending.token(), requested);

    assertSame(requested, displayed.displayedSnapshot());
    assertEquals(
        "requested.bin.gz",
        displayed.displayedSnapshot().activeWeightPath.getFileName().toString());
    assertTrue(displayed.isActiveWeight(requested.activeWeightPath));
    assertEquals(KataGoAutoSetupDialog.WeightSwitchDisplayPhase.SUCCEEDED, displayed.phase());
  }

  @Test
  void rapidWeightSwitchIgnoresOldCallbacksThatArriveLate(@TempDir Path tempDir)
      throws IOException {
    SetupSnapshot current = createSetupSnapshot(tempDir, "current.bin.gz");
    SetupSnapshot firstTarget = createSetupSnapshot(tempDir, "first.bin.gz");
    SetupSnapshot secondTarget = createSetupSnapshot(tempDir, "second.bin.gz");
    SetupSnapshot authoritative = createSetupSnapshot(tempDir, "authoritative.bin.gz");
    KataGoAutoSetupDialog.WeightSwitchDisplayState first =
        KataGoAutoSetupDialog.WeightSwitchDisplayState.active(current).begin(firstTarget);
    KataGoAutoSetupDialog.WeightSwitchDisplayState failed =
        first.fail(first.token(), authoritative);
    KataGoAutoSetupDialog.WeightSwitchDisplayState refreshed = failed.reconcile(authoritative);
    KataGoAutoSetupDialog.WeightSwitchDisplayState second = refreshed.begin(secondTarget);

    assertTrue(second.token() > first.token(), "refresh must not reset the monotonic token");
    assertSame(second, second.succeed(first.token(), firstTarget));
    assertSame(second, second.fail(first.token(), authoritative));
    assertTrue(second.isPendingWeight(secondTarget.activeWeightPath));

    KataGoAutoSetupDialog.WeightSwitchDisplayState completed =
        second.succeed(second.token(), secondTarget);
    assertTrue(completed.isActiveWeight(secondTarget.activeWeightPath));
    assertFalse(completed.isActiveWeight(firstTarget.activeWeightPath));
  }

  private SetupSnapshot createSetupSnapshot(Path tempDir, String weightFileName)
      throws IOException {
    Path engine = tempDir.resolve("katago.exe");
    Path gtpConfig = tempDir.resolve("gtp.cfg");
    Path analysisConfig = tempDir.resolve("analysis.cfg");
    Path weight = tempDir.resolve(weightFileName);
    Files.writeString(engine, "test engine");
    Files.writeString(gtpConfig, "test gtp config");
    Files.writeString(analysisConfig, "test analysis config");
    Files.writeString(weight, "test weight");
    return KataGoAutoSetupHelper.inspectSelectedLocalKataGo(engine, gtpConfig, weight).toSnapshot();
  }
}
