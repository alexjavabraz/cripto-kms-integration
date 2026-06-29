package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.kms.KmsSigner;
import net.tokeniza.kms.TestCryptoUtils;
import net.tokeniza.kms.persistence.RequestLogService;
import net.tokeniza.kms.service.ResponsePublisher;
import net.tokeniza.kms.service.SnsPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.http.HttpService;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration test for ERC20 deployment.
 *
 * Requires Docker — starts a Ganache container to provide a real EVM.
 * KMS is simulated via a mocked KmsClient backed by a local secp256k1 key pair.
 */
@Testcontainers
@ExtendWith(MockitoExtension.class)
class CreationConsumerIntegrationTest {

    // Ganache --deterministic account[0]
    private static final String GANACHE_PRIVATE_KEY =
            "4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> ganache =
            new GenericContainer<>("trufflesuite/ganache:v7.9.2")
                    .withExposedPorts(8545)
                    .withCommand("--deterministic", "--accounts=5", "--chain.chainId=1337");

    Web3j web3j;
    KmsSigner kmsSigner;
    SnsPublisher snsPublisher;
    ResponsePublisher responsePublisher;
    RequestLogService requestLogService;
    CreationConsumer consumer;
    ObjectMapper objectMapper;
    AppProperties props;

    ECKeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        String rpcUrl = "http://localhost:" + ganache.getMappedPort(8545);
        web3j = Web3j.build(new HttpService(rpcUrl));

        // Local key pair — mimics the KMS platform key
        keyPair = ECKeyPair.create(new BigInteger(GANACHE_PRIVATE_KEY, 16));
        KmsClient mockKms = buildLocalKmsClient(keyPair);
        kmsSigner = new KmsSigner(mockKms, "local-test-key");

        // Capture SNS publish calls via ResponsePublisher (responseQueue is null → falls through to SNS)
        snsPublisher = mock(SnsPublisher.class);
        objectMapper = new ObjectMapper();
        responsePublisher = new ResponsePublisher(mock(io.awspring.cloud.sqs.operations.SqsTemplate.class), snsPublisher, objectMapper);
        requestLogService = mock(RequestLogService.class);

        // AppProperties wired for Ganache + ERC20 bytecode loaded from test resources
        props = new AppProperties();
        props.setKeyId("local-test-key");
        props.getDlt().setRpcEndpoint(rpcUrl);
        props.getDlt().setChainId(1337L);
        props.getDlt().setGasLimit(10_000_000L);
        props.getDlt().setMaxFeePerGas(0L);
        props.getDlt().setMaxPriorityFeePerGas(0L);
        props.getDlt().getBytecode().put("ERC20", loadErc20Bytecode());

        consumer = new CreationConsumer(web3j, kmsSigner, responsePublisher, requestLogService, props, objectMapper);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void deployERC20_publishesSuccessResponse() throws Exception {
        String deployerAddress = kmsSigner.getAddress();

        consumer.handle(buildCreationRequest(
                "key-erc20-deploy-ok",
                "RealToken", "RLT", 18, "1000000",
                deployerAddress));

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(snsPublisher, timeout(30_000)).publish(eq("key-erc20-deploy-ok"), payloadCaptor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(response).containsEntry("event", "token.creation.succeeded");
        assertThat(response).containsEntry("idempotencyKey", "key-erc20-deploy-ok");

        @SuppressWarnings("unchecked")
        Map<String, Object> deployment = (Map<String, Object>) response.get("deployment");
        String contractAddress = (String) deployment.get("contractAddress");
        assertThat(contractAddress).matches("(?i)0x[0-9a-fA-F]{40}");
        assertThat(deployment.get("transactionHash")).asString().matches("(?i)0x[0-9a-fA-F]{64}");
        assertThat(deployment.get("deployerAddress")).asString().isEqualToIgnoringCase(deployerAddress);
    }

    @Test
    void deployERC20_contractHasCorrectNameAndSymbol() throws Exception {
        String deployerAddress = kmsSigner.getAddress();

        consumer.handle(buildCreationRequest(
                "key-erc20-symbol",
                "MyStablecoin", "MSTB", 6, "500000",
                deployerAddress));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(snsPublisher, timeout(30_000)).publish(any(), captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) captor.getValue();
        assertThat(response).containsEntry("event", "token.creation.succeeded");

        @SuppressWarnings("unchecked")
        Map<String, Object> deployment = (Map<String, Object>) response.get("deployment");
        String contractAddress = (String) deployment.get("contractAddress");

        // Call name() on deployed contract to verify constructor args were encoded correctly
        String encodedName = callContractView(contractAddress, "0x06fdde03"); // name()
        String encodedSymbol = callContractView(contractAddress, "0x95d89b41"); // symbol()

        String decodedName = decodeString(encodedName);
        String decodedSymbol = decodeString(encodedSymbol);

        assertThat(decodedName).isEqualTo("MyStablecoin");
        assertThat(decodedSymbol).isEqualTo("MSTB");
    }

    @Test
    void deployERC20_duplicateIdempotencyKey_publishesError() throws Exception {
        String deployerAddress = kmsSigner.getAddress();
        String json = buildCreationRequest("key-dup", "Dup", "DUP", 18, "1", deployerAddress);

        consumer.handle(json);
        verify(snsPublisher, timeout(30_000)).publish(eq("key-dup"), any());

        // Second call with same key
        consumer.handle(json);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(snsPublisher, timeout(5_000).times(2)).publish(eq("key-dup"), captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> secondResponse = (Map<String, Object>) captor.getAllValues().get(1);
        assertThat(secondResponse).containsEntry("event", "token.creation.failed");
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) secondResponse.get("error");
        assertThat(error).containsEntry("code", "DUPLICATE_REQUEST");
    }

    @Test
    void deployERC20_missingBytecode_publishesError() throws Exception {
        props.getDlt().getBytecode().remove("ERC20");

        consumer.handle(buildCreationRequest("key-no-bytecode", "X", "X", 18, "1",
                kmsSigner.getAddress()));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(snsPublisher, timeout(5_000)).publish(eq("key-no-bytecode"), captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) captor.getValue();
        assertThat(response).containsEntry("event", "token.creation.failed");
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertThat(error.get("message").toString()).containsIgnoringCase("BYTECODE_ERC20");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildCreationRequest(String idempotencyKey, String name, String symbol,
                                        int decimals, String supply, String ownerAddress) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "idempotencyKey", idempotencyKey,
                "timestamp", java.time.Instant.now().toString(),
                "network", Map.of("name", "testnet", "chainId", 1337),
                "token", Map.of(
                        "standard", "ERC20",
                        "name", name,
                        "symbol", symbol,
                        "ownerAddress", ownerAddress),
                "params", Map.of(
                        "erc20", Map.of("decimals", decimals, "supply", supply)),
                "metadata", Map.of("correlationId", "test-correlation")));
    }

    private String loadErc20Bytecode() throws IOException {
        var url = getClass().getClassLoader().getResource("contracts/ERC20Token.bytecode");
        assertThat(url).isNotNull();
        return Files.readString(Path.of(url.getPath())).trim();
    }

    /** Builds a KmsClient mock backed by a local secp256k1 key pair. */
    private KmsClient buildLocalKmsClient(ECKeyPair pair) {
        KmsClient mock = org.mockito.Mockito.mock(KmsClient.class);

        byte[] publicKeyDer = TestCryptoUtils.buildPublicKeyDer(pair.getPublicKey());
        when(mock.getPublicKey(any(GetPublicKeyRequest.class)))
                .thenReturn(GetPublicKeyResponse.builder()
                        .publicKey(SdkBytes.fromByteArray(publicKeyDer))
                        .build());

        when(mock.sign(any(SignRequest.class))).thenAnswer(inv -> {
            SignRequest req = inv.getArgument(0);
            byte[] digest = req.message().asByteArray();
            Sign.SignatureData webSig = Sign.signMessage(digest, pair, false);
            byte[] der = TestCryptoUtils.buildDerSignature(
                    new BigInteger(1, webSig.getR()),
                    new BigInteger(1, webSig.getS()));
            return SignResponse.builder().signature(SdkBytes.fromByteArray(der)).build();
        });

        return mock;
    }

    /** Calls a view function on a deployed contract and returns the raw hex result. */
    private String callContractView(String contractAddress, String encodedFunction) throws Exception {
        return web3j.ethCall(
                Transaction.createEthCallTransaction(
                        kmsSigner.getAddress(), contractAddress, encodedFunction),
                DefaultBlockParameterName.LATEST
        ).send().getValue();
    }

    /** Decodes an ABI-encoded string returned by a view function call. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String decodeString(String hexResult) {
        List<TypeReference<Type>> outputParams = List.of(
                (TypeReference<Type>) (TypeReference) new TypeReference<Utf8String>() {});
        List<Type> decoded = FunctionReturnDecoder.decode(hexResult, outputParams);
        assertThat(decoded).hasSize(1);
        return (String) decoded.get(0).getValue();
    }
}
