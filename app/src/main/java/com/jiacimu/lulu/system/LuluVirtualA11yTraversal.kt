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
        // Optional per-parent traversal order. Chat composers live below a
        // potentially enormous scrollable transcript: visiting children in
        // ordinary DOM order can spend all 1600 nodes before reaching the
        // real bottom editor. This is observation only, never a tap decision.
        priority: ((N) -> Int)? = null,
        inspect: (N) -> Unit,
    ): Boolean {
        var complete = true
        var visited = 0
        fun walk(node: N, depth: Int) {
            if (depth > MAX_DEPTH || visited >= MAX_NODES) {
                complete = false
                return
            }
            visited++
            if (isProtected(node)) return
            if (isVisible(node)) inspect(node)
            if (priority == null) {
                for (index in 0 until childCount(node)) {
                    if (visited >= MAX_NODES) {
                        complete = false
                        break
                    }
                    childAt(node, index)?.let { walk(it, depth + 1) }
                }
            } else {
                // The ordering does not change the global visit budget or
                // protected-subtree rule. A large history still marks this
                // generic traversal incomplete; specialized composer readers
                // prune the verified scrollable transcript independently.
                val children = (0 until childCount(node)).mapNotNull { childAt(node, it) }
                    .sortedByDescending(priority)
                for (child in children) {
                    if (visited >= MAX_NODES) {
                        complete = false
                        break
                    }
                    walk(child, depth + 1)
                }
            }
        }
        roots.forEach { walk(it, 0) }
        return complete
    }
}
