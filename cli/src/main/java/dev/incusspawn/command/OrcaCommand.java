package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.config.ProjectConfig;
import dev.incusspawn.orca.IncusOrcaBackend;
import dev.incusspawn.orca.OrcaRecipe;
import dev.incusspawn.orca.OrcaRuntime;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.StdioGuard;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code isx orca}: the commands of an Orca environment recipe, so Orca runs its agents in isx
 * instances. Orca runs each from the repository's checkout on this host: {@code create} with the
 * workspace in {@code ORCA_*} variables, the rest with a JSON payload on stdin. Stdout holds only
 * the one JSON object Orca parses; progress and errors go to stderr, which Orca logs.
 */
@CommandDefinition(
        name = "orca",
        description = "Run Orca's agents in isx instances: the commands of an Orca environment recipe",
        generateHelp = true,
        groupCommands = {
                OrcaCommand.Create.class,
                OrcaCommand.Suspend.class,
                OrcaCommand.Resume.class,
                OrcaCommand.Destroy.class
        }
)
public class OrcaCommand extends BaseCommand {

    /** The most a lifecycle payload may be: Orca's results are a few hundred bytes. */
    static final int MAX_PAYLOAD = 1 << 20;

    @Override
    protected CommandResult doExecute() {
        commandInvocation.println(commandInvocation.getHelpInfo());
        return CommandResult.SUCCESS;
    }

    static OrcaRuntime runtime() {
        return new OrcaRuntime(new IncusOrcaBackend(RuntimeServices.incus(), RuntimeServices.lockManager()));
    }

    static void print(OutputStream out, String json) throws IOException {
        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    static String readPayload(InputStream in) throws IOException {
        var bytes = in.readNBytes(MAX_PAYLOAD + 1);
        if (bytes.length > MAX_PAYLOAD) {
            throw new OrcaRecipe.RecipeException("the payload on stdin is larger than " + MAX_PAYLOAD + " bytes.");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** The template {@code --from} names, else the one the checkout's incus-spawn.yaml names, as {@code isx branch} finds it. */
    static String template(String from, Path repoPath) {
        if (from != null && !from.isBlank()) return from.strip();
        var project = ProjectConfig.findInDirectory(repoPath);
        if (project != null && project.getName() != null) return project.getName();
        throw new OrcaRecipe.RecipeException("no --from given, and no incus-spawn.yaml in " + repoPath
                + " names a template.");
    }

    @CommandDefinition(name = "create", generateHelp = true,
            description = "Branch an instance for an Orca workspace and print the SSH connection Orca opens")
    public static class Create extends BaseCommand {

        @Option(name = "from", description = "Template (or instance) to branch; default: the one the checkout's"
                + " incus-spawn.yaml names. It needs the orca tool.")
        String from;

        @Option(name = "project-root", description = "Checkout in the instance Orca opens (default: the template's"
                + " clone of the repository, else a clone at ~/<repo>)")
        String projectRoot;

        @OptionList(name = "account", description = "Credential account to use, as <namespace>=<account>."
                + " Repeatable; overrides the template's choice.")
        List<String> accounts;

        @Option(name = "proxy-only", hasValue = false, description = "Restrict network to the host proxy only")
        boolean proxyOnly;

        @Option(name = "cpu", description = "CPU core limit (default: as isx branch)")
        Integer cpu;

        @Option(name = "memory", description = "Memory limit, e.g. '8GB' (default: as isx branch)")
        String memory;

        @Option(name = "disk", description = "Disk size limit (default: as isx branch)")
        String disk;

        @Override
        protected CommandResult doExecute() throws Exception {
            var guard = StdioGuard.install();
            var context = OrcaRecipe.Context.fromEnv(System.getenv(), Path.of("").toAbsolutePath());
            var options = new OrcaRuntime.CreateOptions(template(from, context.repoPath()), projectRoot, accounts,
                    proxyOnly ? NetworkMode.PROXY_ONLY : NetworkMode.FULL, cpu, memory, disk);
            // Inside create, so a result that cannot be written deletes the branch Orca never heard of
            runtime().create(context, options, json -> {
                try {
                    print(guard.protocolOut, json);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException("could not write the result for Orca: " + e.getMessage(), e);
                }
            });
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(name = "suspend", generateHelp = true,
            description = "Stop the instance of the Orca runtime the payload on stdin names")
    public static class Suspend extends BaseCommand {
        @Override
        protected CommandResult doExecute() throws Exception {
            var guard = StdioGuard.install();
            runtime().suspend(readPayload(guard.protocolIn));
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(name = "resume", generateHelp = true,
            description = "Start the instance of the Orca runtime the payload on stdin names, and print its connection")
    public static class Resume extends BaseCommand {
        @Override
        protected CommandResult doExecute() throws Exception {
            var guard = StdioGuard.install();
            print(guard.protocolOut, runtime().resume(readPayload(guard.protocolIn)));
            return CommandResult.SUCCESS;
        }
    }

    @CommandDefinition(name = "destroy", generateHelp = true,
            description = "Delete the instance of the Orca runtime the payload on stdin names")
    public static class Destroy extends BaseCommand {
        @Override
        protected CommandResult doExecute() throws Exception {
            var guard = StdioGuard.install();
            var deleted = runtime().destroy(readPayload(guard.protocolIn));
            BuildOutput.note(deleted ? "Deleted the runtime's instance." : "The runtime's instance was already gone.");
            return CommandResult.SUCCESS;
        }
    }
}
