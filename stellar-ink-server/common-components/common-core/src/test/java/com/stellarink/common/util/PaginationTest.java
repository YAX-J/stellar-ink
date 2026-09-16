package com.stellarink.common.util;

import com.stellarink.sharedmodel.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaginationTest {

    @Test
    void acceptsBoundaryValues() {
        assertDoesNotThrow(() -> Pagination.requireValid(1, 1));
        assertDoesNotThrow(() -> Pagination.requireValid(99, 100));
    }

    @Test
    void rejectsInvalidPageAndSize() {
        assertThrows(BusinessException.class, () -> Pagination.requireValid(0, 10));
        assertThrows(BusinessException.class, () -> Pagination.requireValid(1, 0));
        assertThrows(BusinessException.class, () -> Pagination.requireValid(1, 101));
        assertThrows(BusinessException.class, () -> Pagination.requireValid(null, 10));
    }
}
