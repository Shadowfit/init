resource "aws_vpc" "main" {
  cidr_block           = "10.20.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = var.name }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = var.name }
}

# 단일 노드라 서브넷 하나면 된다. HA 단계에서 두 번째 AZ 서브넷을 추가한다.
resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.20.1.0/24"
  availability_zone       = var.availability_zone
  map_public_ip_on_launch = false # 고정 IP 는 aws_eip 로 붙인다

  tags = { Name = "${var.name}-public" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${var.name}-public" }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "app" {
  name_prefix = "${var.name}-app-"
  description = "ShadowFit app host: REST 8080 and AI HTTP 8000 only. No SSH, no MySQL."
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.name}-app" }

  lifecycle {
    create_before_destroy = true
  }
}

# 3306(MySQL)·6565/8585(gRPC)·9090(actuator)는 의도적으로 열지 않는다.
# 컨테이너끼리는 compose 네트워크 안에서 서비스 이름으로 붙는다.
resource "aws_vpc_security_group_ingress_rule" "rest" {
  for_each = toset(var.allowed_cidrs)

  security_group_id = aws_security_group.app.id
  cidr_ipv4         = each.value
  from_port         = 8080
  to_port           = 8080
  ip_protocol       = "tcp"
  description       = "backend REST"
}

resource "aws_vpc_security_group_ingress_rule" "ai_http" {
  for_each = toset(var.allowed_cidrs)

  security_group_id = aws_security_group.app.id
  cidr_ipv4         = each.value
  from_port         = 8000
  to_port           = 8000
  ip_protocol       = "tcp"
  description       = "AI HTTP via nginx (session-sticky)"
}

# 아웃바운드는 전부 연다: GHCR·SSM·S3·OpenAI·Expo 푸시 등이 모두 외부로 나간다.
resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.app.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}
