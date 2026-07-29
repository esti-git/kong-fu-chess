package client;

import client.logging.ClientLog;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class ApiGatewayClient {

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public ApiGatewayClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public JSONObject login(String username, String password) {
        try {
            String body = new JSONObject().put("username", username).put("password", password).toString();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/login"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new JSONObject(response.body());
        } catch (Exception e) {
            ClientLog.warn("API Gateway unreachable at " + baseUrl + ": " + e.getMessage());
            return null;
        }
    }
}
