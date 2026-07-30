package nats;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;

import java.io.IOException;

/** Shared connect helper so reconnect settings are defined once for every NATS client/listener. */
public class NatsConnections {

    private NatsConnections() {
    }

    public static Connection connect(String url) throws IOException, InterruptedException {
        Options options = Options.builder()
                .server(url)
                .maxReconnects(-1)
                .build();
        return Nats.connect(options);
    }
}
