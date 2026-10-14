/*
 * Adapted from SoftApHelper MainHook / ReflectUtils / StackUtils.
 * Copyright (c) 2026 XhyEax — MIT License.
 * See assets/licenses/SoftApHelper-LICENSE.txt and THIRD_PARTY_NOTICES.md.
 */
package com.phonerouter.hotspot.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reflection and eligibility checks kept independent of Android for host-side tests. */
final class HookSupport {
    enum PrefixState { CLEAR, CONFLICT, UNSUPPORTED, UNCHECKED }

    private HookSupport() { }

    static boolean isTargetPackage(String name) {
        return "android".equals(name)
                || "com.android.networkstack.tethering".equals(name)
                || "com.android.networkstack.tethering.inprocess".equals(name)
                || "com.google.android.networkstack.tethering".equals(name)
                || "com.google.android.networkstack.tethering.inprocess".equals(name);
    }

    static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    static boolean isWifi(Object server) throws IllegalAccessException {
        Field type = findField(server.getClass(), "mInterfaceType");
        // Never assume Wi-Fi when a ROM's internal interface type is unknown.
        return type != null && type.getInt(server) == 0;
    }

    static boolean isConfiguringIpv4(StackTraceElement[] stack, String className) {
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().equals(className)
                    && frame.getMethodName().equals("configureIPv4")) return true;
        }
        return false;
    }

    private static Method prefixMethod(Class<?> type, String name, Object prefix) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (method.getName().equals(name) && args.length == 1
                        && args[0].isInstance(prefix)) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    static PrefixState prefixState(Object server, Object prefix, int sdk) throws Exception {
        Field field = findField(server.getClass(), "mPrivateAddressCoordinator");
        // SoftApHelper's Android 15+ path: the coordinator field may no longer exist.
        if (field == null) return sdk >= 35 ? PrefixState.UNCHECKED : PrefixState.UNSUPPORTED;
        Object coordinator = field.get(server);
        if (coordinator == null) return PrefixState.UNSUPPORTED;
        Class<?> type = coordinator.getClass();
        Method conflict = prefixMethod(type, "getConflictPrefix", prefix);
        if (conflict != null) {
            return conflict.invoke(coordinator, prefix) != null ? PrefixState.CONFLICT : PrefixState.CLEAR;
        }
        // Android 11 uses separate upstream and downstream checks.
        Method downstream = prefixMethod(type, "isDownstreamPrefixInUse", prefix);
        Method upstream = prefixMethod(type, "isConflictWithUpstream", prefix);
        if (downstream == null || upstream == null) return PrefixState.UNSUPPORTED;
        Object down = downstream.invoke(coordinator, prefix);
        Object up = upstream.invoke(coordinator, prefix);
        if (!(down instanceof Boolean) || !(up instanceof Boolean)) return PrefixState.UNSUPPORTED;
        return (Boolean) down || (Boolean) up ? PrefixState.CONFLICT : PrefixState.CLEAR;
    }
}
