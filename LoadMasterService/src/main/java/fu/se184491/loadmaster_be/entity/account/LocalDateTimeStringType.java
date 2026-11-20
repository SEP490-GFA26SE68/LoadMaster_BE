package fu.se184491.loadmaster_be.entity.account;

import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import jakarta.persistence.AttributeConverter;
import org.hibernate.usertype.UserType;

import java.sql.Types;
import java.time.LocalDateTime;

/**
 * User-only adapter for the existing LocalDateTime/VARCHAR(19) contract.
 * Hibernate 7.4 sizes a temporal domain AttributeConverter as a temporal column,
 * discarding its explicit string length. UserType supplies non-temporal mapping
 * metadata while reusing exactly the existing converter for JDBC reads and writes.
 * Do not register globally: other timestamps retain their own storage contracts.
 */
public final class LocalDateTimeStringType implements UserType<LocalDateTime> {
    private static final SupportTicket.LocalDateTimeSecondsConverter CONVERTER =
            new SupportTicket.LocalDateTimeSecondsConverter();

    @Override
    public int getSqlType() { return Types.VARCHAR; }

    @Override
    public Class<LocalDateTime> returnedClass() { return LocalDateTime.class; }

    @Override
    public LocalDateTime deepCopy(LocalDateTime value) { return value; }

    @Override
    public boolean isMutable() { return false; }

    @Override
    public AttributeConverter<LocalDateTime, String> getValueConverter() { return CONVERTER; }

    @Override
    public long getDefaultSqlLength() { return 19; }
}
