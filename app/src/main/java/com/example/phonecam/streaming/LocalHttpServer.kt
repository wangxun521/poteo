package com.example.phonecam.streaming

import android.content.Context
import com.example.phonecam.service.RecordingService
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.RandomAccessFile

class LocalHttpServer(
    private val context: Context,
    port: Int,
    private val streamer: MjpegStreamer,
    private val binder: RecordingService.LocalBinder,
    private val videoDir: File
) : NanoHTTPD(port) {

    private val thermal = ThermalReader(context)

    override fun serve(session: IHTTPSession): Response {
        val rawUri = session.uri.trimStart('/').ifEmpty { "index.html" }
        val (path, _) = rawUri.split('?', limit = 2).let { it[0] to it.getOrNull(1) }
        return try {
            when {
                path == "index.html" -> asset("index.html", "text/html; charset=utf-8")
                path == "stream.mjpeg" -> mjpeg()
                path == "snapshot.jpg" -> snapshot()
                path == "cameras" -> camerasJson()
                path == "switch" -> switchCamera(session)
                path == "config" -> if (session.method == Method.POST) applyConfig(session) else configJson()
                path == "thermal" -> jsonOk(thermal.toJson())
                path == "recordings" -> recordingsJson()
                path.startsWith("recordings/") -> serveRecording(session, path.removePrefix("recordings/"))
                else -> json404("not found")
            }
        } catch (t: Throwable) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", t.message ?: "err")
        }
    }

    // ----- web assets -----
    private fun asset(name: String, mime: String): Response {
        return try {
            val data = context.assets.open(name).readBytes()
            newFixedLengthResponse(Response.Status.OK, mime, data.inputStream(), data.size.toLong())
                .also { it.addHeader("Access-Control-Allow-Origin", "*") }
        } catch (_: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "asset missing: $name")
        }
    }

    // ----- live stream -----
    private fun snapshot(): Response {
        val jpg = streamer.latest
            ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "no frame yet")
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(jpg), jpg.size.toLong())
            .also {
                it.addHeader("Access-Control-Allow-Origin", "*")
                it.addHeader("Cache-Control", "no-cache")
            }
    }

    private fun mjpeg(): Response {
        val boundary = "phonecam-frame"
        val pis = PipedInputStream(256 * 1024)
        val pos = PipedOutputStream(pis)
        Thread({
            var seq = -1L
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val jpg = streamer.await(seq, 1000) ?: continue
                    seq = streamer.frameSeq()
                    val header = ("--$boundary\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${jpg.size}\r\n\r\n").toByteArray()
                    pos.write(header); pos.write(jpg); pos.write("\r\n".toByteArray()); pos.flush()
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            } finally { try { pos.close() } catch (_: IOException) {} }
        }, "mjpeg-writer").apply { isDaemon = true; start() }

        return newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$boundary", pis
        ).also {
            it.addHeader("Access-Control-Allow-Origin", "*")
            it.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            it.addHeader("Pragma", "no-cache")
            it.addHeader("Connection", "close")
        }
    }

    // ----- camera selection -----
    private fun camerasJson(): Response {
        val cur = binder.currentCameraId()
        val items = binder.listCameras().joinToString(",") { e ->
            """{"id":"${esc(e.id)}","label":"${esc(e.label)}","facing":"${esc(e.facing)}"}"""
        }
        return jsonOk("""{"current":"${esc(cur)}","cameras":[$items]}""")
    }

    private fun switchCamera(session: IHTTPSession): Response {
        val id = session.parms["id"] ?: return json400("missing id")
        val ok = binder.switchCamera(id)
        return if (ok) jsonOk("""{"ok":true,"current":"${esc(binder.currentCameraId())}"}""")
               else json400("switch failed")
    }

    // ----- config -----
    private fun configJson(): Response {
        val c = binder.config()
        return jsonOk(c.toJson())
    }

    private fun applyConfig(session: IHTTPSession): Response {
        val p = session.parms
        val cur = binder.config()
        val next = cur.copy(
            saveAudio = p["saveAudio"]?.toBooleanStrictOrNull() ?: cur.saveAudio,
            recQuality = p["recQuality"]?.uppercase() ?: cur.recQuality,
            recBitrate = p["recBitrate"]?.toIntOrNull()?.coerceIn(200_000, 20_000_000) ?: cur.recBitrate,
            streamWidth = p["streamWidth"]?.toIntOrNull()?.coerceIn(120, 1920) ?: cur.streamWidth,
            streamHeight = p["streamHeight"]?.toIntOrNull()?.coerceIn(90, 1080) ?: cur.streamHeight,
            streamFps = p["streamFps"]?.toIntOrNull()?.coerceIn(1, 30) ?: cur.streamFps,
            jpegQuality = p["jpegQuality"]?.toIntOrNull()?.coerceIn(10, 95) ?: cur.jpegQuality,
            cameraId = p["cameraId"] ?: cur.cameraId
        )
        val ok = binder.applyConfig(next)
        return if (ok) jsonOk("""{"ok":true,"config":${binder.config().toJson()}}""")
               else json400("apply failed")
    }

    // ----- recordings -----
    private fun recordingsJson(): Response {
        val files = videoDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val items = files.joinToString(",") { f ->
            """{"name":"${esc(f.name)}","size":${f.length()},"mtime":${f.lastModified()}}"""
        }
        return jsonOk("""{"items":[$items]}""")
    }

    /** HTTP Range support so the browser can seek within an MP4. */
    private fun serveRecording(session: IHTTPSession, name: String): Response {
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return json400("bad name")
        val f = File(videoDir, name)
        if (!f.exists() || !f.isFile) return json404("not found")
        val total = f.length()
        val rangeHeader = session.headers["range"]
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            val resp = newFixedLengthResponse(Response.Status.OK, "video/mp4", FileInputStream(f), total)
            resp.addHeader("Accept-Ranges", "bytes")
            resp.addHeader("Content-Length", total.toString())
            return resp
        }
        // Parse "bytes=START-END"
        val spec = rangeHeader.removePrefix("bytes=").trim()
        val parts = spec.split('-', limit = 2)
        val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val end = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (total - 1)
        if (start < 0 || end >= total || start > end) {
            val resp = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "bad range")
            resp.addHeader("Content-Range", "bytes */$total")
            return resp
        }
        val length = end - start + 1
        val raf = RandomAccessFile(f, "r"); raf.seek(start)
        val resp = newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT, "video/mp4",
            BoundedRafStream(raf, length), length
        )
        resp.addHeader("Content-Range", "bytes $start-$end/$total")
        resp.addHeader("Accept-Ranges", "bytes")
        resp.addHeader("Content-Length", length.toString())
        return resp
    }

    // ----- helpers -----
    private fun jsonOk(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)
            .also { it.addHeader("Access-Control-Allow-Origin", "*"); it.addHeader("Cache-Control", "no-cache") }

    private fun json400(msg: String): Response =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", """{"error":"${esc(msg)}"}""")

    private fun json404(msg: String): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8", """{"error":"${esc(msg)}"}""")

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
