package fu.se184491.loadmaster_be.service.cargo;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackingBarcodeAllocatorTest {

    @Test
    void usesPackageCodeWhenFree() {
        TrackingBarcodeAllocator allocator = new TrackingBarcodeAllocator(code -> false);

        assertEquals("PKG-001", allocator.allocate(" PKG-001 "));
    }

    @Test
    void blankCodeHasNoBarcode() {
        TrackingBarcodeAllocator allocator = new TrackingBarcodeAllocator(code -> false);

        assertNull(allocator.allocate(null));
        assertNull(allocator.allocate("  "));
    }

    @Test
    void codeTakenInDatabaseGetsSuffix() {
        Set<String> existing = Set.of("PKG-001", "PKG-001-2");
        TrackingBarcodeAllocator allocator = new TrackingBarcodeAllocator(existing::contains);

        assertEquals("PKG-001-3", allocator.allocate("PKG-001"));
    }

    @Test
    void codesInSameBatchStayUnique() {
        TrackingBarcodeAllocator allocator = new TrackingBarcodeAllocator(code -> false);

        assertEquals("ABC", allocator.allocate("ABC"));
        assertEquals("abc-2", allocator.allocate("abc"));
        assertEquals("ABC-3", allocator.allocate("ABC"));
    }

    @Test
    void longCodeIsCutToColumnLength() {
        String longCode = "X".repeat(120);
        TrackingBarcodeAllocator allocator = new TrackingBarcodeAllocator(code -> false);

        String first = allocator.allocate(longCode);
        String second = allocator.allocate(longCode);

        assertEquals(TrackingBarcodeAllocator.MAX_LENGTH, first.length());
        assertEquals(TrackingBarcodeAllocator.MAX_LENGTH, second.length());
        assertTrue(second.endsWith("-2"));
    }
}
