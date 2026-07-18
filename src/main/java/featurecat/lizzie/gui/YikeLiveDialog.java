package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.YikeApiClient.YikeLiveGame;
import featurecat.lizzie.gui.YikeApiClient.YikeLivePage;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

public class YikeLiveDialog extends JFrame {
  private static final long serialVersionUID = 4797528713876680614L;
  private static final int DEFAULT_WIDTH = 920;
  private static final int DEFAULT_HEIGHT = 420;

  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;
  private final YikeApiClient apiClient = new YikeApiClient();
  private final JComboBox<Category> categoryCombo = new JComboBox<Category>();
  private final JTextField filterField = new JTextField(18);
  private final JButton refreshButton = new JButton(text("YikeLiveDialog.refresh", "Refresh"));
  private final JButton moreButton = new JButton(text("YikeLiveDialog.more", "More"));
  private final JButton syncButton = new JButton(text("YikeLiveDialog.sync", "Sync"));
  private final JButton webButton = new JButton(text("YikeLiveDialog.openWeb", "Open web"));
  private final JLabel statusLabel = new JLabel(" ");
  private final LiveTableModel tableModel = new LiveTableModel();
  private final JTable table = new JTable(tableModel);
  private final TableRowSorter<LiveTableModel> sorter =
      new TableRowSorter<LiveTableModel>(tableModel);
  private final List<YikeLiveGame> games = new ArrayList<YikeLiveGame>();

  private int page = 1;
  private long since = 0;
  private SwingWorker<YikeLivePage, Void> worker;
  private String activeSyncUrl = "";

  public YikeLiveDialog(Window owner) {
    super();
    setTitle(text("YikeLiveDialog.title", "Yike Live Center"));
    setAlwaysOnTop(Lizzie.frame.isAlwaysOnTop());
    setDefaultCloseOperation(HIDE_ON_CLOSE);
    try {
      setIconImage(ImageIO.read(MoreEngines.class.getResourceAsStream("/assets/logo.png")));
    } catch (IOException e) {
      e.printStackTrace();
    }

    categoryCombo.addItem(
        new Category("1", text("YikeLiveDialog.category.recommend", "Recommend")));
    categoryCombo.addItem(new Category("0", text("YikeLiveDialog.category.local", "Local")));
    categoryCombo.addItem(new Category("4", text("YikeLiveDialog.category.personal", "Personal")));

    table.setRowSorter(sorter);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setFillsViewportHeight(true);
    table.getTableHeader().setReorderingAllowed(false);
    table.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
              syncSelectedGame();
            }
          }
        });
    table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "syncSelectedYikeLiveGame");
    table
        .getActionMap()
        .put(
            "syncSelectedYikeLiveGame",
            new javax.swing.AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent e) {
                syncSelectedGame();
              }
            });

    JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
    topPanel.setBorder(BorderFactory.createEmptyBorder(4, 6, 0, 6));
    JLabel categoryLabel = new JLabel(text("YikeLiveDialog.category", "Category"));
    topPanel.add(categoryLabel);
    topPanel.add(categoryCombo);
    JLabel filterLabel = new JLabel(text("YikeLiveDialog.filter", "Filter"));
    topPanel.add(filterLabel);
    topPanel.add(filterField);
    topPanel.add(refreshButton);
    topPanel.add(moreButton);
    topPanel.add(syncButton);
    topPanel.add(webButton);

    add(topPanel, BorderLayout.NORTH);
    add(new JScrollPane(table), BorderLayout.CENTER);

    JPanel bottomPanel = new JPanel(new BorderLayout());
    bottomPanel.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 10));
    bottomPanel.add(statusLabel, BorderLayout.CENTER);
    add(bottomPanel, BorderLayout.SOUTH);

    refreshButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            loadGames(true);
          }
        });
    moreButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            loadGames(false);
          }
        });
    syncButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            syncSelectedGame();
          }
        });
    webButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            Lizzie.frame.openYikeLiveWeb();
          }
        });
    categoryCombo.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            loadGames(true);
          }
        });
    filterField
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(DocumentEvent e) {
                updateFilter();
              }

              @Override
              public void removeUpdate(DocumentEvent e) {
                updateFilter();
              }

              @Override
              public void changedUpdate(DocumentEvent e) {
                updateFilter();
              }
            });

    AccessibilitySupport.labelFor(
        categoryLabel, categoryCombo, text("YikeLiveDialog.category", "Category"));
    AccessibilitySupport.labelFor(
        filterLabel, filterField, text("YikeLiveDialog.filter", "Filter"));
    AccessibilitySupport.named(
        table,
        text("YikeLiveDialog.title", "Yike Live Center"),
        text("YikeLiveDialog.title", "Yike Live Center"));
    AccessibilitySupport.applyToTree(getContentPane());
    AccessibilitySupport.installEscapeToClose(getRootPane(), this);

    setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    setLocationRelativeTo(owner);
    fitOnScreen();
  }

  public void showAndActivate() {
    setExtendedState(restoredWindowState(getExtendedState()));
    setVisible(true);
    toFront();
    requestFocus();
  }

  static int restoredWindowState(int state) {
    return state & ~Frame.ICONIFIED;
  }

  public void refreshIfEmpty() {
    if (games.isEmpty()) loadGames(true);
  }

  public void updateSyncStatus(String url, String status) {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(
          new Runnable() {
            @Override
            public void run() {
              updateSyncStatus(url, status);
            }
          });
      return;
    }
    if (!activeSyncUrl.isEmpty() && url != null && !activeSyncUrl.equals(url)) {
      return;
    }
    statusLabel.setText(status);
  }

  private void loadGames(boolean reset) {
    if (worker != null && !worker.isDone()) {
      worker.cancel(true);
    }
    Category category = (Category) categoryCombo.getSelectedItem();
    int targetPage = reset ? 1 : page + 1;
    long targetSince = reset ? 0 : since;
    setLoading(true, text("YikeLiveDialog.loading", "Loading Yike live games..."));

    worker =
        new SwingWorker<YikeLivePage, Void>() {
          @Override
          protected YikeLivePage doInBackground() throws Exception {
            return apiClient.fetchLiveList(category.code, targetPage, targetSince);
          }

          @Override
          protected void done() {
            try {
              YikeLivePage result = get();
              if (reset) games.clear();
              games.addAll(result.getGames());
              tableModel.fireTableDataChanged();
              page = targetPage;
              since = result.getSince();
              setLoading(
                  false,
                  text("YikeLiveDialog.loaded", "Loaded")
                      + " "
                      + games.size()
                      + " "
                      + text("YikeLiveDialog.loadedSuffix", "games"));
            } catch (Exception e) {
              setLoading(
                  false,
                  text("YikeLiveDialog.loadFailed", "Failed to load Yike live games")
                      + ": "
                      + e.getMessage());
            }
          }
        };
    worker.execute();
  }

  private void setLoading(boolean loading, String status) {
    refreshButton.setEnabled(!loading);
    moreButton.setEnabled(!loading);
    syncButton.setEnabled(!loading);
    webButton.setEnabled(!loading);
    categoryCombo.setEnabled(!loading);
    statusLabel.setText(status);
  }

  private void syncSelectedGame() {
    YikeLiveGame game = selectedGame();
    if (game == null) {
      statusLabel.setText(text("YikeLiveDialog.noSelection", "Select a live game first."));
      return;
    }
    activeSyncUrl = game.toRoomUrl();
    statusLabel.setText(text("YikeLiveDialog.syncing", "Syncing") + ": " + game.getGameName());
    Lizzie.frame.syncOnline(activeSyncUrl);
  }

  private YikeLiveGame selectedGame() {
    int row = table.getSelectedRow();
    if (row < 0) return null;
    int modelRow = table.convertRowIndexToModel(row);
    if (modelRow < 0 || modelRow >= games.size()) return null;
    return games.get(modelRow);
  }

  private void updateFilter() {
    String filter = filterField.getText().trim();
    if (filter.isEmpty()) {
      sorter.setRowFilter(null);
    } else {
      sorter.setRowFilter(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(filter)));
    }
  }

  private void fitOnScreen() {
    Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    int width = Math.min(getWidth(), Math.max(1, bounds.width - 20));
    int height = Math.min(getHeight(), Math.max(1, bounds.height - 20));
    if (width != getWidth() || height != getHeight()) {
      setSize(width, height);
    }
    int x = Math.max(bounds.x, Math.min(getX(), bounds.x + bounds.width - getWidth()));
    int y = Math.max(bounds.y, Math.min(getY(), bounds.y + bounds.height - getHeight()));
    setLocation(x, y);
  }

  private String text(String key, String fallback) {
    try {
      return resourceBundle.getString(key);
    } catch (MissingResourceException e) {
      return fallback;
    }
  }

  private static class Category {
    private final String code;
    private final String label;

    private Category(String code, String label) {
      this.code = code;
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  private class LiveTableModel extends DefaultTableModel {
    private static final long serialVersionUID = 7266236050670812701L;
    private final String[] columns = {
      text("YikeLiveDialog.column.status", "Status"),
      text("YikeLiveDialog.column.time", "Time"),
      text("YikeLiveDialog.column.game", "Game"),
      text("YikeLiveDialog.column.black", "Black"),
      text("YikeLiveDialog.column.white", "White"),
      text("YikeLiveDialog.column.hands", "Moves"),
      text("YikeLiveDialog.column.rate", "Winrate"),
      text("YikeLiveDialog.column.attention", "Views")
    };

    @Override
    public int getRowCount() {
      return games == null ? 0 : games.size();
    }

    @Override
    public int getColumnCount() {
      return columns.length;
    }

    @Override
    public String getColumnName(int column) {
      return columns[column];
    }

    @Override
    public Object getValueAt(int row, int column) {
      YikeLiveGame game = games.get(row);
      switch (column) {
        case 0:
          return (game.isTopFlag() ? "*" : "") + game.statusText();
        case 1:
          return game.timeText();
        case 2:
          return game.getGameName();
        case 3:
          return game.playerText(true);
        case 4:
          return game.playerText(false);
        case 5:
          return game.getHandsCount();
        case 6:
          return game.winrateText();
        case 7:
          return game.attentionText();
        default:
          return "";
      }
    }

    @Override
    public boolean isCellEditable(int row, int column) {
      return false;
    }
  }
}
