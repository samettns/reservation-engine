package io.github.samettns.reservation.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * All five strategies are beans; {@code reservation.strategy} picks which one the API uses.
 * An unknown name fails at startup (fail fast) instead of at the first request.
 */
@Configuration
public class StrategyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StrategyConfiguration.class);

    @Bean
    @Primary
    ReservationStrategy activeStrategy(List<ReservationStrategy> all,
                                       @Value("${reservation.strategy}") String name) {
        ReservationStrategy chosen = all.stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown reservation.strategy '" + name
                        + "'. Valid values: " + all.stream().map(ReservationStrategy::name).toList()));
        if (chosen instanceof NaiveStrategy) {
            log.warn("Using the NAIVE strategy: it oversells under concurrency. Demo/benchmark use only.");
        }
        log.info("Active reservation strategy: {}", chosen.name());
        return chosen;
    }
}
