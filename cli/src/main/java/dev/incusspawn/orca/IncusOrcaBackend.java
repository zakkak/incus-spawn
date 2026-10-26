package dev.incusspawn.orca;

import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.command.InstancePrep;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.lifecycle.InstanceDestroyer;
import dev.incusspawn.lifecycle.RuntimeSetup;
import dev.incusspawn.lifecycle.TemplateLock;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.tool.ActionResolver;
import dev.incusspawn.tui.InstanceLockManager;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.HostLock;
import dev.incusspawn.util.LineOutputStream;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** {@link OrcaBackend} on a real Incus, through the same flows as {@code isx branch}, {@code shell} and {@code destroy}. */
public final class IncusOrcaBackend implements OrcaBackend {

    /** How long reading the host checkout's remote may take: git reads one config file. */
    private static final long GIT_TIMEOUT_SECONDS = 10;

    private final IncusClient incus;
    private final InstanceLockManager locks;

    public IncusOrcaBackend(IncusClient incus, InstanceLockManager locks) {
        this.incus = incus;
        this.locks = locks;
    }

    @Override
    public Hold holdTemplate(String template) {
        try {
            return TemplateLock.reading(template, BuildOutput::step)::close;
        } catch (HostLock.HostLockException e) {
            throw new OrcaRecipe.RecipeException(e.getMessage());
        }
    }

    @Override
    public Source preflight(BranchFlow.Request request) {
        if (!incus.exists(request.source())) {
            throw new OrcaRecipe.RecipeException("there is no template or instance '" + request.source() + "' to branch.");
        }
        BranchFlow.Preflight preflight;
        try {
            preflight = BranchFlow.preflight(incus, request);
        } catch (BranchFlow.BranchException e) {
            throw new OrcaRecipe.RecipeException(e.getMessage());
        }
        var config = preflight.sourceInstance().path("config");
        var ssh = RuntimeSetup.hasSshCapability(config.pathText(Metadata.SSH_SETUP, ""),
                config.pathText(Metadata.BUILD_SOURCE, ""));
        var resolver = new ActionResolver(incus, RuntimeServices.toolDefLoader(), RuntimeServices.toolSetups(),
                preflight.defs());
        return new Source(ssh, resolver.collectInstalledTools(preflight.sourceInstance(), preflight.template()),
                resolver.collectRepos(preflight.sourceInstance(), preflight.template()), preflight);
    }

    @Override
    public void create(Source source) {
        try {
            BranchFlow.create(incus, source.preflight());
        } catch (BranchFlow.BranchException e) {
            throw new OrcaRecipe.RecipeException(e.getMessage());
        }
    }

    @Override
    public int run(String instance, String script) {
        var out = new LineOutputStream(line -> BuildOutput.step(BuildOutput.guestText(line)));
        var err = new LineOutputStream(line -> BuildOutput.step(BuildOutput.guestText(line)));
        try (out; err) {
            return incus.execScriptAsUser(instance, OrcaRecipe.USER, script, null, out, err);
        }
    }

    @Override
    public Map<String, String> instance(String name) {
        // Only a 404 means gone: a refused or failed read must not pass for a deleted instance
        JqValue raw;
        try {
            raw = incus.instanceMetadataOrThrow(name);
        } catch (IncusException e) {
            throw new OrcaRecipe.RecipeException("cannot read '" + name + "' from Incus: " + e.getMessage());
        }
        return raw == null || raw.isNull() ? null : configOf(raw);
    }

    private static Map<String, String> configOf(JqValue instance) {
        var result = new LinkedHashMap<String, String>();
        if (instance.path("config") instanceof JqObject config) {
            config.forEach((key, value) -> {
                if (key.startsWith(Metadata.PREFIX)) result.put(key, value.asText(""));
            });
        }
        var status = instance.pathText("status", "");
        if (!status.isEmpty()) result.put(STATUS, status);
        return result;
    }

    @Override
    public void stop(String name) {
        try (var held = lock(name, Metadata.OP_STOPPING)) {
            incus.setPendingOperation(name, Metadata.OP_STOPPING);
            try {
                incus.stop(name);
            } finally {
                incus.clearPendingOperation(name);
            }
        } catch (IncusException e) {
            throw new OrcaRecipe.RecipeException("could not stop '" + name + "': " + e.getMessage());
        }
    }

    @Override
    public void start(String name) {
        try (var held = lock(name, "starting")) {
            InstancePrep.prepare(incus, name, BuildOutput::step);
        } catch (InstancePrep.Refused | IncusException e) {
            throw new OrcaRecipe.RecipeException("could not start '" + name + "': " + e.getMessage());
        }
    }

    @Override
    public boolean destroyIf(String name, Predicate<Map<String, String>> stillWanted) {
        boolean deleted;
        try (var held = lock(name, Metadata.OP_DELETING)) {
            deleted = InstanceDestroyer.deleteHeldIf(incus, name, instance -> stillWanted.test(configOf(instance)));
        } catch (IncusException e) {
            throw new OrcaRecipe.RecipeException("could not delete '" + name + "': " + e.getMessage());
        }
        if (deleted) InstanceDestroyer.refreshProxy();
        return deleted;
    }

    private InstanceLockManager.LockHandle lock(String name, String operation) {
        try {
            return locks.tryAcquire(name, operation).orElseThrow(() -> new OrcaRecipe.RecipeException(
                    "'" + name + "' is locked by another isx process; try again."));
        } catch (UncheckedIOException e) {
            throw new OrcaRecipe.RecipeException("cannot lock '" + name + "': " + e.getCause().getMessage());
        }
    }

    /**
     * The checkout's {@code origin}, else its only remote, as Git reads them: a linked worktree
     * and an included config are Git's to resolve. Null when it has neither, or is no checkout.
     */
    @Override
    public String hostRemote(Path repo) {
        var origin = git(repo, "remote", "get-url", "origin");
        if (origin != null && !origin.isEmpty()) return origin;
        var remotes = git(repo, "remote");
        if (remotes == null || remotes.isEmpty() || remotes.lines().count() != 1) return null;
        var only = git(repo, "remote", "get-url", remotes.strip());
        return only == null || only.isEmpty() ? null : only;
    }

    private static String git(Path repo, String... args) {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(java.util.List.of(args));
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(false)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? output.strip() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public OrcaRecipe.Target target(String name) {
        // Orca reconnects to a workspace it believes is awake, which a host restart may have stopped
        return new OrcaRecipe.Target(name, Environment.sshKeyFile().toString(), SshKeyManager.proxyCommand(name, true));
    }
}
