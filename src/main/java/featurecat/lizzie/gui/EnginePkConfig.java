package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import java.awt.*;
import java.util.ResourceBundle;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

public class EnginePkConfig extends JDialog {
  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;
  JFontTextField txtresignSettingBlackMinMove;
  JFontTextField txtresignSettingBlack;
  JFontTextField txtresignSettingBlack2;
  JFontTextField txtresignSettingWhiteMinMove;
  JFontTextField txtresignSettingWhite;
  JFontTextField txtresignSettingWhite2;
  JFontTextField txtnameSetting;
  JFontTextField txtGameMAX;
  JRadioButton rdoGenmove;
  JRadioButton rdoAna;
  JRadioButton rdoCurrentMove;
  JRadioButton rdoLastMove;

  JFontCheckBox chkPreviousBestmovesOnlyFirstMove;
  JFontCheckBox chkAutosave;
  JFontCheckBox chkExchange;
  JFontCheckBox chkGameMAX;
  JFontCheckBox chkRandomMove;
  JFontCheckBox chkRandomMoveVists;
  JFontCheckBox chkSaveWinrate;
  JFontCheckBox chkSatartNum;

  JFontTextField txtRandomMove;
  JFontTextField txtRandomDiffWinrate;
  JFontTextField txtRandomMoveVists;
  private JFontCheckBox chkPkPonder;
  private JFontTextField txtStartNum;

  public EnginePkConfig(boolean formToolbar) {
    setModal(true);
    setTitle(resourceBundle.getString("EnginePkConfig.title"));
    setResizable(true);
    setAlwaysOnTop(Lizzie.frame.isAlwaysOnTop());
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);

    initControls(formToolbar);

    JPanel mainPanel = new ViewportWidthPanel(new GridBagLayout());
    mainPanel.setBorder(new EmptyBorder(14, 16, 12, 16));

    int row = 0;
    addMainSection(mainPanel, buildModeSection(formToolbar), row++);
    addMainSection(mainPanel, buildGameOptionsSection(), row++);
    addMainSection(mainPanel, buildRandomMoveSection(), row++);
    if (formToolbar) {
      addMainSection(mainPanel, buildResignSection(), row++);
    }
    addMainSection(mainPanel, buildCandidateSection(), row++);
    addMainSection(mainPanel, buildHintSection(), row++);
    addMainFiller(mainPanel, row);

    JScrollPane scrollPane = new JScrollPane(mainPanel);
    scrollPane.setBorder(null);
    scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    scrollPane.getVerticalScrollBar().setUnitIncrement(Math.max(18, controlHeight()));

    Container contentPane = getContentPane();
    contentPane.setLayout(new BorderLayout());
    contentPane.add(scrollPane, BorderLayout.CENTER);
    contentPane.add(buildButtonBar(), BorderLayout.SOUTH);

    initControlValues();
    pack();
    applyDialogSize(formToolbar);
    setLocationRelativeTo(Lizzie.frame);
    adjustLocationForScaledDisplay();
    keepDialogOnScreen(getUsableScreenBounds());
  }

  private void initControls(boolean formToolbar) {
    txtresignSettingBlack = new JFontTextField();
    txtresignSettingBlack.setDocument(new IntDocument());
    txtresignSettingBlack2 = new JFontTextField();
    txtresignSettingBlack2.setDocument(new DoubleDocument());
    txtresignSettingBlackMinMove = new JFontTextField();
    txtresignSettingBlackMinMove.setDocument(new IntDocument());
    txtresignSettingWhite = new JFontTextField();
    txtresignSettingWhite.setDocument(new IntDocument());
    txtresignSettingWhite2 = new JFontTextField();
    txtresignSettingWhite2.setDocument(new DoubleDocument());
    txtresignSettingWhiteMinMove = new JFontTextField();
    txtresignSettingWhiteMinMove.setDocument(new IntDocument());

    chkExchange = new JFontCheckBox(resourceBundle.getString("EnginePkConfig.chkExchange"));
    chkGameMAX = new JFontCheckBox(resourceBundle.getString("EnginePkConfig.lblGameMAX"));
    txtGameMAX = new JFontTextField();
    txtGameMAX.setDocument(new IntDocument());
    chkAutosave = new JFontCheckBox(resourceBundle.getString("EnginePkConfig.chkAutosave"));
    chkSaveWinrate = new JFontCheckBox(resourceBundle.getString("EnginePkConfig.chkSaveWinrate"));

    chkRandomMove = new JFontCheckBox(resourceBundle.getString("EnginePkConfig.chkRandomMove"));
    txtRandomMove = new JFontTextField();
    txtRandomMove.setDocument(new IntDocument());
    txtRandomDiffWinrate = new JFontTextField();
    txtRandomDiffWinrate.setDocument(new DoubleDocument());
    chkRandomMoveVists =
        new JFontCheckBox(resourceBundle.getString("EnginePkConfig.chkRandomMoveVists"));
    txtRandomMoveVists = new JFontTextField();
    txtRandomMoveVists.setDocument(new DoubleDocument());

    txtnameSetting = new JFontTextField();
    chkSatartNum = new JFontCheckBox(resourceBundle.getString("EnginePkConfig.chkSatartNum"));
    txtStartNum = new JFontTextField();
    txtStartNum.setDocument(new IntDocument());

    rdoGenmove = new JFontRadioButton(resourceBundle.getString("EnginePkConfig.rdoGenmove"));
    rdoAna = new JFontRadioButton(resourceBundle.getString("EnginePkConfig.rdoAna"));
    rdoAna.addActionListener(e -> setTextEnable(true));
    rdoGenmove.addActionListener(e -> setTextEnable(false));
    rdoAna.setFocusable(false);
    rdoGenmove.setFocusable(false);
    ButtonGroup modeGroup = new ButtonGroup();
    modeGroup.add(rdoGenmove);
    modeGroup.add(rdoAna);

    rdoCurrentMove =
        new JFontRadioButton(resourceBundle.getString("EnginePkConfig.rdoCurrentMove"));
    rdoLastMove = new JFontRadioButton(resourceBundle.getString("EnginePkConfig.rdoLastMove"));
    ButtonGroup bestMoveGroup = new ButtonGroup();
    bestMoveGroup.add(rdoCurrentMove);
    bestMoveGroup.add(rdoLastMove);
    rdoLastMove.addActionListener(
        e -> chkPreviousBestmovesOnlyFirstMove.setEnabled(rdoLastMove.isSelected()));
    rdoCurrentMove.addActionListener(
        e -> chkPreviousBestmovesOnlyFirstMove.setEnabled(rdoLastMove.isSelected()));
    chkPreviousBestmovesOnlyFirstMove =
        new JFontCheckBox(
            resourceBundle.getString("EnginePkConfig.chkPreviousBestmovesOnlyFirstMove"));

    chkPkPonder =
        new JFontCheckBox(resourceBundle.getString("NewEngineGameDialog.checkBoxAllowPonder"));
    chkPkPonder.addActionListener(
        e -> {
          Lizzie.config.enginePkPonder = chkPkPonder.isSelected();
          Lizzie.config.uiConfig.put("engine-pk-ponder", Lizzie.config.enginePkPonder);
        });
    chkPkPonder.setVisible(formToolbar);

    setPreferredControlWidth(txtresignSettingBlackMinMove, 64);
    setPreferredControlWidth(txtresignSettingBlack, 64);
    setPreferredControlWidth(txtresignSettingBlack2, 72);
    setPreferredControlWidth(txtresignSettingWhiteMinMove, 64);
    setPreferredControlWidth(txtresignSettingWhite, 64);
    setPreferredControlWidth(txtresignSettingWhite2, 72);
    setPreferredControlWidth(txtGameMAX, 72);
    setPreferredControlWidth(txtRandomMove, 64);
    setPreferredControlWidth(txtRandomDiffWinrate, 64);
    setPreferredControlWidth(txtRandomMoveVists, 72);
    setPreferredControlWidth(txtnameSetting, 240);
    setPreferredControlWidth(txtStartNum, 72);
  }

  private JPanel buildModeSection(boolean formToolbar) {
    JPanel panel = createSectionPanel();
    JFontButton aboutAnalyzeGame = new JFontButton("?");
    aboutAnalyzeGame.setFocusable(false);
    aboutAnalyzeGame.setMargin(new Insets(0, 0, 0, 0));
    aboutAnalyzeGame.setPreferredSize(new Dimension(controlHeight() + 2, controlHeight() + 2));
    aboutAnalyzeGame.addActionListener(e -> Lizzie.frame.showAnalyzeGenmoveInfo());

    addFullWidth(panel, inlinePanel(rdoGenmove, rdoAna, aboutAnalyzeGame), 0);
    if (formToolbar) {
      addFullWidth(panel, chkPkPonder, 1);
    }
    return panel;
  }

  private JPanel buildGameOptionsSection() {
    JPanel panel = createSectionPanel();
    addFullWidth(panel, inlinePanel(chkExchange, chkGameMAX, txtGameMAX), 0);
    addFullWidth(panel, inlinePanel(chkAutosave, chkSaveWinrate), 1);

    JFontLabel lblnameSetting =
        new JFontLabel(resourceBundle.getString("EnginePkConfig.lblnameSetting"));
    addFullWidth(panel, inlinePanel(lblnameSetting, txtnameSetting), 2);
    addFullWidth(panel, inlinePanel(chkSatartNum, txtStartNum), 3);
    return panel;
  }

  private JPanel buildRandomMoveSection() {
    JPanel panel = createSectionPanel();
    JFontLabel lblRandomWinrate =
        new JFontLabel(resourceBundle.getString("EnginePkConfig.lblRandomWinrate"));
    addFullWidth(
        panel,
        inlinePanel(
            chkRandomMove, txtRandomMove, lblRandomWinrate, txtRandomDiffWinrate, percent()),
        0);
    addFullWidth(panel, inlinePanel(chkRandomMoveVists, txtRandomMoveVists, percent()), 1);
    return panel;
  }

  private JPanel buildResignSection() {
    JPanel panel = createSectionPanel();
    addResignRow(
        panel,
        0,
        new JFontLabel(resourceBundle.getString("EnginePkConfig.lblresignSettingBlack")),
        txtresignSettingBlackMinMove,
        txtresignSettingBlack,
        txtresignSettingBlack2);
    addResignRow(
        panel,
        1,
        new JFontLabel(resourceBundle.getString("EnginePkConfig.lblresignSettingWhite")),
        txtresignSettingWhiteMinMove,
        txtresignSettingWhite,
        txtresignSettingWhite2);
    return panel;
  }

  private JPanel buildCandidateSection() {
    JPanel panel = createSectionPanel();
    addFullWidth(
        panel,
        inlinePanel(
            new JFontLabel(resourceBundle.getString("EnginePkConfig.lblChooseBestMoves")),
            rdoCurrentMove,
            rdoLastMove),
        0);
    addFullWidth(panel, chkPreviousBestmovesOnlyFirstMove, 1);
    return panel;
  }

  private JPanel buildHintSection() {
    JPanel panel = createSectionPanel();
    JTextArea textAreaHint = new JTextArea();
    textAreaHint.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    textAreaHint.setLineWrap(true);
    textAreaHint.setWrapStyleWord(true);
    textAreaHint.setText(resourceBundle.getString("EnginePkConfig.textAreaHint"));
    textAreaHint.setOpaque(false);
    textAreaHint.setEditable(false);
    textAreaHint.setFocusable(false);
    addFullWidth(panel, textAreaHint, 0);
    return panel;
  }

  private JPanel buildButtonBar() {
    JPanel panel = plainPanel(new GridBagLayout());
    panel.setBorder(new EmptyBorder(10, 16, 14, 16));
    JFontButton okButton = new JFontButton(resourceBundle.getString("EnginePkConfig.okButton"));
    JFontButton cancelButton =
        new JFontButton(resourceBundle.getString("EnginePkConfig.cancelButton"));
    okButton.setPreferredSize(new Dimension(104, controlHeight() + 4));
    cancelButton.setPreferredSize(new Dimension(104, controlHeight() + 4));
    okButton.setMargin(new Insets(0, 0, 0, 0));
    cancelButton.setMargin(new Insets(0, 0, 0, 0));
    okButton.addActionListener(e -> applyChange());
    cancelButton.addActionListener(e -> setVisible(false));

    GridBagConstraints filler = baseConstraints(0, 0);
    filler.weightx = 1.0;
    filler.fill = GridBagConstraints.HORIZONTAL;
    panel.add(Box.createHorizontalGlue(), filler);
    panel.add(cancelButton, buttonConstraints(1, 0, new Insets(0, 0, 0, 10)));
    panel.add(okButton, buttonConstraints(2, 0, new Insets(0, 0, 0, 0)));
    return panel;
  }

  private void initControlValues() {
    txtresignSettingBlack.setText(String.valueOf(Lizzie.config.firstEngineResignMoveCounts));
    txtresignSettingBlack2.setText(String.valueOf(Lizzie.config.firstEngineResignWinrate));
    txtresignSettingBlackMinMove.setText(String.valueOf(Lizzie.config.firstEngineMinMove));
    txtresignSettingWhite.setText(String.valueOf(Lizzie.config.secondEngineResignMoveCounts));
    txtresignSettingWhite2.setText(String.valueOf(Lizzie.config.secondEngineResignWinrate));
    txtresignSettingWhiteMinMove.setText(String.valueOf(Lizzie.config.secondEngineMinMove));

    chkSatartNum.setSelected(Lizzie.config.chkPkStartNum);
    txtStartNum.setText(String.valueOf(Lizzie.config.pkStartNum));
    if (EngineManager.engineGameInfo != null && EngineManager.engineGameInfo.batchGameName != null)
      txtnameSetting.setText(LizzieFrame.toolbar.batchPkNameToolbar);
    chkAutosave.setSelected(LizzieFrame.toolbar.AutosavePk);
    chkExchange.setSelected(LizzieFrame.toolbar.exChangeToolbar);
    chkGameMAX.setSelected(LizzieFrame.toolbar.checkGameMaxMove);
    txtGameMAX.setText(String.valueOf(LizzieFrame.toolbar.maxGameMoves));
    chkRandomMove.setSelected(LizzieFrame.toolbar.isRandomMove);
    if (LizzieFrame.toolbar.randomMove > 0)
      txtRandomMove.setText(String.valueOf(LizzieFrame.toolbar.randomMove));
    txtRandomDiffWinrate.setText(String.valueOf(LizzieFrame.toolbar.randomDiffWinrate));
    chkSaveWinrate.setSelected(LizzieFrame.toolbar.enginePkSaveWinrate);
    chkRandomMoveVists.setSelected(Lizzie.config.checkRandomVisits);
    txtRandomMoveVists.setText(String.valueOf(Lizzie.config.percentsRandomVisits));
    chkPkPonder.setSelected(Lizzie.config.enginePkPonder);

    if (Lizzie.config.showPreviousBestmovesInEngineGame) rdoLastMove.setSelected(true);
    else rdoCurrentMove.setSelected(true);
    chkPreviousBestmovesOnlyFirstMove.setSelected(Lizzie.config.showPreviousBestmovesOnlyFirstMove);
    chkPreviousBestmovesOnlyFirstMove.setEnabled(rdoLastMove.isSelected());

    if (LizzieFrame.toolbar.isGenmoveToolbar) {
      rdoGenmove.setSelected(true);
      setTextEnable(false);
    } else {
      rdoAna.setSelected(true);
      setTextEnable(true);
    }
  }

  private void addResignRow(
      JPanel panel,
      int row,
      JComponent title,
      JComponent minMoveField,
      JComponent consistentField,
      JComponent belowField) {
    panel.add(title, compactConstraints(0, row, 0.0));
    panel.add(
        new JFontLabel(resourceBundle.getString("EnginePkConfig.lblresignSettingConsistent")),
        compactConstraints(1, row, 0.0));
    panel.add(minMoveField, compactConstraints(2, row, 0.0));
    panel.add(
        new JFontLabel(resourceBundle.getString("EnginePkConfig.lblresignSetting2")),
        compactConstraints(3, row, 0.0));
    panel.add(consistentField, compactConstraints(4, row, 0.0));
    panel.add(
        new JFontLabel(resourceBundle.getString("EnginePkConfig.lblresignSetting3")),
        compactConstraints(5, row, 0.0));
    panel.add(belowField, compactConstraints(6, row, 0.0));
    panel.add(percent(), compactConstraints(7, row, 0.0));
    GridBagConstraints filler = compactConstraints(8, row, 1.0);
    filler.fill = GridBagConstraints.HORIZONTAL;
    panel.add(Box.createHorizontalGlue(), filler);
  }

  private JLabel percent() {
    return new JFontLabel("%");
  }

  private JPanel createSectionPanel() {
    JPanel panel = plainPanel(new GridBagLayout());
    panel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(220, 224, 230)),
            new EmptyBorder(12, 12, 12, 12)));
    return panel;
  }

  private JPanel plainPanel(LayoutManager layout) {
    JPanel panel = new JPanel(layout);
    panel.setOpaque(false);
    return panel;
  }

  private JPanel inlinePanel(Component... components) {
    JPanel panel = plainPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    for (Component component : components) {
      panel.add(component);
    }
    return panel;
  }

  private void addMainSection(JPanel mainPanel, JComponent section, int row) {
    GridBagConstraints constraints = baseConstraints(0, row);
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.weightx = 1.0;
    constraints.insets = new Insets(row == 0 ? 0 : 10, 0, 0, 0);
    mainPanel.add(section, constraints);
  }

  private void addMainFiller(JPanel mainPanel, int row) {
    GridBagConstraints constraints = baseConstraints(0, row);
    constraints.fill = GridBagConstraints.BOTH;
    constraints.weightx = 1.0;
    constraints.weighty = 1.0;
    mainPanel.add(Box.createGlue(), constraints);
  }

  private void addFullWidth(JPanel panel, JComponent component, int row) {
    GridBagConstraints constraints = baseConstraints(0, row);
    constraints.gridwidth = GridBagConstraints.REMAINDER;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.weightx = 1.0;
    constraints.insets = new Insets(row == 0 ? 0 : 8, 0, 0, 0);
    panel.add(component, constraints);
  }

  private GridBagConstraints compactConstraints(int x, int y, double weightx) {
    GridBagConstraints constraints = baseConstraints(x, y);
    constraints.weightx = weightx;
    constraints.fill = weightx > 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
    constraints.anchor = GridBagConstraints.WEST;
    constraints.insets = new Insets(y == 0 ? 0 : 8, x == 0 ? 0 : 10, 0, 0);
    return constraints;
  }

  private GridBagConstraints buttonConstraints(int x, int y, Insets insets) {
    GridBagConstraints constraints = baseConstraints(x, y);
    constraints.fill = GridBagConstraints.BOTH;
    constraints.insets = insets;
    return constraints;
  }

  private GridBagConstraints baseConstraints(int x, int y) {
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = x;
    constraints.gridy = y;
    constraints.anchor = GridBagConstraints.WEST;
    return constraints;
  }

  private void setPreferredControlWidth(JComponent component, int width) {
    Dimension preferred = component.getPreferredSize();
    component.setPreferredSize(new Dimension(width, Math.max(controlHeight(), preferred.height)));
    component.setMinimumSize(new Dimension(Math.min(width, 48), Math.max(22, preferred.height)));
  }

  private int controlHeight() {
    if (Lizzie.config.isFrameFontSmall()) return 24;
    if (Lizzie.config.isFrameFontMiddle()) return 26;
    return 28;
  }

  private void applyDialogSize(boolean formToolbar) {
    Rectangle usableBounds = getUsableScreenBounds();
    int desiredWidth =
        Lizzie.config.isFrameFontSmall() ? 680 : (Lizzie.config.isFrameFontMiddle() ? 730 : 780);
    int desiredHeight =
        formToolbar
            ? (Lizzie.config.isFrameFontSmall() ? 600 : 650)
            : (Lizzie.config.isFrameFontSmall() ? 540 : 590);
    Dimension targetSize =
        new Dimension(
            Math.min(desiredWidth, Math.max(640, usableBounds.width - 40)),
            Math.min(desiredHeight, Math.max(500, usableBounds.height - 40)));
    setMinimumSize(
        new Dimension(Math.min(640, targetSize.width), Math.min(500, targetSize.height)));
    setSize(targetSize);
  }

  private Rectangle getUsableScreenBounds() {
    GraphicsConfiguration configuration = getGraphicsConfiguration();
    if (configuration == null) {
      return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    }
    Rectangle bounds = configuration.getBounds();
    Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
    return new Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom);
  }

  private void keepDialogOnScreen(Rectangle usableBounds) {
    int maxX = usableBounds.x + usableBounds.width - getWidth();
    int maxY = usableBounds.y + usableBounds.height - getHeight();
    int x = Math.max(usableBounds.x, Math.min(getX(), maxX));
    int y = Math.max(usableBounds.y, Math.min(getY(), maxY));
    setLocation(x, y);
  }

  private void adjustLocationForScaledDisplay() {
    GraphicsConfiguration configuration = getGraphicsConfiguration();
    if (configuration == null) {
      return;
    }
    java.awt.geom.AffineTransform transform = configuration.getDefaultTransform();
    double scaleX = transform.getScaleX();
    double scaleY = transform.getScaleY();
    if (scaleX <= 1.0 && scaleY <= 1.0) {
      return;
    }
    int xOffset = (int) Math.round(getWidth() * (scaleX - 1.0) / 2.0);
    int yOffset = (int) Math.round(getHeight() * (scaleY - 1.0) / 2.0);
    setLocation(getX() - xOffset, getY() - yOffset);
  }

  private static class ViewportWidthPanel extends JPanel implements Scrollable {
    ViewportWidthPanel(LayoutManager layout) {
      super(layout);
      setOpaque(false);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
      return 24;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
      return Math.max(120, visibleRect.height - 48);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
      return false;
    }
  }

  private void setTextEnable(boolean status) {
    txtresignSettingBlack.setEnabled(status);
    txtresignSettingBlack2.setEnabled(status);
    txtresignSettingBlackMinMove.setEnabled(status);
    txtresignSettingWhite.setEnabled(status);
    txtresignSettingWhite2.setEnabled(status);
    txtresignSettingWhiteMinMove.setEnabled(status);
    chkRandomMove.setEnabled(status);
    txtRandomMove.setEnabled(status);
    txtRandomDiffWinrate.setEnabled(status);
    txtRandomMoveVists.setEnabled(status);
    chkRandomMoveVists.setEnabled(status);
  }

  private void applyChange() {
    try {
      Lizzie.config.pkStartNum = Integer.parseInt(txtStartNum.getText());
    } catch (NumberFormatException err) {
    }
    Lizzie.config.chkPkStartNum = chkSatartNum.isSelected();

    try {
      Lizzie.config.firstEngineResignMoveCounts = Integer.parseInt(txtresignSettingBlack.getText());
    } catch (NumberFormatException err) {
    }
    try {
      Lizzie.config.firstEngineResignWinrate = Double.parseDouble(txtresignSettingBlack2.getText());
    } catch (NumberFormatException err) {
    }
    try {
      Lizzie.config.firstEngineMinMove = Integer.parseInt(txtresignSettingBlackMinMove.getText());
    } catch (NumberFormatException err) {
    }

    try {
      Lizzie.config.secondEngineResignMoveCounts =
          Integer.parseInt(txtresignSettingWhite.getText());
    } catch (NumberFormatException err) {
    }
    try {
      Lizzie.config.secondEngineResignWinrate =
          Double.parseDouble(txtresignSettingWhite2.getText());
    } catch (NumberFormatException err) {
    }
    try {
      Lizzie.config.secondEngineMinMove = Integer.parseInt(txtresignSettingWhiteMinMove.getText());
    } catch (NumberFormatException err) {
    }
    Lizzie.config.showPreviousBestmovesInEngineGame = rdoLastMove.isSelected();
    Lizzie.config.showPreviousBestmovesOnlyFirstMove =
        chkPreviousBestmovesOnlyFirstMove.isSelected();

    Lizzie.config.uiConfig.put(
        "show-previous-bestmoves-only-first-move",
        Lizzie.config.showPreviousBestmovesOnlyFirstMove);
    Lizzie.config.uiConfig.put(
        "show-previous-bestmoves-in-enginegame", Lizzie.config.showPreviousBestmovesInEngineGame);
    Lizzie.config.uiConfig.put(
        "first-engine-resign-move-counts", Lizzie.config.firstEngineResignMoveCounts);
    Lizzie.config.uiConfig.put(
        "first-engine-resign-winrate", Lizzie.config.firstEngineResignWinrate);
    Lizzie.config.uiConfig.put("first-engine-min-move", Lizzie.config.firstEngineMinMove);

    Lizzie.config.uiConfig.put(
        "second-engine-resign-move-counts", Lizzie.config.secondEngineResignMoveCounts);
    Lizzie.config.uiConfig.put(
        "second-engine-resign-winrate", Lizzie.config.secondEngineResignWinrate);
    Lizzie.config.uiConfig.put("second-engine-min-move", Lizzie.config.secondEngineMinMove);

    LizzieFrame.toolbar.AutosavePk = chkAutosave.isSelected();
    LizzieFrame.toolbar.isGenmoveToolbar = rdoGenmove.isSelected();
    LizzieFrame.toolbar.batchPkNameToolbar = txtnameSetting.getText();
    LizzieFrame.toolbar.exChangeToolbar = chkExchange.isSelected();
    LizzieFrame.toolbar.isRandomMove = chkRandomMove.isSelected();
    LizzieFrame.toolbar.enginePkSaveWinrate = chkSaveWinrate.isSelected();
    try {
      LizzieFrame.toolbar.randomMove = Integer.parseInt(txtRandomMove.getText().trim());
    } catch (NumberFormatException err) {
    }
    try {
      LizzieFrame.toolbar.randomDiffWinrate =
          Double.parseDouble(txtRandomDiffWinrate.getText().trim());
    } catch (NumberFormatException err) {
    }
    Lizzie.config.checkRandomVisits = chkRandomMoveVists.isSelected();
    try {
      Lizzie.config.percentsRandomVisits = Double.parseDouble(txtRandomMoveVists.getText().trim());
    } catch (NumberFormatException err) {
    }
    Lizzie.config.uiConfig.put("check-random-visits", Lizzie.config.checkRandomVisits);
    Lizzie.config.uiConfig.put("percents-random-visits", Lizzie.config.percentsRandomVisits);
    LizzieFrame.toolbar.setGenmove();

    LizzieFrame.toolbar.checkGameMaxMove = chkGameMAX.isSelected();
    try {
      LizzieFrame.toolbar.maxGameMoves = Integer.parseInt(txtGameMAX.getText().trim());
    } catch (NumberFormatException err) {
    }
    setVisible(false);
  }
}
