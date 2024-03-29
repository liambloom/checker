package dev.liambloom.checker.internal;

import dev.liambloom.checker.TestStatus;
import dev.liambloom.checker.books.Result;
import dev.liambloom.util.function.FunctionThrowsException;
import dev.liambloom.util.function.FunctionUtils;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface Test {
    ReadWriteLock testLock = new ReentrantReadWriteLock();
    ExecutorService readOnlyTest = Executors.newCachedThreadPool();
    ExecutorService writingTest = Executors.newSingleThreadExecutor();

    Future<Result<TestStatus>> start();

    static Test of(String name, TestStatus status) {
        return of(new Result<>(name, status));
    }

    static Test of(Result<TestStatus> result) {
        return () -> CompletableFuture.completedFuture(result);
    }

    static Test multi(String name, Stream<Test> tests) {
        return () -> readOnlyTest.submit(() -> {
            List<Result<TestStatus>> subResults = tests.sequential()
                .map(Test::start)
                .map(FunctionUtils.unchecked((FunctionThrowsException<Future<Result<TestStatus>>, Result<TestStatus>>) Future::get))
                .collect(Collectors.toList());
            return new Result<>(
                name,
                subResults.stream()
                    .map(Result::status)
                    .max(Comparator.naturalOrder())
                    .orElseThrow(),
                subResults);
        });
    }

//    ReadWriteLock testLock = new ReentrantReadWriteLock();
//    ExecutorService readOnlyTest = Executors.newCachedThreadPool();
//    ExecutorService writingTest = Executors.newSingleThreadExecutor();
//
//    static Test withFixedResult(Result<TestStatus> result) {
//        return () -> CompletableFuture.completedFuture(result);
//    }
//
//    static Test multiTest(String name, Targets targets, Node testGroup) {
//        System.getLogger(Util.generateLoggerName()).log(System.Logger.Level.TRACE, "MultiTest %s with targets %s", name, targets);
//        Stream<Test> subTests = Util.streamNodeList(testGroup.getChildNodes())
//            .filter(Element.class::isInstance)
//            .map(Element.class::cast)
//            .flatMap(node -> {
//                Set<? extends AnnotatedElement> filteredTargets = switch (node.getTagName()) {
//                    case "method" -> targets.methods();
//                    case "constructor" -> targets.constructors();
//                    case "program" -> {
//                        Set<AnnotatedElement> projectTargets = targets.classes().stream()
//                            .filter(t -> {
//                                try {
//                                    Method main = t.getDeclaredMethod("main", String[].class);
//                                    int mod = main.getModifiers();
//                                    return Modifier.isPublic(mod) && Modifier.isStatic(mod) && main.getReturnType() == void.class;
//                                }
//                                catch (NoSuchMethodException e) {
//                                    return false;
//                                }
//                            })
//                            .collect(Collectors.toSet());
//                        projectTargets.addAll(targets.methods());
//                        yield projectTargets;
//                    }
//                    default -> throw new IllegalStateException("Target type " + node.getTagName() + " not recognized");
//                };
//                System.getLogger(Util.generateLoggerName()).log(System.Logger.Level.TRACE, "Test of %s filtered for, result: %s; targets: %s", name, node.getTagName(), targets);
//                if (filteredTargets.isEmpty())
//                    return Stream.of(Test.withFixedResult(new Result<>(name, TestStatus.INCOMPLETE)));
//                else if (filteredTargets.size() == 1) {
//                    AnnotatedElement target = filteredTargets.iterator().next();
//                    if (target instanceof Constructor<?> c)
//                        return StaticExecutableTest.stream(name, c, c::newInstance, targets, node);
//                    else if (target instanceof Class<?> c) {
//                        try {
//                            target = c.getDeclaredMethod("main", String[].class);
//                        }
//                        catch (NoSuchMethodException e) {
//                            throw new IllegalStateException("Unreachable: Class should have been filtered out", e);
//                        }
//                    }
//                    else if (target instanceof Method m && !Modifier.isStatic(m.getModifiers())) {
//                        ReLogger logger = new ReLogger(Test.class.name());
//                        logger.log(System.Logger.Level.ERROR, "Bad Header: Instance method %s should be static", Util.executableToString(m));
//                        return Stream.of(Test.withFixedResult(new Result<>(name, TestStatus.BAD_HEADER, logger)));
//                    }
//                    if (target instanceof Method m)
//                        return StaticExecutableTest.stream(name, m, p -> m.invoke(null, p), targets, node);
//
//                    throw new IllegalStateException("Unreachable: Target of impossible type");
//                }
//                else
//                    throw new NotYetImplementedError("Resolving test target from multiple options");
//            });
//        return () -> readOnlyTest.submit(() -> {
//            List<Result<TestStatus>> subResults = subTests.sequential()
//                .map(Test::start)
//                .map(FunctionUtils.unchecked((FunctionThrowsException<Future<Result<TestStatus>>, Result<TestStatus>>) Future::get))
//                .collect(Collectors.toList());
//            return new Result<>(
//                name,
//                subResults.stream()
//                    .map(Result::status)
//                    .max(Comparator.naturalOrder())
//                    .orElseThrow(),
//                subResults);
//        });
//    }
}
