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
  private final JComboBox<Effort> reasoning = new JComboBox<>();
  private final JTextArea reasoningHint = note("");
  private final java.util.Map<String, String> draftEfforts = new java.util.HashMap<>();
  private final java.util.Map<String, String> draftModels = new java.util.HashMap<>();
  private boolean updatingEffort;
  private boolean updatingModels;
  private final JButton connect = button("connect", "Continue with ChatGPT");
  private final JButton add = button("add", "Add account");
  private final JButton logout = button("logout", "Sign out");
  private final JButton refresh = button("refresh", "Refresh models");
  private final JButton cancel = button("cancel", "Cancel sign-in");
  private final JButton retry = button("retry", "Retry");
  private final JTextArea status = note("");
  private Runnable retryAction = this::reload;
  private final JPanel welcome = new JPanel();
  private final JPanel fields = new JPanel();
  private final JPanel modelFields = TeacherSettingsStyle.panel(new BorderLayout(0, 5));
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
    row.insets = new Insets(2, 0, 2, 0);

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
    JPanel modelRow = TeacherSettingsStyle.panel(new BorderLayout(8, 0));
    modelRow.add(models, BorderLayout.CENTER);
    modelRow.add(refresh, BorderLayout.LINE_END);
    JLabel effortLabel = TeacherSettingsStyle.label(text("reasoning", "Thinking depth"), 15, true);
    effortLabel.setLabelFor(reasoning);
    TeacherSettingsStyle.input(reasoning);
    reasoning.setName("chatGptReasoning");
    reasoning.setEnabled(false);
    reasoning.getAccessibleContext().setAccessibleName(effortLabel.getText());
    JPanel selectors = TeacherSettingsStyle.panel(new GridBagLayout());
    GridBagConstraints selector = new GridBagConstraints();
    selector.fill = GridBagConstraints.HORIZONTAL;
    selector.weightx = 0.68;
    selector.gridx = 0;
    selector.gridy = 0;
    selector.insets = new Insets(0, 0, 5, 12);
    selectors.add(modelLabel, selector);
    selector.gridy = 1;
    selector.insets = new Insets(0, 0, 0, 12);
    modelRow.setMinimumSize(new java.awt.Dimension(0, modelRow.getPreferredSize().height));
    selectors.add(modelRow, selector);
    selector.gridx = 1;
    selector.gridy = 0;
    selector.weightx = 0.32;
    selector.insets = new Insets(0, 0, 5, 0);
    selectors.add(effortLabel, selector);
    selector.gridy = 1;
    selector.insets = new Insets(0, 0, 0, 0);
    reasoning.setPreferredSize(new java.awt.Dimension(180, reasoning.getPreferredSize().height));
    selectors.add(reasoning, selector);
    modelFields.add(selectors, BorderLayout.NORTH);
    modelFields.add(reasoningHint, BorderLayout.SOUTH);
    fieldRow.gridy++;
    fields.add(modelFields, fieldRow);
    row.gridy++;
    content.add(fields, row);

    for (JButton button : List.of(connect, add, logout, refresh, cancel, usage, retry)) {
      TeacherSettingsStyle.button(button, button == connect);
    }
    connect.setName("chatGptConnect");
    usage.setName("chatGptUsage");
    retry.setName("chatGptRetry");
    retry.setBorder(BorderFactory.createEmptyBorder(2, 12, 2, 12));
    retry.setVisible(false);
    retry.addActionListener(event -> retryAction.run());
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
    JPanel statusRow = TeacherSettingsStyle.panel(new BorderLayout(8, 0));
    statusRow.add(status, BorderLayout.CENTER);
    statusRow.add(retry, BorderLayout.LINE_END);
    content.add(statusRow, row);
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
    models.addActionListener(
        event -> {
          updateReasoning();
          if (!updatingModels && selected() != null && !selectedModel().isEmpty())
            draftModels.put(selected().id, selectedModel());
        });
    reasoning.addActionListener(
        event -> {
          if (!updatingEffort && selected() != null && !selectedModel().isEmpty())
            draftEfforts.put(selected().id + ":" + selectedModel(), selectedReasoningEffort());
        });
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
    boolean unavailable = account != null && account.credentialsUnavailable;
    welcome.setVisible(!signedIn && accounts.getItemCount() == 0);
    fields.setVisible(accounts.getItemCount() > 0);
    modelFields.setVisible(signedIn && account.authorized);
    actions.setVisible(signedIn);
    planNotice.setVisible(!unavailable && (!signedIn || !account.authorized));
    connect.setVisible(!unavailable && (!signedIn || !account.authorized));
    signInRow.setVisible(connect.isVisible());
    alignWelcomeActions(welcome.isVisible());
    revalidate();
    repaint();
    connect.setEnabled(!signedIn || !account.authorized);
    logout.setEnabled(signedIn);
    refresh.setEnabled(signedIn && account.authorized);
    models.removeAllItems();
    models.setEnabled(signedIn && account.authorized);
    updateReasoning();
    updateStatus();
    retryAction = this::reload;
    retry.setVisible(unavailable);
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
    boolean unavailable = account != null && account.credentialsUnavailable;
    status.setVisible(signedIn || unavailable);
    status.setText(
        unavailable
            ? ChatGptHttp.error("credentialsUnavailable").getMessage()
            : !signedIn
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
            BorderFactory.createEmptyBorder(empty ? 14 : 6, 0, empty ? 26 : 0, 0)));
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
    String chosen = draftModels.getOrDefault(account.id, account.model);
    work(
        () -> new ChatGptCommentaryClient(sessions, account.id, account.model).models(),
        available -> {
          boolean found = chosen.isEmpty();
          updatingModels = true;
          try {
            models.removeAllItems();
            for (ChatGptCommentaryClient.Model model : available) {
              models.addItem(model);
              if (chosen.equals(model.slug)) {
                models.setSelectedItem(model);
                found = true;
              }
            }
            if (!found) models.setSelectedItem(null);
          } finally {
            updatingModels = false;
          }
          if (found) draftModels.put(account.id, selectedModel());
          updateStatus();
          if (!found) status.setText(ChatGptHttp.error("modelRemoved").getMessage());
          firePropertyChange("connectionReady", false, found);
        },
        this::refreshModels);
  }

  String selectedAccountId() {
    return selected() == null ? null : selected().id;
  }

  String selectedModel() {
    ChatGptCommentaryClient.Model model = (ChatGptCommentaryClient.Model) models.getSelectedItem();
    return model == null ? "" : model.slug;
  }

  String selectedReasoningEffort() {
    Effort effort = (Effort) reasoning.getSelectedItem();
    return effort == null ? "" : effort.value;
  }

  private void updateReasoning() {
    updatingEffort = true;
    try {
      reasoning.removeAllItems();
      reasoning.addItem(new Effort(""));
      ChatGptCommentaryClient.Model model =
          (ChatGptCommentaryClient.Model) models.getSelectedItem();
      ChatGptSessions.Account account = selected();
      String saved =
          account == null || model == null
              ? ""
              : draftEfforts.getOrDefault(
                  account.id + ":" + model.slug,
                  account.reasoningByModel.getOrDefault(model.slug, ""));
      if (model != null)
        for (String value : model.reasoningEfforts) {
          Effort effort = new Effort(value);
          reasoning.addItem(effort);
          if (value.equals(saved)) reasoning.setSelectedItem(effort);
        }
      boolean supported = model != null && !model.reasoningEfforts.isEmpty();
      reasoning.setEnabled(models.isEnabled() && supported);
      String hint =
          supported
              ? text(
                  "reasoning.hint",
                  "Deeper thinking may take longer and use more of your plan allowance.")
              : text(
                  "reasoning.unavailable",
                  "This model does not advertise adjustable thinking depth; its default is used.");
      reasoningHint.setText(hint);
      reasoning.getAccessibleContext().setAccessibleDescription(hint);
    } finally {
      updatingEffort = false;
    }
  }

  private record Effort(String value) {
    @Override
    public String toString() {
      return text(
          "reasoning." + (value.isEmpty() ? "default" : value),
          value.isEmpty() ? "Model default" : value);
    }
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
    work(operation, completed, this::reload);
  }

  private <T> void work(
      Callable<T> operation, java.util.function.Consumer<T> completed, Runnable recovery) {
    if (worker != null && !worker.isDone()) worker.cancel(true);
    long request = ++generation;
    connect.setEnabled(false);
    add.setEnabled(false);
    logout.setEnabled(false);
    refresh.setEnabled(false);
    models.setEnabled(false);
    reasoning.setEnabled(false);
    accounts.setEnabled(false);
    usage.setEnabled(false);
    retry.setVisible(false);
    retry.setEnabled(false);
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
            usage.setEnabled(true);
            retry.setEnabled(true);
            ChatGptSessions.Account account = selected();
            connect.setEnabled(account == null || !account.signedIn || !account.authorized);
            logout.setEnabled(account != null && account.signedIn);
            refresh.setEnabled(account != null && account.signedIn && account.authorized);
            models.setEnabled(refresh.isEnabled());
            updateReasoning();
            try {
              completed.accept(get());
            } catch (Exception failure) {
              cancel.setVisible(false);
              Throwable root = failure;
              while (root.getCause() != null) root = root.getCause();
              if (root instanceof ChatGptApiException api && api.refreshRevoked()) {
                reload();
                return;
              }
              boolean authorize =
                  root instanceof ChatGptApiException api
                      && (api.status == 401 || api.status == 403);
              boolean canSignIn =
                  account == null
                      || (!account.signedIn && !account.credentialsUnavailable)
                      || authorize;
              connect.setEnabled(canSignIn);
              connect.setVisible(canSignIn);
              signInRow.setVisible(canSignIn);
              retryAction = recovery;
              retry.setVisible(!canSignIn);
              // Network/library exceptions may include URLs. Only display known localized errors.
              String message = root.getMessage();
              status.setText(
                  ChatGptText.isKnownError(message)
                      ? message
                      : text(
                          "error.network",
                          "Could not connect to ChatGPT. Check your network and retry."));
              revalidate();
              repaint();
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
