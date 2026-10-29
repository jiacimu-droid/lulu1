package com.jiacimu.lulu.data

import android.content.Context
import android.content.SharedPreferences
import com.jiacimu.lulu.LuluRepositories
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real mailbox delivery, persistence and participant evidence, without model calls. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ResidentMailDeliveryAtomicityTest {
    private lateinit var app: Context
    private lateinit var actualPrefs: SharedPreferences
    private val savedFields = mutableMapOf<String, Any?>()
    private val roles = mutableListOf<String>()
    private fun field(name: String) = ResidentSocialMailbox::class.java
        .getDeclaredField(name).apply { isAccessible = true }

    @Before fun initialize() {
        MemoryTestEnvironment.release()
        app = RuntimeEnvironment.getApplication()
        for (name in listOf("prefs", "appContext", "messages"))
            savedFields[name] = field(name).get(ResidentSocialMailbox)
        field("prefs").set(ResidentSocialMailbox, null)
        LuluRepositories.initialize(app)
        MigratedDomainStores.initialize(app)
        SharedExperienceTimeline.initialize(app)
        DigitalLifeProfileStore.initialize(app)
        DigitalWorldStore.initialize(app)
        MomentFriendAccess.initialize(app)
        ResidentSocialMailbox.initialize(app)
        actualPrefs = field("prefs").get(ResidentSocialMailbox) as SharedPreferences
        // Scheduling is unrelated to the persistence race and not invoked here.
        field("appContext").set(ResidentSocialMailbox, null)
    }

    @After fun restore() {
        for (a in roles) for (b in roles) if (a != b) MomentFriendAccess.setFriends(a, b, false)
        for ((name, value) in savedFields) field(name).set(ResidentSocialMailbox, value)
        MemoryTestEnvironment.release()
    }

    private fun person(digital: Boolean = true): String {
        val role = MigratedDomainStores.characters.create("邮件归属-${UUID.randomUUID()}")
        if (digital) DigitalLifeProfileStore.registerNewLife(role.characterId,
            role.displayName, "数字生命", Instant.now().minusSeconds(60))
        roles += role.characterId
        return role.characterId
    }

    /** Intercept only the mailbox commit; all actual Android preferences stay real. */
    private fun interceptCommit(commit: () -> Boolean) {
        val wrapped = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name != "edit") method.invoke(actualPrefs, *(args ?: emptyArray()))
            else {
                val editor = actualPrefs.edit()
                lateinit var editorProxy: SharedPreferences.Editor
                editorProxy = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { _, editMethod, editArgs ->
                    when (editMethod.name) {
                        "commit" -> if (commit()) editor.commit() else false
                        else -> editMethod.invoke(editor, *(editArgs ?: emptyArray())).let {
                            if (it === editor) editorProxy else it
                        }
                    }
                } as SharedPreferences.Editor
                editorProxy
            }
        } as SharedPreferences
        field("prefs").set(ResidentSocialMailbox, wrapped)
    }

    @Test(timeout = 20000) fun concurrentSendersKeepBothLettersEvidenceAndUnreadStateAcrossReload() {
        val first = person()
        val second = person()
        val receiver = person()
        val outsider = person()
        assertTrue(MomentFriendAccess.setFriends(first, receiver, true))
        assertTrue(MomentFriendAccess.setFriends(second, receiver, true))
        val firstAtCommit = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAttempting = CountDownLatch(1)
        val secondAtCommit = CountDownLatch(1)
        val commits = AtomicInteger()
        interceptCommit {
            if (commits.incrementAndGet() == 1) {
                firstAtCommit.countDown()
                check(releaseFirst.await(8, TimeUnit.SECONDS))
            } else secondAtCommit.countDown()
            true
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<ResidentDirectMessage> {
                ResidentSocialMailbox.send(first, receiver, "第一位朋友的旧话题")
            }
            assertTrue(firstAtCommit.await(5, TimeUnit.SECONDS))
            val b = pool.submit<ResidentDirectMessage> {
                secondAttempting.countDown()
                ResidentSocialMailbox.send(second, receiver, "另一位朋友的邀约")
            }
            assertTrue(secondAttempting.await(5, TimeUnit.SECONDS))
            // Old send() had no monitor: B saved a stale messages snapshot here,
            // then A overwrote it when released, although BOTH calls succeeded.
            assertFalse("A delivery must finish before B can overwrite its snapshot",
                secondAtCommit.await(250, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            val one = a.get(8, TimeUnit.SECONDS)
            val two = b.get(8, TimeUnit.SECONDS)
            assertEquals(2, commits.get())
            val ids = setOf(one.id, two.id)
            val stored = JSONArray(actualPrefs.getString("messages", "[]"))
            val storedIds = (0 until stored.length()).map { stored.getJSONObject(it).getString("id") }.toSet()
            assertTrue(storedIds.containsAll(ids))
            assertTrue(ResidentSocialMailbox.pendingIncoming(receiver, 100).map { it.id }.containsAll(ids))
            for (mail in listOf(one, two)) {
                for (viewer in listOf(mail.fromId, receiver)) {
                    val evidence = SharedExperienceTimeline.eventsByIds(viewer,
                        listOf(ResidentSocialMailbox.evidenceId(mail, viewer))).single()
                    assertEquals(mail.content, ResidentSocialMailbox.witnessedMessage(evidence)?.content)
                }
            }
            assertFalse(ResidentSocialMailbox.historyForInspection(outsider).any { it.id in ids })
            // Reopen the same durable mailbox rather than trusting the live list.
            field("prefs").set(ResidentSocialMailbox, null)
            ResidentSocialMailbox.initialize(app)
            field("appContext").set(ResidentSocialMailbox, null)
            assertTrue(ResidentSocialMailbox.pendingIncoming(receiver, 100).map { it.id }.containsAll(ids))
            assertTrue(ResidentSocialMailbox.acknowledgeIncoming(receiver, one.id))
            assertFalse(ResidentSocialMailbox.pendingIncoming(receiver, 100).any { it.id == one.id })
            assertTrue(ResidentSocialMailbox.pendingIncoming(receiver, 100).any { it.id == two.id })
        } finally {
            releaseFirst.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(8, TimeUnit.SECONDS))
            field("prefs").set(ResidentSocialMailbox, actualPrefs)
        }
    }

    @Test fun failedPersistenceDoesNotInventDeliveryOrReciprocalAcquaintance() {
        val sender = person()
        val receiver = person()
        assertTrue(MomentFriendAccess.setFriends(sender, receiver, true))
        val before = actualPrefs.getString("messages", "[]")
        val receiverBefore = ResidentSocialMailbox.historyForInspection(receiver)
        interceptCommit { false }
        try {
            assertTrue(runCatching { ResidentSocialMailbox.send(sender, receiver, "未保存的消息") }.isFailure)
            assertEquals(before, actualPrefs.getString("messages", "[]"))
            assertEquals(receiverBefore, ResidentSocialMailbox.historyForInspection(receiver))
            assertFalse(SharedExperienceTimeline.all(receiver).any { it.content.contains("未保存的消息") })
        } finally {
            field("prefs").set(ResidentSocialMailbox, actualPrefs)
        }
    }

    @Test fun unknownSendersAndUnresolvedRealityLabelsCannotCreateAFirstContact() {
        val first = person(digital = false)
        val second = person(digital = false)
        assertEquals("现实世界", DigitalWorldStore.locationOf(first))
        assertEquals("现实世界", DigitalWorldStore.locationOf(second))
        assertFalse(ResidentSocialMailbox.canContact(first, second))
        assertFalse(ResidentSocialMailbox.canContact("missing-${UUID.randomUUID()}", second))
        val before = actualPrefs.getString("messages", "[]")
        assertTrue(runCatching { ResidentSocialMailbox.send(first, second, "假装同场认识") }.isFailure)
        assertEquals(before, actualPrefs.getString("messages", "[]"))
        // The guard is about evidence, not a ban on independent friendships.
        assertTrue(MomentFriendAccess.setFriends(first, second, true))
        assertTrue(ResidentSocialMailbox.canContact(first, second))
        val actual = ResidentSocialMailbox.send(first, second, "双方确实确认好友后可以联系")
        assertEquals(actual.id, ResidentSocialMailbox.pendingIncoming(second).single().id)
    }
}
