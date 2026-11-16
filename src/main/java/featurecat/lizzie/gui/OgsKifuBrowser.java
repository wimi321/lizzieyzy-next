package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.util.OgsGameClient;
import featurecat.lizzie.util.OgsGameClient.Game;
import featurecat.lizzie.util.OgsGameClient.GamePage;
import featurecat.lizzie.util.OgsGameClient.Player;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** EDT-owned game browser. Requests publish only while their window is still current. */
final class OgsKifuBrowser extends JPanel {
  private static final Logger LOG = LoggerFactory.getLogger(OgsKifuBrowser.class);

  @FunctionalInterface
  interface Loader {
    void load(String sgf, Consumer<Boolean> completion);
  }

  private final ResourceBundle strings;
  private final OgsGameClient client;
  private final Loader loader;
  private final Consumer<Integer> afterOpen;
  private final int refreshDelay;
  final JTextField query = new JFontTextField();
  final JButton search;
  final JButton open;
  final JButton first = new JFontButton("|<");
  final JButton previous = new JFontButton("<");
  final JButton next = new JFontButton(">");
  final JButton last = new JFontButton(">|");
  final JTable table;
  final JComboBox<String> after;
  final JComboBox<String> recent = new JFontComboBox<>();
  final JLabel status = new JFontLabel();
  private final JLabel playerLabel = new JFontLabel();
  private final JLabel pageLabel = new JFontLabel("1/1");
  private final JProgressBar progress = new JProgressBar();
  private final DefaultTableModel model;
  private List<Game> games = List.of();
  private Player player;
  private int page = 1;
  private int pages = 1;
  private boolean busy;
  private long generation;
  private SwingWorker<?, Void> worker;

  OgsKifuBrowser(
      ResourceBundle strings, OgsGameClient client, Loader loader, Consumer<Integer> afterOpen) {
    this(strings, client, loader, afterOpen, 120);
  }

  OgsKifuBrowser(
      ResourceBundle strings,
      OgsGameClient client,
      Loader loader,
      Consumer<Integer> afterOpen,
      int refreshDelay) {
    super(new BorderLayout(8, 8));
    this.strings = strings;
    this.client = client;
    this.loader = loader;
    this.afterOpen = afterOpen;
    this.refreshDelay = refreshDelay;
    setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8));
    JPanel north = new JPanel(new BorderLayout(6, 8));
    JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
    JLabel queryLabel = new JFontLabel(text("OgsKifuDownload.player"));
    AccessibilitySupport.labelFor(queryLabel, query, text("OgsKifuDownload.player"));
    search = new JFontButton(text("TencentKifuDownload.btnSearch"));
    search.addActionListener(e -> search());
    query.addActionListener(e -> search());
    query.setColumns(18);
    searchRow.add(queryLabel);
    searchRow.add(query);
    searchRow.add(search);
    north.add(searchRow, BorderLayout.NORTH);
    JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT));
    JLabel recentLabel = new JFontLabel(text("TencentKifuDownload.recentSearches"));
    AccessibilitySupport.labelFor(recentLabel, recent, recentLabel.getText());
    recent.setPrototypeDisplayValue("abcdefghijklmnopqrst");
    recent.addActionListener(
        e -> {
          if (!busy && recent.getSelectedItem() != null) {
            query.setText(recent.getSelectedItem().toString());
            search();
          }
        });
    options.add(recentLabel);
    options.add(recent);
    JButton clear = new JFontButton(text("TencentKifuDownload.clearRecent"));
    clear.addActionListener(e -> recent.removeAllItems());
    options.add(clear);
    JLabel afterLabel = new JFontLabel(text("TencentKifuDownload.lblAfterGet"));
    after = new JFontComboBox<>();
    for (String key : List.of("min", "close", "none")) {
      after.addItem(text("TencentKifuDownload.cbxAfterGet." + key));
    }
    AccessibilitySupport.labelFor(afterLabel, after, afterLabel.getText());
    JPanel afterRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
    afterRow.add(afterLabel);
    afterRow.add(after);
    JPanel optionRows = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
    optionRows.add(options);
    optionRows.add(afterRow);
    north.add(optionRows, BorderLayout.CENTER);
    playerLabel.setText(text("OgsKifuDownload.hint"));
    north.add(playerLabel, BorderLayout.SOUTH);
    add(north, BorderLayout.NORTH);

    model =
        new DefaultTableModel(
            new String[] {
              text("TencentKifuDownload.column.time"), text("TencentKifuDownload.column.black"),
              text("TencentKifuDownload.column.white"), text("TencentKifuDownload.column.result"),
              text("OgsKifuDownload.boardSize")
            },
            0) {
          @Override
          public boolean isCellEditable(int row, int col) {
            return false;
          }
        };
    table = new JTable(model);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setFillsViewportHeight(true);
    Font font = new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize);
    table.setFont(font);
    table.getTableHeader().setFont(font);
    table.setRowHeight(Math.max(24, Config.menuHeight));
    table.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2 && table.rowAtPoint(e.getPoint()) >= 0) openSelected();
          }
        });
    table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "ogs-open");
    table
        .getActionMap()
        .put(
            "ogs-open",
            new javax.swing.AbstractAction() {
              @Override
              public void actionPerformed(java.awt.event.ActionEvent e) {
                openSelected();
              }
            });
    table.getSelectionModel().addListSelectionListener(e -> updateControls());
    AccessibilitySupport.named(table, text("Menu.ogsKifu"), text("Menu.ogsKifu"));
    JScrollPane scroll = new JScrollPane(table);
    scroll.setColumnHeaderView(table.getTableHeader());
    add(scroll, BorderLayout.CENTER);

    JPanel south = new JPanel(new BorderLayout(6, 6));
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER));
    first.addActionListener(e -> fetchPage(1));
    previous.addActionListener(e -> fetchPage(page - 1));
    next.addActionListener(e -> fetchPage(page + 1));
    last.addActionListener(e -> fetchPage(pages));
    for (JButton button : List.of(first, previous, next, last)) buttons.add(button);
    buttons.add(pageLabel);
    open = new JFontButton(text("TencentKifuDownload.column.open"));
    open.addActionListener(e -> openSelected());
    buttons.add(open);
    south.add(buttons, BorderLayout.NORTH);
    JPanel feedback = new JPanel(new BorderLayout(8, 0));
    feedback.add(status, BorderLayout.CENTER);
    progress.setIndeterminate(true);
    progress.setVisible(false);
    AccessibilitySupport.progress(
        progress,
        text("Accessibility.kifuLoadProgress"),
        text("Accessibility.kifuLoadProgressDescription"));
    feedback.add(progress, BorderLayout.EAST);
    south.add(feedback, BorderLayout.SOUTH);
    add(south, BorderLayout.SOUTH);
    AccessibilitySupport.applyToTree(this);
    updateControls();
  }

  private String text(String key) {
    return strings.getString(key);
  }

  private record SearchResult(Player player, GamePage page) {}

  void search() {
    if (busy) return;
    String input = query.getText().trim();
    if (input.isEmpty()) {
      status.setText(text("OgsKifuDownload.player"));
      return;
    }
    // Do not leave another player's rows selectable after a failed new search.
    player = null;
    games = List.of();
    model.setRowCount(0);
    page = pages = 1;
    pageLabel.setText("1/1");
    playerLabel.setText(input);
    request(
        () -> {
          Player found = client.findPlayer(input);
          return new SearchResult(found, found == null ? null : client.games(found, 1));
        },
        result -> {
          if (result.player() == null) {
            status.setText(text("OgsKifuDownload.noPlayer"));
          } else {
            player = result.player();
            playerLabel.setText(player.name() + " (" + player.id() + ")");
            // Populate while busy so combo events cannot trigger a second request.
            List<String> history = new ArrayList<>();
            history.add(input);
            for (int i = 0; i < recent.getItemCount() && history.size() < 8; i++) {
              String old = recent.getItemAt(i);
              if (!old.equals(input)) history.add(old);
            }
            recent.removeAllItems();
            for (String item : history) recent.addItem(item);
            showPage(result.page());
          }
          setBusy(false);
        },
        "OgsKifuDownload.failed");
  }

  private void fetchPage(int target) {
    if (busy || player == null || target < 1 || target > pages || target == page) return;
    Player requestedPlayer = player;
    request(
        () -> client.games(requestedPlayer, target),
        result -> {
          showPage(result);
          setBusy(false);
        },
        "OgsKifuDownload.failed");
  }

  private void showPage(GamePage result) {
    games = result.games();
    page = result.page();
    pages = result.pages();
    model.setRowCount(0);
    for (Game game : games) {
      model.addRow(
          new Object[] {
            displayDate(game.ended()), game.black(), game.white(), game.result(), game.size()
          });
    }
    pageLabel.setText(page + "/" + pages);
    status.setText(games.isEmpty() ? text("OgsKifuDownload.empty") : "");
    if (!games.isEmpty()) table.setRowSelectionInterval(0, 0);
  }

  private String displayDate(String value) {
    try {
      return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
          .withLocale(strings.getLocale())
          .withZone(ZoneId.systemDefault())
          .format(Instant.parse(value));
    } catch (RuntimeException e) {
      return value;
    }
  }

  void openSelected() {
    int row = table.getSelectedRow();
    if (busy || row < 0) return;
    Game selected = games.get(table.convertRowIndexToModel(row));
    request(
        () -> client.sgf(selected.id()),
        sgf -> {
          status.setText(text("KifuLoad.parsing"));
          long active = generation;
          loader.load(
              sgf,
              loaded -> {
                if (active != generation) return;
                if (loaded) {
                  status.setText(text("KifuLoad.refreshing"));
                  KifuLoadFinisher.finishAfterRefresh(
                      this::repaint,
                      () -> {
                        if (active != generation) return;
                        setBusy(false);
                        status.setText("");
                        afterOpen.accept(after.getSelectedIndex());
                      },
                      refreshDelay,
                      error -> LOG.warn("OGS browser refresh failed", error));
                } else {
                  setBusy(false);
                  status.setText(text("OgsKifuImport.failed"));
                }
              });
        },
        "OgsKifuImport.failed");
  }

  private <T> void request(Callable<T> call, Consumer<T> result, String errorKey) {
    setBusy(true);
    status.setText(text("KifuLoad.sharedDownloading"));
    long active = ++generation;
    worker =
        OgsKifuImport.download(
            call,
            value -> {
              if (active == generation) result.accept(value);
            },
            error -> {
              if (active != generation) return;
              LOG.warn("OGS browser request failed", error);
              setBusy(false);
              status.setText(text(errorKey));
            });
  }

  void cancelPending() {
    generation++;
    if (worker != null) worker.cancel(true);
    setBusy(false);
  }

  private void setBusy(boolean value) {
    busy = value;
    progress.setVisible(value);
    updateControls();
  }

  private void updateControls() {
    query.setEnabled(!busy);
    search.setEnabled(!busy);
    recent.setEnabled(!busy);
    table.setEnabled(!busy);
    after.setEnabled(!busy);
    if (open != null) open.setEnabled(!busy && table.getSelectedRow() >= 0);
    first.setEnabled(!busy && player != null && page > 1);
    previous.setEnabled(first.isEnabled());
    next.setEnabled(!busy && player != null && page < pages);
    last.setEnabled(next.isEnabled());
  }
}
