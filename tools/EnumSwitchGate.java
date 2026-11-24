import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConstantCaseLabelTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Enum-switch gate (#1217): a switch over an enum, statement or expression, whose {@code default} hides a constant fails the build, so
 * adding an enum member fails at every switch that forgot it. Error Prone's MissingCasesInEnumSwitch covers a switch with no default and
 * UnnecessaryDefaultInEnumSwitch one whose default is unreachable; this covers the one in between.
 *
 * <p>The sources are attributed with javac (the public {@code com.sun.source} API, no dependency), so the selector's type is what the
 * compiler resolved: an int or String switch never counts, and a nested, source or class-file (vanilla) enum all do. A source that does
 * not attribute fails the gate, because a switch it could not type would pass unseen. package-info files are skipped: the source sets
 * share packages, so attributed together their annotations clash, and they hold no switch.
 *
 * <p>A switch passes when it has no default, when its constants are all named (the default is then unreachable, and Error Prone owns
 * that), or when its default carries {@code // enum-default: <reason>}: on a line of the default arm and of the statement it wraps over
 * (palantir-java-format moves a trailing comment to the end of a wrapped statement), or in the block of comment-only lines directly
 * above the arm. The reason is required, and the gate does not judge it. The marker binds to its own default arm: one on the arm
 * above, or above an unrelated statement, does not count.
 *
 * <p>Every finding fails; nothing is grandfathered. --list prints every enum switch with a default and how it passes or fails, and
 * exits 0.
 *
 * <p>Usage: java EnumSwitchGate.java --sources dir... [--classpath entry...] [--root dir] [--list]. Sources are the java roots of every
 * source set, attributed together; the classpath is the jars the sources compile against, without the mod's own class output. Exit 1
 * prints the failures.
 */
public final class EnumSwitchGate {
    private static final Pattern MARKER = Pattern.compile("//\\s*enum-default:(.*)$");
    private static final int ERRORS_SHOWN = 20;

    private final Path root;
    private final Map<Path, Lexed> lexed = new HashMap<>();
    private final Map<String, String> findings = new TreeMap<>();
    private final List<String> listing = new ArrayList<>();

    private EnumSwitchGate(Path root) {
        this.root = root;
    }

    /** Per line: the {@code //} comment outside any literal (null when none), and whether the rest of the line is code. */
    private record Lexed(List<String> comments, List<Boolean> code) {}

    public static void main(String[] args) throws IOException {
        Map<String, List<String>> options = new HashMap<>();
        String current = null;
        for (String arg : args) {
            if (arg.startsWith("--")) {
                current = arg;
                options.put(current, new ArrayList<>());
            } else if (current != null) {
                options.get(current).add(arg);
            }
        }
        if (options.getOrDefault("--sources", List.of()).isEmpty()) {
            throw new IllegalArgumentException("EnumSwitchGate: --sources is required");
        }
        Path root = Path.of(options.getOrDefault("--root", List.of(".")).getFirst())
                .toAbsolutePath()
                .normalize();
        EnumSwitchGate gate = new EnumSwitchGate(root);
        List<Path> files = new ArrayList<>();
        for (String source : options.get("--sources")) {
            try (Stream<Path> walk = Files.walk(Path.of(source))) {
                walk.filter(path -> path.toString().endsWith(".java") && !path.endsWith("package-info.java"))
                        .sorted()
                        .forEach(files::add);
            }
        }
        gate.run(files, options.getOrDefault("--classpath", List.of()));
        if (options.containsKey("--list")) {
            gate.listing.forEach(System.out::println);
            return;
        }
        if (!gate.findings.isEmpty()) {
            System.err.println("Enum switches whose default hides a constant (" + gate.findings.size()
                    + "). Adding an enum member would fall into the default unseen."
                    + " Name the constants instead: one arm per constant, or 'case A, B ->' for a shared outcome, and no default."
                    + " A default that is the point (a vanilla enum with many members this arm does not care about) ends its arm's line,"
                    + " or sits on the comment lines directly above it, with"
                    + " '// enum-default: <why the other constants share one outcome>'.");
            gate.findings.values().forEach(message -> System.err.println("  " + message));
            System.exit(1);
        }
    }

    private void run(List<Path> files, List<String> classpath) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            List<String> options = new ArrayList<>(List.of("-proc:none", "-Xlint:none", "-XDuseUnsharedTable=true"));
            if (!classpath.isEmpty()) {
                options.addAll(List.of("-classpath", String.join(File.pathSeparator, classpath)));
            }
            JavacTask task = (JavacTask) compiler.getTask(
                    null, manager, diagnostics, options, null, manager.getJavaFileObjectsFromPaths(files));
            Trees trees = Trees.instance(task);
            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();
            List<String> errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(this::describe)
                    .toList();
            if (!errors.isEmpty()) {
                System.err.println("EnumSwitchGate: " + errors.size()
                        + " sources do not attribute, so a switch could pass unseen. Check the classpath.");
                errors.stream().limit(ERRORS_SHOWN).forEach(error -> System.err.println("  " + error));
                System.exit(1);
            }
            for (CompilationUnitTree unit : units) {
                new Scanner(trees, trees.getSourcePositions(), unit).scan(unit, null);
            }
        }
    }

    private String describe(Diagnostic<? extends JavaFileObject> diagnostic) {
        String where = diagnostic.getSource() == null
                ? ""
                : relative(Path.of(diagnostic.getSource().toUri())) + ":" + diagnostic.getLineNumber() + " ";
        return where + diagnostic.getMessage(null).lines().findFirst().orElse("");
    }

    private String relative(Path path) {
        Path normal = path.toAbsolutePath().normalize();
        return (normal.startsWith(root) ? root.relativize(normal) : normal).toString();
    }

    private final class Scanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final SourcePositions positions;
        private final CompilationUnitTree unit;

        Scanner(Trees trees, SourcePositions positions, CompilationUnitTree unit) {
            this.trees = trees;
            this.positions = positions;
            this.unit = unit;
        }

        @Override
        public Void visitSwitch(SwitchTree node, Void unused) {
            check(node.getExpression(), node.getCases());
            return super.visitSwitch(node, unused);
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree node, Void unused) {
            check(node.getExpression(), node.getCases());
            return super.visitSwitchExpression(node, unused);
        }

        private void check(ExpressionTree selector, List<? extends CaseTree> cases) {
            TypeMirror type = trees.getTypeMirror(new TreePath(getCurrentPath(), selector));
            // Known limit: a type-variable selector or a sealed interface of enums is skipped; the repo has neither.
            if (type == null || type.getKind() != TypeKind.DECLARED) {
                return;
            }
            Element element = ((DeclaredType) type).asElement();
            if (element.getKind() != ElementKind.ENUM) {
                return;
            }
            TypeElement enumType = (TypeElement) element;
            CaseTree withDefault = null;
            CaseLabelTree defaultLabel = null;
            Set<String> named = new HashSet<>();
            for (CaseTree caseTree : cases) {
                for (CaseLabelTree label : caseTree.getLabels()) {
                    if (label.getKind() == Tree.Kind.DEFAULT_CASE_LABEL) {
                        withDefault = caseTree;
                        defaultLabel = label;
                    } else if (label instanceof ConstantCaseLabelTree constant) {
                        String name = constantName(constant.getConstantExpression());
                        if (name != null) {
                            named.add(name);
                        }
                    }
                }
            }
            if (withDefault == null) {
                return;
            }
            Set<String> hidden = new LinkedHashSet<>();
            for (Element member : enumType.getEnclosedElements()) {
                if (member.getKind() == ElementKind.ENUM_CONSTANT
                        && !named.contains(member.getSimpleName().toString())) {
                    hidden.add(member.getSimpleName().toString());
                }
            }
            String enumName = enumType.getQualifiedName().toString();
            String file = relative(Path.of(unit.getSourceFile().toUri()));
            int line = (int) unit.getLineMap().getLineNumber(positions.getStartPosition(unit, defaultLabel));
            String where = file + ":" + line;
            if (hidden.isEmpty()) {
                listing.add(where + " " + enumName + " all constants named");
                return;
            }
            String marker = markerFor(unit, withDefault, line);
            if (marker != null && !marker.isBlank()) {
                listing.add(where + " " + enumName + " marked");
                return;
            }
            listing.add(where + " " + enumName + " hides " + String.join(", ", hidden));
            findings.put(
                    String.format("%s:%08d", file, line),
                    where + " switch over " + enumName + " has a default that hides " + String.join(", ", hidden)
                            + (marker != null ? "; its '// enum-default:' marker has no reason" : ""));
        }

        private static String constantName(ExpressionTree expression) {
            if (expression instanceof IdentifierTree identifier) {
                return identifier.getName().toString();
            }
            if (expression instanceof MemberSelectTree select) {
                return select.getIdentifier().toString();
            }
            return null;
        }

        /**
         * The text after {@code // enum-default:} on a line of the default arm (its label, and the statement or expression it
         * wraps over) or, when none has one, in the block of comment-only lines directly above the arm; null when there is no marker.
         */
        private String markerFor(CompilationUnitTree tree, CaseTree caseTree, int line) {
            Lexed source = lex(tree);
            Tree body = caseTree.getStatements() != null
                            && !caseTree.getStatements().isEmpty()
                    ? caseTree.getStatements().getFirst()
                    : caseTree.getBody();
            int last = line;
            if (body != null && !(body instanceof BlockTree)) {
                last = Math.max(line, (int) tree.getLineMap().getLineNumber(positions.getEndPosition(tree, body)));
            }
            String found = null;
            for (int number = line; number <= last; number++) {
                String text = marker(source.comments().get(number - 1));
                if (text != null && (found == null || found.isBlank())) {
                    found = text;
                }
            }
            if (found != null) {
                return found;
            }
            for (int above = line - 2;
                    above >= 0 && !source.code().get(above) && source.comments().get(above) != null;
                    above--) {
                String text = marker(source.comments().get(above));
                if (text != null) {
                    return text;
                }
            }
            return null;
        }
    }

    private static String marker(String comment) {
        if (comment == null) {
            return null;
        }
        Matcher matcher = MARKER.matcher(comment);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    private Lexed lex(CompilationUnitTree unit) {
        return lexed.computeIfAbsent(Path.of(unit.getSourceFile().toUri()), path -> {
            String text;
            try {
                text = Files.readString(path);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + path, e);
            }
            return lexText(text);
        });
    }

    /** Splits {@code text} into lines, finding each line's {@code //} comment and whether it holds code, skipping literals and block comments. */
    private static Lexed lexText(String text) {
        List<String> comments = new ArrayList<>();
        List<Boolean> code = new ArrayList<>();
        String comment = null;
        boolean hasCode = false;
        int length = text.length();
        int i = 0;
        while (i <= length) {
            boolean end = i == length;
            char c = end ? '\n' : text.charAt(i);
            if (c == '\n') {
                comments.add(comment);
                code.add(hasCode);
                comment = null;
                hasCode = false;
                i++;
            } else if (text.startsWith("//", i)) {
                int stop = text.indexOf('\n', i);
                stop = stop < 0 ? length : stop;
                comment = text.substring(i, stop);
                i = stop;
            } else if (text.startsWith("/*", i)) {
                int stop = text.indexOf("*/", i + 2);
                stop = stop < 0 ? length : stop + 2;
                for (int k = i; k < stop; k++) {
                    if (text.charAt(k) == '\n') {
                        comments.add(comment);
                        code.add(hasCode);
                        comment = null;
                        hasCode = false;
                    }
                }
                i = stop;
            } else if (text.startsWith("\"\"\"", i)) {
                hasCode = true;
                i += 3;
                while (i < length && !text.startsWith("\"\"\"", i)) {
                    if (text.charAt(i) == '\\') {
                        i++;
                    } else if (text.charAt(i) == '\n') {
                        comments.add(null);
                        code.add(true);
                    }
                    i++;
                }
                i += 3;
            } else if (c == '"' || c == '\'') {
                hasCode = true;
                i++;
                while (i < length && text.charAt(i) != c && text.charAt(i) != '\n') {
                    i += text.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
            } else {
                hasCode |= !Character.isWhitespace(c);
                i++;
            }
        }
        return new Lexed(comments, code);
    }
}
