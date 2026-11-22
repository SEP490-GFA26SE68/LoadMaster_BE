package fu.se184491.loadmaster_be.service.assistant.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Lệnh giao diện cho màn 3D. Trường không dùng là {@code null} và bị bỏ khỏi JSON. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UiAction(String type, String packageCode, String preset, Integer step, String mode, String tab) {
}
