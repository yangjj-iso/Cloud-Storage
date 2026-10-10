package com.cloudchunk.knowledge;

import com.cloudchunk.infra.inbox.ConsumerEventKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerEventKeyTest {
    @Test void namespacesAreIndependentAndFitExistingInboxColumn() {
        String key=ConsumerEventKey.of("knowledge-v1","event-a");
        assertEquals(64,key.length());
        assertEquals(key,ConsumerEventKey.of("knowledge-v1","event-a"));
        assertNotEquals(key,ConsumerEventKey.of("summary-v1","event-a"));
        assertNotEquals(ConsumerEventKey.of("ab","c"),ConsumerEventKey.of("a","bc"));
        assertThrows(IllegalArgumentException.class,() -> ConsumerEventKey.of("knowledge-v1",null));
    }
}
