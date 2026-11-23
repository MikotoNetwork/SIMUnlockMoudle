package cn.ltingyuanfang.simunlockmoudle.core;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * LSPosed API 102 模块入口（通用方案，不依赖 ColorOS/AOSP 私有 Keyguard 类）
 *
 * 配套 META-INF/xposed/ 配置（需放在 src/main/resources/META-INF/xposed/）：
 * - java_init.list: cn.ltingyuanfang.simunlockmoudle.core.SimUnlockModule
 * - scope.list:     com.android.systemui
 * - module.prop:    minApiVersion=102 / targetApiVersion=102 / staticScope=true
 */
public class SimUnlockModule extends XposedModule {

    private static final String TAG = "SimAutoUnlock";
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean(false);
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!"com.android.systemui".equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            hook(onCreate)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object res = chain.proceed();
                        if (chain.getThisObject() instanceof Application app) {
                            initInSystemUi(app);
                        }
                        return res;
                    });
            log(Log.INFO, TAG, "Hooked SystemUI Application.onCreate");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Hook failed", t);
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void initInSystemUi(Context context) {
        IntentFilter filter = new IntentFilter("android.intent.action.SIM_STATE_CHANGED");
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if ("PIN_REQUIRED".equals(intent.getStringExtra("ss"))) {
                    int subId = intent.getIntExtra("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                    tryUnlockOnce(ctx, subId);
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }

        // 兜底：若广播在注册前已发出，开机 3 秒后主动检查一次卡槽状态
        EXECUTOR.schedule(() -> checkSlotsAndUnlock(context), 3, TimeUnit.SECONDS);
    }

    @SuppressLint("MissingPermission")
    private void checkSlotsAndUnlock(Context context) {
        if (ATTEMPTED.get()) {
            return;
        }
        TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        if (tm == null) {
            return;
        }
        int count = tm.getActiveModemCount();
        for (int slot = 0; slot < count; slot++) {
            if (tm.getSimState(slot) == TelephonyManager.SIM_STATE_PIN_REQUIRED) {
                int subId = resolveSubId(context, slot);
                tryUnlockOnce(context, subId);
                break;
            }
        }
    }

    @SuppressLint("MissingPermission")
    private int resolveSubId(Context context, int slotIndex) {
        SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
        if (sm != null) {
            SubscriptionInfo info = sm.getActiveSubscriptionInfoForSimSlotIndex(slotIndex);
            if (info != null) {
                return info.getSubscriptionId();
            }
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    @SuppressLint("MissingPermission")
    private void tryUnlockOnce(Context context, int subId) {
        if (!ATTEMPTED.compareAndSet(false, true)) {
            return;
        }
        SharedPreferences prefs = getRemotePreferences("sim_unlock");
        String encryptedPin = prefs.getString("pin_enc", null);
        if (encryptedPin == null || encryptedPin.isEmpty()) {
            log(Log.WARN, TAG, "No encrypted PIN configured");
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                String pin = PinCrypto.decrypt(encryptedPin);
                if (pin == null || pin.isEmpty()) {
                    return;
                }
                TelephonyManager tm = context.getSystemService(TelephonyManager.class);
                if (tm == null) {
                    return;
                }
                TelephonyManager targetTm = (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                        ? tm.createForSubscriptionId(subId)
                        : tm;
                Method supplyPin = TelephonyManager.class.getMethod("supplyIccLockPin", String.class);
                Object pinResult = getInvoker(supplyPin).invoke(targetTm, pin);
                int resultType = (int) pinResult.getClass().getMethod("getResult").invoke(pinResult);
                // 仅记录解锁状态码，严禁在日志中输出 PIN 或密文
                log(Log.INFO, TAG, "SIM unlock attempted, resultType=" + resultType);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "SIM unlock error", t);
            }
        });
    }
}