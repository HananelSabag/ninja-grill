# Design

The look of Ninja Grill as built (2026-10-20, mockup 4; sky palette 2026-11-14). Code is the source of truth: `ui/theme/Theme.kt`, `ui/Components.kt`.

## World
A clean instrument for one job: how close is the meat, and what is the grill doing, set against an open sky: the page is a clear daytime sky (a night sky in dark) with soft clouds and a slow sun, and the grill photo is lit in front of it. Light, friendly tones; azure is the primary. Heat appears in orange only where something is hot, Stop in red, done in green. Light by default (read beside a grill in daylight). Dark is a full scheme, chosen in settings (light / dark / system).

## Colour roles (`GrillColors`, light / dark)
| Role | Light | Dark | Use |
|---|---|---|---|
| groundTop / bg / groundBottom | #8FD0FF / #D8EEFF / #F4FAFF | #16375F / #0C1B2E / #070F1A | the sky: vertical fade to the horizon, a drifting `sun`, soft `haze` clouds, a white studio light (`spot`, warming to `spotWarm` while cooking, when the sky also warms toward a sunset), a faint `vignette` |
| surface | #FFFFFF | #112235 | cards (tone only, no outline, no shadow) |
| surfaceAlt | #F0F7FE | #172B41 | wells: segmented tracks, fields, steppers, toggles |
| primary / primaryHi | #1F7AD1 / #5AB2F6 | #7CC2FF / #A9D8FF | azure: selection, primary action (selected controls run primaryHi to primary) |
| heat / heatFill | #D24A0E / #FF8A3D | #FFB27A / #FF8F4D | cooking, pull point, preheat, the open-lid warning |
| ok | #0B8A4E | #5BE49B | target reached, connected |
| danger | #E5372B | #FF5A52 | Stop only |
| ble / cloud | #1F6FEB / #7A4CE0 | #8EBBFF / #C3AEFF | which channel a reading came from |

Containers pair with their own dark tone for text (`onHeatContainer`, `onOkContainer`, ...), never grey.

## Type
System Roboto through the Material 3 scale, Medium for headings. Every changing number uses tabular figures (`Numeric`). The live probe reading during a cook is the largest number: `LiveNumeral`, 52sp. The grill's own display on the photo uses monospace bold, like the real LCD.

## Components
- **Card**: 16dp radius, 18dp padding, tone only. Never nested.
- **Segmented**: tonal track, selected option lifted onto primary; replaces Material's outlined segmented buttons.
- **Pill**: status label with optional dot (pulses while heating).
- **Field**: filled text field without underline or outline.
- **Bottom action bar**: always present, one action. Cooking: Stop (red, asks once). Sending a start: a busy button. Ready: "light the grill" with the plan above (asks once, with a summary). Panel off: switch it on (asks once; Bluetooth or cloud). Unheard: the reason, no button.
- **Top bar**: title, the connection pill (dot + Bluetooth + cloud glyphs, lit when live) and settings. The pill opens a dropdown with each channel's state; nothing about connection sits on the page itself.
- **Grill hero**: closed and open-lid pictures (our own vector drawings; a local build may swap in photos under `app/src/debug/res`, never committed) share one 720×715 canvas at one scale, aligned on the control panel's display, so the lid crossfade never resizes the grill. Grey and dim when off; a breathing warm glow and rising heat lines (grey smoke with Woodfire) while heating; a live display drawn over the photo's LCD (HI / 180 and PrE / 0:23).
- **Heat card**: the grill's sensors (grill · air · smoke box) under the photo in every state, fixed order, "—" when a value is missing (never hidden). During preheat/ignition it grows: the grill reading as the big number, a bar from the preheat-start reading to the target, ETA, air and smoke small, and the skip button (preheat or ignition) inside it, away from Stop.
- **Probe strip**: one short tile per probe (name, reading), plus a tile to add a Tempwise.
- **Cook planner, as the grill's panel**: mode tiles; a readout of the cook's 2–3 numbers (heat · what ends it · cap), one selected (primaryContainer + caret); one control for the selected number: `LevelTrack` (grill levels with their real °C, doneness presets) or `DialRuler` (a ruler dragged like a knob, a haptic tick per step, labels on major ticks, pull/target marks); source chips choose probe or timer; Woodfire and skip preheat as option tiles. No time-limit switch: the cap is dialled down from the mode's maximum.
- **Starting card**: in the planner's place from the tap until the grill reports heat ("sending…" then "received, waiting…") with the seconds elapsed; after 15 s without an answer it says what to check (on, lid shut, in range) and offers Cancel.
- **Lid banner**: heatContainer, under the grill, whenever the grill holds a start for its lid (or reports "waiting for the lid"); also an alert notification. The one loud warning on the page.
- **Live probe card + doneness track**: reading, pull point (target minus the owner's carryover, a setting) and target; ETA pill in primaryContainer (green is only "reached"); footer with the auto-stop and the cap. **Time card**: the countdown as the big number, the clock time it ends, a bar.
- **Change card**: the running plan in the planner's own cells; tapping one opens the planner in place with that number selected; Update stays off until something changed.
- **Finished card**: okContainer, stays until closed or the next start: how it ended and when, the probe now against the moment the heat stopped (its real rest-time climb), the cook time.
- **Settings**: grouped rows (icon tile, name, value or control). Short choices on the row (switch, mini segmented, mini stepper); longer ones in a bottom panel.

## Motion
The probe numeral rolls up or down to each new reading, and the doneness track eases to it. The grill photo desaturates when the panel switches off and lights back up when it wakes; while heating, a glow breathes behind it and heat lines rise off the hood. The page ground's light drifts slowly and warms from blue to orange while cooking. Everything else is a 180ms colour change.

## Layout
Hebrew, forced RTL. 16dp side gutters, 12dp between cards, 48dp minimum targets (56dp for the main actions). Edge-to-edge with system bar insets; status bar icons follow the in-app theme.
