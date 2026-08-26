# Kimchi_R1

Ray-Ban Meta용 Android 네이티브 앱. Meta Wearables Device Access Toolkit(DAT) 0.9.0 기반.

## 설정

1. `.env.example`을 참고해 로컬 `.env`를 채운다.
2. `GITHUB_TOKEN`에는 GitHub classic PAT의 `read:packages` 권한이 필요하다.
3. `META_DAT_APPLICATION_ID`, `META_DAT_CLIENT_TOKEN`은 Wearables Developer Center 값이다.
4. Meta AI 앱에서 Developer Mode를 켜고 Ray-Ban Meta를 페어링한다.

```powershell
.\gradlew.bat assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## 동작

- 실행 시 DAT 등록 상태 확인
- 미등록 시 Meta AI 등록 화면으로 이동
- 등록 후 백엔드 웹앱을 WebView로 표시
- 웹앱의 `NativeBridge.openVision()` 호출로 네이티브 비전 화면 열기
- 네이티브 비전에서 세션 생성, 카메라 권한 요청, HEVC 미리보기, 제스처 인식, 음성 기록, 사진/영상 캡처
- 서버 주소를 앱에 저장하고 `/health`로 연결 확인
