package fu.se184491.loadmaster_be.client;

import fu.se184491.loadmaster_be.dto.goong.DistanceMatrixResponse;
import fu.se184491.loadmaster_be.dto.goong.DirectionsResponse;
import fu.se184491.loadmaster_be.dto.goong.LatLng;
import fu.se184491.loadmaster_be.exception.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

class GoongMapsClientTest {

    @Test
    void returnsDistanceAndDurationForEveryOriginDestinationPair() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://goong.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoongMapsClient client = new GoongMapsClient(builder.build(), "test-api-key");
        server.expect(once(), requestTo(
                        "https://goong.test/distancematrix?origins=10.75,106.67%7C10.78,106.7"
                                + "&destinations=10.8,106.72&vehicle=car"))
                .andExpect(method(GET))
                .andExpect(header("Goong-Api-Key", "test-api-key"))
                .andRespond(withSuccess("""
                        {
                          "rows": [
                            {"elements": [{"status": "OK", "duration": {"text": "5 phút", "value": 300}, "distance": {"text": "4 km", "value": 4000}}]},
                            {"elements": [{"status": "OK", "duration": {"text": "7 phút", "value": 420}, "distance": {"text": "6 km", "value": 6000}}]}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        DistanceMatrixResponse response = client.getDistanceMatrix(
                List.of(new LatLng(10.75, 106.67), new LatLng(10.78, 106.70)),
                List.of(new LatLng(10.80, 106.72)));

        assertEquals(2, response.getRows().size());
        assertEquals(300, response.getRows().getFirst().getElements().getFirst().getDuration().getValue());
        assertEquals(4000, response.getRows().getFirst().getElements().getFirst().getDistance().getValue());
        assertEquals(420, response.getRows().get(1).getElements().getFirst().getDuration().getValue());
        assertEquals(6000, response.getRows().get(1).getElements().getFirst().getDistance().getValue());
        server.verify();
    }

    @Test
    void returnsDirectionOptionsWithDurationDistanceAndPolyline() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://goong.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoongMapsClient client = new GoongMapsClient(builder.build(), "test-api-key");
        server.expect(once(), requestTo(
                        "https://goong.test/direction?origin=10.75,106.67"
                                + "&destination=10.8,106.72&vehicle=car"))
                .andExpect(method(GET))
                .andExpect(header("Goong-Api-Key", "test-api-key"))
                .andRespond(withSuccess("""
                        {
                          "routes": [
                            {
                              "legs": [{"distance": {"text": "8 km", "value": 8123}, "duration": {"text": "12 phút", "value": 721}}],
                              "overview_polyline": {"points": "encoded-route-one"}
                            },
                            {
                              "legs": [{"distance": {"text": "9 km", "value": 9345}, "duration": {"text": "10 phút", "value": 615}}],
                              "overview_polyline": {"points": "encoded-route-two"}
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        DirectionsResponse response = client.getDirections(
                new LatLng(10.75, 106.67),
                new LatLng(10.80, 106.72));

        assertEquals(2, response.getRoutes().size());
        assertEquals(721, response.getRoutes().getFirst().getDurationSeconds());
        assertEquals(8123, response.getRoutes().getFirst().getDistanceMeters());
        assertEquals("encoded-route-one", response.getRoutes().getFirst().getPolyline());
        assertEquals(615, response.getRoutes().get(1).getDurationSeconds());
        assertEquals(9345, response.getRoutes().get(1).getDistanceMeters());
        assertEquals("encoded-route-two", response.getRoutes().get(1).getPolyline());
        server.verify();
    }

    @Test
    void retriesTwiceAfterTimeoutAndReturnsTheSuccessfulResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://goong.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoongMapsClient client = new GoongMapsClient(builder.build(), "test-api-key");
        String expectedUrl = "https://goong.test/direction?origin=10.75,106.67"
                + "&destination=10.8,106.72&vehicle=car";
        server.expect(times(2), requestTo(expectedUrl))
                .andRespond(request -> {
                    throw new ResourceAccessException(
                            "Read timed out",
                            new SocketTimeoutException("Read timed out"));
                });
        server.expect(once(), requestTo(expectedUrl))
                .andRespond(withSuccess("""
                        {"routes": [{"legs": [{"distance": {"value": 1000}, "duration": {"value": 120}}],
                        "overview_polyline": {"points": "retry-route"}}]}
                        """, MediaType.APPLICATION_JSON));

        DirectionsResponse response = client.getDirections(
                new LatLng(10.75, 106.67),
                new LatLng(10.80, 106.72));

        assertEquals("retry-route", response.getRoutes().getFirst().getPolyline());
        server.verify();
    }

    @Test
    void reportsServiceUnavailableAfterTimeoutRetriesAreExhausted() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://goong.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoongMapsClient client = new GoongMapsClient(builder.build(), "test-api-key");
        server.expect(times(3), requestTo(
                        "https://goong.test/distancematrix?origins=10.75,106.67"
                                + "&destinations=10.8,106.72&vehicle=car"))
                .andRespond(request -> {
                    throw new ResourceAccessException(
                            "Read timed out",
                            new SocketTimeoutException("Read timed out"));
                });

        assertThrows(
                ServiceUnavailableException.class,
                () -> client.getDistanceMatrix(
                        List.of(new LatLng(10.75, 106.67)),
                        List.of(new LatLng(10.80, 106.72))));
        server.verify();
    }
}
