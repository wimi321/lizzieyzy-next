package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class KataGoAutoSetupDialogLayoutTest {
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
        "14,552",
        KataGoAutoSetupDialog.compactEloRating("14551.5 ± 26.0 - (3,325 games)"));
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
        Path.of("weights", "kata1-zhizi-b28c512nbt-muonfd2.bin.gz")
            .toAbsolutePath()
            .normalize();
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
    String status =
        "TensorRT acceleration is available only in the Windows NVIDIA package and must not be clipped";

    KataGoAutoSetupDialog.setWrappedInfoText(label, status);

    assertTrue(label.getText().startsWith("<html>"));
    assertTrue(label.getPreferredSize().height > 30);
    assertTrue(label.getAccessibleContext().getAccessibleName().equals(status));
  }

  @Test
  void weightSwitchOnlyInterruptsRecoverableQuickAnalysis() {
    assertEquals(
        KataGoAutoSetupDialog.WeightSwitchPreparation.READY,
        KataGoAutoSetupDialog.decideWeightSwitchPreparation(
            false, false, false, false, false));
    assertEquals(
        KataGoAutoSetupDialog.WeightSwitchPreparation.RESET_QUICK_ANALYSIS,
        KataGoAutoSetupDialog.decideWeightSwitchPreparation(
            true, true, true, false, false));
    assertEquals(
        KataGoAutoSetupDialog.WeightSwitchPreparation.WAIT_FOR_QUICK_ANALYSIS,
        KataGoAutoSetupDialog.decideWeightSwitchPreparation(
            true, true, true, true, true));
    assertEquals(
        KataGoAutoSetupDialog.WeightSwitchPreparation.BLOCKED_BY_ANALYSIS,
        KataGoAutoSetupDialog.decideWeightSwitchPreparation(
            true, true, false, true, true));
    assertEquals(
        KataGoAutoSetupDialog.WeightSwitchPreparation.BLOCKED_BY_ENGINE_TASK,
        KataGoAutoSetupDialog.decideWeightSwitchPreparation(
            true, false, false, true, false));
  }
}
