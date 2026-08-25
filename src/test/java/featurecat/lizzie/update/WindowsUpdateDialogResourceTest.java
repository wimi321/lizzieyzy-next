package featurecat.lizzie.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class WindowsUpdateDialogResourceTest {
  @Test
  void englishBundleContainsWindowsUpdateKeys() {
    ResourceBundle bundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);

    assertEquals("New version available", bundle.getString("WindowsUpdate.title"));
    assertEquals("Current version", bundle.getString("WindowsUpdate.currentVersion"));
    assertEquals("Update now", bundle.getString("WindowsUpdate.btnUpdate"));
    assertEquals("Close", bundle.getString("WindowsUpdate.btnLater"));
    assertEquals("View release", bundle.getString("WindowsUpdate.btnRelease"));
    assertEquals(
        "The update is downloaded and verified first; the app then closes and a standalone"
            + " updater replaces the files and restarts it.",
        bundle.getString("WindowsUpdate.summary.intro"));
    assertEquals("Core update download", bundle.getString("WindowsUpdate.summary.coreSize"));
    assertEquals(
        "Large resources to update", bundle.getString("WindowsUpdate.summary.resourceSize"));
    assertEquals(
        "No large resources to download this time: KataGo, weights, the runtime, the sync tool"
            + " and the browser component are all kept.",
        bundle.getString("WindowsUpdate.summary.noResource"));
    assertEquals("Total download", bundle.getString("WindowsUpdate.summary.totalDownload"));
    assertEquals("Components to update:", bundle.getString("WindowsUpdate.summary.components"));
    assertEquals(
        "Unchanged large resources are not downloaded again. User data, game records, settings,"
            + " downloaded weights and TensorRT are preserved.",
        bundle.getString("WindowsUpdate.summary.preserved"));
    assertEquals("Core update", bundle.getString("WindowsUpdate.component.core"));
    assertEquals("KataGo CPU engine", bundle.getString("WindowsUpdate.component.katagoCpu"));
    assertEquals("KataGo OpenCL engine", bundle.getString("WindowsUpdate.component.katagoOpencl"));
    assertEquals("KataGo NVIDIA engine", bundle.getString("WindowsUpdate.component.katagoNvidia"));
    assertEquals(
        "KataGo RTX 50 CUDA engine", bundle.getString("WindowsUpdate.component.katagoNvidia50"));
    assertEquals("Default weights", bundle.getString("WindowsUpdate.component.weightDefault"));
    assertEquals("Board sync tool", bundle.getString("WindowsUpdate.component.readboard"));
    assertEquals("Embedded browser runtime", bundle.getString("WindowsUpdate.component.jcef"));
    assertEquals("Java runtime", bundle.getString("WindowsUpdate.component.javaRuntime"));
    assertEquals("Preparing download...", bundle.getString("WindowsUpdate.status.preparing"));
    assertEquals("Update failed", bundle.getString("WindowsUpdate.status.failed"));
    assertEquals(
        "Download complete, launching the updater...",
        bundle.getString("WindowsUpdate.status.launching"));
    assertEquals(
        "Failed to launch the updater", bundle.getString("WindowsUpdate.status.launchFailed"));
    assertEquals(
        "Could not open the release page",
        bundle.getString("WindowsUpdate.status.openReleaseFailed"));
    assertEquals(
        "There is no newer version on the official channel.",
        bundle.getString("WindowsUpdate.noUpdate.stable"));
    assertEquals(
        "There is no newer version on the test channel.",
        bundle.getString("WindowsUpdate.noUpdate.beta"));
    assertEquals(
        "Could not check the official channel. Check your network and retry.",
        bundle.getString("WindowsUpdate.fetchFailed.stable"));
    assertEquals(
        "Could not check the test channel. GitHub must be reachable.",
        bundle.getString("WindowsUpdate.fetchFailed.beta"));
    assertEquals("Update channel", bundle.getString("WindowsUpdate.page.channel"));
    assertEquals("Official", bundle.getString("WindowsUpdate.channel.stable"));
    assertEquals("Test", bundle.getString("WindowsUpdate.channel.beta"));
    assertEquals("Check update", bundle.getString("WindowsUpdate.btnCheck"));
    assertEquals("Update source", bundle.getString("WindowsUpdate.page.source"));
    assertEquals("Official site", bundle.getString("WindowsUpdate.source.official"));
    assertEquals("GitHub", bundle.getString("WindowsUpdate.source.github"));
  }

  @Test
  void chineseBundleContainsWindowsUpdateKeys() {
    ResourceBundle bundle =
        ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);

    assertEquals("发现新版本", bundle.getString("WindowsUpdate.title"));
    assertEquals("当前版本", bundle.getString("WindowsUpdate.currentVersion"));
    assertEquals("立即更新", bundle.getString("WindowsUpdate.btnUpdate"));
    assertEquals("关闭", bundle.getString("WindowsUpdate.btnLater"));
    assertEquals("查看 Release", bundle.getString("WindowsUpdate.btnRelease"));
    assertEquals(
        "这次会先下载并校验更新文件，然后关闭当前程序，由独立更新器替换文件并重新打开。", bundle.getString("WindowsUpdate.summary.intro"));
    assertEquals("本次主程序小更新", bundle.getString("WindowsUpdate.summary.coreSize"));
    assertEquals("本次需要更新的大资源", bundle.getString("WindowsUpdate.summary.resourceSize"));
    assertEquals(
        "大资源本次无需下载: KataGo、权重、运行环境、同步工具和浏览器组件都会保留。",
        bundle.getString("WindowsUpdate.summary.noResource"));
    assertEquals("总下载", bundle.getString("WindowsUpdate.summary.totalDownload"));
    assertEquals("将更新的组件:", bundle.getString("WindowsUpdate.summary.components"));
    assertEquals(
        "未变化的大资源不会重复下载。用户数据、棋谱、设置、下载权重和 TensorRT 会保留。",
        bundle.getString("WindowsUpdate.summary.preserved"));
    assertEquals("主程序小更新", bundle.getString("WindowsUpdate.component.core"));
    assertEquals("KataGo CPU 引擎", bundle.getString("WindowsUpdate.component.katagoCpu"));
    assertEquals("KataGo OpenCL 引擎", bundle.getString("WindowsUpdate.component.katagoOpencl"));
    assertEquals("KataGo NVIDIA 引擎", bundle.getString("WindowsUpdate.component.katagoNvidia"));
    assertEquals(
        "KataGo RTX 50 CUDA 引擎", bundle.getString("WindowsUpdate.component.katagoNvidia50"));
    assertEquals("默认权重", bundle.getString("WindowsUpdate.component.weightDefault"));
    assertEquals("棋盘同步工具", bundle.getString("WindowsUpdate.component.readboard"));
    assertEquals("内置浏览器运行时", bundle.getString("WindowsUpdate.component.jcef"));
    assertEquals("Java 运行时", bundle.getString("WindowsUpdate.component.javaRuntime"));
    assertEquals("准备下载...", bundle.getString("WindowsUpdate.status.preparing"));
    assertEquals("更新失败", bundle.getString("WindowsUpdate.status.failed"));
    assertEquals("下载完成，正在启动更新器...", bundle.getString("WindowsUpdate.status.launching"));
    assertEquals("启动更新器失败", bundle.getString("WindowsUpdate.status.launchFailed"));
    assertEquals("无法打开 Release 页面", bundle.getString("WindowsUpdate.status.openReleaseFailed"));
    assertEquals("正式通道暂无更新版本。", bundle.getString("WindowsUpdate.noUpdate.stable"));
    assertEquals("测试通道暂无更新版本。", bundle.getString("WindowsUpdate.noUpdate.beta"));
    assertEquals("无法检查正式通道更新，请检查网络后重试。", bundle.getString("WindowsUpdate.fetchFailed.stable"));
    assertEquals(
        "无法检查测试通道更新，请确认可以访问 GitHub。", bundle.getString("WindowsUpdate.fetchFailed.beta"));
    assertEquals("更新通道", bundle.getString("WindowsUpdate.page.channel"));
    assertEquals("正式", bundle.getString("WindowsUpdate.channel.stable"));
    assertEquals("测试", bundle.getString("WindowsUpdate.channel.beta"));
    assertEquals("检查更新", bundle.getString("WindowsUpdate.btnCheck"));
    assertEquals("更新源", bundle.getString("WindowsUpdate.page.source"));
    assertEquals("官网", bundle.getString("WindowsUpdate.source.official"));
    assertEquals("GitHub", bundle.getString("WindowsUpdate.source.github"));
  }

  @Test
  void traditionalChineseBundlesContainWindowsUpdateKeys() {
    ResourceBundle tw = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.TRADITIONAL_CHINESE);
    assertEquals("發現新版本", tw.getString("WindowsUpdate.title"));
    assertEquals("Java 執行環境", tw.getString("WindowsUpdate.component.javaRuntime"));

    ResourceBundle hk =
        ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag("zh-HK"));
    assertEquals("發現新版本", hk.getString("WindowsUpdate.title"));
    assertEquals("啟動更新器失敗", hk.getString("WindowsUpdate.status.launchFailed"));
    tw.getString("WindowsUpdate.page.source");
    tw.getString("WindowsUpdate.source.official");
    tw.getString("WindowsUpdate.source.github");
    hk.getString("WindowsUpdate.page.source");
    hk.getString("WindowsUpdate.source.official");
    hk.getString("WindowsUpdate.source.github");
  }
}
