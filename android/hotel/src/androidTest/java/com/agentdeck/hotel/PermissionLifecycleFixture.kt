package com.agentdeck.hotel

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import com.agentdeck.ui.ReceptionView
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Simulates a pending permission lease. Never grants permission, creates audio or connects a provider. */
internal object PermissionLifecycleFixture {
    fun verify(test: Instrumentation) {
        val context = test.targetContext
        val activity = test.startActivitySync(Intent(context, HotelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HotelActivity
        val ownerField = HotelActivity::class.java.getDeclaredField("owner").apply { isAccessible = true }
        val pending = HotelActivity::class.java.getDeclaredField("pendingMicrophone").apply { isAccessible = true }
        val permission = HotelActivity::class.java.getDeclaredField("permissionDialog").apply { isAccessible = true }
        val reception = HotelActivity::class.java.getDeclaredField("reception").apply { isAccessible = true }
        val shown = ReceptionView::class.java.getDeclaredField("shown").apply { isAccessible = true }
        val owner = ownerField.get(activity) as SessionOwner
        fun stagePermissionPause(): SessionOwner.Lease {
            lateinit var lease: SessionOwner.Lease
            test.runOnMainSync {
                lease = owner.acquire(SessionOwner.Request(UUID.randomUUID().toString(), "translation", "fr", "en"))!!
                pending.set(activity, lease)
                permission.setBoolean(activity, true)
                test.callActivityOnPause(activity)
                check(owner.current == lease && !owner.closing) { "Permission pause cancelled its pending session" }
                check(shown.getBoolean(reception.get(activity))) { "Permission pause detached the JavaScript bridge" }
            }
            return lease
        }
        fun awaitReleased() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var released = false
            while (!released && System.nanoTime() < deadline) {
                test.runOnMainSync { released = owner.current == null && !owner.audioUnknown }
                if (!released) Thread.sleep(20)
            }
            check(released) { "Pending permission lease did not release" }
        }
        try {
            test.waitForIdleSync()
            stagePermissionPause()
            test.runOnMainSync {
                activity.onRequestPermissionsResult(5, arrayOf(Manifest.permission.RECORD_AUDIO),
                    intArrayOf(PackageManager.PERMISSION_DENIED))
            }
            awaitReleased()
            test.runOnMainSync { test.callActivityOnResume(activity) }
            stagePermissionPause()
            test.runOnMainSync { test.callActivityOnStop(activity) }
            awaitReleased()
        } finally {
            test.runOnMainSync { activity.finish() }
        }
    }
}
