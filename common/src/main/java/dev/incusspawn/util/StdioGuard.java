package dev.incusspawn.util;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Hands the real stdin and stdout to the protocol, and everything else to stderr.
 *
 * <p>Much of isx prints progress to {@code System.out} ({@code BuildOutput}, lifecycle steps,
 * proxy warnings). Under {@code isx mcp} stdout is the protocol channel, under {@code isx orca}
 * it holds the one JSON object Orca parses, and under {@code isx ssh-proxy} it is the SSH
 * connection, so one stray line would corrupt any of them. Rather than
 * teach every helper about them, {@code System.out} becomes stderr -- which the MCP client and
 * Orca log -- and {@code System.in} becomes empty, so nothing but the protocol can consume the
 * caller's input. isx's own messages are bound to stderr outright
 * ({@link TerminalOutput#stderrOnly}), and {@link Headless} stops animations and prompts.
 */
public final class StdioGuard {

    public final InputStream protocolIn;
    public final OutputStream protocolOut;
    /** The real stderr, for a protocol that relays a child's own ({@code isx ssh-proxy}). */
    public final OutputStream protocolErr;

    private StdioGuard(InputStream in, OutputStream out, OutputStream err) {
        this.protocolIn = in;
        this.protocolOut = out;
        this.protocolErr = err;
    }

    public static StdioGuard install() {
        var guard = new StdioGuard(new FileInputStream(FileDescriptor.in),
                new FileOutputStream(FileDescriptor.out), new FileOutputStream(FileDescriptor.err));
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true));
        System.setIn(InputStream.nullInputStream());
        // For the life of the process, so no message of isx's own depends on the swap above.
        BuildOutput.bind(TerminalOutput.stderrOnly());
        Headless.enable();
        return guard;
    }
}
