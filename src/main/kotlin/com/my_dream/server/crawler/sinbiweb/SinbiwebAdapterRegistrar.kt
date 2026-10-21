package com.my_dream.server.crawler.sinbiweb

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.stereotype.Component

/**
 * [SinbiwebSites.ALL] 의 매장마다 [SinbiwebAdapter] 빈을 하나씩 만든다 (아키텍처 D41).
 *
 * **왜 `@Bean` 을 손으로 안 다나.** 수집기·조회 API·지역 표가 전부 `List<StoreAdapter>` 를 받는다 —
 * 스프링은 **빈 하나하나**만 그 목록에 넣는다. 매장마다 `@Bean` 을 달면 "설정 한 줄 + 빈 한 줄" 로 두 군데를
 * 고쳐야 하고, 빈 줄을 빠뜨린 매장은 **조용히 수집에서 빠진다.** 여기서 설정 목록을 그대로 빈으로 바꾸면 한 군데다.
 *
 * 빠뜨림은 `BranchRegionsTest` 가 잡는다 — 스프링이 실제로 만든 어댑터의 지점마다 지역 줄이 있는지 본다.
 */
@Component
class SinbiwebAdapterRegistrar : BeanDefinitionRegistryPostProcessor {

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        SinbiwebSites.ALL.forEach { site ->
            val definition = BeanDefinitionBuilder.genericBeanDefinition(SinbiwebAdapter::class.java)
                .addConstructorArgValue(site)
                .addConstructorArgReference("sinbiwebCrawler")
                .beanDefinition
            registry.registerBeanDefinition("sinbiwebAdapter:${site.host}", definition)
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit
}
