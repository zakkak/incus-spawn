package dev.incusspawn.orca;

import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.tool.ActionContext;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrcaRuntimeTest {

    private static final String RUNTIME = "orca-42";
    private static final OrcaRecipe.Context CONTEXT = OrcaRecipe.Context.fromEnv(Map.of(
            "ORCA_VM_INSTANCE_ID", RUNTIME, "ORCA_WORKSPACE_NAME", "ws", "ORCA_REPO_PATH", "/src/app"), Path.of("/w"));
    private static final String NAME = OrcaRecipe.instanceName("ws", RUNTIME);
    private static final OrcaRuntime.CreateOptions FROM_TEMPLATE =
            new OrcaRuntime.CreateOptions("tpl-dev", null, List.of("claude=work"), null, null, null, null);

    private final FakeBackend backend = new FakeBackend();
    private final OrcaRuntime runtime = new OrcaRuntime(backend);

    @Test
    void createBranchesAStampedInstanceAndPrintsItsConnection() {
        var json = JqValues.parseStrict(runtime.create(CONTEXT, FROM_TEMPLATE));

        var request = backend.requests.getFirst();
        assertEquals("tpl-dev", request.source());
        assertEquals(NAME, request.name());
        assertEquals(Boolean.FALSE, request.gui(), "nobody watches an Orca instance's display");
        assertEquals(List.of("claude=work"), request.accountOverrides());
        assertEquals(Map.of(Metadata.ORCA_RUNTIME, RUNTIME), request.extraConfig());
        assertEquals(List.of("hold tpl-dev", "preflight", "create", "run " + NAME, "release tpl-dev"), backend.calls);
        assertTrue(backend.scripts.getFirst().contains("git clone -- 'https://github.com/acme/app.git'"),
                backend.scripts.getFirst());

        assertEquals("/home/agentuser/app", json.path("connection").pathText("projectRoot", ""));
        assertEquals(NAME, json.path("connection").path("target").pathText("host", ""));
        assertEquals(RUNTIME, json.path("userData").pathText("runtimeId", ""));
    }

    @Test
    void aTemplateWithoutSshdIsRefusedBeforeAnythingIsCreated() {
        backend.ssh = false;
        var e = assertThrows(OrcaRecipe.RecipeException.class, () -> runtime.create(CONTEXT, FROM_TEMPLATE));
        assertTrue(e.getMessage().contains("sshd"), e.getMessage());
        assertFalse(backend.calls.contains("create"));
        assertEquals("release tpl-dev", backend.calls.getLast());
    }

    @Test
    void aRepositoryWithNoRemoteIsRefusedBeforeAnythingIsCreated() {
        backend.remote = null;
        assertThrows(OrcaRecipe.RecipeException.class, () -> runtime.create(CONTEXT, FROM_TEMPLATE));
        assertFalse(backend.calls.contains("create"));
    }

    @Test
    void aFailedCheckoutDeletesTheBranchOrcaWillNeverHear() {
        backend.exit = 128;
        var e = assertThrows(OrcaRecipe.RecipeException.class, () -> runtime.create(CONTEXT, FROM_TEMPLATE));
        assertTrue(e.getMessage().contains("exit 128"), e.getMessage());
        assertTrue(backend.calls.contains("destroy " + NAME), backend.calls.toString());
        assertNull(backend.instances.get(NAME));
    }

    @Test
    void aProvisionedRootClonesFromTheUrlOrcaResolved() {
        backend.remote = "git@github.com:someone-else/unused.git";
        var context = OrcaRecipe.Context.fromEnv(Map.of("ORCA_VM_INSTANCE_ID", RUNTIME, "ORCA_WORKSPACE_NAME", "ws",
                "ORCA_RECIPE_RESULT_SCHEMA_VERSION", "2", "ORCA_REPO_URL", "https://github.com/acme/app.git",
                "ORCA_REPO_REF", "refs/heads/main", "ORCA_REPO_REF_HEAD", "0123456789abcdef0123456789abcdef01234567",
                "ORCA_REPO_BRANCH", "ws"), Path.of("/w"));
        var json = JqValues.parseStrict(runtime.create(context, FROM_TEMPLATE));
        assertEquals("provisioned-root", json.pathText("checkoutMode", ""));
        assertFalse(backend.scripts.getFirst().contains("someone-else"), backend.scripts.getFirst());
    }

    @Test
    void suspendStopsOnlyTheRuntimesInstance() {
        backend.instance(NAME, RUNTIME, "Running");
        runtime.suspend(OrcaRecipeTest.payload("suspend", RUNTIME, "incus-spawn").replace("orca-ws-1a2b3c", NAME));
        assertEquals(List.of("stop " + NAME), backend.calls);

        backend.calls.clear();
        runtime.suspend(OrcaRecipeTest.payload("suspend", RUNTIME, "incus-spawn").replace("orca-ws-1a2b3c", NAME));
        assertEquals(List.of(), backend.calls, "already stopped");
    }

    @Test
    void anInstanceStampedForAnotherRuntimeIsLeftAlone() {
        backend.instance(NAME, "orca-other", "Running");
        var payload = OrcaRecipeTest.payload("destroy", RUNTIME, "incus-spawn").replace("orca-ws-1a2b3c", NAME);
        var e = assertThrows(OrcaRecipe.RecipeException.class, () -> runtime.destroy(payload));
        assertTrue(e.getMessage().contains("leaves it alone"), e.getMessage());
        assertNotNull(backend.instances.get(NAME));

        backend.instance("dev", null, "Running");
        assertThrows(OrcaRecipe.RecipeException.class, () -> runtime.suspend(
                OrcaRecipeTest.payload("suspend", RUNTIME, "incus-spawn").replace("orca-ws-1a2b3c", "dev")));
        assertEquals(List.of(), backend.calls);
    }

    @Test
    void resumeStartsTheInstanceAndPrintsTheConnectionAgain() {
        backend.instance(NAME, RUNTIME, "Stopped");
        var json = JqValues.parseStrict(runtime.resume(
                OrcaRecipeTest.payload("resume", RUNTIME, "incus-spawn").replace("orca-ws-1a2b3c", NAME)));
        assertEquals(List.of("start " + NAME), backend.calls);
        assertEquals("/home/agentuser/app", json.path("connection").pathText("projectRoot", ""));
        assertEquals(NAME, json.path("userData").pathText("resourceId", ""));
        assertEquals(RUNTIME, json.path("userData").pathText("runtimeId", ""));
    }

    @Test
    void destroyDeletesTheRuntimesInstanceAndAGoneOneIsSuccess() {
        backend.instance(NAME, RUNTIME, "Running");
        var payload = OrcaRecipeTest.payload("destroy", RUNTIME, "incus-spawn").replace("orca-ws-1a2b3c", NAME);
        assertTrue(runtime.destroy(payload));
        assertNull(backend.instances.get(NAME));
        assertFalse(runtime.destroy(payload), "a repeated destroy finds it gone");
    }

    /** Instances in memory; every call that acts is recorded. */
    private static final class FakeBackend implements OrcaBackend {
        final List<String> calls = new ArrayList<>();
        final List<BranchFlow.Request> requests = new ArrayList<>();
        final List<String> scripts = new ArrayList<>();
        final Map<String, Map<String, String>> instances = new HashMap<>();
        boolean ssh = true;
        String remote = "git@github.com:acme/app.git";
        int exit = 0;

        void instance(String name, String runtime, String status) {
            var config = new LinkedHashMap<String, String>();
            if (runtime != null) config.put(Metadata.ORCA_RUNTIME, runtime);
            config.put(STATUS, status);
            instances.put(name, config);
        }

        @Override
        public Hold holdTemplate(String template) {
            calls.add("hold " + template);
            return () -> calls.add("release " + template);
        }

        @Override
        public Source preflight(BranchFlow.Request request) {
            calls.add("preflight");
            requests.add(request);
            return new Source(ssh, List.of(new ActionContext.RepoInfo("other", "/home/agentuser/other",
                    "https://github.com/acme/other")), null);
        }

        @Override
        public void create(Source source) {
            calls.add("create");
            var request = requests.getLast();
            var config = new LinkedHashMap<>(request.extraConfig());
            config.put(STATUS, "Running");
            instances.put(request.name(), config);
        }

        @Override
        public int run(String instance, String script) {
            calls.add("run " + instance);
            scripts.add(script);
            return exit;
        }

        @Override
        public Map<String, String> instance(String name) {
            return instances.get(name);
        }

        @Override
        public void stop(String name) {
            calls.add("stop " + name);
            instances.get(name).put(STATUS, "Stopped");
        }

        @Override
        public void start(String name) {
            calls.add("start " + name);
            instances.get(name).put(STATUS, "Running");
        }

        @Override
        public boolean destroyIf(String name, Predicate<Map<String, String>> stillWanted) {
            var config = instances.get(name);
            if (config == null || !stillWanted.test(config)) return false;
            calls.add("destroy " + name);
            instances.remove(name);
            return true;
        }

        @Override
        public String hostRemote(Path repo) {
            return remote;
        }

        @Override
        public OrcaRecipe.Target target(String name) {
            return new OrcaRecipe.Target(name, "/home/me/.config/incus-spawn/ssh/id_ed25519", "isx ssh-proxy " + name);
        }
    }
}
