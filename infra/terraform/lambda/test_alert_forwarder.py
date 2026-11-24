"""alert_forwarder 단위 시험 — AWS·네트워크 없이 돈다.

    python -m unittest infra/terraform/lambda/test_alert_forwarder.py   (저장소 루트에서)
"""
import json
import os
import sys
import types
import unittest
import urllib.error
from email.message import Message
from unittest import mock

# boto3 가 없는 환경(CI·개발 장비)에서도 import 되게 한다. 실제 호출은 시험에서 치환한다.
if "boto3" not in sys.modules:
    _boto3_stub = types.ModuleType("boto3")
    setattr(_boto3_stub, "client", lambda *args, **kwargs: None)
    sys.modules["boto3"] = _boto3_stub
sys.path.insert(0, os.path.dirname(__file__))
import alert_forwarder as fw  # noqa: E402

ALARM = {
    "AlarmName": "shadowfit-outbox-failed-new",
    "AlarmDescription": "outbox 행이 FAILED 가 됐다",
    "NewStateValue": "ALARM",
    "NewStateReason": "Threshold Crossed: 1 datapoint [2.0] was greater than the threshold (0.0).",
    "StateChangeTime": "2026-11-24T12:00:00.000+0000",
}


def sns_event(message: str) -> dict:
    return {"Records": [{"Sns": {"Message": message}}]}


class FormatTest(unittest.TestCase):
    def test_alarm_message_has_name_state_reason(self):
        text = fw.format_message(ALARM)
        self.assertIn("🔴 [ALARM] shadowfit-outbox-failed-new", text)
        self.assertIn("사유: Threshold Crossed", text)

    def test_ok_state_uses_green(self):
        self.assertTrue(fw.format_message({**ALARM, "NewStateValue": "OK"}).startswith("🟢"))

    def test_missing_fields_do_not_crash(self):
        self.assertIn("(이름 없음)", fw.format_message({}))


class PayloadTest(unittest.TestCase):
    def test_discord_uses_content(self):
        self.assertEqual(fw.build_payload("https://discord.com/api/webhooks/1/x", "hi"), {"content": "hi"})

    def test_slack_uses_text(self):
        self.assertEqual(fw.build_payload("https://hooks.slack.com/services/T/B/x", "hi"), {"text": "hi"})

    def test_lookalike_host_is_not_discord(self):
        # 호스트를 접미사로만 보면 evil-discord.com.example 같은 것이 통과한다.
        self.assertEqual(fw.build_payload("https://discord.com.evil.example/x", "hi"), {"text": "hi"})

    def test_discord_truncates_to_limit(self):
        payload = fw.build_payload("https://discord.com/api/webhooks/1/x", "a" * 5000)
        self.assertLessEqual(len(payload["content"]), 2000)


class HandlerTest(unittest.TestCase):
    URL = "https://hooks.slack.com/services/T/B/secret"

    def setUp(self):
        os.environ["WEBHOOK_PARAM"] = "/shadowfit/alerts/webhook-url"
        patcher = mock.patch.object(fw, "_webhook_url", return_value=self.URL)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_posts_formatted_message(self):
        with mock.patch.object(fw, "post") as post:
            out = fw.handler(sns_event(json.dumps(ALARM)), None)
        self.assertEqual(out, {"sent": 1})
        url, payload = post.call_args.args
        self.assertEqual(url, self.URL)
        self.assertIn("[ALARM]", payload["text"])

    def test_non_json_message_is_still_forwarded(self):
        with mock.patch.object(fw, "post") as post:
            fw.handler(sns_event("그냥 문자열"), None)
        self.assertIn("그냥 문자열", post.call_args.args[1]["text"])

    def test_http_error_raises_without_leaking_url(self):
        err = urllib.error.HTTPError(self.URL, 404, "Not Found", Message(), None)
        with mock.patch.object(fw, "post", side_effect=err):
            with self.assertRaises(RuntimeError) as ctx:
                fw.handler(sns_event(json.dumps(ALARM)), None)
        self.assertIn("404", str(ctx.exception))
        self.assertNotIn("secret", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
