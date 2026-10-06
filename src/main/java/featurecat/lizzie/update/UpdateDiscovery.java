package featurecat.lizzie.update;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Synchronous manual update discovery. Callers supply one 更新检查选择快照 and receive one 更新检查结果. This
 * module does not start threads, touch Swing, or create UI copy.
 */
public final class UpdateDiscovery {
  interface ManifestFetcher {
    UpdateManifestClient.FetchResult fetch(UpdateCheckSelection selection) throws IOException;

    default UpdateManifestClient.FetchResult fetchSignedCandidate(
        UpdateCheckSelection snapshot, UpdateChannel candidate) throws IOException {
      return fetch(
          UpdateCheckSelection.of(candidate, UpdateSource.GITHUB, snapshot.installedVersion));
    }
  }

  interface PlatformAdapter {
    boolean supports(UpdateCheckSelection selection);

    UpdateCheckResult plan(UpdateCheckSelection selection, UpdateManifest manifest);
  }

  private final ManifestFetcher fetcher;
  private final List<PlatformAdapter> adapters;

  UpdateDiscovery(ManifestFetcher fetcher, List<PlatformAdapter> adapters) {
    this.fetcher = fetcher;
    this.adapters = adapters == null ? List.of() : List.copyOf(new ArrayList<>(adapters));
  }

  public static UpdateCheckResult check(UpdateCheckSelection selection) {
    return production().discover(selection);
  }

  static UpdateDiscovery production() {
    return new UpdateDiscovery(
        new SnapshotManifestFetcher(),
        List.of(new WindowsUpdateAdapter(), new PackageUpdateAdapter()));
  }

  UpdateCheckResult discover(UpdateCheckSelection selection) {
    UpdateCheckSelection snapshot =
        selection == null
            ? UpdateCheckSelection.of(UpdateChannel.STABLE, UpdateSource.OFFICIAL_SITE, null)
            : selection;
    if (!UpdateVersion.isPackagedRelease(snapshot.installedVersion)) {
      return UpdateCheckResult.unavailableBuild();
    }
    PlatformAdapter adapter;
    try {
      adapter = supportingAdapter(snapshot);
    } catch (RuntimeException e) {
      e.printStackTrace();
      return UpdateCheckResult.failure(UpdateCheckResult.FailureKind.ADAPTER);
    }
    if (adapter == null) {
      return UpdateCheckResult.unsupportedPlatform();
    }
    if (snapshot.channel == UpdateChannel.BETA) {
      Candidate stable = fetchCandidate(snapshot, UpdateChannel.STABLE);
      Candidate beta = fetchCandidate(snapshot, UpdateChannel.BETA);
      UpdateManifest winner = stable.manifest;
      if (beta.manifest != null
          && (winner == null
              || UpdateVersion.isNewerThan(beta.manifest.releaseTag, winner.releaseTag))) {
        winner = beta.manifest;
      }
      UpdateCheckResult result =
          winner == null
              ? UpdateCheckResult.failure(UpdateCheckResult.FailureKind.FETCH)
              : plan(snapshot, adapter, winner);
      return result.withCandidateFailures(stable.failure, beta.failure);
    }
    UpdateManifestClient.FetchResult fetched;
    try {
      fetched = fetcher.fetch(snapshot);
    } catch (IOException e) {
      return UpdateCheckResult.failure(UpdateCheckResult.FailureKind.FETCH);
    } catch (RuntimeException e) {
      e.printStackTrace();
      return UpdateCheckResult.failure(UpdateCheckResult.FailureKind.UNEXPECTED);
    }
    if (fetched == null || fetched.manifest == null) {
      return UpdateCheckResult.failure(UpdateCheckResult.FailureKind.FETCH);
    }
    UpdateManifest manifest = fetched.manifest;
    if (manifest.prerelease) {
      return UpdateCheckResult.noUpdate();
    }
    return plan(snapshot, adapter, manifest);
  }

  private Candidate fetchCandidate(UpdateCheckSelection snapshot, UpdateChannel role) {
    try {
      UpdateManifestClient.FetchResult fetched = fetcher.fetchSignedCandidate(snapshot, role);
      if (fetched == null || fetched.manifest == null) {
        return new Candidate(null, UpdateCheckResult.FailureKind.FETCH);
      }
      UpdateManifest manifest = fetched.manifest;
      if (!fetched.signatureVerified
          || manifest.schemaVersion != UpdateManifest.SUPPORTED_SCHEMA_VERSION
          || manifest.prerelease != (role == UpdateChannel.BETA)
          || !UpdateVersion.isPackagedRelease(manifest.releaseTag)) {
        return new Candidate(null, UpdateCheckResult.FailureKind.INVALID_CANDIDATE);
      }
      return new Candidate(manifest, null);
    } catch (IOException e) {
      return new Candidate(null, UpdateCheckResult.FailureKind.FETCH);
    } catch (RuntimeException e) {
      return new Candidate(null, UpdateCheckResult.FailureKind.INVALID_CANDIDATE);
    }
  }

  private record Candidate(UpdateManifest manifest, UpdateCheckResult.FailureKind failure) {}

  private UpdateCheckResult plan(
      UpdateCheckSelection snapshot, PlatformAdapter adapter, UpdateManifest manifest) {
    if (!UpdateVersion.isNewerThan(manifest.releaseTag, snapshot.installedVersion)) {
      return UpdateCheckResult.noUpdate();
    }
    try {
      UpdateCheckResult planned = adapter.plan(snapshot, manifest);
      return planned == null
          ? UpdateCheckResult.failure(UpdateCheckResult.FailureKind.ADAPTER)
          : planned;
    } catch (RuntimeException e) {
      e.printStackTrace();
      return UpdateCheckResult.failure(UpdateCheckResult.FailureKind.ADAPTER);
    }
  }

  private PlatformAdapter supportingAdapter(UpdateCheckSelection snapshot) {
    for (PlatformAdapter adapter : adapters) {
      if (adapter != null && adapter.supports(snapshot)) {
        return adapter;
      }
    }
    return null;
  }

  private static final class SnapshotManifestFetcher implements ManifestFetcher {
    @Override
    public UpdateManifestClient.FetchResult fetch(UpdateCheckSelection selection)
        throws IOException {
      return new UpdateManifestClient(selection.channel, selection.effectiveSource).fetchLatest();
    }

    @Override
    public UpdateManifestClient.FetchResult fetchSignedCandidate(
        UpdateCheckSelection snapshot, UpdateChannel candidate) throws IOException {
      return new UpdateManifestClient(
              UpdateManifestClient.signedCandidateUrlsFor(candidate), null, candidate)
          .fetchSigned();
    }
  }
}
