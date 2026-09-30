package featurecat.lizzie.teacher;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.URI;
import java.util.List;
import java.util.concurrent.Callable;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;

/** Account UI contains no token or password fields. All authentication work is off the EDT. */
final class ChatGptSettingsPanel extends JPanel {
  private final ChatGptSessions sessions;
  private final JComboBox<ChatGptSessions.Account> accounts = new JComboBox<>();
  private final JComboBox<ChatGptCommentaryClient.Model> models = new JComboBox<>();
  private final JButton connect = button("connect", "Continue with ChatGPT");
  private final JButton add = button("add", "Add account");
  private final JButton logout = button("logout", "Sign out");
  private final JButton refresh = button("refresh", "Refresh models");
  private final JButton cancel = button("cancel", "Cancel sign-in");
  private final JTextArea status = note("");
  private final JPanel welcome = new JPanel();
  private final JPanel fields = new JPanel();
  private final JPanel actions = new JPanel();
  private final JPanel privacy = TeacherSettingsStyle.panel(new BorderLayout(10, 0));
  private final JButton usage = button("usage", "Manage usage");
  private final JPanel signInRow =
      TeacherSettingsStyle.panel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
  private final JTextArea planNotice =
      TeacherSettingsStyle.note(
          TeacherSettingsStyle.text(
              "planNotice",
              "An eligible ChatGPT plan is required. Commentary uses shared plan limits, not unlimited usage."),
          2);
  private boolean updating;
  private long generation;
  private ChatGptSignIn attempt;
  private SwingWorker<?, ?> worker;

  ChatGptSettingsPanel(ChatGptSessions sessions) {
    super(new BorderLayout(0, 8));
    this.sessions = sessions;
    setOpaque(false);
    status.getAccessibleContext().setAccessibleName(text("tab", "ChatGPT login"));
    status.setName("chatGptStatus");
    JPanel content = TeacherSettingsStyle.panel(new GridBagLayout());
    GridBagConstraints row = new GridBagConstraints();
    row.gridx = 0;
    row.gridy = 0;
    row.weightx = 1;
    row.fill = GridBagConstraints.HORIZONTAL;
    row.insets = new Insets(3, 0, 3, 0);

    welcome.setLayout(new BorderLayout(36, 0));
    welcome.setOpaque(false);
    welcome.setBorder(BorderFactory.createEmptyBorder(14, 20, 6, 0));
    JLabel icon = new JLabel(TeacherSettingsStyle.icon("browser-globe", 86));
    icon.setName("chatGptWelcomeIcon");
    icon.setVerticalAlignment(javax.swing.SwingConstants.TOP);
    welcome.add(icon, BorderLayout.WEST);
    JPanel intro = TeacherSettingsStyle.panel(new BorderLayout(0, 10));
    intro.add(
        TeacherSettingsStyle.label(
            TeacherSettingsStyle.text("chatTitle", "Use your ChatGPT account"), 21, true),
        BorderLayout.NORTH);
    JTextArea loginHint =
        TeacherSettingsStyle.note(
            TeacherSettingsStyle.text(
                "chatHint", "Sign in in your browser. No password is collected."),
            1);
    loginHint.setFont(TeacherSettingsStyle.font(15, false));
    intro.add(loginHint, BorderLayout.CENTER);
    welcome.add(intro, BorderLayout.CENTER);
    content.add(welcome, row);

    fields.setLayout(new GridBagLayout());
    fields.setOpaque(false);
    GridBagConstraints fieldRow = new GridBagConstraints();
    fieldRow.gridx = 0;
    fieldRow.gridy = 0;
    fieldRow.weightx = 1;
    fieldRow.fill = GridBagConstraints.HORIZONTAL;
    fieldRow.insets = new Insets(3, 0, 4, 0);
    TeacherSettingsStyle.input(accounts);
    TeacherSettingsStyle.input(models);
    accounts.setName("chatGptAccounts");
    models.setName("chatGptModels");
    refresh.setName("chatGptRefresh");
    logout.setName("chatGptLogout");
    JLabel accountLabel = TeacherSettingsStyle.label(text("account", "ChatGPT account"), 15, true);
    accountLabel.setLabelFor(accounts);
    fields.add(accountLabel, fieldRow);
    fieldRow.gridy++;
    fields.add(accounts, fieldRow);
    JLabel modelLabel = TeacherSettingsStyle.label(text("model", "ChatGPT model"), 15, true);
    modelLabel.setLabelFor(models);
    fieldRow.gridy++;
    fields.add(modelLabel, fieldRow);
    JPanel modelRow = TeacherSettingsStyle.panel(new BorderLayout(8, 0));
    modelRow.add(models, BorderLayout.CENTER);
    modelRow.add(refresh, BorderLayout.LINE_END);
    fieldRow.gridy++;
    fields.add(modelRow, fieldRow);
    row.gridy++;
    content.add(fields, row);

    for (JButton button : List.of(connect, add, logout, refresh, cancel, usage)) {
      TeacherSettingsStyle.button(button, button == connect);
    }
    connect.setName("chatGptConnect");
    usage.setName("chatGptUsage");
    connect.setPreferredSize(
        new java.awt.Dimension(Math.max(270, connect.getPreferredSize().width), 44));
    signInRow.add(connect);
    signInRow.add(cancel);
    row.gridy++;
    content.add(signInRow, row);
    actions.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
    actions.setOpaque(false);
    actions.add(add);
    actions.add(logout);
    actions.add(usage);
    row.gridy++;
    content.add(actions, row);
    row.gridy++;
    content.add(planNotice, row);
    row.gridy++;
    content.add(status, row);
    row.gridy++;
    row.weighty = 1;
    content.add(TeacherSettingsStyle.panel(new BorderLayout()), row);
    add(content, BorderLayout.CENTER);
    privacy.add(new JLabel(TeacherSettingsStyle.icon("lock-keyhole", 22)), BorderLayout.WEST);
    privacy.add(
        TeacherSettingsStyle.note(
            TeacherSettingsStyle.text(
                "privacy",
                "Only selected analysis and questions are sent; the full game is not uploaded."),
            1),
        BorderLayout.CENTER);
    add(privacy, BorderLayout.SOUTH);
    fields.setVisible(false);
    actions.setVisible(false);
    alignWelcomeActions(true);
    usage.addActionListener(
        event ->
            work(
                () -> {
                  browse(URI.create("https://chatgpt.com/settings/usage"));
                  return null;
                },
                ignored -> updateStatus()));
    cancel.setVisible(false);
    connect.addActionListener(event -> signIn(selected() == null ? null : selected().id));
    add.addActionListener(event -> signIn(null));
    cancel.addActionListener(
        event -> {
          suspend();
          reload();
        });
    logout.addActionListener(
        event -> {
          ChatGptSessions.Account account = selected();
          if (account == null) return;
          work(
              () -> sessions.signOut(account.id),
              revoked -> {
                reload();
                if (!revoked)
                  JOptionPane.showMessageDialog(
                      this,
                      text(
                          "revocation",
                          "Signed out locally. Remote revocation was not confirmed; disconnect the app in ChatGPT settings."));
              });
        });
    accounts.addActionListener(
        event -> {
          if (updating || selected() == null) return;
          String id = selected().id;
          work(
              () -> {
                sessions.select(id);
                return null;
              },
              ignored -> reload());
        });
    refresh.addActionListener(event -> refreshModels());
  }

  void reload() {
    work(
        () -> new Loaded(sessions.list(), sessions.active()),
        loaded -> {
          updating = true;
          accounts.removeAllItems();
          for (ChatGptSessions.Account account : loaded.accounts) {
            accounts.addItem(account);
            if (loaded.active != null && loaded.active.id.equals(account.id))
              accounts.setSelectedItem(account);
          }
          updating = false;
          showAccount();
        });
  }

  private void showAccount() {
    ChatGptSessions.Account account = selected();
    boolean signedIn = account != null && account.signedIn;
    welcome.setVisible(!signedIn && accounts.getItemCount() == 0);
    fields.setVisible(accounts.getItemCount() > 0);
    actions.setVisible(signedIn);
    planNotice.setVisible(!signedIn || !account.authorized);
    connect.setVisible(!signedIn || !account.authorized);
    signInRow.setVisible(connect.isVisible());
    alignWelcomeActions(welcome.isVisible());
    revalidate();
    repaint();
    connect.setEnabled(!signedIn || !account.authorized);
    logout.setEnabled(signedIn);
    refresh.setEnabled(signedIn && account.authorized);
    models.removeAllItems();
    models.setEnabled(signedIn && account.authorized);
    updateStatus();
    if (signedIn && account.authorized && !account.welcomed) {
      JOptionPane.showMessageDialog(
          this,
          text(
              "welcome",
              "You are using your ChatGPT plan. Manage shared usage in ChatGPT settings."),
          text("usingPlan", "Using ChatGPT plan"),
          JOptionPane.INFORMATION_MESSAGE);
      work(
          () -> {
            sessions.welcomed(account.id);
            return null;
          },
          ignored -> refreshModels());
    } else if (signedIn && account.authorized) {
      refreshModels();
    }
  }

  private void updateStatus() {
    ChatGptSessions.Account account = selected();
    boolean signedIn = account != null && account.signedIn;
    status.setVisible(signedIn);
    status.setText(
        !signedIn
            ? text("notConnected", "Connect ChatGPT to use your plan.")
            : !account.authorized
                ? text(
                    "error.permission",
                    "Authorize ChatGPT plan usage before generating commentary.")
                : account.sessionOnly
                    ? text(
                        "sessionOnly",
                        "Connected for this session only. Secure storage is unavailable.")
                    : text("usingPlan", "Using ChatGPT plan"));
  }

  private void alignWelcomeActions(boolean empty) {
    signInRow.setBorder(BorderFactory.createEmptyBorder(0, empty ? 142 : 0, 4, 0));
    planNotice.setBorder(BorderFactory.createEmptyBorder(0, empty ? 142 : 0, 0, 0));
    privacy.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, TeacherSettingsStyle.border()),
            BorderFactory.createEmptyBorder(14, 0, empty ? 26 : 0, 0)));
  }

  private void signIn(String id) {
    signInRow.setVisible(true);
    cancel.setVisible(true);
    work(
        () -> {
          ChatGptSignIn signIn = sessions.signIn(id);
          synchronized (this) {
            if (Thread.currentThread().isInterrupted()) {
              signIn.close();
              throw new java.util.concurrent.CancellationException();
            }
            attempt = signIn;
          }
          try {
            browse(signIn.authorization);
          } catch (Exception error) {
            signIn.close();
            throw error;
          }
          return signIn.result.get();
        },
        account -> {
          cancel.setVisible(false);
          reload();
        });
  }

  private void refreshModels() {
    ChatGptSessions.Account account = selected();
    if (account == null || !account.signedIn || !account.authorized) return;
    work(
        () -> new ChatGptCommentaryClient(sessions, account.id, account.model).models(),
        available -> {
          models.removeAllItems();
          for (ChatGptCommentaryClient.Model model : available) {
            models.addItem(model);
            if (account.model.equals(model.slug)) models.setSelectedItem(model);
          }
          status.setText(
              account.sessionOnly
                  ? text(
                      "sessionOnly",
                      "Connected for this session only. Secure storage is unavailable.")
                  : text("usingPlan", "Using ChatGPT plan"));
        });
  }

  String selectedAccountId() {
    return selected() == null ? null : selected().id;
  }

  String selectedModel() {
    ChatGptCommentaryClient.Model model = (ChatGptCommentaryClient.Model) models.getSelectedItem();
    return model == null ? "" : model.slug;
  }

  boolean isReady() {
    ChatGptSessions.Account account = selected();
    return (worker == null || worker.isDone())
        && account != null
        && account.signedIn
        && account.authorized
        && !selectedModel().isBlank();
  }

  private ChatGptSessions.Account selected() {
    return (ChatGptSessions.Account) accounts.getSelectedItem();
  }

  synchronized void suspend() {
    generation++;
    if (worker != null) {
      worker.cancel(true);
      worker = null;
    }
    if (attempt != null) {
      ChatGptSignIn closing = attempt;
      attempt = null;
      // Native credential storage may be finishing a commit. Never block the EDT on it.
      java.util.concurrent.CompletableFuture.runAsync(closing::close);
    }
    cancel.setVisible(false);
  }

  private <T> void work(Callable<T> operation, java.util.function.Consumer<T> completed) {
    if (worker != null && !worker.isDone()) worker.cancel(true);
    long request = ++generation;
    connect.setEnabled(false);
    add.setEnabled(false);
    logout.setEnabled(false);
    refresh.setEnabled(false);
    models.setEnabled(false);
    accounts.setEnabled(false);
    status.setText(text("working", "Connecting..."));
    status.setVisible(true);
    worker =
        new SwingWorker<T, Void>() {
          @Override
          protected T doInBackground() throws Exception {
            return operation.call();
          }

          @Override
          protected void done() {
            if (generation != request || !isDisplayable()) return;
            add.setEnabled(true);
            accounts.setEnabled(true);
            ChatGptSessions.Account account = selected();
            connect.setEnabled(account == null || !account.signedIn || !account.authorized);
            logout.setEnabled(account != null && account.signedIn);
            refresh.setEnabled(account != null && account.signedIn && account.authorized);
            models.setEnabled(refresh.isEnabled());
            try {
              completed.accept(get());
            } catch (Exception failure) {
              cancel.setVisible(false);
              connect.setEnabled(true);
              connect.setVisible(true);
              signInRow.setVisible(true);
              Throwable root = failure;
              while (root.getCause() != null) root = root.getCause();
              // Network/library exceptions may include URLs. Only display known localized errors.
              String message = root.getMessage();
              status.setText(
                  ChatGptText.isKnownError(message)
                      ? message
                      : text(
                          "error.network",
                          "Could not connect to ChatGPT. Check your network and retry."));
            }
          }
        };
    worker.execute();
  }

  static void browse(URI uri) throws Exception {
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
      throw ChatGptHttp.error("browser");
    }
    Desktop.getDesktop().browse(uri);
  }

  static String text(String key, String fallback) {
    return TeacherStrings.get("Teacher.chatgpt." + key, fallback);
  }

  private static JButton button(String key, String fallback) {
    JButton button = new JButton(text(key, fallback));
    button.getAccessibleContext().setAccessibleDescription(text(key, fallback));
    return button;
  }

  private static JTextArea note(String text) {
    return TeacherSettingsStyle.note(text, 1);
  }

  private record Loaded(List<ChatGptSessions.Account> accounts, ChatGptSessions.Account active) {}
}
