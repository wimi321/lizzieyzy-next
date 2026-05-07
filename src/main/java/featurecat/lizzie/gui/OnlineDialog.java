package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.AjaxHttpRequest;
import featurecat.lizzie.util.Utils;
import featurecat.lizzie.util.YikeSyncDebugLog;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.text.NumberFormat;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.InternationalFormatter;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class OnlineDialog extends JDialog {
  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;
  private ScheduledExecutorService online = Executors.newScheduledThreadPool(1);
  private ScheduledFuture<?> schedule = null;
  private final YikeApiClient yikeApiClient = new YikeApiClient();
  private static WebSocketClient client;
  private Socket sio;
  private int type = 0;
  private JFormattedTextField txtRefreshTime;
  private JLabel lblError;
  private int refreshTime;
  private JTextField txtUrl;
  private String ajaxUrl = "";
  private String lastYikeMainlineSgf = "";
  private int lastYikeHandsCount = -1;
  private Map queryMap = null;
  private String query = "";
  private String whitePlayer = "";
  private String blackPlayer = "";
  private long wuid = 0;
  private long buid = 0;
  private String wTime = "";
  private String bTime = "";
  private int seqs = 0;
  private boolean done = false;
  private BoardHistoryList history = null;
  private int boardSize = 19;
  private boolean hasResolvedYikeBoardSize = false;
  private YikeGeometrySnapshot lastYikeGeometry = null;
  private long userId = -1000000;
  private long roomId = 0;
  // static AjaxHttpRequest ajax;
  private boolean firstTime = true;
  static boolean isStoped = false;
  static boolean fromBrowser = false;
  static Timer timer;
  private boolean chineseFlag = false;
  private int chineseRule = 1;
  private boolean shouldMoveForward = false;
  private Map<Integer, Map<Integer, JSONObject>> branchs =
      new HashMap<Integer, Map<Integer, JSONObject>>();
  private Map<Integer, Map<Integer, JSONObject>> comments =
      new HashMap<Integer, Map<Integer, JSONObject>>();
  private byte[] b = {
    119, 115, 115, 58, 47, 47, 104, 108, 119, 115, 46, 104, 117, 97, 110, 108, 101, 46, 113, 113,
    46, 99, 111, 109, 47, 119, 113, 98, 114, 111, 97, 100, 99, 97, 115, 116, 108, 111, 116, 117, 115
  };
  private byte[] b2 = {
    119, 115, 58, 47, 47, 119, 115, 104, 97, 108, 108, 46, 104, 117, 97, 110, 108, 101, 46, 113,
    113, 46, 99, 111, 109, 47, 78, 101, 119, 69, 97, 103, 108, 101, 69, 121, 101, 76, 111, 116, 117,
    115
  };
  private byte[] b3 = {
    104, 116, 116, 112, 115, 58, 47, 47, 119, 101, 105, 113, 105, 46, 113, 113, 46, 99, 111, 109,
    47, 111, 112, 101, 110, 113, 105, 112, 117, 47, 103, 101, 116, 113, 105, 112, 117, 63, 99, 97,
    108, 108, 98, 97, 99, 107, 61, 106, 81, 117, 101, 114, 121, 49, 38, 103, 97, 109, 101, 99, 111,
    100, 101, 61
  };
  private byte[] b4 = {104, 117, 97, 110, 108, 101, 46, 113, 113, 46, 99, 111, 109};

  private byte[] c1 = {
    104, 116, 116, 112, 115, 58, 47, 47, 114, 116, 103, 97, 109, 101, 46, 121, 105, 107, 101, 119,
    101, 105, 113, 105, 46, 99, 111, 109
  };

  private static final class YikeGeometrySnapshot {
    private final int left;
    private final int top;
    private final int width;
    private final int height;
    private final Double firstX;
    private final Double firstY;
    private final Double cellX;
    private final Double cellY;
    private final int score;
    private final String node;
    private final String reason;

    private YikeGeometrySnapshot(
        int left,
        int top,
        int width,
        int height,
        Double firstX,
        Double firstY,
        Double cellX,
        Double cellY,
        int score,
        String node,
        String reason) {
      this.left = left;
      this.top = top;
      this.width = width;
      this.height = height;
      this.firstX = firstX;
      this.firstY = firstY;
      this.cellX = cellX;
      this.cellY = cellY;
      this.score = score;
      this.node = node;
      this.reason = reason;
    }
  }

  public OnlineDialog(Window owner) {
    super(owner);
    yikeDebugLog("OnlineDialog created");
    setTitle(resourceBundle.getString("OnlineDialog.title.config"));
    setModalityType(ModalityType.APPLICATION_MODAL);
    setAlwaysOnTop(Lizzie.frame.isAlwaysOnTop());
    // setType(Type.POPUP);
    // setBounds(100, 100, 790, 207);
    Lizzie.setFrameSize(this, 730, 172);
    this.setResizable(false);
    try {
      this.setIconImage(ImageIO.read(MoreEngines.class.getResourceAsStream("/assets/logo.png")));
    } catch (IOException e) {
      e.printStackTrace();
    }
    getContentPane().setLayout(new BorderLayout());
    JPanel buttonPane = new JPanel();
    getContentPane().add(buttonPane, BorderLayout.CENTER);
    JButton okButton = new JButton(resourceBundle.getString("OnlineDialog.button.ok"));
    okButton.setBounds(129, 105, 74, 29);
    okButton.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            applyChange();
          }
        });
    buttonPane.setLayout(null);
    okButton.setActionCommand("OK");
    buttonPane.add(okButton);
    getRootPane().setDefaultButton(okButton);

    JButton interruptButton =
        new JButton(resourceBundle.getString("OnlineDialog.button.interrupt"));
    interruptButton.setBounds(218, 105, 74, 29);
    interruptButton.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            stopSync();
          }
        });
    buttonPane.add(interruptButton);
    interruptButton.setMargin(new Insets(0, 0, 0, 0));

    JButton cancelButton = new JButton(resourceBundle.getString("OnlineDialog.button.cancel"));
    cancelButton.setBounds(307, 105, 74, 29);
    cancelButton.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            setVisible(false);
          }
        });
    cancelButton.setActionCommand("Cancel");
    buttonPane.add(cancelButton);

    JLabel lblUrl = new JLabel(resourceBundle.getString("OnlineDialog.title.url"));
    lblUrl.setBounds(10, 58, 56, 14);
    buttonPane.add(lblUrl);
    lblUrl.setHorizontalAlignment(SwingConstants.LEFT);

    NumberFormat nf = NumberFormat.getIntegerInstance();
    nf.setGroupingUsed(false);

    txtUrl = new JTextField();
    txtUrl.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusGained(FocusEvent e) {
            txtUrl.selectAll();
          }
        });
    txtUrl.setBounds(69, 55, 639, 20);
    buttonPane.add(txtUrl);
    txtUrl.setColumns(10);

    JLabel lblRefresh = new JLabel(resourceBundle.getString("OnlineDialog.title.refresh"));
    lblRefresh.setBounds(10, 80, 56, 14);
    buttonPane.add(lblRefresh);

    JLabel lblRefreshTime = new JLabel(resourceBundle.getString("OnlineDialog.title.refreshTime"));
    lblRefreshTime.setBounds(119, 80, 81, 14);
    buttonPane.add(lblRefreshTime);

    txtRefreshTime =
        new JFormattedTextField(
            new InternationalFormatter(nf) {
              protected DocumentFilter getDocumentFilter() {
                return filter;
              }

              private DocumentFilter filter = new DigitOnlyFilter();
            });
    txtRefreshTime.setBounds(69, 79, 36, 20);
    txtRefreshTime.setText("1");
    buttonPane.add(txtRefreshTime);
    txtRefreshTime.setColumns(10);
    JLabel lblPrompt1 = new JLabel(resourceBundle.getString("OnlineDialog.lblPrompt1.text"));
    lblPrompt1.setBounds(10, 6, 398, 14);
    buttonPane.add(lblPrompt1);

    JLabel lblPrompt2 =
        new JLabel(
            resourceBundle.getString("OnlineDialog.lblPrompt2.text")
                + "https://home.yikeweiqi.com/#/live/room/18328/1/15630642"); // 支持弈客直播，例如
    lblPrompt2.setBounds(10, 30, 475, 14);
    // buttonPane.add(lblPrompt2);
    //    JLabel lblPrompt3 =
    //        new JLabel(
    //
    // "支持野狐(腾讯围棋)分享链接，例如:http://share.foxwq.com/index.html?gameid=369&showtype=1&showid=83&chessid=383699091456898&status=0&createtime=1559816204&title=%E9%9F%A9%E5%9B%BD%E5%9B%B4%E6%A3%8BTV%E6%9D%AF32%E5%BC%BA%E6%88%98&chatid=880&support=1");
    //    lblPrompt3.setBounds(10, 50, 755, 14);
    //    buttonPane.add(lblPrompt3);
    //
    //    JLabel lblPrompt4 =
    //        new JLabel(
    //
    // "或:http://huanle.qq.com/act/a20170110wq/index-share.html?srctype=2&svrid=2404&roomid=20&tableid=6354&gametag=S2404R20T6354t5D06F6ECS82&title=%E8%85%BE%E8%AE%AF%E5%9B%B4%E6%A3%8B%E5%9C%A8%E7%BA%BF%E5%AF%B9%E5%BC%88&uin=643324524&support=0");
    //    lblPrompt4.setBounds(10, 65, 755, 14);
    //    buttonPane.add(lblPrompt4);

    lblError = new JLabel(resourceBundle.getString("OnlineDialog.lblError.text"));
    lblError.setForeground(Color.RED);
    lblError.setBounds(171, 80, 316, 16);
    lblError.setVisible(false);
    buttonPane.add(lblError);

    txtUrl.selectAll();

    setLocationRelativeTo(getOwner());
    paste();
  }

  public void paste() {
    String pastContent =
        Optional.ofNullable(Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null))
            .filter(cc -> cc.isDataFlavorSupported(DataFlavor.stringFlavor))
            .flatMap(
                cc -> {
                  try {
                    return Optional.of((String) cc.getTransferData(DataFlavor.stringFlavor));
                  } catch (UnsupportedFlavorException e) {
                    e.printStackTrace();
                  } catch (IOException e) {
                    e.printStackTrace();
                  }
                  return Optional.empty();
                })
            .orElse("");
    txtUrl.setText(pastContent);
  }

  private void applyChange() {
    //
    if (LizzieFrame.urlSgf) {
      if (client != null && client.isOpen()) {
        client.close();
        client = null;
      }
    }
    type = checkUrl();
    isStoped = false;
    chineseRule = 1;
    chineseFlag = false;
    firstTime = true;
    LizzieFrame.urlSgf = true;
    Lizzie.frame.setCommentPaneOrArea(false);
    if (type > 0) {
      error(false);
      setVisible(false);
      try {
        Lizzie.frame.setResult("");
        proc();
      } catch (IOException | URISyntaxException e) {
        e.printStackTrace();
      }
    } else {
      error(true);
    }
    Lizzie.frame.syncLiveBoardStat();
  }

  private class DigitOnlyFilter extends DocumentFilter {
    @Override
    public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
        throws BadLocationException {
      String newStr = string != null ? string.replaceAll("\\D++", "") : "";
      if (!newStr.isEmpty()) {
        fb.insertString(offset, newStr, attr);
      }
    }

    @Override
    public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
        throws BadLocationException {
      String newStr = text != null ? text.replaceAll("\\D++", "") : "";
      if (!newStr.isEmpty()) {
        fb.replace(offset, length, newStr, attrs);
      }
    }
  }

  private void error(boolean e) {
    if (!this.isVisible() && OnlineDialog.fromBrowser) return;
    if (!isStoped
        && (Lizzie.frame.browserFrame == null || !Lizzie.frame.browserFrame.isVisible())) {
      lblError.setVisible(e);
      setVisible(true);
    }
  }

  private boolean isYikeSyncType() {
    return type == YikeUrlInfo.TYPE_OLD_LIVE_ROOM
        || type == YikeUrlInfo.TYPE_OLD_LIVE_BOARD
        || type == YikeUrlInfo.TYPE_GAME_ROOM
        || type == YikeUrlInfo.TYPE_NEW_LIVE_ROOM
        || type == YikeUrlInfo.TYPE_UNITE_ROOM;
  }

  private String currentYikeSourceUrl() {
    return txtUrl == null ? "" : txtUrl.getText().trim();
  }

  private void updateYikeSyncStatus(String url, String status) {
    if (isYikeSyncType()) {
      Lizzie.frame.updateYikeLiveSyncStatus(url, status);
    }
  }

  private String text(String key, String fallback) {
    try {
      return resourceBundle.getString(key);
    } catch (MissingResourceException e) {
      return fallback;
    }
  }

  private int checkUrl() {
    String id = null;
    chineseRule = 1;
    chineseFlag = false;
    String url = txtUrl.getText().trim();
    Optional<YikeUrlInfo> yikeUrlInfo = YikeUrlParser.parse(url);
    if (yikeUrlInfo.isPresent()) {
      YikeUrlInfo info = yikeUrlInfo.get();
      roomId = info.getRoomId();
      ajaxUrl = info.getAjaxUrl();
      return info.getType();
    }
    if (url.endsWith("/0/0")) {
      url = url.substring(0, url.length() - 4);
    }
    Pattern up =
        Pattern.compile(
            "https*://(?s).*?([^\\./]+\\.[^\\./]+)/(?s).*?(live/[a-zA-Z]+/)([^/]+)/[0-9]+/([^/]+)[^\\n]*");
    Matcher um = up.matcher(url);
    if (um.matches() && um.groupCount() >= 4) {
      int type = 1;
      id = um.group(3);
      try {
        roomId = Long.parseLong(um.group(4));
      } catch (NumberFormatException e) {
        roomId = Long.parseLong(id);
        type = 2;
      }
      if (!Utils.isBlank(id) && roomId > 0) {
        ajaxUrl = "https://api." + um.group(1) + "/golive/dtl?id=" + id + "&flag=1";
        return type;
      }
    }
    up = Pattern.compile("https*://(?s).*?([^\\./]+\\.[^\\./]+)/(?s).*?(live/[a-zA-Z]+/)([^/]+)");
    um = up.matcher(url);
    if (um.matches() && um.groupCount() >= 3) {
      id = um.group(3);
      if (!Utils.isBlank(id)) {
        ajaxUrl = "https://api." + um.group(1) + "/golive/dtl?id=" + id;
        return 2;
      }
    }

    up =
        Pattern.compile(
            "https*://(?s).*?([^\\./]+\\.[^\\./]+)/(?s).*?(game/[a-zA-Z]+/)[0-9]+/([^/]+)");
    um = up.matcher(url);
    if (um.matches() && um.groupCount() >= 3) {
      roomId = Long.parseLong(um.group(3));
      if (roomId > 0) { // !Utils.isBlank(id)) {
        ajaxUrl = "https://api." + um.group(1) + "/golive/dtl?id=" + roomId;
        return 5;
      }
    }

    up =
        Pattern.compile(
            "https*://(?s).*?([^\\./]+\\.[^\\./]+)/(?s).*?(room=)([0-9]+)(&hall)(?s).*?");
    um = up.matcher(url);
    if (um.matches() && um.groupCount() >= 3) {
      roomId = Long.parseLong(um.group(3));
      if (roomId > 0) { // !Utils.isBlank(id)) {
        ajaxUrl = "https://api." + um.group(1) + "/golive/dtl?id=" + roomId;
        return 5;
      }
    }

    try {
      URI uri = new URI(url);
      queryMap = splitQuery(uri);
      if (queryMap != null) {
        if (queryMap.get("gameid") != null && queryMap.get("createtime") != null) {
          return 3;
        } else if (queryMap.get("gametag") != null && queryMap.get("uin") != null) {
          query = uri.getRawQuery();
          ajaxUrl =
              "http://wshall."
                  + new String(b4) // uri.getHost()
                  + "/wxnseed/Broadcast/RequestBroadcast?callback=jQuery1&"
                  + query;
          return 4;
        }
      }
    } catch (URISyntaxException e) {
      e.printStackTrace();
    }

    // Try
    ajaxUrl = url;
    return 99;
  }

  private void proc() throws IOException, URISyntaxException {
    refreshTime = Utils.txtFieldValue(txtRefreshTime);
    refreshTime = (refreshTime > 0 ? refreshTime : 10);
    YikeSyncDebugLog.log(
        "OnlineDialog.proc type="
            + type
            + " roomId="
            + roomId
            + " ajaxUrl="
            + ajaxUrl
            + " refreshTime="
            + refreshTime);
    // if (!online.isShutdown()) {
    // online.shutdown();
    // }
    if (schedule != null && !schedule.isCancelled() && !schedule.isDone()) {
      schedule.cancel(false);
    }
    ensureOnlineExecutor();
    done = false;
    history = null;
    hasResolvedYikeBoardSize = false;
    lastYikeGeometry = null;
    lastYikeMainlineSgf = "";
    lastYikeHandsCount = -1;
    Lizzie.board.clearForOnline();
    switch (type) {
      case 1:
      case 5:
        online.execute(
            new Runnable() {
              @Override
              public void run() {
                try {
                  req2(false);
                } catch (URISyntaxException e) {
                  e.printStackTrace();
                  SwingUtilities.invokeLater(
                      new Runnable() {
                        @Override
                        public void run() {
                          error(true);
                        }
                      });
                }
              }
            });
        break;
      case 6:
        reqNewYikeRoom(true);
        break;
      case 7:
        startYikeUnitePolling();
        break;
      case 2:
        refresh("(?s).*?(\\\"Content\\\":\\\")(.+)(\\\",\\\")(?s).*");
        break;
      case 3:
        req(true);
        break;
      case 4:
        req0();
        break;
      case 99:
        get();
        break;
      default:
        break;
    }
  }

  private void ensureOnlineExecutor() {
    if (online == null || online.isShutdown() || online.isTerminated()) {
      online = Executors.newScheduledThreadPool(1);
    }
  }

  private boolean shouldSyncYikeMainlineOnly() {
    return type == YikeUrlInfo.TYPE_OLD_LIVE_ROOM
        || type == YikeUrlInfo.TYPE_OLD_LIVE_BOARD
        || type == YikeUrlInfo.TYPE_GAME_ROOM
        || type == YikeUrlInfo.TYPE_NEW_LIVE_ROOM
        || type == YikeUrlInfo.TYPE_UNITE_ROOM;
  }

  private boolean shouldReplaceYikeMainline() {
    return type == YikeUrlInfo.TYPE_NEW_LIVE_ROOM || type == YikeUrlInfo.TYPE_UNITE_ROOM;
  }

  @SuppressWarnings("deprecation")
  public void parseSgf(String data, String format, int num, boolean decode, boolean first) {
    YikeSyncDebugLog.log(
        "OnlineDialog.parseSgf entry edt="
            + SwingUtilities.isEventDispatchThread()
            + " type="
            + type
            + " first="
            + first
            + " dataLen="
            + (data == null ? -1 : data.length())
            + " formatBlank="
            + Utils.isBlank(format));
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(
          new Runnable() {
            public void run() {
              parseSgf(data, format, num, decode, first);
            }
          });
      return;
    }
    JSONObject o = null;
    JSONObject live = null;
    try {
      o = new JSONObject(data);
      o = o.optJSONObject("Result");
      if (o != null) {
        live = o.optJSONObject("live");
      }
      if (live == null) {
        JSONObject root = new JSONObject(data);
        live = root.optJSONObject("result");
      }
    } catch (JSONException e) {
    }
    String sgf = "";
    if (live != null) {
      sgf = live.optString("Content");
      if (Utils.isBlank(sgf)) {
        sgf = firstNonBlank(live.optString("sgf"), live.optString("clean_sgf"));
      }
      if (Utils.isBlank(sgf)) {
        YikeSyncDebugLog.log("OnlineDialog.parseSgf no sgf in live payload");
        updateYikeSyncStatus(
            currentYikeSourceUrl(), text("YikeLiveDialog.syncNoSgf", "No SGF is available yet."));
        error(true);
        return;
      }
    }
    if (Utils.isBlank(sgf)) {
      if (!Utils.isBlank(format)) {
        Pattern sp = Pattern.compile(format);
        Matcher sm = sp.matcher(data);
        if (sm.matches() && sm.groupCount() >= num) {
          sgf = sm.group(num);
          if (decode) {
            sgf = URLDecoder.decode(sgf);
          }
        }
      } else {
        sgf = data;
        Lizzie.frame.loadSgfString(sgf, 200, Lizzie.config.readKomi, false, null);
        return;
      }
    }
    try {
      if (shouldSyncYikeMainlineOnly()) {
        sgf = YikeSgfMainline.withoutVariations(sgf);
      }
      BoardData previousPosition = cloneCurrentBoardPosition();
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      YikeSyncDebugLog.log("OnlineDialog.parseSgf parsing sgf len=" + sgf.length());
      BoardHistoryList liveNode = SGFParser.parseSgf(sgf, first);
      if (liveNode != null) {
        YikeSyncDebugLog.log(
            "OnlineDialog.parseSgf liveNode move="
                + liveNode.getMoveNumber()
                + " boardWidth="
                + Board.boardWidth
                + " replace="
                + shouldReplaceYikeMainline());
        boardSize = Board.boardWidth;
        onYikeBoardSizeResolved();
        blackPlayer = liveNode.getGameInfo().getPlayerBlack();
        whitePlayer = liveNode.getGameInfo().getPlayerWhite();
        double komi = liveNode.getGameInfo().getKomi();
        int handicap = liveNode.getGameInfo().getHandicap();
        if (live != null) {
          komi = live.optDouble("komi", komi);
          handicap = live.optInt("handicap", handicap);
          blackPlayer =
              firstNonBlank(
                  live.optString("BlackPlayer"),
                  live.optString("black_name"),
                  live.optString("blackName"),
                  blackPlayer);
          whitePlayer =
              firstNonBlank(
                  live.optString("WhitePlayer"),
                  live.optString("white_name"),
                  live.optString("whiteName"),
                  whitePlayer);
        }
        if (shouldReplaceYikeMainline()) {
          replaceYikeMainline(
              liveNode,
              live,
              komi,
              handicap,
              previousPosition,
              previousBoardWidth,
              previousBoardHeight);
          return;
        }
        int diffMove = Lizzie.board.getHistory().sync(liveNode);
        YikeSyncDebugLog.log(
            "OnlineDialog.parseSgf synced diffMove="
                + diffMove
                + " currentMove="
                + Lizzie.board.getHistory().getMoveNumber());
        sendYikeContextToReadBoard(liveNode != null ? liveNode.getMoveNumber() : 0);
        if (diffMove >= 0) {
          syncYikeAnalysisEngineToCurrentHistory(
              previousPosition, previousBoardWidth, previousBoardHeight);
          //     Lizzie.board.goToMoveNumberBeyondBranch(diffMove > 0 ? diffMove - 1 : 0);
          //    while (Lizzie.board.nextMove()) ;
        }
        if (Utils.isBlank(blackPlayer)) {
          Pattern spb =
              Pattern.compile("(?s).*?(\\\"BlackPlayer\\\":\\\")([^\"]+)(\\\",\\\")(?s).*");
          Matcher smb = spb.matcher(data);
          if (smb.matches() && smb.groupCount() >= 2) {
            blackPlayer = smb.group(2);
          }
        }
        if (Utils.isBlank(whitePlayer)) {
          Pattern spw =
              Pattern.compile("(?s).*?(\\\"WhitePlayer\\\":\\\")([^\\\"]+)(\\\",\\\")(?s).*");
          Matcher smw = spw.matcher(data);
          if (smw.matches() && smw.groupCount() >= 2) {
            whitePlayer = smw.group(2);
          }
        }
        if (first) {
          Lizzie.frame.setPlayers(whitePlayer, blackPlayer);
          Lizzie.board.getHistory().getGameInfo().setPlayerBlack(blackPlayer);
          Lizzie.board.getHistory().getGameInfo().setPlayerWhite(whitePlayer);
          if (Lizzie.config.readKomi) {
            Lizzie.board.getHistory().getGameInfo().setKomi(komi);
            Lizzie.leelaz.komi(komi);
          }
          firstTime = false;
        }
        if (live != null
            && ("3".equals(live.optString("Status"))
                || "3".equals(live.optString("status"))
                || live.optInt("status", 0) == 3)) {
          if (schedule != null && !schedule.isCancelled() && !schedule.isDone()) {
            schedule.cancel(false);
          }
          String result =
              firstNonBlank(
                  live.optString("GameResult"),
                  live.optString("game_result"),
                  live.optString("resultDesc"));
          if (!Utils.isBlank(result)) {
            Lizzie.board.getHistory().getData().comment =
                result + "\n" + Lizzie.board.getHistory().getData().comment;
            Lizzie.board.previousMove(false);
            Lizzie.board.nextMove(true);
          }
        }
        if (first) {
          Lizzie.frame.refresh();
        }
      } else {
        YikeSyncDebugLog.log("OnlineDialog.parseSgf SGFParser returned null");
        updateYikeSyncStatus(
            currentYikeSourceUrl(), text("YikeLiveDialog.syncFailed", "Yike sync failed."));
        error(true);
      }
    } catch (RuntimeException e) {
      YikeSyncDebugLog.log("OnlineDialog.parseSgf runtime error: " + e.toString());
      updateYikeSyncStatus(
          currentYikeSourceUrl(),
          text("YikeLiveDialog.syncFailed", "Yike sync failed.") + ": " + e.getMessage());
      error(true);
    }
  }

  private void replaceYikeMainline(
      BoardHistoryList liveNode,
      JSONObject live,
      double komi,
      int handicap,
      BoardData previousPosition,
      int previousBoardWidth,
      int previousBoardHeight) {
    YikeSyncDebugLog.log(
        "OnlineDialog.replaceYikeMainline type="
            + type
            + " incomingMove="
            + (liveNode == null ? -1 : liveNode.getMoveNumber())
            + " exploring="
            + isUserExploringVariation());
    boardSize = Board.boardWidth;
    onYikeBoardSizeResolved();
    while (liveNode.previous().isPresent())
      ;
    while (liveNode.next(true).isPresent())
      ;
    if (type == YikeUrlInfo.TYPE_UNITE_ROOM) {
      if (isUserExploringVariation()) {
        appendNewMainlineMovesPreservingHead(liveNode);
        YikeSyncDebugLog.log(
            "OnlineDialog.replaceYikeMainline appended while exploring currentMove="
                + Lizzie.board.getHistory().getMoveNumber());
        return;
      }
      preserveUserBranchesOnto(liveNode);
    }
    Lizzie.board.setHistory(liveNode);
    Lizzie.board.getHistory().getGameInfo().setPlayerBlack(blackPlayer);
    Lizzie.board.getHistory().getGameInfo().setPlayerWhite(whitePlayer);
    Lizzie.board.getHistory().getGameInfo().setHandicap(handicap);
    if (Lizzie.config.readKomi) {
      Lizzie.board.getHistory().getGameInfo().setKomi(komi);
      Lizzie.leelaz.komi(komi);
    }
    Lizzie.frame.setPlayers(whitePlayer, blackPlayer);
    if (live != null
        && ("3".equals(live.optString("Status"))
            || "3".equals(live.optString("status"))
            || live.optInt("status", 0) == 3)) {
      if (schedule != null && !schedule.isCancelled() && !schedule.isDone()) {
        schedule.cancel(false);
      }
      String result =
          firstNonBlank(
              live.optString("GameResult"),
              live.optString("game_result"),
              live.optString("resultDesc"));
      if (!Utils.isBlank(result)) {
        Lizzie.board.getHistory().getData().comment =
            result + "\n" + Lizzie.board.getHistory().getData().comment;
      }
    }
    firstTime = false;
    syncYikeAnalysisEngineToCurrentHistory(
        previousPosition, previousBoardWidth, previousBoardHeight);
    Lizzie.frame.refresh();
    sendYikeContextToReadBoard(liveNode != null ? liveNode.getMoveNumber() : 0);
    YikeSyncDebugLog.log(
        "OnlineDialog.replaceYikeMainline done currentMove="
            + Lizzie.board.getHistory().getMoveNumber());
  }

  private void syncYikeAnalysisEngineToCurrentHistory(
      BoardData previousPosition, int previousBoardWidth, int previousBoardHeight) {
    try {
      if (Lizzie.board == null || Lizzie.frame == null) {
        return;
      }
      if (Lizzie.board.trySyncCurrentPositionToPrimaryEngineIncrementally(
          previousPosition, previousBoardWidth, previousBoardHeight)) {
        return;
      }
      if (Lizzie.board.resendCurrentPositionToPrimaryEngine()) {
        Lizzie.frame.scheduleResumeAnalysisAfterSyncLoad(250);
      }
    } catch (RuntimeException e) {
      updateYikeSyncStatus(
          currentYikeSourceUrl(),
          text("YikeLiveDialog.syncFailed", "Yike sync failed.") + ": " + e.getMessage());
    }
  }

  /** 把用户在主线节点上摆出的"变化分支"按手数搬到新 liveNode 主线对应节点上，避免每次同步丢失复盘分支。 */
  private void preserveUserBranchesOnto(BoardHistoryList liveNode) {
    BoardHistoryList current = Lizzie.board.getHistory();
    if (current == null || liveNode == null) return;
    BoardHistoryNode curHead = current.getCurrentHistoryNode();
    if (curHead == null) return;
    while (curHead.previous().isPresent()) curHead = curHead.previous().get();
    BoardHistoryNode liveHead = liveNode.getCurrentHistoryNode();
    if (liveHead == null) return;
    while (liveHead.previous().isPresent()) liveHead = liveHead.previous().get();

    Map<Integer, BoardHistoryNode> liveByMove = new HashMap<>();
    for (BoardHistoryNode n = liveHead; n != null; n = n.next(true).orElse(null)) {
      liveByMove.put(n.getData().moveNumber, n);
    }

    for (BoardHistoryNode old = curHead; old != null; old = old.next(true).orElse(null)) {
      if (old.numberOfChildren() <= 1) continue;
      BoardHistoryNode liveAtSameMove = liveByMove.get(old.getData().moveNumber);
      if (liveAtSameMove == null) continue;
      for (int i = 1; i < old.numberOfChildren(); i++) {
        BoardHistoryNode branch = old.getVariation(i).orElse(null);
        if (branch != null) branch.reparentAsLastVariationOf(liveAtSameMove);
      }
    }
  }

  private BoardData cloneCurrentBoardPosition() {
    if (Lizzie.board == null
        || Lizzie.board.getHistory() == null
        || Lizzie.board.getData() == null) {
      return null;
    }
    return Lizzie.board.getData().clone();
  }

  /** 用户当前是否在试下变化：head 不在主线，或在主线但已往前翻过棋。试下时直接 setHistory 会拉走视图，所以走 append 路径。 */
  private boolean isUserExploringVariation() {
    BoardHistoryList current = Lizzie.board.getHistory();
    if (current == null) return false;
    BoardHistoryNode head = current.getCurrentHistoryNode();
    if (head == null) return false;
    if (!head.isMainTrunk()) return true;
    return head.next(true).isPresent();
  }

  /** 试下时不动 head，只把 liveNode 主线上多出的手数克隆挂到现有主线末尾，用户回到末尾就能看到新走子。 */
  private void appendNewMainlineMovesPreservingHead(BoardHistoryList liveNode) {
    BoardHistoryList current = Lizzie.board.getHistory();
    if (current == null || liveNode == null) return;
    BoardHistoryNode curHead = current.getCurrentHistoryNode();
    if (curHead == null) return;
    BoardHistoryNode tail = curHead.getLast();
    int existingTailMove = tail.getData().moveNumber;
    BoardHistoryNode liveTail = liveNode.getCurrentHistoryNode();
    while (liveTail != null && liveTail.getData().moveNumber > existingTailMove + 1) {
      liveTail = liveTail.previous().orElse(null);
    }
    java.util.Deque<BoardHistoryNode> toAppend = new java.util.ArrayDeque<>();
    for (BoardHistoryNode c = liveTail;
        c != null && c.getData().moveNumber > existingTailMove;
        c = c.previous().orElse(null)) {
      toAppend.push(c);
    }
    for (BoardHistoryNode src : toAppend) {
      BoardHistoryNode newNode = new BoardHistoryNode(src.getData().clone());
      newNode.reparentAsFirstVariationOf(tail);
      tail = newNode;
    }
  }

  public void get() throws IOException {
    new Thread() {
      public void run() {
        try {
          URL url = URI.create(ajaxUrl).toURL();
          HttpURLConnection con = (HttpURLConnection) url.openConnection();

          con.setRequestMethod("GET");
          con.setRequestProperty(
              "User-Agent",
              "Mozilla/5.0 (Linux; U; Android 2.3.6; zh-cn; GT-S5660 Build/GINGERBREAD) AppleWebKit/533.1 (KHTML, like Gecko) Version/4.0 Mobile Safari/533.1 MicroMessenger/4.5.255");
          con.setConnectTimeout(10 * 1000);
          con.setReadTimeout(10 * 1000);
          con.getResponseCode();

          BufferedReader in =
              new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"));
          StringBuffer response = new StringBuffer();
          String line;
          while ((line = in.readLine()) != null) {
            response.append(line);
            response.append((char) 10);
          }
          in.close();
          String sgf = response.toString();
          parseSgf(sgf, "", 0, false, true);
        } catch (IOException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }
    }.start();
  }

  private void reqNewYikeRoom(boolean clear) {
    YikeSyncDebugLog.log(
        "OnlineDialog.reqNewYikeRoom clear=" + clear + " ajaxUrl=" + ajaxUrl + " roomId=" + roomId);
    if (clear) Lizzie.board.clearForOnline();
    ensureOnlineExecutor();
    final String sourceUrl = currentYikeSourceUrl();
    Runnable fetch =
        new Runnable() {
          @Override
          public void run() {
            if (isStoped || !LizzieFrame.urlSgf) return;
            try {
              YikeSyncDebugLog.log("OnlineDialog.reqNewYikeRoom fetch start");
              String response = yikeApiClient.fetch(ajaxUrl);
              YikeApiClient.YikeLiveDetail detail = YikeApiClient.parseLiveDetail(response);
              YikeSyncDebugLog.log(
                  "OnlineDialog.reqNewYikeRoom fetched status="
                      + detail.getStatus()
                      + " sgfLen="
                      + (detail.getSgf() == null ? -1 : detail.getSgf().length()));
              if (Utils.isBlank(detail.getSgf())) {
                throw new IOException(text("YikeLiveDialog.syncNoSgf", "No SGF is available yet."));
              }
              String mainlineSgf = YikeSgfMainline.withoutVariations(detail.getSgf());
              boolean hasNewMoves = !mainlineSgf.equals(lastYikeMainlineSgf);
              YikeSyncDebugLog.log("OnlineDialog.reqNewYikeRoom hasNewMoves=" + hasNewMoves);
              lastYikeMainlineSgf = mainlineSgf;
              if (!hasNewMoves) {
                updateYikeSyncStatus(
                    sourceUrl,
                    detail.getStatus() >= 3
                        ? text("YikeLiveDialog.syncedFinished", "Synced. This game has finished.")
                        : text("YikeLiveDialog.syncedWatching", "Synced. Watching for updates."));
                if (detail.getStatus() >= 3
                    && schedule != null
                    && !schedule.isCancelled()
                    && !schedule.isDone()) {
                  schedule.cancel(false);
                }
                return;
              }
              parseSgf(response, "", 0, false, firstTime);
              updateYikeSyncStatus(
                  sourceUrl,
                  detail.getStatus() >= 3
                      ? text("YikeLiveDialog.syncedFinished", "Synced. This game has finished.")
                      : text("YikeLiveDialog.syncedWatching", "Synced. Watching for updates."));
              if (detail.getStatus() >= 3
                  && schedule != null
                  && !schedule.isCancelled()
                  && !schedule.isDone()) {
                schedule.cancel(false);
              }
            } catch (IOException | JSONException e) {
              YikeSyncDebugLog.log("OnlineDialog.reqNewYikeRoom error: " + e.toString());
              e.printStackTrace();
              SwingUtilities.invokeLater(
                  new Runnable() {
                    @Override
                    public void run() {
                      updateYikeSyncStatus(
                          sourceUrl,
                          text("YikeLiveDialog.syncFailed", "Yike sync failed.")
                              + ": "
                              + e.getMessage());
                      error(true);
                    }
                  });
            }
          }
        };
    online.execute(fetch);
    schedule =
        online.scheduleWithFixedDelay(
            fetch, Math.max(refreshTime, 5), Math.max(refreshTime, 5), TimeUnit.SECONDS);
  }

  public void refresh(String format) throws IOException {
    refresh(format, 2, true, false);
  }

  public void refresh(String format, int num, boolean needSchedule, boolean decode)
      throws IOException {
    Map params = new HashMap();
    final AjaxHttpRequest ajax = new AjaxHttpRequest();

    ajax.setReadyStateChangeListener(
        new AjaxHttpRequest.ReadyStateChangeListener() {
          public void onReadyStateChange() {
            int readyState = ajax.getReadyState();
            if (readyState == AjaxHttpRequest.STATE_COMPLETE) {
              String sgf = ajax.getResponseText();
              parseSgf(sgf, format, num, decode, firstTime);
            }
          }
        });

    if (needSchedule && !isStoped && type == 101) { // 弈客暂时不需要刷新了
      timer =
          new Timer(
              refreshTime * 1000,
              new ActionListener() {
                public void actionPerformed(ActionEvent evt) {
                  if (!LizzieFrame.urlSgf) {
                    timer.stop();
                    ajax.abort();
                  } else {
                    try {
                      ajax.open("GET", ajaxUrl, true);
                      ajax.send(params);
                    } catch (IOException e) {
                      e.printStackTrace();
                    }
                  }
                }
              });
      timer.start();
    } else {
      try {
        ajax.open("GET", ajaxUrl, true);
        ajax.send(params);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  public void getSgf(String chessid) {
    if (!Utils.isBlank(chessid)) {
      ajaxUrl = new String(b3) + chessid;
      try {
        refresh("(jQuery1\\(\\\")([^\\\"]+)(?s).*", 2, false, true);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  public void req0() throws IOException {
    Map params = new HashMap();
    final AjaxHttpRequest ajax = new AjaxHttpRequest();

    ajax.setReadyStateChangeListener(
        new AjaxHttpRequest.ReadyStateChangeListener() {
          @SuppressWarnings("unchecked")
          public void onReadyStateChange() {
            int readyState = ajax.getReadyState();
            if (readyState == AjaxHttpRequest.STATE_COMPLETE) {
              String format = "jQuery[^\\(]*\\(((?s).*?)\\)";
              Pattern sp = Pattern.compile(format);
              Matcher sm = sp.matcher(ajax.getResponseText());
              if (sm.matches() && sm.groupCount() == 1) {
                JSONObject o = new JSONObject(sm.group(1));
                if (0 == o.optInt("result") && 0 == o.optInt("ResultID")) {
                  chineseRule = 0;
                }
                List list = new ArrayList();
                list.add("369");
                queryMap.put("gameid", list);
                list = new ArrayList();
                list.add(o.optString("ShowType"));
                queryMap.put("showtype", list);
                list = new ArrayList();
                list.add(o.optString("ShowID"));
                queryMap.put("showid", list);
                list = new ArrayList();
                list.add(o.optString("CreateTime"));
                queryMap.put("createtime", list);

                try {
                  req(true);
                } catch (URISyntaxException e) {
                  e.printStackTrace();
                }
              }
            }
          }
        });

    try {
      ajax.open("GET", ajaxUrl, true);
      ajax.send(params);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void reReq() {
    try {
      req(true);
    } catch (URISyntaxException e) {
      e.printStackTrace();
    }
  }

  private void req(boolean clear) throws URISyntaxException {
    seqs = 0;
    URI uri = new URI(new String(type == 3 ? b : b2));

    if (clear) Lizzie.board.clearForOnline();
    if (client != null && client.isOpen()) {
      client.close();
    }
    client =
        new WebSocketClient(uri) {

          public void onOpen(ServerHandshake arg0) {
            byte[] req1 =
                req1(
                    90,
                    ++seqs,
                    23406,
                    Utils.intOfMap(queryMap, "gameid"),
                    Utils.intOfMap(queryMap, "showtype"),
                    Utils.intOfMap(queryMap, "showid"),
                    Utils.intOfMap(queryMap, "createtime"));
            client.send(req1);
          }

          public void onMessage(String arg0) {
            // System.out.println("socket message" + arg0);
          }

          public void onError(Exception arg0) {
            // arg0.printStackTrace();
            // System.out.println("socket error");
          }

          public void onClose(int arg0, String arg1, boolean arg2) {
            // System.out.println("socket close:" + arg0 + ":" + arg1 + ":" + arg2);
          }

          public void onMessage(ByteBuffer bytes) {
            // System.out.println("socket message ByteBuffer" +
            // byteArrayToHexString(bytes.array()));
            if (!isStoped) parseReq(bytes);
          }
        };

    client.connect();
  }

  public byte[] req1(int len, int seq, int msgID, int gameId, int showType, int showId, int time) {
    ByteBuffer bytes = ByteBuffer.allocate(len);
    bytes.putShort((short) len);
    bytes.putShort((short) 1);
    bytes.putInt(seq);
    bytes.putShort((short) -4);
    bytes.putInt(50000);
    bytes.put((byte) 0);
    bytes.put((byte) 0);
    bytes.putShort((short) msgID);
    bytes.putShort((short) 0);
    bytes.putInt(1000);
    bytes.put((byte) 0);
    bytes.put((byte) 234);
    bytes.putShort((short) 0);
    bytes.putShort((short) 0);
    bytes.putShort((short) 60);
    bytes.putInt(0);
    bytes.putInt(gameId);
    bytes.putInt(showType);
    bytes.putInt(showId);
    bytes.putInt(0);
    bytes.putInt(-1);
    bytes.putInt(0);
    bytes.putShort((short) 0);
    bytes.putInt(3601);
    bytes.putInt(time);
    bytes.putInt(0);
    bytes.putInt(0);
    bytes.putInt(0);
    bytes.putInt(0);
    bytes.putInt(1);
    return bytes.array();
  }

  public byte[] req2(int len, int seq, int msgID, int gameId, int showType, int showId, int time) {
    ByteBuffer bytes = ByteBuffer.allocate(len);
    bytes.putShort((short) len);
    bytes.putShort((short) 1);
    bytes.putInt(seq);
    bytes.putShort((short) -4);
    bytes.putInt(50000);
    bytes.put((byte) 0);
    bytes.put((byte) 0);
    bytes.putShort((short) msgID);
    bytes.putShort((short) 0);
    bytes.putInt(1000);
    bytes.put((byte) 0);
    bytes.put((byte) 234);
    bytes.putShort((short) 0);
    bytes.putShort((short) 0);
    bytes.putShort((short) 24);
    bytes.putInt(0);
    bytes.putInt(gameId);
    bytes.putInt(showType);
    bytes.putInt(showId);
    bytes.putShort((short) 0);
    bytes.putInt(3601);
    return bytes.array();
  }

  public void parseReq(ByteBuffer res) {
    res.get();
    res.get();
    int msgID = res.getShort();
    // System.out.println("recv msgID:" + msgID);
    if (msgID == 23406) {
      res.getShort();
      res.getInt();
      res.get();
      res.get();
      res.getShort();
      res.getShort();
      res.getShort();

      int resultId = res.getInt();
      res.getInt();
      res.getInt();
      res.getInt();
      res.getInt();
      int showFragmentNum = res.getInt();
      List<Fragment> fragmentList = new ArrayList<Fragment>();
      if (showFragmentNum > 0) {
        for (int i = 0; i < showFragmentNum; i++) {
          int len = res.getShort();
          byte[] frag = new byte[len];
          res.get(frag, res.arrayOffset(), len);
          fragmentList.add(new Fragment(len, frag));
        }

        processFrag(fragmentList);
      }

      if (resultId == 23409 || resultId == 23412) {
        done = true;
        getSgf(Utils.stringOfMap(queryMap, "chessid"));
      }

      res.getInt();
      res.getInt();
      res.getInt();
      int transparentLen = res.getShort();
      // TODO
      if (transparentLen > 0) {
        // Transparent
      } else {
        res.get();
      }
      if (type == 3) {
        res.getInt();
        res.getInt();
        res.getInt();
      }

      if (!done && (schedule == null || schedule.isCancelled() || schedule.isDone())) {
        schedule =
            online.scheduleAtFixedRate(
                new Runnable() {
                  @Override
                  public void run() {
                    if (!LizzieFrame.urlSgf) {
                      online.shutdown();
                      schedule.cancel(true);
                      return;
                    }
                    if (client.isOpen()) {
                      byte[] req2 =
                          req2(
                              54,
                              ++seqs,
                              23413,
                              Utils.intOfMap(queryMap, "gameid"),
                              Utils.intOfMap(queryMap, "showtype"),
                              Utils.intOfMap(queryMap, "showid"),
                              Utils.intOfMap(queryMap, "createtime"));
                      client.send(req2);
                    } else {
                      schedule.cancel(false);
                      if (!done) {
                        reReq();
                      }
                    }
                  }
                },
                1,
                refreshTime,
                TimeUnit.SECONDS);
      }
    } else if (msgID == 23407) {
      res.getShort();
      res.getInt();
      res.get();
      res.get();
      res.getShort();
      res.getShort();
      res.getShort();

      res.getInt();
      res.getInt();
      res.getInt();
      res.getInt();
      int showFragmentNum = res.getInt();
      List<Fragment> fragmentList = new ArrayList<Fragment>();
      if (showFragmentNum > 0) {
        for (int i = 0; i < showFragmentNum; i++) {
          int len = res.getShort();
          byte[] frag = new byte[len];
          res.get(frag, res.arrayOffset(), len);
          fragmentList.add(new Fragment(len, frag));
        }
        processFrag(fragmentList);
      }

    } else if (msgID == 23413) {
      res.getShort();
      res.getInt();
      res.get();
      res.get();
      res.getShort();
      res.getShort();
      res.getShort();

      res.getInt();
      res.getInt();
      res.getInt();
      res.getInt();
      res.getInt();
      res.getInt();
      int tipsLen = res.getInt();
      if (tipsLen > 0) {
        for (int i = 0; i < tipsLen; i++) {
          int len = res.getShort();
          byte[] tips = new byte[len];
          res.get(tips, res.arrayOffset(), len);
          // TODO
        }
      }
      res.getInt();
      int transparentLen = res.getShort();
      // TODO
      if (transparentLen > 0) {
        // Transparent
      }
      if (type == 3) {
        res.getInt();
        res.getInt();
        res.getInt();
      }
    } else if (msgID == 23414) {
      res.getShort();
      res.getInt();
      res.get();
      res.get();
      res.getShort();
      res.getShort();
      res.getShort();

      res.getInt();
      res.getInt();
      res.getInt();
      res.getInt();
      res.getInt();
      int transparentDataLen = res.getShort();
      List<Fragment> fragmentList = new ArrayList<Fragment>();
      if (transparentDataLen > 0) {
        byte[] frag = new byte[transparentDataLen];
        res.get(frag, res.arrayOffset(), transparentDataLen);
        fragmentList.add(new Fragment(transparentDataLen, frag));
        processFrag(fragmentList);
      }
    }
  }

  private void processFrag(List<Fragment> fragmentList) {

    for (Fragment f : fragmentList) {
      if (f != null) {
        // System.out.println("Msg:" + f.type + ":" + (f.line != null ?
        // f.line.toString() :
        // ""));
        if (f.type == 20032) {
          int size = ((JSONObject) f.line.opt("AAA307")).optInt("AAA16");
          int handicap = ((JSONObject) f.line.opt("AAA307")).optInt("AAA4");
          if (size > 0) {
            boardSize = size;
            onYikeBoardSizeResolved();
            Lizzie.board.reopen(boardSize, boardSize);
            history = new BoardHistoryList(BoardData.empty(size, size)); // TODO boardSize
            JSONObject a309 = ((JSONObject) f.line.opt("AAA309"));
            blackPlayer =
                a309 == null
                    ? ""
                    : ("86".equals(a309.optString("AAA227"))
                        ? a309.optString("AAA225")
                        : a309.optString("AAA224"));
            JSONObject a308 = ((JSONObject) f.line.opt("AAA308"));
            whitePlayer =
                a308 == null
                    ? ""
                    : ("86".equals(a308.optString("AAA227"))
                        ? a308.optString("AAA225")
                        : a308.optString("AAA224"));
            Lizzie.frame.setPlayers(whitePlayer, blackPlayer);
            Lizzie.board.getHistory().getGameInfo().setPlayerBlack(blackPlayer);
            Lizzie.board.getHistory().getGameInfo().setPlayerWhite(whitePlayer);
            if (handicap > 1) Lizzie.board.getHistory().getGameInfo().setHandicap(handicap);

            if (size == 19) {
              switch (handicap) {
                case 2:
                  history.place(15, 3, Stone.BLACK, false, false);
                  history.place(3, 15, Stone.BLACK, false, false);
                  break;
                case 3:
                  history.place(3, 3, Stone.BLACK);
                  history.place(15, 3, Stone.BLACK);
                  history.place(3, 15, Stone.BLACK);
                  break;
                case 4:
                  history.place(3, 3, Stone.BLACK);
                  history.place(3, 15, Stone.BLACK);
                  history.place(15, 3, Stone.BLACK);
                  history.place(15, 15, Stone.BLACK);
                  break;
                case 5:
                  history.place(3, 3, Stone.BLACK);
                  history.place(3, 15, Stone.BLACK);
                  history.place(15, 3, Stone.BLACK);
                  history.place(15, 15, Stone.BLACK);
                  history.place(9, 9, Stone.BLACK);
                  break;
                case 6:
                  history.place(3, 3, Stone.BLACK);
                  history.place(3, 15, Stone.BLACK);
                  history.place(15, 3, Stone.BLACK);
                  history.place(15, 15, Stone.BLACK);
                  history.place(3, 9, Stone.BLACK);
                  history.place(15, 9, Stone.BLACK);
                  break;
                case 7:
                  history.place(3, 3, Stone.BLACK);
                  history.place(3, 15, Stone.BLACK);
                  history.place(15, 3, Stone.BLACK);
                  history.place(15, 15, Stone.BLACK);
                  history.place(3, 9, Stone.BLACK);
                  history.place(15, 9, Stone.BLACK);
                  history.place(9, 9, Stone.BLACK);
                  break;
                case 8:
                  history.place(3, 3, Stone.BLACK);
                  history.place(3, 15, Stone.BLACK);
                  history.place(15, 3, Stone.BLACK);
                  history.place(15, 15, Stone.BLACK);
                  history.place(9, 3, Stone.BLACK);
                  history.place(9, 15, Stone.BLACK);
                  history.place(3, 9, Stone.BLACK);
                  history.place(15, 9, Stone.BLACK);
                  break;
                case 9:
                  history.place(3, 3, Stone.BLACK);
                  history.place(3, 15, Stone.BLACK);
                  history.place(15, 3, Stone.BLACK);
                  history.place(15, 15, Stone.BLACK);
                  history.place(9, 3, Stone.BLACK);
                  history.place(9, 15, Stone.BLACK);
                  history.place(3, 9, Stone.BLACK);
                  history.place(15, 9, Stone.BLACK);
                  history.place(9, 9, Stone.BLACK);
                  break;
              }
            }
            double komi = Lizzie.board.getHistory().getGameInfo().getKomi();
            int a4 = ((JSONObject) f.line.opt("AAA307")).optInt("AAA4");
            int a5 = ((JSONObject) f.line.opt("AAA307")).optInt("AAA5");
            int a10 = ((JSONObject) f.line.opt("AAA307")).optInt("AAA10");
            if (0 == a4 && 0 == a5) {
              komi = 6.5;
            } else if (1 == a10 && 1 == chineseRule) {
              chineseFlag = true;
              komi = ((double) a5 / 100 * 2);
            } else {
              komi = ((double) a5 / 100);
            }
            if (Lizzie.config.readKomi) {
              Lizzie.board.getHistory().getGameInfo().setKomi(komi);
              Lizzie.leelaz.komi(komi);
            }
          } else {
            break;
          }
        } else if (f.type == 4116) {
          long tu = wuid;
          wuid = buid;
          buid = tu;
          String tt = wTime;
          wTime = bTime;
          bTime = tt;
          String t = whitePlayer;
          whitePlayer = blackPlayer;
          blackPlayer = t;
          Lizzie.frame.setPlayers(whitePlayer, blackPlayer);
          Lizzie.board.getHistory().getGameInfo().setPlayerBlack(blackPlayer);
          Lizzie.board.getHistory().getGameInfo().setPlayerWhite(whitePlayer);
        } else if (f.type == 7005) {
          long uid = f.line.optLong("AAA303");
          int handicap = Lizzie.board.getHistory().getGameInfo().getHandicap();
          int num = f.line.optInt("AAA102") + handicap;
          if (num == 0) {
            num = history.getData().moveNumber + 1;
          }
          //          Stone color;
          //          if (handicap > 0)
          //            color = history.getLastMoveColor() == Stone.WHITE ? Stone.BLACK :
          // Stone.WHITE;
          //          else color = (num % 2 != 0) ? Stone.BLACK : Stone.WHITE;
          //          //    Stone color = ((f.line.optInt("AAA158") & 3) == 1) ? Stone.WHITE :
          // Stone.BLACK;
          //          if (uid > 0) {
          //            if (Stone.BLACK.equals(color)) {
          //              buid = uid;
          //            } else {
          //              wuid = uid;
          //            }
          //          }
          Stone color = ((f.line.optInt("AAA158") & 3) == 1) ? Stone.WHITE : Stone.BLACK;
          if (uid > 0) {
            if (Stone.BLACK.equals(color)) {
              buid = uid;
            } else {
              wuid = uid;
            }
          }
          int index = f.line.optInt("AAA106");
          int[] coord = asCoord(Stone.BLACK.equals(color) ? index : index - 1024);
          boolean changeMove = false;

          if (num <= history.getMoveNumber()) {
            int cur = history.getMoveNumber();
            for (int i = num; i <= cur; i++) {
              BoardHistoryNode currentNode = history.getCurrentHistoryNode();
              boolean isSameMove = (i == cur && currentNode.getData().isSameCoord(coord));
              if (currentNode.previous().isPresent()) {
                BoardHistoryNode pre = currentNode.previous().get();
                history.previous();
                if (pre.numberOfChildren() <= 1 && !isSameMove) {
                  int idx = pre.indexOfNode(currentNode);
                  pre.deleteChild(idx);
                  changeMove = false;
                } else {
                  changeMove = true;
                }
              }
            }
          }

          if (coord == null || !Board.isValid(coord)) {
            history.pass(color, false, false);
          } else {
            history.place(coord[0], coord[1], color, false, changeMove);
          }
        } else if (f.type == 7045) {
          Stone color = history.getLastMoveColor() == Stone.WHITE ? Stone.BLACK : Stone.WHITE;
          history.pass(color, false, false);
        } else if (f.type == 7198) {
          long uid = f.line.optLong("AAA303");
          int time = f.line.optInt("AAA196");
          int readCount = f.line.optInt("AAA197");
          int readTime = f.line.optInt("AAA198");
          if (uid > 0) {
            if (uid == buid) {
              bTime =
                  String.format(
                      "%d:%02d %d %d", (int) (time / 60), (int) (time % 60), readCount, readTime);
            } else {
              wTime =
                  String.format(
                      "%d:%02d %d %d", (int) (time / 60), (int) (time % 60), readCount, readTime);
            }
          }
          // Lizzie.frame.updateBasicInfo(bTime, wTime);
        } else if (f.type == 8005) {
          int num = f.line.optInt("AAA72");
          String comment = f.line.optString("AAA37");
          if (num > 0 && !Utils.isBlank(comment)) {
            history.goToMoveNumber(num, false);
            history.getData().comment += comment + "\n";
            while (history.next(true).isPresent())
              ;
          }
        } else if (f.type == 8185) {
          JSONObject branch = (JSONObject) f.line.opt("AAA79");
          if (branch != null) {
            int moveNum = branch.optInt("AAA20") - 1;
            if (moveNum > 0) {
              history.goToMoveNumber(moveNum, false);
              String branchCmt = branch.optString("AAA283");
              JSONArray branchMoves = branch.optJSONArray("AAA106");
              if (branchMoves != null && branchMoves.length() > 0) {
                if (history.getCurrentHistoryNode().numberOfChildren() == 0) {
                  // BoardData data = BoardData.empty(boardSize);
                  // data.moveMNNumber = history.getData().moveMNNumber + 1;
                  // data.moveNumber = history.getData().moveNumber + 1;
                  // history.getCurrentHistoryNode().addOrGoto(data);
                  Stone color =
                      history.getLastMoveColor() == Stone.WHITE ? Stone.BLACK : Stone.WHITE;
                  history.pass(color, false, true);
                  history.previous();
                }
                for (int i = 0; i < branchMoves.length(); i++) {
                  Stone color =
                      history.getLastMoveColor() == Stone.WHITE ? Stone.BLACK : Stone.WHITE;
                  int index = branchMoves.getInt(i);
                  int[] coord = asCoord(Stone.BLACK.equals(color) ? index : index - 1024);
                  history.place(coord[0], coord[1], color, i == 0);
                  if (i == 0) {
                    history.getData().comment += branchCmt + "\n";
                  }
                }
                history.toBranchTop();
                while (history.next(true).isPresent())
                  ;
              }
            }
          }
        } else if (f.type == 7185 || f.type == 7186) {
          done = true;
          if (schedule != null && !schedule.isCancelled() && !schedule.isDone()) {
            schedule.cancel(false);
          }
          if (client != null && client.isOpen()) {
            client.close();
          }
          String result = result(f.type, f.line);
          while (history.next().isPresent())
            ;
          history.getEnd().getData().comment = result + "\n" + history.getEnd().getData().comment;
          Lizzie.frame.setResult(result);
          Lizzie.board.getHistory().getGameInfo().setResult(result);
        }
      }
    }
    if (history != null) {
      while (history.previous().isPresent())
        ;
      int diffMove = Lizzie.board.getHistory().sync(history);
      if (diffMove >= 0) {
        //     Lizzie.board.goToMoveNumberBeyondBranch(diffMove > 0 ? diffMove - 1 : 0);
        //    while (Lizzie.board.nextMove()) {}
      }
      while (history.next(true).isPresent())
        ;
    }
  }

  private String decimalToFraction(double e) {
    if (e == 0.0) return "";
    int c = 0;
    int b = 10;
    while (e != Math.floor(e)) {
      e *= b;
      c++;
    }
    b = (int) Math.pow(b, c);
    int nor = (int) e;
    int gcd = gcd(nor, b);

    return String.valueOf(nor / gcd) + "/" + String.valueOf(b / gcd);
  }

  private int gcd(int a, int b) {
    if (a == 0) {
      return b;
    }
    return gcd(b % a, a);
  }

  private String result(long type, JSONObject i) {
    String F = "";
    if (type == 7185) {
      if (i.optDouble("AAA167") > 0) {
        double w = i.optDouble("AAA167") / 100;
        if (1 == i.optInt("AAA166")) {
          int I = (int) w;
          double b = w - I;
          String C = decimalToFraction(b);
          F =
              chineseFlag
                  ? (0 != I ? "黑胜" + I + (Utils.isBlank(C) ? "" : "又" + C) + "子" : "黑胜" + C + "子")
                  : "黑胜" + w + "目";
        } else if (2 == i.optInt("AAA166")) {
          int E = (int) w;
          double d = w - E;
          String D = decimalToFraction(d);
          F =
              chineseFlag
                  ? (0 != E ? "白胜" + E + (Utils.isBlank(D) ? "" : "又" + D) + "子" : "白胜" + D + "子")
                  : "白胜" + w + "目";
        } else {
          F = "和棋";
        }
      } else {
        F = (1 == i.optInt("AAA166") ? "黑中盘胜" : (2 == i.optInt("AAA166") ? "白中盘胜" : "和棋"));
      }
    } else if (type == 7186) {
      F = "";
      if (i.optDouble("AAA167") > 0) {
        String[] w = String.valueOf(i.optDouble("AAA167") / 100).split(".");
        String w1 = w.length >= 2 ? "半" : "";
        F =
            (1 == i.optInt("AAA166")
                ? "黑" + w[0] + "目" + w1 + "胜"
                : (2 == i.optInt("AAA166") ? "白" + w[0] + "目" + w1 + "胜" : "和棋"));
      } else {
        F = (1 == i.optInt("AAA166") ? "黑中盘胜" : (2 == i.optInt("AAA166") ? "白中盘胜" : "和棋"));
      }
    }
    return F;
  }

  private int[] asCoord(int index) {
    int[] coord = new int[2];
    if (index >= 1024) {
      int i = index - 1024;
      coord[0] = i % 32;
      coord[1] = i / 32;
    }
    return coord;
  }

  private class Fragment {
    public long type;
    public JSONObject line;

    public Fragment(int len, byte[] frag) {
      Proto o = parseProto(frag);
      // System.out.println("type:" + o.type);
      // System.out.println("raw:" + byteArrayToHexString(o.raw));
      this.type = o.type;
      if (o.type == 20032) {
        line = decode52(ByteBuffer.wrap(o.raw));
      } else if (o.type == 4116) {
        line = decode53(ByteBuffer.wrap(o.raw));
      } else if (o.type == 7005) {
        line = decode20(ByteBuffer.wrap(o.raw));
      } else if (o.type == 8005) {
        line = decode7(ByteBuffer.wrap(o.raw));
      } else if (o.type == 7025) {
        // TODO AA23
        o.type = 0;
      } else if (o.type == 8185) {
        line = decode17(ByteBuffer.wrap(o.raw));
      } else if (o.type == 7185) {
        line = decode35(ByteBuffer.wrap(o.raw));
      } else if (o.type == 7198) {
        line = decode42(ByteBuffer.wrap(o.raw));
      }
    }

    private JSONObject decode52(ByteBuffer buf) {
      JSONObject m = new JSONObject();
      while (buf.position() < buf.array().length) {
        long t = uint32(buf);
        t = t >>> 3;
        if (t == 1) {
          m.put("AAA311", uint32(buf));
        } else if (t == 2) {
          m.put("AAA303", uint64(buf));
        } else if (t == 3) {
          m.put("AAA312", uint32(buf));
        } else if (t == 4) {
          m.put("AAA305", uint64(buf));
        } else if (t == 5) {
          m.put("AAA306", uint64(buf));
        } else if (t == 6) {
          int len = (int) uint32(buf);
          byte[] newB = new byte[len];
          buf.get(newB, 0, len);
          m.put("AAA307", decode1(ByteBuffer.wrap(newB)));
        } else if (t == 7) {
          int len = (int) uint32(buf);
          byte[] newB = new byte[len];
          buf.get(newB, 0, len);
          m.put("AAA308", decode48(ByteBuffer.wrap(newB)));
        } else if (t == 8) {
          int len = (int) uint32(buf);
          byte[] newB = new byte[len];
          buf.get(newB, 0, len);
          m.put("AAA309", decode48(ByteBuffer.wrap(newB)));
        } else if (t == 9) {
          m.put("AAA310", uint64(buf));
        } else {
          // TODO
          break;
          // skipType(buf, (int) (t & 7));
        }
      }
      return m;
    }

    private JSONObject decode1(ByteBuffer buf) {
      JSONObject m = new JSONObject();
      while (buf.position() < buf.array().length) {
        long tl = uint32(buf);
        int t = (int) (tl >>> 3);
        switch (t) {
          case 1:
            m.put("AAA1", uint64(buf));
            break;
          case 2:
            m.put("AAA2", uint64(buf));
            break;
          case 3:
            m.put("AAA3", uint32(buf));
            break;
          case 4:
            m.put("AAA4", uint32(buf));
            break;
          case 5:
            m.put("AAA5", uint32(buf));
            break;
          case 6:
            m.put("AAA6", uint32(buf));
            break;
          case 7:
            m.put("AAA7", uint32(buf));
            break;
          case 8:
            m.put("AAA8", uint32(buf));
            break;
          case 9:
            m.put("AAA9", uint32(buf));
            break;
          case 10:
            m.put("AAA10", uint32(buf));
            break;
          case 11:
            m.put("AAA11", uint32(buf));
            break;
          case 12:
            m.put("AAA12", uint32(buf));
            break;
          case 13:
            m.put("AAA13", uint32(buf));
            break;
          case 14:
            m.put("AAA14", uint32(buf));
            break;
          case 15:
            m.put("AAA15", uint32(buf));
            break;
          case 16:
            m.put("AAA16", uint32(buf));
            break;
          case 17:
            m.put("AAA17", uint32(buf));
            break;
          default:
            // skipType(buf, (int) (t & 7));
            break;
        }
      }
      return m;
    }

    private JSONObject decode48(ByteBuffer buf) {
      JSONObject m = new JSONObject();
      while (buf.position() < buf.array().length) {
        long tl = uint32(buf);
        int t = (int) (tl >>> 3);
        switch (t) {
          case 1:
            m.put("AAA224", readString(buf));
            break;
          case 2:
            m.put("AAA225", readString(buf));
            break;
          case 3:
            m.put("AAA226", uint32(buf));
            break;
          case 4:
            m.put("AAA227", uint32(buf));
            break;
          case 5:
            m.put("AAA228", uint32(buf));
            break;
          case 6:
            m.put("AAA234", uint32(buf));
            break;
          case 7:
            m.put("AAA248", uint32(buf));
            break;
          case 8:
            m.put("AAA249", uint32(buf));
            break;
          case 9:
            m.put("AAA250", uint64(buf));
            break;
          case 10:
            m.put("AAA251", readString(buf));
            break;
          default:
            // skipType(buf, (int) (t & 7));
            break;
        }
      }
      return m;
    }

    private JSONObject decode20(ByteBuffer buf) {
      JSONObject m = new JSONObject();
      while (buf.position() < buf.array().length) {
        long tl = uint32(buf);
        int t = (int) (tl >>> 3);
        switch (t) {
          case 1:
            m.put("AAA311", uint32(buf));
            break;
          case 2:
            m.put("AAA303", uint64(buf));
            break;
          case 3:
            m.put("AAA312", uint32(buf));
            break;
          case 4:
            m.put("AAA106", uint32(buf));
            break;
          case 5:
            m.put("AAA168", uint32(buf));
            break;
          case 6:
            m.put("AAA158", uint32(buf));
            break;
          case 7:
            m.put("AAA109", uint32(buf));
            break;
          case 8:
            m.put("AAA102", uint32(buf));
            break;
          default:
            // r.skipType(t&7)
            // break;
            return m;
        }
      }
      return m;
    }

    private JSONObject decode35(ByteBuffer buf) {
      JSONObject m = new JSONObject();
      while (buf.position() < buf.array().length) {
        long tl = uint32(buf);
        int t = (int) (tl >>> 3);
        switch (t) {
          case 1:
            m.put("AAA311", uint32(buf));
            break;
          case 2:
            m.put("AAA303", uint64(buf));
            break;
          case 3:
            m.put("AAA312", uint32(buf));
            break;
          case 4:
            m.put("AAA166", uint32(buf));
            break;
          case 5:
            m.put("AAA167", (int) uint32(buf));
            break;
          case 6:
            m.put("AAA168", uint32(buf));
            break;
          default:
            // r.skipType(t&7)
            // break;
            return m;
        }
      }
      return m;
    }

    private JSONObject decode53(ByteBuffer buf) {
      JSONObject m = new JSONObject();
      while (buf.position() < buf.array().length) {
        long tl = uint32(buf);
        int t = (int) (tl >>> 3);
        switch (t) {
          case 1:
            m.put("AAA311", uint32(buf));
            break;
          case 2:
            m.put("AAA312", uint32(buf));
            break;
          default:
            // r.skipType(t&7)
            // break;
            return m;
        }
      }
      return m;
    }

    private JSONObject decode7(ByteBuffer buf) {
      JSONObject m = new JSONObject();
      while (buf.position() < buf.array().length) {
        long tl = uint32(buf);
        int t = (int) (tl >>> 3);
        switch (t) {
          case 1:
            m.put("AAA311", uint32(buf));
            break;
          case 2:
            m.put("AAA303", uint64(buf));
            break;
          case 3:
            m.put("AAA312", uint32(buf));
            break;
          case 4:
            m.put("AAA72", uint32(buf));
            break;
          case 5:
            m.put("AAA37", readString(buf));
            break;
          case 6:
            m.put("AAA38", uint32(buf));
            break;
          default:
            // r.skipType(t&7)
            // break;
            return m;
        }
      }
      return m;
    }

    private JSONObject decode17(ByteBuffer buf) {
      JSONObject m = new JSONObject();
      while (buf.position() < buf.array().length) {
        long tl = uint32(buf);
        int t = (int) (tl >>> 3);
        switch (t) {
          case 1:
            m.put("AAA311", uint32(buf));
            break;
          case 2:
            m.put("AAA303", uint64(buf));
            break;
          case 3:
            m.put("AAA312", uint32(buf));
            break;
          case 4:
            m.put("AAA77", uint32(buf));
            break;
          case 5:
            m.put("AAA78", uint32(buf));
            break;
          case 6:
            int len = (int) uint32(buf);
            byte[] newB = new byte[len];
            buf.get(newB, 0, len);
            m.put("AAA79", decode2(ByteBuffer.wrap(newB)));
            break;
          case 7:
            // TODO AAA80
            break;
          default:
            // r.skipType(t&7)
            // break;
            return m;
        }
      }
      return m;
    }

    private JSONObject decode2(ByteBuffer buf) {
      JSONObject m = new JSONObject();
      while (buf.position() < buf.array().length) {
        long tl = uint32(buf);
        int t = (int) (tl >>> 3);
        switch (t) {
          case 1:
            if (m.optJSONArray("AAA106") == null) {
              m.put("AAA106", new JSONArray("[]"));
            }
            m.getJSONArray("AAA106").put(uint32(buf));
            break;
          case 2:
            m.put("AAA19", uint32(buf));
            break;
          case 3:
            m.put("AAA20", uint32(buf));
            break;
          case 4:
            m.put("AAA283", readString(buf));
            break;
          case 5:
            if (m.optJSONArray("AAA37") == null) {
              m.put("AAA37", new JSONArray("[]"));
            }
            m.getJSONArray("AAA37").put(readString(buf));
            break;
          default:
            // r.skipType(t&7)
            // break;
            return m;
        }
      }
      return m;
    }

    public JSONObject decode42(ByteBuffer buf) {
      JSONObject m = new JSONObject();
      while (buf.position() < buf.array().length) {
        long tl = uint32(buf);
        int t = (int) (tl >>> 3);
        switch (t) {
          case 1:
            m.put("AAA311", uint32(buf));
            break;
          case 2:
            m.put("AAA303", uint64(buf));
            break;
          case 3:
            m.put("AAA312", uint32(buf));
            break;
          case 4:
            m.put("AAA196", uint32(buf));
            break;
          case 5:
            m.put("AAA197", uint32(buf));
            break;
          case 6:
            m.put("AAA198", uint32(buf));
            break;
          default:
            // r.skipType(t&7)
            // break;
            return m;
        }
      }
      return m;
    }

    private long uint32(ByteBuffer buf) {
      long i = 0;
      long b = buf.get() & 0xFF;
      i = (127 & b) >>> 0;
      if (b < 128) return i;
      b = buf.get() & 0xFF;
      i = (i | (127 & b) << 7) >>> 0;
      if (b < 128) return i;
      b = buf.get() & 0xFF;
      i = (i | (127 & b) << 14) >>> 0;
      if (b < 128) return i;
      b = buf.get() & 0xFF;
      i = (i | (127 & b) << 21) >>> 0;
      if (b < 128) return i;
      b = buf.get() & 0xFF;
      i = (i | (15 & b) << 28) >>> 0;
      if (b < 128) return i;
      b = buf.get() & 0xFF;
      // TODO
      return i;
    }

    private long uint64(ByteBuffer buf) {
      Uint64 e = u(buf);
      if (e != null && ((e.hi >>> 31) != 0)) {
        long t = 1 + ~e.lo >>> 0;
        long o = ~e.hi >>> 0;
        if (t == 0) {
          o = o + 1 >>> 0;
          return -(t + 4294967296L * o);
        }
        return t;
      }
      return e.lo + 4294967296L * e.hi;
    }

    private Uint64 u(ByteBuffer buf) {
      int t = 0;
      Uint64 e = new Uint64();
      long b = 0;
      if (!(buf.array().length - buf.position() > 4)) {
        for (; t < 3; ++t) {
          if (buf.position() >= buf.array().length) return e;
          b = buf.get() & 0xFF;
          e.lo = (e.lo | (127 & b) << 7 * t) >>> 0;
          if (b < 128) return e;
        }
        b = buf.get() & 0xFF;
        e.lo = (e.lo | (127 & b) << 7 * t) >>> 0;
        return e;
      }
      for (; t < 4; ++t) {
        b = buf.get() & 0xFF;
        e.lo = (e.lo | (127 & b) << 7 * t) >>> 0;
        if (b < 128) return e;
      }
      b = buf.get() & 0xFF;
      e.lo = (e.lo | (127 & b) << 28) >>> 0;
      e.hi = (e.hi | (127 & b) >> 4) >>> 0;
      if (b < 128) return e;
      t = 0;
      if (buf.array().length - buf.position() > 4) {
        for (; t < 5; ++t) {
          b = buf.get() & 0xFF;
          e.hi = (e.hi | (127 & b) << 7 * t + 3) >>> 0;
          if (b < 128) return e;
        }
      } else
        for (; t < 5; ++t) {
          if (buf.position() >= buf.array().length) break;
          b = buf.get() & 0xFF;
          e.hi = (e.hi | (127 & b) << 7 * t + 3) >>> 0;
          if (b < 128) return e;
        }
      // TODO Error
      return e;
    }

    private class Uint64 {
      public long lo = 0;
      public long hi = 0;
    }

    private byte[] bytes(ByteBuffer buf) {
      long e = uint32(buf);
      long t = buf.position();
      long o = t + e;
      if (o > buf.array().length) return null;
      byte[] b = new byte[(int) e];
      for (int i = 0; i < e; i++) {
        b[i] = buf.get();
      }
      return b;
    }

    @SuppressWarnings("unchecked")
    private String readString(ByteBuffer buf) {
      byte[] e = bytes(buf);
      if (e == null || e.length <= 0) return "";
      List<Long> s = new ArrayList();
      StringBuilder i = new StringBuilder();
      int t = 0;
      int o = e.length;
      long r;
      for (; t < o; ) {
        r = e[t++] & 0xFF;
        if (r < 128) {
          s.add(r);
        } else if (r > 191 && r < 224) {
          s.add((31 & r) << 6 | 63 & (e[t++] & 0xFF));
        } else if (r > 239 && r < 365) {
          r =
              ((7 & r) << 18
                      | (63 & (e[t++] & 0xFF)) << 12
                      | (63 & (e[t++] & 0xFF)) << 6
                      | 63 & (e[t++] & 0xFF))
                  - 65536;
          s.add(55296 + (r >> 10));
          s.add(56320 + (1023 & r));
        } else {
          s.add((15 & r) << 12 | (63 & (e[t++] & 0xFF)) << 6 | 63 & (e[t++] & 0xFF));
          // n > 8191;
          for (long l : s) {
            String str = fromCharCode((int) l);
            i.append(str);
          }
          s = new ArrayList();
        }
      }

      if (i.length() == 0 || s.size() > 0) {
        for (long l : s) {
          String str = fromCharCode((int) l);
          i.append(str);
        }
      }

      return i.toString();
    }

    private Proto parseProto(byte[] e) {
      int o = e.length;
      if (o <= 2) return null;
      int r = 0;
      long i = (long) (256 * (e[r] & 0xFF)) + (long) (e[r + 1] & 0xFF);
      r += 2;
      o -= 2;
      long s = 0;
      if (32768 == i) {
        if (o <= 4) return null;
        s =
            (long) (256 * (e[r] & 0xFF) * 256 * 256)
                + (long) (256 * (e[r + 1] & 0xFF) * 256)
                + (long) (256 * (e[r + 2] & 0xFF))
                + (long) ((e[r + 3] & 0xFF));
        r += 4;
        o -= 4;
      } else {
        s = i;
      }
      if (s < 7 || Integer.compareUnsigned((int) s, 0x80000000) >= 0) return null;
      if (o < s) return null;
      long n = (long) (256 * (e[r] & 0xFF)) + (long) (e[r + 1] & 0xFF);
      o -= 2;
      r += 2; // TODO
      o -= 1;
      byte[] a = new byte[e.length - 4 - r - 1];
      for (int p = ++r; p < e.length - 4; p++) a[p - r] = e[p];
      return new Proto(n, a);
    }
  }

  private class Proto {
    public long type;
    public byte[] raw;

    public Proto(long type, byte[] raw) {
      this.type = type;
      this.raw = raw;
      ByteBuffer.wrap(raw);
    }
  }

  public static String byteArrayToHexString(byte[] a) {
    if (a == null) return "null";
    int iMax = a.length - 1;
    if (iMax == -1) return "[]";

    StringBuilder b = new StringBuilder();
    b.append('[');
    for (int i = 0; ; i++) {
      b.append(String.format((a[i] & 0xFF) < 16 ? "0x0%X" : "0x%X", a[i]));
      if (i == iMax) return b.append(']').toString();
      b.append(", ");
    }
  }

  public static byte[] hexStringToByteArray(String s) {
    int len = s.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] =
          (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
    }
    return data;
  }

  public static String fromCharCode(int... codePoints) {
    return new String(codePoints, 0, codePoints.length);
  }

  public Map<String, List<String>> splitQuery(URI uri) {
    if (uri.getQuery() == null) {
      return Collections.emptyMap();
    }
    return Arrays.stream(uri.getQuery().split("&"))
        .map(this::splitQueryParameter)
        .collect(
            Collectors.groupingBy(
                SimpleImmutableEntry::getKey,
                LinkedHashMap::new,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
  }

  public SimpleImmutableEntry<String, String> splitQueryParameter(String it) {
    final int idx = it.indexOf("=");
    final String key = idx > 0 ? it.substring(0, idx) : it;
    final String value = idx > 0 && it.length() > idx + 1 ? it.substring(idx + 1) : null;
    return new SimpleImmutableEntry<>(key, value);
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (!Utils.isBlank(value)) return value;
    }
    return "";
  }

  public void req2(boolean clear) throws URISyntaxException {
    yikeDebugLog("req2 start, clear=" + clear);
    if (clear) Lizzie.board.clearForOnline();
    if (sio != null) {
      sio.close();
    }
    seqs = 0;
    URI uri = new URI(new String(c1));
    yikeDebugLog("req2 uri=" + uri);
    sio = IO.socket(uri);
    sio.on(
            Socket.EVENT_CONNECT,
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                yikeDebugLog("Socket.IO connected");
                login();
              }
            })
        .on(
            Socket.EVENT_MESSAGE,
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:message");
              }
            })
        .on(
            Socket.EVENT_DISCONNECT,
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:disconnect");
              }
            })
        .on(
            Socket.EVENT_ERROR,
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:error");
              }
            })
        .on(
            Socket.EVENT_PING,
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:ping");
              }
            })
        .on(
            Socket.EVENT_PONG,
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:pong");
              }
            })
        .on(
            Socket.EVENT_CONNECT_ERROR,
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                yikeDebugLog(
                    "Socket.IO connect error: "
                        + (args == null || args.length == 0 ? "?" : String.valueOf(args[0])));
              }
            })
        .on(
            Socket.EVENT_CONNECT_TIMEOUT,
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                yikeDebugLog("Socket.IO connect timeout");
              }
            })
        .on(
            Socket.EVENT_CONNECTING,
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:EVENT_CONNECTING");
              }
            })
        .on(
            Socket.EVENT_RECONNECT,
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:EVENT_RECONNECT");
              }
            })
        .on(
            Socket.EVENT_RECONNECT_ATTEMPT,
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:EVENT_RECONNECT_ATTEMPT");
              }
            })
        .on(
            Socket.EVENT_RECONNECT_FAILED,
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:EVENT_RECONNECT_FAILED");
              }
            })
        .on(
            Socket.EVENT_RECONNECT_ERROR,
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:EVENT_RECONNECT_ERROR");
              }
            })
        .on(
            Socket.EVENT_RECONNECTING,
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:EVENT_RECONNECTING");
              }
            })
        .on(
            "heartbeat",
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:heartbeat:" + strJson(args));
              }
            })
        .on(
            "userinfo",
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:userinfo:" + strJson(args));
                // System.out.println(
                // "io:userinfo:userid:"
                // + (args == null || args.length < 1
                // ? ""
                // : ((JSONObject) args[0]).opt("user_id").toString()));
                userId =
                    (args == null || args.length < 1
                        ? userId
                        : ((JSONObject) args[0]).optLong("user_id"));
                entry();
              }
            })
        .on(
            "init",
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                yikeDebugLog(
                    "Socket.IO init event received, args=" + (args == null ? "null" : args.length));
                initData(args == null || args.length < 1 ? null : ((JSONObject) args[0]));
              }
            })
        .on(
            "move",
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:move:" + strJson(args));
                move(args == null || args.length < 1 ? null : (JSONObject) args[0]);
                sync();
                if (shouldMoveForward) {
                  shouldMoveForward = false;
                  Lizzie.frame.lastMove();
                }
              }
            })
        .on(
            "update_game",
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:update_game:" + strJson(args));
                updateGame(args == null || args.length < 1 ? null : (JSONObject) args[0]);
              }
            })
        .on(
            "move_delete",
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:move_delete:" + strJson(args));
                moveDelete();
                // sync();
              }
            })
        .on(
            "comments",
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:comments:" + strJson(args));
                procComments(args == null || args.length < 1 ? null : (JSONObject) args[0]);
                sync();
              }
            })
        .on(
            "notice",
            new Emitter.Listener() {
              @Override
              public void call(Object... args) {
                // System.out.println("io:notice:" + strJson(args));
              }
            });
    sio.connect();
  }

  private void login() {
    JSONObject data = new JSONObject();
    data.put("hall", "1");
    data.put("room", roomId);
    data.put("token", -1);
    data.put("user_id", userId);
    data.put("platform", 3);
    sendData(
        "login",
        data,
        new Ack() {
          @Override
          public void call(Object... args) {
            // entry();
          }
        });
  }

  private void entry() {
    JSONObject data = new JSONObject();
    data.put("hall", "1");
    data.put("room", roomId);
    data.put("platform", 3);
    data.put("user_id", userId);
    sendData(
        "entry_room",
        data,
        new Ack() {
          @Override
          public void call(Object... args) {
            channel();
          }
        });
  }

  private void initData(JSONObject data) {
    yikeDebugLog("initData called, data=" + (data == null ? "null" : "present"));
    if (data == null) return;
    JSONObject info = data.optJSONObject("game_info");
    int size = info.optInt("boardSize", 19);
    boardSize = size;
    onYikeBoardSizeResolved();
    Lizzie.board.reopen(boardSize, boardSize);
    history = new BoardHistoryList(BoardData.empty(size, size)); // TODO boardSize
    blackPlayer = info.optString("blackName");
    whitePlayer = info.optString("whiteName");
    boolean isEnd = !Utils.isBlank(info.optString("resultDesc"));
    String sgf = info.optString("sgf");
    if (shouldSyncYikeMainlineOnly()) {
      sgf = YikeSgfMainline.withoutVariations(sgf);
    }
    history = SGFParser.parseSgf(sgf, true);
    if (history != null) {
      BoardData previousPosition = cloneCurrentBoardPosition();
      int previousBoardWidth = Board.boardWidth;
      int previousBoardHeight = Board.boardHeight;
      double komi = info.optDouble("komi", history.getGameInfo().getKomi());
      int handicap = info.optInt("handicap", history.getGameInfo().getHandicap());
      if (Lizzie.config.readKomi) {
        Lizzie.board.getHistory().getGameInfo().setKomi(komi);
        Lizzie.leelaz.komi(komi);
      }
      Lizzie.board.getHistory().getGameInfo().setHandicap(handicap);
      int diffMove = Lizzie.board.getHistory().sync(history);
      sendYikeContextToReadBoard();
      if (diffMove >= 0) {
        syncYikeAnalysisEngineToCurrentHistory(
            previousPosition, previousBoardWidth, previousBoardHeight);
        //      Lizzie.board.goToMoveNumberBeyondBranch(diffMove > 0 ? diffMove - 1 : 0);
        //      while (Lizzie.board.nextMove()) ;
      }
      if ("3".equals(info.optString("status"))) {
        //   sio.close();
        String result = info.optString("resultDesc");
        if (!Utils.isBlank(result)) {
          Lizzie.board.getHistory().getEnd().getData().comment =
              result + "\n" + Lizzie.board.getHistory().getEnd().getData().comment;
          Lizzie.frame.setResult(result);
          Lizzie.board.getHistory().getGameInfo().setResult(result);
          Lizzie.board.previousMove(false);
          Lizzie.board.nextMove(true);
        }
      }
    }
    if (history == null) {
      //      error(true);
      sio.close();
      if (isEnd && (type == 1 || type == 5)) {
        try {
          refresh("(?s).*?(\\\"Content\\\":\\\")(.+)(\\\",\\\")(?s).*", 2, false, false);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
    Lizzie.frame.setPlayers(whitePlayer, blackPlayer);
    Lizzie.board.getHistory().getGameInfo().setPlayerBlack(blackPlayer);
    Lizzie.board.getHistory().getGameInfo().setPlayerWhite(whitePlayer);
    Lizzie.frame.renderVarTree(0, 0, false, true);
  }

  private void channel() {
    JSONObject data = new JSONObject();
    data.put("hall", "1");
    data.put("room", roomId);
    data.put("platform", 3);
    data.put("channel", "chat_1_" + roomId); // channel);
    sendData(
        "channel/add",
        data,
        new Ack() {
          @Override
          public void call(Object... args) {
            listen();
          }
        });
  }

  private void listen() {
    JSONObject data = new JSONObject();
    data.put("hall", "1");
    data.put("room", roomId);
    data.put("platform", 3);
    sendData(
        "comment/listen",
        data,
        new Ack() {
          @Override
          public void call(Object... args) {
            // System.out.println(
            // "listen callback:"
            // + (args == null || args.length <= 0 ? "null" :
            // args[0].toString()));
            listenBack(args == null || args.length <= 0 ? null : (JSONArray) args[0]);
          }
        });
  }

  private void listenBack(JSONArray data) {
    if (data == null) return;
    for (Object o : data) {
      JSONObject d = (JSONObject) o;
      switch (d.optInt("type", 0)) {
        case 1:
          addBranch(d);
          break;
        case 2:
          try {
            addComment(d, true);
          } catch (Exception ex) {
          }
          break;
      }
    }
    sync();
  }

  void sync() {
    if (history == null) return;
    BoardData previousPosition = cloneCurrentBoardPosition();
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    while (history.previous().isPresent())
      ;
    int diffMove = Lizzie.board.getHistory().sync(history);
    if (diffMove >= 0) {
      syncYikeAnalysisEngineToCurrentHistory(
          previousPosition, previousBoardWidth, previousBoardHeight);
    }
    sendYikeContextToReadBoard();
  }

  public void handleYikeGeometryProbe(String payload) {
    if (Utils.isBlank(payload)) {
      return;
    }
    try {
      JSONObject envelope = new JSONObject(payload);
      String tag = envelope.optString("tag");
      if ("gridProbe".equals(tag)) {
        yikeDebugLog(
            "gridProbe node="
                + envelope.optString("node")
                + " reason="
                + envelope.optString("candidateReason")
                + " rect="
                + envelope.optJSONObject("rect")
                + " bounds="
                + envelope.optJSONObject("bounds")
                + " line="
                + envelope.optJSONObject("line")
                + " structured="
                + envelope.optJSONObject("structured")
                + " svgLines="
                + envelope.optJSONObject("svgLines")
                + " canvasPixels="
                + envelope.optJSONObject("canvasPixels")
                + " wgoInstance="
                + envelope.optJSONObject("wgoInstance")
                + " stones="
                + envelope.optJSONObject("stones")
                + " smallCount="
                + envelope.optInt("smallCount", -1)
                + " sampleNodes="
                + envelope.optJSONArray("sampleNodes")
                + " domDump="
                + envelope.optJSONArray("domDump")
                + " allCanvasSvg="
                + envelope.optJSONArray("allCanvasSvg")
                + " iframes="
                + envelope.optJSONArray("iframes")
                + " centerStack="
                + envelope.optJSONArray("centerStack"));
        return;
      }
      if (!"probe".equals(tag)) {
        return;
      }
      JSONArray candidates = envelope.optJSONArray("candidates");
      JSONObject viewport = envelope.optJSONObject("viewport");
      int viewportWidth = viewport == null ? 0 : (int) Math.round(viewport.optDouble("width", 0d));
      int viewportHeight =
          viewport == null ? 0 : (int) Math.round(viewport.optDouble("height", 0d));
      YikeGeometrySnapshot geometry = pickBestYikeGeometry(candidates);
      if (geometry == null) {
        return;
      }
      if (!isAcceptableYikeGeometry(geometry, viewportWidth, viewportHeight)) {
        yikeDebugLog(
            "rejectYikeGeometry node="
                + geometry.node
                + " reason="
                + geometry.reason
                + " rect="
                + geometry.left
                + ","
                + geometry.top
                + ","
                + geometry.width
                + "x"
                + geometry.height
                + " score="
                + geometry.score
                + " viewport="
                + viewportWidth
                + "x"
                + viewportHeight);
        return;
      }
      geometry = attachExplicitYikeGrid(geometry, envelope.optJSONObject("grid"));
      geometry = normalizeYikeGeometry(geometry);
      lastYikeGeometry = geometry;
      sendYikeGeometryToReadBoard();
    } catch (JSONException e) {
      yikeDebugLog("handleYikeGeometryProbe JSON error: " + e);
    }
  }

  static String describeBestYikeGeometry(JSONArray candidates) {
    YikeGeometrySnapshot geometry = pickBestYikeGeometry(candidates);
    if (geometry == null) {
      return null;
    }
    return geometry.node + "|" + geometry.reason;
  }

  static String describeAcceptedYikeGeometry(
      JSONArray candidates, int viewportWidth, int viewportHeight) {
    YikeGeometrySnapshot geometry = pickBestYikeGeometry(candidates);
    if (!isAcceptableYikeGeometry(geometry, viewportWidth, viewportHeight)) {
      return null;
    }
    return geometry.node + "|" + geometry.reason;
  }

  static String describeAcceptedYikeGeometryCommand(
      JSONArray candidates, JSONObject grid, int viewportWidth, int viewportHeight, int boardSize) {
    YikeGeometrySnapshot geometry = pickBestYikeGeometry(candidates);
    if (!isAcceptableYikeGeometry(geometry, viewportWidth, viewportHeight)) {
      return null;
    }
    geometry = attachExplicitYikeGrid(geometry, grid);
    geometry =
        geometry == null || hasExplicitGrid(geometry)
            ? geometry
            : normalizeYikeBoardRectSnapshot(geometry);
    return buildYikeGeometryCommand(geometry, boardSize);
  }

  private static YikeGeometrySnapshot normalizeYikeBoardRectSnapshot(
      YikeGeometrySnapshot geometry) {
    if (geometry == null) {
      return null;
    }
    Rectangle normalized =
        normalizeYikeBoardRect(geometry.left, geometry.top, geometry.width, geometry.height);
    if (normalized.x == geometry.left
        && normalized.y == geometry.top
        && normalized.width == geometry.width
        && normalized.height == geometry.height) {
      return geometry;
    }
    return new YikeGeometrySnapshot(
        normalized.x,
        normalized.y,
        normalized.width,
        normalized.height,
        geometry.firstX,
        geometry.firstY,
        geometry.cellX,
        geometry.cellY,
        geometry.score,
        geometry.node,
        geometry.reason);
  }

  private static YikeGeometrySnapshot pickBestYikeGeometry(JSONArray candidates) {
    if (candidates == null || candidates.length() <= 0) {
      return null;
    }
    YikeGeometrySnapshot best = null;
    StringBuilder topCandidatesLog = new StringBuilder();
    for (int i = 0; i < candidates.length(); i++) {
      JSONObject candidate = candidates.optJSONObject(i);
      if (candidate == null) {
        continue;
      }
      JSONObject rect = candidate.optJSONObject("rect");
      if (rect == null) {
        continue;
      }
      int left = (int) Math.round(rect.optDouble("left", 0d));
      int top = (int) Math.round(rect.optDouble("top", 0d));
      int width = (int) Math.round(rect.optDouble("width", 0d));
      int height = (int) Math.round(rect.optDouble("height", 0d));
      if (width < 40 || height < 40) {
        continue;
      }
      String node = candidate.optString("node", "");
      String reason = candidate.optString("reason", "");
      int score = scoreYikeGeometryCandidate(candidate, width, height);
      YikeGeometrySnapshot current =
          new YikeGeometrySnapshot(
              left, top, width, height, null, null, null, null, score, node, reason);
      if (i < 8) {
        if (topCandidatesLog.length() > 0) {
          topCandidatesLog.append(" | ");
        }
        topCandidatesLog
            .append("#")
            .append(i + 1)
            .append(" score=")
            .append(score)
            .append(" rect=")
            .append(left)
            .append(",")
            .append(top)
            .append(",")
            .append(width)
            .append("x")
            .append(height)
            .append(" node=")
            .append(node)
            .append(" reason=")
            .append(reason);
      }
      if (best == null || current.score > best.score) {
        best = current;
      } else if (current.score == best.score && width * height > best.width * best.height) {
        best = current;
      }
    }
    if (topCandidatesLog.length() > 0) {
      yikeDebugLog("pickBestYikeGeometry candidates " + topCandidatesLog);
    }
    if (best != null) {
      yikeDebugLog(
          "pickBestYikeGeometry chose node="
              + best.node
              + " reason="
              + best.reason
              + " rect="
              + best.left
              + ","
              + best.top
              + ","
              + best.width
              + "x"
              + best.height
              + " score="
              + best.score);
    }
    return best;
  }

  private static int scoreYikeGeometryCandidate(JSONObject candidate, int width, int height) {
    String node = candidate.optString("node", "");
    String reason = candidate.optString("reason", "");
    int score = 0;
    if (node.contains("#board")) score += 120;
    if (node.contains("wgo-board")) score += 100;
    if (node.contains("wgo-player-board")) score += 90;
    if (node.startsWith("canvas")) score += 110;
    if (node.startsWith("svg")) score += 110;
    if (reason.contains("wgo-instance")) score += 300;
    if (reason.startsWith("selector:")) score += 40;
    if (reason.contains(":inner-media")) score += 95;
    if (reason.contains(":hit")) score += 70;
    if (reason.contains(":content-box")) score -= 60;
    if (node.startsWith("div")
        && reason.startsWith("selector:")
        && !reason.contains(":content-box")
        && !reason.contains(":hit")
        && !reason.contains(":inner-media")) score -= 25;
    int diff = Math.abs(width - height);
    if (diff <= Math.max(12, width / 12)) score += 40;
    else score -= Math.min(60, diff);
    return score;
  }

  private static boolean isAcceptableYikeGeometry(
      YikeGeometrySnapshot geometry, int viewportWidth, int viewportHeight) {
    if (geometry == null) {
      return false;
    }
    if (geometry.score < 60) {
      return false;
    }
    boolean hasBoardSignal = hasBoardSignal(geometry.node, geometry.reason);
    if (geometry.reason.startsWith("square-candidate") && !hasBoardSignal) {
      return false;
    }
    if (viewportWidth > 0 && viewportHeight > 0) {
      double viewportArea = viewportWidth * (double) viewportHeight;
      double geometryArea = geometry.width * (double) geometry.height;
      if (geometryArea >= viewportArea * 0.72d && !hasBoardSignal) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasBoardSignal(String node, String reason) {
    String nodeText = node == null ? "" : node.toLowerCase();
    String reasonText = reason == null ? "" : reason.toLowerCase();
    return nodeText.startsWith("canvas")
        || nodeText.startsWith("svg")
        || nodeText.contains("board")
        || nodeText.contains("goban")
        || nodeText.contains("chess")
        || nodeText.contains("weiqi")
        || reasonText.contains("[class*=board]")
        || reasonText.contains("[id*=board]")
        || reasonText.contains("[class*=goban]")
        || reasonText.contains("[id*=goban]")
        || reasonText.contains("[class*=chess]")
        || reasonText.contains("[class*=weiqi]")
        || reasonText.contains(":content-box")
        || reasonText.contains(":inner-media")
        || reasonText.contains(":hit");
  }

  static Rectangle normalizeYikeBoardRect(int left, int top, int width, int height) {
    int size = Math.min(width, height);
    if (size <= 0) {
      return new Rectangle(left, top, width, height);
    }
    int normalizedLeft = left + Math.max(0, (width - size) / 2);
    int normalizedTop = top + Math.max(0, (height - size) / 2);
    return new Rectangle(normalizedLeft, normalizedTop, size, size);
  }

  private YikeGeometrySnapshot normalizeYikeGeometry(YikeGeometrySnapshot geometry) {
    if (geometry == null) {
      return null;
    }
    if (hasExplicitGrid(geometry)) {
      return geometry;
    }
    Rectangle normalized =
        normalizeYikeBoardRect(geometry.left, geometry.top, geometry.width, geometry.height);
    if (normalized.x == geometry.left
        && normalized.y == geometry.top
        && normalized.width == geometry.width
        && normalized.height == geometry.height) {
      return geometry;
    }
    yikeDebugLog(
        "normalizeYikeGeometry left="
            + geometry.left
            + " top="
            + geometry.top
            + " width="
            + geometry.width
            + " height="
            + geometry.height
            + " => left="
            + normalized.x
            + " top="
            + normalized.y
            + " width="
            + normalized.width
            + " height="
            + normalized.height);
    return new YikeGeometrySnapshot(
        normalized.x,
        normalized.y,
        normalized.width,
        normalized.height,
        geometry.firstX,
        geometry.firstY,
        geometry.cellX,
        geometry.cellY,
        geometry.score,
        geometry.node,
        geometry.reason);
  }

  private static YikeGeometrySnapshot attachExplicitYikeGrid(
      YikeGeometrySnapshot geometry, JSONObject grid) {
    if (geometry == null || grid == null) {
      return geometry;
    }
    Double firstX = optFiniteDouble(grid, "firstX");
    Double firstY = optFiniteDouble(grid, "firstY");
    Double cellX = optFiniteDouble(grid, "cellX");
    Double cellY = optFiniteDouble(grid, "cellY");
    if (firstX == null || firstY == null || cellX == null || cellY == null) {
      return geometry;
    }
    if (cellX <= 0d || cellY <= 0d) {
      return geometry;
    }
    int left = geometry.left;
    int top = geometry.top;
    int width = geometry.width;
    int height = geometry.height;
    Double boardLeft = optFiniteDouble(grid, "boardLeft");
    Double boardTop = optFiniteDouble(grid, "boardTop");
    Double boardWidth = optFiniteDouble(grid, "boardWidth");
    Double boardHeight = optFiniteDouble(grid, "boardHeight");
    if (boardLeft != null
        && boardTop != null
        && boardWidth != null
        && boardHeight != null
        && boardWidth > 40
        && boardHeight > 40) {
      left = (int) Math.round(boardLeft);
      top = (int) Math.round(boardTop);
      width = (int) Math.round(boardWidth);
      height = (int) Math.round(boardHeight);
    }
    return new YikeGeometrySnapshot(
        left,
        top,
        width,
        height,
        firstX,
        firstY,
        cellX,
        cellY,
        geometry.score,
        geometry.node,
        geometry.reason);
  }

  private static Double optFiniteDouble(JSONObject object, String key) {
    if (object == null || !object.has(key)) {
      return null;
    }
    double value = object.optDouble(key, Double.NaN);
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return null;
    }
    return value;
  }

  private static boolean hasExplicitGrid(YikeGeometrySnapshot geometry) {
    return geometry != null
        && geometry.firstX != null
        && geometry.firstY != null
        && geometry.cellX != null
        && geometry.cellY != null;
  }

  private void onYikeBoardSizeResolved() {
    hasResolvedYikeBoardSize = boardSize > 0;
    sendYikeGeometryToReadBoard();
  }

  private static final boolean YIKE_DEBUG_LOG_ENABLED = false;
  private static final String YIKE_LOG_PATH = "D:/dev/weiqi/lizzieyzy-next/target/yike-debug.log";

  private static void yikeDebugLog(String msg) {
    YikeSyncDebugLog.log("OnlineDialog " + msg);
    if (!YIKE_DEBUG_LOG_ENABLED) return;
    try {
      java.io.FileWriter fw = new java.io.FileWriter(YIKE_LOG_PATH, true);
      fw.write(
          "["
              + new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date())
              + "] "
              + msg
              + "\n");
      fw.close();
    } catch (Exception ignored) {
    }
  }

  private void sendYikeContextToReadBoard() {
    sendYikeContextToReadBoard(history != null ? history.getMoveNumber() : 0);
  }

  private void reportSyncStatus(String text) {
    if (Lizzie.frame != null && Lizzie.frame.browserFrame != null) {
      Lizzie.frame.browserFrame.setSyncStatus(text);
    }
  }

  private void sendYikeContextToReadBoard(int moveNumber) {
    try {
      if (Lizzie.frame == null || Lizzie.frame.readBoard == null) {
        yikeDebugLog("sendYike: readBoard is null move=" + moveNumber);
        return;
      }
      StringBuilder sb = new StringBuilder("yike");
      if (roomId > 0) sb.append(" room=").append(roomId);
      if (moveNumber > 0) sb.append(" move=").append(moveNumber);
      String cmd = sb.toString();
      yikeDebugLog("sendYike: " + cmd);
      Lizzie.frame.readBoard.sendCommand(cmd);
      sendYikeGeometryToReadBoard();
    } catch (Exception e) {
      yikeDebugLog("sendYike error: " + e.toString());
    }
  }

  private void sendYikeGeometryToReadBoard() {
    try {
      if (!hasResolvedYikeBoardSize || lastYikeGeometry == null) {
        yikeDebugLog(
            "sendYikeGeometry skipped hasBoardSize="
                + hasResolvedYikeBoardSize
                + " geometry="
                + (lastYikeGeometry != null));
        return;
      }
      if (Lizzie.frame == null || Lizzie.frame.readBoard == null) {
        return;
      }
      String cmd = buildYikeGeometryCommand(lastYikeGeometry, boardSize);
      Lizzie.frame.readBoard.sendCommand(cmd);
      yikeDebugLog(
          "sendYikeGeometry: "
              + cmd
              + " node="
              + lastYikeGeometry.node
              + " reason="
              + lastYikeGeometry.reason
              + " score="
              + lastYikeGeometry.score);
    } catch (Exception e) {
      yikeDebugLog("sendYikeGeometry error: " + e);
    }
  }

  private static String buildYikeGeometryCommand(YikeGeometrySnapshot geometry, int boardSize) {
    if (geometry == null) {
      return null;
    }
    StringBuilder sb =
        new StringBuilder("yikeGeometry left=")
            .append(geometry.left)
            .append(" top=")
            .append(geometry.top)
            .append(" width=")
            .append(geometry.width)
            .append(" height=")
            .append(geometry.height)
            .append(" board=")
            .append(boardSize);
    if (hasExplicitGrid(geometry)) {
      sb.append(" firstX=").append(formatProtocolDouble(geometry.firstX));
      sb.append(" firstY=").append(formatProtocolDouble(geometry.firstY));
      sb.append(" cellX=").append(formatProtocolDouble(geometry.cellX));
      sb.append(" cellY=").append(formatProtocolDouble(geometry.cellY));
    }
    return sb.toString();
  }

  private static String formatProtocolDouble(Double value) {
    if (value == null) {
      return "0";
    }
    String text = String.format(Locale.US, "%.6f", value.doubleValue());
    while (text.contains(".") && (text.endsWith("0") || text.endsWith("."))) {
      text = text.substring(0, text.length() - 1);
    }
    return text;
  }

  private void clearYikeGeometryToReadBoard() {
    try {
      if (Lizzie.frame == null || Lizzie.frame.readBoard == null) {
        return;
      }
      Lizzie.frame.readBoard.sendCommand("yikeGeometry");
    } catch (Exception e) {
      yikeDebugLog("clearYikeGeometry error: " + e);
    }
  }

  private void startYikeUnitePolling() {
    if (roomId <= 0) {
      yikeDebugLog("startYikeUnitePolling: roomId<=0");
      return;
    }
    if (Lizzie.frame.browserFrame == null) {
      yikeDebugLog("startYikeUnitePolling: browserFrame is null");
      return;
    }
    int intervalMs = Math.max(refreshTime, 5) * 1000;
    yikeDebugLog(
        "startYikeUnitePolling room="
            + roomId
            + " intervalMs="
            + intervalMs
            + " ajaxUrl="
            + ajaxUrl);
    Lizzie.frame.browserFrame.installYikeUnitePoller(roomId, intervalMs, ajaxUrl);
  }

  /** 由 BrowserFrame 在浏览器内 fetch game-server API 后通过 cefQuery 回传调用。 */
  public void handleYikeUniteSgf(String envelope) {
    if (envelope == null || envelope.isEmpty()) return;
    yikeDebugLog(
        "handleYikeUniteSgf envelope len="
            + envelope.length()
            + " head="
            + envelope.substring(0, Math.min(envelope.length(), 200)));
    try {
      JSONObject env = new JSONObject(envelope);
      String tag = env.optString("tag");
      if ("ping".equals(tag)) {
        yikeDebugLog("yikeUnite ping: " + env.optString("body"));
        return;
      }
      if ("error".equals(tag)) {
        yikeDebugLog("yikeUnite fetch error: " + env.optString("body"));
        reportSyncStatus("拉取异常: " + env.optString("body"));
        return;
      }
      if (!"resp".equals(tag)) {
        yikeDebugLog("yikeUnite unknown tag=" + tag);
        return;
      }
      if (isStoped || type != YikeUrlInfo.TYPE_UNITE_ROOM) {
        yikeDebugLog("yikeUnite resp skipped (isStoped=" + isStoped + " type=" + type + ")");
        return;
      }
      JSONObject body = env.optJSONObject("body");
      if (body == null) {
        yikeDebugLog("yikeUnite resp has no body");
        return;
      }
      int status = body.optInt("status", -1);
      String text = body.optString("body", "");
      yikeDebugLog("yikeUnite resp status=" + status + " len=" + body.optInt("len"));
      if (status != 200) {
        reportSyncStatus("game-server 响应 HTTP " + status);
        return;
      }
      if (Utils.isBlank(text)) return;
      JSONObject root = new JSONObject(text);
      JSONObject data = root.optJSONObject("data");
      if (data == null) {
        yikeDebugLog("yikeUnite resp: no data field, root keys=" + root.keySet());
        return;
      }
      String sgf = data.optString("sgf");
      if (Utils.isBlank(sgf)) {
        yikeDebugLog("yikeUnite resp: sgf is blank");
        return;
      }
      // 用 hands_count 做主去重：弈客 game/info 里 hands_count 直接给当前手数，比字符串比较快
      int hands = data.optInt("hands_count", -1);
      yikeDebugLog(
          "yikeUnite parsed hands="
              + hands
              + " lastHands="
              + lastYikeHandsCount
              + " sgfLen="
              + sgf.length());
      if (hands >= 0 && hands == lastYikeHandsCount) {
        yikeDebugLog("yikeUnite skipped duplicate hands");
        return; // 没有新走子
      }
      String mainlineSgf = YikeSgfMainline.withoutVariations(sgf);
      boolean hasNewMoves = !mainlineSgf.equals(lastYikeMainlineSgf);
      if (!hasNewMoves) {
        yikeDebugLog("yikeUnite skipped same mainline");
        if (hands >= 0) lastYikeHandsCount = hands;
        return;
      }
      lastYikeMainlineSgf = mainlineSgf;
      if (hands >= 0) lastYikeHandsCount = hands;
      JSONObject wrapper = new JSONObject();
      wrapper.put("result", data);
      parseSgf(wrapper.toString(), "", 0, false, firstTime);
      reportSyncStatus(syncStatusPrefix() + "第 " + (hands >= 0 ? hands : "?") + " 手");
    } catch (JSONException e) {
      yikeDebugLog("handleYikeUniteSgf JSON error: " + e.toString());
      reportSyncStatus("解析响应失败: " + e.getMessage());
    } catch (RuntimeException e) {
      yikeDebugLog("handleYikeUniteSgf error: " + e.toString());
      reportSyncStatus("处理响应异常: " + e.getMessage());
    }
  }

  private void procComments(JSONObject cb) {
    if (cb == null) return;
    String type = cb.optString("type", "");
    if ("add".equals(type) || "update".equals(type)) {
      // TODO
      JSONObject d = ((JSONObject) cb.opt("content"));
      switch (d.optInt("type", 0)) {
        case 1:
          addBranch(d);
          break;
        case 2:
          addComment(d, "add".equals(type));
          break;
      }
    }
  }

  private void addBranch(JSONObject branch) {
    if (branch == null) return;
    int move = branch.optInt("handsCount");
    int id = branch.optInt("id");
    Map<Integer, JSONObject> b = null;
    if (!branchs.containsKey(move)) {
      b = new HashMap<Integer, JSONObject>();
      branchs.put(move, b);
    } else {
      b = branchs.get(move);
    }
    if (!b.containsKey(id)) {
      b.put(id, branch);
      addBranch(move, branch.optString("content"));
    } else {
      // TODO update
    }
  }

  private int addBranch(int move, String sgf) {
    int subIndex = -1;
    if (!Utils.isBlank(sgf)) {
      if (move > 0) {
        history.goToMoveNumber(move, false);
        if (history.getCurrentHistoryNode().numberOfChildren() == 0) {
          Stone color = history.getLastMoveColor() == Stone.WHITE ? Stone.BLACK : Stone.WHITE;
          history.pass(color, false, true);
          history.previous();
        }
        subIndex = SGFParser.parseBranch(history, sgf);
        while (history.next(true).isPresent())
          ;
      }
    }
    return subIndex;
  }

  private void addComment(JSONObject c, boolean add) {
    if (c == null) return;
    JSONObject extend = new JSONObject(c.optString("extend"));
    String member = extend == null ? "" : extend.optString("LiveMember");
    String content = c.optString("content");
    int move = c.optInt("handsCount");
    int id = c.optInt("id");
    Map<Integer, JSONObject> b = null;
    if (!comments.containsKey(move)) {
      b = new HashMap<Integer, JSONObject>();
      comments.put(move, b);
    } else {
      b = comments.get(move);
    }
    // if (!b.containsKey(id)) {
    b.put(id, c);
    // }
    addComment(move, Utils.isBlank(member) ? content : member + "：" + content, add);
  }

  private void addComment(int move, String comment, boolean add) {
    if (!Utils.isBlank(comment)) {
      history.goToMoveNumber(move, false);
      if (add) {
        history.getData().comment += comment + "\n";
      } else {
        history.getData().comment = comment + "\n";
      }
      while (history.next(true).isPresent())
        ;
    }
  }

  private void move(JSONObject d) {
    if (d == null || d.opt("move") == null || history == null) return;
    JSONObject m = (JSONObject) d.get("move");
    int move = m.optInt("mcnt");
    if (move > 0) {
      int[] c = new int[2];
      c[0] = m.optInt("x");
      c[1] = m.optInt("y");
      boolean changeMove = false;
      while (history.next(true).isPresent())
        ;
      Stone color = (m.optInt("c") == -1) ? Stone.WHITE : Stone.BLACK;
      //      Stone color =
      //          (move - history.getData().moveNumber) % 2 == 0
      //              ? history.getLastMoveColor()
      //              : (history.getLastMoveColor() == Stone.WHITE ? Stone.BLACK : Stone.WHITE);
      //      //    Stone color = (m.optInt("c") == -1) ? Stone.WHITE : Stone.BLACK;
      if (move <= history.getMoveNumber()) {
        int cur = history.getMoveNumber();
        for (int i = move; i <= cur; i++) {
          BoardHistoryNode currentNode = history.getCurrentHistoryNode();
          boolean isSameMove = (i == cur && currentNode.getData().isSameCoord(c));
          if (currentNode.previous().isPresent()) {
            BoardHistoryNode pre = currentNode.previous().get();
            history.previous();
            if (pre.numberOfChildren() <= 1 && !isSameMove) {
              int idx = pre.indexOfNode(currentNode);
              pre.deleteChild(idx);
              changeMove = false;
            } else {
              changeMove = true;
            }
          }
        }
      }

      if (c == null || !Board.isValid(c)) {
        history.pass(color, false, false);
      } else {

        history.place(c[0], c[1], color, false, changeMove);
      }

      sync();
    }
  }

  public void moveDelete() {
    if (LizzieFrame.urlSgf) {
      if (client != null && client.isOpen()) {
        client.close();
        client = null;
      }
    }
    type = checkUrl();
    isStoped = false;
    chineseRule = 1;
    chineseFlag = false;
    LizzieFrame.urlSgf = true;
    Lizzie.frame.setCommentPaneOrArea(false);
    if (type > 0) {
      error(false);
      setVisible(false);
      try {
        Lizzie.frame.setResult("");
        proc();
      } catch (IOException | URISyntaxException e) {
        e.printStackTrace();
      }
    } else {
      error(true);
    }
    Lizzie.frame.syncLiveBoardStat();
    //   sync();
    // Lizzie.board.moveToAnyPosition(Lizzie.board.getHistory().getMainEnd());
    // Lizzie.board.deleteMove();
    //  shouldMoveForward = true;
  }

  private void updateGame(JSONObject g) {
    if (g == null) return;
    int status = g.optInt("status");
    if (status == 3) {
      sio.close();
      String result = g.optString("resultDesc");
      if (!Utils.isBlank(result)) {
        while (Lizzie.board.getHistory().next().isPresent())
          ;
        Lizzie.board.getHistory().getEnd().getData().comment =
            result + "\n" + Lizzie.board.getHistory().getEnd().getData().comment;
        Lizzie.frame.setResult(result);
        Lizzie.board.getHistory().getGameInfo().setResult(result);
        Lizzie.board.previousMove(false);
        Lizzie.board.nextMove(true);
      }
    }
  }

  private void sendData(String id, JSONObject data, final Ack ack) { // callback i
    if (data == null) data = new JSONObject();
    sio.emit(
        id,
        data,
        new Ack() {
          @Override
          public void call(Object... args) {
            Object t = null;
            if (args != null && args.length > 0) {
              JSONObject e = (JSONObject) args[0];
              switch ((int) e.get("code")) {
                case 0:
                  if (e.opt("data") instanceof JSONArray) {
                    t = (JSONArray) e.opt("data");
                  } else {
                    t = (JSONObject) e.opt("data");
                  }
                  break;
                case 1:
                case 2:
                  if (e.opt("message") instanceof JSONArray) {
                    t = (JSONArray) e.opt("message");
                  } else {
                    t = (JSONObject) e.opt("message");
                  }
                  break;
                case 10:
                  sio.disconnect();
              }
            }
            if (ack != null) {
              if (t == null) {
                ack.call();
              } else {
                ack.call(t);
              }
            }
          }
        });
  }

  public void stopSync() {
    LizzieFrame.urlSgf = false;
    Lizzie.frame.setCommentPaneOrArea(true);
    isStoped = true;
    if (schedule != null && !schedule.isCancelled() && !schedule.isDone()) {
      schedule.cancel(false);
    }
    if (Lizzie.frame.browserFrame != null) {
      try {
        Lizzie.frame.browserFrame.stopYikeUnitePoller();
      } catch (Exception ignored) {
      }
    }
    //    if (client != null && client.isOpen()) {
    //      client.close();
    //      client = null;
    //    }
    txtUrl.setText("");
    checkUrl();
    //  type = 1;
    //    try {
    //      procNoClear();
    //    } catch (IOException e) {
    //      // TODO Auto-generated catch block
    //      e.printStackTrace();
    //    } catch (URISyntaxException e) {
    //      // TODO Auto-generated catch block
    //      e.printStackTrace();
    //    }
    if (sio != null) {
      sio.close();
    }
    lastYikeGeometry = null;
    hasResolvedYikeBoardSize = false;
    setVisible(false);
    reportSyncStatus("已停止同步");
    //  Lizzie.frame.onlineDialog.dispose();
  }

  public void applyChangeWeb(String url) {
    yikeDebugLog("applyChangeWeb url=" + url);
    //
    isStoped = false;
    fromBrowser = true;
    firstTime = true;
    txtUrl.setText(url);
    type = checkUrl();
    yikeDebugLog("applyChangeWeb type=" + type + " roomId=" + roomId + " ajaxUrl=" + ajaxUrl);
    LizzieFrame.urlSgf = true;
    Lizzie.frame.setCommentPaneOrArea(false);
    if (type > 0) {
      setVisible(false);
      try {
        Lizzie.frame.setResult("");
        proc();
        reportSyncStatus(syncStatusPrefix() + "已启动");
      } catch (IOException | URISyntaxException e) {
        yikeDebugLog("applyChangeWeb proc error: " + e.toString());
        e.printStackTrace();
        reportSyncStatus("同步启动失败: " + e.getMessage());
      }
    } else {
      reportSyncStatus("URL 无法识别");
    }
  }

  private String syncStatusPrefix() {
    String label;
    switch (type) {
      case YikeUrlInfo.TYPE_UNITE_ROOM:
        label = "弈客对弈";
        break;
      case YikeUrlInfo.TYPE_NEW_LIVE_ROOM:
        label = "弈客直播(新)";
        break;
      case YikeUrlInfo.TYPE_OLD_LIVE_ROOM:
      case YikeUrlInfo.TYPE_OLD_LIVE_BOARD:
        label = "弈客直播";
        break;
      case YikeUrlInfo.TYPE_GAME_ROOM:
        label = "弈客对局";
        break;
      default:
        label = "同步";
    }
    return roomId > 0 ? label + " 房间 " + roomId + " " : label + " ";
  }
}
