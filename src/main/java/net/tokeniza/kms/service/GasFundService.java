package net.tokeniza.kms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GasFundService {

    private final TokenTransferService transferService;
    private final AppProperties props;

    /**
     * Best-effort: sends native token to a newly created wallet so it can pay gas.
     * Errors are logged but never re-thrown — must not block the account creation response.
     */
    public void fundAsync(String toAddress) {
        String amount = props.getDlt().getGasFundAmountEth();
        Thread.ofVirtual().start(() -> {
            try {
                log.info("Gas fund: sending {} to {}", amount, toAddress);
                transferService.sendNative(toAddress, amount);
                log.info("Gas fund completed for {}", toAddress);
            } catch (Exception e) {
                log.error("Gas fund failed for {} — continuing without funding: {}", toAddress, e.getMessage());
            }
        });
    }
}
