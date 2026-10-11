import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Test-only gate: fails when a non-private method, constructor or field of the mod (src/main, src/client) is referenced only from the
 * GameTest source set (src/gametest). A test is not a production caller; see tests.md in the deepcharter-conventions skill.
 *
 * <p>It reads compiled classes, so a reference is what javac resolved, not a name that looks alike: calls, field accesses, and method
 * handles (lambdas and method references). A member passes whenever the gate cannot prove it test-only: any production reference,
 * (its own class's reads included, its own writes not); an override or implementation (a supertype declares the same name and arity, or cannot be loaded); an
 * annotated member (mixin targets, injectors, registrations); a mixin class; enum constants, values and valueOf; a record's
 * accessors; a compile-time constant (javac inlines it, so no reference survives); and abstract methods. A member nothing references,
 * tests included, is not this gate's business.
 *
 * <p>A deliberate seam ends its declaration line, or sits on the line above, with {@code // test-seam: <reason>}; the reason is required.
 *
 * <p>Usage: java TestOnlyGate.java --production dir... --tests dir... --sources dir... --classpath entry... [--root dir]
 * Production and test dirs are class output dirs; sources are the java roots of the production source sets; the classpath is the
 * jars and dirs that supertypes are loaded from. Exit 1 prints one line per finding.
 */
public final class TestOnlyGate {
    private static final String MARKER = "test-seam:";
    private static final Set<String> IGNORED_ANNOTATIONS = Set.of(
        "Nullable", "NotNull", "NonNull", "Nonnull", "Deprecated", "SafeVarargs", "FunctionalInterface", "VisibleForTesting");

    /** Who references a member: production code (the mod, own class included) or the GameTest source set. */
    private static final class Refs {
        boolean production;
        String firstTest;
    }

    private record Member(ClassModel owner, String name, String descriptor, boolean field) {
        String key() {
            return owner.thisClass().asInternalName() + "#" + name + (field ? ":" : "") + descriptor;
        }
    }

    private final Map<String, ClassModel> production = new LinkedHashMap<>();
    private final Map<String, ClassModel> tests = new LinkedHashMap<>();
    private final List<ZipFile> jars = new ArrayList<>();
    private final List<Path> classDirs = new ArrayList<>();
    private final Map<String, ClassModel> loaded = new HashMap<>();
    private final Map<String, Refs> refs = new HashMap<>();
    private final List<Path> sourceRoots;
    private final Path root;

    private TestOnlyGate(List<Path> sourceRoots, Path root) {
        this.sourceRoots = sourceRoots;
        this.root = root;
    }

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
        for (String required : List.of("--production", "--tests", "--sources")) {
            if (options.getOrDefault(required, List.of()).isEmpty()) {
                throw new IllegalArgumentException("TestOnlyGate: " + required + " is required");
            }
        }
        List<Path> sources = options.get("--sources").stream().map(Path::of).toList();
        Path root = Path.of(options.getOrDefault("--root", List.of(".")).getFirst()).toAbsolutePath().normalize();
        TestOnlyGate gate = new TestOnlyGate(sources, root);
        for (String entry : options.getOrDefault("--classpath", List.of())) {
            gate.addClasspath(Path.of(entry));
        }
        List<String> findings = gate.run(
            options.get("--production").stream().map(Path::of).toList(), options.get("--tests").stream().map(Path::of).toList());
        if (!findings.isEmpty()) {
            System.err.println("Members referenced only from src/gametest (" + findings.size() + "). A test is not a production caller:"
                + " delete the member and have the test use the seam production uses, or make it private. A deliberate seam ends its"
                + " declaration line, or sits on the line above, with '// test-seam: <reason>'.");
            findings.forEach(finding -> System.err.println("  " + finding));
            System.exit(1);
        }
    }

    private void addClasspath(Path entry) throws IOException {
        if (Files.isDirectory(entry)) {
            classDirs.add(entry);
        } else if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
            jars.add(new ZipFile(entry.toFile()));
        }
    }

    List<String> run(List<Path> productionDirs, List<Path> testDirs) throws IOException {
        classDirs.addAll(productionDirs);
        classDirs.addAll(testDirs);
        read(productionDirs, production);
        read(testDirs, tests);
        production.values().forEach(model -> scanReferences(model, true));
        tests.values().forEach(model -> scanReferences(model, false));
        Map<String, String> findings = new TreeMap<>();
        for (ClassModel model : production.values()) {
            for (Member member : candidates(model)) {
                Refs found = refs.get(member.key());
                if (found != null && !found.production && found.firstTest != null) {
                    String message = report(member, found);
                    if (message != null) {
                        findings.put(message, message);
                    }
                }
            }
        }
        return new ArrayList<>(findings.values());
    }

    private static void read(List<Path> dirs, Map<String, ClassModel> into) throws IOException {
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var files = Files.walk(dir)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    ClassModel model = ClassFile.of().parse(file);
                    into.put(model.thisClass().asInternalName(), model);
                }
            }
        }
    }

    private ClassModel find(String internalName) {
        ClassModel known = production.containsKey(internalName) ? production.get(internalName) : tests.get(internalName);
        if (known != null) {
            return known;
        }
        if (loaded.containsKey(internalName)) {
            return loaded.get(internalName);
        }
        ClassModel model = null;
        String entry = internalName + ".class";
        try {
            for (Path dir : classDirs) {
                if (Files.isRegularFile(dir.resolve(entry))) {
                    model = ClassFile.of().parse(dir.resolve(entry));
                    break;
                }
            }
            if (model == null) {
                try (var in = ClassLoader.getSystemResourceAsStream(entry)) {
                    model = in == null ? null : ClassFile.of().parse(in.readAllBytes());
                }
            }
            for (int i = 0; model == null && i < jars.size(); i++) {
                ZipEntry zipEntry = jars.get(i).getEntry(entry);
                if (zipEntry != null) {
                    try (var in = jars.get(i).getInputStream(zipEntry)) {
                        model = ClassFile.of().parse(in.readAllBytes());
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + internalName, e);
        }
        loaded.put(internalName, model);
        return model;
    }

    private List<ClassModel> supertypes(ClassModel model, boolean[] complete) {
        List<ClassModel> out = new ArrayList<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        enqueueSupers(model, queue);
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!seen.add(name)) {
                continue;
            }
            ClassModel parent = find(name);
            if (parent == null) {
                complete[0] = false;
                continue;
            }
            out.add(parent);
            enqueueSupers(parent, queue);
        }
        return out;
    }

    private static void enqueueSupers(ClassModel model, ArrayDeque<String> queue) {
        model.superclass().ifPresent(parent -> queue.add(parent.asInternalName()));
        model.interfaces().forEach(parent -> queue.add(parent.asInternalName()));
    }

    private void scanReferences(ClassModel from, boolean fromProduction) {
        String origin = from.thisClass().asInternalName();
        for (MethodModel method : from.methods()) {
            method.code().ifPresent(code -> {
                for (CodeElement element : code) {
                    switch (element) {
                        case InvokeInstruction call -> credit(call.owner().asInternalName(), call.name().stringValue(),
                            call.type().stringValue(), false, origin, fromProduction);
                        case FieldInstruction access when isOwnWrite(access, origin) -> { }
                        case FieldInstruction access -> credit(access.owner().asInternalName(), access.name().stringValue(),
                            access.type().stringValue(), true, origin, fromProduction);
                        case InvokeDynamicInstruction dynamic -> dynamic.invokedynamic().bootstrap().arguments().forEach(arg -> creditHandle(arg, origin, fromProduction));
                        case ConstantInstruction.LoadConstantInstruction load -> creditHandle(load.constantEntry(), origin, fromProduction);
                        default -> { }
                    }
                }
            });
        }
    }

    /** A class assigning its own field (an initializer, a constructor) uses nothing: only a read, or a write from another class, does. */
    private static boolean isOwnWrite(FieldInstruction access, String origin) {
        return (access.opcode() == java.lang.classfile.Opcode.PUTFIELD || access.opcode() == java.lang.classfile.Opcode.PUTSTATIC)
            && access.owner().asInternalName().equals(origin);
    }

    private void creditHandle(LoadableConstantEntry entry, String origin, boolean fromProduction) {
        if (entry instanceof MethodHandleEntry handle) {
            MemberRefEntry target = handle.reference();
            boolean field = target.nameAndType().type().stringValue().charAt(0) != '(';
            credit(target.owner().asInternalName(), target.name().stringValue(), target.type().stringValue(), field, origin, fromProduction);
        }
    }

    /** Credits every production class in the owner's hierarchy that declares the member: javac names the receiver's type, not the declarer. */
    private void credit(String owner, String name, String descriptor, boolean field, String origin, boolean fromProduction) {
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (!seen.add(next)) {
                continue;
            }
            ClassModel model = find(next);
            if (model == null) {
                continue;
            }
            if (production.containsKey(next)) {
                Refs entry = refs.computeIfAbsent(next + "#" + name + (field ? ":" : "") + descriptor, key -> new Refs());
                if (fromProduction) {
                    entry.production = true;
                } else if (entry.firstTest == null) {
                    entry.firstTest = origin;
                }
            }
            enqueueSupers(model, queue);
        }
    }

    private List<Member> candidates(ClassModel model) {
        List<Member> out = new ArrayList<>();
        if (annotated(model.findAttribute(Attributes.runtimeVisibleAnnotations()).map(a -> a.annotations()).orElse(List.of()), "Mixin")
            || annotated(model.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(a -> a.annotations()).orElse(List.of()), "Mixin")
            || model.flags().has(java.lang.reflect.AccessFlag.SYNTHETIC)) {
            return out;
        }
        boolean isEnum = model.flags().has(java.lang.reflect.AccessFlag.ENUM);
        boolean isRecord = model.findAttribute(Attributes.record()).isPresent();
        Set<String> components = new HashSet<>();
        model.findAttribute(Attributes.record()).ifPresent(record -> record.components().forEach(c -> components.add(c.name().stringValue())));
        boolean[] complete = {true};
        List<ClassModel> parents = supertypes(model, complete);
        Set<String> bridged = model.methods().stream()
            .filter(method -> method.flags().has(java.lang.reflect.AccessFlag.BRIDGE))
            .map(method -> method.methodName().stringValue()).collect(Collectors.toSet());
        for (var field : model.fields()) {
            var flags = field.flags();
            if (flags.has(java.lang.reflect.AccessFlag.PRIVATE) || flags.has(java.lang.reflect.AccessFlag.SYNTHETIC)
                || flags.has(java.lang.reflect.AccessFlag.ENUM) || field.findAttribute(Attributes.constantValue()).isPresent()
                || ignoredAnnotations(field.findAttribute(Attributes.runtimeVisibleAnnotations()).map(a -> a.annotations()).orElse(List.of()),
                    field.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(a -> a.annotations()).orElse(List.of()))) {
                continue;
            }
            out.add(new Member(model, field.fieldName().stringValue(), field.fieldType().stringValue(), true));
        }
        for (MethodModel method : model.methods()) {
            String name = method.methodName().stringValue();
            var flags = method.flags();
            if (flags.has(java.lang.reflect.AccessFlag.PRIVATE) || flags.has(java.lang.reflect.AccessFlag.SYNTHETIC)
                || flags.has(java.lang.reflect.AccessFlag.BRIDGE) || flags.has(java.lang.reflect.AccessFlag.ABSTRACT)
                || name.equals("<clinit>") || bridged.contains(name)
                || ignoredAnnotations(method.findAttribute(Attributes.runtimeVisibleAnnotations()).map(a -> a.annotations()).orElse(List.of()),
                    method.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(a -> a.annotations()).orElse(List.of()))) {
                continue;
            }
            int arity = MethodTypeDesc.ofDescriptor(method.methodType().stringValue()).parameterCount();
            if (isEnum && (name.equals("values") || name.equals("valueOf"))) {
                continue;
            }
            if (isRecord && arity == 0 && components.contains(name)) {
                continue;
            }
            boolean inherited = !name.equals("<init>") && !flags.has(java.lang.reflect.AccessFlag.STATIC)
                && (!complete[0] || overrides(parents, name, arity));
            if (!inherited) {
                out.add(new Member(model, name, method.methodType().stringValue(), false));
            }
        }
        return out;
    }

    private static boolean overrides(List<ClassModel> parents, String name, int arity) {
        return parents.stream().flatMap(parent -> parent.methods().stream()).anyMatch(method ->
            method.methodName().stringValue().equals(name)
                && !method.flags().has(java.lang.reflect.AccessFlag.PRIVATE)
                && MethodTypeDesc.ofDescriptor(method.methodType().stringValue()).parameterCount() == arity);
    }

    private static boolean annotated(Collection<Annotation> annotations, String simpleName) {
        return annotations.stream().anyMatch(a -> simpleName(a).equals(simpleName));
    }

    private static boolean ignoredAnnotations(List<Annotation> visible, List<Annotation> invisible) {
        return java.util.stream.Stream.concat(visible.stream(), invisible.stream())
            .anyMatch(annotation -> !IGNORED_ANNOTATIONS.contains(simpleName(annotation)));
    }

    private static String simpleName(Annotation annotation) {
        String descriptor = annotation.className().stringValue();
        return descriptor.substring(Math.max(descriptor.lastIndexOf('/'), descriptor.lastIndexOf('$')) + 1, descriptor.length() - 1);
    }

    /** The finding line, or null when a valid marker exempts the declaration. */
    private String report(Member member, Refs found) {
        String binary = member.owner().thisClass().asInternalName();
        String simple = binary.substring(binary.lastIndexOf('/') + 1).replace('$', '.');
        String innermost = simple.substring(simple.lastIndexOf('.') + 1);
        String label;
        String declared;
        if (member.field()) {
            label = simple + "." + member.name();
            declared = member.name();
        } else if (member.name().equals("<init>")) {
            label = "new " + simple + "(" + parameters(member.descriptor()) + ")";
            declared = innermost;
        } else {
            label = simple + "." + member.name() + "(" + parameters(member.descriptor()) + ")";
            declared = member.name();
        }
        String sourceName = member.owner().findAttribute(Attributes.sourceFile()).map(a -> a.sourceFile().stringValue()).orElse(null);
        Path source = sourceName == null ? null : locate(binary, sourceName);
        String where = binary.substring(binary.lastIndexOf('/') + 1);
        int line = 0;
        boolean bare = false;
        if (source != null) {
            where = root.relativize(source.toAbsolutePath().normalize()).toString();
            List<String> lines = read(source);
            Pattern declaration = declaration(declared, member.field(), member.name().equals("<init>"));
            for (int i = 0; i < lines.size(); i++) {
                if (!declaration.matcher(code(lines.get(i))).find()) {
                    continue;
                }
                line = line == 0 ? i + 1 : line;
                for (int at = Math.max(0, i - 1); at <= i; at++) {
                    String marker = marker(lines.get(at));
                    if (marker == null) {
                        continue;
                    }
                    if (!marker.isBlank()) {
                        return null;
                    }
                    bare = true;
                }
            }
        }
        if (line == 0 && member.name().equals("<init>")) {
            return null; // implicit constructor: nothing in the source to delete or mark
        }
        String caller = found.firstTest.substring(found.firstTest.lastIndexOf('/') + 1);
        return where + ":" + line + " " + label + " is referenced only from src/gametest (first: " + caller + ")"
            + (bare ? "; its '// test-seam:' marker has no reason" : "");
    }

    private static String parameters(String descriptor) {
        return MethodTypeDesc.ofDescriptor(descriptor).parameterList().stream()
            .map(type -> type.displayName()).collect(Collectors.joining(", "));
    }

    private Path locate(String binary, String sourceName) {
        String directory = binary.contains("/") ? binary.substring(0, binary.lastIndexOf('/') + 1) : "";
        for (Path sourceRoot : sourceRoots) {
            Path candidate = sourceRoot.resolve(directory + sourceName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<String> read(Path source) {
        try {
            return Files.readAllLines(source);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + source, e);
        }
    }

    /** A line that declares {@code name}: a type or modifiers before it, not a call, a new, an assignment or a statement keyword. */
    private static Pattern declaration(String name, boolean field, boolean constructor) {
        String quoted = Pattern.quote(name);
        String type = "(?:(?!return\\b|throw\\b|new\\b|else\\b|case\\b|yield\\b)[\\w<>\\[\\],.?@]+\\s+)";
        String before = constructor ? "^\\s*(?:(?:public|protected|private)\\s+)?" : "^\\s*(?:@[\\w.]+\\s+)*" + type + "+";
        String after = field ? "\\s*[=;,]" : "\\s*\\(";
        return Pattern.compile(before + quoted + after);
    }

    /** The line without its trailing comment and with string and char literals emptied. */
    private static String code(String line) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (line.startsWith("//", i)) {
                break;
            }
            if (c == '"' || c == '\'') {
                i = endOfLiteral(line, i);
                out.append(c).append(c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int endOfLiteral(String line, int start) {
        char quote = line.charAt(start);
        int i = start + 1;
        while (i < line.length() && line.charAt(i) != quote) {
            i += line.charAt(i) == '\\' ? 2 : 1;
        }
        return i;
    }

    /** The text after {@code // test-seam:} in a real trailing comment (not inside a literal), or null when there is no marker. */
    private static String marker(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' || c == '\'') {
                i = endOfLiteral(line, i);
            } else if (line.startsWith("//", i)) {
                Matcher found = Pattern.compile("^//\\s*" + Pattern.quote(MARKER) + "(.*)$").matcher(line.substring(i));
                return found.find() ? found.group(1).trim() : null;
            }
        }
        return null;
    }
}
