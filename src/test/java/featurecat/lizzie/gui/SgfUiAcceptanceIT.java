package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.MoveData;
import featurecat.lizzie.analysis.SnapshotFileAccessTestBridge;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.BoardNodeKind;
import featurecat.lizzie.rules.SGFParser;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.Utils;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Linux/Xvfb acceptance for the production SGF open, navigate, analyze, save, and reopen flow. */
public final class SgfUiAcceptanceIT {
  private static final String FIXTURE =
      "src/test/resources/featurecat/lizzie/gui/d3-sgf-ui-roundtrip-中文.sgf";
  private static final Set<String> SESSION_ONE_BOOLEAN_KEYS =
      Set.of(
          "open.menu",
          "open.chooser",
          "history.adopted",
          "rules.confirmed",
          "rules.confirmed-before-position",
          "position.confirmed",
          "navigation.keys",
          "navigation.focus",
          "analysis.current-node",
          "analysis.positive-visits",
          "analysis.quiet-400ms",
          "save.menu",
          "save.chooser",
          "semantic.before-save",
          "overwrite.cancel",
          "protected.unchanged",
          "stop",
          "quit",
          "cleanup.process",
          "cleanup.readers",
          "cleanup.sgf");
  private static final Set<String> SESSION_ONE_KEYS =
      orderedSet(
          "scenario",
          "source.sha256",
          "peer.kind",
          "platform.os",
          "platform.arch",
          "runtime.java",
          "display.surface",
          "fixture.path",
          "output.path",
          "output.sha256",
          "protected.path",
          "protected.before.sha256",
          "protected.after.sha256",
          "peer.pid",
          "open.menu",
          "open.chooser",
          "history.adopted",
          "rules.confirmed",
          "rules.confirmed-before-position",
          "rules.target",
          "rules.revision",
          "position.confirmed",
          "position.board",
          "position.komi",
          "position.stones",
          "position.turn",
          "position.tail",
          "navigation.keys",
          "navigation.focus",
          "selected-node-before-save",
          "selected-node.kind",
          "analysis.current-node",
          "analysis.positive-visits",
          "analysis.move",
          "analysis.visits",
          "analysis.emitted",
          "analysis.quiet-400ms",
          "save.menu",
          "save.chooser",
          "semantic.before-save",
          "overwrite.cancel",
          "protected.unchanged",
          "stop",
          "stop.command",
          "quit",
          "cleanup.process",
          "cleanup.readers",
          "cleanup.sgf",
          "evidence.commands",
          "evidence.peer-state",
          "evidence.loaded-sgf",
          "evidence.stopped",
          "evidence.quit",
          "evidence.peer-complete",
          "evidence.stdout",
          "evidence.stderr",
          "evidence.app-log",
          "evidence.staged-sgf",
          "evidence.phases",
          "evidence.lifecycle");
  private static final Set<String> SESSION_TWO_BOOLEAN_KEYS =
      Set.of(
          "open.menu",
          "open.chooser",
          "history.adopted",
          "semantic.tree",
          "semantic.metadata",
          "semantic.setup",
          "semantic.comments",
          "semantic.markup",
          "semantic.rules",
          "reopen.current-node",
          "quit",
          "cleanup.process",
          "cleanup.readers",
          "cleanup.sgf");
  private static final Set<String> SESSION_TWO_KEYS =
      orderedSet(
          "scenario",
          "source.sha256",
          "peer.kind",
          "platform.os",
          "platform.arch",
          "runtime.java",
          "display.surface",
          "fixture.path",
          "output.path",
          "output.sha256",
          "protected.path",
          "open.menu",
          "open.chooser",
          "history.adopted",
          "semantic.tree",
          "semantic.metadata",
          "semantic.setup",
          "semantic.comments",
          "semantic.markup",
          "semantic.rules",
          "rules.target",
          "rules.revision",
          "reopen-current-node",
          "reopen.current-node",
          "reopen.node-kind",
          "reopen.move-color",
          "quit",
          "cleanup.process",
          "cleanup.readers",
          "cleanup.sgf",
          "evidence.stdout",
          "evidence.stderr",
          "evidence.app-log",
          "evidence.phases",
          "evidence.lifecycle");
  private static final Set<String> PEER_STATE_KEYS =
      Set.of(
          "board",
          "komi",
          "stones",
          "turn",
          "tail",
          "rules",
          "rules.confirmed",
          "analysis.count",
          "analysis.active",
          "stop.command",
          "loaded.path");

  @Test
  void opensBranchesAnalyzesSavesCancelsOverwriteAndReopens() throws Exception {
    DesktopProbeProcess.requireDisplay();
    Path fixture = Path.of(FIXTURE).toAbsolutePath().normalize();
    assertTrue(Files.isRegularFile(fixture), fixture.toString());

    Path firstResult =
        DesktopProbeProcess.run(
            SgfUiAcceptanceIT.class,
            "d3-sgf-ui-session-1",
            List.of(),
            List.of("session-1", fixture.toString()),
            120);
    Map<String, String> first = parseResult(firstResult, SESSION_ONE_KEYS);
    assertEquals("d3-sgf-ui-session-1", first.get("scenario"));
    assertEquals("controlled-java", first.get("peer.kind"));
    assertPlatformRuntime(first);
    assertEquals(fixture.toString(), first.get("fixture.path"));
    assertEquals(sha256(fixture), first.get("source.sha256"));
    for (String key : SESSION_ONE_BOOLEAN_KEYS) {
      assertEquals("true", first.get(key), key + " must be affirmative");
    }
    assertEquals("0/1/0", first.get("selected-node-before-save"));
    assertEquals("MOVE", first.get("selected-node.kind"));
    assertEquals("Chinese", first.get("rules.target"));
    assertTrue(Long.parseLong(first.get("rules.revision")) > 0);
    assertEquals("9x9", first.get("position.board"));
    assertEquals("6.5", first.get("position.komi"));
    assertEquals("B:cc,dc,gc;W:dd,de,ee", first.get("position.stones"));
    assertEquals("B", first.get("position.turn"));
    assertEquals("W[dd],B[dc],W[de]", first.get("position.tail"));
    assertTrue(Integer.parseInt(first.get("analysis.visits")) > 0);
    assertTrue(Integer.parseInt(first.get("analysis.emitted")) > 0);
    assertTrue(Set.of("stop", "name").contains(first.get("stop.command")));
    assertEquals(first.get("protected.before.sha256"), first.get("protected.after.sha256"));
    assertEquals(sha256(Path.of(first.get("output.path"))), first.get("output.sha256"));

    long peerPid = Long.parseLong(first.get("peer.pid"));
    assertTrue(peerPid > 0);
    assertFalse(ProcessHandle.of(peerPid).map(ProcessHandle::isAlive).orElse(false));
    assertSessionOneEvidence(first);

    Path sharedProfile = firstResult.getParent().resolve("work").toAbsolutePath().normalize();
    Path secondResult =
        DesktopProbeProcess.run(
            SgfUiAcceptanceIT.class,
            "d3-sgf-ui-session-2",
            List.of("-Dlizzie.work.dir=" + sharedProfile),
            List.of(
                "session-2",
                fixture.toString(),
                first.get("output.path"),
                first.get("protected.path")),
            120);
    Map<String, String> second = parseResult(secondResult, SESSION_TWO_KEYS);
    assertEquals("d3-sgf-ui-session-2", second.get("scenario"));
    assertEquals("none", second.get("peer.kind"));
    assertPlatformRuntime(second);
    assertEquals(first.get("platform.os"), second.get("platform.os"));
    assertEquals(first.get("platform.arch"), second.get("platform.arch"));
    assertEquals(first.get("runtime.java"), second.get("runtime.java"));
    assertEquals(first.get("display.surface"), second.get("display.surface"));
    assertEquals(first.get("source.sha256"), second.get("source.sha256"));
    assertEquals(first.get("output.sha256"), second.get("output.sha256"));
    for (String key : SESSION_TWO_BOOLEAN_KEYS) {
      assertEquals("true", second.get(key), key + " must be affirmative");
    }
    assertEquals("0/0/0", second.get("reopen-current-node"));
    assertEquals("PASS", second.get("reopen.node-kind"));
    assertEquals("W", second.get("reopen.move-color"));
    assertEquals("Chinese", second.get("rules.target"));
    assertTrue(Long.parseLong(second.get("rules.revision")) > 0);
    assertSessionTwoEvidence(second);
    assertNotEquals(firstResult.getParent(), secondResult.getParent());
  }

  private static void assertSessionOneEvidence(Map<String, String> records) {
    for (String key :
        Set.of(
            "fixture.path",
            "output.path",
            "protected.path",
            "evidence.commands",
            "evidence.peer-state",
            "evidence.loaded-sgf",
            "evidence.stopped",
            "evidence.quit",
            "evidence.peer-complete",
            "evidence.stdout",
            "evidence.stderr",
            "evidence.app-log",
            "evidence.phases",
            "evidence.lifecycle")) {
      assertTrue(Files.isRegularFile(Path.of(records.get(key))), key + " missing");
    }
    assertFalse(Files.exists(Path.of(records.get("evidence.staged-sgf"))));
  }

  private static void assertSessionTwoEvidence(Map<String, String> records) {
    for (String key :
        Set.of(
            "fixture.path",
            "output.path",
            "protected.path",
            "evidence.stdout",
            "evidence.stderr",
            "evidence.app-log",
            "evidence.phases",
            "evidence.lifecycle")) {
      assertTrue(Files.isRegularFile(Path.of(records.get(key))), key + " missing");
    }
  }

  /** Child-process entry point for both production application sessions and the controlled peer. */
  public static void main(String[] args) throws Exception {
    if (args.length == 2 && "peer".equals(args[0])) {
      ControlledPeer.run(Path.of(args[1]).toAbsolutePath().normalize());
      return;
    }
    int exitCode = 1;
    Path result =
        args.length == 0 ? null : Path.of(args[args.length - 1]).toAbsolutePath().normalize();
    try {
      if (args.length == 4 && "session-1".equals(args[0])) {
        runSessionOne(
            Path.of(args[1]).toAbsolutePath().normalize(),
            Path.of(args[2]).toAbsolutePath().normalize(),
            result);
      } else if (args.length == 6 && "session-2".equals(args[0])) {
        runSessionTwo(
            Path.of(args[1]).toAbsolutePath().normalize(),
            Path.of(args[2]).toAbsolutePath().normalize(),
            Path.of(args[3]).toAbsolutePath().normalize(),
            Path.of(args[4]).toAbsolutePath().normalize(),
            result);
      } else {
        throw new IllegalArgumentException(
            "unexpected D3 child arguments: " + Arrays.toString(args));
      }
      // Exit finishes benchmark cancellation and logging asynchronously. Only the
      // production shutdown may terminate successfully; the parent checks exit 0.
      new CountDownLatch(1).await(30, TimeUnit.SECONDS);
      throw new AssertionError("File -> Exit did not terminate the application within 30 seconds");
    } catch (Throwable failure) {
      if (result != null) {
        Files.writeString(
            result,
            "failure.class="
                + failure.getClass().getName()
                + "\nfailure.message="
                + clean(failure.getMessage())
                + "\n",
            StandardCharsets.UTF_8);
      }
      failure.printStackTrace(System.err);
    } finally {
      System.exit(exitCode);
    }
  }

  private static void runSessionOne(Path fixture, Path work, Path result) throws Exception {
    Files.createDirectories(work);
    Path outputDirectory = Files.createDirectories(work.resolve("保存目录"));
    Path output = outputDirectory.resolve("roundtrip-output.sgf");
    Path protectedFile = outputDirectory.resolve("protected-original.sgf");
    byte[] protectedBytes =
        "(;FF[4]GM[1]CA[UTF-8]SZ[9]C[PROTECTED-原文])\n".getBytes(StandardCharsets.UTF_8);
    Files.write(protectedFile, protectedBytes);
    initializeProfile(work, fixture.getParent());
    System.setProperty("lizzie.work.dir", work.toString());

    Robot robot = robot();
    DesktopProbeProcess.phase(result, "production-startup");
    startApplication(result);
    focusBoard();

    DesktopProbeProcess.phase(result, "file-open-menu");
    openViaChooser(robot, fixture, result);
    Deadline adoption = Deadline.after(Duration.ofSeconds(15));
    await(
        () ->
            Board.boardWidth == 9
                && Board.boardHeight == 9
                && Lizzie.board != null
                && Lizzie.board.getHistory() != null
                && rulesAreChinese(Lizzie.board.getHistory()),
        adoption,
        "history adoption and rules target");
    BoardHistoryList live = Lizzie.board.getHistory();
    BoardHistoryList expected = parseExpected(fixture);
    assertFixtureNamedCoordinates(expected);
    assertSemanticEquality(expected, live);
    BoardHistoryNode branchZeroPass = child(child(child(live.getStart(), 0), 0), 0);
    await(
        () -> live.getCurrentHistoryNode() == branchZeroPass,
        adoption,
        "loadSgfLast branch-zero terminal PASS");

    DesktopProbeProcess.phase(result, "keyboard-branch-navigation");
    focusBoard();
    for (int key :
        List.of(
            KeyEvent.VK_UP,
            KeyEvent.VK_UP,
            KeyEvent.VK_DOWN,
            KeyEvent.VK_RIGHT,
            KeyEvent.VK_DOWN)) {
      press(robot, key);
      Thread.sleep(80);
    }
    BoardHistoryNode selected = child(child(child(live.getStart(), 0), 1), 0);
    await(
        () ->
            live.getCurrentHistoryNode() == selected
                && Lizzie.board.getHistory().getCurrentHistoryNode() == selected,
        Deadline.after(Duration.ofSeconds(15)),
        "branch-one terminal selection");
    boolean boardFocus = edt(() -> Lizzie.frame.mainPanel.isFocusOwner());
    if (!boardFocus) {
      throw new AssertionError("main board did not retain keyboard focus");
    }
    assertEqualsForChild("0/1/0", semanticPath(live.getStart(), selected), "selected path");
    assertEqualsForChild(BoardNodeKind.MOVE, selected.getData().getNodeKind(), "selected kind");
    assertMove(selected, Stone.WHITE, "de");

    DesktopProbeProcess.phase(result, "controlled-engine-switch");
    Path peerRoot = Files.createDirectories(work.resolve("controlled-peer"));
    CatalogNamedLeelaz controlled = installControlledEngine(peerRoot);
    EngineManager manager = Lizzie.engineManager;
    int index = manager.engineList.indexOf(controlled);
    Deadline engineDeadline = Deadline.after(Duration.ofSeconds(20));
    if (!callWithin(
        () -> manager.switchEngineIfAvailable(index, true),
        engineDeadline,
        "production engine switch")) {
      throw new AssertionError("production engine switch rejected controlled catalog entry");
    }
    Path peerPidPath = peerRoot.resolve("peer.pid");
    await(() -> Files.isRegularFile(peerPidPath), engineDeadline, "controlled peer PID");
    long peerPid = Long.parseLong(Files.readString(peerPidPath, StandardCharsets.UTF_8));
    ReaderThreads readers = awaitReaderThreads(engineDeadline);
    Path peerStatePath = peerRoot.resolve("peer-state.txt");
    await(
        () ->
            controlled.isLoaded()
                && manager.isEngineSwitchActive(index, true)
                && rulesConfirmed(controlled)
                && peerSelectedPosition(peerStatePath),
        engineDeadline,
        "rules and selected position confirmation");
    if (live.getCurrentHistoryNode() != selected) {
      throw new AssertionError("engine switch changed the selected history node");
    }

    DesktopProbeProcess.phase(result, "selected-node-analysis");
    Deadline analysisDeadline = Deadline.after(Duration.ofSeconds(10));
    await(
        () -> controlled.isPondering() && positiveAnalysis(selected).isPresent(),
        analysisDeadline,
        "positive visits on selected node");
    assertRulesConfirmedBeforePosition(peerRoot.resolve("commands.log"));
    MoveData received = positiveAnalysis(selected).orElseThrow();
    controlled.togglePonder();
    Path stoppedPath = peerRoot.resolve("stopped.txt");
    Deadline stopDeadline = Deadline.after(Duration.ofSeconds(6));
    await(
        () -> Files.isRegularFile(stoppedPath) && !controlled.isPondering(),
        stopDeadline,
        "controlled analysis stop");
    awaitAnalysisQuiescence(selected, peerStatePath, stopDeadline);
    Map<String, String> stopped = readRecords(stoppedPath, Set.of("command", "analysis.count"));
    Map<String, String> stoppedState = readRecords(peerStatePath, PEER_STATE_KEYS);
    assertEqualsForChild("false", stoppedState.get("analysis.active"), "peer analysis active");
    int emitted = Integer.parseInt(stoppedState.get("analysis.count"));
    if (emitted <= 0) {
      throw new AssertionError("controlled peer emitted no analysis");
    }

    DesktopProbeProcess.phase(result, "save-as-output");
    saveAsViaChooser(robot, output, result);
    await(
        () -> Files.isRegularFile(output),
        Deadline.after(Duration.ofSeconds(15)),
        "saved SGF output");
    assertSemanticEquality(expected, live);

    DesktopProbeProcess.phase(result, "save-as-overwrite-cancel");
    saveAsViaChooserAndCancelOverwrite(robot, protectedFile, result);
    byte[] protectedAfter = Files.readAllBytes(protectedFile);
    if (!Arrays.equals(protectedBytes, protectedAfter)) {
      throw new AssertionError("overwrite cancellation changed protected SGF bytes");
    }

    BoardHistoryList.SessionRulesTarget target = live.captureSessionRules();
    Path stagedSgf = Path.of(stoppedState.get("loaded.path"));
    SessionOneEvidence evidence =
        new SessionOneEvidence(
            fixture,
            output,
            protectedFile,
            sha256(fixture),
            sha256(output),
            sha256(protectedBytes),
            sha256(protectedAfter),
            peerPid,
            readers,
            stagedSgf,
            peerRoot,
            selected,
            received,
            emitted,
            stopped.get("command"),
            target.revision());
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> writeSessionOneShutdownResult(result, evidence), "d3-session-1-result"));

    DesktopProbeProcess.phase(result, "file-exit-menu");
    clickMenuAction(robot, "menu.exit", Duration.ofSeconds(10));
  }

  private static void runSessionTwo(
      Path fixture, Path output, Path protectedFile, Path harnessWork, Path result)
      throws Exception {
    if (!Files.isRegularFile(output) || !Files.isRegularFile(protectedFile)) {
      throw new AssertionError("session two inputs are missing");
    }
    Files.createDirectories(harnessWork);
    Robot robot = robot();
    DesktopProbeProcess.phase(result, "production-startup");
    startApplication(result);

    DesktopProbeProcess.phase(result, "file-reopen-menu");
    openViaChooser(robot, output, result);
    Deadline adoption = Deadline.after(Duration.ofSeconds(15));
    await(
        () ->
            Board.boardWidth == 9
                && Board.boardHeight == 9
                && Lizzie.board != null
                && Lizzie.board.getHistory() != null
                && rulesAreChinese(Lizzie.board.getHistory()),
        adoption,
        "fresh-process history adoption");
    BoardHistoryList expected = parseExpected(fixture);
    BoardHistoryList actual = Lizzie.board.getHistory();
    assertFixtureNamedCoordinates(expected);
    assertSemanticEquality(expected, actual);
    BoardHistoryNode expectedPass = child(child(child(actual.getStart(), 0), 0), 0);
    await(
        () -> actual.getCurrentHistoryNode() == expectedPass,
        adoption,
        "reopened branch-zero terminal PASS");
    BoardData current = expectedPass.getData();
    if (current.getNodeKind() != BoardNodeKind.PASS
        || current.lastMove.isPresent()
        || current.lastMoveColor != Stone.WHITE
        || current.dummy) {
      throw new AssertionError("reopened current node is not the genuine branch-zero W PASS");
    }
    BoardHistoryList.SessionRulesTarget target = actual.captureSessionRules();
    if (target.revision() <= 0
        || target.source() != BoardHistoryList.SessionRulesSource.EXTERNAL_IMPORT) {
      throw new AssertionError("reopened rules target is not associated with the adopted history");
    }
    SessionTwoEvidence evidence =
        new SessionTwoEvidence(
            fixture, output, protectedFile, sha256(fixture), sha256(output), target.revision());
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> writeSessionTwoShutdownResult(result, evidence), "d3-session-2-result"));

    DesktopProbeProcess.phase(result, "file-exit-menu");
    clickMenuAction(robot, "menu.exit", Duration.ofSeconds(10));
  }

  private static void initializeProfile(Path work, Path initialDirectory) throws IOException {
    JSONObject ui =
        new JSONObject()
            .put("autoload-empty", true)
            .put("autoload-default", false)
            .put("autoload-last", false)
            .put("first-time-load", false)
            .put("use-language", 2)
            .put("play-sound", false)
            .put("load-sgf-last", true)
            .put("enable-startup-benchmark", false);
    JSONObject leelaz = new JSONObject().put("engine-settings-list", new JSONArray());
    Files.writeString(
        work.resolve("config.txt"),
        new JSONObject().put("ui", ui).put("leelaz", leelaz).toString(2),
        StandardCharsets.UTF_8);
    Files.writeString(
        work.resolve("persist"),
        new JSONObject()
            .put("ui-persist", new JSONObject())
            .put("filesystem", new JSONObject().put("last-folder", initialDirectory.toString()))
            .toString(2),
        StandardCharsets.UTF_8);
    Files.createDirectories(work.resolve("save"));
  }

  private static void startApplication(Path result) throws Exception {
    Deadline startup = Deadline.after(Duration.ofSeconds(30));
    Lizzie.main(new String[0]);
    await(() -> Lizzie.frame != null && Lizzie.frame.isShowing(), startup, "real main window");
    Thread.sleep(250);
    List<String> dialogs =
        edt(
            () ->
                Arrays.stream(Window.getWindows())
                    .filter(Window::isShowing)
                    .filter(Dialog.class::isInstance)
                    .map(window -> ((Dialog) window).getTitle())
                    .toList());
    if (!dialogs.isEmpty()) {
      throw new AssertionError("unexpected startup dialogs: " + dialogs);
    }
    DesktopProbeProcess.phase(result, "production-startup-complete");
  }

  private static void openViaChooser(Robot robot, Path file, Path result) throws Exception {
    AtomicReference<JFileChooser> chooserRef = new AtomicReference<>();
    clickMenuAction(robot, "files.open", Duration.ofSeconds(15));
    await(
        () -> {
          JFileChooser chooser = visibleChooser();
          chooserRef.set(chooser);
          return chooser != null;
        },
        Deadline.after(Duration.ofSeconds(15)),
        "visible open JFileChooser");
    JFileChooser chooser = chooserRef.get();
    edt(
        () -> {
          Window chooserWindow = SwingUtilities.getWindowAncestor(chooser);
          chooserWindow.addWindowListener(
              new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent event) {
                  Lizzie.frame.toFront();
                  Lizzie.frame.requestFocus();
                  SwingUtilities.invokeLater(
                      () -> {
                        if (!Lizzie.frame.mainPanel.requestFocusInWindow()) {
                          Lizzie.frame.setMainPanelFocus();
                        }
                      });
                }
              });
          return null;
        });
    clickChooserFile(robot, chooser, file.getFileName().toString());
    clickComponent(robot, edt(() -> approveButton(chooser)));
    await(
        () -> !edt(chooser::isShowing),
        Deadline.after(Duration.ofSeconds(15)),
        "open chooser approval");
    DesktopProbeProcess.phase(result, "file-open-approved");
  }

  private static void saveAsViaChooser(Robot robot, Path file, Path result) throws Exception {
    JFileChooser chooser = openSaveChooser(robot);
    JTextField filename = pasteFileName(robot, chooser, file);
    press(robot, KeyEvent.VK_ENTER);
    try {
      await(
          () -> !edt(chooser::isShowing),
          Deadline.after(Duration.ofSeconds(15)),
          "save chooser approval");
    } catch (AssertionError failure) {
      String state =
          edt(() -> "selected=" + chooser.getSelectedFile() + ",field=" + filename.getText());
      throw new AssertionError(failure.getMessage() + "; " + state, failure);
    }
    DesktopProbeProcess.phase(result, "file-save-approved");
  }

  private static void saveAsViaChooserAndCancelOverwrite(Robot robot, Path file, Path result)
      throws Exception {
    JFileChooser chooser = openSaveChooser(robot);
    pasteFileName(robot, chooser, file);
    press(robot, KeyEvent.VK_ENTER);
    AtomicReference<JOptionPane> paneRef = new AtomicReference<>();
    await(
        () -> {
          JOptionPane pane = visibleOptionPane();
          paneRef.set(pane);
          return pane != null;
        },
        Deadline.after(Duration.ofSeconds(15)),
        "overwrite confirmation dialog");
    JButton cancel = edt(() -> cancelButton(paneRef.get()));
    clickComponent(robot, cancel);
    await(
        () -> visibleOptionPane() == null && !edt(chooser::isShowing),
        Deadline.after(Duration.ofSeconds(15)),
        "overwrite cancellation");
    DesktopProbeProcess.phase(result, "overwrite-cancelled");
  }

  private static JFileChooser openSaveChooser(Robot robot) throws Exception {
    clickMenuAction(robot, "files.save-as", Duration.ofSeconds(15));
    AtomicReference<JFileChooser> chooserRef = new AtomicReference<>();
    await(
        () -> {
          JFileChooser chooser = visibleChooser();
          chooserRef.set(chooser);
          return chooser != null;
        },
        Deadline.after(Duration.ofSeconds(15)),
        "visible save JFileChooser");
    return chooserRef.get();
  }

  private static JTextField pasteFileName(Robot robot, JFileChooser chooser, Path file)
      throws Exception {
    AtomicReference<JTextField> filenameRef = new AtomicReference<>();
    await(
        () ->
            edt(
                () -> {
                  JTextField filename = saveFileNameField(chooser);
                  filenameRef.set(filename);
                  return filename != null;
                }),
        Deadline.after(Duration.ofSeconds(15)),
        "enabled visible save filename field");
    JTextField filename = filenameRef.get();
    Toolkit.getDefaultToolkit()
        .getSystemClipboard()
        .setContents(new StringSelection(file.toAbsolutePath().toString()), null);
    clickComponent(robot, filename);
    robot.keyPress(KeyEvent.VK_CONTROL);
    press(robot, KeyEvent.VK_A);
    press(robot, KeyEvent.VK_V);
    robot.keyRelease(KeyEvent.VK_CONTROL);
    robot.delay(100);
    await(
        () -> edt(() -> filename.getText().equals(file.toAbsolutePath().toString())),
        Deadline.after(Duration.ofSeconds(5)),
        "pasted save filename");
    return filename;
  }

  // The standard chooser owns its input; the removed custom save dialog's static field is stale.
  // Match the visible preset filename instead of assuming a look-and-feel-specific widget name.
  // Ambiguous or hidden inputs must fail, never silently switch to programmatic file selection.
  static JTextField saveFileNameField(JFileChooser chooser) {
    java.io.File selected = chooser.getSelectedFile();
    if (selected == null) return null;
    List<JTextField> matching =
        descendants(chooser).stream()
            .filter(JTextField.class::isInstance)
            .map(JTextField.class::cast)
            .filter(Component::isShowing)
            .filter(JTextField::isEnabled)
            .filter(JTextField::isEditable)
            .filter(
                field ->
                    selected.getName().equals(field.getText())
                        || selected.getAbsolutePath().equals(field.getText()))
            .toList();
    return matching.size() == 1 ? matching.get(0) : null;
  }

  private static void clickChooserFile(Robot robot, JFileChooser chooser, String fileName)
      throws Exception {
    AtomicReference<JList<?>> listRef = new AtomicReference<>();
    AtomicReference<Integer> indexRef = new AtomicReference<>();
    await(
        () ->
            edt(
                () -> {
                  for (Component component : descendants(chooser)) {
                    if (!(component instanceof JList<?> list) || !list.isShowing()) {
                      continue;
                    }
                    for (int index = 0; index < list.getModel().getSize(); index++) {
                      Object value = list.getModel().getElementAt(index);
                      String name =
                          value instanceof java.io.File f ? f.getName() : String.valueOf(value);
                      if (fileName.equals(name)) {
                        list.ensureIndexIsVisible(index);
                        listRef.set(list);
                        indexRef.set(index);
                        return true;
                      }
                    }
                  }
                  return false;
                }),
        Deadline.after(Duration.ofSeconds(15)),
        "Unicode fixture row in visible chooser");
    JList<?> list = listRef.get();
    int index = indexRef.get();
    Rectangle cell = edt(() -> list.getCellBounds(index, index));
    Point location = edt(list::getLocationOnScreen);
    click(robot, location.x + cell.x + cell.width / 2, location.y + cell.y + cell.height / 2);
    await(
        () ->
            edt(
                () ->
                    chooser.getSelectedFile() != null
                        && fileName.equals(chooser.getSelectedFile().getName())),
        Deadline.after(Duration.ofSeconds(5)),
        "chooser row selection");
  }

  private static JButton approveButton(JFileChooser chooser) {
    JButton defaultButton = SwingUtilities.getRootPane(chooser).getDefaultButton();
    if (defaultButton != null && defaultButton.isShowing() && defaultButton.isEnabled()) {
      return defaultButton;
    }
    String approveText = chooser.getApproveButtonText();
    return descendants(chooser).stream()
        .filter(JButton.class::isInstance)
        .map(JButton.class::cast)
        .filter(Component::isShowing)
        .filter(JButton::isEnabled)
        .filter(
            button ->
                JFileChooser.APPROVE_SELECTION.equals(button.getActionCommand())
                    || (approveText != null && approveText.equals(button.getText())))
        .findFirst()
        .orElseThrow(() -> new AssertionError("visible chooser approve button not found"));
  }

  private static JButton cancelButton(JOptionPane pane) {
    String cancelText = UIManager.getString("OptionPane.cancelButtonText");
    return descendants(pane).stream()
        .filter(JButton.class::isInstance)
        .map(JButton.class::cast)
        .filter(Component::isShowing)
        .filter(JButton::isEnabled)
        .filter(
            button ->
                button.getText() != null
                    && (button.getText().equals(cancelText)
                        || button.getText().equalsIgnoreCase("cancel")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("visible overwrite Cancel button not found"));
  }

  private static JFileChooser visibleChooser() throws Exception {
    return edt(
        () ->
            Arrays.stream(Window.getWindows())
                .filter(Window::isShowing)
                .flatMap(window -> descendants(window).stream())
                .filter(JFileChooser.class::isInstance)
                .map(JFileChooser.class::cast)
                .filter(Component::isShowing)
                .findFirst()
                .orElse(null));
  }

  private static JOptionPane visibleOptionPane() throws Exception {
    return edt(
        () ->
            Arrays.stream(Window.getWindows())
                .filter(Window::isShowing)
                .flatMap(window -> descendants(window).stream())
                .filter(JOptionPane.class::isInstance)
                .map(JOptionPane.class::cast)
                .filter(Component::isShowing)
                .findFirst()
                .orElse(null));
  }

  private static void clickMenuAction(Robot robot, String action, Duration timeout)
      throws Exception {
    Deadline deadline = Deadline.after(timeout);
    JMenuItem item = edt(() -> findMenuAction(action));
    JMenu parent = edt(() -> topLevelMenuContaining(item));
    AtomicReference<Component> invokerRef = new AtomicReference<>();
    await(
        () -> {
          Component invoker = edt(() -> visibleMenuInvoker(parent));
          invokerRef.set(invoker);
          return invoker != null;
        },
        deadline,
        "visible production menu " + parent.getText());
    clickComponent(robot, invokerRef.get());
    await(() -> edt(item::isShowing), deadline, "visible menu item " + action);
    clickComponent(robot, item);
  }

  private static Component visibleMenuInvoker(JMenu menu) {
    if (menu.isShowing()) {
      return menu;
    }
    WindowMenuStrip strip = Lizzie.frame == null ? null : Lizzie.frame.windowMenuStrip;
    if (strip == null || !strip.isShowing()) {
      return null;
    }
    String text = menu.getText();
    return descendants(strip).stream()
        .filter(JButton.class::isInstance)
        .map(JButton.class::cast)
        .filter(Component::isShowing)
        .filter(JButton::isEnabled)
        .filter(button -> text != null && text.equals(button.getText()))
        .findFirst()
        .orElse(null);
  }

  private static JMenuItem findMenuAction(String action) {
    JMenuBar bar = LizzieFrame.menu;
    if (bar == null) {
      throw new AssertionError("production menu is unavailable");
    }
    for (int index = 0; index < bar.getMenuCount(); index++) {
      JMenuItem found = findMenuAction(bar.getMenu(index), action);
      if (found != null) {
        return found;
      }
    }
    throw new AssertionError("production menu action not found: " + action);
  }

  private static JMenuItem findMenuAction(JMenuItem item, String action) {
    if (action.equals(item.getClientProperty(AutoAnalyzeMenu.ACTION_PROPERTY))) {
      return item;
    }
    if (item instanceof JMenu menu) {
      for (Component child : menu.getMenuComponents()) {
        if (child instanceof JMenuItem menuItem) {
          JMenuItem found = findMenuAction(menuItem, action);
          if (found != null) {
            return found;
          }
        }
      }
    }
    return null;
  }

  private static JMenu topLevelMenuContaining(JMenuItem target) {
    JMenuBar bar = LizzieFrame.menu;
    if (bar != null) {
      for (int index = 0; index < bar.getMenuCount(); index++) {
        JMenu menu = bar.getMenu(index);
        if (containsMenuItem(menu, target)) {
          return menu;
        }
      }
    }
    throw new AssertionError("menu action has no production top-level menu: " + target);
  }

  private static boolean containsMenuItem(JMenuItem item, JMenuItem target) {
    if (item == target) {
      return true;
    }
    if (item instanceof JMenu menu) {
      for (Component child : menu.getMenuComponents()) {
        if (child instanceof JMenuItem menuItem && containsMenuItem(menuItem, target)) {
          return true;
        }
      }
    }
    return false;
  }

  private static void focusBoard() throws Exception {
    edt(
        () -> {
          Lizzie.frame.toFront();
          Lizzie.frame.requestFocus();
          return null;
        });
    try {
      await(
          () -> edt(() -> Lizzie.frame.isFocused()),
          Deadline.after(Duration.ofSeconds(5)),
          "production main window focus");
    } catch (AssertionError failure) {
      throw new AssertionError(failure.getMessage() + "; windows=" + visibleWindowState(), failure);
    }
    edt(
        () -> {
          if (!Lizzie.frame.mainPanel.requestFocusInWindow()) {
            Lizzie.frame.setMainPanelFocus();
          }
          return null;
        });
    await(
        () -> edt(() -> Lizzie.frame.mainPanel.isFocusOwner()),
        Deadline.after(Duration.ofSeconds(5)),
        "main board focus");
  }

  private static List<String> visibleWindowState() throws Exception {
    return edt(
        () ->
            Arrays.stream(Window.getWindows())
                .filter(Window::isShowing)
                .map(
                    window ->
                        window.getClass().getName()
                            + "[title="
                            + (window instanceof Dialog dialog ? dialog.getTitle() : "")
                            + ",text="
                            + (window instanceof HtmlMessage
                                    && ((HtmlMessage) window).getContentPane().getComponentCount()
                                        > 0
                                    && ((HtmlMessage) window).getContentPane().getComponent(0)
                                        instanceof JTextPane textPane
                                ? textPane.getText()
                                : "")
                            + ",focused="
                            + window.isFocused()
                            + ",active="
                            + window.isActive()
                            + ",focusOwner="
                            + window.getFocusOwner()
                            + "]")
                .toList());
  }

  private static void clickComponent(Robot robot, Component component) throws Exception {
    Point point =
        edt(
            () -> {
              if (component == null || !component.isShowing()) {
                throw new AssertionError("component is not showing: " + component);
              }
              Point location = component.getLocationOnScreen();
              return new Point(
                  location.x + Math.max(1, component.getWidth()) / 2,
                  location.y + Math.max(1, component.getHeight()) / 2);
            });
    click(robot, point.x, point.y);
  }

  private static void click(Robot robot, int x, int y) {
    robot.mouseMove(x, y);
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    robot.delay(100);
  }

  private static void press(Robot robot, int key) {
    robot.keyPress(key);
    robot.keyRelease(key);
    robot.delay(100);
  }

  private static Robot robot() throws Exception {
    Robot robot = new Robot();
    robot.setAutoDelay(60);
    return robot;
  }

  private static List<Component> descendants(Container root) {
    List<Component> result = new ArrayList<>();
    for (Component component : root.getComponents()) {
      result.add(component);
      if (component instanceof Container container) {
        result.addAll(descendants(container));
      }
    }
    return result;
  }

  private static CatalogNamedLeelaz installControlledEngine(Path peerRoot) throws Exception {
    Path java =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            "java" + (System.getProperty("os.name", "").startsWith("Windows") ? ".exe" : ""));
    String command =
        quote(java.toString())
            + " -cp "
            + quote(System.getProperty("java.class.path"))
            + " "
            + SgfUiAcceptanceIT.class.getName()
            + " peer "
            + quote(peerRoot.toString());
    EngineManager manager = Lizzie.engineManager;
    int index = manager.engineList.size();
    EngineData entry = new EngineData();
    entry.id = UUID.randomUUID().toString();
    entry.index = index;
    entry.commands = command;
    entry.name = "D3 controlled KataGo";
    entry.preload = false;
    entry.width = 9;
    entry.height = 9;
    entry.isDefault = false;
    entry.komi = 6.5f;
    Utils.saveEngineSettings(new ArrayList<>(List.of(entry)));
    CatalogNamedLeelaz controlled = new CatalogNamedLeelaz(entry);
    SnapshotFileAccessTestBridge.trustDirectLocalSnapshotFileAccessForTest(controlled);
    manager.engineList.add(controlled);
    return controlled;
  }

  private static boolean rulesConfirmed(Leelaz engine) {
    EngineRulesResult result = engine.engineRulesResult();
    KataGoRules chinese = KataGoRules.parse("chinese").orElseThrow();
    return result.status() == EngineRulesResult.Status.CONFIRMED
        && result.observed() != null
        && result.observed().semanticallyEquals(chinese);
  }

  private static void assertPlatformRuntime(Map<String, String> records) {
    assertTrue(records.get("platform.os").startsWith("Linux"));
    assertFalse(records.get("platform.arch").isBlank());
    assertFalse(records.get("runtime.java").isBlank());
    assertTrue(records.get("display.surface").contains("DISPLAY="));
    assertTrue(records.get("display.surface").contains("JFileChooser"));
    assertFalse(records.get("display.surface").contains("DISPLAY=null"));
  }

  private static void assertRulesConfirmedBeforePosition(Path commandsPath) throws IOException {
    List<String> commands = Files.readAllLines(commandsPath, StandardCharsets.UTF_8);
    int initialRulesGet = commandIndex(commands, "kata-get-rules", 0);
    int rulesSet = commandIndex(commands, "kata-set-rules", 0);
    int rulesConfirmed =
        rulesSet < 0 ? initialRulesGet : commandIndex(commands, "kata-get-rules", rulesSet + 1);
    int snapshotLoaded = commandIndex(commands, "loadsgf", rulesConfirmed + 1);
    int analysisStarted = commandIndex(commands, "kata-analyze", snapshotLoaded + 1);
    if (rulesConfirmed < 0 || snapshotLoaded < 0 || analysisStarted < 0) {
      throw new AssertionError(
          "rules/position/analysis command sequence is incomplete: " + commands);
    }
  }

  private static int commandIndex(List<String> commands, String expected, int fromIndex) {
    for (int index = Math.max(0, fromIndex); index < commands.size(); index++) {
      String[] words = commands.get(index).trim().split("\\s+", 3);
      int commandIndex = words.length > 1 && words[0].matches("\\d+") ? 1 : 0;
      if (words.length > commandIndex && words[commandIndex].equals(expected)) {
        return index;
      }
    }
    return -1;
  }

  private static boolean rulesAreChinese(BoardHistoryList history) {
    if (history == null) {
      return false;
    }
    BoardHistoryList.SessionRulesTarget target = history.captureSessionRules();
    KataGoRules chinese = KataGoRules.parse("chinese").orElseThrow();
    return target.kind() == BoardHistoryList.SessionRulesKind.VALID
        && target.source() == BoardHistoryList.SessionRulesSource.EXTERNAL_IMPORT
        && target.revision() > 0
        && "Chinese".equals(target.rawDeclaration())
        && target.parsedRules().map(rules -> rules.semanticallyEquals(chinese)).orElse(false);
  }

  private static boolean peerSelectedPosition(Path path) throws IOException {
    if (!Files.isRegularFile(path)) {
      return false;
    }
    Map<String, String> state = readRecords(path, PEER_STATE_KEYS);
    return state.get("board").equals("9")
        && state.get("komi").equals("6.5")
        && state.get("stones").equals("B:cc,dc,gc;W:dd,de,ee")
        && state.get("turn").equals("B")
        && state.get("tail").equals("W[dd],B[dc],W[de]")
        && state.get("rules.confirmed").equals("true")
        && KataGoRules.parse(state.get("rules"))
            .map(rules -> rules.semanticallyEquals(KataGoRules.parse("chinese").orElseThrow()))
            .orElse(false);
  }

  private static Optional<MoveData> positiveAnalysis(BoardHistoryNode target) {
    if (target != Lizzie.board.getHistory().getCurrentHistoryNode()
        || target.getData().getPlayouts() <= 0
        || target.getData().bestMoves == null) {
      return Optional.empty();
    }
    return target.getData().bestMoves.stream().filter(move -> move.playouts > 0).findFirst();
  }

  private static void awaitAnalysisQuiescence(
      BoardHistoryNode target, Path statePath, Deadline deadline) throws Exception {
    int peerCount = -1;
    int visits = -1;
    long stableSince = -1;
    while (deadline.hasTime()) {
      Map<String, String> state = readRecords(statePath, PEER_STATE_KEYS);
      int currentPeerCount = Integer.parseInt(state.get("analysis.count"));
      int currentVisits = target.getData().getPlayouts();
      if (currentPeerCount != peerCount || currentVisits != visits) {
        peerCount = currentPeerCount;
        visits = currentVisits;
        stableSince = System.nanoTime();
      } else if (stableSince >= 0
          && System.nanoTime() - stableSince >= TimeUnit.MILLISECONDS.toNanos(400)) {
        return;
      }
      TimeUnit.NANOSECONDS.sleep(
          Math.min(TimeUnit.MILLISECONDS.toNanos(25), deadline.remainingNanos()));
    }
    throw new AssertionError("analysis stream did not become quiescent for 400 ms");
  }

  private static ReaderThreads awaitReaderThreads(Deadline deadline) throws Exception {
    AtomicReference<ReaderThreads> found = new AtomicReference<>();
    await(
        () -> {
          Optional<ReaderThreads> readers = findReaderThreads();
          readers.ifPresent(found::set);
          return readers.isPresent();
        },
        deadline,
        "two production reader threads");
    return found.get();
  }

  private static Optional<ReaderThreads> findReaderThreads() {
    Thread stdout = null;
    Thread stderr = null;
    for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
      Thread thread = entry.getKey();
      if (!thread.isAlive()) {
        continue;
      }
      boolean readsStdout = false;
      boolean readsStderr = false;
      for (StackTraceElement frame : entry.getValue()) {
        if (!frame.getClassName().equals(Leelaz.class.getName())) {
          continue;
        }
        readsStdout |= frame.getMethodName().equals("read");
        readsStderr |= frame.getMethodName().equals("readError");
      }
      if (readsStdout) {
        if (stdout != null && stdout != thread) {
          return Optional.empty();
        }
        stdout = thread;
      }
      if (readsStderr) {
        if (stderr != null && stderr != thread) {
          return Optional.empty();
        }
        stderr = thread;
      }
    }
    return stdout == null || stderr == null || stdout == stderr
        ? Optional.empty()
        : Optional.of(new ReaderThreads(stdout, stderr));
  }

  private static BoardHistoryList parseExpected(Path fixture) throws IOException {
    BoardHistoryList expected =
        SGFParser.parseSgf(Files.readString(fixture, StandardCharsets.UTF_8), true);
    if (expected == null) {
      throw new AssertionError("fixture did not parse");
    }
    return expected;
  }

  private static void assertFixtureNamedCoordinates(BoardHistoryList history) {
    BoardHistoryNode root = history.getStart();
    if (root.getData().getNodeKind() != BoardNodeKind.SNAPSHOT) {
      throw new AssertionError("fixture root is not a setup SNAPSHOT");
    }
    requireStone(root.getData(), "cc", Stone.BLACK);
    requireStone(root.getData(), "gc", Stone.BLACK);
    requireStone(root.getData(), "ee", Stone.WHITE);
    if (root.getData().blackToPlay) {
      throw new AssertionError("fixture root PL[W] was not adopted");
    }
    BoardHistoryNode first = child(root, 0);
    assertMove(first, Stone.WHITE, "dd");
    if (first.numberOfChildren() != 2) {
      throw new AssertionError("fixture branch point does not have two ordered branches");
    }
    BoardHistoryNode branchZero = child(first, 0);
    assertMove(branchZero, Stone.BLACK, "cd");
    BoardHistoryNode pass = child(branchZero, 0);
    if (pass.getData().getNodeKind() != BoardNodeKind.PASS
        || pass.getData().lastMoveColor != Stone.WHITE
        || pass.getData().lastMove.isPresent()
        || pass.getData().dummy) {
      throw new AssertionError("fixture branch zero terminal is not a genuine W PASS");
    }
    BoardHistoryNode branchOne = child(first, 1);
    assertMove(branchOne, Stone.BLACK, "dc");
    assertEqualsForChild("dc:甲", branchOne.getData().getProperty("LB"), "branch LB");
    assertMove(child(branchOne, 0), Stone.WHITE, "de");
  }

  private static void assertSemanticEquality(BoardHistoryList expected, BoardHistoryList actual) {
    if (Board.boardWidth != 9 || Board.boardHeight != 9) {
      throw new AssertionError("adopted board size is not 9x9");
    }
    if (Double.compare(expected.getGameInfo().getKomi(), actual.getGameInfo().getKomi()) != 0
        || expected.getGameInfo().getHandicap() != actual.getGameInfo().getHandicap()
        || !expected.getGameInfo().getPlayerBlack().equals(actual.getGameInfo().getPlayerBlack())
        || !expected.getGameInfo().getPlayerWhite().equals(actual.getGameInfo().getPlayerWhite())) {
      throw new AssertionError("game metadata changed during UI round-trip");
    }
    assertNodeSemanticsEqual(expected.getStart(), actual.getStart());
    if (!rulesAreChinese(actual)) {
      throw new AssertionError("adopted history lost its immutable Chinese session rules target");
    }
  }

  private static void assertNodeSemanticsEqual(BoardHistoryNode expected, BoardHistoryNode actual) {
    BoardData left = expected.getData();
    BoardData right = actual.getData();
    assertEqualsForChild(left.getNodeKind(), right.getNodeKind(), "node kind");
    assertEqualsForChild(left.moveNumber, right.moveNumber, "move number");
    assertEqualsForChild(left.lastMoveColor, right.lastMoveColor, "move color");
    assertEqualsForChild(left.blackToPlay, right.blackToPlay, "side to play");
    assertEqualsForChild(left.blackCaptures, right.blackCaptures, "black captures");
    assertEqualsForChild(left.whiteCaptures, right.whiteCaptures, "white captures");
    assertEqualsForChild(left.dummy, right.dummy, "dummy flag");
    assertEqualsForChild(
        normalizeComment(left.comment), normalizeComment(right.comment), "comment");
    if (left.lastMove.isPresent() != right.lastMove.isPresent()
        || (left.lastMove.isPresent()
            && !Arrays.equals(left.lastMove.orElseThrow(), right.lastMove.orElseThrow()))) {
      throw new AssertionError("last move changed during UI round-trip");
    }
    if (!Arrays.equals(left.stones, right.stones)) {
      throw new AssertionError("complete stones changed during UI round-trip");
    }
    for (String key : List.of("AB", "AW", "PL", "LB", "XX", "GN", "RU")) {
      String expectedValue = left.getProperty(key);
      if (expectedValue != null && !expectedValue.equals(right.getProperty(key))) {
        throw new AssertionError("property " + key + " changed during UI round-trip");
      }
    }
    if (expected.numberOfChildren() != actual.numberOfChildren()) {
      throw new AssertionError("ordered variation count changed during UI round-trip");
    }
    for (int index = 0; index < expected.numberOfChildren(); index++) {
      assertNodeSemanticsEqual(child(expected, index), child(actual, index));
    }
  }

  private static BoardHistoryNode child(BoardHistoryNode parent, int index) {
    return parent
        .getVariation(index)
        .orElseThrow(() -> new AssertionError("missing variation " + index));
  }

  private static void assertMove(BoardHistoryNode node, Stone color, String coordinate) {
    BoardData data = node.getData();
    int[] expected = {coordinate.charAt(0) - 'a', coordinate.charAt(1) - 'a'};
    if (data.getNodeKind() != BoardNodeKind.MOVE
        || data.lastMoveColor != color
        || data.lastMove.isEmpty()
        || !Arrays.equals(expected, data.lastMove.orElseThrow())) {
      throw new AssertionError("expected " + color + "[" + coordinate + "]");
    }
  }

  private static void requireStone(BoardData data, String coordinate, Stone expected) {
    int x = coordinate.charAt(0) - 'a';
    int y = coordinate.charAt(1) - 'a';
    Stone actual = data.stones[Board.getIndex(x, y)];
    if (actual != expected) {
      throw new AssertionError(coordinate + " expected " + expected + " but was " + actual);
    }
  }

  private static String semanticPath(BoardHistoryNode root, BoardHistoryNode target) {
    if (root == target) {
      return "root";
    }
    List<Integer> path = new ArrayList<>();
    if (!findPath(root, target, path)) {
      throw new AssertionError("target node is outside the adopted history");
    }
    return path.stream()
        .map(String::valueOf)
        .reduce((left, right) -> left + "/" + right)
        .orElseThrow();
  }

  private static boolean findPath(
      BoardHistoryNode current, BoardHistoryNode target, List<Integer> path) {
    for (int index = 0; index < current.numberOfChildren(); index++) {
      path.add(index);
      BoardHistoryNode child = child(current, index);
      if (child == target || findPath(child, target, path)) {
        return true;
      }
      path.remove(path.size() - 1);
    }
    return false;
  }

  private static String normalizeComment(String value) {
    String comment = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    return SGFParser.splitWinrateComment(comment).personalComment;
  }

  private static void writeSessionOneShutdownResult(Path result, SessionOneEvidence evidence) {
    try {
      Path quit = evidence.peerRoot.resolve("quit.txt");
      Path complete = evidence.peerRoot.resolve("peer-complete.txt");
      waitUntil(
          () ->
              Files.isRegularFile(quit)
                  && Files.isRegularFile(complete)
                  && !ProcessHandle.of(evidence.peerPid).map(ProcessHandle::isAlive).orElse(false)
                  && evidence.readers.terminated()
                  && !Files.exists(evidence.stagedSgf),
          Duration.ofSeconds(10));
      boolean processDead =
          !ProcessHandle.of(evidence.peerPid).map(ProcessHandle::isAlive).orElse(false);
      boolean readersDead = evidence.readers.terminated();
      boolean stagedGone = !Files.exists(evidence.stagedSgf);
      boolean quitComplete = Files.isRegularFile(quit) && Files.isRegularFile(complete);
      Map<String, String> records = new LinkedHashMap<>();
      records.put("scenario", "d3-sgf-ui-session-1");
      records.put("source.sha256", evidence.sourceSha);
      records.put("peer.kind", "controlled-java");
      putPlatformRuntime(records);
      records.put("fixture.path", evidence.fixture.toString());
      records.put("output.path", evidence.output.toString());
      records.put("output.sha256", evidence.outputSha);
      records.put("protected.path", evidence.protectedFile.toString());
      records.put("protected.before.sha256", evidence.protectedBeforeSha);
      records.put("protected.after.sha256", evidence.protectedAfterSha);
      records.put("peer.pid", Long.toString(evidence.peerPid));
      records.put("open.menu", "true");
      records.put("open.chooser", "true");
      records.put("history.adopted", "true");
      records.put("rules.confirmed", "true");
      records.put("rules.confirmed-before-position", "true");
      records.put("rules.target", "Chinese");
      records.put("rules.revision", Long.toString(evidence.rulesRevision));
      records.put("position.confirmed", "true");
      records.put("position.board", "9x9");
      records.put("position.komi", "6.5");
      records.put("position.stones", "B:cc,dc,gc;W:dd,de,ee");
      records.put("position.turn", "B");
      records.put("position.tail", "W[dd],B[dc],W[de]");
      records.put("navigation.keys", "true");
      records.put("navigation.focus", "true");
      records.put("selected-node-before-save", "0/1/0");
      records.put("selected-node.kind", evidence.selected.getData().getNodeKind().name());
      records.put("analysis.current-node", "true");
      records.put("analysis.positive-visits", "true");
      records.put("analysis.move", evidence.move.coordinate);
      records.put("analysis.visits", Integer.toString(evidence.move.playouts));
      records.put("analysis.emitted", Integer.toString(evidence.emitted));
      records.put("analysis.quiet-400ms", "true");
      records.put("save.menu", "true");
      records.put("save.chooser", "true");
      records.put("semantic.before-save", "true");
      records.put("overwrite.cancel", "true");
      records.put("protected.unchanged", "true");
      records.put("stop", "true");
      records.put("stop.command", evidence.stopCommand);
      records.put("quit", Boolean.toString(quitComplete));
      records.put("cleanup.process", Boolean.toString(processDead));
      records.put("cleanup.readers", Boolean.toString(readersDead));
      records.put("cleanup.sgf", Boolean.toString(stagedGone));
      records.put("evidence.commands", evidence.peerRoot.resolve("commands.log").toString());
      records.put("evidence.peer-state", evidence.peerRoot.resolve("peer-state.txt").toString());
      records.put("evidence.loaded-sgf", evidence.peerRoot.resolve("loaded.sgf").toString());
      records.put("evidence.stopped", evidence.peerRoot.resolve("stopped.txt").toString());
      records.put("evidence.quit", quit.toString());
      records.put("evidence.peer-complete", complete.toString());
      records.put("evidence.stdout", result.resolveSibling("stdout.log").toString());
      records.put("evidence.stderr", result.resolveSibling("stderr.log").toString());
      records.put("evidence.app-log", result.getParent().resolve("work/logs/app.log").toString());
      records.put("evidence.staged-sgf", evidence.stagedSgf.toString());
      records.put("evidence.phases", result.resolveSibling("phases.log").toString());
      records.put("evidence.lifecycle", result.resolveSibling("lifecycle.txt").toString());
      writeResult(result, records, SESSION_ONE_KEYS);
    } catch (Throwable failure) {
      failure.printStackTrace(System.err);
      writeShutdownFailure(result, failure);
    }
  }

  private static void writeSessionTwoShutdownResult(Path result, SessionTwoEvidence evidence) {
    try {
      Map<String, String> records = new LinkedHashMap<>();
      records.put("scenario", "d3-sgf-ui-session-2");
      records.put("source.sha256", evidence.sourceSha);
      records.put("peer.kind", "none");
      putPlatformRuntime(records);
      records.put("fixture.path", evidence.fixture.toString());
      records.put("output.path", evidence.output.toString());
      records.put("output.sha256", evidence.outputSha);
      records.put("protected.path", evidence.protectedFile.toString());
      records.put("open.menu", "true");
      records.put("open.chooser", "true");
      records.put("history.adopted", "true");
      records.put("semantic.tree", "true");
      records.put("semantic.metadata", "true");
      records.put("semantic.setup", "true");
      records.put("semantic.comments", "true");
      records.put("semantic.markup", "true");
      records.put("semantic.rules", "true");
      records.put("rules.target", "Chinese");
      records.put("rules.revision", Long.toString(evidence.rulesRevision));
      records.put("reopen-current-node", "0/0/0");
      records.put("reopen.current-node", "true");
      records.put("reopen.node-kind", "PASS");
      records.put("reopen.move-color", "W");
      records.put("quit", "true");
      records.put("cleanup.process", "true");
      records.put("cleanup.readers", "true");
      records.put("cleanup.sgf", "true");
      records.put("evidence.stdout", result.resolveSibling("stdout.log").toString());
      records.put("evidence.stderr", result.resolveSibling("stderr.log").toString());
      records.put(
          "evidence.app-log",
          Path.of(System.getProperty("lizzie.work.dir")).resolve("logs/app.log").toString());
      records.put("evidence.phases", result.resolveSibling("phases.log").toString());
      records.put("evidence.lifecycle", result.resolveSibling("lifecycle.txt").toString());
      writeResult(result, records, SESSION_TWO_KEYS);
    } catch (Throwable failure) {
      failure.printStackTrace(System.err);
      writeShutdownFailure(result, failure);
    }
  }

  private static void putPlatformRuntime(Map<String, String> records) {
    records.put(
        "platform.os",
        System.getProperty("os.name", "unknown")
            + " "
            + System.getProperty("os.version", "unknown"));
    records.put("platform.arch", System.getProperty("os.arch", "unknown"));
    records.put(
        "runtime.java",
        System.getProperty("java.runtime.name", "unknown")
            + " "
            + System.getProperty("java.runtime.version", "unknown"));
    records.put(
        "display.surface",
        "DISPLAY="
            + System.getenv("DISPLAY")
            + ";toolkit="
            + Toolkit.getDefaultToolkit().getClass().getName()
            + ";chooser=JFileChooser");
  }

  private static void writeShutdownFailure(Path result, Throwable failure) {
    try {
      Files.writeString(
          result, "shutdown.failure=" + clean(failure.toString()) + "\n", StandardCharsets.UTF_8);
    } catch (IOException ignored) {
      failure.addSuppressed(ignored);
    }
  }

  private static Map<String, String> parseResult(Path result, Set<String> expectedKeys)
      throws IOException {
    return readRecords(result, expectedKeys);
  }

  private static Map<String, String> readRecords(Path path, Set<String> expectedKeys)
      throws IOException {
    Map<String, String> records = new LinkedHashMap<>();
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      int separator = line.indexOf('=');
      if (separator <= 0 || separator == line.length() - 1) {
        throw new AssertionError("malformed record in " + path + ": " + line);
      }
      String key = line.substring(0, separator);
      String value = line.substring(separator + 1);
      if (!key.matches("[a-z][a-z0-9.-]*")) {
        throw new AssertionError("malformed record key in " + path + ": " + key);
      }
      if (!expectedKeys.contains(key)) {
        throw new AssertionError("unknown record key in " + path + ": " + key);
      }
      if (records.putIfAbsent(key, value) != null) {
        throw new AssertionError("duplicate record key in " + path + ": " + key);
      }
    }
    Set<String> missing = new LinkedHashSet<>(expectedKeys);
    missing.removeAll(records.keySet());
    if (!missing.isEmpty()) {
      throw new AssertionError("missing record keys in " + path + ": " + missing);
    }
    return records;
  }

  private static void writeResult(Path path, Map<String, String> records, Set<String> schema)
      throws IOException {
    if (!records.keySet().equals(schema)) {
      throw new AssertionError("child result schema mismatch: " + records.keySet());
    }
    StringBuilder text = new StringBuilder();
    records.forEach(
        (key, value) -> {
          if (value == null
              || value.isEmpty()
              || value.indexOf('\n') >= 0
              || value.indexOf('\r') >= 0) {
            throw new AssertionError("invalid child result value: " + key);
          }
          text.append(key).append('=').append(value).append('\n');
        });
    Files.writeString(path, text, StandardCharsets.UTF_8);
  }

  private static Set<String> orderedSet(String... keys) {
    return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(List.of(keys)));
  }

  private static String sha256(Path path) throws Exception {
    return sha256(Files.readAllBytes(path));
  }

  private static String sha256(byte[] bytes) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder hex = new StringBuilder();
    for (byte value : digest) {
      hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
    }
    return hex.toString();
  }

  private static void waitUntil(BooleanSupplier condition, Duration duration) {
    long deadline = System.nanoTime() + duration.toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      try {
        Thread.sleep(25);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static void await(CheckedCondition condition, Deadline deadline, String description)
      throws Exception {
    Throwable lastFailure = null;
    while (deadline.hasTime()) {
      try {
        if (condition.test()) {
          return;
        }
        lastFailure = null;
      } catch (IOException | IllegalArgumentException failure) {
        lastFailure = failure;
      }
      TimeUnit.NANOSECONDS.sleep(
          Math.min(TimeUnit.MILLISECONDS.toNanos(25), deadline.remainingNanos()));
    }
    AssertionError error = new AssertionError("Timed out awaiting " + description);
    if (lastFailure != null) {
      error.initCause(lastFailure);
    }
    throw error;
  }

  private static <T> T callWithin(CheckedSupplier<T> action, Deadline deadline, String description)
      throws Exception {
    FutureTask<T> task = new FutureTask<>(action::get);
    Thread worker = new Thread(task, "d3-sgf-ui-" + description.replace(' ', '-'));
    worker.setDaemon(true);
    worker.start();
    try {
      return task.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException failure) {
      task.cancel(true);
      throw new AssertionError("Timed out awaiting " + description, failure);
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof Exception exception) {
        throw exception;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new RuntimeException(cause);
    }
  }

  private static <T> T edt(Callable<T> action) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) {
      return action.call();
    }
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeLater(task);
    try {
      return task.get(10, TimeUnit.SECONDS);
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof Exception exception) {
        throw exception;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new RuntimeException(cause);
    }
  }

  private static String quote(String value) {
    return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  private static String clean(String value) {
    return value == null ? "unspecified failure" : value.replace('\n', ' ').replace('\r', ' ');
  }

  private static void assertEqualsForChild(Object expected, Object actual, String description) {
    if (!java.util.Objects.equals(expected, actual)) {
      throw new AssertionError(description + " expected " + expected + " but was " + actual);
    }
  }

  @FunctionalInterface
  private interface CheckedCondition {
    boolean test() throws Exception;
  }

  @FunctionalInterface
  private interface CheckedSupplier<T> {
    T get() throws Exception;
  }

  private record Deadline(long nanoTime) {
    static Deadline after(Duration duration) {
      return new Deadline(System.nanoTime() + duration.toNanos());
    }

    boolean hasTime() {
      return remainingNanos() > 0;
    }

    long remainingNanos() {
      return Math.max(0, nanoTime - System.nanoTime());
    }
  }

  private record ReaderThreads(Thread stdout, Thread stderr) {
    boolean terminated() {
      return !stdout.isAlive() && !stderr.isAlive();
    }
  }

  private record SessionOneEvidence(
      Path fixture,
      Path output,
      Path protectedFile,
      String sourceSha,
      String outputSha,
      String protectedBeforeSha,
      String protectedAfterSha,
      long peerPid,
      ReaderThreads readers,
      Path stagedSgf,
      Path peerRoot,
      BoardHistoryNode selected,
      MoveData move,
      int emitted,
      String stopCommand,
      long rulesRevision) {}

  private record SessionTwoEvidence(
      Path fixture,
      Path output,
      Path protectedFile,
      String sourceSha,
      String outputSha,
      long rulesRevision) {}

  /** Controlled catalog entry whose application-exit shutdown performs a graceful GTP quit. */
  private static final class CatalogNamedLeelaz extends Leelaz {
    private final String catalogName;

    private CatalogNamedLeelaz(EngineData data) throws Exception {
      super(data.commands);
      catalogName = data.name;
      savedEntryId = data.id;
      preload = data.preload;
      width = data.width;
      height = data.height;
      oriWidth = data.width;
      oriHeight = data.height;
      komi = data.komi;
      orikomi = data.komi;
      useJavaSSH = data.useJavaSSH;
      ip = data.ip;
      port = data.port;
      useKeyGen = data.useKeyGen;
      keyGenPath = data.keyGenPath;
      userName = data.userName;
      password = data.password;
      initialCommand = data.initialCommand;
      gtpConfigurationProtocol = data.gtpConfigurationProtocol;
      gtpConfigurationProfile =
          data.gtpConfigurationProfile == null
              ? null
              : new JSONObject(data.gtpConfigurationProfile.toString());
    }

    @Override
    public String getEngineName(int index) {
      return catalogName;
    }

    @Override
    public void forceQuit() {
      normalQuit();
    }
  }

  /** Deterministic Java GTP process used through the production catalog and engine manager. */
  public static final class ControlledPeer {
    private static final Pattern COMMAND =
        Pattern.compile("^(?:(\\d+)\\s+)?([a-z][a-z0-9_-]*)(?:\\s+(.*))?$");
    private static final String GTP_COLUMNS = "ABCDEFGHJKLMNOPQRSTUVWXYZ";
    private static final Set<String> COMMANDS =
        orderedSet(
            "protocol_version",
            "name",
            "version",
            "list_commands",
            "known_command",
            "boardsize",
            "komi",
            "clear_board",
            "loadsgf",
            "play",
            "kata-get-rules",
            "kata-set-rules",
            "kata-get-param",
            "kata-analyze",
            "stop",
            "quit");
    private static final Object OUTPUT_LOCK = new Object();
    private static final TreeMap<String, String> stones = new TreeMap<>();
    private static final List<String> tail = new ArrayList<>();
    private static Path root;
    private static BufferedWriter output;
    private static Thread analysisThread;
    private static int boardSize = 9;
    private static double komi = 6.5;
    private static String turn = "B";
    private static String rules = "chinese";
    private static boolean freshRulesConfirmed;
    private static boolean analysisActive;
    private static int analysisCount;
    private static String stopCommand = "none";
    private static Path loadedPath;

    private ControlledPeer() {}

    static void run(Path evidenceRoot) throws Exception {
      root = evidenceRoot;
      Files.createDirectories(root);
      writeAtomic(root.resolve("peer.pid"), Long.toString(ProcessHandle.current().pid()));
      startWatchdog();
      try (BufferedReader input =
              new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
          BufferedWriter writer =
              new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
        output = writer;
        analysisThread = new Thread(ControlledPeer::analysisLoop, "d3-controlled-analysis");
        analysisThread.setDaemon(true);
        analysisThread.start();
        boolean quit = false;
        String line;
        while (!quit && (line = input.readLine()) != null) {
          Files.writeString(
              root.resolve("commands.log"),
              line + "\n",
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND);
          Matcher matcher = COMMAND.matcher(line.trim());
          if (!matcher.matches()) {
            respond(false, "", "malformed command");
            continue;
          }
          String id = matcher.group(1) == null ? "" : matcher.group(1);
          String command = matcher.group(2);
          String arguments = matcher.group(3) == null ? "" : matcher.group(3).trim();
          PeerResult result = handle(command, arguments);
          respond(result.success, id, result.payload);
          if (result.afterResponse != null) {
            result.afterResponse.run();
          }
          quit = result.quit;
        }
        synchronized (OUTPUT_LOCK) {
          analysisActive = false;
        }
        analysisThread.interrupt();
        analysisThread.join(1000);
        if (quit) {
          writeAtomic(root.resolve("peer-complete.txt"), "natural\n");
        }
      }
    }

    private static PeerResult handle(String command, String arguments) {
      try {
        return switch (command) {
          case "protocol_version" -> noArguments(arguments, "2");
          case "name" -> name(arguments);
          case "version" -> noArguments(arguments, "1.18.1-d3-controlled");
          case "list_commands" -> noArguments(arguments, String.join("\n", COMMANDS));
          case "known_command" ->
              success(Boolean.toString(COMMANDS.contains(singleWord(arguments))));
          case "boardsize" -> boardsize(arguments);
          case "komi" -> komi(arguments);
          case "clear_board" -> clearBoard(arguments);
          case "loadsgf" -> loadSgf(arguments);
          case "play" -> play(arguments);
          case "kata-set-rules" -> setRules(arguments);
          case "kata-get-rules" -> getRules(arguments);
          case "kata-get-param" -> getParam(arguments);
          case "kata-analyze" -> analyze(arguments);
          case "stop" -> stop(arguments, "stop");
          case "quit" -> quit(arguments);
          default -> failure("unsupported command: " + command);
        };
      } catch (Exception failure) {
        return failure(clean(failure.getMessage()));
      }
    }

    private static PeerResult name(String arguments) {
      requireNoArguments(arguments);
      boolean stopped;
      synchronized (OUTPUT_LOCK) {
        stopped = analysisActive;
        if (stopped) {
          analysisActive = false;
          stopCommand = "name";
        }
      }
      return new PeerResult(true, "KataGo", stopped ? ControlledPeer::recordStop : null, false);
    }

    private static PeerResult boardsize(String arguments) {
      int value = Integer.parseInt(singleWord(arguments));
      if (value < 2 || value > GTP_COLUMNS.length()) {
        throw new IllegalArgumentException("unsupported boardsize");
      }
      synchronized (OUTPUT_LOCK) {
        boardSize = value;
        stones.clear();
        tail.clear();
        turn = "B";
        writeState();
      }
      return success("");
    }

    private static PeerResult komi(String arguments) {
      double value = Double.parseDouble(singleWord(arguments));
      if (!Double.isFinite(value)) {
        throw new IllegalArgumentException("invalid komi");
      }
      synchronized (OUTPUT_LOCK) {
        komi = value;
        writeState();
      }
      return success("");
    }

    private static PeerResult clearBoard(String arguments) {
      requireNoArguments(arguments);
      synchronized (OUTPUT_LOCK) {
        stones.clear();
        tail.clear();
        turn = "B";
        writeState();
      }
      return success("");
    }

    private static PeerResult loadSgf(String arguments) throws IOException {
      requireFreshRulesConfirmation();
      Path path = Path.of(singleWord(arguments));
      if (!path.isAbsolute()) {
        path = Path.of("").toAbsolutePath().resolve(path);
      }
      path = path.normalize().toRealPath();
      String text = Files.readString(path, StandardCharsets.UTF_8);
      Snapshot snapshot = parseSnapshot(text);
      synchronized (OUTPUT_LOCK) {
        boardSize = snapshot.boardSize;
        komi = snapshot.komi;
        turn = snapshot.turn;
        stones.clear();
        stones.putAll(snapshot.stones);
        tail.clear();
        loadedPath = path;
        writeAtomic(root.resolve("loaded.sgf"), text);
        writeAtomic(root.resolve("loaded.path"), path.toString());
        writeState();
      }
      return success("");
    }

    private static PeerResult play(String arguments) {
      requireFreshRulesConfirmation();
      String[] words = arguments.split("\\s+");
      if (words.length != 2) {
        throw new IllegalArgumentException("play requires color and move");
      }
      String color = words[0].toUpperCase(Locale.ROOT);
      synchronized (OUTPUT_LOCK) {
        if (!color.equals(turn)) {
          throw new IllegalArgumentException("move color does not match side to play");
        }
        String move;
        if (words[1].equalsIgnoreCase("pass")) {
          move = color + "[]";
        } else {
          String coordinate = gtpToSgf(words[1]);
          if (stones.putIfAbsent(coordinate, color) != null) {
            throw new IllegalArgumentException("move occupies an existing stone");
          }
          move = color + "[" + coordinate + "]";
        }
        tail.add(move);
        turn = color.equals("B") ? "W" : "B";
        writeState();
      }
      return success("");
    }

    private static PeerResult setRules(String arguments) {
      if (arguments.isBlank() || KataGoRules.parse(arguments).isEmpty()) {
        throw new IllegalArgumentException("invalid rules");
      }
      synchronized (OUTPUT_LOCK) {
        rules = arguments;
        freshRulesConfirmed = false;
        writeState();
      }
      return success("");
    }

    private static PeerResult getRules(String arguments) {
      requireNoArguments(arguments);
      synchronized (OUTPUT_LOCK) {
        freshRulesConfirmed = true;
        writeState();
        return success(rules);
      }
    }

    private static void requireFreshRulesConfirmation() {
      synchronized (OUTPUT_LOCK) {
        if (!freshRulesConfirmed) {
          throw new IllegalStateException(
              "position restore attempted before fresh rules confirmation");
        }
      }
    }

    private static PeerResult getParam(String arguments) {
      return switch (singleWord(arguments)) {
        case "playoutDoublingAdvantage" -> success("0");
        case "analysisWideRootNoise" -> success("false");
        default -> failure("unsupported kata parameter");
      };
    }

    private static PeerResult analyze(String arguments) {
      requireFreshRulesConfirmation();
      if (arguments.isBlank()) {
        throw new IllegalArgumentException("kata-analyze requires arguments");
      }
      return new PeerResult(
          true,
          "",
          () -> {
            synchronized (OUTPUT_LOCK) {
              analysisActive = true;
              writeState();
            }
          },
          false);
    }

    private static PeerResult stop(String arguments, String command) {
      requireNoArguments(arguments);
      synchronized (OUTPUT_LOCK) {
        analysisActive = false;
        stopCommand = command;
      }
      return new PeerResult(true, "", ControlledPeer::recordStop, false);
    }

    private static PeerResult quit(String arguments) {
      requireNoArguments(arguments);
      synchronized (OUTPUT_LOCK) {
        analysisActive = false;
      }
      return new PeerResult(
          true,
          "",
          () -> {
            writeAtomic(root.resolve("quit.txt"), "graceful\n");
            writeState();
          },
          true);
    }

    private static void analysisLoop() {
      try {
        while (!Thread.currentThread().isInterrupted()) {
          Thread.sleep(75);
          synchronized (OUTPUT_LOCK) {
            if (!analysisActive || output == null) {
              continue;
            }
            int visits = 10 + analysisCount;
            output.write(
                "info move D4 visits "
                    + visits
                    + " winrate 0.55 scoreMean 1.0 scoreStdev 2.0 prior 0.1 lcb 0.5 order 0 pv D4\n");
            output.flush();
            analysisCount++;
            writeState();
          }
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }

    private static Snapshot parseSnapshot(String text) {
      if (!text.startsWith("(;") || !text.endsWith(")")) {
        throw new IllegalArgumentException("snapshot must be one SGF node");
      }
      Map<String, List<String>> properties = parseProperties(text.substring(2, text.length() - 1));
      int size = Integer.parseInt(singleProperty(properties, "SZ"));
      double snapshotKomi = Double.parseDouble(singleProperty(properties, "KM"));
      String snapshotTurn = singleProperty(properties, "PL");
      TreeMap<String, String> snapshotStones = new TreeMap<>();
      addSetup(properties.getOrDefault("AB", List.of()), "B", size, snapshotStones);
      addSetup(properties.getOrDefault("AW", List.of()), "W", size, snapshotStones);
      for (String removed : properties.getOrDefault("AE", List.of())) {
        snapshotStones.remove(removed);
      }
      return new Snapshot(size, snapshotKomi, snapshotTurn, snapshotStones);
    }

    private static Map<String, List<String>> parseProperties(String body) {
      Map<String, List<String>> properties = new LinkedHashMap<>();
      int offset = 0;
      while (offset < body.length()) {
        while (offset < body.length() && Character.isWhitespace(body.charAt(offset))) {
          offset++;
        }
        int start = offset;
        while (offset < body.length() && Character.isUpperCase(body.charAt(offset))) {
          offset++;
        }
        if (start == offset) {
          throw new IllegalArgumentException("malformed SGF property");
        }
        String key = body.substring(start, offset);
        List<String> values = properties.computeIfAbsent(key, ignored -> new ArrayList<>());
        while (offset < body.length() && body.charAt(offset) == '[') {
          offset++;
          StringBuilder value = new StringBuilder();
          boolean escaped = false;
          while (offset < body.length()) {
            char current = body.charAt(offset++);
            if (escaped) {
              value.append(current);
              escaped = false;
            } else if (current == '\\') {
              escaped = true;
            } else if (current == ']') {
              break;
            } else {
              value.append(current);
            }
          }
          values.add(value.toString());
        }
      }
      return properties;
    }

    private static String singleProperty(Map<String, List<String>> properties, String key) {
      List<String> values = properties.get(key);
      if (values == null || values.size() != 1 || values.get(0).isBlank()) {
        throw new IllegalArgumentException("missing or duplicate SGF property " + key);
      }
      return values.get(0);
    }

    private static void addSetup(
        List<String> coordinates, String color, int size, Map<String, String> target) {
      for (String coordinate : coordinates) {
        if (coordinate.length() != 2
            || coordinate.charAt(0) - 'a' < 0
            || coordinate.charAt(0) - 'a' >= size
            || coordinate.charAt(1) - 'a' < 0
            || coordinate.charAt(1) - 'a' >= size
            || target.putIfAbsent(coordinate, color) != null) {
          throw new IllegalArgumentException("invalid setup coordinate " + coordinate);
        }
      }
    }

    private static String gtpToSgf(String value) {
      String coordinate = value.toUpperCase(Locale.ROOT);
      int x = coordinate.isEmpty() ? -1 : GTP_COLUMNS.indexOf(coordinate.charAt(0));
      int row;
      try {
        row = Integer.parseInt(coordinate.substring(1));
      } catch (RuntimeException failure) {
        throw new IllegalArgumentException("invalid GTP coordinate", failure);
      }
      if (x < 0 || x >= boardSize || row < 1 || row > boardSize) {
        throw new IllegalArgumentException("GTP coordinate outside board");
      }
      return "" + (char) ('a' + x) + (char) ('a' + boardSize - row);
    }

    private static void recordStop() {
      synchronized (OUTPUT_LOCK) {
        writeAtomic(
            root.resolve("stopped.txt"),
            "command=" + stopCommand + "\nanalysis.count=" + analysisCount + "\n");
        writeState();
      }
    }

    private static void writeState() {
      writeAtomic(
          root.resolve("peer-state.txt"),
          "board="
              + boardSize
              + "\nkomi="
              + komi
              + "\nstones="
              + formattedStones()
              + "\nturn="
              + turn
              + "\ntail="
              + String.join(",", tail)
              + "\nrules="
              + rules
              + "\nrules.confirmed="
              + freshRulesConfirmed
              + "\nanalysis.count="
              + analysisCount
              + "\nanalysis.active="
              + analysisActive
              + "\nstop.command="
              + stopCommand
              + "\nloaded.path="
              + (loadedPath == null ? "none" : loadedPath)
              + "\n");
    }

    private static String formattedStones() {
      List<String> groups = new ArrayList<>();
      for (String color : List.of("B", "W")) {
        List<String> coordinates =
            stones.entrySet().stream()
                .filter(entry -> entry.getValue().equals(color))
                .map(Map.Entry::getKey)
                .toList();
        if (!coordinates.isEmpty()) {
          groups.add(color + ":" + String.join(",", coordinates));
        }
      }
      return String.join(";", groups);
    }

    private static PeerResult noArguments(String arguments, String payload) {
      requireNoArguments(arguments);
      return success(payload);
    }

    private static void requireNoArguments(String arguments) {
      if (!arguments.isBlank()) {
        throw new IllegalArgumentException("unexpected command arguments");
      }
    }

    private static String singleWord(String arguments) {
      String[] words = arguments.isBlank() ? new String[0] : arguments.split("\\s+");
      if (words.length != 1) {
        throw new IllegalArgumentException("expected one command argument");
      }
      return words[0];
    }

    private static PeerResult success(String payload) {
      return new PeerResult(true, payload, null, false);
    }

    private static PeerResult failure(String payload) {
      return new PeerResult(false, payload, null, false);
    }

    private static void respond(boolean success, String id, String payload) throws IOException {
      synchronized (OUTPUT_LOCK) {
        output.write(success ? '=' : '?');
        output.write(id);
        if (!payload.isEmpty()) {
          output.write(' ');
          output.write(payload);
        }
        output.write("\n\n");
        output.flush();
      }
    }

    private static void writeAtomic(Path path, String text) {
      try {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        try {
          Files.move(
              temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
          Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }

    private static void startWatchdog() {
      Thread watchdog =
          new Thread(
              () -> {
                try {
                  Thread.sleep(TimeUnit.SECONDS.toMillis(100));
                  writeAtomic(root.resolve("peer-timeout.txt"), "deadline exceeded\n");
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  return;
                }
                Runtime.getRuntime().halt(70);
              },
              "d3-controlled-deadline");
      watchdog.setDaemon(true);
      watchdog.start();
    }

    private record PeerResult(
        boolean success, String payload, Runnable afterResponse, boolean quit) {}

    private record Snapshot(
        int boardSize, double komi, String turn, TreeMap<String, String> stones) {}
  }
}
