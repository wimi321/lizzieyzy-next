package featurecat.lizzie.teacher;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/** Modal settings editor; network discovery runs outside the EDT. */
final class TeacherSettingsDialog extends JDialog {
  private final TeacherSettings settings;
  private final JToggleButton chatGptTab =
      new JToggleButton(ChatGptSettingsPanel.text("tab", "ChatGPT login"));
  private final JToggleButton apiTab = new JToggleButton("API Key");
  private final JPanel providers =
      new JPanel(new CardLayout()) {
        @Override
        public Dimension getPreferredSize() {
          for (Component child : getComponents())
            if (child.isVisible()) return child.getPreferredSize();
          return new Dimension(0, 0);
        }
      };
  private final JPanel pages = new JPanel(new CardLayout());
  private final JToggleButton connectionPage =
      new JToggleButton(design("connection", "Connection"));
  private final JToggleButton preferencesPage =
      new JToggleButton(design("preferences", "Commentary preferences"));
  private boolean preferencesVisible;
  private final ChatGptSettingsPanel chatGptPanel;
  private final JTextField baseUrlField = new JTextField(34);
  private final JPasswordField apiKeyField = new JPasswordField(28);
  private final char passwordEchoChar = apiKeyField.getEchoChar();
  private final JComboBox<String> modelBox = new JComboBox<>();
  private final JCheckBox showApiKey =
      new JCheckBox(TeacherStrings.get("Teacher.settings.showKey", "Show API key"));
  private final JCheckBox rememberApiKey =
      new JCheckBox(TeacherStrings.get("Teacher.settings.rememberKey", "Remember securely"));
  private final JComboBox<String> rankModeBox =
      new JComboBox<>(
          new String[] {
            TeacherStrings.get("Teacher.settings.rank.kyu", "Kyu"),
            TeacherStrings.get("Teacher.settings.rank.dan", "Dan")
          });
  private final JSpinner rankNumSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 18, 1));
  private final JComboBox<RankChoice> rankChoice = new JComboBox<>();
  private final JComboBox<String> styleBox =
      new JComboBox<>(
          new String[] {
            TeacherStrings.get("Teacher.settings.style.balanced", "Balanced"),
            TeacherStrings.get("Teacher.settings.style.rigorous", "Rigorous"),
            TeacherStrings.get("Teacher.settings.style.patient", "Patient"),
            TeacherStrings.get("Teacher.settings.style.strict", "Strict"),
            TeacherStrings.get("Teacher.settings.style.humorous", "Humorous")
          });
  private final JComboBox<String> densityBox =
      new JComboBox<>(
          new String[] {
            TeacherStrings.get("Teacher.settings.density.low", "Low"),
            TeacherStrings.get("Teacher.settings.density.medium", "Medium"),
            TeacherStrings.get("Teacher.settings.density.high", "High")
          });
  private final JComboBox<String> paceBox =
      new JComboBox<>(
          new String[] {
            TeacherStrings.get("Teacher.settings.pace.brief", "Brief"),
            TeacherStrings.get("Teacher.settings.pace.standard", "Standard"),
            TeacherStrings.get("Teacher.settings.pace.detailed", "Detailed")
          });
  private final JComboBox<String> variationBox =
      new JComboBox<>(
          new String[] {
            TeacherStrings.get("Teacher.settings.variation.few", "Few"),
            TeacherStrings.get("Teacher.settings.variation.moderate", "Moderate"),
            TeacherStrings.get("Teacher.settings.variation.many", "Many")
          });
  private final JTextArea status = TeacherSettingsStyle.note(" ", 2);
  private final JButton refreshModels =
      new JButton(TeacherStrings.get("Teacher.settings.refreshModels", "Refresh models"));
  private final JButton saveButton =
      new JButton(TeacherStrings.get("Teacher.settings.save", "Save"));
  private final JButton cancelButton =
      new JButton(TeacherStrings.get("Teacher.settings.cancel", "Cancel"));
  private final JButton reloadKnowledge =
      new JButton(TeacherStrings.get("Teacher.settings.reloadKnowledge", "Reload knowledge"));
  private boolean saved;
  private long providerGeneration;
  private SwingWorker<?, ?> apiModelWorker;

  static boolean show(Component parent, TeacherSettings settings) {
    Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
    TeacherSettingsDialog dialog = new TeacherSettingsDialog(owner, settings);
    dialog.setLocationRelativeTo(parent);
    dialog.setVisible(true);
    return dialog.saved;
  }

  TeacherSettingsDialog(Window owner, TeacherSettings settings) {
    super(
        owner,
        TeacherStrings.get("Teacher.settings.title", "AI commentary settings"),
        Dialog.ModalityType.APPLICATION_MODAL);
    this.settings = settings;
    chatGptPanel = new ChatGptSettingsPanel(settings.chatGpt());
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    setContentPane(buildContent());
    loadValues();
    setPreferredSize(new Dimension(880, 650));
    pack();
    setMinimumSize(new Dimension(740, 530));
    java.awt.Rectangle usable =
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    setSize(
        Math.min(getWidth(), usable.width),
        Math.min(getHeight(), Math.max(460, usable.height - 60)));
    getRootPane()
        .registerKeyboardAction(
            event -> {
              if (cancelButton.isEnabled()) {
                dispose();
              }
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
  }

  private JPanel buildContent() {
    JPanel content = new JPanel(new BorderLayout());
    content.setBackground(TeacherSettingsStyle.surface());
    JPanel header = TeacherSettingsStyle.panel(new BorderLayout(0, 6));
    header.setOpaque(true);
    header.setBackground(TeacherSettingsStyle.fieldSurface());
    header.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, TeacherSettingsStyle.border()),
            BorderFactory.createEmptyBorder(13, 24, 9, 24)));
    header.add(TeacherSettingsStyle.label(getTitle(), 32, true), BorderLayout.NORTH);
    header.add(
        TeacherSettingsStyle.note(design("subtitle", "Make every review easier to understand"), 1),
        BorderLayout.CENTER);
    content.add(header, BorderLayout.NORTH);

    ButtonGroup navigation = new ButtonGroup();
    navigation.add(connectionPage);
    navigation.add(preferencesPage);
    connectionPage.setName("connectionPage");
    preferencesPage.setName("preferencesPage");
    TeacherSettingsStyle.selection(connectionPage, true);
    TeacherSettingsStyle.selection(preferencesPage, true);
    connectionPage.setIcon(TeacherSettingsStyle.icon("link", 22));
    preferencesPage.setIcon(TeacherSettingsStyle.icon("sliders-horizontal", 22));
    JPanel rail = new JPanel(new GridBagLayout());
    rail.setBackground(TeacherSettingsStyle.railSurface());
    rail.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 1, TeacherSettingsStyle.border()),
            BorderFactory.createEmptyBorder(26, 10, 12, 10)));
    GridBagConstraints nav = new GridBagConstraints();
    nav.gridx = 0;
    nav.gridy = 0;
    nav.weightx = 1;
    nav.fill = GridBagConstraints.HORIZONTAL;
    nav.insets = new Insets(0, 0, 12, 0);
    rail.add(connectionPage, nav);
    nav.gridy++;
    rail.add(preferencesPage, nav);
    nav.gridy++;
    nav.weighty = 1;
    rail.add(TeacherSettingsStyle.panel(new BorderLayout()), nav);
    rail.setPreferredSize(new Dimension(Math.max(210, rail.getPreferredSize().width), 100));
    content.add(rail, BorderLayout.WEST);

    pages.setOpaque(false);
    pages.add(scrollPage(buildConnectionPage()), "connection");
    pages.add(scrollPage(buildPreferencesPage()), "preferences");
    content.add(pages, BorderLayout.CENTER);
    connectionPage.addActionListener(event -> showPage(false));
    preferencesPage.addActionListener(event -> showPage(true));
    connectionPage.setSelected(true);
    showPage(false);

    TeacherSettingsStyle.button(saveButton, true);
    TeacherSettingsStyle.button(cancelButton, false);
    saveButton.setText(design("save", "Save settings"));
    saveButton.setName("saveSettings");
    cancelButton.setPreferredSize(
        new Dimension(Math.max(96, cancelButton.getPreferredSize().width), 42));
    saveButton.setPreferredSize(
        new Dimension(
            Math.max(
                126,
                Math.max(
                        saveButton
                            .getFontMetrics(saveButton.getFont())
                            .stringWidth(design("save", "Save settings")),
                        saveButton
                            .getFontMetrics(saveButton.getFont())
                            .stringWidth(design("savePreferences", "Save preferences")))
                    + saveButton.getInsets().left
                    + saveButton.getInsets().right),
            42));
    status.setFont(TeacherSettingsStyle.font(13, false));
    status.setForeground(TeacherSettingsStyle.muted());
    status.setName("settingsStatus");
    status
        .getAccessibleContext()
        .setAccessibleName(TeacherStrings.get("Teacher.settings.title", "AI commentary settings"));
    JPanel footer = TeacherSettingsStyle.panel(new BorderLayout(12, 0));
    footer.setOpaque(true);
    footer.setBackground(TeacherSettingsStyle.fieldSurface());
    footer.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, TeacherSettingsStyle.border()),
            BorderFactory.createEmptyBorder(10, 20, 10, 10)));
    JPanel buttons = TeacherSettingsStyle.panel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    buttons.add(cancelButton);
    buttons.add(saveButton);
    footer.add(status, BorderLayout.CENTER);
    footer.add(buttons, BorderLayout.EAST);
    content.add(footer, BorderLayout.SOUTH);

    showApiKey.setOpaque(false);
    rememberApiKey.setOpaque(false);
    showApiKey.setFont(TeacherSettingsStyle.font(14, false));
    rememberApiKey.setFont(TeacherSettingsStyle.font(14, false));
    showApiKey.setForeground(TeacherSettingsStyle.text());
    rememberApiKey.setForeground(TeacherSettingsStyle.text());
    showApiKey.addActionListener(
        event -> apiKeyField.setEchoChar(showApiKey.isSelected() ? (char) 0 : passwordEchoChar));
    rankModeBox.addActionListener(event -> updateRankBounds());
    refreshModels.addActionListener(event -> refreshModels());
    reloadKnowledge.addActionListener(
        event -> {
          featurecat.lizzie.teacher.knowledge.JosekiRecognizer.invalidateCache();
          status.setText(
              TeacherStrings.get("Teacher.settings.knowledgeReloaded", "Knowledge reloaded."));
        });
    cancelButton.addActionListener(event -> dispose());
    saveButton.addActionListener(event -> save());
    getRootPane().setDefaultButton(saveButton);
    return content;
  }

  private JPanel buildConnectionPage() {
    JPanel page = TeacherSettingsStyle.panel(new BorderLayout(0, 8));
    page.add(
        TeacherSettingsStyle.heading(
            design("connection", "Connection"),
            design("connectionHint", "Choose how to connect AI commentary")),
        BorderLayout.NORTH);
    ButtonGroup group = new ButtonGroup();
    group.add(chatGptTab);
    group.add(apiTab);
    JPanel tabs = TeacherSettingsStyle.panel(new GridLayout(1, 2, 0, 0));
    tabs.setBorder(new TeacherDialogStyle.RoundedBorder(TeacherSettingsStyle.border(), 8));
    for (JToggleButton button : List.of(chatGptTab, apiTab)) {
      TeacherSettingsStyle.selection(button, false);
      tabs.add(button);
    }
    chatGptTab.setName("chatGptProvider");
    apiTab.setName("apiKeyProvider");
    providers.setOpaque(false);
    JPanel choose = TeacherSettingsStyle.panel(new BorderLayout());
    choose.setBorder(BorderFactory.createEmptyBorder(26, 0, 0, 0));
    choose.add(
        TeacherSettingsStyle.note(
            ChatGptSettingsPanel.text("choose", "Choose a connection method and finish setup."), 2),
        BorderLayout.NORTH);
    providers.add(choose, "UNSELECTED");
    providers.add(buildApiForm(), "API_KEY");
    providers.add(chatGptPanel, "CHATGPT");
    chatGptTab.addActionListener(event -> chooseProvider(TeacherSettings.Provider.CHATGPT));
    apiTab.addActionListener(event -> chooseProvider(TeacherSettings.Provider.API_KEY));
    JPanel providerContent = TeacherSettingsStyle.panel(new BorderLayout(0, 18));
    providerContent.add(tabs, BorderLayout.NORTH);
    providerContent.add(providers, BorderLayout.CENTER);
    page.add(providerContent, BorderLayout.CENTER);
    return page;
  }

  private JPanel buildApiForm() {
    JPanel api = TeacherSettingsStyle.panel(new GridBagLayout());
    GridBagConstraints row = new GridBagConstraints();
    row.insets = new Insets(8, 0, 8, 0);
    row.anchor = GridBagConstraints.WEST;
    row.fill = GridBagConstraints.HORIZONTAL;
    for (JComponent component : List.of(baseUrlField, apiKeyField, modelBox)) {
      TeacherSettingsStyle.input(component);
    }
    baseUrlField.setName("apiBaseUrl");
    apiKeyField.setName("apiSecret");
    modelBox.setName("apiModel");
    modelBox.setEditable(true);
    TeacherSettingsStyle.button(refreshModels, false);
    JPanel keyRow = TeacherSettingsStyle.panel(new BorderLayout(8, 0));
    keyRow.add(apiKeyField, BorderLayout.CENTER);
    keyRow.add(showApiKey, BorderLayout.EAST);
    JPanel modelRow = TeacherSettingsStyle.panel(new BorderLayout(8, 0));
    modelRow.add(modelBox, BorderLayout.CENTER);
    modelRow.add(refreshModels, BorderLayout.EAST);
    apiRow(
        api,
        row,
        0,
        TeacherStrings.get("Teacher.settings.baseUrl", "API base URL"),
        baseUrlField,
        baseUrlField);
    apiRow(api, row, 1, "API Key", keyRow, apiKeyField);
    apiRow(api, row, 2, TeacherStrings.get("Teacher.settings.model", "Model"), modelRow, modelBox);
    row.gridx = 1;
    row.gridy = 3;
    api.add(rememberApiKey, row);
    row.gridx = 0;
    row.gridy = 4;
    row.gridwidth = 2;
    api.add(
        TeacherSettingsStyle.note(
            TeacherStrings.get(
                "Teacher.settings.privacy", "The key is not stored in normal configuration files."),
            1),
        row);
    row.gridy = 5;
    api.add(
        TeacherSettingsStyle.note(
            design(
                "privacy",
                "Only selected analysis and questions are sent; the full game is not uploaded."),
            2),
        row);
    row.gridy = 6;
    row.weighty = 1;
    api.add(TeacherSettingsStyle.panel(new BorderLayout()), row);
    return api;
  }

  private static void apiRow(
      JPanel form,
      GridBagConstraints row,
      int y,
      String text,
      JComponent component,
      JComponent field) {
    JLabel label = TeacherSettingsStyle.label(text, 15, false);
    label.setLabelFor(field);
    row.gridy = y;
    row.gridx = 0;
    row.weightx = 0;
    row.insets = new Insets(5, 0, 5, 14);
    form.add(label, row);
    row.gridx = 1;
    row.weightx = 1;
    row.insets = new Insets(5, 0, 5, 0);
    form.add(component, row);
  }

  private JPanel buildPreferencesPage() {
    JPanel page = TeacherSettingsStyle.panel(new BorderLayout());
    page.add(
        TeacherSettingsStyle.heading(
            design("preferences", "Commentary preferences"),
            design("preferencesHint", "Shared by ChatGPT and API Key")),
        BorderLayout.NORTH);
    JPanel rows = TeacherSettingsStyle.panel(new GridBagLayout());
    for (int number = 18; number >= 1; number--) rankChoice.addItem(new RankChoice(false, number));
    for (int number = 1; number <= 9; number++) rankChoice.addItem(new RankChoice(true, number));
    TeacherSettingsStyle.input(rankChoice);
    rankChoice.addActionListener(
        event -> {
          RankChoice selected = (RankChoice) rankChoice.getSelectedItem();
          if (selected != null) {
            rankModeBox.setSelectedIndex(selected.dan ? 1 : 0);
            updateRankBounds();
            rankNumSpinner.setValue(selected.number);
          }
        });
    for (JComponent component : List.of(styleBox, densityBox, paceBox, variationBox)) {
      TeacherSettingsStyle.input(component);
    }
    rankChoice.setName("rankPreference");
    styleBox.setName("stylePreference");
    preferenceRow(
        rows,
        0,
        design("level", "My level"),
        design("levelHint", "Adjust explanation depth to your playing level"),
        rankChoice,
        rankChoice);
    preferenceRow(
        rows,
        1,
        TeacherStrings.get("Teacher.settings.style", "Style"),
        design("styleHint", "Choose how you like things explained"),
        styleBox,
        styleBox);
    preferenceRow(
        rows,
        2,
        TeacherStrings.get("Teacher.settings.terminology", "Terminology"),
        design("termsHint", "How often Go terminology is used"),
        densityBox,
        densityBox);
    preferenceRow(
        rows,
        3,
        TeacherStrings.get("Teacher.settings.pace", "Pace"),
        design("paceHint", "Choose a concise or detailed explanation"),
        paceBox,
        paceBox);
    preferenceRow(
        rows,
        4,
        TeacherStrings.get("Teacher.settings.variation", "Variation"),
        design("variationHint", "How deeply to explore follow-up moves"),
        variationBox,
        variationBox);
    TeacherSettingsStyle.link(reloadKnowledge);
    reloadKnowledge.setIcon(TeacherSettingsStyle.icon("book-open", 22));
    reloadKnowledge.setIconTextGap(8);
    GridBagConstraints reload = new GridBagConstraints();
    reload.gridx = 0;
    reload.gridy = 7;
    reload.weightx = 1;
    reload.gridwidth = 2;
    reload.anchor = GridBagConstraints.WEST;
    reload.insets = new Insets(0, 0, 0, 0);
    rows.add(reloadKnowledge, reload);
    JPanel top = TeacherSettingsStyle.panel(new BorderLayout());
    top.add(rows, BorderLayout.NORTH);
    page.add(top, BorderLayout.CENTER);
    return page;
  }

  private static void preferenceRow(
      JPanel rows, int index, String title, String hint, JComponent control, JComponent field) {
    if (index == 0 || index == 2) {
      JLabel section =
          TeacherSettingsStyle.label(
              index == 0
                  ? design("personal", "For your level")
                  : design("details", "Explanation details"),
              14,
              true);
      section.setForeground(TeacherSettingsStyle.muted());
      GridBagConstraints sectionRow = new GridBagConstraints();
      sectionRow.gridx = 0;
      sectionRow.gridy = index == 0 ? 0 : 3;
      sectionRow.gridwidth = 2;
      sectionRow.anchor = GridBagConstraints.WEST;
      sectionRow.insets = new Insets(index == 0 ? 0 : 12, 0, 4, 0);
      rows.add(section, sectionRow);
    }
    JPanel description = TeacherSettingsStyle.panel(new BorderLayout(0, 4));
    JLabel label = TeacherSettingsStyle.label(title, 16, true);
    label.setLabelFor(field);
    field.getAccessibleContext().setAccessibleName(title);
    field.getAccessibleContext().setAccessibleDescription(hint);
    description.add(label, BorderLayout.NORTH);
    description.add(TeacherSettingsStyle.note(hint, 1), BorderLayout.CENTER);
    JPanel line = TeacherSettingsStyle.panel(new BorderLayout(22, 0));
    line.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, TeacherSettingsStyle.border()),
            BorderFactory.createEmptyBorder(4, 0, 4, 0)));
    line.add(description, BorderLayout.CENTER);
    control.setPreferredSize(new Dimension(220, 38));
    JPanel right = TeacherSettingsStyle.panel(new java.awt.GridBagLayout());
    right.add(control);
    line.add(right, BorderLayout.EAST);
    GridBagConstraints row = new GridBagConstraints();
    row.gridy = index + (index < 2 ? 1 : 2);
    row.gridx = 0;
    row.weightx = 1;
    row.gridwidth = 2;
    row.fill = GridBagConstraints.HORIZONTAL;
    rows.add(line, row);
  }

  private record RankChoice(boolean dan, int number) {
    @Override
    public String toString() {
      return TeacherStrings.format(
          "Teacher.settings.design." + (dan ? "rankDan" : "rankKyu"),
          dan ? "{0} dan" : "{0} kyu",
          number);
    }
  }

  private JScrollPane scrollPage(JPanel page) {
    page.setBorder(BorderFactory.createEmptyBorder(20, 36, 0, 36));
    JPanel tracking = new WidthTrackingPage();
    tracking.add(page, BorderLayout.CENTER);
    JScrollPane scroll = new JScrollPane(tracking);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.getViewport().setBackground(TeacherSettingsStyle.surface());
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    return scroll;
  }

  private static final class WidthTrackingPage extends JPanel implements javax.swing.Scrollable {
    WidthTrackingPage() {
      super(new BorderLayout());
      setOpaque(false);
    }

    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    public int getScrollableUnitIncrement(
        java.awt.Rectangle visible, int orientation, int direction) {
      return 16;
    }

    public int getScrollableBlockIncrement(
        java.awt.Rectangle visible, int orientation, int direction) {
      return Math.max(16, visible.height - 16);
    }

    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    public boolean getScrollableTracksViewportHeight() {
      return getParent() instanceof javax.swing.JViewport viewport
          && viewport.getHeight() >= getPreferredSize().height;
    }
  }

  private void showPage(boolean preferences) {
    ((CardLayout) pages.getLayout()).show(pages, preferences ? "preferences" : "connection");
    preferencesVisible = preferences;
    saveButton.setText(
        preferences
            ? design("savePreferences", "Save preferences")
            : design("save", "Save settings"));
    saveButton.getAccessibleContext().setAccessibleDescription(saveButton.getText());
    if (isShowing()) (preferences ? preferencesPage : connectionPage).requestFocusInWindow();
  }

  private static String design(String key, String fallback) {
    return TeacherSettingsStyle.text(key, fallback);
  }

  private void loadValues() {
    setInputsEnabled(false);
    cancelButton.setEnabled(true);
    status.setText(
        TeacherStrings.get("Teacher.status.loadingSettings", "Loading secure settings..."));
    new SwingWorker<LoadedValues, Void>() {
      @Override
      protected LoadedValues doInBackground() throws Exception {
        TeacherSettings.Snapshot snapshot = settings.load();
        return new LoadedValues(snapshot, settings.apiKey().orElse(""));
      }

      @Override
      protected void done() {
        if (!isDisplayable()) {
          return;
        }
        try {
          LoadedValues loaded = get();
          TeacherSettings.Snapshot snapshot = loaded.snapshot;
          chatGptTab.setSelected(snapshot.provider == TeacherSettings.Provider.CHATGPT);
          apiTab.setSelected(snapshot.provider == TeacherSettings.Provider.API_KEY);
          chooseProvider(snapshot.provider);
          baseUrlField.setText(snapshot.baseUrl);
          modelBox.addItem(snapshot.model);
          modelBox.setSelectedItem(snapshot.model);
          rememberApiKey.setSelected(snapshot.rememberApiKey);
          rankModeBox.setSelectedIndex("d".equals(snapshot.rankMode) ? 1 : 0);
          updateRankBounds();
          rankNumSpinner.setValue(snapshot.rankNum);
          rankChoice.setSelectedIndex(
              "d".equals(snapshot.rankMode) ? 17 + snapshot.rankNum : 18 - snapshot.rankNum);
          styleBox.setSelectedIndex(clampIndex(snapshot.styleIndex, 4));
          densityBox.setSelectedIndex(clampIndex(snapshot.densityIndex, 2));
          paceBox.setSelectedIndex(clampIndex(snapshot.paceIndex, 2));
          variationBox.setSelectedIndex(clampIndex(snapshot.variationIndex, 2));
          apiKeyField.setText(loaded.apiKey);
          if (!snapshot.secureStorageAvailable) {
            rememberApiKey.setToolTipText(
                TeacherStrings.get(
                    "Teacher.settings.storageUnavailable",
                    "System credential storage is unavailable; the key will be session-only."));
          }
          setInputsEnabled(true);
          rememberApiKey.setEnabled(snapshot.secureStorageAvailable);
          status.setText(
              snapshot.provider == TeacherSettings.Provider.UNSELECTED
                  ? ChatGptSettingsPanel.text(
                      "choose", "Choose a connection method and finish setup.")
                  : " ");
        } catch (Exception error) {
          setInputsEnabled(true);
          rememberApiKey.setEnabled(false);
          status.setText(localError(error));
        }
      }
    }.execute();
  }

  private void refreshModels() {
    char[] key = apiKeyField.getPassword();
    String baseUrl = baseUrlField.getText();
    String selectedModel = selectedModel();
    if (key.length == 0) {
      status.setText(TeacherStrings.get("Teacher.settings.enterKey", "Enter an API key first."));
      return;
    }
    refreshModels.setEnabled(false);
    status.setText(TeacherStrings.get("Teacher.settings.loadingModels", "Loading models..."));
    char[] keyCopy = key.clone();
    Arrays.fill(key, '\0');
    long request = providerGeneration;
    apiModelWorker =
        new SwingWorker<List<String>, Void>() {
          @Override
          protected List<String> doInBackground() throws Exception {
            try {
              return new TeacherLlmClient(baseUrl, new String(keyCopy), selectedModel).listModels();
            } finally {
              Arrays.fill(keyCopy, '\0');
            }
          }

          @Override
          protected void done() {
            if (!isDisplayable() || providerGeneration != request) return;
            refreshModels.setEnabled(true);
            try {
              List<String> models = get();
              Object previous = modelBox.getEditor().getItem();
              modelBox.removeAllItems();
              for (String model : models) {
                modelBox.addItem(model);
              }
              if (previous != null && !previous.toString().isBlank()) {
                modelBox.setSelectedItem(previous.toString());
              }
              status.setText(
                  TeacherStrings.format(
                      "Teacher.settings.modelsLoaded", "Loaded {0} models.", models.size()));
            } catch (Exception error) {
              status.setText(localError(error));
            }
          }
        };
    apiModelWorker.execute();
  }

  private void updateRankBounds() {
    SpinnerNumberModel model = (SpinnerNumberModel) rankNumSpinner.getModel();
    int maximum = rankModeBox.getSelectedIndex() == 1 ? 9 : 18;
    model.setMaximum(maximum);
    if (((Number) model.getValue()).intValue() > maximum) {
      model.setValue(maximum);
    }
  }

  private void save() {
    boolean preferencesOnly = preferencesVisible;
    TeacherSettings.Provider provider =
        chatGptTab.isSelected()
            ? TeacherSettings.Provider.CHATGPT
            : apiTab.isSelected()
                ? TeacherSettings.Provider.API_KEY
                : TeacherSettings.Provider.UNSELECTED;
    if (!preferencesOnly
        && (provider == TeacherSettings.Provider.UNSELECTED
            || (provider == TeacherSettings.Provider.CHATGPT && !chatGptPanel.isReady()))) {
      status.setText(
          ChatGptSettingsPanel.text("choose", "Choose a connection method and finish setup."));
      return;
    }
    String chatAccount = chatGptPanel.selectedAccountId();
    String chatModel = chatGptPanel.selectedModel();
    char[] key = apiKeyField.getPassword();
    String requestedBaseUrl = baseUrlField.getText();
    String requestedModel = selectedModel();
    boolean requestedRemember = rememberApiKey.isSelected();
    String rankMode = rankModeBox.getSelectedIndex() == 1 ? "d" : "k";
    int rankNumber = ((Number) rankNumSpinner.getValue()).intValue();
    int style = styleBox.getSelectedIndex();
    int density = densityBox.getSelectedIndex();
    int pace = paceBox.getSelectedIndex();
    int variation = variationBox.getSelectedIndex();
    setInputsEnabled(false);
    if (!preferencesOnly) {
      chatGptPanel.suspend();
      setChildrenEnabled(chatGptPanel, false);
    }
    cancelButton.setEnabled(false);
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    status.setText(TeacherStrings.get("Teacher.settings.saving", "Saving securely..."));
    new SwingWorker<TeacherSettings.Snapshot, Void>() {
      @Override
      protected TeacherSettings.Snapshot doInBackground() throws Exception {
        settings.saveTeachingPreferences(rankMode, rankNumber, style, density, pace, variation);
        if (preferencesOnly) return settings.snapshot();
        if (provider == TeacherSettings.Provider.API_KEY) {
          settings.save(requestedBaseUrl, requestedModel, key, requestedRemember);
        } else {
          settings.chatGpt().model(chatAccount, chatModel);
          settings.chatGpt().select(chatAccount);
          settings.refreshChatGptAccount();
        }
        settings.selectProvider(provider);
        return settings.snapshot();
      }

      @Override
      protected void done() {
        Arrays.fill(key, '\0');
        try {
          get();
          saved = true;
          if (preferencesOnly) {
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            setInputsEnabled(true);
            cancelButton.setEnabled(true);
            status.setText(
                design(
                    "preferencesSaved", "Preferences saved. Connection settings are unchanged."));
            return;
          }
          dispose();
        } catch (Exception error) {
          setDefaultCloseOperation(DISPOSE_ON_CLOSE);
          setInputsEnabled(true);
          if (!preferencesOnly) {
            setChildrenEnabled(chatGptPanel, true);
            if (chatGptTab.isSelected()) chatGptPanel.reload();
          }
          cancelButton.setEnabled(true);
          status.setText(localError(error));
        }
      }
    }.execute();
  }

  private void setInputsEnabled(boolean enabled) {
    connectionPage.setEnabled(enabled);
    preferencesPage.setEnabled(enabled);
    reloadKnowledge.setEnabled(enabled);
    chatGptTab.setEnabled(enabled);
    apiTab.setEnabled(enabled);
    baseUrlField.setEnabled(enabled);
    apiKeyField.setEnabled(enabled);
    modelBox.setEnabled(enabled);
    showApiKey.setEnabled(enabled);
    rememberApiKey.setEnabled(enabled);
    rankModeBox.setEnabled(enabled);
    rankNumSpinner.setEnabled(enabled);
    rankChoice.setEnabled(enabled);
    styleBox.setEnabled(enabled);
    densityBox.setEnabled(enabled);
    paceBox.setEnabled(enabled);
    variationBox.setEnabled(enabled);
    refreshModels.setEnabled(enabled);
    saveButton.setEnabled(enabled);
  }

  private void chooseProvider(TeacherSettings.Provider provider) {
    providerGeneration++;
    if (apiModelWorker != null) apiModelWorker.cancel(true);
    refreshModels.setEnabled(true);
    chatGptPanel.suspend();
    ((CardLayout) providers.getLayout()).show(providers, provider.name());
    if (provider == TeacherSettings.Provider.CHATGPT) chatGptPanel.reload();
    providers.revalidate();
    status.setText(
        provider == TeacherSettings.Provider.UNSELECTED
            ? ChatGptSettingsPanel.text("choose", "Choose a connection method and finish setup.")
            : " ");
  }

  @Override
  public void dispose() {
    providerGeneration++;
    if (apiModelWorker != null) apiModelWorker.cancel(true);
    if (chatGptPanel != null) chatGptPanel.suspend();
    super.dispose();
  }

  private static void setChildrenEnabled(java.awt.Container parent, boolean enabled) {
    for (Component child : parent.getComponents()) {
      child.setEnabled(enabled);
      if (child instanceof java.awt.Container container) setChildrenEnabled(container, enabled);
    }
  }

  private static int clampIndex(int value, int max) {
    return Math.max(0, Math.min(max, value));
  }

  private String selectedModel() {
    Object value = modelBox.getEditor().getItem();
    return value == null ? "" : value.toString().trim();
  }

  private static String localError(Throwable error) {
    Throwable cause = error;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    String message = cause.getMessage();
    return message == null || message.isBlank()
        ? TeacherStrings.get("Teacher.error.generic", "The operation failed.")
        : message;
  }

  private static final class LoadedValues {
    private final TeacherSettings.Snapshot snapshot;
    private final String apiKey;

    private LoadedValues(TeacherSettings.Snapshot snapshot, String apiKey) {
      this.snapshot = snapshot;
      this.apiKey = apiKey;
    }
  }
}
