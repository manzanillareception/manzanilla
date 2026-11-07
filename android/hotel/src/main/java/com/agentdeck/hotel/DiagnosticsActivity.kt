package com.agentdeck.hotel

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Device-local read-only screen: shows the in-memory voice timing timeline and lets
 * staff export it as text. Timestamps and endpoint names only; no speech content.
 */
class DiagnosticsActivity : Activity() {
    private lateinit var timeline: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ink = Color.rgb(232, 238, 240)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 15, 21))
            setPadding(32, 24, 32, 24)
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(action("Refresh") { render() })
        actions.addView(action("Share as text") { share() })
        actions.addView(action("Clear") { VoiceDiagnostics.clear(); render() })
        actions.addView(action("Close") { finish() })
        root.addView(actions)
        timeline = TextView(this).apply {
            setTextColor(ink)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        root.addView(ScrollView(this).apply { addView(timeline) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private fun action(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(4, 0, 4, 8)
            }
        }

    private fun render() {
        timeline.text = VoiceDiagnostics.render()
    }

    private fun share() {
        val text = VoiceDiagnostics.render()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Manzanilla connection diagnostics")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(send, "Share voice diagnostics"))
        } catch (_: Exception) {
            Toast.makeText(this, "No app can share text on this device", Toast.LENGTH_SHORT).show()
        }
    }
}
