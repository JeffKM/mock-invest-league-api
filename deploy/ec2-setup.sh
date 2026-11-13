#!/bin/bash
# 5주차② EC2(Amazon Linux 2023) 첫 설정. 인스턴스를 만들 때 "사용자 데이터"에 붙여 넣거나, 접속해서 sudo 로 한 번 실행한다.
set -euo pipefail

# Docker 와 compose 플러그인
dnf install -y docker
systemctl enable --now docker
usermod -aG docker ec2-user
COMPOSE_VERSION=v5.6.1
COMPOSE_URL="https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-x86_64"
mkdir -p /usr/local/lib/docker/cli-plugins
cd /usr/local/lib/docker/cli-plugins
curl -fsSLO "$COMPOSE_URL"
curl -fsSLO "$COMPOSE_URL.sha256"
sha256sum -c docker-compose-linux-x86_64.sha256   # 받은 파일이 공식 해시와 같은지 확인(다르면 set -e 로 멈춘다)
mv docker-compose-linux-x86_64 docker-compose
rm docker-compose-linux-x86_64.sha256
chmod +x docker-compose
cd /

# 메모리 1GB 짜리라 스왑 1GB 를 둔다(JVM 시작이나 이미지 풀 때 잠깐 메모리가 몰리면 OOM 으로 죽지 않게)
if [ ! -f /swapfile ]; then
  dd if=/dev/zero of=/swapfile bs=1M count=1024
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

mkdir -p /opt/league
chown ec2-user:ec2-user /opt/league
