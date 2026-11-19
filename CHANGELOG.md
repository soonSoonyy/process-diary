# 변경 내역

버전을 올릴 때는 `gradle.properties`의 `pluginVersion`을 바꾸고 이 파일 맨 위에 같은 버전의 항목을 추가하세요.
`main`에 푸시하면 GitHub Release와 IDE 업데이트가 자동으로 만들어집니다.

## [1.3.0]
- Java processes show what they run: main class or jar (e.g. "Gradle Daemon", "ShopApplication", "app.jar"), Java heap usage and Java version
- Java monitoring: heap graph (used / reserved / max), GC time graph, GC count and pause time, metaspace, threads, loaded classes; "Java processes" scope
- Java actions: thread dump (opens in an editor tab), heap dump (.hprof, after a confirmation) and Run GC, using jcmd
- GC alert: notify when a Java process spends too much time in GC (default 25% over the alert period), even with the tool window closed
- Run configurations: processes started from the IDE show their run configuration name; "IDE runs" scope; "Show in Run window"; Stop goes through the IDE
- Spring Boot Actuator: health status and components, app name and version, and endpoint links in the detail panel
- Diary tab: a saved log of runs, port conflicts, stopped processes, alerts and Docker / Java actions, with filters; heap-after-GC trend over 6 hours with a memory leak hint
- "Report on GitHub" in the IDE error dialog opens a prefilled issue
- Settings for all of the above: run configuration names, stopping through the IDE, Actuator on/off and paths, heap trend, diary on/off, which events to record and how many to keep
- Containers tab: Docker containers with published ports, CPU and memory; start, stop, restart, remove; "This project only" for docker compose
- Port conflicts caused by a Docker container now offer "Stop container and rerun" instead of stopping Docker's port forwarder
- Usage alerts: notify when CPU or memory stays above a limit (default 90% for 1 minute), with the top processes and a Stop button
- Port conflicts are handled automatically when the port is held by something clearly left over from this project (started by you, in this project folder, before this run, not running from the IDE); otherwise you are asked
- Windows: working directory column, Java heap, and "Stop" asks the program to close before terminating it
- 이 프로젝트에서 남겨진 프로세스나 compose 컨테이너 때문에 생긴 포트 충돌은 묻지 않고 자동으로 정리 후 다시 실행 (설정에서 끌 수 있음)
- 윈도우: 작업 폴더 표시, Java 힙 표시, "종료"는 먼저 닫기 요청 후 강제 종료
- Java 프로세스에 실행 중인 메인 클래스/jar 이름, 힙 사용량, Java 버전 표시
- Java 모니터링: 힙 그래프(사용/확보/최대), GC 시간 그래프, GC 횟수·멈춘 시간, 메타스페이스, 스레드, 클래스 수, "Java 프로세스" 보기
- Java 동작: 스레드 덤프(편집기 탭으로 열림), 힙 덤프(.hprof, 확인 후), GC 실행
- GC 경고: Java 프로세스가 GC에 시간을 너무 많이 쓰면 알림 (기본 25%), 도구 창을 닫아도 동작
- 실행 설정 연결: IDE에서 실행한 프로세스에 Run 설정 이름 표시, "IDE 실행" 보기, Run 창에서 보기, 종료는 IDE를 통해
- Spring Boot Actuator: 상태(health)와 구성 요소, 앱 이름·버전, 엔드포인트 링크
- 다이어리 탭: 실행·포트 충돌·종료·경고·Docker/Java 동작 기록 (저장됨), GC 후 힙 6시간 추이와 메모리 누수 의심 기록
- IDE 오류 창에서 "GitHub에 보고"로 내용이 채워진 이슈 열기
- 위 기능들을 설정 화면에서 켜고 끄기: Run 설정 이름 표시, IDE를 통한 종료, Actuator와 경로, 힙 추이, 다이어리와 기록 종류·보관 개수
- 컨테이너 탭: Docker 컨테이너 목록, 게시 포트, 시작·정지·재시작·삭제
- Docker 컨테이너 때문에 생긴 포트 충돌은 "컨테이너 정지 후 다시 실행"으로 해결
- 사용량 경고: CPU나 메모리가 기준(기본 90%, 1분)을 계속 넘으면 알림

## [1.2.0]
- Renamed to Process Diary (new plugin ID io.github.soonsoonyy.processdiary). Uninstall the old "Process Monitor" plugin after installing this one.
- English and Korean UI: follows the IDE language, or pick one in Settings → Tools → Process Diary → Language
- Fixed numbers such as ports and PIDs being shown with thousands separators in some messages
- New plugin icon, MIT license
- 이름이 Process Diary로 바뀌었습니다 (새 플러그인 ID). 설치 후 기존 "Process Monitor" 플러그인은 삭제하세요.
- 영어/한국어 화면 지원: IDE 언어를 따르거나 설정에서 직접 선택

## [1.1.1]
- 업데이트할 때 IDE가 플러그인을 바로 교체하려다 상태 표시줄 위젯이 잠깐 사라지던 문제: 처음부터 "재시작해서 적용"으로 안내

## [1.1.0]
- IDE 자동 업데이트: GitHub Release에서 새 버전을 받아 IntelliJ가 업데이트를 알려줍니다
- 처음 실행 시 "자동 업데이트 켜기" 알림, 설정 화면에서 켜기/끄기
- 그래프 글자를 IDE와 같은 방식으로 그리고, 라벨이 그래프 선에 가려지지 않게 개선 (윈도우에서 글자가 깨져 보이던 문제)

## [1.0.0]
- 상태 표시줄 위젯과 사용량 그래프 팝업 (CPU · 메모리 · 디스크 · 네트워크)
- Process Monitor 도구 창: 목록/트리, 검색(이름·PID·포트·작업 폴더·경로), 순서 고정, 다중 종료, 상세 패널
- Run/Debug 감시: 포트 충돌 알림과 "종료하고 다시 실행", 실행 종료 후 남은 프로세스 알림
- 설정 화면, Find Action/Tools 메뉴 동작, 프로젝트별 보기 상태 기억
