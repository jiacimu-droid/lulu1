package com.jiacimu.lulu.data

import org.json.JSONObject

/**
 * Enforces a real two-stage boundary for salient social events.
 *
 * The first (appraisal) model commits emotions, thoughts and motives. A second
 * model can choose an action but cannot quietly rewrite its own supposed cause
 * after seeing which action would be convenient to take.
 */
internal object CharacterCausalActionPolicy {
    private val privateFields = listOf(
        "innerLife", "innerThought", "inner_voice", "innerThoughtBasis",
        "afterglow", "mood", "intention",
    )

    fun actionOnly(proposed: JSONObject, alreadyAppraised: Boolean): JSONObject {
        if (!alreadyAppraised) return proposed
        return JSONObject(proposed.toString()).apply {
            privateFields.forEach { remove(it) }
        }
    }

    fun followThroughInstruction(evidenceId: String): String = """
        【已经完成的私人感受阶段】
        事件证据ID=$evidenceId。你已经独立形成并保存了当时的感受、动机与没说出口的心声。
        现在只根据已保存的内心状态、个人性格、已有关系、目前实际能做的事决定行动。
        可以联系用户，也可以不联系；不能以为要发送消息就倒写一个新的担心或委屈。
        这一轮不再产生 innerLife、innerThought、mood、afterglow、intention；
        如果想让一个已存在的动机继续发挥作用，选用它的真实 motiveId。
        若选择主动行动，请在 reason 里简短说明它怎样承接已保存的情绪/动机，
        以及为什么此刻行动合适，而不是虚构用户挂断的真正原因。
        无需执行行动时选择 silent，不必杜撰关心、质问或孤独感。
    """.trimIndent()
}
