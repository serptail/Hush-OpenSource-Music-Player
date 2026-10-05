package com.gamut.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the code paths of a normal launch (loading screen, Discover, scrolling) so release builds ship them
 * pre-compiled instead of JIT-compiling them on first use. That covers the first-composition stall and the
 * first-open cost of big composables. Needs network, because Discover loads from Apple.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndDiscover() = rule.collect(packageName = "com.gamut", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        // Loading screen ends once the charts are in; Discover is then a scrollable list.
        device.wait(Until.hasObject(By.scrollable(true)), 20_000)
        device.waitForIdle()
        device.findObject(By.scrollable(true))?.let { list ->
            list.setGestureMargin(device.displayWidth / 5)
            list.fling(Direction.DOWN)
            device.waitForIdle()
            list.fling(Direction.UP)
            device.waitForIdle()
        }
    }
}
