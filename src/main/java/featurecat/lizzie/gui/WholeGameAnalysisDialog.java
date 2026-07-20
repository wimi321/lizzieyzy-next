package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.WholeGameAnalysisSession;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicButtonUI;

/** Modeless progress surface for whole-game analysis; the board remains fully interactive. */
public final class WholeGameAnalysisDialog extends JDialog
    implements WholeGameAnalysisSession.Listener {
  private static final long serialVersionUID = 1L;
  private static final Color BACKGROUND = new Color(247, 244, 236);
  private static final Color CARD = new Color(255, 253, 248);
  private static final Color BORDER = new Color(218, 208, 188);
  private static final Color TEXT = new Color(39, 47, 43);
  private static final Color MUTED = new Color(103, 107, 99);
  private static final Color ACCENT = new Color(20, 111, 91);
  private static final Color ACCENT_SOFT = new Color(225, 240, 234);

  private final ResourceBundle resources = Lizzie.resourceBundle;
  private final LizzieFrame ownerFrame;
  private final JLabel phaseLabel = new JLabel();
  private final JLabel progressLabel = new JLabel();
  private final JLabel modeLabel = new JLabel();
  private final JLabel remainingLabel = new JLabel();
  private final JProgressBar progressBar = new JProgressBar(0, 100);
  private final JButton hideButton = new JButton();
  private final JButton stopButton = new JButton();
  private WholeGameAnalysisSession session;
  private WholeGameAnalysisSession.State latestState = WholeGameAnalysisSession.State.IDLE;

  public WholeGameAnalysisDialog(LizzieFrame owner) {
    super(owner, Lizzie.resourceBundle.getString("WholeGameAnalysis.title"), false);
    ownerFrame = owner;
    setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    setType(Window.Type.UTILITY);
    setAutoRequestFocus(false);
    setFocusableWindowState(false);
    setMinimumSize(new Dimension(500, 330));
    setPreferredSize(new Dimension(540, 360));
    setContentPane(buildContent());
    pack();
    setLocationRelativeTo(owner);
    installWindowBehavior();
  }

  public void setSession(WholeGameAnalysisSession session) {
    this.session = session;
  }

  public void showOnScreen() {
    if (!isVisible()) setVisible(true);
    SwingUtilities.invokeLater(
        () -> {
          ownerFrame.setMainPanelFocus();
          toFront();
        });
  }

  @Override
  public void onSnapshot(WholeGameAnalysisSession.Snapshot snapshot) {
    latestState = snapshot.state;
    phaseLabel.setText(resources.getString(snapshot.detailKey));
    progressBar.setValue(snapshot.overallPercent);
    progressBar.setString(snapshot.overallPercent + "%");
    progressLabel.setText(
        snapshot.state == WholeGameAnalysisSession.State.COMPLETE
            ? resources.getString("WholeGameAnalysis.saveHint")
            : MessageFormat.format(
                resources.getString("WholeGameAnalysis.progress"),
                snapshot.completedPositions,
                snapshot.totalPositions,
                snapshot.targetVisits));
    modeLabel.setText(
        snapshot.state == WholeGameAnalysisSession.State.PREPARING
            ? ""
            : resources.getString(
                snapshot.remoteBackend
                    ? "WholeGameAnalysis.mode.remote"
                    : "WholeGameAnalysis.mode.local"));
    remainingLabel.setText(remainingText(snapshot));

    boolean terminal = isTerminal(snapshot.state);
    hideButton.setEnabled(!terminal);
    stopButton.setText(
        resources.getString(terminal ? "WholeGameAnalysis.close" : "WholeGameAnalysis.cancel"));
    stopButton.setBackground(terminal ? ACCENT : CARD);
    stopButton.setForeground(terminal ? Color.WHITE : TEXT);
    updateButtonSize(stopButton);
    progressBar
        .getAccessibleContext()
        .setAccessibleDescription(phaseLabel.getText() + ". " + progressLabel.getText());
    stopButton.getAccessibleContext().setAccessibleName(stopButton.getText());
  }

  private JPanel buildContent() {
    JPanel root = new JPanel(new BorderLayout(0, 18));
    root.setBackground(BACKGROUND);
    root.setBorder(BorderFactory.createEmptyBorder(24, 26, 22, 26));
    root.add(buildHeader(), BorderLayout.NORTH);
    root.add(buildProgressCard(), BorderLayout.CENTER);
    root.add(buildActions(), BorderLayout.SOUTH);
    return root;
  }

  private JComponent buildHeader() {
    JPanel header = new JPanel(new BorderLayout(14, 4));
    header.setOpaque(false);
    JLabel icon = new JLabel(new GoIcon(46));
    icon.setPreferredSize(new Dimension(46, 46));
    header.add(icon, BorderLayout.WEST);

    JPanel copy = new JPanel();
    copy.setOpaque(false);
    copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
    JLabel title = new JLabel(resources.getString("WholeGameAnalysis.title"));
    title.setForeground(TEXT);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
    title.setAlignmentX(Component.LEFT_ALIGNMENT);
    JTextArea description = new JTextArea(resources.getString("WholeGameAnalysis.description"));
    description.setEditable(false);
    description.setFocusable(false);
    description.setOpaque(false);
    description.setLineWrap(true);
    description.setWrapStyleWord(true);
    description.setRows(2);
    description.setForeground(MUTED);
    description.setFont(title.getFont().deriveFont(Font.PLAIN, 13f));
    description.setAlignmentX(Component.LEFT_ALIGNMENT);
    description.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
    copy.add(title);
    copy.add(Box.createVerticalStrut(5));
    copy.add(description);
    header.add(copy, BorderLayout.CENTER);
    return header;
  }

  private JComponent buildProgressCard() {
    JPanel card = new JPanel();
    card.setBackground(CARD);
    card.setBorder(new RoundedBorder(BORDER, 16));
    card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
    card.setBorder(
        BorderFactory.createCompoundBorder(
            new RoundedBorder(BORDER, 16), BorderFactory.createEmptyBorder(20, 22, 18, 22)));

    phaseLabel.setForeground(TEXT);
    phaseLabel.setFont(phaseLabel.getFont().deriveFont(Font.BOLD, 18f));
    phaseLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
    phaseLabel.setText(resources.getString("WholeGameAnalysis.preparing"));
    card.add(phaseLabel);
    card.add(Box.createVerticalStrut(14));

    progressBar.setValue(0);
    progressBar.setStringPainted(true);
    progressBar.setForeground(ACCENT);
    progressBar.setBackground(ACCENT_SOFT);
    progressBar.setBorderPainted(false);
    progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
    progressBar.setPreferredSize(new Dimension(440, 22));
    progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
    progressBar
        .getAccessibleContext()
        .setAccessibleName(resources.getString("WholeGameAnalysis.title"));
    card.add(progressBar);
    card.add(Box.createVerticalStrut(12));

    progressLabel.setForeground(TEXT);
    progressLabel.setFont(progressLabel.getFont().deriveFont(13f));
    progressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
    progressLabel.setText(
        MessageFormat.format(resources.getString("WholeGameAnalysis.progress"), 0, 0, 0));
    card.add(progressLabel);
    card.add(Box.createVerticalStrut(12));

    JPanel meta = new JPanel(new BorderLayout(12, 0));
    meta.setOpaque(false);
    meta.setAlignmentX(Component.LEFT_ALIGNMENT);
    modeLabel.setForeground(ACCENT);
    modeLabel.setFont(modeLabel.getFont().deriveFont(Font.BOLD, 12f));
    remainingLabel.setForeground(MUTED);
    remainingLabel.setHorizontalAlignment(SwingConstants.RIGHT);
    remainingLabel.setFont(remainingLabel.getFont().deriveFont(12f));
    meta.add(modeLabel, BorderLayout.WEST);
    meta.add(remainingLabel, BorderLayout.EAST);
    card.add(meta);
    return card;
  }

  private JComponent buildActions() {
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    actions.setOpaque(false);
    hideButton.setText(resources.getString("WholeGameAnalysis.hide"));
    styleButton(hideButton, false);
    hideButton.addActionListener(event -> setVisible(false));
    stopButton.setText(resources.getString("WholeGameAnalysis.cancel"));
    styleButton(stopButton, false);
    stopButton.addActionListener(
        event -> {
          if (isTerminal(latestState)) {
            dispose();
          } else if (session != null) {
            session.cancel();
          }
        });
    actions.add(hideButton);
    actions.add(stopButton);
    return actions;
  }

  private void styleButton(JButton button, boolean primary) {
    installPortableButtonFill(button);
    button.setFocusPainted(false);
    button.setOpaque(true);
    button.setBorder(new RoundedBorder(primary ? ACCENT : BORDER, 12));
    button.setBackground(primary ? ACCENT : CARD);
    button.setForeground(primary ? Color.WHITE : TEXT);
    updateButtonSize(button);
    button.getAccessibleContext().setAccessibleName(button.getText());
  }

  static void installPortableButtonFill(JButton button) {
    // Windows native LAF can ignore JButton background colors. The completion button uses white
    // text on an accent fill, so install a cross-platform UI that actually paints that fill.
    button.setUI(new BasicButtonUI());
    button.setContentAreaFilled(true);
  }

  private static void updateButtonSize(JButton button) {
    Dimension natural = button.getUI().getPreferredSize(button);
    button.setPreferredSize(new Dimension(Math.max(110, natural.width + 18), 38));
  }

  private void installWindowBehavior() {
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent event) {
            if (isTerminal(latestState)) {
              dispose();
            } else {
              setVisible(false);
            }
          }
        });
    getRootPane()
        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "hide");
    getRootPane()
        .getActionMap()
        .put(
            "hide",
            new AbstractAction() {
              @Override
              public void actionPerformed(java.awt.event.ActionEvent event) {
                setVisible(false);
              }
            });
  }

  private String remainingText(WholeGameAnalysisSession.Snapshot snapshot) {
    if (snapshot.state == WholeGameAnalysisSession.State.COMPLETE
        || snapshot.state == WholeGameAnalysisSession.State.CANCELLED
        || snapshot.state == WholeGameAnalysisSession.State.FAILED) {
      return "";
    }
    if (snapshot.estimatedRemainingMillis < 0) {
      return resources.getString("WholeGameAnalysis.remaining.calculating");
    }
    return MessageFormat.format(
        resources.getString("WholeGameAnalysis.remaining"),
        formatDuration(snapshot.estimatedRemainingMillis));
  }

  static String formatDuration(long millis) {
    long totalSeconds = Math.max(0L, (millis + 999L) / 1000L);
    long hours = totalSeconds / 3600L;
    long minutes = (totalSeconds % 3600L) / 60L;
    long seconds = totalSeconds % 60L;
    if (hours > 0L) {
      return String.format("%d:%02d:%02d", hours, minutes, seconds);
    }
    return String.format("%02d:%02d", minutes, seconds);
  }

  private static boolean isTerminal(WholeGameAnalysisSession.State state) {
    return state == WholeGameAnalysisSession.State.COMPLETE
        || state == WholeGameAnalysisSession.State.CANCELLED
        || state == WholeGameAnalysisSession.State.FAILED;
  }

  private static final class RoundedBorder extends AbstractBorder {
    private static final long serialVersionUID = 1L;
    private final Color color;
    private final int radius;

    private RoundedBorder(Color color, int radius) {
      this.color = color;
      this.radius = radius;
    }

    @Override
    public void paintBorder(
        Component component, Graphics graphics, int x, int y, int width, int height) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(color);
      g2.setStroke(new BasicStroke(1f));
      g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
      g2.dispose();
    }
  }

  private static final class GoIcon implements Icon {
    private final int size;

    private GoIcon(int size) {
      this.size = size;
    }

    @Override
    public int getIconWidth() {
      return size;
    }

    @Override
    public int getIconHeight() {
      return size;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(new Color(226, 204, 158));
      g2.fillRoundRect(x, y, size, size, 14, 14);
      int stone = Math.round(size * 0.48f);
      g2.setColor(new Color(35, 39, 38));
      g2.fillOval(x + 5, y + 6, stone, stone);
      g2.setColor(new Color(247, 245, 238));
      g2.fillOval(x + size - stone - 5, y + size - stone - 5, stone, stone);
      g2.setColor(new Color(187, 178, 158));
      g2.drawOval(x + size - stone - 5, y + size - stone - 5, stone, stone);
      g2.dispose();
    }
  }
}
