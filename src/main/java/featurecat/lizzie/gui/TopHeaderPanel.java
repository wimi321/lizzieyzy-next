package featurecat.lizzie.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

public class TopHeaderPanel extends JPanel {

  public final JPanel leftArea;
  public final JPanel centerArea;
  public final JPanel rightArea;

  private final JPanel contentPanel;
  private final GroupPanel leftGroup;
  private final GroupPanel centerGroup;
  private final GroupPanel rightGroup;

  public TopHeaderPanel() {
    setLayout(new BorderLayout());
    setOpaque(false);
    setBorder(new EmptyBorder(4, 10, 4, 10));

    contentPanel = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
    contentPanel.setOpaque(false);

    leftGroup = new GroupPanel();
    centerGroup = new GroupPanel();
    rightGroup = new GroupPanel();
    leftArea = leftGroup;
    centerArea = centerGroup;
    rightArea = rightGroup;

    super.add(contentPanel, BorderLayout.CENTER);
  }

  public void addSeparator(Dimension dimension) {
    int width = dimension != null ? Math.max(6, dimension.width) : 10;
    int height = dimension != null ? Math.max(16, dimension.height) : 24;
    contentPanel.add(new ToolbarSeparator(width, height));
  }

  public void addSeparator() {
    addSeparator(new Dimension(10, 24));
  }

  @Override
  public void removeAll() {
    if (contentPanel == null) {
      super.removeAll();
      return;
    }
    contentPanel.removeAll();
    leftGroup.reset();
    centerGroup.reset();
    rightGroup.reset();
    revalidate();
    repaint();
  }

  @Override
  public Component add(Component comp) {
    if (comp == null) {
      return null;
    }
    if (contentPanel == null || comp == contentPanel) {
      return super.add(comp);
    }
    prepareHeaderComponent(comp);
    Component added = contentPanel.add(comp);
    contentPanel.revalidate();
    contentPanel.repaint();
    return added;
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    AppleStyleSupport.paintToolbarSurface(g2, getWidth(), getHeight(), true);
    g2.dispose();
  }

  private void ensureGroupAttached(GroupPanel group) {
    if (!group.isAttached()) {
      group.setAttached(true);
      contentPanel.add(group);
      contentPanel.revalidate();
      contentPanel.repaint();
    }
  }

  private void prepareHeaderComponent(Component comp) {
    // Styling is handled centrally by AppleStyleSupport which detects TopHeaderPanel ancestry.
    // Only set non-opaque for panels here to prevent background bleed-through.
    if (comp instanceof JPanel) {
      ((JPanel) comp).setOpaque(false);
    }
  }

  private final class GroupPanel extends JPanel {
    private boolean attached;

    private GroupPanel() {
      super(new FlowLayout(FlowLayout.LEFT, 4, 0));
      setOpaque(false);
    }

    @Override
    public Component add(Component comp) {
      ensureGroupAttached(this);
      prepareHeaderComponent(comp);
      return super.add(comp);
    }

    private boolean isAttached() {
      return attached;
    }

    private void setAttached(boolean attached) {
      this.attached = attached;
    }

    private void reset() {
      attached = false;
      super.removeAll();
    }
  }

  private static final class ToolbarSeparator extends JComponent {
    private final int separatorWidth;
    private final int separatorHeight;

    private ToolbarSeparator(int separatorWidth, int separatorHeight) {
      this.separatorWidth = separatorWidth;
      this.separatorHeight = separatorHeight;
      setOpaque(false);
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(separatorWidth, separatorHeight);
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      int x = getWidth() / 2;
      int top = Math.max(2, getHeight() / 5);
      int bottom = Math.min(getHeight() - 2, getHeight() - top);
      g2.setColor(new Color(255, 255, 255, 34));
      g2.drawLine(x, top, x, bottom);
      g2.dispose();
    }
  }

  private static final class WrapLayout extends FlowLayout {
    private WrapLayout(int align, int hgap, int vgap) {
      super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
      return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
      Dimension minimum = layoutSize(target, false);
      minimum.width -= getHgap() + 1;
      return minimum;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
      synchronized (target.getTreeLock()) {
        int targetWidth = target.getSize().width;
        if (targetWidth <= 0) {
          Container parent = target.getParent();
          while (parent != null && targetWidth <= 0) {
            targetWidth = parent.getSize().width;
            parent = parent.getParent();
          }
        }
        if (targetWidth <= 0) {
          targetWidth = Integer.MAX_VALUE;
        }

        Insets insets = target.getInsets();
        int horizontalInsetsAndGap = insets.left + insets.right + (getHgap() * 2);
        int maxWidth = Math.max(0, targetWidth - horizontalInsetsAndGap);

        Dimension dimension = new Dimension(0, 0);
        int rowWidth = 0;
        int rowHeight = 0;

        int members = target.getComponentCount();
        for (int i = 0; i < members; i++) {
          Component component = target.getComponent(i);
          if (!component.isVisible()) {
            continue;
          }

          Dimension componentSize =
              preferred ? component.getPreferredSize() : component.getMinimumSize();

          if (rowWidth > 0 && rowWidth + getHgap() + componentSize.width > maxWidth) {
            addRow(dimension, rowWidth, rowHeight);
            rowWidth = 0;
            rowHeight = 0;
          }

          if (rowWidth > 0) {
            rowWidth += getHgap();
          }
          rowWidth += componentSize.width;
          rowHeight = Math.max(rowHeight, componentSize.height);
        }

        addRow(dimension, rowWidth, rowHeight);

        dimension.width += horizontalInsetsAndGap;
        dimension.height += insets.top + insets.bottom + (getVgap() * 2);

        Container scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
        if (scrollPane != null && target.isValid()) {
          dimension.width -= getHgap() + 1;
        }

        return dimension;
      }
    }

    private void addRow(Dimension dimension, int rowWidth, int rowHeight) {
      dimension.width = Math.max(dimension.width, rowWidth);
      if (dimension.height > 0) {
        dimension.height += getVgap();
      }
      dimension.height += rowHeight;
    }
  }
}
