package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.core.MemoryEntry
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Persistent local cache for memory embeddings.
 *
 * V2 names every vector with a hashed memory-id prefix. That makes deletion a real lifecycle
 * operation instead of merely making an orphaned vector unreachable. Model identity + content
 * identity remain part of the key, so edits/model changes cannot reuse stale semantics.
 */
internal object MemoryEmbeddingIndex {
    private const val DIRECTORY = "memory_embedding_index_v2"
    private const val LEGACY_DIRECTORY = "memory_embedding_index_v1"
    private const val MAX_VECTOR_FILES = 2_400
    private const val MAX_VECTOR_DIMENSIONS = 16_384

    private var directory: File? = null
    private val lock = Any()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (directory != null) return
            val root = context.applicationContext.filesDir
            // V1 filenames did not retain any reversible memory identity, so they could not be
            // removed when a source memory was deleted. Drop that cache once during V2 migration.
            runCatching { File(root, LEGACY_DIRECTORY).deleteRecursively() }
            directory = File(root, DIRECTORY).apply { mkdirs() }
        }
    }

    fun key(connection: ModelConnection, memory: MemoryEntry): String = buildString {
        append(connection.baseUrl.trimEnd('/'))
        append('|')
        append(connection.model)
        append('|')
        append(memory.id)
        append('|')
        append(memory.content.length)
        append('|')
        append(memory.content.hashCode())
    }

    fun get(key: String, memoryId: String): FloatArray? = synchronized(lock) {
        val file = fileFor(key, memoryId) ?: return@synchronized null
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

    fun put(key: String, memoryId: String, vector: FloatArray) {
        if (memoryId.isBlank() || vector.isEmpty() || vector.size > MAX_VECTOR_DIMENSIONS) return
        synchronized(lock) {
            val target = fileFor(key, memoryId) ?: return
            // An edited memory keeps the same id but must have only one current vector per model key.
            val prefix = memoryPrefix(memoryId)
            target.parentFile?.listFiles { file ->
                file.isFile && file.name.startsWith(prefix) && file.name != target.name
            }?.forEach(File::delete)
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

    fun removeMemory(memoryId: String) {
        if (memoryId.isBlank()) return
        synchronized(lock) {
            val root = directory ?: return
            val prefix = memoryPrefix(memoryId)
            root.listFiles { file -> file.isFile && file.name.startsWith(prefix) }
                ?.forEach(File::delete)
        }
    }

    private fun fileFor(key: String, memoryId: String): File? {
        val root = directory ?: return null
        return File(root, "${memoryPrefix(memoryId)}${sha256(key)}.vec")
    }

    private fun memoryPrefix(memoryId: String): String = "mem_${sha256(memoryId).take(20)}_"

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
