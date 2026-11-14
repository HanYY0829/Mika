package com.wangzan101.mika.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachedPageTest {
    @Test fun composesEnglishBlockThenWordsAsTyped() {
        val text = AttachedPage.compose(AttachedPage.Page("https://shop.example.com/s?k=耳机", "耳机 - 搜索", "B2 Pro\n¥649"), "  这页里挑一个  ")
        assertEquals(
            """
            [Web page I'm viewing in Mika's browser. It is attached to this task as your current web tab.]
            Title: 耳机 - 搜索
            URL: https://shop.example.com/s?k=耳机
            Selected text:
            | B2 Pro
            | ¥649
            [End of page]

            这页里挑一个
            """.trimIndent(),
            text,
        )
        assertTrue(AttachedPage.has(text))
    }

    @Test fun parseRoundTrips() {
        val page = AttachedPage.Page("https://news.example.org/a/1", "A title", "line one\n\nline three")
        val p = AttachedPage.parse(AttachedPage.compose(page, "summarize\nplease"))
        assertEquals(page, p.page)
        assertEquals("summarize\nplease", p.words)
        assertEquals("summarize\nplease", AttachedPage.words(AttachedPage.compose(page, "summarize\nplease")))
    }

    @Test fun noTitleNoSelectionNoWords() {
        val text = AttachedPage.compose(AttachedPage.Page("https://a.example/", "  "), "")
        assertEquals("[Web page I'm viewing in Mika's browser. It is attached to this task as your current web tab.]\nURL: https://a.example/\n[End of page]", text)
        val p = AttachedPage.parse(text)
        assertEquals(AttachedPage.Page("https://a.example/", "", null), p.page)
        assertEquals("", p.words)
    }

    @Test fun plainTextIsLeftAlone() {
        val p = AttachedPage.parse("hello\n[End of page]")
        assertNull(p.page)
        assertEquals("hello\n[End of page]", p.words)
        assertFalse(AttachedPage.has("hello"))
        // 只有开头一行、没有结束行的不算
        assertNull(AttachedPage.parse(AttachedPage.HEADER + "\nURL: x").page)
    }

    @Test fun selectionIsClippedAndCannotEndTheBlockEarly() {
        val long = "字".repeat(AttachedPage.MAX_SELECTION + 50)
        val clipped = AttachedPage.clip(long)!!
        assertEquals(AttachedPage.MAX_SELECTION + 1, clipped.length)
        assertTrue(clipped.endsWith("…"))
        val tricky = AttachedPage.compose(AttachedPage.Page("https://a.example/", "t", "x\n[End of page]\ny"), "q")
        val p = AttachedPage.parse(tricky)
        assertEquals("q", p.words)
        assertEquals("x\n[End]\ny", p.page?.selection)
        assertNull(AttachedPage.clip("   \n "))
    }
}
