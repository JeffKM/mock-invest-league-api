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

# 6주차⑪ P2: 연결 추적 표(conntrack). Docker 는 80 → 컨테이너 8080 으로 주소를 바꿔 넘기므로 커널이 연결마다 한 칸을 쓴다.
# 메모리 1GB 인스턴스의 기본값은 7,680칸이고, 끊긴 연결도 TIME_WAIT 로 120초 남는다. 덜어내기 503 은 톰캣이 연결을 닫으므로
# 몰림 때 새 연결이 초당 수백 개 생겨 표가 찼고, 커널이 새 연결을 조용히 버렸다(dmesg "nf_conntrack: table full, dropping packet",
# 연결 맺기 최대 29초). 65,536칸(가득 차도 약 20MB), TIME_WAIT 30초. 부팅 때 모듈이 먼저 올라와야 sysctl 이 먹는다
echo nf_conntrack > /etc/modules-load.d/nf_conntrack.conf
modprobe nf_conntrack
cat > /etc/sysctl.d/90-league.conf <<'CONF'
net.netfilter.nf_conntrack_max = 65536
net.netfilter.nf_conntrack_tcp_timeout_time_wait = 30
CONF
sysctl --system > /dev/null

mkdir -p /opt/league
chown ec2-user:ec2-user /opt/league
