package synamyk.dto.admin;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class AdminPaymentResponse {
    private Long id;
    private String transactionId;
    private UserInfo user;
    private BigDecimal amount;
    private String paymentMethod;
    private String status;
    private LocalDateTime date;
    private Integer earnedPoints;
    private String testTitle;
    private Long subTestId;      // null for a whole-test (bundle) purchase
    private String subTestTitle; // null for a whole-test (bundle) purchase

    @Data
    @Builder
    public static class UserInfo {
        private Long id;
        private String fullName;
        private String phone;
        private String avatarUrl;
    }
}
