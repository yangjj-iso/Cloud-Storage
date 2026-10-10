package com.cloudchunk.core.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HybridSearchServiceTest {
    @Test void cosineProtectsAgainstInvalidOrMismatchedVectors() {
        assertEquals(1.0,HybridSearchService.cosine(new double[]{1,0},new double[]{3,0}),0.000001);
        assertEquals(0,HybridSearchService.cosine(new double[]{1,0},new double[]{0,1}),0.000001);
        assertTrue(HybridSearchService.cosine(new double[]{1,0},new double[]{-1,0})<0);
        assertEquals(-1,HybridSearchService.cosine(new double[]{1},new double[]{1,0}));
        assertEquals(-1,HybridSearchService.cosine(new double[]{0,0},new double[]{1,1}));
        assertEquals(-1,HybridSearchService.cosine(new double[]{Double.NaN,1},new double[]{1,1}));
    }
}
