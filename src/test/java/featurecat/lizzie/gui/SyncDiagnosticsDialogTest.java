package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.SyncDecisionTrace;
import featurecat.lizzie.analysis.SyncDiagnosticsReport;
import featurecat.lizzie.analysis.SyncDiagnosticsSnapshot;
import featurecat.lizzie.analysis.YikeSessionDiagnosticsSnapshot;
import java.awt.FontMetrics;
import java.awt.GridLayout;
import java.nio.file.Path;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import org.junit.jupiter.api.Test;

class SyncDiagnosticsDialogTest {
  @Test
  void exportSuccessStatusSanitizesUserPathBeforeItCanBeCopied() {
    Path zip =
        Path.of(
            "/home/Alice Smith/.lizzie-yzy/sync-diagnostics/sync-diagnostics-20260601-125136.zip");

    String status = SyncDiagnosticsDialog.exportSuccessStatus("Exported to:", zip);

    assertTrue(status.contains("Exported to:"));
    assertTrue(status.contains("/home/<user>/sync-diagnostics-20260601-125136.zip"));
    assertFalse(status.contains("Alice Smith"));
    assertFalse(status.contains(".lizzie-yzy"));
    assertFalse(status.contains("sync-diagnostics/"));
  }

  @Test
  void exportSuccessStatusShowsProjectRelativePathForDefaultExportFolder() {
    Path zip =
        Path.of(
            "/home/Alice Smith/dev/lizzieyzy-next/sync-diagnostics/sync-diagnostics-20260601-125136.zip");

    String status = SyncDiagnosticsDialog.exportSuccessStatus("Exported to:", zip);

    assertTrue(status.contains("lizzieyzy-next/sync-diagnostics/"));
    assertTrue(status.contains("sync-diagnostics-20260601-125136.zip"));
    assertFalse(status.contains("Alice Smith"));
    assertFalse(status.contains("/home/Alice Smith"));
  }

  @Test
  void exportSuccessStatusShowsBuildRootRelativePathForCopiedWindowsBuilds() {
    Path zip =
        Path.of(
            "/tmp/lizzieyzy-next-sync-diagnostics-export-build-20260601/sync-diagnostics/sync-diagnostics-20260601-125136.zip");

    String status = SyncDiagnosticsDialog.exportSuccessStatus("Exported to:", zip);

    assertTrue(
        status.contains("lizzieyzy-next-sync-diagnostics-export-build-20260601/sync-diagnostics/"));
    assertTrue(status.contains("sync-diagnostics-20260601-125136.zip"));
    assertFalse(status.contains("/tmp/"));
  }

  @Test
  void footerButtonsDisableHoverAndFocusPaintArtifacts() {
    JButton button = new JButton("Export package");

    SyncDiagnosticsDialog.configureFooterButton(button);

    assertNotEquals(BasicButtonUI.class, button.getUI().getClass());
    assertTrue(button.isFocusable());
    assertTrue(button.isFocusPainted());
    assertFalse(button.isRolloverEnabled());
    assertFalse(button.isContentAreaFilled());
    assertFalse(button.isBorderPainted());
    assertTrue(button.getMargin().left >= 12);
    assertTrue(button.getMargin().right >= 12);
    assertTrue(button.getMinimumSize().width >= button.getPreferredSize().width);
    assertTrue(button.getMinimumSize().height >= button.getPreferredSize().height);
  }

  @Test
  void footerButtonSizeLeavesRoomForChineseLabels() {
    JButton button = new JButton("复制诊断摘要");

    SyncDiagnosticsDialog.configureFooterButton(button);

    FontMetrics metrics = button.getFontMetrics(button.getFont());
    int requiredWidth =
        metrics.stringWidth(button.getText())
            + button.getMargin().left
            + button.getMargin().right
            + 16;
    int requiredHeight =
        metrics.getHeight() + button.getMargin().top + button.getMargin().bottom + 6;
    assertTrue(button.getPreferredSize().width >= requiredWidth);
    assertTrue(button.getPreferredSize().height >= requiredHeight);
  }

  @Test
  void sectionsPanelBuildsFourVisibleDiagnosticAreasInTwoColumns() {
    JTextArea readBoard = new JTextArea();
    JTextArea yike = new JTextArea();
    JTextArea latestDecision = new JTextArea();
    JTextArea analysis = new JTextArea();

    JPanel panel =
        SyncDiagnosticsDialog.createSectionsPanel(
            readBoard,
            yike,
            latestDecision,
            analysis,
            "ReadBoard / sync status",
            "Yike session / placement geometry",
            "Latest sync decision",
            "Analysis resume / snapshot state");

    assertTrue(panel.getLayout() instanceof GridLayout);
    assertEquals(2, ((GridLayout) panel.getLayout()).getColumns());
    assertEquals(4, panel.getComponentCount());
    assertSection(panel, 0, "ReadBoard / sync status", readBoard);
    assertSection(panel, 1, "Yike session / placement geometry", yike);
    assertSection(panel, 2, "Latest sync decision", latestDecision);
    assertSection(panel, 3, "Analysis resume / snapshot state", analysis);
  }

  @Test
  void sectionTextsSplitReportIntoVisiblePanelsAndKeepCopySummaryComplete() {
    SyncDecisionTrace decision =
        SyncDecisionTrace.builder("FORCE_REBUILD", "first_sync_force_rebuild")
            .platform("FOX")
            .windowKind("LIVE_ROOM")
            .source("ReadBoard.syncBoardStones")
            .summary("force rebuild summary")
            .remoteContextFingerprint("platform=FOX, windowKind=LIVE_ROOM")
            .snapshotHash("05056846")
            .changedStoneCount(197)
            .removedStoneCount(0)
            .recoveryMoveNumber(203)
            .resolvedSnapshotMoveNumber(-1)
            .resolvedSnapshotKind("none")
            .firstSyncFrame(true)
            .shouldResumeAnalysis(false)
            .epoch(1)
            .timestampMillis(1780303912407L)
            .build();
    SyncDiagnosticsSnapshot sync =
        SyncDiagnosticsSnapshot.builder()
            .readBoardAttached(true)
            .readBoardConnected(true)
            .usePipe(false)
            .syncing(false)
            .awaitingFirstSyncFrame(false)
            .hasResumeState(true)
            .hasLastResolvedSnapshotNode(false)
            .syncAnalysisEpoch(7)
            .pendingRemoteContextSummary("platform=FOX, windowKind=LIVE_ROOM")
            .lastResolvedSnapshotSummary("none")
            .lastProtocolLineSummary("readBoardUpdateReady captured")
            .lastProtocolTimestampMillis(1780303912000L)
            .source("readboard")
            .summary("readboard ready in /Users/Alice Smith/Lizzie")
            .timestampMillis(1780303912400L)
            .latestDecisionTrace(decision)
            .build();
    YikeSessionDiagnosticsSnapshot yike =
        YikeSessionDiagnosticsSnapshot.builder()
            .listenerEnabled(true)
            .currentRouteKind("live-room")
            .currentSessionKey("live-room:186538")
            .activeSessionKey("live-room:186538")
            .activeSyncReady(true)
            .activeGeometryReady(false)
            .activeBoardSize(19)
            .pendingSessionKey("none")
            .pendingSyncReady(null)
            .pendingGeometryReady(null)
            .pendingBoardSize(0)
            .effectiveGeometrySessionKey("live-room:186538")
            .effectiveGeometryReady(false)
            .placementGeometryAllowed(false)
            .lastGeometryClearReason("room_changed")
            .lastSessionSwitchReason("https://www.yikeweiqi.com/live/186538?roomToken=abc123")
            .lastYikeDebugEventSummary(
                "geometry waiting for https://www.yikeweiqi.com/live/186538?roomToken=abc123")
            .source("online-dialog")
            .summary("yike waiting for live-room:186538")
            .timestampMillis(1780303912300L)
            .build();
    SyncDiagnosticsReport report =
        SyncDiagnosticsReport.builder()
            .syncSnapshot(sync)
            .yikeSnapshot(yike)
            .latestDecisionTrace(decision)
            .capturedAtMillis(1780303912500L)
            .source("test")
            .build();

    SyncDiagnosticsDialog.SectionTexts sections = SyncDiagnosticsDialog.sectionTexts(report);

    assertTrue(sections.overview.contains("capturedAtMillis: 1780303912500"));
    assertTrue(sections.readBoard.contains("attached: true"));
    assertTrue(sections.readBoard.contains("lastProtocolLine: readBoardUpdateReady captured"));
    assertFalse(sections.readBoard.contains("active: live-room:186538"));
    assertTrue(sections.yike.contains("active: live-room#1"));
    assertTrue(sections.yike.contains("placementGeometryAllowed: false"));
    assertTrue(sections.latestDecision.contains("result: FORCE_REBUILD"));
    assertTrue(sections.latestDecision.contains("reason: first_sync_force_rebuild"));
    assertTrue(sections.analysis.contains("hasResumeState: true"));
    assertTrue(sections.analysis.contains("syncAnalysisEpoch: 7"));
    assertTrue(sections.analysis.contains("shouldResumeAnalysis: false"));
    assertTrue(sections.copySummary.contains("Sync Diagnostics"));
    assertTrue(sections.copySummary.contains("Latest decision"));
    assertTrue(sections.copySummary.contains("readBoardUpdateReady captured"));
    String copiedAndVisible =
        String.join(
            "\n",
            sections.overview,
            sections.readBoard,
            sections.yike,
            sections.latestDecision,
            sections.analysis,
            sections.copySummary);
    assertTrue(copiedAndVisible.contains("live-room#1"));
    assertFalse(copiedAndVisible.contains("live-room:186538"));
    assertFalse(copiedAndVisible.contains("186538"));
    assertFalse(copiedAndVisible.contains("abc123"));
    assertFalse(copiedAndVisible.contains("roomToken"));
    assertFalse(copiedAndVisible.contains("https://"));
    assertFalse(copiedAndVisible.contains("Alice Smith"));
  }

  private static void assertSection(
      JPanel panel, int index, String expectedTitle, JTextArea expectedTextArea) {
    assertTrue(panel.getComponent(index) instanceof JPanel);
    JPanel section = (JPanel) panel.getComponent(index);
    assertTrue(section.getBorder() instanceof TitledBorder);
    assertEquals(expectedTitle, ((TitledBorder) section.getBorder()).getTitle());
    assertEquals(1, section.getComponentCount());
    assertSame(expectedTextArea, section.getComponent(0));
  }
}
