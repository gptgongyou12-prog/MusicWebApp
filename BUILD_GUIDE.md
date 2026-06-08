# MusicWebApp 빌드 가이드

## APK 빌드 방법

### 방법 1: Android Studio (권장)

1. [Android Studio 다운로드](https://developer.android.com/studio)
2. Android Studio 실행 → `Open` → `MusicWebApp` 폴더 선택
3. Gradle sync 완료 대기
4. 상단 메뉴: `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`
5. 빌드 완료 후 `app/build/outputs/apk/debug/app-debug.apk` 파일 생성

### 방법 2: 커맨드라인 (Android SDK 설치 필요)

```bash
cd MusicWebApp
./gradlew assembleDebug
```

APK 위치: `app/build/outputs/apk/debug/app-debug.apk`

릴리즈 APK:
```bash
./gradlew assembleRelease
```

---

## 앱 동작

1. **최초 실행**: URL 입력 다이얼로그 표시
   - `https://` 로 시작하는 URL 입력
   - 확인 버튼 클릭 → URL 고정 저장

2. **이후 실행**: 저장된 URL로 바로 진입

3. **URL 변경**: 앱 재설치만 가능 (앱 데이터 삭제도 가능)

---

## 앱 아이콘 교체

`app/src/main/res/` 아래 `mipmap-*` 폴더에 아이콘 파일 추가:
- `mipmap-mdpi/ic_launcher.png` (48x48)
- `mipmap-hdpi/ic_launcher.png` (72x72)
- `mipmap-xhdpi/ic_launcher.png` (96x96)
- `mipmap-xxhdpi/ic_launcher.png` (144x144)
- `mipmap-xxxhdpi/ic_launcher.png` (192x192)

Android Studio에서 `res` 우클릭 → `New` → `Image Asset` 으로 자동 생성 가능.

---

## 앱 이름 변경

`app/src/main/res/values/strings.xml` 에서 `app_name` 수정.

---

## 음악 재생 최적화 특징

- `mediaPlaybackRequiresUserGesture = false`: 자동재생 허용
- `WAKE_LOCK`: 화면 꺼져도 오디오 스트림 유지
- 전체화면 몰입 모드 (상태바/내비게이션바 숨김)
- 스크롤바 숨김, 텍스트 선택 비활성화
- WebView User-Agent에서 `wv` 태그 제거 (일부 사이트 제한 우회)
- 동영상 전체화면 지원
- 허용된 도메인 외부 링크 차단
