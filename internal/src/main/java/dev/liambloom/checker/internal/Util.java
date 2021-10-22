package dev.liambloom.checker.internal;

import dev.liambloom.util.ResourcePool;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public final class Util {
    private static final ResourcePool<XPath> xPathPool = new ResourcePool<>(XPathFactory.newInstance()::newXPath);

    private Util() {
    }

    public static final Pattern TRAILING_SPACES_AND_NEWLINE = Pattern.compile("\\s*\\R");

    public static String generateLoggerName() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass().getName()
            + "#" + ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    }

    /*public static String normalizeLineSeparators(String s) {
        return LINE_SEPARATOR.matcher(s).replaceAll(System.lineSeparator());
    }*/

    public static String cleansePrint(String raw) {
        String[] lines = Util.TRAILING_SPACES_AND_NEWLINE.split(raw);
        Stream<String> linesStream = Arrays.stream(lines);
        if (lines[lines.length - 1].isBlank())
            linesStream = linesStream.limit(lines.length - 1);
        if (lines[0].isEmpty())
            linesStream = linesStream.skip(1);
        return linesStream
            .collect(Collectors.joining(System.lineSeparator()));
    }

    public static String executableToString(Executable e) {
        StringBuilder builder = new StringBuilder()
            .append(e.getDeclaringClass().getName())
            .append('.')
            .append(e.getName())
            .append('(');
        Type[] args = e.getGenericParameterTypes();
        for (int i = 0; i < args.length; i++) {
            if (i + 1 == args.length && e.isVarArgs()) {
                builder.append(args[i].getTypeName())
                    .replace(builder.length() - 2, builder.length(), "...");
            }
            else
                builder.append(args[i].getTypeName());
        }
        builder.append(')');
        return builder.toString();
    }

    public static Stream<Node> streamNodeList(NodeList nodeList) {
        return IntStream.range(0, nodeList.getLength())
            .parallel()
            .mapToObj(nodeList::item);
    }

    public static String getAccessibilityModifierName(int modifier) {
        if (Modifier.isPublic(modifier))
            return "public";
        else if (Modifier.isProtected(modifier))
            return "protected";
        else if (Modifier.isPrivate(modifier))
            return "private";
        else
            return "package-private";
    }

    public static String getAccessibilityModifierName(Member m) {
        return getAccessibilityModifierName(m.getModifiers());
    }

    public static Class<?> loadClass(String name) throws ClassNotFoundException {
        return loadClass(ClassLoader.getSystemClassLoader(), name);
    }

    public static Class<?> loadClass(ClassLoader loader, String name) throws ClassNotFoundException {
        int arrayDepth = 0;
        StringBuilder componentName = new StringBuilder(name);
        componentName.trimToSize();
        while (componentName.length() > 2 && componentName.charAt(componentName.length() - 2) == '[' && componentName.charAt(componentName.length() - 1) == ']') {
            arrayDepth++;
            componentName.delete(componentName.length() - 2, componentName.length());
        }
        Class<?> clazz = switch (name) {
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "boolean" -> boolean.class;
            case "char" -> char.class;
            default -> loader.loadClass(name);
        };
        for (int i = 0; i < arrayDepth; i++)
            clazz = clazz.arrayType();
        return clazz;
    }

    public static ResourcePool<XPath> getXPathPool() {
        return xPathPool;
    }
}