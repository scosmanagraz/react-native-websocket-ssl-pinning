package com.websocketsslpinning.utils

import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableMapKeySetIterator
import com.websocketsslpinning.BuildConfig
import java.io.*

/**
 * Created by Max Toyberman on 2/5/18.
 */
object Utilities {
  // Copy an InputStream to a File.
  fun copyInputStreamToFile(`in`: InputStream, file: File?) {
    var out: OutputStream? = null
    try {
      out = FileOutputStream(file)
      val buf = ByteArray(1024)
      var len: Int
      while (`in`.read(buf).also { len = it } > 0) {
        out.write(buf, 0, len)
      }
    } catch (e: Exception) {
      if (BuildConfig.DEBUG) {
        e.printStackTrace()
      }
    } finally {
      // Ensure that the InputStreams are closed even if there's an exception.
      try {
        out?.close()

        // If you want to close the "in" InputStream yourself then remove this
        // from here but ensure that you close it yourself eventually.
        `in`.close()
      } catch (e: IOException) {
        if (BuildConfig.DEBUG) {
          e.printStackTrace()
        }
      }
    }
  }

  /**
   * @param map     - map of headers
   * @param builder - request builder, all headers will be added to this request
   */
  fun addHeadersFromMap(map: ReadableMap, builder: Builder) {
    val iterator: ReadableMapKeySetIterator = map.keySetIterator()
    while (iterator.hasNextKey()) {
      val key: String = iterator.nextKey()
      builder.addHeader(key, map.getString(key))
    }
  }
}
