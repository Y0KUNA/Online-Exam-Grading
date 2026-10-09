package vn.edu.examgrading.common.security;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class HmacSignerTest {
    @Test void deterministicAndSecretBound() {
        assertEquals(HmacSigner.sign("a-secret", "request"), HmacSigner.sign("a-secret", "request"));
        assertNotEquals(HmacSigner.sign("a-secret", "request"), HmacSigner.sign("other-secret", "request"));
    }
}
