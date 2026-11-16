package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class OgsKifuImportTest {
  @Test
  void usesDownloadedKifuLoaderAndWaitsForActualCompletion() throws Exception {
    java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    RecordingFrame frame =
        (RecordingFrame) ((sun.misc.Unsafe) field.get(null)).allocateInstance(RecordingFrame.class);
    SwingUtilities.invokeAndWait(
        () -> {
          OgsKifuImport.loadDownloaded(frame, "(;SZ[19]KM[6.5])");
          assertEquals("(;SZ[19]KM[6.5])", frame.sgf);
          assertFalse(frame.finished);
          frame.completion.accept(true);
          assertTrue(frame.finished);
          assertEquals("KifuLoad.refreshing", frame.progress);
          frame.finished = false;
          OgsKifuImport.loadDownloaded(frame, "invalid");
          frame.completion.accept(false);
          assertTrue(frame.failed);
          assertFalse(frame.finished);
        });
  }

  private static class RecordingFrame extends LizzieFrame {
    String sgf;
    String progress;
    boolean finished;
    boolean failed;
    java.util.function.Consumer<Boolean> completion;

    @Override
    public String kifuLoadText(String key) {
      return key;
    }

    @Override
    public void updateKifuLoad(String message) {
      progress = message;
    }

    @Override
    public void finishKifuLoad() {
      finished = true;
    }

    @Override
    public void failKifuLoad(String message) {
      failed = true;
    }

    @Override
    public boolean loadDownloadedSgfString(
        String content,
        int delay,
        boolean readKomi,
        boolean reset,
        Runnable after,
        java.util.function.Consumer<Boolean> callback) {
      assertTrue(SwingUtilities.isEventDispatchThread());
      assertTrue(readKomi);
      assertEquals(0, delay);
      assertFalse(reset);
      sgf = content;
      completion = callback;
      return true; // Deferred loading is not completion.
    }
  }

  @Test
  void downloadsOffEdtAndDeliversOnEdt() throws Exception {
    CompletableFuture<String> result = new CompletableFuture<>();
    AtomicBoolean background = new AtomicBoolean();
    AtomicBoolean edt = new AtomicBoolean();
    SwingUtilities.invokeAndWait(
        () ->
            OgsKifuImport.download(
                () -> {
                  background.set(!SwingUtilities.isEventDispatchThread());
                  return "(;SZ[19])";
                },
                sgf -> {
                  edt.set(SwingUtilities.isEventDispatchThread());
                  result.complete(sgf);
                },
                result::completeExceptionally));
    assertEquals("(;SZ[19])", result.get(5, TimeUnit.SECONDS));
    assertTrue(background.get());
    assertTrue(edt.get());
  }

  @Test
  void failedDownloadDoesNotInvokeBoardLoader() throws Exception {
    IOException failure = new IOException("HTTP 404");
    CompletableFuture<Throwable> result = new CompletableFuture<>();
    AtomicBoolean loaded = new AtomicBoolean();
    AtomicBoolean edt = new AtomicBoolean();
    SwingUtilities.invokeAndWait(
        () ->
            OgsKifuImport.download(
                () -> {
                  throw failure;
                },
                sgf -> loaded.set(true),
                error -> {
                  edt.set(SwingUtilities.isEventDispatchThread());
                  result.complete(error);
                }));
    assertSame(failure, result.get(5, TimeUnit.SECONDS));
    assertFalse(loaded.get());
    assertTrue(edt.get());
  }
}
