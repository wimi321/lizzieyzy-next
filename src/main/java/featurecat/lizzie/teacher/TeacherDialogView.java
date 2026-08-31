package featurecat.lizzie.teacher;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.accessibility.AccessibleContext;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;

/** Presentation-only surface for the AI commentary dialog. */
final class TeacherDialogView extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final String EMPTY_CARD = "empty";
  private static final String OUTPUT_CARD = "output";

  enum Mode {
    NEXT,
    RANGE,
    WHOLE
  }

  enum StatusTone {
    NEUTRAL,
    RUNNING,
    SUCCESS,
    WARNING,
    ERROR
  }

  private final JEditorPane output = new JEditorPane();
  private final JLabel status = new ClippedLabel(" ");
  private final JLabel modelStatus = new ClippedLabel(" ", SwingConstants.RIGHT);
  private final JToggleButton explainNext =
      new JToggleButton(TeacherStrings.get("Teacher.mode.next", "Next move"));
  private final JToggleButton explainRange =
      new JToggleButton(TeacherStrings.get("Teacher.mode.range", "Range"));
  private final JToggleButton explainWhole =
      new JToggleButton(TeacherStrings.get("Teacher.mode.whole", "Whole game"));
  private final JButton stop = new JButton(TeacherStrings.get("Teacher.action.stop", "Stop"));
  private final JButton settingsButton =
      new JButton(TeacherStrings.get("Teacher.action.settings", "Settings"));
  private final JButton ask = new JButton(TeacherStrings.get("Teacher.action.ask", "Ask"));
  private final JCheckBox writeToSgf =
      new JCheckBox(
          TeacherStrings.get("Teacher.writeToSgf", "Write result to the SGF comment"), true);
  private final PlaceholderTextField followUp =
      new PlaceholderTextField(
          TeacherStrings.get(
              "Teacher.followUp.placeholder", "Ask about this position or commentary..."));
  private final JSpinner rangeStart = new JSpinner();
  private final JSpinner rangeEnd = new JSpinner();
  private final JProgressBar progressBar = new JProgressBar();
  private final JLabel currentMove = new ClippedLabel(" ");
  private final JLabel emptyTitle =
      new JLabel(TeacherStrings.get("Teacher.empty.title", "Commentary will appear here"));
  private final JTextPane emptyDetail = new JTextPane();
  private final StatusDot statusDot = new StatusDot();
  private final JPanel contentCards = new JPanel(new CardLayout());

  TeacherDialogView() {
    super(new BorderLayout(0, 14));
    setName("teacherDialogView");
    setBackground(TeacherDialogStyle.background());
    setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
    add(buildHeader(), BorderLayout.NORTH);
    add(buildWorkspace(), BorderLayout.CENTER);
    add(buildComposer(), BorderLayout.SOUTH);
    selectMode(Mode.NEXT);
    showEmpty();
  }

  private JPanel buildHeader() {
    JLabel title = new JLabel(TeacherStrings.get("Teacher.title", "AI commentary"));
    title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 6f));
    title.setForeground(TeacherDialogStyle.text());

    JTextArea subtitle =
        wrappingText(
            TeacherStrings.get(
                "Teacher.subtitle",
                "Uses existing KataGo analysis; missing evidence is never invented."));
    subtitle.setRows(2);
    subtitle.setFont(subtitle.getFont().deriveFont(subtitle.getFont().getSize2D() - 1f));
    subtitle.setForeground(TeacherDialogStyle.muted());

    JPanel headingText = transparent(new GridBagLayout());
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = 0;
    constraints.gridy = 0;
    constraints.weightx = 1.0;
    constraints.anchor = GridBagConstraints.WEST;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    headingText.add(title, constraints);
    constraints.gridy = 1;
    constraints.insets = new Insets(3, 0, 0, 0);
    headingText.add(subtitle, constraints);

    TeacherDialogStyle.styleSecondary(settingsButton);
    TeacherDialogStyle.installSettingsIcon(settingsButton);
    settingsButton.setToolTipText(
        TeacherStrings.get(
            "Teacher.action.settings.description",
            "Configure the commentary service, model, and teaching preferences."));
    settingsButton.getAccessibleContext().setAccessibleDescription(settingsButton.getToolTipText());

    JPanel settings = transparent(new GridBagLayout());
    settings.add(settingsButton);

    JPanel header = transparent(new BorderLayout(14, 0));
    header.add(headingText, BorderLayout.CENTER);
    header.add(settings, BorderLayout.EAST);
    return header;
  }

  private JPanel buildWorkspace() {
    JPanel workspace = transparent(new BorderLayout(14, 0));
    workspace.add(buildModeRail(), BorderLayout.WEST);
    workspace.add(buildReader(), BorderLayout.CENTER);
    return workspace;
  }

  private JPanel buildModeRail() {
    JPanel rail = new JPanel();
    rail.setName("teacherModeRail");
    rail.setLayout(new BoxLayout(rail, BoxLayout.Y_AXIS));
    rail.setOpaque(true);
    rail.setBackground(TeacherDialogStyle.railSurface());
    rail.setBorder(new TeacherDialogStyle.RoundedBorder(TeacherDialogStyle.border(), 8));
    Dimension railSize = new Dimension(94, 10);
    rail.setPreferredSize(railSize);
    rail.setMinimumSize(railSize);

    ButtonGroup modes = new ButtonGroup();
    configureModeButton(
        explainNext,
        TeacherDialogStyle.ModeGlyph.NEXT,
        TeacherStrings.get("Teacher.action.next", "Explain next move"),
        TeacherStrings.get(
            "Teacher.action.next.description",
            "Compare the recorded next move with KataGo's top candidates."));
    configureModeButton(
        explainRange,
        TeacherDialogStyle.ModeGlyph.RANGE,
        TeacherStrings.get("Teacher.action.range", "Explain range"),
        TeacherStrings.get(
            "Teacher.action.range.description",
            "Explain analyzed positions between the selected move numbers."));
    configureModeButton(
        explainWhole,
        TeacherDialogStyle.ModeGlyph.WHOLE,
        TeacherStrings.get("Teacher.action.whole", "Explain whole game"),
        TeacherStrings.get(
            "Teacher.action.whole.description",
            "Summarize key moments across the analyzed main line."));
    modes.add(explainNext);
    modes.add(explainRange);
    modes.add(explainWhole);
    rail.add(explainNext);
    rail.add(explainRange);
    rail.add(explainWhole);
    rail.add(Box.createVerticalGlue());
    return rail;
  }

  private void configureModeButton(
      JToggleButton button,
      TeacherDialogStyle.ModeGlyph glyph,
      String accessibleName,
      String description) {
    TeacherDialogStyle.styleModeButton(button, glyph);
    button.setAlignmentX(CENTER_ALIGNMENT);
    button.setToolTipText(accessibleName);
    button.getAccessibleContext().setAccessibleName(accessibleName);
    button.getAccessibleContext().setAccessibleDescription(description);
  }

  private JPanel buildReader() {
    JPanel reader = new JPanel(new BorderLayout(0, 0));
    reader.setName("teacherReader");
    reader.setOpaque(true);
    reader.setBackground(TeacherDialogStyle.surface());
    reader.setBorder(new TeacherDialogStyle.RoundedBorder(TeacherDialogStyle.border(), 8));
    reader.add(buildContextBar(), BorderLayout.NORTH);
    reader.add(buildContentCards(), BorderLayout.CENTER);
    reader.add(buildStatusArea(), BorderLayout.SOUTH);
    return reader;
  }

  private JPanel buildContextBar() {
    currentMove.setFont(currentMove.getFont().deriveFont(Font.BOLD));
    currentMove.setForeground(TeacherDialogStyle.text());
    currentMove.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 8));

    JLabel from = new JLabel(TeacherStrings.get("Teacher.range.from", "From"));
    JLabel to = new JLabel(TeacherStrings.get("Teacher.range.to", "to"));
    from.setLabelFor(rangeStart);
    to.setLabelFor(rangeEnd);
    rangeStart.getAccessibleContext().setAccessibleName(from.getText());
    rangeEnd.getAccessibleContext().setAccessibleName(to.getText());
    from.setForeground(TeacherDialogStyle.muted());
    to.setForeground(TeacherDialogStyle.muted());
    TeacherDialogStyle.styleSpinner(rangeStart);
    TeacherDialogStyle.styleSpinner(rangeEnd);

    JPanel range = transparent(new GridBagLayout());
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridy = 0;
    constraints.insets = new Insets(0, 0, 0, 6);
    range.add(from, constraints);
    constraints.gridx = 1;
    range.add(rangeStart, constraints);
    constraints.gridx = 2;
    range.add(to, constraints);
    constraints.gridx = 3;
    range.add(rangeEnd, constraints);

    TeacherDialogStyle.styleDanger(stop);
    stop.setToolTipText(
        TeacherStrings.get(
            "Teacher.action.stop.description", "Cancel the active network request."));
    stop.getAccessibleContext().setAccessibleDescription(stop.getToolTipText());

    JPanel right = transparent(new BorderLayout(10, 0));
    right.add(range, BorderLayout.CENTER);
    right.add(stop, BorderLayout.EAST);

    JPanel context = transparent(new BorderLayout(12, 0));
    context.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, TeacherDialogStyle.border()),
            BorderFactory.createEmptyBorder(10, 14, 10, 12)));
    context.add(currentMove, BorderLayout.CENTER);
    context.add(right, BorderLayout.EAST);
    return context;
  }

  private JPanel buildContentCards() {
    contentCards.setName("teacherContentCards");
    contentCards.setOpaque(true);
    contentCards.setBackground(TeacherDialogStyle.surface());
    contentCards.add(buildEmptyState(), EMPTY_CARD);
    contentCards.add(buildOutput(), OUTPUT_CARD);
    return contentCards;
  }

  private JPanel buildEmptyState() {
    JLabel emptyIcon = new JLabel(TeacherDialogStyle.commentaryIcon());
    emptyIcon.setHorizontalAlignment(SwingConstants.CENTER);

    emptyTitle.setFont(
        emptyTitle.getFont().deriveFont(Font.BOLD, emptyTitle.getFont().getSize2D() + 4f));
    emptyTitle.setForeground(TeacherDialogStyle.text());
    emptyTitle.setHorizontalAlignment(SwingConstants.CENTER);

    emptyDetail.setEditable(false);
    emptyDetail.setFocusable(false);
    emptyDetail.setOpaque(false);
    emptyDetail.setForeground(TeacherDialogStyle.muted());
    emptyDetail.setFont(emptyDetail.getFont().deriveFont(emptyDetail.getFont().getSize2D() - 1f));
    emptyDetail.setPreferredSize(new Dimension(100, 38));
    centerEmptyDetail();

    JPanel text = transparent(new GridBagLayout());
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = 0;
    constraints.gridy = 0;
    constraints.weightx = 1.0;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.anchor = GridBagConstraints.CENTER;
    text.add(emptyIcon, constraints);
    constraints.gridy = 1;
    constraints.insets = new Insets(12, 28, 0, 28);
    text.add(emptyTitle, constraints);
    constraints.gridy = 2;
    constraints.insets = new Insets(8, 28, 0, 28);
    text.add(emptyDetail, constraints);

    JPanel empty = transparent(new GridBagLayout());
    empty.setName("teacherEmptyState");
    GridBagConstraints center = new GridBagConstraints();
    center.gridx = 0;
    center.gridy = 0;
    center.weightx = 1.0;
    center.fill = GridBagConstraints.HORIZONTAL;
    center.anchor = GridBagConstraints.CENTER;
    empty.add(text, center);
    return empty;
  }

  private JScrollPane buildOutput() {
    output.setContentType("text/html");
    output.setEditable(false);
    output.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
    output.setForeground(TeacherDialogStyle.text());
    output.setBackground(TeacherDialogStyle.surface());
    output.setEditorKit(createEditorKit());
    output.setText("<html><body></body></html>");
    output.setBorder(BorderFactory.createEmptyBorder());
    output
        .getAccessibleContext()
        .setAccessibleName(TeacherStrings.get("Teacher.output", "AI commentary result"));

    JScrollPane scroll = new JScrollPane(output);
    scroll.setName("teacherOutputScroll");
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.getViewport().setBackground(TeacherDialogStyle.surface());
    scroll.getVerticalScrollBar().setUnitIncrement(18);
    return scroll;
  }

  private HTMLEditorKit createEditorKit() {
    HTMLEditorKit kit = new HTMLEditorKit();
    StyleSheet style = kit.getStyleSheet();
    String fontFamily = output.getFont().getFamily().replace("'", "\\'");
    String text = TeacherDialogStyle.cssColor(TeacherDialogStyle.text());
    String surface = TeacherDialogStyle.cssColor(TeacherDialogStyle.surface());
    String muted = TeacherDialogStyle.cssColor(TeacherDialogStyle.muted());
    String subtle = TeacherDialogStyle.cssColor(TeacherDialogStyle.railSurface());
    String border = TeacherDialogStyle.cssColor(TeacherDialogStyle.border());
    style.addRule(
        "body { font-family: '"
            + fontFamily
            + "', sans-serif; font-size: 15px; line-height: 1.55; margin: 18px 22px; color: "
            + text
            + "; background-color: "
            + surface
            + "; }");
    style.addRule("p { margin: 7px 0; }");
    style.addRule("h1 { font-size: 22px; margin: 15px 0 7px 0; }");
    style.addRule("h2 { font-size: 19px; margin: 14px 0 6px 0; }");
    style.addRule("h3 { font-size: 16px; margin: 13px 0 5px 0; }");
    style.addRule("b, strong { font-weight: bold; }");
    style.addRule(
        "code { background-color: " + subtle + "; padding: 1px 4px; font-family: monospace; }");
    style.addRule(
        "pre { background-color: "
            + subtle
            + "; padding: 9px; border: 1px solid "
            + border
            + "; font-family: monospace; }");
    style.addRule("ul { margin: 6px 0; padding-left: 22px; }");
    style.addRule("ol { margin: 6px 0; padding-left: 25px; }");
    style.addRule(
        "blockquote { color: "
            + muted
            + "; border-left: 3px solid "
            + border
            + "; margin: 9px 0; padding-left: 11px; }");
    return kit;
  }

  private JPanel buildStatusArea() {
    progressBar.setIndeterminate(true);
    progressBar.setVisible(false);
    progressBar.setPreferredSize(new Dimension(10, 3));
    progressBar.setBorderPainted(false);
    progressBar.setForeground(TeacherDialogStyle.accent());
    progressBar
        .getAccessibleContext()
        .setAccessibleName(
            TeacherStrings.get("Teacher.progress.accessible", "Commentary generation progress"));

    status.setForeground(TeacherDialogStyle.muted());
    modelStatus.setForeground(TeacherDialogStyle.muted());
    modelStatus.setPreferredSize(new Dimension(220, 20));
    status
        .getAccessibleContext()
        .setAccessibleName(TeacherStrings.get("Teacher.status.accessible", "Commentary status"));
    modelStatus
        .getAccessibleContext()
        .setAccessibleName(TeacherStrings.get("Teacher.model.accessible", "Selected AI model"));

    JPanel statusLeft = transparent(new BorderLayout(8, 0));
    statusLeft.add(statusDot, BorderLayout.WEST);
    statusLeft.add(status, BorderLayout.CENTER);

    JPanel row = transparent(new BorderLayout(14, 0));
    row.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, TeacherDialogStyle.border()),
            BorderFactory.createEmptyBorder(8, 13, 8, 13)));
    row.add(statusLeft, BorderLayout.CENTER);
    row.add(modelStatus, BorderLayout.EAST);

    JPanel area = transparent(new BorderLayout());
    area.add(progressBar, BorderLayout.NORTH);
    area.add(row, BorderLayout.CENTER);
    return area;
  }

  private JPanel buildComposer() {
    JLabel followUpLabel = new JLabel(TeacherStrings.get("Teacher.followUp", "Follow-up question"));
    followUpLabel.setLabelFor(followUp);
    followUpLabel.setFont(followUpLabel.getFont().deriveFont(Font.BOLD));
    followUpLabel.setForeground(TeacherDialogStyle.text());

    TeacherDialogStyle.styleInput(followUp);
    TeacherDialogStyle.stylePrimary(ask);
    ask.setToolTipText(
        TeacherStrings.get(
            "Teacher.action.ask.description",
            "Ask a follow-up using the current commentary and KataGo evidence."));
    ask.getAccessibleContext().setAccessibleDescription(ask.getToolTipText());
    writeToSgf.setOpaque(false);
    writeToSgf.setForeground(TeacherDialogStyle.muted());

    JPanel inputRow = transparent(new BorderLayout(8, 0));
    inputRow.add(followUp, BorderLayout.CENTER);
    inputRow.add(ask, BorderLayout.EAST);

    JPanel promptRow = transparent(new BorderLayout(12, 0));
    promptRow.add(followUpLabel, BorderLayout.WEST);
    promptRow.add(inputRow, BorderLayout.CENTER);

    JPanel composer = transparent(new BorderLayout(0, 6));
    composer.setName("teacherComposer");
    composer.add(promptRow, BorderLayout.CENTER);
    composer.add(writeToSgf, BorderLayout.SOUTH);
    return composer;
  }

  private static JTextArea wrappingText(String text) {
    JTextArea area = new JTextArea(text);
    area.setEditable(false);
    area.setFocusable(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setOpaque(false);
    area.setBorder(BorderFactory.createEmptyBorder());
    return area;
  }

  private static JPanel transparent(java.awt.LayoutManager layout) {
    JPanel panel = new JPanel(layout);
    panel.setOpaque(false);
    return panel;
  }

  void selectMode(Mode mode) {
    switch (mode) {
      case RANGE:
        explainRange.setSelected(true);
        break;
      case WHOLE:
        explainWhole.setSelected(true);
        break;
      case NEXT:
      default:
        explainNext.setSelected(true);
        break;
    }
  }

  void showEmpty() {
    ((CardLayout) contentCards.getLayout()).show(contentCards, EMPTY_CARD);
  }

  void showLoading(String detail) {
    emptyTitle.setText(TeacherStrings.get("Teacher.loading.title", "Preparing commentary"));
    setEmptyDetail(detail);
    showEmpty();
  }

  void showOutput() {
    emptyTitle.setText(TeacherStrings.get("Teacher.empty.title", "Commentary will appear here"));
    ((CardLayout) contentCards.getLayout()).show(contentCards, OUTPUT_CARD);
  }

  void resetEmptyTitle() {
    emptyTitle.setText(TeacherStrings.get("Teacher.empty.title", "Commentary will appear here"));
  }

  void setEmptyDetail(String detail) {
    String safe = detail == null || detail.isBlank() ? " " : detail;
    emptyDetail.setText(safe);
    centerEmptyDetail();
    emptyDetail.setToolTipText(safe);
    emptyDetail.setCaretPosition(0);
  }

  private void centerEmptyDetail() {
    SimpleAttributeSet attributes = new SimpleAttributeSet();
    StyleConstants.setAlignment(attributes, StyleConstants.ALIGN_CENTER);
    emptyDetail
        .getStyledDocument()
        .setParagraphAttributes(0, emptyDetail.getDocument().getLength(), attributes, false);
  }

  void setCurrentMove(int moveNumber) {
    currentMove.setText(
        moveNumber <= 0
            ? TeacherStrings.get("Teacher.position.none", "No active position")
            : TeacherStrings.format("Teacher.position.move", "Current move {0}", moveNumber));
    currentMove.setToolTipText(currentMove.getText());
  }

  void setStatus(String message, StatusTone tone) {
    String previous = status.getText();
    String next = message == null || message.isBlank() ? " " : message;
    status.setText(next);
    status.setToolTipText(next);
    setEmptyDetail(
        tone == StatusTone.NEUTRAL
            ? TeacherStrings.get("Teacher.empty.ready", "Current position evidence is ready.")
            : next);
    statusDot.setTone(tone == null ? StatusTone.NEUTRAL : tone);
    status
        .getAccessibleContext()
        .firePropertyChange(AccessibleContext.ACCESSIBLE_DESCRIPTION_PROPERTY, previous, next);
  }

  void setModelStatus(String message) {
    String safe = message == null || message.isBlank() ? " " : message;
    modelStatus.setText(safe);
    modelStatus.setToolTipText(safe);
  }

  void setRunning(boolean running) {
    progressBar.setVisible(running);
    revalidate();
    repaint();
  }

  JEditorPane output() {
    return output;
  }

  JLabel status() {
    return status;
  }

  JLabel modelStatus() {
    return modelStatus;
  }

  JToggleButton explainNext() {
    return explainNext;
  }

  JToggleButton explainRange() {
    return explainRange;
  }

  JToggleButton explainWhole() {
    return explainWhole;
  }

  JButton stop() {
    return stop;
  }

  JButton settingsButton() {
    return settingsButton;
  }

  JButton ask() {
    return ask;
  }

  JCheckBox writeToSgf() {
    return writeToSgf;
  }

  JTextField followUp() {
    return followUp;
  }

  JSpinner rangeStart() {
    return rangeStart;
  }

  JSpinner rangeEnd() {
    return rangeEnd;
  }

  JProgressBar progressBar() {
    return progressBar;
  }

  JPanel contentCards() {
    return contentCards;
  }

  JLabel emptyTitle() {
    return emptyTitle;
  }

  JTextPane emptyDetail() {
    return emptyDetail;
  }

  JLabel currentMove() {
    return currentMove;
  }

  private static final class PlaceholderTextField extends JTextField {
    private static final long serialVersionUID = 1L;
    private final String placeholder;

    private PlaceholderTextField(String placeholder) {
      this.placeholder = placeholder;
      getAccessibleContext()
          .setAccessibleName(TeacherStrings.get("Teacher.followUp", "Follow-up question"));
      getAccessibleContext().setAccessibleDescription(placeholder);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      if (!getText().isEmpty() || hasFocus()) {
        return;
      }
      Graphics2D g2 = (Graphics2D) graphics.create();
      g2.setColor(TeacherDialogStyle.muted());
      g2.setFont(getFont());
      Insets insets = getInsets();
      int baseline =
          insets.top
              + (getHeight() - insets.top - insets.bottom - getFontMetrics(getFont()).getHeight())
                  / 2
              + getFontMetrics(getFont()).getAscent();
      g2.drawString(placeholder, insets.left, baseline);
      g2.dispose();
    }
  }

  private static final class StatusDot extends JPanel {
    private static final long serialVersionUID = 1L;
    private StatusTone tone = StatusTone.NEUTRAL;

    private StatusDot() {
      setOpaque(false);
      Dimension size = new Dimension(10, 10);
      setPreferredSize(size);
      setMinimumSize(size);
      setMaximumSize(size);
      getAccessibleContext()
          .setAccessibleName(TeacherStrings.get("Teacher.status.accessible", "Commentary status"));
    }

    private void setTone(StatusTone tone) {
      this.tone = tone;
      repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      Graphics2D g2 = (Graphics2D) graphics.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(toneColor());
      int diameter = Math.min(getWidth(), getHeight()) - 2;
      g2.fillOval(1, 1, Math.max(0, diameter), Math.max(0, diameter));
      g2.dispose();
    }

    private Color toneColor() {
      switch (tone) {
        case RUNNING:
        case SUCCESS:
          return TeacherDialogStyle.accent();
        case WARNING:
          return TeacherDialogStyle.warning();
        case ERROR:
          return TeacherDialogStyle.danger();
        case NEUTRAL:
        default:
          return TeacherDialogStyle.muted();
      }
    }
  }

  private static final class ClippedLabel extends JLabel {
    private static final long serialVersionUID = 1L;

    private ClippedLabel(String text) {
      super(text);
    }

    private ClippedLabel(String text, int horizontalAlignment) {
      super(text, horizontalAlignment);
    }

    @Override
    public Dimension getMinimumSize() {
      Dimension preferred = super.getMinimumSize();
      return new Dimension(0, preferred.height);
    }
  }
}
