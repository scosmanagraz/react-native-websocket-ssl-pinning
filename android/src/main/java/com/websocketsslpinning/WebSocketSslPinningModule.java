package com.websocketsslpinning;

import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.modules.network.ForwardingCookieHandler;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.websocketsslpinning.utils.OkHttpUtils;

import org.json.JSONException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.Response;

import com.google.gson.Gson;

/**
 * TODO use proper RFC6455 spec on websocket closures
 * 7.4.1.  Defined Status Codes

   Endpoints MAY use the following pre-defined status codes when sending
   a Close frame.

   1000

      1000 indicates a normal closure, meaning that the purpose for
      which the connection was established has been fulfilled.

   1001

      1001 indicates that an endpoint is "going away", such as a server
      going down or a browser having navigated away from a page.

   1002

      1002 indicates that an endpoint is terminating the connection due
      to a protocol error.

   1003

      1003 indicates that an endpoint is terminating the connection
      because it has received a type of data it cannot accept (e.g., an
      endpoint that understands only text data MAY send this if it
      receives a binary message).
 */

public class WebSocketSslPinningModule extends ReactContextBaseJavaModule {


    private static final String OPT_SSL_PINNING_KEY = "sslPinning";
    private static final String DISABLE_ALL_SECURITY = "disableAllSecurity";
    private static final String RESPONSE_TYPE = "responseType";
    private static final String KEY_NOT_ADDED_ERROR = "sslPinning key was not added";

    private final ReactApplicationContext reactContext;
    private final HashMap<String, List<Cookie>> cookieStore;
    private CookieJar cookieJar = null;
    private ForwardingCookieHandler cookieHandler;
    private OkHttpClient client;

    public WebSocketSslPinningModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        cookieStore = new HashMap<>();
        cookieHandler = new ForwardingCookieHandler(reactContext);
        cookieJar = new CookieJar() {

            @Override
            public synchronized void saveFromResponse(HttpUrl url, List<Cookie> unmodifiableCookieList) {
                for (Cookie cookie : unmodifiableCookieList) {
                    setCookie(url, cookie);
                }
            }

            @Override
            public synchronized List<Cookie> loadForRequest(HttpUrl url) {
                List<Cookie> cookies = cookieStore.get(url.host());
                return cookies != null ? cookies : new ArrayList<Cookie>();
            }

            public void setCookie(HttpUrl url, Cookie cookie) {

                final String host = url.host();

                List<Cookie> cookieListForUrl = cookieStore.get(host);
                if (cookieListForUrl == null) {
                    cookieListForUrl = new ArrayList<Cookie>();
                    cookieStore.put(host, cookieListForUrl);
                }
                try {
                    putCookie(url, cookieListForUrl, cookie);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            private void putCookie(HttpUrl url, List<Cookie> storedCookieList, Cookie newCookie) throws URISyntaxException, IOException {

                Cookie oldCookie = null;
                Map<String, List<String>> cookieMap = new HashMap<>();

                for (Cookie storedCookie : storedCookieList) {

                    // create key for comparison
                    final String oldCookieKey = storedCookie.name() + storedCookie.path();
                    final String newCookieKey = newCookie.name() + newCookie.path();

                    if (oldCookieKey.equals(newCookieKey)) {
                        oldCookie = storedCookie;
                        break;
                    }
                }
                if (oldCookie != null) {
                    storedCookieList.remove(oldCookie);
                }
                storedCookieList.add(newCookie);

                cookieMap.put("Set-cookie", Collections.singletonList(newCookie.toString()));
                cookieHandler.put(url.uri(), cookieMap);
            }
        };

    }

    public static String getDomainName(String url) throws URISyntaxException {
        URI uri = new URI(url);
        String domain = uri.getHost();
        return domain.startsWith("www.") ? domain.substring(4) : domain;
    }


    @ReactMethod
    public void getCookies(String domain, final Promise promise) {
        try {
            WritableMap map = new WritableNativeMap();

            List<Cookie> cookies = cookieStore.get(getDomainName(domain));

            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    map.putString(cookie.name(), cookie.value());
                }
            }

            promise.resolve(map);
        } catch (Exception e) {
            promise.reject(e);
        }
    }


    @ReactMethod
    public void removeCookieByName(String cookieName, final Promise promise) {
        List<Cookie> cookies = null;

        for (String domain : cookieStore.keySet()) {
            List<Cookie> newCookiesList = new ArrayList<>();

            cookies = cookieStore.get(domain);
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (!cookie.name().equals(cookieName)) {
                        newCookiesList.add(cookie);
                    }
                }
                cookieStore.put(domain, newCookiesList);
            }
        }

        promise.resolve(null);
    }

    private WebSocket webSocketInstance;

    /**
     * Send this to Javascript
     * @param eventName
     * @param params
     */
    private void sendEvent(String eventName, @Nullable String params) {
        this.reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(eventName, params);
    }

    /**
     * Javascript message to JSON
     * @param message
     * @return
     */
    private String convertReadableMapToJson(ReadableMap readableMap) {
        Gson gson = new Gson();
        return gson.toJson(Arguments.toBundle(readableMap));
    }

    @ReactMethod
    public void sendWebSocketMessage(String message, final Callback callback) {
        if (webSocketInstance != null) {
            try {
                // Send the WebSocket message
                webSocketInstance.send(message);
                callback.invoke(null, "Message sent successfully");
            } catch (Exception e) {
                callback.invoke("SEND_ERROR", "Failed to send message: " + e.getMessage());
            }
        } else {
            callback.invoke(new Throwable("WebSocket not initialized"));
        }
    }

    @ReactMethod
    public void fetch(String hostname, final ReadableMap options, final Callback callback) {
        if (webSocketInstance != null) {
            callback.invoke(new Throwable("WebSocket is already open"));
            return;
        }

        final WritableMap response = Arguments.createMap();
        String domainName;
        try {
            domainName = getDomainName(hostname);
        } catch (URISyntaxException e) {
            domainName = hostname;
        }

        // Check if WSS (WebSocket Secure) is being used
        if (hostname.startsWith("wss://")) {
            // Use the same SSL pinning mechanism for WSS
            if (options.hasKey(OPT_SSL_PINNING_KEY)) {
                if (options.getMap(OPT_SSL_PINNING_KEY).hasKey("certs")) {
                    ReadableArray certs = options.getMap(OPT_SSL_PINNING_KEY).getArray("certs");
                    if (certs != null && certs.size() == 0) {
                        throw new RuntimeException("certs array is empty");
                    }
                    // Build OkHttpClient with WSS and SSL pinning
                    client = OkHttpUtils.buildOkHttpClient(cookieJar, domainName, certs, options);
                } else {
                    callback.invoke(new Throwable("key certs was not found"), null);
                }
            } else {
                callback.invoke(new Throwable(KEY_NOT_ADDED_ERROR), null);
                return;
            }

            // Initialize WebSocket request
            Request request = new Request.Builder().url(hostname).build();
            OkHttpClient wsClient = client.newBuilder().build();
            
            // Open the WebSocket connection
            wsClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    webSocketInstance = webSocket;

                    WritableMap responseMap = Arguments.createMap();
                    responseMap.putString("status", "WebSocket Opened");
                    responseMap.putInt("code", response.code());
                    responseMap.putString("message", response.message());
                
                    callback.invoke(null, responseMap);
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    sendEvent("onMessage", text);
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    callback.invoke(t.getMessage(), null);
                    webSocketInstance = null;
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    webSocket.close(1000, null);
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    sendEvent("onClosed", reason);
                    webSocketInstance = null;
                }
            });
            
        }
    }

    @ReactMethod
    public void closeWebSocket(String reason, Callback callback) {
        if (webSocketInstance != null) {
            webSocketInstance.close(1000, reason);
            webSocketInstance = null;
            callback.invoke(null, "WebSocket successfully closed");
        } else {
            callback.invoke("WebSocket cannot close because it has not yet been initialized", null);
            return;
        }
    }


    @NonNull
    private WritableMap buildResponseHeaders(Response okHttpResponse) {
        Headers responseHeaders = okHttpResponse.headers();
        Set<String> headerNames = responseHeaders.names();
        WritableMap headers = Arguments.createMap();
        for (String header : headerNames) {
            headers.putString(header, responseHeaders.get(header));
        }
        return headers;
    }

    @Override
    public String getName() {
        return "WebSocketSslPinning";
    }

}
