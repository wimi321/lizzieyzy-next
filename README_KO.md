<p align="center">
  <img src="assets/hero.svg" alt="LizzieYzy Next-FoxUID" width="100%" />
</p>

<p align="center">
  <a href="README.md">中文</a> · <a href="README_EN.md">English</a> · <a href="README_JA.md">日本語</a> · 한국어
</p>

# LizzieYzy Next-FoxUID

**고장 난 Fox 기보 동기화를 복구하고, 실제로 쓰기 쉬운 배포 패키지까지 정리한 유지보수형 LizzieYzy 포크입니다.**

> [!IMPORTANT]
> 원래 LizzieYzy에서는 Fox 기보 동기화가 사실상 잘 동작하지 않았습니다. 이 포크는 그 흐름을 복구하고 입력도 Fox ID 기준으로 통일했습니다.

## 이 포크가 필요한 이유

원래 LizzieYzy는 여전히 매력적인 기능이 많지만, Fox 기보 동기화처럼 중요한 기능이 그대로는 잘 동작하지 않는 상태였습니다. 이 포크는 그런 부분을 다시 실사용 가능하게 만드는 데 집중합니다.

핵심 변화:

- **Fox 기보 동기화 복구**
- **입력 방식을 Fox ID로 통일**
- **Windows / macOS / Linux 배포 정리**
- **지속 유지보수 전제의 포크 운영**

## 스크린샷

![LizzieYzy Next-FoxUID Screenshot](screenshot_ko.png)

## 패키지 선택 가이드

| 시스템 | 추천 패키지 | Java | KataGo |
| --- | --- | --- | --- |
| Windows x64 | `windows64.with-katago.zip` | 포함 | 포함 |
| Windows x64 | `windows64.without.engine.zip` | 포함 | 없음 |
| macOS Apple Silicon | `mac-arm64.with-katago.dmg` | App 내장 | 포함 |
| macOS Intel | `mac-amd64.with-katago.dmg` | App 내장 | 포함 |
| Linux x64 | `linux64.with-katago.zip` | 포함 | 포함 |

Releases: <https://github.com/wimi321/lizzieyzy-next-foxuid/releases>

## 빠른 시작

1. Releases 에서 내 시스템에 맞는 패키지를 다운로드합니다.
2. 가장 쉽게 시작하려면 `with-katago` 패키지를 선택합니다.
3. 앱을 열고 Fox ID를 입력해 최신 공개 기보를 가져옵니다.

Detailed install guide: [Installation Guide](docs/INSTALL_EN.md)

## 내장 정보

- KataGo: `v1.16.4`
- 기본 가중치: `g170e-b20c256x2-s5303129600-d1228401921.bin.gz`
- macOS 가중치 경로: `LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`

## 참여하기

- [Contributing Guide](CONTRIBUTING.md)
- [Code Of Conduct](CODE_OF_CONDUCT.md)
- [Security Policy](SECURITY.md)
- [Issues](https://github.com/wimi321/lizzieyzy-next-foxuid/issues)
- [Discussions](https://github.com/wimi321/lizzieyzy-next-foxuid/discussions)
