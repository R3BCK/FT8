package com.bg7yoz.ft8cn.database;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Защищённое хранилище для конфиденциальных данных (пароли, токены, ключи API).
 * Использует Android Keystore и шифрование AES-256-GCM через Jetpack Security.
 *
 * [SECURITY] Пароли больше не хранятся в открытом виде в SQLite.
 * [COMPAT] Работает на Android 6.0+ (API 23). Для старых версий предусмотрен fallback.
 *
 * Автор: R3BCK + qwen.ai
 * Дата: 2026-05-14
 * Комментарии: русский язык, только ASCII в логике.
 */
public class SecureStorage {
    private static final String TAG = "SecureStorage";
    private static final String PREFS_NAME = "ft8cn_secure_prefs";
    private static final String MIGRATION_FLAG_KEY = "secure_migration_done_v1";

    private SharedPreferences encryptedPrefs;
    private boolean isAvailable;
    private static SecureStorage instance;

    // Приватный конструктор для Singleton
    private SecureStorage(Context context) {
        init(context);
    }

    public static synchronized SecureStorage getInstance(Context context) {
        if (instance == null) {
            instance = new SecureStorage(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * Инициализация зашифрованного хранилища.
     * Проверяет версию API и доступность Keystore.
     * При ошибке или API < 23 устанавливает флаг fallback.
     */
    private void init(Context context) {
        // [COMPAT] EncryptedSharedPreferences требует API 23+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "SecureStorage недоступен: API < 23. Используем fallback.");
            isAvailable = false;
            return;
        }

        try {
            // [SECURITY] MasterKey хранится в Android Keystore
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            encryptedPrefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            isAvailable = true;
            Log.d(TAG, "SecureStorage инициализирован успешно (AES-256-GCM)");
        } catch (Exception e) {
            Log.e(TAG, "Ошибка инициализации SecureStorage: " + e.getMessage(), e);
            isAvailable = false;
        }
    }

    /**
     * Проверка готовности защищённого хранилища.
     * @return true если шифрование активно, false если требуется fallback
     */
    public boolean isAvailable() {
        return isAvailable && encryptedPrefs != null;
    }

    /**
     * Сохранение конфиденциального значения.
     * Если хранилище недоступно, значение НЕ сохраняется (вызывающий код должен обработать fallback).
     * @param key Ключ предпочтения
     * @param value Значение в открытом виде для шифрования и сохранения
     */
    public void save(@NonNull String key, @NonNull String value) {
        if (!isAvailable()) {
            Log.w(TAG, "Вызов save() при недоступном хранилище. Ключ: " + key);
            return;
        }
        encryptedPrefs.edit().putString(key, value).apply();
    }

    /**
     * Получение конфиденциального значения.
     * @param key Ключ предпочтения
     * @param defaultValue Значение по умолчанию, если ключ не найден или хранилище недоступно
     * @return Расшифрованное значение или defaultValue
     */
    @NonNull
    public String get(@NonNull String key, @NonNull String defaultValue) {
        if (!isAvailable()) {
            Log.w(TAG, "Вызов get() при недоступном хранилище. Ключ: " + key);
            return defaultValue;
        }
        return encryptedPrefs.getString(key, defaultValue);
    }

    /**
     * Удаление значения из защищённого хранилища.
     * @param key Ключ предпочтения
     */
    public void remove(@NonNull String key) {
        if (!isAvailable()) return;
        encryptedPrefs.edit().remove(key).apply();
    }

    /**
     * Проверка, выполнена ли миграция паролей из SQLite в защищённое хранилище.
     * @return true если флаг миграции установлен
     */
    public boolean isMigrationDone() {
        if (!isAvailable()) return true; // На legacy устройствах миграция не требуется
        return encryptedPrefs.getBoolean(MIGRATION_FLAG_KEY, false);
    }

    /**
     * Установка флага успешной миграции.
     */
    public void markMigrationDone() {
        if (!isAvailable()) return;
        encryptedPrefs.edit().putBoolean(MIGRATION_FLAG_KEY, true).apply();
    }
}