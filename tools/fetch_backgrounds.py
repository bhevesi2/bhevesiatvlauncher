#!/usr/bin/env python3
"""Downloads the bundled background photo sets (Unsplash License) and crops them to 1920x1080.

Each line of tools/backgrounds.txt: <set> <phase> <photo>[,<photo>...]
where <photo> is an images.unsplash.com photo path. When several are listed, the first one
that is landscape-shaped is used, so a portrait photo is never squeezed into a wide crop."""
import io, os, urllib.request
from PIL import Image, ImageOps

OUT = "app/src/main/assets/backgrounds"
W, H = 1920, 1080


def fetch(pid):
    url = f"https://images.unsplash.com/{pid}?fm=jpg&q=90&w=2400&fit=max"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    data = urllib.request.urlopen(req, timeout=60).read()
    return ImageOps.exif_transpose(Image.open(io.BytesIO(data))).convert("RGB")


for line in open("tools/backgrounds.txt"):
    parts = line.split()
    if len(parts) != 3:
        continue
    s, phase, candidates = parts
    chosen = None
    first = None
    for pid in candidates.split(","):
        try:
            img = fetch(pid)
        except Exception as e:
            print(f"  {pid}: {e}")
            continue
        print(f"  {pid}: {img.size[0]}x{img.size[1]}")
        if first is None:
            first = (pid, img)
        if img.size[0] >= img.size[1] * 1.3:
            chosen = (pid, img)
            break
    chosen = chosen or first
    if chosen is None:
        raise SystemExit(f"no usable photo for {s}/{phase}")
    pid, img = chosen
    img = ImageOps.fit(img, (W, H), method=Image.LANCZOS, centering=(0.5, 0.5))
    os.makedirs(f"{OUT}/{s}", exist_ok=True)
    path = f"{OUT}/{s}/{phase}.jpg"
    img.save(path, "JPEG", quality=84, optimize=True, progressive=True)
    print(f"{path} <- {pid} ({os.path.getsize(path)} bytes)")
