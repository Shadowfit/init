import unittest

from app.core.dtw_calculator import (
    classify_sync_haptic_cue,
    classify_sync_visual_cue,
    resolve_sync_thresholds,
)
from app.utils.constants import DEFAULT_SYNC_GOOD, DEFAULT_SYNC_WARN


class SyncFeedbackTests(unittest.TestCase):
    """기본 임계값(양호 80 · 주의 60, 2026-10-21) 기준. 예전엔 70/40 고정이었다."""

    def test_high_sync_is_green_and_no_haptic(self) -> None:
        visual = classify_sync_visual_cue(82)
        haptic = classify_sync_haptic_cue(82)

        self.assertEqual(visual.zone, "green")
        self.assertFalse(visual.flashing)
        self.assertEqual(haptic.pattern, "off")
        self.assertFalse(haptic.enabled)

    def test_mid_sync_is_orange_with_light_haptic(self) -> None:
        visual = classify_sync_visual_cue(70)
        haptic = classify_sync_haptic_cue(70)

        self.assertEqual(visual.zone, "orange")
        self.assertFalse(visual.flashing)
        self.assertEqual(haptic.pattern, "light_repeat")
        self.assertTrue(haptic.enabled)
        self.assertEqual(haptic.message, "60~80% 구간 유지 시 가벼운 반복 진동")

    def test_low_sync_is_red_flash_with_warning_haptic(self) -> None:
        visual = classify_sync_visual_cue(55)
        haptic = classify_sync_haptic_cue(55)

        self.assertEqual(visual.zone, "red")
        self.assertTrue(visual.flashing)
        self.assertEqual(visual.animation, "twinkle_flash")
        self.assertEqual(haptic.pattern, "warning_repeat")
        self.assertTrue(haptic.enabled)
        self.assertEqual(haptic.message, "60% 미만 진입 시 강한 경고 진동")

    def test_boundaries_are_inclusive_at_good_and_warn(self) -> None:
        self.assertEqual(classify_sync_visual_cue(DEFAULT_SYNC_GOOD).zone, "green")
        self.assertEqual(classify_sync_visual_cue(DEFAULT_SYNC_WARN).zone, "orange")
        self.assertEqual(classify_sync_haptic_cue(DEFAULT_SYNC_GOOD).pattern, "off")
        self.assertEqual(classify_sync_haptic_cue(DEFAULT_SYNC_WARN).pattern, "light_repeat")


class GivenThresholdTests(unittest.TestCase):
    """운동별 임계값 한 쌍을 주면 그 값으로 가른다 (2026-10-21)."""

    def test_given_good_and_warn_move_the_zones(self) -> None:
        # 기본값(80/60)이면 70 은 주황이지만, 양호 65 · 주의 40 이면 초록이다.
        self.assertEqual(classify_sync_visual_cue(70, good=65, warn=40).zone, "green")
        self.assertEqual(classify_sync_haptic_cue(70, good=65, warn=40).pattern, "off")
        # 양호 90 · 주의 75 면 70 은 빨강이다.
        self.assertEqual(classify_sync_visual_cue(70, good=90, warn=75).zone, "red")
        haptic = classify_sync_haptic_cue(70, good=90, warn=75)
        self.assertEqual(haptic.pattern, "warning_repeat")
        self.assertEqual(haptic.message, "75% 미만 진입 시 강한 경고 진동")

    def test_zero_thresholds_fall_back_to_defaults(self) -> None:
        # proto3 에서 «안 보냄» 은 0.0 — 그러면 80/60 으로 가른다.
        self.assertEqual(classify_sync_visual_cue(70, good=0, warn=0).zone, "orange")
        self.assertEqual(classify_sync_visual_cue(82, good=0, warn=0).zone, "green")
        self.assertEqual(classify_sync_haptic_cue(55, good=0, warn=0).pattern, "warning_repeat")


class ResolveSyncThresholdsTests(unittest.TestCase):
    def test_valid_pair_is_kept(self) -> None:
        self.assertEqual(resolve_sync_thresholds(85.0, 65.0), (85.0, 65.0))
        self.assertEqual(resolve_sync_thresholds(100.0, 0.5), (100.0, 0.5))

    def test_unset_or_out_of_range_fields_take_their_default(self) -> None:
        defaults = (DEFAULT_SYNC_GOOD, DEFAULT_SYNC_WARN)
        self.assertEqual(resolve_sync_thresholds(0.0, 0.0), defaults)
        self.assertEqual(resolve_sync_thresholds(None, None), defaults)
        self.assertEqual(resolve_sync_thresholds(-5.0, 130.0), defaults)
        self.assertEqual(resolve_sync_thresholds(float("nan"), float("inf")), defaults)
        # 한쪽만 비면 그쪽만 기본값으로 — 나머지는 살린다.
        self.assertEqual(resolve_sync_thresholds(90.0, 0.0), (90.0, DEFAULT_SYNC_WARN))
        self.assertEqual(resolve_sync_thresholds(0.0, 50.0), (DEFAULT_SYNC_GOOD, 50.0))

    def test_inverted_pair_falls_back_to_both_defaults(self) -> None:
        defaults = (DEFAULT_SYNC_GOOD, DEFAULT_SYNC_WARN)
        self.assertEqual(resolve_sync_thresholds(60.0, 70.0), defaults)
        self.assertEqual(resolve_sync_thresholds(70.0, 70.0), defaults)
        # good 만 왔는데 기본 warn(60) 보다 낮으면 뒤집힌 쌍이 된다 → 둘 다 기본값.
        self.assertEqual(resolve_sync_thresholds(50.0, 0.0), defaults)


if __name__ == "__main__":
    unittest.main()
