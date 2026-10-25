package com.jiacimu.lulu.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Exact, durable observation checkpoints. This is NOT a read receipt, tool result,
 * or replacement for the original SharedExperienceTimeline events.
 *
 * The former 64-entry SharedPreferences cache used 32-bit hashCode tokens. Keep
 * legacy tokens for a short upgrade grace period while converting encountered
 * revision-1 events; afterwards only exact (character, event, revision) keys count.
 */
internal object PerceptionStimulusLedger {
    private const val LEGACY_PREFS = "lulu_perception_stimuli"
    private const val GRACE_MS = 48L * 60 * 60 * 1000
    private var legacy: android.content.SharedPreferences? = null
    private var helper: SeenStimuliDatabase? = null

    @Synchronized fun initialize(context: Context) {
        if (helper == null) helper = SeenStimuliDatabase(context.applicationContext)
        if (legacy == null) legacy = context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
    }

    @Synchronized fun hasSeen(
        context: Context, characterId: String, evidenceId: String, revision: Long = 1L,
    ): Boolean {
        if (characterId.isBlank() || evidenceId.isBlank()) return false
        initialize(context)
        if (contains(characterId, evidenceId, revision)) return true
        // Legacy hashes are irreversible. Convert only matching revision-1
        // events encountered shortly after upgrading; never treat a hash as
        // proof that an edited revision or a fresh exact event was observed.
        if (revision == 1L && legacyContains(characterId, evidenceId)) {
            insert(characterId, evidenceId, revision)
            return true
        }
        return false
    }

    @Synchronized fun claim(context: Context, characterId: String, stimulus: PerceptionStimulus): Boolean {
        if (characterId.isBlank() || stimulus.evidenceId.isBlank()) return false
        if (hasSeen(context, characterId, stimulus.evidenceId, stimulus.revision)) return false
        return insert(characterId, stimulus.evidenceId, stimulus.revision)
    }

    @Synchronized fun invalidate(eventId: String) {
        if (eventId.isBlank()) return
        val ids = setOf(eventId, eventId.substringBefore(":group:"))
        helper?.writableDatabase?.let { db ->
            ids.forEach { id -> db.delete("observed_stimuli", "evidence_id=?", arrayOf(id)) }
        }
        // Clear the old hashed entries too; deleting a source must not let the
        // migration bridge re-mark its replacement as already observed.
        val tokens = ids.map { it.hashCode().toString() }.toSet()
        val p = legacy ?: return
        p.all.keys.filter { it.startsWith("seen:") }.forEach { key ->
            val old = p.getString(key, "").orEmpty()
            val rest = old.lineSequence().filter { it.isNotBlank() && it !in tokens }.joinToString("\n")
            if (rest != old) p.edit().putString(key, rest).commit()
        }
    }

    @Synchronized fun clear(characterId: String) {
        if (characterId.isBlank()) return
        helper?.writableDatabase?.delete("observed_stimuli", "character_id=?", arrayOf(characterId))
        legacy?.edit()?.remove("seen:$characterId")?.commit()
    }

    private fun contains(role: String, evidenceId: String, revision: Long): Boolean {
        val db = checkNotNull(helper).readableDatabase
        db.rawQuery(
            "SELECT 1 FROM observed_stimuli WHERE character_id=? AND evidence_id=? AND revision=? LIMIT 1",
            arrayOf(role, evidenceId, revision.toString())
        ).use { return it.moveToFirst() }
    }

    private fun insert(role: String, evidenceId: String, revision: Long): Boolean {
        val values = ContentValues().apply {
            put("character_id", role)
            put("evidence_id", evidenceId)
            put("revision", revision)
        }
        return checkNotNull(helper).writableDatabase.insertWithOnConflict(
            "observed_stimuli", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    private fun legacyContains(role: String, evidenceId: String): Boolean {
        val p = legacy ?: return false
        var until = p.getLong("migration_expires_at", 0L)
        if (until == 0L) {
            until = System.currentTimeMillis() + GRACE_MS
            check(p.edit().putLong("migration_expires_at", until).commit())
        }
        if (System.currentTimeMillis() >= until) return false
        val token = evidenceId.hashCode().toString()
        return p.getString("seen:$role", "").orEmpty().lineSequence().any { it == token }
    }

    private class SeenStimuliDatabase(context: Context) :
        SQLiteOpenHelper(context, "lulu_observed_stimuli.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE observed_stimuli (
                    character_id TEXT NOT NULL,
                    evidence_id TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    PRIMARY KEY (character_id, evidence_id, revision)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX observed_stimuli_by_event ON observed_stimuli(evidence_id)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
