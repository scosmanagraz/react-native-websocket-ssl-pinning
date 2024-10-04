package com.websocketsslpinning

package com.toyberman

import android.os.Build
import android.util.Log
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.modules.network.ForwardingCookieHandler
import com.google.gson.Gson
import com.toyberman.Utils.OkHttpUtils
import okhttp3.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.*
import kotlin.collections.HashMap

/**
 * TODO use proper RFC6455 spec on websocket closures
 * 7.4.1. Defined Status Codes
 *
 * Endpoints MAY use the following pre-defined status codes when sending
 * a Close frame.
 *
 * 1000 - Normal Closure
 * 1001 - Going away
 * 1002 - Protocol error
 * 1003 - Unacceptable data type
 */
class RNSslPinningModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val OPT_SSL_PINNING_KEY = "sslPinning"
        private const val DISABLE_ALL_SECURITY = "disableAllSecurity"
        private const val RESPONSE_TYPE = "responseType"
        private const val KEY_NOT_ADDED_ERROR = "sslPinning key was not added"

        @Throws(URISyntaxException::class)
        fun getDomainName(url: String): String {
            val uri = URI(url)
            val domain = uri.host
            return if (domain.startsWith("www.")) domain.substring(4) else domain
        }
    }

    private val cookieStore = HashMap<String, List<Cookie>>()
    private var cookieJar: CookieJar? = null
    private var cookieHandler: ForwardingCookieHandler
    private lateinit var client: OkHttpClient
    private var webSocketInstance: WebSocket? = null

    init {
        cookieHandler = ForwardingCookieHandler(reactContext)
        cookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, unmodifiableCookieList: List<Cookie>) {
                unmodifiableCookieList.forEach { setCookie(url, it) }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host()] ?: ArrayList()
            }

            private fun setCookie(url: HttpUrl, cookie: Cookie) {
                val host = url.host()
                val cookieListForUrl = cookieStore[host] ?: ArrayList<Cookie>().also {
                    cookieStore[host] = it
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
                val cookieMap = HashMap<String, List<String>>()

                storedCookieList.forEach { storedCookie ->
                    val oldCookieKey = storedCookie.name + storedCookie.path
                    val newCookieKey = newCookie.name + newCookie.path

                    if (oldCookieKey == newCookieKey) {
                        oldCookie = storedCookie
                        return@forEach
                    }
                }

                oldCookie?.let { storedCookieList.remove(it) }
                storedCookieList.add(newCookie)

                cookieMap["Set-cookie"] = listOf(newCookie.toString())
                cookieHandler.put(url.uri(), cookieMap)
            }
        }
    }

    @ReactMethod
    fun getCookies(domain: String, promise: Promise) {
        try {
            val map = WritableNativeMap()
            val cookies = cookieStore[getDomainName(domain)]
            cookies?.forEach { cookie -> map.putString(cookie.name, cookie.value) }
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject(e)
        }
    }

    @ReactMethod
    fun removeCookieByName(cookieName: String, promise: Promise) {
        cookieStore.keys.forEach { domain ->
            val newCookiesList = ArrayList<Cookie>()
            val cookies = cookieStore[domain]
            cookies?.forEach { cookie ->
                if (cookie.name != cookieName) newCookiesList.add(cookie)
            }
            cookieStore[domain] = newCookiesList
        }
        promise.resolve(null)
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
        } ?: run {
            callback.invoke(Throwable("WebSocket not initialized"))
        }
    }

    @ReactMethod
    fun fetch(hostname: String, options: ReadableMap, callback: Callback) {
        if (webSocketInstance != null) {
            callback.invoke(Throwable("WebSocket is already open"))
            return
        }

        val response = Arguments.createMap()
        val domainName = try {
            getDomainName(hostname)
        } catch (e: URISyntaxException) {
            hostname
        }

        if (hostname.startsWith("wss://")) {
            if (options.hasKey(OPT_SSL_PINNING_KEY)) {
                val certs = options.getMap(OPT_SSL_PINNING_KEY)?.getArray("certs")
                if (certs != null && certs.size() == 0) throw RuntimeException("certs array is empty")
                client = OkHttpUtils.buildOkHttpClient(cookieJar!!, domainName, certs!!, options)
            } else {
                callback.invoke(Throwable(KEY_NOT_ADDED_ERROR), null)
                return
            }

            val request = Request.Builder().url(hostname).build()
            val wsClient = client.newBuilder().build()

            wsClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocketInstance = webSocket
                    val responseMap = Arguments.createMap()
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
        } ?: run {
            callback.invoke("WebSocket cannot close because it has not yet been initialized", null)
        }
    }

    private fun sendEvent(eventName: String, @Nullable params: String?) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    private fun convertReadableMapToJson(readableMap: ReadableMap): String {
        val gson = Gson()
        return gson.toJson(Arguments.toBundle(readableMap))
    }

    @NonNull
    private fun buildResponseHeaders(okHttpResponse: Response): WritableMap {
        val responseHeaders = okHttpResponse.headers()
        val headerNames = responseHeaders.names()
        val headers = Arguments.createMap()
        for (header in headerNames) {
            headers.putString(header, responseHeaders[header])
        }
        return headers
    }

    override fun getName(): String {
        return "RNSslPinning"
    }
}
