package featurecat.lizzie;

import featurecat.lizzie.analysis.EngineCommandSink;
import featurecat.lizzie.analysis.EngineFollowController;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.LeelazEngineCommandSink;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.enginegame.EngineGameModule;
import featurecat.lizzie.enginegame.EngineGameSnapshot;

import featurecat.lizzie.gui.AppleStyleSupport;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.DesktopTimeControl;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.gui.FirstUseSettings;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.LoadEngine;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.web.WebBoardManager;
import featurecat.lizzie.logging.CrashHandlers;
import featurecat.lizzie.logging.EdtHangWatchdog;
import featurecat.lizzie.logging.LogCategories;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import featurecat.lizzie.logging.WorkDirectoryResolver;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.LocaleFontSupport;
import featurecat.lizzie.util.NetworkProxy;
import featurecat.lizzie.util.Utils;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Window;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import org.jdesktop.swingx.util.OS;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Main class. */
public class Lizzie {
  static {
    // ImageIO's disk cache routes every decoded image through a temp file. All reads here are
    // small classpath/theme images, so decode in memory; on Windows this also keeps antivirus
    // scanners out of the image loading path.
    ImageIO.setUseCache(false);
  }

  private static final Logger APP = LoggerFactory.getLogger(LogCategories.APP);

  public static ResourceBundle resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings");
  public static final EngineStartupStatus engineStartupStatus = new EngineStartupStatus();
  public static Config config;
  public static GtpConsolePane gtpConsole;
  public static LizzieFrame frame;
  public static JDialog loadEngine;
  public static FirstUseSettings firstUseSettings;
  public static Board board;
  public static Leelaz leelaz;
  public static Leelaz leelaz2;
  private static final Object PRIMARY_ENGINE_LOCK = new Object();
  private static final ReentrantReadWriteLock ENGINE_AUTHORITY_PRESENTATION_LOCK =
      new ReentrantReadWriteLock(true);
  private static final ThreadLocal<Integer> ENGINE_AUTHORITY_PRESENTATION_DEPTH =
      ThreadLocal.withInitial(() -> 0);
  private static long primaryEngineGeneration;

  public static void setPrimaryEngine(Leelaz engine) {
    runWithEngineAuthorityMutation(
        () -> {
          synchronized (PRIMARY_ENGINE_LOCK) {
            leelaz = engine;
            primaryEngineGeneration++;
          }
          return null;
        });
  }

  /** Atomically replaces only the exact primary generation captured by a deferred action. */
  public static boolean setPrimaryEngineIfCurrent(
      Leelaz expected, long expectedGeneration, Leelaz replacement) {
    return runWithEngineAuthorityMutation(
        () -> {
          synchronized (PRIMARY_ENGINE_LOCK) {
            if (leelaz != expected || primaryEngineGeneration != expectedGeneration) {
              return false;
            }
            leelaz = replacement;
            primaryEngineGeneration++;
            return true;
          }
        });
  }

  public static void setBoard(Board replacement) {
    runWithEngineAuthorityMutation(
        () -> {
          board = replacement;
          return null;
        });
  }

  public static void setEngineManager(EngineManager replacement) {
    runWithEngineAuthorityMutation(
        () -> {
          engineManager = replacement;
          return null;
        });
  }

  public static <T> T runWithEngineAuthorityMutation(Supplier<T> mutation) {
    if (mutation == null) {
      throw new IllegalArgumentException("mutation");
    }
    if (ENGINE_AUTHORITY_PRESENTATION_DEPTH.get() > 0) {
      throw new IllegalStateException(
          "Engine authority cannot be mutated by its active presentation owner");
    }
    ReentrantReadWriteLock.WriteLock writeLock =
        ENGINE_AUTHORITY_PRESENTATION_LOCK.writeLock();
    writeLock.lock();
    try {
      return mutation.get();
    } finally {
      writeLock.unlock();
    }
  }

  public static EngineAuthorityPresentationLease claimEngineAuthorityPresentation(
      Board expectedBoard,
      EngineManager expectedManager,
      Leelaz expectedPrimary,
      long expectedPrimaryGeneration) {
    ReentrantReadWriteLock.ReadLock readLock =
        ENGINE_AUTHORITY_PRESENTATION_LOCK.readLock();
    readLock.lock();
    boolean claimed = false;
    try {
      synchronized (PRIMARY_ENGINE_LOCK) {
        if (board != expectedBoard
            || engineManager != expectedManager
            || leelaz != expectedPrimary
            || primaryEngineGeneration != expectedPrimaryGeneration) {
          return null;
        }
      }
      ENGINE_AUTHORITY_PRESENTATION_DEPTH.set(
          ENGINE_AUTHORITY_PRESENTATION_DEPTH.get() + 1);
      claimed = true;
      return new EngineAuthorityPresentationLease(Thread.currentThread());
    } finally {
      if (!claimed) {
        readLock.unlock();
      }
    }
  }

  public static final class EngineAuthorityPresentationLease implements AutoCloseable {
    private final Thread owner;
    private final AtomicBoolean released = new AtomicBoolean();

    private EngineAuthorityPresentationLease(Thread owner) {
      this.owner = owner;
    }

    @Override
    public void close() {
      if (Thread.currentThread() != owner) {
        throw new IllegalStateException(
            "Engine authority presentation lease must be released by its owner");
      }
      if (!released.compareAndSet(false, true)) {
        return;
      }
      int depth = ENGINE_AUTHORITY_PRESENTATION_DEPTH.get();
      if (depth <= 0) {
        throw new IllegalStateException("Engine authority presentation lease depth underflow");
      }
      if (depth == 1) {
        ENGINE_AUTHORITY_PRESENTATION_DEPTH.remove();
      } else {
        ENGINE_AUTHORITY_PRESENTATION_DEPTH.set(depth - 1);
      }
      ENGINE_AUTHORITY_PRESENTATION_LOCK.readLock().unlock();
    }
  }

  public static long capturePrimaryEngineGeneration(Leelaz expected) {
    synchronized (PRIMARY_ENGINE_LOCK) {
      return leelaz == expected ? primaryEngineGeneration : -1L;
    }
  }

  public static boolean runIfPrimaryEngine(
      Leelaz expected, long expectedGeneration, Runnable action) {
    synchronized (PRIMARY_ENGINE_LOCK) {
      if (leelaz != expected || primaryEngineGeneration != expectedGeneration) {
        return false;
      }
      action.run();
      return true;
    }
  }

  /**
   * Runs a short state-only mutation while the exact PRIMARY generation remains current.
   *
   * <p>The authority write lock is held across both the generation check and the supplied action,
   * so an active presentation lease freezes PRIMARY until its presentation is complete. The
   * capability lets callers replace PRIMARY without re-entering its monitor from an engine
   * endpoint callback. The supplied action must not perform I/O or invoke arbitrary UI callbacks.
   */
  public static boolean runIfPrimaryEngineWithMutation(
      Leelaz expected,
      long expectedGeneration,
      Consumer<PrimaryEngineMutation> action) {
    if (action == null) {
      return false;
    }
    return runWithEngineAuthorityMutation(
        () -> {
          synchronized (PRIMARY_ENGINE_LOCK) {
            if (leelaz != expected || primaryEngineGeneration != expectedGeneration) {
              return false;
            }
            boolean[] active = {true};
            try {
              action.accept(
                  replacement -> {
                    if (!active[0] || !Thread.holdsLock(PRIMARY_ENGINE_LOCK)) {
                      throw new IllegalStateException(
                          "PRIMARY mutation capability used outside its ownership scope");
                    }
                    leelaz = replacement;
                    primaryEngineGeneration++;
                  });
            } finally {
              active[0] = false;
            }
            return true;
          }
        });
  }

  @FunctionalInterface
  public interface PrimaryEngineMutation {
    void replaceWith(Leelaz replacement);
  }

  public static String appName = "LizzieYzy Next";
  public static String lizzieVersion = "2.5.3";
  private static final String DEFAULT_NEXT_VERSION = "next-dev";
  private static final String SMOKE_OPEN_BOARD_SYNC_PROPERTY = "lizzie.smoke.openBoardSync";
  private static final String SMOKE_OPEN_BOARD_SYNC_DELAY_MS_PROPERTY =
      "lizzie.smoke.openBoardSyncDelayMs";
  private static final String SMOKE_OPEN_AUTO_SETUP_PROPERTY = "lizzie.smoke.openAutoSetup";
  private static final String SMOKE_OPEN_AUTO_SETUP_DELAY_MS_PROPERTY =
      "lizzie.smoke.openAutoSetupDelayMs";
  private static final String SMOKE_OPEN_YIKE_WEB_PROPERTY = "lizzie.smoke.openYikeWeb";
  private static final String SMOKE_OPEN_YIKE_WEB_DELAY_MS_PROPERTY =
      "lizzie.smoke.openYikeWebDelayMs";
  private static final String SMOKE_OPEN_REMOTE_COMPUTE_PROPERTY =
      "lizzie.smoke.openRemoteCompute";
  private static final String SMOKE_OPEN_REMOTE_COMPUTE_DELAY_MS_PROPERTY =
      "lizzie.smoke.openRemoteComputeDelayMs";
  private static final String UNKNOWN_HOST_NAME = "unknown-host";
  private static final long HOST_NAME_LOOKUP_TIMEOUT_MILLIS = 500L;
  private static final long LOCAL_HOST_NAME_COMMAND_TIMEOUT_MILLIS = 250L;
  public static String nextVersion = resolveNextVersion();
  public static String checkVersion = "230614";
  public static boolean readMode = false;
  private static String[] mainArgs;
  public static EngineManager engineManager;
  public static final EngineGameModule engineGame = new EngineGameModule();

  public static featurecat.lizzie.analysis.EngineFollowController engineFollowController;
  public static WebBoardManager webBoardManager = new WebBoardManager();
  public static int javaVersion = 8;
  public static Float javaScaleFactor = 1.0f;
  public static boolean isMultiScreen = false;
  public static String javaVersionString = "";
  private static Image applicationIcon;
  private static boolean firstLaunchSession;
  private static volatile boolean startupProfileSaveFailed;
  public static Float sysScaleFactor = initialSystemScaleFactor();

  static float initialSystemScaleFactor() {
    if (!OS.isWindows() || GraphicsEnvironment.isHeadless()) {
      return 1.0f;
    }
    return java.awt.Toolkit.getDefaultToolkit().getScreenResolution() / 96.0f;
  }

  /** Launches the game window, and runs the game. */
  public static void main(String[] args) throws IOException {
    mainArgs = args;
    // Must be set before any AWT/Swing class initializes. macOS always antialiases
    // text; on Windows/Linux these flags are what keep Swing text from rendering
    // jagged under the cross-platform look and feel.
    if (System.getProperty("awt.useSystemAAFontSettings") == null) {
      System.setProperty("awt.useSystemAAFontSettings", "on");
    }
    if (System.getProperty("swing.aatext") == null) {
      System.setProperty("swing.aatext", "true");
    }
    bootstrapLogging();
    config = new Config();
    LoggingRuntime.current().ifPresent(runtime -> runtime.applySettings(config.loggingSettings));
    logPersistedLoggingSettingsApplied();
    firstLaunchSession = config.isNewProfile() || config.firstTimeLoad;
    resourceBundle = AppLocale.loadBundle(config.useLanguage);
    NetworkProxy.installSystemProxyPropertyFromSavedConfig();
    Utils.applyMaintainedDefaultSettings();
    // -Dsun.java2d.uiScale.enabled=false
    // -Dsun.java2d.win.uiScaleX=1.25 -Dsun.java2d.win.uiScaleY=1.25
    // -Dsun.java2d.win.uiScaleX=125% -Dsun.java2d.win.uiScaleY=125%
    // -Dsun.java2d.win.uiScaleX=120dpi -Dsun.java2d.win.uiScaleY=120dpi
    //  System.out.println(System.getProperty("sun.java2d.win.uiScaleX"));
    // System.setProperty("sun.java2d.uiScale.enabled", "false");
    // -Dsun.java2d.uiScale=1.0
    javaVersionString = System.getProperty("java.version");
    try {
      int majorVersion;
      if (javaVersionString.startsWith("1.")) {
        majorVersion =
            Integer.parseInt(javaVersionString.substring(2, javaVersionString.indexOf('.', 2)));
      } else {
        majorVersion =
            Integer.parseInt(javaVersionString.substring(0, javaVersionString.indexOf('.')));
      }
      javaVersion = Math.max(8, majorVersion);
    } catch (Exception e) {
      javaVersion = 8;
    }
    installApplicationIcon();
    leelaz = new Leelaz("");
    isMultiScreen = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length > 1;
    // The HiDPI scale must be known synchronously before any window is sized; a paint-based
    // probe only delivers it after the EDT gets around to painting.
    AffineTransform defaultTransform =
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getDefaultScreenDevice()
            .getDefaultConfiguration()
            .getDefaultTransform();
    if (defaultTransform.getScaleX() > 1) {
      Config.isScaled = true;
      javaScaleFactor = (float) defaultTransform.getScaleX();
    }
    String storedHostName = config.hostName == null ? "" : config.hostName;
    String hostName = resolveHostNameSafely(storedHostName);
    boolean machineChanged = shouldTreatAsHostChange(storedHostName, hostName);
    if (isKnownHostName(hostName) && !hostName.equals(storedHostName)) {
      config.hostName = hostName;
      config.uiConfig.put("host-name", hostName);
    }
    if (config.firstTimeLoad || machineChanged) {
      if (machineChanged) config.deletePersist(false);
      resetAllHints();
      config.isChinese = (resourceBundle.getString("Lizzie.isChinese")).equals("yes");
      completeAutomaticFirstRunSetup();
    }
    resourceBundle = AppLocale.loadBundle(config.useLanguage);
    config.isChinese = (resourceBundle.getString("Lizzie.isChinese")).equals("yes");
    Locale appLocale = AppLocale.fromConfigValue(config.useLanguage).locale();
    if (resourceBundle.containsKey("Lizzie.defaultFontName")) {
      Config.sysDefaultFontName =
          LocaleFontSupport.resolveDefaultFontName(
              resourceBundle.getString("Lizzie.defaultFontName"), appLocale);
    }
    if (config.theme.uiFontName() != null) config.uiFontName = config.theme.uiFontName();
    if (config.theme.fontName() != null) config.fontName = config.theme.fontName();
    config.uiFontName =
        LocaleFontSupport.resolveConfiguredFontName(
            config.uiFontName, appLocale, Config.sysDefaultFontName);
    config.fontName =
        LocaleFontSupport.resolveConfiguredFontName(
            config.fontName, appLocale, Config.sysDefaultFontName);
    Utils.loadFonts(config.uiFontName, config.fontName, config.winrateFontName);
    config.shareLabel1 =
        config.uiConfig.optString(
            "share-label-1", resourceBundle.getString("ShareFrame.shareLabel1"));
    config.shareLabel2 =
        config.uiConfig.optString(
            "share-label-2", resourceBundle.getString("ShareFrame.shareLabel2"));
    config.shareLabel3 =
        config.uiConfig.optString(
            "share-label-3", resourceBundle.getString("ShareFrame.shareLabel3"));
    setLookAndFeel();
    Locale.setDefault(Locale.ENGLISH);
    boolean noConfiguredEngine = !hasConfiguredEngine();
    if (!startupProfileSaveFailed
        && shouldOfferEngineRepair(
            !noConfiguredEngine, config.uiConfig.optBoolean("autoload-empty", false))) {
      engineStartupStatus.needsRepair(
          "EngineStartup.needsRepair",
          "AI is not ready - click to repair",
          text("EngineStartup.noBundledEngine", "No complete built-in engine setup was found."));
    }
    if (Lizzie.config.uiConfig.optBoolean("autoload-default", false)) {
      startConfiguredEngine(-1, true);
    } else if (Lizzie.config.uiConfig.optBoolean("autoload-last", false)) {
      int lastEngine = Lizzie.config.uiConfig.optInt("last-engine", -1);
      startConfiguredEngine(lastEngine, false);
    } else if (Lizzie.config.uiConfig.optBoolean("autoload-empty", false)) {
      start(-1, false);
    } else {
      if (mainArgs.length == 1) {
        if (mainArgs[0].equals("read")) {
          readMode = true;
          config.showStatus = false;
          start(-1, false);
          return;
        }
      }
      if (noConfiguredEngine) {
        start(-1, false);
      } else if (firstLaunchSession) {
        int engineCount = Utils.getEngineData().size();
        int defaultEngine = config.uiConfig.optInt("default-engine", 0);
        if (defaultEngine < 0 || defaultEngine >= engineCount) {
          defaultEngine = 0;
        }
        startConfiguredEngine(defaultEngine, true);
      } else {
        loadEngine = LoadEngine.createDialog();
        APP.info("application startup engine-selection");
        loadEngine.setVisible(true);
      }
    }
    if (Lizzie.config.autoReplayBranch) frame.autoReplayBranch();
    scheduleBoardSyncSmokeProbe();
    scheduleAutoSetupSmokeProbe();
    scheduleYikeWebSmokeProbe();
    scheduleRemoteComputeSmokeProbe();
  }

  public static WorkDirectoryResolution bootstrapLogging() {
    WorkDirectoryResolution workDirectory = WorkDirectoryResolver.resolve();
    try {
      Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
      if (!Files.isWritable(cwd)) {
        System.setProperty("user.dir", workDirectory.directory().toString());
      }
    } catch (Exception ignored) {
    }
    try {
      LoggingRuntime.initialize(workDirectory);
    } catch (Throwable t) {
      System.err.println(
          LoggingRuntime.STDERR_PREFIX + "bootstrap " + t.getClass().getSimpleName());
    }
    CrashHandlers.install();
    EdtHangWatchdog.installDefault();
    logStartupIdentity();
    return workDirectory;
  }

  public static void logStartupIdentity() {
    if (!APP.isInfoEnabled()) {
      return;
    }
    APP.info(
        "application version={} java.vendor={} java.version={} java.runtime={}",
        nextVersion,
        System.getProperty("java.vendor"),
        System.getProperty("java.version"),
        System.getProperty("java.runtime.version"));
  }

  public static void logPersistedLoggingSettingsApplied() {
    LoggingRuntime.current()
        .ifPresent(
            runtime -> {
              if (!APP.isInfoEnabled()) {
                return;
              }
              APP.info(
                  "persisted logging settings applied diagnostics={} modules={} scopes={}",
                  runtime.settings().diagnosticsEnabled(),
                  runtime.settings().diagnosticModules().size(),
                  runtime.settings().preferredTraceScopes().size());
            });
  }

  public static void logApplicationReady() {
    APP.info("application ready");
  }

  public static void logShutdownRequested() {
    APP.info("application shutdown requested");
  }

  /**
   * Resolves the machine name on a daemon thread with a strict startup deadline. A DNS failure or
   * stall must never prevent launch or masquerade as a machine change, which would wipe persisted
   * state.
   */
  static String resolveHostNameSafely(String storedHostName) {
    return resolveHostNameSafely(
        storedHostName,
        Lizzie::resolveLocalHostNameWithoutNetwork,
        HOST_NAME_LOOKUP_TIMEOUT_MILLIS);
  }

  /**
   * Reads the machine name without DNS or LAN discovery. In particular, {@code
   * InetAddress.getLocalHost()} can trigger the macOS local-network permission prompt even though
   * startup only needs a stable local label.
   */
  static String resolveLocalHostNameWithoutNetwork() throws IOException {
    String environmentName =
        firstNonBlank(System.getenv("COMPUTERNAME"), System.getenv("HOSTNAME"));
    if (!environmentName.isEmpty()) {
      return environmentName;
    }

    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
      Path hostnameFile = Path.of("/etc/hostname");
      if (Files.isRegularFile(hostnameFile) && Files.isReadable(hostnameFile)) {
        String fileName = Files.readString(hostnameFile, StandardCharsets.UTF_8).trim();
        if (!fileName.isEmpty()) {
          return fileName;
        }
      }
    }
    return resolveHostNameFromCommand();
  }

  static String firstNonBlank(String... candidates) {
    if (candidates == null) {
      return "";
    }
    for (String candidate : candidates) {
      if (candidate != null && !candidate.trim().isEmpty()) {
        return candidate.trim();
      }
    }
    return "";
  }

  private static String resolveHostNameFromCommand() throws IOException {
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    List<String> command = windows ? List.of("hostname") : List.of("/bin/hostname");
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    try {
      if (!process.waitFor(LOCAL_HOST_NAME_COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return "";
      }
      if (process.exitValue() != 0) {
        return "";
      }
      return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    } finally {
      process.destroy();
    }
  }

  static String resolveHostNameSafely(
      String storedHostName, Callable<String> resolver, long timeoutMillis) {
    FutureTask<String> lookup = new FutureTask<>(resolver);
    Thread lookupThread = new Thread(lookup, "lizzie-hostname-lookup");
    lookupThread.setDaemon(true);
    lookupThread.start();
    try {
      String resolved = lookup.get(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
      return resolved == null || resolved.trim().isEmpty()
          ? hostNameFallback(storedHostName)
          : resolved;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      lookup.cancel(true);
      return hostNameFallback(storedHostName);
    } catch (Exception e) {
      lookup.cancel(true);
      return hostNameFallback(storedHostName);
    }
  }

  static String hostNameFallback(String storedHostName) {
    return storedHostName == null || storedHostName.isEmpty() ? UNKNOWN_HOST_NAME : storedHostName;
  }

  static boolean shouldTreatAsHostChange(String storedHostName, String resolvedHostName) {
    return isKnownHostName(storedHostName)
        && isKnownHostName(resolvedHostName)
        && !normalizeHostName(storedHostName).equals(normalizeHostName(resolvedHostName));
  }

  private static String normalizeHostName(String hostName) {
    String normalized = hostName == null ? "" : hostName.trim();
    while (normalized.endsWith(".")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized.toLowerCase(Locale.ROOT);
  }

  private static boolean isKnownHostName(String hostName) {
    return hostName != null && !hostName.isEmpty() && !UNKNOWN_HOST_NAME.equals(hostName);
  }

  private static void scheduleBoardSyncSmokeProbe() {
    if (!Boolean.getBoolean(SMOKE_OPEN_BOARD_SYNC_PROPERTY)) {
      return;
    }

    int delayMs = Math.max(0, Integer.getInteger(SMOKE_OPEN_BOARD_SYNC_DELAY_MS_PROPERTY, 5000));
    Thread smokeThread =
        new Thread(
            () -> {
              try {
                Thread.sleep(delayMs);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }

              SwingUtilities.invokeLater(
                  () -> {
                    if (frame == null) {
                      System.err.println("Board sync smoke probe skipped: frame unavailable.");
                      return;
                    }
                    System.out.println("Board sync smoke probe: invoking openBoardSync().");
                    frame.openBoardSync();
                  });
            },
            "lizzie-board-sync-smoke");
    smokeThread.setDaemon(true);
    smokeThread.start();
  }

  private static void scheduleAutoSetupSmokeProbe() {
    if (!Boolean.getBoolean(SMOKE_OPEN_AUTO_SETUP_PROPERTY)) {
      return;
    }

    int delayMs = Math.max(0, Integer.getInteger(SMOKE_OPEN_AUTO_SETUP_DELAY_MS_PROPERTY, 3000));
    Thread smokeThread =
        new Thread(
            () -> {
              try {
                Thread.sleep(delayMs);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }

              SwingUtilities.invokeLater(
                  () -> {
                    if (frame == null) {
                      System.err.println("Auto setup smoke probe skipped: frame unavailable.");
                      return;
                    }
                    System.out.println("Auto setup smoke probe: opening KataGo Auto Setup.");
                    frame.openKataGoAutoSetup();
                  });
            },
            "lizzie-auto-setup-smoke");
    smokeThread.setDaemon(true);
    smokeThread.start();
  }

  private static void scheduleYikeWebSmokeProbe() {
    if (!Boolean.getBoolean(SMOKE_OPEN_YIKE_WEB_PROPERTY)) {
      return;
    }

    int delayMs = Math.max(0, Integer.getInteger(SMOKE_OPEN_YIKE_WEB_DELAY_MS_PROPERTY, 3000));
    Thread smokeThread =
        new Thread(
            () -> {
              try {
                Thread.sleep(delayMs);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }

              SwingUtilities.invokeLater(
                  () -> {
                    if (frame == null) {
                      System.err.println("Yike web smoke probe skipped: frame unavailable.");
                      return;
                    }
                    System.out.println("Yike web smoke probe: opening the built-in Yike page.");
                    frame.openYikeLiveWeb();
                  });
            },
            "lizzie-yike-web-smoke");
    smokeThread.setDaemon(true);
    smokeThread.start();
  }

  private static void scheduleRemoteComputeSmokeProbe() {
    if (!Boolean.getBoolean(SMOKE_OPEN_REMOTE_COMPUTE_PROPERTY)) {
      return;
    }

    int delayMs =
        Math.max(0, Integer.getInteger(SMOKE_OPEN_REMOTE_COMPUTE_DELAY_MS_PROPERTY, 3000));
    Thread smokeThread =
        new Thread(
            () -> {
              try {
                Thread.sleep(delayMs);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }

              SwingUtilities.invokeLater(
                  () -> {
                    if (frame == null) {
                      System.err.println("Remote compute smoke probe skipped: frame unavailable.");
                      return;
                    }
                    System.out.println("Remote compute smoke probe: opening Remote Compute.");
                    frame.openRemoteComputeCenter();
                  });
            },
            "lizzie-remote-compute-smoke");
    smokeThread.setDaemon(true);
    smokeThread.start();
  }

  public static String getAppDisplayName() {
    return appName + " " + nextVersion;
  }

  public static String resolveNextVersion() {
    return chooseNextVersion(
        System.getProperty("lizzie.next.version"), System.getenv("LIZZIE_NEXT_VERSION"));
  }

  static String chooseNextVersion(String propertyValue, String environmentValue) {
    String propertyVersion = trimToNull(propertyValue);
    if (propertyVersion != null) {
      return propertyVersion;
    }
    String environmentVersion = trimToNull(environmentValue);
    if (environmentVersion != null) {
      return environmentVersion;
    }
    return DEFAULT_NEXT_VERSION;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static void installApplicationIcon() {
    if (!System.getProperty("os.name", "").contains("Mac")) {
      return;
    }
    Image icon = loadApplicationIcon();
    if (icon == null) {
      return;
    }

    if (!trySetTaskbarIcon(icon) && javaVersion <= 8) {
      trySetAppleDockIcon(icon);
    }
  }

  private static Image loadApplicationIcon() {
    if (applicationIcon != null) {
      return applicationIcon;
    }
    try (InputStream iconStream = Lizzie.class.getResourceAsStream("/assets/logo.png")) {
      if (iconStream == null) {
        return null;
      }
      applicationIcon = ImageIO.read(iconStream);
      return applicationIcon;
    } catch (IOException e) {
      APP.error("failed to load application icon", e);
      return null;
    }
  }

  private static boolean trySetAppleDockIcon(Image icon) {
    try {
      Class<?> applicationClass = Class.forName("com.apple.eawt.Application");
      Object application = applicationClass.getMethod("getApplication").invoke(null);
      applicationClass.getMethod("setDockIconImage", Image.class).invoke(application, icon);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean trySetTaskbarIcon(Image icon) {
    try {
      Class<?> taskbarClass = Class.forName("java.awt.Taskbar");
      boolean taskbarSupported =
          Boolean.TRUE.equals(taskbarClass.getMethod("isTaskbarSupported").invoke(null));
      if (!taskbarSupported) {
        return false;
      }

      Object taskbar = taskbarClass.getMethod("getTaskbar").invoke(null);
      Class<?> featureClass = Class.forName("java.awt.Taskbar$Feature");
      Object iconImageFeature = null;
      Object[] features = featureClass.getEnumConstants();
      if (features != null) {
        for (Object feature : features) {
          if ("ICON_IMAGE".equals(String.valueOf(feature))) {
            iconImageFeature = feature;
            break;
          }
        }
      }
      if (iconImageFeature == null) {
        return false;
      }

      boolean iconSupported =
          Boolean.TRUE.equals(
              taskbarClass
                  .getMethod("isSupported", featureClass)
                  .invoke(taskbar, iconImageFeature));
      if (iconSupported) {
        taskbarClass.getMethod("setIconImage", Image.class).invoke(taskbar, icon);
        return true;
      }
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  public static void setFrameSize(Window frame, int width, int height) {
    if (javaVersion > 8)
      frame.setSize(
          (int) (width - 20 + (Config.isScaled ? 1.0 : Math.sqrt(Lizzie.sysScaleFactor)) * 30),
          (int) (height - 20 + (Config.isScaled ? 1.0 : Lizzie.sysScaleFactor) * 30));
    else
      frame.setSize(
          (int) (width - 20 + (Config.isScaled ? 1.0 : Math.sqrt(Lizzie.sysScaleFactor)) * 20),
          (int) (height - 20 + (Config.isScaled ? 1.0 : Lizzie.sysScaleFactor) * 25));
  }

  public static void openFirstUseSettings(boolean isOnload) {
    firstUseSettings = new FirstUseSettings(isOnload);
    firstUseSettings.setVisible(true);
  }

  public static boolean isFirstLaunchSession() {
    return firstLaunchSession;
  }

  private static String text(String key, String fallback) {
    try {
      if (resourceBundle != null && resourceBundle.containsKey(key)) {
        return resourceBundle.getString(key);
      }
    } catch (Exception e) {
    }
    return fallback;
  }

  private static void completeAutomaticFirstRunSetup() {
    boolean ready = hasUsableStartupConfiguration();
    if (!ready) {
      tryAutoSetupEngineProfile();
      ready = hasUsableStartupConfiguration();
    }
    startupProfileSaveFailed = !finalizeAutomaticFirstRunSetup();
    if (startupProfileSaveFailed) {
      engineStartupStatus.failed(
          "EngineStartup.profileSaveFailed",
          "Settings could not be saved - click to repair",
          text(
              "EngineStartup.profileSaveFailedDescription",
              "The new user profile could not be saved. Check folder permissions and free space."));
    } else if (!ready) {
      engineStartupStatus.needsRepair(
          "EngineStartup.needsRepair",
          "AI is not ready - click to repair",
          text("EngineStartup.noBundledEngine", "No complete built-in engine setup was found."));
    }
  }

  private static boolean hasUsableStartupConfiguration() {
    if (config.uiConfig.optBoolean("autoload-empty", false)) {
      return true;
    }
    return hasConfiguredEngine();
  }

  private static boolean hasConfiguredEngine() {
    try {
      List<EngineData> engines = Utils.getEngineData();
      for (EngineData engine : engines) {
        if (engine != null && engine.commands != null && !engine.commands.trim().isEmpty()) {
          return true;
        }
      }
      return false;
    } catch (Exception e) {
      APP.error("failed to read configured engines", e);
      return false;
    }
  }

  static boolean shouldOfferEngineRepair(boolean hasConfiguredEngine, boolean autoloadEmpty) {
    return !hasConfiguredEngine && !autoloadEmpty;
  }

  private static void tryAutoSetupEngineProfile() {
    try {
      SetupSnapshot snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
      if (snapshot.hasEngine() && snapshot.hasConfigs() && snapshot.hasWeight()) {
        KataGoAutoSetupHelper.applyAutoSetup(snapshot.withActiveWeight(snapshot.activeWeightPath));
      }
    } catch (Exception e) {
      APP.error("automatic engine profile setup failed", e);
    }
  }

  private static boolean finalizeAutomaticFirstRunSetup() {
    config.firstTimeLoad = false;
    config.needReopenFirstUseSettings = false;
    config.uiConfig.put("first-time-load", false);
    config.uiConfig.put("host-name", config.hostName);
    try {
      config.save();
      return true;
    } catch (IOException e) {
      APP.error("failed to save first-run profile", e);
      return false;
    }
  }

  public static void start(int index, boolean loadDefault) {
    setBoard(new Board());
    frame = new LizzieFrame();
    LizzieFrame.toolbar.setPopupMenu();
    LizzieFrame.menu.doubleMenu(true);
    frame.reSetLoc();
    frame.showMainPanel();
    logApplicationReady();
    frame.addResizeLis();
    // 引擎跟随控制器：试下期间让引擎跟随 displayNode 实时分析
    EngineCommandSink sink = new LeelazEngineCommandSink();
    engineFollowController = new EngineFollowController(sink);
    BoardHistoryNode initialTail = board.getHistory().getCurrentHistoryNode();
    if (initialTail != null) {
      engineFollowController.setCurrentEngineNode(initialTail);
    }
    webBoardManager.setEngineFollowController(engineFollowController);
    webBoardManager.setDesktopPlayingProbe(
        () -> frame != null && (frame.isPlayingAgainstLeelaz || frame.isAnaPlayingAgainstLeelaz));
    gtpConsole = new GtpConsolePane(frame);
    gtpConsole.setVisible(config.persistedUi.optBoolean("gtp-console-opened", false));
    SwingUtilities.invokeLater(
        new Thread() {
          public void run() {
            if (config.isShowingIndependentMain) frame.openIndependentMainBoard();
            if (config.isShowingIndependentSub) frame.openIndependentSubBoard();
            if (config.isCtrlOpened) frame.openController();
            try {
              new EngineManager(Lizzie.config, index, loadDefault);
            } catch (Exception e) {
              e.printStackTrace();
              engineStartupStatus.failed(
                  "EngineStartup.failed",
                  "AI failed to start - click to repair",
                  e.getMessage());
              try {
                new EngineManager(Lizzie.config, -1, false);
              } catch (JSONException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
              } catch (IOException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
              }
            }
            if (mainArgs.length == 1) {
              if (!mainArgs[0].equals("read")) {
                File file = new File(mainArgs[0]);
                frame.loadFile(file, true, true);
                LizzieFrame.curFile = file;
              }
            } else if (config.autoResume) {
              frame.resumeFile();
            }
            Lizzie.frame.setMainPanelFocus();
            if (Lizzie.config.saveBoardConfig.optInt("save-auto-game-index2", -1) == -5) {
              Lizzie.config.saveBoardConfig.put("save-auto-game-index1", 1);
              File file = new File("save\\autoGame1.bmp");
              if (file.exists() && file.isFile()) file.delete();
              File file2 = new File("save\\autoGame1.sgf");
              if (file2.exists() && file2.isFile()) file2.delete();
              File oldfile = new File("save\\autoGame2.bmp");
              File newfile = new File("save\\autoGame1.bmp");
              if (oldfile.exists()) {
                oldfile.renameTo(newfile);
              }
              File oldfile2 = new File("save\\autoGame2.sgf");
              File newfile2 = new File("save\\autoGame1.sgf");
              if (oldfile2.exists()) {
                oldfile2.renameTo(newfile2);
              }
            }
            if (Lizzie.config.analysisEnginePreLoad) {
              frame.preloadConfiguredAnalysisEngineAfterStartup();
            }
            KataGoRuntimeHelper.startAppleSiliconAutoOptimizationAsync();
            KataGoRuntimeHelper.startFirstRunBenchmarkAsync();
          }
        });
  }

  public static void markEngineReady() {
    if (!prepareEngineReadyPersistence()) {
      return;
    }
    engineStartupStatus.prepareReady().run();
  }

  /** Performs the retryable persistence step without publishing READY or notifying listeners. */
  public static synchronized boolean prepareEngineReadyPersistence() {
    if (startupProfileSaveFailed) {
      try {
        config.save();
        startupProfileSaveFailed = false;
      } catch (IOException e) {
        return false;
      }
    }
    return true;
  }

  public static void setLookAndFeel() {
    ToolTipManager.sharedInstance().setDismissDelay(99999);
    try {
      if (System.getProperty("os.name").contains("Mac")) {
        if (config.useJavaLooks)
          setUIFont(new javax.swing.plaf.FontUIResource(Config.sysDefaultFontName, Font.PLAIN, 12));
        else {
          // Keep the app menu visible inside the main window.
          System.setProperty("apple.laf.useScreenMenuBar", "false");
        }
      } else {
        setUIFont(new javax.swing.plaf.FontUIResource(Config.sysDefaultFontName, Font.PLAIN, 12));
      }
      UIManager.put(
          "OptionPane.buttonFont",
          new FontUIResource(
              new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize)));
      UIManager.put(
          "OptionPane.messageFont",
          new FontUIResource(
              new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize)));
      if (config.useJavaLooks) {
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
      } else {
        // String lookAndFeel = "com.sun.java.swing.plaf.windows.WindowsClassicLookAndFeel";
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
      }
      AppleStyleSupport.applyUiDefaults();
      applyOptionPaneLocalization(resourceBundle);
    } catch (ReflectiveOperationException | UnsupportedLookAndFeelException e) {
      APP.error("failed to set look and feel", e);
    }
  }

  public static void resetLookAndFeel() {
    try {
      if (config.useJavaLooks) {
        String lookAndFeel = UIManager.getCrossPlatformLookAndFeelClassName();
        UIManager.setLookAndFeel(lookAndFeel);
      } else {
        String lookAndFeel = UIManager.getSystemLookAndFeelClassName();
        UIManager.setLookAndFeel(lookAndFeel);
      }
      AppleStyleSupport.applyUiDefaults();
      applyOptionPaneLocalization(resourceBundle);
    } catch (ReflectiveOperationException | UnsupportedLookAndFeelException e) {
      APP.error("failed to reset look and feel", e);
    }
  }

  public static void setUIFont(javax.swing.plaf.FontUIResource f) {
    java.util.Enumeration keys = UIManager.getDefaults().keys();
    while (keys.hasMoreElements()) {
      Object key = keys.nextElement();
      Object value = UIManager.get(key);
      if (value instanceof javax.swing.plaf.FontUIResource) {
        UIManager.put(key, f);
      }
    }
  }

  private static void startConfiguredEngine(int index, boolean loadDefault) {
    RemoteComputeConfig.StartupSelection selection =
        RemoteComputeConfig.resolveStartupSelection(index, loadDefault);
    start(selection.engineIndex, selection.loadDefault);
  }

  static void applyOptionPaneLocalization(ResourceBundle bundle) {
    if (bundle == null) return;
    UIManager.put("OptionPane.okButtonText", bundle.getString("GameInfoDialog.okButton"));
    UIManager.put("OptionPane.cancelButtonText", bundle.getString("LizzieFrame.cancel"));
    UIManager.put("OptionPane.yesButtonText", bundle.getString("ConfigDialog2.yes"));
    UIManager.put("OptionPane.noButtonText", bundle.getString("ConfigDialog2.no"));
  }

  public static void initializeAfterVersionCheck(boolean isEngineGame, Leelaz engine) {
    initializeAfterVersionCheck(isEngineGame, engine, true);
  }

  public static void initializeAfterVersionCheck(
      boolean isEngineGame, Leelaz engine, boolean startPondering) {
    if (engine == null) {
      return;
    }
    long primaryGeneration = capturePrimaryEngineGeneration(engine);
    initializeAfterVersionCheck(isEngineGame, engine, startPondering, primaryGeneration);
  }

  public static void initializeAfterVersionCheck(
      boolean isEngineGame,
      Leelaz engine,
      boolean startPondering,
      long primaryGeneration) {
    PreparedEngineReadyPublication publication =
        prepareInitializeAfterVersionCheck(
            isEngineGame, engine, startPondering, primaryGeneration);
    if (publication == null) {
      return;
    }
    if (!publication.readyPublicationEnabled()) {
      return;
    }
    final EngineStartupStatus.PreparedNotification[] notification =
        new EngineStartupStatus.PreparedNotification[1];
    if (runIfPrimaryEngine(
        engine,
        primaryGeneration,
        () -> notification[0] = publication.prepareReadyStatus())) {
      publication.publishForPrimary(notification[0]);
    }
  }

  /**
   * Performs every failure-prone engine initialization step while deferring READY and its UI.
   * Update-engine replacement uses this capability so lifecycle close and exact incarnation
   * settlement can finish before the terminal READY publication becomes observable.
   */
  public static PreparedEngineReadyPublication prepareInitializeAfterVersionCheck(
      boolean isEngineGame,
      Leelaz engine,
      boolean startPondering,
      long primaryGeneration) {
    return prepareInitializeAfterVersionCheck(
        isEngineGame, engine, startPondering, primaryGeneration, false);
  }

  /**
   * Prepares engine readiness while optionally deferring PDA presentation until the lifecycle
   * owner has closed successfully. Ordinary engine switches use this form so a late detach failure
   * cannot leave UI belonging to a rolled-back target.
   */
  public static PreparedEngineReadyPublication prepareInitializeAfterVersionCheck(
      boolean isEngineGame,
      Leelaz engine,
      boolean startPondering,
      long primaryGeneration,
      boolean deferPdaPresentation) {
    if (engine == null) {
      return null;
    }
    Menu currentMenu = LizzieFrame.menu;
    LizzieFrame currentFrame = frame;
    BottomToolbar currentToolbar = LizzieFrame.toolbar;
    engine.canRestoreDymPda = true;
    // Engine-local time setup historically precedes primary-owner publication. It must target the
    // exact engine argument (for human games), while global READY/PDA/ponder/UI effects below stay
    // fenced to the captured primary generation.
    if (!engineGame.current().startingOrPlaying()) {
      boolean readBoardGmaActive =
          currentFrame != null
              && currentFrame.readBoard != null
              && currentFrame.readBoard.isReadBoardGmaAutoPlayActive();
      if (currentFrame != null
          && DesktopTimeControl.shouldSendHumanTimeOnEngineReady(
              currentFrame.isPlayingAgainstLeelaz,
              currentFrame.isAnaPlayingAgainstLeelaz && !readBoardGmaActive)) {
        LizzieFrame.sendAiTime(false, engine, false);
      }
    }
    if (primaryGeneration < 0L) {
      return null;
    }
    Runnable pdaPresentation =
        () -> {
          if (engineGame.current().startingOrPlaying()) {
            EngineManager manager = engineManager;
            Boolean participantPda = engineGameParticipantPda(manager);
            if (currentMenu != null && participantPda != null) {
              currentMenu.showPdaForEngine(engine, primaryGeneration, participantPda);
            }
          } else if (currentMenu != null) {
            currentMenu.showPdaForEngine(engine, primaryGeneration, engine.isKataGoPda);
          }
        };
    if (!runIfPrimaryEngine(
        engine,
        primaryGeneration,
        () -> {
          if (!deferPdaPresentation) {
            pdaPresentation.run();
          }
          if (!isEngineGame && currentFrame != null && !currentFrame.isPlayingAgainstLeelaz) {
            if (startPondering && !Lizzie.config.notStartPondering) {
              engine.ponder();
              engine.setResponseUpToDate();
            } else {
              engine.notPondering();
              engine.setResponseUpToDate();
              if (Lizzie.config.notStartPondering) {
                Lizzie.config.notStartPondering = false;
              }
            }
          }
        })) {
      return null;
    }
    boolean readyPublicationEnabled = prepareEngineReadyPersistence();
    return new PreparedEngineReadyPublication(
        isEngineGame,
        engine,
        primaryGeneration,
        currentMenu,
        currentFrame,
        currentToolbar,
        readyPublicationEnabled,
        deferPdaPresentation
            ? () -> runIfPrimaryEngine(engine, primaryGeneration, pdaPresentation)
            : null);
  }

  private static Boolean engineGameParticipantPda(EngineManager manager) {
    EngineGameSnapshot snapshot = engineGame.current();
    if (!snapshot.startingOrPlaying()
        || manager == null
        || manager.engineList == null
        || !(snapshot instanceof EngineGameSnapshot.BatchActive active)) {
      return null;
    }
    int first = manager.resolveEngineGameParticipant(active.batch().first());
    int second = manager.resolveEngineGameParticipant(active.batch().second());
    if (first < 0
        || second < 0
        || first >= manager.engineList.size()
        || second >= manager.engineList.size()) {
      return null;
    }
    return manager.engineList.get(first).isKataGoPda
        || manager.engineList.get(second).isKataGoPda;
  }

  public static final class PreparedEngineReadyPublication {
    private final boolean engineGame;
    private final Leelaz engine;
    private final long primaryGeneration;
    private final Menu menu;
    private final LizzieFrame frame;
    private final BottomToolbar toolbar;
    private final boolean readyPublicationEnabled;
    private final Runnable deferredPdaPresentation;
    private final AtomicBoolean statusPrepared = new AtomicBoolean();
    private final AtomicBoolean presentationPublished = new AtomicBoolean();
    private final AtomicBoolean pdaPresentationPublished = new AtomicBoolean();

    private PreparedEngineReadyPublication(
        boolean engineGame,
        Leelaz engine,
        long primaryGeneration,
        Menu menu,
        LizzieFrame frame,
        BottomToolbar toolbar,
        boolean readyPublicationEnabled,
        Runnable deferredPdaPresentation) {
      this.engineGame = engineGame;
      this.engine = engine;
      this.primaryGeneration = primaryGeneration;
      this.menu = menu;
      this.frame = frame;
      this.toolbar = toolbar;
      this.readyPublicationEnabled = readyPublicationEnabled;
      this.deferredPdaPresentation = deferredPdaPresentation;
    }

    public Leelaz engine() {
      return engine;
    }

    public long primaryGeneration() {
      return primaryGeneration;
    }

    public boolean readyPublicationEnabled() {
      return readyPublicationEnabled;
    }

    /** Logical state commit only; arbitrary listener callbacks remain deferred. */
    public EngineStartupStatus.PreparedNotification prepareReadyStatus() {
      if (!statusPrepared.compareAndSet(false, true)) {
        throw new IllegalStateException("Engine READY publication was already prepared");
      }
      if (!readyPublicationEnabled) {
        return null;
      }
      return engineStartupStatus.prepareReady();
    }

    /** Normal startup publication, fenced to the primary owner generation captured at prepare. */
    public void publishForPrimary(EngineStartupStatus.PreparedNotification notification) {
      if (notification == null) {
        runDeferredPdaPresentation();
        return;
      }
      try {
        notification.run();
      } catch (RuntimeException | Error listenerFailure) {
        listenerFailure.printStackTrace();
      }
      Runnable presentation =
          () -> {
            if (!notification.isCurrent()) {
              return;
            }
            runIfPrimaryEngine(
                engine,
                primaryGeneration,
                () -> {
                  if (notification.isCurrent()) {
                    runPresentation();
                  }
                });
          };
      try {
        if (SwingUtilities.isEventDispatchThread()) {
          presentation.run();
        } else {
          SwingUtilities.invokeLater(presentation);
        }
      } catch (RuntimeException | Error presentationFailure) {
        presentationFailure.printStackTrace();
      }
    }

    /** Caller supplies the exact manager/slot/incarnation fence at UI execution time. */
    public void runPresentation() {
      if (!presentationPublished.compareAndSet(false, true)) {
        return;
      }
      runDeferredPdaPresentation();
      runReadyPresentationStep(() -> {
        if (menu != null) menu.updateMenuStatusForEngine();
      });
      runReadyPresentationStep(() -> {
        if (frame != null && !frame.syncBoard) frame.reSetLoc();
      });
      runReadyPresentationStep(() -> {
        if (toolbar != null) toolbar.reSetButtonLocation();
      });
      runReadyPresentationStep(
          () -> {
            if (!engineGame && frame != null && frame.resetMovelistFrameandAnalysisFrame()) {
              frame.setVisible(true);
            }
          });
    }

    private void runDeferredPdaPresentation() {
      if (deferredPdaPresentation != null
          && pdaPresentationPublished.compareAndSet(false, true)) {
        runReadyPresentationStep(deferredPdaPresentation);
      }
    }

    private static void runReadyPresentationStep(Runnable action) {
      try {
        action.run();
      } catch (RuntimeException | Error failure) {
        failure.printStackTrace();
      }
    }
  }

  public static void shutdown() {
    shutdown(System::exit);
  }

  static void shutdown(IntConsumer exit) {
    logShutdownRequested();
    try {
      if (config.autoSaveOnExit) frame.saveAutoGame(1);
    } catch (Exception e) {
      APP.error("failed to auto-save on shutdown", e);
    }
    if (Lizzie.config.uiConfig.optBoolean("autoload-last", false)) {
      Lizzie.config.uiConfig.put("last-engine", EngineManager.currentEngineNo);
    }
    if (Lizzie.frame.ctrl != null && Lizzie.frame.ctrl.isVisible()) {
      Lizzie.config.uiConfig.put("is-ctrl-opened", true);
    } else Lizzie.config.uiConfig.put("is-ctrl-opened", false);
    try {
      config.persist();
    } catch (Exception e) {
      Utils.showMsgModal(
          "<html>"
              + resourceBundle.getString("Lizzie.save.error")
              + e.getLocalizedMessage()
              + "<br />"
              + resourceBundle.getString("Lizzie.save.path")
              + config.getPersistFilePath()
              + "</html>");
      APP.error("failed to persist UI state", e);
    }
    try {
      config.save();
    } catch (Exception e) {
      Utils.showMsgModal(
          "<html>"
              + resourceBundle.getString("Lizzie.save.error")
              + e.getLocalizedMessage()
              + "<br />"
              + resourceBundle.getString("Lizzie.save.path")
              + config.getConfigFilePath()
              + "</html>");
      APP.error("failed to save config on shutdown", e);
    }
    try {
      frame.closeContributeEngine();
    } catch (Exception e) {
      e.printStackTrace();
    }
    try {
      frame.shutdownKifuEngineSyncCoordinator();
    } catch (Exception e) {
      APP.error("failed to shut down kifu engine synchronization", e);
    }
    try {
      if (engineManager != null) engineManager.forceKillAllEngines();
    } catch (Exception e) {
      e.printStackTrace();
    }
    if (frame.readBoard != null)
      try {
        frame.readBoard.shutdown();
      } catch (Exception e) {
        e.printStackTrace();
      }
    try {
      frame.shutdownClockHelper();
    } catch (Exception e) {
      APP.error("failed to shut down clock helper", e);
    }
    Lizzie.frame.destroyAnalysisEngine();
    if (webBoardManager != null) {
      try {
        webBoardManager.stop();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    shutdownLoggingThenExit(exit);
  }

  public static void shutdownLoggingThenExit(IntConsumer exit) {
    try {
      EdtHangWatchdog.uninstall();
    } catch (RuntimeException ignored) {
    }
    try {
      LoggingRuntime.current()
          .ifPresent(
              runtime -> {
                if (APP.isInfoEnabled()) {
                  APP.info("logging shutdown begin");
                }
                runtime.shutdown();
              });
    } catch (RuntimeException ignored) {
    }
    exit.accept(0);
  }

  public static void resetAllHints() {
    config.allowCloseCommentControlHint = true;
    config.showReplaceFileHint = true;
    config.firstLoadKataGo = true;
    config.exitAutoAnalyzeTip = true;
    config.uiConfig.put("first-load-katago", config.firstLoadKataGo);
    config.uiConfig.put("show-replace-file-hint", config.showReplaceFileHint);
    config.uiConfig.put("allow-close-comment-control-hint", config.allowCloseCommentControlHint);
    config.uiConfig.put("exit-auto-analyze-tip", true);
  }
}
