package dev.liambloom.checker;

import dev.liambloom.checker.books.Color;
import dev.liambloom.checker.books.Result;

public enum TestStatus implements Result.Status {
    /*
     * The checkable item has been submitted
     */
//    SUBMITTED(Color.GREEN),

    /*
     * The checkable item was submitted, and has since been updated. Note that
     * this does not affect the submitted status of the checkable item.
     */
//    UPDATED(Color.CYAN),

    /**
     * The checkable item passed all tests
     */
    OK(Color.GREEN),

    /*
     * The checkable item has not been completed
     */
    //MISSING(Color.GRAY),

    /**
     * Parts of the checkable item are missing
     */
    INCOMPLETE(Color.GRAY),

    /*
     * The checkable item was correct at some point, but currently does not pass
     * all tests. This is most likely if there is a single method that
     * is modified in multiple exercises/
     */
//    PREVIOUSLY_DONE(Color.YELLOW),

    /**
     * A method or field corresponding to the checkable item is not accessible,
     * either because it is not public or because its package is not
     * exported from its respective module.
     */
    BAD_HEADER(Color.RED),

    /**
     * The checkable item did not pass all tests
     */
    FAILED(Color.RED),

    /**
     * No checks exist for the checkable item
     */
    NO_SUCH_TEST(Color.RED);

    private final Color color;

    TestStatus(Color color) {
        this.color = color;
    }

    public Color color() {
        return color;
    }
}
