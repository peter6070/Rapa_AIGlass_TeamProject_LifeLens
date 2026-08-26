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

가장 기본적인 실행:

```powershell
npm run server
```

하지만 이 저장소를 쓰는 개발 환경(Windows, BLE 프록시 사용)에서는 기본값만으로는 부족해서 아래처럼 옵션을 붙여야 한다.

```powershell
npm run server -- --storage-path "$PWD\data-fresh" --primary-interface "Wi-Fi" --ble-proxy --log-level debug
```

- `--storage-path` : 설정/인증서 등을 저장할 디렉터리. 지정하지 않으면 기본 경로를 쓰는데 환경에 따라 꼬일 수 있어 명시하는 것을 권장.
- `--primary-interface "Wi-Fi"` : Windows에서 네트워크 인터페이스를 자동 감지하면 VPN/가상 어댑터를 잘못 잡는 경우가 많아, mDNS/링크로컬 주소에 쓸 인터페이스를 직접 지정한다.
- `--ble-proxy` : Windows에서는 Node의 네이티브 BLE(`@matter/nodejs-ble`, noble 기반)가 불안정하거나 동작하지 않는 경우가 많다. 이 옵션은 서버의 로컬 BLE 어댑터 대신 `/ble` WebSocket 엔드포인트를 열어, 실제 BLE 어댑터를 가진 별도 프로세스가 붙게 만든다.
- `--log-level debug` : 커미셔닝 실패 등을 디버깅할 때 로그 레벨을 올린다.

**편의 스크립트**: 매번 위 명령을 치기 번거로우면 `matterjs-server-main/package.json`에 추가해 둔 다음 스크립트를 쓴다 (Wi-Fi 인터페이스명이 다르면 스크립트 내용을 직접 수정).

```powershell
npm run server:win-ble
```

### BLE 프록시 (별도 터미널)

`--ble-proxy` 모드로 서버를 띄우면, 실제 BLE 하드웨어에 접근하는 별도 프로세스를 새 터미널에서 함께 실행해야 한다.

```powershell
matter-ble-proxy --server ws://localhost:5580/ble
```

(`matter-ble-proxy`는 `matterjs-server-main/python_ble_proxy`의 Python CLI. 설치가 안 되어 있다면 `npm run python-ble-proxy:install`로 venv를 만든 뒤 `.venv/bin/matter-ble-proxy`를 사용하거나, PATH에 등록해 둔다.)

### 왜 `npm run server` 하나로는 안 되는가

`--primary-interface`(어떤 네트워크 인터페이스명을 쓸지)와 `--ble-proxy`(로컬 BLE를 쓸지, 원격 BLE 프록시를 쓸지)는 컴퓨터/환경마다 값이 다르기 때문에 기본값으로 하드코딩할 수 없다. 또한 BLE 프록시는 Matter 서버(Node.js)와 BLE 어댑터를 다루는 프로세스(Python, 다른 언어/런타임)가 별도 프로세스로 분리되어 있어서 npm 스크립트 하나로 합칠 수 없다 — 두 프로세스를 각자의 터미널에서 띄워야 한다. 대신 위의 `server:win-ble` 같은 스크립트로 옵션을 한 번만 고정해두면, 매번 긴 명령을 치는 수고는 줄일 수 있다.

Docker로 실행할 경우에는 이런 옵션들이 필요 없을 수 있다. 자세한 내용은 [matterjs-server-main/README.md](matterjs-server-main/README.md)와 [docs/docker.md](matterjs-server-main/docs/docker.md) 참고.
