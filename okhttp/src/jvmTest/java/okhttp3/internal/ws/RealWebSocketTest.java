/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.ws;

import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.util.Random;
import okhttp3.Headers;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.TestUtil;
import okhttp3.internal.concurrent.TaskFaker;
import okio.ByteString;
import okio.Okio;
import okio.Pipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static okhttp3.internal.ws.RealWebSocket.DEFAULT_MINIMUM_DEFLATE_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("Slow")
public final class RealWebSocketTest {
  // NOTE: Fields are named 'client' and 'server' for cognitive simplicity. This differentiation has
  // zero effect on the behavior of the WebSocket API which is why tests are only written once
  // from the perspective of a single peer.

  private final Random random = new Random(0);
  private final Pipe client2Server = new Pipe(8192L);
  private final Pipe server2client = new Pipe(8192L);

  private final TaskFaker taskFaker = new TaskFaker();
  private final TestStreams client = new TestStreams(
    true, taskFaker, server2client, client2Server);
  private final TestStreams server = new TestStreams(
    false, taskFaker, client2Server, server2client);

  @BeforeEach public void setUp() throws IOException {
    client.initWebSocket(random, 0);
    server.initWebSocket(random, 0);
  }

  @AfterEach public void tearDown() throws Exception {
    client.listener.assertExhausted();
    server.listener.assertExhausted();
    server.getSource().close();
    client.getSource().close();
    server.webSocket.tearDown();
    client.webSocket.tearDown();
    taskFaker.close();
  }

  @Test public void close() throws IOException {
    client.webSocket.close(1000, "Hello!");
    taskFaker.runTasks();
    // This will trigger a close response.
    assertThat(server.processNextFrame()).isFalse();
    server.listener.assertClosing(1000, "Hello!");
    server.webSocket.finishReader();
    server.webSocket.close(1000, "Goodbye!");
    assertThat(client.processNextFrame()).isFalse();
    client.listener.assertClosing(1000, "Goodbye!");
    client.webSocket.finishReader();
    server.listener.assertClosed(1000, "Hello!");
    client.listener.assertClosed(1000, "Goodbye!");
  }

  @Test public void clientCloseThenMethodsReturnFalse() throws IOException {
    client.webSocket.close(1000, "Hello!");

    assertThat(client.webSocket.close(1000, "Hello!")).isFalse();
    assertThat(client.webSocket.send("Hello!")).isFalse();
  }

  @Test public void clientCloseWith0Fails() throws IOException {
    try {
      client.webSocket.close(0, null);
      fail();
    } catch (IllegalArgumentException expected) {
      assertThat("Code must be in range [1000,5000): 0").isEqualTo(expected.getMessage());
    }
  }

  @Test public void afterSocketClosedPingFailsWebSocket() throws IOException {
    client2Server.source().close();
    client.webSocket.pong(ByteString.encodeUtf8("Ping!"));
    taskFaker.runTasks();
    client.listener.assertFailure(IOException.class, "source is closed");

    assertThat(client.webSocket.send("Hello!")).isFalse();
  }

  @Test public void socketClosedDuringMessageKillsWebSocket() throws IOException {
    client2Server.source().close();

    assertThat(client.webSocket.send("Hello!")).isTrue();
    taskFaker.runTasks();
    client.listener.assertFailure(IOException.class, "source is closed");

    // A failed write prevents further use of the WebSocket instance.
    assertThat(client.webSocket.send("Hello!")).isFalse();
    assertThat(client.webSocket.pong(ByteString.encodeUtf8("Ping!"))).isFalse();
  }

  @Test public void serverCloseThenWritingPingSucceeds() throws IOException {
    server.webSocket.close(1000, "Hello!");
    taskFaker.runTasks();
    client.processNextFrame();
    client.listener.assertClosing(1000, "Hello!");

    assertThat(client.webSocket.pong(ByteString.encodeUtf8("Pong?"))).isTrue();
  }

  @Test public void clientCanWriteMessagesAfterServerClose() throws IOException {
    server.webSocket.close(1000, "Hello!");
    taskFaker.runTasks();
    client.processNextFrame();
    client.listener.assertClosing(1000, "Hello!");

    assertThat(client.webSocket.send("Hi!")).isTrue();
    server.processNextFrame();
    server.listener.assertTextMessage("Hi!");
  }

  @Test public void serverCloseThenClientClose() throws IOException {
    server.webSocket.close(1000, "Hello!");
    taskFaker.runTasks();

    client.processNextFrame();
    client.listener.assertClosing(1000, "Hello!");
    assertThat(client.webSocket.close(1000, "Bye!")).isTrue();
    client.webSocket.finishReader();
    client.listener.assertClosed(1000, "Hello!");

    server.processNextFrame();
    server.listener.assertClosing(1000, "Bye!");
    server.webSocket.finishReader();
    server.listener.assertClosed(1000, "Bye!");
  }

  @Test public void emptyCloseInitiatesShutdown() throws IOException {
    server.getSink().write(ByteString.decodeHex("8800")).emit(); // Close without code.
    client.processNextFrame();
    client.listener.assertClosing(1005, "");
    client.webSocket.finishReader();

    assertThat(client.webSocket.close(1000, "Bye!")).isTrue();
    taskFaker.runTasks();
    server.processNextFrame();
    server.listener.assertClosing(1000, "Bye!");
    server.webSocket.finishReader();

    client.listener.assertClosed(1005, "");
  }

  @Test public void clientCloseClosesConnection() throws IOException {
    client.webSocket.close(1000, "Hello!");
    taskFaker.runTasks();
    assertThat(client.closed).isFalse();
    server.processNextFrame(); // Read client closing, send server close.
    server.listener.assertClosing(1000, "Hello!");
    server.webSocket.finishReader();

    server.webSocket.close(1000, "Goodbye!");
    client.processNextFrame(); // Read server closing, close connection.
    taskFaker.runTasks();
    client.listener.assertClosing(1000, "Goodbye!");
    client.webSocket.finishReader();
    assertThat(client.closed).isTrue();

    // Server and client both finished closing, connection is closed.
    server.listener.assertClosed(1000, "Hello!");
    client.listener.assertClosed(1000, "Goodbye!");
  }

  @Test public void serverCloseClosesConnection() throws IOException {
    server.webSocket.close(1000, "Hello!");
    taskFaker.runTasks();

    client.processNextFrame(); // Read server close, send client close, close connection.
    assertThat(client.closed).isFalse();
    client.listener.assertClosing(1000, "Hello!");
    client.webSocket.finishReader();

    client.webSocket.close(1000, "Hello!");
    server.processNextFrame();
    server.listener.assertClosing(1000, "Hello!");
    server.webSocket.finishReader();

    client.listener.assertClosed(1000, "Hello!");
    server.listener.assertClosed(1000, "Hello!");
  }

  @Test public void clientAndServerCloseClosesConnection() throws Exception {
    // Send close from both sides at the same time.
    server.webSocket.close(1000, "Hello!");
    taskFaker.runTasks();
    client.processNextFrame(); // Read close, close connection close.

    assertThat(client.closed).isFalse();
    client.webSocket.close(1000, "Hi!");
    server.processNextFrame();

    client.listener.assertClosing(1000, "Hello!");
    server.listener.assertClosing(1000, "Hi!");
    client.webSocket.finishReader();
    server.webSocket.finishReader();
    client.listener.assertClosed(1000, "Hello!");
    server.listener.assertClosed(1000, "Hi!");
    taskFaker.runTasks();
    assertThat(client.closed).isTrue();

    server.listener.assertExhausted(); // Client should not have sent second close.
    client.listener.assertExhausted(); // Server should not have sent second close.
  }

  @Test public void serverCloseBreaksReadMessageLoop() throws IOException {
    server.webSocket.send("Hello!");
    server.webSocket.close(1000, "Bye!");
    taskFaker.runTasks();
    assertThat(client.processNextFrame()).isTrue();
    client.listener.assertTextMessage("Hello!");
    assertThat(client.processNextFrame()).isFalse();
    client.listener.assertClosing(1000, "Bye!");
  }

  @Test public void protocolErrorBeforeCloseSendsFailure() throws IOException {
    server.getSink().write(ByteString.decodeHex("0a00")).emit(); // Invalid non-final ping frame.

    client.processNextFrame(); // Detects error, send close, close connection.
    taskFaker.runTasks();
    client.webSocket.finishReader();
    assertThat(client.closed).isTrue();
    client.listener.assertFailure(ProtocolException.class, "Control frames must be final.");

    server.processNextFrame();
    taskFaker.runTasks();
    server.listener.assertFailure();
  }

  @Test public void protocolErrorInCloseResponseClosesConnection() throws IOException {
    client.webSocket.close(1000, "Hello");
    taskFaker.runTasks();
    server.processNextFrame();
    // Not closed until close reply is received.
    assertThat(client.closed).isFalse();

    // Manually write an invalid masked close frame.
    server.getSink().write(ByteString.decodeHex("888760b420bb635c68de0cd84f")).emit();

    client.processNextFrame();// Detects error, disconnects immediately since close already sent.
    client.webSocket.finishReader();
    assertThat(client.closed).isTrue();
    client.listener.assertFailure(
        ProtocolException.class, "Server-sent frames must not be masked.");

    server.listener.assertClosing(1000, "Hello");
    server.listener.assertExhausted(); // Client should not have sent second close.
  }

  @Test public void protocolErrorAfterCloseDoesNotSendClose() throws IOException {
    client.webSocket.close(1000, "Hello!");
    taskFaker.runTasks();
    server.processNextFrame();

    // Not closed until close reply is received.
    assertThat(client.closed).isFalse();
    server.getSink().write(ByteString.decodeHex("0a00")).emit(); // Invalid non-final ping frame.

    client.processNextFrame(); // Detects error, disconnects immediately since close already sent.
    client.webSocket.finishReader();
    taskFaker.runTasks();
    assertThat(client.closed).isTrue();
    client.listener.assertFailure(ProtocolException.class, "Control frames must be final.");

    server.listener.assertClosing(1000, "Hello!");

    server.listener.assertExhausted(); // Client should not have sent second close.
  }

  @Test public void networkErrorReportedAsFailure() throws IOException {
    server.getSink().close();
    client.processNextFrame();
    taskFaker.runTasks();
    client.listener.assertFailure(EOFException.class);
  }

  @Test public void closeThrowingFailsConnection() throws IOException {
    client2Server.source().close();
    client.webSocket.close(1000, null);
    taskFaker.runTasks();
    client.listener.assertFailure(IOException.class, "source is closed");
  }

  @Test public void closeMessageAndConnectionCloseThrowingDoesNotMaskOriginal() throws IOException {
    // So when the client sends close it throws an IOException.
    server.getSource().close();

    client.webSocket.close(1000, "Bye!");
    taskFaker.runTasks();
    client.webSocket.finishReader();
    client.listener.assertFailure(IOException.class, "source is closed");
    assertThat(client.closed).isTrue();
  }

  @Test public void pingOnInterval() throws IOException {
    client.initWebSocket(random, 500);
    taskFaker.advanceUntil(ns(500L));

    server.processNextFrame(); // Ping.
    client.processNextFrame(); // Pong.

    taskFaker.advanceUntil(ns(1_000L));
    server.processNextFrame(); // Ping.
    client.processNextFrame(); // Pong.

    taskFaker.advanceUntil(ns(1_500L));
    server.processNextFrame(); // Ping.
    client.processNextFrame(); // Pong.
  }

  @Test public void unacknowledgedPingFailsConnection() throws IOException {
    client.initWebSocket(random, 500);

    // Don't process the ping and pong frames!
    taskFaker.advanceUntil(ns(500L));
    taskFaker.advanceUntil(ns(1_000L));
    client.listener.assertFailure(SocketTimeoutException.class,
        "sent ping but didn't receive pong within 500ms (after 0 successful ping/pongs)");
  }

  @Test public void unexpectedPongsDoNotInterfereWithFailureDetection() throws IOException {
    client.initWebSocket(random, 500);

    // At 0ms the server sends 3 unexpected pongs. The client accepts 'em and ignores em.
    server.webSocket.pong(ByteString.encodeUtf8("pong 1"));
    taskFaker.runTasks();
    client.processNextFrame();
    server.webSocket.pong(ByteString.encodeUtf8("pong 2"));
    client.processNextFrame();
    taskFaker.runTasks();
    server.webSocket.pong(ByteString.encodeUtf8("pong 3"));
    client.processNextFrame();

    // After 500ms the client automatically pings and the server pongs back.
    taskFaker.advanceUntil(ns(500L));
    server.processNextFrame(); // Ping.
    client.processNextFrame(); // Pong.

    // After 1000ms the client will attempt a ping 2, but we don't process it. That'll cause the
    // client to fail at 1500ms when it's time to send ping 3 because pong 2 hasn't been received.
    taskFaker.advanceUntil(ns(1_000L));
    taskFaker.advanceUntil(ns(1_500L));
    client.listener.assertFailure(SocketTimeoutException.class,
        "sent ping but didn't receive pong within 500ms (after 1 successful ping/pongs)");
  }

  @Test public void messagesNotCompressedWhenNotConfigured() throws IOException {
    String message = TestUtil.repeat('a', (int) DEFAULT_MINIMUM_DEFLATE_SIZE);
    server.webSocket.send(message);
    taskFaker.runTasks();

    assertThat(client.clientSourceBufferSize()).isGreaterThan(message.length()); // Not compressed.
    assertThat(client.processNextFrame()).isTrue();
    client.listener.assertTextMessage(message);
  }

  @Test public void messagesCompressedWhenConfigured() throws IOException {
    Headers headers = Headers.of("Sec-WebSocket-Extensions", "permessage-deflate");
    client.initWebSocket(random, 0, headers);
    server.initWebSocket(random, 0, headers);

    String message = TestUtil.repeat('a', (int) DEFAULT_MINIMUM_DEFLATE_SIZE);
    server.webSocket.send(message);

    taskFaker.runTasks();
    assertThat(client.clientSourceBufferSize()).isLessThan(message.length()); // Compressed!
    assertThat(client.processNextFrame()).isTrue();
    client.listener.assertTextMessage(message);
  }

  @Test public void smallMessagesNotCompressed() throws IOException {
    Headers headers = Headers.of("Sec-WebSocket-Extensions", "permessage-deflate");
    client.initWebSocket(random, 0, headers);
    server.initWebSocket(random, 0, headers);

    String message = TestUtil.repeat('a', (int) DEFAULT_MINIMUM_DEFLATE_SIZE - 1);
    server.webSocket.send(message);
    taskFaker.runTasks();

    assertThat(client.clientSourceBufferSize()).isGreaterThan(message.length()); // Not compressed.
    assertThat(client.processNextFrame()).isTrue();
    client.listener.assertTextMessage(message);
  }

  private static long ns(long millis) {
    return millis * 1_000_000L;
  }

  /** One peer's streams, listener, and web socket in the test. */
  private static class TestStreams extends RealWebSocket.Streams {
    private final String name;
    private final WebSocketRecorder listener;
    private final TaskFaker taskFaker;
    final Pipe sourcePipe;
    final Pipe sinkPipe;
    private RealWebSocket webSocket;
    boolean closed;

    public TestStreams(boolean client, TaskFaker taskFaker, Pipe source, Pipe sink) {
      super(client, Okio.buffer(source.source()), Okio.buffer(sink.sink()));
      this.name = client ? "client" : "server";
      this.listener = new WebSocketRecorder(name);
      this.taskFaker = taskFaker;
      this.sourcePipe = source;
      this.sinkPipe = sink;
    }

    public void initWebSocket(Random random, int pingIntervalMillis) throws IOException {
      initWebSocket(random, pingIntervalMillis, Headers.of());
    }

    public void initWebSocket(
        Random random, int pingIntervalMillis, Headers responseHeaders) throws IOException {
      String url = "http://example.com/websocket";
      Response response = new Response.Builder()
          .code(101)
          .message("OK")
          .request(new Request.Builder().url(url).build())
          .headers(responseHeaders)
          .protocol(Protocol.HTTP_1_1)
          .build();
      webSocket = new RealWebSocket(taskFaker.getTaskRunner(), response.request(), listener, random,
          pingIntervalMillis, WebSocketExtensions.Companion.parse(responseHeaders),
          DEFAULT_MINIMUM_DEFLATE_SIZE);
      webSocket.initReaderAndWriter(name, this);
    }

    /**
     * Peeks the number of bytes available for the client to read immediately. This doesn't block so
     * it requires that bytes have already been flushed by the server.
     */
    public long clientSourceBufferSize() throws IOException {
      getSource().request(1L);
      return getSource().getBuffer().size();
    }

    public boolean processNextFrame() throws IOException {
      return webSocket.processNextFrame();
    }

    @Override public void close() {
      if (closed) {
        throw new AssertionError("Already closed");
      }
      try {
        getSource().close();
      } catch (IOException ignored) {
      }
      try {
        getSink().close();
      } catch (IOException ignored) {
      }
      closed = true;
    }

    @Override public void cancel() {
      sourcePipe.cancel();
      sinkPipe.cancel();
    }
  }
}
