package com.example.phonecam.streaming

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

class LocalHttpServer(
    private val context: Context,
    port: Int,
    private val streamer: MjpegStreamer
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimStart('/').ifEmpty { "index.html" }
        return when {
            uri == "index.html" -> serveAsset("index.html", "text/html; charset=utf-8")
            uri == "stream.mjpeg" -> serveMjpeg()
            uri == "snapshot.jpg" -> serveSnapshot()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        }
    }

    private fun serveAsset(name: String, mime: String): Response {
        return try {
            val data = context.assets.open(name).readBytes()
            newFixedLengthResponse(Response.Status.OK, mime, data.inputStream(), data.size.toLong())
                .also { it.addHeader("Access-Control-Allow-Origin", "*") }
        } catch (_: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "asset missing: $name")
        }
    }

    private fun serveSnapshot(): Response {
        val jpg = streamer.latest
            ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "no frame yet")
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(jpg), jpg.size.toLong())
            .also {
                it.addHeader("Access-Control-Allow-Origin", "*")
                it.addHeader("Cache-Control", "no-cache")
            }
    }

    private fun serveMjpeg(): Response {
        val boundary = "phonecam-frame"
        val pis = PipedInputStream(256 * 1024)
        val pos = PipedOutputStream(pis)
        val writer = Thread({
            var seq = -1L
            try {
                // Send a fast initial frame if available so the browser shows
                // something immediately instead of a blank <img> for the first
                // 100ms while the analyzer warms up.
                while (!Thread.currentThread().isInterrupted) {
                    val jpg = streamer.await(seq, 1000) ?: continue
                    seq = streamer.frameSeq()
                    val header = ("--" + boundary + "\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: " + jpg.size + "\r\n\r\n").toByteArray()
                    pos.write(header)
                    pos.write(jpg)
                    pos.write("\r\n".toByteArray())
                    pos.flush()
                }
            } catch (_: IOException) {
                // Client disconnected; normal.
            } catch (_: InterruptedException) {
            } finally {
                try { pos.close() } catch (_: IOException) {}
            }
        }, "mjpeg-writer").apply { isDaemon = true; start() }

        return newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$boundary",
            pis
        ).also {
            it.addHeader("Access-Control-Allow-Origin", "*")
            it.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            it.addHeader("Pragma", "no-cache")
            it.addHeader("Connection", "close")
        }
    }
}
