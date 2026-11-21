package dev.shop.api.order;

record Transition(OrderStatus next, StockEffect effect) {}
