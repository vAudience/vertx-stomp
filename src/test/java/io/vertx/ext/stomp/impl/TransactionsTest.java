package io.vertx.ext.stomp.impl;

import com.jayway.awaitility.Awaitility;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.stomp.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test the STOMP transactions.
 *
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class TransactionsTest {

  private Vertx vertx;
  private StompServer server;

  private List<StompClient> clients = new ArrayList<>();

  @Before
  public void setUp() {
    vertx = Vertx.vertx();
    AsyncLock<StompServer> lock = new AsyncLock<>();
    vertx = Vertx.vertx();
    server = StompServer.create(vertx)
        .handler(StompServerHandler.create(vertx))
        .listen(lock.handler());
    lock.waitForSuccess();
  }

  @After
  public void tearDown() {
    clients.forEach(StompClient::close);
    clients.clear();
    AsyncLock<Void> lock = new AsyncLock<>();
    server.close(lock.handler());
    lock.waitForSuccess();

    lock = new AsyncLock<>();
    vertx.close(lock.handler());
    lock.waitForSuccess();
  }


  @Test
  public void testBasicTransaction() {
    List<Frame> frames = new CopyOnWriteArrayList<>();
    List<Frame> errors = new CopyOnWriteArrayList<>();
    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.subscribe("/queue", (frames::add));
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> Helper.hasDestination(server.stompHandler().getDestinations(), "/queue"));

    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.errorHandler(errors::add);
      connection.beginTX("my-tx");
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("Hello")));
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx").setBody(
          Buffer.buffer("World")));
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("!!!")));
      connection.commit("my-tx");
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> frames.size() == 3 && errors.isEmpty());
    for (Frame frame : frames) {
      assertThat(frame.getHeader(Frame.TRANSACTION)).isEqualTo("my-tx");
    }
  }

  @Test
  public void testAbortedTransaction() throws InterruptedException {
    List<Frame> frames = new CopyOnWriteArrayList<>();
    List<Frame> errors = new CopyOnWriteArrayList<>();

    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.subscribe("/queue", (frames::add));
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> Helper.hasDestination(server.stompHandler().getDestinations(), "/queue"));

    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.errorHandler(errors::add);
      connection.beginTX("my-tx");
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("Hello")));
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx").setBody(
          Buffer.buffer("World")));
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("!!!")));
      connection.abort("my-tx");
    }));

    // Wait a few seconds to be sure messages are not sent
    Thread.sleep(2000);
    assertThat(errors).isEmpty();
    assertThat(frames).isEmpty();
  }


  @Test
  public void testTransactionDeliveringToTwoClients() {
    List<Frame> frames1 = new CopyOnWriteArrayList<>();
    List<Frame> frames2 = new CopyOnWriteArrayList<>();
    List<Frame> errors = new CopyOnWriteArrayList<>();
    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.subscribe("/queue", (frames1::add));
    }));

    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.subscribe("/queue", (frames2::add));
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> Helper.hasDestination(server.stompHandler().getDestinations(), "/queue"));

    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.errorHandler(errors::add);
      connection.beginTX("my-tx");
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("Hello")));
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx").setBody(
          Buffer.buffer("World")));
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("!!!")));
      connection.commit("my-tx");
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() ->
        frames1.size() == 3 && frames2.size() == 3 && errors.isEmpty());
    for (Frame frame : frames1) {
      assertThat(frame.getHeader(Frame.TRANSACTION)).isEqualTo("my-tx");
    }
    for (Frame frame : frames2) {
      assertThat(frame.getHeader(Frame.TRANSACTION)).isEqualTo("my-tx");
    }
  }

  @Test
  public void testThatYouCannotBeginTwoTransactionsWithTheSameId() {
    List<Frame> frames = new CopyOnWriteArrayList<>();
    List<Frame> errors = new CopyOnWriteArrayList<>();
    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.subscribe("/queue", (frames::add));
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> Helper.hasDestination(server.stompHandler().getDestinations(), "/queue"));

    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.errorHandler(errors::add);
      connection.beginTX("my-tx");
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("Hello")));
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx").setBody(
          Buffer.buffer("World")));
      connection.beginTX("my-tx"); // Illegal call
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> !errors.isEmpty());
    assertThat(frames.isEmpty());
    assertThat(errors.get(0).getHeader("message")).containsIgnoringCase("Already existing transaction");
  }

  @Test
  public void testThatTransactionIDCanBeReusedAfterCommit() {
    List<Frame> frames = new CopyOnWriteArrayList<>();
    List<Frame> errors = new CopyOnWriteArrayList<>();
    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.subscribe("/queue", (frames::add));
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> Helper.hasDestination(server.stompHandler().getDestinations(), "/queue"));

    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.errorHandler(errors::add);
      connection.beginTX("my-tx");
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("Hello")));
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx").setBody(
          Buffer.buffer("World")));
      connection.commit("my-tx");
      connection.beginTX("my-tx");
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("!!!")));
      connection.commit("my-tx");
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> frames.size() == 3 && errors.isEmpty());
    for (Frame frame : frames) {
      assertThat(frame.getHeader(Frame.TRANSACTION)).isEqualTo("my-tx");
    }
  }

  @Test
  public void testAutoAbortOnClose() {
    List<Frame> frames = new CopyOnWriteArrayList<>();
    List<Frame> errors = new CopyOnWriteArrayList<>();

    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.subscribe("/queue", (frames::add));
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> Helper.hasDestination(server.stompHandler().getDestinations(), "/queue"));

    AtomicBoolean done = new AtomicBoolean();
    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.errorHandler(errors::add);
      connection.beginTX("my-tx");
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("Hello")));
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx").setBody(
          Buffer.buffer("World")));
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("!!!")));

      connection.close();
      done.set(true);
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> Transactions.instance().getTransactionCount() == 0
        && done.get());
    assertThat(frames).isEmpty();
    assertThat(errors).isEmpty();
  }

  @Test
  public void testAutoAbortOnDisconnect() {
    List<Frame> frames = new CopyOnWriteArrayList<>();
    List<Frame> errors = new CopyOnWriteArrayList<>();

    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.subscribe("/queue", (frames::add));
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> Helper.hasDestination(server.stompHandler().getDestinations(), "/queue"));

    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.errorHandler(errors::add);
      connection.beginTX("my-tx", f -> {
        connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
            .setBody(Buffer.buffer("Hello")));
        connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx").setBody(
            Buffer.buffer("World")));
        connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
            .setBody(Buffer.buffer("!!!")));
        connection.disconnect();
      });
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> Transactions.instance().getTransactionCount() == 0);
    assertThat(frames).isEmpty();
    assertThat(errors).isEmpty();
  }

  @Test
  public void testCommitWithIllegalId() {
    List<Frame> frames = new CopyOnWriteArrayList<>();
    List<Frame> errors = new CopyOnWriteArrayList<>();
    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.subscribe("/queue", (frames::add));
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> Helper.hasDestination(server.stompHandler().getDestinations(), "/queue"));

    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.errorHandler(errors::add);
      connection.beginTX("my-tx");
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("Hello")));
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx").setBody(
          Buffer.buffer("World")));
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("!!!")));
      connection.commit("illegal");
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> errors.size() >= 1);
    assertThat(frames).isEmpty();
    // On error, all transactions are closed
    assertThat(Transactions.instance().getTransactionCount()).isEqualTo(0);
    assertThat(errors.get(0).toString()).containsIgnoringCase("Unknown transaction");
  }

  @Test
  public void testAbortWithBadTransactionId() {
    List<Frame> frames = new CopyOnWriteArrayList<>();
    List<Frame> errors = new CopyOnWriteArrayList<>();
    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.subscribe("/queue", (frames::add));
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> Helper.hasDestination(server.stompHandler().getDestinations(), "/queue"));

    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.errorHandler(errors::add);
      connection.beginTX("my-tx");
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("Hello")));
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx").setBody(
          Buffer.buffer("World")));
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("!!!")));
      connection.abort("illegal");
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> errors.size() >= 1);
    assertThat(frames).isEmpty();
    // On error, all transactions are closed
    assertThat(Transactions.instance().getTransactionCount()).isEqualTo(0);
    assertThat(errors.get(0).toString()).containsIgnoringCase("Unknown transaction");
  }

  @Test
  public void testNumberOfFramesInTransaction() {
    server.options().setMaxFrameInTransaction(2);

    List<Frame> frames = new CopyOnWriteArrayList<>();
    List<Frame> errors = new CopyOnWriteArrayList<>();
    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.subscribe("/queue", (frames::add));
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> Helper.hasDestination(server.stompHandler().getDestinations(), "/queue"));

    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.errorHandler(errors::add);
      connection.beginTX("my-tx");
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("Hello")));
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx").setBody(
          Buffer.buffer("World")));
      // Next will be dropped:
      connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
          .setBody(Buffer.buffer("!!!")));
      connection.commit("my-tx");
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> Transactions.instance().getTransactionCount() == 0);
    assertThat(frames).isEmpty();
    assertThat(errors).hasSize(1);
  }

  @Test
  public void testTransactionChunk() {
    server.options().setTransactionChunkSize(100);
    server.options().setMaxFrameInTransaction(10000);

    List<Frame> frames = new CopyOnWriteArrayList<>();
    List<Frame> errors = new CopyOnWriteArrayList<>();
    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.subscribe("/queue", (frames::add));
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> Helper.hasDestination(server.stompHandler().getDestinations(), "/queue"));

    clients.add(StompClient.create(vertx).connect(ar -> {
      final StompClientConnection connection = ar.result();
      connection.errorHandler(errors::add);
      connection.beginTX("my-tx");
      for (int i = 0; i < 5000; i++) {
        connection.send(new Frame().setCommand(Frame.Command.SEND).setDestination("/queue").setTransaction("my-tx")
            .setBody(Buffer.buffer("Hello-" + i)));
      }
      connection.commit("my-tx");
    }));

    Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> frames.size() == 5000 && errors.isEmpty());
    int i = 0;
    for (Frame frame : frames) {
      assertThat(frame.getHeader(Frame.TRANSACTION)).isEqualTo("my-tx");
      assertThat(frame.getBodyAsString()).isEqualTo("Hello-" + i);
      i++;
    }
  }
}
