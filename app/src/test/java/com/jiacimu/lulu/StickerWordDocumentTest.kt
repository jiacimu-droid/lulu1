package com.jiacimu.lulu

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [32])
class StickerWordDocumentTest {
    @Test fun wordDocumentImportsTwoPicturesWithTheirOwnCaptionsAndPreservesGroup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val docx = File(context.cacheDir, "lulu-sticker-document-test.docx")
        fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(6, 6, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            val out = ByteArrayOutputStream()
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            bitmap.recycle()
            return out.toByteArray()
        }
        fun para(body: String): String = "<w:p><w:r><w:t>$body</w:t></w:r></w:p>"
        fun picture(ref: String): String =
            "<w:p><w:r><w:drawing><a:blip r:embed=\"$ref\" /></w:drawing></w:r></w:p>"
        val document = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                <w:body>
                    ${picture("rId1")}
                    ${para("名称：一号抱抱猫")}
                    ${para("画面描述：白猫伸出前爪")}
                    ${para("使用场景：求抱抱")}
                    ${picture("rId2")}
                    ${para("名称：二号生气猫")}
                    ${para("画面描述：棕猫皱眉")}
                    ${para("使用场景：不开心")}
                </w:body>
            </w:document>
        """.trimIndent()
        val rels = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/one.png" />
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/two.png" />
            </Relationships>
        """.trimIndent()
        try {
            ZipOutputStream(docx.outputStream()).use { zip ->
                fun add(path: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                add("word/document.xml", document.toByteArray())
                add("word/_rels/document.xml.rels", rels.toByteArray())
                add("word/media/one.png", png(Color.WHITE))
                add("word/media/two.png", png(Color.BLUE))
            }
            val result = StickerWordDocument.import(context, Uri.fromFile(docx), "朋友的猫猫表情.docx")
            assertEquals("朋友的猫猫表情", result.pack)
            assertEquals(2, result.imported)
            assertTrue(result.errors.toString(), result.errors.isEmpty())
            val items = StickerLibraryStore.items.value.filter { it.pack == result.pack }
            assertEquals(2, items.size)
            assertEquals("白猫伸出前爪", items.single { it.name == "一号抱抱猫" }.visualDescription)
            assertEquals("不开心", items.single { it.name == "二号生气猫" }.usageHint)
            assertTrue(items.all { StickerLibraryStore.canActuallySend(context, it) })
            val again = StickerWordDocument.import(context, Uri.fromFile(docx), "朋友的猫猫表情.docx")
            assertEquals(0, again.imported)
            assertEquals(2, again.skipped)
        } finally {
            docx.delete()
        }
    }

    @Test fun rejectsOldBinaryDocWithAUsefulMessage() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val path = File(context.cacheDir, "anything.doc")
        path.writeText("not a modern Word document")
        try {
            val error = runCatching {
                StickerWordDocument.import(context, Uri.fromFile(path), "表情包.doc")
            }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty().contains(".docx"))
        } finally {
            path.delete()
        }
    }
}
