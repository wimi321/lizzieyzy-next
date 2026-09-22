package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.enginegame.EngineGamePresentation;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.util.AtomicSgfFileWriter;
import featurecat.lizzie.util.Utils;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.json.JSONObject;

/** Captures SGF on the UI thread, keeping mutable board/engine state out of background I/O. */
public final class SgfSaveCoordinator {
  enum Mode {
    NORMAL(false, false, false, true),
    RAW(true, false, false, true),
    RAW_COMMENT(true, true, false, false),
    CURRENT_BRANCH(true, false, true, false);

    final boolean raw;
    final boolean comments;
    final boolean branch;
    final boolean updateCurrentFile;

    Mode(boolean raw, boolean comments, boolean branch, boolean updateCurrentFile) {
      this.raw = raw;
      this.comments = comments;
      this.branch = branch;
      this.updateCurrentFile = updateCurrentFile;
    }
  }

  private static final SgfSaveQueue SAVES =
      new SgfSaveQueue(
          new ThreadPoolExecutor(
              1,
              1,
              30,
              TimeUnit.SECONDS,
              new ArrayBlockingQueue<>(16),
              task -> {
                Thread worker = new Thread(task, "lizzie-sgf-save");
                worker.setDaemon(true);
                return worker;
              }),
          SwingUtilities::invokeLater,
          AtomicSgfFileWriter::write);

  private SgfSaveCoordinator() {}

  static void chooseAndSave(Mode mode) {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(() -> chooseAndSave(mode));
      return;
    }
    Leelaz engine = Lizzie.leelaz;
    boolean pondering =
        engine != null && engine.isPondering() && !EngineGamePresentation.current().playing();
    if (pondering) engine.togglePonder();
    try {
      JSONObject filesystem = Lizzie.config.persisted.getJSONObject("filesystem");
      JFileChooser chooser = new JFileChooser(filesystem.getString("last-folder"));
      chooser.setFileFilter(new FileNameExtensionFilter("*.sgf", "SGF"));
      chooser.setMultiSelectionEnabled(false);
      String name = Lizzie.board.getHistory().getGameInfo().getSaveFileName();
      String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
      chooser.setSelectedFile(new File(name.isEmpty() ? timestamp : name + "_" + timestamp));
      if (chooser.showSaveDialog(Lizzie.frame) != JFileChooser.APPROVE_OPTION) return;
      File target = LizzieFrame.sgfSaveTarget(chooser.getSelectedFile());
      if (target.exists()
          && JOptionPane.showConfirmDialog(
                  Lizzie.frame,
                  Lizzie.resourceBundle.getString("LizzieFrame.prompt.sgfExists"),
                  Lizzie.resourceBundle.getString("LizzieFrame.warning"),
                  JOptionPane.OK_CANCEL_OPTION)
              != JOptionPane.OK_OPTION) return;
      captureAndSave(mode, target);
    } finally {
      if (pondering && Lizzie.leelaz == engine && !engine.isPondering()) engine.togglePonder();
    }
  }

  static void saveOriginal(File target) {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(() -> saveOriginal(target));
      return;
    }
    captureAndSave(Mode.NORMAL, target);
  }

  private static void captureAndSave(Mode mode, File target) {
    Board board = Lizzie.board;
    BoardHistoryList history = board.getHistory();
    BoardHistoryNode root = history.getStart();
    JSONObject filesystem = Lizzie.config.persisted.getJSONObject("filesystem");
    try {
      String snapshot = SGFParser.saveSnapshot(board, mode.raw, mode.comments, mode.branch);
      SAVES.submit(
          target.toPath(),
          snapshot,
          () ->
              Lizzie.board == board && board.getHistory() == history && history.getStart() == root,
          () -> {
            if (mode.updateCurrentFile) LizzieFrame.curFile = target;
            if (target.getParent() != null) filesystem.put("last-folder", target.getParent());
          },
          failure -> showFailure());
    } catch (IOException | RuntimeException failure) {
      showFailure();
    }
  }

  private static void showFailure() {
    Utils.showMsg(Lizzie.resourceBundle.getString("LizzieFrame.saveFileFailed"));
  }

  /** Shutdown must let accepted writes and their UI completion finish before persisting/exiting. */
  public static CompletableFuture<Void> pendingSaves() {
    return SAVES.pending();
  }
}
