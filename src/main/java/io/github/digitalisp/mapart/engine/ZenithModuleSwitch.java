package io.github.digitalisp.mapart.engine;

import com.zenith.module.api.Module;

import java.util.Optional;

import static com.zenith.Globals.MODULE;

/**
 * {@link ModuleSwitch} over ZenithProxy's module manager. Modules are found by class simple name
 * (case-insensitive), which covers built-in modules ("AntiAFK", "KillAura", ...) and plugin modules.
 * {@code Module.setEnabled} does not write the module's config, so restoring is exact.
 */
public final class ZenithModuleSwitch implements ModuleSwitch {
    @Override
    public boolean isEnabled(String moduleName) {
        return find(moduleName).map(Module::isEnabled).orElse(false);
    }

    @Override
    public void setEnabled(String moduleName, boolean enabled) {
        find(moduleName).ifPresent(m -> m.setEnabled(enabled));
    }

    private static Optional<Module> find(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        for (Module m : MODULE.getModules()) {
            if (m instanceof MapArtModule) continue; // never switch ourselves off
            if (m.getClass().getSimpleName().equalsIgnoreCase(name.trim())) return Optional.of(m);
        }
        return Optional.empty();
    }
}
