package com.example.phonecam.streaming

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

class LocalHttpServer(
    private val context: Context,
    port: Int,
    private val hlsDir: File
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimStart('/').ifEmpty { "index.html" }
        return when {
            uri == "index.html" -> serveAsset("index.html", "text/html; charset=utf-8")
            uri == "live.m3u8" -> serveFile(File(hlsDir, "live.m3u8"), "application/vnd.apple.mpegurl")
            uri.startsWith("seg-") && uri.endsWith(".ts") -> serveFile(File(hlsDir, uri), "video/mp2t")
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        }
    }

    private fun serveAsset(name: String, mime: String): Response {
        return try {
            val data = context.assets.open(name).readBytes()
            newFixedLengthResponse(Response.Status.OK, mime, data.inputStream(), data.size.toLong())
                .also { it.addHeader("Access-Control-Allow-Origin", "*") }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "asset missing: $name")
        }
    }

    private fun serveFile(f: File, mime: String): Response {
        if (!f.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no file")
        val fis = FileInputStream(f)
        return newFixedLengthResponse(Response.Status.OK, mime, fis, f.length()).also {
            it.addHeader("Access-Control-Allow-Origin", "*")
            it.addHeader("Cache-Control", "no-cache")
        }
    }
}
