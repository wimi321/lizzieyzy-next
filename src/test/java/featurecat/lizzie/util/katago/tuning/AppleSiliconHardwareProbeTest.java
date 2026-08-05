package featurecat.lizzie.util.katago.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppleSiliconHardwareProbeTest {
  @Test
  void parsesExpectedSysctlAndSwVersValuesWithoutQueryingIdentifiers() {
    Map<List<String>, AppleSiliconHardwareProbe.CommandResult> responses = new HashMap<>();
    responses.put(sysctl("hw.model"), ok("hw.model: Mac16,1\n"));
    responses.put(sysctl("machdep.cpu.brand_string"), ok("Apple M4 Pro\n"));
    responses.put(sysctl("hw.machine"), ok("hw.machine=arm64\n"));
    responses.put(sysctl("hw.logicalcpu"), ok("14\n"));
    responses.put(sysctl("hw.memsize"), ok("51539607552\n"));
    responses.put(sysctl("sysctl.proc_translated"), ok("1\n"));
    responses.put(List.of("/usr/bin/sw_vers", "-buildVersion"), ok("BuildVersion: 25F90\n"));

    List<List<String>> commands = new ArrayList<>();
    AppleSiliconHardwareProbe probe =
        new AppleSiliconHardwareProbe(
            command -> {
              commands.add(List.copyOf(command));
              return responses.getOrDefault(
                  command, new AppleSiliconHardwareProbe.CommandResult(1, "", "missing"));
            });

    AppleSiliconHardwareProbe.HardwareProfile profile = probe.probe();

    assertEquals("Mac16,1", profile.hardwareModel());
    assertEquals("Apple M4 Pro", profile.chipOrBrand());
    assertEquals("arm64", profile.architecture());
    assertEquals(14, profile.logicalCpuCount());
    assertEquals(51_539_607_552L, profile.memoryBytes());
    assertEquals("25F90", profile.macOsBuild());
    assertTrue(profile.rosettaTranslated());
    assertEquals(7, commands.size());
    assertTrue(commands.containsAll(responses.keySet()));
    assertFalse(
        commands.stream()
            .map(command -> String.join(" ", command).toLowerCase(Locale.ROOT))
            .anyMatch(
                command ->
                    command.contains("serial")
                        || command.contains("uuid")
                        || command.contains("ioplatform")));
  }

  @Test
  void toleratesUnavailableAndMalformedOptionalValues() {
    AppleSiliconHardwareProbe probe =
        new AppleSiliconHardwareProbe(
            command -> {
              String key = command.get(command.size() - 1);
              return switch (key) {
                case "hw.model" -> ok("Mac14,2\n");
                case "machdep.cpu.brand_string" ->
                    new AppleSiliconHardwareProbe.CommandResult(1, "", "unavailable");
                case "hw.logicalcpu", "hw.memsize" -> ok("not-a-number\n");
                default -> new AppleSiliconHardwareProbe.CommandResult(1, "", "unavailable");
              };
            });

    AppleSiliconHardwareProbe.HardwareProfile profile = probe.probe();

    assertEquals("Mac14,2", profile.hardwareModel());
    assertEquals("Mac14,2", profile.chipOrBrand());
    assertEquals(0L, profile.memoryBytes());
    assertFalse(profile.rosettaTranslated());
  }

  private static List<String> sysctl(String key) {
    return List.of("/usr/sbin/sysctl", "-n", key);
  }

  private static AppleSiliconHardwareProbe.CommandResult ok(String output) {
    return new AppleSiliconHardwareProbe.CommandResult(0, output, "");
  }
}
