package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class KataGoAutoSetupDialogLayoutTest {
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
  void localizedWeightActionsStackBelowTheSelector() {
    JComboBox<String> selector = new JComboBox<String>();
    selector.setPreferredSize(new Dimension(390, 36));
    JButton use = new JButton("ใช้โมเดลที่เลือกกับ KataGo");
    JButton importWeight = new JButton("นำเข้าโมเดลที่กำหนดเองจากคอมพิวเตอร์");

    int inlineWidth =
        selector.getPreferredSize().width
            + use.getPreferredSize().width
            + importWeight.getPreferredSize().width;
    JPanel row = KataGoAutoSetupDialog.createStackedActionRow(selector, use, importWeight);

    assertTrue(row.getPreferredSize().width < inlineWidth);
    assertTrue(
        row.getPreferredSize().height
            > selector.getPreferredSize().height + use.getPreferredSize().height * 2);
  }

  @Test
  void detailCardsAlwaysTrackTheViewportWidth() {
    KataGoAutoSetupDialog.ViewportWidthPanel cards =
        new KataGoAutoSetupDialog.ViewportWidthPanel(new CardLayout());

    assertTrue(cards.getScrollableTracksViewportWidth());
    assertFalse(cards.getScrollableTracksViewportHeight());
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
}
