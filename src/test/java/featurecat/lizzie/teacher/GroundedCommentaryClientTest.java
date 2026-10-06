package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GroundedCommentaryClientTest {
  @Test
  void repairsOnceAndDoesNotStreamInvalidDraft() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    CommentaryClient source =
        (messages, cancellation, onText) -> {
          String answer = calls.incrementAndGet() == 1 ? "Play T1" : "A18 captures A19.";
          onText.accept(answer);
          if (calls.get() == 2)
            assertTrue(
                messages.get(messages.size() - 1).content.contains("Unsupported coordinate T1"));
          return answer;
        };
    var evidence = TeacherEvidence.current(TeacherBoardContextTest.position()).orElseThrow();
    StringBuilder displayed = new StringBuilder();
    String answer =
        new GroundedCommentaryClient(source, List.of(evidence))
            .stream(
                List.of(new TeacherLlmClient.Message("user", "frozen evidence")),
                new TeacherLlmClient.Cancellation(),
                displayed::append);
    assertEquals("A18 captures A19.", answer);
    assertEquals(answer, displayed.toString());
    assertEquals(2, calls.get());
  }

  @Test
  void stopsAfterSecondInvalidAnswerWithoutDeliveringText() {
    AtomicInteger calls = new AtomicInteger();
    CommentaryClient source =
        (messages, cancellation, onText) -> {
          calls.incrementAndGet();
          return "Play T1";
        };
    var evidence = TeacherEvidence.current(TeacherBoardContextTest.position()).orElseThrow();
    StringBuilder displayed = new StringBuilder();
    assertThrows(
        IOException.class,
        () ->
            new GroundedCommentaryClient(source, List.of(evidence))
                .stream(List.of(), new TeacherLlmClient.Cancellation(), displayed::append));
    assertEquals(2, calls.get());
    assertEquals("", displayed.toString());
  }

  @Test
  void cancellationNeverStartsARepair() {
    AtomicInteger calls = new AtomicInteger();
    var cancellation = new TeacherLlmClient.Cancellation();
    CommentaryClient source =
        (messages, cancel, onText) -> {
          calls.incrementAndGet();
          cancel.cancel();
          return "Play T1";
        };
    var evidence = TeacherEvidence.current(TeacherBoardContextTest.position()).orElseThrow();
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () ->
            new GroundedCommentaryClient(source, List.of(evidence))
                .stream(List.of(), cancellation, ignored -> fail()));
    assertEquals(1, calls.get());
  }
}
