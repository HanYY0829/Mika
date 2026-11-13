package com.wangzan101.mika.capsule

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapsuleEdgeContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun leftHalfIsNarrowAndTapRestores() = check(DockSide.Left)
    @Test fun rightHalfIsNarrowAndTapRestores() = check(DockSide.Right)

    private fun check(side: DockSide) {
        var restored = 0
        compose.setContent { MikaTheme { CapsuleEdgeContent(side, 0.4f) { restored++ } } }
        val half = compose.onNodeWithTag("capsule-edge")
        val size = half.fetchSemanticsNode().size
        assertTrue("half-circle window must be narrower than the ball: " + size, size.width < size.height)
        half.performClick()
        assertEquals(1, restored)
    }
}
