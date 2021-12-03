package dev.liambloom.checker.ui.cli;

import dev.liambloom.checker.*;
import dev.liambloom.checker.internal.Util;
import dev.liambloom.checker.ui.BeanBook;
import dev.liambloom.checker.ui.Books;
import dev.liambloom.checker.ui.Config;
import dev.liambloom.checker.ui.UserErrorException;
import dev.liambloom.util.StringUtils;
import dev.liambloom.util.function.FunctionUtils;
import javafx.application.Application;
import org.fusesource.jansi.AnsiConsole;
import org.xml.sax.SAXException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main {
    private static final Pattern RANGED_NUM = Pattern.compile("(?:\\d+(?:-\\d+)?(?:,|$))+");

    public static void main(String[] args) {
        try {
//            Logger.setLogger(new PrintStreamLogger());
            AnsiConsole.systemInstall();
            /*if (args.length > 1 && (args[1].equals("-h") || args[1].equals("--help"))) {
                // TODO: handle help better
                assertLength(args, 2);
                printHelp(args[0]);
            }
            else {*/
            if (args.length == 0)
                args = new String[]{ "-h" };

            switch (args[0]) {
                case "-h", "--help" -> {
                    assertArgsPresent(args, 1);
                    printHelp("checker");
                }
                case "-v", "--version" -> {
                    assertArgsPresent(args, 1);
                    System.out.println(Main.class.getPackage().getImplementationVersion());
                }
                case "check" -> {
                    try {
                        List<String> globArgs = new LinkedList<>();
                        String testName = null;
                        OptionalInt chapter = OptionalInt.empty();
                        Map<String, boolean[]> preCheckables = new HashMap<>();

                        Queue<String> argQ = new ArrayDeque<>(Arrays.asList(args).subList(1, args.length));

                        while (!argQ.isEmpty()) {
                            String arg = argQ.remove();

                            switch (arg) {
                                case "-s", "--section" -> {
                                    if (chapter.isPresent())
                                        throw new UserErrorException("Repeat argument: " + arg);
                                    try {
                                        chapter = OptionalInt.of(Integer.parseInt(Optional.ofNullable(argQ.poll()).orElseThrow(
                                            () -> new UserErrorException("Missing argument: expected a value after " + arg)
                                        )));
                                    }
                                    catch (NumberFormatException e) {
                                        throw new UserErrorException(e);
                                    }
                                }
                                case "-b", "--books" -> {
                                    if (testName != null)
                                        throw new UserErrorException("Repeat argument: " + arg);
                                    testName = Optional.ofNullable(argQ.poll()).orElseThrow(() -> new UserErrorException("Missing argument: expected a value after " + arg));
                                }
                                default -> {
                                    String target;
                                    if (arg.startsWith("-t:"))
                                        target = arg.substring(3);
                                    else if (arg.startsWith("--target:"))
                                        target = arg.substring(9);
                                    else {
                                        globArgs.add(arg);
                                        break;
                                    }
                                    preCheckables.compute(target, (k, v) -> {
                                        if (v == null) {
                                            int absMax = Integer.MIN_VALUE;
                                            List<int[]> ranges = new ArrayList<>(); //[args.length - i][];

                                            while (!argQ.isEmpty() && RANGED_NUM.matcher(argQ.peek()).matches()) {
                                                for (String s : argQ.remove().split(",")) {
                                                    if (s.isEmpty())
                                                        continue;
                                                    int min, max;
                                                    if (s.contains("-")) {
                                                        String[] range = s.split("-");
                                                        min = Integer.parseInt(range[0]);
                                                        max = Integer.parseInt(range[1]);
                                                    }
                                                    else
                                                        min = max = Integer.parseInt(s);

                                                    if (absMax < max)
                                                        absMax = max;

                                                    if (min > max || min <= 0)
                                                        throw new UserErrorException("Range " + s + " is invalid");

                                                    ranges.add(new int[]{ min, max });
                                                }
                                            }

                                            if (ranges.isEmpty())
                                                throw new UserErrorException("Missing argument: expected value(s) after " + arg);

                                            boolean[] nums = new boolean[absMax + 1];

                                            for (int[] range : ranges) {
                                                for (int j = range[0]; j <= range[1]; j++) {
                                                    if (nums[j])
                                                        throw new UserErrorException("Attempt to list " + target + " " + j + " twice");
                                                    else
                                                        nums[j] = true;
                                                }
                                            }

                                            return nums;
                                        }
                                        else
                                            throw new UserErrorException("Duplicate Argument: " + arg);
                                    });
                                }
                            }
                        }

                        if (preCheckables.isEmpty())
                            throw new UserErrorException("No exercises or programming projects specified");
                        if (testName == null) {
                            testName = Config.get(Config.Property.BOOK);
                                if (testName == null)
                                    throw new UserErrorException("Either provide book argument (`-b') or set a default book");
                        }

                        // TODO: Do something to catch other error (like references to non-existant types)

                        Stream<Path> paths = new Glob(globArgs).files();

                        BeanBook book = getMaybeAnonymousBook(testName);
                        Result<TestStatus>[] result;
                        BookReader reader;

//                        do {
                            reader = new BookReader(testName, book.getInnerBook());

                            Map<String, String> checkableNameAbbrMap = new HashMap<>();
                            Set<String> names = new HashSet<>();
                            for (String name : reader.getCheckableTypeSet()) {
                                names.add(name);
                                Set<String> variations = Stream.of(StringUtils.Case.PASCAL, StringUtils.Case.CAMEL, StringUtils.Case.SNAKE, StringUtils.Case.CONST, StringUtils.Case.SKEWER)
                                    .map(c -> StringUtils.convertCase(name, c))
                                    .collect(Collectors.toSet());
                                variations.add(name.substring(0, 1));
                                variations.add(String.valueOf(StringUtils.initials(name)));

                                for (String abbr : variations) {
                                    checkableNameAbbrMap.merge(abbr, name, (oldValue, newValue) -> {
                                        String r = null;
                                        if (abbr.equals(newValue))
                                            r = newValue;
                                        else if (names.contains(oldValue))
                                            r = oldValue;
                                        if (preCheckables.containsKey(abbr)) {
                                            if (r == null)
                                                throw new IllegalArgumentException("Ambiguous abbreviation " + abbr + ": could refer to " + oldValue + " or " + newValue);
                                            else
                                                return r;
                                        }
                                        else
                                            return null;
                                    });
                                }
                            }

                            Map<String, boolean[]> processedCheckables = new HashMap<>();
                            for (Map.Entry<String, boolean[]> e : preCheckables.entrySet()) {
                                String k = checkableNameAbbrMap.get(e.getKey());
                                if (k == null)
                                    throw new IllegalArgumentException("Unknown checkable type: " + e.getKey());
                                processedCheckables.merge(k, e.getValue(), (v1, v2) -> {
                                    throw new IllegalArgumentException("Repeat argument: --target:" + k);
                                });
                            }

                             result = reader.check(chapter, processedCheckables, paths);
//                        }
//                        while (!reader.validateResults());

                        printResults(result);
                    }
                    catch (SAXException | ClassNotFoundException | IllegalArgumentException e) {
                        throw new UserErrorException(e);
                    }
                }
                case "submit" -> throw new UserErrorException("Command `submit' not supported in current checker version");
                // break;
                case "books" -> {
                    if (args.length == 1)
                        throw new UserErrorException("Missing argument. See `chk books --help' for help."); // TODO
                    switch (args[1]) {
                        // TODO: handle errors
                        case "add" -> {
                            assertArgsPresent(args, 2, "name", "path");
                            try {
                                Books.add(args[2], new Glob(args[3]).single().toUri().toURL());
                            }
                            catch (IllegalArgumentException e) {
                                throw new UserErrorException(e);
                            }
                        }
                        case "remove" -> Books.remove(args[2]);
                        case "rename" -> {
                            assertArgsPresent(args, 2, "old name", "new name");
                            try {
                                Books.getBook(args[2]).setName(args[3]);
                            }
                            catch (NullPointerException e) {
                                throw new UserErrorException(e.getMessage(), e);
                            }
                        }
                        case "change" -> {
                            assertArgsPresent(args, 2, "name", "new URL");
                            BeanBook book;
                            try {
                                book = Books.getBook(args[2]);
                            }
                            catch (NullPointerException e) {
                                throw new UserErrorException(e.getMessage(), e);
                            }
                            URL before = book.getUrl();
                            boolean exists = false;
                            try {
                                URL url = new URL(args[3]);
                                book.setUrl(url);
                                if (!book.getExists())
                                    System.getLogger(Util.generateLoggerName()).log(System.Logger.Level.WARNING, "\"" + url + "\" does not exist");
                            }
                            catch (MalformedURLException e) {
                                try {
                                    Path path = new Glob(args[3]).single();
                                    if (!Files.exists(path))
                                        System.getLogger(Util.generateLoggerName()).log(System.Logger.Level.WARNING, "\"" + path + "\" does not exist");
                                    book.setUrl(path.toUri().toURL());
                                }
                                catch (MalformedURLException e2) {
                                    throw new UserErrorException(e.getMessage(), e);
                                }
                            }
                        }
                        case "list" -> {
                            assertArgsPresent(args, 2);
                            BeanBook[] books = Books.getAllBooks();//.collect(Collectors.toList());
                            String[][] strs = new String[books.length][2];
                            int maxBookNameLength = 0;
                            for (int i = 0; i < strs.length; i++) {
                                BeanBook book = books[i];
                                if (book.getName().length() > maxBookNameLength)
                                    maxBookNameLength = book.getName().length();
                                strs[i][0] = book.getName();
                                strs[i][1] = book.getUrl().toString();
                            }
                            for (String[] book : strs)
                                System.out.printf("%-" + maxBookNameLength + "s  %s%n", book[0], book[1]);
                        }
                        case "validate" -> {
                            if (args.length == 2)
                                throw new UserErrorException("Missing argument after validate");
                            printResults((args[2].equals("-a") || args[2].equals("--all")
                                ? Arrays.stream(Books.getAllBookNames())
                                : Arrays.stream(args).skip(2))
                                .map(Books::getBook)
                                .map(b -> new BookReader(b.getName(), b.getInnerBook()))
                                .map(FunctionUtils.unchecked(BookReader::validateBook))
                                .toArray(Result[]::new));
                        }
                        /*case "get-default" -> System.out.println(Optional.ofNullable(prefs.get("selectedTests", null))
                            .orElseThrow(() -> new UserErrorException("No default test found")));
                        case "set-default" -> {
                            assertArgsPresent(args, 2, "name");
                            try {
                                Books.setDefaultBook(args[2]);
                            }
                            catch (NullPointerException e) {
                                throw new UserErrorException(e.getMessage(), e);
                            }
                        }*/
                        default -> throw new UserErrorException("Command `tests " + args[1] + "' not recognized. See `checker tests --help' for a list of subcommands of `tests'");
                    }
                }
                case "config" -> {
                    if (args.length == 1)
                        throw new UserErrorException("Missing argument: expected one of: get, set, unset");
                    if (args.length == 2)
                        throw new UserErrorException("Missing argument: property");
                    if (!Config.propertyExists(args[2]))
                        throw new UserErrorException("Configuration property \"" + args[2] + "\" does not exist");
                    switch (args[1]) {
                        case "get" -> {
                            assertArgsPresent(args, 3);
                            System.out.println(Config.get(args[2]));
                        }
                        case "set" -> {
                            assertArgsPresent(args, 3, "value");
                            Config.set(args[2], args[3]);
                        }
                        case "unset" -> {
                            assertArgsPresent(args, 3);
                            Config.unset(args[2]);
                        }
                        default -> throw new UserErrorException("Command `config " + args[1] + "' not recognized. See `checker config --help; for a list of subcommands.");
                    }
                }
                case "gui" -> Application.launch(dev.liambloom.checker.ui.gui.Main.class, Arrays.copyOfRange(args, 1, args.length));
                default -> throw new UserErrorException("Command `" + args[0] + "' not recognized. See `checker --help' for a list of subcommands.");
            }
            //}
        }
        catch (UserErrorException e) {
            System.getLogger(Util.generateLoggerName()).log(System.Logger.Level.ERROR, e.getMessage());
            System.exit(1);
            //e.printStackTrace();
        }
        catch (Throwable e) {
            System.getLogger(Util.generateLoggerName()).log(System.Logger.Level.ERROR, "An error was encountered internally.");
            //e.printStackTrace();
            /*try {
                Logger.createLogFile(e);
            }
            catch (IOException ignored) {
                Logger.logger.log(LogKind.ERROR, "Failed to create log file");
            }*/

            System.exit(1);
        }
        finally {
            AnsiConsole.systemUninstall();
        }
    }

    private static void printHelp(String arg) throws IOException {
        InputStream stream;
        if (arg.contains("/") || (stream = Main.class.getResourceAsStream("/help/" + arg + ".txt")) == null)
            throw new UserErrorException("Unable to find help for `" + arg + "'");
        int next;
        while ((next = stream.read()) != -1)
            System.out.write(next);
    }

    private static void assertArgsPresent(String[] args, int i, String... names) {
        int rem = args.length - i;
        if (rem < names.length)
            throw new UserErrorException("Missing argument: " + names[rem]);
        else if (rem > names.length)
            throw new UserErrorException("Unexpected argument: `" + args[i + names.length] + '\'');
    }

    public static void printResults(Result<?>[] s) throws IOException {
        for (Result<?> r : s)
            System.out.printf("%s ... \u001b[%sm%s\u001b[0m%n", r.name(), r.status().color().ansi(), StringUtils.convertCase(r.status().toString(), StringUtils.Case.SPACE));

    }

    private static BeanBook getMaybeAnonymousBook(String name) throws IOException {
        try {
            return Books.getBook(name);
        }
        catch (NullPointerException ignored) { }
        try {
            return Books.getAnonymousBook(new URL(name));
        }
        catch (MalformedURLException ignored) { }
        try {
            return Books.getAnonymousBook(new Glob(name).single().toUri().toURL());
        }
        catch (MalformedURLException ignored) { }
        throw new UserErrorException("Book \"" + name + "\" does not exist.");
    }
}
