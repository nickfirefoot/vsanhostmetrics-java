#!/usr/bin/env python3
"""Generate src/com/cptvsan/adapter/MetricTree.java (and model/metric-tree.yaml)
from the Python pack's metric tree, so the Java and Python packs push the same keys.

Sources, all read from the Python repo working tree (the earlier Python pack, 2.x):
  app/metric_tree.py          TREE, PREFIX, FRESHNESS_KEY, RESERVED   (imported: no deps)
  app/perfsvc_model.py        ENTITIES[...]["identity"], ["metrics"]  (imported: no deps)
  app/perfsvc_model_extra.py  same, merged in; the live model wins     (imported: no deps)
  app/perfsvc.py              PERMILLE, FEATURE_ENTITIES, DISK_HARDWARE_ENTITIES
                              (read with ast: the module imports pyVmomi)
  tools/build_scotts_readme.py  HEAVY families                         (read with ast)

Usage:
  python3 tools/gen_metric_tree.py [--python-repo <Python 2.x pack> | $PYTHON_REPO]
  python3 tools/gen_metric_tree.py --check     # exit 1 if the committed files differ

Nothing here touches a network. The generated files are committed.
"""
import argparse
import ast
import importlib.util
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
JAVA_OUT = os.path.join(REPO, "src", "com", "cptvsan", "adapter", "MetricTree.java")
YAML_OUT = os.path.join(REPO, "model", "metric-tree.yaml")
NAMES_OUT = os.path.join(REPO, "src", "com", "cptvsan", "adapter", "MetricNames.java")
KEYS_OUT = os.path.join(REPO, "tests", "fixtures", "python-keys.tsv")

TARGETS = {"ClusterComputeResource": "CLUSTER", "HostSystem": "HOST",
           "VirtualMachine": "VM"}


def load_module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def ast_literal(path, name):
    """The literal value assigned to top-level `name` (frozenset({...}) allowed)."""
    with open(path) as f:
        tree = ast.parse(f.read(), path)
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
                isinstance(t, ast.Name) and t.id == name for t in node.targets):
            v = node.value
            if (isinstance(v, ast.Call) and isinstance(v.func, ast.Name)
                    and v.func.id in ("frozenset", "set", "tuple") and len(v.args) == 1):
                return ast.literal_eval(v.args[0])
            return ast.literal_eval(v)
    raise SystemExit(f"{path}: no literal assignment to {name}")


def jstr(s):
    out = ['"']
    for ch in s:
        if ch in '"\\':
            out.append("\\" + ch)
        elif ord(ch) < 0x20 or ord(ch) > 0x7e:
            out.append("\\u%04x" % ord(ch))
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def jarr(items):
    return "new String[] {" + ", ".join(jstr(i) for i in items) + "}"


def placeholders(template):
    out, i = [], 0
    while template and i < len(template):
        a = template.find("{", i)
        if a < 0:
            break
        b = template.find("}", a)
        out.append(template[a + 1:b])
        i = b + 1
    return out


KEPT = {}
PYTREE = {}


def generate(py_repo):
    app = os.path.join(py_repo, "app")
    tree = load_module(os.path.join(app, "metric_tree.py"), "cpt_metric_tree")
    model = load_module(os.path.join(app, "perfsvc_model.py"), "cpt_perfsvc_model")
    extra = load_module(os.path.join(app, "perfsvc_model_extra.py"), "cpt_perfsvc_extra")
    entities = dict(model.ENTITIES)
    for k, v in extra.ENTITIES.items():
        entities.setdefault(k, v)
    perfsvc = os.path.join(app, "perfsvc.py")
    permille = sorted(ast_literal(perfsvc, "PERMILLE"))
    feature = set(ast_literal(perfsvc, "FEATURE_ENTITIES"))
    disk_hw = list(ast_literal(perfsvc, "DISK_HARDWARE_ENTITIES"))
    heavy_fams = ast_literal(os.path.join(py_repo, "tools", "build_scotts_readme.py"), "HEAVY")
    heavy = sorted({e for v in heavy_fams.values() for e in v})

    rev, branch = "", ""

    # Checks: the Java side relies on every one of these.
    assert tree.PREFIX == "cptvsan", tree.PREFIX
    assert tree.FRESHNESS_KEY == "cptvsan|Collector|lastUpdated", tree.FRESHNESS_KEY
    assert set(tree.TREE) <= set(entities), sorted(set(tree.TREE) - set(entities))
    assert set(heavy) <= set(tree.TREE), sorted(set(heavy) - set(tree.TREE))
    assert set(feature) <= set(tree.TREE) | set(entities)
    assert set(disk_hw) <= set(tree.TREE)

    rows = []
    for etype in sorted(tree.TREE):
        target, group, inst = tree.TREE[etype]
        identity = list(entities[etype]["identity"])
        for p in placeholders(inst):
            assert p in identity, f"{etype}: template part {p} not in identity {identity}"
        metrics = set(entities[etype]["metrics"])
        twins = sorted(m for m in metrics if m.endswith("Actual") and m[:-6] in metrics)
        kept = sorted(m for m in metrics if not (m.endswith("Actual") and m[:-6] in metrics))
        rows.append((etype, TARGETS[target], group, inst, identity, etype in heavy,
                     etype in feature, twins, len(metrics)))
        KEPT[etype] = kept

    PYTREE.update(tree=tree)
    pairs = sorted({(r[1], r[2]) for r in rows})
    groups = sorted({r[2] for r in rows})
    return dict(rows=rows, pairs=pairs, groups=groups, permille=permille,
                reserved=dict(tree.RESERVED), disk_hw=disk_hw, heavy_fams=heavy_fams,
                rev=rev, branch=branch)


def java(d):
    L = []
    w = L.append
    w("// GENERATED by tools/gen_metric_tree.py -- DO NOT EDIT BY HAND.")
    w(f"// Source: the earlier Python pack (2.x): app/metric_tree.py (TREE),")
    w("//   app/perfsvc_model.py + app/perfsvc_model_extra.py (identity, Actual twins),")
    w("//   app/perfsvc.py (PERMILLE, FEATURE_ENTITIES, DISK_HARDWARE_ENTITIES),")
    w("//   tools/build_scotts_readme.py (HEAVY).")
    w("package com.cptvsan.adapter;")
    w("")
    w("import java.util.Arrays;")
    w("import java.util.Collections;")
    w("import java.util.LinkedHashMap;")
    w("import java.util.List;")
    w("import java.util.Map;")
    w("import java.util.Set;")
    w("import java.util.TreeSet;")
    w("")
    w("/**")
    w(f" * The cptvsan metric tree as data: {len(d['rows'])} vSAN Performance Service entity")
    w(f" * types in {len(d['pairs'])} (target kind, group) pairs. Every key is")
    w(" * {@code cptvsan|<group>[:<instance>]|<metric>}; {@link StatKeys} builds it.")
    w(" * Change the Python {@code TREE} and regenerate; never edit this file.")
    w(" */")
    w("final class MetricTree {")
    w("")
    w("    private MetricTree() {")
    w("    }")
    w("")
    w(f"    static final String SOURCE = {jstr('the earlier Python pack (2.x)')};")
    w("")
    w("    /** Every entity type, sorted by id. */")
    w("    static final List<TreeEntry> ENTRIES = Collections.unmodifiableList(Arrays.asList(")
    for i, (etype, tgt, group, inst, ident, heavy, feat, twins, _n) in enumerate(d["rows"]):
        sep = "," if i < len(d["rows"]) - 1 else "));"
        w(f"            new TreeEntry({jstr(etype)}, TargetKind.{tgt}, {jstr(group)},")
        w(f"                    {jstr(inst) if inst else 'null'}, {jarr(ident)},")
        w(f"                    {'true' if heavy else 'false'}, {'true' if feat else 'false'}, "
          f"{jarr(twins)}){sep}")
    w("")
    w("    /** Labels the service reports per mille; pushed divided by 10 (percent). */")
    w("    static final Set<String> PERMILLE = Collections.unmodifiableSet(new TreeSet<>(")
    w("            Arrays.asList(" + ", ".join(jstr(p) for p in d["permille"]) + ")));")
    w("")
    w("    /** Entity types whose disk instances also get firmware/model/vendor properties. */")
    w("    static final Set<String> DISK_HARDWARE_ENTITIES = Collections.unmodifiableSet(")
    w("            new TreeSet<>(Arrays.asList(" + ", ".join(jstr(p) for p in d["disk_hw"])
      + ")));")
    w("")
    w("    /**")
    w("     * Reserved top-level groups: documented, never written. A future source")
    w("     * lands on these names; nothing in this pack builds a key under them.")
    w("     */")
    w("    static final Map<String, String> RESERVED;")
    w("")
    w("    static {")
    w("        Map<String, String> r = new LinkedHashMap<>();")
    for k, v in d["reserved"].items():
        w(f"        r.put({jstr(k)}, {jstr(v)});")
    w("        RESERVED = Collections.unmodifiableMap(r);")
    w("    }")
    w("}")
    return "\n".join(L) + "\n"


def names_java(d):
    """MetricNames.java: the metrics kept per entity type (model minus *Actual twins)."""
    L = ["// GENERATED by tools/gen_metric_tree.py -- DO NOT EDIT BY HAND.",
         f"// Source: the earlier Python pack (2.x): perfsvc.metrics_for(entity).",
         "package com.cptvsan.adapter;", "", "import java.util.HashMap;", "import java.util.Map;", "",
         "/** The metric labels each entity type reports and the pack keys. */",
         "final class MetricNames {", "", "    private MetricNames() {", "    }", "",
         "    private static final Map<String, String[]> BY_TYPE = new HashMap<>();", "",
         "    static {"]
    for i, etype in enumerate(sorted(KEPT)):
        L.append(f"        BY_TYPE.put({jstr(etype)}, m{i}());")
    L += ["    }", "",
          "    /** The kept labels of {@code entityType}; empty for an unknown type. */",
          "    static String[] of(String entityType) {",
          "        String[] m = BY_TYPE.get(entityType);",
          "        return m == null ? new String[0] : m.clone();",
          "    }", ""]
    for i, etype in enumerate(sorted(KEPT)):
        L.append(f"    private static String[] m{i}() {{")
        L.append("        return new String[] {")
        for j in range(0, len(KEPT[etype]), 4):
            L.append("            " + ", ".join(jstr(m) for m in KEPT[etype][j:j + 4]) + ",")
        L.append("        };")
        L.append("    }")
        L.append("")
    L.append("}")
    return "\n".join(L) + "\n"


def keys_tsv(d):
    """entity <TAB> identity parts joined by | <TAB> metric <TAB> key, from the PYTHON build_key."""
    tree = PYTREE["tree"]
    out = ["# GENERATED by tools/gen_metric_tree.py from the Python metric_tree.build_key.",
           "# entity\tparts\tmetric\tkey"]
    n = 0
    for etype, _t, _g, _i, ident, _h, _f, _tw, _n in d["rows"]:
        samples = [["id%d" % k for k in range(len(ident))],
                   ["a:b%d" % k for k in range(len(ident))]]
        if etype == "host-cpu":
            samples.append(["id0", "(all)"])
        for parts in samples:
            for m in KEPT[etype]:
                key = tree.build_key(etype, m, dict(zip(ident, parts)))
                out.append(f"{etype}\t{'|'.join(parts)}\t{m}\t{key}")
                n += 1
    return "\n".join(out) + "\n"


def yaml(d):
    L = [f"# cptvsan metric tree. GENERATED by tools/gen_metric_tree.py from the earlier Python pack (2.x).",
         "# key = cptvsan|<group>[:<instance>]|<metric>; instance ':' and '|' become '-'.",
         "prefix: cptvsan",
         "freshness_property: cptvsan|Collector|lastUpdated",
         f"entity_types: {len(d['rows'])}",
         f"target_group_pairs: {len(d['pairs'])}",
         "reserved_groups:"]
    for k, v in d["reserved"].items():
        L.append(f'  "{k}": "{v}"')
    L.append("heavy_families:")
    for fam, ents in d["heavy_fams"].items():
        L.append(f'  "{fam}": [{", ".join(ents)}]')
    L.append("entities:")
    for etype, tgt, group, inst, ident, heavy, feat, twins, n in d["rows"]:
        L += [f"  {etype}:",
              f"    target: {dict((v, k) for k, v in TARGETS.items())[tgt]}",
              f'    group: "{group}"',
              "    instance: " + (f'"{inst}"' if inst else "null"),
              f"    identity: [{', '.join(ident)}]",
              f"    cadence: {'heavy' if heavy else 'core'}",
              f"    feature_gated: {'true' if feat else 'false'}",
              f"    metrics: {n}"]
    return "\n".join(L) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--python-repo", default=os.environ.get("PYTHON_REPO"), required=not os.environ.get("PYTHON_REPO"))
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    d = generate(a.python_repo)
    outs = {JAVA_OUT: java(d), YAML_OUT: yaml(d), NAMES_OUT: names_java(d), KEYS_OUT: keys_tsv(d)}
    if a.check:
        stale = [p for p, t in outs.items()
                 if not os.path.exists(p) or open(p).read() != t]
        for p in stale:
            print(f"stale: {os.path.relpath(p, REPO)}", file=sys.stderr)
        sys.exit(1 if stale else 0)
    for p, t in outs.items():
        with open(p, "w") as f:
            f.write(t)
        print(f"wrote {os.path.relpath(p, REPO)}")
    print(f"{len(d['rows'])} entity types, {len(d['pairs'])} (target, group) pairs, "
          f"{sum(1 for r in d['rows'] if r[5])} heavy, {len(d['reserved'])} reserved groups")


if __name__ == "__main__":
    main()
