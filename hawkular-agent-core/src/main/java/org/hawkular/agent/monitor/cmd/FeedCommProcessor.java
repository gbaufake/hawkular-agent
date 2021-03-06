/*
 * Copyright 2015-2017 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.agent.monitor.cmd;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.hawkular.agent.monitor.cmd.CommandContext.ResponseSentListener;
import org.hawkular.agent.monitor.config.AgentCoreEngineConfiguration;
import org.hawkular.agent.monitor.log.AgentLoggers;
import org.hawkular.agent.monitor.log.MsgLogger;
import org.hawkular.agent.monitor.service.AgentCoreEngine;
import org.hawkular.agent.monitor.util.Util;
import org.hawkular.bus.common.BasicMessage;
import org.hawkular.bus.common.BasicMessageWithExtraData;
import org.hawkular.bus.common.BinaryData;
import org.hawkular.cmdgw.api.ApiDeserializer;
import org.hawkular.cmdgw.api.AuthMessage;
import org.hawkular.cmdgw.api.Authentication;
import org.hawkular.cmdgw.api.GenericErrorResponse;
import org.hawkular.cmdgw.api.GenericErrorResponseBuilder;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.ws.WebSocket;
import okhttp3.ws.WebSocketCall;
import okhttp3.ws.WebSocketListener;
import okio.Buffer;
import okio.BufferedSink;

public class FeedCommProcessor implements WebSocketListener {
    private static final MsgLogger log = AgentLoggers.getLogger(FeedCommProcessor.class);

    /**
     * @return a map of all the default commands the agent will always support
     */
    private static Map<String, Class<? extends Command<? extends BasicMessage, ? extends BasicMessage>>> //
            getDefaultCommands() {

        Map<String, Class<? extends Command<? extends BasicMessage, ? extends BasicMessage>>> cmds = new HashMap<>();
        cmds.put(AddDatasourceCommand.REQUEST_CLASS.getName(), AddDatasourceCommand.class);
        cmds.put(AddJdbcDriverCommand.REQUEST_CLASS.getName(), AddJdbcDriverCommand.class);
        cmds.put(DeployApplicationCommand.REQUEST_CLASS.getName(), DeployApplicationCommand.class);
        cmds.put(DisableApplicationCommand.REQUEST_CLASS.getName(), DisableApplicationCommand.class);
        cmds.put(EchoCommand.REQUEST_CLASS.getName(), EchoCommand.class);
        cmds.put(EnableApplicationCommand.REQUEST_CLASS.getName(), EnableApplicationCommand.class);
        cmds.put(ExecuteAgnosticOperationCommand.REQUEST_CLASS.getName(), ExecuteAgnosticOperationCommand.class);
        cmds.put(ExportJdrCommand.REQUEST_CLASS.getName(), ExportJdrCommand.class);
        cmds.put(GenericErrorResponseCommand.REQUEST_CLASS.getName(), GenericErrorResponseCommand.class);
        cmds.put(RemoveDatasourceCommand.REQUEST_CLASS.getName(), RemoveDatasourceCommand.class);
        cmds.put(RemoveJdbcDriverCommand.REQUEST_CLASS.getName(), RemoveJdbcDriverCommand.class);
        cmds.put(RestartApplicationCommand.REQUEST_CLASS.getName(), RestartApplicationCommand.class);
        cmds.put(StatisticsControlCommand.REQUEST_CLASS.getName(), StatisticsControlCommand.class);
        cmds.put(UndeployApplicationCommand.REQUEST_CLASS.getName(), UndeployApplicationCommand.class);
        cmds.put(UpdateDatasourceCommand.REQUEST_CLASS.getName(), UpdateDatasourceCommand.class);
        return cmds;
    }

    private final int disconnectCode = 1000;
    private final String disconnectReason = "Shutting down FeedCommProcessor";
    private final Map<String, Class<? extends Command<? extends BasicMessage, ? extends BasicMessage>>> allCommands;
    private final WebSocketClientBuilder webSocketClientBuilder;
    private final AgentCoreEngine agentCoreEngine;
    private final String feedcommUrl;
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService pingExecutor = Executors.newScheduledThreadPool(1);
    private final AtomicReference<ReconnectJobThread> reconnectJobThread = new AtomicReference<>();

    private WebSocketCall webSocketCall;
    private WebSocket webSocket;

    private ScheduledFuture<?> pingFuture;

    // if this is true, this object should never reconnect
    private boolean destroyed = false;

    public FeedCommProcessor(
            WebSocketClientBuilder webSocketClientBuilder,
            Map<String, Class<? extends Command<? extends BasicMessage, ? extends BasicMessage>>> moreCommands,
            String feedId,
            AgentCoreEngine agentCoreEngine) {

        if (feedId == null || feedId.isEmpty()) {
            throw new IllegalArgumentException("Must have a valid feed ID to communicate with the server");
        }

        this.webSocketClientBuilder = webSocketClientBuilder;
        this.agentCoreEngine = agentCoreEngine;

        // build the map of all supported commands
        this.allCommands = getDefaultCommands();
        if (moreCommands != null) {
            for (Map.Entry<String, Class<? extends Command<? extends BasicMessage, ? extends BasicMessage>>> c //
            : moreCommands.entrySet()) {
                this.allCommands.put(c.getKey(), c.getValue());
            }
        }

        // determine the websocket URL is to the server
        AgentCoreEngineConfiguration config = this.agentCoreEngine.getConfiguration();
        try {
            StringBuilder url;
            url = Util.getContextUrlString(config.getStorageAdapter().getUrl(),
                    config.getStorageAdapter().getFeedcommContext());
            url.append("feed/").append(feedId);
            this.feedcommUrl = url.toString().replaceFirst("https?:",
                    (config.getStorageAdapter().isUseSSL()) ? "wss:" : "ws:");
            log.infoFeedCommUrl(this.feedcommUrl);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot build URL to the server command-gateway endpoint", e);
        }
    }

    /**
     * @return true if this object is currently connected to the websocket.
     */
    public boolean isConnected() {
        return this.webSocket != null;
    }

    /**
     * Connects to the websocket endpoint. This first attempts to disconnect to any existing connection.
     * If this object has previously been {@link #destroy() destroyed}, then the connect request is ignored.
     *
     * @throws Exception on failure
     */
    public void connect() throws Exception {
        disconnect(); // disconnect from any old connection we had

        if (destroyed) {
            return;
        }

        log.debugf("About to connect a feed WebSocket client to endpoint [%s]", feedcommUrl);

        webSocketCall = webSocketClientBuilder.createWebSocketCall(feedcommUrl, null);
        webSocketCall.enqueue(this);
    }

    /**
     * Performs a graceful disconnect with the {@link #disconnectCode} and {@link #disconnectReason}.
     */
    public void disconnect() {
        disconnect(disconnectCode, disconnectReason);
    }

    private void disconnect(int code, String reason) {
        if (webSocket != null) {
            try {
                webSocket.close(code, reason);
                // CommandGateway performs some async cleanup code that must complete before we
                // can safely continue.  Otherwise we may try to establish a new connection (with the same name)
                // before the old one is cleaned up server-side. It's fast, wait 2s and then continue. This is
                // blunt but because there is no way for us to know when cmdgw has finished its close actions,
                // there is no obvious alternative.
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (Exception e) {
                log.warnFailedToCloseWebSocket(code, reason, e);
            }
            webSocket = null;
        }

        if (webSocketCall != null) {
            try {
                webSocketCall.cancel();
            } catch (Exception e) {
                log.errorCannotCloseWebSocketCall(e);
            }
            webSocketCall = null;
        }
    }

    /**
     * Call this when you know this processor object will never be used again.
     */
    public void destroy() {
        log.debugf("Destroying FeedCommProcessor");
        this.destroyed = true;
        stopReconnectJobThread();
        disconnect();
        destroyPingExecutor();
    }

    /**
     * Sends a message to the server asynchronously. This method returns immediately; the message may not go out until
     * some time in the future.
     *
     * @param messageWithData the message to send
     */
    public void sendAsync(BasicMessageWithExtraData<? extends BasicMessage> messageWithData) {
        if (!isConnected()) {
            throw new IllegalStateException("WebSocket connection was closed. Cannot send any messages");
        }

        BasicMessage message = messageWithData.getBasicMessage();
        configurationAuthentication(message);

        sendExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (messageWithData.getBinaryData() == null) {
                        String messageString = ApiDeserializer.toHawkularFormat(message);
                        @SuppressWarnings("resource")
                        Buffer buffer = new Buffer().writeUtf8(messageString);
                        RequestBody requestBody = RequestBody.create(WebSocket.TEXT, buffer.readByteArray());
                        FeedCommProcessor.this.webSocket.sendMessage(requestBody);
                    } else {
                        BinaryData messageData = ApiDeserializer.toHawkularFormat(message,
                                messageWithData.getBinaryData());

                        RequestBody requestBody = new RequestBody() {
                            @Override
                            public MediaType contentType() {
                                return WebSocket.BINARY;
                            }

                            @Override
                            public void writeTo(BufferedSink bufferedSink) throws IOException {
                                emitToSink(messageData, bufferedSink);
                            }
                        };

                        FeedCommProcessor.this.webSocket.sendMessage(requestBody);
                    }
                } catch (Throwable t) {
                    log.errorFailedToSendOverFeedComm(message.getClass().getName(), t);
                }
            }
        });
    }

    /**
     * Sends a message to the server synchronously. This will return only when the message has been sent.
     *
     * @param messageWithData the message to send
     * @throws IOException if the message failed to be sent
     */
    public void sendSync(BasicMessageWithExtraData<? extends BasicMessage> messageWithData) throws Exception {
        if (!isConnected()) {
            throw new IllegalStateException("WebSocket connection was closed. Cannot send any messages");
        }

        BasicMessage message = messageWithData.getBasicMessage();
        configurationAuthentication(message);

        if (messageWithData.getBinaryData() == null) {
            String messageString = ApiDeserializer.toHawkularFormat(message);
            @SuppressWarnings("resource")
            Buffer buffer = new Buffer().writeUtf8(messageString);
            RequestBody requestBody = RequestBody.create(WebSocket.TEXT, buffer.readByteArray());
            FeedCommProcessor.this.webSocket.sendMessage(requestBody);
        } else {
            BinaryData messageData = ApiDeserializer.toHawkularFormat(message, messageWithData.getBinaryData());

            RequestBody requestBody = new RequestBody() {
                @Override
                public MediaType contentType() {
                    return WebSocket.BINARY;
                }

                @Override
                public void writeTo(BufferedSink bufferedSink) throws IOException {
                    emitToSink(messageData, bufferedSink);
                }
            };

            FeedCommProcessor.this.webSocket.sendMessage(requestBody);

        }
    }

    private void emitToSink(BinaryData in, BufferedSink out) throws RuntimeException {
        int bufferSize = 32768;
        try {
            InputStream input = new BufferedInputStream(in, bufferSize);
            byte[] buffer = new byte[bufferSize];
            for (int bytesRead = input.read(buffer); bytesRead != -1; bytesRead = input.read(buffer)) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (IOException ioe) {
            throw new RuntimeException("Failed to emit to sink", ioe);
        }
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        if (response != null && response.body() != null) {
            try {
                response.body().close();
            } catch (Exception ignore) {
            }
        }

        stopReconnectJobThread();
        this.webSocket = webSocket;
        startPinging();
        log.infoOpenedFeedComm(feedcommUrl);
    }

    @Override
    public void onClose(int reasonCode, String reason) {
        stopPinging();
        webSocket = null;
        log.infoClosedFeedComm(feedcommUrl, reasonCode, reason);

        // We always want a connection - so try to get another one.
        // Note that we don't try to get another connection if we think we'll never get one;
        // This avoids a potential infinite loop of continually trying (and failing) to get a connection.
        // We also don't try to get another one if we were explicitly told to disconnect.
        if (!(disconnectCode == reasonCode && disconnectReason.equals(reason))) {
            switch (reasonCode) {
                case 1008: { // VIOLATED POLICY - don't try again since it probably will fail again (bad credentials?)
                    break;
                }
                default: {
                    startReconnectJobThread();
                    break;
                }
            }
        }
    }

    @Override
    public void onFailure(IOException e, Response response) {
        if (response == null) {
            if (e instanceof java.net.ConnectException) {
                // don't flood the log with these at the WARN level - its probably just because the server is down
                // and we can't reconnect - while the server is down, our reconnect logic will cause this error
                // to occur periodically. Our reconnect logic will log other messages.
                log.tracef("Feed communications had a failure - a reconnection is likely required: %s", e);
            } else if (e.getMessage() != null && e.getMessage().toLowerCase().contains("socket closed")) {
                log.debugf("Feed communications channel has been shutdown: " + e);
            } else {
                log.warnFeedCommFailure("<null>", e);
            }
        } else {
            log.warnFeedCommFailure(response.toString(), e);
            if (response.body() != null) {
                try {
                    response.body().close();
                } catch (Exception ignore) {
                }
            }
        }

        // refresh our connection in case the network dropped our connection and onClose wasn't called
        if (reconnectJobThread.get() == null) {
            stopPinging();
            disconnect();
            startReconnectJobThread();
        }
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void onMessage(ResponseBody responseBody) throws IOException {

        BasicMessageWithExtraData<? extends BasicMessage> response;
        CommandContext context = null;
        String requestClassName = "?";

        try {
            try {
                BasicMessageWithExtraData<? extends BasicMessage> msgWithData;

                if (responseBody.contentType().equals(WebSocket.TEXT)) {
                    String nameAndJsonStr = responseBody.string();
                    msgWithData = new ApiDeserializer().deserialize(nameAndJsonStr);
                } else if (responseBody.contentType().equals(WebSocket.BINARY)) {
                    InputStream input = responseBody.byteStream();
                    msgWithData = new ApiDeserializer().deserialize(input);
                } else {
                    throw new IllegalArgumentException(
                            "Unknown mediatype type, please report this bug: " + responseBody.contentType());
                }

                log.debug("Received message from server");

                BasicMessage msg = msgWithData.getBasicMessage();
                requestClassName = msg.getClass().getName();

                Class<? extends Command<?, ?>> commandClass = this.allCommands.get(requestClassName);
                if (commandClass == null) {
                    log.errorInvalidCommandRequestFeed(requestClassName);
                    String errorMessage = "Invalid command request: " + requestClassName;
                    GenericErrorResponse errorMsg = new GenericErrorResponseBuilder().setErrorMessage(errorMessage)
                            .build();
                    response = new BasicMessageWithExtraData<BasicMessage>(errorMsg, null);
                } else {
                    Command command = commandClass.newInstance();
                    context = new CommandContext(this, this.agentCoreEngine);
                    response = command.execute(msgWithData, context);
                }
            } finally {
                // must ensure response is closed; this assumes if it was a stream that the command is finished with it
                responseBody.close();
            }
        } catch (Throwable t) {
            log.errorCommandExecutionFailureFeed(requestClassName, t);
            String errorMessage = "Command failed [" + requestClassName + "]";
            GenericErrorResponse errorMsg = new GenericErrorResponseBuilder().setThrowable(t)
                    .setErrorMessage(errorMessage).build();
            response = new BasicMessageWithExtraData<BasicMessage>(errorMsg, null);
        }

        // send the response back to the server and notify the listeners after the send is done
        if (response != null) {
            Exception err = null;
            try {
                sendSync(response);
            } catch (Exception e) {
                err = e;
                log.errorFailedToSendOverFeedComm(response.getClass().getName(), e);
            }
            if (context != null) {
                for (ResponseSentListener listener : context.getResponseSentListeners()) {
                    listener.onSend(response, err);
                }
            }
        }
    }

    @Override
    public void onPong(Buffer buffer) {
        try {
            if (!buffer.equals(createPingBuffer())) {
                log.debugf("Failed to verify WebSocket pong [%s]", buffer.toString());
            }
        } finally {
            buffer.close();
        }
    }

    private void configurationAuthentication(BasicMessage message) {
        if (!(message instanceof AuthMessage)) {
            return; // this message doesn't need authentication
        }

        AuthMessage authMessage = (AuthMessage) message;

        Authentication auth = authMessage.getAuthentication();
        if (auth != null) {
            return; // authentication already configured; assume whoever did it knew what they were doing and keep it
        }

        auth = new Authentication();
        auth.setUsername(this.agentCoreEngine.getConfiguration().getStorageAdapter().getUsername());
        auth.setPassword(this.agentCoreEngine.getConfiguration().getStorageAdapter().getPassword());
        authMessage.setAuthentication(auth);
    }

    private void startReconnectJobThread() {
        ReconnectJobThread newReconnectJob = (!destroyed) ? new ReconnectJobThread() : null;
        ReconnectJobThread oldReconnectJob = reconnectJobThread.getAndSet(newReconnectJob);

        if (oldReconnectJob != null) {
            oldReconnectJob.interrupt();
        }

        if (newReconnectJob != null) {
            log.debugf("Starting WebSocket reconnect thread");
            newReconnectJob.start();
        }
    }

    private void stopReconnectJobThread() {
        ReconnectJobThread reconnectJob = reconnectJobThread.getAndSet(null);
        if (reconnectJob != null) {
            log.debugf("Stopping WebSocket reconnect thread");
            reconnectJob.interrupt();
        }
    }

    private class ReconnectJobThread extends Thread {
        public ReconnectJobThread() {
            super("Hawkular WildFly Monitor Websocket Reconnect Thread");
            setDaemon(true);
        }

        @Override
        public void run() {
            int attemptCount = 0;
            final long sleepInterval = 1000L;
            boolean keepTrying = true;
            while (keepTrying && !destroyed) {
                try {
                    attemptCount++;
                    Thread.sleep(sleepInterval);
                    if (!isConnected()) {
                        // only log a message for each minute that passes in which we couldn't reconnect
                        if (attemptCount % 60 == 0) {
                            log.errorCannotReconnectToWebSocket(new Exception("Attempt #" + attemptCount));
                        }
                        connect();
                    } else {
                        keepTrying = false;
                    }
                } catch (InterruptedException ie) {
                    keepTrying = false;
                } catch (Exception e) {
                    if (attemptCount % 60 == 0) {
                        log.errorCannotReconnectToWebSocket(e);
                    }
                }
            }
        }
    }

    private void startPinging() {
        synchronized (pingExecutor) {
            stopPinging(); // cleans up anything left over from previous pinging

            log.debugf("Starting WebSocket ping");
            pingFuture = pingExecutor.scheduleAtFixedRate(
                    new Runnable() {
                        @Override
                        public void run() {
                            if (isConnected()) {
                                try {
                                    webSocket.sendPing(createPingBuffer());
                                } catch (IOException ioe) {
                                    log.debugf("Failed to send ping. Cause=%s", ioe.toString());
                                    disconnect(4000, "Ping failed"); // sendPing javadoc says to close on IOException
                                } catch (IllegalStateException ise) {
                                    log.debugf("Cannot ping. WebSocket is already closed. Cause=%s", ise.toString());
                                } catch (Exception e) {
                                    // Catch other problems so exception is not thrown which would stop the thread.
                                    // This will cover a race condition where webSocket may be null.
                                    log.debugf("Cannot ping. Cause=%s", e.toString());
                                }
                            }
                        }
                    }, 5, 5, TimeUnit.SECONDS);
        }
    }

    private void stopPinging() {
        synchronized (pingExecutor) {
            if (pingFuture != null) {
                log.debugf("Stopping WebSocket ping");
                pingFuture.cancel(true);
                pingFuture = null;
            }
        }
    }

    /**
     * Call this when you know the feed comm processor object will never be used again
     * and thus the ping executor will also never be used again.
     */
    private void destroyPingExecutor() {
        synchronized (pingExecutor) {
            if (!pingExecutor.isShutdown()) {
                try {
                    log.debugf("Shutting down WebSocket ping executor");
                    pingExecutor.shutdown();
                    if (!pingExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                        pingExecutor.shutdownNow();
                    }
                } catch (Throwable t) {
                    log.warnf("Cannot shut down WebSocket ping executor. Cause=%s", t.toString());
                }
            }
        }
    }

    @SuppressWarnings("resource")
    private Buffer createPingBuffer() {
        return new Buffer().writeUtf8("hawkular-ping");
    }

}
