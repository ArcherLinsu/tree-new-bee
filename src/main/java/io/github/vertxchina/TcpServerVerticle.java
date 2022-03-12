package io.github.vertxchina;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static io.github.vertxchina.EventbusAddress.PUBLISH_MESSAGE;
import static io.github.vertxchina.EventbusAddress.READ_STORED_MESSAGES;
import static io.github.vertxchina.Message.CLIENT_ID_KEY;
import static io.github.vertxchina.Message.MESSAGE_CONTENT_KEY;

public class TcpServerVerticle extends AbstractVerticle {
  Logger log = LoggerFactory.getLogger(TcpServerVerticle.class);
  public static final String PROTOCOL = "TCP";
  DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");
  public static final String DELIM = "\r\n";
  SocketWriteHolder<NetSocket> socketHolder = new SocketWriteHolder<>((socket, message) -> socket.write(message.toBuffer().appendString(DELIM)));

  @Override
  public void start(Promise<Void> startPromise) {
    Integer port = config().getInteger("TcpServer.port", 32167);

    vertx.createNetServer(new NetServerOptions().setTcpKeepAlive(true))
      .connectHandler(socket -> {
        var id = SocketWriteHolder.generateClientId();
        log.info(id + " Connected TcpServer!");
        socket.write(new Message(CLIENT_ID_KEY, id)+DELIM); //先将id发回
//        new Message(CLIENT_ID_KEY, id).writeTo(socket); //先将id发回
        socketHolder.addSocket(id, socket);

        //todo 将来有了账户之后，改成登陆之后，再将历史记录发回
        //FIXME 如果client登录后马上发消息，这里会返回此间client发送的消息
        vertx.setTimer(3000, t -> vertx.eventBus()
          .<List<Message>>request(READ_STORED_MESSAGES, null, ar -> {
            if (ar.succeeded()) {
              ar.result().body().forEach(m -> socket.write(m.toBuffer().appendString(DELIM)));
            }
          }));

        socket.handler(RecordParser.newDelimited(DELIM, buffer -> {
          log.debug("Received message raw content: " + buffer);
          try {
            String now = ZonedDateTime.now().format(dateFormatter);
            var message = new Message(buffer).initServerSide(id, now, PROTOCOL);
            socketHolder.receiveMessage(socket, message);
            if (message.hasMessage()) {
              socketHolder.sendToOtherUsers(message);
              vertx.eventBus().publish(PUBLISH_MESSAGE, message);
            }
          } catch (Exception e) {
            socket.write(new Message(MESSAGE_CONTENT_KEY, e.getMessage()) + DELIM);
          }
        }).maxRecordSize(1024 * 64));

        socket.closeHandler(v -> socketHolder.removeSocket(socket));
      })
      .listen(port)
      .onSuccess(s -> {
        log.info("TcpServer listen to port: " + port);
        startPromise.complete();
      })
      .onFailure(e -> {
        log.error("TcpServer start failed: " + e.getMessage(), e);
        startPromise.fail(e);
      });
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
