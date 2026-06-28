package featurecat.lizzie.analysis.remote;

import io.socket.client.IO;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;

public class ZhiziGtpTransport implements EngineTransport {
  private static final Duration READY_TIMEOUT = Duration.ofSeconds(60);

  private final ZhiziApiClient apiClient;
  private final String accountToken;
  private final String args;
  private final BlockingByteInputStream stdout = new BlockingByteInputStream();
  private final BlockingByteInputStream stderr = new BlockingByteInputStream();
  private final SocketCommandOutputStream stdin = new SocketCommandOutputStream();
  private final AtomicBoolean open = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(true);
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
    socket.on(Socket.EVENT_CONNECT, objects -> writeStderrLine("智子云算力已连接，等待引擎准备..."));
    socket.on(
        "ready",
        objects -> {
          open.set(true);
          stdin.bind(socket);
          writeStderrLine("智子云算力已准备好。");
          readyLatch.countDown();
        });
    socket.on("stdout", objects -> writePayload(stdout, first(objects)));
    socket.on("stderr", objects -> writePayload(stderr, first(objects)));
    socket.on(
        Socket.EVENT_DISCONNECT,
        objects -> {
          open.set(false);
          stdin.bind(null);
          writeStderrLine("智子云算力连接断开，正在自动重连...");
        });
    socket.on(
        Socket.EVENT_CONNECT_ERROR,
        objects -> {
          failedBeforeReady.set(true);
          writeStderrLine("智子云算力连接失败: " + summarize(first(objects)));
          errorLatch.countDown();
        });
    socket.connect();
    long deadline = System.currentTimeMillis() + READY_TIMEOUT.toMillis();
    try {
      while (!readyLatch.await(250, TimeUnit.MILLISECONDS)) {
        if (errorLatch.getCount() == 0 && failedBeforeReady.get()) {
          throw new IOException("智子云算力连接失败，请检查网络、登录状态或账号额度。");
        }
        if (Thread.currentThread().isInterrupted()) {
          Thread.currentThread().interrupt();
          throw new IOException("连接智子云算力被中断。");
        }
        if (System.currentTimeMillis() >= deadline) {
          close();
          throw new IOException("智子云算力连接超时，请稍后重试。");
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
    return !closed.get() && current != null && (open.get() || current.connected() || current.isActive());
  }

  @Override
  public String description() {
    return RemoteComputeConfig.displayNameForZhiziArgs(args);
  }

  @Override
  public void close() {
    closed.set(true);
    open.set(false);
    stdin.bind(null);
    Socket current = socket;
    socket = null;
    if (current != null) {
      try {
        current.io().reconnection(false);
        current.emit("stdin", "quit\n");
      } catch (Exception ignored) {
      }
      current.off();
      current.disconnect();
      current.close();
    }
    closeSocketHttpClient();
    closeQuietly(stdout);
    closeQuietly(stderr);
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

  private static final class SocketCommandOutputStream extends OutputStream {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private volatile Socket socket;

    void bind(Socket socket) {
      this.socket = socket;
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
      Socket current = socket;
      if (current == null || !current.connected()) {
        buffer.reset();
        throw new IOException("智子云算力暂未连接。");
      }
      String command = buffer.toString(StandardCharsets.UTF_8);
      buffer.reset();
      current.emit("stdin", command);
    }
  }
}
