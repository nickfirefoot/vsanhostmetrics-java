#!/usr/bin/env python3
"""Instanced-view gate: rendered views must be real instanced-group views.

A column keyed `group:*|metric` or the pseudo-column `Instance Name` only
yields rows when the view carries an instanced-group DRIVER item
(isInstancedGroup=true, instanceGroupName, showInstanceName).  A plain
`Instance Name` attribute renders "-" in every cell (the 3.0.0 Storage and
Network dashboards).  Usage: check_view_instancing.py [views_dir]
Exit 0 = every view is sound.
"""
import glob, os, pathlib, sys
import xml.etree.ElementTree as ET
HERE = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, os.environ["VCFCF_SRC"])
from vcfcf_core.dashboards.loader import load_view
from vcfcf_core.dashboards.render import render_views_xml

vdir = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else HERE / "views"
views = [load_view(p, enforce_framework_prefix=False) for p in sorted(vdir.glob("*.yaml"))]
bad = []
for vd in ET.fromstring(render_views_xml(views)).iter("ViewDef"):
    t = vd.findtext("Title")
    items = [{p.get("name"): p.get("value") for p in v.findall("Property")} for v in vd.iter("Value")]
    wild = [i["attributeKey"] for i in items if ":*|" in i.get("attributeKey", "")]
    drivers = [i for i in items if i.get("attributeKey") == "Instance Name"]
    good = [d for d in drivers if d.get("isInstancedGroup") == "true" and d.get("instanceGroupName")]
    if (wild or drivers) and not good:
        bad.append(f"{t}: instanced columns {wild or ['Instance Name']} but no isInstancedGroup driver")
    if len(good) != len(drivers):
        bad.append(f"{t}: 'Instance Name' column without isInstancedGroup=true")
    if wild and len(good) != 1:
        bad.append(f"{t}: wildcard columns need exactly one instanced-group driver, found {len(good)}")
print(f"views checked: {len(views)}")
for b in bad: print("FAIL", b)
sys.exit(1 if bad else 0)
