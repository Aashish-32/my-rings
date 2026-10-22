package com.aashish.myrings

import com.aashish.myrings.service.HealthNotifier
import com.aashish.myrings.service.HealthNotifier.decide
import org.junit.Assert.*
import org.junit.Test

class HealthNotifierTest {
    @Test fun firstDriftNotifies() {
        val d = decide(wasActive = false, notePresent = true, canPost = true)
        assertTrue(d.notify); assertTrue(d.nowActive)
    }

    @Test fun sameEpisodeDoesNotNotifyAgain() {
        val d = decide(wasActive = true, notePresent = true, canPost = true)
        assertFalse(d.notify); assertTrue(d.nowActive)
    }

    @Test fun returningToNormalStartsAFreshEpisode() {
        assertFalse(decide(wasActive = true, notePresent = false, canPost = true).nowActive)
        assertTrue(decide(wasActive = false, notePresent = true, canPost = true).notify)
    }

    @Test fun blockedNotificationsAreRetriedLater() {
        val d = decide(wasActive = false, notePresent = true, canPost = false)
        assertFalse(d.notify); assertFalse(d.nowActive)
    }

    @Test fun lowBatteryNotifiesOncePerEpisodeAndNotWhileCharging() {
        val n = HealthNotifier.decideLowBattery(false, 18, 22, true)
        assertTrue(n.notify && n.nowActive)
        assertFalse(HealthNotifier.decideLowBattery(true, 15, 18, true).notify)          // already announced
        assertFalse(HealthNotifier.decideLowBattery(false, 12, 7, true).notify)          // rising: on the charger
        assertFalse(HealthNotifier.decideLowBattery(true, 25, 18, true).nowActive.not()) // still the same episode until 35%
        assertFalse(HealthNotifier.decideLowBattery(true, 40, 35, true).nowActive)       // recovered: next one is new
    }
}
