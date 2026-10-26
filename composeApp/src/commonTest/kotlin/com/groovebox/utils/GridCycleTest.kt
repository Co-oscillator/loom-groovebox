package com.groovebox.utils

import com.groovebox.GridMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The GRID button walks a fixed cycle and offers the split 20/20 layout as its
 * last step, so there is no separate split control.
 */
class GridCycleTest {

    private fun walk(steps: Int, includeMacKeys: Boolean = false): List<String> {
        var mode = GridMode.GRID_4X4
        var split = false
        val labels = mutableListOf<String>()
        repeat(steps) {
            labels += if (split) "20/20" else mode.shortLabel()
            val (nextMode, nextSplit) = nextGridStep(mode, split, includeMacKeys)
            mode = nextMode
            split = nextSplit
        }
        return labels
    }

    @Test
    fun cycleEndsOnSplitThenWrapsTo4x4() {
        assertEquals(listOf("4x4", "6x6", "TON", "20/20"), walk(4))
        // The fifth press is back at 4x4.
        assertEquals("4x4", walk(5).last())
    }

    @Test
    fun keyboardModeAddsMacKeysBeforeTonnetz() {
        assertEquals(listOf("4x4", "6x6", "Keys", "TON", "20/20"), walk(5, includeMacKeys = true))
    }

    @Test
    fun splitAlwaysReturnsTo4x4() {
        assertEquals(GridMode.GRID_4X4 to false, nextGridStep(GridMode.TONNETZ, true, false))
    }
}
