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
 * proxy warnings). Under {@code isx mcp} stdout is the protocol channel, and under {@code isx orca}
 * it holds the one JSON object Orca parses, so one stray line would corrupt either. Rather than
 * teach every helper about them, {@code System.out} becomes stderr -- which the MCP client and
 * Orca log -- and {@code System.in} becomes empty, so nothing but the protocol can consume the
 * caller's input. isx's own messages are bound to stderr outright
 * ({@link TerminalOutput#stderrOnly}), and {@link Headless} stops animations and prompts.
 */
public final class StdioGuard {

    public final InputStream protocolIn;
    public final OutputStream protocolOut;

    private StdioGuard(InputStream in, OutputStream out) {
        this.protocolIn = in;
        this.protocolOut = out;
    }

    public static StdioGuard install() {
        var guard = new StdioGuard(new FileInputStream(FileDescriptor.in),
                new FileOutputStream(FileDescriptor.out));
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true));
        System.setIn(InputStream.nullInputStream());
        // For the life of the process, so no message of isx's own depends on the swap above.
        BuildOutput.bind(TerminalOutput.stderrOnly());
        Headless.enable();
        return guard;
    }
}
