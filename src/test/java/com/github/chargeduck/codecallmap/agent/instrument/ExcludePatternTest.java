package com.github.chargeduck.codecallmap.agent.instrument;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排除规则的通配匹配测试。
 *
 * <h2>为什么这个测试必须存在</h2>
 * POJO 包排除靠的是 {@code *.domain.entity.} 这类通配规则。
 * 第一版实现里 {@code endsWith} 拿<b>整个类名</b>去比对 {@code .domain.entity}，
 * 而类名后面还有 {@code .SysUser} —— 结果一个类都匹配不到，
 * 却<b>不报错</b>，表现为"排除了但启动照样慢"，极难排查。
 *
 * <p>所以这里既验"该匹配的必须匹配上"，也验"不该匹配的不能误伤"，
 * 并且覆盖「直接子包」与「多层子包」两种结构。
 */
class ExcludePatternTest {

    @Test
    void globMatchesPackageSuffixAcrossProjects() {
        // 包路径以 domain.entity 结尾 → 匹配（类直接位于 entity 包下）
        assertTrue(InstrumentFilter.matchesGlob(
                "com.github.chargeduck.domain.entity.SysUser", "*.domain.entity."));
        assertTrue(InstrumentFilter.matchesGlob(
                "com.github.order.domain.entity.OrderItem", "*.domain.entity."));
        // 直接位于 dto 包下
        assertTrue(InstrumentFilter.matchesGlob(
                "com.github.chargeduck.domain.dto.UserDto", "*.domain.dto."));
        // 同项目里 domain.vo
        assertTrue(InstrumentFilter.matchesGlob(
                "com.github.chargeduck.domain.vo.UserVo", "*.domain.vo."));
    }

    @Test
    void globMatchesMultiLevelSubPackages() {
        // 末尾写 .* 才能覆盖 dto.query / dto.save / dto.save.evaldetail 这些子包
        assertTrue(InstrumentFilter.matchesGlob(
                "com.github.chargeduck.domain.dto.query.UserQuery", "*.domain.dto.*"));
        assertTrue(InstrumentFilter.matchesGlob(
                "com.github.chargeduck.domain.dto.save.UserSave", "*.domain.dto.*"));
        assertTrue(InstrumentFilter.matchesGlob(
                "com.github.chargeduck.domain.dto.save.evaldetail.Item", "*.domain.dto.*"));
        // 反例：末端是 * 时，dto 仍必须是完整包段
        assertFalse(InstrumentFilter.matchesGlob(
                "com.github.chargeduck.domain.dtoy.Foo", "*.domain.dto.*"));
    }

    @Test
    void globDoesNotMatchSimilarButDifferentPackage() {
        // 关键防误伤：末尾的点保证了 entity 必须是完整包段
        assertFalse(InstrumentFilter.matchesGlob(
                "com.github.entityutil.Helper", "*.domain.entity."));
        assertFalse(InstrumentFilter.matchesGlob(
                "com.github.chargeduck.domain.entityx.Foo", "*.domain.entity."));
        assertFalse(InstrumentFilter.matchesGlob(
                "com.github.chargeduck.service.impl.UserServiceImpl", "*.domain.entity."));
        // entity 在中间段、其后还有子包 → 点边界规则不命中
        assertFalse(InstrumentFilter.matchesGlob(
                "com.foo.entity.vo.Bar", "*.entity."));
    }

    @Test
    void globSupportsMultipleStarsAndPlainPrefix() {
        assertTrue(InstrumentFilter.matchesGlob("a.b.c.d.E", "a.*.c.*"));
        assertTrue(InstrumentFilter.matchesGlob("a.b.c.d.E", "a."));
        assertFalse(InstrumentFilter.matchesGlob("a.b.c.d.E", "b."));
        // 无通配符时是纯前缀语义（与旧行为一致）
        assertTrue(InstrumentFilter.matchesGlob("java.lang.String", "java."));
        assertFalse(InstrumentFilter.matchesGlob("javax.swing.JFrame", "java."));
    }

    @Test
    void isExcludedHandlesSlashFormAndMixedRules() {
        List<String> rules = Arrays.asList("java.", "*.domain.entity.", "org.springframework.");
        // ASM 内部名（斜杠分隔）也要能命中，否则插桩器传进来的名字会漏判
        assertTrue(InstrumentFilter.isExcluded("com/github/chargeduck/domain/entity/SysUser", rules));
        assertTrue(InstrumentFilter.isExcluded("java/lang/String", rules));
        assertFalse(InstrumentFilter.isExcluded("com/github/chargeduck/controller/UserController", rules));
    }

    @Test
    void bundledDefaultsExcludePojoPackages() throws Exception {
        // 内置默认配置必须覆盖典型的 POJO 包，否则新装用户会白付 70% 插桩开销
        InstrumentFilterConfig cfg = InstrumentFilterConfig.load(null);
        List<String> ex = cfg.exclude;
        assertTrue(InstrumentFilter.isExcluded(
                "com.github.chargeduck.domain.entity.SysUser", ex),
                "内置 default 未排除 domain.entity，插桩量会失控");
        assertTrue(InstrumentFilter.isExcluded(
                "com.github.chargeduck.domain.dto.query.UserQuery", ex),
                "内置 default 未排除 domain.dto 的子包");
        assertTrue(InstrumentFilter.isExcluded(
                "com.github.chargeduck.domain.vo.UserVo", ex),
                "内置 default 未排除 domain.vo");
        // 业务代码必须仍然被采集
        assertFalse(InstrumentFilter.isExcluded(
                "com.github.chargeduck.service.impl.UserServiceImpl", ex));
        assertFalse(InstrumentFilter.isExcluded(
                "com.github.chargeduck.controller.UserController", ex));
    }
}
