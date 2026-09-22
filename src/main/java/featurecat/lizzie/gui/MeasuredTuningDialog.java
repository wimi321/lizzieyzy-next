package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.MeasuredKataGoTuning;
import featurecat.lizzie.util.katago.tuning.KataGoMeasuredReport.Scene;
import java.awt.Window;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Review and explicit confirmation for measurement-backed settings in the existing setup page. */
final class MeasuredTuningDialog {
  private MeasuredTuningDialog() {}

  static void importReport(Window owner, String entryId, Consumer<Boolean> busy, Runnable changed) {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle(text("import"));
    chooser.setFileFilter(new FileNameExtensionFilter("JSON", "json"));
    if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
    java.nio.file.Path reportPath = chooser.getSelectedFile().toPath();
    busy.accept(true);
    new SwingWorker<MeasuredKataGoTuning.Review, Void>() {
      @Override
      protected MeasuredKataGoTuning.Review doInBackground() throws Exception {
        return MeasuredKataGoTuning.review(entryId, reportPath);
      }

      @Override
      protected void done() {
        busy.accept(false);
        if (!owner.isDisplayable()) return;
        try {
          var review = get();
          var report = review.report();
          String scene = text(report.scene() == Scene.LIVE ? "live" : "wholeGame");
          String message =
              scene
                  + "\n"
                  + report.baselineParameters()
                  + " → "
                  + report.candidateParameters()
                  + "\n"
                  + String.format(
                      Locale.getDefault(), text("gain"), (report.assess().speedup() - 1) * 100)
                  + "\n\n"
                  + text("confirm");
          if (JOptionPane.showConfirmDialog(
                  owner,
                  plainText(message),
                  text("title"),
                  JOptionPane.OK_CANCEL_OPTION,
                  JOptionPane.QUESTION_MESSAGE)
              == JOptionPane.OK_OPTION) {
            runWorker(
                owner,
                busy,
                changed,
                () -> {
                  MeasuredKataGoTuning.apply(review);
                  return null;
                });
          }
        } catch (Exception failure) {
          showFailure(owner, failure);
        }
      }
    }.execute();
  }

  static void restore(Window owner, String entryId, Consumer<Boolean> busy, Runnable changed) {
    if (JOptionPane.showConfirmDialog(
            owner,
            plainText(text("restoreConfirm")),
            text("title"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE)
        != JOptionPane.OK_OPTION) return;
    runWorker(
        owner,
        busy,
        changed,
        () -> {
          MeasuredKataGoTuning.restore(entryId);
          return null;
        });
  }

  private static void runWorker(
      Window owner, Consumer<Boolean> busy, Runnable changed, Callable<Void> action) {
    busy.accept(true);
    new SwingWorker<Void, Void>() {
      @Override
      protected Void doInBackground() throws Exception {
        return action.call();
      }

      @Override
      protected void done() {
        busy.accept(false);
        if (!owner.isDisplayable()) return;
        try {
          get();
          changed.run();
          JOptionPane.showMessageDialog(
              owner, plainText(text("saved")), text("title"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception failure) {
          showFailure(owner, failure);
        }
      }
    }.execute();
  }

  private static void showFailure(Window owner, Exception failure) {
    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
    JOptionPane.showMessageDialog(
        owner,
        plainText(text("rejected") + "\n" + cause.getMessage()),
        text("title"),
        JOptionPane.WARNING_MESSAGE);
  }

  private static JTextArea plainText(String value) {
    JTextArea area = new JTextArea(value, 0, 58);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setEditable(false);
    area.setOpaque(false);
    return area;
  }

  private static String text(String key) {
    return Lizzie.resourceBundle.getString("MeasuredTuning." + key);
  }
}
