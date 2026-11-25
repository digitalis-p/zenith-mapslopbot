package io.github.digitalisp.mapart.engine;

/**
 * Turns other ZenithProxy modules on and off by name (class simple name, case-insensitive, e.g.
 * "AntiAFK"). The engine disables {@code build.disableModules} while it is active and restores exactly
 * the ones it disabled. Implementations never touch the modules' persisted config.
 * Production: {@link ZenithModuleSwitch}; tests: an in-memory map.
 */
public interface ModuleSwitch {
    /** True if a module with this name exists and is currently enabled. Unknown names return false. */
    boolean isEnabled(String moduleName);

    /** Enables or disables the named module for this session only. Unknown names are ignored. */
    void setEnabled(String moduleName, boolean enabled);

    /** No modules (tests, or when module control is unavailable). */
    ModuleSwitch NONE = new ModuleSwitch() {
        @Override public boolean isEnabled(String moduleName) { return false; }
        @Override public void setEnabled(String moduleName, boolean enabled) { }
    };
}
