package dev.liambloom.tests.book.bjp.checker.old;

import java.io.Closeable;
import java.io.File;
import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.fusesource.jansi.AnsiConsole;

class CLI {
}

class Debugger implements Closeable, Logger {
    public final boolean debugMode;

    public Debugger() {
        AnsiConsole.systemInstall();
        if (debugMode = "1".equals(System.getenv("CHECKER_DEBUG")))
            System.out.println("Debug mode is ON");
    }

    public void error(String msg, Object... args) {
        System.err.println("\u001b[31m[error]\u001b[0m " + String.format(msg, args));
    }

    public void internalError(Throwable e) {
        try {
            this.error("Internal Error. Check logs for more detailed information");
            if (debugMode)
                e.printStackTrace();
            System.out.println(App.here()
                    + File.separator + "logs" + File.separator
                    + DateTimeFormatter.ofPattern("uuuu-MM-dd-HH-mm-ss").format(LocalDateTime.now()) + ".log");
            final File log = new File(new File(App.here()).getParent()
                    + File.separator + "logs" + File.separator
                    + DateTimeFormatter.ofPattern("uuuu-MM-dd-HH-mm-ss").format(LocalDateTime.now()) + ".log");
            log.getParentFile().mkdir();
            log.createNewFile();
            e.printStackTrace(new PrintStream(log));
        }
        catch (Throwable logError) {
            this.error("Failed to create log file");
            if (debugMode)
                logError.printStackTrace();
        }
        System.exit(1);
    }

    public void warn(String msg, Object... args) {
        System.err.println("\u001b[33m[warning]\u001b[0m " + String.format(msg, args));
    }

    public void notice(String msg, Object... args) {
        System.err.println("\u001b[36m[notice]\u001b[0m " + String.format(msg, args));
    }

    public void close() {
        AnsiConsole.systemUninstall();
    }
}