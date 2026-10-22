package com.aashish.myrings

import com.aashish.myrings.service.RingWidget
import org.junit.Assert.*
import org.junit.Test

class RingWidgetTest {
    @Test fun agoReadsNaturally() {
        assertEquals("just now", RingWidget.ago(30))
        assertEquals("12 min ago", RingWidget.ago(12 * 60))
        assertEquals("3 h ago", RingWidget.ago(3 * 3600))
        assertEquals("2 d ago", RingWidget.ago(2 * 86_400))
    }

    @Test fun batteryColourTurnsRedBelowTwentyPercent() {
        assertNotEquals(RingWidget.batteryColor(19), RingWidget.batteryColor(20))
        assertNotEquals(RingWidget.batteryColor(39), RingWidget.batteryColor(40))
    }
}
