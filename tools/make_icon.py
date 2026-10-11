#!/usr/bin/env python3
"""يولّد الأيقونة الأصلية من هندسة واحدة: vector للتكيّفية + monochrome + PNG لكل الكثافات + 512px."""
import os
from PIL import Image, ImageDraw
ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
BG, CREAM, GOLD = "#22305A", "#F3F4F1", "#F5D547"
V = 108.0
LEFT = [(53, 80), (27, 71), (27, 41), (53, 49)]
RIGHT = [(55, 80), (81, 71), (81, 41), (55, 49)]
SUN = (54, 31, 7)            # cx, cy, r
ARC = (42, 19, 66, 43)       # bbox لنصف الدائرة العلوي
def path(pts): return "M" + " L".join(f"{x},{y}" for x, y in pts) + " Z"

def vector(fg_color_cream, fg_color_gold, mono=False):
    cx, cy, r = SUN
    return f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="{fg_color_cream}" android:pathData="{path(LEFT)}"/>
    <path android:fillColor="{fg_color_cream}" android:pathData="{path(RIGHT)}"/>
    <path android:fillColor="{fg_color_gold}" android:pathData="M{cx-r},{cy} a{r},{r} 0 1,0 {2*r},0 a{r},{r} 0 1,0 {-2*r},0 Z"/>
    <path android:strokeColor="{fg_color_gold}" android:strokeWidth="2.4" android:strokeLineCap="round" android:pathData="M42,31 A12,12 0 0 1 66,31"/>
</vector>
'''

def render(size, shape):
    S = size * 4
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = S / V
    if shape == "square":
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=BG)
    elif shape == "round":
        d.ellipse([0, 0, S - 1, S - 1], fill=BG)
    else:
        d.rectangle([0, 0, S, S], fill=BG)
    sc = 1.18  # تكبير الرمز في الأيقونات القديمة لأنها بلا منطقة أمان
    def T(p): return ((54 + (p[0] - 54) * sc) * k, (54 + (p[1] - 54) * sc) * k)
    for poly in (LEFT, RIGHT): d.polygon([T(p) for p in poly], fill=CREAM)
    cx, cy, r = SUN
    c = T((cx, cy)); rr = r * sc * k
    d.ellipse([c[0] - rr, c[1] - rr, c[0] + rr, c[1] + rr], fill=GOLD)
    a0 = T((ARC[0], ARC[1])); a1 = T((ARC[2], ARC[3]))
    d.arc([a0[0], a0[1] + 0, a1[0], a1[1] + 0], 180, 360, fill=GOLD, width=int(2.4 * sc * k))
    return img.resize((size, size), Image.LANCZOS)

def w(rel, text):
    p = os.path.join(ROOT, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(text)

w("drawable/ic_launcher_foreground.xml", vector(CREAM, GOLD))
w("drawable/ic_launcher_monochrome.xml", vector("#000000", "#000000"))
w("mipmap-anydpi-v26/ic_launcher.xml", '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/icon_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
''')
w("mipmap-anydpi-v26/ic_launcher_round.xml", open(os.path.join(ROOT, "mipmap-anydpi-v26/ic_launcher.xml"), encoding="utf-8").read())
for dens, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    for name, shape in (("ic_launcher", "square"), ("ic_launcher_round", "round")):
        p = os.path.join(ROOT, f"mipmap-{dens}", f"{name}.png"); os.makedirs(os.path.dirname(p), exist_ok=True)
        render(px, shape).save(p)
os.makedirs(os.path.join(ROOT, "..", "..", "..", "store"), exist_ok=True)
render(512, "square").save(os.path.join(ROOT, "..", "..", "..", "store", "play_store_icon_512.png"))
print("icon done")
