package fu.se184491.loadmaster_be.dto.request.warehouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Số seal niêm phong thùng, ghi sau khi xếp xong và trước khi tài xế xuất phát; 1–32 ký tự sau khi bỏ khoảng trắng hai đầu. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecordSealRequest {

    private String sealNumber;
}
