package com.example.solace;

import com.solacesystems.jcsmp.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

@Command(
    name = "publish",
    aliases = {"pub", "send"},
    description = "Publish a message to a Solace queue",
    mixinStandardHelpOptions = true
)
public class PublishCommand implements Callable<Integer> {

    @Mixin
    ConnectionOptions connection;

    @Parameters(index = "0", arity = "0..1",
            description = "Message content (reads from stdin if not provided)")
    String message;

    @Option(names = {"-f", "--file"},
            description = "Read message content from file")
    String inputFile;

    @Option(names = {"-c", "--count"},
            description = "Number of messages to send",
            defaultValue = "1")
    int count;

    @Option(names = {"--correlation-id"},
            description = "Correlation ID for the message")
    String correlationId;

    @Option(names = {"--delivery-mode"},
            description = "Delivery mode: PERSISTENT or DIRECT",
            defaultValue = "PERSISTENT")
    String deliveryMode;

    @Option(names = {"--ttl"},
            description = "Time to live in milliseconds (0 = no expiry)",
            defaultValue = "0")
    long ttl;

    @Override
    public Integer call() {
        JCSMPSession session = null;
        XMLMessageProducer producer = null;

        try {
            String content = resolveMessageContent();
            if (content == null || content.isEmpty()) {
                System.err.println("Error: No message content provided");
                return 1;
            }

            System.out.println("Connecting to " + connection.host + "...");
            session = SolaceConnection.createSession(connection);
            System.out.println("Connected successfully");

            Queue queue = JCSMPFactory.onlyInstance().createQueue(connection.queue);

            producer = session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
                @Override
                public void responseReceivedEx(Object key) {
                    // Message acknowledged
                }

                @Override
                public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
                    System.err.println("Error publishing message: " + cause.getMessage());
                }
            });

            DeliveryMode mode = "DIRECT".equalsIgnoreCase(deliveryMode) 
                ? DeliveryMode.DIRECT 
                : DeliveryMode.PERSISTENT;

            for (int i = 0; i < count; i++) {
                TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                msg.setText(content);
                msg.setDeliveryMode(mode);

                if (correlationId != null) {
                    msg.setCorrelationId(correlationId);
                }

                if (ttl > 0) {
                    msg.setTimeToLive(ttl);
                }

                producer.send(msg, queue);

                String msgId = (count > 1) ? " [" + (i + 1) + "/" + count + "]" : "";
                System.out.println("Published message to queue '" + connection.queue + "'" + msgId);
            }

            System.out.println("Successfully published " + count + " message(s)");
            return 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        } finally {
            if (producer != null) {
                producer.close();
            }
            if (session != null) {
                session.closeSession();
            }
        }
    }

    private String resolveMessageContent() throws Exception {
        if (inputFile != null) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(inputFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
            }
            return sb.toString();
        }

        if (message != null) {
            return message;
        }

        // Read from stdin if available
        if (System.in.available() > 0) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
            }
            return sb.toString();
        }

        return null;
    }
}
