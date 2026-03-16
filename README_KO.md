# LizzieYzy Next-FoxUID - 바둑 엔진용 GUI
## 먼저 읽어주세요
**원래 LizzieYzy에서는 텐센트 Fox 기보 동기화 기능이 제대로 작동하지 않았습니다. 이 유지보수 포크에서 그 기능을 복구했고, 이제 Fox ID를 입력해 최신 공개 기보를 가져올 수 있습니다.**

## 유지보수 핵심 변경
**이 포크에서 가장 중요한 변경점은 아래와 같습니다.**

* **Fox 기보 동기화 복구**: 원래 흐름이 이미 깨져 있어서, 이제 Fox H5 API를 직접 호출하도록 수정했습니다.
* **Fox ID로 바로 가져오기**: 사용자명 검색은 제거했고, Fox ID를 입력해 최신 공개 기보를 가져오도록 바꿨습니다.
* **멀티 플랫폼 배포 복구**: Windows / macOS / Linux 배포 파일을 다시 정리했습니다.

## 3단계 빠른 시작
1. [Releases](https://github.com/wimi321/lizzieyzy-next-foxuid/releases) 에서 내 시스템에 맞는 패키지를 다운로드합니다.
2. `with-katago` 패키지는 압축 해제 또는 설치 후 바로 사용할 수 있습니다. `without.engine` 패키지는 엔진을 직접 설정해야 합니다.
3. 앱을 열고 Fox ID를 입력해 최신 공개 기보를 가져옵니다.

![screenshot_ko](/screenshot_ko.png?raw=true)

LizzieYzy Next-FoxUID는 더 이상 유지되지 않는 원본 LizzieYzy에서 망가진 기능을 복구하고 계속 사용할 수 있게 유지하는 포크입니다. 현재 이 포크는 Fox 기보 동기화를 복구했고, 더 이해하기 쉬운 Fox ID 입력 방식으로 바꿨습니다.

LizzieYzy는 [Lizzie](https://github.com/featurecat/lizzie)를 기반으로 [Katago](https://github.com/lightvector/KataGo)、[LeelaZero](https://github.com/leela-zero/leela-zero)、[Leela](https://github.com/gcp/Leela)、[ZenGTP](https://github.com/yzyray/ZenGTP)、[SAI](http://sai.unich.it)、[Pachi](https://github.com/pasky/pachi) 등의 다른 GTP engine들을 로드할 수 있도록 수정된 그래픽 인터페이스입니다.

Lizzie 기반에 몇 가지 새로운 기능 추가: **Hawk Eye, Flash Analyze, Batch Analyze, Estimate, Board Synchronization(only windows), Engine Game, Tsumego Analysis, Double Engine Mode, Visualized KataGo Distributed Training** 및 몇몇 세부 사항 조정, 고해상도를 완벽하게 지원하여 시스템 스케일링으로 인해 흐릿하게 표시되지 않습니다.
#
* 새로운 기능

  * **Hawk Eye**: 정확도, 승률 차이, 점수 차이, 엔진 후보 수와 실제 착수의 차이를 기반으로 한 실착수를 찾아서 차트에 표시.

  * **Flash Analyze**: Katago의 분석 모드를 사용함. 모든 기보를 병렬로 분석. 승률 그래프 후보를 빠르게 획득. 일괄 분석 지원.

  * **Batch Analyze**: GTP engine 또는 Katago의 분석 모드를 사용한 다수 기보 일괄 분석 지원.

  * **Estimate**: Katago(기본값)의 `kata-raw-nn` 명령 또는 ZenGTP의 `territory` 명령을 사용하여 현재 집 수 계산. 각 착수 후의 집 수 자동 예측을 지원.

  * **Board Synchronization(C#)**: [Repository](https://github.com/yzyray/readboard) 두 가지 모드: 전경(바둑판을 이동하거나 덮을 수 없음)/배경. FoxWQ, TYGEM, SINA 플랫폼에 최적화. 버튼을 클릭하여 동기화를 허용. 다른 플랫폼이나 사진 또는 착수를 통한 동기화 시 바둑판이 포함된 영역을 선택해야 함. 양방향 자동 착수 전달 지원. C#으로 개발되어서 Windows만 지원.

  * **Board Synchronization(Java)**: [Repository](https://github.com/yzyray/readboard_Boofcv) 전경 전용. 바둑판이 포함된 영역을 선택해야 함. 양방향 자동 착수 전달 지원.

  * **Engine Game**: 두 엔진 간의 1회(또는 다회) 대국을 수행. 대국 시작점으로 일부 sgf를 사용할 수 있습니다. 착수를 위한 다양한 명령 지원: `lz-analyze`, `kata-analyze`, `genmove`. 다회 대국시에 몇 가지 통계를 수집합니다: elo, stdev 간격 등.

  * **Tsumego Analysis**: Support capture tsumego in part of goban, and automatically generate other part of stones help engine analyze in right area, refer to [Analyze]-[Tsumego frame] or [Capture tsumego] or [Tsumego] button in toolbar.

  * **Double Engine Mode**: 2개 엔진을 로드하여 동시 분석을 지원. 비교에 편리합니다.

  * **Visualized KataGo Distributed Training**: 시각화된 공식 KataGo 훈련. 모든 대국(진행중이거나 완료된)을 시청할 수 있습니다.

#
 * [사용 지침(영문)](https://github.com/yzyray/lizzieyzy/blob/main/readme_en.pdf)
 * 기타 jar 소스 코드 링크: [foxRequestQ.jar](https://github.com/yzyray/FoxRequest) [InVisibleFrame.jar](https://github.com/yzyray/testbuffer) [CaptureTsumeGo.jar](https://github.com/yzyray/captureTsumeGo/blob/main/README.md)
