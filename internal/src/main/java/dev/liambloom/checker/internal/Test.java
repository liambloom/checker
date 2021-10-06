package dev.liambloom.checker.internal;

import dev.liambloom.checker.shared.Result;
import dev.liambloom.checker.shared.LogKind;
import dev.liambloom.checker.shared.PrintStreamLogger;
import dev.liambloom.checker.shared.Util;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodType;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.*;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface Test {
    Result<TestStatus> run();

    static Test withFixedResult(Result<TestStatus> result) {
        return () -> result;
    }

    static Test multiTest(String name, Targets targets, Node testGroup, UnaryOperatorThrowsIOException<Path> resolver) {
        Stream<Test> subTests = Util.streamNodeList(testGroup.getChildNodes())
            .map(Element.class::cast)
            .flatMap(node -> {
                switch (node.getTagName()) {
                    case "method" -> {
                        if (targets.methods().isEmpty())
                            return Stream.of(Test.withFixedResult(new Result<>(name, TestStatus.INCOMPLETE)));
                        else if (targets.methods().size() == 1) {
                            Method method = targets.methods().iterator().next();
                            if (!Modifier.isStatic(method.getModifiers())) {
                                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                                new BadHeaderException("Instance method " + Util.executableToString(method) + " should be static").printStackTrace(new PrintStream(outputStream));
                                return Stream.of(Test.withFixedResult(new Result<>(name, TestStatus.BAD_HEADER, Optional.of(outputStream))));
                            }
                            return Test.streamFromStaticExecutable(name, method, targets, testGroup, resolver);
                        }
                        else {

                        }
                    }
                    case "constructor" -> {
                        if (targets.constructors().isEmpty())
                            return Stream.of(Test.withFixedResult(new Result<>(name, TestStatus.INCOMPLETE)));
                        else if (targets.constructors().size() == 1)
                            return Test.streamFromStaticExecutable(name, targets.constructors().iterator().next(), targets, testGroup, resolver);
                        else {
                            // TODO
                        }
                    }
                    case "project" -> {
                        // TODO
                    }
                    default -> throw new IllegalStateException("This should not have passed the schema");
                }
                return null; // TODO
            });
        return () -> {
            List<Result<TestStatus>> subResults = subTests.map(Test::run).collect(Collectors.toList());
            return new Result<>(
                name,
                subResults.stream()
                    .map(Result::status)
                    .max(Comparator.naturalOrder())
                    .get(),
                Optional.empty(),
                subResults);
        };
    }

    static Stream<Test> streamFromStaticExecutable(String name, Executable executable, Targets targets, Node node, UnaryOperatorThrowsIOException<Path> resolver) {
        if (!executable.canAccess(null) && !executable.trySetAccessible()) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            new BadHeaderException(Case.convert(Util.getAccessibilityModifierName(executable), Case.SENTENCE)
                + ' '
                + executable.getClass().getSimpleName().toLowerCase(Locale.ENGLISH)
                + ' '
                + Util.executableToString(executable)
                + " is not accessible")
                .printStackTrace(new PrintStream(outputStream));
            return Stream.of(Test.withFixedResult(new Result<>(name, TestStatus.BAD_HEADER, Optional.of(outputStream))));
        }
        XPath xpath = Checker.getXPathPool().get();
        NodeList expectedParamNodes;
        try {
            expectedParamNodes = (NodeList) xpath.evaluate("parameters/parameter", node, XPathConstants.NODESET);
        }
        catch (XPathExpressionException e) {
            throw new RuntimeException(e);
        }
        finally {
            Checker.getXPathPool().offer(xpath);
        }
        Class<?>[] params = MethodType.methodType(void.class, executable.getParameterTypes()).wrap().parameterArray();
        Class<?>[] expectedParams = MethodType.methodType(void.class, Util.streamNodeList(expectedParamNodes)
            .map(Node::getTextContent)
            .map(String::trim)
            .map(n -> {
                try {
                    return Util.loadClass(new ClassLoader() {
                        @Override
                        protected Class<?> findClass(String name) throws ClassNotFoundException {
                            if (name.equals("this"))
                                return executable.getDeclaringClass();
                            else
                                throw new ClassNotFoundException(name);
                        }
                    }, n);
                }
                catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("Invalid document passed into Test.streamFromStaticExecutable", e);
                }
            })
            .collect(Collectors.toList())
        )
            .wrap()
            .parameterArray();
        boolean isCorrectParams = false;
        if (params.length == expectedParams.length || executable.isVarArgs() && expectedParams.length >= params.length - 1) {
            for (int i = 0; i < expectedParams.length; i++) {
                if (!(isCorrectParams = i < params.length && params[i].isAssignableFrom(expectedParams[i])
                    && (i != params.length - 1 || executable.isVarArgs() && params.length == expectedParams.length)
                    || executable.isVarArgs() && i >= params.length - 1 && params[params.length - 1].getComponentType().isAssignableFrom(expectedParams[i])))
                    break;
            }
        }

        if (isCorrectParams) {
            NodeList tests;
            try {
                tests = (NodeList) xpath.evaluate("test", node, XPathConstants.NODESET);
            }
            catch (XPathExpressionException e) {
                throw new RuntimeException(e);
            }
            return IntStream.range(0, tests.getLength())
                .mapToObj(i -> Test.staticExecutableTest("Test " + i, executable, targets, tests.item(i), resolver));
        }
        else {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new PrintStreamLogger(new PrintStream(out))
                .log(LogKind.NOTICE, executable.getClass().getSimpleName()
                    + ' '
                    + Util.executableToString(executable)
                    + " was detected, but did not have the expected parameters ("
                    + Util.streamNodeList(expectedParamNodes)
                    .map(Node::getTextContent)
                    .collect(Collectors.joining(", "))
                    + ')');
            return Stream.of(Test.withFixedResult(new Result<>(name, TestStatus.INCOMPLETE)));
        }
    }

    static Test staticExecutableTest(String name, Executable executable, Targets targets, Node test, UnaryOperatorThrowsIOException<Path> resolver) {
        int i = 0;
        NodeList children = test.getChildNodes();
        InputStream in = ((Element) children.item(i)).getTagName().equals("System.in")
            ? new ByteArrayInputStream(children.item(i++).getTextContent().getBytes())
            : InputStream.nullInputStream();
        if (((Element) children.item(i)).getTagName().equals("this")) // TODO: Update Schema
            throw new IllegalArgumentException("Element <this> invalid in top level method");
        PrePost[] args = ((Element) children.item(i)).getTagName().equals("arguments")
            ? Util.streamNodeList(children.item(i++).getChildNodes())
                .map(Element.class::cast)
                .map(e -> new PrePost(e, resolver))
                .toArray(PrePost[]::new)
            : new PrePost[0];
        Class<? extends Throwable> expectedThrows;
        Element expectedReturns;
        String expectedPrints;
        if (((Element) children.item(i)).getTagName().equals("throws")) {
            try {
                expectedThrows = (Class<? extends Throwable>) ClassLoader.getSystemClassLoader().loadClass(children.item(i++).getTextContent());
            }
            catch (ClassNotFoundException | ClassCastException e) {
                throw new IllegalStateException("This should not have passed validation.", e);
            }
            expectedReturns = null;
            expectedPrints = null;
        }
        else {
            expectedThrows = null;
            expectedReturns = Optional.ofNullable(children.item(i))
                .map(Element.class::cast)
                .filter(n -> n.getTagName().equals("returns"))
                .orElse(null);
            if (expectedReturns != null)
                i++;
            String rawExpectedPrints = Optional.ofNullable(children.item(i))
                .map(Element.class::cast)
                .map(Element::getTextContent)
                .map(String::stripIndent)
                .orElse(null);
            if (rawExpectedPrints == null)
                expectedPrints = null;
            else {
                expectedPrints = Util.cleansePrint(rawExpectedPrints);
            }
        }
        return null; // TODO
    }

//    static Test

    /*protected InputStream sysIn = new InputStream() { // Or from xml
        @Override
        public int read() throws IOException {
            return -1;
        }
    };
    protected Object[] args = new Object[0]; // TODO
    protected String prints = null;
    protected Element returns = null;
    protected Class<? extends Throwable> throwsErr = null;
    //protected Map<Matcher, Object> pre;
    //protected Map<Matcher, Object> post;

    //private Test() {}

    public Test(List<AnnotatedElement> targets, Node tests) {

    }

    public Result run() {
        return null; // TODO
    }*/

    /*public final class Builder {
        private Test test = new Test();

        public Test build() {
            if (test == null)
                throw new IllegalStateException();
            try {
                return test;
            }
            finally {
                test = null;
            }
        }

        public Builder prints(String s) {
            if (test.prints != null)

            test.prints = s;
            return this;
        }

        public Builder returns(Object o) {

        }
    }*/

    /*public Result test(Method m) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream outWrapper = new PrintStream(out);
        InputStream defaultIn = System.in;
        PrintStream defaultOut = System.out;
        PrintStream defaultErr = System.err;
        System.setIn(sysIn);
        System.setOut(outWrapper);
        System.setErr(outWrapper);
        Object target;
        if (Modifier.isStatic(m.getModifiers())) // TODO: check if "this" is defined
            target = null;
        else {
            // TODO
            target = null;
        }
        try {
            m.setAccessible(true);
        }
        catch (InaccessibleObjectException e) {
            //TODO: return new TestResult(this.type, this.num, ); // Method inaccessible
        }
        try {
            Object r = m.invoke(target, args);

            if (!normalizeLineSeparators(out.toString()).equals(normalizeLineSeparators(prints))) {}
                // TODO: return new TestResult(this.type, this.num, TestResult.Variant.INCORRECT, );
        }
        catch (Throwable e) {
            if (throwsErr == null || !throwsErr.isInstance(e))
                return null; // TODO new TestResult(this.type, this.num, TestResult.Variant.INCORRECT, e, out);
            else
                return null;// TODO new TestResult(this.type, this.num, TestResult.Variant.CORRECT);
        }
        finally {
            System.setIn(defaultIn);
            System.setOut(defaultOut);
            System.setErr(defaultErr);
        }
        return null;
    }

    */



    /*public TestResult test(Class<?> clazz) {
        for (Matcher matcher : pre.keySet()) {
            Field field = matcher.findField(clazz);
            field.setAccessible(true);
            field.set()
        }
        return null;
    }

    public TestResult test(Method method) {
        // TODO
        return null;
    }*/
}