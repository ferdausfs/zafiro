# Zafiro App — Complete Setup & Operation Guide

> Ei guide e sob kichu ache: kon screen e ki korte hobe, kon API key kothay diye add korbe, kon secret kothay jabe — sob ekshathe. Step by step follow korlei pura app connect hoye jabe.

---

## 0. Big Picture — Puro System Kivabe Kaj Kore

```
┌─────────────────────────────────────────────────────────────┐
│                        APNAR PHONE                          │
│                                                             │
│   Zafiro App                                                │
│   ├── Chat UI (HomePage)          ← apni ekhane kotha bolle │
│   ├── Agent Runtime (Okia)        ← LLM ke call kore        │
│   ├── TokenVault                  ← API key encrypted store │
│   ├── Automation Triggers         ← notification/battery/   │
│   │                                  time/location event    │
│   └── Terminal / Phone-Use        ← app control, shell,     │
│                                      python, screen op      │
└──────────┬──────────────────────────────┬───────────────────┘
           │                              │
           │ (1) LLM API call             │ (3) long-poll task
           │     Authorization: Bearer    │     (25s interval)
           ▼                              ▼
┌──────────────────────────┐    ┌──────────────────────────┐
│ (2) Cloudflare AI        │    │ Cloud Brain Worker       │
│     Gateway (optional)   │    │ (Cloudflare Workers +    │
│ gateway.ai.cloudflare.com│    │  Durable Objects)        │
│  → cache/retry/log       │    │  → LLM loop server e     │
│  → provider e forward    │    │  → web_search/fetch      │
└──────────┬───────────────┘    └────────┬─────────────────┘
           │                             │
           ▼                             ▼
   OpenAI / Anthropic /            BRAIN_API_KEY diye
   DeepSeek / OpenRouter /         OpenAI-compatible
   Google / 19 provider            API e connect kore
```

**Short e:**
1. Apni LLM provider er **API key** app e den → app oi key diye model call kore
2. **AI Gateway** optional — Cloudflare er through diye call jay (caching/logs)
3. **Cloud Brain** optional — heavy agent work phone er bodole server e chole (phone thanda thake)

---

## 1. LLM Provider Setup (Sobcheye First Step)

**Screen:** `Settings → 模型 (Model) section → Configure`

Ei screen e apni provider, endpoint, API key, model — sob configure koren.

### 1.1 Built-in Providers (19 ta — API key kon site theke nibe)

| Provider | API Key Nen Kon Theke | Default Protocol |
|---|---|---|
| **OpenAI** | platform.openai.com/api-keys | openai-chat-completions |
| **Anthropic (Claude)** | console.anthropic.com | anthropic-messages |
| **DeepSeek** | platform.deepseek.com | openai-chat-completions |
| **Google (Gemini)** | aistudio.google.com/apikey | openai-chat-completions |
| **OpenRouter** | openrouter.ai/keys | openai-chat-completions |
| **Cloudflare AI (Workers AI)** | CF dashboard → API Tokens (Workers AI: Run permission) | openai-chat-completions |
| NVIDIA (NIM) | build.nvidia.com | openai-chat-completions |
| Groq | console.groq.com/keys | openai-chat-completions |
| Mistral | console.mistral.ai | openai-chat-completions |
| xAI (Grok) | console.x.ai | openai-chat-completions |
| Together | api.together.xyz | openai-chat-completions |
| Fireworks | fireworks.ai | openai-chat-completions |
| Cerebras | cloud.cerebras.ai | openai-chat-completions |
| Bailian (Alibaba) | bailian.console.aliyun.com | openai-chat-completions |
| Kimi (Moonshot) | platform.moonshot.cn | openai-chat-completions |
| SiliconFlow | cloud.siliconflow.cn | openai-chat-completions |
| CommandCode / OpenCode | remote coding agent setup | openai-chat-completions |
| Ollama (local) | key lagbe na | openai-chat-completions |

### 1.2 Custom Provider (Apnar Nijer "AI Gateway" API)

Apnar nijer kono OpenAI-compatible endpoint thakle (jemon third-party AI gateway service):

**Screen:** `Settings → Model → Configure → provider list er niche Custom Provider / add`

Fill korte hobe:
- **Base URL** — `https://` ba `http://` diye shuru hote hobe (jemon: `https://api.apnar-gateway.com/v1`)
- **API Key** — provider er dewa key
- **Model** — model name (ba "fetch models" chaple live list ashe)
- **Protocol** — default `openai-chat-completions` thaklei beshirbhag service e kaj kore

> ⚠️ **IMPORTANT (401 bug context):** Custom provider er API key **ALWAYS TokenVault e** encrypted store hoy — plaintext config e thake na. Keystore key harale (reinstall/OTA) key deko jabe na. Fix v2.1.3 te ache — details section 2 te.

### 1.3 API Key Kivabe Store Hoy (Security)

- **Built-in provider** (Configure page theke): key plaintext `api_key` field e save hoy
- **Custom provider**: key **shudhu TokenVault e** (AndroidKeyStore AES-256/GCM encrypted) — config e shudhu reference (`api_key_vault_ref`) thake
- Key **kokhono sync ba host process e jay na** — phone er app data chara kothao naoa jay na

### 1.4 Onnano Configure Field

- **Endpoint** — blank rakhle provider er official endpoint use hoy; custom endpoint dile override
- **Proxy** — HTTP/SOCKS proxy lagle (blank = direct)
- **Thinking Level** — thinking/reasoning model er jonno (blank = provider default)
- **Supports Images** — vision model hole ON koren
- **Fallback Config** — main model fail korle backup config (Settings → Model → fallback order)

---

## 2. TokenVault — API Key Er Vault (401 Problem Er Solution)

**Screen:** `Settings → Tools (工具) section → Tokens`

### 2.1 Eta Ki?

- Sob sensitive token er encrypted storage (Settings → Tokens e dekhben)
- Prati entry te: **name + note + encrypted value**
- Max **64 token**
- Encryption: AndroidKeyStore AES-256/GCM — key phone er secure hardware e thake

### 2.2 Kivabe Token Add/Update Korben

1. `Settings → Tools → Tokens` e jan
2. **+ / Add** chapdin
3. **Name** din (e.g. `my-ai-gateway-key`)
4. **Value** e API key paste korun
5. Save

Custom provider config ei vault ref ta auto-link hoye jay.

### 2.3 ⚠️ 401 Unauthorized (code 2009) Er Karon & Solution

**Problem chirotonton karon:** Phone reinstall/update/holo OTA korle AndroidKeyStore key **haray jay**, kintu encrypted entries thake jay. Tokhon app **chupchap empty key** diye request pathato → remote API **401 Unauthorized** dito. Data clear korle thik hoto karon sob fresh hoye jeto.

**v2.1.3 theke fix:** ekhon vault key harale app **loud ERROR log + detection** kore, ar silent empty-key send kora bondho.

**Jodi 401 ashe:**
1. ❌ App data clear korte hobe NA
2. ✅ `Settings → Tools → Tokens` e jan → oi key ta **re-enter** korun
3. ✅ Ba config edit kore API key field e key ta abar likhe save korun

---

## 3. Cloudflare AI Gateway (Optional — Recommended)

**Screen:** `Settings → Rules (规则) section → Cloud Brain (Cloudflare)`

### 3.1 Ki Labho?

Sob LLM call Cloudflare er edge diye jabe — **caching, retry, logs, analytics**. Apnar provider key app ei thake (BYOK).

### 3.2 Cloudflare Dashboard Setup (One-time, ~5 min)

1. **dash.cloudflare.com** e login
2. **AI → AI Gateway → Create Gateway** → nam din (e.g. `zafiro-gw`)
3. **Gateway authentication OFF** rakhen (optional upgrade pore)
4. Dashboard er right side theke **Account ID** copy korun (ba Workers & Pages overview e)

### 3.3 App Screen e Ki Diben

`Settings → Rules → Cloud Brain (Cloudflare)` e:

| Field | Ki Diben | Example |
|---|---|---|
| **AI Gateway toggle** | ON | — |
| **Account ID** | CF Account ID (32-char hex) | `a1b2c3d4...` |
| **Gateway name** | Step 3.2 er gateway nam | `zafiro-gw` |
| **Custom provider slug** | khali rakhen (built-in mapping ache) | — |
| **CF API token** | khali rakhen (Gateway auth OFF thakle) | — |

**Built-in provider → gateway slug mapping (auto):**
`openai` → `openai`, `anthropic` → `anthropic`, `deepseek` → `deepseek`, `openrouter` → `openrouter`, `google` → `google-ai-studio`
Onno provider hole custom slug dite hobe (Cloudflare doc theke).

**Kivabe kaj kore:** apnar endpoint `https://api.openai.com/v1/chat/completions` auto rewrite hoye `https://gateway.ai.cloudflare.com/v1/{accountId}/zafiro-gw/openai/v1/chat/completions` hoye jay.

### 3.4 Gateway Authentication (Optional, Advanced)

Jodi Cloudflare Gateway Authentication **ON** koren:
1. CF dashboard → My Profile → API Tokens → Create (permission: **AI Gateway: Run**)
2. App er **CF API token** field e oi token din
3. App `cf-aig-authorization: Bearer <token>` header pathabe

Auth OFF thakle ei field **khali** rakhun.

---

## 4. Cloud Brain Worker (Optional — Heavy Work Server E)

**Screen:** `Settings → Rules → Cloud Brain (Cloudflare)` — same page er second section

### 4.1 Ki?

Agent er **LLM loop server e cholbe** — phone shudhu "hands" (terminal, app launch, screen operation). Phone gora/hang kom hobe. SMS reply offline e queue hoye pore internet ashle auto-send.

### 4.2 GitHub Secrets (4 ta — Repo Settings e)

**Repo → Settings → Secrets and variables → Actions → New repository secret:**

| Secret Name | Value Kothay Theke | Kaj |
|---|---|---|
| `CLOUDFLARE_API_TOKEN` | CF → My Profile → API Tokens → template **Edit Cloudflare Workers** | Worker deploy korar token |
| `CLOUDFLARE_ACCOUNT_ID` | CF dashboard (step 3.2 er same ID) | Account identify |
| `CLOUD_BRAIN_SECRET` | nije banan: `openssl rand -hex 24` | App ↔ Worker shared secret — **same value app ei dite hobe!** |
| `BRAIN_API_KEY` | Je OpenAI-compatible key brain use korbe (OpenAI/OpenRouter/NVIDIA NIM) | Brain er LLM call er key |

### 4.3 Deploy

1. GitHub repo → **Actions** tab
2. **Deploy Cloud Brain Worker** workflow khulun
3. **Run workflow** chapdin
4. Sesh e worker URL pabe: `https://zafiro-cloud-brain.<apnar-subdomain>.workers.dev`

Brain er model/endpoint change korte chaile `worker/wrangler.toml` e:

```toml
[vars]
BRAIN_BASE_URL = "https://openrouter.ai/api/v1"
BRAIN_MODEL = "gpt-4o-mini"
```

### 4.4 App Screen e Ki Diben

| Field | Ki Diben |
|---|---|
| **Cloud Brain toggle** | ON |
| **Worker URL** | `https://zafiro-cloud-brain.<sub>.workers.dev` |
| **Shared secret** | `CLOUD_BRAIN_SECRET` er same value |
| **Test connection** | chapdin → "Connected: ok" asha uchit |

### 4.5 Security Note (Khub Important)

Cloud Brain ON thakle agent trigger server e run hoy — phone **shudhu whitelist action** pacche: `terminal`, `launch_app`, `open_uri`, `notify`, `find_installed_apps`, `screen_operation_*`, `memory`, `todo_write`. **Kintu `execute_python`, screenshot, TokenVault — kokhono server e jay na.**

---

## 5. Cloudflare AI Provider (Workers AI — No Extra Key)

Provider list e **Cloudflare AI** entry ache — Cloudflare er nijer model (bill CF account e):

1. CF dashboard → API Tokens → token banan (**Workers AI: Run** permission)
2. App e provider → **Cloudflare AI** → oi token API key field e din
3. Endpoint prefilled `{account_id}` — Account ID auto-replace hoy (Cloud Brain settings theke)
4. Default model: `@cf/meta/llama-3.3-70b-instruct-fp8-fast`
5. "Fetch models" chaple live model list ashbe

---

## 6. Automation — Proactive Triggers (SMS/Notification/Time/Battery/Location)

**Screen:** `Settings → Rules (规则) section → Automation`

Ei screen e apni rule banaben — kono event hole agent auto kaj korbe.

### 6.1 Trigger Sources (5 rokom)

| Source | Kichu Pare | Extra Field |
|---|---|---|
| **NOTIFICATION** | system notification dhore | App package (khali = jekono app), sender/title contains, keywords |
| **FILE_DOWNLOAD** | Download folder e notun file asle | — |
| **BATTERY** | Battery event (LOW ≤20%, CHARGING, FULL ≥95%, OKAY, POWER_SAVE_ON/OFF — Samsung) | battery level % |
| **TIME** | Nirdishto time e (HH:mm + weekday) | — |
| **LOCATION** | Jayga e dhukle/ber hole (lat/lng + radius) | ENTER/EXIT mode |

### 6.2 Actions (2 rokom)

- **AGENT** — LLM agent jage, event context shoho prompt run kore (token khoshe)
- **ALERT** — token kharoch na kore direct high-priority notification dey

### 6.3 Rule Editor Fields

- **Name** — rule er nam
- **Source** — upore 5 tar ekta
- **App / Sender / Keywords** — filter (khali = shob match)
- **Action** — AGENT ba ALERT
- **Prompt** — AGENT hole agent er instruction template
- **Cooldown** — 2 bar trigger er moddhe minimum gap (seconds) — storm prevent kore

### 6.4 Permissions (Automation Kaj Korte Lagbe)

- **Notification access** — Settings e prompt asbe → allow (NotificationListenerService)
- **Battery optimization** — OFF korte hobe prompt e (nahole background e trigger hobe na)
- **Exact alarm** — Android 12+ e permission prompt asbe

---

## 7. Device Prep — App Er Puro Kaj Korte Ki Ki Permission Lagbe

App install korar por ei gulo on/off korte hobe:

| Permission | Keno | Kivabe |
|---|---|---|
| **Shizuku** | Root chara shell/app control | Shizuku app install → wireless/adb debug start → Zafiro te grant |
| **Accessibility / Screen capture** | Phone-use / screen operation | Settings → System Integration section e prompt |
| **Notification access** | Automation trigger | Settings → Rules → Automation |
| **Battery optimization ignore** | Background agent/automation | Settings prompt → allow |
| **Install unknown apps** | In-app self-update (v2.1.2+) | System prompt e allow |
| **Storage / Media** | File operation, downloads | Runtime prompt |
| **Contacts / Calendar / Location** | v1.7.0 system-integrated agent features | Runtime prompt (jodi use korte chan) |
| **Root (optional)** | Full experience (APK install, deep control) | Magisk/KernelSU e grant |

**Voice Assistant Takeover (Root + LSPosed):** OPPO/OnePlus/Realme er Breeno assistant ke takeover kora jay — wake word e apnar agent uthe. (Xiaomi XiaoAi support bondho ache.)

---

## 8. Release / Update Process (CI Fixed — v2.1.3 Theke Automatic)

### 8.1 Ekhon Kivabe Release Hobe

1. Code push kore `main` e
2. Version bump din: `app/build.gradle.kts` → `versionName` + `versionCode`
3. Tag push: `git tag v2.1.4 && git push origin v2.1.4`
4. **GitHub Actions → Release Build** auto build kore release create korbe
5. Asset name: `Zafiro-<version>.apk` (arm64)

### 8.2 Build Er Signing

- Keystore: `keystore/zafiro-release.jks` (repo committed)
- Password/alias: `gradle.properties` e (`zafiro2026` / alias `zafiro`)
- **Sob release same signature** — verified SHA-256 `52:00:51:27:84:E3:85:D6:...`
- Manrei direct update install hobe, uninstall lagbe na

### 8.3 In-App Self-Update (v2.1.2+)

- App er bhitore update check → download (progress) → PackageInstaller session install
- "Install unknown apps" permission lagbe
- Fail hole user-readable error dekhabe (blocked/aborted/invalid/storage/incompatible)

### ⚠️ 8.4 Security Warning (Serious)

**Keystore + password public repo te committed ache.** Keu repo clone kore apnar app er signature diye malicious update banate pare. Recommendation:

- **Short term:** repo **private** korun (Settings → General → Danger Zone → Change visibility)
- **Long term:** keystore rotate korun (tabe ekbar sob user uninstall/reinstall korbe — signature change hobe)
- GitHub token kokhono chat/docs e paste korben na

---

## 9. Secrets Summary — Kon Secret Kothay Jabe (Quick Table)

| Secret/Key | Banaben Kon Jaygay | Add Korben Kon Jaygay |
|---|---|---|
| Provider API key (OpenAI/DeepSeek/etc.) | Provider er dashboard | App → Settings → Model → Configure |
| Custom provider key | Apnar gateway service | App → Custom Provider (vault e auto-store) |
| Cloudflare Account ID | CF dashboard right side | App → Cloud Brain settings + GitHub secret |
| CF API token (deploy) | CF → API Tokens (Edit Workers template) | GitHub secret `CLOUDFLARE_API_TOKEN` |
| CF API token (gateway auth) | CF → API Tokens (AI Gateway: Run) | App → CF API token field (auth ON hole) |
| Workers AI token | CF → API Tokens (Workers AI: Run) | App → Cloudflare AI provider key |
| `CLOUD_BRAIN_SECRET` | nije: `openssl rand -hex 24` | GitHub secret + App → Shared secret (SAME value) |
| `BRAIN_API_KEY` | OpenAI/OpenRouter/etc. | GitHub secret only |
| `BRAIN_BASE_URL` / `BRAIN_MODEL` | — | `worker/wrangler.toml` [vars] |

---

## 10. Troubleshooting

| Problem | Karon | Fix |
|---|---|---|
| **401 Unauthorized (code 2009)** | Keystore key haraye vault decrypt fail → empty key | Settings → Tokens e key **re-enter** (data clear lagbe na); v2.1.3 te fix |
| **Update install hoy na** | Signature consistent (verified) — Play Protect / storage / corrupt download | `adb install -r <apk>` diye exact error nen; Play Protect → app scanning OFF; storage check |
| **"Connected: ok" ashe na (Cloud Brain)** | Worker URL/secret bhul, worker deploy hoy nai | Wrangler deploy log check; `CLOUD_BRAIN_SECRET` app ↔ GitHub same kina |
| **Gateway 401 (cf-aig-authorization)** | Gateway Authentication ON but token empty | CF token (AI Gateway: Run) nile token field e din, ba gateway auth OFF korun |
| **Automation trigger hocche na** | Battery optimization ON, notification access off | Automation settings er prompt follow korun |
| **Model catalog fetch fail** | Key/endpoint bhul ba provider /models support kore na | Key + endpoint verify; nijei model name likhe din |
| **App er data haray geche** | — | Vault token re-enter + config re-check (v2.1.3 er backup exclusion agam theke help korbe) |

---

## 11. Recommended Setup Order (Fresh Start)

```
1. Phone:      Zafiro install (arm64 APK)
2. Phone:      Shizuku + permissions grant (section 7)
3. App:        LLM provider + API key (section 1)  ← AI kaj shuru
4. App:        Test chat → agent reply ashche kina dekhen
5. CF:         AI Gateway banao (section 3)         ← optional
6. App:        Gateway settings + test
7. GitHub:     4 secrets + Cloud Brain deploy (section 4) ← optional
8. App:        Worker URL + secret + Test connection
9. App:        Automation rules banao (section 6)
10. Sob chalu! Agent chat + SMS/notification automation + phone-use
```

Ei order e korle ekta ekta step verify kore agabe — problem hole kon step e bug seta jaldhi dhora porbe.
