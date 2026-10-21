package com.github.chargeduck.codecallmap.run;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 包名探测逻辑单元测试（纯算法部分，不需要 IDEA 运行时）。
 *
 * <p>覆盖三类：包名解析、应用根包回溯、冗余候选剔除。
 * 这些逻辑直接决定「插件能不能自己找对要插桩的包」，错了会静默失效，必须测到。
 */
class PackageProbeTest {

    // ---------- packageOf ----------

    @Test
    void packageOfExtractsPackage() {
        assertEquals("com.mycompany.order", PackageProbe.packageOf("com.mycompany.order.OrderApplication"));
        assertEquals("com.mycompany", PackageProbe.packageOf("com.mycompany.Boot"));
        assertEquals("", PackageProbe.packageOf("Boot"), "无包名时返回空串");
        assertEquals("com.A", PackageProbe.packageOf("com.A.B"));
    }

    // ---------- isLibraryPackage ----------

    @Test
    void libraryPackageDetection() {
        assertTrue(PackageProbe.isLibraryPackage("org.springframework.boot"));
        assertTrue(PackageProbe.isLibraryPackage("java.util"));
        assertTrue(PackageProbe.isLibraryPackage("jakarta.servlet"));
        assertTrue(PackageProbe.isLibraryPackage("com.fasterxml.jackson.core"));
        assertFalse(PackageProbe.isLibraryPackage("com.mycompany.order"));
        assertFalse(PackageProbe.isLibraryPackage("cn.acme"), "cn.acme 不是库包");
    }

    // ---------- 应用根包回溯（核心逻辑） ----------

    @Test
    void bootClassInRootPackageReturnsItselfAndParents() {
        // com.mycompany.order.OrderApplication → 根包应是 com.mycompany.order
        List<String> roots = PackageProbe.inferRootPackages("com.mycompany.order");
        assertTrue(roots.contains("com.mycompany.order"), "应包含 Main class 所在包，实际=" + roots);
        // 外层优先：com.mycompany 也应在候选里（更宽覆盖）
        assertTrue(roots.contains("com.mycompany"), "应向上回溯包含 com.mycompany，实际=" + roots);
        // 外层排最前
        assertEquals("com.mycompany", roots.get(0), "最外层应排最前，实际=" + roots);
        // 关键：绝不退化成 com（会命中 com.fasterxml 等全部第三方库）
        assertFalse(roots.contains("com"),
                "不得把 com 作为插桩包名，实际=" + roots);
    }

    @Test
    void bootClassUnderConfigPackageWalksUp() {
        // 常见写法：Main 放在 .config 子包下 → 应回溯到业务根包
        List<String> roots = PackageProbe.inferRootPackages("com.mycompany.order.config");
        assertTrue(roots.contains("com.mycompany.order"),
                "应跳过 config 子包上溯到 com.mycompany.order，实际=" + roots);
        assertTrue(roots.contains("com.mycompany"), "也应包含更外层，实际=" + roots);
        assertFalse(roots.contains("com"), "不得退化成 com，实际=" + roots);
    }

    @Test
    void deepSubPackageWalksUpToBusinessRoot() {
        List<String> roots = PackageProbe.inferRootPackages("com.mycompany.order.controller.internal");
        assertTrue(roots.contains("com.mycompany.order"), "应上溯到业务根包，实际=" + roots);
        assertTrue(roots.contains("com.mycompany"), "也应包含更外层，实际=" + roots);
        assertFalse(roots.contains("com"), "不得退化成 com，实际=" + roots);
    }

    @Test
    void subPackageMarkersAreRecognized() {
        assertTrue(PackageProbe.isNonRootMarker("config"));
        assertTrue(PackageProbe.isNonRootMarker("controller"));
        assertTrue(PackageProbe.isNonRootMarker("Service"), "大小写不敏感");
        assertTrue(PackageProbe.isNonRootMarker("mapper"));
        assertFalse(PackageProbe.isNonRootMarker("mycompany"));
        assertFalse(PackageProbe.isNonRootMarker("order"));
    }

    @Test
    void singleLevelPackageHandled() {
        List<String> roots = PackageProbe.inferRootPackages("app");
        assertTrue(roots.contains("app"), "单层包应返回自身，实际=" + roots);
    }

    @Test
    void twoSegmentPackageNeverTruncatedToTopLevel() {
        // com.acme 只有两段，不应被截成 com
        List<String> roots = PackageProbe.inferRootPackages("com.acme");
        assertEquals(List.of("com.acme"), roots, "两段包应原样返回，实际=" + roots);
    }

    // ---------- 冗余剔除 ----------

    @Test
    void removeRedundantDropsChildWhenParentPresent() {
        List<String> in = List.of("com.mycompany", "com.mycompany.order", "com.mycompany.order.web");
        List<String> out = PackageProbe.removeRedundant(in);
        assertEquals(List.of("com.mycompany"), out,
                "父包已覆盖子包，应只剩父包，实际=" + out);
    }

    @Test
    void removeRedundantKeepsUnrelated() {
        List<String> in = List.of("com.mycompany.order", "cn.acme.pay");
        List<String> out = PackageProbe.removeRedundant(in);
        assertEquals(2, out.size(), "不相关的两个包都应保留，实际=" + out);
        assertTrue(out.contains("com.mycompany.order"));
        assertTrue(out.contains("cn.acme.pay"));
    }

    @Test
    void removeRedundantDoesNotMergeSimilarPrefix() {
        // com.mycompany 与 com.mycompany2 不是父子关系（前缀相同但非包边界）
        List<String> in = List.of("com.mycompany", "com.mycompany2");
        List<String> out = PackageProbe.removeRedundant(in);
        assertEquals(2, out.size(), "com.mycompany2 不应被 com.mycompany 覆盖，实际=" + out);
    }

    // ---------- 规格解析 ----------

    @Test
    void parseSpecSplitsOnCommaSemicolonSpace() {
        assertEquals(List.of("com.a", "com.b", "com.c"),
                PackageProbe.parseSpec("com.a, com.b;com.c"));
    }

    @Test
    void parseSpecDeduplicatesAndIgnoresBlank() {
        assertEquals(List.of("com.a", "com.b"),
                PackageProbe.parseSpec("com.a, com.a, , com.b"));
        assertTrue(PackageProbe.parseSpec(null).isEmpty());
        assertTrue(PackageProbe.parseSpec("   ").isEmpty());
    }

    @Test
    void formatAsSpecProducesCommaJoined() {
        assertEquals("com.a,com.b", PackageProbe.formatAsSpec(List.of("com.a", "com.b")));
        assertEquals("", PackageProbe.formatAsSpec(List.of()));
    }

    // ---------- 端到端：模拟 Spring Boot 典型布局 ----------

    /**
     * 真实世界包名布局对照表：断言「最终传给 agent 的包名」。
     *
     * <p>这是本类最重要的测试——它把「各种常见项目结构」与「期望结果」钉死，
     * 一旦回溯算法被改动，这四条会立刻暴露回归。
     */
    @Test
    void realWorldLayoutsProduceExpectedFinalSpec() {
        assertFinalSpec("com.acme.shop", "com.acme");
        assertFinalSpec("com.acme.shop.config", "com.acme");
        assertFinalSpec("com.acme.shop.controller.web", "com.acme");
        assertFinalSpec("cn.tencent.cloud.pay", "cn.tencent");
        assertFinalSpec("io.github.me.svc", "io.github");
    }

    /**
     * 域名倒置 + 补 com 的写法：{@code cn.com.企业.项目}。
     *
     * <p>这是国内企业的常见写法。若按「固定保留两段」处理会得到 {@code cn.com} ——
     * 几乎所有中国公司共用这个前缀，等于没有限制，会命中海量第三方库。
     * 实测用户项目就踩了这个坑。
     */
    @Test
    void domainReversedWithExtraComKeepsThreeSegments() {
        assertFinalSpec("cn.com.acme", "cn.com.acme");
        assertFinalSpec("cn.com.acme.web", "cn.com.acme");
        assertFinalSpec("com.com.github.app", "com.com.github");
        assertFinalSpec("cn.net.foo.bar", "cn.net.foo");
    }

    @Test
    void neverResolvesToBareCountryComPair() {
        // cn.com / com.com 这类公共域名空间绝不能作为插桩包名
        for (String pkg : List.of("com.github.chargeduck", "cn.com.abc", "cn.net.x.y")) {
            List<String> finalSpec = PackageProbe.removeRedundant(
                    PackageProbe.inferRootPackages(pkg));
            for (String r : finalSpec) {
                assertFalse("cn.com".equals(r) || "com.com".equals(r),
                        "不得把公共域名空间当插桩包名: " + r + "（来自 " + pkg + "）");
            }
        }
    }

    /** 断言给定 Main class 所在包最终解析出的包名规格。 */
    private static void assertFinalSpec(String mainClassPackage, String expected) {
        List<String> roots = PackageProbe.inferRootPackages(mainClassPackage);
        List<String> finalSpec = PackageProbe.removeRedundant(roots);
        assertEquals(List.of(expected), finalSpec,
                "包 " + mainClassPackage + " 应解析为 " + expected + "，实际=" + finalSpec);
    }

    @Test
    void neverResolvesToBareTopLevelDomain() {
        // com / org / cn / io 这类只有一段的顶层域名绝不能被当作插桩包名，
        // 否则会命中第三方库（com.fasterxml、org.springframework...）导致数据不可用
        for (String pkg : List.of("com.acme.shop", "com.acme.shop.config",
                "org.myorg.app", "cn.tencent.pay", "io.github.me")) {
            List<String> roots = PackageProbe.inferRootPackages(pkg);
            for (String r : roots) {
                assertTrue(r.contains("."),
                        "候选包名不应是单段顶层域名: " + r + "（来自 " + pkg + "）");
            }
        }
    }

    @Test
    void springBootTypicalLayoutResolvesToRootPackage() {
        // Main class: com.acme.shop.ShopApplication
        String mainClass = "com.acme.shop.ShopApplication";
        String pkg = PackageProbe.packageOf(mainClass);
        List<String> roots = PackageProbe.inferRootPackages(pkg);
        List<String> finalSpec = PackageProbe.removeRedundant(roots);

        assertFalse(finalSpec.isEmpty(), "不应为空");
        assertEquals("com.acme", finalSpec.get(0),
                "最终应收敛到最外层业务根包，实际=" + finalSpec);
        // 校验它确实能覆盖 Main class 所在包
        assertTrue(pkg.startsWith(finalSpec.get(0)),
                "根包必须能覆盖 Main class 所在包");
    }
}
