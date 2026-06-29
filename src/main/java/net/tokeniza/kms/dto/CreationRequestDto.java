package net.tokeniza.kms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreationRequestDto extends BaseRequestDto {
    private String idempotencyKey;
    private String timestamp;
    private Network network;
    private Token token;
    private Params params;
    private Metadata metadata;

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Network {
        private String name;
        private long chainId;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Token {
        private String standard;   // ERC20 | ERC721 | ERC1155
        private String name;
        private String symbol;
        private String ownerAddress;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Params {
        private Erc20Params erc20;
        private Erc721Params erc721;
        private Erc1155Params erc1155;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Erc20Params {
        private int decimals = 18;
        private String supply;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Erc721Params {
        private String baseUri;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Erc1155Params {
        private String uri;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metadata {
        private String correlationId;
    }
}
