package featurecat.lizzie.analysis;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.FloatBoard;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.SMessage;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import featurecat.lizzie.util.Utils;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.SwingUtilities;

public class ReadBoard {
  private static final File NATIVE_READBOARD_DIR = new File("readboard");
  private static final String NATIVE_READBOARD_EXE = "readboard.exe";
  private static final String NATIVE_READBOARD_BAT = "readboard.bat";

  public static boolean isLegacyNativeReadBoardAvailable() {
    return isNativeReadBoardExeAvailable() || isNativeReadBoardBatAvailable();
  }

  public static boolean isNativeReadBoardExeAvailable() {
    return new File(NATIVE_READBOARD_DIR, NATIVE_READBOARD_EXE).canRead();
  }

  public static boolean isNativeReadBoardBatAvailable() {
    return new File(NATIVE_READBOARD_DIR, NATIVE_READBOARD_BAT).canRead();
  }

  public Process process;
  private InputStreamReader inputStream;
  private BufferedOutputStream outputStream;
  private ScheduledExecutorService executor;
  ArrayList<Integer> tempcount = new ArrayList<Integer>();
  // private long startSyncTime = 0;

  public boolean isLoaded = false;
  private int version = 220430;
  private String engineCommand;
  public String currentEnginename = "";
  private int port = -1;

  boolean firstcount = true;
  public int numberofcount = 0;
  public boolean firstSync = true;
  // public boolean syncBoth = Lizzie.config.syncBoth;
  private ReadBoardStream readBoardStream;
  private Socket socket;
  private ServerSocket s;
  private boolean noMsg = false;
  private boolean usePipe = true;
  private boolean needGenmove = false;
  private boolean showInBoard = false;
  private boolean isSyncing = false;
  // private long startTime;
  private boolean javaReadBoard = false;
  private String javaReadBoardName = "readboard-1.6.2-shaded.jar";
  private boolean waitSocket = true;
  public boolean lastMovePlayByLizzie = false;
  private boolean hideFloadBoardBeforePlace = false;
  private boolean hideFromPlace = false;
  public boolean editMode = false;
  private final SyncConflictTracker conflictTracker = new SyncConflictTracker();

  public ReadBoard(boolean usePipe, boolean isJavaReadBoard) throws Exception {
    this.usePipe = usePipe;
    this.javaReadBoard = isJavaReadBoard;
    if (s != null && !s.isClosed()) {
      s.close();
    }
    if (usePipe) engineCommand = NATIVE_READBOARD_EXE;
    else engineCommand = new File(NATIVE_READBOARD_DIR, NATIVE_READBOARD_BAT).getPath();
    startEngine(engineCommand, 0);
  }

  private void createSocketServer() {
    try {
      s = new ServerSocket(0);
      port = s.getLocalPort();
      waitSocket = false;
      while (true) {
        socket = s.accept();
        readBoardStream = new ReadBoardStream(socket);
        break;
      }
    } catch (Exception e) {
      if (!noMsg)
        Utils.showMsg(
            Lizzie.resourceBundle.getString("ReadBoard.port")
                + " "
                + port
                + " "
                + Lizzie.resourceBundle.getString("ReadBoard.portUsed")
                + e.getMessage());
      try {
        s.close();
      } catch (Exception e1) {
        e1.printStackTrace();
      }
      e.printStackTrace();
    }
  }

  public void startEngine(String engineCommand, int index) throws Exception {
    if (javaReadBoard) {
      File javaReadBoardJar = new File("readboard_java" + File.separator + javaReadBoardName);
      if (!javaReadBoardJar.exists()) {
        Utils.deleteDir(new File("readboard_java"));
        Utils.copyReadBoardJava(javaReadBoardName);
      }
      List<String> jvmArgs = new ArrayList<String>();
      jvmArgs.add("-Dsun.java2d.uiScale=1.0");
      if (Lizzie.javaVersion >= 17) {
        jvmArgs.add("--add-opens");
        jvmArgs.add("java.desktop/sun.awt=ALL-UNNAMED");
        jvmArgs.add("--add-opens");
        jvmArgs.add("java.desktop/java.awt=ALL-UNNAMED");
        jvmArgs.add("--add-opens");
        jvmArgs.add("java.base/java.lang=ALL-UNNAMED");
      }
      List<String> appArgs = new ArrayList<String>();
      appArgs.add(Lizzie.resourceBundle.getString("ReadBoard.language"));
      appArgs.add(Lizzie.config.useJavaLooks ? "true" : "false");
      appArgs.add(String.valueOf((int) Math.round(Config.frameFontSize * Lizzie.javaScaleFactor)));
      appArgs.add(String.valueOf(Board.boardWidth));
      appArgs.add(String.valueOf(Board.boardHeight));
      try {
        process = Utils.startJavaJar(javaReadBoardJar, appArgs, jvmArgs);
      } catch (Exception e) {
        Utils.showMsg(e.getLocalizedMessage());
      }
    } else {
      if (!usePipe) {
        waitSocket = true;
        noMsg = false;
        Runnable runnable2 =
            new Runnable() {
              public void run() {
                if (s == null || s.isClosed()) createSocketServer();
              }
            };
        Thread thread2 = new Thread(runnable2);
        thread2.start();
        int times = 300;
        while (waitSocket && times > 0) {
          Thread.sleep(10);
          times--;
        }
      }
      List<String> commands = new ArrayList<String>();
      commands.add(engineCommand);
      commands.add("yzy");
      commands.add(
          !LizzieFrame.toolbar.chkAutoPlayTime.isSelected()
                  || LizzieFrame.toolbar.txtAutoPlayTime.getText().equals("")
              ? " "
              : LizzieFrame.toolbar.txtAutoPlayTime.getText());
      commands.add(
          !LizzieFrame.toolbar.chkAutoPlayPlayouts.isSelected()
                  || LizzieFrame.toolbar.txtAutoPlayPlayouts.getText().equals("")
              ? " "
              : LizzieFrame.toolbar.txtAutoPlayPlayouts.getText());
      commands.add(
          !LizzieFrame.toolbar.chkAutoPlayFirstPlayouts.isSelected()
                  || LizzieFrame.toolbar.txtAutoPlayFirstPlayouts.getText().equals("")
              ? " "
              : LizzieFrame.toolbar.txtAutoPlayFirstPlayouts.getText());

      if (usePipe) commands.add("0");
      else commands.add("1");
      commands.add(Lizzie.resourceBundle.getString("ReadBoard.language"));
      if (usePipe) commands.add("-1");
      else commands.add(String.valueOf(port));
      ProcessBuilder processBuilder = new ProcessBuilder(commands);
      if (usePipe) processBuilder.directory(NATIVE_READBOARD_DIR);
      processBuilder.redirectErrorStream(true);
      try {
        process = processBuilder.start();
      } catch (IOException e) {
        if (!usePipe) {
          Utils.showMsg(e.getLocalizedMessage());
          SMessage msg = new SMessage();
          msg.setMessage(Lizzie.resourceBundle.getString("ReadBoard.loadFailed"), 2);
          s.close();
          return;
        } else {
          System.out.print(e.getLocalizedMessage());
          throw new Exception("Start pipe failed");
        }
      }
    }
    if (usePipe) {
      initializeStreams();
      executor = Executors.newSingleThreadScheduledExecutor();
      executor.execute(this::read);
    }
  }

  private void initializeStreams() throws UnsupportedEncodingException {
    inputStream = new InputStreamReader(process.getInputStream(), "UTF-8");
    outputStream = new BufferedOutputStream(process.getOutputStream());
  }

  private void read() {
    try {
      int c;
      StringBuilder line = new StringBuilder();
      // while ((c = inputStream.read()) != -1) {
      while ((c = inputStream.read()) != -1) {
        line.append((char) c);

        if ((c == '\n')) {
          try {
            parseLine(line.toString());
            if (!isLoaded) {
              isLoaded = true;
              if (!javaReadBoard) checkVersion();
            }
          } catch (Exception ex) {
            ex.printStackTrace();
          }
          line = new StringBuilder();
        }
      }
      // this line will be reached when BoardSync shuts down
      if (Lizzie.leelaz.isPondering()) Lizzie.leelaz.ponder();
      showInBoard = false;
      if (Lizzie.frame.floatBoard != null) {
        Lizzie.frame.floatBoard.setVisible(false);
      }
      System.out.println("Board synchronization tool process ended.");
      if (!javaReadBoard && !isLoaded) {
        fallbackToJavaReadBoard();
        shutdown();
      } else shutdown();
      // Do no exit for switching weights
      // System.exit(-1);
    } catch (IOException e) {
      e.printStackTrace();
      Lizzie.frame.bothSync = false;
      Lizzie.frame.syncBoard = false;
      // System.exit(-1);
    }
  }

  private void fallbackToJavaReadBoard() {
    SwingUtilities.invokeLater(
        new Runnable() {
          public void run() {
            Utils.showMsg(Lizzie.resourceBundle.getString("ReadBoard.nativeStartFailed"));
            Lizzie.frame.openReadBoardJava();
          }
        });
  }

  public void parseLine(String line) {
    // if (Lizzie.gtpConsole.isVisible())
    // Lizzie.gtpConsole.addLine(line);
    //  System.out.println(line);
    //    if (Lizzie.frame.isPlayingAgainstLeelaz) {
    //      if (Lizzie.frame.playerIsBlack && !Lizzie.board.getHistory().isBlacksTurn()) return;
    //      if (!Lizzie.frame.playerIsBlack && Lizzie.board.getHistory().isBlacksTurn()) return;
    //    }
    if (line.startsWith("playpon")) {
      String[] params = line.split(" ");
      if (params.length == 2) {
        if (params[1].startsWith("on")) {
          Lizzie.config.readBoardPonder = true;
        } else if (params[1].startsWith("off")) {
          Lizzie.config.readBoardPonder = false;
        }
      }
    }
    if (line.startsWith("re=")) {
      String[] params = line.substring(3).split(",");
      if (params.length == Board.boardWidth) {
        for (int i = 0; i < params.length; i++)
          tempcount.add(Integer.parseInt(params[i].substring(0, 1)));
      }
    }
    if (line.startsWith("version")) {
      Lizzie.gtpConsole.addLineReadBoard("Board synchronization tool " + line + "\n");
      String[] params = line.trim().split(" ");
      if (Integer.parseInt(params[1]) < version) {
        SMessage msg = new SMessage();
        msg.setMessage(Lizzie.resourceBundle.getString("ReadBoard.versionCheckFaied"), 2);
      }
    }
    if (line.startsWith("error")) {
      Lizzie.gtpConsole.addLineReadBoard(line + (usePipe ? "" : "\n"));
    }
    if (line.startsWith("end")) {
      if (!isSyncing) syncBoardStones(false);
      tempcount = new ArrayList<Integer>();
    }
    if (line.startsWith("clear")) {
      conflictTracker.clear();
      Lizzie.board.clear(false);
      Lizzie.frame.refresh();
    }
    if (line.startsWith("start")) {
      String[] params = line.trim().split(" ");
      if (params.length >= 3) {
        int boardWidth = Integer.parseInt(params[1]);
        int boardHeight = Integer.parseInt(params[2]);
        if (boardWidth != Board.boardWidth || boardHeight != Board.boardHeight) {
          conflictTracker.clear();
          Lizzie.board.reopen(boardWidth, boardHeight);
        } else {
          conflictTracker.clear();
          Lizzie.board.clear(false);
        }
      } else {
        conflictTracker.clear();
        Lizzie.board.clear(false);
      }
    }
    if (line.startsWith("sync")) {
      Lizzie.frame.syncBoard = true;
      if (!Lizzie.leelaz.isPondering()) Lizzie.leelaz.togglePonder();
    }
    if (line.startsWith("both")) {
      Lizzie.frame.bothSync = true;
      if (Lizzie.frame.floatBoard != null && Lizzie.frame.floatBoard.isVisible())
        Lizzie.frame.floatBoard.setEditButton();
    }
    if (line.startsWith("noboth")) {
      Lizzie.frame.bothSync = false;
      if (Lizzie.frame.floatBoard != null && Lizzie.frame.floatBoard.isVisible())
        Lizzie.frame.floatBoard.setEditButton();
    }
    if (line.startsWith("stopAutoPlay")) {
      LizzieFrame.toolbar.chkAutoPlay.setSelected(false);
      LizzieFrame.toolbar.isAutoPlay = false;
    }
    if (line.startsWith("endsync")) {
      noMsg = true;
      conflictTracker.clear();
      tempcount = new ArrayList<Integer>();
      Lizzie.frame.syncBoard = false;
      if (Lizzie.frame.isAnaPlayingAgainstLeelaz) {
        Lizzie.frame.stopAiPlayingAndPolicy();
      }
      showInBoard = false;
      if (Lizzie.frame.floatBoard != null) {
        Lizzie.frame.floatBoard.setVisible(false);
      }
    }
    if (line.startsWith("stopsync")) {
      conflictTracker.clear();
      tempcount = new ArrayList<Integer>();
      Lizzie.frame.syncBoard = false;
      if (Lizzie.frame.isAnaPlayingAgainstLeelaz) {
        Lizzie.frame.stopAiPlayingAndPolicy();
      }
      Lizzie.leelaz.nameCmd();
      showInBoard = false;
      if (Lizzie.frame.floatBoard != null) {
        Lizzie.frame.floatBoard.setVisible(false);
      }
    }
    if (line.startsWith("play")) {
      String[] params = line.trim().split(">");
      if (params.length == 3) {
        String[] playParams = params[2].trim().split(" ");
        int playouts = Integer.parseInt(playParams[1]);
        int firstPlayouts = Integer.parseInt(playParams[2]);
        int time = Integer.parseInt(playParams[0]);
        if (time > 0) {
          LizzieFrame.toolbar.txtAutoPlayTime.setText(String.valueOf(time));
          LizzieFrame.toolbar.chkAutoPlayTime.setSelected(true);
        } else {
          LizzieFrame.toolbar.txtAutoPlayTime.setText(
              String.valueOf(Lizzie.config.leelazConfig.getInt("max-game-thinking-time-seconds")));
          LizzieFrame.toolbar.chkAutoPlayTime.setSelected(true);
        }
        if (playouts > 0) {
          LizzieFrame.toolbar.txtAutoPlayPlayouts.setText(String.valueOf(playouts));
          LizzieFrame.toolbar.chkAutoPlayPlayouts.setSelected(true);
        } else LizzieFrame.toolbar.chkAutoPlayPlayouts.setSelected(false);
        if (firstPlayouts > 0) {
          LizzieFrame.toolbar.txtAutoPlayFirstPlayouts.setText(String.valueOf(firstPlayouts));
          LizzieFrame.toolbar.chkAutoPlayFirstPlayouts.setSelected(true);
        } else LizzieFrame.toolbar.chkAutoPlayFirstPlayouts.setSelected(false);
        if (params[1].equals("black")) {
          LizzieFrame.toolbar.chkAutoPlayBlack.setSelected(true);
          LizzieFrame.toolbar.chkAutoPlayWhite.setSelected(false);
          LizzieFrame.toolbar.chkAutoPlay.setSelected(true);
          LizzieFrame.toolbar.setChkShowBlack(true);
          LizzieFrame.toolbar.setChkShowWhite(true);
          Lizzie.config.UsePureNetInGame = false;
          Lizzie.frame.isAnaPlayingAgainstLeelaz = true;
          LizzieFrame.toolbar.isAutoPlay = true;
          Lizzie.frame.clearWRNforGame(false);
        } else if (params[1].equals("white")) {
          LizzieFrame.toolbar.chkAutoPlayBlack.setSelected(false);
          LizzieFrame.toolbar.chkAutoPlayWhite.setSelected(true);
          LizzieFrame.toolbar.chkAutoPlay.setSelected(true);
          LizzieFrame.toolbar.setChkShowBlack(true);
          LizzieFrame.toolbar.setChkShowWhite(true);
          Lizzie.config.UsePureNetInGame = false;
          Lizzie.frame.isAnaPlayingAgainstLeelaz = true;
          LizzieFrame.toolbar.isAutoPlay = true;
          Lizzie.frame.clearWRNforGame(false);
        }
        Lizzie.leelaz.ponder();
      }
    }
    if (line.startsWith("pass")) {
      Lizzie.board.changeNextTurn();
    }
    if (line.startsWith("firstchanged")) {
      String[] params = line.trim().split(" ");
      if (params.length == 2) {
        int firstPlayouts = Integer.parseInt(params[1]);
        if (firstPlayouts > 0) {
          LizzieFrame.toolbar.txtAutoPlayFirstPlayouts.setText(String.valueOf(firstPlayouts));
          LizzieFrame.toolbar.chkAutoPlayFirstPlayouts.setSelected(true);
        } else LizzieFrame.toolbar.chkAutoPlayFirstPlayouts.setSelected(false);
      }
    }
    if (line.startsWith("playoutschanged")) {
      String[] params = line.trim().split(" ");
      if (params.length == 2) {
        int playouts = Integer.parseInt(params[1]);
        if (playouts > 0) {
          LizzieFrame.toolbar.txtAutoPlayPlayouts.setText(String.valueOf(playouts));
          LizzieFrame.toolbar.chkAutoPlayPlayouts.setSelected(true);
        } else LizzieFrame.toolbar.chkAutoPlayPlayouts.setSelected(false);
      }
    }
    if (line.startsWith("timechanged")) {
      String[] params = line.trim().split(" ");
      if (params.length == 2) {
        int time = Integer.parseInt(params[1]);
        if (time > 0) {
          LizzieFrame.toolbar.txtAutoPlayTime.setText(String.valueOf(time));
          LizzieFrame.toolbar.chkAutoPlayTime.setSelected(true);
        } else {
          LizzieFrame.toolbar.txtAutoPlayTime.setText(
              String.valueOf(Lizzie.config.leelazConfig.getInt("max-game-thinking-time-seconds")));
          LizzieFrame.toolbar.chkAutoPlayTime.setSelected(true);
        }
      }
    }

    if (line.startsWith("noponder")) {
      if (Lizzie.frame.isPlayingAgainstLeelaz) {
        Lizzie.frame.isPlayingAgainstLeelaz = false;
        Lizzie.leelaz.isThinking = false;
      }
      if (Lizzie.frame.isAnaPlayingAgainstLeelaz) {
        Lizzie.frame.stopAiPlayingAndPolicy();
      }
      Lizzie.leelaz.togglePonder();
    }
    if (line.startsWith("noinboard")) {
      if (Lizzie.frame.floatBoard != null && Lizzie.frame.floatBoard.isVisible()) {
        Lizzie.frame.floatBoard.setVisible(false);
      }
    }
    if (line.startsWith("inboard")) {
      //	Lizzie.gtpConsole.addLine(line);
      if (hideFromPlace) return;
      showInBoard = true;
      String[] params = line.trim().split(" ");
      if (params.length == 6) {
        if (params[5].startsWith("99")) {
          String[] param = params[5].split("_");
          float factor = Float.parseFloat(param[1]);
          if (Lizzie.frame.floatBoard == null) {
            Lizzie.frame.floatBoard =
                new FloatBoard(
                    (int) Math.ceil(Integer.parseInt(params[1]) * factor),
                    (int) Math.ceil(Integer.parseInt(params[2]) * factor),
                    (int) Math.ceil(Integer.parseInt(params[3]) * factor),
                    (int) Math.ceil(Integer.parseInt(params[4]) * factor),
                    Integer.parseInt(param[2]),
                    true);
            // Lizzie.frame.floatBoard.setFactor(factor);
          } else {
            Lizzie.frame.floatBoard.setPos(
                (int) Math.ceil(Integer.parseInt(params[1]) * factor),
                (int) Math.ceil(Integer.parseInt(params[2]) * factor),
                (int) Math.ceil(Integer.parseInt(params[3]) * factor),
                (int) Math.ceil(Integer.parseInt(params[4]) * factor),
                Integer.parseInt(param[2]));
            //   Lizzie.frame.floatBoard.setFactor(factor);
          }
        } else {
          if (Lizzie.frame.floatBoard == null) {
            Lizzie.frame.floatBoard =
                new FloatBoard(
                    Integer.parseInt(params[1]),
                    Integer.parseInt(params[2]),
                    Integer.parseInt(params[3]),
                    Integer.parseInt(params[4]),
                    Integer.parseInt(params[5]),
                    false);
            Lizzie.frame.floatBoard.setBoardType();
          } else {
            Lizzie.frame.floatBoard.setPos(
                Integer.parseInt(params[1]),
                Integer.parseInt(params[2]),
                Integer.parseInt(params[3]),
                Integer.parseInt(params[4]),
                Integer.parseInt(params[5]));
          }
        }
      }
    }
    if (line.startsWith("notinboard")) {
      showInBoard = false;
      if (Lizzie.frame.floatBoard != null) {
        Lizzie.frame.floatBoard.setVisible(false);
      }
    }
    if (line.startsWith("foreFoxWithInBoard")) {
      hideFloadBoardBeforePlace = true;
    }
    if (line.startsWith("notForeFoxWithInBoard")) {
      hideFloadBoardBeforePlace = false;
    }
    if (line.startsWith("placeComplete")) {
      if (hideFloadBoardBeforePlace && hideFromPlace) {
        hideFromPlace = false;
        if (Lizzie.frame.floatBoard != null) Lizzie.frame.floatBoard.setVisible(true);
      }
    }
  }

  private void syncBoardStones(boolean isSecondTime) {
    //    if (!this.javaReadBoard && !isSecondTime) {
    //      long thisTime = System.currentTimeMillis();
    //      if (thisTime - startSyncTime < Lizzie.config.readBoardArg2 / 2) return;
    //      startSyncTime = thisTime;
    //    }
    if (tempcount.size() > Board.boardWidth * Board.boardHeight) {
      tempcount = new ArrayList<Integer>();
      conflictTracker.clear();
      return;
    }
    isSyncing = true;
    boolean needReSync = false;
    boolean played = false;
    boolean holdLastMove = false;
    int lastX = 0;
    int lastY = 0;
    int playedMove = 0;
    boolean isLastBlack = false;
    BoardHistoryNode node = Lizzie.board.getHistory().getCurrentHistoryNode();
    BoardHistoryNode node2 = Lizzie.board.getHistory().getMainEnd();
    Stone[] syncStartStones = node2.getData().stones.clone();
    Stone[] stones = Lizzie.board.getHistory().getMainEnd().getData().stones;

    boolean needRefresh = false;
    for (int i = 0; i < tempcount.size(); i++) {
      int m = tempcount.get(i);
      int y = i / Board.boardWidth;
      int x = i % Board.boardWidth;
      if (((holdLastMove && m == 3) || m == 1) && !stones[Board.getIndex(x, y)].isBlack()) {
        if (stones[Board.getIndex(x, y)].isWhite()) {
          Lizzie.board.clear(false);
          needReSync = true;
          needRefresh = true;
          break;
        }
        if (!played) {
          Lizzie.board.moveToAnyPosition(node2);
        }
        Lizzie.board.placeForSync(x, y, Stone.BLACK, true);
        if (node2.variations.size() > 0 && node2.variations.get(0).isEndDummay()) {
          node2.variations.add(0, node2.variations.get(node2.variations.size() - 1));
          node2.variations.remove(1);
          node2.variations.remove(node2.variations.size() - 1);
        }
        played = true;
        playedMove = playedMove + 1;
      }
      if (((holdLastMove && m == 4) || m == 2) && !stones[Board.getIndex(x, y)].isWhite()) {
        if (stones[Board.getIndex(x, y)].isBlack()) {
          Lizzie.board.clear(false);
          needReSync = true;
          needRefresh = true;
          break;
        }

        if (!played) {
          Lizzie.board.moveToAnyPosition(node2);
        }
        Lizzie.board.placeForSync(x, y, Stone.WHITE, true);
        if (node2.variations.size() > 0 && node2.variations.get(0).isEndDummay()) {
          node2.variations.add(0, node2.variations.get(node2.variations.size() - 1));
          node2.variations.remove(1);
          node2.variations.remove(node2.variations.size() - 1);
        }
        played = true;
        playedMove = playedMove + 1;
      }

      if (!holdLastMove && m == 3 && !stones[Board.getIndex(x, y)].isBlack()) {
        if (stones[Board.getIndex(x, y)].isWhite()) {
          Lizzie.board.clear(false);
          needReSync = true;
          needRefresh = true;
          break;
        }
        holdLastMove = true;
        lastX = x;
        lastY = y;
        isLastBlack = true;
      }
      if (!holdLastMove && m == 4 && !stones[Board.getIndex(x, y)].isWhite()) {
        if (stones[Board.getIndex(x, y)].isBlack()) {
          Lizzie.board.clear(false);
          needReSync = true;
          needRefresh = true;
          break;
        }
        holdLastMove = true;
        lastX = x;
        lastY = y;
        isLastBlack = false;
      }
    }
    if (firstSync) {
      Lizzie.board.hasStartStone = true;
      Lizzie.board.addStartListAll();
      Lizzie.board.flatten();
    }
    // 落最后一步
    if (holdLastMove && !needReSync) {
      if (!played) {
        Lizzie.board.moveToAnyPosition(node2);
      }
      Lizzie.board.placeForSync(lastX, lastY, isLastBlack ? Stone.BLACK : Stone.WHITE, true);
      if (node2.variations.size() > 0 && node2.variations.get(0).isEndDummay()) {
        node2.variations.add(0, node2.variations.get(node2.variations.size() - 1));
        node2.variations.remove(1);
        node2.variations.remove(node2.variations.size() - 1);
      }
      played = true;
      if (Lizzie.config.alwaysSyncBoardStat || showInBoard) Lizzie.frame.lastMove();
    }
    stones = Lizzie.board.getHistory().getMainEnd().getData().stones;
    if ((Lizzie.config.alwaysSyncBoardStat) || showInBoard) {
      for (int i = 0; i < tempcount.size(); i++) {
        int m = tempcount.get(i);
        int y = i / Board.boardWidth;
        int x = i % Board.boardWidth;
        if (isStoneDiff(m, stones, x, y)) {
          needReSync = true;
          break;
        }
      }
    }
    if (!needReSync) {
      applySyncViewState(played, node, node2);
    }
    if (needReSync && !isSecondTime) {
      int[] snapshotCodes = getSnapshotCodes();
      if (tryApplySingleMoveRecovery(node, node2, syncStartStones, snapshotCodes)) {
        played = true;
        needReSync = false;
        needRefresh = true;
      } else {
        if (!played && !needRefresh) {
          SyncConflictTracker.Decision conflictDecision = conflictTracker.evaluate(snapshotCodes);
          if (conflictDecision == SyncConflictTracker.Decision.HOLD) {
            isSyncing = false;
            return;
          }
        }
        conflictTracker.clear();
        Lizzie.board.clear(false);
        syncBoardStones(true);
      }
    }
    if (!needReSync) {
      conflictTracker.clear();
    }
    if (played || needRefresh) {
      Lizzie.frame.refresh();
    }
    if (firstSync) {
      firstSync = false;
      Lizzie.board.previousMove(true);
      new Thread() {
        public void run() {
          try {
            Thread.sleep(500);
          } catch (InterruptedException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
          }
          Lizzie.frame.lastMove();
        }
      }.start();
    }
    if (Lizzie.frame.isPlayingAgainstLeelaz && needGenmove) {
      if (!Lizzie.board.getHistory().isBlacksTurn() && Lizzie.frame.playerIsBlack) {
        Lizzie.leelaz.genmove("W");
        needGenmove = false;
      } else if (!Lizzie.frame.playerIsBlack) {
        Lizzie.leelaz.genmove("B");
        needGenmove = false;
      }
    }
    isSyncing = false;
    //	    if (played && Lizzie.config.alwaysGotoLastOnLive) {
    //	      int moveNumber = Lizzie.board.getHistory().getMainEnd().getData().moveNumber;
    //	      Lizzie.board.goToMoveNumberBeyondBranch(moveNumber);
    //	      Lizzie.frame.refresh();
    //	    }
  }

  private int[] getSnapshotCodes() {
    int[] codes = new int[tempcount.size()];
    for (int index = 0; index < tempcount.size(); index++) {
      codes[index] = tempcount.get(index);
    }
    return codes;
  }

  private void restoreViewedNodeAfterSync(
      boolean played, BoardHistoryNode currentNode, BoardHistoryNode syncEndNode) {
    if (Lizzie.frame.bothSync
        || !played
        || Lizzie.config.alwaysGotoLastOnLive
        || (showInBoard
            && Lizzie.frame.floatBoard != null
            && !Lizzie.frame.floatBoard.hideSuggestion)
        || !Lizzie.board.getHistory().getCurrentHistoryNode().previous().isPresent()
        || currentNode == syncEndNode) {
      return;
    }
    Lizzie.board.moveToAnyPosition(currentNode);
  }

  private boolean tryApplySingleMoveRecovery(
      BoardHistoryNode currentNode,
      BoardHistoryNode syncStartNode,
      Stone[] syncStartStones,
      int[] snapshotCodes) {
    SyncSnapshotClassifier classifier =
        new SyncSnapshotClassifier(Board.boardWidth, Board.boardHeight);
    Optional<SyncSnapshotClassifier.SingleMove> recoveredMove =
        classifier.findSingleMoveCapture(syncStartStones, snapshotCodes);
    if (!recoveredMove.isPresent()) {
      return false;
    }
    SyncSnapshotClassifier.SingleMove move = recoveredMove.get();
    if (!canApplySingleMoveRecovery(syncStartNode, move, snapshotCodes)) {
      return false;
    }
    Lizzie.board.moveToAnyPosition(syncStartNode);
    Lizzie.board.placeForSync(move.x, move.y, move.color, false);
    if (Lizzie.config.alwaysSyncBoardStat || showInBoard) {
      Lizzie.frame.lastMove();
    }
    applySyncViewState(true, currentNode, syncStartNode);
    return true;
  }

  private void applySyncViewState(
      boolean played, BoardHistoryNode currentNode, BoardHistoryNode syncEndNode) {
    restoreViewedNodeAfterSync(played, currentNode, syncEndNode);
    if (editMode) Lizzie.board.moveToAnyPosition(currentNode);
    if (played) Lizzie.frame.renderVarTree(0, 0, false, false);
  }

  private boolean canApplySingleMoveRecovery(
      BoardHistoryNode syncStartNode, SyncSnapshotClassifier.SingleMove move, int[] snapshotCodes) {
    BoardData syncStartData = syncStartNode.getData();
    Stone[] stones = syncStartData.stones.clone();
    int moveIndex = Board.getIndex(move.x, move.y);
    if (!stones[moveIndex].isEmpty()) {
      return false;
    }
    Zobrist zobrist = syncStartData.zobrist.clone();
    stones[moveIndex] = move.color;
    zobrist.toggleStone(move.x, move.y, move.color);
    int isSuicidal = 0;
    if (!Lizzie.config.noCapture) {
      Board.removeDeadChain(move.x + 1, move.y, move.color.opposite(), stones, zobrist);
      Board.removeDeadChain(move.x, move.y + 1, move.color.opposite(), stones, zobrist);
      Board.removeDeadChain(move.x - 1, move.y, move.color.opposite(), stones, zobrist);
      Board.removeDeadChain(move.x, move.y - 1, move.color.opposite(), stones, zobrist);
      isSuicidal = Board.removeDeadChain(move.x, move.y, move.color, stones, zobrist);
    }
    if (violatesRecoveryKo(syncStartNode, zobrist) || violatesRecoverySuicide(isSuicidal)) {
      return false;
    }
    return !hasSnapshotDiff(stones, snapshotCodes);
  }

  private boolean violatesRecoveryKo(BoardHistoryNode syncStartNode, Zobrist zobrist) {
    return syncStartNode
        .previous()
        .map(previousNode -> previousNode != null && zobrist.equals(previousNode.getData().zobrist))
        .orElse(false);
  }

  private boolean violatesRecoverySuicide(int isSuicidal) {
    if (Lizzie.leelaz.canSuicidal) {
      return isSuicidal == 1;
    }
    return isSuicidal > 0;
  }

  private boolean hasSnapshotDiff(Stone[] stones, int[] snapshotCodes) {
    for (int index = 0; index < snapshotCodes.length; index++) {
      int y = index / Board.boardWidth;
      int x = index % Board.boardWidth;
      if (isStoneDiff(snapshotCodes[index], stones, x, y)) {
        return true;
      }
    }
    return false;
  }

  private boolean isStoneDiff(int m, Stone[] stones, int x, int y) {
    // TODO Auto-generated method stub
    Stone stone = stones[Board.getIndex(x, y)];
    if (m == 0 && stone != Stone.EMPTY) {
      if (Lizzie.frame.bothSync && lastMovePlayByLizzie) {
        BoardHistoryNode curNode = Lizzie.board.getHistory().getMainEnd();
        if (curNode.getData().lastMove.isPresent()) {
          int[] lastCoords = curNode.getData().lastMove.get();
          if (lastCoords[0] == x && lastCoords[1] == y) {
            return false;
          }
        }
      }
      return true;
    }
    if ((m == 1 || m == 3) && !stone.isBlack()) {
      return true;
    }
    if ((m == 2 || m == 4) && !stone.isWhite()) {
      return true;
    }
    return false;
  }

  public void shutdown() {
    noMsg = true;
    conflictTracker.clear();
    tempcount = new ArrayList<Integer>();
    Lizzie.frame.syncBoard = false;
    Lizzie.frame.bothSync = false;
    this.sendCommand("quit");
    if (usePipe) {
      try {
        s.close();
        socket.close();
      } catch (Exception e) {
      }
    }
  }

  public void sendCommandTo(String command) {
    // if (Lizzie.gtpConsole.isVisible() || Lizzie.config.alwaysGtp)
    // Lizzie.gtpConsole.addReadBoardCommand(command);
    try {
      outputStream.write((command + "\n").getBytes());
      outputStream.flush();
    } catch (IOException e) {
      // e.printStackTrace();
    }
  }

  public void sendCommand(String command) {
    if (command.startsWith("place")) {
      if (hideFloadBoardBeforePlace && Lizzie.frame.floatBoard != null) {
        Lizzie.frame.floatBoard.setVisible(false);
        hideFromPlace = true;
      }
      lastMovePlayByLizzie = true;
      if (Lizzie.frame.isPlayingAgainstLeelaz) needGenmove = true;
    }
    if (usePipe) {
      sendCommandTo(command);
    } else if (readBoardStream != null) readBoardStream.sendCommand(command);
  }

  public void sendLossFocus() {
    // TODO Auto-generated method stub
    if (!Lizzie.config.readBoardGetFocus) return;
    sendCommand("loss");
  }

  public void checkVersion() {
    sendCommand("version");
  }

  // public void sendStopInBoard() {
  //	// TODO Auto-generated method stub
  //	 sendCommand("notinboard");
  // }
}
