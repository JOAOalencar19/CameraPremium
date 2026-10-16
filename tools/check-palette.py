#!/usr/bin/env python3
"""Fails if the UI uses any color outside the monochrome palette.

Allowed: black #000000, white #FFFFFF, #1A1A1A, #808080, #D0D0D0 (any alpha), defined once in
ui/Theme.kt. Kotlin code may not create colors directly (Color.rgb/argb/parseColor, hex ints);
XML resources may only use the palette values.
Usage: check-palette.py <app/src/main>
"""
import re, sys, pathlib
PALETTE = {"000000", "FFFFFF", "1A1A1A", "808080", "D0D0D0"}
root = pathlib.Path(sys.argv[1])
bad = []
for f in root.rglob("*.kt"):
    if f.name == "Theme.kt":
        for m in re.finditer(r"0x([0-9A-Fa-f]{2})([0-9A-Fa-f]{6})", f.read_text()):
            if m.group(2).upper() not in PALETTE: bad.append(f"{f}: {m.group(0)}")
        continue
    for i, line in enumerate(f.read_text().splitlines(), 1):
        if re.search(r"Color\.(rgb|argb|parseColor|valueOf|RED|GREEN|BLUE|YELLOW|CYAN|MAGENTA|GRAY|LTGRAY|DKGRAY)\b", line) or \
           re.search(r"\b0x[0-9A-Fa-f]{8}\b", line) and ("color" in line.lower() or "Color" in line):
            bad.append(f"{f}:{i}: {line.strip()}")
for f in root.rglob("*.xml"):
    for i, line in enumerate(f.read_text().splitlines(), 1):
        for m in re.finditer(r"#([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6})\b", line):
            v = m.group(1).upper()[-6:]
            if v not in PALETTE: bad.append(f"{f}:{i}: {m.group(0)}")
if bad:
    print("ERRO: cores fora da paleta monocromatica:")
    for b in bad: print("  ", b)
    sys.exit(1)
print("ok: interface usa apenas preto, branco, cinzas e transparencias")
