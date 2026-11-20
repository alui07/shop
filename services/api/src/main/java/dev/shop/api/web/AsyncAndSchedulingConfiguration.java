package dev.shop.api.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The one place that switches background work on; tests set {@code shop.jobs.enabled=false} and call the jobs directly. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.jobs.enabled", matchIfMissing = true)
@EnableScheduling
class AsyncAndSchedulingConfiguration {}
