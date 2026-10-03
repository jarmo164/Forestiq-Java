package ee.forestiq.deal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DealLifecycleBoundaryTest {

    @Test
    void workProjectionExposesOnlyReadAndHealthOperations() {
        Set<String> methodNames = Arrays.stream(DealWorkService.class.getDeclaredMethods())
            .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
            .map(Method::getName)
            .collect(java.util.stream.Collectors.toSet());

        assertThat(methodNames).containsExactlyInAnyOrder("list", "get", "lossReasons", "lossReport");
    }

    @Test
    void commercialServiceHasNoParallelLossCommand() {
        assertThat(Arrays.stream(DealCommercialService.class.getDeclaredMethods())
            .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
            .map(Method::getName))
            .doesNotContain("lose");
    }
}
