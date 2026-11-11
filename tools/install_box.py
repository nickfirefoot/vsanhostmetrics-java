#!/usr/bin/env python3
"""The "Complete the install" box: one text, written onto every dashboard and every
dashboard README, so the two cannot drift.

    python3 tools/install_box.py            # rewrite dashboards/*.yaml and docs/dashboards/*/README.md
    python3 tools/install_box.py --check    # exit 1 if either is out of date

Style follows the Memory Tier pack: the TextDisplay widget follows neither the theme nor the
dashboard font, so the box paints its own card and EVERY element carries inline colours (the
rich-text editor discards <style>). Contrast is measured by tools/test_install_box_contrast.py.
"""
import argparse
import glob
import os
import re
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
TITLE = "Complete the install"

_CARD = ("style=\"font-family:'Metropolis','Segoe UI',sans-serif;font-size:13px;line-height:1.45;"
         "padding:12px 14px;background:#2b3137;color:#e9edf0;border-radius:6px;"
         "border:1px solid #4a535d\"")
_H = 'style="font-size:15px;margin:4px 0 10px 0;color:#ffffff"'
_P = 'style="margin:5px 0;line-height:1.45;color:#e9edf0"'
_B = 'style="font-weight:700;color:#ffffff"'
_W = 'style="font-weight:700;color:#ffd79a"'

# (html, markdown) per step. Wording verified live on 8.18.7 and 9.1.1 on 2026-11-11.
STEPS = [
    ('Go to Administration &rarr; Integrations &rarr; <b {B}>Accounts</b> tab &rarr; <b {B}>ADD</b> '
     '&rarr; choose the <b {B}>vSAN Host Metrics</b> tile.',
     'Go to Administration → Integrations → **Accounts** tab → **ADD** → choose the '
     '**vSAN Host Metrics** tile.'),
    ('Fill in <b {B}>Add Account - vSAN Host Metrics</b>: <b {B}>Name</b>; <b {B}>vCenter Server</b>; '
     '<b {B}>vCenter username</b>; <b {B}>Credential</b> &rarr; <b {B}>+</b> &rarr; '
     '<b {B}>Credential name</b> and <b {B}>vCenter password</b> &rarr; <b {B}>OK</b>; '
     '<b {B}>Verify vCenter certificate</b>; <b {B}>Cloud Proxy / Group</b> (9.1.1) or '
     '<b {B}>Collector / Group</b> (8.18). The vCenter port is under <b {B}>Advanced Settings</b> '
     '(default 443).',
     'Fill in **Add Account - vSAN Host Metrics**: **Name**; **vCenter Server**; '
     '**vCenter username**; **Credential** → **+** → **Credential name** and '
     '**vCenter password** → **OK**; **Verify vCenter certificate**; **Cloud Proxy / Group** '
     '(9.1.1) or **Collector / Group** (8.18). The vCenter port is under **Advanced Settings** '
     '(default 443).'),
    ('Click <b {B}>VALIDATE CONNECTION</b> and accept the vCenter certificate when prompted. '
     'Then click <b {B}>ADD</b>.',
     'Click **VALIDATE CONNECTION** and accept the vCenter certificate when prompted. '
     'Then click **ADD**.'),
    ('<b {W}>After installing or upgrading the pak:</b> Administration &rarr; Integrations '
     '&rarr; Accounts &rarr; expand <b {B}>vSAN Host Metrics</b> &rarr; check the account\'s '
     '<b {B}>Status</b>. If it isn\'t collecting, use &#8942; on the account row &rarr; '
     '<b {B}>Start Collecting</b>.',
     '**After installing or upgrading the pak:** Administration → Integrations → Accounts → '
     'expand **vSAN Host Metrics** → check the account\'s **Status**. If it isn\'t collecting, '
     'use ⋮ on the account row → **Start Collecting**.'),
    ('<b {B}>Requirements:</b> vCenter 8.0 U3 or later, or 9.0 or later; a vCenter account that '
     'can read vSAN performance data.',
     '**Requirements:** vCenter 8.0 U3 or later, or 9.0 or later; a vCenter account that can '
     'read vSAN performance data.'),
]
NOTE = "No policy or super-metric changes are needed."


def html():
    fmt = dict(B=_B, W=_W)
    lis = "\n".join(f"<li {_P}>{h.format(**fmt)}</li>" for h, _ in STEPS)
    return (f"<div {_CARD}>\n<h3 {_H}>{TITLE}</h3>\n"
            f'<ol style="margin:4px 0 4px 20px;padding:0;color:#e9edf0">\n{lis}\n</ol>\n'
            f"<p {_P}>{NOTE}</p>\n</div>")


def markdown():
    steps = "\n".join(f"{i}. {m}" for i, (_, m) in enumerate(STEPS, 1))
    return (f"## {TITLE}\n\n![Complete the install box](../complete-the-install.png)\n\n"
            f"{steps}\n\n{NOTE}\n")


def widget_id(dash):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"vsanhostmetrics/install-box/{dash}"))


def yaml_scalar(text):
    return "'" + text.replace("'", "''") + "'"


def update_dashboard(path):
    s = open(path).read()
    name = os.path.basename(path)
    # drop an earlier copy of the box, then append as the last widget
    s = re.sub(r"- id: [0-9a-f-]+\n  type: TextDisplay\n  title: " + re.escape(TITLE) +
               r"\n.*?(?=^interactions:|\Z)", "", s, flags=re.S | re.M)
    ys = [int(m) for m in re.findall(r"^    y: (\d+)\n    w: \d+\n    h: (?:\d+)", s, re.M)]
    ends = [int(y) + int(h) for y, h in re.findall(r"^    y: (\d+)\n    w: \d+\n    h: (\d+)", s, re.M)]
    y = max(ends)
    w = ("- id: %s\n  type: TextDisplay\n  title: %s\n  coords:\n    x: 1\n    y: %d\n    w: 12\n"
         "    h: 9\n  html: %s\n" % (widget_id(name), TITLE, y, yaml_scalar(html())))
    assert "\ninteractions:" in s, path
    s = s.replace("\ninteractions:", "\n" + w + "interactions:", 1) if False else \
        re.sub(r"^interactions:", lambda m: w + "interactions:", s, count=1, flags=re.M)
    return s


def update_readme(path):
    s = open(path).read()
    s = re.sub(r"\n## " + TITLE + r"\n.*?(?=\n## |\Z)", "\n", s, flags=re.S)
    s = s.rstrip("\n") + "\n\n" + markdown()
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    stale = []
    jobs = [(p, update_dashboard) for p in sorted(glob.glob(os.path.join(REPO, "dashboards", "*.yaml")))]
    jobs += [(p, update_readme) for p in sorted(glob.glob(os.path.join(REPO, "docs", "dashboards", "*", "README.md")))]
    for p, fn in jobs:
        new = fn(p)
        if new != open(p).read():
            stale.append(p)
            if not a.check:
                open(p, "w").write(new)
    if a.check and stale:
        print("out of date (run tools/install_box.py):", *stale, sep="\n  ")
        return 1
    print(f"{len(jobs)} files {'current' if a.check else 'written'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
