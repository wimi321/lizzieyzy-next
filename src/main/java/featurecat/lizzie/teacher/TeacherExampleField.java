package featurecat.lizzie.teacher;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.JTextField;

/** An example is painted only; it never becomes input or saved configuration. */
final class TeacherExampleField extends JTextField {
  private final String example;

  TeacherExampleField(String example, int columns) {
    super(columns);
    this.example = example;
    getAccessibleContext().setAccessibleDescription(example);
    addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusGained(FocusEvent event) {
            repaint();
          }

          @Override
          public void focusLost(FocusEvent event) {
            repaint();
          }
        });
  }

  boolean isExampleVisible() {
    return getText().isEmpty() && !hasFocus();
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    super.paintComponent(graphics);
    if (!isExampleVisible()) return;
    Graphics2D copy = (Graphics2D) graphics.create();
    try {
      var insets = getInsets();
      copy.clipRect(
          insets.left,
          insets.top,
          Math.max(0, getWidth() - insets.left - insets.right),
          Math.max(0, getHeight() - insets.top - insets.bottom));
      copy.setColor(TeacherSettingsStyle.muted());
      copy.setFont(getFont());
      var metrics = copy.getFontMetrics();
      int baseline =
          insets.top
              + (getHeight() - insets.top - insets.bottom - metrics.getHeight()) / 2
              + metrics.getAscent();
      copy.drawString(example, insets.left, baseline);
    } finally {
      copy.dispose();
    }
  }
}
