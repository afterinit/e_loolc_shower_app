package com.loolc.shower_app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 使用 Android Keystore 加密保存登录信息和浴室会话。 */
final class CredentialVault {
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String ALIAS = "loolc.shower.credentials.v1";
    private final SharedPreferences preferences;

    CredentialVault(Context context) {
        preferences = context.getSharedPreferences("credential_vault", Context.MODE_PRIVATE);
    }

    Session load() {
        String saved = preferences.getString("session", null);
        if (saved == null) return null;
        try {
            JSONObject payload = new JSONObject(saved);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            Base64.Decoder decoder = Base64.getDecoder();
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, decoder.decode(payload.getString("iv"))));
            JSONObject session = new JSONObject(new String(cipher.doFinal(decoder.decode(payload.getString("data"))),
                    StandardCharsets.UTF_8));
            return new Session(session.getString("account"), session.getString("password"), session.optString("token"));
        } catch (Exception exception) {
            clear();
            return null;
        }
    }

    boolean save(Session session) {
        try {
            JSONObject fields = new JSONObject().put("account", session.account).put("password", session.password)
                    .put("token", session.token);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(fields.toString().getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getEncoder();
            JSONObject payload = new JSONObject().put("iv", encoder.encodeToString(cipher.getIV()))
                    .put("data", encoder.encodeToString(encrypted));
            return preferences.edit().putString("session", payload.toString()).commit();
        } catch (Exception exception) {
            return false;
        }
    }

    boolean clear() {
        return preferences.edit().remove("session").commit();
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(PROVIDER);
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    static final class Session {
        final String account;
        final String password;
        final String token;

        Session(String account, String password, String token) {
            this.account = account;
            this.password = password;
            this.token = token;
        }

        Session withToken(String newToken) {
            return new Session(account, password, newToken);
        }

        boolean hasUsableToken() {
            if (token.isEmpty()) return false;
            long expiry = BathClient.tokenExpiry(token);
            return expiry == 0 || expiry > Instant.now().getEpochSecond();
        }
    }
}
