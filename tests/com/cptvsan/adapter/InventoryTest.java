package com.cptvsan.adapter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Mirror updates, joins, cluster filter and the version floor. */
public final class InventoryTest {

    public static void main(String[] args) throws Exception {
        Check.yes(VcClient.versionOk("8.0.3"), "8.0.3");
        Check.yes(VcClient.versionOk("8.0.3.00500"), "8.0.3 build");
        Check.yes(VcClient.versionOk("9.0.0.0"), "9.0");
        Check.yes(VcClient.versionOk("9.1.1.0"), "9.1");
        Check.yes(!VcClient.versionOk("8.0.2.00300"), "8.0.2 refused");
        Check.yes(!VcClient.versionOk("8.0.0"), "8.0.0 refused");
        Check.yes(!VcClient.versionOk("7.0.3"), "7.0.3 refused");
        Check.yes(!VcClient.versionOk(""), "empty refused");

        try (FakeOps fake = new FakeOps(2, 2)) {
            VcClient vc = new VcClient(fake.vcBase(), "u", "p", Net.PLAIN, true, 10_000);
            vc.open();
            vc.checkVersion();
            Inventory inv = new Inventory();
            Inventory.SyncStats s = inv.sync(vc, 1000, 60);
            Check.yes(s.full, "first sync is full");
            Check.eq("first run", s.reason, "reason");
            Check.eq(1 + 2 + 4, s.objects, "1 cluster + 2 hosts + 4 VMs");
            List<Inventory.ClusterRef> cl = inv.clusterRefs();
            Check.eq(1, cl.size(), "one cluster");
            Check.eq("cluster-uuid-1", cl.get(0).vsanUuid, "vSAN uuid from the hosts");
            Check.eq(Arrays.asList("host-1", "host-2"), cl.get(0).hosts, "members");
            Inventory.Joined j = inv.join(cl);
            Check.eq("host-2", j.parents.get("hosts").get("node-uuid-2"), "node uuid to host");
            Check.eq("domain-c1", j.parents.get("clusters").get("cluster-uuid-1"), "cluster uuid");
            Check.eq("vm-1-1", j.parents.get("vms").get("iuuid-vm-1-1"), "instance uuid to VM");
            Check.eq("vm-1-1", j.parents.get("vms").get("uuid-vm-1-1"), "config uuid to VM");
            Check.eq("vm-2-2", j.parents.get("vdisks").get("vm-2-2/vm-2-2.vmdk"), "vdisk path");
            Check.eq("vm-2-2", j.parents.get("vdisks").get("/vm-2-2/vm-2-2.vmdk"), "vdisk slash form");
            Check.eq("vm-1-1 [Hard disk 1]", j.names.get("vm-1-1/vm-1-1.vmdk"), "vdisk name");
            Check.eq(4, j.morefs.get("VirtualMachine").size(), "4 VMs in scope");
            Check.eq(2, j.morefs.get("HostSystem").size(), "2 hosts in scope");

            // second sync with a live session: incremental, nothing changed
            int creates = fake.creates.get();
            s = inv.sync(vc, 2000, 60);
            Check.yes(!s.full, "incremental");
            Check.eq(0, s.objects, "no changes");
            Check.eq(creates, fake.creates.get(), "no new collector");
            // after the refresh interval: full again, old collector destroyed
            s = inv.sync(vc, 2000 + 61 * 60_000L, 60);
            Check.yes(s.full && s.reason.startsWith("periodic"), "safety refresh");
            // a new session: the collector died with it
            vc.login();
            s = inv.sync(vc, 3000 + 61 * 60_000L, 60);
            Check.yes(s.full && s.reason.equals("new vCenter session"), "new session: full");

            // an update: a VM leaves, a name changes
            Object upd = JsonReader.parse("{\"version\":\"2\",\"filterSet\":[{\"objectSet\":["
                    + "{\"kind\":\"leave\",\"obj\":{\"type\":\"VirtualMachine\",\"value\":\"vm-1-1\"}},"
                    + "{\"kind\":\"modify\",\"obj\":{\"type\":\"HostSystem\",\"value\":\"host-1\"},"
                    + "\"changeSet\":[{\"name\":\"name\",\"op\":\"assign\",\"val\":{\"_typeName\":"
                    + "\"string\",\"_value\":\"renamed\"}}]}]}]}");
            Check.eq(2, inv.applyUpdates(upd), "two objects touched");
            Check.eq(3, inv.vms().size(), "VM removed");
            Check.eq("renamed", inv.hosts().get("host-1").get("name"), "host renamed");
            Check.eq("host-1", inv.hosts().get("host-1") == null ? null : "host-1", "still there");
        }

        // unbox
        Check.eq("host-1", Inventory.unbox(JsonReader.parse(
                "{\"_typeName\":\"ManagedObjectReference\",\"type\":\"HostSystem\",\"value\":\"host-1\"}")),
                "MoRef to its moid");
        Check.eq(Arrays.asList("a", "b"), Inventory.unbox(JsonReader.parse(
                "{\"_typeName\":\"ArrayOfString\",\"_value\":[\"a\",\"b\"]}")), "array unboxed");

        // filter
        List<Inventory.ClusterRef> cs = Arrays.asList(
                new Inventory.ClusterRef("domain-c1", "Prod-East", Collections.<String>emptyList(), ""),
                new Inventory.ClusterRef("domain-c2", "prod-west", Collections.<String>emptyList(), ""),
                new Inventory.ClusterRef("domain-c3", "lab", Collections.<String>emptyList(), ""));
        Check.eq(3, Inventory.filterClusters(cs, Collections.<String>emptyList(),
                Collections.<String>emptyList()).size(), "empty include is everything");
        Check.eq(2, Inventory.filterClusters(cs, Arrays.asList("prod-*"),
                Collections.<String>emptyList()).size(), "wildcard, case-insensitive");
        Check.eq(1, Inventory.filterClusters(cs, Arrays.asList("domain-c3"),
                Collections.<String>emptyList()).size(), "MoRef exact");
        Check.eq(1, Inventory.filterClusters(cs, Arrays.asList("prod-*"),
                Arrays.asList("prod-west")).size(), "exclude wins");
        Check.done("InventoryTest");
    }
}
