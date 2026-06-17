# GitHub Actions → Cloud Run 배포 셋업

`.github/workflows/deploy.yml` 이 main push 시 Cloud Run 에 자동 배포한다. 인증은
**Workload Identity Federation (WIF)** — GitHub OIDC 토큰을 GCP federated token 으로 교환.
서비스 계정 JSON 키를 GitHub 에 저장할 필요 없음.

`deploy/cloud-run.sh` 는 Cloud Run runtime SA / Secret Manager / 첫 배포만 셋업한다.
**WIF 풀과 provider 는 별도로** 만들어야 — 아래 1회 부트스트랩 후 GitHub Secrets 채우면 끝.

---

## 에러 매핑

| 증상 | 원인 |
|---|---|
| `invalid_target — pool or provider … doesn't exist` | 풀/provider 미생성 (가장 흔함) 또는 `GCP_WIF_PROVIDER` 값 형식 오류 |
| `unauthorized_client — repository not allowed` | provider `attribute-condition` 이 이 repo 를 허용하지 않음 |
| `Permission 'iam.serviceAccounts.getAccessToken' denied` | SA 에 `roles/iam.workloadIdentityUser` binding 누락 |
| `gcloud run deploy: Permission denied` | SA 에 Cloud Run / Cloud Build / Artifact Registry 권한 누락 (배포 권한은 runtime 권한과 별개) |
| 배포 성공 후 다운로드 endpoint 만 500 | `vars.R2_BUCKET` / `vars.R2_ACCOUNT_ID` 오타, R2 bucket 미존재, `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` secret 만료·권한 부족 |

---

## 1) WIF 풀 + provider 부트스트랩

```bash
# 사전: cloud-run.sh 한 번 돌려서 vibi-bff-sa 만들어둔 상태 가정.
export PROJECT_ID=<your-project-id>
export PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')
export SA_EMAIL="vibi-bff-sa@${PROJECT_ID}.iam.gserviceaccount.com"
export REPO_OWNER="perso-devrel"           # GitHub <owner> (이 repo 는 perso-devrel 조직)
export REPO="perso-devrel/vibi-bff"        # GitHub <owner>/<repo>

gcloud config set project "$PROJECT_ID"
gcloud services enable iamcredentials.googleapis.com sts.googleapis.com

# 풀
gcloud iam workload-identity-pools create github-pool \
  --location=global \
  --display-name="GitHub Actions Pool"

# OIDC provider — repository_owner 로 제한 (다른 조직 토큰 거부)
gcloud iam workload-identity-pools providers create-oidc github-provider \
  --location=global \
  --workload-identity-pool=github-pool \
  --display-name="GitHub Actions Provider" \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_owner=assertion.repository_owner" \
  --attribute-condition="assertion.repository_owner=='${REPO_OWNER}'"

# SA 에 이 repo 한정 WIF binding — principalSet 로 repo 단위 허용
gcloud iam service-accounts add-iam-policy-binding "$SA_EMAIL" \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github-pool/attribute.repository/${REPO}"

# 배포 권한 — gcloud run deploy --source 는 Cloud Build 로 빌드 후 Artifact Registry 에 push
for ROLE in \
  roles/run.admin \
  roles/iam.serviceAccountUser \
  roles/cloudbuild.builds.builder \
  roles/artifactregistry.writer \
  roles/storage.admin \
  roles/logging.logWriter; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${SA_EMAIL}" \
    --role="$ROLE" \
    --condition=None --quiet >/dev/null
done

# GitHub secret GCP_WIF_PROVIDER 에 넣을 값
echo ""
echo "GCP_WIF_PROVIDER = projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github-pool/providers/github-provider"
echo "GCP_SA_EMAIL     = ${SA_EMAIL}"
echo "GCP_PROJECT_ID   = ${PROJECT_ID}"
```

---

## 2) GitHub Secrets 설정

repo → **Settings → Secrets and variables → Actions → New repository secret** 에서 3개:

| Secret | 값 | 주의 |
|---|---|---|
| `GCP_WIF_PROVIDER` | `projects/<PROJECT_NUMBER>/locations/global/workloadIdentityPools/github-pool/providers/github-provider` | `<PROJECT_NUMBER>` 는 **숫자 12자리** (project ID 아님). 위 스크립트 마지막 출력 그대로 복사. |
| `GCP_SA_EMAIL` | `vibi-bff-sa@<PROJECT_ID>.iam.gserviceaccount.com` | |
| `GCP_PROJECT_ID` | `<PROJECT_ID>` | project ID (문자열) |

또한 secrets 탭에서 추가:

| Secret | 값 |
|---|---|
| `GOOGLE_OAUTH_CLIENT_IDS` | 콤마 분리 Google OAuth client ID (iOS / Android / Web) |
| `APPLE_OAUTH_CLIENT_IDS` | 콤마 분리 Apple Sign In client ID — 보통 iOS bundle id (`com.vibi.ios`). 비워두면 Apple 로그인 비활성. |

### Secret Manager (Postgres / Neon) — `cloud-run.sh` 가 1회 시드

DB credential 은 GitHub Secret 이 아닌 **Secret Manager** 에 저장된다 (Cloud Run runtime SA 만 read).
첫 부트스트랩 시 `.env` 에 채운 값을 `cloud-run.sh` 가 자동으로 Secret Manager 에 시드한다 —
이후 회전은 `.env` 갱신 후 `cloud-run.sh` 재실행 또는 `gcloud secrets versions add ...`.

| Secret 이름 | 형식 | 비고 |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://<host>/<db>?sslmode=require` | **`jdbc:` 접두사 필수** — Neon UI 가 주는 원본 URL 에는 없음 |
| `DB_USER` | Neon role 이름 | |
| `DB_PASSWORD` | Neon role 비밀번호 | |

---

## 2.5) GitHub Variables (선택)

같은 화면의 **Variables** 탭. 설정 안 해도 워크플로우는 돌지만, 기능별 활성화 여부에
영향. R2 egress 분리를 쓰려면 `R2_BUCKET` + `R2_ACCOUNT_ID` 필수 (credentials 는 Secret Manager).

| Variable | 값 예시 | 미설정 시 동작 |
|---|---|---|
| `BFF_BASE_URL` | `https://api.vibi.fm` | Cloud Run 자동 발급 URL self-reference |
| `CORS_ALLOWED_ORIGINS` | 콤마 분리 origin | 빈 값 (CORS 비활성) |
| `R2_BUCKET` | `vibi-bff-artifacts` | **빈 값 → 다운로드가 Cloud Run streaming 으로 fallback**. R2 egress 분리 효과 없음. |
| `R2_ACCOUNT_ID` | 32자 hex (dashboard URL 에서 복사) | R2_BUCKET 셋업 시 필수 — endpoint host 결정 |

> Presigned URL TTL 은 application.conf 의 `signedUrlTtlSec = "900"` (15분) default 사용.
> 바꿔야 하면 그때 `SIGNED_URL_TTL_SEC` var 추가.

R2 bucket / API token 은 Cloudflare dashboard 에서 미리 만들어두고 (Object Read & Write 권한),
`deploy/cloud-run.sh` 가 access key / secret 을 Secret Manager 로 옮긴다. lifecycle (7일 자동 삭제) 도
dashboard → R2 → bucket → Settings → Object lifecycle rules 에서 1회 설정.

---

## 3) 검증

### 셋업 검증 (로컬, GitHub 트리거 전)

```bash
# 풀 / provider 가 ACTIVE 인지
gcloud iam workload-identity-pools providers describe github-provider \
  --location=global \
  --workload-identity-pool=github-pool \
  --format='value(name,state)'
# → projects/.../providers/github-provider  ACTIVE

# SA 에 workloadIdentityUser binding 있는지
gcloud iam service-accounts get-iam-policy "$SA_EMAIL" \
  --format='value(bindings.role,bindings.members)' | grep workloadIdentityUser
```

### CI 검증

`main` 에 빈 커밋 push 하거나 Actions UI → **Run workflow**:

```bash
git commit --allow-empty -m "ci: trigger deploy"
git push
```

성공 시 워크플로우 마지막 step 이 `::notice title=Deployed::https://vibi-bff-…-uc.a.run.app` 출력.

---

## Troubleshooting

### `invalid_target` 재발 — 셋업 후에도

1. **PROJECT_NUMBER 가 아닌 PROJECT_ID 를 넣음**. secret 값이 `projects/my-proj-id/...` 면 X — `projects/123456789012/...` 형식.
2. 풀/provider 이름 오타. secret 값과 `gcloud iam workload-identity-pools providers describe` 출력의 `name` 필드가 정확히 일치해야 함.
3. 풀/provider 가 `DELETED` 상태. soft-delete 면 30일 보존 — 새 이름으로 재생성.

### `unauthorized_client — repository "<owner>/<repo>" is not allowed`

`attribute-condition` 이 이 repo 의 owner 를 거부 중. 확인:

```bash
gcloud iam workload-identity-pools providers describe github-provider \
  --location=global --workload-identity-pool=github-pool \
  --format='value(attributeCondition)'
```

`assertion.repository_owner=='perso-devrel'` 같은 식. owner 가 바뀌었으면 update:

```bash
gcloud iam workload-identity-pools providers update-oidc github-provider \
  --location=global --workload-identity-pool=github-pool \
  --attribute-condition="assertion.repository_owner=='<new-owner>'"
```

### `Permission denied` on `gcloud run deploy`

위 부트스트랩의 `for ROLE in ...` 블록 다시 실행. `roles/iam.serviceAccountUser` 가 빠지면
Cloud Run 이 runtime SA 를 attach 못 함.

### 다운로드 endpoint 가 500 / presigned URL 발급 실패

배포는 성공했는데 `/api/v2/render/{jobId}/download` 등이 500 반환:

1. **`vars.R2_BUCKET` / `vars.R2_ACCOUNT_ID` 값이 실제와 다름** — Cloudflare dashboard 에서 정확한
   이름 / 32자 hex 확인.
2. **`R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` secret 만료·삭제** — Cloudflare dashboard 에서 token
   재발급 후 cloud-run.sh 재실행 (Secret Manager 새 version 추가).
3. **token 권한 부족** — R2 API token 이 Object Read & Write 권한이어야 함. Read-only 면 upload 실패.

`vars.R2_BUCKET` 를 빈 값으로 두면 위 모두 불필요 — 다운로드가 Cloud Run streaming 으로 fallback.

### `Cloud Build` 빌드 실패

첫 배포는 `gcloud run deploy --source .` 가 Cloud Build 로 컨테이너를 빌드. 빌드 자체가
깨지면 GitHub Actions 가 아니라 Cloud Build 로그 확인:

```bash
gcloud builds list --limit=5
gcloud builds log <BUILD_ID>
```

---

## 보안 메모

- WIF 풀은 SA 키보다 안전 — 키 회전 / 분실 위험 없음, repo 범위 제한.
- `attribute-condition` 으로 **반드시 repository_owner 또는 repository 단위 제한**. 빠뜨리면
  GitHub 의 모든 repo 토큰이 이 SA 를 빌릴 수 있음.
- `principalSet://...attribute.repository/<owner>/<repo>` binding 은 정확히 그 repo 만 허용 —
  fork / PR from fork 에서는 `id-token` 권한이 read-only 라 별도 안전.
- `workflow_dispatch` 로 수동 트리거할 때도 같은 인증 경로 사용.
