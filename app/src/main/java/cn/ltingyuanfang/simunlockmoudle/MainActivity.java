package cn.ltingyuanfang.simunlockmoudle;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;

import cn.ltingyuanfang.simunlockmoudle.core.PinCrypto;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块配置界面：通过 XposedService 将加密后的 PIN 写入 RemotePreferences
 */
public class MainActivity extends Activity {

    private XposedService xposedService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EditText pinInput = new EditText(this);
        pinInput.setHint("输入 SIM PIN 码");
        pinInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        Button saveBtn = new Button(this);
        saveBtn.setText("加密保存");
        saveBtn.setOnClickListener(v -> savePin(pinInput.getText().toString().trim()));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);
        layout.addView(pinInput);
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

    private void savePin(String pin) {
        if (xposedService == null || pin.isEmpty()) {
            Toast.makeText(this, "模块未激活或 PIN 为空", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String encrypted = PinCrypto.encrypt(pin);
            SharedPreferences prefs = xposedService.getRemotePreferences("sim_unlock");
            prefs.edit().putString("pin_enc", encrypted).apply();
            Toast.makeText(this, "已加密保存", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }
}
