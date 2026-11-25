terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # 5.81 부터 aws_iam_openid_connect_provider 의 thumbprint_list 가 선택값이다 (GitHub OIDC 는 필요 없음)
      version = ">= 5.81, < 6.0"
    }
    # 알람 Lambda 소스를 zip 으로 묶는다 (alarm.tf)
    archive = {
      source  = "hashicorp/archive"
      version = ">= 2.4, < 3.0"
    }
  }

  # 상태 파일은 로컬이 기본이다. 팀이 같이 쓰기 시작하면 아래 주석을 풀어 S3 로 옮긴다.
  # (버킷은 이 Terraform 이 만들 수 없다 — 닭과 달걀. infra/README.md "상태 백엔드" 참조)
  #
  # backend "s3" {
  #   bucket       = "<state-bucket>"
  #   key          = "shadowfit/prod/terraform.tfstate"
  #   region       = "ap-northeast-2"
  #   use_lockfile = true
  # }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "shadowfit"
      ManagedBy = "terraform"
    }
  }
}
