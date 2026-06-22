package com.vibi.bff.service

import com.vibi.bff.config.StorageConfig
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Cloudflare R2 (S3-compatible) object store + SigV4 presigned URL 발급.
 *
 * 큰 산출물(render mp4, separation stem, mix) 을 R2 bucket 에 업로드 후 presigned URL 로
 * 302 redirect 해 Cloud Run 인스턴스가 바이트 전송으로 잠기지 않게 한다. **R2 는 egress
 * 무료** 라 GCS 대비 운영 비용 큰 폭 절감 — 본 BFF 가 R2 로 이주한 핵심 이유.
 *
 * 인증: R2 API token 의 access key + secret. Cloudflare dashboard → R2 → Manage API Tokens
 * 에서 Object Read & Write 권한으로 발급. account ID 는 dashboard URL 에 노출되는 32자 hex.
 *
 * 미설정 (`R2_BUCKET` blank) 시 [fromConfig] 가 null 반환 — DownloadResponder 가 respondFile
 * streaming fallback (로컬 dev / R2 미사용).
 */
class ObjectStore(
    private val bucket: String,
    val defaultSignedUrlTtlSec: Long,
    private val s3: S3Client,
    private val presigner: S3Presigner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 같은 instance 가 같은 jobId 를 반복 처리할 때 HEAD RPC 도 0회로. cold start 시
     * 비어있어 첫 요청은 정상 흐름. */
    private val uploadedKeys = ConcurrentHashMap<String, Long>()

    /** asset-by-reference 흐름의 다운로드 캐시. 같은 인스턴스가 같은 R2 객체를 두 번
     * 다운로드 안 하기 위함 — multi-variant 렌더에서 동일 source 영상/BGM 을 N 번 받지 않음. */
    private val downloadedKeys = ConcurrentHashMap<String, Long>()

    init { require(bucket.isNotBlank()) { "ObjectStore bucket must not be blank" } }

    /**
     * R2 가 객체를 갖고 있지 않으면 [file] 을 업로드. 멱등.
     *
     * **로컬 [file] 부재 허용**: SeparationService 의 DB fallback 분기 (`rebuildReadyJob`) 가
     * 만든 placeholder File 처리 — 그 잡의 실체 stems 는 다른 인스턴스가 이미 eager upload
     * 해둔 R2 객체. HEAD 가 R2 hit 을 확인하면 그대로 통과 (업로드 skip). HEAD 가 miss 인데
     * 로컬 file 도 없으면 IllegalStateException — 호출자 (route) 는 그대로 propagate 해
     * 404 가 아닌 500 으로 응답 (운영상 발생 시 R2 lifecycle 만료 후 잡 lookup 한 케이스).
     */
    fun uploadIfAbsent(file: File, objectKey: String, contentType: String) {
        val fileExists = file.exists()
        val fileLen = if (fileExists) file.length() else -1L
        // Hot path: 같은 인스턴스에서 이미 업로드한 객체면 R2 HEAD 도 skip.
        // 로컬 file 부재 분기에선 size 비교 못 하므로 cached 가 있기만 하면 통과.
        val cachedLen = uploadedKeys[objectKey]
        if (cachedLen != null && (!fileExists || cachedLen == fileLen)) return

        val existingLen = try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build()).contentLength()
        } catch (_: NoSuchKeyException) {
            -1L
        } catch (e: S3Exception) {
            // R2 는 missing key 에 404 (NoSuchKey) 또는 403 (token 권한에 따라) 으로 응답.
            // 둘 다 "없음" 으로 간주하고 upload 진행 — 권한 진짜 부족이면 putObject 에서 throw.
            if (e.statusCode() in MISSING_KEY_STATUS_CODES) -1L else throw e
        }
        if (existingLen >= 0 && (!fileExists || existingLen == fileLen)) {
            // R2 가 갖고 있고 (로컬 file 없거나 같은 크기) — 업로드 skip. placeholder File 케이스도 통과.
            uploadedKeys[objectKey] = existingLen
            return
        }
        if (!fileExists) {
            // R2 도 없고 로컬도 없음 — 진짜 데이터 없음. 운영상 발생하면 R2 lifecycle 만료 후
            // 옛 jobId 를 GET 한 케이스. throw 로 caller 가 5xx 응답하게 함.
            throw IllegalStateException(
                "Object missing in R2 and no local file: r2://$bucket/$objectKey",
            )
        }

        s3.putObject(
            PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(contentType).build(),
            file.toPath(),
        )
        uploadedKeys[objectKey] = fileLen
        log.info("Uploaded to R2: r2://{}/{} ({} bytes)", bucket, objectKey, fileLen)
    }

    /**
     * R2 에 객체가 존재하는지 HEAD 로 확인. asset-by-reference 흐름이 모바일 PUT 직전에
     * dedup 체크하는 용도. uploadedKeys 캐시 hit 도 hit 으로 본다 — 같은 인스턴스에서
     * 직전에 올렸으면 R2 HEAD 도 skip.
     *
     * 권한 부족(403) 도 missing 으로 간주. presigned PUT 발급 후 모바일이 PUT 실패하면
     * 그쪽에서 surface 되므로 안전.
     */
    fun objectExists(objectKey: String): Boolean {
        if (uploadedKeys.containsKey(objectKey)) return true
        return try {
            val len = s3.headObject(
                HeadObjectRequest.builder().bucket(bucket).key(objectKey).build()
            ).contentLength()
            if (len >= 0) {
                uploadedKeys[objectKey] = len
                true
            } else false
        } catch (_: NoSuchKeyException) {
            false
        } catch (e: S3Exception) {
            if (e.statusCode() in MISSING_KEY_STATUS_CODES) false else throw e
        }
    }

    /**
     * SigV4 presigned PUT URL — 모바일이 R2 에 직접 업로드할 수 있게 한다.
     *
     * [contentType] 과 [contentLengthBytes] 는 sign 시점에 고정되므로 클라가 PUT 시
     * 동일 값을 전송해야 한다. 다른 값을 보내면 R2 가 401. 짧은 TTL ([ttlSec] 기본 300s) 로
     * leak 윈도우 최소화.
     */
    fun presignedPutUrl(
        objectKey: String,
        contentType: String,
        contentLengthBytes: Long,
        ttlSec: Long = 300L,
    ): String {
        require(contentLengthBytes > 0) { "contentLengthBytes must be positive" }
        require(ttlSec in 60..3600) { "ttlSec out of safe range: $ttlSec" }
        val putRequest = PutObjectRequest.builder()
            .bucket(bucket)
            .key(objectKey)
            .contentType(contentType)
            .contentLength(contentLengthBytes)
            .build()
        val presigned = presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(ttlSec))
                .putObjectRequest(putRequest)
                .build()
        )
        return presigned.url().toString()
    }

    /**
     * R2 객체를 로컬 [targetFile] 로 다운로드. 멱등 — 같은 인스턴스가 이미 받은 적이 있고
     * (downloadedKeys cache) 로컬 file 도 존재하면 즉시 반환. 디스크에 file 만 있고
     * cache 가 stale 인 경우 (인스턴스 재시작) R2 HEAD 로 size 비교 후 일치하면 통과.
     *
     * 다운로드는 `.tmp` 로 받아 ATOMIC_MOVE 로 rename — 동시 다운로드 race 에서도 partial
     * file 노출 없음.
     */
    fun downloadIfAbsent(objectKey: String, targetFile: File): File {
        val cachedLen = downloadedKeys[objectKey]
        if (cachedLen != null && targetFile.exists() && targetFile.length() == cachedLen) {
            return targetFile
        }
        if (targetFile.exists()) {
            val localLen = targetFile.length()
            val remoteLen = try {
                s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build()).contentLength()
            } catch (_: NoSuchKeyException) {
                -1L
            } catch (e: S3Exception) {
                if (e.statusCode() in MISSING_KEY_STATUS_CODES) -1L else throw e
            }
            if (remoteLen >= 0 && remoteLen == localLen) {
                downloadedKeys[objectKey] = localLen
                return targetFile
            }
        }
        targetFile.parentFile?.mkdirs()
        val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp.${System.nanoTime()}")
        try {
            s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(objectKey).build(),
                tmpFile.toPath(),
            )
            Files.move(tmpFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Throwable) {
            tmpFile.delete()
            throw e
        }
        val len = targetFile.length()
        downloadedKeys[objectKey] = len
        log.info("Downloaded from R2: r2://{}/{} ({} bytes)", bucket, objectKey, len)
        return targetFile
    }

    /** SigV4 presigned GET URL. [ttlSec] 미지정 시 ctor 의 [defaultSignedUrlTtlSec] 사용. */
    fun signedUrl(
        objectKey: String,
        ttlSec: Long = defaultSignedUrlTtlSec,
        downloadFilename: String? = null,
        contentType: String? = null,
    ): String {
        val getBuilder = GetObjectRequest.builder().bucket(bucket).key(objectKey)
        if (contentType != null) getBuilder.responseContentType(contentType)
        if (downloadFilename != null) {
            // S3/R2 spec: response-content-disposition 은 presigned query param 으로만 전달 가능
            // (request header 가 아님) — URL 만으로 다운로드 파일명을 강제하기 위함.
            getBuilder.responseContentDisposition("attachment; filename=\"$downloadFilename\"")
        }
        val presigned = presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(ttlSec))
                .getObjectRequest(getBuilder.build())
                .build()
        )
        return presigned.url().toString()
    }

    /**
     * R2 오브젝트 삭제 — 회원탈퇴 콘텐츠 erasure (GDPR/CCPA) 용. S3 DeleteObject 는 멱등
     * (없는 키도 성공). 삭제 성공/대상부재면 true, 예외면 false (caller 가 best-effort 로 계속).
     * 로컬 dedup 캐시도 함께 무효화해 같은 인스턴스의 후속 HEAD 가 stale hit 안 되게 한다.
     */
    fun deleteObject(objectKey: String): Boolean = try {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build())
        uploadedKeys.remove(objectKey)
        downloadedKeys.remove(objectKey)
        true
    } catch (e: Exception) {
        log.warn("R2 deleteObject failed: r2://{}/{} — {}", bucket, objectKey, e.message)
        false
    }

    fun shutdown() {
        runCatching { s3.close() }
        runCatching { presigner.close() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ObjectStore::class.java)

        /** R2 가 인식하는 region 특수값 — 실제 데이터는 단일 글로벌 namespace, 트래픽 라우팅은
         * Cloudflare edge 담당. AWS SDK 가 빈 region 을 거부하므로 placeholder 필수. */
        private val R2_REGION = Region.of("auto")

        /** HEAD object 가 missing key 신호로 반환할 수 있는 status code. R2 의 token 권한에 따라
         * 404 (NoSuchKey) 또는 403 둘 다 가능. 권한 진짜 부족이면 putObject 가 throw 하므로 안전. */
        private val MISSING_KEY_STATUS_CODES = setOf(403, 404)

        /** [StorageConfig.r2] 가 null 이면 백엔드 비활성 (로컬 dev / R2 미사용). */
        fun fromConfig(config: StorageConfig): ObjectStore? {
            val r2 = config.r2 ?: run {
                log.info("Object store disabled (R2_BUCKET blank) — using respondFile streaming")
                return null
            }

            val endpoint = URI.create("https://${r2.accountId}.r2.cloudflarestorage.com")
            val creds = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(r2.accessKeyId, r2.secretAccessKey)
            )
            val s3 = S3Client.builder()
                .endpointOverride(endpoint)
                .region(R2_REGION)
                .credentialsProvider(creds)
                // UrlConnectionHttpClient — SDK 의 가장 가벼운 sync HTTP 백엔드. Apache HttpClient
                // 의존성 제거 + JDK 내장 HttpURLConnection 사용. 본 BFF 의 R2 호출은 저빈도
                // (업로드 1회 + 다운로드 0회 — 다운로드는 presigned URL 로 클라가 직접) 라 충분.
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build()
            val presigner = S3Presigner.builder()
                .endpointOverride(endpoint)
                .region(R2_REGION)
                .credentialsProvider(creds)
                .build()

            log.info(
                "Object store (R2) enabled: bucket={} defaultSignedUrlTtlSec={}",
                config.r2Bucket, config.signedUrlTtlSec,
            )
            return ObjectStore(
                bucket = config.r2Bucket,
                defaultSignedUrlTtlSec = config.signedUrlTtlSec,
                s3 = s3,
                presigner = presigner,
            )
        }
    }
}
