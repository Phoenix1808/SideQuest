"""
Side Quest backend - turns the real places around you into short outdoor quests,
and checks the photo proof with an open-weight vision model.

Everything it relies on is open:
  * Open-weight LLM (Qwen 3.8 27B, text + vision) via any OpenAI-compatible endpoint
  * OpenStreetMap data via the Overpass API (nearby parks, water, viewpoints...)
  * Open-Meteo (open-source weather + sunrise/sunset, no API key)

Swapping the model or provider is a config change:
    LLM_BASE_URL=https://api.groq.com/openai/v1   LLM_MODEL=qwen/qwen3.8-27b
    LLM_BASE_URL=http://localhost:11434/v1        LLM_MODEL=gemma3:4b
"""

import asyncio
import base64
import json
import math
import os
import random
import re
import secrets
import time
import uuid

import httpx
from dotenv import load_dotenv
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel

load_dotenv()

LLM_BASE_URL = os.getenv("LLM_BASE_URL", "https://api.groq.com/openai/v1").rstrip("/")
LLM_API_KEY = os.getenv("LLM_API_KEY", "")
LLM_MODEL = os.getenv("LLM_MODEL", "qwen/qwen3.8-27b")

# Shared secret the Android app sends as X-App-Key. Keeps strangers who find the
# public URL from spending the free model quota. Unset = open (local development).
APP_KEY = os.getenv("APP_KEY", "")

# Public Overpass mirrors are free and community-run, so any of them can be slow
# or overloaded. We ask all of them at once and use the first good answer.
OVERPASS_URLS = [
    "https://overpass-api.de/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
PLACES_BUDGET_S = 15          # how long a quest request waits for map data
PLACES_TTL_S = 24 * 60 * 60   # parks don't move - reuse map data for a day
MAX_RETRIES = 3               # rate-limit retries per model call
MAX_RETRY_WAIT_S = 45         # longest we'll quietly wait out a rate limit
USER_AGENT = "SideQuest/1.0 (Hacktoberfest 2026 open-source AI project)"
MAX_PHOTO_BYTES = 8 * 1024 * 1024

app = FastAPI(title="Side Quest API", version="1.0.0")


def require_app_key(x_app_key: str = Header(default="")):
    if APP_KEY and not secrets.compare_digest(x_app_key, APP_KEY):
        raise HTTPException(401, "This server only answers the Side Quest app.")


# ---------- models ----------

class QuestRequest(BaseModel):
    lat: float
    lon: float
    count: int = 3
    radius_m: int = 1500


class Place(BaseModel):
    name: str
    kind: str
    lat: float
    lon: float
    distance_m: int
    direction: str


class Weather(BaseModel):
    temp_c: float
    summary: str
    emoji: str
    is_day: bool
    local_time: str
    sunrise: str
    sunset: str


class Quest(BaseModel):
    id: str
    emoji: str
    title: str
    description: str
    proof: str
    minutes: int
    place: Place | None = None


class QuestResponse(BaseModel):
    weather: Weather | None
    quests: list[Quest]


class VerifyResponse(BaseModel):
    success: bool
    caption: str
    message: str


# ---------- LLM ----------

async def ask_llm(messages: list[dict], temperature: float = 0.6) -> dict:
    """Send a chat request to the open-weight model and parse its JSON reply."""
    if not LLM_API_KEY and "localhost" not in LLM_BASE_URL:
        raise HTTPException(500, "LLM_API_KEY is not set on the server")

    payload = {
        "model": LLM_MODEL,
        "temperature": temperature,
        "response_format": {"type": "json_object"},
        "messages": messages,
    }
    headers = {"Authorization": f"Bearer {LLM_API_KEY}"} if LLM_API_KEY else {}

    try:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.post(f"{LLM_BASE_URL}/chat/completions", json=payload, headers=headers)
            # Free tiers allow a few thousand tokens a minute (a photo alone is ~1.8k).
            # If the provider says "wait a few seconds", wait and retry - with a little
            # jitter so several waiting requests don't all retry at the same instant.
            waited = 0.0
            for _ in range(MAX_RETRIES):
                if resp.status_code != 429:
                    break
                wait = retry_after_seconds(resp)
                if wait is None or waited + wait > MAX_RETRY_WAIT_S:
                    break
                pause = wait + random.uniform(0.5, 3.0)
                await asyncio.sleep(pause)
                waited += pause
                resp = await client.post(f"{LLM_BASE_URL}/chat/completions", json=payload, headers=headers)
    except httpx.HTTPError as e:
        raise HTTPException(502, f"Could not reach the model provider: {e}") from e

    if resp.status_code == 429:
        raise HTTPException(
            429, "The AI referee is catching its breath (free tier limit). Give it a minute, then tap Retry."
        )
    if resp.status_code != 200:
        raise HTTPException(502, f"Model provider error {resp.status_code}: {resp.text[:300]}")

    return parse_json(resp.json()["choices"][0]["message"]["content"])


def retry_after_seconds(resp: httpx.Response) -> float | None:
    header = resp.headers.get("retry-after")
    if header:
        try:
            return float(header)
        except ValueError:
            pass
    # Groq also says it in the message: "...Please try again in 7.66s."
    match = re.search(r"try again in ([\d.]+)s", resp.text)
    return float(match.group(1)) if match else None


def parse_json(text: str) -> dict:
    """Models occasionally wrap JSON in prose or code fences - dig it out."""
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, re.DOTALL)
        if match:
            try:
                return json.loads(match.group(0))
            except json.JSONDecodeError:
                pass
    raise HTTPException(502, "Model returned an unreadable response, please retry")


# ---------- geography ----------

def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> int:
    r = 6371000
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return round(2 * r * math.asin(math.sqrt(a)))


def direction(lat1: float, lon1: float, lat2: float, lon2: float) -> str:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    x = math.sin(dl) * math.cos(p2)
    y = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    bearing = (math.degrees(math.atan2(x, y)) + 360) % 360
    return ["north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"][
        round(bearing / 45) % 8
    ]


KIND_KEYS = ["leisure", "natural", "tourism", "landuse", "amenity", "water"]


def describe_kind(tags: dict) -> str:
    for key in KIND_KEYS:
        if key in tags:
            return tags[key].replace("_", " ")
    return "outdoor spot"


# (rounded lat, rounded lon, radius) -> (fetched_at, raw OSM elements)
_places_cache: dict[tuple, tuple[float, list[dict]]] = {}
_places_pending: dict[tuple, asyncio.Task] = {}


async def _query_mirror(client: httpx.AsyncClient, url: str, query: str) -> list[dict]:
    resp = await client.post(url, data={"data": query})
    if resp.status_code != 200:
        raise RuntimeError(f"{url} answered {resp.status_code}")
    return resp.json().get("elements", [])


async def _fetch_osm(key: tuple, lat: float, lon: float, radius: int) -> list[dict]:
    """Race every Overpass mirror; cache and return the first good answer."""
    # A bounding box is much cheaper for Overpass than a circle; we trim to the circle later
    dlat = radius / 111_320
    dlon = radius / (111_320 * math.cos(math.radians(lat)))
    box = f"({lat - dlat:.5f},{lon - dlon:.5f},{lat + dlat:.5f},{lon + dlon:.5f})"
    query = f"""
[out:json][timeout:25];
(
  nwr["leisure"~"^(park|garden|nature_reserve|playground|dog_park)$"]{box};
  nwr["natural"~"^(water|wood|beach|peak|grassland)$"]["name"]{box};
  nwr["tourism"~"^(viewpoint|picnic_site)$"]{box};
  nwr["landuse"~"^(forest|meadow|recreation_ground|village_green)$"]["name"]{box};
  nwr["amenity"="fountain"]{box};
);
out center tags 150;
"""
    try:
        async with httpx.AsyncClient(timeout=40, headers={"User-Agent": USER_AGENT}) as client:
            tasks = [asyncio.create_task(_query_mirror(client, url, query)) for url in OVERPASS_URLS]
            try:
                for next_done in asyncio.as_completed(tasks):
                    try:
                        elements = await next_done
                    except (httpx.HTTPError, RuntimeError, json.JSONDecodeError):
                        continue
                    _places_cache[key] = (time.time(), elements)
                    return elements
                return []
            finally:
                for task in tasks:
                    task.cancel()
    finally:
        _places_pending.pop(key, None)


async def nearby_places(lat: float, lon: float, radius: int) -> list[Place]:
    """Outdoor places around the user from OpenStreetMap. Empty list if the map servers are too slow."""
    key = (round(lat, 2), round(lon, 2), radius)   # ~1 km cells

    cached = _places_cache.get(key)
    if cached and time.time() - cached[0] < PLACES_TTL_S:
        elements = cached[1]
    else:
        task = _places_pending.get(key)
        if task is None:
            task = asyncio.create_task(_fetch_osm(key, lat, lon, radius))
            _places_pending[key] = task
        try:
            # shield: if we give up waiting, the lookup still finishes and fills the cache
            elements = await asyncio.wait_for(asyncio.shield(task), PLACES_BUDGET_S)
        except asyncio.TimeoutError:
            return []

    named: dict[str, Place] = {}
    unnamed: list[Place] = []
    for el in elements:
        tags = el.get("tags", {})
        plat = el.get("lat") or el.get("center", {}).get("lat")
        plon = el.get("lon") or el.get("center", {}).get("lon")
        if plat is None or plon is None:
            continue
        meters = distance_m(lat, lon, plat, plon)
        if meters > radius:
            continue
        kind = describe_kind(tags)
        name = tags.get("name:en") or tags.get("name")
        place = Place(
            name=name or f"unnamed {kind}",
            kind=kind,
            lat=plat,
            lon=plon,
            distance_m=meters,
            direction=direction(lat, lon, plat, plon),
        )
        if name:
            # Big parks show up several times (as a node and a way) - keep the closest
            if name not in named or place.distance_m < named[name].distance_m:
                named[name] = place
        else:
            unnamed.append(place)

    places = sorted(named.values(), key=lambda p: p.distance_m)[:12]
    places += sorted(unnamed, key=lambda p: p.distance_m)[:3]
    return places


# ---------- weather ----------

def weather_from_code(code: int) -> tuple[str, str]:
    if code == 0:
        return "clear sky", "☀️"
    if code in (1, 2):
        return "partly cloudy", "⛅"
    if code == 3:
        return "overcast", "☁️"
    if code in (45, 48):
        return "foggy", "🌫️"
    if 51 <= code <= 57:
        return "drizzle", "🌦️"
    if 61 <= code <= 67 or 80 <= code <= 82:
        return "rain", "🌧️"
    if 71 <= code <= 77 or code in (85, 86):
        return "snow", "❄️"
    if code >= 95:
        return "thunderstorm", "⛈️"
    return "mixed weather", "🌤️"


async def current_weather(lat: float, lon: float) -> Weather | None:
    params = {
        "latitude": lat,
        "longitude": lon,
        "current": "temperature_2m,weather_code,is_day",
        "daily": "sunrise,sunset",
        "timezone": "auto",
        "forecast_days": 1,
    }
    try:
        async with httpx.AsyncClient(timeout=10, headers={"User-Agent": USER_AGENT}) as client:
            resp = await client.get("https://api.open-meteo.com/v1/forecast", params=params)
        data = resp.json()
        current, daily = data["current"], data["daily"]
    except (httpx.HTTPError, KeyError, json.JSONDecodeError):
        return None

    summary, emoji = weather_from_code(int(current.get("weather_code", -1)))
    is_day = bool(current.get("is_day", 1))
    if emoji == "☀️" and not is_day:
        emoji = "🌙"
    return Weather(
        temp_c=current.get("temperature_2m", 0),
        summary=summary,
        emoji=emoji,
        is_day=is_day,
        local_time=current.get("time", "")[-5:],
        sunrise=daily["sunrise"][0][-5:],
        sunset=daily["sunset"][0][-5:],
    )


# ---------- endpoints ----------

@app.get("/")
async def health():
    return {"status": "ok", "model": LLM_MODEL, "provider": LLM_BASE_URL}


@app.post("/quests", response_model=QuestResponse, dependencies=[Depends(require_app_key)])
async def make_quests(req: QuestRequest):
    count = max(1, min(req.count, 5))
    radius = max(300, min(req.radius_m, 3000))

    places, weather = await asyncio.gather(
        nearby_places(req.lat, req.lon, radius),
        current_weather(req.lat, req.lon),
    )

    place_lines = "\n".join(
        f"[{i}] {p.name} ({p.kind}) - {p.distance_m} m {p.direction}" for i, p in enumerate(places)
    ) or "No mapped places found nearby. Use quests that work on any street, garden or open ground."
    weather_line = (
        f"{weather.summary}, {weather.temp_c:.0f}°C, local time {weather.local_time}, "
        f"sunrise {weather.sunrise}, sunset {weather.sunset}, {'daytime' if weather.is_day else 'after dark'}"
        if weather else "unknown"
    )

    system = (
        "You design short real-world 'side quests' that get people off their phone and outside. "
        "Each quest takes 10-45 minutes on foot and ends with ONE photo that proves it was done. "
        "Quests should feel like small adventures: noticing nature, light, seasons, birds, trees, water, "
        "sky, textures - not chores and not shopping. "
        "The proof must be easy to capture in ONE ordinary phone photo: something still and clearly visible "
        "(a leaf, flower, tree, bark, water, sky, clouds, the moon, a bench, a perched bird, a street light). "
        "Never require tripods, long exposure, zoom, or animals in flight. "
        "Safety rules: never ask anyone to trespass, climb, touch wildlife, enter water, or go far after dark. "
        "In rain or thunderstorm, keep quests close to home (doorstep, balcony, covered spots). "
        "After dark, keep quests close, well-lit and about the night sky, lights or sounds. "
        "Reply only with JSON: {\"quests\": [{\"emoji\": one emoji, \"title\": max 6 words, "
        "\"description\": 1-2 vivid sentences telling exactly where to go and what to look for, "
        "\"proof\": what the photo must clearly show (one short phrase), "
        "\"minutes\": int, \"place_index\": index from the place list or null}]}"
    )
    user = f"""Weather now: {weather_line}

Places within walking distance (from OpenStreetMap):
{place_lines}

Create {count} quests. Make them different from each other:
- at least one sends the person to a specific place from the list (set place_index, mention it by name and distance),
- at least one is about noticing living nature (birds, insects, leaves, flowers, trees),
- if sunset is within 2 hours, one quest should be about the golden hour or sunset.
The proof must be something a camera can actually capture."""

    data = await ask_llm(
        [{"role": "system", "content": system}, {"role": "user", "content": user}],
        temperature=0.9,
    )

    quests = []
    for q in data.get("quests", [])[:count]:
        idx = q.get("place_index")
        place = places[idx] if isinstance(idx, int) and 0 <= idx < len(places) else None
        title = str(q.get("title", "")).strip()
        if not title:
            continue
        quests.append(
            Quest(
                id=uuid.uuid4().hex[:10],
                emoji=str(q.get("emoji", "🌿"))[:4] or "🌿",
                title=title,
                description=str(q.get("description", "")).strip(),
                proof=str(q.get("proof", "")).strip() or title,
                minutes=max(5, min(int(q.get("minutes", 20) or 20), 120)),
                place=place,
            )
        )
    if not quests:
        raise HTTPException(502, "The model didn't come up with any quests, please retry")
    return QuestResponse(weather=weather, quests=quests)


@app.post("/verify", response_model=VerifyResponse, dependencies=[Depends(require_app_key)])
async def verify(
    photo: UploadFile = File(...),
    title: str = Form(...),
    proof: str = Form(...),
):
    data = photo.file.read(MAX_PHOTO_BYTES + 1)
    if len(data) > MAX_PHOTO_BYTES:
        raise HTTPException(413, "That photo is too large")
    mime = photo.content_type if (photo.content_type or "").startswith("image/") else "image/jpeg"
    image_url = f"data:{mime};base64,{base64.b64encode(data).decode()}"

    system = (
        "You are the friendly referee of an outdoor photo quest game. "
        "Decide if the photo plausibly proves the quest. Be fair and generous with real outdoor photos "
        "(partial views, blur, low light are fine), but fail photos of screens, monitors, printed pictures, "
        "or photos clearly unrelated to the quest. "
        "Reply only with JSON: {\"success\": bool, \"caption\": one sentence describing what you see, "
        "\"message\": 1-2 warm sentences to the player - celebrate a success with a specific detail you noticed, "
        "or explain kindly what is missing and how to get it}"
    )
    messages = [
        {"role": "system", "content": system},
        {
            "role": "user",
            "content": [
                {"type": "text", "text": f"Quest: {title}\nThe photo must show: {proof}"},
                {"type": "image_url", "image_url": {"url": image_url}},
            ],
        },
    ]
    result = await ask_llm(messages, temperature=0.2)
    return VerifyResponse(
        success=bool(result.get("success", False)),
        caption=str(result.get("caption", "")).strip(),
        message=str(result.get("message", "")).strip(),
    )
