package com.example.springai.service;

import com.example.springai.dto.OrderDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.Map;
import java.util.function.Function;

/**
 * Spring AI tool/function definition allowing the local LLM to execute structured business function calls.
 * <p>
 * Demonstrates:
 * <ul>
 *   <li>{@link Bean} + {@link Description}: Automatically exposes JSON Schema tool definitions to the LLM</li>
 *   <li>Dynamic Function Calling: The model inspects user prompt intent, automatically pauses text generation, triggers this Java function, and summarizes the returned JSON structure</li>
 * </ul>
 *
 * @author Spring Showcase Team
 * @version 1.0
 */
@Configuration
public class OrderToolService {

    private static final Logger log = LoggerFactory.getLogger(OrderToolService.class);

    /**
     * Request input schema bound to LLM tool call arguments.
     *
     * @param orderId the order identifier (e.g. ORD-101)
     */
    public record Request(String orderId) {}

    private final Map<String, OrderDetails> orderDatabase = Map.of(
            "ORD-101", new OrderDetails("ORD-101", "Alice Johnson", "SHIPPED", "123 Market St, San Francisco, CA", "2026-08-19", 249.99),
            "ORD-102", new OrderDetails("ORD-102", "Bob Smith", "PROCESSING", "456 Elm Ave, New York, NY", "2026-08-22", 89.50),
            "ORD-103", new OrderDetails("ORD-103", "Charlie Davis", "DELIVERED", "789 Pine Rd, London, UK", "2026-08-15", 1120.00)
    );

    /**
     * Tool callback function looking up order shipping and fulfillment details by Order ID.
     *
     * @return a {@link Function} accepting {@link Request} and returning {@link OrderDetails}
     */
    @Bean
    @Description("Look up the shipping and processing status of a customer order by its Order ID")
    public Function<Request, OrderDetails> getOrderStatus() {
        return request -> {
            log.info("[SPRING-AI-TOOL] Executing getOrderStatus tool call for orderId={}", request.orderId());
            String key = request.orderId().trim().toUpperCase();
            return orderDatabase.getOrDefault(key,
                    new OrderDetails(request.orderId(), "Unknown Customer", "NOT_FOUND", "N/A", "N/A", 0.0));
        };
    }
}
