#!/usr/bin/env python3
"""Prove the ported views/dashboards match the Python pack's native content.

Loads the ported YAML with the factory loader, RENDERS it with the factory's
forward renderer (what build-sdk would emit), and compares against the source
content of the earlier Python pack (its content/ directory):
  - same set of view titles; same set of dashboard names
  - per view: same cptvsan| metric keys, same SubjectType (adapterKind,resourceKind,type) set
  - per dashboard: same cptvsan| keys, same widget count, same widget titles,
    same referenced view-name set
Exit 0 only if every comparison matches.
Usage: check_content.py [ported_dir=<script>/..] [factory_root] [source_content_dir]
"""
import json, os, re, sys, glob
import xml.etree.ElementTree as ET
from pathlib import Path

here = Path(__file__).resolve().parent.parent
ported = Path(sys.argv[1]) if len(sys.argv) > 1 else here
factory = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(os.environ["VCFCF_ROOT"])
src = Path(sys.argv[3]) if len(sys.argv) > 3 else Path(os.environ["PYTHON_REPO"]) / "content"
sys.path.insert(0, str(factory / "src"))
from vcfcf_core.dashboards.loader import load_view, load_dashboard
from vcfcf_core.dashboards.render import render_views_xml, render_dashboards_bundle_json

KEY = re.compile(r'cptvsan\|[^"<>]+')
def keys(text): return set(KEY.findall(text))

# ---- source
s_views = {}  # title -> (keys, subjects)
for f in sorted(glob.glob(str(src / "reports/*/*.xml"))):
    for vd in ET.parse(f).getroot().iter("ViewDef"):
        t = vd.findtext("Title")
        s_views[t] = (keys(ET.tostring(vd, encoding="unicode")),
                      {(s.get("adapterKind"), s.get("resourceKind"), s.get("type"))
                       for s in vd.findall("SubjectType")})
s_dash = {}
vid2title = {}
for f in sorted(glob.glob(str(src / "reports/*/*.xml"))):
    for vd in ET.parse(f).getroot().iter("ViewDef"):
        vid2title[vd.get("id")] = vd.findtext("Title")
for f in sorted(glob.glob(str(src / "dashboards/*/*.json"))):
    for d in json.load(open(f))["dashboards"]:
        txt = json.dumps(d)
        ws = d.get("widgets", [])
        vref = {vid2title.get(m, "?" + m) for m in
                re.findall(r'"viewDefinitionId":\s*"([0-9a-f-]{36})"', txt)}
        s_dash[d["name"]] = (keys(txt), len(ws), sorted(w.get("title", "") for w in ws), vref)

# ---- ported (load + forward-render)
vfiles = sorted((ported / "views").glob("*.yaml"))
dfiles = sorted((ported / "dashboards").glob("*.yaml"))
views = [load_view(p, enforce_framework_prefix=False) for p in vfiles]
dashes = [load_dashboard(p, enforce_framework_prefix=False) for p in dfiles]
vxml = render_views_xml(views)
p_views = {}
for vd in ET.fromstring(vxml).iter("ViewDef"):
    p_views[vd.findtext("Title")] = (keys(ET.tostring(vd, encoding="unicode")),
        {(s.get("adapterKind"), s.get("resourceKind"), s.get("type")) for s in vd.findall("SubjectType")})
vbn = {v.name: v for v in views}
djson = json.loads(render_dashboards_bundle_json(dashes, vbn, "check-user"))
dl = djson["dashboards"] if isinstance(djson, dict) else djson
p_vid2title = {v.id.lower(): v.name for v in views}
p_dash = {}
for d in dl:
    txt = json.dumps(d); ws = d.get("widgets", [])
    nm = d["name"].split("/", 1)[-1].strip()
    vref = {p_vid2title.get(m.lower(), "?" + m) for m in
            re.findall(r'"viewDefinitionId":\s*"([0-9a-f-]{36})"', txt)}
    p_dash[nm] = (keys(txt), len(ws), sorted(w.get("title", "") for w in ws), vref)

# ---- compare
bad = []
def cmp(what, a, b):
    if a != b: bad.append(f"{what}: source-only={sorted(a - b) if isinstance(a, set) else a} ported-only={sorted(b - a) if isinstance(b, set) else b}")
cmp("view names", set(s_views), set(p_views))
cmp("dashboard names", set(s_dash), set(p_dash))
for t in sorted(set(s_views) & set(p_views)):
    cmp(f"view '{t}' keys", s_views[t][0], p_views[t][0])
    cmp(f"view '{t}' SubjectTypes", s_views[t][1], p_views[t][1])
for n in sorted(set(s_dash) & set(p_dash)):
    a, b = s_dash[n], p_dash[n]
    cmp(f"dash '{n}' keys", a[0], b[0]); cmp(f"dash '{n}' widget count", a[1], b[1])
    cmp(f"dash '{n}' widget titles", a[2], b[2]); cmp(f"dash '{n}' view refs", a[3], b[3])
sk = set().union(*(v[0] for v in s_views.values()), *(d[0] for d in s_dash.values()))
pk = set().union(*(v[0] for v in p_views.values()), *(d[0] for d in p_dash.values()))
print(f"dashboards: source {len(s_dash)} ported {len(p_dash)}")
print(f"views:      source {len(s_views)} ported {len(p_views)}")
print(f"distinct cptvsan| keys: source {len(sk)} ported {len(pk)}")
print(f"widgets: source {sum(d[1] for d in s_dash.values())} ported {sum(d[1] for d in p_dash.values())}")
if bad:
    print(f"FAIL: {len(bad)} difference(s)")
    for b in bad: print("  -", b)
    sys.exit(1)
print("OK: names, keys, subject types, widget counts/titles and view references all match")
