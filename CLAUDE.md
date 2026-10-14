# RunPlan: notes for Claude

A free, Runna-style training plan and pace coach: an Android phone app (GPS and voice coaching) plus a Galaxy Watch (Wear OS) display. Thomas builds it for himself first, but other people may use it, so the app must stay free of jargon.

## How changes ship

- Push to `main` and CI (`.github/workflows/build.yml`) runs the `:core` tests, builds both APKs and publishes the **Latest build** GitHub release.
- The phone app updates itself from that release (`data/Updater.kt`, versionCode = Actions run number).
- Thomas tests on his own phone and watch, so a red build means nothing ships. Check CI after pushing: `gh run list -R spanalytic/runplan-app -L 1`.
- Commit straight to `main`; that is the established flow here.

## Modules

| Module | What it holds |
|---|---|
| `core` | Pure Kotlin, unit-tested: VDOT paces (`Vdot.kt`), plan rules (`PlanBuilder.kt`), run step engine and spoken cues (`RunEngine.kt`), GPS odometer (`Track.kt`), analytics (best efforts, climb), exports. **Put logic here, not in the app.** |
| `app` | Compose UI, foreground `RunService` (GPS, barometer if present, TTS via `Speaker`), JSON storage (`Store.kt`), exports, Strava upload. |
| `wear` | Watch display and controls. Gets `RunState` from the phone in a compact wire format (`RunState.encode/decode`). |
| `tools/runplan.py` | Python CLI the plan rules came from. **Keep it in step with `PlanBuilder.kt`**; a test checks that a hilly plan matches it week by week. |

## Testing without network

The sandbox can't reach Maven or Google, so `gradle` can't build here. To compile and run the core tests, use the Kotlin compiler bundled with Gradle (`/opt/gradle-*/lib/kotlin-compiler-embeddable-*.jar`) with a small stand-in for `kotlin.test` (compile with `-Xallow-kotlin-package`) and a main that runs each `@Test` method by reflection. The app and wear modules can only be checked by CI.

## Product decisions (keep unless Thomas says otherwise)

- **No jargon for users:** VDOT and zone codes stay internal. Users see "Easy", "Tempo", "Hard"…
- **Easy runs** have only a ceiling, no slower limit: no faster than marathon pace (`Paces.easyCap`), with slow-down reminders spaced 1, 2, then 3 minutes apart. Stored easy results have `slow` = NaN (ceiling only).
- **Strava** goes through the free web uploader (a TCX file is saved, then strava.com/upload opens). No paid API app.
- **No auto-pause.** Time is elapsed (active) time.
- **No external elevation service** for now (Thomas: elevation not important; also avoids sending routes to a third party). Climb comes from the barometer if the phone has one, otherwise GPS altitude averaged over 150 m with a 4 m step. Thomas's phone (Galaxy A55) has no barometer; his Galaxy Watch 4 does, and streaming its pressure readings to the phone is a possible future step.
- **Voice:** 5 s start countdown. The last step announces "one minute / 200 metres left in the workout". The finish says "Workout complete, good job", then the last lap (only if there was more than one step), then a summary. Speech queues, never interrupts. The speaker waits until it has finished talking before shutting down.
- **Moving sessions:** a session can move to another day of its own week (button on the session screen, or long-press it in the Plan tab). Moving onto a day with a session swaps the two. Moves are stored as `moves` (workout id to day) in `state.json` and laid on top of the built plan (`Moves.kt`); a workout's id stays its planned day so done flags and runs still match. Done sessions and race day don't move.
- **Done sessions don't change when the plan is edited:** each done session's workout is copied into `kept` in `state.json` (workout id to workout) and laid on the rebuilt plan before moves (`Kept.kt`); un-ticking it lets it follow the plan again. A done session with a recorded run shows the run's actual distance and time in the Plan tab.
- **Starting a workout:** tapping a plan session opens it with "Start this workout". On the Plan tab, "Start workout" on a day with a planned session asks: the planned workout or build your own. Rest days (and days whose session is done) go straight to "Build a workout"; "Free run" stays as a second option.
- **Built workouts** (`CustomWorkout` in `Custom.kt`, `BuildScreen`): optional warm-up, a main part of N repeats (time or distance, optional pace zone) with recovery jogs between, optional cool-down. Passed to `RunService` as a `custom:` id and coached like plan sessions, but saved with no `workoutId`, so they don't tick off a planned session. The builder remembers the last one only while the app runs.
- **Hill sessions** (optional, from the onboarding "Is your race hilly?" question): 10 min easy, jog to the hill (open step, tap Next), then per rep a 10 s countdown (`StepKind.READY`), the rep by effort (no pace target, zone code "H"), 10 s rest, jog back down (open step, tap Next), then cool-down. Base: hill sprints (10 s) every other week instead of strides. Build: hill repeats (60–90 s) every 4th/3rd/2nd week for a flat/rolling/hilly race. Hilly races also keep some in Peak. None in the taper.

## Conventions

- Plain-English UI and spoken text, British spelling, metric units.
- Stored data is JSON (`state.json`) plus one CSV per GPS track. New fields need defaults so old saves still load. A damaged `state.json` is never overwritten: it is copied aside and `state.json.bak` (taken at each clean start) is used.
- While a run records, `RunService` saves progress every 15 s (`run-in-progress.json` plus the track CSV). If the app dies mid-run, the next start saves the run from that file and tells the user. Bump `Store.ANALYTICS_VERSION` when derived numbers (best efforts, climb) are calculated differently, so saved runs get recalculated.
- Add a core test with any engine or plan change.
