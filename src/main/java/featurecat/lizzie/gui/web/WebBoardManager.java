package featurecat.lizzie.gui.web;

import featurecat.lizzie.Lizzie;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.Enumeration;
import org.json.JSONObject;

public class WebBoardManager {
  private volatile WebBoardServer wsServer;
  private volatile WebBoardHttpServer httpServer;
  private volatile WebBoardDataCollector collector;
  private volatile boolean running;
  private volatile String accessUrl;
  private int actualHttpPort;
  private int actualWsPort;

  public synchronized boolean start() {
    if (running) return true;
    JSONObject cfg = Lizzie.config.config.optJSONObject("web-board");
    int httpPort = cfg != null ? cfg.optInt("http-port", 9998) : 9998;
    int wsPort = cfg != null ? cfg.optInt("ws-port", 9999) : 9999;
    int maxConn = cfg != null ? cfg.optInt("max-connections", 20) : 20;

    httpServer = null;
    for (int i = 0; i < 10; i++) {
      try {
        httpServer = new WebBoardHttpServer(httpPort + i);
        httpServer.start();
        actualHttpPort = httpPort + i;
        break;
      } catch (Exception e) {
        httpServer = null;
      }
    }
    if (httpServer == null) return false;

    wsServer = null;
    for (int i = 0; i < 10; i++) {
      int candidatePort = wsPort + i;
      if (!isPortAvailable(candidatePort)) continue;
      try {
        wsServer = new WebBoardServer(new InetSocketAddress("0.0.0.0", candidatePort), maxConn);
        wsServer.start();
        actualWsPort = candidatePort;
        break;
      } catch (Exception e) {
        wsServer = null;
      }
    }
    if (wsServer == null) {
      httpServer.stop();
      return false;
    }

    httpServer.setWsPort(actualWsPort);

    collector = new WebBoardDataCollector();
    collector.setServer(wsServer);

    String ip = getLanIp();
    accessUrl = "http://" + ip + ":" + actualHttpPort;
    running = true;
    return true;
  }

  public synchronized void stop() {
    if (!running) return;
    if (collector != null) {
      collector.shutdown();
      collector = null;
    }
    if (wsServer != null) {
      try {
        wsServer.stop(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Exception ignored) {
      }
      wsServer = null;
    }
    if (httpServer != null) {
      httpServer.stop();
      httpServer = null;
    }
    running = false;
    accessUrl = null;
  }

  public boolean isRunning() {
    return running;
  }

  public String getAccessUrl() {
    return accessUrl;
  }

  public int getWsPort() {
    return actualWsPort;
  }

  public WebBoardDataCollector getCollector() {
    return collector;
  }

  static String getLanIp() {
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      while (interfaces.hasMoreElements()) {
        NetworkInterface ni = interfaces.nextElement();
        if (ni.isLoopback() || !ni.isUp()) continue;
        Enumeration<InetAddress> addrs = ni.getInetAddresses();
        while (addrs.hasMoreElements()) {
          InetAddress addr = addrs.nextElement();
          if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
            return addr.getHostAddress();
          }
        }
      }
    } catch (SocketException ignored) {
    }
    return "127.0.0.1";
  }

  private static boolean isPortAvailable(int port) {
    try (ServerSocket ss = new ServerSocket(port)) {
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
