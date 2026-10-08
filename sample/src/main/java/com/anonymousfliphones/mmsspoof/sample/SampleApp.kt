package com.anonymousfliphones.mmsspoof.sample

import android.app.Application
import android.os.Bundle
import com.anonymousfliphones.mmsspoof.MmsDeviceSpoof

/**
 * Demonstrates wiring MmsDeviceSpoof into an Application class.
 *
 * ## Send path (no vendored library)
 *
 * Build a configOverrides Bundle before calling SmsManager:
 *
 * ```kotlin
 * val overrides = Bundle()
 * MmsDeviceSpoof.apply(MmsDeviceSpoof.Preset.SYSTEM, context, subId, overrides)
 * SmsManager.getSmsManagerForSubscriptionId(subId)
 *     .downloadMultimediaMessage(context, locationUrl, contentUri, overrides, pendingIntent)
 * ```
 *
 * ## Receive path (klinker/android-smsmms vendored library)
 *
 * Add MmsRequestOverrides.java to your vendored copy of the library (see README),
 * then call [MmsDeviceSpoof.installKlinkerHook] once in Application.onCreate().
 *
 * ## Preset stored in SharedPreferences
 *
 * ```kotlin
 * val preset = MmsDeviceSpoof.Preset.fromString(prefs.getString("mms_ua_preset", "system"))
 * MmsDeviceSpoof.apply(preset, context, subId, overrides)
 * ```
 */
class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Option A — klinker hook (covers both send and receive automatically):
        MmsDeviceSpoof.installKlinkerHook { context, subId, bundle ->
            MmsDeviceSpoof.apply(MmsDeviceSpoof.Preset.SYSTEM, context, subId, bundle)
        }

        // Option B — manual send path (if you use raw SmsManager, not klinker):
        // val overrides = Bundle()
        // MmsDeviceSpoof.apply(MmsDeviceSpoof.Preset.VERIZON, this, -1, overrides)
        // SmsManager.getDefault().sendMultimediaMessage(this, contentUri, null, overrides, null)
    }
}
