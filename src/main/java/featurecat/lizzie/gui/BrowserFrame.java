package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.MultiOutputStream;
import featurecat.lizzie.util.Utils;
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
  private boolean isYike;

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
              installYikeUnitePoller(pendingYikeRoomId, pendingYikeIntervalMs);
            }
          }

          @Override
          public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
            if (frame != null && frame.isMain() && pendingYikeRoomId > 0) {
              installYikeUnitePoller(pendingYikeRoomId, pendingYikeIntervalMs);
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
              if (isYike) Lizzie.frame.syncOnline(address_.getText());
            }
          }
        });

    // Update the address field when the browser URL changes.
    client_.addDisplayHandler(
        new CefDisplayHandlerAdapter() {
          @Override
          public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
            SwingUtilities.invokeLater(() -> address_.setText(url));
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
                  if (isYike && !currentUrl.equals(target_url)) {
                    Lizzie.frame.syncOnline(target_url);
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
            if (isYike) {
              Lizzie.frame.syncOnline(addr);
            }
            browser_.loadURL(addr);
          }
        });

    JLabel stop = makeLabelButton(Lizzie.resourceBundle.getString("LizzieFrame.stopSync"));
    stop.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseClicked(java.awt.event.MouseEvent e) {
            if (LizzieFrame.onlineDialog != null) {
              LizzieFrame.onlineDialog.stopSync();
            } else {
              setSyncStatus("无同步任务");
            }
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
            if (!yike) pendingYikeRoomId = 0;
            setTitle(baseTitle);
            setVisible(true);
            toolbar.setVisible(isYike);
            setFrameSize();
          }
        });
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

  /**
   * 在已加载的弈客对弈页面里被动 hook fetch/XHR：第一次抓到 game-server.yikeweiqi.com/game/info 的请求时 完整捕获请求（fetch 路径捕获
   * Request 对象，XHR 路径捕获 method/url/headers/body），之后每 intervalMs 用 同样方式重发，把响应通过 cefQuery 回传
   * Java。借用弈客网页自己的鉴权，不用关心 token / CORS。
   */
  public void installYikeUnitePoller(long roomId, int intervalMs) {
    pendingYikeRoomId = roomId;
    pendingYikeIntervalMs = intervalMs;
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
            + "send('ping','start polling kind='+window.__yikeUniteSaved.kind+' url='+window.__yikeUniteSaved.url+' period='+PERIOD);"
            + "window.__yikeUnitePoller=setInterval(poll,PERIOD);"
            + "};"
            + "if(window.__yikeUniteHookInstalled){"
            + "send('ping','hook already installed');"
            + "if(window.__yikeUniteSaved)startPolling();"
            + "return;}"
            + "window.__yikeUniteHookInstalled=true;"
            + "send('ping','installing hook for room='+ROOM);"
            + "window.fetch=function(input,init){"
            + "var url=typeof input==='string'?input:(input&&input.url);"
            + "var p=origFetch.apply(this,arguments);"
            + "if(matchHttp(url)){"
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
            + "x.addEventListener('load',function(){try{if(matchHttp(x.__yikeUrl)){"
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
            + "})();";
    browser_.executeJavaScript(js, browser_.getURL(), 0);
  }

  public void stopYikeUnitePoller() {
    pendingYikeRoomId = 0;
    String js =
        "if(window.__yikeUnitePoller){clearInterval(window.__yikeUnitePoller);"
            + "window.__yikeUnitePoller=null;}"
            + "if(window.__yikeUniteWsDebounce){clearTimeout(window.__yikeUniteWsDebounce);"
            + "window.__yikeUniteWsDebounce=null;}"
            + "window.__yikeUniteSaved=null;";
    browser_.executeJavaScript(js, browser_.getURL(), 0);
  }
}
