# DubCast BFF 피벗 진행 상황

## 완료된 Phase

### Phase 1: 기존 파이프라인 정리 ✅
- 삭제: TranslateRoutes, JobRoutes, JobStore + 관련 테스트
- BffModels.kt: 더빙 관련 DTO 전부 삭제, UploadResponse/ErrorResponse 유지
- ElevenLabsModels.kt: 더빙 관련 모델 전부 삭제
- ElevenLabsClient.kt: createDubbing, getDubbingStatus, downloadDubbedAudio, getTranscript 삭제
- FileStorageService.kt: getDubbedResultFile 삭제
- Routing.kt, Application.kt: JobStore 참조 제거

### Phase 2: Voices API ✅
- GET /api/v1/voices — ElevenLabs 보이스 목록 프록시
- ElevenLabsVoice, ElevenLabsVoicesResponse 모델 추가
- Voice, VoiceListResponse BFF DTO 추가
- VoiceRoutesTest: 2개 테스트 (목록 반환, 빈 목록)

### Phase 3: TTS API ✅
- POST /api/v1/tts — TTS 생성, 오디오 저장 후 URL 반환
- TtsRequest, TtsResponse BFF DTO 추가
- ElevenLabsClient.textToSpeech() 추가
- FileStorageService.saveTtsAudio() 추가, tts/ 디렉토리 생성
- TtsRoutesTest: 2개 테스트 (기본 파라미터, 커스텀 파라미터)

### Phase 4: Lip-Sync API ✅
- POST /api/v1/lipsync — 립싱크 요청
- GET /api/v1/lipsync/{id}/status — 상태 폴링
- ElevenLabsLipSyncResponse, ElevenLabsLipSyncStatus 모델 추가
- LipSyncRequest, LipSyncStatusResponse BFF DTO 추가
- ElevenLabsClient: createLipSync, getLipSyncStatus, downloadLipSyncResult 추가
- FileStorageService.getLipSyncResultFile() 추가, lipsync/ 디렉토리 생성
- LipSyncRoutesTest: 4개 테스트 (생성, 진행중, 완료, 실패)

### Phase 5: 최종 검증 ✅
- `./gradlew clean test --no-daemon` — BUILD SUCCESSFUL (7 tasks, 전체 테스트 통과)
- curl 수동 테스트는 사용자가 실제 API 키 설정 후 진행 필요

## 테스트 결과

```
BUILD SUCCESSFUL in 39s
7 actionable tasks: 7 executed
```

테스트 파일 5개:
- FileStorageServiceTest (9개 테스트)
- UploadRoutesTest (3개 테스트)
- VoiceRoutesTest (2개 테스트)
- TtsRoutesTest (2개 테스트)
- LipSyncRoutesTest (4개 테스트)

## 최종 API 엔드포인트

| Method | Path | 설명 |
|--------|------|------|
| POST | /api/v1/upload | 파일 업로드 |
| GET | /api/v1/voices | 보이스 목록 |
| POST | /api/v1/tts | TTS 음성 생성 |
| POST | /api/v1/lipsync | 립싱크 요청 |
| GET | /api/v1/lipsync/{id}/status | 립싱크 상태 확인 |
