package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RemoteComputeConfigTest {
  @Test
  void displayNameIncludesZhiziGpuType() {
    assertEquals(
        "智子云算力 VIP包月 · 智子28B · TensorRT",
        RemoteComputeConfig.displayNameForZhiziArgs(RemoteComputeConfig.DEFAULT_ZHIZI_ARGS));
    assertEquals(
        "智子云算力 按量1x · 智子28B · TensorRT",
        RemoteComputeConfig.displayNameForZhiziArgs(RemoteComputeConfig.ON_DEMAND_1X_ZHIZI_ARGS));
    assertEquals(
        "智子云算力 按量3x · 智子28B · TensorRT",
        RemoteComputeConfig.displayNameForZhiziArgs(RemoteComputeConfig.FASTER_ZHIZI_ARGS));
    assertEquals(
        "智子云算力 VIP包月 · 智子28B · TensorRT",
        RemoteComputeConfig.displayNameForZhiziArgs(RemoteComputeConfig.VIP_ZHIZI_ARGS));
  }

  @Test
  void gpuTypeParserReadsVipShareAndFallsBackForBlankArgs() {
    assertEquals("vip-share", RemoteComputeConfig.gpuTypeForArgs(RemoteComputeConfig.VIP_ZHIZI_ARGS));
    assertEquals("vip-share", RemoteComputeConfig.gpuTypeForArgs(""));
  }

  @Test
  void vipFailureMessageSuggestsSwitchingToOnDemand() {
    String message =
        RemoteComputeConfig.friendlyZhiziErrorMessage(
            "no worker available", RemoteComputeConfig.DEFAULT_ZHIZI_ARGS);

    assertEquals(
        "no worker available\n\n"
            + "当前账号可能未开通 VIP 包月，或 VIP 算力暂时没有可用 worker。"
            + "请在高级设置切换到“按量 1x”，或检查智子账号套餐。",
        message);
    assertEquals(
        "no worker available",
        RemoteComputeConfig.friendlyZhiziErrorMessage(
            "no worker available", RemoteComputeConfig.ON_DEMAND_1X_ZHIZI_ARGS));
  }
}
