package xyz.daikirai.inject;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Injected clients have nowhere to write: System.out goes to the launcher's
 * log window (or nowhere at all once the game owns the console), and the game
 * is already running by the time we load, so there is no startup banner to
 * print into. Everything goes to a file instead.
 *
 * The path is fixed rather than configurable on purpose -- a user who has to
 * find a config file to discover why injection failed is a user who will not
 * find it.
 */
public final class Log {

    private static final String FILE_NAME = "openraven-native.log";
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("HH:mm:ss.SSS");

    private static File file;

    private Log() {
    }

    public static synchronized File file() {
        if (file == null) {
            file = new File(System.getProperty("java.io.tmpdir"), FILE_NAME);
        }
        return file;
    }

    /** Overrides the log location. Only useful before anything has been logged. */
    public static synchronized void to(File target) {
        file = target;
    }

    public static void line(String message) {
        write("[" + STAMP.format(new Date()) + "] " + message);
    }

    public static void throwable(String message, Throwable error) {
        StringWriter buffer = new StringWriter();
        buffer.write(message);
        buffer.write(System.lineSeparator());
        PrintWriter printer = new PrintWriter(buffer);
        error.printStackTrace(printer);
        printer.flush();
        write(buffer.toString());
    }

    /** Distinct failures already reported, so a per-tick one is one line. */
    private static final java.util.Set<String> SEEN = new java.util.HashSet<String>();

    /**
     * For exceptions that are caught because they genuinely do not matter.
     * Named so that "we ignored this" is visible in the log rather than silent.
     *
     * <p>Each distinct failure is written once. A callback that throws every
     * tick would otherwise be a log line every tick, which is how the 1.4 GB
     * log file happened; the first line says everything the rest would.
     */
    public static void swallowed(Throwable error) {
        String message = "ignored: " + error;
        synchronized (SEEN) {
            if (!SEEN.add(message)) {
                return;
            }
        }
        line(message);
    }

    private static synchronized void write(String message) {
        try {
            FileWriter writer = new FileWriter(file(), true);
            try {
                writer.write(message);
                writer.write(System.lineSeparator());
            } finally {
                writer.close();
            }
        } catch (Throwable ignored) {
            // A logger that throws is worse than a logger that loses a line.
        }
    }
}
