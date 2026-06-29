package net.tokeniza.kms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BaseRequestDto {
    /** SQS queue name or URL where kms-integration should publish the response. Falls back to the default SNS topic when blank. */
    private String responseQueue;
}
