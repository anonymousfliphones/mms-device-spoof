# mms-device-spoof

Android library that fixes Verizon MMS voice note playback by overriding the
User-Agent and UAProf URL your app sends to the carrier MMSC.

## The problem

The Verizon MMSC transcodes voice notes based on the **receiver's** UAProf URL,
not the sender's. Every third-party SMS app (and Google Messages) identifies
itself as `Android-Mms/1.0` + Google's kila UAProf. The MMSC sees that and
delivers voice notes as `audio/vnd.qcelp` (QCELP) — a codec Android has no
built-in decoder for, so playback fails.

When the receiver identifies as a real Verizon handset (e.g. TCL Flip T408DL),
the same MMSC delivers the same voice note as `audio/x-mpeg3` (MP3), which
plays on any Android device.

This was proven empirically on 2026-10-08 using a TCL Flip T408DL on
TracFone/Verizon (MMSC `mms.vzwreseller.com`):

| App | User-Agent | UAProf | Delivered codec |
|---|---|---|---|
| Google Messages | `Android-Mms/1.0` | kila.xml | `audio/vnd.qcelp` ❌ |
| TCL stock Messaging | `T408DL-MMS/2.0` | uaprof.vtext.com/alcatel/… | `audio/x-mpeg3` ✅ |

The Accept header is irrelevant. Verizon httpParams (X-VzW-MDN) are already
present in the firmware's carrier config on Verizon-branded phones and do not
need to be injected.

## Built-in presets

| Preset | User-Agent | UAProf host | Notes |
|---|---|---|---|
| `SYSTEM` | — | — | Auto-detect: applies VERIZON on Verizon/MVNO SIM, no-op otherwise |
| `VERIZON` | `T408DL-MMS/2.0` | uaprof.vtext.com | **Proven** on TracFone/Verizon |
| `SAMSUNG` | `samg970u` | uaprof.vtext.com | Galaxy S10 on Verizon — also uaprof.vtext.com |
| `IPHONE` | `iPhoneOS/4.2.1 (8C148)` | iphonemms.apple.com | — |
| `MOTO` | `motog7power` | uaprof.motorola.com | — |
| `CUSTOM` | caller-supplied | caller-supplied | Pass strings to `apply()` |

Both VERIZON and SAMSUNG use `uaprof.vtext.com`, so either triggers MP3
transcoding on Verizon. VERIZON is the tested one.

## Library module (`lib`)

### Method 1 — raw SmsManager (no vendored library)

```kotlin
// In your send / download call:
val overrides = Bundle()
MmsDeviceSpoof.apply(
    preset  = MmsDeviceSpoof.Preset.SYSTEM,
    context = context,
    subId   = subId,
    configOverrides = overrides
)
SmsManager.getSmsManagerForSubscriptionId(subId)
    .downloadMultimediaMessage(context, locationUrl, contentUri, overrides, pendingIntent)
```

Android's `MmsService` applies `configOverrides` via `putAll` **after** carrier
config, so these two keys always win:

- `SmsManager.MMS_CONFIG_USER_AGENT`
- `SmsManager.MMS_CONFIG_UA_PROF_URL`

### Method 2 — klinker/android-smsmms vendored hook

Most third-party SMS apps (DPAD Messaging, QKSMS, Pulse, …) vendor a copy of
[klinker/android-smsmms](https://github.com/klinker41/android-smsmms).
The library calls `SmsManager.sendMultimediaMessage` /
`downloadMultimediaMessage` internally, so you need a hook at that layer.

#### Step 1 — Add `MmsRequestOverrides.java` to your vendored library

Create `vendor/mmslib/src/main/java/com/klinker/android/send_message/MmsRequestOverrides.java`:

```java
package com.klinker.android.send_message;

import android.content.Context;
import android.os.Bundle;

public final class MmsRequestOverrides {

    public interface Provider {
        void apply(Context context, int subId, Bundle configOverrides);
        boolean appendTransactionId(Context context, int subId);
    }

    private static volatile Provider provider;

    public static void setProvider(Provider p) { provider = p; }
    public static Provider getProvider()       { return provider; }
}
```

#### Step 2 — Call it in `DownloadManager.java` (receive path)

In `downloadMms()`, just before the `SmsManager` call, add:

```java
MmsRequestOverrides.Provider p = MmsRequestOverrides.getProvider();
if (p != null) p.apply(context, subscriptionId, configOverrides);
```

#### Step 3 — Register the hook in `Application.onCreate()`

```kotlin
MmsDeviceSpoof.installKlinkerHook { context, subId, bundle ->
    MmsDeviceSpoof.apply(MmsDeviceSpoof.Preset.SYSTEM, context, subId, bundle)
}
```

Also call `MmsDeviceSpoof.apply()` from your send path before you call
klinker's `Transaction.sendNewMessage()`.

### Storing the preset in SharedPreferences

```kotlin
val preset = MmsDeviceSpoof.Preset.fromString(
    prefs.getString("mms_ua_preset", "system") ?: "system"
)
MmsDeviceSpoof.apply(preset, context, subId, overrides,
    customUserAgent = prefs.getString("mms_custom_ua", null),
    customUaProfUrl = prefs.getString("mms_custom_uaprof", null)
)
```

## `MmsAudio` — MIME type normalization

Verizon delivers MP3 voice notes with content-type `audio/x-mpeg3`. Android's
`MediaPlayer` can play the file but the non-standard type breaks file-open
intents and extension inference. `MmsAudio.normalizeMimeType()` collapses all
known aliases to their RFC counterpart.

```kotlin
val canonical = MmsAudio.normalizeMimeType(part.contentType, part.fileName)
// "audio/x-mpeg3" → "audio/mpeg"
// "audio/x-amr"   → "audio/amr"
```

`MmsAudio.cacheFile()` copies a `content://mms/part/<id>` URI to a cache file,
sniffing the true format from the leading bytes and giving it the right
extension.

## JitPack

```groovy
// settings.gradle
dependencyResolutionManagement {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}

// build.gradle
dependencies {
    implementation 'com.github.anonymousfliphones:mms-device-spoof:<tag>'
}
```

## License

Dual-licensed under [GPL-3.0](LICENSE) + [PolyForm Noncommercial 1.0.0](LICENSE):
- Source must remain open (GPL-3.0 copyleft)
- Commercial use is not permitted (PolyForm Noncommercial)
