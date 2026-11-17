package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluVirtualA11yTraversalTest {
    private data class Node(
        val label: String = "",
        val visible: Boolean = true,
        val isProtected: Boolean = false,
        val children: List<Node> = emptyList(),
    )

    private fun read(vararg roots: Node): List<String> {
        val result = mutableListOf<String>()
        LuluVirtualA11yTraversal.forEachVisible(
            roots = roots.toList(),
            childCount = { it.children.size },
            childAt = { node, i -> node.children.getOrNull(i) },
            isProtected = { it.isProtected },
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
            Node(isProtected = true, children = listOf(Node(label = "聊天密码"))),
            Node(visible = false, children = listOf(Node(label = "文件传输助手"))),
        ))
        val text = read(tree)
        assertTrue(text.contains("返回"))
        assertTrue(text.contains("文件传输助手"))
        assertFalse(text.contains("聊天密码"))
    }

    @Test fun bottomFirstComposerScanDoesNotSpendEntireBudgetOnChatHistory() {
        val history = Node(label = "chat_history", children = (1..1800).map {
            Node(label = "old message $it")
        })
        val composer = Node(label = "bottom_input")
        val root = Node(children = listOf(history, composer))
        val plain = read(root)
        assertFalse("The legacy top-first scan exhausts before the footer",
            plain.contains("bottom_input"))
        val observed = mutableListOf<String>()
        val complete = LuluVirtualA11yTraversal.forEachVisible(
            roots = listOf(root),
            childCount = { it.children.size },
            childAt = { node, index -> node.children.getOrNull(index) },
            isProtected = { it.isProtected || it.label == "chat_history" },
            isVisible = { it.visible },
            priority = { if (it.label == "bottom_input") 1000 else 0 },
        ) { node ->
            if (node.label.isNotBlank()) observed += node.label
        }
        assertTrue("Composer tree is complete once the irrelevant transcript is pruned", complete)
        assertEquals(listOf("bottom_input"), observed)
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
