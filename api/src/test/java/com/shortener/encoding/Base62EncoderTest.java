package com.shortener.encoding;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class Base62EncoderTest {

    @Test
    void encode_250000_returns4Chars() {
        Base62Encoder encoder = new Base62Encoder("any-secret");
        assertEquals(4, encoder.encode(250000).length());
    }

    @Test
    void decode_encode_roundtrip() {
        Base62Encoder encoder = new Base62Encoder("roundtrip-secret");
        Random rng = new Random(42);
        for (int i = 0; i < 1000; i++) {
            long id = (long)(rng.nextDouble() * 1_000_000_000L) + 1;
            assertEquals(id, encoder.decode(encoder.encode(id)),
                    "roundtrip failed for id=" + id);
        }
    }

    @Test
    void differentKeys_produceDifferentOutputs() {
        Base62Encoder enc1 = new Base62Encoder("secret-one");
        Base62Encoder enc2 = new Base62Encoder("secret-two");
        assertNotEquals(enc1.encode(12345), enc2.encode(12345));
    }

    @Test
    void sameKey_producesDeterministicOutput() {
        Base62Encoder enc1 = new Base62Encoder("same-secret");
        Base62Encoder enc2 = new Base62Encoder("same-secret");
        assertEquals(enc1.encode(12345), enc2.encode(12345));
    }
}
