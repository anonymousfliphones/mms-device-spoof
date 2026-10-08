package com.anonymousfliphones.mmsspoof

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.TelephonyManager

/**
 * Injects a spoofed MMS User-Agent and UAProf URL into the configOverrides Bundle
 * that Android's MmsService accepts on [SmsManager.sendMultimediaMessage] and
 * [SmsManager.downloadMultimediaMessage].
 *
 * ## Why this exists
 *
 * The Verizon MMSC transcodes audio attachments based on the **receiver's** UAProf
 * URL, not the sender's. When Android's stock MmsService identifies itself as
 * `Android-Mms/1.0` + Google's kila UAProf, the MMSC delivers voice notes as
 * `audio/vnd.qcelp` (QCELP) — a codec Android has no decoder for. When the
 * receiver identifies as a real Verizon handset (e.g. TCL Flip T408DL), the same
 * MMSC delivers the same note as `audio/x-mpeg3` MP3.
 *
 * This was proven empirically on 2026-10-08 using a TCL Flip T408DL on
 * TracFone/Verizon (MMSC mms.vzwreseller.com): only the stock TCL Messaging app
 * (UA `T408DL-MMS/2.0`, UAProf `uaprof.vtext.com/alcatel/wst408dl/wst408dl.xml`)
 * received the voice note as MP3. Google Messages — which uses MmsService just
 * like every third-party SMS app — received QCELP.
 *
 * ## Integration (klinker/android-smsmms vendored library)
 *
 * The easiest hook point is `MmsRequestOverrides` in that library. In App.onCreate():
 *
 * ```kotlin
 * MmsDeviceSpoof.installKlinkerHook { context, subId, bundle ->
 *     MmsDeviceSpoof.apply(Preset.SYSTEM, context, subId, bundle)
 * }
 * ```
 *
 * Also call [apply] from the send path before passing configOverrides to
 * [SmsManager.sendMultimediaMessage].
 *
 * ## Integration (raw MmsService)
 *
 * ```kotlin
 * val overrides = Bundle()
 * MmsDeviceSpoof.apply(Preset.VERIZON, context, subId, overrides)
 * SmsManager.getSmsManagerForSubscriptionId(subId)
 *     .downloadMultimediaMessage(context, locationUrl, contentUri, overrides, pendingIntent)
 * ```
 */
object MmsDeviceSpoof {

    /**
     * Preset identities. Each maps to a (User-Agent, UAProf URL) pair.
     *
     * [SYSTEM] auto-detects the SIM: applies [VERIZON] when a Verizon/MVNO SIM is
     * active, otherwise does nothing (leaves the firmware's own identity in place).
     *
     * [CUSTOM] reads [customUserAgent] / [customUaProfUrl] you supply to [apply].
     */
    enum class Preset {
        /** Auto-detect: Verizon SIM → [VERIZON] preset; other SIM → no override. */
        SYSTEM,
        /** TCL Flip T408DL — proven to receive Verizon voice notes as MP3. */
        VERIZON,
        /** Samsung Galaxy S10 on Verizon (uaprof.vtext.com) — also gets MP3. */
        SAMSUNG,
        /** iPhone. */
        IPHONE,
        /** Motorola G7 Power. */
        MOTO,
        /** Caller supplies [customUserAgent] and/or [customUaProfUrl] to [apply]. */
        CUSTOM;

        companion object {
            fun fromString(s: String): Preset = entries.firstOrNull {
                it.name.equals(s, ignoreCase = true)
            } ?: SYSTEM
        }
    }

    // ── UA / UAProf table ─────────────────────────────────────────────────────

    /**
     * TCL Flip T408DL on TracFone/Verizon.
     * Proven 2026-10-08: this identity causes uaprof.vtext.com MMSC to deliver
     * voice notes as audio/x-mpeg3 (MP3) instead of audio/vnd.qcelp (QCELP).
     */
    const val UA_VERIZON     = "T408DL-MMS/2.0"
    const val UAPROF_VERIZON = "http://uaprof.vtext.com/alcatel/wst408dl/wst408dl.xml"

    /** Samsung Galaxy S10 (SM-G970U) on Verizon — also served by uaprof.vtext.com. */
    const val UA_SAMSUNG     = "samg970u"
    const val UAPROF_SAMSUNG = "http://uaprof.vtext.com/sam/samg970u/samg970u.xml"

    /** iPhone (iOS 4.2.1) — Apple's MMS UAProf. */
    const val UA_IPHONE      = "iPhoneOS/4.2.1 (8C148)"
    const val UAPROF_IPHONE  = "http://iphonemms.apple.com/iphone/uaprof-2MB.rdf"

    /** Motorola G7 Power. */
    const val UA_MOTO        = "motog7power"
    const val UAPROF_MOTO    = "http://uaprof.motorola.com/phoneconfig/motov1/Profile/motov1.rdf"

    // ── Verizon SIM detection ─────────────────────────────────────────────────

    private const val VERIZON_CARRIER_ID = 1839
    private val VERIZON_MCC_MNC = setOf(
        "310004", "310010", "310012", "310013",
        "311480", "311481", "311482", "311483", "311484",
        "311485", "311486", "311487", "311488", "311489"
    )

    /** Returns true when the SIM identified by [subId] is Verizon or a Verizon MVNO. */
    fun isVerizonSim(context: Context, subId: Int): Boolean {
        val base = context.getSystemService(TelephonyManager::class.java) ?: return false
        val tm = if (subId >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            runCatching { base.createForSubscriptionId(subId) }.getOrDefault(base)
        else base
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            runCatching { tm.simCarrierId }.getOrDefault(-1) == VERIZON_CARRIER_ID) return true
        return runCatching { tm.simOperator }.getOrNull().orEmpty() in VERIZON_MCC_MNC
    }

    // ── Core apply ───────────────────────────────────────────────────────────

    /**
     * Writes the UA / UAProf matching [preset] into [configOverrides].
     *
     * @param preset         Which identity to use.
     * @param context        Used only for [Preset.SYSTEM] SIM detection.
     * @param subId          Subscription ID for the MMS operation (pass -1 if unknown).
     * @param configOverrides The Bundle passed to MmsService.
     * @param customUserAgent UA string used when [preset] is [Preset.CUSTOM].
     * @param customUaProfUrl UAProf URL used when [preset] is [Preset.CUSTOM].
     */
    fun apply(
        preset: Preset,
        context: Context,
        subId: Int,
        configOverrides: Bundle,
        customUserAgent: String? = null,
        customUaProfUrl: String? = null
    ) {
        val (ua, uaProf) = when (preset) {
            Preset.CUSTOM -> {
                val ua   = customUserAgent?.trim()?.takeIf { it.isNotEmpty() }
                val prof = customUaProfUrl?.trim()?.takeIf { it.isNotEmpty() }
                if (ua == null && prof == null) return
                ua to prof
            }
            Preset.VERIZON, Preset.SYSTEM -> {
                if (preset == Preset.SYSTEM && !isVerizonSim(context, subId)) return
                UA_VERIZON to UAPROF_VERIZON
            }
            Preset.SAMSUNG -> UA_SAMSUNG to UAPROF_SAMSUNG
            Preset.IPHONE  -> UA_IPHONE  to UAPROF_IPHONE
            Preset.MOTO    -> UA_MOTO    to UAPROF_MOTO
        }

        if (ua != null)     configOverrides.putString(SmsManager.MMS_CONFIG_USER_AGENT, ua)
        if (uaProf != null) configOverrides.putString(SmsManager.MMS_CONFIG_UA_PROF_URL, uaProf)
    }

    // ── klinker/android-smsmms hook helper ───────────────────────────────────

    /**
     * Convenience wrapper: registers a [MmsRequestOverrides.Provider] with the
     * vendored klinker library. This hooks **both** the download (receive) and send
     * paths so you don't have to patch two sites.
     *
     * Call once from your `Application.onCreate()`:
     * ```kotlin
     * MmsDeviceSpoof.installKlinkerHook { context, subId, bundle ->
     *     MmsDeviceSpoof.apply(Preset.SYSTEM, context, subId, bundle)
     * }
     * ```
     *
     * Requires `MmsRequestOverrides.java` vendored into your copy of android-smsmms.
     * See the `sample/` module for the full vendoring instructions.
     *
     * @param block Called on every MMS send/receive. Write to [bundle] to override.
     */
    fun installKlinkerHook(
        block: (context: Context, subId: Int, bundle: Bundle) -> Unit
    ) {
        try {
            val providerClass = Class.forName("com.klinker.android.send_message.MmsRequestOverrides")
            val provider = java.lang.reflect.Proxy.newProxyInstance(
                providerClass.classLoader,
                arrayOf(providerClass.getDeclaredClasses().first { it.simpleName == "Provider" })
            ) { _, method, args ->
                when (method.name) {
                    "apply" -> {
                        val ctx    = args[0] as Context
                        val subId  = args[1] as Int
                        val bundle = args[2] as Bundle
                        block(ctx, subId, bundle)
                        null
                    }
                    "appendTransactionId" -> false
                    else -> null
                }
            }
            providerClass.getMethod("setProvider", providerClass.getDeclaredClasses()
                .first { it.simpleName == "Provider" }).invoke(null, provider)
        } catch (_: ClassNotFoundException) {
            // klinker library not on classpath — hook silently skipped.
        }
    }
}
