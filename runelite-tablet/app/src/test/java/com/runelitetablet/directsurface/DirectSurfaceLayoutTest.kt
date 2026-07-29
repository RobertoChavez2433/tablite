package com.runelitetablet.directsurface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectSurfaceLayoutTest {
    @Test
    fun nativeCanvasLeavesRuneLiteSidebarVisible() {
        assertEquals(
            DirectOverlayLayout(left = 0, top = 1, width = 2898, height = 1846),
            computeDirectOverlayLayout(
                hostWidth = 2960,
                hostHeight = 1848,
                frameWidth = 2898,
                frameHeight = 1846,
            ),
        )
    }

    @Test
    fun pluginPanelResizeExposesAdditionalX11Chrome() {
        assertEquals(
            DirectOverlayLayout(left = 0, top = 1, width = 2434, height = 1846),
            computeDirectOverlayLayout(
                hostWidth = 2960,
                hostHeight = 1848,
                frameWidth = 2434,
                frameHeight = 1846,
            ),
        )
    }

    @Test
    fun oversizedFrameIsClampedToHostSurface() {
        assertEquals(
            DirectOverlayLayout(left = 0, top = 0, width = 2960, height = 1848),
            computeDirectOverlayLayout(
                hostWidth = 2960,
                hostHeight = 1848,
                frameWidth = 4096,
                frameHeight = 2160,
            ),
        )
    }

    @Test
    fun zeroSizedHostDefersLayout() {
        assertNull(
            computeDirectOverlayLayout(
                hostWidth = 0,
                hostHeight = 1848,
                frameWidth = 2898,
                frameHeight = 1846,
            ),
        )
    }
}
