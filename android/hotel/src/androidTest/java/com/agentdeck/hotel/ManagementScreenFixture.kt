package com.agentdeck.hotel

import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import java.io.File

/**
 * Captures real pixels of the staff management (enrollment) screen on a disposable emulator.
 * Run with: am instrument -w -e case management-screen com.agentdeck.hotel.test/com.agentdeck.hotel.VoiceLifecycleFixture
 * No credentials are typed. When the CI script sets a fixture PIN, the system credential
 * prompt is answered through shell input. Capture uses the shell `screencap` binary because
 * UiAutomation screenshots can return hardware bitmaps/null on headless emulators. The PNG
 * path is reported as an INSTRUMENTATION_STATUS `screenshot=<path>` line for the runner.
 */
object ManagementScreenFixture {
    fun verify(test: Instrumentation) {
        val context = test.targetContext
        val intent = Intent(context, KeyEntryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent) // async: startActivitySync from the main thread can deadlock/throw
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure == true && keyguard.isKeyguardLocked) {
            test.uiAutomation.executeShellCommand("input keyevent 82")?.close()
            Thread.sleep(1_000)
            test.uiAutomation.executeShellCommand("input text 0000")?.close()
            Thread.sleep(500)
            test.uiAutomation.executeShellCommand("input keyevent 66")?.close()
        }
        test.sendStatus(0, Bundle().apply { putString("stream", "management-screen stage=launched\n") })
        // Let the window draw.
        Thread.sleep(4_000)
        val out = File(context.getExternalFilesDir(null) ?: context.filesDir, "management-screen.png")
        out.delete()
        test.uiAutomation.executeShellCommand("screencap -p ${out.absolutePath}")?.close()
        // Wait for the capture to land.
        var ready = false
        repeat(20) {
            Thread.sleep(500)
            if (out.isFile && out.length() > 1_000) { ready = true; return@repeat }
        }
        if (!ready) {
            test.sendStatus(0, Bundle().apply { putString("stream", "management-screen stage=capture-failed size=" + (if (out.isFile) out.length() else -1L) + "\n") })
        }
        check(ready) { "screencap produced no output" }
        test.sendStatus(0, Bundle().apply { putString("stream", "management-screen stage=captured\n") })        // Activity left open intentionally: the runner tears the emulator down.
        test.sendStatus(0, Bundle().apply { putString("screenshot", out.absolutePath) })
    }
}
