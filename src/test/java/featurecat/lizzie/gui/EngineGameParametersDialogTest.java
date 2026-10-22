package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGameParameterRequest;
import featurecat.lizzie.enginegame.EngineGameParameterRevision;
import featurecat.lizzie.enginegame.EngineGameParameterState;
import featurecat.lizzie.enginegame.EngineGameParameterState.Position;
import featurecat.lizzie.enginegame.EngineGameParameterState.Value;
import featurecat.lizzie.enginegame.EngineGamePlayMode;
import featurecat.lizzie.enginegame.EngineGameView;
import featurecat.lizzie.enginegame.EngineParticipantIdentity;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineGameParametersDialogTest {
  private static final EngineGameView VIEW =
      new EngineGameView(
          new EngineParticipantIdentity("first", "KataGo E1"),
          new EngineParticipantIdentity("second", "KataGo E2"),
          EngineGamePlayMode.ANALYSIS,
          1,
          0,
          1,
          0,
          1,
          true,
          -1);

  @TempDir Path root;

  private Config previousConfig;
  private ResourceBundle previousBundle;
  private FakeGame game;
  private EngineGameParametersDialog dialog;

  @BeforeEach
  void setUp() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless());
    previousConfig = Lizzie.config;
    previousBundle = Lizzie.resourceBundle;
    Files.createDirectories(root.resolve("save"));
    Lizzie.config = ConfigTestHelper.createBootstrapped(root);
    Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
    game = new FakeGame();
    SwingUtilities.invokeAndWait(
        () -> {
          dialog = new EngineGameParametersDialog(null, game);
          game.dialog = dialog;
        });
  }

  @AfterEach
  void tearDown() throws Exception {
    if (dialog != null) SwingUtilities.invokeAndWait(dialog::dispose);
    if (previousBundle != null) {
      Lizzie.config = previousConfig;
      Lizzie.resourceBundle = previousBundle;
    }
  }

  @Test
  void okWaitsForEveryConfirmationWithoutChangingThePanelThenCloses() throws Exception {
    edt(
        () -> {
          dialog.present(null);
          Dimension before = dialog.getSize();
          engine2Breadth().setText("0.45");
          ok();

          assertEquals(1, game.requests.size());
          assertEquals(0.45, game.requests.get(0).secondWrn());
          assertTrue(dialog.isVisible(), "still waiting for the engines");
          assertEquals("0.45", engine2Breadth().getText(), "typed value is kept while waiting");
          assertFalse(
              labels().stream().anyMatch(t -> t.startsWith("Applying")), labels().toString());
          assertEquals(before, dialog.getSize(), "no resize while waiting to close");

          game.publish(state(0.25, 0.45, true, null)); // an unrelated refresh, still unconfirmed
          assertTrue(dialog.isVisible());
          game.publish(state(0.45, null, false, null));
          assertFalse(dialog.isVisible(), "closes once both participants confirmed");
        });
  }

  @Test
  void failureKeepsThePanelOpenAndASuccessfulRetryClosesIt() throws Exception {
    edt(
        () -> {
          dialog.present(null);
          engine2Breadth().setText("0.45");
          ok();
          game.publish(state(0.25, 0.45, false, "Engine 2: the engine rejected it"));
          assertTrue(dialog.isVisible(), "a failed change keeps the panel open");
          assertTrue(statusText().contains("Engine 2: the engine rejected it"), statusText());

          engine2Breadth().setText("0.4");
          ok();
          // The retry is running; the earlier failure is still recorded until it settles.
          assertTrue(dialog.isVisible());
          game.publish(state(0.4, null, false, null));
          assertFalse(dialog.isVisible(), "the successful retry closes the panel");
        });
  }

  @Test
  void okWithoutEditsAndEscCloseWithoutSendingAndDropTheDraft() throws Exception {
    edt(
        () -> {
          dialog.present(null);
          ok();
          assertFalse(dialog.isVisible(), "nothing edited: OK just closes");

          dialog.present(null);
          engine2Breadth().setText("1.5");
          escape();
          assertFalse(dialog.isVisible(), "Esc closes");
          assertTrue(game.requests.isEmpty(), game.requests.toString());

          dialog.present(null);
          assertEquals("0.25", engine2Breadth().getText(), "an abandoned draft is not kept");
        });
  }

  @Test
  void panelShrinksBackAfterATemporaryLabelUnlessTheUserResizedIt() {
    Dimension fitted = new Dimension(720, 200);
    Dimension widened = new Dimension(810, 200);
    assertEquals(
        widened, EngineGameParametersDialog.fittedSize(fitted, fitted, widened), "grows to fit");
    assertEquals(
        fitted,
        EngineGameParametersDialog.fittedSize(widened, widened, fitted),
        "shrinks back once the pending label is gone");

    Dimension userSize = new Dimension(900, 260);
    assertEquals(
        userSize,
        EngineGameParametersDialog.fittedSize(userSize, fitted, widened),
        "a size the user chose is kept while the content fits");
    assertEquals(
        new Dimension(1000, 260),
        EngineGameParametersDialog.fittedSize(userSize, fitted, new Dimension(1000, 200)),
        "and only grows when the content would be cut off");
  }

  /** Engine 1 breadth/aggressiveness = 0.5/1.5; Engine 2 aggressiveness = -1. */
  private static EngineGameParameterState state(
      double engine2Breadth, Double engine2Target, boolean pending, String failure) {
    Position first = new Position(new Value(true, 0.5, null), new Value(true, 1.5, null));
    Position second =
        new Position(new Value(true, engine2Breadth, engine2Target), new Value(true, -1.0, null));
    return new EngineGameParameterState(first, second, pending, failure);
  }

  private JTextField engine2Breadth() {
    // Fields in layout order: Engine 1 breadth, Engine 1 aggressiveness, Engine 2 breadth, ...
    return components(JTextField.class).get(2);
  }

  private void ok() {
    dialog.getRootPane().getDefaultButton().doClick();
  }

  private void escape() {
    JComponent rootPane = dialog.getRootPane();
    Object key =
        rootPane
            .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
    rootPane.getActionMap().get(key).actionPerformed(null);
  }

  private List<String> labels() {
    List<String> texts = new ArrayList<>();
    for (JLabel label : components(JLabel.class)) {
      if (label.isVisible() && !label.getText().isEmpty()) texts.add(label.getText());
    }
    return texts;
  }

  private String statusText() {
    for (JTextArea area : components(JTextArea.class)) {
      if (area.isVisible()) return area.getText();
    }
    return "";
  }

  private <T> List<T> components(Class<T> type) {
    List<T> found = new ArrayList<>();
    collect(dialog.getContentPane(), type, found);
    return found;
  }

  private static <T> void collect(Container container, Class<T> type, List<T> found) {
    for (Component component : container.getComponents()) {
      if (type.isInstance(component)) found.add(type.cast(component));
      if (component instanceof Container child) collect(child, type, found);
    }
  }

  private static void edt(ThrowingRunnable body) throws Exception {
    Throwable[] failure = new Throwable[1];
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            body.run();
          } catch (Throwable t) {
            failure[0] = t;
          }
        });
    if (failure[0] instanceof Error error) throw error;
    if (failure[0] instanceof Exception exception) throw exception;
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  /**
   * One playing game. Like the production module, accepting a request records its target, marks the
   * update pending without clearing an earlier failure, and refreshes the panel at once.
   */
  private static final class FakeGame implements EngineGameParametersDialog.Game {
    final Object identity = new Object();
    final List<EngineGameParameterRequest> requests = new ArrayList<>();
    EngineGameParametersDialog dialog;
    EngineGameParameterState state = state(0.25, null, false, null);

    @Override
    public EngineGameParameterState parameters() {
      return state;
    }

    @Override
    public EngineGameView view() {
      return VIEW;
    }

    @Override
    public Object identity() {
      return identity;
    }

    @Override
    public EngineGameParameterRevision revise(EngineGameParameterRequest request) {
      requests.add(request);
      publish(state(state.second().wrn().confirmed(), request.secondWrn(), true, state.failure()));
      return EngineGameParameterRevision.ACCEPTED;
    }

    void publish(EngineGameParameterState next) {
      state = next;
      dialog.refresh();
    }
  }
}
