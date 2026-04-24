package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.Utils;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import javax.swing.SwingUtilities;

public class SocketGetFile {
  SMessage msg;
  ShareMessage smsg;
  String link = "";
  boolean recievedServer = false;
  Socket socket = null;

  public void SocketGetFile(String name, String date, String file) {
    recievedServer = false;
    Lizzie.frame.beginKifuLoad(
        Lizzie.frame.kifuLoadText(
            "KifuLoad.sharedDownloading", "正在下载棋谱…", "Downloading game record..."));
    Thread thread =
        new Thread(
            new Runnable() {
              public void run() {
                downloadSocketFile(name, date, file);
              }
            },
            "lizzie-shared-kifu-download");
    thread.setDaemon(true);
    thread.start();
  }

  private void downloadSocketFile(String name, String date, String file) {
    BufferedReader br = null;
    PrintWriter pw = null;
    try {
      // 客户端socket指定服务器的地址和端口号121.36.229.204
      socket = new Socket("lizzieyzy.cn", 3105);
      // System.out.println("Socket=" + socket);
      // 同服务器原理一样
      br = new BufferedReader(new InputStreamReader(socket.getInputStream(), "utf-8"));
      pw =
          new PrintWriter(
              new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "utf-8")));
      sendContent("SktINFOStart", pw);
      pw.println(name + ">" + date + ">" + file);
      pw.flush();
      pw.println("SktEND");
      pw.flush();
      //  sendContent("SktEND",pw);

      String str;
      boolean errMsg = false;
      String err = "";

      Runnable runnable =
          new Runnable() {
            public void run() {
              try {
                Thread.sleep(15000);
                if (socket != null && !recievedServer)
                  try {
                    socket.close();
                    // msg = new SMessage();
                    //    msg.setMessage(
                    //
                    // "连接失败...请重试或下载最新版Lizzie,链接:https://pan.baidu.com/s/1q615GHD62F92mNZbTYfcxA");
                    //     msg.setVisible(true);
                    SwingUtilities.invokeLater(
                        new Runnable() {
                          public void run() {
                            Lizzie.frame.failKifuLoad(
                                Lizzie.resourceBundle.getString("Socket.connectFailed"));
                          }
                        });
                  } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                  }
              } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
              }
            }
          };
      Thread th = new Thread(runnable);
      th.start();
      String sgfString = "";
      while ((str = br.readLine()) != null) {
        recievedServer = true;
        sgfString += str + "\n";
        if (str.startsWith("getFileEnd")) {
          String loadedSgf = sgfString;
          SwingUtilities.invokeLater(
              new Runnable() {
                public void run() {
                  Lizzie.frame.failKifuLoad(null);
                  Lizzie.frame.loadSgfString(loadedSgf, 200, Lizzie.config.readKomi, false, null);
                }
              });
        }
        if (str.startsWith("errorFileInfo")) {
          errMsg = true;
          err = str.substring(13);
        }
      }
      if (errMsg) {
        String error = err;
        SwingUtilities.invokeLater(
            new Runnable() {
              public void run() {
                Lizzie.frame.failKifuLoad(error);
              }
            });
      }

    } catch (Exception e) {
      SwingUtilities.invokeLater(
          new Runnable() {
            public void run() {
              Lizzie.frame.failKifuLoad(Lizzie.resourceBundle.getString("Socket.connectFailed"));
            }
          });
    } finally {
      if (socket != null)
        try {
          // System.out.println("close......");
          if (br != null) br.close();
          if (pw != null) pw.close();
          socket.close();
        } catch (IOException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      else {
        // System.out.println("连接失败...");
        //  Lizzie.gtpConsole.addLine("连接失败..." + "\n");
        SwingUtilities.invokeLater(
            new Runnable() {
              public void run() {
                Lizzie.frame.failKifuLoad(Lizzie.resourceBundle.getString("Socket.connectFailed"));
              }
            });
        // msg = new SMessage();
        // msg.setMessage("连接失败...请重试或下载最新版Lizzie,链接:https://pan.baidu.com/s/1q615GHD62F92mNZbTYfcxA");

        //  msg.setVisible(true);
      }
    }
  }

  public void sendContent(String str, PrintWriter pw) {
    pw.println(Utils.doEncrypt(str));
    pw.flush();
  }
}
