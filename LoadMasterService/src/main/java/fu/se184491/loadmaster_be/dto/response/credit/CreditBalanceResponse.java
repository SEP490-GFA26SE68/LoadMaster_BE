package fu.se184491.loadmaster_be.dto.response.credit;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditBalanceResponse {
    private Long companyId;
    private Integer balance;
    private Boolean isUnlimited;
}
