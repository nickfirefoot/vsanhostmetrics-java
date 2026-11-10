package com.cptvsan.adapter;

/**
 * The VMWARE resource kinds this pack writes onto. It never writes onto any
 * other kind, never creates one, and never asserts a relationship on one.
 */
enum TargetKind {
    CLUSTER("ClusterComputeResource"),
    HOST("HostSystem"),
    VM("VirtualMachine");

    /** The VMWARE adapter kind every target belongs to. */
    static final String ADAPTER_KIND = "VMWARE";

    /** The VMWARE resource kind key. */
    final String resourceKind;

    TargetKind(String resourceKind) {
        this.resourceKind = resourceKind;
    }
}
