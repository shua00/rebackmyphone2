package com.example.devicefinder;

/**
 * 版本唯一来源。每次构建改 BUILD_VERSION；协议变更才改 PROTOCOL_VERSION。
 *
 * BUILD_VERSION 格式：YYYYMMDD-HHMM（构建时间，精确到分钟）
 * PROTOCOL_VERSION：
 *   v1 = 旧 FIND_ME_MSG 协议（APP 内不再新增，仅旧 APK 存在）
 *   v2 = FIND_ME_FLOAT / FIND_ME_CHAT 新前缀
 */
public final class BuildInfo {
    /** 协议版本：机器读。v1=旧 FIND_ME_MSG，v2=FLOAT/CHAT 新前缀 */
    public static final int PROTOCOL_VERSION = 2;

    /**
     * 构建版本：人读。每次构建改。
     * 【打包前修改这一行】例：20261105-1600
     */
    public static final String BUILD_VERSION = "20261105-1600";

    private BuildInfo() {}
}
