
package cn.ltingyuanfang.simunlockmoudle.core;

import android.os.Build;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 基于设备硬件特征 + PBKDF2WithHmacSHA256 派生 AES-256-GCM 密钥的加解密工具
 * 密文格式：Base64(salt[16] + iv[12] + ciphertext)
 */
public final class PinCrypto {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final int ITERATIONS = 100_000;

    // 应用固定 UUID 盐值，与设备硬件特征共同参与派生
    private static final String APP_PEPPER = "c8e4b912-6f3a-4d7e-9a1b-5c2d8e4f7031";

    private PinCrypto() {}

    public static String encrypt(String plainPin) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        random.nextBytes(salt);
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(salt), new GCMParameterSpec(TAG_BITS, iv));
        byte[] cipherBytes = cipher.doFinal(plainPin.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[SALT_LEN + IV_LEN + cipherBytes.length];
        System.arraycopy(salt, 0, combined, 0, SALT_LEN);
        System.arraycopy(iv, 0, combined, SALT_LEN, IV_LEN);
        System.arraycopy(cipherBytes, 0, combined, SALT_LEN + IV_LEN, cipherBytes.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    public static String decrypt(String encoded) throws Exception {
        byte[] combined = Base64.decode(encoded, Base64.NO_WRAP);
        if (combined.length <= SALT_LEN + IV_LEN) {
            return null;
        }
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        byte[] cipherBytes = new byte[combined.length - SALT_LEN - IV_LEN];
        System.arraycopy(combined, 0, salt, 0, SALT_LEN);
        System.arraycopy(combined, SALT_LEN, iv, 0, IV_LEN);
        System.arraycopy(combined, SALT_LEN + IV_LEN, cipherBytes, 0, cipherBytes.length);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(salt), new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
    }

    private static SecretKeySpec deriveKey(byte[] salt) throws Exception {
        char[] seed = buildDeviceSeed();
        try {
            KeySpec spec = new PBEKeySpec(seed, salt, ITERATIONS, KEY_BITS);
            byte[] keyBytes = SecretKeyFactory.getInstance(KDF_ALGORITHM)
                    .generateSecret(spec)
                    .getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            java.util.Arrays.fill(seed, '\0');
        }
    }

    /**
     * 选用跨进程一致且不随系统 OTA 变化的硬件特征（避免使用按签名隔离的 ANDROID_ID）
     */
    private static char[] buildDeviceSeed() {
        String abis = Build.SUPPORTED_ABIS != null ? String.join(",", Build.SUPPORTED_ABIS) : "";
        String raw = String.join("|",
                APP_PEPPER,
                Build.MANUFACTURER,
                Build.BRAND,
                Build.MODEL,
                Build.DEVICE,
                Build.BOARD,
                Build.HARDWARE,
                Build.PRODUCT,
                abis);
        return raw.toCharArray();
    }
}