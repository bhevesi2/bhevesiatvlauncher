#!/usr/bin/env python3
"""Downloads the bundled background photo sets (Unsplash License) and crops them to 1920x1080.
Each line of tools/backgrounds.txt: <set> <phase> <images.unsplash.com photo path>"""
import io, os, sys, urllib.request
from PIL import Image, ImageOps

OUT = "app/src/main/assets/backgrounds"
W, H = 1920, 1080

for line in open("tools/backgrounds.txt"):
    parts = line.split()
    if len(parts) != 3:
        continue
    s, phase, pid = parts
    url = f"https://images.unsplash.com/{pid}?fm=jpg&q=90&w=2400&fit=max"
    data = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=60).read()
    img = ImageOps.exif_transpose(Image.open(io.BytesIO(data))).convert("RGB")
    img = ImageOps.fit(img, (W, H), method=Image.LANCZOS, centering=(0.5, 0.5))
    os.makedirs(f"{OUT}/{s}", exist_ok=True)
    path = f"{OUT}/{s}/{phase}.jpg"
    img.save(path, "JPEG", quality=84, optimize=True, progressive=True)
    print(f"{path} <- {pid} ({os.path.getsize(path)} bytes)")
