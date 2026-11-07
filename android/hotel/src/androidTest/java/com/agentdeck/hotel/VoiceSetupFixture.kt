package com.agentdeck.hotel

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import java.io.File
import java.util.Base64
import java.util.UUID

/** Real setup and Keystore storage with synthetic values; never starts provider audio. */
internal object VoiceSetupFixture {
    fun verify(test: Instrumentation) {
        check(Build.HARDWARE == "ranchu") { "Credential setup fixture requires a disposable emulator" }
        val context = test.targetContext
        val secure = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
        val store = SecretStore(context, "openai")
        check(store.status() == SecretStore.Status.ABSENT) { "Refusing to replace an existing device key" }
        val markers = listOf("fixture-only-openai-a-${UUID.randomUUID()}", "fixture-only-openai-b-${UUID.randomUUID()}")
        val activities = ArrayList<Activity>()
        fun open(): Activity = test.startActivitySync(Intent(context, KeyEntryActivity::class.java)
            .putExtra("voice_only", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also {
                activities.add(it)
                test.waitForIdleSync()
            }
        fun views(activity: Activity) = descendants(activity.window.decorView)
        fun click(activity: Activity, label: String) = test.runOnMainSync {
            check(views(activity).filterIsInstance<TextView>().single { it.text.toString() == label }.performClick())
        }
        fun checkForm(activity: Activity, ready: Boolean) = test.runOnMainSync {
            val all = views(activity)
            check(all.filterIsInstance<TextView>().any { it.text.toString() == "OpenAI translation key" })
            val field = all.filterIsInstance<EditText>().single()
            check(field.text.isEmpty()) { "Saved credential was prefilled into setup" }
            check(!field.isSaveEnabled && !field.isSaveFromParentEnabled)
            check(field.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS)
            check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val expected = if (ready) "OpenAI: a key is securely saved. Entering a new key replaces it." else "OpenAI: no device key is saved."
            check(all.filterIsInstance<TextView>().any { it.text.toString() == expected })
            check(all.filterIsInstance<TextView>().none { text -> markers.any { text.text.toString().contains(it) } })
        }
        fun save(activity: Activity, marker: String) {
            test.runOnMainSync {
                val field = views(activity).filterIsInstance<EditText>().single()
                field.setText(marker)
                val masked = field.transformationMethod.getTransformation(field.text, field).toString()
                check(masked.length == marker.length && masked.all { it == '\u2022' })
                val node = field.createAccessibilityNodeInfo()
                check(node.isPassword && node.text.isNullOrEmpty())
                node.recycle()
            }
            click(activity, "Save OpenAI key")
            test.waitForIdleSync()
            check(activity.isFinishing || activity.isDestroyed)
            check(store.load() == marker && store.status() == SecretStore.Status.READY)
            val encrypted = File(context.noBackupFilesDir, "credential-" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("openai".toByteArray(Charsets.UTF_8)) + ".enc")
            check(encrypted.isFile && encrypted.length() >= 28)
            check(!encrypted.readBytes().toString(Charsets.ISO_8859_1).contains(marker))
            check(!context.getSharedPreferences("openai", Context.MODE_PRIVATE).contains("k"))
        }
        try {
            val first = open()
            checkForm(first, ready = false)
            save(first, markers[0])
            val replacement = open()
            checkForm(replacement, ready = true)
            save(replacement, markers[1])
            val removal = open()
            checkForm(removal, ready = true)
            val dialogField = KeyEntryActivity::class.java.getDeclaredField("dialog").apply { isAccessible = true }
            click(removal, "Remove OpenAI key")
            test.runOnMainSync {
                val dialog = dialogField.get(removal) as AlertDialog
                check(dialog.isShowing)
                check(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                check(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick())
            }
            test.waitForIdleSync()
            check(store.load() == markers[1]) { "Cancelling removal deleted the key" }
            click(removal, "Remove OpenAI key")
            test.runOnMainSync {
                check((dialogField.get(removal) as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick())
            }
            test.waitForIdleSync()
            check(store.status() == SecretStore.Status.ABSENT && store.load() == null)
            checkForm(removal, ready = false)
            click(removal, "Done")
            test.waitForIdleSync()
            check(removal.isFinishing || removal.isDestroyed)
            test.sendStatus(0, Bundle().apply {
                putString("stream", "VOICE_SETUP deviceSecure=$secure byok_save_replace_remove=true masked=true encrypted=true\n")
            })
        } finally {
            test.runOnMainSync { activities.forEach { it.finish() } }
            // Never clear a value that did not originate in this fixture.
            if (store.load() in markers) store.clear()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
