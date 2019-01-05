package com.github.gotify.service;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import com.github.gotify.SSLSettings;
import com.github.gotify.Utils;
import com.github.gotify.api.Callback;
import com.github.gotify.api.CertUtils;
import com.github.gotify.client.model.Message;
import com.github.gotify.log.Log;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class WebSocketConnection {
    private final ConnectivityManager connectivityManager;
    private OkHttpClient client;

    public static final int RECONNECT_SCHEDULE_REASON_NETWORK = 0;
    public static final int RECONNECT_SCHEDULE_REASON_CONN_ERR = 1;
    private final Handler reconnectHandler = new Handler();
    private Runnable reconnectCallback = this::start;
    private int errorCount = 0;

    private final String baseUrl;
    private final String token;
    private WebSocket webSocket;
    private Callback.SuccessCallback<Message> onMessage;
    private Runnable onClose;
    private Runnable onOpen;
    private BadRequestRunnable onBadRequest;
    private OnReconnectScheduleRunnable onReconnectSchedule;
    private Runnable onReconnected;
    private boolean isClosed;
    private Runnable onDisconnect;

    WebSocketConnection(
            String baseUrl,
            SSLSettings settings,
            String token,
            ConnectivityManager connectivityManager) {
        this.connectivityManager = connectivityManager;
        OkHttpClient.Builder builder =
                new OkHttpClient.Builder()
                        .readTimeout(0, TimeUnit.MILLISECONDS)
                        .pingInterval(1, TimeUnit.MINUTES)
                        .connectTimeout(10, TimeUnit.SECONDS);
        CertUtils.applySslSettings(builder, settings);

        client = builder.build();

        this.baseUrl = baseUrl;
        this.token = token;
    }

    synchronized WebSocketConnection onMessage(Callback.SuccessCallback<Message> onMessage) {
        this.onMessage = onMessage;
        return this;
    }

    synchronized WebSocketConnection onClose(Runnable onClose) {
        this.onClose = onClose;
        return this;
    }

    synchronized WebSocketConnection onOpen(Runnable onOpen) {
        reconnectHandler.removeCallbacks(reconnectCallback);
        this.onOpen = onOpen;
        return this;
    }

    synchronized WebSocketConnection onBadRequest(BadRequestRunnable onBadRequest) {
        this.onBadRequest = onBadRequest;
        return this;
    }

    synchronized WebSocketConnection onDisconnect(Runnable onDisconnect) {
        this.onDisconnect = onDisconnect;
        return this;
    }

    synchronized WebSocketConnection onReconnectSchedule(
            OnReconnectScheduleRunnable onReconnectSchedule) {
        this.onReconnectSchedule = onReconnectSchedule;
        return this;
    }

    synchronized WebSocketConnection onReconnected(Runnable onReconnected) {
        this.onReconnected = onReconnected;
        return this;
    }

    private Request request() {
        HttpUrl url =
                HttpUrl.parse(baseUrl)
                        .newBuilder()
                        .addPathSegment("stream")
                        .addQueryParameter("token", token)
                        .build();
        return new Request.Builder().url(url).get().build();
    }

    public synchronized WebSocketConnection start() {
        close();
        isClosed = false;
        Log.i("WebSocket: starting...");

        webSocket = client.newWebSocket(request(), new Listener());
        return this;
    }

    public synchronized void close() {
        if (webSocket != null) {
            Log.i("WebSocket: closing existing connection.");
            isClosed = true;
            webSocket.close(1000, "");
            webSocket = null;
        }
    }

    public synchronized void scheduleReconnect(long delay, int reason) {
        reconnectHandler.removeCallbacks(reconnectCallback);

        Log.i(
                "WebSocket: scheduling a restart in "
                        + delay / TimeUnit.SECONDS.toMillis(1)
                        + " second(s)");
        reconnectHandler.postDelayed(reconnectCallback, delay);

        onReconnectSchedule.execute(delay, reason);
    }

    private class Listener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.i("WebSocket: opened");
            synchronized (this) {
                onOpen.run();

                if (errorCount > 0) {
                    onReconnected.run();
                    errorCount = 0;
                }
            }
            super.onOpen(webSocket, response);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            Log.i("WebSocket: received message " + text);
            synchronized (this) {
                Message message = Utils.JSON.fromJson(text, Message.class);
                onMessage.onSuccess(message);
            }
            super.onMessage(webSocket, text);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            synchronized (this) {
                if (!isClosed) {
                    Log.w("WebSocket: closed");
                    onClose.run();
                }
            }

            super.onClosed(webSocket, code, reason);
        }

        synchronized void scheduleReconnect(long delay, int reason) {
            WebSocketConnection.this.scheduleReconnect(delay, reason);
            onReconnectSchedule.execute(delay, reason);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            String code = response != null ? "StatusCode: " + response.code() : "";
            String message = response != null ? response.message() : "";
            Log.e("WebSocket: failure " + code + " Message: " + message, t);
            synchronized (this) {
                if (response != null && response.code() >= 400 && response.code() <= 499) {
                    onBadRequest.execute(message);
                    close();
                    return;
                }

                errorCount++;

                NetworkInfo network = connectivityManager.getActiveNetworkInfo();
                if (network == null || !network.isConnected()) {
                    Log.i("WebSocket: Network not connected");
                    onDisconnect.run();
                    return;
                }

                int minutes = Math.min(errorCount * 2 - 1, 20);

                scheduleReconnect(
                        TimeUnit.MINUTES.toMillis(minutes), RECONNECT_SCHEDULE_REASON_CONN_ERR);
            }

            super.onFailure(webSocket, t, response);
        }
    }

    interface BadRequestRunnable {
        void execute(String message);
    }

    interface OnReconnectScheduleRunnable {
        void execute(long minutesToTryAgain, int reason);
    }
}
