package com.ignite.common;

/**
 * Module-wide constants shared across all scopes (Gateway, Designer).
 */
public class IgniteModule {

    /** Unique module ID — must match id in build.gradle.kts */
    public static final String MODULE_ID = "com.ignite.module";

    /** Human-readable module name */
    public static final String MODULE_NAME = "Ignite";

    /** URL prefix for all Ignite API endpoints */
    public static final String API_PREFIX = "/api/ignite";

    private IgniteModule() {}
}
