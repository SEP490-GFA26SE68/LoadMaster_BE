package fu.se184491.loadmaster_be.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "permissions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Permission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "permission_code", length = 100, unique = true, nullable = false)
    private String permissionCode;

    @Column(name = "resource", length = 50, nullable = false)
    private String resource;

    @Column(name = "action", length = 50, nullable = false)
    private String action;

    @Column(name = "description", columnDefinition = "text")
    private String description;
}
