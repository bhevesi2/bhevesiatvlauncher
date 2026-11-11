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

## Beállítás

A launcher a `/sdcard/Android/data/hu.otthon.launcher/files/config.json` fájlt olvassa (ADB-vel feltölthető).
Minden mező opcionális:

```json
{
  "ha_url": "http://192.168.x.x:8123",
  "ha_token": "<long-lived access token>",
  "lat": 47.63,
  "lon": 17.73,
  "background": "alpesi",
  "dim": 0.7,
  "favorites": ["com.netflix.ninja", "com.google.android.youtube.tv"],
  "status": [
    { "entity": "sensor.kulso_homerseklet", "label": "Kint", "icon": "cloud" },
    { "entity": "sensor.nappali_homerseklet", "label": "Nappali", "icon": "home" },
    { "entity": "lock.bejarat", "label": "Bejárat", "icon": "lock" }
  ],
  "now_playing": "media_player.nappali",
  "now_playing_label": "Roon · Nappali hi-fi",
  "scenes": [
    { "entity": "scene.mozi", "label": "Mozi", "icon": "film" },
    { "entity": "light.nappali", "label": "Fények", "icon": "bulb" }
  ]
}
```

- `background`: `alpesi`, `domb` (beépített vektoros tájképek) vagy `foto:<név>`. Az utóbbi a
  `files/backgrounds/<név>/hajnal.jpg`, `nappal.jpg`, `alkony.jpg`, `ejszaka.jpg` képeket használja.
- `favorites`: a kezdeti kedvenclista. A távirányítóval végzett módosítások a launcherben tárolódnak.

## Background photos

The `matterhorn` set is downloaded at build time (`tools/fetch_backgrounds.sh`) from Unsplash, under the Unsplash License:

| Phase | Photo |
|---|---|
| Dawn | Nadine Marfurt – Fluhalp, Zermatt (`FLfNL6XuOEM`) |
| Day | Tomi Blasic – Riffelsee, Zermatt (`tDbCVk4bffM`) |
| Dusk | Marc Wieland – Swiss alpine lake at dusk (`1Kcgpq1ap9E`) |
| Night | Pascal Debrunner – Milky Way with Matterhorn (`xu9ck-3xTvI`) |

To swap photos, edit `tools/backgrounds.txt` (set, phase, Unsplash photo id).
Your own photos can also be placed on the TV in `files/backgrounds/<name>/hajnal|nappal|alkony|ejszaka.jpg`.
