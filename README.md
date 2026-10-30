# Ferry

An Android TV IPTV player built to be driven by Home Assistant: MQTT discovery, deep links, and a phone-friendly web config page so you never type a URL with a D-pad.

> Working name. Package is `dev.sysmoon.ferry`; rename with Android Studio's *Refactor → Rename package* plus `applicationId` in `app/build.gradle.kts`.

## What's in v0.1

| Area | Status |
|---|---|
| M3U / M3U Plus and Xtream Codes sources | ✅ streaming parser, favourites/hidden/order survive refreshes |
| XMLTV EPG (`.xml` / `.xml.gz`) | ✅ filtered to your channels, auto-refresh |
| Guide (group rail + channel list with now/next) | ✅ |
| Player (Media3, HLS + MPEG-TS), zapping, info banner | ✅ |
| Web config server with PIN/QR pairing | ✅ `http://<tv-ip>:8765` |
| Home Assistant MQTT discovery | ✅ |
| Deep links / explicit intents | ✅ |
| mDNS advertising (`_ferry._tcp`) | ✅ |
| EPG grid view, catch-up, recording, libVLC fallback | ⏳ next |

## Build

Open the folder in Android Studio (Ladybug or newer), let Gradle sync, run on your TV. Versions live in `gradle/libs.versions.toml`, so bump them there if Studio offers newer ones.

```
./gradlew installDebug        # needs a gradle wrapper; Studio generates it on first sync
./gradlew test                # M3U + XMLTV time parser tests
```

## First run

1. Launch Ferry, go to **Settings & pairing**.
2. Scan the QR code with your phone. It opens the config page and pairs automatically (the QR carries the PIN).
3. **Sources → Add**: paste an M3U URL (+ optional EPG URL) or Xtream server/username/password.
4. **Channels**: star favourites, hide groups you don't want.
5. **Home Assistant**: enter your MQTT broker, *Test connection*, *Save*.

### Let HA bring Ferry to the front

On Android 10+ an app can't start its own activity from the background unless it has *Display over other apps*. Grant it once:

```
adb shell appops set dev.sysmoon.ferry SYSTEM_ALERT_WINDOW allow
```

Without it, MQTT/API commands still work while Ferry is open; from the background, use the Android TV Remote deep link below instead.

## Home Assistant

### MQTT entities (one device per TV)

| Entity | What it does |
|---|---|
| `select.<name>_channel` | Favourites; choosing one tunes the TV |
| `sensor.<name>_state` | idle / buffering / playing / paused / error |
| `sensor.<name>_channel_name`, `_programme`, `_next_programme` | With full JSON attributes (logo, tvg_id, times, description, deep link) |
| `sensor.<name>_progress` | Programme progress % |
| `button.<name>_channel_up` / `_channel_down` / `_stop` | Remote keys |

Topics (`base = ferry/<device-id>`):

```
base/availability   online | offline (LWT)
base/state          JSON status, retained
base/cmd/play       tvg:BBCOne.uk | id:<channelId> | number:101 | 101 | <channel name>
base/cmd/channel    favourite name (used by the select)
base/cmd/key        up | down | stop | pause | last
```

```yaml
action: mqtt.publish
data:
  topic: ferry/<device-id>/cmd/play
  payload: "tvg:BBCOne.uk"
```

### Deep links (Android TV Remote integration, ADB, NFC)

```
ferry://tvg/<tvg-id>     best choice: survives playlist re-imports
ferry://number/<n>
ferry://name/<url-encoded name>
ferry://channel/<channel-id>
ferry://last
ferry://guide
```

```yaml
action: media_player.play_media
target:
  entity_id: media_player.living_room_tv   # Android TV Remote entity
data:
  media_content_type: url
  media_content_id: ferry://tvg/BBCOne.uk
```

ADB / Tasker:

```
adb shell am start -a android.intent.action.VIEW -d "ferry://tvg/BBCOne.uk"
adb shell am start -a dev.sysmoon.ferry.action.PLAY --es tvg_id BBCOne.uk
```

NFC tags can hold the `ferry://` URI directly.

### REST (for `rest_command`)

Pair once to get a token (`POST /api/pair {"pin":"123456"}`), then:

```yaml
rest_command:
  ferry_play:
    url: http://<tv-ip>:8765/api/play
    method: post
    headers: { Authorization: !secret ferry_token }   # "Bearer <token>"
    content_type: application/json
    payload: '{"tvgId": "{{ tvg_id }}"}'
```

## API

All under `/api`, `Authorization: Bearer <token>` (WebSocket: `?token=`).

| Method | Path | |
|---|---|---|
| POST | `/pair` | `{pin}` → `{token}` (no auth; 5 wrong PINs = 60s lockout) |
| GET | `/status` | now playing |
| WS | `/ws` | status pushes |
| GET/POST | `/sources` | list / add (triggers import) |
| PUT/DELETE | `/sources/{id}` | edit (`password: null` keeps it) / delete |
| POST | `/sources/{id}/refresh` | |
| GET | `/channels?q=&group=&limit=&offset=` | paged |
| PATCH | `/channels/{id}` | `{favourite?, hidden?}` |
| GET | `/groups`, `/favourites` | |
| POST | `/groups/hidden` | `{group, hidden}` |
| POST | `/favourites/order` | `{ids: [...]}` |
| POST | `/play` | `{channelId? tvgId? number? name?}` |
| POST | `/key` | `{key}` |
| GET/PUT | `/mqtt` | passwords are write-only |
| POST | `/mqtt/test` | |
| PUT | `/device` | `{name}` |
| POST | `/tokens/revoke` | unpair everything |

## Layout

```
app/src/main/java/dev/sysmoon/ferry/
  FerryApp.kt          AppGraph (manual DI)
  FerryService.kt      foreground service: server, MQTT, NSD, refresh loop; BootReceiver
  MainActivity.kt      screens + intent handling
  DeepLinks.kt         ferry:// and PLAY intent parsing/building
  RemoteCommands.kt    shared command handling for MQTT + API
  data/                Room entities/DAOs, repository (import/refresh), DataStore settings
  parse/               M3U, XMLTV parsers (pure Kotlin, unit-tested)
  player/              PlayerController (single ExoPlayer, now/next state)
  server/              Ktor config server + DTOs
  mqtt/                HA discovery bridge (HiveMQ client)
  net/                 local IP, mDNS advertiser
  ui/                  Compose for TV screens
app/src/main/assets/web/index.html   config page (no build step)
```

## Notes / known gaps

- Up on the remote = previous channel in the list, Down = next. Left/Back = guide.
- Leaving the app (Home) stops playback; the service keeps running for HA control.
- Renaming the device updates HA after the next MQTT reconnect (toggle MQTT or restart the app).
- Streams that ExoPlayer can't decode (rare TS muxing, some AC3 on cheap boxes) will need the planned libVLC fallback.
- Credentials are stored in app-private storage, not encrypted; fine for a home TV, revisit before sharing builds.
