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

/** Device-local V1 provider/profile setup; no cloud account is required. */
class KeyEntryActivity : Activity() {
    private val voiceOnly get() = intent.getBooleanExtra("voice_only", false)
    private val nightOnly get() = intent.getBooleanExtra("night_only", false)
    private val v1Setup get() = intent.getBooleanExtra("v1_setup", false)
    private val publicSetup get() = voiceOnly || nightOnly || v1Setup
    private val setupTitle get() = when { voiceOnly -> "OpenAI translation key"; nightOnly -> "Night receptionist setup"; v1Setup -> "Reception setup"; else -> "Staff setup" }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private val management by lazy { ManagementSync(this, onMain = { action -> runOnUiThread { action() } }) }
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
        if (!publicSetup) lockedView("Use your normal device PIN, password or pattern to open setup. No separate staff account is required.")
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (publicSetup) {
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
            if (publicSetup) showConfiguration() else requestAuthentication()
        }
    }

    @Deprecated("Uses Android device credential confirmation for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AUTH_REQUEST || !authenticationInFlight) return
        authenticationInFlight = false
        if (publicSetup) { authenticationAccepted = false; return }
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
        if (publicSetup) {
            setContentView(column().apply { addView(label(setupTitle, 24f)) })
        } else lockedView("Setup is locked. Confirm this device's screen lock to continue.")
    }

    @Suppress("DEPRECATION")
    private fun requestAuthentication() {
        authenticated = false
        if (publicSetup) return
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
        if (publicSetup) return foreground
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
            column.addView(label("Used only for live translation. Your key is encrypted on this device, never included in the APK. Reopen this screen to replace or remove it."))
            addProvider(column, "OpenAI", "openai")
            column.addView(button("Done") { finish() })
            setContentView(ScrollView(this).apply { isSaveEnabled = false; addView(column) })
            return
        }
        if (nightOnly) {
            showNightProfile(column)
            return
        }
        if (v1Setup) {
            column.addView(label(setupTitle, 24f))
            column.addView(button("OpenAI translation key") {
                startActivity(Intent(this, KeyEntryActivity::class.java).putExtra("voice_only", true))
            })
            column.addView(button("Night receptionist setup") {
                startActivity(Intent(this, KeyEntryActivity::class.java).putExtra("night_only", true))
            })
            column.addView(button("Connection diagnostics") {
                startActivity(Intent(this, DiagnosticsActivity::class.java))
            })
            column.addView(button("Done") { finish() })
            setContentView(ScrollView(this).apply { isSaveEnabled = false; addView(column) })
            return
        }
        column.addView(label("Staff setup", 24f))
        column.addView(label("Hotel service setup is for staff-led assistance. Translation and night reception have separate V1 settings."))
        column.addView(button("OpenAI translation key") { startActivity(Intent(this, KeyEntryActivity::class.java).putExtra("voice_only", true)) })
        column.addView(button("Night receptionist setup") { startActivity(Intent(this, KeyEntryActivity::class.java).putExtra("night_only", true)) })
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
        addManagementSection(column)
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

    // Device management enrollment. Primary path: hotel staff email/password sign-in
    // (release builds carry the public Supabase client config; without it the setup-code
    // path below remains). Setup codes are the admin-assisted fallback from the web admin.
    private fun addManagementSection(column: LinearLayout) {
        column.addView(label("Device management", 20f))
        val status = label("")
        status.text = management.statusSummary()
        column.addView(status)
        if (management.enrolled()) {
            column.addView(button("Remove device management") {
                AlertDialog.Builder(this).setTitle("Remove device management")
                    .setMessage("This tablet will no longer report to or be controlled by hotel management. Voice keeps working; the tablet becomes unmanaged.")
                    .setPositiveButton("Remove") { _, _ ->
                        management.clearEnrollment()
                        lock(); showConfiguration()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
            return
        }
        if (BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()) {
            val email = EditText(this).apply {
                hint = "Staff email"
                inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                setSingleLine(); isSaveEnabled = false; setSaveFromParentEnabled(false)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                setTextColor(ink); setHintTextColor(hintInk)
                backgroundTintList = ColorStateList.valueOf(accent)
            }
            val password = EditText(this).apply {
                hint = "Staff password"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(); isSaveEnabled = false; setSaveFromParentEnabled(false)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                setTextColor(ink); setHintTextColor(hintInk)
                backgroundTintList = ColorStateList.valueOf(accent)
            }
            fields.add(email); fields.add(password)
            column.addView(email); column.addView(password)
            column.addView(button("Sign in and enroll") {
                val e = email.text.toString(); val p = password.text.toString()
                if (!ManagementCredentials.validEmail(e.trim())) { toast("Enter the hotel staff email."); return@button }
                if (p.isBlank()) { toast("Enter the staff password."); return@button }
                toast("Signing in…")
                management.signInAndEnroll(ManagementSync.DEFAULT_BASE, e, p, deviceLabel()) { ok, msg ->
                    toast(msg); if (ok) { lock(); showConfiguration() }
                }
            })
        }
        val code = EditText(this).apply {
            hint = "Setup code from web admin"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(); isSaveEnabled = false; setSaveFromParentEnabled(false)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextColor(ink); setHintTextColor(hintInk)
            backgroundTintList = ColorStateList.valueOf(accent)
        }
        fields.add(code)
        column.addView(code)
        column.addView(button("Enroll with setup code") {
            val c = code.text.toString()
            if (ManagementCredentials.validSetupToken(c) == null) { toast("Enter the setup code exactly as shown in the web admin."); return@button }
            toast("Enrolling…")
            management.enrollWithToken(ManagementSync.DEFAULT_BASE, c, deviceLabel()) { ok, msg ->
                toast(msg); if (ok) { lock(); showConfiguration() }
            }
        })
    }

    private fun deviceLabel() = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} tablet"

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun showNightProfile(column: LinearLayout) {
        val store = NightProfileStore(this)
        val profile = store.load()
        column.addView(label(setupTitle, 24f))
        column.addView(label("Supply the hotel facts guests may hear. Leave unknown facts blank. Location is shared with the receptionist only when a conversation starts."))
        fun input(title: String, value: String, multiline: Boolean = false): EditText {
            column.addView(label(title))
            return EditText(this).apply {
                setText(value)
                hint = title
                inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_VARIATION_NORMAL
                if (!multiline) setSingleLine() else minLines = 2
                isSaveEnabled = false
                setSaveFromParentEnabled(false)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                setTextColor(ink)
                setHintTextColor(hintInk)
                backgroundTintList = ColorStateList.valueOf(accent)
                fields.add(this)
                column.addView(this)
            }
        }
        val name = input("Hotel name", profile.name)
        val address = input("Hotel address", profile.address)
        val area = input("City / area", profile.area)
        val hours = input("Reception opening hours", profile.openingHours)
        val facts = input("Approved hotel facts and check-in guidance", profile.facts, true)
        val latitude = input("Hotel latitude (optional)", profile.latitude?.toString().orEmpty())
        val longitude = input("Hotel longitude (optional)", profile.longitude?.toString().orEmpty())
        val localPack = input("Approved nearby places JSON (optional)", profile.localPackJson, true)
        val status = label("")
        column.addView(status)
        column.addView(button("Save receptionist settings") {
            if (!authorized()) return@button
            val latText = latitude.text.toString().trim()
            val lonText = longitude.text.toString().trim()
            val lat = latText.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            val lon = lonText.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            if ((latText.isNotEmpty() && lat == null) || (lonText.isNotEmpty() && lon == null) ||
                (lat == null) != (lon == null) || (lat != null && (!lat.isFinite() || lat !in -90.0..90.0)) ||
                (lon != null && (!lon.isFinite() || lon !in -180.0..180.0))) {
                status.text = "Enter both coordinates within the valid latitude/longitude ranges, or leave both blank."
            } else if (store.save(name.text.toString(), address.text.toString(), area.text.toString(),
                    hours.text.toString(), facts.text.toString(), lat, lon,
                    localPack.text.toString().ifBlank { "[]" }, profile.availability, profile.availabilityUpdatedAtMillis)) {
                setResult(RESULT_OK)
                finish()
            } else status.text = "Settings were not saved. Check field lengths and the nearby-places JSON, then try again."
        })
        column.addView(button("Cancel") { finish() })
        setContentView(ScrollView(this).apply { isSaveEnabled = false; addView(column) })
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
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled = false
            setSaveFromParentEnabled(false)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            filterTouchesWhenObscured = true
            setSingleLine()
            transformationMethod = object : PasswordTransformationMethod() {
                // Android's default password transform can briefly reveal the last typed glyph.
                override fun getTransformation(source: CharSequence, view: View): CharSequence = object : CharSequence {
                    override val length: Int get() = source.length
                    override fun get(index: Int): Char = '\u2022'
                    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = "\u2022".repeat(endIndex - startIndex)
                    override fun toString(): String = "\u2022".repeat(length)
                }
            }
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
                if (voiceOnly) { setResult(RESULT_OK); finish() }
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
