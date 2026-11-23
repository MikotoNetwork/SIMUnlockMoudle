/*
 * Copyright 2026 MikotoNetwork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.ltingyuanfang.simunlockmoudle;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import cn.ltingyuanfang.simunlockmoudle.core.PinCrypto;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块配置主界面（自适应深浅色模式、单双卡独立卡片、状态实时反馈）
 */
public class MainActivity extends Activity {

    private XposedService xposedService;
    private final List<SlotRow> slotRows = new ArrayList<>();

    private View statusDot;
    private TextView statusTitle;
    private TextView statusSubtitle;
    private Button saveButton;

    // 主题色板
    private boolean isDark;
    private int colorBg;
    private int colorCard;
    private int colorBorder;
    private int colorInputBg;
    private int colorTextPrimary;
    private int colorTextSecondary;
    private int colorAccent;
    private int colorSuccess;
    private int colorSuccessBg;
    private int colorWarning;
    private int colorDanger;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initThemeColors();
        setupSystemBars();
        setContentView(buildContentView());

        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(@NonNull XposedService service) {
                xposedService = service;
                runOnUiThread(() -> {
                    updateFrameworkStatus(true);
                    refreshSlotBadges();
                });
            }

            @Override
            public void onServiceDied(@NonNull XposedService service) {
                if (xposedService == service) {
                    xposedService = null;
                    runOnUiThread(() -> updateFrameworkStatus(false));
                }
            }
        });
    }

    private void initThemeColors() {
        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        isDark = (nightMode == Configuration.UI_MODE_NIGHT_YES);
        colorBg = isDark ? 0xFF0F1115 : 0xFFF4F6FA;
        colorCard = isDark ? 0xFF1A1D24 : 0xFFFFFFFF;
        colorBorder = isDark ? 0xFF2B303B : 0xFFE3E8F0;
        colorInputBg = isDark ? 0xFF12151B : 0xFFF7F9FC;
        colorTextPrimary = isDark ? 0xFFF1F5F9 : 0xFF0F172A;
        colorTextSecondary = isDark ? 0xFF94A3B8 : 0xFF64748B;
        colorAccent = isDark ? 0xFF38BDF8 : 0xFF1A73E8;
        colorSuccess = isDark ? 0xFF34D399 : 0xFF10B981;
        colorSuccessBg = isDark ? 0x2610B981 : 0x1A10B981;
        colorWarning = isDark ? 0xFFFBBF24 : 0xFFD97706;
        colorDanger = isDark ? 0xFFF87171 : 0xFFDC2626;
    }

    @SuppressWarnings("deprecation")
    private void setupSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(colorBg);
        window.setNavigationBarColor(colorBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(isDark ? 0 : mask, mask);
            }
        } else if (!isDark) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private View buildContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(colorBg);
        scrollView.setFillViewport(true);
        scrollView.setFitsSystemWindows(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));

        // 顶部标题区
        TextView title = new TextView(this);
        title.setText("SIM 自动解锁");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(colorTextPrimary);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("LSPosed API 102 · 本地硬件特征加密");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setTextColor(colorTextSecondary);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        root.addView(subtitle);

        // 模块激活状态卡片
        root.addView(buildStatusCard());

        // 卡槽配置卡片
        int slotCount = detectSlotCount();
        for (int slot = 0; slot < slotCount; slot++) {
            root.addView(buildSlotCard(slot));
        }

        // 主保存按钮
        saveButton = new Button(this);
        saveButton.setText("加密保存配置");
        saveButton.setAllCaps(false);
        saveButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        saveButton.setTypeface(Typeface.DEFAULT_BOLD);
        saveButton.setTextColor(Color.WHITE);
        saveButton.setBackground(createRippleDrawable(colorAccent, dp(14)));
        saveButton.setStateListAnimator(null);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        btnLp.topMargin = dp(20);
        saveButton.setLayoutParams(btnLp);
        saveButton.setOnClickListener(v -> savePins());
        root.addView(saveButton);

        // 底部安全提示
        TextView footer = new TextView(this);
        footer.setText("安全提示：PIN 码仅在本地通过 AES-256-GCM 加密存储且永不输出至日志；每次开机每个卡槽严格仅尝试解锁一次，以防误填触发 PUK 锁。");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        footer.setLineSpacing(dp(4), 1f);
        footer.setTextColor(colorTextSecondary);
        footer.setPadding(dp(4), dp(16), dp(4), 0);
        root.addView(footer);

        scrollView.addView(root);
        updateFrameworkStatus(false);
        return scrollView;
    }

    private View buildStatusCard() {
        LinearLayout card = createCardLayout();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);

        statusDot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.rightMargin = dp(12);
        statusDot.setLayoutParams(dotLp);
        card.addView(statusDot);

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);

        statusTitle = new TextView(this);
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusTitle.setTextColor(colorTextPrimary);
        textBox.addView(statusTitle);

        statusSubtitle = new TextView(this);
        statusSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusSubtitle.setTextColor(colorTextSecondary);
        statusSubtitle.setPadding(0, dp(2), 0, 0);
        textBox.addView(statusSubtitle);

        card.addView(textBox);
        return card;
    }

    private View buildSlotCard(int slotIndex) {
        LinearLayout card = createCardLayout();
        card.setOrientation(LinearLayout.VERTICAL);

        // 标题行：SIM X + 状态徽章 + 清除按钮
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText("SIM " + (slotIndex + 1));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(colorTextPrimary);
        header.addView(label);

        TextView badge = new TextView(this);
        badge.setText("未配置");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.leftMargin = dp(8);
        badge.setLayoutParams(badgeLp);
        header.addView(badge);

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView clearBtn = new TextView(this);
        clearBtn.setText("清除");
        clearBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        clearBtn.setTextColor(colorDanger);
        clearBtn.setPadding(dp(8), dp(4), dp(4), dp(4));
        clearBtn.setVisibility(View.GONE);
        clearBtn.setOnClickListener(v -> clearSlotPin(slotIndex));
        header.addView(clearBtn);

        card.addView(header);

        // 输入框容器（含显示/隐藏切换）
        LinearLayout inputBox = new LinearLayout(this);
        inputBox.setOrientation(LinearLayout.HORIZONTAL);
        inputBox.setGravity(Gravity.CENTER_VERTICAL);
        inputBox.setBackground(createRoundedDrawable(colorInputBg, colorBorder, dp(1), dp(12)));
        inputBox.setPadding(dp(14), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        boxLp.topMargin = dp(12);
        inputBox.setLayoutParams(boxLp);

        EditText input = new EditText(this);
        input.setHint("输入 4-8 位 PIN 码（留空跳过）");
        input.setHintTextColor(colorTextSecondary);
        input.setTextColor(colorTextPrimary);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        inputBox.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        TextView toggleVis = new TextView(this);
        toggleVis.setText("显示");
        toggleVis.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        toggleVis.setTextColor(colorAccent);
        toggleVis.setPadding(dp(8), dp(6), dp(6), dp(6));
        final boolean[] visible = {false};
        toggleVis.setOnClickListener(v -> {
            visible[0] = !visible[0];
            input.setInputType(visible[0]
                    ? InputType.TYPE_CLASS_NUMBER
                    : (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD));
            input.setSelection(input.getText().length());
            toggleVis.setText(visible[0] ? "隐藏" : "显示");
        });
        inputBox.addView(toggleVis);

        card.addView(inputBox);
        slotRows.add(new SlotRow(slotIndex, input, badge, clearBtn));
        styleBadge(badge, false);
        return card;
    }

    private void updateFrameworkStatus(boolean active) {
        if (active && xposedService != null) {
            statusDot.setBackground(createCircleDrawable(colorSuccess));
            statusTitle.setText("模块已激活");
            statusSubtitle.setText(xposedService.getFrameworkName()
                    + " · API " + xposedService.getApiVersion()
                    + " · 检测到 " + slotRows.size() + " 个卡槽");
            saveButton.setEnabled(true);
            saveButton.setAlpha(1f);
        } else {
            statusDot.setBackground(createCircleDrawable(colorWarning));
            statusTitle.setText("模块未激活");
            statusSubtitle.setText("请在 LSPosed 管理器中启用本模块并勾选「系统界面」");
            saveButton.setEnabled(false);
            saveButton.setAlpha(0.55f);
        }
    }

    private void refreshSlotBadges() {
        if (xposedService == null) {
            return;
        }
        SharedPreferences prefs = xposedService.getRemotePreferences("sim_unlock");
        for (SlotRow row : slotRows) {
            boolean configured = prefs.contains("pin_enc_slot_" + row.slotIndex)
                    || (row.slotIndex == 0 && prefs.contains("pin_enc"));
            row.configured = configured;
            styleBadge(row.badge, configured);
            row.clearBtn.setVisibility(configured ? View.VISIBLE : View.GONE);
            row.input.setHint(configured ? "已加密保护（输入新 PIN 可覆盖）" : "输入 4-8 位 PIN 码（留空跳过）");
        }
    }

    private void styleBadge(TextView badge, boolean configured) {
        if (configured) {
            badge.setText("已加密保存");
            badge.setTextColor(colorSuccess);
            badge.setBackground(createRoundedDrawable(colorSuccessBg, Color.TRANSPARENT, 0, dp(6)));
        } else {
            badge.setText("未配置");
            badge.setTextColor(colorTextSecondary);
            badge.setBackground(createRoundedDrawable(colorInputBg, colorBorder, dp(1), dp(6)));
        }
    }

    private void clearSlotPin(int slotIndex) {
        if (xposedService == null) {
            return;
        }
        SharedPreferences prefs = xposedService.getRemotePreferences("sim_unlock");
        SharedPreferences.Editor editor = prefs.edit().remove("pin_enc_slot_" + slotIndex);
        if (slotIndex == 0) {
            editor.remove("pin_enc");
        }
        editor.apply();
        for (SlotRow row : slotRows) {
            if (row.slotIndex == slotIndex) {
                row.input.setText("");
                break;
            }
        }
        refreshSlotBadges();
        Toast.makeText(this, "已清除 SIM " + (slotIndex + 1) + " 配置", Toast.LENGTH_SHORT).show();
    }

    private void savePins() {
        if (xposedService == null) {
            Toast.makeText(this, "模块未激活", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            SharedPreferences prefs = xposedService.getRemotePreferences("sim_unlock");
            SharedPreferences.Editor editor = prefs.edit();
            int updatedCount = 0;

            for (SlotRow row : slotRows) {
                String pin = row.input.getText().toString().trim();
                if (pin.isEmpty()) {
                    continue; // 留空保持原有配置，删除请点击右侧「清除」
                }
                if (pin.length() < 4 || pin.length() > 8) {
                    Toast.makeText(this, "SIM " + (row.slotIndex + 1) + " PIN 长度应为 4-8 位", Toast.LENGTH_SHORT).show();
                    return;
                }
                editor.putString("pin_enc_slot_" + row.slotIndex, PinCrypto.encrypt(pin));
                row.input.setText("");
                updatedCount++;
            }

            if (updatedCount == 0) {
                Toast.makeText(this, "未输入新的 PIN 码", Toast.LENGTH_SHORT).show();
                return;
            }

            editor.remove("pin_enc");
            editor.apply();
            refreshSlotBadges();
            Toast.makeText(this, "已加密保存（共更新 " + updatedCount + " 张卡）", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
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

    private LinearLayout createCardLayout() {
        LinearLayout card = new LinearLayout(this);
        card.setBackground(createRoundedDrawable(colorCard, colorBorder, dp(1), dp(16)));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private GradientDrawable createRoundedDrawable(int fillColor, int strokeColor, int strokeWidth, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(radiusPx);
        if (strokeWidth > 0) {
            d.setStroke(strokeWidth, strokeColor);
        }
        return d;
    }

    private GradientDrawable createCircleDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private RippleDrawable createRippleDrawable(int fillColor, int radiusPx) {
        GradientDrawable content = createRoundedDrawable(fillColor, Color.TRANSPARENT, 0, radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, content);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private static final class SlotRow {
        final int slotIndex;
        final EditText input;
        final TextView badge;
        final TextView clearBtn;
        boolean configured;

        SlotRow(int slotIndex, EditText input, TextView badge, TextView clearBtn) {
            this.slotIndex = slotIndex;
            this.input = input;
            this.badge = badge;
            this.clearBtn = clearBtn;
        }
    }
}
