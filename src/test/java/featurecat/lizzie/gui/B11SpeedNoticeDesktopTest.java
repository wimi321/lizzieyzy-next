package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.AppLocale;
import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.util.B11ModelNotice;
import featurecat.lizzie.util.EngineThreadPolicy;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.LocaleFontSupport;
import featurecat.lizzie.util.Utils;
import java.awt.Component;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Window;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPOutputStream;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real application windows with synthetic metadata/results; no network or benchmark is started. */
class B11SpeedNoticeDesktopTest {
  @ParameterizedTest
  @ValueSource(strings = {"1", "1.25", "1.5", "2"})
  void realWindowsShowTheNoticeAndKeepModelTargetsSeparate(String scale) throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(
            getClass(),
            "b11-notice-" + scale,
            List.of("-Dsun.java2d.uiScale=" + scale),
            List.of("probe"),
            120);
    assertEquals("PASS", new JSONObject(Files.readString(result)).getString("status"));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[1]);
    Path result = Path.of(args[2]);
    try {
      Files.writeString(
          work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},\"logging\":{\"diagnostics-enabled\":false},"
              + "\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"use-language\":1}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      await(() -> Lizzie.frame != null && Lizzie.frame.isShowing());
      SwingUtilities.invokeAndWait(
          () -> {
            for (Window window : Window.getWindows())
              if (window instanceof JDialog) window.dispose();
          });
      Path b11 = writeModel(work.resolve("default.bin.gz"), "kata1-tf3-b11c768-s12002M-d6304M");
      Path b10 = writeModel(work.resolve("other.bin.gz"), "b10c512h8nbt3tflrs-fson-silu-rsnh");
      Path executable = Files.writeString(work.resolve("katago"), "metadata-only test fixture");
      Path config = Files.writeString(work.resolve("gtp.cfg"), "numSearchThreads = 12\n");
      EngineData entry = new EngineData();
      entry.id = "b11-notice-fixture";
      entry.name = "Model presentation fixture";
      entry.width = entry.height = 19;
      entry.komi = 7.5f;
      entry.commands = command(executable, b11, config);

      Path bridge = Files.writeString(work.resolve("katago-bridge.sh"), "metadata-only script fixture");
      entry.commands = command(bridge, b11, config);
      assertTrue(EngineThreadPolicy.isLocalKataGoCommand(entry.commands, false));
      SetupSnapshot bridgeSnapshot = KataGoAutoSetupHelper.inspectSavedEngine(entry);
      assertNotNull(bridgeSnapshot);
      assertTrue(B11ModelNotice.isB11(KataGoAutoSetupHelper.readWeightModelName(b11)));
      AtomicReference<KataGoAutoSetupDialog> boundaryDialog = new AtomicReference<>();
      SwingUtilities.invokeAndWait(
          () -> {
            KataGoAutoSetupDialog dialog = new KataGoAutoSetupDialog(Lizzie.frame);
            invoke(dialog, "cancelStateRefresh");
            set(dialog, "stateRefreshRequestId", ((Long) field(dialog, "stateRefreshRequestId")) + 1L);
            boundaryDialog.set(dialog);
          });
      try {
        for (boolean catalog : List.of(false, true)) {
          SetupSnapshot directB11 =
              KataGoAutoSetupHelper.inspectSavedEngine(directEntry(executable, b11, config));
          SetupSnapshot directB10 =
              KataGoAutoSetupHelper.inspectSavedEngine(directEntry(executable, b10, config));
          if (catalog) {
            directB11 = directB11.scanWeightCatalog();
            directB10 = directB10.scanWeightCatalog();
          }
          SetupSnapshot indirect = catalog ? bridgeSnapshot.scanWeightCatalog() : bridgeSnapshot;
          assertBenchmarkNotice(
              boundaryDialog.get(), directB11, true, "direct B11, catalog=" + catalog);
          assertBenchmarkNotice(
              boundaryDialog.get(), indirect, false, "script host collision, catalog=" + catalog);
          assertBenchmarkNotice(
              boundaryDialog.get(), directB10, false, "direct B10, catalog=" + catalog);
        }
      } finally {
        SwingUtilities.invokeAndWait(() -> boundaryDialog.get().dispose());
      }
      entry.commands = command(executable, b11, config);
      entry.threadPolicy = new JSONObject().put("source", "CFG");
      SetupSnapshot b11Snapshot =
          KataGoAutoSetupHelper.inspectSavedEngine(entry).scanWeightCatalog();
      assertNotNull(b11Snapshot);
      JSONObject benchmarkEnvironment = EngineThreadPolicy.environment(b11Snapshot);
      entry
          .threadPolicy
          .put("katago-benchmark-threads", 12)
          .put("katago-benchmark-visits-per-second", 2040.6)
          .put("katago-benchmark-nn-evals-per-second", 1850.3)
          .put("katago-benchmark-backend", "CUDA")
          .put("environment", benchmarkEnvironment);
      Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
      entry.commands = command(executable, b10, config);
      SetupSnapshot b10Snapshot =
          KataGoAutoSetupHelper.inspectSavedEngine(entry).scanWeightCatalog();
      entry.commands = command(executable, b11, config);

      Leelaz main = new Leelaz(entry.commands);
      main.oriEnginename = entry.name;
      Method streams =
          Leelaz.class.getDeclaredMethod(
              "installFreshCommandStreamsForTest",
              InputStream.class,
              OutputStream.class,
              InputStream.class);
      streams.setAccessible(true);
      streams.invoke(
          main,
          new ByteArrayInputStream(new byte[0]),
          new ByteArrayOutputStream(),
          new ByteArrayInputStream(new byte[0]));
      Object binding = field(main, "readerStreamBinding");
      set(
          binding,
          "speedModelLookup",
          B11ModelNotice.local(List.of("katago", "gtp", "-model", b11.toString()), work));
      await(main::usesB11ForSpeedNotice);
      SwingUtilities.invokeAndWait(
          () -> {
            Lizzie.leelaz = main;
            EngineManager.isEmpty = false;
          });

      for (String language : List.of("zh-CN", "en-US", "zh-TW", "zh-HK", "ja-JP", "ko", "th-TH")) {
        Lizzie.resourceBundle =
            ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag(language));
        Lizzie.config.useLanguage =
            AppLocale.fromSystemLocale(Locale.forLanguageTag(language)).configValue();
        Lizzie.config.uiFontName =
            LocaleFontSupport.resolveConfiguredFontName(
                null, Locale.forLanguageTag(language), Config.sysDefaultFontName);
        AtomicReference<KataGoAutoSetupDialog> shown = new AtomicReference<>();
        SwingUtilities.invokeAndWait(
            () -> {
              Lizzie.frame.updateTitle();
              assertTrue(
                  Lizzie.frame
                      .getTitle()
                      .contains(Lizzie.resourceBundle.getString("B11SpeedNotice.title")));
              KataGoAutoSetupDialog dialog = new KataGoAutoSetupDialog(Lizzie.frame);
              invoke(dialog, "cancelStateRefresh");
              set(
                  dialog,
                  "stateRefreshRequestId",
                  ((Long) field(dialog, "stateRefreshRequestId")) + 1L);
              try {
                Method settled =
                    KataGoAutoSetupDialog.class.getDeclaredMethod(
                        "setStateRefreshPending", boolean.class);
                settled.setAccessible(true);
                settled.invoke(dialog, false);
              } catch (Exception failure) {
                throw new AssertionError(failure);
              }
              set(dialog, "snapshot", b11Snapshot);
              ((JList<?>) field(dialog, "sectionNav")).setSelectedIndex(2);
              invoke(dialog, "updateBenchmarkInfo");
              dialog.setVisible(true);
              shown.set(dialog);
            });
        new Robot().waitForIdle();
        KataGoAutoSetupDialog dialog = shown.get();
        SwingUtilities.invokeAndWait(
            () -> {
              ((JList<?>) field(dialog, "sectionNav")).requestFocusInWindow();
              var notice = (B11SpeedNoticePanel) field(dialog, "benchmarkModelNotice");
              var viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, notice);
              viewport.setViewPosition(new Point(0, 0));
            });
        new Robot().waitForIdle();
        for (String state :
            List.of("EMPTY", "RUNNING", "COMPLETE", "CANCELLED", "FAILED", "LEGACY")) {
          SwingUtilities.invokeAndWait(
              () -> {
                setEnum(dialog, "benchmarkDisplayState", state);
                invoke(dialog, "updateBenchmarkInfo");
                dialog.validate();
                B11SpeedNoticePanel notice =
                    (B11SpeedNoticePanel) field(dialog, "benchmarkModelNotice");
                assertTrue(notice.isShowing(), state + " " + language);
                assertNoticeBounds(notice);
                if (state.equals("FAILED")) {
                  assertEquals(
                      Lizzie.resourceBundle.getString("AutoSetup.benchmarkFailed"),
                      ((JLabel) field(dialog, "lblBenchmarkReportStatus")).getText());
                }
              });
        }
        SwingUtilities.invokeAndWait(
            () -> {
              setEnum(dialog, "benchmarkDisplayState", "IDLE");
              invoke(dialog, "updateBenchmarkInfo");
            });
        new Robot().waitForIdle();
        SwingUtilities.invokeAndWait(
            () -> {
              var notice = (B11SpeedNoticePanel) field(dialog, "benchmarkModelNotice");
              var viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, notice);
              assertEquals(
                  0,
                  viewport.getViewPosition().y,
                  "Read-only result updates must not scroll the report");
            });
        ImageIO.write(
            new Robot().createScreenCapture(dialog.getBounds()),
            "png",
            result.getParent().resolve(language + ".png").toFile());
        SwingUtilities.invokeAndWait(
            () -> {
              set(dialog, "snapshot", b10Snapshot);
              invoke(dialog, "updateBenchmarkInfo");
              assertFalse(
                  ((B11SpeedNoticePanel) field(dialog, "benchmarkModelNotice")).isVisible(),
                  "benchmark target is B10 even though the main engine is B11");
              assertTrue(main.usesB11ForSpeedNotice());
              set(dialog, "snapshot", b11Snapshot);
              entry.threadPolicy.put("environment", new JSONObject());
              Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
              invoke(dialog, "updateBenchmarkInfo");
              assertEquals(
                  Lizzie.resourceBundle.getString("B11SpeedNotice.historicalStatus"),
                  ((JLabel) field(dialog, "lblBenchmarkReportStatus")).getText());
              assertTrue(
                  ((JTextArea) field(dialog, "benchmarkPolicyDetails"))
                      .getText()
                      .contains(
                          Lizzie.resourceBundle.getString("B11SpeedNotice.historicalResult")));
              entry.threadPolicy.put("environment", benchmarkEnvironment);
              Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
              dialog.dispose();
            });
      }
      SwingUtilities.invokeAndWait(
          () -> {
            Lizzie.resourceBundle =
                ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
            Lizzie.frame.setSize(1200, 850);
            Lizzie.frame.setLocation(0, 0);
            Lizzie.frame.toFront();
            Lizzie.frame.updateTitle();
          });
      new Robot().waitForIdle();
      ImageIO.write(
          new Robot().createScreenCapture(Lizzie.frame.getBounds()),
          "png",
          result.getParent().resolve("main-title.png").toFile());
      SwingUtilities.invokeAndWait(
          () -> {
            EngineManager.isEmpty = true;
            Lizzie.frame.updateTitle();
            assertFalse(
                Lizzie.frame
                    .getTitle()
                    .contains(Lizzie.resourceBundle.getString("B11SpeedNotice.title")));
          });
      Files.writeString(
          result,
          new JSONObject()
              .put("status", "PASS")
              .put("data", "synthetic metadata and benchmark results; no performance claim")
              .toString());
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      System.exit(1);
    }
  }

  private static void assertNoticeBounds(B11SpeedNoticePanel notice) {
    assertTrue(notice.getWidth() > 0 && notice.getHeight() > 0);
    for (Component component : notice.getComponents()) {
      assertTrue(notice.contains(component.getX(), component.getY()));
      assertTrue(component.getY() + component.getHeight() <= notice.getHeight());
      assertTrue(component.getHeight() >= component.getPreferredSize().height);
      if (component instanceof JTextArea text) {
        assertEquals(-1, text.getFont().canDisplayUpTo(text.getText()), "Missing glyphs in notice");
      }
    }
  }

  private static String command(Path executable, Path model, Path config) {
    return "\"" + executable + "\" gtp -model \"" + model + "\" -config \"" + config + "\"";
  }

  private static EngineData directEntry(Path executable, Path model, Path config) {
    EngineData entry = new EngineData();
    entry.commands = command(executable, model, config);
    return entry;
  }

  private static void assertBenchmarkNotice(
      KataGoAutoSetupDialog dialog, SetupSnapshot target, boolean expected, String context)
      throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          set(dialog, "snapshot", target);
          invoke(dialog, "updateBenchmarkModelNotice");
        });
    if (expected) {
      await(() -> ((B11SpeedNoticePanel) field(dialog, "benchmarkModelNotice")).isVisible());
    } else {
      // Let a wrongly admitted asynchronous header lookup publish before checking the page.
      Thread.sleep(750);
    }
    SwingUtilities.invokeAndWait(
        () ->
            assertEquals(
                expected,
                ((B11SpeedNoticePanel) field(dialog, "benchmarkModelNotice")).isVisible(),
                context));
  }

  private static Path writeModel(Path path, String identity) throws Exception {
    try (var stream = new GZIPOutputStream(Files.newOutputStream(path))) {
      stream.write((identity + "\n17\n22\n19\n").getBytes(StandardCharsets.US_ASCII));
    }
    return path;
  }

  private static Object field(Object object, String name) {
    try {
      Field field = object.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(object);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static void set(Object object, String name, Object value) {
    try {
      Field field = object.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(object, value);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void setEnum(Object object, String field, String value) {
    set(object, field, Enum.valueOf((Class) field(object, field).getClass(), value));
  }

  private static void invoke(Object object, String name) {
    try {
      Method method = object.getClass().getDeclaredMethod(name);
      method.setAccessible(true);
      method.invoke(object);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static void await(BooleanSupplier predicate) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    while (!predicate.getAsBoolean()) {
      if (System.nanoTime() > deadline) throw new AssertionError("Desktop condition timed out");
      Thread.sleep(50);
    }
  }
}
