#!/usr/bin/env bash
# vibi-bff Cloud Run 배포 스크립트.
# 사전 조건:
#   1. gcloud CLI + aws CLI 설치 (aws 는 R2 lifecycle 적용용 — S3 API 호환)
#   2. PROJECT_ID 환경변수 또는 아래 PROJECT_ID 값 채우기 (신규 프로젝트 ID)
#   3. vibi-bff/.env 에 채워져 있어야 함:
#      - PERSO_API_KEY / PERSO_SPACE_SEQ / GOOGLE_OAUTH_CLIENT_IDS
#      - DATABASE_URL / DB_USER / DB_PASSWORD (Neon 등 managed Postgres)
#      - R2_BUCKET / R2_ACCOUNT_ID / R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY (Cloudflare R2)
#      - APPLE_OAUTH_CLIENT_IDS (선택 — 비우면 Apple 로그인 비활성)
# 멱등 (중복 실행해도 안전): 이미 존재하면 update / skip. R2 lifecycle 도 매번 PUT —
# S3 PutBucketLifecycleConfiguration 은 전체 교체 semantics 라 drift 자연 차단.

set -euo pipefail

PROJECT_ID="${PROJECT_ID:?set PROJECT_ID env var, e.g. export PROJECT_ID=vibi-bff-prod}"
REGION="${REGION:-us-central1}"
SERVICE_NAME="vibi-bff"
SA_NAME="vibi-bff-sa"
SA_EMAIL="${SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"
# Cloudflare R2 — 큰 산출물(render mp4, separation stem, mix) 다운로드를 SigV4 presigned
# URL redirect 로 우회. **R2 egress 무료** 라 Cloud Run egress 비용 0. Cloud Run 인스턴스도
# 바이트 전송으로 잠기지 않아 max-instances=2 cap 에서 동시 다운로드 처리량 회복.
SIGNED_URL_TTL_SEC="${SIGNED_URL_TTL_SEC:-900}"
# min-instances=0 이 기본 — 유휴 시 scale-to-zero 라 비용 0. SeparationDispatcher 가 BFF 안에
# 살고 있어 idle 시 인스턴스가 죽으면 dispatch 가 멈추는 문제는, 아래 --no-cpu-throttling 으로
# CPU 를 항상 할당해 인스턴스가 살아있는 한 백그라운드 잡(렌더 ffmpeg / Perso 폴링)이 끝까지 돌게
# 해결. 첫 submit 은 콜드 스타트(~5-15s) 감수. 큐 즉시성이 중요하면 MIN_INSTANCES=1 override (월 비용 발생).
MIN_INSTANCES="${MIN_INSTANCES:-0}"
MAX_INSTANCES="${MAX_INSTANCES:-2}"

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
: "${R2_BUCKET:?R2_BUCKET missing in .env}"
: "${R2_ACCOUNT_ID:?R2_ACCOUNT_ID missing in .env (Cloudflare dashboard URL 의 32자 hex)}"
: "${R2_ACCESS_KEY_ID:?R2_ACCESS_KEY_ID missing in .env (R2 API token)}"
: "${R2_SECRET_ACCESS_KEY:?R2_SECRET_ACCESS_KEY missing in .env (R2 API token)}"
: "${ADMIN_SLUG:?ADMIN_SLUG missing in .env (운영자 admin SPA prefix — blank 이면 마운트 안 됨)}"
: "${CORS_ALLOWED_ORIGINS:=}"  # optional — 없으면 빈 값
# Vertex AI 가 활성화된 프로젝트가 Cloud Run 배포 프로젝트와 다를 수 있어 (cross-project) 별도 변수.
# .env 의 GEMINI_PROJECT_ID 가 있으면 그 값, 없으면 PROJECT_ID fallback.
: "${GEMINI_PROJECT_ID:=$PROJECT_ID}"

command -v gcloud >/dev/null || { echo "❌ gcloud CLI not found — install from https://cloud.google.com/sdk/docs/install" >&2; exit 1; }
command -v aws    >/dev/null || { echo "❌ aws CLI not found (필요: R2 lifecycle 적용) — install from https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html" >&2; exit 1; }

echo "▶ Using project: $PROJECT_ID  region: $REGION"
gcloud config set project "$PROJECT_ID" >/dev/null

echo "▶ Enabling required APIs (idempotent)…"
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com \
  cloudbuild.googleapis.com \
  aiplatform.googleapis.com

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

# R2 (Cloudflare) — bucket / access token 은 Cloudflare dashboard 에서 1회 생성.
# 본 스크립트는 (1) R2 credentials 를 Secret Manager 에 미러, (2) bucket lifecycle 을
# deploy/r2-lifecycle.json 으로 강제 적용 (산출물 7일 retention).

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
# R2 API token — Cloudflare R2 object 쓰기 + presigned URL 발급용. 키 회전 시 .env 만 갈고 재실행.
create_or_update_secret R2_ACCESS_KEY_ID          "$R2_ACCESS_KEY_ID"
create_or_update_secret R2_SECRET_ACCESS_KEY      "$R2_SECRET_ACCESS_KEY"

# R2 lifecycle — bucket 에 7일 자동 삭제 룰 강제 적용. PUT semantics 라 매 배포마다
# deploy/r2-lifecycle.json 으로 전체 교체 → drift 자연 차단. token 이 PutBucketLifecycle
# 권한 없으면 (Object R/W 만 있는 경우) 403 — 서비스 동작과 무관한 운영 위생 단계라
# 실패해도 deploy 계속 진행 (warn-only). 대시보드에서 수동 적용 가능.
LIFECYCLE_JSON="$(dirname "$0")/r2-lifecycle.json"
if [[ ! -f "$LIFECYCLE_JSON" ]]; then
  echo "❌ R2 lifecycle config not found at $LIFECYCLE_JSON" >&2
  exit 1
fi
echo "▶ Applying R2 lifecycle rule (bucket=$R2_BUCKET) from $(basename "$LIFECYCLE_JSON")…"
LIFECYCLE_OUTPUT=$(AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
  AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
  AWS_DEFAULT_REGION=auto \
  aws s3api put-bucket-lifecycle-configuration \
    --bucket "$R2_BUCKET" \
    --lifecycle-configuration "file://$LIFECYCLE_JSON" \
    --endpoint-url "https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com" 2>&1) \
  && echo "  ✓ R2 lifecycle applied" \
  || cat <<EOF >&2
⚠️  R2 lifecycle 적용 실패 (deploy 는 계속). 보통 R2 token 권한 부족 (Object R/W 만 있고
   PutBucketLifecycleConfiguration 미허용). 다음 중 하나로 해결:
   - 대시보드에서 1회 수동 설정:
     https://dash.cloudflare.com → R2 → $R2_BUCKET → Settings → Object lifecycle rules
     → Add rule (delete-after-7d, all objects, 7 days)
   - 또는 R2 API token 을 Admin Read & Write 권한으로 재발급 후 .env 갱신
   원본 에러:
$LIFECYCLE_OUTPUT
EOF

# admin-ui (Vite) 빌드 산출물은 .gitignore 됨 — deploy 시점에 항상 fresh build.
# 빠뜨리면 ADMIN_SLUG 가 박혀도 classpath:/admin/index.html 부재로 마운트 skip + 부팅 로그 WARN.
echo "▶ Building admin-ui (vite)…"
(
  cd "$(dirname "$0")/../admin-ui"
  if [[ -f package-lock.json ]]; then
    npm ci --silent
  else
    npm install --silent
  fi
  npm run build
)

echo "▶ Deploying to Cloud Run (first build can take ~5min)…"
gcloud run deploy "$SERVICE_NAME" \
  --source "$(dirname "$0")/.." \
  --region "$REGION" \
  --service-account "$SA_EMAIL" \
  --execution-environment gen2 \
  --cpu 1 --memory 2Gi --cpu-boost \
  --timeout 3600 \
  --concurrency 4 \
  --min-instances "$MIN_INSTANCES" --max-instances "$MAX_INSTANCES" \
  --no-cpu-throttling \
  --session-affinity \
  --allow-unauthenticated \
  --set-env-vars="^@^GEMINI_PROJECT_ID=${GEMINI_PROJECT_ID}@GEMINI_LOCATION=${REGION}@PERSO_BASE_URL=https://api.perso.ai@PERSO_STORAGE_BASE_URL=https://portal-media.perso.ai@STORAGE_PATH=/tmp/storage@GOOGLE_OAUTH_CLIENT_IDS=${GOOGLE_OAUTH_CLIENT_IDS}@APPLE_OAUTH_CLIENT_IDS=${APPLE_OAUTH_CLIENT_IDS}@CORS_ALLOWED_ORIGINS=${CORS_ALLOWED_ORIGINS}@R2_BUCKET=${R2_BUCKET}@R2_ACCOUNT_ID=${R2_ACCOUNT_ID}@SIGNED_URL_TTL_SEC=${SIGNED_URL_TTL_SEC}@ADMIN_SLUG=${ADMIN_SLUG}" \
  --set-secrets="PERSO_API_KEY=PERSO_API_KEY:latest,PERSO_SPACE_SEQ=PERSO_SPACE_SEQ:latest,AUTH_JWT_SECRET=AUTH_JWT_SECRET:latest,SEPARATION_SIGNING_SECRET=SEPARATION_SIGNING_SECRET:latest,DATABASE_URL=DATABASE_URL:latest,DB_USER=DB_USER:latest,DB_PASSWORD=DB_PASSWORD:latest,R2_ACCESS_KEY_ID=R2_ACCESS_KEY_ID:latest,R2_SECRET_ACCESS_KEY=R2_SECRET_ACCESS_KEY:latest"

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
