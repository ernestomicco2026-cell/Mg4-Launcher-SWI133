#!/usr/bin/env python3
"""Genera app/res/drawable/ic_cam360.xml: la scritta "360°" come icona vettoriale (0.3.2, punto 7c).

Il testo diventa un tracciato pieno (bianco, colorato dal launcher come le altre icone), centrato in un quadrato
48×48 e largo quasi quanto l'icona, così resta grande e leggibile nella schermata App e ovunque si usi l'icona 360.
Uso: python3 tools/gen_icon360.py [font]   (predefinito: Inter SemiBold, simile al sans-serif-medium dell'auto)
"""
import os
import sys

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

TEXT = "360°"
VIEW = 48.0
MAX_W, MAX_H = 46.0, 22.0


def main():
    font_path = sys.argv[1] if len(sys.argv) > 1 else "/usr/share/fonts/opentype/inter/Inter-SemiBold.otf"
    font = TTFont(font_path)
    gs = font.getGlyphSet()
    cmap = font.getBestCmap()
    hmtx = font["hmtx"]
    # posizioni dei caratteri (con un po' meno spazio tra le cifre, come il testo della barra)
    track = -0.02 * font["head"].unitsPerEm
    glyphs, x = [], 0.0
    for ch in TEXT:
        name = cmap[ord(ch)]
        glyphs.append((name, x))
        x += hmtx[name][0] + track
    # ingombro reale dei tracciati
    bp = BoundsPen(gs)
    for name, gx in glyphs:
        gs[name].draw(TransformPen(bp, (1, 0, 0, 1, gx, 0)))
    x0, y0, x1, y1 = bp.bounds
    s = min(MAX_W / (x1 - x0), MAX_H / (y1 - y0))
    ox = (VIEW - (x1 - x0) * s) / 2 - x0 * s
    oy = (VIEW + (y1 - y0) * s) / 2 + y0 * s   # asse y verso il basso
    pen = SVGPathPen(gs, ntos=lambda v: ("%.2f" % v).rstrip("0").rstrip("."))
    for name, gx in glyphs:
        gs[name].draw(TransformPen(pen, (s, 0, 0, -s, ox + gx * s, oy)))
    path = pen.getCommands()
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "res", "drawable", "ic_cam360.xml")
    with open(root, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<!-- generato da tools/gen_icon360.py: scritta "360°" (0.3.2) -->\n'
                '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:width="24dp" android:height="24dp"\n'
                '    android:viewportWidth="48" android:viewportHeight="48">\n'
                '    <path android:fillColor="#FFFFFFFF" android:pathData="%s"/>\n'
                '</vector>\n' % path)
    print("ic_cam360.xml: %d caratteri di tracciato" % len(path))


if __name__ == "__main__":
    main()
