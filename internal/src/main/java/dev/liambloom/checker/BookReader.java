package dev.liambloom.checker;

import dev.liambloom.checker.internal.*;
import dev.liambloom.util.function.ConsumerThrowsException;
import dev.liambloom.util.function.FunctionThrowsException;
import dev.liambloom.util.function.FunctionUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Note: this is NOT thread safe.
 */
public final class BookReader {
    private static final Path initDir = Path.of(".").toAbsolutePath().normalize();
    private static final Thread dirResetter = new Thread(BookReader::resetDir);
    private static final XPathFactory xpf = XPathFactory.newInstance();
    private static final DocumentBuilderFactory dbf;
    private static final Lock nativeLoaderLock = new ReentrantLock();
    private static boolean triedLoadNative;
    private static boolean loadedNativeSuccess;

    static {
        dbf = DocumentBuilderFactory.newInstance();
        SchemaFactory factory = SchemaFactory.newInstance("http://www.w3.org/XML/XMLSchema/v1.1");
        try {
            factory.setFeature("http://apache.org/xml/features/validation/cta-full-xpath-checking", true);
            dbf.setSchema(factory.newSchema(
                new StreamSource(BookReader.class.getResourceAsStream("/book-schema.xsd"))));
        }
        catch (SAXException e) {
            e.printStackTrace();
        }
        dbf.setNamespaceAware(true);
    }

    // Set at initialization
    private final System.Logger logger = System.getLogger(Long.toString(System.identityHashCode(this)));
    private final XPath xpath = xpf.newXPath();
    private final Book book;
    private final String name;
    private MessageDigest digest;

    // Set in parseAndValidateDocument()
    private Document document = null;
    private ClassLoader classLoader;
    private Result<TestValidationStatus> result = null;
    private Exception documentParseException;
    private CheckableType<? extends Annotation> sectionType = null;
    private CheckableType<? extends Annotation>[] checkableTypes = null;

    // Set in check()
    private Path tempDir = null;

    // Set in validate()
    private boolean isInvalidated = false;

    // Set in getCheckableTypeSet()
    private Set<String> checkableTypeSet = null;

    // Set in getResources()
    private String[] resources = null;

    public BookReader(Book book) {
        this(book.toString(), book);
    }

    public BookReader(String name, Book book) {
        this.name = name;
        this.book = book;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                removeTempDir();
            }
            catch (IOException e) {
                System.getLogger(BookReader.class.getName()).log(System.Logger.Level.ERROR, "Error removing temp dir", e);
            }
        }));
    }

    private void parseAndValidateDocument() throws IOException, SAXException, ClassNotFoundException, NoSuchMethodException {
        logger.log(System.Logger.Level.TRACE, "Parsing & validating document");
        if (document != null)
            logger.log(System.Logger.Level.TRACE, "Document already parsed");
        else if (documentParseException == null) {
            ValidationErrorHandler handler = new ValidationErrorHandler(name);
            try {
                logger.log(System.Logger.Level.TRACE, "Parsing document");
                if (!book.exists()) {
                    logger.log(System.Logger.Level.TRACE, "Book does not exist");
                    result = new Result<>(name, TestValidationStatus.NOT_FOUND);
                    throw new IllegalStateException("Book " + book + " does not exist");
                }
                DocumentBuilder db;
                try {
                    db = dbf.newDocumentBuilder();
                }
                catch (ParserConfigurationException e) {
                    logger.log(System.Logger.Level.DEBUG, "Failure to create document builder", e);
                    throw new RuntimeException(e);
                }
                logger.log(System.Logger.Level.TRACE, "Document builder obtained");
                db.setErrorHandler(handler);
                try {
                    document = db.parse(new DigestInputStream(book.getInputStream(), digest));
                    logger.log(System.Logger.Level.TRACE, "Successfully parsed document");
                }
                catch (SAXException e) {
                    logger.log(System.Logger.Level.TRACE, "Error parsing document");
                    result = new Result<>(name, TestValidationStatus.INVALID, handler.getLogs());
                    throw e;
                }
                catch (Throwable e) {
                    logger.log(System.Logger.Level.TRACE, "Non-sax error parsing document", e);
                    e.printStackTrace();
                    throw e;
                }
                // I'd like to wrap exceptions in SAXParseException, but I have no way to get the line/column number
                //  without using a SAX parser. Since I want a document to end with, I would need to either:
                //  a: parse it twice, or b: build my own DocumentBuilder.
                try {
                    URI bookUri = book.getResourceBaseURI();
                    classLoader = new URLClassLoader((Util.streamNodeList((NodeList) xpath
                        .evaluate("/book/meta/classPath/include", document, XPathConstants.NODESET))
                        .filter(Element.class::isInstance)
                        .map(Element.class::cast)
                        .map(Element::getTextContent)
                        .map(bookUri::resolve))
                        .map(FunctionUtils.unchecked(URI::toURL))
                        .toArray(URL[]::new));
                }
                catch (XPathExpressionException | URISyntaxException e) {
                    throw new RuntimeException(e);
                }
                NodeList checkableTypeNodes = document.getElementsByTagName("checkableType");
                checkableTypes = new CheckableType[checkableTypeNodes.getLength()];
                Stream.Builder<Exception> checkableTypeErrors = Stream.builder();
                for (int i = 0; i < checkableTypeNodes.getLength(); i++) {
                    try {
                        checkableTypes[i] = new CheckableType<>((Element) checkableTypeNodes.item(i));
                    }
                    catch (ClassNotFoundException | ClassCastException | NoSuchMethodException e) {
                        checkableTypeErrors.add(e);
                    }
                }

                Stream<Exception> typeErrors = Stream.of(
                    Stream.of(
                        elementOfType("parameter")
                            .map(Node::getTextContent),
                        elementOfType("Array", "ArrayList", "LinkedList", "TargetArrayList", "Stack", "HashSet", "TreeSet", "TargetTree")
                            .map(e -> e.getAttribute("elementType")),
                        elementOfType("HashMap", "TreeMap")
                            .flatMap(e -> Stream.of("keyType", "valueType").map(e::getAttribute))
                    )
                        .flatMap(Function.identity())
                        .map(String::trim)
                        .map(type -> switch (type) {
                            case "byte", "short", "int", "long", "float", "double", "boolean", "char", "this" -> null;
                            default -> {
                                try {
                                    classLoader.loadClass(type);
                                    yield null;
                                }
                                catch (ClassNotFoundException e) {
                                    yield e;
                                }
                            }
                        })
                        .filter(Objects::nonNull),
                    elementOfType("throws")
                        .map(Node::getTextContent)
                        .map(String::trim)
                        .map(type -> {
                            try {
                                Throwable.class.isAssignableFrom(classLoader.loadClass(type));
                                return null;
                            }
                            catch (ClassNotFoundException | ClassCastException e) {
                                return e;
                            }
                        }),
                    elementOfType("sectionType")
                        .map(element -> {
                            try {
                                sectionType = new CheckableType<>(element);
                                return null;
                            }
                            catch (ClassNotFoundException | ClassCastException | NoSuchMethodException e) {
                                return e;
                            }
                        }),
                    checkableTypeErrors.build()
                )
                    .flatMap(Function.<Stream<? extends Exception>>identity())
                    .filter(Objects::nonNull);

//                typeErrors = typeErrors

                List<Exception> typeErrorsList = typeErrors.collect(Collectors.toList());
                for (Exception e : typeErrorsList)
                    handler.log(System.Logger.Level.ERROR, e);
                if (typeErrorsList.isEmpty())
                    logger.log(System.Logger.Level.TRACE, "No type errors present");
                else {
                    logger.log(System.Logger.Level.TRACE, "Type errors present");
                    Exception err = typeErrorsList.get(0);
                    if (err instanceof ClassNotFoundException e)
                        throw e;
                    else if (err instanceof ClassCastException e)
                        throw e;
                    else if (err instanceof NoSuchMethodException e)
                        throw e;
                    else
                        throw new IllegalStateException("Type error is not a type error", err);
                }

                if (!book.supportsResourceLoading()
                    && (document.getElementsByTagName("File").getLength() > 0 || document.getElementsByTagName("Path").getLength() > 0)) {
                    UnsupportedOperationException e = new UnsupportedOperationException("Book document contains <File> or <Path> element, but Book does not support file resolution");
                    handler.log(System.Logger.Level.ERROR, e);
                    throw e;
                }
            }
            catch (IOException | SAXException | ClassNotFoundException | RuntimeException | NoSuchMethodException e) {
                if (result == null)
                    result = new Result<>(name, TestValidationStatus.INVALID, handler.getLogs());
                documentParseException = e;
                throw e;
            }
            finally {
                if (result == null) {
                    if (handler.getMaxErrorKind() == null)
                        result = new Result<>(name, TestValidationStatus.VALID);
                    else if (handler.getMaxErrorKind() == System.Logger.Level.WARNING)
                        result = new Result<>(name, TestValidationStatus.VALID_WITH_WARNINGS, handler.getLogs());
                    else
                        result = new Result<>(name, TestValidationStatus.INVALID, handler.getLogs());
                }
            }
        }
        else {
            logger.log(System.Logger.Level.TRACE, "A previous attempt to parse the document failed");
            if (documentParseException instanceof RuntimeException e)
                throw e;
            else if (documentParseException instanceof IOException e)
                throw e;
            else if (documentParseException instanceof SAXException e)
                throw e;
            else if (documentParseException instanceof ClassNotFoundException e)
                throw e;
            else
                throw new IllegalStateException("DocumentParseException had unexpected value");
        }
    }

    private Stream<Element> elementOfType(String... types) {
        return Arrays.stream(types)
            .map(document::getElementsByTagName)
            .flatMap(Util::streamNodeList)
            .filter(Element.class::isInstance)
            .map(Element.class::cast);
    }

    public Result<TestValidationStatus> validateBook() throws IOException {
        try {
            parseAndValidateDocument();
        }
        catch (IOException e) {
            throw e;
        }
        catch (Error | RuntimeException e) {
            if (e != documentParseException)
                throw e;
        }
        catch (Exception e) {
            if (e != documentParseException)
                throw new IllegalStateException("Checked exception thrown during document parsing, but result is null", e);
        }
        assert result != null;
        return result;
    }

    private Document getDocument() throws IOException, ClassNotFoundException, SAXException, NoSuchMethodException {
        parseAndValidateDocument();
        return document;
    }

    private CheckableType<? extends Annotation> getSectionType() throws IOException, ClassNotFoundException, SAXException, NoSuchMethodException {
        parseAndValidateDocument();
        return sectionType;
    }

    private CheckableType<? extends Annotation>[] getCheckableTypes() throws IOException, ClassNotFoundException, SAXException, NoSuchMethodException {
        parseAndValidateDocument();
        return checkableTypes;
    }

    public Set<String> getCheckableTypeSet() throws IOException, ClassNotFoundException, SAXException, NoSuchMethodException {
        return checkableTypeSet == null ? checkableTypeSet = Arrays.stream(getCheckableTypes()).map(CheckableType::name).collect(Collectors.toCollection(HashSet::new)) : checkableTypeSet;
    }

    private String[] getResources() throws IOException, ClassNotFoundException, SAXException, NoSuchMethodException {
        if (resources != null)
            return resources;

        NodeList nodes;
        try {
            nodes = (NodeList) xpath.evaluate("/book/meta/rsc", getDocument(), XPathConstants.NODESET);
        }
        catch (XPathExpressionException e) {
            throw new RuntimeException(e);
        }
        resources = new String[nodes.getLength()];
        for (int i = 0; i < resources.length; i++)
            resources[i] = ((Element) nodes.item(i)).getAttribute("href");
        return resources;
    }

    /**
     * Checks according to args
     * @param section             The section to check, or {@code OptionalInt.empty()} to auto-detect.
     * @param checkables          A map between annotations for checkables and the checkables of that type to run.
     * @param targets             A stream of classes that should be checked
     * @return A stream containing the results of all checks
     *
     * @throws IOException If an i/o error occurs
     * @throws ClassNotFoundException If the book references a class that could not be found
     * @throws SAXException If a SAXException is thrown when parsing the book
     * @throws ClassCastException If the book's section or checkable annotations are not actually annotations, or if their
     *              {@code value()} methods don't return {@code int}s.
     * @throws NoSuchMethodException If the book's section or checkable annotations don't have a {@code value()} method
     * @throws IllegalAccessException If the book's section or checkable annotations' {@code value()} method is not accessible
     * @throws InvocationTargetException If the book's section or checkable annotations' {@code value()} method throws an exception
     */
    @SuppressWarnings("RedundantThrows")
    public Result<TestStatus>[] check(@SuppressWarnings("OptionalUsedAsFieldOrParameterType") OptionalInt section, Map<String, boolean[]> checkables,
                                      Stream<Path> targets) throws IOException, ClassNotFoundException, SAXException, NoSuchMethodException,
        IllegalAccessException, InvocationTargetException {
        getDocument();

        NodeList metadataNodes;
//        sectionAnnotation;
//        Map<String, Class<? extends Annotation>> checkableAnnotations = new HashMap<>();
//        checkableAnnotations;
//        Stream<String> resources;
        {
            try {
                metadataNodes = ((Element) xpath.evaluate("/book/meta", document, XPathConstants.NODE)).getChildNodes();
            }
            catch (XPathExpressionException e) {
                throw new RuntimeException(e);
            }
            finally {
                Util.getXPathPool().offer(xpath);
            }
        }

        Element[] meta = Util.streamNodeList(metadataNodes)
            .filter(Element.class::isInstance)
            .map(Element.class::cast)
            .toArray(Element[]::new);
        Class<? extends Annotation> sectionAnnotation = getSectionType().annotation();

        for (String checkable : checkables.keySet()) {
            if (!getCheckableTypeSet().contains(checkable))
                throw new IllegalArgumentException("Checkable \"" + checkable + "\" does not exist");
        }

        Method m = sectionAnnotation.getMethod("value");

        int chapter;
        logger.log(System.Logger.Level.DEBUG, "Section annotation type: %s", sectionAnnotation);
//        logger.log(System.Logger.Level.DEBUG, "Target annotation types: %s", );s
        AtomicInteger detectedChapter = new AtomicInteger(-1);
        List<Class<?>> classes = new PathClassLoader(targets, classLoader).loadAllOwnClasses()
//            .peek(System.out::println)
            .filter(clazz -> {
                System.Logger logger = System.getLogger(Util.generateLoggerName());
                logger.log(System.Logger.Level.TRACE, "%s has annotations %s", clazz, Arrays.toString(clazz.getAnnotations()));
                return Arrays.stream(clazz.getAnnotationsByType(sectionAnnotation))
                    .map(FunctionUtils.unchecked((FunctionThrowsException<Annotation, Integer>) a -> {
                        m.trySetAccessible();
                        return (int) m.invoke(a);
                    }))
                    .anyMatch(c -> {
                        logger.log(System.Logger.Level.TRACE, "Class %s belongs to section %d, expecting %s", clazz, c, section);
                        if (section.isPresent())
                            return c == section.getAsInt();
                        else if (detectedChapter.compareAndExchange(-1, c) != c)
                            throw new IllegalArgumentException("Cannot auto detect section, as classes belonging to chapters "
                                + c + " and " + detectedChapter + " were found");
                        else
                            return true;
                    });
                }
            )
            .collect(Collectors.toList());
        logger.log(System.Logger.Level.TRACE, "Classes: " + classes);
        chapter = section.orElseGet(detectedChapter::get);



        Runtime.getRuntime().addShutdownHook(dirResetter);
        if (tempDir == null)
            tempDir = Files.createTempDirectory(null);
        changeDirectory(book.loadResources(tempDir, getResources()));

        Node ch;
        try {
            ch = (Node) xpath.evaluate("/book/chapter[@num='" + chapter + "']", document, XPathConstants.NODE);
        }
        catch (XPathExpressionException e) {
            throw new RuntimeException(e);
        }

        InputStream prevIn = System.in;
        PrintStream prevOut = System.out;
        PrintStream prevErr = System.err;
        try {
             List<Future<Result<TestStatus>>> futures = Arrays.stream(getCheckableTypes())
                 .parallel()
                 .map(a -> new CheckerTargetGroup<>(a, checkables.getOrDefault(a.name(), new boolean[0])))
                 .flatMap(FunctionUtils.unchecked((FunctionThrowsException<CheckerTargetGroup<?>, Stream<Test>>) (e -> {
                     e.addPotentialTargets(classes.iterator());
                     return e.apply(ch);
                 })))
                 .map(Test::start)
                 .collect(Collectors.toList());
             Result<TestStatus>[] r = new Result[futures.size()];
             Iterator<Future<Result<TestStatus>>> iter = futures.iterator();
             for (int j = 0; iter.hasNext(); j++)
                 r[j] = iter.next().get();
             return r;
        }
        catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
        finally {
            System.setIn(prevIn);
            System.setOut(prevOut);
            System.setErr(prevErr);
            resetDir();
            Runtime.getRuntime().removeShutdownHook(dirResetter);
        }
    }

    private static void resetDir() {
        changeDirectory(initDir);
    }

    private void removeTempDir() throws IOException {
        if (tempDir == null)
            return;
        Files.walk(tempDir)
            .sorted(Comparator.comparing(Path::getNameCount))
            .forEach(FunctionUtils.unchecked(Files::delete));
    }

    private static void changeDirectory(Path path) {
        nativeLoaderLock.lock();
        try {
            if (!triedLoadNative) {
                Path tempDir = null;
                Path tempFile = null;
                try {
                    tempDir = Files.createTempDirectory(null);
                    tempFile = tempDir.resolve(System.mapLibraryName("native"));
                    Files.copy(Objects.requireNonNull(BookReader.class.getResourceAsStream("native/"
                        + System.getProperty("os.arch") + "/"
                        + System.mapLibraryName("native"))), tempFile);
                    System.load(tempFile.toAbsolutePath().toString());
                    loadedNativeSuccess = true;
                }
                catch (UnsatisfiedLinkError | IOException | NullPointerException e) {
                    loadedNativeSuccess = false;
                    System.getLogger(BookReader.class.getName()).log(System.Logger.Level.WARNING,
                        "Failed to load native library for " + System.getProperty("os.name")
                            + " on " + System.getProperty("os.arch"),
                        e);
                }
                finally {
                    try {
                        if (tempFile != null)
                            Files.deleteIfExists(tempFile);
                        if (tempDir != null)
                            Files.deleteIfExists(tempDir);
                    }
                    catch (IOException e) {
                        System.getLogger(Util.generateLoggerName()).log(System.Logger.Level.DEBUG, "Error deleting temp file for native library", e);
                    }
                }
            }
        }
        finally {
            triedLoadNative = true;
            nativeLoaderLock.unlock();
        }
        if (loadedNativeSuccess)
            changeDirectory(path.toString());
    }

    private static native void changeDirectory(String path);

    public boolean validateResults() throws IOException {
        if (isInvalidated)
            return false;

        if (document == null)
            return true;

        MessageDigest oldDigest, newDigest;

        try {
            oldDigest = (MessageDigest) digest.clone();
            newDigest = digests(1)[0];
        }
        catch (CloneNotSupportedException e) {
            oldDigest = digest;
            MessageDigest[] digests = digests(2);
            digest = digests[0];
            newDigest = digests[1];
        }

        return !(isInvalidated = !MessageDigest.isEqual(oldDigest.digest(), newDigest.digest()));
    }

    private MessageDigest[] digests(int count) throws IOException {
        MessageDigest[] digests = new MessageDigest[count];
        try {
            for (MessageDigest digest : digests)
                digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        if (document != null) {
            for (MessageDigest digest : digests)
                digest.update(book.getInputStream().readAllBytes());
        }

        if (tempDir != null) {
            Path newTempDir = Files.createTempDirectory(null);
            try {
                book.loadResources(newTempDir, getResources());
            }
            catch (ClassNotFoundException | SAXException | NoSuchMethodException e) {
                throw new IllegalStateException("getResources() should not throw exception if document is loaded");
            }
            OutputStream os = OutputStream.nullOutputStream();
            for (MessageDigest digest : digests)
                os = new DigestOutputStream(os, digest);
            try (ZipOutputStream out = new ZipOutputStream(os)) {
                Files.walk(tempDir)
                    .forEachOrdered(FunctionUtils.unchecked((ConsumerThrowsException<Path>) p -> {
                        String name = tempDir.relativize(p).toString();
                        if (Files.isDirectory(p))
                            name += '/';
                        out.putNextEntry(new ZipEntry(name));
                        if (!Files.isDirectory(p))
                            Files.copy(p, out);
                    }));
            }
        }
        return digests;
    }

    class CheckableType<T extends Annotation> {
        private final String name;
        private final Class<T> annotation;
        private final Method value;

        /**
         * Constructs a {@code CheckableType} from an {@code Element}
         *
         * @param e The element to construct from
         * @throws ClassNotFoundException If the class linked in the element could not be found.
         * @throws ClassCastException If the class linked in the element is not an annotation, or
         *          if the {@code value()} method does not return an int.
         * @throws NoSuchMethodException If the annotation does not have a {@code value()} method.
         */
        public CheckableType(Element e) throws ClassNotFoundException, NoSuchMethodException {
            this.name = e.getAttribute("name");
            Class<?> annotation = classLoader.loadClass(e.getAttribute("annotation"));
            if (!Annotation.class.isAssignableFrom(annotation))
                throw new ClassCastException("Cannot convert " + annotation.getName() + " to java.lang.Annotation");
            value = annotation.getMethod("value");
            if (!value.getReturnType().equals(int.class))
                throw new ClassCastException("Cannot convert " + value.getGenericReturnType() + " to int");
            value.trySetAccessible();
            this.annotation = (Class<T>) annotation;
        }

        public int value(T target) throws InvocationTargetException, IllegalAccessException {
            System.getLogger(Util.generateLoggerName()).log(System.Logger.Level.TRACE, "Getting %s of %s", name, target);
            return (int) value.invoke(target);
        }

        public String name() {
            return name;
        }

        public Class<T> annotation() {
            return annotation;
        }
    }
}
