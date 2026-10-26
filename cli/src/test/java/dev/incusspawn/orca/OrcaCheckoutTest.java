package dev.incusspawn.orca;

import dev.incusspawn.tool.ActionContext;
import dev.incusspawn.tool.HermeticGit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrcaCheckoutTest {

    private static final List<ActionContext.RepoInfo> TEMPLATE_REPOS = List.of(
            new ActionContext.RepoInfo("quarkus", "/home/agentuser/quarkus", "https://github.com/quarkusio/quarkus.git"),
            new ActionContext.RepoInfo("docs", "/home/agentuser/src/docs", "https://github.com/acme/docs"));

    private static final OrcaRecipe.Context WORKTREE = OrcaRecipe.Context.fromEnv(Map.of(), Path.of("/w"));

    @TempDir
    Path dir;
    private Path gitConfig;

    @BeforeEach
    void emptyGitConfig() throws IOException {
        gitConfig = Files.writeString(dir.resolve("gitconfig"), "");
    }

    @Test
    void theTemplatesCloneOfTheRepositoryIsUsedWhereItIs() {
        var checkout = OrcaCheckout.plan(null, "git@github.com:quarkusio/quarkus.git", TEMPLATE_REPOS);
        assertEquals("/home/agentuser/quarkus", checkout.projectRoot());
        assertTrue(checkout.fromTemplate());
    }

    @Test
    void anyOtherRepositoryIsClonedBesideTheTemplates() {
        var checkout = OrcaCheckout.plan(null, "git@github.com:me/tool.git", TEMPLATE_REPOS);
        assertEquals("/home/agentuser/tool", checkout.projectRoot());
        assertEquals("https://github.com/me/tool.git", checkout.cloneUrl());
        assertFalse(checkout.fromTemplate());
    }

    @Test
    void aForkDoesNotLandOnItsUpstreamsClone() {
        var checkout = OrcaCheckout.plan(null, "https://github.com/me/quarkus.git", TEMPLATE_REPOS);
        assertEquals("/home/agentuser/quarkus-2", checkout.projectRoot());
        assertFalse(checkout.fromTemplate());
    }

    @Test
    void aGivenProjectRootIsTakenInTheInstancesHome() {
        var checkout = OrcaCheckout.plan("~/src/docs/", null, TEMPLATE_REPOS);
        assertEquals("/home/agentuser/src/docs", checkout.projectRoot());
        assertNull(checkout.cloneUrl());
        assertTrue(checkout.fromTemplate());
        assertThrows(OrcaRecipe.RecipeException.class, () -> OrcaCheckout.plan("src/docs", null, TEMPLATE_REPOS));
    }

    @Test
    void noRemoteAndNoProjectRootCannotBePlanned() {
        var e = assertThrows(OrcaRecipe.RecipeException.class, () -> OrcaCheckout.plan(null, null, TEMPLATE_REPOS));
        assertTrue(e.getMessage().contains("--project-root"), e.getMessage());
    }

    @Test
    void theScriptQuotesWhatItIsGiven() throws Exception {
        var checkout = new OrcaCheckout("/home/agentuser/it's here", "https://example.org/a'b.git", false);
        var script = checkout.script(WORKTREE);
        assertEquals(0, bash(dir, "-n", "-c", script).exit(), script);
        assertTrue(script.contains("root='/home/agentuser/it'\"'\"'s here'"), script);
    }

    @Test
    void aProvisionedRootChecksOutThePinnedCommit() {
        var context = OrcaRecipe.Context.fromEnv(Map.of("ORCA_RECIPE_RESULT_SCHEMA_VERSION", "2",
                "ORCA_REPO_URL", "git@github.com:acme/app.git", "ORCA_REPO_REF", "refs/heads/main",
                "ORCA_REPO_REF_HEAD", "0123456789abcdef0123456789abcdef01234567", "ORCA_REPO_BRANCH", "feature/x"),
                Path.of("/w"));
        var script = new OrcaCheckout("/home/agentuser/app", "https://github.com/acme/app.git", false).script(context);
        assertTrue(script.contains("fetch --quiet -- 'https://github.com/acme/app.git' 'refs/heads/main'"), script);
        assertTrue(script.contains("checkout --quiet -B 'feature/x' '0123456789abcdef0123456789abcdef01234567' --"), script);
    }

    // The script runs as agentuser in a login shell in the instance; here it runs in bash against
    // local repositories, which is what the instance's Git sees of a checkout either way.

    @Test
    void anExistingCheckoutIsFetchedSoOrcasWorktreeStartsFromTheRemote() throws Exception {
        var upstream = dir.resolve("upstream");
        git(dir, "init", "-q", "-b", "main", upstream.toString());
        commit(upstream, "one");
        var clone = dir.resolve("clone");
        git(dir, "clone", "-q", upstream.toString(), clone.toString());
        commit(upstream, "two");

        var run = bash(dir, "-c", new OrcaCheckout(clone.toString(), null, true).script(WORKTREE));

        assertEquals(0, run.exit(), run.output());
        assertEquals(git(upstream, "rev-parse", "main"), git(clone, "rev-parse", "origin/main"));
    }

    @Test
    void aFetchThatFailsLeavesTheTemplatesCloneAsItWas() throws Exception {
        var clone = dir.resolve("clone");
        git(dir, "init", "-q", "-b", "main", clone.toString());
        commit(clone, "one");
        git(clone, "remote", "add", "origin", dir.resolve("gone").toString());

        var run = bash(dir, "-c", new OrcaCheckout(clone.toString(), null, true).script(WORKTREE));

        assertEquals(0, run.exit(), run.output());
        assertTrue(run.output().contains("could not fetch origin"), run.output());
    }

    @Test
    void aDirectoryThatIsNoCheckoutIsNotClonedOver() throws Exception {
        var root = Files.createDirectories(dir.resolve("app"));
        var run = bash(dir, "-c", new OrcaCheckout(root.toString(), "https://example.invalid/app.git", false).script(WORKTREE));
        assertEquals(1, run.exit(), run.output());
        assertTrue(run.output().contains("is not a Git checkout"), run.output());
    }

    @Test
    void noCheckoutAndNothingToCloneFails() throws Exception {
        var run = bash(dir, "-c", new OrcaCheckout(dir.resolve("app").toString(), null, false).script(WORKTREE));
        assertEquals(1, run.exit(), run.output());
        assertTrue(run.output().contains("no remote to clone it from"), run.output());
    }

    private record Run(int exit, String output) {}

    private Run bash(Path dir, String... args) throws IOException, InterruptedException {
        var command = new java.util.ArrayList<String>();
        command.add("bash");
        command.addAll(List.of(args));
        var pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        HermeticGit.configure(pb, gitConfig);
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        return new Run(process.exitValue(), output);
    }

    private String git(Path dir, String... args) throws IOException, InterruptedException {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        var pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        HermeticGit.configure(pb, gitConfig);
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private void commit(Path repo, String message) throws IOException, InterruptedException {
        Files.writeString(repo.resolve("file"), message);
        git(repo, "add", "file");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@example.org", "commit", "-q", "-m", message);
    }
}
