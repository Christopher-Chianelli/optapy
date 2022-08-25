package org.optaplanner.python.translator.types.errors.io;

import java.util.List;

import org.optaplanner.python.translator.PythonLikeObject;
import org.optaplanner.python.translator.types.PythonLikeType;
import org.optaplanner.python.translator.types.errors.PythonBaseException;

/**
 * Raised when a buffer related operation cannot be performed.
 */
public class SystemExit extends PythonBaseException {
    final public static PythonLikeType SYSTEM_EXIT_TYPE =
            new PythonLikeType("SystemExit", SystemExit.class, List.of(PythonBaseException.BASE_EXCEPTION_TYPE)),
            $TYPE = SYSTEM_EXIT_TYPE;

    static {
        SYSTEM_EXIT_TYPE.setConstructor(
                ((positionalArguments, namedArguments) -> new SystemExit(SYSTEM_EXIT_TYPE, positionalArguments)));
    }

    public SystemExit(PythonLikeType type) {
        super(type);
    }

    public SystemExit(PythonLikeType type, List<PythonLikeObject> args) {
        super(type, args);
    }

    public SystemExit(PythonLikeType type, String message) {
        super(type, message);
    }
}
