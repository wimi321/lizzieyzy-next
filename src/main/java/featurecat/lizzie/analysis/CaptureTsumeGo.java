package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import featurecat.lizzie.rules.extraMoveForTsumego;
import featurecat.lizzie.util.Utils;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.JFrame;

public class CaptureTsumeGo {
  private static boolean macPermissionHintShown = false;

  private Process process;
  private BufferedReader inputStream;
  private BufferedReader errorStream;
  private ScheduledExecutorService executor;
  private ScheduledExecutorService executorErr;

  public CaptureTsumeGo() {
    maybeShowMacScreenRecordingHint();
    if (start()) {
      initializeStreams();
      executor = Executors.newSingleThreadScheduledExecutor();
      executor.execute(this::read);
      executorErr = Executors.newSingleThreadScheduledExecutor();
      executorErr.execute(this::readError);
    } else {
      Lizzie.frame.setExtendedState(JFrame.NORMAL);
    }
  }

  private static void maybeShowMacScreenRecordingHint() {
    if (macPermissionHintShown) return;
    String osName = System.getProperty("os.name", "").toLowerCase();
    if (osName.contains("mac")) {
      macPermissionHintShown = true;
      Utils.showMsgNoModal("macOS 需要授予\"屏幕录制\"权限才能使用抓死活棋功能。\n" + "请在 系统设置 → 隐私与安全性 → 屏幕录制 中允许本应用。");
    }
  }

  private boolean start() {
    String jarName = "CaptureTsumeGo1.2.jar";
    File jarFile = new File("captureTsumeGo" + File.separator + jarName);
    if (!jarFile.exists()) Utils.copyCaptureTsumeGo();
    if (!jarFile.exists()) {
      Utils.showMsg("找不到 " + jarFile.getAbsolutePath() + "，抓死活棋功能无法启动。");
      return false;
    }
    try {
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
      appArgs.add(String.valueOf(Lizzie.config.captureBlackOffset));
      appArgs.add(String.valueOf(Lizzie.config.captureBlackPercent));
      appArgs.add(String.valueOf(Lizzie.config.captureWhiteOffset));
      appArgs.add(String.valueOf(Lizzie.config.captureWhitePercent));
      appArgs.add(String.valueOf(Lizzie.config.captureGrayOffset));
      process = Utils.startJavaJar(jarFile, appArgs, jvmArgs);
      return true;
    } catch (Exception e) {
      Utils.showMsg("抓死活棋启动失败: " + e.getLocalizedMessage());
      return false;
    }
  }

  private void initializeStreams() {
    inputStream = new BufferedReader(new InputStreamReader(process.getInputStream()));
    errorStream = new BufferedReader(new InputStreamReader(process.getErrorStream()));
  }

  private void read() {
    try {
      String line = "";
      while ((line = inputStream.readLine()) != null) {
        try {
          parseLine(line.toString());
        } catch (Exception ex) {
          ex.printStackTrace();
        }
      }
      System.out.println("Capture process ended.");
    } catch (IOException e) {
    }
    shutdown();
    process = null;
  }

  private void readError() {
    String line = "";
    try {
      while ((line = errorStream.readLine()) != null) {
        try {
          Lizzie.gtpConsole.addErrorLine(line + "\n");
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private int direction = -1;
  private int bw = -1;
  private int bh = -1;
  private ArrayList<int[]> stoneData;

  private void parseLine(String line) {
    if (line.equals("esc")) {
      Lizzie.frame.setExtendedState(JFrame.NORMAL);
      Lizzie.frame.openCaptureTsumego();
    }
    if (line.startsWith("dx")) {
      String[] params = line.split(" ");
      if (params.length == 6) {
        direction = Integer.parseInt(params[1]);
        bw = Integer.parseInt(params[3]);
        bh = Integer.parseInt(params[5]);
        stoneData = new ArrayList<int[]>();
      }
    }
    if (line.startsWith("#")) {
      if (stoneData == null) return;
      String[] params = line.substring(2).split(" ");
      int[] data = new int[params.length];
      for (int i = 0; i < params.length; i++) {
        data[i] = Integer.parseInt(params[i]);
      }
      stoneData.add(data);
    }
    if (line.equals("end")) {
      if (stoneData == null || direction < 0 || bw < 0 || bh < 0) return;
      processStoneData(stoneData, direction, bw, bh);
      Lizzie.frame.setExtendedState(JFrame.NORMAL);
      Lizzie.frame.openTsumego();
    }
  }

  private void processStoneData(ArrayList<int[]> stoneData, int direction, int bw, int bh) {
    if (Board.boardWidth < bw || Board.boardHeight < bh) Lizzie.board.reopen(bw, bw);
    else Lizzie.board.clear(false);
    Stone[] curStones = Lizzie.board.getStones();
    Stone[] stones = new Stone[curStones.length];
    for (int i = 0; i < curStones.length; i++) {
      stones[i] = curStones[i];
    }
    Zobrist zobrist = Lizzie.board.getHistory().getZobrist();
    List<extraMoveForTsumego> extraStones = new ArrayList<extraMoveForTsumego>();
    for (int y = 0; y < stoneData.size(); y++) {
      int[] value = stoneData.get(y);
      for (int x = 0; x < value.length; x++) {
        int data = value[x];
        Stone stone = Stone.EMPTY;
        if (data == 1) stone = Stone.BLACK;
        else if (data == 2) stone = Stone.WHITE;
        if (stone != Stone.EMPTY) {
          switch (direction) {
            case 1: // 左上角
              Utils.addStone(stones, zobrist, x, y, stone, extraStones);
              break;
            case 2: // 右上角
              Utils.addStone(stones, zobrist, Board.boardWidth - (bw - x), y, stone, extraStones);
              break;
            case 3: // 左下角
              Utils.addStone(stones, zobrist, x, Board.boardHeight - (bh - y), stone, extraStones);
              break;
            case 4: // 右下角
              Utils.addStone(
                  stones,
                  zobrist,
                  Board.boardWidth - (bw - x),
                  Board.boardHeight - (bh - y),
                  stone,
                  extraStones);
              break;
          }
        }
      }
    }
    Lizzie.board.flattenWithCondition(stones, zobrist, true, extraStones);
    Lizzie.frame.refresh();
  }

  public void shutdown() {
    if (process != null) {
      try {
        process.destroy();
      } catch (Exception ignored) {
      }
    }
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
    if (executorErr != null) {
      executorErr.shutdownNow();
      executorErr = null;
    }
  }
}
