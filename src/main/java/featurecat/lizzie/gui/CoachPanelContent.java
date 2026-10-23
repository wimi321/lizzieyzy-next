package featurecat.lizzie.gui;

import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import javax.swing.JPanel;
import javax.swing.Scrollable;

/** Keeps decisions and actions separate, scrolling only when the coach cannot fit vertically. */
final class CoachPanelContent extends JPanel implements Scrollable {
  private static final int GAP = 12;
  private final CoachSummaryView summary;
  private final CoachMoveCard moveCard;
  private final CoachHistoryView history;
  private final JPanel actions;

  CoachPanelContent(
      CoachSummaryView summary, CoachMoveCard moveCard, CoachHistoryView history, JPanel actions) {
    super(null);
    this.summary = summary;
    this.moveCard = moveCard;
    this.history = history;
    this.actions = actions;
    setOpaque(false);
    add(summary);
    add(moveCard);
    add(history);
    add(actions);
  }

  @Override
  public Dimension getPreferredSize() {
    int width = getWidth() > 0 ? getWidth() : CoachPanel.WIDTH;
    Insets insets = getInsets();
    int inner = Math.max(0, width - insets.left - insets.right);
    int cardHeight = moveCard.heightFor(inner);
    int height = insets.top + summary.heightFor(inner);
    if (cardHeight > 0) height += GAP + cardHeight;
    return new Dimension(
        width, height + 2 * GAP + actions.getPreferredSize().height + insets.bottom);
  }

  @Override
  public void doLayout() {
    Insets insets = getInsets();
    int x = insets.left;
    int width = Math.max(0, getWidth() - insets.left - insets.right);
    int y = insets.top;
    summary.setBounds(x, y, width, summary.heightFor(width));
    y += summary.getHeight();
    int cardHeight = moveCard.heightFor(width);
    moveCard.setBounds(x, y + GAP, width, cardHeight);
    if (cardHeight > 0) y += GAP + cardHeight;
    int actionsHeight = actions.getPreferredSize().height;
    int actionsTop = Math.max(y + 2 * GAP, getHeight() - insets.bottom - actionsHeight);
    actions.setBounds(x, actionsTop, width, actionsHeight);
    int historyTop = y + GAP;
    history.setBounds(x, historyTop, width, Math.max(0, actionsTop - GAP - historyTop));
  }

  @Override
  public Dimension getPreferredScrollableViewportSize() {
    return getPreferredSize();
  }

  @Override
  public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
    return 24;
  }

  @Override
  public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
    return Math.max(24, visible.height - 48);
  }

  @Override
  public boolean getScrollableTracksViewportWidth() {
    return true;
  }

  @Override
  public boolean getScrollableTracksViewportHeight() {
    return getParent() != null && getParent().getHeight() >= getPreferredSize().height;
  }
}
