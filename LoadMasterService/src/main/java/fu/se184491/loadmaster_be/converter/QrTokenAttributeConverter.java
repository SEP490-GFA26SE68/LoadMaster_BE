
package fu.se184491.loadmaster_be.converter;

import fu.se184491.loadmaster_be.service.qr.AesQrTokenService;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class QrTokenAttributeConverter
        implements AttributeConverter<String, String> {

    private final AesQrTokenService aesQrTokenService;

    public QrTokenAttributeConverter(
            AesQrTokenService aesQrTokenService
    ) {
        this.aesQrTokenService = aesQrTokenService;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return aesQrTokenService.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return aesQrTokenService.decrypt(dbData);
    }
}
