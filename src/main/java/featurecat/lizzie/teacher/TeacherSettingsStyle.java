package featurecat.lizzie.teacher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Image;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicButtonUI;

/** Shared, theme-aware settings controls; no authentication or persisted state. */
final class TeacherSettingsStyle {
  private TeacherSettingsStyle() {}

  static String text(String key, String fallback) {
    return TeacherStrings.get("Teacher.settings.design." + key, fallback);
  }

  static Font font(float size, boolean bold) {
    Font base = UIManager.getFont("Label.font");
    return new Font(base.getFamily(), bold ? Font.BOLD : Font.PLAIN, Math.round(size));
  }

  static Color surface() {
    return dark() ? TeacherDialogStyle.surface() : Color.WHITE;
  }

  static Color railSurface() {
    return dark() ? TeacherDialogStyle.railSurface() : new Color(244, 248, 247);
  }

  private static boolean dark() {
    Color color = TeacherDialogStyle.surface();
    return color.getRed() + color.getGreen() + color.getBlue() < 360;
  }

  static JPanel panel(java.awt.LayoutManager layout) {
    JPanel panel = new JPanel(layout);
    panel.setOpaque(false);
    return panel;
  }

  static JLabel label(String text, float size, boolean bold) {
    JLabel label = new JLabel(text);
    label.setFont(font(size, bold));
    label.setForeground(TeacherDialogStyle.text());
    return label;
  }

  static JTextArea note(String text, int rows) {
    JTextArea note = new JTextArea(text, rows, 1);
    note.setEditable(false);
    note.setFocusable(false);
    note.setLineWrap(true);
    note.setWrapStyleWord(true);
    note.setOpaque(false);
    note.setBorder(null);
    note.setFont(font(14, false));
    note.setForeground(TeacherDialogStyle.muted());
    note.getAccessibleContext().setAccessibleName(text);
    ((javax.swing.text.DefaultCaret) note.getCaret())
        .setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
    return note;
  }

  static JPanel heading(String title, String subtitle) {
    JPanel panel = panel(new BorderLayout(0, 6));
    panel.add(label(title, 26, true), BorderLayout.NORTH);
    panel.add(note(subtitle, 1), BorderLayout.CENTER);
    panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
    return panel;
  }

  static void input(JComponent input) {
    TeacherDialogStyle.styleInput(input);
    input.setFont(font(15, false));
    input.setBackground(surface());
    if (input instanceof javax.swing.JComboBox<?> combo) {
      combo.setUI(
          new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override
            protected javax.swing.JButton createArrowButton() {
              javax.swing.JButton button = new javax.swing.JButton(icon("chevron-down", 16));
              button.setUI(new BasicButtonUI());
              button.setContentAreaFilled(false);
              button.setBorder(BorderFactory.createEmptyBorder());
              return button;
            }

            @Override
            public void paintCurrentValueBackground(
                Graphics g, java.awt.Rectangle bounds, boolean focus) {
              g.setColor(surface());
              g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }

            @Override
            public void paintCurrentValue(Graphics g, java.awt.Rectangle bounds, boolean focus) {
              java.awt.Component renderer =
                  comboBox
                      .getRenderer()
                      .getListCellRendererComponent(
                          listBox, comboBox.getSelectedItem(), -1, false, false);
              renderer.setFont(font(15, false));
              renderer.setBackground(surface());
              renderer.setForeground(TeacherDialogStyle.text());
              currentValuePane.paintComponent(
                  g, renderer, comboBox, bounds.x, bounds.y, bounds.width, bounds.height, true);
            }
          });
      combo.setRenderer(
          new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                javax.swing.JList<?> list,
                Object value,
                int index,
                boolean selected,
                boolean focus) {
              super.getListCellRendererComponent(list, value, index, selected, focus);
              setFont(font(15, false));
              setBackground(selected ? TeacherDialogStyle.accentSoft() : surface());
              setForeground(TeacherDialogStyle.text());
              return this;
            }
          });
      combo.getEditor().getEditorComponent().setFont(font(15, false));
      combo.getEditor().getEditorComponent().setBackground(surface());
    }
    input.setPreferredSize(new Dimension(220, 38));
    input.setMinimumSize(new Dimension(80, 38));
  }

  static void button(AbstractButton button, boolean primary) {
    button.setFont(font(15, false));
    if (primary) TeacherDialogStyle.stylePrimary(button);
    else
      TeacherDialogStyle.styleButton(
          button, surface(), TeacherDialogStyle.text(), TeacherDialogStyle.border());
    button.setFont(font(15, true));
    button.setMargin(new java.awt.Insets(9, 16, 9, 16));
    button.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
    button.getAccessibleContext().setAccessibleDescription(button.getText());
  }

  static void link(AbstractButton button) {
    button(button, false);
    button.setUI(new BasicButtonUI());
    button.setForeground(TeacherDialogStyle.accent());
    button.setBorder(BorderFactory.createEmptyBorder(7, 0, 7, 0));
    button.setFocusPainted(true);
    button.setHorizontalAlignment(SwingConstants.LEADING);
  }

  static Icon icon(String name, int size) {
    var resource =
        TeacherSettingsStyle.class.getResource("/assets/teacher-settings/" + name + ".png");
    if (resource == null) return null;
    return new ImageIcon(
        new ImageIcon(resource).getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH));
  }

  static void selection(AbstractButton button, boolean sidebar) {
    button.setFont(font(16, true));
    button.setContentAreaFilled(false);
    button.setOpaque(false);
    button.setFocusPainted(false);
    button.setRolloverEnabled(true);
    button.setHorizontalAlignment(sidebar ? SwingConstants.LEADING : SwingConstants.CENTER);
    button.setIconTextGap(12);
    button.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
    button.setUI(
        new BasicButtonUI() {
          @Override
          public void paint(Graphics graphics, JComponent component) {
            boolean selected = button.isSelected();
            button.setForeground(
                selected ? TeacherDialogStyle.accent() : TeacherDialogStyle.text());
            if (selected || button.getModel().isRollover()) {
              graphics.setColor(
                  selected ? TeacherDialogStyle.accentSoft() : TeacherDialogStyle.railSurface());
              graphics.fillRoundRect(0, 0, component.getWidth(), component.getHeight(), 8, 8);
            }
            if (selected) {
              graphics.setColor(TeacherDialogStyle.accent());
              if (sidebar) graphics.fillRect(0, 8, 3, component.getHeight() - 16);
              else graphics.fillRect(0, component.getHeight() - 3, component.getWidth(), 3);
            }
            if (button.hasFocus()) {
              graphics.setColor(TeacherDialogStyle.accent());
              graphics.drawRoundRect(
                  3, 3, component.getWidth() - 7, component.getHeight() - 7, 6, 6);
            }
            super.paint(graphics, component);
          }
        });
    button.getAccessibleContext().setAccessibleDescription(button.getText());
  }
}
