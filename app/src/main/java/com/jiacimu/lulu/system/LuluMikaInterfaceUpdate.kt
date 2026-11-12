/* Adapted from Mika core/uistate/InterfaceUpdate.kt (Apache-2.0).
 * Copyright 2026 wangzan101. Lulu uses its own plain Kotlin data structures
 * instead of Mika's kotlinx.serialization and model snapshot registry.
 */
package com.jiacimu.lulu.system

/** Only describes what was actually present in verified, role-owned phone UI
 * receipts. It never decides whether a real-world search or task succeeded. */
internal object LuluMikaInterfaceUpdate {
    data class Element(
        val ref: String,
        val name: String,
        val role: String,
        val resourceId: String,
        val clickable: Boolean,
        val x: Int,
        val y: Int,
    )
    data class Delta(
        val full: Boolean,
        val added: List<Element>,
        val changed: List<Element>,
        val removed: List<String>,
        val unchanged: Int,
    ) {
        fun summary(): String = when {
            full -> "新界面控件快照，请使用本轮完整控件编号"
            added.isEmpty() && changed.isEmpty() && removed.isEmpty() -> "可见控件未发生可核实变化"
            else -> buildString {
                append("界面控件差异：")
                if (added.isNotEmpty()) append("新增 ").append(added.take(6).joinToString { "${it.ref}:${it.name}" }).append("；")
                if (changed.isNotEmpty()) append("变化 ").append(changed.take(6).joinToString { "${it.ref}:${it.name}" }).append("；")
                if (removed.isNotEmpty()) append("移除 ").append(removed.take(6).joinToString()).append("；")
                append("其他未变 ").append(unchanged)
                append("。差异不是用户目标完成的证明。")
            }
        }
    }

    /** Diff is valid only while the prior inventory is actually available to
     * the role. Refs from different screens still require CURRENT snapshot ID. */
    fun between(base: List<Element>?, current: List<Element>, baseAvailable: Boolean): Delta {
        if (!baseAvailable || base == null) return Delta(true, current, emptyList(), emptyList(), 0)
        val previous = base.associateBy(Element::ref)
        val present = current.associateBy(Element::ref)
        val added = current.filter { it.ref !in previous }
        val changed = current.filter { previous[it.ref]?.let { old -> old != it } == true }
        val removed = base.map(Element::ref).filter { it !in present }
        val unchanged = current.size - added.size - changed.size
        // Large changes are clearer as a complete screen. Missing current
        // elements are not automatically "deleted" outside this observation.
        val changes = added.size + changed.size + removed.size
        val whole = changes > (base.size + current.size).coerceAtLeast(2) * 0.6
        return if (whole) Delta(true, current, emptyList(), emptyList(), 0)
            else Delta(false, added, changed, removed, unchanged)
    }
}
