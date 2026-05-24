#!/usr/bin/env bash
# vibi-bff 운영 모니터링 1회 셋업.
#
# 멱등 — 같은 PROJECT_ID 로 다시 돌려도 안전 (이미 있으면 skip).
# 본 스크립트는 cloud-run.sh 와 별개로 *한 번만* 돌리면 됨. metric / alert 정책은 한 번
# 생성되면 모든 후속 배포에서 그대로 적용.
#
# 무엇이 깔리는가:
#   1. log-based metric `separation_queue_stuck` — SeparationQueueRepository 가 stuck/stale
#      reaping 시 출력하는 WARN 로그를 counter 로 변환.
#   2. log-based metric `bff_error_count` — severity=ERROR 로그 카운터. Error Reporting 과
#      별개로 burst 감지용.
#   3. Alert policy 2개:
#      - separation_queue_stuck > 0 in 5min → email
#      - bff_error_count > 10 in 5min → email (정상 운영 시 ERROR 거의 0)
#
# 알람 수신처는 dashboard 의 Notification Channel 을 미리 만들어두고 그 ID 를 NOTIFICATION_CHANNEL
# env 로 넘겨야 함 (script 마지막 echo 가 안내). 채널 없이도 metric 까지는 만들 수 있음.

set -euo pipefail

PROJECT_ID="${PROJECT_ID:?set PROJECT_ID, e.g. export PROJECT_ID=vibi-bff-prod}"
NOTIFICATION_CHANNEL="${NOTIFICATION_CHANNEL:-}"  # 형식: "projects/$PROJECT_ID/notificationChannels/<id>"

command -v gcloud >/dev/null || { echo "❌ gcloud CLI required" >&2; exit 1; }

echo "▶ Project: $PROJECT_ID"
gcloud config set project "$PROJECT_ID" >/dev/null

ensure_metric() {
    local NAME=$1 DESC=$2 FILTER=$3
    if gcloud logging metrics describe "$NAME" >/dev/null 2>&1; then
        echo "  ✓ metric $NAME already exists — skip"
    else
        gcloud logging metrics create "$NAME" \
            --description="$DESC" \
            --log-filter="$FILTER"
        echo "  + metric $NAME created"
    fi
}

echo "▶ Creating log-based metrics…"

# SeparationQueueRepository.kt 의 reapStuckSubmitting / reapStaleQueued 가 출력하는 WARN.
# JSON log 의 message 필드 (logback.xml LogstashEncoder 의 fieldNames.message=message) 매칭.
ensure_metric "separation_queue_stuck" \
    "Separation queue reaper found stuck/stale jobs" \
    'resource.type="cloud_run_revision"
     AND severity="WARNING"
     AND jsonPayload.message=~"Reaped (stuck|stale)"'

# 모든 ERROR 카운터. Error Reporting 이 그루핑까지 해주지만 absolute count 알람은 별개.
ensure_metric "bff_error_count" \
    "All ERROR-level logs from vibi-bff" \
    'resource.type="cloud_run_revision"
     AND resource.labels.service_name="vibi-bff"
     AND severity="ERROR"'

# Cloud Run 잡 자체가 죽거나 OOM 시 출력하는 시스템 로그.
ensure_metric "bff_container_terminated" \
    "Cloud Run container terminated abnormally (OOM, crash, etc)" \
    'resource.type="cloud_run_revision"
     AND resource.labels.service_name="vibi-bff"
     AND (textPayload=~"Container called exit" OR textPayload=~"OOMKilled")'

echo ""
if [[ -z "$NOTIFICATION_CHANNEL" ]]; then
    cat <<EOF
✅ Metrics 생성 완료. Alert 까지 자동화하려면:

  1. Cloud Console → Monitoring → Alerting → Notification channels
     이메일/Slack 채널 만들고 channel ID 복사
     (형식: projects/$PROJECT_ID/notificationChannels/<id>)

  2. NOTIFICATION_CHANNEL 환경변수 set 후 재실행:
     export NOTIFICATION_CHANNEL="projects/$PROJECT_ID/notificationChannels/<id>"
     ./deploy/setup-monitoring.sh

  Alert 없이 metric 만으로도 충분하면 여기서 끝. Console → Logging → Logs Explorer 에서
  "Show metric" 으로 시각화 가능.
EOF
    exit 0
fi

echo "▶ Creating alert policies (channel: $NOTIFICATION_CHANNEL)…"

ensure_alert() {
    local DISPLAY_NAME=$1 POLICY_JSON_PATH=$2
    if gcloud alpha monitoring policies list --format='value(displayName)' 2>/dev/null | grep -Fxq "$DISPLAY_NAME"; then
        echo "  ✓ alert '$DISPLAY_NAME' already exists — skip"
        return
    fi
    gcloud alpha monitoring policies create --policy-from-file="$POLICY_JSON_PATH"
    echo "  + alert '$DISPLAY_NAME' created"
}

# Alert 정책은 임시 JSON 파일에 써서 gcloud 에 전달. 인라인 JSON 은 escape 지옥이라 파일 경유.
TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

cat > "$TMPDIR/queue-stuck.json" <<EOF
{
  "displayName": "vibi-bff: separation queue stuck",
  "combiner": "OR",
  "conditions": [{
    "displayName": "Stuck jobs > 0 in 5min",
    "conditionThreshold": {
      "filter": "metric.type=\"logging.googleapis.com/user/separation_queue_stuck\" AND resource.type=\"cloud_run_revision\"",
      "aggregations": [{ "alignmentPeriod": "300s", "perSeriesAligner": "ALIGN_SUM" }],
      "comparison": "COMPARISON_GT",
      "thresholdValue": 0,
      "duration": "0s"
    }
  }],
  "notificationChannels": ["$NOTIFICATION_CHANNEL"]
}
EOF

cat > "$TMPDIR/error-burst.json" <<EOF
{
  "displayName": "vibi-bff: ERROR burst (>10 in 5min)",
  "combiner": "OR",
  "conditions": [{
    "displayName": "ERROR count > 10 in 5min",
    "conditionThreshold": {
      "filter": "metric.type=\"logging.googleapis.com/user/bff_error_count\" AND resource.type=\"cloud_run_revision\"",
      "aggregations": [{ "alignmentPeriod": "300s", "perSeriesAligner": "ALIGN_SUM" }],
      "comparison": "COMPARISON_GT",
      "thresholdValue": 10,
      "duration": "0s"
    }
  }],
  "notificationChannels": ["$NOTIFICATION_CHANNEL"]
}
EOF

cat > "$TMPDIR/container-terminated.json" <<EOF
{
  "displayName": "vibi-bff: container terminated abnormally",
  "combiner": "OR",
  "conditions": [{
    "displayName": "OOM/crash detected",
    "conditionThreshold": {
      "filter": "metric.type=\"logging.googleapis.com/user/bff_container_terminated\" AND resource.type=\"cloud_run_revision\"",
      "aggregations": [{ "alignmentPeriod": "300s", "perSeriesAligner": "ALIGN_SUM" }],
      "comparison": "COMPARISON_GT",
      "thresholdValue": 0,
      "duration": "0s"
    }
  }],
  "notificationChannels": ["$NOTIFICATION_CHANNEL"]
}
EOF

ensure_alert "vibi-bff: separation queue stuck" "$TMPDIR/queue-stuck.json"
ensure_alert "vibi-bff: ERROR burst (>10 in 5min)" "$TMPDIR/error-burst.json"
ensure_alert "vibi-bff: container terminated abnormally" "$TMPDIR/container-terminated.json"

echo ""
echo "✅ Monitoring setup complete."
echo ""
echo "Verify in Console:"
echo "  • https://console.cloud.google.com/logs/metrics?project=$PROJECT_ID"
echo "  • https://console.cloud.google.com/monitoring/alerting?project=$PROJECT_ID"
echo ""
echo "Error Reporting (auto-populated by severity=ERROR JSON logs from BFF):"
echo "  • https://console.cloud.google.com/errors?project=$PROJECT_ID"
