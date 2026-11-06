package com.agentdeck.hotel

import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView

/** The retired voice setup must not collect a credential for a removed provider. */
internal object VoiceSetupFixture {
    fun verify(test: Instrumentation) {
        val context = test.targetContext
        val secure = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
        val activity = test.startActivitySync(Intent(context, KeyEntryActivity::class.java)
            .putExtra("voice_only", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
        try {
            test.waitForIdleSync()
            test.runOnMainSync {
                val views = children(activity.window.decorView)
                check(views.none { it is EditText }) { "Unavailable translation setup collected a credential" }
                val text = views.filterIsInstance<TextView>().map { it.text.toString() }
                check(text.any { it == "Translation unavailable" })
                check(text.any { it == "Live translation is unavailable in this build." })
                check(views.filterIsInstance<TextView>().first { it.text.toString() == "Close" }.performClick())
            }
            test.waitForIdleSync()
            check(activity.isFinishing || activity.isDestroyed)
            println("VOICE_SETUP deviceSecure=$secure")
        } finally {
            test.runOnMainSync { activity.finish() }
        }
    }
}
