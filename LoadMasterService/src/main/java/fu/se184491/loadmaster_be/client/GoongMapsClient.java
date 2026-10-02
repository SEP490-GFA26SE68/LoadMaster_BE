package fu.se184491.loadmaster_be.client;

import fu.se184491.loadmaster_be.dto.goong.DistanceMatrixResponse;
import fu.se184491.loadmaster_be.dto.goong.DirectionsResponse;
import fu.se184491.loadmaster_be.dto.goong.LatLng;
import fu.se184491.loadmaster_be.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
@Slf4j
public class GoongMapsClient {

    private static final String API_KEY_HEADER = "Goong-Api-Key";

    private final RestClient restClient;
    private final String apiKey;

    public GoongMapsClient(
            @Qualifier("goongRestClient") RestClient restClient,
            @Value("${goong.api.key}") String apiKey
    ) {
        this.restClient = restClient;
        this.apiKey = apiKey;
    }

    public DistanceMatrixResponse getDistanceMatrix(
            List<LatLng> origins,
            List<LatLng> destinations
    ) {
        return executeWithTimeoutRetry(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/distancematrix")
                        .queryParam("origins", coordinates(origins))
                        .queryParam("destinations", coordinates(destinations))
                        .queryParam("vehicle", "car")
                        .build())
                .header(API_KEY_HEADER, apiKey)
                .retrieve()
                .body(DistanceMatrixResponse.class));
    }

    public DirectionsResponse getDirections(LatLng origin, LatLng destination) {
        return executeWithTimeoutRetry(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/direction")
                        .queryParam("origin", coordinate(origin))
                        .queryParam("destination", coordinate(destination))
                        .queryParam("vehicle", "car")
                        .build())
                .header(API_KEY_HEADER, apiKey)
                .retrieve()
                .body(DirectionsResponse.class));
    }

    private String coordinates(List<LatLng> points) {
        return points.stream()
                .map(this::coordinate)
                .collect(Collectors.joining("|"));
    }

    private String coordinate(LatLng point) {
        return point.latitude() + "," + point.longitude();
    }

    private <T> T executeWithTimeoutRetry(Supplier<T> request) {
        int retries = 0;
        while (true) {
            try {
                return request.get();
            } catch (ResourceAccessException exception) {
                if (isTimeout(exception) && retries < 2) {
                    retries++;
                    continue;
                }
                throw unavailable(exception);
            } catch (RestClientException exception) {
                throw unavailable(exception);
            }
        }
    }

    private ServiceUnavailableException unavailable(RestClientException cause) {
        log.warn("Goong Maps API is unavailable: {}", cause.getMessage());
        return new ServiceUnavailableException("Goong Maps API is unavailable", cause);
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
