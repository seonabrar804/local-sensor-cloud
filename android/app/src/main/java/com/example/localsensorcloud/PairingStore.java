package com.example.localsensorcloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class PairingStore {
    private static final String PREFERENCES = "secure_laptop_pairings";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String WRAPPING_KEY_ALIAS = "local_sensor_cloud_pairing_key";

    private PairingStore() { }

    static final class PairingMaterial {
        final byte[] applicationKey;
        final byte[] certificateDer;

        PairingMaterial(byte[] certificateDer, byte[] applicationKey) {
            this.certificateDer = certificateDer.clone();
            this.applicationKey = applicationKey.clone();
        }
    }

    static String normalizeEndpoint(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    static boolean has(Context context, String endpoint, String deviceId) {
        return load(context, endpoint, deviceId) != null;
    }

    static PairingMaterial load(Context context, String endpoint, String deviceId) {
        try {
            String prefix = entryPrefix(endpoint, deviceId);
            SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            String certificateValue = preferences.getString(prefix + "certificate", null);
            String encryptedKeyValue = preferences.getString(prefix + "key", null);
            String ivValue = preferences.getString(prefix + "iv", null);
            if (certificateValue == null || encryptedKeyValue == null || ivValue == null) return null;

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrappingKey(),
                    new GCMParameterSpec(128, Base64.decode(ivValue, Base64.NO_WRAP)));
            cipher.updateAAD(pairingIdentity(endpoint, deviceId));
            byte[] applicationKey = cipher.doFinal(Base64.decode(encryptedKeyValue, Base64.NO_WRAP));
            byte[] certificate = Base64.decode(certificateValue, Base64.NO_WRAP);
            if (applicationKey.length != 32 || certificate.length == 0) return null;
            return new PairingMaterial(certificate, applicationKey);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            return null;
        }
    }

    static void save(Context context, String endpoint, String deviceId, byte[] certificateDer, byte[] applicationKey)
            throws GeneralSecurityException {
        if (certificateDer == null || certificateDer.length == 0) {
            throw new GeneralSecurityException("The laptop certificate is missing");
        }
        if (applicationKey == null || applicationKey.length != 32) {
            throw new GeneralSecurityException("The phone encryption key must contain 32 bytes");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey());
        cipher.updateAAD(pairingIdentity(endpoint, deviceId));
        byte[] encryptedKey = cipher.doFinal(applicationKey);
        String prefix = entryPrefix(endpoint, deviceId);
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(prefix + "certificate", Base64.encodeToString(certificateDer, Base64.NO_WRAP))
                .putString(prefix + "key", Base64.encodeToString(encryptedKey, Base64.NO_WRAP))
                .putString(prefix + "iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }

    static void remove(Context context, String endpoint, String deviceId) {
        String prefix = entryPrefix(endpoint, deviceId);
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .remove(prefix + "certificate")
                .remove(prefix + "key")
                .remove(prefix + "iv")
                .apply();
    }

    private static SecretKey getOrCreateWrappingKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        try {
            keyStore.load(null);
        } catch (java.io.IOException error) {
            throw new GeneralSecurityException("Could not open Android secure storage", error);
        }
        SecretKey existing = (SecretKey) keyStore.getKey(WRAPPING_KEY_ALIAS, null);
        if (existing != null) return existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                WRAPPING_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static byte[] pairingIdentity(String endpoint, String deviceId) {
        return (normalizeEndpoint(endpoint) + "\n" + String.valueOf(deviceId).trim()).getBytes(StandardCharsets.UTF_8);
    }

    private static String entryPrefix(String endpoint, String deviceId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(pairingIdentity(endpoint, deviceId));
            StringBuilder value = new StringBuilder("pair_");
            for (int index = 0; index < 12; index++) value.append(String.format("%02x", digest[index] & 0xff));
            return value.append('_').toString();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }
}
