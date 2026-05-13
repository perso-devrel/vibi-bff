package com.vibi.bff.service

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.HttpMethod
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import com.vibi.bff.config.StorageConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * GCS upload + V4 signed URL 발급.
 *
 * 인증: ADC. Cloud Run 에서는 attached service account, 로컬에선
 * `gcloud auth application-default login` 캐시 또는 GOOGLE_APPLICATION_CREDENTIALS.
 *
 * **signedUrl 발급 권한**: Cloud Run 의 ADC 에는 private key 가 없어 IAM `signBlob` 호출로
 * 서명. SA 가 자기 자신에 `iam.serviceAccountTokenCreator` self-binding 필요 — `deploy/cloud-run.sh` 참고.
 */
class GcsObjectStore(
    private val bucket: String,
    val defaultSignedUrlTtlSec: Long,
    private val storage: Storage = StorageOptions.getDefaultInstance().service,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 같은 instance 가 같은 jobId 를 반복 처리할 때 (session-affinity) GCS metadata RPC 를
     * 0회로 줄이기 위한 in-process upload memo. cold start 시 비어있어 첫 요청은 정상 흐름. */
    private val uploadedKeys = ConcurrentHashMap<String, Long>()

    init {
        require(bucket.isNotBlank()) { "GcsObjectStore bucket must not be blank" }
    }

    fun uploadIfAbsent(file: File, objectKey: String, contentType: String) {
        val fileLen = file.length()
        // Hot path: 같은 인스턴스에서 이미 업로드한 객체면 GCS GET 도 skip.
        if (uploadedKeys[objectKey] == fileLen) return

        val blobId = BlobId.of(bucket, objectKey)
        val existing = storage.get(blobId)
        if (existing != null && existing.size == fileLen) {
            uploadedKeys[objectKey] = fileLen
            return
        }
        val info = BlobInfo.newBuilder(blobId)
            .setContentType(contentType)
            .build()
        // 16MB chunk — 큰 MP4 (50~200MB) 업로드 시 default 256KB 보다 round-trip 수 60배 감소.
        storage.createFrom(info, file.toPath(), UPLOAD_BUFFER_BYTES)
        uploadedKeys[objectKey] = fileLen
        log.info("Uploaded to GCS: gs://{}/{} ({} bytes)", bucket, objectKey, fileLen)
    }

    /** V4 signed URL. [ttlSec] 미지정 시 ctor 의 [defaultSignedUrlTtlSec] 사용. */
    fun signedUrl(
        objectKey: String,
        ttlSec: Long = defaultSignedUrlTtlSec,
        downloadFilename: String? = null,
        contentType: String? = null,
    ): String {
        val blobInfoBuilder = BlobInfo.newBuilder(BlobId.of(bucket, objectKey))
        if (contentType != null) blobInfoBuilder.setContentType(contentType)
        val blobInfo = blobInfoBuilder.build()

        val opts = mutableListOf<Storage.SignUrlOption>(
            Storage.SignUrlOption.withV4Signature(),
            Storage.SignUrlOption.httpMethod(HttpMethod.GET),
        )
        if (downloadFilename != null) {
            // V4 spec: response-content-disposition 은 signed query param 으로만 전달 가능
            // (request header 가 아님) — URL 만으로 다운로드 파일명을 강제하기 위함.
            opts.add(Storage.SignUrlOption.withQueryParams(
                mapOf("response-content-disposition" to "attachment; filename=\"$downloadFilename\"")
            ))
        }

        return storage.signUrl(blobInfo, ttlSec, TimeUnit.SECONDS, *opts.toTypedArray()).toString()
    }

    companion object {
        private const val UPLOAD_BUFFER_BYTES = 16 * 1024 * 1024

        /** [StorageConfig.gcsBucket] 가 비어있으면 null (로컬 dev / GCS 미사용). */
        fun fromConfig(config: StorageConfig): GcsObjectStore? {
            if (config.gcsBucket.isBlank()) {
                LoggerFactory.getLogger(GcsObjectStore::class.java).info(
                    "GCS object store disabled (GCS_BUCKET blank) — using respondFile streaming"
                )
                return null
            }
            LoggerFactory.getLogger(GcsObjectStore::class.java).info(
                "GCS object store enabled: bucket={} defaultSignedUrlTtlSec={}",
                config.gcsBucket, config.gcsSignedUrlTtlSec,
            )
            return GcsObjectStore(
                bucket = config.gcsBucket,
                defaultSignedUrlTtlSec = config.gcsSignedUrlTtlSec,
            )
        }
    }
}
