package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.GetFoxRequest;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.util.Utils;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
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
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.AbstractCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
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

public class FoxKifuDownload extends JFrame {
  private DefaultTableModel model;
  private JTable table;
  private JScrollPane scrollPane;
  private JTextField txtUserName;
  private JLabel lblCurrentUser;
  private JPanel recentSearchesPanel;
  public GetFoxRequest foxReq;
  private List<KifuInfo> foxKifuInfos;
  private final List<RecentFoxSearch> recentSearches = new ArrayList<RecentFoxSearch>();
  private String myUid = "";
  private String currentFoxNickname = "";
  private String lastCode = "";
  private int tabNumber = 1;
  private final int numbersPerTab = 25;
  private int curTabNumber = 1;
  private boolean isComplete = false;
  private boolean isSearching = false;
  private boolean advanceToNextPageAfterLoad = false;
  JLabel lblTab;
  private ArrayList<String[]> rows;
  private boolean isSecondTimeReqEmpty = false;
  private boolean isRequestEmpty = false;

  public FoxKifuDownload() {
    Lizzie.setFrameSize(this, 980, 680);
    setTitle(Lizzie.resourceBundle.getString("FoxKifuDownload.title"));
    try {
      this.setIconImage(ImageIO.read(MoreEngines.class.getResourceAsStream("/assets/logo.png")));
    } catch (IOException e1) {
      e1.printStackTrace();
    }
    setAlwaysOnTop(Lizzie.frame.isAlwaysOnTop());
    setLocationRelativeTo(Lizzie.frame);

    foxKifuInfos = new ArrayList<KifuInfo>();
    loadRecentSearches();

    JPanel northPanel = new JPanel(new BorderLayout(0, 8));
    northPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 4, 8));
    getContentPane().add(northPanel, BorderLayout.NORTH);

    JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    northPanel.add(searchPanel, BorderLayout.NORTH);

    JLabel lblUserName =
        new JFontLabel(Lizzie.resourceBundle.getString("FoxKifuDownload.lblUserName"));
    searchPanel.add(lblUserName);

    txtUserName = new JFontTextField();
    txtUserName.setColumns(10);
    txtUserName.setText(Lizzie.config.lastFoxName);
    txtUserName.addKeyListener(
        new KeyAdapter() {
          public void keyPressed(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_ENTER) {
              getFoxKifus();
            }
          }
        });
    searchPanel.add(txtUserName);

    JButton btnSearch =
        new JFontButton(Lizzie.resourceBundle.getString("FoxKifuDownload.btnSearch"));
    btnSearch.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            getFoxKifus();
          }
        });
    searchPanel.add(btnSearch);

    JLabel lblUidHint =
        new JFontLabel(Lizzie.resourceBundle.getString("FoxKifuDownload.uidOnlyHint"));
    lblUidHint.setForeground(Color.GRAY);
    searchPanel.add(lblUidHint);

    JLabel lblAfterGet = new JFontLabel();
    lblAfterGet.setText(Lizzie.resourceBundle.getString("FoxKifuDownload.lblAfterGet"));
    searchPanel.add(lblAfterGet);

    JComboBox<String> cbxAfterGet = new JFontComboBox<String>();
    cbxAfterGet.addItem(Lizzie.resourceBundle.getString("FoxKifuDownload.cbxAfterGet.min"));
    cbxAfterGet.addItem(Lizzie.resourceBundle.getString("FoxKifuDownload.cbxAfterGet.close"));
    cbxAfterGet.addItem(Lizzie.resourceBundle.getString("FoxKifuDownload.cbxAfterGet.none"));
    cbxAfterGet.addItemListener(
        new ItemListener() {
          public void itemStateChanged(final ItemEvent e) {
            int index = cbxAfterGet.getSelectedIndex();
            Lizzie.config.foxAfterGet = index;
            Lizzie.config.uiConfig.put("fox-after-get", index);
            saveConfigQuietly();
          }
        });
    cbxAfterGet.setSelectedIndex(Lizzie.config.foxAfterGet);
    searchPanel.add(cbxAfterGet);

    JPanel infoPanel = new JPanel(new BorderLayout(0, 6));
    northPanel.add(infoPanel, BorderLayout.CENTER);

    lblCurrentUser = new JFontLabel();
    infoPanel.add(lblCurrentUser, BorderLayout.NORTH);

    JPanel recentWrapper = new JPanel(new BorderLayout(6, 0));
    infoPanel.add(recentWrapper, BorderLayout.CENTER);

    JLabel lblRecent =
        new JFontLabel(Lizzie.resourceBundle.getString("FoxKifuDownload.recentSearches"));
    recentWrapper.add(lblRecent, BorderLayout.WEST);

    recentSearchesPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    recentWrapper.add(recentSearchesPanel, BorderLayout.CENTER);

    JButton btnClearRecent =
        new JFontButton(Lizzie.resourceBundle.getString("FoxKifuDownload.clearRecent"));
    btnClearRecent.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            clearRecentSearches();
          }
        });
    recentWrapper.add(btnClearRecent, BorderLayout.EAST);

    updateCurrentUserLabel(txtUserName.getText().trim(), null);
    updateRecentSearchesPanel();

    JPanel buttonPane = new JPanel();

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
            if (e.getClickCount() == 2) {
              if (row >= 0 && col >= 0 && foxReq != null) {
                foxReq.sendCommand("chessid " + table.getValueAt(row, 10).toString());
              }
            }
          }
        });
    TableCellRenderer tcr = new ColorTableCellRenderer();
    table.setDefaultRenderer(Object.class, tcr);
    scrollPane = new JScrollPane(table);
    getContentPane().add(scrollPane, BorderLayout.CENTER);
    getContentPane().add(buttonPane, BorderLayout.SOUTH);

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
            if (rows == null) return;
            if (curTabNumber == 1) return;
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

  private void reloadCurrentPage() {
    if (model == null) return;
    while (model.getRowCount() > 0) {
      model.removeRow(model.getRowCount() - 1);
    }
    for (int i = (curTabNumber - 1) * numbersPerTab;
        i < curTabNumber * numbersPerTab && i < rows.size();
        i++) {
      model.addRow(rows.get(i));
    }
  }

  private void maybeGetNextPage() {
    if (foxReq == null || foxKifuInfos.isEmpty()) return;
    if (curTabNumber == tabNumber || tabNumber >= 4 && curTabNumber == tabNumber - 1) {
      String last = foxKifuInfos.get(foxKifuInfos.size() - 1).chessid;
      if (!lastCode.equals(last)) {
        lastCode = last;
        advanceToNextPageAfterLoad = curTabNumber == tabNumber;
        foxReq.sendCommand(
            "uid " + myUid + " " + foxKifuInfos.get(foxKifuInfos.size() - 1).chessid);
      } else {
        if (curTabNumber == tabNumber) {
          if (isSecondTimeReqEmpty) {
            Utils.showMsg(Lizzie.resourceBundle.getString("FoxKifuDownload.noMoreKifu"), this);
          }
          if (isRequestEmpty) {
            isSecondTimeReqEmpty = true;
          }
        }
      }
    }
  }

  private void getFoxKifus() {
    triggerFoxSearch(txtUserName.getText().trim());
  }

  private void triggerFoxSearch(String foxUserText) {
    if (foxUserText == null || foxUserText.trim().isEmpty()) {
      Utils.showMsg(Lizzie.resourceBundle.getString("FoxKifuDownload.noUser"), this);
      return;
    }
    String normalizedUser = foxUserText.trim();
    if (isSearching) {
      Utils.showMsg(Lizzie.resourceBundle.getString("FoxKifuDownload.waitLastSearch"), this);
      return;
    }
    myUid = "";
    currentFoxNickname = normalizedUser;
    isSearching = true;
    isComplete = false;
    isSecondTimeReqEmpty = false;
    isRequestEmpty = false;
    advanceToNextPageAfterLoad = false;
    shutdownFoxRequest();
    foxReq = new GetFoxRequest(this);
    foxKifuInfos = new ArrayList<KifuInfo>();
    rows = null;
    model = null;
    tabNumber = 1;
    curTabNumber = 1;
    lastCode = "";
    txtUserName.setText(normalizedUser);
    updateCurrentUserLabel(normalizedUser, null);
    Lizzie.config.lastFoxName = normalizedUser;
    Lizzie.config.uiConfig.put("last-fox-name", Lizzie.config.lastFoxName);
    saveConfigQuietly();
    foxReq.sendCommand("user_name " + normalizedUser);
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
      if (jsonObject.optInt("result", 0) != 0) {
        isSearching = false;
        Utils.showMsg(
            Lizzie.resourceBundle.getString("FoxKifuDownload.getKifuFailed")
                + jsonObject.optString("resultstr", string),
            this);
        return;
      }
      String resolvedUid = jsonObject.optString("fox_uid", "").trim();
      String resolvedNickname =
          firstNonEmpty(
              jsonObject.optString("fox_nickname", ""),
              jsonObject.optString("username", ""),
              jsonObject.optString("name", ""),
              currentFoxNickname);
      if (!resolvedUid.isEmpty()) {
        myUid = resolvedUid;
      }
      if (!resolvedNickname.isEmpty()) {
        currentFoxNickname = resolvedNickname;
      }
      if (jsonObject.has("chesslist")) {
        handleChessList(jsonObject.getJSONArray("chesslist"), resolvedNickname, resolvedUid);
      }
      if (jsonObject.has("chess")) {
        String kifu = jsonObject.getString("chess");
        boolean oriReadKomi = Lizzie.config.readKomi;
        Lizzie.config.readKomi = false;
        SGFParser.loadFromString(kifu);
        Lizzie.board.setMovelistAll();
        Lizzie.frame.scheduleResumeAnalysisAfterLoad(200);
        Lizzie.frame.refresh();
        Lizzie.config.readKomi = oriReadKomi;
        if (Lizzie.config.foxAfterGet == 0) setExtendedState(JFrame.ICONIFIED);
        else if (Lizzie.config.foxAfterGet == 1) setVisible(false);
      }
    } catch (JSONException e1) {
      e1.printStackTrace();
      Utils.showMsg(
          Lizzie.resourceBundle.getString("FoxKifuDownload.getKifuFailed") + string, this);
      isSearching = false;
    }
  }

  private void handleChessList(JSONArray jsonArray, String resolvedNickname, String resolvedUid)
      throws JSONException {
    isSearching = false;
    int oldRows = foxKifuInfos.size();
    int previousTabNumber = curTabNumber;
    boolean shouldAdvancePage = advanceToNextPageAfterLoad;
    advanceToNextPageAfterLoad = false;
    if (jsonArray.length() == 0) {
      if (oldRows > 0) {
        isComplete = true;
        isSecondTimeReqEmpty = true;
        isRequestEmpty = true;
      } else {
        Utils.showMsg(Lizzie.resourceBundle.getString("FoxKifuDownload.noKifu"), this);
      }
      return;
    }

    isComplete = jsonArray.length() < 100;
    String detectedNickname = null;
    for (int i = 0; i < jsonArray.length(); i++) {
      JSONObject jsonObject = jsonArray.getJSONObject(i);
      KifuInfo kifuInfo = new KifuInfo();
      kifuInfo.index = foxKifuInfos.size() + 1;
      kifuInfo.playTime = jsonObject.getString("starttime");
      kifuInfo.blackUid = jsonObject.optString("blackuid", "").trim();
      if (kifuInfo.blackUid.isEmpty()) {
        kifuInfo.blackUid = String.valueOf(jsonObject.optLong("blackuid"));
      }
      kifuInfo.whiteUid = jsonObject.optString("whiteuid", "").trim();
      if (kifuInfo.whiteUid.isEmpty()) {
        kifuInfo.whiteUid = String.valueOf(jsonObject.optLong("whiteuid"));
      }
      kifuInfo.blackName =
          resolveFoxName(jsonObject, "blacknick", "blacknickname", "blackname", "blackenname");
      kifuInfo.whiteName =
          resolveFoxName(jsonObject, "whitenick", "whitenickname", "whitename", "whiteenname");
      if (detectedNickname == null) {
        if (kifuInfo.blackUid.equals(myUid)) detectedNickname = kifuInfo.blackName;
        else if (kifuInfo.whiteUid.equals(myUid)) detectedNickname = kifuInfo.whiteName;
      }
      int bRank = jsonObject.getInt("blackdan") - 17;
      kifuInfo.blackRank =
          bRank > 0
              ? bRank + Lizzie.resourceBundle.getString("FoxKifuDownload.rank.dan")
              : (Math.abs(bRank) + 1) + Lizzie.resourceBundle.getString("FoxKifuDownload.rank.kyu");
      int wRank = jsonObject.getInt("whitedan") - 17;
      kifuInfo.whiteRank =
          wRank > 0
              ? wRank + Lizzie.resourceBundle.getString("FoxKifuDownload.rank.dan")
              : (Math.abs(wRank) + 1) + Lizzie.resourceBundle.getString("FoxKifuDownload.rank.kyu");
      kifuInfo.chessid = jsonObject.getString("chessid");
      kifuInfo.totalMoves = jsonObject.getInt("movenum");
      kifuInfo.isWin = isCurrentUserWin(jsonObject);
      kifuInfo.result = buildResultText(jsonObject, kifuInfo.isWin);
      foxKifuInfos.add(kifuInfo);
    }

    String displayUid = firstNonEmpty(resolvedUid, myUid);
    String displayNickname = firstNonEmpty(detectedNickname, resolvedNickname, currentFoxNickname);
    if (!displayUid.isEmpty()) {
      myUid = displayUid;
    }
    if (!displayNickname.isEmpty()) {
      currentFoxNickname = displayNickname;
    }

    if (!displayNickname.isEmpty() || !displayUid.isEmpty()) {
      updateCurrentUserLabel(displayNickname, displayUid);
      addRecentSearch(displayNickname, displayUid);
    } else {
      updateCurrentUserLabel(null, null);
    }

    rows = new ArrayList<String[]>();
    for (int i = 0; i < foxKifuInfos.size(); i++) {
      KifuInfo info = foxKifuInfos.get(i);
      String[] rowParams = {
        String.valueOf(info.index),
        info.playTime,
        formatFoxUser(info.blackName, info.blackUid),
        info.blackRank,
        formatFoxUser(info.whiteName, info.whiteUid),
        info.whiteRank,
        info.result,
        String.valueOf(info.totalMoves),
        "",
        String.valueOf(info.isWin),
        info.chessid
      };
      rows.add(rowParams);
    }

    tabNumber = Math.max(1, (int) Math.ceil(rows.size() / (double) numbersPerTab));
    if (oldRows <= 0 || model == null) {
      model = new DefaultTableModel();
      model.addColumn(Lizzie.resourceBundle.getString("FoxKifuDownload.column.index"));
      model.addColumn(Lizzie.resourceBundle.getString("FoxKifuDownload.column.time"));
      model.addColumn(Lizzie.resourceBundle.getString("FoxKifuDownload.column.black"));
      model.addColumn(Lizzie.resourceBundle.getString("FoxKifuDownload.column.rank"));
      model.addColumn(Lizzie.resourceBundle.getString("FoxKifuDownload.column.white"));
      model.addColumn(Lizzie.resourceBundle.getString("FoxKifuDownload.column.rank"));
      model.addColumn(Lizzie.resourceBundle.getString("FoxKifuDownload.column.result"));
      model.addColumn(Lizzie.resourceBundle.getString("FoxKifuDownload.column.moves"));
      model.addColumn(Lizzie.resourceBundle.getString("FoxKifuDownload.column.open"));
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
      table.getColumnModel().getColumn(8).setCellEditor(new MyButtonOpenFoxKifuEditor());
      table.getColumnModel().getColumn(8).setCellRenderer(new MyButtonOpenFoxKifu());
      table.getColumnModel().getColumn(9).setPreferredWidth(0);
      table.getColumnModel().getColumn(10).setPreferredWidth(0);
      hideColumn(9);
      hideColumn(10);
      table.revalidate();
    }

    if (oldRows <= 0 || model == null) {
      curTabNumber = 1;
    } else if (shouldAdvancePage && previousTabNumber < tabNumber) {
      curTabNumber = previousTabNumber + 1;
    } else {
      curTabNumber = Math.min(previousTabNumber, tabNumber);
    }
    reloadCurrentPage();
    setLblTab(curTabNumber);
    if (Lizzie.config.isFrameFontSmall() && rows.size() >= 25) {
      scrollPane.setPreferredSize(
          new Dimension(
              scrollPane.getWidth(),
              table.getTableHeader().getHeight() + Config.menuHeight * 25 + 2));
      pack();
    }
  }

  private String buildResultText(JSONObject jsonObject, boolean isCurrentUserWin)
      throws JSONException {
    String result = "";
    int winner = jsonObject.getInt("winner");
    int point = jsonObject.getInt("point");
    int rule = jsonObject.getInt("rule");
    if (winner == 1 || winner == 2) {
      if (winner == 1) {
        result = Lizzie.resourceBundle.getString("FoxKifuDownload.black");
      } else {
        result = Lizzie.resourceBundle.getString("FoxKifuDownload.white");
      }
      if (point < 0) {
        if (point == -1) result += Lizzie.resourceBundle.getString("FoxKifuDownload.winByRes");
        else if (point == -2)
          result += Lizzie.resourceBundle.getString("FoxKifuDownload.winByTime");
        else result += Lizzie.resourceBundle.getString("FoxKifuDownload.win");
      } else {
        String unit = "";
        if (rule == 1) unit = Lizzie.resourceBundle.getString("FoxKifuDownload.stones");
        if (rule == 0) unit = Lizzie.resourceBundle.getString("FoxKifuDownload.points");
        String scoreText = formatPointValue(point);
        result +=
            Lizzie.config.isChinese
                ? Lizzie.resourceBundle.getString("FoxKifuDownload.win") + scoreText + unit
                : " +" + scoreText + unit;
      }
    } else {
      result = Lizzie.resourceBundle.getString("FoxKifuDownload.other");
    }
    return result;
  }

  private String formatPointValue(int point) {
    return new DecimalFormat("0.##").format(point / 100.0d);
  }

  private boolean isCurrentUserWin(JSONObject jsonObject) throws JSONException {
    int winner = jsonObject.getInt("winner");
    if (winner == 1) return String.valueOf(jsonObject.optLong("blackuid")).equals(myUid);
    if (winner == 2) return String.valueOf(jsonObject.optLong("whiteuid")).equals(myUid);
    return false;
  }

  private String resolveFoxName(JSONObject jsonObject, String... keys) throws JSONException {
    for (int i = 0; i < keys.length; i++) {
      String value = jsonObject.optString(keys[i], "").trim();
      if (!value.isEmpty()) {
        return value;
      }
    }
    return Lizzie.resourceBundle.getString("FoxKifuDownload.unknownNick");
  }

  private void updateCurrentUserLabel(String nickname, String uid) {
    String display;
    String safeNickname = nickname == null ? "" : nickname.trim();
    String safeUid = uid == null ? "" : uid.trim();
    if (safeUid.isEmpty() && safeNickname.isEmpty()) {
      display = Lizzie.resourceBundle.getString("FoxKifuDownload.currentUser.waiting");
    } else if (safeUid.isEmpty()) {
      display = safeNickname;
    } else if (safeNickname.isEmpty()) {
      display = safeUid;
    } else {
      display = formatFoxUser(safeNickname, safeUid);
    }
    lblCurrentUser.setText(
        Lizzie.resourceBundle.getString("FoxKifuDownload.currentUser.label") + display);
  }

  private String formatFoxUser(String nickname, String uid) {
    String safeName =
        nickname == null || nickname.trim().isEmpty()
            ? Lizzie.resourceBundle.getString("FoxKifuDownload.unknownNick")
            : nickname.trim();
    String safeUid = uid == null ? "" : uid.trim();
    if (safeUid.isEmpty()) return safeName;
    if (safeName.equals(safeUid)) return safeName;
    return safeName + " (" + safeUid + ")";
  }

  private void loadRecentSearches() {
    recentSearches.clear();
    JSONArray saved = Lizzie.config.uiConfig.optJSONArray("fox-recent-searches");
    if (saved == null) return;
    for (int i = 0; i < saved.length(); i++) {
      JSONObject item = saved.optJSONObject(i);
      if (item == null) continue;
      RecentFoxSearch search = new RecentFoxSearch();
      search.uid = item.optString("uid", "").trim();
      search.nickname = item.optString("nickname", "").trim();
      if (search.nickname.isEmpty() && !search.uid.isEmpty()) {
        search.nickname = search.uid;
      }
      if (search.nickname.isEmpty() && search.uid.isEmpty()) continue;
      recentSearches.add(search);
    }
  }

  private void saveRecentSearches() {
    JSONArray saved = new JSONArray();
    for (int i = 0; i < recentSearches.size() && i < 8; i++) {
      RecentFoxSearch search = recentSearches.get(i);
      JSONObject item = new JSONObject();
      item.put("uid", search.uid);
      item.put("nickname", search.nickname);
      saved.put(item);
    }
    Lizzie.config.uiConfig.put("fox-recent-searches", saved);
    saveConfigQuietly();
  }

  private void addRecentSearch(String nickname, String uid) {
    String normalizedUid = uid == null ? "" : uid.trim();
    String normalizedNickname = nickname == null ? "" : nickname.trim();
    if (normalizedNickname.isEmpty() && normalizedUid.isEmpty()) return;
    for (int i = recentSearches.size() - 1; i >= 0; i--) {
      RecentFoxSearch existing = recentSearches.get(i);
      if ((!normalizedUid.isEmpty() && normalizedUid.equals(existing.uid))
          || normalizedNickname.equals(existing.nickname)) {
        recentSearches.remove(i);
      }
    }
    RecentFoxSearch search = new RecentFoxSearch();
    search.uid = normalizedUid;
    search.nickname = normalizedNickname;
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
            Lizzie.resourceBundle.getString("FoxKifuDownload.clearRecentConfirm"),
            Lizzie.resourceBundle.getString("FoxKifuDownload.clearRecentTitle"),
            JOptionPane.OK_CANCEL_OPTION);
    if (choice != JOptionPane.OK_OPTION) return;
    recentSearches.clear();
    saveRecentSearches();
    updateRecentSearchesPanel();
  }

  private void updateRecentSearchesPanel() {
    recentSearchesPanel.removeAll();
    if (recentSearches.isEmpty()) {
      JLabel empty = new JFontLabel(Lizzie.resourceBundle.getString("FoxKifuDownload.recentEmpty"));
      empty.setForeground(Color.GRAY);
      recentSearchesPanel.add(empty);
    } else {
      for (int i = 0; i < recentSearches.size(); i++) {
        RecentFoxSearch search = recentSearches.get(i);
        JFontButton button = new JFontButton(formatFoxUser(search.nickname, search.uid));
        button.setFocusable(false);
        button.setMargin(new Insets(2, 8, 2, 8));
        button.addActionListener(
            new ActionListener() {
              public void actionPerformed(ActionEvent e) {
                String keyword = search.nickname == null ? "" : search.nickname.trim();
                if (keyword.isEmpty()) {
                  keyword = search.uid == null ? "" : search.uid.trim();
                }
                txtUserName.setText(keyword);
                triggerFoxSearch(keyword);
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

  private void shutdownFoxRequest() {
    if (foxReq != null) {
      foxReq.shutdown();
      foxReq = null;
    }
  }

  private String firstNonEmpty(String... values) {
    if (values == null) {
      return "";
    }
    for (String value : values) {
      if (value != null && !value.trim().isEmpty()) {
        return value.trim();
      }
    }
    return "";
  }
}

class ColorTableCellRenderer extends DefaultTableCellRenderer {

  public Component getTableCellRendererComponent(
      JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
    super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
    setHorizontalAlignment(CENTER);
    if (table.getValueAt(row, 9).toString().equals("true")) {
      setForeground(Color.RED);
    } else {
      setForeground(Color.GRAY);
    }
    return this;
  }
}

class MyButtonOpenFoxKifu implements TableCellRenderer {
  private JPanel panel;
  private JButton button;

  public MyButtonOpenFoxKifu() {
    initButton();
    initPanel();
    panel.add(button, BorderLayout.CENTER);
  }

  private void initButton() {
    button = new JFontButton();
    button.setMargin(new Insets(0, 0, 0, 0));
  }

  private void initPanel() {
    panel = new JPanel();
    panel.setLayout(new BorderLayout());
  }

  public Component getTableCellRendererComponent(
      JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
    button.setText(Lizzie.resourceBundle.getString("FoxKifuDownload.column.open"));
    return panel;
  }
}

class MyButtonOpenFoxKifuEditor extends AbstractCellEditor implements TableCellEditor {
  private JPanel panel;
  private JButton button;
  private String chessid = "";

  public MyButtonOpenFoxKifuEditor() {
    initButton();
    initPanel();
    panel.add(this.button, BorderLayout.CENTER);
  }

  private void initButton() {
    button = new JFontButton();
    button.setMargin(new Insets(0, 0, 0, 0));
    button.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            if (Lizzie.frame.foxKifuDownload != null
                && Lizzie.frame.foxKifuDownload.foxReq != null) {
              Lizzie.frame.foxKifuDownload.foxReq.sendCommand("chessid " + chessid);
            }
          }
        });
  }

  private void initPanel() {
    panel = new JPanel();
    panel.setLayout(new BorderLayout());
  }

  @Override
  public Component getTableCellEditorComponent(
      JTable table, Object value, boolean isSelected, int row, int column) {
    button.setText(Lizzie.resourceBundle.getString("FoxKifuDownload.column.open"));
    chessid = table.getValueAt(row, 10).toString();
    return panel;
  }

  @Override
  public Object getCellEditorValue() {
    return null;
  }
}

class KifuInfo {
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
  String chessid;
  boolean isWin;
}

class RecentFoxSearch {
  String uid;
  String nickname;
}
