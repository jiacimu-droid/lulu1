package com.jiacimu.lulu.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import com.jiacimu.lulu.ai.ScopedModelSelections
import java.util.concurrent.TimeUnit

/** Literary work follows an already committed fact; it never holds the live reply lock. */
class WorldLifeEpisodeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        initializeBackgroundRuntime(applicationContext)
        ScopedModelSelections.initialize(applicationContext)
        val role = inputData.getString("characterId") ?: return Result.failure()
        val location = inputData.getString("location") ?: return Result.failure()
        return writers.withLock(role) {
            val id = inputData.getString("evidenceId") ?: return@withLock Result.failure()
            val source = SharedExperienceTimeline.eventsByIds(role, listOf(id)).firstOrNull()
                ?: return@withLock Result.success()
            val category = inputData.getString("category").orEmpty().ifBlank { "outing" }
            val sourceIds = (listOf(id) + inputData.getStringArray("sourceEvidenceIds").orEmpty())
                .distinct().take(16)
            val verified = SharedExperienceTimeline.eventsByIds(role, sourceIds)
            if (verified.size != sourceIds.size) return@withLock Result.success()
            if (source.revision != inputData.getLong("revision", -1L)) return@withLock Result.success()
            if (WorldLifeEpisodeStore.episodes(role).any { it.evidenceId == id } ||
                (category == "outing" &&
                    WorldLifeEpisodeStore.nearbyAt(role, location, source.occurredAt) != null))
                return@withLock Result.success()
            val composition = try {
                WorldLifeEpisodeComposer.compose(role, location, source.content,
                    inputData.getString("title").orEmpty(), inputData.getString("seed").orEmpty(),
                    witnessedItems = inputData.getString("items").orEmpty(),
                    verifiedEvents = verified, category = category,
                    experiencedContext = decodeExperiencedContext(inputData.getByteArray("experiencedContextGzip")))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                WorldLifeComposeOutcome(failureReason = error.message.orEmpty().take(55))
            }
            // Source deletion, editing or an old outstanding job must never
            // publish a ghost scene or a bogus failure message.
            if (SharedExperienceTimeline.eventsByIds(role, sourceIds).toSet() != verified.toSet())
                return@withLock Result.success()
            if (composition.draft == null) {
                com.jiacimu.lulu.LuluRepositories.performance.recordError(
                    source = "生活小剧场",
                    title = "生活片段补写 · 第${runAttemptCount + 1}次",
                    message = composition.failureReason.take(160).ifBlank { "没有得到完整正文" },
                )
                // The former Result.success() permanently discarded every
                // interrupted/failed novel; keep the same original receipt
                // and try it again later, without executing a second world action.
                if (runAttemptCount < 2 && composition.retryable) return@withLock Result.retry()
                // One concise final notice only after retry exhaustion, rather
                // than a string of failure notices inside the character chat.
                MigratedDomainStores.chat.appendPrivateActivityNotice(
                    role, "生活小剧场暂未补写成功 · 真实经历已保存，可查看模型请求日志")
                return@withLock Result.success()
            }
            val draft = composition.draft
            val episode = try {
                WorldLifeEpisodeStore.record(role, location, draft.title, draft.scene,
                    source.content, id, source.occurredAt, draft.unresolvedThread,
                    expectedSource = source, sourceEvidenceIds = sourceIds, category = category)
            } catch (error: Exception) {
                com.jiacimu.lulu.LuluRepositories.performance.recordError(
                    source = "生活小剧场", title = "生活片段持久化失败",
                    message = error.message.orEmpty().take(140))
                if (runAttemptCount < 2) return@withLock Result.retry()
                return@withLock Result.failure()
            }
            if (episode != null &&
                SharedExperienceTimeline.eventsByIds(role, listOf(id)).firstOrNull() == source) {
                val name = MigratedDomainStores.characters.get(role).displayName
                MigratedDomainStores.chat.appendPrivateActivityNotice(role,
                    "[独处片段|${episode.id}] ${name}的《${episode.title}》 · 查看生活小剧场")
            }
            Result.success()
        }
    }

    companion object {
        private val writers = KeyedWorkMutex()

        internal fun encodeExperiencedContext(text: String): ByteArray =
            java.io.ByteArrayOutputStream().use { buffer ->
                java.util.zip.GZIPOutputStream(buffer).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                buffer.toByteArray()
            }

        internal fun decodeExperiencedContext(bytes: ByteArray?): String =
            if (bytes == null) "" else runCatching {
                java.util.zip.GZIPInputStream(bytes.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrDefault("")

        internal fun enqueue(context: Context, characterId: String, location: String,
            evidenceId: String, title: String, seed: String,
            sourceEvidenceIds: List<String> = listOf(evidenceId), category: String = "outing") {
            val source = SharedExperienceTimeline.eventsByIds(characterId, listOf(evidenceId)).firstOrNull() ?: return
            val items = DigitalWorldStore.itemsAtLocation(characterId).take(9)
                .joinToString("；") { "${it.name}（${it.position.take(65)}）" }.take(900)
            val snapshot = encodeExperiencedContext(WorldLifeEpisodeComposer.captureExperiencedContext(characterId))
            val payload = Data.Builder().putString("characterId", characterId)
                .putString("location", location).putString("evidenceId", evidenceId)
                .putLong("revision", source.revision).putString("items", items)
                .putStringArray("sourceEvidenceIds", (listOf(evidenceId) + sourceEvidenceIds)
                    .distinct().take(16).toTypedArray()).putString("category", category)
                .putString("title", title.take(70)).putString("seed", seed.take(500))
            // WorkManager Data has a platform 10KB serialized limit. Preserve
            // the factual task if its optional snapshot cannot fit; the writer
            // explicitly treats missing historical psychology as unknown.
            val factsOnly = payload.build()
            val input = runCatching { payload.putByteArray("experiencedContextGzip", snapshot).build() }
                .getOrElse {
                    android.util.Log.w("WorldLifeEpisodeWorker",
                        "Psychology snapshot exceeds WorkManager payload; factual task preserved")
                    factsOnly
                }
            val request = OneTimeWorkRequestBuilder<WorldLifeEpisodeWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
                .setInputData(input)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "lulu-world-prose-$characterId-$evidenceId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
