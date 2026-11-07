package com.agentdeck.hotel

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Hotel administration requires the device credential; translation status exposes no keys. */
class KeyEntryActivity : Activity() {
    // The public internal status path exposes no hotel administration or credential fields.
    private val voiceOnly get() = intent.getBooleanExtra("voice_only", false)
    private val setupTitle get() = if (voiceOnly) "Translation unavailable" else "Staff setup"
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private var authenticated = false
    private var authenticationInFlight = false
    private var authenticationAccepted = false
    private var foreground = false
    private val fields = ArrayList<EditText>()
    private var dialog: AlertDialog? = null
    private val light by lazy { getSharedPreferences("deck", MODE_PRIVATE).getString("reception_appearance", "dark") == "light" }
    private val background get() = if (light) Color.rgb(243, 245, 242) else Color.rgb(8, 15, 21)
    private val ink get() = if (light) Color.rgb(24, 44, 56) else Color.rgb(229, 237, 243)
    private val hintInk get() = if (light) Color.rgb(68, 93, 108) else Color.rgb(157, 178, 194)
    private val accent get() = if (light) Color.rgb(20, 85, 112) else Color.rgb(97, 201, 237)
    private val surface get() = if (light) Color.rgb(228, 236, 235) else Color.rgb(24, 44, 59)

    override fun onCreate(state: Bundle?) {
        if (light) setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        // Do not restore a prior configuration view hierarchy or typed credentials.
        super.onCreate(null)
        window.setBackgroundDrawable(ColorDrawable(background))
        window.statusBarColor = background
        window.navigationBarColor = background
        if (light) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!voiceOnly) lockedView("Use your normal device PIN, password or pattern to open setup. No separate staff account is required.")
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (voiceOnly) {
            showConfiguration()
        } else if (authenticationAccepted && keyguard.isDeviceSecure && !keyguard.isKeyguardLocked) {
            authenticationAccepted = false
            authenticated = true
            showConfiguration()
        } else if (!authenticationInFlight) {
            requestAuthentication()
        }
    }

    override fun onPause() {
        foreground = false
        lock()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lock()
        if (foreground && !authenticationInFlight) {
            if (voiceOnly) showConfiguration() else requestAuthentication()
        }
    }

    @Deprecated("Uses Android device credential confirmation for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AUTH_REQUEST || !authenticationInFlight) return
        authenticationInFlight = false
        if (voiceOnly) { authenticationAccepted = false; return }
        authenticationAccepted = resultCode == RESULT_OK && keyguard.isDeviceSecure
        if (!authenticationAccepted) finish()
    }

    private fun lock() {
        authenticated = false
        authenticationAccepted = false
        dialog?.dismiss()
        dialog = null
        fields.forEach { it.setText("") }
        fields.clear()
        if (voiceOnly) {
            setContentView(column().apply { addView(label(setupTitle, 24f)) })
        } else lockedView("Setup is locked. Confirm this device's screen lock to continue.")
    }

    @Suppress("DEPRECATION")
    private fun requestAuthentication() {
        authenticated = false
        if (voiceOnly) return
        if (!keyguard.isDeviceSecure) {
            lockedView("Staff setup is unavailable: no device screen-lock credential is enrolled. A tablet administrator must set a PIN, password or pattern through the tablet's managed Android Security settings, then return and authenticate. Guest setup cannot enroll or reset a credential.")
            return
        }
        val confirmation = keyguard.createConfirmDeviceCredentialIntent(setupTitle, "Use your normal device PIN, password or pattern. No separate staff account is required.")
        if (confirmation == null) {
            lockedView("Android could not open device credential confirmation. Unlock the tablet and retry Staff setup.", retry = true)
            return
        }
        authenticationInFlight = true
        try {
            startActivityForResult(confirmation, AUTH_REQUEST)
        } catch (_: Exception) {
            authenticationInFlight = false
            lockedView("Device credential confirmation is unavailable. Ask the tablet administrator to check Android Security settings.", retry = true)
        }
    }

    private fun authorized(): Boolean {
        if (voiceOnly) return foreground
        if (foreground && authenticated && keyguard.isDeviceSecure && !keyguard.isKeyguardLocked) return true
        lock()
        if (foreground && !authenticationInFlight) requestAuthentication()
        return false
    }

    private fun lockedView(message: String, retry: Boolean = false) {
        val column = column()
        column.addView(label(setupTitle, 24f))
        column.addView(label(message))
        if (retry) column.addView(button("Authenticate again") { requestAuthentication() })
        column.addView(button("Close") { finish() })
        setContentView(column)
    }

    private fun showConfiguration() {
        if (!authorized()) return
        val column = column()
        if (voiceOnly) {
            column.addView(label(setupTitle, 24f))
            column.addView(label(AudioPolicy.BLOCK_REASON))
            column.addView(button("Close") { finish() })
            setContentView(ScrollView(this).apply { isSaveEnabled = false; addView(column) })
            return
        }
        column.addView(label("Staff setup", 24f))
        column.addView(label(AudioPolicy.BLOCK_REASON + " Hotel service setup below is for staff-led assistance."))
        column.addView(label("Hotel service", 20f))
        val connectionUrl = EditText(this).apply {
            hint = "HTTPS hotel service URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            isSaveEnabled = false
            setSaveFromParentEnabled(false)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextColor(ink)
            setHintTextColor(hintInk)
            backgroundTintList = ColorStateList.valueOf(accent)
            setText(HotelConnection(this@KeyEntryActivity).load()?.base.orEmpty())
        }
        fields.add(connectionUrl)
        column.addView(connectionUrl)
        addProvider(column, "Hotel connection", "hotel_connection", connectionUrl)
        column.addView(button("Voice diagnostics (test build)") {
            startActivity(Intent(this@KeyEntryActivity, DiagnosticsActivity::class.java))
        })
        column.addView(button("Done") { finish() })
        setContentView(ScrollView(this).apply {
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            addView(column)
        })
    }

    private fun addProvider(column: LinearLayout, provider: String, prefsName: String, connectionUrl: EditText? = null) {
        // Hotel fields require staff authentication.
        if (!authorized()) return
        val store = SecretStore(this, prefsName)
        val connection = if (connectionUrl != null) HotelConnection(this) else null
        val status = label("")
        fun refresh() {
            if (!authorized()) return
            val savedStatus = store.status()
            status.text = when (savedStatus) {
                SecretStore.Status.READY -> "$provider: a key is securely saved. Entering a new key replaces it."
                SecretStore.Status.ABSENT -> "$provider: no device key is saved."
                SecretStore.Status.ERROR -> "$provider: ${store.errorMessage}"
            }
        }
        column.addView(label(provider, 20f))
        column.addView(status)
        val field = object : EditText(this) {
            override fun onTextContextMenuItem(id: Int): Boolean {
                if (id == android.R.id.copy || id == android.R.id.cut || id == android.R.id.shareText) return true
                return super.onTextContextMenuItem(id)
            }
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.text = null
                info.isPassword = true
                info.contentDescription = "New $provider key, masked"
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY)
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CUT)
            }
            override fun sendAccessibilityEventUnchecked(event: AccessibilityEvent) {
                event.text.clear()
                event.text.add("Masked key field")
                event.beforeText = null
                event.contentDescription = "New $provider key, masked"
                event.isPassword = true
                super.sendAccessibilityEventUnchecked(event)
            }
        }.apply {
            hint = "New $provider key (not shown)"
            contentDescription = "New $provider key, masked"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            transformationMethod = object : PasswordTransformationMethod() {
                // Android's default password transform can briefly reveal the last typed glyph.
                override fun getTransformation(source: CharSequence, view: View): CharSequence = object : CharSequence {
                    override val length: Int get() = source.length
                    override fun get(index: Int): Char = '\u2022'
                    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = "\u2022".repeat(endIndex - startIndex)
                    override fun toString(): String = "\u2022".repeat(length)
                }
            }
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled = false
            setSaveFromParentEnabled(false)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            filterTouchesWhenObscured = true
            setSingleLine()
            setTextColor(ink)
            setHintTextColor(hintInk)
            backgroundTintList = ColorStateList.valueOf(accent)
            customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = false
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = true
                override fun onDestroyActionMode(mode: ActionMode) {}
            }
        }
        fields.add(field)
        column.addView(field)
        column.addView(button("Save $provider key") {
            if (!authorized()) return@button
            val value = field.text.toString().trim()
            if (value.isEmpty() || value.any { it.isWhitespace() }) {
                Toast.makeText(this, "Paste a provider key without spaces.", Toast.LENGTH_LONG).show()
            } else if (if (connection != null) connection.save(connectionUrl!!.text.toString(), value) else store.save(value)) {
                field.setText("")
                refresh()
                Toast.makeText(this, "$provider key securely saved.", Toast.LENGTH_SHORT).show()
            } else {
                status.text = "$provider: ${connection?.errorMessage ?: store.errorMessage}"
            }
        })
        column.addView(button("Remove $provider key") {
            if (!authorized()) return@button
            dialog = AlertDialog.Builder(this)
                .setTitle("Remove saved $provider key?")
                .setMessage("This only removes this tablet's credential. Keep the provider's original key if you need it again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ ->
                    if (authorized()) {
                        store.clear()
                        field.setText("")
                        if (store.status == SecretStore.Status.ERROR) status.text = "$provider: ${store.errorMessage}"
                        else refresh()
                    }
                }.create().also {
                    it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    it.show()
                    it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
        })
        refresh()
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(this@KeyEntryActivity.background)
        setPadding(40, 24, 40, 24)
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    private fun label(value: String, size: Float = 15f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(ink)
        setPadding(0, 8, 0, 8)
        isSaveEnabled = false
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        setTextColor(ink)
        backgroundTintList = ColorStateList.valueOf(surface)
        minHeight = (48 * resources.displayMetrics.density).toInt()
        filterTouchesWhenObscured = true
        setOnClickListener { action() }
    }

    companion object { private const val AUTH_REQUEST = 31 }
}
