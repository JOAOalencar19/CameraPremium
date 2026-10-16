#!/usr/bin/env python3
"""Fails if app bytecode uses Android framework members that are not public SDK API.

The offline build compiles against a full framework jar (robolectric android-all), which also
contains @hide methods. Calling one compiles fine but crashes on a real phone with
NoSuchMethodError. This checks every android.* method/field reference against the official
API signature file (AOSP core/api/current.txt), walking superclasses and interfaces.

Usage: check-public-api.py <classes-dir> <current.txt>
"""
import re, subprocess, sys, pathlib

api = {}       # 'android/util/Rational' -> {'members': set(), 'supers': [..]}
pkg = None
stack = []     # nested class names
cls = None
ANNOT = re.compile(r"@[\w.]+(\((?:[^()]|\([^()]*\))*\))?\s*")
for line in open(sys.argv[2], encoding="utf-8"):
    line = ANNOT.sub("", line)
    m = re.match(r"^package ([\w.]+) \{", line)
    if m: pkg = m.group(1).replace(".", "/"); continue
    m = re.match(r"^  (?:[\w@.()\"=, ]+ )?(?:class|interface|enum|@interface) ([\w.]+)(?:<.*?>)?(.*)\{", line)
    if m and pkg:
        name = m.group(1).replace(".", "$")
        cls = pkg + "/" + name
        rest = m.group(2)
        supers = []
        for kw in ("extends", "implements"):
            mm = re.search(kw + r" ([\w.<>?, ]+?)(?: implements| \{|$)", rest + " {")
            if mm:
                for s in re.split(r",\s*", re.sub(r"<[^<>]*>", "", re.sub(r"<[^<>]*>", "", mm.group(1)))):
                    s = s.strip()
                    if s: supers.append(s)
        api[cls] = {"members": set(), "supers": supers}
        continue
    if cls is None: continue
    m = re.match(r"^    (ctor|method|field|enum_constant|property) .*?([\w$]+)\s*[(;=]", line)
    if m:
        kind, name = m.group(1), m.group(2)
        if kind == "ctor": name = "<init>"
        api[cls]["members"].add(name)
        if kind == "property": api[cls]["members"].add(name)

simple = {}
for c in api:
    simple.setdefault(c.split("/")[-1].replace("$", "."), []).append(c)

def resolve_super(s, ctx):
    s = s.replace(".", "/")
    if s in api: return s
    # simple or nested name relative to package
    p = ctx.rsplit("/", 1)[0]
    cand = p + "/" + s.split("/")[-1].replace(".", "$")
    if cand in api: return cand
    # dotted fqcn with nested class: android/view/View/OnClickListener -> View$OnClickListener
    parts = s.split("/")
    for i in range(len(parts) - 1, 0, -1):
        c = "/".join(parts[:i]) + "/" + "$".join(parts[i:])
        if c in api: return c
    return None

_jcache = {}
def java_members(fq):
    """Public members of a java.* type, from the JDK (inherited ones included via javap -public)."""
    fq = re.sub(r"<.*", "", fq)
    if fq not in _jcache:
        names = set()
        todo = [fq]
        while todo:
            t = todo.pop()
            txt = subprocess.run(["javap", "-public", t], capture_output=True, text=True).stdout
            for m in re.finditer(r"([\w$]+)\(", txt): names.add(m.group(1))
            hdr = re.search(r"(?:class|interface) [\w.$]+(?:<.*?>)? (?:extends ([\w.$, <>?]+?))?(?: implements ([\w.$, <>?]+))? ?\{", txt)
            if hdr:
                for g in hdr.groups():
                    if g:
                        for sup in re.split(r",\s*", re.sub(r"<[^<>]*>", "", g)):
                            if sup.strip(): todo.append(sup.strip())
        _jcache[fq] = names | {"<init>"}
    return _jcache[fq]

def has(c, name, seen=None):
    """True if found; also True when the chain reaches a java.* type we cannot inspect."""
    seen = seen or set()
    if c in seen or c not in api: return False
    seen.add(c)
    if name in api[c]["members"]: return True
    for s in api[c]["supers"]:
        r = resolve_super(s, c)
        if r is None and s.startswith("java."):
            if name in java_members(s): return True
            continue
        if r and has(r, name, seen): return True
    return False

classes = [str(p) for p in pathlib.Path(sys.argv[1]).rglob("*.class")]
out = ""
for i in range(0, len(classes), 300):
    out += subprocess.run(["javap", "-c", "-p"] + classes[i:i + 300], capture_output=True, text=True).stdout
refs = set(re.findall(r"(?:Method|InterfaceMethod|Field) (android/[\w/$]+)\.([\w$<>\"]+):", out))
bad = []
for owner, name in sorted(refs):
    name = name.strip('"')
    if owner not in api:
        bad.append(f"{owner} (classe nao publica)")
        continue
    if name in ("<clinit>",): continue
    if not has(owner, name) and not has("java/lang/Object", name):
        # members inherited from java.* (Object, Number, Enum...) are fine
        if name in ("toString", "hashCode", "equals", "getClass", "wait", "notify", "notifyAll", "clone", "values", "valueOf", "ordinal", "name", "compareTo"):
            continue
        bad.append(f"{owner}.{name}")
if bad:
    print("ERRO: uso de API nao publica do Android (quebra em aparelho real):")
    for b in bad: print("  ", b)
    sys.exit(1)
print(f"ok: {len(refs)} referencias ao framework, todas na API publica")
