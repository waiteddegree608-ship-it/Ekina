package com.ekina.common.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Thread-safe high-performance binary serializer utility utilizing Kryo.
 * Optimized for persisting long-running Agent session contexts and Workflow execution states.
 */
public class KryoSerializer {

    // ThreadLocal ensures that each thread uses its own non-thread-safe Kryo instance
    private static final ThreadLocal<Kryo> kryoThreadLocal = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        // Disabling registration requirement speeds up rapid prototyping of new domain classes
        kryo.setRegistrationRequired(false);
        return kryo;
    });

    /**
     * Serializes any Java object graph to a binary byte array.
     */
    public static byte[] serialize(Object obj) {
        if (obj == null) {
            return new byte[0];
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (Output output = new Output(bos)) {
            kryoThreadLocal.get().writeObject(output, obj);
            output.flush();
            return bos.toByteArray();
        }
    }

    /**
     * Deserializes binary byte arrays back to its original Java object class.
     */
    public static <T> T deserialize(byte[] bytes, Class<T> clazz) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        try (Input input = new Input(bis)) {
            return kryoThreadLocal.get().readObject(input, clazz);
        }
    }
}
