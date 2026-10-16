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
 * Records the code paths of a normal launch (loading screen, Discover, scrolling, Settings and its sub-pages) so release
 * builds ship them pre-compiled instead of JIT-compiling them on first use. Needs network, because Discover loads from Apple.
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

        // Let the idle pre-composition of the Settings pages and the player finish (it runs until ~5 s after loading).
        Thread.sleep(6_000)

        // Settings -> Playback & Audio -> back -> back.
        if (device.wait(Until.hasObject(By.desc("Profile")), 5_000)) {
            device.findObject(By.desc("Profile")).click()
            device.wait(Until.hasObject(By.text("Playback & Audio")), 5_000)
            device.waitForIdle()
            device.findObject(By.text("Playback & Audio"))?.click()
            device.wait(Until.hasObject(By.text("Equalizer")), 5_000)
            device.waitForIdle()
            device.findObject(By.scrollable(true))?.fling(Direction.DOWN)
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
        }
    }
}