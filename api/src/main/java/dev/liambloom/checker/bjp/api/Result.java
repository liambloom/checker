package dev.liambloom.checker.bjp.api;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public record Result(String name, Status status, Optional<ByteArrayOutputStream> console, List<Result> subResults) {
    public Result(String name, Status status, @SuppressWarnings("OptionalUsedAsFieldOrParameterType") Optional<ByteArrayOutputStream> console) {
        this(name, status, console, Collections.emptyList());
    }

    public Result(String name, Status status) {
        this(name, status, Optional.empty());
    }

    // This makes more sense as an abstract class, but enums can't extend abstract classes
    public interface Status {
        Color color();
    }
}