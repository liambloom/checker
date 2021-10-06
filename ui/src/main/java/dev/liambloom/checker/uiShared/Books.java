package dev.liambloom.checker.uiShared;

import dev.liambloom.checker.book.Book;
import dev.liambloom.checker.book.PathBook;
import dev.liambloom.checker.shared.Lazy;
import org.xml.sax.SAXException;

import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.prefs.Preferences;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public final class Books {
    private Books() {
    }

    private static final Map<String, Book> loadedTests = Collections.synchronizedMap(new HashMap<>());
//    private static final Map<String, String> LOCAL_TEST_NAMES;
//    private static final Lazy<Preferences> customTests = new Lazy<>(() -> App.prefs().node("tests"));

//    static {
//        LOCAL_TEST_NAMES = new HashMap<>();
//        LOCAL_TEST_NAMES.put("BJP 3", "bjp3");
//    }

    public static Schema getSchema() {
        return schema.get();
    }

    public static Lazy<Schema> getSchemaLazy() {
        return schema;
    }

//    public static Preferences getCustomTests() {
//        return customTests.get();
//    }

    public static Book getBook(String name) {
        { // Get book if it has already been loaded
            Book alreadyLoaded = loadedTests.get(name);
            if (alreadyLoaded != null)
                return alreadyLoaded;
        }

        Book r;

        /*InputStream stream = Book.class.getClassLoader().getResourceAsStream("tests/" + LOCAL_TEST_NAMES.get(name) + ".xml");
        if (stream != null) // Get test from jar
            r = new StreamBook(name, stream);
        else { // Get tests from preferences
            String pathString = App.prefs().node("tests").get(name, null);

            if (pathString == null)
                throw new UserErrorException("Test \"" + name + "\" not found");

            Path p = Path.of(pathString);

            r = new PathBook(name, p);
        }*/


        loadedTests.put(name, r);
        return r;
    }

    public static boolean bookNameExists(String name) {
        return loadedTests.containsKey(name)
            || Optional.ofNullable(LOCAL_TEST_NAMES.get(name)).map(s -> "tests/" + s + ".xml").map(Book.class.getClassLoader()::getResource).isPresent()
            || App.prefs().node("tests").get(name, null) != null;
    }

    public static void addBook(String name, Path p) throws IOException {
        if (bookNameExists(name))
            throw new IllegalArgumentException("Test `" + name + "' already exists");
        if (name.equals("-a") || name.equals("--all"))
            throw new IllegalArgumentException("Invalid test name: `-a' and `--all' are reserved");
        if (name.length() > Preferences.MAX_KEY_LENGTH)
            throw new IllegalArgumentException("Invalid test name: must not have a length greater than " + Preferences.MAX_KEY_LENGTH);
        if (!p.toString().endsWith(".xml"))
            throw new IllegalArgumentException("Invalid test path: " + p + " is not xml");
        if (Files.notExists(p))
            throw new IllegalArgumentException("Invalid test path: " + p + " does not exist");
        /*if (!Files.exists(p) || !p.toRealPath().toString().endsWith(".xml"))
            throw new UserErrorException("Path `" + p + "' is not xml");
        getCustomTests().put(name, p.toString());*/
        PathBook.setPath(name, p);
        Preferences index = getCustomTests().node("index");
        int size = index.getInt("size", 0);
        index.put(Integer.toString(size), name);
        index.putInt("size", size + 1);
    }

    public static void removeBook(String name) {
        loadedTests.remove(name);
        if (getCustomTests().get(name, null) == null)
            throw new UserErrorException("Test \"" + name + "\" not found");
        getCustomTests().remove(name);
        Preferences index = getCustomTests().node("index");
        int size = index.getInt("size", 0);
        int i;
        for (i = 0; i < size; i++) {
            if (index.get(Integer.toString(i), null).equals(name)) {
                index.remove(Integer.toString(i));
                break;
            }
        }
        if (i == size)
            throw new IllegalStateException("Test \"" + name + "\" not found in index");
        for (; i < size - 1; i++)
            index.put(Integer.toString(i), index.get(Integer.toString(i + 1), null));
        index.remove(Integer.toString(size - 1));
        index.putInt("size", size - 1);
    }

    public static void updateLoadedBookName(String oldName) {
        synchronized (loadedTests) {
            Book book = loadedTests.remove(oldName);
            if (book == null)
                throw new IllegalArgumentException("Book \"" + oldName + "\" does not exist");
            loadedTests.compute(book.getName(), (k, v) -> {
                if (v != null)
                    throw new UserErrorException("Name already taken");
                return loadedTests.get(oldName);
            });
        }
    }

    public static Stream<Book> getAllBooks() {
        Preferences index = getCustomTests().node("index");

        return Stream.concat(
            LOCAL_TEST_NAMES.keySet().stream(),
            IntStream.range(0, index.getInt("size", 0))
                .mapToObj(i -> index.get(Integer.toString(i), null))
                .filter(Objects::nonNull)
                    /*.map(name -> getCustomTests().get(name, null))
                    .filter(Objects::nonNull)*/
        )
            .map(Books::getBook);
    }
}