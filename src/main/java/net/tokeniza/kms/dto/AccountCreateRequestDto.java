package net.tokeniza.kms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccountCreateRequestDto extends BaseRequestDto {
    private String event;
    private String idempotencyKey;
    /** Client-defined unique identifier — never exposed as KMS keyId. */
    private String clientId;
}
