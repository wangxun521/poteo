package com.example.phonecam.storage

import android.util.Log
import java.io.File

class CircularStorageManager(
    private val dir: File,
    private val limitBytes: Long
) {
    private val tag = "Storage"

    @Synchronized
    fun gc() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.sortedBy { it.lastModified() }
            ?.toMutableList() ?: return
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
}
