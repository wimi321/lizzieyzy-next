package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.RulePreset;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryList;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class RulesQuickSwitchTest {
  private static final String CHINESE =
      "{\"ko\":\"SIMPLE\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
          + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true}";

  @Test
  void the2027RulesSendTheKomiBeforeTheRulesAndPublishThem() throws Exception {
    try (Fixture fixture = new Fixture()) {
      GameInfo game = fixture.game();
      game.setKomiNoMenu(7.5);
      BoardHistoryList history = Lizzie.board.getHistory();
      BoardHistoryList.SessionRulesTarget before = history.captureSessionRules();

      fixture.apply(RulePreset.CHINESE_2027);

      String commands = fixture.output.toString();
      int komi = commands.indexOf(" komi 7.0");
      int rules = commands.indexOf(" kata-set-rules ");
      assertTrue(komi >= 0 && rules > komi, commands);
      assertEquals(7.0, game.getKomi());

      fixture.confirm(CHINESE);
      await(() -> history.captureSessionRules() != before);
      assertTrue(
          history
              .captureSessionRules()
              .parsedRules()
              .orElseThrow()
              .semanticallyEquals(KataGoRules.parse("chinese").orElseThrow()));
      assertTrue(fixture.messages.isEmpty(), fixture.messages.toString());
    }
  }

  @Test
  void aHandicapGameKeepsItsKomi() throws Exception {
    try (Fixture fixture = new Fixture()) {
      GameInfo game = fixture.game();
      game.setHandicap(2);
      game.setKomiNoMenu(0.5);

      fixture.apply(RulePreset.CHINESE_2027);

      String commands = fixture.output.toString();
      assertFalse(commands.contains(" komi "), commands);
      assertTrue(commands.contains(" kata-set-rules "), commands);
      assertEquals(0.5, game.getKomi());
      assertEquals(7.0f, fixture.engine.orikomi, "new games still start with the komi");
    }
  }

  @Test
  void theDrawToFirstPassRuleSendsKataGosChineseRulesWithTheButton() throws Exception {
    try (Fixture fixture = new Fixture()) {
      GameInfo game = fixture.game();
      game.setKomiNoMenu(7.5);
      BoardHistoryList history = Lizzie.board.getHistory();
      BoardHistoryList.SessionRulesTarget before = history.captureSessionRules();

      fixture.apply(RulePreset.CHINESE_2027_DRAW_TO_FIRST_PASS);

      String commands = fixture.output.toString();
      assertTrue(commands.contains(" komi 7.0"), commands);
      assertTrue(commands.contains("\"hasButton\":true"), commands);
      fixture.confirm(CHINESE.replace("\"hasButton\":false", "\"hasButton\":true"));
      await(() -> history.captureSessionRules() != before);
      assertTrue(
          history
              .captureSessionRules()
              .parsedRules()
              .orElseThrow()
              .semanticallyEquals(RulePreset.CHINESE_2027_DRAW_TO_FIRST_PASS.rules()));
      assertTrue(fixture.messages.isEmpty(), fixture.messages.toString());
    }
  }

  @Test
  void rulesTheEngineDoesNotTakeAreReported() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.apply(RulePreset.JAPANESE);

      fixture.confirm(CHINESE);
      await(() -> !fixture.messages.isEmpty());
      assertEquals(
          List.of(Lizzie.resourceBundle.getString("SetKataRules.status.setFailed")),
          fixture.messages);
    }
  }

  @Test
  void theChoiceBecomesTheRulesNewGamesStartWith() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Lizzie.config.autoLoadKataRules = false;
      Lizzie.config.newGameKomi = 7.5;
      Lizzie.config.newEngineGameKomi = 7.5;

      fixture.apply(RulePreset.CHINESE_2027);

      KataGoRules chinese = RulePreset.CHINESE_2027.rules();
      assertTrue(Lizzie.config.autoLoadKataRules);
      assertTrue(
          KataGoRules.parse(Lizzie.config.kataRules).orElseThrow().semanticallyEquals(chinese));
      JSONObject saved = Lizzie.config.uiConfig;
      assertTrue(saved.getBoolean("auto-load-kata-rules"));
      assertTrue(
          KataGoRules.parse(saved.getString("kata-rules"))
              .orElseThrow()
              .semanticallyEquals(chinese));
      assertEquals("CHINESE_2027", saved.getString(RulePreset.CHOSEN_KEY));
      assertEquals(7.0, saved.getDouble("new-game-komi"));
      assertEquals(7.0, saved.getDouble("new-engine-game-komi"));
      assertEquals(7.0, Lizzie.config.newGameKomi);
      assertEquals(7.0, Lizzie.config.newEngineGameKomi);
      assertEquals(Optional.of(RulePreset.CHINESE_2027), RulePreset.chosen(Lizzie.config));
      for (Leelaz engine : fixture.engines) {
        assertEquals(7.0f, engine.orikomi, "a cleared board on any engine gets the komi");
      }
    }
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(20L);
    }
    SwingUtilities.invokeAndWait(() -> {}); // let a message queued on the event thread arrive
    assertTrue(condition.getAsBoolean(), "condition not met in time");
  }

  private static int commandIdFor(String commands, String command) {
    String[] lines = commands.split("\\R");
    for (int index = lines.length - 1; index >= 0; index--) {
      String trimmed = lines[index].trim();
      int split = trimmed.indexOf(' ');
      if (split > 0 && trimmed.substring(split + 1).startsWith(command)) {
        return Integer.parseInt(trimmed.substring(0, split));
      }
    }
    throw new AssertionError("missing " + command + " in " + commands);
  }

  /** A started KataGo that answers only what the test dispatches; global state is restored. */
  private static final class Fixture implements AutoCloseable {
    private final Config previousConfig = Lizzie.config;
    private final Leelaz previousLeelaz = Lizzie.leelaz;
    private final Board previousBoard = Lizzie.board;
    private final LizzieFrame previousFrame = Lizzie.frame;
    private final ResourceBundle previousBundle = Lizzie.resourceBundle;
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final List<String> messages = new CopyOnWriteArrayList<>();
    final Leelaz engine;
    final List<Leelaz> engines;

    Fixture() throws Exception {
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Lizzie.config = ConfigTestHelper.createForTests(Files.createTempDirectory("rules-quick"));
      Lizzie.config.uiConfig = new JSONObject();
      Lizzie.config.leelazConfig = new JSONObject();
      Lizzie.config.config =
          new JSONObject()
              .put("ui", Lizzie.config.uiConfig)
              .put("leelaz", Lizzie.config.leelazConfig);
      Lizzie.frame = null;
      resetEngineGame();
      Leelaz[] created = new Leelaz[1];
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              created[0] = liveEngine(output);
              Lizzie.board = new Board();
            } catch (Exception failure) {
              throw new IllegalStateException(failure);
            }
          });
      engine = created[0];
      Lizzie.leelaz = engine;
      Leelaz other = new Leelaz("");
      other.orikomi = 7.5f;
      engine.orikomi = 7.5f;
      engines = List.of(engine, other);
    }

    GameInfo game() {
      return Lizzie.board.getHistory().getGameInfo();
    }

    void apply(RulePreset preset) throws Exception {
      RulesQuickSwitch actions =
          new RulesQuickSwitch(
              engine,
              engines,
              game(),
              Lizzie.config,
              new ManualRulesSelection(Lizzie.config, () -> Lizzie.board, () -> null),
              Lizzie.resourceBundle,
              messages::add,
              () -> {});
      SwingUtilities.invokeAndWait(() -> actions.apply(preset));
    }

    /** Acknowledges the rules command and reads {@code rules} back as the engine's rules. */
    void confirm(String rules) throws Exception {
      dispatch("=" + commandIdFor(output.toString(), "kata-set-rules"));
      dispatch("=" + commandIdFor(output.toString(), "kata-get-rules") + " " + rules);
    }

    private void dispatch(String line) throws Exception {
      Method method = Leelaz.class.getDeclaredMethod("dispatchReaderLineForTest", String.class);
      method.setAccessible(true);
      method.invoke(engine, line);
    }

    private static Leelaz liveEngine(ByteArrayOutputStream stream) throws Exception {
      Leelaz created = new Leelaz("");
      Method install =
          Leelaz.class.getDeclaredMethod(
              "installFreshCommandOutputForTest", java.io.OutputStream.class);
      install.setAccessible(true);
      install.invoke(created, stream);
      created.started = true;
      created.isLoaded = true;
      created.isKatago = true;
      created.commandLists.addAll(List.of("kata-set-rules", "kata-get-rules"));
      Field ready = Leelaz.class.getDeclaredField("endGetCommandList");
      ready.setAccessible(true);
      ready.setBoolean(created, true);
      return created;
    }

    private static void resetEngineGame() throws Exception {
      Method method =
          EngineManager.class.getDeclaredMethod("resetEngineGameTransactionStateForTest");
      method.setAccessible(true);
      method.invoke(null);
    }

    @Override
    public void close() throws Exception {
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              resetEngineGame();
            } catch (Exception ignored) {
              // Restore the globals even when the engine-game reset fails.
            }
            Lizzie.config = previousConfig;
            Lizzie.leelaz = previousLeelaz;
            Lizzie.board = previousBoard;
            Lizzie.frame = previousFrame;
            Lizzie.resourceBundle = previousBundle;
          });
    }
  }
}
