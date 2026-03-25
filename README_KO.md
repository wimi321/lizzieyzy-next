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
  <strong>아직 lizzieyzy를 쓰고 싶은 사람을 위해, 다시 계속 사용할 수 있게 만든 유지보수판입니다.</strong><br/>
  원래 프로젝트가 오래 관리되지 않으면서 Fox 기보 가져오기가 막힌 사용자가 많아졌습니다. 이 포크는 먼저 그 실사용 경로를 복구하고, 첫 실행과 내장 KataGo 사용도 더 쉽게 정리했습니다.<br/>
  <strong>다운로드하고, Fox 닉네임을 입력하고, 다시 기보를 가져와 분석과 복기를 이어갈 수 있습니다.</strong>
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><strong>Releases</strong></a>
  ·
  <a href="docs/INSTALL_KO.md"><strong>설치 가이드</strong></a>
  ·
  <a href="docs/TROUBLESHOOTING_EN.md"><strong>문제 해결</strong></a>
</p>

> [!IMPORTANT]
> 먼저 이 4가지만 보면 됩니다:
> - 대부분의 Windows 사용자는 `windows64.with-katago.installer.exe` 를 받으면 가장 쉽습니다
> - PC 에 **NVIDIA 그래픽카드** 가 있고 더 빠른 분석을 원하면 `windows64.nvidia.installer.exe` 를 고르면 됩니다
> - Fox 기보 가져오기는 이제 **Fox 닉네임** 을 입력하면 됩니다. 앱이 맞는 계정을 자동으로 찾습니다
> - 주 추천 통합 패키지는 KataGo 를 포함하고 있고, 첫 실행에서는 자동 설정을 먼저 시도합니다

## 커뮤니티와 현재 방향

| 지금 하고 싶은 일 | 먼저 볼 곳 |
| --- | --- |
| 다운로드하고 설치하기 | [Releases](https://github.com/wimi321/lizzieyzy-next/releases) / [설치 가이드](docs/INSTALL_KO.md) |
| 버그나 설치 결과 공유하기 | [Support](SUPPORT.md) |
| 사용 경험과 아이디어 이야기하기 | [GitHub Discussions](https://github.com/wimi321/lizzieyzy-next/discussions) / QQ 그룹 `299419120` |
| 프로젝트가 다음에 집중할 일 보기 | [ROADMAP.md](ROADMAP.md) |
| 유지보수에 참여하기 | [CONTRIBUTING.md](CONTRIBUTING.md) |

이 저장소는 다음 같은 실사용 목표를 우선합니다.

- 아직 `lizzieyzy` 흐름을 쓰는 사용자들의 핵심 경로를 계속 유지하기
- Fox 기보 가져오기, KataGo 포함 패키지, 첫 실행 경험을 이해하기 쉽게 유지하기
- 설정에 익숙한 소수만이 아니라 일반 사용자도 바로 쓰기 쉽게 만들기

## Windows 사용자는 여기부터 보면 됩니다

**Windows** 를 쓴다면:

- 대부분의 사용자는 **`windows64.with-katago.installer.exe`**
- NVIDIA 그래픽카드가 있고 속도를 더 원하면 **`windows64.nvidia.installer.exe`**

앞의 패키지는 가장 쉬운 기본판이고, NVIDIA 패키지는 NVIDIA GPU 사용자를 위한 빠른 분석판입니다.

## 무엇을 다운로드해야 하나요

표보다 먼저 그림으로 보고 싶다면 이 선택 가이드를 보면 됩니다.

<p align="center">
  <img src="assets/package-guide.svg" alt="LizzieYzy Next package guide" width="100%" />
</p>

| 내 컴퓨터 | 먼저 받을 파일 |
| --- | --- |
| Windows x64 | `windows64.with-katago.installer.exe` |
| Windows x64, NVIDIA GPU, 더 빠르게 | `windows64.nvidia.installer.exe` |
| Windows x64, NVIDIA GPU, 설치 없이 사용 | `windows64.nvidia.portable.zip` |
| Windows x64, 설치 없이 사용 | `windows64.with-katago.portable.zip` |
| Windows x64, 내 엔진 사용 + 설치형 | `windows64.without.engine.installer.exe` |
| Windows x64, 내 엔진 사용 | `windows64.without.engine.portable.zip` |
| macOS Apple Silicon | `mac-arm64.with-katago.dmg` |
| macOS Intel | `mac-amd64.with-katago.dmg` |
| Linux x64 | `linux64.with-katago.zip` |

간단한 기준:

- Windows: NVIDIA GPU 인지 모르겠으면 `windows64.with-katago.installer.exe`
- Mac: Apple Silicon 인지 Intel 인지 먼저 확인
- Linux: `with-katago.zip`

짧게 정리하면:

- `installer.exe` 는 대부분의 Windows 사용자용 기본 선택
- `nvidia.installer.exe` 는 NVIDIA GPU 사용자용 빠른 패키지
- `nvidia.portable.zip` 는 NVIDIA GPU 사용자용 무설치 패키지
- `portable.zip` 는 설치 없이 압축만 풀어 쓰고 싶은 경우
- `without.engine.installer.exe` 는 설치형을 원하지만 엔진은 직접 쓰려는 경우
- `without.engine.portable.zip` 는 이미 자기 엔진을 관리하고 설치도 원하지 않는 경우

## 이 유지보수판이 해결한 것

- **Fox 기보 가져오기가 다시 됩니다**
  원 프로젝트에서 멈춰 있던 공개 기보 가져오기 흐름을 다시 사용할 수 있게 복구했습니다.
- **이제 먼저 숫자 계정 ID를 알 필요가 없습니다**
  Fox 닉네임만 입력하면 앱이 해당 계정을 찾고 공개 기보를 가져옵니다.
- **첫 실행이 더 편해졌습니다**
  처음부터 수동 설정을 요구하기보다, 내장 분석 환경을 먼저 준비하도록 바꿨습니다.

## 3단계로 시작

1. [Releases](https://github.com/wimi321/lizzieyzy-next/releases) 에서 내 시스템에 맞는 패키지를 다운로드합니다.
2. **Fox 기보 가져오기(닉네임 검색)** 메뉴를 엽니다.
3. Fox 닉네임을 입력하고 최신 공개 기보를 가져온 뒤 바로 복기와 분석을 이어갑니다.

<p align="center">
  <a href="assets/fox-id-demo.gif">
    <img src="assets/fox-id-demo-cover.png" alt="LizzieYzy Next Demo" width="100%" />
  </a>
</p>

<p align="center">
  GitHub 에서 GIF 재생이 느리면 위 이미지를 눌러 전체 애니메이션을 열 수 있습니다.
</p>

## 실제 화면

아래 이미지는 지금 유지보수 중인 현재 버전의 실제 화면입니다. 하단에서 **野狐棋谱** 와 **공식 가중치 업데이트** 같은 실사용 버튼을 바로 볼 수 있습니다.

<p align="center">
  <img src="assets/interface-overview.png" alt="LizzieYzy Next actual interface" width="100%" />
</p>

자주 쓰는 입구는 처음부터 메인 화면에서 바로 보이게 유지하고 있습니다.

| 하고 싶은 일 | 메인 화면에서 바로 보이는 것 |
| --- | --- |
| 최근 공개 기보 가져오기 | `野狐棋谱` |
| 공식 KataGo 가중치 업데이트 | `更新官方权重` |
| 바로 AI 복기 이어가기 | `Kata评估` / `自动分析` |

## 통합 패키지에 이미 들어 있는 것

| 항목 | 현재 값 |
| --- | --- |
| KataGo 버전 | `v1.16.4` |
| 기본 가중치 | `g170e-b20c256x2-s5303129600-d1228401921.bin.gz` |
| 첫 실행 자동 설정 | 사용 |
| 공식 가중치 다운로드 입구 | 포함 |

대부분의 사용자에게 중요한 요점은 이것입니다.

**주 추천 통합 패키지는 KataGo 와 기본 가중치를 이미 포함하고 있어서, 설치 후 바로 시작하기 쉽습니다.**

## 자주 묻는 질문

<details>
<summary><strong>먼저 Fox 계정 번호를 알아야 하나요?</strong></summary>

아니요. Fox 닉네임만 입력하면 됩니다. 앱이 맞는 계정을 자동으로 찾고, 결과 목록에는 닉네임과 계정 번호가 함께 표시됩니다.
</details>

<details>
<summary><strong>왜 닉네임 입력으로 바꿨나요?</strong></summary>

일반 사용자는 계정 번호보다 닉네임을 더 잘 알고 있기 때문입니다. 유지보수판에서는 그 조회 과정을 앱 안으로 다시 넣었습니다.
</details>

<details>
<summary><strong>기보가 안 나오면 무엇을 확인해야 하나요?</strong></summary>

닉네임이 정확한지, 그 계정에 최근 공개 기보가 있는지, 일시적인 네트워크 문제가 없는지 먼저 확인해 주세요.
</details>

<details>
<summary><strong>첫 실행에서 KataGo 를 직접 설정해야 하나요?</strong></summary>

대부분의 `with-katago` 사용자에게는 필요 없습니다. 내장된 KataGo, 가중치, 설정 경로를 자동으로 찾는 흐름을 우선합니다.
</details>

<details>
<summary><strong>macOS 에서 처음 실행이 막히면 어떻게 하나요?</strong></summary>

현재 macOS 빌드는 아직 서명과 공증이 되어 있지 않습니다. 처음 막히면 [설치 가이드](docs/INSTALL_KO.md) 의 안내를 따라 주세요.
</details>

## 더 보기

- [설치 가이드](docs/INSTALL_KO.md)
- [Package Overview](docs/PACKAGES_EN.md)
- [Troubleshooting](docs/TROUBLESHOOTING_EN.md)
- [Tested Platforms](docs/TESTED_PLATFORMS.md)
- [Roadmap](ROADMAP.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)
- [Support](SUPPORT.md)

## 크레딧

- Original project: [yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- KataGo: [lightvector/KataGo](https://github.com/lightvector/KataGo)
- Fox 기보 가져오기 참고:
  - [yzyray/FoxRequest](https://github.com/yzyray/FoxRequest)
  - [FuckUbuntu/Lizzieyzy-Helper](https://github.com/FuckUbuntu/Lizzieyzy-Helper)
