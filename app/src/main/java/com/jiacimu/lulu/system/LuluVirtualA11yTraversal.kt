package com.jiacimu.lulu.system

/**
 * Bounded Mika-style walk over a virtual display's accessibility hierarchy.
 *
 * An invisible layout-only ancestor is NOT proof that its children are hidden.
 * The only subtree pruned outright is a protected/password node. Callers may
 * inspect visible children but may never treat an invisible ancestor as a
 * click target or as recipient evidence.
 */
internal object LuluVirtualA11yTraversal {
    private const val MAX_DEPTH = 80
    private const val MAX_NODES = 1600

    fun <N : Any> forEachVisible(
        roots: List<N>,
        childCount: (N) -> Int,
        childAt: (N, Int) -> N?,
        isProtected: (N) -> Boolean,
        isVisible: (N) -> Boolean,
        inspect: (N) -> Unit,
    ) {
        var visited = 0
        fun walk(node: N, depth: Int) {
            if (depth > MAX_DEPTH || visited >= MAX_NODES) return
            visited++
            if (isProtected(node)) return
            if (isVisible(node)) inspect(node)
            for (index in 0 until childCount(node)) {
                if (visited >= MAX_NODES) break
                childAt(node, index)?.let { walk(it, depth + 1) }
            }
        }
        roots.forEach { root ->
            if (visited < MAX_NODES) walk(root, 0)
        }
    }
}
