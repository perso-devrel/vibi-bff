# DubCast BFF 피벗 태스크 목록

## Phase 1: 기존 파이프라인 정리

- [x] `routes/TranslateRoutes.kt` 삭제
- [x] `routes/JobRoutes.kt` 삭제
- [x] `service/JobStore.kt` 삭제
- [x] `test/JobRoutesTest.kt` 삭제
- [x] `test/JobStoreTest.kt` 삭제
- [x] `model/BffModels.kt` — TranslationRequest, TranslationResponse, JobStatus, ProgressResponse, DownloadResponse, ScriptResponse, ScriptSegment, EstimateResponse 삭제. UploadResponse, ErrorResponse 유지
- [x] `model/ElevenLabsModels.kt` — ElevenLabsDubbingResponse, ElevenLabsDubbingStatus, MediaMetadata, ElevenLabsTranscript, TranscriptUtterance 삭제
- [x] `service/ElevenLabsClient.kt` — createDubbing, getDubbingStatus, downloadDubbedAudio, getTranscript 삭제
- [x] `service/FileStorageService.kt` — getDubbedResultFile 삭제
- [x] `plugins/Routing.kt` — translateRoutes, jobRoutes, JobStore 참조 제거
- [x] `Application.kt` — JobStore 인스턴스 생성 및 참조 제거
- [x] 빌드 확인 (`./gradlew clean build --no-daemon`)

## Phase 2: Voices API

- [x] `model/ElevenLabsModels.kt` — ElevenLabsVoice(voiceId, name, previewUrl?, category?, labels), ElevenLabsVoicesResponse(voices) 추가
- [x] `model/BffModels.kt` — Voice(voiceId, name, previewUrl?, category?, labels), VoiceListResponse(voices) 추가
- [x] `service/ElevenLabsClient.kt` — `getVoices()` 추가 (GET /v1/voices)
- [x] `routes/VoiceRoutes.kt` 생성 — GET /api/v1/voices (ElevenLabs 보이스 목록을 BFF Voice DTO로 매핑 후 반환)
- [x] `plugins/Routing.kt` — voiceRoutes 연결
- [x] `test/VoiceRoutesTest.kt` 생성 — mockk으로 ElevenLabsClient 모킹, 보이스 목록 반환 확인
- [x] 빌드 및 테스트 확인

## Phase 3: TTS API

- [x] `model/BffModels.kt` — TtsRequest(text, voiceId, languageCode?, modelId, stability, similarityBoost), TtsResponse(audioUrl) 추가
- [x] `service/ElevenLabsClient.kt` — `textToSpeech(voiceId, text, modelId, stability, similarityBoost, languageCode?)` 추가 (POST /v1/text-to-speech/{voiceId}, 오디오 바이트 반환)
- [x] `service/FileStorageService.kt` — `saveTtsAudio(bytes, requestId): String` 추가 (tts/ 디렉토리에 저장), `init()`에 tts/ 디렉토리 생성 추가
- [x] `routes/TtsRoutes.kt` 생성 — POST /api/v1/tts (TtsRequest 수신 → ElevenLabs TTS 호출 → 오디오 저장 → audioUrl 반환)
- [x] `plugins/Routing.kt` — ttsRoutes 연결, `/files/tts` 정적 파일 서빙 추가
- [x] `test/TtsRoutesTest.kt` 생성 — mockk으로 ElevenLabsClient 모킹, TTS 요청→오디오 URL 반환 확인
- [x] 빌드 및 테스트 확인

## Phase 4: Lip-Sync API

- [x] `model/ElevenLabsModels.kt` — ElevenLabsLipSyncResponse(id, status?), ElevenLabsLipSyncStatus(id, status, error?) 추가
- [x] `model/BffModels.kt` — LipSyncRequest(videoBlobPath, audioBlobPath), LipSyncStatusResponse(id, status, outputVideoUrl?, error?) 추가
- [x] `service/ElevenLabsClient.kt` — `createLipSync(videoFile, audioFile)`, `getLipSyncStatus(id)`, `downloadLipSyncResult(id, targetFile)` 추가
- [x] `service/FileStorageService.kt` — `getLipSyncResultFile(lipSyncId): Pair<File, String>` 추가, `init()`에 lipsync/ 디렉토리 생성 추가
- [x] `routes/LipSyncRoutes.kt` 생성 — POST /api/v1/lipsync (립싱크 요청), GET /api/v1/lipsync/{id}/status (상태 폴링, 완료 시 결과 다운로드 후 URL 반환)
- [x] `plugins/Routing.kt` — lipSyncRoutes 연결, `/files/lipsync` 정적 파일 서빙 추가
- [x] `test/LipSyncRoutesTest.kt` 생성 — mockk으로 ElevenLabsClient 모킹, 립싱크 요청/상태 확인
- [x] 빌드 및 테스트 확인

## Phase 5: 최종 검증

- [x] `./gradlew clean test --no-daemon` 전체 테스트 통과
- [ ] 서버 실행 후 curl로 엔드포인트별 1회 수동 테스트 (비용 최소화)

## 참고: 테스트 원칙

- 단위 테스트는 반드시 mockk으로 ElevenLabsClient 모킹 (ElevenLabs 과금 방지)
- API 수동 테스트는 최소 1회만 (TTS 텍스트는 짧게, 립싱크는 짧은 영상으로)
- 빌드 디렉토리는 `C:/tmp/dubcast-bff-build` (한글 경로 이슈 회피)
