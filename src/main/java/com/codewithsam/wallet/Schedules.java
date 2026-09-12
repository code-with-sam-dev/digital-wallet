package com.codewithsam.wallet;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background work, switched on by configuration.
 *
 * The relay and the gauge refresh both run on timers in production. In tests
 * they must not, because a timer firing between two assertions decides the
 * result of the second one. A suite that fails once every twenty runs teaches
 * people to re-run it until it goes green, which is how real failures get
 * ignored.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "wallet.schedules.enabled", havingValue = "true", matchIfMissing = true)
public class Schedules {
}
