package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** The simple tree's render/publication boundary. Each drawing owns its mutable renderer. */
final class VariationTreeImage {
  record View(
      Board board,
      BoardHistoryList history,
      BoardHistoryNode displayNode,
      BoardHistoryNode boardNode,
      long revision,
      int x,
      int y,
      int width,
      int height,
      int panelWidth,
      int panelHeight) {
    boolean hasCurrentHistory() {
      return Lizzie.board == board
          && board.getHistory() == history
          && history.getCurrentHistoryNode() == boardNode
          && board.getContextRevision() == revision
          && Lizzie.frame.getDisplayNode() == displayNode;
    }
  }

  record Result(View view, BufferedImage image, VariationTreeBig renderer) {}

  private final Executor worker;
  private final Consumer<Runnable> completion;
  private volatile long generation;

  VariationTreeImage() {
    this(ForkJoinPool.commonPool(), SwingUtilities::invokeLater);
  }

  VariationTreeImage(Executor worker, Consumer<Runnable> completion) {
    this.worker = worker;
    this.completion = completion;
  }

  void request(View view, BooleanSupplier current, Consumer<Result> publish) {
    long requestGeneration = ++generation;
    worker.execute(
        () -> {
          Result result;
          // Capture and traverse history under the navigation/adoption monitor. Each drawing
          // owns its renderer; no mutable layout state escapes to another worker or to the EDT.
          synchronized (view.board()) {
            if (requestGeneration != generation || !view.hasCurrentHistory()) return;
            BufferedImage image =
                new BufferedImage(view.width(), view.height(), BufferedImage.TYPE_INT_ARGB);
            VariationTreeBig renderer = new VariationTreeBig(view.displayNode());
            Graphics2D graphics = image.createGraphics();
            try {
              graphics.setRenderingHint(
                  RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
              graphics.setRenderingHint(
                  RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
              renderer.draw(graphics, 0, 0, view.width(), view.height());
            } finally {
              graphics.dispose();
            }
            result = new Result(view, image, renderer);
          }
          completion.accept(
              () -> {
                if (requestGeneration == generation && current.getAsBoolean())
                  publish.accept(result);
              });
        });
  }
}
