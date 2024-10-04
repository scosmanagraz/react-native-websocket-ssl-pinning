package com.websocketsslpinning

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.modules.network.ForwardingCookieHandler
import com.google.gson.Gson
import com.websocketsslpinning.utils.OkHttpUtils.buildOkHttpClient
import okhttp3.*
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/**
 * TODO use proper RFC6455 spec on websocket closures
 * 7.4.1.  Defined Status Codes
 *
 * Endpoints MAY use the following pre-defined status codes when sending
 * a Close frame.
 *
 * 1000
 *
 * 1000 indicates a normal closure, meaning that the purpose for
 * which the connection was established has been fulfilled.
 *
 * 1001
 *
 * 1001 indicates that an endpoint is "going away", such as a server
 * going down or a browser having navigated away from a page.
 *
 * 1002
 *
 * 1002 indicates that an endpoint is terminating the connection due
 * to a protocol error.
 *
 * 1003
 *
 * 1003 indicates that an endpoint is terminating the connection
 * because it has received a type of data it cannot accept (e.g., an
 * endpoint that understands only text data MAY send this if it
 * receives a binary message).
 */
class RNSslPinningModule(reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {
  private val reactContext: ReactApplicationContext
  private val cookieStore: HashMap<String, MutableList<Cookie>>
  private var cookieJar: CookieJar? = null
  private val cookieHandler: ForwardingCookieHandler
  private var client: OkHttpClient? = null
  @ReactMethod
  fun getCookies(domain: String?, promise: Promise) {
    try {
      val map: WritableMap = WritableNativeMap()
      val cookies: List<Cookie>? = cookieStore[getDomainName(domain)]
      if (cookies != null) {
        for (cookie in cookies) {
          map.putString(cookie.name, cookie.value)
        }
      }
      promise.resolve(map)
    } catch (e: Exception) {
      promise.reject(e)
    }
  }

  @ReactMethod
  fun removeCookieByName(cookieName: String, promise: Promise) {
    var cookies: List<Cookie>? = null
    for (domain in cookieStore.keys) {
      val newCookiesList: MutableList<Cookie> = ArrayList()
      cookies = cookieStore[domain]
      if (cookies != null) {
        for (cookie in cookies) {
          if (cookie.name != cookieName) {
            newCookiesList.add(cookie)
          }
        }
        cookieStore[domain] = newCookiesList
      }
    }
    promise.resolve(null)
  }

  private var webSocketInstance: WebSocket? = null

  init {
    this.reactContext = reactContext
    cookieStore = HashMap()
    cookieHandler = ForwardingCookieHandler(reactContext)
    cookieJar = object : CookieJar {
      @Synchronized
      override fun saveFromResponse(url: HttpUrl, unmodifiableCookieList: List<Cookie>) {
        for (cookie in unmodifiableCookieList) {
          setCookie(url, cookie)
        }
      }

      @Synchronized
      override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookies: List<Cookie>? = cookieStore[url.host]
        return cookies ?: ArrayList()
      }

      fun setCookie(url: HttpUrl, cookie: Cookie) {
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
      private fun putCookie(
        url: HttpUrl,
        storedCookieList: MutableList<Cookie>,
        newCookie: Cookie
      ) {
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
        cookieMap["Set-cookie"] = listOf(newCookie.toString())
        cookieHandler.put(url.toUri(), cookieMap)
      }
    }
  }

  /**
   * Send this to Javascript
   * @param eventName
   * @param params
   */
  private fun sendEvent(eventName: String, params: String?) {
    reactContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      .emit(eventName, params)
  }

  /**
   * Javascript message to JSON
   * @param message
   * @return
   */
  private fun convertReadableMapToJson(readableMap: ReadableMap): String {
    val gson = Gson()
    return gson.toJson(Arguments.toBundle(readableMap))
  }

  @ReactMethod
  fun sendWebSocketMessage(message: String?, callback: Callback) {
    if (webSocketInstance != null) {
      try {
        // Send the WebSocket message
        webSocketInstance!!.send(message!!)
        callback.invoke(null, "Message sent successfully")
      } catch (e: Exception) {
        callback.invoke("SEND_ERROR", "Failed to send message: " + e.message)
      }
    } else {
      callback.invoke(Throwable("WebSocket not initialized"))
    }
  }

  @ReactMethod
  fun fetch(hostname: String, options: ReadableMap, callback: Callback) {
    if (webSocketInstance != null) {
      callback.invoke(Throwable("WebSocket is already open"))
      return
    }
    val response: WritableMap = Arguments.createMap()
    val domainName: String
    domainName = try {
      getDomainName(hostname)
    } catch (e: URISyntaxException) {
      hostname
    }

    // Check if WSS (WebSocket Secure) is being used
    if (hostname.startsWith("wss://")) {
      // Use the same SSL pinning mechanism for WSS
      if (options.hasKey(OPT_SSL_PINNING_KEY)) {
        if (options.getMap(OPT_SSL_PINNING_KEY).hasKey("certs")) {
          val certs: ReadableArray = options.getMap(OPT_SSL_PINNING_KEY).getArray("certs")
          if (certs != null && certs.size() === 0) {
            throw RuntimeException("certs array is empty")
          }
          // Build OkHttpClient with WSS and SSL pinning
          client = buildOkHttpClient(cookieJar, domainName, certs, options)
        } else {
          callback.invoke(Throwable("key certs was not found"), null)
        }
      } else {
        callback.invoke(Throwable(KEY_NOT_ADDED_ERROR), null)
        return
      }

      // Initialize WebSocket request
      val request: Request = Builder().url(hostname).build()
      val wsClient = client!!.newBuilder().build()

      // Open the WebSocket connection
      wsClient.newWebSocket(request, object : WebSocketListener() {
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
  fun closeWebSocket(reason: String?, callback: Callback) {
    if (webSocketInstance != null) {
      webSocketInstance!!.close(1000, reason)
      webSocketInstance = null
      callback.invoke(null, "WebSocket successfully closed")
    } else {
      callback.invoke("WebSocket cannot close because it has not yet been initialized", null)
      return
    }
  }

  @NonNull
  private fun buildResponseHeaders(okHttpResponse: Response): WritableMap {
    val responseHeaders = okHttpResponse.headers
    val headerNames = responseHeaders.names()
    val headers: WritableMap = Arguments.createMap()
    for (header in headerNames) {
      headers.putString(header, responseHeaders[header])
    }
    return headers
  }

  val name: String
    get() = "RNSslPinning"

  companion object {
    private const val OPT_SSL_PINNING_KEY = "sslPinning"
    private const val DISABLE_ALL_SECURITY = "disableAllSecurity"
    private const val RESPONSE_TYPE = "responseType"
    private const val KEY_NOT_ADDED_ERROR = "sslPinning key was not added"
    @Throws(URISyntaxException::class)
    fun getDomainName(url: String?): String {
      val uri = URI(url)
      val domain = uri.host
      return if (domain.startsWith("www.")) domain.substring(4) else domain
    }
  }
}
