package net.tokeniza.kms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceService {

    private final Web3j web3j;

    public record BalanceResult(String raw, String formatted, String name, String symbol, int decimals) {}

    public BalanceResult getErc20Balance(String contractAddress, String walletAddress) throws Exception {
        BigInteger rawBalance = callUint256(contractAddress, new Function(
                "balanceOf",
                List.of(new Address(walletAddress)),
                List.of(new TypeReference<Uint256>() {})
        ));

        int decimals = callUint8(contractAddress, new Function(
                "decimals", Collections.emptyList(), List.of(new TypeReference<Uint8>() {})
        ));

        String name   = callString(contractAddress, new Function("name",   Collections.emptyList(), List.of(new TypeReference<Utf8String>() {})));
        String symbol = callString(contractAddress, new Function("symbol", Collections.emptyList(), List.of(new TypeReference<Utf8String>() {})));

        BigDecimal formatted = new BigDecimal(rawBalance).divide(BigDecimal.TEN.pow(decimals));

        log.info("Balance query: contract={} wallet={} balance={} {}", contractAddress, walletAddress, formatted, symbol);

        return new BalanceResult(rawBalance.toString(), formatted.toPlainString(), name, symbol, decimals);
    }

    // ── EVM call helpers ──────────────────────────────────────────────────────

    private BigInteger callUint256(String contract, Function function) throws Exception {
        List<Type> result = ethCall(contract, function);
        return ((Uint256) result.get(0)).getValue();
    }

    private int callUint8(String contract, Function function) throws Exception {
        List<Type> result = ethCall(contract, function);
        return ((Uint8) result.get(0)).getValue().intValue();
    }

    private String callString(String contract, Function function) throws Exception {
        List<Type> result = ethCall(contract, function);
        return ((Utf8String) result.get(0)).getValue();
    }

    @SuppressWarnings("rawtypes")
    private List<Type> ethCall(String contract, Function function) throws Exception {
        String encodedFunction = FunctionEncoder.encode(function);
        EthCall response = web3j.ethCall(
                Transaction.createEthCallTransaction(null, contract, encodedFunction),
                DefaultBlockParameterName.LATEST
        ).send();
        if (response.hasError()) {
            throw new RuntimeException("eth_call error: " + response.getError().getMessage());
        }
        return FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
    }
}
