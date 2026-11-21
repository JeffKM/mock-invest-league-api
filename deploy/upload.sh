#!/bin/bash
# 노트북 → EC2 로 배포 파일 올리기. 사용: deploy/upload.sh <EC2 IP> [키 파일]
# compose 파일·deploy.sh·psql.sh·sql/ 은 매번 덮어쓰고, .env 는 서버에 없을 때만 틀을 올린다(채운 값을 덮어쓰지 않게).
set -euo pipefail
HOST="${1:?사용법: deploy/upload.sh <EC2 IP> [키 파일]}"
KEY="${2:-$HOME/.ssh/league.pem}"
DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET="ec2-user@$HOST"

# p6-*.sh·p7-*.sh: 측정 스크립트도 같이 올린다(따로 scp 하다 줄이 꺾여 노트북에서 돈 일이 있었다, 28단계)
scp -i "$KEY" "$DIR/docker-compose.yml" "$DIR/deploy.sh" "$DIR/psql.sh" "$DIR"/p[0-9]*-*.sh "$DIR/mem-watch.sh" "$DIR/stall-watch.sh" "$DIR/syn-capture.sh" "$DIR/syn-analyze.py" "$TARGET:/opt/league/"
scp -i "$KEY" -r "$DIR/sql" "$TARGET:/opt/league/"   # 진단용 SQL(psql.sh 로 돌린다)
if ssh -i "$KEY" "$TARGET" 'test -e /opt/league/.env'; then
  echo ".env 는 이미 있어서 건드리지 않았다"
else
  scp -i "$KEY" "$DIR/.env.example" "$TARGET:/opt/league/.env"
  echo ".env 틀을 올렸다. 값은 서버에서 직접 채운다"
fi
ssh -i "$KEY" "$TARGET" 'chmod 600 /opt/league/.env && chmod +x /opt/league/*.sh && ls -la /opt/league'
