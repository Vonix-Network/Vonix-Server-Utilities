package network.vonix.serverutilities.features;

import net.minecraft.commands.CommandSourceStack;

import java.util.function.Predicate;

/**
 * Compatibility helper for the existing command declarations. Standalone VSU
 * has no remote feature registry, so feature keys are always enabled and all
 * access control is enforced by {@link PermissionGate}.
 */
public final class FeatureGate {
    private FeatureGate() {}

    public static Predicate<CommandSourceStack> requires(String featureKey) {
        return source -> true;
    }

    public static Predicate<CommandSourceStack> requires(String featureKey,
                                                         Predicate<CommandSourceStack> and) {
        return and;
    }
}
