package dev.incusspawn.orca;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.git.GitRemoteUtils;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.util.BuildOutput;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    private final ExitHooks exitHooks;

    /**
     * Work the JVM runs as it exits: Orca cancels a create by signalling it, which runs no catch.
     * {@link #RUNTIME} in use, a fake in tests.
     */
    public interface ExitHooks {
        /** Run {@code hook} if the process exits before the returned handle is closed. */
        OrcaBackend.Hold onExit(Runnable hook);

        ExitHooks RUNTIME = hook -> {
            var thread = new Thread(hook, "isx-orca-rollback");
            Runtime.getRuntime().addShutdownHook(thread);
            return () -> {
                try {
                    Runtime.getRuntime().removeShutdownHook(thread);
                } catch (IllegalStateException exiting) {
                    // The hook is running or about to: it decides, by the same flag, whether to act
                }
            };
        };
    }

    public OrcaRuntime(OrcaBackend backend) {
        this(backend, ExitHooks.RUNTIME);
    }

    public OrcaRuntime(OrcaBackend backend, ExitHooks exitHooks) {
        this.backend = backend;
        this.exitHooks = exitHooks;
    }

    /**
     * What {@code isx orca create} branches, beyond the template's defaults.
     *
     * @param template    the template (or instance) to branch, or null to {@linkplain #chooseTemplate choose}
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
     * Branch and start an instance for Orca's runtime, ready its checkout, and hand {@code emit} the
     * result Orca parses. Every check that can refuse runs before anything is created; once the
     * branch exists, a failure deletes it again, since Orca destroys only what a result named. So
     * does the process exiting first (Orca cancelling the create), and a result that could not be
     * written, which Orca never read.
     *
     * <p>A failure deletes only the instance this create made, by its {@link Metadata#ORCA_CREATE}
     * stamp: a second create Orca ran for the same runtime meanwhile finds the name taken, and its
     * failure must not delete the first one's instance.
     */
    public void create(OrcaRecipe.Context context, CreateOptions options, Consumer<String> emit) {
        var name = OrcaRecipe.instanceName(context.workspaceName(), context.instanceId());
        var nonce = HexFormat.of().formatHex(randomBytes());
        var remote = context.provisionedRoot() ? context.repoUrl() : backend.hostRemote(context.repoPath());
        var template = options.template() != null ? options.template() : chooseTemplate(remote, backend.templates());
        var request = new BranchFlow.Request(template, name, false, null, options.network(), null,
                options.cpu(), options.memory(), options.disk(), options.accounts(), true,
                Map.of(Metadata.ORCA_RUNTIME, context.instanceId(), Metadata.ORCA_CREATE, nonce));
        try (var held = backend.holdTemplate(template)) {
            var source = backend.preflight(request);
            if (!source.ssh()) {
                throw new OrcaRecipe.RecipeException("'" + template + "' has no SSH server, which Orca"
                        + " connects through: add the " + TOOL + " tool to it, which brings sshd, and rebuild it.");
            }
            if (!source.tools().contains(TOOL)) {
                BuildOutput.warn("'" + template + "' was not built with the " + TOOL + " tool. Orca's server"
                        + " needs tar and awk in the instance, which it installs.");
            }
            var checkout = OrcaCheckout.plan(options.projectRoot(), remote, source.repos());
            var script = checkout.script(context);
            // Set once by whichever settles the branch first: the result written, a failure, or an exit
            var settled = new AtomicBoolean();
            try (var hook = exitHooks.onExit(() -> {
                if (settled.compareAndSet(false, true)) rollBack(name, nonce, null);
            })) {
                try {
                    backend.create(source);
                    BuildOutput.step("Preparing " + checkout.projectRoot() + " for Orca...");
                    var exit = backend.run(name, script);
                    if (exit != 0) {
                        throw new OrcaRecipe.RecipeException("preparing the checkout at " + checkout.projectRoot()
                                + " in " + name + " failed (exit " + exit + ").");
                    }
                    emit.accept(OrcaRecipe.toJson(OrcaRecipe.result(context.schemaVersion(), checkout.projectRoot(),
                            backend.target(name), context.instanceId())));
                    settled.set(true);
                } catch (RuntimeException e) {
                    if (settled.compareAndSet(false, true)) rollBack(name, nonce, e);
                    throw e;
                }
            }
        }
    }

    /**
     * The template for a recipe that names none, as the isx plugin for Orca's does: it is one
     * recipe for every repository. Only a template built with the {@value #TOOL} tool is a
     * candidate. The one that clones the repository's remote wins, else the only candidate; with
     * none, or several and no clone to tell them apart, the user names one.
     *
     * @param remote the repository's remote, or null when it has none
     */
    static String chooseTemplate(String remote, List<OrcaBackend.Template> templates) {
        var candidates = templates.stream().filter(t -> t.tools().contains(TOOL)).toList();
        if (candidates.isEmpty()) {
            throw new OrcaRecipe.RecipeException("no template is built with the " + TOOL + " tool: add it to a"
                    + " template's tools and build it, or name a template with --from.");
        }
        if (remote != null) {
            var cloning = candidates.stream().filter(t -> t.repos().stream()
                    .anyMatch(r -> r.url() != null && GitRemoteUtils.urlsMatch(r.url(), remote))).toList();
            if (cloning.size() == 1) return cloning.getFirst().name();
            if (cloning.size() > 1) {
                throw new OrcaRecipe.RecipeException("several templates built with the " + TOOL + " tool clone this"
                        + " repository (" + names(cloning) + "): " + HOW_TO_NAME);
            }
        }
        if (candidates.size() == 1) return candidates.getFirst().name();
        throw new OrcaRecipe.RecipeException("several templates are built with the " + TOOL + " tool ("
                + names(candidates) + ") and none clones this repository: " + HOW_TO_NAME);
    }

    private static final String HOW_TO_NAME = "name one in the repository's incus-spawn.yaml, or with --from"
            + " in a recipe of its own orca.yaml.";

    private static String names(List<OrcaBackend.Template> templates) {
        return String.join(", ", templates.stream().map(OrcaBackend.Template::name).sorted().toList());
    }

    /** A SecureRandom per call: a static one would be seeded at native-image build time. */
    private static byte[] randomBytes() {
        var bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /** Delete the branch this create made, if it is still the one stamped with its {@code nonce}. */
    private void rollBack(String name, String nonce, RuntimeException cause) {
        try {
            if (backend.destroyIf(name, config -> nonce.equals(config.get(Metadata.ORCA_CREATE)))) {
                BuildOutput.note("Removed " + name + ", which Orca will not get.");
            }
        } catch (RuntimeException e) {
            if (cause != null) cause.addSuppressed(e);
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

    /**
     * Start the runtime's instance and return the result again, which Orca reconnects with. For
     * Orca's own worktree the target is made afresh, so an isx that moved since is found. A
     * provisioned root keeps the one it had: Orca refuses a resumed root whose SSH identity or
     * ProxyCommand changed.
     */
    public String resume(String payload) {
        var lifecycle = OrcaRecipe.parsePayload(payload, "resume");
        requireOwned(lifecycle);
        if (lifecycle.projectRoot().isEmpty()) {
            throw new OrcaRecipe.RecipeException("the payload's recipe result has no projectRoot.");
        }
        backend.start(lifecycle.instance());
        var kept = lifecycle.target();
        var target = lifecycle.schemaVersion() == OrcaRecipe.SCHEMA_PROVISIONED_ROOT
                && !kept.identityFile().isEmpty() && !kept.proxyCommand().isEmpty()
                ? kept : backend.target(lifecycle.instance());
        return OrcaRecipe.toJson(OrcaRecipe.result(lifecycle.schemaVersion(), lifecycle.projectRoot(), target,
                lifecycle.runtimeId()));
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
