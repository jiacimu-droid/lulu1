package com.jiacimu.lulu.data

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JSONObject needs Robolectric rather than Android's unmocked android.jar stub. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OnlineExpiryUnfinishedWorkTest {
    private val now = Instant.parse("2026-11-12T04:46:00Z")

    private fun receipt(tool: String, status: String, secondsAgo: Long,
                        message: String = "后台未打开应用"): SharedTimelineEvent {
        val execution = JSONObject()
            .put("tool", tool).put("requestId", "step-${secondsAgo}")
            .put("status", status)
            .put("result", JSONObject().put("success", status == "succeeded")
                .put("status", status).put("error", message).toString())
        return SharedTimelineEvent(
            id = "tool-test-${secondsAgo}", characterId = "jiang-du",
            channel = "实际工具执行", speaker = "执行器",
            content = execution.toString(), occurredAt = now.minusSeconds(secondsAgo),
            source = "tool-router", evidenceKind = EventEvidenceKind.ToolResult,
        )
    }

    @Test fun expirySeesTheLastRealFailedPhoneOperation() {
        val reminder = OnlineExpiryUnfinishedWork.render(listOf(
            receipt("background_phone", "failed", 50, "小红书输入失败"),
        ), now)
        assertTrue(reminder.contains("background_phone"))
        assertTrue(reminder.contains("小红书输入失败"))
        assertTrue(reminder.contains("未完成"))
        assertTrue(reminder.contains("本人选择"))
    }

    @Test fun laterSuccessForSameRequestSupersedesItsFailure() {
        val failed = receipt("background_phone", "failed", 80)
        val success = receipt("background_phone", "succeeded", 20)
            .copy(content = JSONObject(receipt("background_phone", "succeeded", 20).content)
                .put("requestId", "step-80").toString())
        val reminder = OnlineExpiryUnfinishedWork.render(listOf(failed, success), now)
        assertTrue(reminder.isBlank())
    }

    @Test fun successForDifferentPhoneRequestDoesNotEraseUnfinishedWork() {
        val reminder = OnlineExpiryUnfinishedWork.render(listOf(
            receipt("background_phone", "failed", 80, "微信发送失败"),
            receipt("background_phone", "succeeded", 20, "另一个页面已打开"),
        ), now)
        assertTrue(reminder.contains("微信发送失败"))
        assertTrue(reminder.contains("requestId=step-80"))
        assertFalse(reminder.contains("另一个页面已打开"))
    }

    @Test fun separateFailedPhoneRequestsBothReachTheSameDecision() {
        val reminder = OnlineExpiryUnfinishedWork.render(listOf(
            receipt("background_phone", "failed", 80, "微信未发送"),
            receipt("background_phone", "unverified", 20, "购物页面未确认"),
        ), now)
        assertTrue(reminder.contains("微信未发送"))
        assertTrue(reminder.contains("购物页面未确认"))
    }

    @Test fun nestedExecutionFailureStatusIsReportedInsteadOfAcceptedEnvelope() {
        val accepted = receipt("background_phone", "succeeded", 20)
        val root = JSONObject(accepted.content)
        root.put("result", JSONObject()
            .put("status", "unverified")
            .put("verification", "窗口未确认").toString())
        val reminder = OnlineExpiryUnfinishedWork.render(
            listOf(accepted.copy(content = root.toString())), now)
        assertTrue(reminder.contains("状态=unverified"))
        assertTrue(reminder.contains("窗口未确认"))
        assertFalse(reminder.contains("状态=succeeded"))
    }

    @Test fun nestedSuccessSupersedesFailedEnvelopeForSameExecution() {
        val failedEnvelope = receipt("background_phone", "failed", 20, "旧外层失败")
        val root = JSONObject(failedEnvelope.content)
        root.put("result", JSONObject()
            .put("status", "succeeded")
            .put("summary", "目标窗口已确认").toString())
        val reminder = OnlineExpiryUnfinishedWork.render(
            listOf(failedEnvelope.copy(content = root.toString())), now)
        assertTrue(reminder.isBlank())
    }

    @Test fun nestedPendingRemainsUnfinishedDespiteSuccessfulEnvelope() {
        val accepted = receipt("background_phone", "succeeded", 20)
        val root = JSONObject(accepted.content)
        root.put("result", JSONObject()
            .put("status", "pending")
            .put("summary", "等待真实执行回执").toString())
        val reminder = OnlineExpiryUnfinishedWork.render(
            listOf(accepted.copy(content = root.toString())), now)
        assertTrue(reminder.contains("状态=pending"))
        assertFalse(reminder.contains("状态=succeeded"))
    }

    @Test fun statuslessLaterReceiptCannotEraseEarlierFailureForSameRequest() {
        val failure = receipt("background_phone", "failed", 80, "微信发送失败")
        val later = JSONObject(receipt("background_phone", "succeeded", 20).content)
            .put("requestId", "step-80")
            .remove("status")
        later.put("result", JSONObject().put("summary", "尚无执行结果").toString())
        val statusless = receipt("background_phone", "succeeded", 20)
            .copy(content = later.toString())
        val reminder = OnlineExpiryUnfinishedWork.render(listOf(failure, statusless), now)
        assertTrue(reminder.contains("微信发送失败"))
        assertTrue(reminder.contains("状态=failed"))
    }

    @Test fun rejectedToolGateReasonSurvivesWithoutNestedError() {
        val rejected = receipt("background_phone", "waiting_user", 25)
        val root = JSONObject(rejected.content)
            .put("stage", "rejected")
            .put("reason", "独立屏幕授权已撤销")
            .put("result", JSONObject().put("status", "waiting_user").toString())
        val reminder = OnlineExpiryUnfinishedWork.render(
            listOf(rejected.copy(content = root.toString())), now)
        assertTrue(reminder.contains("状态=waiting_user"))
        assertTrue(reminder.contains("真实阻碍=独立屏幕授权已撤销"))
    }

    @Test fun unrelatedOrStaleEventsDoNotReviveOldFailures() {
        val badSource = receipt("background_phone", "failed", 50)
            .copy(source = "legacy")
        val tooOld = receipt("background_phone", "failed", 1800)
        val userEvent = receipt("background_phone", "failed", 30)
            .copy(evidenceKind = EventEvidenceKind.UserStatement)
        val unrelated = receipt("send_private_message", "failed", 10)
        assertTrue(OnlineExpiryUnfinishedWork.render(
            listOf(badSource, tooOld, userEvent, unrelated), now).isBlank())
    }

    @Test fun unverifiedObservationIsNotProvenSuccess() {
        val reminder = OnlineExpiryUnfinishedWork.render(listOf(
            receipt("background_phone", "unverified", 20, "目标窗口尚无法核实"),
        ), now)
        assertTrue(reminder.contains("目标窗口尚无法核实"))
        assertFalse(reminder.contains("任务已完成"))
    }
}
