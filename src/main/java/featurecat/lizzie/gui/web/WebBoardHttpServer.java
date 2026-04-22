package featurecat.lizzie.gui.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebBoardHttpServer {
  private final int port;
  private ServerSocket serverSocket;
  private Thread acceptThread;
  private ExecutorService clientPool;
  private volatile boolean running;
  private volatile int wsPort;

  private static final Map<String, String> MIME_TYPES =
      Map.of(
          "html", "text/html; charset=utf-8",
          "js", "application/javascript; charset=utf-8",
          "css", "text/css; charset=utf-8",
          "png", "image/png",
          "svg", "image/svg+xml");

  public WebBoardHttpServer(int port) {
    this.port = port;
  }

  public void setWsPort(int wsPort) {
    this.wsPort = wsPort;
  }

  public void start() throws IOException {
    serverSocket = new ServerSocket(port);
    clientPool =
        Executors.newFixedThreadPool(
            4,
            r -> {
              Thread t = new Thread(r, "WebBoardHttp-client");
              t.setDaemon(true);
              return t;
            });
    running = true;
    acceptThread = new Thread(this::acceptLoop, "WebBoardHttp");
    acceptThread.setDaemon(true);
    acceptThread.start();
  }

  public void stop() {
    running = false;
    try {
      if (serverSocket != null) serverSocket.close();
    } catch (IOException ignored) {
    }
    if (clientPool != null) clientPool.shutdownNow();
  }

  private void acceptLoop() {
    while (running) {
      try {
        Socket client = serverSocket.accept();
        clientPool.execute(() -> handleClient(client));
      } catch (SocketException e) {
        if (!running) break;
      } catch (IOException e) {
        break;
      }
    }
  }

  private void handleClient(Socket client) {
    try (client;
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
        OutputStream out = client.getOutputStream()) {

      String requestLine = in.readLine();
      if (requestLine == null) return;

      // Drain request headers
      String headerLine;
      while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) {
        // skip
      }

      String[] parts = requestLine.split(" ");
      if (parts.length < 2) return;

      if (!"GET".equalsIgnoreCase(parts[0])) {
        sendError(out, 405, "Method Not Allowed");
        return;
      }

      String path = java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
      if (path.contains("..") || path.contains("\\")) {
        sendError(out, 403, "Forbidden");
        return;
      }

      if ("/".equals(path)) path = "/index.html";

      String resourcePath = "/web" + path;
      InputStream resource = getClass().getResourceAsStream(resourcePath);
      if (resource == null) {
        sendError(out, 404, "Not Found");
        return;
      }

      byte[] body = resource.readAllBytes();
      resource.close();

      if (resourcePath.endsWith("index.html")) {
        String html = new String(body, StandardCharsets.UTF_8);
        html = html.replace("__WS_PORT__", String.valueOf(wsPort));
        body = html.getBytes(StandardCharsets.UTF_8);
      }

      String ext = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : "";
      String contentType = MIME_TYPES.getOrDefault(ext, "application/octet-stream");

      String header =
          "HTTP/1.1 200 OK\r\n"
              + "Content-Type: "
              + contentType
              + "\r\n"
              + "Content-Length: "
              + body.length
              + "\r\n"
              + "Access-Control-Allow-Origin: *\r\n"
              + "\r\n";
      out.write(header.getBytes());
      out.write(body);
      out.flush();
    } catch (IOException ignored) {
    }
  }

  private void sendError(OutputStream out, int code, String message) throws IOException {
    String body = "<h1>" + code + " " + message + "</h1>";
    String response =
        "HTTP/1.1 "
            + code
            + " "
            + message
            + "\r\n"
            + "Content-Type: text/html\r\n"
            + "Content-Length: "
            + body.length()
            + "\r\n"
            + "\r\n"
            + body;
    out.write(response.getBytes());
    out.flush();
  }

  public int getPort() {
    return port;
  }
}
