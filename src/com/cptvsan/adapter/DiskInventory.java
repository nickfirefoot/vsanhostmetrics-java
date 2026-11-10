package com.cptvsan.adapter;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Disk to host, disk display names and disk hardware for one cluster: the
 * Python pack's {@code perfsvc.disk_inventory}. Static enough to cache for an
 * hour. Best effort throughout: a call that faults is skipped and the rest
 * still report. QueryDisksForVsan covers OSA and ESA; QueryVsanManagedDisks
 * (ESA storage pools) adds display names and wins where both answer.
 */
final class DiskInventory {

    /** vSAN disk uuid to owning host MoRef. */
    final Map<String, String> disks = new HashMap<>();
    /** vSAN disk uuid to a readable label. */
    final Map<String, String> names = new HashMap<>();
    /** vSAN disk uuid to firmware, model, vendor, driver, driver_version (non-empty only). */
    final Map<String, Map<String, String>> hw = new HashMap<>();
    long atMs;

    private static final Pattern T10 = Pattern.compile("^t10\\.([A-Za-z0-9]+)_+(.+)$");

    /** A readable disk name: a t10 canonical name with its padding collapsed. */
    static String diskLabel(Object scsi) {
        String display = JsonReader.str(scsi, "displayName");
        String canonical = JsonReader.str(scsi, "canonicalName");
        if (canonical == null || canonical.isEmpty()) {
            canonical = JsonReader.str(scsi, "deviceName");
        }
        if (canonical == null || canonical.isEmpty()) {
            return display;
        }
        Matcher m = T10.matcher(canonical);
        if (!m.matches()) {
            return display != null && !display.isEmpty() ? display : canonical;
        }
        StringBuilder b = new StringBuilder(m.group(1));
        for (String t : m.group(2).split("_")) {
            if (!t.isEmpty()) {
                b.append(' ').append(t);
            }
        }
        return b.toString();
    }

    /** {@code hosts}: {moid, name, HostVsanSystem moid or null}. */
    static DiskInventory read(VcClient vc, String clusterMoid, String clusterName,
            List<String[]> hosts, Log log) {
        DiskInventory d = new DiskInventory();
        Map<String, String> hclVersion = new HashMap<>();
        try {
            hclVersion = vc.hclVersions(clusterMoid);
        } catch (Exception e) {
            log.info("HCL driver versions unavailable on " + clusterName + ": "
                    + e.getClass().getSimpleName());
        }
        Map<String, String> driverForLun = new HashMap<>();
        Map<String, String> hbaForLun = new HashMap<>();
        try {
            List<String> ids = new java.util.ArrayList<>();
            for (String[] h : hosts) {
                ids.add(h[0]);
            }
            for (Map<String, Object> props : vc.hostStorage(ids).values()) {
                Map<String, String[]> hba = new LinkedHashMap<>();
                Object hbas = props.get("config.storageDevice.hostBusAdapter");
                if (hbas instanceof List) {
                    for (Object a : (List<?>) hbas) {
                        String drv = JsonReader.str(a, "driver");
                        String dev = JsonReader.str(a, "device");
                        hba.put(JsonReader.str(a, "key"), new String[] {drv == null ? "" : drv,
                            dev == null ? "" : dev});
                    }
                }
                Object topo = props.get("config.storageDevice.scsiTopology");
                for (Object adapter : JsonReader.list(topo, "adapter")) {
                    String[] dd = hba.get(JsonReader.str(adapter, "adapter"));
                    if (dd == null) {
                        dd = new String[] {"", ""};
                    }
                    for (Object target : JsonReader.list(adapter, "target")) {
                        for (Object lun : JsonReader.list(target, "lun")) {
                            String key = JsonReader.str(lun, "scsiLun");
                            if (!dd[0].isEmpty()) {
                                driverForLun.put(key, dd[0]);
                            }
                            if (!dd[1].isEmpty()) {
                                hbaForLun.put(key, dd[1]);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.info("no scsi topology on " + clusterName + ": " + e.getClass().getSimpleName());
        }
        for (String[] h : hosts) {
            String host = h[0];
            String name = h[1];
            String vsys = h[2];
            try {
                for (Object[] e : vc.managedDisks(host)) {
                    String uuid = (String) e[0];
                    if (uuid != null && !uuid.isEmpty()) {
                        d.disks.put(uuid, host);
                        String label = diskLabel(e[1]);
                        if (label != null && !label.isEmpty()) {
                            d.names.put(uuid, label + " (" + name + ")");
                        }
                    }
                }
            } catch (Exception e) {
                // best effort: not every vCenter or host has storage pools
            }
            if (vsys == null) {
                continue;
            }
            List<Map<String, String>> entries;
            try {
                entries = vc.disksForVsan(vsys);
            } catch (Exception e) {
                log.info("disk inventory unavailable on " + name + ": "
                        + e.getClass().getSimpleName());
                continue;
            }
            for (Map<String, String> e : entries) {
                String uuid = e.get("uuid");
                if (!uuid.isEmpty() && !d.disks.containsKey(uuid)) {
                    d.disks.put(uuid, host);
                }
            }
            for (Map<String, String> e : entries) {
                String uuid = e.get("uuid");
                if (uuid.isEmpty()) {
                    continue;
                }
                String key = e.get("key");
                Map<String, String> info = new LinkedHashMap<>();
                info.put("firmware", e.get("revision").trim());
                info.put("model", e.get("model").trim());
                info.put("vendor", e.get("vendor").trim());
                info.put("driver", driverForLun.getOrDefault(key, ""));
                String hbaName = hbaForLun.getOrDefault(key, "");
                info.put("driver_version", hclVersion.getOrDefault(hbaName, ""));
                Map<String, String> kept = new LinkedHashMap<>();
                for (Map.Entry<String, String> i : info.entrySet()) {
                    if (!i.getValue().isEmpty()) {
                        kept.put(i.getKey(), i.getValue());
                    }
                }
                d.hw.put(uuid, kept);
            }
        }
        return d;
    }

    String signature() {
        return String.valueOf(new java.util.TreeSet<>(disks.keySet()));
    }
}
