#!/usr/bin/env python3
"""Downloads the bundled background photo sets (Unsplash License) and crops them to 1920x1080.

Each line of tools/backgrounds.txt: <set> <phase> <photo>[,<photo>...]
where <photo> is an images.unsplash.com photo path. When several are listed, the first one
that is landscape-shaped is used, so a portrait photo is never squeezed into a wide crop.

A set may have several lines for the same phase: they become <phase>.jpg, <phase>2.jpg,
<phase>3.jpg ... and the launcher shows them in turn. A picture that is already there is
left alone; delete the file to fetch it again."""
import io, os, urllib.request
from PIL import Image, ImageOps

OUT = "app/src/main/assets/backgrounds"
W, H = 1920, 1080


def fetch(pid):
    url = f"https://images.unsplash.com/{pid}?fm=jpg&q=90&w=2400&fit=max"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    data = urllib.request.urlopen(req, timeout=60).read()
    return ImageOps.exif_transpose(Image.open(io.BytesIO(data))).convert("RGB")


seen = {}
for line in open("tools/backgrounds.txt"):
    parts = line.split()
    if len(parts) != 3 or parts[0].startswith("#"):
        continue
    s, phase, candidates = parts
    n = seen[(s, phase)] = seen.get((s, phase), 0) + 1
    path = f"{OUT}/{s}/{phase}{'' if n == 1 else n}.jpg"
    if os.path.exists(path):
        print(f"{path}: already there")
        continue
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
    img.save(path, "JPEG", quality=84, optimize=True, progressive=True)
    print(f"{path} <- {pid} ({os.path.getsize(path)} bytes)")
