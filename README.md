# vSAN Host Metrics 3.0.1

A VCF Operations management pack (Tier 2, Java adapter) that collects vSAN Performance Service
metrics from one vCenter over the vSphere JSON API and pushes them onto the cluster, host and
virtual machine objects the VMware vSphere adapter already owns. It runs in the collector JVM:
no container, no registry, one `.pak`. Vendor: Nick Wilson.

It is a Java re-implementation of the earlier Python pack (2.x) and pushes the same
`cptvsan|...` metric keys.

## Design rule: no relationships, no foreign objects

Relationship writes through the Suite API replace an object's whole child list. This pack
therefore never writes a relationship and never reports an object it does not own. It only pushes
stats and properties onto objects the vCenter adapter owns. The only object it declares is its own
adapter instance, which carries `Collector|*` self-metrics.

- `SuiteApi` can GET resource listings and POST stats/properties only; any other path throws.
- The adapter overrides `addRelationshipsToCurrentCycle` to throw.
- The framework's `SuiteApiStitcher` is not used; `ci/no_relationships.sh` fails the build on it.

## Requirements

- VCF Operations 8.18+ or 9.x, with a Cloud Proxy or collector to run the adapter.
- vCenter 8.0 Update 3 or later, or 9.0 or later, collected by the VMware vSphere adapter of the
  same Operations instance (otherwise there is no object to receive the metrics).
- A vCenter account with read-only access (System.View and System.Read, propagated from the
  root) that can read the vSAN Performance Service.

## Install

1. Download [`vsanhostmetrics-3.0.1.pak`](https://github.com/nickfirefoot/vsanhostmetrics-java/raw/main/paks/vsanhostmetrics-3.0.1.pak) from the `paks/` directory of this repository (SHA-256 in `paks/SHA256SUMS`).
2. In VCF Operations go to **Administration > Integrations > Repository > Add**, upload the pak
   and accept that it is unsigned.
3. Add an account (**Integrations > Accounts > Add Account > vSAN Host Metrics**) with:
   - **vCenter Server**: FQDN of the vCenter (not an IP or URL)
   - **vCenter port**: default 443
   - **vCenter username**: include the domain, for example `user@vsphere.local`
   - **vCenter password** (credential)
   - **Verify vCenter certificate**: `true` (default) or `false`
4. Validate Connection, then Add. The Suite API credential is injected by the platform; none is
   entered.

More detail: [docs/installing.md](docs/installing.md), [docs/overview.md](docs/overview.md).

## What it collects

Metrics and properties are named `cptvsan|<group>[:<instance>]|<metric>` and land on:

- the vSphere **ClusterComputeResource**, **HostSystem** and **VirtualMachine** objects they
  describe (69 vSAN entity types from `VsanPerfQueryPerf`, one wildcard query per type per cluster);
- a `cptvsan|Collector|lastUpdated` property on each target, and `Collector|*` self-metrics on the
  adapter instance.

The full key list is in [REFERENCE.md](REFERENCE.md). The pack ships 8 dashboards and 31 views:
Rapid vSAN Overview, Storage, Network, DOM, ESA Write Path, Backpressure, Resync and Host
Resources.
Per-dashboard documentation with screenshots is in [docs/dashboards](docs/dashboards/README.md).

Collection behaviour: inventory is read with `WaitForUpdatesEx` on a private PropertyCollector;
a set of 13 heavy entity types is rotated over 3 buckets; types that return no entity are
remembered for 60 minutes and unsupported types for 6 hours. State is held in memory, so a
collector restart costs one cold cycle.

## Build from source

Requirements: JDK 25 (the SDK jar is Java 25 bytecode; the adapter code itself is `--release 11`),
the content factory build tooling, and the Operations SDK jar. The SDK jar is not redistributed;
copy it from a VCF Operations appliance:
`/usr/lib/vmware-vcops/common/lib/vrops-adapters-sdk.jar`.

```sh
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
PYTHONPATH=<factory>/src \
  python3 -m vcfcf_managementpacks build-sdk . --output <dir> --sdk-jar <vrops-adapters-sdk.jar>
python3 ci/airgap_check.py <dir>/*.pak
```

For a release, add `--release` (the factory then stamps the `adapter.yaml` version and compares
the pak against a reference pak), then stamp the vendor and licence:

```sh
python3 tools/fix_generated_docs.py     # after every build, see below
python3 tools/finalize_pak.py <dir>/vcfcf_sdk_vsanhostmetrics.3.0.1.1.pak vsanhostmetrics-3.0.1.pak
```

The build regenerates `docs/inventory-tree.*` and `docs/README.md` from `describe.xml`. This pack
creates no resource kinds, so that output says "No traversal spec found" and "Resource kinds: 0",
which is misleading: the data lives on vCenter's own objects. **Run `python3 tools/fix_generated_docs.py`
after each build**; it rewrites those files as a "Where the data lives" page and deletes the
`.excalidraw` file. `ci/verify.sh` fails if "No traversal spec found" is still anywhere under `docs/`.

The "Complete the install" box on every dashboard (and in each dashboard README) comes from
`tools/install_box.py`; edit the text there, run it, and `ci/verify.sh` checks nothing has drifted.
`tools/test_install_box_contrast.py <pak>` measures its colours inside a built pak.

Checks (generated files current, relationship gate, unit tests):

```sh
PYTHON_REPO=<earlier Python 2.x pack> FRAMEWORK_JAR=<vcfcf-adapter-base.jar> \
VCFCF_SRC=<factory>/src SDK_JAR=<vrops-adapters-sdk.jar> ci/verify.sh
```

The factory builder accepts only a lowercase adapter kind, hence `vsanhostmetrics`.

## Layout

| path | what |
|---|---|
| `src/com/cptvsan/adapter/` | adapter and SDK-free engine |
| `MetricTree.java`, `MetricNames.java`, `model/metric-tree.yaml` | generated from the Python pack's metric tree by `tools/gen_metric_tree.py` |
| `describe.xml`, `resources/`, `CollectorKeys.java` | generated by `tools/gen_describe.py` |
| `views/`, `dashboards/` | the views and dashboards, factory YAML |
| `tests/`, `tests-sdk/` | plain-main tests and an in-process fake vCenter + Suite API |
| `ci/`, `tools/` | verification scripts and generators |

## License

Apache License 2.0, see [LICENSE](LICENSE). The framework this pack is built on comes from
[sentania-labs/vcf-content-factory-sdk-template](https://github.com/sentania-labs/vcf-content-factory-sdk-template)
(MIT, Scott Bowe); see [NOTICE](NOTICE).
