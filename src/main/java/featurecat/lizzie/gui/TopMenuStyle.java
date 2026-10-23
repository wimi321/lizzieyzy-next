package featurecat.lizzie.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.LayoutManager;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.SeparatorUI;
import javax.swing.plaf.basic.BasicPopupMenuUI;

/**
 * Styles a top-strip menu (文件, 显示, 棋局, …) each time it opens, and every submenu each time that
 * opens, so a theme switch carries over and rebuilt submenus such as 最近打开 are covered.
 *
 * <p>Items get {@link TopMenuItemUI}, separators a one-pixel rule like the board menus', and the
 * popups a plain surface instead of the look and feel's own (the Windows one paints its native
 * background and gutter). Nothing is added, removed, renamed or reordered.
 */
final class TopMenuStyle {
  /** A composite row's shortcut, taken out of its label. */
  static final String HINT_PROPERTY = "lizzie.top-menu.hint";

  static final String USER_TEXT_PROPERTY = "lizzie.top-menu.user-text";
  static final String TRAILING_SPACE_PROPERTY = "lizzie.top-menu.trailing-space";

  private static final String LISTENER_PROPERTY = "lizzie.top-menu.listener";
  private static final int BUTTON_GAP = 10;

  private TopMenuStyle() {}

  /** User names are content, even when they end in a key-like parenthesis. */
  static JFontMenuItem userContent(String name) {
    JFontMenuItem item = new JFontMenuItem(name);
    item.putClientProperty(USER_TEXT_PROPERTY, Boolean.TRUE);
    return item;
  }

  static void install(JPopupMenu popup) {
    if (popup == null) {
      return;
    }
    if (!(popup.getUI() instanceof Surface)) {
      popup.setUI(new Surface());
    }
    AppleStyleSupport.installPopupStyle(popup);
    for (Component component : popup.getComponents()) {
      if (component instanceof JMenu menu) {
        if (!(menu.getUI() instanceof TopMenuItemUI.Submenu)) {
          menu.setUI(new TopMenuItemUI.Submenu());
        }
        styleWhenOpened(menu.getPopupMenu());
      } else if (component instanceof JMenuItem item) {
        if (!(item.getUI() instanceof TopMenuItemUI)) {
          item.setUI(new TopMenuItemUI());
        }
        if (item.getComponentCount() > 0) {
          arrangeComposite(item);
        }
      } else if (component instanceof JSeparator separator
          && !(separator.getUI() instanceof Hairline)) {
        separator.setUI(new Hairline());
      }
    }
  }

  private static void styleWhenOpened(JPopupMenu popup) {
    if (popup.getClientProperty(LISTENER_PROPERTY) != null) {
      return;
    }
    PopupMenuListener listener =
        new PopupMenuListener() {
          @Override
          public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            install(popup);
          }

          @Override
          public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}

          @Override
          public void popupMenuCanceled(PopupMenuEvent e) {}
        };
    popup.addPopupMenuListener(listener);
    popup.putClientProperty(LISTENER_PROPERTY, listener);
  }

  /**
   * Lays out an item built from a label and a button (显示 > 自定义布局 1/2): the label where other labels
   * start, its shortcut as a hint, and the button at the right. Any other structure keeps its own
   * layout.
   */
  private static void arrangeComposite(JMenuItem item) {
    if (item.getLayout() instanceof CompositeRow) {
      return;
    }
    JLabel label = null;
    AbstractButton button = null;
    for (Component child : item.getComponents()) {
      if (child instanceof JLabel childLabel && label == null) {
        label = childLabel;
      } else if (child instanceof AbstractButton childButton && button == null) {
        button = childButton;
      } else {
        return;
      }
    }
    if (label == null) {
      return;
    }
    MenuShortcutHint.Split split = MenuShortcutHint.split(label.getText());
    if (split.hint() != null) {
      label.getAccessibleContext().setAccessibleName(label.getText());
      item.getAccessibleContext().setAccessibleName(label.getText());
      label.setText(split.label());
      item.putClientProperty(HINT_PROPERTY, split.hint());
    }
    item.setLayout(new CompositeRow(label, button));
    item.setPreferredSize(null);
  }

  /** A label and an optional button, aligned with the menu's other rows. */
  static final class CompositeRow implements LayoutManager {
    private final JLabel label;
    private final AbstractButton button;

    CompositeRow(JLabel label, AbstractButton button) {
      this.label = label;
      this.button = button;
    }

    /** The width the button takes at the right of the row, gap included. */
    int reservedRight() {
      return button == null ? 0 : button.getPreferredSize().width + BUTTON_GAP;
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
      JMenuItem item = (JMenuItem) parent;
      TopMenuItemUI.Row row = TopMenuItemUI.Row.of(item);
      int width = row.textX() + label.getPreferredSize().width;
      if (row.hint() != null) {
        width += TopMenuItemUI.HINT_GAP + TopMenuItemUI.textWidth(item, row.hint());
      }
      width += reservedRight() + TopMenuItemUI.PADDING;
      width += row.chevronColumn() ? TopMenuItemUI.CHEVRON : 0;
      return new Dimension(width, TopMenuItemUI.rowHeight(item));
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
      return preferredLayoutSize(parent);
    }

    @Override
    public void layoutContainer(Container parent) {
      JMenuItem item = (JMenuItem) parent;
      TopMenuItemUI.Row row = TopMenuItemUI.Row.of(item);
      int height = parent.getHeight();
      Dimension labelSize = label.getPreferredSize();
      label.setBounds(
          row.textX(), (height - labelSize.height) / 2, labelSize.width, labelSize.height);
      if (button != null) {
        Dimension buttonSize = button.getPreferredSize();
        int buttonHeight = Math.min(buttonSize.height, height - 6);
        int right = parent.getWidth() - TopMenuItemUI.PADDING;
        right -= row.chevronColumn() ? TopMenuItemUI.CHEVRON : 0;
        button.setBounds(
            right - buttonSize.width, (height - buttonHeight) / 2, buttonSize.width, buttonHeight);
      }
    }

    @Override
    public void addLayoutComponent(String name, Component comp) {}

    @Override
    public void removeLayoutComponent(Component comp) {}
  }

  /** A one-pixel rule with room above and below, as in the board menus. */
  static final class Hairline extends SeparatorUI {
    static final int HEIGHT = 7;
    private static final int INSET = 10;

    @Override
    public Dimension getPreferredSize(JComponent c) {
      return new Dimension(1, HEIGHT);
    }

    @Override
    public Dimension getMinimumSize(JComponent c) {
      return getPreferredSize(c);
    }

    @Override
    public Dimension getMaximumSize(JComponent c) {
      return new Dimension(Integer.MAX_VALUE, HEIGHT);
    }

    @Override
    public void paint(Graphics g, JComponent c) {
      g.setColor(AppleStyleSupport.popupSurfaceColor());
      g.fillRect(0, 0, c.getWidth(), c.getHeight());
      g.setColor(AppleStyleSupport.popupRuleColor());
      g.fillRect(INSET, HEIGHT / 2, c.getWidth() - 2 * INSET, 1);
    }
  }

  /** The popup's own surface; its border and colour come from AppleStyleSupport. */
  static final class Surface extends BasicPopupMenuUI {}
}
