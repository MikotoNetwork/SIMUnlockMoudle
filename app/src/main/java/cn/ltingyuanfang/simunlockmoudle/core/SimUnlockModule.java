package cn.ltingyuanfang.simunlockmoudle.core;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * LSPosed API 102 模块入口
 *
 * 配套 META-INF/xposed/ 配置：
 * - java_init.list: cn.ltingyuanfang.simunlockmoudle.core.SimUnlockModule
 * - scope.list:     com.android.systemui
 * - module.prop:    minApiVersion=102 / targetApiVersion=102 / staticScope=true
 */
public class SimUnlockModule extends XposedModule {

    private static final String TAG = "SimAutoUnlock";
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean(false);

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!"com.android.systemui".equals(param.getPackageName())) {
            return;
        }
        try {
            Class<?> monitorCls = Class.forName(
                    "com.android.keyguard.KeyguardUpdateMonitor", false, param.getClassLoader());
            Method handleSim = monitorCls.getDeclaredMethod(
                    "handleSimStateChange", int.class, int.class, int.class);

            hook(handleSim)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        int subId = (int) chain.getArg(0);
                        int state = (int) chain.getArg(2);
                        if (state == TelephonyManager.SIM_STATE_PIN_REQUIRED
                                && ATTEMPTED.compareAndSet(false, true)) {
                            unlockOnce(chain.getThisObject(), subId);
                        }
                        return result;
                    });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Hook failed", t);
        }
    }

    @SuppressLint("MissingPermission")
    private void unlockOnce(Object monitor, int subId) {
        SharedPreferences prefs = getRemotePreferences("sim_unlock");
        String encryptedPin = prefs.getString("pin_enc", null);
        if (encryptedPin == null || encryptedPin.isEmpty()) {
            return;
        }
        Context context = extractContext(monitor);
        if (context == null) {
            return;
        }
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String pin = PinCrypto.decrypt(encryptedPin);
                if (pin == null || pin.isEmpty()) {
                    return;
                }
                TelephonyManager tm = context.getSystemService(TelephonyManager.class);
                if (tm == null) {
                    return;
                }
                // supplyIccLockPin 与 PinResult 为 @SystemApi，通过反射调用以兼容标准 Android SDK 编译
                TelephonyManager subTm = tm.createForSubscriptionId(subId);
                Method supplyPin = TelephonyManager.class.getMethod("supplyIccLockPin", String.class);
                Object pinResult = getInvoker(supplyPin).invoke(subTm, pin);
                int resultType = (int) pinResult.getClass().getMethod("getResult").invoke(pinResult);
                // 仅记录解锁状态码，严禁在日志中输出 PIN 或密文
                log(Log.INFO, TAG, "SIM unlock attempted, resultType=" + resultType);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "SIM unlock error");
            }
        });
    }

    private static Context extractContext(Object monitor) {
        try {
            Field f = monitor.getClass().getDeclaredField("mContext");
            f.setAccessible(true);
            return (Context) f.get(monitor);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
