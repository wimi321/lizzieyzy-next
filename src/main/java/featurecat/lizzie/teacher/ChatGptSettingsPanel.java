package featurecat.lizzie.teacher;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
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
  private boolean updating;
  private long generation;
  private ChatGptSignIn attempt;
  private SwingWorker<?, ?> worker;

  ChatGptSettingsPanel(ChatGptSessions sessions) {
    super(new BorderLayout(0, 8));
    this.sessions = sessions;
    status.getAccessibleContext().setAccessibleName(text("tab", "ChatGPT login"));
    setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
    JPanel fields = new JPanel(new GridBagLayout());
    GridBagConstraints row = new GridBagConstraints();
    row.gridx = 0;
    row.gridy = 0;
    row.weightx = 1;
    row.fill = GridBagConstraints.HORIZONTAL;
    row.insets = new Insets(0, 0, 6, 0);
    JLabel accountLabel = new JLabel(text("account", "ChatGPT account"));
    accountLabel.setLabelFor(accounts);
    fields.add(accountLabel, row);
    row.gridy++;
    fields.add(accounts, row);
    JPanel actions = new JPanel(new GridLayout(2, 2, 6, 6));
    actions.add(connect);
    actions.add(add);
    actions.add(logout);
    actions.add(cancel);
    row.gridy++;
    fields.add(actions, row);
    JLabel modelLabel = new JLabel(text("model", "ChatGPT model"));
    modelLabel.setLabelFor(models);
    row.gridy++;
    fields.add(modelLabel, row);
    JPanel modelRow = new JPanel(new BorderLayout(8, 0));
    modelRow.add(models, BorderLayout.CENTER);
    modelRow.add(refresh, BorderLayout.LINE_END);
    row.gridy++;
    fields.add(modelRow, row);
    add(fields, BorderLayout.NORTH);
    JPanel notes = new JPanel(new BorderLayout(0, 6));
    notes.add(
        note(
            text(
                "notice",
                "Eligible ChatGPT plans only. Commentary shares your plan usage; it is not unlimited. Only the selected analysis and question are sent.")),
        BorderLayout.NORTH);
    notes.add(status, BorderLayout.CENTER);
    JButton usage = button("usage", "Manage usage");
    usage.addActionListener(
        event ->
            work(
                () -> {
                  browse(URI.create("https://chatgpt.com/settings/usage"));
                  return null;
                },
                ignored -> updateStatus()));
    notes.add(usage, BorderLayout.SOUTH);
    add(notes, BorderLayout.CENTER);
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

  private void signIn(String id) {
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
    JTextArea area = new JTextArea(text, 3, 35);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setEditable(false);
    area.setOpaque(false);
    area.setFont(javax.swing.UIManager.getFont("Label.font"));
    area.setForeground(javax.swing.UIManager.getColor("Label.foreground"));
    area.getAccessibleContext().setAccessibleName(text);
    ((javax.swing.text.DefaultCaret) area.getCaret())
        .setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
    return area;
  }

  private record Loaded(List<ChatGptSessions.Account> accounts, ChatGptSessions.Account active) {}
}
