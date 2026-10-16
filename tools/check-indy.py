#!/usr/bin/env python3
"""Fails if app bytecode calls a kotlin-stdlib method whose body uses invokedynamic.

ART has no LambdaMetafactory and the offline build uses `dx` (no desugaring), so such a call
would crash at runtime. Usage: check-indy.py <app-classes-dir> <stdlib-classes-dir>
"""
import re, subprocess, sys, pathlib

def javap(paths):
    out = []
    paths = [str(p) for p in paths]
    for i in range(0, len(paths), 300):
        out.append(subprocess.run(["javap", "-c", "-p"] + paths[i:i + 300], capture_output=True, text=True).stdout)
    return "\n".join(out)

app, std = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
bad = set()  # (facade-ish class, method)
for text in [javap(std.rglob("*.class"))]:
    cls = None; meth = None
    for line in text.splitlines():
        m = re.match(r"^(?:public |final |abstract |private |protected |static )*(?:class|interface) ([\w.$]+)", line)
        if m: cls = m.group(1).replace(".", "/"); continue
        m = re.match(r"^  \S.*?([\w$<>]+)\(", line)
        if m: meth = m.group(1); continue
        if "invokedynamic" in line and cls and meth:
            # multifile facades: kotlin/collections/CollectionsKt___CollectionsKt -> CollectionsKt
            facade = re.sub(r"__\w+$", "", cls)
            bad.add((facade, meth)); bad.add((cls, meth))
refs = set(re.findall(r"Method (kotlin/[\w/$]+)\.([\w$<>]+)", javap(app.rglob("*.class"))))
hits = sorted(r for r in refs if r in bad)
if hits:
    print("ERRO: chamadas a metodos do stdlib com invokedynamic:")
    for c, m in hits: print("  ", c + "." + m)
    sys.exit(1)
print(f"ok: {len(refs)} metodos do stdlib referenciados, nenhum usa invokedynamic")
