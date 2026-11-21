import os
import re
from lxml import etree

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PROJECT = ROOT
MODULES = ("agent", "controller")
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
REF_RE = re.compile(r"@(string|color|drawable|layout|style|xml|mipmap)/([A-Za-z0-9_.]+)")
R_RE = re.compile(r"\bR\.(string|color|drawable|layout|style|xml|mipmap)\.([A-Za-z0-9_]+)")
BINDING_RE = re.compile(r"\bbinding\.([A-Za-z_][A-Za-z0-9_]*)")
BINDING_SPECIAL = {"root", "isInitialized"}

errors = []
xml_count = 0
for base, _dirs, files in os.walk(PROJECT):
    for name in files:
        if not name.endswith(".xml"):
            continue
        path = os.path.join(base, name)
        xml_count += 1
        try:
            etree.parse(path)
        except Exception as exc:
            errors.append(f"XML parse error {path}: {exc}")
print(f"Parsed {xml_count} XML files; parse errors: {len(errors)}")
for err in errors:
    print(" ", err)

for module in MODULES:
    src_main = os.path.join(PROJECT, module, "src", "main")
    res = os.path.join(src_main, "res")
    defined = {key: set() for key in ("string", "color", "drawable", "layout", "style", "xml", "mipmap")}
    for base, _dirs, files in os.walk(res):
        rel = os.path.relpath(base, res).replace("\\", "/")
        kind = rel.split("/")[0] if rel != "." else ""
        for name in files:
            if name.endswith(".xml") and kind in ("layout", "drawable", "xml", "mipmap"):
                defined[kind].add(name[:-4])
    values_dir = os.path.join(res, "values")
    for name in os.listdir(values_dir):
        if not name.endswith(".xml"):
            continue
        tree = etree.parse(os.path.join(values_dir, name))
        for element in tree.getroot():
            if not isinstance(element.tag, str):
                continue
            if element.tag in ("string", "color", "style"):
                defined[element.tag].add(element.get("name"))

    ids = set()
    layout_dir = os.path.join(res, "layout")
    for name in os.listdir(layout_dir):
        if not name.endswith(".xml"):
            continue
        tree = etree.parse(os.path.join(layout_dir, name))
        for element in tree.iter():
            value = element.get(ANDROID_NS + "id")
            if value and value.startswith("@+id/"):
                ids.add(value.split("/", 1)[1])

    refs = {key: set() for key in defined}
    binding_refs = set()
    for base, _dirs, files in os.walk(src_main):
        for name in files:
            if not name.endswith((".xml", ".kt")):
                continue
            path = os.path.join(base, name)
            text = open(path, encoding="utf-8").read()
            for match in REF_RE.finditer(text):
                refs[match.group(1)].add((match.group(2), path))
            for match in R_RE.finditer(text):
                refs[match.group(1)].add((match.group(2), path))
            if name.endswith(".kt"):
                for match in BINDING_RE.finditer(text):
                    binding_refs.add(match.group(1))

    print(f"--- {module} ---")
    for kind, entries in refs.items():
        missing = sorted((name, path) for name, path in entries if name not in defined[kind])
        for name, path in missing:
            errors.append(f"Missing @{kind}/{name} referenced by {path}")
            print(f"  MISSING {kind}: {name} ({path})")
    missing_bindings = sorted(binding_refs - ids - BINDING_SPECIAL)
    for name in missing_bindings:
        errors.append(f"binding.{name} has no matching @+id in {module}/res/layout")
        print(f"  MISSING view id: {name}")
    unused = sorted(defined["string"] - {name for name, _ in refs["string"]})
    print(f"  strings defined={len(defined['string'])}, unused={unused}")
    print(f"  view ids={len(ids)}, binding refs={len(binding_refs)}")

print(f"TOTAL ERRORS: {len(errors)}")
for err in errors:
    print("ERROR:", err)
