package com.jiacimu.lulu.data

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Persistent local cache for memory embeddings.
 *
 * The key includes model identity + memory content identity, so edits/model changes naturally create
 * a new vector instead of reusing stale semantics. Old files are lazily trimmed by last access time.
 */
internal object MemoryEmbeddingIndex {
    private const val DIRECTORY = "memory_embedding_index_v1"
    private const val MAX_VECTOR_FILES = 2_400
    private const val MAX_VECTOR_DIMENSIONS = 16_384

    private var directory: File? = null
    private val lock = Any()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (directory != null) return
            directory = File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() }
        }
    }

    fun get(key: String): FloatArray? = synchronized(lock) {
        val file = fileFor(key) ?: return@synchronized null
        if (!file.isFile) return@synchronized null
        val vector = runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val size = input.readInt()
                require(size in 1..MAX_VECTOR_DIMENSIONS)
                FloatArray(size) { input.readFloat() }
            }
        }.getOrNull()
        if (vector == null) {
            file.delete()
        } else {
            file.setLastModified(System.currentTimeMillis())
        }
        vector
    }

    fun put(key: String, vector: FloatArray) {
        if (vector.isEmpty() || vector.size > MAX_VECTOR_DIMENSIONS) return
        synchronized(lock) {
            val target = fileFor(key) ?: return
            val temp = File(target.parentFile, "${target.name}.tmp")
            val saved = runCatching {
                DataOutputStream(temp.outputStream().buffered()).use { output ->
                    output.writeInt(vector.size)
                    vector.forEach(output::writeFloat)
                }
                if (target.exists() && !target.delete()) error("无法替换旧向量")
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                true
            }.getOrDefault(false)
            if (!saved) temp.delete()
            trimLocked()
        }
    }

    fun remove(key: String) {
        synchronized(lock) { fileFor(key)?.delete() }
    }

    private fun fileFor(key: String): File? {
        val root = directory ?: return null
        return File(root, "${sha256(key)}.vec")
    }

    private fun trimLocked() {
        val root = directory ?: return
        val files = root.listFiles { file -> file.isFile && file.name.endsWith(".vec") }?.toList().orEmpty()
        if (files.size <= MAX_VECTOR_FILES) return
        files.sortedBy(File::lastModified)
            .take(files.size - MAX_VECTOR_FILES)
            .forEach(File::delete)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
