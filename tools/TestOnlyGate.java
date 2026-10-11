import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Test-only gate: fails when a non-private method, constructor or field of the mod (src/main, src/client) is referenced only from the
 * GameTest source set (src/gametest). A test is not a production caller; see tests.md in the deepcharter-conventions skill.
 *
 * <p>It reads compiled classes, so a reference is what javac resolved, not a name that looks alike: calls, field accesses, and method
 * handles (lambdas and method references), in the mod and in the tests. A member passes whenever the gate cannot prove it test-only:
 * <ul>
 * <li>Any production reference passes it. A reference from the member's own class counts, a call or a read, but not an assignment of
 * the member's own field, which an initializer or a constructor always makes.
 * <li>An override or implementation passes: a supertype declares a method with the same name and erased parameters, or a bridge method
 * of the class calls it (generics). A supertype that cannot be loaded fails the gate, because it could hide an override.
 * <li>An annotated member passes (mixin targets, injectors, registrations), apart from nullability and deprecation annotations, and so
 * does every member of a {@code @Mixin} class.
 * <li>Enum constants, {@code values} and {@code valueOf}; a record's accessors; compile-time constants (javac inlines them, so no
 * reference survives); abstract methods; implicit constructors (nothing in the source to delete or mark).
 * <li>A {@code static final} field whose type is a registered game object (a subtype of {@link #REGISTERED_ROOTS}): tests look a
 * registered object up through its field, and the registry is the production use.
 * <li>A member nothing references, tests included, is not this gate's business.
 * </ul>
 *
 * <p>A deliberate, permanent seam ends its declaration line, or sits on a comment line just above, with {@code // test-seam: <reason>};
 * the reason is required. The marker binds to the exact declaration: the same name in another class of the file, or another overload,
 * is not exempted by it.
 *
 * <p>A baseline (--baseline) lists the findings that exist already, one {@code Class#member(parameters)} per line, {@code #} for comments.
 * A finding outside it fails the build, and so does an entry that is no longer a finding: the baseline only shrinks.
 * --list prints the key of every finding and exits 0.
 *
 * <p>Usage: java TestOnlyGate.java --production dir... --tests dir... --sources dir... [--classpath entry...] [--root dir]
 * [--baseline file] [--list]. Production and test dirs are class output dirs; sources are the java roots of the production source sets;
 * the classpath is the jars and dirs that supertypes are loaded from. Exit 1 prints the failures.
 */
public final class TestOnlyGate {
    private static final String MARKER = "test-seam:";
    private static final Set<String> NOT_SIGNIFICANT_ANNOTATIONS = Set.of(
        "Nullable", "NotNull", "NonNull", "Nonnull", "Deprecated", "SafeVarargs", "FunctionalInterface", "VisibleForTesting");
    /** Types whose instances the game registers. */
    private static final Set<String> REGISTERED_ROOTS = Set.of(
        "net/minecraft/world/level/block/Block", "net/minecraft/world/item/Item", "net/minecraft/world/entity/EntityType",
        "net/minecraft/sounds/SoundEvent", "net/minecraft/world/level/block/entity/BlockEntityType",
        "net/minecraft/world/inventory/MenuType", "net/minecraft/world/level/material/Fluid", "net/minecraft/core/Holder",
        "net/minecraft/resources/ResourceKey", "net/minecraft/core/component/DataComponentType");

    /** Who references a member: production code (the mod, own class included) or the GameTest source set. */
    private static final class Refs {
        boolean production;
        String firstTest;
    }

    private record Member(ClassModel owner, String name, String descriptor, boolean field) {
        String refKey() {
            return TestOnlyGate.refKey(owner.thisClass().asInternalName(), name, field, descriptor);
        }

        /** {@code pkg.Outer$Inner#name(parameter descriptors)}, or {@code #FIELD}: what the baseline lists. */
        String baselineKey() {
            String binary = owner.thisClass().asInternalName().replace('/', '.');
            return binary + "#" + (field ? name : name + parameters(descriptor));
        }
    }

    private static String refKey(String owner, String name, boolean field, String descriptor) {
        return owner + "#" + name + (field ? ":" : "") + descriptor;
    }

    /** A member declaration found in a source file: where, in which class, and its parameter types as written. */
    private record Declaration(int line, String classPath, String name, boolean field, List<String> parameters) { }

    private final Map<String, ClassModel> production = new LinkedHashMap<>();
    private final Map<String, ClassModel> tests = new LinkedHashMap<>();
    private final List<ZipFile> jars = new ArrayList<>();
    private final List<Path> classDirs = new ArrayList<>();
    private final Map<String, ClassModel> loaded = new HashMap<>();
    private final Map<String, Refs> refs = new HashMap<>();
    private final Map<Path, List<String>> sourceLines = new HashMap<>();
    private final Map<Path, List<Declaration>> declarations = new HashMap<>();
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
        Map<String, String> findings = gate.run(
            options.get("--production").stream().map(Path::of).toList(), options.get("--tests").stream().map(Path::of).toList());
        if (options.containsKey("--list")) {
            findings.keySet().forEach(System.out::println);
            return;
        }
        Path baselineFile = options.containsKey("--baseline") ? Path.of(options.get("--baseline").getFirst()) : null;
        Set<String> baseline = baselineFile == null ? Set.of() : readBaseline(baselineFile);
        List<String> fresh = findings.entrySet().stream().filter(finding -> !baseline.contains(finding.getKey())).map(Map.Entry::getValue).toList();
        List<String> stale = baseline.stream().filter(key -> !findings.containsKey(key)).toList();
        if (!fresh.isEmpty()) {
            System.err.println("Members referenced only from src/gametest (" + fresh.size() + "). A test is not a production caller."
                + " Fix the member: delete it and have the test use the seam production uses, or make it private."
                + " A deliberate, permanent seam ends its declaration line, or sits on the comment line above, with"
                + " '// test-seam: <why it is deliberate>'.");
            fresh.forEach(message -> System.err.println("  " + message));
        }
        if (!stale.isEmpty()) {
            System.err.println("Stale entries in " + baselineFile + " (" + stale.size() + "). These members are no longer test-only:"
                + " delete each line, so the baseline only shrinks.");
            stale.forEach(key -> System.err.println("  " + key));
        }
        if (!fresh.isEmpty() || !stale.isEmpty()) {
            System.exit(1);
        }
    }

    private static Set<String> readBaseline(Path file) throws IOException {
        Set<String> keys = new TreeSet<>();
        for (String line : Files.readAllLines(file)) {
            String key = line.strip();
            if (!key.isEmpty() && !key.startsWith("#")) {
                keys.add(key);
            }
        }
        return keys;
    }

    private void addClasspath(Path entry) throws IOException {
        if (Files.isDirectory(entry)) {
            classDirs.add(entry);
        } else if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
            jars.add(new ZipFile(entry.toFile()));
        }
    }

    /** The finding message of each member the markers do not exempt, by baseline key. */
    Map<String, String> run(List<Path> productionDirs, List<Path> testDirs) throws IOException {
        classDirs.addAll(productionDirs);
        classDirs.addAll(testDirs);
        read(productionDirs, production);
        read(testDirs, tests);
        production.values().forEach(model -> scanReferences(model, true));
        tests.values().forEach(model -> scanReferences(model, false));
        Map<String, String> findings = new TreeMap<>();
        for (ClassModel model : production.values()) {
            for (Member member : candidates(model)) {
                Refs found = refs.get(member.refKey());
                if (found != null && !found.production) {
                    String message = report(member, found);
                    if (message != null) {
                        findings.put(member.baselineKey(), message);
                    }
                }
            }
        }
        return findings;
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
        ClassModel known = production.getOrDefault(internalName, tests.get(internalName));
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

    /** Every supertype of {@code model}. One that cannot be loaded fails the gate: it could declare the method an override implements. */
    private List<ClassModel> supertypes(ClassModel model) {
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
                throw new IllegalStateException("cannot load " + name + ", a supertype of " + model.thisClass().asInternalName()
                    + ": add its jar to --classpath");
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
                        case InvokeDynamicInstruction dynamic -> dynamic.invokedynamic().bootstrap().arguments()
                            .forEach(arg -> creditHandle(arg, origin, fromProduction));
                        case ConstantInstruction.LoadConstantInstruction load -> creditHandle(load.constantEntry(), origin, fromProduction);
                        default -> { }
                    }
                }
            });
        }
    }

    /** A class assigning its own field (an initializer, a constructor) uses nothing: only a read, or a write from another class, does. */
    private static boolean isOwnWrite(FieldInstruction access, String origin) {
        return (access.opcode() == Opcode.PUTFIELD || access.opcode() == Opcode.PUTSTATIC)
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
                Refs entry = refs.computeIfAbsent(refKey(next, name, field, descriptor), key -> new Refs());
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
        if (model.flags().has(AccessFlag.SYNTHETIC) || annotationsOf(model).anyMatch(annotation -> simpleName(annotation).equals("Mixin"))
            || isLocalOrAnonymous(model)) {
            return out;
        }
        boolean isEnum = model.flags().has(AccessFlag.ENUM);
        Set<String> components = new HashSet<>();
        model.findAttribute(Attributes.record()).ifPresent(record -> record.components().forEach(c -> components.add(c.name().stringValue())));
        List<ClassModel> parents = supertypes(model);
        Set<String> bridgeTargets = bridgeTargets(model);
        for (var field : model.fields()) {
            var flags = field.flags();
            if (flags.has(AccessFlag.PRIVATE) || flags.has(AccessFlag.SYNTHETIC) || flags.has(AccessFlag.ENUM)
                || field.findAttribute(Attributes.constantValue()).isPresent()
                || hasSignificantAnnotation(field)
                || (flags.has(AccessFlag.STATIC) && flags.has(AccessFlag.FINAL) && isRegisteredType(field.fieldType().stringValue()))) {
                continue;
            }
            out.add(new Member(model, field.fieldName().stringValue(), field.fieldType().stringValue(), true));
        }
        for (MethodModel method : model.methods()) {
            String name = method.methodName().stringValue();
            String descriptor = method.methodType().stringValue();
            var flags = method.flags();
            if (flags.has(AccessFlag.PRIVATE) || flags.has(AccessFlag.SYNTHETIC) || flags.has(AccessFlag.BRIDGE)
                || flags.has(AccessFlag.ABSTRACT) || name.equals("<clinit>")
                || hasSignificantAnnotation(method)) {
                continue;
            }
            MethodTypeDesc type = MethodTypeDesc.ofDescriptor(descriptor);
            if (isEnum && (name.equals("values") || name.equals("valueOf"))
                || type.parameterCount() == 0 && components.contains(name)) {
                continue;
            }
            boolean inherited = !name.equals("<init>") && !flags.has(AccessFlag.STATIC)
                && (bridgeTargets.contains(name + parameters(descriptor)) || overrides(parents, name, parameters(descriptor)));
            if (!inherited) {
                out.add(new Member(model, name, descriptor, false));
            }
        }
        return out;
    }

    private static boolean isLocalOrAnonymous(ClassModel model) {
        String binary = model.thisClass().asInternalName();
        String last = binary.substring(binary.lastIndexOf('/') + 1);
        return Stream.of(last.split("\\$")).skip(1).anyMatch(part -> !part.isEmpty() && Character.isDigit(part.charAt(0)));
    }

    /** The methods that a bridge method of {@code model} calls: they implement a generic supertype method under another erasure. */
    private static Set<String> bridgeTargets(ClassModel model) {
        Set<String> targets = new HashSet<>();
        String self = model.thisClass().asInternalName();
        for (MethodModel bridge : model.methods()) {
            if (!bridge.flags().has(AccessFlag.BRIDGE)) {
                continue;
            }
            bridge.code().ifPresent(code -> {
                for (CodeElement element : code) {
                    if (element instanceof InvokeInstruction call && call.owner().asInternalName().equals(self)
                        && call.name().stringValue().equals(bridge.methodName().stringValue())) {
                        targets.add(call.name().stringValue() + parameters(call.type().stringValue()));
                    }
                }
            });
        }
        return targets;
    }

    private static boolean overrides(List<ClassModel> parents, String name, String parameters) {
        return parents.stream().flatMap(parent -> parent.methods().stream()).anyMatch(method ->
            method.methodName().stringValue().equals(name)
                && !method.flags().has(AccessFlag.PRIVATE) && !method.flags().has(AccessFlag.STATIC)
                && parameters(method.methodType().stringValue()).equals(parameters));
    }

    /** The parameter part of a method descriptor, {@code (ILjava/lang/String;)}. */
    private static String parameters(String descriptor) {
        return descriptor.substring(0, descriptor.indexOf(')') + 1);
    }

    private boolean isRegisteredType(String fieldDescriptor) {
        if (!fieldDescriptor.startsWith("L")) {
            return false;
        }
        String name = fieldDescriptor.substring(1, fieldDescriptor.length() - 1);
        ClassModel type = find(name);
        return REGISTERED_ROOTS.contains(name)
            || type != null && Stream.concat(Stream.of(type), supertypes(type).stream())
                .anyMatch(parent -> REGISTERED_ROOTS.contains(parent.thisClass().asInternalName()));
    }

    private static Stream<Annotation> annotationsOf(AttributedElement element) {
        return Stream.concat(
            element.findAttribute(Attributes.runtimeVisibleAnnotations()).stream().flatMap(a -> a.annotations().stream()),
            element.findAttribute(Attributes.runtimeInvisibleAnnotations()).stream().flatMap(a -> a.annotations().stream()));
    }

    private static boolean hasSignificantAnnotation(AttributedElement element) {
        return annotationsOf(element).anyMatch(annotation -> !NOT_SIGNIFICANT_ANNOTATIONS.contains(simpleName(annotation)));
    }

    private static String simpleName(Annotation annotation) {
        String descriptor = annotation.className().stringValue();
        return descriptor.substring(Math.max(descriptor.lastIndexOf('/'), descriptor.lastIndexOf('$')) + 1, descriptor.length() - 1);
    }

    /** The finding line, or null when a valid marker on this exact declaration exempts it (or it is an implicit constructor). */
    private String report(Member member, Refs found) {
        String binary = member.owner().thisClass().asInternalName();
        String classPath = binary.substring(binary.lastIndexOf('/') + 1).replace('$', '.');
        String innermost = classPath.substring(classPath.lastIndexOf('.') + 1);
        boolean constructor = member.name().equals("<init>");
        String label;
        if (member.field()) {
            label = classPath + "." + member.name();
        } else if (constructor) {
            label = "new " + classPath + "(" + parameterNames(member.descriptor()) + ")";
        } else {
            label = classPath + "." + member.name() + "(" + parameterNames(member.descriptor()) + ")";
        }
        String sourceName = member.owner().findAttribute(Attributes.sourceFile()).map(a -> a.sourceFile().stringValue()).orElse(null);
        Path source = sourceName == null ? null : locate(binary, sourceName);
        String where = binary.substring(binary.lastIndexOf('/') + 1);
        int line = 0;
        boolean bare = false;
        if (source != null) {
            where = root.relativize(source.toAbsolutePath().normalize()).toString();
            Declaration declaration = declarationOf(source, classPath, constructor ? innermost : member.name(), member);
            if (declaration != null) {
                line = declaration.line();
                String marker = markerFor(sourceLines.get(source), line);
                if (marker != null && !marker.isBlank()) {
                    return null;
                }
                bare = marker != null;
            }
        }
        if (line == 0 && constructor) {
            return null;
        }
        String caller = found.firstTest.substring(found.firstTest.lastIndexOf('/') + 1);
        return where + ":" + line + " " + label + " is referenced only from src/gametest (first: " + caller + ")"
            + (bare ? "; its '// test-seam:' marker has no reason" : "");
    }

    private static String parameterNames(String descriptor) {
        return MethodTypeDesc.ofDescriptor(descriptor).parameterList().stream()
            .map(ClassDesc::displayName).collect(Collectors.joining(", "));
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

    /** The declaration of {@code member} in {@code source}: same class, name and parameter types, so one overload or nested class never stands for another. */
    private Declaration declarationOf(Path source, String classPath, String name, Member member) {
        List<Declaration> all = declarations.computeIfAbsent(source, path -> declarationsIn(path));
        List<String> wanted = member.field() ? List.of() : MethodTypeDesc.ofDescriptor(member.descriptor()).parameterList().stream()
            .map(type -> type.displayName().substring(type.displayName().lastIndexOf('$') + 1)).toList();
        return all.stream().filter(d -> d.classPath().equals(classPath) && d.name().equals(name) && d.field() == member.field()
            && (member.field() || sameParameters(d.parameters(), wanted))).findFirst().orElse(null);
    }

    private static boolean sameParameters(List<String> written, List<String> erased) {
        if (written.size() != erased.size()) {
            return false;
        }
        for (int i = 0; i < written.size(); i++) {
            String type = written.get(i);
            if (!type.equals(erased.get(i)) && !type.matches("[A-Z][A-Z0-9]?")) {
                return false;
            }
        }
        return true;
    }

    /** The text after {@code // test-seam:} on declaration {@code line} (1-based) or, when that line has none, on a comment-only line above. */
    private static String markerFor(List<String> lines, int line) {
        String same = marker(lines.get(line - 1));
        if (same != null) {
            return same;
        }
        return line >= 2 && lines.get(line - 2).strip().startsWith("//") ? marker(lines.get(line - 2)) : null;
    }

    private List<Declaration> declarationsIn(Path source) {
        List<String> lines;
        try {
            lines = Files.readAllLines(source);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + source, e);
        }
        sourceLines.put(source, lines);
        List<Declaration> out = new ArrayList<>();
        ArrayDeque<String> classes = new ArrayDeque<>();
        ArrayDeque<Integer> classDepths = new ArrayDeque<>();
        String pending = null;
        int depth = 0;
        boolean inBlockComment = false;
        Pattern classDeclaration = Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)");
        for (int index = 0; index < lines.size(); index++) {
            String raw = lines.get(index);
            if (inBlockComment) {
                int end = raw.indexOf("*/");
                if (end < 0) {
                    continue;
                }
                raw = raw.substring(end + 2);
                inBlockComment = false;
            }
            String code = code(raw).replaceAll("/\\*.*?\\*/", " ");
            if (code.contains("/*")) {
                code = code.substring(0, code.indexOf("/*"));
                inBlockComment = true;
            }
            Matcher declared = classDeclaration.matcher(code);
            boolean atMemberLevel = !classes.isEmpty() && depth == classDepths.peek();
            if (declared.find()) {
                pending = declared.group(1);
            } else if (atMemberLevel) {
                Declaration member = memberDeclaration(lines, index, code, String.join(".", reversed(classes)), classes.peek());
                if (member != null) {
                    out.add(member);
                }
            }
            for (char c : code.toCharArray()) {
                if (c == '{') {
                    depth++;
                    if (pending != null) {
                        classes.push(pending);
                        classDepths.push(depth);
                        pending = null;
                    }
                } else if (c == '}') {
                    if (!classDepths.isEmpty() && classDepths.peek() == depth) {
                        classes.pop();
                        classDepths.pop();
                    }
                    depth--;
                }
            }
        }
        return out;
    }

    private static List<String> reversed(ArrayDeque<String> stack) {
        List<String> out = new ArrayList<>(stack);
        java.util.Collections.reverse(out);
        return out;
    }

    private static final Set<String> MODIFIERS = Set.of(
        "public", "protected", "private", "static", "final", "abstract", "synchronized", "native", "default", "strictfp", "transient", "volatile");

    /** A method, constructor or field declared on {@code code}, or null: a type or modifiers before the name, never a statement. */
    private static Declaration memberDeclaration(List<String> lines, int index, String code, String classPath, String className) {
        Matcher first = Pattern.compile("[(=;,]").matcher(code);
        if (!first.find()) {
            return null;
        }
        boolean method = code.charAt(first.start()) == '(';
        String head = code.substring(0, first.start()).replaceAll("@\\w+(?:\\.\\w+)*(?:\\([^)]*\\))?", " ").replaceAll("<[^<>]*(?:<[^<>]*>[^<>]*)*>", " ").strip();
        if (head.isEmpty()) {
            return null;
        }
        String[] words = head.split("\\s+");
        String name = words[words.length - 1];
        if (!name.matches("\\w+")) {
            return null;
        }
        List<String> before = Stream.of(words).limit(words.length - 1).filter(word -> !MODIFIERS.contains(word)).toList();
        if (before.stream().anyMatch(word -> Set.of("return", "throw", "new", "else", "case", "yield", "package", "import").contains(word))) {
            return null;
        }
        boolean constructor = method && before.isEmpty() && name.equals(className);
        if (before.isEmpty() && !constructor) {
            return null;
        }
        if (!method) {
            return new Declaration(index + 1, classPath, name, true, List.of());
        }
        return new Declaration(index + 1, classPath, name, false, parameterTypes(lines, index, first.start()));
    }

    /** The parameter types as written, simple names, generics dropped, varargs as arrays. */
    private static List<String> parameterTypes(List<String> lines, int index, int open) {
        StringBuilder text = new StringBuilder();
        int depth = 0;
        boolean started = false;
        outer:
        for (int i = index; i < lines.size(); i++) {
            String code = code(lines.get(i));
            for (int j = i == index ? open : 0; j < code.length(); j++) {
                char c = code.charAt(j);
                if (c == '(') {
                    depth++;
                    if (depth == 1) {
                        started = true;
                        continue;
                    }
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        break outer;
                    }
                }
                if (started) {
                    text.append(c);
                }
            }
            text.append(' ');
        }
        String params = text.toString().replaceAll("@\\w+(?:\\.\\w+)*(?:\\([^)]*\\))?", " ").strip();
        if (params.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int angle = 0;
        StringBuilder current = new StringBuilder();
        for (char c : params.toCharArray()) {
            if (c == '<') {
                angle++;
            } else if (c == '>') {
                angle--;
            }
            if (c == ',' && angle == 0) {
                out.add(simpleType(current.toString()));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(simpleType(current.toString()));
        return out;
    }

    private static String simpleType(String parameter) {
        String stripped = parameter.replaceAll("<[^<>]*(?:<[^<>]*>[^<>]*)*>", "").replaceAll("\\bfinal\\b", " ").strip();
        String type = stripped.substring(0, Math.max(0, stripped.lastIndexOf(' '))).strip().replaceAll("\\s+", "");
        type = type.endsWith("...") ? type.substring(0, type.length() - 3) + "[]" : type;
        return type.substring(type.lastIndexOf('.') + 1);
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
