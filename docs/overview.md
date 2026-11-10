# Overview: vSAN Host Metrics

## What's in the Pack

vSAN Host Metrics. For the version, see [docs/README.md](README.md) (regenerated on every build) or the GitHub release.

vSAN Performance Service metrics for every vSAN cluster of one vCenter, over the vSphere JSON API (vCenter 8.0 Update 3+ or 9.0+), pushed under `cptvsan|` onto the VMWARE cluster, host and VM objects the vCenter adapter owns. The only object this pack has is its adapter instance; it never adds, removes or replaces a relationship.

### Resource Kinds (1)

- **vSAN Host Metrics Adapter Instance** (`vsanhostmetrics`): the adapter instance, carrying `Collector|*` self-metrics

## Cross-Adapter Notes

Needs the VMware vSphere adapter to collect the SAME vCenter, or no metric has an object to land on.
Nothing is stitched and no relationship is written: values go through the Suite API stats and
properties endpoints only.

## Known Limitations

- State (watermarks, id cache, inventory mirror) is in memory: a collector restart costs one cold cycle.
- Uninstalling removes the dashboards, not the views (search `[VHM]`), nor metric history already written.
