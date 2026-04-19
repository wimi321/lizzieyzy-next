<p align="center">
  <img src="assets/hero-korean.svg" alt="LizzieYzy Next" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next?display_name=tag&label=Release&color=111111" alt="Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/stargazers"><img src="https://img.shields.io/github/stars/wimi321/lizzieyzy-next?style=flat&color=444444" alt="Stars"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/downloads/wimi321/lizzieyzy-next/total?label=Downloads&color=666666" alt="Downloads"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-888888" alt="Platforms">
</p>

<p align="center">
  <a href="README.md">中文</a> · <a href="README_EN.md">English</a> · <a href="README_JA.md">日本語</a> · 한국어
</p>

<p align="center">
  <strong>LizzieYzy Next 는 지금도 유지보수 중인 lizzieyzy 계열 실사용판이며, 일반 사용자를 위한 KataGo 복기 GUI 입니다.</strong><br/>
  사용자가 실제로 체감하는 부분인 배포판 선택, 첫 실행 준비, Fox 기보 가져오기, 전판을 한눈에 읽는 분석 경험을 지금 시점에 맞게 다시 다듬고 있습니다.<br/>
  <strong>다운로드하고, Fox 닉네임을 입력하고, 공개 기보를 가져오고, 빠른 전판 분석을 돌린 뒤, 새 승률 그래프와 하단 빠른 개요로 중요한 수로 바로 이동할 수 있습니다.</strong>
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><strong>Releases</strong></a>
  ·
  <a href="https://pan.baidu.com/s/1wthaL8YwGMxy_u0U7Mabpw?pwd=3i8w"><strong>Baidu 다운로드</strong></a>
  ·
  <a href="docs/INSTALL_KO.md"><strong>설치 가이드</strong></a>
  ·
  <a href="docs/TROUBLESHOOTING_EN.md"><strong>문제 해결</strong></a>
</p>

> [!NOTE]
> 중국 본토 사용자라면 공개 Baidu Netdisk 다운로드도 바로 사용할 수 있습니다:
> [https://pan.baidu.com/s/1wthaL8YwGMxy_u0U7Mabpw?pwd=3i8w](https://pan.baidu.com/s/1wthaL8YwGMxy_u0U7Mabpw?pwd=3i8w)
> 추출 코드: `3i8w`

> [!TIP]
> 중국어 QQ 그룹: `299419120`
>
> 일상적인 사용 질문, 버그 제보, 기능 요청을 가장 빠르게 주고받는 곳입니다.

> [!IMPORTANT]
> 먼저 이 6가지만 기억하면 됩니다:
> - 대부분의 Windows 사용자는 [Releases](https://github.com/wimi321/lizzieyzy-next/releases) 에서 `*windows64.opencl.portable.zip` 을 받으면 가장 쉽습니다
> - NVIDIA GPU 가 있고 더 빠른 분석을 원하면 `*windows64.nvidia.portable.zip` 을 선택하면 됩니다
> - OpenCL 이 잘 맞지 않으면 `*windows64.with-katago.portable.zip` 으로 바꿔 쓸 수 있습니다
> - Fox 기보 가져오기는 닉네임 입력을 지원하므로, 많은 사용자는 계정 번호를 먼저 알 필요가 없습니다
> - 주요 통합 패키지에는 KataGo `v1.16.4` 와 공식 추천 `zhizi` 가중치 `kata1-zhizi-b28c512nbt-muonfd2.bin.gz` 가 포함됩니다
> - 주요 release 패키지에는 `readboard_java` 도 들어 있으므로, 대부분의 사용자는 별도 readboard 저장소가 필요하지 않습니다

## 왜 많은 사용자가 여기서 시작하는가

`LizzieYzy Next` 는 이렇게 이해하면 됩니다.

- 일반 사용자가 바로 쓰기 쉬운 `KataGo 복기 소프트웨어`
- `Fox 기보 가져오기 + 빠른 전판 분석 + 여러 OS 배포판` 을 한데 묶은 실사용 워크플로
- 오래된 `lizzieyzy` 환경에서 이어서 쓰기 쉬운 현재 유지보수 브랜치

다음 같은 것을 찾고 있다면 이 프로젝트를 먼저 보면 됩니다.

- `KataGo 복기 소프트웨어`
- `KataGo GUI`
- `lizzieyzy 유지보수판`
- `Fox 기보 가져오기 + KataGo 복기`
- `Windows 무설치 바둑 AI 도구`

## 실행하자마자 할 수 있는 것

| 하고 싶은 일 | 지금 이 프로젝트에서 어떻게 해결되는가 |
| --- | --- |
| 최근 공개 Fox 기보 가져오기 | Fox 닉네임을 입력하면 앱이 맞는 계정을 자동으로 찾습니다 |
| 전판 흐름을 빨리 보기 | 한 수씩 수동으로 넘기지 않아도 빠른 전판 분석을 사용할 수 있습니다 |
| 문제수를 빨리 찾기 | 새 메인 승률 그래프와 하단 열지도 개요로 큰 손해 구간을 더 쉽게 찾습니다 |
| 설정을 덜 건드리기 | 추천 패키지에 KataGo, 기본 가중치, 첫 실행 자동 설정이 들어 있습니다 |
| 설치 없이 쓰기 | Windows 에서는 `portable.zip` 을 우선 고를 수 있습니다 |
| 바둑판 동기화도 쓰기 | 주요 배포판에 `readboard_java` 가 포함됩니다 |

## 먼저 무엇을 다운로드할까

모든 배포판은 [Releases](https://github.com/wimi321/lizzieyzy-next/releases) 에 있습니다. 아래 표는 최신 release 페이지에서 찾을 때 쓰기 좋은 파일명 키워드입니다.

<p align="center">
  <img src="assets/package-guide.svg" alt="LizzieYzy Next package guide" width="100%" />
</p>

| 내 환경 | Releases 에서 이 키워드를 포함하는 파일 찾기 |
| --- | --- |
| 대부분의 Windows 사용자, 추천, 무설치 | `*windows64.opencl.portable.zip` |
| Windows, OpenCL 버전, 설치형 | `*windows64.opencl.installer.exe` |
| Windows, OpenCL 이 불안정, CPU 대안, 무설치 | `*windows64.with-katago.portable.zip` |
| Windows, CPU 대안, 설치형 | `*windows64.with-katago.installer.exe` |
| Windows, NVIDIA GPU, 더 빠른 분석, 무설치 | `*windows64.nvidia.portable.zip` |
| Windows, NVIDIA GPU, 설치형 | `*windows64.nvidia.installer.exe` |
| Windows, 내 엔진 사용, 무설치 | `*windows64.without.engine.portable.zip` |
| Windows, 내 엔진 사용, 설치형 | `*windows64.without.engine.installer.exe` |
| macOS Apple Silicon | `*mac-arm64.with-katago.dmg` |
| macOS Intel | `*mac-amd64.with-katago.dmg` |
| Linux | `*linux64.with-katago.zip` |

헷갈릴 때는 이렇게 보면 됩니다:

- Windows: 먼저 `*windows64.opencl.portable.zip`
- Windows + NVIDIA GPU: 먼저 `*windows64.nvidia.portable.zip`
- OpenCL 이 잘 맞지 않음: `*windows64.with-katago.portable.zip`
- Mac: Apple Silicon 인지 Intel 인지 먼저 확인
- Linux: `*linux64.with-katago.zip`

## 왜 지금 이 버전이 더 쓰기 쉬운가

- `Fox 기보 가져오기가 다시 작동`
  사용자가 기억하는 닉네임에서 바로 시작할 수 있습니다.
- `빠른 전판 분석이 주 흐름이 됨`
  한 수씩 쌓지 않아도 전체 그림을 빨리 만들 수 있습니다.
- `메인 승률 그래프 + 하단 빠른 개요`
  큰 손해가 나온 구간을 먼저 보기 쉬워졌습니다.
- `Windows 는 무설치판 우선`
  OpenCL / NVIDIA / CPU 대안의 차이를 더 쉽게 이해할 수 있습니다.
- `readboard_java 를 메인 release 에 포함`
  대부분의 사용자는 별도 저장소를 조합할 필요가 없습니다.
- `실제 release + 실제 스모크 테스트`
  소스만 바꾸는 것이 아니라, 여러 OS 배포판과 실제 흐름 검증도 계속 하고 있습니다.

## 3단계로 시작

1. [Releases](https://github.com/wimi321/lizzieyzy-next/releases) 에서 내 환경에 맞는 패키지를 다운로드합니다.
2. `Fox Kifu` 를 열고 Fox 닉네임을 입력합니다.
3. 기보를 가져온 뒤 빠른 전판 분석을 돌리고, 그래프와 개요에서 중요한 수로 바로 이동합니다.

<p align="center">
  <a href="assets/fox-id-demo.gif">
    <img src="assets/fox-id-demo-cover.png" alt="LizzieYzy Next Fox nickname demo" width="100%" />
  </a>
</p>

<p align="center">
  GitHub 에서 GIF 재생이 느리면 위 이미지를 눌러 전체 애니메이션을 열 수 있습니다.
</p>

## 실제 화면

아래 이미지는 현재 유지보수판의 실제 화면입니다.

<p align="center">
  <img src="assets/interface-overview-2026-04.png" alt="LizzieYzy Next actual interface" width="100%" />
</p>

그래프 영역은 이렇게 읽으면 됩니다.

<p align="center">
  <img src="assets/winrate-quick-overview-2026-04.png" alt="LizzieYzy Next winrate graph and quick overview" width="46%" />
</p>

- 파란선 / 자홍선: 양쪽 승률 흐름
- 초록선: 집 차이 변화
- 아래 열지도 막대: 전판에서 큰 문제수가 몰린 구간
- 세로 가이드선: 현재 수나 마우스를 올린 수의 위치

## 원래 lizzieyzy 와의 차이

| 비교 항목 | 원래 `lizzieyzy` | `LizzieYzy Next` |
| --- | --- | --- |
| 현재 상태 | 많은 사용자가 기억하는 원 프로젝트지만 실사용 유지보수는 약함 | 사용성과 배포 경험을 계속 다듬는 현재 유지보수 브랜치 |
| Fox 기보 가져오기 | 예전 흐름은 깨진 환경이 많음 | 자주 쓰는 가져오기 흐름을 복구했고 닉네임 입력 지원 |
| 입력 방식 | 숫자 계정 번호를 먼저 알아야 하는 경우가 많음 | Fox 닉네임을 넣으면 앱이 계정을 자동으로 찾음 |
| KataGo 사용 장벽 | 직접 환경이나 누락 리소스를 채워야 하는 경우가 많음 | 추천 패키지에 KataGo 와 기본 가중치가 이미 포함됨 |
| Windows 다운로드 경험 | 사용자가 직접 판단해야 할 부분이 더 많음 | `portable.zip` 우선 추천으로 더 명확함 |
| 동기화 도구 | 사용자가 직접 조합해야 하는 경우가 많음 | 주요 release 에 `readboard_java` 포함 |

## 자주 묻는 질문

### readboard 용으로 별도 저장소가 아직도 필요한가?

대부분의 사용자는 필요 없습니다. `LizzieYzy Next` 는 `readboard_java` 를 주요 release 패키지에 포함합니다.

### Fox 계정 번호를 먼저 알아야 하나?

대부분의 경우 그럴 필요 없습니다. Fox 닉네임을 입력하면 앱이 맞는 계정을 찾아 줍니다.

### 전판 흐름을 보려면 아직도 한 수씩 넘겨야 하나?

보통은 그럴 필요가 없습니다. 빠른 전판 분석이 있어서 메인 승률 그래프와 개요가 훨씬 빨리 만들어집니다.

### macOS 에서 첫 실행 때 막히면 어떻게 하나?

현재 macOS 배포판은 아직 서명과 공증이 없습니다. 첫 실행에서 막히면 [설치 가이드](docs/INSTALL_KO.md) 를 따라 진행하면 됩니다.

## 사용자 문서

- [지원 가이드](SUPPORT.md)
- [설치 가이드](docs/INSTALL_KO.md)
- [패키지 안내 (English)](docs/PACKAGES_EN.md)
- [문제 해결 (English)](docs/TROUBLESHOOTING_EN.md)
- [검증된 플랫폼 (English)](docs/TESTED_PLATFORMS.md)
- [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases)
- GitHub Discussions: <https://github.com/wimi321/lizzieyzy-next/discussions>
- 중국어 QQ 그룹: `299419120`

## 프로젝트 링크
- [Roadmap](ROADMAP.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)
- [Support](SUPPORT.md)

## Credits

- Original project: [yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- KataGo: [lightvector/KataGo](https://github.com/lightvector/KataGo)
Historical Fox sync references:
- [yzyray/FoxRequest](https://github.com/yzyray/FoxRequest)
- [FuckUbuntu/Lizzieyzy-Helper](https://github.com/FuckUbuntu/Lizzieyzy-Helper)
