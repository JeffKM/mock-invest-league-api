#!/bin/bash
# 지표 그래프 끄기. 수집한 데이터(볼륨)는 남긴다. 지우려면: docker compose -f monitoring/docker-compose.yml down -v
cd "$(dirname "$0")" && docker compose down
