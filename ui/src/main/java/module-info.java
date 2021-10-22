import dev.liambloom.checker.ui.cli.PrintStreamLoggerFinder;

module dev.liambloom.checker.ui {
    requires static java.xml; // why is this static?
    requires java.prefs;
    requires javafx.graphics;
    requires javafx.controls;
    requires javafx.fxml;
    requires dev.liambloom.checker.internal;
    requires dev.liambloom.checker.books;
    requires dev.liambloom.util.function;
    requires dev.liambloom.util.base;
    requires net.harawata.appdirs;
    exports dev.liambloom.checker.ui.cli;
    exports dev.liambloom.checker.ui.gui;
    exports dev.liambloom.checker.ui;
    provides System.LoggerFinder with PrintStreamLoggerFinder;
    opens views;
    opens css;
    opens dev.liambloom.checker.ui.gui to javafx.fxml;
}