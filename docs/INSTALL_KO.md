# 설치 가이드

이 문서는 `LizzieYzy Next-FoxUID` 를 최대한 빨리 설치해서 사용하고 싶은 사용자를 위한 안내입니다.

## 먼저 패키지를 고르세요

| 환경 | 추천 패키지 | Java | KataGo |
| --- | --- | --- | --- |
| Windows x64 | `<date>-windows64.with-katago.installer.exe` | 포함 | 포함 |
| Windows x64 | `<date>-windows64.with-katago.portable.zip` | 포함 | 포함 |
| Windows x64 | `<date>-windows64.without.engine.portable.zip` | 포함 | 없음 |
| Windows x86 | `<date>-windows32.without.engine.zip` | 없음 | 없음 |
| macOS Apple Silicon | `<date>-mac-arm64.with-katago.dmg` | App 내장 | 포함 |
| macOS Intel | `<date>-mac-amd64.with-katago.dmg` | App 내장 | 포함 |
| Linux x64 | `<date>-linux64.with-katago.zip` | 포함 | 포함 |

가장 쉽게 시작하려면 `with-katago` 패키지를 선택하세요. Windows 에서는 보통 `installer.exe` 가 가장 쉬운 선택입니다.

## Windows

### Windows x64 설치 프로그램

1. `windows64.with-katago.installer.exe` 를 다운로드합니다.
2. 더블클릭해서 설치 프로그램을 실행합니다.
3. 설치 마법사의 안내에 따라 설치합니다.
4. 설치 후 시작 메뉴나 바탕화면 바로가기에서 실행합니다.

### Windows x64 포터블 패키지

1. `windows64.with-katago.portable.zip` 을 다운로드합니다.
2. 일반 폴더에 압축을 풉니다.
3. 압축을 푼 폴더에서 `LizzieYzy Next-FoxUID.exe` 를 실행합니다.

### Windows x64 무엔진 패키지

1. `windows64.without.engine.portable.zip` 을 다운로드합니다.
2. 압축을 푼 뒤 `LizzieYzy Next-FoxUID.exe` 를 실행합니다.
3. 이 패키지에는 KataGo 가 포함되지 않으므로, 실행 후 직접 엔진을 설정해야 합니다.

## macOS

1. 칩에 맞는 dmg 를 선택합니다.
   - Apple Silicon: `mac-arm64.with-katago.dmg`
   - Intel: `mac-amd64.with-katago.dmg`
2. dmg 를 열고 앱을 `Applications` 로 드래그합니다.
3. `Applications` 에서 앱을 실행합니다.

현재 macOS 패키지는 서명 / 공증되지 않은 유지보수 빌드입니다. 처음 실행이 막히면:

1. 먼저 한 번 실행을 시도합니다.
2. `시스템 설정 -> 개인정보 보호 및 보안` 으로 이동합니다.
3. `그래도 열기` 를 선택합니다.
4. 다시 실행합니다.

## Linux

1. `linux64.with-katago.zip` 을 다운로드합니다.
2. 압축을 풉니다.
3. 터미널에서 실행합니다.

```bash
chmod +x start-linux64.sh
./start-linux64.sh
```

## 첫 실행에 대해

현재 유지보수판은 첫 실행에서 다음 작업을 우선 시도합니다.

- 내장 KataGo 와 기본 가중치 파일 감지
- 사용 가능한 기본 엔진 설정 자동 생성
- 필요하면 권장 공식 가중치 안내

보통 `with-katago` 패키지라면 수동 설정 없이 바로 시작할 수 있는 경우가 많습니다.

## Fox 기보 가져오기

앱을 실행한 뒤 Fox 기보 메뉴에서 **숫자만 있는 Fox ID** 를 입력하세요.

- 사용자명 검색은 더 이상 지원하지 않습니다.
- 최신 공개 기보를 가져오는 흐름으로 통일되어 있습니다.

## 관련 문서

- [Installation Guide (English)](INSTALL_EN.md)
- [Troubleshooting (English)](TROUBLESHOOTING_EN.md)
- [Package Overview (English)](PACKAGES_EN.md)
- [Development Guide (English)](DEVELOPMENT_EN.md)
- [Tested Platforms](TESTED_PLATFORMS.md)
- [Support](../SUPPORT.md)
