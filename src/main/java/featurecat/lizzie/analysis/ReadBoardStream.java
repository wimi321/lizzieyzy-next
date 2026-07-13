package featurecat.lizzie.analysis;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
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
      in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
      out = new BufferedOutputStream(socket.getOutputStream());
      start();
    } catch (Exception e) {
      e.printStackTrace();
    }
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
