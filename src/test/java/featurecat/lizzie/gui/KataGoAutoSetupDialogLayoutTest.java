package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.JButton;
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
