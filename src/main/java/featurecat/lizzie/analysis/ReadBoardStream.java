package featurecat.lizzie.analysis;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import org.json.JSONException;

public class ReadBoardStream extends Thread implements Closeable {

  private final ReadBoard owner;
  private Socket socket = null;
  private BufferedReader in;
  private BufferedOutputStream out;
  private volatile boolean closed;

  public ReadBoardStream(ReadBoard owner, Socket s) {
    this.owner = owner;
    socket = s;
    try {
      in = utf8Reader(socket.getInputStream());
      out = new BufferedOutputStream(socket.getOutputStream());
      start();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Test constructor: bind a UTF-8 byte stream without a socket or reader thread. {@link #run()}
   * still performs the production line-framing loop.
   */
  ReadBoardStream(ReadBoard owner, InputStream rawInput) {
    this.owner = owner;
    try {
      in = utf8Reader(rawInput);
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static BufferedReader utf8Reader(InputStream rawInput)
      throws UnsupportedEncodingException {
    return new BufferedReader(new InputStreamReader(rawInput, "UTF-8"));
  }

  @Override
  public void run() {
    String line;
    try {
      while (!closed && (line = in.readLine()) != null) {
        if (closed) {
          break;
        }
        //  System.out.println(line);
        owner.parseLine(line);
        if (line.equals("ready")) owner.handleReady();
      }
    } catch (NumberFormatException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (JSONException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      // e.printStackTrace();
    }
  }

  public void sendCommand(String command) {
    if (out == null) {
      return;
    }
    try {
      out.write((command + "\n").getBytes());
      out.flush();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      //  e.printStackTrace();
    }
  }

  @Override
  public void close() throws IOException {
    closed = true;
    IOException closeFailure = null;
    try {
      if (socket != null) {
        socket.close();
      }
    } catch (IOException ex) {
      closeFailure = ex;
    }
    try {
      if (out != null) {
        out.close();
      }
    } catch (IOException ex) {
      if (closeFailure == null) {
        closeFailure = ex;
      }
    }
    try {
      if (in != null) {
        in.close();
      }
    } catch (IOException ex) {
      if (closeFailure == null) {
        closeFailure = ex;
      }
    }
    if (closeFailure != null) {
      throw closeFailure;
    }
  }
}
