package featurecat.lizzie.gui;

import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.ZhiziEngineConnections;
import java.awt.BorderLayout;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-side connection visibility independent of the currently selected engine or account. */
final class ZhiziConnectionControls extends JPanel {
  private static final Logger LOG = LoggerFactory.getLogger(ZhiziConnectionControls.class);

  interface Backend {
    ZhiziEngineConnections.State state();

    ZhiziEngineConnections.State disconnect();
  }

  interface DisconnectRunner {
    void run(Backend target, BiConsumer<ZhiziEngineConnections.State, Throwable> completion);
  }

  private final DisconnectRunner runner;
  private final Supplier<Backend> backend;
  private final Runnable cancelPendingSwitch;
  private final BooleanSupplier pendingSwitch;
  private final Consumer<Boolean> busy;
  private final BiFunction<String, String, String> text;
  private final JLabel status = new JLabel();
  private final JButton disconnect = new JButton();
  private final Timer refresh;
  private boolean closing;
  private boolean disposed;
  private String notice;
  private ZhiziEngineConnections.State lastState;

  ZhiziConnectionControls(
      Supplier<Backend> backend,
      Runnable cancelPendingSwitch,
      BooleanSupplier pendingSwitch,
      Consumer<Boolean> busy,
      BiFunction<String, String, String> text) {
    this(
        backend,
        cancelPendingSwitch,
        pendingSwitch,
        busy,
        text,
        ZhiziConnectionControls::runBackground);
  }

  ZhiziConnectionControls(
      Supplier<Backend> backend,
      Runnable cancelPendingSwitch,
      BooleanSupplier pendingSwitch,
      Consumer<Boolean> busy,
      BiFunction<String, String, String> text,
      DisconnectRunner runner) {
    super(new BorderLayout(12, 4));
    this.runner = runner;
    this.backend = backend;
    this.cancelPendingSwitch = cancelPendingSwitch;
    this.pendingSwitch = pendingSwitch;
    this.busy = busy;
    this.text = text;
    setOpaque(false);
    setName("zhiziConnectionControls");
    disconnect.setName("disconnectZhizi");
    disconnect.setText(text.apply("RemoteCompute.disconnectZhizi", "Disconnect Zhizi engines"));
    disconnect.addActionListener(event -> disconnect());
    JPanel row = new JPanel(new BorderLayout(12, 0));
    row.setOpaque(false);
    row.add(status, BorderLayout.CENTER);
    row.add(disconnect, BorderLayout.EAST);
    add(row, BorderLayout.NORTH);
    JTextArea hint =
        new JTextArea(
            text.apply(
                "RemoteCompute.zhiziSession.billing",
                "Only main, comparison and retained engine connections are covered. Independent analysis may remain connected. Switching engines or signing out can retain connections; client closure does not confirm server billing has ended."));
    hint.setEditable(false);
    hint.setLineWrap(true);
    hint.setWrapStyleWord(true);
    hint.setOpaque(false);
    hint.setRows(3);
    hint.setFont(status.getFont());
    add(hint, BorderLayout.CENTER);
    refresh = new Timer(500, event -> refresh());
    refresh();
  }

  @Override
  public void addNotify() {
    super.addNotify();
    if (!disposed) refresh.start();
  }

  @Override
  public void removeNotify() {
    refresh.stop();
    super.removeNotify();
  }

  void close() {
    disposed = true;
    refresh.stop();
    // Do not cancel the worker: an accepted disconnect must finish even after dismissal.
  }

  void refresh() {
    if (closing || disposed) return;
    Backend current = backend.get();
    ZhiziEngineConnections.State state =
        current == null ? ZhiziEngineConnections.State.DISCONNECTED : current.state();
    if (state != lastState) notice = null;
    lastState = state;
    if (notice == null) showState(state);
    disconnect.setEnabled(
        state != ZhiziEngineConnections.State.DISCONNECTED || pendingSwitch.getAsBoolean());
  }

  private void showState(ZhiziEngineConnections.State state) {
    String message =
        switch (state) {
          case DISCONNECTED ->
              text.apply(
                  "RemoteCompute.zhiziSession.disconnected",
                  "Main/comparison Zhizi engines: disconnected");
          case CONNECTED ->
              text.apply(
                  "RemoteCompute.zhiziSession.connected",
                  "Main/comparison Zhizi engines: connection retained");
          case BUSY ->
              text.apply(
                  "RemoteCompute.zhiziSession.busy",
                  "Main/comparison Zhizi engines: task or connection change active");
        };
    status.setText(message);
    AccessibilitySupport.named(status, message, message);
  }

  private void disconnect() {
    if (closing || disposed) return;
    cancelPendingSwitch.run();
    Backend target = backend.get();
    if (target == null) {
      busy.accept(false);
      refresh();
      return;
    }
    notice = null;
    closing = true;
    busy.accept(true);
    disconnect.setEnabled(false);
    status.setText(
        text.apply("RemoteCompute.zhiziSession.disconnecting", "Disconnecting Zhizi Cloud..."));
    runner.run(
        target,
        (result, failure) -> {
          closing = false;
          if (disposed) return;
          busy.accept(false);
          refresh();
          if (failure != null) {
            LOG.warn("Could not disconnect Zhizi client sessions", failure);
            notice =
                text.apply(
                    "RemoteCompute.zhiziSession.failed",
                    "Disconnect failed. Check the connection before continuing.");
            status.setText(notice);
          } else if (result == ZhiziEngineConnections.State.BUSY) {
            notice =
                text.apply(
                    "RemoteCompute.zhiziSession.blocked",
                    "Finish the active task or engine switch, then disconnect Zhizi Cloud.");
            status.setText(notice);
          }
        });
  }

  private static void runBackground(
      Backend target, BiConsumer<ZhiziEngineConnections.State, Throwable> completion) {
    new SwingWorker<ZhiziEngineConnections.State, Void>() {
      protected ZhiziEngineConnections.State doInBackground() {
        return target.disconnect();
      }

      protected void done() {
        ZhiziEngineConnections.State result;
        try {
          result = get();
        } catch (Exception failure) {
          completion.accept(null, failure);
          return;
        }
        completion.accept(result, null);
      }
    }.execute();
  }

  static Backend backend(EngineManager manager) {
    if (manager == null) return null;
    return new Backend() {
      public ZhiziEngineConnections.State state() {
        return manager.zhiziConnectionState();
      }

      public ZhiziEngineConnections.State disconnect() {
        return manager.disconnectZhiziEngines();
      }
    };
  }
}
