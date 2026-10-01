package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.SGFParser;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Controlled completion order with the real tree renderer, plus an engine-free Swing window. */
public class VariationTreePublicationTest {
  @Test
  void onlyCurrentTreeCanPublish() throws Exception {
    DesktopProbeProcess.requireDisplay();
    for (String scale : List.of("1", "2")) {
      Path result =
          DesktopProbeProcess.run(
              VariationTreePublicationTest.class,
              "variation-publication-" + scale,
              List.of("-Dsun.java2d.uiScale=" + scale),
              List.of());
      assertTrue(Files.readString(result).contains("PASS"));
    }
  }

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    Path result = Path.of(args[1]);
    try {
      Files.writeString(
          work.resolve("config.txt"),
          "{\"leelaz\":{\"engine-settings-list\":[]},\"ui\":{\"autoload-empty\":true,"
              + "\"first-time-load\":false,\"use-language\":2}}");
      System.setProperty("lizzie.work.dir", work.toString());
      Lizzie.main(new String[0]);
      double requestedScale = Double.parseDouble(System.getProperty("sun.java2d.uiScale"));
      assertEquals(requestedScale, Lizzie.javaScaleFactor.doubleValue());
      assertEquals(
          requestedScale,
          Lizzie.frame.getGraphicsConfiguration().getDefaultTransform().getScaleX());
      SwingUtilities.invokeAndWait(() -> exercise(result));
      windowScenario(result);
      Files.writeString(
          result,
          "PASS: stale node/viewport/history completions rejected; "
              + "preview selection preserved; window navigation and resize repainted without input; "
              + "javaScaleFactor="
              + Lizzie.javaScaleFactor
              + "\n");
      System.exit(0);
    } catch (Throwable failure) {
      failure.printStackTrace();
      Files.writeString(result, failure.toString());
      System.exit(1);
    }
  }

  static String fixture() {
    StringBuilder sgf =
        new StringBuilder("(;FF[4]GM[1]SZ[19];B[aa];W[ba];B[ca];W[da];B[ea];W[fa];B[ga];W[ha]");
    for (char x = 'a'; x <= 's'; x++)
      sgf.append("(;B[").append(x).append("c];W[").append(x).append("d])");
    return sgf.append(')').toString();
  }

  private static void exercise(Path result) {
    assertTrue(Lizzie.frame.isShowing());
    assertTrue(SGFParser.loadFromString(fixture(), false));
    Lizzie.config.showVariationGraph = true;
    Lizzie.config.showScrollVariation = false;
    Lizzie.board.goToMoveNumber(8);
    BoardHistoryNode eight = Lizzie.frame.getDisplayNode();
    BoardHistoryNode nine = eight.next().orElseThrow();
    List<BoardHistoryNode> children = List.copyOf(eight.variations);
    List<Runnable> work = new ArrayList<>();
    List<Runnable> completions = new ArrayList<>();
    VariationTreeImage publication = new VariationTreeImage(work::add, completions::add);
    AtomicReference<VariationTreeImage.Result> visible = new AtomicReference<>();
    java.util.function.Consumer<VariationTreeImage.Result> publish =
        image -> {
          assertTrue(SwingUtilities.isEventDispatchThread());
          visible.set(image);
        };
    VariationTreeImage.View old = view(320, 400);
    publication.request(old, old::hasCurrentHistory, publish);
    work.remove(0).run(); // finish drawing 8, hold its completion at the publication boundary
    Lizzie.board.navigateToNode(nine);
    VariationTreeImage.View current = view(320, 400);
    publication.request(current, current::hasCurrentHistory, publish);
    work.remove(0).run();
    completions.remove(1).run(); // new drawing finishes first
    VariationTreeImage.Result accepted = visible.get();
    assertSame(nine, accepted.view().displayNode());
    assertSelection(accepted.image());
    completions.remove(0).run(); // release old drawing only after 9 is visible
    assertSame(accepted, visible.get(), "late move 8 must not replace the displayed move 9 image");
    assertSame(nine, Lizzie.board.getHistory().getCurrentHistoryNode());
    assertEquals(children, eight.variations);
    children.forEach(child -> assertSame(eight, child.previous().orElseThrow()));
    publication.request(current, current::hasCurrentHistory, publish);
    work.remove(0).run();
    VariationTreeImage.View resized = view(460, 500);
    publication.request(resized, resized::hasCurrentHistory, publish);
    work.remove(0).run();
    completions.remove(1).run();
    VariationTreeImage.Result resizedImage = visible.get();
    assertEquals(460, resizedImage.image().getWidth());
    assertEquals(500, resizedImage.image().getHeight());
    assertSelection(resizedImage.image());
    completions.remove(0).run();
    assertSame(resizedImage, visible.get(), "old viewport completion must be discarded");

    // A display preview must have one marker, at the preview node, not at the real board node.
    Lizzie.frame.setDisplayNodeOverride(eight);
    VariationTreeImage.View preview = view(320, 400);
    publication.request(preview, preview::hasCurrentHistory, publish);
    work.remove(0).run();
    completions.remove(0).run();
    assertSame(eight, visible.get().view().displayNode());
    assertSame(nine, Lizzie.board.getHistory().getCurrentHistoryNode());
    assertSelection(visible.get().image());
    assertNotEquals(
        Color.RED.getRGB(),
        visible.get().image().getRGB(markerX(), 200 + markerX() - 20 + (largeScale() ? 30 : 20)));
    Lizzie.frame.setDisplayNodeOverride(null);

    // Replacing the history invalidates a finished image even before another tree request exists.
    publication.request(current, current::hasCurrentHistory, publish);
    work.remove(0).run();
    VariationTreeImage.Result beforeReload = visible.get();
    assertTrue(SGFParser.loadFromString(fixture(), false));
    completions.remove(0).run();
    assertSame(
        beforeReload, visible.get(), "reloaded history must reject the previous game's image");
    try {
      ImageIO.write(accepted.image(), "png", result.resolveSibling("move9.png").toFile());
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    }
  }

  private static void windowScenario(Path result) throws Exception {
    java.awt.Robot robot = new java.awt.Robot();
    SwingUtilities.invokeAndWait(
        () -> {
          Lizzie.board.goToMoveNumber(8);
          Lizzie.frame.refresh();
        });
    awaitVisibleTree(robot, 8, result.resolveSibling("window-move8.png"));
    SwingUtilities.invokeAndWait(() -> Lizzie.board.goToMoveNumber(9));
    awaitVisibleTree(robot, 9, result.resolveSibling("window-move9.png"));
    SwingUtilities.invokeAndWait(
        () -> {
          Lizzie.frame.setSize(1180, 820);
          Lizzie.frame.refresh();
        });
    awaitVisibleTree(robot, 9, result.resolveSibling("window-resized.png"));
  }

  private static void awaitVisibleTree(java.awt.Robot robot, int move, Path screenshot)
      throws Exception {
    java.util.concurrent.CompletableFuture<Void> visible =
        new java.util.concurrent.CompletableFuture<>();
    SwingUtilities.invokeAndWait(
        () -> {
          // Observe repaint completion without causing a paint, navigation or another input event.
          javax.swing.Timer observer = new javax.swing.Timer(30, null);
          long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
          observer.addActionListener(
              event -> {
                try {
                  if (System.nanoTime() > deadline) {
                    java.awt.Point position = Lizzie.frame.mainPanel.getLocationOnScreen();
                    ImageIO.write(
                        robot.createScreenCapture(
                            new java.awt.Rectangle(
                                position.x,
                                position.y,
                                Lizzie.frame.mainPanel.getWidth(),
                                Lizzie.frame.mainPanel.getHeight())),
                        "png",
                        screenshot.toFile());
                    VariationTreeImage.Result last = Lizzie.frame.currentVariationTreeImage();
                    if (last != null)
                      ImageIO.write(
                          last.image(),
                          "png",
                          screenshot.resolveSibling("timeout-tree.png").toFile());
                    throw new AssertionError(
                        "tree did not repaint without input; panel="
                            + position
                            + "; view="
                            + (last == null ? null : last.view()));
                  }
                  VariationTreeImage.Result tree = Lizzie.frame.currentVariationTreeImage();
                  if (tree == null || tree.view().displayNode().getData().moveNumber != move)
                    return;
                  assertSame(
                      Lizzie.board.getHistory().getCurrentHistoryNode(), tree.view().displayNode());
                  java.awt.Point origin = Lizzie.frame.mainPanel.getLocationOnScreen();
                  BufferedImage screen =
                      robot.createScreenCapture(
                          new java.awt.Rectangle(
                              origin.x,
                              origin.y,
                              Lizzie.frame.mainPanel.getWidth(),
                              Lizzie.frame.mainPanel.getHeight()));
                  int markerX = markerX();
                  int markerY = tree.image().getHeight() / 2 + markerX - 20;
                  if (tree.image().getRGB(markerX, markerY) != Color.RED.getRGB()) return;
                  // The canvas cancels the Java2D scale in Utils.ajustScale; Robot returns
                  // logical screen pixels, so use the same inverse mapping as Swing overlays.
                  int screenX = featurecat.lizzie.util.Utils.zoomIn(tree.view().x() + markerX);
                  int screenY = featurecat.lizzie.util.Utils.zoomIn(tree.view().y() + markerY);
                  if (screen.getRGB(screenX, screenY) != Color.RED.getRGB()) return;
                  ImageIO.write(screen, "png", screenshot.toFile());
                  observer.stop();
                  visible.complete(null);
                } catch (Throwable failure) {
                  observer.stop();
                  visible.completeExceptionally(failure);
                }
              });
          observer.start();
        });
    visible.get(15, java.util.concurrent.TimeUnit.SECONDS);
  }

  static VariationTreeImage.View view(int width, int height) {
    return new VariationTreeImage.View(
        Lizzie.board,
        Lizzie.board.getHistory(),
        Lizzie.frame.getDisplayNode(),
        Lizzie.board.getHistory().getCurrentHistoryNode(),
        Lizzie.board.getContextRevision(),
        0,
        0,
        width,
        height,
        Lizzie.frame.mainPanel.getWidth(),
        Lizzie.frame.mainPanel.getHeight());
  }

  private static void assertSelection(BufferedImage image) {
    // First lane's move marker is centered vertically by the established renderer.
    assertEquals(
        Color.RED.getRGB(), image.getRGB(markerX(), image.getHeight() / 2 + markerX() - 20));
  }

  private static boolean largeScale() {
    return featurecat.lizzie.Config.isScaled && Lizzie.javaScaleFactor >= 1.5;
  }

  private static int markerX() {
    return largeScale() ? 28 : 25;
  }
}
