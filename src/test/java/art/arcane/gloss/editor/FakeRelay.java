package art.arcane.gloss.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The server side of the relay's {@code /agent} socket, enough to test the relay client: it accepts connections
 * (counting them), sends frames the test writes to the newest one and queues the frames the client sends. It
 * answers pings only while {@link #answerPings} is set, so a quiet relay can be healthy or half-open.
 */
final class FakeRelay implements AutoCloseable {
  private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
  private final ServerSocket server;
  final LinkedBlockingQueue<JsonObject> received = new LinkedBlockingQueue<>();
  final AtomicInteger connections = new AtomicInteger();
  volatile boolean answerPings;
  volatile String path;
  private volatile Socket socket;
  private volatile OutputStream output;

  FakeRelay() throws IOException {
    server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
    Thread.ofVirtual().start(this::acceptAll);
  }

  String url() {
    return "ws://127.0.0.1:" + server.getLocalPort();
  }

  private void acceptAll() {
    while (!server.isClosed()) {
      try {
        Socket accepted = server.accept();
        connections.incrementAndGet();
        Thread.ofVirtual().start(() -> serve(accepted));
      } catch (IOException closed) {
        return;
      }
    }
  }

  private void serve(Socket accepted) {
    try {
      socket = accepted;
      InputStream input = accepted.getInputStream();
      String head = readHead(input);
      path = head.split(" ")[1];
      String key = null;
      for (String line : head.split("\r\n")) {
        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:")) {
          key = line.substring(line.indexOf(':') + 1).strip();
        }
      }
      String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
          .digest((key + GUID).getBytes(StandardCharsets.US_ASCII)));
      OutputStream connection = accepted.getOutputStream();
      synchronized (this) {
        connection.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        connection.flush();
        output = connection;
      }
      while (true) {
        String text = readFrame(input, (byte[] ping) -> pong(connection, ping));
        if (text == null) {
          return;
        }
        received.add(JsonParser.parseString(text).getAsJsonObject());
      }
    } catch (Exception gone) {
      // The client or the test closed the socket.
    }
  }

  private void pong(OutputStream connection, byte[] payload) {
    if (!answerPings) {
      return;
    }
    synchronized (this) {
      try {
        connection.write(0x8A);
        connection.write(payload.length);
        connection.write(payload);
        connection.flush();
      } catch (IOException gone) {
        // The client left.
      }
    }
  }

  /** Waits for the connection and sends one text frame. */
  void send(JsonObject frame) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (output == null && System.nanoTime() < deadline) {
      Thread.sleep(10L);
    }
    byte[] payload = frame.toString().getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    encoded.write(0x81);
    if (payload.length < 126) {
      encoded.write(payload.length);
    } else if (payload.length < 65536) {
      encoded.write(126);
      encoded.write(payload.length >>> 8);
      encoded.write(payload.length & 0xff);
    } else {
      encoded.write(127);
      for (int shift = 56; shift >= 0; shift -= 8) {
        encoded.write((int) (((long) payload.length >>> shift) & 0xff));
      }
    }
    encoded.writeBytes(payload);
    synchronized (this) {
      output.write(encoded.toByteArray());
      output.flush();
    }
  }

  /** The next frame the client sent, of this type, within ten seconds. */
  JsonObject next(String type) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (true) {
      JsonObject frame = received.poll(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      if (frame == null) {
        throw new AssertionError("no " + type + " frame from the relay client");
      }
      if (frame.get("type").getAsString().equals(type)) {
        return frame;
      }
    }
  }

  private static String readHead(InputStream input) throws IOException {
    ByteArrayOutputStream head = new ByteArrayOutputStream();
    int state = 0;
    while (state < 4) {
      int read = input.read();
      if (read < 0) {
        throw new IOException("closed");
      }
      head.write(read);
      state = (read == '\r' && (state == 0 || state == 2)) || (read == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
    }
    return head.toString(StandardCharsets.ISO_8859_1);
  }

  /** One text message, joined from its fragments; null when the client closes. Pings go to {@code pings}. */
  private static String readFrame(InputStream input, Consumer<byte[]> pings) throws IOException {
    ByteArrayOutputStream message = new ByteArrayOutputStream();
    boolean inText = false;
    while (true) {
      int first = input.read();
      int second = input.read();
      if (first < 0 || second < 0) {
        return null;
      }
      long length = second & 0x7f;
      if (length == 126) {
        length = ((long) input.read() << 8) | input.read();
      } else if (length == 127) {
        length = 0;
        for (int index = 0; index < 8; index++) {
          length = (length << 8) | input.read();
        }
      }
      byte[] mask = (second & 0x80) != 0 ? input.readNBytes(4) : new byte[4];
      byte[] payload = input.readNBytes((int) length);
      for (int index = 0; index < payload.length; index++) {
        payload[index] ^= mask[index % 4];
      }
      int opcode = first & 0x0f;
      boolean fin = (first & 0x80) != 0;
      if (opcode == 0x8) {
        return null;
      }
      if (opcode == 0x9) {
        pings.accept(payload);
        continue;
      }
      if (opcode == 0x1 || opcode == 0x0 && inText) {
        message.writeBytes(payload);
        inText = !fin;
        if (fin) {
          return message.toString(StandardCharsets.UTF_8);
        }
      }
    }
  }

  @Override
  public void close() throws IOException {
    server.close();
    Socket current = socket;
    if (current != null) {
      current.close();
    }
  }
}
