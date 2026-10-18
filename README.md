# Glasses Mode

An Android app for USB-C display glasses (XREAL One and similar) that mirror your phone.
Double-tap a volume button and:

- the **phone's own screen goes black** and **ignores touches**, while the **glasses keep showing the picture**
- the picture is **locked to landscape**, so it stops flipping when you move the phone

Double-tap again, or unplug the glasses, to go back to normal. No root, no Shizuku, no PC needed after install.

## Install

1. Download `GlassesMode-*.apk` from the [latest release](../../releases/latest) and install it.
2. Open **Glasses Mode** and tap **Open accessibility settings**, then turn on **Glasses Mode**.
   - If the switch is greyed out ("restricted setting"), go to *Settings → Apps → Glasses Mode*, open the **⋮** menu,
     tap **Allow restricted settings**, then try again. Android does this for any app installed outside the Play Store.
3. On ColorOS / MIUI / similar, allow the app to run in the background (auto-launch, no battery restrictions)
   so the system doesn't turn the service off.

## Use

With the glasses plugged in:

| Action | What happens |
| --- | --- |
| Double-tap volume up or volume down | Glasses mode on / off (one buzz = on, two buzzes = off) |
| Single tap | Changes the volume as usual (after a short pause, to tell it apart from a double tap) |
| Hold | Keeps changing the volume as usual |
| Unplug the glasses | Glasses mode turns off by itself |

Without glasses plugged in, the volume buttons are left completely alone.

## How it works

Everything runs inside an accessibility service, so no special permissions are needed beyond turning it on.

- **Black phone screen:** the app attaches a black layer to the phone's display and marks it "skip screenshot".
  Android only draws such layers on the built-in screen and leaves them out of mirrored copies,
  so the glasses still show the normal picture.
- **No touches:** while the mode is on, the service takes over touchscreen input
  (`AccessibilityServiceInfo.setMotionEventSources(SOURCE_TOUCHSCREEN)`), so taps, edge swipes and the
  notification shade do nothing. A transparent full-screen overlay catches anything that slips through.
- **Landscape lock:** that same overlay window asks for landscape orientation, which wins even over
  portrait-only apps. It also keeps the screen awake.
- **Volume buttons:** handled with the accessibility key filter, only while glasses are connected
  (an external display is present).

Nothing in system settings is changed, so there is nothing to restore if the app is killed.

## Compatibility

- Requires Android 14 or newer.
- Tested on an OPPO Find N6 (Android 16, ColorOS) with XREAL One glasses.
- Should work with any glasses or USB-C display that shows up as a mirrored external display.
  Reports from other phones are welcome. Some manufacturer builds may ignore the "skip screenshot" flag,
  in which case the glasses would go black too; double-tap again to undo.

## Build

Needs JDK 17+ and the Android SDK (platform 36).

```sh
./gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`.

For testing from a computer, glasses mode can also be toggled over adb:

```sh
adb shell am broadcast -a com.esfle.glassesmode.TOGGLE
```

## License

MIT
