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
`input_names`, and `dashboard`:

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

## Colour buttons

Settings › Remote gives each of the remote's red, green, yellow and blue buttons a Home Assistant
action: a scene or script to run, a light, switch or helper to toggle, a button to press, or a
camera to pop up (with its name as the caption). A short message confirms the action.

By default the buttons work on the home screen. "Colour buttons work › In every app" switches on
`KeyService`, an accessibility service that sees the buttons before any app does; the launcher
asks Home Assistant's Android TV integration to enable it over ADB (other accessibility services
are kept). Only buttons that have an action are taken over, the rest still reach the app
(teletext, programme guide). Choosing "On the home screen" removes the service again.

## Camera pop-ups

When one of the chosen sensors turns on (by default: Frigate seeing a person on any camera of the
camera wall except indoor ones), that camera appears in the bottom right corner for a while, over
whatever is playing. While it is up, Back closes it, OK opens the camera full screen and any other
key just closes it (volume keys still work). A background service checks the sensors every two
seconds while the TV is on. Settings › Cameras › Pop-ups switches it off, sets how long it stays,
and edits the sensors. Drawing over other apps is allowed over ADB:
`appops set hu.otthon.launcher SYSTEM_ALERT_WINDOW allow` (the settings do it through Home Assistant).

Settings › Cameras › Live video plays the full-screen view and the pop-ups as video from
Frigate's RTSP restream (`rtsp://{host}:8554/{camera}` by default, editable), with Android's own
player; the Frigate add-on has to publish its port 8554. A stream that does not start within ten
seconds falls back to the still pictures.

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
