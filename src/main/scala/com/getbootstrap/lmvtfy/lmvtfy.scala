/*
 * Copyright (c) 2014 Christopher Rebert
 * Copyright (c) 2013-2014 Mozilla Foundation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.getbootstrap.lmvtfy

// import org.xml.sax.InputSource
// import nu.validator.validation.SimpleDocumentValidator

import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URL
import java.util.ArrayList
import java.util.List

import nu.validator.htmlparser.sax.XmlSerializer
import nu.validator.messages.GnuMessageEmitter
import nu.validator.messages.JsonMessageEmitter
import nu.validator.messages.MessageEmitterAdapter
import nu.validator.messages.TextMessageEmitter
import nu.validator.messages.XmlMessageEmitter
import nu.validator.servlet.imagereview.ImageCollector
import nu.validator.source.SourceCode
import nu.validator.validation.SimpleDocumentValidator
import nu.validator.validation.SimpleDocumentValidator.SchemaReadException
import nu.validator.xml.SystemErrErrorHandler

import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException

sealed case object OutputFormat
case object HTML extends OutputFormat
case object XHTML extends OutputFormat
case object TEXT extends OutputFormat
case object XML extends OutputFormat
case object JSON extends OutputFormat
case object RELAXED extends OutputFormat
case object SOAP extends OutputFormat
case object UNICORN extends OutputFormat
case object GNU extends OutputFormat


class SimpleCommandLineValidator {
    private var SimpleDocumentValidator validator

    private var OutputStream out

    private var MessageEmitterAdapter errorHandler

    private val verbose: Boolean = false

    private var errorsOnly: Boolean = false

    private var loadEntities: Boolean = false

    private var noStream: Boolean = false

    private var forceHTML: Boolean = false

    private var asciiQuotes: Boolean = false // FIXME??

    private val lineOffset: Int = 0

    private val outputFormat: OutputFormat = JSON

    // throws SAXException, Exception
    def main(args: Array[String]) {
        out = System.err
        System.setProperty("org.whattf.datatype.warn", "true")
        String schemaUrl = null
        boolean hasFileArgs = false
        boolean readFromStdIn = false
        int fileArgsStart = 0
        if (args.length == 0) {
            usage()
            System.exit(-1)
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-")) {
                readFromStdIn = true
                break
            } else if (!args[i].startsWith("--")) {
                hasFileArgs = true
                fileArgsStart = i
                break
            } else {
                if ("--verbose".equals(args[i])) {
                    verbose = true
                } else if ("--errors-only".equals(args[i])) {
                    errorsOnly = true
                    System.setProperty("org.whattf.datatype.warn", "false")
                } else if ("--format".equals(args[i])) {
                    outFormat = args[++i]
                } else if ("--version".equals(args[i])) {
                    System.out.println(version)
                    System.exit(0)
                } else if ("--help".equals(args[i])) {
                    help()
                    System.exit(0)
                } else if ("--html".equals(args[i])) {
                    forceHTML = true
                } else if ("--entities".equals(args[i])) {
                    loadEntities = true
                } else if ("--no-stream".equals(args[i])) {
                    noStream = true
                } else if ("--schema".equals(args[i])) {
                    schemaUrl = args[++i]
                    if (!schemaUrl.startsWith("http:")) {
                        System.err.println("error: The \"--schema\" option"
                                + " requires a URL for a schema.")
                        System.exit(-1)
                    }
                }
            }
        }
        if (schemaUrl == null) {
            schemaUrl = "http://s.validator.nu/html5-rdfalite.rnc"
        }
        if (readFromStdIn) {
            InputSource is = new InputSource(System.in)
            validator = new SimpleDocumentValidator()
            setup(schemaUrl)
            validator.checkHtmlInputSource(is)
            end()
        } else if (hasFileArgs) {
            List<File> files = new ArrayList<File>()
            for (int i = fileArgsStart; i < args.length; i++) {
                files.add(new File(args[i]))
            }
            validator = new SimpleDocumentValidator()
            setup(schemaUrl)
            checkFiles(files)
            end()
        } else {
            System.err.printf("\nError: No documents specified.\n")
            usage()
            System.exit(-1)
        }
    }

    private static void setup(String schemaUrl) throws SAXException, Exception {
        setErrorHandler()
        try {
            validator.setUpMainSchema(schemaUrl, new SystemErrErrorHandler())
        } catch (SchemaReadException e) {
            System.out.println(e.getMessage() + " Terminating.")
            System.exit(-1)
        } catch (StackOverflowError e) {
            System.out.println("StackOverflowError"
                    + " while evaluating HTML schema.")
            System.out.println("The checker requires a java thread stack size"
                    + " of at least 512k.")
            System.out.println("Consider invoking java with the -Xss"
                    + " option. For example:")
            System.out.println("\n  java -Xss512k -jar ~/vnu.jar FILE.html")
            System.exit(-1)
        }
        validator.setUpValidatorAndParsers(errorHandler, noStream, loadEntities)
    }

    private static void end() throws SAXException {
        errorHandler.end("Document checking completed. No errors found.",
                "Document checking completed.")
    }

    private static void checkFiles(List<File> files) throws SAXException,
            IOException {
        for (File file : files) {
            if (file.isDirectory()) {
                recurseDirectory(file)
            } else {
                checkHtmlFile(file)
            }
        }
    }

    private static void recurseDirectory(File directory) throws SAXException,
            IOException {
        File[] files = directory.listFiles()
        for (int i = 0; i < files.length; i++) {
            File file = files[i]
            if (file.isDirectory()) {
                recurseDirectory(file)
            } else {
                checkHtmlFile(file)
            }
        }
    }

    private static void checkHtmlFile(File file) throws IOException {
        try {
            String path = file.getPath()
            if (path.matches("^http:/[^/].+$")) {
                path = "http://" + path.substring(path.indexOf("/") + 1)
                emitFilename(path)
                try {
                    validator.checkHttpURL(new URL(path))
                } catch (IOException e) {
                    errorHandler.error(new SAXParseException(e.toString(),
                            null, path, -1, -1))
                }
            } else if (path.matches("^https:/[^/].+$")) {
                path = "https://" + path.substring(path.indexOf("/") + 1)
                emitFilename(path)
                try {
                    validator.checkHttpURL(new URL(path))
                } catch (IOException e) {
                    errorHandler.error(new SAXParseException(e.toString(),
                            null, path, -1, -1))
                }
            } else if (!file.exists()) {
                if (verbose) {
                    errorHandler.warning(new SAXParseException(
                            "File not found.", null,
                            file.toURI().toURL().toString(), -1, -1))
                }
                return
            } else if (isHtml(file)) {
                emitFilename(path)
                validator.checkHtmlFile(file, true)
            } else if (isXhtml(file)) {
                emitFilename(path)
                if (forceHTML) {
                    validator.checkHtmlFile(file, true)
                } else {
                    validator.checkXmlFile(file)
                }
            } else {
                if (verbose) {
                    errorHandler.warning(new SAXParseException(
                            "File was not checked. Files must have .html,"
                                    + " .xhtml, .htm, or .xht extension.",
                            null, file.toURI().toURL().toString(), -1, -1))
                }
            }
        } catch (SAXException e) {
            if (!errorsOnly) {
                System.err.printf("\"%s\":-1:-1: warning: %s\n",
                        file.toURI().toURL().toString(), e.getMessage())
            }
        }
    }

    private static boolean isXhtml(File file) {
        String name = file.getName()
        return (name.endsWith(".xhtml") || name.endsWith(".xht"))
    }

    private static boolean isHtml(File file) {
        String name = file.getName()
        return (name.endsWith(".html") || name.endsWith(".htm"))
    }

    private static void emitFilename(String name) {
        if (verbose) {
            System.out.println(name)
        }
    }

    private static void setErrorHandler() {
        SourceCode sourceCode = new SourceCode()
        ImageCollector imageCollector = new ImageCollector(sourceCode)
        boolean showSource = false
        if (outputFormat == OutputFormat.TEXT) {
            errorHandler = new MessageEmitterAdapter(sourceCode, showSource,
                    imageCollector, lineOffset, true, new TextMessageEmitter(
                            out, asciiQuotes))
        } else if (outputFormat == OutputFormat.GNU) {
            errorHandler = new MessageEmitterAdapter(sourceCode, showSource,
                    imageCollector, lineOffset, true, new GnuMessageEmitter(
                            out, asciiQuotes))
        } else if (outputFormat == OutputFormat.XML) {
            errorHandler = new MessageEmitterAdapter(sourceCode, showSource,
                    imageCollector, lineOffset, true, new XmlMessageEmitter(
                            new XmlSerializer(out)))
        } else if (outputFormat == OutputFormat.JSON) {
            String callback = null
            errorHandler = new MessageEmitterAdapter(sourceCode, showSource,
                    imageCollector, lineOffset, true, new JsonMessageEmitter(
                            new nu.validator.json.Serializer(out), callback))
        } else {
            throw new RuntimeException("Bug. Should be unreachable.")
        }
        errorHandler.setErrorsOnly(errorsOnly)
    }

    private static void usage() {
        System.out.println("Usage:")
        System.out.println("")
        System.out.println("    java -jar vnu.jar [--errors-only] [--no-stream]")
        System.out.println("         [--format gnu|xml|json|text] [--help] [--html]")
        System.out.println("         [--verbose] [--version] FILES")
        System.out.println("")
        System.out.println("    java -cp vnu.jar nu.validator.servlet.Main 8888")
        System.out.println("")
        System.out.println("    java -cp vnu.jar nu.validator.client.HttpClient FILES")
        System.out.println("")
        System.out.println("For detailed usage information, use \"java -jar vnu.jar --help\" or see:")
        System.out.println("")
        System.out.println("  http://validator.github.io/")
        System.out.println("")
        System.out.println("To read from stdin, use \"-\" as the filename, like this: \"java -jar vnu.jar - \".")
    }

    private static void help() {
        InputStream help = SimpleCommandLineValidator.class.getClassLoader().getResourceAsStream(
                "nu/validator/localentities/files/cli-help")
        try {
            System.out.println("")
            for (int b = help.read(); b != -1; b = help.read()) {
                System.out.write(b)
            }
            help.close()
        } catch (IOException e) {
            throw new RuntimeException(e)
        }
    }
}