data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

locals {
  # docker-compose.prod.yml 이 필수로 요구하는 한도들. AI 는 공식에서 유도한다.
  ai_mem_mib = ceil(708 + 106.74 * var.concurrent_sessions)
}

resource "aws_instance" "app" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.host.name

  # IMDSv2 만 허용 — 컨테이너 안에서 SSRF 로 호스트 자격증명을 빼가는 경로를 줄인다.
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2 # 컨테이너에서 IMDS 를 쓸 일이 생길 때를 위해 2
  }

  root_block_device {
    volume_size = var.root_volume_gb
    volume_type = "gp3"
    encrypted   = true
  }

  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    region        = var.region
    data_volume   = aws_ebs_volume.data.id
    ssm_prefix    = local.ssm_param_prefix
    ai_mem_mib    = local.ai_mem_mib
    backend_mib   = var.backend_mem_mib
    cpus          = var.vcpus
    buffer_pool   = var.innodb_buffer_pool
    deploy_bucket = aws_s3_bucket.deploy.bucket
    backup_bucket = aws_s3_bucket.backup.bucket
  })

  tags = { Name = var.name }

  lifecycle {
    # AMI 갱신이나 부트스트랩 스크립트 수정으로 돌고 있는 호스트가 재시작·교체되지 않게 한다.
    # user_data 는 최초 부팅 때만 돈다 — 바꾸려면 호스트를 의도적으로 교체(-replace)한다.
    ignore_changes = [ami, user_data]
  }
}

resource "aws_eip" "app" {
  domain   = "vpc"
  instance = aws_instance.app.id

  tags = { Name = var.name }
}
