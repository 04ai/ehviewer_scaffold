# Engineering Directives: Compose UI & Rust Boundary

## 1. Threading & Rust Boundary
- **Zero JNI on Main Thread**: Gestures and animations must use local Kotlin scalar math. Never call JNI/Rust during continuous gestures.
- **Dispatch**: All Rust calls must be on `Dispatchers.IO`. Conflate incoming streams (`flow.conflate()`).

## 2. Compose Recomposition Discipline
- **Zero Recomposition on Hot Paths**: Never read drag offsets/scales directly in modifier arguments (`offset()`, `scale()`).
- **Phase Deferral**: Enforce lambda-based layers:
  `Modifier.graphicsLayer { translationY = dynamicOffset; scaleX = pressScale; scaleY = pressScale }`

## 3. Interaction Physics & Visuals
- **No Tweens**: Ban `tween()`. Use `spring()` for all animations.
- **Rubber Band Formula**: `(drag * 0.55f * dim) / (dim + 0.55f * drag)`.
- **Instant Press**: Scale to `0.97f` or alpha to `0.75f` immediately on pointer down; recover via spring.
- **No Runtime Blur**: Ban `RenderEffect.createBlurEffect` and dynamic blur libs. Use solid surface `Color(0xF01C1C1E)` + `Modifier.border(0.5.dp, Color.White.copy(0.12f), shape)`.
- **Typography**: Dynamic numbers must use `TextStyle(fontFeatureSettings = "tnum")`.

## 4. Hardware Haptics
- **Tick (`EFFECT_TICK`)** for continuous scrubbing; **Click (`EFFECT_HEAVY_CLICK`)** for discrete boundary hits.
- **Defensive API Branching**:
  ```kotlin
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator.hasVibrator()) {
      vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
  } else {
      view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
  }