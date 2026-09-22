package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Exercises the production board and renderers, without an engine or headless GUI stubs. */
public final class OfflineBoardAcceptanceTest {
  @Test
  void editsAndImportsWithNoEngine() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path result =
        DesktopProbeProcess.run(
            OfflineBoardAcceptanceTest.class, "offline-board", List.of(), List.of());
    assertEquals("PASS", Files.readString(result));
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    Path result = Path.of(args[1]);
    int exit = 1;
    try {
      Files.writeString(
          work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},\"ui\":{\"autoload-empty\":true,\"first-time-load\":false,\"play-sound\":false}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      SwingUtilities.invokeAndWait(
          () -> {
            assertTrue(Lizzie.frame.isDisplayable());
            // Match a failed startup rather than the harmless empty-engine placeholder.
            Lizzie.setPrimaryEngine(null);
            assertNull(Lizzie.leelaz);
            Lizzie.board.place(3, 3, Stone.BLACK);
            Lizzie.board.place(15, 15, Stone.WHITE);
            assertEquals(2, Lizzie.board.getHistory().getMoveNumber());
            assertTrue(Lizzie.board.previousMove(true));
            // Clicking an existing next move uses a different path from keyboard navigation.
            Lizzie.board.place(15, 15, Stone.WHITE);
            assertEquals(2, Lizzie.board.getHistory().getMoveNumber());
            Lizzie.board.pass(Stone.BLACK);
            Lizzie.board.setKomi(6.5);
            String sgf = assertDoesNotThrow(() -> SGFParser.saveToString(false));
            assertTrue(sgf.contains("B[dd]"));
            assertTrue(SGFParser.loadFromString(sgf));
            assertEquals(3, Lizzie.board.getHistory().mainTrunkLength());
            assertEquals(6.5, Lizzie.board.getHistory().getGameInfo().getKomi());
            LizzieFrame.menu.btnKomiUp.doClick();
            assertEquals(7.0, Lizzie.board.getHistory().getGameInfo().getKomi());
            LizzieFrame.menu.btnKomiDown.doClick();
            assertEquals(6.5, Lizzie.board.getHistory().getGameInfo().getKomi());
            LizzieFrame.menu.txtKomi.setText("8.5");
            java.awt.event.KeyEvent released = new java.awt.event.KeyEvent(
                LizzieFrame.menu.txtKomi, java.awt.event.KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_ENTER, '\n');
            for (java.awt.event.KeyListener listener : LizzieFrame.menu.txtKomi.getKeyListeners())
              listener.keyReleased(released);
            assertEquals(8.5, Lizzie.board.getHistory().getGameInfo().getKomi());
            GameInfoDialog dialog = new GameInfoDialog();
            try {
              dialog.setGameInfo(Lizzie.board.getHistory().getGameInfo());
              assertDoesNotThrow(dialog::apply);
              assertEquals(8.5, Lizzie.board.getHistory().getGameInfo().getKomi());
            } finally {
              dialog.dispose();
            }
            while (Lizzie.board.nextMove(true)) {}
            java.awt.image.BufferedImage graphImage = new java.awt.image.BufferedImage(
                640, 480, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D graphGraphics = graphImage.createGraphics();
            try {
              for (boolean kataBoard : new boolean[] {false, true}) {
                Lizzie.board.isKataBoard = kataBoard;
                assertDoesNotThrow(() -> new WinrateGraph().draw(
                    graphGraphics, graphGraphics, graphGraphics, 0, 0, 640, 480));
              }
            } finally {
              graphGraphics.dispose();
            }
            Lizzie.board.SpinAndMirror(3);
            assertEquals(3, Lizzie.board.getHistory().mainTrunkLength());
            Lizzie.board.clear(false);
            assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
            verifyOfflineEstimateMenus();
            org.junit.jupiter.api.Assertions.assertAll(
                () -> verifyCancelledOfflineSave(() -> LizzieFrame.saveFile(false)),
                () -> verifyCancelledOfflineSave(() -> LizzieFrame.saveFile(true)),
                () -> verifyCancelledOfflineSave(() -> Lizzie.frame.saveRawFileComment()),
                () -> verifyOverwriteCancel(work, () -> LizzieFrame.saveFile(false)),
                () -> verifyOverwriteCancel(work, () -> LizzieFrame.saveFile(true)),
                () -> verifyOverwriteCancel(work, () -> Lizzie.frame.saveRawFileComment()),
                () -> verifyOverwriteCancel(work, LizzieFrame::saveCurrentBranch),
                () -> verifySaveAnalysisState(work));
          });
      verifySuccessfulSaves(work);
      Files.writeString(result, "PASS");
      exit = 0;
    } catch (Throwable failure) {
      failure.printStackTrace();
      Files.writeString(result, failure.toString());
    } finally {
      System.exit(exit);
    }
  }

  private static void verifyOfflineEstimateMenus() {
    javax.swing.JMenu estimate = findMenu(LizzieFrame.menu, "Menu.kataEstimate");
    for (String key : new String[] {
        "Menu.kataEstimateClose", "Menu.kataEstimateCloseView",
        "Menu.kataEstimateOnMainBoard", "Menu.kataEstimateOnSubBoard",
        "Menu.kataEstimateOnBothBoard", "Menu.kataEstimateByTransparentSmall",
        "Menu.kataEstimateByTransparent", "Menu.kataEstimateByTransparentNotOnLive",
        "Menu.kataEstimateByBigSquare", "Menu.kataEstimateBySize"}) {
      javax.swing.JMenuItem item = findMenuItem(estimate.getMenuComponents(), key);
      assertTrue(item.isEnabled(), key);
      assertDoesNotThrow(() -> { item.doClick(); }, key);
      assertNull(Lizzie.leelaz, "Changing display preferences must not create an engine");
    }
    javax.swing.JMenu pure = (javax.swing.JMenu) findMenuItem(
        estimate.getMenuComponents(), "Menu.kataEstimateInPureNet");
    for (String key : new String[] {
        "Menu.kataEstimateByTransparentSmall", "Menu.kataEstimateByTransparent",
        "Menu.kataEstimateByTransparentNotOnLive", "Menu.kataEstimateByBigSquare",
        "Menu.kataEstimateBySize"}) {
      javax.swing.JMenuItem item = findMenuItem(pure.getMenuComponents(), key);
      assertDoesNotThrow(() -> { item.doClick(); }, key);
      assertNull(Lizzie.leelaz);
    }
  }

  private static void verifyCancelledOfflineSave(Runnable save) {
    boolean rawBefore = LizzieFrame.isSavingRaw;
    boolean commentsBefore = LizzieFrame.isSavingRawComment;
    java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    javax.swing.Timer cancelChooser = new javax.swing.Timer(100, event -> {
      for (java.awt.Window window : java.awt.Window.getWindows()) {
        if (!window.isShowing()) continue;
        javax.swing.JFileChooser chooser = findChooser(window);
        if (chooser != null) {
          assertEquals(Lizzie.frame, window.getOwner(), "Main window must own the save dialog");
          cancelled.set(true);
          chooser.cancelSelection();
          ((javax.swing.Timer) event.getSource()).stop();
          return;
        }
      }
    });
    cancelChooser.start();
    try {
      assertDoesNotThrow(() -> { save.run(); });
      assertTrue(cancelled.get(), "Save must reach its chooser with no foreground engine");
      assertEquals(rawBefore, LizzieFrame.isSavingRaw, "Cancelled save leaked raw mode");
      assertEquals(commentsBefore, LizzieFrame.isSavingRawComment, "Cancelled save leaked comment mode");
    } finally {
      cancelChooser.stop();
      LizzieFrame.isSavingRaw = rawBefore;
      LizzieFrame.isSavingRawComment = commentsBefore;
    }
  }

  private static javax.swing.JFileChooser findChooser(java.awt.Container root) {
    for (java.awt.Component component : root.getComponents()) {
      if (component instanceof javax.swing.JFileChooser chooser) return chooser;
      if (component instanceof java.awt.Container container) {
        javax.swing.JFileChooser found = findChooser(container);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void verifySuccessfulSaves(Path work) throws Exception {
    Runnable[] saves = {
        () -> LizzieFrame.saveFile(false), () -> LizzieFrame.saveFile(true),
        () -> Lizzie.frame.saveRawFileComment(), LizzieFrame::saveCurrentBranch};
    for (int index = 0; index < saves.length; index++) {
      Path target = work.resolve("成功保存-中文-" + index + ".SGF");
      Runnable save = saves[index];
      int mode = index;
      SwingUtilities.invokeAndWait(() -> {
        assertTrue(SGFParser.loadFromString("(;SZ[19];B[aa](;W[bb])(;W[dd]C[before]))"));
        var history = Lizzie.board.getHistory();
        var root = history.getStart();
        var fork = root.next().orElseThrow();
        var selected = fork.getVariation(1).orElseThrow();
        history.setHead(selected);
        javax.swing.Timer approve = new javax.swing.Timer(100, event -> {
          for (java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isShowing()) continue;
            javax.swing.JFileChooser chooser = findChooser(window);
            if (chooser != null) {
              assertEquals(Lizzie.frame, window.getOwner());
              chooser.setSelectedFile(target.toFile());
              chooser.approveSelection();
              ((javax.swing.Timer) event.getSource()).stop();
              return;
            }
          }
        });
        approve.start();
        try {
          save.run();
          assertEquals(history, Lizzie.board.getHistory(), "Save must not rebuild the board");
          assertEquals(selected, history.getCurrentHistoryNode());
          assertEquals(2, fork.numberOfChildren());
          selected.getData().comment = "edited after snapshot";
        } finally {
          approve.stop();
        }
      });
      SgfSaveCoordinator.pendingSaves().get(10, java.util.concurrent.TimeUnit.SECONDS);
      String written = Files.readString(target);
      assertTrue(written.contains(";B[aa]"));
      assertTrue(written.contains(";W[dd]"));
      assertEquals(mode != 3, written.contains(";W[bb]"));
      assertEquals(mode == 0 || mode == 2, written.contains("C[before]"));
      assertTrue(!written.contains("edited after snapshot"));
      assertTrue(!Files.exists(Path.of(target + ".sgf")), "Uppercase SGF must not gain another suffix");
      if (mode < 2) SwingUtilities.invokeAndWait(() -> assertEquals(target.toFile(), LizzieFrame.curFile));
    }
  }

  private static void verifyOverwriteCancel(Path work, Runnable save) throws Exception {
    Path selected = work.resolve("protected-sgf-中文");
    Path target = work.resolve("protected-sgf-中文.sgf");
    String original = "(;GM[1]SZ[19]C[Do not overwrite this fixture])";
    Files.writeString(target, original);
    java.util.concurrent.atomic.AtomicBoolean approved = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    javax.swing.Timer control = new javax.swing.Timer(100, event -> {
      for (java.awt.Window window : java.awt.Window.getWindows()) {
        if (!window.isShowing()) continue;
        if (!approved.get()) {
          javax.swing.JFileChooser chooser = findChooser(window);
          if (chooser != null) {
            approved.set(true);
            chooser.setSelectedFile(selected.toFile());
            chooser.approveSelection();
            return;
          }
        } else {
          javax.swing.JOptionPane pane = findOptionPane(window);
          if (pane != null) {
            cancelled.set(true);
            pane.setValue(javax.swing.JOptionPane.CANCEL_OPTION);
            return;
          }
        }
      }
    });
    control.start();
    try {
      save.run();
      assertTrue(cancelled.get(), "The final suffixed target requires overwrite confirmation");
      assertEquals(original, Files.readString(target));
      assertTrue(!Files.exists(selected), "The unsuffixed name must not be written");
    } finally {
      control.stop();
    }
  }

  private static void verifySaveAnalysisState(Path work) throws Exception {
    Leelaz previous = Lizzie.leelaz;
    try {
      SaveTrackingEngine active = new SaveTrackingEngine(true);
      Lizzie.setPrimaryEngine(active);
      verifyCancelledOfflineSave(() -> LizzieFrame.saveFile(true));
      assertTrue(active.pondering);
      assertEquals(2, active.toggles, "Cancel should stop then resume the same active engine");
      verifyOverwriteCancel(work, () -> LizzieFrame.saveFile(false));
      assertTrue(active.pondering);
      assertEquals(4, active.toggles, "Overwrite cancellation must also resume analysis");

      SaveTrackingEngine paused = new SaveTrackingEngine(false);
      Lizzie.setPrimaryEngine(paused);
      verifyCancelledOfflineSave(() -> LizzieFrame.saveFile(false));
      assertEquals(0, paused.toggles, "Saving must preserve a user's paused engine");

      SaveTrackingEngine replacement = new SaveTrackingEngine(false);
      Lizzie.setPrimaryEngine(active);
      javax.swing.Timer switchEngine = new javax.swing.Timer(50, event -> {
        Lizzie.setPrimaryEngine(replacement);
        ((javax.swing.Timer) event.getSource()).stop();
      });
      switchEngine.start();
      try {
        verifyCancelledOfflineSave(() -> LizzieFrame.saveFile(false));
        assertEquals(replacement, Lizzie.leelaz);
        assertEquals(0, replacement.toggles, "A replacement engine must not be resumed by save");
        assertEquals(5, active.toggles, "The replaced engine must remain stopped");
      } finally {
        switchEngine.stop();
      }
    } finally {
      Lizzie.setPrimaryEngine(previous);
    }
  }

  private static final class SaveTrackingEngine extends Leelaz {
    private boolean pondering;
    private int toggles;

    SaveTrackingEngine(boolean pondering) throws java.io.IOException {
      super("");
      this.pondering = pondering;
    }

    @Override
    public boolean isPondering() {
      return pondering;
    }

    @Override
    public void togglePonder() {
      pondering = !pondering;
      toggles++;
    }
  }

  private static javax.swing.JOptionPane findOptionPane(java.awt.Container root) {
    for (java.awt.Component component : root.getComponents()) {
      if (component instanceof javax.swing.JOptionPane pane) return pane;
      if (component instanceof java.awt.Container container) {
        javax.swing.JOptionPane found = findOptionPane(container);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static javax.swing.JMenu findMenu(javax.swing.JMenuBar bar, String key) {
    javax.swing.JMenuItem item = findMenuItem(bar.getComponents(), key);
    assertTrue(item instanceof javax.swing.JMenu, key);
    return (javax.swing.JMenu) item;
  }

  private static javax.swing.JMenuItem findMenuItem(java.awt.Component[] components, String key) {
    String label = Lizzie.resourceBundle.getString(key);
    javax.swing.JMenuItem result = findMenuItemByLabel(components, label);
    assertTrue(result != null, "Missing production menu: " + key);
    return result;
  }

  private static javax.swing.JMenuItem findMenuItemByLabel(
      java.awt.Component[] components, String label) {
    for (java.awt.Component component : components) {
      if (component instanceof javax.swing.JMenuItem item && label.equals(item.getText()))
        return item;
      if (component instanceof javax.swing.JMenu menu) {
        javax.swing.JMenuItem found = findMenuItemByLabel(menu.getMenuComponents(), label);
        if (found != null) return found;
      }
    }
    return null;
  }
}
