package cn.ltingyuanfang.simunlockmoudle;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import android.content.Context;

/**
 * 模块配置界面：根据设备卡槽数量自动显示单卡/双卡 PIN 输入框，分别加密写入 RemotePreferences
 * @author 猫薄荷与风
 * @version 1.1
 */
public class MainActivity extends Activity {

    private XposedService xposedService;
    private final List<EditText> pinInputs = new ArrayList<>();
    

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int slotCount = detectSlotCount();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        for (int slot = 0; slot < slotCount; slot++) {
            EditText input = new EditText(this);
            input.setHint("SIM " + (slot + 1) + " PIN 码（留空则不解锁）");
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            pinInputs.add(input);
            layout.addView(input);
        }

        Button saveBtn = new Button(this);
        saveBtn.setText("加密保存");
        saveBtn.setOnClickListener(v -> savePins());
        layout.addView(saveBtn);

        setContentView(layout);

        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(@NonNull XposedService service) {
                xposedService = service;
            }

            @Override
            public void onServiceDied(@NonNull XposedService service) {
                if (xposedService == service) {
                    xposedService = null;
                }
            }
        });
    }

    private int detectSlotCount() {
        TelephonyManager tm = getSystemService(TelephonyManager.class);
        if (tm == null) {
            return 1;
        }
        int count = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? tm.getActiveModemCount()
                : tm.getPhoneCount();
        return Math.max(1, count);
    }

    private void savePins() {
        if (xposedService == null) {
            Toast.makeText(this, "模块未激活", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            SharedPreferences prefs = xposedService.getRemotePreferences("sim_unlock");
            SharedPreferences.Editor editor = prefs.edit();
            for (int slot = 0; slot < pinInputs.size(); slot++) {
                String pin = pinInputs.get(slot).getText().toString().trim();
                String key = "pin_enc_slot_" + slot;
                if (pin.isEmpty()) {
                    editor.remove(key);
                } else {
                    editor.putString(key, PinCrypto.encrypt(pin));
                }
            }
             // 清理旧版单卡键
            editor.remove("pin_enc");
            editor.apply();
            Toast.makeText(this, "已加密保存", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }
}