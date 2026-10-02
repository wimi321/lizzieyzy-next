package featurecat.lizzie.teacher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** A bounded correction pass; unverified coordinates are never saved as a completed lesson. */
final class GroundedCommentaryClient implements CommentaryClient {
  private final CommentaryClient delegate;
  private final List<TeacherEvidence.Position> positions;

  GroundedCommentaryClient(CommentaryClient delegate, List<TeacherEvidence.Position> positions) {
    this.delegate = delegate;
    this.positions = List.copyOf(positions);
  }

  @Override
  public String stream(
      List<TeacherLlmClient.Message> messages,
      TeacherLlmClient.Cancellation cancellation,
      Consumer<String> onText)
      throws IOException, InterruptedException {
    List<TeacherLlmClient.Message> input = new ArrayList<>(messages);
    for (int attempt = 0; attempt < 2; attempt++) {
      if (cancellation.isCancelled()) throw new CancellationException();
      String result = delegate.stream(input, cancellation, ignored -> {});
      if (cancellation.isCancelled()) throw new CancellationException();
      TeacherVerifier.Result check = TeacherVerifier.verify(result, positions);
      if (check.ok()) {
        onText.accept(result);
        return result;
      }
      if (attempt == 0) {
        input.add(new TeacherLlmClient.Message("assistant", result));
        input.add(
            new TeacherLlmClient.Message(
                "user",
                "Correct the unsupported claims below using ONLY the original frozen evidence. "
                    + "Do not add moves or substitute arbitrary coordinates. Remove unsupported claims, "
                    + "retain useful board reasoning, and return the complete corrected lesson in the "
                    + "requested language.\n"
                    + String.join("\n", check.violations.stream().limit(8).toList())));
      }
    }
    throw new IOException(
        TeacherStrings.get(
            "Teacher.learning.unverified",
            "The explanation could not be verified against this position. Nothing was saved."));
  }
}
