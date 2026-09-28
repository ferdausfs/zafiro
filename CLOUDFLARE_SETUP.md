# Zafiro Cloud Brain — Cloudflare Setup Guide

> Bangla quick-start at the bottom / নিচে বাংলা সংক্ষিপ্ত গাইড।

## What you get

| Piece | Where it runs | What it does |
|---|---|---|
| **AI Gateway** (app setting) | Cloudflare edge | Routes every LLM call through your gateway: caching, retries, logs, analytics. Your provider API key stays in the app. |
| **Cloud Brain Worker** (this repo `worker/`) | Cloudflare Workers + Durable Objects | The agent's *brain*: LLM loop, web_search, web_fetch. The phone stays the *hands*: terminal, apps, UI automation. |
| **Offline outbox** (app) | Phone | Tasks/results queue locally when the internet drops and auto-flush when it returns (SMS-reply case). |

## One-time setup (≈10 minutes)

### 1. Create the AI Gateway

1. Cloudflare dashboard → **AI** → **AI Gateway** → *Create Gateway* → name it (e.g. `zafiro-gw`).
2. Keep **Gateway authentication OFF** for now (the app routes by URL; key enforcement is a later upgrade).
3. Copy your **Account ID** (dashboard right side, or Workers & Pages overview).

### 2. Create an API token for deployment

My Profile → **API Tokens** → *Create Token* → template **Edit Cloudflare Workers**.
It needs: Workers Scripts:Edit, Account Settings:Read. Save it.

### 3. Put 3 secrets into this GitHub repo

Repo → Settings → Secrets and variables → **Actions** → New repository secret:

| Secret | Value |
|---|---|
| `CLOUDFLARE_API_TOKEN` | token from step 2 |
| `CLOUDFLARE_ACCOUNT_ID` | account id from step 1 |
| `CLOUD_BRAIN_SECRET` | any long random string, e.g. `openssl rand -hex 24` — **this exact value also goes into the app later** |
| `BRAIN_API_KEY` | an OpenAI-compatible API key the brain uses (OpenAI / OpenRouter / NVIDIA NIM / any compatible endpoint) |

### 4. Deploy

Push anything under `worker/` (or run the **Deploy Cloud Brain Worker** workflow manually via *Actions → Run workflow*).
The workflow runs `wrangler deploy`, syncs secrets, and prints the deployment.
Your worker URL will be: `https://zafiro-cloud-brain.<your-subdomain>.workers.dev`
(visible in the workflow log / dashboard).

Optional brain endpoint override (commit to `worker/wrangler.toml` `[vars]`):

```toml
[vars]
BRAIN_BASE_URL = "https://openrouter.ai/api/v1"   # any OpenAI-compatible endpoint
BRAIN_MODEL = "gpt-4o-mini"
```

### 5. Fill the app settings

Zafiro → Settings → **Cloud Brain (Cloudflare)**:

* AI Gateway section → enable → Account ID + Gateway name (`zafiro-gw`).
  *Provider mapping*: `openai`, `anthropic`, `deepseek`, `openrouter`, `google` are auto-mapped.
  Other providers need the *custom provider slug* (whatever Cloudflare documents for that provider).
* Cloud Brain section → enable → Worker URL (`https://zafiro-cloud-brain.<sub>.workers.dev`) + Shared secret (same as `CLOUD_BRAIN_SECRET`).
* Tap **Test connection** → should say *Connected: ok*.

### 6. Route automation through the brain

With the Cloud Brain toggle on, every automation AGENT trigger runs on the server:
the phone only receives whitelisted action commands (`terminal`, `launch_app`,
`open_uri`, `notify`, `find_installed_apps`, `screen_operation_*`, `memory`,
`todo_write`). `execute_python`, screenshots and the token vault are never
sent to the server.

## Honest limits

* The phone still needs the internet to *talk* to the brain. Sending an SMS never
  needs the internet; generating the reply does. With the outbox, a task submitted
  offline is queued and auto-sent on reconnect — the reply is no longer lost.
* Long-poll push (25s) while the automation service is alive; not a replacement
  for FCM — a future phase can add FCM/WebSocket if needed.
* Session hard cap: 10 minutes, ≤40 reasoning steps.

---

## বাংলা কুইক-স্টার্ট

১. Cloudflare dashboard → AI → AI Gateway → নতুন Gateway বানাও (নাম যেমন `zafiro-gw`)। Account ID কপি করো।
২. API Token বানাও (*Edit Cloudflare Workers* template)।
৩. GitHub repo → Settings → Secrets → ৪টা সিক্রেট দাও: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `CLOUD_BRAIN_SECRET` (লম্বা র‍্যান্ডম স্ট্রিং), `BRAIN_API_KEY` (ব্রেইন যে model API ব্যবহার করবে)।
৪. Actions → Deploy Cloud Brain Worker → Run workflow। শেষে worker URL পাবে: `https://zafiro-cloud-brain.<তোমার-সাবডোমেইন>.workers.dev`
৫. অ্যাপ → Settings → Cloud Brain (Cloudflare) → Account ID + Gateway নাম দাও, Worker URL + সিক্রেট দাও, **Test connection** চাপো।
৬. Cloud Brain toggle ON = অটোমেশনের সব ভারী কাজ সার্ভারে, ফোন শুধু কাজ করবে — ফোন গরম/হ্যাং অনেক কমবে।
