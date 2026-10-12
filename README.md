# RunPlan

A free, rules-based training plan and pace coach for Android, with a Galaxy Watch (Wear OS) display.

- **Plan:** answer five questions (recent race, goal, race day, run days, weekly km). You get a periodised plan of Base, Build, Peak and Taper, with training paces from the Daniels VDOT model.
- **Run:** the phone records GPS and talks you through each step ("Rep 2 of 5, 5 minutes at 5:03 to 5:13"). It warns "speed up" or "ease off" when you leave the target band, and announces kilometre splits on easy and long runs.
- **Watch:** shows the step, target band, current pace and time left. It buzzes on step changes and when you go off pace, and can start, pause and skip steps.
- **History:** step-by-step results against target, and a map of each run with the route coloured by pace zone (OpenStreetMap / CARTO tiles). Exports: GPX, TCX (one lap per step), steps CSV and raw GPS CSV.
- **Strava:** upload a run, or every run automatically. Intervals show as laps, and the description lists each rep against its target.

## Install

1. Get `runplan-phone.apk` and `runplan-watch.apk` from the **Latest build** release, or from the Actions run artifacts.
2. **Phone:** open the APK and allow installs from your browser or file manager.
3. **Watch** (Galaxy Watch 4 or later, Wear OS 3+):
   1. On the watch, go to Settings, then About watch, then Software information, and tap Software version 5 times. This turns on Developer options.
   2. In Developer options, turn on ADB debugging and Wireless debugging. Note the IP and port it shows.
   3. From a computer with [platform-tools](https://developer.android.com/tools/releases/platform-tools), pair and install:
      ```
      adb pair <ip>:<pairing-port>        # enter the code shown on the watch
      adb connect <ip>:<port>
      adb install runplan-watch.apk
      ```
   Updates install over the old version, because both apps are signed with the shared key in `keystore/`.

## Strava setup (one-off)

Strava only lets apps upload through an API app you register, which is free for personal use:

1. Go to https://www.strava.com/settings/api and create an app. Any name, category and website will do.
2. Set **Authorization Callback Domain** to `localhost`.
3. In RunPlan, go to Settings, then Strava. Paste the **Client ID** and **Client Secret**, then tap **Connect Strava** and approve uploads.

## Use

Before a run, open RunPlan on the watch. On the phone, tap **Start run**, wait for "Ready", then press Start on the phone or the watch.

## Build

The project uses Gradle 8.11, AGP 8.7, Kotlin 2.1 and Compose. CI builds on every push to `main`. To build locally:

```
gradle :core:test :app:assembleDebug :wear:assembleDebug
```

| Module | What it is |
|---|---|
| `core` | Pure Kotlin. VDOT, plan builder, run step engine and cues, GPS odometer, watch wire format. Unit-tested on the JVM. |
| `app` | Phone app: Compose UI, foreground GPS service, text-to-speech, JSON storage, exports. |
| `wear` | Wear OS display and controls over the Wearable Data Layer. |

The plan rules mirror `runplan.py`, the Python CLI this started from.

## Notes

- The signing key in `keystore/` is shared and public. That's fine for personal sideloading. Generate a private one before distributing.
- Fonts: Barlow and Barlow Condensed, SIL Open Font License (see `FONTS-OFL.txt`).
