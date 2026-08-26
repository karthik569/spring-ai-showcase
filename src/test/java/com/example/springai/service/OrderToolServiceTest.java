package com.example.springai.service;

import com.example.springai.dto.OrderDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class OrderToolServiceTest {

    private OrderToolService orderToolService;
    private Function<OrderToolService.Request, OrderDetails> orderFunction;

    @BeforeEach
    void setUp() {
        orderToolService = new OrderToolService();
        orderFunction = orderToolService.getOrderStatus();
    }

    @Test
    void testGetOrderForValidId() {
        OrderDetails result = orderFunction.apply(new OrderToolService.Request("ORD-101"));
        assertNotNull(result);
        assertEquals("ORD-101", result.orderId());
        assertEquals("Alice Johnson", result.customerName());
        assertEquals("SHIPPED", result.status());
        assertEquals(249.99, result.amount());
    }

    @Test
    void testGetOrderCaseInsensitiveAndTrim() {
        OrderDetails result = orderFunction.apply(new OrderToolService.Request(" ord-103 "));
        assertNotNull(result);
        assertEquals("ORD-103", result.orderId());
        assertEquals("DELIVERED", result.status());
    }

    @Test
    void testGetOrderNotFound() {
        OrderDetails result = orderFunction.apply(new OrderToolService.Request("ORD-99999"));
        assertNotNull(result);
        assertEquals("ORD-99999", result.orderId());
        assertEquals("NOT_FOUND", result.status());
    }
}
