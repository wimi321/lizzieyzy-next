package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.util.OgsGameClient;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class OgsKifuBrowserTest {
  private static final ResourceBundle EN =
      ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);

  @Test
  void searchesPagesAndOpensSelectedGameThroughDeferredCompletion() throws Exception {
    FakeClient client = new FakeClient();
    AtomicReference<OgsKifuBrowser> ref = new AtomicReference<>();
    CompletableFuture<Consumer<Boolean>> completion = new CompletableFuture<>();
    CompletableFuture<Integer> after = new CompletableFuture<>();
    onEdt(
        () ->
            ref.set(
                new OgsKifuBrowser(
                    EN,
                    client,
                    (sgf, done) -> {
                      assertTrue(SwingUtilities.isEventDispatchThread());
                      assertEquals("(;SZ[19])", sgf);
                      completion.complete(done);
                    },
                    after::complete,
                    0)));
    OgsKifuBrowser browser = ref.get();
    search(browser, "alice");
    onEdt(
        () -> {
          assertEquals("Black", browser.table.getValueAt(0, 1));
          assertTrue(browser.next.isEnabled());
          assertFalse(browser.previous.isEnabled());
          assertEquals("alice", browser.recent.getItemAt(0));
        });
    CountDownLatch secondPage = rowsChanged(browser, "White page 2");
    onEdt(() -> browser.next.doClick());
    await(secondPage);
    onEdt(
        () -> {
          assertFalse(browser.next.isEnabled());
          assertTrue(browser.previous.isEnabled());
          browser.after.setSelectedIndex(2);
          browser.open.doClick(0);
          browser.openSelected(); // Duplicate clicks cannot launch another download.
        });
    Consumer<Boolean> done = completion.get(5, TimeUnit.SECONDS);
    onEdt(
        () -> {
          assertFalse(browser.open.isEnabled());
          assertFalse(after.isDone());
          done.accept(true);
        });
    assertEquals(2, after.get(5, TimeUnit.SECONDS));
    assertEquals(202, client.downloaded);
    assertEquals(1, client.downloads.get());
    onEdt(
        () -> {
          assertTrue(browser.open.isEnabled());
          browser.cancelPending();
        });
  }

  @Test
  void failedSearchClearsOldRowsAndAllowsRetry() throws Exception {
    FakeClient client = new FakeClient();
    OgsKifuBrowser browser = browser(client, (sgf, done) -> fail("Must not load"));
    search(browser, "alice");
    CountDownLatch failed = statusChanged(browser, EN.getString("OgsKifuDownload.failed"));
    client.failSearch = true;
    onEdt(
        () -> {
          browser.query.setText("other");
          browser.search.doClick(0);
        });
    await(failed);
    onEdt(
        () -> {
          assertEquals(0, browser.table.getRowCount());
          assertTrue(browser.search.isEnabled());
          assertFalse(browser.open.isEnabled());
        });
    client.failSearch = false;
    search(browser, "alice");
    onEdt(browser::cancelPending);
  }

  @Test
  void closingWindowIgnoresLateDownloadAndDoesNotTouchBoard() throws Exception {
    FakeClient client = new FakeClient();
    client.downloadGate = new CompletableFuture<>();
    AtomicInteger loads = new AtomicInteger();
    OgsKifuBrowser browser = browser(client, (sgf, done) -> loads.incrementAndGet());
    search(browser, "alice");
    onEdt(() -> browser.open.doClick());
    await(client.downloadStarted);
    onEdt(browser::cancelPending);
    client.downloadGate.complete(null);
    await(client.downloadFinished);
    // Cancellation completes SwingWorker on the EDT; flush that queue as well.
    onEdt(
        () -> {
          assertEquals(0, loads.get());
          assertTrue(browser.search.isEnabled());
        });
  }

  @Test
  void emptyHistoryAndUnknownPlayerHaveExplanations() throws Exception {
    FakeClient client = new FakeClient();
    OgsKifuBrowser browser = browser(client, (sgf, done) -> fail("Must not load"));
    client.empty = true;
    CountDownLatch empty = statusChanged(browser, EN.getString("OgsKifuDownload.empty"));
    onEdt(
        () -> {
          browser.query.setText("alice");
          browser.search.doClick(0);
        });
    await(empty);
    client.unknown = true;
    CountDownLatch unknown = statusChanged(browser, EN.getString("OgsKifuDownload.noPlayer"));
    onEdt(() -> browser.search.doClick());
    await(unknown);
    onEdt(
        () -> {
          assertFalse(browser.open.isEnabled());
          browser.cancelPending();
        });
  }

  @Test
  void toolbarButtonsShareStylingAndDispatchTheirOwnActions() throws Exception {
    onEdt(
        () -> {
          AtomicInteger calls = new AtomicInteger();
          for (String source : List.of("fox", "tencent", "ogs")) {
            var button = KifuBrowseControls.button(EN, source, calls::incrementAndGet);
            assertEquals(EN.getString("Menu." + source + "KifuButton"), button.getText());
            assertEquals(
                "menu." + source + "Kifu",
                button.getClientProperty(AutoAnalyzeMenu.ACTION_PROPERTY));
            assertTrue(
                button.getPreferredSize().width
                    >= button.getFontMetrics(button.getFont()).stringWidth(button.getText()));
            button.doClick(0);
          }
          assertEquals(3, calls.get());
        });
  }

  @Test
  void browserControlsFitAndRenderAtNormalWindowSize() throws Exception {
    OgsKifuBrowser browser = browser(new FakeClient(), (sgf, done) -> {});
    search(browser, "alice");
    onEdt(
        () -> {
          JPanel root = new JPanel(new BorderLayout());
          JPanel toolbar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
          for (String source : List.of("fox", "tencent", "ogs"))
            toolbar.add(KifuBrowseControls.button(EN, source, () -> {}));
          root.add(toolbar, BorderLayout.NORTH);
          root.add(browser, BorderLayout.CENTER);
          root.setSize(980, 680);
          layout(root);
          for (Component control :
              List.of(browser.query, browser.search, browser.recent, browser.after, browser.open)) {
            assertTrue(control.getWidth() > 0);
            assertTrue(
                control.getX() + control.getWidth() <= control.getParent().getWidth(),
                control.toString());
          }
          BufferedImage image = new BufferedImage(980, 680, BufferedImage.TYPE_INT_RGB);
          var graphics = image.createGraphics();
          root.printAll(graphics);
          graphics.dispose();
          try {
            Files.createDirectories(Path.of("target"));
            ImageIO.write(image, "png", Path.of("target/ogs-browser.png").toFile());
          } catch (IOException e) {
            throw new AssertionError(e);
          }
          browser.cancelPending();
        });
  }

  private static void layout(Container container) {
    container.doLayout();
    for (Component child : container.getComponents()) if (child instanceof Container c) layout(c);
  }

  private static OgsKifuBrowser browser(FakeClient client, OgsKifuBrowser.Loader loader)
      throws Exception {
    AtomicReference<OgsKifuBrowser> ref = new AtomicReference<>();
    onEdt(() -> ref.set(new OgsKifuBrowser(EN, client, loader, action -> {}, 0)));
    return ref.get();
  }

  private static void search(OgsKifuBrowser browser, String user) throws Exception {
    CountDownLatch rows = rowsChanged(browser, "White");
    onEdt(
        () -> {
          browser.query.setText(user);
          browser.search.doClick(0);
        });
    await(rows);
    onEdt(() -> {});
  }

  private static CountDownLatch rowsChanged(OgsKifuBrowser browser, String white) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    onEdt(
        () ->
            browser
                .table
                .getModel()
                .addTableModelListener(
                    event -> {
                      if (browser.table.getRowCount() > 0
                          && white.equals(browser.table.getValueAt(0, 2))) latch.countDown();
                    }));
    return latch;
  }

  private static CountDownLatch statusChanged(OgsKifuBrowser browser, String text)
      throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    onEdt(
        () ->
            browser.status.addPropertyChangeListener(
                "text",
                event -> {
                  if (text.equals(event.getNewValue())) latch.countDown();
                }));
    return latch;
  }

  private static void await(CountDownLatch latch) throws Exception {
    assertTrue(latch.await(5, TimeUnit.SECONDS));
  }

  private static void onEdt(Runnable action) throws Exception {
    SwingUtilities.invokeAndWait(action);
  }

  private static class FakeClient extends OgsGameClient {
    volatile boolean failSearch;
    volatile boolean unknown;
    volatile boolean empty;
    volatile long downloaded;
    CompletableFuture<Void> downloadGate;
    final AtomicInteger downloads = new AtomicInteger();
    final CountDownLatch downloadStarted = new CountDownLatch(1);
    final CountDownLatch downloadFinished = new CountDownLatch(1);

    @Override
    public Player findPlayer(String input) throws IOException {
      assertFalse(SwingUtilities.isEventDispatchThread());
      if (failSearch) throw new IOException("offline");
      return unknown ? null : new Player(1, input);
    }

    @Override
    public GamePage games(Player player, int page) {
      assertFalse(SwingUtilities.isEventDispatchThread());
      return new GamePage(
          empty
              ? List.of()
              : List.of(
                  new Game(
                      page == 1 ? 101 : 202,
                      "2026-01-02T12:00:00Z",
                      "Black",
                      page == 1 ? "White" : "White page 2",
                      "B+R",
                      "19×19")),
          page,
          empty ? 1 : 2);
    }

    @Override
    public String sgf(long id) {
      assertFalse(SwingUtilities.isEventDispatchThread());
      downloaded = id;
      downloads.incrementAndGet();
      downloadStarted.countDown();
      if (downloadGate != null) downloadGate.join();
      downloadFinished.countDown();
      return "(;SZ[19])";
    }
  }
}
