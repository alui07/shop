package dev.shop.api.payment;

import dev.shop.api.order.PayableOrder;
import dev.shop.api.payment.PaymentRepository.PaymentRow;

record OpenPayment(PaymentRow payment, PayableOrder order) {}
