package net.tokeniza.kms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccountCreateRequestDto extends BaseRequestDto {
    private String event;
    private String idempotencyKey;
    private String userId;
    private String network;
}
