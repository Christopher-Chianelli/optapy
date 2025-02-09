package org.optaplanner.jpyinterpreter.opcodes.controlflow;

import java.util.Optional;

import org.optaplanner.jpyinterpreter.PythonBytecodeInstruction;
import org.optaplanner.jpyinterpreter.PythonVersion;
import org.optaplanner.jpyinterpreter.opcodes.Opcode;
import org.optaplanner.jpyinterpreter.util.JumpUtils;

public class ControlFlowOpcodes {
    public static Optional<Opcode> lookupOpcodeForInstruction(PythonBytecodeInstruction instruction,
            PythonVersion pythonVersion) {
        switch (instruction.opcode) {
            case RETURN_VALUE: {
                return Optional.of(new ReturnValueOpcode(instruction));
            }
            case JUMP_FORWARD: {
                return Optional
                        .of(new JumpAbsoluteOpcode(instruction, JumpUtils.getRelativeTarget(instruction, pythonVersion)));
            }
            case POP_JUMP_IF_TRUE: {
                return Optional
                        .of(new PopJumpIfTrueOpcode(instruction, JumpUtils.getAbsoluteTarget(instruction, pythonVersion)));
            }
            case POP_JUMP_IF_FALSE: {
                return Optional
                        .of(new PopJumpIfFalseOpcode(instruction, JumpUtils.getAbsoluteTarget(instruction, pythonVersion)));
            }
            case JUMP_IF_NOT_EXC_MATCH: {
                return Optional
                        .of(new JumpIfNotExcMatchOpcode(instruction, JumpUtils.getAbsoluteTarget(instruction, pythonVersion)));
            }
            case JUMP_IF_TRUE_OR_POP: {
                return Optional
                        .of(new JumpIfTrueOrPopOpcode(instruction, JumpUtils.getAbsoluteTarget(instruction, pythonVersion)));
            }
            case JUMP_IF_FALSE_OR_POP: {
                return Optional
                        .of(new JumpIfFalseOrPopOpcode(instruction, JumpUtils.getAbsoluteTarget(instruction, pythonVersion)));
            }
            case JUMP_ABSOLUTE: {
                return Optional
                        .of(new JumpAbsoluteOpcode(instruction, JumpUtils.getAbsoluteTarget(instruction, pythonVersion)));
            }
            case FOR_ITER: {
                return Optional.of(new ForIterOpcode(instruction, JumpUtils.getRelativeTarget(instruction, pythonVersion)));
            }
            default: {
                return Optional.empty();
            }
        }
    }
}
