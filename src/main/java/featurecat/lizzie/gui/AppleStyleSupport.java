package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.theme.MorandiPalette;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.text.JTextComponent;

public final class AppleStyleSupport {
  private static final String BUTTON_ROLE = "lizzie.apple.button.role";
  private static final String ROLE_PRIMARY = "primary";
  private static final String ROLE_DANGER = "danger";
  private static final String LEGACY_BUTTON_UI = "lizzie.apple.legacy.button.ui";
  private static final String LEGACY_CHECKBOX_ICON = "lizzie.apple.legacy.checkbox.icon";
  private static final String LEGACY_COMBO_RENDERER = "lizzie.apple.legacy.combo.renderer";
  private static final Insets TEXT_BUTTON_INSETS = new Insets(0, 12, 0, 12);
  private static final Insets ICON_BUTTON_INSETS = new Insets(0, 6, 0, 6);
  private static final Insets FIELD_INSETS = new Insets(4, 8, 4, 8);

  private AppleStyleSupport() {}

  public static boolean isAppleStyleEnabled() {
    return Lizzie.config != null && Lizzie.config.isAppleStyle;
  }

  private static boolean isInsideTopHeader(Component component) {
    Container parent = component != null ? component.getParent() : null;
    while (parent != null) {
      if (parent instanceof TopHeaderPanel) {
        return true;
      }
      parent = parent.getParent();
    }
    return false;
  }

  public static void markPrimary(AbstractButton button) {
    if (button != null) {
      button.putClientProperty(BUTTON_ROLE, ROLE_PRIMARY);
    }
  }

  public static void markDanger(AbstractButton button) {
    if (button != null) {
      button.putClientProperty(BUTTON_ROLE, ROLE_DANGER);
    }
  }

  public static void applyUiDefaults() {
    UIManager.put("Panel.background", dialogSurfaceColor());
    UIManager.put("OptionPane.background", dialogSurfaceColor());
    UIManager.put("OptionPane.foreground", controlTextColor());
    UIManager.put("MenuBar.background", topStripColor());
    UIManager.put("MenuBar.foreground", controlTextColor());
    UIManager.put("Menu.background", popupSurfaceColor());
    UIManager.put("Menu.selectionBackground", popupSelectionColor());
    UIManager.put("Menu.selectionForeground", controlTextColor());
    UIManager.put("Menu.foreground", controlTextColor());
    UIManager.put("MenuItem.background", popupSurfaceColor());
    UIManager.put("MenuItem.selectionBackground", popupSelectionColor());
    UIManager.put("MenuItem.selectionForeground", controlTextColor());
    UIManager.put("MenuItem.foreground", controlTextColor());
    UIManager.put("PopupMenu.background", popupSurfaceColor());
    UIManager.put("PopupMenu.foreground", controlTextColor());
    UIManager.put("ToolBar.background", topStripColor());
    UIManager.put("ToolBar.foreground", controlTextColor());
    UIManager.put("Button.background", buttonFillColor(false, false, false, null));
    UIManager.put("Button.foreground", controlTextColor());
    UIManager.put("CheckBox.background", dialogSurfaceColor());
    UIManager.put("CheckBox.foreground", controlTextColor());
    UIManager.put("ComboBox.background", fieldBackgroundColor());
    UIManager.put("ComboBox.foreground", controlTextColor());
    UIManager.put("TextField.background", fieldBackgroundColor());
    UIManager.put("TextField.foreground", controlTextColor());
    UIManager.put("TextField.caretForeground", controlTextColor());
    UIManager.put("TextField.selectionBackground", accentFillColor(170));
    UIManager.put("TextField.selectionForeground", Color.WHITE);
  }

  public static void refreshMainChrome() {
    applyUiDefaults();
    if (Lizzie.frame == null) {
      return;
    }
    SwingUtilities.updateComponentTreeUI(Lizzie.frame);
    if (LizzieFrame.menu != null) {
      applyToContainer(LizzieFrame.menu);
    }
    if (Lizzie.frame.topPanel != null) {
      applyToolbarContainerStyle(Lizzie.frame.topPanel);
      applyToContainer(Lizzie.frame.topPanel);
    }
    if (LizzieFrame.toolbar != null) {
      LizzieFrame.toolbar.refreshComponentStyles();
    }
    if (Lizzie.frame.sidebarPanel != null) {
      applyToContainer(Lizzie.frame.sidebarPanel);
    }
    Lizzie.frame.backgroundPaint = null;
    Lizzie.frame.redrawBackgroundAnyway = true;
    Lizzie.frame.revalidate();
    Lizzie.frame.repaint();
    Lizzie.frame.refresh();
  }

  public static void applyToolbarContainerStyle(JComponent component) {
    if (component == null) {
      return;
    }
    component.setOpaque(false);
    component.setForeground(controlTextColor());
  }

  public static void applyPanelStyle(JComponent component) {
    if (component == null) {
      return;
    }
    component.setOpaque(false);
    component.setForeground(controlTextColor());
  }

  public static void installButtonStyle(AbstractButton button) {
    if (button == null) {
      return;
    }
    // Preserve manually set sizes (e.g. BottomToolbar buttons sized before styling)
    Dimension savedPref = button.isPreferredSizeSet() ? button.getPreferredSize() : null;
    Dimension savedMin = button.isMinimumSizeSet() ? button.getMinimumSize() : null;

    button.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    button.setForeground(buttonTextColor(button));

    button.setFocusPainted(false);
    button.setBorderPainted(false);
    button.setContentAreaFilled(false);
    button.setOpaque(false);
    button.setRolloverEnabled(true);

    boolean isTopHeaderBtn = isInsideTopHeader(button);

    if (!(button instanceof JMenuItem) && button.getClientProperty(LEGACY_BUTTON_UI) == null) {
      button.setUI(new ToolbarButtonUI());
      button.putClientProperty(LEGACY_BUTTON_UI, Boolean.TRUE);
    }

    if (isTopHeaderBtn) {
      button.setForeground(Color.WHITE);
    }
    Insets insets = isIconOnly(button) ? ICON_BUTTON_INSETS : TEXT_BUTTON_INSETS;
    button.setBorder(new EmptyBorder(insets));
    if (button.getHorizontalAlignment() == SwingConstants.CENTER) {
      button.setHorizontalAlignment(SwingConstants.CENTER);
    }

    // Restore manually set sizes so layout calculations remain correct
    if (savedPref != null) {
      button.setPreferredSize(savedPref);
      button.setSize(savedPref);
    }
    if (savedMin != null) {
      button.setMinimumSize(savedMin);
    }
  }

  public static void installCheckBoxStyle(AbstractButton button) {
    if (button == null) {
      return;
    }

    boolean isTopHeaderBtn = isInsideTopHeader(button);

    button.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    button.setOpaque(false);
    button.setContentAreaFilled(false);
    button.setForeground(isTopHeaderBtn ? Color.WHITE : controlTextColor());
    button.setFocusPainted(false);
    button.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 4));
    button.setIcon(new AppleCheckIcon(false));
    button.setSelectedIcon(new AppleCheckIcon(true));
    button.setDisabledIcon(new AppleCheckIcon(false));
    button.setDisabledSelectedIcon(new AppleCheckIcon(true));
    button.setIconTextGap(6);
    button.putClientProperty(LEGACY_CHECKBOX_ICON, Boolean.TRUE);
  }

  public static void installRadioButtonStyle(AbstractButton button) {
    if (button == null) {
      return;
    }

    boolean isTopHeaderBtn = isInsideTopHeader(button);

    button.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    button.setOpaque(false);
    button.setContentAreaFilled(false);
    button.setForeground(isTopHeaderBtn ? Color.WHITE : controlTextColor());
    button.setFocusPainted(false);
    button.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 4));
    button.setIcon(new AppleRadioIcon(false));
    button.setSelectedIcon(new AppleRadioIcon(true));
    button.setDisabledIcon(new AppleRadioIcon(false));
    button.setDisabledSelectedIcon(new AppleRadioIcon(true));
    button.setIconTextGap(6);
    button.putClientProperty(LEGACY_CHECKBOX_ICON, Boolean.TRUE);
  }

  public static void installTextFieldStyle(JTextComponent textComponent) {
    if (textComponent == null) {
      return;
    }

    boolean isTopHeaderBtn = isInsideTopHeader(textComponent);

    textComponent.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    textComponent.setOpaque(true);
    textComponent.setBackground(
        isTopHeaderBtn ? new Color(255, 255, 255, 40) : fieldBackgroundColor());
    textComponent.setForeground(isTopHeaderBtn ? Color.WHITE : controlTextColor());
    textComponent.setCaretColor(isTopHeaderBtn ? Color.WHITE : controlTextColor());
    textComponent.setSelectionColor(accentFillColor(isAppleStyleEnabled() ? 190 : 140));
    textComponent.setSelectedTextColor(Color.WHITE);
    Border outer = new RoundedLineBorder(controlBorderColor(), controlCornerRadius());
    textComponent.setBorder(new CompoundBorder(outer, new EmptyBorder(FIELD_INSETS)));
  }

  public static void installComboBoxStyle(JComboBox<?> comboBox) {
    if (comboBox == null) {
      return;
    }
    comboBox.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    comboBox.setOpaque(true);
    comboBox.setBackground(fieldBackgroundColor());
    comboBox.setForeground(controlTextColor());
    comboBox.setBorder(
        new CompoundBorder(
            new RoundedLineBorder(controlBorderColor(), controlCornerRadius()),
            new EmptyBorder(0, 6, 0, 4)));
    if (comboBox.getClientProperty(LEGACY_COMBO_RENDERER) == null) {
      comboBox.setRenderer(
          new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                javax.swing.JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
              JLabel label =
                  (JLabel)
                      super.getListCellRendererComponent(
                          list, value, index, isSelected, cellHasFocus);
              label.setOpaque(true);
              label.setBorder(new EmptyBorder(4, 8, 4, 8));
              label.setBackground(isSelected ? popupSelectionColor() : popupSurfaceColor());
              label.setForeground(controlTextColor());
              return label;
            }
          });
      comboBox.putClientProperty(LEGACY_COMBO_RENDERER, Boolean.TRUE);
    }
  }

  public static void installPopupStyle(JPopupMenu popupMenu) {
    if (popupMenu == null) {
      return;
    }
    popupMenu.setOpaque(true);
    popupMenu.setBackground(popupSurfaceColor());
    popupMenu.setBorder(
        new CompoundBorder(
            new RoundedLineBorder(controlBorderColor(), controlCornerRadius()),
            new EmptyBorder(4, 4, 4, 4)));
  }

  public static void styleMenuItem(AbstractButton menuItem) {
    if (menuItem == null) {
      return;
    }
    menuItem.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    menuItem.setOpaque(true);
    menuItem.setBackground(popupSurfaceColor());
    menuItem.setForeground(controlTextColor());
    menuItem.setFocusPainted(false);
    menuItem.setBorder(new EmptyBorder(4, 8, 4, 8));
  }

  public static void styleLabel(JLabel label) {
    if (label == null) {
      return;
    }

    boolean isTopHeaderBtn = isInsideTopHeader(label);

    label.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    label.setForeground(isTopHeaderBtn ? Color.WHITE : controlTextColor());
  }

  public static void applyToContainer(Container container) {
    if (container == null) {
      return;
    }
    if (container instanceof JPopupMenu) {
      installPopupStyle((JPopupMenu) container);
    } else if (container instanceof JMenuBar) {
      ((JMenuBar) container).setOpaque(true);
      container.setBackground(topStripColor());
      container.setForeground(controlTextColor());
    } else if (container instanceof JToolBar) {
      applyToolbarContainerStyle((JComponent) container);
    } else if (container instanceof JPanel) {
      applyPanelStyle((JComponent) container);
    }
    for (Component child : container.getComponents()) {
      styleComponent(child);
      if (child instanceof Container) {
        applyToContainer((Container) child);
      }
    }
  }

  private static void styleComponent(Component component) {
    if (component instanceof JMenu) {
      styleMenuItem((JMenu) component);
    } else if (component instanceof JMenuItem) {
      styleMenuItem((JMenuItem) component);
    } else if (component instanceof javax.swing.JRadioButton) {
      installRadioButtonStyle((AbstractButton) component);
    } else if (component instanceof JCheckBox) {
      installCheckBoxStyle((JCheckBox) component);
    } else if (component instanceof AbstractButton) {
      installButtonStyle((AbstractButton) component);
    } else if (component instanceof JComboBox) {
      installComboBoxStyle((JComboBox<?>) component);
    } else if (component instanceof JTextComponent) {
      installTextFieldStyle((JTextComponent) component);
    } else if (component instanceof JLabel) {
      styleLabel((JLabel) component);
    } else if (component instanceof JPanel) {
      applyPanelStyle((JComponent) component);
    }
  }

  public static void paintToolbarSurface(Graphics2D graphics, int width, int height, boolean top) {
    if (graphics == null || width <= 0 || height <= 0) {
      return;
    }
    Graphics2D g = (Graphics2D) graphics.create();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    if (isAppleStyleEnabled()) {
      GradientPaint base = new GradientPaint(0, 0, topStripColor(), 0, height, bottomStripColor());
      g.setPaint(base);
      g.fillRoundRect(0, 0, width, height, 18, 18);

      g.setColor(new Color(255, 255, 255, 26));
      g.fillRoundRect(1, 1, Math.max(0, width - 2), Math.max(0, height / 2), 18, 18);

      g.setColor(controlBorderColor());
      g.setStroke(new BasicStroke(1f));
      g.drawRoundRect(0, 0, width - 1, height - 1, 18, 18);

      Color edge = top ? new Color(255, 255, 255, 46) : new Color(0, 0, 0, 60);
      g.setColor(edge);
      g.drawLine(0, top ? height - 1 : 0, width, top ? height - 1 : 0);
    } else {
      g.setColor(MorandiPalette.TOOLBAR_BG);
      g.fillRect(0, 0, width, height);
      g.setColor(MorandiPalette.TOOLBAR_BORDER);
      g.drawLine(0, height - 1, width, height - 1);
    }
    g.dispose();
  }

  private static boolean isIconOnly(AbstractButton button) {
    String text = button.getText();
    return button.getIcon() != null && (text == null || text.trim().isEmpty());
  }

  private static String buttonRole(AbstractButton button) {
    Object role = button.getClientProperty(BUTTON_ROLE);
    return role == null ? null : role.toString();
  }

  private static Color buttonTextColor(AbstractButton button) {
    String role = buttonRole(button);
    if (ROLE_PRIMARY.equals(role) || ROLE_DANGER.equals(role)) {
      return Color.WHITE;
    }
    return controlTextColor();
  }

  private static Color buttonFillColor(
      boolean hover, boolean pressed, boolean enabled, String role) {
    if (!enabled) {
      return isAppleStyleEnabled() ? new Color(255, 255, 255, 16) : new Color(225, 220, 213, 110);
    }
    if (ROLE_PRIMARY.equals(role)) {
      return pressed ? accentFillColor(230) : accentFillColor(hover ? 215 : 190);
    }
    if (ROLE_DANGER.equals(role)) {
      return pressed ? new Color(210, 62, 62, 230) : new Color(210, 62, 62, hover ? 210 : 185);
    }
    if (isAppleStyleEnabled()) {
      return new Color(255, 255, 255, pressed ? 64 : hover ? 44 : 28);
    }
    return pressed
        ? MorandiPalette.TOOLBAR_BUTTON_PRESSED
        : hover ? MorandiPalette.TOOLBAR_BUTTON_HOVER : MorandiPalette.TOOLBAR_BUTTON_BG;
  }

  private static Color buttonBorderColor(AbstractButton button) {
    String role = buttonRole(button);
    if (ROLE_PRIMARY.equals(role)) {
      return accentBorderColor();
    }
    if (ROLE_DANGER.equals(role)) {
      return new Color(255, 130, 130, isAppleStyleEnabled() ? 180 : 220);
    }
    return controlBorderColor();
  }

  private static Color topStripColor() {
    return isAppleStyleEnabled() ? new Color(18, 20, 24, 225) : MorandiPalette.TOOLBAR_BG;
  }

  private static Color bottomStripColor() {
    return isAppleStyleEnabled() ? new Color(33, 36, 42, 215) : MorandiPalette.TOOLBAR_BG;
  }

  private static Color dialogSurfaceColor() {
    return isAppleStyleEnabled() ? new Color(30, 33, 38) : MorandiPalette.CREAM_WHITE;
  }

  private static Color popupSurfaceColor() {
    return isAppleStyleEnabled() ? new Color(38, 41, 47) : MorandiPalette.CREAM_WHITE;
  }

  private static Color popupSelectionColor() {
    return isAppleStyleEnabled() ? accentFillColor(180) : MorandiPalette.MENU_ITEM_SELECTED;
  }

  private static Color fieldBackgroundColor() {
    return isAppleStyleEnabled() ? new Color(255, 255, 255, 22) : MorandiPalette.CREAM_WHITE;
  }

  public static Color validFieldBackground() {
    return isAppleStyleEnabled() ? new Color(255, 255, 255, 22) : Color.WHITE;
  }

  public static Color errorFieldBackground() {
    return isAppleStyleEnabled() ? new Color(180, 50, 50, 80) : Color.RED;
  }

  public static Image brightenIcon(Image src, int size) {
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2 = img.createGraphics();
    g2.drawImage(src, 0, 0, size, size, null);
    g2.dispose();
    for (int y = 0; y < img.getHeight(); y++) {
      for (int x = 0; x < img.getWidth(); x++) {
        int rgba = img.getRGB(x, y);
        int a = (rgba >> 24) & 0xFF;
        if (a > 10) {
          img.setRGB(x, y, (a << 24) | 0xE0E4EA);
        }
      }
    }
    return img;
  }

  private static Color controlTextColor() {
    return isAppleStyleEnabled() ? new Color(244, 247, 251) : MorandiPalette.MENU_ITEM_TEXT;
  }

  private static Color controlBorderColor() {
    if (isAppleStyleEnabled()) {
      return Lizzie.config != null && Lizzie.config.theme != null
          ? Lizzie.config.theme.glassPanelBorderColor()
          : new Color(255, 255, 255, 40);
    }
    return MorandiPalette.TOOLBAR_BUTTON_BORDER;
  }

  private static Color accentFillColor(int alpha) {
    Color accent =
        Lizzie.config != null && Lizzie.config.theme != null
            ? Lizzie.config.theme.glassAccentColor()
            : new Color(96, 165, 250);
    return new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), alpha);
  }

  private static Color accentBorderColor() {
    Color accent =
        Lizzie.config != null && Lizzie.config.theme != null
            ? Lizzie.config.theme.glassAccentColor()
            : new Color(96, 165, 250);
    return new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 220);
  }

  private static int controlCornerRadius() {
    return Lizzie.config != null && Lizzie.config.theme != null
        ? Math.max(8, Lizzie.config.theme.glassCornerRadius())
        : 12;
  }

  private static class ToolbarButtonUI extends BasicButtonUI {
    @Override
    public void paint(Graphics graphics, JComponent component) {
      AbstractButton button = (AbstractButton) component;
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      int width = component.getWidth();
      int height = component.getHeight();
      int arc = controlCornerRadius();
      g.setColor(
          buttonFillColor(
              button.getModel().isRollover(),
              button.getModel().isArmed() || button.getModel().isPressed(),
              button.isEnabled(),
              buttonRole(button)));
      g.fill(new RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc));
      g.setColor(buttonBorderColor(button));
      g.setStroke(new BasicStroke(1f));
      g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc));
      if (isAppleStyleEnabled()) {
        GradientPaint highlight =
            new GradientPaint(
                0,
                1,
                new Color(255, 255, 255, 46),
                0,
                Math.max(2, height / 2),
                new Color(255, 255, 255, 0));
        g.setPaint(highlight);
        g.fillRoundRect(1, 1, Math.max(0, width - 2), Math.max(1, height / 2), arc, arc);
      }
      g.dispose();
      super.paint(graphics, component);
    }
  }

  private static class AppleCheckIcon implements Icon {
    private final boolean selected;

    private AppleCheckIcon(boolean selected) {
      this.selected = selected;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
      AbstractButton button =
          component instanceof AbstractButton ? (AbstractButton) component : null;
      boolean enabled = button == null || button.isEnabled();
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      int size = getIconWidth() - 1;
      Color fill =
          selected
              ? accentFillColor(enabled ? 215 : 120)
              : (isAppleStyleEnabled()
                  ? new Color(255, 255, 255, enabled ? 20 : 12)
                  : MorandiPalette.CREAM_WHITE);
      Color border = selected ? accentBorderColor() : new Color(controlBorderColor().getRGB());
      g.setColor(fill);
      g.fillRoundRect(x, y, size, size, 7, 7);
      g.setColor(border);
      g.setStroke(new BasicStroke(1.2f));
      g.drawRoundRect(x, y, size, size, 7, 7);
      if (selected) {
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int x1 = x + 4;
        int y1 = y + 8;
        int x2 = x + 7;
        int y2 = y + 11;
        int x3 = x + 12;
        int y3 = y + 5;
        g.drawLine(x1, y1, x2, y2);
        g.drawLine(x2, y2, x3, y3);
      }
      g.dispose();
    }

    @Override
    public int getIconWidth() {
      return 16;
    }

    @Override
    public int getIconHeight() {
      return 16;
    }
  }

  private static class AppleRadioIcon implements Icon {
    private final boolean selected;

    private AppleRadioIcon(boolean selected) {
      this.selected = selected;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
      AbstractButton button =
          component instanceof AbstractButton ? (AbstractButton) component : null;
      boolean enabled = button == null || button.isEnabled();
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      int size = getIconWidth() - 1;
      Color fill =
          selected
              ? accentFillColor(enabled ? 215 : 120)
              : (isAppleStyleEnabled()
                  ? new Color(255, 255, 255, enabled ? 20 : 12)
                  : MorandiPalette.CREAM_WHITE);
      Color border = selected ? accentBorderColor() : new Color(controlBorderColor().getRGB());
      g.setColor(fill);
      g.fillOval(x, y, size, size);
      g.setColor(border);
      g.setStroke(new BasicStroke(1.2f));
      g.drawOval(x, y, size, size);
      if (selected) {
        g.setColor(Color.WHITE);
        int dotSize = 6;
        int dotX = x + (size - dotSize) / 2;
        int dotY = y + (size - dotSize) / 2;
        g.fillOval(dotX, dotY, dotSize, dotSize);
      }
      g.dispose();
    }

    @Override
    public int getIconWidth() {
      return 16;
    }

    @Override
    public int getIconHeight() {
      return 16;
    }
  }

  private static class RoundedLineBorder extends AbstractBorder {
    private final Color color;
    private final int radius;

    private RoundedLineBorder(Color color, int radius) {
      this.color = color;
      this.radius = radius;
    }

    @Override
    public Insets getBorderInsets(Component component) {
      return new Insets(1, 1, 1, 1);
    }

    @Override
    public Insets getBorderInsets(Component component, Insets insets) {
      insets.set(1, 1, 1, 1);
      return insets;
    }

    @Override
    public void paintBorder(
        Component component, Graphics graphics, int x, int y, int width, int height) {
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(color);
      g.setStroke(new BasicStroke(1f));
      g.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
      g.dispose();
    }
  }
}
