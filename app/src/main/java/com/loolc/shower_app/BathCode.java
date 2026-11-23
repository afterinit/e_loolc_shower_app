package com.loolc.shower_app;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;

/** 读取有效洗澡码，等待服务端状态更新后确认换码。 */
final class BathCode {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int CHECKS = 6;
    private static final long CHECK_INTERVAL_MS = 500;

    final String value;
    final String generatedAt;
    final Instant expiresAt;
    final boolean enabled;
    final String minimumBalance;

    BathCode(String value, String generatedAt, boolean enabled, String minimumBalance) {
        this.value = value;
        this.generatedAt = generatedAt;
        this.enabled = enabled;
        this.minimumBalance = minimumBalance;
        this.expiresAt = expiration(generatedAt);
    }

    String maskedValue() {
        char[] masked = value.toCharArray();
        for (int i = 0, digits = 0; i < masked.length && digits < 5; i++) {
            if (Character.isDigit(masked[i])) {
                masked[i] = '*';
                digits++;
            }
        }
        return new String(masked);
    }

    static String formatCountdown(long remainingMillis) {
        long seconds = (Math.max(remainingMillis, 0) + 999) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    private boolean usableAt(Instant now) {
        return enabled && !value.isEmpty() && expiresAt != null && now.isBefore(expiresAt);
    }

    static BathCode obtain(Gateway gateway, boolean renew) throws Exception {
        BathCode before = gateway.read();
        if (!renew && before.usableAt(Instant.now())) return before;

        if (before.enabled) {
            setEnabled(gateway, false, before.minimumBalance);
            Thread.sleep(CHECK_INTERVAL_MS);
        }
        BathCode latest = setEnabled(gateway, true, before.minimumBalance);
        for (int attempt = 0; attempt < CHECKS; attempt++) {
            boolean changed = !latest.value.equals(before.value) || !latest.generatedAt.equals(before.generatedAt);
            if (latest.enabled && changed && !latest.value.isEmpty()
                    && (latest.expiresAt == null || latest.usableAt(Instant.now()))) {
                return latest;
            }
            if (attempt + 1 < CHECKS) {
                Thread.sleep(CHECK_INTERVAL_MS);
                latest = gateway.read();
            }
        }
        if (latest.value.isEmpty()) {
            String minimum = latest.minimumBalance.isEmpty() ? before.minimumBalance : latest.minimumBalance;
            String detail = minimum.isEmpty() ? "" : "，最低余额要求为 " + minimum + " 元";
            throw new Failure("浴室服务暂未生成洗澡码" + detail + "。请检查余额后重试。");
        }
        throw new Failure("浴室服务尚未返回有效的新码，请稍后重试。");
    }

    private static BathCode setEnabled(Gateway gateway, boolean enabled, String minimum) throws Exception {
        gateway.toggle(minimum);
        for (int attempt = 0; attempt < CHECKS; attempt++) {
            BathCode current = gateway.read();
            if (current.enabled == enabled) return current;
            if (attempt + 1 < CHECKS) Thread.sleep(CHECK_INTERVAL_MS);
        }
        throw new Failure("浴室服务的使用码开关尚未更新，请稍后重试。");
    }

    private static Instant expiration(String generatedAt) {
        try {
            return LocalDateTime.parse(generatedAt, FORMAT).plusHours(1).atZone(CAMPUS_ZONE).toInstant();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    interface Gateway {
        BathCode read() throws Exception;
        void toggle(String minimumBalance) throws Exception;
    }

    static class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }
}
