package io.example;

import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;

import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Setup
public class Bootstrap implements ServiceSetup {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);
    private final Config appConfig;

    public Bootstrap(ComponentClient componentClient, Config appConfig) {
        this.appConfig = appConfig;
    }

    @Override
    public void onStartup() {
        var flight = appConfig.getConfig("flight-agent");
        String apiKey = flight.getString("api-key");
        String locationQuery = flight.getString("location-query");
        boolean apiKeyConfigured = apiKey != null && !apiKey.isBlank();
        log.info(
                "[bootstrap] service=flight-training-scheduler basePath=/flight openWeatherApiKeyConfigured={} openWeatherLocationQuery={}",
                apiKeyConfigured,
                locationQuery);
    }

}
