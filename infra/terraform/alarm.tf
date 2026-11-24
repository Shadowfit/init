# outbox FAILED 알람 → SNS → Lambda → Slack/Discord 웹훅.
#
# 흐름: 호스트의 shadowfit-outbox-metric.timer 가 1분마다 CloudWatch 에 ShadowFit/OutboxFailedNew 를 올리고,
#       아래 알람이 그 값을 보고 SNS 로 알린다. SNS 는 웹훅 형식을 몰라서 Lambda 가 한 번 번역한다.
#
# 웹훅 URL 은 Terraform 이 만들지 않는다 — 값이 state 에 남지 않게 하려는 것이다(iam.tf 머리말과 같은 방침).
# 사람이 넣는다(infra/README.md 「알람」). 값은 /shadowfit/prod/ 밖에 둔다: 그 아래는 deploy.sh 가 전부
# 앱 환경변수로 읽어 들이므로 웹훅 URL 이 백엔드 컨테이너로 새어 들어간다.

locals {
  alert_webhook_param = "/shadowfit/alerts/webhook-url"
}

resource "aws_sns_topic" "alerts" {
  name = "${var.name}-alerts"
}

# ── 웹훅 전달 Lambda ───────────────────────────────────────────────────────────
data "archive_file" "alert_forwarder" {
  type        = "zip"
  source_file = "${path.module}/lambda/alert_forwarder.py"
  output_path = "${path.module}/.build/alert_forwarder.zip"
}

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "alert_forwarder" {
  name               = "${var.name}-alert-forwarder"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

resource "aws_iam_role_policy_attachment" "alert_forwarder_logs" {
  role       = aws_iam_role.alert_forwarder.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

data "aws_iam_policy_document" "alert_forwarder" {
  statement {
    sid       = "ReadWebhookUrl"
    actions   = ["ssm:GetParameter"]
    resources = ["arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter${local.alert_webhook_param}"]
  }
}

resource "aws_iam_role_policy" "alert_forwarder" {
  name   = "read-webhook-url"
  role   = aws_iam_role.alert_forwarder.id
  policy = data.aws_iam_policy_document.alert_forwarder.json
}

resource "aws_lambda_function" "alert_forwarder" {
  function_name    = "${var.name}-alert-forwarder"
  role             = aws_iam_role.alert_forwarder.arn
  runtime          = "python3.12"
  handler          = "alert_forwarder.handler"
  filename         = data.archive_file.alert_forwarder.output_path
  source_code_hash = data.archive_file.alert_forwarder.output_base64sha256
  timeout          = 15
  memory_size      = 128

  environment {
    variables = {
      WEBHOOK_PARAM = local.alert_webhook_param
    }
  }
}

resource "aws_lambda_permission" "sns_invoke" {
  statement_id  = "AllowSnsInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.alert_forwarder.function_name
  principal     = "sns.amazonaws.com"
  source_arn    = aws_sns_topic.alerts.arn
}

resource "aws_sns_topic_subscription" "alert_forwarder" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "lambda"
  endpoint  = aws_lambda_function.alert_forwarder.arn
}

# ── 알람 ───────────────────────────────────────────────────────────────────────
# 1) 새 FAILED 가 생겼다.
#    OutboxFailed(누적)가 아니라 OutboxFailedNew(증가분)에 건다 — FAILED 행은 90일 보관이라 누적값은 한 번
#    오르면 계속 >0 이어서, ALARM 상태에서 생긴 두 번째 실패는 알림이 없다. 증가분은 5분 창마다 0 으로 돌아와
#    OK → ALARM 전이가 실패마다 다시 일어난다. OK 로 돌아올 때는 알리지 않는다(회복이 아니라 «조용한 창»이다).
resource "aws_cloudwatch_metric_alarm" "outbox_failed_new" {
  alarm_name          = "${var.name}-outbox-failed-new"
  alarm_description   = "outbox 이벤트가 재시도를 소진해 FAILED 가 됐다 (탈퇴 후 삭제·AI 통보 등). 확인: outbox_events WHERE status='FAILED'"
  namespace           = "ShadowFit"
  metric_name         = "OutboxFailedNew"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching" # 데이터 없음은 아래 하트비트 알람이 맡는다
  alarm_actions       = [aws_sns_topic.alerts.arn]
}

# 2) 지표가 안 올라온다 — 위 알람의 눈이 멀었다는 뜻이다.
#    호스트 타이머가 죽거나 백엔드 지표를 못 읽으면 지표가 끊기고, 1번 알람은 «문제 없음»으로 남는다.
#    배포 중 백엔드 재기동(수 분)에는 울리지 않게 15분(5분×3) 연속 무데이터일 때만 올린다.
resource "aws_cloudwatch_metric_alarm" "outbox_metric_missing" {
  alarm_name          = "${var.name}-outbox-metric-missing"
  alarm_description   = "outbox FAILED 지표가 15분째 안 올라온다 — 호스트의 shadowfit-outbox-metric.timer 또는 백엔드 확인"
  namespace           = "ShadowFit"
  metric_name         = "OutboxFailed"
  statistic           = "SampleCount"
  period              = 300
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}
