package com.example.phonecam.storage

import android.os.StatFs
import android.util.Log
import java.io.File

class CircularStorageManager(
    private val dir: File,
    @Volatile var limitBytes: Long
) {
    private val tag = "Storage"

    @Synchronized
    fun gc() {
        val files = mp4s()?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        while (total > limitBytes && files.size > 1) {
            val oldest = files.removeAt(0)
            val len = oldest.length()
            if (oldest.delete()) {
                total -= len
                Log.i(tag, "deleted ${oldest.name} freed=${len}")
            } else {
                Log.w(tag, "failed to delete ${oldest.name}")
                break
            }
        }
    }

    fun usedBytes(): Long = mp4s()?.sumOf { it.length() } ?: 0L
    fun fileCount(): Int = mp4s()?.size ?: 0

    fun deviceFreeBytes(): Long = try {
        val sf = StatFs(dir.absolutePath)
        sf.availableBlocksLong * sf.blockSizeLong
    } catch (_: Throwable) { -1L }

    fun deviceTotalBytes(): Long = try {
        val sf = StatFs(dir.absolutePath)
        sf.blockCountLong * sf.blockSizeLong
    } catch (_: Throwable) { -1L }

    private fun mp4s(): List<File>? = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
        ?.sortedBy { it.lastModified() }
}
