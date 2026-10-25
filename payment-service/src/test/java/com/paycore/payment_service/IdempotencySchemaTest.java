package com.paycore.payment_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class IdempotencySchemaTest extends PostgresTestBase {

    private static final String FINGERPRINT = "a".repeat(64);   // CHAR(64)

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE idempotency_keys, payments CASCADE");
    }

    // ---------- idempotency_keys: the race arbiter ----------

    @Test
    void secondClaimOfSameKeyLosesTheRace() {
        int first = claimKey("M101", "CREATE_PAYMENT", "order-123-payment");
        int second = claimKey("M101", "CREATE_PAYMENT", "order-123-payment");

        assertThat(first).isEqualTo(1);    // winner
        assertThat(second).isEqualTo(0);   // loser: ON CONFLICT DO NOTHING
        assertThat(countKeys()).isEqualTo(1);
    }

    @Test
    void sameKeyForDifferentMerchantIsAllowed() {
        assertThat(claimKey("M101", "CREATE_PAYMENT", "order-123-payment")).isEqualTo(1);
        assertThat(claimKey("M102", "CREATE_PAYMENT", "order-123-payment")).isEqualTo(1);
        assertThat(countKeys()).isEqualTo(2);
    }

    @Test
    void sameKeyForDifferentOperationIsAllowed() {
        assertThat(claimKey("M101", "CREATE_PAYMENT", "k-1")).isEqualTo(1);
        assertThat(claimKey("M101", "CREATE_REFUND", "k-1")).isEqualTo(1);
        assertThat(countKeys()).isEqualTo(2);
    }

    @Test
    void plainInsertOfDuplicateKeyThrows() {
        claimKey("M101", "CREATE_PAYMENT", "k-1");

        assertThrows(DuplicateKeyException.class, () -> jdbc.update("""
                INSERT INTO idempotency_keys
                    (merchant_id, operation, idem_key, fingerprint, status, expires_at)
                VALUES (?, ?, ?, ?, 'IN_PROGRESS', now() + interval '24 hours')
                """, "M101", "CREATE_PAYMENT", "k-1", FINGERPRINT));
    }

    @Test
    void invalidKeyStatusIsRejected() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO idempotency_keys
                    (merchant_id, operation, idem_key, fingerprint, status, expires_at)
                VALUES (?, ?, ?, ?, 'WHATEVER', now() + interval '24 hours')
                """, "M101", "CREATE_PAYMENT", "k-1", FINGERPRINT));
    }

    // ---------- payments: one active payment per order ----------

    @Test
    void secondActivePaymentForSameOrderIsRejected() {
        insertPayment("M101", "ORD123", "PROCESSING");

        assertThrows(DuplicateKeyException.class,
                () -> insertPayment("M101", "ORD123", "PROCESSING"));
    }

    @Test
    void successfulPaymentAlsoBlocksAnotherAttemptForTheOrder() {
        insertPayment("M101", "ORD123", "CAPTURED");

        assertThrows(DuplicateKeyException.class,
                () -> insertPayment("M101", "ORD123", "CREATED"));
    }

    @Test
    void newAttemptIsAllowedAfterFirstFailed() {
        insertPayment("M101", "ORD123", "FAILED");

        // no exception: FAILED is outside the partial index
        insertPayment("M101", "ORD123", "PROCESSING");

        // but now there is an active one again
        assertThrows(DuplicateKeyException.class,
                () -> insertPayment("M101", "ORD123", "PROCESSING"));
    }

    @Test
    void sameOrderIdForDifferentMerchantsIsAllowed() {
        insertPayment("M101", "ORD123", "PROCESSING");
        insertPayment("M102", "ORD123", "PROCESSING");   // no exception
    }

    @Test
    void zeroAmountIsRejected() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO payments (id, merchant_id, order_id, amount, currency, status)
                VALUES (?, 'M101', 'ORD123', 0, 'INR', 'CREATED')
                """, UUID.randomUUID()));
    }

    @Test
    void invalidPaymentStatusIsRejected() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insertPayment("M101", "ORD123", "WHATEVER"));
    }

    // ---------- helpers ----------

    private int claimKey(String merchantId, String operation, String key) {
        return jdbc.update("""
                INSERT INTO idempotency_keys
                    (merchant_id, operation, idem_key, fingerprint, status, expires_at)
                VALUES (?, ?, ?, ?, 'IN_PROGRESS', now() + interval '24 hours')
                ON CONFLICT DO NOTHING
                """, merchantId, operation, key, FINGERPRINT);
    }

    private int insertPayment(String merchantId, String orderId, String status) {
        return jdbc.update("""
                INSERT INTO payments (id, merchant_id, order_id, amount, currency, status)
                VALUES (?, ?, ?, 5000, 'INR', ?)
                """, UUID.randomUUID(), merchantId, orderId, status);
    }

    private int countKeys() {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Integer.class);
    }
}