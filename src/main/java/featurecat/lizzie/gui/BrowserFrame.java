package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.MultiOutputStream;
import featurecat.lizzie.util.Utils;
import featurecat.lizzie.util.YikeSyncDebugLog;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import javax.imageio.ImageIO;
import javax.swing.*;
import me.friwi.jcefmaven.*;
import org.cef.CefApp;
import org.cef.CefApp.CefAppState;
import org.cef.CefClient;
import org.cef.CefSettings.LogSeverity;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefFocusHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.handler.CefRequestHandler;
import org.cef.handler.CefRequestHandlerAdapter;
import org.json.JSONObject;
import org.json.JSONTokener;

public class BrowserFrame extends JFrame {
  private static final long serialVersionUID = -5570653778104813836L;
  private static final String YIKE_BROWSER_SYNC_STOP_COMMAND = "yikeBrowserSyncStop";
  private final JTextField address_;
  private final CefApp cefApp_;
  private final CefClient client_;
  private final CefBrowser browser_;
  private final Component browerUI_;
  private volatile boolean browserFocus_ = true;
  private JToolBar toolbar;
  private String baseTitle = "";
  private String lastSyncStatus = null;
  private long pendingYikeRoomId = 0;
  private int pendingYikeIntervalMs = 1000;
  private String pendingYikeInfoUrl = "";
  private boolean isYike;
  private volatile boolean yikeBrowserSyncEnabled = false;

  /**
   * To display a simple browser window, it suffices completely to create an instance of the class
   * CefBrowser and to assign its UI component to your application (e.g. to your content pane). But
   * to be more verbose, this CTOR keeps an instance of each object on the way to the browser UI.
   */
  public BrowserFrame(String startURL, String title, boolean yike)
      throws UnsupportedPlatformException,
          CefInitializationException,
          IOException,
          InterruptedException {
    // (0) Initialize CEF using the maven loader
    this.isYike = yike;
    this.baseTitle = title == null ? "" : title;
    this.setTitle(this.baseTitle);
    try {
      setIconImage(ImageIO.read(getClass().getResourceAsStream("/assets/logo.png")));
    } catch (IOException e) {
      e.printStackTrace();
    }
    checkBundleFolder();
    CefAppBuilder builder = new CefAppBuilder();
    // windowless_rendering_enabled must be set to false if not wanted.
    builder.getCefSettings().windowless_rendering_enabled = false;
    builder.getCefSettings().log_severity = LogSeverity.LOGSEVERITY_DISABLE;
    File cefCache = new File("jcef-bundle", "cache");
    if (!cefCache.exists()) cefCache.mkdirs();
    builder.getCefSettings().root_cache_path = cefCache.getAbsolutePath();
    builder.getCefSettings().cache_path = cefCache.getAbsolutePath();
    // USE builder.setAppHandler INSTEAD OF CefApp.addAppHandler!
    // Fixes compatibility issues with MacOSX
    builder.setAppHandler(
        new MavenCefAppHandlerAdapter() {
          @Override
          public void stateHasChanged(org.cef.CefApp.CefAppState state) {
            // Shutdown the app if the native CEF part is terminated
            if (state == CefAppState.TERMINATED) setVisible(false);
          }
        });

    // (1) The entry point to JCEF is always the class CefApp. There is only one
    //     instance per application and therefore you have to call the method
    //     "getInstance()" instead of a CTOR.
    //
    //     CefApp is responsible for the global CEF context. It loads all
    //     required native libraries, initializes CEF accordingly, starts a
    //     background task to handle CEF's message loop and takes care of
    //     shutting down CEF after disposing it.
    //
    //     WHEN WORKING WITH MAVEN: Use the builder.build() method to
    //     build the CefApp on first run and fetch the instance on all consecutive
    //     runs. This method is thread-safe and will always return a valid app
    //     instance.
    if (!Lizzie.config.browserInitiazed)
      Lizzie.frame.browserInitializing = new BrowserInitializing(Lizzie.frame);
    new Thread() {
      public void run() {
        try {
          Thread.sleep(500);
          if (!Lizzie.config.browserInitiazed) Lizzie.frame.browserInitializing.setVisible(true);
          else Lizzie.frame.browserInitializing.dispose();
        } catch (InterruptedException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }
    }.start();
    cefApp_ = builder.build();
    Lizzie.config.browserInitiazed = true;
    if (Lizzie.frame.browserInitializing != null && Lizzie.frame.browserInitializing.isVisible()) {
      Lizzie.frame.browserInitializing.setVisible(false);
      Lizzie.frame.browserInitializing.dispose();
    }
    if (Lizzie.config.logConsoleToFile) {
      PrintStream oldErrorPrintStream = System.err;
      FileOutputStream bosError =
          new FileOutputStream("LastErrorLogs_" + Lizzie.nextVersion + ".txt", true);
      MultiOutputStream multiError =
          new MultiOutputStream(new PrintStream(bosError), oldErrorPrintStream);
      System.setErr(new PrintStream(multiError));
    }
    // (2) JCEF can handle one to many browser instances simultaneous. These
    //     browser instances are logically grouped together by an instance of
    //     the class CefClient. In your application you can create one to many
    //     instances of CefClient with one to many CefBrowser instances per
    //     client. To get an instance of CefClient you have to use the method
    //     "createClient()" of your CefApp instance. Calling an CTOR of
    //     CefClient is not supported.
    //
    //     CefClient is a connector to all possible events which come from the
    //     CefBrowser instances. Those events could be simple things like the
    //     change of the browser title or more complex ones like context menu
    //     events. By assigning handlers to CefClient you can control the
    //     behavior of the browser. See tests.detailed.MainFrame for an example
    //     of how to use these handlers.
    client_ = cefApp_.createClient();

    // (3) Create a simple message router to receive messages from CEF.
    CefMessageRouter msgRouter =
        CefMessageRouter.create(
            new CefMessageRouterHandlerAdapter() {
              @Override
              public boolean onQuery(
                  CefBrowser browser,
                  CefFrame frame,
                  long queryId,
                  String request,
                  boolean persistent,
                  CefQueryCallback callback) {
                if (request != null && request.startsWith("yikeUnite:")) {
                  // 立即回调，绝不在 CEF UI 线程里做解析/Swing/IO，否则消息队列堵了会拖死整个进程
                  if (callback != null) callback.success("");
                  final String payload = request.substring("yikeUnite:".length());
                  YikeQueryWorker.submit(
                      () -> {
                        try {
                          if (LizzieFrame.onlineDialog != null) {
                            LizzieFrame.onlineDialog.handleYikeUniteSgf(payload);
                          }
                        } catch (Throwable ignored) {
                        }
                      });
                  return true;
                }
                if (request != null && request.startsWith("yikeGeometry:")) {
                  if (callback != null) callback.success("");
                  final String payload = request.substring("yikeGeometry:".length());
                  YikeQueryWorker.submit(
                      () -> {
                        try {
                          if (LizzieFrame.onlineDialog != null) {
                            LizzieFrame.onlineDialog.handleYikeGeometryProbe(payload);
                          }
                        } catch (Throwable ignored) {
                        }
                      });
                  return true;
                }
                return false;
              }
            });
    client_.addMessageRouter(msgRouter);

    client_.addLoadHandler(
        new CefLoadHandlerAdapter() {
          @Override
          public void onLoadStart(
              CefBrowser browser, CefFrame frame, org.cef.network.CefRequest.TransitionType t) {
            // 在页面 DOM 刚开始构建时就装 hook，赶在弈客网页主动调 game/info 之前
            if (frame != null && frame.isMain() && pendingYikeRoomId > 0) {
              installYikeUnitePoller(pendingYikeRoomId, pendingYikeIntervalMs, pendingYikeInfoUrl);
            }
            if (frame != null && frame.isMain() && isYike) {
              installYikeGeometryProbe();
            }
          }

          @Override
          public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
            if (frame != null && frame.isMain() && pendingYikeRoomId > 0) {
              installYikeUnitePoller(pendingYikeRoomId, pendingYikeIntervalMs, pendingYikeInfoUrl);
            }
            if (frame != null && frame.isMain() && isYike) {
              installYikeGeometryProbe();
            }
          }

          @Override
          public void onLoadError(
              CefBrowser browser,
              CefFrame frame,
              CefLoadHandler.ErrorCode errorCode,
              String errorText,
              String failedUrl) {
            if (frame.isMain()
                && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED
                && errorCode != CefLoadHandler.ErrorCode.ERR_NONE) {
              String safeUrl = failedUrl.replace("\\", "\\\\").replace("'", "\\'");
              String safeError = errorText.replace("\\", "\\\\").replace("'", "\\'");
              browser.executeJavaScript(
                  "document.body.textContent='';"
                      + "var d=document.createElement('div');"
                      + "d.style.cssText='text-align:center;padding:60px;font-family:sans-serif;color:#333';"
                      + "d.innerHTML='<h2>\\u52a0\\u8f7d\\u5931\\u8d25</h2>"
                      + "<p>\\u9519\\u8bef: "
                      + safeError
                      + " ("
                      + errorCode
                      + ")</p>"
                      + "<p>URL: "
                      + safeUrl
                      + "</p>"
                      + "<button onclick=\"location.reload()\" style=\"padding:8px 24px;"
                      + "font-size:14px;cursor:pointer\">\\u91cd\\u8bd5</button>';"
                      + "document.body.appendChild(d);",
                  frame.getURL(),
                  0);
            }
          }
        });

    client_.addRequestHandler(
        new CefRequestHandlerAdapter() {
          @Override
          public void onRenderProcessTerminated(
              CefBrowser browser,
              CefRequestHandler.TerminationStatus status,
              int errorCode,
              String errorString) {
            javax.swing.SwingUtilities.invokeLater(
                () -> {
                  String url = address_.getText();
                  if (url != null && !url.isEmpty()) {
                    browser_.loadURL(url);
                  }
                });
          }
        });

    // (4) One CefBrowser instance is responsible to control what you'll see on
    //     the UI component of the instance. It can be displayed off-screen
    //     rendered or windowed rendered. To get an instance of CefBrowser you
    //     have to call the method "createBrowser()" of your CefClient
    //     instances.
    //
    //     CefBrowser has methods like "goBack()", "goForward()", "loadURL()",
    //     and many more which are used to control the behavior of the displayed
    //     content. The UI is held within a UI-Compontent which can be accessed
    //     by calling the method "getUIComponent()" on the instance of CefBrowser.
    //     The UI component is inherited from a java.awt.Component and therefore
    //     it can be embedded into any AWT UI.
    browser_ = client_.createBrowser(startURL, false, false);
    browerUI_ = browser_.getUIComponent();

    // (5) For this minimal browser, we need only a text field to enter an URL
    //     we want to navigate to and a CefBrowser window to display the content
    //     of the URL. To respond to the input of the user, we're registering an
    //     anonymous ActionListener. This listener is performed each time the
    //     user presses the "ENTER" key within the address field.
    //     If this happens, the entered value is passed to the CefBrowser
    //     instance to be loaded as URL.
    address_ = new JTextField(startURL);
    address_.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            browser_.loadURL(address_.getText());
          }
        });

    address_.addKeyListener(
        new KeyAdapter() {
          public void keyPressed(KeyEvent e) {
            if (e.getKeyChar() == KeyEvent.VK_ENTER) // 按回车键执行相应操作;
            {
              browser_.loadURL(address_.getText());
              startYikeSync(address_.getText(), true);
            }
          }
        });

    // Update the address field when the browser URL changes.
    client_.addDisplayHandler(
        new CefDisplayHandlerAdapter() {
          @Override
          public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
            SwingUtilities.invokeLater(
                () -> {
                  address_.setText(url);
                  if (frame != null && frame.isMain() && isYike) {
                    installYikeGeometryProbe();
                  }
                });
          }
        });

    // Clear focus from the browser when the address field gains focus.
    address_.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusGained(FocusEvent e) {
            if (!browserFocus_) return;
            browserFocus_ = false;
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
            address_.requestFocus();
          }
        });

    // Clear focus from the address field when the browser gains focus.
    client_.addFocusHandler(
        new CefFocusHandlerAdapter() {
          @Override
          public void onGotFocus(CefBrowser browser) {
            if (browserFocus_) return;
            browserFocus_ = true;
            SwingUtilities.invokeLater(
                () -> {
                  KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
                  browser.setFocus(true);
                });
          }

          @Override
          public void onTakeFocus(CefBrowser browser, boolean next) {
            browserFocus_ = false;
          }
        });

    // 以下为处理无法打开新链接的问题
    client_.addLifeSpanHandler(
        new CefLifeSpanHandlerAdapter() {

          @Override
          public boolean onBeforePopup(
              CefBrowser browser, CefFrame frame, String target_url, String target_frame_name) {
            final String currentUrl = browser.getURL();
            SwingUtilities.invokeLater(
                () -> {
                  if (shouldAutoStartYikeSync() && !currentUrl.equals(target_url)) {
                    startYikeSync(target_url, false);
                  }
                  browser_.loadURL(target_url);
                });
            return true;
          }
        });

    // (6) All UI components are assigned to the default content pane of this
    //     JFrame and afterwards the frame is made visible to the user.
    toolbar = new JToolBar();
    toolbar.setBorderPainted(false);
    toolbar.setFloatable(false);
    // toolbarPanel.setLayout(new BorderLayout(0, 0));

    ImageIcon iconLeft = new ImageIcon();
    try {
      iconLeft.setImage(ImageIO.read(getClass().getResourceAsStream("/assets/left.png")));
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    JButton back = new JButton(iconLeft);
    back.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            if (browser_.canGoBack()) browser_.goBack();
          }
        });
    back.setFocusable(false);

    JLabel load = makeLabelButton(Lizzie.resourceBundle.getString("LizzieFrame.onLoad"));
    load.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseClicked(java.awt.event.MouseEvent e) {
            String addr = address_.getText();
            startYikeSync(addr, true);
            browser_.loadURL(addr);
          }
        });

    JLabel stop = makeLabelButton(Lizzie.resourceBundle.getString("LizzieFrame.stopSync"));
    stop.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseClicked(java.awt.event.MouseEvent e) {
            stopYikeBrowserSync(true);
          }
        });
    toolbar.add(back);
    toolbar.add(address_);
    toolbar.add(load);
    toolbar.add(stop);

    JPanel view = new JPanel();
    view.setLayout(null);
    view.add(browerUI_);

    view.addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(ComponentEvent e) {
            view.revalidate();
            browerUI_.setBounds(0, 0, view.getWidth(), view.getHeight());
          }
        });

    getContentPane()
        .add(toolbar, BorderLayout.PAGE_START); // .add(toolbarPanel, BorderLayout.NORTH);
    toolbar.setVisible(isYike);
    getContentPane().add(view, BorderLayout.CENTER);
    getRootPane().setBorder(BorderFactory.createEmptyBorder());
    pack();
    setSize(Lizzie.frame.bowserWidth, Lizzie.frame.bowserHeight);
    setLocation(Lizzie.frame.bowserX, Lizzie.frame.bowserY);
    setFrameSize();
    setVisible(true);

    // (7) To take care of shutting down CEF accordingly, it's important to call
    //     the method "dispose()" of the CefApp instance if the Java
    //     application will be closed. Otherwise you'll get asserts from CEF.
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            if (isYike) {
              Lizzie.frame.bowserX = getX();
              Lizzie.frame.bowserY = getY();
              Lizzie.frame.bowserWidth = getWidth();
              Lizzie.frame.bowserHeight = getHeight();
            }
            setVisible(false);
          }
        });
  }

  private void checkBundleFolder() {
    // TODO Auto-generated method stub
    String tag = "jcef-99c2f7a+cef-127.3.1+g6cbb30e+chromium-127.0.6533.100";
    File meta = new File("jcef-bundle" + File.separator + "build_meta.json");
    if (meta.exists()) {
      FileInputStream fp;
      try {
        fp = new FileInputStream(meta);
        InputStreamReader reader;
        reader = new InputStreamReader(fp, "utf-8");
        JSONObject json = new JSONObject(new JSONTokener(reader));
        reader.close();
        fp.close();
        if (!json.has("release_tag") || !json.getString("release_tag").equals(tag)) {
          File dir = new File("jcef-bundle");
          Utils.deleteDir(dir);
        }
      } catch (FileNotFoundException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      } catch (UnsupportedEncodingException e1) {
        // TODO Auto-generated catch block
        e1.printStackTrace();
      } catch (IOException e2) {
        // TODO Auto-generated catch block
        e2.printStackTrace();
      }
    }
  }

  private void setFrameSize() {
    // TODO Auto-generated method stub
    if (isYike) {
      setSize(Lizzie.frame.bowserWidth, Lizzie.frame.bowserHeight);
      setLocation(Lizzie.frame.bowserX, Lizzie.frame.bowserY);
    } else setExtendedState(Frame.MAXIMIZED_BOTH);
  }

  public void openURL(String url, String title, boolean yike) {
    // TODO Auto-generated method stub
    browser_.loadURL(url);
    SwingUtilities.invokeLater(
        new Runnable() {
          public void run() {
            isYike = yike;
            baseTitle = title == null ? "" : title;
            lastSyncStatus = null;
            if (!yike) {
              pendingYikeRoomId = 0;
              pendingYikeInfoUrl = "";
            }
            setTitle(baseTitle);
            setVisible(true);
            toolbar.setVisible(isYike);
            setFrameSize();
          }
        });
  }

  public void startYikeSyncFromCurrentAddress() {
    String addr = address_ == null ? "" : address_.getText();
    YikeSyncDebugLog.log("BrowserFrame start from current address addr=" + addr);
    if (startYikeSync(addr, true) && browser_ != null) {
      YikeSyncDebugLog.log(
          "BrowserFrame reload current address for readboard yike sync addr=" + addr);
      browser_.loadURL(addr);
    }
  }

  public void ensureYikeSyncFromCurrentAddress() {
    YikeSyncDebugLog.log(
        "BrowserFrame ensure start isYike="
            + isYike
            + " enabled="
            + yikeBrowserSyncEnabled
            + " address="
            + (address_ == null ? "" : address_.getText()));
    if (!shouldStartYikeSyncFromPlatformSignal(isYike, yikeBrowserSyncEnabled)) {
      YikeSyncDebugLog.log("BrowserFrame ensure skipped");
      return;
    }
    startYikeSyncFromCurrentAddress();
  }

  public void stopYikeSyncFromReadBoard() {
    YikeSyncDebugLog.log("BrowserFrame stop from readboard");
    stopYikeBrowserSync(false);
  }

  static boolean shouldAutoStartYikeSync(boolean isYike, boolean yikeBrowserSyncEnabled) {
    return isYike && yikeBrowserSyncEnabled;
  }

  static boolean shouldStartYikeSyncFromPlatformSignal(
      boolean isYike, boolean yikeBrowserSyncEnabled) {
    return isYike && !yikeBrowserSyncEnabled;
  }

  private boolean shouldAutoStartYikeSync() {
    return shouldAutoStartYikeSync(isYike, yikeBrowserSyncEnabled);
  }

  private boolean startYikeSync(String addr, boolean enableFutureAutoSync) {
    YikeSyncDebugLog.log(
        "BrowserFrame startYikeSync isYike="
            + isYike
            + " enableFuture="
            + enableFutureAutoSync
            + " enabledBefore="
            + yikeBrowserSyncEnabled
            + " addr="
            + addr);
    if (!isYike) {
      YikeSyncDebugLog.log("BrowserFrame startYikeSync skipped non-yike");
      return false;
    }
    if (Utils.isBlank(addr)) {
      setSyncStatus("URL 无法识别");
      YikeSyncDebugLog.log("BrowserFrame startYikeSync skipped blank url");
      return false;
    }
    if (enableFutureAutoSync) {
      yikeBrowserSyncEnabled = true;
    }
    YikeSyncDebugLog.log(
        "BrowserFrame calling LizzieFrame.syncOnline enabledAfter=" + yikeBrowserSyncEnabled);
    Lizzie.frame.syncOnline(addr);
    return true;
  }

  private void stopYikeBrowserSync(boolean notifyReadBoard) {
    YikeSyncDebugLog.log(
        "BrowserFrame stopYikeBrowserSync isYike="
            + isYike
            + " notifyReadBoard="
            + notifyReadBoard
            + " enabledBefore="
            + yikeBrowserSyncEnabled
            + " onlineDialog="
            + (LizzieFrame.onlineDialog != null));
    if (isYike) {
      yikeBrowserSyncEnabled = false;
    }
    if (LizzieFrame.onlineDialog != null) {
      LizzieFrame.onlineDialog.stopSync();
    } else {
      setSyncStatus("无同步任务");
    }
    if (notifyReadBoard && isYike) {
      sendYikeBrowserSyncStopToReadBoard();
    }
    YikeSyncDebugLog.log("BrowserFrame stopYikeBrowserSync enabledAfter=" + yikeBrowserSyncEnabled);
  }

  private void sendYikeBrowserSyncStopToReadBoard() {
    YikeSyncDebugLog.log(
        "BrowserFrame send yikeBrowserSyncStop readBoard="
            + (Lizzie.frame != null && Lizzie.frame.readBoard != null));
    if (Lizzie.frame != null && Lizzie.frame.readBoard != null) {
      Lizzie.frame.readBoard.sendCommand(YIKE_BROWSER_SYNC_STOP_COMMAND);
    }
  }

  /** 用 JLabel 模拟按钮，完全自绘，绕开 Windows L&F + JToolBar 的 hover/pressed 残影。 */
  private static JLabel makeLabelButton(String text) {
    JLabel lbl = new JLabel(text, SwingConstants.CENTER);
    lbl.setOpaque(true);
    lbl.setBackground(new Color(0xF0F0F0));
    lbl.setForeground(Color.BLACK);
    lbl.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0xB0B0B0)),
            BorderFactory.createEmptyBorder(4, 12, 4, 12)));
    lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    lbl.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseEntered(java.awt.event.MouseEvent e) {
            lbl.setBackground(new Color(0xE0E0E0));
          }

          @Override
          public void mouseExited(java.awt.event.MouseEvent e) {
            lbl.setBackground(new Color(0xF0F0F0));
          }

          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            lbl.setBackground(new Color(0xC8C8C8));
          }

          @Override
          public void mouseReleased(java.awt.event.MouseEvent e) {
            lbl.setBackground(
                lbl.contains(e.getPoint()) ? new Color(0xE0E0E0) : new Color(0xF0F0F0));
          }
        });
    return lbl;
  }

  public void setSyncStatus(String text) {
    String normalized = Utils.isBlank(text) ? "" : text;
    if (java.util.Objects.equals(normalized, lastSyncStatus)) return;
    lastSyncStatus = normalized;
    SwingUtilities.invokeLater(
        () -> {
          if (normalized.isEmpty()) {
            setTitle(baseTitle);
          } else {
            setTitle(baseTitle + "  —  " + normalized);
          }
        });
  }

  /** 在弈客对弈页面里 hook fetch/XHR，复用网页自己的 game/info 请求形状和登录态。 */
  public void installYikeUnitePoller(long roomId, int intervalMs, String infoUrl) {
    YikeSyncDebugLog.log(
        "BrowserFrame installYikeUnitePoller roomId="
            + roomId
            + " intervalMs="
            + intervalMs
            + " infoUrl="
            + infoUrl
            + " url="
            + (browser_ == null ? "" : browser_.getURL()));
    pendingYikeRoomId = roomId;
    pendingYikeIntervalMs = intervalMs;
    pendingYikeInfoUrl = infoUrl == null ? "" : infoUrl;
    int periodMs = Math.max(500, intervalMs);
    String js =
        "(function(){"
            + "var ROOM='"
            + roomId
            + "';"
            + "var PERIOD="
            + periodMs
            + ";"
            + "var send=function(tag,body){try{window.cefQuery({request:'yikeUnite:'+"
            + "JSON.stringify({tag:tag,body:body}),persistent:false,"
            + "onSuccess:function(){},onFailure:function(){}});}catch(e){}};"
            + "var matchHttp=function(u){return typeof u==='string' && u.indexOf('game-server.yikeweiqi.com/game/info')>=0;};"
            + "var matchRoom=function(u){return matchHttp(u) && String(u).indexOf('id='+ROOM)>=0;};"
            + "if(window.__yikeUnitePoller){clearInterval(window.__yikeUnitePoller);window.__yikeUnitePoller=null;}"
            + "var origFetch=window.__yikeUniteOrigFetch||window.fetch;"
            + "window.__yikeUniteOrigFetch=origFetch;"
            + "var XO=window.XMLHttpRequest;"
            + "var origXOpen=window.__yikeUniteOrigXOpen||(XO&&XO.prototype.open);"
            + "var origXSend=window.__yikeUniteOrigXSend||(XO&&XO.prototype.send);"
            + "var origXSetHdr=window.__yikeUniteOrigXSetHdr||(XO&&XO.prototype.setRequestHeader);"
            + "window.__yikeUniteOrigXOpen=origXOpen;window.__yikeUniteOrigXSend=origXSend;"
            + "window.__yikeUniteOrigXSetHdr=origXSetHdr;"
            + "var deliverResp=function(url,status,text){"
            + "send('resp',{url:url,status:status,len:text.length,body:text.substring(0,500000)});};"
            + "var poll=function(){"
            + "var saved=window.__yikeUniteSaved;if(!saved)return;"
            + "if(!matchRoom(saved.url)){window.__yikeUniteSaved=null;send('ping','cleared stale request '+saved.url);return;}"
            + "if(saved.kind==='fetch'){try{"
            + "var req=saved.req.clone();"
            + "origFetch(req).then(function(r){return r.clone().text().then(function(t){"
            + "deliverResp(saved.url,r.status,t);});}).catch(function(e){send('error','poll fetch: '+e);});"
            + "}catch(e){send('error','poll fetch throw: '+e);}}"
            + "else if(saved.kind==='xhr'){try{"
            + "var x=new XO();"
            + "origXOpen.call(x,saved.method||'GET',saved.url,true);"
            + "if(saved.headers){for(var k in saved.headers){try{origXSetHdr.call(x,k,saved.headers[k]);}catch(e){}}}"
            + "x.addEventListener('load',function(){deliverResp(saved.url,x.status,x.responseText||'');});"
            + "x.addEventListener('error',function(){send('error','poll xhr: network');});"
            + "origXSend.call(x,saved.body||null);"
            + "}catch(e){send('error','poll xhr throw: '+e);}}"
            + "};"
            + "var startPolling=function(){"
            + "if(window.__yikeUnitePoller)return;"
            + "var saved=window.__yikeUniteSaved;"
            + "send('ping','start polling kind='+(saved?saved.kind:'waiting')+' url='+(saved?saved.url:'')+' period='+PERIOD);"
            + "window.__yikeUnitePoller=setInterval(poll,PERIOD);"
            + "};"
            + "if(window.__yikeUniteSaved&&!matchRoom(window.__yikeUniteSaved.url)){"
            + "send('ping','cleared stale request '+window.__yikeUniteSaved.url);window.__yikeUniteSaved=null;}"
            + "if(window.__yikeUniteHookInstalled){"
            + "send('ping','hook already installed');"
            + "startPolling();poll();"
            + "return;}"
            + "window.__yikeUniteHookInstalled=true;"
            + "send('ping','installing hook for room='+ROOM);"
            + "window.fetch=function(input,init){"
            + "var url=typeof input==='string'?input:(input&&input.url);"
            + "var p=origFetch.apply(this,arguments);"
            + "if(matchRoom(url)){"
            + "p.then(function(r){r.clone().text().then(function(t){"
            + "deliverResp(url,r.status,t);"
            + "if(!window.__yikeUniteSaved){"
            + "try{var req;"
            + "if(input&&typeof input!=='string'&&typeof input.clone==='function'){req=input.clone();}"
            + "else if(typeof Request!=='undefined'){req=new Request(url,init||{});}"
            + "if(req){window.__yikeUniteSaved={kind:'fetch',url:url,req:req};startPolling();}"
            + "}catch(e){send('error','save fetch: '+e);}"
            + "}"
            + "}).catch(function(){});}).catch(function(){});}"
            + "return p;};"
            + "if(XO&&XO.prototype){"
            + "XO.prototype.open=function(m,u){this.__yikeUrl=u;this.__yikeMethod=m;this.__yikeHdrs={};return origXOpen.apply(this,arguments);};"
            + "XO.prototype.setRequestHeader=function(k,v){try{this.__yikeHdrs=this.__yikeHdrs||{};this.__yikeHdrs[k]=v;}catch(e){}return origXSetHdr.apply(this,arguments);};"
            + "XO.prototype.send=function(body){var x=this;x.__yikeBody=body;"
            + "x.addEventListener('load',function(){try{if(matchRoom(x.__yikeUrl)){"
            + "deliverResp(x.__yikeUrl,x.status,x.responseText||'');"
            + "if(!window.__yikeUniteSaved){"
            + "window.__yikeUniteSaved={kind:'xhr',url:x.__yikeUrl,method:x.__yikeMethod||'GET',"
            + "headers:x.__yikeHdrs||{},body:x.__yikeBody};"
            + "startPolling();}}}catch(e){}});"
            + "return origXSend.apply(this,arguments);};}"
            // -- WebSocket hook: 弈客通过 WS 推送落子，收到任何消息立刻触发一次 fetch
            + "var WSO=window.__yikeUniteOrigWS||window.WebSocket;"
            + "window.__yikeUniteOrigWS=WSO;"
            + "if(WSO && !window.__yikeUniteWSHook){window.__yikeUniteWSHook=true;"
            + "var WSP=function(url,protocols){"
            + "var ws=protocols?new WSO(url,protocols):new WSO(url);"
            + "var u=String(url);"
            + "if(u.indexOf('yikeweiqi.com')>=0){"
            + "send('ping','ws-open '+u);"
            + "ws.addEventListener('message',function(){"
            + "if(window.__yikeUniteSaved){"
            + "if(window.__yikeUniteWsDebounce)clearTimeout(window.__yikeUniteWsDebounce);"
            + "window.__yikeUniteWsDebounce=setTimeout(function(){poll();},80);"
            + "}});}"
            + "return ws;};"
            + "WSP.prototype=WSO.prototype;WSP.CONNECTING=WSO.CONNECTING;WSP.OPEN=WSO.OPEN;"
            + "WSP.CLOSING=WSO.CLOSING;WSP.CLOSED=WSO.CLOSED;"
            + "window.WebSocket=WSP;"
            + "}"
            + "startPolling();poll();"
            + "})();";
    browser_.executeJavaScript(js, browser_.getURL(), 0);
  }

  public void stopYikeUnitePoller() {
    YikeSyncDebugLog.log("BrowserFrame stopYikeUnitePoller");
    pendingYikeRoomId = 0;
    pendingYikeInfoUrl = "";
    String js =
        "if(window.__yikeUnitePoller){clearInterval(window.__yikeUnitePoller);"
            + "window.__yikeUnitePoller=null;}"
            + "if(window.__yikeUniteWsDebounce){clearTimeout(window.__yikeUniteWsDebounce);"
            + "window.__yikeUniteWsDebounce=null;}";
    browser_.executeJavaScript(js, browser_.getURL(), 0);
  }

  private void installYikeGeometryProbe() {
    if (!isYike) {
      return;
    }

    String js =
        "(function(){"
            + "var send=function(payload){try{window.cefQuery({request:'yikeGeometry:'+JSON.stringify(payload),persistent:false,onSuccess:function(){},onFailure:function(){}});}catch(e){}};"
            + "var norm=function(v){return Math.round(v*100)/100;};"
            + "var num=function(v){v=parseFloat(v);return isFinite(v)?v:0;};"
            + "var trim=function(s,n){s=String(s||'').trim();return s.length>n?s.substring(0,n):s;};"
            + "var desc=function(el){"
            + "var id=el.id?('#'+trim(el.id,48)):'';"
            + "var cls=trim((el.className&&typeof el.className==='string')?el.className:'',96).replace(/\\s+/g,'.');"
            + "return trim(el.tagName.toLowerCase()+id+(cls?'.'+cls:''),160);};"
            + "var collect=function(reason){"
            + "var seen=[];"
            + "var out=[];"
            + "var push=function(el,tag){"
            + "if(!el||seen.indexOf(el)>=0)return;"
            + "if(el.id==='__lizzie_yike_grid_probe_overlay')return;"
            + "seen.push(el);"
            + "var r=el.getBoundingClientRect();"
            + "if(!r||r.width<40||r.height<40)return;"
            + "var st=getComputedStyle(el);"
            + "out.push({"
            + "reason:tag,"
            + "node:desc(el),"
            + "rect:{left:norm(r.left),top:norm(r.top),width:norm(r.width),height:norm(r.height)},"
            + "client:{width:el.clientWidth||0,height:el.clientHeight||0},"
            + "scroll:{width:el.scrollWidth||0,height:el.scrollHeight||0},"
            + "style:{display:st.display,position:st.position,pointerEvents:st.pointerEvents,transform:trim(st.transform,120),backgroundImage:trim(st.backgroundImage,120)},"
            + "text:trim(el.textContent,40)"
            + "});};"
            + "var pushRect=function(node,left,top,width,height,tag){"
            + "if(width<40||height<40)return;"
            + "out.push({"
            + "reason:tag,"
            + "node:node,"
            + "rect:{left:norm(left),top:norm(top),width:norm(width),height:norm(height)},"
            + "client:{width:Math.round(width),height:Math.round(height)},"
            + "scroll:{width:Math.round(width),height:Math.round(height)},"
            + "style:{display:'virtual',position:'virtual',pointerEvents:'auto',transform:'none',backgroundImage:''},"
            + "text:''"
            + "});};"
            + "var pushWrapperContentBox=function(el,tag){"
            + "var r=el.getBoundingClientRect();"
            + "if(!r||r.width<40||r.height<40)return;"
            + "var st=getComputedStyle(el);"
            + "var left=r.left+num(st.borderLeftWidth)+num(st.paddingLeft);"
            + "var top=r.top+num(st.borderTopWidth)+num(st.paddingTop);"
            + "var right=r.right-num(st.borderRightWidth)-num(st.paddingRight);"
            + "var bottom=r.bottom-num(st.borderBottomWidth)-num(st.paddingBottom);"
            + "pushRect(desc(el)+'::content-box',left,top,right-left,bottom-top,tag+':content-box');};"
            + "var pushWrapperHitStack=function(el,tag){"
            + "var r=el.getBoundingClientRect();"
            + "if(!r||r.width<40||r.height<40)return;"
            + "var pts=[[0.5,0.5],[0.25,0.5],[0.75,0.5],[0.5,0.25],[0.5,0.75],[0.08,0.5],[0.92,0.5],[0.5,0.08],[0.5,0.92]];"
            + "for(var pi=0;pi<pts.length;pi++){"
            + "var pt=pts[pi];"
            + "var x=r.left+r.width*pt[0];"
            + "var y=r.top+r.height*pt[1];"
            + "if(x<0||y<0||x>window.innerWidth||y>window.innerHeight)continue;"
            + "var stack=[];"
            + "try{stack=document.elementsFromPoint(x,y)||[];}catch(e){}"
            + "for(var si=0;si<stack.length;si++){"
            + "var current=stack[si];"
            + "if(!current)continue;"
            + "push(current,tag+':hit');"
            + "if(current===el)break;}}};"
            + "var pushWrapperInnerMedia=function(el,tag){"
            + "var count=0;"
            + "try{el.querySelectorAll('canvas,svg').forEach(function(inner){"
            + "if(count>=6)return;"
            + "count++;"
            + "push(inner,tag+':inner-media');"
            + "});}catch(e){}};"
            + "var refineWrapper=function(el,tag){"
            + "pushWrapperContentBox(el,tag);"
            + "pushWrapperInnerMedia(el,tag);"
            + "pushWrapperHitStack(el,tag);};"
            + "var gridModel=function(r,mode){"
            + "var left=r.left,top=r.top,width=r.width,height=r.height;"
            + "if(mode==='bounds'){var cx=width/19,cy=height/19;return {mode:mode,firstX:left+cx/2,firstY:top+cy/2,cellX:cx,cellY:cy};}"
            + "var ix=width*0.055,iy=height*0.058;"
            + "return {mode:mode,firstX:left+ix,firstY:top+iy,cellX:(width-ix*2)/18,cellY:(height-iy*2)/18};};"
            + "var clusterAxis=function(points,key,tol){"
            + "var vals=[];for(var i=0;i<points.length;i++){var v=points[i]&&points[i][key];if(isFinite(v))vals.push(v);}vals.sort(function(a,b){return a-b;});"
            + "var clusters=[];for(var vi=0;vi<vals.length;vi++){var value=vals[vi];var last=clusters.length?clusters[clusters.length-1]:null;"
            + "if(!last||Math.abs(value-(last.sum/last.count))>tol){clusters.push({sum:value,count:1,min:value,max:value});continue;}"
            + "last.sum+=value;last.count++;if(value<last.min)last.min=value;if(value>last.max)last.max=value;}"
            + "return clusters.map(function(c){return {value:c.sum/c.count,count:c.count,spread:c.max-c.min};});};"
            + "var median=function(values){if(!values.length)return 0;var sorted=values.slice().sort(function(a,b){return a-b;});var mid=Math.floor(sorted.length/2);return sorted.length%2?sorted[mid]:(sorted[mid-1]+sorted[mid])/2;};"
            + "var pickUniformAxis=function(clusters,target,minSpan){"
            + "if(clusters.length<target)return null;var best=null,bestScore=1e9;"
            + "for(var start=0;start<=clusters.length-target;start++){var window=clusters.slice(start,start+target);var span=window[target-1].value-window[0].value;if(span<minSpan)continue;"
            + "var diffs=[],countScore=0;for(var i=0;i<window.length;i++){countScore+=window[i].count;if(i>0)diffs.push(window[i].value-window[i-1].value);}var step=median(diffs);if(!isFinite(step)||step<=0)continue;"
            + "var error=0;for(var di=0;di<diffs.length;di++){error+=Math.abs(diffs[di]-step);}var score=error-(countScore*0.25);if(score<bestScore){bestScore=score;best={first:window[0].value,cell:step,count:window.length,error:error};}}"
            + "return best;};"
            + "var candidateScore=function(c){"
            + "if(!c||!c.rect)return -9999;"
            + "var n=String(c.node||''),rs=String(c.reason||'');"
            + "var s=0;"
            + "if(n.indexOf('#board')>=0)s+=120;"
            + "if(n.indexOf('wgo-board')>=0)s+=100;"
            + "if(n.indexOf('wgo-player-board')>=0)s+=90;"
            + "if(n.indexOf('canvas')===0)s+=110;"
            + "if(n.indexOf('svg')===0)s+=110;"
            + "if(rs.indexOf('selector:')===0)s+=40;"
            + "if(rs.indexOf(':inner-media')>=0)s+=95;"
            + "if(rs.indexOf(':hit')>=0)s+=70;"
            + "if(rs.indexOf(':content-box')>=0)s-=60;"
            + "if(n.indexOf('div')===0&&rs.indexOf('selector:')===0&&rs.indexOf(':content-box')<0&&rs.indexOf(':hit')<0&&rs.indexOf(':inner-media')<0)s-=25;"
            + "var w=c.rect.width||0,h=c.rect.height||0;"
            + "var d=Math.abs(w-h);if(d<=Math.max(12,w/12))s+=40;else s-=Math.min(60,d);"
            + "return s;};"
            + "var rankCandidates=function(list){"
            + "return (list||[]).filter(function(c){return c&&c.rect;}).sort(function(a,b){"
            + "var ds=candidateScore(b)-candidateScore(a);if(ds)return ds;"
            + "var aa=(a.rect.width||0)*(a.rect.height||0),ab=(b.rect.width||0)*(b.rect.height||0);"
            + "return ab-aa;});};"
            + "var pickGrid=function(list){"
            + "var ranked=rankCandidates(list);return ranked.length?ranked[0]:null;};"
            + "var smallCenters=function(r){"
            + "var out=[],nodes=[];var min=Math.max(8,Math.min(r.width,r.height)/42),max=Math.max(24,Math.min(r.width,r.height)/6);"
            + "try{Array.prototype.forEach.call(document.querySelectorAll('*'),function(el){"
            + "if(out.length>=160)return;var b=el.getBoundingClientRect();"
            + "if(!b||b.width<min||b.height<min||b.width>max||b.height>max)return;"
            + "var cx=b.left+b.width/2,cy=b.top+b.height/2;"
            + "if(cx<r.left||cx>r.left+r.width||cy<r.top||cy>r.top+r.height)return;"
            + "out.push({x:norm(cx),y:norm(cy),w:norm(b.width),h:norm(b.height),node:desc(el)});"
            + "if(nodes.length<20)nodes.push(desc(el));"
            + "});}catch(e){}return {centers:out,nodes:nodes};};"
            + "var gridFromGridLines=function(r){"
            + "var hs=[],vs=[],probed=0,maxProbe=4000;"
            + "var inRect=function(b){return b&&b.width>0&&b.height>0&&b.left>=r.left-2&&b.top>=r.top-2&&b.left+b.width<=r.left+r.width+2&&b.top+b.height<=r.top+r.height+2;};"
            + "var consider=function(b){"
            + "if(!inRect(b))return;"
            + "var thick=Math.min(b.width,b.height),longSide=Math.max(b.width,b.height);"
            + "if(thick>4)return;if(longSide<r.width*0.6&&longSide<r.height*0.6)return;"
            + "if(b.width>=b.height){hs.push({pos:b.top+b.height/2,start:b.left,end:b.left+b.width});}"
            + "else{vs.push({pos:b.left+b.width/2,start:b.top,end:b.top+b.height});}};"
            + "try{Array.prototype.forEach.call(document.querySelectorAll('line,path,rect,polyline'),function(el){"
            + "if(probed++>maxProbe)return;"
            + "var b;try{b=el.getBoundingClientRect();}catch(e){return;}"
            + "consider(b);});}catch(e){}"
            + "var dedupe=function(arr,tol){arr.sort(function(a,b){return a.pos-b.pos;});var out=[];for(var i=0;i<arr.length;i++){if(!out.length||arr[i].pos-out[out.length-1].pos>tol)out.push(arr[i]);}return out;};"
            + "var tol=Math.max(2,Math.min(r.width,r.height)/80);"
            + "hs=dedupe(hs,tol);vs=dedupe(vs,tol);"
            + "if(hs.length<18||vs.length<18)return null;"
            + "var firstX=vs[0].pos,lastX=vs[vs.length-1].pos;"
            + "var firstY=hs[0].pos,lastY=hs[hs.length-1].pos;"
            + "if(lastX-firstX<r.width*0.5||lastY-firstY<r.height*0.5)return null;"
            + "return {mode:'svg-lines',firstX:norm(firstX),firstY:norm(firstY),cellX:norm((lastX-firstX)/18),cellY:norm((lastY-firstY)/18),xCount:vs.length,yCount:hs.length};};"
            + "var pickPeaks=function(arr,target){"
            + "var n=arr.length;if(n<target)return null;"
            + "var maxv=0;for(var i=0;i<n;i++)if(arr[i]>maxv)maxv=arr[i];"
            + "if(maxv<=0)return null;"
            + "var thr=maxv*0.4;"
            + "var peaks=[];var i=0;"
            + "while(i<n){if(arr[i]<thr){i++;continue;}"
            + "var j=i,sumW=0,sumWX=0;"
            + "while(j<n&&arr[j]>=thr){sumW+=arr[j];sumWX+=arr[j]*j;j++;}"
            + "peaks.push(sumWX/sumW);i=j;}"
            + "if(peaks.length<target)return null;"
            + "var best=null;"
            + "for(var s=0;s+target<=peaks.length;s++){"
            + "var first=peaks[s],last=peaks[s+target-1];"
            + "var step=(last-first)/(target-1);if(step<=0)continue;"
            + "var err=0;for(var k=0;k<target;k++){err+=Math.abs(peaks[s+k]-(first+step*k));}"
            + "if(!best||err<best.err)best={first:first,step:step,err:err};}"
            + "return best;};"
            + "var gridFromCanvasPixels=function(r){"
            + "try{"
            + "var canvases=document.querySelectorAll('canvas');"
            + "var picked=null,pickedRect=null;"
            + "for(var i=0;i<canvases.length;i++){"
            + "var cv=canvases[i];if(cv.id==='__lizzie_yike_grid_probe_overlay')continue;"
            + "var b=cv.getBoundingClientRect();"
            + "if(b.width<r.width*0.7||b.height<r.height*0.7)continue;"
            + "if(Math.abs(b.left-r.left)>r.width*0.3||Math.abs(b.top-r.top)>r.height*0.3)continue;"
            + "if(!cv.width||!cv.height)continue;"
            + "if(!picked||cv.width*cv.height>picked.width*picked.height){picked=cv;pickedRect=b;}}"
            + "if(!picked)return null;"
            + "var ctx=picked.getContext('2d');var img;try{img=ctx.getImageData(0,0,picked.width,picked.height);}catch(e){return null;}"
            + "var data=img.data,W=picked.width,H=picked.height;"
            + "var cols=new Float32Array(W),rows=new Float32Array(H);"
            + "var sx=Math.max(1,Math.floor(W/700)),sy=Math.max(1,Math.floor(H/700));"
            + "for(var y=0;y<H;y+=sy){var base=y*W*4;for(var x=0;x<W;x+=sx){var p=base+x*4;var rr=data[p],gg=data[p+1],bb=data[p+2],aa=data[p+3];if(aa<32)continue;var lum=(rr+gg+bb)/3;if(lum<90){cols[x]+=1;rows[y]+=1;}}}"
            + "var px=pickPeaks(cols,19),py=pickPeaks(rows,19);"
            + "if(!px||!py)return null;"
            + "var scaleX=pickedRect.width/W,scaleY=pickedRect.height/H;"
            + "var firstX=pickedRect.left+px.first*scaleX;"
            + "var firstY=pickedRect.top+py.first*scaleY;"
            + "var cellX=px.step*scaleX,cellY=py.step*scaleY;"
            + "if(cellX<8||cellY<8)return null;"
            + "return {mode:'canvas-pixels',firstX:norm(firstX),firstY:norm(firstY),cellX:norm(cellX),cellY:norm(cellY),canvasW:W,canvasH:H,errX:norm(px.err),errY:norm(py.err)};"
            + "}catch(e){return null;}};"
            + "var gridFromCenters=function(r,small){"
            + "if(!small||!small.centers||small.centers.length<24)return null;"
            + "var tol=Math.max(2,Math.min(r.width,r.height)/80);"
            + "var x=pickUniformAxis(clusterAxis(small.centers,'x',tol),19,r.width*0.55);"
            + "var y=pickUniformAxis(clusterAxis(small.centers,'y',tol),19,r.height*0.55);"
            + "if(!x||!y)return null;"
            + "return {mode:'dom-centers',firstX:norm(x.first),firstY:norm(y.first),cellX:norm(x.cell),cellY:norm(y.cell),xCount:x.count,yCount:y.count};};"
            + "var drawProbe=function(r,bounds,grid){"
            + "var id='__lizzie_yike_grid_probe_overlay';var c=document.getElementById(id);"
            + "if(!c){c=document.createElement('canvas');c.id=id;c.style.cssText='position:fixed;left:0;top:0;width:100vw;height:100vh;pointer-events:none;z-index:2147483646';document.documentElement.appendChild(c);}"
            + "var dpr=window.devicePixelRatio||1;c.width=Math.round(window.innerWidth*dpr);c.height=Math.round(window.innerHeight*dpr);"
            + "var ctx=c.getContext('2d');ctx.setTransform(dpr,0,0,dpr,0,0);ctx.clearRect(0,0,window.innerWidth,window.innerHeight);"
            + "var draw=function(m,color){ctx.fillStyle=color;for(var x=0;x<19;x++){for(var y=0;y<19;y++){var px=m.firstX+m.cellX*x,py=m.firstY+m.cellY*y;ctx.beginPath();ctx.arc(px,py,(x===9&&y===9)?4:2,0,Math.PI*2);ctx.fill();}}};"
            + "ctx.strokeStyle='rgba(255,255,255,.9)';ctx.strokeRect(r.left,r.top,r.width,r.height);"
            + "draw(bounds,'rgba(34,211,238,.88)');if(grid)draw(grid,'rgba(249,115,22,.88)');};"
            + "var emitGridProbe=function(list,reason){"
            + "var c=pickGrid(list);if(!c||!c.rect)return null;var r=c.rect;"
            + "var bounds=gridModel(r,'bounds'),line=gridModel(r,'line');var small=smallCenters(r);var structured=gridFromCenters(r,small);var svgLines=gridFromGridLines(r);var canvasPx=gridFromCanvasPixels(r);"
            + "var chosen=canvasPx||svgLines||structured||line;"
            + "drawProbe(r,bounds,chosen);"
            + "send({tag:'gridProbe',reason:reason,node:c.node,candidateReason:c.reason,rect:r,bounds:bounds,line:line,structured:structured,svgLines:svgLines,canvasPixels:canvasPx,smallCount:small.centers.length,sampleCenters:small.centers.slice(0,24),sampleNodes:small.nodes});"
            + "return chosen;};"
            + "['canvas','svg','[class*=board]','[id*=board]','[class*=goban]','[id*=goban]','[class*=chess]','[class*=weiqi]'].forEach(function(sel){"
            + "try{document.querySelectorAll(sel).forEach(function(el){"
            + "var tag='selector:'+sel;"
            + "push(el,tag);"
            + "if(sel!=='canvas'&&sel!=='svg')refineWrapper(el,tag);"
            + "});}catch(e){}});"
            + "Array.prototype.forEach.call(document.querySelectorAll('div,canvas,svg'),function(el){"
            + "var r=el.getBoundingClientRect();"
            + "if(r.width<200||r.height<200)return;"
            + "if(r.left>window.innerWidth*0.72||r.top>window.innerHeight*0.9)return;"
            + "var ratio=r.width/r.height;"
            + "if(ratio<0.75||ratio>1.35)return;"
            + "push(el,'square-candidate');"
            + "});"
            + "var ranked=rankCandidates(out);"
            + "var packet={"
            + "tag:'probe',"
            + "reason:reason,"
            + "href:location.href,"
            + "title:document.title,"
            + "viewport:{width:window.innerWidth,height:window.innerHeight,dpr:window.devicePixelRatio||1},"
            + "candidates:ranked.slice(0,40)"
            + "};"
            + "var structuredGrid=emitGridProbe(packet.candidates,reason);"
            + "if(structuredGrid)packet.grid=structuredGrid;"
            + "send(packet);"
            + "};"
            + "if(window.__lizzieYikeGeomProbeInstalled){collect('reinstall');return;}"
            + "window.__lizzieYikeGeomProbeInstalled=true;"
            + "send({tag:'installed',href:location.href,title:document.title});"
            + "setTimeout(function(){collect('delay-300');},300);"
            + "setTimeout(function(){collect('delay-1200');},1200);"
            + "setTimeout(function(){collect('delay-2500');},2500);"
            + "window.addEventListener('resize',function(){setTimeout(function(){collect('window-resize');},120);},{passive:true});"
            + "if(window.ResizeObserver&&!window.__lizzieYikeGeomResizeObserver){"
            + "window.__lizzieYikeGeomResizeObserver=new ResizeObserver(function(){setTimeout(function(){collect('resize-observer');},120);});"
            + "try{window.__lizzieYikeGeomResizeObserver.observe(document.body);}catch(e){}}"
            + "})();";
    browser_.executeJavaScript(js, browser_.getURL(), 0);
  }
}
