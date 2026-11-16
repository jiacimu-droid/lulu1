package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluVirtualA11yTraversalTest {
    private data class Node(
        val label: String = "",
        val visible: Boolean = true,
        val protected: Boolean = false,
        val children: List<Node> = emptyList(),
    )

    private fun read(vararg roots: Node): List<String> {
        val result = mutableListOf<String>()
        LuluVirtualA11yTraversal.forEachVisible(
            roots = roots.toList(),
            childCount = { it.children.size },
            childAt = { node, i -> node.children.getOrNull(i) },
            isProtected = { it.protected },
            isVisible = { it.visible },
        ) { node ->
            if (node.label.isNotBlank()) result += node.label
        }
        return result
    }

    @Test fun chatTitleSurvivesFortyInvisibleParentLayers() {
        val title = Node(label = "文件传输助手")
        val deepToolbar = (1..40).fold(title) { child, _ ->
            Node(visible = false, children = listOf(child))
        }
        assertEquals(listOf("文件传输助手"), read(deepToolbar))
    }

    @Test fun doesNotReadOrTraverseProtectedSubtree() {
        val tree = Node(children = listOf(
            Node(label = "返回"),
            Node(protected = true, children = listOf(Node(label = "聊天密码"))),
            Node(visible = false, children = listOf(Node(label = "文件传输助手"))),
        ))
        val text = read(tree)
        assertTrue(text.contains("返回"))
        assertTrue(text.contains("文件传输助手"))
        assertFalse(text.contains("聊天密码"))
    }

    @Test fun scanLimitsDepthAndTotalNodes() {
        val beyondDepth = (1..81).fold(Node(label = "隐藏的标题")) { child, _ ->
            Node(children = listOf(child))
        }
        assertFalse(read(beyondDepth).contains("隐藏的标题"))
        val many = Node(children = (1..1800).map { Node(label = it.toString()) })
        val seen = read(many)
        assertEquals(1599, seen.size) // Root consumes one scan slot.
        assertFalse(seen.contains("1800"))
    }
}
