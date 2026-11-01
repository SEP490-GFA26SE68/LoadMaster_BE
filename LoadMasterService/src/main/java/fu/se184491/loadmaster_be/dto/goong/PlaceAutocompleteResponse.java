
package fu.se184491.loadmaster_be.dto.goong;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlaceAutocompleteResponse {

    private List<Prediction> predictions;
    private String status;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Prediction {

        @JsonProperty("place_id")
        private String placeId;

        private String description;

        @JsonProperty("structured_formatting")
        private StructuredFormatting structuredFormatting;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StructuredFormatting {

        @JsonProperty("main_text")
        private String mainText;

        @JsonProperty("secondary_text")
        private String secondaryText;
    }
}
