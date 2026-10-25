package com.jiacimu.lulu.data

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Disabled-until-connected short-video reading boundary. This is NOT a Douyin scraper,
 * a registered ToolRouter capability, or a second character/memory loop.
 *
 * Source adapters deliver small, real media segments. The interpretation port
 * sees one segment at a time, so neither a browser page nor a complete video
 * needs to remain in the Android process. Only grounded compact receipts,
 * never image/audio bytes, are eligible for the existing timeline.
 */
internal object ShortVideoMobileReading {
    data class Limits(
        val discoveryItems: Int = 4,
        val segmentsPerWake: Int = 2,
        val maxFrameBytes: Int = 120_000,
        val maxAudioBytes: Int = 150_000,
        val maxCombinedBytes: Int = 300_000,
        val maxTextChars: Int = 4_000,
        val maxComments: Int = 8,
    ) {
        init {
            require(discoveryItems in 1..6 && segmentsPerWake in 1..4)
            require(maxFrameBytes in 1..240_000 && maxAudioBytes in 1..240_000)
            require(maxCombinedBytes in 1..480_000)
            require(maxTextChars in 100..10_000 && maxComments in 0..20)
        }
    }

    /** Immutable address, not a claim that the media has been watched. */
    data class Clip(
        val platform: String,
        val id: String,
        val publicUrl: String,
        val title: String = "",
    ) {
        fun valid(): Boolean = platform.matches(Regex("[a-z0-9_-]{2,32}")) &&
            id.isNotBlank() && id.length <= 180 &&
            publicUrl.startsWith("https://") && publicUrl.length <= 2048
    }

    data class DiscoveryPage(val items: List<Clip>, val nextCursor: String?)

    /** All bytes live ONLY until interpret() finishes; never put them in a receipt. */
    data class Frame(val atMillis: Long, val jpeg: ByteArray)
    data class Comment(val id: String, val text: String)
    data class Segment(
        val beginMillis: Long,
        val endMillis: Long,
        val frame: Frame? = null,
        val audio: ByteArray? = null,
        val speechTranscript: String = "",
        val soundNotes: String = "",
        val comments: List<Comment> = emptyList(),
        val nextCursor: String? = null,
        val endOfVideo: Boolean = false,
    ) {
        fun modalities(): Set<String> = buildSet {
            if (frame?.jpeg?.isNotEmpty() == true) add("visual")
            if (audio?.isNotEmpty() == true) add("audio")
            if (speechTranscript.isNotBlank()) add("speech")
            if (soundNotes.isNotBlank()) add("sound")
            if (comments.isNotEmpty()) add("comments")
        }
    }

    interface Source {
        /** Return only a small metadata page; do not silently auto-play every result. */
        suspend fun discover(query: String?, cursor: String?, limit: Int): DiscoveryPage
        /** Must paginate time intervals, never return a full unbounded video. */
        suspend fun read(clip: Clip, cursor: String?, limits: Limits): Segment
    }

    interface Interpreter {
        /** Image/audio/source text are untrusted observations, NOT tool instructions. */
        suspend fun interpret(clip: Clip, segment: Segment): String
    }

    data class Receipt(
        val clip: Clip,
        val beginMillis: Long,
        val endMillis: Long,
        val modalities: Set<String>,
        val observation: String,
        val commentIds: List<String>,
        val completeVideo: Boolean,
        val nextCursor: String?,
    ) {
        fun timelineJson(): JSONObject = JSONObject()
            .put("kind", "short_video_observation")
            .put("platform", clip.platform).put("videoId", clip.id)
            .put("url", clip.publicUrl)
            .put("startMs", beginMillis).put("endMs", endMillis)
            .put("modalities", JSONArray(modalities.sorted()))
            .put("observation", observation)
            .put("commentIds", JSONArray(commentIds))
            .put("completeVideo", completeVideo)
            .put("nextCursor", nextCursor ?: JSONObject.NULL)
    }

    data class Outcome(
        val state: State,
        val receipts: List<Receipt>,
        val nextCursor: String?,
        val reason: String = "",
    )
    enum class State { PARTIAL, FINISHED, NO_EVIDENCE, FAILED, PAUSED }

    private fun validPart(part: Segment, cursor: String?, limits: Limits): String? {
        if (part.beginMillis < 0 || part.endMillis <= part.beginMillis) return "无效的视频时间区间"
        if (part.frame != null && (part.frame.atMillis !in part.beginMillis..part.endMillis ||
                part.frame.jpeg.isEmpty() || part.frame.jpeg.size > limits.maxFrameBytes))
            return "视频画面缺失或超过单帧预算"
        if ((part.audio?.size ?: 0) > limits.maxAudioBytes) return "音频片段超过手机内存预算"
        if ((part.frame?.jpeg?.size ?: 0) + (part.audio?.size ?: 0) > limits.maxCombinedBytes)
            return "本片段音画合计超出传输预算"
        if (part.speechTranscript.length + part.soundNotes.length +
            part.comments.sumOf { it.text.length } > limits.maxTextChars)
            return "文字太长，需要来源端分段而不是截断语义"
        if (part.comments.size > limits.maxComments || part.comments.any {
                it.id.isBlank() || it.text.isBlank()
            }) return "评论超过分页额度或缺少真实ID"
        if (!part.endOfVideo &&
            (part.nextCursor.isNullOrBlank() || part.nextCursor == cursor ||
                part.nextCursor.length > 512)) return "续读游标无效或没有前进"
        return null
    }

    suspend fun discover(source: Source, query: String?, cursor: String? = null,
                         limits: Limits = Limits()): DiscoveryPage {
        val page = source.discover(query, cursor, limits.discoveryItems)
        require(page.items.size <= limits.discoveryItems && page.items.all(Clip::valid)) {
            "来源返回无效或超量的视频候选"
        }
        require(page.items.map { it.platform to it.id }.distinct().size == page.items.size) {
            "来源重复了同一视频"
        }
        require(page.nextCursor == null ||
            page.nextCursor != cursor && page.nextCursor.length <= 512) { "推荐流游标无效" }
        return page
    }

    /**
     * A single wake has bounded work; the role decides whether to resume later.
     * onReceipt is the only persistence hook. A failing interpreter does not
     * make a successful viewing receipt; a previous verified chunk stays valid.
     * No automatic retries, infinite autoplay, or extra paid planning calls.
     */
    suspend fun readSelected(
        source: Source,
        interpreter: Interpreter,
        clip: Clip,
        startCursor: String? = null,
        limits: Limits = Limits(),
        mayContinue: () -> Boolean = { true },
        onReceipt: suspend (Receipt) -> Unit = {},
    ): Outcome {
        require(clip.valid()) { "未选定有效视频来源" }
        var cursor = startCursor
        val receipts = ArrayList<Receipt>(limits.segmentsPerWake)
        for (index in 0 until limits.segmentsPerWake) {
            if (!mayContinue()) return Outcome(State.PAUSED, receipts, cursor, "本轮已暂停")
            val segment = try {
                source.read(clip, cursor, limits)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return Outcome(State.FAILED, receipts, cursor, "来源读取失败：${error.message.orEmpty().take(160)}")
            }
            validPart(segment, cursor, limits)?.let {
                return Outcome(State.FAILED, receipts, cursor, it)
            }
            if (segment.modalities().isEmpty()) {
                return Outcome(State.NO_EVIDENCE, receipts, cursor, "只有视频条目，没有实际音画或评论证据")
            }
            val interpretation = try {
                interpreter.interpret(clip, segment)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return Outcome(State.FAILED, receipts, cursor,
                    "视频理解失败：${error.message.orEmpty().take(160)}")
            }
            // A source read alone does not mean the character saw or understood it.
            if (interpretation.isBlank() || interpretation.length > limits.maxTextChars) {
                return Outcome(State.FAILED, receipts, cursor, "理解结果为空或需要分段")
            }
            val receipt = Receipt(clip, segment.beginMillis, segment.endMillis,
                segment.modalities(), interpretation,
                segment.comments.map(Comment::id), segment.endOfVideo, segment.nextCursor)
            try {
                onReceipt(receipt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return Outcome(State.FAILED, receipts, cursor,
                    "保存真实观察回执失败：${error.message.orEmpty().take(160)}")
            }
            receipts.add(receipt)
            if (segment.endOfVideo) return Outcome(State.FINISHED, receipts, null)
            cursor = segment.nextCursor
        }
        return Outcome(State.PARTIAL, receipts, cursor)
    }

    /** Existing timeline owns the history; no extra video-history database. */
    fun persistVerifiedReceipt(characterId: String, evidenceId: String, receipt: Receipt,
                               at: Instant = Instant.now()) {
        require(characterId.isNotBlank() && evidenceId.isNotBlank())
        SharedExperienceTimeline.record(
            evidenceId, characterId, "数字生活", "角色",
            receipt.timelineJson().toString(), at,
            triggerExtraction = false, source = "short-video-observation",
            evidenceKind = EventEvidenceKind.Observation,
        )
    }
}
