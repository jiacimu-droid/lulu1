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
import com.jiacimu.lulu.ai.VisionModelService
import java.time.Instant
import java.util.concurrent.TimeUnit

/** This is a user-initiated profile-image observation, not screen capture permission. */
internal object UserAvatarVision {
    private const val WORK_NAME = "lulu-user-avatar-vision"
    private const val PROFILE_PREFS = "lulu_user_profile"
    private const val KEY_REVISION = "avatar_vision_revision_v1"
    private const val KEY_COMPLETED = "avatar_vision_completed_revision_v1"
    private const val INPUT_URI = "avatarUri"
    private const val INPUT_REVISION = "revision"

    internal fun stillCurrent(savedUri: String?, savedRevision: String?,
        requestedUri: String?, requestedRevision: String?): Boolean =
        !requestedUri.isNullOrBlank() && !requestedRevision.isNullOrBlank() &&
            savedUri == requestedUri && savedRevision == requestedRevision

    internal fun successEvidence(description: String): String? =
        description.trim().take(1_200).takeIf(String::isNotBlank)?.let {
            "视觉模型已实际读取用户刚更新的头像。画面描述：$it"
        }

    internal fun failureEvidence(reason: String): String =
        "本次用户头像识图未成功（$reason），目前没有可核实的新头像画面描述；这不代表角色永久不具备视觉能力。"

    /**
     * Called only after the profile was successfully saved and its change event
     * written. A new revision cancels superseded observations, including
     * replacing an avatar with no image; no screenshot permission is needed.
     */
    fun onAvatarSaved(context: Context, avatarUri: String?, revision: String) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
        if (revision.isBlank() || !prefs.edit().putString(KEY_REVISION, revision).commit()) return
        val scheduled = runCatching {
            val manager = WorkManager.getInstance(app)
            if (avatarUri.isNullOrBlank()) {
                manager.cancelUniqueWork(WORK_NAME)
            } else {
                val request = OneTimeWorkRequestBuilder<UserAvatarVisionWorker>()
                    .setConstraints(Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .setInputData(Data.Builder()
                        .putString(INPUT_URI, avatarUri)
                        .putString(INPUT_REVISION, revision).build())
                    .build()
                manager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            }
        }
        if (scheduled.isFailure && !avatarUri.isNullOrBlank()) {
            recordEvidence(app, revision,
                failureEvidence("后台识图任务未能排入队列"))
        }
    }

    internal fun isCurrent(context: Context, avatarUri: String, revision: String): Boolean {
        val prefs = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
        return stillCurrent(prefs.getString("avatar_uri", null),
            prefs.getString(KEY_REVISION, null), avatarUri, revision)
    }

    internal fun isCompleted(context: Context, revision: String): Boolean =
        context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COMPLETED, null) == revision

    internal fun markCompleted(context: Context, revision: String) {
        context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_COMPLETED, revision).commit()
    }

    internal fun recordEvidence(context: Context, revision: String, content: String) {
        val at = Instant.now()
        // Use the SAME factual timeline as calls, conversations and autonomous
        // perception; never insert a guessed visual description into memories.
        MigratedDomainStores.characters.settings.value.keys.forEach { roleId ->
            if (!isRevisionCurrent(context, revision)) return
            SharedExperienceTimeline.record(
                eventId = "user-profile-avatar-vision-" + revision + "-" + roleId,
                characterId = roleId,
                channel = "用户资料",
                speaker = "头像视觉观察",
                content = content,
                occurredAt = at,
                triggerExtraction = false,
                source = "user-profile-avatar-vision",
                evidenceKind = EventEvidenceKind.Observation,
            )
        }
    }

    private fun isRevisionCurrent(context: Context, revision: String): Boolean =
        context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REVISION, null) == revision
}

/** Survives leaving the profile page, app process death and network interruptions. */
class UserAvatarVisionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uri = inputData.getString("avatarUri").orEmpty()
        val revision = inputData.getString("revision").orEmpty()
        if (!UserAvatarVision.isCurrent(applicationContext, uri, revision) ||
            UserAvatarVision.isCompleted(applicationContext, revision)) return Result.success()

        val observation = runCatching {
            initializeBackgroundRuntime(applicationContext)
            VisionModelService.describeImage(applicationContext, uri).getOrThrow()
        }
        // A rapid second profile edit must never let the old result become
        // current evidence, regardless of whether its API call succeeded.
        if (!UserAvatarVision.isCurrent(applicationContext, uri, revision)) return Result.success()
        val evidence = observation.getOrNull()
            ?.let(UserAvatarVision::successEvidence)
        if (evidence != null) {
            UserAvatarVision.recordEvidence(applicationContext, revision, evidence)
            UserAvatarVision.markCompleted(applicationContext, revision)
            return Result.success()
        }
        if (runAttemptCount < 2) return Result.retry()

        val error = observation.exceptionOrNull()?.message.orEmpty()
        val reason = when {
            error.contains("没有可用的识图模型") || error.contains("尚未配置") ->
                "尚未配置可用的视觉模型"
            error.contains("没有可读取的图片") || error.contains("读取") && error.contains("失败") ->
                "头像图片无法读取"
            else -> "视觉服务请求失败"
        }
        runCatching {
            initializeBackgroundRuntime(applicationContext)
            if (UserAvatarVision.isCurrent(applicationContext, uri, revision)) {
                UserAvatarVision.recordEvidence(applicationContext, revision,
                    UserAvatarVision.failureEvidence(reason))
                UserAvatarVision.markCompleted(applicationContext, revision)
            }
        }.getOrElse { return Result.retry() }
        return Result.success()
    }
}
