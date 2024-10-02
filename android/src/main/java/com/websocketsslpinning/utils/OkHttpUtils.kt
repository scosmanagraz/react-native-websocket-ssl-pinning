package com.websocketsslpinning.Utils

import android.content.Context
import android.net.Uri
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import okhttp3.CertificatePinner
import okhttp3.CookieJar
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.util.Arrays
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object OkHttpUtils {

    private const val HEADERS_KEY = "headers"
    private const val BODY_KEY = "body"
    private const val METHOD_KEY = "method"
    private const val FILE = "file"
    private val clientsByDomain = HashMap<String, OkHttpClient>()
    private var defaultClient: OkHttpClient? = null
    private lateinit var sslContext: SSLContext
    private var contentType = "application/json; charset=utf-8"
    var mediaType: MediaType? = MediaType.parse(contentType)

    fun buildOkHttpClient(cookieJar: CookieJar, domainName: String, certs: ReadableArray, options: ReadableMap): OkHttpClient? {
        var client: OkHttpClient? = null
        var certificatePinner: CertificatePinner? = null
        if (!clientsByDomain.containsKey(domainName)) {
            val logging = HttpLoggingInterceptor()
            logging.level = HttpLoggingInterceptor.Level.BODY

            val clientBuilder = OkHttpClient.Builder()
            clientBuilder.cookieJar(cookieJar)

            if (options.hasKey("pkPinning") && options.getBoolean("pkPinning")) {
                certificatePinner = initPublicKeyPinning(certs, domainName)
                clientBuilder.certificatePinner(certificatePinner)
            } else {
                val manager = initSSLPinning(certs)
                clientBuilder.sslSocketFactory(sslContext.socketFactory, manager)
            }

            // clientBuilder.addInterceptor(logging)

            client = clientBuilder.build()
            clientsByDomain[domainName] = client
            return client
        }

        client = clientsByDomain[domainName]

        if (options.hasKey("timeoutInterval")) {
            val timeout = options.getInt("timeoutInterval")
            val client2 = client.newBuilder()
                .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                .writeTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                .build()
            return client2
        }

        return client
    }

    fun buildDefaultOkHttpClient(cookieJar: CookieJar, domainName: String, options: ReadableMap): OkHttpClient? {
        if (defaultClient == null) {
            val logging = HttpLoggingInterceptor()
            logging.level = HttpLoggingInterceptor.Level.BODY

            val clientBuilder = OkHttpClient.Builder()
            clientBuilder.cookieJar(cookieJar)

            // clientBuilder.addInterceptor(logging)

            defaultClient = clientBuilder.build()
        }

        if (options.hasKey("timeoutInterval")) {
            val timeout = options.getInt("timeoutInterval")
            defaultClient = defaultClient?.newBuilder()
                ?.readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                ?.writeTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                ?.connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                ?.build()
        }

        return defaultClient
    }

    private fun initPublicKeyPinning(pins: ReadableArray, domain: String): CertificatePinner {
        val certificatePinnerBuilder = CertificatePinner.Builder()
        for (i in 0 until pins.size()) {
            certificatePinnerBuilder.add(domain, pins.getString(i))
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
                val filename = certs.getString(i)
                val caInput: InputStream = BufferedInputStream(OkHttpUtils::class.java.classLoader?.getResourceAsStream("assets/$filename.cer")!!)
                val ca: Certificate
                try {
                    ca = cf.generateCertificate(caInput)
                } finally {
                    caInput.close()
                }
                keyStore.setCertificateEntry(filename, ca)
            }

            val tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm()
            val tmf = TrustManagerFactory.getInstance(tmfAlgorithm)
            tmf.init(keyStore)

            val trustManagers = tmf.trustManagers
            if (trustManagers.size != 1 || trustManagers[0] !is X509TrustManager) {
                throw IllegalStateException("Unexpected default trust managers:" + Arrays.toString(trustManagers))
            }
            trustManager = trustManagers[0] as X509TrustManager
            sslContext.init(null, arrayOf(trustManager), null)

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
        return value.hasKey("type") && (value.hasKey("uri") || value.hasKey("path"))
    }

    private fun addFormDataPart(context: Context, multipartBodyBuilder: MultipartBody.Builder, fileData: ReadableMap, key: String) {
        var uri = Uri.parse("")
        if (fileData.hasKey("uri")) {
            uri = Uri.parse(fileData.getString("uri"))
        } else if (fileData.hasKey("path")) {
            uri = Uri.parse(fileData.getString("path"))
        }
        val type = fileData.getString("type")
        var fileName = ""
        if (fileData.hasKey("fileName")) {
            fileName = fileData.getString("fileName")
        } else if (fileData.hasKey("name")) {
            fileName = fileData.getString("name")
        }

        try {
            val file = getTempFile(context, uri)
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
            for (i in 0 until parts.size()) {
                val part = parts.getArray(i)
                var key = ""
                when (part.getType(0)) {
                    ReadableType.String -> key = part.getString(0)
                    ReadableType.Number -> key = part.getInt(0).toString()
                }

                if (isFilePart(part)) {
                    val fileData = part.getMap(1)
                    addFormDataPart(context, multipartBodyBuilder, fileData, key)
                } else {
                    val value = part.getString(1)
                    multipartBodyBuilder.addFormDataPart(key, value)
                }
            }
        }
        return multipartBodyBuilder.build()
    }

    @Throws(JSONException::class)
    fun buildRequest(context: Context, options: ReadableMap, hostname: String): Request {
        val requestBuilder = Request.Builder()
        var body: RequestBody? = null

        var method = "GET"

        if (options.hasKey(HEADERS_KEY)) {
            setRequestHeaders(options, requestBuilder)
        }

        if (options.hasKey(METHOD_KEY)) {
            method = options.getString(METHOD_KEY)
        }

        if (options.hasKey(BODY_KEY)) {
            val bodyType = options.getType(BODY_KEY)
            when (bodyType) {
                ReadableType.String -> body = RequestBody.create(mediaType, options.getString(BODY_KEY))
                ReadableType.Map -> {
                    val bodyMap = options.getMap(BODY_KEY)
                    if (bodyMap.hasKey("formData")) {
                        val formData = bodyMap.getMap("formData")
                        body = buildFormDataRequestBody(context, formData)
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

    @Throws(IOException::class)
    fun getTempFile(context: Context, uri: Uri): File {
        val file = File.createTempFile("media", null)
        val inputStream = context.contentResolver.openInputStream(uri)
        val outputStream: OutputStream = BufferedOutputStream(FileOutputStream(file))
        val buffer = ByteArray(1024)
        var len: Int
        while (inputStream.read(buffer).also { len = it } != -1) {
            outputStream.write(buffer, 0, len)
        }
        inputStream.close()
        outputStream.close()
        return file
    }

    private fun setRequestHeaders(options: ReadableMap, requestBuilder: Request.Builder) {
        val map = options.getMap(HEADERS_KEY)
        Utilities.addHeadersFromMap(map, requestBuilder)
        if (map.hasKey("content-type")) {
            contentType = map.getString("content-type")
            mediaType = MediaType.parse(contentType)
        }
    }
}

