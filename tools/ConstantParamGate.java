import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
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
import java.lang.classfile.instruction.NopInstruction;
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
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Constant-parameter gate (#912): fails when a parameter of a method or constructor of the mod (src/main, src/client) has at least one
 * production caller, every one of which passes the same literal there. A parameter nothing varies is a constant in disguise.
 *
 * <p>Run it as {@code java tools/ConstantParamGate.java ...}: a multi-file source launch (JEP 458) that reuses {@link TestOnlyGate}.
 *
 * <p>Callers are read from the compiled classes with a small stack simulation, so an argument that is a variable, a ternary or any other
 * expression counts as varying. A literal is a number, string, char, boolean, {@code null}, class literal or enum constant; an inlined
 * {@code static final} is indistinguishable from its value. A method handle or reference counts as a caller with unknown arguments.
 * Tests are not read: a parameter only tests vary still fails.
 *
 * <p>It passes what the test-only gate passes (overrides, annotated members, {@code @Mixin} classes, local and anonymous classes) and
 * a record's canonical constructor, a varargs parameter, the synthetic enum and outer-instance parameters, and a method with no
 * production caller. The marker is {@code // const-param: <reason>}, placed like {@code test-seam:}; its reason needs two or more words
 * that are not a stock phrase. A declaration the parser cannot locate is a finding at the class, which the marker on the class exempts.
 *
 * <p>Usage: java ConstantParamGate.java --production dir... --sources dir... [--classpath entry...] [--root dir] [--list]. --list
 * prints the key of every finding ({@code Class#member(parameters)#parameter}) and exits 0.
 */
public final class ConstantParamGate {
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

    /** Marks an enum constant's literal, followed by its class's internal name and the constant's name, so equal names in two packages differ. */
    private static final String ENUM = "enum:";

    private static final String BAD_REASON =
            "; its '// const-param:' marker needs a reason of two or more words that is not a stock phrase";
    private final TestOnlyGate gate;
    private final Map<String, ArgUse> argUses = new HashMap<>();

    private ConstantParamGate(TestOnlyGate gate) {
        this.gate = gate;
    }

    public static void main(String[] args) throws IOException {
        Map<String, List<String>> options = TestOnlyGate.options(args);
        for (String required : List.of("--production", "--sources")) {
            if (options.getOrDefault(required, List.of()).isEmpty()) {
                throw new IllegalArgumentException("ConstantParamGate: " + required + " is required");
            }
        }
        Path root = Path.of(options.getOrDefault("--root", List.of(".")).getFirst())
                .toAbsolutePath()
                .normalize();
        TestOnlyGate base =
                new TestOnlyGate(options.get("--sources").stream().map(Path::of).toList(), root);
        for (String entry : options.getOrDefault("--classpath", List.of())) {
            base.addClasspath(Path.of(entry));
        }
        Map<String, String> findings = new ConstantParamGate(base)
                .run(options.get("--production").stream().map(Path::of).toList());
        if (options.containsKey("--list")) {
            findings.keySet().forEach(System.out::println);
            return;
        }
        if (!findings.isEmpty()) {
            System.err.println(
                    "Parameters every production caller passes the same literal (" + findings.size() + ")."
                            + " Fix the method: inline the literal and drop the parameter, or make the callers vary."
                            + " A deliberate seam ends its declaration line, or sits on the comment line above, with"
                            + " '// const-param: <why callers must be able to vary it>' (two or more words, not a stock phrase).");
            findings.values().forEach(message -> System.err.println("  " + message));
            System.exit(1);
        }
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

        void clear() {
            stack.clear();
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
    Map<String, String> run(List<Path> productionDirs) throws IOException {
        gate.classDirs.addAll(productionDirs);
        TestOnlyGate.read(productionDirs, gate.production);
        for (ClassModel from : gate.production.values()) {
            from.methods().forEach(method -> method.code().ifPresent(this::simulate));
        }
        Map<String, String> findings = new TreeMap<>();
        for (ClassModel model : gate.production.values()) {
            for (MethodModel method : constantCandidates(model)) {
                ArgUse uses = argUses.get(TestOnlyGate.refKey(
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
                                                    ENUM + access.owner().asInternalName() + "."
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
                case StoreInstruction _, MonitorInstruction _ -> frame.pop();
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
                case NewPrimitiveArrayInstruction _, NewReferenceArrayInstruction _, TypeCheckInstruction _ -> {
                    frame.pop();
                    frame.push(Val.UNKNOWN);
                }
                case NewMultiArrayInstruction array -> {
                    for (int i = 0; i < array.dimensions(); i++) {
                        frame.pop();
                    }
                    frame.push(Val.UNKNOWN);
                }
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
                case LookupSwitchInstruction table -> jumpSwitch(frame, table.defaultTarget(), table.cases());
                case TableSwitchInstruction table -> jumpSwitch(frame, table.defaultTarget(), table.cases());
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
                case NopInstruction _, IncrementInstruction _ -> {}
                // Any other instruction has an effect on the stack this walk does not model: forget the stack, so later
                // pops are unknown.
                case Instruction ignored -> frame.clear();
                default -> {}
            }
        }
    }

    private static void jumpSwitch(Frame frame, Label defaultTarget, List<SwitchCase> cases) {
        frame.pop();
        frame.jump(defaultTarget);
        cases.stream().map(SwitchCase::target).forEach(frame::jump);
        frame.end();
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
            ClassModel model = gate.find(next);
            if (model == null) {
                continue;
            }
            if (model.methods().stream()
                    .anyMatch(method -> method.methodName().stringValue().equals(name)
                            && method.methodType().stringValue().equals(descriptor))) {
                if (gate.production.containsKey(next)) {
                    argUses.computeIfAbsent(TestOnlyGate.refKey(next, name, false, descriptor), key -> new ArgUse())
                            .add(args);
                }
            } else {
                TestOnlyGate.enqueueSupers(model, queue);
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
        ClassModel model = gate.find(owner);
        return model != null
                && model.fields().stream()
                        .anyMatch(field -> field.fieldName().stringValue().equals(name)
                                && field.flags().has(AccessFlag.ENUM));
    }

    /** A literal as a finding shows it: an enum constant by its simple class name. */
    private static String displayLiteral(String literal) {
        if (!literal.startsWith(ENUM)) {
            return literal;
        }
        String internal = literal.substring(ENUM.length());
        return internal.substring(internal.lastIndexOf('/') + 1).replace('$', '.');
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
                || TestOnlyGate.annotationsOf(model)
                        .anyMatch(annotation ->
                                TestOnlyGate.simpleName(annotation).equals("Mixin"))
                || TestOnlyGate.isLocalOrAnonymous(model)) {
            return out;
        }
        boolean isEnum = model.flags().has(AccessFlag.ENUM);
        String canonical = model.findAttribute(Attributes.record())
                .map(record -> record.components().stream()
                        .map(c -> c.descriptor().stringValue())
                        .collect(Collectors.joining("", "(", ")V")))
                .orElse(null);
        List<ClassModel> parents = gate.supertypes(model);
        Set<String> bridgeTargets = TestOnlyGate.bridgeTargets(model);
        for (MethodModel method : model.methods()) {
            String name = method.methodName().stringValue();
            String descriptor = method.methodType().stringValue();
            var flags = method.flags();
            if (flags.has(AccessFlag.SYNTHETIC)
                    || flags.has(AccessFlag.BRIDGE)
                    || flags.has(AccessFlag.ABSTRACT)
                    || name.equals("<clinit>")
                    || TestOnlyGate.hasSignificantAnnotation(method)
                    || isEnum && name.equals("valueOf")
                    || name.equals("<init>") && descriptor.equals(canonical)
                    || MethodTypeDesc.ofDescriptor(descriptor).parameterCount() <= syntheticParameters(model, name)) {
                continue;
            }
            boolean inherited = !name.equals("<init>")
                    && !flags.has(AccessFlag.STATIC)
                    && !flags.has(AccessFlag.PRIVATE)
                    && (bridgeTargets.contains(name + TestOnlyGate.parameters(descriptor))
                            || TestOnlyGate.overrides(parents, name, TestOnlyGate.parameters(descriptor)));
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
        TestOnlyGate.Member member = new TestOnlyGate.Member(model, name, descriptor, false);
        String label = TestOnlyGate.labelOf(member, classPath, skip);
        String sourceName = model.findAttribute(Attributes.sourceFile())
                .map(a -> a.sourceFile().stringValue())
                .orElse(null);
        Path source = sourceName == null ? null : gate.locate(binary, sourceName);
        if (source == null) {
            return Map.of();
        }
        String where = gate.root.relativize(source.toAbsolutePath().normalize()).toString();
        TestOnlyGate.Declaration declaration = gate.declarationOf(
                source,
                classPath,
                constructor ? classPath.substring(classPath.lastIndexOf('.') + 1) : name,
                member,
                skip);
        if (declaration == null || declaration.names().size() != count - skip) {
            return notLocated(member, label, where, source, classPath);
        }
        String reason = TestOnlyGate.markerFor(gate.lexed.get(source), declaration.line(), CONSTANT_MARKER);
        if (isValid(reason)) {
            return Map.of();
        }
        Map<String, String> out = new TreeMap<>();
        for (int i : constant) {
            String parameter = declaration.names().get(i - skip);
            out.put(
                    member.key() + "#" + parameter,
                    where + ":" + declaration.line() + " " + label + " parameter '" + parameter + "' is always "
                            + displayLiteral(uses.values[i]) + " (" + uses.calls + " production call"
                            + (uses.calls == 1 ? "" : "s")
                            + ")"
                            + (reason != null ? BAD_REASON : ""));
        }
        return out;
    }

    /** The parser could not find the declaration: one finding at the class line, which the marker on the class exempts. */
    private Map<String, String> notLocated(
            TestOnlyGate.Member member, String label, String where, Path source, String classPath) {
        int line = gate.classLine(source, classPath);
        String reason = TestOnlyGate.markerFor(gate.lexed.get(source), Math.max(line, 1), CONSTANT_MARKER);
        if (isValid(reason)) {
            return Map.of();
        }
        return Map.of(
                member.key() + "#?",
                where + ":" + Math.max(line, 1) + " declaration not located: " + label
                        + "; mark the class or simplify the declaration"
                        + (reason != null ? BAD_REASON : ""));
    }

    /** A marker's reason, or null when there is no marker: two or more words that are not a stock phrase. */
    private static boolean isValid(String reason) {
        if (reason == null) {
            return false;
        }
        String trimmed = reason.strip();
        return trimmed.split("\\s+").length >= 2
                && !GENERIC_REASONS.contains(trimmed.toLowerCase().replaceAll("\\.+$", ""));
    }
}
