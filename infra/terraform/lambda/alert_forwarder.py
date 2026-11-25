"""CloudWatch 알람(SNS) → Slack/Discord 웹훅.

SNS 의 HTTPS 구독은 SNS 고유 JSON 을 그대로 POST 하는데 Slack·Discord 웹훅은 그 형식을 받지 않는다.
그 사이를 잇는 것이 이 함수의 전부다.

웹훅 URL 은 시크릿이라 Terraform 이 만들지 않는다 — SSM Parameter Store(SecureString)에서 읽는다
(infra/README.md 「알람」). 코드·환경변수·로그에 URL 을 남기지 않는다.
"""
import json
import os
import urllib.error
import urllib.request
from urllib.parse import urlparse

import boto3

_STATE_MARK = {"ALARM": "🔴", "OK": "🟢", "INSUFFICIENT_DATA": "⚪"}


def _webhook_url() -> str:
    ssm = boto3.client("ssm")
    return ssm.get_parameter(Name=os.environ["WEBHOOK_PARAM"], WithDecryption=True)["Parameter"]["Value"]


def format_message(alarm: dict) -> str:
    """CloudWatch 알람 상태 변화 메시지(SNS Message 의 JSON)를 사람이 읽는 몇 줄로."""
    state = alarm.get("NewStateValue", "?")
    lines = [
        f"{_STATE_MARK.get(state, '❔')} [{state}] {alarm.get('AlarmName', '(이름 없음)')}",
    ]
    if alarm.get("AlarmDescription"):
        lines.append(alarm["AlarmDescription"])
    if alarm.get("NewStateReason"):
        lines.append(f"사유: {alarm['NewStateReason']}")
    if alarm.get("StateChangeTime"):
        lines.append(f"시각: {alarm['StateChangeTime']}")
    return "\n".join(lines)


def build_payload(url: str, text: str) -> dict:
    """Discord 는 content, Slack 은 text. 호스트로 가른다 — 둘 다 넣는 편법은 쓰지 않는다."""
    host = (urlparse(url).hostname or "").lower()
    if host == "discord.com" or host.endswith(".discord.com") or host == "discordapp.com":
        return {"content": text[:1900]}  # Discord 한도 2000자
    return {"text": text}


def post(url: str, payload: dict) -> None:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            # Discord 는 기본 Python-urllib UA 를 403 으로 막는다.
            "User-Agent": "shadowfit-alert-forwarder/1.0",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=10):
        pass


def handler(event, context):
    url = _webhook_url()
    for record in event.get("Records", []):
        raw = record["Sns"]["Message"]
        try:
            text = format_message(json.loads(raw))
        except (ValueError, TypeError):
            # CloudWatch 가 아닌 메시지(콘솔의 SNS 시험 발행 등)도 버리지 않고 그대로 보낸다.
            text = f"ℹ️ {raw}"
        try:
            post(url, build_payload(url, text))
        except urllib.error.HTTPError as e:
            # URL 은 메시지에 넣지 않는다. 예외를 올리면 SNS 가 비동기 호출을 재시도한다.
            raise RuntimeError(f"웹훅이 HTTP {e.code} 로 거절했다") from None
    return {"sent": len(event.get("Records", []))}
