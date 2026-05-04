package io.example.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.FunctionTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(id = "flight-conditions-agent")
public class FlightConditionsAgent extends Agent {

  private static final Logger log = LoggerFactory.getLogger(FlightConditionsAgent.class);

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

  private final String apiKey;
  private final String baseUrl;
  private final String locationQuery;

  private static final DateTimeFormatter SLOT_ID_FORMATTER =
      DateTimeFormatter.ofPattern("uuuu-MM-dd-HH").withResolverStyle(ResolverStyle.STRICT);

  private static final double MAX_WIND_MPS = 12.86;
  private static final int MIN_VISIBILITY_M = 4828;

  public FlightConditionsAgent(Config config) {
    var flightCfg = config.getConfig("flight-agent");
    this.apiKey = flightCfg.getString("api-key");
    this.baseUrl = flightCfg.getString("base-url");
    this.locationQuery = flightCfg.getString("location-query");
  }

  public record ConditionsReport(String timeSlotId, Boolean meetsRequirements, String reason) {}

  private static final String SYSTEM_MESSAGE =
      """
      You are an expert Flight Safety Officer. Your primary responsibility is to determine if a flight training session is safe based on real-time current weather from OpenWeatherMap (via the tool).

      Operational Instructions:
      1. Always invoke the `getWeatherForecast` tool first using the provided timeSlotId.
      2. Analyze the structured summary returned by the tool (condition codes, wind, visibility, precipitation).
      3. Determine safety using the tool's STATUS line (SAFE/UNSAFE) as authoritative unless it contradicts obvious errors.
      4. Handle Uncertainty: If the tool returns "Undetermined" or an error, return meetsRequirements: false and explain that weather data is unavailable.
      5. Future slots: Current Weather reflects conditions now at the configured location, not a hour-by-hour forecast for that slot. If the tool notes a future slot, you may still approve conditionally when STATUS is SAFE but state that limitation in the reason.

      Output Constraints:
      - You MUST return a strictly valid JSON object conforming to the ConditionsReport schema.
      - The JSON must contain exactly these fields: "timeSlotId" (string), "meetsRequirements" (boolean), and "reason" (string).
      - Provide a concise, professional, and audit-ready reason for your decision.
      - Do NOT include any conversational filler, introductory text, or markdown code blocks. Return ONLY the JSON object.
      """
          .stripIndent();

  public Effect<ConditionsReport> query(String timeSlotId) {
    log.info("[flight.conditions] agent.query slotId={}", timeSlotId);

    return effects()
        .systemMessage(SYSTEM_MESSAGE)
        .userMessage("Validate the conditions for slot: " + timeSlotId)
        .responseAs(ConditionsReport.class)
        .thenReply();
  }

  @FunctionTool(
      description =
          "Fetches current weather at the configured location (OpenWeatherMap Current Weather API). "
              + "Returns a structured summary including wind (m/s), visibility, and SAFE/UNSAFE.")
  private String getWeatherForecast(String timeSlotId) {
    if (apiKey == null || apiKey.isBlank()) {
      log.warn("[flight.conditions] tool.getWeatherForecast outcome=skipped reason=missing-api-key");
      return "Undetermined (missing API key)";
    }
    try {
      LocalDateTime slotDateTime = LocalDateTime.parse(timeSlotId, SLOT_ID_FORMATTER);
      String encodedQ = URLEncoder.encode(locationQuery, StandardCharsets.UTF_8);
      String url =
          String.format(
              "%s?q=%s&appid=%s&units=metric",
              baseUrl.replaceAll("/+$", ""), encodedQ, apiKey);

      log.info(
          "[flight.conditions] openweather.request slotId={} locationQuery={}", timeSlotId, locationQuery);

      HttpRequest request =
          HttpRequest.newBuilder().uri(URI.create(url)).GET().build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        log.error(
            "[flight.conditions] openweather.http status={} body={}",
            response.statusCode(),
            truncate(response.body()));
        return "Undetermined (API error)";
      }

      JsonNode root = mapper.readTree(response.body());
      if (root.path("cod").asInt(200) != 200) {
        log.error("[flight.conditions] openweather.payload cod!=200 body={}", truncate(response.body()));
        return "Undetermined (API error)";
      }

      JsonNode weather0 = root.path("weather").path(0);
      int owmId = weather0.path("id").asInt(-1);
      String main = weather0.path("main").asText("unknown");
      String description = weather0.path("description").asText("unknown");

      double windMs = root.path("wind").path("speed").asDouble(0);
      double gustMs = root.path("wind").path("gust").asDouble(0);
      boolean hasGust = root.path("wind").has("gust");

      int visibilityM = root.path("visibility").asInt(10_000);
      int clouds = root.path("clouds").path("all").asInt(0);

      boolean precip =
          root.has("rain") || root.has("snow") || isPrecipitationGroup(main, owmId);

      boolean unsafe =
          isOpenWeatherUnsafe(owmId, windMs, hasGust ? gustMs : null, visibilityM, precip);
      String status = unsafe ? "UNSAFE" : "SAFE";

      String slotNote = "";
      if (slotDateTime.isAfter(LocalDateTime.now())) {
        slotNote =
            " Note: requested slot is in the future; Current Weather describes conditions now, not at booking hour.";
      }

      String precipNote = precip ? " (precipitation indicated)" : "";

      log.info(
          "[flight.conditions] openweather.summary slotId={} owmId={} main={} flightStatus={}",
          timeSlotId,
          owmId,
          main,
          status);

      return String.format(
          "OpenWeather current at q=%s for slot %s: %s (%s). Wind %.1f m/s%s. Visibility %d m. Clouds %d%%%s. OWM weather id=%d. STATUS: %s.%s",
          locationQuery,
          timeSlotId,
          main,
          description,
          windMs,
          hasGust ? ", gust " + String.format("%.1f", gustMs) + " m/s" : "",
          visibilityM,
          clouds,
          precipNote,
          owmId,
          status,
          slotNote);

    } catch (DateTimeParseException e) {
      log.warn("[flight.conditions] tool.getWeatherForecast slotId={} outcome=invalid-slot-format", timeSlotId);
      return "Undetermined (Invalid slot format)";
    } catch (Exception e) {
      log.error("[flight.conditions] tool.getWeatherForecast slotId={} outcome=error", timeSlotId, e);
      return "Undetermined";
    }
  }

  private static boolean isPrecipitationGroup(String main, int id) {
    if (main.equalsIgnoreCase("Rain")
        || main.equalsIgnoreCase("Drizzle")
        || main.equalsIgnoreCase("Thunderstorm")
        || main.equalsIgnoreCase("Snow")) {
      return true;
    }
    return id >= 200 && id < 600;
  }

  private static boolean isOpenWeatherUnsafe(
      int id, double windMs, Double gustMs, int visibilityM, boolean precipObject) {
    if (id >= 200 && id <= 232) {
      return true;
    }
    if (id >= 300 && id <= 321) {
      return true;
    }
    if (id >= 500 && id <= 531) {
      return true;
    }
    if (id >= 600 && id <= 622) {
      return true;
    }
    if (id >= 701 && id <= 781) {
      return true;
    }
    if (precipObject) {
      return true;
    }
    double effective = gustMs != null ? Math.max(windMs, gustMs) : windMs;
    if (effective > MAX_WIND_MPS) {
      return true;
    }
    return visibilityM > 0 && visibilityM < MIN_VISIBILITY_M;
  }

  private static String truncate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() > 400 ? s.substring(0, 400) + "…" : s;
  }
}
