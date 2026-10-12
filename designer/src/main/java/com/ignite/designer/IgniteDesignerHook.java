package com.ignite.designer;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.DesignerContext;

/**
 * Designer Hook — entry point for the Ignite module in the Designer.
 *
 * Minimal implementation; Ignite has no Designer-specific UI in v1.
 */
public class IgniteDesignerHook extends AbstractDesignerModuleHook {

    private static final LoggerEx log = LoggerEx.newBuilder().build("ignite.designer.IgniteDesignerHook");

    @Override
    public void startup(DesignerContext context, LicenseState activationState) {
        log.info("Ignite module starting up in Designer.");
    }

    @Override
    public void shutdown() {
        log.info("Ignite module shutting down in Designer.");
    }
}
