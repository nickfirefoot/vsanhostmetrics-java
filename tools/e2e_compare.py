#!/usr/bin/env python3
"""Drive the Java collector and the Python collector against the SAME fake
vCenter + Suite API (the Python pack's tools/fake_vsphere.py, offline,
127.0.0.1) and compare the keys each one pushes.

Two fresh fake servers with the same world, one per collector, so neither sees
the other's watermarks. Cycles 0..N-1 at the same simulated times, so HEAVY
rotation lines up. Compared: the stat keys and property keys that landed, per
VMWARE kind, as sets. The fake's sample values are random-ish with occasional
"None", so a metric whose every sample in a window is None can differ by
chance; any difference is printed, not hidden.

  python3 tools/e2e_compare.py [--python-repo <Python 2.x pack>] [--cycles 6]
"""
import argparse
import os
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--python-repo", default=os.environ.get("PYTHON_REPO"), required=not os.environ.get("PYTHON_REPO"))
    ap.add_argument("--cycles", type=int, default=6)
    ap.add_argument("--java-home", default="/usr/lib/jvm/java-25-openjdk-amd64")
    ap.add_argument("--clusters", type=int, default=1)
    ap.add_argument("--hosts", type=int, default=3)
    ap.add_argument("--vms", type=int, default=2)
    a = ap.parse_args()
    sys.path[:0] = [os.path.join(a.python_repo, "app"), os.path.join(a.python_repo, "tools")]
    import fake_vsphere
    import collector

    def patch(cls):
        orig = cls._land

        def land(self, rid, contents, is_stats):
            ks = self.__dict__.setdefault("keys", {"stats": set(), "props": set()})
            for c in contents:
                ks["stats" if is_stats else "props"].add(c["statKey"])
            return orig(self, rid, contents, is_stats)
        cls._land = land
    patch(fake_vsphere.Server)

    now = (int(time.time() * 1000) // 300_000) * 300_000 + 30_000

    # --- Python
    httpd, srv, port = fake_vsphere.start(a.clusters, a.hosts, a.vms)
    tmp = tempfile.mkdtemp(prefix="e2e-py-")
    ep = collector.Endpoints(vc_host="127.0.0.1", vc_user="u@vsphere.local", vc_password="p",
                             vc_port=port, vc_protocol="http",
                             suite_base=f"http://127.0.0.1:{port}/suite-api/",
                             suite_user="admin", suite_password="p")
    cfg = collector.Config(stagger_s=0, state_dir=tmp)
    for c in range(a.cycles):
        srv.w.clock_ms = now + c * 300_000
        collector.run_collection(ep, cfg, os.path.join(tmp, "state.json"), cycle=c,
                                 now_ms=now + c * 300_000)
    py = srv.__dict__["keys"]
    httpd.shutdown()

    # --- Java
    httpd, srv, port = fake_vsphere.start(a.clusters, a.hosts, a.vms)
    out = tempfile.mkdtemp(prefix="e2e-java-")
    env = dict(os.environ, JAVA_HOME=a.java_home, PATH=a.java_home + "/bin:" + os.environ["PATH"])
    subprocess.run(["javac", "--release", "11", "-d", out, "-sourcepath", "src:tests",
                    "tests/com/cptvsan/adapter/E2eMain.java"],
                   cwd=REPO, env=env, check=True, capture_output=True)
    for c in range(a.cycles):
        srv.w.clock_ms = now + c * 300_000
    # the Java driver advances its own simulated time; the fake only needs the clock for tokens
    r = subprocess.run(["java", "-cp", out, "com.cptvsan.adapter.E2eMain", str(port),
                        str(a.cycles), str(now)], cwd=REPO, env=env, capture_output=True, text=True)
    print(r.stdout.strip())
    if r.returncode:
        print(r.stderr[-2000:])
        sys.exit(1)
    jv = srv.__dict__["keys"]
    httpd.shutdown()

    bad = False
    for what in ("stats", "props"):
        p, j = py[what], jv[what]
        print(f"{what}: python {len(p)} keys, java {len(j)} keys, "
              f"python-only {len(p - j)}, java-only {len(j - p)}")
        for k in sorted(p - j)[:15]:
            print("  python only:", k)
        for k in sorted(j - p)[:15]:
            print("  java only:  ", k)
        bad = bad or p != j
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
