package dev.lumina.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Writes to logs/lumina.log (flushed on every line, so the tail survives a hard crash)
 * and mirrors INFO+ to the regular game log.
 */
public final class LuminaLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("Lumina");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_REPEATS = 5;

    private static BufferedWriter writer;
    private static boolean debug = true;
    private static final Map<String, Integer> repeatCounts = new HashMap<>();

    private LuminaLog() {
    }

    public static synchronized void init(Path logDir) {
        try {
            Files.createDirectories(logDir);
            Path file = logDir.resolve("lumina.log");
            Path previous = logDir.resolve("lumina-previous.log");

            if (Files.exists(file)) {
                Files.move(file, previous, StandardCopyOption.REPLACE_EXISTING);
            }

            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            info("Log started %s", LocalDateTime.now());
        } catch (IOException e) {
            LOGGER.error("Unable to open lumina.log, file logging disabled", e);
        }
    }

    public static void setDebug(boolean enabled) {
        debug = enabled;
    }

    public static void debug(String format, Object... args) {
        if (debug)
            write("DEBUG", format(format, args), null, false);
    }

    public static void info(String format, Object... args) {
        write("INFO", format(format, args), null, true);
    }

    public static void warn(String format, Object... args) {
        write("WARN", format(format, args), null, true);
    }

    public static void error(String message, Throwable t) {
        write("ERROR", message, t, true);
    }

    /**
     * For messages that can fire every frame: only the first few occurrences per key are written.
     */
    public static void errorLimited(String key, String message, Throwable t) {
        int count;
        synchronized (LuminaLog.class) {
            count = repeatCounts.merge(key, 1, Integer::sum);
        }

        if (count <= MAX_REPEATS) {
            write("ERROR", message, t, true);

            if (count == MAX_REPEATS)
                write("ERROR", "'%s' repeated %d times, further occurrences are not logged".formatted(key, MAX_REPEATS), null, true);
        }
    }

    private static String format(String format, Object[] args) {
        if (args == null || args.length == 0)
            return format;

        try {
            return String.format(format, args);
        } catch (RuntimeException e) {
            return format;
        }
    }

    private static synchronized void write(String level, String message, Throwable t, boolean mirror) {
        if (mirror) {
            switch (level) {
                case "ERROR" -> LOGGER.error(message, t);
                case "WARN" -> LOGGER.warn(message);
                default -> LOGGER.info(message);
            }
        }

        if (writer == null)
            return;

        try {
            writer.write('[');
            writer.write(LocalDateTime.now().format(TIME));
            writer.write("] [");
            writer.write(Thread.currentThread().getName());
            writer.write("/");
            writer.write(level);
            writer.write("] ");
            writer.write(message);
            writer.newLine();

            if (t != null) {
                StringWriter stack = new StringWriter();
                t.printStackTrace(new PrintWriter(stack));
                writer.write(stack.toString());
            }

            writer.flush();
        } catch (IOException e) {
            writer = null;
            LOGGER.error("lumina.log became unwritable, file logging disabled", e);
        }
    }
}
