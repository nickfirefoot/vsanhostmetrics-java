# Changelog

## 3.0.1

- Every dashboard ends with a "Complete the install" box: where to add the account, what to fill in,
  how to restart collection after an upgrade (account row menu, Start Collecting), and the
  requirements. The same text is in each dashboard's README, with a screenshot.
- The generated inventory docs no longer say "No traversal spec found" / "Resource kinds: 0".
  `tools/fix_generated_docs.py` rewrites them after each build as "Where the data lives" (the pack
  creates only its adapter instance; its metrics are pushed onto vCenter's own cluster, host and
  virtual machine objects). The `.excalidraw` file is removed, and `ci/verify.sh` fails if the old
  wording returns. No adapter code, metric or view changes.

## 3.0.0

- build 1: the earlier Python pack (2.x) as a Java GENERAL adapter. JSON API only (vCenter 8.0 U3+/9.0+),
  69 entity types, the same `cptvsan|...` keys (3193-row key diff against the Python pack, 3257 stat keys
  and 67 property keys identical in an end-to-end run against a fake vCenter),
  HEAVY rotation, empty-type memory, derived metrics, `Collector|*` self-metrics, the 8
  dashboards and 31 views. Replaces the 0.1.0 SOAP, host-only sample; its World object is gone:
  the adapter instance is the only object.
- Adapter kind key `vsanhostmetrics` (lowercase, as the stock factory builder requires). Display name "vSAN Host Metrics". Dashboards and views target VMWARE objects and do not reference the kind.
- Review fixes: a failed cluster (or entities collected with zero push targets) now fails the cycle after what succeeded is pushed, naming the clusters; `onDiscover` (8.18) returns an empty successful result; Test Connection keeps the exception cause chain (secrets removed) so certificate-trust wording works; HTTP responses are read to the end and the connection is left for keep-alive pooling; the relationship gate bans object/relationship/reflection calls and counts every Suite API write site, with a self-test that injects bad calls.
