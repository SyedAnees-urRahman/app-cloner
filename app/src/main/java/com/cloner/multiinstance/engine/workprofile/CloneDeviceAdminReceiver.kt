package com.cloner.multiinstance.engine.workprofile

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Device admin receiver required to become the Profile Owner of a managed
 * (work) profile. Provisioned via:
 *
 *   adb shell dpm set-profile-owner com.cloner.multiinstance/.engine.workprofile.CloneDeviceAdminReceiver
 *
 * Once provisioned, [WorkProfileManager] can install apps into the work
 * profile — real installs, fully isolated, no hooking.
 */
class CloneDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        // Profile created. Nothing mandatory here; the work profile launcher
        // badge appears automatically. We just record readiness.
        WorkProfileManager.onProvisioningComplete(context)
    }

    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, CloneDeviceAdminReceiver::class.java)
    }
}
