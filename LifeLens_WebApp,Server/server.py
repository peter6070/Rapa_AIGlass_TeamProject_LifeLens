"""LifeLens relay server. Run: python server.py"""

from __future__ import annotations

import asyncio
import base64
import ctypes
import json
import os
import re
import shutil
import socket
import sqlite3
import urllib.error
import urllib.parse
import urllib.request
from contextlib import asynccontextmanager
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from typing import Generator

import uvicorn
from fastapi import Depends, FastAPI, Header, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field


ROOT_DIR = Path(__file__).resolve().parent
WEB_DIR = ROOT_DIR / "web"
DATA_DIR = Path(os.getenv("LOCALAPPDATA", ROOT_DIR)) / "Kimchi_R1"
DB_PATH = Path(os.getenv("KIMCHI_DB_PATH", DATA_DIR / "kimchi_r1.db"))
PHOTO_DIR = DATA_DIR / "photos"
SETTINGS_PATH = DATA_DIR / "settings.json"


def load_env() -> None:
    env_file = ROOT_DIR / ".env"
    if not env_file.exists():
        return
    for line in env_file.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            name, value = line.split("=", 1)
            os.environ.setdefault(name.strip(), value.strip().strip("\"'"))


load_env()
API_KEY = os.getenv("API_KEY", "")


def saved_ollama_url() -> str:
    default = os.getenv("OLLAMA_URL", "http://127.0.0.1:11434").rstrip("/")
    try:
        saved = json.loads(SETTINGS_PATH.read_text(encoding="utf-8"))
        return normalize_ollama_url(saved.get("ollama_url", default))
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        return default


def normalize_ollama_url(value: object) -> str:
    url = str(value or "").strip().rstrip("/")
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.path not in {"", "/"}:
        raise ValueError("Ollama 주소는 http://호스트:포트 형식이어야 합니다.")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Ollama 주소에 인증 정보, 쿼리 또는 경로를 넣을 수 없습니다.")
    try:
        parsed.port
    except ValueError as error:
        raise ValueError("올바른 포트 번호를 입력하세요.") from error
    return url


OLLAMA_URL = saved_ollama_url()
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "gemma4:e4b")
OLLAMA_VISION_MODEL = os.getenv("OLLAMA_VISION_MODEL", "gemma3:4b")
# Do not depend on system tzdata. Korea has no daylight-saving adjustment.
APP_TIME_ZONE = timezone(timedelta(hours=9), name="KST")


def db() -> sqlite3.Connection:
    connection = sqlite3.connect(DB_PATH)
    connection.row_factory = sqlite3.Row
    return connection


def initialize_database() -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    PHOTO_DIR.mkdir(parents=True, exist_ok=True)
    with db() as connection:
        connection.executescript("""
            PRAGMA foreign_keys = ON;
            CREATE TABLE IF NOT EXISTS devices (
              device_id TEXT PRIMARY KEY,
              created_at TEXT NOT NULL,
              last_seen TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS conversation_records (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              device_id TEXT NOT NULL,
              client_record_id TEXT NOT NULL,
              text TEXT NOT NULL,
              spoken_at TEXT NOT NULL,
              received_at TEXT NOT NULL,
              UNIQUE(device_id, client_record_id),
              FOREIGN KEY(device_id) REFERENCES devices(device_id)
            );
            CREATE TABLE IF NOT EXISTS daily_summaries (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              device_id TEXT NOT NULL,
              summary_date TEXT NOT NULL,
              summary TEXT NOT NULL,
              keywords_json TEXT NOT NULL DEFAULT '[]',
              todos_json TEXT NOT NULL DEFAULT '[]',
              model TEXT NOT NULL,
              generated_at TEXT NOT NULL,
              UNIQUE(device_id, summary_date),
              FOREIGN KEY(device_id) REFERENCES devices(device_id)
            );
            CREATE INDEX IF NOT EXISTS idx_records_day ON conversation_records(device_id, spoken_at);
            CREATE TABLE IF NOT EXISTS photo_records (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              device_id TEXT NOT NULL,
              client_photo_id TEXT NOT NULL,
              taken_at TEXT NOT NULL,
              latitude REAL,
              longitude REAL,
              location_name TEXT,
              file_path TEXT NOT NULL,
              received_at TEXT NOT NULL,
              UNIQUE(device_id, client_photo_id),
              FOREIGN KEY(device_id) REFERENCES devices(device_id)
            );
            CREATE INDEX IF NOT EXISTS idx_photos_day ON photo_records(device_id, taken_at);
        """)
        columns = {row["name"] for row in connection.execute("PRAGMA table_info(photo_records)")}
        if "location_name" not in columns:
            connection.execute("ALTER TABLE photo_records ADD COLUMN location_name TEXT")
        if "vision_caption" not in columns:
            connection.execute("ALTER TABLE photo_records ADD COLUMN vision_caption TEXT")
        if "vision_model" not in columns:
            connection.execute("ALTER TABLE photo_records ADD COLUMN vision_model TEXT")
        if "analyzed_at" not in columns:
            connection.execute("ALTER TABLE photo_records ADD COLUMN analyzed_at TEXT")


class RecordIn(BaseModel):
    client_record_id: str = Field(min_length=1, max_length=80)
    text: str = Field(min_length=1, max_length=4000)
    spoken_at: datetime


class SyncIn(BaseModel):
    device_id: str = Field(min_length=8, max_length=80)
    date: date
    records: list[RecordIn] = Field(default_factory=list, max_length=2000)


class RecordOut(BaseModel):
    client_record_id: str
    text: str
    spoken_at: datetime


class PhotoIn(BaseModel):
    device_id: str = Field(min_length=8, max_length=80)
    client_photo_id: str = Field(min_length=1, max_length=80)
    taken_at: datetime
    latitude: float | None = None
    longitude: float | None = None
    location_name: str | None = Field(default=None, max_length=200)
    image_base64: str = Field(min_length=32, max_length=12_000_000)


class PhotoOut(BaseModel):
    client_photo_id: str
    taken_at: datetime
    latitude: float | None = None
    longitude: float | None = None
    location_name: str | None = None
    vision_caption: str | None = None
    url: str


class SummaryOut(BaseModel):
    summary: str
    keywords: list[str]
    todos: list[str]
    model: str
    generated_at: datetime


class LifeLogOut(BaseModel):
    date: date
    records: list[RecordOut]
    summary: SummaryOut | None
    photos: list[PhotoOut] = Field(default_factory=list)


class PageCopyIn(BaseModel):
    page: str = Field(pattern="^(home|skills|stream|debug|lifelog|iot)$")


class PageCopyOut(BaseModel):
    title: str
    body: str


class PresentationControlIn(BaseModel):
    action: str = Field(pattern="^(next|previous)$")


class OllamaSettingsIn(BaseModel):
    ollama_url: str = Field(min_length=8, max_length=500)


class OllamaSettingsOut(BaseModel):
    ollama_url: str
    ollama_model: str
    ollama_vision_model: str


def require_api_key(x_api_key: str | None = Header(default=None)) -> None:
    # LifeLens is used on a trusted local network. Do not block phone-to-PC relay requests
    # with a separately managed shared key.
    return None


def press_presentation_key(action: str) -> None:
    """Send an arrow key to the foreground window on the Windows relay PC."""
    if os.name != "nt":
        raise RuntimeError("presentation control requires a Windows relay PC")
    virtual_key = 0x27 if action == "next" else 0x25  # VK_RIGHT / VK_LEFT
    key_up = 0x0002
    ctypes.windll.user32.keybd_event(virtual_key, 0, 0, 0)
    ctypes.windll.user32.keybd_event(virtual_key, 0, key_up, 0)


def save_ollama_url(value: str) -> str:
    global OLLAMA_URL
    normalized = normalize_ollama_url(value)
    SETTINGS_PATH.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = SETTINGS_PATH.with_suffix(".tmp")
    temporary_path.write_text(json.dumps({"ollama_url": normalized}, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary_path.replace(SETTINGS_PATH)
    OLLAMA_URL = normalized
    return normalized


def server_ip() -> str:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        try:
            sock.connect(("8.8.8.8", 80))
            return sock.getsockname()[0]
        except OSError:
            return "127.0.0.1"


def iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat()


def day_bounds(day: date) -> tuple[str, str]:
    # The UI groups LifeLens records by Korean calendar day. Store and query timestamps in UTC,
    # but derive the bounds from KST so 00:00~08:59 records do not fall into the previous day.
    start = datetime.combine(day, time.min, tzinfo=APP_TIME_ZONE)
    end = datetime.combine(day.fromordinal(day.toordinal() + 1), time.min, tzinfo=APP_TIME_ZONE)
    return iso(start), iso(end)


def records_for_day(connection: sqlite3.Connection, device_id: str, day: date) -> list[sqlite3.Row]:
    start, end = day_bounds(day)
    return connection.execute(
        "SELECT client_record_id, text, spoken_at FROM conversation_records WHERE device_id = ? AND spoken_at >= ? AND spoken_at < ? ORDER BY spoken_at",
        (device_id, start, end),
    ).fetchall()


def photos_for_day(connection: sqlite3.Connection, device_id: str, day: date) -> list[sqlite3.Row]:
    start, end = day_bounds(day)
    return connection.execute(
        """SELECT client_photo_id, taken_at, latitude, longitude, location_name, file_path,
                  vision_caption, vision_model, analyzed_at
           FROM photo_records WHERE device_id = ? AND taken_at >= ? AND taken_at < ? ORDER BY taken_at""",
        (device_id, start, end),
    ).fetchall()


def photo_out(device_id: str, row: sqlite3.Row) -> PhotoOut:
    return PhotoOut(client_photo_id=row["client_photo_id"], taken_at=datetime.fromisoformat(row["taken_at"]), latitude=row["latitude"], longitude=row["longitude"], location_name=row["location_name"], vision_caption=row["vision_caption"], url=f"/v1/lifelogs/photos/{device_id}/{row['client_photo_id']}")


def summary_for_day(connection: sqlite3.Connection, device_id: str, day: date) -> SummaryOut | None:
    row = connection.execute(
        "SELECT summary, keywords_json, todos_json, model, generated_at FROM daily_summaries WHERE device_id = ? AND summary_date = ?",
        (device_id, day.isoformat()),
    ).fetchone()
    if row is None:
        return None
    return SummaryOut(summary=row["summary"], keywords=json.loads(row["keywords_json"]), todos=json.loads(row["todos_json"]), model=row["model"], generated_at=datetime.fromisoformat(row["generated_at"]))


def ollama_request(payload: bytes, timeout: int = 180) -> dict:
    request = urllib.request.Request(f"{OLLAMA_URL}/api/generate", data=payload, headers={"Content-Type": "application/json"}, method="POST")
    # A cold start has to load the multi-gigabyte model before generating. Leave enough room
    # for that first request so the web app does not surface a misleading 503 response.
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def analyze_photo_image(photo: sqlite3.Row) -> str:
    path = Path(photo["file_path"])
    if not path.is_file():
        raise ValueError(f"photo file not found: {photo['client_photo_id']}")
    image_base64 = base64.b64encode(path.read_bytes()).decode("ascii")
    result = ollama_request(json.dumps({
        "model": OLLAMA_VISION_MODEL,
        "prompt": """이 사진은 사용자의 하루 기록이다. 사진에서 직접 확인되는 장소의 유형, 사람의 활동, 주요 사물과 상황을 한국어 2~3문장으로 객관적으로 설명하라. 얼굴로 신원을 추측하거나 보이지 않는 사건·감정·장소명을 만들지 마라. 일기 작성에 유용한 구체적인 사실만 일반 텍스트로 반환하라.""",
        "images": [image_base64],
        "stream": False,
        "options": {"temperature": 0.1},
    }, ensure_ascii=False).encode("utf-8"), timeout=300)
    caption = str(result["response"]).strip()
    if not caption:
        raise ValueError("vision model returned an empty caption")
    return caption[:2000]


async def ensure_photo_captions(device_id: str, day: date, photos: list[sqlite3.Row]) -> list[sqlite3.Row]:
    for photo in photos:
        if photo["vision_caption"] and photo["vision_model"] == OLLAMA_VISION_MODEL:
            continue
        try:
            caption = await asyncio.to_thread(analyze_photo_image, photo)
        except (urllib.error.URLError, urllib.error.HTTPError, KeyError, ValueError, TypeError, OSError) as error:
            raise HTTPException(status_code=503, detail=f"Ollama photo analysis unavailable ({OLLAMA_VISION_MODEL}): {error}") from error
        with db() as connection:
            connection.execute(
                """UPDATE photo_records SET vision_caption = ?, vision_model = ?, analyzed_at = ?
                   WHERE device_id = ? AND client_photo_id = ?""",
                (caption, OLLAMA_VISION_MODEL, iso(datetime.now(timezone.utc)), device_id, photo["client_photo_id"]),
            )
    with db() as connection:
        return photos_for_day(connection, device_id, day)


def clean_summary_markdown(value: object) -> str:
    """Keywords and todos are rendered separately by the app, never inside Markdown."""
    text = str(value or "").strip()
    text = re.sub(r"^\s*(?:#{1,6}\s*)?오늘의\s*리포트\s*[:：]?[^\n]*\n+", "", text, flags=re.IGNORECASE)
    return re.sub(r"\n\s*(?:#{1,6}\s*)?(?:keywords|핵심\s*키워드|키워드)\s*[:：]?\s*\[[\s\S]*$", "", text, flags=re.IGNORECASE).strip()


async def generate_summary(rows: list[sqlite3.Row], photos: list[sqlite3.Row]) -> tuple[str, list[str], list[str]]:
    events: list[tuple[datetime, str]] = []
    for row in rows:
        spoken_at = datetime.fromisoformat(row["spoken_at"]).astimezone(APP_TIME_ZONE)
        events.append((spoken_at, f"[{spoken_at.strftime('%H:%M')}][대화] {row['text']}"))
    for photo in photos:
        taken_at = datetime.fromisoformat(photo["taken_at"]).astimezone(APP_TIME_ZONE)
        if photo["location_name"]:
            location = str(photo["location_name"])
        elif photo["latitude"] is not None and photo["longitude"] is not None:
            location = f"좌표 {photo['latitude']:.5f}, {photo['longitude']:.5f}"
        else:
            location = "위치 정보 없음"
        events.append((taken_at, f"[{taken_at.strftime('%H:%M')}][사진] 위치: {location} / 사진에서 확인된 내용: {photo['vision_caption']}"))
    timeline = "\n".join(text for _, text in sorted(events, key=lambda event: event[0]))
    prompt = f'''다음은 한 사람의 하루를 시간순으로 정리한 LifeLens 기록이다. 대화, 글래스로 촬영한 사진의 시각적 분석, 촬영 위치가 함께 들어 있다. 반드시 한국어 JSON만 반환하라.
형식: {{"summary":"Markdown 리포트. ## 하루의 흐름, ## 주요 활동과 장소, ## 정리 제목을 사용하고 각 내용은 합계 8~10문장 분량으로 구체적으로 작성. 필요 시 - 목록 사용. 사진과 위치를 근거로 어디에서 무엇을 했는지 시간 흐름에 맞춰 반영하고, 대화의 주요 주제·사람·계획·감정 또는 의도·결정 사항도 포함할 것. 사진 분석과 대화가 서로 보완될 때만 자연스럽게 연결하고, 기록에 없는 사실이나 정확한 장소명·사람의 신원을 만들지 말 것. summary 안에는 오늘의 리포트·keywords·핵심 키워드·할 일 섹션을 절대 넣지 말 것","keywords":["최대 7개"],"todos":["기록에서 확인된 할 일"]}}
통합 타임라인:
{timeline}'''
    try:
        result = await asyncio.to_thread(ollama_request, json.dumps({
            "model": OLLAMA_MODEL, "prompt": prompt,
            "format": {
                "type": "object",
                "properties": {
                    "summary": {"type": "string"},
                    "keywords": {"type": "array", "items": {"type": "string"}},
                    "todos": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["summary", "keywords", "todos"],
            },
            "stream": False,
            "options": {"temperature": 0.2},
        }, ensure_ascii=False).encode("utf-8"))
        raw_response = result["response"]
        try:
            parsed = json.loads(raw_response)
        except json.JSONDecodeError:
            # Some local models return literal Markdown newlines inside the JSON string. Keep the
            # report rather than failing the whole life-log request when that happens.
            summary_match = re.search(r'"summary"\s*:\s*"(.*?)(?=,\s*"keywords"\s*:)', raw_response, re.DOTALL)
            if summary_match is None:
                raise
            parsed = {
                "summary": summary_match.group(1).replace("\\n", "\n").replace('\\"', '"'),
                "keywords": [],
                "todos": [],
            }
        return clean_summary_markdown(parsed["summary"]), [str(value) for value in parsed.get("keywords", [])][:5], [str(value) for value in parsed.get("todos", [])][:10]
    except (urllib.error.URLError, urllib.error.HTTPError, KeyError, ValueError, TypeError) as error:
        raise HTTPException(status_code=503, detail=f"Ollama summary unavailable: {error}") from error


async def generate_page_copy(page: str) -> PageCopyOut:
    contexts = {
        "home": "LifeLens 홈. Ray-Ban Meta 글래스와 대화를 통해 일상을 더 선명하게 돕는 시작 화면",
        "skills": "LifeLens 스킬. 글래스의 제스처, 라이프로그, IoT 기능을 발견하는 화면",
        "stream": "LifeLens 스트림. 글래스 카메라와 마이크가 지금 무엇을 보고 듣는지 실시간으로 확인하는 화면",
        "debug": "LifeLens 디버그. 백엔드와 Matter 브릿지의 연결 상태를 차분하게 확인하는 화면",
        "lifelog": "LifeLens 라이프로그. 하루 대화 기록을 되돌아보고 의미를 정리하는 화면",
        "iot": "LifeLens IoT 제어. 조명과 플러그를 편안하고 즉시 제어하는 화면",
    }
    prompt = f'''당신은 LifeLens 모바일 앱의 한국어 카피라이터다.
화면: {contexts[page]}
매번 새롭지만 과장 없이 따뜻하고 간결한 Hero 문구를 작성하라.
title과 body는 반드시 위 화면의 기능을 가리켜야 한다. 다른 화면에 그대로 옮겨도 말이 되는 일반적인 문장은 금지한다.
title은 줄바꿈 없이 하나의 자연스러운 한국어 구절로 13자 이내로 작성한다. 짧고 기억하기 쉬운 문장으로 쓰며, 단어·어절을 나누지 않는다.
body는 줄바꿈 없이 화면에서 최대 두 줄로 보일 수 있게 한 문장 26자 이내로 작성한다. 이모지, 따옴표, Markdown은 쓰지 않는다.
반드시 JSON만 반환한다.'''
    try:
        result = await asyncio.to_thread(ollama_request, json.dumps({
            "model": OLLAMA_MODEL,
            "prompt": prompt,
            "format": {
                "type": "object",
                "properties": {"title": {"type": "string"}, "body": {"type": "string"}},
                "required": ["title", "body"],
            },
            "stream": False,
            "options": {"temperature": 0.9},
        }, ensure_ascii=False).encode("utf-8"))
        parsed = json.loads(result["response"])
        return PageCopyOut(title=str(parsed["title"]).strip()[:80], body=str(parsed["body"]).strip()[:160])
    except (urllib.error.URLError, urllib.error.HTTPError, KeyError, ValueError, TypeError) as error:
        raise HTTPException(status_code=503, detail=f"Ollama page copy unavailable: {error}") from error


@asynccontextmanager
async def lifespan(_: FastAPI):
    initialize_database()
    yield


app = FastAPI(title="LifeLens Relay", lifespan=lifespan)
# The Debug screen can point the web app at a relay on another PC, which makes
# those calls cross-origin. Same trusted-LAN posture as require_api_key.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.mount("/app/static", StaticFiles(directory=WEB_DIR), name="web-static")


@app.get("/app/")
def web_app() -> FileResponse:
    # The Android client keeps one WebView for the app lifetime. Always fetch the current HTML
    # shell after a server restart; videos and images remain separately cacheable.
    return FileResponse(
        WEB_DIR / "html" / "index.html",
        headers={"Cache-Control": "no-store, max-age=0"},
    )


@app.get("/health")
@app.get("/")
def health() -> dict[str, str]:
    return {"status": "ok", "model": OLLAMA_MODEL, "server_ip": server_ip()}


@app.post("/v1/page-copy", response_model=PageCopyOut, dependencies=[Depends(require_api_key)])
async def page_copy(payload: PageCopyIn) -> PageCopyOut:
    return await generate_page_copy(payload.page)


@app.post("/v1/presentation/control", dependencies=[Depends(require_api_key)])
async def presentation_control(payload: PresentationControlIn) -> dict[str, str]:
    try:
        await asyncio.to_thread(press_presentation_key, payload.action)
    except RuntimeError as error:
        raise HTTPException(status_code=501, detail=str(error)) from error
    return {"status": "ok", "action": payload.action}


@app.get("/v1/settings/ollama", response_model=OllamaSettingsOut, dependencies=[Depends(require_api_key)])
def get_ollama_settings() -> OllamaSettingsOut:
    return OllamaSettingsOut(ollama_url=OLLAMA_URL, ollama_model=OLLAMA_MODEL, ollama_vision_model=OLLAMA_VISION_MODEL)


@app.put("/v1/settings/ollama", response_model=OllamaSettingsOut, dependencies=[Depends(require_api_key)])
def update_ollama_settings(payload: OllamaSettingsIn) -> OllamaSettingsOut:
    try:
        ollama_url = save_ollama_url(payload.ollama_url)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    return OllamaSettingsOut(ollama_url=ollama_url, ollama_model=OLLAMA_MODEL, ollama_vision_model=OLLAMA_VISION_MODEL)


@app.get("/v1/lifelogs/{device_id}/{day}", response_model=LifeLogOut, dependencies=[Depends(require_api_key)])
def get_lifelog(device_id: str, day: date) -> LifeLogOut:
    with db() as connection:
        rows = records_for_day(connection, device_id, day)
        summary = summary_for_day(connection, device_id, day)
        photos = photos_for_day(connection, device_id, day)
    records = [RecordOut(client_record_id=row["client_record_id"], text=row["text"], spoken_at=datetime.fromisoformat(row["spoken_at"])) for row in rows]
    return LifeLogOut(date=day, records=records, summary=summary, photos=[photo_out(device_id, row) for row in photos])


@app.post("/v1/lifelogs/photos/sync", dependencies=[Depends(require_api_key)])
def sync_photo(payload: PhotoIn) -> dict[str, str]:
    try:
        image = base64.b64decode(payload.image_base64, validate=True)
    except ValueError as error:
        raise HTTPException(status_code=422, detail="invalid photo payload") from error
    if len(image) > 8_000_000:
        raise HTTPException(status_code=413, detail="photo is too large")
    safe_device = re.sub(r"[^A-Za-z0-9_-]", "_", payload.device_id)
    safe_photo = re.sub(r"[^A-Za-z0-9_-]", "_", payload.client_photo_id)
    folder = PHOTO_DIR / safe_device
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f"{safe_photo}.jpg"
    # Do not expose a partial JPEG if a request is interrupted while another client reads it.
    temporary_path = path.with_suffix(".upload")
    temporary_path.write_bytes(image)
    temporary_path.replace(path)
    now = iso(datetime.now(timezone.utc))
    with db() as connection:
        connection.execute("INSERT INTO devices(device_id, created_at, last_seen) VALUES(?, ?, ?) ON CONFLICT(device_id) DO UPDATE SET last_seen=excluded.last_seen", (payload.device_id, now, now))
        connection.execute("""INSERT INTO photo_records(device_id, client_photo_id, taken_at, latitude, longitude, location_name, file_path, received_at)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(device_id, client_photo_id) DO UPDATE SET
              taken_at=excluded.taken_at, latitude=excluded.latitude, longitude=excluded.longitude,
              location_name=excluded.location_name, file_path=excluded.file_path, received_at=excluded.received_at""", (payload.device_id, payload.client_photo_id, iso(payload.taken_at), payload.latitude, payload.longitude, payload.location_name, str(path), now))
    return {"url": f"/v1/lifelogs/photos/{payload.device_id}/{payload.client_photo_id}"}


@app.get("/v1/lifelogs/photos/{device_id}/{client_photo_id}", dependencies=[Depends(require_api_key)])
def get_photo(device_id: str, client_photo_id: str) -> FileResponse:
    with db() as connection:
        row = connection.execute("SELECT file_path FROM photo_records WHERE device_id = ? AND client_photo_id = ?", (device_id, client_photo_id)).fetchone()
    if row is None or not Path(row["file_path"]).is_file():
        raise HTTPException(status_code=404, detail="photo not found")
    return FileResponse(row["file_path"], media_type="image/jpeg")


@app.post("/v1/lifelogs/sync", dependencies=[Depends(require_api_key)])
def sync_lifelog(payload: SyncIn) -> dict[str, int]:
    """Store phone records immediately; all LifeLens phones use one shared stream."""
    now = iso(datetime.now(timezone.utc))
    with db() as connection:
        connection.execute(
            "INSERT INTO devices(device_id, created_at, last_seen) VALUES(?, ?, ?) ON CONFLICT(device_id) DO UPDATE SET last_seen = excluded.last_seen",
            (payload.device_id, now, now),
        )
        connection.executemany(
            "INSERT OR IGNORE INTO conversation_records(device_id, client_record_id, text, spoken_at, received_at) VALUES(?, ?, ?, ?, ?)",
            [(payload.device_id, record.client_record_id, record.text, iso(record.spoken_at), now) for record in payload.records],
        )
    return {"stored": len(payload.records)}


@app.post("/v1/lifelogs/summarize", response_model=LifeLogOut, dependencies=[Depends(require_api_key)])
async def sync_and_summarize(payload: SyncIn) -> LifeLogOut:
    now = iso(datetime.now(timezone.utc))
    with db() as connection:
        connection.execute(
            "INSERT INTO devices(device_id, created_at, last_seen) VALUES(?, ?, ?) ON CONFLICT(device_id) DO UPDATE SET last_seen = excluded.last_seen",
            (payload.device_id, now, now),
        )
        connection.executemany(
            "INSERT OR IGNORE INTO conversation_records(device_id, client_record_id, text, spoken_at, received_at) VALUES(?, ?, ?, ?, ?)",
            [(payload.device_id, record.client_record_id, record.text, iso(record.spoken_at), now) for record in payload.records],
        )
        rows = records_for_day(connection, payload.device_id, payload.date)
        photos = photos_for_day(connection, payload.device_id, payload.date)
    if not rows and not photos:
        raise HTTPException(status_code=422, detail="no conversation records or photos for this date")
    photos = await ensure_photo_captions(payload.device_id, payload.date, photos)
    summary_text, keywords, todos = await generate_summary(rows, photos)
    summary_model = f"{OLLAMA_MODEL} + {OLLAMA_VISION_MODEL}" if photos else OLLAMA_MODEL
    generated_at = iso(datetime.now(timezone.utc))
    with db() as connection:
        connection.execute(
            """INSERT INTO daily_summaries(device_id, summary_date, summary, keywords_json, todos_json, model, generated_at)
               VALUES(?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(device_id, summary_date) DO UPDATE SET summary=excluded.summary, keywords_json=excluded.keywords_json,
               todos_json=excluded.todos_json, model=excluded.model, generated_at=excluded.generated_at""",
            (payload.device_id, payload.date.isoformat(), summary_text, json.dumps(keywords, ensure_ascii=False), json.dumps(todos, ensure_ascii=False), summary_model, generated_at),
        )
        photos = photos_for_day(connection, payload.device_id, payload.date)
    return LifeLogOut(
        date=payload.date,
        records=[RecordOut(client_record_id=row["client_record_id"], text=row["text"], spoken_at=datetime.fromisoformat(row["spoken_at"])) for row in rows],
        summary=SummaryOut(summary=summary_text, keywords=keywords, todos=todos, model=summary_model, generated_at=datetime.fromisoformat(generated_at)),
        photos=[photo_out(payload.device_id, row) for row in photos],
    )


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8080")))
