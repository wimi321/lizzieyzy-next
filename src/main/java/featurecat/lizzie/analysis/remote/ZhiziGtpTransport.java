package featurecat.lizzie.analysis.remote;

import io.socket.client.IO;
import io.socket.client.Manager;
import io.socket.client.Socket;
import io.socket.engineio.client.transports.WebSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;

public class ZhiziGtpTransport implements EngineTransport {
  private static final Duration READY_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration READY_FALLBACK_AFTER_RECONNECT = Duration.ofSeconds(4);
  private static final Duration RECONNECT_GRACE_PERIOD = Duration.ofSeconds(45);

  private final ZhiziApiClient apiClient;
  private final String accountToken;
  private final String args;
  private final BlockingByteInputStream stdout = new BlockingByteInputStream();
  private final BlockingByteInputStream stderr = new BlockingByteInputStream();
  private final SocketCommandOutputStream stdin =
      new SocketCommandOutputStream(new SocketCommandEmitter(null));
  private final AtomicBoolean open = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(true);
  private final AtomicBoolean everReady = new AtomicBoolean(false);
  private final AtomicInteger connectGeneration = new AtomicInteger();
  private final AtomicInteger reconnectAttemptCount = new AtomicInteger();
  private final ScheduledExecutorService reconnectExecutor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "zhizi-remote-reconnect");
            thread.setDaemon(true);
            return thread;
          });
  private volatile ScheduledFuture<?> readyFallbackTask;
  private volatile long lastDisconnectAtMs;
  private Socket socket;
  private OkHttpClient socketHttpClient;

  public ZhiziGtpTransport(ZhiziApiClient apiClient, String accountToken, String args)
      throws IOException {
    this.apiClient = apiClient;
    this.accountToken = accountToken == null ? "" : accountToken.trim();
    this.args = args == null || args.trim().isEmpty() ? RemoteComputeConfig.DEFAULT_ZHIZI_ARGS : args;
  }

  public static ZhiziGtpTransport fromSavedConfig() throws IOException {
    RemoteComputeConfig.State state = RemoteComputeConfig.load();
    if (state.zhiziAccountToken == null || state.zhiziAccountToken.trim().isEmpty()) {
      throw new IOException("请先在“远程算力中心”登录智子云算力。");
    }
    return new ZhiziGtpTransport(new ZhiziApiClient(), state.zhiziAccountToken, state.zhiziArgs);
  }

  @Override
  public void start() throws IOException {
    if (accountToken.isEmpty()) {
      throw new IOException("请先登录智子云算力。");
    }
    closed.set(false);
    ZhiziApiClient.SocketToken socketToken;
    try {
      socketToken = apiClient.fetchSocketioToken(accountToken, args);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("连接智子云算力被中断。", e);
    }
    CountDownLatch readyLatch = new CountDownLatch(1);
    CountDownLatch errorLatch = new CountDownLatch(1);
    AtomicBoolean failedBeforeReady = new AtomicBoolean(false);
    AtomicReference<String> startupError = new AtomicReference<>("");
    try {
      IO.Options options =
          IO.Options.builder()
              .setPath("/socket.io.v4")
              .setQuery(
                  "zz-socketio-token="
                      + URLEncoder.encode(socketToken.token, StandardCharsets.UTF_8))
              .setTransports(new String[] {WebSocket.NAME})
              .setReconnection(true)
              .setReconnectionAttempts(Integer.MAX_VALUE)
              .setReconnectionDelay(1200)
              .setReconnectionDelayMax(8000)
              .setTimeout(30000)
              .build();
      socketHttpClient = new OkHttpClient();
      options.callFactory = socketHttpClient;
      options.webSocketFactory = socketHttpClient;
      socket = IO.socket(socketToken.socketIOURL, options);
    } catch (URISyntaxException e) {
      throw new IOException("智子云算力连接地址无效。", e);
    }
    attachReconnectDiagnostics(socket);
    socket.on(
        Socket.EVENT_CONNECT,
        objects -> {
          int generation = connectGeneration.incrementAndGet();
          Socket current = socket;
          writeStderrLine("智子云算力已连接，等待引擎准备...");
          if (everReady.get()) {
            scheduleReadyFallback(current, generation);
          }
        });
    socket.on(
        "ready",
        objects -> {
          markReady(socket, readyLatch, "智子云算力已准备好。");
        });
    socket.on("stdout", objects -> writePayload(stdout, first(objects)));
    socket.on("stderr", objects -> writePayload(stderr, first(objects)));
    socket.on(
        Socket.EVENT_DISCONNECT,
        objects -> {
          open.set(false);
          stdin.bind(new SocketCommandEmitter(null));
          cancelReadyFallback();
          lastDisconnectAtMs = System.currentTimeMillis();
          String reason = summarize(first(objects));
          String suffix = reason.isEmpty() ? "" : "（" + reason + "）";
          writeStderrLine("智子云算力连接断开" + suffix + "，正在自动重连...");
        });
    socket.on(
        Socket.EVENT_CONNECT_ERROR,
        objects -> {
          String error = summarize(first(objects));
          if (!everReady.get()) {
            failedBeforeReady.set(true);
            startupError.set(error);
          }
          writeStderrLine((everReady.get() ? "智子云算力重连暂未成功: " : "智子云算力连接失败: ") + error);
          errorLatch.countDown();
        });
    socket.connect();
    long deadline = System.currentTimeMillis() + READY_TIMEOUT.toMillis();
    try {
      while (!readyLatch.await(250, TimeUnit.MILLISECONDS)) {
        if (errorLatch.getCount() == 0
            && failedBeforeReady.get()
            && isProbablyFatalStartupError(startupError.get())) {
          throw new IOException("智子云算力连接失败，请检查网络、登录状态或账号额度。");
        }
        if (Thread.currentThread().isInterrupted()) {
          Thread.currentThread().interrupt();
          throw new IOException("连接智子云算力被中断。");
        }
        if (System.currentTimeMillis() >= deadline) {
          String lastError = startupError.get();
          close();
          throw new IOException(
              lastError == null || lastError.isBlank()
                  ? "智子云算力连接超时，请稍后重试。"
                  : "智子云算力连接超时，请稍后重试。最后错误：" + lastError);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("连接智子云算力被中断。", e);
    }
    if (!open.get()) {
      close();
      throw new IOException("智子云算力超时未准备好。");
    }
  }

  @Override
  public InputStream stdout() {
    return stdout;
  }

  @Override
  public OutputStream stdin() {
    return stdin;
  }

  @Override
  public InputStream stderr() {
    return stderr;
  }

  @Override
  public boolean isOpen() {
    Socket current = socket;
    if (closed.get() || current == null) {
      return false;
    }
    if (open.get() || current.connected() || current.isActive()) {
      return true;
    }
    return everReady.get()
        && lastDisconnectAtMs > 0
        && System.currentTimeMillis() - lastDisconnectAtMs <= RECONNECT_GRACE_PERIOD.toMillis();
  }

  @Override
  public String description() {
    return RemoteComputeConfig.displayNameForZhiziArgs(args);
  }

  @Override
  public void close() {
    closed.set(true);
    open.set(false);
    cancelReadyFallback();
    stdin.closeForShutdown();
    Socket current = socket;
    socket = null;
    if (current != null) {
      try {
        current.io().reconnection(false);
        current.emit("stdin", "quit\n");
      } catch (Exception ignored) {
      }
      current.io().off();
      current.off();
      current.disconnect();
      current.close();
    }
    reconnectExecutor.shutdownNow();
    closeSocketHttpClient();
    closeQuietly(stdout);
    closeQuietly(stderr);
  }

  private void attachReconnectDiagnostics(Socket socket) {
    Manager manager = socket.io();
    manager.on(
        Manager.EVENT_RECONNECT_ATTEMPT,
        objects -> {
          int countedAttempt = reconnectAttemptCount.incrementAndGet();
          String attempt = summarize(first(objects));
          if (attempt.isEmpty()) {
            attempt = String.valueOf(countedAttempt);
          }
          writeStderrLine(
              "智子云算力正在重连（第 " + attempt + " 次）...");
        });
    manager.on(
        Manager.EVENT_RECONNECT,
        objects -> {
          String attempt = summarize(first(objects));
          reconnectAttemptCount.set(0);
          writeStderrLine(
              attempt.isEmpty()
                  ? "智子云算力网络已恢复，等待引擎 ready..."
                  : "智子云算力网络已恢复（第 " + attempt + " 次），等待引擎 ready...");
        });
    manager.on(
        Manager.EVENT_RECONNECT_ERROR,
        objects -> writeStderrLine("智子云算力重连错误: " + summarize(first(objects))));
    manager.on(
        Manager.EVENT_RECONNECT_FAILED,
        objects -> writeStderrLine("智子云算力重连失败，请检查网络或稍后切回本机引擎。"));
  }

  private void markReady(Socket readySocket, CountDownLatch readyLatch, String message) {
    if (closed.get() || readySocket == null) {
      return;
    }
    boolean reconnectReady = everReady.get();
    cancelReadyFallback();
    open.set(true);
    everReady.set(true);
    stdin.bind(new SocketCommandEmitter(readySocket));
    int flushed = stdin.flushQueuedCommands();
    boolean resumedAnalysis = reconnectReady && stdin.resumeLastAnalysisIfIdle();
    String suffix = "";
    if (flushed > 0) {
      suffix += " 已补发重连期间等待的 " + flushed + " 条命令。";
    }
    if (reconnectReady && resumedAnalysis) {
      suffix += " 已恢复实时分析。";
    }
    writeStderrLine(
        suffix.isEmpty() ? message : message + suffix);
    readyLatch.countDown();
  }

  private void scheduleReadyFallback(Socket expectedSocket, int expectedGeneration) {
    cancelReadyFallback();
    readyFallbackTask =
        reconnectExecutor.schedule(
            () -> {
              if (closed.get()
                  || open.get()
                  || socket != expectedSocket
                  || connectGeneration.get() != expectedGeneration
                  || expectedSocket == null
                  || !expectedSocket.connected()) {
                return;
              }
              markReady(
                  expectedSocket,
                  new CountDownLatch(0),
                  "智子云算力网络已恢复，未收到新的 ready，已恢复命令通道。");
            },
            READY_FALLBACK_AFTER_RECONNECT.toMillis(),
            TimeUnit.MILLISECONDS);
  }

  private void cancelReadyFallback() {
    ScheduledFuture<?> task = readyFallbackTask;
    readyFallbackTask = null;
    if (task != null) {
      task.cancel(false);
    }
  }

  private boolean isProbablyFatalStartupError(String message) {
    String normalized = message == null ? "" : message.toLowerCase();
    return normalized.contains("401")
        || normalized.contains("403")
        || normalized.contains("unauthorized")
        || normalized.contains("forbidden")
        || normalized.contains("invalid token")
        || normalized.contains("expired token")
        || normalized.contains("worker")
        || normalized.contains("quota")
        || normalized.contains("balance")
        || normalized.contains("vip")
        || normalized.contains("权限")
        || normalized.contains("额度")
        || normalized.contains("余额")
        || normalized.contains("套餐");
  }

  private void closeSocketHttpClient() {
    OkHttpClient client = socketHttpClient;
    socketHttpClient = null;
    if (client == null) {
      return;
    }
    client.dispatcher().cancelAll();
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
  }

  private Object first(Object[] objects) {
    return objects == null || objects.length == 0 ? "" : objects[0];
  }

  private void writePayload(BlockingByteInputStream sink, Object payload) {
    String text = decodePayload(payload);
    if (text.isEmpty()) {
      return;
    }
    sink.append(text.getBytes(StandardCharsets.UTF_8));
  }

  private void writeStderrLine(String line) {
    writePayload(stderr, "[remote] " + line + "\n");
  }

  static String decodePayload(Object payload) {
    if (payload == null) {
      return "";
    }
    if (payload instanceof String) {
      return (String) payload;
    }
    if (payload instanceof byte[]) {
      return new String((byte[]) payload, StandardCharsets.UTF_8);
    }
    if (payload instanceof ByteBuffer) {
      ByteBuffer duplicate = ((ByteBuffer) payload).duplicate();
      byte[] bytes = new byte[duplicate.remaining()];
      duplicate.get(bytes);
      return new String(bytes, StandardCharsets.UTF_8);
    }
    return String.valueOf(payload);
  }

  private String summarize(Object payload) {
    String text = decodePayload(payload).replaceAll("\\s+", " ").trim();
    if (text.length() <= 160) {
      return text;
    }
    return text.substring(0, 160) + "...";
  }

  private static void closeQuietly(AutoCloseable closeable) {
    try {
      if (closeable != null) {
        closeable.close();
      }
    } catch (Exception ignored) {
    }
  }

  static final class BlockingByteInputStream extends InputStream {
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    private int firstChunkOffset;
    private int availableBytes;
    private boolean closed;

    synchronized void append(byte[] bytes) {
      if (closed || bytes == null || bytes.length == 0) {
        return;
      }
      chunks.add(Arrays.copyOf(bytes, bytes.length));
      availableBytes += bytes.length;
      notifyAll();
    }

    @Override
    public synchronized int read() throws IOException {
      waitForData();
      if (availableBytes == 0) {
        return -1;
      }
      byte[] chunk = chunks.peek();
      int value = chunk[firstChunkOffset] & 0xff;
      firstChunkOffset++;
      availableBytes--;
      discardConsumedChunkIfNeeded(chunk);
      return value;
    }

    @Override
    public synchronized int read(byte[] buffer, int offset, int length) throws IOException {
      if (buffer == null) {
        throw new NullPointerException("buffer");
      }
      if (offset < 0 || length < 0 || length > buffer.length - offset) {
        throw new IndexOutOfBoundsException();
      }
      if (length == 0) {
        return 0;
      }
      waitForData();
      if (availableBytes == 0) {
        return -1;
      }
      int total = 0;
      while (length > 0 && availableBytes > 0) {
        byte[] chunk = chunks.peek();
        int count = Math.min(length, chunk.length - firstChunkOffset);
        System.arraycopy(chunk, firstChunkOffset, buffer, offset, count);
        firstChunkOffset += count;
        availableBytes -= count;
        offset += count;
        length -= count;
        total += count;
        discardConsumedChunkIfNeeded(chunk);
      }
      return total;
    }

    @Override
    public synchronized int available() {
      return availableBytes;
    }

    @Override
    public synchronized void close() {
      closed = true;
      chunks.clear();
      firstChunkOffset = 0;
      availableBytes = 0;
      notifyAll();
    }

    private void waitForData() throws IOException {
      while (availableBytes == 0 && !closed) {
        try {
          wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          InterruptedIOException interrupted =
              new InterruptedIOException("Interrupted while waiting for remote output.");
          interrupted.initCause(e);
          throw interrupted;
        }
      }
    }

    private void discardConsumedChunkIfNeeded(byte[] chunk) {
      if (firstChunkOffset < chunk.length) {
        return;
      }
      chunks.remove();
      firstChunkOffset = 0;
    }
  }

  static final class SocketCommandOutputStream extends OutputStream {
    private static final int MAX_QUEUED_COMMAND_BYTES = 256 * 1024;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final ArrayDeque<String> queuedCommands = new ArrayDeque<>();
    private int queuedCommandBytes;
    private String lastAnalysisCommand = "";
    private boolean lastQueuedFlushHadAnalysis;
    private volatile CommandEmitter emitter;
    private volatile boolean closed;

    SocketCommandOutputStream(CommandEmitter emitter) {
      this.emitter = emitter == null ? new SocketCommandEmitter(null) : emitter;
    }

    void bind(CommandEmitter emitter) {
      this.emitter = emitter == null ? new SocketCommandEmitter(null) : emitter;
    }

    synchronized int flushQueuedCommands() {
      CommandEmitter current = emitter;
      lastQueuedFlushHadAnalysis = false;
      if (closed || current == null || !current.isConnected() || queuedCommands.isEmpty()) {
        return 0;
      }
      int flushed = 0;
      while (!queuedCommands.isEmpty() && current.isConnected()) {
        String command = queuedCommands.removeFirst();
        queuedCommandBytes -= command.getBytes(StandardCharsets.UTF_8).length;
        if (isAnalysisCommand(command)) {
          lastQueuedFlushHadAnalysis = true;
        }
        current.emit(command);
        flushed++;
      }
      if (queuedCommands.isEmpty()) {
        queuedCommandBytes = 0;
      }
      return flushed;
    }

    synchronized boolean resumeLastAnalysisIfIdle() {
      if (closed || lastQueuedFlushHadAnalysis || lastAnalysisCommand.isEmpty()) {
        return false;
      }
      CommandEmitter current = emitter;
      if (current == null || !current.isConnected()) {
        return false;
      }
      current.emit(lastAnalysisCommand);
      return true;
    }

    void closeForShutdown() {
      closed = true;
      emitter = new SocketCommandEmitter(null);
      synchronized (this) {
        buffer.reset();
        queuedCommands.clear();
        queuedCommandBytes = 0;
      }
    }

    @Override
    public synchronized void write(int b) {
      buffer.write(b);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
      buffer.write(b, off, len);
    }

    @Override
    public synchronized void flush() throws IOException {
      if (buffer.size() == 0) {
        flushQueuedCommands();
        return;
      }
      if (closed) {
        buffer.reset();
        throw new IOException("智子云算力连接已关闭。");
      }
      String command = buffer.toString(StandardCharsets.UTF_8);
      buffer.reset();
      rememberCommand(command);
      CommandEmitter current = emitter;
      if (current != null && current.isConnected()) {
        current.emit(command);
        return;
      }
      queueCommand(command);
    }

    int queuedCommandCount() {
      return queuedCommands.size();
    }

    private void queueCommand(String command) throws IOException {
      int commandBytes = command.getBytes(StandardCharsets.UTF_8).length;
      if (queuedCommandBytes + commandBytes > MAX_QUEUED_COMMAND_BYTES) {
        throw new IOException("智子云算力重连时间过长，等待发送的命令过多，请稍后重试。");
      }
      queuedCommands.addLast(command);
      queuedCommandBytes += commandBytes;
    }

    private void rememberCommand(String command) {
      if (isStopCommand(command)) {
        lastAnalysisCommand = "";
      } else if (isAnalysisCommand(command)) {
        lastAnalysisCommand = command;
      }
    }

    private static boolean isAnalysisCommand(String command) {
      String normalized = firstCommandLine(command).trim();
      return normalized.startsWith("kata-analyze ")
          || normalized.startsWith("kata-analyze_interval ")
          || normalized.equals("kata-raw-nn")
          || normalized.startsWith("kata-raw-nn ")
          || normalized.startsWith("lz-analyze ")
          || normalized.startsWith("analyze ");
    }

    private static boolean isStopCommand(String command) {
      String normalized = firstCommandLine(command).trim();
      return normalized.equals("stop")
          || normalized.equals("stop-ponder")
          || normalized.equals("quit");
    }

    private static String firstCommandLine(String command) {
      if (command == null) {
        return "";
      }
      int newline = command.indexOf('\n');
      return newline >= 0 ? command.substring(0, newline) : command;
    }
  }

  interface CommandEmitter {
    boolean isConnected();

    void emit(String command);
  }

  static final class SocketCommandEmitter implements CommandEmitter {
    private final Socket socket;

    SocketCommandEmitter(Socket socket) {
      this.socket = socket;
    }

    @Override
    public boolean isConnected() {
      return socket != null && socket.connected();
    }

    @Override
    public void emit(String command) {
      socket.emit("stdin", command);
    }
  }
}
