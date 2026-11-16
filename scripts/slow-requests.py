# 6주차⑦ 구간마다 느린 요청 수(0.11·0.54·1.07초 넘음, 히스토그램 구간 경계)와 전체 요청 수를 25초 창으로 센다(맥 Prometheus).
# 사용: python3 scripts/slow-requests.py 2026-11-16 이름=HH:MM:SS(UTC) ...   최고 p99 한 점보다 덜 흔들린다
import json,sys,urllib.request,urllib.parse,datetime
day=sys.argv[1]
def q(expr,t):
  u="http://localhost:9090/api/v1/query?"+urllib.parse.urlencode({"query":expr,"time":t})
  r=json.load(urllib.request.urlopen(u))["data"]["result"]
  return float(r[0]["value"][1]) if r else float("nan")
W=25
for a in sys.argv[2:]:
  name,hms=a.split("=")
  t=datetime.datetime.fromisoformat(f"{day}T{hms}+00:00").timestamp()+W
  sel='uri!~"/actuator.*"'
  tot=q(f'sum(increase(http_server_requests_seconds_count{{{sel}}}[{W}s]))',t)
  out=[f"{name:6} 전체={tot:5.0f}"]
  for le in ("0.111848106","0.536870911","1.073741824"):
    b=q(f'sum(increase(http_server_requests_seconds_bucket{{{sel},le="{le}"}}[{W}s]))',t)
    out.append(f">{float(le):.2f}s={tot-b:4.0f}")
  print("  ".join(out))
