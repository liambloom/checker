package dev.liambloom.checker.bjp.api;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public record Result<T extends Result.Status>(String name, T status, Optional<ByteArrayOutputStream> console, List<? extends Result<? extends T>> subResults) {
    public Result(String name, T status, @SuppressWarnings("OptionalUsedAsFieldOrParameterType") Optional<ByteArrayOutputStream> console) {
        this(name, status, console, Collections.emptyList());
    }

    public Result(String name, T status) {
        this(name, status, Optional.empty());
    }

    // This makes more sense as an abstract class, but enums can't extend abstract classes
    public interface Status {
        Color color();
    }
}