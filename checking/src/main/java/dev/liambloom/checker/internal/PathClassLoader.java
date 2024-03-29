package dev.liambloom.checker.internal;

import dev.liambloom.util.function.FunctionThrowsException;
import dev.liambloom.util.function.FunctionUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

public final class PathClassLoader extends ClassLoader {

    private final SortedMap<String, LazyClass> classes;// = Collections.synchronizedSortedMap(new TreeMap<>());

    public PathClassLoader(Stream<Path> glob) throws IOException {
        this(glob, getSystemClassLoader());
    }

    public PathClassLoader(Stream<Path> glob, ClassLoader parent) throws IOException {
        super(parent);
        //noinspection RedundantCast
        classes = glob
            .map(FunctionUtils.unchecked((FunctionThrowsException<Path, Path>) Path::toRealPath))
            // Intellij says that the cast to (Function<...>) is redundant, but the code doesn't compile without it, so Imma say it's not
            .flatMap((Function<Path, Stream<? extends ClassSource>>)
                FunctionUtils.unchecked((FunctionThrowsException<Path, Stream<? extends ClassSource>>) p -> {
                if (p.toString().endsWith(".jar"))
                    return CompressedClassSource.allSources(new JarFile(p.toFile())).stream();
                else if (p.toString().endsWith(".class"))
                    return Stream.of(new PathClassSource(p));
                else
                    throw new IllegalArgumentException("Unable to load classes from `" + p + "' because it is not a .class or .jar file");
            }))
            .collect(Collectors.toMap(
                s -> new StringBuilder(s.path()).reverse().toString(),
                FunctionUtils.unchecked((FunctionThrowsException<ClassSource, LazyClass>) s -> new LazyClass(s.bytes())),
                (v1, v2) -> v1.markAsDuplicate(),
                TreeMap<String, LazyClass>::new
            ));
//        System.getLogger(Long.toString(System.identityHashCode(this))).log(System.Logger.Level.TRACE, "new PathClassLoader" + classes);
    }

    public Stream<Class<?>> loadAllOwnClasses() {
        return classes.values()
            .stream()
            .map(c -> c.get(null));
        //.toArray(Class<?>[]::new);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        StringBuilder nameMut = new StringBuilder(name).reverse();
        String from = nameMut.toString();
        nameMut.setCharAt(name.length() - 1, (char) (nameMut.charAt(name.length() - 1) + 1));
        String to = nameMut.toString();
        Class<?> r = null;
        for (LazyClass clazz : classes.subMap(from, to).values()) {
            try {
                // Go through all of them just in case there's a duplicate class name
                r = clazz.get(name);
            }
            catch (NoClassDefFoundError ignored) {
            }
        }
        if (r == null)
            throw new ClassNotFoundException('"' + name + '"');
        return r;
    }

    private class LazyClass {
        private Class<?> clazz = null;
        private byte[] bytes;
        private boolean isDuplicate = false;

        public LazyClass(byte[] bytes) {
            this.bytes = bytes;
        }

        public synchronized Class<?> get(String name) {
            if (clazz == null) {
                clazz = defineClass(name, bytes, 0, bytes.length);
                if (isDuplicate)
                    defineClass(name, bytes, 0, bytes.length);
                bytes = null;
            }
            else if (name != null && !clazz.getName().equals(name))
                throw new NoClassDefFoundError(name);
            return clazz;
        }

        public LazyClass markAsDuplicate() {
            isDuplicate = true;
            return this;
        }
    }
}

interface ClassSource {
    String path();

    byte[] bytes() throws IOException;
}

class PathClassSource implements ClassSource {
    private final Path p;

    public PathClassSource(Path p) {
        this.p = p.toAbsolutePath();
    }

    @Override
    public String path() {
        return p.toString().substring(0, p.toString().length() - 6).replace(File.separatorChar, '.');
    }

    @Override
    public byte[] bytes() throws IOException {
        return Files.readAllBytes(p);
    }
}

class CompressedClassSource implements ClassSource {
    public static final Pattern MR_CLASS = Pattern.compile("META-INF/version/\\d+/");
    private static final int JAVA_VERSION = Runtime.version().feature();

    private final ZipEntry entry;
    private final JarFile jar;
    private final boolean entryIsMr;

    private static Optional<CompressedClassSource> construct(JarFile jar, ZipEntry entry, boolean jarIsMr) {
        boolean entryIsMr = MR_CLASS.matcher(entry.getName()).lookingAt();

        return jarIsMr || !entryIsMr ? Optional.of(new CompressedClassSource(jar, entry, entryIsMr)).filter(s -> s.version() <= JAVA_VERSION) : Optional.empty();
    }

    private CompressedClassSource(JarFile jar, ZipEntry entry, boolean entryIsMr) {
        this.entry = entry;
        this.jar = jar;
        this.entryIsMr = entryIsMr;
    }

    @Override
    public String path() {
        String p = entry.getName();
        if (entryIsMr)
            p = p.substring(p.indexOf('/', 17));
        return p.substring(0, p.length() - 6).replace('/', '.');
    }

    @Override
    public byte[] bytes() throws IOException {
        InputStream stream = jar.getInputStream(entry);
        ByteArrayOutputStream bufs = new ByteArrayOutputStream();
        byte[] buf = new byte[1024]; // 1kb
        int len;
        while ((len = stream.read(buf)) != -1)
            bufs.write(buf, 0, len);
        return bufs.toByteArray();
    }

    public static Collection<CompressedClassSource> allSources(JarFile jar) throws IOException {
        boolean isMrJar = isMrJar(jar);
        Enumeration<JarEntry> entries = jar.entries();
        Map<String, CompressedClassSource> entryMap = new HashMap<>();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.toLowerCase(Locale.ENGLISH).endsWith(".class"))
                continue;
            CompressedClassSource.construct(jar, entry, isMrJar)
                .ifPresent(src -> entryMap.compute(src.path(), (k, val) -> val == null || val.version() < src.version() ? src : val));
        }
        return entryMap.values();
    }

    public int version() {
        String name = entry.getName();
        return entryIsMr ? Integer.parseInt(name.substring(17, name.indexOf('/', 17))) : 8;
    }

    private static boolean isMrJar(JarFile jar) throws IOException {
        return JAVA_VERSION > 8 && Optional.ofNullable(jar.getManifest().getMainAttributes().getValue(Attributes.Name.MULTI_RELEASE))
            .map(s -> s.toLowerCase(Locale.ENGLISH))
            .map("true"::equals)
            .orElse(false);
    }
}

