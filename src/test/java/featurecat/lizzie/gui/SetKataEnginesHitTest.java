package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetKataEnginesHitTest {
  @TempDir Path root;

  @Test
  void threadCheckboxReceivesMouseAtEverySupportedFontSize() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    var previousFrame = Lizzie.frame;
    var previousEngine = Lizzie.leelaz;
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    int previousSize = Config.frameFontSize;
    try {
      Files.createDirectories(root.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      Lizzie.frame =
          (QuietFrame) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(QuietFrame.class);
      Lizzie.setPrimaryEngine(null);
      SwingUtilities.invokeAndWait(
          () -> {
            for (int size : new int[] {12, 16, 20}) {
              Config.frameFontSize = size;
              SetKataEngines dialog = new SetKataEngines();
              try {
                dialog.setVisible(true);
                Field checkboxField = SetKataEngines.class.getDeclaredField("chkEditThreads");
                checkboxField.setAccessible(true);
                JCheckBox checkbox = (JCheckBox) checkboxField.get(dialog);
                Point center =
                    SwingUtilities.convertPoint(
                        checkbox,
                        checkbox.getWidth() / 2,
                        checkbox.getHeight() / 2,
                        dialog.getContentPane());
                assertSame(
                    checkbox,
                    SwingUtilities.getDeepestComponentAt(
                        dialog.getContentPane(), center.x, center.y),
                    "Thread checkbox must receive mouse input at font size " + size);
              } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
              } finally {
                dialog.dispose();
              }
            }
          });
    } finally {
      Config.frameFontSize = previousSize;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.setPrimaryEngine(previousEngine);
      Lizzie.resourceBundle = previousBundle;
    }
  }

  @Test
  void acknowledgedOverrideReopensCheckedAndLateQueryKeepsDraft() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    var previousFrame = Lizzie.frame;
    var previousEngine = Lizzie.leelaz;
    var previousConsole = Lizzie.gtpConsole;
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    Leelaz engine = null;
    SetKataEngines[] dialogs = new SetKataEngines[3];
    try {
      Files.createDirectories(root.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
      Lizzie.frame = (QuietFrame) unsafe.allocateInstance(QuietFrame.class);
      Lizzie.gtpConsole = (QuietConsole) unsafe.allocateInstance(QuietConsole.class);
      engine = new Leelaz("katago gtp");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      engineHook(engine, "installFreshCommandOutputForTest", java.io.OutputStream.class, output);
      engine.started = true;
      engine.isLoaded = true;
      engine.isKatago = true;
      Lizzie.setPrimaryEngine(engine);
      var set = engine.captureRuntimeSearchThreads().apply(10);
      engineHook(
          engine,
          "processCommandResponseLineForTest",
          String.class,
          "=" + commandId(output, "kata-set-param numSearchThreads 10"));
      assertEquals(10, set.get(2, TimeUnit.SECONDS));

      SwingUtilities.invokeAndWait(
          () -> {
            dialogs[0] = new SetKataEngines();
            dialogs[0].setVisible(true);
            assertTrue(field(dialogs[0], "chkEditThreads", JCheckBox.class).isSelected());
            assertEquals(
                "10",
                field(dialogs[0], "txtThreads", JTextField.class).getText(),
                "confirmed value must be visible before the query responds");
            field(dialogs[0], "txtThreads", JTextField.class).setText("12");
          });
      int queryId = commandId(output, "kata-get-param numSearchThreads");
      engineHook(engine, "processCommandResponseLineForTest", String.class, "=" + queryId + " 10");
      output.reset();
      SwingUtilities.invokeAndWait(
          () -> {
            assertEquals("12", field(dialogs[0], "txtThreads", JTextField.class).getText());
            field(dialogs[0], "btnCancel", JButton.class).doClick();
            dialogs[1] = new SetKataEngines();
            dialogs[1].setVisible(true);
            assertTrue(field(dialogs[1], "chkEditThreads", JCheckBox.class).isSelected());
            assertEquals("10", field(dialogs[1], "txtThreads", JTextField.class).getText());
          });
      assertEquals(
          Leelaz.RuntimeThreadOverrideState.ON,
          engine.captureRuntimeSearchThreads().overrideState());
      engineHook(
          engine,
          "processCommandResponseLineForTest",
          String.class,
          "?" + commandId(output, "kata-get-param numSearchThreads") + " unavailable");
      SwingUtilities.invokeAndWait(
          () -> {
            assertEquals("10", field(dialogs[1], "txtThreads", JTextField.class).getText());
            assertEquals(
                Lizzie.resourceBundle.getString("SetKataEngines.threadReadFailed"),
                field(dialogs[1], "lblThreadReadStatus", javax.swing.JLabel.class).getText());
            dialogs[1].setVisible(false);
          });
      output.reset();
      engineHook(engine, "installFreshCommandOutputForTest", java.io.OutputStream.class, output);
      SwingUtilities.invokeAndWait(
          () -> {
            dialogs[2] = new SetKataEngines();
            dialogs[2].setVisible(true);
            assertEquals(
                "",
                field(dialogs[2], "txtThreads", JTextField.class).getText(),
                "replacement process must not inherit the old confirmed value");
            assertEquals(
                Lizzie.resourceBundle.getString("SetKataEngines.threadReading"),
                field(dialogs[2], "lblThreadReadStatus", javax.swing.JLabel.class).getText());
          });
      engineHook(
          engine,
          "processCommandResponseLineForTest",
          String.class,
          "=" + commandId(output, "kata-get-param numSearchThreads") + " 6");
      SwingUtilities.invokeAndWait(
          () -> {
            assertEquals("6", field(dialogs[2], "txtThreads", JTextField.class).getText());
            assertEquals(
                "", field(dialogs[2], "lblThreadReadStatus", javax.swing.JLabel.class).getText());
          });
    } finally {
      SwingUtilities.invokeAndWait(
          () -> {
            for (SetKataEngines dialog : dialogs) if (dialog != null) dialog.dispose();
          });
      if (engine != null) {
        engine.isNormalEnd = true;
        engine.forceQuit();
      }
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.setPrimaryEngine(previousEngine);
      Lizzie.gtpConsole = previousConsole;
      Lizzie.resourceBundle = previousBundle;
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void threadCallbackAppliesOnlyEditedParameterDrafts(boolean editWrn) throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    var previousFrame = Lizzie.frame;
    var previousEngine = Lizzie.leelaz;
    var previousConsole = Lizzie.gtpConsole;
    var previousMenu = LizzieFrame.menu;
    var previousBoard = Lizzie.board;
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    Leelaz engine = null;
    SetKataEngines[] dialog = new SetKataEngines[1];
    try {
      Files.createDirectories(root.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      Lizzie.config.chkKataEngineWRN = true;
      Lizzie.config.txtKataEngineWRN = "0.3";
      Lizzie.config.chkKataEnginePDA = true;
      Lizzie.config.txtKataEnginePDA = "0.5";
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
      Lizzie.frame = (QuietFrame) unsafe.allocateInstance(QuietFrame.class);
      Lizzie.gtpConsole = (QuietConsole) unsafe.allocateInstance(QuietConsole.class);
      LizzieFrame.menu = (QuietMenu) unsafe.allocateInstance(QuietMenu.class);
      Lizzie.board = new featurecat.lizzie.rules.Board();
      engine = new Leelaz("katago gtp");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      engineHook(engine, "installFreshCommandOutputForTest", java.io.OutputStream.class, output);
      engine.started = true;
      engine.isLoaded = true;
      engine.isKatago = true;
      Lizzie.setPrimaryEngine(engine);
      var set = engine.captureRuntimeSearchThreads().apply(10);
      engineHook(
          engine,
          "processCommandResponseLineForTest",
          String.class,
          "=" + commandId(output, "kata-set-param numSearchThreads 10"));
      assertEquals(10, set.get(2, TimeUnit.SECONDS));
      SwingUtilities.invokeAndWait(
          () -> {
            dialog[0] = new SetKataEngines();
            dialog[0].setVisible(true);
            field(dialog[0], "txtThreads", JTextField.class).setText("12");
            if (editWrn) field(dialog[0], "txtWRN", JTextField.class).setText("0.6");
          });
      engineHook(
          engine,
          "processCommandResponseLineForTest",
          String.class,
          "=" + commandId(output, "kata-get-param numSearchThreads") + " 10");
      output.reset();
      SwingUtilities.invokeAndWait(() -> field(dialog[0], "btnApply", JButton.class).doClick());
      // A value set elsewhere while the thread change is in flight is newer than the dialog's copy.
      Lizzie.config.txtKataEnginePDA = "0.9";
      engineHook(
          engine,
          "processCommandResponseLineForTest",
          String.class,
          "=" + commandId(output, "kata-set-param numSearchThreads 12"));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (dialog[0].isVisible() && System.nanoTime() < deadline) Thread.sleep(10);
      SwingUtilities.invokeAndWait(() -> {});
      assertFalse(dialog[0].isVisible(), "the thread change completes the save");
      String sent = output.toString(StandardCharsets.UTF_8);
      assertFalse(sent.contains("playoutDoublingAdvantage"), sent);
      assertEquals("0.9", Lizzie.config.txtKataEnginePDA);
      if (editWrn) {
        assertTrue(sent.contains("kata-set-param analysisWideRootNoise 0.6"), sent);
        assertEquals("0.6", Lizzie.config.txtKataEngineWRN);
      } else {
        assertFalse(sent.contains("analysisWideRootNoise"), sent);
      }
    } finally {
      SwingUtilities.invokeAndWait(
          () -> {
            for (java.awt.Window window : java.awt.Window.getWindows()) {
              if (window instanceof javax.swing.JDialog d && d.isVisible()) d.dispose();
            }
          });
      if (engine != null) {
        engine.isNormalEnd = true;
        engine.forceQuit();
      }
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.setPrimaryEngine(previousEngine);
      Lizzie.gtpConsole = previousConsole;
      LizzieFrame.menu = previousMenu;
      Lizzie.board = previousBoard;
      Lizzie.resourceBundle = previousBundle;
    }
  }

  @Test
  void engineGamePreferenceSaveIgnoresUnrelatedThreadsAndSendsNoParameters() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    var previousFrame = Lizzie.frame;
    var previousEngine = Lizzie.leelaz;
    var previousConsole = Lizzie.gtpConsole;
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    Leelaz engine = null;
    SetKataEngines[] dialog = new SetKataEngines[1];
    try {
      Files.createDirectories(root.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
      Lizzie.frame = (QuietFrame) unsafe.allocateInstance(QuietFrame.class);
      Lizzie.gtpConsole = (QuietConsole) unsafe.allocateInstance(QuietConsole.class);
      engine = new Leelaz("katago gtp");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      engineHook(engine, "installFreshCommandOutputForTest", java.io.OutputStream.class, output);
      engine.started = true;
      engine.isLoaded = true;
      engine.isKatago = true;
      Lizzie.setPrimaryEngine(engine);
      featurecat.lizzie.enginegame.EngineGameSnapshotFixtures.publishPlaying();
      boolean showWrn = !Lizzie.config.showWRNInMenu;
      SwingUtilities.invokeAndWait(
          () -> {
            dialog[0] = new SetKataEngines();
            dialog[0].setVisible(true);
            for (String editor : new String[] {"chkEditWRN", "txtWRN", "chkEditPDA", "txtPDA"}) {
              assertFalse(
                  field(dialog[0], editor, javax.swing.JComponent.class).isEnabled(), editor);
            }
            field(dialog[0], "chkWRNInMenu", JCheckBox.class).setSelected(showWrn);
          });
      // An ordinary hand change replaces the opening reader; the thread handle is now stale.
      engineHook(engine, "installFreshCommandOutputForTest", java.io.OutputStream.class, output);
      output.reset();

      SwingUtilities.invokeLater(() -> field(dialog[0], "btnApply", JButton.class).doClick());

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (dialog[0].isVisible() && System.nanoTime() < deadline) Thread.sleep(10);
      SwingUtilities.invokeAndWait(
          () -> {
            for (java.awt.Window window : java.awt.Window.getWindows()) {
              assertFalse(
                  window instanceof javax.swing.JDialog d
                      && d.isVisible()
                      && d.getContentPane().getComponentCount() > 0
                      && d.getContentPane().getComponent(0) instanceof javax.swing.JOptionPane,
                  "no stale-thread warning");
            }
          });
      assertFalse(dialog[0].isVisible(), "pure preference save completes");
      assertEquals(showWrn, Lizzie.config.showWRNInMenu);
      String sent = output.toString(StandardCharsets.UTF_8);
      assertFalse(sent.contains("analysisWideRootNoise"), sent);
      assertFalse(sent.contains("playoutDoublingAdvantage"), sent);
    } finally {
      featurecat.lizzie.enginegame.EngineGameSnapshotFixtures.publishIdle();
      SwingUtilities.invokeAndWait(
          () -> {
            for (java.awt.Window window : java.awt.Window.getWindows()) {
              if (window instanceof javax.swing.JDialog d && d.isVisible()) d.dispose();
            }
          });
      if (engine != null) {
        engine.isNormalEnd = true;
        engine.forceQuit();
      }
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.setPrimaryEngine(previousEngine);
      Lizzie.gtpConsole = previousConsole;
      Lizzie.resourceBundle = previousBundle;
    }
  }

  @Test
  void savingADialogWhoseEngineChangedSendsNoParametersOutsideAnEngineGame() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    var previousFrame = Lizzie.frame;
    var previousEngine = Lizzie.leelaz;
    var previousConsole = Lizzie.gtpConsole;
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    Leelaz engine = null;
    SetKataEngines[] dialog = new SetKataEngines[1];
    try {
      Files.createDirectories(root.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
      Lizzie.frame = (QuietFrame) unsafe.allocateInstance(QuietFrame.class);
      Lizzie.gtpConsole = (QuietConsole) unsafe.allocateInstance(QuietConsole.class);
      engine = new Leelaz("katago gtp");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      engineHook(engine, "installFreshCommandOutputForTest", java.io.OutputStream.class, output);
      engine.started = true;
      engine.isLoaded = true;
      engine.isKatago = true;
      Lizzie.setPrimaryEngine(engine);
      SwingUtilities.invokeAndWait(
          () -> {
            dialog[0] = new SetKataEngines();
            dialog[0].setVisible(true);
            field(dialog[0], "chkEditWRN", JCheckBox.class).doClick();
            field(dialog[0], "txtWRN", JTextField.class).setText("0.4");
          });
      // The engine process the dialog opened on is replaced before the user confirms.
      engineHook(engine, "installFreshCommandOutputForTest", java.io.OutputStream.class, output);
      output.reset();

      SwingUtilities.invokeLater(() -> field(dialog[0], "btnApply", JButton.class).doClick());

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      boolean warned = false;
      while (!warned && System.nanoTime() < deadline) {
        Thread.sleep(10);
        for (java.awt.Window window : java.awt.Window.getWindows()) {
          warned |=
              window instanceof javax.swing.JDialog d
                  && d != dialog[0]
                  && d.isVisible()
                  && d.getContentPane().getComponentCount() > 0
                  && d.getContentPane().getComponent(0) instanceof javax.swing.JOptionPane;
        }
      }
      String sent = output.toString(StandardCharsets.UTF_8);
      assertFalse(sent.contains("analysisWideRootNoise"), sent);
      assertFalse(sent.contains("playoutDoublingAdvantage"), sent);
      assertTrue(warned, "the changed engine is reported instead of receiving the old dialog");
    } finally {
      SwingUtilities.invokeAndWait(
          () -> {
            for (java.awt.Window window : java.awt.Window.getWindows()) {
              if (window instanceof javax.swing.JDialog d && d.isVisible()) d.dispose();
            }
          });
      if (engine != null) {
        engine.isNormalEnd = true;
        engine.forceQuit();
      }
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.setPrimaryEngine(previousEngine);
      Lizzie.gtpConsole = previousConsole;
      Lizzie.resourceBundle = previousBundle;
    }
  }

  @Test
  void anOpenDialogLocksAndUnlocksItsParameterEditorsWithTheEngineGame() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    var previousFrame = Lizzie.frame;
    var previousEngine = Lizzie.leelaz;
    var previousConsole = Lizzie.gtpConsole;
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    Leelaz engine = null;
    SetKataEngines[] dialog = new SetKataEngines[1];
    String[] editors = {"chkEditWRN", "txtWRN", "chkEditPDA", "txtPDA"};
    try {
      Files.createDirectories(root.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      Lizzie.config.chkKataEngineWRN = true;
      Lizzie.config.chkKataEnginePDA = true;
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
      Lizzie.frame = (QuietFrame) unsafe.allocateInstance(QuietFrame.class);
      Lizzie.gtpConsole = (QuietConsole) unsafe.allocateInstance(QuietConsole.class);
      engine = new Leelaz("katago gtp");
      engineHook(
          engine,
          "installFreshCommandOutputForTest",
          java.io.OutputStream.class,
          new ByteArrayOutputStream());
      engine.started = true;
      engine.isLoaded = true;
      engine.isKatago = true;
      Lizzie.setPrimaryEngine(engine);
      SwingUtilities.invokeAndWait(
          () -> {
            dialog[0] = new SetKataEngines();
            dialog[0].setVisible(true);
            for (String editor : editors) {
              assertTrue(
                  field(dialog[0], editor, javax.swing.JComponent.class).isEnabled(), editor);
            }
          });
      // An engine game starts while the dialog stays open.
      Lizzie.engineGame.publishSnapshotForTest(
          featurecat.lizzie.enginegame.EngineGameSnapshotFixtures.starting());
      SwingUtilities.invokeAndWait(
          () -> {
            featurecat.lizzie.enginegame.SwingEngineGameChrome.INSTANCE.publish(
                new featurecat.lizzie.enginegame.EngineGameChromeTransition(
                    featurecat.lizzie.enginegame.EngineGameChromeTransition.Kind.STARTING,
                    Lizzie.engineGame.current()));
            for (String editor : editors) {
              assertFalse(
                  field(dialog[0], editor, javax.swing.JComponent.class).isEnabled(), editor);
            }
          });
      featurecat.lizzie.enginegame.EngineGameSnapshotFixtures.publishIdle();
      SwingUtilities.invokeAndWait(
          () -> {
            featurecat.lizzie.enginegame.SwingEngineGameChrome.INSTANCE.publish(
                new featurecat.lizzie.enginegame.EngineGameChromeTransition(
                    featurecat.lizzie.enginegame.EngineGameChromeTransition.Kind.BATCH_ENDED,
                    Lizzie.engineGame.current()));
            for (String editor : editors) {
              javax.swing.JComponent control =
                  field(dialog[0], editor, javax.swing.JComponent.class);
              assertTrue(control.isEnabled(), editor);
              assertEquals(null, control.getToolTipText(), editor);
            }
          });
    } finally {
      featurecat.lizzie.enginegame.EngineGameSnapshotFixtures.publishIdle();
      SwingUtilities.invokeAndWait(
          () -> {
            for (java.awt.Window window : java.awt.Window.getWindows()) {
              if (window instanceof javax.swing.JDialog d && d.isVisible()) d.dispose();
            }
          });
      if (engine != null) {
        engine.isNormalEnd = true;
        engine.forceQuit();
      }
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.setPrimaryEngine(previousEngine);
      Lizzie.gtpConsole = previousConsole;
      Lizzie.resourceBundle = previousBundle;
    }
  }

  @Test
  void engineGamePdaDialogSavesPreferencesWithoutSendingPda() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Config previousConfig = Lizzie.config;
    var previousFrame = Lizzie.frame;
    var previousEngine = Lizzie.leelaz;
    var previousConsole = Lizzie.gtpConsole;
    ResourceBundle previousBundle = Lizzie.resourceBundle;
    Leelaz engine = null;
    try {
      Files.createDirectories(root.resolve("save"));
      Lizzie.config = ConfigTestHelper.createBootstrapped(root);
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
      Lizzie.frame = (QuietFrame) unsafe.allocateInstance(QuietFrame.class);
      Lizzie.gtpConsole = (QuietConsole) unsafe.allocateInstance(QuietConsole.class);
      engine = new Leelaz("katago gtp");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      engineHook(engine, "installFreshCommandOutputForTest", java.io.OutputStream.class, output);
      engine.started = true;
      engine.isLoaded = true;
      engine.isKatago = true;
      Lizzie.setPrimaryEngine(engine);
      featurecat.lizzie.enginegame.EngineGameSnapshotFixtures.publishPlaying();
      double previousPda = engine.pda;
      SwingUtilities.invokeAndWait(
          () -> {
            SetKataPDA dialog = new SetKataPDA();
            dialog.chkDymPda.setSelected(false);
            dialog.chkNoPDA.setSelected(false);
            dialog.chkStaticPda.setSelected(true);
            dialog.chkAutoPDA.setSelected(true);
            try {
              Field current = SetKataPDA.class.getDeclaredField("txtStaticCur");
              current.setAccessible(true);
              ((JTextField) current.get(dialog)).setText("1.5");
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            }
            String apply = Lizzie.resourceBundle.getString("SetKataPDA.btnApply");
            for (java.awt.Component child : dialog.getContentPane().getComponents()) {
              if (child instanceof JButton button && apply.equals(button.getText())) {
                button.doClick();
              }
            }
            assertFalse(dialog.isVisible());
          });
      String sent = output.toString(StandardCharsets.UTF_8);
      assertFalse(sent.contains("pda"), sent);
      assertEquals(previousPda, engine.pda);
      assertEquals("pda 1.5", Lizzie.config.AutoPDA);
    } finally {
      featurecat.lizzie.enginegame.EngineGameSnapshotFixtures.publishIdle();
      SwingUtilities.invokeAndWait(
          () -> {
            for (java.awt.Window window : java.awt.Window.getWindows()) {
              if (window instanceof javax.swing.JDialog d && d.isVisible()) d.dispose();
            }
          });
      if (engine != null) {
        engine.isNormalEnd = true;
        engine.forceQuit();
      }
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.setPrimaryEngine(previousEngine);
      Lizzie.gtpConsole = previousConsole;
      Lizzie.resourceBundle = previousBundle;
    }
  }

  private static <T> T field(SetKataEngines dialog, String name, Class<T> type) {
    try {
      Field field = SetKataEngines.class.getDeclaredField(name);
      field.setAccessible(true);
      return type.cast(field.get(dialog));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void engineHook(Leelaz engine, String name, Class<?> argumentType, Object argument)
      throws Exception {
    var hook = Leelaz.class.getDeclaredMethod(name, argumentType);
    hook.setAccessible(true);
    hook.invoke(engine, argument);
  }

  private static int commandId(ByteArrayOutputStream output, String suffix) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    do {
      for (String line : output.toString(StandardCharsets.UTF_8).split("\\R")) {
        if (line.endsWith(suffix)) return Integer.parseInt(line.substring(0, line.indexOf(' ')));
      }
      Thread.sleep(5);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Missing command " + suffix);
  }

  private static final class QuietConsole extends GtpConsolePane {
    private QuietConsole() {
      super(null);
    }

    @Override
    public void addLine(String line) {}
  }

  private static final class QuietFrame extends LizzieFrame {
    @Override
    public GraphicsConfiguration getGraphicsConfiguration() {
      return GraphicsEnvironment.getLocalGraphicsEnvironment()
          .getDefaultScreenDevice()
          .getDefaultConfiguration();
    }
  }

  private static final class QuietMenu extends Menu {
    @Override
    public void updateMenuStatusForEngine() {}

    @Override
    public void setUseWrn(boolean use, String wrn) {}

    @Override
    public void setUseGfPda(boolean use, String pda) {}
  }
}
