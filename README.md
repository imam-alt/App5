# App5
Signal-aware collision warning prototype for Android.

## What this app does
This repository now contains an Android prototype that fuses:
- Wi‑Fi scan strength changes
- BLE scan strength changes
- cellular signal instability
- live camera analysis in the center lane

The app raises a vibration warning when radio proximity signals and the camera both suggest there may be an obstacle ahead.

## Important technical note
A normal Android app **cannot** access raw radio reflections from Wi‑Fi, Bluetooth, or GSM the way a real radar system would. Standard Android APIs only expose scan results, RSSI, and telephony signal information. Because of that, this project implements a **practical collision-warning prototype**, not true radio-reflection tomography.

The current algorithm works like this:
1. Estimate nearby signal intensity from Wi‑Fi and BLE RSSI.
2. Track cellular signal instability as a weak environmental-change hint.
3. Validate the forward path using camera texture / edge / looming analysis.
4. Fuse all scores into a risk level.
5. Vibrate the phone if the fused risk crosses the warning threshold.

## Project structure
- `app/src/main/java/com/imam/app5/MainActivity.kt` — UI, permission flow, vibration, camera boot
- `app/src/main/java/com/imam/app5/SignalScanner.kt` — Wi‑Fi, BLE, and cell sampling
- `app/src/main/java/com/imam/app5/CameraObstacleAnalyzer.kt` — camera-based obstacle heuristics
- `app/src/main/java/com/imam/app5/SignalFusionEngine.kt` — fusion and warning thresholds

## Build
Open the repo in Android Studio and sync Gradle. Then run on a real Android phone with:
- camera
- Wi‑Fi enabled
- Bluetooth enabled
- location permission enabled

## Current limitations
- Not true radar mapping
- Wi‑Fi scans are throttled by Android on many devices
- BLE and Wi‑Fi RSSI are noisy
- Cellular data is useful only as a weak context signal
- Camera validation is heuristic and can be fooled by low-texture surfaces

## Suggested next steps
- Add ARCore depth / motion tracking for stronger visual validation
- Add a calibration mode per device
- Learn thresholds from recorded walk-test sessions
- Replace heuristic fusion with a lightweight on-device model
