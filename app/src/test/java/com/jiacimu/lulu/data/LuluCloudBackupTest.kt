package com.jiacimu.lulu.data

import java.io.BufferedInputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * WebDAV 上传对着测试里现写的小服务器测，不碰 Android API。
 *
 * 上一版测试用的是 JDK 自带的 com.sun.net.httpserver.HttpServer，
 * 但单元测试的编译环境里没有 jdk.httpserver 这个模块，整批构建
 * 在测试编译那步就挂了（正式代码本身没问题）。所以这里改用最
 * 基础的 java.net.ServerSocket 自己写个小服务器：读请求行、请求头
 * 和 body，按事先排好的状态码依次回；测试断言上传方发来的方法、
 * 路径、认证头和 body。
 */
class LuluCloudBackupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private data class SeenRequest(
        val method: String,
        val path: String,
        val auth: String?,
        val body: ByteArray,
    )

    /** 按 [codes] 依次回状态码的小服务器；收到的请求都记下来。 */
    private class FakeServer(private val codes: List<Int>) {
        val seen = mutableListOf<SeenRequest>()
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort
        private var served = 0
        @Volatile
        private var running = true
        private val thread = Thread {
            try {
                while (running && served < codes.size) {
                    val socket = server.accept()
                    served++
                    handle(socket)
                }
            } catch (ignored: Exception) {
                // 关服务器时会打断 accept，正常。
            }
        }

        fun start() = thread.start()

        fun stop() {
            running = false
            runCatching { server.close() }
            thread.join(2000)
        }

        private fun handle(socket: Socket) {
            socket.use {
                it.soTimeout = 10_000
                val input = BufferedInputStream(it.getInputStream())
                val parts = readLine(input).split(" ")
                val method = parts.getOrNull(0).orEmpty()
                val path = parts.getOrNull(1).orEmpty()
                var contentLength = 0
                var auth: String? = null
                while (true) {
                    val line = readLine(input)
                    if (line.isEmpty()) break
                    val name = line.substringBefore(":").trim()
                    val value = line.substringAfter(":", "").trim()
                    if (name.equals("Content-Length", ignoreCase = true)) {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                    if (name.equals("Authorization", ignoreCase = true)) auth = value
                }
                val body = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(body, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                seen += SeenRequest(method, path, auth, body.copyOf(read))
                val code = codes[(served - 1).coerceIn(codes.indices)]
                val out = it.getOutputStream()
                // 回完就关连接，必须明说 Connection: close。HTTP/1.1 默认
                // 连接可复用，客户端会拿着这个已关的旧连接发下一个请求；
                // 没 body 的 MKCOL 能自动换新连接重试，但带 body 的重试
                // PUT 换不了，就会报连接被重置——上一轮测试就是这么挂的。
                // 真实网盘不会这样（它要么保持连接、要么明说关闭），所以
                // 这是假服务器的毛病，不是上传代码的毛病。
                out.write("HTTP/1.1 $code Done\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
            }
        }

        private fun readLine(input: BufferedInputStream): String {
            val bytes = mutableListOf<Byte>()
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                if (b != '\r'.code) bytes.add(b.toByte())
            }
            return bytes.toByteArray().toString(Charsets.US_ASCII)
        }
    }

    private fun backupFile(): File =
        folder.newFile("lulu-backup-test.zip").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }

    private fun basicAuth(user: String, pass: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    @Test
    fun putSucceedsWithBasicAuth() {
        val server = FakeServer(listOf(201))
        server.start()
        try {
            val result = LuluCloudBackup.upload(
                "http://localhost:${server.port}/dav", "user", "pass",
                "lulu-backups", backupFile(), "a.zip",
            )
            assertTrue(result.isSuccess)
            assertEquals(1, server.seen.size)
            assertEquals("PUT", server.seen[0].method)
            assertEquals("/dav/lulu-backups/a.zip", server.seen[0].path)
            assertEquals(basicAuth("user", "pass"), server.seen[0].auth)
            assertEquals(5, server.seen[0].body.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun missingFolderIsCreatedThenRetried() {
        val server = FakeServer(listOf(404, 201, 201))
        server.start()
        try {
            val result = LuluCloudBackup.upload(
                "http://localhost:${server.port}/dav", "user", "pass",
                "lulu-backups", backupFile(), "a.zip",
            )
            assertTrue("upload failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
            assertEquals(
                listOf("PUT", "MKCOL", "PUT"),
                server.seen.map { it.method },
            )
            assertEquals(
                listOf(
                    "/dav/lulu-backups/a.zip",
                    "/dav/lulu-backups",
                    "/dav/lulu-backups/a.zip",
                ),
                server.seen.map { it.path },
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun wrongCredentialsFailWithReadableReason() {
        val server = FakeServer(listOf(401))
        server.start()
        try {
            val result = LuluCloudBackup.upload(
                "http://localhost:${server.port}/dav", "user", "wrong",
                "lulu-backups", backupFile(), "a.zip",
            )
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("用户名或密码不对"))
        } finally {
            server.stop()
        }
    }
}
