# bhevesiatvlauncher

Letisztult, gyors kezdőképernyő (launcher) Android TV-re, a Sony AG8-ra (Android 9, 1920×1080, 960×540 dp) méretezve.

- Óra, dátum, napszak szerint változó háttér (hajnal / nappal / alkony / éjszaka, a valódi napkeltéhez és napnyugtához igazítva)
- Kedvencek sor (6 app), „Minden app” lista. Hosszan nyomott OK vagy Menü gomb: áthelyezés, eltávolítás, hozzáadás
- Home Assistant: állapotchipek, „Most szól” kártya, jelenetgombok (opcionális)
- OLED-kímélés: a statikus elemek percenként néhány pixellel elmozdulnak
- Nincs reklám, nincs ajánlás, nincs telemetria, nincs külső függőség

## Fordítás

A GitHub Actions minden `main`-re küldött commitnál lefordítja és kiadja az APK-t:
`https://github.com/bhevesi2/bhevesiatvlauncher/releases/latest/download/launcher.apk`

Az aláíró kulcs (`app/launcher.keystore`) szándékosan a repóban van, hogy a frissítések felülírhassák egymást.
Csak saját telepítésre szolgál.

## Settings

Everything is edited on the TV: the gear icon on the home screen opens the launcher settings
(background set, favorites, status chips, quick actions, cameras, inputs, dashboard sections,
Home Assistant connection). Entities are chosen from a searchable list loaded from Home Assistant.

Settings › Home Assistant › Use Home Assistant is the main switch (`ha_on`). Off, the launcher
works on its own, for a TV in a home without Home Assistant: no dashboard, cameras, Today panel,
quick actions, chips, Now playing card, camera pop-ups or colour buttons. Their settings are kept
for when it is switched on again. Changing the Home button and installing updates from Settings
go through Home Assistant's ADB integration, so on such a TV use `home-button.bat` and
`update.bat` on the PC instead.

The settings are stored in `/sdcard/Android/data/hu.otthon.launcher/files/config.json`, which can
also be written over ADB. Main keys: `ha_url`, `ha_token`, `lat`, `lon`, `background`, `dim`,
`status`, `scenes`, `cameras` (lists of `{ "entity", "label" }`), `now_playing`, `inputs_hidden`,
`input_names`, and `dashboard`. The Home Assistant token and the network folder's password do not
stay in this file: the launcher moves them into its own storage (which other apps cannot read)
the next time it reads the settings, and writes the file without them. Writing them into the
file again (a new token over ADB, say) works the same way. The file is written whole or not at
all, and the last good one is kept as `config.json.bak`:

```json
"dashboard": {
  "weather": "weather.home",
  "sections": [
    { "title": "Pool", "hide_when": "input_boolean.pool_winterized",
      "items": [ { "entity": "switch.pool_pump", "label": "Pump" },
                 { "entity": "input_select.mode", "label": "Mode", "ro": true } ] }
  ]
}
```

Everything that can be switched from the TV is collected in a "Switches" block; the rest of each
section is shown as status tiles. A `device_tracker` of a car (in a section called Cars, or named
like one) becomes a card with the car's picture, and the entities listed after it, up to the next
tracker, are shown on that card. The picture is the tracker's own entity picture or an image
entity named after the car; `"picture": "image.my_car"` (or a camera, or a URL) on the tracker
item overrides it.

The dashboard draws every other entity as a tile: numbers with a curve of their last 24 hours,
percentages with a level bar, everything else with a badge that lights up while it is on, open or
home. The first entity of a section becomes a tall tile when it is a thermostat (a dial), a power
sensor (a big curve), a selector, an alarm panel or a vacuum.

On the dashboard, OK toggles switches, lights and helpers, runs scenes and scripts, and opens
statistics (history chart) for everything else; holding OK always opens statistics. `"ro": true`
makes an item display-only. A section with `hide_when` disappears while that entity is `on`.

- `background`: `asset:<name>` (photo sets bundled in the APK), `foto:<name>` (your own photos in
  `files/backgrounds/<name>/hajnal.jpg`, `nappal.jpg`, `alkony.jpg`, `ejszaka.jpg`), or the
  built-in illustrations `alpine` and `hills`. A set may hold several pictures per time of day
  (`hajnal.jpg`, `hajnal2.jpg`, `hajnal3.jpg` ...): they take turns, a new one every hour.

## Staying up

An error on the main thread that does not break a screen being opened or drawn (a Home Assistant
answer that went missing, a callback that did not expect it) is written down and the launcher
goes on; background work (`Bg.run`) never stops it either. A real crash is written down too, and
when a launcher screen was in front the launcher starts again by itself, at most three times in
ten minutes. A crash outside the app's code (the graphics driver running out of memory kills the
process outright) is noticed when the process starts again: a launcher screen was in front and
never left, so it is written down and the launcher comes back, within the same allowance. A TV
whose graphics memory runs out can be given plainer graphics with
`"lite_graphics": true` in its settings file (panels without the landscape, the critters at
quarter size); off by default, the 2 GB Sony copes with the full look. Everything goes to `files/crashes.log` (also under `Android/data/hu.otthon.launcher/
files/` for ADB); the last crash is shown on the home screen the next time it opens, and holding
OK on the gear button shows the log, so a TV without ADB or Home Assistant can show it too.

## Language

English by default; Settings › Appearance › Language switches the launcher to Hungarian
(`"language": "hu"` in config.json). The texts are written in English in the code; `Lang.kt` holds
their Hungarian, whole texts and patterns for texts with names and numbers in them. Every text
view the launcher makes (`label`) translates what is set on it, so a new English text simply
shows in English until its Hungarian is added there. Dates follow the language too.

## Background photos

The `matterhorn` and `city` sets live in `app/src/main/assets/backgrounds/` (fetched with `tools/fetch_backgrounds.py`) and come from Unsplash, under the Unsplash License:

| Phase | Photo |
|---|---|
| Dawn | Ilia Bronskiy – Matterhorn at sunrise, Zermatt (`7bYsjkMTqpw`) |
| Day | Luke Tanis – Matterhorn reflection, Zermatt (`qnPEmE8M1c4`) |
| Dusk | Marc Wieland – Swiss alpine lake at dusk (`1Kcgpq1ap9E`) |
| Night | Pascal Debrunner – Milky Way with Matterhorn (`xu9ck-3xTvI`) |

Landscapes set (several pictures per time of day, a new one every hour): Alessio Soggetti,
Boris Baldinger, Colin Lloyd (dawn); Christopher Politano, Tobias Keller, Josef Stepanek,
John O'Nolan (day); Simon Berger, Craig Melville, Alexander Slash (dusk); Benjamin Voros,
Vincent Guth, Colin Watts, Joshua Kettle (night).

City set: Ahmed Nishaath – Dalian at sunrise (`Z0JVfNqv2VE`), Adrian Newell – Houston skyline
(`oXoQ5nKUd5o`), Arto Marttinen – Tokyo at sunset (`6xh7H5tWj9c`), Andrea De Santis – London at
night (`bGWu7lcoZqY`).

To swap photos, edit `tools/backgrounds.txt` (set, phase, images.unsplash.com photo path), run `python tools/fetch_backgrounds.py` (needs Pillow) and commit the new files.
Your own photos can also be placed on the TV in `files/backgrounds/<name>/hajnal|nappal|alkony|ejszaka.jpg`.

## Music

On the "Now playing" card of the home screen OK plays or pauses, and holding OK opens the Music panel: every Roon zone in Home
Assistant with what it is playing (found with `integration_entities('roon')`, which needs an
administrator's token; otherwise the players that are playing right now are listed). Moving over a
zone shows it large on the left with previous / play-pause / next, which the remote's own media
keys also control; OK on a zone puts it on the home screen card. The card only shows while its
zone is playing or paused; the panel is also under Settings › Home screen › Music zones.

## Recommendations

Down on the bottom row of the home screen slides the page up: the bottom row stays at the top and
the streaming apps' rows fill the screen, "Continue watching" first, then the favorites' apps.
They are the preview channels and Watch Next programmes apps publish to the TV's listings provider,
the same ones the Google TV home shows; reading them needs `READ_TV_LISTINGS`, which the launcher
asks for the first time (Allow). OK opens a programme, Hold OK moves its row up or down (kept in
`reco_order`), Back or Up returns. A small mark at the bottom edge of the home screen shows there
is something below. Settings › Home screen › Recommendations switches them off, chooses the apps
and Continue watching, and resets the row order.

## Recommendations and the dashboard

Down from the bottom row slides up the recommendations; OK on the status capsule in the top
right corner opens Home Assistant's dashboard.

## Wallpapers from the NAS

Settings › Background › Wallpapers from a network folder: a Windows (SMB) share, e.g.
`\\192.168.2.2\NAS\Wallpapers` (computer, share, folder, user, password; the password stays
in the TV's config.json like the Home Assistant token). The picture set "Wallpapers (NAS)" then
shows whatever is in that folder (and two levels of subfolders): the launcher looks again every
half hour or on "Check the folder now", fetches only the picture on screen, keeps it on the TV,
and drops pictures that left the folder. A word in a picture's name or folder gives its part of
the day — hajnal / dawn / sunrise / reggel, nappal / day, alkony / dusk / sunset / este, éjszaka /
night — and others suit any time; karácsony / christmas / xmas / advent pictures are used in
Christmas mode instead of the Christmas illustrations. The pictures of a part of the day take
turns every hour; Settings › Background › Next wallpaper skips to the next one (any photo set).
Read with smbj (+ Bouncy Castle for NTLM's MD4).

## Design: Standard and Live

Settings › Background › Design › Home screen chooses between two looks of the home screen.
**Standard** keeps the apps' own banners as favourite tiles. **Live** makes the tiles taller
(about 4:3) and shows on each what the app recommends right now (from Android TV's
recommendation channels and Continue watching), with the app's banner as a small badge in the
bottom left corner. The tile under the cursor shows the title of the picture and moves on to
the app's next recommendation every 3.5 seconds with a soft cross-fade, like a Windows live
tile; the other tiles stand still, so only one thing moves at a time and the TV is not loaded.
An app that publishes nothing shows its banner large and darkened.

Both share one set of tokens in `Look`: small corner radii, the same everywhere — 6 dp (list
rows), 8 dp (buttons, pills, the status capsule, tiles, covers, cards) and 12 dp (every panel:
settings, quick settings, pop-ups, pickers, `Look.panel`); controls sit on frameless dark glass
(`Look.surface`); pictures grow 1.06× with a glow when focused, controls get the gliding light
fill; no rings, no frames and no lifted shadows.

## Look

The home screen's controls share one look, in the manner of Google TV: round icon buttons (Today,
TV settings, Launcher settings, All apps, Dashboard, Cameras), the statuses together in one glass
capsule with thin lines between them, and pills for the scenes and the inputs. All of them sit on
the same dark frosted glass. The cursor is the same everywhere on the home screen: the soft glow
of the app row, gliding from one control to the next; the focused control turns solid dark inside
its glow, with a thin light edge, and grows a little, and in a row (the round buttons, the scenes) its neighbours step
aside with a small spring, like the app tiles. The music card and the status capsule only grow. No name labels pop up under the buttons.
Lists elsewhere (settings, quick settings) keep the light fill. A glow is only cut where a list
really scrolls, and only in the direction it scrolls.

## Little visitors

Settings › Appearance › Bears and bunnies (Off / A few / Lots) lets a few drawn bears and bunnies
live on the home screen. They walk along the tops of the tiles, chips and buttons and rest now
and then. Bunnies hop from tile to tile, stop for a carrot, and jump up when the tile under them
gets focus. Bears waddle, wave, step across small gaps and, to reach another row, set up a
ladder, climb it and pack it away. They are drawn about thirty times a second and stop whenever
the home screen is covered.

Settings › Appearance › Christmas swaps them for reindeer with red collars and little bells
(at least a few, even with Bears and bunnies off). They trot along the tiles, leap across gaps
and nibble at the frost now and then. Every minute or two Santa's sleigh, loaded with presents
and pulled by two pairs of reindeer, flies across the screen and leaves a trail of gold dust;
halfway across Santa waves. The app tiles light up with a twinkling string of fairy lights
instead of the coloured glow.

Every minute or two one reindeer leaps off the edge of the screen and comes back with a little
decorated tree or a wrapped present on its back, finds a spot and sets it down on top of a tile.
At most four stay; when another one arrives the oldest fades away. Once in a few minutes one
finds a magic cookie, eats it and floats around in the air for a while, trailing glitter, then
glides back down.

The background becomes a Christmas landscape with gently falling snow, whatever background is
chosen: a snowy village with a lit tree on the square, a log cabin in the forest with a snowman,
and a frozen lake under the northern lights. They take turns, a new one every hour, and follow the
time of day like the other landscapes (pink dawn, blue day, violet dusk, moonlit night with warm
windows). They are drawn from data in `assets/winter/`, made by `tools/winter/gen.py`;
`python3 tools/winter/gen.py --preview out/` draws every scene at every time of day as PNGs.

## Music Assistant

The note button in the bottom row (next to Cameras) opens Music Assistant as pages, the way the
streaming apps work. Back goes back a page.

- **Library**, the full width: tabs (Home, Playlists, Albums, Artists, Radio) and search. Home
  has rows of covers (recently played albums, playlists, radio, the artists played most,
  recently added albums); the tabs are grids. Covers Music Assistant has no picture for get a
  gradient with the name on it.
- **The corner** of every page, one press up: a Now playing chip (small cover, the song, bouncing
  bars) that opens Now playing, and the player pill that picks where the music plays. It starts
  with the player last chosen on this TV, or a Music Assistant player in the TV's room. Switching
  player while music plays offers to move the music along (transfer queue).
- **An album or a playlist** opens on its own page: the cover, Play / Shuffle / Radio, and the
  songs (from Home Assistant's media browser). OK on a song plays the album from that song; hold
  OK for only this song, play next, add to the queue, radio, or another player. The song that is
  playing is marked.
- **An artist** opens on a page with Play / Shuffle / Radio and the artist's albums.
- **Now playing** is a page of its own: the cover large over a blurred copy of it, the song, the
  artist and album, the file's quality (e.g. DSF · 705.6 kHz), a progress bar, volume / previous
  / play / next, Go to album, and what comes next.
- Radio stations and songs in search results play at once; hold OK on any cover for play next,
  add to the queue, radio mode, or another player.

When nothing is chosen as the now-playing card in the settings, the home screen's card follows
the Music Assistant player last used here; hold OK on it to open Now playing. The remote's media
buttons control the player. The button hides itself when Home Assistant has no Music Assistant.
(With a Roon card chosen, holding OK on the card still opens the Roon zones.)

## Kids profile

Settings › Kids profile turns on a second profile for the kids: their name, a four-digit PIN,
and the apps they may use (YouTube Kids, Disney+ and Netflix to start with, if installed). Their
home screen has a big clock and a hello, the allowed apps on big tiles with candy-coloured frames
that bounce when the cursor arrives, and below them what those apps recommend; the reindeer or
bears walk on the big tiles too. There are no Home Assistant panels, cameras, settings or other
apps. The round avatar at the top right switches profiles: from the grown-ups' screen straight
into the kids profile, back only with the PIN (entered with the arrows, or the number buttons).
"When the TV wakes up" decides what greets whoever turns the TV on: "Who's watching?" (the
profiles as big round pictures; the grown-ups' one asks for the PIN), straight into the kids
profile, or the last profile. The PIN can also be kept in Home Assistant (`input_text.kids_tv_pin`,
a password field): every TV then uses it, and a PIN set on a TV is saved there.

The app lock (the same accessibility service as the colour buttons, switched on over ADB) sees
every app that comes to the front. In the kids profile anything else than the allowed apps,
opened by voice search, from another app or cast from a phone, and also the TV settings, the
Play Store and the stock home screens, sends the TV straight back to the kids screen with a
friendly note. System overlays (volume, keyboard, screensaver) are left alone.

Time: with Home Assistant's "Kids TV" helpers (`input_number.kids_tv_daily_limit`,
`input_number.kids_tv_minutes_today`, `input_number.kids_tv_extra_minutes`,
`input_datetime.kids_tv_bedtime`, `input_datetime.kids_tv_wake_up`, `input_boolean.kids_tv_allowed`)
the daily time, bedtime and wake-up are shared by every TV and set from the phone; every minute
spent in an allowed app is added to `kids_tv_minutes_today`. Without them the settings page has
its own daily time, bedtime and wake-up for this TV. When the time is over, before wake-up, after
bedtime or while "allowed" is off, the kids screen shows a sleepy moon with floating Zzz instead
of the apps, and ten minutes later (bedtime, time over) the TV goes to sleep.

## Colour buttons

Settings › Remote gives each of the remote's red, green, yellow and blue buttons a Home Assistant
action: a scene or script to run, a light, switch or helper to toggle, a button to press, or a
camera to pop up (with its name as the caption). A short message confirms the action.

By default the buttons work on the home screen. "Colour buttons work › In every app" switches on
`KeyService`, an accessibility service that sees the buttons before any app does; the launcher
asks Home Assistant's Android TV integration to enable it over ADB (other accessibility services
are kept). Only buttons that have an action are taken over, the rest still reach the app
(teletext, programme guide). Choosing "On the home screen" removes the service again.

## Amplifier volume

For an amplifier that only listens to infrared (the Musical Fidelity M6si500), the TV remote's
volume and mute buttons can work the amplifier instead of the TV, in every app, through an
infrared remote in Home Assistant (a Broadlink). Settings › Remote › Volume buttons: choose the
Broadlink's `remote.` entity, then teach it the amplifier remote's Volume +, Volume − and Mute
(Teach the Broadlink…, each row waits 15 s for the button). The commands are saved in the
Broadlink under the device `amplifier` as `volume_up`, `volume_down` and `mute`; commands
learnt earlier under other names can be used by editing `amp_volume` in config.json:

```json
"amp_volume": { "remote": "remote.broadlink", "device": "amplifier",
                "up": "volume_up", "down": "volume_down", "mute": "mute" }
```

Holding a button keeps stepping (about 7 steps a second); steps pressed while earlier ones are
still on their way are sent together with `num_repeats`. A small sign in the top right corner
shows the direction. Set the TV's optical output to a fixed level.

## Gear button and quick settings

Remotes without a gear button (the Sony's): Settings › Remote › a colour button › "Open the quick
settings" puts the panel on that button, in every app.

Google TV home answers the remote's gear button with its settings panel. With Google TV home
switched off, the launcher's `KeyService` takes the button and opens its own **quick settings**,
over whatever is on screen (a film keeps playing): the time, the profile (one press into the
kids' picture as a bubble to switch to their profile), made of the settings screen's parts.
TV, four tiles with what each is set to now: Inputs (listed in the panel), Network (the Wi-Fi's
name, or Ethernet; the name needs location access, also in the background, since the panel
opens from the button service: given over ADB), Sleep timer (off → 15 → 30 →
60 → 90 → 120 minutes; the TV goes to sleep through Home Assistant's ADB) and Running apps (the
apps with a tile that are running, the biggest first with their memory; OK brings one to the
front, the round ✕ beside it (or holding OK) closes it, Close all closes them all; listed and
closed over ADB). The round power button beside the profile
bubble offers Turn off (a complete shutdown, not sleep, so the optical output goes quiet and a
DAC that picks its input by itself lets go of the TV) and Restart. The remote's power button
starts the TV again. Home: Cameras, All lights of the TV's room (on or off together, "3 of 19 on") and the
lights chosen in Settings › Remote › Quick settings · lights (without a choice, the room's light
groups), each with its state; OK switches them. Camera alerts (beside Cameras) switches the
camera pop-ups on or off. Then Launcher settings and All TV settings, and at the bottom (the panel
scrolls) the last 24 hours' **notifications and camera alerts**, the newest four, with All
notifications for the rest: OK on an alert opens that camera with the recording of its detection,
OK on a notification or the round ✕ dismisses it, Clear all clears them. They are kept in
`files/inbox.json` on each TV; what came while the TV slept is taken from Home Assistant's
history (the sensors' and the notification helper's), so it is there too. There is no Picture or Sound: the TCL's own menus only open over a film, and from the
home screen only Android's generic page would come. The TCL does not let an app start the
screen saver either. The gear again or Back
closes it; it also closes after half a minute. The gear in the home screen's top right corner opens it too. The home screen has no TV-settings or profile
button any more: both are here. Settings › Remote ›
Gear button opens: Automatic (the panel while Google TV home is off), Quick settings (always),
Display & sound, All settings, or TV decides.

## Resolution

The menus of these TVs are drawn at 1920×1080 and stretched to the 4K panel; only films and
pictures in a player are 4K. The TCL's graphics plane is limited to 1920×1080
(`ro.surface_flinger.max_graphics_width`), so `wm size 3840x2160` would only enlarge everything,
not sharpen it: the launcher has no 4K switch. `restore-tv.bat` sets the TV's own 1080p and
density again if they were ever changed.

## The TV's own ADB

What only ADB may do (closing apps, turning the TV off, the sleep timer, giving the launcher its
permissions, installing updates) the launcher does itself on the TV's network ADB, connecting
from inside the TV to 127.0.0.1:5555 with a key of its own. The first time (once, on the home
screen, or from Settings › Home screen › This TV's own ADB) the TV asks "Allow USB debugging?":
tick Always allow, then Allow. Until then, and whenever it fails, Home Assistant's Android TV
(ADB) integration does it as before. Commands that outlive the launcher (its own update, a
restart) are started apart from the connection, so they finish when the launcher is replaced.

## Camera detections

On the camera page, in a camera's full-screen view, **▼** opens a strip of that camera's
detections from Frigate (newest first, older ones load as you go right): the picture, what was
seen and where, when, and how long. **OK** plays the recording full screen with its sound;
**◀ ▶** step to the newer / older detection, **OK** pauses, **▲ ▼** jump 10 seconds, **Back**
returns to the strip, and **▲** or **Back** there returns to the live picture.

The list comes from the Frigate integration of Home Assistant (`frigate/events/get` over the
WebSocket API); the camera's `camera_name` / `client_id` attributes tell which Frigate camera it
is. Recordings play from the integration's clip link, or failing that as a stream of the same
minutes from the recordings.

## Camera pop-ups

When one of the chosen sensors turns on (by default: Frigate seeing a person on any camera of the
camera wall except indoor ones), that camera appears in the bottom right corner for a while, over
whatever is playing (on a TV with little memory, such as the 2 GB Sony, not over the open quick
settings: there the alert just goes into their list, since it ran out of graphics memory with both
at once; a TV with more memory, such as the TCL, shows it over the panel too). While it is up, Back closes it, OK opens the camera full screen and any other
key just closes it (volume keys still work). A background service hears every change of the
sensors at once over Home Assistant's WebSocket (subscribe_entities), also while the TV is off,
for the quick settings' list, which collects the alerts even with the pop-ups off; while that line
is down it asks every two seconds (15 while the TV is off) and reconnects by itself. Settings › Cameras › Pop-ups (or the Camera
alerts tile of the quick settings) switches it off, sets how long it stays,
and edits the sensors. Drawing over other apps is allowed over ADB:
`appops set hu.otthon.launcher SYSTEM_ALERT_WINDOW allow`. The launcher asks Home Assistant's ADB
for it by itself (at most once an hour) whenever it is missing, and `update.bat` gives it on every
install from the PC, so a fresh install on a new TV does not stay without pop-ups.

Settings › Cameras › Live video plays the full-screen view and the pop-ups as video from
Frigate's RTSP restream (`rtsp://{host}:8554/{camera}` by default, editable), with Android's own
player; the Frigate add-on has to publish its port 8554. A stream that does not start within ten
seconds falls back to the still pictures.

## Carrying a TV's settings across a reinstall

When Android will not take a new launcher as an update (its signing key changed), the settings
are carried over by hand: `am broadcast -n hu.otthon.launcher/.CarryReceiver -a
hu.otthon.launcher.EXPORT` (ADB only) writes `carry.json` into the launcher's folder: the
settings with their token and password, and the app's own stored values (the kids' profile, the
music player…; not this install's ADB key). Copy it away, uninstall, install the new launcher,
put the file back: the launcher takes it in the first time it reads its settings and deletes it.

## The TV's state in Home Assistant

The launcher tells Home Assistant what the TV is doing, as a sensor of its own,
`sensor.<the TV's name>_launcher` (the name from Android's device name, e.g.
`sensor.living_room_tv_launcher`; `"state_entity"` in the settings file sets another):
`off`, `screensaver`, `home` (a launcher screen in front), `menu` (the TV's settings, the app
store, the search) or `watching` (any other app). The button service sees every app that comes to
the front; the main process sends the state on each change and every five minutes (Home
Assistant forgets such a state when it restarts). Attributes: `app`, `app_name`, `version`,
`model`, `android`, `low_memory`, `crashes_24h`, `last_crash`, `notifications`, `updated`.
`"report_state": false` switches it off.

## Notifications

Home Assistant can show a short message on the TV, as a card in the top right corner over
whatever is on. The card takes no keys, so the remote keeps working in the app, and it goes away
by itself after a few seconds (Settings › Home Assistant › Notifications: on/off, how long, a test
card). It uses the same background service and the same "draw over other apps" permission as the
camera pop-ups, and only runs while the TV is on.

Home Assistant writes the message into a text helper, `input_text.tv_notification` by default
(Settings › Home Assistant › Notifications › Text helper), as

    <sent, unix seconds>|<icon>|<level>|<title>|<text>

for example `1794651277|garage|warn|Garage gate open|Open for 15 minutes`. The icon is a
drawable name without `ic_` (`garage`, `storm`, `washer`, `bin`, `bell`, `door`, `car` ...;
unknown names show a house), the level `info`, `warn` or `alert` colours it. A message older than
two minutes is not shown. A text helper holds at most 255 characters, so keep it short.

## Today

The calendar button next to the settings opens a panel with what needs a look at home (open doors
and gates, unlocked cars, low fuel or batteries among the dashboard items), the next twelve hours
of weather, the calendars of the coming week, the to-do lists (OK marks an item done) and who is
at home; sunrise, sunset and whether it is a workday sit under the clock.

## Updates on the TV

Settings › About › Check for updates looks at the latest GitHub release; OK downloads it and has
Home Assistant install it over ADB (copied to `/data/local/tmp` first: the package manager may not
read the app's own folder).

Home Assistant (or a PC) can start an update on a TV over ADB, without touching the TV:

    adb shell am broadcast -n hu.otthon.launcher/.UpdateReceiver -a hu.otthon.launcher.UPDATE

The launcher checks GitHub, downloads a newer build and has Home Assistant install it. Only the
TV's shell can send this (the receiver requires the `DUMP` permission). `--ez force true`
installs the latest release even when it is not newer. The Home Assistant script
"Update TV launchers" sends it to every TV. The home screen checks every six hours and puts a dot on the
settings button when there is something newer.

## Making it the default home app

Settings › Home screen › Home button opens. Android TV only allows this over ADB, so the launcher
asks Home Assistant's Android TV (ADB) integration to run the commands on the TV:

    pm disable-user --user 0 com.google.android.tvlauncher     # hand Home to this launcher
    cmd package set-home-activity hu.otthon.launcher/.HomeActivity

    pm enable com.google.android.tvlauncher                    # give it back to Google TV home

From a Windows PC, `home-button.bat` in the project folder does the same over ADB on its own
(no Home Assistant needed): run it to see the current choice and pick one, or pass `launcher` or
`google` (and optionally the TV's address).

Google's home app has a higher priority than any installed launcher, so the TV always picks it
while it is switched on; that is why it is switched off rather than just outranked. It stays
installed. The TV's player in Home Assistant is found by the TV's address, or set it as
`"adb_entity"` in the settings.

## Building on a Windows PC

Needs JDK 17. One-time setup, in PowerShell (installs Git if missing, the Android SDK
command-line tools, adb, platform 34 and build-tools 34, and clones this project to
`%USERPROFILE%\bhevesiatvlauncher`):

    irm https://raw.githubusercontent.com/bhevesi2/bhevesiatvlauncher/main/tools/setup-windows.ps1 | iex

After that, `update.bat` in the project folder pulls the newest source, builds the APK and
installs it on the TV over ADB (`update.bat <address>` for another TV, `update.bat build` to only
build). The first time, the TV asks whether to allow debugging from the PC.

Every build is signed with the key in `app/launcher.keystore` and gets a time-based version
code, so PC builds and GitHub builds install over each other.
