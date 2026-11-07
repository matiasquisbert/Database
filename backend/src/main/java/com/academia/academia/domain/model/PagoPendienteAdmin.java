package com.academia.academia.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Transacción en estado PENDIENTE: espera que secretaría verifique el comprobante. */
public record PagoPendienteAdmin(
        Integer idTransaccion,
        String estudiante,
        String curso,
        String concepto,
        BigDecimal monto,
        String metodoPago,
        String nroComprobante,
        LocalDateTime fecha) {
}
