import 'package:flutter/services.dart';

/// Modern, deliberate haptics. This is an image-viewing app, so feedback is
/// kept rare and purposeful instead of firing on every tap:
///
/// - tap:       subtle tick for explicit actions (tags, chips)
/// - pageFlip:  crisp tick when turning a reading page
/// - confirm:   medium pulse when starting/stopping a download etc.
/// - success:   light pulse after a task completes
/// - error:     heavy pulse for destructive confirms (deletes)
/// - longPress: medium tick for long-press actions
class Haptics {
  static bool enabled = true;

  static void tap() {
    if (enabled) HapticFeedback.selectionClick();
  }

  static void pageFlip() {
    if (enabled) HapticFeedback.lightImpact();
  }

  static void confirm() {
    if (enabled) HapticFeedback.mediumImpact();
  }

  static void success() {
    if (enabled) HapticFeedback.lightImpact();
  }

  static void error() {
    if (enabled) HapticFeedback.heavyImpact();
  }

  static void longPress() {
    if (enabled) HapticFeedback.mediumImpact();
  }
}
