# LifeLens

Ray-Ban Meta 스마트 글래스를 활용한 AI 라이프로깅 프로젝트. 안드로이드 네이티브 앱, 백엔드 웹/API 서버, Matter.js 기반 스마트홈 연동 서버로 구성된다.

## 폴더 구조

| 폴더 | 설명 |
|---|---|
| [LifeLens_Android/](LifeLens_Android/) | Ray-Ban Meta용 안드로이드 네이티브 앱 (Meta Wearables DAT 0.9.0 기반) |
| [LifeLens_WebApp,Server/](LifeLens_WebApp,Server/) | FastAPI 기반 릴레이 서버 + 웹 프론트엔드 |
| [matterjs-server-main/](matterjs-server-main/) | Matter.js 기반 Matter 컨트롤러 서버 (Home Assistant 호환, BLE 커미셔닝용) |

각 폴더의 세부 설정은 하위 README를 참고한다.

- [LifeLens_Android/README.md](LifeLens_Android/README.md)
- [matterjs-server-main/README.md](matterjs-server-main/README.md)

## LifeLens_WebApp,Server

웹앱 서버 실행

```powershell
cd "LifeLens_WebApp,Server"
python server.py
```

FastAPI + `uvicorn` 기반 서버로, 안드로이드 앱과 웹 프론트엔드(`web/`)를 서빙하고 SQLite에 데이터를 저장한다.

## matterjs-server-main (Matter 서버)

Matter 기기(조명, 센서 등)를 BLE로 커미셔닝하고 제어하기 위한 Node.js 서버. 원본은 [Open Home Foundation Matter(.js) Server](https://github.com/matter-js/matterjs-server)를 사용한다.

### 처음 설치할 때 (필수: node-module 재빌드)

```powershell
cd matterjs-server-main
npm i
```

`npm i`를 실행하면 `prepare` 스크립트(`npm run build-clean`)가 자동으로 실행되어 네이티브 모듈 포함 전체 `node_modules`를 다시 빌드한다. 코드를 pull 하거나 Node 버전을 바꾼 뒤 서버가 이상하게 동작하면(특히 BLE/네이티브 바인딩 관련 오류) 아래처럼 재빌드부터 다시 실행한다.

```powershell
npm i
npm run build
```

### 서버 실행

```powershell
npm run server
```