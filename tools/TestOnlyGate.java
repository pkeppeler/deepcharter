import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * <p>A deliberate, permanent seam ends its declaration line, or sits in the block of comment lines just above (palantir wraps a long comment onto more lines), with {@code // test-seam: <reason>};
 * the reason is required. The marker binds to the exact declaration: the same name in another class of the file, or another overload,
 * is not exempted by it.
 *
 * <p>Every finding fails; nothing is grandfathered. --list prints the key of every finding ({@code Class#member(parameters)}) and exits 0.
 *
 * <p>Usage: java TestOnlyGate.java --production dir... --tests dir... --sources dir... [--classpath entry...] [--root dir]
 * [--list]. Production and test dirs are class output dirs; sources are the java roots of the production source sets;
 * the classpath is the jars and dirs that supertypes are loaded from. Exit 1 prints the failures.
 *
 * <p>With {@code --constant-params} (and no {@code --tests}) the same scan runs as the constant-parameter gate (#912): a parameter of a
 * method or constructor of the mod (private ones too) that has at least one production caller, every one of which passes the same literal
 * there, fails. A literal is a number, string, char, boolean, {@code null}, class literal or enum constant; a {@code static final}
 * constant that is not inlined (a registered object, say) is not one. Callers are read from the compiled classes with a small stack
 * simulation, so a call whose argument is a variable, a ternary or any other expression counts as varying. A method handle or method
 * reference counts as a caller with unknown arguments. It passes, besides what the test-only gate passes (overrides, annotated members,
 * {@code @Mixin} classes, local and anonymous classes): a record's canonical constructor, a varargs parameter, the synthetic enum and
 * outer-instance parameters, and a method with no production caller. The marker is {@code // const-param: <reason>}, placed like
 * {@code test-seam:}, and its reason needs two or more words that are not a stock phrase.
 */
public final class TestOnlyGate {
    private static final String MARKER = "test-seam:";
    private static final String CONSTANT_MARKER = "const-param:";
    /** Stock phrases that say nothing: a {@code const-param:} reason that is one of these is no reason. */
    private static final Set<String> GENERIC_REASONS = Set.of(
            "not used",
            "unused",
            "needed",
            "needed later",
            "used later",
            "keep",
            "keep it",
            "ok",
            "fine",
            "todo",
            "n/a",
            "later",
            "required",
            "intentional",
            "on purpose",
            "no reason",
            "because",
            "future use",
            "for later",
            "might need");

    private static final Set<String> BOXES = Set.of(
            "java/lang/Boolean",
            "java/lang/Byte",
            "java/lang/Character",
            "java/lang/Short",
            "java/lang/Integer",
            "java/lang/Long",
            "java/lang/Float",
            "java/lang/Double");
    /** What an argument or parameter holds when it is not one literal: an expression, or literals that differ. */
    private static final String VARIES = "\0";

    private static final Set<String> NOT_SIGNIFICANT_ANNOTATIONS = Set.of(
            "Nullable",
            "NotNull",
            "NonNull",
            "Nonnull",
            "Deprecated",
            "SafeVarargs",
            "FunctionalInterface",
            "VisibleForTesting");
    /** Types whose instances the game registers. */
    private static final Set<String> REGISTERED_ROOTS = Set.of(
            "net/minecraft/world/level/block/Block",
            "net/minecraft/world/item/Item",
            "net/minecraft/world/entity/EntityType",
            "net/minecraft/sounds/SoundEvent",
            "net/minecraft/world/level/block/entity/BlockEntityType",
            "net/minecraft/world/inventory/MenuType",
            "net/minecraft/world/level/material/Fluid",
            "net/minecraft/core/Holder",
            "net/minecraft/resources/ResourceKey",
            "net/minecraft/core/component/DataComponentType");

    /** Who references a member: production code (the mod, own class included) or the GameTest source set. */
    private static final class Refs {
        boolean production;
        String firstTest;
    }

    private record Member(ClassModel owner, String name, String descriptor, boolean field) {
        String refKey() {
            return TestOnlyGate.refKey(owner.thisClass().asInternalName(), name, field, descriptor);
        }

        /** {@code pkg.Outer$Inner#name(parameter descriptors)}, or {@code #FIELD}: what --list prints. */
        String key() {
            String binary = owner.thisClass().asInternalName().replace('/', '.');
            return binary + "#" + (field ? name : name + parameters(descriptor));
        }
    }

    private static String refKey(String owner, String name, boolean field, String descriptor) {
        return owner + "#" + name + (field ? ":" : "") + descriptor;
    }

    /** A source file's lines as code only, and each line's trailing {@code //} comment (null when none). */
    private record Lexed(List<String> code, List<String> comments) {}

    /** A member declaration found in a source file: where, in which class, and its parameter types as written. */
    private record Declaration(
            int line, String classPath, String name, boolean field, List<String> parameters, List<String> names) {}

    private final Map<String, ClassModel> production = new LinkedHashMap<>();
    private final Map<String, ClassModel> tests = new LinkedHashMap<>();
    private final List<ZipFile> jars = new ArrayList<>();
    private final List<Path> classDirs = new ArrayList<>();
    private final Map<String, ClassModel> loaded = new HashMap<>();
    private final Map<String, Refs> refs = new HashMap<>();
    private final Map<Path, Lexed> lexed = new HashMap<>();
    private final Map<String, ArgUse> argUses = new HashMap<>();
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
        boolean constantParams = options.containsKey("--constant-params");
        for (String required : constantParams
                ? List.of("--production", "--sources")
                : List.of("--production", "--tests", "--sources")) {
            if (options.getOrDefault(required, List.of()).isEmpty()) {
                throw new IllegalArgumentException("TestOnlyGate: " + required + " is required");
            }
        }
        List<Path> sources = options.get("--sources").stream().map(Path::of).toList();
        Path root = Path.of(options.getOrDefault("--root", List.of(".")).getFirst())
                .toAbsolutePath()
                .normalize();
        TestOnlyGate gate = new TestOnlyGate(sources, root);
        for (String entry : options.getOrDefault("--classpath", List.of())) {
            gate.addClasspath(Path.of(entry));
        }
        List<Path> productionDirs =
                options.get("--production").stream().map(Path::of).toList();
        Map<String, String> findings = constantParams
                ? gate.runConstantParams(productionDirs)
                : gate.run(
                        productionDirs,
                        options.get("--tests").stream().map(Path::of).toList());
        if (options.containsKey("--list")) {
            findings.keySet().forEach(System.out::println);
            return;
        }
        List<String> fresh = List.copyOf(findings.values());
        if (!fresh.isEmpty() && constantParams) {
            System.err.println(
                    "Parameters every production caller passes the same literal (" + fresh.size() + ")."
                            + " Fix the method: inline the literal and drop the parameter, or make the callers vary."
                            + " A deliberate seam ends its declaration line, or sits on the comment line above, with"
                            + " '// const-param: <why callers must be able to vary it>' (two or more words, not a stock phrase).");
            fresh.forEach(message -> System.err.println("  " + message));
            System.exit(1);
        }
        if (!fresh.isEmpty()) {
            System.err.println("Members referenced only from src/gametest (" + fresh.size()
                    + "). A test is not a production caller."
                    + " Fix the member: delete it and have the test use the seam production uses, or make it private."
                    + " A deliberate, permanent seam ends its declaration line, or sits on the comment line above, with"
                    + " '// test-seam: <why it is deliberate>'.");
            fresh.forEach(message -> System.err.println("  " + message));
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

    /** The finding message of each member the markers do not exempt, by key. */
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
                        findings.put(member.key(), message);
                    }
                }
            }
        }
        return findings;
    }

    /** One operand-stack entry: the literal it holds, or null when it is not one. */
    private record Val(String literal, boolean wide) {
        static final Val UNKNOWN = new Val(null, false);
        static final Val UNKNOWN_WIDE = new Val(null, true);

        static Val unknown(boolean wide) {
            return wide ? UNKNOWN_WIDE : UNKNOWN;
        }
    }

    /** What the production call sites of one method pass: how many, and per parameter the one literal all pass, else {@link #VARIES}. */
    private static final class ArgUse {
        int calls;
        String[] values;

        void add(List<String> args) {
            if (values == null) {
                values = args.toArray(new String[0]);
            } else {
                for (int i = 0; i < values.length; i++) {
                    if (!values[i].equals(args.get(i))) {
                        values[i] = VARIES;
                    }
                }
            }
            calls++;
        }
    }

    /** The operand stack of one method as the scan walks it, with the stack each jump carried to its target. */
    private static final class Frame {
        private List<Val> stack = new ArrayList<>();
        private boolean reachable = true;
        private final Map<Label, List<Val>> jumps = new HashMap<>();

        Val pop() {
            return stack.isEmpty() ? Val.UNKNOWN : stack.remove(stack.size() - 1);
        }

        void push(Val value) {
            stack.add(value);
        }

        void jump(Label target) {
            jumps.merge(target, List.copyOf(stack), Frame::merge);
        }

        /** Reaching a label: the stack its jumps carried, merged with the fall-through one, or just the exception when it is a handler. */
        void arrive(Label label, boolean handler) {
            List<Val> jumped = jumps.get(label);
            if (handler) {
                stack = new ArrayList<>(List.of(Val.UNKNOWN));
            } else if (jumped != null) {
                stack = new ArrayList<>(reachable ? merge(stack, jumped) : jumped);
            } else if (!reachable) {
                stack = new ArrayList<>();
            }
            reachable = true;
        }

        void end() {
            reachable = false;
        }

        private static List<Val> merge(List<Val> a, List<Val> b) {
            if (a.size() != b.size()) {
                return Collections.nCopies(Math.min(a.size(), b.size()), Val.UNKNOWN);
            }
            List<Val> out = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                out.add(
                        a.get(i).equals(b.get(i))
                                ? a.get(i)
                                : Val.unknown(a.get(i).wide()));
            }
            return out;
        }
    }

    /** The finding message of each constant parameter the markers do not exempt, by key. */
    Map<String, String> runConstantParams(List<Path> productionDirs) throws IOException {
        classDirs.addAll(productionDirs);
        read(productionDirs, production);
        production.values().forEach(this::scanCalls);
        Map<String, String> findings = new TreeMap<>();
        for (ClassModel model : production.values()) {
            for (MethodModel method : constantCandidates(model)) {
                ArgUse uses = argUses.get(refKey(
                        model.thisClass().asInternalName(),
                        method.methodName().stringValue(),
                        false,
                        method.methodType().stringValue()));
                if (uses != null) {
                    findings.putAll(constantFindings(model, method, uses));
                }
            }
        }
        return findings;
    }

    private void scanCalls(ClassModel from) {
        for (MethodModel method : from.methods()) {
            method.code().ifPresent(this::simulate);
        }
    }

    /** Walks the code once, tracking which stack entries are literals, and records the arguments of every call into the mod. */
    private void simulate(CodeModel code) {
        Set<Label> handlers =
                code.exceptionHandlers().stream().map(ExceptionCatch::handler).collect(Collectors.toSet());
        Frame frame = new Frame();
        for (CodeElement element : code) {
            switch (element) {
                case LabelTarget target -> frame.arrive(target.label(), handlers.contains(target.label()));
                case ConstantInstruction constant -> {
                    if (constant instanceof ConstantInstruction.LoadConstantInstruction load
                            && load.constantEntry() instanceof MethodHandleEntry handle) {
                        creditUnknown(handle);
                        frame.push(Val.UNKNOWN);
                    } else {
                        String literal =
                                constant.opcode() == Opcode.ACONST_NULL ? "null" : literal(constant.constantValue());
                        frame.push(new Val(literal, constant.typeKind().slotSize() == 2));
                    }
                }
                case InvokeInstruction call -> simulateCall(frame, call);
                case InvokeDynamicInstruction dynamic -> {
                    MethodTypeDesc type =
                            MethodTypeDesc.ofDescriptor(dynamic.type().stringValue());
                    for (int i = 0; i < type.parameterCount(); i++) {
                        frame.pop();
                    }
                    pushResult(frame, type);
                    dynamic.invokedynamic().bootstrap().arguments().forEach(arg -> {
                        if (arg instanceof MethodHandleEntry handle) {
                            creditUnknown(handle);
                        }
                    });
                }
                case FieldInstruction access -> {
                    String type = access.type().stringValue();
                    boolean wide = type.equals("J") || type.equals("D");
                    switch (access.opcode()) {
                        case GETSTATIC ->
                            frame.push(
                                    isEnumConstant(
                                                    access.owner().asInternalName(),
                                                    access.name().stringValue())
                                            ? new Val(
                                                    simpleName(access.owner().asInternalName()) + "."
                                                            + access.name().stringValue(),
                                                    false)
                                            : Val.unknown(wide));
                        case GETFIELD -> {
                            frame.pop();
                            frame.push(Val.unknown(wide));
                        }
                        case PUTSTATIC -> frame.pop();
                        default -> {
                            frame.pop();
                            frame.pop();
                        }
                    }
                }
                case LoadInstruction load ->
                    frame.push(Val.unknown(load.typeKind().slotSize() == 2));
                case StoreInstruction ignored -> frame.pop();
                case ArrayLoadInstruction load -> {
                    frame.pop();
                    frame.pop();
                    frame.push(Val.unknown(load.typeKind().slotSize() == 2));
                }
                case ArrayStoreInstruction ignored -> {
                    frame.pop();
                    frame.pop();
                    frame.pop();
                }
                case OperatorInstruction operator -> {
                    String name = operator.opcode().name();
                    frame.pop();
                    if (!name.endsWith("NEG") && !name.equals("ARRAYLENGTH")) {
                        frame.pop();
                    }
                    frame.push(Val.unknown((name.startsWith("L") || name.startsWith("D")) && !name.contains("CMP")));
                }
                case ConvertInstruction convert -> {
                    frame.pop();
                    frame.push(Val.unknown(convert.toType().slotSize() == 2));
                }
                case StackInstruction stack -> simulateStack(frame, stack.opcode());
                case NewObjectInstruction ignored -> frame.push(Val.UNKNOWN);
                case NewPrimitiveArrayInstruction ignored -> {
                    frame.pop();
                    frame.push(Val.UNKNOWN);
                }
                case NewReferenceArrayInstruction ignored -> {
                    frame.pop();
                    frame.push(Val.UNKNOWN);
                }
                case NewMultiArrayInstruction array -> {
                    for (int i = 0; i < array.dimensions(); i++) {
                        frame.pop();
                    }
                    frame.push(Val.UNKNOWN);
                }
                case TypeCheckInstruction ignored -> {
                    frame.pop();
                    frame.push(Val.UNKNOWN);
                }
                case MonitorInstruction ignored -> frame.pop();
                case BranchInstruction branch -> {
                    switch (branch.opcode()) {
                        case GOTO, GOTO_W -> {}
                        case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE, IFNULL, IFNONNULL -> frame.pop();
                        default -> {
                            frame.pop();
                            frame.pop();
                        }
                    }
                    frame.jump(branch.target());
                    if (branch.opcode() == Opcode.GOTO || branch.opcode() == Opcode.GOTO_W) {
                        frame.end();
                    }
                }
                case LookupSwitchInstruction table -> {
                    frame.pop();
                    frame.jump(table.defaultTarget());
                    table.cases().stream().map(SwitchCase::target).forEach(frame::jump);
                    frame.end();
                }
                case TableSwitchInstruction table -> {
                    frame.pop();
                    frame.jump(table.defaultTarget());
                    table.cases().stream().map(SwitchCase::target).forEach(frame::jump);
                    frame.end();
                }
                case ReturnInstruction ret -> {
                    if (ret.typeKind() != TypeKind.VOID) {
                        frame.pop();
                    }
                    frame.end();
                }
                case ThrowInstruction ignored -> {
                    frame.pop();
                    frame.end();
                }
                default -> {}
            }
        }
    }

    private void simulateCall(Frame frame, InvokeInstruction call) {
        MethodTypeDesc type = MethodTypeDesc.ofDescriptor(call.type().stringValue());
        String[] args = new String[type.parameterCount()];
        for (int i = args.length - 1; i >= 0; i--) {
            args[i] = display(frame.pop().literal(), type.parameterType(i).descriptorString());
        }
        boolean isStatic = call.opcode() == Opcode.INVOKESTATIC;
        if (!isStatic) {
            frame.pop();
        }
        String owner = call.owner().asInternalName();
        String name = call.name().stringValue();
        record(owner, name, call.type().stringValue(), Arrays.asList(args));
        if (isStatic
                && BOXES.contains(owner)
                && name.equals("valueOf")
                && args.length == 1
                && !args[0].equals(VARIES)
                && type.parameterType(0).isPrimitive()) {
            frame.push(new Val(args[0], false));
        } else {
            pushResult(frame, type);
        }
    }

    private static void pushResult(Frame frame, MethodTypeDesc type) {
        String result = type.returnType().descriptorString();
        if (!result.equals("V")) {
            frame.push(Val.unknown(result.equals("J") || result.equals("D")));
        }
    }

    private static void simulateStack(Frame frame, Opcode opcode) {
        switch (opcode) {
            case POP -> frame.pop();
            case POP2 -> {
                if (!frame.pop().wide()) {
                    frame.pop();
                }
            }
            case DUP -> {
                Val a = frame.pop();
                pushAll(frame, a, a);
            }
            case DUP_X1 -> {
                Val a = frame.pop();
                Val b = frame.pop();
                pushAll(frame, a, b, a);
            }
            case DUP_X2 -> {
                Val a = frame.pop();
                Val b = frame.pop();
                if (b.wide()) {
                    pushAll(frame, a, b, a);
                } else {
                    pushAll(frame, a, frame.pop(), b, a);
                }
            }
            case DUP2 -> {
                Val a = frame.pop();
                if (a.wide()) {
                    pushAll(frame, a, a);
                } else {
                    Val b = frame.pop();
                    pushAll(frame, b, a, b, a);
                }
            }
            case DUP2_X1 -> {
                Val a = frame.pop();
                Val b = frame.pop();
                if (a.wide()) {
                    pushAll(frame, a, b, a);
                } else {
                    pushAll(frame, b, a, frame.pop(), b, a);
                }
            }
            case DUP2_X2 -> {
                Val a = frame.pop();
                Val b = frame.pop();
                if (a.wide() && b.wide()) {
                    pushAll(frame, a, b, a);
                } else if (a.wide()) {
                    pushAll(frame, a, frame.pop(), b, a);
                } else {
                    Val c = frame.pop();
                    if (c.wide()) {
                        pushAll(frame, b, a, c, b, a);
                    } else {
                        pushAll(frame, b, a, frame.pop(), c, b, a);
                    }
                }
            }
            case SWAP -> {
                Val a = frame.pop();
                Val b = frame.pop();
                pushAll(frame, a, b);
            }
            default -> {}
        }
    }

    private static void pushAll(Frame frame, Val... values) {
        for (Val value : values) {
            frame.push(value);
        }
    }

    /** Records one call's arguments against the method javac resolved it to: the first declaration up the receiver type's hierarchy. */
    private void record(String owner, String name, String descriptor, List<String> args) {
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
            if (model.methods().stream()
                    .anyMatch(method -> method.methodName().stringValue().equals(name)
                            && method.methodType().stringValue().equals(descriptor))) {
                if (production.containsKey(next)) {
                    argUses.computeIfAbsent(refKey(next, name, false, descriptor), key -> new ArgUse())
                            .add(args);
                }
            } else {
                enqueueSupers(model, queue);
            }
        }
    }

    /** A method handle or reference is a caller whose arguments the scan cannot see. */
    private void creditUnknown(MethodHandleEntry handle) {
        MemberRefEntry target = handle.reference();
        String descriptor = target.nameAndType().type().stringValue();
        if (descriptor.charAt(0) == '(') {
            record(
                    target.owner().asInternalName(),
                    target.name().stringValue(),
                    descriptor,
                    Collections.nCopies(MethodTypeDesc.ofDescriptor(descriptor).parameterCount(), VARIES));
        }
    }

    private boolean isEnumConstant(String owner, String name) {
        ClassModel model = find(owner);
        return model != null
                && model.fields().stream()
                        .anyMatch(field -> field.fieldName().stringValue().equals(name)
                                && field.flags().has(AccessFlag.ENUM));
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1).replace('$', '.');
    }

    /** A constant as source writes it, or null for one that is not a literal (a method handle, a dynamic constant). */
    private static String literal(Object value) {
        return switch (value) {
            case Integer number -> number.toString();
            case Long number -> number + "L";
            case Float number -> number + "f";
            case Double number -> number.toString();
            case String text -> "\"" + escape(text) + "\"";
            case ClassDesc type -> type.displayName() + ".class";
            default -> null;
        };
    }

    /** A literal as the called method's parameter type reads it: an int is a boolean or a char there. */
    private static String display(String literal, String parameterDescriptor) {
        if (literal == null) {
            return VARIES;
        }
        if (parameterDescriptor.equals("Z") && literal.matches("[01]")) {
            return literal.equals("1") ? "true" : "false";
        }
        if (parameterDescriptor.equals("C") && literal.matches("\\d+")) {
            return "'" + escape(String.valueOf((char) Integer.parseInt(literal))) + "'";
        }
        return literal;
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\'' -> out.append("\\'");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return out.toString();
    }

    /** The methods and constructors whose parameters the gate judges: what no signature, annotation or framework fixes. */
    private List<MethodModel> constantCandidates(ClassModel model) {
        List<MethodModel> out = new ArrayList<>();
        if (model.flags().has(AccessFlag.SYNTHETIC)
                || annotationsOf(model)
                        .anyMatch(annotation -> simpleName(annotation).equals("Mixin"))
                || isLocalOrAnonymous(model)) {
            return out;
        }
        boolean isEnum = model.flags().has(AccessFlag.ENUM);
        String canonical = model.findAttribute(Attributes.record())
                .map(record -> record.components().stream()
                        .map(c -> c.descriptor().stringValue())
                        .collect(Collectors.joining("", "(", ")V")))
                .orElse(null);
        List<ClassModel> parents = supertypes(model);
        Set<String> bridgeTargets = bridgeTargets(model);
        for (MethodModel method : model.methods()) {
            String name = method.methodName().stringValue();
            String descriptor = method.methodType().stringValue();
            var flags = method.flags();
            if (flags.has(AccessFlag.SYNTHETIC)
                    || flags.has(AccessFlag.BRIDGE)
                    || flags.has(AccessFlag.ABSTRACT)
                    || name.equals("<clinit>")
                    || hasSignificantAnnotation(method)
                    || isEnum && name.equals("valueOf")
                    || name.equals("<init>") && descriptor.equals(canonical)
                    || MethodTypeDesc.ofDescriptor(descriptor).parameterCount() <= syntheticParameters(model, name)) {
                continue;
            }
            boolean inherited = !name.equals("<init>")
                    && !flags.has(AccessFlag.STATIC)
                    && !flags.has(AccessFlag.PRIVATE)
                    && (bridgeTargets.contains(name + parameters(descriptor))
                            || overrides(parents, name, parameters(descriptor)));
            if (!inherited) {
                out.add(method);
            }
        }
        return out;
    }

    /** How many leading descriptor parameters javac added that the source does not declare: an enum's name and ordinal, a member class's outer instance. */
    private static int syntheticParameters(ClassModel model, String methodName) {
        if (!methodName.equals("<init>")) {
            return 0;
        }
        if (model.flags().has(AccessFlag.ENUM)) {
            return 2;
        }
        String self = model.thisClass().asInternalName();
        return model.findAttribute(Attributes.innerClasses()).stream()
                        .flatMap(attribute -> attribute.classes().stream())
                        .anyMatch(inner -> inner.innerClass().asInternalName().equals(self)
                                && inner.outerClass().isPresent()
                                && !inner.flags().contains(AccessFlag.STATIC))
                ? 1
                : 0;
    }

    private Map<String, String> constantFindings(ClassModel model, MethodModel method, ArgUse uses) {
        String name = method.methodName().stringValue();
        String descriptor = method.methodType().stringValue();
        int count = MethodTypeDesc.ofDescriptor(descriptor).parameterCount();
        int skip = syntheticParameters(model, name);
        int last = method.flags().has(AccessFlag.VARARGS) ? count - 1 : count;
        List<Integer> constant = new ArrayList<>();
        for (int i = skip; i < last; i++) {
            if (!uses.values[i].equals(VARIES)) {
                constant.add(i);
            }
        }
        if (constant.isEmpty()) {
            return Map.of();
        }
        String binary = model.thisClass().asInternalName();
        String classPath = binary.substring(binary.lastIndexOf('/') + 1).replace('$', '.');
        boolean constructor = name.equals("<init>");
        Member member = new Member(model, name, descriptor, false);
        String label = labelOf(member, classPath, skip);
        String sourceName = model.findAttribute(Attributes.sourceFile())
                .map(a -> a.sourceFile().stringValue())
                .orElse(null);
        Path source = sourceName == null ? null : locate(binary, sourceName);
        if (source == null) {
            return Map.of();
        }
        String where = root.relativize(source.toAbsolutePath().normalize()).toString();
        Declaration declaration = declarationOf(
                source,
                classPath,
                constructor ? classPath.substring(classPath.lastIndexOf('.') + 1) : name,
                member,
                skip);
        if (declaration == null || declaration.names().size() != count - skip) {
            if (constructor) {
                return Map.of();
            }
            throw new IllegalStateException(
                    where + ": cannot find the declaration of " + label + "; the gate's parser lost its place");
        }
        String reason = markerFor(lexed.get(source), declaration.line(), CONSTANT_MARKER);
        if (reason != null && validReason(reason)) {
            return Map.of();
        }
        Map<String, String> out = new TreeMap<>();
        for (int i : constant) {
            String parameter = declaration.names().get(i - skip);
            out.put(
                    member.key() + "#" + parameter,
                    where + ":" + declaration.line() + " " + label + " parameter '" + parameter + "' is always "
                            + uses.values[i] + " (" + uses.calls + " production call" + (uses.calls == 1 ? "" : "s")
                            + ")"
                            + (reason != null
                                    ? "; its '// const-param:' marker needs a reason of two or more words that is not a stock phrase"
                                    : ""));
        }
        return out;
    }

    private static boolean validReason(String reason) {
        String trimmed = reason.strip();
        return trimmed.split("\\s+").length >= 2
                && !GENERIC_REASONS.contains(trimmed.toLowerCase().replaceAll("\\.+$", ""));
    }

    private static void read(List<Path> dirs, Map<String, ClassModel> into) throws IOException {
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var files = Files.walk(dir)) {
                for (Path file :
                        files.filter(path -> path.toString().endsWith(".class")).toList()) {
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
                throw new IllegalStateException("cannot load " + name + ", a supertype of "
                        + model.thisClass().asInternalName() + ": add its jar to --classpath");
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
                        case InvokeInstruction call ->
                            credit(
                                    call.owner().asInternalName(),
                                    call.name().stringValue(),
                                    call.type().stringValue(),
                                    false,
                                    origin,
                                    fromProduction);
                        case FieldInstruction access when isOwnWrite(access, origin) -> {}
                        case FieldInstruction access ->
                            credit(
                                    access.owner().asInternalName(),
                                    access.name().stringValue(),
                                    access.type().stringValue(),
                                    true,
                                    origin,
                                    fromProduction);
                        case InvokeDynamicInstruction dynamic ->
                            dynamic.invokedynamic()
                                    .bootstrap()
                                    .arguments()
                                    .forEach(arg -> creditHandle(arg, origin, fromProduction));
                        case ConstantInstruction.LoadConstantInstruction load ->
                            creditHandle(load.constantEntry(), origin, fromProduction);
                        default -> {}
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
            credit(
                    target.owner().asInternalName(),
                    target.name().stringValue(),
                    target.type().stringValue(),
                    field,
                    origin,
                    fromProduction);
        }
    }

    /** Credits every production class in the owner's hierarchy that declares the member: javac names the receiver's type, not the declarer. */
    private void credit(
            String owner, String name, String descriptor, boolean field, String origin, boolean fromProduction) {
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
        if (model.flags().has(AccessFlag.SYNTHETIC)
                || annotationsOf(model)
                        .anyMatch(annotation -> simpleName(annotation).equals("Mixin"))
                || isLocalOrAnonymous(model)) {
            return out;
        }
        boolean isEnum = model.flags().has(AccessFlag.ENUM);
        Set<String> components = new HashSet<>();
        model.findAttribute(Attributes.record())
                .ifPresent(record ->
                        record.components().forEach(c -> components.add(c.name().stringValue())));
        List<ClassModel> parents = supertypes(model);
        Set<String> bridgeTargets = bridgeTargets(model);
        for (var field : model.fields()) {
            var flags = field.flags();
            if (flags.has(AccessFlag.PRIVATE)
                    || flags.has(AccessFlag.SYNTHETIC)
                    || flags.has(AccessFlag.ENUM)
                    || field.findAttribute(Attributes.constantValue()).isPresent()
                    || hasSignificantAnnotation(field)
                    || (flags.has(AccessFlag.STATIC)
                            && flags.has(AccessFlag.FINAL)
                            && isRegisteredType(field.fieldType().stringValue()))) {
                continue;
            }
            out.add(new Member(
                    model, field.fieldName().stringValue(), field.fieldType().stringValue(), true));
        }
        for (MethodModel method : model.methods()) {
            String name = method.methodName().stringValue();
            String descriptor = method.methodType().stringValue();
            var flags = method.flags();
            if (flags.has(AccessFlag.PRIVATE)
                    || flags.has(AccessFlag.SYNTHETIC)
                    || flags.has(AccessFlag.BRIDGE)
                    || flags.has(AccessFlag.ABSTRACT)
                    || name.equals("<clinit>")
                    || hasSignificantAnnotation(method)) {
                continue;
            }
            MethodTypeDesc type = MethodTypeDesc.ofDescriptor(descriptor);
            if (isEnum && (name.equals("values") || name.equals("valueOf"))
                    || type.parameterCount() == 0 && components.contains(name)) {
                continue;
            }
            boolean inherited = !name.equals("<init>")
                    && !flags.has(AccessFlag.STATIC)
                    && (bridgeTargets.contains(name + parameters(descriptor))
                            || overrides(parents, name, parameters(descriptor)));
            if (!inherited) {
                out.add(new Member(model, name, descriptor, false));
            }
        }
        return out;
    }

    private static boolean isLocalOrAnonymous(ClassModel model) {
        String binary = model.thisClass().asInternalName();
        String last = binary.substring(binary.lastIndexOf('/') + 1);
        return Stream.of(last.split("\\$"))
                .skip(1)
                .anyMatch(part -> !part.isEmpty() && Character.isDigit(part.charAt(0)));
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
                    if (element instanceof InvokeInstruction call
                            && call.owner().asInternalName().equals(self)
                            && call.name()
                                    .stringValue()
                                    .equals(bridge.methodName().stringValue())) {
                        targets.add(call.name().stringValue()
                                + parameters(call.type().stringValue()));
                    }
                }
            });
        }
        return targets;
    }

    private static boolean overrides(List<ClassModel> parents, String name, String parameters) {
        return parents.stream()
                .flatMap(parent -> parent.methods().stream())
                .anyMatch(method -> method.methodName().stringValue().equals(name)
                        && !method.flags().has(AccessFlag.PRIVATE)
                        && !method.flags().has(AccessFlag.STATIC)
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
                || type != null
                        && Stream.concat(Stream.of(type), supertypes(type).stream())
                                .anyMatch(parent -> REGISTERED_ROOTS.contains(
                                        parent.thisClass().asInternalName()));
    }

    private static Stream<Annotation> annotationsOf(AttributedElement element) {
        return Stream.concat(
                element.findAttribute(Attributes.runtimeVisibleAnnotations()).stream()
                        .flatMap(a -> a.annotations().stream()),
                element.findAttribute(Attributes.runtimeInvisibleAnnotations()).stream()
                        .flatMap(a -> a.annotations().stream()));
    }

    private static boolean hasSignificantAnnotation(AttributedElement element) {
        return annotationsOf(element)
                .anyMatch(annotation -> !NOT_SIGNIFICANT_ANNOTATIONS.contains(simpleName(annotation)));
    }

    private static String simpleName(Annotation annotation) {
        String descriptor = annotation.className().stringValue();
        return descriptor.substring(
                Math.max(descriptor.lastIndexOf('/'), descriptor.lastIndexOf('$')) + 1, descriptor.length() - 1);
    }

    /** The finding line, or null when a valid marker on this exact declaration exempts it (or it is an implicit constructor). */
    private String report(Member member, Refs found) {
        String binary = member.owner().thisClass().asInternalName();
        String classPath = binary.substring(binary.lastIndexOf('/') + 1).replace('$', '.');
        String innermost = classPath.substring(classPath.lastIndexOf('.') + 1);
        boolean constructor = member.name().equals("<init>");
        String label = labelOf(member, classPath);
        String sourceName = member.owner()
                .findAttribute(Attributes.sourceFile())
                .map(a -> a.sourceFile().stringValue())
                .orElse(null);
        Path source = sourceName == null ? null : locate(binary, sourceName);
        String where = binary.substring(binary.lastIndexOf('/') + 1);
        int line = 0;
        boolean bare = false;
        if (source != null) {
            where = root.relativize(source.toAbsolutePath().normalize()).toString();
            Declaration declaration = declarationOf(source, classPath, constructor ? innermost : member.name(), member);
            if (declaration == null && !constructor) {
                throw new IllegalStateException(
                        where + ": cannot find the declaration of " + label + "; the gate's parser lost its place");
            }
            if (declaration != null) {
                line = declaration.line();
                String marker = markerFor(lexed.get(source), line, MARKER);
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

    private static String labelOf(Member member, String classPath) {
        return labelOf(member, classPath, 0);
    }

    /** The member as source names it, without the first {@code skip} descriptor parameters. */
    private static String labelOf(Member member, String classPath, int skip) {
        if (member.field()) {
            return classPath + "." + member.name();
        }
        String parameters = parameterNames(member.descriptor(), skip);
        return member.name().equals("<init>")
                ? "new " + classPath + "(" + parameters + ")"
                : classPath + "." + member.name() + "(" + parameters + ")";
    }

    private static String parameterNames(String descriptor, int skip) {
        return MethodTypeDesc.ofDescriptor(descriptor).parameterList().stream()
                .skip(skip)
                .map(ClassDesc::displayName)
                .collect(Collectors.joining(", "));
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
        return declarationOf(source, classPath, name, member, 0);
    }

    /** As above, ignoring the first {@code skip} descriptor parameters: the ones javac adds that the source does not declare. */
    private Declaration declarationOf(Path source, String classPath, String name, Member member, int skip) {
        List<Declaration> all = declarations.computeIfAbsent(source, path -> declarationsIn(path));
        List<String> wanted = member.field()
                ? List.of()
                : MethodTypeDesc.ofDescriptor(member.descriptor()).parameterList().stream()
                        .skip(skip)
                        .map(type ->
                                type.displayName().substring(type.displayName().lastIndexOf('$') + 1))
                        .toList();
        return all.stream()
                .filter(d -> d.classPath().equals(classPath)
                        && d.name().equals(name)
                        && d.field() == member.field()
                        && (member.field() || sameParameters(d.parameters(), wanted)))
                .findFirst()
                .orElse(null);
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

    /**
     * The text after {@code // test-seam:} on declaration {@code line} (1-based) or, when that line has none, on a line of the block
     * of comment-only lines directly above it.
     */
    private static String markerFor(Lexed source, int line, String marker) {
        String same = marker(source.comments().get(line - 1), marker);
        if (same != null) {
            return same;
        }
        for (int above = line - 2;
                above >= 0
                        && source.code().get(above).isBlank()
                        && source.comments().get(above) != null;
                above--) {
            String found = marker(source.comments().get(above), marker);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private List<Declaration> declarationsIn(Path source) {
        List<String> lines;
        try {
            lines = Files.readAllLines(source);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + source, e);
        }
        Lexed lexedSource = lex(source, lines);
        lexed.put(source, lexedSource);
        List<String> codeLines = lexedSource.code();
        List<Declaration> out = new ArrayList<>();
        ArrayDeque<String> classes = new ArrayDeque<>();
        ArrayDeque<Integer> classDepths = new ArrayDeque<>();
        String pending = null;
        int depth = 0;
        Pattern classDeclaration = Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)");
        for (int index = 0; index < lines.size(); index++) {
            String code = codeLines.get(index);
            Matcher declared = classDeclaration.matcher(code);
            boolean atMemberLevel = !classes.isEmpty() && depth == classDepths.peek();
            if (declared.find()) {
                pending = declared.group(1);
            } else if (atMemberLevel) {
                Declaration member =
                        memberDeclaration(codeLines, index, code, String.join(".", reversed(classes)), classes.peek());
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
            "public",
            "protected",
            "private",
            "static",
            "final",
            "abstract",
            "synchronized",
            "native",
            "default",
            "strictfp",
            "transient",
            "volatile");

    /** A method, constructor or field declared on {@code code}, or null: a type or modifiers before the name, never a statement. */
    private static Declaration memberDeclaration(
            List<String> codeLines, int index, String code, String classPath, String className) {
        int stop = endOfHead(code);
        if (stop < 0) {
            return null;
        }
        boolean method = code.charAt(stop) == '(';
        String head = code.substring(0, stop)
                .replaceAll("@\\w+(?:\\.\\w+)*(?:\\([^)]*\\))?", " ")
                .replaceAll("<[^<>]*(?:<[^<>]*>[^<>]*)*>", " ")
                .strip();
        if (head.isEmpty()) {
            return null;
        }
        String[] words = head.split("\\s+");
        String name = words[words.length - 1];
        if (!name.matches("\\w+")) {
            return null;
        }
        List<String> before = Stream.of(words)
                .limit(words.length - 1)
                .filter(word -> !MODIFIERS.contains(word))
                .toList();
        if (before.stream()
                .anyMatch(word -> Set.of("return", "throw", "new", "else", "case", "yield", "package", "import")
                        .contains(word))) {
            return null;
        }
        boolean constructor = method && before.isEmpty() && name.equals(className);
        if (before.isEmpty() && !constructor) {
            return null;
        }
        if (!method) {
            return new Declaration(index + 1, classPath, name, true, List.of(), List.of());
        }
        List<String> declared = rawParameters(codeLines, index, stop);
        return new Declaration(
                index + 1,
                classPath,
                name,
                false,
                declared.stream().map(TestOnlyGate::simpleType).toList(),
                declared.stream().map(TestOnlyGate::parameterName).toList());
    }

    /** The index of the first {@code (}, {@code =}, {@code ;} or {@code ,} outside angle brackets: a comma in a generic type is not the end of the head. */
    private static int endOfHead(String code) {
        int angle = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '<') {
                angle++;
            } else if (c == '>') {
                angle--;
            } else if (angle <= 0 && "(=;,".indexOf(c) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** Each parameter as written, annotations dropped: the text between the declaration's parentheses split at top-level commas. */
    private static List<String> rawParameters(List<String> codeLines, int index, int open) {
        StringBuilder text = new StringBuilder();
        int depth = 0;
        boolean started = false;
        outer:
        for (int i = index; i < codeLines.size(); i++) {
            String code = codeLines.get(i);
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
        String params = text.toString()
                .replaceAll("@\\w+(?:\\.\\w+)*(?:\\([^)]*\\))?", " ")
                .strip();
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
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }

    private static String parameterName(String parameter) {
        String stripped = parameter.strip();
        return stripped.substring(stripped.lastIndexOf(' ') + 1);
    }

    private static String simpleType(String parameter) {
        String stripped = parameter
                .replaceAll("<[^<>]*(?:<[^<>]*>[^<>]*)*>", "")
                .replaceAll("\\bfinal\\b", " ")
                .strip();
        String type = stripped.substring(0, Math.max(0, stripped.lastIndexOf(' ')))
                .strip()
                .replaceAll("\\s+", "");
        type = type.endsWith("...") ? type.substring(0, type.length() - 3) + "[]" : type;
        return type.substring(type.lastIndexOf('.') + 1);
    }

    /**
     * Each line as code only (and its trailing {@code //} comment, or null): comments removed (line, block and javadoc, across lines), string literals, text blocks and char literals
     * emptied. An apostrophe in a comment is not a char literal. A literal or comment the lexer cannot close fails loudly with the file
     * and line, so a parser that lost its place never goes on to report a bogus line.
     */
    private static Lexed lex(Path source, List<String> lines) {
        List<String> out = new ArrayList<>();
        List<String> comments = new ArrayList<>();
        boolean inBlock = false;
        boolean inTextBlock = false;
        for (int number = 1; number <= lines.size(); number++) {
            String line = lines.get(number - 1);
            StringBuilder code = new StringBuilder();
            String comment = null;
            int i = 0;
            while (i < line.length()) {
                if (inBlock) {
                    int end = line.indexOf("*/", i);
                    if (end < 0) {
                        i = line.length();
                    } else {
                        inBlock = false;
                        i = end + 2;
                        code.append(' ');
                    }
                } else if (inTextBlock) {
                    int end = endOfTextBlock(line, i);
                    if (end < 0) {
                        i = line.length();
                    } else {
                        inTextBlock = false;
                        i = end + 3;
                        code.append("\"\"");
                    }
                } else if (line.startsWith("//", i)) {
                    comment = line.substring(i);
                    break;
                } else if (line.startsWith("/*", i)) {
                    inBlock = true;
                    i += 2;
                } else if (line.startsWith("\"\"\"", i)) {
                    inTextBlock = true;
                    i += 3;
                } else if (line.charAt(i) == '"' || line.charAt(i) == '\'') {
                    char quote = line.charAt(i);
                    int end = endOfLiteral(line, i);
                    if (end >= line.length()) {
                        throw new IllegalStateException(source + ":" + number + ": unterminated " + quote
                                + " literal; the gate's parser lost its place");
                    }
                    code.append(quote).append(quote);
                    i = end + 1;
                } else {
                    code.append(line.charAt(i));
                    i++;
                }
            }
            out.add(code.toString());
            comments.add(comment);
        }
        if (inBlock || inTextBlock) {
            throw new IllegalStateException(source + ": unterminated " + (inBlock ? "block comment" : "text block")
                    + " at end of file; the gate's parser lost its place");
        }
        return new Lexed(out, comments);
    }

    /** The index of the closing triple quote at or after {@code from}, or -1. */
    private static int endOfTextBlock(String line, int from) {
        for (int i = from; i < line.length(); i++) {
            if (line.charAt(i) == '\\') {
                i++;
            } else if (line.startsWith("\"\"\"", i)) {
                return i;
            }
        }
        return -1;
    }

    private static int endOfLiteral(String line, int start) {
        char quote = line.charAt(start);
        int i = start + 1;
        while (i < line.length() && line.charAt(i) != quote) {
            i += line.charAt(i) == '\\' ? 2 : 1;
        }
        return i;
    }

    /** The text after {@code // test-seam:} in a line's {@code //} comment (null when it has none), or null when there is no marker. */
    private static String marker(String comment, String marker) {
        if (comment == null) {
            return null;
        }
        Matcher found =
                Pattern.compile("^//\\s*" + Pattern.quote(marker) + "(.*)$").matcher(comment);
        return found.find() ? found.group(1).trim() : null;
    }
}
