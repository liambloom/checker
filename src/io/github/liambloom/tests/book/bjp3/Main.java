package io.github.liambloom.tests.book.bjp3;

class Main {
    public static final Debugger debugger = new Debugger();

    public static void main(String[] rawArgs) {
        try {

            System.out.println("done");
            // -[x] Parse args
            final Arguments args = new Arguments(rawArgs);

            // -[ ] Load Tests
            TestLoader.load();

            // -[x] Load Classes
            // TODO: maybe make an argument to run tests in another directory
            final Class<?>[] classes = DirectoryClassLoader.loadClassesHere();

            // -[ ] Search classes to find correct chapter(s) and exercise(s)
            // -[ ] Retrieve/decode previous results
            // -[ ] Maybe find some way to store diff and compare (so you don't test
            //      unchanged methods)
            //      Note: Tests would also need to be re-run is dependencies (including java
            //      version) changed
            // -[ ] Run tests in tests.xml
            // -[ ] Print/save/encode/submit results
            //      Results could be: correct, incorrect, previously working, missing
        }
        catch (UserErrorException e) {
            debugger.error(e.getMessage());
            if (false /* TODO: args.debug */ && e.getCause() != null)
                e.getCause().printStackTrace();
        }
        catch (Throwable e) {
            debugger.internalError(e);
        }
    }
}