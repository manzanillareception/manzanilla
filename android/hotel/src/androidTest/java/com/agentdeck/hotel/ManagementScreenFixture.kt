package com.agentdeck.hotel

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import java.io.File
import java.io.FileOutputStream

/**
 * Captures real pixels of the staff management (enrollment) screen on a disposable emulator.
 * Run with: am instrument -w -e case management-screen com.agentdeck.hotel.test/com.agentdeck.hotel.VoiceLifecycleFixture
 * No credentials are typed; the screen is unenrolled by definition on a fresh emulator.
 */
object ManagementScreenFixture {
    fun verify(test: Instrumentation) {
        val context = test.targetContext
        val intent = Intent(context, KeyEntryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = test.startActivitySync(intent)
        // Let the locked view / configuration view render.
        Thread.sleep(2_000)
        val screenshot: Bitmap = test.uiAutomation.takeScreenshot()
            ?: error("UiAutomation screenshot unavailable")
        val out = File(context.getExternalFilesDir(null) ?: context.filesDir, "management-screen.png")
        FileOutputStream(out).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(out.isFile && out.length() > 0) { "Screenshot not written" }
        test.finish(0, Bundle().apply { putString("screenshot", out.absolutePath) })
        activity.finish()
    }
}
