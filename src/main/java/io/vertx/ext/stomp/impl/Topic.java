package io.vertx.ext.stomp.impl;

import io.vertx.core.Vertx;
import io.vertx.ext.stomp.Destination;
import io.vertx.ext.stomp.Frame;
import io.vertx.ext.stomp.StompServerConnection;
import io.vertx.ext.stomp.utils.Headers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of {@link io.vertx.ext.stomp.Destination} dispatching messages to all subscribers.
 *
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class Topic implements Destination {

  private final String destination;

  private final List<Subscription> subscriptions = new ArrayList<>();
  private final Vertx vertx;

  public Topic(Vertx vertx, String destination) {
    this.destination = destination;
    this.vertx = vertx;
  }

  /**
   * @return the destination address.
   */
  @Override
  public String destination() {
    return destination;
  }

  /**
   * Dispatches the given frame.
   *
   * @param connection the connection
   * @param frame      the frame ({@code SEND} frame).
   * @return the current instance of {@link Destination}
   */
  @Override
  public synchronized Destination dispatch(StompServerConnection connection, Frame frame) {
    for (Subscription subscription : subscriptions) {
      String messageId = UUID.randomUUID().toString();
      Frame message = transform(frame, subscription, messageId);
      subscription.connection.write(message.toBuffer());
    }
    return this;
  }

  public static Frame transform(Frame frame, Subscription subscription, String messageId) {
    final Headers headers = Headers.create(frame.getHeaders())
        // Destination already set in the input headers.
        .add(Frame.SUBSCRIPTION, subscription.id)
        .add(Frame.MESSAGE_ID, messageId);
    if (!subscription.ackMode.equals("auto")) {
      // We reuse the message Id as ack Id
      headers.add(Frame.ACK, messageId);
    }
    return new Frame(Frame.Command.MESSAGE,
        headers,
        frame.getBody());
  }

  /**
   * Handles a subscription request to the current {@link Destination}. All check about the frame format and unicity
   * of the id should have been done beforehand.
   *
   * @param connection the connection
   * @param frame      the {@code SUBSCRIBE} frame
   * @return the current instance of {@link Destination}
   */
  @Override
  public synchronized Destination subscribe(StompServerConnection connection, Frame frame) {
    Subscription subscription = new Subscription(connection, frame);
    subscriptions.add(subscription);
    return this;
  }

  /**
   * Handles a un-subscription request to the current {@link Destination}.
   *
   * @param connection the connection
   * @param frame      the {@code UNSUBSCRIBE} frame
   * @return {@code true} if the un-subscription has been handled, {@code false} otherwise.
   */
  @Override
  public synchronized boolean unsubscribe(StompServerConnection connection, Frame frame) {
    boolean r = false;
    for (Subscription subscription : subscriptions) {
      if (subscription.connection.equals(connection) && subscription.id.equals(frame.getId())) {
        r = subscriptions.remove(subscription);
        // Subscription id are unique for a connection.
        break;
      }
    }
    if (subscriptions.isEmpty()) {
      vertx.sharedData().getLocalMap("stomp.destinations").remove(destination);
    }
    return r;
  }

  /**
   * Removes all subscriptions of the given connection
   *
   * @param connection the connection
   * @return the current instance of {@link Destination}
   */
  @Override
  public synchronized Destination unsubscribeConnection(StompServerConnection connection) {
    new ArrayList<>(subscriptions)
        .stream()
        .filter(subscription -> subscription.connection.equals(connection))
        .forEach(subscriptions::remove);

    if (subscriptions.isEmpty()) {
      vertx.sharedData().getLocalMap("stomp.destinations").remove(destination);
    }
    return this;
  }

  /**
   * Handles a {@code ACK} frame.
   *
   * @param connection the connection
   * @param frame      the {@code ACK} frame
   * @return {@code true} if the destination has handled the frame (meaning it has sent the message with id)
   */
  @Override
  public synchronized boolean ack(StompServerConnection connection, Frame frame) {
    return false;
  }

  /**
   * Handles a {@code NACK} frame.
   *
   * @param connection the connection
   * @param frame      the {@code NACK} frame
   * @return {@code true} if the destination has handled the frame (meaning it has sent the message with id)
   */
  @Override
  public synchronized boolean nack(StompServerConnection connection, Frame frame) {
    return false;
  }

  /**
   * Gets all subscription ids for the given destination hold by the given client
   *
   * @param connection the connection (client)
   * @return the list of subscription id, empty if none
   */
  @Override
  public synchronized List<String> getSubscriptions(StompServerConnection connection) {
    return subscriptions.stream()
        .filter(subscription -> subscription.connection.equals(connection))
        .map(s -> s.id)
        .collect(Collectors.toList());
  }

  /**
   * Gets the number of subscriptions attached to the current {@link Destination}.
   *
   * @return the number of subscriptions.
   */
  @Override
  public synchronized int numberOfSubscriptions() {
    return subscriptions.size();
  }

  private class Subscription {
    private final StompServerConnection connection;
    private final String id;
    private final String ackMode;

    private Subscription(StompServerConnection connection, Frame frame) {
      this.connection = connection;
      this.ackMode = frame.getAck();
      this.id = frame.getId();
    }
  }

}
