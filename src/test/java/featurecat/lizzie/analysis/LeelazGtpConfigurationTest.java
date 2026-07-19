package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.gtpconfig.GtpConfigurationProbe;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class LeelazGtpConfigurationTest {
  @Test
  void buildsOneLineProfileCommandOnlyForSupportedProtocol() {
    JSONObject profile = new JSONObject().put("mode", "rank").put("rankPreset", "6d");

    String command =
        Leelaz.configurationProfileCommand(GtpConfigurationProbe.ZENGTP_PROTOCOL, profile)
            .orElseThrow();

    assertTrue(command.startsWith("zengtp_config_set "));
    assertTrue(command.contains("\"rankPreset\":\"6d\""));
    assertTrue(!command.contains("\n"));
    assertTrue(Leelaz.configurationProfileCommand("unknown", profile).isEmpty());
    assertTrue(
        Leelaz.configurationProfileCommand(GtpConfigurationProbe.ZENGTP_PROTOCOL, null).isEmpty());
  }

  @Test
  void extractsPayloadFromNumberedAndUnnumberedGtpResponses() {
    assertEquals(
        "{\"operation\":\"set\"}", Leelaz.gtpResponsePayload("=42 {\"operation\":\"set\"}"));
    assertEquals("invalid value", Leelaz.gtpResponsePayload("? invalid value"));
    assertEquals("plain", Leelaz.gtpResponsePayload("plain"));
  }
}
