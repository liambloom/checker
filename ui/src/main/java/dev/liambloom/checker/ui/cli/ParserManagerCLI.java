package dev.liambloom.checker.ui.cli;

import dev.liambloom.checker.ui.Data;

import java.io.IOException;
import java.util.Optional;

public class ParserManagerCLI extends ResourceManagerCLI<Data.ParserManager, Data.ParserManager.ParserRecord> {
    public ParserManagerCLI(Data.ParserManager inner) {
        super(inner);
    }

    @Override
    public void evaluate(String[] args, int start) throws IOException {
        if (args.length == 0)
            throw new UserErrorException("Missing argument. See `chk " + inner.getPluralName() + " --help' for help.");
        if ("add".equals(args[start++])) {
            Main.assertArgsPresent(args, start, "name", "source");
            try {
                inner.add(args[start], Main.resolveResource(args[start + 1]));
            }
            catch (IllegalArgumentException e) {
                throw new UserErrorException(e.getMessage(), e);
            }
        }
        else {
            super.evaluate(args, start);
        }
    }
}