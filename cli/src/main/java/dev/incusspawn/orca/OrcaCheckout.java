package dev.incusspawn.orca;

import dev.incusspawn.git.GitRemoteUtils;
import dev.incusspawn.incus.Container;
import dev.incusspawn.tool.ActionContext;

import java.util.HashSet;
import java.util.List;

/**
 * Where Orca's project lives in the instance, and the script that makes it so.
 *
 * <p>A repository the template already cloned is used where it is: the branch shares it
 * copy-on-write, so a create costs a fetch rather than a clone. Any other is cloned over HTTPS,
 * through the isx proxy, which signs in to GitHub; the instance never sees a host credential.
 *
 * @param projectRoot the checkout Orca opens, an absolute path in the instance
 * @param cloneUrl    where to clone it from when it is not there yet, or null when it must be
 * @param fromTemplate whether the template cloned it there
 */
public record OrcaCheckout(String projectRoot, String cloneUrl, boolean fromTemplate) {

    /**
     * The checkout for Orca's repository, whose remote is {@code remoteUrl}: the template's clone of
     * it, else a clone at {@code ~/<name>}, beside the template's own. Null {@code remoteUrl} with
     * no {@code projectRoot} cannot be planned.
     *
     * @param projectRoot {@code --project-root}, or null to choose
     * @param remoteUrl   Orca's remote, from {@code ORCA_REPO_URL} or the host checkout's {@code origin}
     * @param repos       the repositories the source was built with
     */
    public static OrcaCheckout plan(String projectRoot, String remoteUrl, List<ActionContext.RepoInfo> repos) {
        if (projectRoot != null) {
            var root = guestPath(projectRoot);
            var cloned = repos.stream().anyMatch(r -> r.path().equals(root));
            return new OrcaCheckout(root, remoteUrl == null ? null : OrcaRecipe.guestCloneUrl(remoteUrl), cloned);
        }
        if (remoteUrl == null) {
            throw new OrcaRecipe.RecipeException("Orca's repository has no remote isx can clone in the instance:"
                    + " give it an origin, add it to the template's repos, or give --project-root.");
        }
        for (var repo : repos) {
            if (repo.url() != null && GitRemoteUtils.urlsMatch(repo.url(), remoteUrl)) {
                return new OrcaCheckout(repo.path(), OrcaRecipe.guestCloneUrl(remoteUrl), true);
            }
        }
        var cloneUrl = OrcaRecipe.guestCloneUrl(remoteUrl);
        var name = GitRemoteUtils.repoNameFromUrl(cloneUrl);
        if (name.isEmpty() || name.startsWith(".")) name = "project";
        var taken = new HashSet<String>();
        repos.forEach(r -> taken.add(r.path()));
        var root = OrcaRecipe.HOME + "/" + name;
        // A template repository of the same name from another remote (a fork's upstream) keeps its path
        for (int i = 2; taken.contains(root); i++) root = OrcaRecipe.HOME + "/" + name + "-" + i;
        return new OrcaCheckout(root, cloneUrl, false);
    }

    /** {@code path} in the instance: {@code ~/} is agentuser's home, and anything else must be absolute. */
    static String guestPath(String path) {
        var p = path.strip();
        if (p.equals("~")) return OrcaRecipe.HOME;
        if (p.startsWith("~/")) p = OrcaRecipe.HOME + p.substring(1);
        if (!p.startsWith("/") || OrcaRecipe.hasControl(p)) {
            throw new OrcaRecipe.RecipeException("--project-root must be an absolute path or start with ~/.");
        }
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    /**
     * The script agentuser runs in the instance, in a login shell, so Git goes through the isx
     * proxy with the GitHub credential helper. Prompts are off: a remote that wants a password
     * fails at once instead of waiting for an answer nobody can give.
     *
     * <p>The checkout is cloned if missing, else fetched so Orca's worktree starts from the
     * remote's current branches; a fetch that fails leaves the template's clone as it was, with a
     * warning. For a provisioned root the workspace's branch is then checked out at exactly the
     * commit Orca pinned, fetched from the URL Orca resolved it on.
     */
    public String script(OrcaRecipe.Context context) {
        var s = new StringBuilder("set -e\nexport GIT_TERMINAL_PROMPT=0\n");
        s.append("root=").append(Container.shellQuote(projectRoot)).append('\n');
        s.append("if [ -e \"$root/.git\" ]; then\n");
        s.append("  git -C \"$root\" fetch --quiet --prune origin || echo \"isx: could not fetch origin in $root;"
                + " continuing with what it has\" >&2\n");
        if (cloneUrl == null) {
            s.append("else\n  echo \"isx: no repository at $root, and no remote to clone it from\" >&2\n  exit 1\n");
        } else {
            s.append("elif [ -e \"$root\" ]; then\n  echo \"isx: $root exists and is not a Git checkout\" >&2\n  exit 1\n");
            s.append("else\n  git clone -- ").append(Container.shellQuote(cloneUrl)).append(" \"$root\"\n");
        }
        s.append("fi\n");
        if (context.provisionedRoot()) {
            s.append("git -C \"$root\" fetch --quiet -- ").append(Container.shellQuote(OrcaRecipe.guestCloneUrl(context.repoUrl())))
                    .append(' ').append(Container.shellQuote(context.repoRef())).append('\n');
            s.append("git -C \"$root\" cat-file -e ").append(Container.shellQuote(context.refHead() + "^{commit}")).append('\n');
            s.append("git -C \"$root\" checkout --quiet -B ").append(Container.shellQuote(context.branch()))
                    .append(' ').append(Container.shellQuote(context.refHead())).append(" --\n");
        }
        return s.toString();
    }
}
