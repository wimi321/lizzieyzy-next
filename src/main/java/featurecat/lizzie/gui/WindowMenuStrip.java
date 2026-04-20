package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.border.EmptyBorder;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

public class WindowMenuStrip extends JPanel {
  private final Menu sourceMenuBar;
  private final List<MenuButton> menuButtons = new ArrayList<>();
  private static final String POPUP_LISTENER_KEY = "lizzie.window-menu-strip.popup-listener";

  public WindowMenuStrip(Menu sourceMenuBar) {
    this.sourceMenuBar = sourceMenuBar;
    setLayout(new FlowLayout(FlowLayout.LEFT, 8, 4));
    setOpaque(false);
    setBorder(new EmptyBorder(2, 10, 2, 10));
    rebuild();
  }

  public void rebuild() {
    removeAll();
    menuButtons.clear();
    if (sourceMenuBar == null) {
      return;
    }
    int menuCount = sourceMenuBar.getMenuCount();
    for (int i = 0; i < menuCount; i++) {
      JMenu menu = sourceMenuBar.getMenu(i);
      if (menu == null || !menu.isVisible()) {
        continue;
      }
      String text = menu.getText();
      if (text == null || text.trim().isEmpty()) {
        continue;
      }
      MenuButton button = new MenuButton(menu);
      menuButtons.add(button);
      add(button);
    }
    revalidate();
    repaint();
  }

  public void refreshColors() {
    Color fg = AppleStyleSupport.dialogTextColor();
    for (MenuButton b : menuButtons) {
      b.setForeground(fg);
    }
  }

  @Override
  public Dimension getPreferredSize() {
    Dimension size = super.getPreferredSize();
    return new Dimension(size.width, Math.max(Config.menuHeight + 6, size.height));
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    AppleStyleSupport.paintToolbarSurface(g2, getWidth(), getHeight(), true);
    g2.dispose();
  }

  private void hideOtherPopups(JMenu except) {
    if (sourceMenuBar == null) {
      return;
    }
    for (int i = 0; i < sourceMenuBar.getMenuCount(); i++) {
      JMenu menu = sourceMenuBar.getMenu(i);
      if (menu != null && menu != except) {
        menu.setPopupMenuVisible(false);
        notifyMenuDeselected(menu);
      }
    }
  }

  private boolean hasVisiblePopup() {
    if (sourceMenuBar == null) {
      return false;
    }
    for (int i = 0; i < sourceMenuBar.getMenuCount(); i++) {
      JMenu menu = sourceMenuBar.getMenu(i);
      if (menu != null && menu.getPopupMenu() != null && menu.getPopupMenu().isVisible()) {
        return true;
      }
    }
    return false;
  }

  private void openMenu(MenuButton button, boolean toggleIfVisible) {
    if (button == null || button.menu == null) {
      return;
    }
    JPopupMenu popup = button.menu.getPopupMenu();
    if (popup == null) {
      return;
    }
    boolean alreadyVisible = popup.isVisible();
    hideOtherPopups(button.menu);
    if (alreadyVisible && toggleIfVisible) {
      popup.setVisible(false);
      notifyMenuDeselected(button.menu);
      repaint();
      return;
    }

    notifyMenuSelected(button.menu);
    AppleStyleSupport.installPopupStyle(popup);
    popup.show(button, 0, button.getHeight() + 3);
    repaint();
  }

  private void ensurePopupListener(JMenu menu) {
    if (menu == null || menu.getPopupMenu() == null) {
      return;
    }
    JPopupMenu popup = menu.getPopupMenu();
    if (Boolean.TRUE.equals(popup.getClientProperty(POPUP_LISTENER_KEY))) {
      return;
    }
    popup.addPopupMenuListener(
        new PopupMenuListener() {
          @Override
          public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            repaint();
          }

          @Override
          public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            notifyMenuDeselected(menu);
            repaint();
          }

          @Override
          public void popupMenuCanceled(PopupMenuEvent e) {
            notifyMenuDeselected(menu);
            repaint();
          }
        });
    popup.putClientProperty(POPUP_LISTENER_KEY, Boolean.TRUE);
  }

  private void notifyMenuSelected(JMenu menu) {
    MenuEvent event = new MenuEvent(menu);
    for (MenuListener listener : menu.getMenuListeners()) {
      listener.menuSelected(event);
    }
  }

  private void notifyMenuDeselected(JMenu menu) {
    MenuEvent event = new MenuEvent(menu);
    for (MenuListener listener : menu.getMenuListeners()) {
      listener.menuDeselected(event);
    }
  }

  private final class MenuButton extends JButton {
    private final JMenu menu;

    private MenuButton(JMenu menu) {
      super(menu.getText());
      this.menu = menu;
      ensurePopupListener(menu);
      setFont(menu.getFont().deriveFont(Font.BOLD, Math.max(Config.frameFontSize, 12)));
      setForeground(AppleStyleSupport.dialogTextColor());
      setOpaque(false);
      setContentAreaFilled(false);
      setBorderPainted(false);
      setFocusPainted(false);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      setRolloverEnabled(true);
      setMargin(new Insets(0, 0, 0, 0));
      setBorder(new EmptyBorder(5, 10, 5, 10));
      addActionListener(e -> openMenu(this, true));
      addMouseListener(
          new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
              if (hasVisiblePopup() && !menu.getPopupMenu().isVisible()) {
                openMenu(MenuButton.this, false);
              } else {
                repaint();
              }
            }

            @Override
            public void mouseExited(MouseEvent e) {
              repaint();
            }
          });
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      boolean active = menu.getPopupMenu().isVisible();
      boolean hover = getModel().isRollover();
      if (hover || active) {
        g2.setColor(new Color(255, 255, 255, active ? 40 : 24));
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
      }
      if (active) {
        g2.setColor(new Color(120, 190, 255, 220));
        g2.setStroke(new BasicStroke(2f));
        g2.drawLine(8, getHeight() - 2, getWidth() - 8, getHeight() - 2);
      }
      g2.dispose();
      super.paintComponent(g);
    }
  }
}
