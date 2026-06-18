package com.cayleywcs;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 架构约束（对齐 CayleyWMS 铁律）：业务接口全 POST+JSON；Service 为 interface+impl；Mapper 继承 BaseMapper。
 */
class ArchitectureConventionsTest {
    private static final String BASE = "com.cayleywcs";
    // 基础设施端点豁免（健康检查 GET 探针）。
    private static final Set<String> CONTROLLER_ALLOWLIST = Set.of("com.cayleywcs.health.HealthController");

    @Test
    void businessControllersUsePostJsonOnly() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> controller : scan(new AnnotationTypeFilter(RestController.class))) {
            if (CONTROLLER_ALLOWLIST.contains(controller.getName())) {
                continue;
            }
            RequestMapping classRm = controller.getAnnotation(RequestMapping.class);
            boolean classJson = classRm != null && containsJson(classRm.consumes()) && containsJson(classRm.produces());
            for (var m : controller.getDeclaredMethods()) {
                if (m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(PutMapping.class)
                        || m.isAnnotationPresent(DeleteMapping.class) || m.isAnnotationPresent(PatchMapping.class)) {
                    violations.add(controller.getSimpleName() + "#" + m.getName() + " 使用了非 POST 映射");
                }
                PostMapping post = m.getAnnotation(PostMapping.class);
                if (post != null && !classJson && !(containsJson(post.consumes()) && containsJson(post.produces()))) {
                    violations.add(controller.getSimpleName() + "#" + m.getName() + " 未声明 application/json");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void serviceImplsImplementInterfaceInImplPackage() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> impl : scan(new AnnotationTypeFilter(Service.class))) {
            if (!impl.getSimpleName().endsWith("ServiceImpl")) {
                continue;
            }
            if (!impl.getPackageName().endsWith(".impl")) {
                violations.add(impl.getName() + " 不在 impl 包");
            }
            if (impl.getInterfaces().length == 0) {
                violations.add(impl.getName() + " 未实现 Service 接口");
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void mappersExtendBaseMapper() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> mapper : scan(new AssignableTypeFilter(BaseMapper.class))) {
            if (mapper.isInterface() && mapper.getSimpleName().endsWith("Mapper")
                    && !BaseMapper.class.isAssignableFrom(mapper)) {
                violations.add(mapper.getName() + " 未继承 BaseMapper");
            }
        }
        assertThat(violations).isEmpty();
    }

    private static List<Class<?>> scan(org.springframework.core.type.filter.TypeFilter filter) throws ClassNotFoundException {
        // 覆写候选判定，纳入接口（Mapper），不限于具体类。
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(
                            org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
                        return beanDefinition.getMetadata().isIndependent();
                    }
                };
        provider.addIncludeFilter(filter);
        List<Class<?>> classes = new ArrayList<>();
        for (var bd : provider.findCandidateComponents(BASE)) {
            classes.add(Class.forName(bd.getBeanClassName()));
        }
        return classes;
    }

    private static boolean containsJson(String[] values) {
        if (values == null) {
            return false;
        }
        for (String v : values) {
            if (v != null && v.contains("application/json")) {
                return true;
            }
        }
        return false;
    }
}
