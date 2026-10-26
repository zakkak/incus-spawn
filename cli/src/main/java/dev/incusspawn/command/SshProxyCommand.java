package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.StdioGuard;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.time.Duration;

@CommandDefinition(
        name = "ssh-proxy",
        description = "SSH ProxyCommand that tunnels through the Incus exec API",
        generateHelp = true
)
public class SshProxyCommand extends BaseCommand {

    @Argument(description = "Instance name", required = true)
    String instance;

    @Option(name = "start", hasValue = false,
            description = "Start the instance first if it is stopped, as isx shell does")
    boolean start;

    /** How long a connection waits for another isx process that is starting the instance. */
    static final Duration START_WAIT = Duration.ofMinutes(2);

    @Override
    protected CommandResult doExecute() throws Exception {
        // Stdin and stdout are the SSH connection: nothing isx says while starting may reach them
        var guard = StdioGuard.install();
        var incus = RuntimeServices.incus();
        requireRunning(incus);
        int exitCode = incus.execBidirectional(instance, 0, 0, "/",
                guestCommand(),
                guard.protocolIn, guard.protocolOut, guard.protocolErr);
        return CommandResult.valueOf(exitCode);
    }

    /** A socket listening on port 22 (hex {@code 0016}), as a line of {@code /proc/net/tcp} or {@code tcp6} shows it. */
    static final String SSHD_LISTENING = ":0016 [0-9A-F]*:0000 0A";
    /** At most how many seconds the guest waits for sshd to listen before connecting anyway. */
    static final int SSHD_WAIT_SECONDS = 20;

    /**
     * What the exec runs in the instance: the end of the ssh connection.
     * <p>
     * A start returns once the guest answers an exec, about a second before sshd listens, so an
     * immediate {@code ssh} would be refused (#1699). The wait is here rather than on every start:
     * only an SSH connection pays it. It has to happen before {@code nc} runs, since ssh has already
     * written its banner to stdin and a retried exec would lose it. The probe reads the kernel's
     * socket table, so it costs one {@code grep} when sshd is up and logs no connection in sshd.
     * It waits only while systemd is still starting the guest: after that a stopped or failed sshd
     * will not come, and {@code nc} reports the refusal at once, as before. The deadline is wall
     * clock, since early in boot a {@code systemctl} call to a busy PID 1 can take far longer than
     * a pass's sleep; it is set on the first miss, so a ready instance forks no {@code date}.
     */
    static String[] guestCommand() {
        return new String[]{"sh", "-c",
                "end=; until grep -qs '" + SSHD_LISTENING + "' /proc/net/tcp6 /proc/net/tcp; do"
                        + " case $(systemctl is-system-running 2>/dev/null) in initializing|starting) ;; *) break ;; esac;"
                        + " now=$(date +%s); : ${end:=$((now + " + SSHD_WAIT_SECONDS + "))}; [ $now -ge $end ] && break;"
                        + " sleep 0.1; done;"
                        + " exec nc localhost 22"};
    }

    /**
     * Refuse an instance that is not running, unless {@code --start} asks to start it. That is the
     * ProxyCommand {@code isx orca} gives Orca, which reconnects to a workspace it believes is
     * awake: one stopped meanwhile (a host restart, isx stopping it) comes back as {@code isx shell}
     * would bring it back, its credentials and CA included.
     *
     * <p>A connection that finds the instance locked by another isx process (another connection
     * starting it, a stop under way) waits for the lock and then prepares the instance itself,
     * rather than taking {@code Running} for ready: a start is running before it has delivered the
     * instance's credentials, and a shell opened meanwhile would keep its placeholders empty. The
     * preparation finds a running instance ready, so the second connection pays little for it.
     */
    void requireRunning(IncusClient incus) throws InterruptedException {
        var status = status(incus);
        if ("Running".equalsIgnoreCase(status)) return;
        if (!start) {
            throw new IllegalStateException("instance '" + instance + "' is not running (status: " + status + ").");
        }
        var deadline = System.nanoTime() + START_WAIT.toNanos();
        while (true) {
            var lock = RuntimeServices.lockManager().tryAcquire(instance, "starting");
            if (lock.isPresent()) {
                try (var held = lock.get()) {
                    InstancePrep.prepare(incus, instance, BuildOutput::step);
                } catch (InstancePrep.Refused e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("instance '" + instance + "' stayed locked by another isx process for "
                        + START_WAIT.toSeconds() + " seconds; try again.");
            }
            Thread.sleep(250);
        }
    }

    private String status(IncusClient incus) {
        String status;
        try {
            status = incus.getInstanceStatus(instance);
        } catch (RuntimeException e) {
            throw new IllegalStateException("could not check instance status: " + e.getMessage(), e);
        }
        if (status == null || status.isEmpty()) {
            throw new IllegalStateException("instance '" + instance + "' not found or not accessible.");
        }
        return status;
    }
}
