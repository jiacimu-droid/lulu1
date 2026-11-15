package com.jiacimu.lulu

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class QqShoppingProductLinkOpenerTest {
    private fun product(merchant: String, url: String) =
        QqShoppingProductLink(merchant, url, "江渡选的商品")

    @Test fun merchantAppReceivesTheOriginalUrlBeforeAnyBrowser() {
        val goods = product("拼多多", "https://mobile.yangkeduo.com/goods.html?goods_id=123")
        val intents = QqShoppingProductLinkOpener.candidates(goods)
        assertEquals(listOf("com.xunmeng.pinduoduo", null), intents.map { it.`package` })
        intents.forEach {
            assertEquals(Intent.ACTION_VIEW, it.action)
            assertTrue(it.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertEquals(goods.url, it.dataString)
            assertTrue(it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
        val launched = mutableListOf<String?>()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) { launched.add(intent.`package`) }
        }
        assertEquals(QqShoppingProductLinkOpener.Result.MERCHANT_APP,
            QqShoppingProductLinkOpener.open(context, goods))
        assertEquals(listOf("com.xunmeng.pinduoduo"), launched)
    }

    @Test fun existingTaobaoAndMeituanCardsPreferTheirCorrespondingApps() {
        val flash = QqShoppingProductLinkOpener.candidates(
            product("淘宝闪购", "https://u.ele.me/Flash123"))
        assertEquals(listOf("com.taobao.taobao", "me.ele", null),
            flash.map { it.`package` })
        assertEquals(listOf("com.taobao.idlefish", null),
            QqShoppingProductLinkOpener.candidates(
                product("闲鱼", "https://h5.m.goofish.com/item?id=123"))
                .map { it.`package` })
        assertEquals(listOf("com.sankuai.meituan.takeoutnew", "com.sankuai.meituan", null),
            QqShoppingProductLinkOpener.candidates(
                product("美团外卖", "https://i.meituan.com/deal/123"))
                .map { it.`package` })
        assertEquals(listOf("com.jingdong.app.mall", null),
            QqShoppingProductLinkOpener.candidates(
                product("京东", "https://item.jd.com/123.html"))
                .map { it.`package` })
    }

    @Test fun fallsBackOnlyAfterInstalledAppHandlersRejectTheLink() {
        val goods = product("拼多多", "https://p.pinduoduo.com/Abc123")
        val attempts = mutableListOf<String?>()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) {
                attempts.add(intent.`package`)
                if (intent.`package` != null) throw ActivityNotFoundException("No app link handler")
            }
        }
        assertEquals(QqShoppingProductLinkOpener.Result.SYSTEM_HANDLER,
            QqShoppingProductLinkOpener.open(context, goods))
        assertEquals(listOf("com.xunmeng.pinduoduo", null), attempts)
    }

    @Test fun rejectsUnverifiedOrSpoofedLinksWithoutLaunchingAnything() {
        val bad = listOf(
            product("拼多多", "https://pinduoduo.com.evil.test/steal"),
            product("拼多多", "http://mobile.yangkeduo.com/goods.html"),
            product("淘宝", "https://u.ele.me.evil.test/123"),
            product("未知商城", "https://example.com/goods"),
        )
        val attempts = mutableListOf<Intent>()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) { attempts.add(intent) }
        }
        bad.forEach {
            assertTrue(QqShoppingProductLinkOpener.candidates(it).isEmpty())
            assertEquals(QqShoppingProductLinkOpener.Result.FAILED,
                QqShoppingProductLinkOpener.open(context, it))
        }
        assertTrue(attempts.isEmpty())
    }

    @Test fun noAppOrSystemHandlerReportsFailureRatherThanPretendingItOpened() {
        val attempts = mutableListOf<String?>()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) {
                attempts.add(intent.`package`)
                throw ActivityNotFoundException("not installed")
            }
        }
        assertEquals(QqShoppingProductLinkOpener.Result.FAILED,
            QqShoppingProductLinkOpener.open(
                context, product("淘宝", "https://m.tb.cn/h.abc123")))
        assertEquals(listOf("com.taobao.taobao", null), attempts)
    }
}
