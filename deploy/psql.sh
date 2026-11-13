#!/bin/bash
# 서버에서 RDS 에 psql 로 붙는다. .env 의 DB_URL·DB_USERNAME·DB_PASSWORD 를 컨테이너 환경 변수로만 넘겨 화면·셸 기록에 남지 않는다.
# 사용(서버의 /opt/league 에서): ./psql.sh -f /sql/autovacuum.sql      ./psql.sh -c "select 1"
# 처음 한 번 postgres:16-alpine 이미지(약 100MB)를 받는다
cd "$(dirname "$0")"
exec docker run --rm -i --env-file .env -v "$PWD/sql:/sql:ro" postgres:16-alpine \
  sh -c 'PGPASSWORD="$DB_PASSWORD" exec psql "${DB_URL#jdbc:}" -U "$DB_USERNAME" -P pager=off "$@"' sh "$@"
