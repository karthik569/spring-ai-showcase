package com.example.springai.dto;

public record OrderDetails(
        String orderId,
        String customerName,
        String status,
        String deliveryAddress,
        String estimatedDeliveryDate,
        double amount
) {}
