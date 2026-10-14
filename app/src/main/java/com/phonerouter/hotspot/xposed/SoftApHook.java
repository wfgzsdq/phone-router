/*
 * Wi-Fi fixed-IP implementation adapted from XhyEax/SoftApHelper:
 * https://github.com/XhyEax/SoftApHelper/tree/a16231df89531d094d0f9e0e73d0259807c4cee0
 * Copyright (c) 2026 XhyEax — MIT License.
 * See assets/licenses/SoftApHelper-LICENSE.txt and THIRD_PARTY_NOTICES.md.
 */
package com.phonerouter.hotspot.xposed;

import android.net.IpPrefix;
import android.net.LinkAddress;
import android.os.Build;

import com.phonerouter.hotspot.Prefs;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Runs in system/Tethering processes selected in LSPosed, not in the app service. */
public final class SoftApHook implements IXposedHookLoadPackage {
    private static final String IP_SERVER = "android.net.ip.IpServer";
    private static final String TAG = "[PhoneRouter/SoftApHelper] ";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!HookSupport.isTargetPackage(load.packageName)) return;
        try {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                XposedHelpers.findAndHookMethod(IP_SERVER, load.classLoader,
                        "getRandomWifiIPv4Address", XC_MethodReplacement.returnConstant(Prefs.GATEWAY));
                log("Hook installed: getRandomWifiIPv4Address in " + load.processName);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                installModernHook(load);
            }
        } catch (Throwable error) {
            // A scope can legitimately lack IpServer; another Tethering scope may host it.
            log("Hook unavailable in " + load.processName + ": " + error);
        }
    }

    private void installModernHook(XC_LoadPackage.LoadPackageParam load) throws Exception {
        Class<?> server = load.classLoader.loadClass(IP_SERVER);
        // These String constructors are hidden SDK APIs, accessible in the hooked process.
        Constructor<LinkAddress> addressCtor = LinkAddress.class.getDeclaredConstructor(String.class);
        Constructor<IpPrefix> prefixCtor = IpPrefix.class.getDeclaredConstructor(String.class);
        addressCtor.setAccessible(true);
        prefixCtor.setAccessible(true);
        LinkAddress fixedAddress = addressCtor.newInstance(Prefs.GATEWAY + "/24");
        IpPrefix fixedPrefix = prefixCtor.newInstance(Prefs.GATEWAY + "/24");
        int count = 0;
        // Android 11: (); 12/13: (boolean); 14+: (int, boolean).
        // Hook all matching overloads instead of depending on declaration order.
        for (Method method : server.getDeclaredMethods()) {
            if (!method.getName().equals("requestIpv4Address")
                    || !LinkAddress.class.isAssignableFrom(method.getReturnType())) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!HookSupport.isWifi(param.thisObject)
                                || !HookSupport.isConfiguringIpv4(
                                        Thread.currentThread().getStackTrace(), IP_SERVER)) return;
                        HookSupport.PrefixState state = HookSupport.prefixState(
                                param.thisObject, fixedPrefix, Build.VERSION.SDK_INT);
                        if (state == HookSupport.PrefixState.CONFLICT
                                || state == HookSupport.PrefixState.UNSUPPORTED) {
                            log("Keep system address: " + state);
                            return;
                        }
                        if (state == HookSupport.PrefixState.UNCHECKED) {
                            log("Coordinator absent on Android 15+; using upstream fallback without conflict check");
                        }
                        param.setResult(fixedAddress);
                        log("Wi-Fi gateway selected: " + fixedAddress);
                    } catch (Throwable error) {
                        // Leave the original method intact if a ROM changes its internals.
                        log("Keep system address after hook error: " + error);
                    }
                }
            });
            count++;
        }
        log("Installed " + count + " requestIpv4Address hook(s) in " + load.processName);
    }

    private static void log(String message) {
        XposedBridge.log(TAG + message);
    }
}
