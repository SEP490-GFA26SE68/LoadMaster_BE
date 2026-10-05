package fu.se184491.loadmaster_be.helpers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class TemporaryPasswordGenerator {

    @Value("${app.password.generator.upper}")
    private String upper;

    @Value("${app.password.generator.lower}")
    private String lower;

    @Value("${app.password.generator.digits}")
    private String digits;

    private static final String SPECIAL =
            "!@#$%&*";

    private static final SecureRandom RANDOM =
            new SecureRandom();

    public String generate() {
        String all =
                upper + lower + digits + SPECIAL;

        StringBuilder password =
                new StringBuilder();

        password.append(randomChar(upper));
        password.append(randomChar(lower));
        password.append(randomChar(digits));
        password.append(randomChar(SPECIAL));

        while (password.length() < 12) {
            password.append(
                    randomChar(all)
            );
        }

        return shuffle(
                password.toString()
        );
    }

    private char randomChar(String source) {
        return source.charAt(
                RANDOM.nextInt(source.length())
        );
    }

    private String shuffle(String value) {
        char[] chars =
                value.toCharArray();

        for (int i = chars.length - 1; i > 0; i--) {
            int j =
                    RANDOM.nextInt(i + 1);

            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }

        return new String(chars);
    }
}