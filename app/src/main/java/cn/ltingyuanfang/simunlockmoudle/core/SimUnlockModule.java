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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedModule;

/**
 * LSPosed API 102 模块入口（支持单卡/双卡独立配置，每卡槽每次开机仅尝试解锁一次）
 * @author 猫薄荷与风
 * @see io.github.libxposed.api.XposedModule
 * @version 1.1
 */
public class SimUnlockModule extends XposedModule {

    private static final String TAG = "SimAutoUnlock";
    private static final Set<Integer> ATTEMPTED_SLOTS = ConcurrentHashMap.newKeySet();
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
                if (!"PIN_REQUIRED".equals(intent.getStringExtra("ss"))) {
                    return;
                }
                int subId = intent.getIntExtra("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                int slot = extractSlotIndex(intent, subId);
                if (slot >= 0) {
                    tryUnlockSlot(ctx, slot, subId);
                } else {
                    checkSlotsAndUnlock(ctx);
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }

        // 兜底：若广播在注册前已发出，开机 3 秒后遍历全部卡槽检查一次
        EXECUTOR.schedule(() -> checkSlotsAndUnlock(context), 3, TimeUnit.SECONDS);
    }

    private int extractSlotIndex(Intent intent, int subId) {
        int slot = intent.getIntExtra("android.telephony.extra.SLOT_INDEX", -1);
        if (slot < 0) {
            slot = intent.getIntExtra("phone", -1);
        }
        if (slot < 0) {
            slot = intent.getIntExtra("slot", -1);
        }
        if (slot < 0 && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            slot = SubscriptionManager.getSlotIndex(subId);
        }
        return slot;
    }

    @SuppressLint("MissingPermission")
    private void checkSlotsAndUnlock(Context context) {
        TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        if (tm == null) {
            return;
        }
        int count = getSlotCount(tm);
        for (int slot = 0; slot < count; slot++) {
            if (tm.getSimState(slot) == TelephonyManager.SIM_STATE_PIN_REQUIRED) {
                int subId = resolveSubId(context, slot);
                tryUnlockSlot(context, slot, subId);
            }
        }
    }

    private int getSlotCount(TelephonyManager tm) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? tm.getActiveModemCount()
                : tm.getPhoneCount();
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
    private void tryUnlockSlot(Context context, int slotIndex, int subId) {
        // 每个卡槽每次开机仅允许尝试一次
        if (!ATTEMPTED_SLOTS.add(slotIndex)) {
            return;
        }
        SharedPreferences prefs = getRemotePreferences("sim_unlock");
        String encryptedPin = prefs.getString("pin_enc_slot_" + slotIndex, null);
        // 兼容旧版单卡键名
        if ((encryptedPin == null || encryptedPin.isEmpty()) && slotIndex == 0) {
            encryptedPin = prefs.getString("pin_enc", null);
        }
        if (encryptedPin == null || encryptedPin.isEmpty()) {
            log(Log.INFO, TAG, "SIM slot " + slotIndex + " skipped (no PIN configured)");
            return;
        }
        final String cipherText = encryptedPin;
        EXECUTOR.execute(() -> {
            try {
                String pin = PinCrypto.decrypt(cipherText);
                if (pin == null || pin.isEmpty()) {
                    return;
                }
                TelephonyManager tm = context.getSystemService(TelephonyManager.class);
                if (tm == null) {
                    return;
                }
                int resolvedSubId = (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                        ? subId
                        : resolveSubId(context, slotIndex);
                TelephonyManager targetTm = (resolvedSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                        ? tm.createForSubscriptionId(resolvedSubId)
                        : tm;
                Method supplyPin = TelephonyManager.class.getMethod("supplyIccLockPin", String.class);
                Object pinResult = getInvoker(supplyPin).invoke(targetTm, pin);
                int resultType = (int) pinResult.getClass().getMethod("getResult").invoke(pinResult);
                // 仅记录卡槽索引与状态码，严禁在日志中输出 PIN 或密文
                log(Log.INFO, TAG, "SIM slot " + slotIndex + " unlock attempted, resultType=" + resultType);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "SIM slot " + slotIndex + " unlock error", t);
            }
        });
    }
}