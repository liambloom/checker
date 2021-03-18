package io.github.liambloom.tests.book.bjp3;

import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

class TestLoader {
    // TODO: This should be a ReadWriteLock
    private static Schema schema;

    public static Schema getSchema() throws SAXException {
        if (schema == null)
            schema = SchemaFactory.newInstance("http://www.w3.org/XML/XMLSchema/v1.1").newSchema(
                    new StreamSource(TestLoader.class.getResourceAsStream("/book-tests.xsd")));
        return schema;
    }



    // TODO: split up into several methods and lazy fields
    public static /*Map<String, Source>*/ Source[] load() throws SAXException, URISyntaxException, IOException {
        // TODO: Fix conflicting SAXExceptions
        // If a SAXException is thrown here, it is my fault
        Schema schema = SchemaFactory.newInstance("http://www.w3.org/XML/XMLSchema/v1.1").newSchema(new StreamSource(TestLoader.class.getResourceAsStream("/book-tests.xsd")));

        Source[] tests;
        final URL externalTests = TestLoader.class.getResource("/../tests");
        if (externalTests == null)
            tests = new Source[1];
        else {
            File[] sources = new File(externalTests.toURI()).listFiles();
            tests = new Source[sources.length + 1];
            for (int i = 0; i < sources.length; i++) {
                Path p = sources[i].toPath();
                if (Files.isSymbolicLink(p))
                    p = Files.readSymbolicLink(p);
                // TODO: check if sources[i] is a dir
                final String mime = Files.probeContentType(p);
                if (mime.equals("application/xml") || mime.equals("text/xml"))
                    // TODO: This should be a warning
                    throw new UserErrorException("Expected an xml file at " + p + ", but found " + mime + " instead");
                //if (Files.isSymbolicLink(sources[i].toPath()))

            }
        }
        tests[0] = new StreamSource(TestLoader.class.getResourceAsStream("/tests.xml"));

        // System.out.println(Files.probeContentType(Paths.get(TestLoader.class.getResource("/tests.xml").toURI())));
        //Files.readSymbolicLink(Paths.get(TestLoader.class.getResource("/tests.xml").toURI()));

        Validator validator = schema.newValidator();
        for (Source test : tests) {
            // If a SAXException is thrown here, it is the user's fault\
            try {
                validator.validate(test);
            }
            catch (SAXParseException e) {
                throw new UserErrorException(e);
            }
            validator.reset();
        }

        return tests;
    }
}