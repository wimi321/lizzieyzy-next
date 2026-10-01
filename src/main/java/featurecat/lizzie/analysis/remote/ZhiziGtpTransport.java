package featurecat.lizzie.analysis.remote;

import featurecat.lizzie.util.NetworkProxy;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.engineio.client.transports.WebSocket;
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
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;

public class ZhiziGtpTransport implements EngineTransport {
  private static final Duration READY_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration ANALYSIS_RESPONSE_TIMEOUT = Duration.ofSeconds(20);
  private static final int MAX_START_ATTEMPTS = 3;
  private static final String SMOKE_DISCONNECT_DELAY_PROPERTY =
      "lizzie.smoke.zhiziDisconnectAfterReadyMs";
  private static final long MAX_SMOKE_DISCONNECT_DELAY_MILLIS = 60_000L;
  private static final AtomicBoolean SMOKE_DISCONNECT_SCHEDULED = new AtomicBoolean(false);

  private final ZhiziApiClient apiClient;
  private final String accountToken;
  private final String args;
  private final BlockingByteInputStream stdout = new BlockingByteInputStream();
  private final BlockingByteInputStream stderr = new BlockingByteInputStream();
  private final SocketCommandOutputStream stdin;
  private final AtomicBoolean open = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(true);
  private final AtomicBoolean gracefulCloseStarted = new AtomicBoolean(false);
  private final AtomicBoolean abortStarted = new AtomicBoolean(false);
  private final AtomicBoolean recoveryRequested = new AtomicBoolean(false);
  private final SessionLifecycle lifecycle = new SessionLifecycle();
  private final ScheduledExecutorService reconnectExecutor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "zhizi-remote-reconnect");
            thread.setDaemon(true);
            return thread;
          });
  private final AnalysisResponseWatchdog analysisWatchdog;
  private volatile Socket socket;
  private volatile OkHttpClient socketHttpClient;

  public ZhiziGtpTransport(ZhiziApiClient apiClient, String accountToken, String args)
      throws IOException {
    this.apiClient = apiClient;
    this.accountToken = accountToken == null ? "" : accountToken.trim();
    this.args =
        args == null || args.trim().isEmpty() ? RemoteComputeConfig.DEFAULT_ZHIZI_ARGS : args;
    this.analysisWatchdog =
        new AnalysisResponseWatchdog(
            reconnectExecutor,
            ANALYSIS_RESPONSE_TIMEOUT.toMillis(),
            () -> requestRecovery("智子云算力未返回分析结果，正在自动重建会话并恢复当前棋局..."));
    this.stdin =
        new SocketCommandOutputStream(
            new SocketCommandEmitter(null), analysisWatchdog::onCommandSubmittedOrEmitted);
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
    if (gracefulCloseStarted.get() || abortStarted.get()) {
      throw new IOException("智子云算力传输已经关闭。");
    }
    IOException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_START_ATTEMPTS; attempt++) {
      try {
        startSession();
        return;
      } catch (IOException failure) {
        lastFailure = failure;
        disposeSocketSession(false);
        boolean terminalFailure =
            Thread.currentThread().isInterrupted()
                || isFatalStartupFailure(startupFailureText(failure))
                || attempt >= MAX_START_ATTEMPTS;
        if (terminalFailure) {
          close();
          throw failure;
        }
        long retryDelayMillis = startupRetryDelayMillis(attempt);
        writeStderrLine(
            "智子云算力本次未准备好，"
                + Math.max(1L, (retryDelayMillis + 999L) / 1000L)
                + " 秒后自动重启连接（"
                + (attempt + 1)
                + "/"
                + MAX_START_ATTEMPTS
                + "）...");
        try {
          TimeUnit.MILLISECONDS.sleep(retryDelayMillis);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          close();
          throw new IOException("连接智子云算力被中断。", interrupted);
        }
      }
    }
    close();
    throw lastFailure == null ? new IOException("智子云算力自动重启后仍未准备好。") : lastFailure;
  }

  private void startSession() throws IOException {
    if (accountToken.isEmpty()) {
      throw new IOException("请先登录智子云算力。");
    }
    closed.set(false);
    long generation = lifecycle.beginAttempt();
    ZhiziApiClient.SocketToken socketToken;
    try {
      socketToken = apiClient.fetchSocketioToken(accountToken, args);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("连接智子云算力被中断。", e);
    }
    if (!lifecycle.tokenFetched(generation)) {
      throw new IOException("智子云算力连接已取消。");
    }
    CountDownLatch readyLatch = new CountDownLatch(1);
    CountDownLatch failureLatch = new CountDownLatch(1);
    AtomicReference<String> startupError = new AtomicReference<>("");
    Socket sessionSocket;
    try {
      IO.Options options =
          IO.Options.builder()
              .setPath("/socket.io.v4")
              .setQuery(
                  "zz-socketio-token="
                      + URLEncoder.encode(socketToken.token, StandardCharsets.UTF_8))
              .setTransports(new String[] {WebSocket.NAME})
              .setReconnection(false)
              .setTimeout(30000)
              .build();
      socketHttpClient = NetworkProxy.configure(new OkHttpClient.Builder()).build();
      options.callFactory = socketHttpClient;
      options.webSocketFactory = socketHttpClient;
      sessionSocket = IO.socket(socketToken.socketIOURL, options);
      socket = sessionSocket;
    } catch (URISyntaxException e) {
      lifecycle.startupFailed(generation);
      throw new IOException("智子云算力连接地址无效。", e);
    }
    sessionSocket.on(
        Socket.EVENT_CONNECT,
        objects -> {
          if (lifecycle.connected(generation)) {
            writeStderrLine("智子云算力已连接，等待引擎准备...");
          }
        });
    sessionSocket.on(
        "ready",
        objects -> {
          if (lifecycle.ready(generation)) {
            stdin.bind(new SocketCommandEmitter(sessionSocket));
            writeStderrLine("智子云算力已准备好。");
            readyLatch.countDown();
          }
        });
    sessionSocket.on(
        "stdout",
        objects -> {
          if (lifecycle.acceptsEngineOutput(generation) && socket == sessionSocket) {
            writePayload(stdout, first(objects));
          }
        });
    sessionSocket.on(
        "stderr",
        objects -> {
          if (lifecycle.acceptsDiagnostics(generation) && socket == sessionSocket) {
            writePayload(stderr, first(objects));
          }
        });
    sessionSocket.on(
        Socket.EVENT_DISCONNECT,
        objects -> {
          String reason = summarize(first(objects));
          String suffix = reason.isEmpty() ? "" : "（" + reason + "）";
          handleSessionFailure(generation, "智子云算力连接断开" + suffix, startupError, failureLatch);
        });
    sessionSocket.on(
        Socket.EVENT_CONNECT_ERROR,
        objects -> {
          String error = summarize(first(objects));
          handleSessionFailure(generation, "智子云算力连接失败: " + error, startupError, failureLatch);
        });
    sessionSocket.connect();
    long deadline = System.currentTimeMillis() + READY_TIMEOUT.toMillis();
    try {
      while (!readyLatch.await(250, TimeUnit.MILLISECONDS)) {
        if (failureLatch.getCount() == 0) {
          String detail = startupError.get();
          throw new IOException(
              detail == null || detail.isBlank() ? "智子云算力连接失败，正在重新建立会话。" : detail);
        }
        if (Thread.currentThread().isInterrupted()) {
          Thread.currentThread().interrupt();
          throw new IOException("连接智子云算力被中断。");
        }
        if (System.currentTimeMillis() >= deadline) {
          String lastError = startupError.get();
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
    if (!sessionSocket.connected() || !lifecycle.activate(generation)) {
      throw new IOException("智子云算力在启用前已断开，正在重新建立会话。");
    }
    open.set(true);
    scheduleSmokeDisconnect(sessionSocket);
  }

  private void scheduleSmokeDisconnect(Socket sessionSocket) {
    long delayMillis =
        smokeDisconnectDelayMillis(System.getProperty(SMOKE_DISCONNECT_DELAY_PROPERTY, ""));
    if (delayMillis <= 0L || !SMOKE_DISCONNECT_SCHEDULED.compareAndSet(false, true)) {
      return;
    }
    reconnectExecutor.schedule(
        () -> {
          if (closed.get()
              || socket != sessionSocket
              || !sessionSocket.connected()
              || !lifecycle.isActive()) {
            return;
          }
          writeStderrLine("智子云算力验收探针正在断开当前会话，以验证自动恢复。");
          sessionSocket.disconnect();
        },
        delayMillis,
        TimeUnit.MILLISECONDS);
  }

  static long smokeDisconnectDelayMillis(String value) {
    if (value == null || value.isBlank()) {
      return 0L;
    }
    try {
      long parsed = Long.parseLong(value.trim());
      return parsed <= 0L ? 0L : Math.min(parsed, MAX_SMOKE_DISCONNECT_DELAY_MILLIS);
    } catch (NumberFormatException ignored) {
      return 0L;
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
    return !closed.get()
        && !recoveryRequested.get()
        && current != null
        && current.connected()
        && open.get()
        && lifecycle.isActive();
  }

  @Override
  public void markAnalysisProgressAccepted(long totalPlayouts) {
    analysisWatchdog.onAnalysisProgressAccepted(totalPlayouts);
  }

  @Override
  public boolean isRecoveryRequested() {
    return recoveryRequested.get();
  }

  @Override
  public String description() {
    return RemoteComputeConfig.displayNameForZhiziArgs(args);
  }

  /** The model selected for this connection, not the currently edited/saved settings. */
  public String modelIdentifier() {
    return RemoteComputeConfig.kataWeightForArgs(args);
  }

  @Override
  public void close() {
    if (abortStarted.get() || !gracefulCloseStarted.compareAndSet(false, true)) {
      return;
    }
    terminateTransport(true);
  }

  @Override
  public void abort() {
    if (!abortStarted.compareAndSet(false, true)) {
      return;
    }
    terminateTransport(false);
  }

  private void terminateTransport(boolean sendQuit) {
    closed.set(true);
    open.set(false);
    lifecycle.close();
    analysisWatchdog.cancel();
    disposeSocketSession(sendQuit);
    stdin.closeForShutdown();
    reconnectExecutor.shutdownNow();
    closeQuietly(stdout);
    closeQuietly(stderr);
  }

  void disposeSocketSession(boolean sendQuit) {
    open.set(false);
    analysisWatchdog.suspend();
    stdin.bind(new SocketCommandEmitter(null));
    Socket current = socket;
    if (current != null) {
      if (!sendQuit) {
        // Break the physical client first so a concurrently blocked graceful emit can unwind.
        socket = null;
        closeSocketHttpClient();
      }
      try {
        current.io().reconnection(false);
        if (sendQuit) {
          current.emit("stdin", "quit\n");
        }
      } catch (Exception ignored) {
      }
      current.io().off();
      current.off();
      current.disconnect();
      current.close();
      if (socket == current) {
        socket = null;
      }
    }
    if (!closed.get()) {
      lifecycle.retireAttempt();
    }
    closeSocketHttpClient();
  }

  private void handleSessionFailure(
      long generation,
      String message,
      AtomicReference<String> startupError,
      CountDownLatch startupFailureLatch) {
    SessionFailureAction action = lifecycle.sessionFailed(generation);
    if (action == SessionFailureAction.STARTUP_FAILED) {
      open.set(false);
      startupError.set(message);
      writeStderrLine(message);
      startupFailureLatch.countDown();
    } else if (action == SessionFailureAction.RECOVERY_REQUIRED) {
      initiateRecovery(message + "，正在重建会话并恢复当前棋局...");
    }
  }

  private void requestRecovery(String message) {
    if (lifecycle.requestRecovery()) {
      initiateRecovery(message);
    }
  }

  private void initiateRecovery(String message) {
    if (closed.get() || !recoveryRequested.compareAndSet(false, true)) {
      return;
    }
    open.set(false);
    analysisWatchdog.suspend();
    stdin.invalidateForRecovery();
    writeStderrLine(message);
    // EOF retires the reader incarnation. Leelaz then creates a fresh transport, fetches a new
    // Socket.IO token, waits for the real ready event, and replays the complete board.
    stdout.finish();
  }

  static boolean isFatalStartupFailure(String message) {
    String normalized = message == null ? "" : message.toLowerCase();
    boolean workerCapacityProblem =
        normalized.contains("worker")
            && (normalized.contains("no worker")
                || normalized.contains("unavailable")
                || normalized.contains("busy")
                || normalized.contains("暂时")
                || normalized.contains("没有可用")
                || normalized.contains("无可用"));
    if (workerCapacityProblem
        && !normalized.contains("permission")
        && !normalized.contains("forbidden")
        && !normalized.contains("unauthorized")
        && !normalized.contains("未开通")
        && !normalized.contains("无权限")) {
      return false;
    }
    return normalized.contains("401")
        || normalized.contains("403")
        || normalized.contains("unauthorized")
        || normalized.contains("forbidden")
        || normalized.contains("invalid token")
        || normalized.contains("token invalid")
        || normalized.contains("expired token")
        || normalized.contains("token expired")
        || normalized.contains("quota")
        || normalized.contains("balance")
        || normalized.contains("vip")
        || normalized.contains("请先登录")
        || normalized.contains("登录失效")
        || normalized.contains("登录已过期")
        || normalized.contains("账号未")
        || normalized.contains("账号异常")
        || normalized.contains("权限")
        || normalized.contains("额度")
        || normalized.contains("余额")
        || normalized.contains("套餐");
  }

  static long startupRetryDelayMillis(int completedAttempt) {
    if (completedAttempt <= 1) {
      return 1500L;
    }
    return completedAttempt == 2 ? 4000L : 10_000L;
  }

  private static String startupFailureText(Throwable failure) {
    StringBuilder text = new StringBuilder();
    Throwable current = failure;
    while (current != null) {
      if (current.getMessage() != null && !current.getMessage().isBlank()) {
        if (text.length() > 0) {
          text.append(' ');
        }
        text.append(current.getMessage());
      }
      current = current.getCause();
    }
    return text.toString();
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

  enum SessionState {
    NEW,
    FETCHING_TOKEN,
    CONNECTING,
    WAITING_READY,
    READY,
    ACTIVE,
    RECOVERY_REQUIRED,
    FAILED,
    CLOSED
  }

  enum SessionFailureAction {
    IGNORED,
    STARTUP_FAILED,
    RECOVERY_REQUIRED
  }

  /** Serializes Socket.IO lifecycle events and rejects callbacks from retired connections. */
  static final class SessionLifecycle {
    private long generation;
    private SessionState state = SessionState.NEW;

    synchronized long beginAttempt() {
      generation++;
      state = SessionState.FETCHING_TOKEN;
      return generation;
    }

    synchronized boolean tokenFetched(long expectedGeneration) {
      return transition(expectedGeneration, SessionState.FETCHING_TOKEN, SessionState.CONNECTING);
    }

    synchronized boolean connected(long expectedGeneration) {
      return transition(expectedGeneration, SessionState.CONNECTING, SessionState.WAITING_READY);
    }

    synchronized boolean ready(long expectedGeneration) {
      return transition(expectedGeneration, SessionState.WAITING_READY, SessionState.READY);
    }

    synchronized boolean activate(long expectedGeneration) {
      return transition(expectedGeneration, SessionState.READY, SessionState.ACTIVE);
    }

    synchronized SessionFailureAction sessionFailed(long expectedGeneration) {
      if (expectedGeneration != generation
          || state == SessionState.FAILED
          || state == SessionState.RECOVERY_REQUIRED
          || state == SessionState.CLOSED) {
        return SessionFailureAction.IGNORED;
      }
      if (state == SessionState.ACTIVE) {
        state = SessionState.RECOVERY_REQUIRED;
        return SessionFailureAction.RECOVERY_REQUIRED;
      }
      state = SessionState.FAILED;
      return SessionFailureAction.STARTUP_FAILED;
    }

    synchronized boolean startupFailed(long expectedGeneration) {
      if (expectedGeneration != generation || state == SessionState.CLOSED) {
        return false;
      }
      state = SessionState.FAILED;
      return true;
    }

    synchronized boolean requestRecovery() {
      if (state != SessionState.ACTIVE) {
        return false;
      }
      state = SessionState.RECOVERY_REQUIRED;
      return true;
    }

    synchronized boolean acceptsEngineOutput(long expectedGeneration) {
      return expectedGeneration == generation
          && (state == SessionState.READY || state == SessionState.ACTIVE);
    }

    synchronized boolean acceptsDiagnostics(long expectedGeneration) {
      return expectedGeneration == generation
          && state != SessionState.FAILED
          && state != SessionState.RECOVERY_REQUIRED
          && state != SessionState.CLOSED;
    }

    synchronized boolean isActive() {
      return state == SessionState.ACTIVE;
    }

    synchronized SessionState state() {
      return state;
    }

    synchronized void retireAttempt() {
      generation++;
      if (state != SessionState.CLOSED) {
        state = SessionState.FAILED;
      }
    }

    synchronized void close() {
      generation++;
      state = SessionState.CLOSED;
    }

    private boolean transition(
        long expectedGeneration, SessionState expectedState, SessionState nextState) {
      if (expectedGeneration != generation || state != expectedState) {
        return false;
      }
      state = nextState;
      return true;
    }
  }

  static final class AnalysisResponseWatchdog {
    private final ScheduledExecutorService scheduler;
    private final long timeoutMillis;
    private final Runnable timeoutAction;
    private long generation;
    private long lastProgress = -1L;
    private boolean analysisActive;
    private boolean unresponsive;
    private ScheduledFuture<?> timeoutTask;

    AnalysisResponseWatchdog(
        ScheduledExecutorService scheduler, long timeoutMillis, Runnable timeoutAction) {
      this.scheduler = scheduler;
      this.timeoutMillis = Math.max(1L, timeoutMillis);
      this.timeoutAction = timeoutAction == null ? () -> {} : timeoutAction;
    }

    synchronized void onCommandSubmittedOrEmitted(String command) {
      if (SocketCommandOutputStream.isStopCommand(command)) {
        cancel();
        return;
      }
      if (!SocketCommandOutputStream.isContinuousAnalysisCommand(command)) {
        return;
      }
      analysisActive = true;
      lastProgress = -1L;
      unresponsive = false;
      armDeadline();
    }

    synchronized void onAnalysisProgressAccepted(long totalPlayouts) {
      if (!analysisActive || totalPlayouts == lastProgress) return;
      lastProgress = totalPlayouts;
      unresponsive = false;
      armDeadline();
    }

    synchronized void suspend() {
      generation++;
      analysisActive = false;
      lastProgress = -1L;
      unresponsive = false;
      cancelTask();
    }

    synchronized void cancel() {
      suspend();
    }

    synchronized boolean isUnresponsive() {
      return unresponsive;
    }

    private void armDeadline() {
      long expectedGeneration = ++generation;
      cancelTask();
      timeoutTask =
          scheduler.schedule(
              () -> expire(expectedGeneration), timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void expire(long expectedGeneration) {
      synchronized (this) {
        if (generation != expectedGeneration || !analysisActive || unresponsive) {
          return;
        }
        analysisActive = false;
        lastProgress = -1L;
        unresponsive = true;
        timeoutTask = null;
      }
      timeoutAction.run();
    }

    private void cancelTask() {
      ScheduledFuture<?> task = timeoutTask;
      timeoutTask = null;
      if (task != null) {
        task.cancel(false);
      }
    }
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

    synchronized void finish() {
      closed = true;
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
    private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
    private volatile CommandEmitter emitter;
    private final java.util.function.Consumer<String> commandStateListener;
    private volatile boolean closed;
    private volatile boolean invalidatedForRecovery;

    SocketCommandOutputStream(CommandEmitter emitter) {
      this(emitter, command -> {});
    }

    SocketCommandOutputStream(
        CommandEmitter emitter, java.util.function.Consumer<String> commandStateListener) {
      this.emitter = emitter == null ? new SocketCommandEmitter(null) : emitter;
      this.commandStateListener =
          commandStateListener == null ? command -> {} : commandStateListener;
    }

    void bind(CommandEmitter emitter) {
      this.emitter = emitter == null ? new SocketCommandEmitter(null) : emitter;
    }

    synchronized void invalidateForRecovery() {
      invalidatedForRecovery = true;
      emitter = new SocketCommandEmitter(null);
      buffer.reset();
    }

    synchronized void closeForShutdown() {
      closed = true;
      emitter = new SocketCommandEmitter(null);
      buffer.reset();
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
        return;
      }
      if (closed) {
        buffer.reset();
        throw new IOException("智子云算力连接已关闭。");
      }
      if (invalidatedForRecovery) {
        buffer.reset();
        throw new IOException("智子云算力会话正在重建，旧命令已作废。");
      }
      String command = buffer.toString(StandardCharsets.UTF_8);
      buffer.reset();
      CommandEmitter current = emitter;
      if (current == null || !current.isConnected()) {
        throw new IOException("智子云算力连接已断开，命令未发送。");
      }
      current.emit(command);
      commandStateListener.accept(command);
    }

    static boolean isContinuousAnalysisCommand(String command) {
      String normalized = normalizedCommand(command);
      return normalized.equals("kata-analyze")
          || normalized.startsWith("kata-analyze ")
          || normalized.equals("kata-analyze_interval")
          || normalized.startsWith("kata-analyze_interval ")
          || normalized.equals("lz-analyze")
          || normalized.startsWith("lz-analyze ")
          || normalized.equals("analyze")
          || normalized.startsWith("analyze ");
    }

    static boolean isStopCommand(String command) {
      String normalized = normalizedCommand(command);
      return normalized.equals("stop")
          || normalized.equals("stop-ponder")
          || normalized.equals("quit");
    }

    private static String normalizedCommand(String command) {
      String normalized = firstCommandLine(command).trim();
      int separator = normalized.indexOf(' ');
      if (separator > 0
          && normalized.substring(0, separator).chars().allMatch(Character::isDigit)) {
        return normalized.substring(separator + 1).trim();
      }
      return normalized;
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
