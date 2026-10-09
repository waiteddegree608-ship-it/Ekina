package com.ekina.infrastructure.db.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.postgresql.util.PGobject;

/**
 * JPA Attribute Converter to translate between Java's float[] embedding array
 * and PostgreSQL vector representation using org.postgresql.util.PGobject.
 */
@Converter
public class VectorConverter implements AttributeConverter<float[], Object> {

    public String convertToVectorString(float[] attribute) {
        if (attribute == null || attribute.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < attribute.length; i++) {
            sb.append(attribute[i]);
            if (i < attribute.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public Object convertToDatabaseColumn(float[] attribute) {
        String vecStr = convertToVectorString(attribute);
        if (vecStr == null) {
            return null;
        }
        try {
            PGobject pgObj = new PGobject();
            pgObj.setType("vector");
            pgObj.setValue(vecStr);
            return pgObj;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public float[] convertToEntityAttribute(Object dbData) {
        if (dbData == null) {
            return null;
        }
        String clean;
        if (dbData instanceof PGobject) {
            clean = ((PGobject) dbData).getValue();
        } else {
            clean = dbData.toString();
        }

        if (clean == null || clean.trim().isEmpty()) {
            return null;
        }

        clean = clean.trim();
        if (clean.startsWith("[") && clean.endsWith("]")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        if (clean.isEmpty()) {
            return new float[0];
        }
        String[] parts = clean.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }
}
