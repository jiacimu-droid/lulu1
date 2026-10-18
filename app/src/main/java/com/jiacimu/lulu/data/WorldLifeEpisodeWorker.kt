package com.jiacimu.lulu.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/** Literary work follows an already committed fact; it never holds the live reply lock. */
class WorldLifeEpisodeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        initializeBackgroundRuntime(applicationContext)
        val role = inputData.getString("characterId") ?: return Result.failure()
        val location = inputData.getString("location") ?: return Result.failure()
        return writers.withLock(role) {
            val id = inputData.getString("evidenceId") ?: return@withLock Result.failure()
            val source = SharedExperienceTimeline.eventsByIds(role, listOf(id)).firstOrNull()
                ?: return@withLock Result.success()
            if (source.revision != inputData.getLong("revision", -1L)) return@withLock Result.success()
            if (WorldLifeEpisodeStore.episodes(role).any { it.evidenceId == id } ||
                WorldLifeEpisodeStore.recentAt(role, location, source.occurredAt) != null)
                return@withLock Result.success()
            val composition = try {
                WorldLifeEpisodeComposer.compose(role, location, source.content,
                    inputData.getString("title").orEmpty(), inputData.getString("seed").orEmpty(),
                    witnessedItems = inputData.getString("items").orEmpty())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                WorldLifeComposeOutcome(failureReason = error.message.orEmpty().take(55))
            }
            val episode = composition.draft?.let { draft ->
                WorldLifeEpisodeStore.record(role, location, draft.title, draft.scene,
                    source.content, id, source.occurredAt, draft.unresolvedThread, expectedSource = source)
            }
            // A removed/edited fact has no late prose or misleading failure notice.
            if (SharedExperienceTimeline.eventsByIds(role, listOf(id)).firstOrNull() != source)
                return@withLock Result.success()
            if (episode != null) {
                val name = MigratedDomainStores.characters.get(role).displayName
                MigratedDomainStores.chat.appendPrivateActivityNotice(role,
                    "[独处片段|${episode.id}] ${name}的《${episode.title}》 · 查看片段")
            } else if (composition.draft == null) {
                MigratedDomainStores.chat.appendPrivateActivityNotice(role,
                    "${source.content.take(180)} · 独处片段未完成（${composition.failureReason.take(55).ifBlank { "正文未完成" }}）")
            }
            Result.success()
        }
    }

    companion object {
        private val writers = KeyedWorkMutex()

        internal fun enqueue(context: Context, characterId: String, location: String,
            evidenceId: String, title: String, seed: String) {
            val source = SharedExperienceTimeline.eventsByIds(characterId, listOf(evidenceId)).firstOrNull() ?: return
            val items = DigitalWorldStore.itemsAtLocation(characterId).take(9)
                .joinToString("；") { "${it.name}（${it.position.take(65)}）" }.take(900)
            val request = OneTimeWorkRequestBuilder<WorldLifeEpisodeWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(Data.Builder().putString("characterId", characterId)
                    .putString("location", location).putString("evidenceId", evidenceId)
                    .putLong("revision", source.revision).putString("items", items)
                    .putString("title", title.take(70)).putString("seed", seed.take(500)).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "lulu-world-prose-$characterId-$location", ExistingWorkPolicy.KEEP, request)
        }
    }
}
