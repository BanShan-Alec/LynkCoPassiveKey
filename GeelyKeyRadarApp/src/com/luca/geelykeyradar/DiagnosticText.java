package com.luca.geelykeyradar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 日志写入和导出共用的脱敏规则，不保留车辆完整地址、名称尾号或凭证内容。 */
final class DiagnosticText {
    private static final Pattern MAC = Pattern.compile("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])");
    private static final Pattern VEHICLE = Pattern.compile("(?i)GeelyVehicle[0-9a-z?]+|geely-vin-[0-9a-z]+|LynkCo[0-9a-z?]+|lynkco-vin-[0-9a-z]+|VIN尾号\\s*[0-9a-z]+|(?<![0-9a-z])[A-HJ-NPR-Z0-9]{17}(?![0-9a-z])");
    private static final Pattern SECRET = Pattern.compile("(?i)((?:access_token|refresh_token|authorization|password|secret|credential|token)\\s*[=:]\\s*)(?:Bearer\\s+)?[^\\s,|]+");
    private static final Pattern LEGACY_IDENTITY = Pattern.compile(
            "((?:已加载本车身份|已绑定本车特征|已更新本车蓝牙特征|扫到本车特征广播，已自动锁定"
                    + "|已从系统 GATT 连接识别本车蓝牙|已自动识别本车蓝牙|已从系统 BLE 连接事件自动识别本车"
                    + "|捕获系统 BLE GATT 车机地址|捕获车机 BLE 新地址|从 ACL 连接自动锁定本车"
                    + "|车机底层 ACL 已连接|车机底层 ACL 已断开):\\s*)([^\\r\\n]+)");
    private final String salt;

    /** 使用仅保存在本机的随机盐，让同一地址跨进程可关联但无法直接还原。 */
    DiagnosticText(String salt) {
        this.salt = salt;
    }

    /** 返回短指纹；不将原始蓝牙地址或通知标识写入诊断包。 */
    String fingerprint(String value) {
        if (value == null || value.isEmpty()) return "none";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((salt + "|" + value.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 6; index++) result.append(String.format(Locale.ROOT, "%02x", bytes[index] & 0xff));
            return result.toString();
        } catch (Exception unavailable) {
            return "redacted";
        }
    }

    /** 将信息约束为一行并脱敏，避免异常消息或旧日志带出车辆身份。 */
    String sanitize(String value) {
        if (value == null) return "";
        String result = value.length() > 8192 ? value.substring(0, 8192) + " [truncated]" : value;
        result = result.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        result = redactLegacyIdentity(result);
        result = replaceIdentifiers(MAC, result, "addr#");
        result = replaceIdentifiers(VEHICLE, result, "vehicle#");
        return SECRET.matcher(result).replaceAll("$1[redacted]");
    }

    /** 仅记录异常类型和有限堆栈位置，省略可能含账号、地址或令牌的异常正文。 */
    static String errorSummary(Throwable error) {
        if (error == null) return "error=unknown";
        StringBuilder result = new StringBuilder("error=").append(error.getClass().getSimpleName());
        StackTraceElement[] frames = error.getStackTrace();
        for (int index = 0; index < Math.min(4, frames.length); index++) {
            result.append(index == 0 ? " at=" : ";").append(frames[index].getClassName())
                    .append('.').append(frames[index].getMethodName()).append(':').append(frames[index].getLineNumber());
        }
        if (error.getCause() != null) result.append(" cause=").append(error.getCause().getClass().getSimpleName());
        return result.toString();
    }

    /** 将所有匹配到的身份替换为稳定本机指纹。 */
    private String replaceIdentifiers(Pattern pattern, String value, String prefix) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(result, Matcher.quoteReplacement(prefix + fingerprint(matcher.group())));
        matcher.appendTail(result);
        return result.toString();
    }

    /** 旧版本直接输出自定义车名时，按身份事件脱敏整段详情；新版结构化指纹保持可检索。 */
    private String redactLegacyIdentity(String value) {
        Matcher matcher = LEGACY_IDENTITY.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String detail = matcher.group(2);
            String replacement = detail.contains("addr_id=") && detail.contains("name_id=")
                    ? matcher.group() : matcher.group(1) + "vehicle#" + fingerprint(detail);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
