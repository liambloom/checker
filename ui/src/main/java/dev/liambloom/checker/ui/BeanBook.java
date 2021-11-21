package dev.liambloom.checker.ui;

import dev.liambloom.checker.Book;
import dev.liambloom.checker.Result;
import dev.liambloom.checker.TestValidationStatus;
import dev.liambloom.checker.URLBook;
import dev.liambloom.util.function.FunctionUtils;
import javafx.beans.property.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.Optional;

public class BeanBook extends URLBook {
    private final URLBook inner;
    public final StringProperty name;
    public final ObjectProperty<URL> url;
    private final ReadOnlyObjectWrapper<Result<TestValidationStatus>> validationResultWrapper = new ReadOnlyObjectWrapper<>();
    public final ReadOnlyObjectProperty<Result<TestValidationStatus>> validationResult = validationResultWrapper.getReadOnlyProperty();
    private final ReadOnlyBooleanWrapper existsWrapper = new ReadOnlyBooleanWrapper();
    public final ReadOnlyBooleanProperty exists = existsWrapper.getReadOnlyProperty();

    public BeanBook(URLBook inner) {
        this.inner = inner;
        name = new SimpleStringProperty(inner.getName());
        name.addListener((observable, oldValue, newValue) -> {
            if (inner instanceof ModifiableBook mb)
                mb.rename(newValue);
            else
                throw new UnsupportedOperationException("Attempt to rename BeanBook that wraps an unmodifiable book");
        });
        path = new SimpleObjectProperty<>(inner instanceof PathBook pb ? Optional.of(pb.getPath()) : Optional.empty());
        path.addListener(((observable, oldValue, newValue) -> {
            if (inner instanceof PathBook pb) {
                try {
                    pb.setPath(newValue.orElseThrow());
                    onChange(null);
                }
                catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            else
                throw new UnsupportedOperationException("Attempt to change path of BeanBook that wraps a non-path book");
        }));
        onChange(null);
        if (inner instanceof ModifiableBook mb)
            mb.addWatcher(FunctionUtils.unchecked(this::onChange));
    }

    private void onChange(WatchEvent<Path> e) throws IOException {
        validationResultWrapper.set(inner.validate());
        existsWrapper.set(inner.exists());
    }

    public boolean isModifiable() {
        return inner instanceof ModifiableBook;
    }

    public boolean hasPath() {
        return inner instanceof PathBook;
    }

    public String getName() {
        return name.get();
    }

    public void setName(String value) {
        name.set(value);
    }

    public StringProperty nameProperty() {
        return name;
    }

    public Optional<Path> getPath() {
        return path.get();
    }

    public void setPath(Path value) {
        path.set(Optional.of(value));
    }

    public ObjectProperty<Optional<Path>> pathProperty() {
        return path;
    }

    public Result<TestValidationStatus> getValidationResult() {
        return validationResult.get();
    }

    public ReadOnlyObjectProperty<Result<TestValidationStatus>> validationResultProperty() {
        return validationResult;
    }

    public boolean getExists() {
        return exists.get();
    }

    public ReadOnlyBooleanProperty existsProperty() {
        return exists;
    }
}
