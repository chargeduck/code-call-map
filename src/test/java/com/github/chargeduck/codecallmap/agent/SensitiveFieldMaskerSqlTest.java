package com.github.chargeduck.codecallmap.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 脱敏测试。
 *
 * <p>SQL 的敏感信息藏在<b>字面量</b>里（身份证号、手机号、姓名），
 * 而不是字段名 —— 这和 JSON 的脱敏思路完全不同，必须单独验证。
 */
class SensitiveFieldMaskerSqlTest {

    @Test
    void masksStringLiterals() {
        String sql = "SELECT * FROM t_user WHERE name = '张三'";
        String masked = SensitiveFieldMasker.maskSql(sql);
        assertFalse(masked.contains("张三"), "字符串字面量必须被抹掉");
        assertTrue(masked.contains("'***'"), "保留引号形态，便于看出原本有值");
        assertTrue(masked.contains("t_user"), "表名应保留，这是定位问题的关键");
    }

    @Test
    void masksLongNumericLiterals() {
        // 身份证 / 手机号 / 订单号这类长数字
        String sql = "SELECT * FROM t_user WHERE id_card = 110101199001011234";
        String masked = SensitiveFieldMasker.maskSql(sql);
        assertFalse(masked.contains("110101199001011234"));
        assertTrue(masked.contains("t_user"));
    }

    @Test
    void keepsShortNumbersAndColumnNames() {
        // 短数字（如 status = 1）不敏感，保留便于排查
        String sql = "SELECT id, name FROM t_order WHERE status = 1 AND type = 2";
        assertEquals(sql, SensitiveFieldMasker.maskSql(sql),
                "短数字与列名不应被改动");
    }

    @Test
    void keepsPlaceholdersUntouched() {
        // PreparedStatement 的日志里 SQL 通常已是 ? 占位
        String sql = "INSERT INTO t_order (id, amount, remark) VALUES (?, ?, ?)";
        assertEquals(sql, SensitiveFieldMasker.maskSql(sql));
    }

    @Test
    void handlesEscapedQuotes() {
        // SQL 里 '' 表示一个单引号
        String sql = "SELECT * FROM t WHERE name = 'O''Brien'";
        String masked = SensitiveFieldMasker.maskSql(sql);
        assertFalse(masked.contains("Brien"));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertNull(SensitiveFieldMasker.maskSql(null));
        assertEquals("", SensitiveFieldMasker.maskSql(""));
    }
}
