package com.websocketsslpinning.Utils

import android.content.Context
import android.net.Uri
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONException
import java.io.*
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Created by Max Toyberman on 2/11/18.
 */

object OkHttpUtils {

    private const val HEADERS_KEY = "headers"
    private const val BODY_KEY = "body"
    private const val METHOD_KEY = "method"
    private const val FILE = "file"
    private val clientsByDomain = HashMap<String, OkHttpClient>()
    private var defaultClient: OkHttpClient? = null
    private var sslContext: SSLContext? = null
    private var content_type = "application/json; charset=utf-8"
    var mediaType: MediaType? = MediaType.parse(content_type)

    @JvmStatic
    fun buildOkHttpClient(cookieJar: CookieJar, domainName: String, certs: ReadableArray, options: ReadableMap): OkHttpClient {

        var client: OkHttpClient? = null
        var certificatePinner: CertificatePinner? = null
        if (!clientsByDomain.containsKey(domainName)) {
            // add logging interceptor
            val logging = HttpLoggingInterceptor()
            logging.setLevel(HttpLoggingInterceptor.Level.BODY)

            val clientBuilder = OkHttpClient.Builder()
            clientBuilder.cookieJar(cookieJar)

            if (options.hasKey("pkPinning") && options.getBoolean("pkPinning")) {
                // public key pinning
                certificatePinner = initPublicKeyPinning(certs, domainName)
                clientBuilder.certificatePinner(certificatePinner)
            } else {
                // ssl pinning
                val manager = initSSLPinning(certs)
                clientBuilder.sslSocketFactory(sslContext!!.socketFactory, manager)
            }

            client = clientBuilder.build()
            clientsByDomain[domainName] = client
            return client
        }

        client = clientsByDomain[domainName]

        if (options.hasKey("timeoutInterval")) {
            val timeout = options.getInt("timeoutInterval")
            // Copy to customize OkHttp for this request.
            return client!!.newBuilder()
                .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                .writeTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                .build()
        }

        return client!!
    }

    @JvmStatic
    fun buildDefaultOHttpClient(cookieJar: CookieJar, domainName: String, options: ReadableMap): OkHttpClient {

        if (defaultClient == null) {

            val logging = HttpLoggingInterceptor()
            logging.setLevel(HttpLoggingInterceptor.Level.BODY)

            val clientBuilder = OkHttpClient.Builder()
            clientBuilder.cookieJar(cookieJar)

            defaultClient = clientBuilder.build()
        }

        if (options.hasKey("timeoutInterval")) {
            val timeout = options.getInt("timeoutInterval")

            defaultClient = defaultClient!!.newBuilder()
                .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                .writeTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                .build()
        }

        return defaultClient!!
    }

    private fun initPublicKeyPinning(pins: ReadableArray, domain: String): CertificatePinner {

        val certificatePinnerBuilder = CertificatePinner.Builder()
        // add all keys to the certificates pinner
        for (i in 0 until pins.size()) {
            certificatePinnerBuilder.add(domain, pins.getString(i)!!)
        }

        return certificatePinnerBuilder.build()
    }

    private fun initSSLPinning(certs: ReadableArray): X509TrustManager? {
        var trustManager: X509TrustManager? = null
        try {
            sslContext = SSLContext.getInstance("TLS")
            val cf = CertificateFactory.getInstance("X.509")
            val keyStoreType = KeyStore.getDefaultType()
            val keyStore = KeyStore.getInstance(keyStoreType)
            keyStore.load(null, null)

            for (i in 0 until certs.size()) {
                val filename = certs.getString(i)!!
                val caInput = BufferedInputStream(OkHttpUtils::class.java.classLoader.getResourceAsStream("assets/$filename.cer"))
                val ca: Certificate
                caInput.use {
                    ca = cf.generateCertificate(it)
                }

                keyStore.setCertificateEntry(filename, ca)
            }

            val tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm()
            val tmf = TrustManagerFactory.getInstance(tmfAlgorithm)
            tmf.init(keyStore)

            val trustManagers = tmf.trustManagers
            if (trustManagers.size != 1 || trustManagers[0] !is X509TrustManager) {
                throw IllegalStateException("Unexpected default trust managers: ${trustManagers.contentToString()}")
            }
            trustManager = trustManagers[0] as X509TrustManager

            sslContext!!.init(null, arrayOf<TrustManager>(trustManager), null)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return trustManager
    }

    private fun isFilePart(part: ReadableArray): Boolean {
        if (part.getType(1) != ReadableType.Map) {
            return false
        }
        val value = part.getMap(1)
        return value!!.hasKey("type") && (value.hasKey("uri") || value.hasKey("path"))
    }

    private fun addFormDataPart(context: Context, multipartBodyBuilder: MultipartBody.Builder, fileData: ReadableMap, key: String) {
        var _uri = Uri.parse("")
        if (fileData.hasKey("uri")) {
            _uri = Uri.parse(fileData.getString("uri"))
        } else if (fileData.hasKey("path")) {
            _uri = Uri.parse(fileData.getString("path"))
        }
        val type = fileData.getString("type")!!
        var fileName = ""
        if (fileData.hasKey("fileName")) {
            fileName = fileData.getString("fileName")!!
        } else if (fileData.hasKey("name")) {
            fileName = fileData.getString("name")!!
        }

        try {
            val file = getTempFile(context, _uri)
            multipartBodyBuilder.addFormDataPart(key, fileName, RequestBody.create(MediaType.parse(type), file))
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun buildFormDataRequestBody(context: Context, formData: ReadableMap): RequestBody {
        val multipartBodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
        multipartBodyBuilder.setType(MediaType.parse("multipart/form-data"))
        if (formData.hasKey("_parts")) {
            val parts = formData.getArray("_parts")
            for (i in 0 until parts!!.size()) {
                val part = parts.getArray(i)
                var key = ""
                when (part!!.getType(0)) {
                    ReadableType.String -> key = part.getString(0)!!
                    ReadableType.Number -> key = part.getInt(0).toString()
                }

                if (isFilePart(part)) {
                    val fileData = part.getMap(1)
                    addFormDataPart(context, multipartBodyBuilder, fileData!!, key)
                } else {
                    val value = part.getString(1)!!
                    multipartBodyBuilder.addFormDataPart(key, value)
                }
            }
        }
        return multipartBodyBuilder.build()
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun buildRequest(context: Context, options: ReadableMap, hostname: String): Request {

        val requestBuilder = Request.Builder()
        var body: RequestBody? = null

        var method = "GET"

        if (options.hasKey(HEADERS_KEY)) {
            setRequestHeaders(options, requestBuilder)
        }

        if (options.hasKey(METHOD_KEY)) {
            method = options.getString(METHOD_KEY)!!
        }

        if (options.hasKey(BODY_KEY)) {
            when (options.getType(BODY_KEY)) {
                ReadableType.String -> body = RequestBody.create(mediaType, options.getString(BODY_KEY)!!)
                ReadableType.Map -> {
                    val bodyMap = options.getMap(BODY_KEY)
                    if (bodyMap!!.hasKey("formData")) {
                        val formData = bodyMap.getMap("formData")
                        body = buildFormDataRequestBody(context, formData!!)
                    } else if (bodyMap.hasKey("_parts")) {
                        body = buildFormDataRequestBody(context, bodyMap)
                    }
                }
            }
        }
        return requestBuilder
            .url(hostname)
            .method(method, body)
            .build()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun getTempFile(context: Context, uri: Uri): File {
        val file = File.createTempFile("media", null)
        val inputStream = context.contentResolver.openInputStream(uri)
        val outputStream = BufferedOutputStream(FileOutputStream(file))
        val buffer = ByteArray(1024)
        var len: Int
        while (inputStream!!.read(buffer).also { len = it } != -1) {
            outputStream.write(buffer, 0, len)
        }
        inputStream.close()
        outputStream.close()
        return file
    }

    private fun setRequestHeaders(options: ReadableMap, requestBuilder: Request.Builder) {
        val map = options.getMap(HEADERS_KEY)
        // add headers to request
        Utilities.addHeadersFromMap(map!!, requestBuilder)
        if (map.hasKey("content-type")) {
            content_type = map.getString("content-type")!!
            mediaType = MediaType.parse(content_type)
        }
    }
}
