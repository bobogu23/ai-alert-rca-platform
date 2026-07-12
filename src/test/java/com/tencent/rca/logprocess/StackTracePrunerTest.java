package com.tencent.rca.logprocess;

import static org.assertj.core.api.Assertions.assertThat;

import com.tencent.rca.config.RcaProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * StackTracePruner 单元测试: 校验业务帧保留与框架帧裁剪.
 */
class StackTracePrunerTest {

    @Test
    void shouldReturnEmptyForBlank() {
        StackTracePruner pruner = new StackTracePruner(propsWithBusiness("com.tencent"));
        assertThat(pruner.prune(null)).isEmpty();
        assertThat(pruner.prune("   ")).isEmpty();
    }

    @Test
    void shouldKeepBusinessFramesAndCollapseFrameworkFrames() {
        StackTracePruner pruner = new StackTracePruner(propsWithBusiness("com.tencent"));
        String stack = String.join("\n",
                "java.lang.NullPointerException: oops",
                "\tat com.tencent.order.OrderService.create(OrderService.java:20)",
                "\tat org.springframework.aop.Foo.invoke(Foo.java:1)",
                "\tat org.springframework.aop.Bar.invoke(Bar.java:2)",
                "\tat org.apache.tomcat.Baz.run(Baz.java:3)",
                "\tat com.tencent.web.Controller.handle(Controller.java:10)");

        String pruned = pruner.prune(stack);

        // 业务帧保留
        assertThat(pruned).contains("com.tencent.order.OrderService.create");
        assertThat(pruned).contains("com.tencent.web.Controller.handle");
        // 连续框架帧被折叠, 出现裁剪提示
        assertThat(pruned).contains("已裁剪");
        assertThat(pruned).doesNotContain("Bar.invoke");
    }

    private RcaProperties propsWithBusiness(String... prefixes) {
        RcaProperties properties = new RcaProperties();
        properties.getStackPruning().setBusinessPackages(List.of(prefixes));
        return properties;
    }
}
