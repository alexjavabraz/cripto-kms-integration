package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.tokeniza.kms.dto.AccountCreateRequestDto;
import net.tokeniza.kms.persistence.RequestLogService;
import net.tokeniza.kms.persistence.Wallet;
import net.tokeniza.kms.persistence.WalletService;
import net.tokeniza.kms.service.AccountService;
import net.tokeniza.kms.service.GasFundService;
import net.tokeniza.kms.service.ResponsePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountConsumerTest {

    @Mock AccountService    accountService;
    @Mock GasFundService    gasFundService;
    @Mock WalletService     walletService;
    @Mock ResponsePublisher responsePublisher;
    @Mock RequestLogService requestLogService;

    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    AccountConsumer consumer;

    @BeforeEach
    void injectObjectMapper() throws Exception {
        var field = AccountConsumer.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(consumer, objectMapper);
    }

    private static final String CLIENT_ID      = "cliente-abc123";
    private static final String IDEM_KEY       = "idem-001";
    private static final String RESPONSE_QUEUE = "https://sqs.amazonaws.com/123/test.fifo";
    private static final String ADDRESS        = "0xABCDEF1234567890ABCDEF1234567890ABCDEF12";
    private static final String KEY_ID         = "arn:aws:kms:us-east-1:123:key/test-key";

    // ── Scenario 1: new clientId → creates wallet, funds, publishes success ───

    @Test
    void handle_newClientId_createsWalletAndPublishesSuccess() throws Exception {
        when(walletService.findByClientId(CLIENT_ID)).thenReturn(Optional.empty());
        when(accountService.createWallet(CLIENT_ID))
                .thenReturn(new AccountService.WalletResult(KEY_ID, ADDRESS));

        consumer.handle(buildRequest(CLIENT_ID, IDEM_KEY));

        verify(accountService).createWallet(CLIENT_ID);
        verify(gasFundService).fundAsync(ADDRESS);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(responsePublisher).publish(eq(RESPONSE_QUEUE), eq(IDEM_KEY), payloadCaptor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload).containsEntry("event", "account.create.succeeded");
        assertThat(payload).containsEntry("clientId", CLIENT_ID);
        assertThat(payload).containsEntry("idempotencyKey", IDEM_KEY);
        assertThat(payload).doesNotContainKey("keyId");

        @SuppressWarnings("unchecked")
        Map<String, Object> wallet = (Map<String, Object>) payload.get("wallet");
        assertThat(wallet).containsEntry("address", ADDRESS);
        assertThat(wallet).doesNotContainKey("id");
    }

    // ── Scenario 2: existing clientId → returns existing address, no KMS call ─

    @Test
    void handle_existingClientId_returnsExistingAddressWithoutCreatingNewWallet() throws Exception {
        Wallet existing = fakeWallet(CLIENT_ID, KEY_ID, ADDRESS);
        when(walletService.findByClientId(CLIENT_ID)).thenReturn(Optional.of(existing));

        consumer.handle(buildRequest(CLIENT_ID, IDEM_KEY));

        verify(accountService, never()).createWallet(any());
        verify(gasFundService, never()).fundAsync(any());

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(responsePublisher).publish(eq(RESPONSE_QUEUE), eq(IDEM_KEY), payloadCaptor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload).containsEntry("event", "account.create.succeeded");
        assertThat(payload).containsEntry("clientId", CLIENT_ID);

        @SuppressWarnings("unchecked")
        Map<String, Object> wallet = (Map<String, Object>) payload.get("wallet");
        assertThat(wallet).containsEntry("address", ADDRESS);
    }

    // ── Scenario 3: same clientId, different idempotencyKey → still idempotent ─

    @Test
    void handle_sameClientId_differentIdempotencyKey_returnsExistingAddress() throws Exception {
        Wallet existing = fakeWallet(CLIENT_ID, KEY_ID, ADDRESS);
        when(walletService.findByClientId(CLIENT_ID)).thenReturn(Optional.of(existing));

        consumer.handle(buildRequest(CLIENT_ID, "another-idem-key"));

        verify(accountService, never()).createWallet(any());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(responsePublisher).publish(any(), eq("another-idem-key"), captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) captor.getValue();
        assertThat(payload).containsEntry("clientId", CLIENT_ID);
    }

    // ── Scenario 4: accountService throws → publishes error payload ───────────

    @Test
    void handle_accountServiceThrows_publishesErrorPayload() throws Exception {
        when(walletService.findByClientId(CLIENT_ID)).thenReturn(Optional.empty());
        when(accountService.createWallet(CLIENT_ID))
                .thenThrow(new RuntimeException("KMS unavailable"));

        consumer.handle(buildRequest(CLIENT_ID, IDEM_KEY));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(responsePublisher).publish(eq(RESPONSE_QUEUE), eq(IDEM_KEY), captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) captor.getValue();
        assertThat(payload).containsEntry("event", "account.create.failed");
        assertThat(payload).containsEntry("clientId", CLIENT_ID);

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) payload.get("error");
        assertThat(error).containsEntry("code", "EXECUTION_FAILED");
        assertThat(error.get("message")).asString().contains("KMS unavailable");
    }

    // ── Scenario 5: response does not expose keyId ────────────────────────────

    @Test
    void handle_successResponse_doesNotExposeKeyId() throws Exception {
        when(walletService.findByClientId(CLIENT_ID)).thenReturn(Optional.empty());
        when(accountService.createWallet(CLIENT_ID))
                .thenReturn(new AccountService.WalletResult(KEY_ID, ADDRESS));

        consumer.handle(buildRequest(CLIENT_ID, IDEM_KEY));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(responsePublisher).publish(any(), any(), captor.capture());

        String payloadJson = objectMapper.writeValueAsString(captor.getValue());
        assertThat(payloadJson).doesNotContain(KEY_ID);
        assertThat(payloadJson).doesNotContain("keyId");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildRequest(String clientId, String idempotencyKey) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "type", "ACCOUNT_CREATE",
                "idempotencyKey", idempotencyKey,
                "responseQueue", RESPONSE_QUEUE,
                "event", "account.create.requested",
                "clientId", clientId
        ));
    }

    private Wallet fakeWallet(String clientId, String keyId, String address) {
        Wallet w = new Wallet();
        w.setId(UUID.randomUUID());
        w.setUserId(clientId);
        w.setKeyId(keyId);
        w.setAddress(address);
        w.setNetwork("evm");
        w.setAlias("alias/tokeniza-client-" + clientId);
        w.setCreatedAt(Instant.now());
        return w;
    }
}
