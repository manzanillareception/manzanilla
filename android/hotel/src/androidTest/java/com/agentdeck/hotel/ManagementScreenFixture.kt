package com.agentdeck.hotel

import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import java.io.File
import java.io.FileOutputStream

/**
 * Captures real pixels of the staff management (enrollment) screen on a disposable emulator.
 * Run with: am instrument -w -e case management-screen com.agentdeck.hotel.test/com.agentdeck.hotel.VoiceLifecycleFixture
 * No credentials are typed. On an emulator with a secure lock screen (the CI script sets a PIN
 * via `locksettings set-pin 0000`), the device-credential confirmation is answered through the
 * shell; without one the locked explanation view is captured instead, which is still the
 * tablet-visible truth for an unsecured device.
 */
object ManagementScreenFixture {
    fun verify(test: Instrumentation) {
        val context = test.targetContext
        val activity = test.startActivitySync(
            Intent(context, KeyEntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isDeviceSecure && keyguard.isKeyguardLocked) {
            // Answer the system credential prompt with the CI fixture PIN via shell input.
            test.uiAutomation.executeShellCommand("input keyevent 82")?.close()
            Thread.sleep(1_000)
            test.uiAutomation.executeShellCommand("input text 0000")?.close()
            Thread.sleep(500)
            test.uiAutomation.executeShellCommand("input keyevent 66")?.close()
        }
        // Wait for a drawn frame, then capture with retries (surface may need several vsyncs).
        var bitmap: Bitmap? = null
        repeat(40) {
            Thread.sleep(500)
            bitmap = runCatching { test.uiAutomation.takeScreenshot() }.getOrNull()
            if (bitmap != null && bitmap!!.width > 0 && bitmap!!.height > 0) return@repeat
            bitmap = null
        }
        check(bitmap != null) { "No drawable frame for the management screen" }
        val out = File(context.filesDir, "management-screen.png")
        FileOutputStream(out).use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(out.isFile && out.length() > 1_000) { "Screenshot not written" }
        // Also expose via shell-visible location for adb pull from the runner.
        runCatching {
            val external = context.getExternalFilesDir(null)
            if (external != null) FileOutputStream(File(external, "management-screen.png"))
                .use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        activity.finish()
    }
}
