package fu.se184491.loadmaster_be.service.assistant.tool;

import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Row(int n, String text) {
    }

    private static JsonNode parse(String json) {
        return JSON.readTree(json);
    }

    @Test
    void fiftyRowsAreSentAsTwentyWithTheRealTotal() {
        List<Row> rows = IntStream.range(0, 50).mapToObj(i -> new Row(i, "kiện " + i)).toList();

        JsonNode out = parse(ToolResults.json(ToolResults.page(rows, 100)));

        assertThat(out.get("total").asInt()).isEqualTo(50);
        assertThat(out.get("shown").asInt()).isEqualTo(20);
        assertThat(out.get("rows")).hasSize(20);
        assertThat(out.get("rows").get(0).get("n").asInt()).isZero();
        assertThat(out.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void listKeepsThirtyRowsAndTheTotalCountedByTheDatabase() {
        List<Row> rows = IntStream.range(0, 35).mapToObj(i -> new Row(i, "xe " + i)).toList();

        ToolPage<Row> page = ToolResults.list(rows, 412);
        ToolPage<Row> small = ToolResults.list(rows.subList(0, 3), 0);

        assertThat(page.shown()).isEqualTo(ToolResults.LIST_ROWS);
        assertThat(page.total()).isEqualTo(412);
        assertThat(small.total()).as("total never below the rows read").isEqualTo(3);
        assertThat(ToolResults.list(null, 0).rows()).isEmpty();
    }

    @Test
    void limitBelowTheCapIsKeptAndNonPositiveMeansTheCap() {
        List<Integer> rows = IntStream.range(0, 30).boxed().toList();

        assertThat(ToolResults.page(rows, 5).shown()).isEqualTo(5);
        assertThat(ToolResults.page(rows, 0).shown()).isEqualTo(ToolResults.MAX_ROWS);
        assertThat(ToolResults.page(rows, -3).shown()).isEqualTo(ToolResults.MAX_ROWS);
        assertThat(ToolResults.page(null, 10)).isEqualTo(new ToolPage<>(0, 0, List.of(), false));
    }

    @Test
    void pageTooLongDropsRowsUntilItFitsAndStaysValidJson() {
        List<Row> rows = IntStream.range(0, 20).mapToObj(i -> new Row(i, "x".repeat(1000))).toList();

        String json = ToolResults.json(ToolResults.page(rows, 20));
        JsonNode out = parse(json);

        assertThat(json.length()).isLessThanOrEqualTo(ToolResults.MAX_CHARS);
        assertThat(out.get("total").asInt()).isEqualTo(20);
        assertThat(out.get("truncated").asBoolean()).isTrue();
        assertThat(out.get("shown").asInt()).isEqualTo(out.get("rows").size()).isBetween(1, 19);
    }

    @Test
    void resultOfTwentyThousandCharactersIsCutAndStillValidJson() {
        String json = ToolResults.json(Map.of("note", "a".repeat(20_000)));
        JsonNode out = parse(json);

        assertThat(json.length()).isLessThanOrEqualTo(ToolResults.MAX_CHARS);
        assertThat(out.get("truncated").asBoolean()).isTrue();
        assertThat(out.get("partial").asString()).startsWith("{\"note\":\"aaa");
    }

    @Test
    void cutCountsEscapedCharactersAndNeverSplitsASurrogatePair() {
        String quotes = ToolResults.json("\"".repeat(20_000));
        String emoji = ToolResults.json("🚚".repeat(20_000));

        assertThat(quotes.length()).isLessThanOrEqualTo(ToolResults.MAX_CHARS);
        assertThat(parse(quotes).get("partial").asString()).isNotEmpty().matches("\"+");
        assertThat(emoji.length()).isLessThanOrEqualTo(ToolResults.MAX_CHARS);
        String partial = parse(emoji).get("partial").asString();
        assertThat(partial).isNotEmpty();
        assertThat(partial.length() % 2).isZero();
        assertThat(partial.codePoints()).allMatch(cp -> cp == "🚚".codePointAt(0));
    }

    @Test
    void appExceptionBecomesItsCodeOnly() {
        assertThat(ToolResults.error(new AppException(ErrorCode.TRIP_NOT_FOUND)))
                .isEqualTo("{\"error\":\"TRIP_NOT_FOUND\"}");
    }

    @Test
    void stringIsSentAsItIsAndNullIsAnEmptyObject() {
        assertThat(ToolResults.json("{\"ok\":true}")).isEqualTo("{\"ok\":true}");
        assertThat(ToolResults.json(null)).isEqualTo("{}");
    }
}
