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

    /** Mika InterfaceDiff-style matching: ephemeral e1/e2 traversal IDs
     * must NEVER be compared as if they identified the same element across
     * screens. Match actual semantic identity, position and local state.
     * Output refs are the CURRENT screen's refs only. */
    fun between(base: List<Element>?, current: List<Element>, baseAvailable: Boolean): Delta {
        if (!baseAvailable || base == null)
            return Delta(true, current, emptyList(), emptyList(), 0)
        if (base.isEmpty() && current.isNotEmpty())
            return Delta(true, current, emptyList(), emptyList(), 0)

        val old = base.toMutableList()
        val remaining = current.toMutableList()
        val changed = mutableListOf<Element>()
        var stable = 0
        fun sameName(a: Element, b: Element): Boolean =
            a.role == b.role && a.name == b.name &&
                a.resourceId == b.resourceId && a.clickable == b.clickable
        fun samePosition(a: Element, b: Element): Boolean =
            a.x == b.x && a.y == b.y
        fun matchOnce(equal: (Element, Element) -> Boolean, consumed: (Element) -> Unit) {
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                val priorIndex = old.indexOfFirst { previous -> equal(previous, next) }
                if (priorIndex >= 0) {
                    old.removeAt(priorIndex)
                    iterator.remove()
                    consumed(next)
                }
            }
        }
        // Pass 1: same control, state and location (even if ref/order changed).
        matchOnce({ a, b -> sameName(a, b) && samePosition(a, b) }) { stable++ }
        // Pass 2: same semantic node moved after scrolling or layout change.
        matchOnce(::sameName) { changed += it }
        // Pass 3: same uniquely locatable resource ID, new title at same spot.
        // Without an ID, same-size generic cards are ambiguous and stay added/removed.
        matchOnce({ a, b ->
            a.resourceId.isNotBlank() && a.resourceId == b.resourceId &&
                a.role == b.role && samePosition(a, b)
        }) { changed += it }
        val added = remaining.toList()
        val removed = old.map(Element::ref)
        val totalChanges = added.size + changed.size + removed.size
        val whole = totalChanges > (base.size + current.size).coerceAtLeast(2) * 0.6
        return if (whole) Delta(true, current, emptyList(), emptyList(), 0)
            else Delta(false, added, changed, removed, stable)
    }
}
