package dev.liambloom.checker.bjp.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.BiFunction;

@FunctionalInterface
public interface BiFunctionThrowsIOException<T, U, R> extends BiFunction<T, U, R> {
    @Override
    default R apply(T t, U u) {
        try {
            return applyThrows(t, u);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    R applyThrows(T t, U u) throws IOException;
}