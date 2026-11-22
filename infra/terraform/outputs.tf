output "instance_id" {
  description = "배포 워크플로가 SSM 명령을 보낼 대상 (GitHub 변수 DEPLOY_INSTANCE_ID)"
  value       = aws_instance.app.id
}

output "public_ip" {
  description = "고정 IP. 앱 API 기준 주소는 http://<이 값>:8080"
  value       = aws_eip.app.public_ip
}

output "deploy_bucket" {
  description = "배포 번들 버킷 (GitHub 변수 DEPLOY_BUCKET)"
  value       = aws_s3_bucket.deploy.bucket
}

output "backup_bucket" {
  description = "MySQL 덤프가 올라가는 버킷"
  value       = aws_s3_bucket.backup.bucket
}

output "github_deploy_role_arn" {
  description = "GitHub Actions 가 OIDC 로 맡는 역할 (GitHub 변수 AWS_DEPLOY_ROLE_ARN)"
  value       = aws_iam_role.github_deploy.arn
}

output "ssm_param_prefix" {
  description = "시크릿을 넣을 SSM Parameter Store 경로"
  value       = local.ssm_param_prefix
}

output "derived_limits" {
  description = "호스트에 주입되는 컨테이너 한도 (variables.tf 의 근거 참고)"
  value = {
    ai_mem_mib      = local.ai_mem_mib
    backend_mem_mib = var.backend_mem_mib
  }
}
