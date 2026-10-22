package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGameOpeningParameters;
import featurecat.lizzie.enginegame.EngineGameParameterRequest;
import featurecat.lizzie.enginegame.EngineGameParameterRevision;
import featurecat.lizzie.enginegame.EngineGameParameterState;
import featurecat.lizzie.enginegame.EngineGameView;
import featurecat.lizzie.util.Utils;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.util.function.Function;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * In-game "game parameters" panel: shows the confirmed breadth and aggressiveness of the two
 * engine-game participant positions and sends only the edited fields, and only on OK, then closes
 * once they are confirmed. Drafts belong to the game they were typed for and are dropped when that
 * game ends. Displays only what its {@link Game} reports; EDT only.
 */
public final class EngineGameParametersDialog extends JDialog {
  /** The engine game the panel shows and edits. */
  interface Game {
    /** The playing game's parameters, or null without one. */
    EngineGameParameterState parameters();

    /** The playing game's participants, or null without one. */
    EngineGameView view();

    /** Identity of the playing game; drafts typed for one game are never sent to another. */
    Object identity();

    EngineGameParameterRevision revise(EngineGameParameterRequest request);
  }

  private static final Game LIVE =
      new Game() {
        @Override
        public EngineGameParameterState parameters() {
          return Lizzie.engineGame.parameterState();
        }

        @Override
        public EngineGameView view() {
          return Lizzie.engineGame.current().view();
        }

        @Override
        public Object identity() {
          return Lizzie.engineGame.transaction();
        }

        @Override
        public EngineGameParameterRevision revise(EngineGameParameterRequest request) {
          return Lizzie.engineGame.reviseParameters(request);
        }
      };

  private static EngineGameParametersDialog instance;

  private final Game game;
  private final PositionRow firstRow = new PositionRow(true);
  private final PositionRow secondRow = new PositionRow(false);
  private final JTextArea status = new JTextArea();
  private final JFontButton applyButton =
      new JFontButton(Lizzie.resourceBundle.getString("EngineGameParameters.ok"));

  /** The game the drafts were typed for, or null without a playing game. */
  private Object draftGame;

  /** OK was accepted: close once both participants confirmed, stay open on a failure. */
  private boolean closeWhenConfirmed;

  /** The size the panel last gave itself; a different size was chosen by the user. */
  private Dimension fittedSize;

  EngineGameParametersDialog(Window owner, Game game) {
    super(owner);
    this.game = game;
    setTitle(Lizzie.resourceBundle.getString("EngineGameParameters.title"));
    setModal(false);
    setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
    if (owner != null) {
      setAlwaysOnTop(owner.isAlwaysOnTop());
    }

    JPanel rows = new JPanel(new GridBagLayout());
    rows.setBorder(new EmptyBorder(12, 12, 4, 12));
    firstRow.addTo(rows, 0);
    secondRow.addTo(rows, 1);

    status.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    status.setLineWrap(true);
    status.setWrapStyleWord(true);
    status.setEditable(false);
    status.setFocusable(false);
    status.setOpaque(false);
    status.setColumns(36);
    status.setBorder(new EmptyBorder(4, 12, 4, 12));

    applyButton.addActionListener(e -> apply());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
    buttons.add(applyButton);

    JPanel south = new JPanel(new BorderLayout());
    south.add(status, BorderLayout.CENTER);
    south.add(buttons, BorderLayout.SOUTH);
    getContentPane().setLayout(new BorderLayout());
    getContentPane().add(rows, BorderLayout.CENTER);
    getContentPane().add(south, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(applyButton);
    AccessibilitySupport.installEscapeAction(getRootPane(), this, () -> setVisible(false));
  }

  /** Shows the single panel instance, or brings it to front; drafts of a closed panel are gone. */
  public static void open(Window owner) {
    if (instance == null) {
      instance = new EngineGameParametersDialog(owner, LIVE);
    }
    instance.present(owner);
  }

  void present(Window owner) {
    if (isVisible()) {
      refresh();
    } else {
      discardDrafts();
      closeWhenConfirmed = false;
      refresh();
      pack();
      fittedSize = getSize();
      setLocationRelativeTo(owner);
      setVisible(true);
    }
    toFront();
  }

  /** Refreshes the panel after an engine-game transition; no-op while it is closed. */
  public static void refreshIfOpen() {
    if (instance != null && instance.isVisible()) {
      instance.refresh();
    }
  }

  /** Whether the panel is showing, so its inline failure text replaces a popup. */
  public static boolean isOpen() {
    return instance != null && instance.isVisible();
  }

  /** A parameter as people type it: {@code 0}, {@code 0.25}, {@code -1.5}, never {@code 0.0}. */
  static String formatValue(double value) {
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }

  /** The playing game, or null; drafts and OK are bound to it. */
  private Object playingGame(EngineGameParameterState state) {
    return state == null || game.view() == null ? null : game.identity();
  }

  void refresh() {
    EngineGameParameterState state = game.parameters();
    Object playingGame = playingGame(state);
    if (playingGame != draftGame) {
      // Drafts never carry over to another game.
      discardDrafts();
      draftGame = playingGame;
    }
    EngineGameView view = playingGame == null ? null : game.view();
    boolean playing = view != null;
    if (closeWhenConfirmed) {
      // A retry starts with the previous failure still recorded; only a settled failure counts.
      if (!playing || (state.failure() != null && !state.pending())) {
        closeWhenConfirmed = false;
      } else if (confirmed(state)) {
        closeWhenConfirmed = false;
        setVisible(false);
        return;
      }
    }
    // After OK the panel closes on confirmation: it keeps the typed values and its size meanwhile.
    firstRow.show(view, playing ? state.position(true) : null, closeWhenConfirmed);
    secondRow.show(view, playing ? state.position(false) : null, closeWhenConfirmed);
    applyButton.setEnabled(playing);
    String message = "";
    if (!playing) {
      message = Lizzie.resourceBundle.getString("EngineGameParameters.notPlaying");
    } else if (state.failure() != null) {
      message =
          MessageFormat.format(
              Lizzie.resourceBundle.getString("EngineGameParameters.failed"), state.failure());
    }
    status.setText(message);
    status.setVisible(!message.isEmpty());
    if (isVisible() && !closeWhenConfirmed) {
      Dimension current = getSize();
      Dimension size = fittedSize(current, fittedSize, getPreferredSize());
      if (!size.equals(current)) setSize(size);
      if (current.equals(fittedSize)) fittedSize = size;
    }
  }

  /** Whether no update is running and every requested value is confirmed. */
  static boolean confirmed(EngineGameParameterState state) {
    return !state.pending() && settled(state.first()) && settled(state.second());
  }

  private static boolean settled(EngineGameParameterState.Position position) {
    return position.wrn().target() == null && position.pda().target() == null;
  }

  /**
   * The panel's next size: it follows its content (also shrinking after a temporary label is gone)
   * while it has the size it gave itself; a size the user chose only grows so nothing is cut off.
   */
  static Dimension fittedSize(Dimension current, Dimension fitted, Dimension preferred) {
    if (current.equals(fitted)) return new Dimension(preferred);
    return new Dimension(
        Math.max(current.width, preferred.width), Math.max(current.height, preferred.height));
  }

  private void discardDrafts() {
    firstRow.discardDraft();
    secondRow.discardDraft();
  }

  private void apply() {
    Object playingGame = playingGame(game.parameters());
    if (playingGame == null || playingGame != draftGame) {
      // The drafts were typed for another game; reload instead of replaying them.
      refresh();
      showMessage("EngineGameParameters.notPlaying");
      return;
    }
    // Only edited fields are sent; each must be a complete, valid number before anything is.
    Function<String, Double> wrnParser = EngineGameOpeningParameters::parseWrn;
    Function<String, Double> pdaParser = EngineGameOpeningParameters::parsePda;
    if (!firstRow.wrn.valid(wrnParser) || !secondRow.wrn.valid(wrnParser)) {
      showMessage("EngineGameParameters.invalidWrn");
      return;
    }
    if (!firstRow.pda.valid(pdaParser) || !secondRow.pda.valid(pdaParser)) {
      showMessage("EngineGameParameters.invalidPda");
      return;
    }
    Double firstWrn = firstRow.wrn.edited(wrnParser);
    Double secondWrn = secondRow.wrn.edited(wrnParser);
    Double firstPda = firstRow.pda.edited(pdaParser);
    Double secondPda = secondRow.pda.edited(pdaParser);
    if (firstWrn == null && secondWrn == null && firstPda == null && secondPda == null) {
      setVisible(false);
      return;
    }
    // Set first: the request publishes the new state, which refreshes this panel right away.
    closeWhenConfirmed = true;
    EngineGameParameterRevision revision =
        game.revise(new EngineGameParameterRequest(firstWrn, secondWrn, firstPda, secondPda));
    if (revision != EngineGameParameterRevision.ACCEPTED
        && revision != EngineGameParameterRevision.UNCHANGED) {
      closeWhenConfirmed = false;
    }
    switch (revision) {
      case ACCEPTED, UNCHANGED -> {
        firstRow.submitted(firstWrn, firstPda);
        secondRow.submitted(secondWrn, secondPda);
        refresh();
      }
      case NOT_PLAYING -> {
        refresh();
        showMessage("EngineGameParameters.notPlaying");
      }
      case UNSUPPORTED -> showMessage("EngineGameParameters.rejected");
      case INVALID ->
          showMessage(
              firstWrn != null || secondWrn != null
                  ? "EngineGameParameters.invalidWrn"
                  : "EngineGameParameters.invalidPda");
    }
  }

  private void showMessage(String key) {
    Utils.showMsg(Lizzie.resourceBundle.getString(key), this);
  }

  /** One participant position; bound to the position, its color follows the current game. */
  private static final class PositionRow {
    private final boolean firstPosition;
    private final JFontLabel position;
    private final JFontLabel engine = new JFontLabel("");
    private final JFontLabel color = new JFontLabel("");
    private final ParameterCell wrn = new ParameterCell("EngineGameParameters.wrn");
    private final ParameterCell pda = new ParameterCell("EngineGameParameters.pda");

    PositionRow(boolean firstPosition) {
      this.firstPosition = firstPosition;
      position =
          new JFontLabel(
              Lizzie.resourceBundle.getString(
                  firstPosition
                      ? "EngineGameParameters.position1"
                      : "EngineGameParameters.position2"));
      position.setFont(position.getFont().deriveFont(Font.BOLD));
    }

    void addTo(JPanel panel, int row) {
      JFontLabel[] labels = {position, engine, color};
      for (int column = 0; column < labels.length; column++) {
        panel.add(labels[column], constraints(column, row, column == 1 ? 1.0 : 0.0));
      }
      wrn.addTo(panel, row, labels.length);
      pda.addTo(panel, row, labels.length + ParameterCell.COLUMNS);
    }

    /** Shows the position in {@code view}; both null when no game is playing. */
    void show(EngineGameView view, EngineGameParameterState.Position state, boolean closing) {
      boolean black = view != null && firstPosition == view.firstIsBlack();
      engine.setText(view == null ? "" : (black ? view.black() : view.white()).name());
      color.setText(
          view == null
              ? ""
              : Lizzie.resourceBundle.getString(
                  black ? "EngineGameParameters.black" : "EngineGameParameters.white"));
      wrn.show(state == null ? null : state.wrn(), closing);
      pda.show(state == null ? null : state.pda(), closing);
    }

    /** Clears the edit marks of the values the panel just submitted. */
    void submitted(Double sentWrn, Double sentPda) {
      if (sentWrn != null) wrn.dirty = false;
      if (sentPda != null) pda.dirty = false;
    }

    void discardDraft() {
      wrn.discardDraft();
      pda.discardDraft();
    }
  }

  /** The confirmed value, pending target and edit field of one parameter of one position. */
  private static final class ParameterCell implements DocumentListener {
    static final int COLUMNS = 3;

    private final String nameKey;
    private final JFontLabel confirmed = new JFontLabel("");
    private final JFontTextField field = new JFontTextField();
    private final JFontLabel pending = new JFontLabel("");

    /** The user edited the field since the panel last loaded it. */
    private boolean dirty;

    private boolean loading;

    ParameterCell(String nameKey) {
      this.nameKey = nameKey;
      field.setColumns(6);
      field.getDocument().addDocumentListener(this);
    }

    void addTo(JPanel panel, int row, int firstColumn) {
      panel.add(confirmed, constraints(firstColumn, row, 0.0));
      panel.add(field, constraints(firstColumn + 1, row, 0.0));
      panel.add(pending, constraints(firstColumn + 2, row, 0.0));
    }

    /** Whether the field is unedited or holds a complete, valid value. */
    boolean valid(Function<String, Double> parser) {
      return !dirty || parser.apply(field.getText()) != null;
    }

    /** The edited value, or null when the field is unedited. */
    Double edited(Function<String, Double> parser) {
      return dirty ? parser.apply(field.getText()) : null;
    }

    /** Shows one parameter; null when no game is playing. Closing keeps the submitted text. */
    void show(EngineGameParameterState.Value state, boolean closing) {
      String name = Lizzie.resourceBundle.getString(nameKey) + ": ";
      if (state == null || !state.supported()) {
        confirmed.setText(
            state == null
                ? ""
                : name + Lizzie.resourceBundle.getString("EngineGameParameters.unsupported"));
        pending.setText("");
        field.setEnabled(false);
        dirty = false;
        load("");
        return;
      }
      confirmed.setText(
          name
              + (state.confirmed() == null
                  ? Lizzie.resourceBundle.getString("EngineGameParameters.unknown")
                  : formatValue(state.confirmed())));
      pending.setText(
          state.target() == null || closing
              ? ""
              : MessageFormat.format(
                  Lizzie.resourceBundle.getString("EngineGameParameters.pending"),
                  formatValue(state.target())));
      field.setEnabled(true);
      if (!dirty && !closing) {
        load(state.confirmed() == null ? "" : formatValue(state.confirmed()));
      }
    }

    void discardDraft() {
      dirty = false;
      load("");
    }

    private void load(String text) {
      if (text.equals(field.getText())) {
        return;
      }
      loading = true;
      try {
        field.setText(text);
      } finally {
        loading = false;
      }
    }

    private void edited() {
      if (!loading) {
        dirty = true;
      }
    }

    @Override
    public void insertUpdate(DocumentEvent e) {
      edited();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
      edited();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
      edited();
    }
  }

  private static GridBagConstraints constraints(int x, int y, double weightx) {
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = x;
    constraints.gridy = y;
    constraints.weightx = weightx;
    constraints.anchor = GridBagConstraints.WEST;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.insets = new Insets(y == 0 ? 0 : 8, x == 0 ? 0 : 12, 0, 0);
    return constraints;
  }
}
