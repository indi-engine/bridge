import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.format.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.FileInputStream;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Channel;

public class Bridge {

    // Logger
    private static final Logger log = LoggerFactory.getLogger(Bridge.class);

    // Json mapper
    private static final ObjectMapper map = new ObjectMapper();

    // Main script
    public static void main(String[] args) throws Exception {

        // Load props from file at first, if possible, and then merge CLI ones on top
        var props = new Properties();
        try (var fis = new FileInputStream(System.getProperty("props.path", "debezium.properties"))) { props.load(fis); }
        catch (Exception e) {}
        props.putAll(System.getProperties());

        // Setup RabbitMQ connection
        var factory = new ConnectionFactory();
        factory.setHost(props.getProperty("rabbitmq.host"));
        factory.setPort(Integer.parseInt(props.getProperty("rabbitmq.port")));
        factory.setUsername(props.getProperty("rabbitmq.user"));
        factory.setPassword(props.getProperty("rabbitmq.password"));
        final var rabbit = factory.newConnection();

        // Setup channel and declare exchange where CDC-event will be pushed into
        final var channel = rabbit.createChannel();
        var exchange = props.getProperty("rabbitmq.exchange");
        channel.exchangeDeclare(exchange, "direct", false);

        // Debug flag
        var debug = Boolean.parseBoolean(props.getProperty("debug", "false"));

        // Create the engine
        DebeziumEngine<ChangeEvent<String, String>> engine = DebeziumEngine.create(Json.class).using(props)

            // Completion callback — called when engine stops
            .using((success, message, error) -> {
                if (error != null) {
                    log.error("Engine stopped with error: {}", message, error);
                } else {
                    log.info("Engine stopped cleanly: {}", message);
                }
            })

            // CDC-events handler by itself
            .notifying(record -> {

                // Get value
                String valueJson = record.value();

                // If it's a tombstone / delete marker - do nothing
                if (valueJson == null) return;

                // Else start preparing the value
                try {

                    // Shortcuts
                    var src = map.readTree(valueJson);
                    var out = map.createObjectNode();
                    var type = src.get("op").asText();
                    var database = src.at("/source/db").asText();

                    // Set database and table names
                    out.put("database", database);
                    out.put("table", src.at("/source/table").asText());

                    // If it's UPDATE-event
                    if (type.equals("u")) {

                        // Prepare 'old'-data
                        var now = src.get("after");
                        var old = map.createObjectNode();
                        src.get("before").fields().forEachRemaining(item -> {
                            if (!item.getValue().equals(now.get(item.getKey()))) {
                                old.set(item.getKey(), item.getValue());
                            }
                        });

                        // Skip if nothing actually changed
                        if (old.isEmpty()) return;

                        // Set values of 'type', 'data' and 'old' keys
                        out.put("type", "update");
                        out.set("data", now);
                        out.set("old", old);

                    // Else set values only for 'type' and 'data' keys
                    } else if (type.equals("d")) {
                        out.put("type", "delete");
                        out.set("data", src.get("before"));
                    } else if (type.equals("c")) {
                        out.put("type", "insert");
                        out.set("data", src.get("after"));
                    }

                    // Convert to json
                    var json = map.writeValueAsString(out);

                    // Push to RabbitMQ exchange
                    channel.basicPublish(exchange, database, null, json.getBytes());

                    // Print resulting json of a CDC-event, if debugging is enabled
                    if (debug) log.info("CDC event: {}", json);

                // If problem popper - print it
                } catch (Exception e) {
                    log.error("Failed to parse CDC event", e);
                }
            })

            // Build engine
            .build();

        // Register shutdown hook BEFORE starting
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {

            // Log shutdown attempt
            log.info("Shutting down Debezium engine...");

            // Close everything
            try {
                engine.close();
                channel.close();
                rabbit.close();

            // Log on failure
            } catch (Exception e) {
                log.error("Error closing engine", e);
            }
        }));

        // Run engine
        log.info("Debezium engine started, waiting for CDC events...");
        engine.run();

        // Exit on completion or failure
        log.info("Bridge exiting.");
        System.exit(0);
    }
}
