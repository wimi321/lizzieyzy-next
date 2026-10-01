package featurecat.lizzie.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ResourceBundle;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.text.View;

/** A compact, wrapping explanation next to the measurements, never a warning or a modal. */
final class B11SpeedNoticePanel extends JPanel {
  B11SpeedNoticePanel(ResourceBundle strings, Font font) {
    super(new BorderLayout(0, 4));
    setOpaque(false);
    setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
    JTextArea title =
        text(
            strings.getString("B11SpeedNotice.title"),
            font.deriveFont(Font.BOLD, font.getSize2D() + 3f));
    title.setForeground(AppleStyleSupport.workspaceAccent());
    JTextArea detail = text(strings.getString("B11SpeedNotice.description"), font);
    detail.setForeground(AppleStyleSupport.dialogTextColor());
    add(title, BorderLayout.NORTH);
    add(detail, BorderLayout.CENTER);
    getAccessibleContext().setAccessibleName(title.getText());
    getAccessibleContext().setAccessibleDescription(detail.getText());
    setName("b11-speed-notice");
  }

  private static JTextArea text(String text, Font font) {
    JTextArea area =
        new JTextArea(text) {
          @Override
          public Dimension getPreferredSize() {
            int width = getParent() == null ? 560 : getParent().getWidth();
            if (width <= 0) width = 560;
            var insets = getInsets();
            View view = getUI().getRootView(this);
            view.setSize(Math.max(1, width - insets.left - insets.right), Integer.MAX_VALUE);
            return new Dimension(
                width,
                (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS)) + insets.top + insets.bottom);
          }
        };
    area.setFont(font);
    area.setEditable(false);
    ((javax.swing.text.DefaultCaret) area.getCaret())
        .setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
    area.setFocusable(false);
    area.setOpaque(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setBorder(BorderFactory.createEmptyBorder());
    area.getAccessibleContext().setAccessibleName(text);
    return area;
  }
}
