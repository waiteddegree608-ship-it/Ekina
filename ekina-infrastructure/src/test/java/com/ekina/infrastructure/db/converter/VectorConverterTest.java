package com.ekina.infrastructure.db.converter;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for VectorConverter
 */
public class VectorConverterTest {

    private final VectorConverter converter = new VectorConverter();

    @Test
    public void testConvertToDatabaseColumn() {
        float[] vector = {0.1f, -0.2f, 3.14f};
        Object dbData = converter.convertToDatabaseColumn(vector);
        assertTrue(dbData instanceof org.postgresql.util.PGobject);
        org.postgresql.util.PGobject pgObj = (org.postgresql.util.PGobject) dbData;
        assertEquals("vector", pgObj.getType());
        assertEquals("[0.1,-0.2,3.14]", pgObj.getValue());
    }

    @Test
    public void testConvertToDatabaseColumn_NullOrEmpty() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToDatabaseColumn(new float[0]));
    }

    @Test
    public void testConvertToEntityAttribute() {
        String dbData = "[0.1,-0.2,3.14]";
        float[] vector = converter.convertToEntityAttribute(dbData);
        assertNotNull(vector);
        assertEquals(3, vector.length);
        assertEquals(0.1f, vector[0], 0.0001f);
        assertEquals(-0.2f, vector[1], 0.0001f);
        assertEquals(3.14f, vector[2], 0.0001f);
    }

    @Test
    public void testConvertToEntityAttribute_NullOrEmpty() {
        assertNull(converter.convertToEntityAttribute(null));
        assertNull(converter.convertToEntityAttribute("   "));
        float[] empty = converter.convertToEntityAttribute("[]");
        assertEquals(0, empty.length);
    }
}
