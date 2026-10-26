package dev.incusspawn.orca;

import dev.incusspawn.incus.InstanceNames;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrcaRecipeTest {

    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void anInstanceIsNamedForItsWorkspaceAndRuntime() {
        var name = OrcaRecipe.instanceName("Fix login bug!", "orca-5b0e");
        assertTrue(name.startsWith("orca-fix-login-bug-"), name);
        assertTrue(InstanceNames.isValid(name), name);
        assertEquals(name, OrcaRecipe.instanceName("Fix login bug!", "orca-5b0e"), "a repeated create asks for the same name");
        assertNotEquals(name, OrcaRecipe.instanceName("Fix login bug!", "orca-5b0f"), "two runtimes of one name differ");
    }

    @Test
    void aLongOrUnnamedWorkspaceStillMakesAValidName() {
        var longName = OrcaRecipe.instanceName("x".repeat(200) + " -- y", "orca-1");
        assertTrue(InstanceNames.isValid(longName), longName);
        var unnamed = OrcaRecipe.instanceName("", "orca-1");
        assertTrue(unnamed.matches("orca-[0-9a-f]{6}"), unnamed);
        var symbols = OrcaRecipe.instanceName("---", "orca-1");
        assertEquals(unnamed, symbols);
    }

    @ParameterizedTest
    @CsvSource({
            "https://github.com/acme/app.git, https://github.com/acme/app.git",
            "https://x-access-token:secret@github.com/acme/app, https://github.com/acme/app",
            "git@github.com:acme/app.git, https://github.com/acme/app.git",
            "github.com:acme/app, https://github.com/acme/app",
            "ssh://git@github.com:2222/acme/app.git, https://github.com/acme/app.git",
            "git://example.org/app.git, https://example.org/app.git",
            "https://git.example.org:8443/team/app.git, https://git.example.org:8443/team/app.git",
    })
    void theInstanceClonesOverHttpsWithoutCredentials(String remote, String clone) {
        assertEquals(clone, OrcaRecipe.guestCloneUrl(remote));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/home/me/app", "../app", "file:///home/me/app", "ext::sh -c touch% /tmp/x",
            "fd::3", "git@host:~me/app", "ssh://host/~me/app", "https://host", "https://host/", "app",
            "http://git.internal/app.git"})
    void aLocalOrHelperRemoteIsRefused(String remote) {
        var e = assertThrows(OrcaRecipe.RecipeException.class, () -> OrcaRecipe.guestCloneUrl(remote));
        assertTrue(e.getMessage().contains("--project-root"), e.getMessage());
    }

    @Test
    void aRefusedRemoteIsNamedWithoutItsCredentials() {
        var e = assertThrows(OrcaRecipe.RecipeException.class,
                () -> OrcaRecipe.guestCloneUrl("ftp://user:hunter2@host/app"));
        assertFalse(e.getMessage().contains("hunter2"), e.getMessage());
        assertEquals("http://host/a@b", OrcaRecipe.stripUserInfo("http://u:p@ss@host/a@b"));
        assertEquals("https://host/app", OrcaRecipe.stripUserInfo("https://host/app"));
    }

    @Test
    void runByHandACreateIsAWorktreeCreateWithARuntimeOfItsOwn() {
        var context = OrcaRecipe.Context.fromEnv(Map.of(), Path.of("/work/app"));
        assertEquals(OrcaRecipe.SCHEMA_WORKTREE, context.schemaVersion());
        assertTrue(context.instanceId().startsWith("isx-"), context.instanceId());
        assertEquals(Path.of("/work/app"), context.repoPath());
        assertNull(context.repoUrl());
    }

    @Test
    void orcasEnvironmentIsRead() {
        var context = OrcaRecipe.Context.fromEnv(Map.of(
                "ORCA_VM_INSTANCE_ID", "orca-42", "ORCA_WORKSPACE_NAME", "feature",
                "ORCA_REPO_PATH", "/src/app", "ORCA_RECIPE_RESULT_SCHEMA_VERSION", "1"), Path.of("/elsewhere"));
        assertEquals("orca-42", context.instanceId());
        assertEquals("feature", context.workspaceName());
        assertEquals(Path.of("/src/app"), context.repoPath());
        assertFalse(context.provisionedRoot());
    }

    @Test
    void aProvisionedRootNeedsThePinnedSource() {
        var env = Map.of("ORCA_RECIPE_RESULT_SCHEMA_VERSION", "2", "ORCA_REPO_URL", "https://github.com/acme/app",
                "ORCA_REPO_REF", "refs/heads/main", "ORCA_REPO_REF_HEAD", HEAD, "ORCA_REPO_BRANCH", "feature/x");
        assertTrue(OrcaRecipe.Context.fromEnv(env, Path.of("/w")).provisionedRoot());

        for (var key : List.of("ORCA_REPO_URL", "ORCA_REPO_REF", "ORCA_REPO_REF_HEAD", "ORCA_REPO_BRANCH")) {
            var without = new java.util.HashMap<>(env);
            without.remove(key);
            assertThrows(OrcaRecipe.RecipeException.class, () -> OrcaRecipe.Context.fromEnv(without, Path.of("/w")), key);
        }
        var notACommit = new java.util.HashMap<>(env);
        notACommit.put("ORCA_REPO_REF_HEAD", "main");
        assertThrows(OrcaRecipe.RecipeException.class, () -> OrcaRecipe.Context.fromEnv(notACommit, Path.of("/w")));
        var anOption = new java.util.HashMap<>(env);
        anOption.put("ORCA_REPO_BRANCH", "--upload-pack=x");
        assertThrows(OrcaRecipe.RecipeException.class, () -> OrcaRecipe.Context.fromEnv(anOption, Path.of("/w")));
    }

    @Test
    void anUnknownSchemaIsRefusedRatherThanAnsweredInAnother() {
        var e = assertThrows(OrcaRecipe.RecipeException.class,
                () -> OrcaRecipe.Context.fromEnv(Map.of("ORCA_RECIPE_RESULT_SCHEMA_VERSION", "3"), Path.of("/w")));
        assertTrue(e.getMessage().contains("update isx"), e.getMessage());
    }

    @Test
    void theResultHoldsOnlyWhatOrcasStrictSchemaAccepts() {
        var target = new OrcaRecipe.Target("orca-ws-1a2b3c", "/home/me/.config/incus-spawn/ssh/id_ed25519",
                "\"/usr/bin/isx\" ssh-proxy orca-ws-1a2b3c");
        var json = parse(OrcaRecipe.toJson(OrcaRecipe.result(1, "/home/agentuser/app", target, "orca-42")));

        assertEquals(List.of("schemaVersion", "connection", "userData"), keys(json));
        assertEquals(1, json.pathInt("schemaVersion", 0));
        var connection = json.path("connection");
        assertEquals(List.of("type", "projectRoot", "target"), keys(connection));
        assertEquals("ssh", connection.pathText("type", ""));
        assertEquals("/home/agentuser/app", connection.pathText("projectRoot", ""));
        var t = connection.path("target");
        assertEquals(List.of("label", "host", "configHost", "port", "username", "identityFile", "identitiesOnly",
                "proxyCommand"), keys(t));
        assertEquals("orca-ws-1a2b3c", t.pathText("host", ""));
        assertEquals(22, t.pathInt("port", 0));
        assertEquals("agentuser", t.pathText("username", ""));
        assertTrue(t.pathBoolean("identitiesOnly", false));
        assertEquals("\"/usr/bin/isx\" ssh-proxy orca-ws-1a2b3c", t.pathText("proxyCommand", ""));
        var userData = json.path("userData");
        assertEquals("incus-spawn", userData.pathText("provider", ""));
        assertEquals("orca-ws-1a2b3c", userData.pathText("resourceId", ""));
        assertEquals("orca-42", userData.pathText("runtimeId", ""));
    }

    @Test
    void aProvisionedRootResultSaysSo() {
        var target = new OrcaRecipe.Target("orca-a", "/k", "isx ssh-proxy orca-a");
        var json = parse(OrcaRecipe.toJson(OrcaRecipe.result(2, "/home/agentuser/app", target, "orca-42")));
        assertEquals(List.of("schemaVersion", "checkoutMode", "connection", "userData"), keys(json));
        assertEquals("provisioned-root", json.pathText("checkoutMode", ""));
    }

    @Test
    void aPayloadIsReadBackFromTheResultCreatePrinted() {
        var lifecycle = OrcaRecipe.parsePayload(payload("destroy", "orca-42", "incus-spawn"), "destroy");
        assertEquals("orca-ws-1a2b3c", lifecycle.instance());
        assertEquals("orca-42", lifecycle.runtimeId());
        assertEquals("/home/agentuser/app", lifecycle.projectRoot());
        assertEquals(1, lifecycle.schemaVersion());
    }

    @Test
    void withoutOrcasInstanceIdTheRuntimeComesFromUserData() {
        var lifecycle = OrcaRecipe.parsePayload(payload("resume", null, "incus-spawn"), "resume");
        assertEquals("orca-42", lifecycle.runtimeId());
    }

    @Test
    void aPayloadForAnotherCommandOrProviderIsRefused() {
        var wrongMode = assertThrows(OrcaRecipe.RecipeException.class,
                () -> OrcaRecipe.parsePayload(payload("suspend", "orca-42", "incus-spawn"), "destroy"));
        assertTrue(wrongMode.getMessage().contains("'suspend'"), wrongMode.getMessage());
        assertThrows(OrcaRecipe.RecipeException.class,
                () -> OrcaRecipe.parsePayload(payload("destroy", "orca-42", "docker"), "destroy"));
        assertThrows(OrcaRecipe.RecipeException.class, () -> OrcaRecipe.parsePayload("", "destroy"));
        assertThrows(OrcaRecipe.RecipeException.class, () -> OrcaRecipe.parsePayload("[1]", "destroy"));
        assertThrows(OrcaRecipe.RecipeException.class, () -> OrcaRecipe.parsePayload(
                payload("destroy", "orca-42", "incus-spawn").replace("orca-ws-1a2b3c", "../../etc"), "destroy"));
        assertThrows(OrcaRecipe.RecipeException.class, () -> OrcaRecipe.parsePayload(
                payload("resume", "orca-42", "incus-spawn").replace("{\"schemaVersion\":1,\"connection\"",
                        "{\"schemaVersion\":3,\"connection\""), "resume"));
    }

    /** A payload as Orca's {@code buildEphemeralVmRecipeLifecyclePayload} writes it. */
    static String payload(String mode, String instanceId, String provider) {
        return "{\"schemaVersion\":1,\"mode\":\"" + mode + "\",\"recipeId\":\"isx\","
                + (instanceId == null ? "" : "\"instanceId\":\"" + instanceId + "\",")
                + "\"workspaceName\":\"ws\",\"recipeResult\":{\"schemaVersion\":1,\"connection\":{\"type\":\"ssh\","
                + "\"projectRoot\":\"/home/agentuser/app\",\"target\":{\"label\":\"orca-ws-1a2b3c\","
                + "\"host\":\"orca-ws-1a2b3c\",\"port\":22,\"username\":\"agentuser\"}},"
                + "\"userData\":{\"provider\":\"" + provider + "\",\"resourceId\":\"orca-ws-1a2b3c\",\"runtimeId\":\"orca-42\"}}}\n";
    }

    private static JqValue parse(String json) {
        return JqValues.parseStrict(json);
    }

    private static List<String> keys(JqValue object) {
        var keys = new ArrayList<String>();
        ((JqObject) object).forEach((key, value) -> keys.add(key));
        return keys;
    }
}
