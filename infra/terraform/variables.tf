variable "region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "availability_zone" {
  description = "단일 노드 호스트와 데이터 볼륨이 놓일 AZ. EBS 는 AZ 에 묶이므로 둘이 같아야 한다"
  type        = string
  default     = "ap-northeast-2a"
}

variable "instance_type" {
  description = <<-EOT
    호스트 인스턴스 타입. 기본값 c7i.xlarge(4 vCPU / 8GiB)는 AI 추론 용량을 잰 계열(c7i)과
    맞춘 것이다 (loadtest/results/ai-recalibrate-2026-08-11). 이 크기에서의 동시 세션 수를
    직접 재 본 것은 아니다 — 아래 concurrent_sessions 는 가정값이다.
  EOT
  type        = string
  default     = "c7i.xlarge"
}

variable "vcpus" {
  description = "instance_type 의 vCPU 수. 컨테이너 cpus 한도로 쓰인다 (호스트 논리 코어 수를 넘지 않게 — 이슈 #570). c7i.xlarge = 4"
  type        = number
  default     = 4
}

variable "root_volume_gb" {
  description = "루트 볼륨(OS·Docker 이미지) 크기"
  type        = number
  default     = 30
}

variable "data_volume_gb" {
  description = "MySQL 데이터·기준 영상용 별도 EBS 크기. 합성 1억 행을 올릴 용도가 아니라 서비스 운영 규모 기준이다"
  type        = number
  default     = 50
}

variable "allowed_cidrs" {
  description = "8080(백엔드 REST)·8000(AI HTTP)에 접근을 허용할 CIDR. SSH 는 열지 않는다(SSM 사용)"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "github_repository" {
  description = "배포 워크플로가 AWS 역할을 맡을 수 있는 저장소 (owner/name)"
  type        = string
  default     = "Shadowfit/init"
}

variable "create_github_oidc_provider" {
  description = "계정에 GitHub OIDC provider 가 이미 있으면 false (계정당 하나만 만들 수 있다)"
  type        = bool
  default     = true
}

variable "concurrent_sessions" {
  description = <<-EOT
    AI 컨테이너 메모리 한도를 유도할 동시 세션 수(가정값).
    docs/decisions/detector-pool-ceiling-formula.md : 한도 = 708MiB + 106.74MiB × 세션 수.
    그 공식은 x86 c7i.4xlarge 에서 맞춘 실측 적합이며 안전계수가 아니다 — 여유를 더 얹을지는
    실측 후 정한다(docs/19-deployment.md §2.1).
  EOT
  type        = number
  default     = 20
}

variable "backend_mem_mib" {
  description = "백엔드 컨테이너 메모리 한도(MiB). .env.example 의 개발 기본값과 같다 — 실측해서 고른 값이 아니다"
  type        = number
  default     = 2048
}

variable "innodb_buffer_pool" {
  description = "MySQL 버퍼풀 크기. docker-compose.prod.yml 기본값과 같다 — 실측해서 고른 값이 아니다"
  type        = string
  default     = "1G"
}

variable "backup_retention_days" {
  description = "S3 에 보관할 덤프 일수. 요구 사항에서 유도한 값이 아니라 임의의 시작값이다"
  type        = number
  default     = 30
}

variable "name" {
  description = "리소스 이름 접두사"
  type        = string
  default     = "shadowfit-prod"
}
