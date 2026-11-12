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

The dashboard draws every entity as a tile: numbers with a curve of their last 24 hours,
percentages with a level bar, everything else with a badge that lights up while it is on, open or
home. The first entity of a section becomes a tall tile when it is a thermostat (a dial), a power
sensor (a big curve), a selector, an alarm panel or a vacuum.

On the dashboard, OK toggles switches, lights and helpers, runs scenes and scripts, and opens
statistics (history chart) for everything else; holding OK always opens statistics. `"ro": true`
makes an item display-only. A section with `hide_when` disappears while that entity is `on`.

- `background`: `asset:<name>` (photo sets bundled in the APK), `foto:<name>` (your own photos in
  `files/backgrounds/<name>/hajnal.jpg`, `nappal.jpg`, `alkony.jpg`, `ejszaka.jpg`), or the
  built-in illustrations `alpine` and `hills`.

## Background photos

The `matterhorn` and `city` sets live in `app/src/main/assets/backgrounds/` (fetched with `tools/fetch_backgrounds.py`) and come from Unsplash, under the Unsplash License:

| Phase | Photo |
|---|---|
| Dawn | Ilia Bronskiy – Matterhorn at sunrise, Zermatt (`7bYsjkMTqpw`) |
| Day | Luke Tanis – Matterhorn reflection, Zermatt (`qnPEmE8M1c4`) |
| Dusk | Marc Wieland – Swiss alpine lake at dusk (`1Kcgpq1ap9E`) |
| Night | Pascal Debrunner – Milky Way with Matterhorn (`xu9ck-3xTvI`) |

City set: Ahmed Nishaath – Dalian at sunrise (`Z0JVfNqv2VE`), Adrian Newell – Houston skyline
(`oXoQ5nKUd5o`), Arto Marttinen – Tokyo at sunset (`6xh7H5tWj9c`), Andrea De Santis – London at
night (`bGWu7lcoZqY`).

To swap photos, edit `tools/backgrounds.txt` (set, phase, images.unsplash.com photo path), run `python tools/fetch_backgrounds.py` (needs Pillow) and commit the new files.
Your own photos can also be placed on the TV in `files/backgrounds/<name>/hajnal|nappal|alkony|ejszaka.jpg`.

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
