package org.optaplanner.jpyinterpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.optaplanner.jpyinterpreter.opcodes.OpcodeWithoutSource;
import org.optaplanner.jpyinterpreter.types.PythonLikeType;

public class StackMetadata {
    public static final StackMetadata DEAD_CODE = new StackMetadata();

    public LocalVariableHelper localVariableHelper;
    public List<ValueSourceInfo> stackValueSources;

    public List<ValueSourceInfo> localVariableValueSources;

    public List<ValueSourceInfo> cellVariableValueSources;

    public boolean isDeadCode() {
        return this == DEAD_CODE;
    }

    public int getStackSize() {
        return stackValueSources.size();
    }

    /**
     * Returns the value source for the given stack index (stack index is how many
     * elements below TOS (i.e. 0 is TOS, 1 is TOS1)).
     *
     * @param index The stack index (how many elements below TOS)
     * @return The type at the given stack index
     */
    public ValueSourceInfo getValueSourceForStackIndex(int index) {
        return stackValueSources.get(stackValueSources.size() - index - 1);
    }

    /**
     * Returns the value sources up to (and not including) the given stack index (stack index is how many
     * elements below TOS (i.e. 0 is TOS, 1 is TOS1)).
     *
     * @param index The stack index (how many elements below TOS)
     * @return The value sources up to (and not including) the given stack index
     */
    public List<ValueSourceInfo> getValueSourcesUpToStackIndex(int index) {
        return stackValueSources.subList(stackValueSources.size() - index, stackValueSources.size());
    }

    /**
     * Returns the type at the given stack index (stack index is how many
     * elements below TOS (i.e. 0 is TOS, 1 is TOS1)).
     *
     * @param index The stack index (how many elements below TOS)
     * @return The type at the given stack index
     */
    public PythonLikeType getTypeAtStackIndex(int index) {
        ValueSourceInfo valueSourceInfo = stackValueSources.get(stackValueSources.size() - index - 1);
        if (valueSourceInfo != null) {
            return valueSourceInfo.valueType;
        }
        return null;
    }

    /**
     * Returns the value source for the local variable in slot {@code index}
     *
     * @param index The slot
     * @return The type for the local variable in the given slot
     */
    public ValueSourceInfo getLocalVariableValueSource(int index) {
        return localVariableValueSources.get(index);
    }

    /**
     * Returns the type for the local variable in slot {@code index}
     *
     * @param index The slot
     * @return The type for the local variable in the given slot
     */
    public PythonLikeType getLocalVariableType(int index) {
        ValueSourceInfo valueSourceInfo = localVariableValueSources.get(index);
        if (valueSourceInfo != null) {
            return valueSourceInfo.valueType;
        }
        return null;
    }

    /**
     * Returns the value source for the cell variable in slot {@code index}
     *
     * @param index The slot
     * @return The type for the cell variable in the given slot
     */
    public ValueSourceInfo getCellVariableValueSource(int index) {
        return cellVariableValueSources.get(index);
    }

    /**
     * Returns the type for the cell variable in slot {@code index}
     *
     * @param index The slot
     * @return The type for the cell variable in the given slot
     */
    public PythonLikeType getCellVariableType(int index) {
        ValueSourceInfo valueSourceInfo = cellVariableValueSources.get(index);
        if (valueSourceInfo != null) {
            return valueSourceInfo.valueType;
        }
        return null;
    }

    public PythonLikeType getTOSType() {
        return getTypeAtStackIndex(0);
    }

    public ValueSourceInfo getTOSValueSource() {
        return getValueSourceForStackIndex(0);
    }

    public StackMetadata copy() {
        StackMetadata out = new StackMetadata();
        out.localVariableHelper = localVariableHelper;
        out.stackValueSources = new ArrayList<>(stackValueSources);
        out.localVariableValueSources = new ArrayList<>(localVariableValueSources);
        out.cellVariableValueSources = new ArrayList<>(cellVariableValueSources);
        return out;
    }

    public StackMetadata unifyWith(StackMetadata other) {
        if (this == DEAD_CODE) {
            return other;
        }

        if (other == DEAD_CODE) {
            return this;
        }

        StackMetadata out = copy();
        if (out.stackValueSources.size() != other.stackValueSources.size() ||
                out.localVariableValueSources.size() != other.localVariableValueSources.size() ||
                out.cellVariableValueSources.size() != other.cellVariableValueSources.size()) {
            throw new IllegalArgumentException("Impossible State: Bytecode stack metadata size does not match when " +
                    "unifying (" + out.stackValueSources.stream()
                            .map(valueSource -> valueSource.valueType.toString()).collect(Collectors.joining(", ", "[", "]"))
                    +
                    ") with (" + other.stackValueSources.stream()
                            .map(valueSource -> valueSource.valueType.toString()).collect(Collectors.joining(", ", "[", "]"))
                    + ")");
        }

        for (int i = 0; i < out.stackValueSources.size(); i++) {
            out.stackValueSources.set(i, unifyTypes(stackValueSources.get(i), other.stackValueSources.get(i)));
        }

        for (int i = 0; i < out.localVariableValueSources.size(); i++) {
            out.localVariableValueSources.set(i,
                    unifyTypes(localVariableValueSources.get(i), other.localVariableValueSources.get(i)));
        }

        for (int i = 0; i < out.cellVariableValueSources.size(); i++) {
            out.cellVariableValueSources.set(i,
                    unifyTypes(cellVariableValueSources.get(i), other.cellVariableValueSources.get(i)));
        }

        return out;
    }

    private static ValueSourceInfo unifyTypes(ValueSourceInfo a, ValueSourceInfo b) {
        if (Objects.equals(a, b)) {
            return a;
        }

        if (a == null) { // a or b are null when they are deleted/are not set yet
            return b; // TODO: Optional type?
        }

        if (b == null) {
            return a;
        }

        return a.unifyWith(b);
    }

    /**
     * Return a new StackMetadata with {@code type} added as the new
     * TOS element.
     *
     * @param type The type to push to TOS
     */
    public StackMetadata push(ValueSourceInfo type) {
        StackMetadata out = copy();
        out.stackValueSources.add(type);
        return out;
    }

    public StackMetadata pushTemp(PythonLikeType type) {
        return push(ValueSourceInfo.of(new OpcodeWithoutSource(), type));
    }

    /**
     * Return a new StackMetadata with {@code types} added as the new
     * elements. The last element of {@code types} is TOS.
     *
     * @param types The types to push to TOS
     */
    public StackMetadata push(ValueSourceInfo... types) {
        StackMetadata out = copy();
        out.stackValueSources.addAll(Arrays.asList(types));
        return out;
    }

    public StackMetadata pushTemps(PythonLikeType... types) {
        StackMetadata out = copy();
        for (PythonLikeType type : types) {
            out.stackValueSources.add(ValueSourceInfo.of(new OpcodeWithoutSource(), type));
        }
        return out;
    }

    public StackMetadata insertTemp(int tosIndex, PythonLikeType type) {
        StackMetadata out = copy();
        out.stackValueSources.add(stackValueSources.size() - tosIndex,
                ValueSourceInfo.of(new OpcodeWithoutSource(), type));
        return out;
    }

    /**
     * Return a new StackMetadata with {@code types} as the stack;
     * The original stack is cleared.
     *
     * @param types The stack types.
     */
    public StackMetadata stack(ValueSourceInfo... types) {
        StackMetadata out = copy();
        out.stackValueSources.clear();
        out.stackValueSources.addAll(Arrays.asList(types));
        return out;
    }

    /**
     * Return a new StackMetadata with TOS popped
     */
    public StackMetadata pop() {
        StackMetadata out = copy();
        out.stackValueSources.remove(stackValueSources.size() - 1);
        return out;
    }

    /**
     * Return a new StackMetadata with the top {@code count} items popped.
     */
    public StackMetadata pop(int count) {
        StackMetadata out = copy();
        out.stackValueSources.subList(stackValueSources.size() - count, stackValueSources.size()).clear();
        return out;
    }

    /**
     * Return a new StackMetadata with the local variable in slot {@code index} type set to
     * {@code type}.
     */
    public StackMetadata setLocalVariableValueSource(int index, ValueSourceInfo type) {
        StackMetadata out = copy();
        out.localVariableValueSources.set(index, type);
        return out;
    }

    /**
     * Return a new StackMetadata with the given local types. Throws {@link IllegalArgumentException} if
     * types.length != localVariableTypes.size().
     */
    public StackMetadata locals(ValueSourceInfo... types) {
        if (types.length != localVariableValueSources.size()) {
            throw new IllegalArgumentException(
                    "Length mismatch: expected an array with {" + localVariableValueSources.size() + "} elements but got " +
                            "{" + Arrays.toString(types) + "}");
        }
        StackMetadata out = copy();
        for (int i = 0; i < types.length; i++) {
            out.localVariableValueSources.set(i, types[i]);
        }
        return out;
    }

    /**
     * Return a new StackMetadata with the cell variable in slot {@code index} type set to
     * {@code type}.
     */
    public StackMetadata setCellVariableValueSource(int index, ValueSourceInfo type) {
        StackMetadata out = copy();
        out.cellVariableValueSources.set(index, type);
        return out;
    }

    public String toString() {
        return "StackMetadata { stack: " + stackValueSources.toString() + "; locals: " + localVariableValueSources.toString() +
                "; cells: " + cellVariableValueSources.toString() + "; }";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (this == DEAD_CODE || o == DEAD_CODE) {
            return false; // this != o and one is DEAD_CODE
        }

        StackMetadata that = (StackMetadata) o;
        return stackValueSources.equals(that.stackValueSources)
                && localVariableValueSources.equals(that.localVariableValueSources)
                && cellVariableValueSources.equals(that.cellVariableValueSources);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stackValueSources, localVariableValueSources, cellVariableValueSources);
    }
}
