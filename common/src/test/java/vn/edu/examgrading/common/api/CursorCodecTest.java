package vn.edu.examgrading.common.api;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CursorCodecTest {
    @Test void encodesOpaqueCursorAndCapsLimit() {
        assertEquals("submission:42", CursorCodec.decode(CursorCodec.encode("submission:42")));
        assertEquals(50, CursorCodec.normalizeLimit(null));
        assertThrows(IllegalArgumentException.class, () -> CursorCodec.normalizeLimit(201));
    }
}
