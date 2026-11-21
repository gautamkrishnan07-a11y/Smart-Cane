package com.example.pip

import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object PiVideoReceiver {

    suspend fun receiveFrames(
        baseUrl: String,
        onFrame: (android.graphics.Bitmap) -> Unit
    ) = withContext(Dispatchers.IO) {

        val videoUrl = "${baseUrl.trimEnd('/')}/video_feed"

        while (isActive) {

            var connection: HttpURLConnection? = null

            try {
                connection = URL(videoUrl)
                    .openConnection() as HttpURLConnection

                connection.connectTimeout = 5000
                connection.readTimeout = 0
                connection.connect()

                connection.inputStream.use { input ->

                    var previous = -1

                    while (isActive) {

                        val current = input.read()

                        if (current == -1) break

                        if (previous == 0xFF && current == 0xD8) {

                            val jpeg = java.io.ByteArrayOutputStream()
                            jpeg.write(0xFF)
                            jpeg.write(0xD8)

                            var last = -1

                            while (isActive) {

                                val byte = input.read()

                                if (byte == -1) break

                                jpeg.write(byte)

                                if (last == 0xFF && byte == 0xD9) {

                                    val bytes = jpeg.toByteArray()

                                    val bitmap =
                                        BitmapFactory.decodeByteArray(
                                            bytes,
                                            0,
                                            bytes.size
                                        )

                                    if (bitmap != null) {
                                        onFrame(bitmap)
                                    }

                                    break
                                }

                                last = byte
                            }

                            previous = -1

                        } else {
                            previous = current
                        }
                    }
                }

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {
                delay(2000)

            } finally {
                connection?.disconnect()
            }
        }
    }
}