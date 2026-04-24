# 설치 가이드

이 문서는 `LizzieYzy Next` 를 최대한 빨리 설치해서 사용하고 싶은 사용자를 위한 안내입니다.

## 먼저 결론

이 가이드는 지금도 유지보수 중인 `LizzieYzy Next` 용입니다. 많은 사용자가 찾는 `lizzieyzy 유지보수판 / 대체판` 이라고 보면 됩니다.

1. Windows 용 `KataGo 복기 프로그램` 무설치 패키지를 찾는다면 먼저 `windows64.opencl.portable.zip` 을 선택하면 됩니다.
2. `아직 쓸 수 있는 lizzieyzy 유지보수판` 을 찾고 있다면 이 프로젝트를 우선 보면 됩니다.
3. `Fox 닉네임` 을 입력해 기보를 가져오고 바로 복기하는 흐름을 이미 지원합니다.
4. 추천 통합 패키지에는 KataGo 와 기본 가중치가 이미 포함되어 있습니다.

## 먼저 기억할 것

1. 대부분의 Windows 사용자는 `windows64.opencl.portable.zip` 를 선택하면 됩니다.
2. NVIDIA 그래픽카드가 있고 더 빠른 분석을 원하면 `windows64.nvidia.portable.zip` 를 선택할 수 있습니다.
3. `with-katago` 패키지는 KataGo 와 기본 가중치를 포함합니다.
4. Fox 기보를 가져올 때는 이제 **Fox 닉네임** 을 입력합니다.

## 무엇을 다운로드하나요

| 환경 | 추천 패키지 |
| --- | --- |
| Windows x64 | `<date>-windows64.opencl.portable.zip` |
| Windows x64, OpenCL 설치형 | `<date>-windows64.opencl.installer.exe` |
| Windows x64, CPU 대안 | `<date>-windows64.with-katago.portable.zip` |
| Windows x64, CPU 대안 설치형 | `<date>-windows64.with-katago.installer.exe` |
| Windows x64, NVIDIA GPU, 더 빠른 분석 | `<date>-windows64.nvidia.portable.zip` |
| Windows x64, NVIDIA GPU, 설치형 | `<date>-windows64.nvidia.installer.exe` |
| Windows x64, 직접 엔진 설정 | `<date>-windows64.without.engine.portable.zip` |
| Windows x64, 내 엔진 사용 + 설치형 | `<date>-windows64.without.engine.installer.exe` |
| macOS Apple Silicon | `<date>-mac-apple-silicon.with-katago.dmg` |
| macOS Intel | `<date>-mac-intel.with-katago.dmg` |
| Linux x64 | `<date>-linux64.with-katago.zip` |

## Windows

1. 먼저 `windows64.opencl.portable.zip` 를 다운로드합니다.
2. 압축을 풀고 `LizzieYzy Next OpenCL.exe` 를 실행합니다.
3. 설치형 흐름이 필요하면 `windows64.opencl.installer.exe` 를 선택할 수 있습니다.

일반 Windows 패키지도 `KataGo Auto Setup` 에서 `Smart Optimize` 를 한 번 실행하면 더 잘 맞는 스레드 수를 자동으로 저장할 수 있습니다.

NVIDIA 그래픽카드가 있고 속도를 더 원한다면:

1. `windows64.nvidia.portable.zip` 를 다운로드합니다.
2. 압축을 푼 뒤 `LizzieYzy Next NVIDIA.exe` 를 실행합니다.
3. 처음 실행할 때 필요한 공식 NVIDIA 런타임을 사용자 폴더에 자동으로 준비합니다.
4. 설치형이 필요하면 `windows64.nvidia.installer.exe` 를 선택할 수 있습니다.

OpenCL 이 잘 맞지 않다면 `windows64.with-katago.portable.zip` 를 쓰고, 설치형이 필요하면 `windows64.with-katago.installer.exe` 를 고르면 됩니다.

NVIDIA 그래픽카드인지 확실하지 않다면 일반 `windows64.opencl.portable.zip` 를 선택하면 됩니다.

직접 엔진을 쓰고 싶다면:

1. `windows64.without.engine.portable.zip` 또는 `windows64.without.engine.installer.exe` 를 선택합니다.
2. portable 이면 압축을 푼 뒤 `LizzieYzy Next.exe` 를 실행하고, 설치형이면 설치를 완료합니다.
3. 실행 후 자신의 엔진을 설정합니다.

## macOS

1. Apple Silicon 인지 Intel 인지 확인합니다.
2. 맞는 `.dmg` 를 엽니다.
3. `LizzieYzy Next.app` 을 `Applications` 로 드래그합니다.
4. 처음 막히면 `시스템 설정 -> 개인정보 보호 및 보안` 에서 `그래도 열기` 를 선택합니다.

## Linux

```bash
chmod +x start-linux64.sh
./start-linux64.sh
```

## 첫 실행에서 자동으로 하는 일

- 내장 KataGo 감지
- 기본 가중치 확인
- 사용 가능한 기본 설정 작성

## Fox 기보 가져오기

1. 앱을 실행합니다.
2. **Fox 기보 가져오기(닉네임 검색)** 를 엽니다.
3. Fox 닉네임을 입력합니다.
4. 앱이 맞는 계정을 찾아 최근 공개 기보를 가져옵니다.

닉네임이 틀렸거나 공개 기보가 없으면 결과가 비어 있을 수 있습니다.
