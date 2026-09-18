# Performance-First iOS-Style Interaction Directives (Jetpack Compose)

## 1. Rendering & Material Constraints (Zero GPU Overhead)
- **Strictly Forbid Real-Time Blur**: Eliminate `RenderEffect.createBlurEffect`, runtime Gaussian blur libraries (e.g., Haze), and offscreen buffer convolutions.
- **Lightweight Surface Alternative**:
  - Floating HUD islands and bottom bars must use high-opacity solid dark backgrounds (e.g., `Color(0xF01C1C1E)`, 94% opacity).
  - Simulate depth and separation with a 0.5dp hairline specular border: `Modifier.border(0.5.dp, Color.White.copy(alpha = 0.12f), shape)`.
  - Ensure single-pass draw call execution; eliminate all offscreen layer allocations.

## 2. Dynamics & Physics Models (Pure Scalar Calculations over Mechanical Tweens)
- **Forbid Fixed-Duration Tweens**: Strictly ban `tween()`. Enforce `spring()` for all translations, scales, and alpha transitions:
  - Quick snap / menu dismiss: `spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioNoBouncy)`
  - Elastic bounce / subtle breathing: `spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy)`
- **Unconditional Interruptibility**: Any settling animation must immediately yield to incoming touch inputs without locking touch gestures.
- **Boundary Rubber Banding**: When dragging past boundaries (gallery ends or list limits), displacement must strictly follow the hyperbolic asymptotic formula:
  `offset = (rawDrag * 0.55f * viewDimension) / (viewDimension + 0.55f * rawDrag)`
  On release, inject the instantaneous velocity into a spring spec to smoothly animate back to the resting position.

## 3. Direct Manipulation & Zero Perceived Latency
- **Eliminate Tap Latency**: Bypass default tap-delay and ripple fade-in delays to ensure immediate tactile response.
- **Instant Press Depression**:
  - On pointer down (`Press` state): the target element instantly scales down slightly to `0.97f` or dims to `0.75f` opacity.
  - On pointer up: rapid spring recovery. Render visual feedback first; handle asynchronous operations in the background.

## 4. Hardware Micro-Haptics Loop (Zero Render Pipeline Cost)
Call Android platform haptics directly without triggering UI redraws:
- **Granular Tick (`EFFECT_TICK`)**:
  - Trigger a low-latency micro-pulse during slider scrubbing, page flips, and discrete step changes:
    `vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))`
- **Impact Click (`EFFECT_HEAVY_CLICK`)**:
  - Trigger a crisp tactile impact on boundary wall collisions, confirmed action gestures, and long-press menu triggers:
    `vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))`

## 5. Layout Stability & Micro-Typography (Zero-Jitter Typography)
- **Tabular Numbers Constraint**: All dynamic numeric labels (e.g., reader page index `12/150`, download percentages, timestamps) must enforce OpenType Tabular Figures:
  ```kotlin
  style = TextStyle(
      fontFeatureSettings = "tnum"
  )