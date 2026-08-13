package com.xiaohan.zentao.mcp.config;

import java.time.Instant;

/**
 * 保存在本地配置文件中的禅道登录信息。
 *
 * <p>密码按照项目约定以明文持久化，因此该记录不应写入日志，也不应提交到版本库。
 * {@code lastLogin} 由配置存储在成功登录后写入，旧配置或尚未保存的实例中可以为
 * {@code null}。</p>
 *
 * @param baseUrl 禅道站点根地址
 * @param account 登录账号
 * @param password 登录密码
 * @param lastLogin 最近一次成功登录并保存配置的 UTC 时间
 */
public record UserConfig(
    String baseUrl,
    String account,
    String password,
    Instant lastLogin
) {
    /**
     * 判断当前配置是否具备自动登录所需的三个凭据字段。
     *
     * @return 根地址、账号和密码均非空时返回 {@code true}
     */
    public boolean hasCredentials() {
        return !isBlank(baseUrl) && !isBlank(account) && !isBlank(password);
    }

    // 统一处理 null、空串和仅包含空白字符的配置值。
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
