package org.optaplanner.python.translator.types.errors.warning;

import java.util.List;

import org.optaplanner.python.translator.PythonLikeObject;
import org.optaplanner.python.translator.types.PythonLikeType;

public class FutureWarning extends Warning {
    public final static PythonLikeType FUTURE_WARNING_TYPE =
            new PythonLikeType("FutureWarning", FutureWarning.class, List.of(WARNING_TYPE)),
            $TYPE = FUTURE_WARNING_TYPE;

    static {
        FUTURE_WARNING_TYPE.setConstructor(
                ((positionalArguments, namedArguments) -> new FutureWarning(FUTURE_WARNING_TYPE, positionalArguments)));
    }

    public FutureWarning() {
        super(FUTURE_WARNING_TYPE);
    }

    public FutureWarning(String message) {
        super(FUTURE_WARNING_TYPE, message);
    }

    public FutureWarning(PythonLikeType type, List<PythonLikeObject> args) {
        super(type, args);
    }

    public FutureWarning(PythonLikeType type) {
        super(type);
    }

    public FutureWarning(PythonLikeType type, String message) {
        super(type, message);
    }
}
