package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.GetTencentRequest;
import featurecat.lizzie.util.Utils;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.AbstractCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class TencentKifuDownload extends JFrame {
  private static final int NUMBERS_PER_TAB = 25;
  private static final int API_FETCH_NUM = 100;

  private DefaultTableModel model;
  private JTable table;
  private JScrollPane scrollPane;
  private JTextField txtUserName;
  private JLabel lblCurrentUser;
  private JPanel recentSearchesPanel;
  private GetTencentRequest tencentReq;
  private List<TencentKifuInfo> tencentKifuInfos;
  private final List<RecentTencentSearch> recentSearches = new ArrayList<RecentTencentSearch>();
  private String currentQuery = "";
  private String currentUid = "";
  private String lastCode = "";
  private int tabNumber = 1;
  private int curTabNumber = 1;
  private boolean isComplete = false;
  private boolean isSearching = false;
  private boolean isKifuLoading = false;
  private boolean advanceToNextPageAfterLoad = false;
  private boolean isSecondTimeReqEmpty = false;
  private boolean isRequestEmpty = false;
  private int afterGetAction;
  private JLabel lblTab;
  private ArrayList<String[]> rows;
  private JPanel progressGlassPane;
  private JLabel progressMessageLabel;
  private JProgressBar progressBar;

  public TencentKifuDownload() {
    Lizzie.setFrameSize(this, 980, 680);
    setTitle(text("TencentKifuDownload.title"));
    try {
      this.setIconImage(ImageIO.read(MoreEngines.class.getResourceAsStream("/assets/logo.png")));
    } catch (IOException e) {
      e.printStackTrace();
    }
    setAlwaysOnTop(Lizzie.frame.isAlwaysOnTop());
    setLocationRelativeTo(Lizzie.frame);

    tencentKifuInfos = new ArrayList<TencentKifuInfo>();
    afterGetAction = Lizzie.config.uiConfig.optInt("tencent-after-get", Lizzie.config.foxAfterGet);
    loadRecentSearches();
    buildUi();
  }

  private void buildUi() {
    JPanel northPanel = new JPanel(new BorderLayout(0, 8));
    northPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 4, 8));
    getContentPane().add(northPanel, BorderLayout.NORTH);

    JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    northPanel.add(searchPanel, BorderLayout.NORTH);

    searchPanel.add(new JFontLabel(text("TencentKifuDownload.lblUserName")));

    txtUserName = new JFontTextField();
    txtUserName.setColumns(12);
    txtUserName.setText(Lizzie.config.uiConfig.optString("last-tencent-name", ""));
    txtUserName.addKeyListener(
        new KeyAdapter() {
          public void keyPressed(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_ENTER) {
              getTencentKifus();
            }
          }
        });
    searchPanel.add(txtUserName);

    JButton btnSearch = new JFontButton(text("TencentKifuDownload.btnSearch"));
    btnSearch.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            getTencentKifus();
          }
        });
    searchPanel.add(btnSearch);

    JLabel lblHint = new JFontLabel(text("TencentKifuDownload.uidOnlyHint"));
    lblHint.setForeground(Color.GRAY);
    searchPanel.add(lblHint);

    searchPanel.add(new JFontLabel(text("TencentKifuDownload.lblAfterGet")));

    JComboBox<String> cbxAfterGet = new JFontComboBox<String>();
    cbxAfterGet.addItem(text("TencentKifuDownload.cbxAfterGet.min"));
    cbxAfterGet.addItem(text("TencentKifuDownload.cbxAfterGet.close"));
    cbxAfterGet.addItem(text("TencentKifuDownload.cbxAfterGet.none"));
    cbxAfterGet.setSelectedIndex(Math.max(0, Math.min(2, afterGetAction)));
    cbxAfterGet.addItemListener(
        new ItemListener() {
          public void itemStateChanged(final ItemEvent e) {
            if (e.getStateChange() != ItemEvent.SELECTED) {
              return;
            }
            afterGetAction = cbxAfterGet.getSelectedIndex();
            Lizzie.config.uiConfig.put("tencent-after-get", afterGetAction);
            saveConfigQuietly();
          }
        });
    searchPanel.add(cbxAfterGet);

    JPanel infoPanel = new JPanel(new BorderLayout(0, 6));
    northPanel.add(infoPanel, BorderLayout.CENTER);

    lblCurrentUser = new JFontLabel();
    infoPanel.add(lblCurrentUser, BorderLayout.NORTH);

    JPanel recentWrapper = new JPanel(new BorderLayout(6, 0));
    infoPanel.add(recentWrapper, BorderLayout.CENTER);

    recentWrapper.add(
        new JFontLabel(text("TencentKifuDownload.recentSearches")), BorderLayout.WEST);
    recentSearchesPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    recentWrapper.add(recentSearchesPanel, BorderLayout.CENTER);

    JButton btnClearRecent = new JFontButton(text("TencentKifuDownload.clearRecent"));
    btnClearRecent.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            clearRecentSearches();
          }
        });
    recentWrapper.add(btnClearRecent, BorderLayout.EAST);

    updateCurrentUserLabel(txtUserName.getText().trim(), null);
    updateRecentSearchesPanel();

    table =
        new JTable() {
          public boolean isCellEditable(int row, int column) {
            return column == 8;
          }
        };
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setFillsViewportHeight(true);
    ((DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer())
        .setHorizontalAlignment(JLabel.CENTER);
    table
        .getTableHeader()
        .setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    table.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    table.setRowHeight(Config.menuHeight);
    table.addMouseListener(
        new MouseAdapter() {
          public void mouseClicked(MouseEvent e) {
            int row = table.rowAtPoint(e.getPoint());
            int col = table.columnAtPoint(e.getPoint());
            if (e.getClickCount() == 2 && row >= 0 && col >= 0 && tencentReq != null) {
              loadTencentKifu(table.getValueAt(row, 10).toString());
            }
          }
        });
    table.setDefaultRenderer(Object.class, new TencentKifuCellRenderer());
    scrollPane = new JScrollPane(table);
    getContentPane().add(scrollPane, BorderLayout.CENTER);

    JPanel buttonPane = new JPanel();
    getContentPane().add(buttonPane, BorderLayout.SOUTH);
    addPagingButtons(buttonPane);
  }

  private void addPagingButtons(JPanel buttonPane) {
    JButton btnFirst = new JFontButton("|<");
    btnFirst.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            if (rows == null) return;
            curTabNumber = 1;
            reloadCurrentPage();
            setLblTab(1);
            isSecondTimeReqEmpty = false;
          }
        });
    buttonPane.add(btnFirst);

    JButton btnPrevious = new JFontButton("<");
    btnPrevious.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            if (rows == null || curTabNumber == 1) return;
            curTabNumber = curTabNumber - 1;
            reloadCurrentPage();
            setLblTab(curTabNumber);
            isSecondTimeReqEmpty = false;
          }
        });
    buttonPane.add(btnPrevious);

    JButton btnNext = new JFontButton(">");
    btnNext.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            if (rows == null) return;
            if (curTabNumber == tabNumber) {
              maybeGetNextPage();
              return;
            }
            curTabNumber = curTabNumber + 1;
            reloadCurrentPage();
            setLblTab(curTabNumber);
            maybeGetNextPage();
          }
        });
    buttonPane.add(btnNext);

    JButton btnLast = new JFontButton(">|");
    btnLast.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            if (rows == null) return;
            if (curTabNumber == tabNumber) {
              maybeGetNextPage();
              return;
            }
            curTabNumber = tabNumber;
            reloadCurrentPage();
            setLblTab(curTabNumber);
            maybeGetNextPage();
          }
        });
    buttonPane.add(btnLast);

    lblTab = new JFontLabel("1/1");
    lblTab.setPreferredSize(new Dimension(Config.menuHeight * 3, Config.menuHeight));
    buttonPane.add(lblTab);
  }

  private void getTencentKifus() {
    triggerTencentSearch(txtUserName.getText().trim());
  }

  private void triggerTencentSearch(String userText) {
    if (userText == null || userText.trim().isEmpty()) {
      Utils.showMsg(text("TencentKifuDownload.noUser"), this);
      return;
    }
    String normalizedUser = userText.trim();
    if (isSearching) {
      Utils.showMsg(text("TencentKifuDownload.waitLastSearch"), this);
      return;
    }
    if (isKifuLoading) {
      Utils.showMsg(
          Lizzie.frame.kifuLoadText(
              "KifuLoad.wait",
              "请等待当前棋谱加载完成。",
              "Please wait for the current game record to finish loading."),
          this);
      return;
    }
    currentQuery = normalizedUser;
    currentUid = normalizedUser.matches("\\d+") ? normalizedUser : "";
    isSearching = true;
    isComplete = false;
    isSecondTimeReqEmpty = false;
    isRequestEmpty = false;
    advanceToNextPageAfterLoad = false;
    shutdownTencentRequest();
    tencentReq = new GetTencentRequest(this);
    tencentKifuInfos = new ArrayList<TencentKifuInfo>();
    rows = null;
    model = null;
    tabNumber = 1;
    curTabNumber = 1;
    lastCode = "";
    txtUserName.setText(normalizedUser);
    updateCurrentUserLabel(normalizedUser, currentUid);
    Lizzie.config.uiConfig.put("last-tencent-name", normalizedUser);
    saveConfigQuietly();
    showProgressNotice(String.format(text("TencentKifuDownload.searching"), normalizedUser));
    tencentReq.search(normalizedUser);
  }

  public void loadTencentKifu(String chessId) {
    if (tencentReq == null || chessId == null || chessId.trim().isEmpty() || isKifuLoading) {
      return;
    }
    isKifuLoading = true;
    setTableBusy(true);
    showProgressNotice(
        Lizzie.frame.kifuLoadText(
            "KifuLoad.tencentDownloading", "正在下载棋谱…", "Downloading game record..."));
    tencentReq.fetchKifu(chessId.trim());
  }

  public void receiveResult(String string) {
    SwingUtilities.invokeLater(
        new Runnable() {
          public void run() {
            handleResult(string);
          }
        });
  }

  private void handleResult(String string) {
    try {
      JSONObject jsonObject = new JSONObject(string);
      if (!isSuccess(jsonObject)) {
        handleRequestFailure(jsonObject.optString("resultstr", string));
        return;
      }
      if (jsonObject.has("chesslist")) {
        handleChessList(jsonObject.getJSONArray("chesslist"));
      }
      if (jsonObject.has("chess")) {
        String kifu = jsonObject.getString("chess");
        showProgressNotice(
            Lizzie.frame.kifuLoadText("KifuLoad.parsing", "正在解析棋谱…", "Parsing game record..."));
        boolean loaded = Lizzie.frame.loadDownloadedSgfString(kifu, 0, false, false, null);
        if (loaded) {
          finishTencentKifuLoadAfterMainPaint();
        } else {
          isKifuLoading = false;
          setTableBusy(false);
          hideProgressNotice();
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
      hideProgressNotice();
      if (isKifuLoading) {
        isKifuLoading = false;
        setTableBusy(false);
      }
      isSearching = false;
      Utils.showMsg(text("TencentKifuDownload.getKifuFailed") + string, this);
    }
  }

  private boolean isSuccess(JSONObject jsonObject) {
    int result = jsonObject.has("result") ? jsonObject.optInt("result", -1) : 0;
    int ret = jsonObject.has("ret") ? jsonObject.optInt("ret", -1) : 0;
    return result == 0 && ret == 0;
  }

  private void handleRequestFailure(String message) {
    isSearching = false;
    if (isKifuLoading) {
      isKifuLoading = false;
      setTableBusy(false);
    }
    hideProgressNotice();
    Utils.showMsg(text("TencentKifuDownload.getKifuFailed") + message, this);
  }

  private void handleChessList(JSONArray jsonArray) throws JSONException {
    isSearching = false;
    hideProgressNotice();
    int oldRows = tencentKifuInfos.size();
    int previousTabNumber = curTabNumber;
    boolean shouldAdvancePage = advanceToNextPageAfterLoad;
    advanceToNextPageAfterLoad = false;
    if (jsonArray.length() == 0) {
      if (oldRows > 0) {
        isComplete = true;
        isSecondTimeReqEmpty = true;
        isRequestEmpty = true;
      } else {
        Utils.showMsg(text("TencentKifuDownload.noKifu"), this);
      }
      return;
    }

    isRequestEmpty = false;
    isComplete = jsonArray.length() < API_FETCH_NUM;
    for (int i = 0; i < jsonArray.length(); i++) {
      JSONObject jsonObject = jsonArray.getJSONObject(i);
      TencentKifuInfo info = new TencentKifuInfo();
      info.index = tencentKifuInfos.size() + 1;
      info.playTime = resolvePlayTime(jsonObject);
      info.blackUid = resolveUid(jsonObject, "blackuid");
      info.whiteUid = resolveUid(jsonObject, "whiteuid");
      info.blackName = resolveName(jsonObject, "blacknick", "blackenname", "blackname");
      info.whiteName = resolveName(jsonObject, "whitenick", "whiteenname", "whitename");
      info.blackRank = formatRank(jsonObject.optInt("blackdan", 0));
      info.whiteRank = formatRank(jsonObject.optInt("whitedan", 0));
      info.chessId = jsonObject.optString("chessid", "").trim();
      info.totalMoves = jsonObject.optInt("movenum", 0);
      info.isWin = isCurrentUserWin(jsonObject);
      info.result = buildResultText(jsonObject);
      if (!info.chessId.isEmpty()) {
        tencentKifuInfos.add(info);
      }
    }

    updateCurrentUserLabel(currentQuery, currentUid);
    addRecentSearch(currentQuery, currentUid);
    rebuildRows();
    ensureTableModel();
    if (oldRows <= 0 || model == null) {
      curTabNumber = 1;
    } else if (shouldAdvancePage && previousTabNumber < tabNumber) {
      curTabNumber = previousTabNumber + 1;
    } else {
      curTabNumber = Math.min(previousTabNumber, tabNumber);
    }
    reloadCurrentPage();
    setLblTab(curTabNumber);
    if (Lizzie.config.isFrameFontSmall() && rows.size() >= NUMBERS_PER_TAB) {
      scrollPane.setPreferredSize(
          new Dimension(
              scrollPane.getWidth(),
              table.getTableHeader().getHeight() + Config.menuHeight * NUMBERS_PER_TAB + 2));
      pack();
    }
  }

  private void rebuildRows() {
    rows = new ArrayList<String[]>();
    for (int i = 0; i < tencentKifuInfos.size(); i++) {
      TencentKifuInfo info = tencentKifuInfos.get(i);
      String[] rowParams = {
        String.valueOf(info.index),
        info.playTime,
        formatTencentUser(info.blackName, info.blackUid),
        info.blackRank,
        formatTencentUser(info.whiteName, info.whiteUid),
        info.whiteRank,
        info.result,
        String.valueOf(info.totalMoves),
        "",
        String.valueOf(info.isWin),
        info.chessId
      };
      rows.add(rowParams);
    }
    tabNumber = Math.max(1, (int) Math.ceil(rows.size() / (double) NUMBERS_PER_TAB));
  }

  private void ensureTableModel() {
    if (model != null) {
      return;
    }
    model = new DefaultTableModel();
    model.addColumn(text("TencentKifuDownload.column.index"));
    model.addColumn(text("TencentKifuDownload.column.time"));
    model.addColumn(text("TencentKifuDownload.column.black"));
    model.addColumn(text("TencentKifuDownload.column.rank"));
    model.addColumn(text("TencentKifuDownload.column.white"));
    model.addColumn(text("TencentKifuDownload.column.rank"));
    model.addColumn(text("TencentKifuDownload.column.result"));
    model.addColumn(text("TencentKifuDownload.column.moves"));
    model.addColumn(text("TencentKifuDownload.column.open"));
    model.addColumn("");
    model.addColumn("");
    table.setModel(model);
    table.getColumnModel().getColumn(0).setPreferredWidth(40);
    table.getColumnModel().getColumn(1).setPreferredWidth(180);
    table.getColumnModel().getColumn(2).setPreferredWidth(160);
    table.getColumnModel().getColumn(3).setPreferredWidth(50);
    table.getColumnModel().getColumn(4).setPreferredWidth(160);
    table.getColumnModel().getColumn(5).setPreferredWidth(50);
    table.getColumnModel().getColumn(6).setPreferredWidth(95);
    table.getColumnModel().getColumn(7).setPreferredWidth(55);
    table.getColumnModel().getColumn(8).setPreferredWidth(45);
    table.getColumnModel().getColumn(8).setCellEditor(new TencentKifuButtonEditor());
    table.getColumnModel().getColumn(8).setCellRenderer(new TencentKifuButtonRenderer());
    hideColumn(9);
    hideColumn(10);
    table.revalidate();
  }

  private void reloadCurrentPage() {
    if (model == null || rows == null) return;
    while (model.getRowCount() > 0) {
      model.removeRow(model.getRowCount() - 1);
    }
    for (int i = (curTabNumber - 1) * NUMBERS_PER_TAB;
        i < curTabNumber * NUMBERS_PER_TAB && i < rows.size();
        i++) {
      model.addRow(rows.get(i));
    }
  }

  private void maybeGetNextPage() {
    if (tencentReq == null || tencentKifuInfos.isEmpty() || currentQuery.trim().isEmpty()) return;
    if (curTabNumber == tabNumber || tabNumber >= 4 && curTabNumber == tabNumber - 1) {
      String last = tencentKifuInfos.get(tencentKifuInfos.size() - 1).chessId;
      if (!lastCode.equals(last)) {
        lastCode = last;
        advanceToNextPageAfterLoad = curTabNumber == tabNumber;
        showProgressNotice(text("TencentKifuDownload.loadingMore"));
        tencentReq.fetchNextPage(currentQuery, lastCode);
      } else if (curTabNumber == tabNumber) {
        if (isSecondTimeReqEmpty) {
          Utils.showMsg(text("TencentKifuDownload.noMoreKifu"), this);
        }
        if (isRequestEmpty) {
          isSecondTimeReqEmpty = true;
        }
      }
    }
  }

  private void finishTencentKifuLoadAfterMainPaint() {
    showProgressNotice(
        Lizzie.frame.kifuLoadText(
            "KifuLoad.refreshing", "正在刷新胜率曲线…", "Refreshing winrate graph..."));
    KifuLoadFinisher.finishAfterRefresh(
        new Runnable() {
          public void run() {
            if (Lizzie.frame.mainPanel != null) {
              Lizzie.frame.mainPanel.repaint();
            }
            Lizzie.frame.repaint();
          }
        },
        new Runnable() {
          public void run() {
            completeTencentKifuLoad();
          }
        });
  }

  private void completeTencentKifuLoad() {
    isKifuLoading = false;
    setTableBusy(false);
    hideProgressNotice();
    if (afterGetAction == 0) setExtendedState(JFrame.ICONIFIED);
    else if (afterGetAction == 1) setVisible(false);
    focusMainFrameAfterKifuLoad();
  }

  private void focusMainFrameAfterKifuLoad() {
    Runnable focusMainFrame =
        new Runnable() {
          public void run() {
            Lizzie.frame.setVisible(true);
            Lizzie.frame.toFront();
            Lizzie.frame.requestFocus();
            Lizzie.frame.requestFocusInWindow();
            if (Lizzie.frame.mainPanel != null) {
              Lizzie.frame.mainPanel.requestFocusInWindow();
            }
          }
        };
    SwingUtilities.invokeLater(focusMainFrame);
    javax.swing.Timer delayedFocus =
        new javax.swing.Timer(
            120,
            new ActionListener() {
              public void actionPerformed(ActionEvent e) {
                focusMainFrame.run();
              }
            });
    delayedFocus.setRepeats(false);
    delayedFocus.start();
  }

  private void setTableBusy(boolean busy) {
    if (table != null) {
      table.setEnabled(!busy);
    }
  }

  private void showProgressNotice(String message) {
    Runnable show =
        () -> {
          ensureProgressOverlay();
          progressMessageLabel.setText(message);
          progressBar.setString(message);
          presentWindow();
          progressGlassPane.setVisible(true);
          progressGlassPane.revalidate();
          progressGlassPane.repaint();
          Rectangle bounds = progressGlassPane.getBounds();
          if (bounds.width > 0 && bounds.height > 0) {
            progressGlassPane.paintImmediately(0, 0, bounds.width, bounds.height);
          }
        };
    if (SwingUtilities.isEventDispatchThread()) {
      show.run();
    } else {
      SwingUtilities.invokeLater(show);
    }
  }

  private void hideProgressNotice() {
    SwingUtilities.invokeLater(
        () -> {
          if (progressGlassPane != null) {
            progressGlassPane.setVisible(false);
          }
        });
  }

  private void ensureProgressOverlay() {
    if (progressGlassPane != null) {
      return;
    }
    progressGlassPane = new JPanel(new GridBagLayout());
    progressGlassPane.setOpaque(false);
    progressGlassPane.setFocusable(false);

    JPanel card = new JPanel(new BorderLayout(12, 10));
    card.setBackground(new Color(250, 250, 250));
    card.setBorder(
        javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(new Color(96, 112, 128)),
            javax.swing.BorderFactory.createEmptyBorder(16, 22, 16, 22)));

    progressMessageLabel = new JFontLabel();
    card.add(progressMessageLabel, BorderLayout.CENTER);

    progressBar = new JProgressBar();
    progressBar.setIndeterminate(true);
    progressBar.setStringPainted(true);
    progressBar.setString(text("TencentKifuDownload.processing"));
    progressBar.setPreferredSize(new Dimension(360, 22));
    card.add(progressBar, BorderLayout.SOUTH);

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridx = 0;
    gbc.gridy = 0;
    progressGlassPane.add(card, gbc);
    setGlassPane(progressGlassPane);
  }

  public void presentWindow() {
    int state = getExtendedState();
    if ((state & JFrame.ICONIFIED) != 0) {
      setExtendedState(state & ~JFrame.ICONIFIED);
    }
    if (!isVisible()) {
      setVisible(true);
    }
    boolean restoreAlwaysOnTop = isAlwaysOnTop();
    if (!restoreAlwaysOnTop) {
      setAlwaysOnTop(true);
    }
    toFront();
    repaint();
    requestFocus();
    requestFocusInWindow();
    if (!restoreAlwaysOnTop) {
      setAlwaysOnTop(false);
    }
  }

  private String resolvePlayTime(JSONObject jsonObject) {
    String text = jsonObject.optString("starttime", "").trim();
    if (!text.isEmpty()) {
      return text;
    }
    long seconds = jsonObject.optLong("gamestarttime", 0L);
    if (seconds <= 0L) {
      return "";
    }
    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(seconds * 1000L));
  }

  private String resolveUid(JSONObject jsonObject, String key) {
    String value = jsonObject.optString(key, "").trim();
    if (!value.isEmpty()) {
      return value;
    }
    long uid = jsonObject.optLong(key, 0L);
    return uid > 0L ? String.valueOf(uid) : "";
  }

  private String resolveName(JSONObject jsonObject, String... keys) {
    for (int i = 0; i < keys.length; i++) {
      String value = jsonObject.optString(keys[i], "").trim();
      if (!value.isEmpty()) {
        return value;
      }
    }
    return text("TencentKifuDownload.unknownNick");
  }

  private String formatRank(int rawRank) {
    return formatTencentRank(
        rawRank, text("TencentKifuDownload.rank.dan"), text("TencentKifuDownload.rank.kyu"));
  }

  static String formatTencentRank(int rawRank, String danSuffix, String kyuSuffix) {
    if (rawRank <= 0) {
      return "";
    }
    if (rawRank >= 100) {
      return "P" + (rawRank - 99) + danSuffix;
    }
    int rank = rawRank - 17;
    if (rank > 0) {
      return rank + danSuffix;
    }
    return (Math.abs(rank) + 1) + kyuSuffix;
  }

  private String buildResultText(JSONObject jsonObject) {
    String result = "";
    int winner = jsonObject.optInt("winner", 0);
    int point = jsonObject.optInt("point", 0);
    int rule = jsonObject.optInt("rule", 0);
    if (winner == 1 || winner == 2) {
      result = winner == 1 ? text("TencentKifuDownload.black") : text("TencentKifuDownload.white");
      if (point < 0) {
        if (point == -1) result += text("TencentKifuDownload.winByRes");
        else if (point == -2) result += text("TencentKifuDownload.winByTime");
        else result += text("TencentKifuDownload.win");
      } else {
        String unit =
            rule == 1 ? text("TencentKifuDownload.stones") : text("TencentKifuDownload.points");
        String scoreText = new DecimalFormat("0.##").format(point / 100.0d);
        result +=
            Lizzie.config.isChinese
                ? text("TencentKifuDownload.win") + scoreText + unit
                : " +" + scoreText + unit;
      }
    } else {
      result = text("TencentKifuDownload.other");
    }
    return result;
  }

  private boolean isCurrentUserWin(JSONObject jsonObject) {
    int winner = jsonObject.optInt("winner", 0);
    if (winner != 1 && winner != 2) {
      return false;
    }
    String winnerUid =
        winner == 1 ? resolveUid(jsonObject, "blackuid") : resolveUid(jsonObject, "whiteuid");
    String winnerName =
        winner == 1
            ? resolveName(jsonObject, "blacknick", "blackenname", "blackname")
            : resolveName(jsonObject, "whitenick", "whiteenname", "whitename");
    if (!currentUid.isEmpty()) {
      return currentUid.equals(winnerUid);
    }
    return !currentQuery.isEmpty() && currentQuery.equalsIgnoreCase(winnerName);
  }

  private void updateCurrentUserLabel(String query, String uid) {
    String safeQuery = query == null ? "" : query.trim();
    String safeUid = uid == null ? "" : uid.trim();
    String display;
    if (safeQuery.isEmpty() && safeUid.isEmpty()) {
      display = text("TencentKifuDownload.currentUser.waiting");
    } else if (safeUid.isEmpty() || safeQuery.equals(safeUid)) {
      display = safeQuery;
    } else {
      display = safeQuery + " (" + safeUid + ")";
    }
    lblCurrentUser.setText(text("TencentKifuDownload.currentUser.label") + display);
  }

  private String formatTencentUser(String nickname, String uid) {
    String safeName =
        nickname == null || nickname.trim().isEmpty()
            ? text("TencentKifuDownload.unknownNick")
            : nickname.trim();
    String safeUid = uid == null ? "" : uid.trim();
    if (safeUid.isEmpty() || safeName.equals(safeUid)) return safeName;
    return safeName + " (" + safeUid + ")";
  }

  private void loadRecentSearches() {
    recentSearches.clear();
    JSONArray saved = Lizzie.config.uiConfig.optJSONArray("tencent-recent-searches");
    if (saved == null) return;
    for (int i = 0; i < saved.length(); i++) {
      JSONObject item = saved.optJSONObject(i);
      if (item == null) continue;
      RecentTencentSearch search = new RecentTencentSearch();
      search.uid = item.optString("uid", "").trim();
      search.query = item.optString("query", "").trim();
      if (search.query.isEmpty() && !search.uid.isEmpty()) {
        search.query = search.uid;
      }
      if (search.query.isEmpty() && search.uid.isEmpty()) continue;
      recentSearches.add(search);
    }
  }

  private void saveRecentSearches() {
    JSONArray saved = new JSONArray();
    for (int i = 0; i < recentSearches.size() && i < 8; i++) {
      RecentTencentSearch search = recentSearches.get(i);
      JSONObject item = new JSONObject();
      item.put("uid", search.uid);
      item.put("query", search.query);
      saved.put(item);
    }
    Lizzie.config.uiConfig.put("tencent-recent-searches", saved);
    saveConfigQuietly();
  }

  private void addRecentSearch(String query, String uid) {
    String normalizedQuery = query == null ? "" : query.trim();
    String normalizedUid = uid == null ? "" : uid.trim();
    if (normalizedQuery.isEmpty() && normalizedUid.isEmpty()) return;
    for (int i = recentSearches.size() - 1; i >= 0; i--) {
      RecentTencentSearch existing = recentSearches.get(i);
      if ((!normalizedUid.isEmpty() && normalizedUid.equals(existing.uid))
          || normalizedQuery.equals(existing.query)) {
        recentSearches.remove(i);
      }
    }
    RecentTencentSearch search = new RecentTencentSearch();
    search.uid = normalizedUid;
    search.query = normalizedQuery;
    recentSearches.add(0, search);
    while (recentSearches.size() > 8) {
      recentSearches.remove(recentSearches.size() - 1);
    }
    saveRecentSearches();
    updateRecentSearchesPanel();
  }

  private void clearRecentSearches() {
    if (recentSearches.isEmpty()) return;
    int choice =
        JOptionPane.showConfirmDialog(
            this,
            text("TencentKifuDownload.clearRecentConfirm"),
            text("TencentKifuDownload.clearRecentTitle"),
            JOptionPane.OK_CANCEL_OPTION);
    if (choice != JOptionPane.OK_OPTION) return;
    recentSearches.clear();
    saveRecentSearches();
    updateRecentSearchesPanel();
  }

  private void updateRecentSearchesPanel() {
    recentSearchesPanel.removeAll();
    if (recentSearches.isEmpty()) {
      JLabel empty = new JFontLabel(text("TencentKifuDownload.recentEmpty"));
      empty.setForeground(Color.GRAY);
      recentSearchesPanel.add(empty);
    } else {
      for (int i = 0; i < recentSearches.size(); i++) {
        RecentTencentSearch search = recentSearches.get(i);
        JFontButton button = new JFontButton(formatTencentUser(search.query, search.uid));
        button.setFocusable(false);
        button.setMargin(new Insets(2, 8, 2, 8));
        button.addActionListener(
            new ActionListener() {
              public void actionPerformed(ActionEvent e) {
                String keyword = search.query == null ? "" : search.query.trim();
                if (keyword.isEmpty()) {
                  keyword = search.uid == null ? "" : search.uid.trim();
                }
                txtUserName.setText(keyword);
                triggerTencentSearch(keyword);
              }
            });
        recentSearchesPanel.add(button);
      }
    }
    recentSearchesPanel.revalidate();
    recentSearchesPanel.repaint();
  }

  private void setLblTab(int i) {
    lblTab.setText(i + "/" + Math.max(1, tabNumber) + (isComplete ? "" : "..."));
  }

  private void hideColumn(int i) {
    table.getColumnModel().getColumn(i).setWidth(0);
    table.getColumnModel().getColumn(i).setMaxWidth(0);
    table.getColumnModel().getColumn(i).setMinWidth(0);
    table.getTableHeader().getColumnModel().getColumn(i).setMaxWidth(0);
    table.getTableHeader().getColumnModel().getColumn(i).setMinWidth(0);
  }

  private void saveConfigQuietly() {
    try {
      Lizzie.config.save();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void shutdownTencentRequest() {
    if (tencentReq != null) {
      tencentReq.shutdown();
      tencentReq = null;
    }
  }

  private String text(String key) {
    return Lizzie.resourceBundle.getString(key);
  }

  private class TencentKifuCellRenderer extends DefaultTableCellRenderer {
    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
      setHorizontalAlignment(CENTER);
      setForeground("true".equals(table.getValueAt(row, 9).toString()) ? Color.RED : Color.GRAY);
      return this;
    }
  }

  private class TencentKifuButtonRenderer implements TableCellRenderer {
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JButton button = new JFontButton();

    private TencentKifuButtonRenderer() {
      button.setMargin(new Insets(0, 0, 0, 0));
      panel.add(button, BorderLayout.CENTER);
    }

    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      button.setText(text("TencentKifuDownload.column.open"));
      return panel;
    }
  }

  private class TencentKifuButtonEditor extends AbstractCellEditor implements TableCellEditor {
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JButton button = new JFontButton();
    private String chessId = "";

    private TencentKifuButtonEditor() {
      button.setMargin(new Insets(0, 0, 0, 0));
      button.addActionListener(
          new ActionListener() {
            public void actionPerformed(ActionEvent e) {
              loadTencentKifu(chessId);
            }
          });
      panel.add(button, BorderLayout.CENTER);
    }

    public Component getTableCellEditorComponent(
        JTable table, Object value, boolean isSelected, int row, int column) {
      button.setText(text("TencentKifuDownload.column.open"));
      chessId = table.getValueAt(row, 10).toString();
      return panel;
    }

    public Object getCellEditorValue() {
      return null;
    }
  }

  private static class TencentKifuInfo {
    int index;
    String playTime;
    String blackName;
    String blackUid;
    String blackRank;
    String whiteName;
    String whiteUid;
    String whiteRank;
    String result;
    int totalMoves;
    String chessId;
    boolean isWin;
  }

  private static class RecentTencentSearch {
    String uid;
    String query;
  }
}
