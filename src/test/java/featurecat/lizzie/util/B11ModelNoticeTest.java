package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class B11ModelNoticeTest {
  @TempDir Path directory;

  @Test
  void recognizesOfficialB11FamiliesWithoutGuessingFromNicknames() {
    for (String name :
        List.of(
            "kata1-tf3-b11c768-s12002M-d6304M", "kata1-tf3-b11c768-s11500M-d6163M.bin.gz",
            "b11c768h12nbt3tflrs-fson-silu.bin.gz", "KATA1-TF2-B11C768-S100-D200")) {
      assertTrue(B11ModelNotice.isB11(name), name);
    }
    for (String name :
        List.of(
            "",
            "B11",
            "My B11 engine",
            "default.bin.gz",
            "kata1-tf3-b110c768-s1-d1",
            "kata1-b11c768-s1-d1",
            "kata1-b28c512-s1-d1",
            "b10c512h8nbt3tflrs-fson-silu-rsnh")) {
      assertFalse(B11ModelNotice.isB11(name), name);
    }
    assertFalse(B11ModelNotice.isB11(null));
  }

  @Test
  void actualHeaderWinsOverRenamedDefaultOrMisleadingFilename() throws Exception {
    Path model = model("default.bin.gz", "kata1-tf3-b11c768-s12002M-d6304M");
    assertTrue(
        B11ModelNotice.readLocal(List.of("katago", "gtp", "-model", model.toString()), directory));
    Path other = model("kata1-tf3-b11c768-s1-d1.bin.gz", "kata1-b28c512-s1-d1");
    assertFalse(
        B11ModelNotice.readLocal(List.of("katago", "gtp", "-model", other.toString()), directory));
    Files.writeString(model, "not a model");
    assertFalse(
        B11ModelNotice.readLocal(List.of("katago", "gtp", "-model", model.toString()), directory));
  }

  @Test
  void supportsRelativeUnicodeSpacePathsAndNeverUsesTheHumanModel() throws Exception {
    Path model = model("custom 权重 space.bin.gz", "b11c768h12nbt3tflrs-fson-silu");
    assertTrue(
        B11ModelNotice.readLocal(
            List.of("katago", "gtp", "--model=" + model.getFileName()), directory));
    assertTrue(
        B11ModelNotice.readLocal(
            List.of("katago", "gtp", "--weights", model.getFileName().toString()), directory));
    assertFalse(
        B11ModelNotice.readLocal(
            List.of("katago", "gtp", "-human-model", model.toString()), directory));
    assertFalse(B11ModelNotice.readLocal(List.of("katago", "gtp", "-model"), directory));
    assertFalse(
        B11ModelNotice.readLocal(List.of("katago", "gtp", "-model", "missing.bin.gz"), directory));
  }

  @Test
  void indirectLaunchersCannotUseUnrelatedHostModelAsIdentity() throws Exception {
    Path hostModel = model("default.bin.gz", "kata1-tf3-b11c768-s12002M-d6304M");
    for (String launcher :
        List.of(
            "docker",
            "podman",
            "wsl.exe",
            "wslhost",
            "ssh",
            "plink.exe",
            "python3.12.exe",
            "pythonw.exe",
            "py",
            "pypy3",
            "java.exe",
            "javaw21",
            "node",
            "nodejs",
            "bun",
            "deno",
            "ruby3.2",
            "perl",
            "php8.3",
            "dotnet",
            "mono",
            "cscript",
            "wscript",
            "cmd.exe",
            "pwsh",
            "powershell",
            "bash",
            "sh",
            "env",
            "nohup",
            "wine",
            "wine64",
            "flatpak",
            "snap",
            "bridge.cmd",
            "bridge.bat",
            "bridge.ps1",
            "bridge.sh",
            "C:\\Program Files\\Python\\python3.12.exe",
            "/usr/bin/docker")) {
      assertFalse(
          B11ModelNotice.readLocal(
              List.of(launcher, "bridge", "gtp", "-model", hostModel.toString()), directory),
          launcher + " must not identify a model in another filesystem namespace");
      assertFalse(
          B11ModelNotice.local(
                  List.of(launcher, "bridge", "gtp", "--model=" + hostModel.getFileName()),
                  directory)
              .isB11(),
          launcher + " asynchronous entry must also remain unknown");
    }
  }

  @Test
  void directNativeLaunchStillReadsActualModelHeader() throws Exception {
    Path b11 = model("renamed.bin.gz", "kata1-tf3-b11c768-s12002M-d6304M");
    Path b10 = model("misleading-b11.bin.gz", "b10c512h8nbt3tflrs-fson-silu-rsnh");
    for (String executable :
        List.of("katago", "katago.exe", "C:\\engines with space\\katago.exe", "custom-engine")) {
      assertTrue(
          B11ModelNotice.readLocal(
              List.of(executable, "gtp", "-model", b11.getFileName().toString()), directory));
      assertFalse(
          B11ModelNotice.readLocal(
              List.of(executable, "gtp", "-model", b10.toString()), directory));
    }
    assertFalse(B11ModelNotice.local(List.of(), directory).isB11());
    assertFalse(B11ModelNotice.readLocal(List.of(), directory));
    assertFalse(B11ModelNotice.local(List.of(""), directory).isB11());
  }

  @Test
  void backgroundLookupIsNonblockingAndLateCompletionDoesNotChangeReplacement() {
    List<Runnable> queued = new ArrayList<>();
    AtomicInteger reads = new AtomicInteger();
    var old =
        B11ModelNotice.lookup(
            () -> {
              reads.incrementAndGet();
              return true;
            },
            queued::add);
    var replacement = B11ModelNotice.known(false);
    for (int i = 0; i < 1000; i++) assertFalse(old.isB11());
    assertEquals(0, reads.get());
    queued.get(0).run();
    assertTrue(old.isB11());
    assertFalse(replacement.isB11());
    for (int i = 0; i < 1000; i++) assertTrue(old.isB11());
    assertEquals(1, reads.get());
  }

  @Test
  void unreadableMetadataIsUnknownRatherThanBreakingPresentation() {
    var lookup =
        B11ModelNotice.lookup(
            () -> {
              throw new IllegalArgumentException("unavailable");
            },
            Runnable::run);
    assertFalse(lookup.isB11());
  }

  @Test
  void recognizesOnlyExplicitZhiziB11Identifier() {
    assertTrue(B11ModelNotice.isZhiziB11("11b768t"));
    for (String other : List.of("", "28bnbt", "10b512t", "fdx", "B11 cloud")) {
      assertFalse(B11ModelNotice.isZhiziB11(other));
    }
    assertFalse(B11ModelNotice.isZhiziB11(null));
  }

  private Path model(String name, String identity) throws Exception {
    Path path = directory.resolve(name);
    try (var stream = new GZIPOutputStream(Files.newOutputStream(path))) {
      stream.write((identity + "\n17\n22\n19\n").getBytes(StandardCharsets.US_ASCII));
    }
    return path;
  }
}
