package featurecat.lizzie.gui;

import featurecat.lizzie.util.OgsSgfClient;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.SwingWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One-shot OGS imports use the same downloaded-kifu completion path as Fox and Tencent. */
final class OgsKifuImport {
  private static final Logger LOG = LoggerFactory.getLogger(OgsKifuImport.class);

  private OgsKifuImport() {}

  static boolean tryOpen(OnlineDialog dialog, String input) {
    Optional<URI> uri = OgsSgfClient.sgfUri(input);
    if (uri.isEmpty()) return false;
    LizzieFrame frame = (LizzieFrame) dialog.getOwner();
    dialog.stopSync();
    dialog.setVisible(false);
    frame.beginKifuLoad(frame.kifuLoadText("KifuLoad.sharedDownloading"));
    download(
        () -> OgsSgfClient.download(uri.get()),
        sgf -> loadDownloaded(frame, sgf),
        failure -> {
          LOG.warn("OGS game download failed", failure);
          frame.failKifuLoad(frame.kifuLoadText("OgsKifuImport.failed"));
        });
    return true;
  }

  static void loadDownloaded(LizzieFrame frame, String sgf) {
    loadDownloaded(frame, sgf, loaded -> {});
  }

  static void loadDownloaded(LizzieFrame frame, String sgf, Consumer<Boolean> completion) {
    frame.updateKifuLoad(frame.kifuLoadText("KifuLoad.parsing"));
    frame.loadDownloadedSgfString(
        sgf,
        0,
        true,
        false,
        null,
        loaded -> {
          if (loaded) {
            frame.updateKifuLoad(frame.kifuLoadText("KifuLoad.refreshing"));
            frame.finishKifuLoad();
          } else {
            // The common loader already explains parse/load failures.
            frame.failKifuLoad(null);
          }
          completion.accept(loaded);
        });
  }

  static <T> SwingWorker<T, Void> download(
      Callable<T> request, Consumer<T> loaded, Consumer<Throwable> failed) {
    SwingWorker<T, Void> worker =
        new SwingWorker<>() {
          @Override
          protected T doInBackground() throws Exception {
            return request.call();
          }

          @Override
          protected void done() {
            try {
              loaded.accept(get());
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              failed.accept(e);
            } catch (ExecutionException e) {
              failed.accept(e.getCause());
            } catch (RuntimeException e) {
              failed.accept(e);
            }
          }
        };
    worker.execute();
    return worker;
  }
}
