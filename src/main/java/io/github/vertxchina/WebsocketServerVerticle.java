package io.github.vertxchina;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.parsetools.RecordParser;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static io.github.vertxchina.EventbusAddress.PUBLISH_MESSAGE;
import static io.github.vertxchina.EventbusAddress.READ_STORED_MESSAGES;
import static io.github.vertxchina.Message.CLIENT_ID_KEY;
import static io.github.vertxchina.Message.MESSAGE_CONTENT_KEY;

/**
 * @author Leibniz on 2022/03/10 1:05 PM
 */
public class WebsocketServerVerticle extends AbstractVerticle {
  Logger log = LoggerFactory.getLogger(WebsocketServerVerticle.class);
  public static final String PROTOCOL = "WEBSOCKET";
  DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");
  SocketWriteHolder<ServerWebSocket> socketHolder = new SocketWriteHolder<>();

  @Override
  public void start() {
    Integer port = config().getInteger("WebsocketServer.port", 32168);

    vertx.createHttpServer(new HttpServerOptions())
      .webSocketHandler(webSocket -> {
        var id = SocketWriteHolder.generateClientId();
        log.info(id + " Connected Websocket Server!");
        new Message(CLIENT_ID_KEY, id).writeTo(webSocket); //先将id发回
        socketHolder.addSocket(id, webSocket);

        //todo 将来有了账户之后，改成登陆之后，再将历史记录发回
        vertx.setTimer(3000, t -> vertx.eventBus()
          .<List<Message>>request(READ_STORED_MESSAGES, null, ar -> {
            if (ar.succeeded()) {
              ar.result().body().forEach(m -> webSocket.write(m.toBuffer()));
            }
          }));

        webSocket.handler(RecordParser.newDelimited(Message.DELIM, h -> {
          log.info("Received message raw content: " + h);
          try {
            String now = ZonedDateTime.now().format(dateFormatter);
            var message = new Message(h).initServerSide(id, now, PROTOCOL);
            socketHolder.receiveMessage(webSocket, message);
            if (message.hasMessage()) {
              socketHolder.sendToOtherUsers(message);
              vertx.eventBus().publish(PUBLISH_MESSAGE, message);
            }
          } catch (Exception e) {
            new Message(MESSAGE_CONTENT_KEY, e.getMessage()).writeTo(webSocket);
          }
        }).maxRecordSize(1024 * 64));

        webSocket.closeHandler(v -> socketHolder.removeSocket(webSocket));
      })
      .listen(port)
      .onSuccess(s -> log.info("WebsocketServer listen to port: " + port))
      .onFailure(e -> log.error("WebsocketServer start failed: " + e.getMessage(), e));
    vertx.eventBus()
      .<Message>consumer(PUBLISH_MESSAGE)
      .handler(message -> {
        Message tnbMsg = message.body();
        if (!tnbMsg.protocol().equals(PROTOCOL)) {
          socketHolder.sendToOtherUsers(tnbMsg);
        }
      });
  }
}
