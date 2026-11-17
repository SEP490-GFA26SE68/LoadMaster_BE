package fu.se184491.loadmaster_be.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Converter
public class InstantToStringConverter
        implements AttributeConverter<Instant, String> {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String convertToDatabaseColumn(Instant attribute) {
        if (attribute == null) {
            return null;
        }

        return LocalDateTime.ofInstant(attribute, ZoneOffset.UTC)
                .format(FORMATTER);
    }

    @Override
    public Instant convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }

        return LocalDateTime.parse(dbData, FORMATTER)
                .toInstant(ZoneOffset.UTC);
    }
}