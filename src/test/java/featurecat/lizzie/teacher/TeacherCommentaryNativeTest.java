package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real Swing surfaces with isolated test data; no user's credentials or paid requests. */
class TeacherCommentaryNativeTest {
  @TempDir Path directory;

  @Test
  void firstUseHasOneClearConnectionActionEvenWithoutAnalysis() throws Exception {
    try (Flow flow = new Flow(true)) {
      onEdt(
          () -> {
            flow.root.get().getData().bestMoves = List.of();
            assertEquals(
                TeacherStrings.get("Teacher.action.connect", "Connect AI"),
                flow.view.start().getText());
            var dismiss = new javax.swing.Timer(100, null);
            AtomicInteger opened = new AtomicInteger();
            dismiss.addActionListener(
                event -> {
                  for (Window window : Window.getWindows()) {
                    if (window instanceof TeacherSettingsDialog && window.isShowing()) {
                      opened.incrementAndGet();
                      window.dispose();
                      dismiss.stop();
                    }
                  }
                });
            capture(flow.dialog, "10-first-use");
            dismiss.start();
            try {
              flow.view.start().doClick();
            } finally {
              dismiss.stop();
            }
            assertEquals(1, opened.get());
            assertEquals(0, flow.calls.get());
          });
    }
  }

  @Test
  void missingAnalysisNeverSendsAnUngroundedRequest() throws Exception {
    try (Flow flow = new Flow()) {
      onEdt(
          () -> {
            flow.root.get().getData().bestMoves = List.of();
            flow.view.start().doClick();
            assertEquals(0, flow.calls.get());
            assertTrue(
                flow.view
                    .status()
                    .getText()
                    .contains(TeacherStrings.get("Teacher.status.needsAnalysis", "Analyze first")));
            capture(flow.dialog, "11-needs-analysis");
          });
    }
  }

  @Test
  void loadingAnotherGameWhileGeneratingCancelsAndRejectsLateCallbacks() throws Exception {
    try (Flow flow = new Flow()) {
      BoardHistoryNode oldRoot = flow.root.get();
      flow.delay = new CountDownLatch(1);
      onEdt(() -> flow.view.start().doClick());
      await(() -> flow.calls.get() == 1);
      onEdt(
          () -> {
            flow.root.set(node(0));
            flow.current.set(flow.root.get());
          });
      await(() -> !flow.view.stop().isEnabled());
      flow.delay.countDown();
      Thread.sleep(300);
      onEdt(
          () -> {
            assertFalse(flow.view.output().getText().contains("reply 1"));
            assertEquals("original comment", oldRoot.getData().comment);
            assertEquals("original comment", flow.root.get().getData().comment);
            assertEquals(1, flow.calls.get());
          });
    }
  }

  @Test
  void modeSelectionDoesNotSpendRequestsAndTypedRangeIsCommitted() throws Exception {
    try (Flow flow = new Flow()) {
      onEdt(
          () -> {
            flow.view.explainRange().doClick();
            flow.view.explainWhole().doClick();
            flow.view.explainRange().doClick();
            assertEquals(0, flow.calls.get());
            ((javax.swing.JSpinner.DefaultEditor) flow.view.rangeStart().getEditor())
                .getTextField()
                .setText("25");
            ((javax.swing.JSpinner.DefaultEditor) flow.view.rangeEnd().getEditor())
                .getTextField()
                .setText("25");
            capture(flow.dialog, "03-range-before-start");
            flow.view.start().doClick();
            flow.view.start().doClick();
          });
      await(() -> flow.calls.get() == 1 && flow.view.start().isEnabled());
      assertTrue(flow.body.get().contains("selected key positions: 1"), flow.body.get());
      onEdt(
          () -> {
            assertEquals(25, flow.view.rangeStart().getValue());
            capture(flow.dialog, "04-completed");
          });
    }
  }

  @Test
  void followUpToSavedCommentaryAlsoKeepsTheOriginalPosition() throws Exception {
    try (Flow flow = new Flow(false, true)) {
      onEdt(
          () -> {
            flow.current.set(flow.other);
            flow.view.followUp().setText("Explain the saved comment");
            flow.view.ask().doClick();
          });
      await(() -> flow.calls.get() == 1 && flow.view.start().isEnabled());
      assertEquals("original comment", flow.other.getData().comment);
      assertTrue(
          TeacherCommentCodec.extract(flow.root.get().getData().comment)
              .orElse("")
              .contains("reply 1"));
      assertTrue(flow.body.get().contains("Saved explanation"));
    }
  }

  @Test
  void followUpStaysWithExplainedNodeWhenUserBrowsesAnotherMove() throws Exception {
    try (Flow flow = new Flow()) {
      onEdt(() -> flow.view.start().doClick());
      await(() -> flow.calls.get() == 1 && flow.view.start().isEnabled());
      String originalOtherComment = flow.other.getData().comment;
      onEdt(
          () -> {
            flow.current.set(flow.other);
            flow.view.followUp().setText("Why this move?");
            flow.view.ask().doClick();
          });
      await(() -> flow.calls.get() == 2 && flow.view.start().isEnabled());
      assertEquals(originalOtherComment, flow.other.getData().comment);
      assertTrue(
          TeacherCommentCodec.extract(flow.root.get().getData().comment)
              .orElse("")
              .contains("reply 2"));
      assertTrue(flow.body.get().contains("Why this move?"));
      onEdt(() -> capture(flow.dialog, "05-follow-up-original-position"));
    }
  }

  @Test
  void interruptedReplyPreservesQuestionAndNeverWritesPartialComment() throws Exception {
    try (Flow flow = new Flow()) {
      flow.truncate = true;
      String previous = flow.root.get().getData().comment;
      onEdt(
          () -> {
            flow.view.followUp().setText("How do I protect this group?");
            flow.view.ask().doClick();
          });
      await(() -> flow.calls.get() == 1 && flow.view.start().isEnabled());
      assertEquals(previous, flow.root.get().getData().comment);
      onEdt(
          () -> {
            assertEquals("How do I protect this group?", flow.view.followUp().getText());
            assertTrue(
                flow.view
                    .status()
                    .getText()
                    .contains(TeacherStrings.get("Teacher.error.incomplete", "interrupted")));
            capture(flow.dialog, "06-interrupted-preserved-question");
          });
      flow.truncate = false;
      onEdt(() -> flow.view.ask().doClick());
      await(() -> flow.calls.get() == 2 && flow.view.start().isEnabled());
      onEdt(
          () -> {
            assertEquals("", flow.view.followUp().getText());
            assertFalse(
                flow.body.get().contains("Test reply 1"),
                "A partial answer is not completed conversation history");
            assertTrue(
                flow.view
                    .status()
                    .getText()
                    .contains(TeacherStrings.get("Teacher.status.completedSaved", "saved")));
          });
    }
  }

  @Test
  void cancellingConnectionSettingsDoesNotEraseAQuestion() throws Exception {
    try (Flow flow = new Flow()) {
      flow.settings.selectProvider(TeacherSettings.Provider.UNSELECTED);
      onEdt(
          () -> {
            var dismiss = new javax.swing.Timer(100, null);
            dismiss.addActionListener(
                event -> {
                  for (Window window : Window.getWindows()) {
                    if (window instanceof TeacherSettingsDialog && window.isShowing()) {
                      capture((JDialog) window, "07-connection-before-cancel");
                      window.dispose();
                      dismiss.stop();
                    }
                  }
                });
            flow.view.followUp().setText("Explain my mistake");
            dismiss.start();
            try {
              flow.view.ask().doClick();
            } finally {
              dismiss.stop();
            }
            assertEquals("Explain my mistake", flow.view.followUp().getText());
            assertEquals(0, flow.calls.get());
          });
    }
  }

  @Test
  void stopAndNewGameRejectLateOutputWithoutSavingOrRetrying() throws Exception {
    try (Flow flow = new Flow()) {
      flow.delay = new CountDownLatch(1);
      onEdt(
          () -> {
            flow.view.followUp().setText("Explain this fight");
            flow.view.ask().doClick();
          });
      await(() -> flow.calls.get() == 1);
      onEdt(
          () -> {
            flow.view.stop().doClick();
            assertEquals("Explain this fight", flow.view.followUp().getText());
            assertTrue(flow.view.start().isEnabled());
            capture(flow.dialog, "08-stopped");
            var newRoot = node(0);
            flow.root.set(newRoot);
            flow.current.set(newRoot);
          });
      flow.delay.countDown();
      await(() -> flow.view.followUp().getText().isEmpty());
      assertEquals("original comment", flow.root.get().getData().comment);
      assertEquals(1, flow.calls.get());
      onEdt(
          () -> {
            assertFalse(flow.view.output().getText().contains("reply 1"));
            capture(flow.dialog, "09-new-game");
          });
    }
  }

  @Test
  void controlsFitAllLocalesAtMinimumSize() throws Exception {
    try (Flow flow = new Flow()) {
      onEdt(
          () -> {
            for (String tag : List.of("zh-CN", "zh-TW", "zh-HK", "en-US", "ja-JP", "ko", "th-TH")) {
              Lizzie.resourceBundle =
                  ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag(tag));
              JDialog dialog = new JDialog();
              try {
                var view = new TeacherDialogView();
                view.setChatGptUsageVisible(true);
                dialog.setContentPane(view);
                dialog.setSize(760, 540);
                dialog.setVisible(true);
                for (var mode : TeacherDialogView.Mode.values()) {
                  view.selectMode(mode);
                  dialog.validate();
                  for (var button :
                      List.of(
                          view.start(),
                          view.stop(),
                          view.ask(),
                          view.settingsButton(),
                          view.manageChatGptUsage())) {
                    int width =
                        button.getWidth() - button.getInsets().left - button.getInsets().right;
                    assertTrue(
                        width
                            >= button
                                .getFontMetrics(button.getFont())
                                .stringWidth(button.getText()),
                        tag + " " + button.getText());
                    var bounds =
                        SwingUtilities.convertRectangle(
                            button.getParent(), button.getBounds(), view);
                    assertTrue(bounds.x >= 0 && bounds.x + bounds.width <= view.getWidth(), tag);
                    assertTrue(bounds.y >= 0 && bounds.y + bounds.height <= view.getHeight(), tag);
                  }
                  capture(dialog, "locale-" + tag + "-" + mode);
                }
              } finally {
                dialog.dispose();
              }
            }
          });
    }
  }

  private final class Flow implements AutoCloseable {
    final ResourceBundle previous;
    final HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<String> body = new AtomicReference<>("");
    final AtomicReference<BoardHistoryNode> root = new AtomicReference<>(node(24));
    final BoardHistoryNode other = node(25);
    final AtomicReference<BoardHistoryNode> current = new AtomicReference<>(root.get());
    final TeacherSettings settings;
    TeacherDialog dialog;
    TeacherDialogView view;
    volatile boolean truncate;
    volatile CountDownLatch delay;

    Flow() throws Exception {
      this(false);
    }

    Flow(boolean unconfigured) throws Exception {
      this(unconfigured, false);
    }

    Flow(boolean unconfigured, boolean savedCommentary) throws Exception {
      assumeTrue(
          Boolean.getBoolean("lizzie.test.commentaryNative") && !GraphicsEnvironment.isHeadless());
      previous = Lizzie.resourceBundle;
      if (savedCommentary)
        root.get().getData().comment =
            TeacherCommentCodec.upsert("original comment", "Saved explanation", "test-model");
      if (unconfigured) root.get().getData().bestMoves = List.of();
      root.get().variations.add(other);
      root.get().setPreviousForChild(other);
      BoardHistoryNode end = node(26);
      other.variations.add(end);
      other.setPreviousForChild(end);
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/v1/chat/completions",
          exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int count = calls.incrementAndGet();
            if (delay != null)
              try {
                delay.await(4, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            String data =
                "data: {\"choices\":[{\"delta\":{\"content\":\"### Test reply "
                    + count
                    + "\\nProtect your group before attacking.\"}}]}\n\n";
            if (!truncate) data += "data: [DONE]\n\n";
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            try {
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
            } finally {
              exchange.close();
            }
          });
      server.start();
      var store = new ChatGptIntegrationTest.MemoryStore();
      settings =
          new TeacherSettings(
              directory.resolve("teacher.properties"),
              store,
              new ChatGptSessions(directory.resolve("session"), store, new ChatGptHttp()));
      if (!unconfigured) {
        settings.save(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
            "test-model",
            "test-canary".toCharArray(),
            false);
        settings.selectProvider(TeacherSettings.Provider.API_KEY);
      }
      onEdt(
          () -> {
            Lizzie.resourceBundle =
                ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
            dialog = new TeacherDialog(null, settings, current::get, root::get);
            view = (TeacherDialogView) dialog.getContentPane();
            dialog.setVisible(true);
          });
      await(() -> view.start().isEnabled());
    }

    @Override
    public void close() throws Exception {
      if (delay != null) delay.countDown();
      onEdt(
          () -> {
            dialog.dispose();
            Lizzie.resourceBundle = previous;
          });
      server.stop(0);
    }
  }

  private static BoardHistoryNode node(int number) {
    var data = BoardData.empty(19, 19);
    data.moveNumber = number;
    data.comment = "original comment";
    var move = new MoveData();
    move.coordinate = "D4";
    move.playouts = 1000;
    move.winrate = 55;
    move.scoreMean = 1;
    move.variation = List.of("D4", "Q16");
    data.bestMoves = List.of(move);
    return new BoardHistoryNode(data);
  }

  private static void onEdt(Runnable action) throws Exception {
    SwingUtilities.invokeAndWait(action);
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) {
      boolean[] ready = {false};
      onEdt(() -> ready[0] = condition.getAsBoolean());
      if (ready[0]) return;
      Thread.sleep(30);
    }
    fail("Timed out waiting for UI state");
  }

  @Test
  void captureCommentarySurface() throws Exception {
    assumeTrue(
        Boolean.getBoolean("lizzie.test.commentaryNative") && !GraphicsEnvironment.isHeadless());
    var previous = Lizzie.resourceBundle;
    SwingUtilities.invokeAndWait(
        () -> {
          Lizzie.resourceBundle =
              ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
          var dialog = new JDialog();
          var view = new TeacherDialogView();
          try {
            dialog.setContentPane(view);
            dialog.setSize(900, 680);
            dialog.setLocationRelativeTo(null);
            dialog.setVisible(true);
            view.setCurrentMove(24);
            view.setStatus(
                TeacherStrings.get("Teacher.empty.ready", "Ready"),
                TeacherDialogView.StatusTone.NEUTRAL);
            view.setModelStatus("ChatGPT · test-model");
            dialog.validate();
            capture(dialog, "01-ready");
            view.selectMode(TeacherDialogView.Mode.RANGE);
            dialog.validate();
            capture(dialog, "02-range");
          } finally {
            dialog.dispose();
            Lizzie.resourceBundle = previous;
          }
        });
  }

  static void capture(JDialog dialog, String name) {
    String output = System.getProperty("lizzie.test.commentaryScreenshots");
    if (output == null) return;
    try {
      Files.createDirectories(Path.of(output));
      Container content = dialog.getContentPane();
      BufferedImage image =
          new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
      var graphics = image.createGraphics();
      content.paintAll(graphics);
      graphics.dispose();
      ImageIO.write(image, "png", Path.of(output, name + ".png").toFile());
    } catch (Exception failed) {
      throw new AssertionError(failed);
    }
  }
}
