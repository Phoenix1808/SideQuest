# Side Quest: small outdoor adventures from the places around you

Side Quest is an Android app that gets you off the screen. Open it and it writes **three short
real-world quests** from the actual parks, lakes, gardens and viewpoints within walking distance,
shaped by today's weather and sunset time. Put the phone away and go. When you find the thing,
snap a photo: an **open-weight vision model** checks it and the quest goes into your field journal.

Built for the DEV **Hacktoberfest 2026 Open-Source AI Challenge, Week 1: Touch Grass**.

## How it works

```
Android app (Kotlin + XML)
   │  location ─────────────▶  POST /quests
   │  photo proof ──────────▶  POST /verify
   ▼
FastAPI backend (Python, on Render)
   ├── OpenStreetMap (Overpass API)  → real places within ~1.5 km
   ├── Open-Meteo                    → weather, sunrise, sunset (no key)
   └── Qwen 3.8 27B, open weights    → writes the quests, checks the photos
```

The phone does no AI work, and the screen time is tiny: about 30 seconds to read a quest and
one photo at the end.

### What's open here
| Piece | What it does |
|---|---|
| **Qwen 3.8 27B** (open-weight, text + vision) | Writes the quests; referees the photo proof |
| **OpenStreetMap** | The nearby places quests send you to |
| **Open-Meteo** (open-source weather API) | Keeps quests sensible: rain, darkness, golden hour |

The backend talks to any OpenAI-compatible endpoint, so the model is swappable: Qwen on Groq
today, Gemma on Hugging Face or a local Ollama server tomorrow. It's a change to three
environment variables (see `backend/.env.example`).

## Run the backend

```bash
cd backend
python -m venv .venv
.venv\Scripts\activate          # macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
copy .env.example .env          # then put your Groq key in .env
uvicorn main:app --reload --port 8000
```

Get a free Groq key at https://console.groq.com/keys.

| Method | Path | What it does |
|---|---|---|
| GET  | `/` | Health check; returns the active model |
| POST | `/quests` | `{lat, lon}` → weather + 3 quests built from nearby places |
| POST | `/verify` | Photo + quest → `{success, caption, message}` |

## Deploy the backend to Render

1. Push this repo to GitHub.
2. In Render, choose **New → Blueprint**, pick the repo, and it will read `render.yaml`.
3. Set `LLM_API_KEY` when prompted.
4. Copy the service URL, for example `https://sidequest-api.onrender.com/`.

## Run the Android app

1. Open the `android/` folder in Android Studio.
2. In `android/gradle.properties`, set `BACKEND_URL`:
   - deployed backend: `https://sidequest-api.onrender.com/`
   - phone over USB with the backend on your PC: `http://localhost:8000/`, then run
     `adb reverse tcp:8000 tcp:8000`
3. Run it on a phone, then go outside.
