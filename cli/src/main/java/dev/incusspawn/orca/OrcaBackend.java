package dev.incusspawn.orca;

import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.tool.ActionContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** What {@code isx orca} needs from Incus and the host: {@link IncusOrcaBackend}, or a fake in tests. */
public interface OrcaBackend {

    /** Not a config key: the instance's status ({@code Running}, {@code Stopped}), in the same map. */
    String STATUS = "status";

    /**
     * The source a branch would copy, as {@link BranchFlow#preflight} checked it: refused there,
     * with nothing created, when the branch could not be made.
     *
     * @param ssh   whether it was built with an SSH server, which Orca connects through
     * @param repos the repositories it was built with
     * @param preflight what {@link #create} branches; null in a fake
     */
    record Source(boolean ssh, List<ActionContext.RepoInfo> repos, BranchFlow.Preflight preflight) {}

    /** A hold released by closing it, which cannot fail. */
    interface Hold extends AutoCloseable {
        @Override
        void close();
    }

    /** Held from the source's lookup through the branch's start, so a rebuild cannot swap the template away meanwhile. */
    Hold holdTemplate(String template);

    Source preflight(BranchFlow.Request request);

    /** Copy, configure and start the branch {@code source} was checked for. */
    void create(Source source);

    /** Run {@code script} as agentuser in a login shell, its output shown as progress; its exit code. */
    int run(String instance, String script);

    /** The instance's {@code user.incus-spawn.*} config and its {@link #STATUS}, or null when there is none. */
    Map<String, String> instance(String name);

    void stop(String name);

    /** Start it if stopped and make it ready for use, as {@code isx shell} does before a shell. */
    void start(String name);

    /** Delete it if {@code stillWanted} holds for it as read under the delete's mark; whether it was deleted. */
    boolean destroyIf(String name, Predicate<Map<String, String>> stillWanted);

    /** The remote of the host checkout at {@code repo}, or null when it has none. */
    String hostRemote(Path repo);

    /** How Orca reaches the instance over SSH. */
    OrcaRecipe.Target target(String name);
}
