package com.websocketsslpinning.Utils

import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableMapKeySetIterator
import okhttp3.Request
import java.io.*

/**
 * Created by Max Toyberman on 2/5/18.
 */
object Utilities {

    // Copy an InputStream to a File.
    @JvmStatic
    fun copyInputStreamToFile(input: InputStream, file: File) {
        var output: OutputStream? = null
        try {
            output = FileOutputStream(file)
            val buffer = ByteArray(1024)
            var length: Int
            while (input.read(buffer).also { length = it } > 0) {
                output.write(buffer, 0, length)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // Ensure that the InputStreams are closed even if there's an exception.
            try {
                output?.close()
                // If you want to close the "input" InputStream yourself then remove this
                input.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    /**
     * @param map     - map of headers
     * @param builder - request builder, all headers will be added to this request
     */
    @JvmStatic
    fun addHeadersFromMap(map: ReadableMap, builder: Request.Builder) {
        val iterator: ReadableMapKeySetIterator = map.keySetIterator()
        while (iterator.hasNextKey()) {
            val key = iterator.nextKey()
            builder.addHeader(key, map.getString(key))
        }
    }
}
