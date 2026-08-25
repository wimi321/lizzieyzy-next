package featurecat.lizzie.update;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.JFontButton;
import featurecat.lizzie.gui.JFontLabel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/** 检查更新页: current version, 更新通道, 更新源, and Check. Opening the page does not use the network. */
public final class CheckUpdateDialog extends JDialog {
  private static final Color PAGE_BACKGROUND = new Color(246, 247, 249);
  private static final Color MUTED_TEXT = new Color(90, 96, 104);

  private final JRadioButton stableButton =
      channelRadio(UpdateText.tr("WindowsUpdate.channel.stable", "正式", "Official"));
  private final JRadioButton betaButton =
      channelRadio(UpdateText.tr("WindowsUpdate.channel.beta", "测试", "Test"));
  private final JRadioButton officialSourceButton =
      channelRadio(UpdateText.tr("WindowsUpdate.source.official", "官网", "Official site"));
  private final JRadioButton githubSourceButton =
      channelRadio(UpdateText.tr("WindowsUpdate.source.github", "GitHub", "GitHub"));

  public CheckUpdateDialog(Component parent) {
    super(
        parent == null ? null : SwingUtilities.getWindowAncestor(parent),
        UpdateText.tr("WindowsUpdate.page.title", "检查更新", "Check update"),
        ModalityType.APPLICATION_MODAL);
    buildUi(parent);
  }

  private void buildUi(Component parent) {
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    JPanel root = new JPanel(new BorderLayout(0, 16));
    root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
    root.setBackground(PAGE_BACKGROUND);
    setContentPane(root);

    root.add(buildHeader(), BorderLayout.NORTH);
    root.add(buildForm(), BorderLayout.CENTER);
    root.add(buildFooter(), BorderLayout.SOUTH);

    pack();
    setMinimumSize(new Dimension(Math.max(460, getWidth()), getHeight()));
    setLocationRelativeTo(parent == null ? Lizzie.frame : parent);
  }

  private JPanel buildHeader() {
    JPanel header = new JPanel(new BorderLayout(0, 4));
    header.setOpaque(false);

    JFontLabel versionLabel =
        new JFontLabel(
            UpdateText.tr("WindowsUpdate.currentVersion", "当前版本", "Current version"));
    versionLabel.setForeground(mutedText());

    JFontLabel versionValue = new JFontLabel(displayVersion());
    versionValue.setFont(versionValue.getFont().deriveFont(Font.BOLD, 16f));

    header.add(versionLabel, BorderLayout.NORTH);
    header.add(versionValue, BorderLayout.CENTER);
    return header;
  }

  private JPanel buildForm() {
    JPanel form = new JPanel(new GridBagLayout());
    form.setOpaque(false);

    JFontLabel channelLabel =
        new JFontLabel(UpdateText.tr("WindowsUpdate.page.channel", "更新通道", "Update channel"));
    channelLabel.setLabelFor(stableButton);

    UpdateChannel current = UpdateChannel.current();
    stableButton.setSelected(current == UpdateChannel.STABLE);
    betaButton.setSelected(current == UpdateChannel.BETA);
    ButtonGroup group = new ButtonGroup();
    group.add(stableButton);
    group.add(betaButton);
    stableButton.addActionListener(
        e -> {
          UpdateChannel.persist(UpdateChannel.STABLE);
          refreshSourceState();
        });
    betaButton.addActionListener(
        e -> {
          UpdateChannel.persist(UpdateChannel.BETA);
          refreshSourceState();
        });

    JPanel channelRow = new JPanel();
    channelRow.setOpaque(false);
    channelRow.setLayout(new BoxLayout(channelRow, BoxLayout.X_AXIS));
    stableButton.setAlignmentY(Component.CENTER_ALIGNMENT);
    betaButton.setAlignmentY(Component.CENTER_ALIGNMENT);
    channelRow.add(stableButton);
    channelRow.add(Box.createHorizontalStrut(12));
    channelRow.add(betaButton);

    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = 0;
    constraints.gridy = 0;
    constraints.anchor = GridBagConstraints.WEST;
    constraints.insets = new Insets(0, 0, 8, 0);
    form.add(channelLabel, constraints);

    constraints.gridy = 1;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.weightx = 1;
    constraints.insets = new Insets(0, 0, 14, 0);
    form.add(channelRow, constraints);

    JFontLabel sourceLabel =
        new JFontLabel(UpdateText.tr("WindowsUpdate.page.source", "更新源", "Update source"));
    sourceLabel.setLabelFor(officialSourceButton);

    ButtonGroup sourceGroup = new ButtonGroup();
    sourceGroup.add(officialSourceButton);
    sourceGroup.add(githubSourceButton);

    refreshSourceState();
    officialSourceButton.addActionListener(
        e -> {
          if (selectedChannel() == UpdateChannel.STABLE) {
            UpdateSource.persist(UpdateSource.OFFICIAL_SITE);
          }
        });
    githubSourceButton.addActionListener(
        e -> {
          if (selectedChannel() == UpdateChannel.STABLE) {
            UpdateSource.persist(UpdateSource.GITHUB);
          }
        });

    JPanel sourceRow = new JPanel();
    sourceRow.setOpaque(false);
    sourceRow.setLayout(new BoxLayout(sourceRow, BoxLayout.X_AXIS));
    officialSourceButton.setAlignmentY(Component.CENTER_ALIGNMENT);
    githubSourceButton.setAlignmentY(Component.CENTER_ALIGNMENT);
    sourceRow.add(officialSourceButton);
    sourceRow.add(Box.createHorizontalStrut(12));
    sourceRow.add(githubSourceButton);

    constraints.gridy = 2;
    constraints.fill = GridBagConstraints.NONE;
    constraints.weightx = 0;
    constraints.insets = new Insets(0, 0, 8, 0);
    form.add(sourceLabel, constraints);

    constraints.gridy = 3;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.weightx = 1;
    constraints.insets = new Insets(0, 0, 0, 0);
    form.add(sourceRow, constraints);
    return form;
  }

  private JPanel buildFooter() {
    JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    footer.setOpaque(false);
    JFontButton checkButton =
        new JFontButton(UpdateText.tr("WindowsUpdate.btnCheck", "检查更新", "Check update"));
    checkButton.addActionListener(
        e ->
            WindowsUpdateController.checkForUpdate(
                this, selectedChannel(), selectedSource()));
    footer.add(checkButton);
    getRootPane().setDefaultButton(checkButton);
    return footer;
  }

  private UpdateChannel selectedChannel() {
    return betaButton.isSelected() ? UpdateChannel.BETA : UpdateChannel.STABLE;
  }

  private void refreshSourceState() {
    boolean stable = selectedChannel() == UpdateChannel.STABLE;
    officialSourceButton.setEnabled(stable);
    githubSourceButton.setEnabled(stable);
    if (stable) {
      UpdateSource current = UpdateSource.current();
      officialSourceButton.setSelected(current != UpdateSource.GITHUB);
      githubSourceButton.setSelected(current == UpdateSource.GITHUB);
      return;
    }
    officialSourceButton.setSelected(false);
    githubSourceButton.setSelected(true);
  }

  private UpdateSource selectedSource() {
    return githubSourceButton.isSelected() ? UpdateSource.GITHUB : UpdateSource.OFFICIAL_SITE;
  }

  private static JRadioButton channelRadio(String text) {
    JRadioButton button = new JRadioButton(text);
    button.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    button.setOpaque(false);
    button.setContentAreaFilled(false);
    button.setFocusPainted(false);
    button.setBorderPainted(false);
    button.setBorder(BorderFactory.createEmptyBorder());
    button.setHorizontalAlignment(SwingConstants.LEFT);
    return button;
  }

  private static Color mutedText() {
    if (Lizzie.config != null && Lizzie.config.isAppleStyle) {
      return new Color(170, 176, 184);
    }
    return MUTED_TEXT;
  }

  private static String displayVersion() {
    return Lizzie.nextVersion == null || Lizzie.nextVersion.isBlank()
        ? "-"
        : Lizzie.nextVersion;
  }
}
