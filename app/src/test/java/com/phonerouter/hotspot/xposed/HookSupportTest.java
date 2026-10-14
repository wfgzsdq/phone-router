package com.phonerouter.hotspot.xposed;

import org.junit.Test;

import static com.phonerouter.hotspot.xposed.HookSupport.PrefixState.*;
import static org.junit.Assert.*;

public class HookSupportTest {
    private static final String PREFIX = "192.168.43.0/24";

    private static class Server {
        private final int mInterfaceType;
        private final Object mPrivateAddressCoordinator;
        Server(int type, Object coordinator) {
            mInterfaceType = type;
            mPrivateAddressCoordinator = coordinator;
        }
    }

    private static class LegacyCoordinator {
        private final boolean down, up;
        LegacyCoordinator(boolean down, boolean up) { this.down = down; this.up = up; }
        private boolean isDownstreamPrefixInUse(String prefix) { return down; }
        private boolean isConflictWithUpstream(String prefix) { return up; }
    }

    private static class ModernCoordinator {
        private final String conflict;
        ModernCoordinator(String conflict) { this.conflict = conflict; }
        private String getConflictPrefix(String prefix) { return conflict; }
        // A ROM overload must not be accidentally selected by reflection.
        private Object getConflictPrefix(int unrelated) { throw new AssertionError(); }
    }

    @Test public void onlyWifiIsEligible() throws Exception {
        assertTrue(HookSupport.isWifi(new Server(0, null)));
        for (int type = 1; type <= 6; type++) assertFalse(HookSupport.isWifi(new Server(type, null)));
        assertFalse(HookSupport.isWifi(new Object()));
    }

    @Test public void inheritedRomFieldsAreSupported() throws Exception {
        Server subclass = new Server(0, new ModernCoordinator(null)) { };
        assertTrue(HookSupport.isWifi(subclass));
        assertEquals(CLEAR, HookSupport.prefixState(subclass, PREFIX, 33));
    }

    @Test public void android11ChecksBothUpstreamAndDownstream() throws Exception {
        assertEquals(CLEAR, state(new LegacyCoordinator(false, false), 30));
        assertEquals(CONFLICT, state(new LegacyCoordinator(true, false), 30));
        assertEquals(CONFLICT, state(new LegacyCoordinator(false, true), 30));
        assertEquals(CONFLICT, state(new LegacyCoordinator(true, true), 30));
    }

    @Test public void modernCoordinatorChecksPrefixAndIgnoresWrongOverload() throws Exception {
        assertEquals(CLEAR, state(new ModernCoordinator(null), 33));
        assertEquals(CONFLICT, state(new ModernCoordinator(PREFIX), 34));
    }

    @Test public void inheritedCoordinatorMethodsAreSupported() throws Exception {
        assertEquals(CONFLICT, state(new ModernCoordinator(PREFIX) { }, 33));
    }

    @Test public void unknownCoordinatorDoesNotForceAnAddress() throws Exception {
        assertEquals(UNSUPPORTED, state(null, 33));
        assertEquals(UNSUPPORTED, state(new Object(), 33));
        Object partial = new Object() {
            @SuppressWarnings("unused") private boolean isConflictWithUpstream(String p) { return false; }
        };
        assertEquals(UNSUPPORTED, state(partial, 30));
    }

    @Test public void missingCoordinatorFallbackIsOnlyForAndroid15Plus() throws Exception {
        assertEquals(UNSUPPORTED, HookSupport.prefixState(new Object(), PREFIX, 34));
        assertEquals(UNCHECKED, HookSupport.prefixState(new Object(), PREFIX, 35));
    }

    @Test public void failedConflictQueryPropagatesToHookFallback() {
        Object broken = new Object() {
            @SuppressWarnings("unused") private Object getConflictPrefix(String p) {
                throw new IllegalStateException("ROM unavailable");
            }
        };
        assertThrows(Exception.class, () -> state(broken, 33));
    }

    @Test public void addressRotationOutsideConfigureIpv4IsNotIntercepted() {
        String cls = "android.net.ip.IpServer";
        assertTrue(HookSupport.isConfiguringIpv4(stack(cls, "configureIPv4"), cls));
        assertFalse(HookSupport.isConfiguringIpv4(stack(cls, "handleNewPrefixRequest"), cls));
        assertFalse(HookSupport.isConfiguringIpv4(stack("other.IpServer", "configureIPv4"), cls));
        assertFalse(HookSupport.isConfiguringIpv4(stack(cls, "configureIPv4Other"), cls));
    }

    @Test public void scopeExcludesUnrelatedApplicationsAndOverlays() {
        assertTrue(HookSupport.isTargetPackage("android"));
        assertTrue(HookSupport.isTargetPackage("com.android.networkstack.tethering.inprocess"));
        assertTrue(HookSupport.isTargetPackage("com.google.android.networkstack.tethering"));
        assertFalse(HookSupport.isTargetPackage("com.phonerouter.hotspot"));
        assertFalse(HookSupport.isTargetPackage("com.google.android.networkstack.tethering.overlay"));
    }

    private static HookSupport.PrefixState state(Object coordinator, int sdk) throws Exception {
        return HookSupport.prefixState(new Server(0, coordinator), PREFIX, sdk);
    }

    private static StackTraceElement[] stack(String cls, String method) {
        return new StackTraceElement[] { new StackTraceElement(cls, method, "IpServer.java", 1) };
    }
}
