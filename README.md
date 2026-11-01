# Ferry

An Android TV IPTV player built to be driven by Home Assistant: MQTT discovery, deep links, and a phone-friendly web config page so you never type a URL with a D-pad.

> Working name. Package is `dev.sysmoon.ferry`; rename with Android Studio's *Refactor → Rename package* plus `applicationId` in `app/build.gradle.kts`.

## Features

| Area | What you get |
|---|---|
| Sources | M3U / M3U Plus, Xtream Codes, and BBC iPlayer (UK). Favourites, hidden channels and order survive refreshes, even when the provider renames a channel |
| Duplicates | Backup / other-quality copies of a channel fold into one row; the best stream plays first. If a stream fails, drops or stalls (when tuning in or mid-programme), Ferry switches to the channel's other streams in turn, then keeps retrying for a minute. Long-press OK to pick a stream |
| Sky layout | Optional: channels numbered and grouped like a Sky box (Entertainment, Movies, Sports…), for your Sky region, using Sky's own channel list |
| Menu | Main menu: Search, Favourites, Sports, TV Guide, Channel List, Settings. TV Guide and Channel List have their own group list |
| Guide | EPG grid (TV Guide) or channel list (Channel List). The menus hide while you browse; Back brings them back. Up/down keeps your place in time |
| Guide gaps | Channels the provider has no guide for are filled from Sky's schedules, then from any backup XMLTV feeds you add |
| Catch-up | Xtream archive: play past programmes from the guide or the player card, with seeking |
| Player | Sky+-style info card, controls (restart, ±30s, pause, live), number entry, channel up/down |
| Mini player | Back from a stream keeps it playing in a corner tile over the guide; picking the same channel goes back to full screen without the info card |
| Reminders | OK on a programme that hasn't started sets a reminder; a pop-up offers to switch when it starts |
| Search | Programmes (catch-up, on now, coming up) and channels, from the main menu |
| Previous channel | Flip between the last two channels (remote's last-channel key, or the controls row) |
| Recently watched | A group in TV Guide / Channel List with the channels you've watched lately |
| Sleep timer | 15–120 minutes from the controls row or Home Assistant, with a one-minute warning |
| Keep in sync | A box can follow another Ferry and pick up its setup changes automatically |
| Football | Premier League and Champions League fixtures; finds every stream in your playlist that might show a match |
| Start-up | Optionally opens straight into the last watched channel |
| Web config | Phone-friendly page with PIN/QR pairing at `http://<tv-ip>:8765`, including backup & sync between boxes |
| Home Assistant | MQTT discovery, deep links, REST API, mDNS (`_ferry._tcp`) |

## Build

Open the folder in Android Studio (Ladybug or newer), let Gradle sync, run on your TV. Versions live in `gradle/libs.versions.toml`, so bump them there if Studio offers newer ones.

```
./gradlew installRelease      # faster on TV boxes; installDebug for diagnostics
./gradlew test                # parser, duplicate-merging and Sky matching tests
```

## First run

1. Launch Ferry, go to **Settings** in the main menu.
2. Scan the QR code with your phone. It opens the config page and pairs automatically (the QR carries the PIN).
3. **Sources → Add**: paste an M3U URL (+ optional EPG URL) or Xtream server/username/password.
4. **Groups**: tick the provider groups to **Use**. With the Sky layout on, tick **Own section** for any group
   you want as its own entry in the guide alongside Sky's sections.
5. **Home Assistant**: enter your MQTT broker, *Test connection*, *Save*.

Setting up a second box? Use **Device → Backup & sync** instead (below).

## Using it

### Menus

Ferry opens on the **main menu**: 🔍 Search, ★ Favourites, ⚽ Sports, 📺 TV Guide, ☰ Channel List, ⚙ Settings.

- **Favourites** goes straight to a guide of your favourite channels.
- **TV Guide** (programme grid) and **Channel List** (one row per channel, with what's on now and next) open
  their **group list** first (Recently watched, All channels / Sky sections, your playlist's groups); pick
  a group to browse it.
- **Sports** is the football finder (below).
- The menus slide away while you browse so the guide gets the whole screen. **Back** walks back up:
  channels → group list → main menu. **Back** in the main menu exits Ferry and stops anything playing.

### Guide

- The grid is compact and fills the screen (about ten and a half channels on a 1080p TV).
- Up/down in the grid keeps you lined up with the time you were on, whatever the programme lengths.
- **OK** on a live programme plays it; on a past programme with catch-up it plays from the start; on one that
  hasn't started it sets (or clears) a reminder, shown with 🔔.
- **Long-press OK** on a channel: favourite it, or pick which of its streams to play.
- **🔍 Search** (main menu) finds programmes and channels as you type. OK plays, plays catch-up,
  or sets a reminder, depending on when the programme is on.
- **🕘 Recently watched** (top of the group list) lists the channels you've watched lately, newest first.

### Player

- **OK** shows the info card: now, previous and next programme, progress.
  - **Up/down** browses other channels on the card (no video preview: providers usually allow one stream);
    **OK** switches to the one shown.
  - **Left/right** steps through earlier and later programmes; **OK** on an earlier one plays it from catch-up.
  - **OK** on the channel you're watching opens the controls: Restart (from the programme start, via
    catch-up), back/forward 30 s, pause, Live, Previous (channel), Sleep (cycles 15 / 30 / 60 / 90 / 120 min /
    off) and Streams.
  - **OK** on a programme that hasn't started sets a reminder.
- **Last-channel key** (where the remote has one) flips to the previous channel.
- **Number keys** tune by channel number (Sky numbers when the Sky layout is on).
- **CH+/CH−** and the remote's rewind / fast-forward keys work as you'd expect.
- **Back** returns to the guide with the stream still playing in the **mini player** (top-right). Pick the
  same channel to go back to full screen (the info card only pops up when the channel changes). Back from
  the main menu exits Ferry, and leaving Ferry (Back or Home) stops playback.

### Sky layout

Turn it on under **Groups → Sky layout** and pick your region (it sets the regional BBC / ITV channels).
Ferry downloads Sky's channel list (satellite and Sky Q streaming lineups) once a day and matches your
playlist to it by name and guide ID, including Sky's seasonal renames (Sky Sports NFL ↔ Action, BT Sport →
TNT Sports). Anything it can't match by name is compared by schedule (same programmes at the same
times). Channels still unmatched are listed in the web page with a **Pick…** search so you can match them
by hand; manual picks stick across refreshes.

### Football

**⚽ Sports** (main menu) shows Premier League and Champions League fixtures from ESPN's
public data, live scores first, with **Clubs** to pick your teams (they get a **★ My teams** tab).
Choosing a match lists every stream that might show it, best first, with the reason:

1. **Channel name** with both teams (providers rename event channels on match day, e.g.
   "EPL02: Bournemouth 15:00 Leeds 31/10"); dated channels for another day are ignored.
2. **Guide data** around kick-off naming both teams, in any language/spelling, on any channel
   (hidden groups included, so foreign sports channels count).
3. One team plus the competition in the guide, the club's own channel, or a broadcaster ESPN lists.
4. Just the competition at kick-off on a sports channel (weakest).

Other sports, magazine shows ("Preview", "Highlights") and look-alike names are filtered out; UK streams
rank first. Playing one makes that list the channel up/down list, so if a stream dies, ▲ tries the next;
Back returns to the list.

### Reminders

Set from the guide, the info card or search. When the programme starts (or Ferry is opened while it's on),
a pop-up offers **Watch now**. Home Assistant gets an `event.<name>_events` event (`reminder`) and a
`sensor.<name>_next_reminder`, so an automation can, for example, turn the TV on or send a notification.

### Sleep timer

From the controls row (**☾ Sleep**) or Home Assistant's `number.<name>_sleep_timer`. In the last minute
Ferry shows a warning; any button keeps watching. When it runs out playback stops, Ferry returns to the
guide, and HA gets a `sleep` event (handy for turning the TV off).

### Guide gaps

After each refresh Ferry looks for channels with nothing in the guide for the next few hours and fills them,
first from Sky's own schedules (for Sky-matched channels), then from any **backup guide data** XMLTV URLs
added under **Sources**.

### Backup & sync between boxes

**Device → Backup & sync → Export settings** downloads a JSON file with this box's playlists (including
logins), options, Sky layout and picks, group choices, favourites and channel order. On the other box's web
page, **Import settings…** makes it match: playlists already there (same server and login) are kept,
others are added, and ones not in the file are removed. Each box keeps its own name, pairing and Home
Assistant identity, and downloads its own guide. The file contains passwords, so keep it private.

**Keep in sync** (same card) makes a box follow another Ferry: pick it from **Find Ferry boxes** (or enter
its address), type the PIN shown on its **Settings** screen, and it copies the setup straight
away, then checks every 15 minutes and applies any changes. It's one-way: make changes on the box you follow.

### Let HA bring Ferry to the front

On Android 10+ an app can't start its own activity from the background unless it has *Display over other apps*. Grant it once:

```
adb shell appops set dev.sysmoon.ferry SYSTEM_ALERT_WINDOW allow
```

Without it, MQTT/API commands still work while Ferry is open; from the background, use the Android TV Remote deep link below instead.

## BBC iPlayer (optional, UK only)

Add a source of type **BBC iPlayer** in the web UI and pick your BBC One region. Ferry adds BBC One
(your region), Two, Three, Four, News, Parliament, CBBC, CBeebies, Scotland, Alba and S4C as their
own **BBC iPlayer** group, separate from your playlists, with schedules from iPlayer.

- Stream addresses are fetched from the BBC each time a channel is played (HD first, SD fallback).
- UK only; a TV Licence is required to watch live TV.
- Unofficial: it uses the same public request as Streamlink (see `NOTICE.md`) and may break if the
  BBC changes things. No DRM is involved and Ferry doesn't bypass any.
- The ✓ button next to the source in the web UI checks every channel from the TV's own connection.

## Home Assistant

### MQTT entities (one device per TV)

Each TV's device page has a **Visit** link to its web setup page.

| Entity | What it does |
|---|---|
| `select.<name>_channel` | Favourites; choosing one tunes the TV |
| `sensor.<name>_state` | idle / buffering / playing / paused / error |
| `sensor.<name>_channel_name`, `_programme`, `_next_programme` | With full JSON attributes (logo, tvg_id, times, description, deep link) |
| `sensor.<name>_progress` | Programme progress % |
| `button.<name>_channel_up` / `_channel_down` / `_stop` / `_pause` / `_previous_channel` / `_live` | Remote keys |
| `image.<name>_channel_logo` | Logo of the channel playing |
| `number.<name>_sleep_timer` | Minutes left; set it to start the timer (0 = off) |
| `sensor.<name>_next_reminder` | Next reminder, with the full list as attributes |
| `event.<name>_events` | `reminder` (a reminded programme is starting) and `sleep` (timer stopped playback) |

Topics (`base = ferry/<device-id>`):

```
base/availability   online | offline (LWT)
base/state          JSON status, retained
base/cmd/play       tvg:BBCOne.uk | id:<channelId> | number:101 | 101 | <channel name>
base/cmd/channel    favourite name (used by the select)
base/cmd/key        up | down | stop | pause | previous_channel | live
base/cmd/sleep      minutes (0 = off)
base/logo           logo URL of the channel playing
base/reminders      next reminder + list (JSON)
base/event          {"event_type": "reminder" | "sleep", ...} (not retained)
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
| GET/PUT | `/groups/detail`, `/groups/visible` | group list with hidden state / set the groups to use |
| GET/PUT | `/sky` | Sky layout status / `{enabled, region, extraGroups}` |
| PUT | `/sky/override` | `{number, channelId?}` manual Sky match (null clears) |
| GET/PUT | `/options` | `{mergeDuplicates?, resumeLastChannel?, backupEpgUrls?}` |
| POST | `/guide/fill` | fill guide gaps now |
| GET | `/export` | settings export (includes playlist passwords) |
| POST | `/import` | apply an export to this box |
| GET/PUT/DELETE | `/sync` | follow status / follow `{url, pin}` / stop following |
| GET | `/sync/peers` | other Ferry boxes found on the network |
| POST | `/sync/now` | check the followed box now |

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

- Up on the remote = previous channel in the list, Down = next.
- CI publishes `ferry.apk` (release, recommended) and `ferry-debug.apk` to the rolling `nightly` release.
- If a live stream fails, drops or freezes for 10 s mid-programme, Ferry moves through the channel's other
  streams (each once), then retries from the best one; it only gives up after 3 full rounds in a minute.
  A stream that has played steadily for 30 s resets the round. HLS streams that fall behind the live
  window jump back to live instead of switching.
- Leaving the app (Home) stops playback; the service keeps running for HA control.
- Most providers allow one stream per account at a time; a second box (or another app) on the same
  account gets "provider refused the stream (HTTP 407)".
- Renaming the device updates HA after the next MQTT reconnect (toggle MQTT or restart the app).
- App data copied from another box (adb, cloned install) is detected and given its own device identity, so
  two boxes never appear as one device in HA. Use Backup & sync to copy settings instead.
- Streams that ExoPlayer can't decode (rare TS muxing, some AC3 on cheap boxes) will need the planned libVLC fallback.
- Credentials are stored in app-private storage, not encrypted; fine for a home TV, revisit before sharing builds.
