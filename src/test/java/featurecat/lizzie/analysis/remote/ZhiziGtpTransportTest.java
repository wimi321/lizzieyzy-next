package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ZhiziGtpTransportTest {
  @Test
  void decodePayloadSupportsStringBytesAndByteBuffer() {
    assertEquals("= name\n", ZhiziGtpTransport.decodePayload("= name\n"));
    assertEquals(
        "info move Q16\n",
        ZhiziGtpTransport.decodePayload("info move Q16\n".getBytes(StandardCharsets.UTF_8)));
    assertEquals(
        "? error\n",
        ZhiziGtpTransport.decodePayload(
            ByteBuffer.wrap("? error\n".getBytes(StandardCharsets.UTF_8))));
  }

  @Test
  void blockingOutputStreamSurvivesShortLivedWriterThread() throws Exception {
    ZhiziGtpTransport.BlockingByteInputStream stream =
        new ZhiziGtpTransport.BlockingByteInputStream();
    Thread writer =
        new Thread(() -> stream.append("= list_commands\n".getBytes(StandardCharsets.UTF_8)));
    writer.start();
    writer.join();

    assertEquals(
        "= list_commands\n",
        new String(stream.readNBytes("= list_commands\n".length()), StandardCharsets.UTF_8));
    assertEquals(0, stream.available());
  }

  @Test
  void blockingOutputStreamReturnsEofAfterClose() throws Exception {
    InputStream stream = new ZhiziGtpTransport.BlockingByteInputStream();

    stream.close();

    assertEquals(-1, stream.read());
  }
}
