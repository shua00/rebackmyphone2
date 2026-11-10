package com.example.devicefinder;

/**
 * 版本唯一来源。
 * BUILD_VERSION = 自动编译时间 + 手动功能标签
 * PROTOCOL_VERSION：协议变更才改。
 */
public final class BuildInfo {
    /** 协议版本：机器读。v1=旧 FIND_ME_MSG，v2=FLOAT/CHAT 新前缀 */
    public static final int PROTOCOL_VERSION = 2;

    /** 
     * ★【手动编号/标签】：
     * 每次发布大功能或重要补丁（如 H1b、Cell 等）时，在这里加上标记。
     * 如果只是日常改 Bug 不需要特殊标记，直接留空 "" 即可。
     * 例："-V4" 或 "-H1b" 或 "-CellApi"
     */
    private static final String CUSTOM_TAG = "-H1b"; // 当前演示留了一个标签

    /**
     * 构建版本：由 Gradle 自动生成时间戳 + 手动标签组合而成。
     * 格式：YYYYMMDD-HHmm-CUSTOM_TAG (例：20261110-1930-H1b)
     * (自动生成部分由 build.gradle 注入，无需在此修改)
     */
    public static final String BUILD_VERSION = BuildConfig.AUTO_BUILD_VERSION + CUSTOM_TAG;

    private BuildInfo() {}
}
