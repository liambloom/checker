package dev.liambloom.tests.bjp.shared;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Validator;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.prefs.Preferences;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import static java.nio.file.StandardWatchEventKinds.*;

public abstract class Book {
    abstract Result validate(Validator v) throws IOException;
    abstract Document getDocument(DocumentBuilder db) throws SAXException, IOException;
    abstract void addWatcher(Consumer<WatchEvent<Path>> cb) throws IOException;
    abstract boolean exists() throws IOException;

    private static final Map<Path, Collection<Consumer<WatchEvent<Path>>>> watcherCallbacks = Collections.synchronizedMap(new HashMap<>());
    private static final Map<FileSystem, WatchService> watchers = Collections.synchronizedMap(new HashMap<>());
    private static final Map<Path, Collection<Path>> watcherSymlinkTargets = Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, Book> loadedTests = Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, String> LOCAL_TEST_NAMES;

    static {
        LOCAL_TEST_NAMES = new HashMap<>();
        LOCAL_TEST_NAMES.put("BJP 3", "bjp3");
    }

    public static Book getTest(String name) {
        { // Get book if it has already been loaded
            Book alreadyLoaded = loadedTests.get(name);
            if (alreadyLoaded != null)
                return alreadyLoaded;
        }

        Book r;

        InputStream stream = App.class.getClassLoader().getResourceAsStream("/tests/" + LOCAL_TEST_NAMES.get(name) + ".xml");
        if (stream != null) { // Get test from jar
            Source source = new StreamSource(stream);
            r = new Book() {
                @Override
                public Result validate(Validator v) throws IOException {
                    // TODO: v.setErrorHandler()
                    try {
                        v.validate(source);
                        return new Result(name, TestValidationStatus.VALID/* Generate ByteArrayOutputStream from ErrorHandler */);
                    }
                    catch (SAXException e) {
                        return new Result(name, TestValidationStatus.INVALID/*, Generate ByteArrayOutputStream from ErrorHandler */);
                    }
                }

                @Override
                public Document getDocument(DocumentBuilder db) throws SAXException, IOException {
                    return db.parse(stream);
                }

                @Override
                public void addWatcher(Consumer<WatchEvent<Path>> cb) {
                    // Watch creates a watcher for changed, but since this resource can't
                    //  be changed while the program is running, I don't need to do anything
                }

                @Override
                public boolean exists() {
                    return true;
                }
            };
        }
        else { // Get tests from preferences
            String pathString = App.prefs().node("tests").get(name, null);

            if (pathString == null)
                throw new UserErrorException("Test \"" + name + "\" not found");

            Path p = Path.of(pathString);

            r = new Book() {
                @Override
                public Result validate(Validator v) throws IOException {
                    if (exists()) {
                        try {
                            v.validate(new StreamSource(new BufferedInputStream(Files.newInputStream(p))));
                            return new Result(name, TestValidationStatus.VALID/* Generate ByteArrayOutputStream from ErrorHandler */);
                        }
                        catch (SAXException e) {
                            return new Result(name, TestValidationStatus.INVALID/*, Generate ByteArrayOutputStream from ErrorHandler */);
                        }
                    }
                    else
                        return new Result(name, TestValidationStatus.NOT_FOUND);
                }

                @Override
                public Document getDocument(DocumentBuilder db) throws SAXException, IOException {
                    if (exists())
                        return db.parse(Files.newInputStream(p));
                    else
                        throw new NoSuchFileException(p.toString());
                }

                @Override
                public void addWatcher(Consumer<WatchEvent<Path>> cb) throws IOException {
                    Book.addWatcher(p, cb);
                }

                @Override
                public boolean exists() throws IOException {
                    try {
                        return p.toRealPath().toString().endsWith(".xml");
                    }
                    catch (NoSuchFileException e) {
                        return false;
                    }
                }
            };
        }


        loadedTests.put(name, r);
        return r;
    }

    public static boolean testExists(String name) {
        return loadedTests.containsKey(name)
                || Optional.ofNullable(LOCAL_TEST_NAMES.get(name)).map(Book.class.getClassLoader()::getResource).isPresent()
                || App.prefs().node("tests").get(name, null) != null;
    }

    public static void addTest(Path p) throws SAXException, IOException {
        // get document
        // get name from document
        // add name => p to tests
        // add the name to the index
    }

    public static void removeTest(String name) {
        // find the name in the index (same as indexOf, just iterate over it until you find it)
        // shift everything after that 1 to the left
        // remove the last element
        // remove the name from preferences (not just the index)
        // remove the name from loadedTests
    }

    protected static void addWatcher(Path p, Consumer<WatchEvent<Path>> cb) throws IOException {
        try {
            WatchService watcher = watchers.computeIfAbsent(p.getFileSystem(), (FunctionThrowsIOException<FileSystem, WatchService>) fileSystem -> {
                WatchService fsWatcher = fileSystem.newWatchService();

                new Thread(() -> {
                    while (true) {
                        WatchKey key;
                        try {
                            key = fsWatcher.take();
                        }
                        catch (InterruptedException e) {
                            App.logger.log(Logger.LogKind.ERROR, e.toString());
                            throw new RuntimeException(e);
                        }

                        for (WatchEvent<?> eventUnfiltered : key.pollEvents()) {
                            if (eventUnfiltered.kind() == OVERFLOW)
                                continue;

                            @SuppressWarnings("unchecked")
                            WatchEvent<Path> event = (WatchEvent<Path>) eventUnfiltered;
                            Path target = event.context().toAbsolutePath().normalize();
                            Queue<Path> targets = new LinkedList<>();
                            targets.add(target);

                            while (Files.isSymbolicLink(target)) {
                                Path targetTarget;
                                try {
                                    targetTarget = Files.readSymbolicLink(target);
                                }
                                catch (IOException e) {
                                    App.logger.log(Logger.LogKind.ERROR, "No longer watching for changes in tests. Reason: " + e.getMessage());
                                    return;
                                }
                                if (!watcherSymlinkTargets
                                        .computeIfAbsent(targetTarget.toAbsolutePath().normalize(), (FunctionThrowsIOException<Path, Collection<Path>>) (e -> {
                                            e.getParent().register(fsWatcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                                            return Collections.synchronizedCollection(new LinkedList<>());
                                        }))
                                        .add(target.toAbsolutePath().normalize()))
                                    break;
                                target = targetTarget;
                            }

                            while (!targets.isEmpty()) {
                                Path currentTarget = targets.remove();

                                Collection<Consumer<WatchEvent<Path>>> callbacks = watcherCallbacks.get(currentTarget);

                                if (callbacks == null)
                                    continue;

                                for (Consumer<WatchEvent<Path>> callback : callbacks)
                                    callback.accept(event);

                                Iterator<Path> symlinks = watcherSymlinkTargets.get(currentTarget).iterator();
                                while (symlinks.hasNext()) {
                                    Path symlink = symlinks.next();
                                    if (!Files.isSymbolicLink(symlink)){
                                        symlinks.remove();
                                        continue;
                                    }

                                    Path symlinkTarget;
                                    try {
                                        symlinkTarget = Files.readSymbolicLink(symlink).toAbsolutePath().normalize();
                                    }
                                    catch (IOException e) {
                                        App.logger.log(Logger.LogKind.ERROR, "No longer watching for changes in tests. Reason: " + e.getMessage());
                                        return;
                                    }

                                    if (!symlinkTarget.equals(currentTarget)) {
                                        symlinks.remove();
                                        continue;
                                    }
                                    targets.add(symlinkTarget);
                                }
                            }
                        }

                        if (!key.reset()) {
                            App.logger.log(Logger.LogKind.ERROR, "No longer watching for changes in tests. Reason: WatchKey is invalid");
                            break;
                        }
                    }
                })
                        .start();

                return fsWatcher;
            });

            watcherCallbacks
                    .computeIfAbsent(p.toAbsolutePath().normalize(), (FunctionThrowsIOException<Path, Collection<Consumer<WatchEvent<Path>>>>) (key -> {
                        key.getParent().register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                        return Collections.synchronizedCollection(new LinkedList<>());
                    }))
                    .add(cb);

            while (Files.isSymbolicLink(p)) {
                Path target = Files.readSymbolicLink(p);
                if (!watcherSymlinkTargets
                        .computeIfAbsent(target.toAbsolutePath().normalize(), (FunctionThrowsIOException<Path, Collection<Path>>) (key -> {
                            key.getParent().register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                            return Collections.synchronizedCollection(new LinkedList<>());
                        }))
                        .add(p.toAbsolutePath().normalize()))
                    break;
                p = target;
            }
        }
        catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public static Stream<Book> getAllTests() {
        Preferences tests = App.prefs().node("tests");
        Preferences index = tests.node("index");

        return Stream.concat(
                LOCAL_TEST_NAMES.values().stream(),
                IntStream.range(0, index.getInt("size", 0))
                    .mapToObj(i -> index.get(Integer.toString(i), null))
                    .filter(Objects::nonNull)
                    .map(name -> tests.get(name, null))
                    .filter(Objects::nonNull)
        )
                .map(Book::getTest);
    }
}
