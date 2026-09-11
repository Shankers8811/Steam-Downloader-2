package com.example

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared helpers for the human-scenario suites. The app's ViewModels flow
 * from app-scoped service singletons; to emulate every human-visible state
 * without a network, tests inject states straight into the managers'
 * internal flows — the *public rendering contract* is what gets asserted
 * (labels, buttons, gating), not the injection mechanics.
 */
object ScenarioTestHarness {

    /** Sets the value of a private MutableStateFlow field found on [target]
     *  (searching superclasses as well). Used by scenario tests to drive the
     *  real state machine through every human-visible state. */
    @Suppress("UNCHECKED_CAST")
    fun <T> setFlow(target: Any, fieldName: String, value: T) {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val hit = cls.declaredFields.firstOrNull { it.name == fieldName }
            if (hit != null) {
                hit.isAccessible = true
                (hit.get(target) as MutableStateFlow<T>).value = value
                return
            }
            cls = cls.superclass
        }
        error("No field '$fieldName' found on ${target.javaClass} (or superclasses)")
    }

    /** Case-insensitive, substring, unmerged-tree text presence. */
    fun SemanticsNodeInteractionsProvider.anyNodeVisible(
        text: String,
        substring: Boolean = true
    ): Boolean = onAllNodes(
        hasText(text, substring = substring, ignoreCase = true),
        useUnmergedTree = true
    ).fetchSemanticsNodes().isNotEmpty()

    /** Every text currently visible on screen (for self-diagnosing failures:
     *  failure messages always tell us what WAS rendered, not just what
     *  wasn't). */
    fun SemanticsNodeInteractionsProvider.visibleTexts(): List<String> = runCatching {
        onRoot(useUnmergedTree = true).fetchSemanticsNode().allTexts()
    }.getOrDefault(listOf("<tree unreadable>"))

    private fun SemanticsNode.allTexts(): List<String> {
        val mine = runCatching {
            config[SemanticsProperties.Text]
                .joinToString(" | ") { annotated -> annotated.text }
        }.getOrNull()
        val rest: List<String> = children.flatMap { child -> child.allTexts() }
        return listOfNotNull(mine) + rest
    }

    /** Polls (up to [timeoutMs]) for the text to appear — Room/coroutine
     *  emissions reach the composition a beat after the test thread proceeds. */
    fun SemanticsNodeInteractionsProvider.awaitVisible(
        text: String,
        substring: Boolean = true,
        timeoutMs: Long = 4_000
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (anyNodeVisible(text, substring)) return
            Thread.sleep(100)
        }
        org.junit.Assert.assertTrue(
            "Timed out awaiting visible text '$text'. Visible now: ${visibleTexts().take(30)}",
            anyNodeVisible(text, substring)
        )
    }

    fun SemanticsNodeInteractionsProvider.assertAnyVisible(
        text: String,
        substring: Boolean = true
    ) {
        org.junit.Assert.assertTrue(
            "Expected any visible node with text containing '$text'. Visible now: ${visibleTexts().take(30)}",
            anyNodeVisible(text, substring)
        )
    }

    fun SemanticsNodeInteractionsProvider.assertNoneVisible(
        text: String,
        substring: Boolean = true
    ) {
        org.junit.Assert.assertFalse(
            "Expected NO visible node with text containing '$text'. Visible now: ${visibleTexts().take(30)}",
            anyNodeVisible(text, substring)
        )
    }
}
