package com.websocketsslpinning

import android.os.Build
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.modules.network.ForwardingCookieHandler
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.toyberman.Utils.OkHttpUtils
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.*
import okhttp3.*
import com.google.gson.Gson

class WebSocketSslPinningModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    private val cookieStore: HashMap<String, MutableList<Cookie>> = HashMap()
    private var cookieJar: CookieJar? = null
    private var cookieHandler: ForwardingCookieHandler
    private var client: OkHttpClient? = null
    private var webSocketInstance: WebSocket? = null

    init {
        cookieHandler = ForwardingCookieHandler(reactContext)
        cookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, unmodifiableCookieList: List<Cookie>) {
                synchronized(this) {
                    for (cookie in unmodifiableCookieList) {
                        setCookie(url, cookie)
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val cookies = cookieStore[url.host]
                return cookies ?: ArrayList()
            }

            private fun setCookie(url: HttpUrl, cookie: Cookie) {
                val host = url.host
                var cookieListForUrl = cookieStore[host]
                if (cookieListForUrl == null) {
                    cookieListForUrl = ArrayList()
                    cookieStore[host] = cookieListForUrl
                }
                try {
                    putCookie(url, cookieListForUrl, cookie)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            @Throws(URISyntaxException::class, IOException::class)
            private fun putCookie(url: HttpUrl, storedCookieList: MutableList<Cookie>, newCookie: Cookie) {
                var oldCookie: Cookie? = null
                val cookieMap: MutableMap<String, List<String>> = HashMap()

                for (storedCookie in storedCookieList) {
                    // create key for comparison
                    val oldCookieKey = storedCookie.name + storedCookie.path
                    val newCookieKey = newCookie.name + newCookie.path

                    if (oldCookieKey == newCookieKey) {
                        oldCookie = storedCookie
                        break
                    }
                }
                if (oldCookie != null) {
                    storedCookieList.remove(oldCookie)
                }
                storedCookieList.add(newCookie)

                cookieMap["Set-cookie"] = Collections.singletonList(newCookie.toString())
                cookieHandler.put(url.uri(), cookieMap)
            }
        }
    }

    @Throws(URISyntaxException::class)
    private fun getDomainName(url: String): String {
        val uri = URI(url)
        return if (uri.host!!.startsWith("www.")) uri.host.substring(4) else uri.host
    }

    @ReactMethod
    fun getCookies(domain: String, promise: Promise) {
        try {
            val map: WritableMap = WritableNativeMap()
            val cookies = cookieStore[getDomainName(domain)]
            cookies?.forEach { cookie ->
                map.putString(cookie.name, cookie.value)
            }
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject(e)
        }
    }

    @ReactMethod
    fun removeCookieByName(cookieName: String, promise: Promise) {
        for (domain in cookieStore.keys) {
            val newCookiesList: MutableList<Cookie> = ArrayList()
            val cookies = cookieStore[domain]
            cookies?.forEach { cookie ->
                if (cookie.name != cookieName) {
                    newCookiesList.add(cookie)
                }
            }
            cookieStore[domain] = newCookiesList
        }
        promise.resolve(null)
    }

    private fun sendEvent(eventName: String, params: String?) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    private fun convertReadableMapToJson(readableMap: ReadableMap): String {
        val gson = Gson()
        return gson.toJson(Arguments.toBundle(readableMap))
    }

    @ReactMethod
    fun sendWebSocketMessage(message: String, callback: Callback) {
        webSocketInstance?.let {
            try {
                it.send(message)
                callback.invoke(null, "Message sent successfully")
            } catch (e: Exception) {
                callback.invoke("SEND_ERROR", "Failed to send message: ${e.message}")
            }
        } ?: callback.invoke(Throwable("WebSocket not initialized"))
    }

    @ReactMethod
    fun fetch(hostname: String, options: ReadableMap, callback: Callback) {
        if (webSocketInstance != null) {
            callback.invoke(Throwable("WebSocket is already open").getMessage(), null)
            return
        }

        val response: WritableMap = Arguments.createMap()
        val domainName: String = try {
            getDomainName(hostname)
        } catch (e: URISyntaxException) {
            hostname
        }

        if (hostname.startsWith("wss://")) {
            if (options.hasKey(OPT_SSL_PINNING_KEY)) {
                if (options.getMap(OPT_SSL_PINNING_KEY).hasKey("certs")) {
                    val certs = options.getMap(OPT_SSL_PINNING_KEY).getArray("certs")
                    if (certs != null && certs.size() == 0) {
                        throw RuntimeException("certs array is empty")
                    }
                    client = OkHttpUtils.buildOkHttpClient(cookieJar, domainName, certs, options)
                } else {
                    callback.invoke(Throwable("key certs was not found"), null)
                }
            } else {
                callback.invoke(Throwable(KEY_NOT_ADDED_ERROR), null)
                return
            }

            val request = Request.Builder().url(hostname).build()
            val wsClient = client!!.newBuilder().build()

            wsClient?.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocketInstance = webSocket
                    val responseMap: WritableMap = Arguments.createMap()
                    responseMap.putString("status", "WebSocket Opened")
                    responseMap.putInt("code", response.code)
                    responseMap.putString("message", response.message)

                    callback.invoke(null, responseMap)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    sendEvent("onMessage", text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    callback.invoke(t.message, null)
                    webSocketInstance = null
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    sendEvent("onClosed", reason)
                    webSocketInstance = null
                }
            })
        }
    }

    @ReactMethod
    fun closeWebSocket(reason: String, callback: Callback) {
        webSocketInstance?.let {
            it.close(1000, reason)
            webSocketInstance = null
            callback.invoke(null, "WebSocket successfully closed")
        } ?: callback.invoke("WebSocket cannot close because it has not yet been initialized", null)
    }

    @NonNull
    private fun buildResponseHeaders(okHttpResponse: Response): WritableMap {
        val responseHeaders = okHttpResponse.headers
        val headers = Arguments.createMap()
        for (header in responseHeaders.names) {
            headers.putString(header, responseHeaders[header])
        }
        return headers
    }

    override fun getName(): String {
        return "RNSslPinning"
    }

    companion object {
        private const val OPT_SSL_PINNING_KEY = "sslPinning"
        private const val DISABLE_ALL_SECURITY = "disableAllSecurity"
        private const val RESPONSE_TYPE = "responseType"
        private const val KEY_NOT_ADDED_ERROR = "sslPinning key was not added"
    }
}


