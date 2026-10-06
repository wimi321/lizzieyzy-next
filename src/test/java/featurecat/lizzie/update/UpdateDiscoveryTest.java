package featurecat.lizzie.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class UpdateDiscoveryTest {
  private static final String INSTALLED = "next-2026-08-01.1";
  private static final String NEWER = "next-2026-08-24.1";
  private static final String OLDER = "next-2026-07-01.1";

  @TempDir Path tempDir;

  private featurecat.lizzie.Config previousConfig;
  private String previousVersion;

  @BeforeEach
  void setUp() {
    previousConfig = Lizzie.config;
    previousVersion = Lizzie.nextVersion;
    Lizzie.config = null;
  }

  @AfterEach
  void tearDown() {
    Lizzie.config = previousConfig;
    Lizzie.nextVersion = previousVersion;
  }

  @Test
  void developmentBuildIsUnavailableWithoutFetch() {
    AtomicInteger fetches = new AtomicInteger();
    UpdateDiscovery discovery =
        windowsDiscovery(
            selection -> {
              fetches.incrementAndGet();
              return signed(official(NEWER, false));
            });

    UpdateCheckResult result =
        discovery.discover(
            UpdateCheckSelection.of(UpdateChannel.STABLE, UpdateSource.OFFICIAL_SITE, "next-dev"));

    assertEquals(UpdateCheckResult.Reason.UNAVAILABLE_BUILD, result.reason);
    assertNull(result.failureKind);
    assertNull(result.windowsPlan);
    assertEquals(0, fetches.get());
  }

  @Test
  void officialChannelRejectsTestManifestAsNoUpdate() {
    UpdateCheckResult result =
        windowsDiscovery(selection -> signed(official(NEWER, true)))
            .discover(officialSelection(INSTALLED));

    assertEquals(UpdateCheckResult.Reason.NO_UPDATE, result.reason);
    assertNull(result.windowsPlan);
    assertNull(result.failureKind);
  }

  @Test
  void testChannelAdmitsNewerSignedTestManifest() {
    UpdateCheckResult result =
        windowsDiscovery(selection -> signed(official(NEWER, true)))
            .discover(
                UpdateCheckSelection.of(UpdateChannel.BETA, UpdateSource.OFFICIAL_SITE, INSTALLED));

    assertEquals(UpdateCheckResult.Reason.OFFER, result.reason);
    assertEquals(NEWER, result.windowsPlan.manifest.releaseTag);
    assertTrue(result.windowsPlan.hasUpdate());
  }

  @Test
  void testChannelAdmitsOfficialCandidateButRejectsOfficialPointer() {
    UpdateCheckResult result =
        windowsDiscovery(selection -> signed(official(NEWER, false)))
            .discover(UpdateCheckSelection.of(UpdateChannel.BETA, UpdateSource.GITHUB, INSTALLED));

    assertEquals(UpdateCheckResult.Reason.OFFER, result.reason);
    assertEquals(NEWER, result.windowsPlan.manifest.releaseTag);
    assertTrue(!result.windowsPlan.manifest.prerelease);
  }

  @Test
  void testChannelRejectsUnsignedLegacyManifest() {
    UpdateManifest unsigned = UpdateManifest.parse(UpdateManifestTest.validManifest());
    UpdateCheckResult result =
        windowsDiscovery(
                selection -> new UpdateManifestClient.FetchResult(unsigned, "test://legacy", false))
            .discover(UpdateCheckSelection.of(UpdateChannel.BETA, UpdateSource.GITHUB, INSTALLED));

    assertEquals(UpdateCheckResult.Reason.FAILURE, result.reason);
    assertEquals(UpdateCheckResult.FailureKind.INVALID_CANDIDATE, result.stableCandidateFailure);
    assertEquals(UpdateCheckResult.FailureKind.INVALID_CANDIDATE, result.betaCandidateFailure);
  }

  @Test
  void officialChannelAdmitsUnsignedLegacyNewerManifest() {
    JSONObject payload = UpdateManifestTest.validManifest();
    payload.put("releaseTag", NEWER);
    payload.getJSONArray("components").getJSONObject(0).put("version", NEWER);
    UpdateManifest unsigned = UpdateManifest.parse(payload);

    UpdateCheckResult result =
        windowsDiscovery(
                selection -> new UpdateManifestClient.FetchResult(unsigned, "test://legacy", false))
            .discover(officialSelection(INSTALLED));

    assertEquals(UpdateCheckResult.Reason.OFFER, result.reason);
    assertEquals(NEWER, result.windowsPlan.manifest.releaseTag);
  }

  @Test
  void bothChannelsReportNoUpdateForEqualOrOlderTags() {
    assertEquals(
        UpdateCheckResult.Reason.NO_UPDATE,
        windowsDiscovery(selection -> signed(official(INSTALLED, false)))
            .discover(officialSelection(INSTALLED))
            .reason);
    assertEquals(
        UpdateCheckResult.Reason.NO_UPDATE,
        windowsDiscovery(selection -> signed(official(OLDER, false)))
            .discover(officialSelection(INSTALLED))
            .reason);
    assertEquals(
        UpdateCheckResult.Reason.NO_UPDATE,
        windowsDiscovery(selection -> signed(official(INSTALLED, true)))
            .discover(UpdateCheckSelection.of(UpdateChannel.BETA, UpdateSource.GITHUB, INSTALLED))
            .reason);
    assertEquals(
        UpdateCheckResult.Reason.NO_UPDATE,
        windowsDiscovery(selection -> signed(official(INSTALLED, true)))
            .discover(UpdateCheckSelection.of(UpdateChannel.BETA, UpdateSource.GITHUB, NEWER))
            .reason);
  }

  @Test
  void officialChannelDoesNotDowngradeFromNewerTestInstall() {
    UpdateCheckResult result =
        windowsDiscovery(selection -> signed(official(INSTALLED, false)))
            .discover(officialSelection(NEWER));

    assertEquals(UpdateCheckResult.Reason.NO_UPDATE, result.reason);
    assertNull(result.windowsPlan);
  }

  @Test
  void fetchFailureIsFailureNotNoUpdate() {
    UpdateCheckResult result =
        windowsDiscovery(
                selection -> {
                  throw new IOException("HTTP 404");
                })
            .discover(officialSelection(INSTALLED));

    assertEquals(UpdateCheckResult.Reason.FAILURE, result.reason);
    assertEquals(UpdateCheckResult.FailureKind.FETCH, result.failureKind);
    assertNull(result.windowsPlan);
  }

  @Test
  void unsupportedPlatformIsDistinctFromFailureAndNoUpdate() {
    AtomicInteger fetches = new AtomicInteger();
    UpdateDiscovery discovery =
        new UpdateDiscovery(
            selection -> {
              fetches.incrementAndGet();
              return signed(official(NEWER, false));
            },
            List.of());

    UpdateCheckResult result = discovery.discover(officialSelection(INSTALLED));

    assertEquals(UpdateCheckResult.Reason.UNSUPPORTED_PLATFORM, result.reason);
    assertNull(result.failureKind);
    assertEquals(0, fetches.get());
  }

  @Test
  void newerReleaseWithNoMatchingWindowsAssetIsNoPackage() {
    JSONObject payload = SignedUpdateEnvelopeTest.validPayload();
    payload.put("releaseTag", NEWER);
    payload.put("prerelease", false);
    payload.getJSONArray("components").getJSONObject(0).put("platform", "linux");
    UpdateManifest linuxOnly = UpdateManifest.parse(payload);

    UpdateCheckResult result =
        windowsDiscovery(selection -> signed(linuxOnly)).discover(officialSelection(INSTALLED));

    assertEquals(UpdateCheckResult.Reason.NO_PACKAGE, result.reason);
    assertNull(result.windowsPlan);
    assertNull(result.failureKind);
  }

  @Test
  void windowsOfferCarriesExistingWindowsPlan() {
    UpdateCheckResult result =
        windowsDiscovery(selection -> signed(official(NEWER, false)))
            .discover(officialSelection(INSTALLED));

    assertEquals(UpdateCheckResult.Reason.OFFER, result.reason);
    assertNotNull(result.windowsPlan);
    assertEquals(NEWER, result.windowsPlan.manifest.releaseTag);
    assertEquals(INSTALLED, result.windowsPlan.currentVersion);
    assertTrue(result.windowsPlan.hasUpdate());
    assertEquals("core", result.windowsPlan.selectedItems().get(0).component.id);
    assertNull(result.packagePlan);
  }

  @Test
  void packageOfferCarriesExistingPackagePlan() {
    UpdateCheckResult result =
        packageDiscovery(selection -> signed(official(NEWER, false)))
            .discover(officialSelection(INSTALLED));

    assertEquals(UpdateCheckResult.Reason.OFFER, result.reason);
    assertNotNull(result.packagePlan);
    assertEquals(NEWER, result.packagePlan.manifest.releaseTag);
    assertEquals(INSTALLED, result.packagePlan.currentVersion);
    assertEquals("macos", result.packagePlan.platform);
    assertEquals("arm64", result.packagePlan.arch);
    assertEquals("with-katago", result.packagePlan.flavor);
    assertEquals("2026-08-03-mac-arm64.with-katago.dmg", result.packagePlan.packageAsset.assetName);
    assertNull(result.windowsPlan);
    assertNull(result.failureKind);
  }

  @Test
  void newerReleaseWithNoMatchingPackageAssetIsNoPackage() {
    UpdateDiscovery discovery =
        new UpdateDiscovery(
            selection -> signed(official(NEWER, false)),
            List.of(new PackageUpdateAdapter(true, "linux", "x64", "opencl")));

    UpdateCheckResult result = discovery.discover(officialSelection(INSTALLED));

    assertEquals(UpdateCheckResult.Reason.NO_PACKAGE, result.reason);
    assertNull(result.packagePlan);
    assertNull(result.windowsPlan);
    assertNull(result.failureKind);
  }

  @Test
  void checkUsesSelectionSnapshotNotMutatedGlobalConfig() {
    Lizzie.config = ConfigTestHelper.createForTests(tempDir.resolve("snapshot-config"));
    Lizzie.config.uiConfig = new org.json.JSONObject();
    UpdateChannel.persist(UpdateChannel.STABLE);
    UpdateSource.persist(UpdateSource.OFFICIAL_SITE);
    Lizzie.nextVersion = "next-dev";

    List<UpdateCheckSelection> seen = new ArrayList<>();
    UpdateDiscovery discovery =
        windowsDiscovery(
            selection -> {
              seen.add(selection);
              UpdateChannel.persist(UpdateChannel.BETA);
              UpdateSource.persist(UpdateSource.GITHUB);
              Lizzie.nextVersion = NEWER;
              return signed(official(NEWER, false));
            });

    UpdateCheckResult result = discovery.discover(officialSelection(INSTALLED));

    assertEquals(UpdateCheckResult.Reason.OFFER, result.reason);
    assertEquals(1, seen.size());
    assertEquals(UpdateChannel.STABLE, seen.get(0).channel);
    assertEquals(UpdateSource.OFFICIAL_SITE, seen.get(0).effectiveSource);
    assertEquals(INSTALLED, seen.get(0).installedVersion);
    assertEquals(UpdateChannel.BETA, UpdateChannel.current());
    assertEquals(UpdateSource.GITHUB, UpdateSource.current());
  }

  @Test
  void testChannelEffectiveGithubSourceDoesNotOverwriteOfficialSource() {
    Lizzie.config = ConfigTestHelper.createForTests(tempDir.resolve("source-isolation"));
    Lizzie.config.uiConfig = new org.json.JSONObject();
    UpdateSource.persist(UpdateSource.OFFICIAL_SITE);
    UpdateChannel.persist(UpdateChannel.STABLE);

    List<UpdateCheckSelection> seen = new ArrayList<>();
    UpdateCheckResult result =
        windowsDiscovery(
                selection -> {
                  seen.add(selection);
                  return signed(official(NEWER, true));
                })
            .discover(
                UpdateCheckSelection.of(UpdateChannel.BETA, UpdateSource.OFFICIAL_SITE, INSTALLED));

    assertEquals(UpdateCheckResult.Reason.OFFER, result.reason);
    assertEquals(UpdateSource.GITHUB, seen.get(0).effectiveSource);
    assertEquals(UpdateSource.OFFICIAL_SITE, UpdateSource.current());
    assertEquals(UpdateChannel.STABLE, UpdateChannel.current());
  }

  @Test
  void unexpectedAdapterFailureIsFailureWithoutUiCopy() {
    UpdateDiscovery discovery =
        new UpdateDiscovery(
            selection -> signed(official(NEWER, false)),
            List.of(
                new UpdateDiscovery.PlatformAdapter() {
                  @Override
                  public boolean supports(UpdateCheckSelection selection) {
                    return true;
                  }

                  @Override
                  public UpdateCheckResult plan(
                      UpdateCheckSelection selection, UpdateManifest manifest) {
                    throw new IllegalStateException("disk exploded");
                  }
                }));

    UpdateCheckResult result = discovery.discover(officialSelection(INSTALLED));

    assertEquals(UpdateCheckResult.Reason.FAILURE, result.reason);
    assertEquals(UpdateCheckResult.FailureKind.ADAPTER, result.failureKind);
    assertNull(result.windowsPlan);
  }

  @Test
  void unexpectedAdapterSupportFailureIsFailureWithoutUiCopy() {
    UpdateDiscovery discovery =
        new UpdateDiscovery(
            selection -> signed(official(NEWER, false)),
            List.of(
                new UpdateDiscovery.PlatformAdapter() {
                  @Override
                  public boolean supports(UpdateCheckSelection selection) {
                    throw new IllegalStateException("probe failed");
                  }

                  @Override
                  public UpdateCheckResult plan(
                      UpdateCheckSelection selection, UpdateManifest manifest) {
                    return UpdateCheckResult.offerWindows(null);
                  }
                }));

    UpdateCheckResult result = discovery.discover(officialSelection(INSTALLED));

    assertEquals(UpdateCheckResult.Reason.FAILURE, result.reason);
    assertEquals(UpdateCheckResult.FailureKind.ADAPTER, result.failureKind);
    assertNull(result.windowsPlan);
  }

  @Test
  void unexpectedFetchFailureIsFailure() {
    UpdateCheckResult result =
        windowsDiscovery(
                selection -> {
                  throw new IllegalStateException("boom");
                })
            .discover(officialSelection(INSTALLED));

    assertEquals(UpdateCheckResult.Reason.FAILURE, result.reason);
    assertEquals(UpdateCheckResult.FailureKind.UNEXPECTED, result.failureKind);
  }

  @Test
  void testChannelSelectionFixesGithubWithoutWritingConfig() {
    Lizzie.config = ConfigTestHelper.createForTests(tempDir.resolve("selection-config"));
    Lizzie.config.uiConfig = new org.json.JSONObject();
    UpdateSource.persist(UpdateSource.OFFICIAL_SITE);

    UpdateCheckSelection selection =
        UpdateCheckSelection.of(UpdateChannel.BETA, UpdateSource.OFFICIAL_SITE, INSTALLED);

    assertEquals(UpdateChannel.BETA, selection.channel);
    assertEquals(UpdateSource.GITHUB, selection.effectiveSource);
    assertEquals(UpdateSource.OFFICIAL_SITE, UpdateSource.current());
  }

  @Test
  void directTestChannelConstructorFixesGithubWithoutWritingConfig() {
    UpdateCheckSelection selection =
        new UpdateCheckSelection(UpdateChannel.BETA, UpdateSource.OFFICIAL_SITE, INSTALLED);

    assertEquals(UpdateChannel.BETA, selection.channel);
    assertEquals(UpdateSource.GITHUB, selection.effectiveSource);
  }

  @ParameterizedTest
  @CsvSource({
    "next-2026-09-26.2,next-2026-09-26.1,next-2026-09-26.2,false",
    "next-2026-09-26.2,next-2026-09-30.1,next-2026-09-30.1,true",
    "next-2026-09-26.2,next-2026-09-26.10,next-2026-09-26.10,true",
    "next-2026-09-26.2,next-2026-09-26.2,next-2026-09-26.2,false"
  })
  void betaChoosesHighestVersionWithOfficialTiePriority(
      String stable, String beta, String expected, boolean prerelease) {
    UpdateCheckResult result =
        windowsDiscovery(
                selection ->
                    signed(
                        official(
                            selection.channel == UpdateChannel.STABLE ? stable : beta,
                            selection.channel == UpdateChannel.BETA)))
            .discover(UpdateCheckSelection.of(UpdateChannel.BETA, UpdateSource.GITHUB, INSTALLED));

    assertEquals(UpdateCheckResult.Reason.OFFER, result.reason);
    assertEquals(expected, result.windowsPlan.manifest.releaseTag);
    assertEquals(prerelease, result.windowsPlan.manifest.prerelease);
    assertNull(result.stableCandidateFailure);
    assertNull(result.betaCandidateFailure);
  }

  @ParameterizedTest
  @EnumSource(UpdateChannel.class)
  void survivingCandidateDeterminesOfferNoUpdateAndNoPackage(UpdateChannel failed) {
    UpdateDiscovery.ManifestFetcher fetcher =
        selection -> {
          if (selection.channel == failed) {
            throw new IOException("unavailable candidate");
          }
          return signed(official(NEWER, selection.channel == UpdateChannel.BETA));
        };
    UpdateCheckSelection snapshot =
        UpdateCheckSelection.of(UpdateChannel.BETA, UpdateSource.GITHUB, INSTALLED);
    UpdateCheckResult offered = windowsDiscovery(fetcher).discover(snapshot);
    UpdateCheckResult equal =
        windowsDiscovery(fetcher)
            .discover(UpdateCheckSelection.of(UpdateChannel.BETA, UpdateSource.GITHUB, NEWER));
    UpdateCheckResult missing =
        new UpdateDiscovery(
                fetcher, List.of(new PackageUpdateAdapter(true, "linux", "x64", "opencl")))
            .discover(snapshot);

    assertEquals(UpdateCheckResult.Reason.OFFER, offered.reason);
    assertEquals(NEWER, offered.windowsPlan.manifest.releaseTag);
    assertEquals(UpdateCheckResult.Reason.NO_UPDATE, equal.reason);
    assertEquals(UpdateCheckResult.Reason.NO_PACKAGE, missing.reason);
    for (UpdateCheckResult result : List.of(offered, equal, missing)) {
      assertEquals(
          failed == UpdateChannel.STABLE ? UpdateCheckResult.FailureKind.FETCH : null,
          result.stableCandidateFailure);
      assertEquals(
          failed == UpdateChannel.BETA ? UpdateCheckResult.FailureKind.FETCH : null,
          result.betaCandidateFailure);
    }
  }

  @ParameterizedTest
  @EnumSource(UpdateChannel.class)
  void rejectsWrongIdentityAndInvalidVersionWithoutDiscardingOtherCandidate(UpdateChannel invalid) {
    for (boolean wrongIdentity : new boolean[] {true, false}) {
      UpdateCheckResult result =
          windowsDiscovery(
                  selection -> {
                    boolean beta = selection.channel == UpdateChannel.BETA;
                    if (selection.channel == invalid) {
                      return signed(
                          official(
                              wrongIdentity ? "next-2099-01-01.1" : "next-invalid",
                              wrongIdentity ? !beta : beta));
                    }
                    return signed(official(NEWER, beta));
                  })
              .discover(
                  UpdateCheckSelection.of(UpdateChannel.BETA, UpdateSource.GITHUB, INSTALLED));

      assertEquals(UpdateCheckResult.Reason.OFFER, result.reason);
      assertEquals(NEWER, result.windowsPlan.manifest.releaseTag);
      assertEquals(invalid == UpdateChannel.STABLE, result.windowsPlan.manifest.prerelease);
      assertEquals(
          UpdateCheckResult.FailureKind.INVALID_CANDIDATE,
          invalid == UpdateChannel.STABLE
              ? result.stableCandidateFailure
              : result.betaCandidateFailure);
    }
  }

  @Test
  void bothInvalidCandidatesFailRatherThanClaimingNoUpdate() {
    UpdateCheckResult result =
        windowsDiscovery(
                selection ->
                    signed(official("next-invalid", selection.channel == UpdateChannel.BETA)))
            .discover(UpdateCheckSelection.of(UpdateChannel.BETA, UpdateSource.GITHUB, INSTALLED));

    assertEquals(UpdateCheckResult.Reason.FAILURE, result.reason);
    assertEquals(UpdateCheckResult.FailureKind.INVALID_CANDIDATE, result.stableCandidateFailure);
    assertEquals(UpdateCheckResult.FailureKind.INVALID_CANDIDATE, result.betaCandidateFailure);
  }

  @ParameterizedTest
  @EnumSource(UpdateChannel.class)
  void highestVersionMissingPackageNeverFallsBackAcrossPlatforms(UpdateChannel highest) {
    UpdateDiscovery.ManifestFetcher fetcher =
        selection -> {
          JSONObject payload = SignedUpdateEnvelopeTest.validPayload();
          payload.put("releaseTag", selection.channel == highest ? NEWER : "next-2026-08-02.1");
          payload.put("prerelease", selection.channel == UpdateChannel.BETA);
          if (selection.channel == highest) {
            payload.getJSONArray("components").getJSONObject(0).put("platform", "linux");
            payload.getJSONArray("packages").getJSONObject(0).put("arch", "x64");
          }
          return signed(UpdateManifest.parse(payload));
        };
    UpdateCheckSelection snapshot =
        UpdateCheckSelection.of(UpdateChannel.BETA, UpdateSource.GITHUB, INSTALLED);

    assertEquals(
        UpdateCheckResult.Reason.NO_PACKAGE, windowsDiscovery(fetcher).discover(snapshot).reason);
    assertEquals(
        UpdateCheckResult.Reason.NO_PACKAGE, packageDiscovery(fetcher).discover(snapshot).reason);
  }

  @Test
  void subsequentSuccessfulCheckDoesNotRetainCandidateFailure() {
    AtomicInteger attempts = new AtomicInteger();
    UpdateDiscovery discovery =
        windowsDiscovery(
            selection -> {
              if (attempts.getAndIncrement() == 0) {
                throw new IOException("first candidate temporarily unavailable");
              }
              return signed(official(NEWER, selection.channel == UpdateChannel.BETA));
            });
    UpdateCheckSelection snapshot =
        UpdateCheckSelection.of(UpdateChannel.BETA, UpdateSource.GITHUB, INSTALLED);

    UpdateCheckResult first = discovery.discover(snapshot);
    assertEquals(UpdateCheckResult.FailureKind.FETCH, first.stableCandidateFailure);
    UpdateCheckResult second = discovery.discover(snapshot);
    assertEquals(UpdateCheckResult.Reason.OFFER, second.reason);
    assertNull(second.stableCandidateFailure);
    assertNull(second.betaCandidateFailure);
  }

  private static UpdateCheckSelection officialSelection(String installedVersion) {
    return UpdateCheckSelection.of(
        UpdateChannel.STABLE, UpdateSource.OFFICIAL_SITE, installedVersion);
  }

  private static UpdateDiscovery windowsDiscovery(UpdateDiscovery.ManifestFetcher fetcher) {
    return new UpdateDiscovery(
        fetcher,
        List.of(
            new WindowsUpdateAdapter(
                true, "opencl", InstalledUpdateState.empty(INSTALLED, "windows", "opencl"))));
  }

  private static UpdateDiscovery packageDiscovery(UpdateDiscovery.ManifestFetcher fetcher) {
    return new UpdateDiscovery(
        fetcher, List.of(new PackageUpdateAdapter(true, "macos", "arm64", "with-katago")));
  }

  private static UpdateManifest official(String tag, boolean prerelease) {
    JSONObject payload = SignedUpdateEnvelopeTest.validPayload();
    payload.put("releaseTag", tag);
    payload.put("prerelease", prerelease);
    return UpdateManifest.parse(payload);
  }

  private static UpdateManifestClient.FetchResult signed(UpdateManifest manifest) {
    return new UpdateManifestClient.FetchResult(manifest, "test://signed", true);
  }
}
