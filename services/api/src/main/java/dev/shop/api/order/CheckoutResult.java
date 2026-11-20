package dev.shop.api.order;

record CheckoutResult(Order order, boolean replayed) {}
