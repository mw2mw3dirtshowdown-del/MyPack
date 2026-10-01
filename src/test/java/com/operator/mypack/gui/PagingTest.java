package com.operator.mypack.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PagingTest {

    @Test
    @DisplayName("an empty list still has one (empty) page")
    void empty() {
        Paging p = Paging.of(0, 45, 0);
        assertEquals(1, p.pageCount());
        assertEquals(0, p.from());
        assertEquals(0, p.to());
        assertFalse(p.hasPrevious());
        assertFalse(p.hasNext());
    }

    @Test
    @DisplayName("exactly one full page does not create a second page")
    void exactlyOnePage() {
        Paging p = Paging.of(45, 45, 0);
        assertEquals(1, p.pageCount());
        assertEquals(45, p.to());
        assertFalse(p.hasNext());
    }

    @Test
    @DisplayName("one entry over the limit creates a second page holding that entry")
    void overflow() {
        Paging first = Paging.of(46, 45, 0);
        assertEquals(2, first.pageCount());
        assertEquals(0, first.from());
        assertEquals(45, first.to());
        assertTrue(first.hasNext());
        Paging second = Paging.of(46, 45, 1);
        assertEquals(45, second.from());
        assertEquals(46, second.to());
        assertTrue(second.hasPrevious());
        assertFalse(second.hasNext());
    }

    @Test
    @DisplayName("requested pages outside the range are clamped (e.g. after the list shrank)")
    void clamping() {
        assertEquals(0, Paging.of(100, 45, -3).index());
        assertEquals(2, Paging.of(100, 45, 99).index());
        assertEquals(90, Paging.of(100, 45, 99).from());
        assertEquals(100, Paging.of(100, 45, 99).to());
        assertEquals(0, Paging.of(3, 45, 5).from());
    }

    @Test
    @DisplayName("pages tile the list without gaps or overlap")
    void tiling() {
        int total = 137;
        int perPage = 28;
        Paging probe = Paging.of(total, perPage, 0);
        int covered = 0;
        int expectedFrom = 0;
        for (int page = 0; page < probe.pageCount(); page++) {
            Paging p = Paging.of(total, perPage, page);
            assertEquals(expectedFrom, p.from());
            covered += p.to() - p.from();
            expectedFrom = p.to();
        }
        assertEquals(total, covered);
    }

    @Test
    @DisplayName("a page size below 1 is treated as 1")
    void invalidPageSize() {
        assertEquals(3, Paging.of(3, 0, 0).pageCount());
    }
}
