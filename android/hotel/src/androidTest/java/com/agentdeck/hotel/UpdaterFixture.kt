package com.agentdeck.hotel

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import java.io.File
import java.util.UUID

/** Isolated cache/admission checks plus one empty Android install session, never committed.
 * No downloaded APK, actual installation, provider credential or network request is used.
 */
internal object UpdaterFixture {
    fun verify(instrumentation: Instrumentation) {
        check(BuildConfig.DEBUG) { "Use a debug verification APK" }
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "updater-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val prefix = root.name
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?): File = File(root, "downloads").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
            override fun startActivity(intent: Intent) { error("Isolated fixture must never open installer or settings UI") }
        }
        val statuses = ArrayList<String>()
        val updater = Updater(context, statuses::add, canInstall = { false })
        val ownership = Updater::class.java.getDeclaredField("ownership").apply { isAccessible = true }.get(updater) as UpdateOwnership
        val phase = Updater::class.java.getDeclaredField("phase").apply { isAccessible = true }
        val request = UpdateRequest("v99.0.0", "https://fixture.invalid/releases/download/v99.0.0/app.apk",
            base.packageName, "99.0.0", 9999, UpdateRequest.hash("fixture-only".toByteArray()))
        val before = base.packageManager.getPackageInfo(base.packageName, 0)
        val settings = context.getSharedPreferences("deck", Context.MODE_PRIVATE)
        val prefs = context.getSharedPreferences("upd", Context.MODE_PRIVATE)
        val sentinel = File(context.filesDir, "reception-preferences.fixture").apply { writeText("appearance=light;guest=fr;staff=de") }
        val externalSentinel = File(context.getExternalFilesDir(null), "reception-history.fixture").apply { writeText("fixture-history-kept") }
        val apk = File(context.filesDir, "update-${request.key}.apk").apply { writeText("fixture-cache-not-an-APK") }
        val downloaded = File(context.getExternalFilesDir(null), "update-${request.key}.apk").apply { writeText("fixture-cache-copy") }
        try {
            check(settings.edit().putString("reception_appearance", "light").putString("reception_guest_language", "fr").commit())
            instrumentation.runOnMainSync {
                check(ownership.begin(request) != null)
                phase.set(updater, "ready")
                repeat(3) { updater.tryInstallIfIdle() }
                check(!updater.isInstalling()) { "busy reception reserved an installation" }
                check(phase.get(updater) == "ready") { "busy reception started validation or installer UI" }
                check(statuses.size == 1 && statuses.single().startsWith("waiting:")) { "idle polling repeated UI/status work" }
                updater.cancel()
                check(ownership.active == null && !updater.isInstalling())
            }
            check(!apk.exists() && !downloaded.exists()) { "cancel did not remove only its cached APK copies" }
            check(sentinel.readText() == "appearance=light;guest=fr;staff=de" && externalSentinel.readText() == "fixture-history-kept")
            check(settings.getString("reception_appearance", null) == "light" && settings.getString("reception_guest_language", null) == "fr")

            val obsolete = File(context.filesDir, "update-obsolete.apk").apply { writeText("obsolete-cache") }
            val part = File(context.filesDir, "update-obsolete.part").apply { writeText("interrupted-cache") }
            val cleanup = Updater::class.java.getDeclaredMethod("discardObsoleteCache").apply { isAccessible = true }
            cleanup.invoke(updater)
            check(!obsolete.exists() && !part.exists() && sentinel.isFile && externalSentinel.isFile)
            check(settings.getString("reception_appearance", null) == "light")

            // A declined/dispatched update restored by a new activity stays manual.
            check(prefs.edit().putString("installAttempted", request.key).commit())
            val restoredStatuses = ArrayList<String>()
            val restored = Updater(context, restoredStatuses::add, canInstall = { true })
            val restoredOwnership = Updater::class.java.getDeclaredField("ownership").apply { isAccessible = true }.get(restored) as UpdateOwnership
            val restore = Updater::class.java.getDeclaredMethod("restoreInstallAttempt", UpdateRequest::class.java).apply { isAccessible = true }
            instrumentation.runOnMainSync {
                check(restoredOwnership.begin(request) != null)
                restore.invoke(restored, request)
                phase.set(restored, "ready")
                repeat(3) { restored.tryInstallIfIdle() }
                check(!restored.isInstalling() && phase.get(restored) == "ready")
                check(restoredStatuses.size == 1 && restoredStatuses.single().startsWith("confirm:"))
                restored.cancel()
            }

            val factory = Updater::class.java.getDeclaredMethod("installParameters", String::class.java).apply { isAccessible = true }
            val params = factory.invoke(updater, base.packageName) as PackageInstaller.SessionParams
            val installer = base.packageManager.packageInstaller
            val id = installer.createSession(params)
            try {
                val info = installer.getSessionInfo(id) ?: error("Android did not retain the staged fixture session")
                check(info.appPackageName == base.packageName) { "update changed package identity" }
                if (Build.VERSION.SDK_INT >= 27) check(info.mode == PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                if (Build.VERSION.SDK_INT >= 31) check(info.requireUserAction == PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                // Replay a delayed Android consent callback after reception went idle-off.
                // The real listener must release admission and retain a manual action,
                // without launching installer UI over a different foreground app.
                instrumentation.runOnMainSync {
                    val attempt = ownership.begin(request)!!
                    val policy = Updater::class.java.getDeclaredField("installPolicy").apply { isAccessible = true }.get(updater) as UpdateInstallPolicy
                    check(policy.reserve(idle = true, permitted = true) == UpdateInstallPolicy.Decision.INSTALL)
                    phase.set(updater, "installing")
                    val session = Updater::class.java.getDeclaredField("installSessionId").apply { isAccessible = true }
                    session.setInt(updater, id)
                    Updater::class.java.getDeclaredMethod("listen", UpdateAttempt::class.java).apply { isAccessible = true }.invoke(updater, attempt)
                    val listener = Updater::class.java.getDeclaredField("receiver").apply { isAccessible = true }.get(updater) as BroadcastReceiver
                    val pending = Intent("dev.orange.hotel.UPDATE_RESULT")
                        .putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
                        .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
                        .putExtra(Intent.EXTRA_INTENT, Intent("fixture.CONFIRM"))
                    listener.onReceive(context, pending)
                    check(phase.get(updater) == "confirming" && !updater.isInstalling() && ownership.active == attempt)
                    listener.onReceive(context, pending)
                    updater.onForeground()
                    check(phase.get(updater) == "confirming" && !updater.isInstalling())
                    Updater::class.java.getDeclaredMethod("stopListening").apply { isAccessible = true }.invoke(updater)
                    session.setInt(updater, -1)
                }
                // Deliberately no openWrite/commit: this cannot replace the running application.
            } finally { installer.abandonSession(id) }

            val after = base.packageManager.getPackageInfo(base.packageName, PackageManager.GET_PERMISSIONS)
            check(after.firstInstallTime == before.firstInstallTime && after.versionName == before.versionName)
            check(File(after.applicationInfo!!.sourceDir).isFile) { "cache cleanup removed the installed package" }
            check(after.requestedPermissions.orEmpty().contains("android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"))
            check(sentinel.readText() == "appearance=light;guest=fr;staff=de")
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "updater_scope=isolated idle=deferred cache_cleanup=user_data_retained installer=same_package_full_install_uncommitted_and_abandoned\n")
            })
        } finally {
            instrumentation.runOnMainSync { updater.cancel() }
            prefs.edit().clear().commit()
            settings.edit().clear().commit()
            root.deleteRecursively()
        }
    }
}
