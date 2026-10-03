package ee.forestiq.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import ee.forestiq.owner.OwnerDtos.OwnerStatusDefinition;

class OwnerStatusDefinitionCacheTest {
    private static final UUID ORG_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void organizationIdIsPartOfCacheKey() {
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager(OwnerStatusDefinitionCache.CACHE_NAME);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OwnerStatusDefinitionCache cache = cache(manager, registry);
        AtomicInteger aLoads = new AtomicInteger();
        AtomicInteger bLoads = new AtomicInteger();

        List<OwnerStatusDefinition> a = cache.get(ORG_A, () -> {
            aLoads.incrementAndGet();
            return List.of(new OwnerStatusDefinition("A_ONLY", "111111", 1, false));
        });
        List<OwnerStatusDefinition> b = cache.get(ORG_B, () -> {
            bLoads.incrementAndGet();
            return List.of(new OwnerStatusDefinition("B_ONLY", "222222", 2, false));
        });
        List<OwnerStatusDefinition> aAgain = cache.get(ORG_A, () -> {
            aLoads.incrementAndGet();
            return List.of(new OwnerStatusDefinition("WRONG", "ffffff", 99, false));
        });

        assertThat(a).extracting(OwnerStatusDefinition::id).containsExactly("A_ONLY");
        assertThat(b).extracting(OwnerStatusDefinition::id).containsExactly("B_ONLY");
        assertThat(aAgain).extracting(OwnerStatusDefinition::id).containsExactly("A_ONLY");
        assertThat(aLoads).hasValue(1);
        assertThat(bLoads).hasValue(1);
        assertThat(OwnerStatusDefinitionCache.key(ORG_A)).isNotEqualTo(OwnerStatusDefinitionCache.key(ORG_B));
        assertThat(registry.get("forestiq.cache.requests").tag("cache", OwnerStatusDefinitionCache.CACHE_NAME)
            .tag("result", "hit").counter().count()).isEqualTo(1.0d);
    }

    @Test
    void cacheReadFailureFallsBackToPostgreSqlLoader() {
        CacheManager manager = mock(CacheManager.class);
        Cache broken = mock(Cache.class);
        when(manager.getCache(OwnerStatusDefinitionCache.CACHE_NAME)).thenReturn(broken);
        when(broken.get(any(), eq(String.class))).thenThrow(new IllegalStateException("Redis unavailable"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OwnerStatusDefinitionCache cache = cache(manager, registry);
        AtomicInteger loads = new AtomicInteger();

        List<OwnerStatusDefinition> result = cache.get(ORG_A, () -> {
            loads.incrementAndGet();
            return List.of(new OwnerStatusDefinition("DB_VALUE", "abcdef", 5, true));
        });

        assertThat(result).extracting(OwnerStatusDefinition::id).containsExactly("DB_VALUE");
        assertThat(loads).hasValue(1);
        assertThat(registry.get("forestiq.cache.errors").tag("cache", OwnerStatusDefinitionCache.CACHE_NAME)
            .tag("operation", "access").counter().count()).isGreaterThanOrEqualTo(1.0d);
    }

    @Test
    void invalidationMakesNextReadLoadFreshValue() {
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager(OwnerStatusDefinitionCache.CACHE_NAME);
        OwnerStatusDefinitionCache cache = cache(manager, new SimpleMeterRegistry());

        assertThat(cache.get(ORG_A, () -> List.of(
            new OwnerStatusDefinition("STATUS", "111111", 1, false))).getFirst().colorHex())
            .isEqualTo("111111");

        cache.invalidate(ORG_A);

        assertThat(cache.get(ORG_A, () -> List.of(
            new OwnerStatusDefinition("STATUS", "222222", 2, false))).getFirst().colorHex())
            .isEqualTo("222222");
    }

    @SuppressWarnings("unchecked")
    private static OwnerStatusDefinitionCache cache(CacheManager manager, SimpleMeterRegistry registry) {
        ObjectProvider<CacheManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(manager);
        return new OwnerStatusDefinitionCache(provider, new ObjectMapper(), registry);
    }
}
