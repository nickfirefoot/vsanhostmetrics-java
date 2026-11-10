# Installing & Configuring: vSAN Host Metrics

## Prerequisites

- VCF Operations 8.18 or later, or 9.x, with a Cloud Proxy or collector
- vCenter 8.0 Update 3 or later, or 9.0 or later, collected by the VMware vSphere adapter of the same Operations

## Permissions Required

A vCenter account with Read-only (System.View, System.Read) propagated from the root. The Suite API credential is injected by the platform; none is entered.

## Network Requirements

| Port | Protocol | Direction | Purpose |
|------|----------|-----------|---------|
| 443  | HTTPS    | Outbound  | vCenter vSphere JSON API and vSAN Performance Service |

## Configuration Fields

When adding a new adapter instance in VCF Operations, you will be prompted for:

| Field | Key | Required | Default | Notes |
|-------|-----|----------|---------|-------|
| vCenter Server | `vcenter_host` | Yes | - | FQDN, not an IP or URL; the same vCenter the vSphere adapter collects |
| vCenter port | `vcenter_port` | No | 443 | |
| vCenter username | `vcenter_user` | Yes | - | include the domain |
| Verify vCenter certificate | `verify_certs` | Yes | true | accept the certificate at Validate Connection, or set false |
| vCenter password | credential `vcenter_password` | Yes | - | |

## Step-by-Step Installation

1. Install the `.pak` file via **Administration > Integrations > Repository > Add** and accept the unsigned pak.
2. After installation, navigate to **Integrations > Accounts**.
3. Click **Add Account** and select **vSAN Host Metrics**.
4. Fill in the configuration fields above.
5. Click **Validate Connection**, then **Add**.

## Troubleshooting

- Validate Connection fails on TLS: accept the vCenter certificate in the dialog, use the FQDN, or set Verify to false.
- The instance shows an error naming unresolved objects: the vSphere adapter does not collect this vCenter.
- The instance shows the Suite API could not authenticate: the platform-injected credential is missing for this collector.
