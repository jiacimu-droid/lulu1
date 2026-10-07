package com.jiacimu.lulu.data

import android.content.Context
import androidx.work.*
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Notifications are external evidence, never authorization; opt-in packages only. */
object ImportantEventBridge {
    @Synchronized
    fun notification(context: Context, key: String, packageName: String, content: String, occurredAt: Instant) {
        val p = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        val roles = p.all.filter { it.key.endsWith(":notification_packages") && it.value is String }
        roles.forEach { (setting, value) ->
            val allowed = (value as String).split(',', '，', '\n').map(String::trim).filter(String::isNotBlank)
            if (packageName !in allowed) return@forEach
            val id = setting.removeSuffix(":notification_packages")
            val seen = "seen:$id:$key"
            if (p.getLong(seen, 0) >= occurredAt.toEpochMilli()) return@forEach
            p.edit().putLong(seen, occurredAt.toEpochMilli()).commit()
            initializeBackgroundRuntime(context)
            SharedExperienceTimeline.record("notification-$id-${key.hashCode()}-${occurredAt.toEpochMilli()}", id,
                "重要通知", packageName, content.take(2_000), occurredAt,
                source = "external-notification", evidenceKind = EventEvidenceKind.Observation)
            wake(context, id, "重要事件·用户选择的通知")
        }
    }

    @Synchronized
    fun wake(context: Context, characterId: String, reason: String) {
        val p = context.getSharedPreferences("lulu_event_rate_limit", Context.MODE_PRIVATE)
        val last = p.getLong(characterId, 0)
        val delay = maxOf(30_000L, last + 600_000L - System.currentTimeMillis())
        p.edit().putLong(characterId, System.currentTimeMillis() + delay).commit()
        val request = OneTimeWorkRequestBuilder<ProactivePerceptionWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString("characterId", characterId).putString("trigger", reason).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("lulu-important-event-$characterId", ExistingWorkPolicy.KEEP, request)
    }
}
