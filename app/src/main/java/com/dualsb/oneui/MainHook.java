package com.dualsb.oneui;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            if ("com.android.systemui".equals(lpp.packageName)) {
                SysUiHook.install(lpp);
            } else if ("android".equals(lpp.packageName)) {
                HeightHook.install(lpp.classLoader);   // system_server: chỉnh inset thanh trạng thái
            }
        } catch (Throwable t) {
            XposedBridge.log("[DualSB] handleLoadPackage: " + t);
        }
    }
}
