package org.optaplanner.python.translator.types.datetime;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

public class PythonTimeTest {
    @Test
    public void testIsoFormat() {
        PythonTime pythonTime = new PythonTime(LocalTime.of(1, 30, 45));
        assertThat(pythonTime.isoformat()).isEqualTo("01:30:45");
    }
}
