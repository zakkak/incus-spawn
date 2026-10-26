package dev.incusspawn.orca;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.util.BuildOutput;

import java.util.List;
import java.util.Map;

/**
 * The four commands of an Orca environment recipe, on an {@link OrcaBackend}.
 *
 * <p>{@code create} branches an instance for one Orca runtime and stamps it with the runtime's id
 * ({@link Metadata#ORCA_RUNTIME}). {@code suspend}, {@code resume} and {@code destroy} then act
 * only on the instance their payload names, and only while it carries that stamp: the payload
 * says which instance, the stamp says it is Orca's to stop or delete.
 */
public final class OrcaRuntime {

    /** The built-in tool that gives an instance what Orca's server needs ({@code tools/orca.yaml}). */
    static final String TOOL = "orca";

    private final OrcaBackend backend;

    public OrcaRuntime(OrcaBackend backend) {
        this.backend = backend;
    }

    /**
     * What {@code isx orca create} branches, beyond the template's defaults.
     *
     * @param template    the template (or instance) to branch
     * @param projectRoot the checkout in the instance Orca opens, or null to choose
     */
    public record CreateOptions(String template, String projectRoot, List<String> accounts,
                                NetworkMode network, Integer cpu, String memory, String disk) {
        public CreateOptions {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
            network = network == null ? NetworkMode.FULL : network;
        }
    }

    /**
     * Branch and start an instance for Orca's runtime, ready its checkout, and return the result
     * Orca parses. Every check that can refuse runs before anything is created; once the branch
     * exists, a failure deletes it again, since Orca destroys only what a result named.
     */
    public String create(OrcaRecipe.Context context, CreateOptions options) {
        var name = OrcaRecipe.instanceName(context.workspaceName(), context.instanceId());
        var request = new BranchFlow.Request(options.template(), name, false, null, options.network(), null,
                options.cpu(), options.memory(), options.disk(), options.accounts(), true,
                Map.of(Metadata.ORCA_RUNTIME, context.instanceId()));
        try (var held = backend.holdTemplate(options.template())) {
            var source = backend.preflight(request);
            if (!source.ssh()) {
                throw new OrcaRecipe.RecipeException("'" + options.template() + "' has no SSH server, which Orca"
                        + " connects through: add the " + TOOL + " tool to it, which brings sshd, and rebuild it.");
            }
            if (!source.tools().contains(TOOL)) {
                BuildOutput.warn("'" + options.template() + "' was not built with the " + TOOL + " tool. Orca's server"
                        + " needs tar and awk in the instance, which it installs.");
            }
            var remote = context.provisionedRoot() ? context.repoUrl() : backend.hostRemote(context.repoPath());
            var checkout = OrcaCheckout.plan(options.projectRoot(), remote, source.repos());
            var script = checkout.script(context);
            try {
                backend.create(source);
                BuildOutput.step("Preparing " + checkout.projectRoot() + " for Orca...");
                var exit = backend.run(name, script);
                if (exit != 0) {
                    throw new OrcaRecipe.RecipeException("preparing the checkout at " + checkout.projectRoot()
                            + " in " + name + " failed (exit " + exit + ").");
                }
                return OrcaRecipe.toJson(OrcaRecipe.result(context.schemaVersion(), checkout.projectRoot(),
                        backend.target(name), context.instanceId()));
            } catch (RuntimeException e) {
                rollBack(name, context.instanceId(), e);
                throw e;
            }
        }
    }

    private void rollBack(String name, String runtimeId, RuntimeException cause) {
        try {
            if (backend.destroyIf(name, config -> runtimeId.equals(config.get(Metadata.ORCA_RUNTIME)))) {
                BuildOutput.note("Removed " + name + ", which Orca will not get.");
            }
        } catch (RuntimeException e) {
            cause.addSuppressed(e);
            BuildOutput.warn("Could not remove " + name + " after the failed create: " + e.getMessage(),
                    "Remove it with: isx destroy " + name);
        }
    }

    /** Stop the runtime's instance; one already stopped is left as it is. */
    public void suspend(String payload) {
        var lifecycle = OrcaRecipe.parsePayload(payload, "suspend");
        var config = requireOwned(lifecycle);
        if (!"Stopped".equals(config.get(OrcaBackend.STATUS))) backend.stop(lifecycle.instance());
    }

    /** Start the runtime's instance and return the result again, which Orca reconnects with. */
    public String resume(String payload) {
        var lifecycle = OrcaRecipe.parsePayload(payload, "resume");
        requireOwned(lifecycle);
        if (lifecycle.projectRoot().isEmpty()) {
            throw new OrcaRecipe.RecipeException("the payload's recipe result has no projectRoot.");
        }
        backend.start(lifecycle.instance());
        return OrcaRecipe.toJson(OrcaRecipe.result(lifecycle.schemaVersion(), lifecycle.projectRoot(),
                backend.target(lifecycle.instance()), lifecycle.runtimeId()));
    }

    /**
     * Delete the runtime's instance; whether it was there to delete. One already gone is success,
     * so Orca can repeat a destroy that failed after the delete.
     */
    public boolean destroy(String payload) {
        var lifecycle = OrcaRecipe.parsePayload(payload, "destroy");
        var config = backend.instance(lifecycle.instance());
        if (config == null) return false;
        requireStamp(lifecycle, config);
        var deleted = backend.destroyIf(lifecycle.instance(),
                current -> lifecycle.runtimeId().equals(current.get(Metadata.ORCA_RUNTIME)));
        if (!deleted && backend.instance(lifecycle.instance()) != null) {
            throw new OrcaRecipe.RecipeException("'" + lifecycle.instance() + "' changed while it was being"
                    + " deleted, and is no longer stamped for this Orca runtime; isx left it.");
        }
        return deleted;
    }

    private Map<String, String> requireOwned(OrcaRecipe.Lifecycle lifecycle) {
        var config = backend.instance(lifecycle.instance());
        if (config == null) {
            throw new OrcaRecipe.RecipeException("there is no instance '" + lifecycle.instance() + "'.");
        }
        requireStamp(lifecycle, config);
        return config;
    }

    private static void requireStamp(OrcaRecipe.Lifecycle lifecycle, Map<String, String> config) {
        if (!lifecycle.runtimeId().equals(config.get(Metadata.ORCA_RUNTIME))) {
            throw new OrcaRecipe.RecipeException("'" + lifecycle.instance() + "' was not created by isx orca for"
                    + " this Orca runtime; isx leaves it alone.");
        }
    }
}
