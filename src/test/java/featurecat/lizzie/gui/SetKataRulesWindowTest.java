package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SetKataRulesWindowTest {
  @BeforeEach
  void requireDesktopWhenRequested() {
    assertFalse(Boolean.getBoolean("lizzie.desktop.required") && GraphicsEnvironment.isHeadless());
  }

  private static final String CHINESE =
      "{\"ko\":\"SIMPLE\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
          + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true}";
  private static final String POSITIONAL_CHINESE =
      "{\"ko\":\"POSITIONAL\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
          + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true,"
          + "\"experimentalKo\":\"X\"}";
  private static final String JAPANESE =
      "{\"ko\":\"SIMPLE\",\"scoring\":\"TERRITORY\",\"tax\":\"SEKI\",\"suicide\":false,"
          + "\"hasButton\":false,\"whiteHandicapBonus\":\"0\",\"friendlyPassOk\":true}";

  @Test
  void statusWaitAndMutatingReadUseEventThread() throws Exception {
    SetKataRules dialog = allocate(EdtCheckedReadback.class);
    awaitStatus(dialog, "confirmed");
    assertTrue(readOnEdt(dialog::getRules));
  }

  /** No native window is needed to verify the test helpers' thread ownership. */
  private static final class EdtCheckedReadback extends SetKataRules {
    @Override
    String statusText() {
      assertTrue(SwingUtilities.isEventDispatchThread(), "statusText must be read on the EDT");
      return "confirmed";
    }

    @Override
    public boolean getRules() {
      assertTrue(SwingUtilities.isEventDispatchThread(), "getRules mutates Swing state");
      return true;
    }
  }

  @Test
  void navigationFocusesScoringControlsWithoutChangingRulesOrSaving() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      SetKataRules dialog = readOnEdt(() -> new SetKataRules(fixture.engine));
      JFontRadioButton area = dialog.editor.rdoArea;
      JFontRadioButton territory = dialog.editor.rdoTerritory;
      Border areaBorder = readOnEdt(area::getBorder);
      Border territoryBorder = readOnEdt(territory::getBorder);
      boolean areaBorderPainted = readOnEdt(area::isBorderPainted);
      boolean territoryBorderPainted = readOnEdt(territory::isBorderPainted);
      boolean areaSelected = readOnEdt(area::isSelected);
      boolean territorySelected = readOnEdt(territory::isSelected);
      String configBefore = Lizzie.config.uiConfig.toString();

      SwingUtilities.invokeAndWait(
          () -> {
            dialog.locateRulesEditor();
            dialog.setVisible(true);
          });
      boolean[] highlightVisible = new boolean[1];
      SwingUtilities.invokeAndWait(
          () -> {
            assertTrue(area.isBorderPainted());
            assertTrue(territory.isBorderPainted());
            highlightVisible[0] =
                rendersColor(area, new Color(185, 156, 93))
                    && rendersColor(territory, new Color(185, 156, 93));
          });
      assertTrue(highlightVisible[0], "scoring controls must paint the navigation highlight");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (System.nanoTime() < deadline
          && readOnEdt(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner())
              != (territorySelected ? territory : area)) {
        Thread.sleep(20L);
        SwingUtilities.invokeAndWait(() -> {});
      }

      runOnEdt(
          () -> {
            assertSame(
                territorySelected ? territory : area,
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
            assertEquals(areaSelected, area.isSelected());
            assertEquals(territorySelected, territory.isSelected());
          });
      assertEquals(configBefore, Lizzie.config.uiConfig.toString());
      SwingUtilities.invokeAndWait(() -> dialog.setVisible(false));
      runOnEdt(
          () -> {
            assertSame(areaBorder, area.getBorder());
            assertSame(territoryBorder, territory.getBorder());
            assertEquals(areaBorderPainted, area.isBorderPainted());
            assertEquals(territoryBorderPainted, territory.isBorderPainted());
            dialog.dispose();
          });
    }
  }

  @Test
  void mismatchedManualReadbackDoesNotChangeSessionTarget() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      SetKataRules dialog = readOnEdt(() -> new SetKataRules(fixture.engine));
      runOnEdt(
          () -> {
            assertTrue(dialog.statusText().contains("Confirming engine rules"));
            assertTrue(
                dialog.chkbxAutoLoadRules.getText().contains("startup default"),
                dialog.chkbxAutoLoadRules.getText());
          });

      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + queryId + " " + POSITIONAL_CHINESE);
      awaitConfirmedReadback(dialog);
      runOnEdt(
          () -> {
            assertTrue(dialog.hasRulesResponse());
            assertTrue(dialog.getRules());
            assertTrue(dialog.editor.rdoPositionKo.isSelected());
          });
      assertEquals(KataGoRules.Summary.OTHER, fixture.engine.rulesSummary);
      BoardHistoryList.SessionRulesTarget originalTarget =
          Lizzie.board.getHistory().captureSessionRules();

      runOnEdt(
          () -> {
            dialog.editor.rdoTerritory.setSelected(true);
            dialog.editor.rdoSeKiTax.setSelected(true);
            dialog.editor.rdoNoHandicapKomi.setSelected(true);
            dialog.editor.rdoSimpleKo.setSelected(true);
            dialog.editor.rdoNoSuicide.setSelected(true);
            dialog.setVisible(true);
            dialog.applySelectedRules();
            assertTrue(dialog.statusText().contains("Confirming engine rules"));
          });
      assertEquals(KataGoRules.Summary.OTHER, fixture.engine.rulesSummary);
      assertTrue(fixture.output.toString().contains("experimentalKo"));
      assertFalse(fixture.engine.engineRulesResult().isConfirmed());

      int setId = commandIdFor(fixture.output.toString(), "kata-set-rules");
      dispatch(fixture.engine, "=" + setId);
      int readbackId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + readbackId + " " + JAPANESE);
      awaitStatus(dialog, "Failed to set engine rules");
      runOnEdt(
          () -> {
            assertFalse(dialog.getRules());
            assertTrue(dialog.statusText().contains("Failed to set engine rules"));
            assertTrue(dialog.editor.rdoTerritory.isSelected());
            assertFalse(dialog.editor.rdoArea.isSelected());
          });
      assertEquals(KataGoRules.Summary.JAPANESE, fixture.engine.rulesSummary);
      assertTrue(fixture.engine.engineRulesResult().isConfirmed());
      assertEquals("TERRITORY", fixture.engine.engineRulesResult().observed().string("scoring"));
      assertSame(originalTarget, Lizzie.board.getHistory().captureSessionRules());
      assertTrue(readOnEdt(dialog::isVisible), "A mismatched readback must remain visible");
      assertTrue(KataGoRules.parse(POSITIONAL_CHINESE).orElseThrow().hasField("experimentalKo"));
      closeDialog(dialog);
    }
  }

  @Test
  void closingAfterApplyStillPublishesMatchingManualReadback() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      SetKataRules dialog = readOnEdt(() -> new SetKataRules(fixture.engine));
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + queryId + " " + CHINESE);
      awaitConfirmedReadback(dialog);
      BoardHistoryList history = Lizzie.board.getHistory();
      BoardHistoryList.SessionRulesTarget original = history.captureSessionRules();

      runOnEdt(
          () -> {
            dialog.editor.rdoTerritory.setSelected(true);
            dialog.editor.rdoSeKiTax.setSelected(true);
            dialog.editor.rdoNoHandicapKomi.setSelected(true);
            dialog.editor.rdoSimpleKo.setSelected(true);
            dialog.editor.rdoNoSuicide.setSelected(true);
            dialog.applySelectedRules();
            closeDialog(dialog);
          });

      int setId = commandIdFor(fixture.output.toString(), "kata-set-rules");
      dispatch(fixture.engine, "=" + setId);
      int readbackId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + readbackId + " " + JAPANESE);

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (history.captureSessionRules() == original && System.nanoTime() < deadline) {
        Thread.sleep(20L);
      }
      BoardHistoryList.SessionRulesTarget published = history.captureSessionRules();
      assertFalse(published == original);
      assertTrue(
          published
              .parsedRules()
              .orElseThrow()
              .semanticallyEquals(KataGoRules.parse(JAPANESE).orElseThrow()));
    }
  }

  @Test
  void confirmSavesStartupRulesAndClosesAfterMatchingReadback() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      SetKataRules dialog = readOnEdt(() -> new SetKataRules(fixture.engine));
      SwingUtilities.invokeAndWait(() -> dialog.setVisible(true));
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + queryId + " " + CHINESE);
      awaitConfirmedReadback(dialog);
      BoardHistoryList history = Lizzie.board.getHistory();
      BoardHistoryList.SessionRulesTarget original = history.captureSessionRules();
      runOnEdt(
          () -> {
            assertTrue(dialog.isVisible(), "Opening readback must not close the editor");
            dialog.chkbxAutoLoadRules.setSelected(true);
            dialog.editor.rdoTerritory.setSelected(true);
            dialog.editor.rdoSeKiTax.setSelected(true);
            dialog.editor.rdoNoHandicapKomi.setSelected(true);
            dialog.editor.rdoSimpleKo.setSelected(true);
            dialog.editor.rdoNoSuicide.setSelected(true);
          });
      clickApply(dialog);
      assertTrue(readOnEdt(dialog::isVisible), "Keep the editor open until the engine confirms");
      JSONObject saved =
          new JSONObject(Files.readString(Path.of(Lizzie.config.getConfigFilePath())))
              .getJSONObject("ui");
      assertTrue(saved.getBoolean("auto-load-kata-rules"));
      assertTrue(
          KataGoRules.parse(saved.getString("kata-rules"))
              .orElseThrow()
              .semanticallyEquals(KataGoRules.parse(JAPANESE).orElseThrow()));
      int setId = commandIdFor(fixture.output.toString(), "kata-set-rules");
      dispatch(fixture.engine, "=" + setId);
      int readbackId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + readbackId + " " + JAPANESE);
      awaitConfirmedReadback(dialog);
      // The status watcher polls every 50 ms and closes the editor on the event thread.
      awaitHidden(dialog);

      BoardHistoryList.SessionRulesTarget published = history.captureSessionRules();
      assertEquals(original.revision() + 1L, published.revision());
      assertTrue(readOnEdt(dialog::getRules));
      assertEquals(original.revision() + 1L, history.captureSessionRules().revision());
      assertFalse(readOnEdt(dialog::isVisible), "Successful confirmation must close the editor");
      closeDialog(dialog);
    }
  }

  @Test
  void confirmClosesWhenTheOperationCompletesAfterItsConfirmedSnapshot() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      SetKataRules dialog = readOnEdt(() -> new SetKataRules(fixture.engine));
      SwingUtilities.invokeAndWait(() -> dialog.setVisible(true));
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + queryId + " " + CHINESE);
      awaitConfirmedReadback(dialog);
      runOnEdt(
          () -> {
            dialog.editor.rdoTerritory.setSelected(true);
            dialog.editor.rdoSeKiTax.setSelected(true);
            dialog.editor.rdoNoHandicapKomi.setSelected(true);
            dialog.editor.rdoSimpleKo.setSelected(true);
            dialog.editor.rdoNoSuicide.setSelected(true);
          });
      clickApply(dialog);
      dispatch(fixture.engine, "=" + commandIdFor(fixture.output.toString(), "kata-set-rules"));
      int readbackId = commandIdFor(fixture.output.toString(), "kata-get-rules");

      // Confirmation publishes the operation's terminal snapshot, refreshes the frame and only
      // then completes the operation. Hold the reader in that window while the status watcher
      // (50 ms polls) sees the confirmed snapshot whose result() is still null.
      GatedRefreshFrame.entered = new java.util.concurrent.CountDownLatch(1);
      GatedRefreshFrame.release = new java.util.concurrent.CountDownLatch(1);
      Lizzie.frame = allocate(GatedRefreshFrame.class);
      Thread reader =
          new Thread(
              () -> {
                try {
                  dispatch(fixture.engine, "=" + readbackId + " " + JAPANESE);
                } catch (Exception failure) {
                  throw new AssertionError(failure);
                }
              },
              "set-kata-rules-readback");
      reader.start();
      try {
        assertTrue(GatedRefreshFrame.entered.await(5, TimeUnit.SECONDS));
        Thread.sleep(300L);
        SwingUtilities.invokeAndWait(() -> {});
        assertTrue(readOnEdt(dialog::isVisible), "Not closed before the operation completes");
      } finally {
        GatedRefreshFrame.release.countDown();
        reader.join(5_000L);
      }

      awaitHidden(dialog);
      assertFalse(
          readOnEdt(dialog::isVisible),
          "Completion after the confirmed snapshot must close the editor");
      closeDialog(dialog);
    }
  }

  @Test
  void delayedQueryStaysPendingUntilProtocolSettlesThenAppliesRadios() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      SetKataRules dialog = readOnEdt(() -> new SetKataRules(fixture.engine));
      Thread.sleep(250L);
      SwingUtilities.invokeAndWait(() -> {});
      runOnEdt(
          () -> {
            assertTrue(dialog.statusText().contains("Confirming engine rules"));
            assertFalse(dialog.hasRulesResponse());
            assertFalse(dialog.editor.rdoPositionKo.isSelected());
          });

      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + queryId + " " + POSITIONAL_CHINESE);
      awaitConfirmedReadback(dialog);
      runOnEdt(
          () -> {
            assertTrue(dialog.hasRulesResponse());
            assertTrue(dialog.editor.rdoPositionKo.isSelected());
            assertFalse(dialog.statusText().contains("Failed to read engine rules"));
          });
      closeDialog(dialog);
    }
  }

  @Test
  void windowShowsQueryFailureWithoutTreatingLastKnownAsSuccess() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      SetKataRules dialog = readOnEdt(() -> new SetKataRules(fixture.engine));
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + queryId + " " + CHINESE);
      awaitConfirmedReadback(dialog);
      assertTrue(readOnEdt(dialog::getRules));
      String previous = fixture.engine.recentRulesLine;

      fixture.engine.queryEngineRules();
      int failedQueryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "?" + failedQueryId + " unknown command");
      awaitStatus(dialog, "Failed to read engine rules");

      EngineRulesResult result = fixture.engine.engineRulesResult();
      assertEquals(EngineRulesResult.Status.QUERY_FAILED, result.status());
      runOnEdt(
          () -> {
            assertTrue(dialog.statusText().contains("Failed to read engine rules"));
            assertTrue(dialog.statusText().contains("Last known rules may be stale"));
          });
      assertEquals(previous, fixture.engine.recentRulesLine);
      assertFalse(result.isConfirmed());
      assertTrue(result.lastKnownStale());
      closeDialog(dialog);
    }
  }

  @Test
  void composeConfirmPreservesChineseFriendlyPassOkAndExtrasWithoutEngineCommands()
      throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      JSONObject json = new JSONObject(CHINESE);
      json.put("experimentalKo", "X");
      KataGoRules initial = KataGoRules.fromJson(json);

      SetKataRules cancelled = readOnEdt(() -> new SetKataRules(fixture.engine, true, initial));
      closeDialog(cancelled);
      assertNull(composedRules(cancelled));

      SetKataRules dialog = readOnEdt(() -> new SetKataRules(fixture.engine, true, initial));
      runOnEdt(
          () -> {
            assertTrue(dialog.editor.rdoSimpleKo.isSelected());
            assertTrue(dialog.editor.rdoArea.isSelected());
            dialog.applySelectedRules();
          });
      KataGoRules confirmed = composedRules(dialog);
      assertTrue(confirmed.hasRequiredFields());
      assertTrue(confirmed.hasField("friendlyPassOk"));
      assertTrue(confirmed.bool("friendlyPassOk"));
      assertTrue(confirmed.hasField("experimentalKo"));
      assertTrue(initial.semanticallyEquals(confirmed));

      SetKataRules edited = readOnEdt(() -> new SetKataRules(fixture.engine, true, initial));
      runOnEdt(
          () -> {
            edited.editor.rdoPositionKo.setSelected(true);
            edited.applySelectedRules();
          });
      KataGoRules overlaid = composedRules(edited);
      assertEquals("POSITIONAL", overlaid.string("ko"));
      assertTrue(overlaid.hasRequiredFields());
      assertTrue(overlaid.bool("friendlyPassOk"));
      assertTrue(overlaid.hasField("experimentalKo"));
      assertFalse(initial.semanticallyEquals(overlaid));

      String commands = fixture.output.toString();
      assertFalse(commands.contains("kata-get-rules"));
      assertFalse(commands.contains("kata-set-rules"));
      closeDialog(dialog);
      closeDialog(edited);
    }
  }

  @Test
  void unchangedTerritoryRulesRoundTripWithoutRewritingHiddenFields() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      for (String preset : KataGoRules.officialPresetNames()) {
        KataGoRules initial = KataGoRules.parse(preset).orElseThrow();
        SetKataRules dialog = readOnEdt(() -> new SetKataRules(fixture.engine, true, initial));
        try {
          runOnEdt(dialog::applySelectedRules);
          assertTrue(initial.semanticallyEquals(composedRules(dialog)), preset);
        } finally {
          closeDialog(dialog);
        }
      }
    }
  }

  @Test
  void presetButtonsComposeExactPresetsAndTerritoryLocksFriendlyPass() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      KataGoRules initial = KataGoRules.parse("tromp-taylor").orElseThrow();
      for (String preset : List.of("goe", "tromp-taylor", "stone-scoring", "chinese", "japanese")) {
        SetKataRules dialog = readOnEdt(() -> new SetKataRules(fixture.engine, true, initial));
        KataRulesEditor editor = dialog.editor;
        JFontButton button =
            switch (preset) {
              case "goe" -> editor.btnGoeRule;
              case "tromp-taylor" -> editor.btnTTRule;
              case "stone-scoring" -> editor.btnChnOldRule;
              case "chinese" -> editor.btnChnRule;
              default -> editor.btnJpnRule;
            };
        SwingUtilities.invokeAndWait(button::doClick);
        runOnEdt(dialog::applySelectedRules);
        assertTrue(
            composedRules(dialog).semanticallyEquals(KataGoRules.parse(preset).orElseThrow()),
            preset + " -> " + composedRules(dialog));
        closeDialog(dialog);
      }

      SetKataRules dialog = readOnEdt(() -> new SetKataRules(fixture.engine, true, initial));
      KataRulesEditor editor = dialog.editor;
      assertTrue(readOnEdt(editor.rdoNoFriendlyPass::isSelected));
      SwingUtilities.invokeAndWait(editor.rdoTerritory::doClick);
      runOnEdt(
          () -> {
            assertTrue(editor.rdoNoFriendlyPass.isSelected());
            assertFalse(editor.rdoFriendlyPass.isEnabled());
            assertFalse(editor.rdoNoFriendlyPass.isEnabled());
          });
      SwingUtilities.invokeAndWait(editor.rdoArea::doClick);
      runOnEdt(
          () -> {
            assertTrue(editor.rdoNoFriendlyPass.isSelected());
            assertTrue(editor.rdoNoFriendlyPass.isEnabled());
            dialog.applySelectedRules();
          });
      assertFalse(composedRules(dialog).bool(KataGoRules.FRIENDLY_PASS_OK));
      assertFalse(fixture.output.toString().contains("kata-set-rules"));
      closeDialog(dialog);
    }
  }

  @Test
  void komiHintComparesGameKomiWithEditedRulesAndLeavesKomiAlone() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    try (Fixture fixture = Fixture.ordinary()) {
      GameInfo game = Lizzie.board.getHistory().getGameInfo();
      runOnEdt(() -> game.setKomiNoMenu(7.5));
      SetKataRules dialog = readOnEdt(() -> new SetKataRules(fixture.engine));
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      dispatch(fixture.engine, "=" + queryId + " " + CHINESE);
      awaitConfirmedReadback(dialog);
      assertEquals("", readOnEdt(dialog::komiHintText));

      SwingUtilities.invokeAndWait(dialog.editor.btnJpnRule::doClick);
      String hint = readOnEdt(dialog::komiHintText);
      assertTrue(hint.contains("7.5") && hint.contains("6.5"), hint);
      assertEquals(7.5, game.getKomi());

      runOnEdt(() -> game.setKomiNoMenu(6.5));
      activate(dialog);
      assertEquals(
          "", readOnEdt(dialog::komiHintText), "komi edited elsewhere refreshes on reactivation");

      runOnEdt(
          () -> {
            game.setKomiNoMenu(0.5);
            game.setHandicap(2);
          });
      activate(dialog);
      assertEquals("", readOnEdt(dialog::komiHintText), "handicap games use their own komi");
      runOnEdt(
          () -> {
            game.setHandicap(0);
            game.setKomiNoMenu(7.5);
          });

      SwingUtilities.invokeAndWait(dialog.editor.btnChnRule::doClick);
      assertEquals("", readOnEdt(dialog::komiHintText));
      closeDialog(dialog);

      SetKataRules compose =
          readOnEdt(
              () ->
                  new SetKataRules(
                      fixture.engine, true, KataGoRules.parse("japanese").orElseThrow()));
      activate(compose);
      assertEquals("", readOnEdt(compose::komiHintText), "match-rules composing has no game komi");
      closeDialog(compose);
    }
  }

  private static void runOnEdt(CheckedAction action) {
    readOnEdt(
        () -> {
          action.run();
          return null;
        });
  }

  /** Reads and mutating reads such as getRules() must share the status watcher's EDT owner. */
  private static <T> T readOnEdt(Callable<T> action) {
    FutureTask<T> task = new FutureTask<>(action);
    try {
      if (SwingUtilities.isEventDispatchThread()) {
        task.run();
      } else {
        SwingUtilities.invokeAndWait(task);
      }
      return task.get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof Error error) throw error;
      throw new AssertionError(failure.getCause());
    } catch (java.lang.reflect.InvocationTargetException failure) {
      throw new AssertionError(failure.getCause());
    }
  }

  private interface CheckedAction {
    void run() throws Exception;
  }

  private static void activate(SetKataRules dialog) throws Exception {
    SwingUtilities.invokeAndWait(
        () ->
            dialog.dispatchEvent(
                new java.awt.event.WindowEvent(
                    dialog, java.awt.event.WindowEvent.WINDOW_ACTIVATED)));
  }

  private static KataGoRules composedRules(SetKataRules dialog) throws Exception {
    Field field = SetKataRules.class.getDeclaredField("composed");
    field.setAccessible(true);
    return readOnEdt(() -> (KataGoRules) field.get(dialog));
  }

  private static void awaitConfirmedReadback(SetKataRules dialog) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      boolean[] settled = new boolean[1];
      SwingUtilities.invokeAndWait(
          () -> settled[0] = dialog.hasRulesResponse() && dialog.statusText().isEmpty());
      if (settled[0]) {
        return;
      }
      Thread.sleep(20L);
    }
    throw new AssertionError("Confirmed readback did not settle: " + readOnEdt(dialog::statusText));
  }

  private static void clickApply(SetKataRules dialog) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (java.awt.Component component : dialog.getContentPane().getComponents()) {
            if (component instanceof JFontButton button
                && button
                    .getText()
                    .equals(Lizzie.resourceBundle.getString("SetKataRules.btnApply"))) {
              button.doClick();
              return;
            }
          }
          throw new AssertionError("Confirm button missing");
        });
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  /** A frame whose refresh() can hold the engine reader; allocated without a constructor. */
  private static final class GatedRefreshFrame extends LizzieFrame {
    // Static because allocated frames do not run field initializers.
    static volatile java.util.concurrent.CountDownLatch entered;
    static volatile java.util.concurrent.CountDownLatch release;

    @Override
    public void refresh() {
      java.util.concurrent.CountDownLatch gate = release;
      if (gate == null) {
        return;
      }
      entered.countDown();
      try {
        gate.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void retireImportedRulesForManualSelection(
        BoardHistoryList history, BoardHistoryList.SessionRulesTarget target) {}

    @Override
    public void synchronizeManualRulesAfterSelection(
        BoardHistoryList history,
        BoardHistoryList.SessionRulesTarget target,
        BoardHistoryList.ManualRulesIntent manualIntent,
        Leelaz primary,
        long primaryGeneration) {}
  }

  private static void awaitHidden(SetKataRules dialog) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      boolean[] visible = new boolean[1];
      SwingUtilities.invokeAndWait(() -> visible[0] = dialog.isVisible());
      if (!visible[0]) {
        return;
      }
      Thread.sleep(20L);
    }
  }

  private static void awaitStatus(SetKataRules dialog, String fragment) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      if (readOnEdt(() -> dialog.statusText().contains(fragment))) {
        return;
      }
      Thread.sleep(20L);
    }
    throw new AssertionError("status was: " + readOnEdt(dialog::statusText));
  }

  private static void closeDialog(SetKataRules dialog) {
    runOnEdt(
        () ->
            dialog.dispatchEvent(
                new java.awt.event.WindowEvent(dialog, java.awt.event.WindowEvent.WINDOW_CLOSING)));
  }

  private static int commandIdFor(String commands, String command) {
    String[] lines = commands.split("\\R");
    for (int index = lines.length - 1; index >= 0; index--) {
      String trimmed = lines[index].trim();
      int split = trimmed.indexOf(' ');
      if (split <= 0) {
        continue;
      }
      if (trimmed.substring(split + 1).startsWith(command)) {
        return Integer.parseInt(trimmed.substring(0, split));
      }
    }
    throw new AssertionError("missing " + command + " in " + commands);
  }

  private static void dispatch(Leelaz engine, String line) throws Exception {
    java.lang.reflect.Method method =
        Leelaz.class.getDeclaredMethod("dispatchReaderLineForTest", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void installOutput(Leelaz engine, ByteArrayOutputStream stream) throws Exception {
    java.lang.reflect.Method method =
        Leelaz.class.getDeclaredMethod(
            "installFreshCommandOutputForTest", java.io.OutputStream.class);
    method.setAccessible(true);
    method.invoke(engine, stream);
  }

  private static void resetEngineGame() throws Exception {
    java.lang.reflect.Method method =
        EngineManager.class.getDeclaredMethod("resetEngineGameTransactionStateForTest");
    method.setAccessible(true);
    method.invoke(null);
  }

  private static boolean rendersColor(JComponent component, Color expected) {
    BufferedImage image =
        new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_ARGB);
    java.awt.Graphics2D graphics = image.createGraphics();
    try {
      component.paintAll(graphics);
    } finally {
      graphics.dispose();
    }
    int expectedRgb = expected.getRGB();
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if (image.getRGB(x, y) == expectedRgb) return true;
      }
    }
    return false;
  }

  private static final class Fixture implements AutoCloseable {
    private final java.util.Set<java.awt.Window> previousWindows =
        readOnEdt(
            () -> java.util.Set.copyOf(java.util.Arrays.asList(java.awt.Window.getWindows())));
    private final Config previousConfig;
    private final Leelaz previousLeelaz;
    private final Leelaz previousSecondary;
    private final GtpConsolePane previousConsole;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final EngineManager previousManager;
    private final ResourceBundle previousBundle;
    final Leelaz engine;
    final ByteArrayOutputStream output;

    private Fixture() throws Exception {
      previousConfig = Lizzie.config;
      previousLeelaz = Lizzie.leelaz;
      previousSecondary = Lizzie.leelaz2;
      previousConsole = Lizzie.gtpConsole;
      previousBoard = Lizzie.board;
      previousFrame = Lizzie.frame;
      previousManager = Lizzie.engineManager;
      previousBundle = Lizzie.resourceBundle;
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Lizzie.config = ConfigTestHelper.createForTests(Files.createTempDirectory("set-kata-rules"));
      Lizzie.config.uiConfig = new JSONObject();
      Lizzie.config.leelazConfig = new JSONObject();
      Lizzie.config.config =
          new JSONObject()
              .put("ui", Lizzie.config.uiConfig)
              .put("leelaz", Lizzie.config.leelazConfig);
      Lizzie.gtpConsole = null;
      Lizzie.frame = null;
      resetEngineGame();
      output = new ByteArrayOutputStream();
      engine = liveEngine(output);
      Lizzie.leelaz = engine;
      Lizzie.board = new Board();
    }

    static Fixture ordinary() throws Exception {
      return readOnEdt(Fixture::new);
    }

    private static Leelaz liveEngine(ByteArrayOutputStream stream) throws Exception {
      Leelaz created = new Leelaz("");
      installOutput(created, stream);
      created.started = true;
      created.isLoaded = true;
      created.isKatago = true;
      created.commandLists.addAll(List.of("kata-set-rules", "kata-get-rules"));
      Field ready = Leelaz.class.getDeclaredField("endGetCommandList");
      ready.setAccessible(true);
      ready.setBoolean(created, true);
      return created;
    }

    @Override
    public void close() {
      try {
        SwingUtilities.invokeAndWait(
            () -> {
              for (java.awt.Window window : java.awt.Window.getWindows()) {
                if (window instanceof SetKataRules dialog && !previousWindows.contains(window)) {
                  closeDialog(dialog);
                  window.dispose();
                }
              }
            });
      } catch (Exception failure) {
        throw new AssertionError("Could not dispose test rule windows", failure);
      } finally {
        runOnEdt(this::restoreState);
      }
    }

    private void restoreState() {
      try {
        resetEngineGame();
      } catch (Exception ignored) {
        // Restore global Lizzie fields even if engine-game reset fails.
      }
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.leelaz2 = previousSecondary;
      Lizzie.gtpConsole = previousConsole;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      Lizzie.engineManager = previousManager;
      Lizzie.resourceBundle = previousBundle;
    }
  }
}
