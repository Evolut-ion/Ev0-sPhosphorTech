package com.Ev0sMods.PhosphorTech.compat;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

public final class ReflectionCache {
    private static final ConcurrentHashMap<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();

    private ReflectionCache() {}

    public static void setField(Class<?> clazz, Object target, String fieldName, Object value) throws Exception {
        Field f = null;
        try {
            try {
                f = getField(clazz, fieldName);
            } catch (NoSuchFieldException e) {
                if (target != null) {
                    try {
                        f = getField(target.getClass(), fieldName);
                    } catch (NoSuchFieldException ignored) {
                        SyntheticFieldStore.put(target, fieldName, value);
                        return;
                    }
                } else {
                    return;
                }
            }

            if (f != null) {
                f.set(target, value);
            }
        } catch (IllegalAccessException iae) {
            throw iae;
        }
    }

    public static Field getField(Class<?> clazz, String fieldName) throws Exception {
        String key = clazz.getName() + "#" + fieldName;
        Field f = FIELD_CACHE.get(key);
        if (f != null) return f;

        Class<?> search = clazz;
        while (search != null) {
            try {
                Field nf = search.getDeclaredField(fieldName);
                nf.setAccessible(true);
                Field prev = FIELD_CACHE.putIfAbsent(key, nf);
                return (prev != null) ? prev : nf;
            } catch (NoSuchFieldException ignored) {
                search = search.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
