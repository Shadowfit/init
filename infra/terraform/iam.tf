locals {
  # 시크릿은 Terraform 이 만들지 않는다 — 값이 state 에 남지 않게 하려는 것이다.
  # 사람이 `aws ssm put-parameter --type SecureString` 으로 넣는다 (infra/README.md).
  ssm_param_prefix = "/shadowfit/prod"
}

# ── 호스트(EC2)가 쓰는 역할 ────────────────────────────────────────────────────
data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "host" {
  name               = "${var.name}-host"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

# SSM Agent 가 Run Command 를 받고 세션을 맺는 데 필요한 관리형 정책.
resource "aws_iam_role_policy_attachment" "host_ssm_core" {
  role       = aws_iam_role.host.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

data "aws_iam_policy_document" "host" {
  statement {
    sid       = "ReadAppSecrets"
    actions   = ["ssm:GetParametersByPath", "ssm:GetParameter", "ssm:GetParameters"]
    resources = ["arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter${local.ssm_param_prefix}/*"]
  }

  # 쓰기 전용 — 호스트가 침해돼도 백업을 지우거나 읽어 가지 못한다. 복구는 사람이 자기 자격으로 한다.
  statement {
    sid       = "WriteBackups"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.backup.arn}/mysql/*"]
  }

  statement {
    sid       = "ReadDeployBundles"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.deploy.arn}/bundles/*"]
  }

  # outbox FAILED 지표 발행(infra/host/alarm). PutMetricData 는 리소스 단위 제한이 없어서 네임스페이스로 좁힌다.
  statement {
    sid       = "PublishAppMetrics"
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = ["ShadowFit"]
    }
  }
}

resource "aws_iam_role_policy" "host" {
  name   = "app-config-and-bundles"
  role   = aws_iam_role.host.id
  policy = data.aws_iam_policy_document.host.json
}

resource "aws_iam_instance_profile" "host" {
  name = "${var.name}-host"
  role = aws_iam_role.host.name
}

# ── GitHub Actions 가 맡는 역할 (OIDC, 장기 키 없음) ───────────────────────────
resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

locals {
  github_oidc_arn = var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn
}

data "aws_iam_policy_document" "github_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # main 브랜치의 워크플로만. PR·포크가 운영 호스트를 건드리지 못하게 한다.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name               = "${var.name}-github-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_assume.json
}

data "aws_iam_policy_document" "github_deploy" {
  statement {
    sid       = "UploadBundle"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.deploy.arn}/bundles/*"]
  }

  # 이 인스턴스에, 이 문서(AWS-RunShellScript)로만 명령을 보낼 수 있다.
  statement {
    sid     = "RunCommandOnHost"
    actions = ["ssm:SendCommand"]
    resources = [
      "arn:aws:ec2:${var.region}:${data.aws_caller_identity.current.account_id}:instance/${aws_instance.app.id}",
      "arn:aws:ssm:${var.region}::document/AWS-RunShellScript",
    ]
  }

  statement {
    sid       = "ReadCommandResult"
    actions   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  name   = "deploy-via-ssm"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.github_deploy.json
}
