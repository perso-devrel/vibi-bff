#!/usr/bin/env bash
# vibi-bff Cloud Run 배포 스크립트.
# 사전 조건:
#   1. gcloud CLI 설치 + 로그인 완료
#   2. PROJECT_ID 환경변수 또는 아래 PROJECT_ID 값 채우기 (신규 프로젝트 ID)
#   3. vibi-bff/.env 에 채워져 있어야 함:
#      - PERSO_API_KEY / PERSO_SPACE_SEQ / GOOGLE_OAUTH_CLIENT_IDS
#      - DATABASE_URL / DB_USER / DB_PASSWORD (Neon 등 managed Postgres)
#      - APPLE_OAUTH_CLIENT_IDS (선택 — 비우면 Apple 로그인 비활성)
# 멱등 (중복 실행해도 안전): 이미 존재하면 update / skip.

set -euo pipefail

PROJECT_ID="${PROJECT_ID:?set PROJECT_ID env var, e.g. export PROJECT_ID=vibi-bff-prod}"
REGION="${REGION:-us-central1}"
SERVICE_NAME="vibi-bff"
SA_NAME="vibi-bff-sa"
SA_EMAIL="${SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"
# GCS bucket — 큰 산출물(render mp4, dub mp3/mp4, stem) 다운로드를 GCS V4 signed URL
# redirect 로 우회. Cloud Run 인스턴스가 바이트 전송으로 잠기지 않아 max-instances=2
# cap 에서 동시 다운로드 처리량 회복. blank 이면 fallback (respondFile streaming).
GCS_BUCKET="${GCS_BUCKET:-${PROJECT_ID}-vibi-bff-artifacts}"
GCS_SIGNED_URL_TTL_SEC="${GCS_SIGNED_URL_TTL_SEC:-900}"

ENV_FILE="$(dirname "$0")/../.env"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "❌ .env not found at $ENV_FILE — copy .env.example and fill secrets first." >&2
  exit 1
fi
# .env 에서 필요한 값 읽기 (export 되지 않은 라인도 처리)
set -a; source "$ENV_FILE"; set +a

: "${PERSO_API_KEY:?PERSO_API_KEY missing in .env}"
: "${PERSO_SPACE_SEQ:?PERSO_SPACE_SEQ missing in .env}"
: "${GOOGLE_OAUTH_CLIENT_IDS:?GOOGLE_OAUTH_CLIENT_IDS missing in .env}"
: "${APPLE_OAUTH_CLIENT_IDS:=}"  # optional — 빈 값이면 Apple 로그인 비활성
: "${DATABASE_URL:?DATABASE_URL missing in .env (jdbc:postgresql://<host>/<db>?sslmode=require)}"
: "${DB_USER:?DB_USER missing in .env}"
: "${DB_PASSWORD:?DB_PASSWORD missing in .env}"
: "${CORS_ALLOWED_ORIGINS:=}"  # optional — 없으면 빈 값
# Vertex AI 가 활성화된 프로젝트가 Cloud Run 배포 프로젝트와 다를 수 있어 (cross-project) 별도 변수.
# .env 의 GEMINI_PROJECT_ID 가 있으면 그 값, 없으면 PROJECT_ID fallback.
: "${GEMINI_PROJECT_ID:=$PROJECT_ID}"

echo "▶ Using project: $PROJECT_ID  region: $REGION"
gcloud config set project "$PROJECT_ID" >/dev/null

echo "▶ Enabling required APIs (idempotent)…"
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com \
  cloudbuild.googleapis.com \
  aiplatform.googleapis.com \
  storage.googleapis.com \
  iamcredentials.googleapis.com

echo "▶ Ensuring service account ${SA_EMAIL}…"
gcloud iam service-accounts describe "$SA_EMAIL" >/dev/null 2>&1 \
  || gcloud iam service-accounts create "$SA_NAME" \
       --display-name="vibi-bff Cloud Run runtime"

for ROLE in roles/aiplatform.user roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:$SA_EMAIL" \
    --role="$ROLE" \
    --condition=None \
    --quiet >/dev/null
done

echo "▶ Ensuring GCS bucket gs://${GCS_BUCKET}…"
if ! gcloud storage buckets describe "gs://${GCS_BUCKET}" >/dev/null 2>&1; then
  # Uniform bucket-level access — object-level ACL 비활성, IAM 단일 소스. signed URL
  # 자체는 IAM 우회라 외부 접근 영향 없음.
  gcloud storage buckets create "gs://${GCS_BUCKET}" \
    --location="$REGION" \
    --uniform-bucket-level-access \
    --public-access-prevention
fi

# SA 가 bucket 에 object 쓰기 + 메타 읽기 (idempotent upload skip 용).
gcloud storage buckets add-iam-policy-binding "gs://${GCS_BUCKET}" \
  --member="serviceAccount:$SA_EMAIL" \
  --role="roles/storage.objectAdmin" \
  --quiet >/dev/null

# V4 signed URL 발급 권한 — Cloud Run 의 ADC 에는 private key 가 없어 IAM signBlob
# API 로 위임 서명. self-binding (SA 가 자기 자신에게 tokenCreator) 이 표준 패턴.
gcloud iam service-accounts add-iam-policy-binding "$SA_EMAIL" \
  --member="serviceAccount:$SA_EMAIL" \
  --role="roles/iam.serviceAccountTokenCreator" \
  --quiet >/dev/null

# (선택) 산출물 7일 후 자동 삭제 — 스토리지 비용 cap. 정책 이미 있으면 무해하게 덮어씀.
LIFECYCLE_FILE="$(mktemp)"
cat > "$LIFECYCLE_FILE" <<'JSON'
{
  "lifecycle": {
    "rule": [
      { "action": {"type": "Delete"}, "condition": {"age": 7} }
    ]
  }
}
JSON
gcloud storage buckets update "gs://${GCS_BUCKET}" --lifecycle-file="$LIFECYCLE_FILE" >/dev/null
rm -f "$LIFECYCLE_FILE"

echo "▶ Creating / updating secrets…"
create_or_update_secret() {
  local NAME=$1 VAL=$2
  if gcloud secrets describe "$NAME" >/dev/null 2>&1; then
    printf "%s" "$VAL" | gcloud secrets versions add "$NAME" --data-file=- >/dev/null
  else
    printf "%s" "$VAL" | gcloud secrets create "$NAME" --data-file=- --replication-policy=automatic
  fi
}
# 매 배포마다 새로 발급 — production 에서 키 회전하려면 .env 만 갈고 재실행
create_or_update_secret AUTH_JWT_SECRET           "${AUTH_JWT_SECRET:-$(openssl rand -hex 32)}"
create_or_update_secret SEPARATION_SIGNING_SECRET "${SEPARATION_SIGNING_SECRET:-$(openssl rand -hex 32)}"
create_or_update_secret PERSO_API_KEY             "$PERSO_API_KEY"
create_or_update_secret PERSO_SPACE_SEQ           "$PERSO_SPACE_SEQ"
# Postgres (Neon) — IAP 도입 대비 user 영속화용. 평문 password 가 Secret Manager 에 들어가지만
# Cloud Run 의 attached SA 만 read 권한 (secretAccessor). GitHub Actions 인 WIF SA 는 별도 권한.
create_or_update_secret DATABASE_URL              "$DATABASE_URL"
create_or_update_secret DB_USER                   "$DB_USER"
create_or_update_secret DB_PASSWORD               "$DB_PASSWORD"

echo "▶ Deploying to Cloud Run (first build can take ~5min)…"
gcloud run deploy "$SERVICE_NAME" \
  --source "$(dirname "$0")/.." \
  --region "$REGION" \
  --service-account "$SA_EMAIL" \
  --execution-environment gen2 \
  --cpu 1 --memory 2Gi --cpu-boost \
  --timeout 3600 \
  --concurrency 4 \
  --min-instances 0 --max-instances 2 \
  --session-affinity \
  --allow-unauthenticated \
  --set-env-vars="^@^GEMINI_PROJECT_ID=${GEMINI_PROJECT_ID}@GEMINI_LOCATION=${REGION}@PERSO_BASE_URL=https://api.perso.ai@PERSO_STORAGE_BASE_URL=https://portal-media.perso.ai@STORAGE_PATH=/tmp/storage@GOOGLE_OAUTH_CLIENT_IDS=${GOOGLE_OAUTH_CLIENT_IDS}@APPLE_OAUTH_CLIENT_IDS=${APPLE_OAUTH_CLIENT_IDS}@CORS_ALLOWED_ORIGINS=${CORS_ALLOWED_ORIGINS}@GCS_BUCKET=${GCS_BUCKET}@GCS_SIGNED_URL_TTL_SEC=${GCS_SIGNED_URL_TTL_SEC}" \
  --set-secrets="PERSO_API_KEY=PERSO_API_KEY:latest,PERSO_SPACE_SEQ=PERSO_SPACE_SEQ:latest,AUTH_JWT_SECRET=AUTH_JWT_SECRET:latest,SEPARATION_SIGNING_SECRET=SEPARATION_SIGNING_SECRET:latest,DATABASE_URL=DATABASE_URL:latest,DB_USER=DB_USER:latest,DB_PASSWORD=DB_PASSWORD:latest"

URL=$(gcloud run services describe "$SERVICE_NAME" --region "$REGION" --format='value(status.url)')
echo "▶ Updating BFF_BASE_URL=$URL (self-reference)…"
gcloud run services update "$SERVICE_NAME" --region "$REGION" \
  --update-env-vars "BFF_BASE_URL=$URL" >/dev/null

echo ""
echo "✅ Deployed: $URL"
echo "   Swagger: $URL/swagger"
echo ""
echo "Next steps:"
echo "  1. Smoke test:  curl -i $URL/swagger"
echo "  2. Update vibi-mobile/local.properties → BFF_BASE_URL=$URL/"
echo "  3. Update vibi-mobile/iosApp/Configuration/Auth.xcconfig → BFF_BASE_URL=$URL/"
echo "  4. iosApp/Info.plist 의 NSAppTransportSecurity localhost 예외 제거"
