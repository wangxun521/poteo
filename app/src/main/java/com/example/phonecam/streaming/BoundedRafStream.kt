package com.example.phonecam.streaming

import java.io.InputStream
import java.io.RandomAccessFile

/** Reads at most [limit] bytes starting from the current position of [raf]. */
class BoundedRafStream(private val raf: RandomAccessFile, private var limit: Long) : InputStream() {
    override fun read(): Int {
        if (limit <= 0) return -1
        val b = raf.read()
        if (b != -1) limit--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (limit <= 0) return -1
        val toRead = minOf(len.toLong(), limit).toInt()
        val n = raf.read(b, off, toRead)
        if (n > 0) limit -= n
        return n
    }

    override fun available(): Int = limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override fun close() { try { raf.close() } catch (_: Throwable) {} }
}
