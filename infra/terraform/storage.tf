data "aws_caller_identity" "current" {}

# 배포 번들(compose·nginx 설정·host 스크립트)을 CI 가 올리고 호스트가 내려받는 곳.
# 이미지는 GHCR 에 있으므로 여기엔 작은 텍스트 파일만 들어간다.
resource "aws_s3_bucket" "deploy" {
  bucket = "${var.name}-deploy-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_public_access_block" "deploy" {
  bucket                  = aws_s3_bucket.deploy.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "deploy" {
  bucket = aws_s3_bucket.deploy.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "deploy" {
  bucket = aws_s3_bucket.deploy.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "deploy" {
  bucket = aws_s3_bucket.deploy.id

  rule {
    id     = "expire-old-bundles"
    status = "Enabled"

    filter {
      prefix = "bundles/"
    }

    expiration {
      days = 30
    }

    noncurrent_version_expiration {
      noncurrent_days = 7
    }
  }
}

# ── 백업 버킷 ────────────────────────────────────────────────────────────────
# 호스트(와 그 EBS)를 통째로 잃어도 남아야 하므로 인스턴스 밖에 둔다.
# 논리 덤프(scripts/backup/mysql-backup.sh)가 매일 올린다. 호스트 역할은 쓰기만 되고 지우지 못한다.
resource "aws_s3_bucket" "backup" {
  bucket = "${var.name}-backup-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_public_access_block" "backup" {
  bucket                  = aws_s3_bucket.backup.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "backup" {
  bucket = aws_s3_bucket.backup.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 버전 관리: 덮어쓰기나 (호스트 역할이 못 하지만) 실수 삭제에서도 직전 상태를 살린다.
resource "aws_s3_bucket_versioning" "backup" {
  bucket = aws_s3_bucket.backup.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "backup" {
  bucket = aws_s3_bucket.backup.id

  rule {
    id     = "expire-old-dumps"
    status = "Enabled"

    filter {
      prefix = "mysql/"
    }

    # 보관 기간은 임의로 정한 값이다 — 실제 요구(규정·복구 창)가 생기면 그때 근거와 함께 바꾼다.
    expiration {
      days = var.backup_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 7
    }
  }
}

# 데이터 볼륨은 인스턴스와 분리한다. 인스턴스를 교체·삭제해도 MySQL 데이터는 남는다.
# ⚠️ `terraform destroy`(전체)는 이 볼륨도 지운다. 계산 자원만 내리려면
#    infra/README.md 의 "비용 줄이기" 절차(-target)를 쓴다.
resource "aws_ebs_volume" "data" {
  availability_zone = var.availability_zone
  size              = var.data_volume_gb
  type              = "gp3"
  encrypted         = true

  tags = { Name = "${var.name}-data" }
}

resource "aws_volume_attachment" "data" {
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.data.id
  instance_id = aws_instance.app.id
}
