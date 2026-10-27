package com.jiacimu.lulu.data

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * WebDAV 上传对着本地 HTTP 服务器测，不碰 Android API：
 * 成功、建文件夹后重传、账号密码不对三种情况都要有确定结果。
 */
class LuluCloudBackupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun backupFile(): File =
        folder.newFile("lulu-backup-test.zip").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }

    @Test
    fun putSucceedsWithBasicAuth() {
        data class Seen(val method: String, val path: String, val auth: String?, val body: ByteArray)
        val seen = mutableListOf<Seen>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes()
            seen += Seen(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestHeaders.getFirst("Authorization"),
                body,
            )
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://localhost:${server.address.port}/dav"
            val result = LuluCloudBackup.upload(base, "user", "pass", "lulu-backups", backupFile(), "a.zip")
            assertTrue(result.isSuccess)
            assertEquals(1, seen.size)
            assertEquals("PUT", seen[0].method)
            assertEquals("/dav/lulu-backups/a.zip", seen[0].path)
            val expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString("user:pass".toByteArray(Charsets.UTF_8))
            assertEquals(expectedAuth, seen[0].auth)
            assertEquals(5, seen[0].body.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun missingFolderIsCreatedThenRetried() {
        val methods = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            methods += exchange.requestMethod + " " + exchange.requestURI.path
            if (exchange.requestMethod == "PUT" && methods.count { it.startsWith("PUT") } == 1) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.sendResponseHeaders(201, -1)
            }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://localhost:${server.address.port}/dav"
            val result = LuluCloudBackup.upload(base, "user", "pass", "lulu-backups", backupFile(), "a.zip")
            assertTrue(result.isSuccess)
            assertEquals(
                listOf("PUT /dav/lulu-backups/a.zip", "MKCOL /dav/lulu-backups", "PUT /dav/lulu-backups/a.zip"),
                methods,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun wrongCredentialsFailWithReadableReason() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://localhost:${server.address.port}/dav"
            val result = LuluCloudBackup.upload(base, "user", "wrong", "lulu-backups", backupFile(), "a.zip")
            assertTrue(result.isFailure)
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("用户名或密码不对"))
        } finally {
            server.stop(0)
        }
    }

}
